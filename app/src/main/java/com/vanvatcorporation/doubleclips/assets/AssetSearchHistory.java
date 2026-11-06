package com.vanvatcorporation.doubleclips.assets;

import android.content.Context;

import com.vanvatcorporation.doubleclips.helper.TemplateSearchHistory;

/** The Assets searches this phone submitted: the same store as the Templates one, in a file of its own (the two lists don't mix). */
public final class AssetSearchHistory {
    private AssetSearchHistory() { }

    private static TemplateSearchHistory instance;

    public static synchronized TemplateSearchHistory get(Context context) {
        if (instance == null) {
            instance = new TemplateSearchHistory(context.getApplicationContext().getSharedPreferences("asset_search", Context.MODE_PRIVATE));
        }
        return instance;
    }
}
