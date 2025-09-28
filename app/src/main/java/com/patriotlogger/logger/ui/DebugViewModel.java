package com.patriotlogger.logger.ui;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.TagStatus;
import java.util.List;

public class DebugViewModel extends AndroidViewModel {

    private final Repository repository;
    private final LiveData<List<TagStatus>> allTagStatuses;

    public DebugViewModel(@NonNull Application application) {
        super(application);
        repository = Repository.get(application);
        // Use the existing liveTagStatuses() method from Repository
        allTagStatuses = repository.liveTagStatuses(); 
    }

    public LiveData<List<TagStatus>> getAllTagStatuses() {
        return allTagStatuses;
    }

    // Renamed to match the broader scope of clearing all data, as implemented in Repository
    public void clearAllRoomData() {
        // Use the existing clearAll() method from Repository
        repository.clearAll(); 
    }
}
