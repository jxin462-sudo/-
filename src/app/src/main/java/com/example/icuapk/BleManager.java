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
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;


import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import androidx.annotation.NonNull;

@SuppressLint({"NewApi", "MissingPermission"})
@SuppressWarnings({"unused", "Java8ListSort"})
public class BleManager {
    public interface Listener {
        void onStateChanged();
    }

    public static final UUID ICU_SERVICE_UUID = UUID.fromString("0000a002-0000-1000-8000-00805f9b34fb");
    public static final UUID ICU_NOTIFY_UUID = UUID.fromString("0000c305-0000-1000-8000-00805f9b34fb");
    public static final UUID ICU_WRITE_UUID = UUID.fromString("0000c304-0000-1000-8000-00805f9b34fb");
    private static final UUID CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String DEFAULT_ZONE = "right";
    private static final String BLE_SETTINGS_PREFS = "icu_ble_settings";
    private static final String TREATMENT_CLOCK_PREF_PREFIX = "treatment_clock_v1|";
    private static final int TREATMENT_CLOCK_VERSION = 1;
    /**
     * 最后一份活动治疗计时的兜底快照，按 recordNo + 舱位校验。
     * 冷启动时 connectedDeviceId 还是空的，只有「主机地址|舱位」这一份会读不到；
     * 没有它，正在治疗的病例会被当成新会话从 00:00 重新起算。
     */
    private static final String TREATMENT_CLOCK_LAST_KEY = "treatment_clock_last_v1";
    private static final int ICU_MTU = 517;
    private static final int MAX_LOG_COUNT = 120;
    /**
     * Notify 分片缓冲的最大长度。
     *
     * <p>主机的一帧 JSON 可能超过一个 BLE 包，被拆成好几片下发。
     * 拼到这么长还没凑出一帧完整 JSON，说明这帧已经对不上了，
     * 留着只会污染后面的帧，直接清空重来。</p>
     */
    private static final int MAX_NOTIFY_BUFFER_CHARS = 32768;
    private static final long SCAN_TIMEOUT_MS = 8000L;
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
     * 结果就是设备重新上电了 App 也扫不到。定期重启一次扫描来规避这个问题。</p>
     */
    private static final long AUTO_RECONNECT_SCAN_KEEP_ALIVE_MS = 15000L;
    /**
     * 自动轮询间隔。
     *
     * <p>每次只查询一个舱，250 毫秒后轮到另一个舱，正常通讯时单舱
     * 约 0.5 秒查询一次。同名总表查询不并行：固件若省略 zone，
     * 就不会把唯一等待中的左舱回包按先进先出误归给右舱。队列去重、
     * 控制优先和等待超时仍然保留，主机较慢时不会继续堆积轮询指令。
     * 背景舱刚漏回时先退避，不能反复挡住当前舱的实时刷新。</p>
     */
    private static final long AUTO_UPDATE_MS = 250L;
    /** 背景舱查询未回答后的再次尝试间隔；当前舱不受此退避影响。 */
    private static final long BACKGROUND_STATUS_RETRY_MS = 10000L;
    /** 控制后尽快回读当前舱真实状态；只读查询，不重复物理控制。 */
    private static final long CONTROL_STATUS_REFRESH_DELAY_MS = 150L;
    private static final long SERVICE_DISCOVERY_DELAY_MS = 350L;
    private static final long SERVICE_DISCOVERY_TIMEOUT_MS = 3500L;
    /**
     * 写回执超时。
     *
     * <p>实测一次写入约 120ms 就回执，留 7 倍余量足够。
     * 之前 1200ms 的超时一旦触发，队列会被卡住一秒多，
     * 用户看到的就是点了没反应。</p>
     */
    private static final long WRITE_CALLBACK_TIMEOUT_MS = 900L;
    /**
     * 主机回包最长等待时间。
     *
     * <p>超过这么久还没回包的指令，主机多半不会再回了。
     * 它一直占着队头，后面所有回包的归属都会被带偏，
     * 主机随后那句 error 回包就会算到别人头上。</p>
     */
    private static final long REPLY_WAIT_MAX_MS = 2500L;
    /** writeCharacteristic 明确返回未启动时，隔多久再尝试启动写入。 */
    private static final long WRITE_RETRY_DELAY_MS = 200L;
    /** 写回执超时后先留出迟到回执窗口，再启动下一条，避免本地 GATT 忙。 */
    private static final long WRITE_RECOVERY_DELAY_MS = 300L;
    /** 当前舱环境值缺项或超过这个时间没更新时，补一轮分项查询。 */
    private static final long ENVIRONMENT_STALE_MS = 15000L;
    /** 同一舱的分项回退最小间隔，避免重新用查询冲满主机接收队列。 */
    private static final long ENVIRONMENT_FALLBACK_MIN_INTERVAL_MS = 15000L;
    /**
     * 下发开关后主机回读有延迟，这段时间内不能用回读到的旧值覆盖刚下发的值。
     *
     * <p>实测这台主机（EM-150Vet-20）的指令回执只要 60~125 毫秒，
     * 状态回读也就 250 毫秒上下，2 秒足够覆盖慢一点的链路。</p>
     */
    private static final long PENDING_SWITCH_TIMEOUT_MS = 2000L;
    /** 开关下发后开始只读回读的等待时间；超时只查状态，不重发开关。 */
    private static final long SWITCH_CONFIRM_TIMEOUT_MS = 800L;
    /**
     * 主机确认开关之后的一小段时间。
     *
     * <p>主机确认之后可能还会回一帧处理之前的旧状态快照，
     * 拿它覆盖刚确认的值，界面上的开关就会在开和关之间来回跳。
     * 这段时间内忽略和确认值矛盾的旧值。</p>
     */
    private static final long SWITCH_CONFIRM_GRACE_MS = 900L;
    /**
     * 连续几帧状态都报的还是旧值，就结束本次待确认并以主机为准。
     *
     * <p>主机每收到一条开关指令都可能再鸣一声。回执缺失或旧状态都不是
     * “主机没有执行”的充分证据，因此这里只回读，不再自动重发物理开关。</p>
     */
    private static final int SWITCH_CONTRADICT_FRAMES = 2;

    /**
     * 批量连续下发标记：护理模式「保存并开启」一次性下发整套设定时为 true。
     *
     * <p>期间不逐条回读、不登记开关待确认、不重发、不插状态查询，
     * 让一条条指令紧挨着连续送到主机，不被这些额外通信拆成
     * 「滴、滴、滴…」。整批结束后统一刷新一次状态。</p>
     */
    private boolean quietBatch = false;

    /** 开始一批连续控制指令（护理模式整套设定下发前调用）。 */
    public void beginQuietBatch() {
        quietBatch = true;
    }

    /** 结束一批连续控制指令：整批发完后统一刷新一次主机状态。 */
    public void endQuietBatch() {
        quietBatch = false;
        scheduleStatusRefresh();
        emit();
    }

    /**
     * 主机已经回执"收到指令"、却迟迟不报状态时，最多主动回读几轮。
     *
     * <p>回读就是把"主机到底做没做"问清楚的手段：问了几轮还是
     * 对不上，就说明这次下发没落实，界面按主机最后一次的说法显示，
     * 同时提示状态未确认，让操作者自己去核对主机。</p>
     */
    private static final int SWITCH_MAX_VERIFY_ROUND = 3;
    /**
     * 回读查询发出之后，等多久还没回包就认为这次查询没人管了。
     *
     * <p>主机回包实测 60~125 毫秒，1.5 秒已经有十几倍余量。</p>
     */
    private static final long SWITCH_READ_REPLY_TIMEOUT_MS = 1500L;
    /**
     * 主机回执之后，最多保护多久。
     *
     * <p>保护期内不接受主机报出的矛盾值（那多半是它还没更新完的旧快照）。
     * 保护不能无限延长：超过这个时间还没对上就以主机为准，
     * 免得界面一直显示着一个主机根本没有的状态。</p>
     */
    private static final long SWITCH_ACK_PROTECT_MS = 3000L;
    /** 发送队列里最多堆积多少条指令，超出之后暂停状态轮询。 */
    private static final int WRITE_QUEUE_LIMIT = 8;
    /** 开关标识：本地字段、主机指令、回包字段统一用这一套 key 对应。 */
    private static final String SWITCH_CO2 = "co2_enable";
    private static final String SWITCH_COLD_LIGHT = "cold_light";
    private static final String SWITCH_WARM_LIGHT = "warm_light";
    private static final String SWITCH_RED = "red_enable";
    private static final String SWITCH_BLUE = "blue_enable";
    private static final String SWITCH_OUTER_CYCLE = "outer_cycle";
    private static final String SWITCH_INNER_CYCLE = "inner_cycle";
    private static final String SWITCH_NEBULIZER = "nebulizer_enable";
    private static final String SWITCH_ANION = "anion_enable";
    private static final String SWITCH_UV = "uv_enable";
    private static final String SWITCH_O2 = "o2_enable";

    /** 开关标识 → 主机指令名：下发和回包归属共用这张表。 */
    private static final HashMap<String, String> SWITCH_COMMANDS = new HashMap<>();

    static {
        SWITCH_COMMANDS.put(SWITCH_CO2, "set_co2_enable");
        SWITCH_COMMANDS.put(SWITCH_COLD_LIGHT, "set_cold_light");
        SWITCH_COMMANDS.put(SWITCH_WARM_LIGHT, "set_warm_light");
        SWITCH_COMMANDS.put(SWITCH_RED, "set_red_enable");
        SWITCH_COMMANDS.put(SWITCH_BLUE, "set_blue_enable");
        SWITCH_COMMANDS.put(SWITCH_OUTER_CYCLE, "set_outer_cycle");
        SWITCH_COMMANDS.put(SWITCH_INNER_CYCLE, "set_inner_cycle");
        SWITCH_COMMANDS.put(SWITCH_NEBULIZER, "set_nebulizer_enable");
        SWITCH_COMMANDS.put(SWITCH_ANION, "set_anion_enable");
        SWITCH_COMMANDS.put(SWITCH_UV, "set_uv_enable");
        SWITCH_COMMANDS.put(SWITCH_O2, "set_o2_enable");
    }

    /** 能证明主机真的回了状态内容的字段；裸 ok、版本回声或任意 Notify 不算。 */
    private static final String[] HOST_STATE_FIELDS = {
            "run_time", "o2_enable",
            "uv", "uv_enable", "blue", "blue_light", "blue_enable",
            "red", "red_light", "red_enable", "nebulizer", "nebulizer_enable",
            "anion", "anion_enable", "inner_cycle", "inner_cycle_enable",
            "outer_cycle", "outer_cycle_enable", "cold_light", "cold_light_enable",
            "warm_light", "warm_light_enable", "co2_enable"
    };

    /**
     * 开关标识对应的主机回读指令。
     *
     * <p>实测这版主机（V1.01.02）没有单开关回读指令：get_cold_light 之类
     * 一律回 {"cmd":"error"}。这张表留着是为了兼容认这些指令的主机，
     * App 默认不主动下发它们，开关状态统一从 get_all_status 里读。</p>
     */
    private static final HashMap<String, String> SWITCH_READERS = new HashMap<>();

    /**
     * 回读指令 → 开关标识：收到这类回包时用它找到对应的本地开关。
     */
    private static final HashMap<String, String> SWITCH_READER_KEYS = new HashMap<>();

    static {
        SWITCH_READERS.put(SWITCH_COLD_LIGHT, "get_cold_light");
        SWITCH_READERS.put(SWITCH_WARM_LIGHT, "get_warm_light");
        SWITCH_READERS.put(SWITCH_RED, "get_red_light");
        SWITCH_READERS.put(SWITCH_BLUE, "get_blue_light");
        SWITCH_READERS.put(SWITCH_UV, "get_uv");
        SWITCH_READERS.put(SWITCH_NEBULIZER, "get_nebulizer");
        SWITCH_READERS.put(SWITCH_ANION, "get_anion");
        SWITCH_READERS.put(SWITCH_OUTER_CYCLE, "get_outer_cycle");
        SWITCH_READERS.put(SWITCH_INNER_CYCLE, "get_inner_cycle");
        SWITCH_READERS.put(SWITCH_O2, "get_o2");
        SWITCH_READERS.put(SWITCH_CO2, "get_co2");
        // 反向表由正向表生成：两张表不会再写出不一致的对应关系。
        for (String key : SWITCH_READERS.keySet()) {
            SWITCH_READER_KEYS.put(SWITCH_READERS.get(key), key);
        }
    }

    /**
     * 链路静默判定：超过这个时间没有收到主机数据，就认为链路真的断了。
     *
     * 设备被直接断电时 Android 常常不会回调 GATT 断开，只靠那个回调的话
     * App 会一直以为还连着：数值停住、治疗时长还在按墙钟往上走。
     *
     * <p>这个阈值必须远大于状态轮询间隔（{@link #AUTO_UPDATE_MS}）：
     * 主机不会主动推数据，只有 App 去问才有回包，轮询之间主机本来是安静的。
     * 阈值比轮询间隔还小的话，每一轮轮询的间隙都会被当成掉线，
     * App 就会每隔一轮自己把链路拆掉再重连一次。</p>
     */
    private static final long LINK_SILENCE_TIMEOUT_MS = 70000L;
    /**
     * 静默多久之后先主动探一次主机，再决定要不要判定掉线。
     *
     * <p>「安静」不等于「断了」：先问一次，问得到就说明链路好着呢。
     * 这样既不会误拆链路，也不会让真正掉线的设备一直挂着。</p>
     */
    private static final long LINK_SILENCE_PROBE_MS = 35000L;
    /** 探测发出之后多久还没有任何回包，才判定链路真的断了。 */
    private static final long LINK_PROBE_TIMEOUT_MS = 15000L;
    /** 链路及数据通路看门狗的检查间隔。 */
    private static final long LINK_WATCHDOG_INTERVAL_MS = 1000L;
    /** 正常每舱约0.5秒回读；当前舱查询后连续10秒无有效状态，不能再视为数据就绪。 */
    private static final long STATUS_REPLY_TIMEOUT_MS = 10000L;
    /** 即使队列受阻没发成新查询，20秒没有任何有效状态也必须报告数据停滞。 */
    private static final long STATUS_FRESHNESS_TIMEOUT_MS = 20000L;
    /** GATT写成功不代表主机执行；控制回包超过该时间仍缺失就明确提示未确认。 */
    private static final long CONTROL_REPLY_TIMEOUT_MS = 5000L;
    private static final long MTU_READY_TIMEOUT_MS = 1200L;
    private static final long NOTIFY_READY_TIMEOUT_MS = 2200L;
    private static final int MAX_SERVICE_DISCOVERY_ATTEMPTS = 3;
    private static final int MAX_NOTIFY_ENABLE_ATTEMPTS = 2;
    private static final int MAX_PRESET_MINUTES = 1440;

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

    /**
     * 单舱的环境实测值。主机用同一条蓝牙链路管理左右两个舱，
     * 实测值必须按舱保存，否则切舱时会把另一舱的温湿度/CO2 显示到当前舱。
     */
    private static final class ZoneEnvironmentState {
        Float temperature;
        Float oxygen;
        Float humidity;
        Integer co2;
        Float infraredTemp;
        long updatedAtMs;
        /** 以下只用于区分“未查询、未回包、回包无有效环境值”，不参与伪造读数时间。 */
        long statusQuerySentAtMs;
        long statusReplyAtMs;
        /** 第一条至今未得到任何有效状态回答的查询发出时刻；后续轮询不会把它向后刷新。 */
        long awaitingStatusReplySinceAtMs;
        /** 最近一帧含真实环境值、开关或运行状态的回包时刻。 */
        long validStateAtMs;
        String lastStatusFrame = "";
    }

    /**
     * 单舱的控制状态（开关、目标值、计时）。切舱时整体保存/恢复，
     * 保证左舱和右舱的开关状态互不串舱。
     */
    private static final class HostZoneState {
        final String zone;
        boolean initialized;
        String statusLightColor = "";
        Integer treatmentMinutes;
        long runTimeBaseAt;
        Boolean tempEnabled;
        Boolean o2Enabled;
        Boolean coldLight;
        Boolean warmLight;
        Boolean redTherapy;
        Boolean blueTherapy;
        Boolean outerCycle;
        Boolean innerCycle;
        Boolean nebulizer;
        Boolean anion;
        Boolean uv;
        Boolean co2Enabled;
        Integer redTime;
        Integer blueTime;
        Integer nebulizerTime;
        Integer anionTime;
        Integer uvTime;

        HostZoneState(String zone) {
            this.zone = zone;
        }
    }
    /**
     * 一次刚下发的开关指令：记住下发的值、下发时刻和对应的指令名，
     * 用来判断主机回读到的还是不是旧值；无法确认时只回读并提示，不盲目重发。
     */
    private static final class PendingSwitch {
        final boolean value;
        /** 这个待确认记录对应的开关标识，回读时要用。 */
        final String key;
        long sentAtMs;
        final String cmd;
        Runnable confirmRunnable;
        /** 已经主动回读过几轮：仍对不上就以主机为准，结束这一轮下发。 */
        int verifyRounds;
        /**
         * 主机已经回执"收到这条指令"了。
         *
         * <p>主机的红光、蓝光、紫外等开关只回 {"cmd":"set_xxx","ret":"ok"}，
         * 这个 ok 只说明指令到了主机，不代表状态已经生效。
         * 记下这一条，就不会再重复下发同一条指令（重复下发只会堵住队列），
         * 但待确认记录要留着：只有读到主机报出来的真实状态才算确认。</p>
         *
         * <p>这一整份待确认状态只在主线程上读写：
         * 蓝牙回调线程只负责把收到的主机报文交过来，
         * 开关判断、状态更新、超时处理统一在主线程做。</p>
         */
        boolean acknowledged;
        /** 主机回执「收到这条指令」的时刻，用来算保护期。 */
        long acknowledgedAtMs;
        /**
         * 已经收到几帧完整有效的 get_all_status。
         *
         * <p>验证轮次按「收到的状态帧」算，不按「发出的查询」算：
         * 查询发出去只是问了一句，收到一帧完整状态才算真的问到了答案。
         * 最后一轮回包没回来之前，待确认的保护不能撤，
         * 否则主机后补的一帧旧快照会把用户刚点出来的开关弹回去。</p>
         */
        int statusFrames;
        /** 已经发出回读查询、还没等到回包。 */
        boolean readInFlight;
        /** 回读查询发出的时刻：用来判断这次查询是不是已经没人管了。 */
        long readRequestedAtMs;
        /** 主机最后一次报出来的、和用户点的方向不一样的值。 */
        Boolean lastHostValue;
        /** 矛盾状态到达时刻；保护期前的旧快照不能在保护期后冒充新证据。 */
        long lastHostValueAtMs;
        /**
         * 收到过几帧"主机报的还是旧值"的完整状态。
         *
         * <p>连续旧值只在保护期后的新鲜回读中作为对齐依据；不再把它当作重发依据。</p>
         */
        int contradictFrames;
        /**
         * 已经就"主机到底做没做"主动回读过几轮。
         *
         * <p>没收到主机回执时不能盲目重发：先用只读回读问清楚，
         * 问了几轮还是问不出来，就说明主机没响应，
         * 这时候该提示用户，而不是继续往主机上砸指令。</p>
         */
        int readbackRounds;

        PendingSwitch(boolean value, long sentAtMs, String cmd, String key) {
            this.value = value;
            this.sentAtMs = sentAtMs;
            this.cmd = cmd;
            this.key = key;
        }
    }

    /**
     * 主机确认过的开关值。
     *
     * <p>主机确认之后可能还会回一帧处理之前的旧状态快照，
     * 拿它覆盖刚确认的值，界面上的开关就会在开和关之间来回跳。
     * 记住确认值和确认时刻，这段时间内忽略矛盾的旧值。</p>
     */
    private static final class ConfirmedSwitch {
        final boolean value;
        final long atMs;
        /** 宽限期过后再读一次主机状态的回调。 */
        Runnable verifyRunnable;

        ConfirmedSwitch(boolean value, long atMs) {
            this.value = value;
            this.atMs = atMs;
        }
    }

    /**
     * 队列里的一条指令。
     *
     * <p>带命令名是为了去重：同名指令只保留最新的一条，
     * 用户连点同一个开关不会在队列里堆出一串互相覆盖的旧指令。</p>
     */
    private static final class QueuedCommand {
        final String payload;
        final String cmd;
        /** 去重键：命令名 + 舱位。 */
        final String key;
        final boolean control;
        final byte[] data;
        /** 两种写入方式都明确返回 false 的次数；成功启动或超时未确认都不计入。 */
        int writeStartFailures;

        QueuedCommand(String payload, String cmd, String key, boolean control, byte[] data) {
            this.payload = payload;
            this.cmd = cmd;
            this.key = key;
            this.control = control;
            this.data = data;
        }
    }

    /**
     * 已发出、还没收到回包的指令。
     *
     * <p>主机按收到的顺序回包，这里用先进先出和指令对齐：
     * 主机回 {"cmd":"error"} 时不带指令名，只有靠这条队列才知道
     * 到底是哪一条指令被拒绝了。</p>
     */
    private static final class AwaitingCommand {
        final String cmd;
        /** 舱位：左右舱的指令名一样，回包要连舱位一起对齐。 */
        final String zone;
        /** 发出时刻：太老的等待项多半已经对不上回包，不能拿来当归属。 */
        final long sentAtMs;
        /** 发出时的连接世代号：上一条链路的等待项不能拿来当归属。 */
        final long generation;
        /** set_care_mode 实际入队值；迟到回执不能拿最新 pending 值顶替。 */
        final String careModeValue;

        AwaitingCommand(String cmd, String zone, long sentAtMs, long generation,
                        String careModeValue) {
            this.cmd = cmd;
            this.zone = zone;
            this.sentAtMs = sentAtMs;
            this.generation = generation;
            this.careModeValue = careModeValue;
        }
    }

    private final Context appContext;
    private final BluetoothAdapter bluetoothAdapter;
    private final ArrayList<DeviceItem> devices = new ArrayList<>();
    /** 扫描结果在 binder 线程里改，界面在主线程读：不加锁会撞出 ConcurrentModificationException。 */
    private final Object devicesLock = new Object();
    /**
     * 通讯日志。
     *
     * <p>蓝牙回调线程和主线程都会往里写，所有读写都在 logs 上加锁，
     * 否则并发增删会把 ArrayList 的内部数组搞坏。</p>
     */
    private final ArrayList<String> logs = new ArrayList<>();
    /**
     * 待发送队列：用户控制指令和状态轮询共用一条队列。
     *
     * <p>控制指令插到轮询指令前面发；轮询指令按命令名去重。
     * 之前一轮状态轮询一次就是十来条，用户按下的开关只能排在后面干等，
     * 表现出来就是“点了没反应”。</p>
     */
    private final LinkedList<QueuedCommand> writeQueue = new LinkedList<>();
    /** 主机明确不支持的指令：再发也是白费，还会把发送队列堵住。 */
    private final HashSet<String> unsupportedCommands = new HashSet<>();
    /** 已发出、还没收到回包的指令：主机按收到的顺序回包，这里按先进先出对齐。 */
    private final LinkedList<AwaitingCommand> awaitingCommands = new LinkedList<>();
    /** C305 重组独立维护缓冲起点、损坏恢复、超时与溢出复位。 */
    private final HostNotifyReassembler notifyReassembler = new HostNotifyReassembler(
            MAX_NOTIFY_BUFFER_CHARS, HostNotifyReassembler.DEFAULT_FRAME_TIMEOUT_MS);
    /**
     * 连接世代号：每重建一次链路就自增。
     *
     * <p>旧链路的回包可能在新链路建好之后才到，那种帧必须丢掉，
     * 否则会把新链路刚发出去的指令的归属带偏。</p>
     */
    private long connectionGeneration;
    /** 主机最后一次报出来的开关状态：主机拒绝下发时用它把界面改回真实状态。 */
    private final HashMap<String, Boolean> hostSwitchStates = new HashMap<>();
    /** 正在发送的那一条：发出时就从队列里摘走，回执回来时不会摘错。 */
    private QueuedCommand currentWrite;
    /**
     * 还要忽略几条迟到的写回执。
     *
     * <p>写回执迟到时不能让它推进队列，否则下一条指令会被当成已发完而丢掉。
     * 之前用一段时间内都忽略的做法，会把后面正常指令的回执一起吞掉，
     * 一条超时就能拖出一连串秒级卡顿；改成只忽略一次就干净了。</p>
     */
    private int skippedWriteCallbacks;
    /**
     * 指令被主机拒绝的次数。
     *
     * <p>一次拒绝可能是归因错误：主机主动推的状态帧会把队列顺序打乱，
     * 那时这句 error 未必是在说队头那条指令。一次就拉黑的话，
     * 用户刚点下的开关会被误判成主机不支持，从此只能打开、关不掉。</p>
     */
    private final HashMap<String, Integer> commandErrorCounts = new HashMap<>();
    /**
     * 主机确认过的开关值和确认时刻。
     *
     * <p>主机确认之后可能还会回一帧旧状态，这段时间内不能拿旧状态
     * 把刚确认的值盖掉，否则界面上的开关会在开和关之间来回跳。</p>
     */
    private final HashMap<String, ConfirmedSwitch> confirmedSwitches = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;
    /** "记住设备 + 自动重连"控制器：App 重启或掉线后自动连回上次那台设备。 */
    private final BleAutoReconnectController autoReconnect;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    /** 当前链路：蓝牙回调线程要拿它和回调里的 gatt 比对，所以要 volatile。 */
    private volatile BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;
    /** 当前这次写入用的方式；两种写类型都等待 Android 的 onCharacteristicWrite。 */
    private int currentWriteType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
    private boolean scanning;
    /** 自动重连要找回来的设备地址。 */
    private String autoReconnectTargetAddress = "";
    /** 自动重连扫描是否在运行：设备重新开机后靠它被重新发现。 */
    private boolean autoReconnectScanning;
    private boolean connected;
    private boolean protocolReady;
    private boolean writing;
    private int serviceDiscoveryAttempts;
    private int notifyEnableAttempts;
    private String currentZone = DEFAULT_ZONE;
    /** 下一次自动总表查询轮到哪个舱；同名查询串行，正常时每舱约 0.5 秒一次。 */
    private String nextCabinPollZone = DEFAULT_ZONE;
    private String connectionStateText = "未连接";
    private String connectedDeviceName = "";
    private String connectedDeviceId = "";
    private String lastSent = "";
    private String lastReceived = "";
    private String lastError = "";
    /** 一次性操作错误（写入失败、主机拒绝、开关未确认）的留存副本。
     * 不被轮询入队或任意 Notify 帧清空；同一失败指令后来真正启动或新连接就绪时解除。 */
    private volatile String stickyError = "";
    private volatile long stickyErrorAtMs = 0L;
    /** 仅关联“本地未接受写入”的 stickyError，避免后续无关成功掩盖其他操作错误。 */
    private String stickyWriteFailurePayload = "";
    private String lastParsedStatus = "尚未收到响应";
    /** 原始 Notify 诊断：区分完全无回包、只有分片和已组成完整 JSON。 */
    private volatile long lastNotifyChunkAtMs = 0L;
    private volatile String lastNotifyChunk = "";
    private volatile long lastCompleteFrameAtMs = 0L;
    /** 数据通路健康与物理GATT连接分开记录：waiting / ok / timeout。 */
    private volatile String dataPathState = "waiting";
    private String dataPathError = "";
    /** 本轮数据通路诊断版本：用于确认真机运行的是本次传输/重组修复。 */
    public static final String DATA_DIAG_VERSION = "v4-diag-20261119-10";
    private static final int MAX_DIAG_FRAMES = 8;
    private static final int MAX_DIAG_FRAME_CHARS = 240;
    private volatile long diagNotifyChunkCount = 0L;
    private volatile long diagCompleteFrameCount = 0L;
    private volatile long diagParseFailureCount = 0L;
    private volatile long diagZoneRejectedCount = 0L;
    private volatile long diagEnvironmentAppliedCount = 0L;
    private volatile long diagValidHostStateCount = 0L;
    private volatile long diagDiscardedFrameCount = 0L;
    private volatile long diagNotifyResetCount = 0L;
    private volatile String diagLastDropReason = "";
    private volatile long diagLastDropAtMs = 0L;
    /** 最近一次用户控制的 GATT 写回执；与主机应用层 ack 分开记录。 */
    private volatile int lastControlWriteStatus = -1;
    private volatile long lastControlWriteStatusAtMs = 0L;
    private volatile String lastControlWriteStatusPayload = "";
    private volatile boolean lastControlWriteStatusMatched = false;
    private final ArrayList<String> diagRecentFrames = new ArrayList<>();
    /** 控制确认终态：pending 清除后仍保留，避免"没有消息"被读成执行成功。 */
    private final HashMap<String, String> controlFinalStates = new HashMap<>();
    private final HashMap<String, Long> controlFinalAtMs = new HashMap<>();

