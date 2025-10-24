package com.patriotlogger.logger.test;

import com.patriotlogger.logger.data.CalibrationSample;
import com.patriotlogger.logger.data.TagStatus.TagStatusState;
import org.apache.commons.math3.stat.regression.SimpleRegression;

import java.util.ArrayDeque;
import java.util.Deque;

public class TcaHandlerWithFallback2 implements RssiHandler {
    // --- config ---
    private final float  alpha;           // EMA on RSSI (0<alpha<=1)
    private final double txAt1mDbm;       // TX power at 1m (dBm)
    private final double pathExp;         // path-loss exponent
    private final double hereMeters;      // distance threshold for HERE/LOGGED
    private final double thresholdSec;    // time margin for TCA decisions
    private final int    windowSize;      // sliding window size
    private final int    minPoints;       // min points for regression
    private final double approachMeters;  // distance to call APPROACHING (e.g., 50m)

    // NEW: proximity slack for HERE (e.g., allow 15% model error)
    private static final double EPS_HERE = 0.15;

    // NEW: radio-silence gap to force LOGGED if no packets arrive (ms)
    private final long silenceGapMs;

    // Runner speed band (unchanged)
    private static final double V_MIN_MPS = 5000.0 / (26.0 * 60.0);
    private static final double V_MAX_MPS = 5000.0 / (16.0 * 60.0);

    // --- state ---
    private boolean hasEma = false;
    private double ema = 0.0;
    private final Deque<Point> window = new ArrayDeque<>();
    private TagStatusState state = TagStatusState.TOO_FAR;

    // Peak/force-log bookkeeping
    private Long   hereEnterMs = null;
    private Long   forceLogAtMs = null;
    private Double bestRssi = null;
    private Long   bestRssiTsMs = null;

    // NEW: last sample time (for silence detection)
    private Long   lastSampleMs = null;

    private static final double MIN_DIST = 0.30;
    private static final double MAX_DIST = 80.0;

    private static class Point {
        final double tSec;
        final double d;
        Point(double tSec, double d) { this.tSec = tSec; this.d = d; }
    }

    public TcaHandlerWithFallback2(float alpha,
                                  double txAt1mDbm,
                                  double pathExp,
                                  double hereMeters,
                                  double thresholdSec,
                                  int windowSize,
                                  int minPoints,
                                  double approachMeters) {
        this(alpha, txAt1mDbm, pathExp, hereMeters, thresholdSec, windowSize, minPoints, approachMeters, 2000L);
    }

    // NEW: overload with silenceGapMs (ms). Pass <=0 to use default 2000ms.
    public TcaHandlerWithFallback2(float alpha,
                                  double txAt1mDbm,
                                  double pathExp,
                                  double hereMeters,
                                  double thresholdSec,
                                  int windowSize,
                                  int minPoints,
                                  double approachMeters,
                                  long silenceGapMs) {
        this.alpha = alpha;
        this.txAt1mDbm = txAt1mDbm;
        this.pathExp = pathExp <= 0 ? 2.0 : pathExp;
        this.hereMeters = hereMeters;
        this.thresholdSec = Math.max(0.3, thresholdSec);
        this.windowSize = Math.max(6, windowSize);
        this.minPoints = Math.max(8, Math.min(this.windowSize - 2, minPoints));
        this.approachMeters = (approachMeters > 0) ? approachMeters : 50.0; // default 50 m
        this.silenceGapMs = (silenceGapMs > 0) ? silenceGapMs : 2000L;
    }

    @Override public void init() {
        hasEma = false;
        ema = 0.0;
        window.clear();
        state = TagStatusState.TOO_FAR;

        hereEnterMs = null;
        forceLogAtMs = null;
        bestRssi = null;
        bestRssiTsMs = null;
        lastSampleMs = null;
    }

    @Override public String getName() {
        return String.format(
                "TCA(α=%.2f, P1m=%.1f, n=%.2f, thr=%.1fm @ %.2fs, win=%d)"
                        + "+Force(v∈[%.3f,%.3f]m/s, approach≤%.1fm, ε=%.0f%%, silence=%dms)",
                alpha, txAt1mDbm, pathExp, hereMeters, thresholdSec, windowSize,
                V_MIN_MPS, V_MAX_MPS, approachMeters, EPS_HERE*100.0, silenceGapMs
        );
    }

    private double rssiToDistance(double rssiDbm) {
        double num = (txAt1mDbm - rssiDbm) / (10.0 * pathExp);
        double d = Math.pow(10.0, num);
        if (!Double.isFinite(d)) return MAX_DIST;
        if (d < MIN_DIST) d = MIN_DIST;
        if (d > MAX_DIST) d = MAX_DIST;
        return d;
    }

