package com.vanvatcorporation.doubleclips;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.vanvatcorporation.doubleclips.popups.AdsConsentPopup;

public class AdsHandler {

    public static RewardedAd mRewardedAd;
    public static InterstitialAd mInterstitialAd;
    public static boolean wasPreviouslyShowingAds;

    // ─── Frequency-cap constants ───────────────────────────────────────────────
    /** Minimum milliseconds between any two ad shows (5 minutes). */
    private static final long COOLDOWN_MS = 5 * 60 * 1_000L;
    /** Maximum rewarded ad shows per calendar day. */
    private static final int MAX_REWARDED_PER_DAY = 10;
    /** Maximum interstitial ad shows per calendar day. */
    private static final int MAX_INTERSTITIAL_PER_DAY = 20;

    // SharedPreferences keys
    private static final String PREFS_ADS = "ads_frequency_prefs";
    private static final String KEY_LAST_AD_TIME = "last_ad_shown_time";
    private static final String KEY_REWARDED_COUNT = "rewarded_count_today";
    private static final String KEY_INTERSTITIAL_COUNT = "interstitial_count_today";
    private static final String KEY_COUNT_DATE = "count_date_day"; // stores day-of-year

    // ─── Frequency-cap helpers ─────────────────────────────────────────────────

    private static SharedPreferences getAdsPrefs(Context context) {
        return context.getSharedPreferences(PREFS_ADS, Context.MODE_PRIVATE);
    }

