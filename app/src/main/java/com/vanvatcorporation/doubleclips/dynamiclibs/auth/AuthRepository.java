package com.vanvatcorporation.doubleclips.dynamiclibs.auth;

import android.content.Context;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class AuthRepository {
    private static AuthRepository instance;
    private ApiService apiService;

    // Cached User Object (In-Memory)
    private User currentUser;

    // Optional: LiveData if you want to observe changes in UI (Reactive Pattern)
    private MutableLiveData<User> userLiveData = new MutableLiveData<>();

    // Callback interface for UI to handle results
    public interface AuthCallback<T> {
        void onSuccess(T data);

        void onError(String message);
    }

    private Context context;

    private AuthRepository(Context context) {
        this.context = context.getApplicationContext();
        apiService = RetrofitClient.getInstance(context).getApi();
    }

    public static synchronized AuthRepository getInstance(Context context) {
        if (instance == null) {
            instance = new AuthRepository(context);
        }
        return instance;
    }

    /**
     * Get the currently logged-in user (cached in memory).
     *
     * @return User object or null if not logged in / session expired.
     */
    public User getCurrentUser() {
        return currentUser;
    }

    /**
     * Get LiveData for the user (useful for updating UI automatically)
     */
    public LiveData<User> getUserLiveData() {
        return userLiveData;
    }

    public void login(String email, String password, AuthCallback<User> callback) {
        LoginRequest request = new LoginRequest(email, password);
        apiService.login(request).enqueue(new Callback<ApiResponse<User>>() {
            @Override
            public void onResponse(Call<ApiResponse<User>> call, Response<ApiResponse<User>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    if (response.body().isSuccess()) {
                        User user = response.body().getUser();
                        cacheUser(user); // Cache the user
                        callback.onSuccess(user);
                    } else {
                        callback.onError(response.body().getError());
                    }
                } else {
                    callback.onError("Login failed: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<User>> call, Throwable t) {
                callback.onError("Network error: " + t.getMessage());
            }
        });
    }

    public void loginWithGoogle(String idToken, AuthCallback<User> callback) {
        GoogleLoginRequest request = new GoogleLoginRequest(idToken);
        apiService.loginWithGoogle(request).enqueue(new Callback<ApiResponse<User>>() {
            @Override
            public void onResponse(Call<ApiResponse<User>> call, Response<ApiResponse<User>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    if (response.body().isSuccess()) {
                        User user = response.body().getUser();
                        cacheUser(user); // Cache the user
                        callback.onSuccess(user);
                    } else {
                        callback.onError(response.body().getError());
                    }
                } else {
                    callback.onError("Google Login failed: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<User>> call, Throwable t) {
                callback.onError("Network error: " + t.getMessage());
            }
        });
    }

    public void register(String username, String email, String password, String firstName, String lastName,
                         AuthCallback<User> callback) {
        RegisterRequest request = new RegisterRequest(username, email, password, firstName, lastName);
        apiService.register(request).enqueue(new Callback<ApiResponse<User>>() {
            @Override
            public void onResponse(Call<ApiResponse<User>> call, Response<ApiResponse<User>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    if (response.body().isSuccess()) {
                        User user = response.body().getUser();
                        cacheUser(user); // Cache the user
                        callback.onSuccess(user);
                    } else {
                        callback.onError(response.body().getError());
                    }
                } else {
                    callback.onError("Registration failed: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<User>> call, Throwable t) {
                callback.onError("Network error: " + t.getMessage());
            }
        });
    }

    // Check if user is logged in (session cookie valid) by fetching profile
    public void checkSession(AuthCallback<User> callback) {
        apiService.getProfile().enqueue(new Callback<ApiResponse<User>>() {
            @Override
            public void onResponse(Call<ApiResponse<User>> call, Response<ApiResponse<User>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().getUser() != null) {
                    User user = response.body().getUser();
                    cacheUser(user); // Cache the user on session check
                    callback.onSuccess(user);
                } else {
                    // 401 Unauthorized likely
                    clearUser(); // Clear cache if session invalid
                    callback.onError("Not logged in");
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<User>> call, Throwable t) {
                callback.onError("Network error: " + t.getMessage());
            }
        });
    }

    public void logout(AuthCallback<Void> callback) {
        apiService.logout().enqueue(new Callback<ApiResponse<Void>>() {
            @Override
            public void onResponse(Call<ApiResponse<Void>> call, Response<ApiResponse<Void>> response) {
                clearUser(); // Clear cache on logout
                clearLocalSession();
                callback.onSuccess(null);
            }

            @Override
            public void onFailure(Call<ApiResponse<Void>> call, Throwable t) {
                // Should we clear session even on network fail?
                // YES, for user experience "Logout" means "Log me out of this device".
                clearUser();
                clearLocalSession();
                callback.onSuccess(null); // Treat as success from UI perspective
            }
        });
    }

    // Helper to clear local tokens
    private void clearLocalSession() {
        RetrofitClient.getInstance(context).clearCookies();
    }

    // Helper to update cache
    private void cacheUser(User user) {
        this.currentUser = user;
        this.userLiveData.postValue(user);
    }

    // Helper to clear cache
    private void clearUser() {
        this.currentUser = null;
        this.userLiveData.postValue(null);
    }
}
