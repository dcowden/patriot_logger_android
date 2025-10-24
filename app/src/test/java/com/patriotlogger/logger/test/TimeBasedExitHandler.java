package com.patriotlogger.logger.test;

import com.patriotlogger.logger.data.CalibrationSample;
import com.patriotlogger.logger.data.TagStatus.TagStatusState;

public class TimeBasedExitHandler implements RssiHandler {
    private final float alpha;            // EMA alpha, e.g., 0.30f
    private final int hereThresholdDbm;   // e.g., -85
    private final long loggedDelayMs;     // e.g., 2000 ms

    // internal
    private boolean hasEma = false;
    private double ema = 0.0;
    private TagStatusState state = TagStatusState.TOO_FAR;
    private Long hereStartTsMs = null;

    public TimeBasedExitHandler(float alpha, int hereThresholdDbm, long loggedDelayMs) {
        this.alpha = alpha;
        this.hereThresholdDbm = hereThresholdDbm;
        this.loggedDelayMs = Math.max(0, loggedDelayMs);
    }

    @Override public void init() {
        hasEma = false;
        ema = 0.0;
        state = TagStatusState.TOO_FAR;
        hereStartTsMs = null;
    }

    @Override public String getName() {
        return "EMA+TimeExit(α=" + alpha + ", HERE@" + hereThresholdDbm + "dBm, +"
                + loggedDelayMs + "ms)";
    }

    @Override
    public TagStatusState acceptSample(CalibrationSample s) {
        // EMA update
        if (!hasEma) { ema = s.rssi; hasEma = true; }
        else { ema = alpha * s.rssi + (1 - alpha) * ema; }

        switch (state) {
            case TOO_FAR:
            case APPROACHING:
                if (ema >= hereThresholdDbm) {
                    state = TagStatusState.HERE;
                    hereStartTsMs = s.timestampMs;
                } else {
                    state = TagStatusState.APPROACHING;
                }
                break;

            case HERE:
                if (hereStartTsMs != null &&
                        (s.timestampMs - hereStartTsMs) >= loggedDelayMs) {
                    state = TagStatusState.LOGGED;
                }
                break;

            case LOGGED:
                // sticky
                break;
        }
        return state;
    }
}