    /**
     * Returns today's day-of-year (1-366) for daily counter resets.
     */
    private static int todayDayOfYear() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        return cal.get(java.util.Calendar.DAY_OF_YEAR);
    }

    /**
     * Resets daily counters if the stored date no longer matches today.
     */
    private static void resetDailyCountersIfNeeded(SharedPreferences prefs) {
        int storedDay = prefs.getInt(KEY_COUNT_DATE, -1);
        int today = todayDayOfYear();
        if (storedDay != today) {
            prefs.edit()
                    .putInt(KEY_COUNT_DATE, today)
                    .putInt(KEY_REWARDED_COUNT, 0)
                    .putInt(KEY_INTERSTITIAL_COUNT, 0)
                    .apply();
        }
    }

    /**
     * Returns true if enough time has passed since the last ad was shown.
     */
    private static boolean isCooldownOver(SharedPreferences prefs) {
        long lastTime = prefs.getLong(KEY_LAST_AD_TIME, 0L);
        return (System.currentTimeMillis() - lastTime) >= COOLDOWN_MS;
    }

    /**
     * Records that an ad was just shown, bumping the appropriate daily counter.
     *
     * @param isRewarded true for rewarded ads, false for interstitial ads.
     */
    private static void recordAdShown(Context context, boolean isRewarded) {
        SharedPreferences prefs = getAdsPrefs(context);
        resetDailyCountersIfNeeded(prefs);

        SharedPreferences.Editor editor = prefs.edit()
                .putLong(KEY_LAST_AD_TIME, System.currentTimeMillis());

        if (isRewarded) {
            int count = prefs.getInt(KEY_REWARDED_COUNT, 0);
            editor.putInt(KEY_REWARDED_COUNT, count + 1);
        } else {
            int count = prefs.getInt(KEY_INTERSTITIAL_COUNT, 0);
            editor.putInt(KEY_INTERSTITIAL_COUNT, count + 1);
        }
        editor.apply();
    }

    /**
     * Returns true when a rewarded ad is allowed to be shown right now
     * (cooldown has passed AND daily cap not reached).
     */
    public static boolean canShowRewardedAd(Context context) {
        SharedPreferences prefs = getAdsPrefs(context);
        resetDailyCountersIfNeeded(prefs);
        if (!isCooldownOver(prefs))
            return false;
        int count = prefs.getInt(KEY_REWARDED_COUNT, 0);
        return count < MAX_REWARDED_PER_DAY;
    }

    /**
     * Returns true when an interstitial ad is allowed to be shown right now
     * (cooldown has passed AND daily cap not reached).
     */
    public static boolean canShowInterstitialAd(Context context) {
        SharedPreferences prefs = getAdsPrefs(context);
        resetDailyCountersIfNeeded(prefs);
        if (!isCooldownOver(prefs))
            return false;
        int count = prefs.getInt(KEY_INTERSTITIAL_COUNT, 0);
        return count < MAX_INTERSTITIAL_PER_DAY;
    }

    // ─── Ad loading ────────────────────────────────────────────────────────────

    public static void loadRewardedAds(Context context, Activity activity) {
        RewardedAd.load(context, "ca-app-pub-1708105276845874/6458040635", // Test:
                                                                           // ca-app-pub-3940256099942544/5224354917
                new AdRequest.Builder().build(), new RewardedAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull RewardedAd rewardedAd) {
                        mRewardedAd = rewardedAd;
                        if (mInterstitialAd != null) {
                            new AdsConsentPopup(context, activity);
                        }
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        mRewardedAd = null;
                    }
                });
    }

    public static void loadInterstitialAd(Context context, Activity activity) {
        InterstitialAd.load(context,
                "ca-app-pub-1708105276845874/3681324746", // Test: ca-app-pub-3940256099942544/1033173712
                new AdRequest.Builder().build(),
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd interstitialAd) {
                        mInterstitialAd = interstitialAd;
                        if (mRewardedAd != null) {
                            new AdsConsentPopup(context, activity);
                        }

                        mInterstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                            @Override
                            public void onAdDismissedFullScreenContent() {
                                mInterstitialAd = null;
                                loadInterstitialAd(context, activity); // Preload next ad
                                wasPreviouslyShowingAds = true;
                            }

                            @Override
                            public void onAdFailedToShowFullScreenContent(AdError adError) {
                                mInterstitialAd = null;
                            }

                            @Override
                            public void onAdShowedFullScreenContent() {
                                mInterstitialAd = null;
                            }
                        });
                    }

                    @Override
                    public void onAdFailedToLoad(LoadAdError loadAdError) {
                        mInterstitialAd = null;
                    }
                });
    }

    // ─── Ad showing (with frequency-cap guard) ─────────────────────────────────

    /**
     * Shows a rewarded ad only if frequency caps allow it.
     * Always call {@link #canShowRewardedAd(Context)} first to check UI state.
     */
    public static void showRewardedAds(Context context, Activity activity, RewardedAd ad) {
        if (ad == null)
            return;
        if (!canShowRewardedAd(context))
            return; // cooldown or daily cap hit

        ad.show(activity, rewardItem -> {
            recordAdShown(context, true);
            wasPreviouslyShowingAds = true;
        });
    }

    /**
     * Shows an interstitial ad only if frequency caps allow it.
     * Always call {@link #canShowInterstitialAd(Context)} first to check UI state.
     */
    public static void showInterstitialAds(Context context, Activity activity, InterstitialAd ad) {
        if (ad == null)
            return;
        if (!canShowInterstitialAd(context))
            return; // cooldown or daily cap hit

        // Record before show since onAdShowedFullScreenContent fires immediately
        recordAdShown(context, false);
        ad.show(activity);
        wasPreviouslyShowingAds = true;
    }

    // ─── Initialization / bulk loading ─────────────────────────────────────────

    public static void loadBothAds(Context context, Activity activity) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean adsAllowed = prefs.getBoolean("ads_popup", true);
        if (!adsAllowed)
            return;

        AdsHandler.loadRewardedAds(context, activity);
        AdsHandler.loadInterstitialAd(context, activity);
    }

    public static void initializeAds(Context context, Activity activity) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean adsAllowed = prefs.getBoolean("ads_popup", true);
        if (!adsAllowed)
            return;

        MobileAds.initialize(context, initializationStatus -> loadBothAds(context, activity));
    }

    // ─── Thank-you popup ───────────────────────────────────────────────────────

    public static void displayThanksForShowingAds(Context context) {
        if (wasPreviouslyShowingAds) {
            wasPreviouslyShowingAds = false;
            new AdsConsentPopup.AdsThanksLetterPopup(context);
        }
    }
}
