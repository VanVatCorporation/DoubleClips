package com.vanvatcorporation.doubleclips.activities;

import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ViewFlipper;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.dynamiclibs.auth.AuthRepository;
import com.vanvatcorporation.doubleclips.impl.AppCompatActivityImpl;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import android.view.View;
import android.widget.TextView;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;

public class PostTemplateActivity extends AppCompatActivityImpl {

    private ViewFlipper viewFlipper;
    private ExoPlayer player;
    private PlayerView playerView;
    private ImageView thumbnailImageView;
    private EditText titleEditText;
    private EditText descriptionEditText;

    private ImageView uploadPreviewImage;
    private View uploadProgressFill;
    private FrameLayout progressContainer;
    private TextView uploadStatusText;
    private LinearLayout uploadFailedActionContainer;
    private TextView successTemplateIdText;
    private Button btnUploadDone;

    private String ffmpegCommand;
    private int totalClip;
    private ArrayList<String> videoFilePaths;
    private ArrayList<String> previewFilePaths;
    private String defaultTitle;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_post_template);

        // Retrieve Intent Data
        ffmpegCommand = getIntent().getStringExtra("ffmpegCommand");
        totalClip = getIntent().getIntExtra("totalClip", 0);
        videoFilePaths = getIntent().getStringArrayListExtra("videoFilePaths");
        previewFilePaths = getIntent().getStringArrayListExtra("previewFilePaths");
        defaultTitle = getIntent().getStringExtra("defaultTitle");

        initViews();
        setupPreview();
        setupDetails();
    }

    private void initViews() {
        viewFlipper = findViewById(R.id.viewFlipper);
        playerView = findViewById(R.id.playerView);

        ImageButton previewBackButton = findViewById(R.id.previewBackButton);
        Button btnNext = findViewById(R.id.btnNext);

        ImageButton detailsBackButton = findViewById(R.id.detailsBackButton);
        thumbnailImageView = findViewById(R.id.thumbnailImageView);
        titleEditText = findViewById(R.id.titleEditText);
        descriptionEditText = findViewById(R.id.descriptionEditText);
        Button btnUpload = findViewById(R.id.btnUpload);
        Button btnSaveDraft = findViewById(R.id.btnSaveDraft);

        previewBackButton.setOnClickListener(v -> finish());
        btnNext.setOnClickListener(v -> {
            // Move to Details View
            player.pause();
            viewFlipper.setInAnimation(this, R.anim.slide_in_right);
            viewFlipper.setOutAnimation(this, R.anim.slide_out_left);
            viewFlipper.showNext();
        });

        detailsBackButton.setOnClickListener(v -> {
            // Move back to Preview View
            viewFlipper.setInAnimation(this, R.anim.slide_in_left);
            viewFlipper.setOutAnimation(this, R.anim.slide_out_right);
            viewFlipper.showPrevious();
            player.play();
        });

        progressContainer = findViewById(R.id.progressContainer);
        uploadProgressFill = findViewById(R.id.uploadProgressFill);
        uploadPreviewImage = findViewById(R.id.uploadPreviewImage);
        uploadStatusText = findViewById(R.id.uploadStatusText);
        uploadFailedActionContainer = findViewById(R.id.uploadFailedActionContainer);
        successTemplateIdText = findViewById(R.id.successTemplateIdText);
        btnUploadDone = findViewById(R.id.btnUploadDone);

        Button btnUploadBack = findViewById(R.id.btnUploadBack);
        Button btnUploadRetry = findViewById(R.id.btnUploadRetry);

        btnUpload.setOnClickListener(v -> {

            titleEditText.clearFocus();
            descriptionEditText.clearFocus();

            uploadTemplate();
        });
        btnSaveDraft.setOnClickListener(v -> finish());
        btnUploadDone.setOnClickListener(v -> finish());

        btnUploadBack.setOnClickListener(v -> {
            uploadProgressFill.getLayoutParams().width = 0;
            uploadProgressFill.requestLayout();
            viewFlipper.setInAnimation(this, R.anim.slide_in_left);
            viewFlipper.setOutAnimation(this, R.anim.slide_out_right);
            viewFlipper.showPrevious(); // Move back to Detail View
        });

        btnUploadRetry.setOnClickListener(v -> {
            // Reset Progress UI
            uploadProgressFill.getLayoutParams().width = 0;
            uploadProgressFill.requestLayout();
            uploadTemplate();
        });
    }

    private void setupPreview() {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.setRepeatMode(ExoPlayer.REPEAT_MODE_ALL);

        if (previewFilePaths != null && !previewFilePaths.isEmpty()) {
            for (String path : previewFilePaths) {
                if (path.endsWith(".mp4")) {
                    MediaItem mediaItem = MediaItem.fromUri(Uri.fromFile(new File(path)));
                    player.setMediaItem(mediaItem);
                    player.prepare();
                    player.play();
                    break;
                }
            }
        }
    }

    private void setupDetails() {
        if (defaultTitle != null) {
            // Display the placeholder first.
            // titleEditText.setText(defaultTitle);
            // descriptionEditText.setText(defaultTitle); // Use as default description as
            // well
        }

        if (previewFilePaths != null && !previewFilePaths.isEmpty()) {
            for (String path : previewFilePaths) {
                if (path.endsWith(".png")) {
                    Uri imgUri = Uri.fromFile(new File(path));
                    thumbnailImageView.setImageURI(imgUri);
                    uploadPreviewImage.setImageURI(imgUri);
                    break;
                }
            }
        }
    }

    private void uploadTemplate() {
        if (AuthRepository.getInstance(this).getCurrentUser() == null) {
            new AlertDialog.Builder(this).setTitle("Login required").setMessage("Please login to post template").create().show();

            return;
        }

        List<File> videoFiles = new ArrayList<>();
        if (videoFilePaths != null) {
            for (String path : videoFilePaths) {
                videoFiles.add(new File(path));
            }
        }

        List<File> previewFiles = new ArrayList<>();
        if (previewFilePaths != null) {
            for (String path : previewFilePaths) {
                previewFiles.add(new File(path));
            }
        }

        String templateTitle = titleEditText.getText().toString();
        String templateDesc = descriptionEditText.getText().toString();

        if (templateTitle.isEmpty()) {
            templateTitle = defaultTitle;
        }
        if (templateDesc.isEmpty()) {
            templateDesc = defaultTitle;
        }

        Map<String, String> field = new HashMap<>();
        field.put("accountUsername", AuthRepository.getInstance(this).getCurrentUser().getUsername());
        field.put("accountPassword", "********");
        field.put("templateTitle", templateTitle);
        field.put("templateDescription", templateDesc);
        field.put("ffmpegCommand", ffmpegCommand);
        field.put("templateTotalClips", String.valueOf(totalClip));

        // Only advance if we are coming from the Details screen (Index 1)
        if (viewFlipper.getDisplayedChild() == 1) {
            viewFlipper.setInAnimation(this, R.anim.slide_in_right);
            viewFlipper.setOutAnimation(this, R.anim.slide_out_left);
            viewFlipper.showNext();
        }
        
        uploadStatusText.setText("Uploading template...");
        uploadFailedActionContainer.setVisibility(View.GONE);
        uploadProgressFill.setBackgroundColor(getColor(R.color.ios_blue));

        ExportActivity.uploadTemplateNecessityItems(
                this,
                "https://app.vanvatcorp.com/doubleclips/api/post-template",
                field,
                videoFiles,
                previewFiles,
                new ExportActivity.UploadCallback() {
                    @Override
                    public void onProgress(int progress) {
                        runOnUiThread(() -> {
                            int maxWidth = progressContainer.getWidth();
                            uploadProgressFill.getLayoutParams().width = (int) (maxWidth * (progress / 100f));
                            uploadProgressFill.requestLayout();

                            uploadStatusText.setText("Uploading... " + progress + "%");
                        });
                    }

                    @Override
                    public void onResult(boolean success, String result) {
                        runOnUiThread(() -> {
                            if (success) {
                                try {
                                    JSONObject json = new JSONObject(result);
                                    String templateId = json.optString("templateId", "");

                                    viewFlipper.showNext(); // Move to Step 4
                                    successTemplateIdText.setText("Template ID: " + templateId);
                                } catch (Exception e) {
                                    uploadStatusText.setText("Uploaded successfully, but couldn't parse response.");
                                }
                            } else {
                                uploadStatusText.setText("Failed to upload: " + result);
                                uploadFailedActionContainer.setVisibility(View.VISIBLE);
                                uploadProgressFill.setBackgroundColor(getColor(R.color.red));
                            }
                        });
                    }
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
