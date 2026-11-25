package com.example.icuapk;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends Activity
        implements BleManager.Listener, Am4100Manager.Listener, ThermalBleManager.Listener {
    private static final int REQUEST_BLE_PERMISSIONS = 1001;
    private static final int REQUEST_GALLERY_PERMISSIONS = 1002;
    /** 相册扫描结果的缓存时间，避免每次切页都重新扫媒体库。 */
    private static final long GALLERY_CACHE_MS = 10000L;
    /** 教程页相关的本地配置：素材指纹、权限申请记录。 */
    private static final String TUTORIAL_PREFS = "icu_tutorial";
    /** 上一次记录下来的本机素材指纹，用来判断教程资料有没有更新。 */
    private static final String KEY_TUTORIAL_FINGERPRINT = "media_fingerprint";
    /** 是否已经向用户申请过相册权限，用来区分"没申请过"和"被永久拒绝"。 */
    private static final String KEY_GALLERY_PERMISSION_ASKED = "gallery_permission_asked";
    /** 蓝牙权限是否已经申请过：用来区分"还没申请"和"被永久拒绝"。 */
    private static final String KEY_BLE_PERMISSION_ASKED = "ble_permission_asked";
    private static final String TAG = "IcuWebView";
    /** 蓝湖页面的入口页，主文档加载失败时回到这里。 */
    private static final String LANHU_HOME_URL = "file:///android_asset/lanhu/index.html";
    /** 登录/注册页：没有账号时只能进这一个页面。 */
    private static final String LOGIN_URL = "file:///android_asset/lanhu/src/views/lanhu_denglu/index.html";
    /** 三条蓝牙链路的名字，断开提示里要用它们区分是哪台设备断了。 */
    private static final String BLE_LINK_HOST = "动物ICU主机";
    private static final String BLE_LINK_MONITOR = "监护仪";
    private static final String BLE_LINK_THERMAL = "红外测温桥";
    /** 单个波形通道下发给网页的最大采样点数。 */
    private static final int WAVE_SAMPLE_LIMIT = 240;
    /** 血压/体温趋势曲线下发给网页的最大测量点数。 */
    private static final int TREND_SAMPLE_LIMIT = 60;

    private BleManager bleManager;
    private Am4100Manager am4100Manager;
    private IcuDashboardView dashboardView;
    private CameraInlinePreviewView cameraInlinePreviewView;
    private IcuWebView lanhuWebView;
    private volatile String galleryJson = "{\"granted\":null,\"fingerprint\":\"\",\"items\":[]}";
    private volatile long galleryUpdatedAt;
    private final AtomicBoolean galleryRefreshing = new AtomicBoolean(false);
    private ThermalBleManager thermalBleManager;
    private volatile String thermalConnectionState = "等待红外测温桥";
    private volatile boolean thermalFullscreenVisible;
    private boolean thermalConnectRequested;
    private String pendingThermalDeviceId = "";
    /** 蓝牙权限申请通过后要补发的那条指令。 */
    private Runnable pendingBleRetry;
    private long lastThermalJsDispatchMs = 0L;
    private RectF lastInlineCameraBounds;
    private String[] lastInlineCameraUrls;
    /** 蓝牙开关广播接收器：蓝牙被重新打开时自动把上次连过的设备连回来。 */
    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            handleBluetoothStateChanged(state);
        }
    };
    private boolean bluetoothReceiverRegistered = false;
    /** 三条蓝牙链路的连接状态：只在“已连接 → 断开”的跳变上弹一次提示。 */
    private boolean hostConnected;
    private boolean monitorConnected;
    private boolean thermalConnected;
    /** 蓝牙开关被关掉：这时三条链路都按断开处理，GATT 回调再来也不会重复弹窗。 */
    private boolean bluetoothSwitchOff;
    /** 断开提示弹窗：同一时刻只保留一个，避免连续掉线时弹窗堆叠。 */
    private AlertDialog bleDisconnectDialog;
    /** 尚未恢复的断开事件，与当前弹窗展示、用户已经确认的事件分别记录。 */
    private final LinkedHashSet<String> notifiedBleLinks = new LinkedHashSet<String>();
    private final LinkedHashSet<String> displayedBleLinks = new LinkedHashSet<String>();
    private final LinkedHashSet<String> acknowledgedBleLinks = new LinkedHashSet<String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        bleManager = new BleManager(this);
        bleManager.setListener(this);
        am4100Manager = new Am4100Manager(this);
        am4100Manager.setListener(this);
        thermalBleManager = new ThermalBleManager(this);
        thermalBleManager.setListener(this);
        registerBluetoothStateReceiver();
        dashboardView = new IcuDashboardView(this, bleManager, am4100Manager);
        dashboardView.setVisibility(View.INVISIBLE);
        cameraInlinePreviewView = new CameraInlinePreviewView(this);
        dashboardView.setCameraPreviewHost(new IcuDashboardView.CameraPreviewHost() {
            @Override
            public void updateCameraPreview(RectF bounds, boolean visible, String[] urls) {
                updateInlineCameraPreview(bounds, visible, urls);
            }

            @Override
            public void restartCameraPreview(String[] urls) {
                restartInlineCameraPreview(urls);
            }

            @Override
            public void captureCameraFrame(IcuDashboardView.CameraFrameCallback callback) {
                captureInlineCameraFrame(callback);
            }

            @Override
            public void startCameraRecording(File output) throws IOException {
                startInlineCameraRecording(output);
            }

            @Override
            public File stopCameraRecording() {
                return stopInlineCameraRecording();
            }

            @Override
            public boolean isCameraRecording() {
                return cameraInlinePreviewView != null && cameraInlinePreviewView.isRecording();
            }
        });
        FrameLayout root = new FrameLayout(this);
        ImmersiveMode.consumeSystemBarInsets(root);
        root.addView(dashboardView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        lanhuWebView = createLanhuWebView();
        root.addView(lanhuWebView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(cameraInlinePreviewView, new FrameLayout.LayoutParams(1, 1));
        ImmersiveMode.apply(this);
        setContentView(root);
        // 监护仪接上后要持续盯着上下限：数值越界要在 App 上立刻报警。
        dashboardView.startMonitorAlarmWatch();
        // 视图都建好之后再自动连接：连接过程会立刻回调状态，
        // 放在前面会打到还没初始化的 dashboardView 上。
        tryAutoConnectDevices();
    }

    /**
     * 一次真实触点的快照。localX/localY 为 WebView 视图坐标系内的落点，
     * 是唯一与渲染同一坐标系的基准，可用于纠正浏览器上报的整体错位。
     */
    private static final class TouchSnapshot {
        static final TouchSnapshot EMPTY = new TouchSnapshot(-1f, -1f, -1f, -1f, 0, 0, 0, 0, 0f);

        final float localX;
        final float localY;
        final float rawX;
        final float rawY;
        final int originX;
        final int originY;
        final int viewWidth;
        final int viewHeight;
        final float density;

        TouchSnapshot(float localX, float localY, float rawX, float rawY,
                      int originX, int originY, int viewWidth, int viewHeight, float density) {
            this.localX = localX;
            this.localY = localY;
            this.rawX = rawX;
            this.rawY = rawY;
            this.originX = originX;
            this.originY = originY;
            this.viewWidth = viewWidth;
            this.viewHeight = viewHeight;
            this.density = density;
        }
    }

    /**
     * 记录触点原始坐标的 WebView。onTouchEvent 收到的事件坐标即视图内坐标，
     * 与页面渲染共用同一套缩放，不会因系统窗口缩放或状态栏产生整体偏移。
     */
    private static final class IcuWebView extends WebView {
        private volatile TouchSnapshot snapshot = TouchSnapshot.EMPTY;
        private float localX = -1f;
        private float localY = -1f;
        private float rawX = -1f;
        private float rawY = -1f;
        private boolean viewportLogged;

        IcuWebView(Context context) {
            super(context);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP ||
                    action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
                int index = event.getActionIndex();
                if (index < 0 || index >= event.getPointerCount()) {
                    index = 0;
                }
                localX = event.getX(index);
                localY = event.getY(index);
                rawX = event.getRawX(index);
                rawY = event.getRawY(index);
                publish();
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            // 布局变化（含旋转）后立即刷新窗口几何，保证触摸基准始终有效
            publish();
        }

        private void publish() {
            int[] location = new int[2];
            getLocationOnScreen(location);
            snapshot = new TouchSnapshot(
                    localX,
                    localY,
                    rawX,
                    rawY,
                    location[0],
                    location[1],
                    getWidth(),
                    getHeight(),
                    getResources().getDisplayMetrics().density);
            if (!viewportLogged && getWidth() > 0 && getHeight() > 0) {
                // 与网页侧上报的 devicePixelRatio 对照，可直接判断真机上"系统密度"与"渲染比例"是否一致
                viewportLogged = true;
                Log.i(TAG, "icu webview viewport " + getWidth() + "x" + getHeight()
                        + " density=" + getResources().getDisplayMetrics().density
                        + " densityDpi=" + getResources().getDisplayMetrics().densityDpi
                        + " origin=" + location[0] + "," + location[1]);
            }
        }

        TouchSnapshot snapshot() {
            return snapshot;
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private IcuWebView createLanhuWebView() {
        IcuWebView webView = new IcuWebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        WebView.setWebContentsDebuggingEnabled(true);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Log.i(TAG, "console[" + message.messageLevel() + "] " + message.message()
                        + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 未登录时任何页面都不许打开：左右舱、病例列表这些内容不能先露出来
                if (blockedByLoginGate(request.getUrl().toString())) {
                    view.post(new Runnable() {
                        @Override
                        public void run() {
                            view.loadUrl(LOGIN_URL);
                        }
                    });
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // 门禁再拦一次：网页里用 location.replace 跳主页面时，
                // 不拦的话会先闪出左右舱再被换回登录页。
                if (blockedByLoginGate(url)) {
                    view.post(new Runnable() {
                        @Override
                        public void run() {
                            view.loadUrl(LOGIN_URL);
                        }
                    });
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                notifyLanhuStateChanged();
                enforceLoginGate(view, url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                Log.e(TAG, "load error " + request.getUrl() + " -> " + error.getDescription());
                // 主文档加载失败（例如跳到了已下线的旧报告页）时回到入口页，
                // 否则 WebView 会停在错误页上，页面内的跳转全部失效，只能重启应用。
                if (request.isForMainFrame() && !LANHU_HOME_URL.equals(request.getUrl().toString())) {
                    view.post(new Runnable() {
                        @Override
                        public void run() {
                            view.loadUrl(startPageUrl());
                        }
                    });
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                super.onReceivedHttpError(view, request, response);
                Log.e(TAG, "http error " + request.getUrl() + " -> " + response.getStatusCode());
            }
        });
        webView.addJavascriptInterface(new LanhuNativeBridge(), "IcuNative");
        webView.loadUrl(startPageUrl());
        return webView;
    }

    /**
     * 入口页：没有登录账号时只能停在登录页，登录过才进主页面。
     */
    private String startPageUrl() {
        return AccountStore.isLoggedIn(this) ? LANHU_HOME_URL : LOGIN_URL;
    }

    /**
     * 登录门禁：未登录时任何页面都要拉回登录页，
     * 否则可以直接从页面跳转绕过登录。
     */
    private void enforceLoginGate(WebView view, String url) {
        if (!blockedByLoginGate(url)) {
            return;
        }
        view.post(new Runnable() {
            @Override
            public void run() {
                view.loadUrl(LOGIN_URL);
            }
        });
    }

    /**
     * 登录门禁：没有账号时只能停在登录页。
     *
     * <p>网页里任何跳回主页面的动作（location.replace、返回键、历史记录）
     * 都要在这里拦下来，否则会出现"没登录却看到左右舱和病例列表"的情况。</p>
     *
     * @param url 准备打开的地址
     * @return true 表示这个地址不该打开
     */
    private boolean blockedByLoginGate(String url) {
        if (AccountStore.isLoggedIn(this)) {
            return false;
        }
        return url == null || !url.startsWith(LOGIN_URL);
    }

    /**
     * 回到前台时检查登录态：没有账号就回到登录页。
     *
     * <p>App 在后台被杀掉再拉起时，网页可能还停在上次的主页面。</p>
     */
    private void enforceLoginGateOnResume() {
        if (lanhuWebView == null || AccountStore.isLoggedIn(this)) {
            return;
        }
        String url = lanhuWebView.getUrl();
        if (url != null && url.startsWith(LOGIN_URL)) {
            return;
        }
        lanhuWebView.loadUrl(LOGIN_URL);
    }

    private void updateInlineCameraPreview(RectF bounds, boolean visible, String[] urls) {
        if (cameraInlinePreviewView == null) {
            return;
        }
        if (!visible || bounds == null || bounds.width() <= 2 || bounds.height() <= 2) {
            lastInlineCameraBounds = null;
            lastInlineCameraUrls = null;
            cameraInlinePreviewView.setActive(false);
            // 画面收起来的时候必须把录像收尾，否则会留下写坏的文件
            dashboardView.stopCameraRecordingIfNeeded();
            return;
        }
        lastInlineCameraBounds = new RectF(bounds);
        lastInlineCameraUrls = urls;
        if (thermalFullscreenVisible) {
            // 红外热图全屏时原生预览盖在网页之上，必须先让位，退出全屏再恢复
            cameraInlinePreviewView.setActive(false);
            cameraInlinePreviewView.setVisibility(View.GONE);
            return;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) cameraInlinePreviewView.getLayoutParams();
        params.leftMargin = Math.round(bounds.left);
        params.topMargin = Math.round(bounds.top);
        params.width = Math.round(bounds.width());
        params.height = Math.round(bounds.height());
        cameraInlinePreviewView.setLayoutParams(params);
        cameraInlinePreviewView.setPreview(urls);
        cameraInlinePreviewView.setActive(true);
    }

    private void captureInlineCameraFrame(IcuDashboardView.CameraFrameCallback callback) {
        if (cameraInlinePreviewView == null) {
            callback.onError("摄像头预览未初始化");
            return;
        }
        cameraInlinePreviewView.captureFrame(callback);
    }

    private void startInlineCameraRecording(File output) throws IOException {
        if (cameraInlinePreviewView == null) {
            throw new IOException("摄像头预览未初始化");
        }
        cameraInlinePreviewView.startRecording(output);
    }

    private File stopInlineCameraRecording() {
        if (cameraInlinePreviewView == null) {
            return null;
        }
        return cameraInlinePreviewView.stopRecording();
    }

    private void restartInlineCameraPreview(String[] urls) {
        if (cameraInlinePreviewView == null) {
            return;
        }
        cameraInlinePreviewView.restartPreview(urls);
    }

    private void updateLanhuCameraPreview(float left, float top, float width, float height,
                                          float viewportWidth, float viewportHeight, boolean visible) {
        if (!visible || lanhuWebView == null || width <= 2 || height <= 2) {
            updateInlineCameraPreview(null, false, null);
            return;
        }
        float scaleX = viewportWidth <= 0 ? 1f : lanhuWebView.getWidth() / viewportWidth;
        float scaleY = viewportHeight <= 0 ? 1f : lanhuWebView.getHeight() / viewportHeight;
        RectF bounds = new RectF(
                lanhuWebView.getLeft() + left * scaleX,
                lanhuWebView.getTop() + top * scaleY,
                lanhuWebView.getLeft() + (left + width) * scaleX,
                lanhuWebView.getTop() + (top + height) * scaleY);
        updateInlineCameraPreview(bounds, true, dashboardView.getCameraPreviewUrlsForWeb());
    }

    /**
     * 通知 WebView 重新拉取 native 状态。
     * 原生侧改动机构信息后调用，保证页面上的回填值立即更新。
     */
    void notifyLanhuStateChanged() {
        if (lanhuWebView == null) {
            return;
        }
        lanhuWebView.post(new Runnable() {
            @Override
            public void run() {
                lanhuWebView.evaluateJavascript(
                        "window.dispatchEvent(new Event('icu-native-state'));", null);
            }
        });
    }

    /**
     * 高频数值/波形帧引起的状态推送做节流。
     *
     * <p>监护仪每秒发来十几帧（波形帧经 90ms 节流后仍约 11 帧/秒，数值帧 1~2 帧/秒），
     * 如果每帧都触发 {@code icu-native-state} → 整页 {@code firstPhaseState()}
     * （含 {@code PrinterSettings.load} 磁盘读与全量 cases/treatments 序列化），
     * WebView JS 线程会被吃满，反过来拖慢 120ms 的数值轮询，让"数值显示慢"雪上加霜。</p>
     *
     * <p>数值本身走独立的 {@code vitalsState()} 轻量桥 + 60ms 轮询 + 事件即时写入，
     * 不受这里节流影响；这里只负责触发整页渲染（报警条、侧栏等），4 次/秒足够，
     * 既去掉 App 侧多余的渲染负担，又保证设备一旦给出有效帧能被即时显示。</p>
     */
    private static final long LANHU_MONITOR_PUSH_MIN_INTERVAL_MS = 250L;
    private long lastMonitorPushMs;

    private void notifyMonitorFast() {
        long now = SystemClock.uptimeMillis();
        if (now - lastMonitorPushMs < LANHU_MONITOR_PUSH_MIN_INTERVAL_MS) {
            return;
        }
        lastMonitorPushMs = now;
        notifyLanhuStateChanged();
    }

    /**
     * 回到登录页。
     *
     * 退出登录后必须离开主页面：留在 ICU 状态页上，顶栏的账号位会显示成空，
     * 使用者会以为还处于登录状态。
     */
    public void navigateToLoginPage() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (lanhuWebView != null) {
                    // 历史里不能留着主页面：返回键会把人带回左右舱那一屏
                    lanhuWebView.clearHistory();
                    lanhuWebView.loadUrl(LOGIN_URL);
                }
            }
        });
    }

    /**
     * 相册内容在后台线程读取，扫描媒体库不会卡住 WebView。
     */
    private void refreshGalleryItems() {
        if (!galleryRefreshing.compareAndSet(false, true)) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String json = TutorialMediaLibrary.snapshotJson(MainActivity.this, TutorialMediaLibrary.DEFAULT_LIMIT).toString();
                galleryJson = json;
                galleryUpdatedAt = System.currentTimeMillis();
                galleryRefreshing.set(false);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        notifyGalleryChanged();
                    }
                });
            }
        }, "icu-gallery-refresh").start();
    }

    private void notifyGalleryChanged() {
        if (lanhuWebView == null) {
            return;
        }
        lanhuWebView.post(new Runnable() {
            @Override
            public void run() {
                lanhuWebView.evaluateJavascript(
                        "window.dispatchEvent(new Event('icu-gallery-updated'));", null);
            }
        });
    }

    private void requestGalleryPermissions() {
        ArrayList<String> pending = new ArrayList<>();
        for (String permission : TutorialMediaLibrary.requiredPermissions(this)) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                pending.add(permission);
            }
        }
        if (pending.isEmpty()) {
            galleryUpdatedAt = 0L;
            refreshGalleryItems();
            notifyLanhuStateChanged();
            return;
        }
        getSharedPreferences(TUTORIAL_PREFS, 0).edit().putBoolean(KEY_GALLERY_PERMISSION_ASKED, true).apply();
        requestPermissions(pending.toArray(new String[0]), REQUEST_GALLERY_PERMISSIONS);
    }

    /**
     * 素材清单里补上"还能不能再弹系统授权框"，页面据此决定提示方式。
     */
    private String galleryJsonWithPermissionState() {
        try {
            JSONObject root = new JSONObject(galleryJson);
            root.put("canAskAgain", canAskGalleryPermission());
            return root.toString();
        } catch (JSONException exception) {
            return galleryJson;
        }
    }

    /**
     * 系统权限对话框还能不能弹出来。
     * 勾选"不再询问"或永久拒绝后 shouldShowRequestPermissionRationale 恒为 false，
     * 但"从未申请过"也是 false，所以用一个偏好标记区分这两种情况。
     */
    private boolean canAskGalleryPermission() {
        for (String permission : TutorialMediaLibrary.requiredPermissions(this)) {
            if (shouldShowRequestPermissionRationale(permission)) {
                return true;
            }
        }
        return !getSharedPreferences(TUTORIAL_PREFS, 0)
                .getBoolean(KEY_GALLERY_PERMISSION_ASKED, false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 平板从最近任务划掉 App 时 onDestroy 不保证被调用；onPause 一定会走。
        // 在这里同步写入治疗项、主机计时锚点和当前病例的监护快照，冷启动才不会把
        // 正在治疗样本的治疗时长、生命体征历史和三路会话波形重置为空。
        if (dashboardView != null) {
            dashboardView.flushTreatmentStateForBackground();
        }
        if (bleManager != null) {
            bleManager.persistTreatmentClockNow();
        }
        if (lanhuWebView != null) {
            lanhuWebView.onPause();
        }
        if (cameraInlinePreviewView != null) {
            cameraInlinePreviewView.setActive(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ImmersiveMode.apply(this);
        // 退出登录或账号被清掉后回到前台，必须立刻回到登录页
        enforceLoginGateOnResume();
        if (lanhuWebView != null) {
            lanhuWebView.onResume();
        }
        if (dashboardView != null) {
            dashboardView.invalidate();
        }
        if (System.currentTimeMillis() - galleryUpdatedAt > GALLERY_CACHE_MS) {
            refreshGalleryItems();
        }
        // 回到前台时如果还没连上上次那台设备，自动接回去
        tryAutoConnectDevices();
        // 回到前台时顺手把蓝牙权限补上：不同平板预置的权限不一样，
        // 别的机器装好就能扫，这台却因为没有「附近设备」权限一个设备都扫不到。
        requestBlePermissionsOnStartup();
        notifyLanhuStateChanged();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // 系统栏被从边缘划出后会重新显示，重新拿到焦点时再隐藏一次
            ImmersiveMode.apply(this);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == TutorialDocumentImporter.REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                // 文件选择器开了多选：一次挑中的照片、录像、文档全部导入
                android.content.ClipData clip = data.getClipData();
                if (clip != null && clip.getItemCount() > 0) {
                    for (int index = 0; index < clip.getItemCount(); index += 1) {
                        Uri item = clip.getItemAt(index).getUri();
                        if (item != null) {
                            runTutorialDocumentImport(item);
                        }
                    }
                } else if (data.getData() != null) {
                    runTutorialDocumentImport(data.getData());
                }
            }
            return;
        }
        if (requestCode == IcuDashboardView.REQUEST_ORGANIZATION_LOGO) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                dashboardView.onOrganizationLogoSelected(data.getData());
                notifyLanhuStateChanged();
            }
            return;
        }
    }

    /**
     * 把用户选中的文档导入教程资料库，导入完立刻刷新教程页。
     *
     * <p>复制文件在后台线程做，避免大文件让界面卡住。</p>
     */
    private void runTutorialDocumentImport(final Uri source) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final File file = TutorialDocumentImporter.importFrom(MainActivity.this, source);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (file == null) {
                            Toast.makeText(MainActivity.this, "该文件不是支持的图片、视频或文档", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Toast.makeText(MainActivity.this, "已导入教程资料：" + file.getName(), Toast.LENGTH_SHORT).show();
                        // 立刻重新扫描素材，教程页不用等下一次轮询就能看到新文档
                        galleryUpdatedAt = 0L;
                        refreshGalleryItems();
                    }
                });
            }
        }, "icu-tutorial-import").start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraInlinePreviewView != null) {
            cameraInlinePreviewView.stopPreview();
            cameraInlinePreviewView.setVisibility(View.GONE);
        }
        if (lanhuWebView != null) {
            lanhuWebView.destroy();
            lanhuWebView = null;
        }
        unregisterBluetoothStateReceiver();
        bleManager.stopScan();
        // 用 release 而不是 disconnect：断开链路，但保留记住的设备，下次启动还能自动连上
        bleManager.release();
        am4100Manager.stopScan();
        am4100Manager.release();
        if (dashboardView != null) {
            dashboardView.stopMonitorAlarmWatch();
        }
        if (thermalBleManager != null) {
            thermalBleManager.stop();
            thermalBleManager.setListener(null);
        }
    }

    @Override
    public void onStateChanged() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // 自动重连可能在 onCreate 还没建完视图时就回调，这里必须做空判断
                if (dashboardView != null) {
                    // 开关沿和真实使用时长跟随设备状态记录，不依赖网页是否完成全量刷新。
                    dashboardView.captureTreatmentSampleIfNeeded(false);
                    dashboardView.cancelMonitorAlarmsIfDisconnected();
                    dashboardView.invalidate();
                }
                notifyMonitorFast();
                // 主机与监护仪共用这个回调：任一条链路掉线都要弹提示
                checkBleDisconnect();
            }
        });
    }

    @Override
    public void onThermalFrame(short[] centiDegrees, int width, int height, long timestampMs) {
        if (lanhuWebView == null || centiDegrees == null || centiDegrees.length != width * height) {
            return;
        }
        // 兜底节流：上一帧还没推给网页就跳过本帧，避免 evaluateJavascript 在主线程堆积
        long now = SystemClock.uptimeMillis();
        if (now - lastThermalJsDispatchMs < 50L) {
            return;
        }
        lastThermalJsDispatchMs = now;
        byte[] payload = new byte[centiDegrees.length * 2];
        for (int i = 0; i < centiDegrees.length; i++) {
            int offset = i * 2;
            payload[offset] = (byte) (centiDegrees[i] & 0xff);
            payload[offset + 1] = (byte) ((centiDegrees[i] >> 8) & 0xff);
        }
        final String base64 = Base64.encodeToString(payload, Base64.NO_WRAP);
        final String script = "window.IcuThermal&&window.IcuThermal.receiveFrame('" + base64
                + "'," + width + "," + height + ");";
        lanhuWebView.post(new Runnable() {
            @Override
            public void run() {
                if (lanhuWebView != null) {
                    lanhuWebView.evaluateJavascript(script, null);
                }
            }
        });
    }

    @Override
    public void onThermalConnectionChanged(String state) {
        thermalConnectionState = state == null ? "等待红外测温桥" : state;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                checkBleDisconnect();
            }
        });
        if (lanhuWebView == null) {
            return;
        }
        final String escaped = JSONObject.quote(thermalConnectionState);
        lanhuWebView.post(new Runnable() {
            @Override
            public void run() {
                if (lanhuWebView != null) {
                    lanhuWebView.evaluateJavascript(
                            "window.IcuThermal&&window.IcuThermal.setConnectionState(" + escaped + ");", null);
                }
            }
        });
    }

    @Override
    public void onThermalDevicesChanged(ArrayList<ThermalBleManager.DeviceItem> devices) {
        if (lanhuWebView == null) {
            return;
        }
        lanhuWebView.post(new Runnable() {
            @Override
            public void run() {
                if (lanhuWebView != null) {
                    lanhuWebView.evaluateJavascript(
                            "window.dispatchEvent(new Event('icu-thermal-devices'));", null);
                }
            }
        });
    }

    public boolean hasAllBlePermissions() {
        return hasScanPermission() && hasConnectPermission();
    }

    /**
     * 扫描需要的权限。
     *
     * <p>Android 12（API 31）起系统用"附近设备"权限管蓝牙扫描：拿到
     * BLUETOOTH_SCAN 就够了——清单里给它声明了 neverForLocation，系统不会再用
     * 位置权限过滤扫描结果，也不要求系统定位开关必须打开。</p>
     *
     * <p>Android 11 及以下仍是老规矩：BLE 扫描结果必须带位置权限，
     * 否则系统会把结果整批过滤掉，表现就是设备列表一直是空的。</p>
     */
    private String[] getScanPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN};
        }
        return new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };
    }

    /** 连接需要的权限：Android 12 起是"附近设备"里的连接项，老系统不用额外权限。 */
    private String[] getConnectPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[0];
    }

    /** 扫描权限是否已经拿到。 */
    public boolean hasScanPermission() {
        return allPermissionsGranted(getScanPermissions());
    }

    /** 连接权限是否已经拿到。 */
    public boolean hasConnectPermission() {
        return allPermissionsGranted(getConnectPermissions());
    }

    private boolean allPermissionsGranted(String[] permissions) {
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public void requestBlePermissionsFromUi() {
        ArrayList<String> pending = new ArrayList<>();
        for (String permission : getScanPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                pending.add(permission);
            }
        }
        for (String permission : getConnectPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                pending.add(permission);
            }
        }
        if (pending.isEmpty()) {
            Toast.makeText(this, "权限已授权，可以开始扫描", Toast.LENGTH_SHORT).show();
            dashboardView.invalidate();
            return;
        }
        // 用户勾了"不再询问"之后系统弹窗不会再出现：这时再调 requestPermissions
        // 什么也不会发生，现场就变成"怎么点了没反应"。直接把人送去应用权限设置页。
        if (!canAskBlePermission()) {
            openAppPermissionSettings();
            return;
        }
        getSharedPreferences(TUTORIAL_PREFS, 0).edit().putBoolean(KEY_BLE_PERMISSION_ASKED, true).apply();
        requestPermissions(pending.toArray(new String[0]), REQUEST_BLE_PERMISSIONS);
    }

    /**
     * 系统权限对话框还能不能弹出来。
     * 勾选"不再询问"或永久拒绝后 shouldShowRequestPermissionRationale 恒为 false，
     * 但"从未申请过"也是 false，所以用一个偏好标记区分这两种情况。
     */
    private boolean canAskBlePermission() {
        for (String permission : getScanPermissions()) {
            if (shouldShowRequestPermissionRationale(permission)) {
                return true;
            }
        }
        for (String permission : getConnectPermissions()) {
            if (shouldShowRequestPermissionRationale(permission)) {
                return true;
            }
        }
        return !getSharedPreferences(TUTORIAL_PREFS, 0)
                .getBoolean(KEY_BLE_PERMISSION_ASKED, false);
    }

    /** 跳到本应用的权限设置页，让用户自己把"附近设备"打开。 */
    private void openAppPermissionSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Toast.makeText(this, "请在系统设置里打开本应用的蓝牙/附近设备权限", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 首次进入 App 时主动申请一次蓝牙权限。
     *
     * <p>只申请一次：申请过之后不再自动打扰，缺权限时由「申请权限」按钮
     * 和扫描按钮现场触发，永久被拒则把人送去应用权限设置页。</p>
     */
    private void requestBlePermissionsOnStartup() {
        if (hasScanPermission() && hasConnectPermission()) {
            return;
        }
        if (getSharedPreferences(TUTORIAL_PREFS, 0).getBoolean(KEY_BLE_PERMISSION_ASKED, false)) {
            return;
        }
        requestBlePermissionsFromUi();
    }

    /**
     * 监听蓝牙开关状态。蓝牙被关掉再打开之后，之前连过的设备要能自动接回去，
     * 否则使用者还得重新扫一遍设备列表。
     */
    private void registerBluetoothStateReceiver() {
        if (bluetoothReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(bluetoothStateReceiver, filter);
        }
        bluetoothReceiverRegistered = true;
    }

    private void unregisterBluetoothStateReceiver() {
        if (!bluetoothReceiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(bluetoothStateReceiver);
        } catch (IllegalArgumentException ignored) {
            // 已经注销过，忽略
        }
        bluetoothReceiverRegistered = false;
    }

    /** 蓝牙状态变化：打开时自动连回上次那台设备，关闭时停掉等待中的重连。 */
    private void handleBluetoothStateChanged(int state) {
        if (state == BluetoothAdapter.STATE_ON) {
            bluetoothSwitchOff = false;
            tryAutoConnectDevices();
            return;
        }
        if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
            bluetoothSwitchOff = true;
            if (bleManager != null) {
                bleManager.onBluetoothOff();
            }
            if (am4100Manager != null) {
                am4100Manager.onBluetoothOff();
            }
            // 蓝牙被关掉时三条链路同时掉线：这里先按断开记一次，
            // 之后 GATT 回调再来时不会重复弹窗。
            checkBleDisconnect();
        }
    }

    /**
     * 检查三条蓝牙链路，出现“已连接 → 断开”的跳变时弹提示。
     *
     * 主机、监护仪、红外测温桥是三条独立链路，任何一条掉线都要让使用者知道：
     * 页面上数值停住更容易被当成设备卡死，而不是掉线。
     *
     */
    private void checkBleDisconnect() {
        boolean host = !bluetoothSwitchOff && bleManager != null && bleManager.isConnected();
        boolean monitor = !bluetoothSwitchOff && am4100Manager != null && am4100Manager.isConnected();
        boolean thermal = !bluetoothSwitchOff && thermalBleManager != null && thermalBleManager.isConnected();
        ArrayList<String> dropped = new ArrayList<>();
        if (hostConnected && !host) {
            dropped.add(BLE_LINK_HOST);
        }
        if (monitorConnected && !monitor) {
            dropped.add(BLE_LINK_MONITOR);
        }
        if (thermalConnected && !thermal) {
            dropped.add(BLE_LINK_THERMAL);
        }
        hostConnected = host;
        monitorConnected = monitor;
        thermalConnected = thermal;
        // 恢复的链路要移出清单：清单清空说明提示过的设备都连回来了。
        trackBleLink(BLE_LINK_HOST, host);
        trackBleLink(BLE_LINK_MONITOR, monitor);
        trackBleLink(BLE_LINK_THERMAL, thermal);
        if (dropped.isEmpty()) {
            // 没有新的断开事件：只有之前提示过的链路都恢复回来，才收起提示框。
            dismissBleDisconnectIfRestored();
            return;
        }
        notifiedBleLinks.addAll(dropped);
        showBleDisconnect();
    }

    /**
     * 记录单条链路的断开/恢复状态。
     *
     * 恢复时把它从“已提示”的清单里移除，清单清空说明提示过的设备都连回来了。
     */
    private void trackBleLink(String name, boolean connected) {
        if (connected) {
            notifiedBleLinks.remove(name);
            displayedBleLinks.remove(name);
            acknowledgedBleLinks.remove(name);
        }
    }

    /**
     * 提示过的链路都恢复之后才把提示框收掉。
     *
     * 不能只看“还有没有链路是连着的”：只要主机还连着、监护仪还断着，
     * 提示框就还得留着，否则下一次状态刷新就会把刚弹出的提示框关掉。
     */
    private void dismissBleDisconnectIfRestored() {
        if (!notifiedBleLinks.isEmpty()) {
            return;
        }
        if (bleDisconnectDialog != null && bleDisconnectDialog.isShowing()) {
            bleDisconnectDialog.dismiss();
        }
    }

    /**
     * 弹出断开提示：写明是哪台设备断的，并给出重新连接的入口。
     *
     * 框里列出的是当前所有断开的链路：提示还在时又断了一条，就把旧的收起来
     * 重弹一次，免得使用者只看到第一条、漏掉后面断掉的设备。
     */
    private void showBleDisconnect() {
        // 已恢复的链路从当前展示清单移除；已确认但尚未恢复的旧事件不再随新事件重提。
        displayedBleLinks.retainAll(notifiedBleLinks);
        LinkedHashSet<String> newLinks = new LinkedHashSet<String>(notifiedBleLinks);
        newLinks.removeAll(acknowledgedBleLinks);
        newLinks.removeAll(displayedBleLinks);
        if (newLinks.isEmpty()) {
            dismissBleDisconnectIfRestored();
            return;
        }
        displayedBleLinks.addAll(newLinks);
        StringBuilder names = new StringBuilder();
        StringBuilder detail = new StringBuilder();
        for (String name : displayedBleLinks) {
            if (names.length() > 0) {
                names.append('、');
                detail.append('；');
            }
            names.append(name);
            detail.append(bleLinkDetail(name));
        }
        if (bluetoothSwitchOff) {
            if (detail.length() > 0) {
                detail.append('；');
            }
            detail.append("请先打开手机蓝牙");
        }
        if (bleDisconnectDialog != null && bleDisconnectDialog.isShowing()) {
            BleDisconnectDialog.update(bleDisconnectDialog, names.toString(), detail.toString());
            return;
        }
        bleDisconnectDialog = BleDisconnectDialog.show(this, names.toString(), detail.toString(),
                new Runnable() {
                    @Override
                    public void run() {
                        tryAutoConnectDevices();
                    }
                });
        if (bleDisconnectDialog != null) {
            bleDisconnectDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    acknowledgedBleLinks.addAll(displayedBleLinks);
                    displayedBleLinks.clear();
                    bleDisconnectDialog = null;
                }
            });
        } else {
            displayedBleLinks.clear();
        }
    }

    /** 单条链路断开后，页面上会停掉什么。 */
    private String bleLinkDetail(String name) {
        if (BLE_LINK_HOST.equals(name)) {
            return "主机数据与治疗计时已停止更新";
        }
        if (BLE_LINK_MONITOR.equals(name)) {
            return "生命体征保留断开前数值，实时波形已停止更新";
        }
        return "体温数据已停止更新";
    }

    /**
     * App 启动、蓝牙重新打开、权限刚授权下来时都走这里：
     * 把之前连过的设备自动连回来，不用再手动扫描一遍。
     */
    private void tryAutoConnectDevices() {
        if (bleManager == null || am4100Manager == null) {
            return;
        }
        bleManager.tryAutoConnect();
        am4100Manager.tryAutoConnect();
        if (thermalBleManager != null) {
            thermalBleManager.tryAutoConnect();
        }
    }

    /**
     * 申请蓝牙权限，并在授权通过后补发刚才那条指令。
     * 页面上点监护等级、开关这类操作时如果还没有蓝牙权限，指令根本发不出去，
     * 这里先弹系统授权框，授权后自动补发，用户不用再点一次。
     */
    public void requestBlePermissionsWithRetry(Runnable retry) {
        pendingBleRetry = retry;
        ArrayList<String> pending = new ArrayList<>();
        for (String permission : getSendPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                pending.add(permission);
            }
        }
        if (pending.isEmpty()) {
            Runnable task = pendingBleRetry;
            pendingBleRetry = null;
            if (task != null) {
                task.run();
            }
            return;
        }
        requestPermissions(pending.toArray(new String[0]), REQUEST_BLE_PERMISSIONS);
    }

    /** 下发指令只需要蓝牙连接权限，扫描权限只有扫描设备时才用得上。 */
    private String[] getSendPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_GALLERY_PERMISSIONS) {
            boolean granted = TutorialMediaLibrary.hasMediaPermission(this);
            Toast.makeText(this, granted ? "已获得相册访问权限" : "没有相册权限，无法读取教程素材",
                    Toast.LENGTH_SHORT).show();
            galleryUpdatedAt = 0L;
            refreshGalleryItems();
            notifyLanhuStateChanged();
            return;
        }
        if (requestCode != REQUEST_BLE_PERMISSIONS) {
            return;
        }
        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        // 授权通过后补发刚才那条蓝牙指令，结果由指令自己提示，这里不再重复弹一次
        Runnable retry = pendingBleRetry;
        pendingBleRetry = null;
        if (allGranted) {
            if (retry != null) {
                retry.run();
            } else {
                Toast.makeText(this, "权限申请成功", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, retry != null ? "权限没有全部通过，本次操作已取消" : "权限没有全部通过", Toast.LENGTH_SHORT).show();
        }
        if (allGranted && thermalConnectRequested && thermalBleManager != null) {
            thermalConnectRequested = false;
            String deviceId = pendingThermalDeviceId;
            pendingThermalDeviceId = "";
            if (deviceId == null || deviceId.trim().isEmpty()) {
                thermalBleManager.start();
            } else {
                thermalBleManager.connect(deviceId);
            }
        }
        if (allGranted) {
            // 权限到位后，之前连过的设备才能自动接回去
            tryAutoConnectDevices();
        }
        dashboardView.invalidate();
        notifyLanhuStateChanged();
    }

    @Override
    public void onBackPressed() {
        if (lanhuWebView != null && lanhuWebView.canGoBack()) {
            lanhuWebView.goBack();
            return;
        }
        super.onBackPressed();
    }

    private String buildBleStateJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("host", buildHostStateJson());
            root.put("monitor", buildMonitorStateJson());
            root.put("wifi", buildWifiStateJson());
            return root.toString();
        } catch (JSONException exception) {
            return "{}";
        }
    }

    /** 登录状态变化后把当前账号同步到原生界面，并通知页面刷新。 */
    private void applyAccountUser() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (dashboardView != null) {
                    dashboardView.setAccountName(AccountStore.currentUser(MainActivity.this));
                }
                notifyLanhuStateChanged();
            }
        });
    }

    private JSONObject buildHostStateJson() throws JSONException {
        JSONObject host = new JSONObject();
        host.put("connected", bleManager.isConnected());
        host.put("active", bleManager.isLinkActive());
        host.put("scanning", bleManager.isScanning());
        host.put("ready", bleManager.isDataReady());
        host.put("protocolReady", bleManager.isProtocolReady());
        host.put("state", bleManager.getConnectionStateText());
        host.put("dataState", safeJsonText(bleManager.getDataPathState()));
        host.put("dataError", safeJsonText(bleManager.getDataPathError()));
        host.put("lastValidStateAt", bleManager.getLastValidHostStateAtMs());
        host.put("deviceName", safeJsonText(bleManager.getConnectedDeviceName()));
        host.put("deviceId", safeJsonText(bleManager.getConnectedDeviceId()));
        host.put("devices", buildHostDevicesJson());
        host.put("zone", safeJsonText(bleManager.getCurrentZone()));
        host.put("temp", safeJsonText(bleManager.getCabinTempText()));
        host.put("oxygen", safeJsonText(bleManager.getOxygenText()));
        host.put("humidity", safeJsonText(bleManager.getHumidityText()));
        host.put("co2", safeJsonText(bleManager.getCo2Text()));
        host.put("infraredTemp", safeJsonText(bleManager.getInfraredTempText()));
        host.put("treatmentTime", safeJsonText(bleManager.getTreatmentTimeText()));
        host.put("lastSent", safeJsonText(bleManager.getLastSent()));
        host.put("lastControlRequest", safeJsonText(bleManager.getLastControlRequest()));
        host.put("lastControlRequestAt", bleManager.getLastControlRequestAtMs());
        host.put("lastControlSent", safeJsonText(bleManager.getLastControlSent()));
        host.put("lastControlSentAt", bleManager.getLastControlSentAtMs());
        host.put("lastControlReply", safeJsonText(bleManager.getLastControlReply()));
        host.put("lastControlReplyAt", bleManager.getLastControlReplyAtMs());
        host.put("controlReplyState", safeJsonText(bleManager.getControlReplyState()));
        host.put("controlReplyError", safeJsonText(bleManager.getControlReplyError()));
        host.put("currentWrite", safeJsonText(bleManager.getCurrentWritePayload()));
        host.put("writeQueueSize", bleManager.getWriteQueueSize());
        host.put("lastReceived", safeJsonText(bleManager.getLastReceived()));
        host.put("lastNotifyChunk", safeJsonText(bleManager.getLastNotifyChunk()));
        host.put("lastNotifyChunkAt", bleManager.getLastNotifyChunkAtMs());
        host.put("lastCompleteFrameAt", bleManager.getLastCompleteFrameAtMs());
        host.put("notifyBufferChars", bleManager.getNotifyBufferLength());
        host.put("lastError", safeJsonText(bleManager.getLastError()));
        host.put("summary", safeJsonText(bleManager.getLastParsedStatus()));
        // 两套 host 状态 JSON 共用同一份诊断与确认快照，避免首页与蓝牙面板语义不一致。
        host.put("dataDiagVersion", BleManager.DATA_DIAG_VERSION);
        host.put("dataDiag", bleManager.buildDataDiagJson());
        host.put("controlConfirmations", bleManager.buildControlConfirmationJson());

        JSONObject controls = new JSONObject();
        putControl(controls, "temp", 0);
        putControl(controls, "oxygen", 1);
        putControl(controls, "statusLight", 2);
        putControl(controls, "co2", 3);
        putControl(controls, "coldLight", 4);
        putControl(controls, "warmLight", 5);
        putControl(controls, "redTherapy", 6);
        putControl(controls, "blueTherapy", 7);
        putControl(controls, "outerCycle", 8);
        putControl(controls, "innerCycle", 9);
        putControl(controls, "nebulizer", 10);
        putControl(controls, "anion", 11);
        putControl(controls, "uv", 12);
        putControl(controls, "o2Enabled", 13);
        putControl(controls, "humidity", 14);
        putControl(controls, "treatmentTime", 15);
        host.put("controls", controls);
        host.put("zones", buildEnvironmentZonesJson());
        return host;
    }

    private void putControl(JSONObject controls, String key, int index) throws JSONException {
        controls.put(key, safeJsonText(bleManager.getControlValue(index)));
        controls.put(key + "On", bleManager.isControlOn(index));
    }

    /** 左右两舱各自的环境实测值，供页面按舱展示。 */
    private JSONObject buildEnvironmentZonesJson() throws JSONException {
        JSONObject zones = new JSONObject();
        zones.put("left", buildEnvironmentZoneJson("left"));
        zones.put("right", buildEnvironmentZoneJson("right"));
        return zones;
    }

    private JSONObject buildEnvironmentZoneJson(String zone) throws JSONException {
        JSONObject environment = new JSONObject();
        environment.put("temp", safeJsonText(bleManager.getCabinTempText(zone)));
        environment.put("oxygen", safeJsonText(bleManager.getOxygenText(zone)));
        environment.put("humidity", safeJsonText(bleManager.getHumidityText(zone)));
        environment.put("co2", safeJsonText(bleManager.getCo2Text(zone)));
        environment.put("updatedAt", bleManager.getEnvironmentUpdatedAt(zone));
        environment.put("querySentAt", bleManager.getZoneStatusQuerySentAt(zone));
        environment.put("replyAt", bleManager.getZoneStatusReplyAt(zone));
        environment.put("validStateAt", bleManager.getZoneValidStateAt(zone));
        environment.put("lastFrame", safeJsonText(bleManager.getZoneLastStatusFrame(zone)));
        return environment;
    }

    private JSONArray buildHostDevicesJson() throws JSONException {
        JSONArray array = new JSONArray();
        List<BleManager.DeviceItem> devices = bleManager.getDevices();
        for (BleManager.DeviceItem item : devices) {
            JSONObject device = new JSONObject();
            device.put("id", item.deviceId);
            device.put("deviceId", item.deviceId);
            device.put("name", item.deviceName);
            device.put("deviceName", item.deviceName);
            device.put("rssi", item.rssi);
            array.put(device);
        }
        return array;
    }

    private JSONObject buildMonitorStateJson() throws JSONException {
        JSONObject monitor = new JSONObject();
        monitor.put("connected", am4100Manager.isConnected());
        monitor.put("active", am4100Manager.isLinkActive());
        monitor.put("scanning", am4100Manager.isScanning());
        monitor.put("ready", am4100Manager.isProtocolReady());
        monitor.put("protocolReady", am4100Manager.isProtocolReady());
        monitor.put("state", am4100Manager.getStateText());
        monitor.put("deviceName", safeJsonText(am4100Manager.getConnectedDeviceName()));
        monitor.put("deviceId", safeJsonText(am4100Manager.getConnectedDeviceId()));
        monitor.put("devices", buildMonitorDevicesJson());
        putCurrentMonitorVitalsJson(monitor);
        monitor.put("map", safeJsonText(am4100Manager.getMapText()));
        monitor.put("ambientTemp", safeJsonText(am4100Manager.getAmbientTempText()));
        monitor.put("objectTemp", safeJsonText(am4100Manager.getObjectTempText()));
        monitor.put("summary", safeJsonText(am4100Manager.getLastFrameSummary()));
        monitor.put("lastError", safeJsonText(am4100Manager.getLastError()));
        monitor.put("waveStats", safeJsonText(am4100Manager.getWaveDiagnosticsText()));
        monitor.put("numericDiag", safeJsonText(am4100Manager.getNumericFrameDiagnosticsText()));
        return monitor;
    }

    /** 波形轻量轮询与完整监护状态共用同一组当前值，避免格式和无效值口径漂移。 */
    private void putCurrentMonitorVitalsJson(JSONObject target) throws JSONException {
        target.put("heartRate", safeJsonText(am4100Manager.getHeartRateText()));
        target.put("bloodPressure", safeJsonText(am4100Manager.getBloodPressureText()));
        target.put("spo2", safeJsonText(am4100Manager.getSpo2Text()));
        target.put("pulse", safeJsonText(am4100Manager.getPulseRateText()));
        target.put("pulseRate", safeJsonText(am4100Manager.getPulseRateText()));
        target.put("bodyTemp", safeJsonText(am4100Manager.getBodyTempText()));
        target.put("resp", safeJsonText(am4100Manager.getRespText()));
    }

    private JSONArray buildMonitorDevicesJson() throws JSONException {
        JSONArray array = new JSONArray();
        List<Am4100Manager.DeviceItem> devices = am4100Manager.getDevices();
        for (Am4100Manager.DeviceItem item : devices) {
            JSONObject device = new JSONObject();
            device.put("id", item.deviceId);
            device.put("deviceId", item.deviceId);
            device.put("name", item.deviceName);
            device.put("deviceName", item.deviceName);
            device.put("rssi", item.rssi);
            array.put(device);
        }
        return array;
    }

    /**
     * 监护测量历史（带时间戳），页面按时间维度查看趋势图时单独取，
     * 不塞进每次都会拉取的实时状态里，避免高频轮询传输大包。
     */
    private String buildMonitorHistoryJson() {
        return MonitorHistoryJson.encode(System.currentTimeMillis(),
                am4100Manager.getMeasurementHistory());
    }

    /**
     * 实时监护的原始波形采样，供网页按真实数据绘制波形曲线。
     * 每个通道只取尾部若干个点，避免跨 JS 桥的数据量过大。
     */
    private String buildWaveStateJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("ecg", buildWaveSamplesJson(am4100Manager.getEcgWaveSamples()));
            root.put("spo2", buildWaveSamplesJson(am4100Manager.getSpo2WaveSamples()));
            root.put("resp", buildWaveSamplesJson(am4100Manager.getRespWaveSamples()));
            root.put("heartRateTrend", buildWaveSamplesJson(am4100Manager.getHeartRateHistory(), TREND_SAMPLE_LIMIT));
            root.put("spo2Trend", buildWaveSamplesJson(am4100Manager.getSpo2History(), TREND_SAMPLE_LIMIT));
            root.put("pulseTrend", buildWaveSamplesJson(am4100Manager.getPulseRateHistory(), TREND_SAMPLE_LIMIT));
            root.put("respTrend", buildWaveSamplesJson(am4100Manager.getRespHistory(), TREND_SAMPLE_LIMIT));
            root.put("nibpSysTrend", buildWaveSamplesJson(am4100Manager.getBloodPressureHistory(), TREND_SAMPLE_LIMIT));
            root.put("nibpDiaTrend", buildWaveSamplesJson(am4100Manager.getBloodPressureDiastolicHistory(), TREND_SAMPLE_LIMIT));
            root.put("tempTrend", buildWaveSamplesJson(am4100Manager.getTemperatureHistory(), TREND_SAMPLE_LIMIT));
            root.put("tempEarTrend", buildWaveSamplesJson(am4100Manager.getEarTemperatureHistory(), TREND_SAMPLE_LIMIT));
            root.put("stats", buildWaveStatsJson());
            // 保留数值字段，供仍按 waveState() 取值的旧页面与回归探针使用。
            // 真正的快速通道走下面的 vitalsState()，不必为大 JSON 买单。
            JSONObject vitals = buildVitalsJson();
            root.put("vitals", vitals);
            root.put("connected", am4100Manager.isConnected());
            return root.toString();
        } catch (JSONException exception) {
            return "{}";
        }
    }

    /**
     * 真正的轻量数值桥：只有六项监护数值与连接位。
     * 不构建波形/趋势数组、不加舱位锁、不读磁盘，供 H5 的 60ms 数值轮询
     * 和原生帧事件即时取数，避免数值显示排在整套波形 JSON 后面。
     */
    private String buildVitalsStateJson() {
        try {
            return buildVitalsJson().toString();
        } catch (JSONException exception) {
            return "{}";
        }
    }

    private JSONObject buildVitalsJson() throws JSONException {
        JSONObject vitals = new JSONObject();
        putCurrentMonitorVitalsJson(vitals);
        vitals.put("connected", am4100Manager.isConnected());
        // H5 按设备隔离断连回显值，换监护仪时不能带出上一台的旧读数。
        vitals.put("deviceId", safeJsonText(am4100Manager.getConnectedDeviceId()));
        return vitals;
    }

    private JSONArray buildWaveSamplesJson(List<Integer> samples) {
        return buildWaveSamplesJson(samples, WAVE_SAMPLE_LIMIT);
    }

    private JSONArray buildWaveSamplesJson(List<Integer> samples, int limit) {
        JSONArray array = new JSONArray();
        if (samples == null || samples.isEmpty()) {
            return array;
        }
        int start = Math.max(0, samples.size() - Math.max(1, limit));
        for (int i = start; i < samples.size(); i += 1) {
            array.put(samples.get(i));
        }
        return array;
    }

    /** 各波形通道的采样统计，前端据此判断通道是真的没有信号，还是只有数值没有波动。 */
    private JSONObject buildWaveStatsJson() throws JSONException {
        JSONObject stats = new JSONObject();
        stats.put("ecg", buildWaveStatJson(am4100Manager.getEcgWaveSamples()));
        stats.put("spo2", buildWaveStatJson(am4100Manager.getSpo2WaveSamples()));
        stats.put("resp", buildWaveStatJson(am4100Manager.getRespWaveSamples()));
        return stats;
    }

    private JSONObject buildWaveStatJson(List<Integer> samples) throws JSONException {
        JSONObject stat = new JSONObject();
        if (samples == null || samples.isEmpty()) {
            stat.put("count", 0);
            stat.put("min", 0);
            stat.put("max", 0);
            stat.put("amplitude", 0);
            return stat;
        }
        int min = samples.get(0).intValue();
        int max = min;
        for (Integer value : samples) {
            if (value.intValue() < min) {
                min = value.intValue();
            }
            if (value.intValue() > max) {
                max = value.intValue();
            }
        }
        stat.put("count", samples.size());
        stat.put("min", min);
        stat.put("max", max);
        stat.put("amplitude", max - min);
        return stat;
    }

    private JSONObject buildWifiStateJson() throws JSONException {
        JSONObject wifi = new JSONObject();
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifiManager == null) {
            wifi.put("enabled", false);
            wifi.put("current", "");
            wifi.put("networks", new JSONArray());
            return wifi;
        }
        wifi.put("enabled", wifiManager.isWifiEnabled());
        WifiInfo info = wifiManager.getConnectionInfo();
        wifi.put("current", info == null ? "" : cleanSsid(info.getSSID()));
        wifi.put("networks", new JSONArray());
        return wifi;
    }

    private String cleanSsid(String ssid) {
        if (ssid == null) {
            return "";
        }
        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() > 1) {
            return ssid.substring(1, ssid.length() - 1);
        }
        return "<unknown ssid>".equals(ssid) ? "" : ssid;
    }

    private String safeJsonText(String value) {
        return value == null ? "" : value;
    }

    /**
     * 处理“保存医院信息”：从 WebView 里读出 .group_4/.group_5/.group_6 三个输入框的当前值，
     * 交给 dashboardView 写入当前机构并落库。
     */
    private void handleOrganizationSaveFromLanhu() {
        if (lanhuWebView == null) {
            return;
        }
        String script =
                "(function(){" +
                "var pick=function(sel){var n=document.querySelector(sel+' input');" +
                "return n&&n.value?n.value:'';};" +
                "return JSON.stringify({name:pick('.group_4')," +
                "address:pick('.group_5'),phone:pick('.group_6')});" +
                "})()";
        lanhuWebView.evaluateJavascript(script, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                final String name;
                final String address;
                final String phone;
                if (value == null || value.equals("null")) {
                    name = "";
                    address = "";
                    phone = "";
                } else {
                    try {
                        // WebView 会把 JS 返回值包一层 JSON 字符串，先用 JSONTokener 解一次。
                        org.json.JSONTokener tokener = new org.json.JSONTokener(value);
                        Object firstValue = tokener.nextValue();
                        JSONObject obj;
                        if (firstValue instanceof JSONObject) {
                            obj = (JSONObject) firstValue;
                        } else if (firstValue instanceof String) {
                            obj = new JSONObject((String) firstValue);
                        } else {
                            return;
                        }
                        name = obj.optString("name", "");
                        address = obj.optString("address", "");
                        phone = obj.optString("phone", "");
                    } catch (org.json.JSONException e) {
                        Log.e(TAG, "parse hospital inputs failed", e);
                        return;
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String result = dashboardView.webUpdateOrganization(name, address, phone);
                        Toast.makeText(MainActivity.this, result, Toast.LENGTH_SHORT).show();
                        notifyLanhuStateChanged();
                    }
                });
            }
        });
    }

    /**
     * 处理“保存仪器信息”：从 WebView 里读出仪器状态页五个输入框的当前值
     * （产品型号/机型/机号/生产日期/程序版本），交给 dashboardView 写入设备档案并落库。
     */
    private void handleDeviceProfileSaveFromLanhu() {
        if (lanhuWebView == null) {
            return;
        }
        String script =
                "(function(){" +
                "var pick=function(sel){var n=document.querySelector(sel+' input');" +
                "return n&&n.value?n.value:'';};" +
                "return JSON.stringify({productModel:pick('.text-wrapper_2')," +
                "machineType:pick('.box_3'),serialNo:pick('.box_5')," +
                "manufactureDate:pick('.section_2'),softwareVersion:pick('.section_3')});" +
                "})()";
        lanhuWebView.evaluateJavascript(script, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                final String productModel;
                final String machineType;
                final String serialNo;
                final String manufactureDate;
                final String softwareVersion;
                if (value == null || value.equals("null")) {
                    productModel = "";
                    machineType = "";
                    serialNo = "";
                    manufactureDate = "";
                    softwareVersion = "";
                } else {
                    try {
                        // WebView 会把 JS 返回值包一层 JSON 字符串，先用 JSONTokener 解一次。
                        org.json.JSONTokener tokener = new org.json.JSONTokener(value);
                        Object firstValue = tokener.nextValue();
                        JSONObject obj;
                        if (firstValue instanceof JSONObject) {
                            obj = (JSONObject) firstValue;
                        } else if (firstValue instanceof String) {
                            obj = new JSONObject((String) firstValue);
                        } else {
                            return;
                        }
                        productModel = obj.optString("productModel", "");
                        machineType = obj.optString("machineType", "");
                        serialNo = obj.optString("serialNo", "");
                        manufactureDate = obj.optString("manufactureDate", "");
                        softwareVersion = obj.optString("softwareVersion", "");
                    } catch (org.json.JSONException e) {
                        Log.e(TAG, "parse device profile inputs failed", e);
                        return;
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String result = dashboardView.webUpdateDeviceProfile(productModel, machineType, serialNo,
                                manufactureDate, softwareVersion);
                        Toast.makeText(MainActivity.this, result, Toast.LENGTH_SHORT).show();
                        notifyLanhuStateChanged();
                    }
                });
            }
        });
    }

    private final class LanhuNativeBridge {
        @JavascriptInterface
        public String bleState() {
            return buildBleStateJson();
        }

        @JavascriptInterface
        public String waveState() {
            return buildWaveStateJson();
        }

        /** 120ms 数值轮询专用：只回当前六项数值，不序列化波形/趋势大数组。 */
        @JavascriptInterface
        public String vitalsState() {
            return buildVitalsStateJson();
        }

        @JavascriptInterface
        public String monitorHistory() {
            return buildMonitorHistoryJson();
        }

        @JavascriptInterface
        public String thermalState() {
            return thermalConnectionState;
        }

        @JavascriptInterface
        public String thermalDevices() {
            return buildThermalDevicesJson();
        }

        @JavascriptInterface
        public String thermalDevice() {
            return buildThermalDeviceJson();
        }

        @JavascriptInterface
        public void thermalFullscreen(final boolean visible) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setThermalFullscreenVisible(visible);
                }
            });
        }

        @JavascriptInterface
        public void thermalAction(final String action, final String value) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if ("scan".equals(action)) {
                        connectThermalBridge();
                    } else if ("connect".equals(action)) {
                        connectThermalDevice(value);
                    } else if ("disconnect".equals(action)) {
                        thermalConnectRequested = false;
                        pendingThermalDeviceId = "";
                        // 手动断开不是掉线：先记成已断开，别弹断开提示
                        thermalConnected = false;
                        if (thermalBleManager != null) {
                            thermalBleManager.stop();
                        }
                        onThermalConnectionChanged("红外测温已手动断开");
                    }
                }
            });
        }

        @JavascriptInterface
        public void bleAction(final String target, final String action, final String value) {
            Log.i(TAG, "native bleAction=" + target + "/" + action + "/" + value);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    handleBleAction(target, action, value);
                    notifyLanhuStateChanged();
                }
            });
        }

        @JavascriptInterface
        public String firstPhaseState() {
            return dashboardView.buildFirstPhaseStateJson();
        }

        @JavascriptInterface
        public String patientState() {
            return dashboardView.buildPatientStateJson();
        }

        @JavascriptInterface
        public void syncTempPatient(final String payload) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    dashboardView.syncTempPatientFromWeb(payload);
                    notifyLanhuStateChanged();
                }
            });
        }

        @JavascriptInterface
        public String action(final String action, final String text, final String path) {
            Log.i(TAG, "native action=" + action + " text=" + text);
            if ("pdf_download".equals(action)) {
                final IcuDashboardView.PdfDownloadRequest request =
                        dashboardView.acceptPdfDownloadRequest();
                if (request.accepted) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dashboardView.performPdfDownloadAction(request.requestId);
                        }
                    });
                }
                // 同步回执只证明原生已受理或明确拒绝；文件结果仍由 printStatus 分代回推。
                return request.receiptJson();
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // 医院信息页的“保存”需要读取页面内三个输入框的当前值，
                    // 由本层直接回调 WebView 取值后写入 native。
                    if ("organization_save".equals(action)) {
                        handleOrganizationSaveFromLanhu();
                        return;
                    }
                    // 仪器状态页的“保存”同样需要读取页面内五个输入框的当前值，
                    // 由本层回调 WebView 取值后写入 native。
                    if ("device_profile_save".equals(action)) {
                        handleDeviceProfileSaveFromLanhu();
                        return;
                    }
                    // 页面上的登录入口：统一回到登录页
                    if ("app_login".equals(action)) {
                        if (lanhuWebView != null) {
                            lanhuWebView.loadUrl(LOGIN_URL);
                        }
                        return;
                    }
                    if (!dashboardView.performNativeAction(action, text)) {
                        Toast.makeText(MainActivity.this, safeJsonText(text), Toast.LENGTH_SHORT).show();
                    }
                    notifyLanhuStateChanged();
                }
            });
            return "";
        }

        @JavascriptInterface
        public void cameraPreview(final float left, final float top, final float width, final float height,
                                  final float viewportWidth, final float viewportHeight, final boolean visible) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateLanhuCameraPreview(left, top, width, height, viewportWidth, viewportHeight, visible);
                }
            });
        }

        @JavascriptInterface
        public boolean cameraConfigured() {
            return dashboardView.isCameraConfiguredForWeb();
        }

        @JavascriptInterface
        public String cameraSnapshots() {
            return dashboardView.buildCameraSnapshotListJson();
        }

        @JavascriptInterface
        public boolean cameraSelectSnapshot(int index) {
            dashboardView.selectCameraSnapshot(index);
            return true;
        }

        @JavascriptInterface
        public boolean cameraRecording() {
            return dashboardView.isCameraRecording();
        }

        /**
         * 使用教程页读取本机素材：图片、视频与文档按时间倒序合并后的前若干条，
         * 同时带上内容指纹，页面据此判断教程资料有没有更新。
         */
        @JavascriptInterface
        public String galleryItems() {
            if (System.currentTimeMillis() - galleryUpdatedAt > GALLERY_CACHE_MS) {
                refreshGalleryItems();
            }
            return galleryJsonWithPermissionState();
        }

        /** 取一条素材的缩略图（data: 地址），网页按需单独调用。 */
        @JavascriptInterface
        public String galleryThumbnail(String uri) {
            return TutorialMediaLibrary.thumbnail(MainActivity.this, uri);
        }

        @JavascriptInterface
        public void requestGalleryPermission() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    requestGalleryPermissions();
                }
            });
        }

        /** 上一次记录下来的素材指纹，页面用它判断本机教程资料是否更新。 */
        @JavascriptInterface
        public String tutorialFingerprint() {
            return getSharedPreferences(TUTORIAL_PREFS, 0)
                    .getString(KEY_TUTORIAL_FINGERPRINT, "");
        }

        /** 记录当前素材指纹，下次进入教程页就不会重复提示。 */
        @JavascriptInterface
        public void recordTutorialFingerprint(String fingerprint) {
            if (fingerprint == null || fingerprint.isEmpty()) {
                return;
            }
            getSharedPreferences(TUTORIAL_PREFS, 0).edit()
                    .putString(KEY_TUTORIAL_FINGERPRINT, fingerprint).apply();
        }

        /** 权限被永久拒绝时，把用户送到系统设置页自行开启。 */
        @JavascriptInterface
        public void openAppPermissionSettings() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception error) {
                        Toast.makeText(MainActivity.this, "无法打开系统设置", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        /**
         * 打开"所有文件访问权限"设置页。
         * Android 11 之后 MediaStore 只把媒体文件暴露给普通应用，
         * 教程页要自动列出本机文档必须由用户在这里手动授权。
         */
        @JavascriptInterface
        public void openAllFilesAccessSettings() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception error) {
                        Toast.makeText(MainActivity.this, "无法打开权限设置", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        /**
         * 登录：与原版登录页 lanhu_denglu/index.html 的调用约定保持一致，
         * 返回 true 表示登录成功，页面据此跳回主页面。
         */
        @JavascriptInterface
        public boolean login(String userName, String password) {
            AccountStore.Result result = AccountStore.login(MainActivity.this, userName, password);
            if (result.ok) {
                applyAccountUser();
            }
            return result.ok;
        }

        /**
         * 注册：账号名唯一、口令至少 4 位，所属机构可留空。
         * 注册成功后直接登录，页面拿到 "ok" 才会跳转。
         */
        @JavascriptInterface
        public String register(String userName, String password, String organization) {
            AccountStore.Result result = AccountStore.register(MainActivity.this, userName, password, organization);
            if (!result.ok) {
                return result.message;
            }
            AccountStore.Result login = AccountStore.login(MainActivity.this, userName, password);
            if (!login.ok) {
                return login.message;
            }
            applyAccountUser();
            return "ok";
        }

        /** 是否已登录：登录页用它决定要不要跳回主页面。 */
        @JavascriptInterface
        public boolean isLoggedIn() {
            return AccountStore.isLoggedIn(MainActivity.this);
        }

        // 使用教程页：读取内置/已更新的教程清单，并支持后台推送后的更新提示
        @JavascriptInterface
        public String tutorialManifest() {
            return TutorialManager.manifestJson(MainActivity.this);
        }

        @JavascriptInterface
        public String tutorialUpdateStatus() {
            return TutorialManager.updateStatusJson(MainActivity.this);
        }

        @JavascriptInterface
        public String applyTutorialUpdate() {
            return TutorialManager.applyUpdateJson(MainActivity.this);
        }

        /**
         * 打开教程文档：PDF 走内置预览，纯文本与 Word 直接返回正文，
         * 其余类型交给系统应用。返回 JSON，页面按 kind 分流。
         */
        @JavascriptInterface
        public String openTutorialDocument(String uri, String title) {
            return TutorialDocumentOpener.open(MainActivity.this, uri, title);
        }

        /**
         * 教程页点「导入教程文档」：拉起系统文件选择器，
         * 选中的文件会导入到教程资料库并立刻刷新页面。
         */
        @JavascriptInterface
        public void importTutorialDocument() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    TutorialDocumentImporter.launch(MainActivity.this);
                }
            });
        }

        /** 打开本机相册里的教程素材：图片直接看，视频直接播。 */
        @JavascriptInterface
        public void openTutorialMedia(final String uri, final String title, final boolean video) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    TutorialMediaDialog.show(MainActivity.this, Uri.parse(uri), title, video);
                }
            });
        }

        @JavascriptInterface
        public String touchGeometry() {
            TouchSnapshot snapshot = lanhuWebView == null ? TouchSnapshot.EMPTY : lanhuWebView.snapshot();
            JSONObject root = new JSONObject();
            try {
                root.put("localX", snapshot.localX);
                root.put("localY", snapshot.localY);
                root.put("rawX", snapshot.rawX);
                root.put("rawY", snapshot.rawY);
                root.put("originX", snapshot.originX);
                root.put("originY", snapshot.originY);
                root.put("viewWidth", snapshot.viewWidth);
                root.put("viewHeight", snapshot.viewHeight);
                root.put("density", snapshot.density);
            } catch (JSONException exception) {
                return "";
            }
            return root.toString();
        }

        @JavascriptInterface
        public void log(final String message) {
            Log.i(TAG, "web " + safeJsonText(message));
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
            Log.i(TAG, "touch action=" + action + " xy=" + event.getX() + "," + event.getY());
        }
        return super.dispatchTouchEvent(event);
    }

    private void handleBleAction(String target, String action, String value) {
        if ("wifi".equals(target)) {
            if ("settings".equals(action) || "scan".equals(action)) {
                try {
                    startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                } catch (Exception exception) {
                    Toast.makeText(this, "无法打开系统 WiFi 设置", Toast.LENGTH_SHORT).show();
                }
            }
            return;
        }
        if ("host".equals(target)) {
            handleHostBleAction(action, value);
            return;
        }
        if ("monitor".equals(target)) {
            handleMonitorBleAction(action, value);
        }
    }

    private void handleHostBleAction(String action, String value) {
        if ("scan".equals(action)) {
            if (hasScanPermission()) {
                bleManager.startScan();
            } else {
                requestBlePermissionsFromUi();
            }
            return;
        }
        if ("connect".equals(action) && value != null && value.length() > 0) {
            bleManager.connect(value);
            return;
        }
        if ("disconnect".equals(action)) {
            bleManager.disconnect();
            return;
        }
        if ("readAll".equals(action)) {
            bleManager.readAllStatus();
        }
    }

    private void handleMonitorBleAction(String action, String value) {
        if ("scan".equals(action)) {
            if (hasScanPermission()) {
                am4100Manager.startScan();
            } else {
                requestBlePermissionsFromUi();
            }
            return;
        }
        if ("connect".equals(action) && value != null && value.length() > 0) {
            am4100Manager.connect(value);
            return;
        }
        if ("disconnect".equals(action)) {
            am4100Manager.disconnect();
        }
    }

    private void connectThermalBridge() {
        if (thermalBleManager == null) {
            Toast.makeText(this, "红外测温模块未初始化", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasAllBlePermissions()) {
            thermalConnectRequested = true;
            pendingThermalDeviceId = "";
            requestBlePermissionsFromUi();
            return;
        }
        thermalConnectRequested = false;
        pendingThermalDeviceId = "";
        thermalBleManager.stop();
        thermalBleManager.start();
    }

    private void connectThermalDevice(String deviceId) {
        if (thermalBleManager == null) {
            Toast.makeText(this, "红外测温模块未初始化", Toast.LENGTH_SHORT).show();
            return;
        }
        if (deviceId == null || deviceId.trim().isEmpty()) {
            return;
        }
        if (!hasAllBlePermissions()) {
            thermalConnectRequested = true;
            pendingThermalDeviceId = deviceId;
            requestBlePermissionsFromUi();
            return;
        }
        thermalConnectRequested = false;
        pendingThermalDeviceId = "";
        thermalBleManager.connect(deviceId);
    }

    private String buildThermalDevicesJson() {
        JSONArray array = new JSONArray();
        if (thermalBleManager != null) {
            for (ThermalBleManager.DeviceItem device : thermalBleManager.getDevices()) {
                try {
                    JSONObject item = new JSONObject();
                    item.put("id", device.address);
                    item.put("name", device.name);
                    item.put("rssi", device.rssi);
                    array.put(item);
                } catch (JSONException ignored) {
                    // 单个设备字段异常不影响其余设备
                }
            }
        }
        return array.toString();
    }

    /**
     * 当前红外测温桥的信息，供网页显示设备编号与连接状态。
     */
    private String buildThermalDeviceJson() {
        try {
            JSONObject json = new JSONObject();
            if (thermalBleManager == null) {
                json.put("connected", false);
                json.put("address", "");
                json.put("name", "");
                return json.toString();
            }
            json.put("connected", thermalBleManager.isConnected());
            json.put("address", safeJsonText(thermalBleManager.getConnectedAddress()));
            json.put("name", safeJsonText(thermalBleManager.getConnectedName()));
            return json.toString();
        } catch (JSONException exception) {
            return "{}";
        }
    }

    private void setThermalFullscreenVisible(boolean visible) {
        thermalFullscreenVisible = visible;
        if (cameraInlinePreviewView == null) {
            return;
        }
        if (visible) {
            cameraInlinePreviewView.setActive(false);
            cameraInlinePreviewView.setVisibility(View.GONE);
        } else {
            cameraInlinePreviewView.setVisibility(View.VISIBLE);
            restoreInlineCameraPreview();
        }
    }

    private void restoreInlineCameraPreview() {
        if (cameraInlinePreviewView == null || thermalFullscreenVisible) {
            return;
        }
        if (lastInlineCameraBounds == null || lastInlineCameraUrls == null) {
            return;
        }
        updateInlineCameraPreview(lastInlineCameraBounds, true, lastInlineCameraUrls);
    }
}
