package com.vanvatcorporation.doubleclips.dynamiclibs.auth;

public class ApiResponse<T> {
    private boolean success;
    private String message;
    private String error;
    private T user; // The field name in your API is often "user", but could be generic "data"

    // Getters
    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public String getError() {
        return error;
    }

    public T getUser() {
        return user;
    }
}
