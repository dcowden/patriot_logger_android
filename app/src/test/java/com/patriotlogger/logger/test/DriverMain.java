package com.patriotlogger.logger.test;

import com.patriotlogger.logger.data.CalibrationSample;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus.TagStatusState;
import com.patriotlogger.logger.data.Setting;
import com.patriotlogger.logger.logic.TagPeakFinder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Writes JSON files "result_<csv>.json" in the working dir with:
 *  - baseTs
 *  - raw filtered series (blue circles)
 *  - per-algorithm state pins (APPROACHING/HERE/LOGGED/PEAK)
 *  - per-algorithm LINE SERIES of the handler's filtered output over time
 *
 * Peak is computed ONLY up to that handler's LOGGED timestamp; if LOGGED missing => PEAK missing.
 * Samples are pre-filtered (range + optional stateful filters) before hitting handlers.
 */
public class DriverMain {

    private static final String SAMPLES_DIR = "data_samples";
    private static final long BASE_TS = 1761170000000L;

    // candidate field names to reflect handler's current filtered value
    private static final String[] VALUE_FIELDS = new String[]{
            "ema", "median", "filtered", "filter", "estimate", "est", "smoothed", "value", "stateRssi", "x", "xhat"
    };

    @org.junit.Test
    public void runDriver() throws Exception {
        main(new String[]{});
    }

