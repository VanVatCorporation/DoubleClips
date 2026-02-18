package com.vanvatcorporation.doubleclips.popups;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import com.vanvatcorporation.doubleclips.AdsHandler;
import com.vanvatcorporation.doubleclips.R;

public class AdsConsentPopup {

    public AdsConsentPopup(Context context, Activity activity) {
        android.app.Dialog dialog = new android.app.Dialog(context, com.google.android.material.R.style.ThemeOverlay_Material3_BottomSheetDialog);
        View dialogView = LayoutInflater.from(context).inflate(R.layout.popup_asking_for_ads, null);
        dialog.setContentView(dialogView);

        // Bottom-sheet style: full width, anchored to bottom, transparent background
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setGravity(Gravity.BOTTOM);
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        // Wire up buttons
        dialogView.findViewById(R.id.rewardedAdsButton).setOnClickListener(v -> {
            dialog.dismiss();
            AdsHandler.showRewardedAds(activity, AdsHandler.mRewardedAd);
        });
        dialogView.findViewById(R.id.interstitialAdsButton).setOnClickListener(v -> {
            dialog.dismiss();
            AdsHandler.showInterstitialAds(activity, AdsHandler.mInterstitialAd);
        });
        dialogView.findViewById(R.id.declineAdsButton).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }



    public static class AdsThanksLetterPopup extends AlertDialog.Builder {
        public AdsThanksLetterPopup(Context context) {
            super(context);

            // Inflate your custom layout
            LayoutInflater inflater = LayoutInflater.from(context);
            View dialogView = inflater.inflate(R.layout.popup_thanks_for_showing_ads, null);
            setView(dialogView);


            setNegativeButton(context.getText(R.string.close), (dialog, which) -> {
                dialog.dismiss();
            });

            // Create the AlertDialog
            AlertDialog dialog = this.create();

            // Show the dialog
            dialog.show();
        }
    }
}
