package com.patriotlogger.logger.test;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;

import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.Setting;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus;
import com.patriotlogger.logger.data.TagStatus.TagStatusState;
import com.patriotlogger.logger.logic.RssiData;
import com.patriotlogger.logger.logic.SampleProcessor;
import com.patriotlogger.logger.logic.RssiHandler;
import com.patriotlogger.logger.logic.SuperSimpleHandler;
import com.patriotlogger.logger.logic.filters.MinMaxRssiFilter;
import com.patriotlogger.logger.logic.filters.RssiFilter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Writes result_*.json with:
 *   series: [{t, rssi, smoothed}]  // smoothed is ALWAYS recomputed EMA (ignores CSV smoothed)
 *   algorithms[ALG].tracks[trackId].APPROACHINGS/HERES/LOGGEDS/PEAKS
 *   algorithms[ALG].line: handler internal trace (if exposed via reflection)
 *
 * After the last sample, we advance time by abandoned_timeout_ms and invoke the handler's
 * timeOut() so the state can close to LOGGED on silence.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class DriverMain {

    private static final String SAMPLES_DIR = "data_samples";
    private static final long BASE_TS = 1761170000000L;

    private static final long SIM_SILENCE_TAIL_MS = 20_000L;  // how long to simulate silence
    private static final long SIM_TICK_MS         = 100L;     // how often we “tick” the handler


    // reflection probe names to fetch handler's internal value (optional)
    private static final String[] VALUE_FIELDS = new String[]{
            "ema","median","filtered","filter","estimate","est","smoothed","value","stateRssi","x","xhat"
    };

    @Test
    public void runDriver() throws Exception {
        main(new String[]{});
    }

    public static void main(String[] args) throws Exception {
        List<String> resourceFiles = listResourceFiles(SAMPLES_DIR);
        if (resourceFiles.isEmpty()) {
            System.out.println("No CSV files found under src/test/resources/" + SAMPLES_DIR);
            return;
        }

        // Single algorithm config, tune as needed
        List<Setting> algos = new ArrayList<>();
        Setting base = new Setting();
        base.tca_alpha = 0.05f;                      // EMA alpha used to recompute smoothed values
        base.tx_power_at_1m_dbm = -70.0;
        base.path_loss_n = 2.0;
        base.tca_here_meters = 1.0;
        base.tca_threshold_sec = 1.5;
        base.tca_window_size = 40;
        base.tca_min_points = 6;
        base.tca_approach_meters = 15.0;
        base.filter_min_rssi = -115;
        base.filter_max_rssi = -35;
        if (base.abandoned_timeout_ms <= 0) base.abandoned_timeout_ms = 5000;
        algos.add(base);

        for (String resPath : resourceFiles) {
            final String sampleFile = resPath.substring(resPath.lastIndexOf('/') + 1);

            // IMPORTANT: recompute smoothed from RAW with the chosen alpha, ignoring CSV smoothed.
            List<AlgoResult> algoResults = new ArrayList<>();

            for (Setting algoSetting : algos) {
                final Setting setting = cloneSetting(algoSetting);

                List<RssiData> samples = readSamplesFromResource(resPath, setting.tca_alpha);
                // Save a copy for writing JSON later
                List<RssiData> filtered = applyFilters(samples, Arrays.asList(new MinMaxRssiFilter(-115, -35)));

                // map for annotations
                Map<Long, Integer> tsToRssi = new HashMap<>(Math.max(16, filtered.size() * 2));
                List<Long> tsList = new ArrayList<>(filtered.size());
                for (RssiData s : filtered) { tsToRssi.put(s.timestampMs, s.rssi); tsList.add(s.timestampMs); }
                Collections.sort(tsList);

                Context ctx = ApplicationProvider.getApplicationContext();
                Repository repo = Repository.createInMemoryForTest(ctx);

                // Simple causal handler that consumes smoothedRssi from packets
                SuperSimpleHandler handler = new SuperSimpleHandler(-90, 2, 10000, 30000);
                SampleProcessor proc = new SampleProcessor(repo, handler, setting);

                List<LinePoint> line = new ArrayList<>(filtered.size());
                Map<Integer, List<StateHit>> hitsByTrack = new LinkedHashMap<>();
                Map<Integer, TagStatusState> prevByTrack = new HashMap<>();
                Map<Integer, TagStatus> statusByTrack = new HashMap<>();
                Map<Integer, List<TagData>> historyByTrack = new HashMap<>();

                long lastTs = filtered.isEmpty() ? BASE_TS : filtered.get(filtered.size() - 1).timestampMs;
                SuperSimpleHandler lastHandler = null;

                // stream
                for (RssiData s : filtered) {
                    TagStatus cur = proc.processSample(s.tagId, s.rssi, s.timestampMs);

                    // record history
                    historyByTrack.computeIfAbsent(cur.trackId, k -> new ArrayList<>())
                            .add(new TagData(cur.trackId, s.timestampMs, s.rssi));

                    int trackId = cur.trackId;
                    TagStatusState prev = prevByTrack.getOrDefault(trackId, TagStatusState.TOO_FAR);

                    // record handler's internal value if available, else use packet smoothed (recomputed EMA)
                    double v = s.smoothedRssi;
                    SuperSimpleHandler h = proc.getHandlerForTrack(trackId);
                    if (h != null) {
                        lastHandler = h;
                        Double maybe = reflectCurrentValue(h);
                        if (maybe != null) v = maybe;
                    }
                    line.add(new LinePoint(s.timestampMs - BASE_TS, v));

                    if (cur.state != prev) {
                        if (cur.state == TagStatusState.APPROACHING ||
                                cur.state == TagStatusState.HERE ||
                                cur.state == TagStatusState.LOGGED) {
                            hitsByTrack.computeIfAbsent(trackId, k -> new ArrayList<>())
                                    .add(new StateHit(s.timestampMs, trackId, cur.state));
                        }
                        prevByTrack.put(trackId, cur.state);
                    }

                    statusByTrack.put(trackId, cur);
                    lastTs = s.timestampMs;
                }

                // synthetic timeout after silence
                final long timeoutAdvance = Math.max(1000L, setting.abandoned_timeout_ms > 0 ? setting.abandoned_timeout_ms : 5000L);
                final long fireTs = lastTs + timeoutAdvance;

                final long tailEnd = lastTs + SIM_SILENCE_TAIL_MS;

                for (Map.Entry<Integer, TagStatus> e : statusByTrack.entrySet()) {
                    final int trackId = e.getKey();
                    TagStatus cur = e.getValue();
                    TagStatusState prev = prevByTrack.getOrDefault(trackId, TagStatusState.TOO_FAR);

                    // IMPORTANT: use the SAME handler instance that processed this track
                    SuperSimpleHandler h = proc.getHandlerForTrack(trackId);
                    if (h == null) continue;

                    long t = lastTs + SIM_TICK_MS;
                    boolean closed = false;

                    while (t <= tailEnd) {
                        TagStatus after = h.timeOut(cur, t);

                        // Optional: plot the handler's internal value during the silent tail
                        Double v = reflectCurrentValue(h);
                        if (v != null) {
                            line.add(new LinePoint(t - BASE_TS, v));
                        }

                        if (after != null && after.state != prev) {
                            if (after.state == TagStatusState.APPROACHING ||
                                    after.state == TagStatusState.HERE ||
                                    after.state == TagStatusState.LOGGED) {
                                hitsByTrack.computeIfAbsent(trackId, k -> new ArrayList<>())
                                        .add(new StateHit(t, trackId, after.state));
                            }
                            prev = after.state;
                            prevByTrack.put(trackId, prev);
                            statusByTrack.put(trackId, after);
                            if (after.state == TagStatusState.LOGGED) { closed = true; break; }
                        }

                        cur = (after != null) ? after : cur;
                        t += SIM_TICK_MS;
                    }

                    // One last nudge at tailEnd in case the last tick missed a boundary
                    if (!closed) {
                        TagStatus after = h.timeOut(cur, tailEnd);
                        if (after != null && after.state != prev) {
                            if (after.state == TagStatusState.APPROACHING ||
                                    after.state == TagStatusState.HERE ||
                                    after.state == TagStatusState.LOGGED) {
                                hitsByTrack.computeIfAbsent(trackId, k -> new ArrayList<>())
                                        .add(new StateHit(tailEnd, trackId, after.state));
                            }
                            prevByTrack.put(trackId, after.state);
                            statusByTrack.put(trackId, after);
                        }
                    }
                }

                // Convert transitions to points
                Map<Integer, List<Point>> approachesByTrack = new LinkedHashMap<>();
                Map<Integer, List<Point>> heresByTrack      = new LinkedHashMap<>();
                Map<Integer, List<Point>> loggedsByTrack    = new LinkedHashMap<>();
                Map<Integer, List<Point>> peaksByTrack      = new LinkedHashMap<>();

                for (Map.Entry<Integer, List<StateHit>> ee : hitsByTrack.entrySet()) {
                    final int trackId = ee.getKey();
                    List<StateHit> hits = ee.getValue();

                    // States → points
                    for (StateHit hit : hits) {
                        Point p = pointForState(Long.toString(hit.ts), tsToRssi, tsList);
                        switch (hit.state) {
                            case APPROACHING: approachesByTrack.computeIfAbsent(trackId, k -> new ArrayList<>()).add(p); break;
                            case HERE:        heresByTrack.computeIfAbsent(trackId, k -> new ArrayList<>()).add(p); break;
                            case LOGGED:      loggedsByTrack.computeIfAbsent(trackId, k -> new ArrayList<>()).add(p); break;
                        }
                    }

                    // Compute peaks for each LOGGED cut using the TagPeakFinder
                    List<Point> peaks = new ArrayList<>();
                    Long lastCut = null; // start of current window
                    for (StateHit hit : hits) {
                        if (hit.state == TagStatusState.LOGGED) {
                            long hi = hit.ts;
                            long lo = (lastCut != null) ? lastCut
                                    : (filtered.isEmpty() ? BASE_TS : filtered.get(0).timestampMs);

                            // window of samples [lo, hi]
                            List<RssiData> window = new ArrayList<>();
                            for (RssiData s : filtered) {
                                if (s.timestampMs >= lo && s.timestampMs <= hi) window.add(s);
                                if (s.timestampMs > hi) break;
                            }

                            String tPeak = detectPeakTs(window, setting.tca_alpha /* use same alpha as EMA */);
                            Point pPeak  = pointForState(tPeak, tsToRssi, tsList);
                            peaks.add(pPeak);
                            lastCut = hi;
                        }
                    }
                    if (!peaks.isEmpty()) peaksByTrack.put(trackId, peaks);
                }

// …then build the AlgoResult with peaksByTrack included
                String algoName = handler.toString();
                algoResults.add(new AlgoResult(
                        algoName, line,
                        approachesByTrack, heresByTrack, loggedsByTrack, peaksByTrack,
                        firstOf(approachesByTrack), firstOf(heresByTrack), firstOf(loggedsByTrack), firstOf(peaksByTrack),
                        filtered
                ));
            }

            // Write one JSON per file using the first algo's series payload (identical across algos here)
            // We embed series from the first AlgoResult's 'seriesPayload'.
            List<RssiData> toWriteSeries = !algoResults.isEmpty() ? algoResults.get(0).seriesPayload : Collections.emptyList();
            String json = buildJson(sampleFile, toWriteSeries, algoResults);
            Path out = Paths.get("result_" + sampleFile + ".json");
            try (BufferedWriter bw = Files.newBufferedWriter(out,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                bw.write(json);
            }
            System.out.println("Wrote " + out.toAbsolutePath());
        }

        System.out.println("Done. Plot with plot_result4.py");
    }

    // ---------- Helpers & data structures ----------
    private static class StateHit {
        final long ts; final int trackId; final TagStatusState state;
        StateHit(long ts, int trackId, TagStatusState state){ this.ts=ts; this.trackId=trackId; this.state=state; }
    }
    private static class Point { final Long tsOffset; final Integer rssi; Point(Long t, Integer r){ tsOffset=t; rssi=r; } }
    private static class LinePoint { final long t; final double v; LinePoint(long t,double v){ this.t=t; this.v=v; } }

    private static class AlgoResult {
        final String name;
        final List<LinePoint> line;
        final Map<Integer, List<Point>> approachesByTrack;
        final Map<Integer, List<Point>> heresByTrack;
        final Map<Integer, List<Point>> loggedsByTrack;
        final Map<Integer, List<Point>> peaksByTrack;
        final Point firstApproach, firstHere, firstLogged, firstPeak;

        // keep a reference to the (filtered + recomputed EMA) series we want to write
        final List<RssiData> seriesPayload;

        AlgoResult(String name,
                   List<LinePoint> line,
                   Map<Integer, List<Point>> A,
                   Map<Integer, List<Point>> H,
                   Map<Integer, List<Point>> L,
                   Map<Integer, List<Point>> P,
                   Point fA, Point fH, Point fL, Point fP,
                   List<RssiData> seriesPayload) {
            this.name = name;
            this.line = line;
            this.approachesByTrack = A;
            this.heresByTrack = H;
            this.loggedsByTrack = L;
            this.peaksByTrack = P;
            this.firstApproach = fA;
            this.firstHere = fH;
            this.firstLogged = fL;
            this.firstPeak = fP;
            this.seriesPayload = seriesPayload;
        }
    }

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
            } catch (Throwable ignore) { }
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

    private static List<RssiData> applyFilters(List<RssiData> in, List<RssiFilter> filters) {
        if (filters == null || filters.isEmpty()) return in;
        List<RssiData> out = new ArrayList<>(in.size());
        outer:
        for (RssiData s : in) {
            for (RssiFilter f : filters) {
                if (!f.shouldAccept(s.timestampMs, s.rssi)) continue outer;
            }
            out.add(s);
        }
        return out;
    }
    private static String detectPeakTs(List<RssiData> samples, float alphaForFinder) {
        if (samples == null || samples.size() < 3) return "X";
        try {
            // Convert to TagData for the finder
            List<TagData> tagDataList = new ArrayList<>(samples.size());
            for (RssiData s : samples) tagDataList.add(new TagData(0, s.timestampMs, s.rssi));

            // Use the same alpha as your recomputed EMA
            Setting s = new Setting();
            s.rssi_averaging_alpha = alphaForFinder;

            com.patriotlogger.logger.logic.TagPeakFinder pf = new com.patriotlogger.logger.logic.TagPeakFinder();
            Object peak = pf.findPeak(tagDataList, s);
            if (peak == null) return "X";
            Long ts = getLongIfExists(peak, "peakTimeMs");
            if (ts == null) ts = getLongIfExists(peak, "timestampMs");
            return (ts == null) ? "X" : Long.toString(ts);
        } catch (Throwable t) { return "X"; }
    }

    private static Long getLongIfExists(Object obj, String field) {
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(field);
            f.setAccessible(true);
            Object v = f.get(obj);
            if (v instanceof Long) return (Long) v;
            if (v instanceof Number) return ((Number) v).longValue();
            return null;
        } catch (Throwable t) { return null; }
    }

    private static Point pointForState(String tsStr, Map<Long, Integer> tsToRssi, List<Long> sortedTs) {
        if (tsStr == null || tsStr.equals("X")) return new Point(null, null);
        Long ts;
        try { ts = Long.parseLong(tsStr); } catch (NumberFormatException e) { return new Point(null, null); }
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

    private static String buildJson(String sampleFile, List<RssiData> samples, List<AlgoResult> algoResults) {
        StringBuilder sb = new StringBuilder(256_000);
        sb.append("{");
        sb.append("\"sampleFile\":").append(q(sampleFile)).append(",");
        sb.append("\"baseTs\":").append(BASE_TS).append(",");

        // series: always RAW + recomputed EMA
        sb.append("\"series\":[");
        for (int i = 0; i < samples.size(); i++) {
            RssiData s = samples.get(i);
            sb.append("{\"t\":").append(s.timestampMs - BASE_TS)
                    .append(",\"rssi\":").append(s.rssi)
                    .append(",\"smoothed\":").append(s.smoothedRssi)
                    .append("}");
            if (i + 1 < samples.size()) sb.append(",");
        }
        sb.append("],");

        // algorithms block
        sb.append("\"algorithms\":{");
        for (int ai = 0; ai < algoResults.size(); ai++) {
            AlgoResult ar = algoResults.get(ai);
            if (ai > 0) sb.append(",");
            sb.append(q(ar.name)).append(":{");

            sb.append("\"tracks\":{");
            int tcount = 0;
            for (Integer trackId : new TreeSet<>(unionKeys(ar.approachesByTrack, ar.heresByTrack, ar.loggedsByTrack, ar.peaksByTrack))) {
                if (tcount++ > 0) sb.append(",");
                sb.append(q(String.valueOf(trackId))).append(":{");
                sb.append("\"APPROACHINGS\":").append(pointsJson(ar.approachesByTrack.get(trackId))).append(",");
                sb.append("\"HERES\":").append(pointsJson(ar.heresByTrack.get(trackId))).append(",");
                sb.append("\"LOGGEDS\":").append(pointsJson(ar.loggedsByTrack.get(trackId))).append(",");
                sb.append("\"PEAKS\":").append(pointsJson(ar.peaksByTrack.get(trackId)));
                sb.append("}");
            }
            sb.append("},");

            sb.append("\"APPROACHING\":").append(pointJson(ar.firstApproach)).append(",");
            sb.append("\"HERE\":").append(pointJson(ar.firstHere)).append(",");
            sb.append("\"LOGGED\":").append(pointJson(ar.firstLogged)).append(",");
            sb.append("\"PEAK\":").append(pointJson(ar.firstPeak)).append(",");

            sb.append("\"line\":[");
            for (int i2 = 0; i2 < ar.line.size(); i2++) {
                LinePoint lp = ar.line.get(i2);
                sb.append("{\"t\":").append(lp.t).append(",\"v\":").append(fmt(lp.v)).append("}");
                if (i2 + 1 < ar.line.size()) sb.append(",");
            }
            sb.append("]}");
        }
        sb.append("}");

        sb.append("}");
        return sb.toString();
    }

    private static Point firstOf(Map<Integer, List<Point>> m) {
        if (m == null) return null;
        for (Integer k : new TreeSet<>(m.keySet())) {
            List<Point> lst = m.get(k);
            if (lst != null && !lst.isEmpty()) return lst.get(0);
        }
        return null;
    }

    private static Set<Integer> unionKeys(Map<Integer, ?>... maps) {
        Set<Integer> out = new HashSet<>();
        for (Map<Integer, ?> m : maps) if (m != null) out.addAll(m.keySet());
        return out;
    }

    private static String pointJson(Point p) {
        if (p == null || p.tsOffset == null || p.rssi == null) return "{\"t\":null,\"rssi\":null}";
        return "{\"t\":" + p.tsOffset + ",\"rssi\":" + p.rssi + "}";
    }
//    private static String pointsJson(List<Point> pts) {
//        StringBuilder sb = new StringBuilder(64 + 32 * (pts == null ? 0 : pts.size()));
//        sb.append("[");
//        if (pts != null) {
//            for (int i = 0; i < pts.size(); i++) {
//                sb.append(pointJson(pts.get(i)));
//                if (i + 1 < pts.size()) sb.append(",");
//            }
//        }
//        sb.append("]");
//        return sb.toString();
//    }

    private static String q(String s) {
        if (s == null) return "null";
        String esc = s.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + esc + "\"";
    }
    private static String fmt(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
        return String.format(Locale.US, "%.3f", d);
    }

    private static Setting cloneSetting(Setting s) {
        Setting c = new Setting();
        c.tca_alpha = s.tca_alpha;
        c.tx_power_at_1m_dbm = s.tx_power_at_1m_dbm;
        c.path_loss_n = s.path_loss_n;
        c.tca_here_meters = s.tca_here_meters;
        c.tca_threshold_sec = s.tca_threshold_sec;
        c.tca_window_size = s.tca_window_size;
        c.tca_min_points = s.tca_min_points;
        c.tca_approach_meters = s.tca_approach_meters;
        c.filter_min_rssi = s.filter_min_rssi;
        c.filter_max_rssi = s.filter_max_rssi;
        c.retain_samples = s.retain_samples;
        c.tagdata_flush_ms = s.tagdata_flush_ms;
        c.abandoned_timeout_ms = s.abandoned_timeout_ms;
        c.sweep_interval_ms = s.sweep_interval_ms;
        return c;
    }

    // ---------- CSV reader that IGNORES any smoothed column and recomputes EMA with given alpha ----------
    private static List<RssiData> readSamplesFromResource(String resourcePath, float alphaForSmoothing) {
        List<RssiData> out = new ArrayList<>();
        try (BufferedReader br = openResourceAsReader(resourcePath)) {
            String line;
            int tsIdx = -1, tagIdx = -1, rssiIdx = -1;

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
                        // NOTE: any "smoothed" column is ignored on purpose
                    }
                    break;
                }
            }
            if (tsIdx < 0 || rssiIdx < 0) throw new IOException("Expected header with at least timestamp and rssi: " + resourcePath);

            final Map<Integer, Float> emaByTag = new HashMap<>();
            final float alpha = alphaForSmoothing;

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

                    Float prev = emaByTag.get(tagId);
                    float ema = (prev == null) ? rssi : (prev + alpha * (rssi - prev));
                    emaByTag.put(tagId, ema);
                    int smoothed = Math.round(ema);

                    out.add(new RssiData(tagId, ts, rssi, smoothed));
                } catch (NumberFormatException ignore) { }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading " + resourcePath, e);
        }
        return out;
    }

    private static BufferedReader openResourceAsReader(String resourcePath) throws IOException {
        InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
        if (is == null) throw new IOException("Resource not found: " + resourcePath);
        return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
    }

    private static List<String> listResourceFiles(String folder) throws IOException {
        InputStream idx = Thread.currentThread().getContextClassLoader().getResourceAsStream(folder + "/_index.txt");
        if (idx == null) {
            Path p = Paths.get("src", "test", "resources", folder);
            if (Files.isDirectory(p)) {
                List<String> out = new ArrayList<>();
                try {
                    Files.walk(p, 1).forEach(fp -> {
                        if (Files.isRegularFile(fp) && fp.toString().toLowerCase(Locale.US).endsWith(".csv")) {
                            Path rel = Paths.get("src", "test", "resources").relativize(fp);
                            out.add(rel.toString().replace('\\', '/'));
                        }
                    });
                } catch (IOException e) { }
                return out;
            }
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(idx, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    out.add(folder + "/" + line);
                }
            }
        }
        return out;
    }

    // ---- tiny utils ----
    private static String pointsJson(List<Point> pts) {
        StringBuilder sb = new StringBuilder(64 + 32 * (pts == null ? 0 : pts.size()));
        sb.append("[");
        if (pts != null) {
            for (int i = 0; i < pts.size(); i++) {
                sb.append(pointJson(pts.get(i)));
                if (i + 1 < pts.size()) sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
