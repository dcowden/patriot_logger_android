package com.patriotlogger.logger.data;


import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.List;


public class CalibrationTagDataBus {

    private static final int MAX_BUFFER = 10_000; // tune

    // Holds the list of pending TagData objects
    private static final MutableLiveData<List<CalibrationSample>> liveData =
            new MutableLiveData<>(new ArrayList<>());

    public static LiveData<List<CalibrationSample>> getData() {
        return liveData;
    }

    // Called by service to add a new TagData
    public static synchronized void append(CalibrationSample tag) {
        List<CalibrationSample> current = liveData.getValue();
        if (current == null) {
            current = new ArrayList<>();
        }
        current.add(tag);
        if (current.size() > MAX_BUFFER) {
            // drop oldest to prevent OOM
            current.remove(0);
        }
        // Post the updated list so observers are notified
        liveData.postValue(current);
    }

    // Called by consumers to retrieve & clear all pending items
    public static synchronized List<CalibrationSample> consumeAll() {
        List<CalibrationSample> current = liveData.getValue();
        if (current == null || current.isEmpty()) {
            return new ArrayList<>();
        }
        List<CalibrationSample> copy = new ArrayList<>(current);
        current.clear();
        liveData.postValue(current); // notify observers the list is now empty
        return copy;
    }
}
