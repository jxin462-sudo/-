package com.example.icuapk;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「使用教程」页的本机素材库。
 *
 * <p>教程页的六个入口展示本机最近出现的图片、视频和文档：默认两个文档、两个图片、两个视频，
 * 保证每种存在的类型都能露出来，避免文档被大量图片挤掉。
 * 这样用户在教程页既能看图、看视频，也能直接打开文档看内容。</p>
 *
 * <p>整个类只读不写，不会修改媒体库或下载目录里的任何文件。文档优先从下载目录与
 * 媒体库文件表里取，这两个来源在 Android 13 之后依然能读到用户下载的资料。</p>
 */
final class TutorialMediaLibrary {

    private static final String TAG = "IcuTutorial";
    /** 教程页一次需要的素材条数。 */
    static final int DEFAULT_LIMIT = 6;
    /** 每类素材默认占的入口数：图片 2 个、视频 2 个、文档 2 个。 */
    private static final int SLOTS_PER_TYPE = 2;
    /** 入口的排布顺序：先图片、再视频、最后文档。 */
    private static final String[] SLOT_TYPES = {"image", "video", "document"};
    /** 缩略图最长边（像素）。 */
    private static final int THUMBNAIL_MAX_SIZE = 480;
    /** 缩略图 JPEG 质量。 */
    private static final int THUMBNAIL_QUALITY = 60;
    /** Android 13 起「用户只授权了部分照片」的权限。 */
    private static final String PERMISSION_VISUAL_USER_SELECTED =
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED";
    /** 缩略图缓存上限，超过后整体清空，避免长时间运行占用过多内存。 */
    private static final int THUMBNAIL_CACHE_MAX = 32;
    /** 缩略图缓存：uri -> data: 地址。 */
    private static final Map<String, String> THUMBNAIL_CACHE = new HashMap<>();
    /** 可以在教程页里直接查看内容的文档类型。 */
    private static final String[] DOCUMENT_MIME_TYPES = {
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
            "application/json"
    };
    /** 允许导入并在教程页里直接查看的图片后缀。 */
    private static final String[] IMAGE_EXTENSIONS = {
            "jpg", "jpeg", "jpe", "png", "webp", "gif", "bmp", "heic", "heif"
    };
    /** 允许导入并在教程页里直接播放的视频后缀。 */
    private static final String[] VIDEO_EXTENSIONS = {
            "mp4", "m4v", "3gp", "3gpp", "webm", "mkv", "avi", "mov"
    };

    private TutorialMediaLibrary() {
    }

    // ------------------------------------------------------------------ 权限

    /** 任意一种相册权限拿到，就可以展示图片与视频。 */
    static boolean hasMediaPermission(Context context) {
        return hasImagesPermission(context) || hasVideosPermission(context);
    }

