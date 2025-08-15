package com.vanvatcorporation.doubleclips;

import android.content.Context;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Statistics;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.activities.MainActivity;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.impl.java.RunnableImpl;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;

public class FFmpegEdit {
    public static FfmpegRenderQueue queue = new FfmpegRenderQueue();
    public static void runAnyCommand(Context context, String cmd, String taskName) {
        runAnyCommand(context, cmd, taskName, "Ran command!", "Command failed: ", true);
    }

    public static void runAnyCommand(Context context, String cmd, String taskName,
                                     Runnable onSuccessRunnable, Runnable onFailRunnable,
                                     RunnableImpl onLogRunnable, RunnableImpl onStatisticsRunnable) {
        runAnyCommand(context, cmd, taskName, "Ran command!", "Command failed: ", true, onSuccessRunnable, onFailRunnable, onLogRunnable, onStatisticsRunnable);
    }

    public static void runAnyCommand(Context context, String cmd, String taskName, String successMessage, String failMessage, boolean includeFullReport) {
        runAnyCommand(context, cmd, taskName, successMessage, failMessage, includeFullReport, () -> {
        }, () -> {
        }, new RunnableImpl() {
            @Override
            public <T> void runWithParam(T param) {

            }
        }, new RunnableImpl() {
            @Override
            public <T> void runWithParam(T param) {

            }
        });
    }

    public static void runAnyCommand(Context context, String cmd, String taskName, String successMessage, String failMessage, boolean includeFullReport,
                                     Runnable onSuccessRunnable, Runnable onFailRunnable,
                                     RunnableImpl onLogRunnable, RunnableImpl onStatisticsRunnable) {
        LoggingManager.LogToPersistentDataPath(context, cmd);



        queue.enqueue(
                new FfmpegRenderQueue.FfmpegRenderQueueInfo(
                        taskName,
                        () -> {

                            FFmpegKit.executeAsync(cmd, session -> {
                                        if (ReturnCode.isSuccess(session.getReturnCode())) {
                                            StringBuilder builder = new StringBuilder();
                                            if (includeFullReport) {
                                                builder.append("Report: ").append("\n")
                                                        .append("Output: ").append(session.getOutput()).append("\n")
                                                        .append("State: ").append(session.getState()).append("\n")
                                                        .append("Return code: ").append(session.getReturnCode()).append("\n");
                                            }
                                            LoggingManager.LogToPersistentDataPath(context, successMessage + builder);
                                            onSuccessRunnable.run();
                                        } else {
                                            StringBuilder builder = new StringBuilder();
                                            if (includeFullReport) {
                                                builder.append("Report: ").append("\n")
                                                        .append("Output: ").append(session.getOutput()).append("\n")
                                                        .append("State: ").append(session.getState()).append("\n")
                                                        .append("Return code: ").append(session.getReturnCode()).append("\n")
                                                        .append("Stacktrace: ").append(session.getFailStackTrace()).append("\n");
                                            }
                                            LoggingManager.LogToPersistentDataPath(context, failMessage + builder);
                                            onFailRunnable.run();
                                        }

                                        queue.taskCompleted(); // Move to next task
                                    },
                                    onLogRunnable::runWithParam,
                                    onStatisticsRunnable::runWithParam
                            );

                        }
                )

        );

    }


