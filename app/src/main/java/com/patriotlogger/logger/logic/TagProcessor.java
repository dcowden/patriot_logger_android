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

    // kept but no longer used for transitions (we only use dwell now)
    private static final int SAMPLES_TO_CONFIRM_STATE = 3;
    private static final float EXIT_HYSTERESIS_DB = 1.0f;
    private static final float MIN_EMA_DELTA = 0.1f;

    // Dwell-time hold for transitions
    private static final long HOLD_MS = 1000L; // 1 second

    // Per-target-state dwell timers (so the helper can be reused)
    private long approachGateStartMs = 0L; // for transitions targeting APPROACHING
    private long arrivedGateStartMs  = 0L; // for transitions targeting HERE

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
            status.emaRssi = RssiSmoother.computeSmoothedRssi(rawRssi, status.emaRssi, settings.rssi_averaging_alpha);
        }
        Log.i(TAG, String.format("updateSmoothedRssi: raw=%d, prev=%.2f, ema=%.2f, delta=%.2f",
                rawRssi, status.previousEmaRssi, status.emaRssi, status.emaRssi - status.previousEmaRssi));
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
                Log.d(TAG, "State: FIRST_SAMPLE -> TOO_FAR");
                resetDwellTimers();
                break;

            case TOO_FAR:
                // Use dwell-only logic for TOO_FAR -> APPROACHING
                checkAndTransition(status,
                        settings.approaching_threshold,
                        TagStatus.TagStatusState.APPROACHING,
                        HOLD_MS);
                break;

            case APPROACHING:
                Log.d(TAG, String.format("State APPROACHING: ema=%.2f, arriveThresh=%d, approachThresh=%d",
                        status.emaRssi, settings.arrived_threshold, settings.approaching_threshold));

                // Use dwell-only logic for APPROACHING -> HERE
                if (!checkAndTransition(status,
                        settings.arrived_threshold,
                        TagStatus.TagStatusState.HERE,
                        HOLD_MS)) {

                    // If it fades below approach threshold, fall back
                    if (status.emaRssi < settings.approaching_threshold) {
                        Log.d(TAG, "APPROACHING -> TOO_FAR (signal faded)");
                        status.state = TagStatus.TagStatusState.TOO_FAR;
                        status.consecutiveRssiIncreases = 0; // kept for compatibility; not used
                        status.previousEmaRssi = 0.0f;
                        resetDwellTimers();
                    }
                }
                break;

            case HERE:
                float exitThreshold = settings.arrived_threshold - EXIT_HYSTERESIS_DB;
                Log.d(TAG, String.format("State HERE: ema=%.2f, exitThresh=%.2f",
                        status.emaRssi, exitThreshold));
                if (status.emaRssi < exitThreshold) {
                    Log.i(TAG, "Tag #" + status.tagId + " : Logging pass.");
                    status.state = TagStatus.TagStatusState.LOGGED;
                    status.exitTimeMs = status.lastSeenMs;
                    resetDwellTimers();
                }
                break;

            case LOGGED:
            case TIMED_OUT:
                break;
        }
    }

    /**
     * Dwell-only transition checker:
     * Require EMA >= threshold for at least dwellMs (time-based). No "increasing" / consecutive-sample rule.
     */
    private boolean checkAndTransition(
            @NonNull TagStatus status,
            int threshold,
            @NonNull TagStatus.TagStatusState nextState,
            long dwellMs
    ) {
        final boolean aboveThreshold = status.emaRssi >= threshold;

        long start = getDwellStartFor(nextState);
        if (aboveThreshold) {
            if (start == 0L) {
                start = status.lastSeenMs;
                setDwellStartFor(nextState, start);
                Log.d(TAG, nextState + ": above threshold, starting dwell at " + start);
            }
            long dwell = status.lastSeenMs - start;
            if (dwell >= dwellMs) {
                Log.i(TAG, "Tag #" + status.tagId + " met dwell (" + dwell + " ms) -> " + nextState.name());
                status.state = nextState;
                status.consecutiveRssiIncreases = 0; // compatibility, unused
                status.previousEmaRssi = 0.0f;
                setDwellStartFor(nextState, 0L); // reset the used timer
                resetOtherDwell(nextState);
                return true;
            }
        } else {
            if (start != 0L) {
                Log.d(TAG, nextState + ": dropped below gate; resetting dwell timer");
            }
            setDwellStartFor(nextState, 0L);
        }
        return false;
    }

    private void resetDwellTimers() {
        approachGateStartMs = 0L;
        arrivedGateStartMs  = 0L;
    }

    private void resetOtherDwell(TagStatus.TagStatusState keep) {
        if (keep != TagStatus.TagStatusState.APPROACHING) approachGateStartMs = 0L;
        if (keep != TagStatus.TagStatusState.HERE)        arrivedGateStartMs  = 0L;
    }

    private long getDwellStartFor(TagStatus.TagStatusState nextState) {
        switch (nextState) {
            case APPROACHING: return approachGateStartMs;
            case HERE:        return arrivedGateStartMs;
            default:          return 0L;
        }
    }

    private void setDwellStartFor(TagStatus.TagStatusState nextState, long valueMs) {
        switch (nextState) {
            case APPROACHING: approachGateStartMs = valueMs; break;
            case HERE:        arrivedGateStartMs  = valueMs; break;
            default: /* no dwell tracked */ break;
        }
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
            Log.w(TAG, ">>> Corrected Split Time for #" + status.tagId + " = " + status.peakTimeMs + " peakRssi=" + status.peakRssi + " <<<");
        } else {
            Log.e(TAG, "Peak finder failed for trackId: " + status.trackId + " — using live fallback peak.");
        }
    }
}
