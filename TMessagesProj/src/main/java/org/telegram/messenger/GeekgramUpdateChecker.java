/*
 * Geekgram - 云更新检查
 *
 * 后台是 Cloudflare Worker（/api/v1/update/check），管理员在网页后台发布版本，
 * App 启动时静默检查一次，有更新弹窗、强制版不让跳过。
 *
 * 后台地址存在设置里（geekgram_update_base），也可以用 DEFAULT_BASE 写死。
 */
package org.telegram.messenger;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class GeekgramUpdateChecker {

    /** 部署好 Worker 后可以把地址写死在这里，比如 "https://panel.xxx.workers.dev" */
    public static final String DEFAULT_BASE = "";

    private static final String PREF_BASE = "geekgram_update_base";
    private static final String PREF_LAST = "geekgram_update_last_check";
    private static final String PREF_DEVICE = "geekgram_update_device";
    private static final long MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L;

    public interface ResultCallback {
        /** ok=false 时 error 是给人看的一句话；hasUpdate=false 且 ok=true 表示已是最新 */
        void onResult(boolean ok, String error, UpdateInfo info);
    }

    public static class UpdateInfo {
        public boolean hasUpdate;
        public boolean force;
        public int versionCode;
        public String versionName;
        public long size;
        public String url;
        public String changelog;
        public String notice;
        public int latestVersionCode;
    }

    // ---------------------------------------------------------------- 配置

    public static String getBaseUrl() {
        try {
            String v = MessagesController.getGlobalMainSettings().getString(PREF_BASE, "");
            if (!TextUtils.isEmpty(v)) {
                return v.trim();
            }
        } catch (Throwable ignore) {
        }
        return DEFAULT_BASE;
    }

    public static void setBaseUrl(String url) {
        try {
            MessagesController.getGlobalMainSettings().edit()
                    .putString(PREF_BASE, url == null ? "" : url.trim()).apply();
        } catch (Throwable ignore) {
        }
    }

    public static boolean isConfigured() {
        return !TextUtils.isEmpty(getBaseUrl());
    }

    // ---------------------------------------------------------------- 入口

    /** 启动时调用：6 小时最多检查一次，失败静默 */
    public static void checkOnStart(final Activity activity) {
        if (activity == null || !isConfigured()) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            try {
                SharedPreferences p = MessagesController.getGlobalMainSettings();
                long last = p.getLong(PREF_LAST, 0);
                if (System.currentTimeMillis() - last < MIN_INTERVAL_MS) {
                    return;
                }
                p.edit().putLong(PREF_LAST, System.currentTimeMillis()).apply();
            } catch (Throwable ignore) {
            }
            checkAsync(activity, true, null);
        }, 5000);
    }

    /** 手动检查（用户点「检查更新」） */
    public static void checkNow(final Activity activity) {
        checkAsync(activity, true, null);
    }

    public static void checkAsync(final Activity activity, final boolean showDialog,
                                  final ResultCallback callback) {
        if (activity == null) {
            return;
        }
        final String base = getBaseUrl();
        if (TextUtils.isEmpty(base)) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.onResult(false, "未配置更新服务器", null));
            }
            return;
        }
        final String pkg = activity.getPackageName();
        final int vc = versionCode(activity);
        final String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        final String device = deviceId();

        new Thread(() -> {
            UpdateInfo info = null;
            String error = null;
            try {
                String url = base.replaceAll("/+$", "") + "/api/v1/update/check"
                        + "?app=" + enc(pkg)
                        + "&vc=" + vc
                        + "&channel=stable"
                        + "&abi=" + enc(abi)
                        + "&id=" + enc(device);
                String body = httpGet(url);
                JSONObject root = new JSONObject(body);
                if (root.optInt("code", -1) != 0) {
                    error = root.optString("message", "后台返回异常");
                } else {
                    JSONObject d = root.optJSONObject("data");
                    if (d != null) {
                        info = new UpdateInfo();
                        info.hasUpdate = d.optBoolean("hasUpdate", false);
                        info.force = d.optBoolean("force", false);
                        info.versionCode = d.optInt("versionCode", 0);
                        info.versionName = d.optString("versionName", "");
                        info.size = d.optLong("size", 0);
                        info.url = d.optString("url", "");
                        info.changelog = d.optString("changelog", "");
                        info.notice = d.optString("notice", "");
                        info.latestVersionCode = d.optInt("latestVersionCode", vc);
                    }
                }
            } catch (Throwable e) {
                error = "连不上更新服务器";
            }

            final UpdateInfo fInfo = info;
            final String fError = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (showDialog && fError == null && fInfo != null && fInfo.hasUpdate
                        && !activity.isFinishing()) {
                    showUpdateDialog(activity, fInfo);
                }
                if (callback != null) {
                    callback.onResult(fError == null, fError, fInfo);
                }
            });
        }, "geekgram-update").start();
    }

    // ---------------------------------------------------------------- 弹窗

    private static void showUpdateDialog(final Activity activity, final UpdateInfo info) {
        StringBuilder msg = new StringBuilder();
        if (!TextUtils.isEmpty(info.versionName)) {
            msg.append("新版本：").append(info.versionName).append('\n');
        }
        if (info.size > 0) {
            msg.append("大小：").append(fmtSize(info.size)).append('\n');
        }
        if (!TextUtils.isEmpty(info.changelog)) {
            msg.append('\n').append(info.changelog);
        }
        if (msg.length() == 0) {
            msg.append("有新版本可用");
        }

        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle(info.force ? "需要更新才能继续使用" : "发现新版本")
                .setMessage(msg.toString())
                .setPositiveButton("立即更新", (dialog, which) -> openUrl(activity, info.url));
        if (!info.force) {
            b.setNegativeButton("以后再说", null);
        }
        try {
            AlertDialog dialog = b.create();
            dialog.setCancelable(!info.force);
            dialog.setCanceledOnTouchOutside(!info.force);
            dialog.show();
        } catch (Throwable ignore) {
        }
    }

    private static void openUrl(Activity activity, String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable ignore) {
        }
    }

    // ---------------------------------------------------------------- 工具

    private static int versionCode(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                return (int) activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).getLongVersionCode();
            }
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionCode;
        } catch (Throwable e) {
            return 0;
        }
    }

    private static String deviceId() {
        try {
            SharedPreferences p = MessagesController.getGlobalMainSettings();
            String id = p.getString(PREF_DEVICE, "");
            if (TextUtils.isEmpty(id)) {
                try {
                    id = Settings.Secure.getString(ApplicationLoader.applicationContext.getContentResolver(),
                            Settings.Secure.ANDROID_ID);
                } catch (Throwable ignore) {
                }
                if (TextUtils.isEmpty(id)) {
                    id = Long.toHexString(System.nanoTime());
                }
                p.edit().putString(PREF_DEVICE, id).apply();
            }
            return id;
        } catch (Throwable e) {
            return "unknown";
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Throwable e) {
            return "";
        }
    }

    private static String fmtSize(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        double mb = bytes / 1024.0 / 1024.0;
        if (mb >= 1) {
            return String.format(java.util.Locale.US, "%.1f MB", mb);
        }
        return (bytes / 1024) + " KB";
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = null;
        try {
            URL u = new URL(urlStr);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "Geekgram-Android");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) {
                throw new IllegalStateException("HTTP " + code);
            }
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            return sb.toString();
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
