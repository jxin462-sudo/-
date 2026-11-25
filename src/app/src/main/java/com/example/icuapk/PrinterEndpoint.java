package com.example.icuapk;

import android.text.TextUtils;

/**
 * 打印机地址解析结果。
 *
 * 地址支持 "ip"、"ip:端口"、"ipp://ip:端口/路径" 三种写法：
 *  - 没写端口：IPP 走 631，不通再退到 9100 原始端口；
 *  - 写死 9100：只走原始端口——这类打印机只懂 PCL，把 PCL 发到 631 上它根本听不懂；
 *  - 写死其它端口：按 IPP 处理，9100 仍然作为兜底。
 */
final class PrinterEndpoint {

    /** IPP 默认端口。 */
    static final int DEFAULT_IPP_PORT = 631;

    /** RAW 打印端口：只能发 PCL/PostScript，不能直接发 PDF。 */
    static final int RAW_PORT = 9100;

    /** 打印机地址。 */
    final String host;

    /** IPP 端口：状态查询与 IPP 打印都用它。 */
    final int ippPort;

    /** 是否允许走 IPP 打印。 */
    final boolean allowIpp;

    /** 是否允许走 9100 原始端口打印。 */
    final boolean allowRaw;

    /** IPP 资源路径。 */
    final String path;

    PrinterEndpoint(String host, int ippPort, boolean allowIpp, boolean allowRaw, String path) {
        this.host = host == null ? "" : host.trim();
        this.ippPort = ippPort;
        this.allowIpp = allowIpp;
        this.allowRaw = allowRaw;
        this.path = path;
    }

    /** 解析打印机地址。 */
    static PrinterEndpoint parse(String address) {
        String value = address == null ? "" : address.trim();
        for (String prefix : new String[]{"ipp://", "ipps://", "http://", "https://"}) {
            if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                value = value.substring(prefix.length());
                break;
            }
        }
        String path = "/ipp/print";
        int slash = value.indexOf('/');
        if (slash >= 0) {
            path = value.substring(slash);
            value = value.substring(0, slash);
        }
        String host = value;
        int port = -1;
        int colon = value.lastIndexOf(':');
        if (colon > 0 && colon < value.length() - 1) {
            host = value.substring(0, colon);
            try {
                port = Integer.parseInt(value.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
                port = -1;
            }
        }
        if (port == RAW_PORT) {
            return new PrinterEndpoint(host, DEFAULT_IPP_PORT, false, true, path);
        }
        if (port > 0) {
            return new PrinterEndpoint(host, port, true, true, path);
        }
        return new PrinterEndpoint(host, DEFAULT_IPP_PORT, true, true, path);
    }

    /** 地址里有没有可用的打印机主机名或 IP。 */
    boolean isValid() {
        return !TextUtils.isEmpty(host);
    }

    /**
     * 打印机资源 URI。
     *
     * HP 固件只认自己配置里的那一种写法：配置成 "ipp://主机/ipp/print" 的机型，
     * 收到带端口的 URI 会回 0x040D，反过来也一样，所以两种写法都要试。
     */
    static String printerUri(String host, int port, String path, boolean withPort) {
        String suffix = TextUtils.isEmpty(path) ? "/ipp/print" : path;
        return withPort ? ("ipp://" + host + ":" + port + suffix) : ("ipp://" + host + suffix);
    }
}
