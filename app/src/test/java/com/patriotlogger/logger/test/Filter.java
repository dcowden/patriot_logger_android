package com.patriotlogger.logger.test;

/** Simple RSSI filter contract. */
public interface Filter {
    /** @return true if this RSSI value should be kept (accepted), false to drop it. */
    boolean shouldAccept(int rssi);
}
