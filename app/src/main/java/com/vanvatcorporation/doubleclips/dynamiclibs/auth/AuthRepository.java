package com.vanvatcorporation.doubleclips.dynamiclibs.auth;

import android.content.Context;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class AuthRepository {
    private static AuthRepository instance;
    private ApiService apiService;

    // Callback interface for UI to handle results
    public interface AuthCallback<T> {
        void onSuccess(T data);

        void onError(String message);
    }

    private AuthRepository(Context context) {
        apiService = RetrofitClient.getInstance(context).getApi();
    }

    public static synchronized AuthRepository getInstance(Context context) {
        if (instance == null) {
            instance = new AuthRepository(context);
        }
        return instance;
    }

    public void login(String email, String password, AuthCallback<User> callback) {
        LoginRequest request = new LoginRequest(email, password);
        apiService.login(request).enqueue(new Callback<ApiResponse<User>>() {
            @Override
            public void onResponse(Call<ApiResponse<User>> call, Response<ApiResponse<User>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    if (response.body().isSuccess()) {
                        callback.onSuccess(response.body().getUser());
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
                        callback.onSuccess(response.body().getUser());
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
                        callback.onSuccess(response.body().getUser());
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
                    callback.onSuccess(response.body().getUser());
                } else {
                    // 401 Unauthorized likely
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
                callback.onSuccess(null);
            }

            @Override
            public void onFailure(Call<ApiResponse<Void>> call, Throwable t) {
                callback.onError("Logout failed: " + t.getMessage());
            }
        });
    }
}
