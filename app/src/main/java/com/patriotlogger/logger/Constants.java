package com.patriotlogger.logger;

public class Constants {
    public static final String NOTIFICATION_CHANNEL_ID = "ble_scanner_channel";
    public static final int NOTIFICATION_ID = 1;
    public static final int TAG_LOST_TIMEOUT_MILLIS = 1000;
    public static final String UPLOAD_URL = "https://splitriot.onrender.com/upload";
    public static final String WORK_MANAGER_TAG = "upload_work";
    public static final String INBOUND_PAYLOAD_KEY = "inbound_payload";

    // Kalman Filter constants
    public static final double KALMAN_Q = 0.001;
    public static final double KALMAN_R = 0.1;
    public static final double KALMAN_P = 0.05;

    // Peak detection
    public static final int HERE_SAMPLES_LOWER_THAN_PEAK = 4;
}