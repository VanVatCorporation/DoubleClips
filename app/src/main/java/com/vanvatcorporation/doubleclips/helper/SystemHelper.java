package com.vanvatcorporation.doubleclips.helper;

import android.util.DisplayMetrics;
import android.view.WindowManager;

public class SystemHelper {

    public static int[] getScreenResolution(WindowManager windowManager)
    {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(displayMetrics);
        int height = displayMetrics.heightPixels;
        int width = displayMetrics.widthPixels;
        return new int[]{width, height};
    }
}
