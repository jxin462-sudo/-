package com.example.icuapk;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;


import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import androidx.annotation.NonNull;

@SuppressLint({"NewApi", "MissingPermission"})
@SuppressWarnings({"unused", "Java8ListSort"})
public class Am4100Manager {
    public interface Listener {
        void onStateChanged();
    }

    public static final UUID AM4100_SERVICE_UUID = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455");
    public static final UUID AM4100_NOTIFY_UUID = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616");
    public static final UUID AM4100_WRITE_UUID = UUID.fromString("49535343-8841-43F4-A8D4-ECBE34729BB3");
    private static final UUID CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    /** 蓝牙设置存档：与主机共用同一个文件，靠键名前缀区分是主机还是监护仪的记录。 */
    private static final String BLE_SETTINGS_PREFS = "icu_ble_settings";
    private static final long SCAN_TIMEOUT_MS = 10000L;
    /**
     * 扫描时一条设备记录多久没再出现就算过期。
     *
     * <p>周边 BLE 设备很多，列表不清理会越积越多；清理太勤又会让使用者
     * 正在找的设备凭空消失。这个时间要明显大于广播间隔（通常 1 秒以内）。</p>
     */
    private static final long DEVICE_STALE_MS = 15000L;
    /**
     * 自动重连扫描的保活间隔。
     *
     * <p>部分机型对长时间不间断的扫描会静默失效，扫描停了却不会回调失败，
     * 结果就是监护仪重新上电了 App 也扫不到。定期重启一次扫描来规避这个问题。</p>
     */
    private static final long AUTO_RECONNECT_SCAN_KEEP_ALIVE_MS = 15000L;
    /**
     * 链路静默判定：监护仪被直接断电时系统不一定回调 GATT 断开，
     * 超过这个时间没有收到任何数据就认为链路真的断了。
     */
    private static final long LINK_SILENCE_TIMEOUT_MS = 20000L;
    /** 静默看门狗的检查间隔。 */
    private static final long LINK_WATCHDOG_INTERVAL_MS = 5000L;
    private static final long WAVE_UI_EMIT_INTERVAL_MS = 90L;
    private static final long WAVE_LOG_INTERVAL_MS = 1500L;
    private static final long DIAGNOSTIC_LOG_INTERVAL_MS = 3000L;
    private static final int MAX_LOG_COUNT = 80;
    private static final int MAX_WAVE_SAMPLES = 720;
    /** 会话留档保留的波形点数：够画一整页报告，又不会把内存吃满。 */
    private static final int MAX_SESSION_WAVE_SAMPLES = 4096;
    /** 采样恒定说明该通道没有真实波形信号，阈值与前端保持一致。 */
    private static final int WAVE_FLAT_AMPLITUDE = 2;
    /** AM4100 帧类型：波形帧与数值帧分开统计，波形“不动”时可据此定位是哪一种情况。 */
    private static final int FRAME_TYPE_ECG_WAVE = 0x01;
    private static final int FRAME_TYPE_HEART_RESP = 0x02;
    private static final int FRAME_TYPE_NIBP = 0x03;
    private static final int FRAME_TYPE_SPO2_PULSE = 0x04;
    private static final int FRAME_TYPE_TEMP = 0x05;
    private static final int FRAME_TYPE_STATUS = 0x06;
    private static final int FRAME_TYPE_PLETH_WAVE = 0xFE;
    private static final int FRAME_TYPE_RESP_WAVE = 0xFF;

    public static final class DeviceItem {
        public final String deviceId;
        public final String deviceName;
        public final int rssi;

        /** 最后一次扫到它的时间，用来判断这条记录是不是已经过期。 */
        public final long lastSeenMs;

        public DeviceItem(String deviceId, String deviceName, int rssi) {
            this(deviceId, deviceName, rssi, System.currentTimeMillis());
        }

        public DeviceItem(String deviceId, String deviceName, int rssi, long lastSeenMs) {
            this.deviceId = deviceId;
            this.deviceName = deviceName;
            this.rssi = rssi;
            this.lastSeenMs = lastSeenMs;
        }
    }

    /** 测量参数序号，与监护上下限下标保持一致，前端按序号取数画趋势图。 */
    public static final int PARAM_HEART_RATE = 0;
    public static final int PARAM_BP_SYS = 1;
    public static final int PARAM_BP_DIA = 2;
    public static final int PARAM_PULSE = 3;
    public static final int PARAM_SPO2 = 4;
    public static final int PARAM_TEMP = 5;
    public static final int PARAM_RESP = 6;

    /** 一次带时间戳的测量值：历史趋势要按真实时间轴展示，只记数值看不出变化节奏。 */
    public static final class VitalSample {
        public final long time;
        public final int param;
        public final double value;

        VitalSample(long time, int param, double value) {
            this.time = time;
            this.param = param;
            this.value = value;
        }
    }

    private final Context appContext;
    private final BluetoothAdapter bluetoothAdapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<DeviceItem> devices = new ArrayList<>();
    private final ArrayList<String> logs = new ArrayList<>();
    private final ArrayList<Integer> frameBuffer = new ArrayList<>();
    private final ArrayList<Integer> ecgWaveSamples = new ArrayList<>();
    private final ArrayList<Integer> spo2WaveSamples = new ArrayList<>();
    private final ArrayList<Integer> respWaveSamples = new ArrayList<>();
    /**
     * 本次监护会话的波形留档。
     *
     * 实时窗口只留最近 MAX_WAVE_SAMPLES 个点，监护仪一断就被清空；
     * 报告要画的是"这一段治疗真实采到的波形"，所以另存一份更长的留档，
     * 断开之后照样能出图，不会退化成一条基线。
     */
    private final ArrayList<Integer> sessionEcgWaveSamples = new ArrayList<>();
    private final ArrayList<Integer> sessionSpo2WaveSamples = new ArrayList<>();
    private final ArrayList<Integer> sessionRespWaveSamples = new ArrayList<>();
    private final ArrayList<Integer> heartRateHistory = new ArrayList<>();
    private final ArrayList<Integer> spo2History = new ArrayList<>();
    private final ArrayList<Integer> pulseRateHistory = new ArrayList<>();
    private final ArrayList<Integer> bloodPressureHistory = new ArrayList<>();
    private final ArrayList<Integer> temperatureHistory = new ArrayList<>();
    private final ArrayList<Integer> respHistory = new ArrayList<>();
    private final ArrayList<Integer> bloodPressureDiastolicHistory = new ArrayList<>();
    private final ArrayList<Integer> earTemperatureHistory = new ArrayList<>();
    private final MeasurementHistoryBuffer measurementHistory = new MeasurementHistoryBuffer();
    private final Object waveLock = new Object();
    private Listener listener;
    /** "记住设备 + 自动重连"控制器：App 重启或掉线后自动连回上次那台监护仪。 */
    private final BleAutoReconnectController autoReconnect;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic notifyCharacteristic;
    private BluetoothGattCharacteristic writeCharacteristic;
    private boolean scanning;
    /** 自动重连要找回来的监护仪地址。 */
    private String autoReconnectTargetAddress = "";
    /** 自动重连扫描是否在运行：监护仪重新开机后靠它被重新发现。 */
    private boolean autoReconnectScanning;
    private boolean connected;
    private boolean protocolReady;
    private String stateText = "未连接";
    private String connectedDeviceName = "";
    private String connectedDeviceId = "";
    private String lastError = "";
    private String lastFrameSummary = "尚未收到 AM4100 数据";
    private String lastRawFrame = "";
    private int spo2 = -1;
    private int pulseRate = -1;
    private int heartRate = -1;
    private int sys = -1;
    private double map = -1.0;
    private int dia = -1;
    private int resp = -1;
    private String earTemp = "--";
    private String bodyTemp = "--";
    private String ambientTemp = "--";
    private String objectTemp = "--";
    private String statusOne = "--";
    private String statusTwo = "--";
    private String statusThree = "--";
    private boolean waveEmitPending;
    private long lastWaveEmitMs;
    private long lastWaveLogMs;
    /** 最近一次收到监护仪数据的时刻，静默看门狗用它判断链路是否还活着。 */
    private long lastNotifyAtMs;
    private final int[] frameTypeCounts = new int[256];
    private long lastDiagnosticLogMs;
    /**
     * 数值帧级诊断：区分"设备还没给有效帧"和"App 收到有效帧但没显示出来"。
     * 只记录时间戳与计数，不参与任何数值计算、报警判断或协议解析，
     * 因此不会改动原厂蓝牙协议的有效值口径。
     */
    private final long[] lastFrameAtMs = new long[256];
    private final long[] lastValidAtMs = new long[7];
    private final long[] invalidCount = new long[7];
    private long lastReadyAtMs;
    private int lastRejectedParam = -1;
    private double lastRejectedValue = Double.NaN;
    private long lastRejectedAtMs;

