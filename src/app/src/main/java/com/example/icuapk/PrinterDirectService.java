package com.example.icuapk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 直连打印：按打印机地址把报告直接发给网络打印机。
 *
 * 协议按打印机能力挑，而不是写死：
 *  1) 先用 IPP 读一次打印机状态——它支持什么格式、现在缺不缺纸、离不在线；
 *  2) 先送 PDF（矢量，字最清楚）：不少打印机不在 document-format-supported 里申报 PDF，
 *     照着申报结果过滤会把明明能打印的机器判成不能打印；
 *  3) PDF 不收就光栅化，按打印机申报的格式挨个试：JPEG、PWG 标准光栅（IPP Everywhere 机型）、
 *     PCLm（HP LaserJet Pro MFP M1213nf 的原生格式）、URF 通用光栅（AirPrint 机型）；
 *     打印机压根没申报能力时，按 PCLm → PWG → URF → JPEG 的顺序挨个试，拒一种换一种；
 *  4) 都不认就退回 PCL5 光栅；IPP 端口不通再退回 9100 原始端口；
 *  5) 走 9100 时按打印机自己申报的语言挑载荷（PCLm → URF → PCL）：
 *     发了它不认的格式，固件会照单全收却把任务压在队列里不执行，
 *     表面"打印成功"，纸却一直不出——这才是"打印机没反应"的病根；
 *  6) 发送完立刻回读打印机状态核对：打印机"收下任务"不等于"纸出来了"，
 *     固件会先回成功、再把任务压在队列里不执行，这种假成功必须让使用者看见。
 *     IPP 状态读不到（固件的 HTTP 任务卡死）就改看面板文字；
 *  7) 请求已经发出、结果却无法确认时不换协议重发，避免同一份报告打印多张；
 *     全程都不通才算失败，并把原因翻译成能照着做的中文提示。
 *
 * PCLm 要发的是「PDF 语法的分条光栅文档」（见 PclmRasterJob）：
 * 发 PCL 字节或把原 PDF 改名发过去，固件都会照收不误却一张纸都不出。
 * URF 要发的是 Apple Raster 光栅（见 UrfRasterJob）：只声明 image/urf 的机型
 * 收到 PDF 会回 0x040A 拒收，往 9100 写 PCL 也会被固件静默丢弃。
 *
 * PCL5 光栅版面按整页 A4 铺满（210mm × 297mm @300dpi），报告等比缩放后居中放进去，
 * 四周留白直接画进位图：任何机型的不可打印区域都不会切到报告内容，
 * 也不会出现下半截被挤到第二页或多出一张空白页。
 */
final class PrinterDirectService {

    /** 打印结果回调（主线程）。 */
    interface ResultListener {
        void onResult(boolean ok, String message);
    }

    /** 打印机在线但拒绝了任务：与"连不上"区分开，避免盲目换协议重发。 */
    private static final class IppRefusedException extends Exception {
        final int httpCode;
        final int ippStatus;

        IppRefusedException(String message, int httpCode, int ippStatus) {
            super(message);
            this.httpCode = httpCode;
            this.ippStatus = ippStatus;
        }
    }

    /**
     * 已经发出若干页之后失败：不能再换格式重发，
     * 否则同一份报告会被打好几遍。
     */
    private static final class PartialSendException extends Exception {
        PartialSendException(String message) {
            super(message);
        }
    }

    /** 一次发送的结果：成功与否，以及给用户看的提示。 */
    private static final class SendOutcome {
        final boolean ok;
        final String message;

