package com.vanvatcorporation.doubleclips.activities;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import com.vanvatcorporation.doubleclips.FXCommandEmitter;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.externalUtils.Random;
import com.vanvatcorporation.doubleclips.helper.DateHelper;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.helper.IOImageHelper;
import com.vanvatcorporation.doubleclips.helper.ImageHelper;
import com.vanvatcorporation.doubleclips.helper.MathHelper;
import com.vanvatcorporation.doubleclips.helper.ParserHelper;
import com.vanvatcorporation.doubleclips.helper.StringFormatHelper;
import com.vanvatcorporation.doubleclips.impl.AppCompatActivityImpl;
import com.vanvatcorporation.doubleclips.impl.ImageGroupView;
import com.vanvatcorporation.doubleclips.impl.TrackFrameLayout;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;
import com.vanvatcorporation.doubleclips.utils.ShaderUtils;
import com.vanvatcorporation.doubleclips.utils.TimelineUtils;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class EditingActivity extends AppCompatActivityImpl {

    //List<Track> trackList = new ArrayList<>();
    Timeline timeline = new Timeline();
    MainActivity.ProjectData properties;
    VideoSettings settings;

    private LinearLayout timelineTracksContainer, rulerContainer, trackInfoLayout;
    private RelativeLayout timelineWrapper, editingZone;
    private HorizontalScrollView timelineScroll, rulerScroll;
    private ScrollView timelineVerticalScroll, trackInfoVerticalScroll;
    private TextView currentTimePosText, durationTimePosText;
    private ImageButton addNewTrackButton;
    private GLSurfaceView previewView;
    private ImageButton playPauseButton, backButton;
    private Button exportButton;
    private MediaPlayer audioRenderer;
    private TimelineRenderer timelineRenderer;

    private TrackFrameLayout addNewTrackBlankTrackSpacer;


    private RelativeLayout editSpecificAreaText;
    private ImageButton closeTextWindowButton;
    private EditText textEditContent;
    private EditText textSizeContent;

    private RelativeLayout editSpecificAreaEffect;
    private ImageButton closeEffectWindowButton;
    private Spinner effectEditContent;
    private EditText effectDurationContent;

    private RelativeLayout editSpecificAreaTransition;
    private ImageButton closeTransitionWindowButton;
    private Button applyAllTransitionButton;
    private Spinner transitionEditContent;
    private EditText transitionDurationContent;
    private Spinner transitionModeEditContent;


    private RelativeLayout editMultipleAreaClips;
    private ImageButton closeClipsWindowButton;
    private EditText clipsDurationContent;



    private int trackCount = 0;
    private final int TRACK_HEIGHT = 100;
    private static final float MIN_CLIP_DURATION = 0.5f; // in seconds
    public static int pixelsPerSecond = 100;

    public static int centerOffset;
    int currentTimelineEnd = 0; // Keep a variable that tracks the furthest X position of any clip


    float currentTime = 0f; // seconds


    TransitionClip selectedKnot = null;
    Clip selectedClip = null;
    Track selectedTrack = null;

    ArrayList<Clip> selectedClips = new ArrayList<>();
    boolean isClipSelectMultiple;



    private ScaleGestureDetector scaleDetector;
    private float scaleFactor = 1f;
    private int basePixelsPerSecond = 100;
    private float currentTimeBeforeScrolling = -1;




    Handler playheadHandler = new Handler(Looper.getMainLooper());
    Runnable updatePlayhead = new Runnable() {
        @Override
        public void run() {
//            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
//                int currentPositionMs = mediaPlayer.getCurrentPosition();
//                float currentSeconds = currentPositionMs / 1000f;
//
//                int newScrollX = (int) (currentSeconds * pixelsPerSecond);
//                timelineScroll.scrollTo(newScrollX, 0);
//
//                playheadHandler.postDelayed(this, 16); // ~60fps
//            }
        }
    };
    private boolean isPlaying = false;
    private float frameInterval = 1f / 60f; // 60fps
    private Handler playbackHandler = new Handler(Looper.getMainLooper());
    private Runnable playbackLoop;




    @SuppressLint("Range")
    public String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                }
            } finally {
                cursor.close();
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }
    int getCurrentTimeInX()
    {
        return getTimeInX(currentTime);
    }
    int getTimeInX(float time)
    {
        return (int) (centerOffset + time * pixelsPerSecond);
    }

    private ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    // Check if multiple files are selected
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        for (int i = 0; i < count; i++) {
                            Uri fileUri = data.getClipData().getItemAt(i).getUri();
                            // Process each file URI
                            parseFileIntoWorkPathAndAddToTrack(fileUri, i);
                        }
                    } else if (data.getData() != null) {
                        // Single file selected
                        Uri fileUri = data.getData();
                        // Process the file URI
                        parseFileIntoWorkPathAndAddToTrack(fileUri, 0);
                    }
                }
            }
    );

    void parseFileIntoWorkPathAndAddToTrack(Uri uri, int index)
    {

        if(uri == null) return;
        if(selectedTrack == null) return;
        String filename = getFileName(uri);
        String clipPath = IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_CLIP_DIRECTORY, filename);
        IOHelper.writeToFileAsRaw(this, clipPath, IOHelper.readFromFileAsRaw(this, getContentResolver(), uri));

        float duration = 3f; // fallback default if needed
        String mimeType = getContentResolver().getType(uri);

        if(mimeType == null) return;

        if(mimeType.startsWith("video/") || mimeType.startsWith("audio/"))
            try {
                MediaExtractor extractor = new MediaExtractor();
                extractor.setDataSource(clipPath);

                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String trackMime = format.getString(MediaFormat.KEY_MIME);
                    if (trackMime != null) {
                        mimeType = trackMime;

                        if (format.containsKey(MediaFormat.KEY_DURATION)) {
                            long d = format.getLong(MediaFormat.KEY_DURATION);
                            duration = d / 1_000_000f; // microseconds to seconds
                            break;
                        }
                    }
                }

                extractor.release();
            } catch (Exception e) {
                LoggingManager.LogExceptionToNoteOverlay(this, e);
            }



        ClipType type;

        if (mimeType.startsWith("audio/")) type = ClipType.AUDIO;
        else if (mimeType.startsWith("image/")) type = ClipType.IMAGE;
        else if (mimeType.startsWith("video/")) type = ClipType.VIDEO;
        else type = ClipType.EFFECT; // if effect or unknown

        System.err.println(type);


        Clip newClip = new Clip(filename, currentTime + index, duration, selectedTrack.trackIndex, type);
        addClipToTrack(selectedTrack, newClip);
    }


    void pickingContent()
    {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); // Allow multiple
        filePickerLauncher.launch(Intent.createChooser(intent, "Select Media"));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        properties = (MainActivity.ProjectData) createrBundle.getSerializable("ProjectProperties");

        //settings = new VideoSettings(1280, 720, 30, 30, VideoSettings.FfmpegPreset.MEDIUM, VideoSettings.FfmpegTune.ZEROLATENCY);
        settings = new VideoSettings(1366, 768, 30, 30, VideoSettings.FfmpegPreset.MEDIUM, VideoSettings.FfmpegTune.ZEROLATENCY);


        setContentView(R.layout.layout_editing);
        timelineTracksContainer = findViewById(R.id.timeline_tracks_container);
        trackInfoLayout = findViewById(R.id.trackInfoLayout);

        timelineWrapper = findViewById(R.id.timeline_wrapper);


        editingZone = findViewById(R.id.editingZone);

        currentTimePosText = findViewById(R.id.currentTimePosText);
        durationTimePosText = findViewById(R.id.durationTimePosText);

        // Example: Add button to add more tracks
        addNewTrackButton = findViewById(R.id.addTrackButton);
        addNewTrackButton.setOnClickListener(v -> {
            Track track = addNewTrack();
            track.viewRef.trackInfo = track;
        });
        //timelineTracksContainer.addView(addTrackButton);

        previewView = findViewById(R.id.previewView);

        playPauseButton = findViewById(R.id.playPauseButton);
        playPauseButton.setOnClickListener(v -> {

            isPlaying = !isPlaying;

            if (isPlaying) {
                startPlayback();
                playPauseButton.setImageResource(R.drawable.baseline_pause_circle_24);

                // TODO: Temporary processing real-time audio !
                for(Track audioTrack : timeline.tracks)
                {
                    for(Clip audioClip : audioTrack.clips)
                    {
                        if(audioClip.type == ClipType.AUDIO)
                        {
                            if(currentTime - audioClip.startTime > 0)
                            {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    audioRenderer.seekTo((int) ((currentTime - audioClip.startTime) * 1000), MediaPlayer.SEEK_CLOSEST);
                                }
                                else {
                                    audioRenderer.seekTo((int) ((currentTime - audioClip.startTime) * 1000));
                                }
                                audioRenderer.start();
                                break;
                            }
                        }
                    }
                }
            } else {
                stopPlayback();
                playPauseButton.setImageResource(R.drawable.baseline_play_circle_24);


                LoggingManager.LogToToast(this, "Begin prepare for preview!");
                //refreshPreviewClip();

                // TODO: Temporary processing real-time audio !
                for(Track audioTrack : timeline.tracks)
                {
                    for(Clip audioClip : audioTrack.clips)
                    {
                        if(audioClip.type == ClipType.AUDIO)
                        {
                            try {
                                audioRenderer.setDataSource(audioClip.getAbsolutePath(properties));
                                audioRenderer.prepareAsync();
                            } catch (IOException e) {
                                LoggingManager.LogToPersistentDataPath(this, LoggingManager.getStackTraceFromException(e));
                            }
                        }
                    }
                }
                timelineRenderer.buildTimeline(timeline);

                previewView.queueEvent(() -> {
                    timelineRenderer.initClips(); // safe again
                });
            }
        });
        exportButton = findViewById(R.id.exportButton);
        exportButton.setOnClickListener(v -> {
            Timeline.saveTimeline(this, timeline, properties);
            Intent intent = new Intent(this, ExportActivity.class);
            intent.putExtra("ProjectProperties", properties);
            intent.putExtra("ProjectSettings", settings);
            intent.putExtra("ProjectTimeline", timeline);
            startActivity(intent);
        });
        backButton = findViewById(R.id.backButton);
        backButton.setOnClickListener(v -> {
            finish();
        });




        // Time ruler
        rulerContainer = findViewById(R.id.ruler_container);

        timelineScroll = findViewById(R.id.trackHorizontalScrollView);
        rulerScroll = findViewById(R.id.ruler_scroll);
        timelineScroll.post(() -> {

            centerOffset = timelineScroll.getWidth() / 2;
            timelineScroll.scrollTo(centerOffset, 0);

            // Add initial track after centerOffset is taken
            //addNewTrack();



            timeline = Timeline.loadTimeline(this, this, properties);
        });
        timelineScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            rulerScroll.scrollTo(timelineScroll.getScrollX(), 0);

            if(!isPlaying)
                currentTime = (timelineScroll.getScrollX()) / (float) pixelsPerSecond;

            // Get time (- centerOffset mean remove the start spacer)
            //float totalSeconds = (timelineScroll.getScrollX()) / (float) pixelsPerSecond;
            currentTimePosText.post(() -> currentTimePosText.setText(DateHelper.convertTimestampToMMSSFormat((long) (currentTime * 1000L)) + String.format(".%02d", ((long)((currentTime % 1) * 100)))));

            timelineRenderer.updatePlayhead(currentTime);
            previewView.requestRender(); // Only if not using CONTINUOUSLY
        });


        timelineVerticalScroll = findViewById(R.id.trackVerticalScrollView);
        trackInfoVerticalScroll = findViewById(R.id.trackInfoScroll);


        timelineVerticalScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            trackInfoVerticalScroll.scrollTo(0, timelineVerticalScroll.getScrollY());
        });



        addNewTrackBlankTrackSpacer = new TrackFrameLayout(this);
        addNewTrackBlankTrackSpacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,//ViewGroup.LayoutParams.MATCH_PARENT,
                TRACK_HEIGHT
        ));
        addNewTrackBlankTrackSpacer.setBackgroundColor(Color.parseColor("#222222"));
        addNewTrackBlankTrackSpacer.setPadding(4, 4, 4, 4);
        handleTrackInteraction(addNewTrackBlankTrackSpacer);







        // Bottom Navigation Bar
        ImageView addVideoButton = findViewById(R.id.addMediaButton);
        addVideoButton.setOnClickListener(v -> {
            if(selectedTrack != null)
                pickingContent();
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a track first!").show();
        });
        ImageView deleteMediaButton = findViewById(R.id.deleteMediaButton);
        deleteMediaButton.setOnClickListener(v -> {
            if(selectedClip != null) {
                selectedClip.deleteClip(timeline);
                updateCurrentClipEnd();
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a clip first!").show();

        });
        ImageView splitMediaButton = findViewById(R.id.splitMediaButton);
        splitMediaButton.setOnClickListener(v -> {
            List<Clip> affectedClips = timeline.getClipAtCurrentTime(currentTime);
            if(selectedClip != null && affectedClips.contains(selectedClip)) {
                    selectedClip.splitClip(this, timeline, currentTime);
            }
            else {
                for (Clip clip : affectedClips) {
                    clip.splitClip(this, timeline, currentTime);
                }

            }
        });
        ImageView addTextButton = findViewById(R.id.addTextButton);
        addTextButton.setOnClickListener(v -> {

            if(selectedTrack == null) {
                new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a track first!").show();
                return;
            }

            float duration = 3f; // fallback default if needed
            ClipType type = ClipType.TEXT; // if effect or unknown



            Clip newClip = new Clip("TEXT", currentTime, duration, selectedTrack.trackIndex, type);
            newClip.textContent = "Simple text";
            newClip.fontSize = 30;
            addClipToTrack(selectedTrack, newClip);
        });
        ImageView addEffectButton = findViewById(R.id.addEffectButton);
        addEffectButton.setOnClickListener(v -> {

            if(selectedTrack == null) {
                new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a track first!").show();
                return;
            }

            float duration = 3f; // fallback default if needed
            ClipType type = ClipType.EFFECT; // if effect or unknown

            Clip newClip = new Clip("EFFECT", currentTime, duration, selectedTrack.trackIndex, type);
            newClip.effect = new EffectTemplate("glitch-pulse", 1.2, 4.0);

            addClipToTrack(selectedTrack, newClip);
        });
        ImageView editMediaButton = findViewById(R.id.editMediaButton);
        editMediaButton.setOnClickListener(v -> {
            if(selectedClip != null) {
                editingSpecific(selectedClip.type);
            }
            else if(!selectedClips.isEmpty())
            {
                editingMultiple();
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a clip first!").show();
        });
        ImageView addKeyframeButton = findViewById(R.id.addKeyframeButton);
        addKeyframeButton.setOnClickListener(v -> {
            if(selectedClip != null) {
                addKeyframe(selectedClip);
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a clip first!").show();
        });
        ImageView selectMultipleButton = findViewById(R.id.selectMultipleButton);
        selectMultipleButton.setOnClickListener(v -> {
            // Todo: Not fully implemented yet. The idea is to remake the whole thing, get the "array" of selected clip is completed
            // now if one clip is move then the whole array move along. Also ghost will be as well

            isClipSelectMultiple = !isClipSelectMultiple;

            selectMultipleButton.setColorFilter((isClipSelectMultiple ? 0xFFFF0000 : 0xFFFFFFFF), PorterDuff.Mode.SRC_ATOP);
        });
        ImageView selectAllButton = findViewById(R.id.selectAllButton);
        selectAllButton.setOnClickListener(v -> {
            // Todo: Not fully implemented yet. The idea is to remake the whole thing, get the "array" of selected clip is completed
            // now if one clip is move then the whole array move along. Also ghost will be as well


            if(selectedTrack != null) {
                isClipSelectMultiple = true;
                selectMultipleButton.setColorFilter(0xFFFF0000, PorterDuff.Mode.SRC_ATOP);

                deselectingClip();

                for (Clip clip : selectedTrack.clips) {
                    selectingClip(clip);
                }
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a track first!").show();
        });
        ImageView autoSnapButton = findViewById(R.id.autoSnapButton);
        autoSnapButton.setOnClickListener(v -> {
            if(selectedTrack != null) {
                selectedTrack.sortClips();
                List<Clip> clips = selectedTrack.clips;
                for (int i = 1; i < clips.size(); i++) {
                    Clip clip = clips.get(i);
                    Clip prevClip = clips.get(i - 1);

                    clip.startTime = prevClip.startTime + prevClip.duration;
                }

                updateClipLayouts();
                updateCurrentClipEnd();
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a track first!").show();
        });





        setupPreview();

        setupTimelinePinchAndZoom();

        setupSpecificEdit();
    }
    private void editingMultiple() {
        editMultipleAreaClips.setVisibility(View.VISIBLE);
        clipsDurationContent.setText(String.valueOf(selectedClips.get(0).duration));
    }
    private void editingSpecific(ClipType type) {
        switch (type)
        {
            case TEXT:
                editSpecificAreaText.setVisibility(View.VISIBLE);
                textEditContent.setText(selectedClip.textContent);
                textSizeContent.setText(String.valueOf(selectedClip.fontSize));
                break;
            case EFFECT:
                editSpecificAreaEffect.setVisibility(View.VISIBLE);
                List<String> stringEffects = Arrays.asList(FXCommandEmitter.FXRegistry.effectsFXMap.values().toArray(new String[0]));
                effectEditContent.setSelection(stringEffects.indexOf(FXCommandEmitter.FXRegistry.effectsFXMap.get(selectedClip.effect.style)));
                effectDurationContent.setText(String.valueOf(selectedClip.effect.duration));
                break;
            case TRANSITION:
                editSpecificAreaTransition.setVisibility(View.VISIBLE);
                List<String> stringTransition = Arrays.asList(FXCommandEmitter.FXRegistry.transitionFXMap.values().toArray(new String[0]));
                transitionEditContent.setSelection(stringTransition.indexOf(FXCommandEmitter.FXRegistry.transitionFXMap.get(selectedKnot.effect.style)));
                transitionDurationContent.setText(String.valueOf(selectedKnot.effect.duration));
                transitionModeEditContent.setSelection(selectedKnot.mode.ordinal());
                break;
        }
    }

    private void setupSpecificEdit()
    {
        // ===========================       TEXT ZONE       ====================================
        editSpecificAreaText = (RelativeLayout) LayoutInflater.from(this).inflate(R.layout.view_edit_specific_text, null);
        editingZone.addView(editSpecificAreaText);
        closeTextWindowButton = editSpecificAreaText.findViewById(R.id.closeWindowButton);
        textEditContent = editSpecificAreaText.findViewById(R.id.textContent);
        textSizeContent = editSpecificAreaText.findViewById(R.id.sizeContent);
        editSpecificAreaText.setVisibility(View.GONE);
        // ===========================       TEXT ZONE       ====================================


        // ===========================       EFFECT ZONE       ====================================
        editSpecificAreaEffect = (RelativeLayout) LayoutInflater.from(this).inflate(R.layout.view_edit_specific_effect, null);
        editingZone.addView(editSpecificAreaEffect);
        closeEffectWindowButton = editSpecificAreaEffect.findViewById(R.id.closeWindowButton);
        effectEditContent = editSpecificAreaEffect.findViewById(R.id.effectContent);
        effectDurationContent = editSpecificAreaEffect.findViewById(R.id.durationContent);
        editSpecificAreaEffect.setVisibility(View.GONE);
        // ===========================       EFFECT ZONE       ====================================


        // ===========================       TRANSITION ZONE       ====================================
        editSpecificAreaTransition = (RelativeLayout) LayoutInflater.from(this).inflate(R.layout.view_edit_specific_transition, null);
        editingZone.addView(editSpecificAreaTransition);
        closeTransitionWindowButton = editSpecificAreaTransition.findViewById(R.id.closeWindowButton);
        applyAllTransitionButton = editSpecificAreaTransition.findViewById(R.id.applyAllButton);
        transitionEditContent = editSpecificAreaTransition.findViewById(R.id.transitionContent);
        transitionDurationContent = editSpecificAreaTransition.findViewById(R.id.durationContent);
        transitionModeEditContent = editSpecificAreaTransition.findViewById(R.id.transitionModeContent);
        editSpecificAreaTransition.setVisibility(View.GONE);
        // ===========================       TRANSITION ZONE       ====================================




        // ===========================       MULTIPLE CLIPS ZONE       ====================================
        editMultipleAreaClips = (RelativeLayout) LayoutInflater.from(this).inflate(R.layout.view_edit_multiple_clips, null);
        editingZone.addView(editMultipleAreaClips);
        closeClipsWindowButton = editMultipleAreaClips.findViewById(R.id.closeWindowButton);
        clipsDurationContent = editMultipleAreaClips.findViewById(R.id.durationContent);
        editMultipleAreaClips.setVisibility(View.GONE);
        // ===========================       MULTIPLE CLIPS ZONE       ====================================






        // ===========================       TEXT ZONE       ====================================

        closeTextWindowButton.setOnClickListener(v -> {
            editSpecificAreaText.setVisibility(View.GONE);

            if(selectedClip != null)
            {
                selectedClip.textContent = textEditContent.getText().toString();
                selectedClip.fontSize = ParserHelper.TryParse(textSizeContent.getText().toString(), 28f);
            }
        });

        // ===========================       TEXT ZONE       ====================================


        // ===========================       EFFECT ZONE       ====================================

        effectEditContent.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, FXCommandEmitter.FXRegistry.effectsFXMap.values().toArray(new String[0])));

        closeEffectWindowButton.setOnClickListener(v -> {
            editSpecificAreaEffect.setVisibility(View.GONE);

            if(selectedClip != null)
            {
                selectedClip.effect = new EffectTemplate((String) FXCommandEmitter.FXRegistry.effectsFXMap.keySet().toArray()[effectEditContent.getSelectedItemPosition()], ParserHelper.TryParse(effectDurationContent.getText().toString(), 1), 1);
            }
        });

        // ===========================       EFFECT ZONE       ====================================


        // ===========================       TRANSITION ZONE       ====================================

        transitionEditContent.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, FXCommandEmitter.FXRegistry.transitionFXMap.values().toArray(new String[0])));
        transitionModeEditContent.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, TransitionClip.TransitionMode.values()));

        closeTransitionWindowButton.setOnClickListener(v -> {
            editSpecificAreaTransition.setVisibility(View.GONE);

            if(selectedKnot != null)
            {
                for (int i = 0; i < timeline.tracks.get(selectedKnot.trackIndex).transitions.size(); i++)
                {
                    TransitionClip clip = timeline.tracks.get(selectedKnot.trackIndex).transitions.get(i);
                    if(clip == selectedKnot)
                    {
                        clip.duration = ParserHelper.TryParse(transitionDurationContent.getText().toString(), 0.5f);
                        clip.effect.style = (String) FXCommandEmitter.FXRegistry.transitionFXMap.keySet().toArray()[transitionEditContent.getSelectedItemPosition()];
                        clip.effect.duration = ParserHelper.TryParse(transitionDurationContent.getText().toString(), 0.5f);
                        clip.mode = TransitionClip.TransitionMode.values()[transitionModeEditContent.getSelectedItemPosition()];

                        for (TransitionClip clip2 : timeline.tracks.get(selectedKnot.trackIndex).transitions)
                            System.err.println(clip2.duration);
                        selectedKnot = null;
                        break;
                    }
                }
            }
        });
        applyAllTransitionButton.setOnClickListener(v -> {
            editSpecificAreaTransition.setVisibility(View.GONE);
            if(selectedKnot != null)
            {
                for (int i = 0; i < timeline.tracks.get(selectedKnot.trackIndex).transitions.size(); i++)
                {
                    TransitionClip clip = timeline.tracks.get(selectedKnot.trackIndex).transitions.get(i);
                    clip.duration = ParserHelper.TryParse(transitionDurationContent.getText().toString(), 0.5f);
                    clip.effect.style = (String) FXCommandEmitter.FXRegistry.transitionFXMap.keySet().toArray()[transitionEditContent.getSelectedItemPosition()];
                    clip.effect.duration = ParserHelper.TryParse(transitionDurationContent.getText().toString(), 0.5f);
                    clip.mode = TransitionClip.TransitionMode.values()[transitionModeEditContent.getSelectedItemPosition()];
                }
                for (TransitionClip clip : timeline.tracks.get(selectedKnot.trackIndex).transitions)
                    System.err.println(clip.duration);
                selectedKnot = null;

            }
        });

        // ===========================       TRANSITION ZONE       ====================================



        // ===========================       MULTIPLE CLIPS ZONE       ====================================

        closeClipsWindowButton.setOnClickListener(v -> {
            editMultipleAreaClips.setVisibility(View.GONE);

            if(!selectedClips.isEmpty())
            {
                for (Clip clip : selectedClips) {
                    float defaultValue = clip.duration;
                    clip.duration = ParserHelper.TryParse(clipsDurationContent.getText().toString(), defaultValue);
                }
                updateClipLayouts();
                updateCurrentClipEnd();
            }
        });


        // ===========================       MULTIPLE CLIPS ZONE       ====================================


    }
    public void setupTimelinePinchAndZoom() {
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                if(currentTimeBeforeScrolling == -1)
                {
                    currentTimeBeforeScrolling = currentTime;
                }

                scaleFactor *= detector.getScaleFactor();

                // Clamp scale factor
                scaleFactor = Math.max(0.05f, Math.min(scaleFactor, 8.0f));

                pixelsPerSecond = (int) (basePixelsPerSecond * scaleFactor);
                updateTimelineZoom();
                return true;
            }
        });

    }

    private void startPlayback() {
        playbackLoop = new Runnable() {
            @Override
            public void run() {
                if (!isPlaying) return;
                currentTime += frameInterval;

                timelineRenderer.updatePlayhead(currentTime);
                //previewView.requestRender();

                int newScrollX = (int) (currentTime * pixelsPerSecond);
                timelineScroll.scrollTo(newScrollX, 0);

                if (currentTime >= timeline.duration) {
                    isPlaying = false;
                    currentTime = 0f;
                    stopPlayback();
                    playPauseButton.setImageResource(R.drawable.baseline_play_circle_24);
                }


                playbackHandler.postDelayed(this, (long)(frameInterval * 1000));
            }
        };
        playbackHandler.post(playbackLoop);
    }

    private void stopPlayback() {
        playbackHandler.removeCallbacks(playbackLoop);
    }
    private void setCurrentTime(float value)
    {
        currentTime = value;
        timelineScroll.scrollTo((int) (currentTime * pixelsPerSecond), 0);
    }

    void setupPreview()
    {
        audioRenderer = new MediaPlayer();
        timelineRenderer = new TimelineRenderer(this);

        previewView.setEGLContextClientVersion(2);
        previewView.setRenderer(timelineRenderer);
        //previewView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY); // For live playback
        previewView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }
    void reloadPreviewClip()
    {
//        mediaPlayer.reset();
//        try {
//            mediaPlayer.setDataSource(IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_PREVIEW_CLIP_FILENAME));
//        } catch (IOException e) {
//            LoggingManager.LogExceptionToNoteOverlay(this, e);
//        }
//        mediaPlayer.prepareAsync();
//
//        mediaPlayer.setOnPreparedListener(mp -> {
//            playPauseButton.setOnClickListener(v -> {
//                if (mediaPlayer.isPlaying()) {
//                    mediaPlayer.pause();
//                    playPauseButton.setImageResource(R.drawable.baseline_play_circle_24);
//                } else {
//                    mediaPlayer.start();
//                    playheadHandler.post(updatePlayhead); // Start syncing
//                    playPauseButton.setImageResource(R.drawable.baseline_pause_circle_24);
//                }
//            });
//        });
//
//        mediaPlayer.setOnCompletionListener(mp -> {
//            playPauseButton.setImageResource(R.drawable.baseline_play_circle_24);
//            playPauseButton.setOnClickListener(v -> {
//                mediaPlayer.start();
//                playheadHandler.post(updatePlayhead); // Start syncing
//                playPauseButton.setImageResource(R.drawable.baseline_pause_circle_24);
//            });
//        });


    }

    @Override
    public void finish() {
        super.finish();

        Timeline.saveTimeline(this, timeline, properties);
    }
    @Override
    public void onPause() {
        super.onPause();

        Timeline.saveTimeline(this, timeline, properties);
    }

    private Track addNewTrack() {
        Track trackInfo = new Track(trackCount, addNewTrackUi());
        timeline.addTrack(trackInfo);

        return trackInfo;
        // Add a sample clip to the new track
//        addClipToTrack(trackInfo, "/storage/emulated/0/DoubleClips/sample.mp4", 0, Random.Range(1, 6));
    }

    private TrackFrameLayout addNewTrackUi() {
        TrackFrameLayout track = new TrackFrameLayout(this);
        track.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,//ViewGroup.LayoutParams.MATCH_PARENT,
                TRACK_HEIGHT
        ));
        track.setBackgroundColor(Color.parseColor("#222222"));
        track.setPadding(4, 4, 4, 4);

        // 👻 Add spacer to align 0s with center playhead
        View startSpacer = new View(this);
        TrackFrameLayout.LayoutParams spacerParams = new TrackFrameLayout.LayoutParams(
                centerOffset,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        startSpacer.setLayoutParams(spacerParams);
        track.addView(startSpacer); // Add spacer before any clips


        View endSpacer = new View(this);
        TrackFrameLayout.LayoutParams endParams = new TrackFrameLayout.LayoutParams(
                centerOffset,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        endSpacer.setLayoutParams(endParams);
        track.addView(endSpacer); // Add this after all clips


        TextView trackInfoView = new TextView(this);
        LinearLayout.LayoutParams trackInfoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                TRACK_HEIGHT
        );
        trackInfoView.setLayoutParams(trackInfoParams);
        trackInfoView.setGravity(Gravity.CENTER);
        trackInfoView.setText("Track " + trackCount);



        timelineTracksContainer.removeView(addNewTrackBlankTrackSpacer);
        timelineTracksContainer.addView(track, trackCount);
        timelineTracksContainer.addView(addNewTrackBlankTrackSpacer);
        trackCount++;

        trackInfoLayout.removeView(addNewTrackButton);
        trackInfoLayout.addView(trackInfoView);
        trackInfoLayout.addView(addNewTrackButton);

        handleTrackInteraction(track);

        return track;
    }

    public void addClipToTrack(Track track, Clip data) {
        addClipToTrackUi(track.viewRef, data);

        track.addClip(data);
    }
    private void addClipToTrackUi(TrackFrameLayout trackLayout, Clip data)
    {
        ImageGroupView clipView = new ImageGroupView(this);
        TrackFrameLayout.LayoutParams params = new TrackFrameLayout.LayoutParams(
                (int) (data.duration * pixelsPerSecond),
                TRACK_HEIGHT - 4 // 16
        );
        //params.leftMargin = getTimeInX(data.startTime);
        //params.topMargin = 4; // 8
        clipView.setX(getTimeInX(data.startTime));
        clipView.setLayoutParams(params);
        clipView.setFilledImageBitmap(combineThumbnails(extractThumbnail(this, data.getAbsolutePath(properties), data.type)));
        clipView.setTag(data);


        data.registerClipHandle(clipView, this, timelineScroll);


        clipView.post(() -> {
            updateCurrentClipEnd();
        });




        trackLayout.addView(clipView);
        handleClipInteraction(clipView);
    }
    public void addKnotTransition(TransitionClip clip, View clipB)
    {
        View knotView = LayoutInflater.from(this).inflate(R.layout.view_transition_knot, null);
        knotView.setVisibility(View.VISIBLE);

        knotView.setTag(clip);
        // Position it between clips
        int width = 50;
        int height = 50;

        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(width, height);
        //params.leftMargin = clipB.getLeft() - (width / 2); // center between clips
        params.topMargin = clipB.getTop() + (clipB.getHeight() / 2) - (height / 2);
        knotView.setX(clipB.getX() - (width / 2));
        timeline.tracks.get(clip.trackIndex).viewRef.addView(knotView, params);

        handleKnotInteraction(knotView);
    }
    public void addKeyframe(Clip clip)
    {
        View knotView = LayoutInflater.from(this).inflate(R.layout.view_transition_knot, null);
        knotView.setVisibility(View.VISIBLE);

        knotView.setTag(clip);
        // Position it between clips
        int width = 12;
        int height = 12;

        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(width, height);
        //params.leftMargin = getCurrentTimeInX();
        params.topMargin = clip.viewRef.getTop() + (clip.viewRef.getHeight() / 2);
        knotView.setX(getCurrentTimeInX());
        timeline.tracks.get(clip.trackIndex).viewRef.addView(knotView, params);



        clip.scaleKeyFrames.keyframes.add(new Keyframe(currentTime, Random.Range(0.5f, 3f), EasingType.LINEAR));
        clip.rotationKeyFrames.keyframes.add(new Keyframe(currentTime, Random.Range(0.5f, 3f), EasingType.LINEAR));

        handleKeyframeInteraction(knotView);
    }
    public void revalidationClipView(Clip data)
    {
        ImageGroupView clipView = data.viewRef;
        clipView.getLayoutParams().width = (int) (data.duration * pixelsPerSecond);


    }
    private void handleKeyframeInteraction(View view) {
        view.setOnClickListener(v -> {
            if(view.getTag() instanceof Clip)
            {
                Clip data = (Clip) view.getTag();
            }

        });
    }
    private void handleKnotInteraction(View view) {
        view.setOnClickListener(v -> {
            if(view.getTag() instanceof TransitionClip)
            {
                selectingKnot((TransitionClip) view.getTag());
                editingSpecific(ClipType.TRANSITION);
            }

        });
    }
    private void handleTrackInteraction(View view) {
        view.setOnClickListener(v -> {
            if(view instanceof TrackFrameLayout)
            {
                selectingTrack(((TrackFrameLayout) view).trackInfo);
            }
        });

        view.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            switch (event.getAction())
            {
                case MotionEvent.ACTION_MOVE:
                    if(event.getPointerCount() == 2) {
                        timelineScroll.requestDisallowInterceptTouchEvent(true);
                    }
                    break;
                    // ACTION_UP is the action that invoke only if we clicked
                    // that's mean its invoke if we didn't ACTION_MOVE
                case MotionEvent.ACTION_UP:
                    v.performClick();
                    currentTimeBeforeScrolling = -1;
                    timelineScroll.requestDisallowInterceptTouchEvent(false);
                    break;
                    // ACTION_CANCEL is when you accidentally click something and
                    // drag it somewhere so it doesn't recognize that click anymore
                case MotionEvent.ACTION_CANCEL:
                    currentTimeBeforeScrolling = -1;
                    timelineScroll.requestDisallowInterceptTouchEvent(false);
                    break;
            }
            return true;
        });

    }
    private void handleClipInteraction(View view) {
        DragContext dragContext = new DragContext();
        view.setOnClickListener(v -> {
            Clip clip = (Clip) view.getTag(); // Already stored

            selectingClip(clip);
        });


        view.setOnLongClickListener(v -> {

            Clip clip = (Clip) view.getTag(); // Already stored
            Track track = timeline.tracks.get(clip.trackIndex);



            timelineScroll.requestDisallowInterceptTouchEvent(true);

            // 👻 Create ghost
            ImageGroupView ghost = new ImageGroupView(v.getContext());
            ghost.setLayoutParams(new TrackFrameLayout.LayoutParams(v.getWidth(), v.getHeight()));
            ghost.setFilledImageBitmap(((ImageGroupView)v).getFilledImageBitmap());
            ghost.setAlpha(0.5f);




            track.viewRef.addView(ghost);
            ghost.setX(view.getX());
            ghost.setY(view.getY());

            v.setVisibility(View.INVISIBLE); // Hide original

            dragContext.ghost = ghost;
            dragContext.currentTrack = track;
            dragContext.clip = clip;

            return true;
        });

        view.setOnTouchListener(new View.OnTouchListener() {
            float dX;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                Clip data = (Clip) v.getTag();


                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = v.getX() - event.getRawX();
                        //dY = v.getY() - event.getRawY();
                        break;

                    case MotionEvent.ACTION_MOVE:
                        if (dragContext.ghost != null) {
                            float newX = event.getRawX() + dX;
                            if (newX < centerOffset) newX = centerOffset; // ⛔ Prevent going past 0s

                            float ghostWidth = dragContext.ghost.getWidth();
                            float ghostStart = newX;
                            float ghostEnd = newX + ghostWidth;


                            // 🧲 Check for snapping
                            // Snap the playhead
                            float playheadX = (timelineScroll.getScrollX() + centerOffset) - 2;

                            if (Math.abs(ghostStart - playheadX) < Constants.TRACK_CLIPS_SNAP_THRESHOLD_PIXEL) {
                                newX = playheadX;
                            }
                            if (Math.abs(ghostEnd - playheadX) < Constants.TRACK_CLIPS_SNAP_THRESHOLD_PIXEL) {
                                newX = playheadX - ghostWidth;
                            }



                            // Snap the other track
                            for (int i = 0; i < dragContext.currentTrack.viewRef.getChildCount(); i++) {
                                View other = dragContext.currentTrack.viewRef.getChildAt(i);
                                if (other == dragContext.ghost || other == v) continue;

                                float otherStart = other.getX();
                                float otherEnd = other.getX() + other.getWidth();

                                // Snap ghost start to other end
                                if (Math.abs(ghostStart - otherEnd) <= Constants.TRACK_CLIPS_SNAP_THRESHOLD_PIXEL) {
                                    newX = otherEnd;
                                    break;
                                }

                                // Snap ghost end to other start
                                if (Math.abs(ghostEnd - otherStart) <= Constants.TRACK_CLIPS_SNAP_THRESHOLD_PIXEL) {
                                    newX = otherStart - ghostWidth;
                                    break;
                                }

                                // Optional: Snap start-to-start or end-to-end
                                // Todo: Pending removal as no sense of letting clips overlapping each other in the same track in the near future.
                                if (Math.abs(ghostStart - otherStart) <= Constants.TRACK_CLIPS_SNAP_THRESHOLD_PIXEL) {
                                    newX = otherStart;
                                    break;
                                }
                                if (Math.abs(ghostEnd - otherEnd) <= Constants.TRACK_CLIPS_SNAP_THRESHOLD_PIXEL) {
                                    newX = otherEnd - ghostWidth;
                                    break;
                                }
                            }
                            dragContext.ghost.setX(newX);

                            // 🔍 Detect track under finger
                            Track targetTrack = null;
                            float touchY = event.getRawY();


                            for (Track track : timeline.tracks) {
                                TrackFrameLayout trackRef = track.viewRef;

                                int[] loc = new int[2];
                                trackRef.getLocationOnScreen(loc);
                                float top = loc[1];
                                float bottom = top + trackRef.getHeight();
                                // 🧲 Move ghost to new track if needed
                                if (touchY >= top && touchY <= bottom && track != dragContext.currentTrack) {
                                    dragContext.currentTrack.viewRef.removeView(dragContext.ghost);
                                    track.viewRef.addView(dragContext.ghost);
                                    dragContext.ghost.setY(4);
                                    dragContext.currentTrack = track;
                                    break;
                                }
                            }
                            return true;
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        timelineScroll.requestDisallowInterceptTouchEvent(false);

                        if (dragContext.ghost != null) {
                            float finalX = dragContext.ghost.getX();
                            if (finalX < centerOffset) finalX = centerOffset; // ⛔ Clamp again for safety


                            float finalX1 = finalX;

                            v.post(() -> {
                                // Move original to new track and position
                                ViewGroup oldParent = (ViewGroup) v.getParent();
                                oldParent.removeView(v);

                                // Add to new track
                                dragContext.currentTrack.viewRef.addView(v);
                                v.setX(finalX1);
                                v.setVisibility(View.VISIBLE);

                                // Update metadata
                                float newStartTime = (finalX1 - centerOffset) / pixelsPerSecond;
                                dragContext.clip.startTime = Math.max(0, newStartTime); // Clamp to 0
                                timeline.tracks.get(dragContext.clip.trackIndex).removeClip(dragContext.clip);
                                dragContext.clip.trackIndex = dragContext.currentTrack.trackIndex;
                                timeline.tracks.get(dragContext.clip.trackIndex).addClip((dragContext.clip));


                                updateCurrentClipEnd();


                                // Remove ghost
                                dragContext.currentTrack.viewRef.removeView(dragContext.ghost);
                                dragContext.ghost = null;




                                timeline.tracks.get(dragContext.clip.trackIndex).sortClips();
                            });

                        }
                        break;
                }
                return false;
            }
        });
    }



    void updateCurrentClipEnd()
    {
        updateCurrentClipEnd(true);
    }
    void updateCurrentClipEnd(boolean updateRuler)
    {
        int newTimelineEnd = 0;
        // 🧠 Recalculate max right edge of all clips in all tracks
        for (Track trackCpn : timeline.tracks) {
            for (int i = 0; i < trackCpn.viewRef.getChildCount(); i++) {
                View child = trackCpn.viewRef.getChildAt(i);
                if (child.getTag() != null && child.getTag() instanceof Clip) { // It's a clip
                    int right = (int) (child.getX() + child.getWidth());
                    if (right > newTimelineEnd) newTimelineEnd = right;
                }
            }
        }
        currentTimelineEnd = newTimelineEnd;

        // Get time (- centerOffset mean remove the start spacer)
        float totalSeconds = (currentTimelineEnd - centerOffset) / (float) pixelsPerSecond;
        durationTimePosText.post(() -> durationTimePosText.setText(DateHelper.convertTimestampToMMSSFormat((long) (totalSeconds * 1000L))));
        timeline.duration = totalSeconds;
        if(updateRuler)
            updateRuler(totalSeconds, currentRulerInterval);
        // 🧱 Expand end spacer
        for (Track trackCpn : timeline.tracks) {
            updateEndSpacer(trackCpn);
            updateTrackWidth(trackCpn);
            updateTransitionKnot(trackCpn);
        }



        //refreshPreviewClip();
    }
    void updateTransitionKnot(Track track)
    {

        ArrayList<Clip> snappedClipStart = new ArrayList<>();
        ArrayList<Clip> snappedClipEnd = new ArrayList<>();


//                              ArrayList<View> sortedTrackClips = IntStream.range(0, dragContext.currentTrack.viewRef.getChildCount()).mapToObj(i -> dragContext.currentTrack.viewRef.getChildAt(i)).filter(clipView -> clipView.getTag() instanceof Clip).collect(Collectors.toCollection(ArrayList::new));

        track.clearTransition();

        // Check for snapped track
        for (int i = 1; i < track.clips.size(); i++) {
            Clip at = track.clips.get(i - 1);
            Clip other = track.clips.get(i);
            // Snap ghost start to other end
            if (Math.abs(at.startTime - (other.startTime + other.duration)) <= Constants.TRACK_CLIPS_SNAP_THRESHOLD_PIXEL / pixelsPerSecond) {
                snappedClipEnd.add(at);
                snappedClipStart.add(other);
            }
            // Snap ghost end to other start
            if (Math.abs((at.startTime + at.duration) - other.startTime) <= Constants.TRACK_CLIPS_SNAP_THRESHOLD_PIXEL / pixelsPerSecond) {
                snappedClipEnd.add(other);
                snappedClipStart.add(at);
            }
        }

        for (int i = 0; i < snappedClipStart.size(); i++) {
            addTransitionBridge(snappedClipStart.get(i), snappedClipEnd.get(i), 0.2f);
        }
    }
    void updateTrackWidth(Track track)
    {
        track.viewRef.setLayoutParams(new LinearLayout.LayoutParams(
                centerOffset + currentTimelineEnd, // End spacer = centerOffset
                TRACK_HEIGHT
        ));
    }
    private void updateEndSpacer(Track track) {
        View existingSpacer = track.viewRef.findViewWithTag("end_spacer");
        if (existingSpacer != null) {
            track.viewRef.removeView(existingSpacer);
        }

        View endSpacer = new View(this);
        endSpacer.setTag("end_spacer");
        TrackFrameLayout.LayoutParams params = new TrackFrameLayout.LayoutParams(
                centerOffset, // Always half screen
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        endSpacer.setLayoutParams(params);
        track.viewRef.addView(endSpacer);
    }
    private void updateRuler(float totalSeconds, float interval) {
        rulerContainer.removeAllViews();



        // 👻 Add spacer to align 0s with center playhead
        View startSpacerRuler = new View(this);
        LinearLayout.LayoutParams spacerRulerParams = new LinearLayout.LayoutParams(
                centerOffset,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        startSpacerRuler.setLayoutParams(spacerRulerParams);
        rulerContainer.addView(startSpacerRuler); // Add spacer before any clips

        for (float i = 0; i <= totalSeconds; i += interval) {
            TextView tick = new TextView(this);
            tick.setText(StringFormatHelper.smartRound(i, 1, true) + "s");
            //tick.setTextColor(Color.BLACK);
            tick.setTextSize(12);
            tick.setGravity(Gravity.START | Gravity.BOTTOM);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    (int) (pixelsPerSecond * interval), ViewGroup.LayoutParams.MATCH_PARENT
            );
            tick.setLayoutParams(params);
            rulerContainer.addView(tick);
        }

        // Add end spacer to ruler
        View rulerEndSpacer = new View(this);
        rulerEndSpacer.setLayoutParams(new LinearLayout.LayoutParams(
                centerOffset, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        rulerContainer.addView(rulerEndSpacer);
    }
    float currentRulerInterval = 1f;
    float changedRulerInterval = 1f;

    private void updateRulerEfficiently() {
        if(pixelsPerSecond < 25 && pixelsPerSecond > 10)
        {
            changedRulerInterval = 8f;
        }
        if(pixelsPerSecond < 50 && pixelsPerSecond > 25)
        {
            changedRulerInterval = 4f;
        }
        if(pixelsPerSecond < 100 && pixelsPerSecond > 50)
        {
            changedRulerInterval = 2f;
        }
        if(pixelsPerSecond < 200 && pixelsPerSecond > 100)
        {
            changedRulerInterval = 1f;
        }
        if(pixelsPerSecond < 500 && pixelsPerSecond > 200)
        {
            changedRulerInterval = 0.5f;
        }
        if(pixelsPerSecond < 1000 && pixelsPerSecond > 500)
        {
            changedRulerInterval = 0.2f;
        }
        if(pixelsPerSecond < 2000 && pixelsPerSecond > 1000)
        {
            changedRulerInterval = 0.1f;
        }
        if(pixelsPerSecond < 5000 && pixelsPerSecond > 2000)
        {
            changedRulerInterval = 0.05f;
        }


        for (int i = 0; i < rulerContainer.getChildCount(); i++) {
            View tick = rulerContainer.getChildAt(i);
            if(!(tick instanceof TextView)) continue;
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) tick.getLayoutParams();
            params.width = (int) (pixelsPerSecond * changedRulerInterval); // or pixelsPerSecond / 2 for 0.5s ticks
            tick.setLayoutParams(params);
        }
    }

    private void updateClipLayouts() {
        for (Track track : timeline.tracks) {
            for (int i = 0; i < track.viewRef.getChildCount(); i++) {
                View clip = track.viewRef.getChildAt(i);
                if (clip.getTag() instanceof Clip) {
                    Clip data = (Clip) clip.getTag();

                    TrackFrameLayout.LayoutParams params = new TrackFrameLayout.LayoutParams(
                            (int) (data.duration * pixelsPerSecond),
                            TRACK_HEIGHT - 4);
                    //params.leftMargin = (int) (centerOffset + data.startTime * pixelsPerSecond);
                    //params.topMargin = 4;\
                    clip.setX(getTimeInX(data.startTime));
                    clip.setLayoutParams(params);
                }
            }
        }
    }
    private void updateEndSpacers()
    {
        for (Track track : timeline.tracks) {
            updateEndSpacer(track);
        }
    }

    private void updateTimelineZoom() {
        updateRulerEfficiently();
        boolean rulerChanged = false;
        if(changedRulerInterval != currentRulerInterval)
        {
            currentRulerInterval = changedRulerInterval;
            rulerChanged = true;
            updateRuler(timeline.duration, currentRulerInterval);
        }

        updateClipLayouts();
        updateCurrentClipEnd(false);
        //updateEndSpacers(); // updateCurrentClipEnd did it
        //updateEndSpacers(); // Optional: recalculate based on new width

        // Let playhead froze when scrolling
        setCurrentTime(currentTimeBeforeScrolling);


        //Todo: Still resource-intensive, recode it by cache the created thumbnail, then insert/remove accordingly.
        if(rulerChanged)
        {
            for (Track track : timeline.tracks) {
                for (Clip clip : track.clips) {
                    //clip.viewRef.setFilledImageBitmap(combineThumbnails(extractThumbnail(this, clip.filePath, clip.type)));
                }
            }
        }
    }
    private void addTransitionBridge(Clip clipA, Clip clipB, float transitionDuration)
    {
        //if (clipA.startTime + clipA.duration == clipB.startTime)
        {
            TransitionClip transition = new TransitionClip();
            transition.trackIndex = clipA.trackIndex;
            transition.fromClip = clipA;
            transition.toClip = clipB;
            transition.startTime = clipB.startTime - transitionDuration / 2;
            transition.duration = transitionDuration;
            transition.effect = new EffectTemplate("fade", transitionDuration, transition.startTime);
            transition.mode = TransitionClip.TransitionMode.OVERLAP;
            timeline.tracks.get(clipA.trackIndex).addTransition(transition);

            addKnotTransition(transition, clipB.viewRef);
        }

    }





    private void selectingClip(Clip selectedClip)
    {
        if(isClipSelectMultiple)
        {
            this.selectedClip = null;
            if(selectedClips.contains(selectedClip))
            {
                selectedClips.remove(selectedClip);
                selectedClip.deselect();
            }
            else
            {
                selectedClips.add(selectedClip);
                selectedClip.select();
            }
        }
        else {
            deselectingClip();

            if(this.selectedClip != null && this.selectedClip == selectedClip){
                this.selectedClip = null;
            }
            else {
                selectedClip.select();
                this.selectedClip = selectedClip;
            }
            if(currentTime < selectedClip.startTime)
                setCurrentTime(selectedClip.startTime);
        }

    }
    private void selectingTrack(Track selectedTrack)
    {
        deselectingTrack();

        if(selectedTrack == null) {
            deselectingClip();
            this.selectedClip = null;
            return;
        }
        if(this.selectedTrack != null && this.selectedTrack == selectedTrack){
            this.selectedTrack = null;
            deselectingClip();
            this.selectedClip = null;
        }
        else {
            deselectingClip();
            this.selectedClip = null;
            selectedTrack.select();
            this.selectedTrack = selectedTrack;
        }
    }
    private void selectingKnot(TransitionClip selectedKnot)
    {
        this.selectedKnot = selectedKnot;
    }
    private void deselectingClip()
    {
        if(isClipSelectMultiple)
            selectedClips.clear();

        for (Track track : timeline.tracks) {
            for (Clip clip : track.clips) {
                clip.deselect();
            }
        }
    }
    private void deselectingTrack()
    {
        for (Track track : timeline.tracks) {
            track.deselect();
        }
    }




    private void refreshPreviewClip()
    {
        //FFmpegEdit.generatePreviewVideo(this, timeline, settings, properties, this::reloadPreviewClip);
    }


    // Native Android user only
    private static List<Bitmap> extractThumbnail(Context context, String filePath, ClipType type)
    {
        return extractThumbnail(context, filePath, type, -1);
    }
    private static List<Bitmap> extractThumbnail(Context context, String filePath, ClipType type, int frameCountOverride)
    {
        List<Bitmap> thumbnails = new ArrayList<>();
        if(type == null)
            type = ClipType.EFFECT;
        Drawable drawable = ResourcesCompat.getDrawable(context.getResources(), R.drawable.ic_launcher_background, null);
        switch (type)
        {
            case VIDEO:
                try {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(filePath);

                    long durationMs = Long.parseLong(Objects.requireNonNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)));
                    int frameCount = frameCountOverride;
                    if(frameCountOverride == -1) frameCount = (int) (durationMs * pixelsPerSecond / 10000 / 100) + 1;
                    int originalWidth = Integer.parseInt(Objects.requireNonNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)));
                    int originalHeight = Integer.parseInt(Objects.requireNonNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)));

                    int desiredWidth = originalWidth / Constants.SAMPLE_SIZE_PREVIEW_CLIP;
                    int desiredHeight = originalHeight / Constants.SAMPLE_SIZE_PREVIEW_CLIP;

                    for (int i = 0; i < frameCount; i++) {
                        long timeUs = (durationMs * 1000L * i) / frameCount;
                        Bitmap frame;
                        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            frame = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, desiredWidth, desiredHeight);
                        }
                        else {
                            frame = Bitmap.createScaledBitmap(
                                    Objects.requireNonNull(retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)),
                                    desiredWidth, desiredHeight, true);

                        }
                        if (frame != null) {
                            thumbnails.add(frame);
                        }
                    }
                    retriever.release();
                    retriever.close();
                } catch (Exception e) {
                    LoggingManager.LogExceptionToNoteOverlay(context, e);
                }
                break;
            case IMAGE:
                thumbnails.add(IOImageHelper.LoadFileAsPNGImage(context, filePath, Constants.SAMPLE_SIZE_PREVIEW_CLIP));
                break;
            case TEXT:
                drawable.setColorFilter(0xAAFF0000, PorterDuff.Mode.SRC_ATOP);
                thumbnails.add(ImageHelper.createBitmapFromDrawable(drawable));
                break;
            case AUDIO:
                drawable.setColorFilter(0xAA0000FF, PorterDuff.Mode.SRC_ATOP);
                thumbnails.add(ImageHelper.createBitmapFromDrawable(drawable));
                break;
            case EFFECT:
                drawable.setColorFilter(0xAAFFFF00, PorterDuff.Mode.SRC_ATOP);
                thumbnails.add(ImageHelper.createBitmapFromDrawable(drawable));
                break;

        }


        return thumbnails;

    }

    private static Bitmap combineThumbnails(List<Bitmap> frames)
    {
        int totalWidth = frames.size() * frames.get(0).getWidth();
        int height = frames.get(0).getHeight();

        Bitmap combined = Bitmap.createBitmap(totalWidth, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(combined);

        int x = 0;
        for (Bitmap bmp : frames) {
            canvas.drawBitmap(bmp, x, 0, null);
            x += bmp.getWidth();
        }

        return combined;
    }




    // End Native Android













    public static class Timeline implements Serializable {
        @Expose
        public List<Track> tracks = new ArrayList<>();
        @Expose
        public float duration;

        public void addTrack(Track track) {
            tracks.add(track);
        }

        public List<Clip> getClipAtCurrentTime(float playheadTime) {
            List<Clip> clips = new ArrayList<>();
            for (Track track : tracks) {
                for (Clip clip : track.clips) {
                    if (playheadTime >= clip.startTime && playheadTime < clip.startTime + clip.duration) {
                        clips.add(clip);
                    }
                }
            }
            return clips; // No clip at this time
        }



        public static void saveTimeline(Context context, Timeline timeline, MainActivity.ProjectData data)
        {
            float max = 0;
            for (Track trackCpn : timeline.tracks) {
                float endTime = trackCpn.getTrackEndTime();
                if(endTime > max) max = endTime;

                trackCpn.sortClips();
            }
            timeline.duration = max;

            Clip nearestClip = null;
            for (Track trackCpn : timeline.tracks) {
                for (Clip c : trackCpn.clips)
                {
                    nearestClip = c;
                    break;
                }
                if(nearestClip != null) break;
            }
            if(nearestClip != null)
                IOImageHelper.SaveFileAsPNGImage(context, IOHelper.CombinePath(data.getProjectPath(), "preview.png"), extractThumbnail(context, nearestClip.getAbsolutePath(data), nearestClip.type, 1).get(0), 25);

            data.setProjectTimestamp(new Date().getTime());
            data.setProjectDuration((long) (timeline.duration * 1000));
            data.setProjectSize(IOHelper.getFileSize(context, data.getProjectPath()));


            String jsonTimeline = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create().toJson(timeline); // Save


            data.savePropertiesAtProject(context);
            IOHelper.writeToFile(context, IOHelper.CombinePath(data.getProjectPath(), Constants.DEFAULT_TIMELINE_FILENAME), jsonTimeline);
        }
        public static Timeline loadRawTimeline(Context context, MainActivity.ProjectData data)
        {
            String json = IOHelper.readFromFile(context, IOHelper.CombinePath(data.getProjectPath(), Constants.DEFAULT_TIMELINE_FILENAME));
            return new Gson().fromJson(json, Timeline.class);
        }
        public static Timeline loadTimeline(Context context, EditingActivity instance, MainActivity.ProjectData data)
        {
            return loadTimeline(context, instance, loadRawTimeline(context, data));
        }
        public static Timeline loadTimeline(Context context, EditingActivity instance, Timeline timeline)
        {
            if(timeline == null) return new Timeline();
            for (Track track : timeline.tracks) {
                track.viewRef = instance.addNewTrackUi();
                track.viewRef.trackInfo = track;

                for (Clip clip : track.clips) {
                    if (clip.type == null)
                        clip.type = ClipType.VIDEO;
                    instance.addClipToTrackUi(track.viewRef, clip);
                }
            }

            return timeline;
        }
    }

    public static class Track implements Serializable {
        @Expose
        public int trackIndex;
        @Expose
        public List<Clip> clips = new ArrayList<>();
        @Expose
        public List<TransitionClip> transitions = new ArrayList<>();

        //Not serializing
        public transient TrackFrameLayout viewRef;



        public Track(int trackIndex, TrackFrameLayout viewRef) {
            this.trackIndex = trackIndex;
            this.viewRef = viewRef;
        }

        public void addClip(Clip clip) {
            clips.add(clip);
        }

        public void removeClip(Clip clip) {
            clips.remove(clip);
        }
        public void sortClips()
        {
            clips.sort((o1, o2) -> (Float.compare(o1.startTime, o2.startTime)));
        }

        public float getTrackEndTime() {
            float max = 0f;
            for (Clip clip : clips) {
                float end = clip.startTime + clip.duration;
                if (end > max) max = end;
            }
            return max;
        }

        public void select() {
            viewRef.setBackgroundColor(0xFFAAAAAA);
        }
        public void deselect() {
            viewRef.setBackgroundColor(0xFF222222);
        }

        public void addTransition(TransitionClip transition) {
            transitions.add(transition);
        }
        public void removeTransitionUi(TransitionClip transition) {
            View targetView = viewRef.findViewWithTag(transition);
            if(targetView != null)
                viewRef.removeView(targetView);
        }
        public void removeTransition(TransitionClip transition) {
            transitions.remove(transition);
            removeTransitionUi(transition);
        }
        public void clearTransition() {
            for (TransitionClip clip : transitions) {
                removeTransitionUi(clip);
            }
            transitions.clear();
        }
        public void removeTransitionByClip(Clip clip) {
            List<TransitionClip> removalQueue = new ArrayList<>();
            for (TransitionClip transitionClip : transitions) {
                if(transitionClip.fromClip == clip || transitionClip.toClip == clip)
                    removalQueue.add(transitionClip);
                if(removalQueue.size() == 2) break;
            }

            for (TransitionClip clip2 : removalQueue) {
                removeTransitionUi(clip2);
            }
            transitions.removeAll(removalQueue);
        }
    }

    public static class Clip implements Serializable {
        @Expose
        public ClipType type;
        @Expose
        public String clipName;
        @Expose
        public float startTime; // in seconds
        @Expose
        public float duration;  // in seconds
        @Expose
        public float startClipTrim; // in seconds
        @Expose
        public float endClipTrim; // in seconds
        @Expose
        public float originalDuration;  // in seconds
        @Expose
        public int trackIndex;

        @Expose
        public float posX;
        @Expose
        public float posY;
        @Expose
        public float scaleX;
        @Expose
        public float scaleY;
        @Expose
        public float rotation; // in radians Todo: use this to convert from degrees to radians "Math.toRadians(clip.rotation);"

        @Expose
        public AnimatedProperty posXKeyFrames = new AnimatedProperty();
        @Expose
        public AnimatedProperty posYKeyFrames = new AnimatedProperty();
        @Expose
        public AnimatedProperty scaleKeyFrames = new AnimatedProperty();
        @Expose
        public AnimatedProperty rotationKeyFrames = new AnimatedProperty();
        // Use for keyframe pre-rendering
        @Expose
        public String preRenderedName;

        // FX support (for EFFECT type)
        @Expose
        public EffectTemplate effect; // for EFFECT type
        @Expose
        public String textContent;    // for TEXT type
        @Expose
        public float fontSize;    // for TEXT type


        //Not serializing
        public transient View leftHandle, rightHandle;
        public transient ImageGroupView viewRef;

        public Clip(String clipName, float startTime, float duration, int trackIndex, ClipType type) {
            this.clipName = clipName;
            this.startTime = startTime;
            this.startClipTrim = 0;
            this.endClipTrim = 0;
            this.duration = duration;
            this.originalDuration = duration;
            this.trackIndex = trackIndex;
            this.type = type;

            this.scaleX = 1;
            this.scaleY = 1;
        }

        public void registerClipHandle(ImageGroupView clipView, EditingActivity activity, HorizontalScrollView timelineScroll) {
            viewRef = clipView;

            leftHandle = new View(clipView.getContext());
            leftHandle.setBackgroundColor(Color.WHITE);
            RelativeLayout.LayoutParams leftParams = new RelativeLayout.LayoutParams(35, ViewGroup.LayoutParams.MATCH_PARENT);
            leftParams.addRule(RelativeLayout.ALIGN_PARENT_START);
            //leftParams.setMarginStart(-35);   for rendering the part outside of clip to match Capcut
            leftHandle.setLayoutParams(leftParams);

            rightHandle = new View(clipView.getContext());
            rightHandle.setBackgroundColor(Color.WHITE);
            RelativeLayout.LayoutParams rightParams = new RelativeLayout.LayoutParams(35, ViewGroup.LayoutParams.MATCH_PARENT);
            rightParams.addRule(RelativeLayout.ALIGN_PARENT_END);
            //rightParams.setMarginEnd(-35);   for rendering the part outside of clip to match Capcut
            rightHandle.setLayoutParams(rightParams);

            leftHandle.setOnTouchListener(
                    new View.OnTouchListener() {
                        float dX;
                        @Override
                        public boolean onTouch(View v, MotionEvent event) {
                            Clip clip = (Clip) clipView.getTag();
                            int minWidth = (int) (MIN_CLIP_DURATION * pixelsPerSecond);
                            int maxWidth = (int) (clip.originalDuration * pixelsPerSecond);
                            switch (event.getAction()) {
                                case MotionEvent.ACTION_DOWN:
                                    dX = event.getRawX();
                                    break;
                                case MotionEvent.ACTION_MOVE:
                                    timelineScroll.requestDisallowInterceptTouchEvent(true);

                                    float deltaX = event.getRawX() - dX;
                                    dX = event.getRawX();


                                    // Clamping only for video and audio as these type has limited duration
                                    if (type == ClipType.VIDEO || type == ClipType.AUDIO)
                                    {
                                        deltaX = (Math.min(-deltaX, clip.startClipTrim * pixelsPerSecond));
                                        deltaX = -deltaX;


                                        int newWidth = clipView.getWidth() - (int) deltaX;
                                        float newStartTime = (clipView.getX() + deltaX - centerOffset) / pixelsPerSecond;

                                        // Clamping
                                        if (newWidth < minWidth) return true;
                                        // Clamp to prevent going before 0s
                                        if (newStartTime < 0) return true;


                                        newWidth = Math.max(minWidth, Math.min(newWidth, maxWidth));

                                        clipView.getLayoutParams().width = newWidth;
                                        clipView.setX(clipView.getX() + deltaX);
                                        clipView.requestLayout();

                                        clip.startTime = (clipView.getX() - centerOffset) / pixelsPerSecond;
                                        clip.startClipTrim += (deltaX) / pixelsPerSecond;
                                        clip.duration = clip.originalDuration - clip.endClipTrim - clip.startClipTrim;//Math.max(MIN_CLIP_DURATION, newWidth / (float) pixelsPerSecond);
                                    }
                                    else {
                                        deltaX = -deltaX;

                                        int newWidth = clipView.getWidth() - (int) deltaX;

                                        clipView.getLayoutParams().width = newWidth;
                                        clipView.setX(clipView.getX() + deltaX);
                                        clipView.requestLayout();

                                        clip.startTime = (clipView.getX() - centerOffset) / pixelsPerSecond;
                                        clip.startClipTrim += (deltaX) / pixelsPerSecond;
                                        clip.duration = clip.originalDuration - clip.endClipTrim - clip.startClipTrim;
                                    }
                                    break;

                                case MotionEvent.ACTION_UP:
                                case MotionEvent.ACTION_CANCEL:
                                    timelineScroll.requestDisallowInterceptTouchEvent(false);

                                    activity.updateCurrentClipEnd();
                                    break;

                            }
                            return true;
                        }
                    }
            );

            rightHandle.setOnTouchListener(
                    new View.OnTouchListener() {
                        float dX;
                        @Override
                        public boolean onTouch(View v, MotionEvent event) {
                            Clip clip = (Clip) clipView.getTag();
                            int minWidth = (int) (MIN_CLIP_DURATION * pixelsPerSecond);
                            int maxWidth = (int) (clip.originalDuration * pixelsPerSecond);
                            switch (event.getAction())
                            {
                                case MotionEvent.ACTION_DOWN:
                                    dX = event.getRawX();
                                    break;
                                case MotionEvent.ACTION_MOVE:
                                    timelineScroll.requestDisallowInterceptTouchEvent(true);

                                    float deltaX = event.getRawX() - dX;
                                    dX = event.getRawX();

                                    // Clamping only for video and audio as these type has limited duration
                                    if(type == ClipType.VIDEO || type == ClipType.AUDIO)
                                    {
                                        deltaX = Math.min(deltaX, clip.endClipTrim * pixelsPerSecond);

                                        int newWidth = clipView.getWidth() + (int) deltaX;

                                        // Clamping
                                        if (newWidth < minWidth) return true;

                                        newWidth = Math.max(minWidth, Math.min(newWidth, maxWidth));

                                        clipView.getLayoutParams().width = newWidth;
                                        clipView.requestLayout();

                                        clip.endClipTrim -= (deltaX) / pixelsPerSecond;
                                        clip.duration = clip.originalDuration - clip.endClipTrim - clip.startClipTrim;//Math.max(MIN_CLIP_DURATION, newWidth / (float) pixelsPerSecond);
                                    }
                                    else {
                                        int newWidth = clipView.getWidth() + (int) deltaX;

                                        clipView.getLayoutParams().width = newWidth;
                                        clipView.requestLayout();

                                        clip.endClipTrim -= (deltaX) / pixelsPerSecond;
                                        clip.duration = clip.originalDuration - clip.endClipTrim - clip.startClipTrim;//Math.max(MIN_CLIP_DURATION, newWidth / (float) pixelsPerSecond);
                                    }


                                    break;

                                case MotionEvent.ACTION_UP:
                                case MotionEvent.ACTION_CANCEL:
                                    timelineScroll.requestDisallowInterceptTouchEvent(false);

                                    activity.updateCurrentClipEnd();
                                    break;

                            }

                            return true;
                        }
                    }
            );

            clipView.addView(leftHandle);
            clipView.addView(rightHandle);

            deselect();
        }

        public void select() {
            viewRef.getFilledImageView().setColorFilter(0x77AAAAAA);

            leftHandle.setVisibility(View.VISIBLE);
            rightHandle.setVisibility(View.VISIBLE);
        }
        public void deselect() {
            viewRef.getFilledImageView().setColorFilter(0x00000000);

            leftHandle.setVisibility(View.GONE);
            rightHandle.setVisibility(View.GONE);
        }



        public void deleteClip(Timeline timeline)
        {
            Track currentTrack = timeline.tracks.get(trackIndex);

            currentTrack.removeClip(this);
            currentTrack.viewRef.removeView(viewRef);
        }

        public void splitClip(EditingActivity activity, Timeline timeline, float currentGlobalTime)
        {
            Track currentTrack = timeline.tracks.get(trackIndex);

            float translatedLocalCurrentTime = currentGlobalTime - startTime;

            Clip secondaryClip = new Clip(clipName, currentGlobalTime, originalDuration, trackIndex, type);

            float oldEndClipTrim = endClipTrim;
            float oldStartClipTrim = startClipTrim;

            // Primary clip
            endClipTrim = originalDuration - (translatedLocalCurrentTime + oldStartClipTrim);
            duration = originalDuration - endClipTrim - startClipTrim;

            // Secondary clip
            secondaryClip.startClipTrim = translatedLocalCurrentTime + oldStartClipTrim;
            secondaryClip.endClipTrim = oldEndClipTrim;
            secondaryClip.duration = secondaryClip.originalDuration - secondaryClip.endClipTrim - secondaryClip.startClipTrim;

            activity.addClipToTrack(currentTrack, secondaryClip);
            activity.revalidationClipView(this);
        }

        public boolean hasAnimatedProperties() {
            return posXKeyFrames.keyframes.size() != 0 ||
                    posYKeyFrames.keyframes.size() != 0 ||
                    scaleKeyFrames.keyframes.size() != 0 ||
                    rotationKeyFrames.keyframes.size() != 0;
        }

        public String getRenderedName() {
            return preRenderedName;
        }

        public void setRenderedName(String s) {
            preRenderedName = s;
        }
        public String getAbsolutePath(MainActivity.ProjectData properties) {
            return getAbsolutePath(properties.getProjectPath());
        }
        public String getAbsolutePath(String projectPath) {
            return IOHelper.CombinePath(projectPath, Constants.DEFAULT_CLIP_DIRECTORY, clipName);
        }
        public String getAbsoluteRenderPath(MainActivity.ProjectData properties) {
            return getAbsoluteRenderPath(properties.getProjectPath());
        }
        public String getAbsoluteRenderPath(String projectPath) {
            return IOHelper.CombinePath(projectPath, Constants.DEFAULT_CLIP_DIRECTORY, preRenderedName);
        }
    }




    public enum ClipType {
        VIDEO,
        AUDIO,
        IMAGE,
        TEXT,
        TRANSITION,
        EFFECT
    }
    public static class EffectTemplate implements Serializable {
        @Expose
        public String type; // "transition", "overlay", etc
        @Expose
        public String style; // "fade", "zoom", "glitch"
        @Expose
        public double duration;
        @Expose
        public double offset;
        @Expose
        public Map<String, Object> params;

        public EffectTemplate(String style, double duration, double offset)
        {
            this.style = style;
            this.duration = duration;
            this.offset = offset;
        }

    }


    static class DragContext {
        View ghost;
        Track currentTrack;
        Clip clip;
    }

    public static class TransitionClip implements Serializable {
        @Expose
        public int trackIndex;
        @Expose
        public float startTime;
        @Expose
        public float duration;

        @Expose
        public Clip fromClip;
        @Expose
        public Clip toClip;

        @Expose
        public EffectTemplate effect; // e.g. xfade, zoom, etc.

        @Expose
        public TransitionMode mode;


        public enum TransitionMode {
            END_FIRST,
            OVERLAP,
            BEGIN_SECOND
        }
    }



    public static class VideoSettings implements Serializable {
        int videoWidth;
        int videoHeight;
        int frameRate;
        int crf;
        String preset;
        String tune;
        public VideoSettings(int videoWidth, int videoHeight, int frameRate, int crf, String preset, String tune)
        {
            this.videoWidth = videoWidth;
            this.videoHeight = videoHeight;
            this.frameRate = frameRate;
            this.crf = crf;
            this.preset = preset;
            this.tune = tune;
        }

        public int getVideoWidth() {
            return videoWidth;
        }
        public int getVideoHeight() {
            return videoHeight;
        }
        public int getFrameRate() {
            return frameRate;
        }
        public int getCRF() {
            return crf;
        }
        public String getPreset() {
            return preset;
        }
        public String getTune() {
            return tune;
        }

        /*
        // Low
videoWidth = 640;
videoHeight = 360;
frameRate = 24;

// Medium
videoWidth = 1280;
videoHeight = 720;
frameRate = 30;

// High
videoWidth = 1920;
videoHeight = 1080;
frameRate = 60;

         */


        public class FfmpegPreset
        {
            public static final String PLACEBO = "placebo";
            public static final String VERYSLOW = "veryslow";
            public static final String SLOWER = "slower";
            public static final String SLOW = "slow";
            public static final String MEDIUM = "medium";
            public static final String FAST = "fast";
            public static final String FASTER = "faster";
            public static final String VERYFAST = "veryfast";
            public static final String SUPERFAST = "superfast";
            public static final String ULTRAFAST = "ultrafast";
        }

        public class FfmpegTune
        {
            public static final String FILM = "film";
            public static final String ANIMATION = "animation";
            public static final String GRAIN = "grain";
            public static final String STILLIMAGE = "stillimage";
            public static final String FASTDECODE = "fastdecode";
            public static final String ZEROLATENCY = "zerolatency";
        }

    }
    public static class Keyframe implements Serializable {
        public float time; // seconds
        public float value; // could be position, scale, rotation, etc.
        public EasingType easing = EasingType.LINEAR;


        public Keyframe(float time, float value, EasingType easing)
        {
            this.time = time;
            this.value = value;
            this.easing = easing;
        }
    }
    public static class AnimatedProperty implements Serializable {
        public List<Keyframe> keyframes = new ArrayList<>();

        public float getValueAt(float playheadTime) {
            if (keyframes.isEmpty()) return 0f;

            Keyframe prev = keyframes.get(0);
            for (Keyframe next : keyframes) {
                if (playheadTime < next.time) {
                    float t = (playheadTime - prev.time) / (next.time - prev.time);
                    t = Math.max(0f, Math.min(1f, t));
                    return lerp(prev.value, next.value, ease(t, next.easing)); // linear interpolation
                }
                prev = next;

//                if (playheadTime <= keyframes.get(0).time) return keyframes.get(0).value;
//                if (playheadTime >= keyframes.get(keyframes.size() - 1).time) return keyframes.get(keyframes.size() - 1).value;

            }
            return keyframes.get(keyframes.size() - 1).value;
        }

        private float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        private float ease(float t, EasingType type) {
            switch (type) {
                case LINEAR: return t;
                case EASE_IN: return t * t;
                case EASE_OUT: return 1 - (1 - t) * (1 - t);
                case EXPONENTIAL: return (float)Math.pow(2, 10 * (t - 1));
                case EASE_IN_OUT:
                    return t < 0.5f ? 2 * t * t : 1 - (float)Math.pow(-2 * t + 2, 2) / 2;
                case QUADRATIC:
                    return t * t; // same as EASE_IN, but you can customize later
                case SPRING:
                    MathHelper.spring(t, 1, 12, 0.5f);
//                    SpringProperty spring = new SpringProperty();
//                    return spring.getValueAt(t);
                // Add more...
                default: return t;
            }
        }

    }
//    public static class SpringProperty implements Serializable {
//        public float mass = 1f;
//        public float stiffness = 12f;
//        public float damping = 0.5f;
//
//        public float getValueAt(float t) {
//            // Critically damped spring motion
//            return 1 - (float)Math.exp(-damping * t) * (1 + damping * t);
//        }
//    }

    public enum EasingType {
        LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, EXPONENTIAL, SPRING, QUADRATIC
    }

    //Todo: When real-time preview is back to working, consider using this stuff
    protected void useKeyFrameInRealtimeRendering(Clip clip, float playheadTime)
    {
        float x = clip.posXKeyFrames.getValueAt(playheadTime);
        float y = clip.posYKeyFrames.getValueAt(playheadTime);
        float scale = clip.scaleKeyFrames.getValueAt(playheadTime);
        float rotation = clip.rotationKeyFrames.getValueAt(playheadTime);

        // Apply transform to canvas or OpenGL

    }



    public class ClipRenderer {
        public final Clip clip;

        private MediaExtractor extractor;
        private MediaCodec decoder;

        private MediaPlayer mediaPlayer;
        private SurfaceTexture surfaceTexture;
        private Surface surface;

        private int textureId;
        private float[] transformMatrix;

        private FloatBuffer vertexBuffer;
        private FloatBuffer texBuffer;

        private int shaderProgram;
        private int positionHandle;
        private int texCoordHandle;
        private int transformHandle;
        private int textureHandle;

        private Context context;

        public ClipRenderer(Context context, Clip clip) {
            this.context = context;
            this.clip = clip;
        }

        public void initGLResources() {
            // Texture creation
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            textureId = textures[0];

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            surfaceTexture = new SurfaceTexture(textureId);
            surface = new Surface(surfaceTexture);

            // Load shaders
            String vertexSrc = ShaderUtils.readAssetFile(context, "shaders/vertex_shader.glsl");
            String fragmentSrc = ShaderUtils.readAssetFile(context, "shaders/fragment_shader.glsl");
            shaderProgram = ShaderUtils.buildShader(context, vertexSrc, fragmentSrc);

            positionHandle = GLES20.glGetAttribLocation(shaderProgram, "a_Position");
            texCoordHandle = GLES20.glGetAttribLocation(shaderProgram, "a_TexCoord");
            transformHandle = GLES20.glGetUniformLocation(shaderProgram, "u_Transform");
            textureHandle = GLES20.glGetUniformLocation(shaderProgram, "u_Texture");

            initBuffers();
            buildTransformMatrix();
            initDecoder(); // decoder can be started now that surface is available
        }

        private void initBuffers() {
            // Buffers
            float[] vertexData = {
                    -1f,  1f, 0f,
                    -1f, -1f, 0f,
                    1f,  1f, 0f,
                    1f, -1f, 0f
            };
            float[] texCoords = {
                    0f, 0f,
                    0f, 1f,
                    1f, 0f,
                    1f, 1f
            };

            vertexBuffer = ByteBuffer.allocateDirect(vertexData.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertexBuffer.put(vertexData).position(0);

            texBuffer = ByteBuffer.allocateDirect(texCoords.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            texBuffer.put(texCoords).position(0);
        }
        private void initDecoder() {
            try {
                String mimeType = URLConnection.guessContentTypeFromName(new File(clip.getAbsolutePath(properties)).getName());


                if(mimeType.startsWith("image/")) return;
                extractor = new MediaExtractor();
                extractor.setDataSource(clip.getAbsolutePath(properties));
                int videoTrack = TimelineUtils.findVideoTrackIndex(extractor);
                extractor.selectTrack(videoTrack);
                MediaFormat format = extractor.getTrackFormat(videoTrack);

                decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
                decoder.configure(format, surface, null, 0);
                decoder.start();
            } catch (Exception e) {
                LoggingManager.LogExceptionToNoteOverlay(context, e);
            }
        }

        private void buildTransformMatrix() {
            transformMatrix = new float[16];
            Matrix.setIdentityM(transformMatrix, 0);
            Matrix.translateM(transformMatrix, 0, clip.posX, clip.posY, 0f);
            Matrix.rotateM(transformMatrix, 0, clip.rotation, 0f, 0f, 1f);
            Matrix.scaleM(transformMatrix, 0, clip.scaleX, clip.scaleY, 1f);
        }

        public boolean isVisible(float playheadTime) {
            return playheadTime >= clip.startTime &&
                    playheadTime <= clip.startTime + clip.duration;
        }

        public void renderFrame(float playheadTime) {
            if (!isVisible(playheadTime)) return;


            try {
                pumpDecoderInput(playheadTime); // Feed decoder

                // Drain decoder and render frame
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0);
                if (outputIndex >= 0) {
                    System.err.println("Decoder output at: " + bufferInfo.presentationTimeUs);
                    decoder.releaseOutputBuffer(outputIndex, true); // true = render to surface
                }
                System.err.println(outputIndex);

                if (surfaceTexture != null) {
                    surfaceTexture.updateTexImage(); // pulls frame into GPU texture
                }

                drawToCanvas(); // now finally drawing non-black texture
            } catch (Exception e) {
                LoggingManager.LogExceptionToNoteOverlay(context, e);
            }
        }

        private void drawToCanvas() {
            GLES20.glUseProgram(shaderProgram);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);

            GLES20.glUniformMatrix4fv(transformHandle, 1, false, transformMatrix, 0);
            GLES20.glUniform1i(textureHandle, 0);

            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer);

            GLES20.glEnableVertexAttribArray(texCoordHandle);
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texBuffer);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(texCoordHandle);
        }
        private void pumpDecoderInput(float playheadTime) {
            if(decoder == null) return;
            int inputIndex = decoder.dequeueInputBuffer(0);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                int sampleSize = extractor.readSampleData(inputBuffer, 0);

                if (sampleSize >= 0) {
                    float clipTime = playheadTime - clip.startTime;
                    long ptsUs = (long)(clipTime * 1_000_000); // override presentation timestamp


                    decoder.queueInputBuffer(inputIndex, 0, sampleSize, ptsUs, 0);
                    extractor.seekTo(ptsUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    extractor.advance();
                } else {
                    decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            }
        }



        public void release() {
            if (decoder != null) {
                decoder.stop();
                decoder.release();
            }
            if (extractor != null) {
                extractor.release();
            }
            if (surface != null) {
                surface.release();
            }
        }
    }


    public class TimelineRenderer implements GLSurfaceView.Renderer {
        private final Context context;
        private List<List<ClipRenderer>> trackLayers = new ArrayList<>();
        private float playheadTime = 0f;

        public TimelineRenderer(Context context) {
            this.context = context;
        }

        public void buildTimeline(Timeline timeline)
        {
            trackLayers = new ArrayList<>();

            for (Track track : timeline.tracks) {
                List<ClipRenderer> renderers = new ArrayList<>();
                for (Clip clip : track.clips) {
                    if(clip.type == ClipType.VIDEO)
                        renderers.add(new ClipRenderer(context, clip));
                }
                trackLayers.add(renderers);
            }
        }

        public void updatePlayhead(float timeSec) {
            this.playheadTime = timeSec;
        }

        public void initClips()
        {
            for (List<ClipRenderer> track : trackLayers) {
                for (ClipRenderer cr : track) {
                    cr.initGLResources(); // now that context is live
                }
            }
        }
        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            boolean renderedAny = false;

            for (List<ClipRenderer> track : trackLayers) {
                for (ClipRenderer clipRenderer : track) {
                    if (clipRenderer.isVisible(playheadTime)) {
                        clipRenderer.renderFrame(playheadTime);
                        renderedAny = true;
                    }
                }
            }

            if (!renderedAny) {
                renderSolidBlack();
            }
        }

        private void renderSolidBlack() {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }

        public void release() {
            for (List<ClipRenderer> track : trackLayers) {
                for (ClipRenderer cr : track) cr.release();
            }
        }
    }



}



