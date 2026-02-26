package com.vanvatcorporation.doubleclips.activities.editing;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import android.widget.RelativeLayout;

import com.google.android.material.imageview.ShapeableImageView;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.main.BaseAreaScreen;

import java.util.ArrayList;
import java.util.List;

public class BaseEditSpecificAreaScreen extends BaseAreaScreen {

    public ShapeableImageView windowBackground;
    public View closeWindowButton;
    public ArrayList<Runnable> onClose = new ArrayList<>();
    public ArrayList<Runnable> onOpen = new ArrayList<>();

    public AnimationScreen animationScreen = AnimationScreen.ToTop;


    public BaseEditSpecificAreaScreen(Context context) {
        super(context);
    }

    public BaseEditSpecificAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public BaseEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public BaseEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void init() {
        super.init();
        windowBackground = findViewById(R.id.windowBackground);
        closeWindowButton = findViewById(R.id.closeWindowButton);
        setVisibility(GONE);

        // Only visible that we gonna block the view
        windowBackground.setOnLongClickListener(v -> false);
        closeWindowButton.setOnClickListener(v -> {
            close();
        });
    }
    public void open()
    {
        animateLayout(AnimationType.Open);

        for (Runnable run : onOpen) {
            run.run();
        }
    }
    public void close()
    {
        animateLayout(AnimationType.Close);

        for (Runnable run : onClose) {
            run.run();
        }
    }


    public void animateLayout(AnimationType type) {
        Animation animation;
        if(type == AnimationType.Close)
        {
            animation = AnimationUtils.loadAnimation(getContext(),
                    animationScreen == AnimationScreen.ToTop ?
                            R.anim.anim_fly_out_to_top_ancitipated :
                            animationScreen == AnimationScreen.ToBottom ?
                                    R.anim.anim_fly_out_to_bot_ancitipated :
                                    R.anim.anim_fade_out
            );
            animation.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {

                }

                @Override
                public void onAnimationEnd(Animation animation) {
                    setVisibility(View.GONE);

                }

                @Override
                public void onAnimationRepeat(Animation animation) {

                }
            });
        }
        else {
            setVisibility(VISIBLE);
            animation = AnimationUtils.loadAnimation(getContext(),
                    animationScreen == AnimationScreen.ToTop ?
                            R.anim.anim_fly_in_to_top_ancitipated :
                            animationScreen == AnimationScreen.ToBottom ?
                                    R.anim.anim_fly_in_to_bot_ancitipated :
                                    R.anim.anim_fade_in
            );
        }


        if(animation != null)
            startAnimation(animation);
    }

    public enum AnimationScreen {
        ToTop, ToBottom
    }
    public enum AnimationType {
        Open, Close
    }
}
