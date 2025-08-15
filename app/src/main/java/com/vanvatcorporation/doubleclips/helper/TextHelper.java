package com.vanvatcorporation.doubleclips.helper;

import static com.vanvatcorporation.doubleclips.helper.AnimationHelper.AnimateViewX;
import static com.vanvatcorporation.doubleclips.helper.ImageHelper.drawStrokeShape;

import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.animation.AccelerateInterpolator;

import androidx.annotation.RequiresApi;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class TextHelper {
    public static View CreateShape(View tw)
    {
        return CreateShape(tw, Color.WHITE, Color.GREEN);
    }
    public static View CreateShape(View tw, int backgroundColor, int strokeColor)
    {
        drawStrokeShape(tw, backgroundColor, strokeColor);

        return AddAnimation(tw);
    }
    public static View AddAnimation(View tw)
    {
        tw = AnimateViewX(tw, tw.getWidth(), 0, 500, new AccelerateInterpolator(), () -> {});
        return tw;
    }
}
