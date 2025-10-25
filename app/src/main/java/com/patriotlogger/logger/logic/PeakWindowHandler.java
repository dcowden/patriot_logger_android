package com.patriotlogger.logger.logic;

import androidx.annotation.NonNull;

import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus;
import com.patriotlogger.logger.data.TagStatus.TagStatusState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Simple, robust "one peak per pass" detector.
 *
 * Strategy (Rising-Window Max):
 *  - Smooth with EMA (alpha).
 *  - Open a pass window when either:
 *      (a) smoothed rises by >= riseDb within riseWindowMs, OR
 *      (b) smoothed crosses entryRailUp (-90 dBm) upward.
 *  - While window is open, track the max (peak).
 *  - Close window when any holds:
 *      (a) smoothed drops trailingDropDb below peak, OR
 *      (b) smoothed crosses exitRailDown (-92 dBm) downward, OR
 *      (c) idle gap > idleGapCloseMs (no samples).
 *  - On close: mark HERE at the peak, then (next sample) mark LOGGED.
 *  - Enforce cooldownMs to avoid double-peaks.
 */
public final class PeakWindowHandler implements RssiHandler {

    // --- Tunables (pick simple, safe defaults) ---
    private final double alpha;             // EMA smoothing factor
    private final double riseDb;            // open if rise >= this within window
    private final long   riseWindowMs;      // window length for rise detection
    private final double trailingDropDb;    // close if fallen this far from peak
    private final double entryRailUp;       // also open when crossing this upward
    private final double exitRailDown;      // close when crossing this downward
    private final long   idleGapCloseMs;    // close if we stop seeing samples
    private final long   cooldownMs;        // suppress new window after logging
    private final int    sustainSamples;    // how many samples confirm a candidate

    // --- Per-track state (one instance per active track) ---
    private boolean windowOpen = false;
    private boolean awaitingLog = false;

    private long    lastSeenMs = Long.MIN_VALUE;
    private long    windowOpenedAt = 0L;
    private long    cooldownUntil  = Long.MIN_VALUE;

    private double  ema = Double.NaN;
    private double  peak = -200.0;
    private long    tAtPeak = 0L;

    // tiny deque to detect rise within a time window
    private final Deque<Pt> riseBuf = new ArrayDeque<>();
    private int runCountAtOrAbove = 0; // sustain counter for candidates

    private static final class Pt {
        final long t; final double y;
        Pt(long t, double y) { this.t = t; this.y = y; }
    }

    public static final class Params {
        public double alpha = 0.30;
        public double riseDb = 6.0;
        public long   riseWindowMs = 8000;
        public double trailingDropDb = 5.0;
        public double entryRailUp = -90.0;
        public double exitRailDown = -92.0;
        public long   idleGapCloseMs = 6000;
        public long   cooldownMs = 30_000;
        public int    sustainSamples = 2;
    }

    public PeakWindowHandler() {
        this(new Params());
    }
    public void init(){

    }
    public PeakWindowHandler(@NonNull Params p) {
        this.alpha = p.alpha;
        this.riseDb = p.riseDb;
        this.riseWindowMs = p.riseWindowMs;
        this.trailingDropDb = p.trailingDropDb;
        this.entryRailUp = p.entryRailUp;
        this.exitRailDown = p.exitRailDown;
        this.idleGapCloseMs = p.idleGapCloseMs;
        this.cooldownMs = p.cooldownMs;
        this.sustainSamples = Math.max(1, p.sustainSamples);
    }

    @Override @NonNull
    public TagStatus acceptSample(@NonNull TagStatus status,
                                  @NonNull List<TagData> history,
                                  @NonNull RssiData sample) {

        final long now = sample.timestampMs;
        final double x = sample.smoothedRssi; // use smoothed for logic (handler-local EMA too)
        status.lastSeenMs = now;              // keep TagStatus current

        // Maintain small EMA dedicated to this handler (independent of RssiSmoother)
        if (Double.isNaN(ema)) ema = x;
        else                   ema = ema + alpha * (x - ema);

        // Cooldown: keep state as LOGGED until cooldown ends
        if (now < cooldownUntil) {
            status.state = TagStatusState.LOGGED;
            return status;
        }

        // Close-on-idle: if window is open but we've had a large time gap since last sample
        if (windowOpen && lastSeenMs > 0 && (now - lastSeenMs) > idleGapCloseMs) {
            return closeWindowAndMarkHere(status);
        }

        // 1) If window closed -> look for open
        if (!windowOpen) {
            maybeOpenWindow(now, ema);
            if (windowOpen) {
                status.state = TagStatusState.APPROACHING;
            } else {
                // Not opened: remain in prior state (usually IDLE/APPROACHING)
                // Keep APPROACHING if we were there.
            }
        } else {
            // 2) Window open -> update peak & maybe close
            if (ema > peak) { peak = ema; tAtPeak = now; runCountAtOrAbove = 1; }
            else if (Math.abs(ema - peak) <= 0.9) { // within ~1 dB of peak counts too
                runCountAtOrAbove = Math.min(99, runCountAtOrAbove + 1);
            }

            final boolean trailingStop = (peak - ema) >= trailingDropDb && runCountAtOrAbove >= sustainSamples;
            final boolean exitRail     = ema <= exitRailDown;

            if (trailingStop || exitRail) {
                return closeWindowAndMarkHere(status);
            }
        }

        // If we previously closed and are awaiting a single LOGGED event, do it now.
        if (awaitingLog) {
            status.state = TagStatusState.LOGGED;
            awaitingLog = false;
            cooldownUntil = now + cooldownMs;
        }

        lastSeenMs = now;
        return status;
    }

    private void maybeOpenWindow(long now, double y) {
        // Purge old points from the rise buffer
        while (!riseBuf.isEmpty() && (now - riseBuf.peekFirst().t) > riseWindowMs) {
            riseBuf.pollFirst();
        }
        riseBuf.addLast(new Pt(now, y));

        // Check rise-in-window
        double minY = y;
        for (Pt p : riseBuf) minY = Math.min(minY, p.y);
        boolean openedByRise = (y - minY) >= riseDb;

        // Check simple threshold upward crossing
        boolean openedByRail = (!riseBuf.isEmpty() && crossUp(entryRailUp, y));

        if (openedByRise || openedByRail) {
            openWindow(now, y);
        }
    }

    private boolean crossUp(double rail, double y) {
        // "y crossed rail upward" means current smoothed is >= rail and we were below rail recently
        boolean wasBelow = false;
        for (Pt p : riseBuf) {
            if (p.y < rail) { wasBelow = true; break; }
        }
        return wasBelow && y >= rail;
    }

    private void openWindow(long now, double y) {
        windowOpen = true;
        windowOpenedAt = now;
        peak = y;
        tAtPeak = now;
        runCountAtOrAbove = 1;
    }

    private TagStatus closeWindowAndMarkHere(@NonNull TagStatus status) {
        windowOpen = false;
        awaitingLog = true; // mark LOGGED on the next sample processed
        // we mark HERE at the moment we decide the pass peaked (tAtPeak)
        status.state = TagStatusState.HERE;
        status.arrivedTimeMs = tAtPeak; // if your TagStatus doesn't have this field, remove this line
        return status;
    }

    @Override
    public String toString() {
        return "PeakWindowHandler{" +
                "alpha=" + alpha +
                ", riseDb=" + riseDb +
                ", cooldownMs=" + cooldownMs +
                ", sustainSamples=" + sustainSamples +
                '}';
    }

    @Override
    public String getName() {
        return this + "";
    }

}

