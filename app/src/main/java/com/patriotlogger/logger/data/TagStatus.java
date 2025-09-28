package com.patriotlogger.logger.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity
public class TagStatus {
    @PrimaryKey
    public int tagId; // integer from MAD

    public String friendlyName = "";
    public long entryTimeMs = 0L;
    public long peakTimeMs = 0L;
    public long exitTimeMs = 0L; // Represents the last time this tag was seen in processSample

    public float estimatedRssi = -200f;
    public float peakRssi = -200f;
    public float lowestRssi = 100f;
    public int sampleCount = 0;

    public String state = "approaching"; // approaching, here, logged

    // New fields to persist state previously in HashMaps
    public long lastSeenMs = 0L;        // Tracks the timestamp of the last sample processed for this tag.
                                        // This will be used by the sweepForLosses logic.
                                        // Corresponds to the old lastSeenMs HashMap.

    public int belowPeakCount = 0;      // Counter for consecutive samples below peak RSSI.
                                        // Corresponds to the old belowPeakCounts HashMap.

    // Constructor can be useful, though Room can handle direct field access
    public TagStatus() {}

    @NonNull
    @Override public String toString() {
        return "TagStatus{tagId=" + tagId +
               ", name='" + friendlyName + '\'' +
               ", state='" + state + '\'' +
               ", lastSeenMs=" + lastSeenMs +
               ", belowPeakCount=" + belowPeakCount +
               ", peakRssi=" + peakRssi +
               '}';
    }
}
