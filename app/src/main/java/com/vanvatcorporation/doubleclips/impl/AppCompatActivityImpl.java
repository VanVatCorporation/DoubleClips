package com.vanvatcorporation.doubleclips.impl;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.vanvatcorporation.doubleclips.R;

public class AppCompatActivityImpl extends AppCompatActivity {
    protected Bundle createrBundle;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createrBundle = getIntent().getExtras();


    }
}
