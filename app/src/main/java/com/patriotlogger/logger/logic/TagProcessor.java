package com.patriotlogger.logger.logic;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.patriotlogger.logger.data.Setting;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus;

import java.util.List;

/**
 * Pure logic class for processing tag samples and determining state transitions.
 * This class does not interact with the database or settings directly.
 */
public class TagProcessor {

    public TagProcessor() {}


    public static float computeExponentialMovingAverage(float original, float alpha, float currentValue){
        return (currentValue * alpha) + (original * (1 - alpha));
    }

    @NonNull
    public TagStatus processSample(@NonNull TagStatus statusToProcess, Setting settings,
                                   int rssi,
                                   long nowMs) {

        boolean tagIsVisible = ( rssi > settings.approaching_threshold);
        if ( ! tagIsVisible){
            Log.d("TagProcessor", "Tag is not visible -- below approaching threshold" + settings.approaching_threshold);
            return statusToProcess;
        }

        statusToProcess.lastSeenMs = nowMs;

        if (statusToProcess.state == TagStatus.TagStatusState.FIRST_SAMPLE) {
            statusToProcess.state = TagStatus.TagStatusState.APPROACHING;
            statusToProcess.emaRssi = (float) rssi; // Initialize EMA on the first sample
            Log.i("TagProcessor", statusToProcess.tagId + "->APPROACHING");
        }
        else{
            statusToProcess.emaRssi = computeExponentialMovingAverage(statusToProcess.emaRssi,settings.rssi_averaging_alpha,rssi);
        }

        if (statusToProcess.emaRssi > statusToProcess.peakRssi) {
            statusToProcess.peakRssi = statusToProcess.emaRssi;
            statusToProcess.peakTimeMs = statusToProcess.lastSeenMs;
        }

        if (statusToProcess.state == TagStatus.TagStatusState.APPROACHING) {
            if (statusToProcess.emaRssi >= settings.arrived_threshold) {
                statusToProcess.state = TagStatus.TagStatusState.HERE;
                Log.i("TagProcessor", statusToProcess.tagId + "->HERE");
            }
        }
        else if ( statusToProcess.state == TagStatus.TagStatusState.HERE){
            if (statusToProcess.emaRssi < settings.arrived_threshold){
                Log.i("TagProcessor", statusToProcess.tagId + "->LOGGED");
                statusToProcess.state = TagStatus.TagStatusState.LOGGED;
                statusToProcess.exitTimeMs = statusToProcess.lastSeenMs;
            }
        }
        return statusToProcess;
    }

//    @Nullable
//    public TagStatus processTagExit(@NonNull TagStatus statusToProcess,
//                                    @Nullable List<TagData> samplesForPass,
//                                    long currentTimeMs,
//                                    long lossTimeoutMs) {
//
//        if ((currentTimeMs - statusToProcess.lastSeenMs) >= lossTimeoutMs) {
//            statusToProcess.state = TagStatus.TagStatusState.LOGGED;
//            statusToProcess.exitTimeMs = statusToProcess.lastSeenMs;
//
//            if (samplesForPass != null && !samplesForPass.isEmpty()) {
//                TagData peakSample = null;
//                // Find the sample with the highest recorded (and now smoothed) RSSI.
//                // TagData.rssi now stores the EMA value.
//                float maxRssi = -500f; // Use float for comparison
//                for (TagData sample : samplesForPass) {
//                    if (sample.rssi > maxRssi) {
//                        maxRssi = sample.rssi;
//                        peakSample = sample;
//                    }
//                }
//                if (peakSample != null) {
//                    statusToProcess.peakTimeMs = peakSample.timestampMs;
//                } else {
//                    // Fallback if no peak is found
//                    statusToProcess.peakTimeMs = statusToProcess.lastSeenMs;
//                }
//            } else {
//                // Fallback if there are no samples
//                statusToProcess.peakTimeMs = statusToProcess.lastSeenMs;
//            }
//            return statusToProcess;
//        }
//        return null;
//    }
}
