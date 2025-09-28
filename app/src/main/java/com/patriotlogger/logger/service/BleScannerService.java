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
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.patriotlogger.logger.R;
import com.patriotlogger.logger.data.RaceContext;
import com.patriotlogger.logger.data.Racer;
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.TagStatus;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.logic.TagProcessor;
import com.patriotlogger.logger.utils.SettingsManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects; 
import java.util.concurrent.ConcurrentHashMap;

public class BleScannerService extends Service {
    private static final String TAG_SERVICE = "BleScannerService";

    public static final String ACTION_START = "com.patriotlogger.logger.START_SCAN";
    public static final String ACTION_STOP  = "com.patriotlogger.logger.STOP_SCAN";

    private static final String CHANNEL_ID = "scan_channel";
    private static final int NOTIF_ID = 1001;

    private static final long LOSS_TIMEOUT_MS_CONFIG = 1000L; 
    private static final long REPORT_DELAY_MS = 20L; 

    private HandlerThread workerThread;
    private Handler worker;

    private BluetoothLeScanner scanner;
    private Repository repository;
    private TagProcessor tagProcessor;
    private SettingsManager settingsManager;

    private final Map<Integer, TagStatus.TagStatusState> lastNotifiedStateForTrack = new ConcurrentHashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        repository = Repository.get(getApplicationContext());
        settingsManager = SettingsManager.getInstance(getApplicationContext());
        tagProcessor = new TagProcessor();

