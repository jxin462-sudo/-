package com.example.icuapk;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * 数据作用域：把"设备共用"和"账号私有"两类数据分开存放。
 *
 * 设备级数据（仪器状态、报警音、摄像头地址、打印机配置）留在共享存档里，所有账号共用；
 * 账号级数据（病例、治疗记录、机构、护理模式、监护门限）按账号分文件存放，
 * 换一个账号登录就是另一套数据，账号之间互相看不到对方的病历。
 *
 * 左右舱的隔离是另一层：舱位写在键名里，账号写在文件名里，两者互不干扰。
 */
final class AccountScope {

    /** 设备级存档：沿用升级前的 icu_dashboard，不动它就不会丢老数据。 */
    private static final String DEVICE_PREFS = "icu_dashboard";
    /** 账号级存档前缀，完整名字是 icu_dashboard_data_<作用域标识>。 */
    private static final String DATA_PREFS_PREFIX = "icu_dashboard_data_";
    /** 未登录时的作用域：看不到任何账号的数据。 */
    private static final String GUEST_SCOPE = "guest_scope";
    /**
     * 旧版本的数据没有分账号，只允许被一个账号认领一次。
     * 认领之后其余账号拿到的是自己的一份空数据，账号之间才是真隔离。
     */
    private static final String KEY_LEGACY_CLAIMED = "account_scope_legacy_claimed";

    private AccountScope() {
    }

    /** 设备级存档：仪器状态、报警音、摄像头地址等，所有账号共用同一份。 */
    static SharedPreferences devicePrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 账号级存档：账号名决定用哪个文件。
     * 未登录时落到访客作用域，看不到任何账号的数据。
     */
    static SharedPreferences dataPrefs(Context context, String accountName) {
        return context.getApplicationContext()
                .getSharedPreferences(DATA_PREFS_PREFIX + scopeKey(accountName), Context.MODE_PRIVATE);
    }

    /**
     * 作用域标识：账号名规整成文件名安全的短串。
     * 账号名可能是中文，规整后容易撞车，所以再补一段名字摘要保证不同账号互不覆盖。
     */
    static String scopeKey(String accountName) {
        String name = accountName == null ? "" : accountName.trim();
        if (TextUtils.isEmpty(name)) {
            return GUEST_SCOPE;
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < name.length(); index++) {
            char ch = name.charAt(index);
            builder.append(ch < 128 && Character.isLetterOrDigit(ch) ? ch : '_');
        }
        String safe = builder.length() > 24 ? builder.substring(0, 24) : builder.toString();
        return safe + "_" + Integer.toHexString(name.hashCode());
    }

    /** 旧版本的共享数据是否已经被某个账号认领。 */
    static boolean isLegacyClaimed(Context context) {
        return devicePrefs(context).getBoolean(KEY_LEGACY_CLAIMED, false);
    }

    /** 标记旧版本的共享数据已被认领：之后的账号从零开始。 */
    static void markLegacyClaimed(Context context) {
        devicePrefs(context).edit().putBoolean(KEY_LEGACY_CLAIMED, true).apply();
    }
}
