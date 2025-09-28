package com.patriotlogger.logger.ui;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
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
import com.patriotlogger.logger.data.RaceContext;
import com.patriotlogger.logger.data.Racer;
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus;
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

    private static final String TAG = "MainActivity"; // Added for logging
    private static final int RC_PERMS = 100;

    private MainViewModel vm;
    private TextView tvHeader, tvClock, tvTotalSamples;
    private Button btnAction, btnDebug, btnSettings, btnDownload;
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private long gunTimeMs = 0L;
    private boolean isScanningActive = false;
    private Repository repository;

    // For background tasks
    private ExecutorService executorService;
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

        // Initialize ExecutorService and Handler
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

        vm.getContext().observe(this, ctx -> {
            tvHeader.setText(vm.headerText(ctx));
            long newGunTimeMs = (ctx != null) ? ctx.gunTimeMs : 0L;
            if (this.gunTimeMs != newGunTimeMs) {
                this.gunTimeMs = newGunTimeMs;
            }
            updateButtonStates();
            if (this.gunTimeMs > 0 && !isScanningActive) {
                ensureScanningIfReady();
            }
        });

        // This LiveData is already correctly observed and updates UI on main thread
        repository.getTotalSamplesCount().observe(this, count -> {
            if (count != null) {
                tvTotalSamples.setText(String.format(Locale.getDefault(), "Total Samples: %d", count));
            } else {
                tvTotalSamples.setText("Total Samples: 0");
            }
        });

        btnAction.setOnClickListener(v -> onActionButtonClicked());

        handleDeepLink(getIntent()); // Refactored to run DB ops in background
        startClock();
        requestAllRuntimePerms();
        updateButtonStates();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleDeepLink(intent); // Refactored
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
        clockHandler.removeCallbacksAndMessages(null); // Stop clock handler
    }

    private void handleDeepLink(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        String data = intent.getData().getQueryParameter("data");
        if (data == null) return;

        executorService.execute(() -> {
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
                ctx.baseUrl = "https://splitriot.onrender.com/upload";
                ctx.createdAtMs = System.currentTimeMillis();

                final List<Racer> racersToUpsert = new ArrayList<>(); // Final for lambda

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
                // Perform DB operations
                if (!racersToUpsert.isEmpty()) {
                    repository.upsertRacers(racersToUpsert);
                }
                repository.upsertContext(ctx);

                // If any UI update is needed after deep link processing, post it to main thread
                // mainThreadHandler.post(() -> { /* UI Update code */ });
                Log.i(TAG, "Deep link processed successfully in background.");

            } catch (Exception e) {
                Log.e(TAG, "Error handling deep link in background", e);
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
            executorService.execute(() -> {
                boolean shouldSetGunTime = false;
                if (gunTimeMs == 0) { // Check local gunTimeMs first
                    RaceContext currentContext = repository.latestContextNow(); // DB access
                    if (currentContext == null || currentContext.gunTimeMs == 0) {
                        shouldSetGunTime = true;
                    } else {
                        // If DB has a gun time but local doesn't, sync it up.
                        // This case should ideally be handled by ViewModel/LiveData observation,
                        // but added here for robustness if local gunTimeMs could get out of sync.
                        final long dbGunTime = currentContext.gunTimeMs;
                        mainThreadHandler.post(() -> {
                            if (this.gunTimeMs != dbGunTime) {
                                this.gunTimeMs = dbGunTime;
                                Log.i(TAG, "Synced gunTimeMs from DB: " + dbGunTime);
                            }
                            ensureScanningIfReady(); // Now try to scan
                        });
                        return; // Return from executor thread
                    }
                }

                if (shouldSetGunTime) {
                    long now = System.currentTimeMillis();
                    repository.setGunTime(now); // DB access (update)
                    // The ViewModel should observe this change and update gunTimeMs via LiveData
                    // If not, you might need to manually post an update or re-fetch.
                    // For now, assuming LiveData handles this.
                    Log.i(TAG, "Gun time set in DB: " + now);
                }

                mainThreadHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        ensureScanningIfReady(); // This method contains UI ops (Toast, startActivity)
                    }
                });
            });
        } else if (currentButtonText.equalsIgnoreCase("Stop")) {
            // stopBleService() primarily deals with Service and UI, no direct DB ops here.
            stopBleService();
            // isScanningActive is already set in stopBleService
            // updateButtonStates is already called in stopBleService
        }
        // updateButtonStates(); // Already called within stopBleService, and after ensureScanningIfReady via LiveData
    }


    private void stopBleService() {
        Intent svc = new Intent(this, BleScannerService.class).setAction(BleScannerService.ACTION_STOP);
        startService(svc);
        isScanningActive = false; // Update state immediately
        updateButtonStates(); // Reflect change in UI
        Log.i(TAG, "Sent stop command to BleScannerService");
    }

    private void updateButtonStates() {
        // This is UI work, ensure it's on the main thread (it is, as it's called from UI thread or posted handler)
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
                long now = System.currentTimeMillis();
                if (gunTimeMs > 0) {
                    long diff = now - gunTimeMs;
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

    // ensureScanningIfReady contains UI operations (Toast, Log, startActivity) and should run on main thread
    // It's called from onActionButtonClicked (after background work) or LiveData observers (already on main thread)
    private void ensureScanningIfReady() {
        if (gunTimeMs <= 0) {
            Toast.makeText(this, "Clock not started. Cannot start scanning.", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "Cannot Scan: Clock not started");
            updateButtonStates();
            return;
        }
        if (!hasAllPerms()) {
            Toast.makeText(this, "Cannot Scan: Missing permissions.", Toast.LENGTH_SHORT).show();
            requestAllRuntimePerms(); // Attempt to request them again
            Log.w(TAG, "Cannot Scan: Missing permissions");
            return;
        }
        if (needsLocationForThisSDK() && !isLocationEnabled()) {
            Toast.makeText(this, "Turn ON Location to enable BLE scanning for this Android version.", Toast.LENGTH_LONG).show();
            openLocationSettingsLauncher.launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            Log.w(TAG, "Cannot Scan: Location not enabled");
            return;
        }

        Intent svc = new Intent(this, BleScannerService.class).setAction(BleScannerService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);
        isScanningActive = true;
        updateButtonStates();
    }

    // onDownloadCsvClicked already uses its own Executor and Handler.
    // We should ensure that the repository calls within its background task
    // are indeed synchronous and that it's okay for them to run on that specific executor.
    // If these repo calls were async (returning LiveData/Flow), this structure would need adjustment.
    // Assuming they are synchronous (e.g., getAllTagStatusesSync) as their names suggest.
    private void onDownloadCsvClicked() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (!EasyPermissions.hasPermissions(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                Toast.makeText(this, "Storage permission is required to save CSV. Please grant it via app settings or re-launch the app.", Toast.LENGTH_LONG).show();
                return;
            }
        }

        // Using the class-level executorService for consistency, or keep its local one if preferred.
        // For this example, I'll switch to the class-level one.
        // If you had a good reason for a separate executor here (e.g. different priority), keep it.
        // final ExecutorService localExecutor = Executors.newSingleThreadExecutor(); // Kept original for clarity
        // final Handler localMainThreadHandler = new Handler(Looper.getMainLooper()); // Kept original

        Toast.makeText(this, "Generating CSV...", Toast.LENGTH_SHORT).show();

        // Using the activity's main executor and handler
        executorService.execute(() -> {
            List<TagStatus> tagStatuses = repository.getAllTagStatusesSync(); // DB
            List<TagData> allTagData = repository.getAllTagDataSync();       // DB
            RaceContext currentContext = repository.latestContextNow();      // DB
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
                            mainThreadHandler.post(() -> { // Use class-level handler
                                if (!isFinishing() && !isDestroyed()) {
                                    Toast.makeText(MainActivity.this, "Failed to create Downloads directory.", Toast.LENGTH_SHORT).show();
                                }
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
            mainThreadHandler.post(() -> { // Use class-level handler
                if (!isFinishing() && !isDestroyed()) {
                    if (finalSuccess) {
                        Toast.makeText(MainActivity.this, "CSV saved to Downloads: " + fileName, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(MainActivity.this, "Failed to save CSV.", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            // If you used a localExecutor, you'd need to shut it down:
            // localExecutor.shutdown();
        });
    }

    // ===== Permissions (EasyPermissions) =====
    // Remainder of the permissions code is mostly UI interaction or calls other UI methods,
    // so it should be fine on the main thread.
    // ensureScanningIfReady is called from onPermissionsGranted, which is already on the main thread.

    private String[] requiredPerms() {
        List<String> list = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_SCAN);
            list.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) { // Corrected: Less than or EQUAL to P
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return list.toArray(new String[0]);
    }

    private boolean needsLocationForThisSDK() {
        return Build.VERSION.SDK_INT < 31 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
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
            } catch (Exception e) {
                Log.e(TAG, "isLocationEnabled: Failed to get location mode", e);
                return false;
            }
        }
    }

    @Override
    public void onPermissionsGranted(int requestCode, @NonNull List<String> perms) {
        if (requestCode == RC_PERMS) {
            ensureScanningIfReady();
        }
        updateButtonStates();
    }

    @Override
    public void onPermissionsDenied(int requestCode, @NonNull List<String> perms) {
        if (requestCode == RC_PERMS) {
            if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
                Toast.makeText(this, "Permissions permanently denied. Enable them in App Settings.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Required permissions denied.", Toast.LENGTH_SHORT).show();
            }
        }
        updateButtonStates();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }
}
