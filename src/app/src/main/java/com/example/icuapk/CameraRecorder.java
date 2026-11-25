package com.example.icuapk;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 摄像监控页的录像器。
 * <p>
 * RTSP 客户端收到的本来就是 H.264 编码流（Annex-B 访问单元），
 * 这里把每个访问单元直接转成 MP4 样本落盘，不做二次编码，
 * 画面没有损失，也不会额外占用 CPU。
 * <p>
 * MP4 必须从关键帧开始才能正常播放，所以首个关键帧之前的数据会被丢掉；
 * 同一个时间戳只允许出现一次，写入时会强制保持严格递增。
 */
final class CameraRecorder {

    /** 关键帧间隔按 30fps 估算，时间戳冲突时用它往后顶。 */
    private static final long FRAME_INTERVAL_US = 33333L;

    /** Annex-B 起始码，和 RTSP 客户端里保持一致。 */
    private static final byte[] START_CODE = new byte[]{0, 0, 0, 1};

    private final File output;
    private MediaMuxer muxer;
    private int trackIndex = -1;
    private boolean muxerStarted;
    private long basePtsUs = -1L;
    private long lastPtsUs = -1L;
    private boolean written;

    CameraRecorder(File output) {
        this.output = output;
    }

    File getOutput() {
        return output;
    }

    /** 是否已经写进了有效画面（决定这个录像文件能不能用）。 */
    boolean hasContent() {
        return written;
    }

    boolean isStarted() {
        return muxerStarted;
    }

    /**
     * 写入一个访问单元。
     *
     * @param annexBAccessUnit 完整的 Annex-B 访问单元（关键帧会带上 SPS/PPS）
     * @param keyFrame         是否是关键帧
     * @param sps              序列参数集
     * @param pps              图片参数集
     * @param width            视频宽度
     * @param height           视频高度
     * @param sourcePtsUs      源时间戳，只用来换算成从 0 开始的播放时间
     */
    synchronized void writeAccessUnit(byte[] annexBAccessUnit, boolean keyFrame, byte[] sps, byte[] pps,
                                      int width, int height, long sourcePtsUs) throws IOException {
        if (annexBAccessUnit == null || annexBAccessUnit.length == 0) {
            return;
        }
        if (!muxerStarted) {
            if (!keyFrame || sps == null || pps == null || width <= 0 || height <= 0) {
                // 等到第一个关键帧再开轨道，中间的画面丢掉也不影响播放
                return;
            }
            startMuxer(sps, pps, width, height);
            basePtsUs = sourcePtsUs;
        }
        byte[] sample = buildAvccSample(annexBAccessUnit);
        if (sample == null) {
            return;
        }
        long ptsUs = sourcePtsUs - basePtsUs;
        if (ptsUs < 0) {
            ptsUs = 0;
        }
        if (lastPtsUs >= 0 && ptsUs <= lastPtsUs) {
            ptsUs = lastPtsUs + FRAME_INTERVAL_US;
        }
        lastPtsUs = ptsUs;

        ByteBuffer buffer = ByteBuffer.allocateDirect(sample.length);
        buffer.put(sample);
        buffer.position(0);
        buffer.limit(sample.length);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, sample.length, ptsUs, keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        muxer.writeSampleData(trackIndex, buffer, info);
        written = true;
    }

    /** 结束录像并释放封装器；没有写入任何画面时删除空文件。 */
    synchronized void stop() {
        if (muxer != null) {
            try {
                if (muxerStarted && written) {
                    muxer.stop();
                }
            } catch (IllegalStateException exception) {
                // 封装失败时保留已经写下的字节，交由上层判断文件是否可用
            }
            try {
                muxer.release();
            } catch (Exception ignored) {
            }
            muxer = null;
            muxerStarted = false;
            trackIndex = -1;
        }
        if (!written && output.exists()) {
            output.delete();
        }
    }

    private void startMuxer(byte[] sps, byte[] pps, int width, int height) throws IOException {
        muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(annexB(sps)));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(annexB(pps)));
        trackIndex = muxer.addTrack(format);
        muxer.start();
        muxerStarted = true;
    }

    private static byte[] annexB(byte[] data) {
        byte[] sample = new byte[START_CODE.length + data.length];
        System.arraycopy(START_CODE, 0, sample, 0, START_CODE.length);
        System.arraycopy(data, 0, sample, START_CODE.length, data.length);
        return sample;
    }

    /**
     * 把 Annex-B 访问单元转换成 MP4 需要的 AVCC 样本。
     * <p>
     * SPS/PPS 已经放进 csd-0/csd-1，样本里只保留真正的图像切片；
     * SEI、AUD 这些对播放没有作用的数据同样不写进样本。
     */
    private static byte[] buildAvccSample(byte[] accessUnit) {
        List<byte[]> nals = splitNalUnits(accessUnit);
        ByteArrayOutputStream output = new ByteArrayOutputStream(accessUnit.length);
        for (byte[] nal : nals) {
            int type = nal[0] & 0x1F;
            if (type != 1 && type != 5) {
                continue;
            }
            output.write(nal.length >>> 24);
            output.write(nal.length >>> 16);
            output.write(nal.length >>> 8);
            output.write(nal.length);
            output.write(nal, 0, nal.length);
        }
        return output.size() == 0 ? null : output.toByteArray();
    }

    /** 按 4 字节起始码把 Annex-B 数据拆成一个个 NAL 单元。 */
    private static List<byte[]> splitNalUnits(byte[] data) {
        List<byte[]> result = new ArrayList<>();
        int index = 0;
        int length = data.length;
        while (index + 3 < length) {
            if (data[index] == 0 && data[index + 1] == 0 && data[index + 2] == 0 && data[index + 3] == 1) {
                int start = index + 4;
                int end = start;
                while (end + 3 < length && !(data[end] == 0 && data[end + 1] == 0
                        && data[end + 2] == 0 && data[end + 3] == 1)) {
                    end++;
                }
                if (end > start) {
                    byte[] nal = new byte[end - start];
                    System.arraycopy(data, start, nal, 0, nal.length);
                    result.add(nal);
                }
                index = end;
            } else {
                index++;
            }
        }
        return result;
    }
}
