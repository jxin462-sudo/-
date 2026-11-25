package com.example.icuapk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 监护会话存档：进程重启后只恢复报告数据，不把旧实时窗口伪装成新数据。 */
public class MonitorSnapshotCodecTest {

    @Test
    public void roundTripRestoresAllReportChannelsAndClearsLiveWindows() throws Exception {
        List<Am4100Manager.VitalSample> measurements = new ArrayList<>();
        for (int param = Am4100Manager.PARAM_HEART_RATE; param <= Am4100Manager.PARAM_RESP; param++) {
            measurements.add(new Am4100Manager.VitalSample(1700000000000L + param, param, 36.25 + param));
        }
        Am4100Manager.MonitorSnapshot source = new Am4100Manager.MonitorSnapshot(
                Arrays.asList(900, 901), Arrays.asList(800, 801), Arrays.asList(700, 701),
                Arrays.asList(1, 4, 2, 8), Arrays.asList(3, 1, 5, 9), Arrays.asList(7, 2, 6, 0),
                Arrays.asList(80, 81), Arrays.asList(98, 97), Arrays.asList(79, 78),
                Arrays.asList(120, 121), Arrays.asList(80, 79), Arrays.asList(370, 371),
                Arrays.asList(372, 373), Arrays.asList(18, 19), measurements);

        String encoded = MonitorSnapshotCodec.encode(source);
        Am4100Manager.MonitorSnapshot restored = MonitorSnapshotCodec.decode(encoded);

        assertNotNull(restored);
        assertTrue(restored.ecgWaveSamples.isEmpty());
        assertTrue(restored.spo2WaveSamples.isEmpty());
        assertTrue(restored.respWaveSamples.isEmpty());
        assertEquals(source.sessionEcgWaveSamples, restored.sessionEcgWaveSamples);
        assertEquals(source.sessionSpo2WaveSamples, restored.sessionSpo2WaveSamples);
        assertEquals(source.sessionRespWaveSamples, restored.sessionRespWaveSamples);
        assertEquals(source.heartRateHistory, restored.heartRateHistory);
        assertEquals(source.spo2History, restored.spo2History);
        assertEquals(source.pulseRateHistory, restored.pulseRateHistory);
        assertEquals(source.bloodPressureHistory, restored.bloodPressureHistory);
        assertEquals(source.bloodPressureDiastolicHistory, restored.bloodPressureDiastolicHistory);
        assertEquals(source.temperatureHistory, restored.temperatureHistory);
        assertEquals(source.earTemperatureHistory, restored.earTemperatureHistory);
        assertEquals(source.respHistory, restored.respHistory);
        assertEquals(measurements.size(), restored.measurementHistory.size());
        for (int i = 0; i < measurements.size(); i++) {
            assertEquals(measurements.get(i).time, restored.measurementHistory.get(i).time);
            assertEquals(measurements.get(i).param, restored.measurementHistory.get(i).param);
            assertEquals(measurements.get(i).value, restored.measurementHistory.get(i).value, 0.0);
        }
        assertFalse(encoded.contains("ecgWaveSamples"));
    }

    @Test
    public void emptyAndDamagedArchivesNeverCreateDemoData() throws Exception {
        assertEquals("", MonitorSnapshotCodec.encode(null));
        assertNull(MonitorSnapshotCodec.decode(null));
        assertNull(MonitorSnapshotCodec.decode(""));
        assertNull(MonitorSnapshotCodec.decode("not-json"));
        assertNull(MonitorSnapshotCodec.decode("{\"version\":999}"));

        Am4100Manager.MonitorSnapshot restored = MonitorSnapshotCodec.decode(
                MonitorSnapshotCodec.encode(emptySnapshot()));
        assertNotNull(restored);
        assertTrue(restored.sessionEcgWaveSamples.isEmpty());
        assertTrue(restored.sessionSpo2WaveSamples.isEmpty());
        assertTrue(restored.sessionRespWaveSamples.isEmpty());
        assertTrue(restored.measurementHistory.isEmpty());
    }

    @Test
    public void decodeKeepsNewestSamplesWithinMemoryCaps() throws Exception {
        ArrayList<Integer> longWave = new ArrayList<>();
        ArrayList<Integer> longTrend = new ArrayList<>();
        ArrayList<Am4100Manager.VitalSample> longMeasurements = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            longWave.add(i);
        }
        for (int i = 0; i < 900; i++) {
            longTrend.add(80 + i % 100);
        }
        for (int i = 0; i < 3700; i++) {
            int param = i % (Am4100Manager.PARAM_RESP + 1);
            longMeasurements.add(new Am4100Manager.VitalSample(1000L + i,
                    param, validValue(param, i)));
        }
        List<Integer> empty = Collections.emptyList();
        Am4100Manager.MonitorSnapshot source = new Am4100Manager.MonitorSnapshot(
                Collections.<Integer>emptyList(), Collections.<Integer>emptyList(), Collections.<Integer>emptyList(),
                longWave, longWave, longWave, longTrend, empty, empty, empty,
                empty, empty, empty, empty, longMeasurements);

