package com.patriotlogger.logger.test;

import com.patriotlogger.logger.logic.RssiData;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/** Reads CSV files with columns: timestamp,tagid,rssi,smoothedrssi */
public class DataParser {

    public static List<File> listCsvFiles(String directory) {
        File dir = new File(directory);
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".csv"));
        List<File> out = new ArrayList<>();
        if (files != null) {
            for (File f : files) out.add(f);
        }
        return out;
    }

    public static List<RssiData> readSamples(File csv) throws Exception {
        List<RssiData> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(csv))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (first) { // header
                    first = false;
                    continue;
                }
                // timestamp,tagid,rssi,smoothedrssi
                String[] parts = line.split(",");
                if (parts.length < 3) continue;

                long ts = parseLongSafe(parts[0]);
                int tagId = parseIntSafe(parts[1], 0);
                int rssi = parseIntSafe(parts[2], -127);
                int smoothed = (parts.length >= 4) ? parseIntSafe(parts[3], rssi) : rssi;

                // clip obvious outliers (keep it sane for RSSI)
                if (rssi > 0 || rssi < -127) continue;

                RssiData d = new RssiData(tagId,ts,rssi,0);

                out.add(d);
            }
        }
        return out;
    }

    private static long parseLongSafe(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; }
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}
