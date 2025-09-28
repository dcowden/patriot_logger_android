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

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.patriotlogger.logger.R;
import com.patriotlogger.logger.data.RaceContext;
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.TagStatus;
import com.patriotlogger.logger.logic.TagProcessor;
import com.patriotlogger.logger.logic.TagProcessorConfig;

import java.util.List;
// Removed unused imports: Kalman1D, ByteBuffer, ByteOrder, HashMap, Map

public class BleScannerService extends Service {

    public static final String ACTION_START = "com.patriotlogger.logger.START_SCAN";
    public static final String ACTION_STOP  = "com.patriotlogger.logger.STOP_SCAN";

    private static final String CHANNEL_ID = "scan_channel";
    private static final int NOTIF_ID = 1001;

    // Old Manufacturer data spec (commented out as current parsing is from device name)
    // private static final int COMPANY_ID = 0xFFFF; // development
    // private static final byte ASCII_P = 0x50;     // 'P'
    // private static final byte ASCII_T = 0x54;     // 'T'

    // Configurables for TagProcessorConfig
    private static final long LOSS_TIMEOUT_MS_CONFIG = 1000L;
    private static final int SAMPLES_BELOW_PEAK_FOR_HERE_CONFIG = 3; // Was BELOW_PEAK_N, value from your test was 3
    private static final float KALMAN_Q_CONFIG = 0.001f;
    private static final float KALMAN_R_CONFIG = 0.1f;
    private static final float KALMAN_INITIAL_P_CONFIG = 0.05f;

    private HandlerThread workerThread;
    private Handler worker;
    // private Handler sweeper; // sweeper tasks will also use the 'worker' handler

    private BluetoothLeScanner scanner;
    private Repository repository;
    private TagProcessor tagProcessor;

    // Removed old transient state HashMaps: kf, belowPeakCounts, lastSeenMs

