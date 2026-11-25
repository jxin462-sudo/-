package com.example.icuapk;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 打印设置：打印机地址与打印份数。
 *
 * 打印方式只剩"直连打印机"一种：现场这台 HP LaserJet Pro M1213nf 没有装厂商驱动，
 * 走 Android 打印框架只会把任务堆在系统队列里、打印机没反应，界面还提示打印成功，
 * 所以不再提供"系统打印"选项，扫描 / 测试页 / 状态查询的入口也一并去掉。
 *
 * 蓝湖导出的设置类页面内容是图片，直接改会破坏设计稿，
 * 所以这里用原生对话框承载设置项，入口放在 PDF 预览页与原生动作 printer_settings 上。
 */
final class PrinterSettingsDialog {

    private PrinterSettingsDialog() {
    }

    /** 打开打印设置。 */
    static void show(final MainActivity activity) {
        show(activity, null);
    }

    /**
     * 打开打印设置。
     *
     * @param onSaved 保存成功后回调（可为空），用于刷新调用方界面上的打印目标文字
     */
    static void show(final MainActivity activity, final Runnable onSaved) {
        if (activity == null) {
            return;
        }
        final PrinterSettings settings = PrinterSettings.load(activity);
        final int[] copies = {settings.getCopies()};

        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(activity, 16), dp(activity, 6), dp(activity, 16), 0);

        form.addView(label(activity, "打印机地址"));
        final EditText addressInput = new EditText(activity);
        addressInput.setSingleLine(true);
        addressInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressInput.setHint("例如 192.168.1.10 或 192.168.1.10:631");
        addressInput.setText(settings.getAddress());
        form.addView(addressInput);

        form.addView(label(activity, "打印方式"));
        LinearLayout modeRow = row(activity);
        // 只剩直连一种方式：固定为选中态，不再做成可切换的选项。
        TextView directOption = option(activity, "直连打印机");
        applyOptionStyle(directOption, true);
        modeRow.addView(directOption, weightParams(activity));
        form.addView(modeRow);

        form.addView(label(activity, "打印份数"));
        LinearLayout copiesRow = row(activity);
        final TextView minus = option(activity, "－");
        final TextView count = option(activity, String.valueOf(copies[0]));
        final TextView plus = option(activity, "＋");
        copiesRow.addView(minus, weightParams(activity));
        copiesRow.addView(count, weightParams(activity));
        copiesRow.addView(plus, weightParams(activity));
        form.addView(copiesRow);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("打印设置")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.show();

        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                applyOptionStyle(count, false);
                count.setText(String.valueOf(copies[0]));
            }
        };
        refresh.run();

        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                copies[0] = PrinterSettings.clampCopies(copies[0] - 1);
                refresh.run();
            }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                copies[0] = PrinterSettings.clampCopies(copies[0] + 1);
                refresh.run();
            }
        });

        // 保存按钮默认点一下就关闭，这里改成校验通过才保存并关闭。
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String address = addressInput.getText().toString().trim();
                if (TextUtils.isEmpty(address)) {
                    Toast.makeText(activity, "直连打印需要先填写打印机地址", Toast.LENGTH_SHORT).show();
                    return;
                }
                settings.setAddress(address);
                settings.setCopies(copies[0]);
                settings.save(activity);
                activity.notifyLanhuStateChanged();
                Toast.makeText(activity, "打印设置已保存", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                if (onSaved != null) {
                    onSaved.run();
                }
            }
        });
    }

    private static TextView label(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(Color.rgb(47, 56, 72));
        view.setPadding(0, dp(context, 12), 0, dp(context, 4));
        return view;
    }

    private static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private static TextView option(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(14);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 8), dp(context, 10), dp(context, 8), dp(context, 10));
        applyOptionStyle(view, false);
        return view;
    }

    private static void applyOptionStyle(TextView view, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(view.getContext(), 6));
        background.setColor(selected ? Color.rgb(0, 132, 235) : Color.rgb(238, 242, 246));
        view.setBackground(background);
        view.setTextColor(selected ? Color.WHITE : Color.rgb(47, 56, 72));
        view.setTypeface(Typeface.create(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL));
    }

    private static LinearLayout.LayoutParams weightParams(Context context) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(context, 4), 0, dp(context, 4), 0);
        return params;
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
