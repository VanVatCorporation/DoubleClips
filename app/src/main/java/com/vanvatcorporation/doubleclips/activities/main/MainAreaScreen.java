package com.vanvatcorporation.doubleclips.activities.main;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.gson.Gson;
import com.vanvatcorporation.doubleclips.BuildConfig;
import com.vanvatcorporation.doubleclips.FFmpegEdit;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.DebugActivity;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.activities.MainActivity;
import com.vanvatcorporation.doubleclips.activities.editing.BaseEditSpecificAreaScreen;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.ext.rajawali.RajawaliExample;
import com.vanvatcorporation.doubleclips.helper.CompressionHelper;
import com.vanvatcorporation.doubleclips.helper.DateHelper;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.helper.IOImageHelper;
import com.vanvatcorporation.doubleclips.helper.ImageHelper;
import com.vanvatcorporation.doubleclips.helper.StringFormatHelper;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.File;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class MainAreaScreen extends BaseAreaScreen {
    public ProjectData currentExportingProject;



    public ActivityResultLauncher<Intent> filePickerLauncher;
    public ActivityResultLauncher<Intent> fileCreatorLauncher;


    public List<ProjectData> projectList;
    public RecyclerView projectListView;
    public ProjectDataAdapter projectAdapter;
    public SwipeRefreshLayout projectSwipeRefreshLayout;

    public TextView titleText;
    public Button addNewProjectButton;



    public MainAreaScreen(Context context) {
        super(context);
    }

    public MainAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MainAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public MainAreaScreen(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }


    @Override
    public void init() {
        super.init();

        projectListView = findViewById(R.id.projectList);
        //progressBarFetchingBook = view.findViewById(R.id.progressBarFetchingBook);
        projectSwipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        titleText = findViewById(R.id.title);
        addNewProjectButton = findViewById(R.id.addProjectButton);


        titleText.setOnClickListener(v -> {
            Intent intent = new Intent(getContext(), DebugActivity.class);
            getContext().startActivity(intent);
        });

        titleText.setOnLongClickListener(v -> {
            Intent intent = new Intent(getContext(), RajawaliExample.class);
            getContext().startActivity(intent);
            return true;
        });

        addNewProjectButton.setOnClickListener(v -> {
            //pickingContent();

            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());

            // Inflate your custom layout
            LayoutInflater inflater = LayoutInflater.from(getContext());
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



        projectListView.setLayoutManager(new LinearLayoutManager(getContext()));

        projectList = new ArrayList<>();
        projectAdapter = new ProjectDataAdapter(getContext(), projectList);
        projectListView.setAdapter(projectAdapter);

        projectSwipeRefreshLayout.setOnRefreshListener(this::reloadingProject);


    }




    public void addNewProject() {
        String projectPath = IOHelper.getNextIndexPathInFolder(getContext(), Constants.DEFAULT_PROJECT_DIRECTORY(getContext()), "project_", "", false);

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

            File previewDir = new File(IOHelper.CombinePath(projectPath, Constants.DEFAULT_PREVIEW_CLIP_DIRECTORY));
            if(!previewDir.exists())
                previewDir.mkdirs();

            enterEditing(getContext(), data);
        }
    }

    public void reloadingProject()
    {
        int beforeRange = projectList.size();
        projectList.clear();
        projectAdapter.notifyItemRangeRemoved(0, beforeRange);
        String projectsFolderPath = IOHelper.CombinePath(IOHelper.getPersistentDataPath(getContext()), "projects");
        File file = new File(projectsFolderPath);
        if(file.listFiles() == null) {
            projectSwipeRefreshLayout.setRefreshing(false);
            return;
        }
        for (File directory : Objects.requireNonNull(file.listFiles())) {
            if(directory.isDirectory())
            {
                ProjectData data = ProjectData.loadProperties(getContext(), directory.getAbsolutePath());

                if(data != null)
                {
                    projectList.add(data);
                    projectAdapter.notifyItemInserted(projectList.size() - 1);
                }
            }

        }

        projectSwipeRefreshLayout.setRefreshing(false);
    }
    public static void enterEditing(Context context, MainAreaScreen.ProjectData projectItem)
    {
        Intent intent = new Intent(context, EditingActivity.class);
        intent.putExtra("ProjectProperties", projectItem);
        context.startActivity(intent);
    }







    void importContent()
    {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/zip");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePickerLauncher.launch(Intent.createChooser(intent, "Select Import Zip"));
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
            //holder.projectDatetime.setText(new Date(projectItem.getProjectTimestamp()).toString());
            holder.projectDatetime.setText(DateHelper.convertTimestampToDateTimeStringFormat(projectItem.getProjectTimestamp()));
            holder.projectSize.setText(StringFormatHelper.smartRound((projectItem.getProjectSize() / 1024d / 1024d), 2, true) + "MB");
            holder.projectDuration.setText(DateHelper.convertTimestampToHHMMSSFormat(projectItem.getProjectDuration()));

            if(!IOHelper.isFileExist(IOHelper.CombinePath(projectItem.getProjectPath(), "preview.png")))
                IOImageHelper.SaveFileAsPNGImage(context, IOHelper.CombinePath(projectItem.getProjectPath(), "preview.png"), ImageHelper.createBitmapFromDrawable(AppCompatResources.getDrawable(context, R.drawable.logo)));
            Bitmap iconBitmap = IOImageHelper.LoadFileAsPNGImage(context, IOHelper.CombinePath(projectItem.getProjectPath(), "preview.png"));
            if(iconBitmap != null)
            {
                holder.projectPreview.setImageBitmap((iconBitmap));
            }
            holder.projectTitle.setOnLongClickListener(v -> {
                EditProjectTitle(projectItem);
                return false;
            });
            holder.projectTitle.setOnClickListener(v -> {
                holder.wholeView.performClick();
            });
            holder.moreButton.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(context, v);
                popup.getMenuInflater().inflate(R.menu.menu_cpn_project_element_more, popup.getMenu());

                popup.setOnMenuItemClickListener(item -> {
                    if(item.getItemId() == R.id.action_edit)
                    {
                        EditProjectTitle(projectItem);
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
                                    int index = projectList.indexOf(projectItem);
                                    projectList.remove(projectItem);
                                    projectAdapter.notifyItemRemoved(index);

                                })

                                // A null listener allows the button to dismiss the dialog and take no further action.
                                .setNegativeButton(android.R.string.cancel, null)
                                .setIconAttribute(android.R.attr.alertDialogIcon)
                                .show();
                        return true;
                    }
                    else if(item.getItemId() == R.id.action_share)
                    {
                        // Add ffmpeg cmd for ready-to-use rendering in other platform. Can be made into template
                        EditingActivity.VideoSettings videoSettings = new EditingActivity.VideoSettings(1920, 1080, 30, 18, Integer.MAX_VALUE,
                                EditingActivity.VideoSettings.FfmpegPreset.MEDIUM,
                                EditingActivity.VideoSettings.FfmpegTune.ZEROLATENCY);
                        EditingActivity.Timeline timeline = EditingActivity.Timeline.loadRawTimeline(context, projectItem);
                        String ffmpegCmdPath = IOHelper.CombinePath(projectItem.getProjectPath(), "ffmpegCmd.txt");
                        IOHelper.writeToFile(context, ffmpegCmdPath, FFmpegEdit.generateCmdFull(context, videoSettings, timeline, projectItem, false));


                        zippingProject(projectItem);




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

                        reloadingProject();


                        return true;
                    }
                    return false;
                });

                popup.show();
            });


            MainActivity.MigrationHelper.migrate(projectItem);


            holder.wholeView.setOnClickListener(v -> {
                enterEditing(context, projectItem);
            });
            holder.wholeView.setOnLongClickListener(v -> {
                holder.moreButton.performClick();
                return true;
            });
        }

        private void EditProjectTitle(ProjectData projectItem) {

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

                reloadingProject();

                dialog.dismiss();
            });

            cancelButton.setOnClickListener(vcan -> {
                // Just dismiss the dialog
                dialog.dismiss();
            });

            // Show the dialog
            dialog.show();


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
}