    public static void main(String[] args) throws Exception {
        // ---------- Handlers ----------
        List<RssiHandler> handlers = new ArrayList<>();
        //handlers.add(new EmaRssiHandler(0.30f));              // "EMA(alpha=0.3)"
        //handlers.add(new MedianRssiHandler(5));               // "MedianN(N=7)"
        //handlers.add(new KalmanRssiHandler(4f, 16f));         // "Kalman(Q=4.0,R=16.0)"
        //handlers.add(new KalmanRssiHandler(4f, 32f));         // "Kalman(Q=4.0,R=16.0)"
        //handlers.add(new KalmanRssiHandler(8f, 16f));         // "Kalman(Q=4.0,R=16.0)"
        //handlers.add(new KalmanRssiHandler(8f, 32f));
        //handlers.add(new KalmanRssiHandler(2f, 32f));// "Kalman(Q=4.0,R=16.0)"
       //handlers.add(new RobustMedianEmaHandler());           // "Median→EMA FSM …"

        //handlers.add(new NonConsecutiveThresholdHandler(-85, 3));                 // any 3 ≥ -85 enter; any 3 < -85 exit
        //handlers.add(new TimeBasedExitHandler(0.35f, -80, 2000));                 // EMA HERE; LOGGED after 2s
        //handlers.add(new PowerModelDistanceHandler(0.50f, -70, 2.0, 6.0, 2));     // 3 non-consec inside/outside 6m
        //handlers.add(new TcaHandler(0.30f, -70, 2.0, 4, 2.0, 40,6));
        //handlers.add(new TcaHandler(0.30f, -70, 2.0, 6, 2.0, 40,6));
        //handlers.add(new TcaHandler(0.30f, -70, 2.0, 8, 2.0, 40,6));
        //handlers.add(new TcaHandler(0.10f, -70, 2.0, 4, 1.5, 40,6));
        //handlers.add(new TcaHandler(0.20f, -70, 2.0, 4, 1.5, 40,6));
        //handlers.add(new TcaHandlerWithFallback(0.20f, -70, 2.0, 4, 1.5, 40,6,20.0f));
        //handlers.add(new TcaHandlerWithFallback(0.20f, -70, 2.0, 4, 1.5, 40,6,15.0f));
        //handlers.add(new TcaHandlerWithFallback(0.20f, -70, 2.0, 6, 1.5, 40,6,10.0f));
        //handlers.add(new TcaHandlerWithFallback(0.20f, -70, 2.0, 5, 1.5, 40,6,10.0f));
        //handlers.add(new TcaHandlerWithFallback(0.20f, -70, 2.0, 4, 1.5, 40,6,10.0f));
        //handlers.add(new TcaHandlerWithFallback(0.20f, -70, 2.0, 4, 1.5, 40,6,12.0f));
        //handlers.add(new TcaHandlerWithFallback(0.20f, -70, 2.0, 4, 1.5, 40,6,15.0f));
        handlers.add(new TcaHandlerWithFallback(0.20f, -70, 2.0, 4, 1.0, 40,6,20.0f));
        handlers.add(new TcaHandlerWithFallback(0.20f, -70, 2.0, 4, 1.5, 40,6,20.0f));
        handlers.add(new TcaHandlerWithFallback(0.20f, -70, 2.0, 4, 2.0, 40,6,20.0f));
        handlers.add(new TcaHandlerWithFallback(0.20f, -70, 2.0, 4, 2.5, 40,6,20.0f));
        //handlers.add(new TcaHandlerWithFallback(0.20f, -70, 2.0, 3, 1.5, 40,6,10.0f));
        //handlers.add(new TcaHandlerWithFallback2(0.20f, -70, 2.0, 4, 1.5, 40,6,10.0f));

        //handlers.add(new TcaHandler(0.30f, -70, 2.0, 4, 1.5, 40,6));
        //handlers.add(new TcaHandler(0.40f, -70, 2.0, 4, 1.5, 40,6));
        //handlers.add(new TcaHandler(0.50f, -70, 2.0, 4, 1.5, 40,6));

        // ---------- Filters (ingest) ----------
        List<Filter> filters = Arrays.asList(
                new RssiRangeFilter(-115, -35)
                //new OutlierDeltaFilter(5, 8),
                //new MinConsecutiveFilter(6)
        );

        List<String> resourceFiles = ResourceUtils.listResourceFiles(SAMPLES_DIR);
        if (resourceFiles.isEmpty()) {
            System.out.println("No CSV files found under src/test/resources/" + SAMPLES_DIR);
            return;
        }

        for (String resPath : resourceFiles) {
            final String sampleFile = resPath.substring(resPath.lastIndexOf('/') + 1);
            List<CalibrationSample> raw = readSamplesFromResource(resPath);
            List<CalibrationSample> samples = applyFilters(raw, filters);

            // Raw filtered series for plotting (blue circles)
            Map<Long, Integer> tsToRssi = new HashMap<>(Math.max(16, samples.size() * 2));
            List<Long> tsList = new ArrayList<>(samples.size());
            for (CalibrationSample s : samples) {
                tsToRssi.put(s.timestampMs, s.rssi);
                tsList.add(s.timestampMs);
            }
            Collections.sort(tsList);

            // Per-handler: run FSM, collect states + LINE SERIES of handler value
            Map<String, Map<String, Point>> algoStates = new LinkedHashMap<>();
            Map<String, List<LinePoint>> algoLines = new LinkedHashMap<>();

            for (RssiHandler h : handlers) {
                // trace buffer for the handler's filtered value
                List<LinePoint> line = new ArrayList<>(samples.size());

                h.init();
                TestResult tr = new TestResult();
                TagStatusState prev = TagStatusState.TOO_FAR;

                for (CalibrationSample s : samples) {
                    TagStatusState cur = h.acceptSample(s);

                    // try to read handler's current value via reflection (best-effort)
                    Double v = reflectCurrentValue(h);
                    if (v == null) {
                        // fallback: use incoming RSSI (not ideal, but better than nothing)
                        v = (double) s.rssi;
                    }
                    line.add(new LinePoint(s.timestampMs - BASE_TS, v));

                    if (cur != prev) {
                        if (cur == TagStatusState.APPROACHING ||
                                cur == TagStatusState.HERE ||
                                cur == TagStatusState.LOGGED) {
                            tr.record(cur, s.timestampMs);
                        }
                        prev = cur;
                    }
                }

                // Peak only up to LOGGED
                String tApproach = tr.getOrX(TagStatusState.APPROACHING);
                String tHere     = tr.getOrX(TagStatusState.HERE);
                String tLogged   = tr.getOrX(TagStatusState.LOGGED);

                String tPeak = "X";
                Long loggedTs = parseLongOrNull(tLogged);
                if (loggedTs != null) {
                    List<CalibrationSample> upToLogged = cutSamplesAtOrBefore(samples, loggedTs);
                    tPeak = detectPeakTs(upToLogged);
                }

                Point pApproach = pointForState(tApproach, tsToRssi, tsList);
                Point pHere     = pointForState(tHere,     tsToRssi, tsList);
                Point pLogged   = pointForState(tLogged,   tsToRssi, tsList);
                Point pPeak     = pointForState(tPeak,     tsToRssi, tsList);

                Map<String, Point> states = new LinkedHashMap<>();
                states.put("APPROACHING", pApproach);
                states.put("HERE",        pHere);
                states.put("LOGGED",      pLogged);
                states.put("PEAK",        pPeak);

                algoStates.put(h.getName(), states);
                algoLines.put(h.getName(), line);
            }

            // Write JSON
            String json = buildJson(sampleFile, samples, algoStates, algoLines);
            Path out = Paths.get("result_" + sampleFile + ".json");
            try (BufferedWriter bw = Files.newBufferedWriter(out,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                bw.write(json);
            }
            System.out.println("Wrote " + out.toAbsolutePath());
        }

        System.out.println("Done. Plot with plot_results_lines.py");
    }

    // ---------- Reflection probe for handler's current filtered value ----------
    private static Double reflectCurrentValue(Object handler) {
        Class<?> c = handler.getClass();
        for (String name : VALUE_FIELDS) {
            try {
                Field f = findField(c, name);
                if (f != null) {
                    f.setAccessible(true);
                    Object v = f.get(handler);
                    if (v instanceof Number) return ((Number) v).doubleValue();
                }
            } catch (Throwable ignore) { /* try next */ }
        }
        return null;
    }
    private static Field findField(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null) {
            try { return cur.getDeclaredField(name); }
            catch (NoSuchFieldException e) { cur = cur.getSuperclass(); }
        }
        return null;
    }

