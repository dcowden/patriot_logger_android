package com.patriotlogger.logger.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
// Removed unused imports like LiveData, Observer, CountDownLatch, AtomicReference
// LiveData is used via Repository's getLiveConfig()

import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.Setting;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsManager {
    private static final String TAG = "SettingsManager";

    private static volatile SettingsManager instance;
    private final Repository repository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    private volatile Setting cachedSetting; // Make it volatile for visibility across threads

    private SettingsManager(Context context) {
        this.repository = Repository.get(context.getApplicationContext());
        // Observe LiveData to keep cache updated and initialize if needed
        // Posting to main thread if observeForever needs to be called from there
        mainThreadHandler.post(() -> {
            repository.getLiveConfig().observeForever(setting -> {
                if (setting == null) {
                    Log.i(TAG, "No settings found in DB via LiveData, ensuring defaults exist.");
                    initializeDefaultSettingsInDb();
                } else {
                    cachedSetting = setting;
                    Log.i(TAG, "SettingsManager cache updated from LiveData: retain_samples=" + setting.retain_samples +
                                 ", arrived_threshold=" + setting.arrived_threshold);
                }
            });
        });
    }

    public static SettingsManager getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (SettingsManager.class) {
                if (instance == null) {
                    instance = new SettingsManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private void initializeDefaultSettingsInDb() {
        executor.execute(() -> {
            // Double check if another thread/instance initialized it already by fetching sync
            Setting currentDbSetting = repository.getConfigSync();
            if (currentDbSetting == null) {
                Setting defaultSetting = new Setting(); // Instantiates with defaults
                repository.upsertConfig(defaultSetting);
                Log.i(TAG, "Initialized default settings in DB.");
                // The LiveData observer should pick this up and update cachedSetting.
            } else {
                Log.i(TAG, "Defaults already in DB, no re-initialization needed by initializeDefaultSettingsInDb.");
                 // If cachedSetting is still null, update it from what we just read
                if (cachedSetting == null) {
                    cachedSetting = currentDbSetting;
                }
            }
        });
    }

    private Setting getCachedSetting() {
        if (cachedSetting == null) {
            // This might happen if called very early before LiveData has emitted or after a clear.
            // Attempt a synchronous fetch as a fallback.
            Log.w(TAG, "CachedSetting is null. Attempting synchronous fetch.");
            Setting syncSetting = repository.getConfigSync();
            if (syncSetting != null) {
                cachedSetting = syncSetting;
                return cachedSetting;
            }
            Log.e(TAG, "Failed to get settings synchronously. Returning emergency defaults.");
            return new Setting(); // Emergency fallback
        }
        return cachedSetting;
    }

    // --- Getter methods ---
    public boolean shouldRetainSamples() {
        return getCachedSetting().retain_samples;
    }

    public int getArrivedThreshold() {
        return getCachedSetting().arrived_threshold;
    }

    // --- Setter methods ---
    public void setRetainSamples(boolean value) {
        executor.execute(() -> {
            Setting current = getCachedSetting();
            if (current == null) { // Should be handled by getCachedSetting's fallback
                Log.e(TAG, "Cannot set retain_samples, current config is null.");
                return;
            }
            Setting updatedSetting = new Setting();
            updatedSetting.id = current.id; // Preserve ID
            updatedSetting.arrived_threshold = current.arrived_threshold; // Preserve other settings
            updatedSetting.retain_samples = value; // Set the new value

            repository.upsertConfig(updatedSetting);
            // cachedSetting will be updated by the LiveData observer
            Log.i(TAG, "Requested update: retain_samples = " + value);
        });
    }

    public void setArrivedThreshold(int value) {
        executor.execute(() -> {
            Setting current = getCachedSetting();
            if (current == null) { // Should be handled by getCachedSetting's fallback
                Log.e(TAG, "Cannot set arrived_threshold, current config is null.");
                return;
            }
            Setting updatedSetting = new Setting();
            updatedSetting.id = current.id; // Preserve ID
            updatedSetting.retain_samples = current.retain_samples; // Preserve other settings
            updatedSetting.arrived_threshold = value; // Set the new value

            repository.upsertConfig(updatedSetting);
            // cachedSetting will be updated by the LiveData observer
            Log.i(TAG, "Requested update: arrived_threshold = " + value);
        });
    }
}
