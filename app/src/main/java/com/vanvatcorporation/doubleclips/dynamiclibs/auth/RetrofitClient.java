package com.vanvatcorporation.doubleclips.dynamiclibs.auth;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RetrofitClient {

    private static final String BASE_URL = "https://account.vanvatcorp.com";
    private static RetrofitClient instance;
    private Retrofit retrofit;

    private RetrofitClient(Context context) {
        // Use PersistentCookieJar based on SharedPreferences
        PersistentCookieJar cookieJar = new PersistentCookieJar(context);

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        OkHttpClient client = new OkHttpClient.Builder()
                .cookieJar(cookieJar) // Handles httpOnly cookies and persists them!
                .addInterceptor(logging)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .client(client)
                .build();
    }

    public static synchronized RetrofitClient getInstance(Context context) {
        if (instance == null) {
            instance = new RetrofitClient(context.getApplicationContext());
        }
        return instance;
    }

    public ApiService getApi() {
        return retrofit.create(ApiService.class);
    }

    public void clearCookies() {
        if (retrofit.callFactory() instanceof OkHttpClient) {
            CookieJar jar = ((OkHttpClient) retrofit.callFactory()).cookieJar();
            if (jar instanceof PersistentCookieJar) {
                ((PersistentCookieJar) jar).clear();
            }
        }
    }

    /**
     * persistent CookieJar implementation using SharedPreferences
     */
    private static class PersistentCookieJar implements CookieJar {
        private final Map<String, List<Cookie>> cookieStore = new HashMap<>();
        private final SharedPreferences sharedPreferences;
        private final Gson gson;
        private static final String PREF_NAME = "cookie_prefs";
        private static final String COOKIE_KEY = "cookies";

        public PersistentCookieJar(Context context) {
            this.sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            this.gson = new Gson();
            loadCookies();
        }

        public void clear() {
            cookieStore.clear();
            sharedPreferences.edit().remove(COOKIE_KEY).apply();
        }

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            // Update in-memory store
            cookieStore.put(url.host(), cookies);
            // Persist to SharedPreferences
            saveCookies();
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> cookies = cookieStore.get(url.host());
            return cookies != null ? cookies : new ArrayList<>();
        }

        private void saveCookies() {
            // We need to implement a custom serializable format or DTO for cookies because
            // okhttp3.Cookie is not easily GSON serializable without custom adapters
            // sometimes.
            // But for simplicity, we will assume standard GSON serialization works or use a
            // simplified map.
            // Actually, OkHttp Cookies complicate GSON. Let's start simple.

            // For a robust implementation, we would map Host -> List<SerializableCookie>.
            // Here, we simplify by storing the whole map.
            // NOTE: okhttp3.Cookie fields (like 'httpOnly', 'secure') are final and private
            // with no setters,
            // so GSON deserialization might fail or produce empty objects if not careful.

            // Better approach for "copy-paste" limit:
            // Serialization logic is complex for Cookies.
            // Let's rely on a simplified SerializableCookie wrapper if needed,
            // but for now, we'll try direct serialization and if it fails, the user will
            // know.
            // ACTUALLY: Let's do it manually to be safe.

            Map<String, List<SerializableCookie>> serializableStore = new HashMap<>();
            for (Map.Entry<String, List<Cookie>> entry : cookieStore.entrySet()) {
                List<SerializableCookie> list = new ArrayList<>();
                for (Cookie c : entry.getValue()) {
                    list.add(new SerializableCookie(c));
                }
                serializableStore.put(entry.getKey(), list);
            }

            String json = gson.toJson(serializableStore);
            sharedPreferences.edit().putString(COOKIE_KEY, json).apply();
        }

        private void loadCookies() {
            String json = sharedPreferences.getString(COOKIE_KEY, null);
            if (json != null) {
                Type type = new TypeToken<Map<String, List<SerializableCookie>>>() {
                }.getType();
                Map<String, List<SerializableCookie>> serializableStore = gson.fromJson(json, type);

                if (serializableStore != null) {
                    for (Map.Entry<String, List<SerializableCookie>> entry : serializableStore.entrySet()) {
                        List<Cookie> cookies = new ArrayList<>();
                        for (SerializableCookie sc : entry.getValue()) {
                            cookies.add(sc.toCookie());
                        }
                        cookieStore.put(entry.getKey(), cookies);
                    }
                }
            }
        }
    }

    // Helper class for cookie serialization
    private static class SerializableCookie {
        String name;
        String value;
        long expiresAt;
        String domain;
        String path;
        boolean secure;
        boolean httpOnly;
        boolean hostOnly;

        public SerializableCookie(Cookie cookie) {
            this.name = cookie.name();
            this.value = cookie.value();
            this.expiresAt = cookie.expiresAt();
            this.domain = cookie.domain();
            this.path = cookie.path();
            this.secure = cookie.secure();
            this.httpOnly = cookie.httpOnly();
            this.hostOnly = cookie.hostOnly();
        }

        public Cookie toCookie() {
            Cookie.Builder builder = new Cookie.Builder()
                    .name(name)
                    .value(value)
                    .expiresAt(expiresAt)
                    .path(path);

            if (secure)
                builder.secure();
            if (httpOnly)
                builder.httpOnly();
            if (hostOnly)
                builder.hostOnlyDomain(domain);
            else
                builder.domain(domain);

            return builder.build();
        }
    }
}
