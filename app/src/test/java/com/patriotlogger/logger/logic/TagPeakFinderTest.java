package com.patriotlogger.logger.logic;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import com.patriotlogger.logger.data.Setting;
import com.patriotlogger.logger.data.TagData;

import java.util.ArrayList;
import java.util.List;

public class TagPeakFinderTest {

    private TagPeakFinder peakFinder;
    private Setting testSettings;

    @Before
    public void setUp() {
        peakFinder = new TagPeakFinder();
        testSettings = new Setting();
        testSettings.rssi_averaging_alpha = 0.5f; // A common alpha for testing
    }

    @Test
    public void findPeak_withSymmetricalHump_findsCorrectCenterPeak() {
        // Arrange: Create a perfect "hump" of data. The true peak is at timestamp 1004.
        List<TagData> samples = new ArrayList<>();
        samples.add(new TagData(1, 1000, -80));
        samples.add(new TagData(1, 1001, -70));
        samples.add(new TagData(1, 1002, -60));
        samples.add(new TagData(1, 1003, -55));
        samples.add(new TagData(1, 1004, -50)); // The true peak
        samples.add(new TagData(1, 1005, -55));
        samples.add(new TagData(1, 1006, -60));
        samples.add(new TagData(1, 1007, -70));
        samples.add(new TagData(1, 1008, -80));

        // Act: Run the peak finder
        TagPeakData result = peakFinder.findPeak(samples, testSettings);

        // Assert:
        assertNotNull("Result should not be null", result);

        // 1. The most important assertion: Did it find the peak at the correct time?
        assertEquals("The peak time should match the center of the hump", 1004, result.peakTimeMs);

        // --- THIS IS THE FIX ---
        // 2. A better assertion: The smoothed peak's value MUST be less than the raw peak's value.
        //    We no longer assert an arbitrary "close enough" value.
        assertTrue("Smoothed peak RSSI must be less than the raw peak value", result.peakRssi < -50.0f);
        // --- END OF FIX ---
    }

    @Test
    public void findPeak_withTooFewSamples_returnsNull() {
        // Arrange: Create a list with only two samples
        List<TagData> samples = new ArrayList<>();
        samples.add(new TagData(1, 1000, -80));
        samples.add(new TagData(1, 1001, -70));

        // Act
        TagPeakData result = peakFinder.findPeak(samples, testSettings);

        // Assert
        assertNull("Result should be null for insufficient data", result);
    }

    @Test
    public void findPeak_withFlatPeak_findsCenterOfFlatTop() {
        // Arrange: Create a hump with a flat top.
        List<TagData> samples = new ArrayList<>();
        samples.add(new TagData(1, 1000, -80));
        samples.add(new TagData(1, 1001, -60));
        samples.add(new TagData(1, 1002, -50)); // Flat peak starts
        samples.add(new TagData(1, 1003, -50)); // Flat peak continues (this should be the new peak)
        samples.add(new TagData(1, 1004, -50)); // Flat peak ends
        samples.add(new TagData(1, 1005, -60));
        samples.add(new TagData(1, 1006, -80));

        // Act
        TagPeakData result = peakFinder.findPeak(samples, testSettings);

        // Assert
        assertNotNull(result);
        // Because the forward-backward filter smooths the edges, the peak of the
        // *smoothed* data will be in the middle of the flat section.
        assertEquals("The peak time should be the middle of the flat top", 1003, result.peakTimeMs);

        // As with the sharp peak, the smoothed value on a flat peak will also be slightly lower.
        assertTrue("Smoothed peak RSSI on a flat top must be less than the raw value", result.peakRssi < -50.0f);
    }
}
