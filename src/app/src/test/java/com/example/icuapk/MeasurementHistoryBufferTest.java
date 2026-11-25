package com.example.icuapk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class MeasurementHistoryBufferTest {

    @Test
    public void recordsSevenRealParametersWithoutRewritingValues() {
        MeasurementHistoryBuffer buffer = new MeasurementHistoryBuffer();
        double[] values = {78, 121, 82, 76, 98, 37.6, 18};
        for (int param = 0; param < values.length; param++) {
            buffer.record(1700000000000L + param * 1000L, param, values[param]);
        }
        List<Am4100Manager.VitalSample> result = buffer.copy();
        assertEquals(7, result.size());
        for (int param = 0; param < values.length; param++) {
            assertEquals(param, result.get(param).param);
            assertEquals(values[param], result.get(param).value, 0.0);
            assertEquals(1700000000000L + param * 1000L, result.get(param).time);
        }
    }

    @Test
    public void suppressesRepeatedFramesButKeepsChangesAndMinuteCheckpoints() {
        MeasurementHistoryBuffer buffer = new MeasurementHistoryBuffer();
        long base = 1700000000000L;
        for (int i = 0; i < 100; i++) {
            buffer.record(base + i * 100L, Am4100Manager.PARAM_TEMP, 37.5);
        }
        assertEquals(1, buffer.copy().size());
        buffer.record(base + 1000L, Am4100Manager.PARAM_HEART_RATE, 80);
        buffer.record(base + 1500L, Am4100Manager.PARAM_HEART_RATE, 80);
        buffer.record(base + 2000L, Am4100Manager.PARAM_HEART_RATE, 81);
        buffer.record(base + 61000L, Am4100Manager.PARAM_TEMP, 37.5);
        List<Am4100Manager.VitalSample> result = buffer.copy();
        assertEquals(4, result.size());
        assertEquals(81, result.get(2).value, 0.0);
        assertEquals(base + 61000L, result.get(3).time);
    }

    @Test
    public void highFrequencyTemperatureCannotStarveOtherParameters() {
        MeasurementHistoryBuffer buffer = new MeasurementHistoryBuffer();
        long base = 1700000000000L;
        for (int i = 0; i < MeasurementHistoryBuffer.CAPACITY; i++) {
            buffer.record(base + i * 1000L, Am4100Manager.PARAM_TEMP, 35.0 + (i % 50) / 10.0);
        }
        long next = base + MeasurementHistoryBuffer.CAPACITY * 1000L;
        for (int param = 0; param <= Am4100Manager.PARAM_RESP; param++) {
            if (param != Am4100Manager.PARAM_TEMP) {
                buffer.record(next + param * 1000L, param, 60 + param);
            }
        }
        for (int i = 0; i < 1000; i++) {
            buffer.record(next + 10000L + i * 1000L, Am4100Manager.PARAM_TEMP, 36.0 + (i % 30) / 10.0);
        }
        List<Am4100Manager.VitalSample> result = buffer.copy();
        assertEquals(MeasurementHistoryBuffer.CAPACITY, result.size());
        for (int param = 0; param <= Am4100Manager.PARAM_RESP; param++) {
            assertTrue("missing param " + param, containsParam(result, param));
        }
    }

    @Test
    public void rejectsDamagedRecordsAndRestoreNeverInventsSamples() {
        MeasurementHistoryBuffer buffer = new MeasurementHistoryBuffer();
        buffer.record(0L, 0, 80);
        buffer.record(1000L, 7, 80);
        buffer.record(1000L, 0, Double.NaN);
        buffer.record(1000L, 0, Double.POSITIVE_INFINITY);
        buffer.record(1000L, Am4100Manager.PARAM_HEART_RATE, 0);
        buffer.record(2000L, Am4100Manager.PARAM_HEART_RATE, 613);
        buffer.record(3000L, Am4100Manager.PARAM_TEMP, 0);
        buffer.record(4000L, Am4100Manager.PARAM_SPO2, 101);
        assertTrue(buffer.copy().isEmpty());
        buffer.replace(null);
        assertTrue(buffer.copy().isEmpty());
    }

    @Test
    public void restoreDropsOldZeroAndOutlierPollutionButKeepsValidHistory() {
        MeasurementHistoryBuffer buffer = new MeasurementHistoryBuffer();
        List<Am4100Manager.VitalSample> restored = new java.util.ArrayList<>();
        restored.add(new Am4100Manager.VitalSample(1000L, Am4100Manager.PARAM_TEMP, 0));
        restored.add(new Am4100Manager.VitalSample(2000L, Am4100Manager.PARAM_HEART_RATE, 613));
        restored.add(new Am4100Manager.VitalSample(3000L, Am4100Manager.PARAM_HEART_RATE, 80));
        restored.add(new Am4100Manager.VitalSample(4000L, Am4100Manager.PARAM_TEMP, 37.6));
        buffer.replace(restored);
        List<Am4100Manager.VitalSample> result = buffer.copy();
        assertEquals(2, result.size());
        assertEquals(80, result.get(0).value, 0.0);
        assertEquals(37.6, result.get(1).value, 0.0);
    }

    private static boolean containsParam(List<Am4100Manager.VitalSample> samples, int param) {
        for (Am4100Manager.VitalSample sample : samples) {
            if (sample.param == param) {
                return true;
            }
        }
        return false;
    }
}
