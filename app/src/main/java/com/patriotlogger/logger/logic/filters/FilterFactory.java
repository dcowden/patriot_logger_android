package com.patriotlogger.logger.logic.filters;

import androidx.annotation.NonNull;

import com.patriotlogger.logger.data.Setting;

/**
 * Central place to build the active RSSI filter chain from Settings.
 * No schema changes: we keep sensible defaults and use existing fields when helpful.
 */
public final class FilterFactory {

    private FilterFactory() {}

    /**
     * Convert current Setting to a filter chain.
     * Right now:
     *  - Min/Max RSSI bounds (defaults -105..-25 dBm).
     *  - You can add more filters here later without touching BleScannerService.
     */
    @NonNull
    public static java.util.List<RssiFilter> build(@NonNull Setting s, FiltersConfig overrides) {
        final java.util.ArrayList<RssiFilter> list = new java.util.ArrayList<>(4);

        // Reasonable defaults for BLE indoors (avoid crazy outliers).
        int minRssi = (overrides != null && overrides.minRssi != null)
                ? overrides.minRssi
                : -105;

        int maxRssi = (overrides != null && overrides.maxRssi != null)
                ? overrides.maxRssi
                : -25;

        // Example of optionally tying to existing thresholds if you want:
        // If someone sets arrived_threshold tighter than max bound, clamp to that.
        if (s.arrived_threshold != null) {
            // arrived_threshold is a negative number; tighter == closer to 0.
            // maxRssi is also a negative number (upper bound). If arrived is > max, raise the ceiling.
            if (s.arrived_threshold > maxRssi) {
                maxRssi = s.arrived_threshold;
            }
        }

        list.add(new MinMaxRssiFilter(minRssi, maxRssi));

        // Future additions can go here (e.g., short-gap debouncer, burst limiter), still standard.

        return list;
    }

    /**
     * Optional overrides you can pass from the service if you want to tweak without changing Setting.
     */
    public static final class FiltersConfig {
        public final Integer minRssi;
        public final Integer maxRssi;

        public FiltersConfig(Integer minRssi, Integer maxRssi) {
            this.minRssi = minRssi;
            this.maxRssi = maxRssi;
        }

        public static FiltersConfig defaults() {
            return new FiltersConfig(null, null);
        }
    }
}
