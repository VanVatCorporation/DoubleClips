package com.vanvatcorporation.doubleclips.impl;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.vanvatcorporation.doubleclips.R;

public class NavigationIconLayout extends RelativeLayout {
    ImageView iconView;
    TextView textView;

    public NavigationIconLayout(Context context) {
        super(context);
    }

    public NavigationIconLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public NavigationIconLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    public NavigationIconLayout(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        LayoutInflater.from(context).inflate(R.layout.merge_navigation_icon, this, true);
        iconView = findViewById(R.id.navigationIcon);
        textView = findViewById(R.id.navigationText);

        // Resolve theme-aware icon and text color
        int[] themeAttr = { android.R.attr.textColorSecondary };
        android.util.TypedValue tv = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.textColorSecondary, tv, true);
        int defaultColor = tv.data;

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.NavigationIconLayout);
            Drawable icon = a.getDrawable(R.styleable.NavigationIconLayout_navIcon);
            String text = a.getString(R.styleable.NavigationIconLayout_navText);

            if (icon != null) {
                iconView.setImageDrawable(icon);
                iconView.setColorFilter(defaultColor, PorterDuff.Mode.SRC_ATOP);
            }
            if (text != null) {
                textView.setText(text);
                textView.setTextColor(defaultColor);
            }

            a.recycle();
        }
    }

    public void runAnimation(AnimationType type)
    {
        switch (type)
        {
            case SELECTED:
                int blueColor = getContext().getColor(R.color.ios_blue);
                textView.setTextColor(blueColor);
                textView.setTypeface(null, Typeface.BOLD);
                iconView.setColorFilter(blueColor, PorterDuff.Mode.SRC_ATOP);
                iconView.startAnimation(AnimationUtils.loadAnimation(getContext(), R.anim.anim_expand_rapidly));
                break;
            case UNSELECTED:
                android.util.TypedValue tvU = new android.util.TypedValue();
                getContext().getTheme().resolveAttribute(android.R.attr.textColorSecondary, tvU, true);
                int secondaryColor = tvU.data;
                textView.setTextColor(secondaryColor);
                textView.setTypeface(null, Typeface.NORMAL);
                iconView.setColorFilter(secondaryColor, PorterDuff.Mode.SRC_ATOP);
                iconView.startAnimation(AnimationUtils.loadAnimation(getContext(), R.anim.anim_shrink_rapidly));
                break;
        }
    }



    public ImageView getIconView()
    {
        return iconView;
    }
    public TextView getTextView()
    {
        return textView;
    }

    public enum AnimationType {
        SELECTED, UNSELECTED
    }
}
