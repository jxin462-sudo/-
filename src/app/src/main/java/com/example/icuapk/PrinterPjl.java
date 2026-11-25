package com.example.icuapk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PJL 通道：打印机在 9100 原始端口上的"命令行"。
 *
 * IPP(631) 和 Web(80) 都跑在固件的 HTTP 任务里，队列一旦积压就整条卡死；
 * PJL 由另一条解释器处理，机器卡住时往往还活着。
 * 所以这条通道负责两件 IPP 做不到的事：
 *  1) 打印前问清楚打印机支持哪些语言（PCLm / URF / PCL），
 *     免得发了它不认的格式——固件会照单全收却把任务压在队列里不执行，
 *     表面"打印成功"，纸却一直不出；
 *  2) 发送之后看一眼面板，把"数据交出去了"和"真的在打印"分开。
 *
 * 实测机型：HP LaserJet Professional M1213nf MFP，
 * PJL INFO CONFIG 回报 LANGUAGES = ZJS HBS URF PCLm PJL ACL HTTP。
 */
final class PrinterPjl {

    /** 连接超时：PJL 只是一次问答，不必久等。 */
    private static final int CONNECT_TIMEOUT_MS = 4000;

    /** 读取超时：有些机型不回 EOF，按已收到的内容算。 */
    private static final int READ_TIMEOUT_MS = 4000;

    /** PJL 任务结束符。 */
    private static final String UEL = "\u001B%-12345X";

    /** 固件用换页符收尾，收到它就算一条回答读完了。 */
    private static final int RESPONSE_END = 0x0C;

    /** 单次响应上限，防止异常实现撑爆内存。 */
    private static final int MAX_RESPONSE_BYTES = 32 * 1024;

    private PrinterPjl() {
    }

    /** 打印机能力：型号、支持的语言、装着的纸、可用分辨率。 */
    static final class Info {

        /** 9100 通道是否可用。 */
        final boolean reachable;

        /** 型号（@PJL INFO ID），例如 "HP LaserJet Professional M1213nf MFP"。 */
        final String model;

        /** 支持的语言（PJL LANGUAGES），例如 PCLm、URF、PJL。 */
        final Set<String> languages;

        /** 装着的纸（PJL PAPERS），例如 LETTER、A4。 */
        final Set<String> papers;

        /** 支持的光栅分辨率（PJL RESOLUTION），例如 300、600。 */
        final List<Integer> resolutions;

        /** 连不上时的原因。 */
        final String error;

        Info(boolean reachable, String model, Set<String> languages, Set<String> papers,
             List<Integer> resolutions, String error) {
            this.reachable = reachable;
            this.model = model == null ? "" : model;
            this.languages = languages == null ? new LinkedHashSet<String>() : languages;
            this.papers = papers == null ? new LinkedHashSet<String>() : papers;
            this.resolutions = resolutions == null ? new ArrayList<Integer>() : resolutions;
            this.error = error == null ? "" : error;
        }

