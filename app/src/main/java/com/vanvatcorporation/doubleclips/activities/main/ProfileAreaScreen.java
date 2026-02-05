package com.vanvatcorporation.doubleclips.activities.main;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.imageview.ShapeableImageView;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.SettingsActivity;
import com.vanvatcorporation.doubleclips.dynamiclibs.auth.LoginActivity;
import com.vanvatcorporation.doubleclips.externalUtils.Random;
import com.vanvatcorporation.doubleclips.helper.ImageHelper;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ProfileAreaScreen extends BaseAreaScreen {
    SwipeRefreshLayout profileSwipeRefreshLayout;

    TextView profileNameText;
    ShapeableImageView profileAvatarImage;



    public ProfileAreaScreen(Context context) {
        super(context);
    }

    public ProfileAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ProfileAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ProfileAreaScreen(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }


    @Override
    public void init() {
        super.init();

        profileSwipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        profileNameText = findViewById(R.id.profileName);
        profileAvatarImage = findViewById(R.id.profileAvatarImage);

        findViewById(R.id.settingsButton).setOnClickListener(v -> {
            Intent intent = new Intent(getContext(), SettingsActivity.class);
            getContext().startActivity(intent);
        });
        findViewById(R.id.signInButton).setOnClickListener(v -> {
            Intent intent = new Intent(getContext(), LoginActivity.class);
            getContext().startActivity(intent);
        });

        profileSwipeRefreshLayout.setOnRefreshListener(this::reloadingPage);

        reloadingPage();
    }

    public void reloadingPage()
    {
        profileNameText.setText("Van Vat Employee");
        ImageHelper.getImageBitmapFromNetwork(getContext(), "https://account.vanvatcorp.com/api/avatar/784d4412-a6b8-5495-92b8-f2db74b338dd", profileAvatarImage);

        profileSwipeRefreshLayout.setRefreshing(false);
    }











}
