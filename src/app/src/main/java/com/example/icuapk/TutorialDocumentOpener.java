package com.example.icuapk;

import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 打开「使用教程」里的文档。
 *
 * <p>按文档类型分开处理：PDF 用内置的只读预览（只能看内容，不能打印也不能下载），
 * 纯文本与 Word 文档在应用内直接读出正文，
 * 其余类型（Excel、PPT 等）交给系统里能打开它的应用。</p>
 */
final class TutorialDocumentOpener {

    private static final String TAG = "IcuTutorial";
    /** 纯文本最多读取的字符数，避免大文件把页面撑爆。 */
    private static final int MAX_TEXT_CHARS = 200000;
    /** DOCX 主文档 XML 的解压上限；压缩包体积小不代表解压后也小。 */
    private static final int MAX_DOCX_XML_BYTES = 4 * 1024 * 1024;

    private TutorialDocumentOpener() {
    }

    /**
     * 打开一个文档。
     *
     * @return {"ok":true,"kind":"pdf|text|external", ...} 或 {"ok":false,"message":"..."}
     */
    static String open(final MainActivity activity, String uriText, String title) {
        JSONObject root = new JSONObject();
        try {
            if (uriText == null || uriText.isEmpty()) {
                return fail(root, "文档地址无效");
            }
            Uri uri = Uri.parse(uriText);
            String name = displayName(activity, uri);
            String mime = mimeType(activity, uri, name);
            File local = materialize(activity, uri, name);
            if (local == null) {
                return fail(root, "无法读取文档：" + name);
            }
            String kind = kindOf(mime, name);
            if ("pdf".equals(kind)) {
                showPdf(activity, local, title);
                root.put("ok", true);
                root.put("kind", "pdf");
                root.put("title", title);
                return root.toString();
            }
            if ("text".equals(kind) || "word".equals(kind)) {
                String text = "word".equals(kind) ? readDocx(local) : null;
                if (text == null && "docx".equals(extension(name))) {
                    // DOCX 是 ZIP，解析失败不能再按纯文本显示压缩包二进制内容。
                    return fail(root, "无法解析DOCX正文，或文档内容超过安全读取上限：" + name);
                }
                if (text == null) {
                    text = readText(local);
                }
                if (text == null) {
                    return fail(root, "无法解析文档内容：" + name);
                }
                root.put("ok", true);
                root.put("kind", "text");
                root.put("title", title);
                root.put("text", text);
                return root.toString();
            }
            // file:// 地址不能直接交给系统应用，复制一份后用只读提供器共享
            // 共享地址生成失败时也不能把 file:// 传出去，Android 7 之后会直接崩溃。
            Uri shared = shareableUri(activity, local, name);
            if (shared == null) {
                return fail(root, "无法分享该文档：" + name);
            }
            return openExternally(activity, shared, mime, name, root);
        } catch (Exception exception) {
            Log.e(TAG, "open document failed", exception);
            return fail(root, "打开文档失败");
        }
    }

    // ------------------------------------------------------------------ 分类型处理

