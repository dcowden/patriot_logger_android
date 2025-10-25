package com.patriotlogger.logger.logic;

import androidx.annotation.NonNull;

import com.patriotlogger.logger.data.Setting;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus;
import com.patriotlogger.logger.data.TagStatus.TagStatusState;

import java.util.ArrayList;
import java.util.List;

/**
 * SuperSimpleHandler
 *
 * Causal, smoothed-input-only peak detection.
 * (0) Uses sample.smoothedRssi (no internal EMA).
 * (1) Enter APPROACHING on 3 non-consecutive upward crossings above approachThresholdDbm (default -90 dBm).
 * (2) Track peak strictly from smoothedRssi; accept only "higher" (by ε) or "same value but later".
 * (3) Enter LOGGED when the peak hasn't improved for stableAfterPeakMs (default 1500 ms).
 * (4) After LOGGED, ignore samples for cooldownMs (default 20 s) before a new track can start.
 */
public final class SuperSimpleHandler  {

    // ---- Defaults ----
    public static final int   DEFAULT_APPROACH_THRESHOLD_DBM = -90;
    public static final int   DEFAULT_APPROACH_REQUIRED_UPCROSSES = 3;
    public static final long  DEFAULT_STABLE_AFTER_PEAK_MS = 1500L;
    public static final long  DEFAULT_COOLDOWN_MS = 20_000L;  // 20 s
    private static final int  EPS_DB = 0; // tolerance for "same value" comparisons

    // ---- Config ----
    private final int  approachThresholdDbm;
    private final int  requiredUpCrosses;
    private final long stableAfterPeakMs;
    private final long cooldownMs;

    // ---- State ----
    private boolean wasAbove = false;         // for non-consecutive upward crossings
    private int     upCrossCount = 0;         // number of upward crossings seen

    private Integer peakRssi = null;          // best smoothed dB
    private Long    peakTimeMs = null;        // timestamp of best smoothed dB
    private long    lastPeakChangeMs = 0L;    // when the peak last improved (value↑ or same value later)

    private long    lastLoggedTimeMs = 0L;    // start time of cooldown

    private List<TagData> history = new ArrayList<>();
    public SuperSimpleHandler() {
        this(DEFAULT_APPROACH_THRESHOLD_DBM,
                DEFAULT_APPROACH_REQUIRED_UPCROSSES,
                DEFAULT_STABLE_AFTER_PEAK_MS,
                DEFAULT_COOLDOWN_MS);
    }

    public SuperSimpleHandler(int approachThresholdDbm,
                              int requiredUpCrosses,
                              long stableAfterPeakMs,
                              long cooldownMs) {
        this.approachThresholdDbm = approachThresholdDbm;
        this.requiredUpCrosses = Math.max(1, requiredUpCrosses);
        this.stableAfterPeakMs = Math.max(0L, stableAfterPeakMs);
        this.cooldownMs = Math.max(0L, cooldownMs);
    }


    public void init() {
        wasAbove = false;
        upCrossCount = 0;

        peakRssi = null;
        peakTimeMs = null;
        lastPeakChangeMs = 0L;

        lastLoggedTimeMs = 0L;
    }


