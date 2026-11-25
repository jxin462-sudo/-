package com.example.icuapk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 治疗计时恢复与治疗记录单「治疗时长」格式的 JVM 回归。
 * 覆盖：设备 run_time 回读不得清零、进程重启后时长不倒退、时长文本不能混入日期。
 */
public class TreatmentClockMathTest {

    @Test
    public void deviceRunTimeCannotResetAnActiveCase() {
        // 平板从后台划掉 App 再重启，主机回读 run_time=0（或回包缺 value）：
        // 已累计 45 分钟的正在治疗样本必须保持 00:45。
        assertFalse(TreatmentClockMath.shouldAdoptRunTime(true, 45, 0));
        assertFalse(TreatmentClockMath.shouldAdoptRunTime(true, 45, 12));
        assertFalse(TreatmentClockMath.shouldAdoptRunTime(true, 45, 45));
    }

    @Test
    public void deviceRunTimeCanOnlyMoveAnActiveCaseForward() {
        assertTrue(TreatmentClockMath.shouldAdoptRunTime(true, 45, 46));
        assertTrue(TreatmentClockMath.shouldAdoptRunTime(true, 45, 90));
    }

    @Test
    public void firstRunTimeReadingSeedsAFreshCase() {
        // 本地计时还没建立时，设备值可以作为初值；0 也允许（新会话从 00:00 起）。
        assertTrue(TreatmentClockMath.shouldAdoptRunTime(false, 0, 0));
        assertTrue(TreatmentClockMath.shouldAdoptRunTime(false, 0, 30));
    }

    @Test
    public void invalidDeviceReadingIsAlwaysIgnored() {
        assertFalse(TreatmentClockMath.shouldAdoptRunTime(false, 0, -1));
        assertFalse(TreatmentClockMath.shouldAdoptRunTime(true, 45, -1));
    }

    @Test
    public void durationOnlyNeverContainsDateOrTimeWords() {
        assertEquals("--", TreatmentClockMath.durationOnly(0L));
        assertEquals("--", TreatmentClockMath.durationOnly(-1L));
        String value = TreatmentClockMath.durationOnly(45 * 60000L);
        assertEquals("00:45", value);
        assertFalse(value.contains("-"));
        assertFalse(value.contains("\u5e74"));
        assertFalse(value.contains("\u6708"));
        assertFalse(value.contains("\u65e5"));
    }

    @Test
    public void durationOnlyIsShortEnoughForPdfColumn() {
        // 9.5f 字号、48px 列宽：值必须稳定在 5 个字符，pdfTextFit 才不会自动缩字号。
        assertEquals(5, TreatmentClockMath.durationOnly(45 * 60000L).length());
        assertEquals(5, TreatmentClockMath.durationOnly(125 * 60000L).length());
        assertEquals("02:05", TreatmentClockMath.durationOnly(125 * 60000L));
    }

    @Test
    public void durationOnlyRoundsHalfMinuteUp() {
        assertEquals("00:00", TreatmentClockMath.durationOnly(1L));
        assertEquals("00:00", TreatmentClockMath.durationOnly(29999L));
        assertEquals("00:01", TreatmentClockMath.durationOnly(30000L));
        assertEquals("00:01", TreatmentClockMath.durationOnly(60000L));
    }

    @Test
    public void durationOnlyHandlesLongTreatment() {
        assertEquals("10:00", TreatmentClockMath.durationOnly(600 * 60000L));
        assertEquals("25:30", TreatmentClockMath.durationOnly(1530 * 60000L));
    }
}
