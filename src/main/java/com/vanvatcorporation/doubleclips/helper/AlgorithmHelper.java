package com.vanvatcorporation.doubleclips.helper;

import android.os.Build;

import androidx.annotation.RequiresApi;

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
public class AlgorithmHelper {
    public static int getRatio(int a, int b)
    {
        return a/b;
    }
    public static int getRatioToDivideDiv(int div, int a, int b)
    {
        return div / getRatio(a, b);
    }


}