    public @NonNull TagStatus acceptSample(@NonNull TagStatus status,
                                           @NonNull List<TagData> history,
                                           @NonNull RssiData sample) {
        final long nowMs = sample.timestampMs;
        this.history = history; //HACK-- processors and handlers are all fucked up. have to toally refactor TagData, RssiData, TagHandler, Handler, all tha tshit.
        // ----- COOLDOWN: block new tracks during cooldown -----
        if (lastLoggedTimeMs > 0 && (nowMs - lastLoggedTimeMs) < cooldownMs) {
            status.lastSeenMs = nowMs;
            return status;
        }

        // 0) Use pre-smoothed value from the packet
        final int smoothed = sample.smoothedRssi;

        // 1) Non-consecutive upward crossings to enter APPROACHING
        final boolean isAbove = (smoothed > approachThresholdDbm);
        //if (isAbove && !wasAbove) {
        if (isAbove ) {
            upCrossCount++;
        }
        wasAbove = isAbove;

        if (status.state != TagStatusState.APPROACHING && upCrossCount >= requiredUpCrosses) {
            status.state = TagStatusState.APPROACHING;
            status.entryTimeMs = nowMs;

            // reset peak tracking at the moment we start a pass
            peakRssi = null;
            peakTimeMs = null;
            lastPeakChangeMs = nowMs;
        }

        // 2) Peak tracking (smoothed-only). Accept only higher, or same value but later.
        boolean acceptPeak = false;
        if (peakRssi == null) {
            acceptPeak = true; // first observation in/after approach
        } else {
            boolean higher    = smoothed > (peakRssi + EPS_DB);
            boolean sameValue = Math.abs(smoothed - peakRssi) <= EPS_DB;
            boolean later     = (peakTimeMs != null && nowMs > peakTimeMs);
            acceptPeak = higher || (sameValue && later);
            //acceptPeak = higher;
        }

        if (acceptPeak) {
            peakRssi = smoothed;
            peakTimeMs = nowMs;
            lastPeakChangeMs = nowMs;

            status.peakRssi = smoothed;
            status.peakTimeMs = nowMs;
        }

        // 3) Close (LOGGED) when the peak hasn't improved for stableAfterPeakMs
        if (status.state == TagStatusState.APPROACHING && peakTimeMs != null) {
            long sinceChange = nowMs - lastPeakChangeMs;
            if (sinceChange >= stableAfterPeakMs) {
                status.state = TagStatusState.LOGGED;
                status.exitTimeMs = nowMs;
                lastLoggedTimeMs = nowMs;          // start cooldown


                //now find the 'right' peak using zero phase
                TagPeakFinder finder = new TagPeakFinder();
                Setting s = new Setting();
                s.rssi_averaging_alpha = 0.3f;
                TagPeakData peak = finder.findPeak(history,s);
                status.peakTimeMs = peak.peakTimeMs;
                status.peakRssi = peak.peakRssi;
                // reset for next pass post-cooldown
                upCrossCount = 0;
                wasAbove = false;
                peakRssi = null;
                peakTimeMs = null;
                lastPeakChangeMs = nowMs;
            }
        }

        status.lastSeenMs = nowMs;
        return status;
    }


    public TagStatus timeOut(TagStatus currentStatus,  long nowMs) {
        if (currentStatus == null) return null;


        // Apply same stability rule on silence
        if (currentStatus.state == TagStatusState.APPROACHING && peakTimeMs != null) {
            long sinceChange = nowMs - lastPeakChangeMs;
            if (sinceChange >= stableAfterPeakMs) {
                currentStatus.state = TagStatusState.LOGGED;
                currentStatus.exitTimeMs = nowMs;
                lastLoggedTimeMs = nowMs;          // start cooldown


                //now find the 'right' peak using zero phase
                TagPeakFinder finder = new TagPeakFinder();
                Setting s = new Setting();
                s.rssi_averaging_alpha = 0.3f;
                TagPeakData peak = finder.findPeak(history,s);
                currentStatus.peakTimeMs = peak.peakTimeMs;
                currentStatus.peakRssi = peak.peakRssi;

                // reset for next pass post-cooldown
                upCrossCount = 0;
                wasAbove = false;
                peakRssi = null;
                peakTimeMs = null;
                lastPeakChangeMs = nowMs;
            }
        }
        currentStatus.lastSeenMs = nowMs;
        return currentStatus;
    }


    public String getName() {
        return "SuperSimpleHandler{thr=" + approachThresholdDbm +
                ", upCrosses=" + requiredUpCrosses +
                ", stableMs=" + stableAfterPeakMs +
                ", cooldownMs=" + cooldownMs + "}";
    }

    @Override
    public String toString() {
        return getName();
    }
}
