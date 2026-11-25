package com.example.icuapk;

/** 护理模式批量入队计数；只描述 App 队列，不代表主机已经执行。 */
final class HostModeBatchProgress {
    int attempted;
    int enqueued;
    int skipped;

    void record(boolean commandEnqueued) {
        attempted++;
        if (commandEnqueued) {
            enqueued++;
        }
    }

    void skip() {
        attempted++;
        skipped++;
    }

    /** 前置命令全部入队，才允许最后发送 set_care_mode。 */
    boolean canSendCareMode() {
        return attempted == enqueued && skipped == 0;
    }

    boolean isComplete() {
        return attempted > 0 && attempted == enqueued && skipped == 0;
    }
}
