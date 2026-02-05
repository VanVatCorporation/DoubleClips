package com.vanvatcorporation.doubleclips.dynamiclibs.auth;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;

public interface ApiService {
    @POST("/api/login")
    Call<ApiResponse<User>> login(@Body LoginRequest request);

    // Placeholder for Google Login - Backend implementation required
    @POST("/api/login/google")
    Call<ApiResponse<User>> loginWithGoogle(@Body GoogleLoginRequest request);

    @POST("/api/register")
    Call<ApiResponse<User>> register(@Body RegisterRequest request);

    @GET("/api/profile")
    Call<ApiResponse<User>> getProfile();

    @POST("/api/logout")
    Call<ApiResponse<Void>> logout();
}
