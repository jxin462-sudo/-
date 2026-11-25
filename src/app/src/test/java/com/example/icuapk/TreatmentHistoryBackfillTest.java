package com.example.icuapk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class TreatmentHistoryBackfillTest {

    @Test
    public void treatmentWindowUsesOriginalRealSamplesOnly() {
        ArrayList<Am4100Manager.VitalSample> history = new ArrayList<>();
        history.add(new Am4100Manager.VitalSample(999L, 0, 70));       // 治疗前
        history.add(new Am4100Manager.VitalSample(1000L, 0, 80));
        history.add(new Am4100Manager.VitalSample(2000L, 4, 98));       // 其他参数
        history.add(new Am4100Manager.VitalSample(3000L, 0, 0));        // 无效值
        history.add(new Am4100Manager.VitalSample(4000L, 0, 613));      // 异常值
        history.add(new Am4100Manager.VitalSample(5000L, 0, 82));
        history.add(new Am4100Manager.VitalSample(6001L, 0, 84));       // 结算后
        CollectingSink sink = new CollectingSink();

        TreatmentHistoryBackfill.Result result = TreatmentHistoryBackfill.backfill(
                history, Am4100Manager.PARAM_HEART_RATE, 1000L, 6000L, 0L, sink);

        assertEquals(2, result.matched);
        assertEquals(2, result.added);
        assertEquals(5000L, result.latestTime);
        assertEquals(2, sink.values.size());
        assertEquals(1000L, sink.values.get(0).time);
        assertEquals(80.0, sink.values.get(0).value, 0.0);
        assertEquals(5000L, sink.values.get(1).time);
        assertEquals(82.0, sink.values.get(1).value, 0.0);
    }

    @Test
    public void repeatedSettlementDoesNotCountOldPointsAgainEvenAfterDetailCap() {
        ArrayList<Am4100Manager.VitalSample> history = new ArrayList<>();
        history.add(new Am4100Manager.VitalSample(1000L, 6, 18));
        history.add(new Am4100Manager.VitalSample(2000L, 6, 20));
        CollectingSink sink = new CollectingSink();
        TreatmentHistoryBackfill.Result first = TreatmentHistoryBackfill.backfill(
                history, Am4100Manager.PARAM_RESP, 1000L, 6000L, 0L, sink);
        assertEquals(2, first.added);

        // 模拟采样明细达到容量后旧点被淘汰；游标仍必须防止重新累计整段历史。
        sink.values.clear();
        TreatmentHistoryBackfill.Result repeated = TreatmentHistoryBackfill.backfill(
                history, Am4100Manager.PARAM_RESP, 1000L, 6000L, first.latestTime, sink);
        assertEquals(0, repeated.matched);
        assertEquals(0, repeated.added);
        assertTrue(sink.values.isEmpty());

        history.add(new Am4100Manager.VitalSample(3000L, 6, 22));
        TreatmentHistoryBackfill.Result next = TreatmentHistoryBackfill.backfill(
                history, Am4100Manager.PARAM_RESP, 1000L, 6000L, repeated.latestTime, sink);
        assertEquals(1, next.added);
        assertEquals(3000L, sink.values.get(0).time);
        assertEquals(22.0, sink.values.get(0).value, 0.0);
    }

    @Test
    public void identicalTimestampAndValueAreInsertedOnlyOnce() {
        ArrayList<Am4100Manager.VitalSample> history = new ArrayList<>();
        history.add(new Am4100Manager.VitalSample(1000L, 5, 37.6));
        history.add(new Am4100Manager.VitalSample(1000L, 5, 37.6));
        CollectingSink sink = new CollectingSink();
        TreatmentHistoryBackfill.Result result = TreatmentHistoryBackfill.backfill(
                history, Am4100Manager.PARAM_TEMP, 1000L, 2000L, 0L, sink);
        assertEquals(2, result.matched);
        assertEquals(1, result.added);
        assertEquals(1, sink.values.size());
    }

    private static final class CollectingSink implements TreatmentHistoryBackfill.Sink {
        final List<Am4100Manager.VitalSample> values = new ArrayList<>();

        @Override
        public boolean contains(long time, float value) {
            for (Am4100Manager.VitalSample sample : values) {
                if (sample.time == time && Float.compare((float) sample.value, value) == 0) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void add(long time, float value) {
            values.add(new Am4100Manager.VitalSample(time, 0, value));
        }
    }
}
