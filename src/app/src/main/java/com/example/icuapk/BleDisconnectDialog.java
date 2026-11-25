package com.example.icuapk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 蓝牙断开提示弹窗。
 *
 * 主机、监护仪、红外桥是三条独立链路，任何一条掉线都要让使用者知道，
 * 否则页面上数值停住更容易被当成设备卡死。弹窗写明是哪台设备断的，
 * 并给出重新连接的入口。
 */
final class BleDisconnectDialog {

    private BleDisconnectDialog() {
    }

    /**
     * 弹出断开提示。
     *
     * @param deviceName 断开的是哪台设备，例如「动物ICU主机」
     * @param detail     补充说明，例如「实时数据与治疗计时已停止更新」
     * @param onReconnect 点击“重新连接”时执行，可以为空
     * @return 已创建的弹窗，便于调用方在新的断开事件到来时关掉旧的
     */
    static AlertDialog show(Activity activity, String deviceName, String detail, Runnable onReconnect) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return null;
        }
        int pad = dp(activity, 20);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad, pad, pad, dp(activity, 6));

        TextView title = new TextView(activity);
        title.setText("蓝牙连接已断开");
        title.setTextColor(Color.parseColor("#2F3848"));
        title.setTextSize(18);
        title.setGravity(Gravity.START);
        panel.addView(title);

        TextView message = new TextView(activity);
        message.setId(android.R.id.message);
        message.setText(buildMessage(deviceName, detail));
        message.setTextColor(Color.parseColor("#667387"));
        message.setTextSize(15);
        message.setPadding(0, dp(activity, 10), 0, 0);
        panel.addView(message);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setView(panel)
                .setPositiveButton("知道了", null);
        if (onReconnect != null) {
            builder.setNeutralButton("重新连接", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    onReconnect.run();
                }
            });
        }
        return builder.show();
    }

    /** 新链路断开时原地补充当前弹窗，不关闭重弹，也不重新播放已确认事件。 */
    static void update(AlertDialog dialog, String deviceName, String detail) {
        if (dialog == null || !dialog.isShowing()) {
            return;
        }
        TextView message = (TextView) dialog.findViewById(android.R.id.message);
        if (message != null) {
            message.setText(buildMessage(deviceName, detail));
        }
    }

    private static String buildMessage(String deviceName, String detail) {
        StringBuilder text = new StringBuilder();
        text.append(TextUtils.isEmpty(deviceName) ? "设备" : deviceName);
        text.append(" 与 App 的连接已断开。");
        if (!TextUtils.isEmpty(detail)) {
            text.append('\n').append(detail);
        }
        return text.toString();
    }

    private static int dp(Activity activity, float value) {
        return Math.round(activity.getResources().getDisplayMetrics().density * value);
    }
}

