package com.example.icuapk;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

/**
 * 「使用教程」里点开本机相册素材后的原生查看器。
 *
 * <p>相册素材在网页端用 content:// 地址不一定能渲染，所以缩略图由原生压好后交给网页，
 * 点开看大图/播放视频则一律走这里，保证一定能看到内容。</p>
 */
final class TutorialMediaDialog {

    /** 全屏预览时允许的最大边长（像素）。 */
    private static final int PREVIEW_MAX_SIZE = 1600;

    private TutorialMediaDialog() {
    }

    /** 打开一条相册素材：图片直接看，视频直接播。 */
    static void show(MainActivity activity, Uri uri, String title, boolean video) {
        if (activity == null || uri == null) {
            return;
        }
        if (video) {
            showVideo(activity, uri, title);
        } else {
            showImage(activity, uri, title);
        }
    }

    private static void showImage(final MainActivity activity, final Uri uri, String title) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 22, 30));

        TextView header = new TextView(activity);
        header.setText(title == null ? "" : title);
        header.setTextSize(16);
        header.setTextColor(Color.WHITE);
        header.setPadding(dp(activity, 18), dp(activity, 14), dp(activity, 18), dp(activity, 10));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout holder = new LinearLayout(activity);
        holder.setGravity(Gravity.CENTER);
        root.addView(holder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(activity);
        hint.setText("正在载入…");
        hint.setTextSize(14);
        hint.setTextColor(Color.rgb(158, 168, 182));
        holder.addView(hint);

        TextView close = actionButton(activity, "关闭", Color.rgb(236, 240, 245));
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48));
        closeParams.setMargins(dp(activity, 18), dp(activity, 8), dp(activity, 18), dp(activity, 14));
        root.addView(close, closeParams);

        dialog.setContentView(root);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
            }
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.rgb(18, 22, 30)));
        }

        loadPreview(activity, dialog, uri, holder);
    }

    /** 大图解码放到后台线程，避免大照片卡住界面。 */
    private static void loadPreview(final MainActivity activity, final Dialog dialog, final Uri uri,
            final LinearLayout holder) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = decodePreview(activity, uri);
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (bitmap == null) {
                            TextView failed = new TextView(activity);
                            failed.setText("图片无法打开");
                            failed.setTextSize(15);
                            failed.setTextColor(Color.rgb(236, 240, 245));
                            holder.removeAllViews();
                            holder.addView(failed);
                            return;
                        }
                        if (!dialog.isShowing()) {
                            // 窗口已经关掉了，这张图没必要留在内存里
                            bitmap.recycle();
                            return;
                        }
                        ImageView image = new ImageView(activity);
                        image.setImageBitmap(bitmap);
                        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        holder.removeAllViews();
                        holder.addView(image, new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    }
                });
            }
        }, "icu-tutorial-preview").start();
    }

    private static Bitmap decodePreview(Context context, Uri uri) {
        java.io.InputStream input = null;
        try {
            input = context.getContentResolver().openInputStream(uri);
            if (input == null) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(input, null, bounds);
            close(input);
            input = context.getContentResolver().openInputStream(uri);
            if (input == null) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            return BitmapFactory.decodeStream(input, null, options);
        } catch (Exception exception) {
            return null;
        } finally {
            close(input);
        }
    }

    private static int sampleSize(int width, int height) {
        int sample = 1;
        int longest = Math.max(width, height);
        while (longest / sample > PREVIEW_MAX_SIZE) {
            sample *= 2;
        }
        return sample;
    }

    private static void showVideo(MainActivity activity, Uri uri, String title) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 22, 30));

        TextView header = new TextView(activity);
        header.setText(title == null ? "" : title);
        header.setTextSize(16);
        header.setTextColor(Color.WHITE);
        header.setPadding(dp(activity, 18), dp(activity, 14), dp(activity, 18), dp(activity, 10));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout holder = new LinearLayout(activity);
        holder.setGravity(Gravity.CENTER);
        final VideoView video = new VideoView(activity);
        video.setVideoURI(uri);
        MediaController controller = new MediaController(activity);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        video.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(android.media.MediaPlayer mediaPlayer) {
                mediaPlayer.setLooping(false);
                video.start();
            }
        });
        video.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(android.media.MediaPlayer mediaPlayer, int what, int extra) {
                Toast.makeText(activity, "视频无法播放", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        holder.addView(video, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(holder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView close = actionButton(activity, "关闭", Color.rgb(236, 240, 245));
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48));
        closeParams.setMargins(dp(activity, 18), dp(activity, 8), dp(activity, 18), dp(activity, 14));
        root.addView(close, closeParams);

        dialog.setContentView(root);
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface dialogInterface) {
                // 关闭窗口时停掉播放，避免视频在后台继续出声
                video.stopPlayback();
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
            }
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.rgb(18, 22, 30)));
        }
    }

    private static TextView actionButton(Context context, String text, int color) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextSize(16);
        button.setTextColor(color);
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    private static void close(java.io.InputStream input) {
        if (input != null) {
            try {
                input.close();
            } catch (Exception ignored) {
                // 关闭失败不影响结果
            }
        }
    }
}

