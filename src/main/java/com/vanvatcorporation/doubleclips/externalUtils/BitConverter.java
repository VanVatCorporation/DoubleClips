package com.vanvatcorporation.doubleclips.externalUtils;

import com.vanvatcorporation.doubleclips.helper.ObjectPrimitiveHelper;
import com.vanvatcorporation.doubleclips.impl.java.ArrayListImpl;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class BitConverter {
    public static byte[] GetBytes(int value) {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream stream = new DataOutputStream(byteStream);
        try {
            stream.writeInt(value);
        } catch (IOException e) {
            return new byte[4];
        }
        return byteStream.toByteArray();
    }
    public static byte[] GetBytes(boolean value) {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream stream = new DataOutputStream(byteStream);
        try {
            stream.writeBoolean(value);
        } catch (IOException e) {
            return new byte[4];
        }
        return byteStream.toByteArray();
    }
    public static byte[] GetBytes(String value) {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream stream = new DataOutputStream(byteStream);
        try {
            stream.writeChars(value);
        } catch (IOException e) {
            return new byte[4];
        }
        return byteStream.toByteArray();
    }
    public static byte[] GetBytes(double value) {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream stream = new DataOutputStream(byteStream);
        try {
            stream.writeDouble(value);
        } catch (IOException e) {
            return new byte[4];
        }
        return byteStream.toByteArray();
    }
    public static byte[] GetBytes(float value) {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream stream = new DataOutputStream(byteStream);
        try {
            stream.writeFloat(value);
        } catch (IOException e) {
            return new byte[4];
        }
        return byteStream.toByteArray();
    }
    public static byte[] GetBytes(long value) {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream stream = new DataOutputStream(byteStream);
        try {
            stream.writeLong(value);
        } catch (IOException e) {
            return new byte[4];
        }
        return byteStream.toByteArray();
    }
    public static byte[] GetBytes(short value) {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream stream = new DataOutputStream(byteStream);
        try {
            stream.writeShort(value);
        } catch (IOException e) {
            return new byte[4];
        }
        return byteStream.toByteArray();
    }
    public static byte[] GetBytesUTF(String value) {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream stream = new DataOutputStream(byteStream);
        try {
            stream.writeUTF(value);
        } catch (IOException e) {
            return new byte[4];
        }
        return byteStream.toByteArray();
    }







    public static int ToInteger(byte[] data, int startIndex) {
        ByteBuffer wrapped = ByteBuffer.wrap(data); // big-endian by default
        return wrapped.getInt(startIndex); // 1
    }
    public static boolean ToBoolean(byte[] data, int startIndex) {
        return data[startIndex] == 1; // 1
    }
    public static double ToDouble(byte[] data, int startIndex) {
        ByteBuffer wrapped = ByteBuffer.wrap(data); // big-endian by default
        return wrapped.getDouble(startIndex); // 1
    }
    public static float ToFloat(byte[] data, int startIndex) {
        ByteBuffer wrapped = ByteBuffer.wrap(data); // big-endian by default
        return wrapped.getFloat(startIndex); // 1
    }
    public static long ToLong(byte[] data, int startIndex) {
        ByteBuffer wrapped = ByteBuffer.wrap(data); // big-endian by default
        return wrapped.getLong(startIndex); // 1
    }
    public static short ToShort(byte[] data, int startIndex) {
        ArrayListImpl<Byte> dataProcessing = new ArrayListImpl<>(ObjectPrimitiveHelper.toObjects(data));
        dataProcessing.getRange(startIndex, dataProcessing.count() - startIndex);
        ByteBuffer wrapped = ByteBuffer.wrap(ObjectPrimitiveHelper.toPrimitives(dataProcessing.toArray(new Byte[0]))); // big-endian by default
        return wrapped.getShort(); // 1

//        ByteBuffer dbuf = ByteBuffer.allocate(2);
//        dbuf.putShort(num);
//        byte[] bytes = dbuf.array(); // { 0, 1 }
    }
    public static String ToString(byte[] data, int length, int startIndex) {
        StringBuilder sb = new StringBuilder();
        ByteBuffer wrapped = ByteBuffer.wrap(data);
        for (int i = 0; i < length; i++) {
            sb.append(wrapped.getChar(startIndex+(i*2)));
        }
        return sb.toString();
    }
}
