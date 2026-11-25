package com.example.icuapk;

import android.text.TextUtils;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 打印机状态：打印之前先问一次打印机"现在什么状态"。
 *
 * 直连打印以前只看"数据有没有发出去"，打印机缺纸、离线时同样会提示成功，
 * 用户只看到 App 说"打印完成"却等不到纸。这里把打印机的真实状态读回来，
 * 打印结果里一并说明，缺纸、卡纸、离线都能当场看出来。
 */
final class PrinterStatus {

    /** IPP printer-state：3=空闲，4=正在打印，5=已停止。 */
    static final int STATE_IDLE = 3;

    /** 正在打印。 */
    static final int STATE_PROCESSING = 4;

    /** 已停止（缺纸、卡纸等）。 */
    static final int STATE_STOPPED = 5;

    /** 状态查询用较短的超时，别让操作者干等。 */
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private static final int READ_TIMEOUT_MS = 8000;

    /** 打印机资源 URI 的两种写法：带端口、不带端口。 */
    private static final boolean[] URI_WITH_PORT = {true, false};

    /** 上次查通过的 URI 写法，下次先试它，少走一次往返。 */
    private static volatile int preferredUriForm = -1;

    /** 打印机连不上时 reachable=false，error 里是原因。 */
    final boolean reachable;

    /** 连不上时的原因描述。 */
    final String error;

    /** IPP printer-state，连不上时为 -1。 */
    final int state;

    /** printer-state-reasons，多个原因用逗号分隔。 */
    final String reasons;

    /** 排队中的任务数。 */
    final int queuedJobs;

    /** 打印机当前是否接收新任务：false 说明队列积压或设备被暂停。 */
    final boolean acceptingJobs;

    /** 打印机是否上报了队列长度：不回报的机型无法核对任务有没有被消化。 */
    final boolean queueKnown;

    /** PCLm 光栅分辨率：打印机上报的 pclm-source-resolution-supported，0 表示未知。 */
    final int pclmDpi;

    /** PCLm 每条光栅条带的行数：打印机上报的 pclm-strip-height-preferred，0 表示未知。 */
    final int pclmStripHeight;

    /** urf-supported：打印机上报的 URF 能力值（形如 RS600-SRGB24），空字符串表示没上报。 */
    final String urfSupported;

    /** printer-info：打印机型号与能力描述。 */
    final String info;

    /** document-format-supported：打印机支持的文档格式。 */
    final Set<String> formats;

    private PrinterStatus(boolean reachable, String error, int state, String reasons, int queuedJobs, String info,
                          Set<String> formats, boolean acceptingJobs, boolean queueKnown,
                          int pclmDpi, int pclmStripHeight) {
        this(reachable, error, state, reasons, queuedJobs, info, formats, acceptingJobs, queueKnown,
                pclmDpi, pclmStripHeight, "");
    }

    private PrinterStatus(boolean reachable, String error, int state, String reasons, int queuedJobs, String info,
                          Set<String> formats, boolean acceptingJobs, boolean queueKnown,
                          int pclmDpi, int pclmStripHeight, String urfSupported) {
        this.reachable = reachable;
        this.error = error == null ? "" : error;
        this.state = state;
        this.reasons = reasons == null ? "" : reasons;
        this.queuedJobs = queuedJobs;
        this.info = info == null ? "" : info;
        this.formats = formats == null ? new LinkedHashSet<String>() : formats;
        this.acceptingJobs = acceptingJobs;
        this.queueKnown = queueKnown;
        this.pclmDpi = pclmDpi;
        this.pclmStripHeight = pclmStripHeight;
        this.urfSupported = urfSupported == null ? "" : urfSupported;
    }

