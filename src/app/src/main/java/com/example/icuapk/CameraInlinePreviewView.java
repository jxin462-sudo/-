package com.example.icuapk;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 摄像监控 / ICU 状态页的内嵌预览。
 * <p>
 * 只有真正收到解码帧才把视频显示出来：地址没配、连接失败或者还没出帧时，
 * 整个区域保持全透明，页面自身的内容照常显示，不会残留黑框或报错文字。
 */
final class CameraInlinePreviewView extends FrameLayout {
    private static final String TAG = "IcuCameraInline";

    /** 一轮候选地址的最长尝试时间（毫秒）：超过就停止重试并提示用户。 */
    private static final long ATTEMPT_BUDGET_MS = 25000L;

    private final TextureView textureView;
    private String[] streamUrls = new String[0];
    private String currentKey = "";
    private int streamIndex;
    private boolean candidatesExhausted;
    private int sessionId;
    private boolean active;
    private boolean surfaceReady;
    private boolean videoVisible;
    private int videoWidth;
    private int videoHeight;
    private Surface previewSurface;
    private Bitmap lastFrameBitmap;
    private CameraStreamActivity.RtspH264Client rtspClient;
    private CameraRecorder recorder;
    private final TextView statusText;
    /** 一轮候选地址尝试的开始时间，用于限制总耗时。 */
    private long attemptStartedAt;

