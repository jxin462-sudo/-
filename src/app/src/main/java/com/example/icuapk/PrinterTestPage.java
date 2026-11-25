package com.example.icuapk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 打印测试页：生成一页带打印机地址与时间的 PDF，
 * 用来在真正打印报告之前确认打印目标是否可用。
 *
 * 现状：打印设置弹窗里的"打印测试页"入口已按产品要求移除，
 * 本类暂时没有调用方，作为打印链路自检能力保留，需要时直接调用 create 即可。
 */
final class PrinterTestPage {

    private PrinterTestPage() {
    }

    /** 生成测试页 PDF 到应用缓存目录。 */
    static File create(Context context, String printerAddress) throws IOException {
        PdfDocument document = new PdfDocument();
        try {
            PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(595, 842, 1).create());
            Canvas canvas = page.getCanvas();
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);
            paint.setTextSize(22);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            canvas.drawText("动物ICU 打印测试页", 60, 130, paint);

            paint.setTextSize(12);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
            canvas.drawText("打印机地址: " + (TextUtils.isEmpty(printerAddress) ? "（未设置）" : printerAddress),
                    60, 190, paint);
            canvas.drawText("打印时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date()), 60, 220, paint);
            canvas.drawText("如果这一页能正常打印出来，说明当前打印目标配置正确。", 60, 270, paint);
            document.finishPage(page);

            File output = new File(context.getCacheDir(), "icu_print_test_" + System.currentTimeMillis() + ".pdf");
            FileOutputStream stream = null;
            try {
                stream = new FileOutputStream(output);
                document.writeTo(stream);
            } finally {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            return output;
        } finally {
            document.close();
        }
    }
}
