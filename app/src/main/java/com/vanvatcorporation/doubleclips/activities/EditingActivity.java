package com.vanvatcorporation.doubleclips.activities;

import static com.vanvatcorporation.doubleclips.FFmpegEdit.runAnyCommand;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import com.arthenica.ffmpegkit.Log;
import com.arthenica.ffmpegkit.Statistics;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.Expose;

import com.vanvatcorporation.doubleclips.FFmpegEdit;
import com.vanvatcorporation.doubleclips.FXCommandEmitter;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.editing.BaseEditSpecificAreaScreen;
import com.vanvatcorporation.doubleclips.activities.editing.ClipEditSpecificAreaScreen;
import com.vanvatcorporation.doubleclips.activities.editing.ClipsEditSpecificAreaScreen;
import com.vanvatcorporation.doubleclips.activities.editing.EffectEditSpecificAreaScreen;
import com.vanvatcorporation.doubleclips.activities.editing.ProjectFilesEditSpecificAreaScreen;
import com.vanvatcorporation.doubleclips.activities.editing.TextEditSpecificAreaScreen;
import com.vanvatcorporation.doubleclips.activities.editing.Scene3dEditSpecificAreaScreen;
import com.vanvatcorporation.doubleclips.activities.editing.TransitionEditSpecificAreaScreen;
import com.vanvatcorporation.doubleclips.activities.editing.VideoPropertiesEditSpecificAreaScreen;
import com.vanvatcorporation.doubleclips.activities.main.MainAreaScreen;
import com.vanvatcorporation.doubleclips.commands.CommandManager;
import com.vanvatcorporation.doubleclips.commands.AddClipCommand;
import com.vanvatcorporation.doubleclips.commands.DeleteClipCommand;
import com.vanvatcorporation.doubleclips.commands.SplitClipCommand;
import com.vanvatcorporation.doubleclips.commands.BatchCommand;
import com.vanvatcorporation.doubleclips.commands.base.CommandUtils;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.helper.DateHelper;
import com.vanvatcorporation.doubleclips.helper.EdgeScrollHelper;
import com.vanvatcorporation.doubleclips.helper.GsonHelper;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.helper.IOImageHelper;
import com.vanvatcorporation.doubleclips.helper.ImageHelper;
import com.vanvatcorporation.doubleclips.helper.MimeHelper;
import com.vanvatcorporation.doubleclips.helper.ParserHelper;
import com.vanvatcorporation.doubleclips.helper.StringFormatHelper;
import com.vanvatcorporation.doubleclips.impl.AppCompatActivityImpl;
import com.vanvatcorporation.doubleclips.impl.ImageGroupView;
import com.vanvatcorporation.doubleclips.impl.TrackFrameLayout;
import com.vanvatcorporation.doubleclips.impl.java.RunnableImpl;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;
import com.vanvatcorporation.doubleclips.utils.AudioUtils;
import com.vanvatcorporation.doubleclips.utils.BackgroundRemover;
import com.vanvatcorporation.doubleclips.utils.VideoMaskGenerator;
import com.vanvatcorporation.doubleclips.utils.TimelineUtils;

import org.rajawali3d.view.SurfaceView;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class EditingActivity extends AppCompatActivityImpl {


    //List<Track> trackList = new ArrayList<>();
    public Timeline timeline = new Timeline();
    MainAreaScreen.ProjectData properties;
    VideoSettings settings;

    private LinearLayout timelineTracksContainer, trackInfoLayout;
    private TimelineRulerView rulerContainer;
    private RelativeLayout timelineWrapper, editingZone, previewZone, editingToolsZone, outerPreviewViewGroup, pausedCanvasAlertPanel;
    private HorizontalScrollView timelineScroll, rulerScroll;
    private ScrollView timelineVerticalScroll, trackInfoVerticalScroll;
    private TextView currentTimePosText, durationTimePosText, textCanvasControllerInfo;
    private ImageButton addNewTrackButton;
    private FrameLayout previewViewGroup;
    private ImageButton playPauseButton, snapshotButton, undoButton, redoButton, backButton, settingsButton;
    private Button exportButton;
    private TimelineRenderer timelineRenderer;

    private TrackFrameLayout addNewTrackBlankTrackSpacer;

    private TextEditSpecificAreaScreen textEditSpecificAreaScreen;
    private Scene3dEditSpecificAreaScreen scene3dEditSpecificAreaScreen;
    private EffectEditSpecificAreaScreen effectEditSpecificAreaScreen;
    private TransitionEditSpecificAreaScreen transitionEditSpecificAreaScreen;
    private ClipsEditSpecificAreaScreen clipsEditSpecificAreaScreen;
    private ClipEditSpecificAreaScreen clipEditSpecificAreaScreen;
    private VideoPropertiesEditSpecificAreaScreen videoPropertiesEditSpecificAreaScreen;
    private ProjectFilesEditSpecificAreaScreen projectFilesEditSpecificAreaScreen;



    private HorizontalScrollView toolbarDefault, toolbarClip, toolbarTrack, toolbarClips;



    private int trackCount = 0;
    private static final int TRACK_HEIGHT = 100;
    private static final float MIN_CLIP_DURATION = 0.1f; // in seconds
    public static int pixelsPerSecond = 100;

    public static int previewAvailableWidth, previewAvailableHeight;

    public static int centerOffset;
    int currentTimelineEnd = 0; // Keep a variable that tracks the furthest X position of any clip


    float currentTime = 0f; // seconds


    static TransitionClip selectedKnot = null;
    public static Clip selectedClip = null;
    public static Track selectedTrack = null;

    static ArrayList<Clip> selectedClips = new ArrayList<>();
    boolean isClipSelectMultiple;
    boolean getClipSelectMultiple() { return isClipSelectMultiple;}
    void setClipSelectMultiple(boolean value) {
        isClipSelectMultiple = value;

        // Convert from selectedClip to selectedClips
        if(value && (selectedClip != null))
            selectingClip(selectedClip);

        // Convert from selectedClips to selectedClip
        if(!value && (selectedClips != null && !selectedClips.isEmpty() && selectedClips.get(0) != null))
            selectingClip(selectedClips.get(0));
    }

    static CommandManager actionManager = new CommandManager();



    private ScaleGestureDetector scaleDetector;
    private float scaleFactor = 1f;
    private float MIN_SCALE_FACTOR = 0.01f; // 0.05 before
    private float MAX_SCALE_FACTOR = 32f; // 8 before
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
    private boolean isPlayingInReverse = false;
    private boolean keepPlayingWhenClipSelected = false;
    private float previewFpsRuntime = -1f;
    private static int thumbnailAudioBarWidth = 1, thumbnailAudioBarGap = 0;
    private Handler playbackHandler = new Handler(Looper.getMainLooper());
    private Runnable playbackLoop;



    FFmpegEdit.FfmpegRenderQueue previewRenderQueue = new FFmpegEdit.FfmpegRenderQueue();


    @SuppressLint("Range")
    public static String getFileName(ContentResolver contentResolver, Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            Cursor cursor = contentResolver.query(uri, null, null, null, null);
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
    public String getFileName(Uri uri) {
        return getFileName(getContentResolver(), uri);
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
                        Uri[] uris = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            uris[i] = data.getClipData().getItemAt(i).getUri();
                        }
                        processingMultiPreview(uris);
                    } else if (data.getData() != null) {
                        // Single file selected
                        Uri fileUri = data.getData();
                        // Process the file URI
                        parseFileIntoWorkPathAndAddToTrack(fileUri, 0);
                    }
                }
            }
    );

    float parseFileIntoWorkPathAndAddToTrack(Uri uri, float offsetTime)
    {

        if(uri == null) return offsetTime;
        if(selectedTrack == null) return offsetTime;
        String filename = getFileName(uri);
        String clipPath = IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_CLIP_DIRECTORY, filename);
        String previewClipPath = IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_PREVIEW_CLIP_DIRECTORY, filename);
        IOHelper.writeToFileAsRaw(this, clipPath, IOHelper.readFromFileAsRaw(this, getContentResolver(), uri));

        float duration = 3f; // fallback default if needed
        MimeHelper.MimeType mimeType = MimeHelper.getMimeTypeFromUri(this, uri);


        ClipType type;

        if (mimeType.isAudio()) type = ClipType.AUDIO;
        else if (mimeType.isImage()) type = ClipType.IMAGE;
        else if (mimeType.isVideo()) type = ClipType.VIDEO;
        else type = ClipType.EFFECT; // if effect or unknown



        if(type == ClipType.VIDEO || type == ClipType.AUDIO)
            try {
                MediaExtractor extractor = new MediaExtractor();
                extractor.setDataSource(clipPath);



                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String trackMime = format.getString(MediaFormat.KEY_MIME);
                    if (trackMime != null) {
                        // For MOV type, it has 2 track, so before this version, this applied to the below ClipType, which turn MOV to audio type
                        //mimeType = trackMime;

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


        boolean isClipHasAudio = false;
        int width = 0, height = 0;
        // Video check
        if(type == ClipType.VIDEO)
        {
            try {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(clipPath);

                width = Integer.parseInt(Objects.requireNonNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)));
                height = Integer.parseInt(Objects.requireNonNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)));

                isClipHasAudio = "yes".equals(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
                retriever.close();
            } catch (IOException e) {
                LoggingManager.LogExceptionToNoteOverlay(this, e);
            }
        }
        if(type == ClipType.AUDIO)
        {
            // Audio always has audio, of course
            // Add this to fix the noSound icon appear at Audio Clip, which make no sense
            isClipHasAudio = true;
        }
        if(type == ClipType.IMAGE)
        {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true; // Don't load the full bitmap
            BitmapFactory.decodeFile(clipPath, options);
            width = options.outWidth;
            height = options.outHeight;
        }


        Clip newClip = new Clip(filename, currentTime + offsetTime, duration, selectedTrack.timelineIndex, type, isClipHasAudio, width, height);


        addClipToTrack(selectedTrack, newClip);

        offsetTime += duration;


        previewRenderQueue.enqueue(new FFmpegEdit.FfmpegRenderQueue.FfmpegRenderQueueInfo("Preview Generation",
                () -> {
                    processingPreview(newClip, clipPath, previewClipPath);
                }));

        if(type == ClipType.VIDEO && isClipHasAudio)
        {
            previewRenderQueue.enqueue(new FFmpegEdit.FfmpegRenderQueue.FfmpegRenderQueueInfo("Preview Generation",
                    () -> {
                        // Extract audio from Video if it has audio
                        Clip audioClip = new Clip(newClip);
                        audioClip.type = ClipType.AUDIO;
                        processingPreview(audioClip, clipPath, previewClipPath);
                    }));
        }



        return offsetTime;
    }

    public void addProjectFileMediaToTrack(String filename, String filepath)
    {
        if(filepath == null) return;
        if(selectedTrack == null) return;
        float duration = 3f; // fallback default if needed

        MimeHelper.MimeType mimeType = MimeHelper.getMimeTypeFromPath(filepath);

        if(mimeType == null) return;


        ClipType type;

        if (mimeType.isAudio()) type = ClipType.AUDIO;
        else if (mimeType.isImage()) type = ClipType.IMAGE;
        else if (mimeType.isVideo()) type = ClipType.VIDEO;
        else type = ClipType.EFFECT; // if effect or unknown



        if(type == ClipType.VIDEO || type == ClipType.AUDIO)
            try {
                MediaExtractor extractor = new MediaExtractor();
                extractor.setDataSource(filepath);



                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String trackMime = format.getString(MediaFormat.KEY_MIME);
                    if (trackMime != null) {
                        // For MOV type, it has 2 track, so before this version, this applied to the below ClipType, which turn MOV to audio type
                        //mimeType = trackMime;

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


        boolean isClipHasAudio = false;
        int width = 0, height = 0;
        // Video check
        if(type == ClipType.VIDEO)
        {
            try {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(filepath);

                width = Integer.parseInt(Objects.requireNonNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)));
                height = Integer.parseInt(Objects.requireNonNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)));

                isClipHasAudio = "yes".equals(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
                retriever.close();
            } catch (IOException e) {
                LoggingManager.LogExceptionToNoteOverlay(this, e);
            }
        }
        if(type == ClipType.AUDIO)
        {
            // Audio always has audio, of course
            // Add this to fix the noSound icon appear at Audio Clip, which make no sense
            isClipHasAudio = true;
        }
        if(type == ClipType.IMAGE)
        {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true; // Don't load the full bitmap
            BitmapFactory.decodeFile(filepath, options);
            width = options.outWidth;
            height = options.outHeight;
        }


        Clip newClip = new Clip(filename, currentTime, duration, selectedTrack.timelineIndex, type, isClipHasAudio, width, height);


        addClipToTrack(selectedTrack, newClip);
    }

    // ==============       Unsorted feature          =============

    AnimatedProperty currentExportingKeyframes;
    Clip currentExportingClip;
    Track currentExportingTrack;


    private ActivityResultLauncher<Intent> keyframesImportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    // Check if multiple files are selected

                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        Uri[] uris = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            uris[i] = data.getClipData().getItemAt(i).getUri();
                        }

                        for (int i = 0; i < uris.length; i++) {
                            try {
                                AnimatedProperty keyframes = GsonHelper.createStrictGson(new String[]{"keyframes"}, AnimatedProperty.class).fromJson(IOHelper.readFromFile(this, getContentResolver(), uris[i]), AnimatedProperty.class);
                                if(selectedClip != null)
                                    selectedClip.importKeyframes(this, keyframes);
                                else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a clip first!").show();

                            } catch (JsonParseException e) {
                                new AlertDialog.Builder(this).setTitle("Error").setMessage(e.getMessage()).show();
                            }


                        }
                    } else if (data.getData() != null) {
                        // Single file selected
                        Uri fileUri = data.getData();
                        // Process the file URI
                        try {
                            AnimatedProperty keyframes = GsonHelper.createStrictGson(new String[]{"keyframes"}, AnimatedProperty.class).fromJson(IOHelper.readFromFile(this, getContentResolver(), fileUri), AnimatedProperty.class);
                            if(selectedClip != null)
                                selectedClip.importKeyframes(this, keyframes);
                            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a clip first!").show();

                        } catch (JsonParseException e) {
                            new AlertDialog.Builder(this).setTitle("Error").setMessage(e.getMessage()).show();
                        }

                    }

                }
            }
    );
    private ActivityResultLauncher<Intent> keyframesExportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    // Check if multiple files are selected
                    if(currentExportingKeyframes == null) return;
                    IOHelper.writeToFile(
                            this,
                            getContentResolver(),
                            data.getData(),
                            GsonHelper.createExposeOnlyGson().toJson(currentExportingKeyframes));
                    currentExportingKeyframes = null;
                }
            }
    );
    private ActivityResultLauncher<Intent> clipImportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    // Check if multiple files are selected

                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        Uri[] uris = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            uris[i] = data.getClipData().getItemAt(i).getUri();
                        }

                        for (int i = 0; i < uris.length; i++) {
                            try {
                                Clip clip = GsonHelper.createStrictGson(new String[]{"clipName"}, Clip.class).fromJson(IOHelper.readFromFile(this, getContentResolver(), uris[i]), Clip.class);
                                clip.filterNullAfterLoad();
                                if(selectedTrack != null)
                                    addClipToTrack(selectedTrack, clip);
                                else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a track first!").show();

                            } catch (JsonParseException e) {
                                new AlertDialog.Builder(this).setTitle("Error").setMessage(e.getMessage()).show();
                            }


                        }
                    } else if (data.getData() != null) {
                        // Single file selected
                        Uri fileUri = data.getData();
                        // Process the file URI
                        try {
                            Clip clip = GsonHelper.createStrictGson(new String[]{"clipName"}, Clip.class).fromJson(IOHelper.readFromFile(this, getContentResolver(), fileUri), Clip.class);
                            clip.filterNullAfterLoad();
                            if(selectedTrack != null)
                                addClipToTrack(selectedTrack, clip);
                            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a track first!").show();

                        } catch (JsonParseException e) {
                            new AlertDialog.Builder(this).setTitle("Error").setMessage(e.getMessage()).show();
                        }

                    }

                }
            }
    );
    private ActivityResultLauncher<Intent> clipExportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    // Check if multiple files are selected
                    if(currentExportingClip == null) return;
                    IOHelper.writeToFile(
                            this,
                            getContentResolver(),
                            data.getData(),
                            GsonHelper.createExposeOnlyGson().toJson(currentExportingClip));
                    currentExportingClip = null;
                }
            }
    );
    private ActivityResultLauncher<Intent> trackImportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    // Check if multiple files are selected

                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        Uri[] uris = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            uris[i] = data.getClipData().getItemAt(i).getUri();
                        }

                        for (Uri uri : uris) {
                            try {
                                Track track = GsonHelper.createStrictGson(new String[]{"timelineIndex"}, Track.class).fromJson(IOHelper.readFromFile(this, getContentResolver(), uri), Track.class);
                                loadExistingTrack(track);

                                timeline.addTrack(track);
                            } catch (JsonParseException e) {
                                new AlertDialog.Builder(this).setTitle("Error").setMessage(e.getMessage()).show();
                            }



                        }
                    } else if (data.getData() != null) {
                        // Single file selected
                        Uri fileUri = data.getData();
                        // Process the file URI
                        try {
                            Track track = GsonHelper.createStrictGson(new String[]{"timelineIndex"}, Track.class).fromJson(IOHelper.readFromFile(this, getContentResolver(), fileUri), Track.class);
                            loadExistingTrack(track);

                            timeline.addTrack(track);
                        } catch (JsonParseException e) {
                            new AlertDialog.Builder(this).setTitle("Error").setMessage(e.getMessage()).show();
                        }
                    }

                }
            }
    );
    private ActivityResultLauncher<Intent> trackExportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    // Check if multiple files are selected
                    if(currentExportingTrack == null) return;
                    IOHelper.writeToFile(
                            this,
                            getContentResolver(),
                            data.getData(),
                            GsonHelper.createExposeOnlyGson().toJson(currentExportingTrack));
                    currentExportingTrack = null;
                }
            }
    );


    public void renameProjectFile(String oldName, String newName)
    {
        boolean renamed = false;
        for (Track track : timeline.tracks) {
            for (Clip clip : track.clips) {
                if(oldName.equals(clip.clipName)) {
                    clip.setClipName(newName, properties, timeline);
                    renamed = true;
                    break;
                }
            }
            if(renamed) break;
        }

        // Fallback if no clip are available
        if(!renamed)
        {

            String clipPath = IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_CLIP_DIRECTORY, oldName);
            String previewClipPath = IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_PREVIEW_CLIP_DIRECTORY, oldName);
            String clipPathNew = IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_CLIP_DIRECTORY, newName);
            String previewClipPathNew = IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_PREVIEW_CLIP_DIRECTORY, newName);

            // Apply the filename change for both preview path and
            File file = new File(clipPath);
            File filePreview = new File(previewClipPath);
            if(file.renameTo(new File(clipPathNew)) &&
                    filePreview.renameTo(new File(previewClipPathNew))) {
                // Success
            }
            else
                ; // Operation failed
        }
    }
    public void deleteAllClipWithName(String name)
    {
        for (Track track : timeline.tracks) {
            ArrayList<Clip> deleteClip = new ArrayList<>();
            for (Clip clip : track.clips) {
                if(name.equals(clip.clipName))
                    deleteClip.add(clip);
            }
            for (Clip clip : deleteClip) {
                clip.deleteClip(timeline, this);
            }
        }
    }
    public void exportSingularKeyframesList(AnimatedProperty data)
    {
        currentExportingKeyframes = data;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, "export_keyframes_data.json");
        keyframesExportLauncher.launch(Intent.createChooser(intent, "Select Export Keyframes Json Data File"));
    }
    public void importSingularKeyframesList()
    {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        keyframesImportLauncher.launch(Intent.createChooser(intent, "Select Import Keyframes Json Data File"));
    }
    public void exportSingularClip(Clip data)
    {
        currentExportingClip = data;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, "export_clip_" + data.getClipName() + ".json");
        clipExportLauncher.launch(Intent.createChooser(intent, "Select Export Clip Json Data File"));
    }
    public void importSingularClip()
    {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        clipImportLauncher.launch(Intent.createChooser(intent, "Select Import Clip Json Data File"));
    }


    public void exportSingularTrack(Track data)
    {
        currentExportingTrack = data;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, "export_track_" + data.timelineIndex + ".json");
        trackExportLauncher.launch(Intent.createChooser(intent, "Select Export Track Json Data File"));
    }
    public void importSingularTrack()
    {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        trackImportLauncher.launch(Intent.createChooser(intent, "Select Import Track Json Data File"));
    }


    // ==============       Unsorted feature          =============







    void processingPreview(Clip clip, String originalClipPath, String previewClipPath)
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        // Inflate your custom layout
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.popup_processing_preview, null);
        builder.setView(dialogView);
        builder.setCancelable(false);
        builder.setNegativeButton(getText(R.string.alert_processing_without_preview_confirmation),
                (dialog, which) -> {
            // This initial listener might be simple or a placeholder
                    dialog.dismiss();
        });

        // Get references to the EditText and Buttons in your custom layout
        TextView processingText = dialogView.findViewById(R.id.adsDescriptionText);
        TextView processingDescription = dialogView.findViewById(R.id.processingDescription);
        ProgressBar previewProgressBar = dialogView.findViewById(R.id.previewProgressBar);
        TextView processingPercent = dialogView.findViewById(R.id.processingPercent);

        // Create the AlertDialog
        AlertDialog dialog = builder.create();

        // Already prevent using the setCancelable above
        //dialog.setCanceledOnTouchOutside(false);
        // Show the dialog
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setVisibility(View.GONE);


        processingText.setText(getString(R.string.processing) + " " + "\"" + clip.getClipName() + "\"");


        int videoWidth = 2;
        int videoHeight = 1;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            // SAFEGUARD 1: Attempts to initialize data source.
            // If the file is completely invalid (e.g., a PDF or text file), this will throw an exception.
            retriever.setDataSource(originalClipPath);

            // Extract width and height metadata strings
            String widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);

            // SAFEGUARD 2: Check if dimensions actually exist.
            // If it's a valid media file but an AUDIO file (like an MP3), these keys will return null.
            if (widthStr != null && heightStr != null) {
                videoWidth = Integer.parseInt(widthStr);
                videoHeight = Integer.parseInt(heightStr);
            } else {
                LoggingManager.LogToNoteOverlay(this, "File is media, but contains no video tracks (likely an audio file).");
            }

        } catch (Exception e) {
            LoggingManager.LogExceptionToNoteOverlay(this, e);
        } finally {
            // Always clean up resources
            try {
                retriever.release();
            } catch (Exception e) {
                LoggingManager.LogExceptionToNoteOverlay(this, e);
            }
        }


        String scaleStr;
        if(videoWidth < videoHeight) scaleStr = "-2:720";
        else scaleStr = "1280:-2";

        String previewGeneratedVideoCmd = "-i \"" + originalClipPath +
                "\" -vf \"scale=" + scaleStr + "\" -c:v libopenh264 -preset ultrafast -crf 32 -g 1 -an -y \"" + previewClipPath.substring(0, previewClipPath.lastIndexOf('.')) + Constants.DEFAULT_PREVIEW_CLIP_VIDEO_EXTENSION + "\"";
