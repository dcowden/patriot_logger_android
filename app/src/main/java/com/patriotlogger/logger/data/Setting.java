package com.patriotlogger.logger.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "settings_config")
public class Setting {

    public static final int SETTINGS_ID = 1;

    @PrimaryKey
    public int id = SETTINGS_ID; // Use the constant for the single settings row

    public Boolean retain_samples = true; 
    public Integer arrived_threshold = -60;

    public static final boolean DEFAULT_RETAIN_SAMPLES = false;
    public static final int DEFAULT_ARRIVED_THRESHOLD = -70; // Example default RSSI

    public Setting() {}

}
