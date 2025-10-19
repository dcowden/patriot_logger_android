package com.patriotlogger.logger.logic;

/**
 * A simple data class to hold the results of a peak-finding calculation.
 */public class TagPeakData {

    /**
     * The timestamp (in milliseconds) of the calculated peak.
     */
    public final long peakTimeMs;

    /**
     * The smoothed RSSI value at the calculated peak.
     */
    public final float peakRssi;

    public TagPeakData(long peakTimeMs, float peakRssi) {
        this.peakTimeMs = peakTimeMs;
        this.peakRssi = peakRssi;
    }
}
