package com.patriotlogger.logger.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.room.TypeConverters;

@Entity(tableName = "tag_status",
        indices = {@Index(value = {"tagId", "state"})})
@TypeConverters(TagStatusStateConverter.class) // Assuming StateConverter will be renamed
public class TagStatus {

    /**
     * Represents the processing state of a BLE tag during a specific pass.
     */
    public enum TagStatusState {
        FIRST_SAMPLE, // First sample received for this pass
        APPROACHING, // Tag is detected and moving towards the peak signal strength point.
        HERE,        // Tag is at or very near the peak signal strength point.
        LOGGED       // Tag has passed the peak point and is no longer actively tracked for this pass, or signal lost.
    }

    @PrimaryKey(autoGenerate = true)
    public int trackId; // New primary key for each pass

    public int tagId; // Original tag identifier from the BLE device

    public String friendlyName = "";
    public long entryTimeMs = 0L; // Timestamp of the first sample for this pass
    public long peakTimeMs = 0L;  // Timestamp of the peak RSSI sample, to be calculated when pass is logged
    public long exitTimeMs = 0L;  // Timestamp of the last sample for this pass

    public float highestRssi = -500f; // Lowest raw RSSI seen during this pass

    @NonNull
    public TagStatusState state = TagStatusState.FIRST_SAMPLE;

    public long lastSeenMs = 0L; // Timestamp of the last sample processed for this specific trackId

    public TagStatus() {}

    @Ignore
    public TagStatus(int tagId, String friendlyName, long entryTimeMs, TagStatusState initialState) {
        this.tagId = tagId;
        this.friendlyName = friendlyName;
        this.entryTimeMs = entryTimeMs;
        this.lastSeenMs = entryTimeMs;
        this.state = initialState;
        this.highestRssi = -500f; // Initialize to a high value
        // peakTimeMs will be set upon logging the pass
    }

    public boolean isInProcess() {
        return this.state != TagStatusState.LOGGED;
    }

    @NonNull
    @Override
    public String toString() {
        return "TagStatus{" +
               "trackId=" + trackId +
               ", tagId=" + tagId +
               ", friendlyName='" + friendlyName + '\'' +
               ", state=" + state +
               ", entryTimeMs=" + entryTimeMs +
               ", peakTimeMs=" + peakTimeMs +
               ", exitTimeMs=" + exitTimeMs +
               ", lastSeenMs=" + lastSeenMs +
               ", lowestRssi=" + highestRssi +
               '}';
    }
}
