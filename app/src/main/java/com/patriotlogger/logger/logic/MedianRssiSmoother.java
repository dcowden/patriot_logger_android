package com.patriotlogger.logger.logic;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.TreeMap;

/**
 * MedianRssiSmoother
 *
 * Keeps the last N samples and returns their median as the smoothed RSSI.
 * - O(log U) updates (U = number of distinct values in the window)
 * - O(U) median lookup (fine for small/medium N). If you need faster medians
 *   at very large N, switch to a two-heap implementation.
 */
public final class MedianRssiSmoother {

    private final int windowSize;
    private final Deque<Integer> window = new ArrayDeque<>();
    // multiset of values -> counts, ordered for median walk
    private final TreeMap<Integer, Integer> counts = new TreeMap<>();
    private int total = 0; // number of elements currently in the window
    private Integer cachedMedian = null; // simple cache to avoid redundant scans

    /**
     * @param windowSize number of most-recent points to include in the median (N >= 1)
     */
    public MedianRssiSmoother(int windowSize) {
        if (windowSize <= 0) throw new IllegalArgumentException("windowSize must be >= 1");
        this.windowSize = windowSize;
    }

    /** Remove all state. */
    public void reset() {
        window.clear();
        counts.clear();
        total = 0;
        cachedMedian = null;
    }

    /**
     * Add one raw RSSI sample and return the updated median (int, rounded).
     */
    public int updateAndGet(int rssi) {
        add(rssi);
        return getSmoothedRssi();
    }

    /**
     * Add one raw RSSI sample (no return).
     */
    public void add(int rssi) {
        window.addLast(rssi);
        counts.merge(rssi, 1, Integer::sum);
        total++;
        if (total > windowSize) {
            int old = window.removeFirst();
            int c = counts.get(old);
            if (c == 1) counts.remove(old);
            else counts.put(old, c - 1);
            total--;
        }
        cachedMedian = null; // invalidate
    }

    /**
     * Current smoothed RSSI (median). If no samples yet, returns Integer.MIN_VALUE.
     * For even window sizes, returns the average of the two middles (rounded).
     */
    public int getSmoothedRssi() {
        if (total == 0) return Integer.MIN_VALUE;
        if (cachedMedian != null) return cachedMedian;

        // Walk the ordered multiset to find the middle(s)
        int leftIndex = (total - 1) / 2; // 0-based
        int rightIndex = total / 2;      // same as leftIndex when total is odd

        int seen = 0;
        Integer leftVal = null, rightVal = null;

        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            int val = e.getKey();
            int cnt = e.getValue();
            int start = seen;
            int end = seen + cnt - 1;

            if (leftVal == null && leftIndex >= start && leftIndex <= end) leftVal = val;
            if (rightVal == null && rightIndex >= start && rightIndex <= end) {
                rightVal = val;
                // we can break only if left already found too
                if (leftVal != null) break;
            }
            seen += cnt;
        }

        int median;
        if (leftVal != null && rightVal != null) {
            if (leftIndex == rightIndex) median = leftVal; // odd
            else median = Math.round((leftVal + rightVal) / 2.0f); // even
        } else {
            // Fallback (shouldn't happen)
            median = counts.firstKey();
        }

        cachedMedian = median;
        return median;
    }

    /** Same as getSmoothedRssi() but as double (useful for plotting). */
    public double getSmoothedRssiAsDouble() {
        int m = getSmoothedRssi();
        return (m == Integer.MIN_VALUE) ? Double.NaN : (double) m;
    }

    /** @return current window size N (fixed) */
    public int getWindowSize() { return windowSize; }

    /** @return number of samples currently buffered (<= N) */
    public int size() { return total; }
}