    public static void renderKeyframedClip(Context context, String workingProjectPath, EditingActivity.Clip clip, int tempIndex, int fps) {
        int totalFrames = (int)(clip.duration * fps);
        int startingFrame = (int)(clip.startTime * fps);

        int offsetTotalFrames = totalFrames + startingFrame;

        for (int i = startingFrame; i < offsetTotalFrames; i++) {
            float time = i / (float)fps;

            float scale = clip.scaleKeyFrames.getValueAt(time);
            float x = clip.posXKeyFrames.getValueAt(time);
            float y = clip.posYKeyFrames.getValueAt(time);
            float rotation = clip.rotationKeyFrames.getValueAt(time);

            renderFrameToBitmap(context, clip.filePath, workingProjectPath, scale, x, y, rotation, i);
        }

        // Audio extracting part
        String audioFilePath = IOHelper.CombinePath(workingProjectPath, Constants.DEFAULT_CLIP_TEMP_DIRECTORY, tempIndex + "audio.aac");
        StringBuilder audioCmd = new StringBuilder();
        audioCmd.append("-i ").append("\"").append(clip.filePath).append("\"").append(" -ss ").append(clip.startTime).append(" -t ").append(clip.duration).append(" -vn -acodec copy -y ")
                .append("\"").append(audioFilePath).append("\"");
        runAnyCommand(context, audioCmd.toString(), "Copying Audio #" + tempIndex);

        clip.setRenderedPath(IOHelper.CombinePath(workingProjectPath, Constants.DEFAULT_CLIP_TEMP_DIRECTORY, tempIndex + ".mp4"));

        StringBuilder cmd = new StringBuilder();
        cmd.append("-framerate ").append(fps).append(" -i ")
                .append("\"").append(IOHelper.CombinePath(workingProjectPath, Constants.DEFAULT_CLIP_TEMP_DIRECTORY, "frames/frame%04d.jpg")).append("\"")
                .append(" -i ").append("\"").append(audioFilePath).append("\"")
                .append(" -c:v libx264 -pix_fmt yuv420p -c:a aac ").append("-y \"").append(clip.getRenderedPath()).append("\"");

        runAnyCommand(context, cmd.toString(), "Building Keyframed Clip #" + tempIndex);

        // Stitch frames into video
//        ProcessBuilder pb = new ProcessBuilder(
//                "ffmpeg", "-framerate", String.valueOf(fps),
//                "-i", "frames/frame%04d.png",
//                "-c:v", "libx264", "-pix_fmt", "yuv420p",
//                clip.getRenderedPath()
//        );
//        pb.inheritIO().start().waitFor();
    }

    public static void renderFrameToBitmap(Context context, String inputPath, String workingProjectPath, float scale, float x, float y, float rotation, int frameIndex) {
        String outputPath = IOHelper.CombinePath(workingProjectPath, Constants.DEFAULT_CLIP_TEMP_DIRECTORY, String.format("frames/frame%04d.jpg", frameIndex));

        StringBuilder vf = new StringBuilder();

        // Frame selection by index
        vf.append("select='eq(n\\,").append(frameIndex).append(")',");

        // Apply scale
        // -vf "scale=ceil(iw/2)*2:ceil(ih/2)*2"
        vf.append("scale=ceil(iw*").append(scale).append("/2)*2").append(":ceil(ih*").append(scale).append("/2)*2").append(",");

        // Apply rotation
        vf.append("rotate=").append(rotation)
                .append(":ow=rotw(").append(rotation).append("):oh=roth(").append(rotation).append(")")
                .append(":fillcolor=0x00000000");

        StringBuilder cmd = new StringBuilder();
        cmd.append("-i ").append("\"").append(inputPath).append("\"")
                .append(" -q:v ").append(10) // Lower = better quality, higher = more compression, ranges from 1 (best quality) to 31 (worst). Try 5–10 for good balance.
                .append(" -vf \"").append(vf).append("\"")
                .append(" -vsync vfr -frames:v 1 -y ")
                .append("\"").append(outputPath).append("\"");

        runAnyCommand(context, cmd.toString(), "Rendering Frame #" + frameIndex);
    }

//    public static void renderFrameToBitmap(Context context, String inputPath, String workingProjectPath, float scale, float x, float y, float rotation, int frameIndex) {
//        String outputPath = IOHelper.CombinePath(workingProjectPath, Constants.DEFAULT_CLIP_TEMP_DIRECTORY, String.format("frames/frame%04d.png", frameIndex));
//
////        String scaleExpr = String.format("scale=iw*%.2f:ih*%.2f", scale, scale);
////        String rotateExpr = String.format("rotate=%.2f:ow=rotw(%.2f):oh=roth(%.2f):fillcolor=0x00000000", rotation, rotation, rotation);
////        String overlayExpr = String.format("overlay=%d:%d", (int)x, (int)y);
//        StringBuilder vf = new StringBuilder();
//
//        vf.append("scale=iw*").append(scale).append(":ih*").append(scale).append(",");
//        vf.append("rotate=").append(rotation).append(":ow=rotw(").append(rotation).append("):oh=roth(").append(rotation).append(")")
//                .append(":fillcolor=0x00000000");
//
//
//        StringBuilder cmd = new StringBuilder();
//        cmd.append("-i ").append("\"").append(inputPath).append("\"").append(" -vf \"").append(vf).append("\" -frames:v 1 ").append("-y ")
//                .append("\"").append(outputPath).append("\"");
//        runAnyCommand(context, cmd.toString());
////        ProcessBuilder pb = new ProcessBuilder(
////                "ffmpeg", "-i", inputPath,
////                "-vf", vf,
////                "-frames:v", "1",
////                outputPath
////        );
////        pb.inheritIO().start().waitFor();
//    }




