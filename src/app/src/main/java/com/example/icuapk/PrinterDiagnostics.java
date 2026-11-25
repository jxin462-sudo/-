package com.example.icuapk;

import android.text.TextUtils;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * 打印连接诊断：把底层的网络异常翻译成"看得懂、还能照着做"的提示。
 *
 * 直连打印最常见的故障不在 App，而在打印机的地址变了：
 * 主机 ping 得通，但 631/9100 端口直接回 RST（ECONNREFUSED），
 * 这种情况下重试多少次都没用，必须去打印机面板核对地址。
 * 早期版本把 java.net 的原始异常直接抛给用户，屏幕上只会看到
 * "failed to connect to /10.2.34.201 (port 631) ... ECONNREFUSED"，
 * 看不出该做什么，这里统一翻译成中文并给出处置建议。
 */
final class PrinterDiagnostics {

    /** 端口探测的默认超时：短一点，扫完一整个网段才不至于卡住。 */
    private static final int PROBE_TIMEOUT_MS = 1200;

    /** 一次端口探测的结论。 */
    static final class Probe {

        /** 端口上有服务在监听。 */
        final boolean connected;

        /** 主机是活的：回 RST 说明主机在线，只是这个端口没开。 */
        final boolean hostAlive;

        /** 失败原因（中文）。 */
        final String reason;

        Probe(boolean connected, boolean hostAlive, String reason) {
            this.connected = connected;
            this.hostAlive = hostAlive;
            this.reason = reason == null ? "" : reason;
        }
    }

    private PrinterDiagnostics() {
    }

    /**
     * 探测主机某个端口是否在监听。
     *
     * 只建连接、不发送任何打印数据，对打印机本身没有副作用。
     *
     * @param host      主机名或 IP
     * @param port      端口
     * @param timeoutMs 超时时间，传 0 用默认值
     */
    static Probe probe(String host, int port, int timeoutMs) {
        if (TextUtils.isEmpty(host)) {
            return new Probe(false, false, "打印机地址为空");
        }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host.trim(), port),
                    timeoutMs > 0 ? timeoutMs : PROBE_TIMEOUT_MS);
            return new Probe(true, true, "");
        } catch (Exception error) {
            return new Probe(false, isRefused(error), explain(error));
        } finally {
            closeQuietly(socket);
        }
    }

    /** 把 socket 异常翻译成一句中文。 */
    static String explain(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }
        String message = throwable.getMessage();
        String lower = message == null ? "" : message.toLowerCase(Locale.US);
        if (throwable instanceof SocketTimeoutException) {
            return "连接超时：目标没有响应（设备与打印机可能不在同一网络，或被防火墙拦截）";
        }
        if (throwable instanceof UnknownHostException) {
            return "地址无法解析：打印机地址写错了";
        }
        if (throwable instanceof NoRouteToHostException
                || lower.contains("ehostunreach") || lower.contains("enetunreach")) {
            return "网络不可达：设备不在打印机的局域网里";
        }
        if (throwable instanceof PortUnreachableException) {
            return "端口不可达：连接被防火墙拦截";
        }
        if (isRefused(throwable)) {
            return "主机在线，但该端口没有服务在监听（连接被拒绝）";
        }
        if (lower.contains("connection reset")) {
            return "连接被对方重置：打印服务中途断开";
        }
        if (lower.contains("broken pipe")) {
            return "连接被中断：打印机提前关闭了连接";
        }
        if (throwable instanceof IOException) {
            return TextUtils.isEmpty(message) ? "网络错误" : "网络错误：" + message;
        }
        return TextUtils.isEmpty(message) ? throwable.getClass().getSimpleName() : message;
    }

    /**
     * 给操作者的处置建议。
     *
     * @param host 当前填写的打印机地址，可以为空
     */
    static String advice(String host) {
        StringBuilder builder = new StringBuilder("请确认打印机已开机并连上网络");
        if (!TextUtils.isEmpty(host)) {
            builder.append("，在打印机面板『网络设置』核对 IP 是否仍为 ").append(host.trim());
        }
        builder.append("（DHCP 会重新分配 IP）；也可在 PDF 预览页点『打印机：…（点击设置）』改地址");
        return builder.toString();
    }

    /** 是不是"连不上"这类故障——只有这种失败才值得提醒用户改地址或查网络。 */
    static boolean isConnectionFailure(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        if (throwable instanceof ConnectException
                || throwable instanceof SocketTimeoutException
                || throwable instanceof UnknownHostException
                || throwable instanceof NoRouteToHostException
                || throwable instanceof PortUnreachableException) {
            return true;
        }
        String message = throwable.getMessage();
        String lower = message == null ? "" : message.toLowerCase(Locale.US);
        return lower.contains("econnrefused") || lower.contains("connection refused")
                || lower.contains("failed to connect") || lower.contains("timeout");
    }

    /** 端口是不是被明确拒绝（ECONNREFUSED）：说明主机在线，只是没开这个端口。 */
    static boolean isRefused(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        String message = throwable.getMessage();
        String lower = message == null ? "" : message.toLowerCase(Locale.US);
        if (lower.contains("econnrefused") || lower.contains("connection refused")) {
            return true;
        }
        return throwable instanceof ConnectException;
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