    @Override
    public TagStatusState acceptSample(CalibrationSample s) {
        final long   tMs    = s.timestampMs;
        final double tSecAbs= tMs / 1000.0;
        lastSampleMs = tMs;

        // 1) EMA smoothing on RSSI (causal)
        if (!hasEma) { ema = s.rssi; hasEma = true; }
        else { ema = alpha * s.rssi + (1 - alpha) * ema; }

        // 2) RSSI -> distance (using EMA)
        final double dNow = rssiToDistance(ema);

        // 3) Maintain causal window
        window.addLast(new Point(tSecAbs, dNow));
        while (window.size() > windowSize) window.removeFirst();

        // 4) If not enough points, stay as-is; still enforce deadline if already HERE
        if (window.size() < minPoints) {
            trackBestSinceHere(tMs, ema);
            // Even on sparse data, allow forced flip if deadline already passed
            if (checkForceDeadline(tMs)) state = TagStatusState.LOGGED;
            return state;
        }

        // 5) Regression on (tRel, log10 d)
        final double tNow = tSecAbs;
        SimpleRegression reg = new SimpleRegression(true);
        int nAdded = 0;
        for (Point p : window) {
            double tRel = p.tSec - tNow; // <= 0
            double y    = Math.log10(p.d);
            if (Double.isFinite(tRel) && Double.isFinite(y)) { reg.addData(tRel, y); nAdded++; }
        }
        if (nAdded < minPoints || Double.isNaN(reg.getSlope())) {
            trackBestSinceHere(tMs, ema);
            if (checkForceDeadline(tMs)) state = TagStatusState.LOGGED;
            return state;
        }

        // 6) Extract y = a + b t  (y=log10 d)
        double b = reg.getSlope();
        double a = reg.getIntercept();

        // 7) Crossing prediction for HERE
        Double t6 = null;
        if (Math.abs(b) > 1e-9) t6 = (Math.log10(hereMeters) - a) / b;

        // Speed estimate and clamp
        final double dNowMeters = dNow;
        double vEst = Math.abs(b) * Math.log(10.0) * dNowMeters; // m/s
        double vClamped = clamp(vEst, V_MIN_MPS, V_MAX_MPS);

        boolean approachingSlope = b < -1e-6;
        boolean recedingSlope    = b >  1e-6;

        // Distance gate for APPROACHING
        if (dNow <= approachMeters) {
            if (state == TagStatusState.TOO_FAR) state = TagStatusState.APPROACHING;
        } else {
            if (state != TagStatusState.HERE && state != TagStatusState.LOGGED) {
                state = TagStatusState.TOO_FAR;
            }
        }

        switch (state) {
            case TOO_FAR:
            case APPROACHING:
                // --- CHANGE #1: HERE requires BOTH prediction AND proximity ---
                boolean withinHere = dNow <= (hereMeters * (1.0 + EPS_HERE));
                if (withinHere && t6 != null && approachingSlope && t6 >= 0 && t6 <= thresholdSec) {
                    state = TagStatusState.HERE;
                    hereEnterMs = tMs;

                    // DO NOT reset best peak to zero; seed with current best if any
                    if (bestRssi == null || ema > bestRssi) {
                        bestRssi = ema; bestRssiTsMs = tMs;
                    }

                    long totalMs = (long)Math.ceil((2.0 * hereMeters / vClamped) * 1000.0);
                    long worstCaseMs = (long)Math.ceil((2.0 * hereMeters / V_MIN_MPS) * 1000.0);
                    forceLogAtMs = hereEnterMs + Math.min(totalMs, worstCaseMs);
                }
                break;

            case HERE:
                // Original TCA recede rule
                if (t6 != null && recedingSlope && t6 <= -thresholdSec) {
                    state = TagStatusState.LOGGED;
                    break;
                }

                // Track best and refresh force deadline with current vClamped
                trackBestSinceHere(tMs, ema);
                if (hereEnterMs != null) {
                    long totalMs = (long)Math.ceil((2.0 * hereMeters / vClamped) * 1000.0);
                    long worstCaseMs = (long)Math.ceil((2.0 * hereMeters / V_MIN_MPS) * 1000.0);
                    forceLogAtMs = hereEnterMs + Math.min(totalMs, worstCaseMs);
                }

                // Allow force without waiting for another packet
                if (checkForceDeadline(tMs)) {
                    state = TagStatusState.LOGGED;
                }
                break;

            case LOGGED:
                // sticky
                break;
        }

        return state;
    }

    // --- NEW: call this when no packets arrive (timer/idle callback) ---
    /** Enforce forced LOGGED on deadline or radio-silence even without new samples. */
    public TagStatusState onIdle(long nowMs) {
        if (state == TagStatusState.HERE) {
            boolean hitDeadline = checkForceDeadline(nowMs);
            boolean hitSilence  = (lastSampleMs != null) && (nowMs - lastSampleMs >= silenceGapMs);
            if (hitDeadline || hitSilence) {
                state = TagStatusState.LOGGED;
            }
        }
        return state;
    }

    // --- helpers ---

    private void trackBestSinceHere(long tMs, double rssiSmoothed) {
        if (hereEnterMs == null) return; // only track once HERE has begun
        if (bestRssi == null || rssiSmoothed > bestRssi) {
            bestRssi = rssiSmoothed;
            bestRssiTsMs = tMs;
        }
    }

    private boolean checkForceDeadline(long nowMs) {
        return state == TagStatusState.HERE && forceLogAtMs != null && nowMs >= forceLogAtMs;
    }

    private static double clamp(double v, double lo, double hi) {
        if (!Double.isFinite(v)) return lo;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