    @Override
    public void onCreate() {
        super.onCreate();
        repository = Repository.get(this);

        TagProcessorConfig processorConfig = new TagProcessorConfig(
                LOSS_TIMEOUT_MS_CONFIG,
                SAMPLES_BELOW_PEAK_FOR_HERE_CONFIG,
                KALMAN_Q_CONFIG,
                KALMAN_R_CONFIG,
                KALMAN_INITIAL_P_CONFIG
        );
        tagProcessor = new TagProcessor(repository, processorConfig);

        workerThread = new HandlerThread("ble-processor-thread");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        createChannel();
        startForeground(NOTIF_ID, buildNotif("Scanner Initializing..."));

        // Kick off periodic sweeper for loss detection
        worker.postDelayed(this::performSweepRunnable, 1000); // Initial delay
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null) ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            notifyLine("Scanner Stopping...");
            stopSelf(); // This will trigger onDestroy
            return START_NOT_STICKY;
        }
        notifyLine("Scanner Starting...");
        startScan();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopScan();
        if (workerThread != null) {
            worker.removeCallbacksAndMessages(null); // Clear pending tasks
            workerThread.quitSafely();
        }
        Log.i("BLE_SERVICE", "Service Destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ========= Scanning =========

    private void startScan() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (bm != null) ? bm.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            Log.w("BLE_SERVICE", "Bluetooth disabled or not available");
            notifyLine("Bluetooth disabled. Scan failed.");
            stopSelf();
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            Log.w("BLE_SERVICE", "No BLE scanner available");
            notifyLine("BLE Scanner not available. Scan failed.");
            stopSelf();
            return;
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Using Low Latency
                // .setLegacy(true) // Consider if legacy flag is truly needed; remove if not.
                // .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE) // This can be battery intensive.
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(0) // Report results immediately
                .build();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.w("BLE_SERVICE", "Missing BLUETOOTH_SCAN permission for startScan");
            notifyLine("Permission missing. Scan failed.");
            stopSelf();
            return;
        }
        scanner.startScan(null, settings, scanCallback); // No filters, parse in callback
        notifyLine("Scanning for Runners...");
        Log.i("BLE_SERVICE", "Scan started");
    }

    private void stopScan() {
        if (scanner != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.w("BLE_SERVICE", "Missing BLUETOOTH_SCAN permission for stopScan");
                // Still attempt to stop if possible, or log and return
                return;
            }
            try {
                scanner.stopScan(scanCallback);
                Log.i("BLE_SERVICE", "Scan stopped");
                notifyLine("Scanning Paused");
            } catch (Exception e) {
                Log.e("BLE_SERVICE", "Error stopping scan: " + e.getMessage());
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
            Log.e("BLE_SERVICE", "Scan failed with error code: " + errorCode);
            notifyLine("Scan failed: " + errorCode);
            // Consider stopping service or attempting restart based on error code
        }
    };

    private void handleScanResult(ScanResult result) {
        ScanRecord rec = result.getScanRecord();
        if (rec == null) return;

        // Permission check for BLUETOOTH_CONNECT for getName, getAlias, etc.
        // Not strictly needed if only using address and advertised name.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.w("BLE_SERVICE", "BLUETOOTH_CONNECT permission missing, device name might be null or require connect.");
            // Continue if parsing from ScanRecord's deviceName or other advertised data.
        }

        String deviceName = rec.getDeviceName(); // Get name from advertising packet if available
        if (deviceName == null) {
             // Fallback to device.getName() if scan record name is null, though this might also be null or require connection
            // deviceName = result.getDevice().getName(); 
        }

        if (deviceName != null && deviceName.startsWith("PT-")) {
            try {
                int tagId = Integer.parseInt(deviceName.substring(3)); // Extract ID after "PT-"
                int rssi = result.getRssi();
                long now = System.currentTimeMillis();

                // Post processing to worker thread
                worker.post(() -> processSampleWrapper(tagId, rssi, now));
            } catch (NumberFormatException e) {
                Log.w("BLE_SERVICE", "Failed to parse tagId from device name: " + deviceName, e);
            }
        }
        // Removed old manufacturer data parsing logic
    }

    // Wrapper to call TagProcessor on worker thread and handle notifications
    private void processSampleWrapper(int tagId, int rssi, long nowMs) {
        TagStatus statusBefore = repository.getTagStatusNow(tagId); // Get status before processing for accurate notification
        String stateBefore = (statusBefore != null) ? statusBefore.state : "";
        boolean wasNew = statusBefore == null;

        TagStatus currentStatus = tagProcessor.processSample(tagId, rssi, nowMs);

        if (currentStatus != null) {
            if (wasNew && "approaching".equals(currentStatus.state)) {
                notifyLine(displayLabel(currentStatus) + " approaching..");
            } else if (!wasNew && "approaching".equals(stateBefore) && "here".equals(currentStatus.state)) {
                notifyLine(displayLabel(currentStatus) + " passing..");
            }
            // Other state transition notifications can be added here if needed
        }
    }

    // Runnable for periodic loss sweep
    private void performSweepRunnable() {
        Log.d("BLE_SERVICE", "Performing loss sweep...");
        long now = System.currentTimeMillis();
        List<TagStatus> newlyLoggedTags = tagProcessor.performLossSweep(now);

        if (!newlyLoggedTags.isEmpty()) {
            RaceContext raceContext = repository.latestContextNow(); // Fetch once for all logged tags
            long gunTimeMs = (raceContext != null) ? raceContext.gunTimeMs : 0L;

            for (TagStatus loggedTag : newlyLoggedTags) {
                long splitMs = 0;
                if (gunTimeMs > 0 && loggedTag.peakTimeMs > 0) {
                    splitMs = loggedTag.peakTimeMs - gunTimeMs;
                }
                notifyLine(displayLabel(loggedTag) + " logged " + formatMmSs(splitMs));
            }
        }
        // Re-schedule the sweeper
        worker.postDelayed(this::performSweepRunnable, LOSS_TIMEOUT_MS_CONFIG); // Use config for interval
    }


    // ========= Notifications & Utilities =========

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
        // TODO: Ensure R.drawable.ic_notif is the correct white/transparent notification icon
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setSmallIcon(R.drawable.ic_notif) // Assuming ic_notif is your white/transparent icon
                .setOngoing(true)
                .setOnlyAlertOnce(true) // Avoid repeated sound/vibration for ongoing notification updates
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
        return name + " (#" + s.tagId + ")"; // Slightly clearer label
    }

    // Removed idLabel as displayLabel can cover its use cases or be adapted

    private String formatMmSs(long ms) {
        if (ms <= 0) return "--:--.--"; // More precision for splits if desired
        long totalMillis = ms;
        long seconds = (totalMillis / 1000) % 60;
        long minutes = (totalMillis / (1000 * 60)) % 60;
        long millis = totalMillis % 1000;
        // return String.format(Locale.US, "%d:%02d.%03d", minutes, seconds, millis); // With millis
        return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds); // Original format
    }
}
