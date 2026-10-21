package com.vanvatcorporation.drivekit;

import java.util.Locale;

/**
 * Quota maths and wording shared by the native Drive apps, the same as the web Drive:
 * bars turn yellow below 50% remaining and red below 25%, and transfers that probably won't fit
 * today's allowance get a "Continue anyway" warning instead of being blocked.
 */
public final class Quota {
    private Quota() {}

    /** Tiers live on the account (one pack for every Van Vat service), so "more resources" opens the Account Center. */
    public static final String PLANS_URL = "https://account.vanvatcorp.com/account-center/#plans";

    public static final int NORMAL = 0, WARN = 1, DANGER = 2;
    public static final double WARN_REMAINING_RATIO = 0.50, DANGER_REMAINING_RATIO = 0.25;

    public static int tone(double used, double total) {
        if (total <= 0) return NORMAL;
        double ratio = Math.max(0, total - used) / total;
        if (ratio < DANGER_REMAINING_RATIO) return DANGER;
        if (ratio < WARN_REMAINING_RATIO) return WARN;
        return NORMAL;
    }

    /** ARGB colour of a tone (blue / orange / red: readable on light and dark themes). */
    public static int color(int tone) {
        return tone == DANGER ? 0xFFE53935 : tone == WARN ? 0xFFFB8C00 : 0xFF1E88E5;
    }

    /** Progress 0..1000 for a ProgressBar with max=1000. */
    public static int progress(double used, double total) {
        if (total <= 0) return 0;
        return (int) Math.max(0, Math.min(1000, used * 1000 / total));
    }

    public static long storageFree(Models.Me me) { return me == null ? Long.MAX_VALUE : Math.max(0, me.quotaBytes - me.usedBytes); }
    public static long uploadFree(Models.Me me) {
        return me == null || me.usage == null ? Long.MAX_VALUE : Math.max(0, me.usage.uploadLimit - me.usage.uploadedBytes);
    }
    public static long downloadFree(Models.Me me) {
        return me == null || me.usage == null ? Long.MAX_VALUE : Math.max(0, me.usage.downloadLimit - me.usage.downloadedBytes);
    }

    /** The server refused because of a quota or plan limit: the useful next step is "Get more resources". */
    public static boolean isQuotaLimit(Throwable e) {
        if (!(e instanceof DriveException)) return false;
        return isQuotaCode(((DriveException) e).code);
    }

    public static boolean isQuotaCode(String code) {
        return "quota_exceeded".equals(code) || "file_too_large".equals(code) || "daily_upload_limit".equals(code)
                || "daily_download_limit".equals(code) || "daily_compute_limit".equals(code);
    }

    // ── wording (same as the web Drive) ─────────────────────────────────────

    public static String uploadWarnTitle() { return "Daily upload limit warning"; }
    public static String uploadWarnMessage(long size, long free) {
        return "You might not have enough daily upload quota left to upload this file (" + bytes(size) + " selected, only "
                + bytes(free) + " left today). The upload may stop once the limit is reached.";
    }

    public static String downloadWarnTitle() { return "Daily download limit warning"; }
    public static String downloadWarnMessage(long size, long free) {
        return "You might not have enough daily download quota left for this file (" + bytes(size) + " requested, only "
                + bytes(free) + " left today). The download may be cut off once the limit is reached.";
    }
    public static String previewWarnMessage(long size, long free) {
        return "You might not have enough daily download quota left to preview this file (" + bytes(size) + " needed, only "
                + bytes(free) + " left today). Previewing uses your daily download allowance.";
    }

    public static String computeWarnTitle() { return "Daily compute limit warning"; }
    public static String computeWarnMessage(double cost, double free) {
        return "Zipping this selection needs about " + credits(cost) + " compute credits, but only " + credits(free)
                + " are left today (the limit is shared across all Van Vat services). The download may be refused.";
    }

    public static String notEnoughSpace(long free, long quota) {
        return "Not enough space: you have " + bytes(free) + " left of " + bytes(quota) + ".";
    }

    public static String bytes(long n) {
        if (n < 1024) return n + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = n;
        int i = -1;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }

    /** Whole credits without decimals, otherwise one decimal. */
    public static String credits(double n) {
        return n == Math.rint(n) ? String.valueOf((long) n) : String.format(Locale.US, "%.1f", n);
    }
}
