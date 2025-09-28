package com.patriotlogger.logger.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity
public class RaceContext {
    @PrimaryKey(autoGenerate = true)
    public int id;
    public String eventName;
    public String raceName;
    public int raceId;
    public long gunTimeMs; // 0 if not set
    public int splitAssignmentId;
    public String splitName;
    public String authToken;
    public String baseUrl;
    public long createdAtMs;
}
