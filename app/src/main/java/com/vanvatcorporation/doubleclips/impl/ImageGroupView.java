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
    /** A video clip's sound, drawn at the current zoom along the bottom edge (not part of the stretched thumbnail bitmap). */
    WaveformBandView waveformBand;
    /** "Applying Proxy... 52.6%" while a job runs for this clip (reverse, motion, proxy, ...); hidden otherwise. */
    android.widget.TextView processingLabel;

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

        waveformBand = new WaveformBandView(context);
        RelativeLayout.LayoutParams bandParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        bandParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        waveformBand.setLayoutParams(bandParams);
        waveformBand.setVisibility(GONE); // until a clip with sound gives it levels
        addView(waveformBand);

        processingLabel = new android.widget.TextView(context);
        processingLabel.setTextColor(0xFFFFFFFF);
        processingLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        processingLabel.setSingleLine(true);
        processingLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        processingLabel.setGravity(android.view.Gravity.CENTER);
        processingLabel.setBackgroundColor(0xAA000000);
        processingLabel.setVisibility(GONE);
        RelativeLayout.LayoutParams labelParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.addRule(RelativeLayout.CENTER_VERTICAL);
        addView(processingLabel, labelParams);

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

    /** Shows {@code label} over the clip (null hides it). Cheap to call on every progress tick: nothing changes if the text is the same. */
    public void setProcessingLabel(String label) {
        if (label == null) {
            if (processingLabel.getVisibility() != GONE) processingLabel.setVisibility(GONE);
            return;
        }
        if (!label.contentEquals(processingLabel.getText())) processingLabel.setText(label);
        if (processingLabel.getVisibility() != VISIBLE) processingLabel.setVisibility(VISIBLE);
    }

    public WaveformBandView getWaveformBand() {
        return waveformBand;
    }

    /** Shows the clip's sound as a band {@code bandHeightPx} tall along the bottom; it redraws itself at any zoom. */
    public void setWaveform(float[] levels, float startSec, float spanSec, WaveformBandView.Window window,
                            int bandHeightPx, int barWidthPx, int barGapPx) {
        ViewGroup.LayoutParams lp = waveformBand.getLayoutParams();
        lp.height = Math.max(8, bandHeightPx);
        waveformBand.setLayoutParams(lp);
        waveformBand.setLevels(levels, startSec, spanSec, window, barWidthPx, barGapPx);
        waveformBand.setVisibility(VISIBLE);
    }

    /** A standalone audio clip: its sound fills the whole clip view in the audio look, redrawn at any zoom. */
    public void setAudioWaveform(float[] levels, float startSec, float spanSec, WaveformBandView.Window window,
                                 int barWidthPx, int barGapPx) {
        ViewGroup.LayoutParams lp = waveformBand.getLayoutParams();
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        waveformBand.setLayoutParams(lp);
        waveformBand.setAudioClipStyle(true);
        waveformBand.setLevels(levels, startSec, spanSec, window, barWidthPx, barGapPx);
        waveformBand.setVisibility(VISIBLE);
    }

    /** The drag ghost shows the same sound as the clip it stands for. */
    public void copyWaveformFrom(ImageGroupView other) {
        if (other == null || other.waveformBand == null || !other.waveformBand.hasLevels()) return;
        ViewGroup.LayoutParams lp = waveformBand.getLayoutParams();
        lp.height = other.waveformBand.getLayoutParams().height;
        waveformBand.setLayoutParams(lp);
        waveformBand.copyFrom(other.waveformBand);
        waveformBand.setVisibility(VISIBLE);
    }

    public void showLoading() {
        loadingIndicator.setVisibility(VISIBLE);
    }

    public void hideLoading() {
        loadingIndicator.setVisibility(GONE);
    }

}