//        String previewGeneratedVideoCmd = "-i \"" + originalClipPath +
//                "\" -vf \"scale=" + scaleStr + "\" -c:v h264_mediacodec -b:v 512k -g 1 -an -y \"" + previewClipPath + "\"";
        String previewGeneratedAudioCmd = "-i \"" + originalClipPath +
                "\" -vn -ac 1 -ar 22050 -c:a pcm_s16le -y \"" + previewClipPath.substring(0, previewClipPath.lastIndexOf('.')) + Constants.DEFAULT_PREVIEW_CLIP_AUDIO_EXTENSION + "\"";


        processingDescription.setTextColor(0xFF00AA00);
        if(clip.type == ClipType.AUDIO)
        {
            runAnyCommand(this, previewGeneratedAudioCmd, "Exporting Preview Audio",
                    () -> EditingActivity.this.runOnUiThread(() -> {

                        dialog.dismiss();
                        previewRenderQueue.taskCompleted();
                    }), () -> {
                        processingDescription.post(() -> {
                            processingDescription.setTextColor(0xFFFF0000);

                            dialog.setCancelable(true);
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setVisibility(View.VISIBLE);
                        });
                    }
                    , new RunnableImpl() {
                        @Override
                        public <T> void runWithParam(T param) {
                            Log log = (Log) param;
                            processingDescription.post(() -> {
                                processingDescription.setText(log.getMessage());
                            });
                        }
                    }, new RunnableImpl() {
                        @Override
                        public <T> void runWithParam(T param) {
                            double duration = clip.duration * 1000;

                            Statistics statistics = (Statistics) param;
                            {
                                if (statistics.getTime() > 0) {
                                    int progress = (int) ((statistics.getTime() * 100) / (int) duration);
                                    previewProgressBar.setMax(100);
                                    previewProgressBar.setProgress(progress);
                                    processingPercent.setText(progress + "%");
                                }
                            }
                        }
                    });
        }
        else if(clip.type == ClipType.VIDEO)
        {
            runAnyCommand(this, previewGeneratedVideoCmd, "Exporting Preview Video",
                    () -> EditingActivity.this.runOnUiThread(() -> {
                        dialog.dismiss();
                        previewRenderQueue.taskCompleted();
                        if(clip.viewRef != null)
                        {
                            // After preview process, render the thumbnail
                            Executors.newSingleThreadExecutor().execute(() -> {
                                Bitmap retrieveBitmap = combineThumbnails(extractThumbnail(this, clip.getAbsolutePreviewPath(properties, Constants.DEFAULT_PREVIEW_CLIP_VIDEO_EXTENSION), clip));
                                clip.viewRef.post(() -> {
                                    clip.viewRef.setFilledImageBitmap(retrieveBitmap);
                                });
                            });
                        }
                    }), () -> {
                        processingDescription.post(() -> {
                            processingDescription.setTextColor(0xFFFF0000);

                            dialog.setCancelable(true);
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setVisibility(View.VISIBLE);
                        });
                }
                    , new RunnableImpl() {
                        @Override
                        public <T> void runWithParam(T param) {
                            Log log = (Log) param;
                            processingDescription.post(() -> {
                                processingDescription.setText(log.getMessage());
                            });
                        }
                    }, new RunnableImpl() {
                        @Override
                        public <T> void runWithParam(T param) {
                            double duration = clip.duration * 1000;

                            Statistics statistics = (Statistics) param;
                            {
                                if (statistics.getTime() > 0) {
                                    int progress = (int) ((statistics.getTime() * 100) / (int) duration);
                                    previewProgressBar.setMax(100);
                                    previewProgressBar.setProgress(progress);
                                    processingPercent.setText(progress + "%");
                                }
                            }
                        }
                    });







        }
        else {
            // Any other type should be drop
            dialog.dismiss();
        }
    }

    void processingMultiPreview(Uri[] uris) {
//        AlertDialog.Builder builder = new AlertDialog.Builder(this);
//
//        // Inflate your custom layout
//        LayoutInflater inflater = LayoutInflater.from(this);
//        View dialogView = inflater.inflate(R.layout.popup_processing_preview, null);
//        builder.setCancelable(false);
//        builder.setView(dialogView);
//        builder.setNegativeButton(getText(R.string.alert_processing_without_preview_confirmation),
//                (dialog, which) -> {
//                    // This initial listener might be simple or a placeholder
//                    dialog.dismiss();
//                });
//
//        // Get references to the EditText and Buttons in your custom layout
//        TextView processingText = dialogView.findViewById(R.id.adsDescriptionText);
//        TextView processingDescription = dialogView.findViewById(R.id.processingDescription);
//        ProgressBar previewProgressBar = dialogView.findViewById(R.id.previewProgressBar);
//        TextView processingPercent = dialogView.findViewById(R.id.processingPercent);
//
//        // Create the AlertDialog
//        AlertDialog dialog = builder.create();
//
//        // Already prevent using the setCancelable above
//        //dialog.setCanceledOnTouchOutside(false);
//        // Show the dialog
//        dialog.show();

        // TODO: Find a way to render 2 uncancelable dialog for these commented line of code to work

        ExecutorService service = Executors.newSingleThreadExecutor();
        service.execute(() -> {
            int index = 0;
            AtomicReference<Float> offsetTime = new AtomicReference<>((float) 0);
//            previewProgressBar.setMax(100);
            for (Uri uri : uris) {
                index++;
                int percent = ((index * 100) / uris.length);

                runOnUiThread(() -> {
                    offsetTime.set(parseFileIntoWorkPathAndAddToTrack(uri, offsetTime.get()));
//                    processingText.setText(getString(R.string.processing));
//                    previewProgressBar.setProgress(percent);
//                    processingPercent.setText(percent + "%");
                });
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {

                }
//                if(index == uris.length) dialog.dismiss();
            }
        });

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


        properties = (MainAreaScreen.ProjectData) createrBundle.getSerializable("ProjectProperties");

        //settings = new VideoSettings(1280, 720, 30, 30, VideoSettings.FfmpegPreset.MEDIUM, VideoSettings.FfmpegTune.ZEROLATENCY);
        assert properties != null;
        settings = VideoSettings.loadSettings(this, properties);

        if(settings == null)
        {
            settings = new VideoSettings(1366, 768, 30, 30, 30, VideoSettings.FfmpegPreset.MEDIUM, VideoSettings.FfmpegTune.ZEROLATENCY);
        }

        pixelsPerSecond = basePixelsPerSecond;
        previewFpsRuntime = settings.frameRate;


        setContentView(R.layout.layout_editing);
        timelineTracksContainer = findViewById(R.id.timeline_tracks_container);
        trackInfoLayout = findViewById(R.id.trackInfoLayout);

        timelineWrapper = findViewById(R.id.timeline_wrapper);


        editingZone = findViewById(R.id.editingZone);
        previewZone = findViewById(R.id.previewZone);
        editingToolsZone = findViewById(R.id.editingToolsZone);

        currentTimePosText = findViewById(R.id.currentTimePosText);
        durationTimePosText = findViewById(R.id.durationTimePosText);
        textCanvasControllerInfo = findViewById(R.id.textCanvasControllerInfo);

        // Example: Add button to add more tracks
        addNewTrackButton = findViewById(R.id.addTrackButton);
        addNewTrackButton.setOnClickListener(v -> {
            Track track = addNewTrack();
            track.viewRef.trackInfo = track;
        });
        //timelineTracksContainer.addView(addTrackButton);

        previewViewGroup = findViewById(R.id.previewViewGroup);

        ViewGroup.LayoutParams previewViewGroupParams = previewViewGroup.getLayoutParams();
        previewViewGroupParams.width = settings.videoWidth;
        previewViewGroupParams.height = settings.videoHeight;
        previewViewGroup.setLayoutParams(previewViewGroupParams);


        outerPreviewViewGroup = findViewById(R.id.outerPreviewViewGroup);

        outerPreviewViewGroup.post(() -> {
            previewAvailableWidth = outerPreviewViewGroup.getWidth();
            previewAvailableHeight = outerPreviewViewGroup.getHeight();
            
            float ratioX = (float) previewAvailableWidth / settings.videoWidth;
            float ratioY = (float) previewAvailableHeight / settings.videoHeight;
            float rScale = Math.min(ratioX, ratioY);
            previewViewGroup.setScaleX(rScale);
            previewViewGroup.setScaleY(rScale);
        });

        pausedCanvasAlertPanel = findViewById(R.id.pausedCanvasAlertPanel);
        findViewById(R.id.pausedCanvasAlertResumeButton).setOnClickListener(v -> {
            regeneratingTimelineRenderer();
        });

        playPauseButton = findViewById(R.id.playPauseButton);
        playPauseButton.setOnClickListener(v -> {

            isPlaying = !isPlaying;

            if (isPlaying) {
                startPlayback();
                playPauseButton.setImageResource(R.drawable.baseline_pause_circle_24);
            } else {
                stopPlayback(true);
            }
        });
        playPauseButton.setOnLongClickListener(v -> {
            regeneratingPreviewThumbnail();
            return true;
        });

        snapshotButton = findViewById(R.id.snapshotButton);
        snapshotButton.setOnClickListener(v -> {
            captureSnapshot();
        });


        undoButton = findViewById(R.id.undoButton);
        undoButton.setEnabled(actionManager.isUndoAvailable());
        undoButton.setOnClickListener(v -> {
            if (actionManager.isUndoAvailable()) {
                actionManager.undo();
            }
        });

        redoButton = findViewById(R.id.redoButton);
        redoButton.setEnabled(actionManager.isRedoAvailable());
        redoButton.setOnClickListener(v -> {
            if (actionManager.isRedoAvailable()) {
                actionManager.redo();
            }
        });
        
        // Set up state change listener to update button states automatically
        actionManager.setStateChangeListener((canUndo, canRedo) -> {
            undoButton.setEnabled(canUndo);
            redoButton.setEnabled(canRedo);
        });
        
        // Initialize button states
        updateUndoRedoButtons();
        
        // Set up command action listener for toast notifications
        actionManager.setCommandActionListener(new CommandManager.CommandActionListener() {
            @Override
            public void onCommandExecuted(CommandUtils.Command command) {
                // Don't show toast for regular executions, only for undo/redo
            }

            @Override
            public void onCommandUndone(CommandUtils.Command command) {
                showUndoRedoToast("Undo", command.toString(), R.drawable.baseline_undo_24);
            }

            @Override
            public void onCommandRedone(CommandUtils.Command command) {
                showUndoRedoToast("Redo", command.toString(), R.drawable.baseline_redo_24);
            }
        });

        exportButton = findViewById(R.id.exportButton);
        exportButton.setOnClickListener(v -> {
            Timeline.saveTimeline(this, timeline, properties, settings);
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
        settingsButton = findViewById(R.id.settingsButton);
        settingsButton.setOnClickListener(v -> {
            videoPropertiesEditSpecificAreaScreen.open();
        });




        // Time ruler
        rulerContainer = findViewById(R.id.ruler_container);

        timelineScroll = findViewById(R.id.trackHorizontalScrollView);
        rulerScroll = findViewById(R.id.ruler_scroll);
        EdgeScrollHelper edgeScrollHelper = new EdgeScrollHelper(timelineScroll, 40, 20, 16);
        edgeScrollHelper.attach();
        timelineScroll.post(() -> {

            centerOffset = timelineScroll.getWidth() / 2;
            timelineScroll.scrollTo(centerOffset, 0);

            // Push centerOffset to ruler so 0s aligns with the centre playhead
            updateRuler(timeline.duration, currentRulerInterval);

            // Add initial track after centerOffset is taken
            //addNewTrack();



            timeline = Timeline.loadTimeline(this, this, properties);

            // Regenerate timeline renderer when timeline finishes loading.
            regeneratingTimelineRenderer();
        });
        timelineScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            rulerScroll.scrollTo(timelineScroll.getScrollX(), 0);
            if(selectedClip != null) {
                selectedClip.movePropertiesLayoutAlong(timelineScroll.getScrollX());


                if(selectedClip.startTime > currentTime || currentTime > selectedClip.startTime + selectedClip.duration)
                {
                    deselectingClip();
                    selectedClip = null;
                }
            }
            else if (selectedTrack != null)
            {
                List<Clip> listClip = selectedTrack.getClipsAtCurrentTime(currentTime);
                if(!listClip.isEmpty())
                    selectingClip(listClip.get(0));
            }

            if(!isPlaying)
                currentTime = (timelineScroll.getScrollX()) / (float) pixelsPerSecond;

            // Get time (- centerOffset mean remove the start spacer)
            //float totalSeconds = (timelineScroll.getScrollX()) / (float) pixelsPerSecond;
            currentTimePosText.post(() -> currentTimePosText.setText(DateHelper.convertTimestampToMMSSFormat((long) (currentTime * 1000L)) + String.format(".%02d", ((long)((currentTime % 1) * 100)))));

            if(isPlayingInReverse || !isPlaying || previewFpsRuntime != settings.frameRate) {
                timelineRenderer.updateTime(currentTime, true);
            }
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





        setupPreview();

        setupTimelinePinchAndZoom();

        setupSpecificEdit();

        setupToolbars();

        handleEditZoneInteraction(timelineScroll);
    }
    private void editingMultiple() {
        clipsEditSpecificAreaScreen.open();
    }
    private void editingSpecific(ClipType type) {
        switch (type)
        {
            case TEXT:
                textEditSpecificAreaScreen.open();
                break;
            case EFFECT:
                effectEditSpecificAreaScreen.open();
                break;
            case TRANSITION:
                transitionEditSpecificAreaScreen.open();
                break;
            case SCENE_3D:
                scene3dEditSpecificAreaScreen.open();
                break;
            case VIDEO:
            case IMAGE:
            case AUDIO:
                clipEditSpecificAreaScreen.open();
                break;
        }
    }

    // Bottom Navigation Bar
    private void setupToolbars()
    {
        // ===========================       CRITICAL ZONE       ====================================

        toolbarDefault = (HorizontalScrollView) LayoutInflater.from(this).inflate(R.layout.view_toolbar_default, null);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        editingToolsZone.addView(toolbarDefault, params);

        toolbarTrack = (HorizontalScrollView) LayoutInflater.from(this).inflate(R.layout.view_toolbar_track, null);
        RelativeLayout.LayoutParams paramsTrack = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        paramsTrack.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        editingToolsZone.addView(toolbarTrack, paramsTrack);
        toolbarTrack.setVisibility(View.GONE);

        toolbarClip = (HorizontalScrollView) LayoutInflater.from(this).inflate(R.layout.view_toolbar_clip, null);
        RelativeLayout.LayoutParams paramsClip = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        paramsClip.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        editingToolsZone.addView(toolbarClip, paramsClip);
        toolbarClip.setVisibility(View.GONE);

        toolbarClips = (HorizontalScrollView) LayoutInflater.from(this).inflate(R.layout.view_toolbar_clips, null);
        RelativeLayout.LayoutParams paramsClips = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        paramsClips.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        editingToolsZone.addView(toolbarClips, paramsClips);
        toolbarClips.setVisibility(View.GONE);

        // ===========================       CRITICAL ZONE       ====================================


        // ===========================       DEFAULT ZONE       ====================================


        toolbarDefault.findViewById(R.id.addTrackButton).setOnClickListener(v -> {
            Track track = addNewTrack();
            track.viewRef.trackInfo = track;
        });
        toolbarDefault.findViewById(R.id.splitMediaButton).setOnClickListener(v -> {
            List<Clip> affectedClips = timeline.getClipsAtCurrentTime(currentTime);
            if(selectedClip != null && affectedClips.contains(selectedClip)) {
                selectedClip.splitClip(this, timeline, currentTime);
            }
            else {
                for (Clip clip : affectedClips) {
                    clip.splitClip(this, timeline, currentTime);
                }

            }
        });
        toolbarDefault.findViewById(R.id.projectFilesViewerButton).setOnClickListener(v -> {
            projectFilesEditSpecificAreaScreen.open();
        });
        toolbarDefault.findViewById(R.id.importTrackButton).setOnClickListener(v -> {
            importSingularTrack();
        });


        // ===========================       DEFAULT ZONE       ====================================



        // ===========================       TRACK ZONE       ====================================


        toolbarTrack.findViewById(R.id.addMediaButton).setOnClickListener(v -> {
            if(selectedTrack != null)
                pickingContent();
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a track first!").show();
        });

        toolbarTrack.findViewById(R.id.addMediaButton).setOnLongClickListener(v -> {
            if(selectedTrack != null) {
                addClipToTrack(selectedTrack, new Clip("3DScene", currentTime, 3f, selectedTrack.timelineIndex, ClipType.SCENE_3D, false, 1366, 768));
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a track first!").show();
            return true;
        });
        toolbarTrack.findViewById(R.id.deleteTrackButton).setOnClickListener(v -> {
            if(selectedTrack != null) {
                selectedTrack.delete(timeline, timelineTracksContainer, trackInfoLayout, this);
                updateCurrentClipEnd();
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a clip first!").show();

        });
        toolbarTrack.findViewById(R.id.splitMediaButton).setOnClickListener(v -> {
            List<Clip> affectedClips = timeline.getClipsAtCurrentTime(currentTime);
            if(selectedClip != null && affectedClips.contains(selectedClip)) {
                selectedClip.splitClip(this, timeline, currentTime);
            }
            else {
                for (Clip clip : affectedClips) {
                    clip.splitClip(this, timeline, currentTime);
                }

            }
        });

        toolbarTrack.findViewById(R.id.addTextButton).setOnClickListener(v -> {

            if(selectedTrack == null) {
                new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a track first!").show();
                return;
            }

            float duration = 3f; // fallback default if needed
            ClipType type = ClipType.TEXT; // if effect or unknown



            Clip newClip = new Clip("TEXT", currentTime, duration, selectedTrack.timelineIndex, type, false, 0, 0);
            newClip.textContent = "Simple text";
            newClip.fontSize = 30;
            addClipToTrack(selectedTrack, newClip);
        });
        toolbarTrack.findViewById(R.id.addEffectButton).setOnClickListener(v -> {

            if(selectedTrack == null) {
                new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a track first!").show();
                return;
            }

            float duration = 3f; // fallback default if needed
            ClipType type = ClipType.EFFECT; // if effect or unknown

            Clip newClip = new Clip("EFFECT", currentTime, duration, selectedTrack.timelineIndex, type, false, 0, 0);
            newClip.effect = new EffectTemplate("glitch-pulse", 1.2, 4.0);

            addClipToTrack(selectedTrack, newClip);
        });
        toolbarTrack.findViewById(R.id.selectAllButton).setOnClickListener(v -> {
            // Todo: Not fully implemented yet. The idea is to remake the whole thing, get the "array" of selected clip is completed
            // now if one clip is move then the whole array move along. Also ghost will be as well


            if(selectedTrack != null) {
                setClipSelectMultiple(true);

                deselectingClip();

                for (Clip clip : selectedTrack.clips) {
                    selectingClip(clip);
                }
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a track first!").show();
        });
        toolbarTrack.findViewById(R.id.autoSnapButton).setOnClickListener(v -> {
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
        toolbarTrack.findViewById(R.id.importClipButton).setOnClickListener(v -> {
            if(selectedTrack != null) {
                importSingularClip();
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a track first!").show();
        });
        toolbarTrack.findViewById(R.id.exportTrackButton).setOnClickListener(v -> {
            if(selectedTrack != null) {
                exportSingularTrack(selectedTrack);
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a track first!").show();
        });


        // ===========================       TRACK ZONE       ====================================





        // ===========================       CLIP ZONE       ====================================


        toolbarClip.findViewById(R.id.deleteMediaButton).setOnClickListener(v -> {
            if(selectedClip != null) {
                // Use command for undo/redo support
                executeCommand(new DeleteClipCommand(this, selectedClip));
                updateCurrentClipEnd();
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a clip first!").show();

        });
        toolbarClip.findViewById(R.id.splitMediaButton).setOnClickListener(v -> {
            List<Clip> affectedClips = timeline.getClipsAtCurrentTime(currentTime);
            if(selectedClip != null && affectedClips.contains(selectedClip)) {
                // Use command for undo/redo support
                executeCommand(new SplitClipCommand(this, selectedClip, currentTime));
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a clip first!").show();
        });
        toolbarClip.findViewById(R.id.cloneMediaButton).setOnClickListener(v -> {
            if(selectedClip != null) {
                Clip cloneClip = new Clip(selectedClip);
                cloneClip.startTime = selectedClip.startTime + selectedClip.duration;
                if(selectedTrack != null) {
                    // Use command for undo/redo support
                    executeCommand(new AddClipCommand(this, selectedTrack, cloneClip));
                }
                else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a track first!").show();
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a clip first!").show();
        });
        toolbarClip.findViewById(R.id.editMediaButton).setOnClickListener(v -> {
            if(selectedClip != null) {
                editingSpecific(selectedClip.type);
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a clip first!").show();
        });
        toolbarClip.findViewById(R.id.addKeyframeButton).setOnClickListener(v -> {
            if(selectedClip != null) {
                addKeyframe(selectedClip, currentTime);
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a clip first!").show();
        });
        toolbarClip.findViewById(R.id.selectMultipleButton).setOnClickListener(v -> {
            // Todo: Not fully implemented yet. The idea is to remake the whole thing, get the "array" of selected clip is completed
            // now if one clip is move then the whole array move along. Also ghost will be as well

            setClipSelectMultiple(!getClipSelectMultiple());

            //((NavigationIconLayout) toolbarClip.findViewById(R.id.selectMultipleButton)).getIconView().setColorFilter((isClipSelectMultiple ? 0xFFFF0000 : 0xFFFFFFFF), PorterDuff.Mode.SRC_ATOP);
        });
        toolbarClip.findViewById(R.id.applyKeyframeToAllClip).setOnClickListener(v -> {

            if(selectedTrack != null && selectedClip != null) {

                List<Clip> clips = selectedTrack.clips;
                for (Clip clip : clips) {
                    if(clip != selectedClip)
                    {
                        clip.importKeyframes(this, selectedClip.keyframes);

//                        clearKeyframe(clip);
//
//                        for (Keyframe keyframe : selectedClip.keyframes.keyframes) {
//                            addKeyframe(clip, keyframe);
//                        }
                    }
                }
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a track first!").show();

        });
        toolbarClip.findViewById(R.id.restateButton).setOnClickListener(v -> {
            if(selectedClip != null) {
                selectedClip.restate();

                // TODO: Find a way to specifically build only the edited clip. Not entire timeline
                //  this is just for testing. Resource-consuming asf.
                regeneratingTimelineRenderer();



            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a clip first!").show();
        });
        toolbarClip.findViewById(R.id.exportClipButton).setOnClickListener(v -> {
            if(selectedClip != null) {
                exportSingularClip(selectedClip);
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a clip first!").show();
        });

        // ===========================       CLIP ZONE       ====================================



        // ===========================       CLIPS ZONE       ====================================


        toolbarClips.findViewById(R.id.deleteMediaButton).setOnClickListener(v -> {
            if(selectedClips != null && !selectedClips.isEmpty()) {
                // Use BatchCommand to delete multiple clips
                BatchCommand batchDelete = new BatchCommand("Delete Multiple Clips");
                List<Clip> affectedClips = new ArrayList<>(selectedClips);
                for (Clip clip : affectedClips) {
                    batchDelete.addCommand(new DeleteClipCommand(this, clip));
                }
                executeCommand(batchDelete);
                updateCurrentClipEnd();
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick clips first!").show();

        });
        toolbarClips.findViewById(R.id.cloneMediaButton).setOnClickListener(v -> {
            if(selectedClips != null && !selectedClips.isEmpty()) {
                if (selectedTrack != null) {
                    // Use BatchCommand to clone multiple clips
                    BatchCommand batchClone = new BatchCommand("Clone Multiple Clips");
                    for (Clip clip : selectedClips) {
                        Clip cloneClip = new Clip(clip);
                        cloneClip.startTime = clip.startTime + clip.duration;
                        batchClone.addCommand(new AddClipCommand(this, selectedTrack, cloneClip));
                    }
                    executeCommand(batchClone);
                }
                else
                    new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a track first!").show();
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick clips first!").show();
        });
        toolbarClips.findViewById(R.id.editMediaButton).setOnClickListener(v -> {
            if(!selectedClips.isEmpty())
            {
                editingMultiple();
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a clips first!").show();
        });
        toolbarClips.findViewById(R.id.selectMultipleButton).setOnClickListener(v -> {
            // Todo: Not fully implemented yet. The idea is to remake the whole thing, get the "array" of selected clip is completed
            // now if one clip is move then the whole array move along. Also ghost will be as well

            setClipSelectMultiple(!getClipSelectMultiple());

            //((NavigationIconLayout) toolbarClips.findViewById(R.id.selectMultipleButton)).getIconView().setColorFilter((isClipSelectMultiple ? 0xFFFF0000 : 0xFFFFFFFF), PorterDuff.Mode.SRC_ATOP);
        });
        toolbarClips.findViewById(R.id.applyKeyframeToAllClip).setOnClickListener(v -> {

            // TODO: Make a menu that choose the available clips in the track to clone,
            //  temporary clone Keyframes from the first chosen clip.
            if(selectedClips != null) {
                for (Clip clip : selectedClips) {
                    if(clip != selectedClips.get(0))
                    {
                        clip.importKeyframes(this, selectedClips.get(0).keyframes);
                    }
                }
            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a track first!").show();

        });
        toolbarClips.findViewById(R.id.restateButton).setOnClickListener(v -> {
            if(selectedClips != null) {

                for (Clip clip : selectedClips) {
                    clip.restate();
                }

                // TODO: Find a way to specifically build only the edited clip. Not entire timeline
                //  this is just for testing. Resource-consuming asf.
                regeneratingTimelineRenderer();



            }
            else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a clips first!").show();
        });

        // ===========================       CLIPS ZONE       ====================================
    }

    private void setupSpecificEdit()
    {
        // ===========================       TEXT ZONE       ====================================
        textEditSpecificAreaScreen = (TextEditSpecificAreaScreen) LayoutInflater.from(this).inflate(R.layout.view_edit_specific_text, null);
        scene3dEditSpecificAreaScreen = (Scene3dEditSpecificAreaScreen) LayoutInflater.from(this).inflate(R.layout.view_edit_specific_scene3d, null);
        editingZone.addView(textEditSpecificAreaScreen);
        editingZone.addView(scene3dEditSpecificAreaScreen);
        // ===========================       TEXT ZONE       ====================================


        // ===========================       EFFECT ZONE       ====================================
        effectEditSpecificAreaScreen = (EffectEditSpecificAreaScreen) LayoutInflater.from(this).inflate(R.layout.view_edit_specific_effect, null);
        editingZone.addView(effectEditSpecificAreaScreen);
        // ===========================       EFFECT ZONE       ====================================


        // ===========================       TRANSITION ZONE       ====================================
        transitionEditSpecificAreaScreen = (TransitionEditSpecificAreaScreen) LayoutInflater.from(this).inflate(R.layout.view_edit_specific_transition, null);
        editingZone.addView(transitionEditSpecificAreaScreen);
        // ===========================       TRANSITION ZONE       ====================================

        // ===========================       MULTIPLE CLIPS ZONE       ====================================
        clipsEditSpecificAreaScreen = (ClipsEditSpecificAreaScreen) LayoutInflater.from(this).inflate(R.layout.view_edit_multiple_clips, null);
        editingZone.addView(clipsEditSpecificAreaScreen);
        // ===========================       MULTIPLE CLIPS ZONE       ====================================

        // ===========================       CLIP ZONE       ====================================
        clipEditSpecificAreaScreen = (ClipEditSpecificAreaScreen) LayoutInflater.from(this).inflate(R.layout.view_edit_specific_clip, null);
        editingZone.addView(clipEditSpecificAreaScreen);
        // ===========================       CLIP ZONE       ====================================


        // ===========================       VIDEO PROPERTIES ZONE       ====================================
        videoPropertiesEditSpecificAreaScreen = (VideoPropertiesEditSpecificAreaScreen) LayoutInflater.from(this).inflate(R.layout.view_edit_specific_video_properties, null);
        previewZone.addView(videoPropertiesEditSpecificAreaScreen);
        // ===========================       VIDEO PROPERTIES ZONE       ====================================


        // ===========================       PROJECT FILES ZONE       ====================================
        projectFilesEditSpecificAreaScreen = (ProjectFilesEditSpecificAreaScreen) LayoutInflater.from(this).inflate(R.layout.view_edit_project_files, null);
        projectFilesEditSpecificAreaScreen.properties = properties;
        projectFilesEditSpecificAreaScreen.activityInstance = this;
        previewZone.addView(projectFilesEditSpecificAreaScreen);
        // ===========================       PROJECT FILES ZONE       ====================================






        // ===========================       TEXT ZONE       ====================================

        textEditSpecificAreaScreen.onClose.add(() -> {
            if(selectedClip != null)
            {
                selectedClip.textContent = textEditSpecificAreaScreen.textEditContent.getText().toString();
                selectedClip.fontSize = ParserHelper.TryParse(textEditSpecificAreaScreen.textSizeContent.getText().toString(), 28f);
            }
        });
        
        scene3dEditSpecificAreaScreen.onClose.add(() -> {
            if (selectedClip != null && selectedClip.type == ClipType.SCENE_3D) {
                selectedClip.sceneConfig = scene3dEditSpecificAreaScreen.sceneConfigContent.getText().toString();
                selectedClip.textureClipName = scene3dEditSpecificAreaScreen.textureClipNameContent.getText().toString();
            }
        });
        textEditSpecificAreaScreen.onOpen.add(() -> {
            if (selectedClip != null && selectedClip.type == ClipType.TEXT) {
                textEditSpecificAreaScreen.textEditContent.setText(selectedClip.textContent);
                textEditSpecificAreaScreen.textSizeContent.setText(String.valueOf(selectedClip.fontSize));
            }
        });

        scene3dEditSpecificAreaScreen.onOpen.add(() -> {
            if (selectedClip != null && selectedClip.type == ClipType.SCENE_3D) {
                scene3dEditSpecificAreaScreen.sceneConfigContent.setText(selectedClip.sceneConfig);
                scene3dEditSpecificAreaScreen.textureClipNameContent.setText(selectedClip.textureClipName);
            }
        });

        // ===========================       TEXT ZONE       ====================================


        // ===========================       EFFECT ZONE       ====================================


        effectEditSpecificAreaScreen.onClose.add(() -> {
            if(selectedClip != null)
            {
                selectedClip.effect = new EffectTemplate((String) FXCommandEmitter.FXRegistry.effectsFXMap.keySet().toArray()[effectEditSpecificAreaScreen.effectEditContent.getSelectedItemPosition()], ParserHelper.TryParse(effectEditSpecificAreaScreen.effectDurationContent.getText().toString(), 1), 1);
            }
        });
        effectEditSpecificAreaScreen.onOpen.add(() -> {
            List<String> stringEffects = Arrays.asList(FXCommandEmitter.FXRegistry.effectsFXMap.values().toArray(new String[0]));
            effectEditSpecificAreaScreen.effectEditContent.setSelection(stringEffects.indexOf(FXCommandEmitter.FXRegistry.effectsFXMap.get(selectedClip.effect.style)));
            effectEditSpecificAreaScreen.effectDurationContent.setText(String.valueOf(selectedClip.effect.duration));
        });

        // ===========================       EFFECT ZONE       ====================================


        // ===========================       TRANSITION ZONE       ====================================



        transitionEditSpecificAreaScreen.onClose.add(() -> {
            if(selectedKnot != null)
            {
                for (int i = 0; i < timeline.tracks.get(selectedKnot.trackIndex).clips.size(); i++)
                {
                    TransitionClip clip = timeline.tracks.get(selectedKnot.trackIndex).clips.get(i).endTransition;
                    if(clip == selectedKnot)
                    {
                        clip.duration = ParserHelper.TryParse(transitionEditSpecificAreaScreen.transitionDurationContent.getText().toString(), 0.5f);
                        clip.effect.style = (String) FXCommandEmitter.FXRegistry.transitionFXMap.keySet().toArray()[transitionEditSpecificAreaScreen.transitionEditContent.getSelectedItemPosition()];
                        clip.effect.duration = ParserHelper.TryParse(transitionEditSpecificAreaScreen.transitionDurationContent.getText().toString(), 0.5f);
                        clip.mode = TransitionClip.TransitionMode.values()[transitionEditSpecificAreaScreen.transitionModeEditContent.getSelectedItemPosition()];
//
//                        for (TransitionClip clip2 : timeline.tracks.get(selectedKnot.trackIndex).transitions)
//                            System.err.println(clip2.duration);
                        selectedKnot = null;
                        break;
                    }
                }
            }
        });
        transitionEditSpecificAreaScreen.applyAllTransitionButton.setOnClickListener(v -> {
            transitionEditSpecificAreaScreen.animateLayout(BaseEditSpecificAreaScreen.AnimationType.Close);
            if(selectedKnot != null)
            {
                for (int i = 0; i < timeline.tracks.get(selectedKnot.trackIndex).clips.size(); i++)
                {
                    TransitionClip clip = timeline.tracks.get(selectedKnot.trackIndex).clips.get(i).endTransition;
                    if(clip == null) continue;
                    clip.duration = ParserHelper.TryParse(transitionEditSpecificAreaScreen.transitionDurationContent.getText().toString(), 0.5f);
                    clip.effect.style = (String) FXCommandEmitter.FXRegistry.transitionFXMap.keySet().toArray()[transitionEditSpecificAreaScreen.transitionEditContent.getSelectedItemPosition()];
                    clip.effect.duration = ParserHelper.TryParse(transitionEditSpecificAreaScreen.transitionDurationContent.getText().toString(), 0.5f);
                    clip.mode = TransitionClip.TransitionMode.values()[transitionEditSpecificAreaScreen.transitionModeEditContent.getSelectedItemPosition()];
                }
//                for (TransitionClip clip : timeline.tracks.get(selectedKnot.trackIndex).transitions)
//                    System.err.println(clip.duration);
                selectedKnot = null;

            }
        });
        transitionEditSpecificAreaScreen.onOpen.add(() -> {
            List<String> stringTransition = Arrays.asList(FXCommandEmitter.FXRegistry.transitionFXMap.values().toArray(new String[0]));
            transitionEditSpecificAreaScreen.transitionEditContent.setSelection(stringTransition.indexOf(FXCommandEmitter.FXRegistry.transitionFXMap.get(selectedKnot.effect.style)));
            transitionEditSpecificAreaScreen.transitionDurationContent.setText(String.valueOf(selectedKnot.effect.duration));
            transitionEditSpecificAreaScreen.transitionModeEditContent.setSelection(selectedKnot.mode.ordinal());
        });

        // ===========================       TRANSITION ZONE       ====================================



        // ===========================       MULTIPLE CLIPS ZONE       ====================================

        clipsEditSpecificAreaScreen.onClose.add(() -> {
            if(!selectedClips.isEmpty())
            {
                for (Clip clip : selectedClips) {
                    float defaultValue = clip.duration;
                    clip.setDuration(ParserHelper.TryParse(clipsEditSpecificAreaScreen.clipsDurationContent.getText().toString(), defaultValue));
                }
                updateClipLayouts();
                updateCurrentClipEnd();
            }
        });
        clipsEditSpecificAreaScreen.onOpen.add(() -> {
            // TODO: Didn't change following the endClipTrim/startClipTrim rule yet.
            clipsEditSpecificAreaScreen.clipsDurationContent.setText(String.valueOf(selectedClips.get(0).duration));
        });


        // ===========================       MULTIPLE CLIPS ZONE       ====================================



        // ===========================       CLIP ZONE       ====================================

        clipEditSpecificAreaScreen.onClose.add(() -> {
            if(selectedClip != null)
            {
                // Only modify if different
                if(!selectedClip.getClipName().equals(clipEditSpecificAreaScreen.clipNameField.getText().toString()))
                    selectedClip.setClipName(clipEditSpecificAreaScreen.clipNameField.getText().toString(), properties, timeline);
                selectedClip.setStartClipTrim(ParserHelper.TryParse(clipEditSpecificAreaScreen.startTrimField.getText().toString(), selectedClip.getStartClipTrim()));
                selectedClip.setEndClipTrim(ParserHelper.TryParse(clipEditSpecificAreaScreen.endTrimField.getText().toString(), selectedClip.getEndClipTrim()));

                // Apply to the keyframe if possible
                Keyframe selectedKeyframe = selectedClip.keyframes.getKeyframeAtTime(selectedClip, currentTime);
                if(selectedKeyframe != null)
                {
                    selectedKeyframe.value.setAllValue(
                            ParserHelper.TryParse(clipEditSpecificAreaScreen.positionXField.getText().toString(), selectedKeyframe.value.getValue(VideoProperties.ValueType.PosX)),
                            ParserHelper.TryParse(clipEditSpecificAreaScreen.positionYField.getText().toString(), selectedKeyframe.value.getValue(VideoProperties.ValueType.PosY)),
                            ParserHelper.TryParse(clipEditSpecificAreaScreen.rotationField.getText().toString(), selectedKeyframe.value.getValue(VideoProperties.ValueType.Rot)),
                            ParserHelper.TryParse(clipEditSpecificAreaScreen.scaleXField.getText().toString(), selectedKeyframe.value.getValue(VideoProperties.ValueType.ScaleX)),
                            ParserHelper.TryParse(clipEditSpecificAreaScreen.scaleYField.getText().toString(), selectedKeyframe.value.getValue(VideoProperties.ValueType.ScaleY)),
                            ParserHelper.TryParse(clipEditSpecificAreaScreen.hueField.getText().toString(), selectedKeyframe.value.getValue(VideoProperties.ValueType.Hue)),

                            Math.clamp(clipEditSpecificAreaScreen.opacitySeekbar.getProgressFloat(), 0f, 1f),
                            Math.clamp(clipEditSpecificAreaScreen.speedSeekbar.getProgressFloat(), 0.01f, 10f),
                            Math.clamp(clipEditSpecificAreaScreen.saturationSeekbar.getProgressFloat(), -10f, 10f),
                            Math.clamp(clipEditSpecificAreaScreen.brightnessSeekbar.getProgressFloat(), -10f, 10f),
                            Math.clamp(clipEditSpecificAreaScreen.temperatureSeekbar.getProgressFloat(), 1000f, 40000f)

                            );

                    selectedKeyframe.easing = (EasingType) clipEditSpecificAreaScreen.easingSpinner.getSelectedItem();
                }
                else {

                    new AlertDialog.Builder(this)
                            .setTitle("Missing Keyframe")
                            .setMessage("There is no keyframe at this timestamp. How would you like to apply these property changes?")
                            .setPositiveButton("New Keyframe", (dialog, which) -> {

                                addKeyframe(selectedClip, currentTime);


                                Keyframe insertionKeyframe = selectedClip.keyframes.getKeyframeAtTime(selectedClip, currentTime);

                                if(insertionKeyframe == null) {
                                    LoggingManager.LogToToast(this, "Keyframe insertion error.");
                                }
                                else {

                                    insertionKeyframe.value.setAllValue(
                                            ParserHelper.TryParse(clipEditSpecificAreaScreen.positionXField.getText().toString(), insertionKeyframe.value.getValue(VideoProperties.ValueType.PosX)),
                                            ParserHelper.TryParse(clipEditSpecificAreaScreen.positionYField.getText().toString(), insertionKeyframe.value.getValue(VideoProperties.ValueType.PosY)),
                                            ParserHelper.TryParse(clipEditSpecificAreaScreen.rotationField.getText().toString(), insertionKeyframe.value.getValue(VideoProperties.ValueType.Rot)),
                                            ParserHelper.TryParse(clipEditSpecificAreaScreen.scaleXField.getText().toString(), insertionKeyframe.value.getValue(VideoProperties.ValueType.ScaleX)),
                                            ParserHelper.TryParse(clipEditSpecificAreaScreen.scaleYField.getText().toString(), insertionKeyframe.value.getValue(VideoProperties.ValueType.ScaleY)),
                                            ParserHelper.TryParse(clipEditSpecificAreaScreen.hueField.getText().toString(), insertionKeyframe.value.getValue(VideoProperties.ValueType.Hue)),

                                            Math.clamp(clipEditSpecificAreaScreen.opacitySeekbar.getProgressFloat(), 0f, 1f),
                                            Math.clamp(clipEditSpecificAreaScreen.speedSeekbar.getProgressFloat(), 0.01f, 10f),
                                            Math.clamp(clipEditSpecificAreaScreen.saturationSeekbar.getProgressFloat(), -10f, 10f),
                                            Math.clamp(clipEditSpecificAreaScreen.brightnessSeekbar.getProgressFloat(), -10f, 10f),
                                            Math.clamp(clipEditSpecificAreaScreen.temperatureSeekbar.getProgressFloat(), 1000f, 40000f)

                                    );

                                    insertionKeyframe.easing = (EasingType) clipEditSpecificAreaScreen.easingSpinner.getSelectedItem();

                                    LoggingManager.LogToToast(this, "New keyframe added at " + currentTime);
                                }
                            })
                            .setNeutralButton("Apply to Clip", (dialog, which) -> {

                                selectedClip.videoProperties.setAllValue(
                                        ParserHelper.TryParse(clipEditSpecificAreaScreen.positionXField.getText().toString(), selectedClip.videoProperties.getValue(VideoProperties.ValueType.PosX)),
                                        ParserHelper.TryParse(clipEditSpecificAreaScreen.positionYField.getText().toString(), selectedClip.videoProperties.getValue(VideoProperties.ValueType.PosY)),
                                        ParserHelper.TryParse(clipEditSpecificAreaScreen.rotationField.getText().toString(), selectedClip.videoProperties.getValue(VideoProperties.ValueType.Rot)),
                                        ParserHelper.TryParse(clipEditSpecificAreaScreen.scaleXField.getText().toString(), selectedClip.videoProperties.getValue(VideoProperties.ValueType.ScaleX)),
                                        ParserHelper.TryParse(clipEditSpecificAreaScreen.scaleYField.getText().toString(), selectedClip.videoProperties.getValue(VideoProperties.ValueType.ScaleY)),
                                        ParserHelper.TryParse(clipEditSpecificAreaScreen.hueField.getText().toString(), selectedClip.videoProperties.getValue(VideoProperties.ValueType.Hue)),

                                        Math.clamp(clipEditSpecificAreaScreen.opacitySeekbar.getProgressFloat(), 0f, 1f),
                                        Math.clamp(clipEditSpecificAreaScreen.speedSeekbar.getProgressFloat(), 0.01f, 10f),
                                        Math.clamp(clipEditSpecificAreaScreen.saturationSeekbar.getProgressFloat(), -10f, 10f),
                                        Math.clamp(clipEditSpecificAreaScreen.brightnessSeekbar.getProgressFloat(), -10f, 10f),
                                        Math.clamp(clipEditSpecificAreaScreen.temperatureSeekbar.getProgressFloat(), 1000f, 40000f)

                                );

                            })
                            .setNegativeButton("Revert", (dialog, which) -> {
                                // Dismiss the dialog and potentially refresh the UI fields to original values
                                dialog.dismiss();
                                LoggingManager.LogToToast(this, "Changes discarded");
                            })
                            .show();

                }

                selectedClip.setAdditionalFFmpegCommand(clipEditSpecificAreaScreen.additionFFmpegCommandField.getText().toString());
                selectedClip.setMute(clipEditSpecificAreaScreen.muteAudioCheckbox.isChecked());
                selectedClip.setLockedForTemplate(clipEditSpecificAreaScreen.lockMediaForTemplateCheckbox.isChecked());
                selectedClip.setReverse(clipEditSpecificAreaScreen.reverseCheckbox.isChecked());
                selectedClip.removeBackground = clipEditSpecificAreaScreen.removeBackgroundCheckbox.isChecked();

                if (selectedClip.inAnimation == null) selectedClip.inAnimation = new AnimationClip("none", 0.5f);
                selectedClip.inAnimation.type = (String) clipEditSpecificAreaScreen.inAnimationTypeSpinner.getSelectedItem();
                selectedClip.inAnimation.duration = ParserHelper.TryParse(clipEditSpecificAreaScreen.inAnimationDurationField.getText().toString(), selectedClip.inAnimation.duration);

                updateClipLayouts();
                updateCurrentClipEnd();
                // TODO: Find a way to specifically build only the edited clip. Not entire timeline
                //  this is just for testing. Resource-consuming asf.
                regeneratingTimelineRenderer();


                clipEditSpecificAreaScreen.keyframeScrollFrame.removeAllViews();
            }
        });
        clipEditSpecificAreaScreen.onOpen.add(() -> {
            clipEditSpecificAreaScreen.totalDurationText.setText(String.valueOf(selectedClip.originalDuration));
            clipEditSpecificAreaScreen.durationText.setText(String.valueOf(selectedClip.duration));
            clipEditSpecificAreaScreen.clipNameField.setText(String.valueOf(selectedClip.getClipName()));
            clipEditSpecificAreaScreen.startTrimField.setText(String.valueOf(selectedClip.startClipTrim));
            clipEditSpecificAreaScreen.endTrimField.setText(String.valueOf(selectedClip.endClipTrim));
            clipEditSpecificAreaScreen.positionXField.setText(String.valueOf(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.PosX)));
            clipEditSpecificAreaScreen.positionYField.setText(String.valueOf(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.PosY)));
            clipEditSpecificAreaScreen.rotationField.setText(String.valueOf(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.Rot)));
            clipEditSpecificAreaScreen.scaleXField.setText(String.valueOf(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.ScaleX)));
            clipEditSpecificAreaScreen.scaleYField.setText(String.valueOf(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.ScaleY)));
            clipEditSpecificAreaScreen.hueField.setText(String.valueOf(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.Hue)));

            clipEditSpecificAreaScreen.opacitySeekbar.setProgress(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.Opacity));
            clipEditSpecificAreaScreen.speedSeekbar.setProgress(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.Speed));
            clipEditSpecificAreaScreen.saturationSeekbar.setProgress(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.Saturation));
            clipEditSpecificAreaScreen.brightnessSeekbar.setProgress(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.Brightness));
            clipEditSpecificAreaScreen.temperatureSeekbar.setProgress(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.Temperature));

            clipEditSpecificAreaScreen.additionFFmpegCommandField.setText(selectedClip.additionalFFmpegCommand);
            clipEditSpecificAreaScreen.muteAudioCheckbox.setChecked(selectedClip.isMute());
            clipEditSpecificAreaScreen.lockMediaForTemplateCheckbox.setChecked(selectedClip.isLockedForTemplate());
            clipEditSpecificAreaScreen.reverseCheckbox.setChecked(selectedClip.isReverse());
            clipEditSpecificAreaScreen.removeBackgroundCheckbox.setChecked(selectedClip.removeBackground);
            clipEditSpecificAreaScreen.removeBackgroundProgress.setVisibility(View.GONE);

            List<String> animTypes = Arrays.asList("none", "unfold");
            if (selectedClip.inAnimation == null) selectedClip.inAnimation = new AnimationClip("none", 0.5f);
            clipEditSpecificAreaScreen.inAnimationTypeSpinner.setSelection(animTypes.indexOf(selectedClip.inAnimation.type));
            clipEditSpecificAreaScreen.inAnimationDurationField.setText(String.valueOf(selectedClip.inAnimation.duration));

            clipEditSpecificAreaScreen.removeBackgroundCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (selectedClip != null) {
                    selectedClip.removeBackground = isChecked;
                    if (isChecked) {
                        processBackgroundRemoval(selectedClip);
                    } else {
                        timelineRenderer.updateTime(currentTime, true);
                    }
                }
            });
            clipEditSpecificAreaScreen.removeBackgroundCheckbox.setChecked(selectedClip.removeBackground);
            clipEditSpecificAreaScreen.removeBackgroundProgress.setVisibility(View.GONE);


            Keyframe k = selectedClip.keyframes.getKeyframeAtTime(selectedClip, currentTime);
            if(k != null) {
                clipEditSpecificAreaScreen.easingSpinner.setSelection(clipEditSpecificAreaScreen.easingTypeArrayAdapter.getPosition(k.easing));
            }
            else LoggingManager.LogToToast(this, "There are 0 keyframe to show at this time.");


            for(Keyframe keyframe : selectedClip.keyframes.keyframes)
            {
                clipEditSpecificAreaScreen.createKeyframeElement(selectedClip, keyframe, () -> {
                    setCurrentTime(keyframe.getGlobalTime(selectedClip));


                    // Same as above
                    clipEditSpecificAreaScreen.positionXField.setText(String.valueOf(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.PosX)));
                    clipEditSpecificAreaScreen.positionYField.setText(String.valueOf(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.PosY)));
                    clipEditSpecificAreaScreen.rotationField.setText(String.valueOf(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.Rot)));
                    clipEditSpecificAreaScreen.scaleXField.setText(String.valueOf(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.ScaleX)));
                    clipEditSpecificAreaScreen.scaleYField.setText(String.valueOf(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.ScaleY)));
                    clipEditSpecificAreaScreen.hueField.setText(String.valueOf(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.Hue)));

                    clipEditSpecificAreaScreen.opacitySeekbar.setProgress(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.Opacity));
                    clipEditSpecificAreaScreen.speedSeekbar.setProgress(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.Speed));
                    clipEditSpecificAreaScreen.saturationSeekbar.setProgress(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.Saturation));
                    clipEditSpecificAreaScreen.brightnessSeekbar.setProgress(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.Brightness));
                    clipEditSpecificAreaScreen.temperatureSeekbar.setProgress(selectedClip.keyframes.getValueAtTime(selectedClip, currentTime, VideoProperties.ValueType.Temperature));

                }, () -> {
                    removeKeyframe(selectedClip, keyframe);
                });
            }

            clipEditSpecificAreaScreen.clearKeyframeButton.setOnClickListener(v -> {
                if(selectedClip != null)
                {
                    clearKeyframe(selectedClip);
                }
            });

            clipEditSpecificAreaScreen.importKeyframesButton.setOnClickListener(v -> {
                if(selectedClip != null) {
                    importSingularKeyframesList();
                }
                else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a clip first!").show();
            });
            clipEditSpecificAreaScreen.exportKeyframesButton.setOnClickListener(v -> {
                if(selectedClip != null) {
                    exportSingularKeyframesList(selectedClip.keyframes);
                }
                else new AlertDialog.Builder(this).setTitle("Error").setMessage("You need to pick a clip first!").show();
            });


        });


        // ===========================       CLIP ZONE       ====================================



        // ===========================       VIDEO PROPERTIES ZONE       ====================================

        videoPropertiesEditSpecificAreaScreen.onClose.add(() -> {
            settings.videoWidth = ParserHelper.TryParse(videoPropertiesEditSpecificAreaScreen.resolutionXField.getText().toString(), settings.videoWidth);
            settings.videoHeight = ParserHelper.TryParse(videoPropertiesEditSpecificAreaScreen.resolutionYField.getText().toString(), settings.videoHeight);
            settings.frameRate = ParserHelper.TryParse(videoPropertiesEditSpecificAreaScreen.frameRateField.getText().toString(), settings.frameRate);
            settings.crf = ParserHelper.TryParse(videoPropertiesEditSpecificAreaScreen.crfField.getText().toString(), settings.crf);
            settings.bitrate = ParserHelper.TryParse(videoPropertiesEditSpecificAreaScreen.bitrateField.getText().toString(), settings.bitrate);
            settings.clipCap = ParserHelper.TryParse(videoPropertiesEditSpecificAreaScreen.clipCapField.getText().toString(), settings.clipCap);
            settings.preset = videoPropertiesEditSpecificAreaScreen.presetSpinner.getSelectedItem().toString();
            settings.tune = videoPropertiesEditSpecificAreaScreen.tuneSpinner.getSelectedItem().toString();
            settings.isStretchToFull = videoPropertiesEditSpecificAreaScreen.stretchMediaToFullCheckbox.isChecked();
            settings.useHardwareAccel = videoPropertiesEditSpecificAreaScreen.hardwareAccelCheckbox.isChecked();

            settings.saveSettings(this, properties);



            ViewGroup.LayoutParams previewViewGroupParams = previewViewGroup.getLayoutParams();
            previewViewGroupParams.width = settings.videoWidth;
            previewViewGroupParams.height = settings.videoHeight;
            previewViewGroup.setLayoutParams(previewViewGroupParams);

            outerPreviewViewGroup.post(() -> {
                previewAvailableWidth = outerPreviewViewGroup.getWidth();
                previewAvailableHeight = outerPreviewViewGroup.getHeight();
                
                float ratioX = (float) previewAvailableWidth / settings.videoWidth;
                float ratioY = (float) previewAvailableHeight / settings.videoHeight;
                float rScale = Math.min(ratioX, ratioY);
                previewViewGroup.setScaleX(rScale);
                previewViewGroup.setScaleY(rScale);
            });


            // Update ruler with new fps
            updateRuler(timeline.duration, currentRulerInterval);
        });
        videoPropertiesEditSpecificAreaScreen.onOpen.add(() -> {
            videoPropertiesEditSpecificAreaScreen.resolutionXField.setText(String.valueOf(settings.getVideoWidth()));
            videoPropertiesEditSpecificAreaScreen.resolutionYField.setText(String.valueOf(settings.getVideoHeight()));
            videoPropertiesEditSpecificAreaScreen.frameRateField.setText(String.valueOf(settings.getFrameRate()));
            videoPropertiesEditSpecificAreaScreen.crfField.setText(String.valueOf(settings.getCRF()));
            videoPropertiesEditSpecificAreaScreen.bitrateField.setText(String.valueOf(settings.getBitrate()));
            videoPropertiesEditSpecificAreaScreen.clipCapField.setText(String.valueOf(settings.getClipCap()));
            videoPropertiesEditSpecificAreaScreen.presetSpinner.setSelection(videoPropertiesEditSpecificAreaScreen.presetAdapter.getPosition(settings.getPreset()));
            videoPropertiesEditSpecificAreaScreen.tuneSpinner.setSelection(videoPropertiesEditSpecificAreaScreen.tuneAdapter.getPosition(settings.getTune()));
            videoPropertiesEditSpecificAreaScreen.stretchMediaToFullCheckbox.setChecked(settings.isStretchToFull());
            videoPropertiesEditSpecificAreaScreen.hardwareAccelCheckbox.setChecked(settings.isUseHardwareAccel());
            videoPropertiesEditSpecificAreaScreen.updateHardwareAccelState(settings.isUseHardwareAccel());

            float activeFps = previewFpsRuntime > 0 ? previewFpsRuntime : settings.frameRate;
            videoPropertiesEditSpecificAreaScreen.previewFpsField.setText(String.format(java.util.Locale.US, "%.1f", activeFps));
            videoPropertiesEditSpecificAreaScreen.previewSpeedField.setText(String.format(java.util.Locale.US, "%.2f", activeFps / settings.frameRate));

            videoPropertiesEditSpecificAreaScreen.reversePlaybackCheckbox.setChecked(isPlayingInReverse);
            videoPropertiesEditSpecificAreaScreen.keepPlaybackWhenClipSelectedCheckbox.setChecked(keepPlayingWhenClipSelected);


            videoPropertiesEditSpecificAreaScreen.audioBarWidthField.setText(String.valueOf(thumbnailAudioBarWidth));
            videoPropertiesEditSpecificAreaScreen.audioBarGapField.setText(String.valueOf(thumbnailAudioBarGap));


        });

        videoPropertiesEditSpecificAreaScreen.previewFpsField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if(videoPropertiesEditSpecificAreaScreen.previewFpsField.hasFocus()) {
                    try {
                        float fps = Float.parseFloat(s.toString());
                        if (fps > 0) {
                            videoPropertiesEditSpecificAreaScreen.previewSpeedField.setText(String.format(java.util.Locale.US, "%.2f", fps / settings.frameRate));
                            previewFpsRuntime = fps;
                        }
                    } catch (Exception ignored) {}
                }
            }
        });

        videoPropertiesEditSpecificAreaScreen.previewSpeedField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if(videoPropertiesEditSpecificAreaScreen.previewSpeedField.hasFocus()) {
                    try {
                        float speed = Float.parseFloat(s.toString());
                        if (speed > 0) {
                            float fps = speed * settings.frameRate;
                            videoPropertiesEditSpecificAreaScreen.previewFpsField.setText(String.format(java.util.Locale.US, "%.1f", fps));
                            previewFpsRuntime = fps;
                        }
                    } catch (Exception ignored) {}
                }
            }
        });

        videoPropertiesEditSpecificAreaScreen.reversePlaybackCheckbox.setOnCheckedChangeListener(
                (buttonView, isChecked) -> isPlayingInReverse = isChecked);

        videoPropertiesEditSpecificAreaScreen.keepPlaybackWhenClipSelectedCheckbox.setOnCheckedChangeListener(
                (buttonView, isChecked) -> keepPlayingWhenClipSelected = isChecked);


        videoPropertiesEditSpecificAreaScreen.audioBarWidthField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if(videoPropertiesEditSpecificAreaScreen.audioBarWidthField.hasFocus()) {
                    try {
                        int barWidth = Integer.parseInt(s.toString());
                        if (barWidth > 0) {
                            thumbnailAudioBarWidth = barWidth;
                        }
                        else  {
                            thumbnailAudioBarWidth = 1;
                        }
                    } catch (Exception ignored) {}
                }
            }
        });
        videoPropertiesEditSpecificAreaScreen.audioBarGapField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if(videoPropertiesEditSpecificAreaScreen.audioBarGapField.hasFocus()) {
                    try {
                        int barGap = Integer.parseInt(s.toString());
                        if (barGap >= 0) {
                            thumbnailAudioBarGap = barGap;
                        }
                        else  {
                            thumbnailAudioBarGap = 0;
                        }
                    } catch (Exception ignored) {}
                }
            }
        });



        // ===========================       VIDEO PROPERTIES ZONE       ====================================



        // ===========================       PROJECT FILES ZONE       ====================================
        projectFilesEditSpecificAreaScreen.onClose.add(() -> {

        });
        projectFilesEditSpecificAreaScreen.onOpen.add(() -> {

        });
        // ===========================       PROJECT FILES ZONE       ====================================


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
                scaleFactor = Math.max(MIN_SCALE_FACTOR, Math.min(scaleFactor, MAX_SCALE_FACTOR));

                pixelsPerSecond = (int) (basePixelsPerSecond * scaleFactor);
                stopPlayback(false);
                updateTimelineZoom();
                return true;
            }
        });

    }

    private void startPlayback() {


        if(selectedClip != null) {
            if(selectedClip.startTime > currentTime || currentTime > selectedClip.startTime + selectedClip.duration)
            {
                currentTime = selectedClip.startTime;
            }
        }

        timelineRenderer.startPlayAt(currentTime);
        playbackLoop = new Runnable() {
            private long lastFrameTime = -1;

            @Override
            public void run() {
                if (!isPlaying) return;

                long now = System.nanoTime();
                float activeFps = previewFpsRuntime > 0 ? previewFpsRuntime : settings.frameRate;
                float playbackSpeed = activeFps / settings.frameRate;
                long targetIntervalMs = (long)(1000f / activeFps);

                // First frame: just record time and schedule next
                if (lastFrameTime < 0) {
                    lastFrameTime = now;
                } else {
                    // Use ACTUAL elapsed time instead of fixed frameInterval
                    float elapsed = (now - lastFrameTime) / 1_000_000_000f;
                    lastFrameTime = now;

                    currentTime += (isPlayingInReverse ? -elapsed : elapsed) * playbackSpeed;
                }

                // Compensate: subtract time already spent in this callback
                long spent = (System.nanoTime() - now) / 1_000_000;
                long nextDelay = Math.max(0, targetIntervalMs - spent);

                timelineRenderer.updateTime(currentTime, false);

                int newScrollX = (int)(currentTime * pixelsPerSecond);
                timelineScroll.scrollTo(newScrollX, 0);

                if (selectedClip != null) {
                    if (selectedClip.startTime > currentTime || currentTime > selectedClip.startTime + selectedClip.duration) {
                        if (!keepPlayingWhenClipSelected) {
                            stopPlayback(false);
                            return;
                        }
                    }
                }

                if (currentTime >= timeline.duration || (currentTime <= 0f && isPlayingInReverse)) {
                    currentTime = isPlayingInReverse ? timeline.duration : 0f;
                    stopPlayback(true);
                    return; // avoid scheduling after stop
                }

                playbackHandler.postDelayed(this, nextDelay);
            }
        };

// Reset lastFrameTime before posting (use a wrapper or reset field on play start)
        playbackHandler.post(playbackLoop);
    }

    private void stopPlayback(boolean recreateTimeline) {
        isPlaying = false;

        playbackHandler.removeCallbacks(playbackLoop);
        playPauseButton.setImageResource(R.drawable.baseline_play_circle_24);

        timelineRenderer.updateTime(currentTime, true); // To stop audio playback

        if(recreateTimeline)
            regeneratingTimelineRenderer();
    }
    private void regeneratingTimelineRenderer()
    {

        LoggingManager.LogToToast(this, "Begin prepare for preview!");
        //refreshPreviewClip();

        // TODO: Tested for dragging back and forth clips. They're doing fine with the extractor SYNC_EXACT
        //  Limit the time of refreshing entire timeline like this.
        timelineRenderer.buildTimeline(timeline, properties, settings, this, previewViewGroup, textCanvasControllerInfo);

        pausedCanvasAlertPanel.setVisibility(View.GONE);
    }
    private void regeneratingPreviewThumbnail()
    {

        LoggingManager.LogToToast(this, "Begin prepare for thumbnail!");

        for (Clip clip : timeline.getStreamOfClip()) {
            clip.viewRef.setFilledImageBitmap(ImageHelper.createSolidColorBitmap(100, 100, 0xFF000000));
            clip.viewRef.showLoading();
            Executors.newSingleThreadExecutor().execute(() -> {
                Bitmap retrieveBitmap = combineThumbnails(extractThumbnail(this, clip.getAbsolutePreviewPath(properties, Constants.DEFAULT_PREVIEW_CLIP_VIDEO_EXTENSION), clip));
                clip.viewRef.post(() -> {
                    clip.viewRef.setFilledImageBitmap(retrieveBitmap);
                });
            });
        }
    }
    private void releaseTimelineRenderer()
    {
        timelineRenderer.release();
        pausedCanvasAlertPanel.setVisibility(View.VISIBLE);
    }
    private void setCurrentTime(float value)
    {
        currentTime = value;
        timelineScroll.scrollTo((int) (currentTime * pixelsPerSecond), 0);
    }

    void setupPreview()
    {
        timelineRenderer = new TimelineRenderer(this);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenHeight = metrics.heightPixels;
        editingZone.getLayoutParams().height = (int) (screenHeight * 0.35);
        editingZone.requestLayout();
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

        releaseTimelineRenderer();
        Timeline.saveTimeline(this, timeline, properties, settings);
    }
    @Override
    public void onPause() {
        super.onPause();

        stopPlayback(false);
        releaseTimelineRenderer();
        Timeline.saveTimeline(this, timeline, properties, settings);
    }
    @Override
    public void onResume() {
        super.onResume();
    }

    private void loadExistingTrack(Track track) {
        track.viewRef = addNewTrackUi();
        track.viewRef.trackInfo = track;
        for (Clip clip : track.clips) {
            clip.trackIndex = track.timelineIndex;
            clip.filterNullAfterLoad();
            addClipToTrackUi(track.viewRef, clip);
        }
    }
    private Track addNewTrack() {
        Track trackInfo = new Track();
        loadExistingTrack(trackInfo);

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
        // This spacer is crucial to the new project as it provide a space for new track to have room to click
        View startSpacer = new View(this);
        TrackFrameLayout.LayoutParams spacerParams = new TrackFrameLayout.LayoutParams(
                centerOffset,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        startSpacer.setLayoutParams(spacerParams);
        track.addView(startSpacer); // Add spacer before any clips

        TextView trackInfoView = new TextView(this);
        LinearLayout.LayoutParams trackInfoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                TRACK_HEIGHT
        );
        trackInfoView.setLayoutParams(trackInfoParams);
        trackInfoView.setGravity(Gravity.CENTER);
        trackInfoView.setText( "Track " + trackCount);


        final int finalTrackIndex = trackCount;
        trackInfoView.setOnClickListener(v -> {

            PopupMenu popup = new PopupMenu(this, v);
            popup.getMenuInflater().inflate(R.menu.menu_cpn_edit_track_more, popup.getMenu());

            popup.setOnMenuItemClickListener(item -> {
                if(item.getItemId() == R.id.action_move_up)
                {
                    timeline.moveTrackUp(this, timeline.tracks.get(finalTrackIndex));
                    return true;
                }
                if(item.getItemId() == R.id.action_move_down)
                {
                    timeline.moveTrackDown(this, timeline.tracks.get(finalTrackIndex));
                    return true;
                }
                return false;
            });

            popup.show();
        });



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

    /**
     * Execute a command through the command manager.
     * This is the preferred way to perform editing operations.
     */
    public void executeCommand(CommandUtils.Command cmd) {
        actionManager.executeCommand(cmd);
    }
    
    /**
     * Update undo/redo button states.
     * Called by the CommandManager state change listener.
     */
    private void updateUndoRedoButtons() {
        if (undoButton != null) {
            undoButton.setEnabled(actionManager.isUndoAvailable());
        }
        if (redoButton != null) {
            redoButton.setEnabled(actionManager.isRedoAvailable());
        }
    }

    /**
     * Legacy method for adding clip to track.
     * Consider using AddClipCommand instead for undo/redo support.
     */
    public void addClipToTrack(Track track, Clip data) {
        addClipToTrackUi(track.viewRef, data);

        track.addClip(data);
    }

    private void captureSnapshot() {
        if (timeline == null) return;

        Clip targetClip = selectedClip;
        if (targetClip == null || currentTime < targetClip.startTime || currentTime > targetClip.startTime + targetClip.duration) {
            List<Clip> clipsAtTime = timeline.getClipsAtCurrentTime(currentTime);
            if (!clipsAtTime.isEmpty()) {
                targetClip = clipsAtTime.get(0);
            }
        }

        if (targetClip == null || targetClip.type != ClipType.VIDEO) {
            LoggingManager.LogToToast(this, "No video clip found at current position");
            return;
        }

        float localTime = currentTime - targetClip.startTime + targetClip.startClipTrim;
        String sourcePath = targetClip.getAbsolutePath(properties);

        if (!IOHelper.isFileExist(sourcePath)) {
            LoggingManager.LogToToast(this, "Source file not found: " + sourcePath);
            return;
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(sourcePath);
            Bitmap bitmap = retriever.getFrameAtTime((long) (localTime * 1000000), MediaMetadataRetriever.OPTION_CLOSEST);
            if (bitmap != null) {
                String snapshotName = "snapshot_" + targetClip.getClipName() + "_" + System.currentTimeMillis() + ".png";
                String destinationPath = IOHelper.CombinePath(properties.getProjectPath(), Constants.DEFAULT_CLIP_DIRECTORY, snapshotName);

                IOImageHelper.SaveFileAsPNGImage(this, destinationPath, bitmap);

                // Notify Gallery
                MediaScannerConnection.scanFile(this, new String[]{destinationPath}, null, (path, uri) -> {
                    // Log to note overlay if needed
                });

                LoggingManager.LogToToast(this, "Snapshot saved: " + snapshotName);
            } else {
                LoggingManager.LogToToast(this, "Failed to capture snapshot");
            }
        } catch (Exception e) {
            LoggingManager.LogExceptionToNoteOverlay(this, e);
            LoggingManager.LogToToast(this, "Error capturing snapshot");
        } finally {
            try {
                retriever.release();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Show a fade-away toast notification for undo/redo actions.
     * @param action "Undo" or "Redo"
     * @param commandDescription Description of the command
     * @param iconResId Icon resource ID
     */
    private void showUndoRedoToast(String action, String commandDescription, int iconResId) {
        try {
            LayoutInflater inflater = getLayoutInflater();
            View layout = inflater.inflate(R.layout.toast_undo_redo, null);

            ImageView icon = layout.findViewById(R.id.toastIcon);
            TextView text = layout.findViewById(R.id.toastText);

            icon.setImageResource(iconResId);
            text.setText(action + ": " + commandDescription);

            android.widget.Toast toast = new android.widget.Toast(getApplicationContext());
            toast.setDuration(android.widget.Toast.LENGTH_SHORT);
            toast.setView(layout);
            toast.show();
        } catch (Exception e) {
            // Fallback to simple toast if custom layout fails
            android.widget.Toast.makeText(this, action + ": " + commandDescription, android.widget.Toast.LENGTH_SHORT).show();
        }
    }
    public void addClipToTrackUi(TrackFrameLayout trackLayout, Clip data)
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
        clipView.setBackgroundColor(0xFF000000);
        clipView.showLoading();
        // Set the thumbnail using preview for less resource consumption.
        Executors.newSingleThreadExecutor().execute(() -> {
            Bitmap retrieveBitmap = combineThumbnails(extractThumbnail(this, data.getAbsolutePreviewPath(properties, Constants.DEFAULT_PREVIEW_CLIP_VIDEO_EXTENSION), data));
            clipView.post(() -> {
                clipView.setFilledImageBitmap(retrieveBitmap);
            });
        });
//        clipView.setFilledImageBitmap(combineThumbnails(extractThumbnail(this, data.getAbsolutePreviewPath(properties), data)));
        clipView.setTag(data);


        data.initClip(clipView, this, timelineScroll);




        clipView.post(() -> {
            updateCurrentClipEnd();

            for (Keyframe keyframe : data.keyframes.keyframes) {
                addKeyframeUi(data, keyframe);
            }
        });




        trackLayout.addView(clipView);
        data.addTransitionKnotToTrack(trackLayout);

        handleClipInteraction(clipView);
    }

    public void addKeyframe(Clip clip, float keyframeTime)
    {
        addKeyframe(clip, new Keyframe(keyframeTime - clip.startTime, new VideoProperties(
                clip.videoProperties.getValue(VideoProperties.ValueType.PosX), clip.videoProperties.getValue(VideoProperties.ValueType.PosY),
                clip.videoProperties.getValue(VideoProperties.ValueType.Rot),
                clip.videoProperties.getValue(VideoProperties.ValueType.ScaleX), clip.videoProperties.getValue(VideoProperties.ValueType.ScaleY),
                clip.videoProperties.getValue(VideoProperties.ValueType.Opacity), clip.videoProperties.getValue(VideoProperties.ValueType.Speed),
                clip.videoProperties.getValue(VideoProperties.ValueType.Hue), clip.videoProperties.getValue(VideoProperties.ValueType.Saturation),
                clip.videoProperties.getValue(VideoProperties.ValueType.Brightness), clip.videoProperties.getValue(VideoProperties.ValueType.Temperature)
        ), EasingType.NONE));
    }
    public void addKeyframe(Clip clip, Keyframe keyframe)
    {
        // Clamp keyframe in local time.
        if(!keyframe.isWithinClip(clip)) return;
        // Prevent duplication
        if(keyframe.isKeyframeExist(clip)) return;
        addKeyframeUi(clip, keyframe);

        clip.keyframes.keyframes.add(keyframe);

        clip.keyframes.sortKeyframe();
    }
    public void addKeyframeUi(Clip clip, Keyframe keyframe)
    {
        View knotView = new View(this);
        knotView.setBackgroundColor(Color.WHITE);
        knotView.setRotation(45f);
        knotView.setVisibility(this.selectedClip == clip ? View.VISIBLE : View.GONE);

        knotView.setTag(R.id.keyframe_knot_tag, keyframe);
        knotView.setTag(R.id.clip_knot_tag, clip);
        
        int width = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, getResources().getDisplayMetrics());
        int height = width;

        knotView.setPivotX((float) width /2);
        knotView.setPivotY((float) height /2);

        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(width, height);
        params.topMargin = (clip.viewRef.getHeight() / 2) - (height / 2);
        knotView.setX((keyframe.getLocalTime() * pixelsPerSecond) - (width / 2f) + 2); // TODO: Temporary 4 px padding compensation

        clip.viewRef.addView(knotView, params);

        handleKeyframeInteraction(knotView);
    }


    public void removeKeyframe(Clip clip, Keyframe keyframe)
    {
        removeKeyframeUi(clip, keyframe);

        clip.keyframes.keyframes.remove(keyframe);
    }
    public void removeKeyframeUi(Clip clip, Keyframe keyframe)
    {
        for (int i = 0; i < clip.viewRef.getChildCount(); i++) {
            View knotView = clip.viewRef.getChildAt(i);
            if(knotView.getTag(R.id.keyframe_knot_tag) instanceof Keyframe && knotView.getTag(R.id.keyframe_knot_tag) == keyframe)
            {
                clip.viewRef.removeViewAt(i);
                break;
            }
        }
    }

    public void clearKeyframe(Clip clip)
    {
        clearKeyframeUi(clip);

        clip.keyframes.keyframes.clear();
    }
    public void clearKeyframeUi(Clip clip)
    {
        for (int i = 0; i < clip.viewRef.getChildCount(); i++) {
            View knotView = clip.viewRef.getChildAt(i);
            if(knotView.getTag(R.id.keyframe_knot_tag) instanceof Keyframe)
            {
                clip.viewRef.removeView(knotView);
            }
        }
    }
    public void revalidationClipView(Clip data)
    {
        ImageGroupView clipView = data.viewRef;
        clipView.getLayoutParams().width = (int) (data.duration * pixelsPerSecond);


    }
    private void handleEditZoneInteraction(View view) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction())
            {
                case MotionEvent.ACTION_MOVE:
                    if(isPlaying)
                        stopPlayback(true);
                    break;
                // ACTION_UP is the action that invoke only if we clicked
                // that's mean its invoke if we didn't ACTION_MOVE
                case MotionEvent.ACTION_UP:
                    v.performClick();
                    break;
                // ACTION_CANCEL is when you accidentally click something and
                // drag it somewhere so it doesn't recognize that click anymore
                case MotionEvent.ACTION_CANCEL:
                    break;
            }
            return false;
        });
    }
    private void handleKeyframeInteraction(View view) {
        view.setOnClickListener(v -> {
            if(view.getTag(R.id.keyframe_knot_tag) instanceof Keyframe && view.getTag(R.id.clip_knot_tag) instanceof Clip)
            {
                Keyframe data = (Keyframe) view.getTag(R.id.keyframe_knot_tag);
                Clip data2 = (Clip) view.getTag(R.id.clip_knot_tag);

                setCurrentTime(data.getGlobalTime(data2));
            }

        });
    }
    private void handleKnotInteraction(View view) {
        view.setOnClickListener(v -> {
            if(view.getTag(R.id.transition_knot_tag) instanceof TransitionClip)
            {
                selectingKnot((TransitionClip) view.getTag(R.id.transition_knot_tag));
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


                        if(dragContext.clip != null) {
                            dragContext.clip.toggleHandlesVisibility(false);
                        }

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
                            for (int j = 0; j < timeline.tracks.size(); j++) {
                                Track track = timeline.tracks.get(j);

                                // Only snap in neighbors track, user can choose to snap to all track
                                // default is false (only neighbors)
                                // if user set to snap all track, remove these 2 lines.
                                int currentTrackIndex = timeline.tracks.indexOf(dragContext.currentTrack);
                                if (!(j >= currentTrackIndex - 1 && j <= currentTrackIndex + 1)) continue;

                                for (int i = 0; i < track.viewRef.getChildCount(); i++) {

                                    View other = track.viewRef.getChildAt(i);
                                    if (other == dragContext.ghost || other == v) continue;
                                    if (!(other.getTag() instanceof Clip)) continue;


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
//                                    if (Math.abs(ghostStart - otherStart) <= Constants.TRACK_CLIPS_SNAP_THRESHOLD_PIXEL) {
//                                        newX = otherStart;
//                                        break;
//                                    }
//                                    if (Math.abs(ghostEnd - otherEnd) <= Constants.TRACK_CLIPS_SNAP_THRESHOLD_PIXEL) {
//                                        newX = otherEnd - ghostWidth;
//                                        break;
//                                    }
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

                        if(dragContext.clip != null) {
                            if(selectedClip == dragContext.clip)
                                dragContext.clip.toggleHandlesVisibility(true);
                            dragContext.clip.resetTransitionKnotPosition();
                        }

                        if (dragContext.ghost != null) {
                            float finalX = dragContext.ghost.getX();
                            if (finalX < centerOffset) finalX = centerOffset; // ⛔ Clamp again for safety


                            float finalX1 = finalX;

                            v.post(() -> {
                                // Move original to new track and position
                                ViewGroup oldParent = (ViewGroup) v.getParent();
                                oldParent.removeView(v);
//                                oldParent.removeView(dragContext.clip.leftHandle);
//                                oldParent.removeView(dragContext.clip.rightHandle);

                                // Add to new track
                                dragContext.currentTrack.viewRef.addView(v);
//                                dragContext.currentTrack.viewRef.addView(dragContext.clip.leftHandle);
//                                dragContext.currentTrack.viewRef.addView(dragContext.clip.rightHandle);
                                dragContext.clip.forceAddHandlesToTrack(dragContext.currentTrack.viewRef);

                                v.setX(finalX1);
                                v.setVisibility(View.VISIBLE);

                                // Update metadata
                                float newStartTime = (finalX1 - centerOffset) / pixelsPerSecond;
                                dragContext.clip.startTime = Math.max(0, newStartTime); // Clamp to 0
                                timeline.tracks.get(dragContext.clip.trackIndex).removeClip(dragContext.clip);
                                dragContext.clip.trackIndex = dragContext.currentTrack.timelineIndex;
                                timeline.tracks.get(dragContext.clip.trackIndex).addClip((dragContext.clip));


                                updateCurrentClipEnd();
                                dragContext.clip.resetHandlesPosition();


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

                    // For keyframe syncing
                    if(child instanceof ImageGroupView)
                    {
                        ImageGroupView clipViewRef = ((ImageGroupView) child);
                        for (int j = 0; j < clipViewRef.getChildCount(); j++) {
                            View child1 = clipViewRef.getChildAt(j);
                            if(child1.getTag(R.id.keyframe_knot_tag) instanceof Keyframe) {
                                child1.setX(((Keyframe) child1.getTag(R.id.keyframe_knot_tag)).getLocalTime() * pixelsPerSecond);
                            }
                        }
                    }
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
        // Check for snapped track
        for (int i = 1; i < track.clips.size(); i++) {
            Clip at = track.clips.get(i - 1);
            Clip other = track.clips.get(i);
            // Snap ghost start to other end
            if (Math.abs(at.startTime - (other.startTime + other.duration)) <= Constants.TRACK_CLIPS_SNAP_THRESHOLD_SECONDS) {
                addTransitionBridge(other, at, 0.2f);
            }
            else {
                other.setEndTransitionEnabled(false);
            }
            // Snap ghost end to other start
            if (Math.abs((at.startTime + at.duration) - other.startTime) <= Constants.TRACK_CLIPS_SNAP_THRESHOLD_SECONDS) {
                addTransitionBridge(at, other, 0.2f);
            }
            else {
                at.setEndTransitionEnabled(false);
            }
        }
        // Fallback if track only has 1 clip element
        if(track.clips.size() == 1) {
            Clip other = track.clips.get(0);
            other.setEndTransitionEnabled(false);
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
        // TimelineRulerView draws everything via Canvas — just update its parameters.
        rulerContainer.setStartOffset(centerOffset);
        rulerContainer.setFps(settings.frameRate);
        rulerContainer.setPixelsPerSecond(pixelsPerSecond);
        rulerContainer.setRulerInterval(interval);          // drives tick density
//        rulerContainer.setTotalDuration(totalSeconds + 2f); // +2s buffer
        rulerContainer.setTotalDuration(totalSeconds); // no buffer, no infinite ruler trick
    }
    float currentRulerInterval = 1f;
    float changedRulerInterval = 1f;

    private void updateRulerEfficiently() {
        // All drawing is handled by TimelineRulerView.onDraw() — just push the new pps.
        rulerContainer.setPixelsPerSecond(pixelsPerSecond);

        // Determine the best major-tick interval for the current zoom.
        if(pixelsPerSecond < 10  && pixelsPerSecond > 5)    { changedRulerInterval = 32f;    }
        if(pixelsPerSecond < 15  && pixelsPerSecond > 10)   { changedRulerInterval = 16f;    }
        if(pixelsPerSecond < 25  && pixelsPerSecond > 15)   { changedRulerInterval = 8f;     }
        if(pixelsPerSecond < 50  && pixelsPerSecond > 25)   { changedRulerInterval = 4f;     }
        if(pixelsPerSecond < 100 && pixelsPerSecond > 50)   { changedRulerInterval = 2f;     }
        if(pixelsPerSecond < 200 && pixelsPerSecond > 100)  { changedRulerInterval = 1f;     }
        if(pixelsPerSecond < 500 && pixelsPerSecond > 200)  { changedRulerInterval = 0.5f;   }
        if(pixelsPerSecond < 1000 && pixelsPerSecond > 500) { changedRulerInterval = 0.2f;   }
        if(pixelsPerSecond < 2000 && pixelsPerSecond > 1000){ changedRulerInterval = 0.1f;   }
        if(pixelsPerSecond < 5000 && pixelsPerSecond > 2000){ changedRulerInterval = 0.05f;  }
        if(pixelsPerSecond < 10000 && pixelsPerSecond > 5000) { changedRulerInterval = 0.02f; }
        if(pixelsPerSecond < 20000 && pixelsPerSecond > 10000){ changedRulerInterval = 0.01f; }
        if(pixelsPerSecond < 50000 && pixelsPerSecond > 20000){ changedRulerInterval = 0.005f;}

        // Push the interval live so the ruler re-draws immediately during pinch-zoom.
        rulerContainer.setRulerInterval(changedRulerInterval);
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
                if(clip.getTag(R.id.transition_knot_tag) instanceof TransitionClip) {
                    TransitionClip data = (TransitionClip) clip.getTag(R.id.transition_knot_tag);
                    Clip data2 = (Clip) clip.getTag(R.id.clip_knot_tag);

                    clip.setX(getTimeInX(data2.duration) - (clip.getWidth() / 2));
                    //clip.setX(getTimeInX(data.time));
                }
                if(clip.getTag(R.id.keyframe_knot_tag) instanceof Keyframe) {
                    Keyframe data = (Keyframe) clip.getTag(R.id.keyframe_knot_tag);
                    Clip data2 = (Clip) clip.getTag(R.id.clip_knot_tag);

                    clip.setX(getTimeInX(data.getGlobalTime(data2)));
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
            // If null then define new transition, else keep the transition from the clip.
            if(clipA.endTransition == null)
            {
                clipA.endTransition = new TransitionClip(clipA, clipB, transitionDuration);
            }

            if(clipA.transitionKnotViewRef.getParent() instanceof TrackFrameLayout && clipA.viewRef.getParent() instanceof TrackFrameLayout)
                if(clipA.transitionKnotViewRef.getParent() != clipA.viewRef.getParent()) {
                    clipA.forceAddTransitionKnotToTrack((TrackFrameLayout) clipA.viewRef.getParent());
                }
            clipA.setEndTransitionEnabled(true);

            //addTransitionBridgeUi(clipA.endTransition, clipA);
        }

    }
//    private void addTransitionBridgeUi(TransitionClip transitionClip, Clip clip)
//    {
//        addKnotTransition(transitionClip, clip);
//    }





    private void selectingClip(Clip selectedClip)
    {
        if(getClipSelectMultiple())
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
                toolbarClip.setVisibility(View.GONE);
                toolbarClips.setVisibility(View.VISIBLE);
            }
        }
        else {
            deselectingClip();

            if(this.selectedClip != null && this.selectedClip == selectedClip){
                this.selectedClip = null;
            }
            else {
                Track clipTrack = timeline.getTrackFromClip(selectedClip);
                if(this.selectedTrack == null || this.selectedTrack != clipTrack)
                    selectingTrack(clipTrack);

                selectedClip.select();
                this.selectedClip = selectedClip;
                toolbarClip.setVisibility(View.VISIBLE);
                toolbarClips.setVisibility(View.GONE);

                // TODO: Still reappear when interacting other the second time
                // Hide all transition knots cleanly to declutter when focusing a clip
                for (Track track : timeline.tracks) {
                    for (Clip clip : track.clips) {
                        if (clip.transitionKnotViewRef != null) {
                            clip.transitionKnotViewRef.setVisibility(View.GONE);
                        }
                    }
                }
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
            toolbarTrack.setVisibility(View.VISIBLE);
        }
    }
    private void selectingKnot(TransitionClip selectedKnot)
    {
        this.selectedKnot = selectedKnot;
    }
    public void deselectingClip()
    {
        toolbarClip.setVisibility(View.GONE);
        toolbarClips.setVisibility(View.GONE);
//        if(getClipSelectMultiple())
        selectedClips.clear();

        for (Track track : timeline.tracks) {
            for (Clip clip : track.clips) {
                clip.deselect();
                if (clip.transitionKnotViewRef != null) {
                    clip.toggleTransitionKnotVisibility(clip.endTransitionEnabled);
                }
            }
        }
    }
    private void deselectingTrack()
    {
        toolbarTrack.setVisibility(View.GONE);
        for (Track track : timeline.tracks) {
            track.deselect();
        }
    }




    private void refreshPreviewClip()
    {
        //FFmpegEdit.generatePreviewVideo(this, timeline, settings, properties, this::reloadPreviewClip);
    }


    // Native Android user only
    private static List<Bitmap> extractThumbnail(Context context, String filePath, Clip clip)
    {
        return extractThumbnail(context, filePath, clip, -1);
    }
    private static List<Bitmap> extractThumbnail(Context context, String filePath, Clip clip, int frameCountOverride)
    {
        List<Bitmap> thumbnails = new ArrayList<>();
        if(clip.type == null)
            clip.type = ClipType.EFFECT;
        Drawable drawable = ResourcesCompat.getDrawable(context.getResources(), R.drawable.ic_launcher_background, null);
        switch (clip.type)
        {
            case VIDEO:
                try {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(filePath);

                    long durationMs = (long) (clip.duration * 1000);
                    int frameCount = frameCountOverride;
                    // Every 1s will have a thumbnail
                    // Math.ceil will make sure 0.1 will be 1, and clamp to 1 using Math.max to make sure not 0 or below
                    // TODO: Convert it to other thread in order to prevent lag
                    if(frameCountOverride == -1) frameCount = (int) Math.max(1, Math.ceil((double) durationMs / 1000));
                    int originalWidth = Integer.parseInt(Objects.requireNonNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)));
                    int originalHeight = Integer.parseInt(Objects.requireNonNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)));

                    int desiredWidth = originalWidth / Constants.SAMPLE_SIZE_PREVIEW_CLIP;
                    int desiredHeight = originalHeight / Constants.SAMPLE_SIZE_PREVIEW_CLIP;

                    for (int i = 0; i < frameCount; i++) {
                        long timeUs = (long) (clip.startClipTrim * 1_000_000) +  ((durationMs * 1000L * i) / frameCount);
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
                    drawable.setColorFilter(0x33FF0000, PorterDuff.Mode.SRC_ATOP);
                    thumbnails.add(ImageHelper.createBitmapFromDrawable(drawable));
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
                try {
                    thumbnails.add(AudioUtils.generateAudioWaveformBitmap(filePath, clip, pixelsPerSecond, TRACK_HEIGHT, thumbnailAudioBarWidth, thumbnailAudioBarGap));
                } catch (Exception e) {
                    // Fallback to tinted placeholder on decode failure
                    drawable.setColorFilter(0xAA1565C0, android.graphics.PorterDuff.Mode.SRC_ATOP);
                    thumbnails.add(ImageHelper.createBitmapFromDrawable(drawable));
                }
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




    public static int previewToRenderConversionX(float previewX, float renderResolutionX)
    {
        return (int) previewX;
    }
    public static int previewToRenderConversionY(float previewY, float renderResolutionY)
    {
        return (int) previewY;
    }

    public static int renderToPreviewConversionX(float renderX, float renderResolutionX)
    {
        return (int) renderX;
    }
    public static int renderToPreviewConversionY(float renderY, float renderResolutionY)
    {
        return (int) renderY;
    }

    // TODO: Using the same ratio system like below because multiplication and division is in the same order, no plus and subtract
    //  the matrix of the preview clip are not using the previewAvailable ratio system yet, so 1366 width
    //  in the 1080px screen the movement will be jittered

    public static float previewToRenderConversionScalingX(float clipScaleX, float renderResolutionX)
    {
        return clipScaleX;
    }
    public static float previewToRenderConversionScalingY(float clipScaleY, float renderResolutionY)
    {
        return clipScaleY;
    }

    public static float renderToPreviewConversionScalingX(float clipScaleX, float renderResolutionX)
    {
        return clipScaleX;
    }
    public static float renderToPreviewConversionScalingY(float clipScaleY, float renderResolutionY)
    {
        return clipScaleY;
    }

    public static float getRenderRatio(float previewAvailable, float renderResolution)
    {
        return 1f;
    }

    // TODO: For the scaling. When passing the previewAvailableWidth/Height. We get
    //  the previewAvailable / renderResolution for the ratio. And we divide the render scale by the ratio to
    //  get the preview












    public static class Timeline implements Serializable {
        @Expose
        public List<Track> tracks = new ArrayList<>();
        @Expose
        public float duration;

        public void addTrack(Track track) {
            track.timelineIndex = tracks.size();
            tracks.add(track);
        }

        public void removeTrack(Track track) {
            tracks.remove(track);
            reloadTrackIndex();
        }

        public void reloadTrackIndex() {
            for (int i = 0; i < tracks.size(); i++) {
                tracks.get(i).timelineIndex = i;
                for (Clip clip : tracks.get(i).clips) {
                    clip.trackIndex = i;
                }
            }
        }
        /**
         * Reassign keyframe into the current timeline in respect of framePerSecond
         * @param framePerSecond
         */
        public void reassignClips(int framePerSecond) {
            if (framePerSecond <= 0) return;

            for (Track track : tracks) {
                track.reassignClips(framePerSecond);
            }
        }
        public void moveTrackUp(Context context, Track track) {
            if(track != null)
            {
                if(track.timelineIndex > 0)
                {
                    Track upperTrack = tracks.get(track.timelineIndex - 1);
                    tracks.set(track.timelineIndex - 1, track);
                    tracks.set(track.timelineIndex, upperTrack);

                    ViewParent vf = track.viewRef.getParent();
                    if(vf instanceof ViewGroup)
                    {
                        ((ViewGroup) vf).removeView(track.viewRef);
                        ((ViewGroup) vf).addView(track.viewRef, track.timelineIndex - 1);
                    }

                    reloadTrackIndex();
                }
                else {
                    LoggingManager.LogToToast(context, "Track already on top");
                }
            }
        }
        public void moveTrackDown(Context context, Track track) {
            if(track != null)
            {
                if(track.timelineIndex < tracks.size() - 1)
                {
                    Track lowerTrack = tracks.get(track.timelineIndex + 1);
                    tracks.set(track.timelineIndex + 1, track);
                    tracks.set(track.timelineIndex, lowerTrack);

                    ViewParent vf = track.viewRef.getParent();
                    if(vf instanceof ViewGroup)
                    {
                        ((ViewGroup) vf).removeView(track.viewRef);
                        ((ViewGroup) vf).addView(track.viewRef, track.timelineIndex + 1);
                    }

                    reloadTrackIndex();
                }
                else {
                    LoggingManager.LogToToast(context, "Track already on bottom");
                }
            }
        }

        public List<Clip> getClipsAtCurrentTime(float playheadTime) {
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

        public void recalculateDuration() {
            float max = 0f;
            for (Track track : tracks) {
                float endTime = track.getTrackEndTime();
                if (endTime > max) {
                    max = endTime;
                }
                track.sortClips();
            }
            duration = max;
        }
        public Track getTrackFromClip(Clip selectedClip) {
            for (Track track : tracks) {
                if(track.clips.contains(selectedClip))
                    return track;
            }
            return null;
        }



        public static void saveTimeline(Context context, Timeline timeline, MainAreaScreen.ProjectData data, VideoSettings settings)
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
                IOImageHelper.SaveFileAsPNGImage(context, IOHelper.CombinePath(data.getProjectPath(), "preview.png"), extractThumbnail(context, nearestClip.getAbsolutePreviewPath(data, Constants.DEFAULT_PREVIEW_CLIP_VIDEO_EXTENSION), nearestClip, 1).get(0), 25);

            data.setProjectTimestamp(new Date().getTime());
            data.setProjectDuration((long) (timeline.duration * 1000));
            data.setProjectSize(IOHelper.getFileSize(context, data.getProjectPath()));


            String jsonTimeline = GsonHelper.createExposeOnlyGson().toJson(timeline); // Save


            data.savePropertiesAtProject(context);
            settings.saveSettings(context, data);
            IOHelper.writeToFile(context, IOHelper.CombinePath(data.getProjectPath(), Constants.DEFAULT_TIMELINE_FILENAME), jsonTimeline);


        }
        public static Timeline loadRawTimeline(Context context, MainAreaScreen.ProjectData data)
        {
            String json = IOHelper.readFromFile(context, IOHelper.CombinePath(data.getProjectPath(), Constants.DEFAULT_TIMELINE_FILENAME));
            return new Gson().fromJson(json, Timeline.class);
        }
        public static Timeline loadTimeline(Context context, EditingActivity instance, MainAreaScreen.ProjectData data)
        {
            return loadTimeline(context, instance, loadRawTimeline(context, data));
        }
        public static Timeline loadTimeline(Context context, EditingActivity instance, Timeline timeline)
        {
            if(timeline == null) return new Timeline();
            for (Track track : timeline.tracks) {
                instance.loadExistingTrack(track);
            }
            timeline.reloadTrackIndex();


            return timeline;
        }

        public int getAllClipCount() {
            int clipCount = 0;
            for (EditingActivity.Track track : tracks) {
                clipCount += track.clips.size();
            }
            return clipCount;
        }
        public Clip[] getStreamOfClip() {
            Clip[] clips = new Clip[getAllClipCount()];
            int i = 0;
            for (EditingActivity.Track track : tracks) {
                for (Clip clip : track.clips) {
                    clips[i] = clip;
                    i++;
                }
            }
            return clips;
        }

        public int getAllReplacementClipCount() {
            return getAllClipCount() - getLockedForTemplateClip().length;
        }

        public Clip[] getLockedForTemplateClip() {
            List<Clip> clips = new ArrayList<>();
            for (EditingActivity.Clip clip : getStreamOfClip()) {
                if(clip.isLockedForTemplate())
                {
                    clips.add(clip);
                }
            }

            return clips.toArray(new Clip[0]);
        }
    }

    public static class Track implements Serializable {
        @Expose
        public int timelineIndex;
        @Expose
        public List<Clip> clips = new ArrayList<>();

        //Not serializing
        public transient TrackFrameLayout viewRef;



        public Track(TrackFrameLayout viewRef) {
            this.viewRef = viewRef;
        }
        public Track() {
        }

        public void addClip(Clip clip) {
            clip.trackIndex = timelineIndex;
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

        public void reassignClips(int framePerSecond) {
            if (framePerSecond <= 0) return;

            for (Clip clip : clips) {
                double currentTime = clip.getStartTime();

                // 1. Find which frame index we are closest to
                // Example: 3.14 * 30 = 94.2. Round(94.2) = 94.
                long closestFrameIndex = Math.round(currentTime * framePerSecond);

                // 2. Convert that frame index back into seconds
                // Example: 94 / 30.0 = 3.1333...
                double snappedTime = (double) closestFrameIndex / framePerSecond;

                clip.setStartTime((float) snappedTime);
            }
        }
        public List<Clip> getClipsAtCurrentTime(float playheadTime) {
            List<Clip> clipsSelected = new ArrayList<>();
            for (Clip clip : clips) {
                if (playheadTime >= clip.startTime && playheadTime < clip.startTime + clip.duration) {
                    clipsSelected.add(clip);
                }
            }
            return clipsSelected; // No clip at this time
        }

        public void select() {
            viewRef.setBackgroundColor(0xFFAAAAAA);
        }
        public void deselect() {
            viewRef.setBackgroundColor(0xFF222222);
        }

        public void clearTransition() {
            for (Clip clip : clips) {
                clip.endTransition = null;
            }
            removeTransitionUi();
        }
        public void disableTransitions() {
            for (Clip clip : clips) {
                clip.setEndTransitionEnabled(false);
            }
            removeTransitionUi();
        }

        public void removeTransitionUi() {

            for (int i = 0; i < viewRef.getChildCount(); i++) {
                View targetView = viewRef.getChildAt(i);

                if (targetView != null && targetView.getTag(R.id.transition_knot_tag) != null) {
                    viewRef.removeView(targetView);
                    break;
                }
            }
        }



        public void delete(Timeline timeline, ViewGroup trackContainer, ViewGroup trackInfo, EditingActivity activity) {
            activity.deselectingTrack();

            timeline.removeTrack(timeline.tracks.get(timelineIndex));

            // Lower the higher indexes track by 1 to fill up the remove one.
            // When removed, trackIndex element become the next element
            List<Track> tracks = timeline.tracks;
            for (int i = timelineIndex; i < tracks.size(); i++) {
                Track higherTrack = tracks.get(i);
                higherTrack.timelineIndex--;
                // Change trackIndex for Clip as well
                for (Clip clip : higherTrack.clips) {
                    clip.trackIndex = higherTrack.timelineIndex;
                }
            }

            trackContainer.removeView(viewRef);

            // Since the track #n is following the pattern, we just need to delete the last track # text and it does the job
            // -1 for the count to index, like count is 4 but index should be 3
            // -1 for the index for the button
            trackInfo.removeView(trackInfo.getChildAt(trackInfo.getChildCount() - 2));

            // Decrease the trackIndex from the global scope
            activity.trackCount--;
        }

    }
        public static class AnimationClip implements Serializable {
            @Expose
            public String type;
            @Expose
            public float duration;

            public AnimationClip(String type, float duration) {
                this.type = type;
                this.duration = duration;
            }

            public AnimationClip(AnimationClip other) {
                if (other != null) {
                    this.type = other.type;
                    this.duration = other.duration;
                } else {
                    this.type = "none";
                    this.duration = 0.5f;
                }
            }
        }

    public static class Clip implements Serializable {
        public static final int ELEVATION_HANDLERS = 2;
        public static final int ELEVATION_TRANSITION_KNOT = 1;


        @Expose
        public ClipType type;
        @Expose
        private String clipName;
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
        public int width;
        @Expose
        public int height;

        @Expose
        public String additionalFFmpegCommand;



        @Expose
        public VideoProperties videoProperties;


        @Expose
        public AnimatedProperty keyframes = new AnimatedProperty();

        // FX support (for EFFECT type)
        @Expose
        public EffectTemplate effect; // for EFFECT type
        @Expose
        public String textContent;    // for TEXT type
        @Expose
        public float fontSize;    // for TEXT type
        @Expose
        public String sceneConfig; // for SCENE_3D type
        @Expose
        public String textureClipName; // for SCENE_3D type


        // TODO: End transition for clip later that attached to the end of this clip.
        //  this will make more sense than begin Transition as no one would merge the beginning of the clip and call it a transition.
        //  Save this endTransition alongside with this clip.
        @Expose
        public TransitionClip endTransition = null;
        @Expose
        public boolean endTransitionEnabled = false;

        /**
         * For VIDEO type.
         * When import, check whether the clip has audio or not
         * When export, decide to include audio stream or not to prevent "match no stream" ffmpeg error.
         */
        @Expose
        public boolean isClipHasAudio;    // for VIDEO type

        /**
         * For VIDEO type.
         * Can use this to mute video when export
         */
        @Expose
        public boolean isMute;    // for VIDEO type
        @Expose
        public boolean isLockedForTemplate;    // for VIDEO type
        @Expose
        public boolean isReverse;    // for VIDEO type
        @Expose
        public boolean removeBackground;

        @Expose
        public AnimationClip inAnimation = null;
        @Expose
        public AnimationClip outAnimation = null;
        @Expose
        public AnimationClip comboAnimation = null;


        //Not serializing
        public transient View leftHandle, rightHandle;
        public transient ImageView transitionKnotViewRef;
        public transient ImageGroupView viewRef;
        public transient LinearLayout clipPropertiesLinearLayoutGroup;
        public transient ImageView templateLockViewRef, noSoundViewRef, muteViewRef, reverseViewRef, customCommandViewRef;
        public transient HorizontalScrollView timelineScrollViewRef;
        public transient TextView durationText;


        public Clip(String clipName, float startTime, float duration, int trackIndex, ClipType type, boolean isClipHasAudio, int width, int height) {
            this.clipName = clipName;
            this.startTime = startTime;
            this.startClipTrim = 0;
            this.endClipTrim = 0;
            this.duration = duration;
            this.originalDuration = duration;
            this.trackIndex = trackIndex;
            this.type = type;
            this.isClipHasAudio = isClipHasAudio;

            this.width = width;
            this.height = height;

            this.videoProperties = new VideoProperties();
            this.isMute = false;
            this.isReverse = false;
            this.removeBackground = false;

            this.inAnimation = new AnimationClip("none", 0.5f);
            this.outAnimation = new AnimationClip("none", 0.5f);
            this.comboAnimation = new AnimationClip("none", 0.5f);
        }

        public Clip(Clip clip) {
            this.clipName = clip.clipName;
            this.startTime = clip.startTime;
            this.startClipTrim = clip.startClipTrim;
            this.endClipTrim = clip.endClipTrim;
            this.duration = clip.duration;
            this.originalDuration = clip.originalDuration;
            this.trackIndex = clip.trackIndex;
            this.type = clip.type;
            this.isClipHasAudio = clip.isClipHasAudio;

            this.width = clip.width;
            this.height = clip.height;

            this.videoProperties = new VideoProperties(clip.videoProperties);
            this.isMute = clip.isMute;
            this.isReverse = clip.isReverse;
            this.removeBackground = clip.removeBackground;
            this.isLockedForTemplate = clip.isLockedForTemplate;

            this.additionalFFmpegCommand = clip.additionalFFmpegCommand;
            this.endTransition = clip.endTransition;

            this.keyframes = new AnimatedProperty(clip.keyframes);

            this.inAnimation = new AnimationClip(clip.inAnimation);
            this.outAnimation = new AnimationClip(clip.outAnimation);
            this.comboAnimation = new AnimationClip(clip.comboAnimation);


            if(clip.type == ClipType.TEXT)
            {
                this.textContent = clip.textContent;
                this.fontSize = clip.fontSize;
            }
            if(clip.type == ClipType.EFFECT)
            {
                this.effect = clip.effect;
            }
            if(clip.type == ClipType.SCENE_3D)
            {
                this.sceneConfig = clip.sceneConfig;
                this.textureClipName = clip.textureClipName;
            }
        }


        /**
         * Full transfer to this new Clip.
         * Safe enough to filter the null when the new update rolled out.
         * @param loadedClip Clip that loaded from JSON and are potentially contains null variables
         */
        public void safeLoad(Clip loadedClip)
        {
            // ? WTF am I writing this for. Make no sense!
        }


        public void filterNullAfterLoad()
        {
            if(type == null) type = ClipType.VIDEO;
            if(videoProperties == null) videoProperties = new VideoProperties();
            if(keyframes == null) keyframes = new AnimatedProperty();
            if(keyframes.keyframes == null) keyframes.keyframes = new ArrayList<>();
            if(additionalFFmpegCommand == null) additionalFFmpegCommand = "";

            if(inAnimation == null) inAnimation = new AnimationClip("none", 0.5f);
            if(outAnimation == null) outAnimation = new AnimationClip("none", 0.5f);
            if(comboAnimation == null) comboAnimation = new AnimationClip("none", 0.5f);

            if(sceneConfig == null && type == ClipType.SCENE_3D) {
                sceneConfig = "{\"camera\":{\"position\":[0.0,0.0,6.0],\"lookAt\":[0.0,0.0,0.0]},\"objects\":[]}";
            }
        }
        public void resetHandlesPosition()
        {
            leftHandle.setX(viewRef.getX() - 35);
            rightHandle.setX(viewRef.getX() + viewRef.getWidth());
        }
        public void addHandlesToTrack(TrackFrameLayout trackViewRef)
        {
            if(leftHandle.getParent() == null)
                trackViewRef.addView(leftHandle);
            if(rightHandle.getParent() == null)
                trackViewRef.addView(rightHandle);
        }
        public void forceAddHandlesToTrack(TrackFrameLayout trackViewRef)
        {
            if(leftHandle.getParent() != null) ((ViewGroup)leftHandle.getParent()).removeView(leftHandle);
            if(rightHandle.getParent() != null) ((ViewGroup)rightHandle.getParent()).removeView(rightHandle);
            addHandlesToTrack(trackViewRef);
        }
        public void toggleHandlesVisibility(boolean isVisible)
        {
            leftHandle.setVisibility(isVisible ? View.VISIBLE : View.GONE);
            rightHandle.setVisibility(isVisible ? View.VISIBLE : View.GONE);
            if(isVisible)
                resetHandlesPosition();
        }
        public void resetTransitionKnotPosition()
        {
            transitionKnotViewRef.setX(viewRef.getX() + (duration * pixelsPerSecond) - (transitionKnotViewRef.getWidth() / 2f));
        }
        public void addTransitionKnotToTrack(TrackFrameLayout trackViewRef)
        {
            if(transitionKnotViewRef.getParent() == null) {
                trackViewRef.addView(transitionKnotViewRef);
            }
        }
        public void forceAddTransitionKnotToTrack(TrackFrameLayout trackViewRef)
        {
            if(transitionKnotViewRef.getParent() != null) ((ViewGroup)transitionKnotViewRef.getParent()).removeView(transitionKnotViewRef);
            addTransitionKnotToTrack(trackViewRef);
        }
        public void toggleTransitionKnotVisibility(boolean isVisible)
        {
            transitionKnotViewRef.setVisibility(isVisible ? View.VISIBLE : View.GONE);
            if(isVisible)
                resetTransitionKnotPosition();
        }


        public void addKnotTransitionUi(EditingActivity activity, TransitionClip clip, Clip clipA)
        {
            transitionKnotViewRef = new ImageView(activity);
            transitionKnotViewRef.setBackgroundResource(R.drawable.rounded_rectangle_4px); //circle_background
            transitionKnotViewRef.setBackgroundTintList(android.content.res.ColorStateList.valueOf(activity.getColor(R.color.ios_blue)));
            transitionKnotViewRef.setElevation(ELEVATION_TRANSITION_KNOT);

            transitionKnotViewRef.setImageResource(R.drawable.baseline_local_movies_24);
            transitionKnotViewRef.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
            int paddingDip = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, activity.getResources().getDisplayMetrics());
            transitionKnotViewRef.setPadding(paddingDip, paddingDip, paddingDip, paddingDip);

            transitionKnotViewRef.setVisibility(View.VISIBLE);

            transitionKnotViewRef.setTag(R.id.transition_knot_tag, clip);
            transitionKnotViewRef.setTag(R.id.clip_knot_tag, clipA);
            // Position it between clips
            int width = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28, activity.getResources().getDisplayMetrics());
            int height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28, activity.getResources().getDisplayMetrics());

            transitionKnotViewRef.setPivotX((float) width /2);
            transitionKnotViewRef.setPivotY((float) height /2);

            TrackFrameLayout.LayoutParams params = new TrackFrameLayout.LayoutParams(width, height);
            //params.leftMargin = clipB.getLeft() - (width / 2); // center between clips
            params.gravity = Gravity.CENTER_VERTICAL;
            //params.topMargin = clipA.viewRef.getTop() + (clipA.viewRef.getHeight() / 2) - (height / 2);
            transitionKnotViewRef.setLayoutParams(params);
            resetTransitionKnotPosition();
//            addTransitionKnotToTrack(activity.timeline.tracks.get(clip.trackIndex).viewRef);

            activity.handleKnotInteraction(transitionKnotViewRef);
        }

        public void registerClipHandles(ImageGroupView clipView, EditingActivity activity, HorizontalScrollView timelineScroll) {
            int handleWidthDip = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, activity.getResources().getDisplayMetrics());

            leftHandle = new ImageView(clipView.getContext());
            leftHandle.setElevation(ELEVATION_HANDLERS);
            leftHandle.setBackgroundColor(activity.getColor(R.color.ios_blue));
            ((ImageView) leftHandle).setImageResource(R.drawable.baseline_more_vert_24);
            ((ImageView) leftHandle).setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
            ((ImageView) leftHandle).setScaleType(ImageView.ScaleType.CENTER);
            RelativeLayout.LayoutParams leftParams = new RelativeLayout.LayoutParams(handleWidthDip, ViewGroup.LayoutParams.MATCH_PARENT);
            //leftParams.addRule(RelativeLayout.ALIGN_PARENT_START);
            //leftParams.setMarginStart(-35);   for rendering the part outside of clip to match Capcut
            // For beginning initization
            leftHandle.setX(activity.getTimeInX(startTime) - handleWidthDip);
            leftHandle.setLayoutParams(leftParams);

            rightHandle = new ImageView(clipView.getContext());
            rightHandle.setElevation(ELEVATION_HANDLERS);
            rightHandle.setBackgroundColor(activity.getColor(R.color.ios_blue));
            ((ImageView) rightHandle).setImageResource(R.drawable.baseline_more_vert_24);
            ((ImageView) rightHandle).setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
            ((ImageView) rightHandle).setScaleType(ImageView.ScaleType.CENTER);
            RelativeLayout.LayoutParams rightParams = new RelativeLayout.LayoutParams(handleWidthDip, ViewGroup.LayoutParams.MATCH_PARENT);
            //rightParams.addRule(RelativeLayout.ALIGN_PARENT_END);
            //rightParams.setMarginEnd(-35);   for rendering the part outside of clip to match Capcut
            rightHandle.setX(activity.getTimeInX(startTime + duration));
            rightHandle.setLayoutParams(rightParams);
            // No need to reset in the beginning, above code do more accurate before viewRef being scale.
//            resetHandlesPosition();

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

                                    float currentWidthExact = clip.duration * pixelsPerSecond;
                                    
                                    // Clamping only for video and audio as these type has limited duration
                                    if (type == ClipType.VIDEO || type == ClipType.AUDIO) {
                                        deltaX = (Math.min(-deltaX, clip.startClipTrim * pixelsPerSecond));
                                        deltaX = -deltaX;
                                    }
                                    
                                    float proposedNewWidthExact = currentWidthExact - deltaX;

                                    if (type == ClipType.VIDEO || type == ClipType.AUDIO) {
                                        if (proposedNewWidthExact < minWidth) return true;
                                        proposedNewWidthExact = Math.max(minWidth, Math.min(proposedNewWidthExact, maxWidth));
                                    }
                                    
                                    if(proposedNewWidthExact < Constants.TRACK_CLIPS_SHRINK_LIMIT_PIXEL) return true;

                                    // Recalculate true deltaX after clamped width
                                    deltaX = currentWidthExact - proposedNewWidthExact;
                                    float deltaTime = deltaX / pixelsPerSecond;

                                    clip.startTime += deltaTime;
                                    clip.startClipTrim += deltaTime;
                                    clip.setDuration(clip.originalDuration - clip.endClipTrim - clip.startClipTrim);

                                    // In sync with clip metadata perfectly
                                    clipView.getLayoutParams().width = (int) (clip.duration * pixelsPerSecond);
                                    clipView.setX(activity.getTimeInX(clip.startTime));
                                    clipView.requestLayout();

                                    resetHandlesPosition();
                                    break;

                                case MotionEvent.ACTION_UP:
                                case MotionEvent.ACTION_CANCEL:
                                    timelineScroll.requestDisallowInterceptTouchEvent(false);

                                    if(clip.startTime < 0)
                                    {
                                        clipView.setX(activity.getTimeInX(0));
                                        clip.startTime = 0;
                                    }

                                    resetHandlesPosition();

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

                                    float currentWidthExact = clip.duration * pixelsPerSecond;

                                    // Clamping only for video and audio as these type has limited duration
                                    if(type == ClipType.VIDEO || type == ClipType.AUDIO) {
                                        deltaX = Math.min(deltaX, clip.endClipTrim * pixelsPerSecond);
                                    }
                                    
                                    float proposedNewWidthExact = currentWidthExact + deltaX;

                                    if(type == ClipType.VIDEO || type == ClipType.AUDIO) {
                                        if (proposedNewWidthExact < minWidth) return true;
                                        proposedNewWidthExact = Math.max(minWidth, Math.min(proposedNewWidthExact, maxWidth));
                                    }

                                    if(proposedNewWidthExact < Constants.TRACK_CLIPS_SHRINK_LIMIT_PIXEL) return true;

                                    deltaX = proposedNewWidthExact - currentWidthExact;
                                    float deltaTime = deltaX / pixelsPerSecond;

                                    clip.endClipTrim -= deltaTime;
                                    clip.setDuration(clip.originalDuration - clip.endClipTrim - clip.startClipTrim);

                                    // In sync with clip metadata perfectly
                                    clipView.getLayoutParams().width = (int) (clip.duration * pixelsPerSecond);
                                    clipView.requestLayout();

                                    resetHandlesPosition();
                                    break;

                                case MotionEvent.ACTION_UP:
                                case MotionEvent.ACTION_CANCEL:
                                    timelineScroll.requestDisallowInterceptTouchEvent(false);
                                    resetHandlesPosition();
                                    activity.updateCurrentClipEnd();
                                    break;

                            }

                            return true;
                        }
                    }
            );


//            new Handler().postDelayed(() -> {
//                addHandlesToTrack(activity.timeline.getTrackFromClip(this).viewRef);
//            }, 2000);
        }

        public void initClip(ImageGroupView clipView, EditingActivity activity, HorizontalScrollView timelineScroll) {
            viewRef = clipView;
            timelineScrollViewRef = timelineScroll;

            // Dimensions in DP
            int dp20 = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, activity.getResources().getDisplayMetrics());
            int dp5 = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5, activity.getResources().getDisplayMetrics());
            int dp4 = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, activity.getResources().getDisplayMetrics());

            // Group for properties like duration, template lock, effect, etc...
            clipPropertiesLinearLayoutGroup = new LinearLayout(activity);
            ImageGroupView.LayoutParams clipPropertiesLinearLayoutGroupLayoutParams = new ImageGroupView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clipPropertiesLinearLayoutGroupLayoutParams.setMargins(dp5, dp5, 0, 0);
            clipPropertiesLinearLayoutGroup.setLayoutParams(clipPropertiesLinearLayoutGroupLayoutParams);
            clipPropertiesLinearLayoutGroup.setOrientation(LinearLayout.HORIZONTAL);
            clipPropertiesLinearLayoutGroup.setGravity(Gravity.CENTER_VERTICAL);
            viewRef.addView(clipPropertiesLinearLayoutGroup);

            LinearLayout.LayoutParams clipPropertiesLayoutParams = new LinearLayout.LayoutParams(dp20, dp20);
            clipPropertiesLayoutParams.setMargins(0, 0, dp5, 0);

            // Helper to style property icon badges
            java.util.function.Consumer<ImageView> styleBadgeIcon = img -> {
                img.setBackgroundResource(R.drawable.bg_drag_handle); //bg_search_bar
                img.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x88000000));
                img.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
                img.setPadding(dp4, dp4, dp4, dp4);
                img.setLayoutParams(clipPropertiesLayoutParams);
            };

            // Lock display for isLockedForTemplate
            templateLockViewRef = new ImageView(activity);
            styleBadgeIcon.accept(templateLockViewRef);
            templateLockViewRef.setImageResource(R.drawable.baseline_lock_24);
            clipPropertiesLinearLayoutGroup.addView(templateLockViewRef);
            templateLockViewRef.setVisibility(isLockedForTemplate ? View.VISIBLE : View.GONE);

            // No sound display
            noSoundViewRef = new ImageView(activity);
            styleBadgeIcon.accept(noSoundViewRef);
            noSoundViewRef.setImageResource(R.drawable.baseline_music_off_24);
            clipPropertiesLinearLayoutGroup.addView(noSoundViewRef);
            noSoundViewRef.setVisibility(!isClipHasAudio ? View.VISIBLE : View.GONE);

            // Mute display
            muteViewRef = new ImageView(activity);
            styleBadgeIcon.accept(muteViewRef);
            muteViewRef.setImageResource(R.drawable.baseline_volume_off_24);
            clipPropertiesLinearLayoutGroup.addView(muteViewRef);
            muteViewRef.setVisibility(isMute ? View.VISIBLE : View.GONE);

            // Reverse display
            reverseViewRef = new ImageView(activity);
            styleBadgeIcon.accept(reverseViewRef);
            reverseViewRef.setImageResource(R.drawable.baseline_rotate_left_24);
            clipPropertiesLinearLayoutGroup.addView(reverseViewRef);
            reverseViewRef.setVisibility(isReverse ? View.VISIBLE : View.GONE);

            // Custom Command display
            customCommandViewRef = new ImageView(activity);
            styleBadgeIcon.accept(customCommandViewRef);
            customCommandViewRef.setImageResource(R.drawable.baseline_code_24);
            clipPropertiesLinearLayoutGroup.addView(customCommandViewRef);
            customCommandViewRef.setVisibility((additionalFFmpegCommand != null && !additionalFFmpegCommand.isEmpty()) ? View.VISIBLE : View.GONE);

            durationText = new TextView(activity);
            durationText.setBackgroundResource(R.drawable.bg_drag_handle); //bg_search_bar
            durationText.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x88000000));
            durationText.setTextColor(Color.WHITE);
            durationText.setPadding(dp5 * 2, dp4 / 2, dp5 * 2, dp4 / 2);
            durationText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            durationText.setText(StringFormatHelper.smartRound(duration, 2, true) + "s");
            durationText.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams durationLayoutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            durationText.setLayoutParams(durationLayoutParams);
            clipPropertiesLinearLayoutGroup.addView(durationText);

            registerClipHandles(clipView, activity, timelineScroll);
            if(endTransition == null)
                endTransition = new TransitionClip(this);
            addKnotTransitionUi(activity, endTransition, this);


            deselect();
        }

        public void movePropertiesLayoutAlong(int scrollX) {
            clipPropertiesLinearLayoutGroup.post(() -> {
                // Normal position (moves with layout)
                float normalLeft = viewRef.getX(); // If scrolled left past the TextView, pin it to screen
                float normalRight = viewRef.getWidth() + viewRef.getX(); // If scrolled right past the TextView, stop
                if (scrollX > normalLeft) {
                    if(scrollX < normalRight)
                        clipPropertiesLinearLayoutGroup.setTranslationX(scrollX - normalLeft);
                } else {
                    clipPropertiesLinearLayoutGroup.setTranslationX(0); // reset
                }
            });
        }

        public void select() {
            // TODO: Outer border of Clip when selected. Failed.
            // viewRef.getFilledImageView().setColorFilter(0x77AAAAAA);
            GradientDrawable border = new GradientDrawable();
            border.setColor(Color.TRANSPARENT);
            int strokeWidth = (int) (2 * viewRef.getContext().getResources().getDisplayMetrics().density);
            border.setStroke(strokeWidth, viewRef.getContext().getColor(R.color.ios_blue));
            
            // Use negative inset to push the border outwards
            android.graphics.drawable.InsetDrawable outwardBorder = new android.graphics.drawable.InsetDrawable(
                border, -strokeWidth, -strokeWidth, -strokeWidth, -strokeWidth);
            viewRef.setBackground(outwardBorder);

            // If handles isn't being added yet
            if(viewRef.getParent() instanceof TrackFrameLayout) {
                ((TrackFrameLayout) viewRef.getParent()).setClipChildren(false);
                addHandlesToTrack(((TrackFrameLayout) viewRef.getParent()));
                addTransitionKnotToTrack(((TrackFrameLayout) viewRef.getParent()));
            }

            toggleHandlesVisibility(true);
            clipPropertiesLinearLayoutGroup.setVisibility(View.VISIBLE);
            
            // Show clip's own keyframes
            for (int i = 0; i < viewRef.getChildCount(); i++) {
                View child = viewRef.getChildAt(i);
                if (child.getTag(R.id.keyframe_knot_tag) != null) {
                    child.setVisibility(View.VISIBLE);
                }
            }

            // Starting point when selecting clip
            if (timelineScrollViewRef != null)
                movePropertiesLayoutAlong(timelineScrollViewRef.getScrollX());
        }

        public void deselect() {
            // viewRef.getFilledImageView().setColorFilter(0x00000000);
            viewRef.setBackgroundColor(0xFF000000);

            toggleHandlesVisibility(false);
            clipPropertiesLinearLayoutGroup.setVisibility(View.GONE);
            
            // Hide clip's own keyframes
            for (int i = 0; i < viewRef.getChildCount(); i++) {
                View child = viewRef.getChildAt(i);
                if (child.getTag(R.id.keyframe_knot_tag) != null) {
                    child.setVisibility(View.GONE);
                }
            }
        }



        public void deleteClip(Timeline timeline, EditingActivity activity)
        {
            activity.deselectingClip();

            Track currentTrack = timeline.tracks.get(trackIndex);

            currentTrack.removeClip(this);
            currentTrack.viewRef.removeView(viewRef);
        }

        public void splitClip(EditingActivity activity, Timeline timeline, float currentGlobalTime)
        {
            Track currentTrack = timeline.tracks.get(trackIndex);

            float translatedLocalCurrentTime = getLocalClipTime(currentGlobalTime);

            Clip secondaryClip = new Clip(this);

            secondaryClip.startTime = currentGlobalTime;

            float oldEndClipTrim = endClipTrim;
            float oldStartClipTrim = startClipTrim;

            // Primary clip
            endClipTrim = originalDuration - (translatedLocalCurrentTime + oldStartClipTrim);
            setDuration(originalDuration - endClipTrim - startClipTrim);

            // Secondary clip
            secondaryClip.startClipTrim = translatedLocalCurrentTime + oldStartClipTrim;
            secondaryClip.endClipTrim = oldEndClipTrim;
            secondaryClip.setDuration(secondaryClip.originalDuration - secondaryClip.endClipTrim - secondaryClip.startClipTrim);

            activity.addClipToTrack(currentTrack, secondaryClip);
            activity.revalidationClipView(this);
        }
        public void restate() {
            videoProperties = new VideoProperties();
        }

        public void mergingVideoPropertiesFromSingleKeyframe() {
            if(hasOnlyOneAnimatedProperties())
            {
                VideoProperties videoProperties = keyframes.keyframes.get(0).value;

                applyPropertiesToClip(videoProperties);
            }
        }
        public void applyPropertiesToClip(VideoProperties properties) {
            this.videoProperties = new VideoProperties(properties);
        }
        public boolean isClipTransitionAvailable()
        {
            return isEndTransitionEnabled() && endTransition != null;
        }




        /**
         * To ensure rendering keyframe correctly, there must have more than 2 keyframe to form a line for
         * lerping back and forth.
         * 2 points create 1 line, 3 points create 2 lines, 4 points create 3 lines, etc...
         * @return true if keyframe list has more than 2 element, false otherwise.
         */
        public boolean hasAnimatedProperties() {
            return keyframes.keyframes.size() > 1;
        }

        /**
         * If there's not enough keyframe to render, then we just merging the video properties to
         * the main video.
         * @return true if keyframe list has only 1 element, false otherwise.
         */
        public boolean hasOnlyOneAnimatedProperties() {
            return keyframes.keyframes.size() == 1;
        }


        /**
         * Used for FFmpeg and other output that requires original quality.
         *
         * @param properties The Project Data.
         * @return The path for original file.
         */
        public String getAbsolutePath(MainAreaScreen.ProjectData properties) {
            return getAbsolutePath(properties.getProjectPath());
        }
        // TODO: Mark the clip to NO_MEDIA if it return the non-existence pathname, maybe the file was deleted, corrupted.
        //  If possible, consent to user to re-import the lost file and merge it with the new one.
        public String getAbsolutePath(String projectPath) {
            return IOHelper.CombinePath(projectPath, Constants.DEFAULT_CLIP_DIRECTORY, getClipName());
        }
        /**
         * Used for EditingActivity in which didn't need high quality video. Fit for real-time preview.
         *
         * @param properties The Project Data.
         * @return The path for preview file.
         */
