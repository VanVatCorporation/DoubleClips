# Android Authentication Integration Guide

## 1. Add Dependencies
Add the following to your `app/build.gradle` file:
```gradle
dependencies {
    // Retrofit & OkHttp
    implementation 'com.squareup.retrofit2:retrofit:2.9.0'
    implementation 'com.squareup.retrofit2:converter-gson:2.9.0'
    implementation 'com.squareup.okhttp3:okhttp:4.9.0'
    implementation 'com.squareup.okhttp3:logging-interceptor:4.9.0'
    
    // Cookie Management
    implementation 'com.squareup.okhttp3:okhttp-urlconnection:4.9.0'
    
    // Google Sign-In
    implementation 'com.google.android.gms:play-services-auth:20.7.0'

    // Gson
    implementation 'com.google.code.gson:gson:2.8.9'
}
```

## 2. Copy Files
Copy the Java files from this folder into your Android project's source path (e.g., `app/src/main/java/com/vanvatcorporation/doubleclips/dynamiclibs/auth/`).
- Ensure the package names match your project structure.

## 3. Usage
Use the `AuthRepository` singleton to perform authentication operations.

### Login
```java
AuthRepository.getInstance(context).login("email@example.com", "password", new AuthRepository.AuthCallback<User>() { ... });
```

### Google Login
```java
// Get ID Token from Google Sign-In SDK first
String idToken = "..."; 
AuthRepository.getInstance(context).loginWithGoogle(idToken, new AuthRepository.AuthCallback<User>() { ... });
```
> **Note**: The backend endpoint `/api/login/google` needs to be implemented.

### Check Session (Auto Login)
Call this when your app starts (e.g., in `SplashActivity`) to see if the user is already logged in via the persistent cookie.
```java
AuthRepository.getInstance(context).checkSession(new AuthRepository.AuthCallback<User>() { ... });
```
