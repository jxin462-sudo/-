package com.example.icuapk;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 极简 IPP 客户端：只实现直连打印需要的两个操作。
 *
 * 兼容老打印机的两个关键点（HP LaserJet Pro MFP M1213nf 实测）：
 *  1) IPP 1.1 的属性值长度字段是 2 字节，IPP 2.0 才是 4 字节。
 *     声明 1.1 却按 4 字节写，打印机会当成格式错误回 0x0400 bad request；
 *  2) 响应里的长度字段宽度由响应头版本号决定，不能一律按 4 字节解析，
 *     否则打印机状态、队列长度这些属性会整体错位，根本读不出来。
 */
final class IppClient {

    /** Print-Job：把打印数据直接交给打印机。 */
    static final int OPERATION_PRINT_JOB = 0x0002;

    /** Get-Printer-Attributes：读取打印机状态与能力。 */
    static final int OPERATION_GET_PRINTER_ATTRIBUTES = 0x000B;

    /** 属性值长度按 2 字节编码（IPP 1.x）。 */
    private static final int VALUE_LENGTH_IPP_1X = 2;

    /** 属性值长度按 4 字节编码（IPP 2.0）。 */
    private static final int VALUE_LENGTH_IPP_2X = 4;

    /** IPP 报文头固定 8 字节：版本号(2) + 操作码(2) + request-id(4)。 */
    private static final int IPP_HEADER_BYTES = 8;

    /** 自然语言：打印机固件普遍只认 en-us，填 zh-cn 会被部分机型判成 0x040D。 */
    private static final String NATURAL_LANGUAGE = "en-us";

    /** 状态查询时用 requested-attributes 组逐条点名的属性。 */
    private static final String[] REQUESTED_ATTRIBUTES = {
            "printer-state",
            "printer-state-reasons",
            "queued-job-count",
            "printer-info",
            "document-format-supported",
            "printer-name",
            "printer-location",
            "printer-make-and-model",
            "printer-is-accepting-jobs",
            "pclm-source-resolution-supported",
            "pclm-strip-height-preferred",
            "urf-supported"
    };

    /** 响应体上限：状态响应很小，限制住可以防止异常实现撑爆内存。 */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    /** 连接重试间隔（毫秒）：打印机休眠时需要这点时间把它唤醒。 */
    private static final long CONNECT_RETRY_DELAY_MS = 1200L;

    private IppClient() {
    }

    /** 一次 IPP 交互的结果。 */
    static final class Response {
        /** HTTP 状态码。 */
        final int httpCode;
        /** IPP 状态码，0x0000 表示成功。 */
        final int ippStatus;
        /** 打印机自述的信息，用于把原因带给用户。 */
        final String statusMessage;
        /** 打印机属性；同名属性有多个值时用逗号连接。 */
        final Map<String, String> attributes;

        Response(int httpCode, int ippStatus, String statusMessage, Map<String, String> attributes) {
            this.httpCode = httpCode;
            this.ippStatus = ippStatus;
            this.statusMessage = statusMessage == null ? "" : statusMessage;
            this.attributes = attributes == null ? new LinkedHashMap<String, String>() : attributes;
        }

        /** 打印机是否接受了这个请求。 */
        boolean isAccepted() {
            return ippStatus <= 0x00FF;
        }
    }

    /**
     * 请求已经发出、但响应没能读回来：打印任务是否被受理无法确认。
     *
     * 这种场合绝不能换协议再发一次，否则同一份报告会打印多张；
     * 调用方只能如实告诉使用者「请先核对是否出纸」。
     */
    static final class UncertainOutcomeException extends IOException {

        UncertainOutcomeException(Throwable cause) {
            super(cause == null ? "IPP 响应未确认" : cause.getMessage());
            if (cause != null) {
                initCause(cause);
            }
        }
    }

