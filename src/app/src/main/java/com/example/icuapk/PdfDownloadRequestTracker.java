package com.example.icuapk;

/** PDF 下载请求分代：只有当前请求能推进阶段或写最终结果。 */
final class PdfDownloadRequestTracker {
    private long nextRequestId;
    private long activeRequestId;

    synchronized long begin() {
        nextRequestId++;
        if (nextRequestId <= 0L) {
            nextRequestId = 1L;
        }
        activeRequestId = nextRequestId;
        return activeRequestId;
    }

    synchronized boolean isActive(long requestId) {
        return requestId > 0L && requestId == activeRequestId;
    }

    /** 成功、失败或排队超时都只能结束一次；旧请求不能覆盖新请求。 */
    synchronized boolean finish(long requestId) {
        if (!isActive(requestId)) {
            return false;
        }
        activeRequestId = 0L;
        return true;
    }
}
