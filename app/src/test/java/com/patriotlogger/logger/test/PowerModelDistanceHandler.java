package com.patriotlogger.logger.test;

import com.patriotlogger.logger.data.CalibrationSample;
import com.patriotlogger.logger.data.TagStatus.TagStatusState;

public class PowerModelDistanceHandler implements RssiHandler {
    private final float alpha;         // EMA alpha for RSSI
    private final double txAt1mDbm;    // calibrated Tx power at 1m (dBm), e.g., -59
    private final double pathExp;      // path loss exponent (2.0 free-space default)
    private final double hereMeters;   // threshold distance, e.g., 6.0
    private final int triggerCount;    // non-consecutive samples needed

    private boolean hasEma = false;
    private double ema = 0.0;
    private int insideCount = 0;
    private int outsideCount = 0;
    private TagStatusState state = TagStatusState.TOO_FAR;

    public PowerModelDistanceHandler(float alpha,
                                     double txAt1mDbm,
                                     double pathExp,
                                     double hereMeters,
                                     int triggerCount) {
        this.alpha = alpha;
        this.txAt1mDbm = txAt1mDbm;
        this.pathExp = pathExp <= 0 ? 2.0 : pathExp;
        this.hereMeters = hereMeters;
        this.triggerCount = Math.max(1, triggerCount);
    }

    @Override public void init() {
        hasEma = false;
        ema = 0.0;
        insideCount = 0;
        outsideCount = 0;
        state = TagStatusState.TOO_FAR;
    }

    @Override public String getName() {
        return String.format("PowerDist(α=%.2f, P1m=%.1fdBm, n=%.2f, thr=%.1fm, N=%d)",
                alpha, txAt1mDbm, pathExp, hereMeters, triggerCount);
    }

    private double rssiToDistance(double rssiDbm) {
        // d = 10^((P1m - RSSI)/(10*n))
        double num = (txAt1mDbm - rssiDbm) / (10.0 * pathExp);

        double d =  Math.pow(10.0, num);
        System.out.println("rssiToDistance: " + rssiDbm + " -> " + d + " m");
        return d;
    }

    @Override
    public TagStatusState acceptSample(CalibrationSample s) {
        if (!hasEma) { ema = s.rssi; hasEma = true; }
        else { ema = alpha * s.rssi + (1 - alpha) * ema; }

        double d = rssiToDistance(ema);

        if (d <= hereMeters) insideCount++;
        if (d > hereMeters)  outsideCount++;

        switch (state) {
            case TOO_FAR:
            case APPROACHING:
                if (insideCount >= triggerCount) {
                    state = TagStatusState.HERE;
                    outsideCount = 0;
                } else {
                    state = TagStatusState.APPROACHING;
                }
                break;

            case HERE:
                if (outsideCount >= triggerCount) {
                    state = TagStatusState.TOO_FAR; // or drop to APPROACHING if preferred
                    insideCount = 0;
                }
                break;

            case LOGGED:
                // sticky
                break;
        }
        return state;
    }
}
