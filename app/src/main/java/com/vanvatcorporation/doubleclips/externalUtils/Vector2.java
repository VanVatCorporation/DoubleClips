package com.vanvatcorporation.doubleclips.externalUtils;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

public class Vector2 implements Parcelable {
    public double x = 0, y = 0;

    public Vector2() { }

    public Vector2(double x, double y) {
        this.x = x;
        this.y = y;
    }

    protected Vector2(Parcel in) {
        x = in.readDouble();
        y = in.readDouble();
    }

    public static final Creator<Vector2> CREATOR = new Creator<Vector2>() {
        @Override
        public Vector2 createFromParcel(Parcel in) {
            return new Vector2(in);
        }

        @Override
        public Vector2[] newArray(int size) {
            return new Vector2[size];
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
