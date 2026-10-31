package com.vanvatcorporation.doubleclips.activities.editing;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.TypedArray;
import android.text.format.Formatter;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.vanvatcorporation.doubleclips.FFmpegEdit;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.activities.main.MainAreaScreen;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.helper.AlgorithmHelper;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.helper.IOImageHelper;
import com.vanvatcorporation.doubleclips.helper.ImageHelper;
import com.vanvatcorporation.doubleclips.helper.MimeHelper;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.File;
import java.io.Serializable;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;

public class ProjectFilesEditSpecificAreaScreen extends BaseEditSpecificAreaScreen {
    public EditingActivity activityInstance;
    public MainAreaScreen.ProjectData properties;

    public List<ProjectFilesData> projectFilesList;
    public RecyclerView projectFilesRecyclerView;
    public ProjectFilesAdapter projectFilesAdapter;
    public SwipeRefreshLayout projectFilesSwipeRefreshLayout;


    public EditText hexColorField;

    /** Every file of the project's Clips folder; {@link #projectFilesList} is what the open filter shows of them. */
    private final List<ProjectFilesData> allFiles = new ArrayList<>();
    private enum Filter { ALL("All"), VIDEO("Video"), IMAGE("Image"), AUDIO("Audio"), UNUSED("Unused");
        final String title;
        Filter(String title) { this.title = title; }
    }
    private Filter filter = Filter.ALL;
    private final List<TextView> filterChips = new ArrayList<>();



    public ProjectFilesEditSpecificAreaScreen(Context context) {
        super(context);
    }

    public ProjectFilesEditSpecificAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ProjectFilesEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ProjectFilesEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void init()
    {
        super.init();

        hexColorField = findViewById(R.id.hexColorField);

        projectFilesRecyclerView = findViewById(R.id.projectFilesRecyclerView);
        projectFilesSwipeRefreshLayout = findViewById(R.id.projectFilesSwipeRefreshLayout);


        GridLayoutManager layoutManager = new GridLayoutManager(getContext(), 3);
        projectFilesRecyclerView.setLayoutManager(layoutManager);


        projectFilesList = new ArrayList<>();
        projectFilesAdapter = new ProjectFilesAdapter(getContext(), projectFilesList);
        projectFilesRecyclerView.setAdapter(projectFilesAdapter);

        projectFilesSwipeRefreshLayout.setOnRefreshListener(this::reloadingProject);

        buildFilterChips();
        findViewById(R.id.importFilesButton).setOnClickListener(v -> {
            if (activityInstance != null) activityInstance.importFilesToProject();
        });
        findViewById(R.id.deleteUnusedButton).setOnClickListener(v -> confirmDeleteUnused());

        findViewById(R.id.createButton).setOnClickListener(v -> {
            FFmpegEdit.generateSolidColorImage(getContext(), IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_CLIP_DIRECTORY), hexColorField.getText().toString());
            postDelayed(this::reloadingProject, 1500); // the image is made by FFmpeg in the background: show it once it is there
        });

        onClose.add(() -> {
            hexColorField.clearFocus();
        });
        onOpen.add(() -> {
            projectFilesSwipeRefreshLayout.setRefreshing(true);
            reloadingProject();
        });