//        public String getAbsolutePreviewPath(MainAreaScreen.ProjectData properties) {
//            return getAbsolutePreviewPath(properties.getProjectPath());
//        }
//        public String getAbsolutePreviewPath(String projectPath) {
//            String path = IOHelper.CombinePath(projectPath, Constants.DEFAULT_PREVIEW_CLIP_DIRECTORY, getClipName());
//            if (removeBackground) {
//                String cutoutPath = getCutoutPath(projectPath);
//                if (IOHelper.isFileExist(cutoutPath)) {
//                    path = cutoutPath;
//                }
//            }
//            // Fallback if not available yet.
//            // TODO: Temporary fix for the soon preview loading. Consider block main thread for preview to have time to load first
//            if(!IOHelper.isFileExist(path))
//                path = getAbsolutePath(projectPath);
//            return path;
//        }

        public String getCutoutPath(MainAreaScreen.ProjectData properties) {
            return getCutoutPath(properties.getProjectPath());
        }

        public String getCutoutPath(String projectPath) {
            String extension = ".png"; // Default for images
            if (type == ClipType.VIDEO) {
                extension = ".mp4"; // Mask video
            }
            String baseName = getClipName();
            int lastDot = baseName.lastIndexOf('.');
            if (lastDot > 0) {
                baseName = baseName.substring(0, lastDot);
            }
            return IOHelper.CombinePath(projectPath, Constants.DEFAULT_CUTOUT_DIRECTORY, baseName + "_cutout" + extension);
        }
        /**
         * Used for EditingActivity in which didn't need high quality video. Fit for real-time preview.
         *
         * @param properties The Project Data.
         * @return The path for preview file.
         */
        public String getAbsolutePreviewPath(MainAreaScreen.ProjectData properties, String previewExtension) {
            return getAbsolutePreviewPath(properties.getProjectPath(), previewExtension);
        }
        // TODO: Raise a Popup consent to user to regenerate Preview media if not available.
        //  before that, check if it was VIDEO or AUDIO type, IMAGE and other don't need preview.
        public String getAbsolutePreviewPath(String projectPath, String previewExtension) {
            String path = IOHelper.CombinePath(projectPath, Constants.DEFAULT_PREVIEW_CLIP_DIRECTORY, getClipName().substring(0, getClipName().lastIndexOf('.')) + previewExtension);
            // Fallback if not available yet.
            // TODO: Temporary fix for the soon preview loading. Consider block main thread for preview to have time to load first
            if(!IOHelper.isFileExist(path))
                path = getAbsolutePath(projectPath);
            return path;
        }
        public float getLocalClipTime(float playheadTime) {
            return playheadTime - startTime;
        }

        public float getTrimmedLocalTime(float localClipTime) {
            return localClipTime + startClipTrim;
        }

        public float getCutoutDuration() {
            return duration - startClipTrim - endClipTrim;
        }


        public boolean isEndTransitionEnabled() {
            return endTransitionEnabled;
        }

        public void setEndTransitionEnabled(boolean endTransitionEnabled) {
            this.endTransitionEnabled = endTransitionEnabled;
            toggleTransitionKnotVisibility(endTransitionEnabled);
        }

        public boolean isLockedForTemplate()
        {
            return isLockedForTemplate;
        }
        public void setLockedForTemplate(boolean value)
        {
            isLockedForTemplate = value;
            templateLockViewRef.setVisibility(value ? View.VISIBLE : View.GONE);
        }

        public String getAdditionalFFmpegCommand() {
            return additionalFFmpegCommand;
        }

        public void setAdditionalFFmpegCommand(String additionalFFmpegCommand) {
            this.additionalFFmpegCommand = additionalFFmpegCommand;
            customCommandViewRef.setVisibility((additionalFFmpegCommand != null && !additionalFFmpegCommand.isEmpty()) ? View.VISIBLE : View.GONE);
        }

        public boolean isMute() {
            return isMute;
        }

        public void setMute(boolean mute) {
            isMute = mute;
            muteViewRef.setVisibility(mute ? View.VISIBLE : View.GONE);
        }

        public boolean isClipHasAudio() {
            return isClipHasAudio;
        }

        public void setClipHasAudio(boolean clipHasAudio) {
            isClipHasAudio = clipHasAudio;
            noSoundViewRef.setVisibility(!clipHasAudio ? View.VISIBLE : View.GONE);
        }

        public boolean isReverse()
        {
            return isReverse;
        }
        public void setReverse(boolean value)
        {
            isReverse = value;
            reverseViewRef.setVisibility(value ? View.VISIBLE : View.GONE);
        }


        public String getClipName()
        {
            return clipName;
        }
        public void setClipName(String clipName, MainAreaScreen.ProjectData data, Timeline timeline)
        {
            String oldName = this.clipName;
            // Apply the filename change for both preview path and
            File file = new File(getAbsolutePath(data));
            File filePreview = new File(getAbsolutePreviewPath(data, Constants.DEFAULT_PREVIEW_CLIP_VIDEO_EXTENSION));
            if(file.renameTo(new File(getAbsolutePath(data).replace(this.clipName, clipName))) &&
                    filePreview.renameTo(new File(getAbsolutePreviewPath(data, Constants.DEFAULT_PREVIEW_CLIP_VIDEO_EXTENSION).replace(this.clipName, clipName)))) {
                // Apply the filename changes for the whole timeline
                for (Track track : timeline.tracks) {
                    for (Clip clip : track.clips) {
                        if(oldName.equals(clip.clipName))
                            clip.clipName = clipName;
                    }
                }
            }
            else
                ; // Operation failed
        }



        public float getStartTime() {
            return startTime;
        }

        public void setStartTime(float startTime) {
            this.startTime = startTime;
        }
        public float getDuration()
        {
            return duration;
        }
        public void setDuration(float value)
        {
            duration = value;
            if(durationText != null)
                durationText.setText(StringFormatHelper.smartRound(duration, 2, true) + "s");
        }
        public float getStartClipTrim()
        {
            return startClipTrim;
        }
        public void setStartClipTrim(float value)
        {
            startClipTrim = value;
            setDuration(originalDuration - endClipTrim - startClipTrim);
        }
        public float getEndClipTrim()
        {
            return endClipTrim;
        }
        public void setEndClipTrim(float value)
        {
            endClipTrim = value;
            setDuration(originalDuration - endClipTrim - startClipTrim);
        }

        public void resetKeyframes(EditingActivity instance) {
            instance.clearKeyframe(this);
        }
        public void addKeyframe(EditingActivity instance, Keyframe keyframe)
        {
            instance.addKeyframe(this, keyframe);
        }
        public void importKeyframes(EditingActivity instance, AnimatedProperty sourceProperty) {
            resetKeyframes(instance);
            for (Keyframe keyframe : sourceProperty.keyframes) {
                addKeyframe(instance, keyframe);
            }
        }
        public void combineClips(Clip clipA, Clip clipB)
        {
            // If both clip are the same track, and same source
            if(clipA.trackIndex == clipB.trackIndex && clipA.clipName.equals(clipB.clipName) &&
                    clipA.originalDuration == clipB.originalDuration)
            {
                // Since both clip has the same originalDuration, we'll using both.
                if(clipA.originalDuration - clipA.endClipTrim == clipB.startClipTrim ||
                        clipB.originalDuration - clipB.endClipTrim == clipA.startClipTrim)
                {
                    // If all satisfy. Merge the video

                    Clip mergedClip = new Clip(clipA);
                    mergedClip.setEndClipTrim(clipB.endClipTrim);

                    mergedClip.keyframes.keyframes.addAll(clipB.keyframes.keyframes);
                }
            }
        }
    }




    public enum ClipType {
        VIDEO,
        AUDIO,
        IMAGE,
        TEXT,
        TRANSITION,
        EFFECT,
        SCENE_3D
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
        public EffectTemplate effect; // e.g. xfade, zoom, etc.

        @Expose
        public TransitionMode mode;

        public TransitionClip(Clip clipA, Clip clipB, float transitionDuration)
        {
            trackIndex = clipA.trackIndex;
            startTime = clipB.startTime - transitionDuration / 2;
            duration = transitionDuration;
            effect = new EffectTemplate("none", transitionDuration, startTime);
            mode = TransitionClip.TransitionMode.OVERLAP;
        }

        public TransitionClip(Clip clipA)
        {
            trackIndex = clipA.trackIndex;
            startTime = 0;
            duration = 0;
            effect = new EffectTemplate("none", 0, startTime);
            mode = TransitionClip.TransitionMode.OVERLAP;
        }


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
        int bitrate;
        int clipCap;
        String preset;
        String tune;
        boolean isStretchToFull;
        boolean useHardwareAccel;
        public VideoSettings(int videoWidth, int videoHeight, int frameRate, int crf, int clipCap, String preset, String tune, boolean isStretchToFull)
        {
            this.videoWidth = videoWidth;
            this.videoHeight = videoHeight;
            this.frameRate = frameRate;
            this.crf = crf;
            this.bitrate = 15;
            this.clipCap = clipCap;
            this.preset = preset;
            this.tune = tune;
            this.isStretchToFull = isStretchToFull;
            this.useHardwareAccel = true;
        }

        public VideoSettings(int videoWidth, int videoHeight, int frameRate, int crf, int clipCap, String preset, String tune)
        {
            this.videoWidth = videoWidth;
            this.videoHeight = videoHeight;
            this.frameRate = frameRate;
            this.crf = crf;
            this.bitrate = 15;
            this.clipCap = clipCap;
            this.preset = preset;
            this.tune = tune;
            this.isStretchToFull = false;
            this.useHardwareAccel = true;
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
        public int getBitrate() {
            return bitrate;
        }
        public int getClipCap() {
            return clipCap;
        }
        public String getPreset() {
            return preset;
        }
        public String getTune() {
            return tune;
        }
        public boolean isStretchToFull() {
            return isStretchToFull;
        }
        public boolean isUseHardwareAccel() {
            return useHardwareAccel;
        }


        /**
         * Special getter for rendering
         * @param isTemplate distinguish the render type reason
         * @return width output by the type accordingly
         */
        public String getRenderVideoWidth(boolean isTemplate) {
            return isTemplate ? Constants.DEFAULT_TEMPLATE_CLIP_SCALE_WIDTH_MARK : String.valueOf(videoWidth);
        }
        /**
         * Special getter for rendering
         * @param isTemplate distinguish the render type reason
         * @return width output by the type accordingly
         */
        public String getRenderVideoHeight(boolean isTemplate) {
            return isTemplate ? Constants.DEFAULT_TEMPLATE_CLIP_SCALE_HEIGHT_MARK : String.valueOf(videoHeight);
        }


        public void saveSettings(Context context, MainAreaScreen.ProjectData data) {
            IOHelper.writeToFile(context, IOHelper.CombinePath(data.getProjectPath(), Constants.DEFAULT_VIDEO_SETTINGS_FILENAME), new Gson().toJson(this));
        }
        public void loadSettingsFromProject(Context context, MainAreaScreen.ProjectData data)
        {
            VideoSettings loadSettings = loadSettings(context, data);
            this.videoWidth = loadSettings.videoWidth;
            this.videoHeight = loadSettings.videoHeight;
            this.frameRate = loadSettings.frameRate;
            this.crf = loadSettings.crf;
            this.bitrate = loadSettings.bitrate > 0 ? loadSettings.bitrate : 15;
            this.clipCap = loadSettings.clipCap;
            this.preset = loadSettings.preset;
            this.tune = loadSettings.tune;
            this.isStretchToFull = loadSettings.isStretchToFull;
            this.useHardwareAccel = loadSettings.useHardwareAccel;
        }
        public static VideoSettings loadSettings(Context context, MainAreaScreen.ProjectData data) {
            return new Gson().fromJson(IOHelper.readFromFile(context, IOHelper.CombinePath(data.getProjectPath(), Constants.DEFAULT_VIDEO_SETTINGS_FILENAME)), VideoSettings.class);
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
    public static class VideoProperties implements Serializable {
        @Expose
        public float valuePosX;
        @Expose
        public float valuePosY;
        @Expose
        public float valueRot;
        @Expose
        public float valueScaleX;
        @Expose
        public float valueScaleY;
        @Expose
        public float valueOpacity;
        @Expose
        public float valueSpeed;
        @Expose
        public float valueHue;
        @Expose
        public float valueSaturation;
        @Expose
        public float valueBrightness;
        @Expose
        public float valueTemperature;

        public VideoProperties()
        {
            this.valuePosX = 0;
            this.valuePosY = 0;
            this.valueRot = 0;
            this.valueScaleX = 1;
            this.valueScaleY = 1;
            this.valueOpacity = 1;
            this.valueSpeed = 1;
            this.valueHue = 0;
            this.valueSaturation = 1;
            this.valueBrightness = 0;
            this.valueTemperature = 6500;
        }
        public VideoProperties(float valuePosX, float valuePosY,
                               float valueRot,
                               float valueScaleX, float valueScaleY,
                               float valueOpacity, float valueSpeed,
                               float valueHue, float valueSaturation,
                               float valueBrightness, float valueTemperature)
        {
            this.valuePosX = valuePosX;
            this.valuePosY = valuePosY;
            this.valueRot = valueRot;
            this.valueScaleX = valueScaleX;
            this.valueScaleY = valueScaleY;
            this.valueOpacity = valueOpacity;
            this.valueSpeed = valueSpeed;
            this.valueHue = valueHue;
            this.valueSaturation = valueSaturation;
            this.valueBrightness = valueBrightness;
            this.valueTemperature = valueTemperature;
        }

        public VideoProperties(VideoProperties properties)
        {
            this.valuePosX = properties.valuePosX;
            this.valuePosY = properties.valuePosY;
            this.valueRot = properties.valueRot;
            this.valueScaleX = properties.valueScaleX;
            this.valueScaleY = properties.valueScaleY;
            this.valueOpacity = properties.valueOpacity;
            this.valueSpeed = properties.valueSpeed;
            this.valueHue = properties.valueHue;
            this.valueSaturation = properties.valueSaturation;
            this.valueBrightness = properties.valueBrightness;
            this.valueTemperature = properties.valueTemperature;
        }

        public float getValue(ValueType valueType) {

            switch (valueType)
            {
                case PosX:
                    return valuePosX;
                case PosY:
                    return valuePosY;
                case Rot:
                    return valueRot;
                case RotInRadians:
                    return (float) Math.toRadians(valueRot);
                case ScaleX:
                    return valueScaleX;
                case ScaleY:
                    return valueScaleY;
                case Opacity:
                    return valueOpacity;
                case Speed:
                    return valueSpeed;
                case Hue:
                    return valueHue;
                case Saturation:
                    return valueSaturation;
                case Brightness:
                    return valueBrightness;
                case Temperature:
                    return valueTemperature;
                default:
                    return 1;

            }
        }

        public void setValue(float v, ValueType valueType) {

            switch (valueType)
            {
                case PosX:
                    valuePosX = v;
                    break;
                case PosY:
                    valuePosY = v;
                    break;
                case Rot:
                    valueRot = v;
                    break;
                case ScaleX:
                    valueScaleX = v;
                    break;
                case ScaleY:
                    valueScaleY = v;
                    break;
                case Opacity:
                    valueOpacity = v;
                    break;
                case Speed:
                    valueSpeed = v;
                    break;
                case Hue:
                    valueHue = v;
                    break;
                case Saturation:
                    valueSaturation = v;
                    break;
                case Brightness:
                    valueBrightness = v;
                    break;
                case Temperature:
                    valueTemperature = v;
                    break;

            }
        }

        public void setAllValue(float valuePosX, float valuePosY, float valueRot, float valueScaleX, float valueScaleY, float valueHue, float valueOpacity, float valueSpeed, float valueSaturation, float valueBrightness, float valueTemperature) {
            setValue(valuePosX, VideoProperties.ValueType.PosX);
            setValue(valuePosY, VideoProperties.ValueType.PosY);
            setValue(valueRot, VideoProperties.ValueType.Rot);
            setValue(valueScaleX, VideoProperties.ValueType.ScaleX);
            setValue(valueScaleY, VideoProperties.ValueType.ScaleY);
            setValue(valueHue, VideoProperties.ValueType.Hue);

            setValue(valueOpacity, VideoProperties.ValueType.Opacity);
            setValue(valueSpeed, VideoProperties.ValueType.Speed);
            setValue(valueSaturation, VideoProperties.ValueType.Saturation);
            setValue(valueBrightness, VideoProperties.ValueType.Brightness);
            setValue(valueTemperature, VideoProperties.ValueType.Temperature);
        }

        public enum ValueType {
            PosX, PosY, Rot, RotInRadians, ScaleX, ScaleY, Opacity, Speed, Hue, Saturation, Brightness, Temperature
        }
    }
    public static class Keyframe implements Serializable {
        @Expose
        private float time; // seconds, in local clip time
        @Expose
        public VideoProperties value;
        @Expose
        public EasingType easing;


        public Keyframe(float time, VideoProperties value, EasingType easing)
        {
            this.time = time;
            this.value = value;
            this.easing = easing;
        }

        public float getLocalTime()
        {
            return time;
        }
        public float getGlobalTime(Clip clip)
        {
            return time + clip.startTime;
        }

        public boolean isWithinClip(Clip clip) {
            return (time >= 0 && time <= clip.duration);
        }
        public boolean isKeyframeExist(Clip clip) {
            boolean isExist = false;
            for (Keyframe keyframe : clip.keyframes.keyframes) {
                if(Math.abs(keyframe.time - this.time) <= Constants.TRACK_CLIPS_MINIMUM_KEYFRAME_SPACE_SECONDS)
                {
                    isExist = true;
                    break;
                }
            }
            return isExist;
        }
    }
    public static class AnimatedProperty implements Serializable {

        @Expose
        public List<Keyframe> keyframes = new ArrayList<>();
        public AnimatedProperty() {}

        public AnimatedProperty(AnimatedProperty keyframes) {
            this.keyframes.addAll(keyframes.keyframes);
        }

        /**
         * Get keyframe at global clip time
         * @param clip the clip that contains keyframe to get its global time
         * @param playheadTime the global time
         * @return keyframe that matched exactly the global time, null if there's no keyframe match the provided global time.
         */
        public Keyframe getKeyframeAtTime(Clip clip, float playheadTime)
        {
            for (Keyframe k : keyframes) {
                //if(k.getGlobalTime(clip) == playheadTime) return k;
                // Adjust tolerance (0.01s)
                if(Math.abs(k.getGlobalTime(clip) - playheadTime) <= Constants.TRACK_CLIPS_MINIMUM_KEYFRAME_SPACE_SECONDS) return k;
            }
            return null;
        }
        public float getValueAtTime(Clip clip, float playheadTime, VideoProperties.ValueType valueType) {
            if (keyframes.isEmpty()) return clip.videoProperties.getValue(valueType); // previously return -1, but it's not suitable for fallback

            // preprocessing the global time to match the local time of the clip
            playheadTime -= clip.startTime;

            Keyframe prev = keyframes.get(0);
            for (Keyframe next : keyframes) {
                if (playheadTime < next.getLocalTime()) {
                    float t = (playheadTime - prev.getLocalTime()) / (next.getLocalTime() - prev.getLocalTime());
                    t = Math.max(0f, Math.min(1f, t));

                    float prevValue = prev.value.getValue(valueType);
                    float nextValue = next.value.getValue(valueType);


                    return lerp(prevValue, nextValue, ease(t, prev.easing)); // linear interpolation
                }
                prev = next;

//                if (playheadTime <= keyframes.get(0).time) return keyframes.get(0).value;
//                if (playheadTime >= keyframes.get(keyframes.size() - 1).time) return keyframes.get(keyframes.size() - 1).value;

            }
            return keyframes.get(keyframes.size() - 1).value.getValue(valueType);
        }
        public float getValueAtPoint(int keyframeIndex, VideoProperties.ValueType valueType) {
            if (keyframes.isEmpty()) return 0f;

            return keyframes.get(keyframeIndex).value.getValue(valueType);
        }

        private float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        private float ease(float t, EasingType type) {
            switch (type) {
                // None
                case NONE: return 0;

                // Linear
                case LINEAR: return t;

                // Sine
                case EASE_IN_SINE: return (float)(1 - Math.cos((t * Math.PI) / 2));
                case EASE_OUT_SINE: return (float)(Math.sin((t * Math.PI) / 2));
                case EASE_IN_OUT_SINE: return (float)(-(Math.cos(Math.PI * t) - 1) / 2);

                // Quadratic
                case EASE_IN_QUAD: return t * t;
                case EASE_OUT_QUAD: return (float)(1 - (1 - t) * (1 - t));
                case EASE_IN_OUT_QUAD: return t < 0.5f ? 2 * t * t : (float)(1 - Math.pow(-2 * t + 2, 2) / 2);

                // Cubic
                case EASE_IN_CUBIC: return t * t * t;
                case EASE_OUT_CUBIC: return (float)(1 - Math.pow(1 - t, 3));
                case EASE_IN_OUT_CUBIC: return t < 0.5f ? 4 * t * t * t : (float)(1 - Math.pow(-2 * t + 2, 3) / 2);

                // Quartic
                case EASE_IN_QUART: return t * t * t * t;
                case EASE_OUT_QUART: return (float)(1 - Math.pow(1 - t, 4));
                case EASE_IN_OUT_QUART: return t < 0.5f ? 8 * t * t * t * t : (float)(1 - Math.pow(-2 * t + 2, 4) / 2);

                // Quintic
                case EASE_IN_QUINT: return t * t * t * t * t;
                case EASE_OUT_QUINT: return (float)(1 - Math.pow(1 - t, 5));
                case EASE_IN_OUT_QUINT: return t < 0.5f ? 16 * t * t * t * t * t : (float)(1 - Math.pow(-2 * t + 2, 5) / 2);

                // Exponential
                case EASE_IN_EXPO: return (t == 0) ? 0 : (float)Math.pow(2, 10 * t - 10);
                case EASE_OUT_EXPO: return (t == 1) ? 1 : (float)(1 - Math.pow(2, -10 * t));
                case EASE_IN_OUT_EXPO:
                    if (t == 0) return 0;
                    if (t == 1) return 1;
                    return t < 0.5f
                            ? (float)(Math.pow(2, 20 * t - 10) / 2)
                            : (float)((2 - Math.pow(2, -20 * t + 10)) / 2);

                // Circular
                case EASE_IN_CIRC: return (float)(1 - Math.sqrt(1 - t * t));
                case EASE_OUT_CIRC: return (float)(Math.sqrt(1 - Math.pow(t - 1, 2)));
                case EASE_IN_OUT_CIRC:
                    return t < 0.5f
                            ? (float)((1 - Math.sqrt(1 - Math.pow(2 * t, 2))) / 2)
                            : (float)((Math.sqrt(1 - Math.pow(-2 * t + 2, 2)) + 1) / 2);

                // Back
                case EASE_IN_BACK: {
                    float c1 = 1.70158f;
                    float c3 = c1 + 1;
                    return c3 * t * t * t - c1 * t * t;
                }
                case EASE_OUT_BACK: {
                    float c1 = 1.70158f;
                    float c3 = c1 + 1;
                    return (float)(1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2));
                }
                case EASE_IN_OUT_BACK: {
                    float c1 = 1.70158f;
                    float c2 = c1 * 1.525f;
                    return t < 0.5f
                            ? (float)(Math.pow(2 * t, 2) * ((c2 + 1) * 2 * t - c2)) / 2
                            : (float)((Math.pow(2 * t - 2, 2) * ((c2 + 1) * (t * 2 - 2) + c2) + 2) / 2);
                }

                // Elastic
                case EASE_IN_ELASTIC:
                    if (t == 0) return 0;
                    if (t == 1) return 1;
                    return (float)(-Math.pow(2, 10 * t - 10) * Math.sin((t * 10 - 10.75) * ((2 * Math.PI) / 3)));
                case EASE_OUT_ELASTIC:
                    if (t == 0) return 0;
                    if (t == 1) return 1;
                    return (float)(Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * ((2 * Math.PI) / 3)) + 1);
                case EASE_IN_OUT_ELASTIC:
                    if (t == 0) return 0;
                    if (t == 1) return 1;
                    return t < 0.5f
                            ? (float)(-(Math.pow(2, 20 * t - 10) * Math.sin((20 * t - 11.125) * ((2 * Math.PI) / 4.5))) / 2)
                            : (float)((Math.pow(2, -20 * t + 10) * Math.sin((20 * t - 11.125) * ((2 * Math.PI) / 4.5))) / 2 + 1);

                // Bounce
                case EASE_IN_BOUNCE: return 1 - ease(1 - t, EasingType.EASE_OUT_BOUNCE);
                case EASE_OUT_BOUNCE:
                    if (t < 1 / 2.75f) {
                        return 7.5625f * t * t;
                    } else if (t < 2 / 2.75f) {
                        t -= 1.5f / 2.75f;
                        return 7.5625f * t * t + 0.75f;
                    } else if (t < 2.5 / 2.75) {
                        t -= 2.25f / 2.75f;
                        return 7.5625f * t * t + 0.9375f;
                    } else {
                        t -= 2.625f / 2.75f;
                        return 7.5625f * t * t + 0.984375f;
                    }
                case EASE_IN_OUT_BOUNCE:
                    return t < 0.5f
                            ? (1 - ease(1 - 2 * t, EasingType.EASE_OUT_BOUNCE)) / 2
                            : (1 + ease(2 * t - 1, EasingType.EASE_OUT_BOUNCE)) / 2;

                default: return t;
            }
        }


        public void sortKeyframe() {
            keyframes.sort((o1, o2) -> (Float.compare(o1.time, o2.time)));
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
        // None
        NONE,

        // Linear
        LINEAR,

        // Sine
        EASE_IN_SINE,
        EASE_OUT_SINE,
        EASE_IN_OUT_SINE,

        // Quadratic
        EASE_IN_QUAD,
        EASE_OUT_QUAD,
        EASE_IN_OUT_QUAD,

        // Cubic
        EASE_IN_CUBIC,
        EASE_OUT_CUBIC,
        EASE_IN_OUT_CUBIC,

        // Quartic
        EASE_IN_QUART,
        EASE_OUT_QUART,
        EASE_IN_OUT_QUART,

        // Quintic
        EASE_IN_QUINT,
        EASE_OUT_QUINT,
        EASE_IN_OUT_QUINT,

        // Exponential
        EASE_IN_EXPO,
        EASE_OUT_EXPO,
        EASE_IN_OUT_EXPO,

        // Circular
        EASE_IN_CIRC,
        EASE_OUT_CIRC,
        EASE_IN_OUT_CIRC,

        // Back
        EASE_IN_BACK,
        EASE_OUT_BACK,
        EASE_IN_OUT_BACK,

        // Elastic
        EASE_IN_ELASTIC,
        EASE_OUT_ELASTIC,
        EASE_IN_OUT_ELASTIC,

        // Bounce
        EASE_IN_BOUNCE,
        EASE_OUT_BOUNCE,
        EASE_IN_OUT_BOUNCE
    }



    public static class ClipRenderer {
        public final Clip clip;

        private MediaExtractor videoExtractor;
        private MediaCodec videoDecoder;

        private MediaExtractor audioExtractor;
        private AudioTrack audioTrack;

        // Preallocate once as a field — not every call
        private ByteBuffer audioBuffer = ByteBuffer.allocate(32768); // larger for WAV chunks
        private byte[] audioChunk = new byte[32768];

        public boolean isPlaying;

        private TextureView textureView;
        private org.rajawali3d.view.SurfaceView surfaceView;
        private Context context;

        private ExecutorService renderThreadExecutorAudio = Executors.newFixedThreadPool(1);
        private ExecutorService renderThreadExecutorVideo = Executors.newFixedThreadPool(1);



        private Matrix matrix = new Matrix();
        private float scaleX = 1, scaleY = 1;
        private float rot = 0;
        private float posX = 0, posY = 0;
        private float opacity = 1;
        private float hue = 0;
        private float saturation = 1;
        private float brightness = 0;
        private float temperature = 6500;


        private float scaleMatrixX = 1, scaleMatrixY = 1;
        private float rotMatrix = 0;
        private float posMatrixX = 0, posMatrixY = 0;


        public ClipRenderer(Context context, Clip clip, MainAreaScreen.ProjectData data, VideoSettings settings, EditingActivity editingActivity, FrameLayout previewViewGroup, TextView textCanvasControllerInfo) {
            this.context = context;
            this.clip = clip;

            try
            {

                switch (clip.type)
                {
                    case VIDEO:
                    {

                        // VIDEO






                        textureView = new TextureView(context);
                        textureView.setOpaque(false);
                        RelativeLayout.LayoutParams textureViewLayoutParams =
                                new RelativeLayout.LayoutParams(clip.width, clip.height);
                        previewViewGroup.addView(textureView, textureViewLayoutParams);


                        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                            @Override
                            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
                                try {
                                    Surface surface = new Surface(surfaceTexture);

                                    // Step 1: Extractor setup
                                    videoExtractor = new MediaExtractor();
                                    videoExtractor.setDataSource(clip.getAbsolutePreviewPath(data, Constants.DEFAULT_PREVIEW_CLIP_VIDEO_EXTENSION));

                                    int trackIndex = TimelineUtils.findMediaTrackIndex(videoExtractor, clip.type);
                                    videoExtractor.selectTrack(trackIndex);

                                    MediaFormat format = videoExtractor.getTrackFormat(trackIndex);

                                    // Step 2: Codec setup
                                    videoDecoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
                                    videoDecoder.configure(format, surface, null, 0);
                                    videoDecoder.start();


                                    surfaceTexture.setDefaultBufferSize(clip.width, clip.height); // or your target resolution

                                    posX = (EditingActivity.renderToPreviewConversionX(clip.videoProperties.getValue(VideoProperties.ValueType.PosX), settings.videoWidth));
                                    posY = (EditingActivity.renderToPreviewConversionY(clip.videoProperties.getValue(VideoProperties.ValueType.PosY), settings.videoHeight));
                                    scaleX = (EditingActivity.renderToPreviewConversionScalingX(clip.videoProperties.getValue(VideoProperties.ValueType.ScaleX), settings.videoWidth));
                                    scaleY = (EditingActivity.renderToPreviewConversionScalingY(clip.videoProperties.getValue(VideoProperties.ValueType.ScaleY), settings.videoHeight));
                                    rot = (clip.videoProperties.getValue(VideoProperties.ValueType.Rot));
                                    opacity = clip.videoProperties.getValue(VideoProperties.ValueType.Opacity);


                                    applyTransformation();
                                    applyPostTransformation();

                                } catch (Exception e) {
                                    LoggingManager.LogExceptionToNoteOverlay(context, e);
                                }
                            }

                            @Override
                            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                                // Handle resize if needed
                            }

                            @Override
                            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                                return true; // release resources if needed
                            }

                            @Override
                            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                                // Called every frame
                            }
                        });












                        // AUDIO

                        audioExtractor = new MediaExtractor();
                        audioExtractor.setDataSource(clip.getAbsolutePreviewPath(data, Constants.DEFAULT_PREVIEW_CLIP_AUDIO_EXTENSION));

                        int audioTrackIndex = TimelineUtils.findMediaTrackIndex(audioExtractor, ClipType.AUDIO);
                        audioExtractor.selectTrack(audioTrackIndex);

                        MediaFormat audioFormat = audioExtractor.getTrackFormat(audioTrackIndex);

                        // TODO: Uninitialized AudioTrack when splitting too many Clip. No reason.
                        //  -> REASON: Too many initialize VIDEO, causing RAM to stall.
                        if(Objects.requireNonNull(audioFormat.getString(MediaFormat.KEY_MIME)).startsWith("audio/"))
                        {
                            int sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            int channelConfig = (audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) ?
                                    AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
                            int audioFormatPCM = AudioFormat.ENCODING_PCM_16BIT;
                            int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormatPCM);

                            audioTrack = new AudioTrack(
                                    AudioManager.STREAM_MUSIC,
                                    sampleRate,
                                    channelConfig,
                                    audioFormatPCM,
                                    minBufferSize,
                                    AudioTrack.MODE_STREAM
                            );
                            audioTrack.play();
                        }


                        break;
                    }
                    case IMAGE:
                    {
                        textureView = new TextureView(context);
                        textureView.setOpaque(false);
                        RelativeLayout.LayoutParams textureViewLayoutParams =
                                new RelativeLayout.LayoutParams(clip.width, clip.height);
                        previewViewGroup.addView(textureView, textureViewLayoutParams);

                        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                            @Override
                            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
                                // Create a Surface from the TextureView

                                // For IMAGE rendering
                                // TODO: WTF sample size 1? Yes for rendering. We don't know how to forcefully extend the 8 old sampleSize
                                Bitmap image = IOImageHelper.LoadFileAsPNGImage(context, clip.getAbsolutePreviewPath(data, Constants.DEFAULT_PREVIEW_CLIP_VIDEO_EXTENSION), 1);

                                // Resize TextureView to match bitmap size
                                ViewGroup.LayoutParams params = textureView.getLayoutParams();
                                params.width = clip.width;
                                params.height = clip.height;
                                textureView.setLayoutParams(params);

                                // Draw the bitmap onto the TextureView’s canvas
                                Canvas canvas = textureView.lockCanvas();
                                if (canvas != null) {
                                    //canvas.drawColor(Color.BLACK); // optional background
                                    canvas.drawBitmap(image, 0, 0, null); // draw at top-left
                                    textureView.unlockCanvasAndPost(canvas);
                                }


                                surfaceTexture.setDefaultBufferSize(clip.width, clip.height); // or your target resolution

                                posX = (EditingActivity.renderToPreviewConversionX(clip.videoProperties.getValue(VideoProperties.ValueType.PosX), settings.videoWidth));
                                posY = (EditingActivity.renderToPreviewConversionY(clip.videoProperties.getValue(VideoProperties.ValueType.PosY), settings.videoHeight));
                                scaleX = (EditingActivity.renderToPreviewConversionScalingX(clip.videoProperties.getValue(VideoProperties.ValueType.ScaleX), settings.videoWidth));
                                scaleY = (EditingActivity.renderToPreviewConversionScalingY(clip.videoProperties.getValue(VideoProperties.ValueType.ScaleY), settings.videoHeight));
                                rot = (clip.videoProperties.getValue(VideoProperties.ValueType.Rot));
                                opacity = clip.videoProperties.getValue(VideoProperties.ValueType.Opacity);

                                applyTransformation();
                                applyPostTransformation();

                            }

                            @Override
                            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}

                            @Override
                            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                                return true;
                            }

                            @Override
                            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
                        });


                        break;
                    }
                    case SCENE_3D:
                    {
                        surfaceView = new org.rajawali3d.view.SurfaceView(context);
                        surfaceView.setZOrderOnTop(true);
                        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); 
                        surfaceView.getHolder().setFormat(android.graphics.PixelFormat.TRANSPARENT);
                        surfaceView.setFrameRate(30);
                        surfaceView.setRenderMode(android.opengl.GLSurfaceView.RENDERMODE_WHEN_DIRTY);

                        com.vanvatcorporation.doubleclips.ext.rajawali.RajawaliSceneRenderer renderer = new com.vanvatcorporation.doubleclips.ext.rajawali.RajawaliSceneRenderer(context, clip.sceneConfig);
                        surfaceView.setSurfaceRenderer(renderer);

                        RelativeLayout.LayoutParams surfaceViewLayoutParams =
                                new RelativeLayout.LayoutParams(clip.width, clip.height);
                        previewViewGroup.addView(surfaceView, surfaceViewLayoutParams);

                        posX = (EditingActivity.renderToPreviewConversionX(clip.videoProperties.getValue(VideoProperties.ValueType.PosX), settings.videoWidth));
                        posY = (EditingActivity.renderToPreviewConversionY(clip.videoProperties.getValue(VideoProperties.ValueType.PosY), settings.videoHeight));
                        scaleX = (EditingActivity.renderToPreviewConversionScalingX(clip.videoProperties.getValue(VideoProperties.ValueType.ScaleX), settings.videoWidth));
                        scaleY = (EditingActivity.renderToPreviewConversionScalingY(clip.videoProperties.getValue(VideoProperties.ValueType.ScaleY), settings.videoHeight));
                        rot = (clip.videoProperties.getValue(VideoProperties.ValueType.Rot));
                        opacity = clip.videoProperties.getValue(VideoProperties.ValueType.Opacity);

                        applyPostTransformation();
                        break;
                    }
                    case AUDIO:
                    {

                        audioExtractor = new MediaExtractor();
                        audioExtractor.setDataSource(clip.getAbsolutePreviewPath(data, Constants.DEFAULT_PREVIEW_CLIP_AUDIO_EXTENSION));

                        int audioTrackIndex = TimelineUtils.findMediaTrackIndex(audioExtractor, ClipType.AUDIO);
                        audioExtractor.selectTrack(audioTrackIndex);

                        MediaFormat audioFormat = audioExtractor.getTrackFormat(audioTrackIndex);

                        int sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        int channelConfig = (audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) ?
                                AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
                        int audioFormatPCM = audioFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                ? audioFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                : AudioFormat.ENCODING_PCM_16BIT;
                        int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormatPCM);

                        audioTrack = new AudioTrack(
                                AudioManager.STREAM_MUSIC,
                                sampleRate,
                                channelConfig,
                                audioFormatPCM,
                                minBufferSize,
                                AudioTrack.MODE_STREAM
                        );
                        audioTrack.play();

                        break;
                    }
                }


                if(textureView != null)
                {
                    setPivot();
                    attachGestureControls(textureView, clip, settings, editingActivity, textCanvasControllerInfo);
                }
                else if(surfaceView != null)
                {
                    attachGestureControls(surfaceView, clip, settings, editingActivity, textCanvasControllerInfo);
                }


            }
            catch (Exception e)
            {
                LoggingManager.LogExceptionToNoteOverlay(context, e);
            }
        }


        public boolean isVisible(float playheadTime) {
            return playheadTime >= clip.startTime &&
                    playheadTime <= clip.startTime + clip.duration;
        }

        public void renderFrame(float playheadTime, boolean isSeekingOnly) {
            if (!isVisible(playheadTime)) {
//                if(surfaceView != null)
//                {
//                    Canvas canvas = surfaceHolder.lockCanvas();
//                    if (canvas != null) {
//                        canvas.drawColor(Color.BLACK); // Fill canvas with black
//                        surfaceHolder.unlockCanvasAndPost(canvas);
//                    }
//                }
                return;
            }
//            if(isPlaying && !isSeekingOnly) return;

            startPlayingAt(playheadTime, isSeekingOnly);
        }

        private void pumpDecoderVideoSeek(float playheadTime) {
            if (videoDecoder == null || textureView == null) return;
            if (textureView.getVisibility() == View.GONE) return;

            float clipTime = playheadTime - clip.startTime + clip.startClipTrim;
            long ptsUs = (long)(clipTime * 1_000_000L);

            // ✅ Seek BEFORE reading — your original code read first, then seeked
            videoExtractor.seekTo(ptsUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            int inputIndex = videoDecoder.dequeueInputBuffer(5_000L); // small timeout vs 0
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = videoDecoder.getInputBuffer(inputIndex);
                inputBuffer.clear();
                int sampleSize = videoExtractor.readSampleData(inputBuffer, 0);

                if (sampleSize >= 0) {
                    videoDecoder.queueInputBuffer(inputIndex, 0, sampleSize, ptsUs, 0);
                } else {
                    videoDecoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputIndex = videoDecoder.dequeueOutputBuffer(bufferInfo, 5_000L);
            if (outputIndex >= 0) {
                videoDecoder.releaseOutputBuffer(outputIndex, true);
            }
        }
        private void pumpDecoderAudioSeek(float playheadTime) {
            if (audioTrack == null || audioExtractor == null) return;
            float clipTime = playheadTime - clip.startTime + clip.startClipTrim;
            long ptsUs = (long)(clipTime * 1_000_000); // override presentation timestamp
            audioTrack.flush(); // discard stale buffered audio from previous position
            audioExtractor.seekTo(ptsUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            audioBuffer.clear();
            int sampleSize = audioExtractor.readSampleData(audioBuffer, 0);
            if (sampleSize < 0) return; // End of stream

            // Reuse chunk array, resize only if needed
            if (audioChunk.length < sampleSize) {
                audioChunk = new byte[sampleSize];
            }

            audioBuffer.get(audioChunk, 0, sampleSize);
            audioTrack.write(audioChunk, 0, sampleSize, AudioTrack.WRITE_NON_BLOCKING);
        }
        boolean audioRunning;
        private void streamContinuousAudio(float clipTime) {
            if (audioTrack == null || audioExtractor == null) return;

            stopAudio();
            audioRunning = true;

            renderThreadExecutorAudio.execute(() -> {
                try {
                    long ptsUs = (long) (clipTime * 1_000_000L);

                    // 2. Seek the extractor to the correct timestamp
                    audioExtractor.seekTo(ptsUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

                    // 3. MUST call play() before a blocking write loop, otherwise it deadlocks
                    // when the internal hardware buffer fills up!
                    audioTrack.play();

                    // Allocate a reusable buffer
                    ByteBuffer buffer = ByteBuffer.allocate(65536);

                    while (audioRunning) {
                        // Clear buffer for the next read
                        buffer.clear();

                        // Read the sample data
                        int sampleSize = audioExtractor.readSampleData(buffer, 0);
                        if (sampleSize < 0) {
                            break; // End of stream reached
                        }

                        // 4. Write to AudioTrack in BLOCKING mode.
                        // Using the API 21+ ByteBuffer signature to avoid creating new byte[] arrays on every loop.
                        int written = audioTrack.write(buffer, sampleSize, AudioTrack.WRITE_BLOCKING);

                        if (written < 0) {
                            System.err.println("AudioTrack write error: " + written);
                            break; // Handle error state
                        }

                        // 5. CRITICAL: Advance the extractor to the next chunk of audio!
                        audioExtractor.advance();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    // Optional: If the loop finishes naturally (end of file), stop the track
                    if (audioRunning && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack.stop();
                    }
                }
            });
        }

        private void stopAudio() {
            softStopAudio();

            if (audioTrack != null) {
                // Pause stops the hardware from playing immediately
                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.pause();
                }
                // Flush instantly dumps the unplayed audio in the hardware buffer.
                // This is crucial for a video editor so you don't hear a "tail" of audio
                // after the user hits the pause button.
                audioTrack.flush();
            }
        }
        private void softStopAudio() {
            audioRunning = false; // This gracefully breaks the while loop in the Executor
        }




        public void startPlayingAt(float playheadTime, boolean isSeekingOnly) {
            if (!isVisible(playheadTime)) {
//                Canvas canvas = surfaceHolder.lockCanvas();
//                if (canvas != null) {
//                    canvas.drawColor(Color.BLACK); // Fill canvas with black
//                    surfaceHolder.unlockCanvasAndPost(canvas);
//                }
                return;
            }


            try {

                if(textureView != null)
                {
                    float x = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.PosX);
                    float y = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.PosY);
                    float rotation = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.Rot);
                    float sx = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.ScaleX);
                    float sy = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.ScaleY);
                    float op = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.Opacity);

                    hue = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.Hue);
                    saturation = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.Saturation);
                    brightness = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.Brightness);
                    temperature = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.Temperature);

                    posX = x;
                    posY = y;
                    rot = rotation;
                    scaleX = sx == -1 ? scaleX : sx;
                    scaleY = sy == -1 ? scaleY : sy;
                    opacity = op < 0 ? opacity : op;



                    applyPostTransformation();
                    //applyPostMatrixTransformation();
                }

                switch (clip.type)
                {
//                    case VIDEO:
//                    {
//
////                        if(clip.getLocalClipTime(playheadTime) * 1000 > 0 && clip.getLocalClipTime(playheadTime) * 1000 < clip.duration)
////                        {
////                            videoPlayer.seekTo((long) (clip.getTrimmedLocalTime(clip.getLocalClipTime(playheadTime)) * 1000000));
////                            System.err.println(videoPlayer.getCurrentPosition());
////                            if(!isSeekingOnly)
////                            {
////                                videoPlayer.start();
////                                isPlaying = true;
////                            }
////                        }
//                        renderThreadExecutorVideo.execute(() -> pumpDecoderVideoSeek(playheadTime));
//
//                        if(clip.isClipHasAudio())
//                            renderThreadExecutorAudio.execute(() -> pumpDecoderAudioSeek(playheadTime));
//                        break;
//                    }
//                    case AUDIO:
//                    {
////                        if(clip.getLocalClipTime(playheadTime) * 1000 > 0 && clip.getLocalClipTime(playheadTime) * 1000 < clip.duration)
////                        {
////                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
////                                audioPlayer.seekTo((int) (clip.getTrimmedLocalTime(clip.getLocalClipTime(playheadTime)) * 1000), android.media.MediaPlayer.SEEK_CLOSEST);
////                            }
////                            else {
////                                audioPlayer.seekTo((int) (clip.getTrimmedLocalTime(clip.getLocalClipTime(playheadTime)) * 1000));
////                            }
////                            if(!isSeekingOnly)
////                            {
////                                audioPlayer.start();
////                                isPlaying = true;
////                            }
////                        }
//
//
//                        renderThreadExecutorAudio.execute(() -> pumpDecoderAudioSeek(playheadTime));
//                        break;
//                    }
                    case SCENE_3D:
                    {
                        if (surfaceView != null && !isSeekingOnly) {
                            surfaceView.requestRender();
                        }
                        break;
                    }

                }

                float clipTime = Math.max(0, playheadTime - clip.startTime + clip.startClipTrim);

                if (isSeekingOnly) {
                    softStopAudio();
                    isPlaying = false;
                    if (clip.type == ClipType.VIDEO || clip.type == ClipType.AUDIO) {
                        if (clip.type == ClipType.VIDEO)
                            renderThreadExecutorVideo.execute(() -> pumpDecoderVideoSeek(playheadTime));
                        renderThreadExecutorAudio.execute(() -> pumpDecoderAudioSeek(playheadTime));
                    }
                } else {
                    if (clip.type == ClipType.VIDEO) {
                        renderThreadExecutorVideo.execute(() -> pumpDecoderVideoSeek(playheadTime));
                    }
                    if (!isPlaying) {
                        isPlaying = true;
                        streamContinuousAudio(clipTime);
                    }
                }



            } catch (Exception e) {
                LoggingManager.LogExceptionToNoteOverlay(context, e);
            }
        }





        private void setPivot() {
            textureView.post(() -> {
                // Not affecting the translation pos when scaling
                textureView.setPivotX(0);
                textureView.setPivotY(0);
                // TODO: Research later. Can be useful (#1)
                // But lets try if we apply the pivot to matrix
//                textureView.setPivotX(textureView.getWidth() / 2f);
//                textureView.setPivotY(textureView.getHeight() / 2f);
            });

        }


        EditMode currentMode = EditMode.NONE;


        private void attachGestureControls(View tv, Clip clip, VideoSettings settings, EditingActivity editingActivity, TextView textCanvasControllerInfo) {
            final GestureDetector tapDrag = new GestureDetector(tv.getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; } // must return true to receive events
                @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {

                    if(currentMode != EditMode.NONE && currentMode != EditMode.MOVE) return true;
                    currentMode = EditMode.MOVE;

                    // Move
                    posX -= dx;
                    posY -= dy;
                    posMatrixX -= dx / clip.videoProperties.getValue(VideoProperties.ValueType.ScaleX);
                    posMatrixY -= dy / clip.videoProperties.getValue(VideoProperties.ValueType.ScaleY);
                    applyTransformation();

                    // Sync model
                    clip.videoProperties.setValue(EditingActivity.previewToRenderConversionX(posX, settings.videoWidth), VideoProperties.ValueType.PosX);
                    clip.videoProperties.setValue(EditingActivity.previewToRenderConversionY(posY, settings.videoHeight), VideoProperties.ValueType.PosY);

                    textCanvasControllerInfo.setText("Pos X: " + clip.videoProperties.getValue(VideoProperties.ValueType.PosX) + " | Pos Y: " + clip.videoProperties.getValue(VideoProperties.ValueType.PosY));
                    return true;
                }
                @Override public boolean onSingleTapUp(MotionEvent e) {
                    editingActivity.selectingClip(clip);
                    return true;
                }
            });

            final ScaleGestureDetector scaler = new ScaleGestureDetector(tv.getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {

                    if(currentMode != EditMode.NONE && currentMode != EditMode.SCALE && currentMode != EditMode.ROTATE) return true;
                    currentMode = EditMode.SCALE;

                    scaleX *= detector.getScaleFactor();
                    scaleY *= detector.getScaleFactor();
                    scaleMatrixX *= detector.getScaleFactor();
                    scaleMatrixY *= detector.getScaleFactor();

                    applyTransformation();

                    // TODO: Before we going further. Let calculate first the aspect ratio of video
                    //  only after that we based on the width and height of the following aspect ratio
                    //  and use it for preview scaling inside the screen that smaller than the video. (Clamping)
                    // Sync model
                    clip.videoProperties.setValue(EditingActivity.previewToRenderConversionScalingX(scaleX, settings.videoWidth), VideoProperties.ValueType.ScaleX);
                    clip.videoProperties.setValue(EditingActivity.previewToRenderConversionScalingY(scaleY, settings.videoHeight), VideoProperties.ValueType.ScaleY);

                    setPivot();

                    textCanvasControllerInfo.setText(
                                    "Scale X: " + clip.videoProperties.getValue(VideoProperties.ValueType.ScaleX) +
                                    " | Scale Y: " + clip.videoProperties.getValue(VideoProperties.ValueType.ScaleY) +
                                    "\n" + "Rot: " + clip.videoProperties.getValue(VideoProperties.ValueType.Rot)
                    );

                    return true;
                }
            });

            // Simple rotation detector
            final float[] lastAngle = { Float.NaN };
            tv.setOnTouchListener((v, event) -> {
                tapDrag.onTouchEvent(event);
                scaler.onTouchEvent(event);


                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_POINTER_DOWN:
                    case MotionEvent.ACTION_MOVE:
                        if (event.getPointerCount() >= 2) {

                            if(currentMode != EditMode.NONE && currentMode != EditMode.ROTATE && currentMode != EditMode.SCALE) break;
                            currentMode = EditMode.ROTATE;

                            float ax = event.getX(0), ay = event.getY(0);
                            float bx = event.getX(1), by = event.getY(1);
                            float angle = (float) Math.toDegrees(Math.atan2(by - ay, bx - ax)); // [-180,180]
                            if (Float.isNaN(lastAngle[0])) {
                                lastAngle[0] = angle;
                            } else {
                                float delta = normalizeAngle(angle - lastAngle[0]);
                                rot += delta;
                                rotMatrix += delta;

                                // Normalize to [-360, 360]
                                rot = ((rot + 360f) % 720f) - 360f;
                                rotMatrix = ((rotMatrix + 360f) % 720f) - 360f;

                                // Snap to nearest multiple of 90
                                float nearest = Math.round(rot / Constants.CANVAS_ROTATE_SNAP_DEGREE) * Constants.CANVAS_ROTATE_SNAP_DEGREE;
                                if (Math.abs(rot - nearest) <= Constants.CANVAS_ROTATE_SNAP_THRESHOLD_DEGREE) {
                                    rot = nearest;
                                }
                                nearest = Math.round(rotMatrix / Constants.CANVAS_ROTATE_SNAP_DEGREE) * Constants.CANVAS_ROTATE_SNAP_DEGREE;
                                if (Math.abs(rotMatrix - nearest) <= Constants.CANVAS_ROTATE_SNAP_THRESHOLD_DEGREE) {
                                    rotMatrix = nearest;
                                }

                                applyTransformation();
                                clip.videoProperties.setValue(rot, VideoProperties.ValueType.Rot);

                                textCanvasControllerInfo.setText(
                                        "Scale X: " + clip.videoProperties.getValue(VideoProperties.ValueType.ScaleX) +
                                                " | Scale Y: " + clip.videoProperties.getValue(VideoProperties.ValueType.ScaleY) +
                                                "\n" + "Rot: " + clip.videoProperties.getValue(VideoProperties.ValueType.Rot)
                                );

                                lastAngle[0] = angle;
                            }
                        }
                        break;
                    case MotionEvent.ACTION_POINTER_UP:
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        lastAngle[0] = Float.NaN;
                        currentMode = EditMode.NONE;
                        applyPostTransformation();
                        break;
                }
                return true;
            });
        }

        // TODO: Isn't handling scale properly yet.
        // TODO: Rotation is based on shared pivot. matrix.postRotate() does have 3 parameters and the last 2 are pivot maybe.
        private void applyTransformation() {
            matrix.reset();

            matrix.postScale(scaleMatrixX, scaleMatrixY);
            matrix.postRotate(rotMatrix);
            // TODO: Research later. Can be useful (#2)
//            matrix.postScale(scaleMatrixX, scaleMatrixY, textureView.getPivotX(), textureView.getPivotY());
//            matrix.postRotate(rotMatrix, textureView.getPivotX(), textureView.getPivotY());
            matrix.postTranslate(posMatrixX, posMatrixY);

            if (textureView != null) {
                textureView.setTransform(matrix);
                textureView.setAlpha(opacity);
                applyColorEffects(textureView);
                textureView.invalidate();
            } else if (surfaceView != null) {
                // Not ideal, but SurfaceView doesn't support Matrix transforms natively like TextureView
                surfaceView.setTranslationX(posX);
                surfaceView.setTranslationY(posY);
                surfaceView.setScaleX(scaleX);
                surfaceView.setScaleY(scaleY);
                surfaceView.setRotation(rot);
                surfaceView.setAlpha(opacity);
                applyColorEffectsToSurfaceView(surfaceView);
            }
        }
        private void applyPostTransformation() {
            View targetView = textureView != null ? textureView : surfaceView;
            if (targetView == null) return;
            
            targetView.post(() -> {
                // Reset the matrix state for next drag
                scaleMatrixX = 1;
                scaleMatrixY = 1;
                rotMatrix = 0;
                posMatrixX = 0;
                posMatrixY = 0;
                matrix.reset();

                if (textureView != null) {
                    textureView.setTransform(matrix);
                    textureView.invalidate();
                }

                targetView.setTranslationX(posX);
                targetView.setTranslationY(posY);
                targetView.setScaleX(scaleX);
                targetView.setScaleY(scaleY);
                targetView.setRotation(rot);
                targetView.setAlpha(opacity);


                if(targetView instanceof TextureView)
                    applyColorEffects((TextureView)targetView);
                else
                    applyColorEffectsToSurfaceView((SurfaceView) targetView);
            });
        }
        private void applyPostMatrixTransformation() {
            matrix.reset();
            matrix.setScale(scaleX, scaleY);
            matrix.setRotate(rot);
            matrix.setTranslate(posX, posY);

            textureView.setTransform(matrix);
            textureView.invalidate();
        }



