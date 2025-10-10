package com.patriotlogger.logger.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.patriotlogger.logger.R;
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.RepositoryCallback;
import com.patriotlogger.logger.data.RepositoryVoidCallback;
import com.patriotlogger.logger.data.Setting;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.logic.RssiSmoother;
import com.patriotlogger.logger.service.BleScannerService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class SettingsActivity extends AppCompatActivity {

    private static final String TAG_ACTIVITY = "SettingsActivity";
    public static final int SCREEN_UPDATE_RATE_MS = 200;
    public static final int GRAPH_MAX_SECS = 30;

    // Add these variables to your SettingsActivity class
    private final Handler calibrationHandler =  new Handler(Looper.getMainLooper());
    private long lastSampleTimestamp = 0L;
    private static final int CHART_UPDATE_INTERVAL_MS = 200; // Poll every 200ms
    private Repository repository;
    private SwitchMaterial switchRetainSamples;
    private Slider sliderApproachingThreshold;
    private Slider sliderArrivedThreshold;
    // private Slider sliderRssiAlpha;
    private TextView labelApproachingThreshold;
    private TextView labelArrivedThreshold;
    // private TextView labelRssiAlpha;
    private Button buttonSaveChanges;
    private Button buttonStartCalibration, buttonHere, buttonArriving, buttonClearCalibration;
    private LineChart chartCalibration;

    private Setting currentSettings;
    private boolean isCalibrating = false;
    private long calibrationStartTime = 0L;
    private float lastRssi = 0f;
    private CalibrationUpdateReceiver calibrationUpdateReceiver;
    private final RssiSmoother calibrationRssiSmoother = new RssiSmoother();
    /**
     * Inner class to receive raw calibration data directly from the BleScannerService.
     * This runs on the UI thread, so it can safely update the chart.
     */
    private class CalibrationUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && BleScannerService.ACTION_CALIBRATION_UPDATE.equals(intent.getAction())) {
                int rssi = intent.getIntExtra(BleScannerService.EXTRA_RSSI, -100);
                long timestamp = intent.getLongExtra(BleScannerService.EXTRA_TIMESTAMP_MS, 0);
                // Directly update the chart with the raw, unfiltered data
                updateChartWithRawSample(rssi, timestamp);
            }
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        repository = Repository.get(getApplicationContext());

        // Initialize UI components
        switchRetainSamples = findViewById(R.id.switchRetainSamples);
        sliderApproachingThreshold = findViewById(R.id.sliderApproachingThreshold);
        sliderArrivedThreshold = findViewById(R.id.sliderArrivedThreshold);
        // sliderRssiAlpha = findViewById(R.id.sliderRssiAlpha);
        labelApproachingThreshold = findViewById(R.id.labelApproachingThreshold);
        labelArrivedThreshold = findViewById(R.id.labelArrivedThreshold);
        // labelRssiAlpha = findViewById(R.id.labelRssiAlpha);
        buttonSaveChanges = findViewById(R.id.buttonSaveChanges);
        buttonStartCalibration = findViewById(R.id.buttonStartCalibration);
        buttonHere = findViewById(R.id.buttonHere);
        buttonArriving = findViewById(R.id.buttonArriving);
        buttonClearCalibration = findViewById(R.id.buttonClearCalibration);
        chartCalibration = findViewById(R.id.chartCalibration);

        setupButtons();
        setupSliderListeners();
        setupChart();
        loadAndObserveSettings();
        //observeCalibrationData();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isCalibrating) {
            stopCalibration();
        }
    }

    private void setupButtons() {
        buttonSaveChanges.setOnClickListener(v -> saveSettings());
        buttonStartCalibration.setOnClickListener(v -> toggleCalibration());
        buttonClearCalibration.setOnClickListener(v -> clearChart());
        buttonHere.setOnClickListener(v -> setSliderFromLastRssi(sliderArrivedThreshold));
        buttonArriving.setOnClickListener(v -> setSliderFromLastRssi(sliderApproachingThreshold));
    }

    private void setupSliderListeners() {
        sliderApproachingThreshold.addOnChangeListener((slider, value, fromUser) -> {
            labelApproachingThreshold.setText(String.format(Locale.US, "Approaching Threshold (%.0f)", value));
        });

        sliderArrivedThreshold.addOnChangeListener((slider, value, fromUser) -> {
            labelArrivedThreshold.setText(String.format(Locale.US, "Arrival Threshold (%.0f)", value));
        });

        // sliderRssiAlpha.addOnChangeListener((slider, value, fromUser) -> {
        //     labelRssiAlpha.setText(String.format(Locale.US, "RSSI Averaging Alpha (%.2f)", value));
        // });
    }

    private void setupChart() {
        chartCalibration.getDescription().setEnabled(false);
        chartCalibration.setTouchEnabled(true);
        chartCalibration.setDragEnabled(false);
        chartCalibration.setScaleEnabled(true);
        chartCalibration.setDrawGridBackground(false);

        YAxis leftAxis = chartCalibration.getAxisLeft();
        leftAxis.setAxisMaximum(-30f);
        leftAxis.setAxisMinimum(-100f);

        chartCalibration.getAxisRight().setEnabled(false);

        XAxis xAxis = chartCalibration.getXAxis();
        xAxis.setAxisMinimum(0f);
        xAxis.setAxisMaximum(30f);

        clearChart();
    }

    private void loadAndObserveSettings() {
        buttonSaveChanges.setEnabled(false);
        repository.getLiveConfig().observe(this, setting -> {
            if (setting != null) {
                currentSettings = setting;
                updateUiWithSettings(setting);
                buttonSaveChanges.setEnabled(true);
            }
        });
    }



    private void updateUiWithSettings(Setting setting) {
        switchRetainSamples.setChecked(setting.retain_samples);
        sliderApproachingThreshold.setValue(setting.approaching_threshold.floatValue());
        sliderArrivedThreshold.setValue(setting.arrived_threshold.floatValue());
        // sliderRssiAlpha.setValue(setting.rssi_averaging_alpha);
        labelApproachingThreshold.setText(String.format(Locale.US, "Approaching Threshold (%d)", setting.approaching_threshold));
        labelArrivedThreshold.setText(String.format(Locale.US, "Arrival Threshold (%d)", setting.arrived_threshold));
        // labelRssiAlpha.setText(String.format(Locale.US, "RSSI Averaging Alpha (%.2f)", setting.rssi_averaging_alpha));
    }

    private void saveSettings() {
        if (currentSettings == null) {
            Toast.makeText(this, "Error: Settings not loaded yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        stopCalibration();
        currentSettings.retain_samples = switchRetainSamples.isChecked();
        currentSettings.approaching_threshold = (int) sliderApproachingThreshold.getValue();
        currentSettings.arrived_threshold = (int) sliderArrivedThreshold.getValue();
        // currentSettings.rssi_averaging_alpha = sliderRssiAlpha.getValue();

        buttonSaveChanges.setEnabled(false);
        repository.upsertConfig(currentSettings, new RepositoryVoidCallback() {
            @Override
            public void onSuccess() {
                buttonSaveChanges.setEnabled(true);
                Toast.makeText(SettingsActivity.this, "Settings saved!", Toast.LENGTH_SHORT).show();
                finish();
            }

            @Override
            public void onError(Exception e) {
                buttonSaveChanges.setEnabled(true);
                Toast.makeText(SettingsActivity.this, "Error saving settings.", Toast.LENGTH_SHORT).show();
                Log.e(TAG_ACTIVITY, "Error saving settings", e);
            }
        });
    }

    private void toggleCalibration() {
        if (isCalibrating) {
            stopCalibration();
        } else {
            // For simplicity, we'll assume a tag ID. In a real app, you might have a dialog to enter this.
            int tagIdToCalibrate = 456; // Example Tag ID
            startCalibration(tagIdToCalibrate);
        }
    }

    private void startCalibration(int tagId) {
        Log.i(TAG_ACTIVITY, "Starting calibration for tagId: " + tagId);
        isCalibrating = true;
        calibrationStartTime = System.currentTimeMillis();
        buttonStartCalibration.setText(R.string.stop_calibration);
        clearChart();

        // 1. Tell the service to enter calibration mode and start broadcasting data for this tag.
        Intent startCalibrationIntent = new Intent(this, BleScannerService.class);
        startCalibrationIntent.setAction(BleScannerService.ACTION_START_CALIBRATION);
        //startCalibrationIntent.putExtra(BleScannerService.EXTRA_TAG_ID, tagId);
        startService(startCalibrationIntent);

        // 2. Register the receiver to listen for the broadcasts.
        calibrationUpdateReceiver = new CalibrationUpdateReceiver();
        IntentFilter filter = new IntentFilter(BleScannerService.ACTION_CALIBRATION_UPDATE);

        // --- THE CORRECT FIX ---
        // For apps targeting SDK 33+, you must always use the version of registerReceiver()
        // that takes an explicit flag. We use RECEIVER_NOT_EXPORTED because this is
        // an internal-only broadcast. This satisfies the lint check for all API levels.
        ContextCompat.registerReceiver(this, calibrationUpdateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        // --- END OF FIX ---
    }

    private void stopCalibration() {
        if (!isCalibrating) return;
        Log.i(TAG_ACTIVITY, "Stopping calibration.");
        isCalibrating = false;
        buttonStartCalibration.setText(R.string.start_calibration);

        // 1. Unregister the receiver to stop listening for updates.
        if (calibrationUpdateReceiver != null) {
            try {
                unregisterReceiver(calibrationUpdateReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG_ACTIVITY, "Receiver was not registered, ignoring.", e);
            }
            calibrationUpdateReceiver = null;
        }

        // 2. Tell the service to exit calibration mode.
        Intent stopCalibrationIntent = new Intent(this, BleScannerService.class);
        stopCalibrationIntent.setAction(BleScannerService.ACTION_STOP_CALIBRATION);
        startService(stopCalibrationIntent);
    }

    private void updateChartWithRawSample(int rssi, long timestamp) {
        if (!isCalibrating) return;

        float smoothedRssi = calibrationRssiSmoother.getNext(rssi, currentSettings.rssi_averaging_alpha);
        this.lastRssi = smoothedRssi; // Update lastRssi with the smoothed value

        LineData data = chartCalibration.getData();
        if (data == null) return;

        LineDataSet set = (LineDataSet)data.getDataSetByIndex(0);
        // If the set is null (e.g., after clearing), create it again.
        if (set == null) {
            set = createNewDataSet();
            data.addDataSet(set);
        }

        float elapsedTimeInSeconds = (timestamp - calibrationStartTime) / 1000f;
        Log.d("SettingsActivity", "Updating Chart, rssi=" + smoothedRssi + " tstamp=" + elapsedTimeInSeconds);
        data.addEntry(new Entry(elapsedTimeInSeconds, rssi), 0);
        data.notifyDataChanged();

        chartCalibration.notifyDataSetChanged();
        //chartCalibration.setVisibleXRangeMaximum(GRAPH_MAX_SECS);
        //chartCalibration.moveViewToX(elapsedTimeInSeconds);
        if (elapsedTimeInSeconds > 30) {
            chartCalibration.getXAxis().setAxisMinimum(elapsedTimeInSeconds - 30);
            chartCalibration.getXAxis().setAxisMaximum(elapsedTimeInSeconds);
        }
        chartCalibration.invalidate();
    }

    private void clearChart() {
        LineData data = new LineData(createNewDataSet());
        chartCalibration.setData(data);
        chartCalibration.invalidate();
    }

    private LineDataSet createNewDataSet() {
        LineDataSet set = new LineDataSet(new ArrayList<>(), "Live RSSI");
        set.setDrawCircles(false);
        set.setDrawValues(false);
        set.setLineWidth(2.5f);
        set.setColor(Color.BLUE);
        return set;
    }

    private void setSliderFromLastRssi(Slider slider) {
        if (isCalibrating && lastRssi != 0f) {
            slider.setValue(lastRssi);
        }
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Prevent memory leaks by removing any pending runnables
        //calibrationHandler.removeCallbacks(fetchNewSamplesRunnable);
    }

}
