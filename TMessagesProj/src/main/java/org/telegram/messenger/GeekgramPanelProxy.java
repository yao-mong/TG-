/*
 * Geekgram - 面板本机代理
 *
 * 订阅面板（vpn.geeksv.com.cn）在部分网络下打不开：域名被污染、连接被中断，
 * 页面就白屏。这里在本机 127.0.0.1 起一个只转发面板域名的迷你代理，
 * 由 GeekgramNet 走 DoH + 自建 TLS 连接取内容，WebView 就能正常显示。
 *
 * 单连接、不保活，够用就行。
 */
package org.telegram.messenger;

import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class GeekgramPanelProxy {

    public static final String PANEL_HOST = "vpn.geeksv.com.cn";

    private static ServerSocket server;
    private static Thread thread;
    private static int port = -1;

    public static synchronized int start() throws IOException {
        if (server != null && !server.isClosed() && port > 0) {
            return port;
        }
        stop();
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        port = server.getLocalPort();
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    ServerSocket ss = server;
                    if (ss == null || ss.isClosed()) {
                        return;
                    }
                    Socket client = null;
                    try {
                        client = ss.accept();
                    } catch (Throwable t) {
                        return;
                    }
                    final Socket c = client;
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            handle(c);
                        }
                    }, "geekgram-panel-proxy");
                    t.setDaemon(true);
                    t.start();
                }
            }
        }, "geekgram-panel-acceptor");
        thread.setDaemon(true);
        thread.start();
        return port;
    }

    public static synchronized int port() {
        return port;
    }

    public static synchronized void stop() {
        if (server != null) {
            try {
                server.close();
            } catch (Throwable ignore) {
            }
        }
        server = null;
        port = -1;
        thread = null;
    }

    // ------------------------------------------------------------------ 处理

    private static void handle(Socket client) {
        OutputStream out = null;
        try {
            client.setSoTimeout(20000);
            InputStream in = client.getInputStream();
            out = client.getOutputStream();

            ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
            int prev3 = 0, prev2 = 0, prev1 = 0;
            int b;
            while ((b = in.read()) >= 0) {
                headBuf.write(b);
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') {
                    break;
                }
                prev3 = prev2;
                prev2 = prev1;
                prev1 = b;
                if (headBuf.size() > 128 * 1024) {
                    break;
                }
            }
            String head = new String(headBuf.toByteArray(), StandardCharsets.ISO_8859_1);
            String[] lines = head.split("\r\n");
            if (lines.length == 0) {
                return;
            }
            String[] reqLine = lines[0].split(" ");
            if (reqLine.length < 2) {
                return;
            }
            String method = reqLine[0].toUpperCase(Locale.US);
            String target = reqLine[1];

            Map<String, String> fwd = new LinkedHashMap<>();
            int contentLength = 0;
            for (int a = 1; a < lines.length; a++) {
                int c = lines[a].indexOf(':');
                if (c <= 0) {
                    continue;
                }
                String k = lines[a].substring(0, c).trim();
                String v = lines[a].substring(c + 1).trim();
                if (k.equalsIgnoreCase("content-length")) {
                    try {
                        contentLength = Integer.parseInt(v);
                    } catch (Throwable ignore) {
                    }
                    continue;
                }
                if (k.equalsIgnoreCase("host") || k.equalsIgnoreCase("connection")
                        || k.equalsIgnoreCase("proxy-connection") || k.equalsIgnoreCase("accept-encoding")
                        || k.equalsIgnoreCase("upgrade") || k.equalsIgnoreCase("te")) {
                    continue;
                }
                fwd.put(k, v);
            }

            byte[] body = null;
            if (contentLength > 0) {
                body = new byte[contentLength];
                int off = 0;
                while (off < contentLength) {
                    int n = in.read(body, off, contentLength - off);
                    if (n <= 0) {
                        break;
                    }
                    off += n;
                }
                if (off < contentLength) {
                    byte[] cut = new byte[off];
                    System.arraycopy(body, 0, cut, 0, off);
                    body = cut;
                }
            }

            String path = target;
            if (path.startsWith("http://") || path.startsWith("https://")) {
                try {
                    java.net.URL tu = new java.net.URL(path);
                    path = TextUtils.isEmpty(tu.getFile()) ? "/" : tu.getFile();
                } catch (Throwable ignore) {
                }
            }
            if (!path.startsWith("/")) {
                path = "/" + path;
            }

            String url = "https://" + PANEL_HOST + path;
            List<String> diag = new ArrayList<>();
            GeekgramNet.Resp resp;
            try {
                resp = GeekgramNet.request(method, url, GeekgramNet.UA_BROWSER, fwd, body, 12 * 1024 * 1024, diag);
            } catch (Throwable t) {
                writeError(out, t, diag);
                return;
            }

            StringBuilder sb = new StringBuilder();
            int code = resp.code > 0 ? resp.code : 502;
            sb.append("HTTP/1.1 ").append(code).append(' ');
            String reason = code == 200 ? "OK" : code == 302 ? "Found" : code == 404 ? "Not Found"
                    : code == 401 ? "Unauthorized" : code == 500 ? "Server Error" : "Status";
            sb.append(reason).append("\r\n");

            boolean hasType = false;
            for (Map.Entry<String, String> e : resp.headers.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k == null || v == null) {
                    continue;
                }
                if (k.equals("content-length") || k.equals("transfer-encoding")
                        || k.equals("content-encoding") || k.equals("connection")) {
                    continue;
                }
                if (k.equals("location")) {
                    v = rewriteLocation(v);
                }
                if (k.equals("set-cookie")) {
                    v = rewriteCookie(v);
                }
                if (k.equals("content-type")) {
                    hasType = true;
                }
                sb.append(k).append(": ").append(v).append("\r\n");
            }
            if (!hasType) {
                sb.append("Content-Type: text/html; charset=utf-8\r\n");
            }
            sb.append("Content-Length: ").append(resp.body.length).append("\r\n");
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(resp.body);
            out.flush();
        } catch (Throwable ignore) {
        } finally {
            try {
                if (out != null) {
                    out.flush();
                }
            } catch (Throwable ignore) {
            }
            try {
                client.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private static String rewriteLocation(String loc) {
        int p = port();
        if (p <= 0 || TextUtils.isEmpty(loc)) {
            return loc;
        }
        String base = "http://127.0.0.1:" + p;
        String l = loc;
        if (l.startsWith("https://" + PANEL_HOST)) {
            l = base + l.substring(("https://" + PANEL_HOST).length());
        } else if (l.startsWith("http://" + PANEL_HOST)) {
            l = base + l.substring(("http://" + PANEL_HOST).length());
        } else if (l.startsWith("/")) {
            l = base + l;
        }
        return l;
    }

    private static String rewriteCookie(String cookie) {
        if (TextUtils.isEmpty(cookie)) {
            return cookie;
        }
        String[] parts = cookie.split(";");
        StringBuilder sb = new StringBuilder();
        for (int a = 0; a < parts.length; a++) {
            String p = parts[a].trim();
            String low = p.toLowerCase(Locale.US);
            if (low.startsWith("domain=") || low.startsWith("secure") || low.startsWith("samesite")
                    || low.startsWith("partitioned")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static void writeError(OutputStream out, Throwable t, List<String> diag) {
        StringBuilder d = new StringBuilder();
        if (diag != null) {
            for (int a = 0; a < diag.size() && a < 6; a++) {
                d.append("<li>").append(escape(diag.get(a))).append("</li>");
            }
        }
        String html = "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>body{background:#0f1115;color:#e6e6e6;font:15px/1.6 -apple-system,sans-serif;padding:24px}"
                + "h3{font-size:17px;margin:0 0 10px}.b{background:#1b1e26;border:1px solid #2a2f3a;border-radius:10px;padding:12px;margin:12px 0}"
                + "code{color:#8ab4ff;word-break:break-all;font-size:13px}li{color:#9aa4b2;font-size:12px}</style></head><body>"
                + "<h3>面板没能加载</h3>"
                + "<div class=\"b\">原因：<code>" + escape(GeekgramNet.describe(t)) + "</code></div>"
                + (d.length() > 0 ? ("<div class=\"b\"><div style=\"color:#9aa4b2;font-size:12px\">最近几处尝试</div><ul style=\"margin:6px 0 0 18px;padding:0\">" + d + "</ul></div>") : "")
                + "<div style=\"color:#9aa4b2;font-size:12px\">订阅链接可以手动填：聊天列表左滑 → 代理 → Clash 订阅，粘贴链接即可，内核照样能用。</div>"
                + "</body></html>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        try {
            String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        } catch (Throwable ignore) {
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
