package com.vanvatcorporation.doubleclips.activities.main;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.imageview.ShapeableImageView;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.SettingsActivity;
import com.vanvatcorporation.doubleclips.dynamiclibs.auth.AuthRepository;
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

    View profileHeader;
    View signInScreen;
    Button logOutButton;



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
        
        profileHeader = findViewById(R.id.profileHeader);
        signInScreen = findViewById(R.id.signInScreen);

        findViewById(R.id.settingsButton).setOnClickListener(v -> {
            Intent intent = new Intent(getContext(), SettingsActivity.class);
            getContext().startActivity(intent);
        });
        findViewById(R.id.signInButton).setOnClickListener(v -> {
            Intent intent = new Intent(getContext(), LoginActivity.class);
            getContext().startActivity(intent);
        });
        logOutButton = findViewById(R.id.logOutButton);
        logOutButton.setOnClickListener(v -> {
            new AlertDialog.Builder(getContext()).setTitle("Log out").setMessage("Are you sure you want to log out?").setPositiveButton("Yes", (dialog, which) -> {
                if(AuthRepository.getInstance(getContext()).getCurrentUser() != null)
                    AuthRepository.getInstance(getContext()).logout(new AuthRepository.AuthCallback<Void>() {
                        @Override
                        public void onSuccess(Void data) {
                            reloadingPage();
                        }

                        @Override
                        public void onError(String message) {
                            reloadingPage();
                        }
                    });
            }).setNegativeButton("No", null).show();
        });

        profileSwipeRefreshLayout.setOnRefreshListener(this::reloadingPage);

        reloadingPage();
    }

    public void reloadingPage()
    {
        if(AuthRepository.getInstance(getContext()).getCurrentUser() != null)
        {
            profileNameText.setText(AuthRepository.getInstance(getContext()).getCurrentUser().getUsername());
            ImageHelper.getImageBitmapFromNetwork(getContext(), "https://account.vanvatcorp.com" + AuthRepository.getInstance(getContext()).getCurrentUser().getAvatarUrl(), profileAvatarImage);
            
            profileHeader.setVisibility(View.VISIBLE);
            signInScreen.setVisibility(View.GONE);
            logOutButton.setVisibility(View.VISIBLE);
        }
        else
        {
            profileNameText.setText("");
            profileAvatarImage.setImageResource(R.drawable.logo); // Default logo if needed, though hidden
            
            profileHeader.setVisibility(View.GONE);
            signInScreen.setVisibility(View.VISIBLE);
            logOutButton.setVisibility(View.GONE); // Hide logout if not signed in
        }

        profileSwipeRefreshLayout.setRefreshing(false);
    }











}
