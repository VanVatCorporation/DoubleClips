package com.vanvatcorporation.doubleclips.helper;

import android.content.Context;
import android.content.pm.PackageManager;

public class AppSourceChecker {
    public enum InstallerMethod {
        PLAY_STORE, AMAZON_STORE, OTHER, UNKNOWN
    }


    public static InstallerMethod getSelfInstallerPackageMethod(Context context) {
        return getInstallerPackageMethod(context, context.getPackageName());
    }
    public static InstallerMethod getInstallerPackageMethod(Context context, String packageName) {
        PackageManager packageManager = context.getPackageManager();
        String installer = packageManager.getInstallerPackageName(packageName);

        if (installer == null) {
            return InstallerMethod.UNKNOWN;
        } else if (installer.equals("com.android.vending")) {
            return InstallerMethod.PLAY_STORE;
        } else if (installer.equals("com.amazon.venezia")) {
            return InstallerMethod.AMAZON_STORE;
        } else {
            return InstallerMethod.OTHER;
        }
    }

    public static String getSelfInstallerPackageName(Context context) {
        return getInstallerPackageName(context, context.getPackageName());
    }
    public static String getInstallerPackageName(Context context, String packageName) {
        PackageManager packageManager = context.getPackageManager();
        String installer = packageManager.getInstallerPackageName(packageName);

        if (installer == null) {
            return "Unknown (likely installed via APK)";
        } else if (installer.equals("com.android.vending")) {
            return "Installed via Google Play Store";
        } else if (installer.equals("com.amazon.venezia")) {
            return "Installed via Amazon Appstore";
        } else {
            return "Installed via other source: " + installer;
        }
    }
}