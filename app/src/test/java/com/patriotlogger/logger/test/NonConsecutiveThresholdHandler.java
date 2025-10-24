package com.patriotlogger.logger.test;

import com.patriotlogger.logger.data.CalibrationSample;
import com.patriotlogger.logger.data.TagStatus.TagStatusState;

public class NonConsecutiveThresholdHandler implements RssiHandler {
    private final int hereThresholdDbm;   // e.g., -85
    private final int samplesNeeded;      // N samples above (enter) / below (exit)
    private int aboveCount;
    private int belowCount;
    private TagStatusState state = TagStatusState.TOO_FAR;

    public NonConsecutiveThresholdHandler(int hereThresholdDbm, int samplesNeeded) {
        this.hereThresholdDbm = hereThresholdDbm;
        this.samplesNeeded = Math.max(1, samplesNeeded);
    }

    @Override public void init() {
        aboveCount = 0;
        belowCount = 0;
        state = TagStatusState.TOO_FAR;
    }

    @Override public String getName() {
        return "NonConsec≥" + samplesNeeded + " @" + hereThresholdDbm + "dBm";
    }

    @Override
    public TagStatusState acceptSample(CalibrationSample s) {
        final int rssi = s.rssi;

        // track counts (non-consecutive)
        if (rssi >= hereThresholdDbm) {
            aboveCount++;
        }
        if (rssi < hereThresholdDbm) {
            belowCount++;
        }

        switch (state) {
            case TOO_FAR:
            case APPROACHING:
                if (aboveCount >= samplesNeeded) {
                    state = TagStatusState.HERE;
                    // reset the opposite counter on transition
                    belowCount = 0;
                } else {
                    state = TagStatusState.APPROACHING;
                }
                break;

            case HERE:
                if (belowCount >= samplesNeeded) {
                    state = TagStatusState.TOO_FAR; // or fall back to APPROACHING if you prefer
                    // reset on exit
                    aboveCount = 0;
                }
                break;

            case LOGGED:
                // no-op, you can keep LOGGED sticky if your FSM expects that
                break;
        }
        return state;
    }
}
