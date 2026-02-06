package com.vanvatcorporation.doubleclips.activities;

import static com.vanvatcorporation.doubleclips.FFmpegEdit.generateCmdFull;
import static com.vanvatcorporation.doubleclips.FFmpegEdit.runAnyCommand;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.Log;
import com.arthenica.ffmpegkit.Statistics;
import com.vanvatcorporation.doubleclips.AdsHandler;
import com.vanvatcorporation.doubleclips.FFmpegEdit;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.export.VideoPropertiesExportSpecificAreaScreen;
import com.vanvatcorporation.doubleclips.activities.main.MainAreaScreen;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.dynamiclibs.auth.AuthRepository;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.helper.ParserHelper;
import com.vanvatcorporation.doubleclips.impl.AppCompatActivityImpl;
import com.vanvatcorporation.doubleclips.impl.SectionView;
import com.vanvatcorporation.doubleclips.impl.java.RunnableImpl;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ExportActivity extends AppCompatActivityImpl {

    EditingActivity.Timeline timeline;
    MainAreaScreen.ProjectData properties;
    EditingActivity.VideoSettings settings;


    private VideoPropertiesExportSpecificAreaScreen videoPropertiesExportSpecificAreaScreen;

    RelativeLayout modifyZone;

    ProgressBar statusBar, globalStatusBar;
    TextView logText, statusText, globalStatusText;
    EditText commandText;
    ScrollView logScroll;
    CheckBox logCheckbox, truncateCheckbox, scrollLockCheckbox;
    Button exportButton, exportAsTemplateButton;

    SectionView logSection, advancedSection;


    private ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    String inputPath = IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_EXPORT_CLIP_FILENAME); // Use your helper

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

        setContentView(R.layout.layout_export);

        properties = (MainAreaScreen.ProjectData) createrBundle.getSerializable("ProjectProperties");
        timeline = (EditingActivity.Timeline) createrBundle.getSerializable("ProjectTimeline");
        settings = (EditingActivity.VideoSettings) createrBundle.getSerializable("ProjectSettings");


        findViewById(R.id.backButton).setOnClickListener(v -> {
            finish();
        });
        findViewById(R.id.settingsButton).setOnClickListener(v -> {
            videoPropertiesExportSpecificAreaScreen.open();
        });
        findViewById(R.id.generateCmdButton).setOnClickListener(v -> {
            generateCommand();
        });
        findViewById(R.id.generateTemplateCmdButton).setOnClickListener(v -> {
            generateTemplateCommand();
        });
        exportButton = findViewById(R.id.exportButton);
        exportButton.setOnClickListener(v -> {
            exportClip(false);
        });
        exportAsTemplateButton = findViewById(R.id.exportAsTemplateButton);
        exportAsTemplateButton.setOnClickListener(v -> {
            exportClip(true);
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


        // Detect the last export session, if exist, try to export again.
        String inputPath = IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_EXPORT_CLIP_FILENAME);
        if (IOHelper.isFileExist(inputPath)) {
            new AlertDialog.Builder(this)
                    .setTitle("You have an unexported video!")
                    .setMessage("Would you like to export it now?")
                    .setPositiveButton("Yes", (dialog, which) -> {

                        exportClipTo(false, "", 0, new ArrayList<>(), new ArrayList<>());
                        dialog.dismiss();
                    })
                    .setNegativeButton("No", (dialog, which) -> {
                        dialog.dismiss();
                    })
                    .show();
        }


        setupSpecificEdit();



    }


    private void setupSpecificEdit() {


        // ===========================       VIDEO PROPERTIES ZONE       ====================================
        videoPropertiesExportSpecificAreaScreen = (VideoPropertiesExportSpecificAreaScreen) LayoutInflater.from(this).inflate(R.layout.view_export_specific_video_properties, null);
        modifyZone.addView(videoPropertiesExportSpecificAreaScreen);
        // ===========================       VIDEO PROPERTIES ZONE       ====================================


        // ===========================       VIDEO PROPERTIES ZONE       ====================================

        videoPropertiesExportSpecificAreaScreen.onClose.add(() -> {
            settings.videoWidth = ParserHelper.TryParse(videoPropertiesExportSpecificAreaScreen.resolutionXField.getText().toString(), settings.videoWidth);
            settings.videoHeight = ParserHelper.TryParse(videoPropertiesExportSpecificAreaScreen.resolutionYField.getText().toString(), settings.videoHeight);
            settings.frameRate = ParserHelper.TryParse(videoPropertiesExportSpecificAreaScreen.frameRateText.getText().toString(), settings.frameRate);
            settings.crf = ParserHelper.TryParse(videoPropertiesExportSpecificAreaScreen.crfText.getText().toString(), settings.crf);
            settings.clipCap = ParserHelper.TryParse(videoPropertiesExportSpecificAreaScreen.clipCapText.getText().toString(), settings.clipCap);
            settings.preset = videoPropertiesExportSpecificAreaScreen.presetSpinner.getSelectedItem().toString();
            settings.tune = videoPropertiesExportSpecificAreaScreen.tuneSpinner.getSelectedItem().toString();
            settings.isStretchToFull = videoPropertiesExportSpecificAreaScreen.stretchMediaToFullCheckbox.isChecked();

            settings.saveSettings(this, properties);


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
                            settings.saveSettings(this, properties);
                            dialog.dismiss();
                        })
                        .show();
            }
        });
        videoPropertiesExportSpecificAreaScreen.onOpen.add(() -> {
            videoPropertiesExportSpecificAreaScreen.resolutionXField.setText(String.valueOf(settings.getVideoWidth()));
            videoPropertiesExportSpecificAreaScreen.resolutionYField.setText(String.valueOf(settings.getVideoHeight()));
            videoPropertiesExportSpecificAreaScreen.crfText.setText(String.valueOf(settings.getCRF()));
            videoPropertiesExportSpecificAreaScreen.clipCapText.setText(String.valueOf(settings.getClipCap()));
            videoPropertiesExportSpecificAreaScreen.presetSpinner.setSelection(videoPropertiesExportSpecificAreaScreen.presetAdapter.getPosition(settings.getPreset()));
            videoPropertiesExportSpecificAreaScreen.tuneSpinner.setSelection(videoPropertiesExportSpecificAreaScreen.tuneAdapter.getPosition(settings.getTune()));
            videoPropertiesExportSpecificAreaScreen.stretchMediaToFullCheckbox.setChecked(settings.isStretchToFull());

        });


        // ===========================       VIDEO PROPERTIES ZONE       ====================================


    }


    private void runLogUpdate() {
        isLogUpdateRunning = true;
        logUpdateHandler.post(logUpdateRunnable);
    }

    private void generateCommand() {
        FFmpegEdit.RenderSettings renderSettings = new FFmpegEdit.RenderSettings(settings, timeline, new EditingActivity.Clip[0], properties, 0, false, false, false);
        String cmd = generateCmdFull(this, renderSettings);
        commandText.setText(cmd);
    }
    private void generateTemplateCommand() {
        FFmpegEdit.RenderSettings renderSettings = new FFmpegEdit.RenderSettings(settings, timeline, new EditingActivity.Clip[0], properties, 0, false, true, true);
        String cmd = generateCmdFull(this, renderSettings);
        commandText.setText(cmd);
    }


    private void exportClip(boolean exportAsTemplate) {

        startExportRendering();

        logText.post(() -> logText.setTextIsSelectable(false));
        FFmpegKit.cancel();

        if (commandText.getText().toString().isEmpty())
            generateCommand();

        String cmd = commandText.getText().toString();


        AdsHandler.loadBothAds(this, this);

        List<File> videoFiles = new ArrayList<>();
        for (EditingActivity.Clip clip : timeline.getLockedForTemplateClip()) {
            videoFiles.add(new File(clip.getAbsolutePath(properties)));
        }

        List<File> previewFiles = Arrays.asList(new File(IOHelper.CombinePath(properties.getProjectPath(), "preview.png")),
                new File(IOHelper.CombinePath(properties.getProjectPath(), "preview.mp4")));



        // Detect when the child layout changes size
        logScroll.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) {
                // Size changed — force ScrollView to re-measure and update

                if (scrollLockCheckbox.isChecked())
                    logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            }
        });


        String[] cmdAfterSplit = cmd.split(Constants.DEFAULT_MULTI_FFMPEG_COMMAND_REGEX);
        for (int i = 0; i < cmdAfterSplit.length; i++) {
            String cmdEach = cmdAfterSplit[i];
            runAnyCommand(this, cmdEach, "Exporting Video", (i == cmdAfterSplit.length - 1 ? () -> exportClipTo(exportAsTemplate, cmd, timeline.getAllReplacementClipCount(), videoFiles, previewFiles) : () -> {
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
                                    // Already handled above.
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
                            double duration = properties.getProjectDuration();

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

    //TODO: Delete the exported clip inside project path. Detect in the beginning the export.mp4 if its exist then do the same with this method to extract it out.
    private void exportClipTo(boolean exportAsTemplate, String ffmpegCommand, int totalClip, List<File> videoFiles, List<File> previewFiles) {
        FFmpegEdit.queue.cancelAllTask();

        logText.post(() -> logText.setTextIsSelectable(true));


        IOHelper.deleteFilesInDir(IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_CLIP_TEMP_DIRECTORY));
        // Request permission to create a file
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_TITLE, "export");

        if(!exportAsTemplate)
            filePickerLauncher.launch(Intent.createChooser(intent, "Select Export"));
        else {
            new File(IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_EXPORT_CLIP_FILENAME))
                    .renameTo(new File(IOHelper.CombinePath(properties.getProjectPath(), "preview.mp4")));

            FFmpegEdit.RenderSettings renderSettings = new FFmpegEdit.RenderSettings(settings, timeline, new EditingActivity.Clip[0], properties, 0, false, true, false);
            ffmpegCommand = generateCmdFull(this, renderSettings);

            Map<String, String> field = new HashMap<>();
            field.put("accountUsername", AuthRepository.getInstance(this).getCurrentUser().getUsername());
            field.put("accountPassword", "********");
            field.put("templateTitle", properties.getProjectTitle());
            field.put("templateDescription", properties.getProjectTitle());
            field.put("ffmpegCommand", ffmpegCommand);
            field.put("templateTotalClips", String.valueOf(totalClip));

            uploadTemplateNecessityItems(this, "https://app.vanvatcorp.com/doubleclips/api/post-template", field, videoFiles, previewFiles);
        }



        finishExportRendering();
    }
    void startExportRendering()
    {
        runOnUiThread(() -> {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            logText.setTextIsSelectable(false);

            exportButton.setEnabled(false);
            exportAsTemplateButton.setEnabled(false);

        });
    }
    void finishExportRendering()
    {
        runOnUiThread(() -> {
            // After rendering, set back to default
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            logText.setTextIsSelectable(true);

            exportButton.setEnabled(true);
            exportAsTemplateButton.setEnabled(true);
        });
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














    public static void uploadTemplateNecessityItems(Context context,
                                                    String serverUrl,
                                                    Map<String, String> fields,
                                                    List<File> videoFiles,
                                                    List<File> previewFiles) {
        OkHttpClient client = new OkHttpClient();
        try {
            // Build multipart body
            MultipartBody.Builder multipartBuilder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM);

            // Add text fields
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                multipartBuilder.addFormDataPart(entry.getKey(), entry.getValue());
            }

            // Add video files
            if (videoFiles != null) {
                for (File file : videoFiles) {
                    RequestBody fileBody = RequestBody.create(file, MediaType.parse("video/mp4"));
                    multipartBuilder.addFormDataPart("videoFiles", file.getName(), fileBody);
                }
            }

            // Add preview files
            if (previewFiles != null) {
                for (File file : previewFiles) {
                    RequestBody fileBody = RequestBody.create(file, MediaType.parse("video/mp4"));
                    multipartBuilder.addFormDataPart("previewFiles", file.getName(), fileBody);
                }
            }

            RequestBody requestBody = multipartBuilder.build();

            // Build request
            Request request = new Request.Builder()
                    .url(serverUrl)
                    .post(requestBody)
                    .build();

            // Execute asynchronously
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    LoggingManager.LogExceptionToNoteOverlay(context, e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        String responseBody = response.body().string();
                        LoggingManager.LogToToast(context, "Server returned OK status: " + responseBody);
                    } else {
                        LoggingManager.LogToToast(context, "Server returned non-OK status: " + response.code());
                    }
                }
            });

        } catch (Exception e) {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }


}
