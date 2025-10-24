package com.patriotlogger.logger.logic.filters;

public class MinMaxRssiFilter implements RssiFilter{
    private final int minRssi;
    private final int maxRssi;
    public MinMaxRssiFilter(int minRssi, int maxRssi) {
        this.minRssi = minRssi;
        this.maxRssi = maxRssi;
    }
    @Override public boolean shouldAccept(long timestampMs, int rssi) {
        return ( rssi >= minRssi && rssi <= maxRssi );
    }

}
