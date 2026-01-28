package com.vanvatcorporation.doubleclips.helper;

import java.lang.reflect.Field;

public class ReflectionHelper {
    public static String getFieldName(Object obj)
    {
        return obj.getClass().getName();
    }
}
