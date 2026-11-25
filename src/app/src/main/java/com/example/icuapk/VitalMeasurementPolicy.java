package com.example.icuapk;

/**
 * 实测数据的应用层异常防御，与用户设置的报警上下限无关。
 * 范围只负责拦截明显无效帧，不把异常值钳成正常值，也不生成替代测量。
 */
final class VitalMeasurementPolicy {

    private VitalMeasurementPolicy() {
    }

    static boolean isValidSample(long time, int param, double value) {
        return time > 0L && isValid(param, value);
    }

    static boolean isValid(int param, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return false;
        }
        switch (param) {
            case Am4100Manager.PARAM_HEART_RATE:
            case Am4100Manager.PARAM_PULSE:
                return value >= 1.0 && value <= 400.0;
            case Am4100Manager.PARAM_BP_SYS:
            case Am4100Manager.PARAM_BP_DIA:
                return value >= 1.0 && value <= 400.0;
            case Am4100Manager.PARAM_SPO2:
                return value >= 1.0 && value <= 100.0;
            case Am4100Manager.PARAM_TEMP:
                return value >= 30.0 && value <= 45.0;
            case Am4100Manager.PARAM_RESP:
                return value >= 1.0 && value <= 200.0;
            default:
                return false;
        }
    }
}
