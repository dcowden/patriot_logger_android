package com.patriotlogger.logger.ui;

import android.os.Bundle;
import android.os.Handler; // Not strictly needed if using LiveData for load and Repo callbacks for save
import android.os.Looper;  // Not strictly needed
import android.text.TextUtils;
import android.util.Log; // For logging
import android.widget.Button;
import android.widget.Toast;
// Import View for ProgressBar if you add it
// import android.view.View;
// import android.widget.ProgressBar;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Observer; // To observe LiveData

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.patriotlogger.logger.R;
import com.patriotlogger.logger.data.Repository; // Import Repository
import com.patriotlogger.logger.data.RepositoryVoidCallback; // Import callback
import com.patriotlogger.logger.data.Setting;   // Import Setting

// ExecutorService is not needed in Activity if Repository handles its own threading for writes
// and LiveData handles its own threading for reads.
// import java.util.concurrent.ExecutorService;
// import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG_ACTIVITY = "SettingsActivity"; // For logging

    private Repository repository; // Use Repository directly
    private SwitchMaterial switchRetainSamples;
    private TextInputEditText editTextArrivedThreshold;
    private Button buttonSaveChanges;
    // private ProgressBar progressBar; // Optional: for loading state

    // No need for activity-level executorService and mainThreadHandler
    // if using LiveData for loading and Repository's async methods (with callbacks) for saving.

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        repository = Repository.get(getApplicationContext());

        switchRetainSamples = findViewById(R.id.switchRetainSamples);
        editTextArrivedThreshold = findViewById(R.id.editTextArrivedThreshold);
        buttonSaveChanges = findViewById(R.id.buttonSaveChanges);
        // progressBar = findViewById(R.id.progressBarSettings); // If you add a ProgressBar

        buttonSaveChanges.setOnClickListener(v -> saveSettings());

        // Load initial settings and observe for changes
        loadAndObserveSettings();
    }

    // onResume is a good place to ensure data is fresh, but LiveData will handle updates.
    // If you only load once in onCreate, LiveData observer still keeps it up-to-date.
    // No need for loadSettingsToUi() to be called from onResume explicitly if using LiveData.

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // If you had any LiveData observers with observeForever, unregister them here.
        // But since we use observe(this, ...), it's lifecycle-aware.
    }

    private void loadAndObserveSettings() {
        // Show loading indicator
        // if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        // Disable save button while loading
        buttonSaveChanges.setEnabled(false);


        // Observe LiveData for settings from the Repository
        repository.getLiveConfig().observe(this, new Observer<Setting>() {
            @Override
            public void onChanged(@Nullable Setting setting) {
                // if (progressBar != null) progressBar.setVisibility(View.GONE);
                buttonSaveChanges.setEnabled(true);

                if (setting != null) {
                    Log.d(TAG_ACTIVITY, "Settings loaded/updated: Retain=" + setting.retain_samples + ", Threshold=" + setting.arrived_threshold);
                    if (!isFinishing() && !isDestroyed()) { // Ensure Activity is valid
                        switchRetainSamples.setChecked(setting.retain_samples);
                        editTextArrivedThreshold.setText(String.valueOf(setting.arrived_threshold));
                    }
                } else {
                    // This case should be handled by Repository's initialization logic.
                    // If it still happens, it means settings are not in DB and defaults didn't initialize.
                    Log.e(TAG_ACTIVITY, "Received null settings from Repository. Check Repository initialization.");
                    Toast.makeText(SettingsActivity.this, "Error loading settings.", Toast.LENGTH_SHORT).show();
                    // Potentially set UI to default values or show error state
                    switchRetainSamples.setChecked(Setting.DEFAULT_RETAIN_SAMPLES); // Fallback to compiled defaults
                    editTextArrivedThreshold.setText(String.valueOf(Setting.DEFAULT_ARRIVED_THRESHOLD));
                }
            }
        });
    }

    private void saveSettings() {
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

        // Optional validation (example)
        if (arrivedThreshold > 0 || arrivedThreshold < -100) {
            editTextArrivedThreshold.setError("Threshold typically ranges from -100 to 0.");
            Toast.makeText(this, "Threshold usually from -100 to 0.", Toast.LENGTH_SHORT).show();
            return;
        }


        // Disable button to prevent multiple clicks while saving
        buttonSaveChanges.setEnabled(false);
        // if (progressBar != null) progressBar.setVisibility(View.VISIBLE); // Show saving progress

        // We can update settings individually or by creating a new Setting object.
        // Using individual setters for this example. The Repository methods are already async.

        // Counter to track completion of async operations if updating individually
        // Or, more simply, update the whole object if that API is preferred.
        // Let's use the upsertConfig method for simplicity which takes the whole object.

        // First, get the current settings to preserve any other settings if they exist
        // and only update the relevant ones.
        // repository.getConfigOnce() is async and returns LiveData. This is a bit complex for a simple save.
        // Better: The Repository's setRetainSamples and setArrivedThreshold methods already handle
        // fetching the current config, updating it, and saving.

        // We'll call setRetainSamples and setArrivedThreshold. Since they are independent,
        // we might get two "saved" messages or need to coordinate.
        // A simpler approach: create a Setting object and upsert it.

        Setting newSetting = new Setting(); // Will be initialized with defaults from Setting class
        newSetting.id = Setting.SETTINGS_ID; // Ensure ID is correct
        newSetting.retain_samples = retainSamples;
        newSetting.arrived_threshold = arrivedThreshold;
        // If there were other settings, you'd fetch the current Setting object first, modify, then upsert.
        // But since Setting entity only has these two + id, we can construct directly.

        repository.upsertConfig(newSetting, new RepositoryVoidCallback() {
            @Override
            public void onSuccess() {
                // Re-enable button, hide progress
                buttonSaveChanges.setEnabled(true);
                // if (progressBar != null) progressBar.setVisibility(View.GONE);

                if (!isFinishing() && !isDestroyed()) {
                    Toast.makeText(SettingsActivity.this, "Settings saved!", Toast.LENGTH_SHORT).show();
                    Log.i(TAG_ACTIVITY, "Settings saved successfully.");
                    // The LiveData observer in loadAndObserveSettings() will automatically
                    // reflect the change if the upsert was successful and the underlying data changed.
                    // finish(); // Optionally close
                }
            }

            @Override
            public void onError(Exception e) {
                buttonSaveChanges.setEnabled(true);
                // if (progressBar != null) progressBar.setVisibility(View.GONE);
                Log.e(TAG_ACTIVITY, "Error saving settings", e);
                if (!isFinishing() && !isDestroyed()) {
                    Toast.makeText(SettingsActivity.this, "Error saving settings.", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
}
