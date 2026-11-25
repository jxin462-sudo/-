package com.example.icuapk;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URF 光栅任务（Apple Raster / UNIRAST，IPP 格式 image/urf）。
 *
 * AirPrint 机型常常只声明 image/urf：PDF 会被回 0x040A 拒收，
 * 往 9100 端口写 PCL 也会被静默丢掉，只有这种 UNIRAST 光栅才打得出来。
 *
 * 版式：12 字节 UNIRAST 文件头 + 32 字节页头，
 * 之后每组相同行先写「重复次数-1」，再写该行的 CUPS modified PackBits 数据，
 * 空白区域靠这个重复计数压掉。
 */
final class UrfRasterJob {

    /** 打印机没上报能力时的默认分辨率。 */
    static final int DEFAULT_DPI = 300;

    /** 默认位深：24 位 sRGB。 */
    static final int DEFAULT_BITS_PER_PIXEL = 24;

    /** 单页光栅体积上限：报告异常时不能把内存吃光。 */
    private static final long MAX_PAGE_BYTES = 128L * 1024L * 1024L;

    /** 文件头 + 页头总长。 */
    private static final int HEADER_BYTES = 44;

    private UrfRasterJob() {
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
     * 生成某一页的 URF 光栅。
     *
     * @param pageIndex    页码，0 起
     * @param dpi          光栅分辨率
     * @param bitsPerPixel 24（sRGB）或 8（灰度）
     */
    static byte[] buildPage(File pdf, int pageIndex, int dpi, int bitsPerPixel) throws IOException {
        if (dpi < 72 || dpi > 1200) {
            throw new IOException("URF 分辨率无效: " + dpi);
        }
        if (bitsPerPixel != 24 && bitsPerPixel != 8) {
            throw new IOException("URF 位深无效: " + bitsPerPixel);
        }
        ParcelFileDescriptor descriptor =
                ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            PdfRenderer renderer = new PdfRenderer(descriptor);
            try {
                if (pageIndex < 0 || pageIndex >= renderer.getPageCount()) {
                    throw new IOException("URF 页码越界: " + pageIndex);
                }
                PdfRenderer.Page page = renderer.openPage(pageIndex);
                try {
                    int width = Math.max(1, Math.round(page.getWidth() * dpi / 72f));
                    int height = Math.max(1, Math.round(page.getHeight() * dpi / 72f));
                    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    try {
                        bitmap.eraseColor(Color.WHITE);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                        return buildRasterPage(bitmap, dpi, bitsPerPixel);
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

    /**
     * 从 urf-supported 里解析 {dpi, bitsPerPixel}。
     *
     * urf-supported 形如 "SRGB24, W8, RS150, RS300"：
     * RS 后面跟的是它支持的分辨率，优先挑 150（省内存），其次 300；
     * 声明了 SRGB24 就用 24 位彩色，只有 W8 才降到 8 位灰度。
     */
    static int[] parseCapabilities(String urfSupported) {
        String text = urfSupported == null ? "" : urfSupported;
        int dpi = DEFAULT_DPI;
        int first = -1;
        boolean has150 = false;
        boolean has300 = false;
        Matcher matcher = Pattern.compile("(?:^|[^A-Za-z0-9])RS([0-9]+(?:-[0-9]+)*)").matcher(text);
        while (matcher.find()) {
            for (String item : matcher.group(1).split("-")) {
                try {
                    int candidate = Integer.parseInt(item);
                    if (candidate < 72 || candidate > 1200) {
                        continue;
                    }
                    if (first < 0) {
                        first = candidate;
                    }
                    has150 |= candidate == 150;
                    has300 |= candidate == 300;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (first > 0) {
            if (has150) {
                dpi = 150;
            } else if (has300) {
                dpi = 300;
            } else {
                dpi = first;
            }
        }
        int bitsPerPixel = !text.contains("SRGB24") && text.contains("W8") ? 8 : DEFAULT_BITS_PER_PIXEL;
        return new int[]{dpi, bitsPerPixel};
    }

    /** 位图转 UNIRAST 光栅页：文件头 + 页头 + 逐行 PackBits。 */
    private static byte[] buildRasterPage(Bitmap bitmap, int dpi, int bitsPerPixel) throws IOException {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int bytesPerPixel = bitsPerPixel / 8;
        int rowBytes = width * bytesPerPixel;
        long rawSize = HEADER_BYTES + (long) rowBytes * height;
        if (rawSize > MAX_PAGE_BYTES) {
            throw new IOException("URF 单页光栅过大: " + rawSize);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) rawSize);
        out.write(buildHeader(width, height, dpi, bitsPerPixel));
        int[] pixels = new int[width];
        byte[] row = new byte[rowBytes];
        byte[] previous = null;
        int repeat = 0;
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                int color = pixels[x];
                int red = (color >> 16) & 0xFF;
                int green = (color >> 8) & 0xFF;
                int blue = color & 0xFF;
                if (bytesPerPixel == 3) {
                    int offset = x * 3;
                    row[offset] = (byte) red;
                    row[offset + 1] = (byte) green;
                    row[offset + 2] = (byte) blue;
                } else {
                    row[x] = (byte) ((red * 77 + green * 150 + blue * 29) >> 8);
                }
            }
            if (previous != null && repeat < 256 && Arrays.equals(previous, row)) {
                repeat++;
                continue;
            }
            if (previous != null) {
                out.write(repeat - 1);
                out.write(encodePackBitsRow(previous, bytesPerPixel));
            }
            previous = Arrays.copyOf(row, row.length);
            repeat = 1;
        }
        if (previous != null) {
            out.write(repeat - 1);
            out.write(encodePackBitsRow(previous, bytesPerPixel));
        }
        if (out.size() > MAX_PAGE_BYTES) {
            throw new IOException("URF 单页编码过大: " + out.size());
        }
        return out.toByteArray();
    }

    /** 12 字节 UNIRAST 文件头 + 32 字节页头。 */
    private static byte[] buildHeader(int widthPx, int heightPx, int dpi, int bitsPerPixel) {
        byte[] header = new byte[HEADER_BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(0, 0x554E4952);          // "UNIR"
        header[4] = 'A';
        header[5] = 'S';
        header[6] = 'T';
        header[7] = 0;
        buffer.putInt(8, 1);                   // 一个 IPP 任务只含当前一页
        int page = 12;
        header[page] = (byte) bitsPerPixel;
        header[page + 1] = (byte) (bitsPerPixel == 24 ? 1 : 4);   // sRGB / W 灰度
        header[page + 2] = 1;                 // 单面
        buffer.putInt(page + 12, widthPx);
        buffer.putInt(page + 16, heightPx);
        buffer.putInt(page + 20, dpi);
        return header;
    }

    /** CUPS modified PackBits：重复像素控制值=count-1，字面像素控制值=257-count。 */
    private static byte[] encodePackBitsRow(byte[] row, int bytesPerPixel) throws IOException {
        if (bytesPerPixel < 1 || row.length == 0 || row.length % bytesPerPixel != 0) {
            throw new IOException("URF 行格式无效");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(row.length + row.length / 128 + 8);
        int pixels = row.length / bytesPerPixel;
        int index = 0;
        while (index < pixels) {
            int run = 1;
            while (index + run < pixels && run < 128 && pixelEquals(row, index, index + run, bytesPerPixel)) {
                run++;
            }
            if (run >= 2) {
                out.write(run - 1);
                out.write(row, index * bytesPerPixel, bytesPerPixel);
                index += run;
                continue;
            }
            int literalStart = index;
            index++;
            while (index < pixels && index - literalStart < 128) {
                if (index + 1 < pixels && pixelEquals(row, index, index + 1, bytesPerPixel)) {
                    break;
                }
                index++;
            }
            int literal = index - literalStart;
            out.write(257 - literal);
            out.write(row, literalStart * bytesPerPixel, literal * bytesPerPixel);
        }
        return out.toByteArray();
    }

    private static boolean pixelEquals(byte[] row, int a, int b, int bytesPerPixel) {
        int left = a * bytesPerPixel;
        int right = b * bytesPerPixel;
        for (int i = 0; i < bytesPerPixel; i++) {
            if (row[left + i] != row[right + i]) {
                return false;
            }
        }
        return true;
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        try {
            descriptor.close();
        } catch (IOException ignored) {
        }
    }
}
