package com.patriotlogger.logger.test;

import com.patriotlogger.logger.data.CalibrationSample;

/**
 * Simple 1D Kalman for RSSI with constant process/measurement noise.
 * Not fancy—good enough to evaluate smoothing vs transitions.
 */
public class KalmanRssiHandler extends AbstractRssiHandler {

    private final float processVar;      // Q
    private final float measurementVar;  // R

    private boolean inited = false;
    private float x; // estimate
    private float p; // estimate covariance

    public KalmanRssiHandler(float processVar, float measurementVar) {
        this.processVar = Math.max(1e-6f, processVar);
        this.measurementVar = Math.max(1e-6f, measurementVar);
    }

    @Override
    protected void onInit() {
        inited = false;
        x = 0f;
        p = 1f;
    }

    @Override
    protected float smooth(CalibrationSample s) {
        float z = s.rssi;

        if (!inited) {
            x = z;
            p = 1f;
            inited = true;
            return x;
        }

        // Predict
        float x_pred = x;          // constant model
        float p_pred = p + processVar;

        // Update
        float k = p_pred / (p_pred + measurementVar);
        x = x_pred + k * (z - x_pred);
        p = (1 - k) * p_pred;

        return x;
    }

    @Override
    public String getName() { return "Kalman(Q=" + processVar + ",R=" + measurementVar + ")"; }
}