    private final Runnable scanTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!scanning) {
                return;
            }
            stopScan();
            addLog("AM4100 扫描已自动停止");
            emit();
        }
    };
    private final Runnable waveEmitRunnable = new Runnable() {
        @Override
        public void run() {
            waveEmitPending = false;
            lastWaveEmitMs = System.currentTimeMillis();
            emit();
        }
    };

    public Am4100Manager(Context context) {
        appContext = context.getApplicationContext();
        BluetoothManager bluetoothManager = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        autoReconnect = new BleAutoReconnectController(appContext, "monitor",
                new BleAutoReconnectController.Connector() {
                    @Override
                    public void connectDevice(String address, boolean automatic) {
                        doConnect(address, automatic);
                    }
                }, handler, new BleAutoReconnectController.StateListener() {
                    @Override
                    public void onAutoReconnectStateChanged(boolean waiting, int attempt) {
                        if (waiting && !connected) {
                            stateText = attempt > 0 ? ("自动重连中(" + attempt + ")") : "自动重连中";
                            addLog("AM4100 掉线后自动重连，第 " + attempt + " 次");
                        }
                        emit();
                    }
                });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public List<DeviceItem> getDevices() {
        pruneStaleDevices();
        synchronized (devices) {
            // AM（监护仪）/ EM（主机）开头的设备优先显示；组内保持发现顺序。
            List<DeviceItem> preferred = new ArrayList<>();
            List<DeviceItem> others = new ArrayList<>();
            for (DeviceItem item : devices) {
                if (DeviceNameOrder.isPreferred(item.deviceName)) {
                    preferred.add(item);
                } else {
                    others.add(item);
                }
            }
            preferred.addAll(others);
            return preferred;
        }
    }

    /**
     * 把新发现的设备追加到列表末尾。
     *
     * <p>扫描期间列表顺序保持不变：附近设备一多，操作者要往下翻才能
     * 找到自己的机器，这时候新设备插队会把刚找到的那台挤走。
     * 信号强度逐行显示，距离由操作者自己判断。</p>
     */
    private void appendDiscoveredDevice(DeviceItem item) {
        devices.add(item);
    }

    /**
     * 剔除扫描期间长时间没再出现的设备。
     *
     * <p>只在扫描进行中清理：扫描一停，列表就保持原样，
     * 使用者还能从容点选刚扫到的那台设备。</p>
     */
    private void pruneStaleDevices() {
        if (!scanning) {
            return;
        }
        long deadline = System.currentTimeMillis() - DEVICE_STALE_MS;
        synchronized (devices) {
            for (int i = devices.size() - 1; i >= 0; i--) {
                if (devices.get(i).lastSeenMs < deadline) {
                    devices.remove(i);
                }
            }
        }
    }

    public List<String> getLogs() {
        synchronized (logs) {
            return new ArrayList<>(logs);
        }
    }

    public boolean isScanning() {
        return scanning;
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isProtocolReady() {
        return protocolReady;
    }

    /** 扫描、GATT/服务发现、自动重连等待和已连接都可通过同一个开关取消。 */
    public boolean isLinkActive() {
        return connected || scanning || autoReconnectScanning || bluetoothGatt != null
                || autoReconnect.isActive();
    }

    public String getStateText() {
        return stateText;
    }

    public String getConnectedDeviceName() {
        return connectedDeviceName;
    }

    public String getConnectedDeviceId() {
        return connectedDeviceId;
    }

    public String getLastError() {
        return lastError;
    }

    public String getLastFrameSummary() {
        return lastFrameSummary;
    }

    public String getLastRawFrame() {
        return lastRawFrame;
    }

    public String getHeartRateText() {
        return intText(heartRate);
    }

    public int getHeartRateValue() {
        return heartRate;
    }

    public String getPulseRateText() {
        return intText(pulseRate);
    }

    public int getPulseRateValue() {
        return pulseRate;
    }

    public String getSpo2Text() {
        return intText(spo2);
    }

    public int getSpo2Value() {
        return spo2;
    }

    public String getBloodPressureText() {
        if (sys < 0 || dia < 0) {
            return "--/--";
        }
        return sys + "/" + dia;
    }

    /** 供波形项配对判定使用：收缩压/舒张压必须有各自的有效数值。 */
    public int getBloodPressureSysValue() {
        return sys;
    }

    public int getBloodPressureDiaValue() {
        return dia;
    }

    public String getMapText() {
        return map < 0 ? "--" : String.format(Locale.US, "%.0f", map);
    }

    public String getRespText() {
        return intText(resp);
    }

    public int getRespValue() {
        return resp;
    }

    /**
     * 断连前保留的标量只供显示，不允许重新触发报警。
     * 每次协议重新就绪后，该参数收到新的有效帧才恢复对应报警。
     */
    public boolean isParamFresh(int param) {
        return MonitorConnectionPolicy.isParamFresh(isConnected(), isProtocolReady(), param,
                lastReadyAtMs, param >= PARAM_HEART_RATE && param <= PARAM_RESP
                        ? lastValidAtMs[param] : 0L);
    }

    public List<Integer> getEcgWaveSamples() {
        synchronized (waveLock) {
            return new ArrayList<>(ecgWaveSamples);
        }
    }

    public List<Integer> getSpo2WaveSamples() {
        synchronized (waveLock) {
            return new ArrayList<>(spo2WaveSamples);
        }
    }

    public List<Integer> getRespWaveSamples() {
        synchronized (waveLock) {
            return new ArrayList<>(respWaveSamples);
        }
    }

    /**
     * 报告用的波形：实时窗口有信号就用实时数据；
     * 没有信号（刚连上还没数据、或已经断开）就用本次会话留档，
     * 保证报告里画的是监护仪真实采到的波形，而不是一条基线。
     */
    public List<Integer> getEcgWaveForReport() {
        return preferReportWave(ecgWaveSamples, sessionEcgWaveSamples);
    }

    public List<Integer> getSpo2WaveForReport() {
        return preferReportWave(spo2WaveSamples, sessionSpo2WaveSamples);
    }

    public List<Integer> getRespWaveForReport() {
        return preferReportWave(respWaveSamples, sessionRespWaveSamples);
    }

    /** 新一轮治疗开始时清掉上一轮的波形留档，避免报告里混入别的宠物数据。 */
    public void resetSessionWaves() {
        synchronized (waveLock) {
            sessionEcgWaveSamples.clear();
            sessionSpo2WaveSamples.clear();
            sessionRespWaveSamples.clear();
        }
    }

    private List<Integer> preferReportWave(List<Integer> live, List<Integer> session) {
        synchronized (waveLock) {
            if (hasWaveSignal(live)) {
                return new ArrayList<>(live);
            }
            if (hasWaveSignal(session)) {
                return new ArrayList<>(session);
            }
            return new ArrayList<>(live);
        }
    }

    /** 采样恒定说明该通道没有真实波形信号。 */
    private static boolean hasWaveSignal(List<Integer> samples) {
        if (samples == null || samples.size() < 6) {
            return false;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (Integer value : samples) {
            int item = value.intValue();
            min = Math.min(min, item);
            max = Math.max(max, item);
        }
        return max - min > WAVE_FLAT_AMPLITUDE;
    }

    public List<Integer> getHeartRateHistory() {
        synchronized (waveLock) {
            return new ArrayList<>(heartRateHistory);
        }
    }

    public List<Integer> getSpo2History() {
        synchronized (waveLock) {
            return new ArrayList<>(spo2History);
        }
    }

    public List<Integer> getPulseRateHistory() {
        synchronized (waveLock) {
            return new ArrayList<>(pulseRateHistory);
        }
    }

    public List<Integer> getBloodPressureHistory() {
        synchronized (waveLock) {
            return new ArrayList<>(bloodPressureHistory);
        }
    }

    public List<Integer> getTemperatureHistory() {
        synchronized (waveLock) {
            return new ArrayList<>(temperatureHistory);
        }
    }

    public List<Integer> getRespHistory() {
        synchronized (waveLock) {
            return new ArrayList<>(respHistory);
        }
    }

    public List<Integer> getBloodPressureDiastolicHistory() {
        synchronized (waveLock) {
            return new ArrayList<>(bloodPressureDiastolicHistory);
        }
    }

    public List<Integer> getEarTemperatureHistory() {
        synchronized (waveLock) {
            return new ArrayList<>(earTemperatureHistory);
        }
    }

    /** 带时间戳的测量历史，供页面按时间维度绘制趋势图。 */
    public List<VitalSample> getMeasurementHistory() {
        synchronized (waveLock) {
            return measurementHistory.copy();
        }
    }

    /**
     * 监护仪采样缓冲的整份快照。左右舱各留一份，切舱时把当前缓冲换走，
     * 否则左舱的历史曲线会出现在右舱的监护页上。
     */
    public static final class MonitorSnapshot {
        final List<Integer> ecgWaveSamples;
        final List<Integer> spo2WaveSamples;
        final List<Integer> respWaveSamples;
        final List<Integer> sessionEcgWaveSamples;
        final List<Integer> sessionSpo2WaveSamples;
        final List<Integer> sessionRespWaveSamples;
        final List<Integer> heartRateHistory;
        final List<Integer> spo2History;
        final List<Integer> pulseRateHistory;
        final List<Integer> bloodPressureHistory;
        final List<Integer> bloodPressureDiastolicHistory;
        final List<Integer> temperatureHistory;
        final List<Integer> earTemperatureHistory;
        final List<Integer> respHistory;
        final List<VitalSample> measurementHistory;

        MonitorSnapshot(List<Integer> ecgWaveSamples, List<Integer> spo2WaveSamples, List<Integer> respWaveSamples,
                        List<Integer> sessionEcgWaveSamples, List<Integer> sessionSpo2WaveSamples,
                        List<Integer> sessionRespWaveSamples,
                        List<Integer> heartRateHistory, List<Integer> spo2History, List<Integer> pulseRateHistory,
                        List<Integer> bloodPressureHistory, List<Integer> bloodPressureDiastolicHistory,
                        List<Integer> temperatureHistory, List<Integer> earTemperatureHistory,
                        List<Integer> respHistory, List<VitalSample> measurementHistory) {
            this.ecgWaveSamples = ecgWaveSamples;
            this.spo2WaveSamples = spo2WaveSamples;
            this.respWaveSamples = respWaveSamples;
            this.sessionEcgWaveSamples = sessionEcgWaveSamples;
            this.sessionSpo2WaveSamples = sessionSpo2WaveSamples;
            this.sessionRespWaveSamples = sessionRespWaveSamples;
            this.heartRateHistory = heartRateHistory;
            this.spo2History = spo2History;
            this.pulseRateHistory = pulseRateHistory;
            this.bloodPressureHistory = bloodPressureHistory;
            this.bloodPressureDiastolicHistory = bloodPressureDiastolicHistory;
            this.temperatureHistory = temperatureHistory;
            this.earTemperatureHistory = earTemperatureHistory;
            this.respHistory = respHistory;
            this.measurementHistory = measurementHistory;
        }
    }

    /** 取出当前所有采样缓冲的副本，供按舱保存。 */
    public MonitorSnapshot snapshotBuffers() {
        synchronized (waveLock) {
            return new MonitorSnapshot(
                    new ArrayList<>(ecgWaveSamples),
                    new ArrayList<>(spo2WaveSamples),
                    new ArrayList<>(respWaveSamples),
                    new ArrayList<>(sessionEcgWaveSamples),
                    new ArrayList<>(sessionSpo2WaveSamples),
                    new ArrayList<>(sessionRespWaveSamples),
                    new ArrayList<>(heartRateHistory),
                    new ArrayList<>(spo2History),
                    new ArrayList<>(pulseRateHistory),
                    new ArrayList<>(bloodPressureHistory),
                    new ArrayList<>(bloodPressureDiastolicHistory),
                    new ArrayList<>(temperatureHistory),
                    new ArrayList<>(earTemperatureHistory),
                    new ArrayList<>(respHistory),
                    measurementHistory.copy());
        }
    }

    /** 用某个舱的快照覆盖当前缓冲，切舱后立刻看到该舱自己的曲线。 */
    public void restoreBuffers(MonitorSnapshot snapshot) {
        synchronized (waveLock) {
            if (snapshot == null) {
                ecgWaveSamples.clear();
                spo2WaveSamples.clear();
                respWaveSamples.clear();
                sessionEcgWaveSamples.clear();
                sessionSpo2WaveSamples.clear();
                sessionRespWaveSamples.clear();
                heartRateHistory.clear();
                spo2History.clear();
                pulseRateHistory.clear();
                bloodPressureHistory.clear();
                bloodPressureDiastolicHistory.clear();
                temperatureHistory.clear();
                earTemperatureHistory.clear();
                respHistory.clear();
                measurementHistory.clear();
                return;
            }
            ecgWaveSamples.clear();
            ecgWaveSamples.addAll(snapshot.ecgWaveSamples);
            spo2WaveSamples.clear();
            spo2WaveSamples.addAll(snapshot.spo2WaveSamples);
            respWaveSamples.clear();
            respWaveSamples.addAll(snapshot.respWaveSamples);
            sessionEcgWaveSamples.clear();
            sessionEcgWaveSamples.addAll(snapshot.sessionEcgWaveSamples);
            sessionSpo2WaveSamples.clear();
            sessionSpo2WaveSamples.addAll(snapshot.sessionSpo2WaveSamples);
            sessionRespWaveSamples.clear();
            sessionRespWaveSamples.addAll(snapshot.sessionRespWaveSamples);
            heartRateHistory.clear();
            heartRateHistory.addAll(snapshot.heartRateHistory);
            spo2History.clear();
            spo2History.addAll(snapshot.spo2History);
            pulseRateHistory.clear();
            pulseRateHistory.addAll(snapshot.pulseRateHistory);
            bloodPressureHistory.clear();
            bloodPressureHistory.addAll(snapshot.bloodPressureHistory);
            bloodPressureDiastolicHistory.clear();
            bloodPressureDiastolicHistory.addAll(snapshot.bloodPressureDiastolicHistory);
            temperatureHistory.clear();
            temperatureHistory.addAll(snapshot.temperatureHistory);
            earTemperatureHistory.clear();
            earTemperatureHistory.addAll(snapshot.earTemperatureHistory);
            respHistory.clear();
            respHistory.addAll(snapshot.respHistory);
            measurementHistory.replace(snapshot.measurementHistory);
        }
    }

    /**
     * 波形通道诊断：各通道采样点数、幅度范围、收到的波形帧数量，以及帧类型分布。
     * 波形“不动”时用它区分“监护仪没发波形帧”和“发了波形帧但采样恒定”。
     */
    public String getWaveDiagnosticsText() {
        synchronized (waveLock) {
            StringBuilder builder = new StringBuilder();
            builder.append(describeWaveChannel("ECG", ecgWaveSamples, FRAME_TYPE_ECG_WAVE));
            builder.append(" | ").append(describeWaveChannel("PLETH", spo2WaveSamples, FRAME_TYPE_PLETH_WAVE));
            builder.append(" | ").append(describeWaveChannel("RESP", respWaveSamples, FRAME_TYPE_RESP_WAVE));
            builder.append(" | 帧 ").append(describeFrameTypes());
            return builder.toString();
        }
    }

    private String describeWaveChannel(String name, ArrayList<Integer> samples, int waveFrameType) {
        int frameCount = frameTypeCounts[waveFrameType];
        if (samples.isEmpty()) {
            return name + " 0点 " + describeWaveFrameState(frameCount, false);
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
        int amplitude = max - min;
        return name + " " + samples.size() + "点 幅度" + amplitude + "(" + min + "~" + max + ") "
                + describeWaveFrameState(frameCount, amplitude > WAVE_FLAT_AMPLITUDE);
    }

    /** 帧级诊断：区分“监护仪没发波形帧”和“发了波形帧但采样恒定”。 */
    private String describeWaveFrameState(int frameCount, boolean hasSignal) {
        if (frameCount <= 0) {
            return "未收到波形帧";
        }
        return hasSignal ? ("波形帧" + frameCount) : ("波形帧" + frameCount + " 采样恒定");
    }

    private String describeFrameTypes() {
        StringBuilder builder = new StringBuilder();
        for (int type = 0; type < frameTypeCounts.length; type++) {
            if (frameTypeCounts[type] <= 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" ");
            }
            builder.append(String.format(Locale.US, "0x%02X:%d", type, frameTypeCounts[type]));
        }
        return builder.length() == 0 ? "无" : builder.toString();
    }

    /** 记录某类数值帧最近一次到达的时刻，诊断"设备到底有没有发这一帧"。 */
    private void noteFrameArrived(int frameType) {
        lastFrameAtMs[frameType] = System.currentTimeMillis();
    }

    /** 只有生理范围内的新测量才刷新报警新鲜度；异常原值另留诊断，不进入实测数据。 */
    private void noteParamValue(int param, double value) {
        long now = System.currentTimeMillis();
        if (VitalMeasurementPolicy.isValid(param, value)) {
            lastValidAtMs[param] = now;
            return;
        }
        invalidCount[param] += 1;
        if (value >= 0.0 && !Double.isInfinite(value) && !Double.isNaN(value)) {
            lastRejectedParam = param;
            lastRejectedValue = value;
            lastRejectedAtMs = now;
        }
    }

    /**
     * 数值帧诊断文本：逐项给出"末帧到达距现在多久、末次有效值距现在多久、累计无效帧数"。
     * 当已连接且协议就绪、但血氧/呼吸仍无效时，明确提示这是设备信号建立（探头搜索）耗时，
     * 而不是 App 侧延迟——直接回答"为什么 30~60 秒才出值"。
     */
    public String getNumericFrameDiagnosticsText() {
        long now = System.currentTimeMillis();
        StringBuilder builder = new StringBuilder();
        appendParamDiag(builder, "心率", PARAM_HEART_RATE, FRAME_TYPE_HEART_RESP, getHeartRateValue(), now);
        appendParamDiag(builder, "血氧", PARAM_SPO2, FRAME_TYPE_SPO2_PULSE, getSpo2Value(), now);
        appendParamDiag(builder, "脉率", PARAM_PULSE, FRAME_TYPE_SPO2_PULSE, getPulseRateValue(), now);
        appendParamDiag(builder, "体温", PARAM_TEMP, FRAME_TYPE_TEMP, "--".equals(getBodyTempText()) ? -1 : 1, now);
        appendParamDiag(builder, "呼吸", PARAM_RESP, FRAME_TYPE_HEART_RESP, getRespValue(), now);
        appendParamDiag(builder, "血压", PARAM_BP_SYS, FRAME_TYPE_NIBP, sys, now);
        if (isConnected() && isProtocolReady() && (getSpo2Value() < 0 || getRespValue() < 0)) {
            long sinceReady = lastReadyAtMs > 0L ? (now - lastReadyAtMs) : 0L;
            builder.append(" | 设备信号建立中(已").append(sinceReady / 1000L).append("s)：持续收到无效帧多为探头搜索/信号锁定，属设备采集耗时，非App延迟");
        }
        if (lastRejectedParam >= 0) {
            builder.append(" | 最近拒绝 p=").append(lastRejectedParam)
                    .append(" v=").append(String.format(Locale.US, "%.2f", lastRejectedValue))
                    .append(" 距今 ").append(sinceText(lastRejectedAtMs, now));
        }
        return builder.toString();
    }

    private void appendParamDiag(StringBuilder builder, String name, int param, int frameType, int value, long now) {
        if (builder.length() > 0) {
            builder.append(" | ");
        }
        builder.append(name)
                .append(" 末帧 ").append(sinceText(lastFrameAtMs[frameType], now))
                .append(" 末有效 ").append(sinceText(lastValidAtMs[param], now))
                .append(" 无效帧 ").append(invalidCount[param]);
    }

    private static String sinceText(long ts, long now) {
        if (ts <= 0L) {
            return "—";
        }
        return String.format(Locale.US, "%.1fs", (now - ts) / 1000.0);
    }

    public String getBodyTempText() {
        // 0x20 是物体/环境温度，不是体温。以前作为第三级回退，
        // 导致没接探头时治疗记录把 26℃ 左右的环境温度写成体温实测值。
        if (isValidTempText(bodyTemp)) {
            return bodyTemp;
        }
        if (isValidTempText(earTemp)) {
            return earTemp;
        }
        return "--";
    }

    /** 物体/环境温度单独暴露，避免再被当成体温使用。 */
    public String getObjectTempText() {
        return objectTemp;
    }

    /** 占位符与超出生理防御范围的值都不算体温实测值。 */
    private boolean isValidTempText(String value) {
        if (value == null || value.length() == 0 || "--".equals(value)) {
            return false;
        }
        try {
            return VitalMeasurementPolicy.isValid(PARAM_TEMP, Float.parseFloat(value));
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    public String getEarTempText() {
        return earTemp;
    }

    public String getAmbientTempText() {
        return ambientTemp;
    }

    public String getCalibrationStatusText() {
        if (!"--".equals(statusThree)) {
            return statusThree;
        }
        if (!"--".equals(statusTwo)) {
            return statusTwo;
        }
        return statusOne;
    }

    public boolean hasVitals() {
        return heartRate >= 0 || spo2 >= 0 || pulseRate >= 0 || sys >= 0 || resp >= 0
                || !"--".equals(bodyTemp) || !"--".equals(earTemp);
    }

    @SuppressLint("MissingPermission")
    public void requestEnableBluetooth(Activity activity) {
        if (bluetoothAdapter == null) {
            setError("设备不支持蓝牙");
            return;
        }
        if (missingBluetoothConnectPermission()) {
            setError("缺少蓝牙连接权限，请先申请权限");
            return;
        }
        try {
            if (isBluetoothEnabled()) {
                addLog("Bluetooth already enabled");
                emit();
                return;
            }
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivity(intent);
            addLog("Request enable bluetooth");
        } catch (SecurityException exception) {
            setError("缺少蓝牙连接权限，请先申请权限");
        }
    }

    @SuppressLint("MissingPermission")
    public void startScan() {
        if (bluetoothAdapter == null) {
            setError("设备不支持蓝牙");
            return;
        }
        // 扫描只需要扫描权限：连接权限在部分定制系统上是分开授权的，
        // 拿不到连接权限不该连扫描一起拦掉，否则一个设备都扫不到。
        if (missingBluetoothScanPermission()) {
            setError("缺少蓝牙扫描权限，请先申请权限");
            return;
        }
        if (!isBluetoothEnabled()) {
            setError("蓝牙未开启，请先打开系统蓝牙");
            return;
        }
        if (Build.VERSION.SDK_INT < 31 && !isLocationServiceEnabled()) {
            // 老系统上定位服务关掉时，BLE 扫描结果会被系统整批过滤，
            // 表现和“监护仪不在附近”一模一样，这里先把话说在前面。
            addLog("系统定位服务未开启，扫描结果可能被系统过滤");
        }
        scanner = getBleScanner();
        if (scanner == null) {
            setError("BLE 扫描器不可用，请检查蓝牙开关与权限");
            return;
        }

        stopScan();
        // 不清空列表：扫描过程中每来一个结果都会刷新界面，
        // 清一次就把使用者正在找的设备冲回列表顶部。
        scanning = true;
        if (!connected) {
            stateText = "扫描中";
        }

        if (scanCallback == null) {
            scanCallback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, @NonNull ScanResult result) {
                    onDeviceFound(result);
                }

                @Override
                public void onBatchScanResults(@NonNull List<ScanResult> results) {
                    for (ScanResult result : results) {
                        onDeviceFound(result);
                    }
                }

                @Override
                public void onScanFailed(int errorCode) {
                    scanning = false;
                    setError("AM4100 scan failed: " + errorCode);
                }
            };
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, settings, scanCallback);
        } catch (SecurityException error) {
            scanning = false;
            setError("系统拒绝了蓝牙扫描权限，请重新授权后重试");
            return;
        }
        lastError = "";
        addLog("扫描附近 BLE 设备");
        handler.removeCallbacks(scanTimeoutRunnable);
        handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);
        emit();
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        handler.removeCallbacks(scanTimeoutRunnable);
        if (missingBluetoothScanPermission()) {
            scanning = false;
            emit();
            return;
        }
        if (scanner != null && scanCallback != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        scanning = false;
        if (!connected && "扫描中".equals(stateText)) {
            stateText = "未连接";
        }
        emit();
    }

    /** 自动重连专用的扫描回调：和用户手动扫描各走各的，互不干扰。 */
    private final ScanCallback autoReconnectScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, @NonNull ScanResult result) {
            handleAutoReconnectScanResult(result);
        }

        @Override
        public void onBatchScanResults(@NonNull List<ScanResult> results) {
            for (ScanResult result : results) {
                handleAutoReconnectScanResult(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            autoReconnectScanning = false;
            autoReconnect.onScanFailed();
            setError("AM4100 自动重连扫描失败: " + errorCode);
        }
    };

    /**
     * 自动重连：先扫描把这台监护仪找回来。
     *
     * <p>监护仪重新开机之后才会恢复广播，扫到它的 MAC 就能连上；
     * 扫不到说明它还没上电或者离得太远，扫描会一直守着，等它回来。</p>
     */
    @SuppressLint("MissingPermission")
    private void startAutoReconnectScan(String address) {
        if (!isBluetoothEnabled()) {
            // 蓝牙没开：停掉重连，等蓝牙打开后由系统广播重新触发。
            autoReconnect.onBluetoothOff();
            return;
        }
        if (missingBluetoothScanPermission() || missingBluetoothConnectPermission()) {
            // 权限还没给：这一轮连不上，退避之后再来一轮，
            // 不能就此把自动重连卡死。
            autoReconnect.onScanFailed();
            return;
        }
        if (autoReconnectScanning && address.equalsIgnoreCase(autoReconnectTargetAddress)) {
            // 已经在扫了：扫描会一直持续到命中为止，不用重来一遍。
            return;
        }
        stopAutoReconnectScan();
        closeConnection();
        autoReconnectTargetAddress = address;
        autoReconnectScanning = true;
        // 扫描等待不算一次连接尝试，不给它计时，扫到设备时才重新开始计时。
        autoReconnect.onScanStarted();
        stateText = "自动重连中";
        beginAutoReconnectScan();
    }

    /** 真正把自动重连扫描跑起来，保活定时器会定期重启它。 */
    @SuppressLint("MissingPermission")
    private void beginAutoReconnectScan() {
        if (!autoReconnectScanning || TextUtils.isEmpty(autoReconnectTargetAddress)) {
            return;
        }
        BluetoothLeScanner bleScanner = getBleScanner();
        if (bleScanner == null) {
            autoReconnectScanning = false;
            autoReconnect.onScanFailed();
            setError("AM4100 自动重连扫描失败: 蓝牙不可用");
            return;
        }
        try {
            bleScanner.startScan(null, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(), autoReconnectScanCallback);
            handler.removeCallbacks(autoReconnectScanKeepAliveRunnable);
            handler.postDelayed(autoReconnectScanKeepAliveRunnable, AUTO_RECONNECT_SCAN_KEEP_ALIVE_MS);
            addLog("AM4100 自动重连：等待监护仪重新广播 " + autoReconnectTargetAddress);
            emit();
        } catch (Exception exception) {
            autoReconnectScanning = false;
            autoReconnect.onScanFailed();
            setError("AM4100 自动重连扫描失败: " + exception.getMessage());
        }
    }

    /**
     * 扫描保活：定期重启一次扫描，
     * 避免某些机型把长时间不间断的扫描悄悄掐掉，导致监护仪回来之后再也扫不到。
     */
    private final Runnable autoReconnectScanKeepAliveRunnable = new Runnable() {
        @Override
        public void run() {
            if (!autoReconnectScanning) {
                return;
            }
            String address = autoReconnectTargetAddress;
            stopAutoReconnectScan();
            if (TextUtils.isEmpty(address)) {
                return;
            }
            autoReconnectTargetAddress = address;
            autoReconnectScanning = true;
            beginAutoReconnectScan();
        }
    };

    /** 停掉自动重连扫描。 */
    @SuppressLint("MissingPermission")
    private void stopAutoReconnectScan() {
        autoReconnectScanning = false;
        autoReconnectTargetAddress = "";
        handler.removeCallbacks(autoReconnectScanKeepAliveRunnable);
        if (missingBluetoothScanPermission()) {
            return;
        }
        BluetoothLeScanner bleScanner = getBleScanner();
        if (bleScanner != null) {
            try {
                bleScanner.stopScan(autoReconnectScanCallback);
            } catch (Exception ignored) {
            }
        }
    }

    /** 扫描命中记住的监护仪：立刻用扫描结果发起连接。 */
    private void handleAutoReconnectScanResult(ScanResult result) {
        if (!autoReconnectScanning || result == null || result.getDevice() == null) {
            return;
        }
        String address;
        try {
            address = result.getDevice().getAddress();
        } catch (SecurityException exception) {
            return;
        }
        if (TextUtils.isEmpty(address) || !address.equalsIgnoreCase(autoReconnectTargetAddress)) {
            return;
        }
        // 扫到的设备顺带刷新一下列表，界面上能看到最新名称和信号强度。
        onDeviceFound(result);
        BluetoothDevice device = result.getDevice();
        String name = resolveDeviceName(address, device);
        stopAutoReconnectScan();
        // 这次才是真正的连接尝试：重新给控制器计时，
        // 别让扫描期间用掉的超时把这次连接判死。
        autoReconnect.onAttemptStarted();
        connectDevice(device, name, true);
    }

    /**
     * 用户在设备列表里点了某个监护仪：记住它，并立即发起连接。
     * 记住之后，App 重启、蓝牙重新打开、设备意外掉线都会自动连回这一台。
     */
    @SuppressLint("MissingPermission")
    public void connect(String address) {
        if (bluetoothAdapter == null) {
            setError("设备不支持蓝牙");
            return;
        }
        if (missingBluetoothConnectPermission()) {
            setError("缺少蓝牙连接权限，请先申请权限");
            return;
        }
        // 用户自己选设备：停掉自动重连的扫描，别让它抢先把上一台连上。
        stopAutoReconnectScan();
        autoReconnect.onManualConnect(address, findDeviceName(address));
        doConnect(address, false);
    }

    /**
     * 真正发起连接。
     *
     * @param automatic true 表示这次是掉线后的自动重连，界面上的状态文字不一样。
     */
    @SuppressLint("MissingPermission")
    private void doConnect(String address, boolean automatic) {
        if (automatic) {
            // 自动重连改成"扫到再连"：监护仪重新开机之后才会广播，
            // 按地址盲连在它没广播时必然失败，连着失败还会把蓝牙栈的连接资源弄脏。
            startAutoReconnectScan(address);
            return;
        }
        stopScan();
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            connectDevice(device, resolveDeviceName(address, device), false);
        } catch (IllegalArgumentException exception) {
            // 地址非法属于永久性错误，重试也没用，直接把这台设备忘掉。
            autoReconnect.forget();
            setError("AM4100 connect failed: " + exception.getMessage());
        } catch (Exception exception) {
            setError("AM4100 connect failed: " + exception.getMessage());
        }
    }

    /**
     * 用具体的设备对象发起连接。
     *
     * @param device     要连的监护仪；扫描命中时用扫描结果里的对象最可靠
     * @param name       设备名称，界面上要显示蓝牙名称而不是地址
     * @param automatic  true 表示这是掉线后的自动重连
     */
    @SuppressLint("MissingPermission")
    private void connectDevice(BluetoothDevice device, String name, boolean automatic) {
        if (device == null) {
            return;
        }
        // 同一台监护仪断开或重连时保留最后有效标量；换设备必须清值，避免跨设备串值。
        String address = device.getAddress();
        boolean sameDevice = !MonitorConnectionPolicy.shouldClearDisplayValues(connectedDeviceId, address);
        closeConnection();
        connectedDeviceId = address;
        connectedDeviceName = TextUtils.isEmpty(name) ? connectedDeviceId : name;
        stateText = automatic ? "自动重连中" : "连接中";
        lastError = "";
        clearVitals(!sameDevice);
        bluetoothGatt = device.connectGatt(appContext, false, gattCallback);
        addLog("Connect AM4100 " + (TextUtils.isEmpty(connectedDeviceName) ? connectedDeviceId : connectedDeviceName));
        emit();
    }

    /** 用户主动断开：本次运行不再自动重连。 */
    @SuppressLint("MissingPermission")
    public void disconnect() {
        stopAutoReconnectScan();
        autoReconnect.onUserDisconnect();
        closeConnection();
    }

    /**
     * 关闭当前链路并复位状态。
     * 与 {@link #disconnect()} 的区别是这里不会停掉自动重连，
     * 用于重连前的清场以及 Activity 销毁时的收尾。
     */
    @SuppressLint("MissingPermission")
    private void closeConnection() {
        stopScan();
        handler.removeCallbacks(linkWatchdogRunnable);
        connected = false;
        protocolReady = false;
        notifyCharacteristic = null;
        writeCharacteristic = null;
        stateText = "未连接";
        frameBuffer.clear();
        handler.removeCallbacks(waveEmitRunnable);
        waveEmitPending = false;
        // 监护链路一断开就清掉全部标量显示（含血压、体温），避免只剩这两项残留。
        // 只清显示与帧诊断，病例会话波形/趋势/测量历史仍然保留。
        clearVitals(true);
        if (bluetoothGatt != null) {
            try {
                if (!missingBluetoothConnectPermission()) {
                    bluetoothGatt.disconnect();
                }
            } catch (Exception ignored) {
            }
            try {
                if (!missingBluetoothConnectPermission()) {
                    bluetoothGatt.close();
                }
            } catch (Exception ignored) {
            }
            bluetoothGatt = null;
        }
        emit();
    }

    /**
     * 链路静默看门狗。
     *
     * 监护仪被突然关掉时系统不一定会回调 GATT 断开，页面上只表现为数值停住。
     * 这里用“多久没收到监护仪数据”来兜底判定链路真的断了。
     */
    private final Runnable linkWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (!connected || !protocolReady) {
                return;
            }
            long now = System.currentTimeMillis();
            if (lastNotifyAtMs > 0L && now - lastNotifyAtMs > LINK_SILENCE_TIMEOUT_MS) {
                handleLinkLost();
                return;
            }
            handler.postDelayed(this, LINK_WATCHDOG_INTERVAL_MS);
        }
    };

    /**
     * 判定链路真的断了：关掉 GATT 让系统能干净地重连，再交给自动重连恢复。
     */
    private void handleLinkLost() {
        addLog("超过 " + (LINK_SILENCE_TIMEOUT_MS / 1000) + " 秒未收到监护仪数据，判定链路断开");
        closeConnection();
        stateText = "连接已丢失";
        lastError = "长时间未收到监护仪数据，链路已断开";
        emit();
        autoReconnect.onDisconnected();
    }

    /**
     * App 启动、蓝牙重新打开、页面回到前台时调用：
     * 之前连过这台监护仪、现在又没连上，就自动把它连回来。
     */
    @SuppressLint("MissingPermission")
    public void tryAutoConnect() {
        if (!isBluetoothEnabled()) {
            return;
        }
        if (missingBluetoothConnectPermission()) {
            return;
        }
        if (connected) {
            return;
        }
        // 上一条链路残留的 gatt 由 doConnect 里的 closeConnection 清理，
        // 这里不能因为"还有 gatt"就跳过，否则监护仪重新开机之后就再也连不上。
        autoReconnect.tryAutoConnect();
    }

    /** 蓝牙被关闭：停掉等待中的自动重连。 */
    public void onBluetoothOff() {
        stopAutoReconnectScan();
        autoReconnect.onBluetoothOff();
        // 蓝牙被关掉，旧链路已经没用了：清掉，
        // 否则蓝牙重新打开时 tryAutoConnect 会因为"还有 gatt"跳过自动连接。
        closeConnection();
    }

    /** Activity 销毁时收尾：关掉链路，但保留记住的设备，下次启动还能自动连上。 */
    @SuppressLint("MissingPermission")
    public void release() {
        stopAutoReconnectScan();
        autoReconnect.release();
        closeConnection();
    }

    public void clearLogs() {
        logs.clear();
        emit();
    }

    public void setTemperatureMode() {
        writeHex(new byte[]{0x55, (byte) 0xAA, 0x04, 0x60, 0x01, (byte) 0x9A});
    }

    public void setEarTemperatureMode() {
        writeHex(new byte[]{0x55, (byte) 0xAA, 0x04, 0x60, 0x00, (byte) 0x9B});
    }

    public void startEarTemperatureCalibration() {
        writeHex(new byte[]{0x55, (byte) 0xAA, 0x04, 0x60, 0x10, (byte) 0x8B});
    }

    @SuppressLint("MissingPermission")
    public void writeHex(byte[] command) {
        if (missingBluetoothConnectPermission()) {
            setError("缺少蓝牙连接权限，请先申请权限");
            return;
        }
        if (!protocolReady || bluetoothGatt == null || writeCharacteristic == null) {
            setError("请先连接 AM4100 监护设备");
            return;
        }
        try {
            writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            writeCharacteristic.setValue(command);
            boolean started = bluetoothGatt.writeCharacteristic(writeCharacteristic);
            if (!started) {
                setError("AM4100 writeCharacteristic returned false");
                return;
            }
            lastError = "";
            addLog("AM4100 发送: " + toHex(command));
            emit();
        } catch (Exception exception) {
            setError("AM4100 write failed: " + exception.getMessage());
        }
    }

    /** BLE 回调只复制参数，所有连接状态和协议数据统一在主线程修改。 */
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(@NonNull final BluetoothGatt gatt, final int status, final int newState) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    handleConnectionStateChange(gatt, status, newState);
                }
            });
        }

        @Override
        public void onServicesDiscovered(@NonNull final BluetoothGatt gatt, final int status) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    handleServicesDiscovered(gatt, status);
                }
            });
        }

        @Override
        public void onDescriptorWrite(@NonNull final BluetoothGatt gatt, @NonNull BluetoothGattDescriptor descriptor, final int status) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    handleDescriptorWrite(gatt, status);
                }
            });
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            postCharacteristicChanged(gatt, characteristic, value);
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
            postCharacteristicChanged(gatt, characteristic, value);
        }
    };

    private void postCharacteristicChanged(final BluetoothGatt gatt,
                                           final BluetoothGattCharacteristic characteristic, byte[] value) {
        final byte[] copied = value == null ? null : value.clone();
        handler.post(new Runnable() {
            @Override
            public void run() {
                handleCharacteristicChanged(gatt, characteristic, copied);
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void handleConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        // 手动关闭后 bluetoothGatt 已清空；任何旧回调都不得重新启动重连或覆盖新链路。
        if (gatt != bluetoothGatt) {
            return;
        }
        if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            connected = false;
            protocolReady = false;
            stateText = "发现服务中";
            addLog("AM4100 GATT connected");
            // 等 Notify 就绪后再结束连接计时，服务发现卡住时仍能自动恢复。
            emit();
            try {
                if (missingBluetoothConnectPermission()) {
                    setError("缺少蓝牙连接权限，请先申请权限");
                    return;
                }
                gatt.discoverServices();
            } catch (Exception exception) {
                setError("AM4100 discover failed: " + exception.getMessage());
            }
            return;
        }
        if (newState == BluetoothGatt.STATE_CONNECTING) {
            stateText = "连接中";
            emit();
            return;
        }
        if (newState == BluetoothGatt.STATE_DISCONNECTING) {
            stateText = "断开中";
            emit();
            return;
        }
        connected = false;
        protocolReady = false;
        notifyCharacteristic = null;
        writeCharacteristic = null;
        stateText = "已断开";
        addLog("AM4100 GATT disconnected");
        clearWaveSamples();
        try {
            gatt.close();
        } catch (Exception ignored) {
        }
        bluetoothGatt = null;
        // 掉线后交给控制器安排自动重连，状态文字会变成"自动重连中"。
        autoReconnect.onDisconnected();
        emit();
    }

    @SuppressLint("MissingPermission")
    private void handleServicesDiscovered(BluetoothGatt gatt, int status) {
        if (gatt != bluetoothGatt) {
            return;
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            stateText = "发现服务失败";
            setError("AM4100 discover status: " + status);
            return;
        }
        BluetoothGattService service = gatt.getService(AM4100_SERVICE_UUID);
        if (service == null) {
            stateText = "协议不匹配";
            setError("未找到 AM4100 服务: " + AM4100_SERVICE_UUID);
            return;
        }
        notifyCharacteristic = service.getCharacteristic(AM4100_NOTIFY_UUID);
        writeCharacteristic = service.getCharacteristic(AM4100_WRITE_UUID);
        if (notifyCharacteristic == null || writeCharacteristic == null) {
            stateText = "协议不匹配";
            setError("未找到 AM4100 Notify/Write 特征");
            return;
        }
        enableNotify(gatt);
    }

    private void handleDescriptorWrite(BluetoothGatt gatt, int status) {
        if (gatt != bluetoothGatt) {
            return;
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            markReady();
        } else {
            stateText = "Notify失败";
            setError("AM4100 Notify descriptor failed: " + status);
        }
    }

    @SuppressLint("MissingPermission")
    private void enableNotify(BluetoothGatt gatt) {
        if (missingBluetoothConnectPermission()) {
            setError("缺少蓝牙连接权限，请先申请权限");
            return;
        }
        stateText = "启用Notify中";
        try {
            boolean enabled = gatt.setCharacteristicNotification(notifyCharacteristic, true);
            if (!enabled) {
                stateText = "Notify失败";
                setError("AM4100 本地 Notify 开关失败");
                return;
            }
            BluetoothGattDescriptor descriptor = notifyCharacteristic.getDescriptor(CLIENT_CONFIG_UUID);
            if (descriptor == null) {
                addLog("AM4100 无 2902 descriptor，仅启用本地通知");
                markReady();
                return;
            }
            int properties = notifyCharacteristic.getProperties();
            if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    && (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            } else {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            }
            boolean started = gatt.writeDescriptor(descriptor);
            if (!started) {
                stateText = "Notify失败";
                setError("AM4100 descriptor write returned false");
            }
        } catch (Exception exception) {
            stateText = "Notify失败";
            setError("AM4100 Notify failed: " + exception.getMessage());
        }
    }

    private void markReady() {
        if (protocolReady || bluetoothGatt == null) {
            return;
        }
        connected = true;
        protocolReady = true;
        // 链路刚就绪时从现在开始计时，静默看门狗才有判断依据。
        lastNotifyAtMs = System.currentTimeMillis();
        lastReadyAtMs = System.currentTimeMillis();
        stateText = "已连接";
        lastError = "";
        addLog("AM4100 协议通道已就绪");
        // 协议就绪时 GATT 已经建立，系统的设备名缓存通常这时才读得到：
        // 自动重连刚连上那会儿往往还只有地址，这里再补一次名称。
        if (TextUtils.isEmpty(connectedDeviceName) && bluetoothGatt != null) {
            String resolved = resolveDeviceName(connectedDeviceId, bluetoothGatt.getDevice());
            if (!TextUtils.isEmpty(resolved)) {
                connectedDeviceName = resolved;
                addLog("AM4100 已补全设备名称: " + resolved);
            }
        }
        autoReconnect.onConnected(connectedDeviceId, connectedDeviceName);
        handler.removeCallbacks(linkWatchdogRunnable);
        handler.postDelayed(linkWatchdogRunnable, LINK_WATCHDOG_INTERVAL_MS);
        emit();
    }

    private void handleCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] data) {
        if (gatt != bluetoothGatt) {
            return;
        }
        if (characteristic == null || !AM4100_NOTIFY_UUID.equals(characteristic.getUuid()) || data == null || data.length == 0) {
            return;
        }
        // 收到任何一帧监护仪数据都算链路还活着，静默看门狗从这里重新计时。
        lastNotifyAtMs = System.currentTimeMillis();
        lastError = "";
        boolean shouldEmitNow = parseFrames(data);
        long now = System.currentTimeMillis();
        if (shouldEmitNow || now - lastWaveLogMs >= WAVE_LOG_INTERVAL_MS) {
            lastRawFrame = toHex(data, 48);
            addLog("AM4100 收到 " + data.length + " bytes: " + lastRawFrame);
            lastWaveLogMs = now;
        }
        if (now - lastDiagnosticLogMs >= DIAGNOSTIC_LOG_INTERVAL_MS) {
            lastDiagnosticLogMs = now;
            addLog("AM4100 诊断 " + getWaveDiagnosticsText());
        }
        if (shouldEmitNow) {
            emit();
        } else {
            scheduleWaveEmit();
        }
    }

    private boolean parseFrames(byte[] data) {
        boolean shouldEmitNow = false;
        for (byte item : data) {
            frameBuffer.add(item & 0xFF);
        }
        int index = 0;
        int validIndex = 0;
        int maxIndex = frameBuffer.size() - 7;
        while (index <= maxIndex) {
            if (frameBuffer.get(index) != 0x55 || frameBuffer.get(index + 1) != 0xAA) {
                index++;
                validIndex = index;
                continue;
            }
            int length = frameBuffer.get(index + 2);
            int type = frameBuffer.get(index + 3);
            int dataCount = length - 3;
            int packetLength = length + 2;
            if (dataCount <= 0) {
                index += 2;
                validIndex = index;
                continue;
            }
            if (index + packetLength > frameBuffer.size()) {
                validIndex = index;
                break;
            }
            int checksum = frameBuffer.get(index + 4 + dataCount) & 0xFF;
            int sum = 0;
            int[] payload = new int[dataCount];
            for (int i = 0; i < dataCount; i++) {
                int value = frameBuffer.get(index + 4 + i) & 0xFF;
                payload[i] = value;
                sum += value;
            }
            int realSum = (~(length + type + sum)) & 0xFF;
            if (checksum != realSum) {
                index += 2;
                validIndex = index;
                continue;
            }
            shouldEmitNow = readData(type, payload) || shouldEmitNow;
            frameTypeCounts[type]++;
            index += packetLength;
            validIndex = index;
        }
        if (validIndex > 0 && validIndex <= frameBuffer.size()) {
            frameBuffer.subList(0, validIndex).clear();
        }
        if (frameBuffer.size() > 2048) {
            frameBuffer.clear();
        }
        return shouldEmitNow;
    }

    private boolean readData(int type, int[] data) {
        if (data.length == 0) {
            return false;
        }
        switch (type) {
            case FRAME_TYPE_HEART_RESP:
                readHeartResp(data);
                lastFrameSummary = "HR/RESP 心率 " + getHeartRateText() + " 呼吸 " + getRespText();
                return true;
            case FRAME_TYPE_NIBP:
                readNonInvasiveBloodPressure(data);
                lastFrameSummary = "无创血压 " + getBloodPressureText() + " 平均压 " + getMapText();
                return true;
            case FRAME_TYPE_SPO2_PULSE:
                readSpo2Pulse(data);
                lastFrameSummary = "SpO2/PR 血氧 " + getSpo2Text() + "% 脉率 " + getPulseRateText();
                return true;
            case FRAME_TYPE_TEMP:
                readTemp(data);
                lastFrameSummary = "TEMP 体温 " + getBodyTempText();
                return true;
            case FRAME_TYPE_STATUS:
                readStatus(data);
                lastFrameSummary = "STATUS " + getCalibrationStatusText();
                return true;
            case FRAME_TYPE_ECG_WAVE:
                appendWaveSamples(ecgWaveSamples, sessionEcgWaveSamples, data);
                lastFrameSummary = "ECG 波形数据";
                return false;
            case FRAME_TYPE_PLETH_WAVE:
                appendWaveSamples(spo2WaveSamples, sessionSpo2WaveSamples, data);
                lastFrameSummary = "SpO2 波形数据";
                return false;
            case FRAME_TYPE_RESP_WAVE:
                appendWaveSamples(respWaveSamples, sessionRespWaveSamples, data);
                lastFrameSummary = "RESP 波形数据";
                return false;
            default:
                lastFrameSummary = "AM4100 frame type 0x" + Integer.toHexString(type).toUpperCase(Locale.US);
                return true;
        }
    }

    private void readHeartResp(int[] data) {
        int status = data[0] & 0x02;
        int nextHr = -1;
        int nextResp = -1;
        if (data.length == 5) {
            nextHr = status == 0 ? data[1] : -1;
            nextResp = status == 0 ? data[2] : -1;
        } else if (data.length == 6) {
            nextHr = status == 0 ? (data[5] << 8) | data[1] : -1;
            nextResp = status == 0 ? data[2] : -1;
        }
        heartRate = VitalMeasurementPolicy.isValid(PARAM_HEART_RATE, nextHr) ? nextHr : -1;
        resp = VitalMeasurementPolicy.isValid(PARAM_RESP, nextResp) ? nextResp : -1;
        noteFrameArrived(FRAME_TYPE_HEART_RESP);
        noteParamValue(PARAM_HEART_RATE, nextHr);
        noteParamValue(PARAM_RESP, nextResp);
        recordTrendSample(heartRateHistory, heartRate);
        recordTrendSample(respHistory, resp);
        recordMeasurement(PARAM_HEART_RATE, heartRate);
        recordMeasurement(PARAM_RESP, resp);
    }

    private void appendWaveSamples(ArrayList<Integer> target, ArrayList<Integer> session, int[] data) {
        synchronized (waveLock) {
            for (int value : data) {
                target.add(value & 0xFF);
                session.add(value & 0xFF);
            }
            if (target.size() > MAX_WAVE_SAMPLES) {
                target.subList(0, target.size() - MAX_WAVE_SAMPLES).clear();
            }
            if (session.size() > MAX_SESSION_WAVE_SAMPLES) {
                session.subList(0, session.size() - MAX_SESSION_WAVE_SAMPLES).clear();
            }
        }
    }

    /**
     * 记录测量趋势点：监护仪会重复上报同一次测量，只在数值变化时落点，
     * 这样趋势曲线反映的是真实变化，而不是一串重复的相同数值。
     */
    private void recordTrendSample(ArrayList<Integer> target, int value) {
        if (value < 0) {
            return;
        }
        synchronized (waveLock) {
            if (!target.isEmpty() && target.get(target.size() - 1).intValue() == value) {
                return;
            }
            target.add(value);
            if (target.size() > MAX_WAVE_SAMPLES) {
                target.subList(0, target.size() - MAX_WAVE_SAMPLES).clear();
            }
        }
    }

    /** 记录一条带时间戳的测量值，供历史趋势按时间维度查看。 */
    private void recordMeasurement(int param, double value) {
        if (!VitalMeasurementPolicy.isValid(param, value)) {
            return;
        }
        synchronized (waveLock) {
            measurementHistory.record(System.currentTimeMillis(), param, value);
        }
    }

    private void readNonInvasiveBloodPressure(int[] data) {
        if (data.length < 5) {
            return;
        }
        int status = (data[0] & (32 | 16 | 8 | 4)) >> 2;
        if (status == 0 || status == 7) {
            int nextSys = data[2];
            int nextDia = data[4];
            sys = VitalMeasurementPolicy.isValid(PARAM_BP_SYS, nextSys) ? nextSys : -1;
            dia = VitalMeasurementPolicy.isValid(PARAM_BP_DIA, nextDia) ? nextDia : -1;
            map = sys > 0 && dia > 0 ? data[3] : -1.0;
            noteFrameArrived(FRAME_TYPE_NIBP);
            noteParamValue(PARAM_BP_SYS, nextSys);
            noteParamValue(PARAM_BP_DIA, nextDia);
            recordTrendSample(bloodPressureHistory, sys);
            recordTrendSample(bloodPressureDiastolicHistory, dia);
            recordMeasurement(PARAM_BP_SYS, sys);
            recordMeasurement(PARAM_BP_DIA, dia);
        } else {
            sys = -1;
            map = -1.0;
            dia = -1;
            noteFrameArrived(FRAME_TYPE_NIBP);
            noteParamValue(PARAM_BP_SYS, sys);
            noteParamValue(PARAM_BP_DIA, dia);
        }
    }

    private void readSpo2Pulse(int[] data) {
        if (data.length < 3) {
            return;
        }
        int nextSpo2 = data[0] == 0 ? data[1] : -1;
        if (nextSpo2 == 127) {
            nextSpo2 = -1;
        }
        int nextPr;
        if (data.length == 3) {
            nextPr = data[2] != 255 ? data[2] : -1;
        } else {
            nextPr = data[2] | (data[3] << 8);
            if (nextPr == 0xFF00) {
                nextPr = -1;
            }
        }
        spo2 = VitalMeasurementPolicy.isValid(PARAM_SPO2, nextSpo2) ? nextSpo2 : -1;
        pulseRate = VitalMeasurementPolicy.isValid(PARAM_PULSE, nextPr) ? nextPr : -1;
        noteFrameArrived(FRAME_TYPE_SPO2_PULSE);
        noteParamValue(PARAM_SPO2, nextSpo2);
        noteParamValue(PARAM_PULSE, nextPr);
        recordTrendSample(spo2History, spo2);
        recordTrendSample(pulseRateHistory, pulseRate);
        recordMeasurement(PARAM_SPO2, spo2);
        recordMeasurement(PARAM_PULSE, pulseRate);
    }

    private void readTemp(int[] data) {
        if (data.length < 3) {
            return;
        }
        // 全零 TEMP 帧表示探头没读到值，不是 0.0℃ 实测。
        // 以前会拼成 "0.0" 进入显示、下限告警与历史曲线，造成常挂误报。
        double value = data[1] + data[2] / 10.0;
        if (data[0] == 0x40 || data[0] == 0x00) {
            noteFrameArrived(FRAME_TYPE_TEMP);
            noteParamValue(PARAM_TEMP, value);
            if (!VitalMeasurementPolicy.isValid(PARAM_TEMP, value)) {
                return;
            }
            if (data[0] == 0x40) {
                earTemp = data[1] + "." + data[2];
                recordTrendSample(earTemperatureHistory, data[1] * 10 + data[2]);
            } else {
                bodyTemp = data[1] + "." + data[2];
                recordTrendSample(temperatureHistory, data[1] * 10 + data[2]);
            }
            // 体温显示本来就是体表温度优先、耳温回退；有效耳温同样属于真实体温历史。
            recordMeasurement(PARAM_TEMP, value);
        } else {
            noteFrameArrived(FRAME_TYPE_TEMP);
            noteParamValue(PARAM_TEMP, "--".equals(getBodyTempText()) ? -1 : 1);
        }
    }

    private void readStatus(int[] data) {
        if (data.length < 1) {
            return;
        }
        if (data[0] == 0x40 && data.length >= 3) {
            ambientTemp = data[1] + "." + data[2];
        } else if (data[0] == 0x20 && data.length >= 3) {
            objectTemp = data[1] + "." + data[2];
        } else if (data[0] == 0xDD) {
            statusOne = "等待37℃校准";
        } else if (data[0] == 0xEE) {
            statusOne = "37℃校准完成";
            statusTwo = "等待42℃校准";
        } else if (data[0] == 0xFF) {
            statusTwo = "42℃校准完成";
            statusThree = "校准完成";
        }
    }

    /**
     * 断开监护仪后清掉实时窗口里的残留波形，避免页面继续显示已经过期的曲线。
     * 会话留档不动：报告要按这一段治疗真实采到的波形出图。
     */
    private void clearWaveSamples() {
        synchronized (waveLock) {
            ecgWaveSamples.clear();
            spo2WaveSamples.clear();
            respWaveSamples.clear();
        }
    }

    private void clearVitals(boolean clearDisplayValues) {
        frameBuffer.clear();
        synchronized (waveLock) {
            // 新链路清实时窗口与帧诊断；趋势、会话波形与测量历史属于当前病例会话。
            // 同设备重连保留显示标量，换设备才由 clearDisplayValues 明确清空。
            clearWaveSamples();
        }
        for (int i = 0; i < frameTypeCounts.length; i++) {
            frameTypeCounts[i] = 0;
        }
        for (int i = 0; i < lastFrameAtMs.length; i++) {
            lastFrameAtMs[i] = 0L;
        }
        for (int i = 0; i < lastValidAtMs.length; i++) {
            lastValidAtMs[i] = 0L;
        }
        for (int i = 0; i < invalidCount.length; i++) {
            invalidCount[i] = 0L;
        }
        lastReadyAtMs = 0L;
        lastRejectedParam = -1;
        lastRejectedValue = Double.NaN;
        lastRejectedAtMs = 0L;
        if (clearDisplayValues) {
            spo2 = -1;
            pulseRate = -1;
            heartRate = -1;
            sys = -1;
            map = -1.0;
            dia = -1;
            resp = -1;
            earTemp = "--";
            bodyTemp = "--";
            ambientTemp = "--";
            objectTemp = "--";
        }
        statusOne = "--";
        statusTwo = "--";
        statusThree = "--";
        lastFrameSummary = "尚未收到 AM4100 数据";
        lastRawFrame = "";
    }

    private void onDeviceFound(ScanResult result) {
        if (result == null || result.getDevice() == null) {
            return;
        }
        String name;
        String address;
        try {
            name = result.getDevice().getName();
            if (TextUtils.isEmpty(name) && result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            address = result.getDevice().getAddress();
        } catch (SecurityException exception) {
            setError("缺少蓝牙扫描权限，请先申请权限");
            return;
        }
        if (TextUtils.isEmpty(address)) {
            return;
        }
        if (TextUtils.isEmpty(name)) {
            name = "未知设备 " + addressSuffix(address);
        } else {
            rememberDeviceName(address, name);
        }
        DeviceItem item = new DeviceItem(address, name, result.getRssi());
        synchronized (devices) {
            int index = findDeviceIndex(address);
            if (index >= 0) {
                // 已经见过的设备只更新信号强度，位置不动：
                // 否则 RSSI 一抖动就整体重排，正在找的设备会来回跳。
                devices.set(index, new DeviceItem(item.deviceId, item.deviceName, item.rssi,
                        System.currentTimeMillis()));
            } else {
                // 新设备追加到末尾，已有设备的位置一动不动。
                appendDiscoveredDevice(item);
            }
        }
        emit();
    }

    private int findDeviceIndex(String address) {
        for (int i = 0; i < devices.size(); i++) {
            if (address.equals(devices.get(i).deviceId)) {
                return i;
            }
        }
        return -1;
    }

    private String findDeviceName(String address) {
        synchronized (devices) {
            for (DeviceItem item : devices) {
                if (address.equals(item.deviceId)) {
                    return item.deviceName;
                }
            }
        }
        return "";
    }

    /**
     * 连接状态页要显示蓝牙名称，不能只显示物理地址。
     *
     * 取名顺序：本次扫描结果 → 系统/已配对缓存名称 → 本地记住的名称。
     * 自动重连与"记忆设备"都是直接按 MAC 连接的，那时没有扫描列表，
     * 本地记住的名称是唯一还能拿到名字的来源。
     */
    @SuppressLint("MissingPermission")
    private String resolveDeviceName(String address, BluetoothDevice device) {
        String name = findDeviceName(address);
        if (!TextUtils.isEmpty(name)) {
            rememberDeviceName(address, name);
            return name;
        }
        if (device != null && !missingBluetoothConnectPermission()) {
            try {
                name = device.getName();
            } catch (Exception ignored) {
                name = "";
            }
            if (!TextUtils.isEmpty(name)) {
                rememberDeviceName(address, name);
                return name;
            }
        }
        return loadRememberedDeviceName(address);
    }

    /** 设备名称按地址落盘：扫描时见过的名字，之后按 MAC 直连也能显示出来。 */
    private void rememberDeviceName(String address, String name) {
        if (TextUtils.isEmpty(address) || TextUtils.isEmpty(name)) {
            return;
        }
        appContext.getSharedPreferences(BLE_SETTINGS_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(deviceNameKey(address), name)
                .apply();
    }

    private String loadRememberedDeviceName(String address) {
        if (TextUtils.isEmpty(address)) {
            return "";
        }
        return appContext.getSharedPreferences(BLE_SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getString(deviceNameKey(address), "");
    }

    private String deviceNameKey(String address) {
        return "monitor_device_name_" + address;
    }

    private String addressSuffix(String address) {
        if (TextUtils.isEmpty(address) || address.length() < 5) {
            return "";
        }
        return address.substring(Math.max(0, address.length() - 5));
    }

    private boolean missingBluetoothScanPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return permissionDenied(Manifest.permission.BLUETOOTH_SCAN);
        }
        // Android 10 起 BLE 扫描必须授精确位置权限，更早的版本粗略位置就够。
        if (Build.VERSION.SDK_INT >= 29) {
            return permissionDenied(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return permissionDenied(Manifest.permission.ACCESS_FINE_LOCATION)
                && permissionDenied(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private boolean missingBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT >= 31
                && permissionDenied(Manifest.permission.BLUETOOTH_CONNECT);
    }

    private boolean permissionDenied(String permission) {
        return appContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 蓝牙开关状态。
     *
     * <p>Android 12 起连读蓝牙开关都要「附近设备」权限，缺权限时
     * {@code BluetoothAdapter.isEnabled()} 会直接抛 SecurityException。
     * 这里统一包一层：拿不到就按“蓝牙不可用”处理，绝不能让 App 崩在这里。</p>
     */
    private boolean isBluetoothEnabled() {
        if (bluetoothAdapter == null) {
            return false;
        }
        try {
            return bluetoothAdapter.isEnabled();
        } catch (SecurityException error) {
            return false;
        }
    }

    /**
     * 取 BLE 扫描器。
     *
     * <p>Android 12 起没有扫描权限时系统会直接抛 SecurityException，
     * 这里统一兜住，返回 null 由调用方按“扫描器不可用”处理。</p>
     */
    private BluetoothLeScanner getBleScanner() {
        if (bluetoothAdapter == null) {
            return null;
        }
        try {
            return bluetoothAdapter.getBluetoothLeScanner();
        } catch (SecurityException error) {
            return null;
        }
    }

    /** 系统定位服务是否打开：Android 11 及以下，定位关掉时扫描结果会被系统过滤。 */
    private boolean isLocationServiceEnabled() {
        try {
            LocationManager manager =
                    (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
            if (manager == null) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= 28) {
                return manager.isLocationEnabled();
            }
            return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception error) {
            return true;
        }
    }

    /**
     * 扫描不出监护仪时的现场诊断。
     *
     * <p>BLE 扫描失败大多不是设备不在附近，而是权限或开关没到位：系统会把
     * 扫描结果整批静默过滤掉，App 侧看起来就是“一个设备都没有”。</p>
     */
    public String getScanDiagnosis() {
        if (bluetoothAdapter == null) {
            return "本机不支持蓝牙，无法扫描";
        }
        if (missingBluetoothScanPermission()) {
            return Build.VERSION.SDK_INT >= 31
                    ? "缺少「附近设备」权限，请在系统设置里允许本应用使用附近设备"
                    : "缺少位置权限，请在系统设置里允许本应用访问位置信息";
        }
        if (!isBluetoothEnabled()) {
            return "蓝牙未开启，请先打开系统蓝牙";
        }
        if (Build.VERSION.SDK_INT < 31 && !isLocationServiceEnabled()) {
            return "系统定位服务未开启，扫描结果会被系统过滤，请先打开定位";
        }
        return "";
    }

    private void addLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        synchronized (logs) {
            logs.add(0, time + "  " + message);
            while (logs.size() > MAX_LOG_COUNT) {
                logs.remove(logs.size() - 1);
            }
        }
    }

    private void setError(String message) {
        lastError = message;
        addLog("ERROR " + message);
        emit();
    }

    private void emit() {
        if (listener != null) {
            listener.onStateChanged();
        }
    }

    private void scheduleWaveEmit() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastWaveEmitMs;
        if (elapsed >= WAVE_UI_EMIT_INTERVAL_MS) {
            lastWaveEmitMs = now;
            emit();
            return;
        }
        if (!waveEmitPending) {
            waveEmitPending = true;
            handler.postDelayed(waveEmitRunnable, WAVE_UI_EMIT_INTERVAL_MS - elapsed);
        }
    }

    private String intText(int value) {
        return value < 0 ? "--" : String.valueOf(value);
    }

    private String toHex(byte[] data) {
        return toHex(data, data == null ? 0 : data.length);
    }

    private String toHex(byte[] data, int maxBytes) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int count = Math.min(data.length, Math.max(0, maxBytes));
        for (int i = 0; i < count; i++) {
            byte item = data[i];
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(String.format(Locale.US, "%02X", item & 0xFF));
        }
        if (data.length > count) {
            builder.append(" ...");
        }
        return builder.toString();
    }
}
