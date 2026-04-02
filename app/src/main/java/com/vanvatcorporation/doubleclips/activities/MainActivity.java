package com.vanvatcorporation.doubleclips.activities;

import static com.vanvatcorporation.doubleclips.FFmpegEdit.runAnyCommand;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.vanvatcorporation.doubleclips.AdsHandler;
import com.vanvatcorporation.doubleclips.BuildConfig;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.UncaughtExceptionHandler;
import com.vanvatcorporation.doubleclips.activities.main.MainAreaScreen;
import com.vanvatcorporation.doubleclips.activities.main.ProfileAreaScreen;
import com.vanvatcorporation.doubleclips.activities.main.TemplateAreaScreen;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.dynamiclibs.auth.AuthRepository;
import com.vanvatcorporation.doubleclips.dynamiclibs.auth.User;
import com.vanvatcorporation.doubleclips.helper.NotificationHelper;
import com.vanvatcorporation.doubleclips.helper.ProgressCompressionHelper;
import com.vanvatcorporation.doubleclips.impl.AppCompatActivityImpl;
import com.vanvatcorporation.doubleclips.impl.ViewPagerImpl;
import com.vanvatcorporation.doubleclips.impl.java.RunnableImpl2;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;
import com.vanvatcorporation.doubleclips.popups.CompressionPopup;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

public class MainActivity extends AppCompatActivityImpl {


    ViewPagerImpl viewPager;


    MainAreaScreen homeAreaScreen;
    TemplateAreaScreen templateAreaScreen;
    ProfileAreaScreen profileAreaScreen;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.layout_main);

        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler(Thread.getDefaultUncaughtExceptionHandler(), this));


        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Check if already logged in
        AuthRepository.getInstance(this).checkSession(new AuthRepository.AuthCallback<User>() {
            @Override
            public void onSuccess(User user) {
                // Navigate to Main Activity...
                LoggingManager.LogToToast(MainActivity.this, "Logged in as " + user.getUsername());

                profileAreaScreen.reloadingPage();
            }

            @Override
            public void onError(String message) {
                // Not logged in, stay on login screen
                Log.d("Auth", "Session check: " + message);
            }
        });
        // Notification Stuff
        NotificationHelper.createNotificationChannel(this);


        // Initialize Mobile Ads SDK
        AdsHandler.initializeAds(this, this);

        if(prefs.getBoolean("early_access_issues_notification", true))
            loadCurrentIssuesAndShow();


