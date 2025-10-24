package com.patriotlogger.logger.logic;

import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus;
import com.patriotlogger.logger.data.TagStatus.TagStatusState;

import org.apache.commons.math3.stat.regression.SimpleRegression;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * TCA with gentle proximity ENTER and conservative exits.
 * - HERE entry: TCA predicts crossing within threshold OR (stable window, slope<0, distance <= 1.05*here).
 * - HERE exit: only via TCA receding (with an earlier "behind us" allowance) or force-deadline.
 * - Force-deadline: min(traverse, worst-case) — no tiny cap.
 */
public class TcaWithFallbackHandler implements RssiHandler {

    // --- config ---
    private final float  alpha;
    private final double txAt1mDbm;
    private final double pathExp;
    private final double hereMeters;
    private final double thresholdSec;
    private final int    windowSize;
    private final int    minPoints;
    private final double approachMeters;

    // Tight proximity enter band
    private static final double H_IN = 1.05;

    // Runner speed band
    private static final double V_MIN_MPS = 5000.0 / (26.0 * 60.0);
    private static final double V_MAX_MPS = 5000.0 / (16.0 * 60.0);

    // Early recede helpers
    private static final double LOG_BEHIND_EPS_SEC   = 0.5;  // if crossing is ≥0.5s behind, allow LOG
    private static final double HERE_MIN_DWELL_SEC   = 0.7;  // or after 0.7s dwell in HERE

    // --- state ---
    private boolean hasEma = false;
    private double ema = 0.0;

    private final Deque<Point> window = new ArrayDeque<>();

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

    public TcaWithFallbackHandler(float alpha,
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
        this.minPoints = Math.max(5, Math.min(this.windowSize - 2, minPoints));
        this.approachMeters = (approachMeters > 0) ? approachMeters : 15.0;
    }

    @Override
    public void init() {
        hasEma = false;
        ema = 0.0;
        window.clear();

        hereEnterMs = null;
        forceLogAtMs = null;
        bestRssi = null;
        bestRssiTsMs = null;
    }

    @Override
    public String getName() {
        return String.format(
                "TCA(α=%.2f,P1m=%.1f,n=%.2f,thr=%.1fm@%.1fs,win=%d,min=%d,approach≤%.1fm)",
                alpha, txAt1mDbm, pathExp, hereMeters, thresholdSec, windowSize, minPoints, approachMeters
        );
    }

