package com.vanvatcorporation.doubleclips.impl;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;

public class ImageGroupView extends RelativeLayout {
    ImageView filledImageView;
    Bitmap filledImageBitmap;
    android.widget.ProgressBar loadingIndicator;

    public ImageGroupView(Context context) {
        super(context);
        init(context);
    }

    public ImageGroupView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ImageGroupView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public ImageGroupView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    private void init(Context context) {
        filledImageView = new ImageView(context);
        filledImageView.setLayoutParams(new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(filledImageView);

        // Loading indicator
        loadingIndicator = new android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.CENTER_VERTICAL);
        lp.leftMargin = 15;
        loadingIndicator.setLayoutParams(lp);
        loadingIndicator.setVisibility(GONE); // Hidden by default
        addView(loadingIndicator);
    }


    public ImageView getFilledImageView()
    {
        return filledImageView;
    }

    public Bitmap getFilledImageBitmap() {
        return filledImageBitmap;
    }
    public void setFilledImageBitmap(Bitmap filledImageBitmap)
    {
        this.filledImageBitmap = filledImageBitmap;
        filledImageView.setImageBitmap(filledImageBitmap);
        filledImageView.setScaleType(ImageView.ScaleType.FIT_XY);
        hideLoading();
    }

    public void showLoading() {
        loadingIndicator.setVisibility(VISIBLE);
    }

    public void hideLoading() {
        loadingIndicator.setVisibility(GONE);
    }

}
