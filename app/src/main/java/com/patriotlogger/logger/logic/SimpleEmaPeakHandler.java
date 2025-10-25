package com.patriotlogger.logger.logic;

import androidx.annotation.NonNull;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus;
import com.patriotlogger.logger.data.TagStatus.TagStatusState;

import java.util.List;

/**
 * Simple EMA-based handler with cooldown.
 *  - Smooths RSSI using EMA (alpha=0.3)
 *  - Switches to APPROACHING after >=3 samples above threshold (-90dBm)
 *  - Tracks highest EMA and peakTimeMs
 *  - Closes to LOGGED when EMA falls <= threshold OR
 *    more than maxPeakAgeMs (3s default) since the last peak
 *  - No HERE state is ever emitted
 *  - Ignores samples for cooldownMs (default 30s) after a LOGGED
 */
public final class SimpleEmaPeakHandler implements RssiHandler {

    private static final float DEFAULT_ALPHA = 0.30f;
    private static final int DEFAULT_THRESHOLD_DBM = -90;
    private static final int DEFAULT_REQUIRED_SAMPLES = 3;
    private static final long DEFAULT_MAX_PEAK_AGE_MS = 3000L;
    private static final long DEFAULT_COOLDOWN_MS = 30_000L; // 30 seconds

    private final float alpha;
    private final int thresholdDbm;
    private final int requiredSamples;
    private final long maxPeakAgeMs;
    private final long cooldownMs;

    private Float ema = null;
    private int samplesAbove = 0;
    private int peakRssi = Integer.MIN_VALUE;
    private long lastPeakTimeMs = 0L;
    private long lastLoggedTimeMs = 0L;

    public SimpleEmaPeakHandler() {
        this(DEFAULT_ALPHA, DEFAULT_THRESHOLD_DBM, DEFAULT_REQUIRED_SAMPLES, DEFAULT_MAX_PEAK_AGE_MS, DEFAULT_COOLDOWN_MS);
    }

    public SimpleEmaPeakHandler(float alpha, int thresholdDbm, int requiredSamples, long maxPeakAgeMs, long cooldownMs) {
        this.alpha = alpha;
        this.thresholdDbm = thresholdDbm;
        this.requiredSamples = requiredSamples;
        this.maxPeakAgeMs = maxPeakAgeMs;
        this.cooldownMs = cooldownMs;
    }

    @Override
    public void init() {}


    @Override
    public TagStatus timeOut ( TagStatus currentStatus, long nowMs){
        if ( currentStatus.state == TagStatusState.APPROACHING ){
            currentStatus.state = TagStatusState.LOGGED;
            currentStatus.exitTimeMs = nowMs;
            return currentStatus;
        }
        else{
            return currentStatus;
        }
    }

    @Override
    public @NonNull TagStatus acceptSample(@NonNull TagStatus status,
                                           @NonNull List<TagData> history,
                                           @NonNull RssiData sample) {
        final long nowMs = sample.timestampMs;

        // --- Cooldown period ---
        if (lastLoggedTimeMs > 0 && nowMs - lastLoggedTimeMs < cooldownMs) {
            // Still cooling down; ignore this sample completely.
            status.lastSeenMs = nowMs;
            return status;
        }

        final int raw = sample.rssi;

        // --- EMA update ---
        if (ema == null) ema = (float) raw;
        else ema = alpha * raw + (1 - alpha) * ema;

        // --- Track peaks ---
        if (ema > peakRssi) {
            peakRssi = Math.round(ema);
            status.peakTimeMs = nowMs;
            lastPeakTimeMs = nowMs;
        }

        // --- Count samples above threshold ---
        if (ema > thresholdDbm) samplesAbove++;

        // --- Enter APPROACHING after enough samples ---
        if (status.state != TagStatusState.APPROACHING && samplesAbove >= requiredSamples) {
            status.state = TagStatusState.APPROACHING;
            status.entryTimeMs = nowMs;
        }

        // --- Close when EMA drops or peak stale ---
        if (status.state == TagStatusState.APPROACHING) {
            boolean dippedBelow = ema <= thresholdDbm;
            boolean peakStale = (lastPeakTimeMs > 0) && (nowMs - lastPeakTimeMs >= maxPeakAgeMs);
            if ( peakStale) {
                status.state = TagStatusState.LOGGED;
                status.exitTimeMs = nowMs;
                lastLoggedTimeMs = nowMs; // Start cooldown
            }
        }

        status.lastSeenMs = nowMs;
        return status;
    }

    @Override
    public String getName() {
        return toString();
    }

    @Override
    public String toString() {
        return "SimpleEmaPeakHandler{" +
                "alpha=" + alpha +
                ", t/hhDbm=" + thresholdDbm +
                ", peakeAge:" + maxPeakAgeMs +
                '}';
    }
}
