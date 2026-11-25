package com.example.icuapk;

/**
 * 治疗记录「真实使用时长」的纯计算，不依赖 Android 框架，可在 JVM 单元测试里直接验证。
 *
 * 三套语义互不替代，不能用演示值或统一勾选互相顶替：
 *  - 实际开启：开关沿捕获的 featureOn / featureOnSince / totalOnMs；
 *  - 纳入统计：调用方按 isValidTreatmentSample 过滤后才写入样本；
 *  - 录入报告：真实开启过即可勾选，时长按真实累计区间展示。
 */
final class TreatmentUsage {

    private TreatmentUsage() {
    }

    /**
     * 折入「仍在开启」的活动区间后的有效累计毫秒数。
     * 纯函数：不修改入参、不读系统时钟，结束结算与页面展示共用同一口径，避免重复计入。
     */
    static long effectiveOnMs(long totalOnMs, boolean featureOn, long featureOnSince, long now) {
        if (!featureOn || featureOnSince <= 0L) {
            return totalOnMs;
        }
        return totalOnMs + Math.max(0L, now - featureOnSince);
    }

    /** 真实时长文案：0 毫秒（从未开启）返回空串；不足一分钟按真实秒数，不伪报成一分钟。 */
    static String formatUsage(long ms) {
        if (ms <= 0L) {
            return "";
        }
        if (ms < 60000L) {
            long seconds = Math.max(1L, Math.round(ms / 1000.0));
            return seconds + "秒";
        }
        long minutes = Math.round(ms / 60000.0);
        if (minutes >= 60L) {
            long hours = minutes / 60L;
            long rest = minutes % 60L;
            return rest > 0L ? hours + "小时" + rest + "分钟" : hours + "小时";
        }
        return minutes + "分钟";
    }

    /** 报告用时列：有真实时长就显示，从未使用过仍保留 "--" 占位，不伪造记录。 */
    static String durationOrPlaceholder(String usage) {
        return usage == null || usage.length() == 0 ? "--" : usage;
    }

    /** 当前病例是否真实使用过该治疗项：任一真实痕迹成立即可，不看此刻全局设备开关。 */
    static boolean wasUsed(boolean hasStartTime, boolean featureOn, long featureOnSince, long totalOnMs) {
        return hasStartTime || featureOn || featureOnSince > 0L || totalOnMs > 0L;
    }

    /** 会话项不看病例预置的默认开始时间，只认真实开关沿和累计时长。 */
    static boolean wasSessionUsed(boolean featureOn, long featureOnSince, long totalOnMs) {
        return wasUsed(false, featureOn, featureOnSince, totalOnMs);
    }
}
