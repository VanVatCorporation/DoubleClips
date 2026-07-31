// TODO: CapCut transition in the way that in a 2s transition, it take 0.6s of the end of clipA and 1.4s of the start of clipB, if the clip is cut from the end
//  in our case it's endClipTrim, then it extend to the 0.6s to match the lost clip from transition merging. If it does not have enough endClipTrim,
//  e.g. 0.3s trim only from clipA then it just freeze the frame fro another 0.3s, if it does not have any endClipTrim at all, then the entire 0.6s
//  will be the last frame of clipA, entirely
//  Audio will be merged too
//  .
//  We uses ffmpeg so it should be 1s equally for both clip. So in order to do it, we will need to get the transition duration before the FXCommandEmmiter.java.
//  which then add the "tpad=stop_mode=clone:stop_duration=n" with n is the half of transition duration to the clipA before transition. No need to set "apad=pad_dur=2"
//  for audio-filter-complex because it will not output anything once the sound run out
//  .
//  .
//  .
//  AI Link: https://copilot.microsoft.com/shares/nw39hkpxpiAxGq55Hy5xa



// TODO: Rewritten by AI:






// TODO: Implement a CapCut-style transition where, in a 2s transition, 0.6s comes from the end of clipA and
//  1.4s from the start of clipB. If the cut is at the end (endClipTrim), extend it by 0.6s to make up for
//  what’s lost during the merge. If endClipTrim is less than 0.6s (e.g., 0.3s), freeze the last frame for
//  the remaining 0.3s. If there’s no endClipTrim, use the last frame of clipA for the full 0.6s.
//  Audio should be merged as well.
//  .
//  Using ffmpeg, the transition should be 1s from each clip. To achieve this, get the transition
//  duration before FXCommandEmitter.java, then add "tpad=stop_mode=clone:stop_duration=n" to clipA
//  before the transition, where n is half the transition duration. No need to use "apad=pad_dur=2"
//  for audio-filter-complex since it won’t output anything once the sound ends.


package com.vanvatcorporation.doubleclips;

import static com.vanvatcorporation.doubleclips.FFmpegEditNative.hardwareAcceleratedName;

import android.content.Context;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Statistics;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.activities.main.MainAreaScreen;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.impl.java.ArrayListImpl;
import com.vanvatcorporation.doubleclips.impl.java.RunnableImpl;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executors;

public class FFmpegEdit {
    public static FfmpegRenderQueue queue = new FfmpegRenderQueue();
    public static void runAnyCommand(Context context, String cmd, String taskName) {
        runAnyCommand(context, cmd, taskName, "Ran command!", "Command failed: ", true);
    }

    public static void runAnyCommand(Context context, String cmd, String taskName,
                                     Runnable onSuccessRunnable, Runnable onFailRunnable,
                                     RunnableImpl onLogRunnable, RunnableImpl onStatisticsRunnable) {
        FFmpegEditNative.runAnyCommand(context, cmd, taskName, "Ran command!", "Command failed: ", true, onSuccessRunnable, onFailRunnable, onLogRunnable, onStatisticsRunnable);
    }

