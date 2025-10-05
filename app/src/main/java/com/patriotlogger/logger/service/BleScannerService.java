package com.patriotlogger.logger.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper; // For posting to main thread if LiveData observeForever is used directly
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.Observer;   // For observing settings

import com.patriotlogger.logger.R;
import com.patriotlogger.logger.data.AppDatabase;
import com.patriotlogger.logger.data.RaceContext;
import com.patriotlogger.logger.data.RaceContextDao;
import com.patriotlogger.logger.data.Racer;
import com.patriotlogger.logger.data.RacerDao;
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.Setting; // Import Setting
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagDataDao;
import com.patriotlogger.logger.data.TagStatus;
import com.patriotlogger.logger.data.TagStatusDao;
import com.patriotlogger.logger.logic.TagProcessor;
// SettingsManager is removed

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BleScannerService extends Service {
    private static final String TAG_SERVICE = "BleScannerService";

    public static final String ACTION_START = "com.patriotlogger.logger.START_SCAN";
    public static final String ACTION_STOP = "com.patriotlogger.logger.STOP_SCAN";

    public static final String TAG_PREFIX = "Patriot";
    private static final String CHANNEL_ID = "scan_channel";
    private static final int NOTIF_ID = 1001;

    private static final long LOST_TRACKER_INTERVAL_MS = 500;
    private static final long LOST_TRACKER_TIMEOUT_MS = 1000;
    private static final long REPORT_DELAY_MS = 0L;

    private HandlerThread workerThread;
    private Handler worker;


    private BluetoothLeScanner scanner;
    private Repository repository;
    private AppDatabase db;
    private TagStatusDao tagStatusDao;
    private RacerDao racerDao;
    private TagDataDao tagDataDao;
    private RaceContextDao raceContextDao;

    private TagProcessor tagProcessor;

    // To hold current settings values, fetched from Repository
    private volatile int currentArrivedThreshold = Setting.DEFAULT_ARRIVED_THRESHOLD; // Default
    private volatile boolean currentRetainSamples = Setting.DEFAULT_RETAIN_SAMPLES;   // Default

    // prevent accessing the same tag at the same time
    private final Map<Integer, Object> tagLockMap = new ConcurrentHashMap<>();

    private final Map<Integer, TagStatus.TagStatusState> lastNotifiedStateForTrack = new ConcurrentHashMap<>();
    private Observer<Setting> settingsObserver; // To remove observer later


    @Override
    public void onCreate() {
        super.onCreate();
        repository = Repository.get(getApplicationContext());
        db = repository.getDatabase();

        tagStatusDao = db.tagStatusDao();
        racerDao = db.racerDao();
        tagDataDao = db.tagDataDao();
        raceContextDao = db.raceContextDao();

        tagProcessor = new TagProcessor();

        workerThread = new HandlerThread("ble-data-processor");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());


        // Fetch initial settings and observe for changes
        // Settings are needed on the worker thread, but LiveData observation needs a Looper.
        // We can observeForever and post updates to our volatile fields.
        settingsObserver = setting -> {
            if (setting != null) {
                Log.i(TAG_SERVICE, "Settings updated: Threshold=" + setting.arrived_threshold + ", Retain=" + setting.retain_samples);
                currentArrivedThreshold = setting.arrived_threshold;
                currentRetainSamples = setting.retain_samples;
            } else {
                // This might happen if DB is cleared and defaults haven't repopulated LiveData yet.
                // Fallback to defaults. Repository's getLiveConfig should handle initialization.
                Log.w(TAG_SERVICE, "Received null settings from LiveData, using defaults.");
                currentArrivedThreshold = Setting.DEFAULT_ARRIVED_THRESHOLD;
                currentRetainSamples = Setting.DEFAULT_RETAIN_SAMPLES;
            }
        };
        // LiveData needs to be observed from a thread with a Looper.
        // The service's main thread is fine for observeForever.
        new Handler(Looper.getMainLooper()).post(() ->
                repository.getLiveConfig().observeForever(settingsObserver)
        );


        createChannel();
        startForeground(NOTIF_ID, buildNotif("Scanner Initializing..."));
        worker.postDelayed(this::performSweepRunnable, LOST_TRACKER_INTERVAL_MS);
        Log.i(TAG_SERVICE, "Service Created and Initialized");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null) ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            notifyLine("Scanner Stopping...");
            Log.i(TAG_SERVICE, "Received stop command");
            stopSelf();
            return START_NOT_STICKY;
        }
        notifyLine("Scanner Starting...");
        Log.i(TAG_SERVICE, "Received start command");
        startScan();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopScan();
        if (settingsObserver != null) {
            // Remove observer from the same thread it was added
            new Handler(Looper.getMainLooper()).post(() ->
                    repository.getLiveConfig().removeObserver(settingsObserver)
            );
        }
        if (workerThread != null) {
            worker.removeCallbacksAndMessages(null);
            workerThread.quitSafely();
        }

        lastNotifiedStateForTrack.clear();
        Log.i(TAG_SERVICE, "Service Destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startScan() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (bm != null) ? bm.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG_SERVICE, "Bluetooth disabled or not available");
            notifyLine("Bluetooth disabled. Scan failed.");
            stopSelf();
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            Log.w(TAG_SERVICE, "No BLE scanner available");
            notifyLine("BLE Scanner not available. Scan failed.");
            stopSelf();
            return;
        }

        ScanSettings scanSettings = new ScanSettings.Builder() // Renamed variable
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(REPORT_DELAY_MS)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG_SERVICE, "Missing BLUETOOTH_SCAN permission for startScan");
                notifyLine("Permission missing. Scan failed.");
                stopSelf();
                return;
            }
        }
        scanner.startScan(null, scanSettings, scanCallback);
        notifyLine("Scanning for Runners...");
        Log.i(TAG_SERVICE, "Scan effectively started");
    }

    private void stopScan() {
        if (scanner != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG_SERVICE, "Missing BLUETOOTH_SCAN permission for stopScan. Cannot stop scan.");
                    // Cannot call stopScan without permission on Android S+
                    // Consider how to handle this - perhaps the service shouldn't have started.
                    return; // Or try to stop anyway and catch SecurityException, though API docs say permission is required.
                }
            }
            try {
                scanner.stopScan(scanCallback);
                Log.i(TAG_SERVICE, "Scan stopped via API");
                notifyLine("Scanning Paused");
            } catch (SecurityException se) {
                Log.e(TAG_SERVICE, "SecurityException stopping scan: " + se.getMessage(), se);
            } catch (Exception e) {
                Log.e(TAG_SERVICE, "Error stopping scan: " + e.getMessage(), e);
            }
            scanner = null;
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleScanResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) {
                handleScanResult(r);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG_SERVICE, "Scan failed with error code: " + errorCode);
            notifyLine("Scan failed: " + errorCode);
        }
    };

    @Nullable
    private Integer getTagIdIfShouldHandle(ScanRecord sr) {
        //TODO: super brittle. and also should use manufacturer ADV data
        if (sr == null) return null;
        String deviceName = sr.getDeviceName();
        if (deviceName != null && deviceName.startsWith(TAG_PREFIX)) {
            try {
                return Integer.parseInt(deviceName.substring(TAG_PREFIX.length() + 1));
            } catch (NumberFormatException e) {
                Log.w(TAG_SERVICE, "Failed to parse tagId from deviceName: " + deviceName, e);
                return null;
            }
        }
        return null;
    }

    private void handleScanResult(ScanResult result) {
        Integer tagId = getTagIdIfShouldHandle(result.getScanRecord());
        if (tagId == null) return;

        int rssi = result.getRssi();
        long now = System.currentTimeMillis();
        Log.d(TAG_SERVICE, "Received scan result for tagId: " + tagId );
        worker.post(() -> processRawSample(tagId, rssi, now));
        Log.d(TAG_SERVICE, "Finished posting to queu for tagId: " + tagId );
    }

    // This method runs on the 'worker' HandlerThread
    private void processRawSample(int tagId, int rssi, long nowMs) {

        Object tagLock = tagLockMap.computeIfAbsent(tagId, k -> new Object());
        synchronized (tagLock){
            TagStatus statusToProcess;
            TagStatus currentKnownStatus = tagStatusDao.getActiveStatusForTagIdSync(tagId);
            Racer racer = racerDao.getSync(tagId);
            String friendlyName = (racer != null && racer.name != null && !racer.name.isEmpty()) ? racer.name : null;

            if (currentKnownStatus == null) {
                statusToProcess = new TagStatus();
                statusToProcess.tagId = tagId;
                statusToProcess.entryTimeMs = nowMs;
                statusToProcess.state = TagStatus.TagStatusState.FIRST_SAMPLE;
                statusToProcess.highestRssi = (float) rssi;
                if (friendlyName != null) {
                    statusToProcess.friendlyName = friendlyName;
                }
                Log.d(TAG_SERVICE, "New pass for tagId: " + tagId + ". Initializing new TagStatus. lastseen=" + nowMs);
            } else {
                statusToProcess = currentKnownStatus;
                if ((statusToProcess.friendlyName == null || statusToProcess.friendlyName.isEmpty()) &&
                        (friendlyName != null && !friendlyName.isEmpty())) {
                    statusToProcess.friendlyName = friendlyName;
                }
            }
            statusToProcess.friendlyName = TAG_PREFIX + "-" + tagId;
            Log.d(TAG_SERVICE, "Update tagId: " + tagId + ". Initializing new TagStatus. lastseen=" + nowMs);
            statusToProcess.lastSeenMs = nowMs;

            // Use the cached settings values
            int arrivedThreshold = this.currentArrivedThreshold;
            Log.d(TAG_SERVICE,"Start processSample");
            TagStatus processedStatus = tagProcessor.processSample(
                    statusToProcess,
                    rssi,
                    nowMs,
                    arrivedThreshold
            );
            Log.d(TAG_SERVICE,"End processSample");
            processedStatus.trackId = (int) tagStatusDao.upsertSync(processedStatus);
            handleProcessingSideEffects(processedStatus, nowMs, rssi);
        }

    }

    // This method runs on the 'worker' HandlerThread
    private void handleProcessingSideEffects(@NonNull TagStatus processedStatus, long sampleTimestampMs, int sampleRssi) {
        if (processedStatus.trackId != 0 && processedStatus.isInProcess()) {
            TagData newSample = new TagData(processedStatus.trackId, sampleTimestampMs, sampleRssi);
            tagDataDao.insert(newSample);
        }

        TagStatus.TagStatusState lastNotified = lastNotifiedStateForTrack.get(processedStatus.trackId);
        if (processedStatus.trackId != 0 && !processedStatus.state.equals(lastNotified)) {
            String message = null;
            if (processedStatus.state == TagStatus.TagStatusState.APPROACHING) {
                if (lastNotified == null) {
                    message = displayLabel(processedStatus) + " approaching...";
                }
            } else if (processedStatus.state == TagStatus.TagStatusState.HERE) {
                message = displayLabel(processedStatus) + " passing...";
            }
            if (message != null) {
                notifyLine(message);
                lastNotifiedStateForTrack.put(processedStatus.trackId, processedStatus.state);
            }
        }
    }

    // This method runs on the 'worker' HandlerThread
    private void performSweepRunnable() {
        Log.d(TAG_SERVICE, "Performing loss sweep START ");
        long now = System.currentTimeMillis();

        List<TagStatus> activeStatuses = tagStatusDao.getAllSync();
        if (activeStatuses == null) activeStatuses = new ArrayList<>();

        RaceContext raceContext = raceContextDao.latestSync();
        long gunTimeMs = (raceContext != null) ? raceContext.gunTimeMs : 0L;

        // Use the cached settings values
        boolean retainSamplesSetting = this.currentRetainSamples;

        for (TagStatus status : activeStatuses) {
            int tagId = status.tagId;
            Object tagLock = tagLockMap.computeIfAbsent(tagId, k -> new Object());
            synchronized (tagLock){
                Log.d(TAG_SERVICE, "Inside tagLock" + System.currentTimeMillis());
                if (status.isInProcess()) {
                    long s = System.currentTimeMillis();
                    List<TagData> samples = tagDataDao.getSamplesForTrackIdSync(status.trackId);
                    Log.d(TAG_SERVICE, "getSamplesForTrackIdSync took " + (System.currentTimeMillis() - s) + " ms");
                    s = System.currentTimeMillis();
                    TagStatus loggedStatus = tagProcessor.processTagExit(status, samples, now, LOST_TRACKER_TIMEOUT_MS);
                    Log.d(TAG_SERVICE, "processTagExit took " + (System.currentTimeMillis() - s) + " ms");
                    if (loggedStatus != null) {
                        tagStatusDao.upsert(loggedStatus);
                        lastNotifiedStateForTrack.remove(loggedStatus.trackId);

                        long splitMs = 0;
                        if (gunTimeMs > 0 && loggedStatus.peakTimeMs > 0) {
                            splitMs = loggedStatus.peakTimeMs - gunTimeMs;
                        }
                        notifyLine(displayLabel(loggedStatus) + " logged " + formatMmSs(splitMs));

                        if (!retainSamplesSetting) {
                            Log.i(TAG_SERVICE, "retain_samples is false. Deleting samples for trackId: " + loggedStatus.trackId);
                            tagDataDao.deleteSamplesForTrackIdSync(loggedStatus.trackId);
                        }
                    }
                }
            }

        }
        Log.d(TAG_SERVICE, "Performing sweep END");
        worker.postDelayed(this::performSweepRunnable, LOST_TRACKER_INTERVAL_MS);
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = nm.getNotificationChannel(CHANNEL_ID);
            if (ch == null) {
                ch = new NotificationChannel(
                        CHANNEL_ID, "Patriot Logger Scanning", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Notifications for BLE scanning activity");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotif(String title) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setSmallIcon(R.drawable.ic_notif)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void notifyLine(String line) {
        Log.i("BLE_NOTIFY", line);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotif(line));
        }
    }

    private String displayLabel(TagStatus s) {
        if (s == null) return "Unknown Tag";
        String name = (s.friendlyName != null && !s.friendlyName.isEmpty())
                ? s.friendlyName : ("PT-" + s.tagId);
        return name + " (#" + s.tagId + (s.trackId != 0 ? " Pass:" + s.trackId : "") + ")";
    }

    private String formatMmSs(long ms) {
        if (ms <= 0) return "--:--.--";
        long seconds = (ms / 1000) % 60;
        long minutes = (ms / (1000 * 60)) % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }
}
