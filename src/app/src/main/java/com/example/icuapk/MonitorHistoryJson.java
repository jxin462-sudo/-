package com.example.icuapk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/** monitorHistory 原生桥的唯一序列化出口，保留真实参数号、时间戳和 double 数值。 */
final class MonitorHistoryJson {

    private MonitorHistoryJson() {
    }

    static String encode(long now, List<Am4100Manager.VitalSample> samples) {
        try {
            JSONObject root = new JSONObject();
            root.put("now", now);
            JSONArray items = new JSONArray();
            if (samples != null) {
                for (Am4100Manager.VitalSample sample : samples) {
                    if (sample == null || !VitalMeasurementPolicy.isValidSample(
                            sample.time, sample.param, sample.value)) {
                        continue;
                    }
                    JSONObject item = new JSONObject();
                    item.put("t", sample.time);
                    item.put("p", sample.param);
                    item.put("v", sample.value);
                    items.put(item);
                }
            }
            root.put("items", items);
            return root.toString();
        } catch (JSONException exception) {
            return "{}";
        }
    }
}
