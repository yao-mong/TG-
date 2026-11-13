/*
 * Geekgram - 内置 Clash 订阅支持
 *
 * 用法：在 代理 页面填入一条 Clash 订阅链接，App 会在本机拉起内置内核，
 * 由内核解析订阅里的节点（vless / vmess / trojan / ss / hysteria 等），
 * 然后在内网回环开一个 socks5 端口，Telegram 通过它出网。
 *
 * 用户不需要额外安装 Clash，也不需要手动导节点。
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
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
    private static final long SUB_TTL_MS = 6 * 60 * 60 * 1000L;

    private static volatile Process process;
    private static volatile boolean busy = false;
    private static volatile boolean running = false;
    private static volatile String lastError = "";

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
    }

    public static boolean hasSubscription() {
        return !TextUtils.isEmpty(getSubscription());
    }

    // ------------------------------------------------------------------ 状态

    public static boolean isBusy() {
        return busy;
    }

    public static boolean isRunning() {
        return running || probePort();
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

    public static String statusText() {
        if (!hasSubscription()) {
            return "未设置订阅，点这里填";
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
     * 启动内核。subUrl 为空时用已保存的订阅；refreshCache=false 时若本地订阅文件较新则不重新下载。
     */
    public static void startAsync(final String subUrl, final boolean forceRefresh, final Callback callback) {
        if (busy) {
            return;
        }
        busy = true;
        lastError = "";
        new Thread("geekgram-clash-core") {
            @Override
            public void run() {
                boolean ok = false;
                String err = null;
                try {
                    if (probePort()) {
                        ok = true;
                    } else {
                        String url = !TextUtils.isEmpty(subUrl) ? subUrl.trim() : getSubscription();
                        if (TextUtils.isEmpty(url)) {
                            throw new IllegalStateException("没有订阅链接");
                        }
                        File subFile = new File(dir(), SUB_FILE);
                        boolean needFetch = forceRefresh || !subFile.exists() || subFile.length() < 512
                                || (System.currentTimeMillis() - subFile.lastModified()) > SUB_TTL_MS;
                        if (needFetch) {
                            fetch(url, subFile);
                        }
                        writeConfig();
                        launch();
                        ok = waitPort(20000);
                        if (!ok) {
                            throw new IllegalStateException("内核没起来");
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
                    }
                    if (callback != null) {
                        callback.onResult(fok, ferr);
                    }
                });
            }
        }.start();
    }

    public static void stop() {
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
            new File(dir(), PID_FILE).delete();
        } catch (Throwable ignore) {
        }
        ClashProxyHelper.disable();
    }

    /** 启动时静默调用：有订阅就后台把内核拉起来，失败不打扰用户 */
    public static void autoStart() {
        if (!ClashProxyHelper.isAutoEnabled() || !hasSubscription() || busy || isRunning()) {
            return;
        }
        startAsync(null, false, null);
    }

    // ------------------------------------------------------------ 内部实现

    private static void fetch(String url, File target) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(45000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "clash.meta/1.19.34");
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("订阅返回 " + code);
            }
            in = conn.getInputStream();
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[16384];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (total > 32L * 1024 * 1024) {
                        break;
                    }
                }
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
            if (!tmp.renameTo(target)) {
                throw new IllegalStateException("写订阅文件失败");
            }
            target.setLastModified(System.currentTimeMillis());
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignore) {
            }
            if (conn != null) conn.disconnect();
            if (tmp.exists() && tmp.length() > 0) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    private static void writeConfig() throws Exception {
        String yaml = ""
                + "mixed-port: " + MIXED_PORT + "\n"
                + "allow-lan: false\n"
                + "bind-address: 127.0.0.1\n"
                + "mode: rule\n"
                + "log-level: warning\n"
                + "ipv6: false\n"
                + "unified-delay: true\n"
                + "tcp-concurrent: true\n"
                + "find-process-mode: off\n"
                + "geodata-mode: false\n"
                + "external-controller: ''\n"
                + "profile:\n"
                + "  store-selected: true\n"
                + "  store-fake-ip: false\n"
                + "dns:\n"
                + "  enable: true\n"
                + "  ipv6: false\n"
                + "  default-nameserver:\n"
                + "    - 223.5.5.5\n"
                + "    - 119.29.29.29\n"
                + "  nameserver:\n"
                + "    - https://dns.alidns.com/dns-query\n"
                + "    - https://sm2.doh.pub/dns-query\n"
                + "    - 223.5.5.5\n"
                + "proxy-providers:\n"
                + "  geekgram_sub:\n"
                + "    type: file\n"
                + "    path: ./" + SUB_FILE + "\n"
                + "    health-check:\n"
                + "      enable: true\n"
                + "      url: http://www.gstatic.com/generate_204\n"
                + "      interval: 600\n"
                + "proxy-groups:\n"
                + "  - name: PROXY\n"
                + "    type: url-test\n"
                + "    use:\n"
                + "      - geekgram_sub\n"
                + "    url: http://www.gstatic.com/generate_204\n"
                + "    interval: 1800\n"
                + "    timeout: 4000\n"
                + "    tolerance: 60\n"
                + "rules:\n"
                + "  - MATCH,PROXY\n";

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
        File bin = new File(ctx().getApplicationInfo().nativeLibraryDir, "libclashcore.so");
        if (!bin.exists()) {
            throw new IllegalStateException("内核文件缺失");
        }
        if (!bin.canExecute()) {
            //noinspection ResultOfMethodCallIgnored
            bin.setExecutable(true, false);
        }
        ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "-d", coreDir.getAbsolutePath());
        pb.directory(coreDir);
        pb.environment().put("HOME", coreDir.getAbsolutePath());
        pb.environment().put("TMPDIR", ctx().getCacheDir().getAbsolutePath());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(coreDir, LOG_FILE)));
        Process p = pb.start();
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
