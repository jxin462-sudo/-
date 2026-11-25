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
 * 摄像监控页的本地文件提供器。
 * <p>
 * 截图和录像都放在应用私有目录里，网页（WebView）没有权限直接读这些文件，
 * 这里用 content:// 地址把文件安全地开放给本应用的网页显示缩略图。
 * <p>
 * 只允许读取白名单目录里的文件，不允许写入，也不允许访问目录以外的路径。
 */
public class CameraFileProvider extends ContentProvider {
    static final String AUTHORITY = "com.example.icuapk.camera";
    static final String PATH_SNAPSHOTS = "snapshots";
    static final String PATH_RECORDINGS = "recordings";
    private static final String[] COLUMNS = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * 生成截图或录像的 content:// 地址。
     */
    static Uri uriFor(File file, boolean recording) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(recording ? PATH_RECORDINGS : PATH_SNAPSHOTS)
                .appendPath(file.getName())
                .build();
    }

    @Override
    public String getType(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (!segments.isEmpty() && PATH_RECORDINGS.equals(segments.get(0))) {
            return "video/mp4";
        }
        return "image/jpeg";
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
        try {
            return resolveFile(uri).delete() ? 1 : 0;
        } catch (FileNotFoundException exception) {
            return 0;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("不支持写入");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("不支持写入");
    }

    /**
     * 把 content:// 地址还原成真实文件，越界的路径一律拒绝。
     */
    private File resolveFile(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        if (context == null) {
            throw new FileNotFoundException("提供器未初始化");
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2) {
            throw new FileNotFoundException("路径不合法: " + uri);
        }
        String group = segments.get(0);
        String name = segments.get(1);
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new FileNotFoundException("文件名不合法: " + name);
        }
        File dir;
        if (PATH_SNAPSHOTS.equals(group)) {
            dir = new File(context.getFilesDir(), "camera_snapshots");
        } else if (PATH_RECORDINGS.equals(group)) {
            dir = new File(context.getFilesDir(), "camera_recordings");
        } else {
            throw new FileNotFoundException("未知目录: " + group);
        }
        File file = new File(dir, name);
        if (!file.exists()) {
            throw new FileNotFoundException("文件不存在: " + name);
        }
        return file;
    }
}
