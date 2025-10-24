package com.patriotlogger.logger.test;

import com.patriotlogger.logger.data.CalibrationSample;
import com.patriotlogger.logger.data.TagStatus.TagStatusState;

/**
 * A small helper to run the state transitions that each handler uses after it
 * computes its own "smoothed" RSSI.
 *
 * State logic is intentionally simple and conservative:
 * - TOO_FAR until we see sustained increase above APPROACH_THRESHOLD.
 * - APPROACHING while slope >= 0 and we're above APPROACH_THRESHOLD.
 * - HERE on the first local maximum after APPROACHING that is >= HERE_THRESHOLD.
 * - LOGGED right after HERE when signal clearly starts falling.
 *
 * You can tweak thresholds or counts here without touching algorithm classes.
 */
abstract class AbstractRssiHandler implements RssiHandler {

    protected TagStatusState state = TagStatusState.TOO_FAR;

    // Tweakable knobs; kept conservative
    protected int APPROACH_THRESHOLD = -90;  // start "approaching" when stronger than this
    protected int HERE_THRESHOLD     = -80;  // near best proximity
    protected int FALLBACK_MIN_RISE_SAMPLES = 3; // how many rising steps before APPROACHING
    protected int FALL_CONFIRM_DROP_SAMPLES = 2; // how many falling steps to confirm drop

    private int consecRising = 0;
    private int consecFalling = 0;

    private float lastSmooth = Float.NEGATIVE_INFINITY;
    private boolean seenTopAfterApproach = false;

    @Override
    public void init() {
        state = TagStatusState.TOO_FAR;
        consecRising = 0;
        consecFalling = 0;
        lastSmooth = Float.NEGATIVE_INFINITY;
        seenTopAfterApproach = false;
        onInit();
    }

    protected void onInit() {}

    /** Implementations must return a smoothed RSSI for this sample. */
    protected abstract float smooth(CalibrationSample s);

    @Override
    public TagStatusState acceptSample(CalibrationSample s) {
        float val = smooth(s);

        if (lastSmooth == Float.NEGATIVE_INFINITY) {
            lastSmooth = val;
            return state;
        }

        if (val > lastSmooth) {
            consecRising++;
            consecFalling = 0;
        } else if (val < lastSmooth) {
            consecFalling++;
            if (state == TagStatusState.APPROACHING) {
                // potential local max just passed
                seenTopAfterApproach = true;
            }
        }

        switch (state) {
            case TOO_FAR:
                if (val >= APPROACH_THRESHOLD && consecRising >= FALLBACK_MIN_RISE_SAMPLES) {
                    state = TagStatusState.APPROACHING;
                }
                break;

            case APPROACHING:
                if (seenTopAfterApproach && val >= HERE_THRESHOLD) {
                    state = TagStatusState.HERE;
                }
                break;

            case HERE:
                // if we start falling for a bit, consider it logged
                if (consecFalling >= FALL_CONFIRM_DROP_SAMPLES) {
                    state = TagStatusState.LOGGED;
                }
                break;

            case LOGGED:
            case TIMED_OUT:
            case FIRST_SAMPLE:
            default:
                // no-op
                break;
        }

        lastSmooth = val;
        return state;
    }
}