// -----------------------------------------------------------------------
// Shared: build the combined ColorMatrix from hue/saturation/brightness
// -----------------------------------------------------------------------

        /**
         * Builds a combined ColorMatrix that applies hue rotation, saturation, and
         * brightness — mirroring the JavaFX ColorAdjust logic exactly.
         */
        private android.graphics.ColorMatrix buildColorAdjustMatrix() {
            android.graphics.ColorMatrix result = new android.graphics.ColorMatrix();

            // --- Brightness ---
            // JavaFX: brightness / 10.0f, clamped to [-1, 1].
            // A value of 0.0 is neutral; 10.0 maps to +1.0 (pure white shift).
            // In ColorMatrix we add (brightness * 255) to each RGB channel via the
            // translate column (indices 4, 9, 14).
            float brightnessNorm = Math.max(-1f, Math.min(1f, brightness / 10.0f));
            float brightnessTranslate = brightnessNorm * 255f;
            android.graphics.ColorMatrix brightnessMatrix = new android.graphics.ColorMatrix(new float[]{
                    1, 0, 0, 0, brightnessTranslate,
                    0, 1, 0, 0, brightnessTranslate,
                    0, 0, 1, 0, brightnessTranslate,
                    0, 0, 0, 1, 0
            });

            // --- Saturation ---
            // JavaFX: model 1.0 is neutral.
            //   saturation >= 1.0 → jfxSaturation = (saturation - 1) / 9,  range [0, 1]
            //   saturation <  1.0 → jfxSaturation = (saturation - 1) / 11, range [-1, 0)
            // Android setSaturation(0) = greyscale, (1) = unchanged, so we map:
            //   jfxSaturation  0.0 → androidSat 1.0  (neutral)
            //   jfxSaturation  1.0 → androidSat 2.0  (fully saturated boost)
            //   jfxSaturation -1.0 → androidSat 0.0  (greyscale)
            float jfxSaturation;
            if (saturation >= 1.0f) {
                jfxSaturation = (saturation - 1.0f) / 9.0f;
            } else {
                jfxSaturation = (saturation - 1.0f) / 11.0f;
            }
            jfxSaturation = Math.max(-1f, Math.min(1f, jfxSaturation));
            float androidSat = jfxSaturation + 1.0f; // shift: [-1,1] → [0,2]
            android.graphics.ColorMatrix saturationMatrix = new android.graphics.ColorMatrix();
            saturationMatrix.setSaturation(androidSat);

            // --- Hue ---
            // JavaFX: hue in [-360, 360], mapped to [-1, 1] via / 360.
            // We rotate the hue angle in degrees using a YIQ-based rotation matrix.
            float hueNorm = hue;
            if (Math.abs(hueNorm) > 360) hueNorm %= 360;
            android.graphics.ColorMatrix hueMatrix = buildHueRotationMatrix(hueNorm);

            // Combine: result = hue × saturation × brightness  (post-concat order)
            result.set(brightnessMatrix);
            result.postConcat(saturationMatrix);
            result.postConcat(hueMatrix);

            return result;
        }

        /**
         * Builds a hue-rotation ColorMatrix for the given angle in degrees.
         * Uses the standard YIQ/luminance-preserving rotation approach.
         */
        private android.graphics.ColorMatrix buildHueRotationMatrix(float degrees) {
            float rad = (float) Math.toRadians(degrees);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);

            // Luminance weights (ITU-R BT.601)
            float lr = 0.213f, lg = 0.715f, lb = 0.072f;

            return new android.graphics.ColorMatrix(new float[]{
                    lr + cos*(1-lr) + sin*(-lr),   lg + cos*(-lg)  + sin*(-lg),   lb + cos*(-lb)  + sin*(1-lb), 0, 0,
                    lr + cos*(-lr)  + sin*(0.143f), lg + cos*(1-lg) + sin*(0.140f),lb + cos*(-lb)  + sin*(-0.283f),0, 0,
                    lr + cos*(-lr)  + sin*(-(1-lr)),lg + cos*(-lg)  + sin*(lg),    lb + cos*(1-lb) + sin*(lb),   0, 0,
                    0,                              0,                              0,                             1, 0
            });
        }

