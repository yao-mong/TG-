/*
 * Geekgram - 出国请求工具
 *
 * 直连优先；直连失败（域名被污染 / 连接被中断）时，用 DoH 拿到真实 IP，
 * 自己建 TLS 连接（带 SNI）再取，绕开系统 DNS。
 *
 * 订阅下载和 YOYO 面板都走这里，所以面板白屏、订阅拉不下来是同一套保底。
 */
package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class GeekgramNet {

    public static final String UA_CLASH = "clash.meta/1.19.34";
    public static final String UA_BROWSER = "Mozilla/5.0 (Linux; Android 14; Mobile) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    /** 这几个都是国内可达的 DoH，走 IP 直连，不吃本机 DNS */
    private static final String[] DOH = {
            "https://223.5.5.5/resolve?name=%s&type=A",
            "https://doh.pub/dns-query?name=%s&type=A",
            "https://dns.alidns.com/resolve?name=%s&type=A"
    };

    public static class Resp {
        public int code;
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body = new byte[0];

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public String header(String name) {
            return name == null ? null : headers.get(name.toLowerCase(Locale.US));
        }
    }

    // ------------------------------------------------------------------ 对外

    /**
     * 取一个地址（自动跟随最多 5 次跳转）。直连不行就走 DoH + 指定 IP 的 TLS 连接。
     */
    public static Resp fetch(String url, String ua, int maxBytes, List<String> diag) throws IOException {
        String firstError = null;
        try {
            Resp r = direct("GET", url, ua, null, null, maxBytes);
            if (r != null && r.code > 0 && r.code < 400) {
                return r;
            }
            if (r != null) {
                firstError = "HTTP " + r.code;
                note(diag, "直连返回 " + r.code);
            }
        } catch (Throwable t) {
            firstError = describe(t);
            note(diag, "直连失败 " + describe(t));
        }

        String cur = url;
        Resp last = null;
        for (int hop = 0; hop < 5; hop++) {
            URL u;
            try {
                u = new URL(cur);
            } catch (Throwable t) {
                break;
            }
            if (!"https".equalsIgnoreCase(u.getProtocol())) {
                break;
            }
            String host = u.getHost();
            int port = u.getPort() > 0 ? u.getPort() : 443;
            String path = TextUtils.isEmpty(u.getFile()) ? "/" : u.getFile();
            ArrayList<String> ips = new ArrayList<>();
            resolve(host, ips, diag, 0, new ArrayList<String>());
            if (ips.isEmpty()) {
                note(diag, "解析不到 " + host);
                break;
            }
            Resp got = null;
            for (int a = 0; a < ips.size() && a < 8; a++) {
                try {
                    got = rawRequest("GET", host, port, path, ua, null, null, maxBytes, ips.get(a));
                } catch (Throwable t) {
                    note(diag, ips.get(a) + " " + describe(t));
                }
                if (got != null) {
                    break;
                }
            }
            if (got == null) {
                break;
            }
            last = got;
            String loc = got.header("location");
            if (got.code >= 300 && got.code < 400 && !TextUtils.isEmpty(loc)) {
                try {
                    cur = new URL(u, loc).toString();
                } catch (Throwable t) {
                    break;
                }
                continue;
            }
            return got;
        }
        if (last != null) {
            return last;
        }
        throw new IOException(firstError != null ? firstError : "网络不可用");
    }

    /** 面板代理用：指定 host/path 取内容，方法可以是 GET/POST */
    public static Resp request(String method, String url, String ua, Map<String, String> extraHeaders,
                               byte[] postBody, int maxBytes, List<String> diag) throws IOException {
        String firstError = null;
        try {
            Resp r = direct(method, url, ua, extraHeaders, postBody, maxBytes);
            if (r != null && r.code > 0) {
                return r;
            }
        } catch (Throwable t) {
            firstError = describe(t);
            note(diag, "直连失败 " + describe(t));
        }
        URL u = new URL(url);
        if (!"https".equalsIgnoreCase(u.getProtocol())) {
            throw new IOException(firstError != null ? firstError : "只支持 https");
        }
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : 443;
        String path = TextUtils.isEmpty(u.getFile()) ? "/" : u.getFile();
        ArrayList<String> ips = new ArrayList<>();
        resolve(host, ips, diag, 0, new ArrayList<String>());
        for (int a = 0; a < ips.size() && a < 8; a++) {
            try {
                return rawRequest(method, host, port, path, ua, extraHeaders, postBody, maxBytes, ips.get(a));
            } catch (Throwable t) {
                note(diag, ips.get(a) + " " + describe(t));
            }
        }
        throw new IOException(firstError != null ? firstError : "连不上 " + host);
    }

    /** 把错误翻成人话 */
    public static String describe(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String m = t.getMessage();
        String cls = t.getClass().getSimpleName();
        if (m != null && (m.contains("ECONNABORTED") || m.contains("Connection aborted"))) {
            return "连接被中断";
        }
        if (m != null && (m.contains("ECONNREFUSED") || m.contains("Connection refused"))) {
            return "端口拒绝";
        }
        if (m != null && (m.contains("ETIMEDOUT") || m.contains("timeout") || m.contains("Timeout"))) {
            return "连接超时";
        }
        if (m != null && m.contains("UnknownHost")) {
            return "域名解析失败";
        }
        if (t instanceof javax.net.ssl.SSLException) {
            return "TLS 握手失败";
        }
        if (TextUtils.isEmpty(m)) {
            return cls;
        }
        return m.length() > 60 ? m.substring(0, 60) : m;
    }

    // --------------------------------------------------------------- DoH 解析

    private static void resolve(String host, ArrayList<String> out, List<String> diag, int depth, ArrayList<String> seen) {
        if (depth > 3 || seen.contains(host)) {
            return;
        }
        seen.add(host);
        for (int i = 0; i < DOH.length; i++) {
            HttpURLConnection c = null;
            try {
                String u = String.format(Locale.US, DOH[i], host);
                c = (HttpURLConnection) new URL(u).openConnection(Proxy.NO_PROXY);
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setRequestProperty("accept", "application/dns-json");
                c.setRequestProperty("user-agent", UA_CLASH);
                if (c.getResponseCode() != 200) {
                    continue;
                }
                String json = new String(readBytes(c.getInputStream(), 512 * 1024), StandardCharsets.UTF_8);
                if (TextUtils.isEmpty(json)) {
                    continue;
                }
                JSONObject o = new JSONObject(json);
                JSONArray ans = o.optJSONArray("Answer");
                if (ans == null) {
                    continue;
                }
                ArrayList<String> cnames = new ArrayList<>();
                for (int a = 0; a < ans.length(); a++) {
                    JSONObject item = ans.optJSONObject(a);
                    if (item == null) {
                        continue;
                    }
                    int type = item.optInt("type", 0);
                    String data = item.optString("data", "");
                    if (TextUtils.isEmpty(data)) {
                        continue;
                    }
                    if (type == 1) {
                        if (!out.contains(data)) {
                            out.add(data);
                        }
                    } else if (type == 5) {
                        cnames.add(data.endsWith(".") ? data.substring(0, data.length() - 1) : data);
                    }
                }
                for (int a = 0; a < cnames.size(); a++) {
                    resolve(cnames.get(a), out, diag, depth + 1, seen);
                }
                if (!out.isEmpty()) {
                    return;
                }
            } catch (Throwable t) {
                note(diag, "DoH " + i + " " + describe(t));
            } finally {
                if (c != null) {
                    try {
                        c.disconnect();
                    } catch (Throwable ignore) {
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ 直连 / 自建

    private static Resp direct(String method, String url, String ua, Map<String, String> extra,
                               byte[] postBody, int maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("Connection", "close");
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                String k = e.getKey();
                if (k.equalsIgnoreCase("host") || k.equalsIgnoreCase("content-length")
                        || k.equalsIgnoreCase("connection") || k.equalsIgnoreCase("accept-encoding")) {
                    continue;
                }
                c.setRequestProperty(k, e.getValue());
            }
        }
        if (postBody != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            OutputStream os = c.getOutputStream();
            try {
                os.write(postBody);
                os.flush();
            } finally {
                try {
                    os.close();
                } catch (Throwable ignore) {
                }
            }
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        Map<String, List<String>> hf = c.getHeaderFields();
        if (hf != null) {
            for (Map.Entry<String, List<String>> e : hf.entrySet()) {
                String k = e.getKey();
                if (k == null || e.getValue() == null || e.getValue().isEmpty()) {
                    continue;
                }
                r.headers.put(k.toLowerCase(Locale.US), e.getValue().get(0));
            }
        }
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        r.body = in == null ? new byte[0] : readBytes(in, maxBytes);
        return r;
    }

    private static Resp rawRequest(String method, String host, int port, String path, String ua,
                                   Map<String, String> extra, byte[] postBody, int maxBytes,
                                   String ip) throws Exception {
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, null, null);
        SSLSocketFactory f = sc.getSocketFactory();

        java.net.Socket raw = new java.net.Socket();
        raw.connect(new InetSocketAddress(ip, port), 15000);
        raw.setSoTimeout(30000);
        SSLSocket s = (SSLSocket) f.createSocket(raw, host, port, true);
        s.setSoTimeout(30000);
        s.startHandshake();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            sb.append("Host: ").append(host).append("\r\n");
            sb.append("User-Agent: ").append(ua).append("\r\n");
            sb.append("Accept: */*\r\n");
            sb.append("Accept-Encoding: identity\r\n");
            sb.append("Connection: close\r\n");
            if (extra != null) {
                for (Map.Entry<String, String> e : extra.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) {
                        continue;
                    }
                    String k = e.getKey();
                    if (k.equalsIgnoreCase("host") || k.equalsIgnoreCase("content-length")
                            || k.equalsIgnoreCase("connection") || k.equalsIgnoreCase("accept-encoding")) {
                        continue;
                    }
                    sb.append(k).append(": ").append(e.getValue()).append("\r\n");
                }
            }
            if (postBody != null) {
                sb.append("Content-Length: ").append(postBody.length).append("\r\n");
            }
            sb.append("\r\n");
            OutputStream os = s.getOutputStream();
            os.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (postBody != null) {
                os.write(postBody);
            }
            os.flush();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes + 1024L * 1024L) {
                    break;
                }
                bos.write(buf, 0, n);
            }
            return parseResponse(bos.toByteArray());
        } finally {
            try {
                s.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private static Resp parseResponse(byte[] raw) throws IOException {
        int split = -1;
        for (int a = 0; a + 3 < raw.length; a++) {
            if (raw[a] == '\r' && raw[a + 1] == '\n' && raw[a + 2] == '\r' && raw[a + 3] == '\n') {
                split = a;
                break;
            }
        }
        if (split < 0) {
            throw new IOException("响应格式不对");
        }
        String head = new String(raw, 0, split, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n");
        Resp r = new Resp();
        String status = lines.length > 0 ? lines[0] : "";
        String[] parts = status.split(" ");
        if (parts.length > 1) {
            try {
                r.code = Integer.parseInt(parts[1].trim());
            } catch (Throwable ignore) {
                r.code = 0;
            }
        }
        for (int a = 1; a < lines.length; a++) {
            int c = lines[a].indexOf(':');
            if (c <= 0) {
                continue;
            }
            String k = lines[a].substring(0, c).trim().toLowerCase(Locale.US);
            String v = lines[a].substring(c + 1).trim();
            if (!r.headers.containsKey(k)) {
                r.headers.put(k, v);
            }
        }
        int off = split + 4;
        byte[] body = new byte[Math.max(0, raw.length - off)];
        if (body.length > 0) {
            System.arraycopy(raw, off, body, 0, body.length);
        }
        String te = r.header("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.US).contains("chunked")) {
            body = dechunk(body);
        }
        r.body = body;
        return r;
    }

    private static byte[] dechunk(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (pos < data.length) {
            int lineEnd = -1;
            for (int a = pos; a + 1 < data.length; a++) {
                if (data[a] == '\r' && data[a + 1] == '\n') {
                    lineEnd = a;
                    break;
                }
            }
            if (lineEnd < 0) {
                break;
            }
            String sizeStr = new String(data, pos, lineEnd - pos, StandardCharsets.ISO_8859_1).trim();
            int semi = sizeStr.indexOf(';');
            if (semi > 0) {
                sizeStr = sizeStr.substring(0, semi).trim();
            }
            int size;
            try {
                size = Integer.parseInt(sizeStr, 16);
            } catch (Throwable t) {
                break;
            }
            pos = lineEnd + 2;
            if (size <= 0) {
                break;
            }
            if (pos + size > data.length) {
                size = data.length - pos;
            }
            out.write(data, pos, size);
            pos += size + 2;
        }
        return out.toByteArray();
    }

    private static byte[] readBytes(InputStream in, int maxBytes) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (in == null) {
            return new byte[0];
        }
        try {
            byte[] buf = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    break;
                }
                bos.write(buf, 0, n);
            }
        } catch (Throwable ignore) {
        } finally {
            try {
                in.close();
            } catch (Throwable ignore) {
            }
        }
        return bos.toByteArray();
    }

    private static void note(List<String> diag, String msg) {
        if (diag != null && diag.size() < 40 && msg != null) {
            try {
                diag.add(msg);
            } catch (Throwable ignore) {
            }
        }
    }
}
