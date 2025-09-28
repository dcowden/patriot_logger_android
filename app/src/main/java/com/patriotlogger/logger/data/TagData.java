package com.patriotlogger.logger.data;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "tag_data",
        foreignKeys = @ForeignKey(entity = TagStatus.class,
                                   parentColumns = "trackId",
                                   childColumns = "trackId",
                                   onDelete = ForeignKey.CASCADE), // If a TagStatus is deleted, its samples are also deleted
        indices = {@Index(value = {"trackId", "timestampMs"})}
)
public class TagData {

    @PrimaryKey(autoGenerate = true)
    public int dataId;

    public int trackId; // Foreign key to TagStatus.trackId

    public long timestampMs; // Timestamp of the sample
    public int rssi;         // RSSI value of the sample

    public TagData(int trackId, long timestampMs, int rssi) {
        this.trackId = trackId;
        this.timestampMs = timestampMs;
        this.rssi = rssi;
    }
}
