package com.patriotlogger.logger.util;

import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CsvExporter {

    private static final String CSV_HEADER = "track_id,tag_id,friendly_name,pass_state,pass_entry_time_ms,pass_peak_time_ms,pass_exit_time_ms,pass_lowest_rssi,split_time_ms,sample_timestamp_ms,sample_rssi";

    /**
     * Generates a CSV string from TagStatus and TagData.
     * Each row in the CSV represents one TagData sample, with associated TagStatus info repeated.
     *
     * @param tagStatuses      List of TagStatus objects (typically all logged passes).
     * @param samplesByTrackId A map where the key is trackId and value is a list of TagData samples for that track.
     * @param gunTimeMs        The gun time in milliseconds, used to calculate split times.
     * @return A string representing the CSV data.
     */
    public static String generateCsvString(List<TagStatus> tagStatuses, Map<Integer, List<TagData>> samplesByTrackId, long gunTimeMs) {
        StringBuilder csvBuilder = new StringBuilder();
        csvBuilder.append(CSV_HEADER).append("\n");

        if (tagStatuses == null || samplesByTrackId == null) {
            return csvBuilder.toString(); // Return header only if data is null
        }

        for (TagStatus status : tagStatuses) {
            List<TagData> samples = samplesByTrackId.get(status.trackId);
            if (samples == null || samples.isEmpty()) {
                // If retain_samples was false, or no samples for this pass, skip or add a single line for status?
                // Current interpretation: Only include rows for actual samples.
                // If you want to include TagStatus even without samples, this part needs adjustment.
                continue; 
            }

            String friendlyName = status.friendlyName != null ? status.friendlyName : "";
            // Sanitize friendlyName: replace commas with semicolons and remove newlines for basic CSV safety.
            friendlyName = friendlyName.replace(",", ";").replace("\n", " ");

            String passState = status.state != null ? status.state.name() : "UNKNOWN";
            
            long splitTimeMs = 0;
            if (status.peakTimeMs > 0 && gunTimeMs > 0) {
                splitTimeMs = status.peakTimeMs - gunTimeMs;
            } else {
                splitTimeMs = 0; // Or some other indicator for undefined split
            }

            for (TagData sample : samples) {
                csvBuilder.append(status.trackId).append(",");
                csvBuilder.append(status.tagId).append(",");
                csvBuilder.append(friendlyName).append(",");
                csvBuilder.append(passState).append(",");
                csvBuilder.append(status.entryTimeMs).append(",");
                csvBuilder.append(status.peakTimeMs).append(",");
                csvBuilder.append(status.exitTimeMs).append(",");
                csvBuilder.append(String.format(Locale.US, "%.2f", status.highestRssi)).append(",");
                csvBuilder.append(splitTimeMs).append(",");
                csvBuilder.append(sample.timestampMs).append(",");
                csvBuilder.append(sample.rssi).append("\n");
            }
        }
        return csvBuilder.toString();
    }
}
