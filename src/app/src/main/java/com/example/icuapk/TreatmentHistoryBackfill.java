package com.example.icuapk;

import java.util.List;

/** 把治疗窗口内的真实监护测量补入治疗记录，不制造结束时刻的替代测量。 */
final class TreatmentHistoryBackfill {

    interface Sink {
        boolean contains(long time, float value);

        void add(long time, float value);
    }

    static final class Result {
        final int matched;
        final int added;
        final long latestTime;

        Result(int matched, int added, long latestTime) {
            this.matched = matched;
            this.added = added;
            this.latestTime = latestTime;
        }
    }

    private TreatmentHistoryBackfill() {
    }

    /**
     * after 是已经结算过的最新测量时间；即使采样明细因容量淘汰，也不会重复累计旧点。
     * 只处理指定参数和闭区间 [from, to] 内的有效实测，保持原时间戳和数值。
     */
    static Result backfill(List<Am4100Manager.VitalSample> samples, int param,
                           long from, long to, long after, Sink sink) {
        int matched = 0;
        int added = 0;
        long latest = after;
        if (samples == null || sink == null || from <= 0L || to < from) {
            return new Result(0, 0, latest);
        }
        for (Am4100Manager.VitalSample sample : samples) {
            if (sample == null || sample.param != param || sample.time <= after
                    || sample.time < from || sample.time > to
                    || !VitalMeasurementPolicy.isValidSample(sample.time, param, sample.value)) {
                continue;
            }
            matched++;
            latest = Math.max(latest, sample.time);
            float value = (float) sample.value;
            if (!sink.contains(sample.time, value)) {
                sink.add(sample.time, value);
                added++;
            }
        }
        return new Result(matched, added, latest);
    }
}