    /**
     * 查询打印机状态。
     *
     * 查询失败不算致命错误：打印机可能只开放 9100，状态读不到照样能把任务发过去，
     * 所以这里返回 reachable=false 的状态对象，而不是抛异常。
     */
    static PrinterStatus query(String host, int port, String path) {
        if (TextUtils.isEmpty(host)) {
            return new PrinterStatus(false, "打印机地址为空", -1, "", 0, "", new LinkedHashSet<String>(), true, false, 0, 0);
        }
        String[] paths = IppClient.resourcePaths(path);
        int[] order = preferredUriForm >= 0 && preferredUriForm < URI_WITH_PORT.length
                ? new int[]{preferredUriForm, 1 - preferredUriForm}
                : new int[]{0, 1};
        String lastError = "";
        for (int form : order) {
            boolean withPort = URI_WITH_PORT[form];
            for (String candidate : paths) {
                String uri = PrinterEndpoint.printerUri(host, port, candidate, withPort);
                for (int shape = 0; shape < 2; shape++) {
                    try {
                        byte[] head = IppClient.buildGetPrinterAttributesHead(uri, shape == 1);
                        IppClient.Response response = IppClient.send(host, port, candidate, head, null,
                                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
                        if (response.httpCode == 404) {
                            lastError = "IPP 路径不可用: " + candidate;
                            break;                   // 这个资源没有这个操作，换路径
                        }
                        if (response.httpCode < 200 || response.httpCode >= 300) {
                            lastError = "HTTP " + response.httpCode;
                            break;
                        }
                        if (!response.isAccepted()) {
                            lastError = "IPP 状态 0x" + Integer.toHexString(response.ippStatus);
                            continue;                // 换一种请求格式再试
                        }
                        preferredUriForm = form;
                        return fromAttributes(response.attributes);
                    } catch (Exception error) {
                        // 连不上就是连不上，换写法、换路径都没用。
                        return new PrinterStatus(false, describe(error), -1, "", 0, "",
                                new LinkedHashSet<String>(), true, false, 0, 0);
                    }
                }
            }
        }
        return new PrinterStatus(false, lastError, -1, "", 0, "", new LinkedHashSet<String>(), true, false, 0, 0);
    }

    /**
     * 按打印机地址查询状态：地址可以带端口与路径（ip / ip:端口 / ipp://ip:端口/路径）。
     */
    static PrinterStatus query(String address) {
        PrinterEndpoint endpoint = PrinterEndpoint.parse(address);
        if (!endpoint.isValid()) {
            return new PrinterStatus(false, "打印机地址为空", -1, "", 0, "", new LinkedHashSet<String>(), true, false, 0, 0);
        }
        return query(endpoint.host, endpoint.ippPort, endpoint.path);
    }

    /**
     * 打印机状态读不到时的状态对象。
     *
     * 只开放 9100 的地址本来就不该去问 631，用这个对象表示"压根没问"。
     */
    static PrinterStatus unreachable(String reason) {
        return new PrinterStatus(false, reason == null ? "" : reason, -1, "", 0, "",
                new LinkedHashSet<String>(), true, false, 0, 0);
    }

    /** 从 IPP 属性里取出状态、能力。 */
    private static PrinterStatus fromAttributes(Map<String, String> attributes) {
        int state = parseInt(attributes.get("printer-state"), -1);
        String reasons = attributes.get("printer-state-reasons");
        int queued = parseInt(attributes.get("queued-job-count"), 0);
        String info = attributes.get("printer-info");
        Set<String> formats = new LinkedHashSet<>();
        String supported = attributes.get("document-format-supported");
        if (!TextUtils.isEmpty(supported)) {
            for (String item : supported.split(",")) {
                String value = item.trim().toLowerCase(Locale.US);
                if (!value.isEmpty()) {
                    formats.add(value);
                }
            }
        }
        boolean accepting = parseBoolean(attributes.get("printer-is-accepting-jobs"), true);
        boolean queueKnown = attributes.containsKey("queued-job-count");
        int pclmDpi = firstInt(attributes.get("pclm-source-resolution-supported"), 0);
        int pclmStripHeight = firstInt(attributes.get("pclm-strip-height-preferred"), 0);
        String urfSupported = attributes.get("urf-supported");
        return new PrinterStatus(true, "", state, reasons, queued, info, formats, accepting, queueKnown,
                pclmDpi, pclmStripHeight, urfSupported);
    }

    /** PCLm 光栅分辨率；打印机没上报或上报得离谱时用默认值。 */
    int pclmDpiOrDefault() {
        return pclmDpi >= 72 && pclmDpi <= 1200 ? pclmDpi : PclmRasterJob.DEFAULT_DPI;
    }

    /** PCLm 条带高度；打印机没上报或上报得离谱时用默认值。 */
    int pclmStripHeightOrDefault() {
        return pclmStripHeight >= 16 && pclmStripHeight <= 512
                ? pclmStripHeight : PclmRasterJob.DEFAULT_STRIP_HEIGHT;
    }

    /**
     * URF 能力：{分辨率, 位深}。
     *
     * urf-supported 形如 "URF-supported-RS600-SRGB24"，打印机没上报时按默认分辨率 + sRGB 处理。
     */
    int[] urfCapabilities() {
        return UrfRasterJob.parseCapabilities(urfSupported);
    }

    /** 解析 IPP 的 boolean 属性（1/0 或 true/false）；属性缺失时按"接收任务"处理。 */
    private static boolean parseBoolean(String value, boolean fallback) {
        if (TextUtils.isEmpty(value)) {
            return fallback;
        }
        String text = value.trim().toLowerCase(Locale.US);
        if ("1".equals(text) || "true".equals(text)) {
            return true;
        }
        if ("0".equals(text) || "false".equals(text)) {
            return false;
        }
        return fallback;
    }

    /** 打印机是否支持某种文档格式；打印机没上报这个属性时按不支持处理。 */
    boolean supports(String format) {
        return format != null && formats.contains(format.toLowerCase(Locale.US));
    }

    /** 打印结果里的中文状态说明。 */
    String describe() {
        if (!reachable) {
            return "读不到状态(" + error + ")";
        }
        String reason = describeReasons();
        if (!TextUtils.isEmpty(reason)) {
            return reason;
        }
        if (!acceptingJobs) {
            return "不接收新任务（队列积压或设备被暂停）";
        }
        if (state == STATE_PROCESSING) {
            return "打印中";
        }
        if (state == STATE_STOPPED) {
            return "已停止";
        }
        if (state == STATE_IDLE) {
            return "就绪";
        }
        return "状态未知";
    }

    /** 是否属于"先把纸放好才能出纸"的问题。 */
    boolean needsPaper() {
        // M1213nf 这类机型缺纸时报的是 media-needed-error（面板提示"装入纸张 普通纸, A4 或按 [OK] 使用"），
        // 不是 media-empty，两种都要认。
        return hasReason("printer-media-empty") || hasReason("printer-paper-empty")
                || hasReason("media-empty") || hasReason("media-needed");
    }

    /** 是否离线。 */
    boolean isOffline() {
        return hasReason("printer-offline") || hasReason("printer-power-save");
    }

    private boolean hasReason(String keyword) {
        return reasons.toLowerCase(Locale.US).contains(keyword);
    }

    /** 把 IPP 的 printer-state-reasons 翻译成一句中文。 */
    private String describeReasons() {
        String lower = reasons.toLowerCase(Locale.US);
        if (lower.contains("media-needed")) {
            return "缺纸（需要装入 A4 纸或按 [OK] 确认纸张）";
        }
        if (lower.contains("printer-media-empty") || lower.contains("printer-paper-empty")
                || lower.contains("media-empty")) {
            return "缺纸";
        }
        if (lower.contains("printer-media-change") || lower.contains("printer-paper-change")) {
            return "需要更换纸张";
        }
        if (lower.contains("printer-jam") || lower.contains("marking-unit-jam") || lower.contains("printer-cutter-error")) {
            return "卡纸";
        }
        if (lower.contains("printer-door-open")) {
            return "舱门未关";
        }
        if (lower.contains("printer-toner-low") || lower.contains("printer-ink-low") || lower.contains("toner-empty")) {
            return "耗材不足";
        }
        if (lower.contains("printer-offline")) {
            return "离线";
        }
        if (lower.contains("spool-area-full")) {
            return "打印队列已满";
        }
        if (lower.contains("printer-error") || lower.contains("server-error")) {
            return "打印机报错";
        }
        return "";
    }

    /** 打印队列/缓存区是否已经满了——满了任务就会一直排队不执行。 */
    boolean isSpoolFull() {
        return hasReason("spool-area-full");
    }

    /**
     * 发送任务之后的核对结果。
     */
    static final class JobCheck {

        /** 打印机确实开始处理任务了（printer-state 变成"打印中"）。 */
        boolean started;

        /** 任务已经从队列里消化掉（打印完成或被丢弃）。 */
        boolean finished;

        /** 核对结束时队列里的任务数。 */
        int queued;

        /** 核对期间队列达到过的最大长度。 */
        int peak;

        /** 打印机当前不接收新任务。 */
        boolean notAccepting;

        /** 打印队列/缓存区已满。 */
        boolean spoolFull;

        /** 核对时打印机仍然缺纸/需要装纸。 */
        boolean needsPaper;

        /** 核对时读到的状态说明。 */
        String stateNote = "";
    }

    /**
     * 发送任务之后核对打印机：到底有没有真的在打印。
     *
     * 打印机"收下任务"不等于"打印了"：固件会先回成功、再把任务压在队列里不执行
     *（HP LaserJet Pro MFP M1213nf 的 pdl-override=attempted 就是这个脾气，
     * 格式不对也照收不误），这种情况以前会被 App 当成打印成功。
     * 这里发送完立刻回读状态，用队列长度、printer-state、
     * printer-is-accepting-jobs 判断任务到底走没走。
     *
     * @param baselineQueue 发送之前的队列长度
     * @param timeoutMs     最长核对时间
     */
    static JobCheck checkAfterSend(String host, int port, String path, int baselineQueue, int timeoutMs) {
        JobCheck check = new JobCheck();
        check.queued = baselineQueue;
        check.peak = baselineQueue;
        long deadline = System.currentTimeMillis() + Math.max(1000, timeoutMs);
        while (true) {
            PrinterStatus now = query(host, port, path);
            if (!now.reachable) {
                break;                      // 读不到状态就如实说明，不能替打印机下结论
            }
            check.queued = now.queuedJobs;
            if (now.queuedJobs > check.peak) {
                check.peak = now.queuedJobs;
            }
            check.notAccepting = !now.acceptingJobs;
            check.spoolFull = now.isSpoolFull();
            check.needsPaper = now.needsPaper();
            check.stateNote = now.describe();
            if (now.state == STATE_PROCESSING) {
                check.started = true;
                break;                      // 打印机已经动手，判断到位
            }
            if (check.peak > 0 && now.queuedJobs < check.peak) {
                check.finished = true;
                break;                      // 任务离开队列：打印完成或被丢弃
            }
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            sleepQuietly(1000L);
        }
        return check;
    }

    /** 稍等一下再查下一次状态。 */
    private static void sleepQuietly(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static int parseInt(String value, int fallback) {
        if (TextUtils.isEmpty(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /**
     * 取属性里的第一个整数。
     *
     * IPP 的多值属性会被拼成 "300, 600" 或 "600x300"，
     * 直接 Integer.parseInt 会失败，只能自己从头扫第一个数字。
     */
    private static int firstInt(String value, int fallback) {
        if (TextUtils.isEmpty(value)) {
            return fallback;
        }
        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.substring(0, end));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String describe(Throwable throwable) {
        // 原始的 java.net 异常对使用者没有意义，统一翻译成中文原因。
        return PrinterDiagnostics.explain(throwable);
    }
}
