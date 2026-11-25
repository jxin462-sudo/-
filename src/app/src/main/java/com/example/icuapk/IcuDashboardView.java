package com.example.icuapk;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class IcuDashboardView extends View {
    private static final String TAG = "IcuDashboard";
    private static final String KEY_TREATMENT_CASES = "treatment_cases";
    /** 选中病例与监护会话都使用稳定 petId，不能随可编辑、可复用的住院号改变身份。 */
    private static final String KEY_SELECTED_CASE = "selected_case_pet_id";
    private static final String KEY_MONITOR_SNAPSHOT = "monitor_snapshot_v1";
    /** 软件侧数据按舱隔离：左右舱的病例与机构互不覆盖。 */
    private static final String ZONE_LEFT = "left";
    private static final String ZONE_RIGHT = "right";
    private static final String KEY_CAMERA_STREAM_URL = "camera_stream_url";
    private static final String KEY_CAMERA_STREAM_USER_SET = "camera_stream_url_user_set";
    private static final String KEY_CAMERA_SNAPSHOTS = "camera_snapshots";
    /** 摄像头里当前版本还没有开放的功能，逐项说明原因。 */
    private static final String[][] CAMERA_UNSUPPORTED_FEATURES = {
            {"talk", "对讲"},
            {"privacy", "镜头遮蔽"},
            {"flip", "画面翻转"},
            {"alarm", "手动报警"},
            {"original", "原图"},
            {"cruise", "巡航"},
            {"chart", "图表"},
            {"custom", "自定义"}
    };
    private static final String KEY_ORGANIZATIONS = "organizations";
    private static final String KEY_SELECTED_ORGANIZATION = "selected_organization";
    /** 仪器状态页（产品型号/机型/机号/生产日期/程序版本）的落库键。 */
    private static final String KEY_DEVICE_PROFILE = "device_profile";
    private static final String KEY_SELECTED_HOST_MODE = "selected_host_mode";
    private static final String KEY_LAST_HOST_MODE = "last_host_mode";
    private static final String KEY_CUSTOM_HOST_MODE_CONFIG = "custom_host_mode_config";
    /** 母幼/术后/心肺三个预设模式各自保存一套开关配置，键名按模式区分。 */
    private static final String KEY_HOST_MODE_CONFIG_PREFIX = "host_mode_config_";
    /** 四个模式分别保存 enabled 键；统一四选一写入保证最多一个为 true。 */
    private static final String KEY_HOST_MODE_ENABLED_PREFIX = "host_mode_enabled_";
    /** 自定义模式在四个模式中的下标；它的配置沿用旧键，避免升级后丢掉已保存的数据。 */
    private static final int HOST_MODE_CUSTOM_INDEX = 3;
    private static final String KEY_TREATMENT_PERIOD_MODE = "treatment_period_mode";
    /** 治疗记录“自定义时段”的时间窗口（开始/结束），按舱保存。 */
    private static final String KEY_TREATMENT_CUSTOM_WINDOW = "treatment_custom_window";
    /** 监护上下限按舱保存：左右舱各自一套报警门限，互不覆盖。 */
    private static final String KEY_MONITOR_LIMITS = "monitor_limits";
    /** 宠物级数据的迁移标记：舱级旧值只迁移一次，之后每只宠物各存各的。 */
    private static final String KEY_PET_SCOPE_MIGRATED = "pet_scope_migrated";
    /**
     * 账号级数据的键前缀：这些数据跟着账号走，换一个账号就是另一套。
     * 设备级数据（仪器状态、报警音、摄像头地址）不在这个名单里，所有账号共用。
     */
    private static final String[] ACCOUNT_SCOPED_KEY_PREFIXES = {
            KEY_TREATMENT_CASES,
            KEY_SELECTED_CASE,
            KEY_MONITOR_SNAPSHOT,
            KEY_ORGANIZATIONS,
            KEY_SELECTED_ORGANIZATION,
            KEY_SELECTED_HOST_MODE,
            KEY_LAST_HOST_MODE,
            KEY_CUSTOM_HOST_MODE_CONFIG,
            KEY_HOST_MODE_CONFIG_PREFIX,
            KEY_TREATMENT_PERIOD_MODE,
            KEY_TREATMENT_CUSTOM_WINDOW,
            KEY_MONITOR_LIMITS,
            KEY_CAMERA_SNAPSHOTS,
            KEY_PET_SCOPE_MIGRATED
    };
    /** 最近请求项只用于展示；没有记录时为 -1，不能默认假装母幼模式已开启。 */
    private static final int DEFAULT_SELECTED_HOST_MODE = -1;
    private static final int DEFAULT_LAST_HOST_MODE = -1;
    /** 监护上下限的默认值：换宠物时先回到默认值，避免沿用上一只宠物的门限。 */
    private static final boolean[] DEFAULT_LIMIT_ENABLED = {true, true, true, true, true, true, true};
    private static final float[] DEFAULT_LIMIT_HIGH = {120f, 140f, 90f, 120f, 100f, 38f, 40f};
    private static final float[] DEFAULT_LIMIT_LOW = {60f, 90f, 60f, 60f, 94f, 35f, 8f};
    private static final String KEY_MONITOR_PULSE_BEEP = "monitor_pulse_beep";
    private static final String KEY_MONITOR_ALARM_SOUND = "monitor_alarm_sound";
    /** 仪器状态页“产品型号”的默认值，与设计稿上的机型标识保持一致。 */
    private static final String DEFAULT_PRODUCT_MODEL = "EM-150Vet-20";
    /** 医院 LOGO 选择请求码：MainActivity 收到结果后回调 onOrganizationLogoSelected。 */
    static final int REQUEST_ORGANIZATION_LOGO = 3001;
    private static final String CAMERA_PLAYER_VERSION = "摄像头内置播放器 v1.8";
    private static final String PERIOD_ALL = "all";
    private static final String PERIOD_ENABLED = "enabled";
    private static final String PERIOD_CUSTOM = "custom";
    // 治疗时长暂不开放设置；旧配置里的 treatmentMinutes 也不再参与四个模式的表单或批量下发。
    private static final String[] CUSTOM_NUMBER_KEYS = {"temp", "oxygen", "co2"};
    private static final String[] CUSTOM_NUMBER_LABELS = {"舱内温度 ℃", "氧浓度 %", "CO2目标值 PPM"};
    private static final int[] CUSTOM_NUMBER_INDICES = {0, 1, 3};
    private static final boolean[] CUSTOM_NUMBER_INTEGER = {false, false, true};
    private static final String[] CUSTOM_SWITCH_KEYS = {"co2Enabled", "coldLight", "warmLight", "redTherapy", "blueTherapy", "uv", "nebulizer", "anion", "outerCycle", "innerCycle"};
    private static final String[] CUSTOM_SWITCH_LABELS = {"CO2开关", "冷光照明", "暖光照明", "红外理疗", "蓝光理疗", "紫外消毒", "雾化器", "负离子", "外循环", "内循环"};
    private static final int[] CUSTOM_SWITCH_INDICES = {3, 4, 5, 6, 7, 12, 10, 11, 8, 9};
    private static final String[] CUSTOM_TIME_KEYS = {"redTime", "blueTime", "uvTime", "nebulizerTime", "anionTime"};
    private static final String[] CUSTOM_TIME_LABELS = {"红外理疗时间 分钟", "蓝光理疗时间 分钟", "紫外消毒时间 分钟", "雾化器时间 分钟", "负离子时间 分钟"};
    private static final int[] CUSTOM_TIME_INDICES = {6, 7, 12, 10, 11};

    private static final String[][] TREATMENT_TEMPLATES = {
            {"舱内温度", "℃", "true", "true"},
            {"氧浓度", "%", "true", "true"},
            {"红外理疗", "分钟", "false", "true"},
            {"蓝光理疗", "分钟", "false", "true"},
            {"雾化器", "分钟", "false", "true"},
            {"负离子", "分钟", "false", "true"},
            {"湿度", "%", "true", "true"},
            {"CO2浓度", "PPM", "true", "true"},
            {"护理模式", "", "true", "true"},
            {"心率", "bpm", "true", "true"},
            {"血压", "mmHg", "true", "true"},
            {"血氧", "%", "true", "true"},
            {"脉率", "bpm", "true", "true"},
            {"体温", "℃", "true", "true"},
            {"呼吸率", "brpm", "true", "true"},
            {"心电图", "", "true", "true"},
            {"血氧波形", "", "true", "true"},
            {"呼吸率波形", "", "true", "true"}
    };

    interface CameraPreviewHost {
        void updateCameraPreview(RectF bounds, boolean visible, String[] urls);

        void restartCameraPreview(String[] urls);

        void captureCameraFrame(CameraFrameCallback callback);

        /** 开始录像，录像文件直接写到 output。 */
        void startCameraRecording(File output) throws IOException;

        /** 结束录像，返回录像文件；没有录到画面时返回 null。 */
        File stopCameraRecording();

        /** 当前是否正在录像。 */
        boolean isCameraRecording();
    }

    interface CameraFrameCallback {
        void onFrame(Bitmap bitmap);

        void onError(String message);
    }

    private interface ClickAction {
        void run();
    }

    private static final class ClickZone {
        final RectF rect;
        final ClickAction action;

        ClickZone(RectF rect, ClickAction action) {
            this.rect = rect;
            this.action = action;
        }
    }

    private void showTreatmentEntryDialog(final PatientCase patient, final TreatmentEntry entry) {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dpInt(12);
        form.setPadding(pad, pad, pad, pad);

        final CheckBox enabled = new CheckBox(activity);
        enabled.setText("纳入统计");
        enabled.setChecked(entry.enabled);
        form.addView(enabled);

        final CheckBox includeReport = new CheckBox(activity);
        includeReport.setText("是否录入报告");
        includeReport.setChecked(entry.includeInReport);
        form.addView(includeReport);

        final EditText manualAverage = addInput(form, "手动平均值/医生修订", entry.manualAverageValue, InputType.TYPE_CLASS_TEXT);
        final EditText manualHigh = addInput(form, "手动最高值/医生修订", entry.manualHighValue, InputType.TYPE_CLASS_TEXT);
        final EditText manualLow = addInput(form, "手动最低值/医生修订", entry.manualLowValue, InputType.TYPE_CLASS_TEXT);
        final EditText start = addInput(form, "开始治疗时间", entry.startTime, InputType.TYPE_CLASS_TEXT);
        final EditText end = addInput(form, "结束治疗时间", entry.endTime, InputType.TYPE_CLASS_TEXT);

        new AlertDialog.Builder(activity)
                .setTitle("编辑治疗记录 - " + entry.itemName)
                .setView(form)
                .setNegativeButton("取消", null)
                .setNeutralButton("清空统计", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        entry.sum = 0f;
                        entry.high = Float.NaN;
                        entry.low = Float.NaN;
                        entry.sampleCount = 0;
                        entry.samples.clear();
                        entry.manualAverageValue = "";
                        entry.manualHighValue = "";
                        entry.manualLowValue = "";
                        entry.lastValue = "--";
                        lastGeneratedPdf = null;
                        saveTreatmentRecordsToStorage();
                        invalidate();
                    }
                })
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        entry.enabled = enabled.isChecked();
                        entry.includeInReport = includeReport.isChecked();
                        entry.manualAverageValue = manualAverage.getText().toString().trim();
                        entry.manualHighValue = manualHigh.getText().toString().trim();
                        entry.manualLowValue = manualLow.getText().toString().trim();
                        String nextStartTime = cleanInput(start, entry.startTime);
                        String nextEndTime = cleanInput(end, entry.endTime);
                        // 用户手改开始/结束时间后，该项按自定义时段统计
                        boolean timeChanged = !nextStartTime.equals(entry.startTime)
                                || !nextEndTime.equals(entry.endTime);
                        entry.startTime = nextStartTime;
                        entry.endTime = nextEndTime;
                        if (timeChanged) {
                            entry.customPeriod = true;
                        }
                        patient.treatmentStartTime = firstNonEmpty(patient.treatmentStartTime, entry.startTime);
                        lastGeneratedPdf = null;
                        saveTreatmentRecordsToStorage();
                        Toast.makeText(activity, "治疗记录已保存", Toast.LENGTH_SHORT).show();
                        invalidate();
                    }
                })
                .show();
    }

    private String treatmentPeriodText(TreatmentEntry entry) {
        if (entry.sampleCount <= 0) {
            return "--";
        }
        return entry.sampleCount + "次 / " + treatmentLastValueText(entry);
    }

    // 采样值可能是旧格式（开启 35分钟 / 92分钟），时间类项目统一折算成 00:00
    private String treatmentLastValueText(TreatmentEntry entry) {
        String value = entry.lastValue;
        if (TextUtils.isEmpty(value)) {
            return "-";
        }
        if (!isTimeUnit(entry.unit)) {
            return value;
        }
        float minutes = parseClockMinutes(value);
        if (Float.isNaN(minutes)) {
            return value;
        }
        return formatClockMinutes(Math.round(minutes));
    }

    /** 少于 2 个有效样本时最高/最低没有统计意义，界面只给 "--"。 */
    private static final int MIN_RELIABLE_SAMPLES = 2;

    private String treatmentAverageText(TreatmentEntry entry) {
        if (entry == null) {
            return "--";
        }
        if (!TextUtils.isEmpty(entry.manualAverageValue)) {
            return manualStatDisplayText(entry.manualAverageValue, entry.unit);
        }
        TreatmentStats stats = treatmentStatsFor(entry);
        if (stats.sampleCount <= 0) {
            return "--";
        }
        return treatmentExtremeText(stats.average(), entry.unit);
    }

    /** 最高值：优先用医生修订值，其次按当前时段口径重新统计。 */
    private String treatmentHighText(TreatmentEntry entry) {
        if (entry == null) {
            return "--";
        }
        if (!TextUtils.isEmpty(entry.manualHighValue)) {
            return manualStatDisplayText(entry.manualHighValue, entry.unit);
        }
        TreatmentStats stats = treatmentStatsFor(entry);
        if (stats.sampleCount < MIN_RELIABLE_SAMPLES) {
            return "--";
        }
        return treatmentExtremeText(stats.high, entry.unit);
    }

    /** 最低值：优先用医生修订值，其次按当前时段口径重新统计。 */
    private String treatmentLowText(TreatmentEntry entry) {
        if (entry == null) {
            return "--";
        }
        if (!TextUtils.isEmpty(entry.manualLowValue)) {
            return manualStatDisplayText(entry.manualLowValue, entry.unit);
        }
        TreatmentStats stats = treatmentStatsFor(entry);
        if (stats.sampleCount < MIN_RELIABLE_SAMPLES) {
            return "--";
        }
        return treatmentExtremeText(stats.low, entry.unit);
    }

    /** 手工填写的统计值：纯数字按单位格式化，其余原样输出。 */
    private String manualStatDisplayText(String value, String unit) {
        if (TextUtils.isEmpty(value)) {
            return "--";
        }
        String trimmed = value.trim();
        if (trimmed.matches("[-+]?\\d+(?:\\.\\d+)?")) {
            try {
                return treatmentExtremeText(Float.parseFloat(trimmed), unit);
            } catch (NumberFormatException ignored) {
            }
        }
        return trimmed;
    }

    /**
     * 按当前时段口径重新统计某个治疗项：
     * 全时段用全部采样，功能开启时段只算功能开启时采到的点，
     * 自定义时段只算落在时间窗口内的点。
     */
    private TreatmentStats treatmentStatsFor(TreatmentEntry entry) {
        TreatmentStats stats = new TreatmentStats();
        if (entry == null) {
            return stats;
        }
        if (entry.samples.isEmpty()) {
            // 老数据没有采样明细，退回到全时段累计值；
            // high 为空说明从来没采到过可计算的数值，此时不应当显示 0
            if (!Float.isNaN(entry.high)) {
                stats.sampleCount = entry.sampleCount;
                stats.sum = entry.sum;
                stats.high = entry.high;
                stats.low = entry.low;
            }
            return stats;
        }
        boolean onlyEnabled = PERIOD_ENABLED.equals(treatmentPeriodMode);
        long from = Long.MIN_VALUE;
        long to = Long.MAX_VALUE;
        if (PERIOD_CUSTOM.equals(treatmentPeriodMode)) {
            long[] window = treatmentCustomWindow(entry);
            from = window[0];
            to = window[1];
        }
        for (int i = 0; i < entry.samples.size(); i++) {
            TreatmentSample sample = entry.samples.get(i);
            if (onlyEnabled && !sample.featureOn) {
                continue;
            }
            if (from != Long.MIN_VALUE && (sample.time < from || sample.time > to)) {
                continue;
            }
            stats.sampleCount++;
            stats.sum += sample.value;
            stats.high = Float.isNaN(stats.high) ? sample.value : Math.max(stats.high, sample.value);
            stats.low = Float.isNaN(stats.low) ? sample.value : Math.min(stats.low, sample.value);
        }
        return stats;
    }

    /** 自定义时段窗口：优先用全局窗口，没有则用该条目自己手工指定的时间。 */
    private long[] treatmentCustomWindow(TreatmentEntry entry) {
        long start = parseTimeTextMs(treatmentCustomStart);
        long end = parseTimeTextMs(treatmentCustomEnd);
        if (start <= 0L || end <= start) {
            if (entry != null && entry.customPeriod) {
                start = parseTimeTextMs(entry.startTime);
                end = parseTimeTextMs(entry.endTime);
            }
        }
        if (start <= 0L || end <= start) {
            return new long[]{Long.MIN_VALUE, Long.MAX_VALUE};
        }
        return new long[]{start, end};
    }

    /** "yyyy-MM-dd HH:mm:ss" 文本转毫秒；解析失败返回 -1。 */
    private static long parseTimeTextMs(String text) {
        if (TextUtils.isEmpty(text)) {
            return -1L;
        }
        try {
            java.text.SimpleDateFormat format =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            java.util.Date date = format.parse(text.trim());
            return date == null ? -1L : date.getTime();
        } catch (Exception ignored) {
            return -1L;
        }
    }

    /** “选取时段”列的文案：自定义时段 / 功能开启时段 / 全时段。 */
    private String treatmentPeriodLabel(TreatmentEntry entry) {
        if (entry != null && entry.customPeriod) {
            return "自定义时段";
        }
        if (PERIOD_CUSTOM.equals(treatmentPeriodMode)) {
            return "自定义时段";
        }
        if (PERIOD_ENABLED.equals(treatmentPeriodMode)
                || isSessionTrackedTreatment(entry == null ? "" : entry.itemName)) {
            return "功能开启时段";
        }
        return "全时段";
    }

    /** 会话类治疗项：只有开关沿能统计使用时长，做数值平均没有意义。 */
    private static boolean isSessionTrackedTreatment(String itemName) {
        return "红外理疗".equals(itemName) || "蓝光理疗".equals(itemName)
                || "雾化器".equals(itemName) || "雾化治疗".equals(itemName)
                || "负离子".equals(itemName) || "紫外消毒".equals(itemName);
    }

    /** 会话类治疗项的累计开启时长，未开启过返回空串。 */
    private String sessionUsageText(TreatmentEntry entry) {
        if (entry == null || !isSessionTrackedTreatment(entry.itemName)) {
            return "";
        }
        // 口径与结束结算共用 TreatmentUsage，避免页面显示一个时长、报告落另一个时长。
        return TreatmentUsage.formatUsage(TreatmentUsage.effectiveOnMs(
                entry.totalOnMs, entry.featureOn, entry.featureOnSince, System.currentTimeMillis()));
    }

    /** 治疗项当前设备/链路状态，仅用于实时采样与累计，不代表记录项是否纳入统计。 */
    private boolean treatmentFeatureOn(String itemName) {
        if ("舱内温度".equals(itemName)) {
            return bleManager.isTempEnabled();
        }
        if ("氧浓度".equals(itemName) || "氧气浓度".equals(itemName)) {
            return bleManager.isOxygenEnabled();
        }
        if ("CO2浓度".equals(itemName)) {
            return bleManager.isControlOn(3);
        }
        if ("红外理疗".equals(itemName)) {
            return bleManager.isControlOn(6);
        }
        if ("蓝光理疗".equals(itemName)) {
            return bleManager.isControlOn(7);
        }
        if ("雾化器".equals(itemName) || "雾化治疗".equals(itemName)) {
            return bleManager.isControlOn(10);
        }
        if ("负离子".equals(itemName)) {
            return bleManager.isControlOn(11);
        }
        if ("紫外消毒".equals(itemName)) {
            return bleManager.isControlOn(12);
        }
        if ("护理模式".equals(itemName)) {
            return !TextUtils.isEmpty(
                    bleManager.getConfirmedCareMode(bleManager.getCurrentZone()));
        }
        if (isMonitorVitalItem(itemName) || TreatmentSamplePolicy.isWaveform(itemName)) {
            return am4100Manager.isConnected()
                    && waveformSourceReady(itemName)
                    && isValidTreatmentSample(itemName,
                            parseFirstNumber(treatmentDisplayValue(itemName)));
        }
        return bleManager.isConnected();
    }

    /** 生命体征与波形类项目：靠监护仪数值判断是否“有数据”。 */
    private static boolean isMonitorVitalItem(String itemName) {
        return "心率".equals(itemName) || "血压".equals(itemName) || "血氧".equals(itemName)
                || "脉率".equals(itemName) || "体温".equals(itemName) || "呼吸率".equals(itemName)
                || "心电图".equals(itemName) || "血氧波形".equals(itemName) || "呼吸率波形".equals(itemName);
    }

    private String treatmentExtremeText(float value, String unit) {
        if (Float.isNaN(value)) {
            return "--";
        }
        if (isTimeUnit(unit)) {
            return formatClockMinutes(Math.round(value));
        }
        String number = Math.abs(value - Math.round(value)) < 0.05f
                ? String.valueOf(Math.round(value))
                : String.format(Locale.US, "%.1f", value);
        return number + unit;
    }

    // 时间类计量单位统一换算成 00:00，和页面上的治疗时长保持一致
    private static boolean isTimeUnit(String unit) {
        return "分钟".equals(unit) || "小时".equals(unit);
    }

    private static String formatClockMinutes(int totalMinutes) {
        int minutes = Math.max(0, totalMinutes);
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private static final class Organization {
        String name;
        String address;
        String phone;
        String logoUri;
        String logoName;

        Organization(String name, String address) {
            this(name, address, "0755-00000000", "", "");
        }

        Organization(String name, String address, String phone) {
            this(name, address, phone, "", "");
        }

        Organization(String name, String address, String phone, String logoUri, String logoName) {
            this.name = name;
            this.address = address;
            this.phone = phone;
            this.logoUri = logoUri == null ? "" : logoUri;
            this.logoName = logoName == null ? "" : logoName;
        }
    }

    /**
     * 仪器状态页的设备档案：产品型号 / 机型 / 机号 / 生产日期 / 程序版本。
     * 由页面内输入框编辑后经 MainActivity 回传，落 SharedPreferences。
     */
    private static final class DeviceProfile {
        String productModel = DEFAULT_PRODUCT_MODEL;
        String machineType = "";
        String serialNo = "";
        String manufactureDate = "";
        /** 用户手工填写的程序版本；softwareVersionFollowsApp 为 true 时该字段不生效。 */
        String softwareVersion = "";
        /** 程序版本是否自动跟随当前 App 版本号（App 升级后自动显示新版本号）。 */
        boolean softwareVersionFollowsApp = true;
    }

    private static final class PatientCase {
        String caseNo;
        String monitorNo;
        /** 宠物唯一标识：同一舱内不同宠物的数据靠它分开存放。 */
        String petId = "";
        String petName;
        String species;
        String sex;
        String age;
        String ownerName;
        String ownerPhone;
        String doctor;
        String recordNo;
        String visitDate;
        String note;
        boolean currentTreatment;
        /** 用户主动新建的样本：即使三项必填还没录入，也必须一直留在样本列表里。 */
        boolean explicitSample;
        String treatmentStartTime;
        String treatmentEndTime;
        long lastTreatmentSampleAt;
        final ArrayList<TreatmentEntry> treatmentEntries = new ArrayList<>();

        PatientCase(String caseNo, String monitorNo, String petName, String species, String sex, String age,
                    String ownerName, String ownerPhone, String doctor, String recordNo, String visitDate, String note) {
            this.caseNo = caseNo;
            this.monitorNo = monitorNo;
            this.petId = UUID.randomUUID().toString();
            this.petName = petName;
            this.species = species;
            this.sex = sex;
            this.age = age;
            this.ownerName = ownerName;
            this.ownerPhone = ownerPhone;
            this.doctor = doctor;
            this.recordNo = recordNo;
            this.visitDate = visitDate;
            this.note = note;
            this.treatmentStartTime = visitDate;
            this.treatmentEndTime = "进行中";
            seedTreatmentEntries();
        }

        private void seedTreatmentEntries() {
            for (String[] item : TREATMENT_TEMPLATES) {
                // 单项的开始/结束时间必须由真实开关沿或有效样本写入。
                // 早期把病例级的“就诊时间/进行中”预置给每一项，导致没开过的功能
                // 也显示开始时间、结束时间一直是“进行中”。新建时一律留空，页面显示 "--"。
                treatmentEntries.add(new TreatmentEntry(item[0], item[1],
                        Boolean.parseBoolean(item[2]), Boolean.parseBoolean(item[3]),
                        "", ""));
            }
        }
    }

    private static final class TreatmentEntry {
        /** 单项最多保留的采样条数，超出后丢最旧的，避免长时间治疗把存储撑爆。 */
        static final int MAX_SAMPLES = 720;

        String itemName;
        String unit;
        /** 用户勾选的“纳入统计”，同时决定治疗记录“是否打开”列；与设备实时状态分开。 */
        boolean enabled;
        boolean includeInReport;
        /** 用户手工改过开始/结束时间：该项按自定义时段统计。 */
        boolean customPeriod;
        String startTime;
        String endTime;
        String manualValue = "";
        /** 医生修订的平均值/最高值/最低值，优先于自动统计。 */
        String manualAverageValue = "";
        String manualHighValue = "";
        String manualLowValue = "";
        String lastValue = "--";
        /** 带时间戳的采样明细，用于按“功能开启时段/自定义时段”重新统计。 */
        final ArrayList<TreatmentSample> samples = new ArrayList<>();
        /** 累计开启时长（毫秒），对应“功能开启时段”。 */
        long totalOnMs;
        /** 上一次采样时功能是否开启，用于检测开关沿。 */
        boolean featureOn;
        /** 本次开启的起始时刻。 */
        long featureOnSince;
        /** 已补入本项统计的最新监护测量时间，防止周期/结束结算重复计数。 */
        long lastHistoryBackfillAt;
        float sum;
        float high = Float.NaN;
        float low = Float.NaN;
        int sampleCount;
        /** 首次读到真实数值的标记，独立于手工改过的时间，决定“是否打开”列。 */
        boolean readingStarted;

        TreatmentEntry(String itemName, String unit, boolean enabled, boolean includeInReport, String startTime, String endTime) {
            this.itemName = itemName;
            this.unit = unit;
            this.enabled = enabled;
            this.includeInReport = includeInReport;
            this.startTime = startTime;
            this.endTime = endTime;
        }

        /**
         * 只有真实数值才计入统计。占位的 "--"、哨兵 -1、全零 0.0
         * 以前会被记成一次采样，出现 sampleCount=1 且 avg=max=min。
         */
        void record(float value, String displayValue) {
            if (Float.isNaN(value) || Float.isInfinite(value) || value <= 0f) {
                return;
            }
            lastValue = TextUtils.isEmpty(displayValue) ? String.valueOf(value) : displayValue;
            sum += value;
            high = Float.isNaN(high) ? value : Math.max(high, value);
            low = Float.isNaN(low) ? value : Math.min(low, value);
            sampleCount++;
        }

        /** 记录一次带时间戳的采样，采样明细用于按时段口径重新统计。 */
        void addSample(long time, float value, boolean featureOn) {
            samples.add(new TreatmentSample(time, value, featureOn));
            while (samples.size() > MAX_SAMPLES) {
                samples.remove(0);
            }
        }
    }

    /** 治疗记录的一次采样：时间、数值、采样那一刻功能是否处于开启状态。 */
    private static final class TreatmentSample {
        final long time;
        final float value;
        final boolean featureOn;

        TreatmentSample(long time, float value, boolean featureOn) {
            this.time = time;
            this.value = value;
            this.featureOn = featureOn;
        }
    }

    /** 某个治疗项在当前时段口径下的统计结果。 */
    private static final class TreatmentStats {
        int sampleCount;
        float sum;
        float high = Float.NaN;
        float low = Float.NaN;

        float average() {
            return sampleCount > 0 ? sum / sampleCount : Float.NaN;
        }
    }

    private static final class PdfRecord {
        String action;
        String fileName;
        String patientName;
        String timeText;
        String location;
        File cacheFile;
        Uri downloadUri;
        /** 记录属于哪个舱，左右舱的报告下载列表各自独立。 */
        String zone;

        PdfRecord(String action, String fileName, String patientName, String timeText, String location, File cacheFile, Uri downloadUri) {
            this.action = action;
            this.fileName = fileName;
            this.patientName = patientName;
            this.timeText = timeText;
            this.location = location;
            this.cacheFile = cacheFile;
            this.downloadUri = downloadUri;
            this.zone = "";
        }
    }

    private static final class CameraSnapshot {
        String fileName;
        String timeText;
        File cacheFile;
        Uri downloadUri;

        CameraSnapshot(String fileName, String timeText, File cacheFile, Uri downloadUri) {
            this.fileName = fileName;
            this.timeText = timeText;
            this.cacheFile = cacheFile;
            this.downloadUri = downloadUri;
        }
    }

    /** 已经保存到系统相册的一次录像。 */
    private static final class CameraRecording {
        String fileName;
        String timeText;
        Uri uri;

        CameraRecording(String fileName, String timeText, Uri uri) {
            this.fileName = fileName;
            this.timeText = timeText;
            this.uri = uri;
        }
    }

    private final MainActivity activity;
    private final BleManager bleManager;
    private final Am4100Manager am4100Manager;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<ClickZone> clickZones = new ArrayList<>();
    private final ArrayList<Organization> organizations = new ArrayList<>();
    private final DeviceProfile deviceProfile = new DeviceProfile();
    private final ArrayList<PatientCase> cases = new ArrayList<>();
    private final ArrayList<PdfRecord> pdfRecords = new ArrayList<>();
    private final ArrayList<CameraSnapshot> cameraSnapshots = new ArrayList<>();
    private final ArrayList<CameraRecording> cameraRecordings = new ArrayList<>();
    private final String[] tabs = {"ICU状态", "主机控制", "实时监护", "摄像监控", "治疗记录", "PDF文件", "使用教程"};
    private static final int MONITOR_TREND_ALL = -1;
    private static final int MONITOR_TREND_HEART = 0;
    private static final int MONITOR_TREND_SPO2 = 1;
    private static final int MONITOR_TREND_PULSE = 2;
    private static final int MONITOR_TREND_BP = 3;
    private static final int MONITOR_TREND_TEMP = 4;
    private static final int MONITOR_TREND_RESP = 5;
    private static final int MONITOR_MODE_LIVE = 0;
    private static final int MONITOR_MODE_HISTORY = 1;
    private static final int MONITOR_MODE_SETTINGS = 2;
    private static final int LIMIT_HEART = 0;
    private static final int LIMIT_BP_SYS = 1;
    private static final int LIMIT_BP_DIA = 2;
    private static final int LIMIT_PULSE = 3;
    private static final int LIMIT_SPO2 = 4;
    private static final int LIMIT_TEMP = 5;
    private static final int LIMIT_RESP = 6;

    private boolean showingSettings;
    private boolean cameraPlayback;
    private int activeTab;
    private int monitorMode = MONITOR_MODE_LIVE;
    private int selectedMonitorTrend = MONITOR_TREND_ALL;
    private int selectedOrganizationIndex;
    private int selectedCaseIndex;
    private int selectedCameraSnapshotIndex = -1;
    /** 最近一次请求下发的模式，不是主机 active 真值。 */
    private int selectedHostModeIndex = -1;
    private int lastHostModeIndex = -1;
    private final RectF am4100DeviceListBounds = new RectF();
    private float am4100DeviceScrollY;
    private float am4100DeviceMaxScrollY;
    private final RectF bleDeviceListBounds = new RectF();
    private float bleDeviceScrollY;
    private float bleDeviceMaxScrollY;
    private float touchDownY;
    private float lastTouchY;
    private boolean draggingAm4100DeviceList;
    private boolean draggingBleDeviceList;
    private boolean touchMoved;
    private boolean monitorRefreshPending;
    private String searchKeyword = "";
    private String accountName = "";
    private String treatmentPeriodMode = PERIOD_ALL;
    /** 自定义时段窗口：开始/结束时间文本，未设置时为空。 */
    private String treatmentCustomStart = "";
    private String treatmentCustomEnd = "";
    /**
     * 监护仪采样缓冲按“舱 + 宠物”各留一份：
     * 换舱要换走，换同一舱里的另一只宠物同样要换走。
     */
    private final Map<String, Am4100Manager.MonitorSnapshot> monitorSnapshots = new HashMap<>();
    /** 旧版本（未分舱）数据的归属舱位：升级时停留的那个舱，旧数据只归它所有。 */
    private final String legacyDataZone;
    /**
     * 舱位数据同时被 UI 线程和网页桥接线程访问：网页通过 JavascriptInterface 取状态时
     * 跑在 WebView 的桥接线程上。换舱、清空、落库必须串行，
     * 否则换舱过程中的中间态会被写进另一个舱的存档。
     */
    private final Object zoneStateLock = new Object();
    /** 采样请求已排队：网页每隔一秒多就会取一次状态，这里合并成一次采样。 */
    private volatile boolean treatmentSampleScheduled;
    /** 监护蓝牙上一次采样时是否连接，用于捕捉“连接→断开”沿并结算波形项结束时间。 */
    private boolean lastMonitorConnectedForWaveEnd;
    private File lastGeneratedPdf;
    private float density;
    private boolean monitorPulseBeep = true;
    private boolean monitorAlarmSound = true;
    private boolean monitorAutoNibp;
    private String cameraStreamUrl = "";
    private CameraPreviewHost cameraPreviewHost;
    private final boolean[] monitorLimitEnabled = {true, true, true, true, true, true, true};
    private final float[] monitorLimitHigh = {120f, 140f, 90f, 120f, 100f, 38f, 40f};
    private final float[] monitorLimitLow = {60f, 90f, 60f, 60f, 94f, 35f, 8f};

    /** 一次超限报警：记录是哪个参数、当前值多少、超的是上限还是下限。 */
    private static final class MonitorAlarm {
        final int limitIndex;
        final String name;
        final String value;
        final String unit;
        final boolean high;
        final String text;

        MonitorAlarm(int limitIndex, String name, String value, String unit, boolean high, String text) {
            this.limitIndex = limitIndex;
            this.name = name;
            this.value = value;
            this.unit = unit;
            this.high = high;
            this.text = text;
        }
    }

    /** 红色报警状态每秒巡检；被动 Toast 按事件提醒，不按时间重复。 */
    private static final long MONITOR_ALARM_INTERVAL_MS = 1000L;
    private final Handler monitorAlarmHandler = new Handler(Looper.getMainLooper());
    private final MonitorAlarmTracker monitorAlarmTracker =
            new MonitorAlarmTracker(monitorLimitEnabled.length);
    private String monitorAlarmSignature = "";
    /** 已 show 的 Toast 仍可能排在系统队列里；断连时必须显式取消这些旧体征提醒。 */
    private final ArrayList<Toast> monitorAlarmToasts = new ArrayList<>();
    private boolean monitorAlarmWatchRunning;
    private final Runnable monitorAlarmRunnable = new Runnable() {
        @Override
        public void run() {
            if (!monitorAlarmWatchRunning) {
                return;
            }
            checkMonitorAlarms();
            monitorAlarmHandler.postDelayed(this, MONITOR_ALARM_INTERVAL_MS);
        }
    };

    private final int navy = Color.rgb(82, 166, 242);
    private final int page = Color.rgb(229, 239, 251);
    private final int sidebar = Color.rgb(86, 166, 242);
    private final int panel = Color.WHITE;
    private final int border = Color.rgb(218, 229, 243);
    private final int text = Color.rgb(64, 72, 86);
    private final int muted = Color.rgb(124, 138, 154);
    private final int green = Color.rgb(27, 206, 138);
    private final int orange = Color.rgb(247, 172, 38);
    private final int red = Color.rgb(240, 84, 92);
    private final int blue = Color.rgb(0, 132, 235);
    private final int softBlue = Color.rgb(238, 246, 255);
    private final int activeBlue = Color.rgb(0, 132, 235);
    private final int deepPanel = Color.rgb(28, 28, 28);
    private final Runnable monitorRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            monitorRefreshPending = false;
            if (!showingSettings && activeTab == 2 && (am4100Manager.isScanning() || am4100Manager.isConnected() || am4100Manager.hasVitals())) {
                invalidate();
            }
        }
    };

    public IcuDashboardView(MainActivity activity, BleManager bleManager, Am4100Manager am4100Manager) {
        super(activity);
        this.activity = activity;
        this.bleManager = bleManager;
        this.am4100Manager = am4100Manager;
        // 旧版本的病例、机构、护理模式等数据没有分舱，统一归到升级时停留的那个舱。
        // 迁移时只能写进这一个舱，否则左右舱会显示同一份数据。
        this.legacyDataZone = bleManager != null && ZONE_LEFT.equals(bleManager.getCurrentZone())
                ? ZONE_LEFT : ZONE_RIGHT;
        // 先定下当前账号：病例、机构、护理模式这些数据按账号分文件存放，
        // 载入的时候必须知道载入的是谁的。
        this.accountName = AccountStore.currentUser(activity);
        seedOrganizations();
        // 先恢复病例：宠物标识在病例里，按宠物存放的门限与快照要靠它对号入座
        loadTreatmentRecordsFromStorage();
        // 冷启动恢复当前病例的会话波形、趋势和测量历史；实时窗口由 codec 留空。
        synchronized (zoneStateLock) {
            restoreMonitorSnapshot(softwareZone());
        }
        loadUiSettings();
        loadCameraSettings();
        loadCameraSnapshotsFromStorage();
        setFocusable(true);
        textPaint.setSubpixelText(true);
    }

    void setCameraPreviewHost(CameraPreviewHost cameraPreviewHost) {
        this.cameraPreviewHost = cameraPreviewHost;
    }

    String buildFirstPhaseStateJson() {
        // 桥接线程只读状态：要写盘的采样统一回 UI 线程做，
        // 否则换舱瞬间桥接线程可能把中间态落到别的舱里。
        scheduleTreatmentSample();
        synchronized (zoneStateLock) {
            return buildFirstPhaseStateJsonLocked();
        }
    }

    /** 打印/下载结果回推给前端的序号：每产生一条新状态就自增，前端靠它判断是不是新消息。 */
    private int printStatusSeq;
    private boolean printStatusOk;
    private String printStatusMsg = "";
    private String printStatusPhase = "";
    private String printStatusFileName = "";
    private String printStatusUri = "";
    private long printStatusElapsedMs;
    private long printStatusRequestId;
    private String printStatusStage = "";
    private long pdfDownloadRequestedAtMs;
    private static final long PDF_DOWNLOAD_QUEUE_TIMEOUT_MS = 30000L;
    private final PdfDownloadRequestTracker pdfDownloadRequests = new PdfDownloadRequestTracker();
    private Runnable pdfDownloadTimeoutRunnable;
    private long pdfDownloadTimeoutRequestId;

    private String buildFirstPhaseStateJsonLocked() {
        try {
            JSONObject root = new JSONObject();
            root.put("zone", softwareZone());
            root.put("organization", organizationToJson(currentOrganization()));
            root.put("deviceProfile", deviceProfileToJson());
            root.put("accountName", accountName);
            PatientCase current = currentCase();
            root.put("patient", patientToJson(current));
            root.put("cases", casesToJson());
            root.put("selectedCaseIndex", filteredCaseIndex(current));
            root.put("treatments", treatmentsToJson(currentCase()));
            root.put("hostModes", hostModesToJson());
            root.put("treatmentPeriodMode", treatmentPeriodMode);
            root.put("monitorSettings", monitorSettingsToJson());
            root.put("ble", dashboardBleStateToJson());
            JSONObject printStatus = new JSONObject();
            printStatus.put("seq", printStatusSeq);
            printStatus.put("ok", printStatusOk);
            printStatus.put("msg", printStatusMsg);
            printStatus.put("phase", printStatusPhase);
            printStatus.put("fileName", printStatusFileName);
            printStatus.put("uri", printStatusUri);
            printStatus.put("elapsedMs", printStatusElapsedMs);
            printStatus.put("requestId", printStatusRequestId);
            printStatus.put("stage", printStatusStage);
            root.put("printStatus", printStatus);
            PrinterSettings printerSettings = PrinterSettings.load(activity);
            JSONObject printerJson = new JSONObject();
            printerJson.put("address", printerSettings.getAddress());
            // 打印方式只剩直连一种，状态里固定回报 direct，前端不用再区分分支。
            printerJson.put("mode", PrinterSettings.MODE_DIRECT);
            printerJson.put("copies", printerSettings.getCopies());
            printerJson.put("describe", printerSettings.describe());
            root.put("printerSettings", printerJson);
            return root.toString();
        } catch (JSONException exception) {
            Log.e(TAG, "buildFirstPhaseStateJson failed", exception);
            return "{}";
        }
    }

    String buildPatientStateJson() {
        synchronized (zoneStateLock) {
            return buildPatientStateJsonLocked();
        }
    }

    private String buildPatientStateJsonLocked() {
        try {
            PatientCase patient = currentCase();
            return patient == null ? "{}" : patientToJson(patient).toString();
        } catch (JSONException exception) {
            return "{}";
        }
    }

    void syncTempPatientFromWeb(String payload) {
        synchronized (zoneStateLock) {
            syncTempPatientFromWebLocked(payload);
        }
    }

    private void syncTempPatientFromWebLocked(String payload) {
        if (TextUtils.isEmpty(payload)) {
            return;
        }
        try {
            JSONObject json = new JSONObject(payload);
            // 切舱瞬间页面手里可能还拿着另一个舱的快照，直接写回会把上一舱的数据落到当前舱。
            // 舱位必须带且与当前舱一致：不带舱位就无法判断这份数据属于哪个舱，
            // 宁可丢掉这次写回，也不能把另一个舱的档案落到当前舱。
            String zone = json.optString("zone", "");
            if (!zone.equals(softwareZone())) {
                return;
            }
            // 页面可能选中列表里的任意一条病例，必须写回同一条，不能覆盖当前病例
            PatientCase patient = resolveWebPatient(json.optString("id", ""));
            if (patient == null) {
                return;
            }
            // 三项必填：页面把空值写回来时整笔拒绝，避免把缺项档案落到原生。
            // 必须放在 resolveWebPatient 之后，切病例的动作不能被校验挡掉。
            if (isMissingRequiredField(json.optString("petName", ""))
                    || isMissingRequiredField(json.optString("recordNo", ""))
                    || isMissingRequiredField(json.optString("ownerPhone", ""))) {
                // 切病例可能在上面已经发生，画面仍要跟着刷新
                invalidate();
                return;
            }
            patient.petName = optNonEmpty(json, "petName", patient.petName);
            patient.species = optNonEmpty(json, "species", patient.species);
            patient.sex = optNonEmpty(json, "sex", patient.sex);
            patient.age = optNonEmpty(json, "age", patient.age);
            patient.ownerName = optNonEmpty(json, "ownerName", patient.ownerName);
            patient.ownerPhone = optNonEmpty(json, "ownerPhone", patient.ownerPhone);
            patient.doctor = optNonEmpty(json, "doctor", patient.doctor);
            // caseNo 是系统发号：只允许 nextCaseNumber() 生成，页面的病历号不能覆盖它。
            patient.recordNo = optNonEmpty(json, "recordNo", patient.recordNo);
            patient.visitDate = optNonEmpty(json, "visitDate", patient.visitDate);
            patient.note = optNonEmpty(json, "note", patient.note);
            patient.treatmentStartTime = optNonEmpty(json, "treatmentStartTime", patient.treatmentStartTime);
            patient.treatmentEndTime = optNonEmpty(json, "treatmentEndTime", patient.treatmentEndTime);
            if (json.has("currentTreatment")) {
                patient.currentTreatment = json.optBoolean("currentTreatment", patient.currentTreatment);
            }
            lastGeneratedPdf = null;
            saveTreatmentRecordsToStorage();
            // 三项补齐后重新评估计时资格：连着主机蓝牙才从这一刻起算。
            bindTreatmentClockToCurrentCase();
            invalidate();
        } catch (JSONException ignored) {
        }
    }

    boolean performNativeAction(String action) {
        return performNativeAction(action, null);
    }

    /**
     * 医院信息页“保存”按钮：把页内输入框的值写入当前机构并落库。
     * 返回给页面的提示文案，区分“未填写”与“已保存”。
     */
    String webUpdateOrganization(String name, String address, String phone) {
        Organization organization = currentOrganization();
        organization.name = name == null ? organization.name : name.trim();
        organization.address = address == null ? organization.address : address.trim();
        organization.phone = phone == null ? organization.phone : phone.trim();
        lastGeneratedPdf = null;
        saveUiSettings();
        activity.notifyLanhuStateChanged();
        invalidate();
        return "医院信息已保存";
    }

    /**
     * 仪器状态页“保存”按钮：把页内五个输入框的值写入设备档案并落库。
     * 清空产品型号会回落到默认机型，其余字段允许留空；
     * 所有字段都做长度收敛，避免异常长文本撑坏设置页布局。
     */
    String webUpdateDeviceProfile(String productModel, String machineType, String serialNo,
                                  String manufactureDate, String softwareVersion) {
        deviceProfile.productModel = cleanDeviceValue(productModel, DEFAULT_PRODUCT_MODEL, 64);
        deviceProfile.machineType = cleanDeviceValue(machineType, "", 64);
        deviceProfile.serialNo = cleanDeviceValue(serialNo, "", 64);
        deviceProfile.manufactureDate = cleanDeviceValue(manufactureDate, "", 32);
        // 程序版本：提交值与当前 App 版本号一致时视为“自动跟随”，
        // App 升级后自动显示新版本号；用户改过（含清空）则按提交值原样保存，
        // 保证页面上看到的就是实际生效的值。
        String submittedVersion = cleanDeviceValue(softwareVersion, "", 64);
        if (submittedVersion.equals(appVersionName())) {
            deviceProfile.softwareVersion = "";
            deviceProfile.softwareVersionFollowsApp = true;
        } else {
            deviceProfile.softwareVersion = submittedVersion;
            deviceProfile.softwareVersionFollowsApp = false;
        }
        saveUiSettings();
        activity.notifyLanhuStateChanged();
        invalidate();
        return "仪器信息已保存";
    }

    private static String cleanDeviceValue(String value, String fallback, int maxLength) {
        if (value == null) {
            return fallback;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return fallback;
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    /**
     * 处理页面发来的原生动作。text 是触发动作的控件文字，
     * 监护等级这类需要带参数的指令靠它取值。
     */
    boolean performNativeAction(String action, String text) {
        if (TextUtils.isEmpty(action)) {
            return false;
        }
        if ("tablet_zone_left".equals(action) || "tablet_zone_right".equals(action)) {
            String zone = "tablet_zone_right".equals(action) ? ZONE_RIGHT : ZONE_LEFT;
            if (!zone.equals(bleManager.getCurrentZone())) {
                switchSoftwareZone(zone);
            }
            invalidate();
            return true;
        }
        if ("select_current_treatment".equals(action)) {
            applySelectedCaseIndex(getCurrentTreatmentIndex());
            activeTab = 0;
            showingSettings = false;
            invalidate();
            return true;
        }
        if ("organization_switch".equals(action)) {
            showOrganizationDialog();
            return true;
        }
        if ("organization_edit".equals(action)) {
            // 医院信息页改为页内直接编辑：三个输入框就在页面上，
            // 这里不再弹原生对话框，避免与页内输入抢焦点。
            // 原生侧仍可通过“切换机构 → 编辑当前机构”进入弹窗编辑。
            return true;
        }
        if ("organization_logo_pick".equals(action)) {
            chooseOrganizationLogo();
            return true;
        }
        if ("account_menu".equals(action)) {
            handleAccountTap();
            return true;
        }
        if ("search_patient".equals(action)) {
            showSearchDialog();
            return true;
        }
        if ("patient_edit".equals(action)) {
            PatientCase patient = currentCase();
            if (patient != null) {
                showEditPatientDialog(patient);
            } else {
                Toast.makeText(activity, "暂无患者信息，无法编辑", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if ("patient_delete".equals(action)) {
            PatientCase patient = currentCase();
            if (patient != null) {
                confirmDeletePatient(patient);
            } else {
                Toast.makeText(activity, "暂无患者信息，无法删除", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if ("pdf_generate".equals(action)) {
            generatePdfAction();
            invalidate();
            return true;
        }
        if ("pdf_download".equals(action)) {
            downloadPdfAction();
            invalidate();
            return true;
        }
        if ("pdf_preview".equals(action)) {
            previewPdfAction();
            invalidate();
            return true;
        }
        if ("pdf_print".equals(action)) {
            printPdfAction();
            invalidate();
            return true;
        }
        if ("printer_settings".equals(action)) {
            PrinterSettingsDialog.show(activity);
            return true;
        }
        if ("monitor_level_set".equals(action)) {
            final String level = text == null ? "" : text.trim();
            sendBleCommand("监护等级 " + level, new BleCommand() {
                @Override
                public boolean send() {
                    return bleManager.setMonitorLevel(level);
                }
            });
            invalidate();
            return true;
        }
        if (action.startsWith("camera_feature")) {
            String feature = action.substring("camera_feature".length());
            showCameraFeatureToast(feature.startsWith("_") ? feature.substring(1) : feature);
            return true;
        }
        if ("camera_record".equals(action)) {
            toggleCameraRecording();
            return true;
        }
        if (action.startsWith("camera_snapshot_select_")) {
            selectCameraSnapshot(actionIndex(action, "camera_snapshot_select_"));
            return true;
        }
        if (action.startsWith("camera_recording_play_")) {
            playCameraRecording(actionIndex(action, "camera_recording_play_"));
            return true;
        }
        if ("settings_not_available".equals(action)) {
            Toast.makeText(activity, "该设置项在当前版本中暂未开放", Toast.LENGTH_SHORT).show();
            return true;
        }
        if ("sensor_readonly".equals(action)) {
            Toast.makeText(activity, "红外体温为实时监测值，不可设置", Toast.LENGTH_SHORT).show();
            return true;
        }
        if ("camera_preview_tap".equals(action) || "camera_open".equals(action)) {
            openTpLinkCameraPreview();
            return true;
        }
        if ("camera_config".equals(action)) {
            showTpLinkCameraDialog();
            return true;
        }
        if ("camera_snapshot".equals(action)) {
            captureCameraSnapshotAction();
            return true;
        }
        if ("camera_snapshot_download".equals(action)) {
            downloadSelectedCameraSnapshot();
            return true;
        }
        if ("camera_snapshot_delete".equals(action)) {
            deleteSelectedCameraSnapshot();
            return true;
        }
        if ("treatment_new".equals(action)) {
            createNewTreatmentRecord();
            activity.notifyLanhuStateChanged();
            invalidate();
            return true;
        }
        if ("treatment_finish".equals(action)) {
            finishCurrentTreatment();
            activity.notifyLanhuStateChanged();
            invalidate();
            return true;
        }
        if (action.startsWith("treatment_edit_")) {
            showTreatmentEntryDialogByAction(action);
            return true;
        }
        if (action.startsWith("treatment_period_")) {
            setTreatmentPeriodMode(action);
            return true;
        }
        if ("monitor_history".equals(action)) {
            activeTab = 2;
            monitorMode = MONITOR_MODE_HISTORY;
            showingSettings = false;
            invalidate();
            return true;
        }
        if ("monitor_limits".equals(action)) {
            activeTab = 2;
            monitorMode = MONITOR_MODE_SETTINGS;
            showingSettings = false;
            invalidate();
            return true;
        }
        if ("monitor_volume".equals(action)) {
            boolean next = !(monitorPulseBeep || monitorAlarmSound);
            monitorPulseBeep = next;
            monitorAlarmSound = next;
            saveUiSettings();
            if (next) {
                playMonitorAlarmSound();
            }
            Toast.makeText(activity, monitorPulseBeep ? "监护音量已开启" : "监护音量已关闭", Toast.LENGTH_SHORT).show();
            activity.notifyLanhuStateChanged();
            invalidate();
            return true;
        }
        if ("monitor_alarm_sound".equals(action)) {
            monitorAlarmSound = !monitorAlarmSound;
            saveUiSettings();
            if (monitorAlarmSound) {
                playMonitorAlarmSound();
            }
            Toast.makeText(activity, monitorAlarmSound ? "报警音已开启" : "报警音已关闭", Toast.LENGTH_SHORT).show();
            activity.notifyLanhuStateChanged();
            invalidate();
            return true;
        }
        if ("monitor_alarm_toggle".equals(action)) {
            monitorAlarmSound = !monitorAlarmSound;
            saveUiSettings();
            if (monitorAlarmSound) {
                playMonitorAlarmSound();
            }
            Toast.makeText(activity, monitorAlarmSound ? "报警音已开启" : "报警音已关闭", Toast.LENGTH_SHORT).show();
            activity.notifyLanhuStateChanged();
            invalidate();
            return true;
        }
        if ("monitor_pulse_beep".equals(action)) {
            monitorPulseBeep = !monitorPulseBeep;
            saveUiSettings();
            Toast.makeText(activity, monitorPulseBeep ? "脉搏音已开启" : "脉搏音已关闭", Toast.LENGTH_SHORT).show();
            activity.notifyLanhuStateChanged();
            invalidate();
            return true;
        }
        if (action.startsWith("monitor_limit_edit_")) {
            int limitIndex = -1;
            try {
                limitIndex = Integer.parseInt(action.substring("monitor_limit_edit_".length()));
            } catch (NumberFormatException ignored) {
            }
            if (limitIndex >= 0 && limitIndex < monitorLimitEnabled.length) {
                showMonitorLimitDialog(limitIndex);
                return true;
            }
            Toast.makeText(activity, "未知的监护上下限项", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (action.startsWith("tutorial_")) {
            showTutorialAction(action);
            return true;
        }
        if ("host_ble".equals(action)) {
            showingSettings = true;
            invalidate();
            return true;
        }
        if ("monitor_ble".equals(action)) {
            activeTab = 2;
            showingSettings = false;
            invalidate();
            return true;
        }
        if (action.startsWith("host_mode_config_")) {
            showHostModeDialog(hostModeIndexFromAction(action));
            return true;
        }
        if (action.startsWith("host_mode_")) {
            toggleHostMode(action);
            return true;
        }
        return performHostControlAction(action);
    }

    boolean isCameraConfiguredForWeb() {
        return !TextUtils.isEmpty(cameraStreamUrl);
    }

    String[] getCameraPreviewUrlsForWeb() {
        if (TextUtils.isEmpty(cameraStreamUrl)) {
            return new String[0];
        }
        return buildTpLinkRtspCandidates(cameraStreamUrl);
    }

    private boolean performHostControlAction(String action) {
        if ("control_temp".equals(action)) {
            showNumberDialog(0, "设置舱内温度", "℃", bleManager.getCabinTempValue(), false);
            return true;
        }
        if ("control_oxygen".equals(action)) {
            showNumberDialog(1, "设置氧浓度", "%", bleManager.getOxygenValue(), false);
            return true;
        }
        if ("control_co2".equals(action)) {
            showCo2ControlDialog();
            return true;
        }
        if ("control_time".equals(action)) {
            Toast.makeText(activity, "治疗时长由治疗过程自动累计，暂不支持设置", Toast.LENGTH_SHORT).show();
            return true;
        }
        boolean timeSetting = action.endsWith("_time");
        String baseAction = timeSetting ? action.substring(0, action.length() - "_time".length()) : action;
        if ("control_humidity".equals(baseAction)) {
            bleManager.noteHumidityReadOnlyRequest();
            Toast.makeText(activity, "湿度仅接收主机数值，不能设置", Toast.LENGTH_SHORT).show();
            return true;
        }
        int index = -1;
        String title = "";
        if ("control_red".equals(baseAction)) {
            index = 6;
            title = "红外理疗";
        } else if ("control_blue".equals(baseAction)) {
            index = 7;
            title = "蓝光理疗";
        } else if ("control_uv".equals(baseAction)) {
            index = 12;
            title = "紫外消毒";
        } else if ("control_nebulizer".equals(baseAction)) {
            index = 10;
            title = "雾化器";
        } else if ("control_anion".equals(baseAction)) {
            index = 11;
            title = "负离子";
        } else if ("control_cold_light".equals(baseAction)) {
            index = 4;
            title = "冷光照明";
        } else if ("control_warm_light".equals(baseAction)) {
            index = 5;
            title = "暖光照明";
        } else if ("control_outer".equals(baseAction)) {
            index = 8;
            title = "外循环";
        } else if ("control_inner".equals(baseAction)) {
            index = 9;
            title = "内循环";
        } else if ("control_time".equals(baseAction)) {
            index = 15;
            title = "治疗时长";
        }
        if (index < 0) {
            return false;
        }
        if (timeSetting) {
            handleControlButtonTap(index, title);
        } else {
            handleControlCardTap(index, title);
        }
        invalidate();
        return true;
    }

    private void showCo2ControlDialog() {
        new AlertDialog.Builder(activity)
                .setTitle("CO2浓度")
                .setItems(new String[]{"设置CO2目标值", "读取当前CO2", "切换CO2开关"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            showNumberDialog(3, "设置CO2目标值", "PPM", bleManager.getCo2Value(), true);
                            return;
                        }
                        final int choice = which;
                        sendBleCommand(choice == 1 ? "读取CO2" : "CO2开关", new BleCommand() {
                            @Override
                            public boolean send() {
                                return choice == 1 ? bleManager.sendControlPreset(3) : bleManager.toggleCo2Enable();
                            }
                        });
                        invalidate();
                    }
                })
                .show();
    }

    private void showTreatmentEntryDialogByAction(String action) {
        PatientCase patient = currentCase();
        if (patient == null) {
            return;
        }
        try {
            int index = Integer.parseInt(action.substring("treatment_edit_".length()));
            if (index >= 0 && index < patient.treatmentEntries.size()) {
                showTreatmentEntryDialog(patient, patient.treatmentEntries.get(index));
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void setTreatmentPeriodMode(String action) {
        if ("treatment_period_enabled".equals(action)) {
            treatmentPeriodMode = PERIOD_ENABLED;
        } else if ("treatment_period_custom".equals(action)) {
            treatmentPeriodMode = PERIOD_CUSTOM;
        } else {
            treatmentPeriodMode = PERIOD_ALL;
        }
        saveUiSettings();
        activity.notifyLanhuStateChanged();
        Toast.makeText(activity, "已切换治疗记录时段: " + treatmentPeriodModeText(), Toast.LENGTH_SHORT).show();
        invalidate();
        if (PERIOD_CUSTOM.equals(treatmentPeriodMode)) {
            // 自定义时段必须给出时间窗口，否则筛选出来的还是全时段数据
            showTreatmentCustomWindowDialog();
        }
    }

    /**
     * 自定义时段的时间窗口：开始与结束都按 "yyyy-MM-dd HH:mm:ss" 填写，
     * 结束必须晚于开始。窗口按舱保存，和治疗记录本身的数据隔离口径一致。
     */
    private void showTreatmentCustomWindowDialog() {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dpInt(12);
        form.setPadding(pad, pad, pad, pad);
        final EditText start = addInput(form, "开始时间", treatmentCustomStart, InputType.TYPE_CLASS_TEXT);
        final EditText end = addInput(form, "结束时间", treatmentCustomEnd, InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(activity)
                .setTitle("自定义时段")
                .setMessage("格式：yyyy-MM-dd HH:mm:ss。留空表示按每条记录自己填写的时间统计。")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String nextStart = start.getText().toString().trim();
                        String nextEnd = end.getText().toString().trim();
                        long startMs = parseTimeTextMs(nextStart);
                        long endMs = parseTimeTextMs(nextEnd);
                        if (TextUtils.isEmpty(nextStart) != TextUtils.isEmpty(nextEnd)) {
                            Toast.makeText(activity, "开始与结束时间要一起填写", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!TextUtils.isEmpty(nextStart) && (startMs <= 0L || endMs <= startMs)) {
                            Toast.makeText(activity, "时间格式不对，或结束时间早于开始时间", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        treatmentCustomStart = nextStart;
                        treatmentCustomEnd = nextEnd;
                        saveUiSettings();
                        activity.notifyLanhuStateChanged();
                        invalidate();
                        Toast.makeText(activity, "自定义时段已保存", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private String treatmentPeriodModeText() {
        if (PERIOD_ENABLED.equals(treatmentPeriodMode)) {
            return "功能开启时段";
        }
        if (PERIOD_CUSTOM.equals(treatmentPeriodMode)) {
            return "自定义时段";
        }
        return "全时段";
    }

    /** 动作名到模式下标的映射：配置动作与下发动作共用同一套下标。 */
    private int hostModeIndexFromAction(String action) {
        if ("host_mode_mother".equals(action) || "host_mode_config_mother".equals(action)) {
            return 0;
        }
        if ("host_mode_postop".equals(action) || "host_mode_config_postop".equals(action)) {
            return 1;
        }
        if ("host_mode_cardio".equals(action) || "host_mode_config_cardio".equals(action)) {
            return 2;
        }
        if ("host_mode_custom".equals(action) || "host_mode_config_custom".equals(action)) {
            return HOST_MODE_CUSTOM_INDEX;
        }
        return -1;
    }

    /** 打开某个护理模式的配置弹窗：在这里自定义这个模式要打开、关闭哪些内容。 */
    private void showHostModeDialog(int modeIndex) {
        final int index = clampIndex(modeIndex, hostModeNames().length);
        final JSONObject config = loadHostModeConfig(index);
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dpInt(12);
        form.setPadding(pad, pad, pad, pad);

        TextView help = new TextView(activity);
        help.setText("勾选这个模式需要自动打开或关闭的内容；保存后点击模式开关即可一键下发。");
        help.setTextSize(13);
        help.setPadding(0, 0, 0, dpInt(8));
        form.addView(help);

        addSectionLabel(form, "基础参数");
        final EditText[] numberInputs = new EditText[CUSTOM_NUMBER_KEYS.length];
        for (int i = 0; i < CUSTOM_NUMBER_KEYS.length; i++) {
            int inputType = InputType.TYPE_CLASS_NUMBER | (CUSTOM_NUMBER_INTEGER[i] ? 0 : InputType.TYPE_NUMBER_FLAG_DECIMAL);
            numberInputs[i] = addInput(form, CUSTOM_NUMBER_LABELS[i], customNumberText(config, CUSTOM_NUMBER_KEYS[i], currentCustomNumber(CUSTOM_NUMBER_INDICES[i]), CUSTOM_NUMBER_INTEGER[i]), inputType);
        }

        addSectionLabel(form, "功能开关");
        final CheckBox[] switchInputs = new CheckBox[CUSTOM_SWITCH_KEYS.length];
        for (int i = 0; i < CUSTOM_SWITCH_KEYS.length; i++) {
            switchInputs[i] = addCheckBox(form, CUSTOM_SWITCH_LABELS[i], customBoolean(config, CUSTOM_SWITCH_KEYS[i], CUSTOM_SWITCH_INDICES[i]));
        }

        addSectionLabel(form, "功能时间");
        final EditText[] timeInputs = new EditText[CUSTOM_TIME_KEYS.length];
        for (int i = 0; i < CUSTOM_TIME_KEYS.length; i++) {
            timeInputs[i] = addInput(form, CUSTOM_TIME_LABELS[i], customNumberText(config, CUSTOM_TIME_KEYS[i], bleManager.getControlTimeValue(CUSTOM_TIME_INDICES[i]), true), InputType.TYPE_CLASS_NUMBER);
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(form);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(hostModeNames()[index] + "设置")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setNeutralButton("仅保存", null)
                .setPositiveButton("保存并开启", null)
                .show();
        // 接管按钮点击：校验失败时保留弹窗和已经填写的内容。
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (saveHostModeForm(index, numberInputs, switchInputs, timeInputs, false)) {
                    dialog.dismiss();
                }
            }
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (saveHostModeForm(index, numberInputs, switchInputs, timeInputs, true)) {
                    dialog.dismiss();
                }
            }
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
    }

    private void addSectionLabel(LinearLayout form, String text) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextSize(15);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setPadding(0, dpInt(10), 0, dpInt(4));
        form.addView(label);
    }

    private CheckBox addCheckBox(LinearLayout form, String text, boolean checked) {
        CheckBox checkBox = new CheckBox(activity);
        checkBox.setText(text);
        checkBox.setTextSize(15);
        checkBox.setChecked(checked);
        form.addView(checkBox);
        return checkBox;
    }

    /** 每个模式一套配置；自定义模式沿用旧键，保证升级后已保存的配置不丢。 */
    private String hostModeConfigKey(int modeIndex) {
        if (modeIndex == HOST_MODE_CUSTOM_INDEX) {
            return petKey(KEY_CUSTOM_HOST_MODE_CONFIG);
        }
        return petKey(KEY_HOST_MODE_CONFIG_PREFIX
                + hostModeValues()[clampIndex(modeIndex, hostModeValues().length)]);
    }

    private JSONObject loadHostModeConfig(int modeIndex) {
        SharedPreferences prefs = accountPrefs();
        String raw = getPetString(prefs, hostModeConfigBaseKey(modeIndex), "");
        if (TextUtils.isEmpty(raw)) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw);
        } catch (JSONException exception) {
            return new JSONObject();
        }
    }

    private void saveHostModeConfig(int modeIndex, JSONObject config) {
        accountPrefs()
                .edit()
                .putString(hostModeConfigKey(modeIndex), config.toString())
                .apply();
    }

    /** 护理模式配置的落库基键；实际落库时再加上舱位后缀。 */
    private String hostModeConfigBaseKey(int modeIndex) {
        if (modeIndex == HOST_MODE_CUSTOM_INDEX) {
            return KEY_CUSTOM_HOST_MODE_CONFIG;
        }
        return KEY_HOST_MODE_CONFIG_PREFIX + hostModeValues()[clampIndex(modeIndex, hostModeValues().length)];
    }

    /** 四个模式仍分别保存配置；enabled 由统一四选一写入，旧版多开存档只解析出一个可见模式。 */
    private String hostModeEnabledKey(int modeIndex) {
        if (modeIndex == HOST_MODE_CUSTOM_INDEX) {
            return petKey("host_mode_enabled_custom");
        }
        return petKey(KEY_HOST_MODE_ENABLED_PREFIX + hostModeValues()[clampIndex(modeIndex, hostModeValues().length)]);
    }

    private boolean isHostModeEnabled(int modeIndex) {
        return accountPrefs().getBoolean(hostModeEnabledKey(modeIndex), false);
    }

    private int confirmedHostModeIndex() {
        String confirmed = bleManager.getConfirmedCareMode(bleManager.getCurrentZone());
        for (int i = 0; i < hostModeValues().length; i++) {
            if (hostModeValues()[i].equals(confirmed)) {
                return i;
            }
        }
        return -1;
    }

    /** 兼容旧版同时存下多个 true：显示和点击判断都只使用一个确定的模式。 */
    private int visibleEnabledHostModeIndex() {
        boolean[] enabled = new boolean[hostModeNames().length];
        for (int i = 0; i < enabled.length; i++) {
            enabled[i] = isHostModeEnabled(i);
        }
        return HostModeSelectionPolicy.visibleEnabledIndex(
                enabled, confirmedHostModeIndex(), selectedHostModeIndex);
    }

    /** 一次性写入四选一结果，避免连续通知让 H5 看到中间的多开状态。 */
    private void setExclusiveHostModeEnabled(int enabledIndex) {
        SharedPreferences.Editor editor = accountPrefs().edit();
        for (int i = 0; i < hostModeNames().length; i++) {
            editor.putBoolean(hostModeEnabledKey(i), i == enabledIndex);
        }
        editor.apply();
        activity.notifyLanhuStateChanged();
    }

    /** 收集旧开启模式的配置；新模式同键值优先，传 -1 时收集全部旧模式用于关闭。 */
    private JSONObject buildExclusiveActivationConfig(int excludedIndex, JSONObject selected) {
        JSONObject[] activeConfigs = new JSONObject[hostModeNames().length];
        for (int i = 0; i < activeConfigs.length; i++) {
            if (i != excludedIndex && isHostModeEnabled(i)) {
                activeConfigs[i] = loadHostModeConfig(i);
            }
        }
        try {
            return HostModeSelectionPolicy.mergeActivationConfig(
                    selected, activeConfigs, CUSTOM_SWITCH_KEYS);
        } catch (JSONException exception) {
            return null;
        }
    }

    /** 点模式开关：四选一；开启另一个模式会关闭旧模式独有功能，各模式配置仍独立保留。 */
    private void toggleHostMode(String action) {
        int index = hostModeIndexFromAction(action);
        if (index < 0) {
            return;
        }
        if (visibleEnabledHostModeIndex() != index) {
            JSONObject activation = buildExclusiveActivationConfig(index, loadHostModeConfig(index));
            if (activation == null) {
                Toast.makeText(activity, hostModeNames()[index] + "配置读取失败，未下发", Toast.LENGTH_LONG).show();
                return;
            }
            // 整批成功入队后才保持开启；未连接或部分失败时四个按钮都不冒充已开启。
            applyHostModeConfig(index, activation, true, true);
        } else {
            // 正常四选一只会收集当前模式；旧版多开存档则一次关闭所有旧模式功能。
            JSONObject deactivation = buildExclusiveActivationConfig(-1, new JSONObject());
            if (deactivation == null) {
                Toast.makeText(activity, hostModeNames()[index] + "配置读取失败，未下发", Toast.LENGTH_LONG).show();
                return;
            }
            setExclusiveHostModeEnabled(-1);
            disableHostModeFunctions(index, deactivation);
        }
    }

    /**
     * 关闭本次收集到的模式功能：正常四选一等价于关闭当前模式；
     * 旧版多开存档会覆盖全部旧开启模式，本地配置保留，不请求新的模式标识。
     */
    private void disableHostModeFunctions(final int modeIndex, final JSONObject config) {
        final int index = clampIndex(modeIndex, hostModeNames().length);
        if (!bleManager.canSendCommand()) {
            Toast.makeText(activity, "正在申请蓝牙权限，授权后自动下发", Toast.LENGTH_SHORT).show();
            activity.requestBlePermissionsWithRetry(new Runnable() {
                @Override
                public void run() {
                    sendHostModeDisable(index, config);
                }
            });
            return;
        }
        sendHostModeDisable(index, config);
    }

    /** 构造“只关开关”的下发配置：仅带该模式原配置中出现过的开关键，且全部置 false。 */
    private void sendHostModeDisable(int index, JSONObject config) {
        JSONObject off = new JSONObject();
        try {
            for (int i = 0; i < CUSTOM_SWITCH_KEYS.length; i++) {
                String key = CUSTOM_SWITCH_KEYS[i];
                if (config.has(key) && !config.isNull(key)) {
                    off.put(key, false);
                }
            }
        } catch (JSONException ignored) {
            // 空配置时直接跳过下发
        }
        sendHostModeConfig(index, off, false, false);
    }

    private String customNumberText(JSONObject config, String key, Number fallback, boolean integerOnly) {
        if (config != null && config.has(key) && !config.isNull(key)) {
            return String.valueOf(config.opt(key));
        }
        if (fallback == null) {
            return "";
        }
        return integerOnly ? String.valueOf(fallback.intValue()) : trimNumber(fallback.floatValue());
    }

    private Number currentCustomNumber(int index) {
        if (index == 0) {
            return bleManager.getCabinTempValue();
        }
        if (index == 1) {
            return bleManager.getOxygenValue();
        }
        if (index == 3) {
            return bleManager.getCo2Value();
        }
        return null;
    }

    private boolean customBoolean(JSONObject config, String key, int index) {
        if (config != null && config.has(key) && !config.isNull(key)) {
            return config.optBoolean(key);
        }
        return bleManager.isControlOn(index);
    }

    private boolean saveHostModeForm(int modeIndex, EditText[] numberInputs, CheckBox[] switchInputs, EditText[] timeInputs, boolean apply) {
        try {
            JSONObject config = new JSONObject();
            for (int i = 0; i < CUSTOM_NUMBER_KEYS.length; i++) {
                String raw = numberInputs[i].getText().toString().trim();
                if (TextUtils.isEmpty(raw)) {
                    continue;
                }
                float value = Float.parseFloat(raw);
                String error = validateControlInput(CUSTOM_NUMBER_INDICES[i], value);
                if (!TextUtils.isEmpty(error)) {
                    Toast.makeText(activity, error, Toast.LENGTH_SHORT).show();
                    return false;
                }
                if (CUSTOM_NUMBER_INTEGER[i]) {
                    config.put(CUSTOM_NUMBER_KEYS[i], Math.round(value));
                } else {
                    config.put(CUSTOM_NUMBER_KEYS[i], value);
                }
            }
            for (int i = 0; i < CUSTOM_SWITCH_KEYS.length; i++) {
                config.put(CUSTOM_SWITCH_KEYS[i], switchInputs[i].isChecked());
            }
            for (int i = 0; i < CUSTOM_TIME_KEYS.length; i++) {
                String raw = timeInputs[i].getText().toString().trim();
                if (TextUtils.isEmpty(raw)) {
                    continue;
                }
                float value = Float.parseFloat(raw);
                String error = validateControlInput(CUSTOM_TIME_INDICES[i], value);
                if (!TextUtils.isEmpty(error)) {
                    Toast.makeText(activity, error, Toast.LENGTH_SHORT).show();
                    return false;
                }
                config.put(CUSTOM_TIME_KEYS[i], Math.round(value));
            }
            JSONObject activation = null;
            if (apply) {
                activation = buildExclusiveActivationConfig(modeIndex, config);
                if (activation == null) {
                    Toast.makeText(activity, hostModeNames()[modeIndex] + "配置读取失败，未保存或下发", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
            // 保存：整套设定只写进「当前护理模式」自己的配置档（App 本地），
            // 不写主机、不散落到各功能模块的设置里，主机不会响。
            saveHostModeConfig(modeIndex, config);
            if (apply) {
                // 保存并开启：四选一下发整套数值/开关/时间，最后再请求护理模式标识。
                applyHostModeConfig(modeIndex, activation, true, true);
            } else {
                Toast.makeText(activity, hostModeNames()[modeIndex] + "配置已保存", Toast.LENGTH_SHORT).show();
                invalidate();
            }
            return true;
        } catch (NumberFormatException exception) {
            Toast.makeText(activity, "请输入正确的数值", Toast.LENGTH_SHORT).show();
        } catch (JSONException exception) {
            Toast.makeText(activity, hostModeNames()[modeIndex] + "保存失败", Toast.LENGTH_SHORT).show();
        }
        return false;
    }

    /**
     * 应用某个护理模式：把该模式在 App 本地保存的整套数值、功能开关、
     * 功能时间连同模式标识，一次性连续下发给主机。
     *
     * <p>点主机控制页的模式开关图标、以及弹窗里的“保存并开启”，都走这里。</p>
     */
    private void applyHostModeConfig(final int modeIndex, final JSONObject config,
                                     final boolean changeCareMode, final boolean enableWhenComplete) {
        final int index = clampIndex(modeIndex, hostModeNames().length);
        if (!bleManager.canSendCommand()) {
            Toast.makeText(activity, "正在申请蓝牙权限，授权后自动下发", Toast.LENGTH_SHORT).show();
            activity.requestBlePermissionsWithRetry(new Runnable() {
                @Override
                public void run() {
                    sendHostModeConfig(index, config, changeCareMode, enableWhenComplete);
                }
            });
            return;
        }
        sendHostModeConfig(index, config, changeCareMode, enableWhenComplete);
    }

    /**
     * 把某个模式的整套设定发到主机上。
     *
     * <p>逐条下发数值、功能开关、功能时间，最后切换护理模式；整批期间
     * 不逐条回读、不重发、不插状态查询，让指令紧挨着连续送达，
     * 避免被拆成一串「滴、滴、滴…」，结束后统一刷新一次状态。</p>
     */
    private void sendHostModeConfig(int index, JSONObject config, boolean changeCareMode,
                                    boolean enableWhenComplete) {
        String[] names = hostModeNames();
        HostModeBatchProgress progress = new HostModeBatchProgress();
        boolean careModeEnqueued = false;
        bleManager.beginQuietBatch();
        try {
            // 时长必须先于开关，模式标识必须最后；前置失败就不进入不完整模式。
            // changeCareMode=false 仅用于关闭当前模式功能，不额外请求新的模式标识。
            applyHostModeNumbers(config, progress);
            boolean[] failedTimes = applyHostModeTimes(config, progress);
            applyHostModeSwitches(config, progress, failedTimes);
            if (changeCareMode) {
                if (progress.canSendCareMode()) {
                    careModeEnqueued = bleManager.setCareMode(hostModeValues()[index]);
                    progress.record(careModeEnqueued);
                } else {
                    progress.skip();
                }
            }
            // 关闭功能本来就没有护理模式标识这一条，不能把“不发送”计成未完成。
        } finally {
            bleManager.endQuietBatch();
        }
        if (enableWhenComplete) {
            int enabledIndex = HostModeSelectionPolicy.completedEnabledIndex(
                    index, true, progress.isComplete());
            setExclusiveHostModeEnabled(enabledIndex);
        }
        if (careModeEnqueued) {
            // 只有 set_care_mode 确实入队才记最近请求；前置失败不能虚记新模式。
            lastHostModeIndex = selectedHostModeIndex;
            selectedHostModeIndex = index;
            saveUiSettings();
        }
        if (progress.isComplete()) {
            Toast.makeText(activity, names[index] + "全部 " + progress.enqueued + "/"
                    + progress.attempted + " 条已排队，等待主机确认", Toast.LENGTH_LONG).show();
        } else if (progress.attempted == 0) {
            // 空配置没有产生任何新命令，不能把此前 cold_light 等旧错误冒充本次失败原因。
            Toast.makeText(activity, names[index] + (changeCareMode
                    ? "没有可下发的配置" : "没有需要关闭的功能"), Toast.LENGTH_LONG).show();
        } else if (progress.enqueued > 0) {
            Toast.makeText(activity, names[index] + "仅 " + progress.enqueued + "/"
                    + progress.attempted + " 条已排队，本次" + (changeCareMode ? "模式" : "功能")
                    + "未完整下发，请检查主机后重试", Toast.LENGTH_LONG).show();
        } else {
            String detail = TextUtils.isEmpty(bleManager.getLastError()) ? "请连接主机蓝牙后再操作" : bleManager.getLastError();
            Toast.makeText(activity, names[index] + "暂未下发：" + detail, Toast.LENGTH_LONG).show();
        }
        invalidate();
    }

    /** 下发模式里的数值参数：舱内温度、氧浓度、CO2；湿度和治疗时长只读。 */
    private void applyHostModeNumbers(JSONObject config, HostModeBatchProgress progress) {
        for (int i = 0; i < CUSTOM_NUMBER_KEYS.length; i++) {
            if (!config.has(CUSTOM_NUMBER_KEYS[i]) || config.isNull(CUSTOM_NUMBER_KEYS[i])) {
                continue;
            }
            int index = CUSTOM_NUMBER_INDICES[i];
            if (index == 0) {
                progress.record(bleManager.setTemperature((float) config.optDouble(CUSTOM_NUMBER_KEYS[i])));
            } else if (index == 1) {
                progress.record(bleManager.setOxygen((float) config.optDouble(CUSTOM_NUMBER_KEYS[i])));
            } else if (index == 3) {
                progress.record(bleManager.setCo2(config.optInt(CUSTOM_NUMBER_KEYS[i])));
            }
        }
    }

    /** 下发模式里的功能开关；对应时长入队失败的开启命令会被跳过。 */
    private void applyHostModeSwitches(JSONObject config, HostModeBatchProgress progress,
                                       boolean[] failedTimes) {
        for (int i = 0; i < CUSTOM_SWITCH_KEYS.length; i++) {
            if (!config.has(CUSTOM_SWITCH_KEYS[i]) || config.isNull(CUSTOM_SWITCH_KEYS[i])) {
                continue;
            }
            int index = CUSTOM_SWITCH_INDICES[i];
            boolean enabled = config.optBoolean(CUSTOM_SWITCH_KEYS[i]);
            if (enabled && index >= 0 && index < failedTimes.length && failedTimes[index]) {
                progress.skip();
                continue;
            }
            progress.record(bleManager.setControlEnabled(index, enabled));
        }
    }

    /** 下发模式里的功能时间，并记录哪些定时功能没能收到新时长。 */
    private boolean[] applyHostModeTimes(JSONObject config, HostModeBatchProgress progress) {
        boolean[] failed = new boolean[16];
        for (int i = 0; i < CUSTOM_TIME_KEYS.length; i++) {
            if (!config.has(CUSTOM_TIME_KEYS[i]) || config.isNull(CUSTOM_TIME_KEYS[i])) {
                continue;
            }
            int index = CUSTOM_TIME_INDICES[i];
            boolean enqueued = bleManager.setControlTime(index, config.optInt(CUSTOM_TIME_KEYS[i]));
            progress.record(enqueued);
            if (!enqueued && index >= 0 && index < failed.length) {
                failed[index] = true;
            }
        }
        return failed;
    }

    private void showTutorialAction(String action) {
        String title = "使用教程";
        String message = "教程内容已内置在当前页面，可按模块查看。";
        if ("tutorial_install_video".equals(action)) {
            title = "装机教程";
            message = "1. 检查舱体、电源线、监护设备和摄像头。\n2. 主机通电后确认蓝牙、WiFi和舱内照明状态。\n3. 在“连接状态”中连接主机蓝牙和监护蓝牙。\n4. 在摄像监控中录入RTSP地址并测试画面。";
        } else if ("tutorial_operation_video".equals(action)) {
            title = "使用操作指南";
            message = "1. 编辑患者信息并确认本次治疗记录。\n2. 连接主机蓝牙后设置温度、氧浓度、湿度、CO2和护理模式。\n3. 连接监护蓝牙后观察心率、血氧、血压、体温和波形。\n4. 治疗结束后生成并下载PDF报告。";
        } else if ("tutorial_manual".equals(action)) {
            title = "临床应用手册";
            message = "包含适用场景、母幼/术后/心肺/自定义护理模式、生命体征指标解释、常见报警处理和日常维护建议。当前版本以内置图文手册为准。";
        } else if ("tutorial_update".equals(action)) {
            title = "版本更新说明";
            message = "教程资料随APK内置发布；后台推送新版本后，使用教程页顶部会出现更新提示，确认后即可下载并替换本地教程资料。";
        }
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private JSONObject organizationToJson(Organization organization) throws JSONException {
        JSONObject json = new JSONObject();
        if (organization == null) {
            return json;
        }
        json.put("name", organization.name);
        json.put("address", organization.address);
        json.put("logoUri", organization.logoUri);
        json.put("logoName", organization.logoName.isEmpty() ? "LOGO" : organization.logoName);
        json.put("phone", organization.phone);
        return json;
    }

    /**
     * 仪器状态页的设备档案下发给页面。
     * 产品型号为空时回落到默认机型，程序版本为空时回落到当前 App 版本号。
     */
    private JSONObject deviceProfileToJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("productModel", firstNonEmpty(deviceProfile.productModel, DEFAULT_PRODUCT_MODEL));
        json.put("machineType", deviceProfile.machineType);
        json.put("serialNo", deviceProfile.serialNo);
        json.put("manufactureDate", deviceProfile.manufactureDate);
        json.put("softwareVersion", deviceProfile.softwareVersionFollowsApp
                ? appVersionName()
                : deviceProfile.softwareVersion);
        return json;
    }

    /**
     * 落库用的存档内容：保存用户原始填写值与“是否跟随 App 版本”的标记，
     * 而不是下发页面时的生效值，避免自动跟随的版本号被固化成旧版本。
     */
    private String deviceProfileToJsonString() {
        try {
            JSONObject json = new JSONObject();
            json.put("productModel", deviceProfile.productModel);
            json.put("machineType", deviceProfile.machineType);
            json.put("serialNo", deviceProfile.serialNo);
            json.put("manufactureDate", deviceProfile.manufactureDate);
            json.put("softwareVersion", deviceProfile.softwareVersion);
            json.put("softwareVersionFollowsApp", deviceProfile.softwareVersionFollowsApp);
            return json.toString();
        } catch (JSONException exception) {
            return "{}";
        }
    }

    /** 当前 App 版本号，作为“程序版本”的默认值。 */
    private String appVersionName() {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return info == null || info.versionName == null ? "" : info.versionName.trim();
        } catch (Exception exception) {
            return "";
        }
    }

    /**
     * 读取医院 LOGO 位图，用于 PDF 头部绘制。
     * 支持 content:// 与 file:// 两种来源，失败时返回 null，由调用方回退到文字占位。
     */
    private Bitmap loadLogoBitmap(String uriOrPath) {
        if (TextUtils.isEmpty(uriOrPath)) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            Bitmap probed = decodeImageWithOptions(uriOrPath, bounds);
            if (probed != null) {
                probed.recycle();
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) > 1600) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return decodeImageWithOptions(uriOrPath, options);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 按来源（content://、file://、裸路径）解码图片。
     */
    private Bitmap decodeImageWithOptions(String uriOrPath, BitmapFactory.Options options) {
        try {
            if (uriOrPath.startsWith("content://") || uriOrPath.startsWith("android.resource://")) {
                java.io.InputStream in = activity.getContentResolver().openInputStream(Uri.parse(uriOrPath));
                if (in == null) {
                    return null;
                }
                try {
                    return BitmapFactory.decodeStream(in, null, options);
                } finally {
                    in.close();
                }
            }
            if (uriOrPath.startsWith("file://")) {
                String path = Uri.parse(uriOrPath).getPath();
                return TextUtils.isEmpty(path) ? null : BitmapFactory.decodeFile(path, options);
            }
            return BitmapFactory.decodeFile(uriOrPath, options);
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject dashboardBleStateToJson() throws JSONException {
        JSONObject root = new JSONObject();
        JSONObject host = new JSONObject();
        host.put("connected", bleManager.isConnected());
        host.put("active", bleManager.isLinkActive());
        host.put("scanning", bleManager.isScanning());
        host.put("ready", bleManager.isDataReady());
        host.put("protocolReady", bleManager.isProtocolReady());
        host.put("state", safeText(bleManager.getConnectionStateText()));
        host.put("dataState", safeText(bleManager.getDataPathState()));
        host.put("dataError", safeText(bleManager.getDataPathError()));
        host.put("lastValidStateAt", bleManager.getLastValidHostStateAtMs());
        host.put("deviceName", safeText(bleManager.getConnectedDeviceName()));
        host.put("deviceId", safeText(bleManager.getConnectedDeviceId()));
        host.put("zone", safeText(bleManager.getCurrentZone()));
        host.put("temp", safeText(bleManager.getCabinTempText()));
        host.put("oxygen", safeText(bleManager.getOxygenText()));
        host.put("humidity", safeText(bleManager.getHumidityText()));
        host.put("co2", safeText(bleManager.getCo2Text()));
        host.put("infraredTemp", safeText(bleManager.getInfraredTempText()));
        host.put("treatmentTime", safeText(bleManager.getTreatmentTimeText()));
        host.put("lastSent", safeText(bleManager.getLastSent()));
        host.put("lastControlRequest", safeText(bleManager.getLastControlRequest()));
        host.put("lastControlRequestAt", bleManager.getLastControlRequestAtMs());
        host.put("lastControlSent", safeText(bleManager.getLastControlSent()));
        host.put("lastControlSentAt", bleManager.getLastControlSentAtMs());
        host.put("lastControlReply", safeText(bleManager.getLastControlReply()));
        host.put("lastControlReplyAt", bleManager.getLastControlReplyAtMs());
        host.put("controlReplyState", safeText(bleManager.getControlReplyState()));
        host.put("controlReplyError", safeText(bleManager.getControlReplyError()));
        host.put("currentWrite", safeText(bleManager.getCurrentWritePayload()));
        host.put("writeQueueSize", bleManager.getWriteQueueSize());
        host.put("lastReceived", safeText(bleManager.getLastReceived()));
        host.put("lastNotifyChunk", safeText(bleManager.getLastNotifyChunk()));
        host.put("lastNotifyChunkAt", bleManager.getLastNotifyChunkAtMs());
        host.put("lastCompleteFrameAt", bleManager.getLastCompleteFrameAtMs());
        host.put("notifyBufferChars", bleManager.getNotifyBufferLength());
        host.put("summary", safeText(bleManager.getLastParsedStatus()));
        host.put("lastError", safeText(bleManager.getLastError()));
        // 主机回报的监护等级：颜色是主机给的原始值，等级名是换算结果。
        // 页面优先用这两个字段拿监护等级，才能接住"机器 → App"方向的更新。
        host.put("statusLightColor", safeText(bleManager.getStatusLightColor()));
        host.put("monitorLevel", safeText(bleManager.getMonitorLevelLabel()));
        // 两套 host 状态 JSON 共用同一份诊断与确认快照，避免首页与蓝牙面板语义不一致。
        host.put("dataDiagVersion", BleManager.DATA_DIAG_VERSION);
        host.put("dataDiag", bleManager.buildDataDiagJson());
        host.put("controlConfirmations", bleManager.buildControlConfirmationJson());

        JSONObject controls = new JSONObject();
        putDashboardControl(controls, "temp", 0);
        putDashboardControl(controls, "oxygen", 1);
        putDashboardControl(controls, "statusLight", 2);
        putDashboardControl(controls, "co2", 3);
        putDashboardControl(controls, "coldLight", 4);
        putDashboardControl(controls, "warmLight", 5);
        putDashboardControl(controls, "redTherapy", 6);
        putDashboardControl(controls, "blueTherapy", 7);
        putDashboardControl(controls, "outerCycle", 8);
        putDashboardControl(controls, "innerCycle", 9);
        putDashboardControl(controls, "nebulizer", 10);
        putDashboardControl(controls, "anion", 11);
        putDashboardControl(controls, "uv", 12);
        putDashboardControl(controls, "o2Enabled", 13);
        putDashboardControl(controls, "humidity", 14);
        putDashboardControl(controls, "treatmentTime", 15);
        host.put("controls", controls);
        host.put("zones", buildEnvironmentZonesJson());

        JSONObject monitor = new JSONObject();
        monitor.put("connected", am4100Manager.isConnected());
        monitor.put("active", am4100Manager.isLinkActive());
        monitor.put("scanning", am4100Manager.isScanning());
        monitor.put("ready", am4100Manager.isProtocolReady());
        monitor.put("protocolReady", am4100Manager.isProtocolReady());
        monitor.put("state", safeText(am4100Manager.getStateText()));
        monitor.put("deviceName", safeText(am4100Manager.getConnectedDeviceName()));
        monitor.put("deviceId", safeText(am4100Manager.getConnectedDeviceId()));
        monitor.put("heartRate", safeText(am4100Manager.getHeartRateText()));
        monitor.put("bloodPressure", safeText(am4100Manager.getBloodPressureText()));
        monitor.put("map", safeText(am4100Manager.getMapText()));
        monitor.put("spo2", safeText(am4100Manager.getSpo2Text()));
        monitor.put("pulse", safeText(am4100Manager.getPulseRateText()));
        monitor.put("pulseRate", safeText(am4100Manager.getPulseRateText()));
        monitor.put("bodyTemp", safeText(am4100Manager.getBodyTempText()));
        monitor.put("resp", safeText(am4100Manager.getRespText()));
        monitor.put("summary", safeText(am4100Manager.getLastFrameSummary()));
        monitor.put("lastError", safeText(am4100Manager.getLastError()));
        monitor.put("waveStats", safeText(am4100Manager.getWaveDiagnosticsText()));

        root.put("host", host);
        root.put("monitor", monitor);
        return root;
    }

    private void putDashboardControl(JSONObject controls, String key, int index) throws JSONException {
        controls.put(key, safeText(bleManager.getControlValue(index)));
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
        environment.put("temp", safeText(bleManager.getCabinTempText(zone)));
        environment.put("oxygen", safeText(bleManager.getOxygenText(zone)));
        environment.put("humidity", safeText(bleManager.getHumidityText(zone)));
        environment.put("co2", safeText(bleManager.getCo2Text(zone)));
        environment.put("updatedAt", bleManager.getEnvironmentUpdatedAt(zone));
        environment.put("querySentAt", bleManager.getZoneStatusQuerySentAt(zone));
        environment.put("replyAt", bleManager.getZoneStatusReplyAt(zone));
        environment.put("validStateAt", bleManager.getZoneValidStateAt(zone));
        environment.put("lastFrame", safeText(bleManager.getZoneLastStatusFrame(zone)));
        return environment;
    }

    private JSONObject monitorSettingsToJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("mode", monitorMode == MONITOR_MODE_HISTORY ? "history" : (monitorMode == MONITOR_MODE_SETTINGS ? "settings" : "live"));
        json.put("pulseBeep", monitorPulseBeep);
        json.put("alarmSound", monitorAlarmSound);
        JSONArray limits = new JSONArray();
        for (int i = 0; i < monitorLimitEnabled.length; i++) {
            JSONObject item = new JSONObject();
            item.put("name", monitorLimitName(i));
            item.put("enabled", monitorLimitEnabled[i]);
            item.put("high", formatLimitValue(i, monitorLimitHigh[i]));
            item.put("low", formatLimitValue(i, monitorLimitLow[i]));
            item.put("unit", monitorLimitUnit(i));
            limits.put(item);
        }
        json.put("limits", limits);
        json.put("alarms", monitorAlarmJson());
        return json;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private boolean isPlaceholderPatientText(String value) {
        if (TextUtils.isEmpty(value)) {
            return true;
        }
        String clean = value.trim();
        if (TextUtils.isEmpty(clean) || "-".equals(clean) || "--".equals(clean) || "/".equals(clean)) {
            return true;
        }
        boolean allZero = clean.length() <= 6;
        for (int i = 0; i < clean.length(); i++) {
            if (clean.charAt(i) != '0') {
                allZero = false;
                break;
            }
        }
        return allZero
                || "\u5f85\u5f55\u5165".equals(clean)
                || "\u672a\u547d\u540d".equals(clean)
                || "\u672a\u77e5".equals(clean)
                || "null".equalsIgnoreCase(clean)
                || "undefined".equalsIgnoreCase(clean);
    }

    private boolean hasMeaningfulPatientData(PatientCase patient) {
        if (patient == null) {
            return false;
        }
        // 用户点「新建样本」产生的记录永远保留：新建下一份时上一份会结束治疗，
        // 若按「有没有录入信息」过滤，没填过字段的样本会被整条丢掉，
        // 页面看起来就只是病例编号换了一下，旧样本再也不会出现在下面。
        if (patient.explicitSample) {
            return true;
        }
        // 当前治疗即使还没填写患者信息也要保留，否则新建的空记录会从列表和存档里消失
        if (patient.currentTreatment) {
            return true;
        }
        return !isPlaceholderPatientText(patient.petName)
                || !isPlaceholderPatientText(patient.species)
                || !isPlaceholderPatientText(patient.ownerName)
                || !isPlaceholderPatientText(patient.ownerPhone)
                || !isPlaceholderPatientText(patient.doctor);
    }

    private JSONArray casesToJson() throws JSONException {
        JSONArray array = new JSONArray();
        for (PatientCase patient : cases) {
            if (!hasMeaningfulPatientData(patient)) {
                continue;
            }
            array.put(patientToJson(patient));
        }
        return array;
    }

    /**
     * 病例列表交给页面前会过滤掉没有信息的记录，页面按过滤后的顺序编号，
     * 这里返回过滤后的下标，保证页面选中的病例与 native 当前病例一致。
     */
    private int filteredCaseIndex(PatientCase target) {
        if (target == null) {
            return 0;
        }
        int seen = 0;
        for (PatientCase item : cases) {
            if (!hasMeaningfulPatientData(item)) {
                continue;
            }
            if (item == target) {
                return seen;
            }
            seen++;
        }
        return 0;
    }

    /** 按过滤后的下标取病例，页面上的样本 id 就是 native_<下标>。 */
    private PatientCase caseAtFilteredIndex(int index) {
        if (index < 0) {
            return null;
        }
        int seen = 0;
        for (PatientCase item : cases) {
            if (!hasMeaningfulPatientData(item)) {
                continue;
            }
            if (seen == index) {
                return item;
            }
            seen++;
        }
        return null;
    }

    /** 页面选中的是哪一条病例：native_<下标> 对应过滤后的顺序，其余走当前病例。 */
    private PatientCase resolveWebPatient(String id) {
        int index = -1;
        if (id != null && id.startsWith("native_")) {
            try {
                index = Integer.parseInt(id.substring("native_".length()).trim());
            } catch (NumberFormatException ignored) {
                index = -1;
            }
        }
        if (index < 0) {
            return currentCase();
        }
        PatientCase item = caseAtFilteredIndex(index);
        if (item == null) {
            return currentCase();
        }
        if (!canSwitchToCase(item)) {
            // 拒绝切换时返回 null：这次写回属于另一只宠物，
            // 落到当前病例上会把它的档案覆盖掉。
            return null;
        }
        applySelectedCaseIndex(cases.indexOf(item));
        return item;
    }

    /**
     * 病例编号只取当前舱剩余病例的最大有效数字编号 + 1。
     * 不重排旧病例、不补中间空号；删空后从 000001 开始，旧持久序号不参与发号。
     */
    private String nextCaseNumber() {
        List<String> caseNumbers = new ArrayList<>();
        for (PatientCase item : cases) {
            if (item != null) {
                caseNumbers.add(item.caseNo);
            }
        }
        return nextCaseNumberFrom(caseNumbers);
    }

    /** 独立纯计算便于单元测试；只读传入编号，绝不修改现有病例。 */
    static String nextCaseNumberFrom(Iterable<String> caseNumbers) {
        long largest = 0;
        if (caseNumbers != null) {
            for (String caseNo : caseNumbers) {
                if (caseNo == null) {
                    continue;
                }
                try {
                    long value = Long.parseLong(caseNo.trim());
                    if (value > largest && value < Long.MAX_VALUE) {
                        largest = value;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return String.format(Locale.US, "%06d", largest + 1);
    }

    private JSONObject patientToJson(PatientCase patient) throws JSONException {
        JSONObject json = new JSONObject();
        if (patient == null) {
            return json;
        }
        json.put("caseNo", patient.caseNo);
        json.put("monitorNo", patient.monitorNo);
        json.put("petName", patient.petName);
        json.put("species", patient.species);
        json.put("sex", patient.sex);
        json.put("age", patient.age);
        json.put("ownerName", patient.ownerName);
        json.put("ownerPhone", patient.ownerPhone);
        json.put("doctor", patient.doctor);
        json.put("recordNo", patient.recordNo);
        json.put("visitDate", patient.visitDate);
        json.put("note", patient.note);
        json.put("currentTreatment", patient.currentTreatment);
        json.put("explicitSample", patient.explicitSample);
        json.put("treatmentStartTime", patient.treatmentStartTime);
        json.put("treatmentEndTime", patient.treatmentEndTime);
        return json;
    }

    private JSONArray treatmentsToJson(PatientCase patient) throws JSONException {
        JSONArray array = new JSONArray();
        if (patient == null) {
            return array;
        }
        for (int i = 0; i < patient.treatmentEntries.size(); i++) {
            TreatmentEntry entry = patient.treatmentEntries.get(i);
            JSONObject json = new JSONObject();
            json.put("index", i + 1);
            json.put("itemName", entry.itemName);
            json.put("unit", entry.unit);
            // 历史病例只读自己的冻结状态，不能再显示此刻另一病例的全局设备开关。
            boolean featureOn = patient.currentTreatment ? treatmentFeatureOn(entry.itemName) : entry.featureOn;
            json.put("featureOn", featureOn);
            json.put("used", pdfEntryUsed(entry));
            boolean readingTracked = isReadingTrackedEntry(entry.itemName);
            json.put("readingTracked", readingTracked);
            json.put("hasReading", readingTracked && entryHasReading(patient, entry));
            json.put("enabled", entry.enabled);
            json.put("recordEnabled", entry.enabled);
            json.put("includeInReport", entry.includeInReport);
            json.put("startTime", entry.startTime);
            json.put("endTime", entry.endTime);
            json.put("manualValue", entry.manualValue);
            json.put("lastValue", entry.lastValue);
            TreatmentStats stats = treatmentStatsFor(entry);
            json.put("sampleCount", entry.sampleCount);
            json.put("periodSampleCount", stats.sampleCount);
            json.put("statsReliable", stats.sampleCount >= MIN_RELIABLE_SAMPLES);
            json.put("statsNote", stats.sampleCount >= MIN_RELIABLE_SAMPLES
                    ? "" : "样本不足(" + stats.sampleCount + "次)");
            json.put("periodLabel", treatmentPeriodLabel(entry));
            String usage = sessionUsageText(entry);
            json.put("usage", usage);
            // 会话类项目在“选取时段”列显示累计使用时长，其余显示时段口径
            json.put("period", TextUtils.isEmpty(usage) ? treatmentPeriodLabel(entry) : usage);
            json.put("average", treatmentAverageText(entry));
            json.put("high", treatmentHighText(entry));
            json.put("low", treatmentLowText(entry));
            array.put(json);
        }
        return array;
    }

    private JSONArray hostModesToJson() throws JSONException {
        JSONArray array = new JSONArray();
        String confirmed = bleManager.getConfirmedCareMode(bleManager.getCurrentZone());
        String pending = bleManager.getPendingCareMode(bleManager.getCurrentZone());
        int enabledIndex = visibleEnabledHostModeIndex();
        for (int i = 0; i < hostModeNames().length; i++) {
            JSONObject item = new JSONObject();
            item.put("name", hostModeNames()[i]);
            item.put("value", hostModeValues()[i]);
            item.put("active", hostModeValues()[i].equals(confirmed));
            item.put("pending", hostModeValues()[i].equals(pending));
            item.put("lastUsed", i == selectedHostModeIndex
                    && !hostModeValues()[i].equals(confirmed));
            item.put("configured", loadHostModeConfig(i).length() > 0);
            item.put("enabled", i == enabledIndex);
            array.put(item);
        }
        return array;
    }

    private String[] hostModeNames() {
        return new String[]{"母幼护理模式", "术后护理模式", "心肺护理模式", "自定义模式"};
    }

    private String[] hostModeValues() {
        return new String[]{"maternal_and_infant_care", "Postoperative_care", "Cardiopulmonary_care", "Custom_mode"};
    }

    private String selectedHostModeName() {
        String confirmed = bleManager.getConfirmedCareMode(bleManager.getCurrentZone());
        for (int i = 0; i < hostModeValues().length; i++) {
            if (hostModeValues()[i].equals(confirmed)) {
                return hostModeNames()[i];
            }
        }
        return "--";
    }

    private String optNonEmpty(JSONObject json, String key, String fallback) {
        String value = json.optString(key, "");
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    /** 新建/编辑样本的三项必填：空串与占位文本都算缺项。 */
    private boolean isMissingRequiredField(String value) {
        String text = value == null ? "" : value.trim();
        return TextUtils.isEmpty(text)
                || "--".equals(text)
                || "待录入".equals(text)
                || "未填写".equals(text)
                || "未命名".equals(text);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        density = getResources().getDisplayMetrics().density;
        captureTreatmentSampleIfNeeded(false);
        clickZones.clear();

        float w = getWidth();
        float h = getHeight();
        float leftW = Math.max(dp(220), Math.min(dp(350), w * 0.19f));
        float topH = dp(44);
        float patientH = dp(148);
        float tabH = dp(48);

        fill(canvas, 0, 0, w, h, page);
        drawSidebar(canvas, leftW, h);
        drawTopBar(canvas, leftW, 0, w - leftW, topH);
        drawPatientHeader(canvas, leftW, topH, w - leftW, patientH);

        if (showingSettings) {
            updateCameraPreviewOverlay(null, false);
            drawSettings(canvas, leftW, topH + patientH, w - leftW, h - topH - patientH);
            return;
        }

        drawTabs(canvas, leftW, topH + patientH, w - leftW, tabH);
        float contentY = topH + patientH + tabH;
        float contentH = h - contentY;
        if (activeTab != 0 && activeTab != 3) {
            updateCameraPreviewOverlay(null, false);
        }
        if (activeTab == 0) {
            drawStatusPage(canvas, leftW, contentY, w - leftW, contentH);
        } else if (activeTab == 1) {
            drawControlPage(canvas, leftW, contentY, w - leftW, contentH);
        } else if (activeTab == 2) {
            drawMonitorPage(canvas, leftW, contentY, w - leftW, contentH);
        } else if (activeTab == 3) {
            drawCameraPage(canvas, leftW, contentY, w - leftW, contentH);
        } else if (activeTab == 4) {
            drawRecordPage(canvas, leftW, contentY, w - leftW, contentH);
        } else if (activeTab == 5) {
            drawPdfFilesPage(canvas, leftW, contentY, w - leftW, contentH);
        } else {
            drawTutorialPage(canvas, leftW, contentY, w - leftW, contentH);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            touchDownY = event.getY();
            lastTouchY = touchDownY;
            touchMoved = false;
            draggingAm4100DeviceList = !showingSettings && activeTab == 2 && am4100DeviceListBounds.contains(event.getX(), event.getY());
            draggingBleDeviceList = showingSettings && bleDeviceListBounds.contains(event.getX(), event.getY());
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (draggingBleDeviceList) {
                float dy = event.getY() - lastTouchY;
                if (Math.abs(event.getY() - touchDownY) > dp(4)) {
                    touchMoved = true;
                }
                bleDeviceScrollY = clamp(bleDeviceScrollY - dy, 0, bleDeviceMaxScrollY);
                lastTouchY = event.getY();
                invalidate();
            } else if (draggingAm4100DeviceList) {
                float dy = event.getY() - lastTouchY;
                if (Math.abs(event.getY() - touchDownY) > dp(4)) {
                    touchMoved = true;
                }
                am4100DeviceScrollY = clamp(am4100DeviceScrollY - dy, 0, am4100DeviceMaxScrollY);
                lastTouchY = event.getY();
                invalidate();
            }
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            draggingAm4100DeviceList = false;
            draggingBleDeviceList = false;
            return true;
        }
        if (event.getAction() != MotionEvent.ACTION_UP) {
            return true;
        }
        if (draggingBleDeviceList && touchMoved) {
            draggingBleDeviceList = false;
            invalidate();
            return true;
        }
        if (draggingAm4100DeviceList && touchMoved) {
            draggingAm4100DeviceList = false;
            invalidate();
            return true;
        }
        draggingAm4100DeviceList = false;
        draggingBleDeviceList = false;
        float x = event.getX();
        float y = event.getY();
        for (int i = clickZones.size() - 1; i >= 0; i--) {
            ClickZone zone = clickZones.get(i);
            if (zone.rect.contains(x, y)) {
                zone.action.run();
                invalidate();
                return true;
            }
        }
        return true;
    }

    private void drawSidebar(Canvas canvas, float w, float h) {
        fill(canvas, 0, 0, w, h, sidebar);
        fill(canvas, 0, 0, w, dp(44), navy);
        drawText(canvas, "● 动物ICU重症监护系统", dp(14), dp(28), 17, Color.WHITE, true);

        Organization orgInfo = currentOrganization();
        RectF org = rect(dp(14), dp(60), w - dp(14), dp(192));
        rounded(canvas, org, Color.TRANSPARENT, Color.TRANSPARENT, dp(10));
        RectF logo = rect(org.left, org.top + dp(18), org.left + dp(60), org.top + dp(78));
        drawCircle(canvas, logo.centerX(), logo.centerY(), dp(30), Color.WHITE);
        drawTextCenter(canvas, "LOGO", logo, 14, Color.rgb(69, 151, 228), true);
        drawText(canvas, orgInfo.name, org.left + dp(76), org.top + dp(34), 17, Color.WHITE, true);
        drawText(canvas, "⌖ " + orgInfo.address, org.left + dp(76), org.top + dp(60), 14, Color.WHITE, false);
        drawSmallButton(canvas, "⇄ 切换机构", org.left + dp(76), org.top + dp(82), dp(106), dp(30), false, new ClickAction() {
            @Override
            public void run() {
                showOrganizationDialog();
            }
        });

        String searchLabel = TextUtils.isEmpty(searchKeyword) ? "⌕  搜索宠物名字/主人" : "⌕  " + searchKeyword;
        drawSearchBox(canvas, searchLabel, dp(14), dp(208), w - dp(28), dp(36), new ClickAction() {
            @Override
            public void run() {
                showSearchDialog();
            }
        });

        List<Integer> visibleCases = getVisibleCaseIndices();
        float rowY = dp(264);
        if (visibleCases.isEmpty()) {
            drawText(canvas, "没有匹配的治疗记录", dp(20), rowY + dp(18), 13, Color.WHITE, false);
            return;
        }
        for (int i = 0; i < visibleCases.size(); i++) {
            final int caseIndex = visibleCases.get(i);
            PatientCase item = cases.get(caseIndex);
            RectF r = rect(dp(14), rowY, w - dp(14), rowY + dp(136));
            boolean selected = caseIndex == selectedCaseIndex;
            rounded(canvas, r, selected ? Color.WHITE : Color.TRANSPARENT, selected ? Color.TRANSPARENT : Color.TRANSPARENT, dp(8));
            int primary = selected ? activeBlue : Color.WHITE;
            int secondary = selected ? text : Color.WHITE;
            int weak = selected ? muted : Color.rgb(226, 242, 255);
            // ICU 标识固定在样本信息框右上角：不随行号、卡片宽度或内容长度漂移
            float icuLeft = r.right - dp(12) - dp(42);
            drawSmallButton(canvas, "ICU", icuLeft, r.top + dp(12), dp(42), dp(20), selected, new ClickAction() {
                @Override
                public void run() {
                    selectCase(caseIndex);
                }
            });
            drawText(canvas, "• 宠物名： " + item.petName, r.left + dp(18), r.top + dp(28), 16, primary, true);
            drawText(canvas, "种类： " + item.species + "    主人： " + item.ownerName, r.left + dp(18), r.top + dp(58), 13, secondary, false);
            drawText(canvas, "编号： " + item.monitorNo, r.left + dp(18), r.top + dp(82), 13, secondary, false);
            drawText(canvas, item.visitDate, r.left + dp(18), r.top + dp(108), 13, weak, false);
            clickZones.add(new ClickZone(new RectF(r), new ClickAction() {
                @Override
                public void run() {
                    selectCase(caseIndex);
                }
            }));
            rowY += dp(136);
            if (rowY > h - dp(80)) {
                break;
            }
        }
    }

    private void drawTopBar(Canvas canvas, float x, float y, float w, float h) {
        fill(canvas, x, y, x + w, y + h, navy);
        drawTopButton(canvas, "▯  动物样本", x, y, dp(160), h, !showingSettings, new ClickAction() {
            @Override
            public void run() {
                showingSettings = false;
            }
        });
        drawTopButton(canvas, "⬡  设置", x + dp(160), y, dp(160), h, showingSettings, new ClickAction() {
            @Override
            public void run() {
                showingSettings = true;
            }
        });
        String label = TextUtils.isEmpty(accountName) ? "登录" : "◎ " + accountName + "⌄";
        drawTextRight(canvas, label, x + w - dp(18), y + dp(28), 16, Color.WHITE, true);
        clickZones.add(new ClickZone(rect(x + w - dp(120), y, x + w, y + h), new ClickAction() {
            @Override
            public void run() {
                handleAccountTap();
            }
        }));
    }

    private void drawTopButton(Canvas canvas, String label, float x, float y, float w, float h, boolean active, ClickAction action) {
        int fill = active ? activeBlue : navy;
        rounded(canvas, rect(x, y, x + w, y + h), fill, fill, 0);
        drawTextCenter(canvas, label, rect(x, y, x + w, y + h), 17, Color.WHITE, true);
        clickZones.add(new ClickZone(rect(x, y, x + w, y + h), action));
    }

    private void drawPatientHeader(Canvas canvas, float x, float y, float w, float h) {
        fill(canvas, x, y, x + w, y + h, Color.WHITE);
        RectF shell = rect(x + dp(28), y + dp(16), x + w - dp(18), y + h - dp(16));
        rounded(canvas, shell, Color.rgb(230, 237, 248), Color.TRANSPARENT, dp(8));
        final PatientCase patient = currentCase();
        float fieldW = (shell.width() - dp(510)) / 4f;
        if (patient == null) {
            drawText(canvas, "暂无患者信息", shell.left + dp(18), shell.top + dp(30), 13, text, true);
            return;
        }
        float fy = shell.top + dp(12);
        float rowGap = dp(40);
        float labelW = dp(72);
        drawFormField(canvas, "宠物名称", patient.petName, shell.left + dp(18), fy, labelW, fieldW, true);
        drawFormField(canvas, "年龄", patient.age, shell.left + dp(18) + labelW + fieldW + dp(42), fy, dp(44), fieldW * 0.8f, false);
        drawFormField(canvas, "宠物主人", patient.ownerName, shell.left + dp(18) + (labelW + fieldW + dp(42)) * 2, fy, dp(72), fieldW, false);
        drawFormField(canvas, "主治医生", patient.doctor, shell.left + dp(18) + (labelW + fieldW + dp(42)) * 3, fy, dp(72), fieldW, false);

        drawFormField(canvas, "动物种类", patient.species, shell.left + dp(18), fy + rowGap, labelW, fieldW, false);
        drawFormField(canvas, "病历编号", patient.monitorNo, shell.left + dp(18) + labelW + fieldW + dp(42), fy + rowGap, dp(72), fieldW, true);
        drawFormField(canvas, "联系电话", patient.ownerPhone, shell.left + dp(18) + (labelW + fieldW + dp(42)) * 2, fy + rowGap, dp(72), fieldW, true);
        drawFormField(canvas, "就诊时间", patient.visitDate, shell.left + dp(18) + (labelW + fieldW + dp(42)) * 3, fy + rowGap, dp(72), fieldW, false);

        RectF order = rect(shell.right - dp(330), shell.top + dp(14), shell.right - dp(18), shell.top + dp(84));
        rounded(canvas, order, Color.WHITE, Color.TRANSPARENT, dp(4));
        drawText(canvas, "医嘱", order.left - dp(42), order.top + dp(24), 13, text, false);
        drawText(canvas, patient.note, order.left + dp(12), order.top + dp(26), 12, text, false);
        drawText(canvas, "禁食5小时", order.left + dp(12), order.top + dp(48), 12, text, false);

        float by = shell.bottom - dp(38);
        String[] labels = {"编辑", "删除", "打印", "下载"};
        final int[] actions = {1, 2, 0, 3};
        for (int i = 0; i < labels.length; i++) {
            final int mappedAction = actions[i];
            drawHeaderButton(canvas, labels[i], shell.left + dp(28) + i * dp(96), by, dp(76), dp(30), i == 3, new ClickAction() {
                @Override
                public void run() {
                    handlePatientAction(mappedAction);
                }
            });
        }
    }

    /**
     * 播种默认机构。机构是医院信息页的初始内容，可以直接在页面上改。
     * 默认数据同样按舱区分：两个舱都用同一份默认值的话，
     * 机构名会一模一样，看上去就像左右舱没有隔离。
     *
     * 病例不在这里播种：刚装好的软件应该一条记录都没有，
     * 用户点「创建新记录」之后才生成一条空白病历。
     */
    private void seedOrganizations() {
        boolean left = ZONE_LEFT.equals(softwareZone());
        String side = left ? "左舱" : "右舱";
        organizations.add(new Organization(side + "动物医院", side + "地址", side + "电话"));
        organizations.add(new Organization(side + "动物ICU监护中心", side + "中心地址", side + "中心电话"));
    }

    /**
     * 当前舱位。病例、机构这类软件数据跟着舱位走，
     * 否则左右舱会显示同一个宠物、同一家医院。
     */
    private String softwareZone() {
        String zone = bleManager == null ? "" : bleManager.getCurrentZone();
        return ZONE_LEFT.equals(zone) ? ZONE_LEFT : ZONE_RIGHT;
    }

    /** 按舱区分的存储键。 */
    private String zoneKey(String base) {
        return base + "_" + softwareZone();
    }

    /**
     * 按宠物区分的存储键：同一舱内不同宠物的监护门限、摄像头快照、
     * 护理模式配置各自一份，互不覆盖。没有当前宠物时回落到舱级键，
     * 避免同一份数据落到两个地方。
     */
    private String petKey(String base) {
        PatientCase patient = currentCase();
        String petId = patient == null ? "" : patient.petId;
        if (TextUtils.isEmpty(petId)) {
            return zoneKey(base);
        }
        return base + "_" + softwareZone() + "_pet_" + petId;
    }

    /** 读取按宠物保存的字符串设置。 */
    private String getPetString(SharedPreferences prefs, String base, String fallback) {
        String key = petKey(base);
        if (prefs.contains(key)) {
            return prefs.getString(key, fallback);
        }
        return fallback;
    }

    /** 读取按宠物保存的整型设置。 */
    private int getPetInt(SharedPreferences prefs, String base, int fallback) {
        String key = petKey(base);
        if (prefs.contains(key)) {
            return prefs.getInt(key, fallback);
        }
        return fallback;
    }

    /** 设备级存档：仪器状态、报警音、摄像头地址，所有账号共用同一份。 */
    private SharedPreferences devicePrefs() {
        return AccountScope.devicePrefs(activity);
    }

    /** 账号级存档：病例、机构、护理模式、监护门限都放在这里，不同账号各存各的。 */
    private SharedPreferences accountPrefs() {
        SharedPreferences prefs = AccountScope.dataPrefs(activity, accountName);
        claimLegacyAccountData(prefs);
        return prefs;
    }

    /**
     * 旧版本的病例、机构等数据存在共享存档里，没有分账号。
     * 升级后让第一个登录的账号认领这份数据（认领后从共享存档里清掉），
     * 后面的账号从零开始：账号之间互相看不到对方的病历。
     */
    private void claimLegacyAccountData(SharedPreferences target) {
        if (TextUtils.isEmpty(accountName) || AccountScope.isLegacyClaimed(activity)) {
            return;
        }
        SharedPreferences legacy = devicePrefs();
        SharedPreferences.Editor editor = target.edit();
        SharedPreferences.Editor legacyEditor = legacy.edit();
        for (Map.Entry<String, ?> entry : legacy.getAll().entrySet()) {
            String key = entry.getKey();
            if (!isAccountScopedKey(key)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof String) {
                editor.putString(key, (String) value);
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Long) {
                editor.putLong(key, (Long) value);
            } else if (value instanceof Float) {
                editor.putFloat(key, (Float) value);
            } else {
                continue;
            }
            legacyEditor.remove(key);
        }
        editor.apply();
        legacyEditor.apply();
        AccountScope.markLegacyClaimed(activity);
    }

    /** 键是否属于账号级数据：病例、机构、护理模式、监护门限、摄像头快照。 */
    private boolean isAccountScopedKey(String key) {
        if (TextUtils.isEmpty(key)) {
            return false;
        }
        for (String prefix : ACCOUNT_SCOPED_KEY_PREFIXES) {
            if (key.equals(prefix) || key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 宠物级数据的一次性迁移。
     * 升级前监护门限、快照、护理模式都是按舱存的：把舱里的旧值落到当前选中的宠物上，
     * 之后每只宠物各存各的，不会互相覆盖。
     */
    private void migrateZoneScopedData(SharedPreferences prefs) {
        if (prefs.getBoolean(zoneKey(KEY_PET_SCOPE_MIGRATED), false)) {
            return;
        }
        PatientCase patient = currentCase();
        if (patient == null || TextUtils.isEmpty(patient.petId)) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        copyZoneScopedValue(prefs, editor, KEY_MONITOR_LIMITS);
        copyZoneScopedValue(prefs, editor, KEY_CAMERA_SNAPSHOTS);
        copyZoneScopedValue(prefs, editor, KEY_SELECTED_HOST_MODE);
        copyZoneScopedValue(prefs, editor, KEY_LAST_HOST_MODE);
        copyZoneScopedValue(prefs, editor, KEY_TREATMENT_PERIOD_MODE);
        copyZoneScopedValue(prefs, editor, KEY_TREATMENT_CUSTOM_WINDOW);
        for (int i = 0; i < hostModeValues().length; i++) {
            copyZoneScopedValue(prefs, editor, hostModeConfigBaseKey(i));
        }
        editor.putBoolean(zoneKey(KEY_PET_SCOPE_MIGRATED), true).apply();
    }

    /** 舱级旧值复制到宠物级键：只补齐缺失的键，不覆盖宠物已有的数据。 */
    private void copyZoneScopedValue(SharedPreferences prefs, SharedPreferences.Editor editor, String base) {
        String zoneValueKey = zoneKey(base);
        String petValueKey = petKey(base);
        if (!prefs.contains(zoneValueKey) || prefs.contains(petValueKey)) {
            return;
        }
        Object value = prefs.getAll().get(zoneValueKey);
        if (value instanceof String) {
            editor.putString(petValueKey, (String) value);
        } else if (value instanceof Integer) {
            editor.putInt(petValueKey, (Integer) value);
        }
    }

    /** 旧数据归属舱的存储键：旧键迁移时只写到这个舱，避免两个舱同时读到同一份数据。 */
    private String legacyZoneKey(String base) {
        return base + "_" + legacyDataZone;
    }

    /**
     * 读取按舱保存的整型设置。舱位数据不存在时沿用旧版本（未分舱）的值，
     * 旧值只迁移到 legacyDataZone 指定的舱并删除旧键：
     * 别的舱必须用自己的种子数据，不能拿旧值兜底，否则左右舱会串数据。
     */
    private int getZoneInt(SharedPreferences prefs, String base, int fallback) {
        String key = zoneKey(base);
        if (prefs.contains(key)) {
            return prefs.getInt(key, fallback);
        }
        if (!prefs.contains(base)) {
            return fallback;
        }
        int legacy = prefs.getInt(base, fallback);
        prefs.edit().putInt(legacyZoneKey(base), legacy).remove(base).apply();
        return legacyDataZone.equals(softwareZone()) ? legacy : fallback;
    }

    /** 读取按舱保存的字符串设置，迁移规则与 {@link #getZoneInt} 一致。 */
    private String getZoneString(SharedPreferences prefs, String base, String fallback) {
        String key = zoneKey(base);
        if (prefs.contains(key)) {
            return prefs.getString(key, fallback);
        }
        if (!prefs.contains(base)) {
            return fallback;
        }
        String legacy = prefs.getString(base, fallback);
        prefs.edit().putString(legacyZoneKey(base), legacy).remove(base).apply();
        return legacyDataZone.equals(softwareZone()) ? legacy : fallback;
    }

    /**
     * 切换舱位：蓝牙指令之外，软件侧的病例与机构也必须一起换舱。
     * 顺序是先给当前舱落库、再切舱、最后载入新舱的数据，避免中途写错舱。
     */
    private void switchSoftwareZone(String zone) {
        synchronized (zoneStateLock) {
            switchSoftwareZoneLocked(zone);
        }
    }

    private void switchSoftwareZoneLocked(String zone) {
        String previousZone = softwareZone();
        saveTreatmentRecordsToStorage();
        saveUiSettings();
        saveCameraSnapshotsToStorage();
        storeMonitorSnapshot(previousZone);
        bleManager.setCurrentZone(zone);
        cases.clear();
        organizations.clear();
        seedOrganizations();
        // 先恢复病例：宠物标识在病例里，按宠物存放的数据要靠它对号入座；
        // 读档同时恢复该舱上次选中的病例，没有选中存档时才优先进行中病例。
        loadTreatmentRecordsFromStorage();
        // 机构、护理模式、监护门限、自定义时段都按舱和宠物存放，换舱要整体换一遍
        loadUiSettings();
        loadCameraSnapshotsFromStorage();
        // 病例载入之后才知道新舱的当前宠物，这时才能恢复对应的波形
        restoreMonitorSnapshot(zone);
        lastGeneratedPdf = null;
        activity.notifyLanhuStateChanged();
        invalidate();
    }

    /**
     * 监护采样的归档键：舱位 + 宠物。
     * 同一舱内换宠物时曲线也要换走，否则会看到上一只宠物的波形。
     */
    private String monitorSnapshotKey(String zone, PatientCase patient) {
        String petId = patient == null ? "" : patient.petId;
        return zone + "|" + petId;
    }

    private String monitorSnapshotStorageKey(String zone, PatientCase patient) {
        String petId = patient == null ? "" : patient.petId;
        return KEY_MONITOR_SNAPSHOT + "_" + zone + "_pet_" + petId;
    }

    /** 把当前监护缓冲按“舱 + 稳定 petId”留档，内存用于快速切换，磁盘用于冷启动恢复。 */
    private void storeMonitorSnapshot(String zone) {
        storeMonitorSnapshot(zone, currentCase(), false);
    }

    private void storeMonitorSnapshot(String zone, PatientCase patient, boolean synchronous) {
        // 历史病例只能在结束/翻篇、标记历史之前显式冻结；普通切舱/后台保存不得覆盖它。
        if (patient == null || !patient.currentTreatment || TextUtils.isEmpty(patient.petId)) {
            return;
        }
        Am4100Manager.MonitorSnapshot snapshot = am4100Manager.snapshotBuffers();
        monitorSnapshots.put(monitorSnapshotKey(zone, patient), snapshot);
        try {
            SharedPreferences.Editor editor = accountPrefs().edit().putString(
                    monitorSnapshotStorageKey(zone, patient), MonitorSnapshotCodec.encode(snapshot));
            if (synchronous) {
                editor.commit();
            } else {
                editor.apply();
            }
        } catch (Exception ignored) {
        }
    }

    private Am4100Manager.MonitorSnapshot loadMonitorSnapshot(String zone, PatientCase patient) {
        if (patient == null || TextUtils.isEmpty(patient.petId)) {
            return null;
        }
        String key = monitorSnapshotKey(zone, patient);
        Am4100Manager.MonitorSnapshot snapshot = monitorSnapshots.get(key);
        if (snapshot != null) {
            return snapshot;
        }
        String raw = accountPrefs().getString(monitorSnapshotStorageKey(zone, patient), "");
        snapshot = MonitorSnapshotCodec.decode(raw);
        if (snapshot != null) {
            monitorSnapshots.put(key, snapshot);
        }
        return snapshot;
    }

    /** 载入目标病例的监护采样缓冲；没有存档时清空，避免看到其他病例的曲线。 */
    private void restoreMonitorSnapshot(String zone) {
        am4100Manager.restoreBuffers(loadMonitorSnapshot(zone, currentCase()));
    }

    /**
     * 切换当前宠物：先把旧宠物的数据落库，再载入新宠物的数据。
     * 监护门限、摄像头快照、护理模式都按宠物分开存放，换宠物时必须整体换一遍。
     */
    private void applySelectedCaseIndex(int index) {
        synchronized (zoneStateLock) {
            if (index == selectedCaseIndex || index < 0 || index >= cases.size()) {
                return;
            }
            savePetScopedData();
            storeMonitorSnapshot(softwareZone());
            selectedCaseIndex = index;
            saveTreatmentRecordsToStorageLocked();
            loadPetScopedData();
            restoreMonitorSnapshot(softwareZone());
        }
    }

    /** 落库当前宠物的私有数据：门限、护理模式、时段、摄像头快照。 */
    private void savePetScopedData() {
        saveUiSettingsLocked();
        saveCameraSnapshotsToStorage();
    }

    /** 载入当前宠物的私有数据：门限、护理模式、时段、摄像头快照。 */
    private void loadPetScopedData() {
        loadUiSettingsLocked();
        loadCameraSnapshotsFromStorage();
    }

    /**
     * 删除/释放样本后清掉它名下所有残留：按宠物存的门限、护理模式、时段、
     * 摄像头快照、主机模式配置，以及按宠物留档的监护波形。
     * 不清掉的话，下次新建样本时会看到上一条样本的旧数据。
     */
    private void purgePetScopedData(String petId) {
        if (TextUtils.isEmpty(petId)) {
            return;
        }
        SharedPreferences prefs = accountPrefs();
        SharedPreferences.Editor editor = prefs.edit();
        String marker = "_pet_" + petId;
        ArrayList<String> keys = new ArrayList<>(prefs.getAll().keySet());
        for (String key : keys) {
            if (key != null && key.contains(marker)) {
                editor.remove(key);
            }
        }
        editor.commit();
        ArrayList<String> snapshotKeys = new ArrayList<>(monitorSnapshots.keySet());
        for (String key : snapshotKeys) {
            if (key.endsWith("|" + petId)) {
                monitorSnapshots.remove(key);
            }
        }
    }

    /**
     * 当前宠物的治疗还没结束时，不允许切到同一舱里的其他宠物：
     * 治疗数据、监护门限与波形都是跟着宠物走的，中途换走这一段治疗就断了。
     */
    private boolean canSwitchToCase(PatientCase target) {
        PatientCase current = currentCase();
        if (current == null || target == null || current == target) {
            return true;
        }
        if (!current.currentTreatment) {
            return true;
        }
        Toast.makeText(activity, "当前宠物的治疗尚未结束，不能切换到其他宠物", Toast.LENGTH_SHORT).show();
        // 把页面上的选择拉回当前宠物，避免页面停在一只已经被拒绝的宠物上
        activity.notifyLanhuStateChanged();
        return false;
    }

    private void loadUiSettings() {
        synchronized (zoneStateLock) {
            loadUiSettingsLocked();
        }
    }

    private void loadUiSettingsLocked() {
        SharedPreferences prefs = accountPrefs();
        SharedPreferences shared = devicePrefs();
        // 舱级旧数据只迁移一次：迁移之后同一舱内的每只宠物各存各的
        migrateZoneScopedData(prefs);
        loadOrganizationsFromStorage(prefs);
        loadDeviceProfileFromStorage(shared);
        selectedOrganizationIndex = clampIndex(getZoneInt(prefs, KEY_SELECTED_ORGANIZATION, selectedOrganizationIndex), organizations.size());
        selectedHostModeIndex = recentHostModeIndex(
                getPetInt(prefs, KEY_SELECTED_HOST_MODE, DEFAULT_SELECTED_HOST_MODE));
        lastHostModeIndex = recentHostModeIndex(
                getPetInt(prefs, KEY_LAST_HOST_MODE, DEFAULT_LAST_HOST_MODE));
        treatmentPeriodMode = getPetString(prefs, KEY_TREATMENT_PERIOD_MODE, PERIOD_ALL);
        String customWindow = getPetString(prefs, KEY_TREATMENT_CUSTOM_WINDOW, "");
        treatmentCustomStart = "";
        treatmentCustomEnd = "";
        if (!TextUtils.isEmpty(customWindow)) {
            try {
                JSONObject window = new JSONObject(customWindow);
                treatmentCustomStart = window.optString("start", "");
                treatmentCustomEnd = window.optString("end", "");
            } catch (JSONException ignored) {
            }
        }
        monitorPulseBeep = shared.getBoolean(KEY_MONITOR_PULSE_BEEP, monitorPulseBeep);
        monitorAlarmSound = shared.getBoolean(KEY_MONITOR_ALARM_SOUND, monitorAlarmSound);
        loadMonitorLimitsFromStorage(prefs);
        if (!PERIOD_ENABLED.equals(treatmentPeriodMode) && !PERIOD_CUSTOM.equals(treatmentPeriodMode)) {
            treatmentPeriodMode = PERIOD_ALL;
        }
    }

    private void saveUiSettings() {
        synchronized (zoneStateLock) {
            saveUiSettingsLocked();
        }
    }

    private void saveUiSettingsLocked() {
        accountPrefs()
                .edit()
                .putInt(zoneKey(KEY_SELECTED_ORGANIZATION), selectedOrganizationIndex)
                .putString(zoneKey(KEY_ORGANIZATIONS), organizationsToJsonString())
                .putInt(petKey(KEY_SELECTED_HOST_MODE), selectedHostModeIndex)
                .putInt(petKey(KEY_LAST_HOST_MODE), lastHostModeIndex)
                .putString(petKey(KEY_TREATMENT_PERIOD_MODE), treatmentPeriodMode)
                .putString(petKey(KEY_TREATMENT_CUSTOM_WINDOW), treatmentCustomWindowJson())
                .putString(petKey(KEY_MONITOR_LIMITS), monitorLimitsToJsonString())
                .apply();
        devicePrefs()
                .edit()
                .putString(KEY_DEVICE_PROFILE, deviceProfileToJsonString())
                .putBoolean(KEY_MONITOR_PULSE_BEEP, monitorPulseBeep)
                .putBoolean(KEY_MONITOR_ALARM_SOUND, monitorAlarmSound)
                .apply();
    }

    /** 自定义时段窗口的存储文本。 */
    private String treatmentCustomWindowJson() {
        try {
            JSONObject window = new JSONObject();
            window.put("start", treatmentCustomStart);
            window.put("end", treatmentCustomEnd);
            return window.toString();
        } catch (JSONException exception) {
            return "";
        }
    }

    /** 监护上下限按舱保存：左右舱各自一套报警门限。 */
    private String monitorLimitsToJsonString() {
        try {
            JSONArray array = new JSONArray();
            for (int i = 0; i < monitorLimitEnabled.length; i++) {
                JSONObject item = new JSONObject();
                item.put("enabled", monitorLimitEnabled[i]);
                item.put("high", monitorLimitHigh[i]);
                item.put("low", monitorLimitLow[i]);
                array.put(item);
            }
            return array.toString();
        } catch (JSONException exception) {
            return "";
        }
    }

    /** 读取当前宠物的监护上下限；没有记录时沿用默认值。 */
    private void loadMonitorLimitsFromStorage(SharedPreferences prefs) {
        // 先回到默认值：换宠物时不能把上一只宠物的门限带过来
        resetMonitorLimits();
        String raw = getPetString(prefs, KEY_MONITOR_LIMITS, "");
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length() && i < monitorLimitEnabled.length; i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                monitorLimitEnabled[i] = item.optBoolean("enabled", monitorLimitEnabled[i]);
                monitorLimitHigh[i] = (float) item.optDouble("high", monitorLimitHigh[i]);
                monitorLimitLow[i] = (float) item.optDouble("low", monitorLimitLow[i]);
            }
        } catch (JSONException ignored) {
        }
    }

    /** 监护上下限回到默认值。 */
    private void resetMonitorLimits() {
        System.arraycopy(DEFAULT_LIMIT_ENABLED, 0, monitorLimitEnabled, 0, monitorLimitEnabled.length);
        System.arraycopy(DEFAULT_LIMIT_HIGH, 0, monitorLimitHigh, 0, monitorLimitHigh.length);
        System.arraycopy(DEFAULT_LIMIT_LOW, 0, monitorLimitLow, 0, monitorLimitLow.length);
    }

    private void loadOrganizationsFromStorage(SharedPreferences prefs) {
        String raw = getZoneString(prefs, KEY_ORGANIZATIONS, "");
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        try {
            JSONArray array = new JSONArray(raw);
            if (array.length() == 0) {
                return;
            }
            organizations.clear();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                organizations.add(new Organization(
                        item.optString("name", "未设置医院"),
                        item.optString("address", "未设置地址"),
                        item.optString("phone", "未设置电话"),
                        item.optString("logoUri", ""),
                        item.optString("logoName", "")));
            }
        } catch (JSONException ignored) {
        }
    }

    /**
     * 读取上次保存的仪器状态页设备档案。
     * 存储内容损坏时保持默认值，不影响其他设置项。
     */
    private void loadDeviceProfileFromStorage(SharedPreferences prefs) {
        String raw = prefs.getString(KEY_DEVICE_PROFILE, "");
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        try {
            JSONObject json = new JSONObject(raw);
            deviceProfile.productModel = firstNonEmpty(json.optString("productModel", ""), DEFAULT_PRODUCT_MODEL);
            deviceProfile.machineType = json.optString("machineType", "");
            deviceProfile.serialNo = json.optString("serialNo", "");
            deviceProfile.manufactureDate = json.optString("manufactureDate", "");
            deviceProfile.softwareVersion = json.optString("softwareVersion", "");
            deviceProfile.softwareVersionFollowsApp = json.optBoolean("softwareVersionFollowsApp", true);
        } catch (JSONException ignored) {
        }
    }

    private String organizationsToJsonString() {
        JSONArray array = new JSONArray();
        try {
            for (Organization organization : organizations) {
                JSONObject item = new JSONObject();
                item.put("name", organization.name);
                item.put("address", organization.address);
                item.put("phone", organization.phone);
                item.put("logoUri", organization.logoUri);
                item.put("logoName", organization.logoName);
                array.put(item);
            }
        } catch (JSONException ignored) {
        }
        return array.toString();
    }

    private void ensureTreatmentEntrySet(PatientCase patient) {
        if (patient == null) {
            return;
        }
        for (String[] item : TREATMENT_TEMPLATES) {
            if (!hasTreatmentEntry(patient, item[0])) {
                patient.treatmentEntries.add(new TreatmentEntry(item[0], item[1],
                        Boolean.parseBoolean(item[2]), Boolean.parseBoolean(item[3]),
                        patient.treatmentStartTime, patient.treatmentEndTime));
            }
        }
    }

    private boolean hasTreatmentEntry(PatientCase patient, String targetName) {
        for (TreatmentEntry entry : patient.treatmentEntries) {
            if (sameTreatmentName(entry.itemName, targetName)) {
                return true;
            }
        }
        return false;
    }

    private boolean sameTreatmentName(String left, String right) {
        if (TextUtils.equals(left, right)) {
            return true;
        }
        if (("氧气浓度".equals(left) && "氧浓度".equals(right)) || ("氧浓度".equals(left) && "氧气浓度".equals(right))) {
            return true;
        }
        return ("雾化治疗".equals(left) && "雾化器".equals(right)) || ("雾化器".equals(left) && "雾化治疗".equals(right));
    }

    // 历史病例不预置演示统计值：旧版 seedHistoricalTreatmentStats/seedEntry 会写死
    // 31.6℃ / 37.1℃ 等假数据与 sampleCount=6，与真实采样混淆，已整体移除。

    // MainActivity 的蓝牙状态回调也会触发采样，需在同包外可见；
    // 内部仍统一走 zoneStateLock，不改动开关沿/时长口径。
    public void captureTreatmentSampleIfNeeded(boolean force) {
        synchronized (zoneStateLock) {
            captureTreatmentSampleIfNeededLocked(force);
        }
    }

    /**
     * 退到后台 / 从最近任务划掉前由 Activity 触发。
     * 只做一次完整写盘，不结算活动区间：舱里还在治疗，开启锚点必须原样留着，
     * 冷启动恢复后才能按真实墙钟把这段时间算进去，而不是把时长清零。
     */
    public void flushTreatmentStateForBackground() {
        synchronized (zoneStateLock) {
            PatientCase patient = currentCase();
            if (patient != null && patient.currentTreatment) {
                // 最后一次采样与快照必须属于同一个正在治疗的选中病例，不能写到历史病例名下。
                captureTreatmentSampleIfNeededLocked(true);
                storeMonitorSnapshot(softwareZone(), patient, true);
            }
            saveTreatmentRecordsToStorageLocked(true);
        }
    }

    /**
     * 采样会写治疗记录存档，只能在 UI 线程做。
     * 网页每隔一秒多就会通过 JavascriptInterface 取一次状态，
     * 这里用标记位把连续请求合并成一次采样，避免任务堆积。
     */
    private void scheduleTreatmentSample() {
        if (treatmentSampleScheduled) {
            return;
        }
        treatmentSampleScheduled = true;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                treatmentSampleScheduled = false;
                captureTreatmentSampleIfNeeded(false);
            }
        });
    }

    private void captureTreatmentSampleIfNeededLocked(boolean force) {
        PatientCase patient = currentTreatmentCase();
        if (patient == null) {
            return;
        }
        long now = System.currentTimeMillis();
        // 数值样本仍维持原 60 秒节流，保留有效值过滤；
        // 开关沿与累计时长不受该门限约束，避免短开关沿被节流漏记（整段开启不足 60 秒仍显示 --）。
        boolean sampleDue = force || patient.lastTreatmentSampleAt <= 0L
                || now - patient.lastTreatmentSampleAt >= 60000L;
        String sampleTime = currentTimeText();
        boolean changed = false;
        // 波形类监护项以“监护蓝牙断开”为结束沿：连接→断开时把仍进行中的波形项结算掉，
        // 不受 60 秒数值节流影响，也不等到整段治疗结束。
        boolean monitorConnected = am4100Manager.isConnected();
        if (lastMonitorConnectedForWaveEnd && !monitorConnected) {
            if (settleWaveformEndOnMonitorDisconnectLocked(patient, sampleTime)) {
                changed = true;
            }
        }
        lastMonitorConnectedForWaveEnd = monitorConnected;
        for (TreatmentEntry entry : patient.treatmentEntries) {
            boolean on = treatmentFeatureOn(entry.itemName);
            boolean environmentSensor = isEnvironmentSensorEntry(entry.itemName);
            // 用户明确指定的 4 个环境传感器：舱内温度、氧浓度、湿度、CO2浓度。
            // 它们的开始时间只认“第一次读到有效数值”，不认开关沿，也不受 60 秒
            // 数值采样节流限制；读不到值时保持 "--"，读到过之后断连/关开关也不回退。
            if (environmentSensor) {
                String reading = treatmentDisplayValue(entry.itemName);
                float value = parseTreatmentSampleValue(entry.itemName, reading);
                if (isValidTreatmentSample(entry.itemName, value)
                        && TextUtils.isEmpty(entry.startTime) && !entry.customPeriod) {
                    entry.startTime = sampleTime;
                    entry.endTime = "进行中";
                    entry.readingStarted = true;
                    changed = true;
                }
            }
            if (treatmentHasSwitch(entry.itemName)) {
                // 开关沿：记录功能真实的开启/结束时间，并累计“功能开启时段”的时长
                if (on && !entry.featureOn) {
                    // 环境传感器的时间由真实读数决定，开关沿只累计时长，不覆盖开始时间。
                    if (!environmentSensor) {
                        // 开启沿：开始时间记为真实开启时刻，结束时间标“进行中”；
                        // 关闭沿再写成具体时刻。没开启过的一项两者都为空，页面显示 "--"。
                        entry.startTime = sampleTime;
                        entry.endTime = "进行中";
                        entry.customPeriod = false;
                    }
                    entry.featureOnSince = now;
                    changed = true;
                } else if (!on && entry.featureOn) {
                    if (!environmentSensor) {
                        entry.endTime = sampleTime;
                    }
                    if (entry.featureOnSince > 0L) {
                        // 关闭沿立即折入真实开启区间，不受 60 秒数值采样门限约束。
                        entry.totalOnMs = TreatmentUsage.effectiveOnMs(
                                entry.totalOnMs, true, entry.featureOnSince, now);
                        entry.featureOnSince = 0L;
                    }
                    changed = true;
                }
                if (!on && entry.totalOnMs <= 0L && !environmentSensor) {
                    // 整段治疗都没开启过：开始/结束时间没有意义，页面显示 "--"
                    if (!TextUtils.isEmpty(entry.startTime) || !TextUtils.isEmpty(entry.endTime)) {
                        entry.startTime = "";
                        entry.endTime = "";
                        changed = true;
                    }
                }
            } else if (!environmentSensor && !isMonitorReadingEntry(entry.itemName)) {
                // 非开关、非读数驱动的杂项：沿用“当前有值才记开始时间”的旧口径。
                if (on && TextUtils.isEmpty(entry.startTime)) {
                    entry.startTime = sampleTime;
                    changed = true;
                }
            }
            // 数值型传感器（心率/血氧/脉率/呼吸率/体温/血压/波形）的开始时间
            // 只看“有没有读到真实数值”，不看此刻是否连接，也不在补录之前清理：
            // 治疗中读到过、此刻断连也不能抹掉开始时间。真正的开始时刻在下方
            // 补录/实时采样的 Sink 里按样本真实时间戳写入。
            entry.featureOn = on;
            if (!entry.enabled) {
                continue;
            }
            // 数值样本受 60 秒节流，开关沿/使用时长不受限
            if (!sampleDue) {
                continue;
            }
            int before = entry.sampleCount;
            long previousBackfillAt = entry.lastHistoryBackfillAt;
            // 优先补录治疗期间已到达的真实历史；有历史支持时不再制造一个结束时刻采样点。
            boolean hasMeasurementHistory = backfillTreatmentHistoryLocked(patient, entry, now);
            if (!hasMeasurementHistory) {
                String display = treatmentDisplayValue(entry.itemName);
                float value = parseTreatmentSampleValue(entry.itemName, display);
                // 没有任何历史时才保留原实时采样兜底；无效值仍不进入样本与统计。
                // 波形三项还要等配对参数同时有效，只有心电没有血压不算波形已开始。
                if (waveformSourceReady(entry.itemName)
                        && isValidTreatmentSample(entry.itemName, value)) {
                    // 生命体征/波形读到第一个有效数值时，用真实采样时刻记开始时间；
                    // 4 个环境传感器已在循环开头按各自读数写入，不在这里重复覆盖。
                    if (isMonitorReadingEntry(entry.itemName)
                            && !environmentSensor
                            && TextUtils.isEmpty(entry.startTime) && !entry.customPeriod) {
                        entry.startTime = timeTextOf(now);
                        entry.endTime = "进行中";
                        entry.readingStarted = true;
                    }
                    entry.record(value, display);
                    entry.addSample(now, value, on);
                }
            }
            changed = changed || entry.sampleCount != before
                    || entry.lastHistoryBackfillAt != previousBackfillAt;
        }
        if (sampleDue) {
            patient.lastTreatmentSampleAt = now;
        }
        if (changed) {
            saveTreatmentRecordsToStorage();
        }
        if (sampleDue && patient == currentCase()) {
            // 周期检查点覆盖“无任何生命周期回调就被杀进程”的情况，最多损失一个采样间隔。
            storeMonitorSnapshot(softwareZone(), patient, false);
        }
    }

    /** 返回本项是否已有真实监护历史支持；新历史点按原时间补入且只结算一次。 */
    private boolean backfillTreatmentHistoryLocked(PatientCase patient, final TreatmentEntry entry, long now) {
        int param = treatmentHistoryParam(entry.itemName);
        long from = parseTimeTextMs(patient.treatmentStartTime);
        if (param < 0 || from <= 0L || now < from) {
            return false;
        }
        TreatmentHistoryBackfill.Result result = TreatmentHistoryBackfill.backfill(
                am4100Manager.getMeasurementHistory(), param, from, now,
                entry.lastHistoryBackfillAt, new TreatmentHistoryBackfill.Sink() {
                    @Override
                    public boolean contains(long time, float value) {
                        for (TreatmentSample sample : entry.samples) {
                            if (sample.time == time && Float.compare(sample.value, value) == 0) {
                                return true;
                            }
                        }
                        return false;
                    }

                    @Override
                    public void add(long time, float value) {
                        // 生命体征/波形按补录样本的真实时间记开始时间；
                        // 波形仍需配对参数同时有效，环境传感器不走监护历史补录。
                        if (isMonitorReadingEntry(entry.itemName)
                                && waveformSourceReady(entry.itemName)
                                && TextUtils.isEmpty(entry.startTime) && !entry.customPeriod) {
                            entry.startTime = timeTextOf(time);
                            entry.endTime = "进行中";
                            entry.readingStarted = true;
                        }
                        entry.record(value, treatmentExtremeText(value, ""));
                        entry.addSample(time, value, true);
                    }
                });
        if (result.latestTime > entry.lastHistoryBackfillAt) {
            entry.lastHistoryBackfillAt = result.latestTime;
        }
        return result.matched > 0 || entry.lastHistoryBackfillAt > 0L;
    }

    /** 血压是 SYS/DIA 组合值，不把两个独立历史参数误回填成一个数。 */
    private static int treatmentHistoryParam(String itemName) {
        if ("心率".equals(itemName)) {
            return Am4100Manager.PARAM_HEART_RATE;
        }
        if ("血氧".equals(itemName)) {
            return Am4100Manager.PARAM_SPO2;
        }
        if ("脉率".equals(itemName)) {
            return Am4100Manager.PARAM_PULSE;
        }
        if ("体温".equals(itemName)) {
            return Am4100Manager.PARAM_TEMP;
        }
        if ("呼吸率".equals(itemName)) {
            return Am4100Manager.PARAM_RESP;
        }
        return -1;
    }

    /** 结束/翻篇前，把仍开启项目的活动区间折入累计时长，并清空进行中标记，避免时长丢失。 */
    private void settleActiveTreatmentIntervalsLocked(PatientCase patient, long now) {
        if (patient == null) {
            return;
        }
        for (TreatmentEntry entry : patient.treatmentEntries) {
            if (entry.featureOn && entry.featureOnSince > 0L) {
                entry.totalOnMs = TreatmentUsage.effectiveOnMs(
                        entry.totalOnMs, true, entry.featureOnSince, now);
            }
            entry.featureOnSince = 0L;
            entry.featureOn = false;
        }
    }

    /**
     * 波形三项以监护蓝牙断开为结束沿。只结算已经开始且仍“进行中”的波形项；
     * 未读到过配对数值的不写结束时间，手工自定义时段也不覆盖。
     */
    private boolean settleWaveformEndOnMonitorDisconnectLocked(PatientCase patient, String endTime) {
        if (patient == null) {
            return false;
        }
        boolean changed = false;
        for (TreatmentEntry entry : patient.treatmentEntries) {
            if (TreatmentSamplePolicy.isWaveform(entry.itemName)
                    && (entry.readingStarted || entry.sampleCount > 0)
                    && !entry.customPeriod
                    && "进行中".equals(entry.endTime)) {
                entry.endTime = endTime;
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 只有真实、落在生理值域内的数值才能进入治疗记录统计。
     * 旧版本会把 -1 / 0.0 / 物体温度当样本，出现 sampleCount=1 且 avg=max=min。
     */
    private boolean isValidTreatmentSample(String itemName, float value) {
        int param = treatmentHistoryParam(itemName);
        if ("血压".equals(itemName)) {
            param = Am4100Manager.PARAM_BP_SYS;
        } else if ("心电图".equals(itemName)) {
            param = Am4100Manager.PARAM_HEART_RATE;
        } else if ("血氧波形".equals(itemName)) {
            param = Am4100Manager.PARAM_SPO2;
        } else if ("呼吸率波形".equals(itemName)) {
            param = Am4100Manager.PARAM_RESP;
        }
        if (param >= 0) {
            return VitalMeasurementPolicy.isValid(param, value);
        }
        return !Float.isNaN(value) && !Float.isInfinite(value) && value > 0f;
    }

    /** 治疗项是否有独立开关；有开关的按开关沿记录真实的开始/结束时间。 */
    private static boolean treatmentHasSwitch(String itemName) {
        return "舱内温度".equals(itemName) || "氧浓度".equals(itemName) || "氧气浓度".equals(itemName)
                || "CO2浓度".equals(itemName) || "红外理疗".equals(itemName) || "蓝光理疗".equals(itemName)
                || "雾化器".equals(itemName) || "雾化治疗".equals(itemName) || "负离子".equals(itemName)
                || "紫外消毒".equals(itemName);
    }

    private PatientCase currentTreatmentCase() {
        for (PatientCase item : cases) {
            if (item.currentTreatment) {
                return item;
            }
        }
        // 没有进行中的治疗时不再采样，避免结束后的记录被继续改写
        return null;
    }

    private String treatmentDisplayValue(String itemName) {
        if ("舱内温度".equals(itemName)) {
            return bleManager.getCabinTempText();
        }
        if ("氧气浓度".equals(itemName) || "氧浓度".equals(itemName)) {
            return bleManager.getOxygenText();
        }
        if ("红外理疗".equals(itemName)) {
            return bleManager.getControlValue(6);
        }
        if ("蓝光理疗".equals(itemName)) {
            return bleManager.getControlValue(7);
        }
        if ("雾化治疗".equals(itemName) || "雾化器".equals(itemName)) {
            return bleManager.getControlValue(10);
        }
        if ("负离子".equals(itemName)) {
            return bleManager.getControlValue(11);
        }
        if ("湿度".equals(itemName)) {
            return bleManager.getHumidityText();
        }
        if ("CO2浓度".equals(itemName)) {
            return bleManager.getCo2Text();
        }
        if ("治疗时长".equals(itemName)) {
            return bleManager.getTreatmentTimeText();
        }
        if ("护理模式".equals(itemName)) {
            return selectedHostModeName();
        }
        if ("心率".equals(itemName)) {
            return am4100Manager.getHeartRateText();
        }
        if ("血压".equals(itemName)) {
            return am4100Manager.getBloodPressureText();
        }
        if ("血氧".equals(itemName)) {
            return am4100Manager.getSpo2Text();
        }
        if ("脉率".equals(itemName)) {
            return am4100Manager.getPulseRateText();
        }
        if ("体温".equals(itemName)) {
            return am4100Manager.getBodyTempText();
        }
        if ("呼吸率".equals(itemName)) {
            return am4100Manager.getRespText();
        }
        if ("心电图".equals(itemName)) {
            return "ECG " + am4100Manager.getHeartRateText();
        }
        if ("血氧波形".equals(itemName)) {
            return "PLETH " + am4100Manager.getSpo2Text();
        }
        if ("呼吸率波形".equals(itemName)) {
            return "RESP " + am4100Manager.getRespText();
        }
        return "--";
    }

    private void saveTreatmentRecordsToStorage() {
        synchronized (zoneStateLock) {
            saveTreatmentRecordsToStorageLocked();
        }
    }

    private void saveTreatmentRecordsToStorageLocked() {
        saveTreatmentRecordsToStorageLocked(false);
    }

    private void saveTreatmentRecordsToStorageLocked(boolean synchronous) {
        try {
            JSONArray array = new JSONArray();
            for (PatientCase patient : cases) {
                if (!hasMeaningfulPatientData(patient)) {
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("caseNo", patient.caseNo);
                item.put("monitorNo", patient.monitorNo);
                item.put("petId", patient.petId);
                item.put("petName", patient.petName);
                item.put("species", patient.species);
                item.put("sex", patient.sex);
                item.put("age", patient.age);
                item.put("ownerName", patient.ownerName);
                item.put("ownerPhone", patient.ownerPhone);
                item.put("doctor", patient.doctor);
                item.put("recordNo", patient.recordNo);
                item.put("visitDate", patient.visitDate);
                item.put("note", patient.note);
                item.put("currentTreatment", patient.currentTreatment);
                item.put("explicitSample", patient.explicitSample);
                item.put("treatmentStartTime", patient.treatmentStartTime);
                item.put("treatmentEndTime", patient.treatmentEndTime);
                item.put("lastTreatmentSampleAt", patient.lastTreatmentSampleAt);
                JSONArray entries = new JSONArray();
                for (TreatmentEntry entry : patient.treatmentEntries) {
                    JSONObject entryJson = new JSONObject();
                    entryJson.put("itemName", entry.itemName);
                    entryJson.put("unit", entry.unit);
                    entryJson.put("enabled", entry.enabled);
                    entryJson.put("includeInReport", entry.includeInReport);
                    entryJson.put("customPeriod", entry.customPeriod);
                    entryJson.put("startTime", entry.startTime);
                    entryJson.put("endTime", entry.endTime);
                    entryJson.put("manualValue", entry.manualValue);
                    entryJson.put("manualAverageValue", entry.manualAverageValue);
                    entryJson.put("manualHighValue", entry.manualHighValue);
                    entryJson.put("manualLowValue", entry.manualLowValue);
                    entryJson.put("lastValue", entry.lastValue);
                    entryJson.put("sum", entry.sum);
                    entryJson.put("high", Float.isNaN(entry.high) ? JSONObject.NULL : entry.high);
                    entryJson.put("low", Float.isNaN(entry.low) ? JSONObject.NULL : entry.low);
                    entryJson.put("sampleCount", entry.sampleCount);
                    entryJson.put("readingStarted", entry.readingStarted);
                    entryJson.put("totalOnMs", entry.totalOnMs);
                    entryJson.put("featureOn", entry.featureOn);
                    entryJson.put("featureOnSince", entry.featureOnSince);
                    entryJson.put("lastHistoryBackfillAt", entry.lastHistoryBackfillAt);
                    JSONArray sampleArray = new JSONArray();
                    for (int s = 0; s < entry.samples.size(); s++) {
                        TreatmentSample sample = entry.samples.get(s);
                        JSONObject sampleJson = new JSONObject();
                        sampleJson.put("t", sample.time);
                        sampleJson.put("v", sample.value);
                        sampleJson.put("on", sample.featureOn);
                        sampleArray.put(sampleJson);
                    }
                    entryJson.put("samples", sampleArray);
                    entries.put(entryJson);
                }
                item.put("entries", entries);
                array.put(item);
            }
            SharedPreferences prefs = accountPrefs();
            PatientCase selected = currentCase();
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(zoneKey(KEY_TREATMENT_CASES), array.toString())
                    .putString(zoneKey(KEY_SELECTED_CASE), selected == null ? "" : selected.petId);
            if (synchronous) {
                editor.commit();
            } else {
                editor.apply();
            }
        } catch (Exception ignored) {
        }
    }

    private void loadTreatmentRecordsFromStorage() {
        synchronized (zoneStateLock) {
            loadTreatmentRecordsFromStorageLocked();
        }
        bindTreatmentClockToCurrentCase();
    }

    /** 三项必填齐全才算「已录入信息」；空白样本不允许启动治疗时长。 */
    private boolean hasCompleteTreatmentInfo(PatientCase patient) {
        return patient != null
                && !isMissingRequiredField(patient.petName)
                && !isMissingRequiredField(patient.recordNo)
                && !isMissingRequiredField(patient.ownerPhone);
    }

    private void bindTreatmentClockToCurrentCase() {
        PatientCase active = currentTreatmentCase();
        boolean complete = hasCompleteTreatmentInfo(active);
        bleManager.bindTreatmentSession(active == null ? "" : active.recordNo,
                active != null, complete);
    }

    private void loadTreatmentRecordsFromStorageLocked() {
        try {
            SharedPreferences prefs = accountPrefs();
            String raw = getZoneString(prefs, KEY_TREATMENT_CASES, "");
            if (TextUtils.isEmpty(raw)) {
                return;
            }
            JSONArray array = new JSONArray(raw);
            if (array.length() == 0) {
                return;
            }
            // 先读进临时列表：存档里没有有效病例时不能把刚建好的病例清空
            ArrayList<PatientCase> loaded = new ArrayList<>();
            // 旧版本自动播种的占位病例要在这次清掉，清过之后把空列表写回存档
            boolean droppedLegacySeed = false;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                PatientCase patient = new PatientCase(
                        item.optString("caseNo", "000000"),
                        item.optString("monitorNo", "000000"),
                        item.optString("petName", "未命名"),
                        item.optString("species", "未知"),
                        item.optString("sex", "-"),
                        item.optString("age", "-"),
                        item.optString("ownerName", "-"),
                        item.optString("ownerPhone", "-"),
                        item.optString("doctor", "-"),
                        item.optString("recordNo", "000000"),
                        item.optString("visitDate", currentTimeText()),
                        item.optString("note", ""));
                if (isLegacySeedCase(patient)) {
                    // 占位病例不是真实病历：直接丢掉，列表从零开始
                    droppedLegacySeed = true;
                    continue;
                }
                // 宠物标识要跟着存档走：换了标识，之前按宠物存的监护门限、快照就找不到了
                String savedPetId = item.optString("petId", "");
                if (!TextUtils.isEmpty(savedPetId)) {
                    patient.petId = savedPetId;
                }
                patient.currentTreatment = item.optBoolean("currentTreatment", i == 0);
                patient.explicitSample = item.optBoolean("explicitSample", false);
                patient.treatmentStartTime = item.optString("treatmentStartTime", patient.visitDate);
                patient.treatmentEndTime = item.optString("treatmentEndTime", patient.currentTreatment ? "进行中" : patient.visitDate);
                patient.lastTreatmentSampleAt = item.optLong("lastTreatmentSampleAt", 0L);
                JSONArray entries = item.optJSONArray("entries");
                if (entries != null && entries.length() > 0) {
                    patient.treatmentEntries.clear();
                    for (int j = 0; j < entries.length(); j++) {
                        JSONObject entryJson = entries.getJSONObject(j);
                        // 单项时间默认留空：旧档缺字段时不再回落成病例级的“就诊时间/进行中”，
                        // 否则没开过的功能会凭空带上开始时间。真实值只来自已存档的单项字段。
                        TreatmentEntry entry = new TreatmentEntry(
                                entryJson.optString("itemName", "项目"),
                                entryJson.optString("unit", ""),
                                entryJson.optBoolean("enabled", true),
                                entryJson.optBoolean("includeInReport", true),
                                entryJson.optString("startTime", ""),
                                entryJson.optString("endTime", ""));
                        entry.manualValue = entryJson.optString("manualValue", "");
                        entry.manualAverageValue = entryJson.optString("manualAverageValue", "");
                        entry.manualHighValue = entryJson.optString("manualHighValue", "");
                        entry.manualLowValue = entryJson.optString("manualLowValue", "");
                        entry.customPeriod = entryJson.optBoolean("customPeriod", false);
                        entry.lastValue = entryJson.optString("lastValue", "--");
                        entry.sum = (float) entryJson.optDouble("sum", 0);
                        entry.high = entryJson.isNull("high") ? Float.NaN : (float) entryJson.optDouble("high", Float.NaN);
                        entry.low = entryJson.isNull("low") ? Float.NaN : (float) entryJson.optDouble("low", Float.NaN);
                        entry.sampleCount = entryJson.optInt("sampleCount", 0);
                        entry.readingStarted = entryJson.optBoolean("readingStarted", false);
                        entry.totalOnMs = entryJson.optLong("totalOnMs", 0L);
                        entry.featureOn = entryJson.optBoolean("featureOn", false);
                        entry.featureOnSince = entryJson.optLong("featureOnSince", 0L);
                        entry.lastHistoryBackfillAt = entryJson.optLong("lastHistoryBackfillAt", 0L);
                        JSONArray sampleArray = entryJson.optJSONArray("samples");
                        if (sampleArray != null) {
                            for (int s = 0; s < sampleArray.length() && s < TreatmentEntry.MAX_SAMPLES; s++) {
                                JSONObject sampleJson = sampleArray.optJSONObject(s);
                                if (sampleJson == null) {
                                    continue;
                                }
                                entry.samples.add(new TreatmentSample(
                                        sampleJson.optLong("t", 0L),
                                        (float) sampleJson.optDouble("v", Double.NaN),
                                        sampleJson.optBoolean("on", false)));
                            }
                        }
                        sanitizeLoadedTreatmentEntry(entry);
                        patient.treatmentEntries.add(entry);
                    }
                }
                ensureTreatmentEntrySet(patient);
                if (!hasMeaningfulPatientData(patient)) {
                    continue;
                }
                loaded.add(patient);
            }
            if (loaded.isEmpty()) {
                if (droppedLegacySeed) {
                    // 存档里只剩占位记录：把空列表写回去，下次启动不用再判断一次
                    saveTreatmentRecordsToStorageLocked();
                }
                return;
            }
            cases.clear();
            cases.addAll(loaded);
            String selectedPetId = getZoneString(prefs, KEY_SELECTED_CASE, "");
            int restoredSelection = -1;
            int activeSelection = -1;
            for (int i = 0; i < cases.size(); i++) {
                PatientCase patient = cases.get(i);
                if (restoredSelection < 0 && !TextUtils.isEmpty(selectedPetId)
                        && TextUtils.equals(selectedPetId, patient.petId)) {
                    restoredSelection = i;
                }
                if (activeSelection < 0 && patient.currentTreatment) {
                    activeSelection = i;
                }
            }
            // 全部病例都已结束时保持历史状态，绝不能把最新病例重新标成“进行中”。
            selectedCaseIndex = restoredSelection >= 0 ? restoredSelection
                    : activeSelection >= 0 ? activeSelection : clampIndex(selectedCaseIndex, cases.size());
            saveTreatmentRecordsToStorage();
        } catch (Exception ignored) {
        }
    }

    /**
     * 读档时剔除旧版本写进来的无效样本：-1 哨兵、0.0℃ 全零帧，
     * 以及被误当成体温的物体/环境温度。样本明细存在时按明细重算统计。
     */
    private void sanitizeLoadedTreatmentEntry(TreatmentEntry entry) {
        if (entry == null) {
            return;
        }
        if (entry.samples.isEmpty()) {
            if (entry.sampleCount > 0 && (!isValidTreatmentSample(entry.itemName, entry.high)
                    || !isValidTreatmentSample(entry.itemName, entry.low))) {
                entry.sampleCount = 0;
                entry.sum = 0f;
                entry.high = Float.NaN;
                entry.low = Float.NaN;
                entry.lastValue = "--";
            }
            return;
        }
        ArrayList<TreatmentSample> valid = new ArrayList<>();
        float sum = 0f;
        float high = Float.NaN;
        float low = Float.NaN;
        for (int i = 0; i < entry.samples.size(); i++) {
            TreatmentSample sample = entry.samples.get(i);
            if (!isValidTreatmentSample(entry.itemName, sample.value)) {
                continue;
            }
            valid.add(sample);
            sum += sample.value;
            high = Float.isNaN(high) ? sample.value : Math.max(high, sample.value);
            low = Float.isNaN(low) ? sample.value : Math.min(low, sample.value);
        }
        entry.samples.clear();
        entry.samples.addAll(valid);
        entry.sampleCount = valid.size();
        entry.sum = sum;
        entry.high = high;
        entry.low = low;
        if (valid.isEmpty()) {
            entry.lastValue = "--";
        }
    }

    /**
     * 旧版本会自动播种一条占位病例（L0001/左舱宠物、R0001/右舱宠物）。
     * 这些字段一个都没被改过，说明它不是真实病历：
     * 现在不再播种病例，这类遗留记录要清掉，列表从零开始。
     */
    private boolean isLegacySeedCase(PatientCase patient) {
        if (patient == null) {
            return false;
        }
        String caseNo = TextUtils.isEmpty(patient.caseNo) ? patient.recordNo : patient.caseNo;
        if (caseNo == null) {
            return false;
        }
        boolean left = "L0001".equalsIgnoreCase(caseNo.trim());
        boolean right = "R0001".equalsIgnoreCase(caseNo.trim());
        if (!left && !right) {
            return false;
        }
        String side = left ? "左舱" : "右舱";
        return (left ? "000001" : "000002").equals(patient.monitorNo)
                && (side + "宠物").equals(patient.petName)
                && (side + "宠物种").equals(patient.species)
                && (side + "主人").equals(patient.ownerName)
                && (side + "电话").equals(patient.ownerPhone)
                && (side + "医生").equals(patient.doctor)
                && (side + "记录").equals(patient.note);
    }

    private int recentHostModeIndex(int index) {
        return index >= 0 && index < hostModeNames().length ? index : -1;
    }

    private int clampIndex(int index, int size) {
        if (size <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(index, size - 1));
    }

    private Organization currentOrganization() {
        if (organizations.isEmpty()) {
            return new Organization("未设置医院", "未设置地址");
        }
        if (selectedOrganizationIndex < 0 || selectedOrganizationIndex >= organizations.size()) {
            selectedOrganizationIndex = 0;
        }
        return organizations.get(selectedOrganizationIndex);
    }

    private PatientCase currentCase() {
        if (cases.isEmpty()) {
            return null;
        }
        if (selectedCaseIndex < 0 || selectedCaseIndex >= cases.size()) {
            selectedCaseIndex = 0;
        }
        return cases.get(selectedCaseIndex);
    }

    private List<Integer> getVisibleCaseIndices() {
        ArrayList<Integer> visible = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            if (caseMatchesSearch(cases.get(i))) {
                visible.add(i);
            }
        }
        return visible;
    }

    private boolean caseMatchesSearch(PatientCase item) {
        if (TextUtils.isEmpty(searchKeyword)) {
            return true;
        }
        String key = searchKeyword.toLowerCase(Locale.getDefault());
        return containsLower(item.petName, key)
                || containsLower(item.ownerName, key)
                || containsLower(item.ownerPhone, key)
                || containsLower(item.caseNo, key)
                || containsLower(item.recordNo, key);
    }

    private boolean containsLower(String value, String key) {
        return value != null && value.toLowerCase(Locale.getDefault()).contains(key);
    }

    private void selectCase(int index) {
        if (index < 0 || index >= cases.size()) {
            return;
        }
        if (!canSwitchToCase(cases.get(index))) {
            return;
        }
        applySelectedCaseIndex(index);
        lastGeneratedPdf = null;
        activeTab = 0;
        showingSettings = false;
        Toast.makeText(activity, "已切换到 " + (cases.get(index).currentTreatment ? "当前治疗: " : "历史治疗: ") + cases.get(index).petName, Toast.LENGTH_SHORT).show();
    }

    private void selectMainTab(final int index) {
        PatientCase patient = currentCase();
        if (patient != null && !patient.currentTreatment && (index == 1 || index == 2)) {
            new AlertDialog.Builder(activity)
                    .setTitle("历史记录只用于查看")
                    .setMessage("主机控制和实时监护只作用于当前治疗记录。是否切回第一条当前治疗记录？")
                    .setNegativeButton("留在历史记录", null)
                    .setPositiveButton("切回当前治疗", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            applySelectedCaseIndex(getCurrentTreatmentIndex());
                            activeTab = index;
                            showingSettings = false;
                            invalidate();
                        }
                    })
                    .show();
            return;
        }
        activeTab = index;
        showingSettings = false;
    }

    private int getCurrentTreatmentIndex() {
        for (int i = 0; i < cases.size(); i++) {
            if (cases.get(i).currentTreatment) {
                return i;
            }
        }
        return 0;
    }

    private void createNewTreatmentRecord() {
        synchronized (zoneStateLock) {
            createNewTreatmentRecordLocked();
        }
    }

    private void createNewTreatmentRecordLocked() {
        String now = currentTimeText();
        PatientCase previousTreatment = currentTreatmentCase();
        if (previousTreatment != null) {
            long settleNow = System.currentTimeMillis();
            // 翻篇前先把仍开启项目的活动区间折入累计时长，避免时长丢失
            captureTreatmentSampleIfNeededLocked(true);
            settleActiveTreatmentIntervalsLocked(previousTreatment, settleNow);
            // 必须在 currentTreatment=false 之前冻结，之后共享缓冲将属于新病例。
            storeMonitorSnapshot(softwareZone(), previousTreatment, true);
            bleManager.finishTreatmentSession(previousTreatment.recordNo);
        }
        for (PatientCase item : cases) {
            if (item.currentTreatment) {
                item.currentTreatment = false;
                item.treatmentEndTime = now;
                for (TreatmentEntry entry : item.treatmentEntries) {
                    // 只结算真正开启过的项目；没开过的时间本就是空，不能被写成结束时间。
                    if ("进行中".equals(entry.endTime)) {
                        entry.endTime = now;
                    }
                }
            }
        }
        // 新记录必须是空白病历：只自动生成编号和就诊时间，其余字段留给用户填写
        String nextNo = nextCaseNumber();
        PatientCase created = new PatientCase(nextNo, nextNo, "", "", "", "", "", "", "", nextNo, now, "");
        created.currentTreatment = true;
        // 空白新建样本也要一直留在列表里，不能被「无有效字段」过滤掉
        created.explicitSample = true;
        created.treatmentStartTime = now;
        created.treatmentEndTime = "进行中";
        // 单项开始/结束时间一律留空，等真实开关沿/有效样本再写；
        // 不能在这里把“就诊时间/进行中”预置给每一项，否则没开的功能也带时间。
        // 旧宠物的门限、截图等私有数据随病例落库；监护缓冲已在翻篇前显式冻结。
        savePetScopedData();
        // 新样本追加在上一个样本下面：列表按新建顺序往下排，不插到最前。
        cases.add(created);
        selectedCaseIndex = cases.size() - 1;
        // 新宠物是空白的：门限、护理模式、截图都回到默认值；新 petId 没有波形存档，
        // restoreBuffers(null) 会同时清空实时、会话和历史缓冲，不能只清报告波形。
        loadPetScopedData();
        restoreMonitorSnapshot(softwareZone());
        activeTab = 0;
        lastGeneratedPdf = null;
        saveTreatmentRecordsToStorage();
        // 新建的是空白病历：三项必填未录入，治疗时长必须等连上主机蓝牙且补齐信息后才开始。
        bleManager.bindTreatmentSession(created.recordNo, true, hasCompleteTreatmentInfo(created));
        Toast.makeText(activity, "已创建新的治疗记录", Toast.LENGTH_SHORT).show();
    }

    private void finishCurrentTreatment() {
        synchronized (zoneStateLock) {
            finishCurrentTreatmentLocked();
        }
    }

    private void finishCurrentTreatmentLocked() {
        PatientCase patient = currentCase();
        if (patient == null) {
            return;
        }
        if (!patient.currentTreatment) {
            Toast.makeText(activity, "历史治疗记录不能结束，请切回当前治疗", Toast.LENGTH_SHORT).show();
            return;
        }
        long settleNow = System.currentTimeMillis();
        // 结束前先把仍开启项目的活动区间折入累计时长，并强制最后一次采样，避免时长丢失
        captureTreatmentSampleIfNeededLocked(true);
        settleActiveTreatmentIntervalsLocked(patient, settleNow);
        // 先冻结该病例的三路会话波形、趋势与测量历史，再把它标记为历史病例。
        storeMonitorSnapshot(softwareZone(), patient, true);
        String now = currentTimeText();
        patient.currentTreatment = false;
        patient.treatmentEndTime = now;
        for (TreatmentEntry entry : patient.treatmentEntries) {
            // 只结算真正开启过的项目；没开过的时间保持空，页面显示 "--"。
            if ("进行中".equals(entry.endTime)) {
                entry.endTime = now;
            }
        }
        Toast.makeText(activity, "当前治疗已结束", Toast.LENGTH_SHORT).show();
        lastGeneratedPdf = null;
        bleManager.finishTreatmentSession(patient.recordNo);
        // 会话波形属于已结束病例的历史报告，不能在结束动作中清掉。
        savePetScopedData();
        saveTreatmentRecordsToStorageLocked(true);
    }

    private void showOrganizationDialog() {
        String[] names = new String[organizations.size()];
        for (int i = 0; i < organizations.size(); i++) {
            names[i] = organizations.get(i).name + "\n" + organizations.get(i).address;
        }
        new AlertDialog.Builder(activity)
                .setTitle("切换机构")
                .setSingleChoiceItems(names, selectedOrganizationIndex, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        selectedOrganizationIndex = which;
                        lastGeneratedPdf = null;
                        saveUiSettings();
                        activity.notifyLanhuStateChanged();
                        Toast.makeText(activity, "已切换机构", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        invalidate();
                    }
                })
                .setNeutralButton("编辑当前机构", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showEditOrganizationDialog();
                    }
                })
                .show();
    }

    private void showEditOrganizationDialog() {
        final Organization organization = currentOrganization();
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dpInt(12);
        form.setPadding(pad, pad, pad, pad);

        final EditText name = addInput(form, "医院名称", organization.name, InputType.TYPE_CLASS_TEXT);
        final EditText address = addInput(form, "医院地址", organization.address, InputType.TYPE_CLASS_TEXT);
        final EditText phone = addInput(form, "医院电话", organization.phone, InputType.TYPE_CLASS_PHONE);

        new AlertDialog.Builder(activity)
                .setTitle("编辑医院信息")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        organization.name = cleanInput(name, organization.name);
                        organization.address = cleanInput(address, organization.address);
                        organization.phone = cleanInput(phone, organization.phone);
                        lastGeneratedPdf = null;
                        saveUiSettings();
                        activity.notifyLanhuStateChanged();
                        Toast.makeText(activity, "医院信息已保存", Toast.LENGTH_SHORT).show();
                        invalidate();
                    }
                })
                .show();
    }

    /**
     * 打开系统图片选择器，让用户为当前机构挑选 LOGO。
     */
    void chooseOrganizationLogo() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, REQUEST_ORGANIZATION_LOGO);
        } catch (Exception e) {
            Toast.makeText(activity, "无法打开图片选择器，LOGO 上传失败", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 图片选择结果：校验可解码后写入当前机构并落库。
     */
    void onOrganizationLogoSelected(Uri logoUri) {
        if (logoUri == null) {
            return;
        }
        if (!isDecodableImage(logoUri)) {
            Toast.makeText(activity, "LOGO 上传失败：文件不是有效图片", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            activity.getContentResolver().takePersistableUriPermission(
                    logoUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // 部分文档提供方不支持持久权限，当前会话仍可显示图片。
        }
        Organization organization = currentOrganization();
        organization.logoUri = logoUri.toString();
        organization.logoName = displayNameForUri(logoUri);
        lastGeneratedPdf = null;
        saveUiSettings();
        Toast.makeText(activity, "医院 Logo 已上传", Toast.LENGTH_SHORT).show();
        invalidate();
    }

    /**
     * 判断 uri 是否指向可解码的图片，避免把损坏文件写进配置。
     */
    private boolean isDecodableImage(Uri uri) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            java.io.InputStream stream = activity.getContentResolver().openInputStream(uri);
            if (stream == null) {
                return false;
            }
            try {
                BitmapFactory.decodeStream(stream, null, options);
            } finally {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
            return options.outWidth > 0 && options.outHeight > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 取 uri 的显示名，用作 LOGO 的名称描述。
     */
    private String displayNameForUri(Uri uri) {
        String fallback = uri.getLastPathSegment();
        try {
            android.database.Cursor cursor = activity.getContentResolver()
                    .query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null) {
                try {
                    int column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (column >= 0 && cursor.moveToFirst()) {
                        String displayName = cursor.getString(column);
                        if (!TextUtils.isEmpty(displayName)) {
                            return displayName;
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            // 取不到显示名时退回 uri 尾段
        }
        return fallback;
    }

    private void showSearchDialog() {
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("宠物名字 / 主人名 / 电话号码");
        input.setText(searchKeyword);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(activity)
                .setTitle("搜索患者信息")
                .setView(input)
                .setNegativeButton("取消", null)
                .setNeutralButton("清空", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        searchKeyword = "";
                        invalidate();
                    }
                })
                .setPositiveButton("搜索", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        searchKeyword = input.getText().toString().trim();
                        List<Integer> visible = getVisibleCaseIndices();
                        if (!visible.isEmpty() && !visible.contains(selectedCaseIndex)) {
                            applySelectedCaseIndex(visible.get(0));
                        }
                        Toast.makeText(activity, visible.isEmpty() ? "没有匹配患者" : "找到 " + visible.size() + " 条患者信息", Toast.LENGTH_SHORT).show();
                        invalidate();
                    }
                })
                .show();
    }

    private void handlePatientAction(int actionIndex) {
        PatientCase patient = currentCase();
        if (patient == null) {
            Toast.makeText(activity, "暂无患者信息", Toast.LENGTH_SHORT).show();
            return;
        }
        if (actionIndex == 0) {
            generatePdfAction();
        } else if (actionIndex == 1) {
            showEditPatientDialog(patient);
        } else if (actionIndex == 2) {
            confirmDeletePatient(patient);
        } else {
            downloadPdfAction();
        }
    }

    private void showEditPatientDialog(final PatientCase patient) {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dpInt(12);
        form.setPadding(pad, pad, pad, pad);

        final EditText petName = addInput(form, "宠物名称", patient.petName, InputType.TYPE_CLASS_TEXT);
        final EditText species = addInput(form, "动物种类", patient.species, InputType.TYPE_CLASS_TEXT);
        final EditText sex = addInput(form, "性别", patient.sex, InputType.TYPE_CLASS_TEXT);
        final EditText age = addInput(form, "年龄", patient.age, InputType.TYPE_CLASS_TEXT);
        final EditText ownerName = addInput(form, "宠主姓名", patient.ownerName, InputType.TYPE_CLASS_TEXT);
        final EditText ownerPhone = addInput(form, "联系电话", patient.ownerPhone, InputType.TYPE_CLASS_PHONE);
        final EditText doctor = addInput(form, "主治医师", patient.doctor, InputType.TYPE_CLASS_TEXT);
        final EditText recordNo = addInput(form, "病历号", patient.recordNo, InputType.TYPE_CLASS_TEXT);
        final EditText visitDate = addInput(form, "就诊日期", patient.visitDate, InputType.TYPE_CLASS_TEXT);
        final EditText note = addInput(form, "医嘱备注", patient.note, InputType.TYPE_CLASS_TEXT);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(form);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("编辑患者信息")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        // 三项必填缺一不可：接管 positive 按钮，校验不通过时不要自动关掉弹窗。
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface shown) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View button) {
                        String requiredName = cleanInput(petName, "");
                        String requiredNo = cleanInput(recordNo, "");
                        String requiredPhone = cleanInput(ownerPhone, "");
                        if (isMissingRequiredField(requiredName)
                                || isMissingRequiredField(requiredNo)
                                || isMissingRequiredField(requiredPhone)) {
                            Toast.makeText(activity, "宠物名称、病历号、联系电话为必填项", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        patient.petName = requiredName;
                        patient.species = cleanInput(species, patient.species);
                        patient.sex = cleanInput(sex, patient.sex);
                        patient.age = cleanInput(age, patient.age);
                        patient.ownerName = cleanInput(ownerName, patient.ownerName);
                        patient.ownerPhone = requiredPhone;
                        patient.doctor = cleanInput(doctor, patient.doctor);
                        patient.recordNo = requiredNo;
                        patient.visitDate = cleanInput(visitDate, patient.visitDate);
                        patient.note = cleanInput(note, patient.note);
                        lastGeneratedPdf = null;
                        saveTreatmentRecordsToStorage();
                        // 三项补齐后重新评估计时资格：连着主机蓝牙才从这一刻起算。
                        bindTreatmentClockToCurrentCase();
                        Toast.makeText(activity, "患者信息已保存", Toast.LENGTH_SHORT).show();
                        activity.notifyLanhuStateChanged();
                        dialog.dismiss();
                        invalidate();
                    }
                });
            }
        });
        dialog.show();
    }

    private EditText addInput(LinearLayout form, String hint, String value, int inputType) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setText(value);
        input.setInputType(inputType);
        form.addView(input);
        return input;
    }

    private String cleanInput(EditText input, String fallback) {
        String value = input.getText().toString().trim();
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    private void confirmDeletePatient(final PatientCase patient) {
        // 空记录没有宠物名：用病历号顶上，弹窗里不能出现空名字
        String label = !TextUtils.isEmpty(patient.petName) ? patient.petName
                : (!TextUtils.isEmpty(patient.recordNo) ? patient.recordNo : "该患者");
        new AlertDialog.Builder(activity)
                .setTitle("删除患者信息")
                .setMessage("确定删除 " + label + " 的 ICU 监护记录吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        synchronized (zoneStateLock) {
                            String removedPetId = patient.petId;
                            int removedIndex = cases.indexOf(patient);
                            boolean removedWasSelected = removedIndex == selectedCaseIndex;
                            // 删掉正在治疗的样本必须先把这一段的治疗计时定格并解绑
                            if (patient.currentTreatment) {
                                bleManager.finishTreatmentSession(patient.recordNo);
                            }
                            cases.remove(patient);
                            if (cases.isEmpty()) {
                                selectedCaseIndex = 0;
                            } else if (removedWasSelected) {
                                selectedCaseIndex = Math.min(Math.max(removedIndex, 0), cases.size() - 1);
                            } else if (removedIndex >= 0 && removedIndex < selectedCaseIndex) {
                                // 删除前面的病例只移动下标，不能让实际选中的宠物悄悄变成下一只。
                                selectedCaseIndex -= 1;
                            }
                            // 删掉当前治疗后要挑一条接上，否则整舱没有进行中的治疗
                            if (!hasCurrentTreatmentCase() && !cases.isEmpty()) {
                                PatientCase next = cases.get(0);
                                next.currentTreatment = true;
                                next.treatmentEndTime = "进行中";
                            }
                            lastGeneratedPdf = null;
                            // 私有设置、波形留档与同号可复用的治疗计时存档都一并清掉。
                            purgePetScopedData(removedPetId);
                            bleManager.purgeTreatmentSession(patient.recordNo);
                            if (removedWasSelected) {
                                am4100Manager.resetSessionWaves();
                            }
                            saveTreatmentRecordsToStorage();
                            // 删除之后当前宠物可能变了：换到新宠物的门限、快照与护理模式
                            loadPetScopedData();
                            if (removedWasSelected) {
                                // 当前缓冲仍属于被删宠物；恢复新选中宠物，没有存档就全部清空。
                                restoreMonitorSnapshot(softwareZone());
                            }
                            // 删除可能把"进行中"转移到另一条样本，计时资格要跟着重算
                            bindTreatmentClockToCurrentCase();
                        }
                        Toast.makeText(activity, "已删除患者信息", Toast.LENGTH_SHORT).show();
                        // 页面上的病例列表是原生数据的镜像：通知它重新拉一次，删掉的记录才会消失
                        activity.notifyLanhuStateChanged();
                        invalidate();
                    }
                })
                .show();
    }

    /** 舱里是否还有一条进行中的治疗。 */
    private boolean hasCurrentTreatmentCase() {
        for (PatientCase item : cases) {
            if (item.currentTreatment) {
                return true;
            }
        }
        return false;
    }

    /**
     * 点账号位。
     *
     * <p>登录与注册统一走网页登录页（lanhu_denglu）：没登录、切换账号、退出登录
     * 全部回到那一个页面，原生的账号输入弹窗不再出现，避免两套入口互相抢。</p>
     */
    private void handleAccountTap() {
        if (TextUtils.isEmpty(accountName)) {
            activity.navigateToLoginPage();
            return;
        }
        String organization = AccountStore.organizationOf(activity, accountName);
        new AlertDialog.Builder(activity)
                .setTitle(TextUtils.isEmpty(organization) ? "当前账号: " + accountName : "当前账号: " + accountName + "（" + organization + "）")
                .setItems(new String[]{"切换账号", "退出登录"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            // 切换账号：到网页登录页登录新账号或注册新账号
                            activity.navigateToLoginPage();
                            return;
                        }
                        AccountStore.logout(activity);
                        // 退出登录要把上一个账号的数据收起来：留在内存里会串到下一个账号。
                        setAccountName(AccountStore.currentUser(activity));
                        activity.notifyLanhuStateChanged();
                        // 退出登录后回到登录页：留在主页面会让人以为还处于登录状态。
                        activity.navigateToLoginPage();
                        Toast.makeText(activity, "已退出登录", Toast.LENGTH_SHORT).show();
                        invalidate();
                    }
                })
                .show();
    }
    /**
     * 同步当前登录账号。
     * 账号本身是设备级的：换舱换宠物都不该把人登出去。
     * 但账号里的病历数据是账号私有的，换账号要整体换一套。
     */
    void setAccountName(String name) {
        String next = name == null ? "" : name.trim();
        synchronized (zoneStateLock) {
            if (next.equals(accountName)) {
                return;
            }
            // 换账号之前先把上一个账号的数据落库，否则会写进新账号的存档里。
            savePetScopedData();
            accountName = next;
            reloadAccountScopedData();
        }
        invalidate();
    }

    /**
     * 账号切换：病例、机构、护理模式、监护门限、摄像头快照都跟着账号走，
     * 换一个账号就要整体换一遍，否则会看到上一个账号的病历。
     */
    private void reloadAccountScopedData() {
        synchronized (zoneStateLock) {
            cases.clear();
            selectedCaseIndex = 0;
            pdfRecords.clear();
            cameraSnapshots.clear();
            loadTreatmentRecordsFromStorageLocked();
            loadUiSettingsLocked();
            loadCameraSnapshotsFromStorage();
        }
    }


    private void generatePdfAction() {
        try {
            lastGeneratedPdf = generatePdfReport();
            addPdfRecord("生成PDF", lastGeneratedPdf.getName(), cacheLocationText(lastGeneratedPdf), lastGeneratedPdf, null);
            activeTab = 5;
            showingSettings = false;
            Toast.makeText(activity, "PDF已生成: " + lastGeneratedPdf.getName(), Toast.LENGTH_LONG).show();
        } catch (IOException exception) {
            Toast.makeText(activity, "生成PDF失败: " + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** H5 下载动作的同步受理回执；accepted 仍不代表文件已经生成或发布。 */
    static final class PdfDownloadRequest {
        final long requestId;
        final boolean accepted;
        private final int seq;
        private final String phase;
        private final String message;

        PdfDownloadRequest(long requestId, boolean accepted, int seq, String phase, String message) {
            this.requestId = requestId;
            this.accepted = accepted;
            this.seq = seq;
            this.phase = phase;
            this.message = message;
        }

        String receiptJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("requestId", requestId);
                json.put("accepted", accepted);
                json.put("seq", seq);
                json.put("phase", phase);
                json.put("msg", message);
                return json.toString();
            } catch (JSONException ignored) {
                return "{\"requestId\":" + requestId + ",\"accepted\":" + accepted + "}";
            }
        }
    }

    /** 原生 Canvas 入口也建立同样的请求分代，避免与 H5 下载互相覆盖。 */
    private void downloadPdfAction() {
        downloadPdfAction(beginPdfDownloadRequest());
    }

    /** H5 已在桥线程受理，这里只执行同一请求；UI 调度异常也必须形成最终失败。 */
    void performPdfDownloadAction(long requestId) {
        try {
            downloadPdfAction(requestId);
        } catch (Throwable error) {
            failPdfDownloadRequest(requestId, error, "", "");
        } finally {
            invalidate();
            activity.notifyLanhuStateChanged();
        }
    }

    private void downloadPdfAction(long requestId) {
        String downloadName = "";
        String downloadUri = "";
        try {
            if (!advancePdfDownloadStage(requestId, "generating", "正在生成PDF")) {
                return;
            }
            // 下载同样按当前监护数据重新生成，下载到的报告才有这一段的真实波形。
            lastGeneratedPdf = generatePdfReport();
            addPdfRecord("生成PDF", lastGeneratedPdf.getName(), cacheLocationText(lastGeneratedPdf), lastGeneratedPdf, null);
            downloadName = createPdfFileName();
            if (!advancePdfDownloadStage(requestId, "publishing", "PDF已生成，正在写入系统下载目录")) {
                return;
            }
            Uri uri = copyPdfToDownloads(lastGeneratedPdf, downloadName);
            if (uri == null) {
                throw new IOException("下载目录未返回文件地址");
            }
            downloadUri = uri.toString();
            addPdfRecord("下载PDF", downloadName, "系统下载目录: " + downloadUri, lastGeneratedPdf, uri);
            activeTab = 5;
            showingSettings = false;
        } catch (Throwable error) {
            failPdfDownloadRequest(requestId, error, downloadName, downloadUri);
            return;
        }
        String message = "PDF已下载: " + downloadName + "\n系统下载目录: " + downloadUri;
        // copyPdfToDownloads 返回前已完成非空复制和发布，只有这里才能报告下载成功。
        if (pushPdfDownloadStatus(requestId, true, message, downloadName, downloadUri)) {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        }
    }

    private long beginPdfDownloadRequest() {
        final long requestId;
        synchronized (zoneStateLock) {
            requestId = pdfDownloadRequests.begin();
            pdfDownloadRequestedAtMs = SystemClock.elapsedRealtime();
            updatePrintStatusLocked(requestId, "queued", "running", false,
                    "PDF下载请求已受理，正在等待生成", "", "", 0L);
        }
        notifyPrintStatusChanged();
        armPdfDownloadTimeout(requestId);
        return requestId;
    }

    private boolean advancePdfDownloadStage(long requestId, String stage, String message) {
        synchronized (zoneStateLock) {
            if (!pdfDownloadRequests.isActive(requestId)) {
                return false;
            }
            long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - pdfDownloadRequestedAtMs);
            updatePrintStatusLocked(requestId, stage, "running", false,
                    message, "", "", elapsed);
        }
        notifyPrintStatusChanged();
        return true;
    }

    private void failPdfDownloadRequest(long requestId, Throwable error,
                                        String fileName, String uri) {
        String detail = error == null ? "未知错误" : error.getMessage();
        if (TextUtils.isEmpty(detail)) {
            detail = error == null ? "未知错误" : error.getClass().getSimpleName();
        }
        String message = "下载失败: " + detail;
        Log.w(TAG, "PDF download failed, requestId=" + requestId, error);
        if (pushPdfDownloadStatus(requestId, false, message, fileName, uri)) {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        }
    }


    /**
     * 预览要下载的 PDF：先用当前数据生成，再逐页渲染出来，
     * 确认"下载出来的 PDF 长什么样"之后才落盘。
     */
    private void previewPdfAction() {
        try {
            // 每次预览都用当前数据重新生成：报告里的波形与数值必须反映此刻的监护数据，
            // 复用上一次生成的 PDF 会打印出没有波形的旧报告。
            final File previewPdf = generatePdfReport();
            lastGeneratedPdf = previewPdf;
            addPdfRecord("生成PDF", previewPdf.getName(), cacheLocationText(previewPdf), previewPdf, null);
            PdfPreviewDialog.show(activity, previewPdf, "PDF预览：" + previewPdf.getName(), new PdfPreviewDialog.Listener() {
                @Override
                public void onPrint() {
                    printPdfFile(previewPdf);
                }
            });
        } catch (IOException exception) {
            Toast.makeText(activity, "预览PDF失败: " + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 系统打印：把当前报告 PDF 交给系统打印框架，
     * 打印实现复用 jxin462-sudo/icu 的 PdfPrintAdapter 方案。
     */
    private void printPdfAction() {
        try {
            // 打印前按当前监护数据重新生成，保证纸上的是最新真实波形。
            File pdf = generatePdfReport();
            lastGeneratedPdf = pdf;
            addPdfRecord("生成PDF", pdf.getName(), cacheLocationText(pdf), pdf, null);
            printPdfFile(pdf);
        } catch (IOException exception) {
            Toast.makeText(activity, "打印失败: " + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 调起系统打印，并回推打印结果。 */
    private void printPdfFile(final File pdf) {
        if (pdf == null || !pdf.exists()) {
            pushPrintStatus(false, "PDF文件不存在，无法打印");
            return;
        }
        final PrinterSettings settings = PrinterSettings.load(activity);
        if (!TextUtils.isEmpty(settings.getAddress())) {
            // 直连：IPP 网络打印机走 IPP，9100 原始端口自动走 PCL 光栅，
            // 协议与回退都在 PrinterDirectService 内部决定，这里不再分流。
            PrinterDirectService.print(activity, pdf, settings.getAddress(), settings.getCopies(),
                    new PrinterDirectService.ResultListener() {
                        @Override
                        public void onResult(boolean ok, String message) {
                            // 直连打印没有系统打印界面兜底，成功失败都要当场告诉操作者，
                            // 否则点了"打印"没反应，用户根本不知道发生了什么。
                            // 文案由 PrinterDirectService 统一给出（打印完成 / 打印任务已受理 / 打印失败），
                            // 这里不再叠加前缀，免得出现"打印完成：打印失败：…"这种自相矛盾的说法。
                            Toast.makeText(activity,
                                    message, Toast.LENGTH_LONG).show();
                            pushPrintStatus(ok, message);
                        }
                    });
            return;
        }
        // 只有没填打印机地址时才退到系统打印框架：现场这台打印机没装厂商驱动，
        // 系统打印只会把任务堆在队列里、界面却提示打印成功，所以它只是最后的兜底。
        PdfPrintHelper.print(activity, pdf, settings.getCopies(), new PdfPrintHelper.ResultListener() {
            @Override
            public void onPrintResult(boolean ok, String message) {
                if (!ok && PdfPrintHelper.NO_PRINT_SERVICE.equals(message)) {
                    // 设备上没有打印服务时系统打印界面是空的，点了等于没反应：
                    // 直接把报告存进下载目录，用户至少还能拿到文件。
                    pushPrintStatus(false, savePdfToDownloadsInstead(pdf));
                    return;
                }
                pushPrintStatus(ok, message);
            }
        });
    }

    /**
     * 打印服务不可用时的兜底：把报告存进系统下载目录，
     * 避免用户点了打印却什么都没有发生。
     */
    private String savePdfToDownloadsInstead(File pdf) {
        try {
            String downloadName = createPdfFileName();
            Uri uri = copyPdfToDownloads(pdf, downloadName);
            addPdfRecord("下载PDF", downloadName, "系统下载目录: " + uri.toString(), pdf, uri);
            invalidate();
            return "系统没有可用的打印服务，已把PDF保存到下载目录: " + downloadName;
        } catch (IOException exception) {
            return "系统没有可用的打印服务，保存到下载目录也失败了: " + exception.getMessage();
        }
    }

    /**
     * 桥线程收到下载动作时先做轻量校验并记录受理，不能把 action 返回当成文件已经落盘。
     * 当前病例缺必填项时不再排入 UI 生成队列，避免无意义的长时间等待。
     */
    PdfDownloadRequest acceptPdfDownloadRequest() {
        final String rejection;
        final PdfDownloadRequest request;
        synchronized (zoneStateLock) {
            long requestId = pdfDownloadRequests.begin();
            pdfDownloadRequestedAtMs = SystemClock.elapsedRealtime();
            PatientCase patient = currentCase();
            String validationError = null;
            if (patient == null) {
                validationError = "暂无患者信息";
            } else {
                String missing = missingReportFields(patient);
                if (!TextUtils.isEmpty(missing)) {
                    validationError = "请先补全患者信息: " + missing;
                }
            }
            boolean accepted = validationError == null;
            if (!accepted) {
                rejection = "下载失败: " + validationError;
                long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - pdfDownloadRequestedAtMs);
                updatePrintStatusLocked(requestId, "error", "error", false,
                        rejection, "", "", elapsed);
                pdfDownloadRequests.finish(requestId);
            } else {
                rejection = null;
                updatePrintStatusLocked(requestId, "queued", "running", false,
                        "PDF下载请求已受理，正在等待生成", "", "", 0L);
            }
            request = new PdfDownloadRequest(requestId, accepted, printStatusSeq,
                    printStatusPhase, printStatusMsg);
        }
        notifyPrintStatusChanged();
        if (rejection != null) {
            if (activity != null) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(activity, rejection, Toast.LENGTH_LONG).show();
                    }
                });
            }
        } else {
            armPdfDownloadTimeout(request.requestId);
        }
        return request;
    }

    /** 30 秒仍未被 UI 执行或结束时明确报错，不能永远停在 running。 */
    private void armPdfDownloadTimeout(final long requestId) {
        synchronized (zoneStateLock) {
            if (!pdfDownloadRequests.isActive(requestId)) {
                return;
            }
            cancelPdfDownloadTimeoutLocked(0L);
            pdfDownloadTimeoutRequestId = requestId;
            pdfDownloadTimeoutRunnable = new Runnable() {
                @Override
                public void run() {
                    synchronized (zoneStateLock) {
                        if (requestId != pdfDownloadTimeoutRequestId) {
                            return;
                        }
                        pdfDownloadTimeoutRunnable = null;
                        pdfDownloadTimeoutRequestId = 0L;
                    }
                    String message = "下载失败: 请求超过30秒未完成，未确认文件已发布，请重试";
                    if (pushPdfDownloadStatus(requestId, false, message, "", "")) {
                        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                    }
                }
            };
            monitorAlarmHandler.postDelayed(pdfDownloadTimeoutRunnable,
                    PDF_DOWNLOAD_QUEUE_TIMEOUT_MS);
        }
    }

    /** requestId 为 0 表示替换计时器；否则只能取消同一请求的计时器。 */
    private void cancelPdfDownloadTimeoutLocked(long requestId) {
        if (requestId > 0L && requestId != pdfDownloadTimeoutRequestId) {
            return;
        }
        if (pdfDownloadTimeoutRunnable != null) {
            monitorAlarmHandler.removeCallbacks(pdfDownloadTimeoutRunnable);
            pdfDownloadTimeoutRunnable = null;
        }
        pdfDownloadTimeoutRequestId = 0L;
    }

    private boolean pushPdfDownloadStatus(long requestId, boolean ok, String message,
                                          String fileName, String uri) {
        synchronized (zoneStateLock) {
            if (!pdfDownloadRequests.finish(requestId)) {
                return false;
            }
            long elapsed = pdfDownloadRequestedAtMs > 0L
                    ? Math.max(0L, SystemClock.elapsedRealtime() - pdfDownloadRequestedAtMs) : 0L;
            updatePrintStatusLocked(requestId, ok ? "success" : "error",
                    ok ? "success" : "error", ok, message, fileName, uri, elapsed);
            // 旧请求即使与新请求交错结束，也不能取消新请求的 watchdog。
            cancelPdfDownloadTimeoutLocked(requestId);
        }
        notifyPrintStatusChanged();
        return true;
    }

    /**
     * 打印结果回推给前端：前端据此提示成功/失败，
     * 不用等用户在系统打印界面里操作完才知道结果。
     */
    private void pushPrintStatus(boolean ok, String message) {
        synchronized (zoneStateLock) {
            // 既有打印接口的 ok 可能只表示任务受理，不能标成 PDF 下载成功。
            updatePrintStatusLocked(0L, "result", "result", ok, message, "", "", 0L);
        }
        notifyPrintStatusChanged();
    }

    private void updatePrintStatusLocked(long requestId, String stage, String phase,
                                         boolean ok, String message, String fileName,
                                         String uri, long elapsedMs) {
        printStatusSeq++;
        printStatusRequestId = Math.max(0L, requestId);
        printStatusStage = stage == null ? "" : stage;
        printStatusPhase = phase == null ? "" : phase;
        printStatusOk = ok;
        printStatusMsg = message == null ? "" : message;
        printStatusFileName = fileName == null ? "" : fileName;
        printStatusUri = uri == null ? "" : uri;
        printStatusElapsedMs = Math.max(0L, elapsedMs);
    }

    private void notifyPrintStatusChanged() {
        if (activity != null) {
            activity.notifyLanhuStateChanged();
        }
    }

    private void addPdfRecord(String action, String fileName, String location, File cacheFile, Uri downloadUri) {
        PatientCase patient = currentCase();
        String patientName = patient == null ? "-" : patient.petName;
        String timeText = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        PdfRecord record = new PdfRecord(action, fileName, patientName, timeText, location, cacheFile, downloadUri);
        record.zone = softwareZone();
        pdfRecords.add(0, record);
        while (pdfRecords.size() > 30) {
            pdfRecords.remove(pdfRecords.size() - 1);
        }
    }

    /** 当前舱的 PDF/下载记录；左右舱的报告列表各自独立。 */
    private ArrayList<PdfRecord> pdfRecordsForCurrentZone() {
        ArrayList<PdfRecord> result = new ArrayList<>();
        String zone = softwareZone();
        for (int i = 0; i < pdfRecords.size(); i++) {
            PdfRecord record = pdfRecords.get(i);
            if (TextUtils.isEmpty(record.zone) || record.zone.equals(zone)) {
                result.add(record);
            }
        }
        return result;
    }

    private String cacheLocationText(File file) {
        return file == null ? "应用缓存目录" : "应用缓存目录: " + file.getAbsolutePath();
    }

    private File generatePdfReport() throws IOException {
        PatientCase patient;
        Am4100Manager.MonitorSnapshot monitorSnapshot;
        synchronized (zoneStateLock) {
            patient = currentCase();
            if (patient == null) {
                throw new IOException("暂无患者信息");
            }
            String missing = missingReportFields(patient);
            if (!TextUtils.isEmpty(missing)) {
                throw new IOException("请先补全患者信息: " + missing);
            }
            if (patient.currentTreatment) {
                captureTreatmentSampleIfNeededLocked(true);
            }
            // 报告只消费这个病例自己的冻结快照；历史病例没有存档时保持无数据。
            monitorSnapshot = loadMonitorSnapshot(softwareZone(), patient);
        }
        File output = new File(activity.getCacheDir(), createPdfFileName());
        PdfDocument document = new PdfDocument();
        PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(595, 842, 1).create());
        Canvas pdfCanvas = page.getCanvas();
        Paint pdfPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        String createdAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        drawTemplatePdf(pdfCanvas, pdfPaint, patient, currentOrganization(), createdAt, monitorSnapshot);
        document.finishPage(page);

        boolean useLegacyPdfTemplate = false;
        if (useLegacyPdfTemplate) {
        drawPdfHeader(pdfCanvas, pdfPaint, "动物ICU重症监护报告", "生成时间: " + createdAt);

        int y = 116;
        y = drawPdfSection(pdfCanvas, pdfPaint, "医院信息", y);
        Organization org = currentOrganization();
        y = drawPdfLine(pdfCanvas, pdfPaint, "医院名称", org.name, y);
        y = drawPdfLine(pdfCanvas, pdfPaint, "医院地址", org.address, y);
        y += 10;
        y = drawPdfSection(pdfCanvas, pdfPaint, "患者信息", y);
        y = drawPdfLine(pdfCanvas, pdfPaint, "宠物名称", patient.petName + " / " + patient.species + " / " + patient.sex, y);
        y = drawPdfLine(pdfCanvas, pdfPaint, "年龄", patient.age, y);
        y = drawPdfLine(pdfCanvas, pdfPaint, "宠主姓名", patient.ownerName, y);
        y = drawPdfLine(pdfCanvas, pdfPaint, "联系电话", patient.ownerPhone, y);
        y = drawPdfLine(pdfCanvas, pdfPaint, "病历号", patient.recordNo, y);
        y = drawPdfLine(pdfCanvas, pdfPaint, "就诊日期", patient.visitDate, y);
        y = drawPdfLine(pdfCanvas, pdfPaint, "主治医师", patient.doctor, y);
        y = drawPdfLine(pdfCanvas, pdfPaint, "备注", patient.note, y);
        y += 10;
        y = drawPdfSection(pdfCanvas, pdfPaint, "ICU状态", y);
        y = drawPdfLine(pdfCanvas, pdfPaint, "舱内温度", bleManager.getCabinTempText(), y);
        y = drawPdfLine(pdfCanvas, pdfPaint, "氧浓度", bleManager.getOxygenText(), y);
        y = drawPdfLine(pdfCanvas, pdfPaint, "湿度", bleManager.getHumidityText(), y);
        y = drawPdfLine(pdfCanvas, pdfPaint, "CO2浓度", bleManager.getCo2Text(), y);
        y = drawPdfLine(pdfCanvas, pdfPaint, "治疗时长", bleManager.getTreatmentTimeText(), y);
        y += 10;
        y = drawPdfTreatmentRecordBlock(pdfCanvas, pdfPaint, patient, y);
        document.finishPage(page);

        PdfDocument.Page monitorPage = document.startPage(new PdfDocument.PageInfo.Builder(595, 842, 2).create());
        Canvas monitorCanvas = monitorPage.getCanvas();
        drawPdfHeader(monitorCanvas, pdfPaint, "实时监护与电波图", "患者: " + patient.petName + "    生成时间: " + createdAt);
        y = 116;
        y = drawPdfSection(monitorCanvas, pdfPaint, "实时监护", y);
        drawPdfMonitorBlock(monitorCanvas, pdfPaint, y);
        document.finishPage(monitorPage);

        }

        FileOutputStream out = new FileOutputStream(output);
        try {
            document.writeTo(out);
        } finally {
            out.close();
            document.close();
        }
        return output;
    }

    private void drawTemplatePdf(Canvas canvas, Paint pdfPaint, PatientCase patient, Organization org,
                                 String createdAt, Am4100Manager.MonitorSnapshot monitorSnapshot) {
        pdfFill(canvas, pdfPaint, 0, 0, 595, 842, Color.WHITE);
        drawPdfTemplateHeader(canvas, pdfPaint, org);
        drawPdfTemplatePatientInfo(canvas, pdfPaint, patient);
        drawPdfTemplateTreatment(canvas, pdfPaint, patient);
        drawPdfTemplateVitalsTable(canvas, pdfPaint,
                monitorSnapshot == null ? null : monitorSnapshot.measurementHistory);
        drawPdfTemplateWave(canvas, pdfPaint, "\u3010\u5fc3\u7535\u56fe\u3011", "\u3010\u5fc3\u7387\u8d8b\u52bf\u3011", 333,
                Color.rgb(55, 220, 92), monitorSnapshot == null ? null : monitorSnapshot.sessionEcgWaveSamples,
                monitorSnapshot == null ? null : monitorSnapshot.heartRateHistory);
        drawPdfTemplateWave(canvas, pdfPaint, "\u3010\u8109\u640f\u6ce2\u5f62\u56fe\u3011", "\u3010\u8840\u6c27\u8d8b\u52bf\u3011", 387,
                Color.rgb(245, 74, 72), monitorSnapshot == null ? null : monitorSnapshot.sessionSpo2WaveSamples,
                monitorSnapshot == null ? null : monitorSnapshot.spo2History);
        drawPdfTemplateWave(canvas, pdfPaint, "\u3010\u547c\u5438\u7387\u56fe\u3011", "\u3010\u547c\u5438\u7387\u8d8b\u52bf\u3011", 441,
                Color.rgb(53, 176, 232), monitorSnapshot == null ? null : monitorSnapshot.sessionRespWaveSamples,
                monitorSnapshot == null ? null : monitorSnapshot.respHistory);
        drawPdfTemplateEnvironment(canvas, pdfPaint);
        drawPdfTemplateImageAlarm(canvas, pdfPaint);
        drawPdfTemplateConclusion(canvas, pdfPaint, patient, org, createdAt);
    }

    /** PDF 内容区的右边界，与表头、表格保持一致。 */
    private static final float PDF_CONTENT_RIGHT = 568f;
    // 结论页底部字段（治疗结论/医院地址/主管护士）共享的竖直列线起点
    private static final float PDF_CONCLUSION_COLUMN_X = 150f;
    /** PDF 信息行中相邻两列之间预留的空白，避免相邻字段的文字互相压字。 */
    private static final float PDF_INFO_COLUMN_GAP = 6f;
    /** 患者信息两行共用的列起始位置，保证上下两行的字段竖向对齐。 */
    private static final float[] PDF_PATIENT_INFO_COLUMNS = {28f, 150f, 253f, 360f, 466f};
    /** 治疗项目区三列的起始位置。 */
    private static final float[] PDF_TREATMENT_COLUMNS = {30f, 245f, 440f};

    private void drawPdfTemplateHeader(Canvas canvas, Paint pdfPaint, Organization org) {
        int headerBg = Color.rgb(228, 241, 252);
        int titleBlue = Color.rgb(0, 158, 218);
        int titleDark = Color.rgb(0, 118, 194);
        int strongText = Color.rgb(58, 62, 68);
        pdfFill(canvas, pdfPaint, 0, 13, 595, 52, headerBg);

        Path darkWedge = new Path();
        darkWedge.moveTo(318, 0);
        darkWedge.lineTo(378, 0);
        darkWedge.lineTo(344, 52);
        darkWedge.lineTo(284, 52);
        darkWedge.close();
        pdfPaint.setShader(null);
        pdfPaint.setStyle(Paint.Style.FILL);
        pdfPaint.setColor(titleDark);
        canvas.drawPath(darkWedge, pdfPaint);

        Path banner = new Path();
        banner.moveTo(360, 0);
        banner.lineTo(595, 0);
        banner.lineTo(595, 52);
        banner.lineTo(330, 52);
        banner.close();
        pdfPaint.setColor(titleBlue);
        canvas.drawPath(banner, pdfPaint);

        String hospitalName = pdfCleanValue(org == null ? "" : org.name);
        if (pdfIsBlankValue(hospitalName)) {
            hospitalName = "\u533b\u9662\u540d\u79f0";
        }
        // 优先画医院上传的真实 LOGO，缺失或解码失败时回退到文字占位
        Bitmap headerLogo = loadLogoBitmap(org == null ? null : org.logoUri);
        if (headerLogo != null) {
            float logoWidth = 34f * headerLogo.getWidth() / headerLogo.getHeight();
            float logoHeight = 34f;
            if (logoWidth > 70f) {
                logoWidth = 70f;
                logoHeight = logoWidth * headerLogo.getHeight() / headerLogo.getWidth();
            }
            float logoTop = 13 + (39 - logoHeight) / 2f;
            pdfPaint.setShader(null);
            pdfPaint.setStyle(Paint.Style.FILL);
            canvas.drawBitmap(headerLogo, null,
                    new RectF(16, logoTop, 16 + logoWidth, logoTop + logoHeight), pdfPaint);
            headerLogo.recycle();
        } else {
            pdfText(canvas, pdfPaint, "LOGO", 16, 38, 18, strongText, true);
        }
        pdfTextFit(canvas, pdfPaint, hospitalName, 89, 36, 230, 15, strongText, false);
        pdfTextCenter(canvas, pdfPaint, "ICU\u52a8\u7269\u8231\u6cbb\u7597\u8bb0\u5f55\u5355", new RectF(360, 0, 590, 52), 18, Color.WHITE, true);
    }

    private void drawPdfTemplatePatientInfo(Canvas canvas, Paint pdfPaint, PatientCase patient) {
        float y1 = 73;
        float y2 = 94;
        // 上下两行共用同一组列起始位置，标签竖向对齐；
        // 每列值的可用宽度由下一列的位置反推，字段再长也不会压到相邻列的标签上。
        drawPdfInfoRow(canvas, pdfPaint, y1, PDF_PATIENT_INFO_COLUMNS,
                new float[]{45f, 38f, 32f, 52f, 30f},
                new String[]{"\u52a8\u7269\u540d\u79f0", "\u4f4f\u9662\u53f7", "\u4e3b\u4eba", "\u6cbb\u7597\u65e5\u671f", "\u8231\u4f4d"},
                new String[]{patient.petName, patient.recordNo, patient.ownerName,
                        pdfDateOnly(patient.visitDate), patient.monitorNo});
        drawPdfInfoRow(canvas, pdfPaint, y2, PDF_PATIENT_INFO_COLUMNS,
                new float[]{45f, 32f, 32f, 52f, 52f},
                new String[]{"\u52a8\u7269\u79cd\u7c7b", "\u4f53\u91cd", "\u79d1\u5ba4", "\u6cbb\u7597\u65f6\u957f", "\u64cd\u4f5c\u533b\u5e08"},
                new String[]{patient.species, "--", "--",
                        pdfTreatmentDuration(patient),
                        pdfFirstValue(patient.doctor, accountName)});
    }

    /**
     * 绘制一行「标签: 值」形式的信息字段。
     * 每一列的值区域右边界固定在下一列的起始位置之前，
     * 内容过长时只会压缩并截断本列自己的值，不会覆盖相邻列的标签。
     */
    private void drawPdfInfoRow(Canvas canvas, Paint pdfPaint, float y, float[] columnX, float[] labelWidths,
                                String[] labels, String[] values) {
        int count = Math.min(labels.length, Math.min(values.length, columnX.length));
        for (int i = 0; i < count; i++) {
            float labelWidth = labelWidths[i];
            float valueX = columnX[i] + labelWidth;
            float nextX = (i + 1 < columnX.length) ? columnX[i + 1] : PDF_CONTENT_RIGHT;
            drawPdfInfoField(canvas, pdfPaint, labels[i], values[i], columnX[i], y, labelWidth,
                    Math.max(20f, nextX - valueX - PDF_INFO_COLUMN_GAP));
        }
    }

    private void drawPdfTemplateTreatment(Canvas canvas, Paint pdfPaint, PatientCase patient) {
        drawPdfSectionTitle(canvas, pdfPaint, "\u3010\u6cbb\u7597\u9879\u76ee\u3011", 28, 119, 92, 568);
        TreatmentEntry temp = pdfFindTreatmentEntry(patient, "\u8231\u5185\u6e29\u5ea6", "\u6052\u6e29\u6cbb\u7597");
        TreatmentEntry oxygen = pdfFindTreatmentEntry(patient, "\u6c27\u6d53\u5ea6", "\u6c27\u6c14\u6d53\u5ea6", "\u6c27\u6c14\u6cbb\u7597");
        TreatmentEntry nebulizer = pdfFindTreatmentEntry(patient, "\u96fe\u5316\u5668", "\u96fe\u5316\u6cbb\u7597");
        TreatmentEntry blueLight = pdfFindTreatmentEntry(patient, "\u84dd\u5149\u7406\u7597", "\u84dd\u5149\u6cbb\u7597", "\u84dd\u5149\u7406\u7597");
        TreatmentEntry redLight = pdfFindTreatmentEntry(patient, "\u7ea2\u5916\u7406\u7597", "\u7ea2\u5916\u6cbb\u7597", "\u7ea2\u5149\u6cbb\u7597", "\u7ea2\u5149\u7406\u7597");
        TreatmentEntry anion = pdfFindTreatmentEntry(patient, "\u8d1f\u79bb\u5b50", "\u8d1f\u79bb\u5b50\u6cbb\u7597");

        drawPdfTreatmentCheck(canvas, pdfPaint, PDF_TREATMENT_COLUMNS[0], 141, "\u6052\u6e29\u6cbb\u7597", pdfEntryEnabled(temp),
                "\u76ee\u6807\u6e29\u5ea6", pdfEntryValue(temp), 68,
                PDF_TREATMENT_COLUMNS[1]);
        drawPdfTreatmentCheck(canvas, pdfPaint, PDF_TREATMENT_COLUMNS[1], 141, "\u96fe\u5316\u6cbb\u7597", pdfEntryUsed(nebulizer),
                "\u65f6\u957f", pdfSessionDuration(nebulizer), 66, PDF_TREATMENT_COLUMNS[2]);
        drawPdfTreatmentCheck(canvas, pdfPaint, PDF_TREATMENT_COLUMNS[2], 141, "\u7ea2\u5149\u6cbb\u7597", pdfEntryUsed(redLight),
                "", pdfSessionDuration(redLight), 76, PDF_CONTENT_RIGHT);

        drawPdfTreatmentCheck(canvas, pdfPaint, PDF_TREATMENT_COLUMNS[0], 162, "\u6c27\u6c14\u6cbb\u7597", pdfEntryUsed(oxygen),
                "\u6c27\u6d53\u5ea6", pdfEntryValue(oxygen), 68,
                PDF_TREATMENT_COLUMNS[1]);
        drawPdfTreatmentCheck(canvas, pdfPaint, PDF_TREATMENT_COLUMNS[1], 162, "\u84dd\u5149\u6cbb\u7597", pdfEntryUsed(blueLight),
                "", pdfSessionDuration(blueLight), 76, PDF_TREATMENT_COLUMNS[2]);
        drawPdfTreatmentCheck(canvas, pdfPaint, PDF_TREATMENT_COLUMNS[2], 162, "\u8d1f\u79bb\u5b50\u6cbb\u7597", pdfEntryUsed(anion),
                "", pdfSessionDuration(anion), 88, PDF_CONTENT_RIGHT);
    }

    private void drawPdfTemplateVitalsTable(Canvas canvas, Paint pdfPaint,
                                            List<Am4100Manager.VitalSample> measurementHistory) {
        drawPdfSectionTitle(canvas, pdfPaint, "\u3010\u751f\u547d\u4f53\u5f81\u8bb0\u5f55\u3011", 28, 198, 118, 568);
        float left = 28;
        float top = 209;
        // 旧版只把"打印那一刻"的实时值写进第一行、后三行全空，等于没有录入过程数据；
        // 脉率、呼吸率也整列缺失。改为按 AM4100 带时间戳的测量历史录入 4 个真实时刻。
        float[] widths = {100, 66, 66, 100, 66, 66, 76};
        String[] headers = {"\u65f6\u95f4", "\u5fc3\u7387", "\u8840\u6c27", "\u8840\u538b",
                "\u4f53\u6e29", "\u8109\u7387", "\u547c\u5438\u7387"};
        String[][] rows = pdfVitalsRows(measurementHistory);
        drawPdfTableRow(canvas, pdfPaint, headers, left, top, widths, 20, true, 0);
        for (int i = 0; i < rows.length; i++) {
            drawPdfTableRow(canvas, pdfPaint, rows[i], left, top + 20 + i * 20, widths, 20, false, i);
        }
    }

    /**
     * 从监护仪真实测量历史组出 4 条记录：同一分钟内的各项数值合并成一条，
     * 再在治疗全程里均匀取最多 4 个时刻（短治疗不会重复、长治疗能覆盖到开头）。
     */
    private String[][] pdfVitalsRows(List<Am4100Manager.VitalSample> history) {
        ArrayList<ArrayList<Am4100Manager.VitalSample>> buckets = new ArrayList<>();
        ArrayList<Long> bucketKeys = new ArrayList<>();
        if (history != null) {
            for (Am4100Manager.VitalSample sample : history) {
                if (sample == null || sample.time <= 0L) {
                    continue;
                }
                long key = sample.time / 60000L;
                int index = bucketKeys.indexOf(key);
                if (index < 0) {
                    bucketKeys.add(key);
                    buckets.add(new ArrayList<Am4100Manager.VitalSample>());
                    index = buckets.size() - 1;
                }
                buckets.get(index).add(sample);
            }
        }
        String[][] rows = new String[4][7];
        for (int i = 0; i < rows.length; i++) {
            for (int j = 0; j < rows[i].length; j++) {
                rows[i][j] = "";
            }
        }
        int total = buckets.size();
        int slots = Math.min(rows.length, total);
        for (int i = 0; i < slots; i++) {
            int index = slots == 1 ? total - 1
                    : (int) Math.round(i * (total - 1.0) / (slots - 1));
            rows[i] = pdfVitalsRow(buckets.get(index));
        }
        // 无历史存档就保留空行；不能用另一个病例或重启后的实时值补造测量时刻。
        return rows;
    }

    private String[] pdfVitalsRow(ArrayList<Am4100Manager.VitalSample> group) {
        String[] row = {"--", "--", "--", "--", "--", "--", "--"};
        if (group == null || group.isEmpty()) {
            return row;
        }
        row[0] = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(group.get(0).time));
        int sys = -1;
        int dia = -1;
        for (Am4100Manager.VitalSample sample : group) {
            switch (sample.param) {
                case Am4100Manager.PARAM_HEART_RATE:
                    row[1] = pdfVitalNumber(sample.value);
                    break;
                case Am4100Manager.PARAM_SPO2:
                    row[2] = pdfVitalNumber(sample.value);
                    break;
                case Am4100Manager.PARAM_BP_SYS:
                    sys = (int) Math.round(sample.value);
                    break;
                case Am4100Manager.PARAM_BP_DIA:
                    dia = (int) Math.round(sample.value);
                    break;
                case Am4100Manager.PARAM_TEMP:
                    row[4] = pdfVitalNumber(sample.value);
                    break;
                case Am4100Manager.PARAM_PULSE:
                    row[5] = pdfVitalNumber(sample.value);
                    break;
                case Am4100Manager.PARAM_RESP:
                    row[6] = pdfVitalNumber(sample.value);
                    break;
                default:
                    break;
            }
        }
        if (sys >= 0 || dia >= 0) {
            row[3] = (sys >= 0 ? Integer.toString(sys) : "--") + "/"
                    + (dia >= 0 ? Integer.toString(dia) : "--");
        }
        return row;
    }

    private String pdfVitalNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
            return "--";
        }
        if (Math.abs(value - Math.round(value)) < 0.05d) {
            return String.valueOf(Math.round(value));
        }
        return String.format(Locale.US, "%.1f", value);
    }

    private void drawPdfTemplateWave(Canvas canvas, Paint pdfPaint, String label, String trendLabel, float top,
                                     int color, List<Integer> waveSamples, List<Integer> trendSamples) {
        float labelLeft = 54;
        float boxLeft = 98;
        float boxRight = 568;
        float boxHeight = 48;
        boolean hasWave = waveHasSignal(waveSamples);
        boolean hasTrend = trendSamples != null && trendSamples.size() >= 2;
        pdfTextRight(canvas, pdfPaint, hasWave || !hasTrend ? label : trendLabel,
                labelLeft + 34, top + 24, 10, Color.rgb(96, 101, 108), true);
        pdfRoundRect(canvas, pdfPaint, boxLeft, top, boxRight, top + boxHeight, 2, Color.BLACK, Color.TRANSPARENT, 0);
        if (hasWave) {
            drawSmoothSampleWave(canvas, boxLeft + 5, top + boxHeight / 2f, boxRight - 5, boxHeight * 0.34f,
                    color, waveSamples, 1.05f);
        } else if (hasTrend) {
            drawSmoothSampleWave(canvas, boxLeft + 5, top + boxHeight / 2f, boxRight - 5, boxHeight * 0.34f,
                    color, trendSamples, 1.05f);
        } else {
            pdfTextCenter(canvas, pdfPaint, "无信号", rect(boxLeft, top, boxRight, top + boxHeight), 9,
                    Color.rgb(150, 158, 168), false);
        }
    }

    private void drawPdfTemplateEnvironment(Canvas canvas, Paint pdfPaint) {
        drawPdfSectionTitle(canvas, pdfPaint, "\u3010\u73af\u5883\u53c2\u6570\u3011", 28, 515, 92, 568);
        drawPdfInfoRow(canvas, pdfPaint, 543,
                new float[]{28f, 180f, 315f, 497f},
                new float[]{62f, 38f, 58f, 30f},
                new String[]{"\u8231\u5185\u6e29\u5ea6", "\u6e7f\u5ea6", "CO2\u6d53\u5ea6", "\u6c27\u6d53\u5ea6"},
                new String[]{bleManager.getCabinTempText(), bleManager.getHumidityText(),
                        bleManager.getCo2Text(), bleManager.getOxygenText()});
    }

    private void drawPdfTemplateImageAlarm(Canvas canvas, Paint pdfPaint) {
        drawPdfSectionTitle(canvas, pdfPaint, "\u3010\u5f71\u50cf\u8bb0\u5f55\u3011", 28, 581, 92, 263);
        drawPdfSectionTitle(canvas, pdfPaint, "\u3010\u62a5\u8b66\u4e8b\u4ef6\u3011", 340, 581, 415, 568);

        CameraSnapshot first = cameraSnapshots.size() > 0 ? cameraSnapshots.get(0) : null;
        CameraSnapshot second = cameraSnapshots.size() > 1 ? cameraSnapshots.get(1) : null;
        drawPdfSnapshotBox(canvas, pdfPaint, new RectF(31, 599, 70, 638), first, "\u56fe\u7247");
        drawPdfSnapshotBox(canvas, pdfPaint, new RectF(91, 599, 130, 638), second, "\u56fe\u7247");
        drawPdfSnapshotBox(canvas, pdfPaint, new RectF(216, 599, 255, 638), null, "\u4e8c\u7ef4\u7801");
        pdfTextCenter(canvas, pdfPaint, "\u6cbb\u7597\u524d", new RectF(24, 648, 78, 663), 8.8f, Color.rgb(96, 101, 108), false);
        pdfTextCenter(canvas, pdfPaint, "\u6cbb\u7597\u540e", new RectF(84, 648, 138, 663), 8.8f, Color.rgb(96, 101, 108), false);
        pdfTextCenter(canvas, pdfPaint, "\u626b\u7801\u67e5\u770b\u539f\u56fe", new RectF(184, 648, 286, 663), 8.8f, Color.rgb(96, 101, 108), false);
        pdfTextCenter(canvas, pdfPaint, pdfSnapshotTime(first), new RectF(19, 664, 83, 678), 7.2f, Color.rgb(108, 114, 122), false);
        pdfTextCenter(canvas, pdfPaint, pdfSnapshotTime(second), new RectF(79, 664, 143, 678), 7.2f, Color.rgb(108, 114, 122), false);

        RectF alarmBox = new RectF(340, 599, 568, 654);
        pdfRoundRect(canvas, pdfPaint, alarmBox.left, alarmBox.top, alarmBox.right, alarmBox.bottom, 2,
                Color.rgb(229, 239, 251), Color.TRANSPARENT, 0);
        pdfDrawWrappedText(canvas, pdfPaint, pdfAlarmText(), alarmBox.left + 8, alarmBox.top + 18,
                alarmBox.width() - 16, 9.5f, Color.rgb(96, 101, 108), false, 3, 14);
    }

    private void drawPdfTemplateConclusion(Canvas canvas, Paint pdfPaint, PatientCase patient, Organization org, String createdAt) {
        drawPdfSectionTitle(canvas, pdfPaint, "\u3010\u6cbb\u7597\u7ed3\u8bba\u3011", PDF_CONCLUSION_COLUMN_X, 704, PDF_CONCLUSION_COLUMN_X + 64f, 568);
        pdfText(canvas, pdfPaint, "\u6cbb\u7597\u6548\u679c:", 28, 728, 9.5f, Color.rgb(96, 101, 108), false);
        drawPdfCheckbox(canvas, pdfPaint, 82, 719, false);
        pdfText(canvas, pdfPaint, "\u826f\u597d", 96, 728, 9.5f, Color.rgb(96, 101, 108), false);
        drawPdfCheckbox(canvas, pdfPaint, 128, 719, false);
        pdfText(canvas, pdfPaint, "\u4e00\u822c", 142, 728, 9.5f, Color.rgb(96, 101, 108), false);
        drawPdfCheckbox(canvas, pdfPaint, 176, 719, false);
        pdfText(canvas, pdfPaint, "\u5dee", 190, 728, 9.5f, Color.rgb(96, 101, 108), false);

        pdfText(canvas, pdfPaint, "\u51fa\u9662\u5efa\u8bae:", 28, 751, 9.5f, Color.rgb(96, 101, 108), false);
        RectF advice = new RectF(78, 738, 568, 771);
        pdfRoundRect(canvas, pdfPaint, advice.left, advice.top, advice.right, advice.bottom, 2,
                Color.rgb(229, 239, 251), Color.TRANSPARENT, 0);
        pdfDrawWrappedText(canvas, pdfPaint, patient.note, advice.left + 8, advice.top + 15, advice.width() - 16,
                9.2f, Color.rgb(96, 101, 108), false, 2, 13);
        pdfText(canvas, pdfPaint, "\u590d\u8bca\u65f6\u95f4: " + pdfFirstValue(""), 28, 793, 9.5f, Color.rgb(96, 101, 108), false);

        pdfText(canvas, pdfPaint, "\u64cd\u4f5c\u533b\u5e08: " + pdfFooterValue(pdfFirstValue(patient.doctor, accountName)), 28, 821, 9.3f, Color.rgb(96, 101, 108), false);
        pdfText(canvas, pdfPaint, "\u4e3b\u7ba1\u62a4\u58eb: ________", PDF_CONCLUSION_COLUMN_X, 821, 9.3f, Color.rgb(96, 101, 108), false);
        pdfText(canvas, pdfPaint, "\u52a8\u7269\u4e3b\u4eba: " + pdfFooterValue(patient.ownerName), 280, 821, 9.3f, Color.rgb(96, 101, 108), false);
        pdfText(canvas, pdfPaint, "\u6253\u5370\u65f6\u95f4: " + createdAt, 430, 821, 9.0f, Color.rgb(96, 101, 108), false);

        pdfText(canvas, pdfPaint, "\u533b\u9662\u7535\u8bdd: " + pdfCleanValue(org == null ? "" : org.phone), 28, 838, 8.4f, Color.rgb(96, 101, 108), false);
        pdfTextFit(canvas, pdfPaint, "\u533b\u9662\u5730\u5740: " + pdfCleanValue(org == null ? "" : org.address), PDF_CONCLUSION_COLUMN_X, 838, 270, 8.4f, Color.rgb(96, 101, 108), false);
        pdfText(canvas, pdfPaint, "\u203b \u672c\u68c0\u6d4b\u7ed3\u679c\u4ec5\u5bf9\u8be5\u6837\u672c\u8d1f\u8d23", 430, 838, 8.4f, Color.rgb(96, 101, 108), false);
    }

    private void drawPdfSectionTitle(Canvas canvas, Paint pdfPaint, String title, float x, float y, float lineStart, float lineEnd) {
        pdfText(canvas, pdfPaint, title, x, y, 12.5f, Color.rgb(78, 82, 88), true);
        pdfLine(canvas, pdfPaint, lineStart, y - 3, lineEnd, y - 3, Color.rgb(105, 174, 255), 0.8f);
    }

    private void drawPdfInfoField(Canvas canvas, Paint pdfPaint, String label, String value, float x, float y, float labelWidth, float valueWidth) {
        int color = Color.rgb(96, 101, 108);
        // 标签同样走 fit：标签过长时只压缩标签本身，不会盖住右侧的值。
        pdfTextFit(canvas, pdfPaint, label + ": ", x, y, labelWidth, 9.5f, color, false);
        pdfTextFit(canvas, pdfPaint, pdfCleanValue(value), x + labelWidth, y, valueWidth, 9.5f, color, false);
    }

    private void drawPdfTreatmentCheck(Canvas canvas, Paint pdfPaint, float x, float baseline, String label, boolean checked,
                                       String detailLabel, String detailValue, float detailOffset, float valueLimit) {
        int color = Color.rgb(96, 101, 108);
        drawPdfCheckbox(canvas, pdfPaint, x, baseline - 8, checked);
        pdfText(canvas, pdfPaint, label, x + 14, baseline, 9.6f, color, false);
        String cleanValue = pdfCleanValue(detailValue);
        float valueX;
        if (!TextUtils.isEmpty(detailLabel)) {
            pdfText(canvas, pdfPaint, detailLabel + ": ", x + detailOffset, baseline, 9.4f, color, false);
            valueX = x + detailOffset + 45;
        } else {
            valueX = x + detailOffset;
        }
        // 值区域右边界固定在下一列之前，数值再长也不会压到相邻列的文本。
        pdfTextFit(canvas, pdfPaint, cleanValue, valueX, baseline,
                Math.max(20f, valueLimit - valueX - PDF_INFO_COLUMN_GAP), 9.4f, color, false);
    }

    private void drawPdfCheckbox(Canvas canvas, Paint pdfPaint, float x, float y, boolean checked) {
        RectF box = new RectF(x, y, x + 8, y + 8);
        pdfStrokeRect(canvas, pdfPaint, box.left, box.top, box.right, box.bottom,
                checked ? Color.rgb(95, 171, 255) : Color.rgb(112, 118, 124), 0.8f);
        if (checked) {
            pdfLine(canvas, pdfPaint, x + 1.6f, y + 4.2f, x + 3.5f, y + 6.3f, Color.rgb(95, 171, 255), 1.1f);
            pdfLine(canvas, pdfPaint, x + 3.5f, y + 6.3f, x + 7.1f, y + 1.6f, Color.rgb(95, 171, 255), 1.1f);
        }
    }

    private void drawPdfTableRow(Canvas canvas, Paint pdfPaint, String[] cells, float left, float top, float[] widths,
                                 float height, boolean header, int rowIndex) {
        int fillColor = header ? Color.rgb(226, 238, 250)
                : rowIndex % 2 == 1 ? Color.rgb(229, 239, 251) : Color.WHITE;
        float right = left;
        for (float width : widths) {
            right += width;
        }
        pdfFill(canvas, pdfPaint, left, top, right, top + height, fillColor);
        float x = left;
        for (int i = 0; i < cells.length && i < widths.length; i++) {
            pdfTextFit(canvas, pdfPaint, cells[i] == null ? "" : cells[i], x + 8, top + 13.5f,
                    widths[i] - 12, 8.9f, Color.rgb(96, 101, 108), header);
            x += widths[i];
        }
    }

    private void drawPdfSnapshotBox(Canvas canvas, Paint pdfPaint, RectF box, CameraSnapshot snapshot, String placeholder) {
        boolean drewBitmap = false;
        if (snapshot != null && snapshot.cacheFile != null && snapshot.cacheFile.exists()) {
            Bitmap bitmap = BitmapFactory.decodeFile(snapshot.cacheFile.getAbsolutePath());
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, box, pdfPaint);
                bitmap.recycle();
                drewBitmap = true;
            }
        }
        if (!drewBitmap) {
            pdfRoundRect(canvas, pdfPaint, box.left, box.top, box.right, box.bottom, 2,
                    Color.rgb(155, 158, 162), Color.TRANSPARENT, 0);
            pdfTextCenter(canvas, pdfPaint, placeholder, box, 9.2f, Color.WHITE, false);
        }
        pdfRoundRect(canvas, pdfPaint, box.left, box.top, box.right, box.bottom, 2,
                Color.TRANSPARENT, Color.rgb(160, 168, 176), 0.6f);
    }

    private void pdfFill(Canvas canvas, Paint pdfPaint, float left, float top, float right, float bottom, int color) {
        pdfPaint.setShader(null);
        pdfPaint.setStyle(Paint.Style.FILL);
        pdfPaint.setColor(color);
        canvas.drawRect(left, top, right, bottom, pdfPaint);
    }

    private void pdfStrokeRect(Canvas canvas, Paint pdfPaint, float left, float top, float right, float bottom, int color, float strokeWidth) {
        pdfPaint.setShader(null);
        pdfPaint.setStyle(Paint.Style.STROKE);
        pdfPaint.setStrokeWidth(strokeWidth);
        pdfPaint.setColor(color);
        canvas.drawRect(left, top, right, bottom, pdfPaint);
        pdfPaint.setStyle(Paint.Style.FILL);
    }

    private void pdfRoundRect(Canvas canvas, Paint pdfPaint, float left, float top, float right, float bottom,
                              float radius, int fillColor, int strokeColor, float strokeWidth) {
        RectF rect = new RectF(left, top, right, bottom);
        pdfPaint.setShader(null);
        if (fillColor != Color.TRANSPARENT) {
            pdfPaint.setStyle(Paint.Style.FILL);
            pdfPaint.setColor(fillColor);
            canvas.drawRoundRect(rect, radius, radius, pdfPaint);
        }
        if (strokeColor != Color.TRANSPARENT && strokeWidth > 0f) {
            pdfPaint.setStyle(Paint.Style.STROKE);
            pdfPaint.setStrokeWidth(strokeWidth);
            pdfPaint.setColor(strokeColor);
            canvas.drawRoundRect(rect, radius, radius, pdfPaint);
            pdfPaint.setStyle(Paint.Style.FILL);
        }
    }

    private void pdfLine(Canvas canvas, Paint pdfPaint, float x1, float y1, float x2, float y2, int color, float strokeWidth) {
        pdfPaint.setShader(null);
        pdfPaint.setStyle(Paint.Style.STROKE);
        pdfPaint.setStrokeWidth(strokeWidth);
        pdfPaint.setColor(color);
        canvas.drawLine(x1, y1, x2, y2, pdfPaint);
        pdfPaint.setStyle(Paint.Style.FILL);
    }

    private void pdfText(Canvas canvas, Paint pdfPaint, String value, float x, float y, float size, int color, boolean bold) {
        pdfPaint.setShader(null);
        pdfPaint.setStyle(Paint.Style.FILL);
        pdfPaint.setTextAlign(Paint.Align.LEFT);
        pdfPaint.setTextSize(size);
        pdfPaint.setColor(color);
        pdfPaint.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
        canvas.drawText(value == null ? "" : value, x, y, pdfPaint);
    }

    private void pdfTextRight(Canvas canvas, Paint pdfPaint, String value, float x, float y, float size, int color, boolean bold) {
        pdfPaint.setShader(null);
        pdfPaint.setStyle(Paint.Style.FILL);
        pdfPaint.setTextAlign(Paint.Align.RIGHT);
        pdfPaint.setTextSize(size);
        pdfPaint.setColor(color);
        pdfPaint.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
        canvas.drawText(value == null ? "" : value, x, y, pdfPaint);
        pdfPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void pdfTextCenter(Canvas canvas, Paint pdfPaint, String value, RectF rect, float size, int color, boolean bold) {
        pdfPaint.setShader(null);
        pdfPaint.setStyle(Paint.Style.FILL);
        pdfPaint.setTextAlign(Paint.Align.CENTER);
        pdfPaint.setTextSize(size);
        pdfPaint.setColor(color);
        pdfPaint.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
        Paint.FontMetrics metrics = pdfPaint.getFontMetrics();
        float baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(value == null ? "" : value, rect.centerX(), baseline, pdfPaint);
        pdfPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void pdfTextFit(Canvas canvas, Paint pdfPaint, String value, float x, float y, float maxWidth,
                            float size, int color, boolean bold) {
        String text = value == null ? "" : value;
        float textSize = size;
        pdfPaint.setShader(null);
        pdfPaint.setStyle(Paint.Style.FILL);
        pdfPaint.setTextAlign(Paint.Align.LEFT);
        pdfPaint.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
        pdfPaint.setColor(color);
        pdfPaint.setTextSize(textSize);
        while (textSize > 6f && pdfPaint.measureText(text) > maxWidth) {
            textSize -= 0.5f;
            pdfPaint.setTextSize(textSize);
        }
        String display = text;
        while (display.length() > 1 && pdfPaint.measureText(display) > maxWidth) {
            display = display.substring(0, display.length() - 1);
        }
        if (!display.equals(text) && display.length() > 3) {
            display = display.substring(0, Math.max(0, display.length() - 3)) + "...";
        }
        canvas.drawText(display, x, y, pdfPaint);
    }

    private void pdfDrawWrappedText(Canvas canvas, Paint pdfPaint, String value, float x, float y, float maxWidth,
                                    float size, int color, boolean bold, int maxLines, float lineHeight) {
        String text = pdfCleanValue(value);
        if (pdfIsBlankValue(text)) {
            return;
        }
        pdfPaint.setShader(null);
        pdfPaint.setStyle(Paint.Style.FILL);
        pdfPaint.setTextAlign(Paint.Align.LEFT);
        pdfPaint.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
        pdfPaint.setTextSize(size);
        pdfPaint.setColor(color);
        StringBuilder line = new StringBuilder();
        int drawn = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            line.append(ch);
            if (pdfPaint.measureText(line.toString()) > maxWidth && line.length() > 1) {
                line.deleteCharAt(line.length() - 1);
                canvas.drawText(line.toString(), x, y + drawn * lineHeight, pdfPaint);
                drawn++;
                if (drawn >= maxLines) {
                    return;
                }
                line.setLength(0);
                line.append(ch);
            }
        }
        if (drawn < maxLines && line.length() > 0) {
            canvas.drawText(line.toString(), x, y + drawn * lineHeight, pdfPaint);
        }
    }

    private String pdfCleanValue(String value) {
        if (TextUtils.isEmpty(value)) {
            return "--";
        }
        String clean = value.trim();
        if (TextUtils.isEmpty(clean) || "-".equals(clean) || "--".equals(clean) || "--/--".equals(clean)
                || "\u5f85\u5f55\u5165".equals(clean) || "\u672a\u77e5".equals(clean)) {
            return "--";
        }
        return clean;
    }

    private boolean pdfIsBlankValue(String value) {
        return "--".equals(pdfCleanValue(value));
    }

    private String pdfFirstValue(String... values) {
        if (values != null) {
            for (String value : values) {
                String clean = pdfCleanValue(value);
                if (!pdfIsBlankValue(clean)) {
                    return clean;
                }
            }
        }
        return "--";
    }

    private String pdfDateOnly(String value) {
        String clean = pdfCleanValue(value);
        if (pdfIsBlankValue(clean)) {
            return "--";
        }
        return clean.length() >= 10 ? clean.substring(0, 10) : clean;
    }

    /**
     * 「治疗时长」只允许出现时长。旧代码用 pdfFirstValue(治疗时长, treatmentEndTime)，
     * 主机计时为 "--" 时会回退显示结束时间（yyyy-MM-dd HH:mm:ss），
     * 而 pdfCleanValue 不把日期判为空，于是年月日直接填进了时长列；
     * 长字符串还会被 pdfTextFit 压到 6f，看起来比同排字段小一圈。
     * 这里回退到本病例治疗项真实累计时长，并统一压成 00:00 短格式。
     */
    private String pdfTreatmentDuration(PatientCase patient) {
        String clock = pdfCleanValue(bleManager.getTreatmentTimeText());
        if (!pdfIsBlankValue(clock)) {
            return clock;
        }
        long maxMs = 0L;
        if (patient != null) {
            long now = System.currentTimeMillis();
            for (TreatmentEntry entry : patient.treatmentEntries) {
                long ms = TreatmentUsage.effectiveOnMs(entry.totalOnMs, entry.featureOn,
                        entry.featureOnSince, now);
                if (ms > maxMs) {
                    maxMs = ms;
                }
            }
        }
        return TreatmentClockMath.durationOnly(maxMs);
    }

    private TreatmentEntry pdfFindTreatmentEntry(PatientCase patient, String... names) {
        if (patient == null || names == null) {
            return null;
        }
        for (TreatmentEntry entry : patient.treatmentEntries) {
            if (entry == null || TextUtils.isEmpty(entry.itemName)) {
                continue;
            }
            for (String name : names) {
                if (TextUtils.isEmpty(name)) {
                    continue;
                }
                if (TextUtils.equals(entry.itemName, name) || entry.itemName.contains(name) || name.contains(entry.itemName)) {
                    return entry;
                }
            }
        }
        return null;
    }

    private boolean pdfEntryEnabled(TreatmentEntry entry) {
        // 报告勾选反映当前病例是否真实使用过该治疗项，而非此刻全局设备的开关
        return pdfEntryUsed(entry);
    }

    /** 真实开关沿/时长才算使用；病例预置的默认 startTime 不能证明项目开启过。 */
    private boolean pdfEntryUsed(TreatmentEntry entry) {
        if (entry == null) {
            return false;
        }
        return TreatmentUsage.wasSessionUsed(entry.featureOn,
                entry.featureOnSince, entry.totalOnMs);
    }

    /** 会话类治疗项累计时长；非会话项或未产生时长返回 "--"。 */
    private String pdfSessionDuration(TreatmentEntry entry) {
        return TreatmentUsage.durationOrPlaceholder(sessionUsageText(entry));
    }

    private boolean pdfTreatmentOn(int controlIndex, TreatmentEntry entry) {
        return bleManager.isControlOn(controlIndex);
    }

    private String pdfEntryValue(TreatmentEntry entry) {
        if (entry == null) {
            return "--";
        }
        return pdfFirstValue(entry.manualValue, entry.sampleCount > 0 ? treatmentLastValueText(entry) : "", treatmentAverageText(entry));
    }

    private String pdfControlDurationText(int controlIndex) {
        Integer minutes = bleManager.getControlTimeValue(controlIndex);
        if (minutes != null) {
            return formatClockMinutes(minutes);
        }
        String value = pdfCleanValue(bleManager.getControlValue(controlIndex));
        if ("\u5f00".equals(value) || "\u5173".equals(value)) {
            return "--";
        }
        return value;
    }

    private String pdfFooterValue(String value) {
        String clean = pdfCleanValue(value);
        return pdfIsBlankValue(clean) ? "________" : clean;
    }

    private String pdfSnapshotTime(CameraSnapshot snapshot) {
        if (snapshot == null) {
            return "";
        }
        String clean = pdfCleanValue(snapshot.timeText);
        if (pdfIsBlankValue(clean)) {
            return "";
        }
        return clean.length() > 16 ? clean.substring(5, 16) : clean;
    }

    private String pdfAlarmText() {
        ArrayList<String> alarms = new ArrayList<>();
        if (isMonitorTrendAlarm(MONITOR_TREND_HEART)) {
            alarms.add("\u5fc3\u7387\u5f02\u5e38");
        }
        if (isMonitorTrendAlarm(MONITOR_TREND_BP)) {
            alarms.add("\u8840\u538b\u5f02\u5e38");
        }
        if (isMonitorTrendAlarm(MONITOR_TREND_SPO2)) {
            alarms.add("\u8840\u6c27\u5f02\u5e38");
        }
        if (isMonitorTrendAlarm(MONITOR_TREND_TEMP)) {
            alarms.add("\u4f53\u6e29\u5f02\u5e38");
        }
        if (isMonitorTrendAlarm(MONITOR_TREND_RESP)) {
            alarms.add("\u547c\u5438\u5f02\u5e38");
        }
        if (alarms.isEmpty()) {
            return "\u65e0";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < alarms.size(); i++) {
            if (i > 0) {
                builder.append("\u3001");
            }
            builder.append(alarms.get(i));
        }
        return builder.toString();
    }

    private String missingReportFields(PatientCase patient) {
        ArrayList<String> missing = new ArrayList<>();
        if (isBlankPatientValue(patient.petName)) {
            missing.add("宠物名称");
        }
        if (isBlankPatientValue(patient.species)) {
            missing.add("动物种类");
        }
        if (isBlankPatientValue(patient.ownerName)) {
            missing.add("宠主姓名");
        }
        if (isBlankPatientValue(patient.ownerPhone)) {
            missing.add("联系电话");
        }
        if (missing.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) {
                builder.append("、");
            }
            builder.append(missing.get(i));
        }
        return builder.toString();
    }

    private boolean isBlankPatientValue(String value) {
        if (TextUtils.isEmpty(value)) {
            return true;
        }
        String clean = value.trim();
        return "--".equals(clean) || "-".equals(clean) || "待录入".equals(clean) || "未知".equals(clean);
    }

    private int drawPdfSection(Canvas canvas, Paint pdfPaint, String title, int y) {
        pdfPaint.setColor(Color.rgb(45, 52, 82));
        pdfPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        pdfPaint.setTextSize(15);
        canvas.drawText(title, 42, y, pdfPaint);
        pdfPaint.setStrokeWidth(1);
        canvas.drawLine(42, y + 8, 552, y + 8, pdfPaint);
        return y + 30;
    }

    private int drawPdfLine(Canvas canvas, Paint pdfPaint, String label, String value, int y) {
        pdfPaint.setColor(Color.rgb(47, 56, 72));
        pdfPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        pdfPaint.setTextSize(11);
        canvas.drawText(label + ":", 54, y, pdfPaint);
        pdfPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        canvas.drawText(value == null ? "-" : value, 130, y, pdfPaint);
        return y + 22;
    }

    private int drawPdfTreatmentRecordBlock(Canvas canvas, Paint pdfPaint, PatientCase patient, int y) {
        y = drawPdfSection(canvas, pdfPaint, "治疗记录", y);
        pdfPaint.setColor(Color.rgb(92, 103, 118));
        pdfPaint.setTextSize(9);
        pdfPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        canvas.drawText("开始: " + patient.treatmentStartTime + "    结束: " + patient.treatmentEndTime, 54, y, pdfPaint);
        y += 18;

        String[] headers = {"项目", "开启", "入报告", "平均", "最高", "最低", "手工修订", "时间段"};
        float[] widths = {64, 32, 42, 54, 54, 54, 74, 112};
        drawPdfTreatmentRow(canvas, pdfPaint, headers, widths, y, true);
        y += 18;
        int count = 0;
        for (TreatmentEntry entry : patient.treatmentEntries) {
            if (!entry.includeInReport) {
                continue;
            }
            String[] row = {
                    entry.itemName,
                    entry.enabled ? "开" : "关",
                    "是",
                    treatmentAverageText(entry),
                    treatmentHighText(entry),
                    treatmentLowText(entry),
                    TextUtils.isEmpty(entry.manualValue) ? "-" : entry.manualValue,
                    treatmentPeriodLabel(entry)
            };
            drawPdfTreatmentRow(canvas, pdfPaint, row, widths, y, false);
            y += 18;
            count++;
            if (count >= 10 || y > 805) {
                break;
            }
        }
        if (count == 0) {
            pdfPaint.setColor(Color.rgb(92, 103, 118));
            pdfPaint.setTextSize(10);
            canvas.drawText("暂无勾选录入报告的治疗项目。", 54, y, pdfPaint);
            y += 18;
        }
        return y + 8;
    }

    private void drawPdfTreatmentRow(Canvas canvas, Paint pdfPaint, String[] cells, float[] widths, int y, boolean header) {
        float x = 54;
        pdfPaint.setStyle(Paint.Style.FILL);
        pdfPaint.setColor(header ? Color.rgb(234, 238, 242) : Color.WHITE);
        canvas.drawRect(54, y - 12, 540, y + 5, pdfPaint);
        pdfPaint.setStyle(Paint.Style.STROKE);
        pdfPaint.setStrokeWidth(0.6f);
        pdfPaint.setColor(Color.rgb(154, 166, 178));
        canvas.drawRect(54, y - 12, 540, y + 5, pdfPaint);
        pdfPaint.setStyle(Paint.Style.FILL);
        pdfPaint.setTextSize(7.2f);
        pdfPaint.setTypeface(Typeface.create(Typeface.DEFAULT, header ? Typeface.BOLD : Typeface.NORMAL));
        pdfPaint.setColor(Color.rgb(47, 56, 72));
        for (int i = 0; i < cells.length && i < widths.length; i++) {
            canvas.drawText(cells[i] == null ? "-" : cells[i], x + 3, y, pdfPaint);
            x += widths[i];
        }
    }

    private void drawPdfHeader(Canvas canvas, Paint pdfPaint, String title, String subtitle) {
        pdfPaint.setStyle(Paint.Style.FILL);
        pdfPaint.setColor(Color.WHITE);
        canvas.drawRect(0, 0, 595, 842, pdfPaint);
        pdfPaint.setColor(Color.rgb(35, 45, 65));
        pdfPaint.setTextSize(22);
        pdfPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText(title, 42, 55, pdfPaint);
        pdfPaint.setTextSize(11);
        pdfPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        canvas.drawText(subtitle, 42, 78, pdfPaint);
        pdfPaint.setColor(Color.rgb(230, 236, 242));
        canvas.drawRect(42, 92, 553, 94, pdfPaint);
    }

    private int drawPdfMonitorBlock(Canvas canvas, Paint pdfPaint, int y) {
        float cardTop = y - 6;
        RectF card = new RectF(48, cardTop, 547, cardTop + 78);
        pdfPaint.setStyle(Paint.Style.FILL);
        pdfPaint.setColor(Color.rgb(246, 250, 253));
        canvas.drawRoundRect(card, 8, 8, pdfPaint);
        pdfPaint.setStyle(Paint.Style.STROKE);
        pdfPaint.setStrokeWidth(1);
        pdfPaint.setColor(Color.rgb(154, 166, 178));
        canvas.drawRoundRect(card, 8, 8, pdfPaint);
        pdfPaint.setStyle(Paint.Style.FILL);

        drawPdfMetric(canvas, pdfPaint, "心率", am4100Manager.getHeartRateText(), "bpm", 66, y + 20, Color.rgb(57, 135, 64));
        drawPdfMetric(canvas, pdfPaint, "血压", am4100Manager.getBloodPressureText(), "mmHg", 220, y + 20, Color.rgb(237, 139, 42));
        drawPdfMetric(canvas, pdfPaint, "血氧", am4100Manager.getSpo2Text(), "%", 386, y + 20, Color.rgb(190, 50, 48));
        drawPdfMetric(canvas, pdfPaint, "脉率", am4100Manager.getPulseRateText(), "bpm", 66, y + 54, Color.rgb(57, 135, 64));
        drawPdfMetric(canvas, pdfPaint, "体温", am4100Manager.getBodyTempText(), "℃", 220, y + 54, Color.rgb(60, 160, 180));
        drawPdfMetric(canvas, pdfPaint, "呼吸率", am4100Manager.getRespText(), "brpm", 386, y + 54, Color.rgb(74, 124, 170));

        float waveTop = card.bottom + 18;
        RectF waveCard = new RectF(48, waveTop, 547, waveTop + 452);
        pdfPaint.setStyle(Paint.Style.FILL);
        pdfPaint.setColor(Color.rgb(12, 18, 28));
        canvas.drawRoundRect(waveCard, 8, 8, pdfPaint);
        pdfPaint.setStyle(Paint.Style.STROKE);
        pdfPaint.setStrokeWidth(1);
        pdfPaint.setColor(Color.rgb(80, 95, 115));
        canvas.drawRoundRect(waveCard, 8, 8, pdfPaint);
        pdfPaint.setStyle(Paint.Style.FILL);

        drawPdfWaveRow(canvas, pdfPaint, "心率 " + dash(am4100Manager.getHeartRateText()) + " bpm", 70, waveTop + 48, 520, 18, Color.rgb(92, 220, 96), am4100Manager.getEcgWaveForReport(), am4100Manager.getHeartRateHistory());
        drawPdfWaveRow(canvas, pdfPaint, "血氧 " + dash(am4100Manager.getSpo2Text()) + " %", 70, waveTop + 112, 520, 18, Color.rgb(232, 80, 72), am4100Manager.getSpo2WaveForReport(), am4100Manager.getSpo2History());
        drawPdfWaveRow(canvas, pdfPaint, "脉率 " + dash(am4100Manager.getPulseRateText()) + " bpm", 70, waveTop + 176, 520, 18, Color.rgb(92, 190, 110), am4100Manager.getSpo2WaveForReport(), am4100Manager.getPulseRateHistory());
        drawPdfWaveRow(canvas, pdfPaint, "血压 " + am4100Manager.getBloodPressureText() + " mmHg", 70, waveTop + 240, 520, 18, Color.rgb(237, 139, 42), null, am4100Manager.getBloodPressureHistory());
        drawPdfWaveRow(canvas, pdfPaint, "体温 " + dash(am4100Manager.getBodyTempText()) + " ℃", 70, waveTop + 304, 520, 18, Color.rgb(60, 160, 180), null, am4100Manager.getTemperatureHistory());
        drawPdfWaveRow(canvas, pdfPaint, "呼吸率 " + dash(am4100Manager.getRespText()) + " brpm", 70, waveTop + 368, 520, 18, Color.rgb(96, 166, 232), am4100Manager.getRespWaveForReport(), am4100Manager.getRespHistory());

        pdfPaint.setColor(Color.rgb(180, 195, 210));
        pdfPaint.setTextSize(9);
        pdfPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        canvas.drawText("历史电波图: 记录本次连接期间的监护数值变化；最近数据: " + dash(am4100Manager.getLastFrameSummary()), 62, waveCard.bottom - 16, pdfPaint);
        return (int) waveCard.bottom + 18;
    }

    private void drawPdfMetric(Canvas canvas, Paint pdfPaint, String label, String value, String unit, float x, float y, int color) {
        pdfPaint.setStyle(Paint.Style.FILL);
        pdfPaint.setColor(Color.rgb(92, 103, 118));
        pdfPaint.setTextSize(9);
        pdfPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        canvas.drawText(label, x, y - 13, pdfPaint);
        pdfPaint.setColor(color);
        pdfPaint.setTextSize(18);
        pdfPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText(dash(value), x, y + 6, pdfPaint);
        pdfPaint.setTextSize(8);
        pdfPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        canvas.drawText(unit, x + 74, y + 4, pdfPaint);
    }

    private void drawPdfWaveRow(Canvas canvas, Paint pdfPaint, String label, float left, float baseline, float right, float amp,
                                int color, List<Integer> waveSamples, List<Integer> trendSamples) {
        pdfPaint.setStyle(Paint.Style.FILL);
        pdfPaint.setColor(color);
        pdfPaint.setTextSize(10);
        pdfPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText(label, left, baseline - amp - 12, pdfPaint);
        pdfPaint.setColor(Color.rgb(38, 50, 66));
        for (int i = 0; i < 5; i++) {
            canvas.drawRect(left, baseline - amp + i * (amp * 2 / 4f), right, baseline - amp + i * (amp * 2 / 4f) + 0.6f, pdfPaint);
        }
        boolean hasWave = waveHasSignal(waveSamples);
        boolean hasTrend = trendSamples != null && trendSamples.size() >= 2;
        drawReportWave(canvas, left + 52, baseline, right, amp, color, waveSamples, trendSamples, 1.6f);
        if (!hasWave) {
            pdfTextRight(canvas, pdfPaint, hasTrend ? "测量趋势" : "无信号",
                    right, baseline - amp - 12, 8, Color.rgb(122, 134, 148), false);
        }
    }

    /**
     * 报告里的波形：只用监护仪真实采样。恒定采样说明通道没有信号，
     * 这时改画真实测量趋势；两者都没有就画一条基线，不再编造一条会动的曲线。
     */
    private void drawReportWave(Canvas canvas, float left, float baseline, float right, float amp, int color,
                                List<Integer> waveSamples, List<Integer> trendSamples, float strokeWidth) {
        if (waveHasSignal(waveSamples)) {
            drawSmoothSampleWave(canvas, left, baseline, right, amp, color, waveSamples, strokeWidth);
            return;
        }
        if (trendSamples != null && trendSamples.size() >= 2) {
            drawSmoothSampleWave(canvas, left, baseline, right, amp, color, trendSamples, strokeWidth);
            return;
        }
        drawWaveBaseline(canvas, left, baseline, right, amp, color);
    }

    /** 采样恒定说明该通道没有真实波形信号，阈值与前端保持一致。 */
    private static final int WAVE_FLAT_AMPLITUDE = 2;

    /**
     * 判定一段采样里是否真的有波形：点数足够且幅度超过阈值。
     * 恒定采样（例如没接导联/探头）说明通道没有信号。
     */
    private boolean waveHasSignal(List<Integer> samples) {
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

    /** 没有真实波形也没有测量值时，画一条基线代替编出来的曲线。 */
    private void drawWaveBaseline(Canvas canvas, float left, float baseline, float right, float amp, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, dp(0.8f)));
        paint.setColor(Color.argb(120, Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawLine(left, baseline, right, baseline, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private String createPdfFileName() {
        PatientCase patient = currentCase();
        String no = patient == null ? "empty" : sanitizeFileName(patient.recordNo + "_" + patient.petName);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return "icu_report_" + no + "_" + stamp + ".pdf";
    }

    private String sanitizeFileName(String value) {
        if (TextUtils.isEmpty(value)) {
            return "report";
        }
        return value.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    private String createCameraSnapshotFileName() {
        PatientCase patient = currentCase();
        String no = patient == null ? "camera" : sanitizeFileName(patient.recordNo + "_" + patient.petName);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return "icu_camera_" + no + "_" + stamp + ".jpg";
    }

    private Uri copyImageToPictures(File source, String fileName) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ICUCamera");
            Uri uri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("无法创建图片文件");
            }
            OutputStream output = activity.getContentResolver().openOutputStream(uri);
            if (output == null) {
                throw new IOException("无法打开图片文件");
            }
            copyFile(source, output);
            return uri;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ICUCamera");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建图片目录");
        }
        File target = new File(dir, fileName);
        FileOutputStream output = new FileOutputStream(target);
        copyFile(source, output);
        return Uri.fromFile(target);
    }

    private void saveCameraSnapshotsToStorage() {
        try {
            JSONArray array = new JSONArray();
            for (CameraSnapshot snapshot : cameraSnapshots) {
                JSONObject item = new JSONObject();
                item.put("fileName", snapshot.fileName);
                item.put("timeText", snapshot.timeText);
                item.put("cachePath", snapshot.cacheFile == null ? "" : snapshot.cacheFile.getAbsolutePath());
                item.put("downloadUri", snapshot.downloadUri == null ? "" : snapshot.downloadUri.toString());
                array.put(item);
            }
            SharedPreferences prefs = accountPrefs();
            prefs.edit().putString(petKey(KEY_CAMERA_SNAPSHOTS), array.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void loadCameraSnapshotsFromStorage() {
        cameraSnapshots.clear();
        // 空存档或解析失败也必须撤销上一宠物的选中项，不能残留旧截图索引。
        selectedCameraSnapshotIndex = -1;
        SharedPreferences prefs = accountPrefs();
        String raw = getPetString(prefs, KEY_CAMERA_SNAPSHOTS, "");
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                File file = new File(item.optString("cachePath", ""));
                if (!file.exists()) {
                    continue;
                }
                String uriText = item.optString("downloadUri", "");
                Uri uri = TextUtils.isEmpty(uriText) ? null : Uri.parse(uriText);
                cameraSnapshots.add(new CameraSnapshot(
                        item.optString("fileName", file.getName()),
                        item.optString("timeText", ""),
                        file,
                        uri));
            }
            selectedCameraSnapshotIndex = cameraSnapshots.isEmpty() ? -1 : 0;
        } catch (Exception ignored) {
        }
    }

    private Uri copyPdfToDownloads(File source, String fileName) throws IOException {
        if (source == null || !source.isFile() || source.length() <= 0L) {
            throw new IOException("生成的PDF文件为空");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = activity.getContentResolver();
            Uri uri = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    throw new IOException("无法创建下载文件");
                }
                OutputStream output = resolver.openOutputStream(uri);
                if (output == null) {
                    throw new IOException("无法打开下载文件");
                }
                if (copyFile(source, output) <= 0L) {
                    throw new IOException("写入下载目录的PDF为空");
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(uri, values, null, null) <= 0) {
                    throw new IOException("无法发布下载文件");
                }
                return uri;
            } catch (IOException exception) {
                deleteIncompleteDownload(resolver, uri);
                throw exception;
            } catch (RuntimeException exception) {
                deleteIncompleteDownload(resolver, uri);
                throw exception;
            }
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建下载目录");
        }
        File target = new File(dir, fileName);
        try {
            FileOutputStream output = new FileOutputStream(target);
            if (copyFile(source, output) <= 0L || target.length() <= 0L) {
                throw new IOException("写入下载目录的PDF为空");
            }
            return Uri.fromFile(target);
        } catch (IOException exception) {
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "Unable to delete incomplete PDF: " + target.getAbsolutePath());
            }
            throw exception;
        } catch (RuntimeException exception) {
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "Unable to delete incomplete PDF: " + target.getAbsolutePath());
            }
            throw exception;
        }
    }

    private void deleteIncompleteDownload(ContentResolver resolver, Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            resolver.delete(uri, null, null);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to delete incomplete download", exception);
        }
    }

    private long copyFile(File source, OutputStream output) throws IOException {
        long total = 0L;
        try (FileInputStream input = new FileInputStream(source);
             OutputStream target = output) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                if (length == 0) {
                    continue;
                }
                target.write(buffer, 0, length);
                total += length;
            }
            target.flush();
        }
        return total;
    }

    private int dpInt(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void drawTabs(Canvas canvas, float x, float y, float w, float h) {
        fill(canvas, x, y, x + w, y + h, Color.WHITE);
        float tabW = Math.min(dp(170), (w - dp(80)) / tabs.length);
        float tx = x + dp(220);
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            RectF r = rect(tx, y + dp(8), tx + tabW, y + h);
            int fill = activeTab == i ? page : Color.WHITE;
            rounded(canvas, r, fill, Color.TRANSPARENT, dp(4));
            drawTextCenter(canvas, tabs[i], r, 16, activeTab == i ? activeBlue : text, activeTab == i);
            clickZones.add(new ClickZone(new RectF(r), new ClickAction() {
                @Override
                public void run() {
                    selectMainTab(index);
                }
            }));
            tx += tabW;
        }
    }

    private void drawStatusPage(Canvas canvas, float x, float y, float w, float h) {
        fill(canvas, x, y, x + w, y + h, Color.WHITE);
        RectF shell = rect(x + dp(28), y, x + w - dp(18), y + h - dp(18));
        rounded(canvas, shell, page, Color.TRANSPARENT, dp(8));
        float gap = dp(22);
        float left = shell.left + dp(24);
        float top = shell.top + dp(24);
        float gridW = shell.width() * 0.50f;
        float cardW = (gridW - gap * 2) / 3f;
        float cardH = Math.min(dp(142), (shell.height() - dp(230)) / 3f);
        // 监护等级来自主机回报的监护等级灯：主机改等级 App 跟着变，
        // 蓝牙断开后没有回包，显示 "--"（而不是写死一个等级）。
        String monitorLevel = bleManager.getMonitorLevelLabel();
        if (TextUtils.isEmpty(monitorLevel)) {
            monitorLevel = "--";
        }
        String[][] data = {
                {"舱内温度  ℃", bleManager.getCabinTempText(), "♨", "#444444"},
                {"氧浓度  %", bleManager.getOxygenText(), "O₂", "#444444"},
                {"湿度  %", bleManager.getHumidityText(), "♢", "#444444"},
                {"红外体温  ℃", bleManager.getInfraredTempText(), "♨", "#444444"},
                {"监护等级", monitorLevel, "▣", "#F0545C"},
                {"二氧化碳浓度  PPM", bleManager.getCo2Text(), "CO₂", "#444444"},
                {"治疗时长", bleManager.getTreatmentTimeText(), "◷", "#444444"},
                {"红外理疗", bleManager.getControlDurationText(6), "∿", "#444444"},
                {"蓝光理疗", bleManager.getControlDurationText(7), "☂", "#444444"}
        };
        for (int i = 0; i < data.length; i++) {
            int col = i % 3;
            int row = i / 3;
            RectF r = rect(left + col * (cardW + gap), top + row * (cardH + gap), left + col * (cardW + gap) + cardW, top + row * (cardH + gap) + cardH);
            rounded(canvas, r, panel, Color.TRANSPARENT, dp(8));
            drawText(canvas, data[i][0], r.left + dp(20), r.top + dp(28), 15, text, true);
            if (i == 4) {
                RectF level = rect(r.left + dp(22), r.top + dp(56), r.left + dp(112), r.top + dp(88));
                // 没有等级（设备断开）时徽标用灰色，别让一个写死的红色等级看着像报警。
                rounded(canvas, level, "--".equals(monitorLevel) ? Color.rgb(198, 205, 214) : red,
                        Color.TRANSPARENT, dp(16));
                drawTextCenter(canvas, monitorLevel, level, 15, Color.WHITE, true);
                drawStatusLightDots(canvas, r);
            } else {
                drawText(canvas, data[i][1], r.left + dp(20), r.top + dp(86), 34, Color.parseColor(data[i][3]), false);
                drawProgressBar(canvas, r.left + dp(20), r.bottom - dp(28), r.width() - dp(82), dp(10), i == 7 ? 0.05f : i == 8 ? 0.14f : 0.58f, i == 8 ? red : green);
            }
            drawTextRight(canvas, data[i][2], r.right - dp(18), r.top + dp(88), 27, Color.rgb(55, 148, 242), true);
        }

        RectF cam = rect(left + gridW + gap * 2, top, shell.right - dp(22), top + cardH * 3 + gap * 2);
        drawCameraPreview(canvas, cam, true);
        RectF statusPreview = inset(cam, dp(2), dp(2));
        statusPreview.bottom -= dp(24);
        updateCameraPreviewOverlay(statusPreview, true);
        drawCameraControlBar(canvas, cam, true);
        clickZones.add(new ClickZone(new RectF(cam), new ClickAction() {
            @Override
            public void run() {
                openTpLinkCameraPreview();
            }
        }));

        float miniTop = top + cardH * 3 + gap * 3;
        String[] names = {"雾化器", "暖光灯", "内循环"};
        for (int i = 0; i < names.length; i++) {
            RectF mini = rect(left + i * dp(168), miniTop, left + i * dp(168) + dp(132), miniTop + dp(110));
            rounded(canvas, mini, Color.WHITE, Color.TRANSPARENT, dp(8));
            drawTextCenter(canvas, names[i], rect(mini.left, mini.top + dp(18), mini.right, mini.top + dp(42)), 16, text, true);
            drawText(canvas, "开", mini.left + dp(34), mini.top + dp(72), 13, text, false);
            drawToggle(canvas, mini.left + dp(58), mini.top + dp(58), bleManager.isControlOn(10 + i));
        }
        RectF thermal = rect(left + dp(504), miniTop, left + dp(760), miniTop + dp(110));
        drawLightPreview(canvas, thermal);

        RectF summary = rect(left, shell.bottom - dp(130), shell.right - dp(22), shell.bottom - dp(28));
        rounded(canvas, summary, panel, Color.TRANSPARENT, dp(8));
        drawText(canvas, "医嘱", summary.left + dp(20), summary.top + dp(34), 15, text, true);
        drawText(canvas, bleManager.getOtherTreatmentSummary(), summary.left + dp(20), summary.top + dp(66), 13, text, false);
    }

    private void drawControlPage(Canvas canvas, float x, float y, float w, float h) {
        fill(canvas, x, y, x + w, y + h, Color.WHITE);
        RectF shell = rect(x + dp(28), y, x + w - dp(18), y + h - dp(18));
        rounded(canvas, shell, page, Color.TRANSPARENT, dp(8));
        String[][] items = {
                {"舱内温度", bleManager.getControlValue(0), "设置"}, {"氧浓度", bleManager.getControlValue(1), "设置"}, {"监护等级灯", bleManager.getControlValue(2), ""}, {"CO2浓度", bleManager.getControlValue(3), "读取"},
                {"冷光照明", bleManager.getControlValue(4), ""}, {"暖光照明", bleManager.getControlValue(5), ""}, {"红外理疗", bleManager.getControlDurationText(6), "定时"}, {"蓝光理疗", bleManager.getControlDurationText(7), "定时"},
                {"外循环", bleManager.getControlValue(8), ""}, {"内循环", bleManager.getControlValue(9), ""}, {"雾化器", bleManager.getControlDurationText(10), "定时"}, {"负离子", bleManager.getControlDurationText(11), "定时"},
                {"紫外消毒", bleManager.getControlDurationText(12), "定时"}, {"雾化器", bleManager.getControlDurationText(10), ""}, {"负离子", bleManager.getControlDurationText(11), ""}, {"冷光照明", bleManager.getControlValue(4), ""},
                {"暖光照明", bleManager.getControlValue(5), ""}, {"外循环", bleManager.getControlValue(8), ""}, {"内循环", bleManager.getControlValue(9), ""}, {"治疗时长", bleManager.getControlValue(15), ""}
        };
        float gap = dp(24);
        float cardW = (shell.width() - gap * 6) / 5f;
        float cardH = Math.min(dp(178), (shell.height() - gap * 5) / 4f);
        // 每张卡片对应的控制项要写死，不能拿卡片下标当命令序号：
        // 第三、四行里有重复卡片（雾化器、负离子、冷光照明……），
        // 用下标当序号会让"雾化器"卡片去下发氧开关、"负离子"卡片去读湿度，
        // 用户点下去看着像没反应。
        final int[] commandIndexes = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 10, 11, 4, 5, 8, 9, 15};
        for (int i = 0; i < items.length; i++) {
            final int commandIndex = i < commandIndexes.length ? commandIndexes[i] : i;
            final String title = items[i][0];
            int col = i % 5;
            int row = i / 5;
            RectF r = rect(shell.left + gap + col * (cardW + gap), shell.top + gap + row * (cardH + gap), shell.left + gap + col * (cardW + gap) + cardW, shell.top + gap + row * (cardH + gap) + cardH);
            ClickAction cardAction = new ClickAction() {
                @Override
                public void run() {
                    handleControlCardTap(commandIndex, title);
                }
            };
            ClickAction buttonAction = new ClickAction() {
                @Override
                public void run() {
                    handleControlButtonTap(commandIndex, title);
                }
            };
            rounded(canvas, r, panel, Color.TRANSPARENT, dp(8));
            drawText(canvas, items[i][0], r.left + dp(22), r.top + dp(34), 17, text, true);
            if (commandIndex == 2) {
                drawStatusLightDots(canvas, r);
            } else {
                int valueColor = commandIndex == 0 ? red : text;
                drawText(canvas, items[i][1], r.left + dp(22), r.top + dp(92), 34, valueColor, false);
            }
            drawControlIcon(canvas, r, commandIndex);
            if (commandIndex == 15) {
                // 治疗时长只读：不画设置按钮或开关，也不注册卡片点击。
            } else if (!TextUtils.isEmpty(items[i][2])) {
                clickZones.add(new ClickZone(new RectF(r), cardAction));
                drawText(canvas, items[i][2], r.left + dp(22), r.bottom - dp(24), 15, activeBlue, false);
                clickZones.add(new ClickZone(rect(r.left, r.bottom - dp(48), r.left + dp(82), r.bottom), buttonAction));
            } else {
                drawToggle(canvas, r.left + dp(22), r.bottom - dp(38), bleManager.isControlOn(commandIndex));
                clickZones.add(new ClickZone(new RectF(r), cardAction));
            }
        }
    }

    private void handleControlCardTap(final int index, String title) {
        if (index == 0 || index == 1) {
            handleControlButtonTap(index, title);
            return;
        }
        sendBleCommand(title, new BleCommand() {
            @Override
            public boolean send() {
                return bleManager.sendControlPreset(index);
            }
        });
        invalidate();
    }

    private void handleControlButtonTap(final int index, String title) {
        if (index == 0) {
            showNumberDialog(index, "设置舱内温度", "℃", bleManager.getCabinTempValue(), false);
            return;
        }
        if (index == 1) {
            showNumberDialog(index, "设置氧浓度", "%", bleManager.getOxygenValue(), false);
            return;
        }
        if (index == 3) {
            showNumberDialog(index, "设置CO2目标值", "PPM", bleManager.getCo2Value(), true);
            return;
        }
        if (index == 15) {
            showNumberDialog(index, "设置治疗时长", "分钟", bleManager.getTreatmentMinutesValue(), true);
            return;
        }
        if (index == 6 || index == 7 || index == 10 || index == 11 || index == 12) {
            showNumberDialog(index, "设置" + title + "时间", "分钟", bleManager.getControlTimeValue(index), true);
            return;
        }
        sendBleCommand(title, new BleCommand() {
            @Override
            public boolean send() {
                return bleManager.sendControlPreset(index);
            }
        });
        invalidate();
    }

    private void showNumberDialog(final int index, String title, String suffix, Number currentValue, final boolean integerOnly) {
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setText(currentValue == null ? "" : (integerOnly ? String.valueOf(currentValue.intValue()) : trimNumber(currentValue.floatValue())));
        input.setHint(suffix);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | (integerOnly ? 0 : InputType.TYPE_NUMBER_FLAG_DECIMAL));

        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String raw = input.getText().toString().trim();
                        if (TextUtils.isEmpty(raw)) {
                            Toast.makeText(activity, "请输入数值", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        try {
                            float value = Float.parseFloat(raw);
                            String error = validateControlInput(index, value);
                            if (!TextUtils.isEmpty(error)) {
                                Toast.makeText(activity, error, Toast.LENGTH_SHORT).show();
                                return;
                            }
                            sendBleCommand("设置", new BleCommand() {
                                @Override
                                public boolean send() {
                                    if (index == 0) {
                                        return bleManager.setTemperature(value);
                                    }
                                    if (index == 1) {
                                        return bleManager.setOxygen(value);
                                    }
                                    if (index == 3) {
                                        return bleManager.setCo2(Math.round(value));
                                    }
                                    return bleManager.setControlTime(index, Math.round(value));
                                }
                            });
                            invalidate();
                        } catch (NumberFormatException exception) {
                            Toast.makeText(activity, "数值格式不正确", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private String validateControlInput(int index, float value) {
        if (index == 0 && (value < 15f || value > 45f)) {
            return "舱内温度建议设置在15-45℃";
        }
        if (index == 1 && (value < 18f || value > 95f)) {
            return "氧浓度建议设置在18-95%";
        }
        if (index == 3 && (value < 300f || value > 5000f)) {
            return "CO2目标值建议设置在300-5000PPM";
        }
        if ((index == 6 || index == 7 || index == 10 || index == 11 || index == 12 || index == 15)
                && (value < 0f || value > 1440f)) {
            return "时间建议设置在0-1440分钟";
        }
        return "";
    }

    /** 一条需要通过蓝牙下发的指令，统一走 sendBleCommand 处理权限和结果提示。 */
    private interface BleCommand {
        boolean send();
    }

    /**
     * 统一的蓝牙下发入口。
     * Android 12 以上蓝牙权限是运行时权限，没授权时指令根本发不出去，
     * 这里先申请授权，授权通过后自动把这条指令补发回去，用户不用再点一次。
     */
    private boolean sendBleCommand(final String title, final BleCommand command) {
        if (!bleManager.canSendCommand()) {
            Toast.makeText(activity, "正在申请蓝牙权限，授权后自动下发", Toast.LENGTH_SHORT).show();
            activity.requestBlePermissionsWithRetry(new Runnable() {
                @Override
                public void run() {
                    sendBleCommand(title, command);
                }
            });
            return false;
        }
        boolean sent = command.send();
        showSendResult(title, sent);
        return sent;
    }

    private void showSendResult(String title, boolean sent) {
        String detail = TextUtils.isEmpty(bleManager.getLastError()) ? "暂时无法发送，请检查蓝牙连接" : bleManager.getLastError();
        String message = sent ? "已发送: " + title + "，等待设备回读" : detail;
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    private void playMonitorAlarmSound() {
        try {
            final ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 850);
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        tone.release();
                    } catch (RuntimeException ignored) {
                    }
                }
            }, 1000L);
        } catch (RuntimeException exception) {
            Toast.makeText(activity, "报警音播放失败，请检查系统音量", Toast.LENGTH_SHORT).show();
        }
    }

    private String trimNumber(float value) {
        if (Math.abs(value - Math.round(value)) < 0.05f) {
            return String.valueOf(Math.round(value));
        }
        return String.format(java.util.Locale.US, "%.1f", value);
    }

    private void drawMonitorPage(Canvas canvas, float x, float y, float w, float h) {
        fill(canvas, x, y, x + w, y + h, page);
        RectF monitor = rect(x + dp(10), y + dp(10), x + w - dp(10), y + h - dp(10));
        rounded(canvas, monitor, Color.BLACK, border, dp(1));
        drawText(canvas, "AM4100 实时监护", monitor.left + dp(20), monitor.top + dp(24), 12, Color.WHITE, true);
        drawText(canvas, "连接: " + am4100Manager.getStateText() + "    设备: " + dash(am4100Manager.getConnectedDeviceName()),
                monitor.left + dp(20), monitor.top + dp(45), 10, Color.rgb(190, 205, 215), false);

        float buttonY = monitor.top + dp(14);
        float buttonX = monitor.left + dp(188);
        drawSmallButton(canvas, "权限", buttonX, buttonY, dp(54), dp(24), false, new ClickAction() {
            @Override
            public void run() {
                activity.requestBlePermissionsFromUi();
            }
        });
        drawSmallButton(canvas, "蓝牙", buttonX + dp(62), buttonY, dp(54), dp(24), false, new ClickAction() {
            @Override
            public void run() {
                am4100Manager.requestEnableBluetooth(activity);
            }
        });
        drawSmallButton(canvas, am4100Manager.isScanning() ? "停止扫描" : "扫描蓝牙", buttonX + dp(124), buttonY, dp(92), dp(24), am4100Manager.isScanning(), new ClickAction() {
            @Override
            public void run() {
                if (am4100Manager.isScanning()) {
                    am4100Manager.stopScan();
                } else if (activity.hasScanPermission()) {
                    am4100Manager.startScan();
                } else {
                    // 直接走申请流程：没授权就弹系统授权框，被“不再询问”拦住了
                    // 就跳应用权限设置页，别只弹个 Toast 让人干瞪眼。
                    activity.requestBlePermissionsFromUi();
                }
            }
        });
        drawSmallButton(canvas, "断开", buttonX + dp(224), buttonY, dp(54), dp(24), false, new ClickAction() {
            @Override
            public void run() {
                am4100Manager.disconnect();
            }
        });
        drawSmallButton(canvas, "体温模式", buttonX + dp(286), buttonY, dp(76), dp(24), false, new ClickAction() {
            @Override
            public void run() {
                am4100Manager.setTemperatureMode();
            }
        });

        drawMonitorModeTabs(canvas, monitor);
        if (monitorMode == MONITOR_MODE_HISTORY) {
            am4100DeviceListBounds.setEmpty();
            drawMonitorHistoryPage(canvas, monitor);
            scheduleMonitorRefresh();
            return;
        }
        if (monitorMode == MONITOR_MODE_SETTINGS) {
            am4100DeviceListBounds.setEmpty();
            drawMonitorSettingsPage(canvas, monitor);
            scheduleMonitorRefresh();
            return;
        }

        float graphLeft = monitor.left + dp(20);
        float graphRight = monitor.right - dp(205);
        if (selectedMonitorTrend == MONITOR_TREND_ALL) {
            drawRealtimeWave(canvas, graphLeft, monitor.top + dp(78), graphRight, dp(30),
                    Color.rgb(57, 135, 64), am4100Manager.getEcgWaveSamples(), am4100Manager.getHeartRateHistory());
            drawRealtimeWave(canvas, graphLeft, monitor.top + dp(150), graphRight, dp(42),
                    Color.rgb(142, 48, 42), am4100Manager.getSpo2WaveSamples(), am4100Manager.getSpo2History());
            drawRealtimeWave(canvas, graphLeft, monitor.top + dp(222), graphRight, dp(36),
                    Color.rgb(74, 124, 170), am4100Manager.getRespWaveSamples(), am4100Manager.getRespHistory());
            drawText(canvas, "ECG", graphLeft, monitor.top + dp(68), 10, Color.rgb(87, 204, 88), true);
            drawText(canvas, "PLETH", graphLeft, monitor.top + dp(135), 10, Color.rgb(224, 68, 64), true);
            drawText(canvas, "RESP", graphLeft, monitor.top + dp(205), 10, Color.rgb(94, 158, 222), true);
            drawText(canvas, am4100Manager.hasVitals() ? "LIVE" : "WAITING", graphLeft + dp(170), monitor.top + dp(174), 28,
                    Color.argb(125, 55, 130, 210), false);
        } else {
            drawSelectedMonitorTrend(canvas, selectedMonitorTrend, graphLeft, monitor.top + dp(62), graphRight, monitor.top + dp(260));
        }

        float mx = monitor.right - dp(178);
        drawMonitorMetric(canvas, "心率", am4100Manager.getHeartRateText(), "bpm", mx, monitor.top + dp(70), green, MONITOR_TREND_HEART);
        drawMonitorMetric(canvas, "血压", am4100Manager.getBloodPressureText(), "mmHg", mx, monitor.top + dp(125), orange, MONITOR_TREND_BP);
        drawMonitorMetric(canvas, "血氧", am4100Manager.getSpo2Text(), "%", mx, monitor.top + dp(180), red, MONITOR_TREND_SPO2);
        drawMonitorMetric(canvas, "脉率", am4100Manager.getPulseRateText(), "bpm", mx, monitor.top + dp(235), green, MONITOR_TREND_PULSE);
        drawMonitorMetric(canvas, "体温", am4100Manager.getBodyTempText(), "℃", mx, monitor.top + dp(290), Color.rgb(60, 190, 210), MONITOR_TREND_TEMP);
        drawMonitorMetric(canvas, "呼吸率", am4100Manager.getRespText(), "brpm", mx, monitor.top + dp(345), Color.rgb(80, 170, 235), MONITOR_TREND_RESP);

        RectF devicePanel = rect(graphLeft, Math.max(monitor.top + dp(275), monitor.bottom - dp(116)),
                graphRight, monitor.bottom - dp(14));
        if (devicePanel.height() > dp(54)) {
            rounded(canvas, devicePanel, Color.rgb(14, 22, 32), Color.rgb(65, 84, 105), dp(2));
            drawText(canvas, "AM4100设备", devicePanel.left + dp(10), devicePanel.top + dp(18), 11, Color.WHITE, true);
            drawText(canvas, "最近帧: " + dash(am4100Manager.getLastFrameSummary()),
                    devicePanel.left + dp(92), devicePanel.top + dp(18), 10, Color.rgb(190, 205, 215), false);
            String error = am4100Manager.getLastError();
            if (!TextUtils.isEmpty(error)) {
                drawText(canvas, error, devicePanel.left + dp(10), devicePanel.top + dp(38), 10, Color.rgb(255, 155, 130), false);
            } else {
                drawText(canvas, "校准: " + dash(am4100Manager.getCalibrationStatusText())
                                + "  环境温: " + am4100Manager.getAmbientTempText()
                                + "  物体温: " + am4100Manager.getObjectTempText(),
                        devicePanel.left + dp(10), devicePanel.top + dp(38), 10, Color.rgb(170, 190, 205), false);
            }

            RectF listClip = rect(devicePanel.left + dp(10), devicePanel.top + dp(48),
                    devicePanel.right - dp(10), devicePanel.bottom - dp(8));
            am4100DeviceListBounds.set(listClip);
            canvas.save();
            canvas.clipRect(listClip);
            List<Am4100Manager.DeviceItem> devices = am4100Manager.getDevices();
            if (devices.isEmpty()) {
                am4100DeviceScrollY = 0;
                am4100DeviceMaxScrollY = 0;
                // 扫不到设备时把最可能的原因直接显示出来：
                // 权限没给、蓝牙没开、定位被关，系统都会把扫描结果整批过滤掉。
                String monitorDiagnosis = am4100Manager.getScanDiagnosis();
                drawText(canvas, TextUtils.isEmpty(monitorDiagnosis)
                                ? "扫描后会显示附近蓝牙设备，可上下滑动查找"
                                : monitorDiagnosis,
                        listClip.left, listClip.top + dp(18), 10, Color.rgb(135, 156, 176), false);
            } else {
                float rowHeight = dp(34);
                float contentHeight = devices.size() * rowHeight;
                am4100DeviceMaxScrollY = Math.max(0, contentHeight - listClip.height());
                am4100DeviceScrollY = clamp(am4100DeviceScrollY, 0, am4100DeviceMaxScrollY);
                float rowY = listClip.top - am4100DeviceScrollY;
                for (int i = 0; i < devices.size(); i++) {
                    final Am4100Manager.DeviceItem item = devices.get(i);
                    RectF row = rect(listClip.left, rowY, listClip.right, rowY + dp(28));
                    if (row.bottom >= listClip.top && row.top <= listClip.bottom) {
                        rounded(canvas, row, Color.rgb(26, 38, 52), Color.rgb(75, 92, 110), dp(2));
                        drawText(canvas, item.deviceName + "  RSSI " + item.rssi, row.left + dp(8), row.top + dp(17), 10, Color.WHITE, true);
                        drawTextRight(canvas, "连接", row.right - dp(10), row.top + dp(18), 10, green, true);
                        RectF clickRow = new RectF(row);
                        clickRow.intersect(listClip);
                        clickZones.add(new ClickZone(clickRow, new ClickAction() {
                            @Override
                            public void run() {
                                am4100Manager.connect(item.deviceId);
                            }
                        }));
                    }
                    rowY += rowHeight;
                }
                if (am4100DeviceMaxScrollY > 0) {
                    float thumbHeight = Math.max(dp(14), listClip.height() * listClip.height() / contentHeight);
                    float thumbTop = listClip.top + (listClip.height() - thumbHeight) * (am4100DeviceScrollY / am4100DeviceMaxScrollY);
                    rounded(canvas, rect(listClip.right - dp(5), thumbTop, listClip.right - dp(1), thumbTop + thumbHeight),
                            Color.rgb(90, 116, 140), Color.TRANSPARENT, dp(2));
                }
            }
            canvas.restore();
        }
        scheduleMonitorRefresh();
    }

    private void drawMonitorModeTabs(Canvas canvas, RectF monitor) {
        float y = monitor.top + dp(14);
        float x = monitor.right - dp(246);
        drawMonitorModeButton(canvas, "实时", x, y, dp(54), MONITOR_MODE_LIVE);
        drawMonitorModeButton(canvas, "历史数据", x + dp(62), y, dp(82), MONITOR_MODE_HISTORY);
        drawMonitorModeButton(canvas, "设置", x + dp(152), y, dp(54), MONITOR_MODE_SETTINGS);
    }

    private void drawMonitorModeButton(Canvas canvas, String label, float x, float y, float w, final int mode) {
        final RectF r = rect(x, y, x + w, y + dp(24));
        boolean active = monitorMode == mode;
        rounded(canvas, r, active ? Color.rgb(28, 92, 68) : Color.rgb(12, 18, 26),
                active ? green : Color.rgb(68, 82, 98), dp(12));
        drawTextCenter(canvas, label, r, 10, active ? Color.WHITE : Color.rgb(184, 202, 216), true);
        clickZones.add(new ClickZone(new RectF(r), new ClickAction() {
            @Override
            public void run() {
                monitorMode = mode;
            }
        }));
    }

    private void drawMonitorHistoryPage(Canvas canvas, RectF monitor) {
        RectF card = rect(monitor.left + dp(20), monitor.top + dp(58), monitor.right - dp(20), monitor.bottom - dp(16));
        rounded(canvas, card, Color.rgb(7, 12, 18), Color.rgb(58, 76, 94), dp(3));
        drawText(canvas, "历史数据", card.left + dp(16), card.top + dp(24), 13, Color.WHITE, true);
        drawText(canvas, "记录本次连接期间 AM4100 回传的心率、血氧、脉率、血压、体温、呼吸率趋势",
                card.left + dp(100), card.top + dp(24), 10, Color.rgb(166, 186, 204), false);
        drawTextRight(canvas, "最近帧: " + dash(am4100Manager.getLastFrameSummary()), card.right - dp(16), card.top + dp(24),
                9, Color.rgb(130, 154, 176), false);

        float top = card.top + dp(42);
        float rowH = Math.max(dp(54), (card.bottom - top - dp(12)) / 6f);
        drawHistoryTrendRow(canvas, MONITOR_TREND_HEART, card.left + dp(12), top, card.right - dp(12), rowH - dp(7));
        drawHistoryTrendRow(canvas, MONITOR_TREND_SPO2, card.left + dp(12), top + rowH, card.right - dp(12), rowH - dp(7));
        drawHistoryTrendRow(canvas, MONITOR_TREND_PULSE, card.left + dp(12), top + rowH * 2, card.right - dp(12), rowH - dp(7));
        drawHistoryTrendRow(canvas, MONITOR_TREND_BP, card.left + dp(12), top + rowH * 3, card.right - dp(12), rowH - dp(7));
        drawHistoryTrendRow(canvas, MONITOR_TREND_TEMP, card.left + dp(12), top + rowH * 4, card.right - dp(12), rowH - dp(7));
        drawHistoryTrendRow(canvas, MONITOR_TREND_RESP, card.left + dp(12), top + rowH * 5, card.right - dp(12), rowH - dp(7));
    }

    private void drawHistoryTrendRow(Canvas canvas, final int trend, float left, float top, float right, float height) {
        RectF r = rect(left, top, right, top + height);
        int color = monitorTrendColor(trend);
        boolean alarm = isMonitorTrendAlarm(trend);
        rounded(canvas, r, alarm ? Color.rgb(36, 10, 10) : Color.rgb(13, 22, 32),
                alarm ? red : Color.rgb(45, 63, 80), dp(2));
        drawText(canvas, monitorTrendName(trend), r.left + dp(12), r.top + dp(21), 11, color, true);
        drawText(canvas, dash(monitorTrendValue(trend)) + " " + monitorTrendUnit(trend),
                r.left + dp(12), r.top + dp(42), 15, alarm ? red : color, true);
        drawText(canvas, monitorLimitText(trend), r.left + dp(122), r.top + dp(21), 9, Color.rgb(150, 170, 188), false);
        drawText(canvas, "点此查看单项电波图", r.left + dp(122), r.top + dp(42), 9, Color.rgb(104, 128, 150), false);

        List<Integer> trendSamples = monitorTrendSamples(trend);
        List<Integer> waveSamples = monitorTrendHasWaveform(trend) ? monitorTrendWaveSamples(trend) : null;
        float graphLeft = r.left + dp(280);
        float graphRight = r.right - dp(92);
        float baseline = r.centerY() + dp(5);
        float amp = Math.max(dp(14), r.height() * 0.28f);
        for (int i = 0; i < 4; i++) {
            float gy = baseline - amp + i * (amp * 2f / 3f);
            fill(canvas, graphLeft, gy, graphRight, gy + dp(0.5f), Color.rgb(28, 43, 58));
        }
        drawReportWave(canvas, graphLeft, baseline, graphRight, amp, color, waveSamples, trendSamples, dp(1.4f));
        drawTextRight(canvas, waveSourceText(waveSamples, trendSamples), r.right - dp(14), r.top + dp(24), 9, Color.rgb(135, 156, 176), false);
        drawTextRight(canvas, alarm ? "报警" : "正常", r.right - dp(14), r.top + dp(45), 10, alarm ? red : green, true);

        clickZones.add(new ClickZone(new RectF(r), new ClickAction() {
            @Override
            public void run() {
                selectedMonitorTrend = trend;
                monitorMode = MONITOR_MODE_LIVE;
            }
        }));
    }

    private void drawMonitorSettingsPage(Canvas canvas, RectF monitor) {
        RectF card = rect(monitor.left + dp(20), monitor.top + dp(58), monitor.right - dp(20), monitor.bottom - dp(16));
        rounded(canvas, card, Color.rgb(18, 18, 18), Color.rgb(85, 85, 85), dp(4));
        drawText(canvas, "设置", card.left + dp(16), card.top + dp(26), 14, Color.WHITE, true);
        drawText(canvas, "上下限会用于实时指标报警高亮，点击每一行可修改数值", card.left + dp(76), card.top + dp(26),
                10, Color.rgb(175, 175, 175), false);

        float y = card.top + dp(52);
        drawMonitorTopSwitch(canvas, "脉搏音", card.left + dp(250), y - dp(22), monitorPulseBeep, new ClickAction() {
            @Override
            public void run() {
                monitorPulseBeep = !monitorPulseBeep;
                saveUiSettings();
            }
        });
        drawMonitorTopSwitch(canvas, "报警音", card.left + dp(470), y - dp(22), monitorAlarmSound, new ClickAction() {
            @Override
            public void run() {
                monitorAlarmSound = !monitorAlarmSound;
                if (monitorAlarmSound) {
                    playMonitorAlarmSound();
                }
                saveUiSettings();
            }
        });
        fill(canvas, card.left + dp(16), y, card.right - dp(16), y + dp(0.8f), Color.rgb(70, 70, 70));
        y += dp(20);

        y = drawMonitorLimitSection(canvas, card.left + dp(18), y, card.right - dp(18), "心率", green,
                new int[]{LIMIT_HEART}, new String[]{"上下限"}, false);
        y = drawMonitorLimitSection(canvas, card.left + dp(18), y, card.right - dp(18), "血压", orange,
                new int[]{LIMIT_BP_SYS, LIMIT_BP_DIA}, new String[]{"收缩压", "舒张压"}, true);
        y = drawMonitorLimitSection(canvas, card.left + dp(18), y, card.right - dp(18), "脉率", Color.rgb(22, 165, 145),
                new int[]{LIMIT_PULSE}, new String[]{"上下限"}, false);
        y = drawMonitorLimitSection(canvas, card.left + dp(18), y, card.right - dp(18), "血氧", red,
                new int[]{LIMIT_SPO2}, new String[]{"上下限"}, false);
        y = drawMonitorLimitSection(canvas, card.left + dp(18), y, card.right - dp(18), "体温", Color.rgb(42, 190, 210),
                new int[]{LIMIT_TEMP}, new String[]{"上下限"}, false);
        drawMonitorLimitSection(canvas, card.left + dp(18), y, card.right - dp(18), "呼吸率", Color.rgb(80, 170, 235),
                new int[]{LIMIT_RESP}, new String[]{"上下限"}, false);
    }

    private void drawMonitorTopSwitch(Canvas canvas, String label, float x, float y, boolean on, final ClickAction action) {
        drawText(canvas, label, x, y + dp(20), 12, Color.WHITE, true);
        RectF sw = rect(x + dp(78), y + dp(2), x + dp(124), y + dp(25));
        drawMonitorSwitch(canvas, sw, on, green);
        clickZones.add(new ClickZone(new RectF(sw), action));
    }

    private float drawMonitorLimitSection(Canvas canvas, float left, float y, float right, String title, int color,
                                          int[] limitIndices, String[] rowLabels, boolean includeAutoRow) {
        drawText(canvas, "[ " + title + " ]", left, y + dp(18), 12, color, true);
        y += dp(30);
        for (int i = 0; i < limitIndices.length; i++) {
            drawMonitorLimitRow(canvas, left, y, right, rowLabels[i], limitIndices[i], color);
            y += dp(34);
        }
        if (includeAutoRow) {
            RectF row = rect(left, y, right, y + dp(30));
            drawText(canvas, "自动测量", row.left, row.top + dp(21), 11, color, true);
            drawMonitorOpenClose(canvas, rect(row.right - dp(90), row.top + dp(4), row.right - dp(34), row.bottom - dp(4)),
                    monitorAutoNibp, color);
            clickZones.add(new ClickZone(new RectF(row.right - dp(94), row.top, row.right, row.bottom), new ClickAction() {
                @Override
                public void run() {
                    monitorAutoNibp = !monitorAutoNibp;
                }
            }));
            y += dp(34);
        }
        fill(canvas, left, y + dp(2), right, y + dp(2.8f), Color.rgb(62, 62, 62));
        return y + dp(18);
    }

    private void drawMonitorLimitRow(Canvas canvas, float left, float y, float right, String label, final int limitIndex, int color) {
        final RectF row = rect(left, y, right, y + dp(32));
        boolean enabled = monitorLimitEnabled[limitIndex];
        int rowColor = enabled ? color : Color.rgb(125, 125, 125);
        drawText(canvas, label, row.left, row.top + dp(21), 11, rowColor, true);
        drawText(canvas, "上限: " + formatLimitValue(limitIndex, monitorLimitHigh[limitIndex]),
                row.left + dp(250), row.top + dp(21), 11, rowColor, true);
        drawText(canvas, "下限: " + formatLimitValue(limitIndex, monitorLimitLow[limitIndex]),
                row.left + dp(365), row.top + dp(21), 11, rowColor, true);
        drawMonitorOpenClose(canvas, rect(row.right - dp(90), row.top + dp(4), row.right - dp(34), row.bottom - dp(4)),
                enabled, rowColor);
        drawTextRight(canvas, ">", row.right - dp(6), row.top + dp(23), 20, Color.rgb(150, 150, 150), false);

        clickZones.add(new ClickZone(new RectF(row), new ClickAction() {
            @Override
            public void run() {
                showMonitorLimitDialog(limitIndex);
            }
        }));
        clickZones.add(new ClickZone(new RectF(row.right - dp(96), row.top, row.right - dp(28), row.bottom), new ClickAction() {
            @Override
            public void run() {
                monitorLimitEnabled[limitIndex] = !monitorLimitEnabled[limitIndex];
            }
        }));
    }

    private void drawMonitorOpenClose(Canvas canvas, RectF r, boolean on, int color) {
        rounded(canvas, r, Color.BLACK, Color.rgb(80, 80, 80), dp(8));
        drawTextCenter(canvas, on ? "开" : "关", r, 11, on ? color : Color.rgb(170, 170, 170), true);
    }

    private void drawMonitorSwitch(Canvas canvas, RectF r, boolean on, int color) {
        rounded(canvas, r, on ? Color.rgb(35, 205, 94) : Color.rgb(80, 80, 80), Color.TRANSPARENT, r.height() / 2f);
        drawCircle(canvas, on ? r.right - r.height() / 2f : r.left + r.height() / 2f, r.centerY(),
                r.height() * 0.42f, Color.WHITE);
    }

    private void drawMonitorMetric(Canvas canvas, String name, String value, String unit, float x, float y, int color, final int trend) {
        RectF hit = rect(x - dp(10), y - dp(25), x + dp(170), y + dp(21));
        boolean alarm = isMonitorTrendAlarm(trend);
        if (selectedMonitorTrend == trend || alarm) {
            rounded(canvas, hit, alarm ? Color.rgb(42, 8, 8) : Color.rgb(10, 25, 36), alarm ? red : color, dp(4));
            drawTextRight(canvas, alarm ? "报警" : "已筛选", hit.right - dp(8), hit.top + dp(13), 8, alarm ? red : color, false);
        }
        drawMetric(canvas, name, value, unit, x, y, color);
        clickZones.add(new ClickZone(new RectF(hit), new ClickAction() {
            @Override
            public void run() {
                selectedMonitorTrend = selectedMonitorTrend == trend ? MONITOR_TREND_ALL : trend;
            }
        }));
    }

    private void drawSelectedMonitorTrend(Canvas canvas, int trend, float left, float top, float right, float bottom) {
        RectF card = rect(left, top, right, bottom);
        int color = monitorTrendColor(trend);
        List<Integer> trendSamples = monitorTrendSamples(trend);
        List<Integer> waveSamples = monitorTrendHasWaveform(trend) ? monitorTrendWaveSamples(trend) : null;
        rounded(canvas, card, Color.rgb(3, 9, 15), Color.rgb(43, 65, 84), dp(2));

        drawText(canvas, monitorTrendWaveTitle(trend, waveSamples, trendSamples), card.left + dp(12), card.top + dp(22), 12, color, true);
        drawTextRight(canvas, "再次点击右侧指标恢复全部", card.right - dp(12), card.top + dp(22), 9, Color.rgb(148, 170, 190), false);

        float chartLeft = card.left + dp(18);
        float chartRight = card.right - dp(18);
        float chartTop = card.top + dp(40);
        float chartBottom = card.bottom - dp(42);
        float baseline = chartTop + (chartBottom - chartTop) * 0.55f;
        float amp = Math.max(dp(34), (chartBottom - chartTop) * 0.36f);
        for (int i = 0; i <= 4; i++) {
            float gy = chartTop + (chartBottom - chartTop) * i / 4f;
            fill(canvas, chartLeft, gy, chartRight, gy + dp(0.6f), Color.rgb(26, 43, 58));
        }
        for (int i = 0; i <= 6; i++) {
            float gx = chartLeft + (chartRight - chartLeft) * i / 6f;
            fill(canvas, gx, chartTop, gx + dp(0.6f), chartBottom, Color.rgb(19, 33, 47));
        }
        drawReportWave(canvas, chartLeft, baseline, chartRight, amp, color, waveSamples, trendSamples, dp(2));

        drawText(canvas, dash(monitorTrendValue(trend)) + " " + monitorTrendUnit(trend),
                card.left + dp(12), card.bottom - dp(16), 20, color, true);
        drawTextRight(canvas, waveSourceText(waveSamples, trendSamples), card.right - dp(12), card.bottom - dp(18),
                9, Color.rgb(150, 170, 188), false);
    }

    private String monitorTrendName(int trend) {
        if (trend == MONITOR_TREND_HEART) {
            return "心率";
        }
        if (trend == MONITOR_TREND_SPO2) {
            return "血氧";
        }
        if (trend == MONITOR_TREND_PULSE) {
            return "脉率";
        }
        if (trend == MONITOR_TREND_BP) {
            return "血压";
        }
        if (trend == MONITOR_TREND_TEMP) {
            return "体温";
        }
        return "呼吸率";
    }

    private String monitorTrendValue(int trend) {
        if (trend == MONITOR_TREND_HEART) {
            return am4100Manager.getHeartRateText();
        }
        if (trend == MONITOR_TREND_SPO2) {
            return am4100Manager.getSpo2Text();
        }
        if (trend == MONITOR_TREND_PULSE) {
            return am4100Manager.getPulseRateText();
        }
        if (trend == MONITOR_TREND_BP) {
            return am4100Manager.getBloodPressureText();
        }
        if (trend == MONITOR_TREND_TEMP) {
            return am4100Manager.getBodyTempText();
        }
        return am4100Manager.getRespText();
    }

    private String monitorTrendUnit(int trend) {
        if (trend == MONITOR_TREND_SPO2) {
            return "%";
        }
        if (trend == MONITOR_TREND_BP) {
            return "mmHg";
        }
        if (trend == MONITOR_TREND_TEMP) {
            return "℃";
        }
        if (trend == MONITOR_TREND_RESP) {
            return "brpm";
        }
        return "bpm";
    }

    private int monitorTrendColor(int trend) {
        if (trend == MONITOR_TREND_SPO2) {
            return red;
        }
        if (trend == MONITOR_TREND_BP) {
            return orange;
        }
        if (trend == MONITOR_TREND_TEMP) {
            return Color.rgb(60, 190, 210);
        }
        if (trend == MONITOR_TREND_RESP) {
            return Color.rgb(80, 170, 235);
        }
        return green;
    }

    private List<Integer> monitorTrendSamples(int trend) {
        if (trend == MONITOR_TREND_HEART) {
            return am4100Manager.getHeartRateHistory();
        }
        if (trend == MONITOR_TREND_SPO2) {
            return am4100Manager.getSpo2History();
        }
        if (trend == MONITOR_TREND_PULSE) {
            return am4100Manager.getPulseRateHistory();
        }
        if (trend == MONITOR_TREND_BP) {
            return am4100Manager.getBloodPressureHistory();
        }
        if (trend == MONITOR_TREND_TEMP) {
            return am4100Manager.getTemperatureHistory();
        }
        return am4100Manager.getRespHistory();
    }

    private List<Integer> monitorTrendWaveSamples(int trend) {
        if (trend == MONITOR_TREND_HEART) {
            return am4100Manager.getEcgWaveSamples();
        }
        if (trend == MONITOR_TREND_SPO2 || trend == MONITOR_TREND_PULSE) {
            return am4100Manager.getSpo2WaveSamples();
        }
        if (trend == MONITOR_TREND_RESP) {
            return am4100Manager.getRespWaveSamples();
        }
        return monitorTrendSamples(trend);
    }

    /** 血压/体温在监护仪协议里没有连续波形帧，其余通道才有。 */
    private boolean monitorTrendHasWaveform(int trend) {
        return trend != MONITOR_TREND_BP && trend != MONITOR_TREND_TEMP;
    }

    /** 说明图上的曲线来自哪里：真实波形、测量趋势，还是根本没有数据。 */
    private String waveSourceText(List<Integer> waveSamples, List<Integer> trendSamples) {
        if (waveHasSignal(waveSamples)) {
            return "波形 " + waveSamples.size() + " 点";
        }
        if (trendSamples != null && trendSamples.size() >= 2) {
            return "趋势 " + trendSamples.size() + " 点";
        }
        return "无信号";
    }

    /** 单项图表标题按真实数据来源命名，不再一律叫“电波图”。 */
    private String monitorTrendWaveTitle(int trend, List<Integer> waveSamples, List<Integer> trendSamples) {
        if (waveHasSignal(waveSamples)) {
            return monitorTrendName(trend) + "电波图";
        }
        if (trendSamples != null && trendSamples.size() >= 2) {
            return monitorTrendName(trend) + "趋势图";
        }
        return monitorTrendName(trend) + "（无信号）";
    }

    private void showMonitorLimitDialog(final int limitIndex) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(12);
        layout.setPadding(pad, pad, pad, pad);

        final EditText highInput = new EditText(activity);
        highInput.setSingleLine(true);
        highInput.setHint("上限 " + monitorLimitUnit(limitIndex));
        highInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        highInput.setText(formatLimitValue(limitIndex, monitorLimitHigh[limitIndex]));
        layout.addView(highInput);

        final EditText lowInput = new EditText(activity);
        lowInput.setSingleLine(true);
        lowInput.setHint("下限 " + monitorLimitUnit(limitIndex));
        lowInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        lowInput.setText(formatLimitValue(limitIndex, monitorLimitLow[limitIndex]));
        layout.addView(lowInput);

        new AlertDialog.Builder(activity)
                .setTitle(monitorLimitTitle(limitIndex))
                .setView(layout)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            float high = Float.parseFloat(highInput.getText().toString().trim());
                            float low = Float.parseFloat(lowInput.getText().toString().trim());
                            if (high <= low) {
                                Toast.makeText(activity, "上限必须大于下限", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            monitorLimitHigh[limitIndex] = high;
                            monitorLimitLow[limitIndex] = low;
                            monitorLimitEnabled[limitIndex] = true;
                            saveUiSettings();
                            activity.notifyLanhuStateChanged();
                            invalidate();
                        } catch (NumberFormatException exception) {
                            Toast.makeText(activity, "请输入正确的上下限数值", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String monitorLimitTitle(int index) {
        return "设置" + monitorLimitName(index) + "上下限";
    }

    private String monitorLimitName(int index) {
        if (index == LIMIT_HEART) {
            return "心率";
        }
        if (index == LIMIT_BP_SYS) {
            return "收缩压";
        }
        if (index == LIMIT_BP_DIA) {
            return "舒张压";
        }
        if (index == LIMIT_PULSE) {
            return "脉率";
        }
        if (index == LIMIT_SPO2) {
            return "血氧";
        }
        if (index == LIMIT_TEMP) {
            return "体温";
        }
        return "呼吸率";
    }

    private String monitorLimitUnit(int index) {
        if (index == LIMIT_SPO2) {
            return "%";
        }
        if (index == LIMIT_TEMP) {
            return "℃";
        }
        if (index == LIMIT_HEART || index == LIMIT_PULSE) {
            return "bpm";
        }
        if (index == LIMIT_RESP) {
            return "brpm";
        }
        return "mmHg";
    }

    private String formatLimitValue(int index, float value) {
        if (index == LIMIT_TEMP || Math.abs(value - Math.round(value)) > 0.05f) {
            return String.format(Locale.US, "%.1f", value);
        }
        return String.valueOf(Math.round(value));
    }

    private String monitorLimitText(int trend) {
        if (trend == MONITOR_TREND_BP) {
            return "收缩 " + formatLimitValue(LIMIT_BP_SYS, monitorLimitHigh[LIMIT_BP_SYS]) + "/"
                    + formatLimitValue(LIMIT_BP_SYS, monitorLimitLow[LIMIT_BP_SYS])
                    + "  舒张 " + formatLimitValue(LIMIT_BP_DIA, monitorLimitHigh[LIMIT_BP_DIA]) + "/"
                    + formatLimitValue(LIMIT_BP_DIA, monitorLimitLow[LIMIT_BP_DIA]) + " mmHg";
        }
        int index = monitorTrendLimitIndex(trend);
        return "上限 " + formatLimitValue(index, monitorLimitHigh[index])
                + "  下限 " + formatLimitValue(index, monitorLimitLow[index])
                + " " + monitorLimitUnit(index);
    }

    private int monitorTrendLimitIndex(int trend) {
        if (trend == MONITOR_TREND_HEART) {
            return LIMIT_HEART;
        }
        if (trend == MONITOR_TREND_SPO2) {
            return LIMIT_SPO2;
        }
        if (trend == MONITOR_TREND_PULSE) {
            return LIMIT_PULSE;
        }
        if (trend == MONITOR_TREND_TEMP) {
            return LIMIT_TEMP;
        }
        if (trend == MONITOR_TREND_RESP) {
            return LIMIT_RESP;
        }
        return LIMIT_BP_SYS;
    }

    private boolean isMonitorTrendAlarm(int trend) {
        if (!hasLiveMonitorValues()) {
            return false;
        }
        if (trend == MONITOR_TREND_BP) {
            String bp = am4100Manager.getBloodPressureText();
            int slash = bp == null ? -1 : bp.indexOf('/');
            if (slash <= 0) {
                return false;
            }
            float sys = parseFloatSafe(bp.substring(0, slash));
            float dia = parseFloatSafe(bp.substring(slash + 1));
            return isOutsideLimit(LIMIT_BP_SYS, sys) || isOutsideLimit(LIMIT_BP_DIA, dia);
        }
        return isOutsideLimit(monitorTrendLimitIndex(trend), parseFloatSafe(monitorTrendValue(trend)));
    }

    private boolean isOutsideLimit(int index, float value) {
        return isValidMonitorAlarmValue(index, value) && monitorLimitEnabled[index]
                && (value > monitorLimitHigh[index] || value < monitorLimitLow[index]);
    }

    /** 只有当前链路已完成协议初始化，缓存数值才允许触发实时超限表现。 */
    private boolean hasLiveMonitorValues() {
        return am4100Manager.isConnected() && am4100Manager.isProtocolReady();
    }

    /** 原生链路状态回调立即调用；连接正常时保持原 1 秒巡检，不改变真实报警时机。 */
    public void cancelMonitorAlarmsIfDisconnected() {
        if (hasLiveMonitorValues()) {
            return;
        }
        boolean hadAlarms = monitorAlarmSignature.length() > 0;
        clearMonitorAlarmState();
        if (hadAlarms) {
            activity.notifyLanhuStateChanged();
        }
    }

    /** 启动超限报警巡检：监护仪接上后数值越界要立刻在 App 上报警。 */
    public void startMonitorAlarmWatch() {
        if (monitorAlarmWatchRunning) {
            return;
        }
        monitorAlarmWatchRunning = true;
        monitorAlarmHandler.postDelayed(monitorAlarmRunnable, MONITOR_ALARM_INTERVAL_MS);
    }

    public void stopMonitorAlarmWatch() {
        monitorAlarmWatchRunning = false;
        monitorAlarmHandler.removeCallbacks(monitorAlarmRunnable);
        clearMonitorAlarmState();
    }

    private void clearMonitorAlarmState() {
        monitorAlarmTracker.clear();
        monitorAlarmSignature = "";
        for (int i = 0; i < monitorAlarmToasts.size(); i++) {
            monitorAlarmToasts.get(i).cancel();
        }
        monitorAlarmToasts.clear();
    }

    /**
     * 按当前上下限检查实时数值，收集正在超限的参数。
     * 只认监护仪真实上报的值：没数据不报警，免得设备没连上就满屏报警。
     */
    private ArrayList<MonitorAlarm> collectMonitorAlarms() {
        ArrayList<MonitorAlarm> alarms = new ArrayList<>();
        // 断连前的标量继续供页面显示，但断开期间绝不参与超限判断。
        // 重连后每项还要通过 isParamFresh：本次 ready 后收到该参数新有效帧才恢复报警。
        if (!hasLiveMonitorValues()) {
            return alarms;
        }
        for (int limitIndex = 0; limitIndex < monitorLimitEnabled.length; limitIndex++) {
            addMonitorAlarm(alarms, limitIndex, monitorAlarmValue(limitIndex));
        }
        return alarms;
    }

    private float monitorAlarmValue(int limitIndex) {
        if (limitIndex == LIMIT_HEART) {
            return am4100Manager.getHeartRateValue();
        }
        if (limitIndex == LIMIT_PULSE) {
            return am4100Manager.getPulseRateValue();
        }
        if (limitIndex == LIMIT_SPO2) {
            return am4100Manager.getSpo2Value();
        }
        if (limitIndex == LIMIT_RESP) {
            return am4100Manager.getRespValue();
        }
        if (limitIndex == LIMIT_TEMP) {
            return parseFloatSafe(am4100Manager.getBodyTempText());
        }
        String bloodPressure = am4100Manager.getBloodPressureText();
        int slash = bloodPressure == null ? -1 : bloodPressure.indexOf('/');
        if (slash <= 0) {
            return Float.NaN;
        }
        return parseFloatSafe(limitIndex == LIMIT_BP_SYS
                ? bloodPressure.substring(0, slash) : bloodPressure.substring(slash + 1));
    }

    /** 报警先过统一的异常测量防线，再单独应用用户设置的上下限。 */
    private boolean isValidMonitorAlarmValue(int limitIndex, float value) {
        // LIMIT_* 与 AM4100 PARAM_* 同为 0..6；这里只复用编号，不改变报警门限语义。
        return VitalMeasurementPolicy.isValid(limitIndex, value);
    }

    private void addMonitorAlarm(ArrayList<MonitorAlarm> alarms, int limitIndex, float value) {
        if (!am4100Manager.isParamFresh(limitIndex)
                || !isValidMonitorAlarmValue(limitIndex, value) || !monitorLimitEnabled[limitIndex]) {
            return;
        }
        boolean high = value > monitorLimitHigh[limitIndex];
        boolean low = value < monitorLimitLow[limitIndex];
        if (!high && !low) {
            return;
        }
        String unit = monitorLimitUnit(limitIndex);
        String valueText = formatLimitValue(limitIndex, value);
        String bound = formatLimitValue(limitIndex, high ? monitorLimitHigh[limitIndex] : monitorLimitLow[limitIndex]);
        String text = monitorLimitName(limitIndex) + (high ? "超上限 " : "低于下限 ") + valueText + unit
                + "（限值 " + bound + unit + "）";
        alarms.add(new MonitorAlarm(limitIndex, monitorLimitName(limitIndex), valueText, unit, high, text));
    }

    /** 超限报警列表，页面上要显示红色报警条并把对应数值标红。 */
    private JSONArray monitorAlarmJson() throws JSONException {
        JSONArray array = new JSONArray();
        ArrayList<MonitorAlarm> alarms = collectMonitorAlarms();
        for (int i = 0; i < alarms.size(); i++) {
            MonitorAlarm alarm = alarms.get(i);
            JSONObject item = new JSONObject();
            item.put("index", alarm.limitIndex);
            item.put("name", alarm.name);
            item.put("value", alarm.value);
            item.put("unit", alarm.unit);
            item.put("high", alarm.high);
            item.put("text", alarm.text);
            array.put(item);
        }
        return array;
    }

    /**
     * 被动超限提醒只播放本次新事件：持续超限不重复，恢复后再次超限或高低限换向才算新事件。
     * 红色报警条仍按当前真实状态持续显示；按钮主动 Toast 不经过这里。
     */
    private void checkMonitorAlarms() {
        if (!hasLiveMonitorValues()) {
            // 断连/协议未就绪：取消已经 show、仍可能排队出现的旧体征 Toast。
            cancelMonitorAlarmsIfDisconnected();
            return;
        }
        ArrayList<MonitorAlarm> alarms = collectMonitorAlarms();
        boolean[] high = new boolean[monitorLimitEnabled.length];
        boolean[] low = new boolean[monitorLimitEnabled.length];
        boolean[] evaluated = new boolean[monitorLimitEnabled.length];
        for (int i = 0; i < evaluated.length; i++) {
            evaluated[i] = monitorLimitEnabled[i] && am4100Manager.isParamFresh(i)
                    && isValidMonitorAlarmValue(i, monitorAlarmValue(i));
        }
        StringBuilder signature = new StringBuilder();
        for (int i = 0; i < alarms.size(); i++) {
            MonitorAlarm alarm = alarms.get(i);
            high[alarm.limitIndex] = alarm.high;
            low[alarm.limitIndex] = !alarm.high;
            signature.append(alarm.limitIndex).append(alarm.high ? "H" : "L").append(';');
        }
        List<Integer> newEvents = monitorAlarmTracker.update(high, low, evaluated);
        String current = signature.toString();
        boolean changed = !current.equals(monitorAlarmSignature);
        monitorAlarmSignature = current;
        boolean notified = false;
        if (!newEvents.isEmpty()) {
            if (!hasLiveMonitorValues()) {
                clearMonitorAlarmState();
                return;
            }
            for (int i = 0; i < alarms.size(); i++) {
                MonitorAlarm alarm = alarms.get(i);
                // 只提示新事件，不能因另一参数刚越界就把所有持续报警重新 Toast 一遍。
                if (!newEvents.contains(alarm.limitIndex)
                        || !am4100Manager.isParamFresh(alarm.limitIndex)) {
                    continue;
                }
                while (monitorAlarmToasts.size() >= 14) {
                    monitorAlarmToasts.remove(0).cancel();
                }
                Toast toast = Toast.makeText(activity, alarm.text, Toast.LENGTH_LONG);
                monitorAlarmToasts.add(toast);
                toast.show();
                notified = true;
            }
            if (notified && monitorAlarmSound) {
                playMonitorAlarmSound();
            }
        }
        if (changed || notified) {
            activity.notifyLanhuStateChanged();
        }
    }

    private String currentTimeText() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    /** 把采样/补录的真实时间戳转成单项开始时间文本，和 parseTimeTextMs 同一格式。 */
    private static String timeTextOf(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(millis));
    }

    /**
     * 用户明确指定的 4 个环境传感器：舱内温度、氧浓度、湿度、CO2浓度。
     * 它们的开始时间由“第一次读到有效数值”决定，不能按开关沿或蓝牙连接状态写。
     */
    private static boolean isEnvironmentSensorEntry(String itemName) {
        return "舱内温度".equals(itemName) || "氧浓度".equals(itemName)
                || "氧气浓度".equals(itemName) || "湿度".equals(itemName)
                || "CO2浓度".equals(itemName);
    }

    /** 生命体征与波形项：开始时间同样由首次真实读数决定，而不是由连接状态决定。 */
    private static boolean isMonitorReadingEntry(String itemName) {
        return isMonitorVitalItem(itemName) || TreatmentSamplePolicy.isWaveform(itemName);
    }

    /** “是否打开”列按读数判定的项目：4 个环境传感器 + 监护仪生命体征/波形。 */
    private static boolean isReadingTrackedEntry(String itemName) {
        return isEnvironmentSensorEntry(itemName) || isMonitorReadingEntry(itemName);
    }

    /**
     * 读数型项目是否真的读到过数值。已落档的首次读数或有效样本优先；
     * 当前治疗中还会看此刻显示值，让刚收到的第一个数值马上反映到勾选列。
     */
    private boolean entryHasReading(PatientCase patient, TreatmentEntry entry) {
        if (entry == null) {
            return false;
        }
        if (entry.readingStarted || entry.sampleCount > 0) {
            return true;
        }
        if (patient == null || !patient.currentTreatment) {
            return false;
        }
        String display = treatmentDisplayValue(entry.itemName);
        float value = parseTreatmentSampleValue(entry.itemName, display);
        return waveformSourceReady(entry.itemName) && isValidTreatmentSample(entry.itemName, value);
    }

    /**
     * 波形三项的开始时间必须等配对参数同时有值：
     * 心电图=心率+血压，血氧波形=血氧+脉率，呼吸率波形=体温+呼吸率。
     * 非波形项不受此约束。
     */
    private boolean waveformSourceReady(String itemName) {
        if ("心电图".equals(itemName)) {
            return VitalMeasurementPolicy.isValid(Am4100Manager.PARAM_HEART_RATE,
                            am4100Manager.getHeartRateValue())
                    && VitalMeasurementPolicy.isValid(Am4100Manager.PARAM_BP_SYS,
                            am4100Manager.getBloodPressureSysValue())
                    && VitalMeasurementPolicy.isValid(Am4100Manager.PARAM_BP_DIA,
                            am4100Manager.getBloodPressureDiaValue());
        }
        if ("血氧波形".equals(itemName)) {
            return VitalMeasurementPolicy.isValid(Am4100Manager.PARAM_SPO2,
                            am4100Manager.getSpo2Value())
                    && VitalMeasurementPolicy.isValid(Am4100Manager.PARAM_PULSE,
                            am4100Manager.getPulseRateValue());
        }
        if ("呼吸率波形".equals(itemName)) {
            float temp = parseFloatSafe(am4100Manager.getBodyTempText());
            return isValidTreatmentSample("体温", temp)
                    && VitalMeasurementPolicy.isValid(Am4100Manager.PARAM_RESP,
                            am4100Manager.getRespValue());
        }
        return true;
    }

    private String shortEndTime(String value) {
        if (TextUtils.isEmpty(value) || "进行中".equals(value)) {
            return "进行中";
        }
        return shortDateTime(value);
    }

    private String shortDateTime(String value) {
        if (TextUtils.isEmpty(value)) {
            return "--";
        }
        if ("进行中".equals(value)) {
            return value;
        }
        return value.length() > 16 ? value.substring(5, 16) : value;
    }

    private String currentTreatmentLiveSummary() {
        return "舱温 " + bleManager.getCabinTempText()
                + "  氧 " + bleManager.getOxygenText()
                + "  心率 " + am4100Manager.getHeartRateText();
    }

    private String firstNonEmpty(String primary, String fallback) {
        return TextUtils.isEmpty(primary) ? fallback : primary;
    }

    private float parseFirstNumber(String value) {
        if (TextUtils.isEmpty(value) || "--".equals(value)) {
            return Float.NaN;
        }
        StringBuilder builder = new StringBuilder();
        boolean started = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean numeric = (ch >= '0' && ch <= '9') || ch == '.' || ch == '-';
            if (numeric) {
                builder.append(ch);
                started = true;
            } else if (started) {
                break;
            }
        }
        if (builder.length() == 0) {
            return Float.NaN;
        }
        try {
            return Float.parseFloat(builder.toString());
        } catch (NumberFormatException exception) {
            return Float.NaN;
        }
    }

    private float parseTreatmentSampleValue(String itemName, String display) {
        if ("治疗时长".equals(itemName)) {
            return parseClockMinutes(display);
        }
        return parseFirstNumber(display);
    }

    private float parseClockMinutes(String value) {
        if (TextUtils.isEmpty(value)) {
            return Float.NaN;
        }
        int separator = value.indexOf(':');
        if (separator <= 0) {
            return parseFirstNumber(value);
        }
        try {
            float hours = Float.parseFloat(value.substring(0, separator).trim());
            float minutes = Float.parseFloat(value.substring(separator + 1).trim());
            return hours * 60f + minutes;
        } catch (NumberFormatException exception) {
            return Float.NaN;
        }
    }

    private float parseFloatSafe(String value) {
        if (TextUtils.isEmpty(value) || "--".equals(value)) {
            return Float.NaN;
        }
        String cleaned = value.replace("℃", "").replace("%", "").trim();
        try {
            return Float.parseFloat(cleaned);
        } catch (NumberFormatException exception) {
            return Float.NaN;
        }
    }

    private void scheduleMonitorRefresh() {
        if (monitorRefreshPending || showingSettings || activeTab != 2) {
            return;
        }
        if (!am4100Manager.isScanning() && !am4100Manager.isConnected() && !am4100Manager.hasVitals()) {
            return;
        }
        monitorRefreshPending = true;
        postDelayed(monitorRefreshRunnable, 160L);
    }

    private void drawCameraPage(Canvas canvas, float x, float y, float w, float h) {
        fill(canvas, x, y, x + w, y + h, Color.WHITE);
        RectF shell = rect(x + dp(28), y, x + w - dp(18), y + h - dp(18));
        rounded(canvas, shell, page, Color.TRANSPARENT, dp(8));
        float rightW = dp(420);
        RectF right = rect(shell.right - rightW, shell.top, shell.right, shell.bottom);
        drawCameraMode(canvas, right);
        if (cameraPlayback) {
            updateCameraPreviewOverlay(null, false);
            drawCameraPlayback(canvas, shell.left + dp(24), shell.top + dp(24), shell.width() - rightW - dp(54), shell.height() - dp(48));
        } else {
            RectF video = rect(shell.left + dp(24), shell.top + dp(24), right.left - dp(28), shell.bottom - dp(24));
            drawVideoPlayer(canvas, video);
            RectF preview = inset(video, dp(2), dp(2));
            preview.bottom -= dp(34);
            updateCameraPreviewOverlay(preview, true);
        }
    }

    private void updateCameraPreviewOverlay(RectF bounds, boolean visible) {
        if (cameraPreviewHost == null) {
            return;
        }
        if (!visible || bounds == null) {
            cameraPreviewHost.updateCameraPreview(null, false, null);
            return;
        }
        cameraPreviewHost.updateCameraPreview(new RectF(bounds), true, buildTpLinkRtspCandidates(cameraStreamUrl));
    }

    private void handleCameraPlaybackAction(int actionIndex) {
        if (actionIndex == 0) {
            captureCameraSnapshotAction();
        } else if (actionIndex == 1) {
            downloadSelectedCameraSnapshot();
        } else {
            deleteSelectedCameraSnapshot();
        }
    }

    private void captureCameraSnapshotAction() {
        if (cameraPreviewHost == null) {
            Toast.makeText(activity, "摄像头预览未准备好", Toast.LENGTH_SHORT).show();
            return;
        }
        cameraPreviewHost.captureCameraFrame(new CameraFrameCallback() {
            @Override
            public void onFrame(Bitmap bitmap) {
                try {
                    saveCameraSnapshot(bitmap);
                    Toast.makeText(activity, "截图已保存到回放列表", Toast.LENGTH_SHORT).show();
                    invalidate();
                } catch (IOException exception) {
                    Toast.makeText(activity, "截图保存失败: " + exception.getMessage(), Toast.LENGTH_LONG).show();
                } finally {
                    if (bitmap != null && !bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                }
            }

            @Override
            public void onError(String message) {
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void saveCameraSnapshot(Bitmap bitmap) throws IOException {
        File dir = new File(activity.getFilesDir(), "camera_snapshots");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建截图目录");
        }
        String fileName = createCameraSnapshotFileName();
        File target = new File(dir, fileName);
        FileOutputStream output = new FileOutputStream(target);
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                throw new IOException("无法编码截图");
            }
        } finally {
            output.close();
        }
        String timeText = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        cameraSnapshots.add(0, new CameraSnapshot(fileName, timeText, target, null));
        selectedCameraSnapshotIndex = 0;
        while (cameraSnapshots.size() > 60) {
            CameraSnapshot removed = cameraSnapshots.remove(cameraSnapshots.size() - 1);
            if (removed.cacheFile != null && removed.cacheFile.exists()) {
                removed.cacheFile.delete();
            }
        }
        saveCameraSnapshotsToStorage();
    }

    private void downloadSelectedCameraSnapshot() {
        CameraSnapshot snapshot = selectedCameraSnapshot();
        if (snapshot == null || snapshot.cacheFile == null || !snapshot.cacheFile.exists()) {
            Toast.makeText(activity, "请先选择一张截图", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = copyImageToPictures(snapshot.cacheFile, snapshot.fileName);
            snapshot.downloadUri = uri;
            saveCameraSnapshotsToStorage();
            Toast.makeText(activity, "截图已下载: " + uri.toString(), Toast.LENGTH_LONG).show();
            invalidate();
        } catch (IOException exception) {
            Toast.makeText(activity, "下载截图失败: " + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void deleteSelectedCameraSnapshot() {
        final CameraSnapshot snapshot = selectedCameraSnapshot();
        if (snapshot == null) {
            Toast.makeText(activity, "请先选择一张截图", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("删除截图")
                .setMessage("确定删除这张摄像头截图吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (snapshot.cacheFile != null && snapshot.cacheFile.exists()) {
                            snapshot.cacheFile.delete();
                        }
                        cameraSnapshots.remove(snapshot);
                        selectedCameraSnapshotIndex = cameraSnapshots.isEmpty()
                                ? -1
                                : Math.min(selectedCameraSnapshotIndex, cameraSnapshots.size() - 1);
                        saveCameraSnapshotsToStorage();
                        Toast.makeText(activity, "截图已删除", Toast.LENGTH_SHORT).show();
                        invalidate();
                    }
                })
                .show();
    }

    private CameraSnapshot selectedCameraSnapshot() {
        if (cameraSnapshots.isEmpty()) {
            return null;
        }
        if (selectedCameraSnapshotIndex < 0 || selectedCameraSnapshotIndex >= cameraSnapshots.size()) {
            selectedCameraSnapshotIndex = 0;
        }
        return cameraSnapshots.get(selectedCameraSnapshotIndex);
    }

    /**
     * 供网页渲染回放列表用的数据：截图条目、当前选中项和本次会话保存的录像。
     */
    String buildCameraSnapshotListJson() {
        try {
            JSONArray items = new JSONArray();
            for (int i = 0; i < cameraSnapshots.size(); i++) {
                CameraSnapshot snapshot = cameraSnapshots.get(i);
                JSONObject node = new JSONObject();
                node.put("index", i);
                node.put("fileName", snapshot.fileName);
                node.put("timeText", snapshot.timeText);
                node.put("downloaded", snapshot.downloadUri != null);
                node.put("uri", snapshot.cacheFile == null
                        ? "" : CameraFileProvider.uriFor(snapshot.cacheFile, false).toString());
                items.put(node);
            }
            JSONArray recordings = new JSONArray();
            for (int i = 0; i < cameraRecordings.size(); i++) {
                CameraRecording recording = cameraRecordings.get(i);
                JSONObject node = new JSONObject();
                node.put("index", i);
                node.put("fileName", recording.fileName);
                node.put("timeText", recording.timeText);
                node.put("uri", recording.uri == null ? "" : recording.uri.toString());
                recordings.put(node);
            }
            JSONObject root = new JSONObject();
            root.put("selected", selectedCameraSnapshotIndex);
            root.put("items", items);
            root.put("recordings", recordings);
            return root.toString();
        } catch (JSONException exception) {
            return "{\"selected\":-1,\"items\":[],\"recordings\":[]}";
        }
    }

    /**
     * 选中某一张截图，下载和删除都以选中项为准。
     */
    void selectCameraSnapshot(int index) {
        if (index < 0 || index >= cameraSnapshots.size()) {
            return;
        }
        selectedCameraSnapshotIndex = index;
    }

    /**
     * 录像状态，网页据此把"录制"按钮切换成"停止录制"。
     */
    boolean isCameraRecording() {
        return cameraPreviewHost != null && cameraPreviewHost.isCameraRecording();
    }

    /**
     * 开始 / 结束录像，由网页上的"录制"按钮触发。
     */
    private void toggleCameraRecording() {
        if (cameraPreviewHost == null) {
            Toast.makeText(activity, "摄像头预览未准备好", Toast.LENGTH_SHORT).show();
            return;
        }
        if (cameraPreviewHost.isCameraRecording()) {
            finishCameraRecording();
        } else {
            startCameraRecording();
        }
    }

    private void startCameraRecording() {
        if (TextUtils.isEmpty(cameraStreamUrl)) {
            Toast.makeText(activity, "请先配置摄像头地址", Toast.LENGTH_SHORT).show();
            return;
        }
        File dir = new File(activity.getFilesDir(), "camera_recordings");
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(activity, "无法创建录像目录", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            cameraPreviewHost.startCameraRecording(new File(dir, createCameraRecordingFileName()));
            Toast.makeText(activity, "开始录制，再点一次“录制”结束", Toast.LENGTH_SHORT).show();
        } catch (IOException exception) {
            Toast.makeText(activity, "开始录制失败: " + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 结束录像并转存到系统相册 Movies/ICUCamera，转存后删掉临时文件。
     */
    private void finishCameraRecording() {
        File file = cameraPreviewHost.stopCameraRecording();
        if (file == null || !file.exists() || file.length() <= 0) {
            Toast.makeText(activity, "没有录到有效画面，录像已取消", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = copyVideoToMovies(file, file.getName());
            String timeText = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            cameraRecordings.add(0, new CameraRecording(file.getName(), timeText, uri));
            Toast.makeText(activity, "录像已保存到系统相册 Movies/ICUCamera", Toast.LENGTH_LONG).show();
        } catch (IOException exception) {
            Toast.makeText(activity, "录像保存失败: " + exception.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            if (file.exists()) {
                file.delete();
            }
        }
    }

    /**
     * 画面被隐藏（切页、进入回放）时自动收尾，避免留下写坏的录像文件。
     */
    void stopCameraRecordingIfNeeded() {
        if (cameraPreviewHost == null || !cameraPreviewHost.isCameraRecording()) {
            return;
        }
        finishCameraRecording();
    }

    /**
     * 用系统播放器打开录像。
     */
    private void playCameraRecording(int index) {
        if (index < 0 || index >= cameraRecordings.size()) {
            return;
        }
        Uri uri = cameraRecordings.get(index).uri;
        if (uri == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "video/mp4");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception exception) {
            Toast.makeText(activity, "没有可用的视频播放器", Toast.LENGTH_SHORT).show();
        }
    }

    private String createCameraRecordingFileName() {
        PatientCase patient = currentCase();
        String no = patient == null ? "camera" : sanitizeFileName(patient.recordNo + "_" + patient.petName);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return "icu_camera_" + no + "_" + stamp + ".mp4";
    }

    private Uri copyVideoToMovies(File source, String fileName) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ICUCamera");
            Uri uri = activity.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("无法创建视频文件");
            }
            OutputStream output = activity.getContentResolver().openOutputStream(uri);
            if (output == null) {
                throw new IOException("无法打开视频文件");
            }
            copyFile(source, output);
            return uri;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ICUCamera");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建视频目录");
        }
        File target = new File(dir, fileName);
        FileOutputStream output = new FileOutputStream(target);
        copyFile(source, output);
        return Uri.fromFile(target);
    }

    /**
     * 摄像头里还没开放的功能，逐项说明原因，避免用户以为点了没反应。
     */
    private void showCameraFeatureToast(String key) {
        String name = "";
        for (String[] feature : CAMERA_UNSUPPORTED_FEATURES) {
            if (feature[0].equals(key)) {
                name = feature[1];
                break;
            }
        }
        if (TextUtils.isEmpty(name)) {
            showUnsupportedCameraFeatureToast();
            return;
        }
        Toast.makeText(activity, name + "：需要摄像头厂家控制协议支持，当前版本暂未开放", Toast.LENGTH_LONG).show();
    }

    /**
     * 解析动作名里的序号，例如 camera_snapshot_select_3 -> 3。
     */
    private static int actionIndex(String action, String prefix) {
        try {
            return Integer.parseInt(action.substring(prefix.length()));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private void drawCameraMode(Canvas canvas, RectF right) {
        RectF live = rect(right.left + dp(44), right.top + dp(66), right.left + dp(204), right.top + dp(108));
        RectF replay = rect(right.left + dp(230), right.top + dp(66), right.left + dp(390), right.top + dp(108));
        rounded(canvas, live, !cameraPlayback ? activeBlue : Color.WHITE, Color.TRANSPARENT, dp(4));
        rounded(canvas, replay, cameraPlayback ? activeBlue : Color.WHITE, Color.TRANSPARENT, dp(4));
        drawTextCenter(canvas, "实况", live, 20, !cameraPlayback ? Color.WHITE : text, true);
        drawTextCenter(canvas, "回放", replay, 20, cameraPlayback ? Color.WHITE : text, true);
        clickZones.add(new ClickZone(live, new ClickAction() {
            @Override
            public void run() {
                cameraPlayback = false;
            }
        }));
        clickZones.add(new ClickZone(replay, new ClickAction() {
            @Override
            public void run() {
                cameraPlayback = true;
            }
        }));
        if (cameraPlayback) {
            String[] buttons = {"截图", "下载", "删除"};
            for (int i = 0; i < buttons.length; i++) {
                final int actionIndex = i;
                drawActionButton(canvas, buttons[i], right.left + dp(44), right.top + dp(170) + i * dp(62), right.width() - dp(88), dp(42), new ClickAction() {
                    @Override
                    public void run() {
                        handleCameraPlaybackAction(actionIndex);
                    }
                });
            }
            return;
        }
        String[] labels = {"截图", "录制", "全屏", "对讲", "镜头遮蔽", "画面翻转", "手动报警", "原图", "巡航", "视频参数", "图表", "自定义"};
        String[] icons = {"✂", "▣", "⛶", "♩", "▧", "⇄", "♕", "▧", "◉", "▻", "▥", "＋"};
        float cellW = dp(82);
        float cellH = dp(74);
        float startX = right.left + dp(44);
        float startY = right.top + dp(168);
        for (int i = 0; i < labels.length; i++) {
            int col = i % 3;
            int row = i / 3;
            final int actionIndex = i;
            RectF cell = rect(startX + col * dp(116), startY + row * dp(112), startX + col * dp(116) + cellW, startY + row * dp(112) + cellH);
            rounded(canvas, cell, i == 5 ? Color.rgb(91, 174, 245) : Color.WHITE, Color.TRANSPARENT, dp(4));
            drawTextCenter(canvas, icons[i], rect(cell.left, cell.top + dp(8), cell.right, cell.top + dp(42)), 24, i == 5 ? Color.WHITE : text, true);
            drawTextCenter(canvas, labels[i], rect(cell.left - dp(12), cell.bottom + dp(8), cell.right + dp(12), cell.bottom + dp(34)), 14, text, false);
            clickZones.add(new ClickZone(new RectF(cell), new ClickAction() {
                @Override
                public void run() {
                    if (actionIndex == 0) {
                        captureCameraSnapshotAction();
                    } else if (actionIndex == 2) {
                        openTpLinkCameraPreview();
                    } else if (actionIndex == 9) {
                        showTpLinkCameraDialog();
                    } else {
                        showUnsupportedCameraFeatureToast();
                    }
                }
            }));
        }
    }

    private void showUnsupportedCameraFeatureToast() {
        Toast.makeText(activity, "当前只支持预览、全屏、截图和视频参数配置；此项需摄像头厂家控制协议", Toast.LENGTH_SHORT).show();
    }

    private void showTpLinkCameraDialog() {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dpInt(12);
        form.setPadding(pad, pad, pad, pad);

        final EditText urlInput = addInput(form, "RTSP地址，例如 rtsp://账号:密码@设备IP:554/stream2",
                cameraStreamUrl, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        TextView help = new TextView(activity);
        help.setText("会自动尝试TP-LINK常见地址：/stream2、/stream2&channel=1、/stream1、/stream1&channel=1。\n"
                + "请在摄像头后台开启RTSP，确认账号密码、H.264编码、端口554和设备网络。地址只保存在本机。\n"
                + "地址不可达时每个地址最多等5秒，一轮尝试不超过25秒，之后会直接提示失败原因。");
        help.setTextSize(13);
        help.setPadding(0, dpInt(8), 0, 0);
        form.addView(help);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("TP-LINK摄像头配置")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存并预览", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String url = urlInput.getText().toString().trim();
                        if (TextUtils.isEmpty(url)) {
                            Toast.makeText(activity, "请填写摄像头RTSP地址", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!url.toLowerCase(Locale.US).startsWith("rtsp://")) {
                            Toast.makeText(activity, "目前只支持 rtsp:// 开头的地址", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        cameraStreamUrl = url;
                        saveCameraSettings();
                        invalidate();
                        restartInlineCameraPreview();
                        Toast.makeText(activity, "已保存，正在连接摄像头…", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
    }

    private void openTpLinkCameraPreview() {
        if (TextUtils.isEmpty(cameraStreamUrl)) {
            Toast.makeText(activity, "请先配置摄像头视频地址", Toast.LENGTH_SHORT).show();
            showTpLinkCameraDialog();
            return;
        }
        openTpLinkCameraInternal();
    }

    /**
     * 重新配置摄像头地址后重启内嵌预览：地址没变也要重连，
     * 否则连接失败之后用户改完地址画面不会自己恢复。
     */
    private void restartInlineCameraPreview() {
        if (cameraPreviewHost == null) {
            return;
        }
        cameraPreviewHost.restartCameraPreview(buildTpLinkRtspCandidates(cameraStreamUrl));
    }

    private void openTpLinkCameraInternal() {
        Intent intent = new Intent(activity, CameraStreamActivity.class);
        intent.putExtra(CameraStreamActivity.EXTRA_STREAM_URL, cameraStreamUrl);
        intent.putExtra(CameraStreamActivity.EXTRA_STREAM_URLS, buildTpLinkRtspCandidates(cameraStreamUrl));
        activity.startActivity(intent);
    }

    private void openTpLinkCameraExternal() {
        if (TextUtils.isEmpty(cameraStreamUrl)) {
            Toast.makeText(activity, "请先配置TP-LINK摄像头视频地址", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(cameraStreamUrl));
            intent.setDataAndType(Uri.parse(cameraStreamUrl), "video/*");
            activity.startActivity(Intent.createChooser(intent, "选择播放器打开摄像头"));
        } catch (Exception exception) {
            Toast.makeText(activity, "没有可用播放器，建议先安装VLC播放器", Toast.LENGTH_LONG).show();
        }
    }

    private void loadCameraSettings() {
        SharedPreferences prefs = devicePrefs();
        if (prefs.getBoolean(KEY_CAMERA_STREAM_USER_SET, false)) {
            cameraStreamUrl = prefs.getString(KEY_CAMERA_STREAM_URL, "");
        } else {
            cameraStreamUrl = "";
            prefs.edit().remove(KEY_CAMERA_STREAM_URL).apply();
        }
    }

    private void saveCameraSettings() {
        SharedPreferences prefs = devicePrefs();
        prefs.edit()
                .putString(KEY_CAMERA_STREAM_URL, cameraStreamUrl)
                .putBoolean(KEY_CAMERA_STREAM_USER_SET, !TextUtils.isEmpty(cameraStreamUrl))
                .apply();
    }

    private String defaultCameraStreamUrl() {
        return "";
    }

    private String[] buildTpLinkRtspCandidates(String configuredUrl) {
        ArrayList<String> urls = new ArrayList<>();
        addCameraUrl(urls, configuredUrl);
        addTpLinkRootCandidates(urls, cameraRtspRoot(configuredUrl));
        return urls.toArray(new String[0]);
    }

    private void addTpLinkRootCandidates(ArrayList<String> urls, String root) {
        if (TextUtils.isEmpty(root)) {
            return;
        }
        addCameraUrl(urls, root + "/stream2");
        addCameraUrl(urls, root + "/stream2&channel=1");
        addCameraUrl(urls, root + "/stream2?channel=1");
        addCameraUrl(urls, root + "/stream2&channel=2");
        addCameraUrl(urls, root + "/stream1");
        addCameraUrl(urls, root + "/stream1&channel=1");
        addCameraUrl(urls, root + "/stream1?channel=1");
        addCameraUrl(urls, root + "/stream1&channel=2");
    }

    private void addCameraUrl(ArrayList<String> urls, String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        String trimmed = url.trim();
        if (trimmed.length() == 0 || urls.contains(trimmed)) {
            return;
        }
        urls.add(trimmed);
    }

    private String cameraRtspRoot(String url) {
        if (TextUtils.isEmpty(url) || !url.startsWith("rtsp://")) {
            return "";
        }
        int schemeEnd = url.indexOf("://");
        int pathStart = url.indexOf('/', schemeEnd + 3);
        if (pathStart <= 0) {
            return url;
        }
        return url.substring(0, pathStart);
    }

    private String maskCameraUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return "-";
        }
        int schemeEnd = url.indexOf("://");
        int at = url.indexOf('@');
        int colon = schemeEnd >= 0 ? url.indexOf(':', schemeEnd + 3) : -1;
        if (schemeEnd >= 0 && colon > schemeEnd && at > colon) {
            return url.substring(0, colon + 1) + "******" + url.substring(at);
        }
        return url;
    }

    private void drawRecordPage(Canvas canvas, float x, float y, float w, float h) {
        fill(canvas, x, y, x + w, y + h, page);
        captureTreatmentSampleIfNeeded(false);
        final PatientCase patient = currentCase();
        RectF shell = rect(x + dp(10), y + dp(12), x + w - dp(10), y + h - dp(12));
        rounded(canvas, shell, Color.WHITE, border, dp(2));
        drawText(canvas, "治疗记录", shell.left + dp(18), shell.top + dp(28), 17, text, true);
        if (patient == null) {
            drawText(canvas, "暂无治疗记录", shell.left + dp(18), shell.top + dp(64), 12, muted, false);
            return;
        }

        drawText(canvas, (patient.currentTreatment ? "当前治疗" : "历史治疗") + "  " + patient.petName + " / " + patient.recordNo,
                shell.left + dp(120), shell.top + dp(28), 12, patient.currentTreatment ? green : muted, true);
        drawText(canvas, "开始: " + patient.treatmentStartTime + "    结束: " + patient.treatmentEndTime,
                shell.left + dp(18), shell.top + dp(52), 10, muted, false);
        drawTextRight(canvas, "采样策略: 每分钟写入一次，异常断电时保留最近有效统计", shell.right - dp(18), shell.top + dp(52), 10, muted, false);

        drawSmallButton(canvas, "新建治疗记录", shell.left + dp(18), shell.top + dp(68), dp(100), dp(26), false, new ClickAction() {
            @Override
            public void run() {
                createNewTreatmentRecord();
            }
        });
        drawSmallButton(canvas, "结束当前治疗", shell.left + dp(130), shell.top + dp(68), dp(100), dp(26), !patient.currentTreatment, new ClickAction() {
            @Override
            public void run() {
                finishCurrentTreatment();
            }
        });

        RectF table = rect(shell.left + dp(12), shell.top + dp(108), shell.right - dp(12), shell.bottom - dp(12));
        String[] headers = {"项目项", "开启", "录入报告", "时间段", "平均值", "最高值", "最低值", "手工编辑", "开始治疗时间", "结束治疗时间"};
        float[] col = {0.12f, 0.06f, 0.08f, 0.12f, 0.09f, 0.09f, 0.09f, 0.10f, 0.13f, 0.12f};
        float rowH = Math.max(dp(25), Math.min(dp(34), table.height() / (patient.treatmentEntries.size() + 1f)));
        float cy = table.top;
        drawTableRow(canvas, table.left, cy, table.width(), rowH, headers, col, true);
        cy += rowH;
        for (int i = 0; i < patient.treatmentEntries.size(); i++) {
            final TreatmentEntry entry = patient.treatmentEntries.get(i);
            String[] row = {
                    entry.itemName,
                    entry.enabled ? "开" : "关",
                    entry.includeInReport ? "是" : "否",
                    treatmentPeriodLabel(entry),
                    treatmentAverageText(entry),
                    treatmentHighText(entry),
                    treatmentLowText(entry),
                    TextUtils.isEmpty(entry.manualValue) ? "点击编辑" : entry.manualValue,
                    shortDateTime(entry.startTime),
                    shortDateTime(entry.endTime)
            };
            RectF rowRect = rect(table.left, cy, table.right, cy + rowH);
            drawTableRow(canvas, table.left, cy, table.width(), rowH, row, col, false);
            clickZones.add(new ClickZone(new RectF(rowRect), new ClickAction() {
                @Override
                public void run() {
                    showTreatmentEntryDialog(patient, entry);
                }
            }));
            cy += rowH;
            if (cy + rowH > table.bottom + dp(2)) {
                break;
            }
        }
    }

    private void drawPdfFilesPage(Canvas canvas, float x, float y, float w, float h) {
        fill(canvas, x, y, x + w, y + h, page);
        RectF shell = rect(x + dp(10), y + dp(10), x + w - dp(10), y + h - dp(10));
        rounded(canvas, shell, Color.WHITE, border, dp(2));
        drawText(canvas, "PDF文件与下载记录", shell.left + dp(18), shell.top + dp(28), 18, text, true);
        drawText(canvas, "生成和下载后的PDF都会显示在这里，方便现场确认文件是否已经产生。", shell.left + dp(18), shell.top + dp(52), 11, muted, false);

        drawActionButton(canvas, "生成当前PDF", shell.left + dp(18), shell.top + dp(70), dp(118), dp(32), new ClickAction() {
            @Override
            public void run() {
                generatePdfAction();
            }
        });
        drawActionButton(canvas, "下载最新PDF", shell.left + dp(150), shell.top + dp(70), dp(118), dp(32), new ClickAction() {
            @Override
            public void run() {
                downloadPdfAction();
            }
        });
        drawActionButton(canvas, "清空记录", shell.left + dp(282), shell.top + dp(70), dp(92), dp(32), new ClickAction() {
            @Override
            public void run() {
                clearPdfRecords();
            }
        });

        RectF summary = rect(shell.right - dp(225), shell.top + dp(22), shell.right - dp(18), shell.top + dp(104));
        rounded(canvas, summary, Color.rgb(246, 250, 253), border, dp(3));
        drawText(canvas, "当前患者", summary.left + dp(12), summary.top + dp(22), 11, muted, false);
        PatientCase patient = currentCase();
        drawText(canvas, patient == null ? "-" : patient.petName + " / " + patient.recordNo, summary.left + dp(12), summary.top + dp(47), 14, text, true);
        ArrayList<PdfRecord> zoneRecords = pdfRecordsForCurrentZone();
        drawText(canvas, "记录数量: " + zoneRecords.size(), summary.left + dp(12), summary.top + dp(69), 11, muted, false);

        RectF preview = rect(shell.right - dp(285), shell.top + dp(118), shell.right - dp(18), shell.bottom - dp(16));
        RectF area = rect(shell.left + dp(18), shell.top + dp(118), preview.left - dp(14), shell.bottom - dp(16));
        canvas.save();
        canvas.clipRect(area);
        if (zoneRecords.isEmpty()) {
            drawEmptyPdfState(canvas, area);
        } else {
            float cy = area.top;
            int maxRows = Math.max(1, (int) (area.height() / dp(86)));
            int count = Math.min(maxRows, zoneRecords.size());
            for (int i = 0; i < count; i++) {
                drawPdfRecordCard(canvas, zoneRecords.get(i), area.left, cy, area.width(), dp(76));
                cy += dp(86);
            }
        }
        canvas.restore();
        drawPdfPreviewPanel(canvas, preview);
    }

    private void drawEmptyPdfState(Canvas canvas, RectF area) {
        RectF card = rect(area.left, area.top, area.right, area.top + dp(118));
        rounded(canvas, card, panel, border, dp(4));
        drawPdfIcon(canvas, card.left + dp(26), card.top + dp(22), dp(52), dp(68), Color.rgb(218, 82, 75));
        drawText(canvas, "还没有生成PDF", card.left + dp(96), card.top + dp(42), 15, text, true);
        drawText(canvas, "点击上方“生成当前PDF”后，会在这里看到文件名、时间和保存位置。", card.left + dp(96), card.top + dp(68), 11, muted, false);
    }

    private void drawPdfRecordCard(Canvas canvas, final PdfRecord record, float x, float y, float w, float h) {
        RectF card = rect(x, y, x + w, y + h);
        rounded(canvas, card, Color.rgb(248, 252, 255), border, dp(4));
        int iconColor = "下载PDF".equals(record.action) ? Color.rgb(64, 142, 210) : Color.rgb(218, 82, 75);
        drawPdfIcon(canvas, card.left + dp(14), card.top + dp(10), dp(42), dp(54), iconColor);
        drawText(canvas, record.action + "  " + record.fileName, card.left + dp(68), card.top + dp(22), 12, text, true);
        drawText(canvas, "患者: " + record.patientName + "    时间: " + record.timeText, card.left + dp(68), card.top + dp(43), 10, muted, false);
        drawText(canvas, record.location, card.left + dp(68), card.top + dp(62), 9, muted, false);
        String badge = record.downloadUri == null ? "已生成" : "已下载";
        RectF badgeRect = rect(card.right - dp(78), card.top + dp(13), card.right - dp(18), card.top + dp(35));
        rounded(canvas, badgeRect, record.downloadUri == null ? Color.rgb(250, 239, 238) : Color.rgb(231, 243, 252), border, dp(11));
        drawTextCenter(canvas, badge, badgeRect, 10, record.downloadUri == null ? Color.rgb(170, 66, 60) : blue, true);
        clickZones.add(new ClickZone(new RectF(card), new ClickAction() {
            @Override
            public void run() {
                Toast.makeText(activity, record.fileName + "\n" + record.location, Toast.LENGTH_LONG).show();
            }
        }));
        if (record.downloadUri == null && record.cacheFile != null && record.cacheFile.exists()) {
            drawSmallButton(canvas, "下载", card.right - dp(78), card.bottom - dp(30), dp(60), dp(22), false, new ClickAction() {
                @Override
                public void run() {
                    downloadExistingPdfRecord(record);
                }
            });
        }
    }

    private void drawPdfPreviewPanel(Canvas canvas, RectF area) {
        rounded(canvas, area, Color.rgb(246, 250, 253), border, dp(4));
        drawText(canvas, "PDF预览", area.left + dp(14), area.top + dp(24), 14, text, true);
        final PdfRecord latest = pdfRecords.isEmpty() ? null : pdfRecords.get(0);
        String fileText = latest == null ? "当前实时监护报告预览" : latest.fileName;
        String stateText = latest == null ? "尚未生成，可先点击“生成当前PDF”" : (latest.downloadUri == null ? "已生成，未下载" : "已下载，可按此预览打印");
        drawText(canvas, fileText, area.left + dp(14), area.top + dp(45), 9, muted, false);
        drawText(canvas, stateText, area.left + dp(14), area.top + dp(62), 9, muted, false);
        drawSmallButton(canvas, "下载预览", area.right - dp(82), area.top + dp(16), dp(66), dp(24), false, new ClickAction() {
            @Override
            public void run() {
                if (latest != null && latest.cacheFile != null && latest.cacheFile.exists() && latest.downloadUri == null) {
                    downloadExistingPdfRecord(latest);
                } else {
                    downloadPdfAction();
                }
            }
        });

        RectF paperArea = rect(area.left + dp(14), area.top + dp(78), area.right - dp(14), area.bottom - dp(14));
        float paperW = Math.min(paperArea.width(), paperArea.height() * 595f / 842f);
        float paperH = paperW * 842f / 595f;
        if (paperH > paperArea.height()) {
            paperH = paperArea.height();
            paperW = paperH * 595f / 842f;
        }
        RectF paperRect = rect(paperArea.centerX() - paperW / 2f, paperArea.top, paperArea.centerX() + paperW / 2f, paperArea.top + paperH);
        rounded(canvas, paperRect, Color.WHITE, Color.rgb(160, 170, 180), dp(2));
        canvas.save();
        canvas.clipRect(paperRect);
        canvas.translate(paperRect.left, paperRect.top);
        float scale = paperRect.width() / 595f;
        canvas.scale(scale, scale);
        Paint previewPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        String subtitle = latest == null ? "预览当前实时数据" : "预览文件: " + latest.fileName;
        drawPdfHeader(canvas, previewPaint, "实时监护与电波图", subtitle);
        int previewY = 116;
        previewY = drawPdfSection(canvas, previewPaint, "实时监护", previewY);
        previewY = drawPdfMonitorBlock(canvas, previewPaint, previewY);
        previewY += 12;
        previewY = drawPdfSection(canvas, previewPaint, "打印说明", previewY);
        drawPdfLine(canvas, previewPaint, "说明", "下载后的PDF会包含本页电波图，可直接打印", previewY);
        canvas.restore();
    }

    private void drawPdfIcon(Canvas canvas, float x, float y, float w, float h, int color) {
        RectF pageRect = rect(x, y, x + w, y + h);
        rounded(canvas, pageRect, Color.WHITE, color, dp(3));
        Path fold = new Path();
        fold.moveTo(pageRect.right - dp(15), pageRect.top);
        fold.lineTo(pageRect.right, pageRect.top + dp(15));
        fold.lineTo(pageRect.right - dp(15), pageRect.top + dp(15));
        fold.close();
        paint.setColor(Color.rgb(235, 239, 244));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(fold, paint);
        RectF label = rect(pageRect.left + dp(5), pageRect.centerY() - dp(10), pageRect.right - dp(5), pageRect.centerY() + dp(10));
        rounded(canvas, label, color, Color.TRANSPARENT, dp(3));
        drawTextCenter(canvas, "PDF", label, 10, Color.WHITE, true);
    }

    private void clearPdfRecords() {
        pdfRecords.clear();
        Toast.makeText(activity, "PDF记录已清空", Toast.LENGTH_SHORT).show();
        invalidate();
    }

    private void downloadExistingPdfRecord(PdfRecord record) {
        try {
            String downloadName = record.fileName;
            Uri uri = copyPdfToDownloads(record.cacheFile, downloadName);
            addPdfRecord("下载PDF", downloadName, "系统下载目录: " + uri.toString(), record.cacheFile, uri);
            Toast.makeText(activity, "PDF已下载: " + uri.toString(), Toast.LENGTH_LONG).show();
            invalidate();
        } catch (IOException exception) {
            Toast.makeText(activity, "下载失败: " + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void drawTutorialPage(Canvas canvas, float x, float y, float w, float h) {
        fill(canvas, x, y, x + w, y + h, page);
        float gap = dp(18);
        float thumbW = (w - gap * 4) / 3f;
        float thumbH = (h - gap * 3) / 2f;
        for (int i = 0; i < 6; i++) {
            int col = i % 3;
            int row = i / 3;
            RectF r = rect(x + gap + col * (thumbW + gap), y + gap + row * (thumbH + gap), x + gap + col * (thumbW + gap) + thumbW, y + gap + row * (thumbH + gap) + thumbH);
            if (i == 0) {
                drawThermalThumb(canvas, r);
            } else if (i == 1) {
                drawControlThumb(canvas, r);
            } else {
                drawDocumentThumb(canvas, r, i);
            }
        }
    }

    private void drawSettings(Canvas canvas, float x, float y, float w, float h) {
        fill(canvas, x, y, x + w, y + h, page);
        RectF left = rect(x + dp(16), y + dp(18), x + w * 0.52f, y + h - dp(18));
        RectF right = rect(x + w * 0.54f, y + dp(18), x + w - dp(16), y + h - dp(18));
        rounded(canvas, left, Color.WHITE, border, dp(2));
        rounded(canvas, right, Color.WHITE, border, dp(2));

        drawText(canvas, "蓝牙连接设置", left.left + dp(18), left.top + dp(28), 18, text, true);
        drawText(canvas, "在这里申请权限、开启蓝牙、扫描设备，并连接 ICU 主机。", left.left + dp(18), left.top + dp(56), 11, muted, false);
        drawText(canvas, "当前舱区: " + ("left".equals(bleManager.getCurrentZone()) ? "左舱" : "右舱"), left.left + dp(18), left.top + dp(78), 11, muted, false);
        drawSmallButton(canvas, "左舱", left.left + dp(112), left.top + dp(62), dp(52), dp(24), "left".equals(bleManager.getCurrentZone()), new ClickAction() {
            @Override
            public void run() {
                bleManager.setCurrentZone("left");
            }
        });
        drawSmallButton(canvas, "右舱", left.left + dp(174), left.top + dp(62), dp(52), dp(24), "right".equals(bleManager.getCurrentZone()), new ClickAction() {
            @Override
            public void run() {
                bleManager.setCurrentZone("right");
            }
        });

        float by = left.top + dp(104);
        drawActionButton(canvas, "申请权限", left.left + dp(18), by, dp(92), dp(32), new ClickAction() {
            @Override
            public void run() {
                activity.requestBlePermissionsFromUi();
            }
        });
        drawActionButton(canvas, "开启蓝牙", left.left + dp(122), by, dp(92), dp(32), new ClickAction() {
            @Override
            public void run() {
                bleManager.requestEnableBluetooth(activity);
            }
        });
        drawActionButton(canvas, bleManager.isScanning() ? "停止扫描" : "开始扫描", left.left + dp(226), by, dp(92), dp(32), new ClickAction() {
            @Override
            public void run() {
                if (bleManager.isScanning()) {
                    bleManager.stopScan();
                } else if (activity.hasScanPermission()) {
                    bleManager.startScan();
                } else {
                    // 直接走申请流程：没授权就弹系统授权框，被“不再询问”拦住了
                    // 就跳应用权限设置页，别只弹个 Toast 让人干瞪眼。
                    activity.requestBlePermissionsFromUi();
                }
            }
        });
        drawActionButton(canvas, "断开连接", left.left + dp(330), by, dp(92), dp(32), new ClickAction() {
            @Override
            public void run() {
                bleManager.disconnect();
            }
        });

        float infoY = by + dp(58);
        drawInfoLine(canvas, "连接状态", bleManager.getConnectionStateText(), left.left + dp(18), infoY, left.width() - dp(36));
        drawInfoLine(canvas, "设备名称", dash(bleManager.getConnectedDeviceName()), left.left + dp(18), infoY + dp(48), left.width() - dp(36));
        drawInfoLine(canvas, "设备ID", dash(bleManager.getConnectedDeviceId()), left.left + dp(18), infoY + dp(96), left.width() - dp(36));
        drawInfoLine(canvas, "协议摘要", dash(bleManager.getLastParsedStatus()), left.left + dp(18), infoY + dp(144), left.width() - dp(36));

        drawText(canvas, "附近设备", left.left + dp(18), infoY + dp(204), 14, text, true);
        RectF deviceArea = rect(left.left + dp(18), infoY + dp(218), left.right - dp(18), left.bottom - dp(16));
        List<BleManager.DeviceItem> devices = bleManager.getDevices();
        bleDeviceListBounds.set(deviceArea);
        canvas.save();
        canvas.clipRect(deviceArea);
        if (devices.isEmpty()) {
            bleDeviceScrollY = 0;
            bleDeviceMaxScrollY = 0;
            // 扫不到设备时把最可能的原因直接显示出来：
            // 权限没给、蓝牙没开、定位被关，系统都会把扫描结果整批过滤掉。
            String scanDiagnosis = bleManager.getScanDiagnosis();
            drawText(canvas, TextUtils.isEmpty(scanDiagnosis)
                            ? "还没有扫描到附近的 BLE 设备"
                            : scanDiagnosis,
                    deviceArea.left, deviceArea.top + dp(18), 12, muted, false);
        } else {
            // 附近设备可能有很多台，列表要能上下滑动，不能只截断前几行。
            float rowHeight = dp(48);
            float contentHeight = devices.size() * rowHeight;
            bleDeviceMaxScrollY = Math.max(0, contentHeight - deviceArea.height());
            bleDeviceScrollY = clamp(bleDeviceScrollY, 0, bleDeviceMaxScrollY);
            float dy = deviceArea.top - bleDeviceScrollY;
            for (int i = 0; i < devices.size(); i++) {
                final BleManager.DeviceItem item = devices.get(i);
                RectF row = rect(deviceArea.left, dy, deviceArea.right, dy + dp(42));
                if (row.bottom >= deviceArea.top && row.top <= deviceArea.bottom) {
                    rounded(canvas, row, panel, border, dp(2));
                    drawText(canvas, item.deviceName + "    RSSI " + item.rssi, row.left + dp(10), row.top + dp(17), 11, text, true);
                    drawText(canvas, item.deviceId, row.left + dp(10), row.top + dp(34), 9, muted, false);
                    RectF clickRow = new RectF(row);
                    clickRow.intersect(deviceArea);
                    clickZones.add(new ClickZone(clickRow, new ClickAction() {
                        @Override
                        public void run() {
                            bleManager.connect(item.deviceId);
                        }
                    }));
                }
                dy += rowHeight;
            }
            if (bleDeviceMaxScrollY > 0) {
                float thumbHeight = Math.max(dp(14), deviceArea.height()) * deviceArea.height() / contentHeight;
                float thumbTop = deviceArea.top + (deviceArea.height() - thumbHeight) * (bleDeviceScrollY / bleDeviceMaxScrollY);
                rounded(canvas, rect(deviceArea.right - dp(5), thumbTop, deviceArea.right - dp(1), thumbTop + thumbHeight),
                        Color.rgb(90, 116, 140), Color.TRANSPARENT, dp(2));
            }
        }
        canvas.restore();

        drawText(canvas, "通信状态", right.left + dp(18), right.top + dp(28), 18, text, true);
        drawActionButton(canvas, "读取全部状态", right.left + dp(18), right.top + dp(56), dp(118), dp(32), new ClickAction() {
            @Override
            public void run() {
                bleManager.readAllStatus();
            }
        });
        drawActionButton(canvas, "读取温度", right.left + dp(150), right.top + dp(56), dp(92), dp(32), new ClickAction() {
            @Override
            public void run() {
                bleManager.sendCommand("{\"cmd\":\"get_temp\"}");
            }
        });
        drawActionButton(canvas, "清空日志", right.left + dp(256), right.top + dp(56), dp(92), dp(32), new ClickAction() {
            @Override
            public void run() {
                bleManager.clearLogs();
            }
        });
        drawLogBox(canvas, "最近发送", dash(bleManager.getLastSent()), right.left + dp(18), right.top + dp(112), right.width() - dp(36), dp(64));
        drawLogBox(canvas, "最近接收", dash(bleManager.getLastReceived()), right.left + dp(18), right.top + dp(192), right.width() - dp(36), dp(64));
        drawLogBox(canvas, "最近错误", dash(bleManager.getLastError()), right.left + dp(18), right.top + dp(272), right.width() - dp(36), dp(64));
        drawText(canvas, "通信日志", right.left + dp(18), right.top + dp(372), 14, text, true);
        RectF logs = rect(right.left + dp(18), right.top + dp(386), right.right - dp(18), right.bottom - dp(16));
        rounded(canvas, logs, panel, border, dp(2));
        List<String> logLines = bleManager.getLogs();
        RectF logClip = inset(logs, dp(8), dp(8));
        float ly = logClip.top + dp(10);
        int maxLogLines = Math.max(0, (int) (logClip.height() / dp(18)));
        canvas.save();
        canvas.clipRect(logClip);
        for (int i = 0; i < Math.min(maxLogLines, logLines.size()); i++) {
            drawText(canvas, logLines.get(i), logClip.left, ly, 10, muted, false);
            ly += dp(18);
        }
        canvas.restore();
    }

    private void drawInfoLine(Canvas canvas, String label, String value, float x, float y, float w) {
        RectF r = rect(x, y, x + w, y + dp(36));
        rounded(canvas, r, panel, border, dp(2));
        drawText(canvas, label, r.left + dp(10), r.top + dp(14), 10, muted, false);
        drawTextRight(canvas, value, r.right - dp(10), r.top + dp(24), 11, text, true);
    }

    private void drawLogBox(Canvas canvas, String label, String value, float x, float y, float w, float h) {
        drawText(canvas, label, x, y, 11, muted, false);
        RectF r = rect(x, y + dp(10), x + w, y + h);
        rounded(canvas, r, panel, border, dp(2));
        canvas.save();
        canvas.clipRect(inset(r, dp(8), dp(6)));
        drawText(canvas, value, r.left + dp(10), r.top + dp(24), 10, text, false);
        canvas.restore();
    }

    private void drawVideoPlayer(Canvas canvas, RectF r) {
        rounded(canvas, r, Color.BLACK, Color.rgb(100, 108, 118), 0);
        drawCameraLens(canvas, rect(r.left + dp(60), r.top + dp(36), r.right - dp(60), r.bottom - dp(48)));
        drawText(canvas, "TP-LINK 摄像头", r.left + dp(18), r.top + dp(28), 14, Color.WHITE, true);
        String status = TextUtils.isEmpty(cameraStreamUrl) ? "未配置视频地址，点击右侧“配置摄像头”" : "已配置: " + maskCameraUrl(cameraStreamUrl);
        drawText(canvas, status, r.left + dp(18), r.top + dp(52), 10, Color.rgb(190, 205, 215), false);
        drawText(canvas, CAMERA_PLAYER_VERSION + "；优先使用已配置地址的 /stream2 H.264 RTSP。",
                r.left + dp(18), r.top + dp(74), 10, Color.rgb(150, 170, 190), false);
        drawText(canvas, "如果仍打不开，请拍摄播放页显示的 DESCRIBE/SETUP/PLAY/H264 错误。",
                r.left + dp(18), r.top + dp(94), 10, Color.rgb(150, 170, 190), false);
        clickZones.add(new ClickZone(new RectF(r), new ClickAction() {
            @Override
            public void run() {
                openTpLinkCameraPreview();
            }
        }));
        drawCameraControlBar(canvas, r, false);
    }

    private void drawCameraControlBar(Canvas canvas, RectF r, boolean compact) {
        float barH = compact ? dp(24) : dp(34);
        RectF bar = rect(r.left, r.bottom - barH, r.right, r.bottom);
        fill(canvas, bar.left, bar.top, bar.right, bar.bottom, Color.argb(190, 18, 22, 28));
        drawText(canvas, compact ? "▶  LIVE" : "▶   00:31 / LIVE", bar.left + dp(12), bar.top + barH * 0.64f, compact ? 9 : 11, Color.WHITE, true);

        drawTextRight(canvas, "全屏", bar.right - dp(12), bar.top + barH * 0.64f, compact ? 9 : 11, Color.WHITE, true);

        final RectF fullscreen = compact
                ? rect(bar.right - dp(54), bar.top, bar.right, bar.bottom)
                : rect(bar.right - dp(70), bar.top, bar.right, bar.bottom);
        clickZones.add(new ClickZone(fullscreen, new ClickAction() {
            @Override
            public void run() {
                openTpLinkCameraInternal();
            }
        }));
    }

    private void drawCameraPlayback(Canvas canvas, float x, float y, float w, float h) {
        RectF panel = rect(x, y, x + w, y + h);
        rounded(canvas, panel, Color.WHITE, border, 0);
        drawText(canvas, "<返回实况", panel.left + dp(12), panel.top + dp(20), 10, muted, false);
        clickZones.add(new ClickZone(rect(panel.left + dp(6), panel.top + dp(4), panel.left + dp(82), panel.top + dp(32)), new ClickAction() {
            @Override
            public void run() {
                cameraPlayback = false;
            }
        }));
        float gap = dp(6);
        float thumbW = (panel.width() - dp(36)) / 3f;
        float thumbH = (panel.height() - dp(72)) / 2.5f;
        float yy = panel.top + dp(44);
        if (cameraSnapshots.isEmpty()) {
            drawTextCenter(canvas, "暂无摄像头截图，点击右侧“截图”后会显示在这里",
                    rect(panel.left, panel.top + dp(80), panel.right, panel.top + dp(130)), 13, muted, false);
            drawTextCenter(canvas, "截图会保存到本地回放；下载后进入系统图片目录 ICUCamera",
                    rect(panel.left, panel.top + dp(118), panel.right, panel.top + dp(160)), 11, muted, false);
            return;
        }
        int count = Math.min(6, cameraSnapshots.size());
        for (int i = 0; i < count; i++) {
            final int snapshotIndex = i;
            CameraSnapshot snapshot = cameraSnapshots.get(i);
            int col = i % 3;
            int row = i / 3;
            RectF t = rect(panel.left + dp(12) + col * (thumbW + gap), yy + row * (thumbH + gap), panel.left + dp(12) + col * (thumbW + gap) + thumbW, yy + row * (thumbH + gap) + thumbH);
            boolean selected = selectedCameraSnapshotIndex == i;
            rounded(canvas, t, Color.BLACK, selected ? orange : Color.WHITE, selected ? dp(3) : dp(1));
            RectF imageArea = rect(t.left + dp(2), t.top + dp(2), t.right - dp(2), t.bottom - dp(28));
            Bitmap bitmap = null;
            if (snapshot.cacheFile != null && snapshot.cacheFile.exists()) {
                bitmap = BitmapFactory.decodeFile(snapshot.cacheFile.getAbsolutePath());
            }
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, imageArea, paint);
                bitmap.recycle();
            } else {
                drawCameraLens(canvas, inset(imageArea, dp(12), dp(8)));
            }
            fill(canvas, t.left, t.bottom - dp(28), t.right, t.bottom, Color.argb(210, 18, 22, 28));
            drawText(canvas, snapshot.timeText, t.left + dp(8), t.bottom - dp(13), 8, Color.WHITE, false);
            drawTextRight(canvas, snapshot.downloadUri == null ? "本地" : "已下载", t.right - dp(8), t.bottom - dp(13), 8,
                    snapshot.downloadUri == null ? Color.rgb(210, 220, 230) : green, true);
            clickZones.add(new ClickZone(new RectF(t), new ClickAction() {
                @Override
                public void run() {
                    selectedCameraSnapshotIndex = snapshotIndex;
                }
            }));
        }
        CameraSnapshot selected = selectedCameraSnapshot();
        String selectedText = selected == null ? "未选择截图" : "已选择: " + selected.fileName;
        drawTextCenter(canvas, "共 " + cameraSnapshots.size() + " 张截图    " + selectedText,
                rect(panel.left, panel.bottom - dp(34), panel.right, panel.bottom - dp(12)), 9, muted, false);
    }

    private void drawCameraPreview(Canvas canvas, RectF r, boolean borderOrange) {
        rounded(canvas, r, Color.BLACK, borderOrange ? orange : border, dp(1));
        drawCameraLens(canvas, inset(r, dp(12), dp(12)));
    }

    private void drawCameraLens(Canvas canvas, RectF r) {
        float cx = r.centerX();
        float cy = r.centerY();
        float radius = Math.min(r.width(), r.height()) * 0.45f;
        if (radius <= 0f) {
            return;
        }
        paint.setShader(new RadialGradient(cx, cy, radius, Color.rgb(210, 220, 220), Color.rgb(25, 30, 33), Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);
        stroke(canvas, cx - radius, cy - radius, cx + radius, cy + radius, Color.rgb(70, 78, 82), dp(2));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setColor(Color.rgb(140, 150, 150));
        for (int i = -2; i <= 2; i++) {
            canvas.drawLine(cx - radius * 0.8f, cy + i * radius * 0.22f, cx + radius * 0.65f, cy - i * radius * 0.18f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        fill(canvas, cx - radius * 0.2f, cy - radius * 0.9f, cx + radius * 0.15f, cy + radius * 0.15f, Color.argb(130, 250, 250, 240));
        fill(canvas, cx + radius * 0.25f, cy - radius * 0.5f, cx + radius * 0.78f, cy + radius * 0.3f, Color.argb(95, 250, 250, 240));
    }

    private void drawLightPreview(Canvas canvas, RectF r) {
        RectF inner = inset(r, dp(18), dp(30));
        paint.setShader(new LinearGradient(inner.left, inner.top, inner.right, inner.bottom, Color.rgb(68, 48, 16), Color.rgb(246, 216, 34), Shader.TileMode.CLAMP));
        canvas.drawRect(inner, paint);
        paint.setShader(null);
    }

    /**
     * 实时波形：只用监护仪真实采样。恒定采样说明通道没有信号，
     * 这时改画真实测量趋势；两者都没有就画基线并如实标注，不再编造会动的假曲线。
     */
    private void drawRealtimeWave(Canvas canvas, float left, float baseline, float right, float amp,
                                  int color, List<Integer> waveSamples, List<Integer> trendSamples) {
        if (waveHasSignal(waveSamples)) {
            drawSampleWave(canvas, left, baseline, right, amp, color, waveSamples);
            return;
        }
        if (trendSamples != null && trendSamples.size() >= 2) {
            drawSampleWave(canvas, left, baseline, right, amp, color, trendSamples);
            drawText(canvas, "测量趋势", left, baseline - amp - dp(4), 8, Color.rgb(120, 140, 160), false);
            return;
        }
        drawWaveBaseline(canvas, left, baseline, right, amp, color);
        drawText(canvas, "无信号", left, baseline - amp - dp(4), 8, Color.rgb(150, 138, 138), false);
    }

    private void drawSampleWave(Canvas canvas, float left, float baseline, float right, float amp, int color, List<Integer> samples) {
        drawSmoothSampleWave(canvas, left, baseline, right, amp, color, samples, dp(1.35f));
    }

    private void drawSmoothSampleWave(Canvas canvas, float left, float baseline, float right, float amp,
                                      int color, List<Integer> samples, float strokeWidth) {
        if (samples == null || samples.size() < 2 || right <= left) {
            return;
        }
        int targetCount = Math.max(220, Math.min(720, (int) ((right - left) / 1.15f)));
        // 长会话覆盖完整窗口，不能只画尾段；每桶峰谷按原先后顺序保留 ECG 尖峰。
        List<Integer> drawnSamples = WaveformSampler.downsample(samples, targetCount);
        int count = drawnSamples.size();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            int value = drawnSamples.get(i);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        float range = Math.max(12f, max - min);
        float center = (max + min) / 2f;
        float top = baseline - amp * 1.72f;
        float bottom = baseline + amp * 1.72f;
        float[] xs = new float[count];
        float[] ys = new float[count];
        float lastY = baseline;
        for (int i = 0; i < count; i++) {
            float t = count <= 1 ? 0 : i / (float) (count - 1);
            xs[i] = left + (right - left) * t;
            float normalized = (drawnSamples.get(i) - center) / range;
            float y = baseline - normalized * amp * 1.62f;
            if (i > 0) {
                y = lastY * 0.18f + y * 0.82f;
            }
            ys[i] = clamp(y, top, bottom);
            lastY = ys[i];
        }

        Path path = new Path();
        path.moveTo(xs[0], ys[0]);
        for (int i = 1; i < count - 1; i++) {
            float midX = (xs[i] + xs[i + 1]) * 0.5f;
            float midY = (ys[i] + ys[i + 1]) * 0.5f;
            path.quadTo(xs[i], ys[i], midX, midY);
        }
        if (count > 1) {
            path.lineTo(xs[count - 1], ys[count - 1]);
        }

        canvas.save();
        canvas.clipRect(left, top - strokeWidth * 2f, right, bottom + strokeWidth * 2f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        int glowAlpha = 52;
        paint.setStrokeWidth(strokeWidth * 2.4f);
        paint.setColor(Color.argb(glowAlpha, Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawPath(path, paint);
        paint.setStrokeWidth(strokeWidth);
        paint.setColor(color);
        canvas.drawPath(path, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeJoin(Paint.Join.MITER);
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }

    private void drawMetric(Canvas canvas, String name, String value, String unit, float x, float y, int color) {
        drawText(canvas, name, x, y, 10, color, false);
        drawText(canvas, value, x + dp(54), y, 14, color, true);
        drawText(canvas, unit, x + dp(116), y, 8, color, false);
    }

    private void drawTableRow(Canvas canvas, float x, float y, float w, float h, String[] cells, float[] cols, boolean header) {
        int fill = header ? Color.rgb(234, 238, 242) : Color.WHITE;
        fill(canvas, x, y, x + w, y + h, fill);
        stroke(canvas, x, y, x + w, y + h, border, dp(0.8f));
        float cx = x;
        for (int i = 0; i < cells.length; i++) {
            float cw = w * cols[i];
            stroke(canvas, cx, y, cx + cw, y + h, border, dp(0.8f));
            drawTextCenter(canvas, cells[i], rect(cx, y, cx + cw, y + h), 9, text, header);
            cx += cw;
        }
    }

    private void drawThermalThumb(Canvas canvas, RectF r) {
        rounded(canvas, r, Color.BLACK, Color.WHITE, 0);
        RectF image = inset(r, dp(8), dp(18));
        paint.setShader(new LinearGradient(image.left, image.top, image.right, image.bottom, Color.rgb(20, 40, 130), Color.rgb(255, 60, 60), Shader.TileMode.CLAMP));
        canvas.drawRect(image, paint);
        paint.setShader(null);
        fill(canvas, image.left + image.width() * 0.3f, image.top + dp(8), image.left + image.width() * 0.48f, image.bottom - dp(8), Color.rgb(255, 235, 80));
        fill(canvas, image.left + image.width() * 0.58f, image.top + dp(14), image.left + image.width() * 0.72f, image.bottom - dp(12), Color.rgb(255, 120, 80));
    }

    private void drawControlThumb(Canvas canvas, RectF r) {
        rounded(canvas, r, Color.rgb(15, 24, 38), Color.WHITE, 0);
        drawTextCenter(canvas, "36.5", rect(r.left + dp(10), r.top + dp(20), r.right - dp(10), r.top + dp(60)), 22, Color.rgb(120, 205, 255), true);
        drawTextCenter(canvas, "ICU 监护舱", rect(r.left, r.centerY(), r.right, r.centerY() + dp(28)), 12, Color.WHITE, true);
        for (int i = 0; i < 5; i++) {
            drawCircle(canvas, r.left + dp(25) + i * dp(26), r.bottom - dp(28), dp(8), i % 2 == 0 ? green : orange);
        }
    }

    private void drawDocumentThumb(Canvas canvas, RectF r, int index) {
        rounded(canvas, r, Color.WHITE, Color.WHITE, 0);
        drawText(canvas, index == 2 ? "术后监护说明" : "ICU 使用教程", r.left + dp(14), r.top + dp(24), 11, Color.rgb(70, 90, 120), true);
        for (int i = 0; i < 5; i++) {
            fill(canvas, r.left + dp(16), r.top + dp(48) + i * dp(16), r.right - dp(30), r.top + dp(54) + i * dp(16), Color.rgb(226, 232, 240));
        }
        drawCircle(canvas, r.right - dp(48), r.bottom - dp(38), dp(22), index % 2 == 0 ? Color.rgb(235, 170, 120) : Color.rgb(125, 190, 220));
    }

    private void drawStatusLightDots(Canvas canvas, RectF r) {
        String active = bleManager.getStatusLightColor();
        drawCircle(canvas, r.centerX() - dp(24), r.top + dp(50), dp(8), "red".equals(active) ? red : Color.rgb(235, 176, 176));
        drawCircle(canvas, r.centerX(), r.top + dp(50), dp(8), "yellow".equals(active) ? Color.rgb(248, 210, 42) : Color.rgb(232, 219, 156));
        drawCircle(canvas, r.centerX() + dp(24), r.top + dp(50), dp(8), "green".equals(active) ? green : Color.rgb(178, 215, 174));
    }

    private void drawSearchBox(Canvas canvas, String label, float x, float y, float w, float h, ClickAction action) {
        RectF r = rect(x, y, x + w, y + h);
        rounded(canvas, r, Color.WHITE, Color.TRANSPARENT, dp(8));
        drawText(canvas, label, r.left + dp(14), r.top + h * 0.64f, 14, text, false);
        clickZones.add(new ClickZone(r, action));
    }

    private void drawHeaderButton(Canvas canvas, String label, float x, float y, float w, float h, boolean active, ClickAction action) {
        RectF r = rect(x, y, x + w, y + h);
        rounded(canvas, r, active ? activeBlue : Color.WHITE, Color.TRANSPARENT, dp(4));
        drawTextCenter(canvas, label, r, 14, active ? Color.WHITE : text, false);
        clickZones.add(new ClickZone(r, action));
    }

    private void drawFormField(Canvas canvas, String label, String value, float x, float y, float labelW, float valueW, boolean required) {
        drawText(canvas, (required ? "* " : "") + label, x, y + dp(20), 13, required ? red : text, false);
        RectF box = rect(x + labelW, y, x + labelW + valueW, y + dp(30));
        rounded(canvas, box, Color.WHITE, Color.TRANSPARENT, dp(3));
        drawText(canvas, dash(value), box.left + dp(12), box.top + dp(20), 13, text, false);
    }

    private void drawProgressBar(Canvas canvas, float x, float y, float w, float h, float progress, int color) {
        RectF bg = rect(x, y, x + w, y + h);
        rounded(canvas, bg, Color.rgb(229, 236, 246), Color.TRANSPARENT, h / 2f);
        RectF fg = rect(x, y, x + Math.max(h, w * clamp(progress, 0f, 1f)), y + h);
        rounded(canvas, fg, color, Color.TRANSPARENT, h / 2f);
    }

    private void drawControlIcon(Canvas canvas, RectF r, int index) {
        float cx = r.right - dp(44);
        float cy = r.centerY();
        int color = Color.rgb(55, 148, 242);
        if (index == 5 || index == 7) {
            color = Color.rgb(250, 174, 75);
        }
        drawTextCenter(canvas, controlIconText(index), rect(cx - dp(28), cy - dp(28), cx + dp(28), cy + dp(28)), 30, color, true);
    }

    private String controlIconText(int index) {
        if (index == 0) return "♨";
        if (index == 1) return "O₂";
        if (index == 2) return "▣";
        if (index == 3) return "CO₂";
        if (index == 4) return "☼";
        if (index == 5) return "☀";
        if (index == 6) return "◌";
        if (index == 7) return "☀";
        if (index == 8) return "◎";
        if (index == 9) return "↻";
        if (index == 10) return "◌";
        if (index == 11) return "✣";
        if (index == 12) return "24";
        return "✦";
    }

    private void drawSmallButton(Canvas canvas, String label, float x, float y, float w, float h, boolean active, ClickAction action) {
        RectF r = rect(x, y, x + w, y + h);
        rounded(canvas, r, active ? activeBlue : Color.WHITE, active ? activeBlue : Color.TRANSPARENT, Math.min(dp(6), h / 2f));
        drawTextCenter(canvas, label, r, 10, active ? Color.WHITE : text, false);
        if (action != null) {
            clickZones.add(new ClickZone(r, action));
        }
    }

    private void drawActionButton(Canvas canvas, String label, float x, float y, float w, float h, ClickAction action) {
        RectF r = rect(x, y, x + w, y + h);
        rounded(canvas, r, Color.WHITE, Color.TRANSPARENT, dp(4));
        drawTextCenter(canvas, label, r, 11, text, true);
        clickZones.add(new ClickZone(r, action));
    }

    private void drawToggle(Canvas canvas, float x, float y, boolean on) {
        RectF r = rect(x, y, x + dp(38), y + dp(18));
        rounded(canvas, r, on ? Color.rgb(194, 229, 249) : Color.rgb(204, 214, 226), Color.TRANSPARENT, dp(9));
        drawCircle(canvas, on ? r.right - dp(9) : r.left + dp(9), r.centerY(), dp(7), on ? activeBlue : Color.rgb(150, 162, 176));
    }

    private void rounded(Canvas canvas, RectF r, int fill, int stroke, float radius) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fill);
        canvas.drawRoundRect(r, radius, radius, paint);
        if (stroke != Color.TRANSPARENT) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(stroke);
            canvas.drawRoundRect(r, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void fill(Canvas canvas, float left, float top, float right, float bottom, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawRect(left, top, right, bottom, paint);
    }

    private void stroke(Canvas canvas, float left, float top, float right, float bottom, int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        canvas.drawRect(left, top, right, bottom, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawCircle(Canvas canvas, float cx, float cy, float radius, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawCircle(cx, cy, radius, paint);
    }

    private void drawText(Canvas canvas, String value, float x, float y, float sp, int color, boolean bold) {
        textPaint.setShader(null);
        textPaint.setColor(color);
        textPaint.setTextSize(dp(sp));
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(value, x, y, textPaint);
    }

    private void drawTextRight(Canvas canvas, String value, float x, float y, float sp, int color, boolean bold) {
        textPaint.setShader(null);
        textPaint.setColor(color);
        textPaint.setTextSize(dp(sp));
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(value, x, y, textPaint);
    }

    private void drawTextCenter(Canvas canvas, String value, RectF r, float sp, int color, boolean bold) {
        textPaint.setShader(null);
        textPaint.setColor(color);
        textPaint.setTextSize(dp(sp));
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
        textPaint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float baseline = r.centerY() - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(value, r.centerX(), baseline, textPaint);
    }

    private RectF rect(float left, float top, float right, float bottom) {
        return new RectF(left, top, right, bottom);
    }

    private RectF inset(RectF r, float dx, float dy) {
        return new RectF(r.left + dx, r.top + dy, r.right - dx, r.bottom - dy);
    }

    private String dash(String value) {
        return TextUtils.isEmpty(value) ? "-" : value;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float value) {
        return value * (density == 0 ? getResources().getDisplayMetrics().density : density);
    }
}
