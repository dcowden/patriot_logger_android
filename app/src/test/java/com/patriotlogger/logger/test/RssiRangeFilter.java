package com.patriotlogger.logger.test;

/** Accepts RSSI only within [minInclusive, maxInclusive]. */
public class RssiRangeFilter implements Filter {
    private final int minInclusive;
    private final int maxInclusive;

    /**
     * @param minInclusive e.g., -120
     * @param maxInclusive e.g.,  -30
     */
    public RssiRangeFilter(int minInclusive, int maxInclusive) {
        this.minInclusive = minInclusive;
        this.maxInclusive = maxInclusive;
    }

    @Override
    public boolean shouldAccept(int rssi) {
        return rssi >= minInclusive && rssi <= maxInclusive;
    }

    @Override
    public String toString() {
        return "RssiRangeFilter[" + minInclusive + "," + maxInclusive + "]";
    }
}
