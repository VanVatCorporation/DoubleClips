package com.vanvatcorporation.doubleclips.activities;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.gson.Gson;
import com.vanvatcorporation.doubleclips.BuildConfig;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.UncaughtExceptionHandler;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.ext.rajawali.RajawaliExample;
import com.vanvatcorporation.doubleclips.helper.CompressionHelper;
import com.vanvatcorporation.doubleclips.helper.DateHelper;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.helper.IOImageHelper;
import com.vanvatcorporation.doubleclips.helper.StringFormatHelper;
import com.vanvatcorporation.doubleclips.impl.AppCompatActivityImpl;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

public class MainActivity extends AppCompatActivityImpl {
    Button addNewProjectButton;



    List<ProjectData> projectList;
    RecyclerView projectListView;
    ProjectDataAdapter projectAdapter;
    SwipeRefreshLayout projectSwipeRefreshLayout;


    ProjectData currentExportingProject;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.layout_main);



        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler(Thread.getDefaultUncaughtExceptionHandler(), this));


//        pickButton = findViewById(R.id.button);
//        pickButton.setOnClickListener(v -> pickingContent());
//
//        trimButton = findViewById(R.id.button2);
//        trimButton.setOnClickListener(v -> FFmpegMethod.trimVideo(this, pathText.getText().toString(), outputText.getText().toString(), 0, 5));
//
//        pathText = findViewById(R.id.pathText);
//        outputText = findViewById(R.id.outputText);
//
//        errorText = findViewById(R.id.textView);


        // Check and request permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
        }




        findViewById(R.id.title).setOnClickListener(v -> {
            Intent intent = new Intent(this, DebugActivity.class);
            startActivity(intent);
        });

        findViewById(R.id.title).setOnLongClickListener(v -> {
            Intent intent = new Intent(this, RajawaliExample.class);
            startActivity(intent);
            return true;
        });





        addNewProjectButton = findViewById(R.id.addProjectButton);
        addNewProjectButton.setOnClickListener(v -> {
            //pickingContent();

            AlertDialog.Builder builder = new AlertDialog.Builder(this);

            // Inflate your custom layout
            LayoutInflater inflater = LayoutInflater.from(this);
            View dialogView = inflater.inflate(R.layout.popup_add_project, null);
            builder.setView(dialogView);

            // Get references to the EditText and Buttons in your custom layout
            ImageView newButton = dialogView.findViewById(R.id.newButton);
            ImageView importButton = dialogView.findViewById(R.id.importButton);

            // Create the AlertDialog
            AlertDialog dialog = builder.create();

            // Set button click listeners
            newButton.setOnClickListener(vok -> {
                addNewProject();
                dialog.dismiss();
            });

            importButton.setOnClickListener(vcan -> {

                importContent();

                // Just dismiss the dialog
                dialog.dismiss();
            });

            // Show the dialog
            dialog.show();
        });



        //Main View
        projectListView = findViewById(R.id.projectList);
        //progressBarFetchingBook = view.findViewById(R.id.progressBarFetchingBook);
        projectSwipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        projectListView.setLayoutManager(new LinearLayoutManager(this));

        projectList = new ArrayList<>();
        projectAdapter = new ProjectDataAdapter(this, projectList);
        projectListView.setAdapter(projectAdapter);

        projectSwipeRefreshLayout.setOnRefreshListener(this::reloadingProject);


        reloadingProject();
    }



    private ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    String inputPath = getPath(this, uri); // Use your helper

                    CompressionHelper.unzipFolder(this, getContentResolver(), uri, Constants.DEFAULT_PROJECT_DIRECTORY(this));
                }
            }
    );
    private ActivityResultLauncher<Intent> fileCreatorLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();

                    if(currentExportingProject == null) return;
                    CompressionHelper.zipFolder(this, currentExportingProject.projectPath, getContentResolver(), uri);
                    currentExportingProject = null;
                }
            }
    );





    void importContent()
    {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/zip");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePickerLauncher.launch(Intent.createChooser(intent, "Select Import Zip"));
    }


    void addNewProject() {
        String projectPath = IOHelper.getNextIndexPathInFolder(this, Constants.DEFAULT_PROJECT_DIRECTORY(this), "project_", "", false);

        String projectName = projectPath.substring(projectPath.lastIndexOf("/") + 1);
        File file = new File(projectPath);
        if(file.mkdirs()) //If directory created operation was success
        {
            ProjectData data = new ProjectData(projectPath, projectName, new Date().getTime(), 31122007, 8032007);
            data.version = BuildConfig.VERSION_NAME;
            projectList.add(data);
            projectAdapter.notifyItemInserted(projectList.size() - 1);

            File basicDir = new File(IOHelper.CombinePath(projectPath, Constants.DEFAULT_CLIP_TEMP_DIRECTORY, "frames"));
            if(!basicDir.exists())
                basicDir.mkdirs();

            enterEditing(this, data);
        }
    }

    void reloadingProject()
    {
        projectList.clear();
        projectAdapter.notifyDataSetChanged();
        String projectsFolderPath = IOHelper.CombinePath(IOHelper.getPersistentDataPath(this), "projects");
        File file = new File(projectsFolderPath);
        if(file.listFiles() == null) return;
        for (File directory : Objects.requireNonNull(file.listFiles())) {
            if(directory.isDirectory())
            {
                ProjectData data = ProjectData.loadProperties(this, directory.getAbsolutePath());

                if(data != null)
                {
                    projectList.add(data);
                    projectAdapter.notifyItemInserted(projectList.size() - 1);
                }
            }

        }

        projectSwipeRefreshLayout.setRefreshing(false);
    }
    public void zippingProject(ProjectData data)
    {
        currentExportingProject = data;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("application/zip");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, "export_" + data.projectTitle + ".zip");
        fileCreatorLauncher.launch(Intent.createChooser(intent, "Select Export"));
    }





    public static String getPath(Context context, Uri uri) {
        String[] projection = { MediaStore.Video.Media.DATA };
        Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null);
        if (cursor != null) {
            int column_index = cursor.getColumnIndexOrThrow(projection[0]);
            cursor.moveToFirst();
            String path = cursor.getString(column_index);
            cursor.close();
            return path;
        }
        return uri.getPath();
    }
    public static void enterEditing(Context context, ProjectData projectItem)
    {
        Intent intent = new Intent(context, EditingActivity.class);
        intent.putExtra("ProjectProperties", projectItem);
        context.startActivity(intent);
    }





    public static class ProjectData implements Serializable {

        public String version;
        private String projectPath;
        private String projectTitle;
        private long projectTimestamp;
        private long projectSize;
        private long projectDuration;

        public ProjectData(String projectPath, String projectTitle, long projectTimestamp, long projectSize, long projectDuration) {
            this.projectPath = projectPath;
            this.projectTitle = projectTitle;
            this.projectTimestamp = projectTimestamp;
            this.projectSize = projectSize;
            this.projectDuration = projectDuration;
        }


        public String getProjectPath() {
            return projectPath;
        }
        public String getProjectTitle() {
            return projectTitle;
        }
        public long getProjectTimestamp() {
            return projectTimestamp;
        }
        public long getProjectSize() {
            return projectSize;
        }
        public long getProjectDuration() {
            return projectDuration;
        }

        public void setProjectPath(String projectPath) {
            this.projectPath = projectPath;
        }
        public void setProjectTitle(Context context, String projectTitle, boolean changeFilePath) {
            // Provide a fallback if the operation failed
            String oldProjectTitle = this.projectTitle;
            String oldDir = this.getProjectPath();

            this.projectTitle = projectTitle;

            if(!changeFilePath) return;

            // Change the path along the way
            String newDir = IOHelper.CombinePath(Constants.DEFAULT_PROJECT_DIRECTORY(context), projectTitle);
            File newName = new File(newDir);
            if(!new File(getProjectPath()).renameTo(newName)) {
                this.projectPath = oldProjectTitle;
                LoggingManager.LogToToast(context, "Rename failed (1)");
                return;
            }
            setProjectPath(newDir);
            if(!new File(getProjectPath()).exists()) {
                this.projectPath = oldProjectTitle;
                setProjectPath(oldDir);
                LoggingManager.LogToToast(context, "Rename failed (2)");
                return;
            }

            // Re-update properties after renaming the entire folder
            savePropertiesAtProject(context);
        }
        public void setProjectTimestamp(long projectTimestamp) {
            this.projectTimestamp = projectTimestamp;
        }
        public void setProjectSize(long projectSize) {
            this.projectSize = projectSize;
        }
        public void setProjectDuration(long projectDuration) {
            this.projectDuration = projectDuration;
        }





        public void savePropertiesAtProject(Context context)
        {
            IOHelper.writeToFile(context, IOHelper.CombinePath(getProjectPath(), Constants.DEFAULT_PROJECT_PROPERTIES_FILENAME), new Gson().toJson(this));
        }
        public void loadPropertiesFromProject(Context context)
        {
            ProjectData data = loadProperties(context, getProjectPath());
            this.version = data.version;
            this.projectPath = data.projectPath;
            this.projectTitle = data.projectTitle;
            this.projectTimestamp = data.projectTimestamp;
            this.projectSize = data.projectSize;
            this.projectDuration = data.projectDuration;
        }
        public static ProjectData loadProperties(Context context, String path)
        {
            return new Gson().fromJson(IOHelper.readFromFile(context, IOHelper.CombinePath(path, Constants.DEFAULT_PROJECT_PROPERTIES_FILENAME)), ProjectData.class);
        }



        @NonNull
        @Override
        protected Object clone() {
            return new ProjectData(projectPath, projectTitle, projectTimestamp, projectSize, projectDuration);
        }
    }
    public class ProjectDataAdapter extends RecyclerView.Adapter<ProjectDataViewHolder>
    {

        private List<ProjectData> projectList;
        private Context context;

        // Constructor
        public ProjectDataAdapter(Context context, List<ProjectData> projectList) {
            this.context = context;
            this.projectList = projectList;
        }
        @Override
        public ProjectDataViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.cpn_project_element, parent, false);
            return new ProjectDataViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ProjectDataViewHolder holder, int position) {

            ProjectData projectItem = projectList.get(position);

            holder.projectTitle.setText(projectItem.getProjectTitle());
            holder.projectDatetime.setText(new Date(projectItem.getProjectTimestamp()).toString());
            holder.projectSize.setText(StringFormatHelper.smartRound((projectItem.getProjectSize() / 1024d / 1024d), 2, true) + "MB");
            holder.projectDuration.setText(DateHelper.convertTimestampToHHMMSSFormat(projectItem.getProjectDuration()));

            Bitmap iconBitmap = IOImageHelper.LoadFileAsPNGImage(context, IOHelper.CombinePath(projectItem.getProjectPath(), "preview.png"));
            if(iconBitmap != null)
            {
                holder.projectPreview.setImageBitmap((iconBitmap));
            }
            holder.moreButton.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(context, v);
                popup.getMenuInflater().inflate(R.menu.menu_cpn_project_element_more, popup.getMenu());

                popup.setOnMenuItemClickListener(item -> {
                    if(item.getItemId() == R.id.action_edit)
                    {
                        AlertDialog.Builder builder = new AlertDialog.Builder(context);

                        // Inflate your custom layout
                        LayoutInflater inflater = LayoutInflater.from(context);
                        View dialogView = inflater.inflate(R.layout.popup_edit_project_title, null);
                        builder.setView(dialogView);

                        // Get references to the EditText and Buttons in your custom layout
                        EditText editText = dialogView.findViewById(R.id.directoryText);
                        Button okButton = dialogView.findViewById(R.id.okButton);
                        Button cancelButton = dialogView.findViewById(R.id.cancelButton);

                        // Create the AlertDialog
                        AlertDialog dialog = builder.create();
                        editText.setText(projectItem.getProjectTitle());

                        // Set button click listeners
                        okButton.setOnClickListener(vok -> {
                            projectItem.setProjectTitle(context, editText.getText().toString(), true);


                            dialog.dismiss();
                        });

                        cancelButton.setOnClickListener(vcan -> {
                            // Just dismiss the dialog
                            dialog.dismiss();
                        });

                        // Show the dialog
                        dialog.show();

                        return true;
                    }
                    else if(item.getItemId() == R.id.action_delete)
                    {
                        new AlertDialog.Builder(context)
                                .setTitle(context.getString(R.string.alert_delete_project_confirmation_title))
                                .setMessage(context.getString(R.string.alert_delete_project_confirmation_description))

                                // Specifying a listener allows you to take an action before dismissing the dialog.
                                // The dialog is automatically dismissed when a dialog button is clicked.
                                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                    // Continue with delete operation
                                    IOHelper.deleteDir(projectItem.projectPath);
                                    projectList.remove(projectItem);

                                })

                                // A null listener allows the button to dismiss the dialog and take no further action.
                                .setNegativeButton(android.R.string.cancel, null)
                                .setIconAttribute(android.R.attr.alertDialogIcon)
                                .show();
                        return true;
                    }
                    else if(item.getItemId() == R.id.action_share)
                    {
                        MainActivity.this.zippingProject(projectItem);




                        return true;
                    }
                    else if(item.getItemId() == R.id.action_upload)
                    {

                        return true;
                    }
                    else if(item.getItemId() == R.id.action_clone)
                    {
                        String projectPath = IOHelper.CombinePath(projectItem.projectPath + "_clone");
                        String oldProjectPath = projectItem.projectPath;



                        IOHelper.copyDir(context, oldProjectPath, projectPath);

                        // Re-update properties after renaming the entire folder
                        ProjectData data = ProjectData.loadProperties(context, projectPath);
                        data.setProjectPath(projectPath);
                        data.setProjectTimestamp(new Date().getTime());
                        data.setProjectTitle(context, data.getProjectTitle() + "_clone", false);

                        data.savePropertiesAtProject(context);


                        return true;
                    }
                    return false;
                });

                popup.show();
            });


            MigrationHelper.migrate(projectItem);


            holder.wholeView.setOnClickListener(v -> {
                enterEditing(context, projectItem);
            });
            holder.wholeView.setOnLongClickListener(v -> {
                holder.moreButton.performClick();
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return projectList.size();
        }
    }
    public static class ProjectDataViewHolder extends RecyclerView.ViewHolder {
        TextView projectTitle, projectDatetime, projectSize, projectDuration;
        ImageView projectPreview;
        ImageButton moreButton;
        View wholeView;
        public ProjectDataViewHolder(@NonNull View itemView) {
            super(itemView);
            wholeView = itemView;

            projectTitle = itemView.findViewById(R.id.titleText);
            projectDatetime = itemView.findViewById(R.id.dateText);
            projectSize = itemView.findViewById(R.id.sizeText);
            projectDuration = itemView.findViewById(R.id.durationText);
            projectPreview = itemView.findViewById(R.id.previewImage);

            moreButton = itemView.findViewById(R.id.moreButton);
        }
    }


    public static class MigrationHelper {
        public static void migrate(ProjectData data)
        {
            // List some critical changes need to be apply e.g a null field that wasn't exist until
            // the newer version come out, and loading it in the new version crashes the app
            if(data.version == null)
                data.version = BuildConfig.VERSION_NAME;
            switch (data.version)
            {
                case "1.0.0":
                    break;
            }
        }
    }

}
