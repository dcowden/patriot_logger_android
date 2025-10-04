package com.patriotlogger.logger.logic;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus;

import java.util.List;

/**
 * Pure logic class for processing tag samples and determining state transitions.
 * This class does not interact with the database or settings directly.
 */
public class TagProcessor {
    private static final String TAG = "TagProcessor";

    public TagProcessor() {}

    /**
     * Processes a single RSSI sample for a given, existing tag status.
     * Assumes statusToProcess is already initialized with tagId, entryTimeMs, etc.
     * Updates lastSeenMs, exitTimeMs, lowestRssi, and state based on the new sample.
     *
     * @param statusToProcess  The non-null, current status of the tag, prepared by the caller.
     * @param rssi             The RSSI value of the current sample.
     * @param nowMs            The timestamp of the current sample.
     * @param arrivedThreshold The RSSI threshold to transition from APPROACHING to HERE.
     * @return The modified statusToProcess object.
     */
    @NonNull 
    public TagStatus processSample(@NonNull TagStatus statusToProcess,
                                   int rssi,
                                   long nowMs,
                                   int arrivedThreshold) {
        // Caller is responsible for providing a fully initialized statusToProcess
        // Initialization logic (new pass, friendly name) is handled by the caller.
        if ( statusToProcess.state == TagStatus.TagStatusState.FIRST_SAMPLE){
            Log.d(TAG, "processSample: Tag " + statusToProcess.tagId + "first_sammple->approaching"     );
            statusToProcess.state = TagStatus.TagStatusState.APPROACHING;
        }
        statusToProcess.lastSeenMs = nowMs;
        Log.d(TAG, "processSample: Tag " + statusToProcess.tagId +
                " (trackId: " + statusToProcess.trackId + ") RSSI: " + rssi + " threshold=" + arrivedThreshold );

        statusToProcess.exitTimeMs = nowMs;
        if (rssi > statusToProcess.highestRssi) {
            statusToProcess.highestRssi = rssi;
        }

        if (statusToProcess.state == TagStatus.TagStatusState.APPROACHING) {
            if (rssi >= arrivedThreshold) {
                statusToProcess.state = TagStatus.TagStatusState.HERE;

            }
        }
        return statusToProcess;
    }

    /**
     * Processes a tag for potential exit (logging) based on timeout.
     * Calculates peak time if the tag is logged.
     *
     * @param statusToProcess  The current status of the tag to check for logging.
     * @param samplesForPass   A list of TagData samples for this specific pass, used to determine peak RSSI time.
     * @param currentTimeMs    The current timestamp to check against for timeout.
     * @param lossTimeoutMs    The duration after which a tag is considered lost and logged.
     * @return The modified TagStatus if it was set to LOGGED, otherwise null (if not timed out).
     */
    @Nullable
    public TagStatus processTagExit(@NonNull TagStatus statusToProcess, 
                                    @Nullable List<TagData> samplesForPass, 
                                    long currentTimeMs, 
                                    long lossTimeoutMs) {

        if ((currentTimeMs - statusToProcess.lastSeenMs) >= lossTimeoutMs) {
            Log.d(TAG,"Process Exit--start");
            statusToProcess.state = TagStatus.TagStatusState.LOGGED;
            statusToProcess.exitTimeMs = statusToProcess.lastSeenMs; 
            Log.d(TAG, "processTagExit: Tag " + statusToProcess.tagId + 
                       " (trackId: " + statusToProcess.trackId + ") -> LOGGED due to loss timeout., currentTimeMs=" +
                    currentTimeMs + ", lastSeenMs=" + statusToProcess.lastSeenMs
                    + ", lossTimeoutMs=" + lossTimeoutMs + " actualCurrentTimeMillis = " + System.currentTimeMillis() );

            if (samplesForPass != null && !samplesForPass.isEmpty()) {
                TagData peakSample = null;
                int maxRssi = Integer.MIN_VALUE;
                for (TagData sample : samplesForPass) {
                    if (sample.rssi > maxRssi) {
                        maxRssi = sample.rssi;
                        peakSample = sample;
                    }
                }
                if (peakSample != null) {
                    statusToProcess.peakTimeMs = peakSample.timestampMs;
                    Log.d(TAG, "processTagExit: Set peakTimeMs for trackId " + statusToProcess.trackId + 
                               " to " + statusToProcess.peakTimeMs + " (RSSI: " + maxRssi + ")");
                } else {
                    statusToProcess.peakTimeMs = statusToProcess.lastSeenMs; 
                    Log.w(TAG, "processTagExit: No valid peak sample for trackId: " + statusToProcess.trackId + ". Defaulting peakTimeMs.");
                }
            } else {
                statusToProcess.peakTimeMs = statusToProcess.lastSeenMs; 
                Log.w(TAG, "processTagExit: No TagData samples for trackId: " + statusToProcess.trackId + ". Defaulting peakTimeMs.");
            }
            Log.d(TAG,"Process Exit--end");
            return statusToProcess;
        }
        return null; 
    }
}
