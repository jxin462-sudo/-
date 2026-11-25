package com.example.icuapk;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * 打印机设置：打印机地址与打印份数。
 *
 * 配置存在独立的 SharedPreferences 里，不参与左右舱的数据隔离：
 * 一台物理打印机由整机共用，地址与份数属于设备级设置，切换舱区不需要重来。
 */
final class PrinterSettings {

    /** 直连打印：按地址用 IPP 协议把报告直接发给网络打印机。 */
    static final String MODE_DIRECT = "direct";

    /** 份数上限：再多只会占满打印队列，临床场景没有意义。 */
    static final int MAX_COPIES = 5;

    private static final String PREFS_NAME = "icu_printer_settings";
    private static final String KEY_ADDRESS = "printer_address";
    private static final String KEY_COPIES = "printer_copies";

    /** 交付现场这台打印机的地址：HP LaserJet Pro M1213nf，装好驱动后直接可用。 */
    private static final String DEFAULT_ADDRESS = "10.2.34.201";

    private String address = "";
    private int copies = 1;

    /** 读取本机保存的打印设置；未配置过时返回现场那台打印机、单份。 */
    static PrinterSettings load(Context context) {
        PrinterSettings settings = new PrinterSettings();
        if (context == null) {
            return settings;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        settings.address = prefs.getString(KEY_ADDRESS, DEFAULT_ADDRESS);
        settings.copies = clampCopies(prefs.getInt(KEY_COPIES, 1));
        return settings;
    }

    /** 保存设置。地址会去掉首尾空格，避免复制粘贴带进隐藏字符。 */
    void save(Context context) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ADDRESS, address == null ? "" : address.trim())
                .putInt(KEY_COPIES, copies)
                .apply();
    }

    String getAddress() {
        return address == null ? "" : address;
    }

    void setAddress(String value) {
        address = value == null ? "" : value.trim();
    }

    int getCopies() {
        return copies;
    }

    void setCopies(int value) {
        copies = clampCopies(value);
    }

    /** 份数越界时收敛到 1~MAX_COPIES。 */
    static int clampCopies(int value) {
        if (value < 1) {
            return 1;
        }
        return Math.min(value, MAX_COPIES);
    }

    /** 当前打印目标的文字描述，用于告诉操作者这份报告会打到哪台机器。 */
    String describe() {
        return TextUtils.isEmpty(address) ? "直连（未设置地址）" : "直连 " + address;
    }
}
