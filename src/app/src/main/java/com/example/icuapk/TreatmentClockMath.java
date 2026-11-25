package com.example.icuapk;

import java.util.Locale;

/**
 * 治疗计时与治疗记录单「治疗时长」的纯计算，不依赖 Android 框架，可在 JVM 单测里直接验证。
 *
 * 两类问题都由这里统一把关，避免原生层和 PDF 层各写一套：
 *  - 设备回读的 run_time 不能把 App 已累计的病例时长改小（后台重启被清零）；
 *  - 治疗时长只能输出 00:00 这类短时长，绝不能混入年月日时间。
 */
final class TreatmentClockMath {

    private TreatmentClockMath() {
    }

    /**
     * 是否采纳主机回读的 run_time。
     * 本地计时还没建立时可以用设备值做初值；一旦建立就只接受"往前推"，
     * 主机重启、回包缺字段（optInt 默认 0）、新会话从 0 报数都不能把时长清零。
     */
    static boolean shouldAdoptRunTime(boolean hasLocalClock, int liveMinutes, int deviceMinutes) {
        if (deviceMinutes < 0) {
            return false;
        }
        if (!hasLocalClock) {
            return true;
        }
        return deviceMinutes > liveMinutes;
    }

    /**
     * 把毫秒折算成治疗记录单用的短时长文本。
     * 固定 00:00 格式，长度稳定，PDF 的 pdfTextFit 不会为了塞进列宽自动缩小字号。
     */
    static String durationOnly(long durationMs) {
        if (durationMs <= 0L) {
            return "--";
        }
        long minutes = (durationMs + 30000L) / 60000L;
        return String.format(Locale.US, "%02d:%02d", minutes / 60L, minutes % 60L);
    }
}