// -----------------------------------------------------------------------
// Temperature tint — mirrors getTemperatureColor() + SOFT_LIGHT blend
// -----------------------------------------------------------------------

        /**
         * Returns a temperature tint as a pre-multiplied ColorMatrix that approximates
         * the JavaFX SOFT_LIGHT blend of a solid color over the image.
         *
         * SOFT_LIGHT formula (per channel): if src <= 0.5 → dst - (1-2*src)*dst*(1-dst)
         *                                   if src >  0.5 → dst + (2*src-1)*(D(dst)-dst)
         *   where D(x) = sqrt(x) for the brighter branch.
         *
         * Because ColorMatrix is linear, we approximate the per-pixel blend with a
         * linear scale + translate that matches the curve at dst = 0.5 (midtone target).
         */
        private android.graphics.ColorMatrix buildTemperatureMatrix() {
            if (Math.abs(temperature - 6500) <= 10) {
                // Neutral — identity matrix
                android.graphics.ColorMatrix m = new android.graphics.ColorMatrix();
                m.reset();
                return m;
            }

            float r, g, b, alpha;
            if (temperature < 6500) {
                // Warm: yellow/orange tint — Color(1.0, 0.6, 0.0, 0.4 * t)
                double t = Math.max(0, Math.min(1, (6500 - temperature) / 5000.0));
                r = 1.0f; g = 0.6f; b = 0.0f;
                alpha = (float)(0.4 * t);
            } else {
                // Cool: blue tint — Color(0.0, 0.4, 1.0, 0.4 * t)
                double t = Math.max(0, Math.min(1, (temperature - 6500) / 5000.0));
                r = 0.0f; g = 0.4f; b = 1.0f;
                alpha = (float)(0.4 * t);
            }

            // Linearise the SOFT_LIGHT approximation at dst=0.5:
            //   src <= 0.5 branch: scale = 1 - (1-2*src)*(1-dst)*... ≈ 1 + (2*src-1)*0.25
            //   Simplified to: out = scale*dst + translate, solved at dst=0 and dst=1.
            //
            // At dst=0: soft_light(src,0)=0  → translate = 0
            // At dst=1: soft_light(src,1)=1  → scale = 1
            // The tint manifests as a midtone push, so we encode it as:
            //   out_channel = dst * (1 - alpha*src) + alpha*src*sqrt(dst)  [continuous approx]
            // For ColorMatrix (linear only) we pick the tangent at dst=0.5:
            //   d/d(dst) at 0.5 ≈ (1 - alpha*src) + alpha*src/(2*sqrt(0.5))
            float scaleR  = 1f - alpha * r  + alpha * r  * 0.7071f; // 1/sqrt(2) ≈ 0.7071
            float scaleG  = 1f - alpha * g  + alpha * g  * 0.7071f;
            float scaleB  = 1f - alpha * b  + alpha * b  * 0.7071f;
            // Translate: the "lift" at dst=0 from the bright-src branch is 0 in soft-light,
            // but the warm/cool shift introduces a small toe — we add half the alpha*src push.
            float transR = alpha * r  * 0.5f * 255f;
            float transG = alpha * g  * 0.5f * 255f;
            float transB = alpha * b  * 0.5f * 255f;

            return new android.graphics.ColorMatrix(new float[]{
                    scaleR, 0,      0,      0, transR,
                    0,      scaleG, 0,      0, transG,
                    0,      0,      scaleB, 0, transB,
                    0,      0,      0,      1, 0
            });
        }

