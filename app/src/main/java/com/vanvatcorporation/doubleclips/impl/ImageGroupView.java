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

    public ImageGroupView(Context context) {
        super(context);

        filledImageView = new ImageView(context);
        filledImageView.setLayoutParams(new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(filledImageView);
    }

    public ImageGroupView(Context context, AttributeSet attrs) {
        super(context, attrs);

        filledImageView = new ImageView(context);
        filledImageView.setLayoutParams(new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(filledImageView);
    }

    public ImageGroupView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        filledImageView = new ImageView(context);
        filledImageView.setLayoutParams(new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(filledImageView);
    }

    public ImageGroupView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        filledImageView = new ImageView(context);
        filledImageView.setLayoutParams(new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(filledImageView);
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
    }

}
