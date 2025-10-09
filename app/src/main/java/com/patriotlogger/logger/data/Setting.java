package com.patriotlogger.logger.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "settings_config")
public class Setting {

    public static final int SETTINGS_ID = 1;
    public static final boolean DEFAULT_RETAIN_SAMPLES = true;
    public static final int DEFAULT_ARRIVED_THRESHOLD = -70;
    public static final int DEFAULT_APPROACHING_THRESHOLD = -90;
    public static final float DEFAULT_RSSI_AVERAGING_ALPHA = 0.3f;

    public static final long DEFAULT_COOLDOWN_MS = 5000L;
    @PrimaryKey
    public int id = SETTINGS_ID;

    public Boolean retain_samples = DEFAULT_RETAIN_SAMPLES;
    public Integer arrived_threshold = DEFAULT_ARRIVED_THRESHOLD;
    public Integer approaching_threshold = DEFAULT_APPROACHING_THRESHOLD;
    public Float rssi_averaging_alpha = DEFAULT_RSSI_AVERAGING_ALPHA;
    public Setting() {}

    @Override
    public String toString() {
        return "Setting{" +
                "id=" + id +
                ", retain_samples=" + retain_samples +
                ", arrived_threshold=" + arrived_threshold +
                ", approaching_threshold=" + approaching_threshold +
                ", rssi_averaging_alpha=" + rssi_averaging_alpha +
                '}';
    }
}