    public static void generateExportVideo(Context context, EditingActivity.Timeline timeline, EditingActivity.VideoSettings settings, MainActivity.ProjectData data, Runnable onSuccess) {
        runAnyCommand(context, generateExportCmd(context, settings, timeline, data), "Exporting Video", onSuccess, () -> {
        }, new RunnableImpl() {
            @Override
            public <T> void runWithParam(T param) {

            }
        }, new RunnableImpl() {
            @Override
            public <T> void runWithParam(T param) {

            }
        });
    }

    public static String generateExportCmd(Context context, EditingActivity.VideoSettings settings, EditingActivity.Timeline timeline, MainActivity.ProjectData data) {
        FfmpegFilterComplexTags tags = new FfmpegFilterComplexTags();

        StringBuilder cmd = new StringBuilder();
        cmd.append("-f lavfi -i color=c=black:s=")
                .append(settings.getVideoWidth()).append("x").append(settings.getVideoHeight())
                .append(":r=").append(settings.getFrameRate()).append(" -t ").append(timeline.duration).append(" ");

        StringBuilder filterComplex = new StringBuilder();
        StringBuilder audioInputs = new StringBuilder();
        StringBuilder audioMaps = new StringBuilder();

        int inputIndex = 0;
        int audioClipCount = 0;


        int keyframeClipIndex = 0;
        // --- Inserting file path into -i ---
        for (EditingActivity.Track track : timeline.tracks) {
            for (EditingActivity.Clip clip : track.clips) {

                switch (clip.type) {
                    case VIDEO:
                    case IMAGE:
                        cmd.append("-f lavfi -i \"nullsrc=size=")
                                .append(settings.getVideoWidth()).append("x").append(settings.getVideoHeight())
                                .append(":rate=").append(settings.getFrameRate()).append(",format=rgba\"").append(" ");

                        // Since image is a still image, with only one frame. We need to specify it and manipulate it
                        // some how to behave like a video, that way we can use that as a normal video playback
                        // and working with many more effect like transition
                        String frameFilter =
                                clip.type == EditingActivity.ClipType.IMAGE ?
                                        "-loop 1 -t " + clip.duration + " -framerate " + settings.getFrameRate() + " " :
                                        "";

                        // Before going to the input stream, detect if it has a keyframe properties, if it has,
                        // render that video beforehand before entering the stream
                        if (clip.hasAnimatedProperties()) {
                            renderKeyframedClip(context, data.getProjectPath(), clip, keyframeClipIndex, settings.getFrameRate()); // Pre-render to video
                            clip.filePath = clip.getRenderedPath(); // Replace with new video
                            clip.type = EditingActivity.ClipType.VIDEO; // Treat as normal video now
                            keyframeClipIndex++;
                        }



                        cmd.append(frameFilter).append("-i \"").append(clip.filePath).append("\" ");
                        break;
                    case AUDIO:
                        cmd.append("-i \"").append(clip.filePath).append("\" ");
                        break;
                    case TEXT:
                        cmd.append("-f lavfi -i \"nullsrc=size=")
                                .append(settings.getVideoWidth()).append("x").append(settings.getVideoHeight())
                                .append(":rate=").append(settings.getFrameRate()).append(",format=rgba\"").append(" ");
                        break;

                }
            }
        }


        // --- Inputting clips from -i ---
        String baseTag = "[base]";
        filterComplex.append("[").append(inputIndex).append(":v]trim=duration=").append(timeline.duration).append(",setpts=PTS-STARTPTS").append(baseTag).append(";\n");
        tags.storeTag(baseTag);
        inputIndex++;
        for (EditingActivity.Track track : timeline.tracks) {
            for (EditingActivity.Clip clip : track.clips) {

                String clipLabel = "[video-" + inputIndex + "]";
                String transparentLabel = "[trans-" + inputIndex + "]";
                String outputLabel = "[trans-video-" + inputIndex + "]";


                String audioLabel = "[audio-" + inputIndex + "]";



                switch (clip.type) {
                    case VIDEO:
                    case IMAGE:

                        // 🖼️ Video/Image visual logic
                        filterComplex.append("[").append(inputIndex).append(":v]")
                                .append("trim=duration=").append(clip.duration).append(",")
                                .append("setpts=PTS-STARTPTS+").append(clip.startTime).append("/TB").append(transparentLabel).append(";\n");
                        inputIndex++;

                        // Video can use start and end trim, but image cant, so we need to specify the trim for each type.
                        String trimFilter =
                                clip.type == EditingActivity.ClipType.VIDEO ?
                                        "trim=start=" + clip.startClipTrim + ":end=" + (clip.startClipTrim + clip.duration) :
                                        "trim=duration=" + clip.duration;

                        filterComplex.append("[").append(inputIndex).append(":v]")
                                .append("scale=iw*").append(clip.scaleX).append(":ih*").append(clip.scaleY).append(",")
                                .append("rotate=").append(clip.rotation).append(":ow=rotw(").append(clip.rotation).append("):oh=roth(").append(clip.rotation).append(")")
                                .append(":fillcolor=0x00000000").append(",")
                                .append(trimFilter).append(",")
                                .append("setpts=PTS-STARTPTS+").append(clip.startTime).append("/TB").append(clipLabel).append(";\n");


                        filterComplex.append(transparentLabel).append(clipLabel)
                                .append("overlay=").append(clip.posX).append(":").append(clip.posY)
                                .append(":enable='between(t,")
                                .append(clip.startTime).append(",")
                                .append(clip.startTime + clip.duration).append(")'").append(",")
                                .append("fps=").append(settings.getFrameRate())
                                .append(outputLabel).append(";\n");

                        tags.storeTag(clip, outputLabel);
                        break;
                    case TEXT:
                        filterComplex.append("[").append(inputIndex).append(":v]")
                                .append("trim=duration=").append(clip.duration).append(",")
                                .append("setpts=PTS-STARTPTS+").append(clip.startTime).append("/TB").append(transparentLabel).append(";\n");

                        filterComplex.append(transparentLabel)
                                .append("drawtext=").append("fontfile='/system/fonts/DroidSans.ttf'")
                                .append(":fontsize=").append(clip.fontSize)
                                .append(":text='").append(clip.textContent.replace(":", "\\:").replace("'", "\\'"))
                                .append("':x=").append("(w-text_w)/2")//.append(clip.posX) Centralize text
                                .append(":y=").append("(h-text_h)/2")//.append(clip.posY) Centralize text
                                .append(":enable='between(t,").append(clip.startTime).append(",")
                                .append(clip.startTime + clip.duration).append(")'").append(",")
                                .append("fps=").append(settings.getFrameRate())
                                .append(outputLabel).append(";\n");

                        tags.storeTag(clip, outputLabel);
                        break;

                    case AUDIO:
                        // 🎵 Pure audio clip logic
                        int delayMs = (int) (clip.startTime * 1000);
                        filterComplex.append("[").append(inputIndex).append(":a]")
                                .append("atrim=start=").append(clip.startClipTrim).append(":end=").append(clip.startClipTrim + clip.duration).append(",")
                                .append("adelay=").append(delayMs).append("|").append(delayMs).append(",")
                                .append("asetpts=PTS-STARTPTS")
                                .append(audioLabel).append(";\n");

                        audioInputs.append(audioLabel);
                        audioClipCount++;
                        break;
                }

                // 🔊 Handle embedded audio in VIDEO
                if (clip.type == EditingActivity.ClipType.VIDEO) {
                    int delayMs = (int) (clip.startTime * 1000);
                    filterComplex.append("[").append(inputIndex).append(":a]")
                            .append("atrim=start=").append(clip.startClipTrim).append(":end=").append(clip.startClipTrim + clip.duration).append(",")
                            .append("adelay=").append(delayMs).append("|").append(delayMs).append(",")
                            .append("asetpts=PTS-STARTPTS")
                            .append(audioLabel).append(";\n");

                    audioInputs.append(audioLabel);
                    audioClipCount++;
                }

                switch (clip.type) {
                    case VIDEO:
                    case IMAGE:
                    case AUDIO:
                    case TEXT:
                        inputIndex++;
                        break;
                }
            }
        }


        for (EditingActivity.Track track : timeline.tracks) {
            for (EditingActivity.TransitionClip transition : track.transitions) {
                filterComplex.append(FXCommandEmitter.emitTransition(transition, tags));
            }
        }

        for (EditingActivity.Track track : timeline.tracks) {
            for (EditingActivity.Clip clip : track.clips) {

                if (Objects.requireNonNull(clip.type) == EditingActivity.ClipType.EFFECT) {
                    if (clip.effect != null) {
                        filterComplex.append(FXCommandEmitter.emit(clip, tags.useTag(clip), tags)).append("\n");
                    }
                }
            }
        }


        int layer = 0;
        FfmpegFilterComplexTags.FilterComplexInfo baseInfo = tags.useTag(baseTag);
        while(tags.tagsMapToUsableTagIndex.size() > 0)
        {
            EditingActivity.Clip clip = (EditingActivity.Clip) tags.tagsMapToUsableTagIndex.keySet().toArray()[0];

            String prevOutputLabel = "[layer-" + (layer - 1 ) + "]";
            String outputLabel = "[layer-" + layer + "]";

            switch (clip.type) {
                case VIDEO:
                case IMAGE:
                case TEXT:
                    filterComplex.append((layer == 0 ? baseInfo.tag : (tags.useTag(prevOutputLabel).tag))).append(tags.useTag(clip).tag)
                            .append("overlay=")
                            .append("enable='between(t,")
                            .append(clip.startTime).append(",")
                            .append(clip.startTime + clip.duration).append(")'").append(outputLabel).append(";\n");

                    tags.storeTag(outputLabel);

                    layer++;
                    break;
            }
        }

        String finalTag = "";
        layer = 0;

        while(tags.usableTag.size() > 1)
        {
            String outputLabel = "[leftover-layer-" + layer + "]";
            finalTag = tags.usableTag.get(0);
            if(tags.usableTag.size() > 2)
            {
                String upperTag = tags.usableTag.get(1);
                tags.useTag(finalTag);
                tags.useTag(upperTag);
                filterComplex.append(finalTag).append(upperTag)
                        .append("overlay").append(outputLabel).append(";\n");
                tags.storeTag(outputLabel);
            }
            else break;
        }


        // 🔁 Mix audio if present
        if (audioClipCount > 0) {
            filterComplex.append(audioInputs)
                    .append("amix=inputs=").append(audioClipCount).append("[aout];\n");
            audioMaps.append("-map \"[aout]\" ");
        } else {
            audioMaps.append("-an "); // 🧘 No audio at all
        }

        cmd.append("-filter_complex \"").append(filterComplex).append("\" ")
                .append("-map \"").append(tags.useTag(0).tag).append("\" ")
                .append(audioMaps)
                .append("-t ").append(timeline.duration)
                .append(" -c:v libx264 -preset ").append(settings.getPreset())
                .append(" -tune ").append(settings.getTune())
                .append(" -crf ").append(settings.getCRF())
                .append(" -y ").append(IOHelper.CombinePath(data.getProjectPath(), Constants.DEFAULT_EXPORT_CLIP_FILENAME));

        return cmd.toString();
    }










