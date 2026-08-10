package com.github.tvbox.osc.update;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 在线更新管理器（规格1）
 * - 多镜像清单：自动遍历 ghproxy / jsDelivr / 直连，主地址不可达自动切换
 * - 稳定后台下载：使用系统 DownloadManager（自带断点续传、通知、后台调度）
 * - 下载镜像失败自动切换下一个，避免单点慢/挂导致几KB级卡死
 * - 安装前 SHA-256 校验，防篡改；通过 FileProvider 调起安装
 */
public class UpdateManager {

    public interface CheckCallback {
        /** 回调结果：null 表示无更新或检查失败 */
        void onResult(UpdateInfo info);
    }

    private static final String TAG = "LdwUpdate";
    private static BroadcastReceiver receiver;

    /** 解析更新清单 JSON */
    public static UpdateInfo parseManifest(String json) {
        try {
            JSONObject o = new JSONObject(json);
            UpdateInfo info = new UpdateInfo();
            info.versionCode = o.optInt("versionCode", 0);
            info.versionName = o.optString("versionName", "");
            info.sha256 = o.optString("sha256", "").trim();
            info.changelog = o.optString("changelog", "");
            info.force = o.optBoolean("force", false);
            JSONArray urls = o.optJSONArray("apkUrls");
            if (urls == null || urls.length() == 0) {
                String single = o.optString("apkUrl", "");
                if (!single.isEmpty()) urls = new JSONArray().put(single);
            }
            java.util.ArrayList<String> list = new java.util.ArrayList<>();
            for (int i = 0; i < urls.length(); i++) {
                String u = urls.optString(i);
                if (u != null && !u.isEmpty()) list.add(u);
            }
            info.apkUrls = list.toArray(new String[0]);
            return info.isValid() ? info : null;
        } catch (Exception e) {
            Log.e(TAG, "parseManifest: " + e);
            return null;
        }
    }

