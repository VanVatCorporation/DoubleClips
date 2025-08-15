package com.vanvatcorporation.doubleclips.helper;

public class ParserHelper {
    public static int TryParse(String parseText, int defaultValue)
    {
        try {
            return Integer.parseInt(parseText);
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }
    public static long TryParse(String parseText, long defaultValue)
    {
        try {
            return Long.parseLong(parseText);
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }
    public static float TryParse(String parseText, float defaultValue)
    {
        try {
            return Float.parseFloat(parseText);
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }
    public static double TryParse(String parseText, double defaultValue)
    {
        try {
            return Double.parseDouble(parseText);
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }
}
