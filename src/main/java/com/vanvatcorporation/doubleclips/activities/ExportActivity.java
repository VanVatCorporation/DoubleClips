package com.vanvatcorporation.doubleclips.activities;

import static com.vanvatcorporation.doubleclips.FFmpegEdit.generateExportCmd;
import static com.vanvatcorporation.doubleclips.FFmpegEdit.runAnyCommand;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.Log;
import com.arthenica.ffmpegkit.LogCallback;
import com.arthenica.ffmpegkit.MediaInformationSession;
import com.arthenica.ffmpegkit.Statistics;
import com.arthenica.ffmpegkit.StatisticsCallback;
import com.vanvatcorporation.doubleclips.FFmpegEdit;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.helper.ParserHelper;
import com.vanvatcorporation.doubleclips.impl.AppCompatActivityImpl;
import com.vanvatcorporation.doubleclips.impl.java.RunnableImpl;

public class ExportActivity extends AppCompatActivityImpl {

    EditingActivity.Timeline timeline;
    MainActivity.ProjectData properties;
    EditingActivity.VideoSettings settings;


    ProgressBar statusBar, globalStatusBar;
    TextView logText, statusText, globalStatusText;
    EditText widthText, heightText, frameRateText, crfText, commandText;
    ScrollView logScroll;
    Spinner presetSpinner, tuneSpinner;


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
            if(FFmpegEdit.queue.currentRenderQueue == null) return;
            statusText.setText(FFmpegEdit.queue.currentRenderQueue.taskName);

            globalStatusBar.setMax(FFmpegEdit.queue.totalQueue);
            globalStatusBar.setProgress(FFmpegEdit.queue.queueDone);

            globalStatusText.setText("Running tasks: " + "(" + FFmpegEdit.queue.queueDone + "/" + FFmpegEdit.queue.totalQueue + ")");

            if(!FFmpegEdit.queue.isRunning) {
                isLogUpdateRunning = false;
            }
            if(isLogUpdateRunning)
                logUpdateHandler.postDelayed(logUpdateRunnable, 500);
        }
    };




    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.layout_export);

        properties = (MainActivity.ProjectData) createrBundle.getSerializable("ProjectProperties");
        timeline = (EditingActivity.Timeline) createrBundle.getSerializable("ProjectTimeline");
        settings = (EditingActivity.VideoSettings) createrBundle.getSerializable("ProjectSettings");

        findViewById(R.id.generateCmdButton).setOnClickListener(v -> {
            generateCommand();
        });
        findViewById(R.id.exportButton).setOnClickListener(v -> {
            exportClip();
        });
        logText = findViewById(R.id.logText);
        statusBar = findViewById(R.id.statusBar);
        globalStatusBar = findViewById(R.id.globalStatusBar);
        statusText = findViewById(R.id.statusText);
        globalStatusText = findViewById(R.id.globalStatusText);
        logScroll = findViewById(R.id.logScroll);

        widthText = findViewById(R.id.exportWidth);
        heightText = findViewById(R.id.exportHeight);
        frameRateText = findViewById(R.id.exportFrameRate);
        crfText = findViewById(R.id.exportCRF);
        commandText = findViewById(R.id.exportCommand);

        presetSpinner = findViewById(R.id.exportPreset);
        presetSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new String[]{
                EditingActivity.VideoSettings.FfmpegPreset.PLACEBO,
                EditingActivity.VideoSettings.FfmpegPreset.VERYSLOW,
                EditingActivity.VideoSettings.FfmpegPreset.SLOWER,
                EditingActivity.VideoSettings.FfmpegPreset.SLOW,
                EditingActivity.VideoSettings.FfmpegPreset.MEDIUM,
                EditingActivity.VideoSettings.FfmpegPreset.FAST,
                EditingActivity.VideoSettings.FfmpegPreset.FASTER,
                EditingActivity.VideoSettings.FfmpegPreset.VERYFAST,
                EditingActivity.VideoSettings.FfmpegPreset.SUPERFAST,
                EditingActivity.VideoSettings.FfmpegPreset.ULTRAFAST
        }));
        presetSpinner.setSelection(9); // ULTRAFAST
        tuneSpinner = findViewById(R.id.exportTune);
        tuneSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new String[]{
                EditingActivity.VideoSettings.FfmpegTune.FILM,
                EditingActivity.VideoSettings.FfmpegTune.ANIMATION,
                EditingActivity.VideoSettings.FfmpegTune.GRAIN,
                EditingActivity.VideoSettings.FfmpegTune.STILLIMAGE,
                EditingActivity.VideoSettings.FfmpegTune.FASTDECODE,
                EditingActivity.VideoSettings.FfmpegTune.ZEROLATENCY
        }));
        tuneSpinner.setSelection(5); // ZEROLATENCY



        // Detect the last export session, if exist, try to export again.
        String inputPath = IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_EXPORT_CLIP_FILENAME);
        if(IOHelper.isFileExist(inputPath))
        {
            exportClipTo();
        }
    }
    private void runLogUpdate() {
        isLogUpdateRunning = true;
        logUpdateHandler.post(logUpdateRunnable);
    }

    private void generateCommand()
    {
        EditingActivity.VideoSettings overrideSettings = new EditingActivity.VideoSettings(
                ParserHelper.TryParse(widthText.getText().toString(), settings.videoWidth),
                ParserHelper.TryParse(heightText.getText().toString(), settings.videoHeight),
                ParserHelper.TryParse(frameRateText.getText().toString(), settings.frameRate),
                ParserHelper.TryParse(crfText.getText().toString(), settings.crf),
                presetSpinner.getSelectedItem().toString(),
                tuneSpinner.getSelectedItem().toString()
        );
        widthText.setText(String.valueOf(overrideSettings.videoWidth));
        heightText.setText(String.valueOf(overrideSettings.videoHeight));
        frameRateText.setText(String.valueOf(overrideSettings.frameRate));
        crfText.setText(String.valueOf(overrideSettings.crf));

        runLogUpdate();
        String cmd = generateExportCmd(this, overrideSettings, timeline, properties);
        commandText.setText(cmd);
    }


    private void exportClip()
    {
        logText.post(() -> logText.setTextIsSelectable(false));
        FFmpegKit.cancel();

        if(commandText.getText().toString().isEmpty())
            generateCommand();

        String cmd = commandText.getText().toString();
        logText.setText(cmd);

        if(!isLogUpdateRunning)
            runLogUpdate();

        runAnyCommand(this, cmd, "Exporting Video", this::exportClipTo, () -> {
                    logText.post(() -> logText.setTextIsSelectable(true));
                }
                , new RunnableImpl() {
                    @Override
                    public <T> void runWithParam(T param) {
                        Log log = (Log) param;
                        logScroll.post(() -> {
                            logText.setText(logText.getText() + "\n" + log.getMessage());
                            logScroll.fullScroll(View.FOCUS_DOWN);
                        });
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
    //TODO: Delete the exported clip inside project path. Detect in the beginning the export.mp4 if its exist then do the same with this method to extract it out.
    private void exportClipTo()
    {
        logText.post(() -> logText.setTextIsSelectable(true));

        IOHelper.deleteFilesInDir(IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_CLIP_TEMP_DIRECTORY));
        // Request permission to create a file
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_TITLE, "export");
        filePickerLauncher.launch(Intent.createChooser(intent, "Select Export"));
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
}
