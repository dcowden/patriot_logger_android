package com.patriotlogger.logger.logic;

import androidx.annotation.NonNull;
import com.patriotlogger.logger.data.Setting;

public class RssiSmoother {

/*

What α (alpha) does

Think of α as the “responsiveness” to the raw signal itself.

High α (~0.5–0.8):

Level hugs the raw samples closely → very reactive.

Less smoothing, more noise gets through.

Low α (~0.1–0.3):

Level ignores short-term wiggles, smoother.

More lag if slope is present, unless β compensates.

What β (beta) does

Controls how quickly the trend (slope) adapts.

High β (~0.3–0.5):

Trend updates aggressively, output leads quickly when slope changes.

Can overshoot or jitter if the signal is noisy.

Low β (~0.05–0.15):

Trend updates slowly, avoids noise-driven slope flips.

Adds back some lag when RSSI ramps fast.

*/
    private static final float HOLT_ALPHA = 1.0f;
    private static final float HOLT_ALPHA_NO_CHANGE = 1.0f;
    private static final float HOLT_BETA_NO_CHANGE = 0.0f;
    private static final float HOLT_BETA  = 0.00f;

    // Shared Holt instance for static smoothing
    private static final Holt staticHolt = new Holt(HOLT_ALPHA, HOLT_BETA);

    private final Holt holt = new Holt(HOLT_ALPHA, HOLT_BETA);
    private float lastRssi = 0.0f;

    /**
     * Old EMA logic, now private. Kept in case you need it internally.
     */
    private static float computeEmaRssi(int rawRssi, float previousSmoothedRssi, float alpha) {
        if (previousSmoothedRssi == 0.0f) {
            return (float) rawRssi;
        }
        return (alpha * rawRssi) + ((1 - alpha) * previousSmoothedRssi);
    }

    /**
     * Public static smoother: now uses Holt under the hood.
     * Any legacy callers will transparently get Holt-smoothed RSSI.
     */
    public static synchronized float computeSmoothedRssi(int rawRssi,
                                                         float previousSmoothedRssi,
                                                         float alpha) {
        float ema = computeEmaRssi(rawRssi, previousSmoothedRssi, alpha);
        return staticHolt.update(ema);
    }

    /**
     * Instance-based smoothing (per stream).
     */
    public float getSmoothedRssi(int rawRssi, @NonNull Setting settings) {
        return computeSmoothedRssi(rawRssi,lastRssi,settings.rssi_averaging_alpha);
    }

    public float getLastRssi() {
        return lastRssi;
    }

    public void reset() {
        holt.reset();
        lastRssi = 0.0f;
    }

    // === Private Holt class ===
    private static final class Holt {
        private final float alpha;
        private final float beta;
        private boolean initialized = false;
        private float level = 0.0f;
        private float trend = 0.0f;

        Holt(float alpha, float beta) {
            this.alpha = clamp01(alpha);
            this.beta  = clamp01(beta);
        }

        float update(float x) {
            if (!initialized) {
                initialized = true;
                level = x;
                trend = 0.0f;
                return x;
            }
            float prevLevel = level;
            float newLevel = alpha * x + (1.0f - alpha) * (level + trend);
            float newTrend = beta * (newLevel - prevLevel) + (1.0f - beta) * trend;
            level = newLevel;
            trend = newTrend;
            return level + trend; // one-step-ahead prediction
        }

        void reset() {
            initialized = false;
            level = 0.0f;
            trend = 0.0f;
        }

        private static float clamp01(float v) {
            return v < 0f ? 0f : (v > 1f ? 1f : v);
        }
    }
}
