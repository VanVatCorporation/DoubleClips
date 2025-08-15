package com.vanvatcorporation.doubleclips.externalUtils;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

public class Vector3 extends Vector2 implements Parcelable {
    public double z = 0;
    public Vector3() { }

    public Vector3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }


    protected Vector3(Parcel in) {
        x = in.readDouble();
        y = in.readDouble();
        z = in.readDouble();
    }

    public static final Creator<Vector3> CREATOR = new Creator<Vector3>() {
        @Override
        public Vector3 createFromParcel(Parcel in) {
            return new Vector3(in);
        }

        @Override
        public Vector3[] newArray(int size) {
            return new Vector3[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeDouble(x);
        dest.writeDouble(y);
        dest.writeDouble(z);
    }
//    /**
//     * Random a double value
//     *
//     * @param min Inclusive Minimum number the Random can get
//     * @param max Inclusive Maximum number the Random can get
//     * @return The random number
//     */
//    public static double getX(double min, double max) {
//        return Math.random() * (max - min) + min;
//    }
//
//    /**
//     * Random an integer value
//     *
//     * @param min Inclusive Minimum number the Random can get
//     * @param max Inclusive Maximum number the Random can get
//     * @return The random number
//     */
//    public static int Range(int min, int max) {
//        return rand.nextInt(max + 1 - min) + min;
//    }
}
