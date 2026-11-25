package com.example.icuapk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 监护会话存档的版本化编解码。
 *
 * <p>只持久化报告需要的会话波形、趋势和带时间戳的测量历史；实时窗口在冷启动时保持空白，
 * 避免把重启前的最后一段实时波形误显示成仍在到达的新数据。所有列表都有与内存缓冲一致的上限。</p>
 */
final class MonitorSnapshotCodec {
    static final int VERSION = 1;
    private static final int MAX_SESSION_WAVE_SAMPLES = 4096;
    private static final int MAX_TREND_SAMPLES = 720;
    private static final int MAX_MEASUREMENT_HISTORY = 3600;

    private MonitorSnapshotCodec() {
    }

    static String encode(Am4100Manager.MonitorSnapshot snapshot) throws JSONException {
        if (snapshot == null) {
            return "";
        }
        JSONObject root = new JSONObject();
        root.put("version", VERSION);
        root.put("sessionEcg", intArray(snapshot.sessionEcgWaveSamples));
        root.put("sessionSpo2", intArray(snapshot.sessionSpo2WaveSamples));
        root.put("sessionResp", intArray(snapshot.sessionRespWaveSamples));
        root.put("heartRate", trendIntArray(snapshot.heartRateHistory, Am4100Manager.PARAM_HEART_RATE, false));
        root.put("spo2", trendIntArray(snapshot.spo2History, Am4100Manager.PARAM_SPO2, false));
        root.put("pulseRate", trendIntArray(snapshot.pulseRateHistory, Am4100Manager.PARAM_PULSE, false));
        root.put("bloodPressure", trendIntArray(snapshot.bloodPressureHistory, Am4100Manager.PARAM_BP_SYS, false));
        root.put("bloodPressureDiastolic", trendIntArray(snapshot.bloodPressureDiastolicHistory, Am4100Manager.PARAM_BP_DIA, false));
        root.put("temperature", trendIntArray(snapshot.temperatureHistory, Am4100Manager.PARAM_TEMP, true));
        root.put("earTemperature", trendIntArray(snapshot.earTemperatureHistory, Am4100Manager.PARAM_TEMP, true));
        root.put("resp", trendIntArray(snapshot.respHistory, Am4100Manager.PARAM_RESP, false));
        JSONArray measurements = new JSONArray();
        if (snapshot.measurementHistory != null) {
            int start = Math.max(0, snapshot.measurementHistory.size() - MAX_MEASUREMENT_HISTORY);
            for (int i = start; i < snapshot.measurementHistory.size(); i++) {
                Am4100Manager.VitalSample sample = snapshot.measurementHistory.get(i);
                if (sample == null || !VitalMeasurementPolicy.isValidSample(
                        sample.time, sample.param, sample.value)) {
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("t", sample.time);
                item.put("p", sample.param);
                item.put("v", sample.value);
                measurements.put(item);
            }
        }
        root.put("measurements", measurements);
        return root.toString();
    }

    static Am4100Manager.MonitorSnapshot decode(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("version", -1) != VERSION) {
                return null;
            }
            return new Am4100Manager.MonitorSnapshot(
                    new ArrayList<Integer>(), new ArrayList<Integer>(), new ArrayList<Integer>(),
                    intList(root.optJSONArray("sessionEcg"), MAX_SESSION_WAVE_SAMPLES),
                    intList(root.optJSONArray("sessionSpo2"), MAX_SESSION_WAVE_SAMPLES),
                    intList(root.optJSONArray("sessionResp"), MAX_SESSION_WAVE_SAMPLES),
                    trendIntList(root.optJSONArray("heartRate"), Am4100Manager.PARAM_HEART_RATE, false),
                    trendIntList(root.optJSONArray("spo2"), Am4100Manager.PARAM_SPO2, false),
                    trendIntList(root.optJSONArray("pulseRate"), Am4100Manager.PARAM_PULSE, false),
                    trendIntList(root.optJSONArray("bloodPressure"), Am4100Manager.PARAM_BP_SYS, false),
                    trendIntList(root.optJSONArray("bloodPressureDiastolic"), Am4100Manager.PARAM_BP_DIA, false),
                    trendIntList(root.optJSONArray("temperature"), Am4100Manager.PARAM_TEMP, true),
                    trendIntList(root.optJSONArray("earTemperature"), Am4100Manager.PARAM_TEMP, true),
                    trendIntList(root.optJSONArray("resp"), Am4100Manager.PARAM_RESP, false),
                    measurementList(root.optJSONArray("measurements")));
        } catch (Exception ignored) {
            // 损坏或未来版本存档不能变成演示数据；本次只按无快照处理。
            return null;
        }
    }

    private static JSONArray intArray(List<Integer> values) {
        JSONArray array = new JSONArray();
        if (values != null) {
            for (Integer value : values) {
                if (value != null) {
                    array.put(value.intValue());
                }
            }
        }
        return array;
    }

    /** 体温趋势以 0.1℃ 整数存储，其余趋势直接使用原参数单位。 */
    private static JSONArray trendIntArray(List<Integer> values, int param, boolean tenths) {
        JSONArray array = new JSONArray();
        if (values != null) {
            for (Integer value : values) {
                if (value != null && VitalMeasurementPolicy.isValid(param,
                        tenths ? value.intValue() / 10.0 : value.intValue())) {
                    array.put(value.intValue());
                }
            }
        }
        return array;
    }

    private static List<Integer> trendIntList(JSONArray array, int param, boolean tenths) {
        ArrayList<Integer> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        int start = Math.max(0, array.length() - MAX_TREND_SAMPLES);
        for (int i = start; i < array.length(); i++) {
            if (array.isNull(i)) {
                continue;
            }
            int value = array.optInt(i);
            if (VitalMeasurementPolicy.isValid(param, tenths ? value / 10.0 : value)) {
                values.add(value);
            }
        }
        return values;
    }

    private static List<Integer> intList(JSONArray array, int cap) {
        ArrayList<Integer> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        int start = Math.max(0, array.length() - cap);
        for (int i = start; i < array.length(); i++) {
            if (!array.isNull(i)) {
                values.add(array.optInt(i));
            }
        }
        return values;
    }

    private static List<Am4100Manager.VitalSample> measurementList(JSONArray array) {
        ArrayList<Am4100Manager.VitalSample> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        int start = Math.max(0, array.length() - MAX_MEASUREMENT_HISTORY);
        for (int i = start; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            long time = item.optLong("t", 0L);
            int param = item.optInt("p", -1);
            double value = item.optDouble("v", Double.NaN);
            if (!VitalMeasurementPolicy.isValidSample(time, param, value)) {
                continue;
            }
            values.add(new Am4100Manager.VitalSample(time, param, value));
        }
        return values;
    }
}
