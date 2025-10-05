package com.patriotlogger.logger.util;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;

/**
 * A LiveData that samples the source LiveData at a specified interval.
 * It emits the most recent value from the source at a rate no faster than the given duration.
 *
 * @param <T> The type of data held by this instance.
 */
public class ThrottledLiveData<T> extends MediatorLiveData<T> {

    private T lastValue;
    private boolean hasPendingValue = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean timerRunning = false;
    private final long throttleMs;

    public ThrottledLiveData(LiveData<T> source, long throttleMs) {
        this.throttleMs = throttleMs;

        addSource(source, value -> {
            lastValue = value;
            hasPendingValue = true;
            if (!timerRunning) {
                startTimer();
            }
        });
    }

    private void startTimer() {
        timerRunning = true;
        handler.postDelayed(() -> {
            if (hasPendingValue) {
                setValue(lastValue);
                hasPendingValue = false;
                startTimer(); // Reschedule the timer to maintain regular updates
            } else {
                timerRunning = false; // Stop the timer if there were no new values
            }
        }, throttleMs);
    }
}
