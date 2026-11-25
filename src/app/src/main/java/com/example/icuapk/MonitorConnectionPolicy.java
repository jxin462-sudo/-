package com.example.icuapk;

/** 监护仪重连的显示值保留与报警新鲜度规则；不依赖 Android，便于 JVM 回归。 */
final class MonitorConnectionPolicy {

    private MonitorConnectionPolicy() {
    }

    static boolean shouldClearDisplayValues(String previousDeviceId, String nextDeviceId) {
        return nextDeviceId == null || nextDeviceId.length() == 0
                || !nextDeviceId.equals(previousDeviceId);
    }

    static boolean isParamFresh(boolean connected, boolean protocolReady, int param,
                                long lastReadyAtMs, long lastValidAtMs) {
        return connected && protocolReady
                && param >= Am4100Manager.PARAM_HEART_RATE && param <= Am4100Manager.PARAM_RESP
                && lastReadyAtMs > 0L && lastValidAtMs >= lastReadyAtMs;
    }
}
