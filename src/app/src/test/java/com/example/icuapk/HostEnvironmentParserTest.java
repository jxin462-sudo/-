package com.example.icuapk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class HostEnvironmentParserTest {
    @Test
    public void readsFlatNumbersAndScaledIntegers() throws Exception {
        JSONObject data = new JSONObject()
                .put("temp", 298)
                .put("o2", 250)
                .put("humidity", 650)
                .put("co2", 580);

        HostEnvironmentParser.Reading reading = HostEnvironmentParser.read(data);

        assertEquals(29.8f, reading.temperature, 0.001f);
        assertEquals(25.0f, reading.oxygen, 0.001f);
        assertEquals(65.0f, reading.humidity, 0.001f);
        assertEquals(Integer.valueOf(580), reading.co2);
        assertTrue(reading.hasAnyValue());
    }

    @Test
    public void readsObjectsAliasesAndNumericStrings() throws Exception {
        JSONObject data = new JSONObject()
                .put("temperature", new JSONObject().put("current", "29.8"))
                .put("oxygen", new JSONObject().put("value", "25.0"))
                .put("humidity", new JSONObject().put("current", "61.5"))
                .put("co2", new JSONObject().put("value", "605"));

        HostEnvironmentParser.Reading reading = HostEnvironmentParser.read(data);

        assertEquals(29.8f, reading.temperature, 0.001f);
        assertEquals(25.0f, reading.oxygen, 0.001f);
        assertEquals(61.5f, reading.humidity, 0.001f);
        assertEquals(Integer.valueOf(605), reading.co2);
    }

    @Test
    public void zeroIsAValidReadingNotMissingData() throws Exception {
        JSONObject data = new JSONObject()
                .put("temp", 0)
                .put("o2", "0")
                .put("humidity", new JSONObject().put("value", 0))
                .put("co2", 0);

        HostEnvironmentParser.Reading reading = HostEnvironmentParser.read(data);

        assertEquals(0.0f, reading.temperature, 0.0f);
        assertEquals(0.0f, reading.oxygen, 0.0f);
        assertEquals(0.0f, reading.humidity, 0.0f);
        assertEquals(Integer.valueOf(0), reading.co2);
        assertTrue(reading.hasAnyValue());
    }

    @Test
    public void mixedScalarAndObjectShapesKeepEveryAvailableField() throws Exception {
        JSONObject data = new JSONObject()
                .put("temp", new JSONObject().put("value", 285).put("status", "on"))
                .put("o2", 23.5)
                .put("humidity", 58)
                .put("co2", new JSONObject().put("current", 0));

        HostEnvironmentParser.Reading reading = HostEnvironmentParser.read(data);

        assertEquals(28.5f, reading.temperature, 0.001f);
        assertEquals(23.5f, reading.oxygen, 0.001f);
        assertEquals(58.0f, reading.humidity, 0.001f);
        assertEquals(Integer.valueOf(0), reading.co2);
        assertEquals("on", reading.temperatureObject.optString("status"));
    }

    @Test
    public void missingNullAndInvalidValuesRemainEmpty() throws Exception {
        JSONObject data = new JSONObject()
                .put("temp", JSONObject.NULL)
                .put("o2", "not-a-number")
                .put("humidity", new JSONObject().put("status", "off"));

        HostEnvironmentParser.Reading reading = HostEnvironmentParser.read(data);

        assertNull(reading.temperature);
        assertNull(reading.oxygen);
        assertNull(reading.humidity);
        assertNull(reading.co2);
        assertFalse(reading.hasAnyValue());
        assertTrue(HostEnvironmentParser.hasEnvironmentField(data));
        assertFalse(HostEnvironmentParser.hasEnvironmentField(new JSONObject()));
    }
}
