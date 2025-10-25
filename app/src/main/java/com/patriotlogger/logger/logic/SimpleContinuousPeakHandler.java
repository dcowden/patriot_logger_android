package com.patriotlogger.logger.logic;

import androidx.annotation.NonNull;

import com.patriotlogger.logger.data.Setting;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus;
import com.patriotlogger.logger.data.TagStatus.TagStatusState;

import java.util.ArrayList;
import java.util.List;

/**
 * SimpleContinuousPeakHandler
 *
 * (1) Smooths incoming RSSI with EMA (configurable alpha; default 0.10).
 * (2) Triggers APPROACHING on N=approachRequiredPoints EMA readings above approachThresholdDbm.
 * (3) After APPROACHING, continuously runs TagPeakFinder on the SMOOTHED history and tracks the peak.
 * (4) Triggers LOGGED once (now - lastPeakChangeMs) >= stableAfterPeakMs (default 1500 ms).
 *
 * NEW: Cooldown — after LOGGED, ignore samples for cooldownMs (default 20s) so a new track can't start.
 */
public final class SimpleContinuousPeakHandler implements RssiHandler {

    // ---- Defaults ----
    public static final float DEFAULT_ALPHA = 0.10f;
    public static final int   DEFAULT_APPROACH_THRESHOLD_DBM = -90;
    public static final int   DEFAULT_APPROACH_REQUIRED_POINTS = 3;
    public static final long  DEFAULT_STABLE_AFTER_PEAK_MS = 1500L;    // 1.5 s
    public static final long  DEFAULT_COOLDOWN_MS = 40_000L;           // 20 s

    // ---- Config ----
    private final float alpha;
    private final int approachThresholdDbm;
    private final int approachRequiredPoints;
    private final long stableAfterPeakMs;
    private final long cooldownMs;              // <- NEW

    // ---- State ----
    private Float ema = null;

    private int approachHits = 0;

    private final List<TagData> smoothedHistory = new ArrayList<>(256);
    private final TagPeakFinder peakFinder = new TagPeakFinder();
    private final Setting pfSetting = new Setting();

    private Long lastPeakTimeFromFinder = null;
    private int  lastPeakRssiFromFinder = -200;
    private long lastPeakChangeMs = 0L;

    private long lastLoggedTimeMs = 0L;         // <- NEW: start of cooldown

    public SimpleContinuousPeakHandler() {
        this(DEFAULT_ALPHA, DEFAULT_APPROACH_THRESHOLD_DBM, DEFAULT_APPROACH_REQUIRED_POINTS,
                DEFAULT_STABLE_AFTER_PEAK_MS, DEFAULT_COOLDOWN_MS);
    }

    public SimpleContinuousPeakHandler(float alpha,
                                       int approachThresholdDbm,
                                       int approachRequiredPoints,
                                       long stableAfterPeakMs) {
        this(alpha, approachThresholdDbm, approachRequiredPoints, stableAfterPeakMs, DEFAULT_COOLDOWN_MS);
    }

    // NEW: constructor with explicit cooldown
    public SimpleContinuousPeakHandler(float alpha,
                                       int approachThresholdDbm,
                                       int approachRequiredPoints,
                                       long stableAfterPeakMs,
                                       long cooldownMs) {
        this.alpha = alpha;
        this.approachThresholdDbm = approachThresholdDbm;
        this.approachRequiredPoints = Math.max(1, approachRequiredPoints);
        this.stableAfterPeakMs = Math.max(0L, stableAfterPeakMs);
        this.cooldownMs = Math.max(0L, cooldownMs);
        this.pfSetting.rssi_averaging_alpha = alpha;
    }

    @Override
    public void init() {
        ema = null;

        approachHits = 0;
        smoothedHistory.clear();
        lastPeakTimeFromFinder = null;
        lastPeakRssiFromFinder = -200;
        lastPeakChangeMs = 0L;
        // keep lastLoggedTimeMs as-is across init? No: init is called per new file, so reset.
        lastLoggedTimeMs = 0L;
    }