    private void noteDiagFrame(String frame) {
        if (TextUtils.isEmpty(frame)) {
            return;
        }
        String truncated = frame.length() > MAX_DIAG_FRAME_CHARS
                ? frame.substring(0, MAX_DIAG_FRAME_CHARS) : frame;
        synchronized (diagRecentFrames) {
            diagRecentFrames.add(truncated);
            while (diagRecentFrames.size() > MAX_DIAG_FRAMES) {
                diagRecentFrames.remove(0);
            }
        }
    }

    private void noteDiagDrop(String reason) {
        diagLastDropReason = reason == null ? "" : reason;
        diagLastDropAtMs = System.currentTimeMillis();
    }

    private void noteControlFinalState(String key, String state) {
        if (TextUtils.isEmpty(key) || TextUtils.isEmpty(state)) {
            return;
        }
        controlFinalStates.put(key, state);
        controlFinalAtMs.put(key, System.currentTimeMillis());
    }

    /** 一个控制命令对应一个确认键；数值型 setter 也纳入"已回包待回读"链路。 */
    private String controlKeyForCommand(String cmd) {
        if (TextUtils.isEmpty(cmd)) {
            return "";
        }
        String key = switchKeyForCommand(cmd);
        if (!TextUtils.isEmpty(key)) {
            return key;
        }
        if ("set_temp".equals(cmd)) {
            return "temp";
        }
        if ("set_o2".equals(cmd)) {
            return "oxygen";
        }
        if ("set_co2".equals(cmd)) {
            return "co2";
        }
        if ("set_run_time".equals(cmd)) {
            return "treatmentTime";
        }
        return "";
    }

    /** 两套 host 状态 JSON 共用同一份诊断快照，首页与蓝牙面板不会出现不同语义。 */
    public JSONObject buildDataDiagJson() throws JSONException {
        JSONObject diag = new JSONObject();
        diag.put("version", DATA_DIAG_VERSION);
        diag.put("notifyChunkCount", diagNotifyChunkCount);
        diag.put("completeFrameCount", diagCompleteFrameCount);
        diag.put("parseFailureCount", diagParseFailureCount);
        diag.put("zoneRejectedCount", diagZoneRejectedCount);
        diag.put("environmentAppliedCount", diagEnvironmentAppliedCount);
        diag.put("validHostStateCount", diagValidHostStateCount);
        diag.put("discardedFrameCount", diagDiscardedFrameCount);
        diag.put("notifyResetCount", diagNotifyResetCount);
        diag.put("notifyBufferChars", getNotifyBufferLength());
        diag.put("lastDropReason", diagLastDropReason);
        diag.put("lastDropAtMs", diagLastDropAtMs);
        diag.put("dataState", dataPathState);
        diag.put("dataError", dataPathError);
        diag.put("stickyError", stickyError);
        diag.put("stickyErrorAtMs", stickyErrorAtMs);
        diag.put("ready", isDataReady());
        diag.put("lastValidHostStateAtMs", lastValidHostStateAtMs);
        JSONArray frames = new JSONArray();
        synchronized (diagRecentFrames) {
            for (String frame : diagRecentFrames) {
                frames.put(frame);
            }
        }
        diag.put("recentFrames", frames);
        return diag;
    }

