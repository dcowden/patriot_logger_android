package com.patriotlogger.logger.logic;

/**
 * Configuration constants for the TagProcessor.
 */
public class TagProcessorConfig {
    public final long lossTimeoutMs;                 // Time after which a tag is considered "lost"
    public final int samplesBelowPeakForHere;       // Consecutive samples below peak RSSI to transition to "here" state
    public final float defaultKalmanQ;              // Default Kalman filter Q parameter (process noise covariance) for new tags
    public final float defaultKalmanR;              // Default Kalman filter R parameter (measurement noise covariance) for new tags
    public final float defaultKalmanInitialP;       // Default Kalman filter initial P parameter (estimation error covariance) for new tags

    public TagProcessorConfig(long lossTimeoutMs, int samplesBelowPeakForHere, 
                              float defaultKalmanQ, float defaultKalmanR, float defaultKalmanInitialP) {
        this.lossTimeoutMs = lossTimeoutMs;
        this.samplesBelowPeakForHere = samplesBelowPeakForHere;
        this.defaultKalmanQ = defaultKalmanQ;
        this.defaultKalmanR = defaultKalmanR;
        this.defaultKalmanInitialP = defaultKalmanInitialP;
    }
}
