package com.example.icuapk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.text.TextUtils;

/**
 * 蓝牙"记住设备 + 自动重连"控制器。
 *
 * 现场的设备（主机、监护仪）是固定装在床位的，操作者只需要连一次；
 * 之后每次打开 App、或者蓝牙被重新打开，都应该自动接回去，
 * 不该让人再手动扫一遍设备列表。
 *
 * 控制器只负责"连哪一台、隔多久连、还要不要连"，真正的 GATT 流程仍然由各自的管理器负责：
 * 1. 记住上次连过的设备地址，冷启动（进程被杀）之后依然有效；
 * 2. 意外掉线（设备重启、超出范围、协议异常）后按退避间隔自动重连，越掉越稀疏；
 * 3. 用户主动点"断开"后本次运行不再自动重连，尊重用户意图，
 *    但记住的设备仍然保留，下次打开 App 还会自动连上。
 *
 * 线程模型：BLE 回调与界面点击都在主线程，控制器的方法也都在主线程调用，不需要加锁。
 */
final class BleAutoReconnectController {
    /** 由具体的管理器执行真正的连接。 */
    interface Connector {
        /**
         * @param address    要连接的设备地址
         * @param automatic  true 表示这次是掉线后的自动重连，不是用户在设备列表里点的
         */
        void connectDevice(String address, boolean automatic);
    }

    /** 重连状态变化时回调给管理器，用来刷新界面上的连接状态文字。 */
    interface StateListener {
        void onAutoReconnectStateChanged(boolean waiting, int attempt);
    }

    private static final String PREFS_NAME = "icu_ble_autoreconnect";
    private static final String KEY_ADDRESS = "last_address";
    private static final String KEY_NAME = "last_name";
    /** 断线后的重连间隔：越往后越慢，最后固定 30 秒一轮，长期守着设备。 */
    private static final long[] BACKOFF_MS = {2000L, 5000L, 10000L, 20000L, 30000L};
    /** 一次连接尝试的超时；超过这个时间还没连上就当失败，交给退避重试。 */
    private static final long CONNECT_TIMEOUT_MS = 15000L;

    private final SharedPreferences prefs;
    private final String role;
    private final Connector connector;
    private final Handler handler;
    private final StateListener listener;

