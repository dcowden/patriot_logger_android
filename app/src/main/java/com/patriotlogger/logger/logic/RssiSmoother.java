package com.patriotlogger.logger.logic;

public class RssiSmoother {

    private Float emaRssi = null;

    /**
     * Resets the smoother to its initial state.
     */
    public void reset() {
        this.emaRssi = null;
    }

    /**
     * Calculates the next Exponential Moving Average for a new RSSI value.
     *
     * @param rawRssi The latest raw RSSI value from the scanner.
     * @param alpha   The smoothing factor (alpha). A higher alpha means the new value has more weight.
     * @return The new smoothed RSSI value.
     */
    public float getNext(int rawRssi, float alpha) {
        // If this is the first value, the EMA is just the value itself.
        if (emaRssi == null) {
            emaRssi = (float) rawRssi;
        } else {
            // Apply the EMA formula.
            emaRssi = (alpha * rawRssi) + (1 - alpha) * emaRssi;
        }
        return emaRssi;
    }
}
