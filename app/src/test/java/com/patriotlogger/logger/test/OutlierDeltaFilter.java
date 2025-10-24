package com.patriotlogger.logger.test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Arrays;

/**
 * Rejects RSSI samples that deviate too far from the recent trend (median of last N).
 * Useful for killing one-off dips/spikes that would break state transitions.
 */
public class OutlierDeltaFilter implements Filter {
    private final int window;      // e.g., 5 samples
    private final int maxDeltaDb;  // e.g., 8 dB
    private final Deque<Integer> hist = new ArrayDeque<>();

    public OutlierDeltaFilter(int window, int maxDeltaDb) {
        this.window = Math.max(1, window);
        this.maxDeltaDb = Math.max(1, maxDeltaDb);
    }

    @Override
    public boolean shouldAccept(int rssi) {
        if (hist.size() < window) {
            hist.addLast(rssi);
            return true; // accept early points
        }

        int median = medianOfDeque(hist);
        boolean ok = Math.abs(rssi - median) <= maxDeltaDb;

        if (ok) {
            hist.addLast(rssi);
            if (hist.size() > window) hist.removeFirst();
        }
        return ok;
    }

    private static int medianOfDeque(Deque<Integer> d) {
        int n = d.size();
        int[] arr = new int[n];
        int i = 0;
        for (Integer v : d) arr[i++] = v;
        Arrays.sort(arr);
        return (n % 2 == 1) ? arr[n / 2]
                : (int) Math.floor((arr[n / 2 - 1] + arr[n / 2]) / 2.0);
    }

    @Override
    public String toString() {
        return "OutlierDeltaFilter[win=" + window + ", maxΔ=" + maxDeltaDb + "dB]";
    }
}
