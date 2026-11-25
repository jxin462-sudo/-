package com.example.icuapk;

import org.json.JSONException;
import org.json.JSONObject;

/** 护理模式四选一及切换配置合并；只描述 App 选择，不代表主机已经执行。 */
final class HostModeSelectionPolicy {
    private HostModeSelectionPolicy() {
    }

    /** 旧版可能同时保存多个 enabled；界面优先跟主机确认值，再跟最近选择，最后取首个。 */
    static int visibleEnabledIndex(boolean[] enabled, int confirmedIndex, int selectedIndex) {
        if (enabled == null || enabled.length == 0) {
            return -1;
        }
        if (isEnabled(enabled, confirmedIndex)) {
            return confirmedIndex;
        }
        if (isEnabled(enabled, selectedIndex)) {
            return selectedIndex;
        }
        for (int i = 0; i < enabled.length; i++) {
            if (enabled[i]) {
                return i;
            }
        }
        return -1;
    }

    /** 只有完整入队的开启操作才保留选中项；关闭或失败均不展示多个开启模式。 */
    static int completedEnabledIndex(int selectedIndex, boolean enabling, boolean complete) {
        return enabling && complete && selectedIndex >= 0 ? selectedIndex : -1;
    }

    /**
     * 新模式配置优先；旧开启模式独有、且新模式未声明的开关必须关闭。
     * 同一个开关只下发新模式的最终值，不会先开再被旧模式关掉。
     */
    static JSONObject mergeActivationConfig(JSONObject selected, JSONObject[] activeConfigs,
                                            String[] switchKeys) throws JSONException {
        JSONObject merged = selected == null ? new JSONObject() : new JSONObject(selected.toString());
        if (activeConfigs == null || switchKeys == null) {
            return merged;
        }
        for (JSONObject active : activeConfigs) {
            if (active == null) {
                continue;
            }
            for (String key : switchKeys) {
                if (key == null || key.length() == 0 || !active.has(key) || active.isNull(key)) {
                    continue;
                }
                if (!merged.has(key) || merged.isNull(key)) {
                    merged.put(key, false);
                }
            }
        }
        return merged;
    }

    private static boolean isEnabled(boolean[] enabled, int index) {
        return index >= 0 && index < enabled.length && enabled[index];
    }
}
