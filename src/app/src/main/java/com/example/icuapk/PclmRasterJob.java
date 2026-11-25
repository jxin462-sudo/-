package com.example.icuapk;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PCLm 光栅任务：把报告光栅成 PCLm 认识的「分条光栅 PDF」。
 *
 * PCLm（HP LaserJet Pro MFP M1213nf 等机型原生声明 application/PCLm）
 * 既不是 PCL5，也不能把原 PDF 改名发过去：
 * 固件会照单全收却什么都不打，任务压进队列，纸一直不出。
 * PCLm 要求的是一份 PDF 语法的文档，页面由若干条 8 位灰度条带拼成，
 * 每条带 RunLength 编码；固件按 pclm-source-resolution 与
 * pclm-strip-height-preferred 这两个能力参数还原这些条带。
 *
 * 一次只渲染一条条带：600dpi 的 A4 整页 ARGB 位图要 130MB 以上，
 * 分条渲染才扛得住平板内存。
 */
final class PclmRasterJob {

    /** 打印机没上报分辨率时的默认值。 */
    static final int DEFAULT_DPI = 300;

    /** 打印机没上报条带高度时的默认值。 */
    static final int DEFAULT_STRIP_HEIGHT = 128;

    /**
     * PCLm 用户单位：1 用户单位 = 1/144 英寸。
     *
     * <p>PDF 语法里 1 用户单位是 1/72 英寸，PCLm 不是：固件按 1/144 英寸
     * 摆放页面。于是按 1/72 写的 A4 页面（595×842 单位）在纸上只有
     * 595/144 = 4.13 英寸宽，正好是 A4 的一半——这就是"报告只占 A4 四分之一、
     * 缩在左上角"的原因（HP LaserJet Pro MFP M1213nf 固件实测）。</p>
     *
     * <p>因此这里整页坐标乘 2：页面框与内容一起放大，纸面才是满页 A4。</p>
     */
    private static final double USER_UNITS_PER_INCH = 144.0;

    /** 单页编码体积上限：报告异常时不能把内存吃光。 */
    private static final long MAX_PAGE_BYTES = 64L * 1024L * 1024L;

