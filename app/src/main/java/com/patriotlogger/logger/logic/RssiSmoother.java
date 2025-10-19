package com.patriotlogger.logger.logic;

import androidx.annotation.NonNull;
import com.patriotlogger.logger.data.Setting;

/**
 * A utility class to apply an Exponential Moving Average (EMA) to a stream of RSSI values.
 * This class is stateful and must be used for a single stream of data (e.g., for one tag).
 */
public class RssiSmoother {

    private float currentEma = 0.0f;
    private float lastRssi = 0.0f;


    public static float computeSmoothedRssi(int rawRssi, float previousSmoothedRssi, float alpha) {
        if (previousSmoothedRssi == 0.0f) {
            return (float) rawRssi;
        }
        return (alpha * rawRssi)  + ((1 - alpha) * previousSmoothedRssi);
    }

    public float getSmoothedRssi(int rawRssi, @NonNull Setting settings) {
        // If this is the first value, the EMA is just the value itself.
        if (currentEma == 0.0f) {
            currentEma = (float) rawRssi;
        } else {
            // Apply the EMA formula.
            currentEma = computeSmoothedRssi(rawRssi,currentEma,settings.rssi_averaging_alpha);
        }
        lastRssi = currentEma;
        return currentEma;
    }
    public float getLastRssi() {
        return lastRssi;
    }
    /**
     * Resets the smoother's state. Call this when starting a new stream.
     */
    public void reset() {
        currentEma = 0.0f;
    }
}