    /** 图片读取权限。 */
    static boolean hasImagesPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isLegacyTarget(context)) {
            return granted(context, Manifest.permission.READ_MEDIA_IMAGES)
                    || granted(context, PERMISSION_VISUAL_USER_SELECTED);
        }
        return granted(context, Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    /** 视频读取权限。 */
    static boolean hasVideosPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isLegacyTarget(context)) {
            return granted(context, Manifest.permission.READ_MEDIA_VIDEO)
                    || granted(context, PERMISSION_VISUAL_USER_SELECTED);
        }
        return granted(context, Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    /** 需要向用户申请的相册权限。 */
    static List<String> requiredPermissions(Context context) {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isLegacyTarget(context)) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        return permissions;
    }

    /**
     * 目标版本低于 33 时，系统仍然按 READ_EXTERNAL_STORAGE 处理媒体库权限，
     * 这时候只申请 READ_MEDIA_* 是读不到任何素材的。
     */
    private static boolean isLegacyTarget(Context context) {
        return context.getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.TIRAMISU;
    }

    /**
     * 是否拿到了"所有文件访问权限"。
     *
     * <p>Android 11 之后，MediaStore 只把图片、视频这类媒体文件暴露给普通应用，
     * 文档（PDF、Word 等）必须拿到所有文件访问权限才能被枚举到，
     * 否则教程页只能显示图片和视频。</p>
     */
    static boolean hasAllFilesAccess(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true;
        }
        return Environment.isExternalStorageManager();
    }

    // ------------------------------------------------------------------ 素材

    /** 本机的一条素材：图片、视频或者文档。 */
    private static final class Item {
        final Uri uri;
        final String name;
        final String type;
        final String mime;
        final long date;
        final long size;

        Item(Uri uri, String name, String type, String mime, long date, long size) {
            this.uri = uri;
            this.name = name;
            this.type = type;
            this.mime = mime;
            this.date = date;
            this.size = size;
        }

        /** 同一份文件在下载目录和文件表里各有一条，用文件名加大小去重。 */
        String dedupeKey() {
            return name + "|" + size;
        }
    }

    /**
     * 素材快照：图片、视频、文档按时间倒序合并后的前若干条。
     *
     * @return {"granted": 是否已授权, "fingerprint": 内容指纹, "items": [...]}
     */
    static JSONObject snapshotJson(Context context, int limit) {
        JSONObject root = new JSONObject();
        JSONArray items = new JSONArray();
        List<Item> selected = new ArrayList<Item>();
        try {
            selected = loadSelected(context, limit);
            for (Item item : selected) {
                JSONObject entry = new JSONObject();
                entry.put("uri", item.uri.toString());
                entry.put("name", item.name);
                entry.put("type", item.type);
                entry.put("mime", item.mime);
                entry.put("date", item.date);
                entry.put("size", item.size);
                items.put(entry);
            }
            root.put("granted", hasMediaPermission(context));
            root.put("allFilesGranted", hasAllFilesAccess(context));
            root.put("fingerprint", fingerprint(selected));
            root.put("items", items);
        } catch (JSONException exception) {
            Log.w(TAG, "build tutorial media json failed: " + exception);
        }
        Log.i(TAG, "tutorial media items=" + items.length() + " types=" + typeSummary(selected));
        return root;
    }

    /** 选中的素材里图片、视频、文档各占几个，方便排查教程页显示的资料对不对。 */
    private static String typeSummary(List<Item> items) {
        Map<String, Integer> counts = new HashMap<>();
        for (Item item : items) {
            Integer count = counts.get(item.type);
            counts.put(item.type, count == null ? 1 : count + 1);
        }
        return counts.toString();
    }

    /**
     * 取出要展示的素材：三类素材合并后按时间倒序，每种类型先按配额取位（默认各两个），
     * 不足的位置再由其它类型补齐。文档没有缩略图，由网页端画占位样式。
     */
    private static List<Item> loadSelected(Context context, int limit) {
        List<Item> all = new ArrayList<>();
        List<Item> images = hasImagesPermission(context) ? queryImages(context, limit) : new ArrayList<Item>();
        List<Item> videos = hasVideosPermission(context) ? queryVideos(context, limit) : new ArrayList<Item>();
        // 文档不受相册权限限制，只要系统允许读取下载目录就能取到
        List<Item> documents = queryDocuments(context, limit);
        Log.i(TAG, "tutorial sources images=" + images.size() + " videos=" + videos.size()
                + " documents=" + documents.size());
        all.addAll(images);
        all.addAll(videos);
        all.addAll(documents);
        // 导入的图片、视频、文档放在应用自己的目录，不受相册权限和
        // 「所有文件访问权限」限制，任何情况下都必须出现在教程页里。
        all.addAll(queryImportedFiles(context, limit));
        return select(all, limit);
    }

    /**
     * 挑选规则：图片、视频、文档各占两个入口，同类内部按时间倒序。
     *
     * <p>某一类素材不足两个时，空出来的位置由其它类里最新的素材补上，
     * 免得教程页出现空卡片；输出按类型分组，六个入口的排布才是固定的。</p>
     */
    private static List<Item> select(List<Item> all, int limit) {
        List<Item> sorted = new ArrayList<>(all);
        Collections.sort(sorted, new Comparator<Item>() {
            @Override
            public int compare(Item left, Item right) {
                int byDate = Long.compare(right.date, left.date);
                if (byDate != 0) {
                    return byDate;
                }
                return left.uri.toString().compareTo(right.uri.toString());
            }
        });
        Map<String, List<Item>> buckets = new HashMap<>();
        for (Item item : sorted) {
            List<Item> bucket = buckets.get(item.type);
            if (bucket == null) {
                bucket = new ArrayList<Item>();
                buckets.put(item.type, bucket);
            }
            bucket.add(item);
        }
        List<Item> selected = new ArrayList<>();
        Set<Item> picked = new HashSet<>();
        // 每类先按配额取位：图片 2 个、视频 2 个、文档 2 个。
        for (String type : SLOT_TYPES) {
            if (selected.size() >= limit) {
                break;
            }
            List<Item> bucket = buckets.get(type);
            if (bucket == null) {
                continue;
            }
            int count = Math.min(SLOTS_PER_TYPE, bucket.size());
            for (int i = 0; i < count; i++) {
                selected.add(bucket.get(i));
                picked.add(bucket.get(i));
            }
        }
        // 某一类不够两个时，用其它类里最新的素材把空位补上。
        for (Item item : sorted) {
            if (selected.size() >= limit) {
                break;
            }
            if (picked.contains(item)) {
                continue;
            }
            selected.add(item);
            picked.add(item);
        }
        // 输出按类型分组，页面上六个入口的排布才是稳定的。
        Collections.sort(selected, new Comparator<Item>() {
            @Override
            public int compare(Item left, Item right) {
                return Integer.compare(typeRank(left.type), typeRank(right.type));
            }
        });
        return selected;
    }

    /** 类型在入口里的排位：图片、视频、文档依次靠前，其它类型排在最后。 */
    private static int typeRank(String type) {
        for (int i = 0; i < SLOT_TYPES.length; i++) {
            if (SLOT_TYPES[i].equals(type)) {
                return i;
            }
        }
        return SLOT_TYPES.length;
    }

    /** 内容指纹：素材集合变了就说明教程内容更新了。 */
    private static String fingerprint(List<Item> items) {
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder raw = new StringBuilder();
        for (Item item : items) {
            raw.append(item.type).append('|').append(item.uri).append('|')
                    .append(item.date).append('|').append(item.size).append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(raw.toString().getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte value : bytes) {
                hex.append(Character.forDigit((value >> 4) & 0x0F, 16));
                hex.append(Character.forDigit(value & 0x0F, 16));
            }
            return hex.toString();
        } catch (Exception exception) {
            Log.w(TAG, "fingerprint failed: " + exception);
            return "";
        }
    }

    private static List<Item> queryImages(Context context, int limit) {
        List<Item> items = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                            MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DATE_ADDED,
                            MediaStore.Images.Media.SIZE},
                    null, null, MediaStore.Images.Media.DATE_ADDED + " DESC");
            if (cursor == null) {
                return items;
            }
            while (cursor.moveToNext() && items.size() < limit) {
                items.add(new Item(
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)),
                        cursor.getString(1),
                        "image",
                        cursor.getString(2),
                        cursor.getLong(3),
                        cursor.getLong(4)));
            }
        } catch (Exception exception) {
            Log.w(TAG, "query images failed: " + exception);
        } finally {
            close(cursor);
        }
        return items;
    }

    private static List<Item> queryVideos(Context context, int limit) {
        List<Item> items = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                            MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.DATE_ADDED,
                            MediaStore.Video.Media.SIZE},
                    null, null, MediaStore.Video.Media.DATE_ADDED + " DESC");
            if (cursor == null) {
                return items;
            }
            while (cursor.moveToNext() && items.size() < limit) {
                items.add(new Item(
                        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)),
                        cursor.getString(1),
                        "video",
                        cursor.getString(2),
                        cursor.getLong(3),
                        cursor.getLong(4)));
            }
        } catch (Exception exception) {
            Log.w(TAG, "query videos failed: " + exception);
        } finally {
            close(cursor);
        }
        return items;
    }

    /**
     * 文档：下载目录与媒体库文件表都查一遍。
     * 下载目录里的资料任何应用都能读到，文件表里则是应用自己创建过的文件。
     */
    private static List<Item> queryDocuments(Context context, int limit) {
        List<Item> merged = new ArrayList<>();
        merged.addAll(queryByMime(context, downloadsCollection(), limit));
        merged.addAll(queryByMime(context, filesCollection(), limit));
        // MediaStore 没有索引的文件（纯文本、手动拷进去的资料）再直接扫一遍目录
        merged.addAll(queryDocumentFiles(context, limit));
        List<Item> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Item item : merged) {
            if (seen.add(item.dedupeKey())) {
                unique.add(item);
            }
        }
        return unique;
    }

    /**
     * 直接扫下载目录与文档目录。
     *
     * <p>只有拿到"所有文件访问权限"时才扫得到；纯文本这类文件媒体库不一定收录，
     * 扫目录才能保证教程页里出现的文档都能打开。</p>
     */
    private static List<Item> queryDocumentFiles(Context context, int limit) {
        List<Item> items = new ArrayList<>();
        if (!hasAllFilesAccess(context)) {
            return items;
        }
        File[] roots = {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        };
        for (File root : roots) {
            File[] files = root.listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (!file.isFile() || !isDocumentName(file.getName())) {
                    continue;
                }
                items.add(new Item(Uri.fromFile(file), file.getName(), "document",
                        mimeByName(file.getName()), file.lastModified(), file.length()));
            }
        }
        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item left, Item right) {
                return Long.compare(right.date, left.date);
            }
        });
        return items.size() > limit ? new ArrayList<>(items.subList(0, limit)) : items;
    }

    /**
     * 教程页里导入的资料：文档、图片、视频都按真实类型进对应槽位。
     *
     * <p>导入的资料放在应用自己的目录里，不需要「所有文件访问权限」就能读到，
     * 所以即使没开这个权限，导入的图片视频照样能在教程页里直接显示和播放。</p>
     */
    private static List<Item> queryImportedFiles(Context context, int limit) {
        List<Item> items = new ArrayList<>();
        File[] files = TutorialDocumentImporter.importDirectory(context).listFiles();
        if (files == null) {
            return items;
        }
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            String type = importedTypeOf(file.getName());
            if (type.isEmpty()) {
                continue;
            }
            items.add(new Item(Uri.fromFile(file), file.getName(), type,
                    mimeByName(file.getName()), file.lastModified(), file.length()));
        }
        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item left, Item right) {
                return Long.compare(right.date, left.date);
            }
        });
        return items.size() > limit ? new ArrayList<>(items.subList(0, limit)) : items;
    }

    /** 能不能导入教程资料库：文档、图片、视频三类都收。 */
    static boolean isImportableName(String name) {
        return !importedTypeOf(name).isEmpty();
    }

    /** 导入文件按后缀归类；不支持的类型返回空串。 */
    static String importedTypeOf(String name) {
        if (isDocumentName(name)) {
            return "document";
        }
        String ext = extensionOf(name);
        for (String candidate : IMAGE_EXTENSIONS) {
            if (candidate.equals(ext)) {
                return "image";
            }
        }
        for (String candidate : VIDEO_EXTENSIONS) {
            if (candidate.equals(ext)) {
                return "video";
            }
        }
        return "";
    }

    /** 文件名后缀是不是可以在教程页里查看的文档类型。 */
    static boolean isDocumentName(String name) {
        String mime = mimeByName(name);
        if (mime.isEmpty()) {
            return false;
        }
        for (String type : DOCUMENT_MIME_TYPES) {
            if (type.equals(mime)) {
                return true;
            }
        }
        return false;
    }

    /** 按扩展名给出 MIME 类型。 */
    private static String mimeByName(String name) {
        String ext = extensionOf(name);
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
        if ("jpg".equals(ext) || "jpeg".equals(ext) || "jpe".equals(ext)) {
            return "image/jpeg";
        }
        if ("png".equals(ext)) {
            return "image/png";
        }
        if ("webp".equals(ext)) {
            return "image/webp";
        }
        if ("gif".equals(ext)) {
            return "image/gif";
        }
        if ("bmp".equals(ext)) {
            return "image/bmp";
        }
        if ("heic".equals(ext) || "heif".equals(ext)) {
            return "image/heic";
        }
        if ("mp4".equals(ext) || "m4v".equals(ext)) {
            return "video/mp4";
        }
        if ("3gp".equals(ext) || "3gpp".equals(ext)) {
            return "video/3gpp";
        }
        if ("webm".equals(ext)) {
            return "video/webm";
        }
        if ("mkv".equals(ext)) {
            return "video/x-matroska";
        }
        if ("avi".equals(ext)) {
            return "video/avi";
        }
        if ("mov".equals(ext)) {
            return "video/quicktime";
        }
        return "";
    }

    private static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int index = name.lastIndexOf('.');
        if (index < 0 || index == name.length() - 1) {
            return "";
        }
        return name.substring(index + 1).toLowerCase(java.util.Locale.US);
    }

    private static List<Item> queryByMime(Context context, Uri collection, int limit) {
        List<Item> items = new ArrayList<>();
        if (collection == null) {
            return items;
        }
        Cursor cursor = null;
        try {
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < DOCUMENT_MIME_TYPES.length; i += 1) {
                if (i > 0) {
                    placeholders.append(',');
                }
                placeholders.append('?');
            }
            String selection = MediaStore.MediaColumns.MIME_TYPE + " IN (" + placeholders + ")";
            cursor = context.getContentResolver().query(
                    collection,
                    // 文件表与下载目录用的是 _display_name，用 display_name 会直接查询失败
                    new String[]{MediaStore.MediaColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED,
                            MediaStore.MediaColumns.SIZE},
                    selection, DOCUMENT_MIME_TYPES,
                    MediaStore.MediaColumns.DATE_ADDED + " DESC");
            if (cursor == null) {
                return items;
            }
            while (cursor.moveToNext() && items.size() < limit) {
                items.add(new Item(
                        ContentUris.withAppendedId(collection, cursor.getLong(0)),
                        cursor.getString(1),
                        "document",
                        cursor.getString(2),
                        cursor.getLong(3),
                        cursor.getLong(4)));
            }
        } catch (Exception exception) {
            Log.w(TAG, "query documents failed: " + exception);
        } finally {
            close(cursor);
        }
        return items;
    }

    /** 下载目录集合，Android 10 起才有。 */
    private static Uri downloadsCollection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        return MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL);
    }

    /** 媒体库文件表。 */
    private static Uri filesCollection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        }
        return MediaStore.Files.getContentUri("external");
    }

    // ------------------------------------------------------------------ 缩略图

    /**
     * 单条素材的缩略图：压缩后的 JPEG data: 地址。
     * 结果按 uri 缓存，避免每次翻页都重新解码一遍。
     *
     * @param uri 素材地址
     * @return data: 地址，取不到时返回空字符串
     */
    static String thumbnail(Context context, String uri) {
        if (uri == null || uri.isEmpty()) {
            return "";
        }
        synchronized (THUMBNAIL_CACHE) {
            String cached = THUMBNAIL_CACHE.get(uri);
            if (cached != null) {
                return cached;
            }
        }
        String data = thumbnailDataUrl(context, Uri.parse(uri));
        if (!data.isEmpty()) {
            synchronized (THUMBNAIL_CACHE) {
                if (THUMBNAIL_CACHE.size() >= THUMBNAIL_CACHE_MAX) {
                    THUMBNAIL_CACHE.clear();
                }
                THUMBNAIL_CACHE.put(uri, data);
            }
        }
        return data;
    }

    private static String thumbnailDataUrl(Context context, Uri uri) {
        Bitmap bitmap = loadThumbnail(context, uri);
        if (bitmap == null) {
            return "";
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, output);
            bitmap.recycle();
            return "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } catch (Exception exception) {
            Log.w(TAG, "thumbnail encode failed: " + exception);
            return "";
        }
    }

    private static Bitmap loadThumbnail(Context context, Uri uri) {
        if (isPdf(context, uri)) {
            return pdfFirstPage(context, uri);
        }
        // 导入的文件是应用私有目录里的 file://：ContentResolver.loadThumbnail 不支持
        // 这个 scheme，直接用路径解码；视频另外取一帧当封面。
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            if (path != null && "video".equals(importedTypeOf(uri.getLastPathSegment()))) {
                Bitmap frame = videoFrame(path);
                if (frame != null) {
                    return frame;
                }
            }
            return decodeFileScaled(path);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return context.getContentResolver().loadThumbnail(
                        uri, new Size(THUMBNAIL_MAX_SIZE, THUMBNAIL_MAX_SIZE), null);
            } catch (Exception exception) {
                Log.w(TAG, "load thumbnail failed: " + exception);
            }
        }
        return decodeScaled(context, uri);
    }

    private static boolean isPdf(Context context, Uri uri) {
        if ("pdf".equals(extensionOf(uri.getLastPathSegment()))) {
            return true;
        }
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        try {
            return "application/pdf".equals(context.getContentResolver().getType(uri));
        } catch (Exception exception) {
            return false;
        }
    }

    /** PDF 只渲染第一页作为真实封面；不支持或损坏时由网页端回退到类型封面。 */
    private static Bitmap pdfFirstPage(Context context, Uri uri) {
        ParcelFileDescriptor descriptor = null;
        PdfRenderer renderer = null;
        PdfRenderer.Page page = null;
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                String path = uri.getPath();
                if (path == null) {
                    return null;
                }
                descriptor = ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
            } else {
                descriptor = context.getContentResolver().openFileDescriptor(uri, "r");
            }
            if (descriptor == null) {
                return null;
            }
            renderer = new PdfRenderer(descriptor);
            if (renderer.getPageCount() < 1) {
                return null;
            }
            page = renderer.openPage(0);
            if (page.getWidth() <= 0 || page.getHeight() <= 0) {
                return null;
            }
            float scale = Math.min(
                    THUMBNAIL_MAX_SIZE / (float) page.getWidth(),
                    THUMBNAIL_MAX_SIZE / (float) page.getHeight());
            int width = Math.max(1, Math.round(page.getWidth() * scale));
            int height = Math.max(1, Math.round(page.getHeight() * scale));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            Matrix matrix = new Matrix();
            matrix.setScale(width / (float) page.getWidth(), height / (float) page.getHeight());
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        } catch (Exception exception) {
            Log.w(TAG, "PDF thumbnail failed: " + exception);
            return null;
        } finally {
            if (page != null) {
                try {
                    page.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响封面回退
                }
            }
            if (renderer != null) {
                try {
                    renderer.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响封面回退
                }
            }
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响封面回退
                }
            }
        }
    }

    /** 视频取一帧当封面，避免导入的录像在教程页里只有空白卡片。 */
    private static Bitmap videoFrame(String path) {
        android.media.MediaMetadataRetriever retriever = null;
        try {
            retriever = new android.media.MediaMetadataRetriever();
            retriever.setDataSource(path);
            return retriever.getFrameAtTime();
        } catch (Exception exception) {
            Log.w(TAG, "video thumbnail failed: " + exception);
            return null;
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                    // 释放失败不影响缩略图结果
                }
            }
        }
    }

    /** file:// 图片按路径解码，绕开 loadThumbnail 对 file scheme 的不支持。 */
    private static Bitmap decodeFileScaled(String path) {
        if (path == null) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            return BitmapFactory.decodeFile(path, options);
        } catch (Exception exception) {
            Log.w(TAG, "decode file thumbnail failed: " + exception);
            return null;
        }
    }

    /** 旧系统没有 loadThumbnail 时，按比例抽稀后解码。 */
    private static Bitmap decodeScaled(Context context, Uri uri) {
        InputStream input = null;
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
            Log.w(TAG, "decode thumbnail failed: " + exception);
            return null;
        } finally {
            close(input);
        }
    }

    private static int sampleSize(int width, int height) {
        int sample = 1;
        int longest = Math.max(width, height);
        while (longest / sample > THUMBNAIL_MAX_SIZE) {
            sample *= 2;
        }
        return sample;
    }

    private static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static void close(Cursor cursor) {
        if (cursor != null) {
            try {
                cursor.close();
            } catch (Exception ignored) {
                // 关闭失败不影响结果
            }
        }
    }

    private static void close(InputStream input) {
        if (input != null) {
            try {
                input.close();
            } catch (Exception ignored) {
                // 关闭失败不影响结果
            }
        }
    }
}

