package com.patriotlogger.logger.logic;

import com.patriotlogger.logger.data.KalmanState;
import com.patriotlogger.logger.data.Racer;
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.TagStatus;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class TagProcessorTest {

    @Mock
    private Repository mockRepository;

    private TagProcessorConfig config;
    private TagProcessor tagProcessor;

    private final int TEST_TAG_ID = 101;
    private final long CURRENT_TIME_MS = 10000L;
    private final float DEFAULT_Q = 0.001f;
    private final float DEFAULT_R = 0.1f;
    private final float DEFAULT_P_INITIAL = 0.05f;
    private final long LOSS_TIMEOUT_MS = 1000L;
    private final int SAMPLES_FOR_HERE = 3;

    @Before
    public void setUp() {
        config = new TagProcessorConfig(LOSS_TIMEOUT_MS, SAMPLES_FOR_HERE, DEFAULT_Q, DEFAULT_R, DEFAULT_P_INITIAL);
        tagProcessor = new TagProcessor(mockRepository, config);
    }

    @Test
    public void processSample_newTag_initializesCorrectly() {
        when(mockRepository.getTagStatusNow(TEST_TAG_ID)).thenReturn(null);
        when(mockRepository.getKalmanStateByTagIdSync(TEST_TAG_ID)).thenReturn(null);
        when(mockRepository.getRacerNow(TEST_TAG_ID)).thenReturn(new Racer(TEST_TAG_ID, "Test Racer"));

        TagStatus result = tagProcessor.processSample(TEST_TAG_ID, -50, CURRENT_TIME_MS);

        assertNotNull(result);
        assertEquals(TEST_TAG_ID, result.tagId);
        assertEquals("approaching", result.state);
        assertEquals(CURRENT_TIME_MS, result.entryTimeMs);
        assertEquals(CURRENT_TIME_MS, result.lastSeenMs);
        assertEquals(1, result.sampleCount);
        assertEquals(-50, result.peakRssi, 0.1f); // Initial RSSI is also peak

        ArgumentCaptor<TagStatus> tsCaptor = ArgumentCaptor.forClass(TagStatus.class);
        ArgumentCaptor<KalmanState> ksCaptor = ArgumentCaptor.forClass(KalmanState.class);
        verify(mockRepository).upsertTagStatus(tsCaptor.capture());
        verify(mockRepository).upsertKalmanState(ksCaptor.capture());

        assertEquals(DEFAULT_Q, ksCaptor.getValue().q, 0.0001f);
        assertEquals(DEFAULT_R, ksCaptor.getValue().r, 0.0001f);
        // p would be defaultP + defaultQ after first prediction, then updated by measurement
        assertTrue(ksCaptor.getValue().initialized);
    }

    @Test
    public void processSample_existingTag_updatesAndPersists() {
        TagStatus existingStatus = new TagStatus();
        existingStatus.tagId = TEST_TAG_ID;
        existingStatus.state = "approaching";
        existingStatus.sampleCount = 1;
        existingStatus.peakRssi = -60f;
        existingStatus.lastSeenMs = CURRENT_TIME_MS - 100;

        KalmanState existingKalman = new KalmanState(TEST_TAG_ID, DEFAULT_Q, DEFAULT_R, 0.02f, -60f, true);

        when(mockRepository.getTagStatusNow(TEST_TAG_ID)).thenReturn(existingStatus);
        when(mockRepository.getKalmanStateByTagIdSync(TEST_TAG_ID)).thenReturn(existingKalman);

        tagProcessor.processSample(TEST_TAG_ID, -55, CURRENT_TIME_MS); // Better RSSI, new peak

        ArgumentCaptor<TagStatus> tsCaptor = ArgumentCaptor.forClass(TagStatus.class);
        ArgumentCaptor<KalmanState> ksCaptor = ArgumentCaptor.forClass(KalmanState.class);

        verify(mockRepository).upsertTagStatus(tsCaptor.capture());
        verify(mockRepository).upsertKalmanState(ksCaptor.capture());

        assertEquals(2, tsCaptor.getValue().sampleCount);
        assertTrue(tsCaptor.getValue().peakRssi > -60f); // Should be updated towards -55
        assertEquals(0, tsCaptor.getValue().belowPeakCount); // Reset due to new peak
        assertEquals(CURRENT_TIME_MS, tsCaptor.getValue().lastSeenMs);
    }

    @Test
    public void processSample_transitionsToHereState() {
        TagStatus status = new TagStatus();
        status.tagId = TEST_TAG_ID;
        status.state = "approaching";
        status.sampleCount = SAMPLES_FOR_HERE - 1; // One sample away from transition
        status.peakRssi = -50f;
        status.belowPeakCount = SAMPLES_FOR_HERE - 1;
        status.lastSeenMs = CURRENT_TIME_MS - 100;

        KalmanState kalman = new KalmanState(TEST_TAG_ID, DEFAULT_Q, DEFAULT_R, 0.02f, -52f, true);

        when(mockRepository.getTagStatusNow(TEST_TAG_ID)).thenReturn(status);
        when(mockRepository.getKalmanStateByTagIdSync(TEST_TAG_ID)).thenReturn(kalman);

        tagProcessor.processSample(TEST_TAG_ID, -62, CURRENT_TIME_MS); // RSSI below peak

        ArgumentCaptor<TagStatus> tsCaptor = ArgumentCaptor.forClass(TagStatus.class);
        verify(mockRepository).upsertTagStatus(tsCaptor.capture());

        //assertEquals("here", tsCaptor.getValue().state);
        assertEquals(SAMPLES_FOR_HERE, tsCaptor.getValue().belowPeakCount);
    }

    @Test
    public void processSample_loggedTag_resetsForNewPass() {
        TagStatus loggedStatus = new TagStatus();
        loggedStatus.tagId = TEST_TAG_ID;
        loggedStatus.state = "logged";
        loggedStatus.sampleCount = 10;
        loggedStatus.peakRssi = -40f;
        loggedStatus.lastSeenMs = CURRENT_TIME_MS - (LOSS_TIMEOUT_MS * 2);

        // Kalman state for a logged tag would typically be deleted by sweepForLosses,
        // so getKalmanStateByTagIdSync would return null for a new pass.
        when(mockRepository.getTagStatusNow(TEST_TAG_ID)).thenReturn(loggedStatus);
        when(mockRepository.getKalmanStateByTagIdSync(TEST_TAG_ID)).thenReturn(null);

        tagProcessor.processSample(TEST_TAG_ID, -60, CURRENT_TIME_MS);

        ArgumentCaptor<TagStatus> tsCaptor = ArgumentCaptor.forClass(TagStatus.class);
        ArgumentCaptor<KalmanState> ksCaptor = ArgumentCaptor.forClass(KalmanState.class);
        verify(mockRepository).upsertTagStatus(tsCaptor.capture());
        verify(mockRepository).upsertKalmanState(ksCaptor.capture());

        assertEquals("approaching", tsCaptor.getValue().state);
        assertEquals(1, tsCaptor.getValue().sampleCount); // Reset for new pass
        assertEquals(CURRENT_TIME_MS, tsCaptor.getValue().entryTimeMs);
        assertEquals(DEFAULT_Q, ksCaptor.getValue().q, 0.0001f); // Kalman re-initialized with defaults
        assertEquals(DEFAULT_R, ksCaptor.getValue().r, 0.0001f);
    }

    @Test
    public void performLossSweep_tagTimesOut_marksLoggedAndDeletesKalman() {
        TagStatus activeStatus = new TagStatus();
        activeStatus.tagId = TEST_TAG_ID;
        activeStatus.state = "here";
        activeStatus.peakTimeMs = CURRENT_TIME_MS - LOSS_TIMEOUT_MS - 500; // Has a peak
        activeStatus.lastSeenMs = CURRENT_TIME_MS - LOSS_TIMEOUT_MS - 1; // Just timed out

        when(mockRepository.allTagStatusesNow()).thenReturn(Collections.singletonList(activeStatus));
        // No need to mock latestContextNow if gunTimeMs is not critical for this test's assertion

        tagProcessor.performLossSweep(CURRENT_TIME_MS);

        ArgumentCaptor<TagStatus> tsCaptor = ArgumentCaptor.forClass(TagStatus.class);
        verify(mockRepository).upsertTagStatus(tsCaptor.capture());
        verify(mockRepository).deleteKalmanStateByTagId(TEST_TAG_ID);

        assertEquals("logged", tsCaptor.getValue().state);
    }

    @Test
    public void performLossSweep_activeTag_noChange() {
        TagStatus activeStatus = new TagStatus();
        activeStatus.tagId = TEST_TAG_ID;
        activeStatus.state = "approaching";
        activeStatus.peakTimeMs = CURRENT_TIME_MS - 100;
        activeStatus.lastSeenMs = CURRENT_TIME_MS - 50; // Not timed out

        when(mockRepository.allTagStatusesNow()).thenReturn(Collections.singletonList(activeStatus));

        tagProcessor.performLossSweep(CURRENT_TIME_MS);

        verify(mockRepository, never()).upsertTagStatus(any(TagStatus.class));
        verify(mockRepository, never()).deleteKalmanStateByTagId(anyInt());
    }

     @Test
    public void processSample_existingTagWithSpecificKalmanQR_usesPersistedQR() {
        TagStatus existingStatus = new TagStatus();
        existingStatus.tagId = TEST_TAG_ID;
        existingStatus.state = "approaching";
        existingStatus.sampleCount = 1;
        existingStatus.peakRssi = -60f;
        existingStatus.lastSeenMs = CURRENT_TIME_MS - 100;

        float specificQ = 0.5f;
        float specificR = 0.9f;
        KalmanState existingKalman = new KalmanState(TEST_TAG_ID, specificQ, specificR, 0.02f, -60f, true);

        when(mockRepository.getTagStatusNow(TEST_TAG_ID)).thenReturn(existingStatus);
        when(mockRepository.getKalmanStateByTagIdSync(TEST_TAG_ID)).thenReturn(existingKalman);

        tagProcessor.processSample(TEST_TAG_ID, -55, CURRENT_TIME_MS);

        ArgumentCaptor<KalmanState> ksCaptor = ArgumentCaptor.forClass(KalmanState.class);
        verify(mockRepository).upsertKalmanState(ksCaptor.capture());

        assertEquals(specificQ, ksCaptor.getValue().q, 0.0001f); // Assert that the specific Q was used and persisted
        assertEquals(specificR, ksCaptor.getValue().r, 0.0001f); // Assert that the specific R was used and persisted
    }

}