    /** GATT Status=0 与 setter ret:ok 都只表示传输/应用层回包，不等于主机已执行。 */
    public JSONObject buildControlConfirmationJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("version", DATA_DIAG_VERSION);
        root.put("request", lastControlRequest);
        root.put("requestedAtMs", lastControlRequestAtMs);
        root.put("sent", lastControlSent);
        root.put("writtenAtMs", lastControlSentAtMs);
        root.put("writeStatus", lastControlWriteStatus);
        root.put("writeStatusAtMs", lastControlWriteStatusAtMs);
        root.put("writeStatusPayload", lastControlWriteStatusPayload);
        root.put("writeStatusMatched", lastControlWriteStatusMatched);
        root.put("reply", lastControlReply);
        root.put("repliedAtMs", lastControlReplyAtMs);
        root.put("replyState", getControlReplyState());
        root.put("replyError", getControlReplyError());
        root.put("pendingCount", pendingSwitches.size());
        boolean replyTimedOut = "timeout".equals(getControlReplyState());
        JSONObject items = new JSONObject();
        for (String key : controlFinalStates.keySet()) {
            JSONObject item = new JSONObject();
            String state = controlFinalStates.get(key);
            if (replyTimedOut && ("pending".equals(state) || "acknowledged".equals(state))) {
                state = "unconfirmed";
            }
            item.put("state", state == null ? "unknown" : state);
            Long atMs = controlFinalAtMs.get(key);
            item.put("updatedAtMs", atMs == null ? 0L : atMs.longValue());
            Boolean hostValue = hostSwitchStates.get(key);
            item.put("hostValue", hostValue == null ? JSONObject.NULL : hostValue.booleanValue());
            PendingSwitch pending = pendingSwitches.get(pendingKeyOf(key));
            item.put("expected", pending == null ? JSONObject.NULL : pending.value);
            item.put("pending", pending != null);
            items.put(key, item);
        }
        root.put("items", items);
        return root;
    }
    private long dataPathWatchStartedAtMs = 0L;
    private long lastValidHostStateAtMs = 0L;
    /** 控制指令单独留痕，不能被随后的状态轮询立即覆盖。 */
    private volatile String lastControlRequest = "";
    private volatile long lastControlRequestAtMs = 0L;
    private volatile String lastControlSent = "";
    private volatile long lastControlSentAtMs = 0L;
    private String lastControlSentCmd = "";
    /** 与最后一次实际控制写入同命令名的主机回包；裸error无法可靠归因时不冒充。 */
    private String lastControlReply = "";
    private long lastControlReplyAtMs = 0L;
    private String lastControlReplyCmd = "";
    private long controlTimeoutLoggedForSentAtMs = 0L;
    private final ZoneEnvironmentState leftEnvironment = new ZoneEnvironmentState();
    private final ZoneEnvironmentState rightEnvironment = new ZoneEnvironmentState();
    /** 每个舱最近一次补发四项环境查询的时刻，只在主线程读写。 */
    private final HashMap<String, Long> environmentFallbackAtMs = new HashMap<>();
    /** 已入队、等待主机 set_care_mode 回执的模式值，按物理舱保存。 */
    private final HashMap<String, String> pendingCareModes = new HashMap<>();
    /** 只有主机明确确认过的模式才进入这里；本地保存或入队不算开启。 */
    private final HashMap<String, String> confirmedCareModes = new HashMap<>();
    private final HostZoneState leftHostState = new HostZoneState("left");
    private final HostZoneState rightHostState = new HostZoneState("right");
    private String statusLightColor = "";
    private String mainVersion = "";
    private String ctrlVersion = "";
    private String btVersion = "";
    private Integer treatmentMinutes;
    private Boolean tempEnabled;
    private Boolean o2Enabled;
    private Boolean coldLight;
    private Boolean warmLight;
    private Boolean redTherapy;
    private Boolean blueTherapy;
    private Boolean outerCycle;
    private Boolean innerCycle;
    private Boolean nebulizer;
    private Boolean anion;
    private Boolean uv;
    private Boolean co2Enabled;
    /**
     * 刚下发、还没被主机确认的开关值。
     *
     * <p>主机回读有延迟，下发之后立刻回读到的往往还是旧值，直接覆盖会让界面上的
     * 开关在开和关之间来回跳。这里先把刚下发的值挂起来，等主机回读确认（或超时）
     * 再把状态交回给主机。</p>
     */
    /**
     * 刚下发、还没被主机确认的开关值。
     *
     * <p>只允许主线程读写：蓝牙回调线程只负责把收到的主机报文交过来，
     * 开关判断、状态更新和超时处理全部在主线程上完成，
     * 不会再出现「回执已经收到，主线程却看不到待确认记录」的偶发问题。</p>
     */
    private final HashMap<String, PendingSwitch> pendingSwitches = new HashMap<>();
    /**
     * 主机回报的剩余分钟。
     *
     * <p>和用户配置的时长分开存，并按舱位和开关标识隔离：主机回报的是"还剩几分钟"，
     * 和"设置了多久"是两回事，混在一起每轮轮询都会把配置值改小。</p>
     */
    private final HashMap<String, Integer> switchRemainingMinutes = new HashMap<>();


    private Integer redTime;
    private Integer blueTime;
    private Integer nebulizerTime;
    private Integer anionTime;
    private Integer uvTime;
    private long deviceSessionStartedAt = 0L;
    private long runTimeBaseAt = 0L;
    /** 当前治疗计时只绑定到明确的病例、主机和舱位，避免重启或切换后串值。 */
    private String treatmentRecordNo = "";
    private String treatmentClockDeviceId = "";
    private String treatmentClockZone = "";
    private boolean treatmentSessionActive;
    /** 样本三项必填是否齐全；缺项样本不允许开始/续算治疗时长。 */
    private volatile boolean treatmentInfoComplete = true;
    private long treatmentClockRollbackLoggedForAnchor;
    /** 最近一次收到主机数据的时刻，静默看门狗用它判断链路是否还活着。 */
    /**
     * 最近一次收到主机数据的时刻，静默看门狗用它判断链路是否还活着。
     *
     * <p>蓝牙回调线程写、主线程读，所以要 volatile。</p>
     */
    private volatile long lastNotifyAtMs = 0L;
    /**
     * 链路探测的发出时刻：静默看门狗先探一次，探完还没回包才判定掉线。
     *
     * <p>收到任何一帧主机数据就清零，说明这次探测已经问到答案了。</p>
     */
    private volatile long linkProbeAtMs = 0L;

    private final Runnable autoUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (!connected || !protocolReady) {
                return;
            }
            refreshCabinStatus();
            handler.postDelayed(this, AUTO_UPDATE_MS);
        }
    };
    /**
     * 链路静默看门狗。
     *
     * 设备被突然关掉时，系统不一定回调 GATT 断开，页面上只表现为数值停住，
     * 很容易被当成设备卡死。这里用“多久没收到数据”来兜底判定链路真的断了，
     * 断了就把治疗时长定格，并交给自动重连去恢复。
     *
     * <p>但主机不会主动推数据，只有 App 去问才有回包，
     * 所以"多久没收到数据"不能直接当成掉线：轮询之间主机本来就是安静的。
     * 现在分两步走：先静默一段时间就主动探测一次，
     * 探测发出后还一直没有回包，才真的判定链路断了。</p>
     */
    private final Runnable linkWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (!connected || !protocolReady) {
                return;
            }
            long now = System.currentTimeMillis();
            // 任意 Notify 只能证明传输层有活动，不能掩盖有效状态或控制回包缺失。
            updateDataPathHealth(now);
            updateControlReplyTimeout();
            long silence = lastNotifyAtMs > 0L ? now - lastNotifyAtMs : 0L;
            if (silence > LINK_SILENCE_TIMEOUT_MS) {
                // 探过一轮也没等到任何回包：主机是真的没声了。
                handleLinkLost();
                return;
            }
            if (silence > LINK_SILENCE_PROBE_MS) {
                if (linkProbeAtMs == 0L) {
                    // 先问一次：安静不等于断了，问得到回包就说明链路好着呢。
                    linkProbeAtMs = now;
                    addLog("已 " + (silence / 1000) + " 秒没有收到主机数据，主动探测一次");
                    requestCabinStatus(true);
                } else if (now - linkProbeAtMs > LINK_PROBE_TIMEOUT_MS) {
                    addLog("探测超时，" + (LINK_PROBE_TIMEOUT_MS / 1000) + " 秒内没有任何回包");
                    handleLinkLost();
                    return;
                }
            }
            handler.postDelayed(this, LINK_WATCHDOG_INTERVAL_MS);
        }
    };
    private final Runnable scanTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!scanning) {
                return;
            }
            stopScan();
            addLog("扫描已自动停止");
            emit();
        }
    };
    private final Runnable statusRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (connected && protocolReady) {
                // 这是控制后的确认回读，必须查操作发生的当前舱；不能跟随自动轮询
                // 指针查到另一舱，否则主机已执行而当前界面仍等不到自己的真实状态。
                requestCabinStatusForZone(currentZone, true);
                requestEnvironmentFallbackIfNeeded(false);
            }
        }
    };
    private final Runnable serviceDiscoveryTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (protocolReady || connected || bluetoothGatt == null) {
                return;
            }
            if (serviceDiscoveryAttempts < MAX_SERVICE_DISCOVERY_ATTEMPTS) {
                addLog("发现服务超时，正在重试");
                startServiceDiscovery();
                return;
            }
            connectionStateText = "发现服务失败";
            setError("发现服务超时，请断开后重新连接，或确认设备支持 A002/C304/C305");
        }
    };
    private final Runnable mtuReadyTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!protocolReady && bluetoothGatt != null && writeCharacteristic != null && notifyCharacteristic != null) {
                addLog("MTU 回调未返回，继续启用 Notify");
                enableNotifyChannel();
            }
        }
    };
    private final Runnable protocolReadyFallbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (protocolReady || bluetoothGatt == null || writeCharacteristic == null || notifyCharacteristic == null) {
                return;
            }
            if (notifyEnableAttempts < MAX_NOTIFY_ENABLE_ATTEMPTS) {
                addLog("Notify descriptor 无回调，正在重试");
                enableNotifyChannel();
                return;
            }
            connectionStateText = "Notify失败";
            setError("Notify descriptor 写入无回调，请断开后重新连接");
        }
    };
    private final Runnable writeTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!writing) {
                return;
            }
            // 两种写类型都按同一套 GATT 回执状态机处理。一次超时不能证明平台永不回调，
            // 更不能据此永久跳过等待、连续启动写入；那会让后续 writeCharacteristic 返回 false。
            QueuedCommand command = currentWrite;
            addLog("写入回执超时(" + writeTypeName(currentWriteType) + "): "
                    + (command == null ? "" : command.cmd)
                    + "，主机可能已经收到，不物理重发，也不改变平台回执能力判定");
            // 给迟到的旧回执一个单独窗口；窗口内到达时 writing=false，不会推进下一条。
            skippedWriteCallbacks = 1;
            finishCurrentWriteAfterTimeout();
        }
    };

    public BleManager(Context context) {
        appContext = context.getApplicationContext();
        BluetoothManager bluetoothManager = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        String savedZone = appContext.getSharedPreferences(BLE_SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getString("current_zone", DEFAULT_ZONE);
        currentZone = normalizeZone(savedZone);
        if (TextUtils.isEmpty(currentZone)) {
            currentZone = DEFAULT_ZONE;
        }
        nextCabinPollZone = currentZone;
        autoReconnect = new BleAutoReconnectController(appContext, "host",
                new BleAutoReconnectController.Connector() {
                    @Override
                    public void connectDevice(String address, boolean automatic) {
                        doConnect(address, automatic);
                    }
                }, handler, new BleAutoReconnectController.StateListener() {
                    @Override
                    public void onAutoReconnectStateChanged(boolean waiting, int attempt) {
                        if (waiting && !connected) {
                            connectionStateText = attempt > 0 ? ("自动重连中(" + attempt + ")") : "自动重连中";
                            addLog("掉线后自动重连，第 " + attempt + " 次");
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
        synchronized (devicesLock) {
            // EM / AM 开头的本机设备排到列表前面；两组内部仍按发现顺序，
            // 不按 RSSI 重排，避免信号抖动时列表来回跳。
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

    /** 开关显示与手动取消共用：连接中的任何阶段都允许用户直接停止。 */
    public boolean isLinkActive() {
        return connected || scanning || autoReconnectScanning || bluetoothGatt != null
                || autoReconnect.isActive();
    }

    /** 页面可操作的“就绪”：至少收到过有效主机状态，且当前没有数据回包超时。 */
    public boolean isDataReady() {
        return connected && protocolReady && lastValidHostStateAtMs > 0L
                && !"timeout".equals(dataPathState);
    }

    public String getDataPathState() {
        return dataPathState;
    }

    public String getDataPathError() {
        return dataPathError;
    }

    public long getLastValidHostStateAtMs() {
        return lastValidHostStateAtMs;
    }

    public String getConnectionStateText() {
        return connectionStateText;
    }

    public String getConnectedDeviceName() {
        return connectedDeviceName;
    }

    public String getConnectedDeviceId() {
        return connectedDeviceId;
    }

    public String getLastSent() {
        return lastSent;
    }

    public String getLastReceived() {
        return lastReceived;
    }

    public String getLastError() {
        if (!TextUtils.isEmpty(lastError)) {
            return lastError;
        }
        if (!TextUtils.isEmpty(stickyError)) {
            return stickyError;
        }
        if (!TextUtils.isEmpty(dataPathError)) {
            return dataPathError;
        }
        return getControlReplyError();
    }

    public long getStickyErrorAtMs() {
        return stickyErrorAtMs;
    }

    public String getLastParsedStatus() {
        return lastParsedStatus;
    }

    /** 最近一次真实 Notify 分片的时间；0 表示本次连接尚未收到任何分片。 */
    public long getLastNotifyChunkAtMs() {
        return lastNotifyChunkAtMs;
    }

    /** 最近一片原始 Notify 文本，保留半截内容供定位分片/编码问题。 */
    public String getLastNotifyChunk() {
        return lastNotifyChunk;
    }

    /** 最近一次拼出完整 JSON 的时间；不能用它冒充环境值更新时间。 */
    public long getLastCompleteFrameAtMs() {
        return lastCompleteFrameAtMs;
    }

    public int getNotifyBufferLength() {
        return notifyReassembler.getBufferLength();
    }

    public String getLastControlRequest() {
        return lastControlRequest;
    }

    public long getLastControlRequestAtMs() {
        return lastControlRequestAtMs;
    }

    public String getLastControlSent() {
        return lastControlSent;
    }

    public long getLastControlSentAtMs() {
        return lastControlSentAtMs;
    }

    public String getLastControlReply() {
        return lastControlReply;
    }

    public long getLastControlReplyAtMs() {
        return lastControlReplyAtMs;
    }

    /** none / waiting / received / timeout；received 只表示有匹配回包，不代表物理效果已观察。 */
    public String getControlReplyState() {
        if (lastControlSentAtMs <= 0L || TextUtils.isEmpty(lastControlSentCmd)) {
            return "none";
        }
        if (lastControlReplyAtMs >= lastControlSentAtMs
                && !TextUtils.isEmpty(lastControlReplyCmd)
                && lastControlReplyCmd.equals(lastControlSentCmd)) {
            return "received";
        }
        return System.currentTimeMillis() - lastControlSentAtMs >= CONTROL_REPLY_TIMEOUT_MS
                ? "timeout" : "waiting";
    }

    public String getControlReplyError() {
        if (!"timeout".equals(getControlReplyState())) {
            return "";
        }
        String cmd = TextUtils.isEmpty(lastControlSentCmd) ? "控制指令" : lastControlSentCmd;
        return cmd + " 已发送，但超过 " + (CONTROL_REPLY_TIMEOUT_MS / 1000)
                + " 秒未收到匹配的主机回包，执行结果未确认；未自动重发";
    }

    public String getCurrentWritePayload() {
        return currentWrite == null ? "" : currentWrite.payload;
    }

    public int getWriteQueueSize() {
        return writeQueue.size() + (currentWrite == null ? 0 : 1);
    }

    public String getCabinTempText() {
        return getCabinTempText(currentZone);
    }

    public String getCabinTempText(String zone) {
        return formatValue(environmentForZone(zone).temperature, "℃");
    }

    public String getOxygenText() {
        return getOxygenText(currentZone);
    }

    public String getOxygenText(String zone) {
        return formatValue(environmentForZone(zone).oxygen, "%");
    }

    public String getHumidityText() {
        return getHumidityText(currentZone);
    }

    public String getHumidityText(String zone) {
        return formatValue(environmentForZone(zone).humidity, "%");
    }

    public Float getHumidityValue() {
        return environmentForZone(currentZone).humidity;
    }

    public String getCo2Text() {
        return getCo2Text(currentZone);
    }

    public String getCo2Text(String zone) {
        Integer value = environmentForZone(zone).co2;
        return value == null ? "--" : value + "PPM";
    }

    public Integer getCo2Value() {
        return environmentForZone(currentZone).co2;
    }

    public String getInfraredTempText() {
        return getInfraredTempText(currentZone);
    }

    public String getInfraredTempText(String zone) {
        return formatValue(environmentForZone(zone).infraredTemp, "℃");
    }

    /** 该舱环境数据的最后更新时间（毫秒时间戳），0 表示还没收到过该舱的数据。 */
    public long getEnvironmentUpdatedAt(String zone) {
        return environmentForZone(zone).updatedAtMs;
    }

    /** 该舱最后一次总表查询实际启动 GATT 写入的时刻；入队不算。 */
    public long getZoneStatusQuerySentAt(String zone) {
        return environmentForZone(zone).statusQuerySentAtMs;
    }

    /** 该舱最后一次已归属总表/环境状态帧的到达时刻，不要求帧里有有效环境值。 */
    public long getZoneStatusReplyAt(String zone) {
        return environmentForZone(zone).statusReplyAtMs;
    }

    /** 该舱最近一次含有效环境、开关或运行状态的回包时刻。 */
    public long getZoneValidStateAt(String zone) {
        return environmentForZone(zone).validStateAtMs;
    }

    /** 该舱最后一帧已归属状态原文，供核对 updatedAt 不前进时主机究竟回了什么。 */
    public String getZoneLastStatusFrame(String zone) {
        return environmentForZone(zone).lastStatusFrame;
    }

    public String getTreatmentTimeText() {
        // 样本信息没录完，或还没连上主机蓝牙协议：一律不显示治疗时长。
        if (!treatmentInfoComplete) {
            return "--";
        }
        if (treatmentMinutes == null && deviceSessionStartedAt <= 0L) {
            return "--";
        }
        return formatClockMinutes(getLiveTreatmentMinutes());
    }

    public Integer getTreatmentMinutesValue() {
        return treatmentInfoComplete ? getLiveTreatmentMinutes() : 0;
    }

    /**
     * 治疗时长只有在「病例三项必填齐全」且「主机蓝牙已连接、协议已就绪」时才计时。
     * 新建的空白样本、还没连上的设备，都不能把墙钟算成治疗时长。
     */
    private boolean treatmentClockAllowed() {
        return treatmentSessionActive && treatmentInfoComplete
                && connected && protocolReady
                && !TextUtils.isEmpty(treatmentRecordNo);
    }

    /** 允许计时但锚点被断链/缺项冻结时，从当前时刻续接；否则保持 "--" 或冻结值。 */
    private void resumeTreatmentClockIfAllowed() {
        if (!treatmentClockAllowed() || runTimeBaseAt > 0L || deviceSessionStartedAt > 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        if (treatmentMinutes != null) {
            runTimeBaseAt = now;
        } else {
            deviceSessionStartedAt = now;
        }
        syncCurrentZoneTreatmentClock();
        persistTreatmentClock();
        addLog("主机蓝牙已就绪且样本信息完整，治疗时长开始计时");
    }

    private int getLiveTreatmentMinutes() {
        long now = System.currentTimeMillis();
        if (treatmentMinutes != null) {
            long elapsed = elapsedTreatmentMinutesSince(runTimeBaseAt, now);
            return (int) Math.max(0L, (long) treatmentMinutes + elapsed);
        }
        if (deviceSessionStartedAt > 0L) {
            return (int) Math.max(0L, elapsedTreatmentMinutesSince(deviceSessionStartedAt, now));
        }
        return 0;
    }

    private long elapsedTreatmentMinutesSince(long anchor, long now) {
        if (anchor <= 0L) {
            return 0L;
        }
        if (now < anchor) {
            if (treatmentClockRollbackLoggedForAnchor != anchor) {
                treatmentClockRollbackLoggedForAnchor = anchor;
                addLog("治疗计时检测到时钟回拨，已按 0 分钟处理负增量");
            }
            return 0L;
        }
        return (now - anchor) / 60000L;
    }

    private String currentTreatmentDeviceId() {
        if (!TextUtils.isEmpty(connectedDeviceId)) {
            return connectedDeviceId;
        }
        return autoReconnect != null ? autoReconnect.getSavedAddress() : "";
    }

    private String treatmentClockKey(String deviceId, String zone) {
        return TREATMENT_CLOCK_PREF_PREFIX + deviceId + "|" + normalizeZone(zone);
    }

    /**
     * 病例恢复后绑定治疗计时。相同病例按原墙钟锚点续算；新病例、新主机或新舱位
     * 只读取自己的快照，绝不把上一份治疗带过来。
     */
    public void bindTreatmentSession(String recordNo, boolean currentTreatment) {
        bindTreatmentSession(recordNo, currentTreatment, treatmentInfoComplete);
    }

    /**
     * 病例恢复后绑定治疗计时。相同病例按原墙钟锚点续算；新病例、新主机或新舱位
     * 只读取自己的快照，绝不把上一份治疗带过来。
     *
     * 计时资格由 treatmentClockAllowed() 统一把关：只有主机蓝牙连上、协议就绪、
     * 且样本三项必填齐全，锚点才会落在"现在"。没连上或空白样本一律保持 "--"。
     */
    public void bindTreatmentSession(String recordNo, boolean currentTreatment, boolean infoComplete) {
        String normalizedRecord = recordNo == null ? "" : recordNo.trim();
        boolean wasComplete = treatmentInfoComplete;
        treatmentInfoComplete = infoComplete;
        if (wasComplete && !infoComplete) {
            // 样本变成空白档案时先把上一段计时定格，不能继续按墙钟往上走。
            freezeTreatmentClock();
        }
        if (!currentTreatment || TextUtils.isEmpty(normalizedRecord)) {
            if (treatmentSessionActive
                    && (TextUtils.isEmpty(normalizedRecord) || normalizedRecord.equals(treatmentRecordNo))) {
                finishTreatmentSession(treatmentRecordNo);
            }
            return;
        }
        String deviceId = currentTreatmentDeviceId();
        boolean sameIdentity = normalizedRecord.equals(treatmentRecordNo)
                && deviceId.equals(treatmentClockDeviceId)
                && currentZone.equals(treatmentClockZone);
        if (sameIdentity) {
            treatmentSessionActive = true;
            resumeTreatmentClockIfAllowed();
            return;
        }
        // 已经开始了本次病例的本地计时，第一次拿到主机地址时只补绑身份，不重新起算。
        if (TextUtils.isEmpty(treatmentClockDeviceId) && !TextUtils.isEmpty(deviceId)
                && normalizedRecord.equals(treatmentRecordNo) && treatmentSessionActive
                // 本地还没恢复出任何计时时才补绑身份；否则必须走下面的读档分支，
                // 不能让进程重启后拿到主机地址时把已累计时长留成 00:00。
                && (treatmentMinutes != null || deviceSessionStartedAt > 0L)) {
            treatmentClockDeviceId = deviceId;
            treatmentClockZone = currentZone;
            persistTreatmentClock();
            resumeTreatmentClockIfAllowed();
            return;
        }

        treatmentRecordNo = normalizedRecord;
        treatmentClockDeviceId = deviceId;
        treatmentClockZone = currentZone;
        treatmentSessionActive = true;
        treatmentClockRollbackLoggedForAnchor = 0L;
        JSONObject saved = readTreatmentClock(deviceId, currentZone, normalizedRecord);
        boolean restored = saved != null && saved.optBoolean("active", false)
                && normalizedRecord.equals(saved.optString("recordNo", ""));
        long now = System.currentTimeMillis();
        if (restored) {
            treatmentMinutes = saved.has("baseMinutes") && !saved.isNull("baseMinutes")
                    ? saved.optInt("baseMinutes") : null;
            runTimeBaseAt = saved.optLong("runTimeBaseAt", 0L);
            deviceSessionStartedAt = saved.optLong("deviceSessionStartedAt", 0L);
            if (runTimeBaseAt <= 0L && deviceSessionStartedAt <= 0L) {
                long fallbackAnchor = saved.optLong("updatedAt", now);
                if (treatmentMinutes != null) {
                    runTimeBaseAt = fallbackAnchor;
                } else {
                    deviceSessionStartedAt = fallbackAnchor;
                }
            }
            if (!treatmentClockAllowed()) {
                // 未连主机时存档只能给出"断开那一刻"的时长，不能按墙钟继续涨。
                long anchor = runTimeBaseAt > 0L ? runTimeBaseAt : deviceSessionStartedAt;
                long savedAt = saved.optLong("updatedAt", now);
                int frozen = treatmentMinutes == null ? 0 : treatmentMinutes;
                if (anchor > 0L && savedAt > anchor) {
                    frozen += (int) Math.max(0L, (savedAt - anchor) / 60000L);
                }
                treatmentMinutes = frozen;
                runTimeBaseAt = 0L;
                deviceSessionStartedAt = 0L;
                addLog("主机蓝牙未连接，恢复的治疗时长先定格");
            } else {
                addLog("已恢复病例 " + normalizedRecord + " 的治疗计时，按原墙钟锚点继续");
            }
        } else {
            // 新病例不立即起算：锚点全清零，等协议就绪且信息完整时再落到"现在"。
            treatmentMinutes = null;
            runTimeBaseAt = 0L;
            deviceSessionStartedAt = 0L;
            addLog("已绑定病例 " + normalizedRecord + "，等待主机蓝牙连接后开始治疗计时");
        }
        syncCurrentZoneTreatmentClock();
        resumeTreatmentClockIfAllowed();
        persistTreatmentClock();
    }

    /** 结束只停止当前病例的计时；断链冻结不会调用这里。 */
    public void finishTreatmentSession(String recordNo) {
        String normalizedRecord = recordNo == null ? "" : recordNo.trim();
        if (!TextUtils.isEmpty(normalizedRecord) && !normalizedRecord.equals(treatmentRecordNo)) {
            return;
        }
        if (!treatmentSessionActive && runTimeBaseAt <= 0L && deviceSessionStartedAt <= 0L) {
            return;
        }
        treatmentMinutes = getLiveTreatmentMinutes();
        runTimeBaseAt = 0L;
        deviceSessionStartedAt = 0L;
        treatmentSessionActive = false;
        syncCurrentZoneTreatmentClock();
        persistTreatmentClock();
        addLog("治疗计时已随病例结束定格，不再继续累计");
    }

    /**
     * 删除病例时彻底清除它在当前舱的治疗计时身份和存档。
     * 病例编号允许按“剩余最大值 + 1”重用末尾号，不能只靠 recordNo 判断新旧会话，
     * 否则新建同号病例可能接着上一病例的分钟数或墙钟锚点继续计算。
     */
    public void purgeTreatmentSession(String recordNo) {
        String normalizedRecord = recordNo == null ? "" : recordNo.trim();
        if (TextUtils.isEmpty(normalizedRecord)) {
            return;
        }
        if (normalizedRecord.equals(treatmentRecordNo)) {
            treatmentRecordNo = "";
            treatmentClockDeviceId = "";
            treatmentClockZone = "";
            treatmentMinutes = null;
            runTimeBaseAt = 0L;
            deviceSessionStartedAt = 0L;
            treatmentSessionActive = false;
            treatmentInfoComplete = false;
            treatmentClockRollbackLoggedForAnchor = 0L;
            syncCurrentZoneTreatmentClock();
        }
        SharedPreferences prefs = appContext.getSharedPreferences(BLE_SETTINGS_PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        ArrayList<String> keys = new ArrayList<>(prefs.getAll().keySet());
        for (String key : keys) {
            if (key == null || !key.startsWith(TREATMENT_CLOCK_PREF_PREFIX)
                    || !key.endsWith("|" + normalizeZone(currentZone))) {
                continue;
            }
            try {
                JSONObject saved = new JSONObject(prefs.getString(key, ""));
                if (normalizedRecord.equals(saved.optString("recordNo", ""))) {
                    editor.remove(key);
                    changed = true;
                }
            } catch (Exception ignored) {
            }
        }
        JSONObject last = parseTreatmentClock(prefs.getString(TREATMENT_CLOCK_LAST_KEY, ""));
        if (last != null && normalizedRecord.equals(last.optString("recordNo", ""))) {
            editor.remove(TREATMENT_CLOCK_LAST_KEY);
            changed = true;
        }
        if (changed) {
            editor.commit();
        }
        addLog("已清除删除病例 " + normalizedRecord + " 在当前舱的治疗计时残留");
    }

    private void syncCurrentZoneTreatmentClock() {
        HostZoneState state = currentHostState();
        state.treatmentMinutes = treatmentMinutes;
        state.runTimeBaseAt = runTimeBaseAt;
    }

    private JSONObject parseTreatmentClock(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        try {
            JSONObject saved = new JSONObject(raw);
            return saved.optInt("version", 0) == TREATMENT_CLOCK_VERSION ? saved : null;
        } catch (JSONException exception) {
            addLog("治疗计时存档无法解析，已按新会话处理");
            return null;
        }
    }

    /**
     * 恢复治疗计时：在「主机地址|舱位」存档和「最后一份活动快照」里挑
     * recordNo/舱位匹配、仍处活动状态且 updatedAt 最新的那一份。
     * 冷启动时 connectedDeviceId 还没拿到、或换了主机地址，只有后者能读到；
     * 少了这条兜底，正在治疗的样本会被当成新会话从 00:00 重新起算。
     */
    private JSONObject readTreatmentClock(String deviceId, String zone, String recordNo) {
        SharedPreferences prefs = appContext.getSharedPreferences(BLE_SETTINGS_PREFS, Context.MODE_PRIVATE);
        String normalizedZone = normalizeZone(zone);
        JSONObject best = null;
        long bestAt = -1L;
        if (!TextUtils.isEmpty(deviceId) && !TextUtils.isEmpty(normalizedZone)) {
            JSONObject byDevice = parseTreatmentClock(
                    prefs.getString(treatmentClockKey(deviceId, zone), ""));
            if (isRestorableTreatmentClock(byDevice, recordNo, normalizedZone)) {
                best = byDevice;
                bestAt = byDevice.optLong("updatedAt", 0L);
            }
        }
        JSONObject last = parseTreatmentClock(prefs.getString(TREATMENT_CLOCK_LAST_KEY, ""));
        if (isRestorableTreatmentClock(last, recordNo, normalizedZone)
                && last.optLong("updatedAt", 0L) > bestAt) {
            best = last;
        }
        if (best != null) {
            addLog("已恢复病例 " + recordNo + " 的治疗计时快照");
        }
        return best;
    }

    private boolean isRestorableTreatmentClock(JSONObject saved, String recordNo, String zone) {
        return saved != null && saved.optBoolean("active", false)
                && recordNo.equals(saved.optString("recordNo", ""))
                && zone.equals(normalizeZone(saved.optString("zone", "")));
    }

    /** 状态转换时保存；冻结值也要写进兜底快照，供进程被杀后冷启动恢复。 */
    private void persistTreatmentClock() {
        if (TextUtils.isEmpty(treatmentRecordNo) || TextUtils.isEmpty(treatmentClockZone)) {
            return;
        }
        // 活动会话但锚点被断链冻结时，不能再覆盖「主机地址|舱位」那份续算锚点。
        boolean hasActiveAnchor = !(treatmentSessionActive
                && runTimeBaseAt <= 0L && deviceSessionStartedAt <= 0L);
        try {
            JSONObject saved = new JSONObject();
            saved.put("version", TREATMENT_CLOCK_VERSION);
            saved.put("recordNo", treatmentRecordNo);
            saved.put("deviceId", treatmentClockDeviceId);
            saved.put("zone", treatmentClockZone);
            saved.put("baseMinutes", treatmentMinutes == null ? JSONObject.NULL : treatmentMinutes);
            saved.put("runTimeBaseAt", runTimeBaseAt);
            saved.put("deviceSessionStartedAt", deviceSessionStartedAt);
            saved.put("active", treatmentSessionActive);
            saved.put("updatedAt", System.currentTimeMillis());
            SharedPreferences.Editor editor = appContext
                    .getSharedPreferences(BLE_SETTINGS_PREFS, Context.MODE_PRIVATE).edit();
            if (hasActiveAnchor && !TextUtils.isEmpty(treatmentClockDeviceId)) {
                editor.putString(treatmentClockKey(treatmentClockDeviceId, treatmentClockZone),
                        saved.toString());
            }
            // 只要病例还在治疗且计时已经建立就留一份兜底：蓝牙断开被定格后，
            // 已累计的分钟数依然在盘上；从后台划掉 App 再冷启动也不会清零。
            // 还没起算的空白样本不写，否则冷启动会把 "--" 误显示成 "00:00"。
            boolean hasClock = treatmentMinutes != null || runTimeBaseAt > 0L
                    || deviceSessionStartedAt > 0L;
            if (treatmentSessionActive && hasClock) {
                editor.putString(TREATMENT_CLOCK_LAST_KEY, saved.toString());
            } else {
                editor.remove(TREATMENT_CLOCK_LAST_KEY);
            }
            editor.apply();
        } catch (JSONException exception) {
            addLog("治疗计时存档失败: " + exception.getClass().getSimpleName());
        }
    }

    /** 退到后台 / 被划掉前由 Activity 显式触发，保证时长锚点在切状态时已经排入写盘。 */
    public void persistTreatmentClockNow() {
        persistTreatmentClock();
    }

    /**
     * 蓝牙断开时把已经累计的治疗时长定格。
     *
     * 设备关机或链路断开后 App 收不到任何回包，如果还按墙钟继续累加，
     * 页面上的治疗时长会一直涨，看上去像设备还在治疗。这里记下断开瞬间的
     * 时长并停止累加，等重新连上、设备回读真实值之后再更新。
     */
    private void freezeTreatmentClock() {
        if (treatmentMinutes == null && deviceSessionStartedAt <= 0L) {
            // 本次连接本来就没有计时，保持 "--" 不变。
            return;
        }
        treatmentMinutes = getLiveTreatmentMinutes();
        runTimeBaseAt = 0L;
        deviceSessionStartedAt = 0L;
        // 舱位缓存里也要定格：断开后再切舱，恢复出来的仍然是断开那一刻的时长，
        // 不会因为换舱又接着往上走。
        HostZoneState state = currentHostState();
        state.treatmentMinutes = treatmentMinutes;
        state.runTimeBaseAt = 0L;
    }

    /**
     * 清空某个舱的环境实测值。
     *
     * <p>置空而不是置 0：页面上的读数显示 "--"，表示"当前没有数据"，
     * 和"实测值就是 0℃"是两回事。</p>
     */
    private static void clearEnvironment(ZoneEnvironmentState environment) {
        environment.temperature = null;
        environment.oxygen = null;
        environment.humidity = null;
        environment.co2 = null;
        environment.infraredTemp = null;
        environment.updatedAtMs = 0L;
        environment.statusQuerySentAtMs = 0L;
        environment.statusReplyAtMs = 0L;
        environment.awaitingStatusReplySinceAtMs = 0L;
        environment.validStateAtMs = 0L;
        environment.lastStatusFrame = "";
    }

    /**
     * 清空某个舱的开关与计时。
     *
     * <p>开关一律置成"关"（不是置空），页面显示的就是"关"；
     * 计时置空，页面显示 "--"。治疗时长不在这里清：它是记录性数据，
     * 由 {@link #freezeTreatmentClock()} 定格后保留。</p>
     */
    private static void clearHostSwitches(HostZoneState state) {
        state.statusLightColor = "";
        state.tempEnabled = Boolean.FALSE;
        state.o2Enabled = Boolean.FALSE;
        state.coldLight = Boolean.FALSE;
        state.warmLight = Boolean.FALSE;
        state.redTherapy = Boolean.FALSE;
        state.blueTherapy = Boolean.FALSE;
        state.outerCycle = Boolean.FALSE;
        state.innerCycle = Boolean.FALSE;
        state.nebulizer = Boolean.FALSE;
        state.anion = Boolean.FALSE;
        state.uv = Boolean.FALSE;
        state.co2Enabled = Boolean.FALSE;
        state.redTime = null;
        state.blueTime = null;
        state.nebulizerTime = null;
        state.anionTime = null;
        state.uvTime = null;
    }

    /**
     * 把某个舱缓存里的治疗时钟定格。
     *
     * <p>切舱时另一个舱的缓存里还留着旧的计时基准，断开之后再来回切舱，
     * 那个基准会让治疗时长又从旧时间接着往上走。这里把已经过去的分钟数
     * 补进缓存值，再把基准清零，切舱就只会读到定格值。</p>
     */
    private static void freezeZoneClock(HostZoneState state, long now) {
        if (state.runTimeBaseAt <= 0L) {
            state.runTimeBaseAt = 0L;
            return;
        }
        if (state.treatmentMinutes != null) {
            long elapsed = Math.max(0L, (now - state.runTimeBaseAt) / 60000L);
            state.treatmentMinutes = (int) Math.max(0L, (long) state.treatmentMinutes + elapsed);
        }
        state.runTimeBaseAt = 0L;
    }

    /**
     * 蓝牙断开之后清空所有"实时"数据。
     *
     * <p>设备关机或链路断开之后 App 收不到任何回包，页面继续显示上一次读到的
     * 温度、继续显示"开"着的开关，会让人误以为机器还在工作。这里统一清场：
     * 实测量置空（页面显示 "--"），开关置为"关"，监护等级清空，
     * 主机控制页与 ICU 状态页立刻反映"当前没有设备在管这只舱"。</p>
     *
     * <p>治疗时长是例外：{@link #freezeTreatmentClock()} 已经把它定格在断开
     * 那一瞬间，治疗记录和打印报告都要用它，清掉的话这一段治疗就变成 0 分钟。</p>
     */
    private void clearLiveHostData() {
        long now = System.currentTimeMillis();
        clearEnvironment(leftEnvironment);
        clearEnvironment(rightEnvironment);
        dataPathState = "waiting";
        dataPathError = "";
        dataPathWatchStartedAtMs = 0L;
        lastValidHostStateAtMs = 0L;
        lastControlSentCmd = "";
        lastControlReply = "";
        lastControlReplyAtMs = 0L;
        lastControlReplyCmd = "";
        controlTimeoutLoggedForSentAtMs = 0L;
        clearHostSwitches(leftHostState);
        clearHostSwitches(rightHostState);
        // 另一个舱的计时基准也要定格，否则断开后再切舱时长会接着往上走。
        freezeZoneClock(leftHostState, now);
        freezeZoneClock(rightHostState, now);

        statusLightColor = "";
        tempEnabled = Boolean.FALSE;
        o2Enabled = Boolean.FALSE;
        coldLight = Boolean.FALSE;
        warmLight = Boolean.FALSE;
        redTherapy = Boolean.FALSE;
        blueTherapy = Boolean.FALSE;
        outerCycle = Boolean.FALSE;
        innerCycle = Boolean.FALSE;
        nebulizer = Boolean.FALSE;
        anion = Boolean.FALSE;
        uv = Boolean.FALSE;
        co2Enabled = Boolean.FALSE;
        redTime = null;
        blueTime = null;
        nebulizerTime = null;
        anionTime = null;
        clearAllSwitchPending();
        switchRemainingMinutes.clear();
        uvTime = null;
        // treatmentMinutes 不动：它已经被 freezeTreatmentClock() 定格在断开那一瞬间，
        // 治疗记录与打印报告都依赖这个值，清成空会让这次治疗变成 0 分钟。
    }

    /**
     * 判定链路真的断了（设备被直接断电时系统常常不回调 GATT 断开）。
     *
     * 处理顺序很重要：先把治疗时长定格，再把 GATT 关掉让系统能干净地重连，
     * 最后交给自动重连控制器按退避策略恢复连接。
     */
    private void handleLinkLost() {
        addLog("超过 " + (LINK_SILENCE_TIMEOUT_MS / 1000) + " 秒未收到主机数据，判定链路断开");
        closeConnection();
        connectionStateText = "连接已丢失";
        lastError = "长时间未收到主机数据，链路已断开";
        emit();
        autoReconnect.onDisconnected();
    }

    private String formatClockMinutes(int totalMinutes) {
        int minutes = Math.max(0, totalMinutes);
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private void updateRunTimeMinutes(int minutes) {
        // 空白样本不能借主机回读的 run_time 偷偷开始显示治疗时长。
        if (minutes < 0 || !treatmentInfoComplete) {
            return;
        }
        // run_time 是主机自己的会话时钟：主机重启、回包缺字段时 optInt 默认 0，
        // 或主机从新会话开始报数，都会比 App 已累计的病例时长小。
        // 本地计时一旦建立就只能往前推，绝不能被设备回读值覆盖成 00:00 ——
        // 这正是「后台划掉 App 再重启，治疗时长被重置」的直接原因。
        boolean hasLocalClock = treatmentMinutes != null || deviceSessionStartedAt > 0L;
        if (!TreatmentClockMath.shouldAdoptRunTime(hasLocalClock,
                hasLocalClock ? getLiveTreatmentMinutes() : 0, minutes)) {
            return;
        }
        if (treatmentMinutes == null || treatmentMinutes != minutes) {
            treatmentMinutes = minutes;
            runTimeBaseAt = System.currentTimeMillis();
            deviceSessionStartedAt = 0L;
            syncCurrentZoneTreatmentClock();
            persistTreatmentClock();
        }
    }

    public String getStatusLightColor() {
        return statusLightColor;
    }

    /**
     * 主机回报的监护等级灯颜色换算成等级名（一级/二级/三级）。
     *
     * <p>页面上的监护等级直接用这个值：它来自主机回读，
     * 主机自己改了等级，App 也能跟着变。</p>
     */
    public String getMonitorLevelLabel() {
        if ("red".equals(statusLightColor)) {
            return "一级";
        }
        if ("yellow".equals(statusLightColor)) {
            return "二级";
        }
        if ("green".equals(statusLightColor)) {
            return "三级";
        }
        return "";
    }

    public Float getCabinTempValue() {
        return environmentForZone(currentZone).temperature;
    }

    public Float getOxygenValue() {
        return environmentForZone(currentZone).oxygen;
    }

    public Integer getControlTimeValue(int index) {
        switch (index) {
            case 6:
                return redTime;
            case 7:
                return blueTime;
            case 10:
                return nebulizerTime;
            case 11:
                return anionTime;
            case 12:
                return uvTime;
            default:
                return null;
        }
    }

    public String getControlValue(int index) {
        switch (index) {
            case 0:
                return getCabinTempText();
            case 1:
                return getOxygenText();
            case 2:
                return formatStatusLight();
            case 3:
                return getCo2Text();
            case 4:
                return formatSwitch(coldLight);
            case 5:
                return formatSwitch(warmLight);
            case 6:
                return formatTimedSwitch(redTherapy, remainingMinutes(SWITCH_RED, redTime));
            case 7:
                return formatTimedSwitch(blueTherapy, remainingMinutes(SWITCH_BLUE, blueTime));
            case 8:
                return formatSwitch(outerCycle);
            case 9:
                return formatSwitch(innerCycle);
            case 10:
                return formatTimedSwitch(nebulizer, remainingMinutes(SWITCH_NEBULIZER, nebulizerTime));
            case 11:
                return formatTimedSwitch(anion, remainingMinutes(SWITCH_ANION, anionTime));
            case 12:
                return formatTimedSwitch(uv, remainingMinutes(SWITCH_UV, uvTime));
            case 13:
                return formatSwitch(o2Enabled);
            case 14:
                return getHumidityText();
            case 15:
                return getTreatmentTimeText();
            default:
                return "-";
        }
    }

    public boolean isControlOn(int index) {
        switch (index) {
            case 3:
                return Boolean.TRUE.equals(co2Enabled);
            case 4:
                return Boolean.TRUE.equals(coldLight);
            case 5:
                return Boolean.TRUE.equals(warmLight);
            case 6:
                return Boolean.TRUE.equals(redTherapy);
            case 7:
                return Boolean.TRUE.equals(blueTherapy);
            case 8:
                return Boolean.TRUE.equals(outerCycle);
            case 9:
                return Boolean.TRUE.equals(innerCycle);
            case 10:
                return Boolean.TRUE.equals(nebulizer);
            case 11:
                return Boolean.TRUE.equals(anion);
            case 12:
                return Boolean.TRUE.equals(uv);
            case 13:
                return Boolean.TRUE.equals(o2Enabled);
            default:
                return false;
        }
    }

    /**
     * 舱内温度与氧浓度的开关没有占用控制项下标，这里单独给出查询口，
     * 治疗记录表的“是否打开”要用真实开关状态，不能只看数值。
     */
    public boolean isTempEnabled() {
        return Boolean.TRUE.equals(tempEnabled);
    }

    public boolean isOxygenEnabled() {
        return Boolean.TRUE.equals(o2Enabled);
    }

    // 带定时的控制项统一按 00:00 显示时长，和页面上的倒计时保持一致
    public String getControlDurationText(int index) {
        switch (index) {
            case 6:
                return formatTimedShort(redTherapy, remainingMinutes(SWITCH_RED, redTime));
            case 7:
                return formatTimedShort(blueTherapy, remainingMinutes(SWITCH_BLUE, blueTime));
            case 10:
                return formatTimedShort(nebulizer, remainingMinutes(SWITCH_NEBULIZER, nebulizerTime));
            case 11:
                return formatTimedShort(anion, remainingMinutes(SWITCH_ANION, anionTime));
            case 12:
                return formatTimedShort(uv, remainingMinutes(SWITCH_UV, uvTime));
            default:
                return getControlValue(index);
        }
    }

    public String getOtherTreatmentSummary() {
        return "红外理疗（" + formatTimedShort(redTherapy, remainingMinutes(SWITCH_RED, redTime)) + "）、雾化器（" + formatTimedShort(nebulizer, remainingMinutes(SWITCH_NEBULIZER, nebulizerTime)) + "）、蓝光理疗（" + formatTimedShort(blueTherapy, remainingMinutes(SWITCH_BLUE, blueTime)) + "）、";
    }

    public String getOtherTreatmentSummaryLine2() {
        return "暖光灯（" + formatSwitch(warmLight) + "）、内循环（" + formatSwitch(innerCycle) + "）";
    }

    public String getCurrentZone() {
        return currentZone;
    }

    /**
     * 切换当前控制的舱位。切舱时先把当前舱的开关/计时状态存进该舱缓存，
     * 再把目标舱的缓存写回显示字段，最后重新读取目标舱的状态，
     * 保证左右两舱的数据互不串舱。
     */
    public void setCurrentZone(String zone) {
        String normalized = normalizeZone(zone);
        if (TextUtils.isEmpty(normalized)) {
            return;
        }
        if (normalized.equals(currentZone)) {
            nextCabinPollZone = normalized;
            updateDataPathHealth(System.currentTimeMillis());
            requestCurrentZoneStatus();
            requestCareMode(normalized);
            return;
        }
        persistTreatmentClock();
        saveCurrentZoneProjection();
        currentZone = normalized;
        nextCabinPollZone = normalized;
        loadZoneProjection();
        appContext.getSharedPreferences(BLE_SETTINGS_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("current_zone", currentZone)
                .apply();
        updateDataPathHealth(System.currentTimeMillis());
        emit();
        requestCurrentZoneStatus();
        requestCareMode(normalized);
    }

    private static String normalizeZone(String zone) {
        if (zone == null) {
            return "";
        }
        String text = zone.trim().toLowerCase();
        if ("left".equals(text)) {
            return "left";
        }
        if ("right".equals(text)) {
            return "right";
        }
        return "";
    }

    private HostZoneState hostStateForZone(String zone) {
        return "left".equals(normalizeZone(zone)) ? leftHostState : rightHostState;
    }

    private HostZoneState currentHostState() {
        return hostStateForZone(currentZone);
    }

    /** 取某个舱的环境缓存；zone 为空或非法时回落到当前舱。 */
    private ZoneEnvironmentState environmentForZone(String zone) {
        String normalized = normalizeZone(zone);
        if (TextUtils.isEmpty(normalized)) {
            normalized = normalizeZone(currentZone);
        }
        return "left".equals(normalized) ? leftEnvironment : rightEnvironment;
    }

    /** 切舱前把当前舱的开关/计时状态存进该舱缓存。 */
    private void saveCurrentZoneProjection() {
        HostZoneState state = currentHostState();
        state.initialized = true;
        state.statusLightColor = statusLightColor;
        state.treatmentMinutes = treatmentMinutes;
        state.runTimeBaseAt = runTimeBaseAt;
        state.tempEnabled = tempEnabled;
        state.o2Enabled = o2Enabled;
        state.coldLight = coldLight;
        state.warmLight = warmLight;
        state.redTherapy = redTherapy;
        state.blueTherapy = blueTherapy;
        state.outerCycle = outerCycle;
        state.innerCycle = innerCycle;
        state.nebulizer = nebulizer;
        state.anion = anion;
        state.uv = uv;
        state.co2Enabled = co2Enabled;
        state.redTime = redTime;
        state.blueTime = blueTime;
        state.nebulizerTime = nebulizerTime;
        state.anionTime = anionTime;
        state.uvTime = uvTime;
    }

    /** 切舱后把目标舱缓存写回显示字段；该舱还没有任何回包时先清空，避免沿用另一舱的数据。 */
    private void loadZoneProjection() {
        HostZoneState state = currentHostState();
        if (!state.initialized) {
            statusLightColor = "";
            treatmentMinutes = null;
            runTimeBaseAt = 0L;
            tempEnabled = null;
            o2Enabled = null;
            coldLight = null;
            warmLight = null;
            redTherapy = null;
            blueTherapy = null;
            outerCycle = null;
            innerCycle = null;
            nebulizer = null;
            anion = null;
            uv = null;
            co2Enabled = null;
            redTime = null;
            blueTime = null;
            nebulizerTime = null;
            anionTime = null;
            uvTime = null;
            return;
        }
        statusLightColor = state.statusLightColor;
        treatmentMinutes = state.treatmentMinutes;
        runTimeBaseAt = state.runTimeBaseAt;
        tempEnabled = state.tempEnabled;
        o2Enabled = state.o2Enabled;
        coldLight = state.coldLight;
        warmLight = state.warmLight;
        redTherapy = state.redTherapy;
        blueTherapy = state.blueTherapy;
        outerCycle = state.outerCycle;
        innerCycle = state.innerCycle;
        nebulizer = state.nebulizer;
        anion = state.anion;
        uv = state.uv;
        co2Enabled = state.co2Enabled;
        redTime = state.redTime;
        blueTime = state.blueTime;
        nebulizerTime = state.nebulizerTime;
        anionTime = state.anionTime;
        uvTime = state.uvTime;
    }

    /**
     * 重新读取当前舱的环境与开关状态。
     *
     * <p>只发一条 get_all_status：实测这版主机（EM-150Vet-20）一次就带回
     * 温度、湿度、CO2、氧浓度、监护等级和全部开关状态。之前这里还额外发
     * get_o2 / get_humidity / get_co2 / get_status_light，四条重复指令全要
     * 排进发送队列，用户刚点下的开关得排在它们后面才轮得到自己发出去；
     * 切几次舱之后队列就积压成几十条，界面上的开关半天都不动。</p>
     */
    private void requestCurrentZoneStatus() {
        if (!connected || !protocolReady) {
            return;
        }
        requestCabinStatus(true);
        requestEnvironmentFallbackIfNeeded(false);
    }

    /**
     * 解析回包属于哪个舱：优先用回包里的 zone，
     * 固件对 get_temp/get_run_time 等命令不回 zone，此时归属当前舱。
     */
    private String resolveResponseZone(JSONObject parsed, JSONObject data) {
        String zone = parsed.optString("zone", "");
        if (TextUtils.isEmpty(zone) && data != null) {
            zone = data.optString("zone", "");
        }
        String normalized = normalizeZone(zone);
        return TextUtils.isEmpty(normalized) ? currentZone : normalized;
    }

    /**
     * 回包是不是当前舱的：固件不一定每条都带 zone，
     * 带了却对不上当前舱时不能采纳，否则左右舱的监护等级会串。
     */
    private boolean isResponseForCurrentZone(JSONObject parsed) {
        String zone = normalizeZone(parsed.optString("zone", ""));
        boolean accepted = TextUtils.isEmpty(zone) || zone.equals(currentZone);
        if (!accepted) {
            diagZoneRejectedCount++;
            noteDiagDrop("zone_mismatch:" + zone);
        }
        return accepted;
    }

    /** 主动回读当前舱的监护等级灯颜色。 */
    private void requestStatusLightColor() {
        if (!connected || !protocolReady) {
            return;
        }
        sendCommand("{\"cmd\":\"get_status_light\",\"zone\":\"" + currentZone + "\"}");
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
                addLog("Bluetooth is ready");
                lastError = "";
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
            // 表现和“设备不在附近”一模一样，这里先把话说在前面。
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
            connectionStateText = "扫描中";
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
                    setError("Scan failed: " + errorCode);
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
        addLog("扫描附近的 BLE 设备");
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
        if (scanning) {
            addLog("Stop BLE scan");
        }
        scanning = false;
        if (!connected) {
            connectionStateText = "未连接";
        }
        emit();
    }

    /**
     * 自动重连专用的扫描回调。
     *
     * <p>设备被关掉再打开之后，只有它重新广播才能被发现：
     * 扫到记住的那台就立刻用扫描结果发起连接，比反复"按地址盲连"可靠得多。
     * 盲连在设备不广播时必然失败，连着失败几次还会把蓝牙栈的连接资源弄脏，
     * 结果就是设备明明已经重新开机，App 却再也连不回去。</p>
     */
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
            setError("自动重连扫描失败: " + errorCode);
        }
    };

    /**
     * 自动重连：先扫描把这台设备找回来。
     *
     * <p>设备重新开机之后才会恢复广播，扫到它的 MAC 就能连上；
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
        connectionStateText = "自动重连中";
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
            setError("自动重连扫描失败: 蓝牙不可用");
            return;
        }
        try {
            bleScanner.startScan(null, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(), autoReconnectScanCallback);
            handler.removeCallbacks(autoReconnectScanKeepAliveRunnable);
            handler.postDelayed(autoReconnectScanKeepAliveRunnable, AUTO_RECONNECT_SCAN_KEEP_ALIVE_MS);
            addLog("自动重连：等待设备重新广播 " + autoReconnectTargetAddress);
            emit();
        } catch (Exception exception) {
            autoReconnectScanning = false;
            autoReconnect.onScanFailed();
            setError("自动重连扫描失败: " + exception.getMessage());
        }
    }

    /**
     * 扫描保活：定期重启一次扫描，
     * 避免某些机型把长时间不间断的扫描悄悄掐掉，导致设备回来之后再也扫不到。
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

    /** 扫描命中记住的设备：立刻用扫描结果发起连接。 */
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
     * 用户在设备列表里点了某个设备：记住它，并立即发起连接。
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
            // 自动重连改成"扫到再连"：设备重新开机之后才会广播，
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
            setError("Connect failed: " + exception.getMessage());
        } catch (Exception exception) {
            setError("Connect failed: " + exception.getMessage());
        }
    }

    /**
     * 用具体的设备对象发起连接。
     *
     * @param device     要连的设备；扫描命中时用扫描结果里的对象最可靠
     * @param name       设备名称，界面上要显示蓝牙名称而不是地址
     * @param automatic  true 表示这是掉线后的自动重连
     */
    @SuppressLint("MissingPermission")
    private void connectDevice(BluetoothDevice device, String name, boolean automatic) {
        if (device == null) {
            return;
        }
        persistTreatmentClock();
        closeConnection();
        connectedDeviceId = device.getAddress();
        connectedDeviceName = TextUtils.isEmpty(name) ? connectedDeviceId : name;
        if (treatmentSessionActive && !TextUtils.isEmpty(treatmentRecordNo)
                && !connectedDeviceId.equals(treatmentClockDeviceId)) {
            bindTreatmentSession(treatmentRecordNo, true);
        }
        connectionStateText = automatic ? "自动重连中" : "连接中";
        lastError = "";
        serviceDiscoveryAttempts = 0;
        notifyEnableAttempts = 0;
        addLog("C304=Write, C305=Notify");
        bluetoothGatt = device.connectGatt(appContext, false, gattCallback);
        addLog("Connect " + connectedDeviceName);
        emit();
    }

    /** 用户主动断开：本次运行不再自动重连。 */
    @SuppressLint("MissingPermission")
    public void disconnect() {
        // 手动扫描也要一起停掉：连接状态页点开关发起的是 startScan()，
        // 若只停自动重连扫描，scanning 仍为 true，isLinkActive() 会一直返回 true，
        // 表现就是"开关打开后再点关不掉"。监护侧 Am4100Manager 在 closeConnection()
        // 首行就 stopScan()，这里对齐同样的行为。
        stopScan();
        stopAutoReconnectScan();
        autoReconnect.onUserDisconnect();
        closeConnection();
    }

    /**
     * 清掉协议层的记账。
     *
     * <p>断开之后这些数据全都作废：等待回包的队列还留着上一条链路的指令，
     * 主机新链路上的第一句 {"cmd":"error"} 就会被算到旧指令头上，
     * 把一条好端端的指令拉黑，用户点下去自然没反应。
     * "主机不支持"的黑名单同样要清：上一次链路上的偶发失败
     * 不该让这一条链路永远发不出这条指令。</p>
     */
    private void resetProtocolState() {
        awaitingCommands.clear();
        nextCabinPollZone = currentZone;
        unsupportedCommands.clear();
        commandErrorCounts.clear();
        hostSwitchStates.clear();
        confirmedSwitches.clear();
        skippedWriteCallbacks = 0;
        pendingSwitches.clear();
        switchRemainingMinutes.clear();
        environmentFallbackAtMs.clear();
        pendingCareModes.clear();
        // 断链后不再展示上一次连接确认过的“正在运行”状态。
        confirmedCareModes.clear();
        // 分代跨重连单调递增，避免新链路复用旧数字后漏掉一次真实确认。
        // 新链路：世代号自增，上一条链路迟到的帧一律作废。
        connectionGeneration++;
        // 半截分片及其超时起点都不能带到新链路上去拼。
        notifyReassembler.reset();
    }

    /**
     * 关闭当前链路并复位状态。
     * 与 {@link #disconnect()} 的区别是这里不会停掉自动重连，
     * 用于重连前的清场以及 Activity 销毁时的收尾。
     */
    @SuppressLint("MissingPermission")
    private void closeConnection() {
        stopAutoUpdate();
        handler.removeCallbacks(linkWatchdogRunnable);
        freezeTreatmentClock();
        // 链路一断，主机控制页与 ICU 状态页的实时数据必须清空：
        // 设备已经关机/断链了，页面还显示着上一次的温度和"开"着的开关会误导操作者。
        clearLiveHostData();
        resetProtocolState();
        writeCharacteristic = null;
        notifyCharacteristic = null;
        connected = false;
        protocolReady = false;
        serviceDiscoveryAttempts = 0;
        notifyEnableAttempts = 0;
        writing = false;
        writeQueue.clear();
        currentWrite = null;
        connectionStateText = "未连接";
        handler.removeCallbacks(writeTimeoutRunnable);
        handler.removeCallbacks(mtuReadyTimeoutRunnable);
        handler.removeCallbacks(serviceDiscoveryTimeoutRunnable);
        handler.removeCallbacks(protocolReadyFallbackRunnable);
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
     * App 启动、蓝牙重新打开、页面回到前台时调用：
     * 之前连过这台设备、现在又没连上，就自动把它连回来。
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
        // 这里不能因为"还有 gatt"就跳过，否则设备重新开机之后就再也连不上。
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
        // 先保存仍在运行的墙钟锚点，再冻结本次 BLE 连接的实时显示。
        persistTreatmentClock();
        closeConnection();
    }

    public void clearLogs() {
        synchronized (logs) {
            logs.clear();
        }
        emit();
    }

    public void readAllStatus() {
        // 环境总表必须先于三条版本查询启动；版本信息不能挡住首帧温/氧/湿/CO2。
        requestCurrentZoneStatus();
        sendCommand("{\"cmd\":\"get_main_version\"}");
        sendCommand("{\"cmd\":\"get_ctrl_version\"}");
        sendCommand("{\"cmd\":\"get_bt_ver\"}");
        refreshCabinStatus();
        // 护理回读排在环境总表之后，不能重新阻塞四路传感器首帧。
        requestCareMode(currentZone);
        addLog("正在读取所有设备状态");
        emit();
    }

    /**
     * 平时刷新舱内状态：左右舱交替发送一条 get_all_status。
     *
     * <p>不再把左右舱同名查询同时压进等待队列。主机如果只回一个舱、
     * 又省略 zone，旧先进先出会把后到的左舱回包归给先查询的右舱。
     * 现在任意时刻最多保留一条总表查询，无 zone 回包才有唯一、可核验的
     * 归属。250 毫秒轮换一次，正常时每舱约 0.5 秒查询一次。</p>
     */
    private void refreshCabinStatus() {
        if (!connected || !protocolReady) {
            return;
        }
        // 队列里已经堆了指令就跳过这一轮轮询：
        // 用户的开关指令必须优先于状态查询发出去。
        if (isWriteQueueBusy()) {
            return;
        }
        String zone = normalizeZone(nextCabinPollZone);
        if (TextUtils.isEmpty(zone)) {
            zone = currentZone;
        }
        if (shouldDeferBackgroundStatus(zone, System.currentTimeMillis())) {
            // 背景舱刚刚没有回答：仍保留单条总表在途的归属安全，等待项过期后
            // 先恢复当前舱刷新，背景舱稍后再试，不能每隔一轮就阻塞当前舱 2.5 秒。
            zone = currentZone;
        }
        requestCabinStatusForZone(zone, false);
        requestEnvironmentFallbackIfNeeded(false);
    }

    /** 仅退避刚刚漏回的背景舱；当前舱即使等待超时也继续按原节奏查询。 */
    private boolean shouldDeferBackgroundStatus(String zone, long now) {
        String normalized = normalizeZone(zone);
        if (TextUtils.isEmpty(normalized) || normalized.equals(currentZone)) {
            return false;
        }
        ZoneEnvironmentState environment = environmentForZone(normalized);
        if (environment.awaitingStatusReplySinceAtMs <= 0L
                || environment.statusQuerySentAtMs <= 0L
                || environment.statusReplyAtMs >= environment.statusQuerySentAtMs) {
            return false;
        }
        return now - environment.statusQuerySentAtMs < BACKGROUND_STATUS_RETRY_MS;
    }

    /**
     * 总表缺项、总表不受支持或读数超时，才给当前舱补一轮旧协议分项查询。
     *
     * <p>只查当前舱且每 15 秒最多一轮；完整有效的总表不会触发这些额外指令。
     * get_temp 沿用原协议不带 zone，其余三项带舱位。0 是有效读数，不参与缺值判断。</p>
     */
    private void requestEnvironmentFallbackIfNeeded(boolean force) {
        if (!connected || !protocolReady) {
            return;
        }
        long now = System.currentTimeMillis();
        String zone = currentZone;
        ZoneEnvironmentState environment = environmentForZone(zone);
        // 初次 null 只是“尚未读取”，不能据此立刻再排 get_temp/o2/humidity/co2 四条。
        // 等总表回答确实缺字段、总表超时或明确不支持时才回退，首帧只被一条总表占据。
        if (!HostTransportPolicy.shouldRequestEnvironmentFallback(force,
                unsupportedCommands.contains("get_all_status"), environment.statusReplyAtMs > 0L,
                hasUnansweredStatusTimeout(environment, now))) {
            return;
        }
        boolean missing = environment.temperature == null || environment.oxygen == null
                || environment.humidity == null || environment.co2 == null;
        boolean stale = environment.updatedAtMs <= 0L
                || now - environment.updatedAtMs >= ENVIRONMENT_STALE_MS;
        if (!missing && !stale) {
            return;
        }
        Long lastFallback = environmentFallbackAtMs.get(zone);
        if (!force && lastFallback != null
                && now - lastFallback < ENVIRONMENT_FALLBACK_MIN_INTERVAL_MS) {
            return;
        }
        if (force && lastFallback != null && now - lastFallback < 1000L) {
            return;
        }
        environmentFallbackAtMs.put(zone, now);
        addLog("[环境] 当前舱总表缺值或读数超时，补发四项分项查询");
        sendCommand("{\"cmd\":\"get_temp\"}");
        sendCommand("{\"cmd\":\"get_o2\",\"zone\":\"" + zone + "\"}");
        sendCommand("{\"cmd\":\"get_humidity\",\"zone\":\"" + zone + "\"}");
        sendCommand("{\"cmd\":\"get_co2\",\"zone\":\"" + zone + "\"}");
    }

    public boolean sendControlPreset(int index) {
        if (!ensureReady()) {
            return false;
        }
        boolean sent;
        switch (index) {
            case 0:
                sent = sendCommand("{\"cmd\":\"get_temp\"}");
                break;
            case 1:
                sent = sendCommand("{\"cmd\":\"get_o2\",\"zone\":\"" + currentZone + "\"}");
                break;
            case 2:
                // 监护等级灯走统一入口：本地先回显，等主机回读确认。
                return setStatusLightColor(nextStatusLightColor());
            case 3:
                sent = sendCommand("{\"cmd\":\"get_co2\",\"zone\":\"" + currentZone + "\"}");
                break;
            case 4:
                sent = setControlEnabled(4, nextSwitchValue(coldLight));
                break;
            case 5:
                sent = setControlEnabled(5, nextSwitchValue(warmLight));
                break;
            case 6:
                sent = sendTimedControl(6, nextSwitchValue(redTherapy), redTime);
                break;
            case 7:
                sent = sendTimedControl(7, nextSwitchValue(blueTherapy), blueTime);
                break;
            case 8:
                sent = setControlEnabled(8, nextSwitchValue(outerCycle));
                break;
            case 9:
                sent = setControlEnabled(9, nextSwitchValue(innerCycle));
                break;
            case 10:
                sent = sendTimedControl(10, nextSwitchValue(nebulizer), nebulizerTime);
                break;
            case 11:
                sent = sendTimedControl(11, nextSwitchValue(anion), anionTime);
                break;
            case 12:
                sent = sendTimedControl(12, nextSwitchValue(uv), uvTime);
                break;
            case 13:
                sent = setControlEnabled(13, nextSwitchValue(o2Enabled));
                break;
            case 14:
                sent = sendCommand("{\"cmd\":\"get_humidity\",\"zone\":\"" + currentZone + "\"}");
                break;
            case 15:
                sent = sendCommand("{\"cmd\":\"get_run_time\"}");
                break;
            default:
                return false;
        }
        if (!sent) {
            return false;
        }
        scheduleStatusRefresh();
        emit();
        return true;
    }

    public boolean setTemperature(float value) {
        if (!ensureReady()) {
            return false;
        }
        if (!sendCommand("{\"cmd\":\"set_temp\",\"value\":" + formatCommandNumber(value) + "}")) {
            return false;
        }
        scheduleStatusRefresh();
        return true;
    }

    public boolean setOxygen(float value) {
        if (!ensureReady()) {
            return false;
        }
        // 主机要的氧浓度是"十分之一"：直接下发 25.0 会被主机判成越界
        // （ret:"o2_range_err"），必须放大 10 倍发过去。
        // 界面上读到的也是同一口径，这里保持一致。
        if (!sendCommand("{\"cmd\":\"set_o2\",\"value\":" + formatCommandNumber(value * 10f) + ",\"zone\":\"" + currentZone + "\"}")) {
            return false;
        }
        scheduleStatusRefresh();
        return true;
    }

    public void noteHumidityReadOnlyRequest() {
        addLog("湿度仅接收主机数值，已拒绝设置请求");
    }

    public boolean setCo2(int value) {
        if (!ensureReady()) {
            return false;
        }
        if (!sendCommand("{\"cmd\":\"set_co2\",\"value\":" + value + ",\"zone\":\"" + currentZone + "\"}")) {
            return false;
        }
        scheduleStatusRefresh();
        return true;
    }

    public boolean toggleCo2Enable() {
        if (!ensureReady()) {
            return false;
        }
        boolean next = nextSwitchValue(co2Enabled);
        if (!sendCommand(switchPayload("set_co2_enable", next))) {
            return false;
        }
        PendingSwitch pending = applyLocalSwitch(SWITCH_CO2, next);
        // 下发之后立刻回读一次：主机到底做没做只有回读才知道。
        // 走待确认记录里的回读，才能分得清这一轮回读到的
        // 是不是这次下发造成的结果。
        if (pending != null) {
            requestSwitchReadback(pending);
        } else {
            requestSwitchState(SWITCH_CO2, true);
        }
        scheduleStatusRefresh();
        emit();
        return true;
    }

    /**
     * 下发一个主机开关。
     *
     * <p>报文统一走 switchPayload；入队成功先在本地回显，
     * 主机回读确认之后以主机为准，回执不明时不自动重发。</p>
     */
    public boolean setControlEnabled(int index, boolean enabled) {
        if (!ensureReady()) {
            return false;
        }
        String cmd = controlCommandFor(index);
        if (TextUtils.isEmpty(cmd)) {
            return false;
        }
        if (!sendCommand(switchPayload(cmd, enabled))) {
            return false;
        }
        String key = switchKeyForCommand(cmd);
        if (quietBatch) {
            // 批量连续下发：只本地回显，不登记待确认、不回读、不重发。
            // 整批结束后由 endQuietBatch 统一刷新状态，整套指令紧挨着送到主机。
            setSwitchState(key, enabled);
            emit();
            return true;
        }
        PendingSwitch pending = applyLocalSwitch(key, enabled);
        // 下发之后立刻回读一次：主机到底做没做只有回读才知道。
        // 不回读的话 App 只能靠上一份状态猜，用户点一下就只会"打开"。
        // 走待确认记录里的回读，才能分得清这一轮回读到的
        // 是不是这次下发造成的结果。
        if (pending != null) {
            requestSwitchReadback(pending);
        } else {
            requestSwitchState(key, true);
        }
        scheduleStatusRefresh();
        emit();
        return true;
    }

    /** 控制项下标 → 主机指令名。 */
    private String controlCommandFor(int index) {
        switch (index) {
            case 3:
                return "set_co2_enable";
            case 4:
                return "set_cold_light";
            case 5:
                return "set_warm_light";
            case 6:
                return "set_red_enable";
            case 7:
                return "set_blue_enable";
            case 8:
                return "set_outer_cycle";
            case 9:
                return "set_inner_cycle";
            case 10:
                return "set_nebulizer_enable";
            case 11:
                return "set_anion_enable";
            case 12:
                return "set_uv_enable";
            case 13:
                return "set_o2_enable";
            default:
                return "";
        }
    }

    /**
     * 下发带定时的开关：先发时长，再发开关。
     *
     * <p>主机先收到"开启"再收到"时长"时，会按旧的时长运行，
     * App 这边的倒计时就和主机对不上了。</p>
     */
    private boolean sendTimedControl(int index, boolean enabled, Integer minutes) {
        if (enabled && minutes != null) {
            String timeCmd = timedTimeCommandFor(index);
            if (!TextUtils.isEmpty(timeCmd)) {
                if (!sendCommand("{\"cmd\":\"" + timeCmd + "\",\"zone\":\""
                        + currentZone + "\",\"value\":" + minutes + "}")) {
                    // 时长没排进去就不能先开启，否则主机会按旧时长运行。
                    return false;
                }
                clearTimedRemaining(index);
            }
        }
        return setControlEnabled(index, enabled);
    }

    /** 控制项下标 → 主机定时时长指令名。 */
    private String timedTimeCommandFor(int index) {
        switch (index) {
            case 6:
                return "set_red_time";
            case 7:
                return "set_blue_time";
            case 10:
                return "set_nebulizer_time";
            case 11:
                return "set_anion_time";
            case 12:
                return "set_uv_time";
            default:
                return "";
        }
    }

    /**
     * 开关指令的报文。
     *
     * <p>外循环协议上不分舱，不带 zone；其余开关都要带当前舱。</p>
     */
    private String switchPayload(String cmd, boolean enabled) {
        if ("set_outer_cycle".equals(cmd)) {
            return "{\"cmd\":\"" + cmd + "\",\"enable\":\"" + onOff(enabled) + "\"}";
        }
        return "{\"cmd\":\"" + cmd + "\",\"zone\":\"" + currentZone + "\",\"enable\":\"" + onOff(enabled) + "\"}";
    }

    public boolean setControlTime(int index, int minutes) {
        if (!ensureReady()) {
            return false;
        }
        if (minutes < 0) {
            minutes = 0;
        }
        boolean sent;
        switch (index) {
            case 6:
                sent = sendCommand("{\"cmd\":\"set_red_time\",\"zone\":\"" + currentZone + "\",\"value\":" + minutes + "}");
                if (sent) {
                    redTime = minutes;
                }
                break;
            case 7:
                sent = sendCommand("{\"cmd\":\"set_blue_time\",\"zone\":\"" + currentZone + "\",\"value\":" + minutes + "}");
                if (sent) {
                    blueTime = minutes;
                }
                break;
            case 10:
                sent = sendCommand("{\"cmd\":\"set_nebulizer_time\",\"zone\":\"" + currentZone + "\",\"value\":" + minutes + "}");
                if (sent) {
                    nebulizerTime = minutes;
                }
                break;
            case 11:
                sent = sendCommand("{\"cmd\":\"set_anion_time\",\"zone\":\"" + currentZone + "\",\"value\":" + minutes + "}");
                if (sent) {
                    anionTime = minutes;
                }
                break;
            case 12:
                sent = sendCommand("{\"cmd\":\"set_uv_time\",\"zone\":\"" + currentZone + "\",\"value\":" + minutes + "}");
                if (sent) {
                    uvTime = minutes;
                }
                break;
            case 15:
                sent = sendCommand("{\"cmd\":\"set_run_time\",\"value\":" + minutes + "}");
                break;
            default:
                return false;
        }
        if (!sent) {
            return false;
        }
        if (index != 15) {
            // 新时长已入队，旧剩余值不再代表本次设置；回读前显示新配置值。
            clearTimedRemaining(index);
        }
        scheduleStatusRefresh();
        emit();
        return true;
    }

    /**
     * 监护等级与主机监护等级灯一一对应：一级红灯、二级黄灯、三级绿灯。
     */
    public boolean setMonitorLevel(String level) {
        String color = monitorLevelColor(level);
        if (color == null) {
            setError("无法识别的监护等级: " + level);
            return false;
        }
        return setStatusLightColor(color);
    }

    /** 直接下发监护等级灯颜色，并同步本地状态，界面可以立即回显。 */
    public boolean setStatusLightColor(String color) {
        if (!ensureReady()) {
            return false;
        }
        String normalized = normalizeStatusLightColor(color);
        if (TextUtils.isEmpty(normalized)) {
            setError("无法识别的监护等级灯颜色: " + color);
            return false;
        }
        sendCommand("{\"cmd\":\"set_status_light\",\"zone\":\"" + currentZone + "\",\"color\":\"" + normalized + "\"}");
        // 先按本地选择回显，等主机回读之后再以主机的实际值为准。
        statusLightColor = normalized;
        // 控制后尽快回读一次主机状态，拿回主机真正生效的监护等级。
        scheduleStatusRefresh();
        emit();
        return true;
    }

    private String monitorLevelColor(String level) {
        if ("一级".equals(level)) {
            return "red";
        }
        if ("二级".equals(level)) {
            return "yellow";
        }
        if ("三级".equals(level)) {
            return "green";
        }
        return null;
    }

    /**
     * 监护等级灯颜色归一化。
     *
     * <p>固件回报的颜色写法不统一（red / Red / 红 / 一级 / 1），
     * 这里统一成 red / yellow / green；认不出来就返回空串，
     * 不能把认不出的脏值当成有效等级显示出去。</p>
     */
    private String normalizeStatusLightColor(String color) {
        if (color == null) {
            return "";
        }
        String text = color.trim().toLowerCase(Locale.US);
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        if ("red".equals(text) || "红".equals(text) || "r".equals(text)
                || "1".equals(text) || "一级".equals(text)) {
            return "red";
        }
        if ("yellow".equals(text) || "黄".equals(text) || "y".equals(text)
                || "2".equals(text) || "二级".equals(text)) {
            return "yellow";
        }
        if ("green".equals(text) || "绿".equals(text) || "g".equals(text)
                || "3".equals(text) || "三级".equals(text)) {
            return "green";
        }
        return "";
    }

    public boolean setCareMode(String value) {
        if (!ensureReady()) {
            return false;
        }
        if (TextUtils.isEmpty(value)) {
            value = "Custom_mode";
        }
        if (!isSupportedCareMode(value)) {
            return false;
        }
        final String requestedValue = value;
        String zone = currentZone;
        if (!sendCommand("{\"cmd\":\"set_care_mode\",\"zone\":\"" + zone
                + "\",\"value\":\"" + escapeJson(requestedValue) + "\"}")) {
            return false;
        }
        // 入队只记 pending，绝不能在这里把界面标成“已开启”。
        pendingCareModes.put(zone, requestedValue);
        scheduleStatusRefresh();
        // set 可能只有裸 ok；随后读取到的真实 care_mode 才能校正模式。
        requestCareMode(zone);
        return true;
    }

    private boolean requestCareMode(String zone) {
        String normalized = normalizeZone(zone);
        return sendCommand("{\"cmd\":\"get_care_mode\",\"zone\":\""
                + normalized + "\"}");
    }

    /** 主机已确认的模式；没有确认记录时返回空串，绝不默认成母幼模式。 */
    public String getConfirmedCareMode(String zone) {
        String normalized = normalizeZone(zone);
        String value = confirmedCareModes.get(normalized);
        return value == null ? "" : value;
    }

    /** 已入队但仍等待主机确认的模式，仅供界面显示“等待确认”。 */
    public String getPendingCareMode(String zone) {
        String value = pendingCareModes.get(normalizeZone(zone));
        return value == null ? "" : value;
    }

    private boolean isSupportedCareMode(String value) {
        return "maternal_and_infant_care".equals(value)
                || "Postoperative_care".equals(value)
                || "Cardiopulmonary_care".equals(value)
                || "Custom_mode".equals(value);
    }

    /** set_care_mode 成功回执到达后，才把回执实际对应的模式标记为已确认。 */
    private void confirmCareMode(String zone, String reportedValue, String commandValue) {
        String normalizedZone = normalizeZone(zone);
        if (TextUtils.isEmpty(normalizedZone)) {
            normalizedZone = currentZone;
        }
        String reported = isSupportedCareMode(reportedValue) ? reportedValue : "";
        String commanded = isSupportedCareMode(commandValue) ? commandValue : "";
        if (!TextUtils.isEmpty(reported) && !TextUtils.isEmpty(commanded)
                && !reported.equals(commanded)) {
            addLog("[护理模式] 回执值 " + reported + " 与实际下发值 " + commanded + " 不一致");
            setError("主机确认的护理模式与回执对应的下发值不一致，请核对主机");
            return;
        }
        String confirmed = !TextUtils.isEmpty(reported) ? reported : commanded;
        if (TextUtils.isEmpty(confirmed)) {
            // 不把“最新 pending”拿来猜迟到回执：连续切换 A/B 时会错把 A 的 ok 记成 B。
            addLog("[护理模式] 成功回执没有可对应的下发值，界面不标记开启");
            return;
        }
        String pending = pendingCareModes.get(normalizedZone);
        if (confirmed.equals(pending)) {
            pendingCareModes.remove(normalizedZone);
        }
        confirmedCareModes.put(normalizedZone, confirmed);
        addLog("[护理模式] 主机已确认 " + normalizedZone + " = " + confirmed);
        emit();
    }

    private void clearPendingCareMode(String zone, String expectedValue) {
        String normalizedZone = normalizeZone(zone);
        normalizedZone = TextUtils.isEmpty(normalizedZone) ? currentZone : normalizedZone;
        if (!TextUtils.isEmpty(expectedValue)
                && expectedValue.equals(pendingCareModes.get(normalizedZone))) {
            pendingCareModes.remove(normalizedZone);
        }
    }

    /** 下发一条指令；返回 false 表示这条指令根本没发出去。 */
    public boolean sendCommand(String payload) {
        return enqueueCommand(payload, isControlPayload(payload));
    }

    /**
     * 判断这条指令是用户操作还是状态查询。
     *
     * <p>协议里 set_ 开头的是控制指令，get_ 开头的是状态查询。</p>
     */
    private boolean isControlPayload(String payload) {
        if (payload == null) {
            return false;
        }
        int index = payload.indexOf("\"cmd\":\"");
        if (index < 0) {
            return false;
        }
        int start = index + 7;
        int end = payload.indexOf('"', start);
        if (end < 0) {
            return false;
        }
        String cmd = payload.substring(start, end);
        // 监护等级灯的命令名是 status_light，不以 set_ 开头，但同样是用户操作：
        // 当成状态查询会被丢到队尾，还会在去重的时候被丢掉。
        return isUserControlCommand(cmd);
    }

    private boolean isUserControlCommand(String cmd) {
        return !TextUtils.isEmpty(cmd) && (cmd.startsWith("set_") || "status_light".equals(cmd));
    }

    /**
     * 把一条指令排进发送队列。
     *
     * <p>控制指令插到状态轮询前面发；同名指令只保留最新的一条，
     * 连点同一个开关不会在队列里堆出一串互相覆盖的旧指令。</p>
     */
    private boolean enqueueCommand(String payload, boolean control) {
        String cmd = commandNameOf(payload);
        boolean userControl = isUserControlCommand(cmd);
        if (userControl) {
            // 请求和实际写入分开记录；lastSent 随后被轮询覆盖也不会丢失这次操作。
            lastControlRequest = payload;
            lastControlRequestAtMs = System.currentTimeMillis();
            // 新请求从入队起就是新的确认分代：尚未写入时不能展示上一次同名 ack。
            lastControlSent = "";
            lastControlSentAtMs = 0L;
            lastControlSentCmd = "";
            lastControlReply = "";
            lastControlReplyAtMs = 0L;
            lastControlReplyCmd = "";
            lastControlWriteStatus = -1;
            lastControlWriteStatusAtMs = 0L;
            lastControlWriteStatusPayload = "";
            lastControlWriteStatusMatched = false;
            controlTimeoutLoggedForSentAtMs = 0L;
            addLog("收到控制指令请求: " + payload);
        }
        if (missingBluetoothConnectPermission()) {
            setError("缺少蓝牙连接权限，请先申请权限");
            return false;
        }
        if (bluetoothGatt == null) {
            setError("请先连接蓝牙设备");
            return false;
        }
        if (!connected || writeCharacteristic == null) {
            setError("蓝牙已连接，协议通道初始化中，请稍后再试");
            return false;
        }
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        // 只有状态查询可以使用“不支持”缓存。裸 {"cmd":"error"} 不含指令名，
        // 归因可能错位，绝不能因此把用户之后主动下发的 set_temp 等控制永久拦截。
        if (!userControl && !TextUtils.isEmpty(cmd) && unsupportedCommands.contains(cmd)) {
            addLog("[主机] 不支持 " + cmd + "，已跳过");
            return false;
        }
        String key = dedupKeyOf(payload);
        QueuedCommand command = new QueuedCommand(payload, cmd, key, control, data);
        if (control) {
            // 同名控制指令只保留最新的一条：用户点了两次以最后一次为准。
            removeQueuedCommand(key);
            insertControlCommand(command);
        } else {
            // 状态查询同名去重：队列里已经有同一条查询就不再重复排队，
            // 免得一轮轮询就把队列塞满。
            if (hasQueuedCommand(key)) {
                return true;
            }
            writeQueue.addLast(command);
        }
        // 入队不等于已经发送。lastSent 和“发送”日志统一在 GATT 写入
        // 成功启动后记录，避免后排入队的另一舱查询覆盖诊断状态。
        // 只清即时错误；stickyError 保留写入失败/主机拒绝等一次性提示，
        // 否则自动轮询入队会把用户刚看到的错误在下一轮抹掉。
        lastError = "";
        processWriteQueue();
        emit();
        return true;
    }

    /**
     * 控制指令插队：排在已排队的控制指令之后、状态轮询之前。
     *
     * <p>控制指令之间保持先来后到，避免先入队的 set_xxx_time 被后入队的
     * set_xxx_enable 顶到后面，主机先收到“开启”再收到“时长”。</p>
     */
    private void insertControlCommand(QueuedCommand command) {
        List<QueuedCommand> head = new ArrayList<>();
        Iterator<QueuedCommand> iterator = writeQueue.iterator();
        while (iterator.hasNext()) {
            QueuedCommand queued = iterator.next();
            if (!queued.control) {
                break;
            }
            iterator.remove();
            head.add(queued);
        }
        head.add(command);
        for (int i = head.size() - 1; i >= 0; i--) {
            writeQueue.addFirst(head.get(i));
        }
    }

    /** 把队列里同一条指令删掉，只保留最新的一条。 */
    private void removeQueuedCommand(String key) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        Iterator<QueuedCommand> iterator = writeQueue.iterator();
        while (iterator.hasNext()) {
            if (key.equals(iterator.next().key)) {
                iterator.remove();
            }
        }
    }

    /** 队列里是不是已经有同一条指令了（含正在发的那条）。 */
    private boolean hasQueuedCommand(String key) {
        if (TextUtils.isEmpty(key)) {
            return false;
        }
    if (currentWrite != null && key.equals(currentWrite.key)) {
        // 同一条还在发，不必再排一条。
        return true;
    }
        for (QueuedCommand queued : writeQueue) {
    if (key.equals(queued.key)) {
                return true;
            }
        }
        return false;
    }

    /** 取指令名：队列去重要用。 */
    private String commandNameOf(String payload) {
        if (payload == null) {
            return "";
        }
        int index = payload.indexOf("\"cmd\":\"");
        if (index < 0) {
            return "";
        }
        int start = index + 7;
        int end = payload.indexOf('"', start);
        if (end < 0) {
            return "";
        }
        return payload.substring(start, end);
    }

    /**
     * 取报文里的舱位。
     *
     * <p>外循环的指令协议上不分舱，报文里没有 zone，这里返回空串。</p>
     */
    private String zoneOf(String payload) {
    if (payload == null) {
            return "";
    }
    int index = payload.indexOf("\"zone\":\"");
    if (index < 0) {
            return "";
    }
    int start = index + 8;
    int end = payload.indexOf('"', start);
    if (end < 0) {
            return "";
    }
            return payload.substring(start, end);
    }

    /**
     * 队列去重键：命令名 + 舱位。
     *
     * <p>左右舱的命令名完全一样，去重必须带上舱位，否则右舱的
     * get_all_status 会被当成左舱的重复指令丢掉，右舱状态永远不刷新。</p>
     */
    private String dedupKeyOf(String payload) {
    String cmd = commandNameOf(payload);
    if (TextUtils.isEmpty(cmd)) {
            return "";
    }
            return cmd + ":" + zoneOf(payload);
    }

    public void sendPayload(String payload) {
        sendCommand(payload);
    }

    @SuppressLint("MissingPermission")
    private void processWriteQueue() {
        if (writing || writeQueue.isEmpty() || bluetoothGatt == null || writeCharacteristic == null) {
            return;
        }
        // 指令发出时就从队列里摘走：回执回来时不用再猜该删哪一条，
        // 也不会把还没发出去的下一条误删掉。
        QueuedCommand command = writeQueue.pollFirst();
        if (command == null) {
            return;
        }
        currentWrite = command;
        writing = true;
        boolean submitted = false;
        try {
            int preferredType = getPreferredWriteType();
            boolean started = tryWrite(command.data, preferredType);
            if (started) {
                submitted = true;
                noteWriteStarted(preferredType);
            }
            if (!started) {
                int fallbackType = preferredType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
                addLog("首选写入方式启动失败，切换为 " + writeTypeName(fallbackType));
                started = tryWrite(command.data, fallbackType);
                if (started) {
                    submitted = true;
                    noteWriteStarted(fallbackType);
                }
            }
            if (!started) {
                command.writeStartFailures++;
                boolean retry = HostTransportPolicy.shouldRetryWriteStart(command.writeStartFailures);
                String failure = "TX failed: writeCharacteristic returned false; cmd=" + command.cmd
                        + ", attempt=" + command.writeStartFailures + "/"
                        + HostTransportPolicy.MAX_WRITE_START_FAILURES;
                if (isUserControlCommand(command.cmd)) {
                    // false 只证明本地协议栈没有启动写入，不能描述成“主机已拒绝”。
                    setError(failure + (retry ? "，将仅重试尚未启动的写入"
                            : "，本次未下发，已停止重试并继续状态回读"));
                    stickyWriteFailurePayload = command.payload;
                } else {
                    // 后台查询失败不能留下一个看似属于用户温度设置的永久错误。
                    lastError = failure;
                    addLog(failure);
                }
                if (retry) {
                    // 两种写入方式都明确返回 false，说明没有启动，可以安全重排。
                    requeueCurrentCommand();
                } else {
                    dropCurrentCommandAfterWriteFailure();
                }
                return;
            }
            trackAwaitingCommand(command.cmd, zoneOf(command.payload), command.payload);
        } catch (Exception exception) {
            // 异常不像 false 那样能证明写入未启动；可能已交给协议栈，绝不能重发。
            if (submitted) {
                // 写回执仍可能到达，不能让它误推进下一条指令。
                skippedWriteCallbacks = 1;
            }
            setError("写入状态不明: " + exception.getMessage() + "，未自动重发，请核对主机状态");
            addLog(submitted ? "写入启动后处理异常，按可能已发出处理"
                    : "写入启动异常，是否到达主机不明，按可能已发出处理");
            finishCurrentWrite(false);
        }
    }

    /** 指令没写出去时放回队首，有限次等待本地 GATT 从忙状态恢复。 */
    private void requeueCurrentCommand() {
        QueuedCommand command = currentWrite;
        currentWrite = null;
        writing = false;
        handler.removeCallbacks(writeTimeoutRunnable);
        if (command == null) {
            return;
        }
        writeQueue.addFirst(command);
        addLog("指令未发出，已重新排队: " + command.payload);
        // 稍后再试，别在 GATT 还忙的时候死循环。
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                processWriteQueue();
            }
        }, WRITE_RETRY_DELAY_MS);
    }

    /**
     * 本地多次明确拒绝启动后结束这一条，不能让队头无限重排、永久挡住 ack 后的状态回读。
     * 这里没有把指令登记成已发送，也不会生成虚假的主机回执。
     */
    private void dropCurrentCommandAfterWriteFailure() {
        QueuedCommand command = currentWrite;
        currentWrite = null;
        writing = false;
        handler.removeCallbacks(writeTimeoutRunnable);
        if (command == null) {
            processWriteQueue();
            return;
        }
        addLog("指令多次未能启动，已放弃本次发送并放行后续队列: " + command.payload);
        boolean queueAlreadyDriven = false;
        if (isUserControlCommand(command.cmd)) {
            if ("set_care_mode".equals(command.cmd)) {
                clearPendingCareMode(zoneOf(command.payload), careModeValueOfPayload(command.payload));
            }
            String key = controlKeyForCommand(command.cmd);
            if (!TextUtils.isEmpty(key)) {
                noteControlFinalState(key, "write_failed");
            }
            // 温度等设置没有拿到 ack 时，更要让真实状态回读有机会发出；
            // 若已有总表在途，入队会去重，不会制造并行双舱查询。
            if (connected && protocolReady) {
                queueAlreadyDriven = requestCabinStatusForZone(currentZone, true);
            }
        }
        if (!queueAlreadyDriven) {
            processWriteQueue();
        }
        emit();
    }

    @SuppressLint("MissingPermission")
    private boolean tryWrite(byte[] data, int writeType) {
        if (missingBluetoothConnectPermission()) {
            return false;
        }
        writeCharacteristic.setWriteType(writeType);
        writeCharacteristic.setValue(data);
        // 本方法只负责启动写入；启动后的记录和计时由调用方补上，
        // 这样异常分支能区分“明确未启动”和“已经交给协议栈”。
        return bluetoothGatt.writeCharacteristic(writeCharacteristic);
    }

    private void noteWriteStarted(int writeType) {
        QueuedCommand command = currentWrite;
        if (command != null) {
            // 同一条此前未启动的指令现在已真正交给协议栈，旧 TX false 不再是当前状态。
            // 其他控制或普通轮询成功不能清除它，避免掩盖温度设置仍未下发的事实。
            if (command.payload.equals(stickyWriteFailurePayload)) {
                clearStickyError();
                if (lastError.startsWith("TX failed:")) {
                    lastError = "";
                }
                addLog("此前未启动的指令现已写入，清除对应 TX 错误: " + command.cmd);
            }
            // 只有 writeCharacteristic 明确启动成功才到这里；此时记录的
            // 才是实际交给协议栈的报文，而不是稍早入队的另一条报文。
            long writtenAtMs = System.currentTimeMillis();
            lastSent = command.payload;
            if (isUserControlCommand(command.cmd)) {
                lastControlSent = command.payload;
                lastControlSentAtMs = writtenAtMs;
                lastControlSentCmd = command.cmd;
                // 新一次实发开始新的回包分代，旧回包不能冒充本次确认。
                lastControlReply = "";
                lastControlReplyAtMs = 0L;
                lastControlReplyCmd = "";
                lastControlWriteStatus = -1;
                lastControlWriteStatusAtMs = 0L;
                lastControlWriteStatusPayload = "";
                lastControlWriteStatusMatched = false;
                controlTimeoutLoggedForSentAtMs = 0L;
            }
            if ("get_all_status".equals(command.cmd)) {
                ZoneEnvironmentState environment = environmentForZone(zoneOf(command.payload));
                environment.statusQuerySentAtMs = writtenAtMs;
                if (environment.awaitingStatusReplySinceAtMs <= 0L) {
                    // 只记第一条尚未得到有效状态回答的查询；持续轮询不能把超时起点向后推。
                    environment.awaitingStatusReplySinceAtMs = writtenAtMs;
                }
            }
            addLog("发送 " + command.data.length + " bytes: " + command.payload);
        }
        currentWriteType = writeType;
        // Android 对两种写类型都通过 onCharacteristicWrite 报告本地 GATT 结果。
        // 不回主机 ack 与没有 GATT 写回执是两件事，不能混为一谈。
        addLog("写入方式: " + writeTypeName(writeType) + "，等待 GATT 写回执");
        handler.removeCallbacks(writeTimeoutRunnable);
        handler.postDelayed(writeTimeoutRunnable, WRITE_CALLBACK_TIMEOUT_MS);
    }

    private void finishCurrentWrite(boolean success) {
        handler.removeCallbacks(writeTimeoutRunnable);
        currentWrite = null;
        writing = false;
        if (success) {
            addLog("写入完成");
        }
        processWriteQueue();
        emit();
    }

    /** 回执超时不重发原指令；先留出旧回执窗口，再恢复后续串行队列。 */
    private void finishCurrentWriteAfterTimeout() {
        handler.removeCallbacks(writeTimeoutRunnable);
        currentWrite = null;
        writing = false;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                processWriteQueue();
            }
        }, WRITE_RECOVERY_DELAY_MS);
        emit();
    }

    private static String careModeValueOfPayload(String payload) {
        try {
            return new JSONObject(payload).optString("value", "");
        } catch (JSONException ignored) {
            // 指令由本类生成；异常时保持空值，回执不能据此伪造确认。
            return "";
        }
    }

    /** 记下刚发出的指令，等着和主机的回包对上。 */
    private void trackAwaitingCommand(String cmd, String zone, String payload) {
        if (TextUtils.isEmpty(cmd)) {
            return;
        }
        String careModeValue = "set_care_mode".equals(cmd)
                ? careModeValueOfPayload(payload) : "";
        awaitingCommands.add(new AwaitingCommand(cmd, zone, System.currentTimeMillis(),
                connectionGeneration, careModeValue));
        // 只保留最近若干条：主机不回包的指令不能把这个队列撑爆。
        while (awaitingCommands.size() > 16) {
            awaitingCommands.pollFirst();
        }
    }

    /**
     * 收到带指令名的回包：按先进先出对齐队头。
     *
     * <p>主机按收到的顺序回包，所以“已发出还没回包”的第一条就是这条回包
     * 对应的指令。之前按命令名在队列里到处找，主机主动推的状态帧会把
     * 排在中间的等待项顶掉：队列顺序一乱，主机随后那句 error 就会算到
     * 用户刚点下的开关头上，那个开关从此只能打开、关不掉。</p>
     */
    private AwaitingCommand resolveAwaitingCommand(String cmd, String zone) {
        if (TextUtils.isEmpty(cmd)) {
            return null;
        }
        pruneStaleAwaitingCommands();
        if ("get_all_status".equals(cmd)) {
            return resolveCabinStatusAwaitingCommand(zone);
        }
        AwaitingCommand head = awaitingCommands.peekFirst();
        if (head == null) {
            return null;
        }
        if (head.cmd.equals(cmd) && matchesZone(head, zone)) {
            awaitingCommands.pollFirst();
            return head;
        }
        // 队头对不上：主机主动推的状态帧不是任何一条指令的回包，队头不能动。
        // 控制回包带命令名，可越过舱位不符的队头，按命令名+舱位找自己的等待项；
        // 普通查询不能做这种纠偏，避免左右舱同名回包互相误摘。
        if (!isControlCommandName(cmd)) {
            return null;
        }
        AwaitingCommand matched = null;
        for (AwaitingCommand candidate : awaitingCommands) {
            if (candidate.cmd.equals(cmd) && matchesZone(candidate, zone)) {
                matched = candidate;
                break;
            }
        }
        if (matched == null) {
            return null;
        }
        awaitingCommands.remove(matched);
        addLog("[主机] 队列错位，已按指令名对齐 " + cmd);
        return matched;
    }

    /**
     * 对齐总表回包。带 zone 时按命令名和舱位精确找；省略 zone 时只接受
     * 唯一舱位的等待项，不能在左右舱各有一条同名查询时随便摘队头。
     */
    private AwaitingCommand resolveCabinStatusAwaitingCommand(String zone) {
        long now = System.currentTimeMillis();
        AwaitingCommand matched = null;
        for (AwaitingCommand candidate : awaitingCommands) {
            if (!"get_all_status".equals(candidate.cmd)
                    || candidate.generation != connectionGeneration
                    || now - candidate.sentAtMs > REPLY_WAIT_MAX_MS) {
                continue;
            }
            if (!TextUtils.isEmpty(zone)) {
                if (zone.equals(candidate.zone)) {
                    matched = candidate;
                    break;
                }
                continue;
            }
            if (matched == null) {
                matched = candidate;
            } else if (!TextUtils.equals(matched.zone, candidate.zone)) {
                addLog("[主机] 总表回包省略 zone，且有多舱查询在等待，拒绝猜测归属");
                return null;
            }
        }
        if (matched == null) {
            return null;
        }
        boolean wasHead = awaitingCommands.peekFirst() == matched;
        awaitingCommands.remove(matched);
        if (!wasHead) {
            addLog("[主机] 总表回包已按唯一命令/舱位对齐，未误摘其他等待项");
        }
        return matched;
    }

    /** 回包的舱位和等待项对不对得上。 */
    private boolean matchesZone(AwaitingCommand awaiting, String zone) {
        return TextUtils.isEmpty(zone)
                || TextUtils.isEmpty(awaiting.zone)
                || zone.equals(awaiting.zone);
    }

    /** 是不是用户下发的控制指令：只有控制指令能按名字纠偏。 */
    private boolean isControlCommandName(String cmd) {
        return isUserControlCommand(cmd);
    }

    /**
     * 清掉等太久的等待项。
     *
     * <p>主机不回包的指令不能一直占着队头：它会把后面所有回包的
     * 归属都带偏，主机那句 error 就会算错人。</p>
     */
    private void pruneStaleAwaitingCommands() {
        long now = System.currentTimeMillis();
        AwaitingCommand head = awaitingCommands.peekFirst();
        while (head != null) {
            if (head.generation != connectionGeneration) {
                // 上一条链路的等待项：那条链路已经没了，不能拿来当归属。
                awaitingCommands.pollFirst();
                head = awaitingCommands.peekFirst();
                continue;
            }
            long replyWaitMs = isUserControlCommand(head.cmd)
                    ? CONTROL_REPLY_TIMEOUT_MS * 2L : REPLY_WAIT_MAX_MS;
            if (now - head.sentAtMs <= replyWaitMs) {
                // 控制回包的5秒观察窗不能被普通查询的2.5秒清理阈值提前截断；
                // 等待项多保留一段仅用于识别迟到回包，超时提示仍在5秒触发。
                break;
            }
            awaitingCommands.pollFirst();
            addLog("[主机] 指令 " + head.cmd + " 迟迟没有回包，放弃等待");
            head = awaitingCommands.peekFirst();
        }
    }

    /**
     * 处理写入回执。
     *
     * <p>主机确认收到这一条之后才发下一条，主机的接收缓冲不会被冲爆，
     * 用户的开关指令也不会被挤掉。</p>
     */
    private void handleWriteCallback(BluetoothGatt gatt, int status) {
        if (bluetoothGatt == null || gatt != bluetoothGatt) {
            addLog("忽略旧 GATT 的迟到写回执: Status=" + status);
            return;
        }
        QueuedCommand callbackCommand = currentWrite;
        boolean controlCallback = writing && callbackCommand != null
                && isUserControlCommand(callbackCommand.cmd)
                // 新请求入队会清空 lastControlSent；此后到达的旧请求回执不能覆盖新分代。
                && callbackCommand.payload.equals(lastControlSent);
        if (controlCallback) {
            lastControlWriteStatus = status;
            lastControlWriteStatusAtMs = System.currentTimeMillis();
            lastControlWriteStatusPayload = callbackCommand.payload;
            lastControlWriteStatusMatched = false;
        }
        if (!writing) {
            // 超时窗口内到达的旧回执只清理对应计数，绝不推进下一条。
            if (skippedWriteCallbacks > 0) {
                skippedWriteCallbacks--;
            }
            addLog("迟到写回执 Status=" + status + "，未推进队列");
            return;
        }
        if (skippedWriteCallbacks > 0) {
            // 上一条超时后才启动当前指令，这条回执按上一条迟到处理。
            skippedWriteCallbacks--;
            addLog("忽略上一条指令的迟到写回执 Status=" + status);
            handler.removeCallbacks(writeTimeoutRunnable);
            handler.postDelayed(writeTimeoutRunnable, WRITE_CALLBACK_TIMEOUT_MS);
            return;
        }
        if (controlCallback) {
            lastControlWriteStatusMatched = true;
        }
        addLog("GATT 写回执 Status=" + status + ", cmd="
                + (callbackCommand == null ? "" : callbackCommand.cmd));
        if (status == BluetoothGatt.GATT_SUCCESS) {
            finishCurrentWrite(true);
        } else {
            // 非成功回执同样不能证明对端没有收到。保留错误提示并推进队列，
            // 只有 writeCharacteristic 明确启动失败时才允许安全重排。
            setError("写入回执失败: " + status + "，未自动重发，请核对主机状态");
            finishCurrentWrite(false);
        }
    }

    private int getPreferredWriteType() {
        if (writeCharacteristic == null) {
            return BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        }
        int properties = writeCharacteristic.getProperties();
        boolean canWriteNoResponse = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        boolean canWriteWithResponse = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
        // 主机控制需要可观测的 Status=0。设备支持 WRITE 时优先 WriteWithResponse；
        // 只有不支持 WRITE 时才使用 WriteWithoutResponse，并同样等待 Android 写回执。
        return HostTransportPolicy.preferWriteWithResponse(canWriteWithResponse, canWriteNoResponse)
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
    }

    private String writeTypeName(int writeType) {
        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            return "WriteWithoutResponse";
        }
        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_SIGNED) {
            return "SignedWrite";
        }
        return "WriteWithResponse";
    }

    private String describeProperties(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) {
            return "--";
        }
        int properties = characteristic.getProperties();
        ArrayList<String> names = new ArrayList<>();
        if ((properties & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
            names.add("read");
        }
        if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            names.add("write");
        }
        if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            names.add("writeWithoutResponse");
        }
        if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            names.add("notify");
        }
        if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            names.add("indicate");
        }
        return names.isEmpty() ? "0x" + Integer.toHexString(properties) : TextUtils.join(", ", names);
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
            // 有些设备广播里不带名称：照样列出来，操作者才选得到它。
            name = "未知设备 " + addressSuffix(address);
        } else {
            rememberDeviceName(address, name);
        }
        DeviceItem item = new DeviceItem(address, name, result.getRssi());
        synchronized (devicesLock) {
            int index = indexOfDevice(item.deviceId);
            if (index >= 0) {
                // 已经见过的设备只更新信号强度，位置不动：
                // 否则 RSSI 一抖动就整体重排，正在找的设备会来回跳。
                devices.set(index, new DeviceItem(item.deviceId, item.deviceName, item.rssi,
                        System.currentTimeMillis()));
            } else {
                addLog("发现: " + name);
                // 新设备追加到末尾，已有设备的位置一动不动。
                appendDiscoveredDevice(item);
            }
        }
        emit();
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
        synchronized (devicesLock) {
            for (int i = devices.size() - 1; i >= 0; i--) {
                if (devices.get(i).lastSeenMs < deadline) {
                    devices.remove(i);
                }
            }
        }
    }

    private String findDeviceName(String address) {
        synchronized (devicesLock) {
            for (DeviceItem item : devices) {
                if (item.deviceId.equals(address)) {
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
        return "device_name_" + address;
    }

    /** 地址尾段：设备没有广播名称时，用它区分是哪一台。 */
    private String addressSuffix(String address) {
        if (TextUtils.isEmpty(address) || address.length() < 5) {
            return "";
        }
        return address.substring(Math.max(0, address.length() - 5));
    }

    private int indexOfDevice(String address) {
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).deviceId.equals(address)) {
                return i;
            }
        }
        return -1;
    }

    @SuppressLint("MissingPermission")
    private void startServiceDiscovery() {
        if (bluetoothGatt == null || protocolReady || connected) {
            return;
        }
        if (serviceDiscoveryAttempts >= MAX_SERVICE_DISCOVERY_ATTEMPTS) {
            connectionStateText = "发现服务失败";
            setError("发现服务超时，请断开后重新连接，或确认设备支持 A002/C304/C305");
            return;
        }
        serviceDiscoveryAttempts++;
        connectionStateText = "发现服务中 " + serviceDiscoveryAttempts + "/" + MAX_SERVICE_DISCOVERY_ATTEMPTS;
        addLog("开始发现服务 " + serviceDiscoveryAttempts + "/" + MAX_SERVICE_DISCOVERY_ATTEMPTS);
        boolean started;
        try {
            started = bluetoothGatt.discoverServices();
        } catch (Exception exception) {
            started = false;
            addLog("发现服务异常: " + exception.getMessage());
        }
        if (!started) {
            if (serviceDiscoveryAttempts < MAX_SERVICE_DISCOVERY_ATTEMPTS) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        startServiceDiscovery();
                    }
                }, SERVICE_DISCOVERY_DELAY_MS);
            } else {
                connectionStateText = "发现服务失败";
                setError("发现服务启动失败，请断开后重新连接");
            }
            emit();
            return;
        }
        handler.removeCallbacks(serviceDiscoveryTimeoutRunnable);
        handler.postDelayed(serviceDiscoveryTimeoutRunnable, SERVICE_DISCOVERY_TIMEOUT_MS);
        emit();
    }

    /**
     * 蓝牙回调。
     *
     * <p>这些回调都在 binder 线程上进来，这里统一把处理切回主线程：
     * 发送队列、待确认开关、界面数据这些共享状态只允许主线程碰，
     * 蓝牙线程只负责"收到了什么"，不负责"改成什么"。</p>
     */
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
        public void onMtuChanged(@NonNull final BluetoothGatt gatt, final int mtu, final int status) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    handleMtuChanged(gatt, mtu, status);
                }
            });
        }

        @SuppressLint("MissingPermission")
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
        public void onDescriptorWrite(@NonNull final BluetoothGatt gatt, @NonNull final BluetoothGattDescriptor descriptor, final int status) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    handleDescriptorWrite(gatt, status);
                }
            });
        }

        @Override
        public void onCharacteristicWrite(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, int status) {
            final int writeStatus = status;
            // 回调在 binder 线程上，统一切回主线程处理，避免和发送队列并发操作。
            handler.post(new Runnable() {
                @Override
                public void run() {
                    handleWriteCallback(gatt, writeStatus);
                }
            });
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic) {
            handleCharacteristicChanged(gatt, characteristic, characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
            handleCharacteristicChanged(gatt, characteristic, value);
        }
    };

    /**
     * 连接状态变化（主线程处理）。
     */
    private void handleConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        // 旧 GATT 的任何迟到回调都不能改写新链路；bluetoothGatt=null 时同样忽略。
        if (gatt != bluetoothGatt) {
            addLog("忽略旧 GATT 连接回调: status=" + status + ", newState=" + newState);
            return;
        }
        addLog("GATT 状态变化: status=" + status + ", newState=" + newState);
        if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            connected = false;
            protocolReady = false;
            // 诊断按物理连接分代，避免上一链路的完整帧掩盖本次完全无回包。
            lastNotifyChunkAtMs = 0L;
            lastNotifyChunk = "";
            lastCompleteFrameAtMs = 0L;
            serviceDiscoveryAttempts = 0;
            notifyEnableAttempts = 0;
            connectionStateText = "发现服务中";
            addLog("GATT 连接成功");
            // 物理 GATT 成功不等于协议可用；连接超时保持到服务与 Notify 全部就绪。
            emit();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startServiceDiscovery();
                }
            }, SERVICE_DISCOVERY_DELAY_MS);
            return;
        }
        if (newState == BluetoothGatt.STATE_CONNECTING) {
            connectionStateText = "连接中";
            emit();
            return;
        }
        if (newState == BluetoothGatt.STATE_DISCONNECTING) {
            connectionStateText = "断开中";
            emit();
            return;
        }
        connected = false;
        protocolReady = false;
        stopAutoUpdate();
        freezeTreatmentClock();
        // 与 closeConnection() 同一套清场逻辑：断开后不留上一轮的数据。
        clearLiveHostData();
        handler.removeCallbacks(serviceDiscoveryTimeoutRunnable);
        handler.removeCallbacks(mtuReadyTimeoutRunnable);
        handler.removeCallbacks(protocolReadyFallbackRunnable);
        serviceDiscoveryAttempts = 0;
        notifyEnableAttempts = 0;
        connectionStateText = "已断开";
        addLog("GATT 已断开或连接失败: status=" + status + ", newState=" + newState);
        writeCharacteristic = null;
        notifyCharacteristic = null;
        writing = false;
        writeQueue.clear();
        currentWrite = null;
        handler.removeCallbacks(writeTimeoutRunnable);
        if (bluetoothGatt == gatt) {
            // 掉线后释放 GATT，下一次重连才有干净的底层资源可用。
            try {
                gatt.close();
            } catch (Exception ignored) {
            }
            bluetoothGatt = null;
        }
        // 掉线后交给控制器安排自动重连，状态文字会变成"自动重连中"。
        autoReconnect.onDisconnected();
        emit();
    }

    /** MTU 协商结果（主线程处理）。 */
    private void handleMtuChanged(BluetoothGatt gatt, int mtu, int status) {
        if (bluetoothGatt == null || gatt != bluetoothGatt) {
            addLog("忽略旧 GATT 的 MTU 回调: status=" + status);
            return;
        }
        handler.removeCallbacks(mtuReadyTimeoutRunnable);
        addLog("MTU " + mtu + " status " + status);
        if (!protocolReady && writeCharacteristic != null && notifyCharacteristic != null) {
            enableNotifyChannel();
        }
        emit();
    }

    /** 服务发现完成（主线程处理）。 */
    @SuppressLint("MissingPermission")
    private void handleServicesDiscovered(BluetoothGatt gatt, int status) {
        if (bluetoothGatt == null || gatt != bluetoothGatt) {
            addLog("忽略旧 GATT 的服务发现回调: status=" + status);
            return;
        }
        handler.removeCallbacks(serviceDiscoveryTimeoutRunnable);
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (serviceDiscoveryAttempts < MAX_SERVICE_DISCOVERY_ATTEMPTS) {
                addLog("发现服务失败 " + status + "，正在重试");
                startServiceDiscovery();
            } else {
                connectionStateText = "发现服务失败";
                setError("发现服务失败: " + status);
            }
            return;
        }
        if (missingBluetoothConnectPermission()) {
            setError("缺少蓝牙连接权限，请先申请权限");
            return;
        }
        BluetoothGattService service = gatt.getService(ICU_SERVICE_UUID);
        if (service == null) {
            connectionStateText = "协议不匹配";
            setError("未找到协议服务: " + ICU_SERVICE_UUID);
            return;
        }
        notifyCharacteristic = service.getCharacteristic(ICU_NOTIFY_UUID);
        writeCharacteristic = service.getCharacteristic(ICU_WRITE_UUID);
        if (notifyCharacteristic == null) {
            connectionStateText = "协议不匹配";
            setError("未找到 TX/Notify 特征: " + ICU_NOTIFY_UUID);
            return;
        }
        if (writeCharacteristic == null) {
            connectionStateText = "协议不匹配";
            setError("未找到 RX/Write 特征: " + ICU_WRITE_UUID);
            return;
        }
        addLog("RX Write 已保存: " + ICU_WRITE_UUID);
        addLog("C305 属性: " + describeProperties(notifyCharacteristic));
        addLog("C304 属性: " + describeProperties(writeCharacteristic));
        try {
            notifyEnableAttempts = 0;
            boolean mtuStarted = gatt.requestMtu(ICU_MTU);
            if (mtuStarted) {
                addLog("请求 MTU " + ICU_MTU);
                handler.removeCallbacks(mtuReadyTimeoutRunnable);
                handler.postDelayed(mtuReadyTimeoutRunnable, MTU_READY_TIMEOUT_MS);
            } else {
                addLog("MTU 请求未启动，直接启用 Notify");
                enableNotifyChannel();
            }
        } catch (Exception exception) {
            addLog("MTU 请求异常: " + exception.getMessage());
            enableNotifyChannel();
        }
    }

    /** Notify 描述符写完（主线程处理）。 */
    private void handleDescriptorWrite(BluetoothGatt gatt, int status) {
        if (bluetoothGatt == null || gatt != bluetoothGatt) {
            addLog("忽略旧 GATT 的 Notify 描述符回执: status=" + status);
            return;
        }
        handler.removeCallbacks(protocolReadyFallbackRunnable);
        if (status == BluetoothGatt.GATT_SUCCESS) {
            markProtocolReady();
            return;
        }
        if (notifyEnableAttempts < MAX_NOTIFY_ENABLE_ATTEMPTS) {
            addLog("Notify descriptor 写入失败 " + status + "，正在重试");
            enableNotifyChannel();
            return;
        }
        connectionStateText = "Notify失败";
        setError("Notify descriptor 写入失败: " + status);
    }

    private void markProtocolReady() {
        if (protocolReady) {
            return;
        }
        connected = true;
        protocolReady = true;
        long readyAtMs = System.currentTimeMillis();
        // 链路刚就绪时从现在开始计时，静默看门狗才有判断依据。
        lastNotifyAtMs = readyAtMs;
        dataPathWatchStartedAtMs = readyAtMs;
        lastValidHostStateAtMs = 0L;
        dataPathState = "waiting";
        dataPathError = "";
        linkProbeAtMs = 0L;
        // 治疗时长只在协议就绪这一刻起算/续算：没连上主机、或样本信息没录完，
        // 这里不会落下任何墙钟锚点，页面保持 "--"。
        resumeTreatmentClockIfAllowed();
        handler.removeCallbacks(serviceDiscoveryTimeoutRunnable);
        handler.removeCallbacks(mtuReadyTimeoutRunnable);
        handler.removeCallbacks(protocolReadyFallbackRunnable);
        handler.removeCallbacks(linkWatchdogRunnable);
        handler.postDelayed(linkWatchdogRunnable, LINK_WATCHDOG_INTERVAL_MS);
        connectionStateText = "已连接 · 等待状态回包";
        lastError = "";
        // 只有新连接真正协议就绪才解除留存错误；扫描/轮询/收帧都不解除。
        clearStickyError();
        addLog("协议通道已就绪，等待有效主机状态回包");
        // 协议就绪时 GATT 已经建立，系统的设备名缓存通常这时才读得到：
        // 自动重连刚连上那会儿往往还只有地址，这里再补一次名称。
        if (TextUtils.isEmpty(connectedDeviceName) && bluetoothGatt != null) {
            String resolved = resolveDeviceName(connectedDeviceId, bluetoothGatt.getDevice());
            if (!TextUtils.isEmpty(resolved)) {
                connectedDeviceName = resolved;
                addLog("已补全设备名称: " + resolved);
            }
        }
        autoReconnect.onConnected(connectedDeviceId, connectedDeviceName);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 描述符写完后稍作间隔再发首条总表；不再让版本查询和四条回退排在它前面。
                readAllStatus();
                startAutoUpdate();
            }
        }, 100L);
        emit();
    }

    @SuppressLint("MissingPermission")
    private void enableNotifyChannel() {
        if (protocolReady || bluetoothGatt == null || notifyCharacteristic == null) {
            return;
        }
        if (missingBluetoothConnectPermission()) {
            setError("缺少蓝牙连接权限，请先申请权限");
            return;
        }
        notifyEnableAttempts++;
        connectionStateText = "启用Notify中 " + notifyEnableAttempts + "/" + MAX_NOTIFY_ENABLE_ATTEMPTS;
        addLog("启用 C305 Notify " + notifyEnableAttempts + "/" + MAX_NOTIFY_ENABLE_ATTEMPTS);
        try {
            boolean notificationStarted = bluetoothGatt.setCharacteristicNotification(notifyCharacteristic, true);
            if (!notificationStarted) {
                connectionStateText = "Notify失败";
                setError("Notify 本地开关启动失败");
                return;
            }
            BluetoothGattDescriptor descriptor = notifyCharacteristic.getDescriptor(CLIENT_CONFIG_UUID);
            if (descriptor == null) {
                addLog("Notify 无 2902 descriptor，仅开启本地通知");
                markProtocolReady();
                return;
            }
            int properties = notifyCharacteristic.getProperties();
            if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    && (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                addLog("C305 使用 Indicate descriptor");
            } else {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                addLog("C305 使用 Notify descriptor");
            }
            boolean descriptorWriteStarted = bluetoothGatt.writeDescriptor(descriptor);
            if (descriptorWriteStarted) {
                handler.removeCallbacks(protocolReadyFallbackRunnable);
                handler.postDelayed(protocolReadyFallbackRunnable, NOTIFY_READY_TIMEOUT_MS);
                emit();
                return;
            }
            if (notifyEnableAttempts < MAX_NOTIFY_ENABLE_ATTEMPTS) {
                addLog("Notify descriptor 写入未启动，正在重试");
                enableNotifyChannel();
                return;
            }
            connectionStateText = "Notify失败";
            setError("Notify descriptor 写入未启动");
        } catch (Exception exception) {
            connectionStateText = "Notify失败";
            setError("Notify 启用异常: " + exception.getMessage());
        }
    }

    private void handleCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] data) {
        if (characteristic == null || !ICU_NOTIFY_UUID.equals(characteristic.getUuid()) || data == null) {
            return;
        }
        // 已经被新链路替换掉的旧 GATT，它的 Notify 直接忽略：
        // 旧链路迟到的帧会把新链路刚发出去的指令的归属带偏，
        // 用户点一下开关就会算到别的指令头上。
        if (bluetoothGatt == null || gatt != bluetoothGatt) {
            addLog("忽略旧连接的迟到 Notify");
            return;
        }
        String chunk = new String(data, StandardCharsets.UTF_8);
        final ArrayList<String> frames;
        // Android 可能从不同 binder 线程交付 Notify；重组结果和对应计数必须在同一临界区更新。
        synchronized (notifyReassembler) {
            // 收到任何一帧主机数据都算链路还活着，静默看门狗从这里重新计时。
            lastNotifyAtMs = System.currentTimeMillis();
            lastNotifyChunkAtMs = lastNotifyAtMs;
            // 探测已经问到答案：清掉探测标记，等下一次再问。
            linkProbeAtMs = 0L;
            lastNotifyChunk = chunk;
            diagNotifyChunkCount++;
            noteDiagFrame(chunk);
            // 主机的一帧 JSON 可能被拆成好几片，先拼成完整帧再逐帧解析。
            frames = appendNotifyChunk(chunk);
        }
        if (frames.isEmpty()) {
            addLog("收到分片 " + data.length + " bytes，等待完整 JSON: " + chunk);
            emit();
            return;
        }
        // 蓝牙回调在 binder 线程上：这里只负责把分片拼成完整帧，
        // 解析和状态更新全部交给主线程做。
        // 开关判断、待确认状态、超时处理都只在主线程上读写，
        // 才不会出现「回执已经收到，主线程却看不到待确认记录」这种偶发问题。
        handler.post(new Runnable() {
            @Override
            public void run() {
                lastCompleteFrameAtMs = System.currentTimeMillis();
                for (String frame : frames) {
                    lastReceived = frame;
                    // 任意 Notify 帧只清即时错误，不清 stickyError；
                    // 传输层有数据不代表之前的写入失败已经被解决。
                    lastError = "";
                    lastParsedStatus = parseStatus(frame);
                    addLog("收到完整消息 " + frame.getBytes(StandardCharsets.UTF_8).length + " bytes ["
                            + extractJsonString(frame, "cmd") + "]: " + frame);
                }
                emit();
            }
        });
    }

    /**
     * 把 BLE 收上来的分片拼成一帧一帧的完整 JSON。
     *
     * <p>主机的一帧 JSON 可能超过一个 BLE 包的长度，被拆成好几片下发。
     * 收到半截 JSON 直接解析一定失败，那一帧的状态就丢了：
     * 开关明明变了，App 却停在旧值。这里按 JSON 的大括号配平切帧，
     * 字符串里的转义引号不算，一包里有多帧就一帧一帧切出来。</p>
     *
     * @return 本次拼完整的帧；还没拼完整时返回空列表
     */
    private ArrayList<String> appendNotifyChunk(String chunk) {
        if (TextUtils.isEmpty(chunk)) {
            return new ArrayList<>();
        }
        synchronized (notifyReassembler) {
            HostNotifyReassembler.Result result = notifyReassembler.append(
                    chunk, System.currentTimeMillis());
            diagCompleteFrameCount += result.frames.size();
            diagDiscardedFrameCount += result.discardedFrameCount;
            diagNotifyResetCount += result.resetCount;
            for (String frame : result.frames) {
                noteDiagFrame(frame);
            }
            if (!TextUtils.isEmpty(result.lastDropReason)) {
                noteDiagDrop(result.lastDropReason);
                addLog("Notify 重组丢弃/复位: reason=" + result.lastDropReason
                        + ", discarded=" + result.discardedFrameCount
                        + ", resets=" + result.resetCount
                        + ", buffer=" + notifyReassembler.getBufferLength());
            }
            return result.frames;
        }
    }

    private void startAutoUpdate() {
        stopAutoUpdate();
        handler.postDelayed(autoUpdateRunnable, AUTO_UPDATE_MS);
        addLog("自动更新已开启（双舱交替，每舱目标约0.5秒；故障背景舱退避）");
    }

    private void stopAutoUpdate() {
        handler.removeCallbacks(autoUpdateRunnable);
    }

    private String parseStatus(String message) {
        updateStatusFromJson(message);
        String cmd = extractJsonString(message, "cmd");
        String ret = extractJsonString(message, "ret");
        if ("get_all_status".equals(cmd)) {
            if (!TextUtils.isEmpty(ret) && !"ok".equals(ret) && !"success".equals(ret)) {
                return cmd + (TextUtils.isEmpty(ret) ? "" : " -> " + ret);
            }
            StringBuilder builder = new StringBuilder("get_all_status");
            if (!TextUtils.isEmpty(ret)) {
                builder.append(" -> ").append(ret);
            }
            try {
                JSONObject parsed = new JSONObject(message);
                JSONObject data = parsed.optJSONObject("data");
                HostEnvironmentParser.Reading reading = HostEnvironmentParser.read(
                        data != null ? data : parsed);
                if (reading.temperature != null) {
                    builder.append(" 温度").append(formatNumber(reading.temperature));
                }
                if (reading.oxygen != null) {
                    builder.append(" 氧").append(formatNumber(reading.oxygen));
                }
                if (reading.humidity != null) {
                    builder.append(" 湿度").append(formatNumber(reading.humidity));
                }
                if (reading.co2 != null) {
                    builder.append(" CO2 ").append(reading.co2);
                }
            } catch (JSONException ignored) {
                // updateStatusFromJson 已记录原始解析错误，这里保留简要状态文本。
            }
            return builder.toString();
        }
        String value = extractJsonNumber(message, "value");
        if (!TextUtils.isEmpty(cmd)) {
            if ("get_temp".equals(cmd) || "get_o2".equals(cmd) || "get_humidity".equals(cmd)) {
                return cmd + " -> " + formatDiv10(value);
            }
            if (("set_temp".equals(cmd) || "set_o2".equals(cmd)) && "ok".equals(ret)) {
                return cmd + " -> " + formatDiv10(value);
            }
            if (!TextUtils.isEmpty(value)) {
                return cmd + " -> " + value;
            }
            return cmd + (TextUtils.isEmpty(ret) ? "" : " -> " + ret);
        }
        return message;
    }

    private void updateStatusFromJson(String message) {
        try {
            JSONObject parsed = new JSONObject(message);
            String cmd = parsed.optString("cmd", "");
            // 主机回 {"cmd":"error"}：它不认识刚才那条指令。
            // 这种回包不带指令名，只能靠"已发出未回包"的那条队列对齐。
            if ("error".equals(cmd)) {
                handleHostUnknownCommand();
                return;
            }
            String replyZone = replyZoneOf(parsed);
            AwaitingCommand replyCommand = resolveAwaitingCommand(cmd, replyZone);
            if (replyCommand != null && TextUtils.isEmpty(replyZone)
                    && !TextUtils.isEmpty(replyCommand.zone)) {
                // 切舱后才到的回包可能省略 zone：归属实际命令的舱位，
                // 不能按当前页面舱位把旧环境值或护理模式确认写到另一舱。
                parsed.put("zone", replyCommand.zone);
            }
            // 带命令名的成功或失败回包都可证明主机回应了本次控制；
            // 这不等于物理效果已确认，裸 {"cmd":"error"} 因缺少命令名不会走到这里。
            noteControlReply(replyCommand, message);
            // 主机回了 ret:"xxx_err"：它认识这条指令，但不接受这个值。
            // 必须把这句话告诉用户，否则界面停在"已下发"，用户以为设好了，
            // 主机那边其实什么都没改。
            String ret = parsed.optString("ret", "");
            if (ret.endsWith("_err") || "error".equals(ret) || "fail".equals(ret)) {
                handleHostValueRejected(parsed, cmd, ret, replyCommand);
                return;
            }
            if (TextUtils.isEmpty(cmd)) {
                // 有些固件直接推无 cmd 的状态帧，data 包裹和顶层平铺都要读。
                JSONObject bareData = parsed.optJSONObject("data");
                JSONObject status = bareData != null ? bareData : parsed;
                if (HostEnvironmentParser.hasEnvironmentField(status)) {
                    AwaitingCommand environmentReply =
                            resolveCabinStatusAwaitingCommand(replyZone);
                    if (environmentReply != null && TextUtils.isEmpty(replyZone)
                            && !TextUtils.isEmpty(environmentReply.zone)) {
                        parsed.put("zone", environmentReply.zone);
                    }
                    // 没有等待中的总表查询时，仍沿用原协议对主动推送的
                    // 当前舱兼容路径；有唯一查询时则优先使用实际查询舱位。
                    applyAllStatus(parsed, status, message);
                    return;
                }
                // 没有状态内容的裸 ok 才按队头记为控制回执。
                applyBareControlAck(parsed, message);
                return;
            }
            // 开关回读：主机只会回报它真正的开关状态，这里必须接住。
            // 少了这一步，App 只能靠下发时的猜测，用户点一下就只会"打开"。
            if (isSwitchReaderCommand(cmd)) {
                applySwitchReaderResponse(cmd, parsed);
            }
            if ("get_all_status".equals(cmd)
                    && (isOkResponse(parsed) || parsed.has("data")
                    || (!parsed.has("ret") && !parsed.has("result")))) {
                if (TextUtils.isEmpty(replyZone) && replyCommand == null) {
                    // 超时后的迟到帧或主动推送没有舱位证据，不能拿当前页面舱位猜测。
                    diagZoneRejectedCount++;
                    noteDiagDrop("zone_missing");
                    noteDiagFrame(message);
                    addLog("[主机] 总表回包无 zone 且无唯一等待命令，已忽略以避免串舱: "
                            + message);
                    return;
                }
                JSONObject data = parsed.optJSONObject("data");
                // 无 ret、无 data 的顶层状态帧同样是有效总表。
                applyAllStatus(parsed, data != null ? data : parsed, message);
                return;
            }

            if (applyEnvironmentValueResponse(cmd, parsed)) {
                return;
            }
            if ("get_care_mode".equals(cmd)) {
                if (TextUtils.isEmpty(replyZone) && replyCommand == null) {
                    addLog("[护理模式] 回读无 zone 且无等待命令，已忽略以避免串舱");
                    return;
                }
                JSONObject data = parsed.optJSONObject("data");
                String reportedValue = readCareModeValue(data != null ? data : parsed);
                if (TextUtils.isEmpty(reportedValue) && data == null) {
                    Object rawData = parsed.opt("data");
                    if (rawData instanceof String) {
                        reportedValue = (String) rawData;
                    }
                }
                if (!isSupportedCareMode(reportedValue)) {
                    addLog("[护理模式] 回读没有合法 care_mode，未改变确认状态: " + message);
                    return;
                }
                // 回读只采用主机实际报告值，绝不用 pending 的 set 值补造确认。
                confirmCareMode(resolveResponseZone(parsed, data), reportedValue, "");
                return;
            }
            if ("set_care_mode".equals(cmd) && isOkResponse(parsed)) {
                JSONObject data = parsed.optJSONObject("data");
                String reportedValue = data != null
                        ? data.optString("value", parsed.optString("value", ""))
                        : parsed.optString("value", "");
                confirmCareMode(resolveResponseZone(parsed, data), reportedValue,
                        replyCommand == null ? "" : replyCommand.careModeValue);
                return;
            }
            if ("get_status_light".equals(cmd) && parsed.has("color")) {
                if (!isResponseForCurrentZone(parsed)) {
                    return;
                }
                String normalized = normalizeStatusLightColor(parsed.optString("color", ""));
                if (!TextUtils.isEmpty(normalized)) {
                    statusLightColor = normalized;
                }
                return;
            }
            if (("status_light".equals(cmd) || "set_status_light".equals(cmd))
                    && isOkResponse(parsed)) {
                // 主机主动回报监护等级：这是"机器 → App"方向唯一可靠的入口，必须接住。
                if (!isResponseForCurrentZone(parsed)) {
                    return;
                }
                String normalized = normalizeStatusLightColor(parsed.optString("color", ""));
                if (TextUtils.isEmpty(normalized)) {
                    // 回包里没带颜色：主动回读一次，确认主机当前真实的监护等级。
                    requestStatusLightColor();
                    return;
                }
                statusLightColor = normalized;
                return;
            }
            if ("get_main_version".equals(cmd)) {
                mainVersion = validVersion(parsed.optString("value", "")) ? parsed.optString("value", "") : mainVersion;
                return;
            }
            if ("get_ctrl_version".equals(cmd)) {
                ctrlVersion = validVersion(parsed.optString("value", "")) ? parsed.optString("value", "") : ctrlVersion;
                return;
            }
            if ("get_bt_ver".equals(cmd)) {
                btVersion = validVersion(parsed.optString("value", "")) ? parsed.optString("value", "") : btVersion;
                return;
            }
            if ("get_run_time".equals(cmd) && parsed.has("value")) {
                updateRunTimeMinutes(parsed.optInt("value"));
                return;
            }
            if ("set_run_time".equals(cmd) && "ok".equals(parsed.optString("ret", "")) && parsed.has("value")
                    && treatmentInfoComplete) {
                treatmentMinutes = parsed.optInt("value");
                runTimeBaseAt = System.currentTimeMillis();
                deviceSessionStartedAt = 0L;
                syncCurrentZoneTreatmentClock();
                persistTreatmentClock();
            }
            // 主机对开关类指令的回执：不同固件的回执结构不完全一样，这里统一兜住。
            if (cmd.startsWith("set_")) {
                applyControlAck(cmd, parsed);
            }
        } catch (JSONException exception) {
            // 主机报文解析失败要留痕：原文和原因都记下来，
            // 否则"没收到状态"和"收到但没解析出来"分不清。
            diagParseFailureCount++;
            noteDiagDrop("parse_failure");
            noteDiagFrame(message);
            addLog("[主机] 报文解析失败，已丢弃: " + message
                    + " (" + exception.getMessage() + ")");
        }
    }

    /** 应用一帧总状态；环境值和开关共用同一数据来源，但按舱更新环境缓存。 */
    private static String readCareModeValue(JSONObject source) {
        if (source == null) {
            return "";
        }
        Object raw = source.opt("care_mode");
        if (raw == null) {
            raw = source.opt("value");
        }
        if (raw == null) {
            raw = source.opt("mode");
        }
        if (raw instanceof JSONObject) {
            JSONObject nested = (JSONObject) raw;
            return nested.optString("value", nested.optString("care_mode", ""));
        }
        return raw == null ? "" : String.valueOf(raw);
    }

    private void applyAllStatus(JSONObject parsed, JSONObject data, String rawFrame) {
        if (parsed == null || data == null) {
            return;
        }
        String responseZone = resolveResponseZone(parsed, data);
        ZoneEnvironmentState environment = environmentForZone(responseZone);
        long receivedAtMs = System.currentTimeMillis();
        environment.statusReplyAtMs = receivedAtMs;
        environment.lastStatusFrame = rawFrame;
        boolean belongsToCurrentZone = responseZone.equals(currentZone);
        HostEnvironmentParser.Reading reading = HostEnvironmentParser.read(data);
        if (reading.temperature != null) {
            environment.temperature = reading.temperature;
        }
        if (reading.oxygen != null) {
            environment.oxygen = reading.oxygen;
        }
        if (reading.humidity != null) {
            environment.humidity = reading.humidity;
        }
        if (reading.co2 != null) {
            environment.co2 = reading.co2;
        }
        if (reading.hasAnyValue()) {
            environment.updatedAtMs = receivedAtMs;
            diagEnvironmentAppliedCount++;
        }
        if (hasValidHostState(data, reading)) {
            // 后台舱的有效回包同样恢复该舱数据健康，不能按当前页面舱位丢弃计时证据。
            noteValidHostState(responseZone, receivedAtMs);
        }
        String reportedCareMode = readCareModeValue(data);
        if (isSupportedCareMode(reportedCareMode)) {
            // 缺字段表示“不知道”，只有主机真实报出的四种护理模式之一才能更新状态。
            confirmCareMode(responseZone, reportedCareMode, "");
        }
        Boolean tempOn = readSwitchFlag(reading.temperatureObject,
                "status", "enable", "enabled", "state", "on");
        Boolean o2On = readSwitchFlag(reading.oxygenObject,
                "status", "enable", "enabled", "state", "on");

        // 切舱前发出的旧请求回来时不能覆盖当前舱的开关状态。
        if (!belongsToCurrentZone) {
            diagZoneRejectedCount++;
            noteDiagDrop("zone_mismatch:" + responseZone);
            return;
        }
        noteStatusFrameArrived();
        if (tempOn != null) {
            tempEnabled = tempOn;
        }
        if (o2On == null) {
            o2On = readSwitchFlag(data, "o2_enable");
        }
        if (o2On != null) {
            applySwitchState(SWITCH_O2, o2On);
        }
        String statusLight = readStatusLightText(data);
        if (!TextUtils.isEmpty(statusLight)) {
            String normalized = normalizeStatusLightColor(statusLight);
            if (!TextUtils.isEmpty(normalized)) {
                statusLightColor = normalized;
            }
        }
        if (data.has("run_time")) {
            updateRunTimeMinutes(data.optInt("run_time", 0));
        }
        updateTimedSwitch(data, "uv", "uv", "uv_enable");
        updateTimedSwitch(data, "blue", "blue_light", "blue_enable");
        updateTimedSwitch(data, "red", "red_light", "red_enable");
        updateTimedSwitch(data, "nebulizer", "nebulizer", "nebulizer_enable");
        updateTimedSwitch(data, "anion", "anion", "anion_enable");
        applySwitchState(SWITCH_INNER_CYCLE, readSwitchFlag(data, "inner_cycle", "inner_cycle_enable"));
        applySwitchState(SWITCH_OUTER_CYCLE, readSwitchFlag(data, "outer_cycle", "outer_cycle_enable"));
        applySwitchState(SWITCH_COLD_LIGHT, readSwitchFlag(data, "cold_light", "cold_light_enable"));
        applySwitchState(SWITCH_WARM_LIGHT, readSwitchFlag(data, "warm_light", "warm_light_enable"));
        Boolean co2On = readSwitchFlag(data, "co2_enable");
        if (co2On == null) {
            co2On = readSwitchFlag(reading.co2Object, "status", "enable", "enabled", "state");
        }
        applySwitchState(SWITCH_CO2, co2On);
    }

    /** 只有分项查询回包里的 value、current 或同名字段才能作为实测值。 */
    private boolean applyEnvironmentValueResponse(String cmd, JSONObject parsed) {
        // setter 的 value 通常是刚下发的目标值，ret:ok 也不是环境测量或有效状态回读。
        boolean temperature = "get_temp".equals(cmd);
        boolean oxygen = "get_o2".equals(cmd);
        boolean humidity = "get_humidity".equals(cmd);
        boolean co2 = "get_co2".equals(cmd);
        if (!temperature && !oxygen && !humidity && !co2) {
            return false;
        }
        JSONObject data = parsed.optJSONObject("data");
        JSONObject source = data != null ? data : parsed;
        HostEnvironmentParser.Reading reading = HostEnvironmentParser.read(source);
        String responseZone = resolveResponseZone(parsed, data);
        ZoneEnvironmentState environment = environmentForZone(responseZone);
        boolean updated = false;
        if (temperature) {
            Float value = reading.temperature;
            if (value == null) {
                value = valueMaybeDiv10Any(source, null, "value", "current");
            }
            if (value != null) {
                environment.temperature = value;
                updated = true;
            }
        } else if (oxygen) {
            Float value = reading.oxygen;
            if (value == null) {
                value = valueMaybeDiv10Any(source, null, "value", "current");
            }
            if (value != null) {
                environment.oxygen = value;
                updated = true;
            }
        } else if (humidity) {
            Float value = reading.humidity;
            if (value == null) {
                value = valueMaybeDiv10Any(source, null, "value", "current");
            }
            if (value != null) {
                environment.humidity = value;
                updated = true;
            }
        } else {
            Integer value = reading.co2;
            if (value == null) {
                value = readIntAny(source, "value", "current");
            }
            if (value != null) {
                environment.co2 = value;
                updated = true;
            }
        }
        if (updated) {
            long receivedAtMs = System.currentTimeMillis();
            environment.updatedAtMs = receivedAtMs;
            diagEnvironmentAppliedCount++;
            noteValidHostState(responseZone, receivedAtMs);
        }
        return updated;
    }

    /** 裸 ok、版本回声或任意 Notify 都不算有效状态；必须有可解析的环境/开关/运行内容。 */
    private boolean hasValidHostState(JSONObject data, HostEnvironmentParser.Reading reading) {
        if (data == null || reading == null) {
            return false;
        }
        if (reading.hasAnyValue()) {
            return true;
        }
        if (readSwitchFlag(reading.temperatureObject, "status", "enable", "enabled", "state", "on") != null
                || readSwitchFlag(reading.oxygenObject, "status", "enable", "enabled", "state", "on") != null
                || readSwitchFlag(reading.co2Object, "status", "enable", "enabled", "state", "on") != null) {
            return true;
        }
        Object runTime = data.opt("run_time");
        if (runTime instanceof Number) {
            double value = ((Number) runTime).doubleValue();
            if (!Double.isNaN(value) && !Double.isInfinite(value) && value >= 0d) {
                return true;
            }
        } else if (runTime instanceof String) {
            try {
                double value = Double.parseDouble(((String) runTime).trim());
                if (!Double.isNaN(value) && !Double.isInfinite(value) && value >= 0d) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // 继续检查其他状态字段。
            }
        }
        for (String field : HOST_STATE_FIELDS) {
            if (!"run_time".equals(field) && readSwitchFlag(data, field) != null) {
                return true;
            }
        }
        return false;
    }

    /** 只有有效状态能刷新数据健康并结束该舱的未回答查询计时。 */
    private void noteValidHostState(String zone, long receivedAtMs) {
        diagValidHostStateCount++;
        ZoneEnvironmentState environment = environmentForZone(zone);
        environment.validStateAtMs = receivedAtMs;
        environment.awaitingStatusReplySinceAtMs = 0L;
        lastValidHostStateAtMs = receivedAtMs;
        updateDataPathHealth(receivedAtMs);
    }

    /** 数据通路超时与 GATT 物理连接分开：不断连、不清缓存，也不自动重发控制。 */
    private void updateDataPathHealth(long now) {
        if (!connected || !protocolReady) {
            return;
        }
        String previousState = dataPathState;
        String previousError = dataPathError;
        String previousConnectionText = connectionStateText;
        String error = "";
        // dataPathState/ready 直接控制当前页面的四路数值灰显，只能由当前舱
        // 自己的未回答查询触发。背景舱的查询/回包时间仍保留在 zones 诊断中，
        // 但不能把正在持续收到新鲜实测值的当前舱一起拖成 timeout。
        ZoneEnvironmentState visibleEnvironment = environmentForZone(currentZone);
        String timeoutZone = hasUnansweredStatusTimeout(visibleEnvironment, now) ? currentZone : "";
        if (!TextUtils.isEmpty(timeoutZone)) {
            error = ("left".equals(timeoutZone) ? "左舱" : "右舱")
                    + "查询后超过 " + (STATUS_REPLY_TIMEOUT_MS / 1000)
                    + " 秒未收到有效状态回包";
        }
        long freshnessStart = lastValidHostStateAtMs > 0L
                ? lastValidHostStateAtMs : dataPathWatchStartedAtMs;
        if (freshnessStart > 0L && now - freshnessStart >= STATUS_FRESHNESS_TIMEOUT_MS) {
            String globalError = lastValidHostStateAtMs > 0L
                    ? "已超过 " + (STATUS_FRESHNESS_TIMEOUT_MS / 1000) + " 秒未收到任何有效主机状态"
                    : "协议就绪后 " + (STATUS_FRESHNESS_TIMEOUT_MS / 1000) + " 秒内未收到任何有效主机状态";
            error = TextUtils.isEmpty(error) ? globalError : error + "；" + globalError;
        }
        if (!TextUtils.isEmpty(error)) {
            dataPathState = "timeout";
            dataPathError = error + "；界面保留最后真实缓存，主机执行结果未确认";
            connectionStateText = "已连接 · 数据回包超时";
        } else if (lastValidHostStateAtMs > 0L) {
            dataPathState = "ok";
            dataPathError = "";
            connectionStateText = "已连接";
        } else {
            dataPathState = "waiting";
            dataPathError = "";
            connectionStateText = "已连接 · 等待状态回包";
        }
        if (!TextUtils.equals(previousState, dataPathState)) {
            if ("timeout".equals(dataPathState)) {
                addLog("[数据通路] " + dataPathError);
            } else if ("ok".equals(dataPathState)) {
                addLog("timeout".equals(previousState)
                        ? "[数据通路] 收到有效状态，数据回包已恢复"
                        : "[数据通路] 已收到首个有效主机状态");
            }
        } else if (!TextUtils.equals(previousError, dataPathError) && !TextUtils.isEmpty(dataPathError)) {
            addLog("[数据通路] " + dataPathError);
        }
        if (!TextUtils.equals(previousState, dataPathState)
                || !TextUtils.equals(previousError, dataPathError)
                || !TextUtils.equals(previousConnectionText, connectionStateText)) {
            emit();
        }
    }

    private boolean hasUnansweredStatusTimeout(ZoneEnvironmentState environment, long now) {
        return environment.awaitingStatusReplySinceAtMs > 0L
                && now - environment.awaitingStatusReplySinceAtMs >= STATUS_REPLY_TIMEOUT_MS;
    }

    /** 控制回包超时只记录并提示，不触发物理重发。 */
    private void updateControlReplyTimeout() {
        if (!"timeout".equals(getControlReplyState())
                || controlTimeoutLoggedForSentAtMs == lastControlSentAtMs) {
            return;
        }
        controlTimeoutLoggedForSentAtMs = lastControlSentAtMs;
        if ("set_care_mode".equals(lastControlSentCmd)) {
            // 超时只结束本次等待，不确认成功，也不自动重发；新值不同则不会被旧超时清掉。
            clearPendingCareMode(zoneOf(lastControlSent), careModeValueOfPayload(lastControlSent));
        }
        String timedOutKey = controlKeyForCommand(lastControlSentCmd);
        if (!TextUtils.isEmpty(timedOutKey)) {
            noteControlFinalState(timedOutKey, "unconfirmed");
        }
        addLog("[控制] " + getControlReplyError());
        emit();
    }

    /** 只接受属于最后一次实际控制写入的等待项；旧同名回包不能确认新一次操作。 */
    private void noteControlReply(AwaitingCommand replyCommand, String rawFrame) {
        if (replyCommand == null || !isUserControlCommand(replyCommand.cmd)
                || !replyCommand.cmd.equals(lastControlSentCmd)
                || replyCommand.sentAtMs < lastControlSentAtMs) {
            return;
        }
        boolean wasTimeout = "timeout".equals(getControlReplyState());
        lastControlReply = rawFrame == null ? "" : rawFrame;
        lastControlReplyAtMs = System.currentTimeMillis();
        lastControlReplyCmd = replyCommand.cmd;
        controlTimeoutLoggedForSentAtMs = 0L;
        String acknowledgedKey = controlKeyForCommand(replyCommand.cmd);
        if (!TextUtils.isEmpty(acknowledgedKey)) {
            noteControlFinalState(acknowledgedKey, "acknowledged");
        }
        addLog("[控制] 收到 " + replyCommand.cmd + (wasTimeout ? " 的迟到匹配回包" : " 的匹配回包")
                + "；仅表示主机有回应，物理效果仍以状态回读为准");
    }

    /**
     * 刷新带倒计时的开关。
     *
     * <p>固件可能回 {"status":"on","time_remaining":30}，也可能直接回 "on"，
     * 字段名还可能不一样（red_light / red_enable），这里都要认。</p>
     */
    private void updateTimedSwitch(JSONObject data, String type, String... keys) {
        if (data == null) {
            return;
        }
        Boolean enabled = null;
        JSONObject holder = null;
        for (String key : keys) {
            if (TextUtils.isEmpty(key) || !data.has(key)) {
                continue;
            }
            Object raw = data.opt(key);
            if (raw instanceof JSONObject && holder == null) {
                holder = (JSONObject) raw;
            }
            if (enabled == null) {
                enabled = readSwitchValue(raw);
            }
        }
        if (enabled == null) {
            return;
        }
        applySwitchState(switchKeyForTimedType(type), enabled);
        if (holder != null) {
            Integer time = readRemainMinutes(holder, "time_remaining", "remain", "remaining", "time", "minute", "minutes");
            if (time != null) {
                applyTimedMinutes(type, time);
            }
        }
    }

    /** 定时开关对应的本地开关标识。 */
    private String switchKeyForTimedType(String type) {
        if ("uv".equals(type)) {
            return SWITCH_UV;
        }
        if ("blue".equals(type)) {
            return SWITCH_BLUE;
        }
        if ("red".equals(type)) {
            return SWITCH_RED;
        }
        if ("nebulizer".equals(type)) {
            return SWITCH_NEBULIZER;
        }
        if ("anion".equals(type)) {
            return SWITCH_ANION;
        }
        return "";
    }

    /**
     * 把主机的剩余分钟写回本地。
     *
     * <p>主机回报的是"还剩多少分钟"，不能写进用户配置的时长里：
     * 否则每轮轮询都会把配置值改小，下次开启时又按被改小的时长下发。</p>
     */
    private void applyTimedMinutes(String type, int minutes) {
        String key = switchKeyForTimedType(type);
        if (!TextUtils.isEmpty(key)) {
            switchRemainingMinutes.put(timedRemainingKey(key), minutes);
        }
    }

    /** 剩余分钟按舱隔离，不能让另一舱的旧倒计时覆盖本舱配置。 */
    private String timedRemainingKey(String key) {
        return currentZone + "|" + key;
    }

    /** 新时长入队后，对应功能的本舱旧剩余值立即失效。 */
    private void clearTimedRemaining(int index) {
        String key = switchKeyForCommand(controlCommandFor(index));
        if (!TextUtils.isEmpty(key)) {
            switchRemainingMinutes.remove(timedRemainingKey(key));
        }
    }

    /** 主机回报的剩余分钟；没有回读过就退回用户配置的时长。 */
    private Integer remainingMinutes(String key, Integer fallback) {
        Integer remaining = switchRemainingMinutes.get(timedRemainingKey(key));
        return remaining == null ? fallback : remaining;
    }

    /**
     * 下发开关后本地先回显，并登记 pending。
     *
     * <p>不回显的话界面要等主机回读才变，用户会以为没点上，
     * 于是反复点同一个方向，主机就一直收到同一条指令。</p>
     */
    private PendingSwitch applyLocalSwitch(String key, boolean enabled) {
        setSwitchState(key, enabled);
        return markSwitchPending(key, enabled);
    }

    /** 登记一次刚下发的开关值，后续只通过状态回读确认。 */
    private PendingSwitch markSwitchPending(String key, boolean value) {
        if (TextUtils.isEmpty(key)) {
            return null;
        }
        clearSwitchPending(key);
        PendingSwitch pending = new PendingSwitch(value, System.currentTimeMillis(), switchCommandFor(key), key);
        pendingSwitches.put(pendingKeyOf(key), pending);
        noteControlFinalState(key, "pending");
        scheduleSwitchConfirm(key, value);
        return pending;
    }

    /**
     * 等主机确认开关状态：没确认就只读回读，最终以主机状态或明确提示收尾。
     *
     * <p>只有回读才知道真实状态；回执不明不能成为再次执行物理开关的理由。</p>
     *
     * <p>回读的轮次按"收到几帧完整有效的状态"算，不按"发了几次查询"算：
     * 最后一轮回包没回来之前，待确认的保护一直有效，
     * 主机后补的一帧旧快照就不会把用户刚点出来的开关弹回去。</p>
     */
    private void scheduleSwitchConfirm(final String key, final boolean value) {
        final PendingSwitch pending = pendingSwitches.get(pendingKeyOf(key));
        if (pending == null) {
            return;
        }
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                PendingSwitch current = pendingSwitches.get(pendingKeyOf(key));
                if (current == null || current.value != value) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (current.acknowledged) {
                    // 主机已经回执"收到指令"，只是还没报出真实状态。
                    // 这时候重复下发同一条指令没有意义：指令是幂等的，
                    // 重复下发只会把发送队列堵住，还会让主机多鸣一声。
                    // 只能主动回读状态，等主机把真实状态报出来。
                    long sinceAck = now - current.acknowledgedAtMs;
                    boolean readTimedOut = current.readInFlight
                            && (now - current.readRequestedAtMs) >= SWITCH_READ_REPLY_TIMEOUT_MS;
                    if (current.statusFrames >= SWITCH_MAX_VERIFY_ROUND
                            && sinceAck >= SWITCH_ACK_PROTECT_MS
                            && current.lastHostValue != null
                            && current.lastHostValue != current.value
                            && current.lastHostValueAtMs >= current.acknowledgedAtMs + SWITCH_ACK_PROTECT_MS) {
                        // 必须在保护期后仍收到矛盾的新回读，才能以主机值回滚界面；
                        // 保护期前积压的旧快照不能拖过超时后再冒充新证据。
                        giveUpSwitchContradicted(key, current);
                        return;
                    }
                    if (current.verifyRounds >= SWITCH_MAX_VERIFY_ROUND && readTimedOut) {
                        // 问了三次，一次完整状态都没等到：这次下发没落实，
                        // 但也没有证据说主机是另一个状态，
                        // 界面保持用户点的那一下，只提示一句。
                        giveUpSwitchUnconfirmed(key, current);
                        return;
                    }
                    if (!current.readInFlight || readTimedOut) {
                        // 上一轮回读没人管了：再问一次。
                        addLog("[开关] " + key + " 主机已收下指令但还没报状态，第 "
                                + (current.statusFrames + 1) + " 次主动回读");
                        requestSwitchReadback(current);
                    } else {
                        addLog("[开关] " + key + " 等待主机回读，已收到 "
                                + current.statusFrames + " 帧状态");
                    }
                    scheduleSwitchConfirm(key, value);
                    return;
                }
                // 还没收到主机的回执。
                // 这里不能直接重发：主机每收到一条就真的执行一次、还会鸣一声，
                // "没收到回执"并不等于"主机没执行"。
                // 之前就是在这里盲发两次，用户点一下红外灯主机就"滴、滴、滴"响三声，
                // 护理模式一次开多个功能时更是连着响个不停。
                // 现在改成：先用只读回读问清楚，读回来的状态就是答案。
                if (current.contradictFrames >= SWITCH_CONTRADICT_FRAMES
                        && current.lastHostValue != null
                        && current.lastHostValue != current.value
                        && now - current.sentAtMs >= PENDING_SWITCH_TIMEOUT_MS
                        && current.lastHostValueAtMs >= current.sentAtMs + PENDING_SWITCH_TIMEOUT_MS) {
                    // 只有保护期后的矛盾新回读才可回滚界面，且绝不重发物理命令。
                    giveUpSwitchContradicted(key, current);
                    return;
                }
                // 还没问出结果：只读回读一次，用状态判断主机做没做，
                // 不往下发指令（下发一次主机就要执行一次、鸣一声）。
                boolean waitingReply = current.readInFlight
                        && (now - current.readRequestedAtMs) < SWITCH_READ_REPLY_TIMEOUT_MS;
                if (waitingReply) {
                    // 还在等上一轮回读的回包，等下一轮再说。
                    addLog("[开关] " + key + " 没收到主机回执，等待第 "
                            + current.readbackRounds + " 轮回读结果");
                } else if (current.readbackRounds >= SWITCH_MAX_VERIFY_ROUND) {
                    // 问了几轮都没问出主机的真实状态：主机多半没响应。
                    // 界面保持用户点的那一下，但必须明确提示，不能闷着。
                    giveUpSwitch(key, current);
                    return;
                } else {
                    requestSwitchReadback(current);
                    addLog("[开关] " + key + " 没收到主机回执，第 " + current.readbackRounds
                            + " 轮主动回读状态（不下发指令）");
                }
                scheduleSwitchConfirm(key, value);
            }
        };
        pending.confirmRunnable = runnable;
        handler.postDelayed(runnable, SWITCH_CONFIRM_TIMEOUT_MS);
    }

    /**
     * 收到一帧完整有效的 get_all_status：给当前舱待确认的开关记一次。
     *
     * <p>验证轮次按"收到的状态帧"算：查询发出去只是问了一句，
     * 收到一帧完整状态才算真的问到了答案。
     * 只有真的答了，这一轮才数得上。</p>
     */
    private void noteStatusFrameArrived() {
        String suffix = ":" + currentZone;
        for (String pendingKey : new ArrayList<>(pendingSwitches.keySet())) {
            if (!pendingKey.endsWith(suffix)) {
                continue;
            }
            PendingSwitch pending = pendingSwitches.get(pendingKey);
            if (pending == null) {
                continue;
            }
            pending.statusFrames++;
            // 问到了：这轮回读不再"在途"。
            pending.readInFlight = false;
        }
    }

    /**
     * 主动回读一次主机状态（主线程）。
     *
     * <p>这版主机没有单开关回读指令，真实状态只能从 get_all_status 里读。
     * 每问一次都记一笔：等不到回包时才知道该再问一次还是该放弃。</p>
     */
    private void requestSwitchReadback(PendingSwitch pending) {
        if (pending == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (pending.readInFlight && now - pending.readRequestedAtMs < SWITCH_READ_REPLY_TIMEOUT_MS) {
            // 刚问过，还在等回包，不重复占用主机的接收缓冲。
            return;
        }
        if (!requestSwitchState(pending.key, true)) {
            // 另一舱总表仍在占用唯一的安全在途窗口，本次实际上没有发出回读。
            // 不能写成 readInFlight=true，否则随后到达的控制 ack 会误以为已经问过，
            // 白白多等一个超时周期。保留 pending，由确认定时器在窗口释放后补问。
            pending.readInFlight = false;
            pending.readRequestedAtMs = 0L;
            addLog("[开关] " + pending.key + " 状态总表正被另一舱占用，等待释放后补发回读");
            return;
        }
        pending.verifyRounds++;
        pending.readbackRounds++;
        pending.readInFlight = true;
        pending.readRequestedAtMs = now;
    }

    /**
     * 主机连着几帧都报的是另一个值：以主机为准。
     *
     * <p>主机说的才算数：界面必须和主机一致，
     * 否则操作者照着平板上的界面判断，而仪器其实没开。</p>
     */
    private void giveUpSwitchContradicted(String key, PendingSwitch pending) {
        noteControlFinalState(key, "contradicted");
        Boolean hostValue = pending.lastHostValue;
        addLog("[开关] " + key + " 主机连续 " + pending.statusFrames
                + " 帧报的不是目标状态，界面以主机为准");
        setError("开关 " + key + " 状态未确认，请检查主机");
        clearSwitchPending(key);
        if (hostValue != null) {
            hostSwitchStates.put(key, hostValue);
            confirmedSwitches.put(pendingKeyOf(key),
                    new ConfirmedSwitch(hostValue, System.currentTimeMillis()));
            setSwitchState(key, hostValue);
        }
        requestSwitchState(key, true);
    }

    /**
     * 一直没问到主机的真实状态：保持用户点的那一下，但要明确提示。
     *
     * <p>这里不能自己把开关改成"关"：没有证据就没有结论，
     * 主机那边到底开没开，只有它自己知道。</p>
     */
    private void giveUpSwitchUnconfirmed(String key, PendingSwitch pending) {
        noteControlFinalState(key, "unconfirmed");
        addLog("[开关] " + key + " 主机没有回读出状态，界面保持用户点的那一下");
        setError("开关 " + key + " 状态未确认，请检查主机");
        clearSwitchPending(key);
        requestSwitchState(key, true);
    }

    /**
     * 这一轮下发结束：界面到底信主机，还是信用户点的那一下。
     *
     * <p>有没有证据说主机是另一个状态，决定界面该信谁：
     * 主机明确报过另一个值就以主机为准（仪器才是真话），
     * 没有证据就保持用户点的那一下；
     * 但两种情况都必须提示用户，不能让界面一直显示一个主机根本没有的状态。</p>
     */
    private void giveUpSwitch(String key, PendingSwitch pending) {
        if (pending != null && pending.contradictFrames > 0
                && pending.lastHostValue != null
                && pending.lastHostValueAtMs >= pending.sentAtMs + PENDING_SWITCH_TIMEOUT_MS) {
            giveUpSwitchContradicted(key, pending);
        } else {
            // 只有保护期前的旧矛盾值，不算超时后的新证据；保持用户操作并提示未确认。
            giveUpSwitchUnconfirmed(key, pending);
        }
    }

    /** 这次下发已经结束（主机确认了，或回读验证结束）。 */
    private void clearSwitchPending(String key) {
        removePending(pendingKeyOf(key));
    }

    /** 按待确认键删除一次下发记录。 */
    private void removePending(String pendingKey) {
        PendingSwitch pending = pendingSwitches.remove(pendingKey);
        if (pending != null && pending.confirmRunnable != null) {
            handler.removeCallbacks(pending.confirmRunnable);
        }
    }

    /** 链路断了：所有开关的下发状态一起作废，别再往主机上补发。 */
    private void clearAllSwitchPending() {
        for (String pendingKey : new ArrayList<>(pendingSwitches.keySet())) {
            removePending(pendingKey);
        }
    }

    /** 开关标识 → 主机指令名：建立待确认记录时使用。 */
    private String switchCommandFor(String key) {
        String cmd = SWITCH_COMMANDS.get(key);
        return cmd == null ? "" : cmd;
    }

    /**
     * 待确认开关的键：开关 + 舱位。
     *
     * <p>左右舱的指令名完全一样，待确认记录必须分舱存放，
     * 否则左舱的下发会把右舱的回读挡掉，右舱的开关状态永远不更新。</p>
     */
    private String pendingKeyOf(String key) {
        return key + ":" + currentZone;
    }

    /**
     * 发送队列里堆积的指令是不是已经太多。
     *
     * <p>主机的接收缓冲有限，状态查询必须给用户的开关指令让路。</p>
     */
    private boolean isWriteQueueBusy() {
        return writeQueue.size() + (currentWrite == null ? 0 : 1) >= WRITE_QUEUE_LIMIT;
    }

    /**
     * 主动确认一个开关在主机上的真实状态。
     *
     * <p>这版主机没有单开关回读指令：get_cold_light 之类一律回
     * {"cmd":"error"}。再去试只会往队列里塞一条注定失败的指令，
     * 主机那句不带指令名的 error 还会把后面指令的归属带偏，
     * 用户刚点下的开关就再也关不掉了。开关的真实状态只能从
     * get_all_status 里读。</p>
     *
     * @param urgent true 表示这是用户刚操作完之后的确认回读，插到队列最前面发；
     *               false 表示常规轮询回读，排到队尾。
     */
    private boolean requestSwitchState(String key, boolean urgent) {
        if (!connected || !protocolReady || TextUtils.isEmpty(key)) {
            return false;
        }
        // 当前舱已经有一条总表在排队、写入或等待回包时，它的回答就会进入
        // noteStatusFrameArrived，可直接作为本次控制回读，不必也不能再排一条。
        if (hasCabinStatusQueryInFlight(currentZone)) {
            return true;
        }
        return requestCabinStatusForZone(currentZone, urgent);
    }

    /**
     * 回读当前舱的全部状态。
     *
     * <p>get_all_status 一次就能带回所有开关的真实状态，比一条条去试
     * 单开关回读指令快得多，也不会在队列里留下失败的指令。</p>
     */
    private void requestCabinStatus(boolean urgent) {
        requestCabinStatusForZone(currentZone, urgent);
    }

    /**
     * 串行请求一个舱的总表；返回 false 表示已有查询在途或命令暂不可发。
     * 只有成功入队才轮换下一舱，失败不会靠重复入队制造流量。
     */
    private boolean requestCabinStatusForZone(String zone, boolean urgent) {
        String normalized = normalizeZone(zone);
        if (!connected || !protocolReady || TextUtils.isEmpty(normalized)
                || unsupportedCommands.contains("get_all_status")
                || hasCabinStatusQueryInFlight()) {
            return false;
        }
        boolean queued = enqueueCommand("{\"cmd\":\"get_all_status\",\"zone\":\""
                + normalized + "\"}", urgent);
        if (queued) {
            nextCabinPollZone = "left".equals(normalized) ? "right" : "left";
        }
        return queued;
    }

    /** 总表查询从排队到等待回包都只允许一条，保证省略 zone 的回包仍可唯一归属。 */
    private boolean hasCabinStatusQueryInFlight() {
        return hasCabinStatusQueryInFlight("");
    }

    /** 可选按舱检查已有总表；用于把已在途的当前舱回包复用为控制回读。 */
    private boolean hasCabinStatusQueryInFlight(String zone) {
        String expectedZone = normalizeZone(zone);
        if (currentWrite != null && "get_all_status".equals(currentWrite.cmd)
                && (TextUtils.isEmpty(expectedZone)
                || expectedZone.equals(normalizeZone(zoneOf(currentWrite.payload))))) {
            return true;
        }
        for (QueuedCommand queued : writeQueue) {
            if ("get_all_status".equals(queued.cmd)
                    && (TextUtils.isEmpty(expectedZone)
                    || expectedZone.equals(normalizeZone(zoneOf(queued.payload))))) {
                return true;
            }
        }
        pruneStaleAwaitingCommands();
        long now = System.currentTimeMillis();
        for (AwaitingCommand awaiting : awaitingCommands) {
            if ("get_all_status".equals(awaiting.cmd)
                    && awaiting.generation == connectionGeneration
                    && now - awaiting.sentAtMs <= REPLY_WAIT_MAX_MS
                    && (TextUtils.isEmpty(expectedZone) || expectedZone.equals(awaiting.zone))) {
                return true;
            }
        }
        return false;
    }

    /** 回包里的舱位：固件不一定每条都带，没带时返回空串。 */
    private String replyZoneOf(JSONObject parsed) {
        if (parsed == null) {
            return "";
        }
        String zone = normalizeZone(parsed.optString("zone", ""));
        if (TextUtils.isEmpty(zone)) {
            JSONObject data = parsed.optJSONObject("data");
            if (data != null) {
                zone = normalizeZone(data.optString("zone", ""));
            }
        }
        return zone;
    }

    /** 这条指令是不是一个开关控制指令。 */
    private boolean isSwitchCommand(String cmd) {
        return !TextUtils.isEmpty(cmd) && SWITCH_COMMANDS.containsValue(cmd);
    }

    /**
     * 处理单个开关的回读回包。
     *
     * <p>不同固件的字段写法不统一：有的直接回 {"status":"on"}，
     * 有的包在 data 里，这里都要认。</p>
     */
    private void applySwitchReaderResponse(String cmd, JSONObject parsed) {
        if (parsed == null || TextUtils.isEmpty(cmd)) {
            return;
        }
        String key = SWITCH_READER_KEYS.get(cmd);
        if (TextUtils.isEmpty(key) || !isResponseForCurrentZone(parsed)) {
            return;
        }
        JSONObject data = parsed.optJSONObject("data");
        JSONObject source = data != null ? data : parsed;
        Boolean value = readSwitchFlag(source, "enable", "status", "state", "on", "power");
        if (value == null) {
            value = readSwitchFlag(source, "switch", "run", "working");
        }
        if (value == null) {
            return;
        }
        applySwitchState(key, value);
    }

    /** 这条回包是不是某个开关的回读指令。 */
    private boolean isSwitchReaderCommand(String cmd) {
        return !TextUtils.isEmpty(cmd) && SWITCH_READER_KEYS.containsKey(cmd);
    }

    /**
     * 用主机的回读值刷新开关状态。
     *
     * <p>刚下发、主机还没确认时保留刚下发的值：主机回读有延迟，
     * 这时候读到的多半是旧值，拿旧值把用户刚点出来的状态弹回去，
     * 用户看到的现象就是“开关只能打开、关不掉”。</p>
     *
     * <p>主机确认之后的一小段时间内，同样不能拿矛盾的旧值覆盖：
     * 主机确认完还可能补一帧处理之前的快照，覆盖了界面上的开关，
     * 开关就会在开和关之间来回滑。</p>
     */
    private void applySwitchState(String key, Boolean value) {
        if (TextUtils.isEmpty(key) || value == null) {
            return;
        }
        String pendingKey = pendingKeyOf(key);
        PendingSwitch pending = pendingSwitches.get(pendingKey);
        if (pending != null) {
            if (pending.value == value) {
                // 主机确认了刚下发的值。
                noteControlFinalState(key, "confirmed");
                clearSwitchPending(key);
                confirmSwitchState(key, value);
                addLog("[开关] " + key + " 状态已确认：" + onOff(value));
                return;
            }
            // 主机报的是另一个值：记下值和到达时刻，确认流程只接受保护期后的新证据。
            long now = System.currentTimeMillis();
            pending.lastHostValue = value;
            pending.lastHostValueAtMs = now;
            // 这一帧也是"主机报的还是旧值"的证据；只记录并等待后续回读，
            // 不自动重发物理开关，避免主机已经执行时再次鸣响。
            pending.contradictFrames++;
            if (pending.acknowledged && now - pending.acknowledgedAtMs <= SWITCH_ACK_PROTECT_MS) {
                // 主机已经回执"收到指令"，但还没报出真实状态：
                // 现在读到的多半是它还没更新完的旧值，不能拿来把界面弹回去。
                // 界面上先保持用户刚点出来的状态，什么时候放弃由确认流程决定：
                // 回读到就确认，一直读不到就提示状态未确认。
                return;
            }
            if (!pending.acknowledged && now - pending.sentAtMs <= PENDING_SWITCH_TIMEOUT_MS) {
                // 主机还没处理完这条指令，读到的多半是旧值：
                // 不能覆盖用户刚点出来的状态，等主机推的那帧新值。
                return;
            }
            // 保护期已经过去：主机说的就是真话，以主机为准。
            addLog("[开关] " + key + " 主机报的是 " + onOff(value)
                    + "，与下发的 " + onOff(pending.value) + " 不一致，界面以主机为准");
            clearSwitchPending(key);
        } else {
            ConfirmedSwitch confirmed = confirmedSwitches.get(pendingKey);
            if (confirmed != null && confirmed.value != value
                    && System.currentTimeMillis() - confirmed.atMs <= SWITCH_CONFIRM_GRACE_MS) {
                // 主机确认之后可能还会回一帧处理之前的旧状态快照：
                // 拿它覆盖刚确认的值，界面上的开关就会在开和关之间来回跳。
                // 宽限期过后主动再读一次，让界面最终和主机一致。
                scheduleSwitchVerify(pendingKey, confirmed);
                return;
            }
        }
        hostSwitchStates.put(key, value);
        setSwitchState(key, value);
    }

    /**
     * 记住主机确认过的开关值和确认时刻。
     *
     * <p>确认之后的一小段时间内，矛盾的旧值要忽略掉：否则开关会在
     * 开和关之间来回滑，用户怎么点都点不准。</p>
     */
    private void confirmSwitchState(String key, boolean value) {
        noteControlFinalState(key, "confirmed");
        String pendingKey = pendingKeyOf(key);
        ConfirmedSwitch confirmed = confirmedSwitches.get(pendingKey);
        if (confirmed != null && confirmed.verifyRunnable != null) {
            handler.removeCallbacks(confirmed.verifyRunnable);
        }
        hostSwitchStates.put(key, value);
        confirmedSwitches.put(pendingKey, new ConfirmedSwitch(value, System.currentTimeMillis()));
        setSwitchState(key, value);
    }

    /**
     * 宽限期过后再读一次主机状态。
     *
     * <p>宽限期里被忽略掉的旧值不能就这么算了：到期后再读一次，
     * 界面最终一定和主机一致，用户不会卡在一个假的状态上。</p>
     */
    private void scheduleSwitchVerify(final String pendingKey, ConfirmedSwitch confirmed) {
        if (confirmed == null) {
            return;
        }
        if (confirmed.verifyRunnable != null) {
            handler.removeCallbacks(confirmed.verifyRunnable);
        }
        long delay = confirmed.atMs + SWITCH_CONFIRM_GRACE_MS - System.currentTimeMillis();
        if (delay < 0) {
            delay = 0;
        }
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (!confirmedSwitches.containsKey(pendingKey)) {
                    return;
                }
                requestCabinStatus(true);
            }
        };
        confirmed.verifyRunnable = runnable;
        handler.postDelayed(runnable, delay + 200L);
    }

    /** 把开关状态写回本地字段。 */
    private void setSwitchState(String key, boolean value) {
        if (!value) {
            // 开关关了就没有剩余时间了，别让旧倒计时留在界面上。
            switchRemainingMinutes.remove(timedRemainingKey(key));
        }
        if (SWITCH_CO2.equals(key)) {
            co2Enabled = value;
        } else if (SWITCH_COLD_LIGHT.equals(key)) {
            coldLight = value;
        } else if (SWITCH_WARM_LIGHT.equals(key)) {
            warmLight = value;
        } else if (SWITCH_RED.equals(key)) {
            redTherapy = value;
        } else if (SWITCH_BLUE.equals(key)) {
            blueTherapy = value;
        } else if (SWITCH_OUTER_CYCLE.equals(key)) {
            outerCycle = value;
        } else if (SWITCH_INNER_CYCLE.equals(key)) {
            innerCycle = value;
        } else if (SWITCH_NEBULIZER.equals(key)) {
            nebulizer = value;
        } else if (SWITCH_ANION.equals(key)) {
            anion = value;
        } else if (SWITCH_UV.equals(key)) {
            uv = value;
        } else if (SWITCH_O2.equals(key)) {
            o2Enabled = value;
        }
    }

    /** 某个开关指令对应的本地开关标识；不是开关指令时返回空串。 */
    private String switchKeyForCommand(String cmd) {
        if (cmd == null) {
            return "";
        }
        if ("set_co2_enable".equals(cmd)) {
            return SWITCH_CO2;
        }
        if ("set_cold_light".equals(cmd)) {
            return SWITCH_COLD_LIGHT;
        }
        if ("set_warm_light".equals(cmd)) {
            return SWITCH_WARM_LIGHT;
        }
        if ("set_red_enable".equals(cmd)) {
            return SWITCH_RED;
        }
        if ("set_blue_enable".equals(cmd)) {
            return SWITCH_BLUE;
        }
        if ("set_outer_cycle".equals(cmd)) {
            return SWITCH_OUTER_CYCLE;
        }
        if ("set_inner_cycle".equals(cmd)) {
            return SWITCH_INNER_CYCLE;
        }
        if ("set_nebulizer_enable".equals(cmd)) {
            return SWITCH_NEBULIZER;
        }
        if ("set_anion_enable".equals(cmd)) {
            return SWITCH_ANION;
        }
        if ("set_uv_enable".equals(cmd)) {
            return SWITCH_UV;
        }
        if ("set_o2_enable".equals(cmd)) {
            return SWITCH_O2;
        }
        return "";
    }

    /**
     * 处理主机对开关类指令的回执。
     *
     * <p>回执可能把开关值放在顶层，也可能放在 data 里，两种都要接住；
     * 主机明确回失败时先不改本地状态，等下一次状态回读纠正。</p>
     */
    private void applyControlAck(String cmd, JSONObject parsed) {
        String key = switchKeyForCommand(cmd);
        if (TextUtils.isEmpty(key) || parsed == null) {
            return;
        }
        if (!isResponseForCurrentZone(parsed)) {
            return;
        }
        if (!isOkResponse(parsed) && parsed.has("ret")) {
            return;
        }
        // 注意不能读 "value"：数值类回执（set_o2 / set_co2）里 value 是数字，
        // 拿它当开关值会把 35 之类的数值当成"打开"。
        Boolean value = readSwitchFlag(parsed, "enable", "status", "state");
        JSONObject data = parsed.optJSONObject("data");
        if (value == null && data != null) {
            value = readSwitchFlag(data, "enable", "status", "state");
        }
        if (value == null) {
            // 这版固件对开关类指令只回 {"cmd":"set_xxx","ret":"ok"}，不带开关值。
            // 这个 ok 只能说明"主机收到了这条指令"，不代表"状态已经生效"：
            // 主机的红光、蓝光、紫外都是这么回的。
            // 之前把 ok 当成状态确认，待确认记录被提前清掉，
            // 主机随后推来的旧状态快照（比 ok 晚一点）就把界面弹回"关"，
            // 仪器明明开着，平板却显示关着。
            // 正确做法：ok 只记账，状态以 get_all_status 为准。
            PendingSwitch pending = pendingSwitches.get(pendingKeyOf(key));
            if (pending == null) {
                addLog("[开关] 主机回执 " + cmd + " 已收到，但没有对应的下发记录");
                return;
            }
            if (!pending.acknowledged) {
                pending.acknowledged = true;
                pending.acknowledgedAtMs = System.currentTimeMillis();
                addLog("[开关] " + key + " 主机已收下指令（ret:ok），等待主机报出真实状态");
            }
            // 立刻回读一次真实状态：只有主机报出来的状态才算确认。
            // 蓝牙线程只负责把报文交过来，这里已经在主线程上了。
            requestSwitchReadback(pending);
            return;
        }
        PendingSwitch pending = pendingSwitches.get(pendingKeyOf(key));
        if (pending != null && !pending.acknowledged) {
            // 回执里带着状态值时同样要记账：主机既然回了状态，
            // 就说明这条指令它收到了。不记账的话待确认记录会一直挂着，
            // 确认流程会把它当成"没收到回执"，进而多下发一次指令。
            pending.acknowledged = true;
            pending.acknowledgedAtMs = System.currentTimeMillis();
            addLog("[开关] " + key + " 主机回执带状态（ret:ok），已记账");
        }
        if (pending != null && pending.value != value) {
            // 主机回的值和刚下发的不一样：这里只记一笔，
            // 真实状态以主机为准，由 applySwitchState 统一处理。
            addLog("[开关] 主机回执 " + cmd + " = " + onOff(value)
                    + "，与下发的 " + onOff(pending.value) + " 不一致");
        }
        applySwitchState(key, value);
    }

    /**
     * 主机回了一句没有指令名的 ok：按"已发出未回包"的队头归属。
     *
     * <p>有些固件的回执不写 cmd，只写 {"ret":"ok"}，按名字根本找不到对应的开关，
     * 待确认记录会一直挂着，最后只能提示"主机状态未确认"。
     * 主机是按收到的顺序回包的，队头就是这条回包对应的指令；
     * 只有队头正好是开关指令、并且确实有待确认记录时才认它，
     * 认错了会让某个开关凭空变成"已确认"。</p>
     */
    private void applyBareControlAck(JSONObject parsed, String rawFrame) {
        if (parsed == null || !isOkResponse(parsed)) {
            return;
        }
        if (parsed.has("data")) {
            // 带 data 的是状态帧，不是回执：状态帧有指令名，
            // 走不到这里来；这里再挡一道，免得把状态帧当成回执记账。
            return;
        }
        pruneStaleAwaitingCommands();
        AwaitingCommand head = awaitingCommands.peekFirst();
        if (head == null || TextUtils.isEmpty(head.cmd)) {
            return;
        }
        String key = switchKeyForCommand(head.cmd);
        // 主机按收到的顺序回包：这句没有指令名的 ok 就是回给队头的。
        // 先把队头摘掉，后面回包的归属才不会错位。
        awaitingCommands.pollFirst();
        noteControlReply(head, rawFrame);
        if ("set_care_mode".equals(head.cmd)) {
            String reportedValue = parsed.optString("value", "");
            confirmCareMode(head.zone, reportedValue, head.careModeValue);
            return;
        }
        if (TextUtils.isEmpty(key)) {
            return;
        }
        PendingSwitch pending = pendingSwitches.get(pendingKeyOf(key));
        if (pending == null) {
            return;
        }
        if (!pending.acknowledged) {
            pending.acknowledged = true;
            pending.acknowledgedAtMs = System.currentTimeMillis();
            addLog("[开关] " + key + " 主机回了不带指令名的回执，按队头 " + head.cmd + " 记账");
        }
        requestSwitchReadback(pending);
    }

    /**
     * 主机回 {"cmd":"error"}：它不认识这条指令。
     *
     * <p>这种回包不带指令名，只能靠“已发出未回包”那条队列对齐。
     * 主机按收到的顺序回包，所以队头就是被拒绝的那条。</p>
     *
     * <p>但一次拒绝也可能归因错了：中间丢一个回包，队头就偏了。
     * 所以控制指令要连续被拒两次才认定为主机不支持；开关指令永远
     * 不拉黑——拉错了那个开关，用户就再也关不掉它了。</p>
     */
    private void handleHostUnknownCommand() {
        pruneStaleAwaitingCommands();
        AwaitingCommand head = awaitingCommands.peekFirst();
        if (head == null) {
            addLog("[主机] 拒绝了一条指令，但队列里没有对应的等待项");
            return;
        }
        awaitingCommands.pollFirst();
        String rejected = head.cmd;
        if (rejected.startsWith("get_") && !"get_all_status".equals(rejected)) {
            // 冗余的状态查询不支持：停了它不影响别的数据。
            unsupportedCommands.add(rejected);
            addLog("[主机] 不支持指令 " + rejected + "，后续不再下发");
            return;
        }
        if ("get_all_status".equals(rejected)) {
            // 总表仍保留给开关状态使用，同时立即补一轮当前舱四项环境查询。
            addLog("[主机] 拒绝了一次状态查询 get_all_status，启用分项环境回退");
            if (TextUtils.isEmpty(head.zone) || currentZone.equals(head.zone)) {
                requestEnvironmentFallbackIfNeeded(true);
            }
            return;
        }
        if ("set_care_mode".equals(rejected)) {
            clearPendingCareMode(head.zone, head.careModeValue);
            setError("主机拒绝了护理模式指令，界面未标记开启");
            emit();
            return;
        }
        if (isSwitchCommand(rejected)) {
            // 开关指令不拉黑：主机那句 error 有可能是回错了人。
            // 界面改回主机最后一次报出来的真实状态。
            addLog("[主机] 拒绝了一条开关指令 " + rejected + "，不做拉黑");
            revertSwitchToHostState(rejected);
            setError("主机拒绝了该操作: " + rejected);
            emit();
            return;
        }
        if (isUserControlCommand(rejected)) {
            // 数值设定等用户主动控制同样不能凭裸 error 永久拉黑。
            // 这里只报告本次失败，不自动重发；下一次只能由用户再次明确点击触发。
            addLog("[主机] 拒绝了控制指令 " + rejected + "，不加入不支持黑名单");
            setError("主机拒绝了该操作: " + rejected);
            emit();
            return;
        }
        int failures = commandErrorCounts.containsKey(rejected)
                ? commandErrorCounts.get(rejected) + 1
                : 1;
        commandErrorCounts.put(rejected, failures);
        if (failures < 2) {
            // 一次拒绝还可能是归因错了：再观察一次，别急着把用户的操作拉黑。
            addLog("[主机] 拒绝指令 " + rejected + "，再试一次确认");
            return;
        }
        // 连续两次被拒才认定为主机不支持。
        unsupportedCommands.add(rejected);
        addLog("[主机] 连续拒绝指令 " + rejected + "，后续不再下发");
        setError("主机不支持该指令: " + rejected);
        emit();
    }

    /** 主机拒绝了开关指令：界面改回主机最后一次报出来的真实状态。 */
    private void revertSwitchToHostState(String cmd) {
        String key = switchKeyForCommand(cmd);
        if (TextUtils.isEmpty(key)) {
            return;
        }
        clearSwitchPending(key);
        Boolean hostValue = hostSwitchStates.get(key);
        if (hostValue != null) {
            setSwitchState(key, hostValue);
        }
    }

    /**
     * 主机拒绝了这个设定值（ret:"xxx_err"）。
     *
     * <p>主机既然拒绝了，界面就不能停在"用户点的那个值"上，
     * 否则用户看到的和主机做的完全不是一回事。</p>
     */
    private void handleHostValueRejected(JSONObject parsed, String cmd, String ret,
                                         AwaitingCommand replyCommand) {
        addLog("[主机] 拒绝 " + cmd + ": " + ret);
        if ("set_care_mode".equals(cmd)) {
            String expected = replyCommand == null ? parsed.optString("value", "")
                    : replyCommand.careModeValue;
            clearPendingCareMode(resolveResponseZone(parsed, parsed.optJSONObject("data")), expected);
        }
        String key = switchKeyForCommand(cmd);
        if (!TextUtils.isEmpty(key) && isResponseForCurrentZone(parsed)) {
            // 这次下发主机没做，别再重发：重发也还是这个结果。
            clearSwitchPending(key);
            Boolean hostValue = hostSwitchStates.get(key);
            if (hostValue != null) {
                setSwitchState(key, hostValue);
            }
        }
        setError("主机拒绝了该设定值(" + ret + ")");
        emit();
    }

    /**
     * 容错读取主机回包里的开关值。
     *
     * <p>不同固件的写法不一样："on"/"off"、1/0、true/false、"ON"/"OFF"，
     * 或者 {"status":"on"} 这种对象都要认；认不出来时返回 null，
     * 表示主机没有表态，不能当成"关"。</p>
     */
    private Boolean readSwitchFlag(JSONObject object, String... keys) {
        if (object == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (TextUtils.isEmpty(key) || !object.has(key)) {
                continue;
            }
            Boolean value = readSwitchValue(object.opt(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** 单个开关值的容错解析。 */
    private Boolean readSwitchValue(Object raw) {
        if (raw == null || raw == JSONObject.NULL) {
            return null;
        }
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        if (raw instanceof Number) {
            return ((Number) raw).intValue() != 0;
        }
        if (raw instanceof JSONObject) {
            return readSwitchFlag((JSONObject) raw, "status", "enable", "enabled", "state", "on");
        }
        String text = String.valueOf(raw).trim().toLowerCase(Locale.US);
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        if ("on".equals(text) || "1".equals(text) || "true".equals(text)
                || "open".equals(text) || "enabled".equals(text) || "run".equals(text)
                || "running".equals(text) || "start".equals(text) || "started".equals(text)) {
            return Boolean.TRUE;
        }
        if ("off".equals(text) || "0".equals(text) || "false".equals(text)
                || "close".equals(text) || "closed".equals(text) || "disabled".equals(text)
                || "stop".equals(text) || "stopped".equals(text) || "idle".equals(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 回包是不是成功：不同固件用 ok / success / true 都有。 */
    private boolean isOkResponse(JSONObject parsed) {
        if (parsed == null) {
            return false;
        }
        Object ret = parsed.opt("ret");
        if (ret == null || ret == JSONObject.NULL) {
            ret = parsed.opt("result");
        }
        if (ret == null || ret == JSONObject.NULL) {
            return false;
        }
        if (ret instanceof Boolean) {
            return (Boolean) ret;
        }
        if (ret instanceof Number) {
            return ((Number) ret).intValue() == 0;
        }
        String text = String.valueOf(ret).trim().toLowerCase(Locale.US);
        return "ok".equals(text) || "success".equals(text) || "true".equals(text)
                || "yes".equals(text) || "0".equals(text);
    }

    /** 容错读取剩余分钟数；读不到时返回 null，表示主机没说，不能当成 0 把倒计时清掉。 */
    private Integer readRemainMinutes(JSONObject object, String... keys) {
        if (object == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (TextUtils.isEmpty(key) || !object.has(key)) {
                continue;
            }
            Object raw = object.opt(key);
            if (raw instanceof Number) {
                return normalizePresetMinutes(((Number) raw).intValue());
            }
            if (raw instanceof String) {
                String text = ((String) raw).trim();
                if (TextUtils.isEmpty(text)) {
                    continue;
                }
                try {
                    return normalizePresetMinutes((int) Double.parseDouble(text));
                } catch (NumberFormatException ignored) {
                    // 不是数字就继续试下一个字段
                }
            }
        }
        return null;
    }

    /** 读取监护等级灯颜色：可能是字符串，也可能是 {"color":"red"} 这种对象。 */
    private String readStatusLightText(JSONObject data) {
        if (data == null) {
            return "";
        }
        Object raw = data.opt("status_light");
        if (raw instanceof JSONObject) {
            JSONObject object = (JSONObject) raw;
            String color = object.optString("color", "");
            if (TextUtils.isEmpty(color)) {
                color = object.optString("status", "");
            }
            return color;
        }
        return data.optString("status_light", "");
    }

    /** 容错读取数值：主机可能用 current，也可能用 value。 */
    private Float valueDiv10Any(JSONObject object, Float fallback, String... keys) {
        if (object == null || keys == null) {
            return fallback;
        }
        for (String key : keys) {
            if (TextUtils.isEmpty(key) || !object.has(key)) {
                continue;
            }
            Object raw = object.opt(key);
            if (raw instanceof Number) {
                return (float) ((Number) raw).doubleValue() / 10f;
            }
            if (raw instanceof String) {
                String text = ((String) raw).trim();
                if (TextUtils.isEmpty(text)) {
                    continue;
                }
                try {
                    return (float) Double.parseDouble(text) / 10f;
                } catch (NumberFormatException ignored) {
                    // 不是数字就继续试下一个字段
                }
            }
        }
        return fallback;
    }

    /**
     * 兼容两种数值口径：
     * 1）主机回的是已经放大 10 倍的整数（例如 298 表示 29.8℃），需要 ÷10；
     * 2）主机回的是小数本身（例如 29.8），不能再 ÷10，否则会显示成 2.98。
     *
     * <p>这里按“大于 100 才认为是放大 10 倍”的启发式处理，
     * 温度 / 湿度 / 氧浓度都在 0~100 量级，误判概率极低。</p>
     */
    private Float valueMaybeDiv10Any(JSONObject object, Float fallback, String... keys) {
        if (object == null || keys == null) {
            return fallback;
        }
        for (String key : keys) {
            if (TextUtils.isEmpty(key) || !object.has(key)) {
                continue;
            }
            Object raw = object.opt(key);
            Double value = null;
            if (raw instanceof Number) {
                value = ((Number) raw).doubleValue();
            } else if (raw instanceof String) {
                String text = ((String) raw).trim();
                if (TextUtils.isEmpty(text)) {
                    continue;
                }
                try {
                    value = Double.parseDouble(text);
                } catch (NumberFormatException ignored) {
                    // 不是数字就继续试下一个字段
                }
            }
            if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
                continue;
            }
            if (Math.abs(value) > 100d) {
                value = value / 10d;
            }
            return value.floatValue();
        }
        return fallback;
    }

    /** 兼容读取整数（CO₂ 等）：支持多个候选字段。 */
    private Integer readIntAny(JSONObject object, String... keys) {
        if (object == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (TextUtils.isEmpty(key) || !object.has(key)) {
                continue;
            }
            Object raw = object.opt(key);
            if (raw instanceof Number) {
                return ((Number) raw).intValue();
            }
            if (raw instanceof String) {
                String text = ((String) raw).trim();
                if (TextUtils.isEmpty(text)) {
                    continue;
                }
                try {
                    return (int) Double.parseDouble(text);
                } catch (NumberFormatException ignored) {
                    // 不是数字就继续试下一个字段
                }
            }
        }
        return null;
    }

    private void scheduleStatusRefresh() {
        if (quietBatch) {
            // 批量下发期间不逐条插状态查询，整批结束后由 endQuietBatch 统一刷一次。
            return;
        }
        handler.removeCallbacks(statusRefreshRunnable);
        handler.postDelayed(statusRefreshRunnable, CONTROL_STATUS_REFRESH_DELAY_MS);
    }

    private Float getCurrentOxygen() {
        return environmentForZone(currentZone).oxygen;
    }

    /**
     * 是否有下发指令所需的蓝牙权限。
     * 判定和 ensureReady 保持一致：权限没给时指令根本发不出去。
     */
    public boolean canSendCommand() {
        return !missingBluetoothConnectPermission();
    }

    private boolean ensureReady() {
        if (missingBluetoothConnectPermission()) {
            setError("缺少蓝牙连接权限，请先申请权限");
            return false;
        }
        if (bluetoothGatt == null) {
            setError("请先连接蓝牙设备");
            return false;
        }
        if (!connected || writeCharacteristic == null) {
            setError("蓝牙已连接，协议通道初始化中，请稍后再试");
            return false;
        }
        return true;
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
     * 扫描不出设备时的现场诊断。
     *
     * <p>BLE 扫描失败大多不是设备不在附近，而是权限或开关没到位：系统会把
     * 扫描结果整批静默过滤掉，App 侧看起来就是“一个设备都没有”。这里把最可能
     * 的原因直接摆到界面上，省得使用者靠猜。</p>
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

    private boolean missingBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT >= 31
                && permissionDenied(Manifest.permission.BLUETOOTH_CONNECT);
    }

    private boolean permissionDenied(String permission) {
        return appContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED;
    }

    private String formatCommandNumber(float value) {
        if (Math.abs(value - Math.round(value)) < 0.001f) {
            return String.valueOf(Math.round(value));
        }
        return String.format(Locale.US, "%.1f", value);
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void updateOxygenFromZone(String zone, Float value) {
        if (value == null) {
            return;
        }
        ZoneEnvironmentState environment = environmentForZone(zone);
        environment.oxygen = value;
        environment.updatedAtMs = System.currentTimeMillis();
    }

    private boolean validVersion(String value) {
        return !TextUtils.isEmpty(value)
                && !"--".equals(value)
                && !"undefined".equals(value)
                && !"null".equals(value);
    }

    private String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private boolean nextSwitchValue(Boolean current) {
        return !Boolean.TRUE.equals(current);
    }

    private String nextStatusLightColor() {
        if ("red".equals(statusLightColor)) {
            return "yellow";
        }
        if ("yellow".equals(statusLightColor)) {
            return "green";
        }
        return "red";
    }

    private Float valueDiv10(JSONObject object, String key, Float fallback) {
        if (object == null || !object.has(key)) {
            return fallback;
        }
        return (float) object.optDouble(key) / 10f;
    }

    private String formatValue(Float value, String suffix) {
        return value == null ? "--" : formatNumber(value) + suffix;
    }

    private String formatSwitch(Boolean value) {
        if (value == null) {
            return "--";
        }
        return value ? "开" : "关";
    }

    private String formatTimedSwitch(Boolean enabled, Integer minutes) {
        if (enabled == null) {
            return "--";
        }
        if (!enabled) {
            return "关";
        }
        return minutes == null ? "开" : "开" + minutes + "分钟";
    }

    private String formatTimedShort(Boolean enabled, Integer minutes) {
        if (enabled == null) {
            return "--";
        }
        if (!enabled) {
            return "关";
        }
        return minutes == null ? "开" : formatClockMinutes(minutes);
    }

    // 否则页面会显示 65535 分钟而不是默认的 99 分钟。
    private Integer normalizePresetMinutes(int minutes) {
        if (minutes <= 0 || minutes > MAX_PRESET_MINUTES) {
            return null;
        }
        return minutes;
    }

    private String formatStatusLight() {
        if ("red".equals(statusLightColor)) {
            return "红";
        }
        if ("yellow".equals(statusLightColor)) {
            return "黄";
        }
        if ("green".equals(statusLightColor)) {
            return "绿";
        }
        return "--";
    }

    private String formatNumber(float value) {
        if (Math.abs(value - Math.round(value)) < 0.05f) {
            return String.valueOf(Math.round(value));
        }
        return String.format(Locale.US, "%.1f", value);
    }

    private String extractJsonString(String message, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(message);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String extractJsonNumber(String message, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
        Matcher matcher = pattern.matcher(message);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String extractNestedInt(String message, String objectKey, String valueKey) {
        Pattern objectPattern = Pattern.compile("\"" + objectKey + "\"\\s*:\\s*\\{([^}]*)\\}");
        Matcher objectMatcher = objectPattern.matcher(message);
        if (!objectMatcher.find()) {
            return "";
        }
        return extractJsonNumber(objectMatcher.group(1), valueKey);
    }

    private String formatDiv10(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        try {
            return String.format(Locale.US, "%.1f", Float.parseFloat(raw) / 10f);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private void setError(String message) {
        lastError = message;
        stickyError = message;
        stickyErrorAtMs = System.currentTimeMillis();
        // 新错误不能继续沿用旧 TX 报文的关联，否则一次无关成功会清错提示。
        stickyWriteFailurePayload = "";
        addLog(message);
        emit();
    }

    private void clearStickyError() {
        stickyError = "";
        stickyErrorAtMs = 0L;
        stickyWriteFailurePayload = "";
    }

    private void addLog(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        synchronized (logs) {
            logs.add(0, "[" + timestamp + "] " + message);
            while (logs.size() > MAX_LOG_COUNT) {
                logs.remove(logs.size() - 1);
            }
        }
    }

    private void emit() {
        if (listener != null) {
            listener.onStateChanged();
        }
    }
}
