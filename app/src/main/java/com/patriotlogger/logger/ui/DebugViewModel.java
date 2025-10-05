package com.patriotlogger.logger.ui;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.patriotlogger.logger.data.DebugTagData; // Import the new POJO
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.RepositoryVoidCallback;

import java.util.List;

public class DebugViewModel extends AndroidViewModel {

    private final Repository repository;
    private final LiveData<List<DebugTagData>> allDebugTagData; // Changed from TagStatus

    public DebugViewModel(@NonNull Application application) {
        super(application);
        repository = Repository.get(application);
        // Use the new LiveData method from Repository for the debug screen
        allDebugTagData = repository.getLiveAllDebugTagData(); // Changed method call
    }

    public LiveData<List<DebugTagData>> getAllDebugTagData() { // Changed getter
        return allDebugTagData;
    }

    public void clearAllRoomData() {
        repository.clearAllData(true,new RepositoryVoidCallback() {
            @Override
            public void onSuccess() {
                Log.i("DebugViewModel", "All Room data cleared successfully.");
            }

            @Override
            public void onError(Exception e) {
                Log.e("DebugViewModel", "Error clearing Room data", e);
            }
        });
    }
}



