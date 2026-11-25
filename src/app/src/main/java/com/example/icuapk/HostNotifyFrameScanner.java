package com.example.icuapk;

import org.json.JSONException;
import org.json.JSONObject;

/** 主机 Notify 缓冲区的纯 JSON 扫描逻辑，便于用 JVM 回归损坏分片恢复。 */
final class HostNotifyFrameScanner {
    private HostNotifyFrameScanner() {
    }

    /** 在前半帧损坏后寻找可以独立解析的后续主机帧。 */
    static int findNextCompleteFrame(StringBuilder buffer, int start) {
        int next = buffer.indexOf("{", start + 1);
        while (next > start) {
            int nextEnd = findFrameEnd(buffer, next);
            if (nextEnd > next && isHostFrame(buffer.substring(next, nextEnd + 1))) {
                return next;
            }
            // 不能只试第一个后续起点：损坏外层的 data 内层也可能未闭合，
            // 更后面已经到齐的总表必须仍有机会被恢复。
            next = buffer.indexOf("{", next + 1);
        }
        return -1;
    }

    /** 字符串里的花括号与转义引号不参与 JSON 深度计算。 */
    static int findFrameEnd(StringBuilder buffer, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < buffer.length(); index++) {
            char current = buffer.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static boolean isHostFrame(String frame) {
        try {
            return new JSONObject(frame).length() > 0;
        } catch (JSONException ignored) {
            return false;
        }
    }
}
