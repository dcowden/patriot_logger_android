package com.patriotlogger.logger.logic;

/**
 * Represents a single calibration RSSI sample.
 * This is separate from TagData so calibration can use
 * tagId directly without touching the persisted DB schema.
 */
public class RssiData {
    public final int tagId;
    public long timestampMs;
    public int rssi;
    public int smoothedRssi;

    public RssiData(int tagId, long timestampMs, int rssi, int smoothedRssi) {
        this.tagId = tagId;
        this.timestampMs = timestampMs;
        this.rssi = rssi;
        this.smoothedRssi = smoothedRssi;
    }

    @Override
    public String toString() {
        return "RssiData{" +
                "tagId=" + tagId +
                ", timestampMs=" + timestampMs +
                ", rssi=" + rssi + ", smoothedRssi=" + smoothedRssi +
                '}';
    }
}