    // ---------- Filters ----------
    private static List<CalibrationSample> applyFilters(List<CalibrationSample> in, List<Filter> filters) {
        if (filters == null || filters.isEmpty()) return in;
        List<CalibrationSample> out = new ArrayList<>(in.size());
        outer:
        for (CalibrationSample s : in) {
            for (Filter f : filters) {
                if (!f.shouldAccept(s.rssi)) continue outer;
            }
            out.add(s);
        }
        return out;
    }

    private static List<CalibrationSample> cutSamplesAtOrBefore(List<CalibrationSample> in, long cutoffTs) {
        if (in == null || in.isEmpty()) return Collections.emptyList();
        List<CalibrationSample> out = new ArrayList<>(in.size());
        for (CalibrationSample s : in) {
            if (s.timestampMs <= cutoffTs) out.add(s); else break;
        }
        return out;
    }

    // ---------- JSON ----------
    private static String buildJson(String sampleFile,
                                    List<CalibrationSample> samples,
                                    Map<String, Map<String, Point>> algoStates,
                                    Map<String, List<LinePoint>> algoLines) {
        StringBuilder sb = new StringBuilder(128_000);
        sb.append("{");
        sb.append("\"sampleFile\":").append(q(sampleFile)).append(",");
        sb.append("\"baseTs\":").append(BASE_TS).append(",");
        // raw series (filtered)
        sb.append("\"series\":[");
        for (int i = 0; i < samples.size(); i++) {
            CalibrationSample s = samples.get(i);
            sb.append("{\"t\":").append(s.timestampMs - BASE_TS)
                    .append(",\"rssi\":").append(s.rssi).append("}");
            if (i + 1 < samples.size()) sb.append(",");
        }
        sb.append("],");
        // algorithms
        sb.append("\"algorithms\":{");
        int a = 0;
        for (Map.Entry<String, Map<String, Point>> e : algoStates.entrySet()) {
            if (a++ > 0) sb.append(",");
            String name = e.getKey();
            sb.append(q(name)).append(":{");
            // states
            sb.append("\"APPROACHING\":").append(pointJson(e.getValue().get("APPROACHING"))).append(",");
            sb.append("\"HERE\":").append(pointJson(e.getValue().get("HERE"))).append(",");
            sb.append("\"LOGGED\":").append(pointJson(e.getValue().get("LOGGED"))).append(",");
            sb.append("\"PEAK\":").append(pointJson(e.getValue().get("PEAK"))).append(",");
            // line series
            List<LinePoint> line = algoLines.get(name);
            sb.append("\"line\":[");
            if (line != null) {
                for (int i2 = 0; i2 < line.size(); i2++) {
                    LinePoint lp = line.get(i2);
                    sb.append("{\"t\":").append(lp.t).append(",\"v\":").append(fmt(lp.v)).append("}");
                    if (i2 + 1 < line.size()) sb.append(",");
                }
            }
            sb.append("]}");
        }
        sb.append("}");
        sb.append("}");
        return sb.toString();
    }

    private static String pointJson(Point p) {
        if (p == null || p.tsOffset == null || p.rssi == null) return "{\"t\":null,\"rssi\":null}";
        return "{\"t\":" + p.tsOffset + ",\"rssi\":" + p.rssi + "}";
    }

    private static String q(String s) {
        if (s == null) return "null";
        String esc = s.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + esc + "\"";
    }
    private static String fmt(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
        return String.format(Locale.US, "%.3f", d);
    }

    // ---------- helpers ----------
    private static class Point { final Long tsOffset; final Integer rssi; Point(Long t, Integer r){ tsOffset=t; rssi=r; } }
    private static class LinePoint { final long t; final double v; LinePoint(long t,double v){ this.t=t; this.v=v; } }

