package com.patriotlogger.logger.ui;

import com.patriotlogger.logger.logic.RssiSmoother;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.AttrRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Observer;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.patriotlogger.logger.R;
import com.patriotlogger.logger.data.CalibrationSample;
import com.patriotlogger.logger.data.CalibrationTagDataBus;
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.RepositoryCallback;
import com.patriotlogger.logger.data.RepositoryVoidCallback;
import com.patriotlogger.logger.data.Setting;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.service.BleScannerService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class SettingsActivity extends AppCompatActivity {

    private static final String TAG_ACTIVITY = "SettingsActivity";
    public static final int GRAPH_MAX_SECS = 30;

    private final Handler calibrationHandler =  new Handler(Looper.getMainLooper());
    private long lastSampleTimestamp = 0L;
    private static final int CHART_UPDATE_INTERVAL_MS = 50;
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
    //private float lastRssi = 0f;
    private RssiSmoother calibrationSmoother;
    protected void processSamples( List<CalibrationSample> newSamples){
        if (isCalibrating && newSamples != null && !newSamples.isEmpty()) {
            long s = System.currentTimeMillis();
            Log.i("SettingsActivity", "Starring Chart Display");

            LineData lineData = chartCalibration.getData();
            if (lineData == null) return;
            lineData.setDrawValues(false);
            LineDataSet set = (LineDataSet) lineData.getDataSetByIndex(0);
            for (CalibrationSample sample : newSamples) {
                float smoothedRssi = calibrationSmoother.getSmoothedRssi(sample.rssi, currentSettings);
                float elapsedTimeInSeconds = (sample.timestampMs - calibrationStartTime) / 1000f;
                set.addEntry(new Entry(elapsedTimeInSeconds, smoothedRssi));

            }

            lastSampleTimestamp = newSamples.get(newSamples.size() - 1).timestampMs;

            lineData.notifyDataChanged();
            chartCalibration.notifyDataSetChanged();

            chartCalibration.setVisibleXRangeMaximum(GRAPH_MAX_SECS);
            chartCalibration.moveViewToX(set.getXMax()); // Move to the newest entry's X value
            CalibrationSample mostRecent = newSamples.get(0);
            float elapsedTimeInSeconds = (mostRecent.timestampMs - calibrationStartTime) / 1000f;
            if (elapsedTimeInSeconds > GRAPH_MAX_SECS) {
                chartCalibration.getXAxis().setAxisMinimum(elapsedTimeInSeconds - GRAPH_MAX_SECS);
                chartCalibration.getXAxis().setAxisMaximum(elapsedTimeInSeconds);
            }

            Log.i("SettingsActivity", "Finished Starring Chart Display in " + (System.currentTimeMillis() - s));
        }

    }

    private final Observer<List<CalibrationSample>> calibrationObserver = tagDataList -> {
        if (!isCalibrating) return;
        List<CalibrationSample> pending = CalibrationTagDataBus.consumeAll();
        if (!pending.isEmpty()) {
            processSamples(pending);
        }
    };

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

        CalibrationTagDataBus.getData().observe(this, calibrationObserver);

        calibrationSmoother = new RssiSmoother();
        setupButtons();
        setupSliderListeners();
        setupChart();
        loadAndObserveSettings();

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
        // --- THEME-AWARE COLOR SETUP ---
        // 1. Resolve the correct colors from the current theme (light or dark).

        // For axes and labels, use the standard Android attribute for primary text color.
        // This is guaranteed to be readable on the default window background.
        int axisColor = getThemeColor(android.R.attr.textColorPrimary);

        // For the data line and circles, use the theme's primary accent color.
        // This is typically light in dark mode and dark in light mode.
        int dataSetColor = getThemeColor(androidx.appcompat.R.attr.colorPrimary);

        // --- GENERAL CHART SETUP ---
        chartCalibration.getDescription().setEnabled(true);
        chartCalibration.getDescription().setText("RSSI over Time");
        chartCalibration.getDescription().setTextColor(axisColor);
        chartCalibration.setTouchEnabled(true);
        chartCalibration.setDragEnabled(false);
        chartCalibration.setScaleEnabled(true);
        chartCalibration.setDrawGridBackground(false);
        chartCalibration.getLegend().setTextColor(axisColor);

        // --- Y-AXIS (LEFT) SETUP ---
        YAxis leftAxis = chartCalibration.getAxisLeft();
        leftAxis.setAxisMaximum(-25f);
        leftAxis.setAxisMinimum(-100f);
        leftAxis.setTextColor(axisColor); // Use theme color for labels
        leftAxis.setGridColor(axisColor); // Use theme color for grid lines
        leftAxis.setAxisLineColor(axisColor); // Use theme color for the axis line itself

        // --- Y-AXIS (RIGHT) SETUP ---
        chartCalibration.getAxisRight().setEnabled(false);

        // --- X-AXIS (BOTTOM) SETUP ---
        XAxis xAxis = chartCalibration.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM); // Ensure X-axis is at the bottom
        xAxis.setAxisMinimum(0f);
        xAxis.setAxisMaximum(GRAPH_MAX_SECS);
        xAxis.setTextColor(axisColor); // Use theme color for labels
        xAxis.setGridColor(axisColor); // Use theme color for grid lines
        xAxis.setAxisLineColor(axisColor); // Use theme color for the axis line itself

        clearChart();
    }

    private int getThemeColor(@AttrRes int colorAttr) {
        // 'this' refers to the Activity context
        TypedArray ta = this.getTheme().obtainStyledAttributes(new int[]{colorAttr});
        int color = ta.getColor(0, Color.BLACK); // Default to black if not found
        ta.recycle(); // Always recycle TypedArray
        return color;
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
            startCalibration();
        }
    }

    private void startCalibration() {
        isCalibrating = true;
        repository.setSavingEnabled(false);
        buttonStartCalibration.setText("Stop");
        clearChart();
        calibrationStartTime = System.currentTimeMillis();
        startService(new Intent(this, BleScannerService.class).setAction(BleScannerService.ACTION_START));
        //calibrationHandler.post(fetchNewSamplesRunnable);
    }

    private void stopCalibration() {
        isCalibrating = false;
        repository.setSavingEnabled(true);
        buttonStartCalibration.setText("Start");
        startService(new Intent(this, BleScannerService.class).setAction(BleScannerService.ACTION_STOP));
        //calibrationHandler.removeCallbacks(fetchNewSamplesRunnable);
    }

    private void clearChart() {
        chartCalibration.setData(new LineData(createDataSet()));
        chartCalibration.invalidate();
        calibrationStartTime = System.currentTimeMillis();
        chartCalibration.getXAxis().setAxisMinimum(0f);
        chartCalibration.getXAxis().setAxisMaximum(30f);
    }

    private LineDataSet createDataSet() {
        LineDataSet set = new LineDataSet(new ArrayList<>(), "RSSI");
        set.setDrawCircles(true);
        set.setCircleColor(Color.RED);
        set.setCircleRadius(3f);
        set.setColor(Color.RED);
        set.setLineWidth(2f);
        return set;
    }

    private void setSliderFromLastRssi(Slider slider) {
        float lastRssi = calibrationSmoother.getLastRssi();
        int roundedRssi = Math.round(lastRssi);
        Log.d(TAG_ACTIVITY, "Setting Slider lastRssi = " + roundedRssi);
        if (isCalibrating && roundedRssi != 0) {
            slider.setValue(roundedRssi);
        }
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Prevent memory leaks by removing any pending runnables
        //calibrationHandler.removeCallbacks(fetchNewSamplesRunnable);
    }

}