        animationScreen = AnimationScreen.ToBottom;
    }
    public void reloadingProject()
    {
        allFiles.clear();
        projectFilesList.clear();
        projectFilesAdapter.notifyDataSetChanged();

        fetchClipsFolder();
    }

    // ---- filters ---------------------------------------------------------------------------------

    private void buildFilterChips() {
        LinearLayout row = findViewById(R.id.projectFilesFilterRow);
        for (final Filter f : Filter.values()) {
            TextView chip = new TextView(getContext());
            chip.setText(f.title);
            chip.setTextSize(14f);
            chip.setGravity(Gravity.CENTER);
            float d = getResources().getDisplayMetrics().density;
            chip.setPadding(Math.round(12 * d), Math.round(8 * d), Math.round(12 * d), Math.round(8 * d));
            chip.setOnClickListener(v -> {
                filter = f;
                applyFilter();
            });
            filterChips.add(chip);
            row.addView(chip);
        }
        styleFilterChips();
    }

    private void styleFilterChips() {
        TypedArray a = getContext().obtainStyledAttributes(new int[]{android.R.attr.textColorSecondary});
        int secondary = a.getColor(0, 0xFF888888);
        a.recycle();
        for (int i = 0; i < filterChips.size(); i++) {
            boolean selected = Filter.values()[i] == filter;
            filterChips.get(i).setTextColor(selected ? getContext().getColor(R.color.ios_blue) : secondary);
            filterChips.get(i).setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    /** What each file is used by (clips on the timeline), then the list the open filter shows. */
    private void applyFilter() {
        Map<String, Integer> usage = activityInstance != null ? activityInstance.projectFileUsage() : new java.util.HashMap<>();
        projectFilesList.clear();
        for (ProjectFilesData file : allFiles) {
            Integer n = usage.get(file.fileTitle);
            file.usedBy = n == null ? 0 : n;
            boolean show;
            switch (filter) {
                case VIDEO: show = file.kind == ProjectFilesData.Kind.VIDEO; break;
                case IMAGE: show = file.kind == ProjectFilesData.Kind.IMAGE; break;
                case AUDIO: show = file.kind == ProjectFilesData.Kind.AUDIO; break;
                case UNUSED: show = file.usedBy == 0; break;
                default: show = true; break;
            }
            if (show) projectFilesList.add(file);
        }
        projectFilesAdapter.notifyDataSetChanged();
        styleFilterChips();
    }

    // ---- delete unused ---------------------------------------------------------------------------

    /** Files no clip uses (and nothing else in the timeline mentions): what "Delete unused files" removes. */
    private List<ProjectFilesData> unusedFiles() {
        List<ProjectFilesData> unused = new ArrayList<>();
        if (activityInstance == null) return unused;
        Map<String, Integer> usage = activityInstance.projectFileUsage();
        List<String> candidates = new ArrayList<>();
        for (ProjectFilesData file : allFiles) {
            if (usage.get(file.fileTitle) == null) candidates.add(file.fileTitle);
        }
        java.util.Set<String> mentioned = activityInstance.projectFilesMentionedInTimeline(candidates);
        for (ProjectFilesData file : allFiles) {
            if (candidates.contains(file.fileTitle) && !mentioned.contains(file.fileTitle)) unused.add(file);
        }
        return unused;
    }

    private void confirmDeleteUnused() {
        final List<ProjectFilesData> unused = unusedFiles();
        if (unused.isEmpty()) {
            LoggingManager.LogToToast(getContext(), getContext().getString(R.string.every_file_is_used_by_a_clip));
            return;
        }
        long bytes = 0;
        for (ProjectFilesData file : unused) bytes += file.bytes;
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.delete_unused_files_2)
                .setMessage((unused.size() == 1 ? "1 file is" : unused.size() + " files are") + " not used by any clip. Deleting "
                        + (unused.size() == 1 ? "it" : "them") + " frees " + Formatter.formatShortFileSize(getContext(), bytes)
                        + " and can't be undone.")
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    for (ProjectFilesData file : unused) deleteFileFromProject(file.fileTitle);
                    reloadingProject();
                    LoggingManager.LogToToast(getContext(), "Freed " + Formatter.formatShortFileSize(getContext(), unusedBytes(unused)));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static long unusedBytes(List<ProjectFilesData> files) {
        long total = 0;
        for (ProjectFilesData file : files) total += file.bytes;
        return total;
    }

    /** The file and its preview copy. */
    private void deleteFileFromProject(String fileName) {
        IOHelper.deleteFile(IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_CLIP_DIRECTORY, fileName));
        IOHelper.deleteFile(IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_PREVIEW_CLIP_DIRECTORY, fileName));
    }

    public void fetchClipsFolder()
    {
        if(properties == null) {
            projectFilesSwipeRefreshLayout.setRefreshing(false);
            return;
        }

        File file = new File(IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_CLIP_DIRECTORY));
        if (file.isDirectory()) {
            for (String child : Objects.requireNonNull(file.list())) {

                String childPath = IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_CLIP_DIRECTORY, child);
                if(new File(childPath).isDirectory()) continue;

                Bitmap previewBitmap;

                MimeHelper.MimeType mimeType = MimeHelper.getMimeTypeFromPath(childPath);
                ProjectFilesData.Kind kind = mimeType.isVideo() ? ProjectFilesData.Kind.VIDEO
                        : mimeType.isImage() ? ProjectFilesData.Kind.IMAGE
                        : mimeType.isAudio() ? ProjectFilesData.Kind.AUDIO : ProjectFilesData.Kind.OTHER;


                if (mimeType.isAudio()) {
                    previewBitmap = ImageHelper.createBitmapFromDrawable( ResourcesCompat.getDrawable(getResources(), R.drawable.baseline_audio_file_24, null));
                }
                else if (mimeType.isImage()) {
                    previewBitmap = IOImageHelper.LoadFileAsPNGImage(getContext(), childPath, Constants.SAMPLE_SIZE_PREVIEW_CLIP);
                }
                else if (mimeType.isVideo()) {
                    try {
                        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                        retriever.setDataSource(childPath);
                        int originalWidth = Integer.parseInt(Objects.requireNonNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)));
                        int originalHeight = Integer.parseInt(Objects.requireNonNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)));

                        int desiredWidth = originalWidth / Constants.SAMPLE_SIZE_PREVIEW_CLIP;
                        int desiredHeight = originalHeight / Constants.SAMPLE_SIZE_PREVIEW_CLIP;
                        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            previewBitmap = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, desiredWidth, desiredHeight);
                        }
                        else {
                            previewBitmap = Bitmap.createScaledBitmap(
                                    Objects.requireNonNull(retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)),
                                    desiredWidth, desiredHeight, true);

                        }

                        retriever.release();
                        retriever.close();
                    } catch (Exception e) {
                        LoggingManager.LogExceptionToNoteOverlay(getContext(), e);
                        previewBitmap = null;
                    }
                }
                else {
                    previewBitmap = ImageHelper.createBitmapFromDrawable( ResourcesCompat.getDrawable(getResources(), R.drawable.baseline_question_mark_24, null));
                }; // if effect or unknown






                ProjectFilesData entry = new ProjectFilesData(childPath, child, previewBitmap);
                entry.bytes = new File(childPath).length();
                entry.kind = kind;
                allFiles.add(entry);
            }
        }

        applyFilter();
        projectFilesSwipeRefreshLayout.setRefreshing(false);
    }








    public static class ProjectFilesData implements Serializable {

        public enum Kind { VIDEO, IMAGE, AUDIO, OTHER }

        public String filePath;
        public String fileTitle;
        public Bitmap filePreview;
        /** Size on disk, what kind of media it is, and how many clips use it (set when the list is filtered). */
        public long bytes;
        public Kind kind = Kind.OTHER;
        public int usedBy;

        public ProjectFilesData(String filePath, String fileTitle, Bitmap filePreview) {
            this.filePath = filePath;
            this.fileTitle = fileTitle;
            this.filePreview = filePreview;
        }


        public String getFilePath() {
            return filePath;
        }
        public String getFileTitle() {
            return fileTitle;
        }
        public Bitmap getFilePreview() {
            return filePreview;
        }


        public void setFileTitle(String fileTitle) {
            this.fileTitle = fileTitle;
        }
        public void setFilePreview(Bitmap filePreview) {
            this.filePreview = filePreview;
        }

    }
    public class ProjectFilesAdapter extends RecyclerView.Adapter<ProjectFilesViewHolder>
    {

        private List<ProjectFilesData> projectFilesList;
        private Context context;

        // Constructor
        public ProjectFilesAdapter(Context context, List<ProjectFilesData> projectFilesList) {
            this.context = context;
            this.projectFilesList = projectFilesList;
        }
        @Override
        public ProjectFilesViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.cpn_project_files_element, parent, false);
            return new ProjectFilesViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ProjectFilesViewHolder holder, int position) {

            ProjectFilesData projectItem = projectFilesList.get(position);

            holder.projectFilesTitle.setText(projectItem.getFileTitle());
            holder.projectFilesDetail.setText((projectItem.usedBy > 0 ? "×" + projectItem.usedBy : "Unused") + " · "
                    + Formatter.formatShortFileSize(context, projectItem.bytes));

            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    Bitmap thumbnailBitmap = projectItem.getFilePreview();

                    if(thumbnailBitmap != null) {
                        holder.wholeView.post(() -> {
                            holder.projectFilesPreview.setImageBitmap(thumbnailBitmap);
                            int targetWidth = holder.wholeView.getWidth();

                            int imageWidth = thumbnailBitmap.getWidth();
                            int imageHeight = thumbnailBitmap.getHeight();

                            int[] res = AlgorithmHelper.scaleByWidth(targetWidth, imageWidth, imageHeight);

                            ViewGroup.LayoutParams imageDimension = holder.projectFilesPreview.getLayoutParams();
                            imageDimension.width = res[0];
                            imageDimension.height = res[1];
                            holder.projectFilesPreview.setLayoutParams(imageDimension);
                        });
                    }
                } catch (Exception e) {
                    LoggingManager.LogExceptionToNoteOverlay(context, e);
                }
            });

            holder.projectFilesTitle.setOnClickListener(v -> {
                holder.wholeView.performClick();
            });

            holder.wholeView.setOnClickListener(v -> {
                if(activityInstance != null)
                    if(EditingActivity.selectedTrack != null) {
                        activityInstance.addProjectFileMediaToTrack(projectItem.getFileTitle(), projectItem.getFilePath());
                    }
                    else new AlertDialog.Builder(getContext()).setTitle(R.string.error).setMessage(R.string.you_need_to_pick_a_track_first).show();
                    else new AlertDialog.Builder(getContext()).setTitle(R.string.error).setMessage("Tung Tung Tung Sahur! (EditingActivity instance not initialized!)").show();
            });
            holder.wholeView.setOnLongClickListener(v -> {


                {
                    PopupMenu popup = new PopupMenu(context, v);
                    popup.getMenuInflater().inflate(R.menu.menu_cpn_project_files_more, popup.getMenu());

                    popup.setOnMenuItemClickListener(item -> {
                        if(item.getItemId() == R.id.action_edit)
                        {
                            editProjectFileName(properties, projectItem);
                            return true;
                        }
                        else if(item.getItemId() == R.id.action_delete)
                        {
                            new AlertDialog.Builder(context)
                                    .setTitle(context.getString(R.string.alert_delete_media_confirmation_title))
                                    .setMessage(context.getString(R.string.alert_delete_media_confirmation_description)
                                            + (projectItem.usedBy > 0 ? "\n\n" + (projectItem.usedBy == 1 ? "1 clip uses" : projectItem.usedBy + " clips use")
                                            + " this file and will be removed from the timeline too." : ""))

                                    // Specifying a listener allows you to take an action before dismissing the dialog.
                                    // The dialog is automatically dismissed when a dialog button is clicked.
                                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                        // Continue with delete operation
                                        activityInstance.deleteAllClipWithName(projectItem.getFileTitle());
                                        deleteFileFromProject(projectItem.getFileTitle());
                                        reloadingProject();

                                    })

                                    // A null listener allows the button to dismiss the dialog and take no further action.
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .setIconAttribute(android.R.attr.alertDialogIcon)
                                    .show();
                            return true;
                        }
                        else if(item.getItemId() == R.id.action_swap)
                        {

                            //activityInstance.renameProjectFile(projectFile.getFileTitle(), editText.getText().toString());
                            // TODO: Swapping the clip
                            //  add Change File (First rename the Clip name to the Imported Filename,
                            //  second import the filename, and go through the preview process,
                            //  then check if the filename and old file are matched,
                            //  if old filename and new filename are the same,
                            //  then we will not need to delete the file, else we will delete the old file)



                            LoggingManager.LogToToast(context, context.getString(R.string.replacing_a_file_isn_t_available_y));
                            return true;
                        }
                        return false;
                    });

                    popup.show();
                }

                return true;
            });
        }


        private void editProjectFileName(MainAreaScreen.ProjectData projectItem, ProjectFilesData projectFile) {

            AlertDialog.Builder builder = new AlertDialog.Builder(context);

            // Inflate your custom layout
            LayoutInflater inflater = LayoutInflater.from(context);
            View dialogView = inflater.inflate(R.layout.popup_edit_project_filename, null);
            builder.setView(dialogView);

            // Get references to the EditText and Buttons in your custom layout
            EditText editText = dialogView.findViewById(R.id.directoryText);
            Button okButton = dialogView.findViewById(R.id.okButton);
            Button cancelButton = dialogView.findViewById(R.id.cancelButton);

            // Create the AlertDialog
            AlertDialog dialog = builder.create();
            editText.setText(projectFile.getFileTitle());

            // Set button click listeners
            okButton.setOnClickListener(vok -> {
                String oldName = projectFile.getFileTitle();
                String newName = editText.getText().toString().trim();
                int oldDot = oldName.lastIndexOf('.');
                String extension = oldDot > 0 ? oldName.substring(oldDot) : "";
                // The extension is what tells the kind of media: renaming never changes it.
                if (!extension.isEmpty() && !newName.toLowerCase().endsWith(extension.toLowerCase())) newName += extension;

                String problem = null;
                if (newName.isEmpty() || newName.equals(extension)) problem = "The name can't be empty.";
                else if (newName.contains("/") || newName.contains("\\")) problem = "The name can't contain / or \\.";
                else if (!newName.equals(oldName)) {
                    for (ProjectFilesData other : allFiles) {
                        if (other.fileTitle.equalsIgnoreCase(newName)) problem = "A file named " + newName + " already exists.";
                    }
                }
                if (problem != null) {
                    LoggingManager.LogToToast(context, problem);
                    return;
                }
                if (!newName.equals(oldName)) {
                    activityInstance.renameProjectFile(oldName, newName);
                    reloadingProject();
                }

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
            return projectFilesList.size();
        }
    }
    public static class ProjectFilesViewHolder extends RecyclerView.ViewHolder {
        TextView projectFilesTitle;
        TextView projectFilesDetail;
        ImageView projectFilesPreview;
        View wholeView;
        public ProjectFilesViewHolder(@NonNull View itemView) {
            super(itemView);
            wholeView = itemView;

            projectFilesTitle = itemView.findViewById(R.id.titleText);
            projectFilesDetail = itemView.findViewById(R.id.detailText);
            projectFilesPreview = itemView.findViewById(R.id.previewImage);
        }
    }

}
