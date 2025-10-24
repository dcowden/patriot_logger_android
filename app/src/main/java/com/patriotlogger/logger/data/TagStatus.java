package com.patriotlogger.logger.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.room.TypeConverters;

@Entity(
        tableName = "tag_status",
        indices = {@Index(value = {"tagId", "state"})}
)
@TypeConverters(TagStatusStateConverter.class)
public class TagStatus {

    public enum TagStatusState {
        FIRST_SAMPLE,
        TOO_FAR,
        APPROACHING,
        HERE,
        LOGGED,
        TIMED_OUT
    }

    @PrimaryKey(autoGenerate = true)
    public int trackId;

    public int tagId;

    // Retain user-facing bits & lifecycle timestamps
    public String friendlyName = "";
    public long entryTimeMs = 0L;
    public long arrivedTimeMs = 0L;
    public long peakTimeMs = 0L;
    public long exitTimeMs = 0L;

    @NonNull
    public TagStatusState state = TagStatusState.FIRST_SAMPLE;

    public long lastSeenMs = 0L;

    public TagStatus() {}

    @Override
    public String toString() {
        return "TagStatus{" +
                "trackId=" + trackId +
                ", tagId=" + tagId +
                ", friendlyName='" + friendlyName + '\'' +
                ", entryTimeMs=" + entryTimeMs +
                ", arrivedTimeMs=" + arrivedTimeMs +
                ", peakTimeMs=" + peakTimeMs +
                ", exitTimeMs=" + exitTimeMs +
                ", state=" + state +
                ", lastSeenMs=" + lastSeenMs +
                '}';
    }
}
