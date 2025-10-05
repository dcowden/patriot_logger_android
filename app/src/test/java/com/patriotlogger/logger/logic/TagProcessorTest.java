package com.patriotlogger.logger.logic;

import android.util.Log;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(MockitoJUnitRunner.class) 
public class TagProcessorTest {

    private TagProcessor tagProcessor;
    private MockedStatic<Log> mockedLog;

    private final int TEST_TAG_ID = 101;
    private final int TEST_TRACK_ID = 1;
    private final String TEST_FRIENDLY_NAME = "Test Racer";
    private final long ENTRY_TIME_MS = 9000L;
    private final long CURRENT_TIME_MS = 10000L;
    private final long LOSS_TIMEOUT_MS = 1000L;
    private final int ARRIVED_THRESHOLD_RSSI = -60;
    private final int INITIAL_RSSI = -75;
    private final int APPROACHING_RSSI = -70;

    @Before
    public void setUp() {
        tagProcessor = new TagProcessor();
        // Mock all static methods of android.util.Log
        mockedLog = Mockito.mockStatic(Log.class);
    }

    @After
    public void tearDown() {
        // Close the static mock after each test to avoid interference
        if (mockedLog != null) {
            mockedLog.close();
        }
    }

    // Helper to create a pre-initialized TagStatus for tests
    private TagStatus createInitialTagStatus(int tagId, String friendlyName, long entryTimeMs, float initialRssi) {
        TagStatus status = new TagStatus();
        status.tagId = tagId;
        status.trackId = TEST_TRACK_ID; 
        status.friendlyName = friendlyName;
        status.entryTimeMs = entryTimeMs;
        status.lastSeenMs = entryTimeMs; 
        status.exitTimeMs = entryTimeMs;   
        status.state = TagStatus.TagStatusState.APPROACHING;
        status.peakRssi = initialRssi;
        return status;
    }

    @Test
    public void processSample_updatesLastSeenAndExitTimes() {
        TagStatus status = createInitialTagStatus(TEST_TAG_ID, TEST_FRIENDLY_NAME, ENTRY_TIME_MS, INITIAL_RSSI);
        long newTimeMs = CURRENT_TIME_MS;

        TagStatus result = tagProcessor.processSample(status, APPROACHING_RSSI, newTimeMs, ARRIVED_THRESHOLD_RSSI);

        assertSame(status, result); 
        assertEquals(newTimeMs, result.lastSeenMs);
        assertEquals(newTimeMs, result.exitTimeMs);
    }


    @Test
    public void processSample_transitionsToHereState_whenRssiMeetsThreshold() {
        TagStatus status = createInitialTagStatus(TEST_TAG_ID, TEST_FRIENDLY_NAME, ENTRY_TIME_MS, INITIAL_RSSI);
        status.state = TagStatus.TagStatusState.APPROACHING; 

        TagStatus result = tagProcessor.processSample(status, ARRIVED_THRESHOLD_RSSI, CURRENT_TIME_MS, ARRIVED_THRESHOLD_RSSI);
        assertEquals(TagStatus.TagStatusState.HERE, result.state);
    }

    @Test
    public void processSample_remainsApproaching_whenRssiBelowThreshold() {
        TagStatus status = createInitialTagStatus(TEST_TAG_ID, TEST_FRIENDLY_NAME, ENTRY_TIME_MS, INITIAL_RSSI);
        status.state = TagStatus.TagStatusState.APPROACHING; 

        TagStatus result = tagProcessor.processSample(status, ARRIVED_THRESHOLD_RSSI - 5, CURRENT_TIME_MS, ARRIVED_THRESHOLD_RSSI);
        assertEquals(TagStatus.TagStatusState.APPROACHING, result.state);
    }
    
