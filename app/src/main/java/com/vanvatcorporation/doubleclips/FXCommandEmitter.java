package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class FXCommandEmitter {
//    public static String emit(EditingActivity.EffectTemplate fx, int inputIndex) {
//        switch (fx.style) {
//            case "fade":
//                return "[" + (inputIndex - 1) + "][clip" + inputIndex + "]xfade=transition=fade:duration=" + fx.duration + ":offset=" + fx.offset + "[tmp" + inputIndex + "];";
//            case "glitch":
//                return "[clip" + inputIndex + "]glitch=intensity=" + fx.params.get("intensity") + "[tmp" + inputIndex + "];";
//            // Add more effects...
//        }
//        return "";
//    }

    public static String emit(EditingActivity.Clip clip, FFmpegEdit.FfmpegFilterComplexTags.FilterComplexInfo affectedTags,
                              FFmpegEdit.FfmpegFilterComplexTags tags) {
        if(affectedTags == null) return "";
        String outputLabel = "[transition_" +
                affectedTags.tag.replace("[trans-video-", "").replace("]", "") + "]";


        EditingActivity.EffectTemplate fx = clip.effect;

        switch (fx.style) {
            case "glitch-pulse":
                tags.storeTag(outputLabel, affectedTags.index);
                return affectedTags.tag + "tblend=all_mode=addition,framestep=2,eq=brightness=0.2" + outputLabel + ";";

            case "warp-zoom":
                tags.storeTag(outputLabel, affectedTags.index);
                return affectedTags.tag + "zoompan=z='zoom+0.001':d=" + (int)(clip.duration * 30) +
                        ":x='iw/2':y='ih/2'" + outputLabel + ";";

            case "lens-flare-surge":
                tags.storeTag(outputLabel, affectedTags.index);
                return affectedTags.tag + "curves=preset=cross_process,eq=contrast=1.5:saturation=1.2" + outputLabel + ";";

            case "spin-burst":
                tags.storeTag(outputLabel, affectedTags.index);
                return affectedTags.tag + "rotate='2*PI*t/" + clip.duration + "'" + outputLabel + ";";

            // You can keep adding more wild ones here...
        }
        return ""; // If unknown, emit nothing
    }
    public static String emitTransition(EditingActivity.TransitionClip transition,
                                        FFmpegEdit.FfmpegFilterComplexTags tags) {

        EditingActivity.Clip clipA = tags.getValidMapKey(transition.fromClip);
        EditingActivity.Clip clipB = tags.getValidMapKey(transition.toClip);
        if(clipA == null) return "";
        if(clipB == null) return "";
        EditingActivity.Clip mergedClip = new EditingActivity.Clip("MERGED", clipA.startTime, clipA.duration + clipB.duration -
                // Overlap mean both the overlap clip lost the transition duration amount of time
                // (end first clip sooner than half of transition and start second clip sooner than half of transition)
                (transition.mode == EditingActivity.TransitionClip.TransitionMode.OVERLAP ? transition.duration : 0)
                , clipA.trackIndex, clipA.type);

        System.err.println(clipA.duration);
        System.err.println(clipB.duration);
        FFmpegEdit.FfmpegFilterComplexTags.FilterComplexInfo fromTag = tags.useTag(clipA, mergedClip);
        FFmpegEdit.FfmpegFilterComplexTags.FilterComplexInfo toTag = tags.useTag(clipB, mergedClip);

        if(fromTag == null) return "";
        if(toTag == null) return "";
        if(tags == null) return "";

//        String outputLabel = "[transition_" +
//                fromTag.tag.replace("[", "").replace("]", "") + "_" +
//                toTag.tag.replace("[", "").replace("]", "") + "]";
        String outputLabel = "[transition_" +
                fromTag.tag.replace("[transition_", "")
                        .replace("[trans-video-", "").replace("]", "") + "_" +
                toTag.tag.replace("[transition_", "")
                        .replace("[trans-video-", "").replace("]", "") + "]";



        float transitionOffset = 0;
        switch (transition.mode)
        {
            case END_FIRST:
                transitionOffset = (clipA.duration - (transition.duration * 2));
                break;
            case OVERLAP:
                transitionOffset = (clipA.duration - transition.duration);
                break;
            case BEGIN_SECOND:
                transitionOffset = clipA.duration;
                break;

        }
        if(transition.effect.style.contains("custom_"))
        {
            String customStyle = transition.effect.style.replace("custom_", "");
            String expression = "";
            switch (customStyle)
            {
                case "expose":
                    expression = "if(gt(Y, H*(1-P)), A, B)";
                    break;
                case "two-stage-slide":
                    expression = "if(P<0.5, B*(Y<H*(0.5+0.5*P)), B*(Y<H*(1.0))*(1-P) + A*(1-(Y<H*(1.0))*(1-P)))";
                    break;
                case "radial-shockwave":
                    expression = "B*sin(P*PI)*exp(-((X-W/2)^2+(Y-H/2)^2)/10000) + A*(1-sin(P*PI))";
                    break;
                case "massive-effect":
                    expression = "(B*(sin(P*PI)*exp(-((X-W/2)^2+(Y-H/2)^2)/5000) + cos(P*PI/2)*sin(sqrt((X-W/2)^2+(Y-H/2)^2)/50)*exp(-P*5)) + A*(1-sin(P*PI))*(1 - exp(-((X-W/2)^2+(Y-H/2)^2)/8000)) + (mod(X+Y+P*1000, 20)/20)*sin(P*PI*4)*cos(X/30)*cos(Y/30)) * (1 + 0.3*sin(P*PI*10)*cos(X/15)*cos(Y/15))";
                    break;
                case "fake-glass-shatter":
                    expression = "B*(exp(-((X-W/2)^2+(Y-H/2)^2)/(500+100*sin(P*PI*10))) * sin(P*PI)^2) + A*(1 - sin(P*PI)^2)";
                    break;

            }

            if(!expression.isEmpty())
            {
                tags.storeTag(mergedClip, outputLabel, fromTag.index);
                return fromTag.tag + toTag.tag +
                        "xfade=transition=custom:duration=" + transition.duration + ":offset=" +
                        transitionOffset +
                        ":expr='" + expression + "'"
                        + outputLabel + ";\n";
            }
        }
        else {
            tags.storeTag(mergedClip, outputLabel, fromTag.index);
            return fromTag.tag + toTag.tag +
                    "xfade=transition=" + transition.effect.style + ":duration=" + transition.duration + ":offset=" +
                    transitionOffset
                    + outputLabel + ";\n";
        }
        return "";
    }


    public static class FXRegistry {
//      public static Map<String, String> nameMap = Map.of(
//                "fade", "Cross Fade",
//                "glitch-pulse", "Glitch Pulse",
//                "warp-zoom", "Warp Zoom",
//                "lens-flare-surge", "Lens Flare Surge",
//                "spin-burst", "Spinning Burst"
//        );

        public static Map<String, String> effectsFXMap = Collections.unmodifiableMap(new HashMap<String, String>() {{
            put("glitch-pulse", "Glitch Pulse");
            put("warp-zoom", "Warp Zoom");
            put("lens-flare-surge", "Lens Flare Surge");
            put("spin-burst", "Spinning Burst");
        }});

        public static Map<String, String> transitionFXMap = Collections.unmodifiableMap(new HashMap<String, String>() {{
            put("custom_expose", "Expose [Custom]");
            put("custom_two-stage-slide", "Two Stage Slide [Custom]");
            put("custom_radial-shockwave", "Radial Shockwave [Custom]");
            put("custom_massive-effect", "Massive Effect [Custom]");
            put("custom_fake-glass-shatter", "Fake Glass Shatter [Custom]");


            put("fade", "Cross Fade");
            put("dissolve", "Dissolve");
            put("radial ", "Radial");
            put("circleopen ", "Circle Open");
            put("circleclose", "Circle Close");
            put("pixelize", "Pixelize");
            put("hlslice", "Horizontal Left Slice");
            put("hrslice", "Horizontal Right Slice");
            put("vuslice", "Vertical Up Slice");
            put("vdslice", "Vertical Down Slice");
            put("hblur", "Horizontal Blur");
            put("fadegrays", "Fade Gray");
            put("fadeblack", "Fade Black");
            put("fadewhite", "Fade White");
            put("rectcrop", "Rect Crop");
            put("circlecrop", "Circle Crop");
            put("wipeleft", "Wipe Left");
            put("wiperight", "Wipe Right");
            put("slidedown", "Slide Down");
            put("slideup", "Slide Up");
            put("slideleft", "Slide Left");
            put("slideright", "Slide Right");
            put("distance", "Distance");
            put("diagtl", "Diagonal Top-Left Wipe");
            put("diagbl", "Diagonal Bottom-Left Wipe");
            put("revealup", "Reveal Up");
        }});

    }

}

