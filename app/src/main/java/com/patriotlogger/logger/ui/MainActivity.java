package com.patriotlogger.logger.ui;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
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

import com.google.android.material.snackbar.Snackbar;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.patriotlogger.logger.R;
import com.patriotlogger.logger.data.AppDatabase;
import com.patriotlogger.logger.data.RaceContext;
import com.patriotlogger.logger.data.RaceContextDao;
import com.patriotlogger.logger.data.Racer;
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.RepositoryVoidCallback;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagDataDao;
import com.patriotlogger.logger.data.TagStatus;
import com.patriotlogger.logger.data.TagStatusDao;
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
import java.util.function.Supplier;

import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends AppCompatActivity implements EasyPermissions.PermissionCallbacks {

    private static final String TAG = "MainActivity";
    private static final int RC_PERMS = 100;

    private MainViewModel vm;
    private TextView tvHeader, tvClock, tvTotalSamples;
    private Button btnAction, btnDebug, btnSettings, btnDownload;
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private long gunTimeMs = 0L;
    private boolean isScanningActive = false;

    private Repository repository;
    private ExecutorService executorService;
    private Handler mainThreadHandler;

    // Launchers
    private final ActivityResultLauncher<Intent> openLocationSettingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                if (isLocationEnabled()) {
                    ensureScanningIfReady();
                }
            });

    private final ActivityResultLauncher<Intent> btEnableLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                // If user enabled BT, try again
                ensureScanningIfReady();
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

        vm.getContext().observe(this, ctx -> {
            tvHeader.setText(vm.headerText(ctx));
            long newGunTimeMs = (ctx != null) ? ctx.gunTimeMs : 0L;
            if (this.gunTimeMs != newGunTimeMs) {
                this.gunTimeMs = newGunTimeMs;
                Log.d(TAG, "gunTimeMs updated from ViewModel: " + this.gunTimeMs);
            }
            updateButtonStates();
            if (this.gunTimeMs > 0 && !isScanningActive) {
                ensureScanningIfReady();
            }
        });

        repository.getTotalDataCount().observe(this, count -> {
            tvTotalSamples.setText(String.format(Locale.getDefault(), "Captured: %d Splits, %d pings ", count.tagCount,count.sampleCount));
        });

        btnAction.setOnClickListener(v -> onActionButtonClicked());

        handleDeepLink(getIntent());
        startClock();
        requestAllRuntimePerms();
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
                        rr.name = (r.has("name") && !r.get("name").isJsonNull()) ? r.get("name").getAsString() : "Unknown Racer";
                        rr.splitAssignmentId = ctx.splitAssignmentId;
                        racersToUpsert.add(rr);
                    }
                }

                if (!racersToUpsert.isEmpty()) repository.upsertRacers(racersToUpsert);
                repository.upsertRaceContext(ctx);

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
            if (this.gunTimeMs == 0) {
                long now = System.currentTimeMillis();
                repository.setGunTime(now, new RepositoryVoidCallback() {
                    @Override
                    public void onSuccess() {
                        Log.i(TAG, "Gun time set successfully: " + now);
                        mainThreadHandler.post(() -> {
                            if (MainActivity.this.gunTimeMs > 0) ensureScanningIfReady();
                        });
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.e(TAG, "Failed to set gun time", e);
                        mainThreadHandler.post(() ->
                                Toast.makeText(MainActivity.this, "Error setting gun time.", Toast.LENGTH_SHORT).show());
                    }
                });
            } else {
                ensureScanningIfReady();
            }
        } else if (currentButtonText.equalsIgnoreCase("Stop")) {
            stopBleService();
        }
    }

    private void stopBleService() {
        Intent svcIntent = new Intent(this, BleScannerService.class);
        svcIntent.setAction(BleScannerService.ACTION_STOP);
        startService(svcIntent);
        isScanningActive = false;
        updateButtonStates();
        Log.i(TAG, "Sent stop command to BleScannerService");
    }

    private void updateButtonStates() {
        btnAction.setText(isScanningActive ? "Stop" : "Start");
    }

    private void startClock() {
        clockHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (MainActivity.this.gunTimeMs > 0) {
                    long now = System.currentTimeMillis();
                    long diff = now - MainActivity.this.gunTimeMs;
                    long m = (diff / 1000) / 60;
                    long s = (diff / 1000) % 60;
                    tvClock.setText(String.format(Locale.getDefault(), "%d:%02d", m, s));
                } else {
                    tvClock.setText("--:--");
                }
                clockHandler.postDelayed(this, 250);
            }
        }, 250);
    }

    private void ensureScanningIfReady() {
        Log.d(TAG, "ensureScanningIfReady called. gunTimeMs: " + this.gunTimeMs + ", isScanningActive: " + isScanningActive);

        if (this.gunTimeMs <= 0) {
            Log.w(TAG, "Cannot Scan: Clock not started (gunTimeMs: " + this.gunTimeMs + ")");
            return;
        }
        if (isScanningActive) {
            Log.d(TAG, "Scanning is already active.");
            updateButtonStates();
            return;
        }

        // Permissions
        if (!hasAllPerms()) {
            Toast.makeText(this, "Cannot Scan: Missing permissions.", Toast.LENGTH_SHORT).show();
            requestAllRuntimePerms();
            Log.w(TAG, "Cannot Scan: Missing permissions");
            return;
        }

        // Location ON requirement on Android <= 30
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && !isLocationEnabled()) {
            Toast.makeText(this, "Turn ON Location to enable BLE scanning on this Android version.", Toast.LENGTH_LONG).show();
            openLocationSettingsLauncher.launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            Log.w(TAG, "Cannot Scan: Location not enabled");
            return;
        }

        // Bluetooth enabled
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!adapter.isEnabled()) {
            btEnableLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }

        Log.i(TAG, "Starting BLE Scanner Service.");
        Intent svcIntent = new Intent(this, BleScannerService.class).setAction(BleScannerService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent);
        } else {
            startService(svcIntent);
        }
        isScanningActive = true;
        updateButtonStates();
    }

    private boolean generateAndSaveCsv(String fileName, String fileContent) {

        boolean success = false;

        Log.d(TAG + "_CSV", "Attempting to save file: " + fileName);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use MediaStore for Android 10 (Q) and above (Scoped Storage)
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                        if (outputStream != null) {
                            outputStream.write(fileContent.getBytes(StandardCharsets.UTF_8));
                            success = true;
                        }
                    }
                } else {
                    Log.e(TAG + "_CSV", "MediaStore URI was null for " + fileName);
                }
            } else {
                // Use legacy file paths for Android 9 (P) and below
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                    Log.e(TAG + "_CSV", "Failed to create Downloads directory.");
                    // No need for a return here, let it fail and show the toast at the end.
                }
                File file = new File(downloadsDir, fileName);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(fileContent.getBytes(StandardCharsets.UTF_8));
                    success = true;
                }
            }
        } catch (IOException e) {
            Log.e(TAG + "_CSV", "IOException writing CSV file " + fileName, e);
        } catch (SecurityException se) {
            Log.e(TAG + "_CSV", "SecurityException writing CSV file " + fileName, se);
        }
        return success;

    }

    private void onDownloadCsvClicked() {
        // Check for storage permission once before starting any downloads on older versions.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (!EasyPermissions.hasPermissions(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                Toast.makeText(this, "Storage permission is required to save CSV files.", Toast.LENGTH_LONG).show();
                // Optionally, you could request the permission here again.
                return;
            }
        }

        Toast.makeText(this, "Generating CSV files...", Toast.LENGTH_SHORT).show();

        executorService.execute(() -> {
            AppDatabase db = repository.getDatabase();
            TagStatusDao tagStatusDao = db.tagStatusDao();
            TagDataDao tagDataDao = db.tagDataDao();
            RaceContextDao raceContextDao = db.raceContextDao();

            // Fetch data once for all reports
            List<TagStatus> tagStatuses = tagStatusDao.getAllSync();
            List<TagData> allTagData = tagDataDao.getAllTagDataSync();
            RaceContext currentContext = raceContextDao.latestSync();
            long currentGunTimeMs = (currentContext != null) ? currentContext.gunTimeMs : 0L;

            Map<Integer, List<TagData>> samplesByTrackId = new HashMap<>();
            if (allTagData != null) {
                for (TagData sample : allTagData) {
                    samplesByTrackId.computeIfAbsent(sample.trackId, k -> new ArrayList<>()).add(sample);
                }
            }

            List<CsvExporter.CsvFile> files_to_download = CsvExporter.generateCsvFiles(tagStatuses,allTagData);
            StringBuilder snackbarText = new StringBuilder();
            String successEmoji = "✅ ";
            String failureEmoji = "❌ ";
            int successCount = 0;

            for ( CsvExporter.CsvFile f: files_to_download){
                boolean success = generateAndSaveCsv(f.filename,f.content);
                if ( success ){
                    successCount++;
                    // Prepend the success emoji to the success message
                    snackbarText.append(successEmoji)
                            .append(f.filename)
                            .append(" (")
                            .append(f.rowCount)
                            .append(" rows)\n");
                }
                else{
                    // Prepend the failure emoji to the failure message
                    snackbarText.append(failureEmoji)
                            .append("Failed: ")
                            .append(f.filename)
                            .append("\n");
                }
            }
// Remove the last newline character for cleaner display
            if (snackbarText.length() > 0) {
                snackbarText.setLength(snackbarText.length() - 1);
            }

            final String finalMessage = snackbarText.toString();
            final int finalSuccessCount = successCount;
            final int totalFiles = files_to_download.size();

            mainThreadHandler.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    // Use a Snackbar for a richer, modern notification
                    // We need a root view from the layout, like a CoordinatorLayout or even the root FrameLayout.
                    // findViewById(android.R.id.content) is a reliable way to get the root view.
                    Snackbar snackbar = Snackbar.make(findViewById(android.R.id.content), finalMessage, Snackbar.LENGTH_INDEFINITE);
                    snackbar.setAction("DISMISS", view -> {
                        // The action is simply to dismiss the snackbar, so the click listener can be empty.
                    });
                    // Set the text color based on the overall result
                    if (finalSuccessCount == totalFiles) {
                        // All successful: Green text
                        snackbar.setTextColor(getColor(R.color.success_green)); // Define this color in colors.xml
                    } else if (finalSuccessCount == 0 && totalFiles > 0) {
                        // All failed: Red text
                        snackbar.setTextColor(getColor(R.color.failure_red));   // Define this color in colors.xml
                    } else {
                        // Mixed result: Default text color
                    }

                    // To show a multi-line message, we need to access the TextView inside the Snackbar
                    TextView snackbarTextView = snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
                    if (snackbarTextView != null) {
                        snackbarTextView.setMaxLines(5); // Allow up to 5 lines
                    }

                    snackbar.show();
                }
            });


        });
    }

    // --- Permissions ---

    private String[] requiredPerms() {
        List<String> p = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= 31) { // Android 12+
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (Build.VERSION.SDK_INT >= 33) {
                p.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else { // Android 6–11
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
            if (Build.VERSION.SDK_INT >= 29 && scansCanRunInBackground()) {
                // If you truly need background scanning on 29–30,
                // request this only after FINE is granted.
                p.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            }
        }

        if (Build.VERSION.SDK_INT <= 28) { // for CSV saving
            p.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        return p.toArray(new String[0]);
    }

    private boolean scansCanRunInBackground() {
        // Return true only if your app actually scans while NOT visible on Android 10–11.
        // If you run scans strictly in a foreground service while app is visible,
        // set this to false to avoid the background location prompt.
        return false;
    }

    private boolean needsLocationForThisSDK() {
        // Location ON is required for BLE scans on Android 6–11.
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S;
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
                    "Patriot Split Logger needs Bluetooth and (on some versions) Location to scan runner tags.",
                    RC_PERMS,
                    perms
            );
        }
        updateButtonStates();
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
            } catch (Settings.SettingNotFoundException e) {
                Log.e(TAG, "isLocationEnabled: Failed to get location mode", e);
                return false;
            }
        }
    }

    @Override
    public void onPermissionsGranted(int requestCode, @NonNull List<String> perms) {
        if (requestCode == RC_PERMS) {
            Log.d(TAG, "Permissions granted: " + perms);
            if (this.gunTimeMs > 0 && !isScanningActive) {
                ensureScanningIfReady();
            }
        }
    }

    @Override
    public void onPermissionsDenied(int requestCode, @NonNull List<String> perms) {
        if (requestCode == RC_PERMS) {
            Log.w(TAG, "Permissions denied: " + perms);
            if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
                Toast.makeText(this, "Permissions permanently denied. Enable them in App Settings.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Required permissions denied.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }
}
