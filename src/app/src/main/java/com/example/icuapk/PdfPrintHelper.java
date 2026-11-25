package com.example.icuapk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintJob;
import android.print.PrintManager;
import android.widget.Toast;

import java.io.Closeable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 系统打印：把已经生成好的 PDF 交给系统打印框架。
 * 实现取自 jxin462-sudo/icu（IcuDashboardView.printReportViaSystem 与 PdfPrintAdapter）：
 * 用 PrintDocumentAdapter 把 PDF 原样写进打印任务，系统弹出打印机选择界面，
 * 任务结束后回推成功 / 失败 / 取消状态。
 */
final class PdfPrintHelper {

    /** 设备上没有可用打印服务时的固定文案：调用方据此决定是否改走"保存到下载目录"。 */
    static final String NO_PRINT_SERVICE = "系统没有可用的打印服务";

    /** 打印结果回调（主线程）。 */
    interface ResultListener {
        void onPrintResult(boolean ok, String message);
    }

    /** 打印任务轮询间隔与最长等待时间。 */
    private static final long POLL_INTERVAL_MS = 1000L;
    private static final long POLL_TIMEOUT_MS = 60000L;

    private PdfPrintHelper() {
    }

    /**
     * 打印 PDF 文件。
     *
     * @param context  上下文，用于调起系统打印界面
     * @param pdfFile  要打印的 PDF
     * @param copies   打印份数，小于 1 按 1 份处理
     * @param listener 结果回调，可为空
     */
    static void print(Context context, File pdfFile, int copies, ResultListener listener) {
        if (context == null) {
            return;
        }
        if (pdfFile == null || !pdfFile.exists()) {
            finish(context, listener, false, "打印失败：PDF文件不存在，无法打印");
            return;
        }
        Object service = context.getSystemService(Context.PRINT_SERVICE);
        if (!(service instanceof PrintManager)) {
            finish(context, listener, false, NO_PRINT_SERVICE);
            return;
        }
        PrintManager printManager = (PrintManager) service;
        int total = copies < 1 ? 1 : copies;
        List<PrintJob> jobs = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            String jobName = "动物ICU监护报告_" + pdfFile.getName() + (total > 1 ? "_" + i : "");
            PrintJob job;
            try {
                job = printManager.print(jobName, new PdfPrintAdapter(pdfFile), null);
            } catch (RuntimeException exception) {
                // 设备上没有打印服务时 print() 会直接抛异常，
                // 转成固定文案，调用方据此把报告存进下载目录。
                finish(context, listener, false, NO_PRINT_SERVICE);
                return;
            }
            if (job != null) {
                jobs.add(job);
            }
        }
        if (jobs.isEmpty()) {
            finish(context, listener, false, "打印失败：打印任务创建失败");
            return;
        }
        watchJobs(context, jobs, listener);
    }

    /** 轮询打印任务，直到全部完成 / 失败 / 取消，最长等待 60 秒。 */
    private static void watchJobs(final Context context, final List<PrintJob> jobs, final ResultListener listener) {
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            private long elapsed = 0;

            @Override
            public void run() {
                boolean allTerminal = true;
                boolean allOk = true;
                String reason = "";
                for (PrintJob job : jobs) {
                    if (job == null) {
                        continue;
                    }
                    if (job.isCompleted()) {
                        continue;
                    }
                    allOk = false;
                    if (job.isFailed()) {
                        reason = "打印失败：系统打印任务被打印机拒绝";
                    } else if (job.isCancelled()) {
                        reason = "打印已取消：用户在系统打印界面取消了任务";
                    } else {
                        allTerminal = false;
                    }
                }
                if (allTerminal) {
                    finish(context, listener, allOk, allOk ? "打印完成：系统打印任务已处理" : reason);
                    return;
                }
                elapsed += POLL_INTERVAL_MS;
                if (elapsed >= POLL_TIMEOUT_MS) {
                    finish(context, listener, false, "打印失败：等待系统打印服务超时，请检查打印机");
                    return;
                }
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }, POLL_INTERVAL_MS);
    }

    /** 统一收口：弹提示并回推结果。 */
    private static void finish(Context context, ResultListener listener, boolean ok, String message) {
        String text = message == null ? "" : message;
        if (context != null) {
            Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_LONG).show();
        }
        if (listener != null) {
            listener.onPrintResult(ok, text);
        }
    }

    /**
     * 把 PDF 交给打印框架：整份打印时原样输出（文字保持清晰），
     * 只打印部分页时按选中的页重新生成一份 PDF，避免多打或者打错页。
     */
    private static final class PdfPrintAdapter extends PrintDocumentAdapter {
        private final File pdfFile;

        PdfPrintAdapter(File pdfFile) {
            this.pdfFile = pdfFile;
        }

        @Override
        public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                             CancellationSignal cancellationSignal, LayoutResultCallback callback, Bundle extras) {
            if (cancellationSignal.isCanceled()) {
                callback.onLayoutCancelled();
                return;
            }
            PrintDocumentInfo info = new PrintDocumentInfo.Builder(pdfFile.getName())
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pageCountOf(pdfFile))
                    .build();
            callback.onLayoutFinished(info, true);
        }

        @Override
        public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                             CancellationSignal cancellationSignal, WriteResultCallback callback) {
            OutputStream output = null;
            try {
                byte[] content = buildPrintContent(pdfFile, pages);
                output = new FileOutputStream(destination.getFileDescriptor());
                output.write(content);
                output.flush();
                callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
            } catch (Exception exception) {
                callback.onWriteFailed(exception.getMessage() == null ? "写入打印数据失败" : exception.getMessage());
            } finally {
                closeQuietly(output);
            }
        }
    }

    /**
     * 打印内容：整份打印时原样输出 PDF 字节；
     * 只打印部分页时按选中的页重新生成 PDF，保证打印出来的就是用户选的那几页。
     */
    private static byte[] buildPrintContent(File pdfFile, PageRange[] pages) throws IOException {
        if (pages == null || pages.length == 0 || containsAllPages(pages)) {
            return readAllBytes(pdfFile);
        }
        int[] indexes = collectPageIndexes(pages);
        if (indexes.length == 0) {
            return readAllBytes(pdfFile);
        }
        return renderPages(pdfFile, indexes);
    }

    /** 打印页范围里是否包含"全部页"。 */
    private static boolean containsAllPages(PageRange[] pages) {
        for (PageRange range : pages) {
            if (PageRange.ALL_PAGES.equals(range)) {
                return true;
            }
        }
        return false;
    }

    /** 把打印页范围换算成页序号（从 0 开始）。 */
    private static int[] collectPageIndexes(PageRange[] pages) {
        List<Integer> indexes = new ArrayList<>();
        for (PageRange range : pages) {
            if (range == null || PageRange.ALL_PAGES.equals(range)) {
                continue;
            }
            int start = Math.max(0, range.getStart());
            int end = range.getEnd();
            if (end == Integer.MAX_VALUE) {
                end = start;
            }
            for (int page = start; page <= end; page++) {
                if (!indexes.contains(page)) {
                    indexes.add(page);
                }
            }
        }
        int[] result = new int[indexes.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = indexes.get(i);
        }
        return result;
    }

    /** PDF 总页数；读不到时返回 -1，打印框架按"页数未知"处理。 */
    private static int pageCountOf(File pdfFile) {
        ParcelFileDescriptor descriptor = null;
        PdfRenderer renderer = null;
        try {
            descriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(descriptor);
            return renderer.getPageCount();
        } catch (Throwable throwable) {
            return -1;
        } finally {
            if (renderer != null) {
                renderer.close();
            }
            closeDescriptor(descriptor);
        }
    }

    /** 读取整个 PDF 的字节。 */
    private static byte[] readAllBytes(File pdfFile) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FileInputStream input = new FileInputStream(pdfFile);
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

    /** 按页序号重新生成一份只含这些页的 PDF。 */
    private static byte[] renderPages(File pdfFile, int[] pageIndexes) throws IOException {
        PdfDocument document = new PdfDocument();
        ParcelFileDescriptor descriptor = null;
        PdfRenderer renderer = null;
        try {
            descriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(descriptor);
            int available = renderer.getPageCount();
            for (int index : pageIndexes) {
                if (index < 0 || index >= available) {
                    continue;
                }
                writeRenderedPage(document, renderer, index);
            }
        } finally {
            if (renderer != null) {
                renderer.close();
            }
            closeDescriptor(descriptor);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            document.writeTo(output);
        } finally {
            document.close();
        }
        return output.toByteArray();
    }

    /** 把 PDF 的一页光栅化后写进新文档，页面尺寸保持与原报告一致。 */
    private static void writeRenderedPage(PdfDocument document, PdfRenderer renderer, int index) {
        PdfRenderer.Page page = renderer.openPage(index);
        Bitmap bitmap = null;
        try {
            int width = page.getWidth();
            int height = page.getHeight();
            // 页面尺寸单位是点，这里按 2 倍分辨率渲染再缩回原尺寸，打印出来更清晰。
            float scale = 2f;
            bitmap = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)),
                    Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
                Matrix matrix = new Matrix();
                matrix.setScale(scale, scale);
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
            PdfDocument.Page target = document.startPage(
                    new PdfDocument.PageInfo.Builder(width, height, index + 1).create());
            target.getCanvas().drawBitmap(bitmap, null, new Rect(0, 0, width, height), null);
            document.finishPage(target);
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
            page.close();
        }
    }

    private static void closeDescriptor(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (Exception ignored) {
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