        workerThread = new HandlerThread("ble-data-processor");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        createChannel();
        startForeground(NOTIF_ID, buildNotif("Scanner Initializing..."));
        worker.postDelayed(this::performSweepRunnable, LOSS_TIMEOUT_MS_CONFIG);
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
        if (workerThread != null) {
            worker.removeCallbacksAndMessages(null); 
            workerThread.quitSafely();
        }
        lastNotifiedStateForTrack.clear();
        Log.i(TAG_SERVICE, "Service Destroyed");
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private void startScan() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (bm != null) ? bm.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG_SERVICE, "Bluetooth disabled or not available");
            notifyLine("Bluetooth disabled. Scan failed.");
            stopSelf(); return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            Log.w(TAG_SERVICE, "No BLE scanner available");
            notifyLine("BLE Scanner not available. Scan failed.");
            stopSelf(); return;
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(REPORT_DELAY_MS)
                .build();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG_SERVICE, "Missing BLUETOOTH_SCAN permission for startScan");
            notifyLine("Permission missing. Scan failed.");
            stopSelf(); return;
        }
        scanner.startScan(null, settings, scanCallback);
        notifyLine("Scanning for Runners...");
        Log.i(TAG_SERVICE, "Scan effectively started");
    }

    private void stopScan() {
        if (scanner != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG_SERVICE, "Missing BLUETOOTH_SCAN permission for stopScan");
                return;
            }
            try {
                scanner.stopScan(scanCallback);
                Log.i(TAG_SERVICE, "Scan stopped via API");
                notifyLine("Scanning Paused");
            } catch (Exception e) {
                Log.e(TAG_SERVICE, "Error stopping scan: " + e.getMessage());
            }
            scanner = null;
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) { handleScanResult(result); }
        @Override
        public void onBatchScanResults(List<ScanResult> results) { 
            for (ScanResult r : results) handleScanResult(r); 
        }
        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG_SERVICE, "Scan failed with error code: " + errorCode);
            notifyLine("Scan failed: " + errorCode);
        }
    };

    @Nullable
    private Integer getTagIdIfShouldHandle(ScanRecord sr) {
        if (sr == null) return null;
        String deviceName = sr.getDeviceName();
        if (deviceName != null && deviceName.startsWith("PT-")) {
            try {
                return Integer.parseInt(deviceName.substring(3));
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

        worker.post(() -> processRawSample(tagId, rssi, now)); 
    }

    private void processRawSample(int tagId, int rssi, long nowMs) {
        TagStatus statusToProcess;
        TagStatus currentKnownStatus = repository.getActiveTagStatusForTagIdNow(tagId);
        Racer racer = repository.getRacerNow(tagId); // Fetch racer info for friendly name
        String friendlyName = (racer != null && racer.name != null && !racer.name.isEmpty()) ? racer.name : null;

        if (currentKnownStatus == null) { // This is a new pass for this tagId
            statusToProcess = new TagStatus();
            statusToProcess.tagId = tagId;
            statusToProcess.entryTimeMs = nowMs;
            statusToProcess.state = TagStatus.TagStatusState.APPROACHING;
            statusToProcess.lowestRssi = (float) rssi; // Initial RSSI is the lowest so far
            if (friendlyName != null) {
                statusToProcess.friendlyName = friendlyName;
            }
            Log.d(TAG_SERVICE, "New pass for tagId: " + tagId + ". Initializing new TagStatus.");
        } else { // Existing pass for this tagId
            statusToProcess = currentKnownStatus;
            // Update friendly name if it wasn't set before and is now available
            if ((statusToProcess.friendlyName == null || statusToProcess.friendlyName.isEmpty()) && 
                (friendlyName != null && !friendlyName.isEmpty())) {
                statusToProcess.friendlyName = friendlyName;
            }
        }

        int arrivedThreshold = settingsManager.getArrivedThreshold();

        TagStatus processedStatus = tagProcessor.processSample(
            statusToProcess, 
            rssi,          
            nowMs,         
            arrivedThreshold
        );
        
        processedStatus.trackId = repository.upsertTagStatusSync(processedStatus);

        handleProcessingSideEffects(processedStatus, nowMs, rssi); 
    }

    private void handleProcessingSideEffects(@NonNull TagStatus processedStatus, long sampleTimestampMs, int sampleRssi) {
        if (processedStatus.trackId != 0 && processedStatus.isInProcess()) { 
            TagData newSample = new TagData(processedStatus.trackId, sampleTimestampMs, sampleRssi);
            repository.insertTagData(newSample);
        }

        TagStatus.TagStatusState lastNotified = lastNotifiedStateForTrack.get(processedStatus.trackId);
        // Corrected comparison: Use .equals() for safe comparison with potentially null lastNotified
        if (processedStatus.trackId != 0 && !processedStatus.state.equals(lastNotified)) {
            String message = null;
            if (processedStatus.state == TagStatus.TagStatusState.APPROACHING) {
                if (lastNotified == null) { // Only notify for first time APPROACHING
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

    private void performSweepRunnable() {
        Log.d(TAG_SERVICE, "Performing loss sweep...");
        long now = System.currentTimeMillis();
        
        List<TagStatus> activeStatuses = repository.getAllTagStatusesSync();
        if (activeStatuses == null) activeStatuses = new ArrayList<>();

        RaceContext raceContext = repository.latestContextNow(); 
        long gunTimeMs = (raceContext != null) ? raceContext.gunTimeMs : 0L;
        boolean retainSamplesSetting = settingsManager.shouldRetainSamples();

        for (TagStatus status : activeStatuses) {
            if (status.isInProcess()) {
                List<TagData> samples = repository.getSamplesForTrackIdSync(status.trackId);
                TagStatus loggedStatus = tagProcessor.processTagExit(status, samples, now, LOSS_TIMEOUT_MS_CONFIG);

                if (loggedStatus != null) { 
                    repository.upsertTagStatus(loggedStatus); 
                    lastNotifiedStateForTrack.remove(loggedStatus.trackId);

                    long splitMs = 0;
                    if (gunTimeMs > 0 && loggedStatus.peakTimeMs > 0) {
                        splitMs = loggedStatus.peakTimeMs - gunTimeMs;
                    }
                    notifyLine(displayLabel(loggedStatus) + " logged " + formatMmSs(splitMs));

                    if (!retainSamplesSetting) {
                        Log.i(TAG_SERVICE, "retain_samples is false. Deleting samples for trackId: " + loggedStatus.trackId);
                        repository.deleteSamplesForTrackId(loggedStatus.trackId);
                    }
                }
            }
        }
        worker.postDelayed(this::performSweepRunnable, LOSS_TIMEOUT_MS_CONFIG);
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
        long totalMillis = ms;
        long seconds = (totalMillis / 1000) % 60;
        long minutes = (totalMillis / (1000 * 60)) % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }
}