        SendOutcome(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    /**
     * 一次格式尝试的结论。
     *
     * outcome 非空：已经有结论（成功、结果不明或失败），整个流程照它收尾；
     * outcome 为空：这个格式打印机不收，换下一种格式再试；
     * channelDead 为 true：IPP 通道本身不通（连不上、读不回响应），换格式也是白等。
     */
    private static final class FormatResult {
        final SendOutcome outcome;
        final boolean channelDead;

        FormatResult(SendOutcome outcome, boolean channelDead) {
            this.outcome = outcome;
            this.channelDead = channelDead;
        }
    }

    /** IPP 状态 0x040A：client-error-document-format-not-supported，打印机不收这种格式。 */
    private static final int IPP_STATUS_FORMAT_NOT_SUPPORTED = 0x040A;

    /** IPP 状态 0x0400：client-error-bad-request，请求没被打印机解析。 */
    private static final int IPP_STATUS_BAD_REQUEST = 0x0400;

    /** 连接超时与读响应超时（毫秒）。 */
    private static final int CONNECT_TIMEOUT_MS = 8000;

    /** 连接尝试次数：打印机休眠时第一次连接常被拒，隔一下再连就能唤醒。 */
    private static final int CONNECT_ATTEMPTS = 3;

    /** 重试间隔（毫秒）。 */
    private static final long CONNECT_RETRY_DELAY_MS = 1200L;

    private static final int READ_TIMEOUT_MS = 15000;

    /** 文档格式：PDF。 */
    private static final String FORMAT_PDF = "application/pdf";

    /** 文档格式：JPEG 光栅。 */
    private static final String FORMAT_JPEG = "image/jpeg";

    /** 文档格式：HP 的 PCL 原始数据。 */
    private static final String FORMAT_PCL = "application/vnd.hp-PCL";

    /** 文档格式：PWG 标准光栅（IPP Everywhere）。 */
    private static final String FORMAT_PWG = "image/pwg-raster";

    /** 文档格式：PCLm（PCL 6）。HP LaserJet Pro MFP M1213nf 等机型只认这个。 */
    private static final String FORMAT_PCLM = "application/PCLm";

    /** 文档格式：URF 通用光栅（AirPrint 设备常见）。 */
    private static final String FORMAT_URF = "image/urf";

    /** 文档格式：通用 PCL。 */
    private static final String FORMAT_PCL5 = "application/pcl";

    /** 光栅分辨率：300dpi，报告里的中文小字才够清楚。 */
    private static final int RASTER_DPI = 300;

    /**
     * raw 9100 通道兜底用的 PCLm 光栅分辨率。
     *
     * raw 通道没法向固件声明光栅分辨率，固件只会按自己的默认分辨率解释条带，
     * HP LaserJet Pro M1213nf 的默认是 600dpi（PJL INFO CONFIG 报 RESOLUTION = 300 600，
     * IPP 上报的 pclm-source-resolution 也是 600）。
     */
    private static final int RAW_PCLM_DEFAULT_DPI = 600;

    /** JPEG 光栅分辨率：比 PCL 粗，用在明确支持 JPEG 的打印机上。 */
    private static final int JPEG_DPI = 200;

    /** 纸张宽度（毫米）：A4。 */
    private static final float PAGE_WIDTH_MM = 210f;

    /** 纸张高度（毫米）：A4。 */
    private static final float PAGE_HEIGHT_MM = 297f;

    /** 页面白边（毫米）：留白画进位图，报告不会被打印机的不可打印区域切到。 */
    private static final float PAGE_MARGIN_MM = 10f;

    /** 分条渲染的行数：控制光栅化时的内存占用，每条约 2MB。 */
    private static final int STRIP_ROWS = 256;

    /** 灰度阈值：低于该亮度算黑点。 */
    private static final int INK_THRESHOLD = 135000;

    /** 发送任务后核对打印机状态的最长等待时间（毫秒）。 */
    private static final int VERIFY_TIMEOUT_MS = 12000;

    /** 打印过程日志标签：真机上"打印机没反应"时，logcat 里能直接看到 App 判定的真实结果。 */
    private static final String TAG = "IcuPrint";

    private PrinterDirectService() {
    }

    /**
     * 直连打印。耗时操作在后台线程执行，结果回调在主线程。
     *
     * @param copies 打印份数，小于 1 按 1 份处理
     */
    static void print(final Context context, final File pdf, final String address, final int copies,
                      final ResultListener listener) {
        if (pdf == null || !pdf.exists()) {
            finish(listener, false, "打印失败：PDF文件不存在，无法打印");
            return;
        }
        final String target = address == null ? "" : address.trim();
        if (TextUtils.isEmpty(target)) {
            finish(listener, false, "打印失败：未设置打印机地址，请先在打印设置里填写打印机地址");
            return;
        }
        final int total = PrinterSettings.clampCopies(copies);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String message = "已发送到打印机";
                for (int i = 0; i < total; i++) {
                    // 只核对第一份：核对要来回问打印机，份数多时不必每份都等。
                    SendOutcome outcome = sendOnce(pdf, target, i == 0);
                    if (!outcome.ok) {
                        finish(listener, false, outcome.message);
                        return;
                    }
                    if (i == 0) {
                        message = outcome.message;
                    }
                }
                String text = total > 1
                        ? message + "，共提交 " + total + " 份（其余各份只提交、未逐一核对出纸）"
                        : message;
                finish(listener, true, text + "（目标打印机 " + target + "）");
            }
        }).start();
    }

    /**
     * 发一次打印任务：先问状态，再按能力选协议，最后核对打印机到底有没有打印。
     *
     * @param verify 发送后是否回读状态核对出纸（多份打印时只核对第一份）
     */
    private static SendOutcome sendOnce(File pdf, String address, boolean verify) {
        PrinterEndpoint endpoint = PrinterEndpoint.parse(address);
        if (!endpoint.isValid()) {
            return new SendOutcome(false, "打印失败：打印机地址无效：" + address);
        }

        // 打印前先问状态：缺纸、离线时当场告诉用户，别等"打印完成"了才说没纸。
        // 只开放 9100 的地址就别去问 631：问了也只是白等一个连接超时。
        PrinterStatus status = endpoint.allowIpp
                ? PrinterStatus.query(endpoint.host, endpoint.ippPort, endpoint.path)
                : PrinterStatus.unreachable("该地址只走 9100 原始端口");

        if (endpoint.allowIpp) {
            // 打印机缺纸时先说清楚，别再往队列里塞任务：
            // 这种机型会照单全收却不执行（面板提示"装入纸张 普通纸, A4 或按 [OK] 使用"），
            // 每点一次打印就多压一个任务，队列越堆越多，最后纸出来时是一叠重复报告。
            if (status.reachable && status.needsPaper()) {
                return new SendOutcome(false, "打印失败：打印机当前缺纸或需要确认纸张（"
                        + status.describe() + "）；本次没有发送任务，避免打印队列越堆越多。"
                        + "请在打印机里装入 A4 纸（或在面板按 [OK] 确认纸张）后重新打印");
            }
            // 1) 先送 PDF：矢量最清楚。
            //    但打印机如果明确申报了格式清单、清单里又没有 PDF，就别送 PDF：
            //    这类机型会照单全收 IPP 任务却一张纸都不出（假成功），
            //    先送过去只是白白消耗一次任务，还把问题拖到核对阶段才暴露。
            //    打印机没申报这个属性时仍然先试 PDF：不少机型压根不上报它。
            if (pdfDeclaredUsable(status)) {
                FormatResult result = trySendPdf(pdf, endpoint, status, verify);
                if (result.outcome != null) {
                    return result.outcome;
                }
                if (result.channelDead) {
                    // 通道已经断了，换格式也是白搭，直接走 9100 原始端口兜底。
                    return sendRaw(pdf, endpoint, status, verify, "IPP 通道无响应");
                }
            }
            // 2) 光栅化：按打印机申报的格式挨个试，拒一种换一种。
            FormatResult raster = trySendRasterFormats(pdf, endpoint, status, verify);
            if (raster.outcome != null) {
                return raster.outcome;
            }
            if (raster.channelDead) {
                return sendRaw(pdf, endpoint, status, verify, "IPP 通道无响应");
            }
            // 3) 打印机活着但不收上面这些格式：用 PCL 语言再试一次 IPP 任务。
            FormatResult pcl = trySendPclIpp(pdf, endpoint, status, verify);
            if (pcl.outcome != null) {
                return pcl.outcome;
            }
            if (pcl.channelDead) {
                return sendRaw(pdf, endpoint, status, verify, "IPP 通道无响应");
            }
        }

        // 4) 兜底：9100 原始端口。
        return sendRaw(pdf, endpoint, status, verify, "");
    }

    /**
     * 要不要先试 PDF。
     *
     * 打印机申报了 document-format-supported 就按它说的来：清单里没有 PDF 就不送 PDF，
     * 免得机型收下任务却不出纸（假成功）。
     * 没申报（状态查询失败，或者机型不上报这个属性）时仍然允许先试一次 PDF。
     */
    private static boolean pdfDeclaredUsable(PrinterStatus status) {
        if (!status.reachable || status.formats.isEmpty()) {
            return true;
        }
        return status.supports(FORMAT_PDF);
    }

    /**
     * 送 PDF。
     *
     * @return 成功或结果不明时给出结论；格式被拒时结论为空，交给下一种格式接手
     */
    private static FormatResult trySendPdf(File pdf, PrinterEndpoint endpoint, PrinterStatus status,
                                           boolean verify) {
        byte[] pdfBytes;
        try {
            pdfBytes = readAllBytes(pdf);
        } catch (IOException exception) {
            // 读不出 PDF 就退到光栅方案。
            return new FormatResult(null, false);
        }
        Exception error = sendIpp(endpoint, FORMAT_PDF, pdfBytes, pdf.getName(), 0, null);
        if (error == null) {
            return new FormatResult(finishSend(endpoint, status, "已发送 PDF 到打印机", verify), false);
        }
        if (error instanceof IppClient.UncertainOutcomeException) {
            return new FormatResult(uncertainOutcome(error), false);
        }
        return new FormatResult(null, isChannelDead(error));
    }

    /** 送 JPEG 光栅：逐页一个 IPP 任务。 */
    private static FormatResult trySendJpeg(File pdf, PrinterEndpoint endpoint, PrinterStatus status,
                                            boolean verify) {
        try {
            sendJpegRaster(pdf, endpoint);
            return new FormatResult(finishSend(endpoint, status, "已通过 JPEG 光栅发送到打印机", verify), false);
        } catch (PartialSendException partial) {
            return new FormatResult(new SendOutcome(false, "打印失败：" + partial.getMessage()
                    + "；为避免重复打印，本次没有换其它格式重发"), false);
        } catch (Exception error) {
            if (error instanceof IppClient.UncertainOutcomeException) {
                return new FormatResult(uncertainOutcome(error), false);
            }
            return new FormatResult(null, isChannelDead(error));
        }
    }

    /**
     * 送 PCLm 光栅。
     *
     * 分辨率与条带高度按打印机上报的 pclm-source-resolution-supported、
     * pclm-strip-height-preferred 取；每一页一个 IPP 任务。
     */
    private static FormatResult trySendPclm(File pdf, PrinterEndpoint endpoint, PrinterStatus status,
                                             boolean verify) {
        try {
            int dpi = status.pclmDpiOrDefault();
            int stripHeight = status.pclmStripHeightOrDefault();
            int pages = PclmRasterJob.pageCount(pdf);
            int sent = 0;
            for (int page = 0; page < pages; page++) {
                byte[] job = PclmRasterJob.buildPage(pdf, page, dpi, stripHeight);
                int[] size = PclmRasterJob.pageSize(pdf, page);
                Exception error = sendIpp(endpoint, FORMAT_PCLM, job, pdf.getName(), dpi,
                        PclmRasterJob.mediaName(size[0], size[1]));
                if (error != null) {
                    if (error instanceof IppClient.UncertainOutcomeException) {
                        return new FormatResult(uncertainOutcome(error), false);
                    }
                    if (sent > 0) {
                        return new FormatResult(new SendOutcome(false, "打印失败：PCLm 光栅第 " + (page + 1)
                                + " 页被打印机拒绝（此前已发出 " + sent + " 页）；"
                                + "为避免重复打印，本次没有换其它格式重发"), false);
                    }
                    // 打印机不收 PCLm，交给下一种格式
                    return new FormatResult(null, isChannelDead(error));
                }
                sent++;
            }
            return new FormatResult(finishSend(endpoint, status, "已通过PCLm光栅提交给打印机", verify), false);
        } catch (Exception error) {
            Log.d(TAG, "PCLm 光栅失败: " + describe(error));
            return new FormatResult(null, false);
        }
    }

    /** 送 PWG 标准光栅：IPP Everywhere 机型只认 image/pwg-raster。 */
    private static FormatResult trySendPwg(File pdf, PrinterEndpoint endpoint, PrinterStatus status,
                                            boolean verify) {
        try {
            int pages = PwgRasterJob.pageCount(pdf);
            int sent = 0;
            for (int page = 0; page < pages; page++) {
                byte[] job = PwgRasterJob.buildPage(pdf, page, PwgRasterJob.DEFAULT_DPI);
                Exception error = sendIpp(endpoint, FORMAT_PWG, job, pdf.getName(), 0, null);
                if (error != null) {
                    if (error instanceof IppClient.UncertainOutcomeException) {
                        return new FormatResult(uncertainOutcome(error), false);
                    }
                    if (sent > 0) {
                        return new FormatResult(new SendOutcome(false, "打印失败：PWG 光栅第 " + (page + 1)
                                + " 页被打印机拒绝（此前已发出 " + sent + " 页）；"
                                + "为避免重复打印，本次没有换其它格式重发"), false);
                    }
                    return new FormatResult(null, isChannelDead(error));
                }
                sent++;
            }
            return new FormatResult(finishSend(endpoint, status, "已通过PWG光栅提交给打印机", verify), false);
        } catch (Exception error) {
            Log.d(TAG, "PWG 光栅失败: " + describe(error));
            return new FormatResult(null, false);
        }
    }

    /** 送 URF 通用光栅：只声明 image/urf 的 AirPrint 机型。 */
    private static FormatResult trySendUrf(File pdf, PrinterEndpoint endpoint, PrinterStatus status,
                                            boolean verify) {
        try {
            int[] capabilities = status.urfCapabilities();
            int dpi = capabilities[0];
            int bitsPerPixel = capabilities[1];
            int pages = UrfRasterJob.pageCount(pdf);
            int sent = 0;
            for (int page = 0; page < pages; page++) {
                byte[] job = UrfRasterJob.buildPage(pdf, page, dpi, bitsPerPixel);
                Exception error = sendIpp(endpoint, FORMAT_URF, job, pdf.getName(), 0, null);
                if (error != null) {
                    if (error instanceof IppClient.UncertainOutcomeException) {
                        return new FormatResult(uncertainOutcome(error), false);
                    }
                    if (sent > 0) {
                        return new FormatResult(new SendOutcome(false, "打印失败：URF 光栅第 " + (page + 1)
                                + " 页被打印机拒绝（此前已发出 " + sent + " 页）；"
                                + "为避免重复打印，本次没有换其它格式重发"), false);
                    }
                    return new FormatResult(null, isChannelDead(error));
                }
                sent++;
            }
            return new FormatResult(finishSend(endpoint, status, "已通过URF光栅提交给打印机", verify), false);
        } catch (Exception error) {
            Log.d(TAG, "URF 光栅失败: " + describe(error));
            return new FormatResult(null, false);
        }
    }

    /**
     * 按打印机能力依次尝试光栅格式。
     *
     * 打印机报了 document-format-supported 就按它申报的顺序试；
     * 没报（状态查询失败，或者机型干脆不上报这个属性）也不能放弃：
     * 按 PCLm → PWG → URF → JPEG 挨个试，被拒就换下一种，总有一种是它认的。
     * 这三种原生光栅都比 JPEG 清楚，JPEG 只当最后的通用兜底。
     */
    private static FormatResult trySendRasterFormats(File pdf, PrinterEndpoint endpoint,
                                                     PrinterStatus status, boolean verify) {
        List<String> order = new ArrayList<String>();
        if (status.reachable && !status.formats.isEmpty()) {
            if (status.supports(FORMAT_JPEG)) {
                order.add(FORMAT_JPEG);
            }
            if (status.supports(FORMAT_PWG)) {
                order.add(FORMAT_PWG);
            }
            if (status.supports(FORMAT_PCLM)) {
                order.add(FORMAT_PCLM);
            }
            if (status.supports(FORMAT_URF)) {
                order.add(FORMAT_URF);
            }
        } else {
            order.add(FORMAT_PCLM);
            order.add(FORMAT_PWG);
            order.add(FORMAT_URF);
            order.add(FORMAT_JPEG);
        }
        for (String format : order) {
            FormatResult result = sendRasterFormat(pdf, endpoint, status, verify, format);
            if (result.outcome != null || result.channelDead) {
                return result;
            }
        }
        return new FormatResult(null, false);
    }

    /** 按格式名分派到对应的光栅实现。 */
    private static FormatResult sendRasterFormat(File pdf, PrinterEndpoint endpoint, PrinterStatus status,
                                                  boolean verify, String format) {
        if (FORMAT_JPEG.equals(format)) {
            return trySendJpeg(pdf, endpoint, status, verify);
        }
        if (FORMAT_PWG.equals(format)) {
            return trySendPwg(pdf, endpoint, status, verify);
        }
        if (FORMAT_PCLM.equals(format)) {
            return trySendPclm(pdf, endpoint, status, verify);
        }
        if (FORMAT_URF.equals(format)) {
            return trySendUrf(pdf, endpoint, status, verify);
        }
        return new FormatResult(null, false);
    }

    /**
     * IPP 通道本身是不是不通。
     *
     * 打印机明确回了 IPP 状态码，说明它活着，只是不收这种格式，换下一种格式就行；
     * 连接失败、读不回响应，说明通道本身有问题，再换格式也是白等。
     */
    private static boolean isChannelDead(Exception error) {
        return !(error instanceof IppRefusedException);
    }

    /**
     * 用 PCL 语言再试一次 IPP 任务。
     *
     * 有些机型只认 application/pcl（PCL5 光栅），PDF 与各种光栅格式一概不收。
     *
     * @return 成功或结果不明时给出结论；格式被拒时结论为空，交给原始端口接手
     */
    private static FormatResult trySendPclIpp(File pdf, PrinterEndpoint endpoint, PrinterStatus status,
                                              boolean verify) {
        byte[] job;
        try {
            job = buildPcl5RasterJob(pdf);
        } catch (Exception error) {
            Log.d(TAG, "PCL 光栅生成失败: " + describe(error));
            return new FormatResult(null, false);
        }
        Exception lastError = null;
        // 打印机不认这种格式时，换一种 PCL 语言再试一次。
        for (String format : new String[]{FORMAT_PCL, FORMAT_PCL5}) {
            Exception error = sendIpp(endpoint, format, job, pdf.getName(), 0, null);
            if (error == null) {
                return new FormatResult(finishSend(endpoint, status, "已通过PCL光栅提交给打印机", verify), false);
            }
            if (error instanceof IppClient.UncertainOutcomeException) {
                return new FormatResult(uncertainOutcome(error), false);
            }
            lastError = error;
            if (!isFormatRefused(error)) {
                break;              // 通道本身不通，换格式也没用
            }
        }
        return new FormatResult(null, lastError != null && isChannelDead(lastError));
    }

    /**
     * 9100 原始端口兜底。
     *
     * IPP(631) 与 Web(80) 都跑在固件的 HTTP 任务里，队列一旦积压就整条卡死；
     * PJL(9100) 走另一条解释器，机器卡住时往往还活着。
     * 所以 IPP 走不通时改从 9100 送，并且按打印机自己申报的语言挑载荷
     * （PCLm → URF → PCL）：发了它不认的格式，固件会照单全收却把任务压在队列里
     * 不执行——表面"打印成功"，纸却一直不出。
     */
    private static SendOutcome sendRaw(File pdf, PrinterEndpoint endpoint, PrinterStatus status,
                                        boolean verify, String ippError) {
        if (!endpoint.allowRaw) {
            return new SendOutcome(false, "打印失败："
                    + (ippError.isEmpty() ? "打印机拒绝了打印任务" : ippError)
                    + "。" + PrinterDiagnostics.advice(endpoint.host));
        }
        PrinterPjl.Info info = PrinterPjl.query(endpoint.host);
        if (!info.reachable) {
            return new SendOutcome(false, "打印失败："
                    + (ippError.isEmpty() ? "IPP 通道不可用" : ippError)
                    + "，9100 原始端口也连不上（" + info.error + "）。"
                    + PrinterDiagnostics.advice(endpoint.host));
        }
        // 发送前先记下面板状态：打印时面板会从"就绪"变成"正在初始化"，
        // 拿发送前后对比，才能确认打印机是真的动起来了，而不是只把数据收下。
        PrinterPjl.Status before = PrinterPjl.status(endpoint.host);
        try {
            String language = pickRawLanguage(info);
            if ("PCLm".equals(language)) {
                int dpi = sendRawPclm(pdf, endpoint, status, info);
                return finishRawSend(endpoint, status, "已通过 9100 端口发送 PCLm 光栅（"
                        + dpi + "dpi）", info, before, verify);
            }
            if ("URF".equals(language)) {
                sendRawUrf(pdf, endpoint, status);
                return finishRawSend(endpoint, status, "已通过 9100 端口发送 URF 光栅", info, before, verify);
            }
            byte[] job = buildPcl5RasterJob(pdf);
            sendRawPcl(endpoint.host, job);
            return finishRawSend(endpoint, status, "已通过 9100 端口发送 PCL 光栅", info, before, verify);
        } catch (Exception error) {
            // 连不上打印机时要直说是"网络/地址"问题，而不是"打印机拒绝了任务"。
            if (PrinterDiagnostics.isConnectionFailure(error)) {
                return new SendOutcome(false, "打印失败：" + describe(error) + "。"
                        + PrinterDiagnostics.advice(endpoint.host));
            }
            return new SendOutcome(false, "打印失败：" + describe(error));
        }
    }

    /** 原始端口用哪种语言：按打印机申报的来，它没申报就按兼容性最好的 PCL。 */
    private static String pickRawLanguage(PrinterPjl.Info info) {
        if (info.supports("PCLm")) {
            return "PCLm";
        }
        if (info.supports("URF")) {
            return "URF";
        }
        return "PCL";
    }

    /**
     * 逐页发 PCLm：PCLm 一页一个文档，一页一个 PJL 任务。
     *
     * @return 实际光栅化的分辨率
     */
    private static int sendRawPclm(File pdf, PrinterEndpoint endpoint, PrinterStatus status,
                                   PrinterPjl.Info info) throws IOException {
        int dpi = pickRawPclmDpi(status, info);
        int stripHeight = status.pclmStripHeightOrDefault();
        int pages = PclmRasterJob.pageCount(pdf);
        for (int page = 0; page < pages; page++) {
            byte[] document = PclmRasterJob.buildPage(pdf, page, dpi, stripHeight);
            sendRawPcl(endpoint.host, pjlJob("PCLm", document, dpi));
        }
        return dpi;
    }

    /** 逐页发 URF 光栅。 */
    private static void sendRawUrf(File pdf, PrinterEndpoint endpoint, PrinterStatus status)
            throws IOException {
        int[] capabilities = status.urfCapabilities();
        int pages = UrfRasterJob.pageCount(pdf);
        for (int page = 0; page < pages; page++) {
            byte[] document = UrfRasterJob.buildPage(pdf, page, capabilities[0], capabilities[1]);
            sendRawPcl(endpoint.host, pjlJob("URF", document, 0));
        }
    }

    /**
     * raw 9100 通道的 PCLm 光栅分辨率：必须与固件默认的光栅分辨率一致。
     *
     * IPP 通道可以用 pclm-source-resolution 属性声明分辨率，raw 通道没有这个声明手段，
     * 固件只能按自己的默认分辨率解释条带。按 300dpi 光栅化、固件按 600dpi 解释时，
     * 内容会缩到页面的四分之一（实测如此）。所以这里取打印机申报的最大光栅分辨率，
     * 与固件默认一致；600dpi 及以下优先，避免光栅数据过大拖慢打印。
     */
    private static int pickRawPclmDpi(PrinterStatus status, PrinterPjl.Info info) {
        int largest = 0;
        int best = 0;
        if (status.pclmDpi >= 300 && status.pclmDpi <= 1200) {
            largest = status.pclmDpi;
        }
        for (int candidate : info.resolutions) {
            if (candidate < 300 || candidate > 1200) {
                continue;
            }
            if (candidate > largest) {
                largest = candidate;
            }
            if (candidate <= 600 && candidate > best) {
                best = candidate;
            }
        }
        if (best > 0) {
            return best;
        }
        return largest > 0 ? largest : RAW_PCLM_DEFAULT_DPI;
    }

    /**
     * 把一份页面数据包成 PJL 任务：切语言 → 送数据 → 结束任务。
     *
     * @param resolution 光栅分辨率，>0 时在任务里声明给固件。raw 通道的 PCLm 必须声明，
     *                   否则固件按默认分辨率解释条带，内容会被缩到页面的四分之一。
     */
    private static byte[] pjlJob(String language, byte[] payload, int resolution) {
        ByteArrayOutputStream job = new ByteArrayOutputStream(payload.length + 64);
        try {
            job.write("\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=".getBytes("US-ASCII"));
            job.write(language.getBytes("US-ASCII"));
            job.write("\r\n".getBytes("US-ASCII"));
            if (resolution > 0) {
                job.write(("@PJL SET RESOLUTION=" + resolution + "\r\n").getBytes("US-ASCII"));
            }
        } catch (IOException ignored) {
            // ByteArrayOutputStream 不会真的抛异常
        }
        job.write(payload, 0, payload.length);
        try {
            job.write("\u001B%-12345X".getBytes("US-ASCII"));
        } catch (IOException ignored) {
        }
        return job.toByteArray();
    }

    /**
     * 任务是否被受理无法确认：不能换协议重发，只能如实告诉使用者。
     */
    private static SendOutcome uncertainOutcome(Exception error) {
        return new SendOutcome(false, "打印未确认：打印数据已发出，但打印机响应无法确认（" + describe(error)
                + "）。请先核对是否出纸；为避免重复打印，本次未自动重发");
    }
    /**
     * 发送之后核对打印机：到底有没有真的在打印。
     *
     * "数据发出去"不等于"纸出来了"：固件会先回成功、再把任务压在队列里不执行，
     * 这种假成功必须让使用者看见。这里回读打印机状态，
     * 按队列长度、printer-state、printer-is-accepting-jobs 判断真实结果。
     */
    private static SendOutcome finishSend(PrinterEndpoint endpoint, PrinterStatus before, String okText,
                                           boolean verify) {
        if (!verify) {
            return new SendOutcome(true, "打印完成：" + okText + "（未逐份核对出纸）");
        }
        int baseline = before.reachable ? before.queuedJobs : 0;
        PrinterStatus.JobCheck check = PrinterStatus.checkAfterSend(endpoint.host, endpoint.ippPort,
                endpoint.path, baseline, VERIFY_TIMEOUT_MS);
        if (check.finished) {
            return new SendOutcome(true, "打印完成：" + okText + "，打印机已执行（" + check.stateNote + "）");
        }
        if (check.started) {
            return new SendOutcome(true, "打印完成：" + okText + "，打印机正在打印（" + check.stateNote + "）");
        }
        if (check.needsPaper) {
            return new SendOutcome(false, "打印未出纸：" + okText + "，打印机当前缺纸或需要确认纸张（"
                    + check.stateNote + "）；任务已留在打印队列里，装纸并在面板按 [OK] 确认后才会出纸，"
                    + "队列里堆积的任务可以在打印机面板上取消");
        }
        if (check.spoolFull) {
            return new SendOutcome(false, "打印失败：" + okText + "，但打印机打印队列已满，任务没有执行；"
                    + "请先在打印机面板清空队列或重启打印机，再重新打印");
        }
        if (check.notAccepting) {
            return new SendOutcome(false, "打印失败：" + okText + "，但打印机当前不接收新任务（队列里还有 " + check.queued
                    + " 个任务没执行）；请先清空队列或重启打印机，再重新打印");
        }
        if (!before.queueKnown) {
            // 打印机不回报队列长度，也不回报状态变化：没法证明纸已经出来。
            // 这种情况下不能报"打印完成"，否则又是一次假成功。
            return new SendOutcome(true, "打印任务已受理（该机型不回报队列信息，无法核对是否出纸，请核对纸张）："
                    + okText);
        }
        return new SendOutcome(false, "打印失败：" + okText + "，但打印机没有开始打印，未确认出纸；"
                + "请核对纸张、耗材与打印队列。" + PrinterDiagnostics.advice(endpoint.host));
    }

    /**
     * 原始端口发送之后的核对。
     *
     * IPP 状态读得到时就用它核对队列与 printer-state；
     * 读不到（固件的 HTTP 任务卡死）就退回看面板：至少能知道打印机认了任务、在不在打。
     */
    private static SendOutcome finishRawSend(PrinterEndpoint endpoint, PrinterStatus status, String okText,
                                              PrinterPjl.Info info, PrinterPjl.Status before, boolean verify) {
        if (status.reachable) {
            return finishSend(endpoint, status, okText, verify);
        }
        if (!verify) {
            return new SendOutcome(true, "打印任务已提交：" + okText + "（未逐份核对出纸）");
        }
        PrinterPjl.Status panel = waitForPanelPrintState(endpoint.host, before, VERIFY_TIMEOUT_MS);
        if (panel != null && panel.isPrinting()) {
            return new SendOutcome(true, "打印任务已提交：" + okText
                    + "，打印机正在打印（" + panel.display.trim() + "）");
        }
        if (panel != null && before != null && panel.reachable && before.reachable
                && !panel.display.equals(before.display)) {
            return new SendOutcome(true, "打印任务已提交：" + okText + "，打印机状态已变化（"
                    + before.display.trim() + " → " + panel.display.trim() + "），请核对纸张");
        }
        String panelNote = panel != null && panel.reachable
                ? "打印机面板显示「" + panel.display.trim() + "」"
                : "打印机面板没有回应";
        return new SendOutcome(true, "打印任务已提交：" + okText + "（" + panelNote
                + "，请核对纸张；若长时间不出纸，请给打印机断电 30 秒重启，清掉积压任务后重试）");
    }

    /** 盯着面板，等打印机真的开始打印。 */
    private static PrinterPjl.Status waitForPanelPrintState(String host, PrinterPjl.Status before, int timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(1000, timeoutMs);
        PrinterPjl.Status last = null;
        while (true) {
            last = PrinterPjl.status(host);
            if (last != null && last.isPrinting()) {
                return last;
            }
            if (last != null && before != null && last.reachable && before.reachable
                    && !last.display.equals(before.display)) {
                return last;      // 面板文字变了：打印机确实在处理
            }
            if (System.currentTimeMillis() >= deadline) {
                return last;
            }
            sleepQuietly(700L);
        }
    }

    /** 是不是"打印机不收这种格式"——只有这种情况才值得换一种格式再试。 */
    private static boolean isFormatRefused(Exception error) {
        return error instanceof IppRefusedException
                && ((IppRefusedException) error).ippStatus == IPP_STATUS_FORMAT_NOT_SUPPORTED;
    }

    /**
     * 用 IPP Print-Job 把打印数据发给打印机。
     *
     * 依次尝试：资源路径的候选 × 资源 URI 的两种写法（带端口 / 不带端口）。
     * HP 固件只认自己配置里的那一种 URI 写法，写错会回 0x040D，所以两种都要试。
     *
     * @return null 表示已经发出去；否则返回失败原因，交给调用方判断要不要换格式
     */
    private static Exception sendIpp(PrinterEndpoint endpoint, String documentFormat, byte[] data,
            String jobName, int pclmDpi, String mediaName) {
        Exception lastError = null;
        boolean channelDead = false;
        for (String path : IppClient.resourcePaths(endpoint.path)) {
            for (boolean withPort : new boolean[]{true, false}) {
                String uri = PrinterEndpoint.printerUri(endpoint.host, endpoint.ippPort, path, withPort);
                try {
                    sendIppOnce(endpoint.host, endpoint.ippPort, path, uri, documentFormat, data, jobName,
                            pclmDpi, mediaName);
                    return null;
                } catch (IppRefusedException refused) {
                    lastError = refused;
                    if (refused.httpCode == 404) {
                        break;      // 这个资源不提供该操作，换下一个路径
                    }
                    if (refused.ippStatus == IPP_STATUS_FORMAT_NOT_SUPPORTED) {
                        // 打印机已经看懂并拒收了这份数据：换路径、换 URI 写法都不会变，
                        // 只有换一种文档格式才有用，直接把结论交回去。
                        return refused;
                    }
                } catch (IppClient.UncertainOutcomeException uncertain) {
                    // 任务可能已经被受理：不能再发一次，否则同一份报告会打印多张。
                    return uncertain;
                } catch (Exception error) {
                    lastError = error;
                    channelDead = true;
                    break;          // 连不上，URI 写法再怎么换也没用
                }
            }
            if (channelDead) {
                break;              // 主机连不上或读不回响应，换路径也是白等
            }
        }
        return lastError != null ? lastError : new IOException("IPP 路径不可用");
    }

    /**
     * 单个路径的 IPP 打印请求。
     *
     * 请求被判 bad request（0x0400）时，说明打印机的属性长度编码和 IPP 1.1 不一样，
     * 换成 IPP 2.0 的 4 字节长度再试一次，避免整份报告打不出来。
     */
    private static void sendIppOnce(String host, int port, String path, String printerUri, String documentFormat,
                                    byte[] data, String jobName, int pclmDpi, String mediaName) throws Exception {
        try {
            sendIppRequest(host, port, path, printerUri, documentFormat, data, jobName, false, pclmDpi, mediaName);
        } catch (IppRefusedException refused) {
            if (refused.ippStatus != IPP_STATUS_BAD_REQUEST) {
                throw refused;
            }
            sendIppRequest(host, port, path, printerUri, documentFormat, data, jobName, true, pclmDpi, mediaName);
        }
    }

    /** 真正的 IPP 请求：发出去并解析打印机返回的状态码。 */
    private static void sendIppRequest(String host, int port, String path, String printerUri, String documentFormat,
                                       byte[] data, String jobName, boolean ipp2x, int pclmDpi,
                                       String mediaName) throws Exception {
        byte[] head = IppClient.buildPrintJobHead(printerUri, documentFormat, jobName, ipp2x, pclmDpi, mediaName);
        IppClient.Response response = IppClient.send(host, port, path, head, data,
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, CONNECT_ATTEMPTS);
        if (response.httpCode < 200 || response.httpCode >= 300) {
            throw new IppRefusedException("HTTP " + response.httpCode + " @ " + path, response.httpCode, -1);
        }
        if (!response.isAccepted()) {
            throw new IppRefusedException("IPP 状态 0x" + Integer.toHexString(response.ippStatus)
                    + (TextUtils.isEmpty(response.statusMessage) ? "" : "（" + response.statusMessage + "）"),
                    response.httpCode, response.ippStatus);
        }
    }

    /**
     * 走 9100 原始端口发 PCL 光栅任务。
     *
     * 发完主动断开写方向：JetDirect 通道要等对端关闭连接才会把这一单真正交给打印机。
     */
    private static void sendRawPcl(String host, byte[] job) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt < CONNECT_ATTEMPTS; attempt++) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, PrinterEndpoint.RAW_PORT), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(READ_TIMEOUT_MS);
                OutputStream out = socket.getOutputStream();
                out.write(job);
                out.flush();
                socket.shutdownOutput();
                return;
            } catch (IOException error) {
                lastError = error;
            } finally {
                closeQuietly(socket);
            }
            sleepQuietly(CONNECT_RETRY_DELAY_MS);
        }
        throw lastError != null ? lastError : new IOException("无法连接打印机 9100 端口");
    }

    /** 稍等一会儿再继续，等打印机从休眠里醒过来。 */
    private static void sleepQuietly(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 整份 PDF → PCL5 光栅任务。
     *
     * 版面按 A4 可打印区域保守取值：内容等比缩到 185mm × 245mm 居中摆放，
     * 无论打印机的默认上下边距是多少，报告都完整落在同一页，
     * 不会出现"下半截被挤到第二页"或者多出一张空白页。
     */
    private static byte[] buildPcl5RasterJob(File pdf) throws IOException {
        ByteArrayOutputStream job = new ByteArrayOutputStream();
        job.write("\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n".getBytes("US-ASCII"));
        job.write(new byte[]{0x1B, 'E'});                    // ESC E 复位
        job.write("\u001B&l26A".getBytes("US-ASCII"));       // A4 纸
        job.write("\u001B&l0O".getBytes("US-ASCII"));        // 纵向
        // 不再设置打印机边距：光栅从页面左上角开始，留白由位图自己带，
        // 免得不同机型的默认边距把报告切掉一块。
        writePages(job, pdf);
        job.write(new byte[]{0x1B, 'E'});                    // ESC E 复位收尾
        job.write("\u001B%-12345X".getBytes("US-ASCII"));    // UEL 任务结束
        return job.toByteArray();
    }

    /** 把 PDF 每一页写成光栅数据。 */
    private static void writePages(ByteArrayOutputStream job, File pdf) throws IOException {
        ParcelFileDescriptor descriptor =
                ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            PdfRenderer renderer = new PdfRenderer(descriptor);
            try {
                int pages = renderer.getPageCount();
                for (int i = 0; i < pages; i++) {
                    PdfRenderer.Page page = renderer.openPage(i);
                    try {
                        writeRasterPage(job, page);
                    } finally {
                        page.close();
                    }
                }
            } finally {
                renderer.close();
            }
        } finally {
            closeQuietly(descriptor);
        }
    }

    /**
     * 把一页 PDF 写成 PCL 光栅行。
     *
     * 整张 A4 画布铺满：报告等比缩放后居中放进画布，四周留白直接画进位图，
     * 光栅从页面左上角开始（忽略打印机默认边距），任何机型都切不到内容。
     *
     * 逐条渲染：整页位图在 300dpi 下要 20MB 以上，分成 256 行一条，
     * 峰值内存只有 2MB 出头，低配设备也不会因为内存不够把任务干掉。
     */
    private static void writeRasterPage(ByteArrayOutputStream job, PdfRenderer.Page page) throws IOException {
        int canvasWidthPx = mmToPx(PAGE_WIDTH_MM);
        int canvasHeightPx = mmToPx(PAGE_HEIGHT_MM);
        int contentWidthPx = mmToPx(PAGE_WIDTH_MM - 2 * PAGE_MARGIN_MM);
        int contentHeightPx = mmToPx(PAGE_HEIGHT_MM - 2 * PAGE_MARGIN_MM);
        // PdfRenderer 的 transform 是相对 1/72 英寸的缩放系数，
        // 页面尺寸要按"点"参与计算，不能先换算成像素再当分母，否则会多缩一次。
        float scale = Math.min(contentWidthPx / (float) page.getWidth(),
                contentHeightPx / (float) page.getHeight());
        int drawWidthPx = Math.max(1, Math.round(page.getWidth() * scale));
        int drawHeightPx = Math.max(1, Math.round(page.getHeight() * scale));
        int offsetX = (canvasWidthPx - drawWidthPx) / 2;
        int offsetY = (canvasHeightPx - drawHeightPx) / 2;

        // 光栅分辨率：PCL5 用 ESC*t 声明。
        job.write(("\u001B*t" + RASTER_DPI + "R").getBytes("US-ASCII"));
        job.write("\u001B*r0F".getBytes("US-ASCII"));                      // 光栅起点：页面左上角
        job.write(("\u001B*b" + canvasHeightPx + "R").getBytes("US-ASCII"));// 光栅高度（行数）

        int rowBytes = (canvasWidthPx + 7) / 8;
        byte[] row = new byte[rowBytes];
        int[] pixels = new int[canvasWidthPx];
        for (int stripTop = 0; stripTop < canvasHeightPx; stripTop += STRIP_ROWS) {
            int stripRows = Math.min(STRIP_ROWS, canvasHeightPx - stripTop);
            Bitmap strip = Bitmap.createBitmap(canvasWidthPx, stripRows, Bitmap.Config.ARGB_8888);
            try {
                strip.eraseColor(Color.WHITE);
                Matrix matrix = new Matrix();
                matrix.setScale(scale, scale);
                // postTranslate 的平移量不会被缩放，直接给目标像素坐标即可。
                matrix.postTranslate(offsetX, offsetY - stripTop);
                page.render(strip, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                for (int y = 0; y < stripRows; y++) {
                    strip.getPixels(pixels, 0, canvasWidthPx, 0, y, canvasWidthPx, 1);
                    Arrays.fill(row, (byte) 0);
                    for (int x = 0; x < canvasWidthPx; x++) {
                        int color = pixels[x];
                        int luma = ((color >> 16) & 0xFF) * 299
                                + ((color >> 8) & 0xFF) * 587
                                + (color & 0xFF) * 114;
                        if (luma < INK_THRESHOLD) {
                            row[x >> 3] |= (byte) (0x80 >> (x & 7));
                        }
                    }
                    job.write(("\u001B*b" + rowBytes + "W").getBytes("US-ASCII"));
                    job.write(row);
                }
            } finally {
                strip.recycle();
            }
        }
        job.write("\u001B*rC".getBytes("US-ASCII"));   // 结束光栅
        job.write(0x0C);                              // 换页出纸
    }

    /** 毫米换算成光栅像素。 */
    private static int mmToPx(float millimeters) {
        return Math.max(1, Math.round(millimeters / 25.4f * RASTER_DPI));
    }

    /** PDF 被拒时的兜底：逐页光栅化成 JPEG 后分别发送。 */
    private static void sendJpegRaster(File pdf, PrinterEndpoint endpoint) throws Exception {
        ParcelFileDescriptor descriptor =
                ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            PdfRenderer renderer = new PdfRenderer(descriptor);
            try {
                int pages = renderer.getPageCount();
                int sent = 0;
                for (int i = 0; i < pages; i++) {
                    PdfRenderer.Page page = renderer.openPage(i);
                    Bitmap bitmap = null;
                    try {
                        int width = Math.max(1, Math.round(page.getWidth() * JPEG_DPI / 72f));
                        int height = Math.max(1, Math.round(page.getHeight() * JPEG_DPI / 72f));
                        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(Color.WHITE);
                        Matrix matrix = new Matrix();
                        // transform 是相对 1/72 英寸的缩放，不给矩阵的话内容只会按 72dpi 画在左上角。
                        matrix.setScale(JPEG_DPI / 72f, JPEG_DPI / 72f);
                        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, jpeg);
                        Exception error = sendIpp(endpoint, FORMAT_JPEG, jpeg.toByteArray(), pdf.getName(), 0, null);
                        if (error != null) {
                            if (sent > 0) {
                                throw new PartialSendException("JPEG 光栅第 " + (i + 1)
                                        + " 页被打印机拒绝（此前已发出 " + sent + " 页）");
                            }
                            throw error;
                        }
                        sent++;
                    } finally {
                        if (bitmap != null) {
                            bitmap.recycle();
                        }
                        page.close();
                    }
                }
            } finally {
                renderer.close();
            }
        } finally {
            closeQuietly(descriptor);
        }
    }

    private static byte[] readAllBytes(File file) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FileInputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) > 0) {
                output.write(buffer, 0, length);
            }
        } finally {
            closeQuietly(input);
        }
        return output.toByteArray();
    }

    private static String describe(Throwable throwable) {
        // 原始的 java.net 异常对使用者没有意义，统一翻译成中文原因。
        return PrinterDiagnostics.explain(throwable);
    }

    private static void finish(ResultListener listener, final boolean ok, final String message) {
        Log.d(TAG, (ok ? "打印成功: " : "打印失败: ") + message);
        if (listener == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                listener.onResult(ok, message);
            }
        });
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
