package com.example.icuapk;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 局域网打印机扫描：用 mDNS/Bonjour 发现 _ipp._tcp 与 _pdl-datastream._tcp 服务。
 *
 * 实现要点（参考 jxin462-sudo/icu 的打印设置扫描）：
 *  1) 扫描期间必须持有组播锁，部分机型不持锁收不到打印机应答，结果恒为空；
 *  2) NsdManager 同时只允许一个 resolve 请求在飞，多个服务必须排队串行解析，
 *     否则会抛 IllegalStateException，结果一个都扫不到；
 *  3) 扫描窗口固定 8 秒，到点主动停止并回推已发现的结果。
 *
 * 现状：打印设置弹窗里的"扫描局域网打印机"入口已按产品要求移除，
 * 本类暂时没有调用方，作为局域网打印机的发现能力保留，需要时直接调用 scan 即可。
 */
final class PrinterScanner {

    /** IPP 打印服务，绝大多数网络打印机都提供这个端口。 */
    private static final String SERVICE_IPP = "_ipp._tcp.";

    /** RAW 打印服务（9100 端口），只能走系统打印或厂商驱动。 */
    private static final String SERVICE_RAW = "_pdl-datastream._tcp.";

    /** 单次扫描的时间窗口。 */
    private static final long SCAN_WINDOW_MS = 8000L;

    /** 跨网段探测的并发数：一次开太多连接，移动网络扛不住。 */
    private static final int SWEEP_CONCURRENCY = 24;

    /** 跨网段探测单个端口的超时，越短整轮扫得越快。 */
    private static final int SWEEP_TIMEOUT_MS = 800;

    /** 跨网段探测的最长等待时间，超过就按当前结果收尾。 */
    private static final long SWEEP_MAX_MS = 20000L;

    /** 最多回传多少台打印机，防止异常环境里刷屏。 */
    private static final int MAX_PRINTERS = 20;

    /** 扫描结果回调（在主线程）。 */
    interface Listener {
        /**
         * @param printers 扫描到的打印机；为空表示没找到
         * @param error    扫描失败原因；为空表示扫描本身正常执行完
         */
        void onScanFinished(List<Printer> printers, String error);
    }

    /** 一台被发现的打印机：显示名 + 可直接填进地址框的 host:port。 */
    static final class Printer {
        final String name;
        final String address;
        /** 发现方式：广播发现 / 网段扫描，让使用者知道这条结果可不可信。 */
        final String source;

        Printer(String name, String address) {
            this(name, address, "广播发现");
        }

        Printer(String name, String address, String source) {
            this.name = name == null || name.length() == 0 ? address : name;
            this.address = address;
            this.source = source == null ? "" : source;
        }

        /** 列表里显示两行：打印机名 + 地址，并标出端口类型与发现方式。 */
        String display() {
            String type = address.endsWith(":9100") ? "（RAW 9100，需系统打印）" : "（IPP）";
            return name + type + "\n" + address + " · " + source;
        }
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<NsdManager.DiscoveryListener> discoveryListeners = new ArrayList<>();
    private final Map<String, Printer> printers = new LinkedHashMap<>();
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private final AtomicBoolean resolving = new AtomicBoolean(false);

    private NsdManager nsdManager;
    private NsdManager.ResolveListener resolveListener;
    private WifiManager.MulticastLock multicastLock;
    private Listener listener;
    private boolean finished;
    /** mDNS 广播部分是否已结束。 */
    private boolean nsdDone;
    /** 跨网段探测是否已结束。 */
    private boolean sweepDone;
    /** 扫描时用来推断网段的打印机地址。 */
    private final String hintAddress;

    private PrinterScanner(Context context, String hintAddress) {
        this.context = context == null ? null : context.getApplicationContext();
        this.hintAddress = hintAddress == null ? "" : hintAddress.trim();
    }

    /**
     * 开始扫描。
     *
     * @param hintAddress 当前填写的打印机地址：mDNS 只覆盖同一广播域，
     *                    跨网段的打印机要靠它推断出网段，再逐个地址试打印端口
     */
    static void scan(Context context, String hintAddress, Listener listener) {
        new PrinterScanner(context, hintAddress).start(listener);
    }

