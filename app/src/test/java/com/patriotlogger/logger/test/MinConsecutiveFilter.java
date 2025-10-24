package com.patriotlogger.logger.test;

/**
 * Rejects isolated samples that jump far from their neighbors.
 * Keeps consecutive readings that stay within a limited dB range.
 */
public class MinConsecutiveFilter implements Filter {
    private final int maxStepDb;  // e.g., 6 dB
    private Integer prevAccepted = null;

    public MinConsecutiveFilter(int maxStepDb) {
        this.maxStepDb = Math.max(1, maxStepDb);
    }

    @Override
    public boolean shouldAccept(int rssi) {
        if (prevAccepted == null) {
            prevAccepted = rssi;
            return true;
        }

        if (Math.abs(rssi - prevAccepted) <= maxStepDb) {
            prevAccepted = rssi;
            return true; // similar to previous accepted sample
        }

        // otherwise, reject this isolated jump
        return false;
    }

    @Override
    public String toString() {
        return "MinConsecutiveFilter[maxStep=" + maxStepDb + "dB]";
    }
}
