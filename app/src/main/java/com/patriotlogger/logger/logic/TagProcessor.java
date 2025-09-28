package com.patriotlogger.logger.logic;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.patriotlogger.logger.data.KalmanState;
import com.patriotlogger.logger.data.RaceContext;
import com.patriotlogger.logger.data.Racer;
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.TagStatus;
import com.patriotlogger.logger.util.Kalman1D;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the core logic for processing BLE tag samples and managing their states.
 * This class is designed to be testable independently of Android service components.
 * It should be instantiated once and reused.
 */
public class TagProcessor {

    private final Repository repository;
    private final TagProcessorConfig config;

    public TagProcessor(@NonNull Repository repository, @NonNull TagProcessorConfig config) {
        this.repository = repository;
        this.config = config;
    }

    /**
     * Processes a single RSSI sample for a given tag.
     * This method is expected to be called on a worker thread due to DB interactions.
     *
     * @param tagId The ID of the tag.
     * @param rssi The RSSI value of the current sample.
     * @param nowMs The current timestamp in milliseconds.
     * @return The updated TagStatus.
     */
    @WorkerThread
    public TagStatus processSample(int tagId, int rssi, long nowMs) {
        TagStatus status = repository.getTagStatusNow(tagId);
        boolean isNewTagOverall = status == null;

        if (isNewTagOverall) {
            status = new TagStatus();
            status.tagId = tagId;
            status.entryTimeMs = nowMs;
            status.lowestRssi = rssi;
            status.state = "approaching";
        }

        // If tag was previously logged, reset its state for a new pass detection
        if ("logged".equals(status.state)) {
            status.state = "approaching";
            status.belowPeakCount = 0;
            status.peakRssi = -200f; // Reset peak RSSI for a new pass
            status.peakTimeMs = 0L;
            status.entryTimeMs = nowMs; // Mark new entry for this pass
            status.sampleCount = 0; // Reset sample count for the new pass
            // Kalman state will be reset/re-initialized below
        }
        
        status.lastSeenMs = nowMs; // Update last seen time for this tag

        if (status.friendlyName == null || status.friendlyName.isEmpty()) {
            Racer racer = repository.getRacerNow(tagId);
            if (racer != null && racer.name != null && !racer.name.isEmpty()) {
                status.friendlyName = racer.name;
            }
        }

        KalmanState kalmanPersistedState = repository.getKalmanStateByTagIdSync(tagId);
        Kalman1D kalmanFilter;

        // If the tag was logged, its Kalman state would have been deleted. Re-initialize.
        // Or, if it's a new pass (sampleCount == 0 for the current "approaching" state).
        if (kalmanPersistedState == null || ("approaching".equals(status.state) && status.sampleCount == 0)) {
            // Create a new Kalman1D, which internally creates a new KalmanState for this tagId
            kalmanFilter = new Kalman1D(tagId, config.defaultKalmanQ, config.defaultKalmanR, config.defaultKalmanInitialP);
        } else {
            // Rehydrate Kalman1D from the existing KalmanState object
            kalmanFilter = new Kalman1D(kalmanPersistedState);
        }

        float estimatedRssi = kalmanFilter.update(rssi);
        status.estimatedRssi = estimatedRssi;
        status.sampleCount += 1;

        if (estimatedRssi > status.peakRssi || status.peakTimeMs == 0L) { // also treat first sample for a pass as peak
            status.peakRssi = estimatedRssi;
            status.peakTimeMs = nowMs;
            status.belowPeakCount = 0;
            // If it was some other state (e.g. logged and reset) and we got a new peak, ensure it's approaching
            if (!"approaching".equals(status.state) && !"here".equals(status.state)) {
                status.state = "approaching";
            }
        } else {
            status.belowPeakCount += 1;
            if (status.belowPeakCount >= config.samplesBelowPeakForHere && "approaching".equals(status.state)) {
                status.state = "here";
            }
        }

        if (rssi < status.lowestRssi) {
            status.lowestRssi = rssi;
        }
        status.exitTimeMs = nowMs;

        repository.upsertTagStatus(status);
        // Get the (potentially modified) KalmanState from the filter and persist it
        repository.upsertKalmanState(kalmanFilter.getKalmanState());

        return status;
    }

    /**
     * Sweeps for tags that haven't been seen for a configured timeout and marks them as "logged".
     * This method is expected to be called on a worker thread.
     *
     * @param currentTimestampMs The current timestamp to compare against.
     * @return A list of TagStatus objects for tags that were newly marked as "logged".
     */
    @WorkerThread
    public List<TagStatus> performLossSweep(long currentTimestampMs) {
        List<TagStatus> newlyLoggedTags = new ArrayList<>();
        List<TagStatus> allStatuses = repository.allTagStatusesNow(); // Assumes this gives a current snapshot

        if (allStatuses == null) return newlyLoggedTags;

        for (TagStatus status : allStatuses) {
            if (!"logged".equals(status.state) && status.peakTimeMs > 0) { // Only consider tags that have been active and had a peak
                if ((currentTimestampMs - status.lastSeenMs) >= config.lossTimeoutMs) {
                    status.state = "logged";
                    repository.upsertTagStatus(status);
                    newlyLoggedTags.add(status);
                    // Clean up KalmanState for this tag as its pass is considered complete.
                    repository.deleteKalmanStateByTagId(status.tagId);
                }
            }
        }
        return newlyLoggedTags;
    }
}
