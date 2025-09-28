package com.patriotlogger.logger;

import static org.junit.Assert.assertTrue;

import com.patriotlogger.logger.util.Kalman1D;

import org.junit.Test;

public class Kalman1DTest {
    @Test public void smoothsNoise() {
        Kalman1D k = new Kalman1D(123, 0.1f, 0.05f, 0.02f);
        float a = k.update(-60);
        float b = k.update(-61);
        float c = k.update(-59);
        assertTrue(c > b); // crude check
    }
}