    public static class FfmpegFilterComplexTags {
        private final ArrayList<String> usableTag = new ArrayList<>();
        private final Map<EditingActivity.Clip, String> tagsMapToUsableTagIndex = new HashMap<>();
        private final Map<EditingActivity.Clip, EditingActivity.Clip> tagsMergedClipMap = new HashMap<>();

        public FilterComplexInfo useTag(int index) {
            if(index < 0 || index >= usableTag.size()) return null;

            String retrieveTag = usableTag.get(index);
            FilterComplexInfo info = new FilterComplexInfo(index, retrieveTag);

            usableTag.remove(index);
            return info;
        }
        public FilterComplexInfo useTag(String tag) {
            if(usableTag.contains(tag))
            {
                int indexTag = usableTag.indexOf(tag);
                FilterComplexInfo info = new FilterComplexInfo(indexTag, tag);
                usableTag.remove(indexTag);
                return info;
            }
            return null;
        }
        public void storeTag(String tag) {
            usableTag.add(tag);
        }
        public void storeTag(String tag, int index) {
            usableTag.add(index, tag);
        }


        public FilterComplexInfo useTag(EditingActivity.Clip key) {
            if(usableTag.contains(tagsMapToUsableTagIndex.get(key)))
            {
                String retrieveTag = tagsMapToUsableTagIndex.get(key);
                int indexTag = usableTag.indexOf(retrieveTag);
                FilterComplexInfo info = new FilterComplexInfo(indexTag, retrieveTag);

                usableTag.remove(indexTag);
                tagsMapToUsableTagIndex.remove(key);
                return info;
            }
//            else if(tagsMergedClipMap.containsKey(key) && usableTag.contains(tagsMapToUsableTagIndex.get(tagsMergedClipMap.get(key))))
//            {
//                useTag(tagsMergedClipMap.get(key));
//                tagsMergedClipMap.remove(key);
//            }
            return null;
        }
        public FilterComplexInfo useTag(EditingActivity.Clip key, EditingActivity.Clip mergingKey) {
            if(usableTag.contains(tagsMapToUsableTagIndex.get(key)))
            {
                String retrieveTag = tagsMapToUsableTagIndex.get(key);
                int indexTag = usableTag.indexOf(retrieveTag);
                FilterComplexInfo info = new FilterComplexInfo(indexTag, retrieveTag);

                usableTag.remove(indexTag);
                tagsMapToUsableTagIndex.remove(key);

                tagsMergedClipMap.put(key, mergingKey);
                return info;
            }
            return null;
        }
        public void storeTag(EditingActivity.Clip key, String tag) {
            usableTag.add(tag);

            tagsMapToUsableTagIndex.put(key, tag);
        }
        public void storeTag(EditingActivity.Clip key, String tag, int index) {
            if(index < 0) index = 0;
            if(index >= usableTag.size()) index = usableTag.size() - 1;
            usableTag.add(index, tag);

            tagsMapToUsableTagIndex.put(key, tag);
        }


