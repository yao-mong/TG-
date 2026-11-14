/*
 * Geekgram - 内核控制接口（本机回环）
 *
 * 内核起来以后会在 127.0.0.1 上开一个很小的控制接口，用它就能：
 *   - 看当前订阅里有哪些节点、分成了哪些组
 *   - 切换当前用哪个节点 / 哪个组（等同 Clash 面板里点那个圆点）
 *   - 测某个节点 / 组的延迟
 *
 * 全部走本机回环，不出网，也不需要用户配置任何东西。
 */
package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class ClashApi {

    /** 内核控制端口，只监听 127.0.0.1 */
    public static final int API_PORT = 9090;
    /** 控制接口的本地口令，内核配置里写的是同一串 */
    public static final String SECRET = "geekgram-local-9f3a";

    private static final String DELAY_URL = "http://www.gstatic.com/generate_204";

    /** 控制接口通不通 */
    public static boolean isAvailable() {
        return request("GET", "/version", null, 900) != null;
    }

    /** 全部节点与分组 */
    public static JSONObject proxies() {
        String s = request("GET", "/proxies", null, 4000);
        if (TextUtils.isEmpty(s)) {
            return null;
        }
        try {
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 某一项的详情（分组才有 all / now） */
    public static JSONObject proxy(String name) {
        String s = request("GET", "/proxies/" + enc(name), null, 2500);
        if (TextUtils.isEmpty(s)) {
            return null;
        }
        try {
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 在一个分组里选中某一项 */
    public static boolean select(String group, String name) {
        if (TextUtils.isEmpty(group) || TextUtils.isEmpty(name)) {
            return false;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("name", name);
            return request("PUT", "/proxies/" + enc(group), body.toString(), 3000) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 测延迟。返回毫秒，-1 表示超时 / 不可用。
     */
    public static int delay(String name, int timeoutMs) {
        if (TextUtils.isEmpty(name)) {
            return -1;
        }
        String s = request("GET", "/proxies/" + enc(name) + "/delay?timeout=" + timeoutMs
                + "&url=" + enc(DELAY_URL), null, timeoutMs + 2500);
        if (TextUtils.isEmpty(s)) {
            return -1;
        }
        try {
            JSONObject o = new JSONObject(s);
            int d = o.optInt("delay", -1);
            return d > 0 ? d : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 从 history 里取最近一次测出来的延迟 */
    public static int lastDelay(JSONObject item) {
        if (item == null) {
            return -1;
        }
        try {
            org.json.JSONArray arr = item.optJSONArray("history");
            if (arr == null || arr.length() == 0) {
                return -1;
            }
            JSONObject last = arr.getJSONObject(arr.length() - 1);
            int d = last.optInt("delay", 0);
            return d > 0 ? d : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Throwable t) {
            return s;
        }
    }

    private static String request(String method, String path, String body, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("http://127.0.0.1:" + API_PORT + path);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(Math.min(timeoutMs, 2500));
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("Authorization", "Bearer " + SECRET);
            c.setRequestProperty("Content-Type", "application/json");
            c.setUseCaches(false);
            if (body != null) {
                c.setDoOutput(true);
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                OutputStream out = c.getOutputStream();
                try {
                    out.write(data);
                    out.flush();
                } finally {
                    try {
                        out.close();
                    } catch (Throwable ignore) {
                    }
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) {
                return code >= 400 ? null : "";
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 4 * 1024 * 1024) {
                    break;
                }
                bos.write(buf, 0, n);
            }
            try {
                in.close();
            } catch (Throwable ignore) {
            }
            if (code >= 400) {
                return null;
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
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
