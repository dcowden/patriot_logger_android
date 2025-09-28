package com.patriotlogger.logger.data;

import org.junit.Test;
import static org.junit.Assert.*;

public class KalmanStateTest {

    @Test
    public void constructor_setsFieldsCorrectly() {
        int tagId = 123;
        float q = 0.1f;
        float r = 0.2f;
        float p = 0.3f;
        float x = 0.4f;
        boolean initialized = true;

        KalmanState kalmanState = new KalmanState(tagId, q, r, p, x, initialized);

        assertEquals(tagId, kalmanState.tagId);
        assertEquals(q, kalmanState.q, 0.001f);
        assertEquals(r, kalmanState.r, 0.001f);
        assertEquals(p, kalmanState.p, 0.001f);
        assertEquals(x, kalmanState.x, 0.001f);
        assertEquals(initialized, kalmanState.initialized);
    }
}
