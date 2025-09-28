package com.patriotlogger.logger.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.patriotlogger.logger.R;
import com.patriotlogger.logger.utils.SettingsManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {

    private SettingsManager settingsManager;
    private SwitchMaterial switchRetainSamples;
    private TextInputEditText editTextArrivedThreshold;
    private Button buttonSaveChanges;

    // For background tasks
    private ExecutorService executorService;
    private Handler mainThreadHandler;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        settingsManager = SettingsManager.getInstance(getApplicationContext());

        // Initialize ExecutorService and Handler
        executorService = Executors.newSingleThreadExecutor();
        mainThreadHandler = new Handler(Looper.getMainLooper());

        switchRetainSamples = findViewById(R.id.switchRetainSamples);
        editTextArrivedThreshold = findViewById(R.id.editTextArrivedThreshold);
        buttonSaveChanges = findViewById(R.id.buttonSaveChanges);

        buttonSaveChanges.setOnClickListener(v -> saveSettings());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettingsToUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Shutdown executor service to prevent leaks
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }

    private void loadSettingsToUi() {
        if (settingsManager == null) {
            // This should ideally not happen if settingsManager is initialized in onCreate
            // and this method is called after. Consider logging an error.
            return;
        }

        // Show some loading indicator if needed (e.g., ProgressBar)
        // progressBar.setVisibility(View.VISIBLE);

        executorService.execute(() -> {
            // Background work: Load settings
            // These calls are assumed to be the ones accessing the database
            boolean retainSamples = settingsManager.shouldRetainSamples();
            int arrivedThreshold = settingsManager.getArrivedThreshold();

            // UI Update: Post back to main thread
            mainThreadHandler.post(() -> {
                // progressBar.setVisibility(View.GONE); // Hide loading indicator
                if (!isFinishing() && !isDestroyed()) { // Ensure Activity is still valid
                    switchRetainSamples.setChecked(retainSamples);
                    editTextArrivedThreshold.setText(String.valueOf(arrivedThreshold));
                }
            });
        });
    }

    private void saveSettings() {
        if (settingsManager == null) return;

        boolean retainSamples = switchRetainSamples.isChecked();
        String thresholdStr = editTextArrivedThreshold.getText() != null ?
                editTextArrivedThreshold.getText().toString() : "";

        if (TextUtils.isEmpty(thresholdStr)) {
            editTextArrivedThreshold.setError("Threshold cannot be empty.");
            Toast.makeText(this, "Arrived threshold is required.", Toast.LENGTH_SHORT).show();
            return;
        }

        int arrivedThreshold;
        try {
            arrivedThreshold = Integer.parseInt(thresholdStr);
        } catch (NumberFormatException e) {
            editTextArrivedThreshold.setError("Invalid number format.");
            Toast.makeText(this, "Invalid threshold value.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Optional: Add more validation for the threshold range if needed
        // if (arrivedThreshold > 0) {
        //     editTextArrivedThreshold.setError("Threshold must be a negative RSSI value.");
        //     Toast.makeText(this, "Threshold must be negative.", Toast.LENGTH_SHORT).show();
        //     return;
        // }

        // Consider moving settingsManager.set... calls to a background thread too
        // if they perform disk I/O, though SharedPreferences writes are often
        // batched and might be acceptable on main thread for simplicity unless they cause noticeable jank.
        // For consistency and best practice, especially if SettingsManager is using Room or a DB:
        executorService.execute(() -> {
            settingsManager.setRetainSamples(retainSamples);
            settingsManager.setArrivedThreshold(arrivedThreshold);

            mainThreadHandler.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    Toast.makeText(SettingsActivity.this, "Settings saved!", Toast.LENGTH_SHORT).show();
                    // finish(); // Optionally close the activity after saving
                }
            });
        });
    }
}
