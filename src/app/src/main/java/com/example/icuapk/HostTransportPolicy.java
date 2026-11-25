package com.example.icuapk;

/** 主机首轮环境查询与写入启动重试的纯调度决策，便于 JVM 回归。 */
final class HostTransportPolicy {
    /** 每种写入方式都明确未启动也算一次；达到上限后必须放行后续回读。 */
    static final int MAX_WRITE_START_FAILURES = 5;

    private HostTransportPolicy() {
    }

    /** 只有 writeCharacteristic 明确返回 false 才调用，绝不用于已发出但回执不明的指令。 */
    static boolean shouldRetryWriteStart(int failureCount) {
        return failureCount > 0 && failureCount < MAX_WRITE_START_FAILURES;
    }

    /** 支持带响应写入时优先使用；两种属性都没有时也保持 DEFAULT，由平台明确返回失败。 */
    static boolean preferWriteWithResponse(boolean canWriteWithResponse, boolean canWriteNoResponse) {
        return canWriteWithResponse || !canWriteNoResponse;
    }

    /**
     * 首次总表尚未回答时，初始 null 只表示“还没读”，不等于总表缺字段。
     * 有总表回答、查询超时、明确不支持或上层强制回退时才补四条分项查询。
     */
    static boolean shouldRequestEnvironmentFallback(boolean force, boolean totalTableUnsupported,
                                                      boolean hasStatusReply, boolean statusTimedOut) {
        return force || totalTableUnsupported || hasStatusReply || statusTimedOut;
    }
}
