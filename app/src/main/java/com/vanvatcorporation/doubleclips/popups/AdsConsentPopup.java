package com.vanvatcorporation.doubleclips.popups;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

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

        View rewardedBtn     = dialogView.findViewById(R.id.rewardedAdsButton);
        View interstitialBtn = dialogView.findViewById(R.id.interstitialAdsButton);

        // Hide ad buttons when frequency caps are reached so the popup still
        // shows (for transparency) but cannot trigger extra invalid traffic.
        boolean canRewarded     = AdsHandler.canShowRewardedAd(context);
        boolean canInterstitial = AdsHandler.canShowInterstitialAd(context);

        rewardedBtn.setVisibility(canRewarded ? View.VISIBLE : View.GONE);
        interstitialBtn.setVisibility(canInterstitial ? View.VISIBLE : View.GONE);

        rewardedBtn.setOnClickListener(v -> {
            dialog.dismiss();
            AdsHandler.showRewardedAds(context, activity, AdsHandler.mRewardedAd);
        });
        interstitialBtn.setOnClickListener(v -> {
            dialog.dismiss();
            AdsHandler.showInterstitialAds(context, activity, AdsHandler.mInterstitialAd);
        });
        dialogView.findViewById(R.id.declineAdsButton).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        if(!canRewarded && !canInterstitial)
            dialog.dismiss();
    }

    public static class AdsThanksLetterPopup extends AlertDialog.Builder {
        public AdsThanksLetterPopup(Context context) {
            super(context);

            LayoutInflater inflater = LayoutInflater.from(context);
            View dialogView = inflater.inflate(R.layout.popup_thanks_for_showing_ads, null);
            setView(dialogView);

            setNegativeButton(context.getText(R.string.close), (dialog, which) -> dialog.dismiss());

            AlertDialog dialog = this.create();
            dialog.show();
        }
    }
}
