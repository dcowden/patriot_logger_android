package com.patriotlogger.logger.ui;

import android.content.Intent;
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

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.patriotlogger.logger.R;
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


    // Add this Runnable to your SettingsActivity class
    private final Runnable fetchNewSamplesRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isCalibrating) {
                return; // Stop the loop if calibration is turned off
            }

            // Use your existing `getNewSamplesSync` method via the repository
            // This runs on a background thread defined in your repository.
            repository.getNewTagDataSamples(lastSampleTimestamp, new RepositoryCallback<List<TagData>>() {
                @Override
                public void onSuccess(List<TagData> newSamples) {
                    if (isCalibrating && newSamples != null && !newSamples.isEmpty()) {
                        long s = System.currentTimeMillis();
                        Log.i("SettingsActivity", "Starring Chart Display");

                        LineData lineData = chartCalibration.getData();
                        if (lineData == null) return;
                        lineData.setDrawValues(false);
                        LineDataSet set = (LineDataSet) lineData.getDataSetByIndex(0);
                        if (set == null) return;
                        set.setDrawCircles(false);
                        // --- REFACTOR START ---

                        // 1. Add all new data points directly to the DataSet in a single loop.
                        // This is much more efficient than adding to the LineData wrapper.
                        for (TagData sample : newSamples) {
                            float elapsedTimeInSeconds = (sample.timestampMs - calibrationStartTime) / 1000f;
                            set.addEntry(new Entry(elapsedTimeInSeconds, sample.rssi));
                        }

                        // 2. Update the timestamp AFTER processing all new samples.
                        lastSampleTimestamp = newSamples.get(newSamples.size() - 1).timestampMs;

                        // 3. Notify the chart that the underlying data has changed.
                        lineData.notifyDataChanged();
                        chartCalibration.notifyDataSetChanged();

                        // 4. Let the chart manage its own viewport. This is the correct way
                        // to create a scrolling "live" data chart.
                        chartCalibration.setVisibleXRangeMaximum(GRAPH_MAX_SECS);
                        chartCalibration.moveViewToX(set.getXMax()); // Move to the newest entry's X value
                        TagData mostRecent = newSamples.get(0);
                        float elapsedTimeInSeconds = (mostRecent.timestampMs - calibrationStartTime) / 1000f;
                        if (elapsedTimeInSeconds > GRAPH_MAX_SECS) {
                            chartCalibration.getXAxis().setAxisMinimum(elapsedTimeInSeconds - GRAPH_MAX_SECS);
                            chartCalibration.getXAxis().setAxisMaximum(elapsedTimeInSeconds);
                        }

//                        // Get the chart data and set
//                        LineData data = chartCalibration.getData();
//                        data.setDrawValues(false);
//
//                        if (data == null) return;
//                        LineDataSet set = (LineDataSet) data.getDataSetByIndex(0);
//                        set.setDrawCircles(false);
//
//                        TagData mostRecent = newSamples.get(0);
//                        // Add every new sample to the chart
//                        for (TagData sample : newSamples) {
//                            float elapsedTimeInSeconds = (sample.timestampMs - calibrationStartTime) / 1000f;
//                            data.addEntry(new Entry(elapsedTimeInSeconds, sample.rssi), 0);
//                        }
//                        float elapsedTimeInSeconds = (mostRecent.timestampMs - calibrationStartTime) / 1000f;
//                        if (elapsedTimeInSeconds > GRAPH_MAX_SECS) {
//                            chartCalibration.getXAxis().setAxisMinimum(elapsedTimeInSeconds - GRAPH_MAX_SECS);
//                            chartCalibration.getXAxis().setAxisMaximum(elapsedTimeInSeconds);
//                        }
//                        chartCalibration.invalidate();
//
//                        // Update the timestamp to the newest sample received
//                        lastSampleTimestamp = newSamples.get(newSamples.size() - 1).timestampMs;
//
//                        // Notify the chart just once after adding all points
//                        data.notifyDataChanged();
//                        chartCalibration.notifyDataSetChanged();
//                        chartCalibration.moveViewToX(data.getEntryCount());

                        Log.i("SettingsActivity", "Finished Starring Chart Display in " + (System.currentTimeMillis() - s));
                    }

                    // Reschedule the next check
                    if (isCalibrating) {
                        calibrationHandler.postDelayed(fetchNewSamplesRunnable, CHART_UPDATE_INTERVAL_MS);
                    }
                }

                @Override
                public void onError(Exception e) {
                    Log.e("SettingsActivity", "Error fetching new samples", e);
                    // Always reschedule to keep trying
                    if (isCalibrating) {
                        calibrationHandler.postDelayed(fetchNewSamplesRunnable, CHART_UPDATE_INTERVAL_MS);
                    }
                }
            });
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

    private void observeCalibrationData() {
        repository.getThrottledLiveAllTagDataDesc(SCREEN_UPDATE_RATE_MS).observe(this, tagDataList -> {
            if (!isCalibrating || tagDataList == null || tagDataList.isEmpty()) return;

            TagData mostRecent = tagDataList.get(0);
            lastRssi = mostRecent.rssi;

            float elapsedTimeInSeconds = (mostRecent.timestampMs - calibrationStartTime) / 1000f;

            LineData data = chartCalibration.getData();
            if (data != null) {
                LineDataSet set = (LineDataSet) data.getDataSetByIndex(0);
                if (set == null) {
                    set = createDataSet();
                    data.addDataSet(set);
                }
                data.addEntry(new Entry(elapsedTimeInSeconds, lastRssi), 0);
                data.notifyDataChanged();
                chartCalibration.notifyDataSetChanged();

                if (elapsedTimeInSeconds > 30) {
                    chartCalibration.getXAxis().setAxisMinimum(elapsedTimeInSeconds - 30);
                    chartCalibration.getXAxis().setAxisMaximum(elapsedTimeInSeconds);
                }
                chartCalibration.invalidate();
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
        buttonStartCalibration.setText("Stop");
        clearChart();
        calibrationStartTime = System.currentTimeMillis();
        startService(new Intent(this, BleScannerService.class).setAction(BleScannerService.ACTION_START));
        calibrationHandler.post(fetchNewSamplesRunnable);
    }

    private void stopCalibration() {
        isCalibrating = false;
        buttonStartCalibration.setText("Start");
        startService(new Intent(this, BleScannerService.class).setAction(BleScannerService.ACTION_STOP));
        calibrationHandler.removeCallbacks(fetchNewSamplesRunnable);
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
        set.setCircleColor(Color.BLUE);
        set.setCircleRadius(4f);
        set.setColor(Color.BLUE);
        set.setLineWidth(2f);
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
        calibrationHandler.removeCallbacks(fetchNewSamplesRunnable);
    }

}