    private void start(Listener target) {
        this.listener = target;
        if (context == null) {
            finish(null, "当前环境不支持打印机扫描");
            return;
        }
        Object service = context.getSystemService(Context.NSD_SERVICE);
        if (service instanceof NsdManager) {
            startBroadcastDiscovery((NsdManager) service);
        } else {
            // 没有 NSD 也不影响跨网段探测，照样能把打印机找出来。
            nsdDone = true;
        }
        startSubnetSweep();
        // 兜底：无论广播还是探测卡在哪里，最多等这么久就按已有结果收尾。
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                nsdDone = true;
                sweepDone = true;
                finish(null, null);
            }
        }, SWEEP_MAX_MS);
    }

    /** mDNS/Bonjour 广播发现：只能覆盖同一个广播域。 */
    private void startBroadcastDiscovery(NsdManager manager) {
        nsdManager = manager;
        acquireMulticastLock();
        resolveListener = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                resolving.set(false);
                resolveNext();
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                resolving.set(false);
                collect(serviceInfo);
                resolveNext();
            }
        };
        String[] types = {SERVICE_IPP, SERVICE_RAW};
        for (final String type : types) {
            NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onDiscoveryStarted(String serviceType) {
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                }

                @Override
                public void onServiceFound(NsdServiceInfo serviceInfo) {
                    enqueue(serviceInfo);
                }

                @Override
                public void onServiceLost(NsdServiceInfo serviceInfo) {
                }
            };
            discoveryListeners.add(discoveryListener);
            try {
                nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            } catch (Exception ignored) {
                // 某些机型不允许注册某类服务，忽略即可，另一类服务仍可能扫到打印机。
            }
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                nsdDone = true;
                maybeFinish();
            }
        }, SCAN_WINDOW_MS);
    }

    /** 发现的服务排队解析：同一时刻只允许一个 resolve 在飞。 */
    private void enqueue(NsdServiceInfo serviceInfo) {
        if (serviceInfo == null || finished) {
            return;
        }
        synchronized (resolveQueue) {
            resolveQueue.add(serviceInfo);
        }
        resolveNext();
    }

    /**
     * 跨网段探测：手机与打印机常常不在同一个网段，mDNS 广播根本扫不到。
     *
     * 做法是取当前填写地址所在的 /24 网段，逐个地址试 631/9100 端口，
     * 开着打印端口的主机就是候选打印机——打印机换了 IP 时能直接把它找出来。
     */
    private void startSubnetSweep() {
        final String prefix = subnetPrefix(hintAddress);
        if (prefix == null) {
            sweepDone = true;
            maybeFinish();
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                sweepSubnet(prefix);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        sweepDone = true;
                        maybeFinish();
                    }
                });
            }
        });
        thread.setName("icu-printer-sweep");
        thread.start();
    }

    /** 扫完一整个 /24：先试 631，再对"主机在线但没开 IPP"的地址试 9100。 */
    private void sweepSubnet(String prefix) {
        final AtomicInteger cursor = new AtomicInteger(1);
        ExecutorService pool = Executors.newFixedThreadPool(SWEEP_CONCURRENCY);
        try {
            for (int worker = 0; worker < SWEEP_CONCURRENCY; worker++) {
                pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        int index;
                        while ((index = cursor.getAndIncrement()) <= 254) {
                            sweepHost(prefix + index);
                        }
                    }
                });
            }
            pool.shutdown();
            pool.awaitTermination(SWEEP_MAX_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
    }

    /** 单个地址：631 通就是 IPP 打印机；631 拒绝连接说明主机在线，再试 9100。 */
    private void sweepHost(String host) {
        PrinterDiagnostics.Probe ipp = PrinterDiagnostics.probe(host,
                PrinterEndpoint.DEFAULT_IPP_PORT, SWEEP_TIMEOUT_MS);
        if (ipp.connected) {
            addPrinter(new Printer("网络打印机", host + ":" + PrinterEndpoint.DEFAULT_IPP_PORT, "网段扫描"));
            return;
        }
        if (!ipp.hostAlive) {
            return;     // 主机不在线，没必要再试 9100
        }
        PrinterDiagnostics.Probe raw = PrinterDiagnostics.probe(host,
                PrinterEndpoint.RAW_PORT, SWEEP_TIMEOUT_MS);
        if (raw.connected) {
            addPrinter(new Printer("网络打印机", host + ":" + PrinterEndpoint.RAW_PORT, "网段扫描"));
        }
    }

    /** 扫到一台打印机：按地址去重，数量满了就不再收。 */
    private void addPrinter(Printer printer) {
        synchronized (printers) {
            if (printers.containsKey(printer.address) || printers.size() >= MAX_PRINTERS) {
                return;
            }
            printers.put(printer.address, printer);
        }
    }

    /** 取地址所在的 /24 网段前缀；地址不是 IPv4 字面量时返回 null。 */
    private static String subnetPrefix(String address) {
        String host = PrinterEndpoint.parse(address).host;   // 去掉协议前缀、端口与路径
        if (TextUtils.isEmpty(host)) {
            return null;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        for (String part : parts) {
            if (part.length() == 0 || part.length() > 3) {
                return null;
            }
            for (int i = 0; i < part.length(); i++) {
                char digit = part.charAt(i);
                if (digit < '0' || digit > '9') {
                    return null;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return null;
            }
        }
        return parts[0] + "." + parts[1] + "." + parts[2] + ".";
    }

    /** 广播与探测都结束后统一收尾。 */
    private void maybeFinish() {
        if (!nsdDone || !sweepDone) {
            return;
        }
        finish(null, null);
    }

    private void resolveNext() {
        if (finished || nsdManager == null || resolveListener == null) {
            return;
        }
        if (!resolving.compareAndSet(false, true)) {
            // 已有解析在飞，等它的回调再继续取下一个服务。
            return;
        }
        NsdServiceInfo serviceInfo;
        synchronized (resolveQueue) {
            serviceInfo = resolveQueue.poll();
        }
        if (serviceInfo == null) {
            resolving.set(false);
            return;
        }
        try {
            nsdManager.resolveService(serviceInfo, resolveListener);
        } catch (Exception exception) {
            resolving.set(false);
            handler.post(new Runnable() {
                @Override
                public void run() {
                    resolveNext();
                }
            });
        }
    }

    /** 把解析出来的服务转成「名字 + 地址」。没有地址的服务无法直连，直接丢弃。 */
    private void collect(NsdServiceInfo serviceInfo) {
        String host = null;
        try {
            host = serviceInfo.getHost() == null ? null : serviceInfo.getHost().getHostAddress();
        } catch (Exception ignored) {
        }
        if (TextUtils.isEmpty(host)) {
            return;
        }
        int port = serviceInfo.getPort();
        String address = port > 0 ? host + ":" + port : host;
        addPrinter(new Printer(serviceInfo.getServiceName(), address));
    }

    /** 扫描结束：停掉发现、回推结果、释放组播锁。 */
    private void finish(List<Printer> result, String error) {
        if (finished) {
            return;
        }
        finished = true;
        handler.removeCallbacksAndMessages(null);
        if (nsdManager != null) {
            for (NsdManager.DiscoveryListener discoveryListener : discoveryListeners) {
                try {
                    nsdManager.stopServiceDiscovery(discoveryListener);
                } catch (Exception ignored) {
                }
            }
        }
        releaseMulticastLock();
        List<Printer> snapshot;
        synchronized (printers) {
            snapshot = new ArrayList<>(printers.values());
        }
        if (listener != null) {
            listener.onScanFinished(result != null ? result : snapshot, error);
        }
    }

    /** 不持组播锁的机型收不到 mDNS 应答，这里显式申请。 */
    private void acquireMulticastLock() {
        try {
            Object service = context.getSystemService(Context.WIFI_SERVICE);
            if (!(service instanceof WifiManager)) {
                return;
            }
            multicastLock = ((WifiManager) service).createMulticastLock("icu-printer-scan");
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
        } catch (Exception ignored) {
            multicastLock = null;
        }
    }

    private void releaseMulticastLock() {
        if (multicastLock == null) {
            return;
        }
        try {
            if (multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception ignored) {
        }
        multicastLock = null;
    }
}