    /** 后台检查更新：遍历多镜像清单，比较版本号，主线程回调 */
    public static void checkUpdate(final Context context, final int currentVersionCode, final CheckCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                UpdateInfo found = null;
                for (String base : UpdateConstants.MANIFEST_URLS) {
                    String json = httpGet(base);
                    if (json == null) continue;
                    UpdateInfo info = parseManifest(json);
                    if (info != null && info.versionCode > currentVersionCode) {
                        found = info;
                        break;
                    }
                }
                final UpdateInfo result = found;
                if (cb != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() { cb.onResult(result); }
                    });
                }
            }
        }).start();
    }

    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(UpdateConstants.CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(UpdateConstants.READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != 200) { conn.disconnect(); return null; }
            InputStream in = new BufferedInputStream(conn.getInputStream());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            return out.toString("utf-8");
        } catch (Exception e) {
            Log.e(TAG, "httpGet " + urlStr + ": " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static File getApkFile(Context context) {
        return new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ldw_update.apk");
    }

    /** 开始更新流程：后台下载 + 失败切多镜像 + 校验 + 安装 */
    public static void startUpdate(final Context context, final UpdateInfo info) {
        final Context app = context.getApplicationContext();
        savePending(app, info);
        setTriedIndex(app, 0);
        registerReceiver(app);
        enqueueCurrent(app, info);
    }

    private static void enqueueCurrent(Context context, UpdateInfo info) {
        int idx = getTriedIndex(context);
        if (info.apkUrls == null || idx >= info.apkUrls.length) {
            notifyError(context, "所有下载镜像均失败，请稍后重试");
            return;
        }
        String url = info.apkUrls[idx];
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) { notifyError(context, "系统下载服务不可用"); return; }
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
        req.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setTitle("光幕影院 更新 v" + info.versionName);
        req.setDescription("正在后台下载安装包…");
        req.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "ldw_update.apk");
        long id = dm.enqueue(req);
        saveDownloadId(context, id);
    }

    private static void registerReceiver(final Context context) {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != getDownloadId(context)) return;
                handleComplete(context);
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        context.registerReceiver(receiver, f);
    }

    private static void unregisterReceiver(Context context) {
        if (receiver != null) {
            try { context.unregisterReceiver(receiver); } catch (Exception ignored) {}
            receiver = null;
        }
    }

    private static void handleComplete(Context context) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) { notifyError(context, "系统下载服务不可用"); return; }
        DownloadManager.Query q = new DownloadManager.Query();
        q.setFilterById(getDownloadId(context));
        Cursor cur = dm.query(q);
        int status = DownloadManager.STATUS_FAILED;
        if (cur != null && cur.moveToFirst()) {
            status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        }
        if (cur != null) cur.close();
        UpdateInfo info = loadPending(context);
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            File apk = getApkFile(context);
            if (info != null && !info.sha256.isEmpty() && !verifySha256(apk, info.sha256)) {
                setTriedIndex(context, getTriedIndex(context) + 1);
                enqueueCurrent(context, info);
                return;
            }
            installApk(context, apk);
            clearPending(context);
            unregisterReceiver(context);
        } else {
            setTriedIndex(context, getTriedIndex(context) + 1);
            enqueueCurrent(context, info);
        }
    }

    /** 供 Activity onResume 补偿处理（防止下载完成广播丢失） */
    public static void resumePending(final Context context) {
        final Context app = context.getApplicationContext();
        if (getDownloadId(app) < 0) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                DownloadManager dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm == null) return;
                DownloadManager.Query q = new DownloadManager.Query();
                q.setFilterById(getDownloadId(app));
                Cursor cur = dm.query(q);
                int status = -1;
                if (cur != null && cur.moveToFirst()) {
                    status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                }
                if (cur != null) cur.close();
                final UpdateInfo info = loadPending(app);
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    final File apk = getApkFile(app);
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (info != null && !info.sha256.isEmpty() && !verifySha256(apk, info.sha256)) {
                                setTriedIndex(app, getTriedIndex(app) + 1);
                                enqueueCurrent(app, info);
                                return;
                            }
                            installApk(app, apk);
                            clearPending(app);
                            unregisterReceiver(app);
                        }
                    });
                } else if (status == DownloadManager.STATUS_FAILED) {
                    setTriedIndex(app, getTriedIndex(app) + 1);
                    enqueueCurrent(app, info);
                }
            }
        }).start();
    }

    public static boolean verifySha256(File file, String expected) {
        if (expected == null || expected.isEmpty() || file == null || !file.exists()) return false;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = new FileInputStream(file);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            in.close();
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString().equalsIgnoreCase(expected);
        } catch (Exception e) {
            Log.e(TAG, "verifySha256: " + e);
            return false;
        }
    }

    public static void installApk(Context context, File apk) {
        if (apk == null || !apk.exists()) return;
        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = androidx.core.content.FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", apk);
        } else {
            uri = Uri.fromFile(apk);
        }
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setData(uri);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    // ---------- 本地存储 ----------
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(UpdateConstants.SP_NAME, Context.MODE_PRIVATE);
    }

    private static void savePending(Context c, UpdateInfo info) {
        try {
            JSONObject o = new JSONObject();
            o.put("versionCode", info.versionCode);
            o.put("versionName", info.versionName);
            o.put("sha256", info.sha256);
            o.put("changelog", info.changelog);
            o.put("force", info.force);
            JSONArray a = new JSONArray();
            for (String u : info.apkUrls) a.put(u);
            o.put("apkUrls", a);
            sp(c).edit().putString("pending_info", o.toString()).apply();
        } catch (Exception e) { Log.e(TAG, "savePending: " + e); }
    }

    private static UpdateInfo loadPending(Context c) {
        String s = sp(c).getString("pending_info", null);
        if (s == null) return null;
        return parseManifest(s);
    }

    private static void setTriedIndex(Context c, int i) { sp(c).edit().putInt("tried_index", i).apply(); }
    private static int getTriedIndex(Context c) { return sp(c).getInt("tried_index", 0); }
    private static void saveDownloadId(Context c, long id) { sp(c).edit().putLong("download_id", id).apply(); }
    private static long getDownloadId(Context c) { return sp(c).getLong("download_id", -1); }
    private static void clearPending(Context c) {
        sp(c).edit().remove("pending_info").remove("tried_index").remove("download_id").apply();
    }

    private static void notifyError(Context c, String msg) {
        Log.e(TAG, "error: " + msg);
        clearPending(c);
        unregisterReceiver(c);
    }
}
