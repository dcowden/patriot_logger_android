package com.patriotlogger.logger.test;

import com.patriotlogger.logger.data.CalibrationSample;
import com.patriotlogger.logger.data.TagStatus.TagStatusState;
import org.apache.commons.math3.stat.regression.SimpleRegression;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * TCA: Time-to-Crossing (distance threshold) Handler.
 * - EMA smooth RSSI
 * - Map RSSI -> distance (meters)
 * - Regress log10(distance) vs time (relative to NOW) over a causal sliding window
 * - Predict time to cross distance threshold (e.g., 6m) instead of 0m
 * - Trigger HERE/LOGGED from the predicted crossing time with approach/recede checks
 */
public class TcaHandler implements RssiHandler {
    // --- config ---
    private final float alpha;          // EMA on RSSI (0<alpha<=1)
    private final double txAt1mDbm;     // calibrated TX power at 1m (dBm), e.g., -70
    private final double pathExp;       // path-loss exponent n (e.g., 2.0)
    private final double hereMeters;    // distance threshold for HERE/LOGGED, e.g., 6.0
    private final double thresholdSec;  // time margin (e.g., 1.5–2.5 s)
    private final int windowSize;       // max samples in causal window (e.g., 40)
    private final int minPoints;        // minimum points to attempt a fit (e.g., 15)

    // --- state ---
    private boolean hasEma = false;
    private double ema = 0.0;
    private final Deque<Point> window = new ArrayDeque<>();
    private TagStatusState state = TagStatusState.TOO_FAR;

    private static final double MIN_DIST = 0.30;  // clamp to avoid log(0)
    private static final double MAX_DIST = 80.0;  // keep outliers bounded

    private static class Point {
        final double tSec; // absolute seconds (monotonic)
        final double d;    // distance (meters)
        Point(double tSec, double d) { this.tSec = tSec; this.d = d; }
    }

    public TcaHandler(float alpha,
                      double txAt1mDbm,
                      double pathExp,
                      double hereMeters,
                      double thresholdSec,
                      int windowSize,
                      int minPoints) {
        this.alpha = alpha;
        this.txAt1mDbm = txAt1mDbm;
        this.pathExp = pathExp <= 0 ? 2.0 : pathExp;
        this.hereMeters = hereMeters;
        this.thresholdSec = Math.max(0.3, thresholdSec);
        this.windowSize = Math.max(6, windowSize);
        this.minPoints = Math.max(8, Math.min(this.windowSize - 2, minPoints));
    }

    @Override public void init() {
        hasEma = false;
        ema = 0.0;
        window.clear();
        state = TagStatusState.TOO_FAR;
    }

    @Override public String getName() {
        return String.format("TCA(α=%.2f, P1m=%.1f, n=%.2f, thr=%.1fm @ %.2fs, win=%d)",
                alpha, txAt1mDbm, pathExp, hereMeters, thresholdSec, windowSize);
    }

    private double rssiToDistance(double rssiDbm) {
        // d = 10^((P1m - RSSI)/(10*n))
        double num = (txAt1mDbm - rssiDbm) / (10.0 * pathExp);
        double d = Math.pow(10.0, num);
        if (Double.isNaN(d) || Double.isInfinite(d)) return MAX_DIST;
        // clamp for stability
        if (d < MIN_DIST) d = MIN_DIST;
        if (d > MAX_DIST) d = MAX_DIST;
        return d;
    }

    @Override
    public TagStatusState acceptSample(CalibrationSample s) {
        final double tSecAbs = s.timestampMs / 1000.0;

        // 1) EMA smoothing on RSSI (causal)
        if (!hasEma) { ema = s.rssi; hasEma = true; }
        else { ema = alpha * s.rssi + (1 - alpha) * ema; }

        // 2) RSSI -> distance
        final double dNow = rssiToDistance(ema);

        // 3) Maintain causal window (by count; all points are <= "now" by construction)
        window.addLast(new Point(tSecAbs, dNow));
        while (window.size() > windowSize) window.removeFirst();

        // 4) Require enough points to fit
        if (window.size() < minPoints) {
            if (state == TagStatusState.TOO_FAR) state = TagStatusState.APPROACHING;
            return state;
        }

        // 5) Build regression on (tRel, log10(d)), with tRel= t_i - t_now  (so NOW==0)
        final double tNow = tSecAbs;
        SimpleRegression reg = new SimpleRegression(true);
        int nAdded = 0;
        for (Point p : window) {
            double tRel = p.tSec - tNow;          // tRel <= 0 (past)
            double y = Math.log10(p.d);           // linear in t for reciprocal speed in log-space
            if (Double.isFinite(tRel) && Double.isFinite(y)) {
                reg.addData(tRel, y);
                nAdded++;
            }
        }
        if (nAdded < minPoints || Double.isNaN(reg.getSlope())) {
            if (state == TagStatusState.TOO_FAR) state = TagStatusState.APPROACHING;
            return state;
        }

        // 6) Extract line y = a + b t  (y=log10 d, t=tRel in seconds, with t=0 at NOW)
        double b = reg.getSlope();
        double a = reg.getIntercept();

        // 7) Predict time when distance crosses hereMeters
        // log10(hereMeters) = a + b * t6  =>  t6 = (log10(hereMeters) - a)/b
        Double t6 = null;
        if (Math.abs(b) > 1e-9) {
            t6 = (Math.log10(hereMeters) - a) / b;   // seconds relative to NOW
        }

        // 8) State machine using approach/recede + time-to-crossing rules
        //    approaching if slope b < 0 (distance decreasing in log space)
        boolean approaching = b < -1e-6;
        boolean receding    = b >  1e-6;

        switch (state) {
            case TOO_FAR:
            case APPROACHING:
                if (t6 != null && approaching && t6 >= 0 && t6 <= thresholdSec) {
                    state = TagStatusState.HERE;
                } else {
                    state = TagStatusState.APPROACHING;
                }
                break;

            case HERE:
                // We’ve passed the threshold if t6 is in the past by margin and we’re receding.
                if (t6 != null && receding && t6 <= -thresholdSec) {
                    state = TagStatusState.LOGGED;
                }
                break;

            case LOGGED:
                // sticky (optionally drop to TOO_FAR after long time)
                break;
        }

        return state;
    }
}
