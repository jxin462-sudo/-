package com.example.icuapk;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * PWG 光栅任务（IPP Everywhere 标准格式 image/pwg-raster）。
 *
 * 一大批只声明 image/pwg-raster 的 IPP 打印机不接受 PDF，也不认 PCL，
 * 只收这种「RaS2 同步字 + 1796 字节页头 + 未压缩 sRGB 行」的光栅数据。
 * 页头字段按 CUPS/PWG v2 的偏移写，固件靠它还原页面尺寸与颜色模型。
 */
final class PwgRasterJob {

    /** 默认分辨率：150dpi 足够看清表格，体积比 300dpi 小四倍。 */
    static final int DEFAULT_DPI = 150;

    /** 单页光栅体积上限：超出说明报告异常，不能把内存吃光。 */
    private static final long MAX_PAGE_BYTES = 128L * 1024L * 1024L;

    /** PWG v2 页头长度（不含最前面的 RaS2 同步字）。 */
    private static final int HEADER_BYTES = 1796;

    private PwgRasterJob() {
    }

    /** 报告总页数。 */
    static int pageCount(File pdf) throws IOException {
        ParcelFileDescriptor descriptor =
                ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            PdfRenderer renderer = new PdfRenderer(descriptor);
            try {
                return renderer.getPageCount();
            } finally {
                renderer.close();
            }
        } finally {
            closeQuietly(descriptor);
        }
    }

    /**
     * 生成某一页的 PWG 光栅。
     *
     * @param pageIndex 页码，0 起
     * @param dpi       光栅分辨率
     */
    static byte[] buildPage(File pdf, int pageIndex, int dpi) throws IOException {
        if (dpi < 72 || dpi > 600) {
            throw new IOException("PWG 分辨率无效: " + dpi);
        }
        ParcelFileDescriptor descriptor =
                ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            PdfRenderer renderer = new PdfRenderer(descriptor);
            try {
                if (pageIndex < 0 || pageIndex >= renderer.getPageCount()) {
                    throw new IOException("PWG 页码越界: " + pageIndex);
                }
                PdfRenderer.Page page = renderer.openPage(pageIndex);
                try {
                    float scale = dpi / 72f;
                    int width = Math.max(1, Math.round(page.getWidth() * scale));
                    int height = Math.max(1, Math.round(page.getHeight() * scale));
                    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    try {
                        bitmap.eraseColor(Color.WHITE);
                        Matrix transform = new Matrix();
                        transform.setScale(scale, scale);
                        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                        return buildPage(bitmap, page.getWidth(), page.getHeight());
                    } finally {
                        bitmap.recycle();
                    }
                } finally {
                    page.close();
                }
            } finally {
                renderer.close();
            }
        } finally {
            closeQuietly(descriptor);
        }
    }

    /** 位图转 PWG 光栅页：同步字 + 页头 + 未压缩的 sRGB 行。 */
    private static byte[] buildPage(Bitmap bitmap, int widthPoints, int heightPoints) throws IOException {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int rowBytes = width * 3;
        long total = 4L + HEADER_BYTES + (long) rowBytes * height;
        if (total > MAX_PAGE_BYTES) {
            throw new IOException("PWG 单页光栅过大: " + total);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) total);
        out.write(buildHeader(width, height, widthPoints, heightPoints, rowBytes));
        int[] pixels = new int[width];
        byte[] row = new byte[rowBytes];
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                int color = pixels[x];
                int offset = x * 3;
                row[offset] = (byte) ((color >> 16) & 0xFF);
                row[offset + 1] = (byte) ((color >> 8) & 0xFF);
                row[offset + 2] = (byte) (color & 0xFF);
            }
            out.write(row);
        }
        return out.toByteArray();
    }

    /** CUPS/PWG v2 页头：偏移都相对 1796 字节页头，最前面另加 RaS2 同步字。 */
    private static byte[] buildHeader(int widthPx, int heightPx, int widthPoints, int heightPoints,
                                      int bytesPerLine) {
        byte[] header = new byte[4 + HEADER_BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(0, 0x52615332);       // "RaS2" = PWG Raster
        buffer.putInt(4 + 276, 150);        // HWResolution.x
        buffer.putInt(4 + 280, 150);        // HWResolution.y
        buffer.putInt(4 + 340, 1);          // NumCopies
        buffer.putInt(4 + 352, widthPoints);
        buffer.putInt(4 + 356, heightPoints);
        buffer.putInt(4 + 372, widthPx);    // cupsWidth
        buffer.putInt(4 + 376, heightPx);   // cupsHeight
        buffer.putInt(4 + 384, 8);          // cupsBitsPerColor
        buffer.putInt(4 + 388, 24);         // cupsBitsPerPixel
        buffer.putInt(4 + 392, bytesPerLine);
        buffer.putInt(4 + 396, 0);          // cupsColorOrder = chunked（RGB RGB ...）
        buffer.putInt(4 + 400, 19);         // cupsColorSpace = sRGB
        buffer.putInt(4 + 404, 0);          // cupsCompression = none
        buffer.putInt(4 + 420, 3);          // cupsNumColors
        buffer.putFloat(4 + 424, 1.0f);     // cupsBorderlessScalingFactor
        buffer.putFloat(4 + 428, widthPoints);
        buffer.putFloat(4 + 432, heightPoints);
        return header;
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        try {
            descriptor.close();
        } catch (IOException ignored) {
        }
    }
}
