/*
 * Geekgram - 节点链接 / 订阅文本 转 Clash 配置
 *
 * 面板拉不下来的时候，用户可以直接把节点链接贴进来（或者从剪贴板一键导入），
 * 这里把它们翻译成内核能直接吃的 YAML proxies 列表，完全不需要联网。
 *
 * 支持：ss://  vmess://  vless://  trojan://  hysteria2://(hy2://)
 * 整段 base64（面板常见的 base64 节点列表）也认。
 */
package org.telegram.messenger;

import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class NodeLinkParser {

    public static class Result {
        public final String yaml;
        public final int count;
        public final List<String> skipped = new ArrayList<>();

        Result(String yaml, int count) {
            this.yaml = yaml;
            this.count = count;
        }
    }

    private static final String[] SCHEMES = {
            "ss://", "vmess://", "vless://", "trojan://", "hysteria2://", "hy2://"
    };

    /** 这段文本里有没有节点链接 */
    public static boolean looksLikeLinks(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        String low = text.toLowerCase(Locale.US);
        for (String s : SCHEMES) {
            if (low.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /** 整段是不是 base64（面板经常给 base64 的节点列表） */
    public static String tryBase64(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        String t = text.trim().replace("\n", "").replace("\r", "").replace(" ", "");
        if (t.length() < 24) {
            return null;
        }
        if (!t.matches("[A-Za-z0-9+/=_\\-]+")) {
            return null;
        }
        try {
            int flags = Base64.URL_SAFE | Base64.NO_WRAP;
            byte[] raw;
            try {
                raw = Base64.decode(t, flags);
            } catch (Throwable e) {
                raw = Base64.decode(t, Base64.DEFAULT | Base64.NO_WRAP);
            }
            String out = new String(raw, "UTF-8");
            if (out.indexOf("://") >= 0) {
                return out;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /** 把一段文本里的节点链接全转成 YAML；没有节点就返回 null */
    public static Result parse(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        String src = text.replace("\r", "\n");
        if (!looksLikeLinks(src)) {
            String decoded = tryBase64(src);
            if (decoded != null && looksLikeLinks(decoded)) {
                src = decoded;
            }
        }
        String[] lines = src.split("\\n");
        StringBuilder sb = new StringBuilder();
        List<String> skipped = new ArrayList<>();
        int count = 0;
        for (String lineRaw : lines) {
            if (lineRaw == null) {
                continue;
            }
            String line = lineRaw.trim();
            if (line.length() < 8) {
                continue;
            }
            String low = line.toLowerCase(Locale.US);
            try {
                String item = null;
                if (low.startsWith("ss://")) {
                    item = parseSs(line);
                } else if (low.startsWith("vmess://")) {
                    item = parseVmess(line);
                } else if (low.startsWith("vless://")) {
                    item = parseVless(line);
                } else if (low.startsWith("trojan://")) {
                    item = parseTrojan(line);
                } else if (low.startsWith("hysteria2://") || low.startsWith("hy2://")) {
                    item = parseHysteria2(line);
                }
                if (item != null) {
                    count++;
                    sb.append(item);
                }
            } catch (Throwable t) {
                skipped.add(low.substring(0, Math.min(low.length(), 16)));
            }
        }
        if (count == 0) {
            return null;
        }
        Result r = new Result("proxies:\n" + sb, count);
        r.skipped.addAll(skipped);
        return r;
    }

    // ------------------------------------------------------------- 各协议

    private static String parseSs(String url) {
        String body = url.substring(5);
        String tag = "";
        int hash = body.indexOf('#');
        if (hash >= 0) {
            tag = decode(body.substring(hash + 1));
            body = body.substring(0, hash);
        }
        String query = "";
        int qm = body.indexOf('?');
        if (qm >= 0) {
            query = body.substring(qm + 1);
            body = body.substring(0, qm);
        }
        String method;
        String password;
        String hostPort;
        int at = body.lastIndexOf('@');
        if (at >= 0) {
            String cred = body.substring(0, at);
            hostPort = body.substring(at + 1);
            String plain = b64(cred);
            if (plain == null || plain.indexOf(':') < 0) {
                plain = decode(cred);
            }
            int c = plain.indexOf(':');
            if (c <= 0) {
                return null;
            }
            method = plain.substring(0, c);
            password = plain.substring(c + 1);
        } else {
            String plain = b64(body);
            if (plain == null) {
                return null;
            }
            int c = plain.lastIndexOf('@');
            if (c <= 0) {
                return null;
            }
            String cred = plain.substring(0, c);
            hostPort = plain.substring(c + 1);
            int cc = cred.indexOf(':');
            if (cc <= 0) {
                return null;
            }
            method = cred.substring(0, cc);
            password = cred.substring(cc + 1);
        }
        String[] hp = splitHostPort(hostPort);
        if (hp == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("  - ").append(kv("name", tagOr(tag, hp[0] + ":" + hp[1], "SS"))).append("\n");
        sb.append("    type: ss\n");
        sb.append("    server: ").append(q(hp[0])).append("\n");
        sb.append("    port: ").append(hp[1]).append("\n");
        sb.append("    cipher: ").append(q(method)).append("\n");
        sb.append("    password: ").append(q(password)).append("\n");
        sb.append("    udp: true\n");
        String plugin = param(query, "plugin");
        if (!TextUtils.isEmpty(plugin)) {
            String dec = decode(plugin);
            // obfs-local / simple-obfs -> 内核用 obfs 参数表示
            if (dec.startsWith("obfs-local") || dec.startsWith("simple-obfs")) {
                Map<String, String> opts = parseSemi(dec);
                String mode = opts.get("obfs");
                String h = opts.get("obfs-host");
                if (!TextUtils.isEmpty(mode)) {
                    sb.append("    plugin: obfs\n");
                    sb.append("    plugin-opts:\n");
                    sb.append("      mode: ").append(q(mode)).append("\n");
                    if (!TextUtils.isEmpty(h)) {
                        sb.append("      host: ").append(q(h)).append("\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String parseVmess(String url) {
        String body = url.substring(8);
        String json = b64(body);
        if (json == null) {
            return null;
        }
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (Throwable t) {
            return null;
        }
        String add = o.optString("add");
        String port = o.optString("port", "");
        String id = o.optString("id");
        String ps = o.optString("ps", "");
        String net = o.optString("net", "tcp");
        String tls = o.optString("tls", "");
        String host = o.optString("host", "");
        String path = o.optString("path", "");
        String sni = o.optString("sni", "");
        String scy = o.optString("scy", "");
        int aid = 0;
        try {
            aid = Integer.parseInt(o.optString("aid", "0"));
        } catch (Throwable ignore) {
        }
        if (TextUtils.isEmpty(add) || TextUtils.isEmpty(id)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("  - ").append(kv("name", tagOr(ps, add, "VMess"))).append("\n");
        sb.append("    type: vmess\n");
        sb.append("    server: ").append(q(add)).append("\n");
        sb.append("    port: ").append(numOr(port, 443)).append("\n");
        sb.append("    uuid: ").append(q(id)).append("\n");
        sb.append("    alterId: ").append(aid).append("\n");
        sb.append("    cipher: ").append(q(TextUtils.isEmpty(scy) ? "auto" : scy)).append("\n");
        sb.append("    udp: true\n");
        if ("tls".equalsIgnoreCase(tls) || "reality".equalsIgnoreCase(tls)) {
            sb.append("    tls: true\n");
            if (!TextUtils.isEmpty(sni)) {
                sb.append("    servername: ").append(q(sni)).append("\n");
            } else if (!TextUtils.isEmpty(host)) {
                sb.append("    servername: ").append(q(host)).append("\n");
            }
        }
        appendTransport(sb, net, path, host, "");
        return sb.toString();
    }

    private static String parseVless(String url) {
        String body = url.substring(8);
        String tag = "";
        int hash = body.indexOf('#');
        if (hash >= 0) {
            tag = decode(body.substring(hash + 1));
            body = body.substring(0, hash);
        }
        String query = "";
        int qm = body.indexOf('?');
        if (qm >= 0) {
            query = body.substring(qm + 1);
            body = body.substring(0, qm);
        }
        int at = body.lastIndexOf('@');
        if (at <= 0) {
            return null;
        }
        String uuid = decode(body.substring(0, at));
        String[] hp = splitHostPort(body.substring(at + 1));
        if (hp == null) {
            return null;
        }
        String security = param(query, "security");
        String sni = param(query, "sni");
        String fp = param(query, "fp");
        String flow = param(query, "flow");
        String net = param(query, "type");
        String path = param(query, "path");
        String host = param(query, "host");
        String pbk = param(query, "pbk");
        String sid = param(query, "sid");
        String alpn = param(query, "alpn");
        String insecure = param(query, "allowInsecure");

        StringBuilder sb = new StringBuilder();
        sb.append("  - ").append(kv("name", tagOr(tag, hp[0], "VLESS"))).append("\n");
        sb.append("    type: vless\n");
        sb.append("    server: ").append(q(hp[0])).append("\n");
        sb.append("    port: ").append(hp[1]).append("\n");
        sb.append("    uuid: ").append(q(uuid)).append("\n");
        sb.append("    udp: true\n");
        boolean tls = "tls".equalsIgnoreCase(security) || "reality".equalsIgnoreCase(security);
        if (tls) {
            sb.append("    tls: true\n");
            if (!TextUtils.isEmpty(sni)) {
                sb.append("    servername: ").append(q(sni)).append("\n");
            }
            if (!TextUtils.isEmpty(alpn)) {
                sb.append("    alpn:\n");
                for (String a : alpn.split(",")) {
                    if (!TextUtils.isEmpty(a.trim())) {
                        sb.append("      - ").append(q(a.trim())).append("\n");
                    }
                }
            }
            if ("1".equals(insecure) || "true".equalsIgnoreCase(insecure)) {
                sb.append("    skip-cert-verify: true\n");
            }
        }
        if (!TextUtils.isEmpty(fp)) {
            sb.append("    client-fingerprint: ").append(q(fp)).append("\n");
        }
        if (!TextUtils.isEmpty(flow)) {
            sb.append("    flow: ").append(q(flow)).append("\n");
        }
        if ("reality".equalsIgnoreCase(security) && !TextUtils.isEmpty(pbk)) {
            sb.append("    reality-opts:\n");
            sb.append("      public-key: ").append(q(pbk)).append("\n");
            if (!TextUtils.isEmpty(sid)) {
                sb.append("      short-id: ").append(q(sid)).append("\n");
            }
        }
        // tcp + reality / vision 不需要 network 段
        if (!TextUtils.isEmpty(net) && !"tcp".equalsIgnoreCase(net)) {
            appendTransport(sb, net, path, host, param(query, "serviceName"));
        }
        return sb.toString();
    }

    private static String parseTrojan(String url) {
        String body = url.substring(9);
        String tag = "";
        int hash = body.indexOf('#');
        if (hash >= 0) {
            tag = decode(body.substring(hash + 1));
            body = body.substring(0, hash);
        }
        String query = "";
        int qm = body.indexOf('?');
        if (qm >= 0) {
            query = body.substring(qm + 1);
            body = body.substring(0, qm);
        }
        int at = body.lastIndexOf('@');
        if (at <= 0) {
            return null;
        }
        String pass = decode(body.substring(0, at));
        String[] hp = splitHostPort(body.substring(at + 1));
        if (hp == null) {
            return null;
        }
        String sni = param(query, "sni");
        if (TextUtils.isEmpty(sni)) {
            sni = param(query, "peer");
        }
        String net = param(query, "type");
        String path = param(query, "path");
        String host = param(query, "host");
        String insecure = param(query, "allowInsecure");
        String alpn = param(query, "alpn");

        StringBuilder sb = new StringBuilder();
        sb.append("  - ").append(kv("name", tagOr(tag, hp[0], "Trojan"))).append("\n");
        sb.append("    type: trojan\n");
        sb.append("    server: ").append(q(hp[0])).append("\n");
        sb.append("    port: ").append(hp[1]).append("\n");
        sb.append("    password: ").append(q(pass)).append("\n");
        sb.append("    udp: true\n");
        if (!TextUtils.isEmpty(sni)) {
            sb.append("    sni: ").append(q(sni)).append("\n");
        }
        if ("1".equals(insecure) || "true".equalsIgnoreCase(insecure)) {
            sb.append("    skip-cert-verify: true\n");
        }
        if (!TextUtils.isEmpty(alpn)) {
            sb.append("    alpn:\n");
            for (String a : alpn.split(",")) {
                if (!TextUtils.isEmpty(a.trim())) {
                    sb.append("      - ").append(q(a.trim())).append("\n");
                }
            }
        }
        if (!TextUtils.isEmpty(net) && !"tcp".equalsIgnoreCase(net)) {
            appendTransport(sb, net, path, host, "");
        }
        return sb.toString();
    }

    private static String parseHysteria2(String url) {
        String body = url.substring(url.indexOf("://") + 3);
        String tag = "";
        int hash = body.indexOf('#');
        if (hash >= 0) {
            tag = decode(body.substring(hash + 1));
            body = body.substring(0, hash);
        }
        String query = "";
        int qm = body.indexOf('?');
        if (qm >= 0) {
            query = body.substring(qm + 1);
            body = body.substring(0, qm);
        }
        int at = body.lastIndexOf('@');
        if (at <= 0) {
            return null;
        }
        String pass = decode(body.substring(0, at));
        String[] hp = splitHostPort(body.substring(at + 1));
        if (hp == null) {
            return null;
        }
        String sni = param(query, "sni");
        String insecure = param(query, "insecure");
        String obfs = param(query, "obfs");
        String obfsPass = param(query, "obfs-password");

        StringBuilder sb = new StringBuilder();
        sb.append("  - ").append(kv("name", tagOr(tag, hp[0], "HY2"))).append("\n");
        sb.append("    type: hysteria2\n");
        sb.append("    server: ").append(q(hp[0])).append("\n");
        sb.append("    port: ").append(hp[1]).append("\n");
        sb.append("    password: ").append(q(pass)).append("\n");
        if (!TextUtils.isEmpty(sni)) {
            sb.append("    sni: ").append(q(sni)).append("\n");
        }
        if ("1".equals(insecure) || "true".equalsIgnoreCase(insecure)) {
            sb.append("    skip-cert-verify: true\n");
        }
        if (!TextUtils.isEmpty(obfs)) {
            sb.append("    obfs: ").append(q(obfs)).append("\n");
            if (!TextUtils.isEmpty(obfsPass)) {
                sb.append("    obfs-password: ").append(q(obfsPass)).append("\n");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- 公共

    private static void appendTransport(StringBuilder sb, String net, String path, String host, String serviceName) {
        String n = net == null ? "" : net.toLowerCase(Locale.US);
        if ("ws".equals(n)) {
            sb.append("    network: ws\n");
            sb.append("    ws-opts:\n");
            sb.append("      path: ").append(q(TextUtils.isEmpty(path) ? "/" : path)).append("\n");
            if (!TextUtils.isEmpty(host)) {
                sb.append("      headers:\n");
                sb.append("        Host: ").append(q(host)).append("\n");
            }
        } else if ("grpc".equals(n)) {
            String svc = !TextUtils.isEmpty(serviceName) ? serviceName : path;
            sb.append("    network: grpc\n");
            sb.append("    grpc-opts:\n");
            sb.append("      grpc-service-name: ").append(q(TextUtils.isEmpty(svc) ? "" : svc)).append("\n");
        } else if ("h2".equals(n) || "http".equals(n)) {
            sb.append("    network: h2\n");
            sb.append("    h2-opts:\n");
            sb.append("      path: ").append(q(TextUtils.isEmpty(path) ? "/" : path)).append("\n");
            if (!TextUtils.isEmpty(host)) {
                sb.append("      host:\n");
                sb.append("        - ").append(q(host)).append("\n");
            }
        }
    }

    private static String[] splitHostPort(String hp) {
        if (TextUtils.isEmpty(hp)) {
            return null;
        }
        String host;
        String port;
        if (hp.startsWith("[")) {
            int end = hp.indexOf(']');
            if (end < 0) {
                return null;
            }
            host = hp.substring(1, end);
            int c = hp.indexOf(':', end);
            port = c >= 0 ? hp.substring(c + 1) : "443";
        } else {
            int c = hp.lastIndexOf(':');
            if (c <= 0) {
                host = hp;
                port = "443";
            } else {
                host = hp.substring(0, c);
                port = hp.substring(c + 1);
            }
        }
        if (TextUtils.isEmpty(host)) {
            return null;
        }
        port = port.replaceAll("[^0-9]", "");
        if (TextUtils.isEmpty(port)) {
            port = "443";
        }
        return new String[]{host, port};
    }

    private static String param(String query, String key) {
        if (TextUtils.isEmpty(query)) {
            return "";
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (part.substring(0, eq).equalsIgnoreCase(key)) {
                return decode(part.substring(eq + 1));
            }
        }
        return "";
    }

    private static Map<String, String> parseSemi(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        String[] parts = s.split(";");
        for (String p : parts) {
            int eq = p.indexOf('=');
            if (eq > 0) {
                out.put(p.substring(0, eq).trim().toLowerCase(Locale.US), p.substring(eq + 1).trim());
            }
        }
        return out;
    }

    private static String numOr(String v, int def) {
        try {
            int n = Integer.parseInt(v.replaceAll("[^0-9]", ""));
            return n > 0 ? String.valueOf(n) : String.valueOf(def);
        } catch (Throwable t) {
            return String.valueOf(def);
        }
    }

    private static String tagOr(String tag, String fallback, String prefix) {
        if (!TextUtils.isEmpty(tag)) {
            return tag;
        }
        if (!TextUtils.isEmpty(fallback)) {
            return fallback;
        }
        return prefix;
    }

    /** YAML 单引号安全输出 */
    private static String q(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("'", "''") + "'";
    }

    private static String kv(String key, String value) {
        return key + ": " + q(value);
    }

    private static String decode(String s) {
        if (s == null) {
            return "";
        }
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        } catch (Throwable t) {
            return s;
        }
    }

    private static String b64(String s) {
        if (TextUtils.isEmpty(s)) {
            return null;
        }
        String t = s.trim();
        int pad = (4 - t.length() % 4) % 4;
        StringBuilder sb = new StringBuilder(t);
        for (int i = 0; i < pad; i++) {
            sb.append('=');
        }
        t = sb.toString().replace('-', '+').replace('_', '/').replace("\n", "").replace("\r", "");
        try {
            byte[] raw = Base64.decode(t, Base64.DEFAULT);
            return new String(raw, "UTF-8");
        } catch (Throwable ignore) {
        }
        try {
            byte[] raw = Base64.decode(t, Base64.URL_SAFE);
            return new String(raw, "UTF-8");
        } catch (Throwable ignore) {
        }
        return null;
    }
}
