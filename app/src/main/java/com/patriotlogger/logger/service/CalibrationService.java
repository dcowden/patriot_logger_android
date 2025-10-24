package com.patriotlogger.logger.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.patriotlogger.logger.data.Repository;

public class CalibrationService extends Service {

    public static final String ACTION_START = "com.patriotlogger.logger.CALIBRATION_START";
    public static final String ACTION_STOP  = "com.patriotlogger.logger.CALIBRATION_STOP";
    private static final String TAG = "CalibrationService";

    private Repository repository;

    @Override
    public void onCreate() {
        super.onCreate();
        repository = Repository.get(getApplicationContext());
        Log.i(TAG, "CalibrationService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null) ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            repository.setSavingEnabled(false);
            repository.clearCalibrationRssi(); // start fresh
            startService(new Intent(this, BleScannerService.class).setAction(BleScannerService.ACTION_START));
            Log.i(TAG, "Calibration started");
            return START_STICKY;
        } else if (ACTION_STOP.equals(action)) {
            startService(new Intent(this, BleScannerService.class).setAction(BleScannerService.ACTION_STOP));
            repository.setSavingEnabled(true);
            Log.i(TAG, "Calibration stopped");
            stopSelf();
            return START_NOT_STICKY;
        } else {
            // No-op; keep service lean. UI should talk to Repository for LiveData.
            return START_NOT_STICKY;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "CalibrationService destroyed");
    }
}
