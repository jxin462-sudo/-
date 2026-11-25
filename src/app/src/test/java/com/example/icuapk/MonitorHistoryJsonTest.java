package com.example.icuapk;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;

public class MonitorHistoryJsonTest {

    @Test
    public void bridgeKeepsAllSevenParameterIdsTimesAndNonZeroValues() throws Exception {
        ArrayList<Am4100Manager.VitalSample> samples = new ArrayList<>();
        double[] values = {78, 121, 82, 76, 98, 37.6, 18};
        long now = 1700000001000L;
        for (int param = 0; param < values.length; param++) {
            samples.add(new Am4100Manager.VitalSample(now - 60000L + param, param, values[param]));
        }
        JSONObject root = new JSONObject(MonitorHistoryJson.encode(now, samples));
        assertEquals(now, root.getLong("now"));
        JSONArray items = root.getJSONArray("items");
        assertEquals(7, items.length());
        for (int param = 0; param < values.length; param++) {
            JSONObject item = items.getJSONObject(param);
            assertEquals(param, item.getInt("p"));
            assertEquals(now - 60000L + param, item.getLong("t"));
            assertEquals(values[param], item.getDouble("v"), 0.0);
        }
    }

    @Test
    public void damagedItemsAreSkippedRatherThanConvertedToZero() throws Exception {
        ArrayList<Am4100Manager.VitalSample> samples = new ArrayList<>();
        samples.add(null);
        samples.add(new Am4100Manager.VitalSample(0L, 0, 80));
        samples.add(new Am4100Manager.VitalSample(1000L, 7, 80));
        samples.add(new Am4100Manager.VitalSample(1000L, 0, Double.NaN));
        samples.add(new Am4100Manager.VitalSample(1000L, 0, Double.NEGATIVE_INFINITY));
        samples.add(new Am4100Manager.VitalSample(1000L, 0, 0));
        samples.add(new Am4100Manager.VitalSample(1001L, 0, 613));
        samples.add(new Am4100Manager.VitalSample(1002L, 5, 0));
        samples.add(new Am4100Manager.VitalSample(1003L, 4, 101));
        samples.add(new Am4100Manager.VitalSample(1000L, 4, 98));
        JSONObject root = new JSONObject(MonitorHistoryJson.encode(2000L, samples));
        JSONArray items = root.getJSONArray("items");
        assertEquals(1, items.length());
        assertEquals(4, items.getJSONObject(0).getInt("p"));
        assertEquals(98, items.getJSONObject(0).getDouble("v"), 0.0);
    }

    @Test
    public void nullHistoryProducesNormalEmptyPayload() throws Exception {
        JSONObject root = new JSONObject(MonitorHistoryJson.encode(
                2000L, Collections.<Am4100Manager.VitalSample>emptyList()));
        assertEquals(2000L, root.getLong("now"));
        assertEquals(0, root.getJSONArray("items").length());
    }
}