    public static void runAnyCommand(Context context, String cmd, String taskName, String successMessage, String failMessage, boolean includeFullReport) {
        FFmpegEditNative.runAnyCommand(context, cmd, taskName, successMessage, failMessage, includeFullReport, () -> {
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





    public static void generateSolidColorImage(Context context, String projectClipPath, String colorHex)
    {
        String emptyImagePath = IOHelper.getNextIndexPathInFolder(context, IOHelper.CombinePath(projectClipPath), "solid_color_", ".png", false);
        runAnyCommand(context,
                "-f lavfi -i color=c=#" + colorHex + ":s=100x100 -frames:v 1 \"" + emptyImagePath + "\"",
                "Solid Image Generation");
    }


    public static String generateExportCmdPartially(Context context, RenderSettings renderSettings,
                                                    int clipCount, int clipOffset) {
        EditingActivity.Clip[] clips = new EditingActivity.Clip[clipCount];
        int currentClipCount = 0;
        for (EditingActivity.Track track : renderSettings.timeline.tracks) {
            if(currentClipCount >= clipCount) break;
            for (EditingActivity.Clip clip : track.clips) {
                if(currentClipCount >= clipCount) break;

                if(clipOffset > 0) {
                    clipOffset--;
                    continue;
                }

                clips[currentClipCount] = clip;
                currentClipCount++;
            }
        }

        renderSettings.setClips(clips);

        return generateExportCmdPartially(context, renderSettings);
    }

    public static String generateExportCmdPartially(Context context, RenderSettings templateSettings) {

        FfmpegFilterComplexTags tags = new FfmpegFilterComplexTags();

        StringBuilder cmd = new StringBuilder();

        // Use the beginning as base
        if(templateSettings.renderingIndex > 0)
        {
            String previousRenderedClipPath = IOHelper.CombinePath(templateSettings.data.getProjectPath(), ((templateSettings.renderingIndex - 1) + "_") + Constants.DEFAULT_EXPORT_CLIP_FILENAME);

            cmd.append(templateSettings.settings.isUseHardwareAccel() ? "-hwaccel " + hardwareAcceleratedName + " " : "").append("-i \"").append(previousRenderedClipPath).append("\" ");

        }
        else {
            cmd.append("-f lavfi -i color=c=black:s=")
                    .append(templateSettings.settings.getRenderVideoWidth(templateSettings.isTemplateCommand)).append("x").append(templateSettings.settings.getRenderVideoHeight(templateSettings.isTemplateCommand))
                    .append(":r=").append(templateSettings.settings.getFrameRate()).append(" -t ").append(templateSettings.timeline.duration).append(" ");
        }




        StringBuilder filterComplex = new StringBuilder();
        StringBuilder audioInputs = new StringBuilder();
        StringBuilder audioMaps = new StringBuilder();

        int inputLayerIndex = 0;
        int inputMediaIndex = 0;
        int audioClipCount = 0;


        int keyframeClipIndex = 0;
        // --- Inserting file path into -i ---

        for (int i = 0; i < templateSettings.clips.length; i++) {
            EditingActivity.Clip clip = templateSettings.clips[i];

            String inputPath;
            if (templateSettings.isTemplateCommand && clip.isLockedForTemplate()) {
                inputPath = Constants.DEFAULT_TEMPLATE_CLIP_STATIC_MARK(clip.getClipName());
            } else if (templateSettings.isTemplateCommand) {
                inputPath = Constants.DEFAULT_TEMPLATE_CLIP_MARK(i);
            } else if (clip.removeBackground && clip.type == EditingActivity.ClipType.IMAGE && IOHelper.isFileExist(clip.getCutoutPath(templateSettings.data.getProjectPath()))) {
                inputPath = clip.getCutoutPath(templateSettings.data.getProjectPath());
            } else {
                inputPath = clip.getAbsolutePath(templateSettings.data);
            }

            switch (clip.type) {
                case VIDEO:
                case IMAGE:
                    cmd.append("-f lavfi -i \"nullsrc=size=")
                            .append(templateSettings.settings.getRenderVideoWidth(templateSettings.isTemplateCommand)).append("x").append(templateSettings.settings.getRenderVideoHeight(templateSettings.isTemplateCommand))
                            .append(":rate=").append(templateSettings.settings.getFrameRate()).append(",format=yuva420p\"").append(" ");

                    String frameFilter =
                            clip.type == EditingActivity.ClipType.IMAGE ?
                                    "-loop 1 -t " + clip.duration + " -framerate " + templateSettings.settings.getFrameRate() + " " :
                                    "";

                    // For VIDEO clips, add hwaccel if enabled; IMAGE/SCENE frames do not use MediaCodec
                    boolean addHwAccel = clip.type == EditingActivity.ClipType.VIDEO
                            && templateSettings.settings.isUseHardwareAccel()
                            && !templateSettings.isTemplateCommand;
                    cmd.append(templateSettings.isTemplateCommand ? "" : frameFilter)
                            .append(addHwAccel ? "-hwaccel " + hardwareAcceleratedName + " " : "")
                            .append("-i \"").append(inputPath).append("\" ");

                    if (clip.type == EditingActivity.ClipType.VIDEO && clip.removeBackground) {
                        String maskPath = clip.getCutoutPath(templateSettings.data.getProjectPath()) + ".mp4";
                        if (IOHelper.isFileExist(maskPath)) {
                            cmd.append(templateSettings.settings.isUseHardwareAccel() ? "-hwaccel " + hardwareAcceleratedName + " " : "")
                                    .append("-i \"").append(maskPath).append("\" ");
                        }
                    }
                    break;
                case SCENE_3D:
                    cmd.append("-f lavfi -i \"nullsrc=size=")
                            .append(templateSettings.settings.getRenderVideoWidth(templateSettings.isTemplateCommand)).append("x").append(templateSettings.settings.getRenderVideoHeight(templateSettings.isTemplateCommand))
                            .append(":rate=").append(templateSettings.settings.getFrameRate()).append(",format=yuva420p\"").append(" ");

                    String scenePath = IOHelper.CombinePath(templateSettings.data.getProjectPath(), "temp_scenes", clip.getClipName().replace(".", "_")) + "/frame_%05d.png";
                    cmd.append("-framerate ").append(templateSettings.settings.getFrameRate()).append(" -i \"").append(scenePath).append("\" ");
                    break;
                case AUDIO:
                    cmd.append("-i \"").append(inputPath).append("\" ");
                    break;
                case TEXT:
                    cmd.append("-f lavfi -i \"nullsrc=size=")
                            .append(templateSettings.settings.getRenderVideoWidth(templateSettings.isTemplateCommand)).append("x").append(templateSettings.settings.getRenderVideoHeight(templateSettings.isTemplateCommand))
                            .append(":rate=").append(templateSettings.settings.getFrameRate()).append(",format=yuva420p\"").append(" ");
                    break;

            }
        }



        // --- Inputting clips from -i ---
        String baseTag = "[base]";
        filterComplex.append("[").append(inputLayerIndex).append(":v]trim=duration=").append(templateSettings.timeline.duration).append(",setpts=PTS-STARTPTS").append(baseTag).append(";\n");
        tags.storeTag(baseTag);
        inputLayerIndex++;
        inputMediaIndex++;

        for (int clipIndex = 0; clipIndex < templateSettings.clips.length; clipIndex++) {
            EditingActivity.Clip clip = templateSettings.clips[clipIndex];

            String clipLabel = "[video-" + inputMediaIndex + "]";
            String transparentLabel = "[trans-" + inputMediaIndex + "]";
            String outputLabel = "[trans-video-" + inputMediaIndex + "]";


            String audioLabel = "[audio-" + inputMediaIndex + "]";


            // Transition extension
            // First we find the exact clip associated with the TransitionClip's clipA
            // Then extract the half duration, we don't need the rest for now at least
            // This for loop may inefficient, but it works! Optimize this later
            // TODO: Optimize the search.
            float fillingTransitionDuration = 0;

            if (clip.isClipTransitionAvailable() && !clip.endTransition.effect.style.equals("none")) {
                switch (clip.endTransition.mode) {
                    case END_FIRST:
                        // 0. End first mean the moment the second clip begin, the fade has completed, so we
                        // doesnt need filling as we begin the transition at the clipA entirely
                        fillingTransitionDuration = 0;
                        break;
                    case OVERLAP:
                        // Duration / 2. Overlap mean half of clipA and half of clipB are join together, we only need
                        // to fill half the clipA as clipB is already get the half.
                        fillingTransitionDuration = clip.endTransition.duration / 2;
                        break;
                    case BEGIN_SECOND:
                        // Duration. Begin second mean the opposite to end first. The moment the second clip begin,
                        // its when the transition begin, so we need to fill all of the duration that's going to fade
                        fillingTransitionDuration = clip.endTransition.duration;
                        break;

                }
            }


            // Because we based on availability of endClipTrim, we first get the few parameters
            // correct adding (extendMediaDuration) and freeze frame duration (freezeFrameDuration)

            // extendMediaDuration: We get the minimum of the clip to extend, if endClipTrim has more than filling
            // then we just take the half duration of transition to extend.
            // if filling is more than the available of clip, which is endClipTrim, then we only extend to the maximum duration of the clip,
            // that mean endClipTrim is meaningless.
            float extendMediaDuration = Math.min(clip.endClipTrim, fillingTransitionDuration);

            // freezeFrameDuration: We get the max value of these 2 variable ( fillingTransitionDuration - clip.endClipTrim and 0 )
            // fillingTransitionDuration - clip.endClipTrim will get the remaining duration after the clip extend all of it endClipTrim
            // Why 0? If the subtraction is negative then it has no freeze frame because there is still enough endClipTrim to extend.
            float freezeFrameDuration = Math.max(fillingTransitionDuration - clip.endClipTrim, 0);


            switch (clip.type) {
                case SCENE_3D:
                case VIDEO:
                case IMAGE:

//                    String speedCmd;
//                    if(clip.hasAnimatedProperties()) {
//                        speedCmd = getKeyframeFFmpegExpr(clip.keyframes.keyframes, clip, 0, EditingActivity.VideoProperties.ValueType.Speed);
//                    } else {
//                        speedCmd = String.valueOf(clip.videoProperties.getValue(EditingActivity.VideoProperties.ValueType.Speed));
//                    }


                    // 🖼️ Video/Image visual logic
                    // Transition extension: Add half of the duration to the transparent layer, if transition isn't exist, then add 0
                    filterComplex.append("[").append(inputLayerIndex).append(":v]")
                            .append("trim=duration=").append(clip.duration + fillingTransitionDuration).append(",")
                            .append("setpts=PTS-STARTPTS+").append(clip.startTime).append("/TB").append(transparentLabel).append(";\n");
                    inputLayerIndex++;

                    int sourceInputIndex = inputLayerIndex;
                    inputLayerIndex++;

                    boolean hasVideoMask = false;
                    int maskInputIndex = -1;
                    if (clip.type == EditingActivity.ClipType.VIDEO && clip.removeBackground) {
                        String maskPath = clip.getCutoutPath(templateSettings.data.getProjectPath()) + ".mp4";
                        if (IOHelper.isFileExist(maskPath)) {
                            hasVideoMask = true;
                            maskInputIndex = inputLayerIndex;
                            inputLayerIndex++;
                        }
                    }

                    // Video can use start and end trim, but image cant, so we need to specify the trim for each type.
                    String trimFilter =
                            templateSettings.isTrimAllowed ?
                                    Constants.DEFAULT_TEMPLATE_TRIM_MARK(clipIndex) :
                                    clip.type == EditingActivity.ClipType.VIDEO ?
                                            "trim=start=" + clip.startClipTrim + ":end=" + (clip.startClipTrim + clip.duration + extendMediaDuration) :
                                            "trim=duration=" + (clip.duration + fillingTransitionDuration);

                    // First we declared the stream of video
                    if (hasVideoMask) {
                        filterComplex.append("[").append(sourceInputIndex).append(":v]").append(trimFilter).append(",setpts=PTS-STARTPTS[v-raw-").append(clipIndex).append("];\n")
                                     .append("[").append(maskInputIndex).append(":v]").append(trimFilter).append(",setpts=PTS-STARTPTS[v-mask-").append(clipIndex).append("];\n")
                                     .append("[v-raw-").append(clipIndex).append("][v-mask-").append(clipIndex).append("]alphamerge,");
                    } else {
                        filterComplex.append("[").append(sourceInputIndex).append(":v]").append(trimFilter).append(",");
                    }


                    // Let simulating 4 keyframe type in opacity for example:
                    // K #1: 1 at 1s
                    // K #2: 0 at 2s
                    // K #3: 0 at 3s
                    // K #4: 1 at 4s
                    // Kinda like --\_/--   (\ and _ and / are actually 3 lines created from 4 points)

                    // gte(t,5)*lte(t,10)
                    //colorchannelmixer=aa='if(gte(t,1)*lte(t,2), exp(-0.5*(t-3)), if(gte(t,2)*lte(t,3), 0, if(gte(t,3)*lte(t,4)), 1-exp(-1*t), 1))'


                    // Detect keyframe after which we write our expr compilation
                    // In this first if expr: We process scaleX, scaleY, rot, opacity, speed
                    if (clip.hasAnimatedProperties()) {


                        String scaleXExpr = getKeyframeFFmpegExpr(clip.keyframes.keyframes, clip, 0, EditingActivity.VideoProperties.ValueType.ScaleX);
                        String scaleYExpr = getKeyframeFFmpegExpr(clip.keyframes.keyframes, clip, 0, EditingActivity.VideoProperties.ValueType.ScaleY);


                        String opacityExpr = getKeyframeFFmpegExpr(clip.keyframes.keyframes, clip, 0, EditingActivity.VideoProperties.ValueType.Opacity);
                        String speedExpr = getKeyframeFFmpegExpr(clip.keyframes.keyframes, clip, 0, EditingActivity.VideoProperties.ValueType.Speed);

                        String rotationExpr = getKeyframeFFmpegExpr(clip.keyframes.keyframes, clip, 0, EditingActivity.VideoProperties.ValueType.RotInRadians);

                        String hueExpr = getKeyframeFFmpegExpr(clip.keyframes.keyframes, clip, 0, EditingActivity.VideoProperties.ValueType.Hue);
                        String saturationExpr = getKeyframeFFmpegExpr(clip.keyframes.keyframes, clip, 0, EditingActivity.VideoProperties.ValueType.Saturation);
                        String brightnessExpr = getKeyframeFFmpegExpr(clip.keyframes.keyframes, clip, 0, EditingActivity.VideoProperties.ValueType.Brightness);
                        String temperatureExpr = getKeyframeFFmpegExpr(clip.keyframes.keyframes, clip, 0, EditingActivity.VideoProperties.ValueType.Temperature);

//                        String scaleXCmd = templateSettings.settings.isStretchToFull() ?
//                                String.valueOf(templateSettings.settings.getRenderVideoWidth(templateSettings.isTemplateCommand)) :
//                                "iw*" + clip.videoProperties.getValue(EditingActivity.VideoProperties.ValueType.ScaleX);
//                        String scaleYCmd = templateSettings.settings.isStretchToFull() ?
//                                String.valueOf(templateSettings.settings.getRenderVideoHeight(templateSettings.isTemplateCommand)) :
//                                "ih*" + clip.videoProperties.getValue(EditingActivity.VideoProperties.ValueType.ScaleY);

                        String scaleXStretchExpr = (templateSettings.settings.isStretchToFull() ?
                                String.valueOf(templateSettings.settings.getRenderVideoWidth(templateSettings.isTemplateCommand)) :
                                "iw") + "*" + scaleXExpr;
                        String scaleYStretchExpr = (templateSettings.settings.isStretchToFull() ?
                                String.valueOf(templateSettings.settings.getRenderVideoHeight(templateSettings.isTemplateCommand)) :
                                "ih") + "*" + scaleYExpr;
//
//                        String scaleZoompan = templateSettings.settings.isStretchToFull() ? ":s=" + scaleXCmd + "x" + scaleYCmd : "";

                        filterComplex
                                .append("setpts='(PTS-STARTPTS)/").append(speedExpr).append("+").append(clip.startTime).append("/TB'").append(",")
                                .append("scale=w='").append(scaleXStretchExpr).append("':h='").append(scaleYStretchExpr).append("':eval=frame,")
//                                .append("pad=width=").append("'iw'").append(":height=").append("'ih'").append(":x=-1:y=-1:color=black:eval=frame,")
//                                .append("pad=width=max(iw\\,ih*(16/9)):height=ow/(16/9):x=(ow-iw)/2:y=(oh-ih)/2:eval=frame,")
                                //.append("crop=iw:ih:(iw-ow)/2:(ih-oh)/2,")
                                //.append("scale=").append(clip.width).append(":").append(clip.height).append(",")
                                .append("rotate='").append(rotationExpr).append("':ow=rotw('").append(rotationExpr).append("'):oh=roth('").append(rotationExpr).append("')")
                                // TODO: FillColor is not applied yet. Add FillColor to each clip as "Background Fill Color".
                                .append(":fillcolor=0x00000000").append(",")
                                .append("hue=h='").append(hueExpr)
                                .append("':s='").append(saturationExpr)
                                .append("':b='").append(brightnessExpr).append("',")
                                .append("colortemperature=temperature='").append(clip.videoProperties.getValue(EditingActivity.VideoProperties.ValueType.Temperature)).append("',")
                               // .append("zoompan=z=zoom*'").append(scaleXExpr).append("':d=1:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'").append(scaleZoompan).append(",")
                                // TODO: Use geq is super slow. Research a better way, like only render affected frame.
                                .append("format=yuva420p,geq=r='r(X,Y)':a='alpha(X,Y)*").append(opacityExpr).append("',");
                    } else {
                        // If possible then merge the keyframe to clip
                        clip.mergingVideoPropertiesFromSingleKeyframe();

                        // FFmpeg uses radians rotation, so...
                        double radiansRotation = clip.videoProperties.getValue(EditingActivity.VideoProperties.ValueType.RotInRadians);
                        // And then add to filterComplex no matter
                        // the clip has merge or there are no keyframe to combine

                        String scaleXCmd = templateSettings.settings.isStretchToFull() ?
                                String.valueOf(templateSettings.settings.getRenderVideoWidth(templateSettings.isTemplateCommand)) :
                                "iw*" + clip.videoProperties.getValue(EditingActivity.VideoProperties.ValueType.ScaleX);
                        String scaleYCmd = templateSettings.settings.isStretchToFull() ?
                                String.valueOf(templateSettings.settings.getRenderVideoHeight(templateSettings.isTemplateCommand)) :
                                "ih*" + clip.videoProperties.getValue(EditingActivity.VideoProperties.ValueType.ScaleY);
                        filterComplex.append("scale=").append(scaleXCmd).append(":").append(scaleYCmd).append(",")                                //.append("scale=").append(clip.width).append(":").append(clip.height).append(",")
                                .append("rotate=").append(radiansRotation).append(":ow=rotw(").append(radiansRotation).append("):oh=roth(").append(radiansRotation).append(")")
                                .append(":fillcolor=0x00000000").append(",")
                                .append("hue=h=").append(clip.videoProperties.getValue(EditingActivity.VideoProperties.ValueType.Hue))
                                .append(":s=").append(clip.videoProperties.getValue(EditingActivity.VideoProperties.ValueType.Saturation))
                                .append(":b=").append(clip.videoProperties.getValue(EditingActivity.VideoProperties.ValueType.Brightness)).append(",")
                                .append("colortemperature=temperature=").append(clip.videoProperties.getValue(EditingActivity.VideoProperties.ValueType.Temperature)).append(",")
                                .append("format=yuva420p,colorchannelmixer=aa=").append(clip.videoProperties.getValue(EditingActivity.VideoProperties.ValueType.Opacity)).append(",")
                                .append("setpts='(PTS-STARTPTS)/").append(clip.videoProperties.getValue(EditingActivity.VideoProperties.ValueType.Speed)).append("+").append(clip.startTime).append("/TB'").append(",");
                    }


                    filterComplex
                            // Transition extension: If there has freeze frames, then this line will handle it.
                            .append("tpad=stop_mode=clone:stop_duration=").append(freezeFrameDuration);

                    // TODO: Dynamic Ffmpeg command for transition, with custom resource.
                    //  Deliver with .zip pack, upload transition API, contains .gif for display.
                    //  which FFmpeg does support.
                    //  For now it's hardcoded.
                    // 🎬 Handle "In" Animations
                    if (clip.inAnimation != null && !"none".equals(clip.inAnimation.type)) {
                        if ("unfold".equals(clip.inAnimation.type)) {
                            float dur = clip.inAnimation.duration;
                            float fps = templateSettings.settings.getFrameRate();
                            float durFrames = dur * fps;
                            String durFramesStr = String.valueOf(durFrames);
                            String cond = getConditionTwo("in", "<=", durFramesStr);
                            
                            // progress p = in / durFrames
                            String p = "(in/" + durFramesStr + ")";
                            
                            // Center expansion logic:
                            // x0: W/2*(1-p) -> 0
                            // y0: H/2*(1-p) -> 0
                            // x1: W/2 + W/2*p -> W
                            // y1: H/2*(1-p) -> 0
                            // x2: W/2*(1-p) -> 0
                            // y2: H/2 + H/2*p -> H
                            // x3: W/2 + W/2*p -> W
                            // y3: H/2 + H/2*p -> H
//
//                            String x0Expr = getIfExpr(cond, "W/2*(1-" + p + ")", "0");
//                            String y0Expr = getIfExpr(cond, "H/2*(1-" + p + ")", "0");
//                            String x1Expr = getIfExpr(cond, "W/2+W/2*" + p, "W");
//                            String y1Expr = getIfExpr(cond, "H/2*(1-" + p + ")", "0");
//                            String x2Expr = getIfExpr(cond, "W/2*(1-" + p + ")", "0");
//                            String y2Expr = getIfExpr(cond, "H/2+H/2*" + p, "H");
//                            String x3Expr = getIfExpr(cond, "W/2+W/2*" + p, "W");
//                            String y3Expr = getIfExpr(cond, "H/2+H/2*" + p, "H");


//                            String x0Expr = getIfExpr(cond, "W/8-W/8*(1-" + p + ")", "0");
//                            String y0Expr = getIfExpr(cond, "H/2*(1-" + p + ")", "0");
//                            String x1Expr = getIfExpr(cond, "W/2+W/2*" + p, "W");
//                            String y1Expr = getIfExpr(cond, "H/2*(1-" + p + ")", "0");
//                            String x2Expr = getIfExpr(cond, "W/2*(1-" + p + ")", "0");
//                            String y2Expr = getIfExpr(cond, "H/2+H/2*" + p, "H");
//                            String x3Expr = getIfExpr(cond, "W/2+W/2*" + p, "W");
//                            String y3Expr = getIfExpr(cond, "H/2+H/2*" + p, "H");


//                            String x0Expr = getIfExpr(cond, "W/8 - W/8*" + p, "0");
//                            String y0Expr = getIfExpr(cond, "(H/8)*(1 - 4*" + p + " + 3*" + p + "*" + p + ")", "0");
//                            String x1Expr = getIfExpr(cond, "W/2 + W/2*" + p, "W");
//                            String y1Expr = getIfExpr(cond, "(H/8)*(1 - 4*" + p + " + 3*" + p + "*" + p + ")", "0");
//                            String x2Expr = getIfExpr(cond, "(W/2)*(1 - 2*" + p + " + " + p + "*" + p + ")", "0");
//                            String y2Expr = getIfExpr(cond, "H/2 + H/2*" + p, "H");
//                            String x3Expr = getIfExpr(cond, "W/2 + W/2*" + p, "W");
//                            String y3Expr = getIfExpr(cond, "H/2 + H/2*" + p, "H");



                            String dx = "W/12";   // corner offset W/8
                            String dy = "H/12";   // corner offset H/8
//                            String hx = "W/2";    // softened half width
//                            String hy = "H/2";    // softened half height
                            String leftRatio = "W/6";   // tweakable
                            String rightRatio = "5*W/6"; // tweakable
                            String topRatio = "H/6";    // tweakable
                            String bottomRatio = "5*H/6"; // tweakable


                            String x0Expr = getIfExpr(cond, dx + " - " + dx + "*" + p, "0");
                            String y0Expr = getIfExpr(cond, "(" + dy + ")*(1 - 4*" + p + " + 3*" + p + "*" + p + ")", "0");

                            String x1Expr = getIfExpr(cond, rightRatio + " + " + leftRatio + "*" + p, "W");
                            String y1Expr = getIfExpr(cond, "(" + dy + ")*(1 - 4*" + p + " + 3*" + p + "*" + p + ")", "0");

                            String x2Expr = getIfExpr(cond, "(" + leftRatio + ")*(1 - 2*" + p + " + " + p + "*" + p + ")", "0");
                            String y2Expr = getIfExpr(cond, bottomRatio + " + " + topRatio + "*" + p, "H");

                            String x3Expr = getIfExpr(cond, rightRatio + " + " + topRatio + "*" + p, "W");
                            String y3Expr = getIfExpr(cond, bottomRatio + " + " + leftRatio + "*" + p, "H");



                            filterComplex.append(",perspective=eval=frame:")
                                    .append("x0='").append(x0Expr).append("':")
                                    .append("y0='").append(y0Expr).append("':")
                                    .append("x1='").append(x1Expr).append("':")
                                    .append("y1='").append(y1Expr).append("':")
                                    .append("x2='").append(x2Expr).append("':")
                                    .append("y2='").append(y2Expr).append("':")
                                    .append("x3='").append(x3Expr).append("':")
                                    .append("y3='").append(y3Expr).append("'");
                            
                            // Fade from white
                            filterComplex.append(",fade=in:st=").append(clip.startTime).append(":d=").append(dur).append(":color=white");
                        }
                    }

                    filterComplex.append(clipLabel).append(";\n");
                    // TODO: For robust speed control
                    //'
                    //    if(between(T,0,1.5),
                    //       (PTS-STARTPTS)/(1+exp(-k*(T-0.75))),
                    //       if(between(T,1.5,3.5),
                    //          (PTS-STARTPTS)/2,
                    //          (PTS-STARTPTS)/(1+exp(-k*(5-T))))
                    //    ) + 3/TB
                    //'


                    // Transition extension: because overlay are just like transparent layer so we add the raw fillingTransitionDuration
                    filterComplex.append(transparentLabel).append(clipLabel);

                    // In this second if expr: We process posX, posY
                    if (clip.hasAnimatedProperties()) {

                        String posXExpr = getKeyframeFFmpegExpr(clip.keyframes.keyframes, clip, 0, EditingActivity.VideoProperties.ValueType.PosX);
                        String posYExpr = getKeyframeFFmpegExpr(clip.keyframes.keyframes, clip, 0, EditingActivity.VideoProperties.ValueType.PosY);

                        filterComplex.append("overlay='").append(posXExpr).append("':'").append(posYExpr).append("'");
                    } else {
                        // Because we already merged from the first if expr, we don't have to do it here
                        //clip.mergingVideoPropertiesFromSingleKeyframe();


                        filterComplex.append("overlay=").append(clip.videoProperties.getValue(EditingActivity.VideoProperties.ValueType.PosX)).append(":").append(clip.videoProperties.getValue(EditingActivity.VideoProperties.ValueType.PosY));

                    }



                    StringBuilder additionCmd = new StringBuilder();
                    if(clip.additionalFFmpegCommand != null) {
                        if(!clip.additionalFFmpegCommand.isEmpty()) {
                            additionCmd.append("[").append(inputMediaIndex).append("-add];")
                                    .append("[").append(inputMediaIndex).append("-add]")
                                    .append(clip.additionalFFmpegCommand);
                        }
                    }


                    filterComplex.append(":enable='").append(
                                    getConditionThree(
                                            "t",
                                            String.valueOf(clip.startTime),
                                            String.valueOf(clip.startTime + clip.duration + fillingTransitionDuration),
                                            "~")
                            ).append("'").append(",")
                            .append("fps=").append(templateSettings.settings.getFrameRate())
                            .append(clip.isReverse() ? ",reverse" : "")
                            .append(additionCmd)
                            .append(outputLabel).append(";\n");


                    // TODO: In the future, before store the tag here, user can import preset (keyframes pack)
                    //  and apply to the clip, keyframe pack will contains almost all the keyframe that we can
                    //  modify in the app. The keyframe that user edit will render first, then the preset.
                    //  so next time when I have time, and if user is apply a preset to a clip
                    //  (for that we can check if the clip has Preset available. Preset is a class that contains
                    //  custom keyframe for import/export) then it will appent to the render system like this
                    //  [preset]scale...[outputLabel]
                    //  the above outputLabel is set to the preset first, then the preset apply the keyframe edit
                    //  just like how we apply the normal keyframe, after that we can output the outputLabel as normal
                    //  .
                    //  And as a side quest, make a Preset Menu that load from network and sort with 3 state in the dropdown
                    //  Most Favorite, Newest, Most Used

                    tags.storeTag(clip, outputLabel);
                    break;
                case TEXT:
                    filterComplex.append("[").append(inputLayerIndex).append(":v]")
                            .append("trim=duration=").append(clip.duration).append(",")
                            .append("setpts=PTS-STARTPTS+").append(clip.startTime).append("/TB").append(transparentLabel).append(";\n");

                    filterComplex.append(transparentLabel)
                            .append("drawtext=").append("fontfile='/system/fonts/DroidSans.ttf'")
                            .append(":fontsize=").append(clip.fontSize)
                            .append(":text='").append(clip.textContent.replace(":", "\\:").replace("'", "\\'"))
                            .append("':x=").append("(w-text_w)/2")//.append(clip.posX) Centralize text
                            .append(":y=").append("(h-text_h)/2")//.append(clip.posY) Centralize text
                            .append(":enable='").append(getConditionThree("t", String.valueOf(clip.startTime), String.valueOf(clip.startTime + clip.duration), "~")).append("'").append(",")
                            .append("fps=").append(templateSettings.settings.getFrameRate())
                            .append(outputLabel).append(";\n");

                    tags.storeTag(clip, outputLabel);
                    break;

                case AUDIO:
                    // 🎵 Pure audio clip logic
                    int delayMs = (int) (clip.startTime * 1000);
                    filterComplex.append("[").append(inputLayerIndex).append(":a]")
                            .append("atrim=start=").append(clip.startClipTrim).append(":end=").append(clip.startClipTrim + clip.duration).append(",")
                            .append("adelay=").append(delayMs).append("|").append(delayMs).append(",")
                            .append("asetpts=PTS-STARTPTS")
                            .append(clip.isReverse() ? ",areverse" : "")
                            .append(audioLabel).append(";\n");

                    audioInputs.append(audioLabel);
                    audioClipCount++;
                    break;
            }

            // 🔊 Handle embedded audio in VIDEO
            if (clip.type == EditingActivity.ClipType.VIDEO && clip.isClipHasAudio() && !clip.isMute()) {

                // Transition extension: Same for clip
                int delayMs = (int) (clip.startTime * 1000);
                // Note: and the video input index might have shifted if there was a mask, 
                // but audio is always in the original video input (sourceInputIndex).
                // Wait, I need to check if sourceInputIndex is available here. 
                // It was defined inside the switch block. I should move it out or use the logic.
                
                int effectiveSourceInputIndex = (clip.type == EditingActivity.ClipType.VIDEO && clip.removeBackground) ? 
                    (inputLayerIndex - (IOHelper.isFileExist(clip.getCutoutPath(templateSettings.data.getProjectPath()) + ".mp4") ? 2 : 1)) :
                    (inputLayerIndex - 1);

                filterComplex.append("[").append(effectiveSourceInputIndex).append(":a]")
                        .append("atrim=start=").append(clip.startClipTrim).append(":end=").append(clip.startClipTrim + clip.duration + extendMediaDuration).append(",")
                        .append("adelay=").append(delayMs).append("|").append(delayMs).append(",")
                        // This handle the extension in silent to match the video
                        .append("apad=pad_dur=").append(freezeFrameDuration).append(",")
                        .append("asetpts=PTS-STARTPTS")
                        .append(clip.isReverse() ? ",areverse" : "")
                        .append(audioLabel).append(";\n");

                audioInputs.append(audioLabel);
                audioClipCount++;
            }

            switch (clip.type) {
                case VIDEO:
                case IMAGE:
                    // inputLayerIndex is already incremented accordingly inside the case
                    inputMediaIndex++;
                    break;
                case AUDIO:
                case TEXT:
                    inputLayerIndex++;
                    inputMediaIndex++;
                    break;
            }
        }

        // Use for previous full render
//        for (EditingActivity.Track track : templateSettings.timeline.tracks) {
//            List<EditingActivity.Clip> clipList = track.clips;
//            for (int i = 0; i < clipList.size() - 1; i++) {
//                EditingActivity.Clip clipA = clipList.get(i);
//                EditingActivity.Clip clipB = clipList.get(i + 1);
//
//                if (clipA.isClipTransitionAvailable())
//                    filterComplex.append(FXCommandEmitter.emitTransition(clipA, clipB, clipA.endTransition, tags));
//            }
//        }

        for (int i = 0; i < templateSettings.clips.length - 1; i++) {
            EditingActivity.Clip clipA = templateSettings.clips[i];
            EditingActivity.Clip clipB = templateSettings.clips[i + 1];

            if (clipA.isClipTransitionAvailable())
                filterComplex.append(FXCommandEmitter.emitTransition(clipA, clipB, clipA.endTransition, tags));

        }




        int layer = 0;
        FfmpegFilterComplexTags.FilterComplexInfo baseInfo = tags.useTag(baseTag);
        while(tags.tagsMapToUsableTagIndex.size() > 0)
        {
            EditingActivity.Clip clip = tags.tagsMapToUsableTagIndex.get(0).getKey();

            String prevOutputLabel = "[layer-" + (layer - 1 ) + "]";
            String outputLabel = "[layer-" + layer + "]";

            switch (clip.type) {
                case VIDEO:
                case IMAGE:
                case TEXT:
                    filterComplex.append((layer == 0 ? baseInfo.tag : (tags.useTag(prevOutputLabel).tag))).append(tags.useTag(clip).tag)
                            .append("overlay=")
                            .append("enable='").append(
                                    getConditionThree("t",
                                            String.valueOf(clip.startTime),
                                            String.valueOf(clip.startTime + clip.duration),
                                            "~")
                            ).append("'").append(outputLabel).append(";\n");

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
                    .append("amix=inputs=").append(audioClipCount).append(":dropout_transition=0:normalize=0").append("[aout];\n");
            audioMaps.append("-map \"[aout]\" ");
        } else {
            audioMaps.append("-an "); // 🧘 No audio at all
        }

        // Available when the track has at least 1 video
        // Null when there are no video in the track
        FfmpegFilterComplexTags.FilterComplexInfo mapTag = tags.useTag(0);

        // If it was template then insert the mark.
        String outputStr =
                templateSettings.isTemplateCommand ? Constants.DEFAULT_TEMPLATE_CLIP_EXPORT_MARK :
                        IOHelper.CombinePath(templateSettings.data.getProjectPath(), (templateSettings.isFinal ? "" : (templateSettings.renderingIndex + "_")) + Constants.DEFAULT_EXPORT_CLIP_FILENAME);

        cmd.append("-filter_complex \"").append(filterComplex).append("\" ")
                .append("-map \"").append( (mapTag != null ? mapTag.tag : "[base]") ).append("\" ")
                .append(audioMaps);
        cmd.append(" -threads 0");

        // Encoder selection: hardware (MediaCodec) or software (libopenh264)
        if (templateSettings.settings.isUseHardwareAccel()) {
            cmd.append(" -c:v h264_" + hardwareAcceleratedName)
               .append(" -b:v ").append(templateSettings.settings.getBitrate()).append("M");
        } else {
            cmd.append(" -c:v libopenh264 -preset ").append(templateSettings.settings.getPreset())
               .append(" -tune ").append(templateSettings.settings.getTune())
               .append(" -crf ").append(templateSettings.settings.getCRF());
        }
        cmd.append(" -y ").append("\"")
                .append(outputStr)
                .append("\"");

        return cmd.toString();
    }
    public static String generateCmdFull(Context context, EditingActivity.VideoSettings settings, EditingActivity.Timeline timeline, MainAreaScreen.ProjectData data, boolean isTemplateCommand, boolean isTrimAllowed) {

        RenderSettings renderSettings = new RenderSettings(settings, timeline, new EditingActivity.Clip[0], data, 0, false, isTemplateCommand, isTrimAllowed);
        return generateCmdFull(context, renderSettings);
    }
    public static String generateCmdFull(Context context, RenderSettings renderSettings) {
        int clipCount = renderSettings.timeline.getAllClipCount();

        StringBuilder cmd = new StringBuilder();
        int renderingIndex = 0;
        if(renderSettings.settings.getClipCap() <= 0) return "Invalid argument: Clip Cap should be greater than 0";
        while (clipCount > 0)
        {
            if(clipCount > renderSettings.settings.getClipCap())
            {
                renderSettings.renderingIndex = renderingIndex;
                renderSettings.isFinal = false;
                cmd.append(generateExportCmdPartially(context, renderSettings, renderSettings.settings.getClipCap(), renderingIndex * renderSettings.settings.getClipCap()))
                        .append(Constants.DEFAULT_MULTI_FFMPEG_COMMAND_REGEX);

                clipCount -= renderSettings.settings.getClipCap();
            }
            else {
                renderSettings.renderingIndex = renderingIndex;
                renderSettings.isFinal = true;
                cmd.append(generateExportCmdPartially(context, renderSettings, clipCount, renderingIndex * renderSettings.settings.getClipCap()));
                break;
            }
            renderingIndex++;
        }
        return cmd.toString();
    }


    /**
     *
     * Get the Expr for keyframe rendering.
     * @param keyframes Total keyframe of the Clip
     * @param startIndex Index for prevKeyframe. Put 0 for start.
     * @param valueType (scaleX, scaleY, posX, posY, Rot)
     * @return Expression for FFmpeg in String format.
     *
     */
    public static String getKeyframeFFmpegExpr(List<EditingActivity.Keyframe> keyframes, EditingActivity.Clip clip, int startIndex, EditingActivity.VideoProperties.ValueType valueType)
    {
        StringBuilder keyframeExprString = new StringBuilder();

        // TODO: Add a 1.0 or fallback clip.videoProperties (reset properties) keyframe right after the clip duration, as it will filter out the keyframe 'else' ffmpeg.
        if(startIndex + 1 >= keyframes.size()) return String.valueOf(keyframes.get(startIndex).value.getValue(valueType)); // Default value (prevKeyframe)
//        if(startIndex + 1 >= keyframes.size()) return String.valueOf(clip.videoProperties.getValue(valueType)); // Default value

        EditingActivity.Keyframe prevKeyframe = keyframes.get(startIndex);

        // Find the furthest keyframe with the same value to optimize the expression by merging segments
        int nextIndex = startIndex + 1;
        while (nextIndex + 1 < keyframes.size() &&
                keyframes.get(nextIndex + 1).value.getValue(valueType) == prevKeyframe.value.getValue(valueType)) {
            nextIndex++;
        }
        EditingActivity.Keyframe nextKeyframe = keyframes.get(nextIndex);

        // Input time for zoompan expression, time for other.
//        String timeUnit =
//                (valueType == EditingActivity.VideoProperties.ValueType.ScaleX || valueType == EditingActivity.VideoProperties.ValueType.ScaleY) ?
//                        "it" :
//                        valueType == EditingActivity.VideoProperties.ValueType.Speed || valueType == EditingActivity.VideoProperties.ValueType.Opacity ?
//                                "T" : "t";
        // TODO: If zooming feature is up, then use "it" for it. Scale isn't it based.
        String timeUnit = "t"; // default
        if (valueType == EditingActivity.VideoProperties.ValueType.Speed || valueType == EditingActivity.VideoProperties.ValueType.Opacity) {
            timeUnit = "T";
        } else if (valueType == EditingActivity.VideoProperties.ValueType.ScaleX || valueType == EditingActivity.VideoProperties.ValueType.ScaleY) {
            // For now scale is handled by 'scale' filter which uses 't',
            // but we might want 'it' if zoompan is enabled.
            // For now, let's stick to 't' for the scale filter.
            timeUnit = "t";
        }

        // Skipping matching value element
        // Only return a constant value if we've reached the end of the keyframes with this value.
        // If there are more keyframes with different values, we must continue the recursion.
        if(prevKeyframe.value.getValue(valueType) == nextKeyframe.value.getValue(valueType) && nextIndex + 1 >= keyframes.size())
            return String.valueOf(prevKeyframe.value.getValue(valueType));

        keyframeExprString
                .append("if(")
//                .append("gte(").append(timeUnit).append(",").append(prevKeyframe.getLocalTime()).append(")")
//                .append("*")
//                .append("lte(").append(timeUnit).append(",").append(nextKeyframe.getLocalTime()).append(")").append(",")

                .append(getConditionThree(
                        timeUnit,
                        String.valueOf(getTimeInTimebase(timeUnit, valueType, prevKeyframe, clip)),
                        String.valueOf(getTimeInTimebase(timeUnit, valueType, nextKeyframe, clip)), "~")
                ).append(",")
                // insert the expr here
                // previous: nextKeyframe.value.getValue(valueType)
                .append(generateEasing(prevKeyframe, nextKeyframe, clip, valueType, timeUnit)).append(",")
                .append(getKeyframeFFmpegExpr(keyframes, clip, startIndex + 1, valueType))
                .append(")");

        return keyframeExprString.toString();
    }

    public static String generateEasing(EditingActivity.Keyframe prevKey, EditingActivity.Keyframe nextKey, EditingActivity.Clip clip, EditingActivity.VideoProperties.ValueType type, String timeUnit)
    {
        // Get global time for Speed as it use T as Timebase, global Time.
        return generateEasing(prevKey.value.getValue(type),
                nextKey.value.getValue(type),
                getTimeInTimebase(timeUnit, type, prevKey, clip),
                (nextKey.getLocalTime() - prevKey.getLocalTime()),
                prevKey.easing,
                timeUnit);
    }

    public static String generateEasing(float prevValue, float nextValue, float offset, float duration, EditingActivity.EasingType type, String timeUnit) {
        StringBuilder expr = new StringBuilder();
        String r = getClipRatio(offset, duration, timeUnit); // clip((t-offset)/duration,0,1)

        String start = Float.toString(prevValue);
        String end = Float.toString(nextValue);
        String delta = "(" + end + "-" + start + ")";

        switch (type) {
            // TODO: Add visualizer for these type like CapCut

            // None
            case NONE:
                return expr.append(start).toString();

            // Linear
            case LINEAR:
                return expr.append(start).append("+").append(delta).append("*").append(r).toString();

            // Sine
            case EASE_IN_SINE:
                return expr.append(start).append("+").append(delta).append("*").append("(1-cos(").append(r).append("*PI/2))").toString();
            case EASE_OUT_SINE:
                return expr.append(start).append("+").append(delta).append("*").append("sin(").append(r).append("*PI/2)").toString();
            case EASE_IN_OUT_SINE:
                return expr.append(start).append("+").append(delta).append("*").append("((1-cos(PI*").append(r).append("))/2)").toString();

            // Quadratic
            case EASE_IN_QUAD:
                return expr.append(start).append("+").append(delta).append("*").append("pow(").append(r).append(",2)").toString();
            case EASE_OUT_QUAD:
                return expr.append(start).append("+").append(delta).append("*").append("(1-pow(1-").append(r).append(",2))").toString();
            case EASE_IN_OUT_QUAD:
                return expr.append(start).append("+").append(delta).append("*")
                        .append("(")
                        //.append(r).append("<0.5? 2*pow(").append(r).append(",2) :1-pow(-2*").append(r).append("+2,2)/2")
                        .append(getIfExpr(getConditionTwo(r, "<", "0.5"),"2*pow(" + r + ",2)","1-pow(-2*" + r + "+2,2)/2"))
                        .append(")")
                        .toString();

            // Cubic
            case EASE_IN_CUBIC:
                return expr.append(start).append("+").append(delta).append("*").append("pow(").append(r).append(",3)").toString();
            case EASE_OUT_CUBIC:
                return expr.append(start).append("+").append(delta).append("*").append("(1-pow(1-").append(r).append(",3))").toString();
            case EASE_IN_OUT_CUBIC:
                return expr.append(start).append("+").append(delta).append("*")
                        .append("(")
                        //.append(r).append("<0.5?4*pow(").append(r).append(",3):1-pow(-2*").append(r).append("+2,3)/2")
                        .append(getIfExpr(getConditionTwo(r, "<", "0.5"),"4*pow(" + r + ",3)","1-pow(-2*" + r + "+2,3)/2"))
                        .append(")")
                        .toString();

            // Quartic
            case EASE_IN_QUART:
                return expr.append(start).append("+").append(delta).append("*").append("pow(").append(r).append(",4)").toString();
            case EASE_OUT_QUART:
                return expr.append(start).append("+").append(delta).append("*").append("(1-pow(1-").append(r).append(",4))").toString();
            case EASE_IN_OUT_QUART:
                return expr.append(start).append("+").append(delta).append("*")
                        .append("(")
                        //.append(r).append("<0.5?8*pow(").append(r).append(",4):1-pow(-2*").append(r).append("+2,4)/2")
                        .append(getIfExpr(getConditionTwo(r, "<", "0.5"),"8*pow(" + r + ",4)","1-pow(-2*" + r + "+2,4)/2"))
                        .append(")")
                        .toString();

            // Quintic
            case EASE_IN_QUINT:
                return expr.append(start).append("+").append(delta).append("*").append("pow(").append(r).append(",5)").toString();
            case EASE_OUT_QUINT:
                return expr.append(start).append("+").append(delta).append("*").append("(1-pow(1-").append(r).append(",5))").toString();
            case EASE_IN_OUT_QUINT:
                return expr.append(start).append("+").append(delta).append("*")
                        .append("(")
                        //.append(r).append("<0.5?16*pow(").append(r).append(",5):1-pow(-2*").append(r).append("+2,5)/2")
                        .append(getIfExpr(getConditionTwo(r, "<", "0.5"),"16*pow(" + r + ",5)","1-pow(-2*" + r + "+2,5)/2"))
                        .append(")")
                        .toString();

            // Exponential
            case EASE_IN_EXPO:
                // normalized so r=0 -> 0, r=1 -> 1
                return expr.append(start).append("+").append(delta).append("*")
                        .append("((pow(2,10*").append(r).append(")-1)/1023)").toString();
            case EASE_OUT_EXPO:
                return expr.append(start).append("+").append(delta).append("*")
                        .append("(1-pow(2,-10*").append(r).append("))").toString();
            case EASE_IN_OUT_EXPO:
                return expr.append(start).append("+").append(delta).append("*")
                        .append("(")
//                        .append(r).append("==0?0:").append(r).append("==1?1:")
//                        .append("(").append(r).append("<0.5?pow(2,20*").append(r).append("-10)/2:(2-pow(2,-20*").append(r).append("+10))/2").append(")")
                        .append(
                                getIfExpr(
                                        getConditionTwo(r, "==", "0"),
                                        "0",
                                        getIfExpr(
                                                getConditionTwo(r, "==", "1"),
                                                "1",
                                                getIfExpr(
                                                        getConditionTwo(r, "<", "0.5"),
                                                        "pow(2,20*" + r + "-10)/2",
                                                        "(2-pow(2,-20*" + r + "+10))/2"
                                                )
                                                )
                                )
                        )
                        .append(")")
                        .toString();

            // Circular
            case EASE_IN_CIRC:
                return expr.append(start).append("+").append(delta).append("*")
                        .append("(1-sqrt(1-").append(r).append("*").append(r).append("))").toString();
            case EASE_OUT_CIRC:
                return expr.append(start).append("+").append(delta).append("*")
                        .append("sqrt(1-pow(").append(r).append("-1,2))").toString();
            case EASE_IN_OUT_CIRC:
                return expr.append(start).append("+").append(delta).append("*")
                        //.append("(").append(r).append("<0.5?(1-sqrt(1-pow(2*").append(r).append(",2)))/2:(sqrt(1-pow(-2*").append(r).append("+2,2))+1)/2").append(")")
                        .append(getIfExpr(getConditionTwo(r, "<", "0.5"),"(1-sqrt(1-pow(2*" + r + ",2)))/2","(sqrt(1-pow(-2*" + r + "+2,2))+1)/2"))
                        .toString();

            // Back
            case EASE_IN_BACK:
                // c1 = 1.70158, c3 = c1 + 1 = 2.70158
                return expr.append(start).append("+").append(delta).append("*")
                        .append("(2.70158*pow(").append(r).append(",3)-1.70158*pow(").append(r).append(",2))")
                        .toString();
            case EASE_OUT_BACK:
                return expr.append(start).append("+").append(delta).append("*")
                        .append("(1+2.70158*pow(").append(r).append("-1,3)+1.70158*pow(").append(r).append("-1,2))")
                        .toString();
            case EASE_IN_OUT_BACK:
                // c1=1.70158, c2=c1*1.525=2.5949099999999997
                return expr.append(start).append("+").append(delta).append("*")
                        .append("(")
//                        .append(r).append("<0.5? (pow(2*").append(r).append(",2)*(((2.5949099999999997)+1)*2*").append(r).append("-2.5949099999999997))/2")
//                        .append(": (pow(2*").append(r).append("-2,2)*(((2.5949099999999997)+1)*(2*").append(r).append("-2)+2.5949099999999997)+2)/2")
                        .append(getIfExpr(getConditionTwo(r, "<", "0.5"),
                                "(pow(2*" + r + ",2)*(((2.5949099999999997)+1)*2*" + r + "-2.5949099999999997))/2",
                                "(pow(2*" + r + "-2,2)*(((2.5949099999999997)+1)*(2*" + r + "-2)+2.5949099999999997)+2)/2"))
                        .append(")")
                        .toString();

            // Elastic
            case EASE_IN_ELASTIC:
                return expr.append(start).append("+").append(delta).append("*")
                        .append("(")
//                        .append(r).append("==0?0:").append(r).append("==1?1:-pow(2,10*").append(r).append("-10)*sin((").append(r).append("*10-10.75)*(2*PI/3))")
                        .append(getIfExpr(getConditionTwo(r, "==", "0"),"0",
                                getIfExpr(getConditionTwo(r, "==", "1"),"1",
                                        "-pow(2,10*" + r + "-10)*sin((" + r + "*10-10.75)*(2*PI/3))"
                                )))
                        .append(")")
                        .toString();
            case EASE_OUT_ELASTIC:
                return expr.append(start).append("+").append(delta).append("*")
                        .append("(")
//                        .append(r).append("==0?0:").append(r).append("==1?1:pow(2,-10*").append(r).append(")*sin((").append(r).append("*10-0.75)*(2*PI/3))+1")
                        .append(getIfExpr(getConditionTwo(r, "==", "0"),"0",
                                getIfExpr(getConditionTwo(r, "==", "1"),"1",
                                        "pow(2,-10*" + r + ")*sin((" + r + "*10-0.75)*(2*PI/3))+1"
                                )))
                        .append(")")
                        .toString();
            case EASE_IN_OUT_ELASTIC:
                return expr.append(start).append("+").append(delta).append("*")
                        .append("(")
//                        .append(r).append("==0?0:").append(r).append("==1?1:")
//                        .append("(").append(r).append("<0.5?-(pow(2,20*").append(r).append("-10)*sin((20*").append(r).append("-11.125)*(2*PI/4.5)))/2:(pow(2,-20*").append(r).append("+10)*sin((20*").append(r).append("-11.125)*(2*PI/4.5)))/2+1").append(")")
                        .append(getIfExpr(getConditionTwo(r, "==", "0"),"0",
                                getIfExpr(getConditionTwo(r, "==", "1"),"1",
                                getIfExpr(getConditionTwo(r, "<", "0.5"),"-(pow(2,20*" + r + "-10)*sin((20*" + r + "-11.125)*(2*PI/4.5)))/2", "(pow(2,-20*" + r + "+10)*sin((20*" + r + "-11.125)*(2*PI/4.5)))/2+1"
                                ))))
                        .append(")")
                        .toString();


            // TODO: Bounce easing error. Code too long to fix.
            // Bounce (using piecewise bounceOut)
            case EASE_OUT_BOUNCE: {
                // bounceOut piecewise
//                String bo =
//                        "(" + r + "<" + (1f/2.75f) + "?" +
//                                (7.5625f) + "*" + r + "*" + r +
//                                ":" + r + "<" + (2f/2.75f) + "?" +
//                                (7.5625f) + "*pow(" + r + "-" + (1.5f/2.75f) + ",2)+" + 0.75f +
//                                ":" + r + "<" + (2.5f/2.75f) + "?" +
//                                (7.5625f) + "*pow(" + r + "-" + (2.25f/2.75f) + ",2)+" + 0.9375f +
//                                ":" +
//                                (7.5625f) + "*pow(" + r + "-" + (2.625f/2.75f) + ",2)+" + 0.984375f +
//                                ")";
                String bo = getIfExpr(getConditionTwo(r, "<", Float.toString(1f/2.75f)),
                        (7.5625f) + "*" + r + "*" + r,
                        getIfExpr(getConditionTwo(r, "<", Float.toString(2f/2.75f)),
                        (7.5625f) + "*pow(" + r + "-" + (1.5f/2.75f) + ",2)+" + 0.75f,
                        getIfExpr(getConditionTwo(r, "<", Float.toString(2.5f/2.75f)),
                                (7.5625f) + "*pow(" + r + "-" + (2.25f/2.75f) + ",2)+" + 0.9375f,
                                (7.5625f) + "*pow(" + r + "-" + (2.625f/2.75f) + ",2)+" + 0.984375f
                                )));
                return expr.append(start).append("+").append(delta).append("*").append(bo).toString();
            }
            case EASE_IN_BOUNCE:
                // 1 - bounceOut(1 - r)
                return expr.append(start).append("+").append(delta).append("*")
//                        .append("(1-(")
//                        .append("(").append("(").append("1-").append(r).append(")<").append(1f/2.75f).append("?")
//                        .append(7.5625f).append("*pow(1-").append(r).append(",2):")
//                        .append("(").append("1-").append(r).append(")<").append(2f/2.75f).append("?")
//                        .append(7.5625f).append("*pow(1-").append(r).append("-").append(1.5f/2.75f).append(",2)+0.75:")
//                        .append("(").append("1-").append(r).append(")<").append(2.5f/2.75f).append("?")
//                        .append(7.5625f).append("*pow(1-").append(r).append("-").append(2.25f/2.75f).append(",2)+0.9375:").
//                        .append(7.5625f).append("*pow(1-").append(r).append("-").append(2.625f/2.75f).append(",2)+0.984375")
//                        .append(")")
//                        .append("))")

                        .append(getIfExpr(getConditionTwo("(1-(((1-" + r + ")", "<", Float.toString(1f/2.75f)),
                                7.5625f + "*pow(1-" + r + ",2)",
                                getIfExpr(getConditionTwo("(1-" + r + ")", "<", Float.toString(2f/2.75f)),
                                        7.5625f + "*pow(1-" + r + "-" + (1.5f/2.75f) + ",2)+0.75",
                                getIfExpr(getConditionTwo("(1-" + r + ")", "<", Float.toString(2.5f/2.75f)),
                                        7.5625f + "*pow(1-" + r + "-" + (2.25f/2.75f) + ",2)+0.9375",
                                        7.5625f + "*pow(1-" + r + "-" + (2.625f/2.75f) + ",2)+0.984375"
                                        )
                                        )
                        ))
                        .toString();
            case EASE_IN_OUT_BOUNCE:
                String bo = getIfExpr(getConditionTwo(r, "<", Float.toString(1f/2.75f)),
                        (7.5625f) + "*" + r + "*" + r,
                        getIfExpr(getConditionTwo(r, "<", Float.toString(2f/2.75f)),
                                (7.5625f) + "*pow(" + r + "-" + (1.5f/2.75f) + ",2)+" + 0.75f,
                                getIfExpr(getConditionTwo(r, "<", Float.toString(2.5f/2.75f)),
                                        (7.5625f) + "*pow(" + r + "-" + (2.25f/2.75f) + ",2)+" + 0.9375f,
                                        (7.5625f) + "*pow(" + r + "-" + (2.625f/2.75f) + ",2)+" + 0.984375f
                                )));
                String bi = getIfExpr(getConditionTwo("(1-(((1-" + r + ")", "<", Float.toString(1f/2.75f)),
                        7.5625f + "*pow(1-" + r + ",2)",
                        getIfExpr(getConditionTwo("(1-" + r + ")", "<", Float.toString(2f/2.75f)),
                                7.5625f + "*pow(1-" + r + "-" + (1.5f/2.75f) + ",2)+0.75",
                                getIfExpr(getConditionTwo("(1-" + r + ")", "<", Float.toString(2.5f/2.75f)),
                                        7.5625f + "*pow(1-" + r + "-" + (2.25f/2.75f) + ",2)+0.9375",
                                        7.5625f + "*pow(1-" + r + "-" + (2.625f/2.75f) + ",2)+0.984375"
                                )
                        )
                );


                return expr.append(start).append("+").append(delta).append("*")
                        .append("(").append(r).append("<0.5?(1-(")
                        .append("(").append("1-2*").append(r).append(")<").append(1f/2.75f).append("?")
                        .append(7.5625f).append("*pow(1-2*").append(r).append(",2):")
                        .append("(").append("1-2*").append(r).append(")<").append(2f/2.75f).append("?")
                        .append(7.5625f).append("*pow(1-2*").append(r).append("-").append(1.5f/2.75f).append(",2)+0.75:")
                        .append("(").append("1-2*").append(r).append(")<").append(2.5f/2.75f).append("?")
                        .append(7.5625f).append("*pow(1-2*").append(r).append("-").append(2.25f/2.75f).append(",2)+0.9375:")
                        .append(7.5625f).append("*pow(1-2*").append(r).append("-").append(2.625f/2.75f).append(",2)+0.984375")
                        .append(")")
                        .append("))/2:(1+(")
                        .append("(").append("2*").append(r).append("-1)<").append(1f/2.75f).append("?")
                        .append(7.5625f).append("*pow(2*").append(r).append("-1,2):")
                        .append("(").append("2*").append(r).append("-1)<").append(2f/2.75f).append("?")
                        .append(7.5625f).append("*pow(2*").append(r).append("-1-").append(1.5f/2.75f).append(",2)+0.75:")
                        .append("(").append("2*").append(r).append("-1)<").append(2.5f/2.75f).append("?")
                        .append(7.5625f).append("*pow(2*").append(r).append("-1-").append(2.25f/2.75f).append(",2)+0.9375:")
                        .append(7.5625f).append("*pow(2*").append(r).append("-1-").append(2.625f/2.75f).append(",2)+0.984375")
                        .append(")")
                        .append("))/2)")

                        .append(getIfExpr(getConditionTwo(r, "<", "0.5"), bi, bo))
                        .toString();

            default:
                return expr.append(start).append("+").append(delta).append("*").append(r).toString();
        }
    }

    public static String getClipRatio(float offset, float duration, String timeUnit)
    {
        return "clip((" + timeUnit + "-" + offset + ")/" + duration + ",0,1)";
    }
    public static String getIfExpr(String condition, String thenExpr, String elseExpr)
    {
        return "if(" + condition + "," + thenExpr + "," + elseExpr +")";
    }
    public static String getConditionTwo(String a, String operator, String b)
    {
        switch (operator) {
            case "<":
                return "lt(" + a + "," + b + ")";
            case "<=":
                return "lte(" + a + "," + b + ")";
            case ">":
                return "gt(" + a + "," + b + ")";
            case ">=":
                return "gte(" + a + "," + b + ")";
            case "==":
                return "eq(" + a + "," + b + ")";
            case "!=":
                return "neq(" + a + "," + b + ")";
            case "&&":
                return "and(" + a + "," + b + ")";
            case "||":
                return "or(" + a + "," + b + ")";
            default:
                return "";
        }
    }
    public static String getConditionThree(String a, String b, String c, String operator)
    {
        switch (operator)
        {
            case "~":
                return "between(" + a + "," + b + "," + c +")";
            case "---":
                return "---";
            default:
                return "";
        }
    }


    public static float getTimeInTimebase(String timebase, EditingActivity.VideoProperties.ValueType type, EditingActivity.Keyframe keyframe, EditingActivity.Clip clip)
    {
        if (Objects.equals(timebase, "T")) {
            if (type == EditingActivity.VideoProperties.ValueType.Speed) {
                // Speed is used in setpts (before project startTime shift).
                // Input T starts at clip.startClipTrim.
                return keyframe.getLocalTime() + clip.startClipTrim;
            }
            // Opacity is used in geq (after project startTime shift).
            // T reflects the global timestamp.
            return keyframe.getGlobalTime(clip);
        }

        if (Objects.equals(timebase, "t")) {
            // scale, rotate, hue etc. are applied after setpts shift.
            // t reflects the global timestamp.
            return keyframe.getGlobalTime(clip);
        }

        if (Objects.equals(timebase, "it")) {
            // it is input time (usually for zoompan), starts at 0 for the filter input.
            return keyframe.getLocalTime();
        }

        return keyframe.getLocalTime();
    }









    public static class FfmpegFilterComplexTags {
        public static class MapUsableTag implements BaseMapTag<EditingActivity.Clip, String> {
            public EditingActivity.Clip key;
            public String value;

            public MapUsableTag(EditingActivity.Clip key, String value) {
                this.key = key;
                this.value = value;
            }

            @Override
            public EditingActivity.Clip getKey() {
                return key;
            }

            @Override
            public String getValue() {
                return value;
            }
        }

        public static class MapMergedClip implements BaseMapTag<EditingActivity.Clip, EditingActivity.Clip> {
            public EditingActivity.Clip key;
            public EditingActivity.Clip value;

            public MapMergedClip(EditingActivity.Clip key, EditingActivity.Clip value) {
                this.key = key;
                this.value = value;
            }

            @Override
            public EditingActivity.Clip getKey() {
                return key;
            }

            @Override
            public EditingActivity.Clip getValue() {
                return value;
            }
        }


        public interface BaseMapTag<K, V> {
            K getKey();

            V getValue();
        }

        public static class MapTag<K, V, T extends BaseMapTag<K, V>> extends ArrayList<T> {

            // Key-based lookup (mimics Map.get)
            public V getValueByKey(K key) {
                for (T item : this) {
                    if (Objects.equals(item.getKey(), key)) {
                        return item.getValue();
                    }
                }
                return null;
            }

            // Check if key exists
            public boolean containsKey(K key) {
                for (T item : this) {
                    if (Objects.equals(item.getKey(), key)) return true;
                }
                return false;
            }

            // Remove by key
            public void removeByKey(K key) {
                removeIf(item -> Objects.equals(item.getKey(), key));
            }

            // Since it extends ArrayList, you already have:
            // add(T element) -> adds to end
            // add(int index, T element) -> inserts at position
        }


        private final ArrayList<String> usableTag = new ArrayList<>();
        private final MapTag<EditingActivity.Clip, String, MapUsableTag> tagsMapToUsableTagIndex = new MapTag<>();
        private final MapTag<EditingActivity.Clip, EditingActivity.Clip, MapMergedClip> tagsMergedClipMap = new MapTag<>();

        public int getTagCount()
        {
            return usableTag.size();
        }

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

            if(usableTag.contains(tagsMapToUsableTagIndex.getValueByKey(key)))
            {
                String retrieveTag = tagsMapToUsableTagIndex.getValueByKey(key);
                int indexTag = usableTag.indexOf(retrieveTag);
                FilterComplexInfo info = new FilterComplexInfo(indexTag, retrieveTag);

                usableTag.remove(indexTag);
                tagsMapToUsableTagIndex.removeByKey(key);
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
            if(usableTag.contains(tagsMapToUsableTagIndex.getValueByKey(key)))
            {
                String retrieveTag = tagsMapToUsableTagIndex.getValueByKey(key);
                int indexTag = usableTag.indexOf(retrieveTag);
                FilterComplexInfo info = new FilterComplexInfo(indexTag, retrieveTag);

                usableTag.remove(indexTag);
                tagsMapToUsableTagIndex.removeByKey(key);

                tagsMergedClipMap.add(new MapMergedClip(key, mergingKey));
                return info;
            }
            return null;
        }
        public void storeTag(EditingActivity.Clip key, String tag) {
            usableTag.add(tag);

            tagsMapToUsableTagIndex.add(new MapUsableTag(key, tag));
        }
        public void storeTag(EditingActivity.Clip key, String tag, int index) {
            if(index < 0) index = 0;
            if(index >= usableTag.size()) index = usableTag.size() - 1;
            usableTag.add(index, tag);

            tagsMapToUsableTagIndex.add(index, new MapUsableTag(key, tag));
        }


//        public EditingActivity.Clip getKeyFromTag(String tag)
//        {
//            for (Map.Entry<EditingActivity.Clip, String> entry : tagsMapToUsableTagIndex.entrySet()) {
//                if (entry.getValue().equals(tag)) {
//                    return entry.getKey();
//                }
//            }
//            return null;
//        }

        public EditingActivity.Clip getKeyFromTag(String tag) {
            // tagsMapToUsableTagIndex is an ArrayList of MapUsableTag
            for (MapUsableTag entry : tagsMapToUsableTagIndex) {
                // Use the getter from the BaseMapTag interface
                if (Objects.equals(entry.getValue(), tag)) {
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
                return tagsMergedClipMap.getValueByKey(clipKey);
            return null;
        }




        public static class FilterComplexInfo {
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

    public static class RenderSettings {

        EditingActivity.VideoSettings settings;
        EditingActivity.Timeline timeline;
        MainAreaScreen.ProjectData data;
        EditingActivity.Clip[] clips;
        int renderingIndex;
        boolean isFinal;
        boolean isTemplateCommand;
        boolean isTrimAllowed;

        public RenderSettings(EditingActivity.VideoSettings settings, EditingActivity.Timeline timeline, EditingActivity.Clip[] clips, MainAreaScreen.ProjectData data, int renderingIndex, boolean isFinal, boolean isTemplateCommand, boolean isTrimAllowed) {
            this.settings = settings;
            this.timeline = timeline;
            this.clips = clips;
            this.data = data;
            this.renderingIndex = renderingIndex;
            this.isFinal = isFinal;
            this.isTemplateCommand = isTemplateCommand;
            this.isTrimAllowed = isTrimAllowed;
        }

        public RenderSettings() {}

        public void setClips(EditingActivity.Clip[] clips) {
            this.clips = clips;
        }
    }


    public static class FFmpegUtilities {
        public static final String[] presetStringList = new String[]{
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
        };
        public static final String[] tuneStringList = new String[]{
                EditingActivity.VideoSettings.FfmpegTune.FILM,
                EditingActivity.VideoSettings.FfmpegTune.ANIMATION,
                EditingActivity.VideoSettings.FfmpegTune.GRAIN,
                EditingActivity.VideoSettings.FfmpegTune.STILLIMAGE,
                EditingActivity.VideoSettings.FfmpegTune.FASTDECODE,
                EditingActivity.VideoSettings.FfmpegTune.ZEROLATENCY
        };
    }
}


/**
 * Below are old code that don't have much easing type


 public static String generateEasing(float prevValue, float nextValue, float offset, float duration, EditingActivity.EasingType type, String timeUnit)
 {
 StringBuilder expr = new StringBuilder();
 switch (type)
 {
 case LINEAR:
 return expr.append(prevValue).append("+(").append(nextValue).append("-").append(prevValue)
 .append(")*").append(getClipRatio(offset, duration, timeUnit)).toString();
 case EASE_IN:
 return expr.append(prevValue).append("+(").append(nextValue).append("-").append(prevValue)
 .append(")*pow(").append(getClipRatio(offset, duration, timeUnit)).append(",2)").toString();
 case EASE_OUT:
 return expr.append(prevValue).append("+(").append(nextValue).append("-").append(prevValue)
 .append(")*(1-pow(1-").append(getClipRatio(offset, duration, timeUnit)).append(",2))").toString();
 case EASE_IN_OUT:
 break;
 case EXPONENTIAL:
 break;
 case QUADRATIC:
 break;
 case SPRING:
 break;

 }
 /**
 * Linear
 * expr='start + (end-start) * clip((t-offset)/duration,0,1)'
 *
 * Quadratic (Ease in)
 * expr='start + (end-start) * pow(clip((t-offset)/duration,0,1),2)'
 *
 * Quadratic (Ease out)
 * expr='start + (end-start) * (1 - pow(1-clip((t-offset)/duration,0,1),2))'
 *
 * Quadratic (Ease in out)
 * expr='start + (end-start) * (clip((t-offset)/duration,0,1)<0.5 ?
 *     2*pow(clip((t-offset)/duration,0,1),2) :
 *     1 - pow(-2*clip((t-offset)/duration,0,1)+2,2)/2)'
 *
 * Exponential (Ease in)
 * expr='start + (end-start) * (pow(2,10*clip((t-offset)/duration,0,1))-1)/1023'
 *
 * Exponential (Ease out)
 * expr='start + (end-start) * (1 - pow(2,-10*clip((t-offset)/duration,0,1)))'
 *
 * Spring
 * expr='start + (end-start) * (sin(clip((t-offset)/duration)*PI*(0.2+2.5*pow(clip((t-offset)/duration),3)))
 *     * pow(1-clip((t-offset)/duration),2) + clip((t-offset)/duration))'
 *\/

        return "";
                }


 */




/**
 * Below are the failed prompt that looks good enough
 *
 *
 *
 * public static String generateEasing(float prevValue, float nextValue, float offset, float duration,
 *                                     EditingActivity.EasingType type, String timeUnit) {
 *     String r = "clip((t-" + offset + ")/" + duration + ",0,1)";
 *     StringBuilder expr = new StringBuilder();
 *
 *     switch (type) {
 *         // Linear
 *         case LINEAR:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*" + r;
 *
 *         // Sine
 *         case EASE_IN_SINE:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(1-cos(" + r + "*PI/2))";
 *         case EASE_OUT_SINE:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*sin(" + r + "*PI/2)";
 *         case EASE_IN_OUT_SINE:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(-(cos(PI*" + r + ")-1)/2)";
 *
 *         // Quadratic
 *         case EASE_IN_QUAD:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*pow(" + r + ",2)";
 *         case EASE_OUT_QUAD:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(1-pow(1-" + r + ",2))";
 *         case EASE_IN_OUT_QUAD:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(" + r + "<0.5 ? 2*pow(" + r + ",2) : 1-pow(-2*" + r + "+2,2)/2)";
 *
 *         // Cubic
 *         case EASE_IN_CUBIC:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*pow(" + r + ",3)";
 *         case EASE_OUT_CUBIC:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(1-pow(1-" + r + ",3))";
 *         case EASE_IN_OUT_CUBIC:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(" + r + "<0.5 ? 4*pow(" + r + ",3) : 1-pow(-2*" + r + "+2,3)/2)";
 *
 *         // Quartic
 *         case EASE_IN_QUART:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*pow(" + r + ",4)";
 *         case EASE_OUT_QUART:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(1-pow(1-" + r + ",4))";
 *         case EASE_IN_OUT_QUART:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(" + r + "<0.5 ? 8*pow(" + r + ",4) : 1-pow(-2*" + r + "+2,4)/2)";
 *
 *         // Quintic
 *         case EASE_IN_QUINT:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*pow(" + r + ",5)";
 *         case EASE_OUT_QUINT:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(1-pow(1-" + r + ",5))";
 *         case EASE_IN_OUT_QUINT:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(" + r + "<0.5 ? 16*pow(" + r + ",5) : 1-pow(-2*" + r + "+2,5)/2)";
 *
 *         // Exponential
 *         case EASE_IN_EXPO:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(" + r + "==0?0:pow(2,10*" + r + "-10))";
 *         case EASE_OUT_EXPO:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(" + r + "==1?1:1-pow(2,-10*" + r + "))";
 *         case EASE_IN_OUT_EXPO:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(" + r + "==0?0:" + r + "==1?1:" + r + "<0.5?pow(2,20*" + r + "-10)/2:(2-pow(2,-20*" + r + "+10))/2)";
 *
 *         // Circular
 *         case EASE_IN_CIRC:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(1-sqrt(1-pow(" + r + ",2)))";
 *         case EASE_OUT_CIRC:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*sqrt(1-pow(" + r + "-1,2))";
 *         case EASE_IN_OUT_CIRC:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(" + r + "<0.5?(1-sqrt(1-pow(2*" + r + ",2)))/2:(sqrt(1-pow(-2*" + r + "+2,2))+1)/2)";
 *
 *         // Back
 *         case EASE_IN_BACK:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*((2.70158*" + r + "*"+ r + "*"+ r + ")-(1.70158*" + r + "*"+ r + "))";
 *         case EASE_OUT_BACK:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(1+2.70158*pow(" + r + "-1,3)+1.70158*pow(" + r + "-1,2))";
 *         case EASE_IN_OUT_BACK:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(" + r + "<0.5?pow(2*" + r + ",2)*((1.525*2*" + r + ")+1.525)/2:(pow(2*" + r + "-2,2)*((1.525*(2*" + r + "-2))+1.525)+2)/2)";
 *
 *         // Elastic
 *         case EASE_IN_ELASTIC:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(" + r + "==0?0:" + r + "==1?1:-pow(2,10*" + r + "-10)*sin((" + r + "*10-10.75)*(2*PI/3)))";
 *         case EASE_OUT_ELASTIC:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(" + r + "==0?0:" + r + "==1?1:pow(2,-10*" + r + ")*sin((" + r + "*10-0.75)*(2*PI/3))+1)";
 *         case EASE_IN_OUT_ELASTIC:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(" + r + "==0?0:" + r + "==1?1:" + r + "<0.5?-pow(2,20*" + r + "-10)*sin((20*" + r + "-11.125)*(2*PI/4.5))/2:(pow(2,-20*" + r + "+10)*sin((20*" + r + "-11.125)*(2*PI/4.5))/2+1))";
 *
 *         // Bounce
 *         case EASE_IN_BOUNCE:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(1-((" + generateBounce(r) + ")))";
 *         case EASE_OUT_BOUNCE:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(" + generateBounce(r) + ")";
 *         case EASE_IN_OUT_BOUNCE:
 *             return prevValue + "+(" + (nextValue - prevValue) + ")*(" + r + "<0.5?(1-" + generateBounce("1-2*" + r) + ")/2:(1+" + generateBounce("2*" + r + "-1") + ")/2)";
 *
 *         default:
 *             return prevValue + "";
 *     }
 * }
 *
 * // Helper for Bounce piecewise
 * private static String generateBounce(String r) {
 *     return "(" + r + "<1/2.75?7.5625*"+r+"*"+r+":" +
 *            r + "<2/2.75?7.5625*("+r+"-1.5/2.75)*("+r+"-1.5/2.75)+0.75:" +
 *            r + "<2.5/2.75?7.5625*("+r+"-2.25/2.75)*("+r+"-2.25/2.75)+0.9375:" +
 *            "7.5625*("+r+"-2.625/2.75)*("+r+"-2.625/2.75)+0
 */