    private static Point pointForState(String tsStr, Map<Long, Integer> tsToRssi, List<Long> sortedTs) {
        if (tsStr == null || tsStr.equals("X")) return new Point(null, null);
        Long ts = parseLongOrNull(tsStr);
        if (ts == null) return new Point(null, null);
        Integer rssi = tsToRssi.get(ts);
        if (rssi == null && !sortedTs.isEmpty()) {
            int idx = Collections.binarySearch(sortedTs, ts);
            if (idx < 0) {
                int ins = -idx - 1;
                long bestTs;
                if (ins <= 0) bestTs = sortedTs.get(0);
                else if (ins >= sortedTs.size()) bestTs = sortedTs.get(sortedTs.size() - 1);
                else {
                    long t1 = sortedTs.get(ins - 1), t2 = sortedTs.get(ins);
                    bestTs = (Math.abs(ts - t1) <= Math.abs(ts - t2)) ? t1 : t2;
                }
                rssi = tsToRssi.get(bestTs);
                ts = bestTs;
            }
        }
        Long tOff = (rssi == null) ? null : (ts - BASE_TS);
        return new Point(tOff, rssi);
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.equals("X")) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    private static String detectPeakTs(List<CalibrationSample> samples) {
        if (samples == null || samples.isEmpty()) return "X";
        try {
            List<TagData> tagDataList = new ArrayList<>(samples.size());
            for (CalibrationSample s : samples) tagDataList.add(new TagData(0, s.timestampMs, s.rssi));
            Setting setting = new Setting();
            setting.rssi_averaging_alpha = 0.30f;
            TagPeakFinder pf = new TagPeakFinder();
            Object peakData = pf.findPeak(tagDataList, setting);
            if (peakData == null) return "X";
            Long ts = getLongIfExists(peakData, "peakTimeMs");
            if (ts == null) ts = getLongIfExists(peakData, "timestampMs");
            return (ts == null) ? "X" : Long.toString(ts);
        } catch (Throwable t) { return "X"; }
    }

    private static Long getLongIfExists(Object obj, String field) {
        try {
            Field f = obj.getClass().getDeclaredField(field);
            f.setAccessible(true);
            Object v = f.get(obj);
            if (v instanceof Long) return (Long) v;
            if (v instanceof Number) return ((Number) v).longValue();
            return null;
        } catch (Throwable t) { return null; }
    }

    // ---------- CSV ingest ----------
    private static List<CalibrationSample> readSamplesFromResource(String resourcePath) {
        List<CalibrationSample> out = new ArrayList<>();
        try (BufferedReader br = ResourceUtils.openResourceAsReader(resourcePath)) {
            String line;
            int tsIdx = -1, tagIdx = -1, rssiIdx = -1, smoothIdx = -1;
            // header
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    String[] hdr = line.split(",", -1);
                    for (int i = 0; i < hdr.length; i++) {
                        String h = hdr[i].trim().toLowerCase(Locale.US);
                        if (h.equals("timestamp") || h.equals("timestampms") || h.equals("time") || h.equals("ts")) tsIdx = i;
                        else if (h.equals("tagid") || h.equals("tag") || h.equals("id")) tagIdx = i;
                        else if (h.equals("rssi")) rssiIdx = i;
                        else if (h.equals("smoothedrssi") || h.equals("smoothed") || h.equals("rssi_smooth")) smoothIdx = i;
                    }
                    break;
                }
            }
            if (tsIdx < 0 || rssiIdx < 0) {
                throw new IOException("Expected header with at least timestamp and rssi: " + resourcePath);
            }
            // rows
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",", -1);
                if (parts.length <= Math.max(tsIdx, rssiIdx)) continue;
                try {
                    long ts = Long.parseLong(parts[tsIdx].trim());
                    int rssi = Integer.parseInt(parts[rssiIdx].trim());
                    int tagId = 0;
                    if (tagIdx >= 0 && tagIdx < parts.length && !parts[tagIdx].trim().isEmpty()) {
                        try { tagId = Integer.parseInt(parts[tagIdx].trim()); } catch (NumberFormatException ignore) {}
                    }
                    int smoothed = rssi;
                    if (smoothIdx >= 0 && smoothIdx < parts.length && !parts[smoothIdx].trim().isEmpty()) {
                        try { smoothed = Integer.parseInt(parts[smoothIdx].trim()); } catch (NumberFormatException ignore) {}
                    }
                    out.add(new CalibrationSample(tagId, ts, rssi, smoothed));
                } catch (NumberFormatException ignore) { /* skip */ }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading " + resourcePath, e);
        }
        return out;
    }
}
