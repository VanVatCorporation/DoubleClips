package com.vanvatcorporation.doubleclips.helper;

import android.content.Context;

import com.vanvatcorporation.doubleclips.R;

import java.util.Calendar;
import java.util.Date;

public class DateHelper {

    public static String getGreeting(Context context, String name) {
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        String greeting = "";

        if (hour >= 5 && hour < 11) {
            //greeting = context.getString(R.string.greeting_morning) + ", ";
        } else if (hour >= 11 && hour < 13) {
            //greeting = context.getString(R.string.greeting_noon) + ", ";
        } else if (hour >= 13 && hour < 18) {
            //greeting = context.getString(R.string.greeting_afternoon) + ", ";
        } else if (hour >= 18 && hour < 22) {
            //greeting = context.getString(R.string.greeting_evening) + ", ";
        } else {
            //greeting = context.getString(R.string.greeting_night) + " ";
        }

        return greeting + name;
    }

    public static int getYearOfTimestamp(long timestamp)
    {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date(timestamp));

        return calendar.get(Calendar.YEAR);
    }
    public static int getDeltaOfYearTimeWithCurrentYearTime(int year)
    {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());

        return Math.abs(calendar.get(Calendar.YEAR) - year);
    }

    public static String convertTimestampToHHMMSSFormat(long timestamp)
    {
        long seconds = timestamp / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }
    public static String convertTimestampToMMSSFormat(long timestamp)
    {
        long seconds = timestamp / 1000;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        return String.format("%02d:%02d", minutes, secs);
    }

}
