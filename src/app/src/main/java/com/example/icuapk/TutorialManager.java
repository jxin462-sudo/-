package com.example.icuapk;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「使用教程」的资料仓库。
 *
 * <p>清单(manifest)优先级：已经应用的更新版本 &gt; APK 内置版本。清单里的素材路径统一解析成
 * {@code file://} 绝对地址，网页端直接交给 img / video 标签使用。</p>
 *
 * <p>「后台推送」用外部目录模拟：把新版本的 {@code manifest.json} 与 {@code media} 目录放到
 * {@code externalFilesDir/icu_tutorial/}（或 {@code filesDir/tutorial/update/}），
 * App 进入教程页时比对版本号，版本更新就在页面上给出更新提示，
 * 用户确认后把清单与素材复制到 {@code filesDir/tutorial/} 长期保存，离线也能继续查看。</p>
 */
final class TutorialManager {

    private static final String TAG = "TutorialManager";
    /** APK 内置教程素材目录。 */
    private static final String ASSET_ROOT = "lanhu/tutorial/";
    private static final String ASSET_MANIFEST = ASSET_ROOT + "manifest.json";
    /** 应用私有目录：保存"已经应用的更新版本"。 */
    private static final String LOCAL_DIR = "tutorial";
    /** 后台推送目录：externalFilesDir/icu_tutorial/manifest.json */
    private static final String PUSH_DIR = "icu_tutorial";
    /** 可选的服务端地址；留空时只用本地目录模拟后台推送。 */
    private static final String UPDATE_URL = "";
    private static final int HTTP_TIMEOUT_MS = 6000;

    private TutorialManager() {
    }

    /** 清单来源：内置 assets 或本地已应用的更新版本。 */
    private static final class ManifestSource {
        final JSONObject json;
        final File baseDir;
        final boolean fromAssets;

        ManifestSource(JSONObject json, File baseDir, boolean fromAssets) {
            this.json = json;
            this.baseDir = baseDir;
            this.fromAssets = fromAssets;
        }
    }

    // ------------------------------------------------------------------ 对外接口

    /** 当前生效的教程清单，素材地址已经解析成绝对路径。 */
    static String manifestJson(Context context) {
        try {
            JSONObject root = new JSONObject();
            ManifestSource source = readCurrentManifest(context);
            if (source == null) {
                root.put("ok", false);
                root.put("message", "未找到教程清单");
                root.put("items", new JSONArray());
                return root.toString();
            }
            root.put("ok", true);
            root.put("version", source.json.optInt("version", 0));
            root.put("updatedAt", source.json.optString("updatedAt", ""));
            root.put("title", source.json.optString("title", "使用教程"));
            root.put("source", source.fromAssets ? "asset" : "local");
            root.put("items", resolveItems(source));
            Log.i(TAG, "tutorial manifest source=" + root.optString("source")
                    + " version=" + root.optInt("version")
                    + " items=" + root.optJSONArray("items").length());
            return root.toString();
        } catch (JSONException exception) {
            Log.e(TAG, "manifest failed", exception);
            return "{\"ok\":false,\"message\":\"教程清单解析失败\"}";
        }
    }

    /** 后台是否推送了新版本。 */
    static String updateStatusJson(Context context) {
        try {
            JSONObject root = new JSONObject();
            int current = currentVersion(context);
            root.put("currentVersion", current);
            File pending = pendingUpdateManifest(context);
            if (pending == null) {
                root.put("available", false);
                root.put("message", "教程已是最新版本");
                return root.toString();
            }
            JSONObject update = readJson(pending);
            if (update == null) {
                root.put("available", false);
                root.put("message", "更新包解析失败");
                return root.toString();
            }
            int version = update.optInt("version", 0);
            boolean available = version > current;
            root.put("available", available);
            root.put("version", version);
            root.put("updatedAt", update.optString("updatedAt", ""));
            root.put("title", update.optString("title", ""));
            root.put("summary", update.optString("summary", ""));
            root.put("message", available ? ("发现新版本 v" + version) : "教程已是最新版本");
            return root.toString();
        } catch (JSONException exception) {
            Log.e(TAG, "update status failed", exception);
            return "{\"available\":false,\"message\":\"更新状态读取失败\"}";
        }
    }

    /** 应用后台推送的新版本：复制清单与素材到私有目录，返回应用结果。 */
    static String applyUpdateJson(Context context) {
        try {
            JSONObject root = new JSONObject();
            File pending = pendingUpdateManifest(context);
            if (pending == null) {
                root.put("ok", false);
                root.put("message", "没有可用的更新包");
                return root.toString();
            }
            JSONObject update = readJson(pending);
            if (update == null) {
                root.put("ok", false);
                root.put("message", "更新包解析失败");
                return root.toString();
            }
            int version = update.optInt("version", 0);
            if (version <= currentVersion(context)) {
                root.put("ok", false);
                root.put("message", "教程已是最新版本");
                return root.toString();
            }
            File sourceBase = pending.getParentFile();
            File targetBase = localRoot(context);
            List<String> missing = new ArrayList<>();
            for (String relative : mediaPaths(update)) {
                File source = new File(sourceBase, relative);
                File target = new File(targetBase, relative);
                if (!source.exists() || !source.isFile()) {
                    missing.add(relative);
                    continue;
                }
                copy(source, target);
            }
            writeJson(new File(targetBase, "manifest.json"), update);
            root.put("ok", missing.isEmpty());
            root.put("version", version);
            root.put("updatedAt", update.optString("updatedAt", ""));
            root.put("missing", missing);
            root.put("message", missing.isEmpty()
                    ? ("教程已更新到 v" + version)
                    : ("教程已更新到 v" + version + "，但缺少 " + missing.size() + " 个素材"));
            return root.toString();
        } catch (JSONException | IOException exception) {
            Log.e(TAG, "apply update failed", exception);
            return "{\"ok\":false,\"message\":\"更新失败\"}";
        }
    }

    // ------------------------------------------------------------------ 内部实现

    private static File localRoot(Context context) {
        return new File(context.getFilesDir(), LOCAL_DIR);
    }

    private static File localManifestFile(Context context) {
        return new File(localRoot(context), "manifest.json");
    }

    private static ManifestSource readCurrentManifest(Context context) {
        File local = localManifestFile(context);
        if (local.exists()) {
            JSONObject json = readJson(local);
            if (json != null) {
                return new ManifestSource(json, local.getParentFile(), false);
            }
        }
        JSONObject json = readAssetManifest(context);
        return json == null ? null : new ManifestSource(json, null, true);
    }

    private static int currentVersion(Context context) {
        ManifestSource source = readCurrentManifest(context);
        return source == null ? 0 : source.json.optInt("version", 0);
    }

    private static JSONObject readAssetManifest(Context context) {
        try {
            InputStream input = context.getAssets().open(ASSET_MANIFEST);
            try {
                return new JSONObject(readText(input));
            } finally {
                close(input);
            }
        } catch (IOException exception) {
            Log.e(TAG, "read asset manifest failed", exception);
            return null;
        } catch (JSONException exception) {
            Log.e(TAG, "parse asset manifest failed", exception);
            return null;
        }
    }

    /** 找出后台推送的新版本清单：外部私有目录 → 私有目录 → 可选的服务端地址。 */
    private static File pendingUpdateManifest(Context context) {
        File external = context.getExternalFilesDir(null);
        if (external != null) {
            File candidate = new File(new File(external, PUSH_DIR), "manifest.json");
            if (candidate.isFile()) {
                return candidate;
            }
        }
        File candidate = new File(new File(localRoot(context), "update"), "manifest.json");
        if (candidate.isFile()) {
            return candidate;
        }
        return downloadRemoteManifest(context);
    }

    /** 可选的服务端通道：把远端清单缓存到本地再比对，离线时直接跳过。 */
    private static File downloadRemoteManifest(Context context) {
        if (UPDATE_URL == null || UPDATE_URL.isEmpty()) {
            return null;
        }
        File cache = new File(localRoot(context), "remote_manifest.json");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(UPDATE_URL).openConnection();
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            if (connection.getResponseCode() != 200) {
                return null;
            }
            InputStream input = connection.getInputStream();
            try {
                writeText(cache, readText(input));
            } finally {
                close(input);
            }
            return cache.isFile() ? cache : null;
        } catch (IOException exception) {
            Log.w(TAG, "remote manifest unavailable: " + exception.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 把清单里的素材路径解析成绝对可访问地址。 */
    private static JSONArray resolveItems(ManifestSource source) throws JSONException {
        JSONArray items = new JSONArray();
        JSONArray rawItems = source.json.optJSONArray("items");
        if (rawItems == null) {
            return items;
        }
        for (int i = 0; i < rawItems.length(); i += 1) {
            JSONObject raw = rawItems.optJSONObject(i);
            if (raw == null) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("id", raw.optString("id", "item_" + i));
            item.put("title", raw.optString("title", "教程资料"));
            item.put("type", raw.optString("type", "imageText"));
            item.put("summary", raw.optString("summary", ""));
            item.put("duration", raw.optInt("duration", 0));
            item.put("thumb", absoluteUri(source, raw.optString("thumb", "")));
            item.put("src", absoluteUri(source, raw.optString("src", "")));
            JSONArray rawPages = raw.optJSONArray("pages");
            JSONArray pages = new JSONArray();
            if (rawPages != null) {
                for (int p = 0; p < rawPages.length(); p += 1) {
                    JSONObject rawPage = rawPages.optJSONObject(p);
                    if (rawPage == null) {
                        continue;
                    }
                    JSONObject page = new JSONObject();
                    page.put("title", rawPage.optString("title", ""));
                    page.put("text", rawPage.optString("text", ""));
                    page.put("image", absoluteUri(source, rawPage.optString("image", "")));
                    pages.put(page);
                }
            }
            item.put("pages", pages);
            items.put(item);
        }
        return items;
    }

    private static String absoluteUri(ManifestSource source, String relative) {
        if (relative == null || relative.isEmpty()) {
            return "";
        }
        if (relative.startsWith("file://") || relative.startsWith("http://") || relative.startsWith("https://")) {
            return relative;
        }
        String normalized = relative.startsWith("/") ? relative.substring(1) : relative;
        if (source.fromAssets) {
            return "file:///android_asset/" + ASSET_ROOT + normalized;
        }
        return Uri.fromFile(new File(source.baseDir, normalized)).toString();
    }

    /** 清单里引用到的全部素材相对路径。 */
    private static Set<String> mediaPaths(JSONObject manifest) {
        Set<String> paths = new LinkedHashSet<>();
        JSONArray items = manifest.optJSONArray("items");
        if (items == null) {
            return paths;
        }
        for (int i = 0; i < items.length(); i += 1) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            addPath(paths, item.optString("thumb", ""));
            addPath(paths, item.optString("src", ""));
            JSONArray pages = item.optJSONArray("pages");
            if (pages == null) {
                continue;
            }
            for (int p = 0; p < pages.length(); p += 1) {
                JSONObject page = pages.optJSONObject(p);
                if (page != null) {
                    addPath(paths, page.optString("image", ""));
                }
            }
        }
        return paths;
    }

    private static void addPath(Set<String> paths, String relative) {
        if (relative == null || relative.isEmpty()) {
            return;
        }
        if (relative.startsWith("file://") || relative.startsWith("http://") || relative.startsWith("https://")) {
            return;
        }
        paths.add(relative.startsWith("/") ? relative.substring(1) : relative);
    }

    // ------------------------------------------------------------------ 基础读写

    private static JSONObject readJson(File file) {
        try {
            InputStream input = new FileInputStream(file);
            try {
                return new JSONObject(readText(input));
            } finally {
                close(input);
            }
        } catch (IOException exception) {
            Log.e(TAG, "read json failed: " + file, exception);
            return null;
        } catch (JSONException exception) {
            Log.e(TAG, "parse json failed: " + file, exception);
            return null;
        }
    }

    private static void writeJson(File file, JSONObject json) throws IOException, JSONException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录");
        }
        OutputStream output = new FileOutputStream(file);
        try {
            output.write(json.toString(2).getBytes("UTF-8"));
        } finally {
            close(output);
        }
    }

    private static String readText(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString("UTF-8");
    }

    private static void writeText(File file, String text) throws IOException {
        OutputStream output = new FileOutputStream(file);
        try {
            output.write(text.getBytes("UTF-8"));
        } finally {
            close(output);
        }
    }

    private static void copy(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录");
        }
        InputStream input = new FileInputStream(source);
        try {
            copy(input, target);
        } finally {
            close(input);
        }
    }

    private static void copy(InputStream input, File target) throws IOException {
        OutputStream output = new FileOutputStream(target);
        try {
            copy(input, output);
        } finally {
            close(output);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) > 0) {
            output.write(chunk, 0, read);
        }
        output.flush();
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 关闭失败不影响主流程
        }
    }
}