    /** PDF：交给内置的只读预览，只能看内容，不能打印也不能下载。 */
    private static void showPdf(final MainActivity activity, final File file, final String title) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                PdfPreviewDialog.showReadOnly(activity, file, title);
            }
        });
    }

    /**
     * 把文档复制到缓存目录，再用只读提供器交给系统应用打开。
     * Android 不允许把 file:// 地址直接传给其它应用。
     */
    private static Uri shareableUri(Context context, File local, String name) {
        try {
            File dir = new File(context.getCacheDir(), "tutorial_docs");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File target = new File(dir, safeName(name));
            InputStream input = new FileInputStream(local);
            try {
                copy(input, target);
            } finally {
                close(input);
            }
            return TutorialFileProvider.uriFor(target);
        } catch (IOException exception) {
            Log.w(TAG, "share document failed: " + exception);
            return null;
        }
    }

    /** 其余类型：交给系统里能打开它的应用。 */
    private static String openExternally(MainActivity activity, Uri uri, String mime, String name,
            JSONObject root) throws JSONException {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (intent.resolveActivity(activity.getPackageManager()) == null) {
            root.put("ok", false);
            root.put("message", "没有可以打开 " + name + " 的应用");
            return root.toString();
        }
        final Intent launch = intent;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    activity.startActivity(launch);
                } catch (ActivityNotFoundException exception) {
                    Toast.makeText(activity, "没有可以打开该文档的应用", Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.put("ok", true);
        root.put("kind", "external");
        return root.toString();
    }

    // ------------------------------------------------------------------ 文件读取

    /** 把文档地址解析成真实文件：content:// 会复制到缓存目录。 */
    private static File materialize(Context context, Uri uri, String name) throws IOException {
        String scheme = uri.getScheme();
        if ("file".equals(scheme)) {
            String path = uri.getPath();
            if (path == null) {
                return null;
            }
            String assetPrefix = "/android_asset/";
            if (path.startsWith(assetPrefix)) {
                return copyAsset(context, path.substring(assetPrefix.length()), name);
            }
            File file = new File(path);
            return file.exists() ? file : null;
        }
        if (!ContentResolver.SCHEME_CONTENT.equals(scheme)) {
            return null;
        }
        File dir = new File(context.getCacheDir(), "tutorial_docs");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File target = new File(dir, safeName(name));
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) {
            return null;
        }
        try {
            copy(input, target);
        } finally {
            close(input);
        }
        return target;
    }

    private static File copyAsset(Context context, String relative, String name) throws IOException {
        File dir = new File(context.getCacheDir(), "tutorial_docs");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File target = new File(dir, safeName(name));
        InputStream input = context.getAssets().open(relative);
        try {
            copy(input, target);
        } finally {
            close(input);
        }
        return target;
    }

    private static void copy(InputStream input, File target) throws IOException {
        OutputStream output = new FileOutputStream(target);
        try {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) {
                output.write(chunk, 0, read);
            }
            output.flush();
        } finally {
            close(output);
        }
    }

    /** 纯文本：优先按 UTF-8 解码，出现乱码时退回 GBK（国内文档常见）。 */
    private static String readText(File file) {
        try {
            byte[] data = readBytes(file);
            if (data == null) {
                return null;
            }
            String text = new String(data, "UTF-8");
            if (text.indexOf('�') >= 0) {
                text = new String(data, "GBK");
            }
            return text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
        } catch (Exception exception) {
            Log.w(TAG, "read text failed: " + exception);
            return null;
        }
    }

    /** Word 文档：解出 word/document.xml 的正文，并限制解压后的 XML 大小。 */
    static String readDocx(File file) {
        ZipInputStream zip = null;
        try {
            zip = new ZipInputStream(new FileInputStream(file));
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!"word/document.xml".equals(entry.getName())) {
                    continue;
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = zip.read(chunk)) > 0) {
                    if (output.size() + read > MAX_DOCX_XML_BYTES) {
                        throw new IOException("DOCX document.xml exceeds safe limit");
                    }
                    output.write(chunk, 0, read);
                }
                return docxToText(output.toString("UTF-8"));
            }
        } catch (Exception exception) {
            Log.w(TAG, "read docx failed: " + exception);
        } finally {
            close(zip);
        }
        return null;
    }

    static String docxToText(String xml) {
        String text = xml.replaceAll("</w:p>", "\n")
                .replaceAll("<w:tab[^>]*/>", "\t")
                .replaceAll("<w:br[^>]*/>", "\n")
                .replaceAll("<w:cr[^>]*/>", "\n");
        text = text.replaceAll("<[^>]+>", "");
        text = text.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
        text = text.trim();
        if (text.length() > MAX_TEXT_CHARS) {
            return text.substring(0, MAX_TEXT_CHARS) + "\n\n（正文过长，仅显示前200000字）";
        }
        return text;
    }

    private static byte[] readBytes(File file) throws IOException {
        long length = file.length();
        if (length <= 0) {
            return new byte[0];
        }
        InputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) {
                output.write(chunk, 0, read);
            }
            return output.toByteArray();
        } finally {
            close(input);
        }
    }

    // ------------------------------------------------------------------ 元信息

    private static String displayName(Context context, Uri uri) {
        String name = null;
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(
                        uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    name = cursor.getString(0);
                }
            } catch (Exception exception) {
                Log.w(TAG, "query display name failed: " + exception);
            } finally {
                close(cursor);
            }
        }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
        }
        if (name == null || name.isEmpty()) {
            name = "教程文档";
        }
        return name;
    }

    private static String mimeType(Context context, Uri uri, String name) {
        String mime = null;
        try {
            mime = context.getContentResolver().getType(uri);
        } catch (Exception exception) {
            Log.w(TAG, "query mime type failed: " + exception);
        }
        if (mime == null || mime.isEmpty()) {
            mime = guessByName(name);
        }
        return mime == null || mime.isEmpty() ? "application/octet-stream" : mime;
    }

    /** 没有 MIME 信息时按扩展名猜一个。 */
    private static String guessByName(String name) {
        String ext = extension(name);
        if ("pdf".equals(ext)) {
            return "application/pdf";
        }
        if ("docx".equals(ext)) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if ("doc".equals(ext)) {
            return "application/msword";
        }
        if ("xlsx".equals(ext)) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if ("xls".equals(ext)) {
            return "application/vnd.ms-excel";
        }
        if ("pptx".equals(ext)) {
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        }
        if ("ppt".equals(ext)) {
            return "application/vnd.ms-powerpoint";
        }
        if ("txt".equals(ext) || "log".equals(ext) || "md".equals(ext)) {
            return "text/plain";
        }
        if ("json".equals(ext)) {
            return "application/json";
        }
        if ("html".equals(ext) || "htm".equals(ext)) {
            return "text/html";
        }
        if ("xml".equals(ext)) {
            return "text/xml";
        }
        return "application/octet-stream";
    }

    /** 文档类别：pdf / text / word / other；通用 MIME 必须按扩展名兜底。 */
    static String kindOf(String mime, String name) {
        String lower = mime == null ? "" : mime.toLowerCase(Locale.US);
        String ext = extension(name);
        // 部分文件提供器对 DOCX 一律返回 application/octet-stream 或 text/plain。
        if ("docx".equals(ext) || "doc".equals(ext)) {
            return "word";
        }
        if (lower.contains("pdf")) {
            return "pdf";
        }
        if (lower.contains("wordprocessingml") || lower.contains("msword")) {
            return "word";
        }
        if (lower.startsWith("text/") || lower.contains("json") || lower.contains("xml")) {
            return "text";
        }
        return isTextExtension(ext) ? "text" : "other";
    }

    /** 常见纯文本扩展名：系统没给出 MIME 时按扩展名兜底，避免被丢给外部应用。 */
    private static boolean isTextExtension(String ext) {
        return "txt".equals(ext) || "log".equals(ext) || "md".equals(ext) || "markdown".equals(ext)
                || "json".equals(ext) || "xml".equals(ext) || "csv".equals(ext) || "ini".equals(ext)
                || "yml".equals(ext) || "yaml".equals(ext) || "conf".equals(ext)
                || "html".equals(ext) || "htm".equals(ext);
    }

    private static String extension(String name) {
        if (name == null) {
            return "";
        }
        int index = name.lastIndexOf('.');
        if (index < 0 || index == name.length() - 1) {
            return "";
        }
        return name.substring(index + 1).toLowerCase(Locale.US);
    }

    /** 缓存文件名：去掉路径分隔符，避免写到目录外面去。 */
    private static String safeName(String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (safe.isEmpty()) {
            safe = "tutorial_document";
        }
        return safe;
    }

    private static String fail(JSONObject root, String message) {
        try {
            root.put("ok", false);
            root.put("message", message);
        } catch (JSONException ignored) {
            // JSON 组装失败时返回最简结果
        }
        return root.toString();
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 关闭失败不影响主流程
        }
    }
}

