package com.patriotlogger.logger.logic;

import android.util.Log;
import androidx.annotation.NonNull;
import com.patriotlogger.logger.data.Setting;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus;

import java.util.List;

public class TagProcessor {
    private static final String TAG = "TagProcessor";

    private final TagPeakFinder peakFinder;

    private static final int SAMPLES_TO_CONFIRM_STATE = 3;
    private static final float EXIT_HYSTERESIS_DB = 1.0f;
    private static final float MIN_EMA_DELTA = 1.0f;

    private RssiSmoother rssiSmoother = new RssiSmoother();

    public TagProcessor(TagPeakFinder peakFinder) {
        this.peakFinder = peakFinder;
    }

    public TagStatus processSample(
            @NonNull TagStatus latestStatus,
            @NonNull Setting settings,
            int rawRssi,
            long nowMs
    ) {
        latestStatus.lastSeenMs = nowMs;
        updateLiveSmoothedRssi(latestStatus, rawRssi, settings);
        updateRawPeakIfApplicable(latestStatus, rawRssi, nowMs);
        updateState(latestStatus, settings);
        return latestStatus;
    }

    public TagStatus processTimedOutTagStatus(
            @NonNull TagStatus latestStatus,
            long nowMs
    ) {
        if (latestStatus.state == TagStatus.TagStatusState.HERE) {
            Log.w(TAG, "Tag #" + latestStatus.tagId + " timed out while HERE. Logging pass.");
            latestStatus.state = TagStatus.TagStatusState.LOGGED;
            latestStatus.exitTimeMs = nowMs;
        } else {
            Log.w(TAG, "Tag #" + latestStatus.tagId + " timed out while " + latestStatus.state + ". Marking TIMED_OUT.");
            latestStatus.state = TagStatus.TagStatusState.TIMED_OUT;
            latestStatus.exitTimeMs = nowMs;
        }
        return latestStatus;
    }

    private void updateLiveSmoothedRssi(@NonNull TagStatus status, int rawRssi, @NonNull Setting settings) {
        status.previousEmaRssi = status.emaRssi;
        if (status.emaRssi == 0.0f) {
            status.emaRssi = (float) rawRssi;
        } else {
            //status.emaRssi = (settings.rssi_averaging_alpha * rawRssi)
            //        + ((1 - settings.rssi_averaging_alpha) * status.emaRssi);
            status.emaRssi = RssiSmoother.computeSmoothedRssi(rawRssi,status.emaRssi,settings.rssi_averaging_alpha);
        }
    }

    private void updateRawPeakIfApplicable(@NonNull TagStatus status, int rawRssi, long nowMs) {
        if (status.state == TagStatus.TagStatusState.APPROACHING
                || status.state == TagStatus.TagStatusState.HERE) {
            if (rawRssi >= status.peakRssi) {
                status.peakRssi = rawRssi;
                status.peakTimeMs = nowMs;
            }
        }
    }

    private void updateState(@NonNull TagStatus status, @NonNull Setting settings) {
        switch (status.state) {
            case FIRST_SAMPLE:
                status.state = TagStatus.TagStatusState.TOO_FAR;
                break;

            case TOO_FAR:
                checkAndTransition(status, settings.approaching_threshold, TagStatus.TagStatusState.APPROACHING);
                break;

            case APPROACHING:
                if (!checkAndTransition(status, settings.arrived_threshold, TagStatus.TagStatusState.HERE)) {
                    if (status.emaRssi < settings.approaching_threshold) {
                        status.state = TagStatus.TagStatusState.TOO_FAR;
                        status.consecutiveRssiIncreases = 0;
                        status.previousEmaRssi = 0.0f;
                    }
                }
                break;

            case HERE:
                if (status.emaRssi < settings.arrived_threshold - EXIT_HYSTERESIS_DB) {
                    Log.i(TAG, "Tag #" + status.tagId + " : Logging pass.");
                    status.state = TagStatus.TagStatusState.LOGGED;
                    status.exitTimeMs = status.lastSeenMs;
                }
                break;

            case LOGGED:
            case TIMED_OUT:
                break;
        }
    }

    private boolean checkAndTransition(
            @NonNull TagStatus status,
            int threshold,
            @NonNull TagStatus.TagStatusState nextState
    ) {
        boolean isRssiIncreasing = (status.previousEmaRssi == 0.0f)
                || (status.emaRssi - status.previousEmaRssi >= MIN_EMA_DELTA);

        if (status.emaRssi >= threshold && isRssiIncreasing) {
            status.consecutiveRssiIncreases++;
        } else {
            status.consecutiveRssiIncreases = 0;
        }

        if (status.consecutiveRssiIncreases >= SAMPLES_TO_CONFIRM_STATE) {
            Log.i(TAG, "Tag #" + status.tagId + " confirmed " + nextState.name());
            status.state = nextState;
            status.consecutiveRssiIncreases = 0;
            status.previousEmaRssi = 0.0f;
            return true;
        }
        return false;
    }

    public void findAndSetPeakTime(
            @NonNull TagStatus status,
            @NonNull List<TagData> samples,
            @NonNull Setting settings
    ) {
        TagPeakData peakData = peakFinder.findPeak(samples, settings);
        if (peakData != null) {
            status.peakTimeMs = peakData.peakTimeMs;
            status.peakRssi = peakData.peakRssi;
            Log.w(TAG, ">>> Corrected Split Time for #" + status.tagId + " = " + status.peakTimeMs);
        } else {
            Log.e(TAG, "Peak finder failed for trackId: " + status.trackId + " — using live fallback peak.");
        }
    }
}
