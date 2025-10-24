package com.patriotlogger.logger.data;

/**
 * Represents a single calibration RSSI sample.
 * This is separate from TagData so calibration can use
 * tagId directly without touching the persisted DB schema.
 */
public class CalibrationSample {
    public final int tagId;
    public  long timestampMs;
    public  int rssi;
    public  int smoothedRssi;

    public CalibrationSample(int tagId, long timestampMs, int rssi, int smoothedRssi) {
        this.tagId = tagId;
        this.timestampMs = timestampMs;
        this.rssi = rssi;
        this.smoothedRssi = smoothedRssi;
    }

    @Override
    public String toString() {
        return "CalibSample{" +
                "tagId=" + tagId +
                ", timestampMs=" + timestampMs +
                ", rssi=" + rssi + ", smoothedRssi=" + smoothedRssi +
                '}';
    }
}

