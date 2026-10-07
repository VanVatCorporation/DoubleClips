package com.vanvatcorporation.doubleclips.popups;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.vanvatcorporation.doubleclips.R;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The progress dialog for exporting / importing a project ZIP.
 * <p>
 * {@link #update} may be called from any thread as often as it likes: the dialog keeps only the latest value and
 * posts at most one UI update at a time, so a fast worker can't flood the main thread (the old per-chunk posts made
 * the bar lag behind the real work). The screen stays on while it is showing, and Cancel is offered through
 * {@link #setOnCancel}.
 */
public class CompressionPopup extends AlertDialog.Builder {
    public ProgressBar previewProgressBar;
    public TextView titleText, descriptionText;
    public TextView processingPercent;
    public Button cancelButton;

    public AlertDialog dialog;

    private final String verb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean updatePending = new AtomicBoolean(false);
    private volatile double fraction;
    private volatile String name = "";

    public CompressionPopup(Context context, String title, String description) {
        super(context);
        this.verb = description;

        // Inflate your custom layout
        LayoutInflater inflater = LayoutInflater.from(context);
        View dialogView = inflater.inflate(R.layout.popup_processing_project, null);
        setView(dialogView);
        setCancelable(false);

        titleText = dialogView.findViewById(R.id.title);
        descriptionText = dialogView.findViewById(R.id.processingDescription);
        previewProgressBar = dialogView.findViewById(R.id.previewProgressBar);
        processingPercent = dialogView.findViewById(R.id.processingPercent);
        cancelButton = dialogView.findViewById(R.id.cancelButton);

        dialog = create();
        // A long export or import must not be interrupted by the screen turning off.
        if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dialog.show();

        previewProgressBar.setMax(1000); // finer than whole percents, so the bar moves smoothly on big projects
        titleText.setText(title);
        descriptionText.setText(description);
    }

    /** Shows a Cancel button; {@code onCancel} runs on the UI thread when it is pressed. */
    public void setOnCancel(Runnable onCancel) {
        cancelButton.setVisibility(View.VISIBLE);
        cancelButton.setOnClickListener(v -> {
            cancelButton.setEnabled(false);
            cancelButton.setText("Cancelling...");
            onCancel.run();
        });
    }

    /**
     * Any thread. {@code fraction} is 0..1, or negative when the total isn't known (shows an indeterminate bar).
     * {@code name} is a stage ("Finishing...") or the file being processed.
     */
    public void update(double fraction, String name) {
        this.fraction = fraction;
        this.name = name == null ? "" : name;
        if (updatePending.compareAndSet(false, true)) ui.post(this::apply);
    }

    private void apply() {
        updatePending.set(false);
        double f = fraction;
        if (f < 0) {
            previewProgressBar.setIndeterminate(true);
            processingPercent.setText("");
        } else {
            previewProgressBar.setIndeterminate(false);
            previewProgressBar.setProgress((int) Math.round(Math.min(1.0, f) * 1000));
            processingPercent.setText(Math.round(Math.min(1.0, f) * 100) + "%");
        }
        String n = name;
        descriptionText.setText(n.endsWith("...") || n.isEmpty() ? (n.isEmpty() ? verb : n) : verb + " " + n);
    }

    /** UI thread. Closes the dialog; fine to call when it is already gone (e.g. the activity was destroyed). */
    public void dismissSafely() {
        try {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        } catch (IllegalArgumentException ignored) {
            // the window is no longer attached
        }
    }
}
