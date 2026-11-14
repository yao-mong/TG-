/*
 * Geekgram - 内置 Clash 订阅支持
 *
 * 用法：在 代理 页面填入一条 Clash 订阅链接，App 会在本机拉起内置内核，
 * 由内核解析订阅里的节点（vless / vmess / trojan / ss / hysteria2 等），
 * 然后在内网回环开一个 socks5 端口，Telegram 通过它出网。
 *
 * 拉不到订阅时（网络被掐）有三条退路：
 *   1) 本地缓存还能用就直接用，不让整次启动失败
 *   2) 走 App 里已经配好的 SOCKS5 代理去取
 *   3) 完全离线：把节点链接 / 整份 Clash 配置 / 剪贴板内容贴进来，本地生成
 *
 * 用户不需要额外安装 Clash，也不需要手动导节点。
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.utils.proxy.ProxySettings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ClashCoreManager {

    /** 本机内核对外开的混合端口（socks5 + http） */
    public static final int MIXED_PORT = 7899;

    private static final String DIR_NAME = "geekgram_core";
    private static final String SUB_FILE = "sub.yaml";
    private static final String CONFIG_FILE = "config.yaml";
    private static final String LOG_FILE = "core.log";
    private static final String PID_FILE = "core.pid";

    private static final String PREF_SUB = "geekgram_sub_url";
    private static final String PREF_SOURCE = "geekgram_sub_source";
    private static final String PREF_NODE_COUNT = "geekgram_sub_nodes";
    private static final long SUB_TTL_MS = 6 * 60 * 60 * 1000L;

    /** 订阅来源：联网拉 / 本地导入 */
    public static final String SOURCE_REMOTE = "remote";
    public static final String SOURCE_LOCAL = "local";

    private static volatile Process process;
    private static volatile boolean busy = false;
    private static volatile boolean running = false;
    private static volatile String lastError = "";
    private static volatile String lastNote = "";

    /** 连接守护：端口一断就自动把内核拉回来，不用用户手动点 */
    private static volatile Thread watchdog;
    private static volatile boolean watching = false;
    private static volatile int restartCount = 0;
    /** 当前线路名（PROXY 指向谁），给界面显示用，后台线程刷新 */
    private static volatile String selectionCache = "";

    public interface Callback {
        void onResult(boolean ok, String error);
    }

    private static Context ctx() {
        return ApplicationLoader.applicationContext;
    }

    private static SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    private static File dir() {
        File d = new File(ctx().getFilesDir(), DIR_NAME);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    // ---------------------------------------------------------------- 订阅地址

    public static String getSubscription() {
        try {
            return prefs().getString(PREF_SUB, "");
        } catch (Throwable t) {
            return "";
        }
    }

    public static void setSubscription(String url) {
        try {
            prefs().edit().putString(PREF_SUB, url == null ? "" : url.trim()).commit();
        } catch (Throwable ignore) {
        }
        setSource(SOURCE_REMOTE);
    }

    public static String getSource() {
        try {
            return prefs().getString(PREF_SOURCE, SOURCE_REMOTE);
        } catch (Throwable t) {
            return SOURCE_REMOTE;
        }
    }

    private static void setSource(String s) {
        try {
            prefs().edit().putString(PREF_SOURCE, s == null ? SOURCE_REMOTE : s).commit();
        } catch (Throwable ignore) {
        }
    }

    public static boolean isLocalSource() {
        return SOURCE_LOCAL.equals(getSource());
    }

    /** Geekgram: 是否在代理设置里隐藏「内核面板」入口，默认显示 */
    public static boolean isCorePanelHidden() {
        try {
            return prefs().getBoolean("core_panel_hidden", false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setCorePanelHidden(boolean hidden) {
        try {
            prefs().edit().putBoolean("core_panel_hidden", hidden).commit();
        } catch (Throwable ignore) {
        }
    }

    public static int getNodeCount() {
        try {
            return prefs().getInt(PREF_NODE_COUNT, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void setNodeCount(int n) {
        try {
            prefs().edit().putInt(PREF_NODE_COUNT, n).commit();
        } catch (Throwable ignore) {
        }
    }

    public static boolean hasSubscription() {
        return !TextUtils.isEmpty(getSubscription());
    }

    /** 能不能启动：远程要有链接，本地要有导入过东西 */
    public static boolean isConfigured() {
        if (isLocalSource()) {
            File f = new File(dir(), SUB_FILE);
            return f.exists() && f.length() > 64;
        }
        return hasSubscription();
    }

    // -------------------------------------------------------------- 离线导入

    /**
     * 导入一段文本。认得三种东西：
     *   - 一条 http(s) 订阅链接
     *   - 一份 Clash 配置（含 proxies:）
     *   - 一堆节点链接（ss/vmess/vless/trojan/hysteria2），base64 的也认
     * 返回给人看的提示。
     */
    public static String importText(String text) {
        if (TextUtils.isEmpty(text) || text.trim().length() < 8) {
            return "内容是空的，没导入";
        }
        String t = text.trim();

        // 1) 订阅链接
        if (t.indexOf('\n') < 0 && t.length() < 2048
                && (t.startsWith("http://") || t.startsWith("https://"))) {
            setSubscription(t);
            setSource(SOURCE_REMOTE);
            setNodeCount(0);
            File sub = new File(dir(), SUB_FILE);
            // 换了新链接，旧缓存不算数
            //noinspection ResultOfMethodCallIgnored
            sub.delete();
            lastNote = "";
            return "订阅链接已保存，点「启动内核」会自动拉取";
        }

        // 2) Clash 配置
        String proxiesBlock = extractProxiesBlock(t);
        if (proxiesBlock != null) {
            int n = countNodes(proxiesBlock);
            if (!writeSubFile("proxies:\n" + proxiesBlock)) {
                return "写配置文件失败，存储空间可能满了";
            }
            setSubscription("");
            setSource(SOURCE_LOCAL);
            setNodeCount(n);
            return "已导入配置，共 " + n + " 个节点（离线可用）";
        }

        // 3) 节点链接（可能整段 base64）
        NodeLinkParser.Result r = NodeLinkParser.parse(t);
        if (r == null) {
            String decoded = NodeLinkParser.tryBase64(t);
            if (decoded != null) {
                r = NodeLinkParser.parse(decoded);
            }
        }
        if (r != null && r.count > 0) {
            if (!writeSubFile(r.yaml)) {
                return "写配置文件失败，存储空间可能满了";
            }
            setSubscription("");
            setSource(SOURCE_LOCAL);
            setNodeCount(r.count);
            return "已导入 " + r.count + " 个节点（离线可用）";
        }

        return "没认出这段内容（要 Clash 配置、节点链接或订阅链接）";
    }

    public static void clearImport() {
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(dir(), SUB_FILE).delete();
        } catch (Throwable ignore) {
        }
        setSubscription("");
        setNodeCount(0);
        lastNote = "";
        lastError = "";
        stop();
    }

    /** 从一整份 YAML 里把 proxies: 这一段抠出来 */
    private static String extractProxiesBlock(String yaml) {
        String[] lines = yaml.replace("\r", "\n").split("\n");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.startsWith("proxies:")) {
                String rest = l.substring(8).trim();
                if (rest.startsWith("[")) {
                    if (rest.length() > 2) {
                        return "  " + rest.substring(1, rest.length() - 1).trim().replace("},", "}\n  - ");
                    }
                    return null;
                }
                start = i + 1;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (int i = start; i < lines.length; i++) {
            String l = lines[i];
            boolean blank = l.trim().length() == 0;
            boolean indented = l.startsWith(" ") || l.startsWith("\t") || l.startsWith("-") || blank;
            if (!blank && !indented) {
                break; // 到下一个顶层键了
            }
            if (blank) {
                continue;
            }
            // 顶层写成 "- name: xx" 的也补齐缩进
            if (l.startsWith("-")) {
                sb.append("  ").append(l).append("\n");
            } else {
                sb.append(l).append("\n");
            }
            any = true;
        }
        return any ? sb.toString() : null;
    }

    private static int countNodes(String block) {
        int n = 0;
        for (String line : block.split("\n")) {
            String t = line.trim();
            if (t.startsWith("- ") && t.contains("name:")) {
                n++;
            } else if (t.startsWith("- type:")) {
                n++;
            }
        }
        return n;
    }

    private static boolean writeSubFile(String content) {
        File tmp = new File(dir(), SUB_FILE + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(content.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (Throwable ignore) {
                }
            }
            File target = new File(dir(), SUB_FILE);
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!tmp.renameTo(target)) {
                return false;
            }
            target.setLastModified(System.currentTimeMillis());
            return true;
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
    }

    // ------------------------------------------------------------------ 状态

    public static boolean isBusy() {
        return busy;
    }

    public static boolean isRunning() {
        return running || probePort();
    }

    public static String getLastNote() {
        return lastNote;
    }

    private static boolean probePort() {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", MIXED_PORT), 180);
            running = true;
            return true;
        } catch (Throwable t) {
            running = false;
            return false;
        } finally {
            try {
                socket.close();
            } catch (Throwable ignore) {
            }
        }
    }

    public static String sourceText() {
        if (isLocalSource()) {
            int n = getNodeCount();
            return n > 0 ? ("本地导入 · " + n + " 个节点") : "本地导入";
        }
        String sub = getSubscription();
        if (TextUtils.isEmpty(sub)) {
            return "未设置";
        }
        return maskUrl(sub);
    }

    private static String maskUrl(String url) {
        try {
            int q = url.indexOf('?');
            String head = q >= 0 ? url.substring(0, q) : url;
            if (head.length() > 42) {
                return head.substring(0, 30) + "…" + head.substring(head.length() - 8);
            }
            return head;
        } catch (Throwable t) {
            return "已设置";
        }
    }

    public static String statusText() {
        if (!isConfigured()) {
            return isLocalSource() ? "本地没节点，点这里导入" : "未设置订阅，点这里填";
        }
        if (busy) {
            return "启动中…";
        }
        if (isRunning()) {
            return "运行中 · 127.0.0.1:" + MIXED_PORT;
        }
        if (!TextUtils.isEmpty(lastError)) {
            return "未运行（" + lastError + "）";
        }
        return "未运行，点这里启动";
    }

    // -------------------------------------------------------------- 启动/停止

    /**
     * 启动内核。subUrl 为空时用已保存的订阅；forceRefresh=true 时强制重新下载。
     */
    public static void startAsync(final String subUrl, final boolean forceRefresh, final Callback callback) {
        if (busy) {
            return;
        }
        busy = true;
        lastError = "";
        lastNote = "";
        new Thread("geekgram-clash-core") {
            @Override
            public void run() {
                boolean ok = false;
                String err = null;
                try {
                    if (probePort()) {
                        ok = true;
                    } else {
                        File subFile = new File(dir(), SUB_FILE);
                        boolean local = isLocalSource();
                        String url = !TextUtils.isEmpty(subUrl) ? subUrl.trim() : getSubscription();
                        if (local) {
                            if (!subFile.exists() || subFile.length() < 64) {
                                throw new IllegalStateException("本地还没有节点，导入一下");
                            }
                        } else {
                            if (TextUtils.isEmpty(url)) {
                                throw new IllegalStateException("没有订阅链接");
                            }
                            boolean needFetch = forceRefresh || !subFile.exists() || subFile.length() < 512
                                    || (System.currentTimeMillis() - subFile.lastModified()) > SUB_TTL_MS;
                            if (needFetch) {
                                if (subFile.exists() && subFile.length() > 512) {
                                    // 先拿缓存跑，能连就用，别因为刷新失败整个启动失败
                                    try {
                                        fetch(url, subFile);
                                    } catch (Throwable t) {
                                        lastNote = "用的本地缓存（" + shortReason(t) + "）";
                                    }
                                } else {
                                    fetch(url, subFile);
                                }
                            }
                        }
                        writeConfig();
                        launch();
                        ok = waitPort(20000);
                        if (!ok) {
                            String tail = logTail(240);
                            throw new IllegalStateException(TextUtils.isEmpty(tail) ? "内核没起来（没有日志）" : "内核没起来：" + tail);
                        }
                    }
                } catch (Throwable t) {
                    err = t.getMessage();
                    if (TextUtils.isEmpty(err)) {
                        err = t.getClass().getSimpleName();
                    }
                    lastError = err;
                }
                busy = false;
                final boolean fok = ok;
                final String ferr = err;
                AndroidUtilities.runOnUIThread(() -> {
                    if (fok) {
                        running = true;
                        lastError = "";
                        ClashProxyHelper.applyPort(MIXED_PORT, true);
                        startWatchdog();
                    }
                    if (callback != null) {
                        callback.onResult(fok, ferr);
                    }
                });
            }
        }.start();
    }

    public static void stop() {
        stopWatchdog();
        running = false;
        Process p = process;
        process = null;
        try {
            if (p != null) {
                long pid = pidOf(p);
                if (pid > 0) {
                    try {
                        Runtime.getRuntime().exec(new String[]{"kill", "-9", String.valueOf(pid)});
                    } catch (Throwable ignore) {
                    }
                }
                p.destroy();
            } else {
                // 可能是上次残留的实例
                File pidFile = new File(dir(), PID_FILE);
                if (pidFile.exists()) {
                    try {
                        String pid = readAll(new java.io.FileInputStream(pidFile)).trim();
                        if (!TextUtils.isEmpty(pid)) {
                            Runtime.getRuntime().exec(new String[]{"kill", "-9", pid});
                        }
                    } catch (Throwable ignore) {
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(dir(), PID_FILE).delete();
        } catch (Throwable ignore) {
        }
        ClashProxyHelper.disable();
    }

    /** 启动时静默调用：配好了就后台把内核拉起来，失败不打扰用户 */
    public static void autoStart() {
        if (!ClashProxyHelper.isAutoEnabled() || !isConfigured() || busy || isRunning()) {
            return;
        }
        startAsync(null, false, null);
    }

    // ------------------------------------------------------------ 内部实现

    // ------------------------------------------------------------ 连接守护

    /**
     * 内核起来以后每 8 秒看一眼：端口还在就顺手确认 Telegram 这边的代理没被关掉；
     * 端口没了（内核被系统杀了 / 自己退了）就静默拉起来，用户不用管。
     */
    private static void startWatchdog() {
        if (watching) {
            return;
        }
        watching = true;
        Thread t = new Thread("geekgram-clash-watch") {
            @Override
            public void run() {
                refreshSelectionCache();
                while (watching) {
                    try {
                        Thread.sleep(8000);
                    } catch (InterruptedException ignore) {
                        break;
                    }
                    if (!watching) {
                        break;
                    }
                    if (probeQuiet()) {
                        restartCount = 0;
                        ensureProxyApplied();
                        refreshSelectionCache();
                        continue;
                    }
                    if (busy) {
                        continue; // 正在启动中，别插一脚
                    }
                    if (restartCount >= 5) {
                        // 连着起不来就别死循环了，歇一分钟再试
                        try {
                            Thread.sleep(52000);
                        } catch (InterruptedException ignore) {
                            break;
                        }
                        if (!watching) {
                            break;
                        }
                        restartCount = 0;
                    }
                    restartCount++;
                    try {
                        writeConfig();
                        launch();
                        if (waitPort(20000)) {
                            running = true;
                            lastError = "";
                            ensureProxyApplied();
                        } else {
                            lastError = "内核退出了，正在重连";
                        }
                    } catch (Throwable t2) {
                        lastError = shortReason(t2);
                    }
                }
            }
        };
        watchdog = t;
        t.start();
    }

    /** 当前走的线路，界面直接读这个，不用等网络 */
    public static String selectionText() {
        return selectionCache;
    }

    private static void refreshSelectionCache() {
        try {
            org.json.JSONObject item = ClashApi.proxy("PROXY");
            if (item != null) {
                String now = item.optString("now", "");
                if (now != null && now.length() > 0) {
                    selectionCache = now;
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static void stopWatchdog() {
        watching = false;
        Thread t = watchdog;
        watchdog = null;
        if (t != null) {
            try {
                t.interrupt();
            } catch (Throwable ignore) {
            }
        }
    }

    /** 端口是通的，但 Telegram 那边代理被关掉或者指到别处了，补回去 */
    private static void ensureProxyApplied() {
        try {
            if (!ClashProxyHelper.isAutoEnabled()) {
                return;
            }
            boolean enabled = MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled", false);
            if (enabled && ClashProxyHelper.detectedPort == MIXED_PORT) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> ClashProxyHelper.applyPort(MIXED_PORT, false));
        } catch (Throwable ignore) {
        }
    }

    private static boolean probeQuiet() {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", MIXED_PORT), 300);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private static String shortReason(Throwable t) {
        String m = t == null ? "" : t.getMessage();
        if (TextUtils.isEmpty(m)) {
            return "网络不通";
        }
        return m.length() > 40 ? m.substring(0, 40) + "…" : m;
    }

    /**
     * 下载订阅。直连不行就用 DoH 解析真实 IP 再连（绕开被污染的 DNS 和断连）；
     * 再不行就走 App 里已经配好的 SOCKS5 代理取。
     */
    private static void fetch(String url, File target) throws Exception {
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        java.util.ArrayList<String> diag = new java.util.ArrayList<>();
        GeekgramNet.Resp resp = null;
        String failReason = null;
        try {
            resp = GeekgramNet.fetch(url, GeekgramNet.UA_CLASH, 32 * 1024 * 1024, diag);
        } catch (Throwable t) {
            failReason = GeekgramNet.describe(t);
        }
        byte[] body = resp == null ? null : resp.body;
        int code = resp == null ? -1 : resp.code;

        if (body == null || body.length < 128) {
            // 退路：走 App 现有的 socks5 代理再试一次
            byte[] viaProxy = fetchViaAppProxy(url);
            if (viaProxy != null && viaProxy.length >= 128) {
                body = viaProxy;
                code = 200;
                failReason = null;
            }
        }
        if (body == null || body.length < 128) {
            String why = failReason != null ? failReason
                    : (resp == null ? "取不到内容" : ("返回 " + code));
            throw new IllegalStateException("订阅拉不下来（" + why + "）");
        }
        if (code != 200) {
            throw new IllegalStateException("订阅返回 " + code);
        }
        String head = new String(body, 0, Math.min(body.length, 600),
                StandardCharsets.UTF_8).toLowerCase();
        if (head.contains("<html") && head.indexOf("proxies:") < 0 && head.indexOf("proxy-providers") < 0) {
            throw new IllegalStateException("订阅地址返回的是网页（链接可能已失效）");
        }
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(body);
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (Throwable ignore) {
                }
            }
            if (tmp.length() < 128) {
                throw new IllegalStateException("订阅内容是空的");
            }
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!tmp.renameTo(target)) {
                throw new IllegalStateException("写订阅文件失败");
            }
            target.setLastModified(System.currentTimeMillis());
        } finally {
            if (tmp.exists() && tmp.length() > 0) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    /** 用 App 里当前配置的 SOCKS5 代理去取订阅；没配代理就返回 null */
    private static byte[] fetchViaAppProxy(String url) {
        try {
            SharedConfig.ProxyInfo info = SharedConfig.currentProxy;
            if (info == null || info.settings == null) {
                return null;
            }
            ProxySettings ps = info.settings;
            if (ps.getType() != ProxySettings.Type.SOCKS5) {
                return null;
            }
            final String addr = ps.getAddress();
            final int port = ps.getPort();
            final String user = ps.getUser();
            final String pass = ps.getPassword();
            if (TextUtils.isEmpty(addr) || port <= 0) {
                return null;
            }
            if (!TextUtils.isEmpty(user)) {
                Authenticator.setDefault(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(user, pass == null ? "".toCharArray() : pass.toCharArray());
                    }
                });
            }
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(addr, port));
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(proxy);
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", GeekgramNet.UA_CLASH);
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            if (code != 200) {
                return null;
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 32 * 1024 * 1024) {
                    break;
                }
                bos.write(buf, 0, n);
            }
            try {
                in.close();
            } catch (Throwable ignore) {
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 分组定义：组名 + 匹配节点的正则（对应节点名里的地区字样） */
    private static final String[][] REGION_GROUPS = {
            {"香港节点", "(?i)香港|hongkong|hong.kong|(^|[^a-z])hk([^a-z]|$)"},
            {"台湾节点", "(?i)台湾|臺灣|taiwan|(^|[^a-z])tw([^a-z]|$)|台北"},
            {"狮城节点", "(?i)新加坡|狮城|獅城|singapore|(^|[^a-z])sg([^a-z]|$)"},
            {"日本节点", "(?i)日本|japan|(^|[^a-z])jp([^a-z]|$)|东京|東京|大阪"},
            {"美国节点", "(?i)美国|美國|united states|usa|(^|[^a-z])us([^a-z]|$)|洛杉矶|圣何塞|西雅图|达拉斯|纽约|芝加哥"},
            {"韩国节点", "(?i)韩国|韓國|korea|(^|[^a-z])kr([^a-z]|$)|首尔|首爾"},
    };

    private static void writeConfig() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("mixed-port: ").append(MIXED_PORT).append('\n');
        sb.append("allow-lan: false\n");
        sb.append("bind-address: 127.0.0.1\n");
        sb.append("mode: rule\n");
        sb.append("log-level: warning\n");
        sb.append("ipv6: false\n");
        sb.append("unified-delay: true\n");
        sb.append("tcp-concurrent: true\n");
        sb.append("find-process-mode: off\n");
        sb.append("geodata-mode: false\n");
        // 本机控制接口：节点选择 / 测延迟全靠它，只监听回环
        sb.append("external-controller: 127.0.0.1:").append(ClashApi.API_PORT).append('\n');
        sb.append("secret: ").append(ClashApi.SECRET).append('\n');
        sb.append("profile:\n");
        sb.append("  store-selected: true\n");
        sb.append("  store-fake-ip: false\n");
        sb.append("dns:\n");
        sb.append("  enable: true\n");
        sb.append("  ipv6: false\n");
        sb.append("  default-nameserver:\n");
        sb.append("    - 223.5.5.5\n");
        sb.append("    - 119.29.29.29\n");
        sb.append("  nameserver:\n");
        sb.append("    - https://dns.alidns.com/dns-query\n");
        sb.append("    - https://sm2.doh.pub/dns-query\n");
        sb.append("    - 223.5.5.5\n");
        sb.append("proxy-providers:\n");
        sb.append("  geekgram_sub:\n");
        sb.append("    type: file\n");
        sb.append("    path: ./").append(SUB_FILE).append('\n');
        sb.append("    health-check:\n");
        sb.append("      enable: true\n");
        sb.append("      url: http://www.gstatic.com/generate_204\n");
        sb.append("      interval: 600\n");
        sb.append("proxy-groups:\n");
        // 总开关：规则走的都是它，用户选哪个组 / 哪个节点就等于切线路
        sb.append("  - name: PROXY\n");
        sb.append("    type: select\n");
        sb.append("    proxies:\n");
        sb.append("      - 自动选择\n");
        sb.append("      - 手动切换\n");
        for (String[] g : REGION_GROUPS) {
            sb.append("      - ").append(g[0]).append('\n');
        }
        sb.append("      - DIRECT\n");
        // 自动选择：一直在所有节点里挑最快的
        sb.append("  - name: 自动选择\n");
        sb.append("    type: url-test\n");
        sb.append("    use:\n");
        sb.append("      - geekgram_sub\n");
        sb.append("    url: http://www.gstatic.com/generate_204\n");
        sb.append("    interval: 300\n");
        sb.append("    timeout: 4000\n");
        sb.append("    tolerance: 50\n");
        // 手动切换：列出订阅里全部节点，自己点
        sb.append("  - name: 手动切换\n");
        sb.append("    type: select\n");
        sb.append("    use:\n");
        sb.append("      - geekgram_sub\n");
        // 地区分组：按节点名自动归类
        for (String[] g : REGION_GROUPS) {
            sb.append("  - name: ").append(g[0]).append('\n');
            sb.append("    type: url-test\n");
            sb.append("    use:\n");
            sb.append("      - geekgram_sub\n");
            sb.append("    filter: \"").append(g[1]).append("\"\n");
            sb.append("    url: http://www.gstatic.com/generate_204\n");
            sb.append("    interval: 300\n");
            sb.append("    timeout: 4000\n");
            sb.append("    tolerance: 50\n");
        }
        sb.append("rules:\n");
        sb.append("  - MATCH,PROXY\n");
        String yaml = sb.toString();

        File cfg = new File(dir(), CONFIG_FILE);
        FileOutputStream out = new FileOutputStream(cfg);
        try {
            out.write(yaml.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            try {
                out.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private static void launch() throws Exception {
        File coreDir = dir();
        File bin = findCoreBinary();
        if (bin == null) {
            throw new IllegalStateException("内核没解出来（重装一次这个包）");
        }
        if (!bin.canExecute()) {
            //noinspection ResultOfMethodCallIgnored
            bin.setExecutable(true, false);
        }
        if (!bin.canExecute()) {
            throw new IllegalStateException("内核不可执行（安装方式有问题）");
        }
        File logFile = new File(coreDir, LOG_FILE);
        //noinspection ResultOfMethodCallIgnored
        logFile.delete();
        ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "-d", coreDir.getAbsolutePath());
        pb.directory(coreDir);
        pb.environment().put("HOME", coreDir.getAbsolutePath());
        pb.environment().put("TMPDIR", ctx().getCacheDir().getAbsolutePath());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(logFile));
        Process p;
        try {
            p = pb.start();
        } catch (Throwable t) {
            throw new IllegalStateException("系统不让内核启动（" + GeekgramNet.describe(t) + "）");
        }
        process = p;
        try {
            FileOutputStream pidOut = new FileOutputStream(new File(coreDir, PID_FILE));
            try {
                pidOut.write(String.valueOf(pidOf(p)).getBytes(StandardCharsets.UTF_8));
                pidOut.flush();
            } finally {
                pidOut.close();
            }
        } catch (Throwable ignore) {
        }
    }

    /** 内核二进制的位置：优先 nativeLibraryDir，取不到再按 ABI 目录兜一圈 */
    private static File findCoreBinary() {
        try {
            File f = new File(ctx().getApplicationInfo().nativeLibraryDir, "libclashcore.so");
            if (f.exists() && f.isFile()) {
                return f;
            }
        } catch (Throwable ignore) {
        }
        try {
            File libRoot = new File(ctx().getApplicationInfo().nativeLibraryDir).getParentFile();
            if (libRoot != null) {
                String[] abis = android.os.Build.SUPPORTED_ABIS;
                for (int a = 0; abis != null && a < abis.length; a++) {
                    File f = new File(new File(libRoot, abis[a]), "libclashcore.so");
                    if (f.exists() && f.isFile()) {
                        return f;
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        try {
            File dataLib = new File(ctx().getApplicationInfo().dataDir, "lib");
            if (dataLib.isDirectory()) {
                File[] list = dataLib.listFiles();
                for (int a = 0; list != null && a < list.length; a++) {
                    if ("libclashcore.so".equals(list[a].getName())) {
                        return list[a];
                    }
                    File nested = new File(list[a], "libclashcore.so");
                    if (nested.exists() && nested.isFile()) {
                        return nested;
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /** 内核日志全文（面板里能看、能复制） */
    public static String readLog() {
        try {
            File f = new File(dir(), LOG_FILE);
            if (!f.exists() || f.length() == 0) {
                return "";
            }
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            try {
                long len = raf.length();
                long start = Math.max(0, len - 64 * 1024);
                raf.seek(start);
                byte[] buf = new byte[(int) (len - start)];
                raf.readFully(buf);
                return new String(buf, StandardCharsets.UTF_8);
            } finally {
                raf.close();
            }
        } catch (Throwable t) {
            return "";
        }
    }

    /** 取内核日志末尾，出错时给个能看懂的原因 */
    private static String logTail(int max) {
        try {
            String s = readLog();
            if (TextUtils.isEmpty(s)) {
                return "";
            }
            s = s.replace('\n', ' ').replace('\r', ' ').trim();
            return s.length() > max ? s.substring(s.length() - max) : s;
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean waitPort(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", MIXED_PORT), 250);
                return true;
            } catch (Throwable ignore) {
            } finally {
                try {
                    socket.close();
                } catch (Throwable ignore) {
                }
            }
            Process p = process;
            if (p != null) {
                try {
                    //noinspection ResultOfMethodCallIgnored
                    p.exitValue();
                    return false; // 内核自己退了
                } catch (IllegalThreadStateException stillRunning) {
                    // 还在跑，继续等
                } catch (Throwable ignore) {
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignore) {
            }
        }
        return false;
    }

    /** 兼容旧 SDK：拿子进程 pid，取不到就算了 */
    private static long pidOf(Process p) {
        if (p == null) {
            return 0;
        }
        try {
            java.lang.reflect.Method m = Process.class.getMethod("pid");
            Object v = m.invoke(p);
            if (v instanceof Long) {
                return (Long) v;
            }
        } catch (Throwable ignore) {
        }
        return 0;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        try {
            in.close();
        } catch (Throwable ignore) {
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
