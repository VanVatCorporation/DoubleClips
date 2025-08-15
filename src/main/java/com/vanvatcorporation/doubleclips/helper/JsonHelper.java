package com.vanvatcorporation.doubleclips.helper;

import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.google.gson.Gson;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;


@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class JsonHelper {


    public static <T> T readJson(Context context, String path, Class<T> tClass, T defaultValue)
    {
        try {
            IOHelper.createEmptyFileIfNotExist(context, path, new Gson().toJson(defaultValue));
            return readJsonString(context, IOHelper.readFromFile(context, path), tClass);
        }
        catch (Exception e)
        {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
        return null;
    }

    public static <T> void writeJson(Context context, String path, T data)
    {
        try {
            IOHelper.writeToFile(context, path, writeJsonString(context, data));
        }
        catch (Exception e)
        {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }


    public static <T> T readJsonString(Context context, String jsonValue, Class<T> tClass)
    {
        try {
            return new Gson().fromJson(jsonValue, tClass);
        }
        catch (Exception e)
        {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
        return null;
    }

    public static <T> String writeJsonString(Context context, T data)
    {
        try {
            return new Gson().toJson(data);
        }
        catch (Exception e)
        {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
        return null;
    }
}
