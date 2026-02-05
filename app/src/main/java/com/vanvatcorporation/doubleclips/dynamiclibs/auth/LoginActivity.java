package com.vanvatcorporation.doubleclips.dynamiclibs.auth;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.vanvatcorporation.doubleclips.R;

public class LoginActivity extends AppCompatActivity {

    private AuthRepository authRepository;
    private EditText emailInput;
    private EditText passwordInput;
    private Button loginButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_login); // Assume you have this layout

        authRepository = AuthRepository.getInstance(this);

        emailInput = findViewById(R.id.editTextTextEmailAddress);
        passwordInput = findViewById(R.id.editTextTextPassword);
        loginButton = findViewById(R.id.loginButton);

        // Check if already logged in
        authRepository.checkSession(new AuthRepository.AuthCallback<User>() {
            @Override
            public void onSuccess(User user) {
                Toast.makeText(LoginActivity.this, "Welcome back, " + user.getUsername(), Toast.LENGTH_LONG).show();
                // Navigate to Main Activity...
                finish();
            }

            @Override
            public void onError(String message) {
                // Not logged in, stay on login screen
                Log.d("Auth", "Session check: " + message);
            }
        });

        loginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String email = emailInput.getText().toString();
                String password = passwordInput.getText().toString();

                authRepository.login(email, password, new AuthRepository.AuthCallback<User>() {
                    @Override
                    public void onSuccess(User user) {
                        Toast.makeText(LoginActivity.this, "Login Success!", Toast.LENGTH_SHORT).show();
                        // Navigate to Main Activity...
                        finish();
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(LoginActivity.this, "Error: " + message, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }
}
