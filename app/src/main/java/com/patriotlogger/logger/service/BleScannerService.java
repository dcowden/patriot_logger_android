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
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.patriotlogger.logger.R;
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.logic.RssiData;
import com.patriotlogger.logger.data.Setting;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus;
import com.patriotlogger.logger.data.TagStatus.TagStatusState;
import com.patriotlogger.logger.logic.RssiSmoother;

import com.patriotlogger.logger.logic.filters.RssiFilter;
import com.patriotlogger.logger.logic.filters.MinMaxRssiFilter;
import com.patriotlogger.logger.logic.RssiHandler;
import com.patriotlogger.logger.logic.TcaWithFallbackHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BleScannerService extends Service {
    private static final String TAG_SERVICE = "BleScannerService";

    public static final String ACTION_START = "com.patriotlogger.logger.START_SCAN";
    public static final String ACTION_STOP  = "com.patriotlogger.logger.STOP_SCAN";

    public static final String ACTION_QUIESCE_AND_DRAIN = "com.patriotlogger.logger.QUIESCE_AND_DRAIN";
    public static final String EXTRA_RESULT_RECEIVER = "result_receiver";

    public static final String TAG_PREFIX = "PT";
    private static final String CHANNEL_ID = "scan_channel";
    private static final int    NOTIF_ID   = 1001;

    private static final long DEFAULT_SWEEP_INTERVAL_MS = 1500;
    private static final long REPORT_DELAY_MS = 0L;

    private HandlerThread workerThread;
    private Handler       worker;

    private BluetoothLeScanner scanner;
    private Repository         repository;

    private volatile Setting currentSettings = new Setting();

    private volatile long abandonedTagTimeoutMs = 5000L;
    private volatile long sweepIntervalMs = DEFAULT_SWEEP_INTERVAL_MS;

    private final Map<Integer, TagStatusState> lastNotifiedStateForTrack = new ConcurrentHashMap<>();
    private Observer<Setting> settingsObserver;

    private static final MutableLiveData<Boolean> _isScanning = new MutableLiveData<>(false);
    public static final LiveData<Boolean> isScanning = _isScanning;

    private final RssiSmoother rssiSmoother = new RssiSmoother();

    private final Map<Integer, Long> lastRadioNs   = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastDeliverNs = new ConcurrentHashMap<>();

    // Filters
    private final List<RssiFilter> filters = new ArrayList<>();

    // Per-track handlers
    private final Map<Integer, RssiHandler> handlerByTrack = new ConcurrentHashMap<>();

    private RssiHandler newTcaHandler() {
        // Use latest settings (with sane defaults if null)
        float  alpha        = currentSettings.tca_alpha != null ? currentSettings.tca_alpha : Setting.DEFAULT_TCA_ALPHA;
        double txAt1mDbm    = currentSettings.tx_power_at_1m_dbm != null ? currentSettings.tx_power_at_1m_dbm : Setting.DEFAULT_TX_POWER_AT_1M_DBM;
        double pathExp      = currentSettings.path_loss_n != null ? currentSettings.path_loss_n : Setting.DEFAULT_PATH_LOSS_N;
        double hereMeters   = currentSettings.tca_here_meters != null ? currentSettings.tca_here_meters : Setting.DEFAULT_TCA_HERE_METERS;
        double thresholdSec = currentSettings.tca_threshold_sec != null ? currentSettings.tca_threshold_sec : Setting.DEFAULT_TCA_THRESHOLD_SEC;
        int    windowSize   = currentSettings.tca_window_size != null ? currentSettings.tca_window_size : Setting.DEFAULT_TCA_WINDOW_SIZE;
        int    minPoints    = currentSettings.tca_min_points != null ? currentSettings.tca_min_points : Setting.DEFAULT_TCA_MIN_POINTS;
        double approachM    = currentSettings.tca_approach_meters != null ? currentSettings.tca_approach_meters : Setting.DEFAULT_TCA_APPROACH_METERS;

        return new TcaWithFallbackHandler(
                alpha,
                txAt1mDbm,
                pathExp,
                hereMeters,
                thresholdSec,
                windowSize,
                minPoints,
                approachM
        );
    }

    private void rebuildFiltersFromSettings(Setting s) {
        int min = (s.filter_min_rssi != null) ? s.filter_min_rssi : Setting.DEFAULT_FILTER_MIN_RSSI;
        int max = (s.filter_max_rssi != null) ? s.filter_max_rssi : Setting.DEFAULT_FILTER_MAX_RSSI;
        synchronized (filters) {
            filters.clear();
            filters.add(new MinMaxRssiFilter(min, max));
        }
    }

    private void applyRuntimeCadencesFromSettings(Setting s) {
        // Abandoned timeout & sweep cadence for this service
        if (s.abandoned_timeout_ms != null) abandonedTagTimeoutMs = s.abandoned_timeout_ms;
        if (s.sweep_interval_ms != null) sweepIntervalMs = s.sweep_interval_ms;

        // Repository flush cadence
        if (s.tagdata_flush_ms != null) {
            repository.setTagDataFlushIntervalMs(s.tagdata_flush_ms);
        }

        // Reschedule sweep using the new cadence
        if (worker != null) {
            worker.removeCallbacks(this::performSweepRunnable);
            worker.postDelayed(this::performSweepRunnable, sweepIntervalMs);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        repository = Repository.get(getApplicationContext());

        workerThread = new HandlerThread("ble-data-processor");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        // Start with conservative default filter; will be rebuilt when settings arrive
        rebuildFiltersFromSettings(currentSettings);

        settingsObserver = setting -> {
            if (setting != null) {
                this.currentSettings = setting;
                rebuildFiltersFromSettings(setting);
                applyRuntimeCadencesFromSettings(setting);

                // Ensure new handler instances pick up new settings
                handlerByTrack.clear();

                Log.d(TAG_SERVICE, "Settings applied to scanner/handler/filters");
            }
        };
        new Handler(Looper.getMainLooper()).post(() ->
                repository.getLiveConfig().observeForever(settingsObserver)
        );

        // Warm seed handlers + buffers for open passes
        worker.post(() -> {
            try {
                handlerByTrack.clear();
                List<TagStatus> open = repository.getOpenPassesSync(); // safe here: not main thread
                for (TagStatus ts : open) handlerByTrack.put(ts.trackId, newTcaHandler());
                repository.restoreOpenPassBuffers(120);
            } catch (Throwable t) {
                Log.w(TAG_SERVICE, "Warm-start seeding failed", t);
            }
        });

        createChannel();
        startForeground(NOTIF_ID, buildNotif("Scanner Initializing..."));
        worker.postDelayed(this::performSweepRunnable, sweepIntervalMs);
        Log.i(TAG_SERVICE, "Service Created and Initialized");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null) ? intent.getAction() : null;
        if (ACTION_QUIESCE_AND_DRAIN.equals(action)) {
            final android.os.ResultReceiver rr = intent.getParcelableExtra(EXTRA_RESULT_RECEIVER);
            // Ensure scanning is stopped so no new work is enqueued
            stopScan();
            // Post a barrier at the end of the worker queue
            worker.post(() -> {
                try {
                    // At this point, all prior onScanResultWork() have run.
                    repository.flushPendingSamplesBlocking(); // write TagData buffers
                    repository.drainDb();                     // wait for all Room upserts (TagStatus too)
                } catch (Throwable t) {
                    Log.w(TAG_SERVICE, "Quiesce+drain failed", t);
                } finally {
                    if (rr != null) rr.send(android.app.Activity.RESULT_OK, null);
                }
            });
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            notifyLine("Scanner Stopping...");
            Log.i(TAG_SERVICE, "Received stop command");
            stopSelf();
            _isScanning.postValue(false);
            return START_NOT_STICKY;
        } else {
            notifyLine("Scanner Starting...");
            Log.i(TAG_SERVICE, "Received start command");
            startScan();
            _isScanning.postValue(true);
            return START_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopScan();
        try { repository.flushPendingSamplesNow(); } catch (Throwable ignored) {}
        if (settingsObserver != null) {
            new Handler(Looper.getMainLooper()).post(() ->
                    repository.getLiveConfig().removeObserver(settingsObserver)
            );
        }
        if (workerThread != null) {
            worker.removeCallbacksAndMessages(null);
            workerThread.quitSafely();
        }
        _isScanning.postValue(false);
        lastNotifiedStateForTrack.clear();
        Log.i(TAG_SERVICE, "Service Destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

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

        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
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
        @Override public void onScanResult(int callbackType, ScanResult result) { handleScanResult(result); }
        @Override public void onBatchScanResults(List<ScanResult> results) { for (ScanResult r : results) handleScanResult(r); }
        @Override public void onScanFailed(int errorCode) {
            Log.e(TAG_SERVICE, "Scan failed with error code: " + errorCode);
            notifyLine("Scan failed: " + errorCode);
        }
    };

    @Nullable
    private Integer getTagIdIfShouldHandle(ScanRecord sr) {
        if (sr == null) return null;
        String deviceName = sr.getDeviceName();
        if (deviceName != null && deviceName.startsWith(TAG_PREFIX)) {
            try {
                // Expect "PT-123"; substring after "PT-"
                return Integer.parseInt(deviceName.substring(TAG_PREFIX.length() + 1));
            } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private void handleScanResult(ScanResult result) {
        Integer tagId = getTagIdIfShouldHandle(result.getScanRecord());
        if (tagId == null) return;

        int rssi = result.getRssi();
        final long radioNs   = (Build.VERSION.SDK_INT >= 26) ? result.getTimestampNanos() : 0L;
        final long deliverNs = android.os.SystemClock.elapsedRealtimeNanos();

        Long pR = (radioNs > 0) ? lastRadioNs.put(tagId, radioNs) : null;
        Long pD = lastDeliverNs.put(tagId, deliverNs);
        if (pD == null || ((deliverNs / 1_000_000L) % 200 == 0)) { // ~2/s
            long dRadioMs   = (pR != null && radioNs>0 && pR>0) ? (radioNs - pR)/1_000_000L : -1;
            long dDeliverMs = (pD != null) ? (deliverNs - pD)/1_000_000L : -1;
            Log.i("BLE_TIMING", "tag="+tagId+" dRadioMs="+dRadioMs+" dDeliverMs="+dDeliverMs);
        }

        worker.post(() -> onScanResultWork(tagId, rssi, radioNs, deliverNs));
    }

    // ====== main pipeline ======
    private void onScanResultWork(int tagId, int rssi, long radioNs, long deliverNs) {
        long nowMs = System.currentTimeMillis();
        Log.i(TAG_SERVICE, "Received scan result for tagId: " + tagId + " RSSI: " + rssi);

        // (filters)
        List<RssiFilter> currentFilters;
        synchronized (filters) { currentFilters = new ArrayList<>(filters); }
        for (RssiFilter f : currentFilters) {
            if (!f.shouldAccept(nowMs, rssi)) return;
        }

        // Build RssiData (smoothed only for UI; TCA does its own EMA)
        float smoothed = rssiSmoother.getSmoothedRssi(rssi, currentSettings);
        RssiData rssiData = new RssiData(tagId, nowMs, rssi, (int) smoothed);

        if (!repository.isSavingEnabled()) {
            // Calibration mode: do not persist; publish to repo stream
            repository.appendCalibrationSample(rssiData);
            return;
        }

        // Persisting mode
        //TagStatus latestStatus = repository.getLatestTagStatusForId(tagId);
        TagStatus latestStatus = repository.getOrCreateActiveStatus(tagId);
        int trackId = latestStatus.trackId;

        // history (persisted + in-mem tail)
        List<TagData> history = repository.getHistoryForTrackIdSyncCombined(trackId);

        // also buffer new sample for periodic flush
        repository.appendInMemoryTagData(new TagData(trackId, nowMs, rssi));

        // per-track handler (constructed from current settings)
        RssiHandler handler = handlerByTrack.computeIfAbsent(trackId, k -> newTcaHandler());

        TagStatus processedStatus = handler.acceptSample(latestStatus, history, rssiData);

        repository.upsertTagStatus(processedStatus, !currentSettings.retain_samples, null);

        // prune handler on terminal states
        if (processedStatus.state == TagStatusState.LOGGED ||
                processedStatus.state == TagStatusState.TIMED_OUT) {
            handlerByTrack.remove(processedStatus.trackId);
        }

        handleUIUpdates(processedStatus, nowMs);
    }

    private void handleUIUpdates(@NonNull TagStatus processedStatus, long sampleTimestampMs) {
        TagStatusState lastNotified = lastNotifiedStateForTrack.get(processedStatus.trackId);
        if (processedStatus.trackId != 0 && !processedStatus.state.equals(lastNotified)) {
            String message = null;
            if (processedStatus.state == TagStatusState.APPROACHING) {
                if (lastNotified == null) {
                    message = displayLabel(processedStatus) + " approaching...";
                }
            } else if (processedStatus.state == TagStatusState.HERE) {
                message = displayLabel(processedStatus) + " passing...";
            }
            if (message != null) {
                notifyLine(message);
                lastNotifiedStateForTrack.put(processedStatus.trackId, processedStatus.state);
            }
        }
    }

    private void performSweepRunnable() {
        long now = System.currentTimeMillis();
        List<TagStatus> activeStatuses = repository.getAllActiveTagsSync();

        for (TagStatus status : activeStatuses) {
            long msSinceLastSeen = now - status.lastSeenMs;

            if (status.state == TagStatusState.HERE && (msSinceLastSeen > abandonedTagTimeoutMs)) {
                Log.w(TAG_SERVICE, "Tag " + status.tagId + " abandoned while HERE. Timing out.");
                TagStatus newStatus = status;
                newStatus.state = TagStatusState.TIMED_OUT;
                newStatus.exitTimeMs = now;
                repository.upsertTagStatus(newStatus, !currentSettings.retain_samples, null);
                // prune handler on terminal
                handlerByTrack.remove(newStatus.trackId);
                handleUIUpdates(newStatus, now);
            }
        }
        Log.d(TAG_SERVICE, "Performing sweep END");
        worker.postDelayed(this::performSweepRunnable, sweepIntervalMs);
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if ( nm != null) {
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
