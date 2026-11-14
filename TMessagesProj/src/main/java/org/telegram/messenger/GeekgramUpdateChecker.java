/*
 * Geekgram - 云更新(内嵌版)
 *
 * 后台是自有云更新服务(/api/v1/update/check),管理员在网页后台发布版本、写公告、勾强制更新。
 * 客户端行为:
 * - 启动即检查(10 分钟内不重复),有更新推到主界面底部横幅;
 * - 后台勾了「强制更新」或 minVersionCode 高于当前版本 -> 启动直接弹公告,不可取消;
 * - 官方 Telegram 更新通道已关闭(BuildVars.CHECK_UPDATES = false),所有更新只走这里。
 */
package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Locale;

public class GeekgramUpdateChecker {

    /** 云更新后台地址;换服务器只改这一行 */
    public static String DEFAULT_BASE = "https://geekgram-update.pages.dev";

    private static final String PREF_BASE = "geekgram_update_base";
    private static final String PREF_LAST = "geekgram_update_last";
    private static final String PREF_INFO = "geekgram_update_info";
    private static final String PREF_SEEN = "geekgram_update_seen";
    private static final String PREF_FORCE_VC = "geekgram_force_vc";

    /** 两次自动检查的最小间隔 */
    private static final long MIN_INTERVAL_MS = 10 * 60 * 1000L;

    private static volatile Info lastInfo;
    private static final ArrayList<StateListener> listeners = new ArrayList<>();
    private static boolean checking;

    public static class Info {
        public boolean hasUpdate;
        public boolean force;
        public int versionCode;
        public String versionName;
        public long size;
        public String url;
        public String mirror;
        public String changelog;
        public String notice;
        public int minVersionCode;
        public int clientVersionCode;
        public boolean ok = true;
        public String error;
    }

    public interface ResCallback {
        void run(boolean ok, String error, Info info);
    }

    public interface StateListener {
        void onUpdateState(Info info);
    }

    // ---------------- 配置 ----------------

    private static SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    public static String getBaseUrl() {
        String url = prefs().getString(PREF_BASE, null);
        if (TextUtils.isEmpty(url)) {
            url = DEFAULT_BASE;
        }
        return url;
    }

    public static void setBaseUrl(String url) {
        if (!TextUtils.isEmpty(url)) {
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
        }
        prefs().edit().putString(PREF_BASE, url).apply();
    }

    public static boolean isConfigured() {
        return !TextUtils.isEmpty(getBaseUrl());
    }

    private static int cachedVersionCode = -1;

