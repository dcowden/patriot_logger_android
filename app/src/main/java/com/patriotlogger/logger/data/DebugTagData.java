package com.patriotlogger.logger.data;

/**
 * A POJO (Plain Old Java Object) to hold the results of a JOIN query
 * between TagData and TagStatus for the debug screen.
 */
public class DebugTagData {
    public int dataId; // The unique primary key from the TagData table
    public int trackId;
    public int tagId;
    public long timestampMs;
    public int rssi;

    /**
     * Helper method to format the device name as requested.
     * @return A string like "PT-123".
     */
    public String getDeviceName() {
        return "PT-" + tagId;
    }
}
