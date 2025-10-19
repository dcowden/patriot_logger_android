package com.patriotlogger.logger.logic;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.patriotlogger.logger.data.Setting;
import com.patriotlogger.logger.data.TagData;

import java.util.List;

/**
 * A dedicated class to find the precise peak time from a list of TagData samples.
 * It uses a zero-phase (forward-backward) digital filter to smooth the data
 * without introducing phase lag, allowing for accurate peak detection.
 */
public class TagPeakFinder {

    private static final int MINIMUM_SAMPLES_FOR_ANALYSIS = 3;

    /**
     * Finds the most accurate peak time from a list of signal samples.
     *
     * @param samples  The list of TagData samples collected during a pass.
     * @param settings The application settings, used to get the smoothing alpha.
     * @return A {@link TagPeakData} object containing the peak time and value,
     *         or null if a peak could not be determined.
     */
    @Nullable
    public TagPeakData findPeak(@NonNull List<TagData> samples, @NonNull Setting settings) {
        if (samples.size() < MINIMUM_SAMPLES_FOR_ANALYSIS) {
            return null; // Not enough data to perform a meaningful analysis.
        }

        // 1. Apply the Zero-Phase (forward-backward) EMA filter.
        double[] zeroPhaseSmoothedRssi = applyZeroPhaseFilter(samples, settings);

        // 2. Find the index of the maximum value in the corrected, smoothed data.
        int peakIndex = -1;
        double maxSmoothedRssi = -Double.MAX_VALUE;
        for (int i = 0; i < zeroPhaseSmoothedRssi.length; i++) {
            if (zeroPhaseSmoothedRssi[i] > maxSmoothedRssi) {
                maxSmoothedRssi = zeroPhaseSmoothedRssi[i];
                peakIndex = i;
            }
        }

        // 3. If a peak was found, create and return the result object.
        if (peakIndex != -1) {
            TagData peakSample = samples.get(peakIndex);
            return new TagPeakData(peakSample.timestampMs, (float) maxSmoothedRssi);
        }

        return null;
    }

    /**
     * Applies a forward-pass EMA and then a backward-pass EMA to the data,
     * effectively canceling out the phase lag of the filter.
     *
     * @param samples  The list of raw TagData samples.
     * @param settings The settings object containing the alpha value.
     * @return An array of doubles containing the lag-corrected smoothed RSSI values.
     */
    private double[] applyZeroPhaseFilter(@NonNull List<TagData> samples, @NonNull Setting settings) {
        int n = samples.size();
        double[] forwardPass = new double[n];
        double[] backwardPass = new double[n];
        float alpha = settings.rssi_averaging_alpha;

        // Forward pass
        forwardPass[0] = samples.get(0).rssi;
        for (int i = 1; i < n; i++) {
            forwardPass[i] = (alpha * samples.get(i).rssi) + ((1 - alpha) * forwardPass[i - 1]);
        }

        // Backward pass
        backwardPass[n - 1] = forwardPass[n - 1];
        for (int i = n - 2; i >= 0; i--) {
            backwardPass[i] = (alpha * forwardPass[i]) + ((1 - alpha) * backwardPass[i + 1]);
        }

        return backwardPass; // This is the zero-phase filtered result
    }
}
