package com.vanvatcorporation.doubleclips.impl;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;

public class TrackFrameLayout extends FrameLayout {

    public EditingActivity.Track trackInfo;


    public TrackFrameLayout(@NonNull Context context) {
        super(context);
    }

    public TrackFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public TrackFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public TrackFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
}
