package com.vanvatcorporation.doubleclips.activities;

import static com.vanvatcorporation.doubleclips.FFmpegEdit.runAnyCommand;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.Log;
import com.arthenica.ffmpegkit.Statistics;
import com.vanvatcorporation.doubleclips.AdsHandler;
import com.vanvatcorporation.doubleclips.FFmpegEdit;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.export.VideoPropertiesExportSpecificAreaScreen;
import com.vanvatcorporation.doubleclips.activities.main.MainAreaScreen;
import com.vanvatcorporation.doubleclips.activities.main.TemplateAreaScreen;
import com.vanvatcorporation.doubleclips.activities.template.TemplateExportPropertiesAreaScreen;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.helper.FileHelper;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.helper.IOImageHelper;
import com.vanvatcorporation.doubleclips.helper.ImageHelper;
import com.vanvatcorporation.doubleclips.helper.MimeHelper;
import com.vanvatcorporation.doubleclips.helper.ParserHelper;
import com.vanvatcorporation.doubleclips.helper.WebHelper;
import com.vanvatcorporation.doubleclips.impl.AppCompatActivityImpl;
import com.vanvatcorporation.doubleclips.impl.SectionView;
import com.vanvatcorporation.doubleclips.impl.java.RunnableImpl;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.net.ssl.HttpsURLConnection;

public class TemplateExportActivity extends AppCompatActivityImpl {
    TemplateAreaScreen.TemplateData data;
    EditingActivity.VideoSettings settings;

    public List<ClipReplacementData> clipReplacementList;
    public RecyclerView clipReplacementRecyclerView;
    public ClipReplacementAdapter clipReplacementAdapter;

    ClipReplacementData selectedClipReplacement;

    int currentlyChosenClipIndex = -1;

