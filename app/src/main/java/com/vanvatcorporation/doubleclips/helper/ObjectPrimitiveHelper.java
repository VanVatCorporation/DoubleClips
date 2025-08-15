package com.vanvatcorporation.doubleclips.helper;

import java.io.ByteArrayOutputStream;

public class ObjectPrimitiveHelper {
    public static byte toPrimitive(Byte object)
    {
        return object;
    }
    public static Byte toObject(byte primitives)
    {
        return primitives;
    }
    public static boolean toPrimitive(Boolean object)
    {
        return object;
    }
    public static Boolean toObject(boolean primitives)
    {
        return primitives;
    }
    public static int toPrimitive(Integer object)
    {
        return object;
    }
    public static Integer toObject(int primitives)
    {
        return primitives;
    }
    public static short toPrimitive(Short object)
    {
        return object;
    }
    public static Short toObject(short primitives)
    {
        return primitives;
    }
    public static long toPrimitive(Long object)
    {
        return object;
    }
    public static Long toObject(long primitives)
    {
        return primitives;
    }
    public static float toPrimitive(Float object)
    {
        return object;
    }
    public static Float toObject(float primitives)
    {
        return primitives;
    }
    public static double toPrimitive(Double object)
    {
        return object;
    }
    public static Double toObject(double primitives)
    {
        return primitives;
    }
    public static char toPrimitive(Character object)
    {
        return object;
    }
    public static Character toObject(char primitives)
    {
        return primitives;
    }







    public static byte[] toPrimitives(Byte[] objects)
    {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        int i=0;
        // Unboxing Byte values. (Byte[] to byte[])
        for(Byte b: objects)
            byteArrayOutputStream.write(b);//b.byteValue();
        return byteArrayOutputStream.toByteArray();
//        byte[] bytes = new byte[objects.length];
//        int i=0;
//        // Unboxing Byte values. (Byte[] to byte[])
//        for(Byte b: objects)
//            bytes[i++] = b;//b.byteValue();
//        return bytes;
    }
    public static Byte[] toObjects(byte[] primitives)
    {
        Byte[] byteObjects = new Byte[primitives.length];
        int i=0;
        // Associating Byte array values with bytes. (byte[] to Byte[])
        for(byte b: primitives)
            byteObjects[i++] = b;  // Autoboxing.
        return byteObjects;
    }


//    public static byte[] toPrimitives(Byte[] objects)
//    {
//        byte[] bytes = new byte[objects.length];
//        int i=0;
//        // Unboxing Byte values. (Byte[] to byte[])
//        for(Byte b: objects)
//            bytes[i++] = b;//b.byteValue();
//        return bytes;
//    }
//    public  static Byte[] toObjects(byte[] primitives)
//    {
//        Byte[] byteObjects = new Byte[primitives.length];
//        int i=0;
//        // Associating Byte array values with bytes. (byte[] to Byte[])
//        for(byte b: primitives)
//            byteObjects[i++] = b;  // Autoboxing.
//        return byteObjects;
//    }










    public static boolean[] toPrimitives(Boolean[] objects)
    {
        boolean[] arr = new boolean[objects.length];
        for (int i = 0; i < objects.length; i++) {
            arr[i] = objects[i];
        }
        return arr;
    }
    public static Boolean[] toObjects(boolean[] primitives)
    {
        Boolean[] objs = new Boolean[primitives.length];
        // Associating Byte array values with bytes. (byte[] to Byte[])
        int i = 0;
        for(Boolean b : primitives)
            objs[i++] = b;  // Autoboxing.
        return objs;
    }
    public static int[] toPrimitives(Integer[] objects)
    {
        int[] arr = new int[objects.length];
        for (int i = 0; i < objects.length; i++) {
            arr[i] = objects[i];
        }
        return arr;
    }
    public static Integer[] toObjects(int[] primitives)
    {
        Integer[] objs = new Integer[primitives.length];
        // Associating Byte array values with bytes. (byte[] to Byte[])
        int i = 0;
        for(Integer b : primitives)
            objs[i++] = b;  // Autoboxing.
        return objs;
    }
    public static short[] toPrimitives(Short[] objects)
    {
        short[] arr = new short[objects.length];
        for (int i = 0; i < objects.length; i++) {
            arr[i] = objects[i];
        }
        return arr;
    }
    public static Short[] toObjects(short[] primitives)
    {
        Short[] objs = new Short[primitives.length];
        // Associating Byte array values with bytes. (byte[] to Byte[])
        int i = 0;
        for(Short b : primitives)
            objs[i++] = b;  // Autoboxing.
        return objs;
    }
    public static long[] toPrimitives(Long[] objects)
    {
        long[] arr = new long[objects.length];
        for (int i = 0; i < objects.length; i++) {
            arr[i] = objects[i];
        }
        return arr;
    }
    public static Long[] toObjects(long[] primitives)
    {
        Long[] objs = new Long[primitives.length];
        // Associating Byte array values with bytes. (byte[] to Byte[])
        int i = 0;
        for(Long b : primitives)
            objs[i++] = b;  // Autoboxing.
        return objs;
    }
    public static float[] toPrimitives(Float[] objects)
    {
        float[] arr = new float[objects.length];
        for (int i = 0; i < objects.length; i++) {
            arr[i] = objects[i];
        }
        return arr;
    }
    public static Float[] toObjects(float[] primitives)
    {
        Float[] objs = new Float[primitives.length];
        // Associating Byte array values with bytes. (byte[] to Byte[])
        int i = 0;
        for(Float b : primitives)
            objs[i++] = b;  // Autoboxing.
        return objs;
    }
    public static double[] toPrimitives(Double[] objects)
    {
        double[] arr = new double[objects.length];
        for (int i = 0; i < objects.length; i++) {
            arr[i] = objects[i];
        }
        return arr;
    }
    public static Double[] toObjects(double[] primitives)
    {
        Double[] objs = new Double[primitives.length];
        // Associating Byte array values with bytes. (byte[] to Byte[])
        int i = 0;
        for(Double b : primitives)
            objs[i++] = b;  // Autoboxing.
        return objs;
    }
    public static char[] toPrimitives(Character[] objects)
    {
        char[] arr = new char[objects.length];
        for (int i = 0; i < objects.length; i++) {
            arr[i] = objects[i];
        }
        return arr;
    }
    public static Character[] toObjects(char[] primitives)
    {
        Character[] objs = new Character[primitives.length];
        // Associating Byte array values with bytes. (byte[] to Byte[])
        int i = 0;
        for(Character b : primitives)
            objs[i++] = b;  // Autoboxing.
        return objs;
    }
}
