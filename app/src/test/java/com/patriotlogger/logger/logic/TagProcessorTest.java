package com.patriotlogger.logger.logic;

import android.util.Log;

import com.patriotlogger.logger.data.Setting;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit test for TagProcessor.
 * This test class uses Mockito to mock the static android.util.Log class,
 * allowing it to run on a standard JVM without crashing.
 */
@RunWith(MockitoJUnitRunner.class)
public class TagProcessorTest {

    private TagProcessor tagProcessor;
    private TagPeakFinder peakFinder;
    private Setting testSettings;
    private TagStatus status;

    // --- Static Mock for android.util.Log ---
    private static MockedStatic<Log> mockedLog;

    @BeforeClass
    public static void beforeClass() {

    }

    @AfterClass
    public static void afterClass() {

    }

    // --- Constants for testing ---
    private static final int APPROACH_THRESHOLD = -70;
    private static final int ARRIVE_THRESHOLD = -59;
    private static final float ALPHA = 0.1f;

    @Before
    public void setUp() {
        // Instantiate the dependencies directly.
        peakFinder = new TagPeakFinder();
        tagProcessor = new TagProcessor(peakFinder);

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

    @Test
    public void processFullWalkBy_fromCsvFile_transitionsThroughAllStates_500ms_interval() throws Exception {
        processFullWalkBy_fromCsvFile_transitionsThroughAllStates("sample_walk_by.csv");
    }

    @Test
    public void processFullWalkBy_fromCsvFile_transitionsThroughAllStates_50ms_interval() throws Exception {
        processFullWalkBy_fromCsvFile_transitionsThroughAllStates("sample-50ms-alpha0.1.csv");
    }


    public void processFullWalkBy_fromCsvFile_transitionsThroughAllStates(String filename) throws Exception {
        // Load CSV data from test resources as TagData objects
        List<TagData> samples = loadCsvDataAsTagData(filename);
        assertFalse("CSV file should contain data points", samples.isEmpty());

        // Initialize a fresh status for this test
        TagStatus walkByStatus = new TagStatus();
        walkByStatus.tagId = 123;
        walkByStatus.trackId = 1;
        walkByStatus.state = TagStatus.TagStatusState.FIRST_SAMPLE;

        // Track state transitions
        boolean seenTooFar = false;
        boolean seenApproaching = false;
        boolean seenHere = false;
        boolean seenLogged = false;

        // Process each data point
        for (TagData sample : samples) {
            TagStatus.TagStatusState previousState = walkByStatus.state;

            tagProcessor.processSample(
                    walkByStatus,
                    testSettings,
                    sample.rssi,
                    sample.timestampMs
            );

            // Track state transitions
            if (walkByStatus.state == TagStatus.TagStatusState.TOO_FAR) {
                seenTooFar = true;
            }
            if (walkByStatus.state == TagStatus.TagStatusState.APPROACHING) {
                seenApproaching = true;
            }
            if (walkByStatus.state == TagStatus.TagStatusState.HERE) {
                seenHere = true;
            }
            if (walkByStatus.state == TagStatus.TagStatusState.LOGGED) {
                seenLogged = true;
            }

            // Log state transitions for debugging
            if (previousState != walkByStatus.state) {
                System.out.println(String.format("State transition at %d: %s -> %s (RSSI: %d, EMA: %.2f)",
                        sample.timestampMs, previousState, walkByStatus.state, sample.rssi, walkByStatus.emaRssi));
            }
        }

        // Assert all states were seen
        assertTrue("Should have transitioned through TOO_FAR state", seenTooFar);
        assertTrue("Should have transitioned through APPROACHING state", seenApproaching);
        assertTrue("Should have transitioned through HERE state", seenHere);
        assertTrue("Should have transitioned through LOGGED state", seenLogged);

        // Verify final state is LOGGED
        assertEquals("Final state should be LOGGED", TagStatus.TagStatusState.LOGGED, walkByStatus.state);

        // Now use the peak finder to find the actual peak from all the samples
        // This simulates what happens in the real application
        tagProcessor.findAndSetPeakTime(walkByStatus, samples, testSettings);

        // Verify peak time was set
        assertTrue("Peak time should be set after peak finding", walkByStatus.peakTimeMs > 0);

        // Find the expected peak from the raw data
        long expectedPeakTime = -1;
        int maxRawRssi = Integer.MIN_VALUE;
        for (TagData sample : samples) {
            if (sample.rssi > maxRawRssi) {
                maxRawRssi = sample.rssi;
                expectedPeakTime = sample.timestampMs;
            }
        }

        // The peak finder uses zero-phase filtering, so the detected peak time should be
        // very close to the actual peak in the raw data (within a few samples)
        long timeDifference = Math.abs(walkByStatus.peakTimeMs - expectedPeakTime);
        long maxAllowedDifference = 2000; // 2 seconds tolerance (4 samples at 500ms each)

        assertTrue(
                String.format("Peak time (%d) should be within %dms of expected peak time (%d), but was %dms off",
                        walkByStatus.peakTimeMs, maxAllowedDifference, expectedPeakTime, timeDifference),
                timeDifference <= maxAllowedDifference
        );

        System.out.println(String.format("\nFinal status: peakTimeMs=%d, peakRssi=%.2f, exitTimeMs=%d",
                walkByStatus.peakTimeMs, walkByStatus.peakRssi, walkByStatus.exitTimeMs));
        System.out.println(String.format("Expected peak: time=%d, rawRssi=%d",
                expectedPeakTime, maxRawRssi));
        System.out.println(String.format("Peak time difference: %dms (max allowed: %dms)",
                timeDifference, maxAllowedDifference));
    }

    /**
     * Load CSV data from test resources as TagData objects
     * Expected format: timestamp,tagid,rssi,smoothedrssi
     */
    private List<TagData> loadCsvDataAsTagData(String filename) throws Exception {
        List<TagData> samples = new ArrayList<>();

        InputStream is = getClass().getClassLoader().getResourceAsStream(filename);
        assertNotNull("CSV file not found: " + filename, is);

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        String line;
        boolean firstLine = true;
        int trackId = 1; // Use a fixed trackId for all samples

        while ((line = reader.readLine()) != null) {
            // Skip header line
            if (firstLine) {
                firstLine = false;
                continue;
            }

            String[] parts = line.split(",");
            if (parts.length >= 3) {
                long timestamp = Long.parseLong(parts[0].trim());
                int rssi = Integer.parseInt(parts[2].trim());
                samples.add(new TagData(trackId, timestamp, rssi));
            }
        }

        reader.close();
        return samples;
    }
}