    private ActivityResultLauncher<Intent> clipChooser = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {


                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {

                    if(result.getData().getClipData() != null) { // checking multiple selection or not
                        Uri[] uris = new Uri[result.getData().getClipData().getItemCount()];
                        for(int i = 0; i < result.getData().getClipData().getItemCount(); i++) {
                            uris[i] = result.getData().getClipData().getItemAt(i).getUri();
                        }
                        arrangeClip(uris);
                    }
                    else {
                        Uri uri = result.getData().getData();
                        arrangeClip(new Uri[]{uri});
                    }
                }
            }
    );

    public void arrangeClip(Uri[] uris)
    {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.execute(() -> {
            for (Uri uri : uris) {
                MimeHelper.MimeType mimeType = MimeHelper.getMimeTypeFromUri(this, uri);

                if (currentlyChosenClipIndex == -1) return;
                // Discard the rest of leftover clip when done arranging
                if (currentlyChosenClipIndex >= clipReplacementList.size()) return;

                String clipPath = IOHelper.CombinePath(IOHelper.getPersistentDataPath(this),
                        Constants.DEFAULT_TEMPLATE_CLIP_TEMP_DIRECTORY,
                        currentlyChosenClipIndex + (mimeType.isVideo()  ? ".mp4" : ".png"));

                IOHelper.writeToFileAsRaw(this, clipPath,
                        IOHelper.readFromFileAsRaw(this, getContentResolver(), uri)
                );

                clipReplacementList.get(currentlyChosenClipIndex).clipPath = clipPath;



                clipReplacementList.get(currentlyChosenClipIndex).type = mimeType.isVideo() ? EditingActivity.ClipType.VIDEO : EditingActivity.ClipType.IMAGE;

                clipReplacementList.get(currentlyChosenClipIndex).clipThumbnail =
                        mimeType.isVideo() ?
                                extractSingleThumbnail(this, clipPath) :
                                IOImageHelper.LoadFileAsPNGImage(this, clipPath, 8)
                ;

                currentlyChosenClipIndex++;
            }

            currentlyChosenClipIndex = -1;

            runOnUiThread(() -> {
                clipReplacementAdapter.notifyDataSetChanged();
            });
        });
    }

    private ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    String inputPath = IOHelper.CombinePath(
                            IOHelper.getPersistentDataPath(this),
                            Constants.DEFAULT_TEMPLATE_CLIP_TEMP_DIRECTORY,
                            Constants.DEFAULT_EXPORT_CLIP_FILENAME); // Use your helper

                    IOHelper.writeToFileAsRaw(this, getContentResolver(), uri, IOHelper.readFromFileAsRaw(this, inputPath));

                    IOHelper.deleteFile(inputPath);


                    // Create an intent to view the media
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, "video/*"); // Replace "audio/*" with the appropriate MIME type
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); // Grant permission if needed

                    // Start the activity
                    if (intent.resolveActivity(getPackageManager()) != null) {
                        startActivity(intent);
                    } else {
                        // Handle the case where no app can handle the intent
                        System.out.println("No app available to open this media.");
                    }
                }
            }
    );


    private VideoPropertiesExportSpecificAreaScreen videoPropertiesExportSpecificAreaScreen;
    private TemplateExportPropertiesAreaScreen templateExportPropertiesAreaScreen;

    RelativeLayout modifyZone;

    ProgressBar statusBar, globalStatusBar;
    TextView logText, statusText, globalStatusText;
    EditText commandText;
    ScrollView logScroll;
    CheckBox logCheckbox, truncateCheckbox, scrollLockCheckbox;
    Button exportButton;

    SectionView logSection, advancedSection;

    boolean isLogUpdateRunning = false;
    Handler logUpdateHandler = new Handler(Looper.getMainLooper());
    Runnable logUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (FFmpegEdit.queue.currentRenderQueue == null) return;
            statusText.setText(FFmpegEdit.queue.currentRenderQueue.taskName);

            globalStatusBar.setMax(FFmpegEdit.queue.totalQueue);
            globalStatusBar.setProgress(FFmpegEdit.queue.queueDone);

            globalStatusText.setText("Running tasks: " + "(" + FFmpegEdit.queue.queueDone + "/" + FFmpegEdit.queue.totalQueue + ")");

            if (!FFmpegEdit.queue.isRunning) {
                isLogUpdateRunning = false;
            }
            if (isLogUpdateRunning)
                logUpdateHandler.postDelayed(logUpdateRunnable, 500);
        }
    };


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_template_export);

        data = (TemplateAreaScreen.TemplateData) createrBundle.getSerializable("TemplateData");
        // Make default video settings at this point
        settings = new EditingActivity.VideoSettings(1366, 768, 30, 30, 30, EditingActivity.VideoSettings.FfmpegPreset.ULTRAFAST, EditingActivity.VideoSettings.FfmpegTune.ZEROLATENCY);

        clipReplacementRecyclerView = findViewById(R.id.clipReplacementRecyclerView);


        // Set layout manager for horizontal scrolling
        clipReplacementRecyclerView.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        );


        clipReplacementList = new ArrayList<>();
        clipReplacementAdapter = new ClipReplacementAdapter(this, clipReplacementList);
        clipReplacementRecyclerView.setAdapter(clipReplacementAdapter);

        exportButton = findViewById(R.id.exportButton);
        exportButton.setEnabled(false);

        findViewById(R.id.backButton).setOnClickListener(v -> {
            finish();
        });
        findViewById(R.id.settingsButton).setOnClickListener(v -> {
            videoPropertiesExportSpecificAreaScreen.open();
        });
        findViewById(R.id.generateCmdButton).setOnClickListener(v -> {
            generateCommand(data.getTemplateAdditionalResourcesName());
        });
        exportButton.setOnClickListener(v -> {
            exportClip();
        });

        modifyZone = findViewById(R.id.modifyZone);

        logText = findViewById(R.id.logText);
        statusBar = findViewById(R.id.statusBar);
        globalStatusBar = findViewById(R.id.globalStatusBar);
        statusText = findViewById(R.id.statusText);
        globalStatusText = findViewById(R.id.globalStatusText);
        logScroll = findViewById(R.id.logScroll);
        logCheckbox = findViewById(R.id.logCheckbox);
        truncateCheckbox = findViewById(R.id.truncateCheckbox);
        scrollLockCheckbox = findViewById(R.id.scrollLockCheckbox);

        commandText = findViewById(R.id.exportCommand);

        logSection = findViewById(R.id.logSection);
        advancedSection = findViewById(R.id.advancedSection);

        setupSpecificEdit();


        for (int i = 0; i < data.getTemplateClipCount(); i++) {
            clipReplacementList.add(new ClipReplacementData(EditingActivity.ClipType.VIDEO, "", null));
        }
        clipReplacementAdapter.notifyDataSetChanged();

        downloadNecessaryResources(data.getTemplateLocation(), data.getTemplateAdditionalResourcesName());


        setupSpecificEdit();
    }

    @Override
    public void finish() {
        super.finish();
        FFmpegEdit.queue.cancelAllTask();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        FFmpegEdit.queue.cancelAllTask();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AdsHandler.displayThanksForShowingAds(this);
    }


    private void setupSpecificEdit() {


        // ===========================       VIDEO PROPERTIES ZONE       ====================================
        videoPropertiesExportSpecificAreaScreen = (VideoPropertiesExportSpecificAreaScreen) LayoutInflater.from(this).inflate(R.layout.view_export_specific_video_properties, null);
        modifyZone.addView(videoPropertiesExportSpecificAreaScreen);
        // ===========================       VIDEO PROPERTIES ZONE       ====================================


        // ===========================       CLIP REPLACEMENT PROPERTIES ZONE       ====================================
        templateExportPropertiesAreaScreen = (TemplateExportPropertiesAreaScreen) LayoutInflater.from(this).inflate(R.layout.view_template_export_properties, null);
        modifyZone.addView(templateExportPropertiesAreaScreen);
        // ===========================       CLIP REPLACEMENT PROPERTIES ZONE       ====================================


        // ===========================       VIDEO PROPERTIES ZONE       ====================================

        videoPropertiesExportSpecificAreaScreen.onClose.add(() -> {
            settings.videoWidth = ParserHelper.TryParse(videoPropertiesExportSpecificAreaScreen.resolutionXField.getText().toString(), settings.videoWidth);
            settings.videoHeight = ParserHelper.TryParse(videoPropertiesExportSpecificAreaScreen.resolutionYField.getText().toString(), settings.videoHeight);
            settings.frameRate = ParserHelper.TryParse(videoPropertiesExportSpecificAreaScreen.frameRateText.getText().toString(), settings.frameRate);
            settings.crf = ParserHelper.TryParse(videoPropertiesExportSpecificAreaScreen.crfText.getText().toString(), settings.crf);
            settings.bitrate = ParserHelper.TryParse(videoPropertiesExportSpecificAreaScreen.bitrateText.getText().toString(), settings.bitrate);
            settings.clipCap = ParserHelper.TryParse(videoPropertiesExportSpecificAreaScreen.clipCapText.getText().toString(), settings.clipCap);
            settings.preset = videoPropertiesExportSpecificAreaScreen.presetSpinner.getSelectedItem().toString();
            settings.tune = videoPropertiesExportSpecificAreaScreen.tuneSpinner.getSelectedItem().toString();
            settings.useHardwareAccel = videoPropertiesExportSpecificAreaScreen.hardwareAccelCheckbox.isChecked();


            // Recommended value not in range pop up [10, 30]
            if (settings.clipCap > 30 || settings.clipCap < 10) {
                new AlertDialog.Builder(this)
                        .setTitle("Clip Cap out of recommended range!")
                        .setMessage("Clip Cap out of recommended range, this mean the rendering process might take longer due to inefficient rendering part. Would you like to continue?")
                        .setPositiveButton("Yes", (dialog, which) -> {
                            dialog.dismiss();
                        })
                        .setNegativeButton("No", (dialog, which) -> {
                            // Set back to default (30)
                            settings.clipCap = 30;
                            dialog.dismiss();
                        })
                        .show();
            }
        });
        videoPropertiesExportSpecificAreaScreen.onOpen.add(() -> {
            videoPropertiesExportSpecificAreaScreen.resolutionXField.setText(String.valueOf(settings.getVideoWidth()));
            videoPropertiesExportSpecificAreaScreen.resolutionYField.setText(String.valueOf(settings.getVideoHeight()));
            videoPropertiesExportSpecificAreaScreen.crfText.setText(String.valueOf(settings.getCRF()));
            videoPropertiesExportSpecificAreaScreen.bitrateText.setText(String.valueOf(settings.getBitrate()));
            videoPropertiesExportSpecificAreaScreen.clipCapText.setText(String.valueOf(settings.getClipCap()));
            videoPropertiesExportSpecificAreaScreen.presetSpinner.setSelection(videoPropertiesExportSpecificAreaScreen.presetAdapter.getPosition(settings.preset));
            videoPropertiesExportSpecificAreaScreen.tuneSpinner.setSelection(videoPropertiesExportSpecificAreaScreen.tuneAdapter.getPosition(settings.tune));
            videoPropertiesExportSpecificAreaScreen.hardwareAccelCheckbox.setChecked(settings.isUseHardwareAccel());
            videoPropertiesExportSpecificAreaScreen.updateHardwareAccelState(settings.isUseHardwareAccel());

        });


        // ===========================       VIDEO PROPERTIES ZONE       ====================================


        // ===========================       CLIP REPLACEMENT PROPERTIES ZONE       ====================================
        templateExportPropertiesAreaScreen.onClose.add(() -> {
            if(selectedClipReplacement != null)
            {
                selectedClipReplacement.startClipTrim = templateExportPropertiesAreaScreen.trimRangeSlider.getValues().get(0);
                selectedClipReplacement.endClipTrim = templateExportPropertiesAreaScreen.trimRangeSlider.getValues().get(1);
            }
        });
        templateExportPropertiesAreaScreen.onOpen.add(() -> {
            int clipIndex = clipReplacementList.indexOf(selectedClipReplacement);
            templateExportPropertiesAreaScreen.titleText.setText("Clip " + (clipIndex + 1));
            templateExportPropertiesAreaScreen.dataInfoText.setText(MimeHelper.getMimeTypeFromPath(selectedClipReplacement.clipPath).getFullName());
            templateExportPropertiesAreaScreen.previewImage.setImageBitmap(selectedClipReplacement.clipThumbnail);
            templateExportPropertiesAreaScreen.trimRangeSlider.setEnabled(data.getFfmpegCommand().contains(Constants.DEFAULT_TEMPLATE_TRIM_MARK(clipIndex)));

            templateExportPropertiesAreaScreen.trimRangeSlider.setValues(selectedClipReplacement.startClipTrim, selectedClipReplacement.endClipTrim);

        });

        // ===========================       CLIP REPLACEMENT PROPERTIES ZONE       ====================================


    }

    // This function block thread.
    void downloadNecessaryResources(String templateLocation, String[] additionalResourcesName)
    {
        Future<Boolean> fetchFFmpegCommandStatus =

        // FFmpeg command fetch
        Executors.newSingleThreadExecutor().submit(() -> {
            try {

                URL url = new URL(
                        "https://app.vanvatcorp.com/doubleclips/api/fetch-ffmpeg-command/" +
                                data.getTemplateAuthor() + "/" +
                                data.getTemplateId()
                );
                HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

                // Read the response
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();


                String ffmpegTemplate = response.toString();
                if(ffmpegTemplate.contains("\n"))
                    ffmpegTemplate = ffmpegTemplate.charAt(ffmpegTemplate.length() - 1) == '\n' ? ffmpegTemplate.substring(0, ffmpegTemplate.length() - 1) : ffmpegTemplate;

                data.setFfmpegCommand(ffmpegTemplate);

                // Allow edit only if these is a place to replace those alternative resolution
                runOnUiThread(() -> {
                    videoPropertiesExportSpecificAreaScreen.resolutionXField.setEnabled(data.getFfmpegCommand().contains(Constants.DEFAULT_TEMPLATE_CLIP_SCALE_WIDTH_MARK));
                    videoPropertiesExportSpecificAreaScreen.resolutionYField.setEnabled(data.getFfmpegCommand().contains(Constants.DEFAULT_TEMPLATE_CLIP_SCALE_HEIGHT_MARK));
                });
                return true;
            }
            catch (Exception e)
            {
                LoggingManager.LogExceptionToNoteOverlay(this, e);
                return false;
            }

        });




        Future<Boolean> fetchAdditionalResourcesStatus =
        Executors.newSingleThreadExecutor().submit(() -> {
            boolean status = true;

            for (String name : additionalResourcesName) {
                WebHelper.downloadFile(this,
                        "https://app.vanvatcorp.com/doubleclips/templates" + templateLocation + "/content/" + name,
                        IOHelper.CombinePath(
                                IOHelper.getPersistentDataPath(this),
                                Constants.DEFAULT_TEMPLATE_CLIP_TEMP_DIRECTORY,
                                name)
                );
            }

            return status;

        });

//
//        try {
//            fetchFFmpegCommandStatus.get();
//            fetchAdditionalResourcesStatus.get();
//        } catch (Exception e) {
//            LoggingManager.LogExceptionToNoteOverlay(this, e);
//        }


        if(!fetchFFmpegCommandStatus.isDone() || !fetchAdditionalResourcesStatus.isDone())
            exportButton.post(() -> exportButton.setEnabled(true));

    }

    private void runLogUpdate() {
        isLogUpdateRunning = true;
        logUpdateHandler.post(logUpdateRunnable);
    }

    private void generateCommand(String[] additionalResourcesName) {
        String cmd = data.getFfmpegCommand();
        for (int i = 0; i < clipReplacementList.size(); i++) {
            ClipReplacementData clipData = clipReplacementList.get(i);

            String frameFilter =
                    clipData.type == EditingActivity.ClipType.IMAGE ?
                            "-loop 1 -t " + data.getTemplateDuration() + " -framerate " + settings.getFrameRate() + " " :
                            "";

            // Replace placeholder clip to the new selected clips according to the list
            cmd = cmd.replace("-i \"" + Constants.DEFAULT_TEMPLATE_CLIP_MARK(i), frameFilter + "-i \"" + clipData.clipPath);


            // TODO: No extension for end video
            float extendMediaDuration = 0;
            float fillingTransitionDuration = 0;

            String trimFilter =
                            clipData.type == EditingActivity.ClipType.VIDEO ?
                                    "trim=start=" + clipData.startClipTrim + ":end=" + (clipData.startClipTrim + clipData.duration + extendMediaDuration) :
                                    "trim=duration=" + (clipData.duration + fillingTransitionDuration);
            cmd = cmd.replace(Constants.DEFAULT_TEMPLATE_TRIM_MARK(i), trimFilter);
        }
        for (String resourceName : additionalResourcesName) {
            cmd = cmd.replace(Constants.DEFAULT_TEMPLATE_CLIP_STATIC_MARK(resourceName), IOHelper.CombinePath(
                    IOHelper.getPersistentDataPath(this),
                    Constants.DEFAULT_TEMPLATE_CLIP_TEMP_DIRECTORY,
                    resourceName));
        }
        cmd = cmd.replace(Constants.DEFAULT_TEMPLATE_CLIP_SCALE_WIDTH_MARK, String.valueOf(settings.videoWidth));
        cmd = cmd.replace(Constants.DEFAULT_TEMPLATE_CLIP_SCALE_HEIGHT_MARK, String.valueOf(settings.videoHeight));

        // Replace output.mp4 to relative device temp path
        cmd = cmd.replace(Constants.DEFAULT_TEMPLATE_CLIP_EXPORT_MARK, IOHelper.CombinePath(
                        IOHelper.getPersistentDataPath(this),
                        Constants.DEFAULT_TEMPLATE_CLIP_TEMP_DIRECTORY,
                        Constants.DEFAULT_EXPORT_CLIP_FILENAME)); // Use your helper)
        commandText.setText(cmd);
    }


    private void exportClip() {
        startExportRendering();
        FFmpegKit.cancel();

        if (commandText.getText().toString().isEmpty())
            generateCommand(data.getTemplateAdditionalResourcesName());

        String cmd = commandText.getText().toString();
        // No need. Already in the EditText
        //logText.setText(cmd);


        AdsHandler.loadBothAds(this, this);


        String[] cmdAfterSplit = cmd.split(Constants.DEFAULT_MULTI_FFMPEG_COMMAND_REGEX);
        for (int i = 0; i < cmdAfterSplit.length; i++) {
            String cmdEach = cmdAfterSplit[i];
            runAnyCommand(this, cmdEach, "Exporting Video", (i == cmdAfterSplit.length - 1 ? this::exportCompleted : () -> {
                    }), this::finishExportRendering
                    , new RunnableImpl() {
                        @Override
                        public <T> void runWithParam(T param) {
                            Log log = (Log) param;
                            if (logCheckbox.isChecked()) {
                                logText.post(() -> {
                                    String logStr = logText.getText() + "\n" + log.getMessage();
                                    if (logStr.length() > Constants.DEFAULT_LOGGING_LIMIT_CHARACTERS && truncateCheckbox.isChecked())
                                        logStr = logStr.substring(logStr.length() - Constants.DEFAULT_LOGGING_LIMIT_CHARACTERS);
                                    logText.setText(logStr);
                                    if (scrollLockCheckbox.isChecked())
                                        logScroll.fullScroll(View.FOCUS_DOWN);
                                });
                            }
                        }
                    }, new RunnableImpl() {
                        @Override
                        public <T> void runWithParam(T param) {
                            //MediaInformationSession session = FFprobeKit.getMediaInformation(properties.getProjectPath());
                            //double duration = Double.parseDouble(session.getMediaInformation().getDuration());
                            double duration = data.getTemplateDuration();

                            Statistics statistics = (Statistics) param;
                            {
                                if (statistics.getTime() > 0) {
                                    int progress = (int) ((statistics.getTime() * 100) / (int) duration);
                                    statusBar.setMax(100);
                                    statusBar.setProgress(progress);
                                }
                            }
                        }
                    });
        }


        if (!isLogUpdateRunning)
            runLogUpdate();
    }

    void exportCompleted() {

        finishExportRendering();

        FFmpegEdit.queue.cancelAllTask();

        IOHelper.deleteFilesInDir(
                IOHelper.CombinePath(
                        IOHelper.getPersistentDataPath(this),
                        Constants.DEFAULT_TEMPLATE_CLIP_TEMP_DIRECTORY)
        );


        // Request permission to create a file
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_TITLE, "export");
        filePickerLauncher.launch(Intent.createChooser(intent, "Select Export"));

    }

    void startExportRendering()
    {
        runOnUiThread(() -> {
            // After rendering, set back to default
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            logText.setTextIsSelectable(false);

            exportButton.setEnabled(false);

        });
    }
    void finishExportRendering()
    {
        runOnUiThread(() -> {
            // After rendering, set back to default
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            logText.setTextIsSelectable(true);

            exportButton.setEnabled(true);
        });
    }

    private static Bitmap extractSingleThumbnail(Context context, String filePath) {
        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(filePath);

            int originalWidth = Integer.parseInt(Objects.requireNonNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)));
            int originalHeight = Integer.parseInt(Objects.requireNonNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)));

            int desiredWidth = originalWidth / Constants.SAMPLE_SIZE_PREVIEW_CLIP;
            int desiredHeight = originalHeight / Constants.SAMPLE_SIZE_PREVIEW_CLIP;

            Bitmap frame;
            if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, desiredWidth, desiredHeight);
            } else {
                frame = Bitmap.createScaledBitmap(
                        Objects.requireNonNull(retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)),
                        desiredWidth, desiredHeight, true);

            }


            retriever.release();
            retriever.close();

            return frame;
        } catch (Exception e) {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
            return null;
        }
    }


    public static class ClipReplacementData implements Serializable {

        EditingActivity.ClipType type;
        public String clipPath;
        public Bitmap clipThumbnail;
        public float startClipTrim, endClipTrim, duration;

        public ClipReplacementData(EditingActivity.ClipType type, String clipPath, Bitmap clipThumbnail) {
            this.type = type;
            this.clipPath = clipPath;
            this.clipThumbnail = clipThumbnail;
        }


        public EditingActivity.ClipType getType() {
            return type;
        }
        public String getClipPath() {
            return clipPath;
        }

        public Bitmap getClipThumbnail() {
            return clipThumbnail;
        }

        public void destroy() {
            clipPath = "";
            startClipTrim = 0;
            endClipTrim = 0;
            duration = 0;
            clipThumbnail = ImageHelper.createTransparentBitmap(1, 1);

        }
    }

    public class ClipReplacementAdapter extends RecyclerView.Adapter<ClipReplacementViewHolder> {

        private List<ClipReplacementData> clipList;
        private Context context;

        // Constructor
        public ClipReplacementAdapter(Context context, List<ClipReplacementData> clipList) {
            this.context = context;
            this.clipList = clipList;
        }

        @Override
        public ClipReplacementViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.cpn_clip_replacement_element, parent, false);
            return new ClipReplacementViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ClipReplacementViewHolder holder, int position) {
            ClipReplacementData data = clipList.get(position);

            int humanIndex = position + 1;

            holder.indexText.setText("" + humanIndex);
            holder.clipPreview.setImageBitmap(data.clipThumbnail);

            holder.wholeView.setOnClickListener(v -> {
                currentlyChosenClipIndex = holder.getAbsoluteAdapterPosition();

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                //intent.setType("video/*");
                intent.setType("*/*"); // general catch-all
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "image/*", "video/*" });
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                clipChooser.launch(Intent.createChooser(intent, "Select Video #" + humanIndex));
            });
            holder.wholeView.setOnLongClickListener(v -> {

                {
                    PopupMenu popup = new PopupMenu(context, v);
                    popup.getMenuInflater().inflate(R.menu.menu_cpn_template_export_more, popup.getMenu());

                    popup.setOnMenuItemClickListener(item -> {
                        if(item.getItemId() == R.id.action_delete)
                        {
                            new AlertDialog.Builder(context)
                                    .setTitle(context.getString(R.string.alert_delete_media_confirmation_title))
                                    .setMessage(context.getString(R.string.alert_delete_media_confirmation_description))

                                    // Specifying a listener allows you to take an action before dismissing the dialog.
                                    // The dialog is automatically dismissed when a dialog button is clicked.
                                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                        // Continue with delete operation

                                        IOHelper.deleteFile(clipList.get(position).clipPath);

                                        clipList.get(position).destroy();
                                        holder.clipPreview.setImageBitmap(clipList.get(position).clipThumbnail);


                                    })

                                    // A null listener allows the button to dismiss the dialog and take no further action.
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .setIconAttribute(android.R.attr.alertDialogIcon)
                                    .show();
                            return true;
                        }
                        else if(item.getItemId() == R.id.action_properties)
                        {
                            selectedClipReplacement = clipList.get(position);
                            templateExportPropertiesAreaScreen.open();
                            // TODO: Add Trim start, Trim end and link it with FFmpeg.

                            return true;
                        }
                        return false;
                    });

                    popup.show();
                }

                // Remove clips
                return true;
            });
        }


        @Override
        public int getItemCount() {
            return clipList.size();
        }
    }

    public static class ClipReplacementViewHolder extends RecyclerView.ViewHolder {
        TextView indexText;
        ImageView clipPreview;
        View wholeView;

        public ClipReplacementViewHolder(@NonNull View itemView) {
            super(itemView);
            wholeView = itemView;

            indexText = itemView.findViewById(R.id.indexText);
            clipPreview = itemView.findViewById(R.id.clipReplacementPreview);
        }
    }
}
