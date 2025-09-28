package com.patriotlogger.logger.util;

import com.patriotlogger.logger.data.KalmanState;

/**
 * Simple 1D Kalman filter for RSSI smoothing.
 * This class now composes a KalmanState object to hold its state,
 * focusing this class on the filtering logic itself.
 */
public class Kalman1D {
    private final KalmanState state;

    /**
     * Constructor for a new filter, initializing its state.
     * @param tagId The tag ID for which this filter is being created.
     * @param q Process noise covariance.
     * @param r Measurement noise covariance.
     * @param initial_p Initial estimation error covariance.
     */
    public Kalman1D(int tagId, float q, float r, float initial_p) {
        this.state = new KalmanState(tagId, q, r, initial_p, 0f, false);
    }

    /**
     * Constructor to recreate a filter from a previously saved (and composed) state.
     * @param existingState The KalmanState object holding the filter's persisted data.
     */
    public Kalman1D(KalmanState existingState) {
        if (existingState == null) {
            throw new IllegalArgumentException("Existing KalmanState cannot be null.");
        }
        this.state = existingState;
    }

    public float update(float measurement) {
        if (!this.state.initialized) {
            this.state.x = measurement; // First measurement becomes the initial state
            this.state.initialized = true;
            // this.state.p remains the initial_p provided at construction of KalmanState
        } else {
            // Prediction update (predict next state)
            // p = p + q (state error covariance)
            this.state.p = this.state.p + this.state.q;

            // Measurement update (correction)
            // K = p / (p + r) (Kalman gain)
            float k = this.state.p / (this.state.p + this.state.r);
            // x = x + K * (measurement - x) (update estimate with measurement)
            this.state.x = this.state.x + k * (measurement - this.state.x);
            // p = (1 - K) * p (update error covariance)
            this.state.p = (1 - k) * this.state.p;
        }
        return this.state.x;
    }

    /**
     * Returns the internal KalmanState object, which contains all current state variables.
     * This is used for persisting the filter's state.
     * @return The composed KalmanState object.
     */
    public KalmanState getKalmanState() {
        return this.state;
    }

    // Individual getters can still be provided for convenience if needed,
    // delegating to the composed state object.
    public float getQ() { return this.state.q; }
    public float getR() { return this.state.r; }
    public float getP() { return this.state.p; }
    public float getX() { return this.state.x; }
    public boolean isInitialized() { return this.state.initialized; }
    public int getTagId() { return this.state.tagId; }
}
