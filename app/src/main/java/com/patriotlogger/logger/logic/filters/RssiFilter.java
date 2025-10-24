package com.patriotlogger.logger.logic.filters;

public interface RssiFilter {
    boolean shouldAccept(long timestampMs, int rssi);
}