    private String targetAddress = "";
    private int attempt = 0;
    private boolean waiting = false;
    private boolean connecting = false;
    private boolean paused = false;
    /** 蓝牙被关掉：这段时间不安排重连，等蓝牙打开再继续。 */
    private boolean bluetoothOff = false;

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            waiting = false;
            startAttempt();
        }
    };

    private final Runnable timeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!connecting) {
                return;
            }
            connecting = false;
            onAttemptFailed();
        }
    };

    BleAutoReconnectController(Context context, String role, Connector connector,
                               Handler handler, StateListener listener) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.role = role;
        this.connector = connector;
        this.handler = handler;
        this.listener = listener;
        this.targetAddress = readPref(KEY_ADDRESS);
    }

    /** 用户在设备列表里点了某个设备：先记住它，之后自动重连就连这一台。 */
    void onManualConnect(String address, String name) {
        if (TextUtils.isEmpty(address)) {
            return;
        }
        remember(address, name);
        paused = false;
        attempt = 0;
        cancelRetry();
        connecting = true;
        startTimeout();
    }

    /**
     * 真正的连接尝试开始了：重新计时。
     *
     * <p>自动重连会先扫描把设备找回来，扫描这段时间不算连接尝试；
     * 真正发起连接的时候才把计时重置，否则扫描期间用掉的超时
     * 会把这次连接误判成失败。</p>
     */
    void onAttemptStarted() {
        if (paused || TextUtils.isEmpty(targetAddress)) {
            return;
        }
        connecting = true;
        attempt = 0;
        cancelRetry();
        handler.removeCallbacks(timeoutRunnable);
        handler.postDelayed(timeoutRunnable, CONNECT_TIMEOUT_MS);
    }

    /**
     * 自动重连进入"扫描等待"阶段：此时还没有真正发起连接，不计时。
     *
     * <p>设备重新上电之后才会恢复广播，扫描可能一直等下去，
     * 这段时间不能算作一次失败的连接尝试，否则退避重试会没完没了。
     * 真正扫到设备、发起连接时由 {@link #onAttemptStarted()} 重新开始计时。</p>
     */
    void onScanStarted() {
        if (paused || TextUtils.isEmpty(targetAddress)) {
            return;
        }
        connecting = true;
        attempt = 0;
        cancelRetry();
        handler.removeCallbacks(timeoutRunnable);
    }

    /** 扫描没能启动：按失败处理，退避之后再来一轮。 */
    void onScanFailed() {
        connecting = false;
        onAttemptFailed();
    }

    /** 连接成功：记住设备，清掉退避计数。 */
    void onConnected(String address, String name) {
        if (!TextUtils.isEmpty(address)) {
            remember(address, name);
        }
        connecting = false;
        attempt = 0;
        cancelRetry();
        handler.removeCallbacks(timeoutRunnable);
        notifyState(false);
    }

    /** 连接断开（设备重启、超出范围等）：按退避策略安排下一次重连。 */
    void onDisconnected() {
        connecting = false;
        handler.removeCallbacks(timeoutRunnable);
        if (paused || bluetoothOff || TextUtils.isEmpty(targetAddress)) {
            notifyState(false);
            return;
        }
        if (waiting) {
            // 已经在等重连了，重复的断开回调不要把退避间隔又重置回去。
            return;
        }
        scheduleRetry();
    }

    /** 用户主动断开：本次运行不再自动重连；记住的设备保留，下次打开 App 仍然自动连。 */
    void onUserDisconnect() {
        paused = true;
        connecting = false;
        cancelRetry();
        handler.removeCallbacks(timeoutRunnable);
        notifyState(false);
    }

    /** App 启动或蓝牙重新打开时调用：能连就连。 */
    void tryAutoConnect() {
        bluetoothOff = false;
        if (paused || connecting || waiting) {
            return;
        }
        if (TextUtils.isEmpty(targetAddress)) {
            return;
        }
        attempt = 0;
        startAttempt();
    }

    /** 蓝牙被关闭：停掉等待中的重连，等蓝牙重新打开再连。 */
    void onBluetoothOff() {
        bluetoothOff = true;
        connecting = false;
        cancelRetry();
        handler.removeCallbacks(timeoutRunnable);
        notifyState(false);
    }

    /** 页面销毁时调用：停掉定时器，但保留记住的设备。 */
    void release() {
        connecting = false;
        cancelRetry();
        handler.removeCallbacks(timeoutRunnable);
    }

    /** 设备地址非法这类永久性错误：忘掉这台设备，别再无休止地重试。 */
    void forget() {
        targetAddress = "";
        attempt = 0;
        waiting = false;
        connecting = false;
        paused = false;
        cancelRetry();
        handler.removeCallbacks(timeoutRunnable);
        prefs.edit()
                .remove(role + "_" + KEY_ADDRESS)
                .remove(role + "_" + KEY_NAME)
                .apply();
    }

    /** 记住的设备地址，空串表示还没连过任何设备。 */
    String getSavedAddress() {
        return targetAddress;
    }

    /** 记住的设备名称。 */
    String getSavedName() {
        return readPref(KEY_NAME);
    }

    /** 是否正在等待自动重连。 */
    boolean isWaiting() {
        return waiting;
    }

    /** 扫描、连接尝试或退避等待都属于可取消的连接活动，不等于协议已连接。 */
    boolean isActive() {
        return connecting || waiting;
    }

    private void startAttempt() {
        String address = targetAddress;
        if (TextUtils.isEmpty(address)) {
            return;
        }
        connecting = true;
        cancelRetry();
        // 先留一个超时兜底：扫描没真正跑起来（比如权限还没给）时，
        // 这一轮会被判成失败并退避重试，不会把自动重连卡死。
        // 扫描一旦跑起来，onScanStarted() 会把这个超时撤掉，
        // 真正发起连接时再由 onAttemptStarted() 重新计时。
        startTimeout();
        connector.connectDevice(address, true);
    }

    private void startTimeout() {
        handler.removeCallbacks(timeoutRunnable);
        handler.postDelayed(timeoutRunnable, CONNECT_TIMEOUT_MS);
    }

    /** 一次尝试超时，按失败处理，交给退避重试。 */
    private void onAttemptFailed() {
        handler.removeCallbacks(timeoutRunnable);
        if (paused || bluetoothOff || TextUtils.isEmpty(targetAddress)) {
            return;
        }
        scheduleRetry();
    }

    private void scheduleRetry() {
        cancelRetry();
        long delay = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
        attempt++;
        waiting = true;
        notifyState(true);
        handler.postDelayed(retryRunnable, delay);
    }

    private void cancelRetry() {
        waiting = false;
        handler.removeCallbacks(retryRunnable);
    }

    private void remember(String address, String name) {
        targetAddress = address;
        prefs.edit()
                .putString(role + "_" + KEY_ADDRESS, address)
                .putString(role + "_" + KEY_NAME, name == null ? "" : name)
                .apply();
    }

    private String readPref(String key) {
        String value = prefs.getString(role + "_" + key, "");
        return value == null ? "" : value;
    }

    private void notifyState(boolean waitingNow) {
        if (listener != null) {
            listener.onAutoReconnectStateChanged(waitingNow, attempt);
        }
    }
}
