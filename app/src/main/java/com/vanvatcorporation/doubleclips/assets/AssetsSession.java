package com.vanvatcorporation.doubleclips.assets;

import android.content.Context;

import com.vanvatcorporation.doubleclips.dynamiclibs.auth.RetrofitClient;
import com.vanvatcorporation.drivekit.AssetsClient;
import com.vanvatcorporation.drivekit.DriveClient;
import com.vanvatcorporation.drivekit.OpenEditzClient;

/** The one {@link AssetsClient} of the app, signed in with the same account as the Storage tab (browsing needs no sign-in). */
public final class AssetsSession {
    private AssetsSession() { }

    private static AssetsClient client;
    private static OpenEditzClient openEditz;
    private static Context appContext;

    public static synchronized AssetsClient get(Context context) {
        if (client == null) {
            appContext = context.getApplicationContext();
            DriveClient drive = new DriveClient(DriveClient.DEFAULT_BASE, new DriveClient.Auth() {
                @Override public String token() { return RetrofitClient.getInstance(appContext).getAuthToken(); }
                @Override public void onRefreshed(String token) { /* the cookie jar stays the source of truth */ }
                @Override public void onUnauthorized() { /* the Profile tab handles signing in again */ }
            });
            client = new AssetsClient(drive, appContext.getCacheDir());
        }
        return client;
    }

    /** The protected OpenEditz folder of the account (a posted file is uploaded to OpenEditz/Assets first). */
    public static synchronized OpenEditzClient openEditz(Context context) {
        if (openEditz == null) openEditz = new OpenEditzClient(get(context).drive());
        return openEditz;
    }

    public static boolean hasSession(Context context) {
        return RetrofitClient.getInstance(context.getApplicationContext()).getAuthToken() != null;
    }
}
