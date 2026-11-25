package com.example.icuapk;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * C305 字节分片的可复位重组状态机。
 *
 * <p>缓冲始终以候选 JSON 起点开始；纯垃圾立即丢弃，损坏前缀让位给后续完整帧，
 * 长时间不能收尾或超过硬上限时彻底复位。任何情况下都不伪造主机状态。</p>
 */
final class HostNotifyReassembler {
    static final long DEFAULT_FRAME_TIMEOUT_MS = 3000L;

    private final StringBuilder buffer = new StringBuilder();
    private final int maxBufferChars;
    private final long frameTimeoutMs;
    private long bufferStartedAtMs = -1L;

    static final class Result {
        final ArrayList<String> frames = new ArrayList<>();
        int discardedFrameCount;
        int resetCount;
        String lastDropReason = "";
    }

    HostNotifyReassembler(int maxBufferChars, long frameTimeoutMs) {
        if (maxBufferChars <= 0) {
            throw new IllegalArgumentException("maxBufferChars must be positive");
        }
        this.maxBufferChars = maxBufferChars;
        this.frameTimeoutMs = frameTimeoutMs;
    }

    synchronized Result append(String chunk, long nowMs) {
        Result result = new Result();
        if (chunk == null || chunk.length() == 0) {
            return result;
        }
        if (buffer.length() == 0) {
            bufferStartedAtMs = nowMs;
        }
        buffer.append(chunk);
        while (buffer.length() > 0) {
            int start = buffer.indexOf("{");
            if (start < 0) {
                // '{' 是单字节 ASCII，不可能跨两个 UTF-8 分片；没有起点就没有可保留的半帧。
                clearBuffer(result, "no_json_start");
                break;
            }
            if (start > 0) {
                // 前导噪声不是任何 JSON 的一部分，不应跟着残缺帧一起长到溢出。
                buffer.delete(0, start);
                result.discardedFrameCount++;
                result.lastDropReason = "prefix_noise";
            }

            int end = HostNotifyFrameScanner.findFrameEnd(buffer, 0);
            if (end > 0) {
                String frame = buffer.substring(0, end + 1);
                if (isHostFrame(frame)) {
                    result.frames.add(frame);
                    buffer.delete(0, end + 1);
                    bufferStartedAtMs = buffer.length() == 0 ? -1L : nowMs;
                    continue;
                }
                // 只放弃这个坏起点，内层或后续起点仍有独立恢复的机会。
                buffer.delete(0, 1);
                result.discardedFrameCount++;
                result.lastDropReason = "invalid_fragment";
                if (buffer.length() == 0) {
                    bufferStartedAtMs = -1L;
                }
                continue;
            }

            int next = HostNotifyFrameScanner.findNextCompleteFrame(buffer, 0);
            if (next > 0) {
                buffer.delete(0, next);
                result.discardedFrameCount++;
                result.lastDropReason = "broken_half_frame";
                bufferStartedAtMs = nowMs;
                continue;
            }
            if (buffer.length() > maxBufferChars) {
                clearBuffer(result, "buffer_overflow");
                break;
            }
            if (frameTimeoutMs >= 0L && bufferStartedAtMs >= 0L
                    && nowMs - bufferStartedAtMs >= frameTimeoutMs) {
                // 已经等待多片仍不能形成任何完整 JSON，不能让旧前缀污染后续连接周期。
                clearBuffer(result, "frame_timeout");
                break;
            }
            break;
        }
        return result;
    }

    synchronized int getBufferLength() {
        return buffer.length();
    }

    synchronized void reset() {
        buffer.setLength(0);
        bufferStartedAtMs = -1L;
    }

    private void clearBuffer(Result result, String reason) {
        if (buffer.length() > 0) {
            result.discardedFrameCount++;
            result.resetCount++;
        }
        buffer.setLength(0);
        bufferStartedAtMs = -1L;
        result.lastDropReason = reason;
    }

    private static boolean isHostFrame(String frame) {
        try {
            return new JSONObject(frame).length() > 0;
        } catch (JSONException ignored) {
            return false;
        }
    }
}
