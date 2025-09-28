package com.patriotlogger.logger.ui;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.patriotlogger.logger.R;
import com.patriotlogger.logger.data.AppDatabase; // Import AppDatabase
import com.patriotlogger.logger.data.RaceContext;
import com.patriotlogger.logger.data.RaceContextDao; // For direct access in background
import com.patriotlogger.logger.data.Racer;
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.RepositoryCallback; // If using callbacks
import com.patriotlogger.logger.data.RepositoryVoidCallback; // If using callbacks
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagDataDao; // For direct access in background
import com.patriotlogger.logger.data.TagStatus;
import com.patriotlogger.logger.data.TagStatusDao; // For direct access in background
import com.patriotlogger.logger.service.BleScannerService;
import com.patriotlogger.logger.util.CsvExporter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends AppCompatActivity implements EasyPermissions.PermissionCallbacks {

    private static final String TAG = "MainActivity";
    private static final int RC_PERMS = 100;

    private MainViewModel vm;
    private TextView tvHeader, tvClock, tvTotalSamples;
    private Button btnAction, btnDebug, btnSettings, btnDownload;
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private long gunTimeMs = 0L; // This will be primarily driven by ViewModel's LiveData
    private boolean isScanningActive = false; // This will also be driven by ViewModel/Service state

    private Repository repository;
    private ExecutorService executorService; // For specific tasks not covered by ViewModel's scope
    private Handler mainThreadHandler;


    private final ActivityResultLauncher<Intent> openLocationSettingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                if (isLocationEnabled()) {
                    ensureScanningIfReady();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        repository = Repository.get(getApplication());
        executorService = Executors.newSingleThreadExecutor();
        mainThreadHandler = new Handler(Looper.getMainLooper());

        if (getSupportActionBar() != null) {
            getSupportActionBar().setIcon(R.drawable.patriot_logo);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        vm = new ViewModelProvider(this).get(MainViewModel.class);

        tvHeader = findViewById(R.id.tvHeader);
        tvClock = findViewById(R.id.tvClock);
        tvTotalSamples = findViewById(R.id.tvTotalSamples);
        btnAction = findViewById(R.id.btnAction);
        btnDebug = findViewById(R.id.btnDebug);
        btnSettings = findViewById(R.id.btnSettings);
        btnDownload = findViewById(R.id.btnDownload);

        btnDebug.setOnClickListener(v -> startActivity(new Intent(this, DebugActivity.class)));
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        btnDownload.setOnClickListener(v -> onDownloadCsvClicked());

        // Observe RaceContext from ViewModel (which gets it from Repository.getLiveRaceContext())
        vm.getContext().observe(this, ctx -> {
            tvHeader.setText(vm.headerText(ctx));
            long newGunTimeMs = (ctx != null) ? ctx.gunTimeMs : 0L;
            if (this.gunTimeMs != newGunTimeMs) {
                this.gunTimeMs = newGunTimeMs; // Update local copy for clock display logic
                Log.d(TAG, "gunTimeMs updated from ViewModel: " + this.gunTimeMs);
            }
            updateButtonStates(); // Update button based on current scanning state
            // Logic to start scanning if gun time is set and not already scanning
            if (this.gunTimeMs > 0 && !isScanningActive) { // isScanningActive needs to be reliable
                ensureScanningIfReady();
            }
        });

        // Observe total samples count from Repository (already returns LiveData)
        repository.getTotalSamplesCount().observe(this, count -> {
            if (count != null) {
                tvTotalSamples.setText(String.format(Locale.getDefault(), "Total Samples: %d", count));
            } else {
                tvTotalSamples.setText("Total Samples: 0");
            }
        });

        btnAction.setOnClickListener(v -> onActionButtonClicked());

        handleDeepLink(getIntent());
        startClock();
        requestAllRuntimePerms();
        // updateButtonStates(); // Called by LiveData observers now
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleDeepLink(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
        clockHandler.removeCallbacksAndMessages(null);
    }

    private void handleDeepLink(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        String data = intent.getData().getQueryParameter("data");
        if (data == null) return;

        // Repository methods for upsert are already async
        executorService.execute(() -> { // Keep on background thread for JSON parsing
            try {
                byte[] bytes = Base64.decode(data, Base64.DEFAULT);
                String json = new String(bytes, StandardCharsets.UTF_8);
                JsonObject root = new Gson().fromJson(json, JsonObject.class);

                RaceContext ctx = new RaceContext();
                ctx.eventName = root.get("event_name").getAsString();
                ctx.raceName = root.get("race_name").getAsString();
                ctx.raceId = root.get("race_id").getAsInt();
                ctx.gunTimeMs = root.get("gun_time").getAsLong();
                ctx.authToken = root.get("auth_token").getAsString();
                ctx.baseUrl = "https://splitriot.onrender.com/upload"; // Corrected from your example
                ctx.createdAtMs = System.currentTimeMillis();

                final List<Racer> racersToUpsert = new ArrayList<>();

                JsonArray splits = root.get("split_assignments").getAsJsonArray();
                if (splits.size() > 0) {
                    JsonObject s0 = splits.get(0).getAsJsonObject();
                    ctx.splitAssignmentId = s0.get("id").getAsInt();
                    ctx.splitName = s0.get("split_name").getAsString();

                    JsonArray arr = s0.get("racers").getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject r = arr.get(i).getAsJsonObject();
                        Racer rr = new Racer();
                        rr.id = r.get("id").getAsInt();
                        if (r.has("name") && !r.get("name").isJsonNull()) {
                            rr.name = r.get("name").getAsString();
                        } else {
                            rr.name = "Unknown Racer";
                        }
                        rr.splitAssignmentId = ctx.splitAssignmentId;
                        racersToUpsert.add(rr);
                    }
                }

                if (!racersToUpsert.isEmpty()) {
                    repository.upsertRacers(racersToUpsert); // Already async in repo
                }
                repository.upsertRaceContext(ctx); // Already async in repo

                Log.i(TAG, "Deep link processed. Repository will update LiveData.");

            } catch (Exception e) {
                Log.e(TAG, "Error handling deep link", e);
                final String errorMsg = e.toString();
                mainThreadHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        startActivity(new Intent(MainActivity.this, ErrorActivity.class).putExtra("err", errorMsg));
                    }
                });
            }
        });
    }


    private void onActionButtonClicked() {
        String currentButtonText = btnAction.getText().toString();

        if (currentButtonText.equalsIgnoreCase("Start")) {
            // Check current gunTimeMs from our LiveData-backed field
            if (this.gunTimeMs == 0) {
                // If gun time is 0, we need to set it.
                // The repository.setGunTime is now async.
                // LiveData from vm.getContext() will update this.gunTimeMs
                // which will then trigger ensureScanningIfReady if conditions are met.
                long now = System.currentTimeMillis();
                repository.setGunTime(now, new RepositoryVoidCallback() {
                    @Override
                    public void onSuccess() {
                        Log.i(TAG, "Gun time set successfully: " + now);
                        // LiveData observer for vm.getContext() should pick up the change
                        // and eventually call ensureScanningIfReady if conditions are met.
                        // We can also call it directly here if we want to be more proactive,
                        // after ensuring this.gunTimeMs is updated.
                        mainThreadHandler.post(() -> {
                            // this.gunTimeMs will be updated by LiveData,
                            // ensureScanningIfReady will be called by its observer.
                            // Or, force a check now:
                            if(MainActivity.this.gunTimeMs > 0) ensureScanningIfReady();
                        });
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.e(TAG, "Failed to set gun time", e);
                        mainThreadHandler.post(() -> Toast.makeText(MainActivity.this, "Error setting gun time.", Toast.LENGTH_SHORT).show());
                    }
                });
            } else {
                // Gun time is already set, just ensure scanning starts
                ensureScanningIfReady();
            }
        } else if (currentButtonText.equalsIgnoreCase("Stop")) {
            stopBleService();
        }
    }


    private void stopBleService() {
        Intent svcIntent = new Intent(this, BleScannerService.class);
        svcIntent.setAction(BleScannerService.ACTION_STOP);
        // Consider using stopService(svcIntent) if you only want to stop it
        // startService is fine too, it will deliver the intent.
        startService(svcIntent);
        isScanningActive = false;
        updateButtonStates();
        Log.i(TAG, "Sent stop command to BleScannerService");
    }

    private void updateButtonStates() {
        if (isScanningActive) {
            btnAction.setText("Stop");
        } else {
            btnAction.setText("Start");
        }
    }

    private void startClock() {
        clockHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Use this.gunTimeMs which is updated by LiveData
                if (MainActivity.this.gunTimeMs > 0) {
                    long now = System.currentTimeMillis();
                    long diff = now - MainActivity.this.gunTimeMs;
                    long m = (diff / 1000) / 60;
                    long s = (diff / 1000) % 60;
                    tvClock.setText(String.format(Locale.getDefault(), "%d:%02d%s", m, s, isScanningActive ? "" : " (Paused)"));
                } else {
                    tvClock.setText("--:--");
                }
                clockHandler.postDelayed(this, 250);
            }
        }, 250);
    }

    private void ensureScanningIfReady() {
        Log.d(TAG, "ensureScanningIfReady called. gunTimeMs: " + this.gunTimeMs + ", isScanningActive: " + isScanningActive);
        // Use this.gunTimeMs
        if (this.gunTimeMs <= 0) {
            // Toast.makeText(this, "Clock not started. Cannot start scanning.", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "Cannot Scan: Clock not started (gunTimeMs: " + this.gunTimeMs + ")");
            // updateButtonStates(); // Will be handled by LiveData flow
            return;
        }
        if (isScanningActive) {
            Log.d(TAG, "Scanning is already active.");
            updateButtonStates(); // Ensure button is "Stop"
            return;
        }
        if (!hasAllPerms()) {
            Toast.makeText(this, "Cannot Scan: Missing permissions.", Toast.LENGTH_SHORT).show();
            requestAllRuntimePerms();
            Log.w(TAG, "Cannot Scan: Missing permissions");
            return;
        }
        if (needsLocationForThisSDK() && !isLocationEnabled()) {
            Toast.makeText(this, "Turn ON Location to enable BLE scanning for this Android version.", Toast.LENGTH_LONG).show();
            openLocationSettingsLauncher.launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            Log.w(TAG, "Cannot Scan: Location not enabled");
            return;
        }

        Log.i(TAG, "Starting BLE Scanner Service.");
        Intent svcIntent = new Intent(this, BleScannerService.class);
        svcIntent.setAction(BleScannerService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svcIntent);
        } else {
            startService(svcIntent);
        }
        isScanningActive = true;
        updateButtonStates();
    }

    private void onDownloadCsvClicked() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (!EasyPermissions.hasPermissions(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                Toast.makeText(this, "Storage permission is required to save CSV.", Toast.LENGTH_LONG).show();
                return;
            }
        }

        Toast.makeText(this, "Generating CSV...", Toast.LENGTH_SHORT).show();

        executorService.execute(() -> {
            // Access DAOs directly as we are on a background thread
            AppDatabase db = repository.getDatabase();
            TagStatusDao tagStatusDao = db.tagStatusDao();
            TagDataDao tagDataDao = db.tagDataDao();
            RaceContextDao raceContextDao = db.raceContextDao();

            List<TagStatus> tagStatuses = tagStatusDao.getAllSync();
            List<TagData> allTagData = tagDataDao.getAllTagDataSync();
            RaceContext currentContext = raceContextDao.latestSync(); // Use DAO's sync method
            long currentGunTimeMs = (currentContext != null) ? currentContext.gunTimeMs : 0L;

            Map<Integer, List<TagData>> samplesByTrackId = new HashMap<>();
            if (allTagData != null) {
                for (TagData sample : allTagData) {
                    samplesByTrackId.computeIfAbsent(sample.trackId, k -> new ArrayList<>()).add(sample);
                }
            }

            String csvData = CsvExporter.generateCsvString(tagStatuses, samplesByTrackId, currentGunTimeMs);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
            String fileName = "patriot_logger_" + sdf.format(new Date()) + ".csv";
            boolean success = false;

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                            if (outputStream != null) {
                                outputStream.write(csvData.getBytes(StandardCharsets.UTF_8));
                                success = true;
                            }
                        }
                    } else {
                        Log.e(TAG + "_CSV", "MediaStore URI was null.");
                    }
                } else {
                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadsDir.exists()) {
                        if (!downloadsDir.mkdirs()) {
                            Log.e(TAG + "_CSV", "Failed to create Downloads directory.");
                            mainThreadHandler.post(() -> {
                                if (!isFinishing() && !isDestroyed()) Toast.makeText(MainActivity.this, "Failed to create Downloads directory.", Toast.LENGTH_SHORT).show();
                            });
                            return;
                        }
                    }
                    File file = new File(downloadsDir, fileName);
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        fos.write(csvData.getBytes(StandardCharsets.UTF_8));
                        success = true;
                    }
                }
            } catch (IOException e) {
                Log.e(TAG + "_CSV", "Error writing CSV file", e);
            } catch (SecurityException se) {
                Log.e(TAG + "_CSV", "Security Exception writing CSV file", se);
            }

            final boolean finalSuccess = success;
            mainThreadHandler.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    if (finalSuccess) {
                        Toast.makeText(MainActivity.this, "CSV saved to Downloads: " + fileName, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(MainActivity.this, "Failed to save CSV.", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        });
    }

    // --- Permissions ---
    private String[] requiredPerms() {
        List<String> list = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12 (API 31)
            list.add(Manifest.permission.BLUETOOTH_SCAN);
            list.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION); // For older BLE
            // list.add(Manifest.permission.BLUETOOTH); // Not usually needed if using SCAN/CONNECT
            // list.add(Manifest.permission.BLUETOOTH_ADMIN); // Not usually needed for scanning
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13 (API 33)
            list.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        // WRITE_EXTERNAL_STORAGE only for API <= P (28)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return list.toArray(new String[0]);
    }

    private boolean needsLocationForThisSDK() {
        // Location permission is needed for BLE scanning on Android 6 (M) through Android 11 (R) (API 23-30)
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.S;
    }

    private boolean hasAllPerms() {
        return EasyPermissions.hasPermissions(this, requiredPerms());
    }

    @AfterPermissionGranted(RC_PERMS)
    private void requestAllRuntimePerms() {
        String[] perms = requiredPerms();
        if (!EasyPermissions.hasPermissions(this, perms)) {
            EasyPermissions.requestPermissions(
                    this,
                    "Patriot Split Logger requires these permissions to function fully:",
                    RC_PERMS,
                    perms
            );
        } else {
            // If permissions are already granted, try to ensure scanning if conditions met
            Log.d(TAG, "All permissions already granted. Checking if scanning should start.");
            if (this.gunTimeMs > 0 && !isScanningActive) {
                ensureScanningIfReady();
            }
        }
    }

    private boolean isLocationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return lm.isLocationEnabled();
        } else {
            try {
                int mode = Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE);
                return mode != Settings.Secure.LOCATION_MODE_OFF;
            } catch (Settings.SettingNotFoundException e) { // Catch specific exception
                Log.e(TAG, "isLocationEnabled: Failed to get location mode", e);
                return false;
            }
        }
    }

    @Override
    public void onPermissionsGranted(int requestCode, @NonNull List<String> perms) {
        if (requestCode == RC_PERMS) {
            Log.d(TAG, "Permissions granted: " + perms.toString());
            // ensureScanningIfReady(); // This will be triggered by LiveData if gunTime is set.
            // Or can be called proactively:
            if (this.gunTimeMs > 0 && !isScanningActive) {
                ensureScanningIfReady();
            }
        }
        // updateButtonStates(); // Handled by LiveData flow
    }

    @Override
    public void onPermissionsDenied(int requestCode, @NonNull List<String> perms) {
        if (requestCode == RC_PERMS) {
            Log.w(TAG, "Permissions denied: " + perms.toString());
            if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
                Toast.makeText(this, "Permissions permanently denied. Enable them in App Settings.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Required permissions denied.", Toast.LENGTH_SHORT).show();
            }
        }
        // updateButtonStates(); // Handled by LiveData flow
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }
}

