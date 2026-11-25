package com.example.icuapk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 治疗记录真实使用时长的 JVM 回归。
 * 只覆盖不依赖 Android 框架的纯计算；页面/报告的装配由静态探针核对。
 */
public class TreatmentUsageTest {

    @Test
    public void zeroAndNegativeDurationStayBlank() {
        assertEquals("", TreatmentUsage.formatUsage(0L));
        assertEquals("", TreatmentUsage.formatUsage(-1L));
        assertEquals(0L, TreatmentUsage.effectiveOnMs(0L, false, 0L, 1_000_000L));
        assertEquals("--", TreatmentUsage.durationOrPlaceholder(TreatmentUsage.formatUsage(0L)));
        assertFalse(TreatmentUsage.wasUsed(false, false, 0L, 0L));
    }

    @Test
    public void subMinuteShowsRealSecondsWithoutPretendingOneMinute() {
        // 不足一分钟必须显示真实秒数；旧口径一律抬到「1分钟」会把短开关沿伪造成长治疗。
        assertEquals("1秒", TreatmentUsage.formatUsage(1L));
        assertEquals("1秒", TreatmentUsage.formatUsage(500L));
        assertEquals("2秒", TreatmentUsage.formatUsage(1500L));
        assertEquals("5秒", TreatmentUsage.formatUsage(5000L));
        assertEquals("45秒", TreatmentUsage.formatUsage(45000L));
        assertEquals("60秒", TreatmentUsage.formatUsage(59999L));
    }

    @Test
    public void minuteAndHourFormats() {
        assertEquals("1分钟", TreatmentUsage.formatUsage(60000L));
        assertEquals("2分钟", TreatmentUsage.formatUsage(90000L));
        assertEquals("2分钟", TreatmentUsage.formatUsage(120000L));
        assertEquals("59分钟", TreatmentUsage.formatUsage(59L * 60000L));
        assertEquals("1小时", TreatmentUsage.formatUsage(3600000L));
        assertEquals("1小时30分钟", TreatmentUsage.formatUsage(5400000L));
        assertEquals("2小时", TreatmentUsage.formatUsage(7200000L));
    }

    @Test
    public void shortSwitchEdgeUnderSixtySecondsStillHasRealDuration() {
        // 开关只开了 5 秒：数值采样仍受 60 秒门限，但开启区间必须单独记下，报告不能是 "--"。
        long total = TreatmentUsage.effectiveOnMs(0L, true, 1_000L, 6_000L);
        assertEquals(5000L, total);
        assertEquals("5秒", TreatmentUsage.durationOrPlaceholder(TreatmentUsage.formatUsage(total)));
    }

    @Test
    public void activeIntervalIsFoldedWithoutMutatingAccumulatedTotal() {
        assertEquals(60000L, TreatmentUsage.effectiveOnMs(30000L, true, 1_000L, 31_000L));
        assertEquals("1分钟", TreatmentUsage.formatUsage(
                TreatmentUsage.effectiveOnMs(0L, true, 1_000L, 61_000L)));
        // 只关开关、没有进行中区间时不凭空加时长
        assertEquals(30000L, TreatmentUsage.effectiveOnMs(30000L, false, 1_000L, 31_000L));
        assertEquals(30000L, TreatmentUsage.effectiveOnMs(30000L, true, 0L, 31_000L));
    }

    @Test
    public void segmentedOnOffIntervalsAccumulateInsteadOfOverwriting() {
        long total = 0L;
        total = TreatmentUsage.effectiveOnMs(total, true, 1_000L, 11_000L);    // +10s
        total = TreatmentUsage.effectiveOnMs(total, true, 20_000L, 40_000L);   // +20s
        total = TreatmentUsage.effectiveOnMs(total, true, 50_000L, 80_000L);   // +30s
        total = TreatmentUsage.effectiveOnMs(total, true, 90_000L, 150_000L);  // +60s
        assertEquals(120000L, total);
        assertEquals("2分钟", TreatmentUsage.formatUsage(total));
    }

    @Test
    public void finishSettlementFoldsActiveIntervalAndNeverCountsItTwice() {
        // 结束治疗时项目仍开着：把进行中区间折入 totalOnMs，随后清空 featureOn/featureOnSince。
        long settled = TreatmentUsage.effectiveOnMs(30000L, true, 1_000_000L, 1_030_000L);
        assertEquals(60000L, settled);
        // 结算后的两次展示/再次结算都不能重复叠加同一区间
        long afterFlagsCleared = TreatmentUsage.effectiveOnMs(settled, false, 0L, 2_000_000L);
        assertEquals(60000L, afterFlagsCleared);
        assertEquals(60000L, TreatmentUsage.effectiveOnMs(afterFlagsCleared, false, 0L, 9_000_000L));
        assertEquals("1分钟", TreatmentUsage.durationOrPlaceholder(
                TreatmentUsage.formatUsage(afterFlagsCleared)));
    }

    @Test
    public void clockGoingBackwardsNeverSubtractsFromAccumulatedTime() {
        assertEquals(5000L, TreatmentUsage.effectiveOnMs(5000L, true, 100_000L, 90_000L));
        assertEquals("5秒", TreatmentUsage.formatUsage(
                TreatmentUsage.effectiveOnMs(0L, true, 100_000L, 105_000L)));
    }

    @Test
    public void reportPlaceholderIsKeptOnlyForUnusedItems() {
        assertEquals("--", TreatmentUsage.durationOrPlaceholder(null));
        assertEquals("--", TreatmentUsage.durationOrPlaceholder(""));
        assertEquals("5秒", TreatmentUsage.durationOrPlaceholder("5秒"));
        assertEquals("1分钟", TreatmentUsage.durationOrPlaceholder("1分钟"));
    }

    @Test
    public void reportCheckboxReflectsCaseUsageNotGlobalDeviceSwitch() {
        assertTrue(TreatmentUsage.wasUsed(true, false, 0L, 0L));    // 捕获过开始时间
        assertTrue(TreatmentUsage.wasUsed(false, true, 0L, 0L));    // 此刻仍开启
        assertTrue(TreatmentUsage.wasUsed(false, false, 1_000L, 0L)); // 进行中区间
        assertTrue(TreatmentUsage.wasUsed(false, false, 0L, 1L));   // 已关闭但累计过时长
        assertFalse(TreatmentUsage.wasUsed(false, false, 0L, 0L));
    }

    @Test
    public void sessionDefaultStartTimeAloneDoesNotProveUsage() {
        // 新病例会给每个会话项预置病例开始时间；没有开关沿/时长就必须保持未使用。
        assertFalse(TreatmentUsage.wasSessionUsed(false, 0L, 0L));
        assertTrue(TreatmentUsage.wasSessionUsed(false, 0L, 9000L));
        assertEquals("9秒", TreatmentUsage.formatUsage(9000L));
        assertTrue(TreatmentUsage.wasSessionUsed(true, 1000L, 0L));
    }

    @Test
    public void checkboxAndDurationRemainDistinctSemantics() {
        // 勾选表示「真实开启过」，时长只属于会话类统计项；两者不能互相伪造。
        boolean checked = TreatmentUsage.wasUsed(true, false, 0L, 0L);
        String duration = TreatmentUsage.durationOrPlaceholder(TreatmentUsage.formatUsage(0L));
        assertTrue(checked);
        assertEquals("--", duration);
        assertTrue(TreatmentUsage.wasUsed(false, false, 0L, 5000L));
        assertEquals("5秒", TreatmentUsage.durationOrPlaceholder(TreatmentUsage.formatUsage(5000L)));
    }
}