    @Test
    public void processSample_alreadyHere_remainsHere_whenRssiChanges() {
        TagStatus status = createInitialTagStatus(TEST_TAG_ID, TEST_FRIENDLY_NAME, ENTRY_TIME_MS, INITIAL_RSSI);
        status.state = TagStatus.TagStatusState.HERE; 

        TagStatus result = tagProcessor.processSample(status, ARRIVED_THRESHOLD_RSSI - 5, CURRENT_TIME_MS, ARRIVED_THRESHOLD_RSSI);
        assertEquals(TagStatus.TagStatusState.HERE, result.state);
        assertEquals(CURRENT_TIME_MS, result.lastSeenMs);
    }



    @Test
    public void processTagExit_tagTimesOut_marksLogged_setsPeakTimeFromSamples() {
        TagStatus activeStatus = createInitialTagStatus(TEST_TAG_ID, TEST_FRIENDLY_NAME, ENTRY_TIME_MS, INITIAL_RSSI);
        activeStatus.state = TagStatus.TagStatusState.HERE;
        activeStatus.lastSeenMs = CURRENT_TIME_MS - LOSS_TIMEOUT_MS; 
        activeStatus.exitTimeMs = activeStatus.lastSeenMs;

        List<TagData> samples = new ArrayList<>();
        samples.add(new TagData(TEST_TRACK_ID, activeStatus.lastSeenMs - 200, -70));
        long peakTimeForThisTest = activeStatus.lastSeenMs - 100;
        samples.add(new TagData(TEST_TRACK_ID, peakTimeForThisTest, -50)); 
        samples.add(new TagData(TEST_TRACK_ID, activeStatus.lastSeenMs, -65));

        TagStatus result = tagProcessor.processTagExit(activeStatus, samples, CURRENT_TIME_MS, LOSS_TIMEOUT_MS);

        assertNotNull(result);
        assertSame(activeStatus, result);
        assertEquals(TagStatus.TagStatusState.LOGGED, result.state);
        assertEquals(peakTimeForThisTest, result.peakTimeMs);
        assertEquals(activeStatus.lastSeenMs, result.exitTimeMs);
    }

    @Test
    public void processTagExit_tagTimesOut_noSamples_setsPeakTimeToLastSeen() {
        TagStatus activeStatus = createInitialTagStatus(TEST_TAG_ID, TEST_FRIENDLY_NAME, ENTRY_TIME_MS, INITIAL_RSSI);
        activeStatus.state = TagStatus.TagStatusState.APPROACHING;
        activeStatus.lastSeenMs = CURRENT_TIME_MS - LOSS_TIMEOUT_MS; 
        activeStatus.exitTimeMs = activeStatus.lastSeenMs;

        TagStatus result = tagProcessor.processTagExit(activeStatus, Collections.emptyList(), CURRENT_TIME_MS, LOSS_TIMEOUT_MS);

        assertNotNull(result);
        assertEquals(TagStatus.TagStatusState.LOGGED, result.state);
        assertEquals(activeStatus.lastSeenMs, result.peakTimeMs);
        assertEquals(activeStatus.lastSeenMs, result.exitTimeMs);
    }

    @Test
    public void processTagExit_tagTimesOut_nullSamples_setsPeakTimeToLastSeen() {
        TagStatus activeStatus = createInitialTagStatus(TEST_TAG_ID, TEST_FRIENDLY_NAME, ENTRY_TIME_MS, INITIAL_RSSI);
        activeStatus.state = TagStatus.TagStatusState.APPROACHING;
        activeStatus.lastSeenMs = CURRENT_TIME_MS - LOSS_TIMEOUT_MS; 
        activeStatus.exitTimeMs = activeStatus.lastSeenMs;

        TagStatus result = tagProcessor.processTagExit(activeStatus, null, CURRENT_TIME_MS, LOSS_TIMEOUT_MS);

        assertNotNull(result);
        assertEquals(TagStatus.TagStatusState.LOGGED, result.state);
        assertEquals(activeStatus.lastSeenMs, result.peakTimeMs);
    }

}
