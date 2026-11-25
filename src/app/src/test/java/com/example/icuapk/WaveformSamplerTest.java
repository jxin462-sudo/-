package com.example.icuapk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class WaveformSamplerTest {

    @Test
    public void shortWaveIsCopiedExactlyWithoutManufacturingPoints() {
        List<Integer> source = Arrays.asList(1, 4, 2, 8);
        List<Integer> result = WaveformSampler.downsample(source, 20);
        assertEquals(source, result);
        assertTrue(source != result);
    }

    @Test
    public void longSessionKeepsEarlyPeakEvenWhenLast400PointsAreFlat() {
        ArrayList<Integer> source = new ArrayList<>();
        for (int i = 0; i < 4096; i++) {
            source.add(120);
        }
        source.set(100, 250);
        source.set(101, 3);
        List<Integer> result = WaveformSampler.downsample(source, 400);
        assertTrue(result.size() <= 400);
        assertTrue(result.contains(250));
        assertTrue(result.contains(3));
        assertEquals(Integer.valueOf(120), result.get(result.size() - 1));
    }

    @Test
    public void bucketPeakAndValleyKeepTheirOriginalOrder() {
        List<Integer> result = WaveformSampler.downsample(
                Arrays.asList(0, 9, 8, 7, 9, 0, 1, 2), 4);
        assertEquals(Arrays.asList(0, 9, 9, 0), result);
    }

    @Test
    public void threeChannelsNeverBorrowEachOthersSamples() {
        List<Integer> ecg = WaveformSampler.downsample(Arrays.asList(1, 2, 3, 4, 5, 6), 4);
        List<Integer> pleth = WaveformSampler.downsample(Arrays.asList(11, 12, 13, 14, 15, 16), 4);
        List<Integer> resp = WaveformSampler.downsample(Arrays.asList(21, 22, 23, 24, 25, 26), 4);
        assertChannel(ecg, 1, 6);
        assertChannel(pleth, 11, 16);
        assertChannel(resp, 21, 26);
    }

    private static void assertChannel(List<Integer> samples, int min, int max) {
        assertTrue(samples.size() <= 4);
        for (int value : samples) {
            assertTrue(value >= min && value <= max);
        }
    }
}
