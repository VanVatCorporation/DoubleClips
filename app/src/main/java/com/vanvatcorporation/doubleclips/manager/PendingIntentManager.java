package com.vanvatcorporation.doubleclips.manager;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.vanvatcorporation.doubleclips.externalUtils.Random;

import java.util.HashMap;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class PendingIntentManager {
    static HashMap<Integer, PendingIntent> pendingIntentIds = new HashMap<>();
    public static int getRequestCode()
    {
        boolean isGood = false;
        int requestCode = 0;
        while (!isGood)
        {
            requestCode = Random.Range(0,9999);
            isGood = true;
            for(Integer i : pendingIntentIds.keySet())
            {
                if (i == requestCode) {
                    isGood = false;
                    break;
                }
            }
        }
        return requestCode;
    }


    public static PendingIntent getA(Context context, Intent intent, boolean isInstant)
    {
        int requestCode = getRequestCode();
        PendingIntent pendingIntent = PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE);
//        if(!isInstant)
//            pendingIntentIds.put(requestCode, pendingIntent);
        return pendingIntent;
    }
    public static PendingIntent getB(Context context, Intent intent, boolean isInstant)
    {
        int requestCode = getRequestCode();
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE);
//        if(!isInstant)
//            pendingIntentIds.put(requestCode, pendingIntent);
        return pendingIntent;
    }
    public static PendingIntent getS(Context context, Intent intent, boolean isInstant)
    {
        int requestCode = getRequestCode();
        PendingIntent pendingIntent = PendingIntent.getService(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE);
//        if(!isInstant)
//            pendingIntentIds.put(requestCode, pendingIntent);
        return pendingIntent;
    }



    public static void send(Context context, PendingIntent pendingIntent)
    {
        try {
            pendingIntent.send();
        }
        catch (Exception e)
        {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }
}