        public EditingActivity.Clip getKeyFromTag(String tag)
        {
            for (Map.Entry<EditingActivity.Clip, String> entry : tagsMapToUsableTagIndex.entrySet()) {
                if (entry.getValue().equals(tag)) {
                    return entry.getKey();
                }
            }
            return null;
        }


        public EditingActivity.Clip getValidMapKey(EditingActivity.Clip clipKey)
        {
            if(tagsMapToUsableTagIndex.containsKey(clipKey))
                return clipKey;
            if(tagsMergedClipMap.containsKey(clipKey))
                return tagsMergedClipMap.get(clipKey);
            return null;
        }




        static class FilterComplexInfo {
            public int index;
            public String tag;
            public FilterComplexInfo(int index, String tag)
            {
                this.index = index;
                this.tag = tag;
            }
        }
    }

    public static class FfmpegRenderQueue {
        public FfmpegRenderQueueInfo currentRenderQueue;
        private final Queue<FfmpegRenderQueueInfo> taskQueue = new LinkedList<>();
        public boolean isRunning = false;

        public int totalQueue = 0, queueDone = 0;

        public void enqueue(FfmpegRenderQueueInfo task) {
            taskQueue.add(task);
            totalQueue++;
            if (!isRunning) {
                runNext();
            }
        }