// -----------------------------------------------------------------------
// Apply to TextureView  (supports ColorFilter directly)
// -----------------------------------------------------------------------

        private void applyColorEffects(android.view.TextureView tv) {
            android.graphics.ColorMatrix combined = buildColorAdjustMatrix();
            combined.postConcat(buildTemperatureMatrix());
            tv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(combined));
            tv.setLayerPaint(paint);
        }

// -----------------------------------------------------------------------
// Apply to Rajawali SurfaceView  (no Paint support — drive via material)
// -----------------------------------------------------------------------

        /**
         * Rajawali's SurfaceView renders through OpenGL, so we can't use
         * ColorMatrixColorFilter. Instead we expose the computed values so the
         * Rajawali material/renderer can consume them (e.g. as uniform floats
         * passed into a custom fragment shader or via Object3D tinting).
         *
         * Call getRajawaliColorMatrix() from your renderer whenever you rebuild
         * the material for this clip's surface.
         */
        private android.graphics.ColorMatrix rajawaliColorMatrix;

        private void applyColorEffectsToSurfaceView(org.rajawali3d.view.SurfaceView sv) {
            android.graphics.ColorMatrix combined = buildColorAdjustMatrix();
            combined.postConcat(buildTemperatureMatrix());
            rajawaliColorMatrix = combined;

            // If your renderer exposes a method to accept a ColorMatrix or its
            // float[20] array, call it here, for example:
            //
            //   if (renderer != null) {
            //       renderer.setColorMatrix(combined.getArray());
            //   }
            //
            // The float[20] layout is:
            //   [ scaleR, 0,      0,      0,  translateR*255,
            //     0,      scaleG, 0,      0,  translateG*255,
            //     0,      0,      scaleB, 0,  translateB*255,
            //     0,      0,      0,      1,  0              ]
        }

        /** Exposes the latest combined colour matrix for the Rajawali renderer. */
        public float[] getRajawaliColorMatrix() {
            return rajawaliColorMatrix != null ? rajawaliColorMatrix.getArray() : null;
        }










        private float normalizeAngle(float a) {
            // Map to [-180, 180] to avoid jump
            while (a > 180f) a -= 360f;
            while (a < -180f) a += 360f;
            return a;
        }












        public enum EditMode {
            MOVE, SCALE, ROTATE, NONE
        }


        public void release() {
            if (audioExtractor != null) {
                audioExtractor.release();
            }
            if(videoDecoder != null) {
                videoDecoder.release();
            }
            if(videoExtractor != null) {
                videoExtractor.release();
            }
            if(renderThreadExecutorAudio != null) {
                renderThreadExecutorAudio.shutdownNow();
            }
            if(renderThreadExecutorVideo != null) {
                renderThreadExecutorVideo.shutdownNow();
            }

        }
    }


    public static class TimelineRenderer {
        private final Context context;
        private List<List<ClipRenderer>> trackLayers = new ArrayList<>();

        public TimelineRenderer(Context context) {
            this.context = context;
        }

        public void buildTimeline(Timeline timeline, MainAreaScreen.ProjectData properties, VideoSettings settings, EditingActivity editingActivity, FrameLayout previewViewGroup, TextView textCanvasControllerInfo)
        {
            // Release the previous render session
            release();

            previewViewGroup.removeAllViews();

            // Black box for blank video
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            View blackBox = new View(context);
            blackBox.setBackgroundColor(Color.BLACK);
            previewViewGroup.addView(blackBox, params);

            trackLayers = new ArrayList<>();

            for (Track track : timeline.tracks) {
                List<ClipRenderer> renderers = new ArrayList<>();
                for (Clip clip : track.clips) {
                    switch (clip.type)
                    {
                        case VIDEO:
                        case AUDIO:
                        case IMAGE:
                        case SCENE_3D:
                            ClipRenderer clipRenderer = new ClipRenderer(context, clip, properties, settings, editingActivity, previewViewGroup, textCanvasControllerInfo);
                            renderers.add(clipRenderer);
                            break;
                    }
                }
                trackLayers.add(renderers);
            }
        }
        public void updateTime(float time, boolean isSeekingOnly)
        {
            for (List<ClipRenderer> trackRenderer : trackLayers) {
                for (ClipRenderer clipRenderer : trackRenderer) {
                    if(clipRenderer != null)
                    {
                        if(clipRenderer.isVisible(time))
                        {
                            if(clipRenderer.textureView != null)
                                clipRenderer.textureView.setVisibility(View.VISIBLE);
                            if(clipRenderer.surfaceView != null)
                                clipRenderer.surfaceView.setVisibility(View.VISIBLE);
                        }
                        else {
                            if(clipRenderer.textureView != null)
                                clipRenderer.textureView.setVisibility(View.GONE);
                            if(clipRenderer.surfaceView != null)
                                clipRenderer.surfaceView.setVisibility(View.GONE);
                            clipRenderer.isPlaying = false;
                        }
                        clipRenderer.renderFrame(time, isSeekingOnly);
                    }
                }
            }
        }


        public void startPlayAt(float playheadTime) {

            boolean renderedAny = false;

            for (List<ClipRenderer> track : trackLayers) {
                for (ClipRenderer clipRenderer : track) {
                    if (clipRenderer.isVisible(playheadTime)) {
                        clipRenderer.renderFrame(playheadTime, false);
                        renderedAny = true;
                    }
                }
            }

            if (!renderedAny) {
                renderSolidBlack();
            }
        }

        private void renderSolidBlack() {
//            GLES20.glClearColor(0f, 0f, 0f, 1f);
//            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }

        public void release() {
            for (List<ClipRenderer> track : trackLayers) {
                for (ClipRenderer cr : track) cr.release();
            }
            trackLayers.clear();
        }
    }



    private void processBackgroundRemoval(Clip clip) {
        String cutoutPath = clip.getCutoutPath(properties);
        if (IOHelper.isFileExist(cutoutPath)) {
            // Already processed
            timelineRenderer.updateTime(currentTime, true);
            return;
        }

        if (clip.type == ClipType.IMAGE) {
            String sourcePath = clip.getAbsolutePath(properties);
            Bitmap source = IOHelper.loadBitmap(sourcePath);
            if (source == null) return;

            clipEditSpecificAreaScreen.removeBackgroundProgress.setVisibility(View.VISIBLE);
            BackgroundRemover.removeBackground(source, new BackgroundRemover.Callback() {
                @Override
                public void onSuccess(Bitmap result) {
                    runOnUiThread(() -> {
                        IOHelper.saveBitmap(EditingActivity.this, result, cutoutPath);
                        clipEditSpecificAreaScreen.removeBackgroundProgress.setVisibility(View.GONE);
                        timelineRenderer.updateTime(currentTime, true);
                    });
                }

                @Override
                public void onFailure(Exception e) {
                    runOnUiThread(() -> {
                        clipEditSpecificAreaScreen.removeBackgroundProgress.setVisibility(View.GONE);
                        LoggingManager.LogToToast(EditingActivity.this, "Failed to remove background: " + e.getMessage());
                    });
                }
            });
        } else if (clip.type == ClipType.VIDEO) {
            clipEditSpecificAreaScreen.removeBackgroundProgress.setVisibility(View.VISIBLE);
            VideoMaskGenerator.generateMask(this, clip, properties, new VideoMaskGenerator.Callback() {
                @Override
                public void onProgress(int current, int total) {
                    runOnUiThread(() -> {
                        clipEditSpecificAreaScreen.removeBackgroundProgress.setProgress((int) ((current / (float) total) * 100));
                    });
                }

                @Override
                public void onSuccess(String maskPath) {
                    runOnUiThread(() -> {
                        clipEditSpecificAreaScreen.removeBackgroundProgress.setVisibility(View.GONE);
                        LoggingManager.LogToToast(EditingActivity.this, "Video background mask generated!");
                        timelineRenderer.updateTime(currentTime, true);
                    });
                }

                @Override
                public void onFailure(Exception e) {
                    runOnUiThread(() -> {
                        clipEditSpecificAreaScreen.removeBackgroundProgress.setVisibility(View.GONE);
                        LoggingManager.LogToToast(EditingActivity.this, "Failed to generate video mask: " + e.getMessage());
                    });
                }
            });
        }
    }
}



