package com.patriotlogger.logger.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.room.TypeConverters;

@Entity(tableName = "tag_status",
        indices = {@Index(value = {"tagId", "state"})})
@TypeConverters(TagStatusStateConverter.class)
public class TagStatus {

    public enum TagStatusState {
        FIRST_SAMPLE,
        TOO_FAR,
        APPROACHING, 
        HERE,        
        LOGGED   ,
        TIMED_OUT
    }

    @PrimaryKey(autoGenerate = true)
    public int trackId;

    public int tagId;


    /**
     * Stores the previous EMA RSSI value to check for an increasing trend.
     */
    public float previousEmaRssi = 0.0f;

    /**
     * Counts consecutive samples where the EMA RSSI is increasing.
     * Reset whenever the trend breaks or a state change occurs.
     */
    public int consecutiveRssiIncreases = 0;

    public String friendlyName = "";
    public long entryTimeMs = 0L;
    public long peakTimeMs = 0L;
    public long exitTimeMs = 0L;

    public float peakRssi = -500f;
    public float emaRssi = 0.0f;

    @NonNull
    public TagStatusState state = TagStatusState.FIRST_SAMPLE;

    public long lastSeenMs = 0L;

    public TagStatus() {}

    @Ignore
    public TagStatus(int tagId, String friendlyName, long entryTimeMs, TagStatusState initialState) {
        this.tagId = tagId;
        this.friendlyName = friendlyName;
        this.entryTimeMs = entryTimeMs;
        this.lastSeenMs = entryTimeMs;
        this.state = initialState;
        this.peakRssi = -500f;
        this.emaRssi = 0.0f;
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
               ", emaRssi=" + emaRssi +
               ", state=" + state +
               '}';
    }
}
