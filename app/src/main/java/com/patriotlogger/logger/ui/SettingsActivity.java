package com.patriotlogger.logger.ui;

import com.patriotlogger.logger.logic.RssiSmoother;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.AttrRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
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
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.RepositoryVoidCallback;
import com.patriotlogger.logger.data.Setting;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.logic.RssiData;
import com.patriotlogger.logger.service.BleScannerService;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// ADDITIONS (match existing approach)
import android.os.Build;
import android.content.ContentValues;
import android.provider.MediaStore;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.io.FileOutputStream;

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
    private TextView labelApproachingThreshold;
    private TextView labelArrivedThreshold;
    private Button buttonSaveChanges;
    private Button buttonStartCalibration, buttonHere, buttonArriving, buttonClearCalibration, buttonDownloadData;
    private LineChart chartCalibration;

    private Setting currentSettings;
    private boolean isCalibrating = false;
    private long calibrationStartTime = 0L;

    private float lastSmoothedRssi = 0.0f;

    // Replace bus observer with repository-backed calibration RSSI
    private final Observer<List<RssiData>> calibrationObserver = samples -> {
        if (!isCalibrating) return;
        if (samples == null || samples.isEmpty()) return;

        // we only render the delta since lastSampleTimestamp to avoid re-drawing entire series
        List<RssiData> pending = new ArrayList<>();
        for (int i = samples.size() - 1; i >= 0; i--) {
            RssiData s = samples.get(i);
            if (s.timestampMs > lastSampleTimestamp) {
                pending.add(0, s); // keep ascending time order
            } else break;
        }
        if (!pending.isEmpty()) {
            processSamples(pending);
        }
    };

    protected void processSamples(List<RssiData> newSamples){
        if (isCalibrating && newSamples != null && !newSamples.isEmpty()) {
            long s = System.currentTimeMillis();
            Log.d("SettingsActivity", "Starring Chart Display");

            LineData lineData = chartCalibration.getData();
            if (lineData == null) return;
            lineData.setDrawValues(false);
            LineDataSet smoothedSet = (LineDataSet) lineData.getDataSetByIndex(1);
            LineDataSet rawSet = (LineDataSet) lineData.getDataSetByIndex(0);
            for (RssiData sample : newSamples) {
                Log.d("SettingsActivity", "charting sample: " + sample);

                float elapsedTimeInSeconds = (sample.timestampMs - calibrationStartTime) / 1000f;

                rawSet.addEntry(new Entry(elapsedTimeInSeconds, sample.rssi));
                smoothedSet.addEntry(new Entry(elapsedTimeInSeconds, sample.smoothedRssi));

                lastSmoothedRssi = sample.smoothedRssi;
            }

            lastSampleTimestamp = newSamples.get(newSamples.size() - 1).timestampMs;

            lineData.notifyDataChanged();
            chartCalibration.notifyDataSetChanged();

            chartCalibration.setVisibleXRangeMaximum(GRAPH_MAX_SECS);
            chartCalibration.moveViewToX(smoothedSet.getXMax()); // Move to the newest entry's X value
            RssiData mostRecent = newSamples.get(newSamples.size()-1);
            float elapsedTimeInSeconds = (mostRecent.timestampMs - calibrationStartTime) / 1000f;
            if (elapsedTimeInSeconds > GRAPH_MAX_SECS) {
                chartCalibration.getXAxis().setAxisMinimum(elapsedTimeInSeconds - GRAPH_MAX_SECS);
                chartCalibration.getXAxis().setAxisMaximum(elapsedTimeInSeconds);
            }

            Log.d("SettingsActivity", "Finished Starring Chart Display in " + (System.currentTimeMillis() - s));
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
        labelApproachingThreshold = findViewById(R.id.labelApproachingThreshold);
        labelArrivedThreshold = findViewById(R.id.labelArrivedThreshold);
        buttonSaveChanges = findViewById(R.id.buttonSaveChanges);
        buttonStartCalibration = findViewById(R.id.buttonStartCalibration);
        buttonHere = findViewById(R.id.buttonHere);
        buttonArriving = findViewById(R.id.buttonArriving);
        buttonClearCalibration = findViewById(R.id.buttonClearCalibration);
        buttonDownloadData = findViewById(R.id.buttonDownloadCsv);
        chartCalibration = findViewById(R.id.chartCalibration);

        // Observe repository calibration stream instead of the old bus
        repository.getLiveCalibrationRssi().observe(this, calibrationObserver);

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
        buttonDownloadData.setOnClickListener(v -> downloadData());
    }

    private void setupSliderListeners() {
        sliderApproachingThreshold.addOnChangeListener((slider, value, fromUser) -> {
            labelApproachingThreshold.setText(String.format(Locale.US, "Approaching Threshold (%.0f)", value));
        });

        sliderArrivedThreshold.addOnChangeListener((slider, value, fromUser) -> {
            labelArrivedThreshold.setText(String.format(Locale.US, "Arrival Threshold (%.0f)", value));
        });
    }

    private void setupChart() {
        int axisColor = getThemeColor(android.R.attr.textColorPrimary);
        int dataSetColor = getThemeColor(androidx.appcompat.R.attr.colorPrimary);

        chartCalibration.getDescription().setEnabled(true);
        chartCalibration.getDescription().setText("RSSI over Time");
        chartCalibration.getDescription().setTextColor(axisColor);
        chartCalibration.setTouchEnabled(true);
        chartCalibration.setDragEnabled(false);
        chartCalibration.setScaleEnabled(true);
        chartCalibration.setDrawGridBackground(false);
        chartCalibration.getLegend().setTextColor(axisColor);

        YAxis leftAxis = chartCalibration.getAxisLeft();
        leftAxis.setAxisMaximum(-25f);
        leftAxis.setAxisMinimum(-100f);
        leftAxis.setTextColor(axisColor);
        leftAxis.setGridColor(axisColor);
        leftAxis.setAxisLineColor(axisColor);

        chartCalibration.getAxisRight().setEnabled(false);

        XAxis xAxis = chartCalibration.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setAxisMinimum(0f);
        xAxis.setAxisMaximum(GRAPH_MAX_SECS);
        xAxis.setTextColor(axisColor);
        xAxis.setGridColor(axisColor);
        xAxis.setAxisLineColor(axisColor);

        clearChart();
    }

    private int getThemeColor(@AttrRes int colorAttr) {
        TypedArray ta = this.getTheme().obtainStyledAttributes(new int[]{colorAttr});
        int color = ta.getColor(0, Color.BLACK);
        ta.recycle();
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
        labelApproachingThreshold.setText(String.format(Locale.US, "Approaching Threshold (%d)", setting.approaching_threshold));
        labelArrivedThreshold.setText(String.format(Locale.US, "Arrival Threshold (%d)", setting.arrived_threshold));
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
        repository.setSavingEnabled(false);                // don’t persist; publish to calibration stream
        repository.clearCalibrationRssi();                 // clear stream buffer
        buttonStartCalibration.setText("Stop");
        clearChart();
        calibrationStartTime = System.currentTimeMillis();
        startService(new Intent(this, BleScannerService.class).setAction(BleScannerService.ACTION_START));
    }

    private void stopCalibration() {
        isCalibrating = false;
        repository.setSavingEnabled(true);
        buttonStartCalibration.setText("Start");
        startService(new Intent(this, BleScannerService.class).setAction(BleScannerService.ACTION_STOP));
    }

    private void clearChart() {
        LineData lineData = new LineData(createRawDataSet(), createSmoothedDataSet());
        chartCalibration.setData(lineData);
        chartCalibration.invalidate();
        calibrationStartTime = System.currentTimeMillis();
        chartCalibration.getXAxis().setAxisMinimum(0f);
        chartCalibration.getXAxis().setAxisMaximum(30f);
    }

    private LineDataSet createSmoothedDataSet() {
        LineDataSet set = new LineDataSet(new ArrayList<>(), "Smoothed RSSI");
        set.setDrawCircles(false);
        set.setColor(Color.RED);
        set.setLineWidth(6f);
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        return set;
    }

    private LineDataSet createRawDataSet() {
        LineDataSet set = new LineDataSet(new ArrayList<>(), "Raw RSSI");
        set.setDrawCircles(true);
        set.setCircleColor(Color.BLUE);
        set.setCircleRadius(2f);
        set.setColor(Color.TRANSPARENT);
        set.setLineWidth(0f);
        return set;
    }

    private void setSliderFromLastRssi(Slider slider) {
        int roundedRssi = Math.round(lastSmoothedRssi);
        Log.w(TAG_ACTIVITY, "Setting Slider lastRssi = " + roundedRssi);
        if (isCalibrating && roundedRssi != 0) {
            slider.setValue(roundedRssi);
        }
    }

    private void downloadData() {
        LineData lineData = chartCalibration.getData();
        if (lineData == null || lineData.getDataSetCount() < 2) {
            Toast.makeText(this, "No data to download", Toast.LENGTH_SHORT).show();
            return;
        }

        LineDataSet smoothedSet = (LineDataSet) lineData.getDataSetByIndex(1);
        LineDataSet rawSet = (LineDataSet) lineData.getDataSetByIndex(0);

        if (smoothedSet.getEntryCount() == 0) {
            Toast.makeText(this, "No data to download", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder sb = new StringBuilder(32 * smoothedSet.getEntryCount());
        sb.append("timestamp,tagid,rssi,smoothedrssi\n");

        for (int i = 0; i < smoothedSet.getEntryCount(); i++) {
            Entry smoothedEntry = smoothedSet.getEntryForIndex(i);
            Entry rawEntry = rawSet.getEntryForIndex(i);

            float elapsedSeconds = smoothedEntry.getX();
            long timestampMs = calibrationStartTime + (long)(elapsedSeconds * 1000);

            int tagId = 0;
            int rssi = Math.round(rawEntry.getY());
            float smoothedRssi = smoothedEntry.getY();

            sb.append(String.format(Locale.US, "%d,%d,%d,%.2f\n",
                    timestampMs, tagId, rssi, smoothedRssi));
        }

        String fileName = "calibration_data_" + System.currentTimeMillis() + ".csv";
        boolean ok = saveCsvToDownloads(fileName, sb.toString());
        if (ok) {
            Toast.makeText(this, "Saved to Downloads: " + fileName, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Error saving CSV (see log).", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean saveCsvToDownloads(String fileName, String fileContent) {
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
                            outputStream.write(fileContent.getBytes(StandardCharsets.UTF_8));
                            success = true;
                        }
                    }
                }
            } else {
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (downloadsDir.exists() || downloadsDir.mkdirs()) {
                    File file = new File(downloadsDir, fileName);
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        fos.write(fileContent.getBytes(StandardCharsets.UTF_8));
                        success = true;
                    }
                }
            }
        } catch (IOException | SecurityException e) {
            Log.e(TAG_ACTIVITY + "_CSV", "Error writing CSV file " + fileName, e);
        }
        return success;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