//        pickButton = findViewById(R.id.button);
//        pickButton.setOnClickListener(v -> pickingContent());
//
//        trimButton = findViewById(R.id.button2);
//        trimButton.setOnClickListener(v -> FFmpegMethod.trimVideo(this, pathText.getText().toString(), outputText.getText().toString(), 0, 5));
//
//        pathText = findViewById(R.id.pathText);
//        outputText = findViewById(R.id.outputText);
//
//        errorText = findViewById(R.id.textView);


        // Check and request permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
        }


        // Start initializing critical modules ----------------------------------------------------------


        // Initialize BottomNavigationView
        com.google.android.material.bottomnavigation.BottomNavigationView bottomNavigationView = findViewById(R.id.bottomNavigation);
        bottomNavigationView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.message_homepage) {
                viewPager.setCurrentItem(0, true);
                return true;
            } else if (itemId == R.id.message_template) {
                viewPager.setCurrentItem(1, true);
                return true;
            } else if (itemId == R.id.message_search) {
                viewPager.setCurrentItem(2, true);
                return true;
            } else if (itemId == R.id.message_storage) {
                viewPager.setCurrentItem(3, true);
                return true;
            } else if (itemId == R.id.message_profile) {
                viewPager.setCurrentItem(4, true);
                return true;
            }
            return false;
        });


        homeAreaScreen = (MainAreaScreen) getLayoutInflater().inflate(R.layout.pager_main_homepage, null);
        templateAreaScreen = (TemplateAreaScreen) getLayoutInflater().inflate(R.layout.pager_main_template, null);
        View View3 = getLayoutInflater().inflate(R.layout.pager_main_search, null);
        View View4 = getLayoutInflater().inflate(R.layout.pager_main_storage, null);
        profileAreaScreen = (ProfileAreaScreen) getLayoutInflater().inflate(R.layout.pager_main_profile, null);


        viewPager = findViewById(R.id.mainViewPager);
        viewPager.insertView(homeAreaScreen, templateAreaScreen, View3, View4, profileAreaScreen);
        viewPager.setupActions(
                new RunnableImpl2() {
                    @Override
                    public <T, T2> void runWithParam(T param, T2 param2) {
                        int position = (int) param2;

                        int menuItemId = -1;
                        switch (position) {
                            case 0:
                                menuItemId = R.id.message_homepage;
                                break;
                            case 1:
                                menuItemId = R.id.message_template;
                                break;
                            case 2:
                                menuItemId = R.id.message_search;
                                break;
                            case 3:
                                menuItemId = R.id.message_storage;
                                break;
                            case 4:
                                menuItemId = R.id.message_profile;
                                break;
                        }

                        if (menuItemId != -1) {
                            bottomNavigationView.getMenu().findItem(menuItemId).setChecked(true);
                        }
                    }
                }
        );

        // ---------------------------------------------------------- End initializing critical modules


        homeAreaScreen.reloadingProject();


        homeAreaScreen.filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();


                        CompressionPopup dialog = new CompressionPopup(this, getString(R.string.alert_processing_decompression), "Extracting project...");
                        ExecutorService executor = Executors.newSingleThreadExecutor();
                        executor.execute(() -> {
                            ProgressCompressionHelper.unzipFolder(this, getContentResolver(), uri, Constants.DEFAULT_PROJECT_DIRECTORY(this),
                                    new ProgressCompressionHelper.UnzipProgressListener() {

                                        @Override
                                        public void onProgress(long bytesExtracted, long totalBytes, String name) {
                                            int percent = (int) ((bytesExtracted * 100) / totalBytes);

                                            dialog.previewProgressBar.post(() -> {
                                                dialog.previewProgressBar.setMax(100);
                                                dialog.previewProgressBar.setProgress(percent);
                                                dialog.processingPercent.setText(percent + "%");
                                                dialog.descriptionText.setText("Extracting project... " + name);
                                            });
                                        }

                                        @Override
                                        public void onCompleted() {
                                            dialog.dialog.dismiss();

                                            MainActivity.this.runOnUiThread(() -> {

                                                // TODO: Find a way to get the directory of the exact extracted path.
//                                            File directory = new File(IOHelper.CombinePath(Constants.DEFAULT_PROJECT_DIRECTORY(MainActivity.this), EditingActivity.getFileName(getContentResolver(), uri)));
//                                            if(directory.isDirectory())
//                                            {
//                                                MainAreaScreen.ProjectData data = MainAreaScreen.ProjectData.loadProperties(MainActivity.this, directory.getAbsolutePath());
//
//                                                if(data != null)
//                                                {
//                                                    homeAreaScreen.projectList.add(data);
//                                                    homeAreaScreen.projectAdapter.notifyItemInserted(homeAreaScreen.projectList.size() - 1);
//                                                }
//                                            }
                                                homeAreaScreen.reloadingProject();
                                            });
                                        }

                                        @Override
                                        public void onError(Exception e) {

                                        }
                                    });
                        });

                    }
                }
        );

        homeAreaScreen.fileCreatorLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();

                        CompressionPopup dialog = new CompressionPopup(this, getString(R.string.alert_processing_compression), "Compressing project...");
                        ExecutorService executor = Executors.newSingleThreadExecutor();
                        executor.execute(() -> {
                            if (homeAreaScreen.currentExportingProject == null) return;
                            ProgressCompressionHelper.zipFolder(this, homeAreaScreen.currentExportingProject.getProjectPath(), getContentResolver(), uri, new ProgressCompressionHelper.ZipProgressListener() {
                                @Override
                                public void onProgress(long bytesWritten, long totalBytes, String name) {
                                    int percent = (int) ((bytesWritten * 100) / totalBytes);

                                    dialog.previewProgressBar.post(() -> {
                                        dialog.previewProgressBar.setMax(100);
                                        dialog.previewProgressBar.setProgress(percent);
                                        dialog.processingPercent.setText(percent + "%");
                                        dialog.descriptionText.setText("Compressing project... " + name);
                                    });
                                }

                                @Override
                                public void onCompleted() {
                                    dialog.dialog.dismiss();
                                }

                                @Override
                                public void onError(Exception e) {

                                }
                            });
                            homeAreaScreen.currentExportingProject = null;
                        });

                    }
                }
        );
    }


    @Override
    protected void onResume() {
        super.onResume();
        AdsHandler.displayThanksForShowingAds(this);
//        AdsHandler.loadBothAds(this, this);
    }


    void loadCurrentIssuesAndShow() {
        // Show the custom dialog immediately (like iOS .onAppear), with a spinner.
        // Then fetch issues in the background and update the UI.
        runOnUiThread(() -> {
            android.app.Dialog dialog = new android.app.Dialog(this, com.google.android.material.R.style.ThemeOverlay_Material3_BottomSheetDialog);
            View dialogView = LayoutInflater.from(this).inflate(R.layout.popup_early_access, null);
            dialog.setContentView(dialogView);

            // Make it a bottom-sheet style (full width, rounded top corners)
            if (dialog.getWindow() != null) {
                dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dialog.getWindow().setGravity(android.view.Gravity.BOTTOM);
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }

            android.widget.LinearLayout issuesLoadingView = dialogView.findViewById(R.id.issuesLoadingView);
            android.widget.TextView issuesText = dialogView.findViewById(R.id.issuesText);

            // Wire up buttons
            dialogView.findViewById(R.id.earlyAccessOkButton).setOnClickListener(v -> dialog.dismiss());
            dialogView.findViewById(R.id.earlyAccessDontShowButton).setOnClickListener(v -> {
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                        .putBoolean("early_access_issues_notification", false).apply();
                dialog.dismiss();
            });

            dialog.show();

            // Fetch issues in background, then update the issues box
            Executors.newSingleThreadExecutor().execute(() -> {
                StringBuilder issueBuilder = new StringBuilder();
                try {
                    URL url = new URL("https://app.vanvatcorp.com/doubleclips/api/fetch-issues");
                    HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");

                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    String[] issues = new Gson().newBuilder().create().fromJson(response.toString(), String[].class);
                    if (issues == null || issues.length == 0) {
                        issueBuilder.append("None");
                    } else {
                        for (String issue : issues) {
                            issueBuilder.append("• ").append(issue).append("\n");
                        }
                    }
                } catch (Exception e) {
                    issueBuilder.append("Failed to get issues from server.");
                }

                final String result = issueBuilder.toString().trim();
                runOnUiThread(() -> {
                    issuesLoadingView.setVisibility(View.GONE);
                    issuesText.setText(result);
                    issuesText.setVisibility(View.VISIBLE);
                });
            });
        });
    }




    public static String getPath(Context context, Uri uri) {
        String[] projection = {MediaStore.Video.Media.DATA};
        Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null);
        if (cursor != null) {
            int column_index = cursor.getColumnIndexOrThrow(projection[0]);
            cursor.moveToFirst();
            String path = cursor.getString(column_index);
            cursor.close();
            return path;
        }
        return uri.getPath();
    }


    public static class MigrationHelper {
        public static void migrate(MainAreaScreen.ProjectData data) {
            // List some critical changes need to be apply e.g a null field that wasn't exist until
            // the newer version come out, and loading it in the new version crashes the app
            if (data.version == null)
                data.version = BuildConfig.VERSION_NAME;
            switch (data.version) {
                case "1.0.0":
                    // Migrate for the lower project
                    // For example newer version may create the folder
                    // but older version don't, therefore missing folder leading to ffmpeg failure
                    // IOHelper.writeToFileAsRaw(MainActivity.this, "/file-or-folder/to/create/for/migration");
                    break;
            }
        }
    }

}