    public static int getVersionCode() {
        if (cachedVersionCode > 0) {
            return cachedVersionCode;
        }
        try {
            cachedVersionCode = ApplicationLoader.applicationContext.getPackageManager()
                    .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).versionCode;
        } catch (Throwable e) {
            cachedVersionCode = 0;
        }
        return cachedVersionCode;
    }

    public static String getVersionName() {
        try {
            return BuildVars.BUILD_VERSION_STRING == null ? "" : BuildVars.BUILD_VERSION_STRING;
        } catch (Throwable e) {
            return "";
        }
    }

    public static String getVersionLine() {
        String name = getVersionName();
        int code = getVersionCode();
        if (TextUtils.isEmpty(name)) {
            return String.valueOf(code);
        }
        return name + " (" + code + ")";
    }

    // ---------------- 监听 ----------------

    public static void addListener(StateListener l) {
        if (l == null) {
            return;
        }
        synchronized (listeners) {
            if (!listeners.contains(l)) {
                listeners.add(l);
            }
        }
    }

    public static void removeListener(StateListener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    public static Info getLastInfo() {
        if (lastInfo == null) {
            lastInfo = loadCachedInfo();
        }
        return lastInfo;
    }

    private static void notifyListeners(final Info info) {
        lastInfo = info;
        final ArrayList<StateListener> copy;
        synchronized (listeners) {
            copy = new ArrayList<>(listeners);
        }
        AndroidUtilities.runOnUIThread(() -> {
            for (StateListener l : copy) {
                try {
                    l.onUpdateState(info);
                } catch (Throwable ignore) {
                }
            }
        });
    }

    // ---------------- 检查 ----------------

    /** 启动时调用:先用缓存立刻上横幅,再按需联网刷新 */
    public static void checkOnStart(final Activity activity) {
        if (!isConfigured()) {
            return;
        }
        final Info cached = loadCachedInfo();
        if (cached != null) {
            lastInfo = cached;
            notifyListeners(cached);
            // 强制更新:启动就弹,不给跳过
            if (activity != null && cached.hasUpdate && cached.force && cached.versionCode > getVersionCode()) {
                markForcePending(cached.versionCode);
                AndroidUtilities.runOnUIThread(() -> showAnnouncement(activity, cached));
            }
        }
        final long last = prefs().getLong(PREF_LAST, 0);
        if (System.currentTimeMillis() - last < MIN_INTERVAL_MS) {
            return;
        }
        checkAsync(activity, false, null);
    }

    public static void checkAsync(final Activity activity, final boolean manual, final ResCallback callback) {
        if (!isConfigured()) {
            if (callback != null) {
                callback.run(false, "未配置服务器", null);
            }
            return;
        }
        if (checking && !manual) {
            return;
        }
        checking = true;
        new Thread(() -> {
            Info info = null;
            String error = null;
            try {
                info = request();
            } catch (Throwable e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            checking = false;
            prefs().edit().putLong(PREF_LAST, System.currentTimeMillis()).apply();
            final Info result = info;
            final String err = error;
            if (result != null) {
                saveCachedInfo(result);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (result == null) {
                    if (callback != null) {
                        callback.run(false, err, null);
                    }
                    return;
                }
                notifyListeners(result);
                if (callback != null) {
                    callback.run(true, null, result);
                }
                if (activity != null && !activity.isFinishing() && result.hasUpdate) {
                    if (result.force) {
                        markForcePending(result.versionCode);
                        showAnnouncement(activity, result);
                    } else if (manual || prefs().getInt(PREF_SEEN, 0) < result.versionCode) {
                        showAnnouncement(activity, result);
                    }
                }
            });
        }, "geekgram-update").start();
    }

    private static Info request() throws Exception {
        final String base = getBaseUrl();
        final StringBuilder sb = new StringBuilder(base);
        sb.append("/api/v1/update/check?app=").append(URLEncoder.encode(ApplicationLoader.applicationContext.getPackageName(), "UTF-8"));
        sb.append("&versionCode=").append(getVersionCode());
        sb.append("&sdk=").append(Build.VERSION.SDK_INT);
        String abi = "";
        try {
            if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
                abi = Build.SUPPORTED_ABIS[0];
            }
        } catch (Throwable ignore) {
        }
        sb.append("&abi=").append(URLEncoder.encode(abi == null ? "" : abi, "UTF-8"));
        sb.append("&uid=").append(UserConfig.selectedAccount);
        sb.append("&channel=").append(BuildVars.DEBUG_VERSION ? "beta" : "stable");

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(sb.toString()).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Geekgram-Android");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) {
                throw new Exception("HTTP " + code);
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                body.append(line);
            }
            r.close();
            return parse(body.toString());
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static Info parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        Info info = new Info();
        info.ok = o.optBoolean("ok", true);
        info.hasUpdate = o.optBoolean("hasUpdate", false);
        info.force = o.optBoolean("force", false);
        info.versionCode = o.optInt("versionCode", 0);
        info.versionName = o.optString("versionName", "");
        info.size = o.optLong("size", 0);
        info.url = o.optString("url", "");
        info.mirror = o.optString("mirror", "");
        info.changelog = o.optString("changelog", "");
        info.notice = o.optString("notice", "");
        info.minVersionCode = o.optInt("minVersionCode", 0);
        info.clientVersionCode = o.optInt("clientVersionCode", getVersionCode());
        if (info.minVersionCode > 0 && getVersionCode() < info.minVersionCode) {
            info.force = true;
        }
        return info;
    }

    private static void saveCachedInfo(Info info) {
        try {
            JSONObject o = new JSONObject();
            o.put("hasUpdate", info.hasUpdate);
            o.put("force", info.force);
            o.put("versionCode", info.versionCode);
            o.put("versionName", info.versionName);
            o.put("size", info.size);
            o.put("url", info.url == null ? "" : info.url);
            o.put("mirror", info.mirror == null ? "" : info.mirror);
            o.put("changelog", info.changelog == null ? "" : info.changelog);
            o.put("notice", info.notice == null ? "" : info.notice);
            o.put("minVersionCode", info.minVersionCode);
            prefs().edit().putString(PREF_INFO, o.toString()).apply();
        } catch (Throwable ignore) {
        }
    }

    private static Info loadCachedInfo() {
        try {
            String raw = prefs().getString(PREF_INFO, null);
            if (TextUtils.isEmpty(raw)) {
                return null;
            }
            return parse(raw);
        } catch (Throwable e) {
            return null;
        }
    }

    private static void markForcePending(int versionCode) {
        prefs().edit().putInt(PREF_FORCE_VC, versionCode).apply();
    }

    public static int getForcePendingVersion() {
        return prefs().getInt(PREF_FORCE_VC, 0);
    }

    public static void clearForcePending() {
        prefs().edit().putInt(PREF_FORCE_VC, 0).apply();
    }

    // ---------------- 公告弹窗 ----------------

    public static void showAnnouncement(final Activity activity, final Info info) {
        if (activity == null || info == null || activity.isFinishing()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) {
            return;
        }
        if (TextUtils.isEmpty(info.url) && TextUtils.isEmpty(info.mirror)) {
            return;
        }
        final boolean force = info.force;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(force ? "必须更新" : "发现新版本");
        builder.setMessage(buildMessage(info));
        builder.setPositiveButton("立即更新", (d, w) -> {
            prefs().edit().putInt(PREF_SEEN, info.versionCode).apply();
            if (!force) {
                clearForcePending();
            }
            String link = TextUtils.isEmpty(info.url) ? info.mirror : info.url;
            try {
                Browser.openUrl(activity, link);
            } catch (Throwable ignore) {
            }
        });
        if (!force) {
            builder.setNegativeButton("以后再说", (d, w) -> {
                prefs().edit().putInt(PREF_SEEN, info.versionCode).apply();
            });
        }
        AlertDialog dialog = builder.create();
        dialog.setCancelable(!force);
        dialog.setCanceledOnTouchOutside(!force);
        try {
            dialog.show();
        } catch (Throwable ignore) {
        }
    }

    private static String buildMessage(Info info) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(info.notice)) {
            sb.append(info.notice).append("\n\n");
        }
        sb.append("新版本 ").append(info.versionName)
          .append(" · ").append(formatSize(info.size)).append("\n");
        if (!TextUtils.isEmpty(info.changelog)) {
            sb.append("\n").append(info.changelog).append("\n");
        }
        if (!TextUtils.isEmpty(info.url)) {
            sb.append("\n下载地址：\n").append(info.url);
        }
        return sb.toString();
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "未知大小";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024f);
        }
        return String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f);
    }

    public static String bannerText(Info info) {
        if (info == null || !info.hasUpdate) {
            return "";
        }
        String version = TextUtils.isEmpty(info.versionName) ? String.valueOf(info.versionCode) : info.versionName;
        return "新版本 " + version + " · " + formatSize(info.size);
    }
}
