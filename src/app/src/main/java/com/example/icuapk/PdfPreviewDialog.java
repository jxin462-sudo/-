package com.example.icuapk;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.ColorDrawable;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 PDF 逐页渲染成图片展示。
 *
 * <p>治疗报告的预览底部是「打印」，看清内容之后可以直接送去打印机；
 * 教程文档的预览是只读的，既不打印也不下载，只留一个「关闭」。</p>
 */
final class PdfPreviewDialog {

    /** 预览窗口里的操作回调。 */
    interface Listener {
        void onPrint();
    }

    /** 单页渲染宽度（像素）。A4 纵向约 900x1273，内存占用可以接受。 */
    private static final int PAGE_WIDTH_PX = 900;

    private PdfPreviewDialog() {
    }

    /** 治疗报告预览：底部是「打印」，并显示这份报告要送去哪台机器。 */
    static void show(final MainActivity activity, final File pdfFile, String title, final Listener listener) {
        showInternal(activity, pdfFile, title, listener, true);
    }

    /** 教程文档预览：只读，既不打印也不下载，底部只有「关闭」。 */
    static void showReadOnly(final MainActivity activity, final File pdfFile, String title) {
        showInternal(activity, pdfFile, title, null, false);
    }

    /**
     * 打开 PDF 预览。
     *
     * @param printable 是否允许打印。治疗报告传 true，教程文档传 false。
     */
    private static void showInternal(final MainActivity activity, final File pdfFile, String title,
                                     final Listener listener, boolean printable) {
        if (activity == null || pdfFile == null || !pdfFile.exists()) {
            if (activity != null) {
                Toast.makeText(activity, "PDF文件不存在，无法预览", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        TextView header = new TextView(activity);
        header.setText(title);
        header.setTextSize(16);
        header.setTextColor(Color.rgb(47, 56, 72));
        header.setPadding(dp(activity, 18), dp(activity, 14), dp(activity, 18), dp(activity, 6));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(activity);
        hint.setText(printable ? "下面就是下载后 PDF 的实际内容，可滚动查看全部页面"
                : "下面是文档的全部内容，可滚动查看所有页面");
        hint.setTextSize(12);
        hint.setTextColor(Color.rgb(122, 135, 154));
        hint.setPadding(dp(activity, 18), 0, dp(activity, 18), dp(activity, 10));
        root.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(activity);
        final LinearLayout pages = new LinearLayout(activity);
        pages.setOrientation(LinearLayout.VERTICAL);
        pages.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
        scroll.addView(pages);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final List<Bitmap> bitmaps = new ArrayList<>();
        if (printable) {
            // 打印目标一行：告诉操作者这份报告会打到哪台机器，点这行可以改打印设置。
            final TextView printer = new TextView(activity);
            printer.setTextSize(13);
            printer.setTextColor(Color.rgb(0, 132, 235));
            printer.setPadding(dp(activity, 18), dp(activity, 10), dp(activity, 18), 0);
            root.addView(printer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            printer.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    PrinterSettingsDialog.show(activity, new Runnable() {
                        @Override
                        public void run() {
                            refreshPrinterTarget(printer, activity);
                        }
                    });
                }
            });
            refreshPrinterTarget(printer, activity);

            // 预览页最下面一行是「打印」：先看清 PDF 长什么样，确认后再送打印机。
            TextView print = actionButton(activity, "打 印", Color.rgb(0, 132, 235));
            print.setTextSize(17);
            LinearLayout.LayoutParams printParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 50));
            printParams.setMargins(dp(activity, 18), 0, dp(activity, 18), dp(activity, 14));
            root.addView(print, printParams);
            print.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (listener != null) {
                        listener.onPrint();
                    }
                    // 打印走系统弹窗，预览页保持打开，结果由原生用 Toast 回推。
                }
            });
        } else {
            // 教程文档只读预览：底部只有「关闭」，不提供打印与下载。
            TextView close = actionButton(activity, "关 闭", Color.rgb(96, 106, 120));
            close.setTextSize(17);
            LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 50));
            closeParams.setMargins(dp(activity, 18), 0, dp(activity, 18), dp(activity, 14));
            root.addView(close, closeParams);
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    dialog.dismiss();
                }
            });
        }

        dialog.setContentView(root);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialogInterface) {
                for (Bitmap bitmap : bitmaps) {
                    bitmap.recycle();
                }
                bitmaps.clear();
            }
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        }

        renderPages(activity, pages, pdfFile, bitmaps);
    }

    /**
     * PDF 渲染放到后台线程，避免大文件预览时卡住界面。
     */
    private static void renderPages(final MainActivity activity, final LinearLayout pages, final File pdfFile, final List<Bitmap> bitmaps) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Bitmap> rendered = new ArrayList<>();
                String error = "";
                ParcelFileDescriptor descriptor = null;
                PdfRenderer renderer = null;
                try {
                    descriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
                    renderer = new PdfRenderer(descriptor);
                    int count = renderer.getPageCount();
                    for (int i = 0; i < count; i++) {
                        PdfRenderer.Page page = renderer.openPage(i);
                        try {
                            int width = PAGE_WIDTH_PX;
                            int height = Math.max(1, Math.round(width * page.getHeight() / (float) page.getWidth()));
                            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                            bitmap.eraseColor(Color.WHITE);
                            Matrix matrix = new Matrix();
                            matrix.setScale(width / (float) page.getWidth(), height / (float) page.getHeight());
                            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            rendered.add(bitmap);
                        } finally {
                            page.close();
                        }
                    }
                } catch (Throwable throwable) {
                    error = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
                    for (Bitmap bitmap : rendered) {
                        bitmap.recycle();
                    }
                    rendered.clear();
                } finally {
                    if (renderer != null) {
                        renderer.close();
                    }
                    if (descriptor != null) {
                        try {
                            descriptor.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
                final String renderError = error;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (renderError.length() > 0) {
                            Toast.makeText(activity, "PDF预览失败: " + renderError, Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (rendered.isEmpty()) {
                            Toast.makeText(activity, "PDF没有可显示的页面", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        bitmaps.addAll(rendered);
                        for (int i = 0; i < rendered.size(); i++) {
                            pages.addView(pageView(activity, rendered.get(i), i + 1, rendered.size()));
                        }
                    }
                });
            }
        }).start();
    }

    private static View pageView(Context context, Bitmap bitmap, int index, int total) {
        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(Gravity.CENTER_HORIZONTAL);
        wrapper.setPadding(0, dp(context, 8), 0, dp(context, 8));

        TextView caption = new TextView(context);
        caption.setText("第 " + index + " 页 / 共 " + total + " 页");
        caption.setTextSize(12);
        caption.setTextColor(Color.rgb(122, 135, 154));
        wrapper.addView(caption, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView image = new ImageView(context);
        image.setImageBitmap(bitmap);
        image.setAdjustViewBounds(true);
        image.setBackgroundColor(Color.WHITE);
        wrapper.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrapper;
    }

    private static TextView actionButton(Context context, String label, int color) {
        TextView button = new TextView(context);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundColor(color);
        button.setPadding(dp(context, 26), dp(context, 10), dp(context, 26), dp(context, 10));
        return button;
    }

    /** 刷新「打印机：xxx」这一行的文字。 */
    private static void refreshPrinterTarget(TextView view, Context context) {
        if (view == null || context == null) {
            return;
        }
        view.setText("打印机：" + PrinterSettings.load(context).describe() + "（点击设置）");
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