        Am4100Manager.MonitorSnapshot restored = MonitorSnapshotCodec.decode(MonitorSnapshotCodec.encode(source));

        assertNotNull(restored);
        assertEquals(4096, restored.sessionEcgWaveSamples.size());
        assertEquals(Integer.valueOf(904), restored.sessionEcgWaveSamples.get(0));
        assertEquals(Integer.valueOf(4999), restored.sessionEcgWaveSamples.get(4095));
        assertEquals(720, restored.heartRateHistory.size());
        assertEquals(Integer.valueOf(160), restored.heartRateHistory.get(0));
        assertEquals(3600, restored.measurementHistory.size());
        assertEquals(1100L, restored.measurementHistory.get(0).time);
        assertEquals(4699L, restored.measurementHistory.get(3599).time);
    }

    @Test
    public void malformedMeasurementEntriesAreSkippedWithoutFailingValidOnes() throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", MonitorSnapshotCodec.VERSION);
        root.put("sessionEcg", new JSONArray(Arrays.asList(1, 2, 3)));
        JSONArray measurements = new JSONArray();
        measurements.put(new JSONObject().put("t", 1000L).put("p", 0).put("v", 80));
        measurements.put(new JSONObject().put("t", 0L).put("p", 0).put("v", 81));
        measurements.put(new JSONObject().put("t", 1002L).put("p", 99).put("v", 82));
        measurements.put(new JSONObject().put("t", 1003L).put("p", 5).put("v", "bad"));
        measurements.put("bad");
        measurements.put(new JSONObject().put("t", 1004L).put("p", 5).put("v", 37.5));
        root.put("measurements", measurements);

        Am4100Manager.MonitorSnapshot restored = MonitorSnapshotCodec.decode(root.toString());

        assertNotNull(restored);
        assertEquals(Arrays.asList(1, 2, 3), restored.sessionEcgWaveSamples);
        assertEquals(2, restored.measurementHistory.size());
        assertEquals(80.0, restored.measurementHistory.get(0).value, 0.0);
        assertEquals(37.5, restored.measurementHistory.get(1).value, 0.0);
    }

    @Test
    public void oldScalarTrendsAndMeasurementsDropZeroAndOutliers() throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", MonitorSnapshotCodec.VERSION);
        root.put("heartRate", new JSONArray(Arrays.asList(0, 80, 613, 81)));
        root.put("spo2", new JSONArray(Arrays.asList(0, 98, 101, 97)));
        root.put("temperature", new JSONArray(Arrays.asList(0, 376, 460, 375)));
        root.put("earTemperature", new JSONArray(Arrays.asList(0, 372, 999)));
        JSONArray measurements = new JSONArray();
        measurements.put(new JSONObject().put("t", 1000L).put("p", 5).put("v", 0));
        measurements.put(new JSONObject().put("t", 1001L).put("p", 0).put("v", 613));
        measurements.put(new JSONObject().put("t", 1002L).put("p", 0).put("v", 80));
        measurements.put(new JSONObject().put("t", 1003L).put("p", 5).put("v", 37.6));
        root.put("measurements", measurements);

        Am4100Manager.MonitorSnapshot restored = MonitorSnapshotCodec.decode(root.toString());
        assertNotNull(restored);
        assertEquals(Arrays.asList(80, 81), restored.heartRateHistory);
        assertEquals(Arrays.asList(98, 97), restored.spo2History);
        assertEquals(Arrays.asList(376, 375), restored.temperatureHistory);
        assertEquals(Arrays.asList(372), restored.earTemperatureHistory);
        assertEquals(2, restored.measurementHistory.size());
        assertEquals(80.0, restored.measurementHistory.get(0).value, 0.0);
        assertEquals(37.6, restored.measurementHistory.get(1).value, 0.0);
    }

    private static double validValue(int param, int index) {
        switch (param) {
            case Am4100Manager.PARAM_HEART_RATE:
                return 80 + index % 100;
            case Am4100Manager.PARAM_BP_SYS:
                return 110 + index % 50;
            case Am4100Manager.PARAM_BP_DIA:
                return 60 + index % 30;
            case Am4100Manager.PARAM_PULSE:
                return 70 + index % 100;
            case Am4100Manager.PARAM_SPO2:
                return 90 + index % 10;
            case Am4100Manager.PARAM_TEMP:
                return 35.0 + (index % 50) / 10.0;
            case Am4100Manager.PARAM_RESP:
                return 10 + index % 30;
            default:
                throw new IllegalArgumentException("unknown param " + param);
        }
    }

    private static Am4100Manager.MonitorSnapshot emptySnapshot() {
        List<Integer> empty = Collections.emptyList();
        return new Am4100Manager.MonitorSnapshot(empty, empty, empty, empty, empty, empty,
                empty, empty, empty, empty, empty, empty, empty, empty,
                Collections.<Am4100Manager.VitalSample>emptyList());
    }
}
