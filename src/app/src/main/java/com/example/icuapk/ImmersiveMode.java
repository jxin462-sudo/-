package com.example.icuapk;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/**
 * 沉浸式全屏：隐藏顶部状态栏与底部导航条，并让内容延伸到整块屏幕。
 * 监护页面按视口宽度自适应缩放（lanhu-fit.js），视口变大后会自动铺满，不留边距。
 */
final class ImmersiveMode {

    private ImmersiveMode() {
    }

    /**
     * 进入沉浸式全屏。系统栏被从边缘划出后会重新显示，需要在重新获得焦点时再调用一次。
     */
    static void apply(Activity activity) {
        if (activity == null) {
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /**
     * 消费系统栏内边距，避免系统给内容加留白，保证页面真正铺满整块屏幕。
     */
    static void consumeSystemBarInsets(View root) {
        if (root == null) {
            return;
        }
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    return WindowInsets.CONSUMED;
                }
                return insets.consumeSystemWindowInsets();
            }
        });
    }
}
