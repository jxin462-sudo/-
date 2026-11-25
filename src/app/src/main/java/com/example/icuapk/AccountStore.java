package com.example.icuapk;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * 本地账号库：注册、登录、退出。
 *
 * 账号属于整台设备上的操作者，和左右舱的数据隔离无关：
 * 换舱、换宠物都不该把人登出去，所以账号不挂在按舱的键上。
 * 口令只保存加盐后的 SHA-256，SharedPreferences 里不留明文。
 */
final class AccountStore {

    /** 账号库独立存放，不参与左右舱隔离。 */
    private static final String PREFS_NAME = "icu_account_store";
    private static final String KEY_ACCOUNTS = "accounts";
    private static final String KEY_CURRENT = "current";
    /** 固定盐：本地登录只需保证磁盘上没有明文口令。 */
    private static final String SALT = "icu-vet-monitor-2026";

    /** 账号操作结果：ok 表示成功，message 是给使用者看的提示。 */
    static final class Result {
        final boolean ok;
        final String message;

        private Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        static Result success(String message) {
            return new Result(true, message);
        }

        static Result failure(String message) {
            return new Result(false, message);
        }
    }

    private AccountStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 当前登录账号，空串表示尚未登录。 */
    static String currentUser(Context context) {
        return prefs(context).getString(KEY_CURRENT, "");
    }

    static boolean isLoggedIn(Context context) {
        return !TextUtils.isEmpty(currentUser(context));
    }

    /** 退出登录：只清当前账号，账号本身保留，下次还能登录回来。 */
    static void logout(Context context) {
        prefs(context).edit().remove(KEY_CURRENT).apply();
    }

    /** 注册新账号：账号名唯一，口令至少 4 位。 */
    static Result register(Context context, String userName, String password) {
        return register(context, userName, password, "");
    }

    /**
     * 注册新账号并记录所属机构。
     * 机构是原版注册表单里的可选项，留空也能注册。
     */
    static Result register(Context context, String userName, String password, String organization) {
        String name = userName == null ? "" : userName.trim();
        if (name.length() < 2) {
            return Result.failure("账号至少 2 个字符");
        }
        if (name.length() > 20) {
            return Result.failure("账号最多 20 个字符");
        }
        if (TextUtils.isEmpty(password) || password.length() < 4) {
            return Result.failure("口令至少 4 位");
        }
        SharedPreferences store = prefs(context);
        JSONArray accounts = readAccounts(store);
        for (int index = 0; index < accounts.length(); index++) {
            JSONObject item = accounts.optJSONObject(index);
            if (item != null && name.equals(item.optString("name", ""))) {
                return Result.failure("账号 " + name + " 已存在");
            }
        }
        JSONObject created = new JSONObject();
        try {
            created.put("name", name);
            created.put("hash", hash(name, password));
            created.put("organization", organization == null ? "" : organization.trim());
            created.put("createdAt", System.currentTimeMillis());
        } catch (Exception exception) {
            return Result.failure("注册失败：" + exception.getMessage());
        }
        accounts.put(created);
        store.edit().putString(KEY_ACCOUNTS, accounts.toString()).apply();
        return Result.success("注册成功");
    }

    /** 账号所属机构：注册时填写，可留空。 */
    static String organizationOf(Context context, String userName) {
        String name = userName == null ? "" : userName.trim();
        if (name.isEmpty()) {
            return "";
        }
        JSONArray accounts = readAccounts(prefs(context));
        for (int index = 0; index < accounts.length(); index++) {
            JSONObject item = accounts.optJSONObject(index);
            if (item != null && name.equals(item.optString("name", ""))) {
                return item.optString("organization", "");
            }
        }
        return "";
    }

    /** 登录：校验账号与口令，成功后记住当前账号。 */
    static Result login(Context context, String userName, String password) {
        String name = userName == null ? "" : userName.trim();
        if (TextUtils.isEmpty(name)) {
            return Result.failure("请输入账号");
        }
        if (TextUtils.isEmpty(password)) {
            return Result.failure("请输入口令");
        }
        SharedPreferences store = prefs(context);
        JSONArray accounts = readAccounts(store);
        for (int index = 0; index < accounts.length(); index++) {
            JSONObject item = accounts.optJSONObject(index);
            if (item == null || !name.equals(item.optString("name", ""))) {
                continue;
            }
            if (!hash(name, password).equals(item.optString("hash", ""))) {
                return Result.failure("账号或口令不正确");
            }
            store.edit().putString(KEY_CURRENT, name).apply();
            return Result.success("登录成功");
        }
        return Result.failure("账号或口令不正确");
    }

    /** 账号是否已存在，注册页做重名提示用。 */
    static boolean exists(Context context, String userName) {
        String name = userName == null ? "" : userName.trim();
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        JSONArray accounts = readAccounts(prefs(context));
        for (int index = 0; index < accounts.length(); index++) {
            JSONObject item = accounts.optJSONObject(index);
            if (item != null && name.equals(item.optString("name", ""))) {
                return true;
            }
        }
        return false;
    }

    private static JSONArray readAccounts(SharedPreferences store) {
        String raw = store.getString(KEY_ACCOUNTS, "");
        if (TextUtils.isEmpty(raw)) {
            return new JSONArray();
        }
        try {
            JSONArray parsed = new JSONArray(raw);
            return parsed == null ? new JSONArray() : parsed;
        } catch (Exception exception) {
            // 账号库损坏时按空库处理：宁可让使用者重新注册，也不要让 App 起不来。
            return new JSONArray();
        }
    }

    /** 口令摘要：加盐 SHA-256，磁盘上不出现明文。 */
    private static String hash(String userName, String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] data = digest.digest((SALT + "|" + userName + "|" + password)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(data.length * 2);
            for (byte value : data) {
                builder.append(String.format(Locale.US, "%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            return "";
        }
    }
}

