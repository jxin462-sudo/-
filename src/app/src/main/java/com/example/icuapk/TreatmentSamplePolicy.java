package com.example.icuapk;

/**
 * Defines the scalar statistics contract for treatment records.
 * Waveform acquisition and charts remain separate from scalar measurements.
 */
final class TreatmentSamplePolicy {

    private static final String HEART_RATE = "心率";
    private static final String BLOOD_PRESSURE = "血压";
    private static final String SPO2 = "血氧";
    private static final String PULSE = "脉率";
    private static final String TEMPERATURE = "体温";
    private static final String RESPIRATION = "呼吸率";
    private static final String ECG_WAVEFORM = "心电图";
    private static final String SPO2_WAVEFORM = "血氧波形";
    private static final String RESP_WAVEFORM = "呼吸率波形";

    private TreatmentSamplePolicy() {
    }

    static boolean isWaveform(String itemName) {
        return ECG_WAVEFORM.equals(itemName) || SPO2_WAVEFORM.equals(itemName)
                || RESP_WAVEFORM.equals(itemName);
    }

    /** One historical scalar parameter, or -1 for compound/non-scalar items. */
    static int measurementParam(String itemName) {
        if (HEART_RATE.equals(itemName)) {
            return Am4100Manager.PARAM_HEART_RATE;
        }
        if (SPO2.equals(itemName)) {
            return Am4100Manager.PARAM_SPO2;
        }
        if (PULSE.equals(itemName)) {
            return Am4100Manager.PARAM_PULSE;
        }
        if (TEMPERATURE.equals(itemName)) {
            return Am4100Manager.PARAM_TEMP;
        }
        if (RESPIRATION.equals(itemName)) {
            return Am4100Manager.PARAM_RESP;
        }
        return -1;
    }

    static boolean isValid(String itemName, double value) {
        if (isWaveform(itemName)) {
            return false;
        }
        if (BLOOD_PRESSURE.equals(itemName)) {
            return VitalMeasurementPolicy.isValid(Am4100Manager.PARAM_BP_SYS, value);
        }
        int param = measurementParam(itemName);
        if (param >= 0) {
            return VitalMeasurementPolicy.isValid(param, value);
        }
        return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0.0;
    }
}
