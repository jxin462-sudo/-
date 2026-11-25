package com.example.icuapk;

import android.annotation.SuppressLint;
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
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

/** Receives 32x24 thermal frames advertised by the Windows serial-to-BLE bridge. */
@SuppressLint("MissingPermission")
public final class ThermalBleManager {
    public interface Listener {
        void onThermalFrame(short[] centiDegrees, int width, int height, long timestampMs);

        void onThermalConnectionChanged(String state);

        void onThermalDevicesChanged(ArrayList<DeviceItem> devices);
    }

    public static final class DeviceItem {
        public final String address;
        public final String name;
        public final int rssi;

        DeviceItem(String address, String name, int rssi) {
            this.address = address;
            this.name = name;
            this.rssi = rssi;
        }
    }

    public static final UUID SERVICE_UUID = UUID.fromString("7f0d0001-9b2e-4ecb-a1c1-4c2ae9b50101");
    public static final UUID FRAME_CHARACTERISTIC_UUID = UUID.fromString("7f0d0002-9b2e-4ecb-a1c1-4c2ae9b50101");
    // 反向 ACK 通道：BLE 接收完一帧后写 ACK 给 PC 端，告知可以发下一帧
    public static final UUID ACK_SERVICE_UUID = UUID.fromString("7f0d0003-9b2e-4ecb-a1c1-4c2ae9b50101");
    public static final UUID ACK_CHARACTERISTIC_UUID = UUID.fromString("7f0d0004-9b2e-4ecb-a1c1-4c2ae9b50101");
    private static final UUID CLIENT_CONFIGURATION_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int HEADER_SIZE = 16;
    private static final int MAX_PACKET_COUNT = 16;
    private static final int MAX_FRAME_BYTES = 4096;
    private static final long RESCAN_DELAY_MS = 1500L;
    private static final int MAX_SERVICE_DISCOVERY_ATTEMPTS = 3;
    private static final int MAX_NOTIFY_ENABLE_ATTEMPTS = 3;
    private static final long SERVICE_DISCOVERY_TIMEOUT_MS = 2500L;
    private static final long NOTIFY_READY_FALLBACK_MS = 1800L;
    /** 连接失败时的最大尝试次数（含首次）：Windows 端 GATT Server 复位后首连常被拒。 */
    private static final int MAX_CONNECT_ATTEMPTS = 3;
    /** 只有信号强度变化时的设备列表推送间隔，避免扫描高频回调把网页刷爆。 */
    private static final long DEVICES_RSSI_INTERVAL_MS = 1000L;
    private static final String TAG = "IcuThermalBle";

    private final Context appContext;
    private final BluetoothAdapter bluetoothAdapter;
    /** 记住上一次连过的红外测温桥，冷启动后自动连回这一台。 */
    private final SharedPreferences prefs;
    private String savedAddress = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<DeviceItem> devices = new ArrayList<>();
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    // ackCharacteristic 由 onServicesDiscovered（主线程）写入，被 consumePacket（binder 线程）
    // 通过 writeAck 读取，必须 volatile 保证跨线程可见性。
    private volatile BluetoothGattCharacteristic ackCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;
    private boolean scanning;
    private boolean started;
    private boolean discoveringServices;
    private boolean protocolReady;
    private int serviceDiscoveryAttempts;
    private int notifyEnableAttempts;
    private Listener listener;
    private String pendingConnectAddress = "";
    /** 已连接红外测温桥的地址与名称，网页显示设备编号时用。 */
    private volatile String connectedAddress = "";
    private volatile String connectedName = "";
    /** 已经发起过的连接尝试次数，用于失败后的自动重试。 */
    private int connectAttempts;
    /** 上次向网页推送设备列表的时间戳，用于给 RSSI 变化限流。 */
    private long lastDevicesEmitMs;
    private int assemblingFrameId = -1;
    private int expectedPacketCount;
    private int frameWidth;
    private int frameHeight;
    private long frameTimestampMs;
    private byte[][] packetPayloads;
    private boolean[] receivedPackets;

