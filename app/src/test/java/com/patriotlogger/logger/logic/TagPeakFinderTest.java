package com.patriotlogger.logger.test;

import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.Setting;
import com.patriotlogger.logger.logic.TagPeakFinder;

import org.junit.Assert;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Unit tests for TagPeakFinder using real CSV samples from src/test/resources/data_samples.
 *
 * This test class includes a private loader that mirrors DriverMain's resource location.
 */
public class TagPeakFinderTest {

    private static final String RES_DIR = "data_samples";

    /**
     * Loads a CSV sample from src/test/resources/data_samples using the classloader,
     * parses it, and converts rows to TagData(tagId=0, timestampMs, rssi).
     *
     * We only require timestamp & rssi columns. Header matching is case-insensitive.
     */
    private List<TagData> loadTagDataFromResource(String filename) throws Exception {
        final String path = RES_DIR + "/" + filename;
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (in == null) {
            // Try the class's loader as a fallback
            in = TagPeakFinderTest.class.getClassLoader().getResourceAsStream(path);
        }
        if (in == null) {
            throw new IllegalArgumentException("Resource not found on classpath: " + path);
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int tsIdx = -1, rssiIdx = -1;
            // read header
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    String[] hdr = line.split(",", -1);
                    for (int i = 0; i < hdr.length; i++) {
                        String h = hdr[i].trim().toLowerCase(Locale.US);
                        if (h.equals("timestamp") || h.equals("timestampms") || h.equals("time") || h.equals("ts")) {
                            tsIdx = i;
                        } else if (h.equals("rssi")) {
                            rssiIdx = i;
                        }
                    }
                    break;
                }
            }
            if (tsIdx < 0 || rssiIdx < 0) {
                throw new IllegalStateException("Expected header with at least timestamp and rssi in " + path);
            }

            List<TagData> out = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",", -1);
                if (parts.length <= Math.max(tsIdx, rssiIdx)) continue;
                try {
                    long ts = Long.parseLong(parts[tsIdx].trim());
                    int rssi = Integer.parseInt(parts[rssiIdx].trim());
                    // tagId not meaningful for peak detection; use 0
                    out.add(new TagData(0, ts, rssi));
                } catch (NumberFormatException ignore) {
                    // skip malformed rows
                }
            }
            return out;
        }
    }

    /**
     * Helper to extract a long field if present (peakTimeMs or timestampMs) from TagPeakFinder's return.
     */
    private Long getLongField(Object obj, String field) {
        try {
            Field f = obj.getClass().getDeclaredField(field);
            f.setAccessible(true);
            Object v = f.get(obj);
            if (v instanceof Long) return (Long) v;
            if (v instanceof Number) return ((Number) v).longValue();
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * If peak is null, dump quick diagnostics to aid debugging.
     */
    private String quickSeriesSummary(List<TagData> td) {
        if (td == null || td.isEmpty()) return "empty series";
        long t0 = td.get(0).timestampMs;
        long t1 = td.get(td.size() - 1).timestampMs;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, sum = 0;
        for (TagData d : td) {
            min = Math.min(min, d.rssi);
            max = Math.max(max, d.rssi);
            sum += d.rssi;
        }
        double avg = sum / (double) td.size();
        return "rows=" + td.size() + ", tRangeMs=[" + t0 + ".." + t1 + "], rssi[min=" + min + ", max=" + max + ", avg=" + String.format(Locale.US, "%.2f", avg) + "]";
    }

    /**
     * Focus test for the sample ending in 5798 (calibration_data_1761170475798.csv).
     *
     * We assert that a peak is found. If not, we print diagnostics and fail the test.
     */
    @Test
    public void testFindPeak_0475798_findsPeak() throws Exception {
        final String fname = "calibration_data_1761170475798.csv";
        List<TagData> series = loadTagDataFromResource(fname);

        // Realistic settings; tweak if your TagPeakFinder expects different alpha, etc.
        Setting setting = new Setting();
        setting.rssi_averaging_alpha = 0.30f;

        TagPeakFinder pf = new TagPeakFinder();
        Object peakData = pf.findPeak(series, setting);

        if (peakData == null) {
            System.out.println("PeakFinder returned null for " + fname + " | " + quickSeriesSummary(series));
        }

        Assert.assertNotNull("Expected a non-null peak for " + fname, peakData);

        // If non-null, also assert we got a sensible timestamp field.
        Long peakTs = getLongField(peakData, "peakTimeMs");
        if (peakTs == null) {
            peakTs = getLongField(peakData, "timestampMs");
        }
        Assert.assertNotNull("Peak object present but no timestamp field (peakTimeMs/timestampMs) found for " + fname, peakTs);

        // Optional: sanity check that peakTs is within the sample time range.
        long tMin = series.get(0).timestampMs;
        long tMax = series.get(series.size() - 1).timestampMs;
        Assert.assertTrue("Peak timestamp not within series range: " + peakTs + " not in [" + tMin + "," + tMax + "]",
                peakTs >= tMin && peakTs <= tMax);

        System.out.println("Peak found for " + fname + " at ts=" + peakTs);
    }
}
