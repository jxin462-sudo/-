package com.example.icuapk;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 「使用教程」的资料导入：文档、图片、视频。
 *
 * <p>用户在教程页点「导入图片 / 视频 / 文档」，从系统文件选择器里挑一个或多个文件，
 * 导入后放进应用自己的资料目录，教程页马上就能看到并打开它。
 * 这样既不用先往平板里装文件管理器，也不依赖「所有文件访问权限」。</p>
 */
final class TutorialDocumentImporter {

    private static final String TAG = "IcuTutorial";
    /** 选择文档的请求码。 */
    static final int REQUEST_CODE = 1003;
    /** 复制过程中的临时文件后缀，避免教程页扫到写了一半的文件。 */
    private static final String TEMP_SUFFIX = ".part";
    /**
     * 文件选择器里允许挑选的类型。
     *
     * <p>额外放开 octet-stream：部分文件管理器不认识 Word/Excel 的标准 MIME，
     * 不放开就选不中那些文件。</p>
     *
     * <p>图片与视频必须一起列出来：系统文件选择器会把不在 EXTRA_MIME_TYPES
     * 里的文件灰显且不可点，只列文档时平板里的照片、录像全都选不中，
     * 「替换文件」按钮也就一直是灰的。具体类型与通配类型都给一遍，
     * 兼容不同平板 ROM 对 MIME 过滤的实现差异。</p>
     */
    private static final String[] PICK_MIME_TYPES = {
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "text/markdown",
            "text/html",
            "text/xml",
            "application/xml",
            "application/json",
            "application/octet-stream",
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
            "image/bmp",
            "image/heic",
            "image/heif",
            "image/*",
            "video/mp4",
            "video/3gpp",
            "video/webm",
            "video/x-matroska",
            "video/avi",
            "video/quicktime",
            "video/*"
    };

    private TutorialDocumentImporter() {
    }

    /**
     * 导入目录：应用自己的外部私有目录，不需要任何权限就能读写。
     *
     * @param context 上下文
     * @return 导入目录，不存在时会创建
     */
    static File importDirectory(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        File dir = new File(base, "tutorial_docs");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** 拉起系统文件选择器，让用户挑一个文档。 */
    static void launch(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, PICK_MIME_TYPES);
        // 图片视频往往一次要导入好几条，允许长按多选，不用反复进出文件选择器
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, REQUEST_CODE);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(activity, "系统没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 把用户选中的文档复制进导入目录。
     *
     * @param context 上下文
     * @param source 用户选中的文档地址
     * @return 导入后的文件；不是支持的图片、视频、文档或复制失败时返回 null
     */
    static File importFrom(Context context, Uri source) {
        if (source == null) {
            return null;
        }
        String name = displayName(context, source);
        if (!TutorialMediaLibrary.isImportableName(name)) {
            return null;
        }
        File target = uniqueTarget(importDirectory(context), name);
        File temp = new File(target.getAbsolutePath() + TEMP_SUFFIX);
        InputStream input = null;
        OutputStream output = null;
        try {
            input = context.getContentResolver().openInputStream(source);
            if (input == null) {
                return null;
            }
            output = new FileOutputStream(temp);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) {
                output.write(chunk, 0, read);
            }
            output.flush();
            close(output);
            output = null;
            // 先写临时文件再改名，教程页扫描时不会读到写了一半的文档
            if (!temp.renameTo(target)) {
                temp.delete();
                return null;
            }
            return target;
        } catch (IOException | SecurityException exception) {
            Log.w(TAG, "import tutorial document failed: " + exception);
            return null;
        } finally {
            close(input);
            close(output);
            if (temp.exists()) {
                temp.delete();
            }
        }
    }

    /** 同名文件不覆盖，追加序号。 */
    private static File uniqueTarget(File dir, String name) {
        File target = new File(dir, name);
        if (!target.exists()) {
            return target;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int index = 1; index < 1000; index += 1) {
            File candidate = new File(dir, base + "(" + index + ")" + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + extension);
    }

    /** 用户选中的文件叫什么。 */
    private static String displayName(Context context, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception exception) {
            Log.w(TAG, "query display name failed: " + exception);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String segment = uri.getLastPathSegment();
        return segment == null ? "" : segment;
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 关闭失败不影响导入结果
        }
    }
}
