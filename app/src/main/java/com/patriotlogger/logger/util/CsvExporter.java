package com.patriotlogger.logger.util;

import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Generates two separate CSV files from TagStatus and TagData lists.
 * Headers are manually defined for stability and clarity.
 */
public class CsvExporter {

    // --- Manually define headers here ---
    private static final String SPLITS_CSV_HEADER = "trackId,tagId,friendlyName,state,entryTimeMs,peakTimeMs,exitTimeMs,highestRssi";
    private static final String DATA_CSV_HEADER = "dataId,trackId,tagId,timestampMs,rssi";

    /**
     * A data class to hold the results of a CSV generation for a single file.
     */
    public static class CsvFile {
        public final String filename;
        public final String content;
        public final int rowCount;

        CsvFile(String filename, String content, int rowCount) {
            this.filename = filename;
            this.content = content;
            this.rowCount = rowCount;
        }
    }

    /**
     * Generates CSV data for both TagStatus (splits) and TagData (samples).
     *
     * @param statuses The list of TagStatus objects to export.
     * @param data     The list of TagData objects to export.
     * @return A list containing two CsvFile objects, one for splits and one for data.
     */
    public static List<CsvFile> generateCsvFiles(List<TagStatus> statuses, List<TagData> data) {
        List<CsvFile> files = new ArrayList<>();
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());

        // Generate Splits (TagStatus) CSV
        String splitsFilename = "patriotlogger_splits_" + timestamp + ".csv";
        String splitsContent = generateSplitsCsv(statuses);
        int splitsRowCount = (statuses != null) ? statuses.size() : 0;
        files.add(new CsvFile(splitsFilename, splitsContent, splitsRowCount));

        // Generate Data (TagData) CSV
        String dataFilename = "patriotlogger_data_" + timestamp + ".csv";
        String dataContent = generateDataCsv(data);
        int dataRowCount = (data != null) ? data.size() : 0;
        files.add(new CsvFile(dataFilename, dataContent, dataRowCount));

        return files;
    }

    /**
     * Generates the CSV string for TagStatus data.
     */
    private static String generateSplitsCsv(List<TagStatus> statuses) {
        StringBuilder csvBuilder = new StringBuilder();
        csvBuilder.append(SPLITS_CSV_HEADER).append("\n");

        if (statuses == null || statuses.isEmpty()) {
            return csvBuilder.toString(); // Return header only
        }

        for (TagStatus status : statuses) {
            csvBuilder.append(status.trackId).append(",");
            csvBuilder.append(status.tagId).append(",");
            csvBuilder.append(sanitize(status.friendlyName)).append(",");
            csvBuilder.append(status.state != null ? status.state.name() : "").append(",");
            csvBuilder.append(status.entryTimeMs).append(",");
            csvBuilder.append(status.peakTimeMs).append(",");
            csvBuilder.append(status.exitTimeMs).append(",");
            csvBuilder.append(status.highestRssi).append("\n");
        }
        return csvBuilder.toString();
    }

    /**
     * Generates the CSV string for TagData.
     */
    private static String generateDataCsv(List<TagData> data) {
        StringBuilder csvBuilder = new StringBuilder();
        csvBuilder.append(DATA_CSV_HEADER).append("\n");

        if (data == null || data.isEmpty()) {
            return csvBuilder.toString(); // Return header only
        }

        for (TagData sample : data) {
            csvBuilder.append(sample.dataId).append(",");
            csvBuilder.append(sample.trackId).append(",");
            csvBuilder.append(sample.timestampMs).append(",");
            csvBuilder.append(sample.rssi).append("\n");
        }
        return csvBuilder.toString();
    }

    /**
     * A simple CSV sanitizer for string fields.
     * If a string contains a comma or a double quote, it will be enclosed in double quotes,
     * and any existing double quotes will be escaped by doubling them.
     */
    private static String sanitize(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