        private void runNext() {
            FfmpegRenderQueueInfo task = taskQueue.poll();
            if (task == null) {
                isRunning = false;
                totalQueue = 0;
                queueDone = 0;
                return;
            }
            currentRenderQueue = task;

            isRunning = true;
            queueDone++;
            task.task.run(); // Each task must call runNext() when done
        }

        public void taskCompleted() {
            runNext();
        }
        public void cancelAllTask()
        {
            isRunning = false;
            taskQueue.clear();
            totalQueue = 0;
            queueDone = 0;
            FFmpegKit.cancel();
        }




        public static class FfmpegRenderQueueInfo {
            Runnable task;
            public String taskName;



            RunnableImpl onStatisticBase = new RunnableImpl() {
                @Override
                public <T> void runWithParam(T param) {
                    Statistics stats = (Statistics) param;

//                    double duration = stats.get;

                    Statistics statistics = (Statistics) param;
                    {
                        if (statistics.getTime() > 0) {
//                            int progress = (int) ((statistics.getTime() * 100) / (int) duration);
//                            statusBar.setMax(100);
//                            statusBar.setProgress(progress);
                        }
                    }
                }
            };
            RunnableImpl onLog;

            public FfmpegRenderQueueInfo(String taskName, Runnable task)
            {
                this.task = task;
                this.taskName = taskName;
            }



        }

    }

}