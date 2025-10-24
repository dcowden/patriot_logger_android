package com.patriotlogger.logger.test;

import com.patriotlogger.logger.data.CalibrationSample;
import com.patriotlogger.logger.data.TagStatus.TagStatusState;

public interface RssiHandler {
    /** Initialize/reset any internal state. Called before each new file is processed. */
    void init();

    /**
     * Accepts one sample and returns the current TagStatusState according to this
     * handler's internal smoothing + state logic.
     */
    TagStatusState acceptSample(CalibrationSample sample);

    /** Human-friendly name for reporting (defaults to simple class name). */
    default String getName() {
        return this.getClass().getSimpleName();
    }
}
