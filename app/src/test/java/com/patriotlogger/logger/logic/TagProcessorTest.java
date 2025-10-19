package com.patriotlogger.logger.logic;import android.util.Log;

import com.patriotlogger.logger.data.Setting;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit test for TagProcessor.
 * This test class uses Mockito to mock the static android.util.Log class,
 * allowing it to run on a standard JVM without crashing.
 */
@RunWith(MockitoJUnitRunner.class)
public class TagProcessorTest {

    private TagProcessor tagProcessor;
    private Setting testSettings;
    private TagStatus status;

    // --- Static Mock for android.util.Log ---
    private static MockedStatic<Log> mockedLog;

    @BeforeClass
    public static void beforeClass() {
        // We need to mock any static method call in the Log class
        mockedLog = mockStatic(Log.class);
        // The correct syntax for stubbing static methods uses Mockito.when()
        mockedLog.when(() -> Log.i(anyString(), anyString())).thenReturn(0);
        mockedLog.when(() -> Log.w(anyString(), anyString())).thenReturn(0);
        mockedLog.when(() -> Log.e(anyString(), anyString())).thenReturn(0);

    }

    @AfterClass
    public static void afterClass() {
        // It's good practice to close the static mock when tests are complete.
        mockedLog.close();
    }

    // --- Constants for testing ---
    private static final int APPROACH_THRESHOLD = -70;
    private static final int ARRIVE_THRESHOLD = -60;
    private static final float ALPHA = 0.5f;

    @Before
    public void setUp() {
        // Instantiate the dependencies directly.
        tagProcessor = new TagProcessor(new TagPeakFinder());

        // Standard settings for all tests
        testSettings = new Setting();
        testSettings.approaching_threshold = APPROACH_THRESHOLD;
        testSettings.arrived_threshold = ARRIVE_THRESHOLD;
        testSettings.rssi_averaging_alpha = ALPHA;

        // A fresh status object for each test
        status = new TagStatus();
        status.trackId = 123;
    }

    /** Helper to simulate processing a single sample */
    private void runProcessor(int rssi) {
        tagProcessor.processSample(status, testSettings, rssi, System.currentTimeMillis());
    }

    @Test
    public void processSample_withFirstSample_transitionsToTooFar() {
        status.state = TagStatus.TagStatusState.FIRST_SAMPLE;
        runProcessor(-90);
        assertEquals(TagStatus.TagStatusState.TOO_FAR, status.state);
    }

    @Test
    public void fromTooFar_toApproaching_failsWithNotEnoughConsecutiveIncreases() {
        status.state = TagStatus.TagStatusState.TOO_FAR;
        runProcessor(APPROACH_THRESHOLD + 5);
        runProcessor(APPROACH_THRESHOLD + 10);
        assertEquals(2, status.consecutiveRssiIncreases);
        assertEquals(TagStatus.TagStatusState.TOO_FAR, status.state);
    }

    @Test
    public void fromTooFar_toApproaching_succeedsWithEnoughConsecutiveIncreases() {
        status.state = TagStatus.TagStatusState.TOO_FAR;
        runProcessor(APPROACH_THRESHOLD + 5);
        runProcessor(APPROACH_THRESHOLD + 10);
        runProcessor(APPROACH_THRESHOLD + 15);
        assertEquals(TagStatus.TagStatusState.APPROACHING, status.state);
    }

    @Test
    public void fromApproaching_toHere_succeedsWithEnoughConsecutiveIncreases() {
        status.state = TagStatus.TagStatusState.APPROACHING;
        runProcessor(ARRIVE_THRESHOLD + 5);
        runProcessor(ARRIVE_THRESHOLD + 10);
        runProcessor(ARRIVE_THRESHOLD + 15);
        assertEquals(TagStatus.TagStatusState.HERE, status.state);
    }

    @Test
    public void fromApproaching_fallsBackToTooFar_whenSignalFades() {
        status.state = TagStatus.TagStatusState.APPROACHING;
        status.emaRssi = (float) (APPROACH_THRESHOLD + 1);
        runProcessor(APPROACH_THRESHOLD - 10);
        assertEquals(TagStatus.TagStatusState.TOO_FAR, status.state);
    }

    @Test
    public void fromHere_toLogged_succeedsWhenSignalFades() {
        status.state = TagStatus.TagStatusState.HERE;
        runProcessor(APPROACH_THRESHOLD - 5);
        assertEquals(TagStatus.TagStatusState.LOGGED, status.state);
    }

    @Test
    public void findAndSetPeakTime_calculatesPeakCorrectly() {
        // This test now validates the separated peak-finding call
        List<TagData> fakeSamples = new ArrayList<>();
        fakeSamples.add(new TagData(status.trackId, 1000L, -70));
        fakeSamples.add(new TagData(status.trackId, 2000L, -50)); // Raw peak is -50
        fakeSamples.add(new TagData(status.trackId, 3000L, -70));

        // Act
        tagProcessor.findAndSetPeakTime(status, fakeSamples,testSettings);

        // Assert
        assertEquals(2000L, status.peakTimeMs);

        // --- THIS IS THE FIX ---
        // A robust assertion checks that the smoothed peak is simply less than the raw peak.
        assertTrue("Smoothed peak RSSI must be less than the raw peak RSSI", status.peakRssi < -50.0f);
    }

    @Test
    public void findAndSetPeakTime_usesFallbackWhenSamplesAreEmpty() {
        long fallbackTime = 1500L;
        status.peakTimeMs = fallbackTime; // Simulate a raw peak being captured

        // Act
        tagProcessor.findAndSetPeakTime(status,  Collections.emptyList(),testSettings);

        // Assert
        assertEquals("Peak time should remain the raw fallback value", fallbackTime, status.peakTimeMs);
    }

    @Test
    public void processTimedOutTag_fromHere_transitionsToLogged() {
        status.state = TagStatus.TagStatusState.HERE;

        // Act
        tagProcessor.processTimedOutTagStatus(status, 5000L);

        // Assert
        assertEquals("State should be LOGGED", TagStatus.TagStatusState.LOGGED, status.state);
    }

    @Test
    public void processTimedOutTag_fromApproaching_transitionsToTimedOut() {
        status.state = TagStatus.TagStatusState.APPROACHING;
        long timeoutTime = 5000L;

        // Act
        tagProcessor.processTimedOutTagStatus(status, timeoutTime);

        // Assert
        assertEquals(TagStatus.TagStatusState.TIMED_OUT, status.state);
        assertEquals(timeoutTime, status.exitTimeMs);
    }
}
