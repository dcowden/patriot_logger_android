package com.patriotlogger.logger.ui;

import android.app.Application;
import android.util.Log; // For logging callback results

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
// Import callback if you use it from Repository
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.RepositoryVoidCallback;
import com.patriotlogger.logger.data.TagStatus;
import java.util.List;

public class DebugViewModel extends AndroidViewModel {

    private final Repository repository;
    private final LiveData<List<TagStatus>> allTagStatuses;public DebugViewModel(@NonNull Application application) {
        super(application);
        repository = Repository.get(application);
        // Use the new LiveData method from Repository
        allTagStatuses = repository.getAllTagStatuses();
    }

    public LiveData<List<TagStatus>> getAllTagStatuses() {
        return allTagStatuses;
    }

    public void clearAllRoomData() {
        // Use the new async clear method from Repository.
        // Optionally handle success/error with a callback if needed for UI feedback.
        repository.clearAllData(new RepositoryVoidCallback() {
            @Override
            public void onSuccess() {
                Log.i("DebugViewModel", "All Room data cleared successfully.");
                // You could post a success message to a LiveData here if UI needs to know
            }

            @Override
            public void onError(Exception e) {
                Log.e("DebugViewModel", "Error clearing Room data", e);
                // You could post an error message to a LiveData here
            }
        });
    }
}

