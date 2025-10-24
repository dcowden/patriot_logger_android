package com.patriotlogger.logger.logic;

import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus;

import java.util.List;

public interface RssiHandler {
    /** Initialize/reset any internal state. Called before each new file is processed. */
    void init();

    /**
     * Accept one sample and return an UPDATED TagStatus according to handler logic.
     * @param currentStatus Current TagStatus (mutable record you persist)
     * @param history Full TagData history for this track (ordered by time)
     * @param sample New RSSI sample (raw + smoothed)
     * @return Updated TagStatus to persist
     */
    TagStatus acceptSample(TagStatus currentStatus, List<TagData> history, RssiData sample);

    /** Human-friendly name for reporting (defaults to simple class name). */
    default String getName() { return this.getClass().getSimpleName(); }
}
