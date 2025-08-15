package com.vanvatcorporation.doubleclips.helper;

public class EnumHelper {
    public static String enumToString(Enum e) {
        if (e == null) {
            throw new IllegalArgumentException("Enum cannot be null.");
        }
        return e.toString();
    }
    public static <T extends Enum<T>> T stringToEnum(String str, Class<T> enumType) {
        return Enum.valueOf(enumType, str);
    }


    public static int enumToInt(Enum e) {
        if (e == null) {
            throw new IllegalArgumentException("Enum cannot be null.");
        }
        return e.ordinal();
    }
    public static <T extends Enum<T>> T intToEnum(int i, Class<T> enumType) {
        T[] enumConstants = enumType.getEnumConstants();
        if (i < 0 || i >= enumConstants.length) {
            throw new IllegalArgumentException("Invalid integer for enum: " + i);
        }
        return enumConstants[i];
    }


}