    private final Runnable rescanRunnable = new Runnable() {
        @Override
        public void run() {
            if (started && gatt == null && !scanning) {
                beginScan();
            }
        }
    };

    private final Runnable mtuFallbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (started && gatt != null && !discoveringServices) {
                beginServiceDiscovery(gatt);
            }
        }
    };

    private final Runnable subscribeTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (started && gatt != null && notifyCharacteristic != null && !protocolReady) {
                // 部分 Windows GATT Server 已接受 CCCD 写入却不回调 onDescriptorWrite。
                // 不能把这个 Android 回调缺失误报成电脑端无响应。
                markProtocolReady();
            }
        }
    };

    private final Runnable serviceDiscoveryTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (started && gatt != null && discoveringServices) {
                discoveringServices = false;
                beginServiceDiscovery(gatt);
            }
        }
    };

    // 兜底超时：connectGatt 调出去后 8 秒内 onConnectionStateChange 还没触发，
    // 说明连接卡死在 BLE 层（最常见：PC 端 winsdk 状态卡死）。
    // 此时主动 disconnect + close，UI 立刻报"连接超时，请重试"，
    // 用户再点一次就能连上——避免"点连接没反应"假死。
    private final Runnable connectTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!started || gatt == null) {
                return;
            }
            BluetoothGatt stuck = gatt;
            gatt = null;
            try {
                stuck.disconnect();
                stuck.close();
            } catch (Exception ignored) {
            }
            resetAssembler();
            // 保留 pendingConnectAddress 让用户重试时不用重新选设备
            if (scheduleConnectRetry("连接超时")) {
                return;
            }
            emitConnection("连接超时，请重试");
        }
    };

    private final Runnable connectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!started || bluetoothAdapter == null || pendingConnectAddress.isEmpty() || gatt != null) {
                return;
            }
            try {
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(pendingConnectAddress);
                // 1500ms 延迟而不是 450ms：给 Android BLE 栈足够时间把上一次 gatt 的
                // 底层资源（pending connect、bonding 状态、descriptor 缓存）清理完，
                // 避免新旧 gatt 资源碰撞导致 connectGatt 静默失败。
                BluetoothGatt newGatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                Log.i(TAG, "connectGatt address=" + pendingConnectAddress + " attempt=" + (connectAttempts + 1)
                        + "/" + MAX_CONNECT_ATTEMPTS + " gatt=" + (newGatt != null));
                if (newGatt == null) {
                    // connectGatt 偶发返回 null（资源碰撞或蓝牙栈饱和），必须显式提示
                    // ——否则用户永远卡在"正在连接..."无任何反馈
                    if (scheduleConnectRetry("启动连接失败")) {
                        return;
                    }
                    emitConnection("启动连接失败，请重试");
                    return;
                }
                gatt = newGatt;
                // 8 秒兜底：onConnectionStateChange 不触发就强制断开。
                // 成功路径（gattCallback onConnectionStateChange）会移除这个回调。
                handler.removeCallbacks(connectTimeoutRunnable);
                handler.postDelayed(connectTimeoutRunnable, 8000L);
            } catch (IllegalArgumentException error) {
                emitConnection("红外测温设备地址无效");
            }
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, @NonNull ScanResult result) {
            if (!started || gatt != null || result.getDevice() == null) {
                return;
            }
            /* ★ 2026-10-12：改为不过滤扫描 + 软件匹配服务 UUID。
               部分平板蓝牙栈把带 serviceUuid 的 ScanFilter 交给固件过滤，
               而 Windows GATT 桥把 128 位服务 UUID 放在扫描响应里，固件过滤会漏掉，
               表现为"永远搜不到红外测温桥"。软件匹配能同时看到广播包+扫描响应。 */
            if (!hasThermalService(result)) {
                return;
            }
            BluetoothDevice device = result.getDevice();
            updateDevice(device.getAddress(), device.getName(), result.getRssi());
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            emitConnection("红外测温扫描失败: " + errorCode);
            scheduleRescan();
        }
    };

    private static boolean hasThermalService(ScanResult result) {
        if (result.getScanRecord() == null || result.getScanRecord().getServiceUuids() == null) {
            return false;
        }
        for (ParcelUuid id : result.getScanRecord().getServiceUuids()) {
            if (SERVICE_UUID.equals(id.getUuid())) {
                return true;
            }
        }
        return false;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt connection, int status, int newState) {
            // 任何状态变化都取消 connect 超时——成功取消避免误触发，失败也取消（已主动关闭）
            handler.removeCallbacks(connectTimeoutRunnable);
            Log.i(TAG, "onConnectionStateChange status=" + status + " newState=" + newState);
            if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectAttempts = 0;
                // 已经连上，清掉待连地址：后续掉线走"已断开"分支，不会再误触发重连。
                pendingConnectAddress = "";
                emitConnection("正在订阅红外测温数据...");
                serviceDiscoveryAttempts = 0;
                notifyEnableAttempts = 0;
                protocolReady = false;
                if (connection.requestMtu(517)) {
                    handler.postDelayed(mtuFallbackRunnable, 1200L);
                } else {
                    beginServiceDiscovery(connection);
                }
                return;
            }
            closeConnection(connection);
            // 首连失败自动重试：Windows 端 GATT Server 复位后的第一次连接常被拒，
            // 重试通常就能连上，不必让用户重新点一次设备。
            if (status != BluetoothGatt.GATT_SUCCESS && scheduleConnectRetry("连接失败(" + status + ")")) {
                return;
            }
            emitConnection(pendingConnectAddress.isEmpty()
                    ? "红外测温桥已断开"
                    : "连接失败(" + status + ")，请重试");
            scheduleRescan();
        }

        @Override
        public void onMtuChanged(BluetoothGatt connection, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && connection == gatt) {
                beginServiceDiscovery(connection);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt connection, int status) {
            discoveringServices = false;
            handler.removeCallbacks(mtuFallbackRunnable);
            handler.removeCallbacks(serviceDiscoveryTimeoutRunnable);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                retryServiceDiscovery(connection, "红外测温服务发现失败");
                return;
            }
            BluetoothGattService service = connection.getService(SERVICE_UUID);
            BluetoothGattCharacteristic characteristic = service == null
                    ? null : service.getCharacteristic(FRAME_CHARACTERISTIC_UUID);
            if (characteristic == null || !connection.setCharacteristicNotification(characteristic, true)) {
                closeConnection(connection);
                emitConnection("红外测温 Notify 通道不可用");
                scheduleRescan();
                return;
            }
            notifyCharacteristic = characteristic;
            // 查找反向 ACK Write 特征（用于告诉 PC 端可以发下一帧）
            BluetoothGattService ackService = connection.getService(ACK_SERVICE_UUID);
            if (ackService != null) {
                ackCharacteristic = ackService.getCharacteristic(ACK_CHARACTERISTIC_UUID);
            }
            if (ackCharacteristic == null && service != null) {
                // 兼容：ACK 特征可能在同一 Notify 服务下
                ackCharacteristic = service.getCharacteristic(ACK_CHARACTERISTIC_UUID);
            }
            enableNotifyChannel(connection);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt connection, BluetoothGattDescriptor descriptor, int status) {
            if (!FRAME_CHARACTERISTIC_UUID.equals(descriptor.getCharacteristic().getUuid())) {
                return;
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                markProtocolReady();
            } else {
                enableNotifyChannel(connection);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic) {
            if (FRAME_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                markProtocolReady();
                consumePacket(characteristic.getValue());
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            if (FRAME_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                markProtocolReady();
                consumePacket(value);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt connection, BluetoothGattCharacteristic characteristic,
                                         int status) {
            // ACK 特征 Read 回调。winsdk 自动返回 static_value（PC 端写入的当前 frame_id）。
            // 仅做诊断用：校验 PC 端发帧与 Android 端读取是否同步。
            if (ACK_CHARACTERISTIC_UUID.equals(characteristic.getUuid())
                    && status == BluetoothGatt.GATT_SUCCESS) {
                byte[] value = characteristic.getValue();
                if (value != null && value.length >= 4
                        && value[0] == 'A' && value[1] == 'C' && value[2] == 'K') {
                    int ackedFrameId = value[3] & 0xFF;
                    int currentFrameId = assemblingFrameId >= 0 ? assemblingFrameId : -1;
                    if (currentFrameId >= 0 && ackedFrameId != currentFrameId) {
                        android.util.Log.w("ThermalBle",
                                "ACK frame_id mismatch: PC=" + ackedFrameId
                                        + " Android=" + currentFrameId
                                        + " (PC 发帧过快或平板丢帧)");
                    }
                }
            }
        }
    };

    public ThermalBleManager(Context context) {
        appContext = context.getApplicationContext();
        BluetoothManager manager = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        prefs = appContext.getSharedPreferences("icu_ble_autoreconnect", Context.MODE_PRIVATE);
        String lastAddress = prefs.getString("thermal_last_address", "");
        savedAddress = lastAddress == null ? "" : lastAddress;
    }

    public void setListener(Listener value) {
        listener = value;
    }

    public void start() {
        started = true;
        if (!isBluetoothEnabled()) {
            emitConnection("请开启蓝牙以连接红外测温桥");
            return;
        }
        if (gatt == null && !scanning) {
            devices.clear();
            emitDevices(true);
            beginScan();
        }
    }

    public void connect(String address) {
        if (!isBluetoothEnabled() || address == null || address.isEmpty()) {
            emitConnection("无法连接红外测温桥");
            return;
        }
        stop();
        started = true;
        pendingConnectAddress = address;
        connectedAddress = address;
        connectedName = findDeviceName(address);
        connectAttempts = 0;
        // 记住这台桥：下次打开 App 直接自动连回来
        savedAddress = address;
        prefs.edit().putString("thermal_last_address", address).apply();
        Log.i(TAG, "connect address=" + address);
        emitConnection("正在连接红外测温桥...");
        // 1500ms 而非 450ms：BLE 栈在 gatt.close() 之后仍可能有挂起操作，
        // 给够时间清完再发起新 connectGatt，避免与上一次的资源碰撞。
        handler.postDelayed(connectRunnable, 1500L);
    }

    /**
     * App 启动、蓝牙重新打开、页面回到前台时调用：
     * 之前连过这台红外测温桥、现在又没连上，就自动把它连回来。
     */
    public void tryAutoConnect() {
        if (!isBluetoothEnabled()) {
            return;
        }
        if (missingBluetoothConnectPermission()) {
            return;
        }
        if (TextUtils.isEmpty(savedAddress) || started || gatt != null) {
            return;
        }
        connect(savedAddress);
    }

    private boolean missingBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT >= 31
                && appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED;
    }

    /** Android 12 起扫描由「附近设备」权限接管，老系统仍要看位置权限。 */
    private boolean missingBluetoothScanPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED;
        }
        return appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 蓝牙开关状态。
     *
     * <p>Android 12 起连读蓝牙开关都要「附近设备」权限，缺权限时
     * {@code BluetoothAdapter.isEnabled()} 会直接抛 SecurityException，
     * 这里统一包一层，不能让 App 崩在这里。</p>
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

    /** 取 BLE 扫描器：缺权限时系统会直接抛异常，这里统一兜住。 */
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

    /**
     * 连接失败后的自动重试。
     *
     * @return true 表示已经安排了下一次连接，调用方不要再提示最终失败。
     */
    private boolean scheduleConnectRetry(String reason) {
        if (!started || pendingConnectAddress.isEmpty() || connectAttempts >= MAX_CONNECT_ATTEMPTS) {
            return false;
        }
        connectAttempts++;
        Log.i(TAG, "connect retry " + connectAttempts + "/" + MAX_CONNECT_ATTEMPTS + " reason=" + reason);
        emitConnection(reason + "，正在重试(" + connectAttempts + "/" + MAX_CONNECT_ATTEMPTS + ")...");
        handler.removeCallbacks(connectRunnable);
        handler.postDelayed(connectRunnable, 1500L);
        return true;
    }

    public ArrayList<DeviceItem> getDevices() {
        // 与主机/监护仪列表一致：EM / AM 开头的信号排前面，组内保持发现顺序。
        ArrayList<DeviceItem> preferred = new ArrayList<>();
        ArrayList<DeviceItem> others = new ArrayList<>();
        for (DeviceItem item : devices) {
            if (DeviceNameOrder.isPreferred(item.name)) {
                preferred.add(item);
            } else {
                others.add(item);
            }
        }
        preferred.addAll(others);
        return preferred;
    }

    /** 当前连接的红外测温桥地址，网页上的"编号"。 */
    public String getConnectedAddress() {
        return connectedAddress;
    }

    /** 当前连接的红外测温桥名称。 */
    public String getConnectedName() {
        return connectedName;
    }

    /** 是否已经连接并且订阅成功，也就是可以正常收到热成像帧。 */
    public boolean isConnected() {
        return gatt != null && protocolReady;
    }

    private String findDeviceName(String address) {
        for (DeviceItem item : devices) {
            if (item.address.equals(address)) {
                return item.name;
            }
        }
        return "";
    }

    public void stop() {
        started = false;
        pendingConnectAddress = "";
        connectedAddress = "";
        connectedName = "";
        handler.removeCallbacks(rescanRunnable);
        handler.removeCallbacks(mtuFallbackRunnable);
        handler.removeCallbacks(subscribeTimeoutRunnable);
        handler.removeCallbacks(serviceDiscoveryTimeoutRunnable);
        handler.removeCallbacks(connectTimeoutRunnable);
        handler.removeCallbacks(connectRunnable);
        stopScan();
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        ackCharacteristic = null;
        notifyCharacteristic = null;
        protocolReady = false;
        resetAssembler();
    }

    private void beginScan() {
        if (!started || !isBluetoothEnabled()) {
            return;
        }
        if (missingBluetoothScanPermission()) {
            // 没有扫描权限时系统会把结果整批过滤掉，现场表现就是“搜不到”，
            // 这里直接把原因说出来，免得使用者以为是桥坏了。
            emitConnection(Build.VERSION.SDK_INT >= 31
                    ? "缺少「附近设备」权限，无法搜索红外测温桥"
                    : "缺少位置权限，无法搜索红外测温桥");
            return;
        }
        scanner = getBleScanner();
        if (scanner == null) {
            emitConnection("当前设备不支持 BLE 扫描");
            return;
        }
        /* ★ 2026-10-12：不再用 setServiceUuid 硬件过滤（原因见 scanCallback），
           改为全量扫描 + hasThermalService 软件匹配 */
        ArrayList<ScanFilter> filters = new ArrayList<>();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanning = true;
        emitConnection("正在搜索红外测温桥...");
        try {
            scanner.startScan(filters, settings, scanCallback);
        } catch (Exception error) {
            scanning = false;
            emitConnection("红外测温扫描启动失败: " + error.getMessage());
            scheduleRescan();
        }
    }

    private void stopScan() {
        if (scanner != null && scanning) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        scanning = false;
    }

    private void scheduleRescan() {
        handler.removeCallbacks(rescanRunnable);
        if (started) {
            handler.postDelayed(rescanRunnable, RESCAN_DELAY_MS);
        }
    }

    private void updateDevice(String address, String name, int rssi) {
        int index = -1;
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).address.equals(address)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            devices.add(new DeviceItem(address, name == null || name.trim().isEmpty() ? "红外测温桥" : name, rssi));
            emitDevices(true);
            return;
        }
        DeviceItem current = devices.get(index);
        String resolvedName = name == null || name.trim().isEmpty() ? current.name : name;
        if (current.rssi == rssi && current.name.equals(resolvedName)) {
            return;
        }
        devices.set(index, new DeviceItem(address, resolvedName, rssi));
        emitDevices(false);
    }

    /**
     * 设备列表推送。
     *
     * 红外测温桥持续广播，扫描回调每秒可达几十次；如果每次都往网页推事件，
     * 网页会不停重建设备列表，用户按下的按钮会在手指抬起之前被换掉，
     * 表现为"点了没反应"。这里按"结构变化立即推、信号变化每秒最多推一次"处理。
     */
    private void emitDevices(boolean structuralChange) {
        long now = SystemClock.elapsedRealtime();
        if (!structuralChange && now - lastDevicesEmitMs < DEVICES_RSSI_INTERVAL_MS) {
            return;
        }
        lastDevicesEmitMs = now;
        final Listener target = listener;
        if (target != null) {
            final ArrayList<DeviceItem> snapshot = getDevices();
            handler.post(new Runnable() {
                @Override
                public void run() {
                    target.onThermalDevicesChanged(snapshot);
                }
            });
        }
    }

    private void closeConnection(BluetoothGatt connection) {
        handler.removeCallbacks(mtuFallbackRunnable);
        handler.removeCallbacks(subscribeTimeoutRunnable);
        handler.removeCallbacks(serviceDiscoveryTimeoutRunnable);
        handler.removeCallbacks(connectTimeoutRunnable);
        discoveringServices = false;
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
            }
        }
        if (gatt == connection) {
            gatt = null;
        }
        ackCharacteristic = null;
        notifyCharacteristic = null;
        protocolReady = false;
        resetAssembler();
    }

    private void beginServiceDiscovery(BluetoothGatt connection) {
        if (connection == null || connection != gatt || discoveringServices) {
            return;
        }
        handler.removeCallbacks(mtuFallbackRunnable);
        if (serviceDiscoveryAttempts >= MAX_SERVICE_DISCOVERY_ATTEMPTS) {
            closeConnection(connection);
            emitConnection("红外测温服务发现多次失败，正在重新扫描");
            scheduleRescan();
            return;
        }
        discoveringServices = true;
        serviceDiscoveryAttempts++;
        if (!connection.discoverServices()) {
            discoveringServices = false;
            retryServiceDiscovery(connection, "红外测温服务发现未启动");
            return;
        }
        handler.removeCallbacks(serviceDiscoveryTimeoutRunnable);
        handler.postDelayed(serviceDiscoveryTimeoutRunnable, SERVICE_DISCOVERY_TIMEOUT_MS);
    }

    private void retryServiceDiscovery(final BluetoothGatt connection, String reason) {
        if (connection == null || connection != gatt) {
            return;
        }
        if (serviceDiscoveryAttempts >= MAX_SERVICE_DISCOVERY_ATTEMPTS) {
            closeConnection(connection);
            emitConnection(reason + "，正在重新扫描");
            scheduleRescan();
            return;
        }
        emitConnection(reason + "，正在重试");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                beginServiceDiscovery(connection);
            }
        }, 350L);
    }

    private void enableNotifyChannel(final BluetoothGatt connection) {
        if (connection == null || connection != gatt || notifyCharacteristic == null || protocolReady) {
            return;
        }
        if (notifyEnableAttempts >= MAX_NOTIFY_ENABLE_ATTEMPTS) {
            closeConnection(connection);
            emitConnection("红外测温通知开启多次失败，正在重新扫描");
            scheduleRescan();
            return;
        }
        BluetoothGattDescriptor descriptor = notifyCharacteristic.getDescriptor(CLIENT_CONFIGURATION_UUID);
        if (descriptor == null) {
            closeConnection(connection);
            emitConnection("红外测温通知描述符缺失");
            scheduleRescan();
            return;
        }
        notifyEnableAttempts++;
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        if (connection.writeDescriptor(descriptor)) {
            handler.removeCallbacks(subscribeTimeoutRunnable);
            handler.postDelayed(subscribeTimeoutRunnable, NOTIFY_READY_FALLBACK_MS);
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                enableNotifyChannel(connection);
            }
        }, 350L);
    }

    private void markProtocolReady() {
        if (protocolReady) {
            return;
        }
        protocolReady = true;
        handler.removeCallbacks(subscribeTimeoutRunnable);
        emitConnection("红外热图已连接");
    }

    private void consumePacket(byte[] packet) {
        if (packet == null || packet.length < HEADER_SIZE || packet[0] != 'T' || packet[1] != 'H' || packet[2] != 1) {
            return;
        }
        int frameId = unsignedShort(packet, 4);
        int packetIndex = unsignedByte(packet[6]);
        int packetCount = unsignedByte(packet[7]);
        int payloadLength = unsignedShort(packet, 8);
        int width = unsignedByte(packet[10]);
        int height = unsignedByte(packet[11]);
        long timestamp = unsignedInt(packet, 12);
        if (packetCount < 1 || packetCount > MAX_PACKET_COUNT || packetIndex >= packetCount
                || payloadLength > packet.length - HEADER_SIZE || width < 1 || height < 1
                || width * height * 2 > MAX_FRAME_BYTES) {
            return;
        }
        if (frameId != assemblingFrameId || packetCount != expectedPacketCount
                || width != frameWidth || height != frameHeight) {
            beginFrame(frameId, packetCount, width, height, timestamp);
        }
        if (!receivedPackets[packetIndex]) {
            packetPayloads[packetIndex] = Arrays.copyOfRange(packet, HEADER_SIZE, HEADER_SIZE + payloadLength);
            receivedPackets[packetIndex] = true;
        }
        for (boolean received : receivedPackets) {
            if (!received) {
                return;
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(frameWidth * frameHeight * 2);
        for (byte[] payload : packetPayloads) {
            output.write(payload, 0, payload.length);
        }
        byte[] frame = output.toByteArray();
        int expectedBytes = frameWidth * frameHeight * 2;
        if (frame.length == expectedBytes) {
            short[] centiDegrees = new short[frameWidth * frameHeight];
            for (int i = 0; i < centiDegrees.length; i++) {
                int offset = i * 2;
                centiDegrees[i] = (short) ((frame[offset] & 0xff) | ((frame[offset + 1] & 0xff) << 8));
            }
            // 帧重组完成：Read 反向 ACK（PC 端 static_value 自动返回当前 frame_id）
            readAck();
            Listener target = listener;
            if (target != null) {
                target.onThermalFrame(centiDegrees, frameWidth, frameHeight, frameTimestampMs);
            }
        }
        resetAssembler();
    }

    /**
     * Read 反向 ACK：BLE 接收完一帧后调用 gatt.readCharacteristic(ackCharacteristic)。
     * winsdk 会自动返回 PC 端写入的 static_value（当前 frame_id）。
     * 结果通过 onCharacteristicRead 回调接收（仅做诊断）。
     * 失败时静默（PC 端不依赖此 ACK，靠 FPS 节流保证稳定）。
     */
    @SuppressLint("MissingPermission")
    private void readAck() {
        if (ackCharacteristic == null || gatt == null) {
            return;
        }
        try {
            // 不关心返回值：失败时不影响主流程
            gatt.readCharacteristic(ackCharacteristic);
        } catch (Exception ignored) {
        }
    }

    private void beginFrame(int frameId, int packetCount, int width, int height, long timestamp) {
        assemblingFrameId = frameId;
        expectedPacketCount = packetCount;
        frameWidth = width;
        frameHeight = height;
        frameTimestampMs = timestamp;
        packetPayloads = new byte[packetCount][];
        receivedPackets = new boolean[packetCount];
    }

    private void resetAssembler() {
        assemblingFrameId = -1;
        expectedPacketCount = 0;
        frameWidth = 0;
        frameHeight = 0;
        packetPayloads = null;
        receivedPackets = null;
    }

    private void emitConnection(final String state) {
        final Listener target = listener;
        if (target != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    target.onThermalConnectionChanged(state);
                }
            });
        }
    }

    private static int unsignedByte(byte value) {
        return value & 0xff;
    }

    private static int unsignedShort(byte[] value, int offset) {
        return unsignedByte(value[offset]) | (unsignedByte(value[offset + 1]) << 8);
    }

    private static long unsignedInt(byte[] value, int offset) {
        return (long) unsignedByte(value[offset])
                | ((long) unsignedByte(value[offset + 1]) << 8)
                | ((long) unsignedByte(value[offset + 2]) << 16)
                | ((long) unsignedByte(value[offset + 3]) << 24);
    }
}
