package com.patriotlogger.logger.test;

import com.patriotlogger.logger.data.CalibrationSample;

public class EmaRssiHandler extends AbstractRssiHandler {

    private final float alpha;
    private Float ema = null;

    public EmaRssiHandler(float alpha) {
        this.alpha = Math.max(0.0f, Math.min(alpha, 1.0f));
    }

    @Override
    protected void onInit() {
        ema = null;
    }

    @Override
    protected float smooth(CalibrationSample s) {
        if (ema == null) ema = (float) s.rssi;
        else ema = alpha * s.rssi + (1 - alpha) * ema;
        return ema;
    }

    @Override
    public String getName() { return "EMA(alpha=" + alpha + ")"; }
}

