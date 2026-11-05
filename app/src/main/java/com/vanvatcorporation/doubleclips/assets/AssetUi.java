package com.vanvatcorporation.doubleclips.assets;

import android.content.Context;

import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.drivekit.AssetKind;

import java.util.Locale;

/** Small shared bits of the Assets screens: the icon and the texts of a kind, readable sizes and counts. */
public final class AssetUi {
    private AssetUi() { }

    public static int icon(AssetKind kind) {
        switch (kind) {
            case VIDEO: return R.drawable.ic_asset_video;
            case IMAGE: return R.drawable.ic_asset_image;
            case AUDIO: return R.drawable.ic_asset_audio;
            case FONT: return R.drawable.ic_asset_font;
            case EFFECT: return R.drawable.ic_asset_effect;
            default: return R.drawable.baseline_folder_24;
        }
    }

    /** The filter chip's text ("Videos"). */
    public static String chipTitle(Context context, AssetKind kind) {
        switch (kind) {
            case VIDEO: return context.getString(R.string.assets_kind_videos);
            case IMAGE: return context.getString(R.string.assets_kind_images);
            case AUDIO: return context.getString(R.string.assets_kind_audio);
            case FONT: return context.getString(R.string.assets_kind_fonts);
            case EFFECT: return context.getString(R.string.assets_kind_effects);
            default: return context.getString(R.string.assets_kind_packs);
        }
    }

    /** One asset of this kind ("Video"). */
    public static String singular(Context context, AssetKind kind) {
        switch (kind) {
            case VIDEO: return context.getString(R.string.assets_kind_video);
            case IMAGE: return context.getString(R.string.assets_kind_image);
            case AUDIO: return context.getString(R.string.assets_kind_audio_one);
            case FONT: return context.getString(R.string.assets_kind_font);
            case EFFECT: return context.getString(R.string.assets_kind_effect);
            default: return context.getString(R.string.assets_kind_pack);
        }
    }

    /** 1.4 MB, 820 KB, 312 B. */
    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, kb < 10 ? "%.1f KB" : "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, mb < 10 ? "%.1f MB" : "%.0f MB", mb);
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0);
    }

    /** 980, 1.2K, 3.4M. */
    public static String count(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format(Locale.ROOT, "%.1fK", n / 1000.0);
        return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
    }
}
