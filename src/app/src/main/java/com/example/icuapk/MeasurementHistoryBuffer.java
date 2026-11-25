package com.example.icuapk;

import java.util.ArrayList;
import java.util.List;

/**
 * 带时间戳的监护测量历史。
 *
 * 高频参数不能把其他参数从共用缓冲里全部挤掉；相同数值也不逐帧重复写入。
 * 这里只保存调用方已经判定为有效的真实测量，不生成补零点或演示数据。
 */
final class MeasurementHistoryBuffer {
    static final int CAPACITY = 3600;
    private static final int PARAM_COUNT = Am4100Manager.PARAM_RESP + 1;
    private static final long MIN_SAMPLE_INTERVAL_MS = 1000L;
    private static final long SAME_VALUE_INTERVAL_MS = 60000L;

    private final ArrayList<Am4100Manager.VitalSample> samples = new ArrayList<>();

    void record(long time, int param, double value) {
        if (!isValid(time, param, value)) {
            return;
        }
        int lastIndex = findLastIndex(param);
        if (lastIndex >= 0) {
            Am4100Manager.VitalSample previous = samples.get(lastIndex);
            long elapsed = time - previous.time;
            if (elapsed >= 0L && elapsed < MIN_SAMPLE_INTERVAL_MS) {
                return;
            }
            if (elapsed >= 0L && elapsed < SAME_VALUE_INTERVAL_MS
                    && Double.compare(previous.value, value) == 0) {
                return;
            }
        }
        append(time, param, value);
    }

    List<Am4100Manager.VitalSample> copy() {
        return new ArrayList<>(samples);
    }

    void clear() {
        samples.clear();
    }

    /** 恢复快照时保留原时间戳和数值，不按当前时间补点，也不重复节流。 */
    void replace(List<Am4100Manager.VitalSample> restored) {
        samples.clear();
        if (restored == null) {
            return;
        }
        for (Am4100Manager.VitalSample sample : restored) {
            if (sample != null && isValid(sample.time, sample.param, sample.value)) {
                append(sample.time, sample.param, sample.value);
            }
        }
    }

    private void append(long time, int param, double value) {
        if (samples.size() >= CAPACITY) {
            evictFor(param);
        }
        samples.add(new Am4100Manager.VitalSample(time, param, value));
    }

    private int findLastIndex(int param) {
        for (int i = samples.size() - 1; i >= 0; i--) {
            if (samples.get(i).param == param) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 缓冲已满时，新参数或样本较少的参数可以挤占高频参数的最旧点；
     * 参数已达到公平份额后才淘汰自己的最旧点，避免最后只剩一种 p 值。
     */
    private void evictFor(int incomingParam) {
        int[] counts = new int[PARAM_COUNT];
        for (Am4100Manager.VitalSample sample : samples) {
            counts[sample.param]++;
        }
        int target = incomingParam;
        int fairShare = (CAPACITY + PARAM_COUNT - 1) / PARAM_COUNT;
        if (counts[incomingParam] < fairShare) {
            int largest = counts[incomingParam];
            for (int param = 0; param < PARAM_COUNT; param++) {
                if (counts[param] > largest) {
                    largest = counts[param];
                    target = param;
                }
            }
        }
        for (int i = 0; i < samples.size(); i++) {
            if (samples.get(i).param == target) {
                samples.remove(i);
                return;
            }
        }
        samples.remove(0);
    }

    private static boolean isValid(long time, int param, double value) {
        return VitalMeasurementPolicy.isValidSample(time, param, value);
    }
}
