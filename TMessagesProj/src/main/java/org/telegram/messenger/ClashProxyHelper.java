/*
 * Geekram - 内置 Clash 代理支持
 *
 * Clash / Clash Meta / Mihomo 默认提供本机混合端口（同时支持 SOCKS5 与 HTTP）：
 *   7890  mixed   （默认）
 *   7891  socks
 *   7892  http
 *   1080  socks（部分配置）
 *
 * 这里自动探测本机端口，探到就一键把 Telegram 的连接接到 Clash 上，
 * 用户不需要手动去「设置 → 数据和存储 → 代理」里填地址端口。
 */
package org.telegram.messenger;

import android.content.SharedPreferences;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;

public class ClashProxyHelper {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int[] CANDIDATE_PORTS = {7890, 7891, 7892, 7893, 1080};

    private static final String PREF_AUTO = "geekrem_clash_auto";

    /** 最近一次探测到的端口，0 表示没探到 */
    public static volatile int detectedPort = 0;
    private static volatile boolean applying = false;

    private static SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean isAutoEnabled() {
        return prefs().getBoolean(PREF_AUTO, true);
    }

    public static void setAutoEnabled(boolean enabled) {
        prefs().edit().putBoolean(PREF_AUTO, enabled).apply();
        if (enabled) {
            applyAsync(false);
        } else {
            disable();
        }
    }

    /** 探测本机是否有 Clash 在监听，返回端口号，没有则 0 */
    public static int probe() {
        for (int port : CANDIDATE_PORTS) {
            if (canConnect(DEFAULT_HOST, port, 220)) {
                detectedPort = port;
                return port;
            }
        }
        detectedPort = 0;
        return 0;
    }

    private static boolean canConnect(String host, int port, int timeoutMs) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (Throwable ignore) {
            }
        }
    }

    /** 后台探测 + 一键接管 */
    public static void applyAsync() {
        applyAsync(false);
    }

    public static void applyAsync(boolean force) {
        if (!isAutoEnabled()) {
            return;
        }
        if (applying) {
            return;
        }
        applying = true;
        new Thread("geekrem-clash-probe") {
            @Override
            public void run() {
                final int port = probe();
                AndroidUtilities.runOnUIThread(() -> {
                    applying = false;
                    if (port > 0) {
                        applyPort(port, force);
                    }
                });
            }
        }.start();
    }

    /** 启动时静默接管：用户自己配了别的代理就不打扰 */
    public static void autoInit() {
        try {
            if (!isAutoEnabled()) {
                return;
            }
            SharedPreferences preferences = prefs();
            boolean proxyEnabled = preferences.getBoolean("proxy_enabled", false);
            if (proxyEnabled) {
                SharedConfig.loadProxyList();
                SharedConfig.ProxyInfo info = SharedConfig.currentProxy;
                if (info != null && !DEFAULT_HOST.equals(info.settings.getAddress())) {
                    return;
                }
            }
            applyAsync(true);
        } catch (Throwable ignore) {
        }
    }

    public static void applyPort(int port, boolean force) {
        if (port <= 0) {
            return;
        }
        try {
            SharedConfig.loadProxyList();
            SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
            if (!force && current != null && !DEFAULT_HOST.equals(current.settings.getAddress())) {
                // 用户自己配的代理优先
                return;
            }
            ProxySettings settings = ProxySettings.builder()
                    .setType(ProxySettings.Type.SOCKS5)
                    .setAddress(DEFAULT_HOST)
                    .setPort(port)
                    .build();

            SharedConfig.ProxyInfo info = null;
            for (SharedConfig.ProxyInfo item : SharedConfig.proxyList) {
                if (item != null && Objects.equals(item.settings, settings)) {
                    info = item;
                    break;
                }
            }
            if (info == null) {
                info = new SharedConfig.ProxyInfo(settings);
                SharedConfig.proxyList.add(0, info);
                SharedConfig.saveProxyList();
            }

            SharedConfig.currentProxy = info;
            detectedPort = port;

            SharedPreferences.Editor editor = prefs().edit();
            info.settings.toSharedPreferences(editor);
            editor.putBoolean("proxy_enabled", true);
            editor.commit();

            ConnectionsManager.setProxySettings(true, settings);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        } catch (Throwable ignore) {
        }
    }

    public static void disable() {
        try {
            SharedPreferences.Editor editor = prefs().edit();
            editor.putBoolean("proxy_enabled", false);
            editor.commit();
            ProxySettings settings = SharedConfig.currentProxy != null
                    ? SharedConfig.currentProxy.settings
                    : ProxySettings.EMPTY;
            ConnectionsManager.setProxySettings(false, settings);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        } catch (Throwable ignore) {
        }
    }

    /** 给界面用的一段状态说明 */
    public static String statusText() {
        if (!isAutoEnabled()) {
            return "已关闭";
        }
        int port = detectedPort;
        if (port == 0) {
            port = probe();
        }
        return port > 0 ? ("已连接 " + DEFAULT_HOST + ":" + port) : "未检测到本机 Clash";
    }
}