    /**
     * 发送一次 IPP 请求并解析响应。
     *
     * @param head IPP 请求头（不含 HTTP 头）
     * @param body 打印数据，可为空
     */
    static Response send(String host, int port, String path, byte[] head, byte[] body,
                         int connectTimeoutMs, int readTimeoutMs) throws IOException {
        return send(host, port, path, head, body, connectTimeoutMs, readTimeoutMs, 1);
    }

    /**
     * 发送一次 IPP 请求并解析响应，可指定连接重试次数。
     *
     * 打印机休眠时第一次连接常被直接拒绝，隔一下再连就能把它唤醒，
     * 所以打印任务用多次连接，状态查询只用一次（快速失败更符合操作预期）。
     */
    static Response send(String host, int port, String path, byte[] head, byte[] body,
                         int connectTimeoutMs, int readTimeoutMs, int connectAttempts) throws IOException {
        Socket socket = null;
        IOException connectError = null;
        int attempts = Math.max(1, connectAttempts);
        for (int attempt = 0; attempt < attempts; attempt++) {
            Socket candidate = new Socket();
            try {
                candidate.connect(new InetSocketAddress(host, port), connectTimeoutMs);
                socket = candidate;
                connectError = null;
                break;
            } catch (IOException error) {
                connectError = error;
                closeQuietly(candidate);
                if (attempt + 1 < attempts) {
                    sleepQuietly(CONNECT_RETRY_DELAY_MS);
                }
            }
        }
        if (socket == null) {
            throw connectError != null ? connectError : new IOException("无法连接打印机 " + host + ":" + port);
        }
        try {
            socket.setSoTimeout(readTimeoutMs);
            OutputStream out = socket.getOutputStream();
            int contentLength = head.length + (body == null ? 0 : body.length);
            StringBuilder request = new StringBuilder();
            request.append("POST ").append(path).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(host).append(':').append(port).append("\r\n");
            request.append("Content-Type: application/ipp\r\n");
            request.append("Content-Length: ").append(contentLength).append("\r\n");
            request.append("Connection: close\r\n\r\n");
            boolean requestSent = false;
            try {
                out.write(request.toString().getBytes("US-ASCII"));
                out.write(head);
                if (body != null) {
                    out.write(body);
                }
                out.flush();
                requestSent = true;

                InputStream in = socket.getInputStream();
                String statusLine = readHeaderLine(in);
                if (statusLine == null || !statusLine.startsWith("HTTP/")) {
                    throw new IOException("IPP 响应无法解析");
                }
                int code = parseHttpCode(statusLine);
                // 有些打印机会先回 100 Continue，再回正式的 IPP 响应，100 没有响应体。
                while (code == 100) {
                    discardHeaders(in);
                    statusLine = readHeaderLine(in);
                    if (statusLine == null || !statusLine.startsWith("HTTP/")) {
                        throw new IOException("IPP 响应无法解析");
                    }
                    code = parseHttpCode(statusLine);
                }
                if (code < 200 || code >= 300) {
                    return new Response(code, -1, "", new LinkedHashMap<String, String>());
                }
                byte[] responseBody = readResponseBody(in);
                if (responseBody.length < 4) {
                    throw new IOException("IPP 响应不完整");
                }
                int ippStatus = ((responseBody[2] & 0xFF) << 8) | (responseBody[3] & 0xFF);
                Map<String, String> attributes = parseAttributes(responseBody);
                return new Response(code, ippStatus, attributes.get("status-message"), attributes);
            } catch (IOException error) {
                // 请求已经发出去了：任务有没有被受理无法确认。
                // 这种场合不能再换协议重发一次，否则同一份报告会打印多张。
                if (requestSent) {
                    throw new UncertainOutcomeException(error);
                }
                throw error;
            }
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * IPP 资源路径候选。
     *
     * 老打印机的 IPP 服务挂在不同路径下（/ipp/print、/ipp、/ 都见过），
     * 挨个试一遍，比写死一个路径稳。
     */
    static String[] resourcePaths(String preferredPath) {
        if (preferredPath == null || preferredPath.isEmpty()) {
            return new String[]{"/", "/ipp/print", "/ipp"};
        }
        if ("/ipp/print".equals(preferredPath)) {
            return new String[]{"/ipp/print", "/ipp", "/"};
        }
        if ("/".equals(preferredPath)) {
            return new String[]{"/", "/ipp/print", "/ipp"};
        }
        return new String[]{preferredPath};
    }

    /**
     * 组装 Print-Job 请求头。
     *
     * @param printerUri 打印机资源 URI，例如 ipp://10.2.34.201:631/ipp/print
     * @param ipp2x true 时按 IPP 2.0 发（属性长度 4 字节），用于兼容只认 2 字节的老打印机之外的实现
     */
    static byte[] buildPrintJobHead(String printerUri, String documentFormat, String jobName,
                                    boolean ipp2x) throws IOException {
        return buildPrintJobHead(printerUri, documentFormat, jobName, ipp2x, 0, null);
    }

    /**
     * 组装 Print-Job 请求头。
     *
     * @param pclmDpi 大于 0 时带上 PCLm 需要的分辨率属性：固件靠
     *                pclm-source-resolution / printer-resolution 还原分条光栅，
     *                不给的话 PCLm 文档会被当成未知尺寸而丢弃
     * @param mediaName 纸张名（iso_a4_210x297mm 等），可为 null
     */
    static byte[] buildPrintJobHead(String printerUri, String documentFormat, String jobName,
                                    boolean ipp2x, int pclmDpi, String mediaName) throws IOException {
        int valueLengthBytes = ipp2x ? VALUE_LENGTH_IPP_2X : VALUE_LENGTH_IPP_1X;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        if (ipp2x) {
            out.writeByte(2);
            out.writeByte(0);
        } else {
            out.writeByte(1);
            out.writeByte(1);
        }
        out.writeShort(OPERATION_PRINT_JOB);
        // request-id 固定 4 字节：IPP/1.1 起报文头就是 8 字节，
        // 写成 2 字节报文头会少 2 字节，严格的打印机会判 0x0400 bad request。
        out.writeInt(1);
        out.writeByte(0x01);            // operation-attributes-tag
        writeAttribute(out, 0x47, "attributes-charset", "utf-8", valueLengthBytes);
        writeAttribute(out, 0x48, "attributes-natural-language", NATURAL_LANGUAGE, valueLengthBytes);
        writeAttribute(out, 0x45, "printer-uri", printerUri, valueLengthBytes);
        writeAttribute(out, 0x42, "requesting-user-name", "ICU", valueLengthBytes);
        writeAttribute(out, 0x42, "job-name", jobName, valueLengthBytes);
        // document-format 是 IPP 1.1 的属性，media-type 是 1.0 的老属性：
        // 两种都带上，老打印机才不会因为认不出格式而回 0x040A。
        writeAttribute(out, 0x42, "media-type", documentFormat, valueLengthBytes);
        writeAttribute(out, 0x49, "document-format", documentFormat, valueLengthBytes);
        if (pclmDpi > 0) {
            writeResolutionAttribute(out, "pclm-source-resolution", pclmDpi, valueLengthBytes);
            writeResolutionAttribute(out, "printer-resolution", pclmDpi, valueLengthBytes);
            writeAttribute(out, 0x44, "print-color-mode", "monochrome", valueLengthBytes);
        }
        if (mediaName != null && mediaName.length() > 0) {
            writeAttribute(out, 0x44, "media", mediaName, valueLengthBytes);
        }
        out.writeByte(0x03);            // end-of-attributes-tag
        out.flush();
        return buffer.toByteArray();
    }

    /**
     * 组装 Get-Printer-Attributes 请求头。
     *
     * @param withRequestedAttributes true 时附带 requested-attributes 组逐条点名要的属性，
     *        部分固件缺这组会回 0x040D
     */
    static byte[] buildGetPrinterAttributesHead(String printerUri, boolean withRequestedAttributes)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeByte(1);
        out.writeByte(1);
        out.writeShort(OPERATION_GET_PRINTER_ATTRIBUTES);
        out.writeInt(1);
        out.writeByte(0x01);            // operation-attributes-tag
        writeAttribute(out, 0x47, "attributes-charset", "utf-8", VALUE_LENGTH_IPP_1X);
        writeAttribute(out, 0x48, "attributes-natural-language", NATURAL_LANGUAGE, VALUE_LENGTH_IPP_1X);
        writeAttribute(out, 0x45, "printer-uri", printerUri, VALUE_LENGTH_IPP_1X);
        out.writeByte(0x03);
        if (withRequestedAttributes) {
            out.writeByte(0x44);        // requested-attributes-tag
            for (String name : REQUESTED_ATTRIBUTES) {
                writeAttribute(out, 0x44, name, "", VALUE_LENGTH_IPP_1X);
            }
            out.writeByte(0x03);
        }
        out.flush();
        return buffer.toByteArray();
    }

    /** 写一条 IPP 属性：tag + 名称 + 值。 */
    private static void writeAttribute(DataOutputStream out, int tag, String name, String value, int valueLengthBytes)
            throws IOException {
        byte[] nameBytes = name.getBytes("US-ASCII");
        byte[] valueBytes = value.getBytes("UTF-8");
        out.writeByte(tag);
        out.writeShort(nameBytes.length);
        out.write(nameBytes);
        if (valueLengthBytes == VALUE_LENGTH_IPP_2X) {
            out.writeInt(valueBytes.length);
        } else {
            out.writeShort(valueBytes.length);
        }
        out.write(valueBytes);
    }

    /**
     * 写一条 resolution 属性：9 字节——x 分辨率、y 分辨率、单位。
     *
     * 单位 3 是"每英寸点数"，PCLm 光栅必须按打印机的实际 dpi 声明，
     * 否则固件按 300dpi 解释 600dpi 的条带，出来的会是花的。
     */
    private static void writeResolutionAttribute(DataOutputStream out, String name, int dpi,
                                                 int valueLengthBytes) throws IOException {
        byte[] nameBytes = name.getBytes("US-ASCII");
        out.writeByte(0x32);                   // resolution
        out.writeShort(nameBytes.length);
        out.write(nameBytes);
        if (valueLengthBytes == VALUE_LENGTH_IPP_2X) {
            out.writeInt(9);
        } else {
            out.writeShort(9);
        }
        out.writeInt(dpi);
        out.writeInt(dpi);
        out.writeByte(3);                      // 单位：每英寸点数
    }

    /**
     * 解析 IPP 响应里的属性。
     *
     * 长度字段的宽度按响应头的版本号决定：IPP 2.0 是 4 字节，1.x 是 2 字节。
     */
    static Map<String, String> parseAttributes(byte[] body) {
        Map<String, String> result = new LinkedHashMap<>();
        if (body == null || body.length < 8) {
            return result;
        }
        int valueLengthBytes = (body[0] & 0xFF) >= 2 ? VALUE_LENGTH_IPP_2X : VALUE_LENGTH_IPP_1X;
        // 报文头固定 8 字节（IPP/1.1 起 request-id 就是 4 字节）：
        // 少读 2 字节会把属性整体错位，状态与能力一个都读不出来。
        int offset = IPP_HEADER_BYTES;
        while (offset < body.length) {
            int tag = body[offset] & 0xFF;
            offset++;
            if (tag == 0x03) {
                break;                                   // end-of-attributes-tag
            }
            if (tag < 0x10) {
                continue;                                // 属性组分隔符
            }
            if (offset + 2 > body.length) {
                break;
            }
            int nameLength = readUnsignedShort(body, offset);
            offset += 2;
            if (nameLength < 0 || offset + nameLength > body.length) {
                break;
            }
            String name = new String(body, offset, nameLength, StandardCharsets.US_ASCII);
            offset += nameLength;
            List<String> values = new ArrayList<>();
            while (true) {
                if (offset + valueLengthBytes > body.length) {
                    break;
                }
                int valueLength = valueLengthBytes == VALUE_LENGTH_IPP_2X
                        ? readUnsignedInt(body, offset) : readUnsignedShort(body, offset);
                if (valueLength < 0) {
                    break;
                }
                offset += valueLengthBytes;
                if (offset + valueLength > body.length) {
                    break;
                }
                values.add(decodeValue(tag, body, offset, valueLength));
                offset += valueLength;
                if (offset >= body.length) {
                    break;
                }
                int peek = body[offset] & 0xFF;
                if (peek < 0x21 || peek > 0x3F) {
                    break;                               // 普通的下一个属性，这个属性到此结束
                }
                // 0x21-0x3F 既可能是"同属性的下一个值"，也可能是"下一个属性的 tag"
                //（printer-state 就是 0x23）。两种编码的字节长得一模一样，
                // 只能靠后面能不能读出像样的属性名来区分。
                int tagPosition = offset;
                offset++;
                if (looksLikeAttributeName(body, offset)) {
                    offset = tagPosition;                // 是新属性，交回外层按属性处理
                    break;
                }
                // 是同属性的下一个值，offset 已经跳过 additional-value-tag，继续读值
            }
            if (!values.isEmpty()) {
                String joined = joinValues(values);
                String previous = result.get(name);
                // 同一个属性出现多次时合并，兼容"多值用重复 tag 编码"的打印机。
                result.put(name, previous == null ? joined : previous + ", " + joined);
            }
        }
        return result;
    }

    /**
     * 判断当前位置能不能读出一个像样的 IPP 属性名。
     *
     * IPP 的属性名都是"单词-单词"的形式（printer-state、queued-job-count），
     * 值则五花八门（none、application/pdf）。用这条经验规则区分
     * "下一个属性"和"同属性的下一个值"。
     */
    private static boolean looksLikeAttributeName(byte[] body, int offset) {
        if (offset + 2 > body.length) {
            return false;
        }
        int length = readUnsignedShort(body, offset);
        if (length < 6 || length > 64 || offset + 2 + length > body.length) {
            return false;
        }
        boolean hasSeparator = false;
        for (int i = 0; i < length; i++) {
            char value = (char) (body[offset + 2 + i] & 0xFF);
            if (value == '-') {
                hasSeparator = true;
                continue;
            }
            boolean letterOrDigit = (value >= 'a' && value <= 'z')
                    || (value >= 'A' && value <= 'Z')
                    || (value >= '0' && value <= '9');
            if (!letterOrDigit) {
                return false;
            }
        }
        return hasSeparator;
    }

    /**
     * 按属性的语法类型解码取值。
     *
     * integer(0x21) / boolean(0x22) / enum(0x23) 这几种 tag 的值是二进制整数，
     * 按 UTF-8 解出来是乱码：printer-state 会变成"状态未知"，
     * queued-job-count 也永远读成 0，队列积压根本发现不了。
     */
    private static String decodeValue(int tag, byte[] body, int offset, int length) {
        if (tag == 0x21 || tag == 0x22 || tag == 0x23 || tag == 0x40) {
            int number = length >= 4 ? readUnsignedInt(body, offset)
                    : (length == 1 ? body[offset] & 0xFF : 0);
            return number < 0 ? "0" : Integer.toString(number);
        }
        if (tag == 0x32) {                     // resolution：x 分辨率、y 分辨率、单位
            if (length < 9) {
                return "0";
            }
            int x = readUnsignedInt(body, offset);
            int y = readUnsignedInt(body, offset + 4);
            int units = body[offset + 8] & 0xFF;
            if (units == 3 && x == y) {
                return Integer.toString(x);    // 常见情况：每英寸点数
            }
            return x + "x" + y;
        }
        return new String(body, offset, length, StandardCharsets.UTF_8);
    }

    private static String joinValues(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    private static int readUnsignedShort(byte[] data, int offset) {
        if (offset + 2 > data.length) {
            return -1;
        }
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readUnsignedInt(byte[] offsetData, int offset) {
        if (offset + 4 > offsetData.length) {
            return -1;
        }
        return ((offsetData[offset] & 0xFF) << 24) | ((offsetData[offset + 1] & 0xFF) << 16)
                | ((offsetData[offset + 2] & 0xFF) << 8) | (offsetData[offset + 3] & 0xFF);
    }

    private static int parseHttpCode(String statusLine) {
        String[] parts = statusLine.split(" ");
        if (parts.length < 2) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /** 读取响应体：支持 Content-Length、Transfer-Encoding: chunked 与连接关闭三种形式。 */
    private static byte[] readResponseBody(InputStream in) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readHeaderLine(in);
            if (line == null) {
                throw new IOException("HTTP 响应头不完整");
            }
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                        line.substring(colon + 1).trim());
            }
        }
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.US).contains("chunked")) {
            return readChunkedBody(in);
        }
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            final long remaining;
            try {
                remaining = Long.parseLong(contentLength);
            } catch (NumberFormatException exception) {
                throw new IOException("HTTP Content-Length 无效: " + contentLength);
            }
            if (remaining < 0 || remaining > MAX_RESPONSE_BYTES) {
                throw new IOException("HTTP 响应体长度异常: " + remaining);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream((int) remaining);
            copyBytes(in, buffer, remaining);
            return buffer.toByteArray();
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        try {
            int read;
            while ((read = in.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
        } catch (SocketTimeoutException ignored) {
            // 打印机不关连接时，保留已收到的响应交给 IPP 状态解析。
        }
        return buffer.toByteArray();
    }

    private static byte[] readChunkedBody(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            String line = readHeaderLine(in);
            if (line == null) {
                throw new IOException("HTTP 分块大小缺失");
            }
            int extension = line.indexOf(';');
            String hex = (extension >= 0 ? line.substring(0, extension) : line).trim();
            final int chunkSize;
            try {
                chunkSize = Integer.parseInt(hex, 16);
            } catch (NumberFormatException exception) {
                throw new IOException("HTTP 分块大小无效: " + hex);
            }
            if (chunkSize < 0 || chunkSize > MAX_RESPONSE_BYTES || buffer.size() + chunkSize > MAX_RESPONSE_BYTES) {
                throw new IOException("HTTP 分块响应体过大");
            }
            if (chunkSize == 0) {
                discardHeaders(in);
                return buffer.toByteArray();
            }
            copyBytes(in, buffer, chunkSize);
            String terminator = readHeaderLine(in);
            if (terminator == null || !terminator.isEmpty()) {
                throw new IOException("HTTP 分块结束符无效");
            }
        }
    }

    private static void copyBytes(InputStream in, ByteArrayOutputStream out, long count) throws IOException {
        byte[] buffer = new byte[4096];
        long remaining = count;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new EOFException("HTTP 响应体不完整");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static String readHeaderLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = in.read()) >= 0) {
            if (value == '\n') {
                return new String(line.toByteArray(), "US-ASCII");
            }
            if (value != '\r') {
                line.write(value);
            }
            if (line.size() > 8192) {
                throw new IOException("HTTP 响应头行过长");
            }
        }
        return line.size() == 0 ? null : new String(line.toByteArray(), "US-ASCII");
    }

    private static void discardHeaders(InputStream in) throws IOException {
        while (true) {
            String line = readHeaderLine(in);
            if (line == null || line.isEmpty()) {
                return;
            }
        }
    }

    /** 稍等一会儿再继续，等打印机从休眠里醒过来。 */
    private static void sleepQuietly(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
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
