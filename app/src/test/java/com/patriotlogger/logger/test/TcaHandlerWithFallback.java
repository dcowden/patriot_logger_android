package com.patriotlogger.logger.test;

import com.patriotlogger.logger.data.CalibrationSample;
import com.patriotlogger.logger.data.TagStatus.TagStatusState;
import org.apache.commons.math3.stat.regression.SimpleRegression;

import java.util.ArrayDeque;
import java.util.Deque;

public class TcaHandlerWithFallback implements RssiHandler {
    // --- config ---
    private final float  alpha;           // EMA on RSSI (0<alpha<=1)
    private final double txAt1mDbm;       // TX power at 1m (dBm)
    private final double pathExp;         // path-loss exponent
    private final double hereMeters;      // distance threshold for HERE/LOGGED
    private final double thresholdSec;    // time margin for TCA decisions
    private final int    windowSize;      // sliding window size
    private final int    minPoints;       // min points for regression
    private final double approachMeters;  // distance to call APPROACHING (e.g., 50m)

    // Runner speed band from 5k in 16..32 min (unchanged)
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

    private static final double MIN_DIST = 0.30;
    private static final double MAX_DIST = 80.0;

    private static class Point {
        final double tSec;
        final double d;
        Point(double tSec, double d) { this.tSec = tSec; this.d = d; }
    }

    public TcaHandlerWithFallback(float alpha,
                                  double txAt1mDbm,
                                  double pathExp,
                                  double hereMeters,
                                  double thresholdSec,
                                  int windowSize,
                                  int minPoints,
                                  double approachMeters) {
        this.alpha = alpha;
        this.txAt1mDbm = txAt1mDbm;
        this.pathExp = pathExp <= 0 ? 2.0 : pathExp;
        this.hereMeters = hereMeters;
        this.thresholdSec = Math.max(0.3, thresholdSec);
        this.windowSize = Math.max(6, windowSize);
        this.minPoints = Math.max(8, Math.min(this.windowSize - 2, minPoints));
        this.approachMeters = (approachMeters > 0) ? approachMeters : 50.0; // default 50 m
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
    }

    @Override public String getName() {
        return String.format(
                "TCA(α=%.2f, P1m=%.1f, n=%.2f, thr=%.1fm @ %.2fs, win=%d)+Force(v∈[%.3f,%.3f]m/s, approach≤%.1fm)",
                alpha, txAt1mDbm, pathExp, hereMeters, thresholdSec, windowSize,
                V_MIN_MPS, V_MAX_MPS, approachMeters
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

        // 1) EMA smoothing on RSSI (causal)
        if (!hasEma) { ema = s.rssi; hasEma = true; }
        else { ema = alpha * s.rssi + (1 - alpha) * ema; }

        // 2) RSSI -> distance (using EMA)
        final double dNow = rssiToDistance(ema);

        // 3) Maintain causal window
        window.addLast(new Point(tSecAbs, dNow));
        while (window.size() > windowSize) window.removeFirst();

        // 4) If not enough points, DO NOT jump to APPROACHING anymore.
        //    Stay TOO_FAR until we can run a regression.
        if (window.size() < minPoints) {
            // track peak for later (if we slip into HERE later)
            trackBestSinceHere(tMs, ema);
            checkForceDeadline(tMs);
            return state; // remains whatever it was (initially TOO_FAR)
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
            // still don't promote to APPROACHING
            trackBestSinceHere(tMs, ema);
            checkForceDeadline(tMs);
            return state;
        }

        // 6) Extract y = a + b t  (y=log10 d)
        double b = reg.getSlope();
        double a = reg.getIntercept();

        // 7) Crossing prediction for HERE
        Double t6 = null;
        if (Math.abs(b) > 1e-9) t6 = (Math.log10(hereMeters) - a) / b;

        // Velocity estimate at NOW: dd/dt = b*ln(10)*d
        double vEst = Math.abs(b) * Math.log(10.0) * dNow; // m/s
        double vClamped = clamp(vEst, V_MIN_MPS, V_MAX_MPS);

        boolean approachingSlope = b < -1e-6;
        boolean recedingSlope    = b >  1e-6;

        // --- NEW: gate APPROACHING on (enough points) AND (distance <= approachMeters) ---
        if (dNow <= approachMeters) {
            // We have enough points (we’re here), and the runner is within the approach distance
            if (state == TagStatusState.TOO_FAR) state = TagStatusState.APPROACHING;
        } else {
            // Outside approach range -> treat as TOO_FAR
            if (state != TagStatusState.HERE && state != TagStatusState.LOGGED) {
                state = TagStatusState.TOO_FAR;
            }
        }

        switch (state) {
            case TOO_FAR:
            case APPROACHING:
                // HERE decision still relies on TCA crossing window
                if (t6 != null && approachingSlope && t6 >= 0 && t6 <= thresholdSec) {
                    state = TagStatusState.HERE;
                    hereEnterMs = tMs;
                    bestRssi = ema; bestRssiTsMs = tMs;

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

    // --- helpers ---

    private void trackBestSinceHere(long tMs, double rssiSmoothed) {
        if (hereEnterMs == null) return;
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
