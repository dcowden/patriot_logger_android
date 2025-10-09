package com.patriotlogger.logger.data;

/**
 * A simple data class to hold the essential information from a raw BLE scan result.
 * Used to pass data for calibration without involving the database.
 */
public class RawScanResult {
    public final int tagId;
    public final int rssi;
    public final long timestampMs;

    public RawScanResult(int tagId, int rssi, long timestampMs) {
        this.tagId = tagId;
        this.rssi = rssi;
        this.timestampMs = timestampMs;
    }
}
