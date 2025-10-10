package com.patriotlogger.logger.logic;

import android.util.Log;
import androidx.annotation.NonNull;
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.Racer;
import com.patriotlogger.logger.data.Setting;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus;
import com.patriotlogger.logger.service.BleScannerService;

/**
 * Enhanced processor that owns all business logic for a BLE sample.
 * It handles the full lifecycle: cooldown, state transitions, and database persistence.
 * It is designed to be called from a background thread.
 */
public class TagProcessor {
    private static final String TAG = "TagProcessor";
    private final Repository repository;
    private final RssiSmoother smoother;
    public TagProcessor(Repository repository) {
        this.repository = repository;
        this.smoother = new RssiSmoother();
    }

    /**
     * Main entry point for processing a raw scan result during race mode.
     * Contains all logic moved from BleScannerService and the old TagProcessor.
     */
    public void process(int tagId, int rssi, long nowMs, @NonNull Setting settings) {
        Object tagLock = repository.getTagLock(tagId);
        synchronized (tagLock) {
            TagStatus currentStatus = repository.getDatabase().tagStatusDao().getActiveStatusForTagIdSync(tagId);

            if (currentStatus == null) {
                // No active pass, so we might be starting a new one.
                if (isTagInCooldown(tagId, nowMs, settings)) return;
                if (isSignalTooWeak(rssi, settings)) return;
                currentStatus = createNewPass(tagId, rssi, nowMs);
            }

            // Update with the latest info from this sample
            currentStatus.lastSeenMs = nowMs;

            // Run the state machine logic
            TagStatus.TagStatusState previousState = currentStatus.state;
            updateState(currentStatus, rssi, settings);
            TagStatus.TagStatusState newState = currentStatus.state;

            // --- Persistence and Post-Processing ---

            // 1. Save the updated status (this will also generate a trackId for new passes)
            currentStatus.trackId = (int) repository.getDatabase().tagStatusDao().upsertSync(currentStatus);

            // 2. Save the raw sample data if retention is enabled
            if (settings.retain_samples && currentStatus.trackId != 0 && currentStatus.isInProcess()) {
                repository.getDatabase().tagDataDao().insert(new TagData(currentStatus.trackId, nowMs, rssi));
            }

            // 3. Handle state transition side-effects (e.g., deleting samples on LOGGED)
            if (newState == TagStatus.TagStatusState.LOGGED && previousState != TagStatus.TagStatusState.LOGGED) {
                Log.i(TAG, "Tag " + tagId + " transitioned to LOGGED for trackId: " + currentStatus.trackId);
                if (!settings.retain_samples) {
                    Log.i(TAG, "Sample retention is off. Deleting data for trackId: " + currentStatus.trackId);
                    repository.getDatabase().tagDataDao().deleteSamplesForTrackIdSync(currentStatus.trackId);
                }
                // TODO: Handle UI notifications for LOGGED event here, perhaps via a callback
            }
        }
    }

    private boolean isTagInCooldown(int tagId, long nowMs, @NonNull Setting settings) {
        TagStatus lastLogged = repository.getDatabase().tagStatusDao().getLastLoggedForTagId(tagId);
        if (lastLogged != null) {
            if ((nowMs - lastLogged.lastSeenMs) < Setting.DEFAULT_COOLDOWN_MS) {
                Log.v(TAG, "Tag " + tagId + " is within cooldown. Ignoring.");
                return true;
            }
        }
        return false;
    }

    private boolean isSignalTooWeak(int rssi, @NonNull Setting settings) {
        if (rssi < settings.approaching_threshold) {
            Log.v(TAG, "Signal " + rssi + " is below approaching threshold. Ignoring.");
            return true;
        }
        return false;
    }

    private TagStatus createNewPass(int tagId, int rssi, long nowMs) {
        Racer racer = repository.getDatabase().racerDao().getSync(tagId);
        String friendlyName = (racer != null && racer.name != null && !racer.name.isEmpty()) ? racer.name : BleScannerService.TAG_PREFIX + "-" + tagId;

        TagStatus newStatus = new TagStatus();
        newStatus.tagId = tagId;
        newStatus.entryTimeMs = nowMs;
        newStatus.state = TagStatus.TagStatusState.FIRST_SAMPLE;
        newStatus.peakRssi = (float) rssi;
        newStatus.emaRssi = (float) rssi; // Initialize EMA with the first value
        newStatus.friendlyName = friendlyName;

        Log.d(TAG, "Creating new pass for tagId: " + tagId);
        return newStatus;
    }

    private void updateState(TagStatus status, int rssi, @NonNull Setting settings) {
        // This is the core logic from your original TagProcessor
        if (status.state != TagStatus.TagStatusState.LOGGED) {
            status.emaRssi = smoother.getNext(rssi, settings.rssi_averaging_alpha);
        }

        if (status.emaRssi > status.peakRssi) {
            status.peakRssi = status.emaRssi;
            status.peakTimeMs = status.lastSeenMs;
        }

        // Apply state transitions
        switch (status.state) {
            case FIRST_SAMPLE:
            case APPROACHING:
                if (status.emaRssi >= settings.arrived_threshold) {
                    status.state = TagStatus.TagStatusState.HERE;
                }
                break;
            case HERE:
                if (status.emaRssi < settings.approaching_threshold) {
                    status.state = TagStatus.TagStatusState.LOGGED;
                    status.exitTimeMs = status.lastSeenMs;
                }
                break;
            case LOGGED:
                // Stays logged
                break;
        }
    }
}