    private PclmRasterJob() {
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
     * 生成某一页的 PCLm 文档。
     *
     * @param pageIndex   页码，0 起
     * @param dpi         光栅分辨率，按打印机上报的能力取
     * @param stripHeight 每条条带的行数，按打印机上报的能力取
     */
    static byte[] buildPage(File pdf, int pageIndex, int dpi, int stripHeight) throws IOException {
        if (dpi < 72 || dpi > 1200) {
            throw new IOException("PCLm 分辨率无效: " + dpi);
        }
        if (stripHeight < 16 || stripHeight > 512) {
            throw new IOException("PCLm 条带高度无效: " + stripHeight);
        }
        ParcelFileDescriptor descriptor =
                ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            PdfRenderer renderer = new PdfRenderer(descriptor);
            try {
                if (pageIndex < 0 || pageIndex >= renderer.getPageCount()) {
                    throw new IOException("PCLm 页码越界: " + pageIndex);
                }
                PdfRenderer.Page page = renderer.openPage(pageIndex);
                try {
                    return buildPage(page, dpi, stripHeight);
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

    /** 页面尺寸（点），用来挑选 IPP 媒体名。 */
    static int[] pageSize(File pdf, int pageIndex) throws IOException {
        ParcelFileDescriptor descriptor =
                ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            PdfRenderer renderer = new PdfRenderer(descriptor);
            try {
                if (pageIndex < 0 || pageIndex >= renderer.getPageCount()) {
                    throw new IOException("PCLm 页码越界: " + pageIndex);
                }
                PdfRenderer.Page page = renderer.openPage(pageIndex);
                try {
                    return new int[]{Math.round(page.getWidth()), Math.round(page.getHeight())};
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

    /** IPP 媒体名：A4 与 Letter 认得出来，其余不写媒体名，让打印机用默认纸张。 */
    static String mediaName(int widthPoints, int heightPoints) {
        if (Math.abs(widthPoints - 595f) <= 3f && Math.abs(heightPoints - 842f) <= 3f) {
            return "iso_a4_210x297mm";
        }
        if (Math.abs(widthPoints - 612f) <= 3f && Math.abs(heightPoints - 792f) <= 3f) {
            return "na_letter_8.5x11in";
        }
        return null;
    }

    /** 把一页渲染成若干条灰度条带，再拼成 PCLm 文档。 */
    private static byte[] buildPage(PdfRenderer.Page page, int dpi, int stripHeight) throws IOException {
        int widthPx = Math.max(1, Math.round(page.getWidth() * dpi / 72f));
        int heightPx = Math.max(1, Math.round(page.getHeight() * dpi / 72f));
        int stripCount = (heightPx + stripHeight - 1) / stripHeight;
        if (widthPx > 12000 || heightPx > 40000 || (long) widthPx * stripHeight > 8000000L) {
            throw new IOException("PCLm 页面尺寸过大: " + widthPx + "x" + heightPx);
        }
        List<byte[]> strips = new ArrayList<>(stripCount);
        boolean[] white = new boolean[stripCount];
        float scale = dpi / 72f;
        for (int index = 0; index < stripCount; index++) {
            int top = index * stripHeight;
            int rows = Math.min(stripHeight, heightPx - top);
            Bitmap strip = Bitmap.createBitmap(widthPx, rows, Bitmap.Config.ARGB_8888);
            try {
                strip.eraseColor(Color.WHITE);
                Matrix transform = new Matrix();
                // 平移量用设备像素：这条条带取的是页面 [top, top + rows) 这一段。
                transform.setValues(new float[]{
                        scale, 0, 0,
                        0, scale, -top,
                        0, 0, 1});
                page.render(strip, new Rect(0, 0, widthPx, rows), transform,
                        PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                byte[] gray = toGray(strip);
                white[index] = isAllWhite(gray);
                strips.add(encodeRunLength(gray));
            } finally {
                strip.recycle();
            }
        }
        return buildDocument(widthPx, heightPx, dpi, stripHeight, strips, white);
    }

    /** 位图转 8 位灰度：白=255、黑=0，PCLm 条带按灰度打印。 */
    private static byte[] toGray(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        byte[] gray = new byte[width * height];
        int[] pixels = new int[width];
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1);
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int color = pixels[x];
                int r = (color >> 16) & 0xFF;
                int g = (color >> 8) & 0xFF;
                int b = color & 0xFF;
                gray[row + x] = (byte) ((r * 77 + g * 150 + b * 29) >> 8);
            }
        }
        return gray;
    }

    /** 整条都是白的话不必让固件去解压，直接标成空白条带。 */
    private static boolean isAllWhite(byte[] gray) {
        for (byte value : gray) {
            if ((value & 0xFF) != 255) {
                return false;
            }
        }
        return true;
    }

    /** PDF RunLengthDecode：0..127 是字面长度-1，129..255 是 257-重复长度，128 结束。 */
    private static byte[] encodeRunLength(byte[] input) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length + input.length / 128 + 16);
        int index = 0;
        while (index < input.length) {
            int run = 1;
            while (index + run < input.length && run < 128 && input[index] == input[index + run]) {
                run++;
            }
            if (run >= 2) {
                out.write(257 - run);
                out.write(input[index]);
                index += run;
                continue;
            }
            int literalStart = index;
            index++;
            while (index < input.length && index - literalStart < 128) {
                if (index + 1 < input.length && input[index] == input[index + 1]) {
                    break;
                }
                index++;
            }
            int literal = index - literalStart;
            out.write(literal - 1);
            out.write(input, literalStart, literal);
        }
        out.write(128);
        return out.toByteArray();
    }

    /**
     * 生成单页 PCLm 文档。
     *
     * 对象编号固定：1=目录、2=页树、3=页面、4=页面内容，
     * 之后每条条带两个对象（图像 + 绘制指令）。
     */
    private static byte[] buildDocument(int widthPx, int heightPx, int dpi, int stripHeight,
                                        List<byte[]> strips, boolean[] whiteStrips) throws IOException {
        int expected = (heightPx + stripHeight - 1) / stripHeight;
        if (widthPx <= 0 || heightPx <= 0 || dpi <= 0
                || strips.size() != expected || whiteStrips.length != expected) {
            throw new IOException("PCLm 条带数量或页面参数无效");
        }
        long payloadBytes = 0;
        for (byte[] strip : strips) {
            payloadBytes += strip.length;
        }
        if (payloadBytes > MAX_PAGE_BYTES) {
            throw new IOException("PCLm 单页编码过大: " + payloadBytes);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) payloadBytes + 16384);
        int nextObject = 5 + expected * 2;
        int[] offsets = new int[nextObject];
        writeAscii(out, "%PDF-1.7\n%PCLm 1.0\n");
        StringBuilder resources = new StringBuilder();
        StringBuilder content = new StringBuilder(String.format(Locale.US,
                "%.8f 0 0 %.8f 0 0 cm\n",
                USER_UNITS_PER_INCH / dpi, USER_UNITS_PER_INCH / dpi));
        for (int i = 0; i < expected; i++) {
            int rows = Math.min(stripHeight, heightPx - i * stripHeight);
            int y = heightPx - (i + 1) * stripHeight;
            resources.append("/Image").append(i).append(' ').append(5 + i * 2).append(" 0 R ");
            content.append("/P <</MCID 0>> BDC q\n")
                    .append(widthPx).append(" 0 0 ").append(rows).append(" 0 ")
                    .append(Math.max(0, y)).append(" cm\n")
                    .append("/Image").append(i).append(" Do Q EMC\n");
        }
        // 页面框与内容用的是同一套用户单位，两者必须一起换算，
        // 否则内容会被页面框裁掉一半。
        int mediaWidth = (int) Math.round(widthPx * USER_UNITS_PER_INCH / dpi);
        int mediaHeight = (int) Math.round(heightPx * USER_UNITS_PER_INCH / dpi);
        offsets[3] = out.size();
        writeAscii(out, "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [ 0 0 "
                + mediaWidth + " " + mediaHeight + " ] /Resources << /XObject << "
                + resources + ">> >> /Contents [ 4 0 R ] >>\nendobj\n");
        writeStream(out, offsets, 4, "", content.toString().getBytes("US-ASCII"));
        for (int i = 0; i < expected; i++) {
            int rows = Math.min(stripHeight, heightPx - i * stripHeight);
            String attributes = "/Type /XObject\n/Subtype /Image\n/Width " + widthPx
                    + " \n/Height " + rows + " \n/ColorSpace /DeviceGray\n/Name /"
                    + (whiteStrips[i] ? "WhiteStrip" : "ColorStrip")
                    + "\n/Filter /RunLengthDecode\n/BitsPerComponent 8 \n";
            writeStream(out, offsets, 5 + i * 2, attributes, strips.get(i));
            writeStream(out, offsets, 6 + i * 2, "", "q /Image Do Q\n".getBytes("US-ASCII"));
        }
        offsets[1] = out.size();
        writeAscii(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        offsets[2] = out.size();
        writeAscii(out, "2 0 obj\n<< /Type /Pages /Count 1 /Kids [3 0 R] >>\nendobj\n");
        int xref = out.size();
        writeAscii(out, "xref\n0 " + nextObject + "\n0000000000 65535 f \n");
        for (int i = 1; i < nextObject; i++) {
            if (offsets[i] <= 0) {
                throw new IOException("PCLm 对象偏移缺失: " + i);
            }
            writeAscii(out, String.format(Locale.US, "%010d 00000 n \n", offsets[i]));
        }
        writeAscii(out, "trailer\n<< /Size " + nextObject
                + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    /** 写一个 PDF 流对象，并记录它的起始偏移供 xref 使用。 */
    private static void writeStream(ByteArrayOutputStream out, int[] offsets, int object,
                                    String attributes, byte[] data) throws IOException {
        offsets[object] = out.size();
        writeAscii(out, object + " 0 obj\n<<\n/Length " + data.length + " \n"
                + attributes + ">>\nstream\n");
        out.write(data, 0, data.length);
        writeAscii(out, "\nendstream\nendobj\n");
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes("US-ASCII");
        out.write(bytes, 0, bytes.length);
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        try {
            descriptor.close();
        } catch (IOException ignored) {
        }
    }
}