    CameraInlinePreviewView(MainActivity activity) {
        super(activity);
        setVisibility(View.GONE);
        setClickable(false);

        textureView = new TextureView(activity);
        textureView.setKeepScreenOn(true);
        textureView.setAlpha(0f);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                surfaceReady = true;
                previewSurface = new Surface(surface);
                applyVideoLayout();
                maybeStart();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                surfaceReady = false;
                stopPreview();
                releasePreviewSurface();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                // 画面真正送到 Surface 才显示，失败时保持透明
                showVideo();
            }
        });
        addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // 连接过程和失败原因要看得见：没有提示时页面只是黑着，用户分不清是还在连接还是已经失败。
        statusText = new TextView(activity);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(13);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(dp(12), dp(8), dp(12), dp(8));
        statusText.setBackgroundColor(Color.argb(170, 18, 22, 28));
        statusText.setVisibility(View.GONE);
        addView(statusText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * 让画面按原始比例铺满取景框。
     * <p>
     * TextureView 只会把视频按比例缩放后放进取景区，四周一定会有留边。这里把取景区
     * 放大成能盖住整个取景框的等比尺寸再居中，多出来的部分由外层裁掉，
     * 这样画面既不会被拉变形，也不会有黑边残留。
     */
    private void applyVideoLayout() {
        int parentWidth = getWidth();
        int parentHeight = getHeight();
        if (parentWidth <= 0 || parentHeight <= 0) {
            return;
        }
        int targetWidth = parentWidth;
        int targetHeight = parentHeight;
        if (videoWidth > 0 && videoHeight > 0) {
            float scale = Math.max(parentWidth / (float) videoWidth, parentHeight / (float) videoHeight);
            targetWidth = Math.round(videoWidth * scale);
            targetHeight = Math.round(videoHeight * scale);
        }
        int leftMargin = (parentWidth - targetWidth) / 2;
        int topMargin = (parentHeight - targetHeight) / 2;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) textureView.getLayoutParams();
        if (params.width == targetWidth && params.height == targetHeight
                && params.leftMargin == leftMargin && params.topMargin == topMargin) {
            return;
        }
        params.width = targetWidth;
        params.height = targetHeight;
        params.leftMargin = leftMargin;
        params.topMargin = topMargin;
        textureView.setLayoutParams(params);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        applyVideoLayout();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // 取景区比取景框大时，把超出取景框的部分裁掉，避免压住页面上的其它控件
        canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());
        super.dispatchDraw(canvas);
        canvas.restore();
    }

    void setPreview(String[] urls) {
        String[] cleaned = sanitizeUrls(urls);
        String nextKey = join(cleaned);
        if (nextKey.equals(currentKey)) {
            return;
        }
        Log.i(TAG, "setPreview new key, urls=" + cleaned.length);
        currentKey = nextKey;
        streamUrls = cleaned;
        resetCandidates();
        hideVideo();
        stopPreview();
        maybeStart();
    }

    /**
     * 重新配置摄像头地址后强制重连：即使新地址和上次完全一样也要重新试，
     * 否则连接失败之后用户改完地址内嵌预览不会重新连接。
     */
    void restartPreview(String[] urls) {
        String[] cleaned = sanitizeUrls(urls);
        currentKey = join(cleaned);
        streamUrls = cleaned;
        resetCandidates();
        hideVideo();
        stopPreview();
        maybeStart();
    }

    void setActive(boolean active) {
        if (this.active == active) {
            return;
        }
        Log.i(TAG, "setActive " + active);
        this.active = active;
        setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) {
            bringToFront();
            hideVideo();
            maybeStart();
        } else {
            cacheCurrentFrame();
            hideVideo();
            stopPreview();
            // 离开页面后允许重新开始一轮连接，重新进入时若摄像头已恢复可直接出画面
            resetCandidates();
        }
    }

    void captureFrame(IcuDashboardView.CameraFrameCallback callback) {
        Bitmap bitmap = null;
        if (videoVisible && textureView.isAvailable() && getWidth() > 0 && getHeight() > 0) {
            bitmap = textureView.getBitmap();
        }
        if (bitmap == null && lastFrameBitmap != null && !lastFrameBitmap.isRecycled()) {
            bitmap = lastFrameBitmap.copy(Bitmap.Config.ARGB_8888, false);
        }
        if (bitmap == null) {
            callback.onError("还没有可截图的摄像头画面，请先停留在实况预览几秒");
            return;
        }
        callback.onFrame(cropToVisibleArea(bitmap));
    }

    /**
     * 截图只保留取景框里露出来的那部分，和屏幕上看到的一致。
     */
    private Bitmap cropToVisibleArea(Bitmap bitmap) {
        int visibleWidth = Math.min(getWidth(), bitmap.getWidth());
        int visibleHeight = Math.min(getHeight(), bitmap.getHeight());
        if (visibleWidth <= 0 || visibleHeight <= 0) {
            return bitmap;
        }
        int offsetX = (bitmap.getWidth() - visibleWidth) / 2;
        int offsetY = (bitmap.getHeight() - visibleHeight) / 2;
        if (offsetX <= 0 && offsetY <= 0
                && visibleWidth == bitmap.getWidth() && visibleHeight == bitmap.getHeight()) {
            return bitmap;
        }
        Bitmap cropped = Bitmap.createBitmap(bitmap, offsetX, offsetY, visibleWidth, visibleHeight);
        if (cropped != bitmap) {
            bitmap.recycle();
        }
        return cropped;
    }

    /**
     * 开始录像。
     * <p>
     * 录像器直接挂在当前的 RTSP 连接上：收到的本来就是 H.264 编码流，
     * 直接封装成 MP4 落盘即可，不需要二次编码。
     */
    void startRecording(File output) throws IOException {
        if (rtspClient == null) {
            throw new IOException("摄像头尚未连接，请先切到实况并等待画面出现");
        }
        if (recorder != null) {
            stopRecording();
        }
        CameraRecorder target = new CameraRecorder(output);
        rtspClient.setRecorder(target);
        recorder = target;
    }

    /**
     * 结束录像。
     *
     * @return 录像文件；没有录到任何画面时返回 null
     */
    File stopRecording() {
        CameraRecorder target = recorder;
        recorder = null;
        if (target == null) {
            return null;
        }
        if (rtspClient != null) {
            rtspClient.setRecorder(null);
        }
        target.stop();
        return target.hasContent() ? target.getOutput() : null;
    }

    boolean isRecording() {
        return recorder != null;
    }

    void stopPreview() {
        // 作废当前连接的回调，避免旧线程晚到的错误信息打断新连接
        sessionId++;
        if (rtspClient != null) {
            rtspClient.stop();
            rtspClient = null;
        }
    }

    private void maybeStart() {
        if (!active || !surfaceReady || rtspClient != null) {
            return;
        }
        if (streamUrls.length == 0) {
            hideVideo();
            hideStatus();
            return;
        }
        if (candidatesExhausted) {
            // 本轮候选地址已经全部试过且都失败，不再重复连接，区域保持留白
            hideVideo();
            return;
        }
        startPlayback();
    }

    private void startPlayback() {
        if (streamIndex >= streamUrls.length) {
            candidatesExhausted = true;
            hideVideo();
            return;
        }
        if (previewSurface == null || !previewSurface.isValid()) {
            hideVideo();
            return;
        }
        final String url = streamUrls[streamIndex];
        Log.i(TAG, "try url[" + streamIndex + "/" + streamUrls.length + "] " + maskUrl(url));
        hideVideo();
        showStatus("正在连接摄像头 (" + (streamIndex + 1) + "/" + streamUrls.length + ") …");
        stopPreview();
        final int session = sessionId;
        rtspClient = new CameraStreamActivity.RtspH264Client(url, previewSurface, new CameraStreamActivity.RtspH264Client.Listener() {
            @Override
            public void onStatus(final String message) {
                // RTSP 客户端的 onStatus 只报「正在建立RTSP连接 / RTSP已连接 /
                // H264解码器已启动」这类过程信息，而且没有配套的清除消息。显示到取景框里
                // 就会在画面正常播放后变成常驻浮层（用户看到的
                // "H264解码器已启动: 704x704" 就是它）。连接中与失败提示已由
                // startPlayback/onError 单独负责，这里只留日志。
                Log.i(TAG, "preview status: " + message);
            }

            @Override
            public void onVideoSize(final int width, final int height) {
                if (session != sessionId) {
                    return;
                }
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (session != sessionId) {
                            return;
                        }
                        videoWidth = width;
                        videoHeight = height;
                        Log.i(TAG, "video size " + width + "x" + height);
                        applyVideoLayout();
                    }
                });
            }

            @Override
            public void onError(final String message) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (session != sessionId) {
                            return;
                        }
                        Log.w(TAG, "preview failed: " + message);
                        if (!tryNextUrl(message)) {
                            hideVideo();
                            // 所有候选地址都连不上：把原因留在画面上，避免看起来像卡住
                            showStatus("摄像头连接失败：" + message);
                        }
                    }
                });
            }
        });
        if (recorder != null) {
            // 断线重连之后要把录像器挂回新连接，否则录像是断的
            rtspClient.setRecorder(recorder);
        }
        new Thread(rtspClient, "tp-link-inline-rtsp").start();
    }

    private boolean tryNextUrl(String reason) {
        stopPreview();
        if (streamIndex + 1 >= streamUrls.length) {
            candidatesExhausted = true;
            Log.i(TAG, "all candidate urls failed, stop retrying");
            return false;
        }
        // 一轮候选地址的总耗时必须有上限：地址不可达时每个地址都要等一次连接超时，
        // 不设上限的话 9 个候选地址串行下来会一直停在「正在连接」。
        if (SystemClock.elapsedRealtime() - attemptStartedAt > ATTEMPT_BUDGET_MS) {
            candidatesExhausted = true;
            Log.w(TAG, "attempt budget exhausted after " + (streamIndex + 1) + " urls: " + reason);
            return false;
        }
        streamIndex++;
        postDelayed(new Runnable() {
            @Override
            public void run() {
                maybeStart();
            }
        }, 700);
        return true;
    }

    /**
     * 重置候选地址的尝试进度：换地址、重新配置、离开页面后都需要从头再试。
     */
    private void resetCandidates() {
        streamIndex = 0;
        candidatesExhausted = false;
        attemptStartedAt = SystemClock.elapsedRealtime();
    }

    /**
     * 日志里不打印 RTSP 账号密码，只保留主机部分。
     */
    private String maskUrl(String url) {
        if (url == null) {
            return "";
        }
        int schemeEnd = url.indexOf("://");
        // 用户信息段以最后一个 @ 结尾，密码里含 @ 时也不会把后半截漏出去
        int at = url.lastIndexOf('@');
        int colon = schemeEnd >= 0 ? url.indexOf(':', schemeEnd + 3) : -1;
        if (schemeEnd >= 0 && colon > schemeEnd && at > colon) {
            return url.substring(0, colon + 1) + "******" + url.substring(at);
        }
        return url;
    }

    private void showVideo() {
        if (videoVisible) {
            return;
        }
        videoVisible = true;
        textureView.setAlpha(1f);
        hideStatus();
    }

    private void hideVideo() {
        if (!videoVisible && textureView.getAlpha() == 0f) {
            return;
        }
        videoVisible = false;
        textureView.setAlpha(0f);
    }

    /** 把连接状态/失败原因显示在取景框里。 */
    private void showStatus(String message) {
        if (message == null || message.length() == 0) {
            hideStatus();
            return;
        }
        statusText.setText(message);
        statusText.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        statusText.setVisibility(View.GONE);
    }

    private void cacheCurrentFrame() {
        if (!textureView.isAvailable() || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        Bitmap bitmap = textureView.getBitmap();
        if (bitmap == null) {
            return;
        }
        if (lastFrameBitmap != null && !lastFrameBitmap.isRecycled()) {
            lastFrameBitmap.recycle();
        }
        lastFrameBitmap = bitmap;
    }

    private void releasePreviewSurface() {
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }

    private String[] sanitizeUrls(String[] urls) {
        List<String> cleaned = new ArrayList<>();
        if (urls != null) {
            for (String url : urls) {
                if (TextUtils.isEmpty(url)) {
                    continue;
                }
                String trimmed = url.trim();
                if (trimmed.length() > 0 && !cleaned.contains(trimmed)) {
                    cleaned.add(trimmed);
                }
            }
        }
        return cleaned.toArray(new String[0]);
    }

    private String join(String[] urls) {
        StringBuilder builder = new StringBuilder();
        for (String url : urls) {
            builder.append(url).append('\n');
        }
        return builder.toString();
    }
}
