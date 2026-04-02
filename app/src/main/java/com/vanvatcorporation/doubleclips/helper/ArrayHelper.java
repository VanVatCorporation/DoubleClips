package com.vanvatcorporation.doubleclips.helper;

import com.vanvatcorporation.doubleclips.impl.java.ArrayListImpl;

import java.util.Arrays;
import java.util.Collection;

public class ArrayHelper {

    public static <T> T[]  FromListOfArrayToArray(Collection<T[]> list, T[] hint)
    {
        ArrayListImpl<T> arrayList = new ArrayListImpl<T>();
        for (T[] array : list)
        {
            arrayList.addAll(Arrays.asList(array));
        }
        return arrayList.toArray(hint);
    }
    public static <T> T[] CopyOfRange(T[] hint, T[] src, int start, int end)
    {
        int len = MathHelper.clamp(end, 0, src.length) - start;
        if (len <= 0) return null;
        return Arrays.copyOfRange(src, start, start + len);
//        int len = MathHelper.clamp(end, 0, src.length-1) - start + 1;
//        if (len <= 0) return null;
//        T[] dest = new ArrayListImpl<T>(len).toArray(hint);
//        System.arraycopy(src, start, dest, 0, len);
//        return dest;
//        //return Arrays.copyOfRange(src, start, end);
    }
}