    @Override
    public @NonNull TagStatus acceptSample(@NonNull TagStatus status,
                                           @NonNull List<TagData> history,
                                           @NonNull RssiData sample) {
        final long nowMs = sample.timestampMs;

        // ----- COOLDOWN GUARD (NEW) -----
        if (lastLoggedTimeMs > 0 && (nowMs - lastLoggedTimeMs) < cooldownMs) {
            // Ignore this sample for approach/peak logic; just advance lastSeen.
            status.lastSeenMs = nowMs;
            return status;
        }

        // 1) EMA update
        //final int raw = sample.rssi;
        //if (ema == null) ema = (float) raw;
        //else ema = alpha * raw + (1 - alpha) * ema;

        // Keep a smoothed point for the peak finder
        smoothedHistory.add(new TagData(sample.tagId, nowMs, Math.round(sample.rssi)));

        // 2) Count hits above threshold (unchanged: each above counts)
        final boolean isAbove = sample.rssi > approachThresholdDbm;
        if (isAbove) {
            approachHits++;
        }

        if (status.state != TagStatusState.APPROACHING && approachHits >= approachRequiredPoints) {
            status.state = TagStatusState.APPROACHING;
            status.entryTimeMs = nowMs;
        }

        // 3) Continuous peak tracking
//        if (smoothedHistory.size() >= 3) {
//            TagPeakData peak = peakFinder.findPeak(smoothedHistory, pfSetting);
//            if (peak != null) {
//                long peakTime = peak.peakTimeMs;
//                int  peakRssi = Math.round(peak.peakRssi);
//
//                if (peakRssi > lastPeakRssiFromFinder) {
//                    if (lastPeakTimeFromFinder == null || peakTime != lastPeakTimeFromFinder)
//                        lastPeakChangeMs = nowMs;
//                    lastPeakTimeFromFinder = peakTime;
//                    lastPeakRssiFromFinder = peakRssi;
//                    status.peakRssi = peakRssi;
//                    status.peakTimeMs = peakTime;
//                }
//            }
//        }
        if (smoothedHistory.size() >= 3) {
            TagPeakData peak = peakFinder.findPeak(smoothedHistory, pfSetting);
            if (peak != null) {
                final long now = sample.timestampMs; // or nowMs if you prefer

                final long peakTime = peak.peakTimeMs;
                final int  peakRssi = Math.round(peak.peakRssi);

                final int  EPS_DB = 1; // tolerance (dB)

                final boolean first = (lastPeakTimeFromFinder == null);
                final boolean higher = (peakRssi > lastPeakRssiFromFinder + EPS_DB);
                final boolean sameValue = Math.abs(peakRssi - lastPeakRssiFromFinder) <= EPS_DB;
                final boolean later = (!first && peakTime >  lastPeakTimeFromFinder);
                final boolean earlier = (!first && peakTime <  lastPeakTimeFromFinder);

                // Accept rules:
                //  1) first result, OR
                //  2) strictly higher value (allow time to move either way), OR
                //  3) same value (±ε) but time moved forward.
                // Reject earlier+lower (regressive) updates caused by zero-phase edge effects.
                final boolean accept =
                        first ||
                                higher ||
                                (sameValue && later);

                System.out.println("At sample time " + now + ", Peak=" + peakRssi + " @ " + peakTime +
                        (accept ? "  <ACCEPT>" : "  <REJECT>"));

                if (accept) {
                    lastPeakChangeMs = now;
                    lastPeakTimeFromFinder = peakTime;
                    lastPeakRssiFromFinder = peakRssi;
                    status.peakTimeMs = peakTime;
                    status.peakRssi   = peakRssi;
                    System.out.println("New Peak.");
                }
                // else: ignore regressive updates; stability timer does NOT reset
            }
        }


        // 4) Closure rule


        if (status.state == TagStatusState.APPROACHING &&  (nowMs - lastPeakChangeMs) >= stableAfterPeakMs) {
            long sinceChange = nowMs - lastPeakChangeMs;
            if (sinceChange >= stableAfterPeakMs) {
                System.out.println("LOGGED.");
                status.state = TagStatusState.LOGGED;
                status.exitTimeMs = nowMs;
                lastLoggedTimeMs = nowMs;
            }        }


        status.lastSeenMs = nowMs;
        return status;
    }

    @Override
    public TagStatus timeOut(TagStatus currentStatus, long nowMs) {
        if (currentStatus == null) return null;

        currentStatus.state = TagStatusState.LOGGED;
        currentStatus.exitTimeMs = nowMs;
        lastLoggedTimeMs = nowMs; // <- NEW: start cooldown on timeout close
        return currentStatus;
    }

    @Override
    public String getName() {
        // unchanged on purpose
        return "SimpleContinuousPeakHandler{alpha=" + alpha +
                ", thr=" + approachThresholdDbm +
                ", hits=" + approachRequiredPoints +
                ", stableMs=" + stableAfterPeakMs + "}";
    }

    @Override
    public String toString() {
        return getName();
    }
}
