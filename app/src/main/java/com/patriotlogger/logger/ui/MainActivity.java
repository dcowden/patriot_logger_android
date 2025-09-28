package com.patriotlogger.logger.ui;

import android.Manifest;
import android.content.Intent;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.patriotlogger.logger.R;
import com.patriotlogger.logger.data.RaceContext;
import com.patriotlogger.logger.data.Racer;
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.service.BleScannerService;
import com.patriotlogger.logger.workers.UploadWorker;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends AppCompatActivity implements EasyPermissions.PermissionCallbacks {

    private static final int RC_PERMS = 100;

    private MainViewModel vm;
    private TextView tvHeader, tvClock;
    private Button btnAction, btnDebug, btnStop;
    private RunnerAdapter adapter;
    private final Handler clockHandler = new Handler();
    private long gunTimeMs = 0L;
    private boolean isScanningActive = false;

    private final ActivityResultLauncher<Intent> openLocationSettingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                if (isLocationEnabled()) {
                    ensureScanningIfReady();
                }
            });

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setIcon(R.drawable.patriot_logo);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        vm = new ViewModelProvider(this).get(MainViewModel.class);
        tvHeader = findViewById(R.id.tvHeader);
        tvClock = findViewById(R.id.tvClock);
        btnAction = findViewById(R.id.btnAction);
        btnStop = findViewById(R.id.btnStop);
        btnDebug = findViewById(R.id.btnDebug);

        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RunnerAdapter();
        recycler.setAdapter(adapter);

        btnDebug.setOnClickListener(v -> startActivity(new Intent(this, DebugActivity.class)));

        vm.getContext().observe(this, ctx -> {
            tvHeader.setText(vm.headerText(ctx));
            long newGunTimeMs = (ctx != null) ? ctx.gunTimeMs : 0L;
            // If we have a new gun time from context and no user-initiated gun time, use it.
            if (gunTimeMs == 0 && newGunTimeMs > 0) {
                gunTimeMs = newGunTimeMs;
            }
            updateButtonStates();
            // Auto-start scanning if context with gun time is loaded and we aren't already scanning
            if (gunTimeMs > 0 && !isScanningActive) {
                 ensureScanningIfReady();
            }
        });
        vm.getStatuses().observe(this, list -> adapter.submit(list));

        btnAction.setOnClickListener(v -> onActionButtonClicked());
        btnStop.setOnClickListener(v -> onStopScanningButtonClicked());

        handleDeepLink(getIntent());
        startClock();
        requestAllRuntimePerms(); // Request perms, but don't auto-start scanning from here
        updateButtonStates();     // Initial button state
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleDeepLink(intent);
    }

    private void handleDeepLink(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        String data = intent.getData().getQueryParameter("data");
        if (data == null) return;
        try {
            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            String json = new String(bytes, StandardCharsets.UTF_8);
            JsonObject root = new Gson().fromJson(json, JsonObject.class);

            RaceContext ctx = new RaceContext();
            ctx.eventName = root.get("event_name").getAsString();
            ctx.raceName  = root.get("race_name").getAsString();
            ctx.raceId    = root.get("race_id").getAsInt();
            ctx.gunTimeMs = root.get("gun_time").getAsLong(); // This gunTime will be picked up by observer
            ctx.authToken = root.get("auth_token").getAsString();
            ctx.baseUrl   = "https://splitriot.onrender.com/upload";
            ctx.createdAtMs = System.currentTimeMillis();

            JsonArray splits = root.get("split_assignments").getAsJsonArray();
            if (splits.size() > 0) {
                JsonObject s0 = splits.get(0).getAsJsonObject();
                ctx.splitAssignmentId = s0.get("id").getAsInt();
                ctx.splitName = s0.get("split_name").getAsString();
                List<Racer> racers = new ArrayList<>();
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
                    racers.add(rr);
                }
                Repository.get(this).upsertRacers(racers);
            }
            Repository.get(this).upsertContext(ctx); // This will trigger the observer
        } catch (Exception e) {
            startActivity(new Intent(this, ErrorActivity.class).putExtra("err", e.toString()));
        }
    }

    private void onActionButtonClicked() {
        if (!isScanningActive) { // Current state is "Start"
            if (gunTimeMs == 0) { // If clock hasn't been started by context/deeplink
                gunTimeMs = System.currentTimeMillis();
                vm.setClockNow(gunTimeMs); // Persist this user-initiated gun time
            }
            ensureScanningIfReady(); // This will set isScanningActive = true if successful
            btnStop.setVisibility(Button.VISIBLE);
        }

    }

    private void onStopScanningButtonClicked() {
        if ( isScanningActive){
            Intent svc = new Intent(this, BleScannerService.class).setAction(BleScannerService.ACTION_STOP);
            startService(svc);
            btnStop.setText("Restart Scanning");
            Log.w("BLE","Stopping Scanning Service");

        }
        else{
            Intent svc = new Intent(this, BleScannerService.class).setAction(BleScannerService.ACTION_START);
            startService(svc);
            btnStop.setText("Stop Scanning");
            Log.w("BLE","Starting Scanning Service");

        }
        isScanningActive =  !isScanningActive;

    }

    private void updateButtonStates() {
        if (isScanningActive) {
            btnAction.setText("Upload");
            btnStop.setVisibility(View.VISIBLE);
        } else {
            btnAction.setText("Start");
            btnStop.setVisibility(View.GONE);
        }
    }

    private void startClock() {
        clockHandler.postDelayed(new Runnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                if (gunTimeMs > 0) { // Display clock if gunTime is set, regardless of scanning state
                    long diff = now - gunTimeMs;
                    long m = (diff / 1000) / 60;
                    long s = (diff / 1000) % 60;
                    tvClock.setText(String.format("%d:%02d%s", m, s, isScanningActive ? "" : " (Paused)"));
                } else {
                    tvClock.setText("--:--");
                }
                clockHandler.postDelayed(this, 250);
            }
        }, 250);
    }

    private void ensureScanningIfReady() {
        if (gunTimeMs <= 0) { // Can't scan without a gun time
            Toast.makeText(this, "Clock not started. Cannot start scanning.", Toast.LENGTH_SHORT).show();
            updateButtonStates(); // Ensure buttons reflect non-scanning state
            Log.w("SCAN","Cannot Scan: Clock not started");
            return;
        }
        if (!hasAllPerms()) {
            Toast.makeText(this, "Cannot Scan:Don't have all the perms", Toast.LENGTH_SHORT).show();
            requestAllRuntimePerms(); // Will trigger permission request flow
            // updateButtonStates will be handled by permission result callbacks
            Log.w("SCAN","Cannot Scan:Don't have all the perms");
            return;
        }
        if (needsLocationForThisSDK() && !isLocationEnabled()) {
            Toast.makeText(this, "Turn ON Location to enable BLE scanning", Toast.LENGTH_SHORT).show();
            openLocationSettingsLauncher.launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            Log.w("SCAN","Turn ON Location to enable BLE scanning");
            return;
        }
        
        Intent svc = new Intent(this, BleScannerService.class).setAction(BleScannerService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
        isScanningActive = true;
        updateButtonStates();
    }

    // ===== Permissions (EasyPermissions) =====

    private String[] requiredPerms() {
        List<String> list = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_SCAN);
            list.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (Build.VERSION.SDK_INT >= 33) {
                list.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return list.toArray(new String[0]);
    }

    private boolean needsLocationForThisSDK() {
        // Only true for Android < 12 (API < 31) if BLUETOOTH_SCAN neverForLocation is used.
        return Build.VERSION.SDK_INT < 31;
    }

    private boolean hasAllPerms() {
        return EasyPermissions.hasPermissions(this, requiredPerms());
    }

    @AfterPermissionGranted(RC_PERMS)
    private void requestAllRuntimePerms() {
        String[] perms = requiredPerms();
        if (EasyPermissions.hasPermissions(this, perms)) {
            // Perms already granted. User can press start now.
            // No automatic scan start here, ensureScanningIfReady will be called by button click.
        } else {
            EasyPermissions.requestPermissions(
                    this,
                    "Patriot Split Logger needs Bluetooth and (in some versions) Location to scan runner tags.",
                    RC_PERMS,
                    perms
            );
        }
        updateButtonStates(); // Reflect current state after permission check
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
                return false;
            }
        }
    }

    @Override
    public void onPermissionsGranted(int requestCode, @NonNull List<String> perms) {
        if (requestCode == RC_PERMS) {
            // User has granted permissions. They may now need to click "Start" again.
            Toast.makeText(this, "Permissions granted. You can now start scanning.", Toast.LENGTH_SHORT).show();
            ensureScanningIfReady(); // Attempt to start if conditions are met (e.g. after location enabled)
        }
        updateButtonStates();
    }

    @Override
    public void onPermissionsDenied(int requestCode, @NonNull List<String> perms) {
        if (requestCode == RC_PERMS) {
            if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
                Toast.makeText(this, "Permissions permanently denied. Enable them in App Settings.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Permissions denied. Scanning disabled.", Toast.LENGTH_SHORT).show();
            }
        }
        updateButtonStates();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
        // The EasyPermissions library will then call onPermissionsGranted or onPermissionsDenied
    }
}
