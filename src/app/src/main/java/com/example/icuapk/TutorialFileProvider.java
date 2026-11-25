package com.example.icuapk;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * 教程文档的只读文件提供器。
 *
 * <p>教程页里有些文档来自下载目录本身（不是通过 MediaStore 拿到的 content:// 地址），
 * 交给系统里的其它应用打开时，Android 不允许直接传 file:// 地址，
 * 这里把缓存目录里的文档以 content:// 形式开放出去。</p>
 *
 * <p>只允许读取白名单目录里的文件，不允许写入，也不允许访问目录以外的路径。</p>
 */
public class TutorialFileProvider extends ContentProvider {

    static final String AUTHORITY = "com.example.icuapk.tutorial";
    static final String PATH_DOCS = "docs";
    private static final String[] COLUMNS = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};

    @Override
    public boolean onCreate() {
        return true;
    }

    /** 生成文档的 content:// 地址。 */
    static Uri uriFor(File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(PATH_DOCS)
                .appendPath(file.getName())
                .build();
    }

    @Override
    public String getType(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (segments.isEmpty()) {
            return "application/octet-stream";
        }
        String name = segments.get(segments.size() - 1);
        int index = name.lastIndexOf('.');
        if (index < 0 || index == name.length() - 1) {
            return "application/octet-stream";
        }
        String ext = name.substring(index + 1).toLowerCase(java.util.Locale.US);
        if ("pdf".equals(ext)) {
            return "application/pdf";
        }
        if ("docx".equals(ext)) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if ("xlsx".equals(ext)) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if ("pptx".equals(ext)) {
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        }
        if ("txt".equals(ext) || "log".equals(ext) || "md".equals(ext)) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !"r".equals(mode)) {
            throw new FileNotFoundException("只允许读取: " + mode);
        }
        return ParcelFileDescriptor.open(resolveFile(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = resolveFile(uri);
        } catch (FileNotFoundException exception) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        cursor.addRow(new Object[]{file.getName(), file.length()});
        return cursor;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("不支持删除");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("不支持写入");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("不支持修改");
    }

    /** 把 content:// 地址还原成真实文件，越界的路径一律拒绝。 */
    private File resolveFile(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        if (context == null) {
            throw new FileNotFoundException("提供器未初始化");
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2 || !PATH_DOCS.equals(segments.get(0))) {
            throw new FileNotFoundException("路径不合法: " + uri);
        }
        String name = segments.get(1);
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new FileNotFoundException("文件名不合法: " + name);
        }
        File file = new File(new File(context.getCacheDir(), "tutorial_docs"), name);
        if (!file.exists()) {
            throw new FileNotFoundException("文件不存在: " + name);
        }
        return file;
    }
}

