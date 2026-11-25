package com.example.icuapk;

/**
 * 蓝牙扫描列表的展示顺序。
 *
 * <p>主机（EM 系列）和监护仪（AM 系列）才是操作者真正要连的设备，
 * 名称以 EM / AM 开头的信号统一排到列表前面，其余设备保持发现顺序。</p>
 *
 * <p>这里只负责判定分组，不做排序：信号强度每次扫描都会抖动，
 * 若按 RSSI 排序，正在找的设备会来回跳。调用方把两组按原顺序拼接即可，
 * 这样组内顺序完全稳定。</p>
 */
public final class DeviceNameOrder {

    private DeviceNameOrder() {
    }

    /** 名称以 EM / AM 开头（忽略大小写与前导空格）的设备优先显示。 */
    public static boolean isPreferred(String name) {
        String text = name == null ? "" : name.trim();
        return text.regionMatches(true, 0, "EM", 0, 2)
                || text.regionMatches(true, 0, "AM", 0, 2);
    }

    /** 0 = 目标设备（排最前），1 = 其它设备；留给需要扩展分组的调用方。 */
    public static int rank(String name) {
        return isPreferred(name) ? 0 : 1;
    }
}
