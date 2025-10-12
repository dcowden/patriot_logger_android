package com.patriotlogger.logger.data;


import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.List;

public class CalibrationTagDataBus {

    // Holds the list of pending TagData objects
    private static final MutableLiveData<List<TagData>> liveData =
            new MutableLiveData<>(new ArrayList<>());

    public static LiveData<List<TagData>> getData() {
        return liveData;
    }

    // Called by service to add a new TagData
    public static synchronized void append(TagData tag) {
        List<TagData> current = liveData.getValue();
        if (current == null) {
            current = new ArrayList<>();
        }
        current.add(tag);
        // Post the updated list so observers are notified
        liveData.postValue(current);
    }

    // Called by consumers to retrieve & clear all pending items
    public static synchronized List<TagData> consumeAll() {
        List<TagData> current = liveData.getValue();
        if (current == null || current.isEmpty()) {
            return new ArrayList<>();
        }
        List<TagData> copy = new ArrayList<>(current);
        current.clear();
        liveData.postValue(current); // notify observers the list is now empty
        return copy;
    }
}