        /** 打印机是否声明支持某种语言。 */
        boolean supports(String language) {
            if (language == null) {
                return false;
            }
            for (String candidate : languages) {
                if (candidate.equalsIgnoreCase(language)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 面板状态：显示文字、在线标志、状态码、当前页码与任务号。 */
    static final class Status {

        /** 9100 通道是否可用。 */
        final boolean reachable;

        /** 面板显示的文字（固件用 UTF-8 编码，例如"就绪"、"打印中"）。 */
        final String display;

        /** 打印机是否在线。 */
        final boolean online;

        /** PJL 状态码，例如 10001。 */
        final int code;

        /** 正在打印的页码，-1 表示没有这个字段。 */
        final int page;

        /** 正在执行的任务号。 */
        final String job;

        /** 读不到时的原因。 */
        final String error;

        Status(boolean reachable, String display, boolean online, int code, int page, String job, String error) {
            this.reachable = reachable;
            this.display = display == null ? "" : display;
            this.online = online;
            this.code = code;
            this.page = page;
            this.job = job == null ? "" : job;
            this.error = error == null ? "" : error;
        }

        /**
         * 面板是不是在说"正在打印"。
         *
         * 打印期间面板会显示"打印中"、"正在初始化"这类文字，或者给出 PAGE 页码；
         * 两者都算机器真的动起来了，"就绪"这种则不算。
         */
        boolean isPrinting() {
            if (!reachable) {
                return false;
            }
            if (page > 0) {
                return true;
            }
            return display.contains("打印") || display.contains("处理") || display.contains("预热")
                    || display.contains("初始化");
        }
    }

    /**
     * 问打印机的能力。
     *
     * 一条连接上问两件事：配置（INFO CONFIG，含语言/纸张/分辨率）与型号（INFO ID）。
     * 两问都答不上来才算通道不通。
     */
    static Info query(String host) {
        Session session = null;
        try {
            session = new Session(host);
            String config = session.ask("@PJL INFO CONFIG");
            String model = session.ask("@PJL INFO ID");
            if (config == null && model == null) {
                return new Info(false, "", null, null, null, "9100 端口无响应");
            }
            return new Info(true, extractModel(model), parseList(config, "LANGUAGES"),
                    parseList(config, "PAPERS"), parseNumbers(config, "RESOLUTION"), "");
        } catch (Exception error) {
            return new Info(false, "", null, null, null, "9100 端口无响应");
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    /** 读面板状态。 */
    static Status status(String host) {
        Session session = null;
        try {
            session = new Session(host);
            String text = session.ask("@PJL INFO STATUS");
            if (text == null) {
                return new Status(false, "", false, -1, -1, "", "9100 端口无响应");
            }
            String display = "";
            boolean online = false;
            int code = -1;
            int page = -1;
            String job = "";
            for (String line : text.split("\r\n|\n|\r")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("DISPLAY=")) {
                    display = unquote(trimmed.substring("DISPLAY=".length()));
                } else if (trimmed.startsWith("ONLINE=")) {
                    online = "TRUE".equalsIgnoreCase(trimmed.substring("ONLINE=".length()).trim());
                } else if (trimmed.startsWith("CODE=")) {
                    code = parseInt(trimmed.substring("CODE=".length()), -1);
                } else if (trimmed.startsWith("PAGE=")) {
                    page = parseInt(trimmed.substring("PAGE=".length()), -1);
                } else if (trimmed.startsWith("JOB=")) {
                    job = unquote(trimmed.substring("JOB=".length()));
                }
            }
            return new Status(true, display, online, code, page, job, "");
        } catch (Exception error) {
            return new Status(false, "", false, -1, -1, "", "9100 端口无响应");
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    /**
     * 一次 PJL 会话：一条连接上问若干个问题。
     */
    private static final class Session {

        private final Socket socket;
        private final InputStream input;
        private final OutputStream output;

        Session(String host) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, PrinterEndpoint.RAW_PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            input = socket.getInputStream();
            output = socket.getOutputStream();
        }

        /** 问一句，等回答；答不上来返回 null。 */
        String ask(String command) throws IOException {
            output.write((UEL + command + "\r\n").getBytes("US-ASCII"));
            output.flush();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            while (buffer.size() < MAX_RESPONSE_BYTES) {
                int read;
                try {
                    read = input.read(chunk);
                } catch (IOException timeout) {
                    break;      // 读超时：机型不回 EOF，按已收到的内容算
                }
                if (read <= 0) {
                    break;
                }
                buffer.write(chunk, 0, read);
                int last = chunk[read - 1] & 0xFF;
                if (last == RESPONSE_END) {
                    break;      // 固件用换页符收尾，收到了就是完整的回答
                }
            }
            String text = new String(buffer.toByteArray(), "UTF-8").trim();
            return text.isEmpty() ? null : text;
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 解析 "LANGUAGES [7 ENUMERATED]" 这类枚举段的条目。
     *
     * 固件把条目写成缩进行，下一个关键字顶格，所以按缩进判断段落边界。
     */
    private static Set<String> parseList(String text, String key) {
        Set<String> values = new LinkedHashSet<String>();
        if (text == null) {
            return values;
        }
        boolean inside = false;
        for (String line : text.split("\r\n|\n|\r")) {
            if (inside) {
                if (line.isEmpty()) {
                    inside = false;
                    continue;
                }
                char first = line.charAt(0);
                if (first == '\t' || first == ' ') {
                    values.add(line.trim());
                    continue;
                }
                inside = false;      // 顶格了：下一个段落开始
            }
            if (line.trim().startsWith(key + " [")) {
                inside = true;
            }
        }
        return values;
    }

    /** 解析 "RESOLUTION [2 ENUMERATED]" 这样的数字列表。 */
    private static List<Integer> parseNumbers(String text, String key) {
        List<Integer> values = new ArrayList<Integer>();
        if (text == null) {
            return values;
        }
        boolean inside = false;
        for (String line : text.split("\r\n|\n|\r")) {
            if (inside) {
                if (line.isEmpty()) {
                    inside = false;
                    continue;
                }
                char first = line.charAt(0);
                if (first == '\t' || first == ' ') {
                    int value = parseInt(line.trim(), -1);
                    if (value > 0) {
                        values.add(value);
                    }
                    continue;
                }
                inside = false;
            }
            if (line.trim().startsWith(key + " [")) {
                inside = true;
            }
        }
        return values;
    }

    /** 从 INFO ID 的回答里取出型号。 */
    private static String extractModel(String text) {
        if (text == null) {
            return "";
        }
        int start = text.indexOf('"');
        int end = text.lastIndexOf('"');
        if (start >= 0 && end > start) {
            return text.substring(start + 1, end).trim();
        }
        return text.trim();
    }

    /** 去掉属性值外面的引号。 */
    private static String unquote(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    /** 取开头的整数，"10001" 之类取 10001，"-1" 之类取不到就返回 fallback。 */
    private static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.trim();
        int end = 0;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.substring(0, end));
        } catch (NumberFormatException error) {
            return fallback;
        }
    }
}
