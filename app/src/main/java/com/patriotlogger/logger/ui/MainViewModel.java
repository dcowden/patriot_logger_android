package com.patriotlogger.logger.ui;

import android.app.Application;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.patriotlogger.logger.data.RaceContext;
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.TagStatus;

import java.util.List;

public class MainViewModel extends AndroidViewModel {
    private final Repository repo;
    private final LiveData<List<TagStatus>> statuses;
    private final LiveData<RaceContext> contextLive;

    public static final long LOSS_TIME_MS = 1000L;
    public static final float Q = 0.001f, R = 0.1f, P = 0.05f;

    private final MutableLiveData<Long> clockNow = new MutableLiveData<>(0L);

    public MainViewModel(@NonNull Application application) {
        super(application);
        repo = Repository.get(application);
        statuses = repo.liveTagStatuses();
        contextLive = repo.liveContext();
    }

    public LiveData<List<TagStatus>> getStatuses() { return statuses; }
    public LiveData<RaceContext> getContext() { return contextLive; }
    public LiveData<Long> clockNow() { return clockNow; }
    public void setClockNow(long ms) { clockNow.postValue(ms); }

    public String headerText(RaceContext c) {
        if (c == null) return "";
        return (TextUtils.isEmpty(c.eventName) ? "" : c.eventName) +
                " • " + (TextUtils.isEmpty(c.raceName) ? "" : c.raceName) +
                " • " + (TextUtils.isEmpty(c.splitName) ? "" : c.splitName);
    }
}