    @Override
    public TagStatus acceptSample(TagStatus currentStatus,
                                  List<TagData> history,
                                  RssiData sample) {
        if (currentStatus == null || sample == null) return currentStatus;

        final long tMs = sample.timestampMs;
        final double tSecAbs = tMs / 1000.0;

        // 1) EMA RSSI
        final int rssiNow = sample.rssi;
        if (!hasEma) { ema = rssiNow; hasEma = true; }
        else         { ema = alpha * rssiNow + (1 - alpha) * ema; }

        // 2) RSSI -> distance
        final double dNow = rssiToDistance(ema);

        // 3) Maintain window
        window.addLast(new Point(tSecAbs, dNow));
        while (window.size() > windowSize) window.removeFirst();

        // Update lastSeen
        currentStatus.lastSeenMs = tMs;

        // Not enough points: bookkeeping and optional gentle ENTER (but never exit)
        if (window.size() < minPoints) {
            trackBestSinceHere(tMs, ema, currentStatus);
            if (checkForceDeadline(tMs, currentStatus)) {
                setLogged(currentStatus, tMs);
            }
            return currentStatus;
        }

        // 4) Regression
        final double tNow = tSecAbs;
        SimpleRegression reg = new SimpleRegression(true);
        int nAdded = 0;
        for (Point p : window) {
            double tRel = p.tSec - tNow; // <= 0
            double y    = Math.log10(p.d);
            if (Double.isFinite(tRel) && Double.isFinite(y)) { reg.addData(tRel, y); nAdded++; }
        }
        if (nAdded < minPoints || Double.isNaN(reg.getSlope())) {
            trackBestSinceHere(tMs, ema, currentStatus);
            if (checkForceDeadline(tMs, currentStatus)) {
                setLogged(currentStatus, tMs);
            }
            return currentStatus;
        }

        final double b = reg.getSlope();
        final double a = reg.getIntercept();
        Double tCross = null;
        if (Math.abs(b) > 1e-9) tCross = (Math.log10(hereMeters) - a) / b;

        // Velocity estimate
        double vEst = Math.abs(b) * Math.log(10.0) * dNow; // m/s
        double vClamped = clamp(vEst, V_MIN_MPS, V_MAX_MPS);

        boolean approachingSlope = b < -1e-6;
        boolean recedingSlope    = b >  1e-6;

        // 5) APPROACHING gating
        if (dNow <= approachMeters) {
            if (currentStatus.state == TagStatusState.TOO_FAR ||
                    currentStatus.state == TagStatusState.FIRST_SAMPLE) {
                currentStatus.state = TagStatusState.APPROACHING;
            }
        } else {
            if (currentStatus.state != TagStatusState.HERE &&
                    currentStatus.state != TagStatusState.LOGGED) {
                currentStatus.state = TagStatusState.TOO_FAR;
            }
        }

        // 6) FSM
        switch (currentStatus.state) {
            case FIRST_SAMPLE:
            case TOO_FAR:
            case APPROACHING: {
                boolean tcaSoon = (tCross != null && approachingSlope && tCross >= 0 && tCross <= thresholdSec);
                boolean proximityEnter = (approachingSlope && dNow <= hereMeters * H_IN);
                if (tcaSoon || proximityEnter) {
                    enterHere(currentStatus, tMs, vClamped);
                }
                break;
            }

            case HERE: {
                // Standard recede: crossing sufficiently behind us
                boolean tcaBehindEnough = (tCross != null && recedingSlope && tCross <= -thresholdSec);

                // Earlier recede allowance: if crossing is behind at all and we've dwelled a bit OR behind by ~0.5s
                long dwellMs = (hereEnterMs != null) ? (tMs - hereEnterMs) : 0L;
                boolean earlyBehind = (tCross != null && recedingSlope && tCross < 0.0
                        && (dwellMs >= (long)(HERE_MIN_DWELL_SEC * 1000.0) || (-tCross) >= LOG_BEHIND_EPS_SEC));

                if (tcaBehindEnough || earlyBehind) {
                    setLogged(currentStatus, tMs);
                    break;
                }

                // Track best + refresh deadline (no tiny cap)
                trackBestSinceHere(tMs, ema, currentStatus);
                refreshForceDeadline(vClamped);

                if (checkForceDeadline(tMs, currentStatus)) {
                    setLogged(currentStatus, tMs);
                }
                break;
            }

            case LOGGED:
            case TIMED_OUT:
                break;
        }

        return currentStatus;
    }

    // --- helpers ---

    private double rssiToDistance(double rssiDbm) {
        double num = (txAt1mDbm - rssiDbm) / (10.0 * pathExp);
        double d = Math.pow(10.0, num);
        if (!Double.isFinite(d)) return MAX_DIST;
        if (d < MIN_DIST) d = MIN_DIST;
        if (d > MAX_DIST) d = MAX_DIST;
        return d;
    }

    private void trackBestSinceHere(long tMs, double rssiSmoothed, TagStatus status) {
        if (hereEnterMs == null) return;
        if (bestRssi == null || rssiSmoothed > bestRssi) {
            bestRssi = rssiSmoothed;
            bestRssiTsMs = tMs;
        }
    }

    private boolean checkForceDeadline(long nowMs, TagStatus status) {
        return status.state == TagStatusState.HERE && forceLogAtMs != null && nowMs >= forceLogAtMs;
    }

    private void setLogged(TagStatus status, long tMs) {
        status.state = TagStatusState.LOGGED;
        status.exitTimeMs = tMs;
        if (bestRssiTsMs != null && status.peakTimeMs == 0L) {
            status.peakTimeMs = bestRssiTsMs;
        }
    }

    private void enterHere(TagStatus status, long tMs, double vClamped) {
        status.state = TagStatusState.HERE;
        hereEnterMs = tMs;
        if (status.arrivedTimeMs == 0L) status.arrivedTimeMs = tMs;
        bestRssi = ema;
        bestRssiTsMs = tMs;
        refreshForceDeadline(vClamped);
    }

    private void refreshForceDeadline(double vClamped) {
        if (hereEnterMs == null) return;
        long traverseMs  = (long) Math.ceil((2.0 * hereMeters / vClamped) * 1000.0);
        long worstCaseMs = (long) Math.ceil((2.0 * hereMeters / V_MIN_MPS) * 1000.0);
        forceLogAtMs = hereEnterMs + Math.min(traverseMs, worstCaseMs);
    }

    private static double clamp(double v, double lo, double hi) {
        if (!Double.isFinite(v)) return lo;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
