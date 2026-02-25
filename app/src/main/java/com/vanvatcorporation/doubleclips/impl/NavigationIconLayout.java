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

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.NavigationIconLayout);
            Drawable icon = a.getDrawable(R.styleable.NavigationIconLayout_navIcon);
            String text = a.getString(R.styleable.NavigationIconLayout_navText);

            if (icon != null) {
                iconView.setImageDrawable(icon);
                iconView.setColorFilter(getContext().getColor(R.color.colorPrimaryButton), PorterDuff.Mode.SRC_ATOP);
            }
            if (text != null) {
                textView.setText(text);
                textView.setTextColor(getContext().getColor(R.color.colorPrimaryButton));
            }

            a.recycle();
        }
    }

    public void runAnimation(AnimationType type)
    {
        switch (type)
        {
            case SELECTED:
                textView.setTextColor(getContext().getColor(R.color.colorHighlightedButton));
                textView.setTypeface(null, Typeface.BOLD);
                iconView.setColorFilter(getContext().getColor(R.color.colorHighlightedButton), PorterDuff.Mode.SRC_ATOP);
                iconView.startAnimation(AnimationUtils.loadAnimation(getContext(), R.anim.anim_expand_rapidly));
                break;
            case UNSELECTED:
                textView.setTextColor(getContext().getColor(R.color.colorPrimaryBackground));
                textView.setTypeface(null, Typeface.NORMAL);
                iconView.setColorFilter(R.color.colorPrimaryButton, PorterDuff.Mode.SRC_ATOP);
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
