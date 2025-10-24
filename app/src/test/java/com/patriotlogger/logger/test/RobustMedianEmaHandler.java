package com.patriotlogger.logger.test;

import com.patriotlogger.logger.data.CalibrationSample;
import com.patriotlogger.logger.data.TagStatus.TagStatusState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * RobustMedianEmaHandler
 *
 * Pipeline:
 *   1) Median over last N samples (default 5) to kill spikes (e.g., 127, single-sample trash).
 *   2) EMA(alpha) on the median stream for smooth, low-lag tracking (default alpha=0.30).
 *   3) FSM driven by slope + light thresholds + hysteresis:
 *        - APPROACHING when value >= approachThreshold and we see >= minRiseCount consecutive rises
 *        - HERE at the first confirmed local max after APPROACHING (slope flip to negative)
 *          OR when value >= hereThreshold and slope flips negative (1-sample confirm)
 *        - LOGGED after >= minFallCount consecutive falls AND drop >= hysteresisDb from the HERE peak
 */
public class RobustMedianEmaHandler implements RssiHandler {

    // ---- Tunables (defaults match my earlier recommendation) ----
    private final int medianWindow;          // e.g., 5
    private final float emaAlpha;            // e.g., 0.30f
    private final int approachThreshold;     // e.g., -90 dBm
    private final int hereThreshold;         // e.g., -85 dBm
    private final int minRiseCount;          // e.g., 3 consecutive rising steps
    private final int minFallCount;          // e.g., 2 consecutive falling steps
    private final float hysteresisDb;        // e.g., 2 dB below HERE peak to consider "leaving"

    // ---- State ----
    private final Deque<Integer> medBuf = new ArrayDeque<>();
    private Float ema = null;
    private Float lastVal = null;

    private int consecRising = 0;
    private int consecFalling = 0;
    private float peakSinceApproach = Float.NEGATIVE_INFINITY; // track max after APPROACHING
    private float herePeak = Float.NEGATIVE_INFINITY;          // value at HERE transition

    private TagStatusState state = TagStatusState.TOO_FAR;

    public RobustMedianEmaHandler() {
        this(5, 0.30f, -90, -85, 3, 2, 2.0f);
    }

    public RobustMedianEmaHandler(int medianWindow,
                                  float emaAlpha,
                                  int approachThreshold,
                                  int hereThreshold,
                                  int minRiseCount,
                                  int minFallCount,
                                  float hysteresisDb) {
        this.medianWindow     = Math.max(1, medianWindow);
        this.emaAlpha         = clamp(emaAlpha, 0f, 1f);
        this.approachThreshold= approachThreshold;
        this.hereThreshold    = hereThreshold;
        this.minRiseCount     = Math.max(1, minRiseCount);
        this.minFallCount     = Math.max(1, minFallCount);
        this.hysteresisDb     = Math.max(0f, hysteresisDb);
    }

    @Override
    public void init() {
        medBuf.clear();
        ema = null;
        lastVal = null;
        consecRising = 0;
        consecFalling = 0;
        peakSinceApproach = Float.NEGATIVE_INFINITY;
        herePeak = Float.NEGATIVE_INFINITY;
        state = TagStatusState.TOO_FAR;
    }

    @Override
    public TagStatusState acceptSample(CalibrationSample s) {
        // 1) Median over window
        if (medBuf.size() == medianWindow) medBuf.removeFirst();
        medBuf.addLast(s.rssi);
        float med = median(medBuf);

        // 2) EMA over median
        if (ema == null) ema = med;
        else ema = emaAlpha * med + (1 - emaAlpha) * ema;

        // 3) Slope stats
        if (lastVal == null) {
            lastVal = ema;
            return state;
        }

        float diff = ema - lastVal;
        if (diff > 0f) {
            consecRising++;
            consecFalling = 0;
        } else if (diff < 0f) {
            consecFalling++;
            consecRising = 0;
        } // diff == 0 -> no change to counts

        switch (state) {
            case TOO_FAR:
                if (ema >= approachThreshold && consecRising >= minRiseCount) {
                    state = TagStatusState.APPROACHING;
                    peakSinceApproach = ema;
                }
                break;

            case APPROACHING:
                // Track peak while approaching
                if (ema > peakSinceApproach) peakSinceApproach = ema;

                // HERE on first confirmed local max OR threshold+flip
                boolean slopeFlippedDown = (diff < 0f); // current step fell
                boolean enoughToCallHere = (ema >= hereThreshold) || (peakSinceApproach >= hereThreshold);

                if (slopeFlippedDown && enoughToCallHere) {
                    state = TagStatusState.HERE;
                    herePeak = Math.max(peakSinceApproach, ema);
                    // reset falling counter to require confirmation for LOGGED
                    // but keep the current step counted as the first fall
                    if (consecFalling == 0) consecFalling = 1;
                }
                break;

            case HERE:
                // Keep the best peak we see while HERE (covers flat tops)
                if (ema > herePeak) herePeak = ema;

                // LOGGED when we've clearly started leaving: consecutive falls and drop >= hysteresis
                boolean bigEnoughDrop = (herePeak - ema) >= hysteresisDb;
                if (consecFalling >= minFallCount && bigEnoughDrop) {
                    state = TagStatusState.LOGGED;
                }
                break;

            case LOGGED:
            case TIMED_OUT:
            case FIRST_SAMPLE:
            default:
                // No-op after LOGGED
                break;
        }

        lastVal = ema;
        return state;
    }

    @Override
    public String getName() {
        return "Median→EMA FSM (N=" + medianWindow +
                ", α=" + trim(emaAlpha) +
                ", thrA=" + approachThreshold +
                ", thrH=" + hereThreshold +
                ", rise=" + minRiseCount +
                ", fall=" + minFallCount +
                ", hyst=" + trim(hysteresisDb) + "dB)";
    }

    // ---- helpers ----
    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float median(Deque<Integer> dq) {
        List<Integer> tmp = new ArrayList<>(dq);
        Collections.sort(tmp);
        int n = tmp.size();
        int mid = n / 2;
        if ((n & 1) == 1) return tmp.get(mid);
        return (tmp.get(mid - 1) + tmp.get(mid)) / 2.0f;
    }

    private static String trim(float f) {
        // small pretty-printer
        String s = String.valueOf(f);
        if (s.indexOf('.') > 0) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }
}

