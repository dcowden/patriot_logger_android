package com.patriotlogger.logger.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "settings_config")
public class Setting {

    public static final int SETTINGS_ID = 1;


    //handlers.add(new TcaWithFallbackHandler(0.30f, -70.0, 2.0, 4.0, 1.5, 40, 6, 12.0));
    // --- existing defaults (kept) ---
    public static final boolean DEFAULT_RETAIN_SAMPLES = true;
    public static final int DEFAULT_ARRIVED_THRESHOLD = -70;
    public static final int DEFAULT_APPROACHING_THRESHOLD = -90;
    public static final float DEFAULT_RSSI_AVERAGING_ALPHA = 0.3f;

    // --- NEW: TCA + filters defaults ---
    public static final double DEFAULT_TCA_HERE_METERS       = 4.0;
    public static final double DEFAULT_TCA_APPROACH_METERS   = 12.0;
    public static final double DEFAULT_TCA_THRESHOLD_SEC     = 1.5;
    public static final int    DEFAULT_TCA_WINDOW_SIZE       = 12;
    public static final int    DEFAULT_TCA_MIN_POINTS        = 6;
    public static final double DEFAULT_PATH_LOSS_N           = 2.0;
    public static final double DEFAULT_TX_POWER_AT_1M_DBM    = -70.0;

    public static final int    DEFAULT_FILTER_MIN_RSSI       = -105;
    public static final int    DEFAULT_FILTER_MAX_RSSI       =  -25;

    // --- NEW: runtime cadence defaults ---
    public static final int    DEFAULT_TAGDATA_FLUSH_MS      = 200; // repo flush cadence
    public static final int    DEFAULT_SWEEP_INTERVAL_MS     = 1000; // service sweep cadence
    public static final int    DEFAULT_ABANDONED_TIMEOUT_MS  = 5000; // HERE -> TIMED_OUT

    // --- NEW: handler EMA default ---
    public static final float  DEFAULT_TCA_ALPHA             = 0.30f;

    @PrimaryKey
    public int id = SETTINGS_ID;

    // behavior flags
    public Boolean retain_samples = DEFAULT_RETAIN_SAMPLES;

    // legacy UI sliders (still used in Settings screen)
    public Integer arrived_threshold = DEFAULT_ARRIVED_THRESHOLD;
    public Integer approaching_threshold = DEFAULT_APPROACHING_THRESHOLD;

    // general RSSI smoothing (for UI only; handler does its own EMA)
    public Float rssi_averaging_alpha = DEFAULT_RSSI_AVERAGING_ALPHA;

    // --- NEW: TCA knobs ---
    /** Distance defining "HERE" crossing in meters. */
    public Double tca_here_meters = DEFAULT_TCA_HERE_METERS;

    /** Distance at which we allow flipping to APPROACHING. */
    public Double tca_approach_meters = DEFAULT_TCA_APPROACH_METERS;

    /** Max allowed time-to-cross (s) to call HERE from regression. */
    public Double tca_threshold_sec = DEFAULT_TCA_THRESHOLD_SEC;

    /** Regression window size (samples). */
    public Integer tca_window_size = DEFAULT_TCA_WINDOW_SIZE;

    /** Minimum points required to run regression. */
    public Integer tca_min_points = DEFAULT_TCA_MIN_POINTS;

    /** Log-distance path loss exponent (environment). */
    public Double path_loss_n = DEFAULT_PATH_LOSS_N;

    /** TX power @1m reference (dBm). */
    public Double tx_power_at_1m_dbm = DEFAULT_TX_POWER_AT_1M_DBM;

    /** EMA alpha used inside the TCA handler. */
    public Float tca_alpha = DEFAULT_TCA_ALPHA;

    // --- NEW: ingest filters ---
    /** Absolute min RSSI to accept. */
    public Integer filter_min_rssi = DEFAULT_FILTER_MIN_RSSI;
    /** Absolute max RSSI to accept. */
    public Integer filter_max_rssi = DEFAULT_FILTER_MAX_RSSI;

    // --- NEW: runtime cadences ---
    /** Repository periodic flush interval (ms) for TagData buffers. */
    public Integer tagdata_flush_ms = DEFAULT_TAGDATA_FLUSH_MS;

    /** BLE sweep cadence used for timeout checks (ms). */
    public Integer sweep_interval_ms = DEFAULT_SWEEP_INTERVAL_MS;

    /** Abandoned HERE timeout (ms) before auto TIMED_OUT. */
    public Integer abandoned_timeout_ms = DEFAULT_ABANDONED_TIMEOUT_MS;

    public Setting() {}

    @Override
    public String toString() {
        return "Setting{" +
                "id=" + id +
                ", retain_samples=" + retain_samples +
                ", arrived_threshold=" + arrived_threshold +
                ", approaching_threshold=" + approaching_threshold +
                ", rssi_averaging_alpha=" + rssi_averaging_alpha +
                ", tca_here_meters=" + tca_here_meters +
                ", tca_approach_meters=" + tca_approach_meters +
                ", tca_threshold_sec=" + tca_threshold_sec +
                ", tca_window_size=" + tca_window_size +
                ", tca_min_points=" + tca_min_points +
                ", path_loss_n=" + path_loss_n +
                ", tx_power_at_1m_dbm=" + tx_power_at_1m_dbm +
                ", tca_alpha=" + tca_alpha +
                ", filter_min_rssi=" + filter_min_rssi +
                ", filter_max_rssi=" + filter_max_rssi +
                ", tagdata_flush_ms=" + tagdata_flush_ms +
                ", sweep_interval_ms=" + sweep_interval_ms +
                ", abandoned_timeout_ms=" + abandoned_timeout_ms +
                '}';
    }
}
