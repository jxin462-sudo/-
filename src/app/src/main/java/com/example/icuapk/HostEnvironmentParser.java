package com.example.icuapk;

import org.json.JSONObject;

/**
 * 主机温、氧、湿、CO2 回包的统一读取。
 *
 * <p>不同固件会把数值放在总字段里，也会包成 current/value 对象；
 * 这一层只负责识别字段和量纲，不接触蓝牙或界面状态，便于做 JVM 回归。</p>
 */
final class HostEnvironmentParser {
    private HostEnvironmentParser() {
    }

    static final class Reading {
        Float temperature;
        Float oxygen;
        Float humidity;
        Integer co2;
        JSONObject temperatureObject;
        JSONObject oxygenObject;
        JSONObject co2Object;

        boolean hasAnyValue() {
            return temperature != null || oxygen != null || humidity != null || co2 != null;
        }
    }

    static Reading read(JSONObject data) {
        Reading reading = new Reading();
        if (data == null) {
            return reading;
        }
        Object temp = first(data, "temp", "temperature");
        Object oxygen = first(data, "o2", "oxygen");
        Object humidity = first(data, "humidity");
        Object co2 = first(data, "co2");

        reading.temperatureObject = asObject(temp);
        reading.oxygenObject = asObject(oxygen);
        reading.co2Object = asObject(co2);
        reading.temperature = scaledNumber(temp, "current", "value", "temperature", "temp");
        reading.oxygen = scaledNumber(oxygen, "current", "value", "concentration", "oxygen", "o2");
        reading.humidity = scaledNumber(humidity, "current", "value", "humidity");
        reading.co2 = integerNumber(co2, "current", "value", "co2");
        return reading;
    }

    static boolean hasEnvironmentField(JSONObject data) {
        if (data == null) {
            return false;
        }
        return data.has("temp") || data.has("temperature") || data.has("o2")
                || data.has("oxygen") || data.has("humidity") || data.has("co2");
    }

    private static Object first(JSONObject object, String... keys) {
        for (String key : keys) {
            Object raw = object.opt(key);
            if (raw != null && raw != JSONObject.NULL) {
                return raw;
            }
        }
        return null;
    }

    private static JSONObject asObject(Object raw) {
        return raw instanceof JSONObject ? (JSONObject) raw : null;
    }

    private static Float scaledNumber(Object raw, String... nestedKeys) {
        Double value = number(raw, nestedKeys);
        if (value == null) {
            return null;
        }
        // 协议中的放大十倍整数通常大于 100；已经是小数的读数不能再次缩小。
        if (Math.abs(value) > 100d) {
            value = value / 10d;
        }
        return value.floatValue();
    }

    private static Integer integerNumber(Object raw, String... nestedKeys) {
        Double value = number(raw, nestedKeys);
        return value == null ? null : value.intValue();
    }

    private static Double number(Object raw, String... nestedKeys) {
        if (raw instanceof JSONObject) {
            JSONObject object = (JSONObject) raw;
            for (String key : nestedKeys) {
                Double value = number(object.opt(key));
                if (value != null) {
                    return value;
                }
            }
            return null;
        }
        return number(raw);
    }

    private static Double number(Object raw) {
        double value;
        if (raw instanceof Number) {
            value = ((Number) raw).doubleValue();
        } else if (raw instanceof String) {
            String text = ((String) raw).trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                value = Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        } else {
            return null;
        }
        return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
    }
}
