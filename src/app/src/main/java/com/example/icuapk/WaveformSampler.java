package com.example.icuapk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 报告波形抽稀：覆盖完整采样窗口，并按原先后顺序保留每桶峰谷。 */
final class WaveformSampler {

    private WaveformSampler() {
    }

    static List<Integer> downsample(List<Integer> samples, int targetCount) {
        if (samples == null || samples.isEmpty() || targetCount <= 0) {
            return Collections.emptyList();
        }
        if (samples.size() <= targetCount) {
            return new ArrayList<>(samples);
        }
        ArrayList<Integer> result = new ArrayList<>(targetCount);
        if (targetCount < 4) {
            result.add(samples.get(0));
            if (targetCount > 1) {
                result.add(samples.get(samples.size() - 1));
            }
            return result;
        }
        int bucketCount = targetCount / 2;
        int size = samples.size();
        for (int bucket = 0; bucket < bucketCount; bucket++) {
            int start = bucket * size / bucketCount;
            int end = (bucket + 1) * size / bucketCount;
            int minIndex = start;
            int maxIndex = start;
            for (int i = start + 1; i < end; i++) {
                int value = samples.get(i);
                if (value < samples.get(minIndex)) {
                    minIndex = i;
                }
                if (value > samples.get(maxIndex)) {
                    maxIndex = i;
                }
            }
            if (minIndex == maxIndex) {
                result.add(samples.get(minIndex));
            } else if (minIndex < maxIndex) {
                result.add(samples.get(minIndex));
                result.add(samples.get(maxIndex));
            } else {
                result.add(samples.get(maxIndex));
                result.add(samples.get(minIndex));
            }
        }
        return result;
    }
}
