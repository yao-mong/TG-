/*
 * Geekgram - 撤回标记
 *
 * 对方撤回消息时，防撤回会把消息留在聊天里。这里给这些消息打个标记，
 * 界面上在时间旁边显示「已删除」，一眼能看出这条是被对面撤回的。
 * 标记持久化在全局设置里，重启 App 之后还在。
 */
package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class GeekgramRevokeMarks {

    private static final String PREF = "geekgram_revoked_marks";
    private static final int MAX = 2000;

    private static final Object lock = new Object();
    private static HashSet<Integer> marks;
    private static ArrayList<Integer> order;

    private static void ensureLoaded() {
        if (marks != null) {
            return;
        }
        marks = new HashSet<>();
        order = new ArrayList<>();
        try {
            SharedPreferences prefs = MessagesController.getGlobalMainSettings();
            String raw = prefs.getString(PREF, "");
            if (!TextUtils.isEmpty(raw)) {
                String[] parts = raw.split(",");
                for (int a = 0; a < parts.length; a++) {
                    String p = parts[a].trim();
                    if (p.length() == 0) {
                        continue;
                    }
                    try {
                        int mid = Integer.parseInt(p);
                        if (marks.add(mid)) {
                            order.add(mid);
                        }
                    } catch (Throwable ignore) {
                    }
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static void save() {
        try {
            StringBuilder sb = new StringBuilder();
            for (int a = 0; a < order.size(); a++) {
                if (a > 0) {
                    sb.append(',');
                }
                sb.append(order.get(a));
            }
            MessagesController.getGlobalMainSettings().edit().putString(PREF, sb.toString()).apply();
        } catch (Throwable ignore) {
        }
    }

    /** 这条消息是不是被对方撤回过的 */
    public static boolean isMarked(int mid) {
        if (mid == 0) {
            return false;
        }
        synchronized (lock) {
            ensureLoaded();
            return marks.contains(mid);
        }
    }

    /** 标记一批消息（只应该在拦下删除之后调用） */
    public static void mark(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        synchronized (lock) {
            ensureLoaded();
            boolean changed = false;
            for (int a = 0; a < ids.size(); a++) {
                Integer id = ids.get(a);
                if (id == null || id == 0) {
                    continue;
                }
                if (marks.add(id)) {
                    order.add(id);
                    changed = true;
                }
            }
            if (!changed) {
                return;
            }
            while (order.size() > MAX) {
                Integer old = order.remove(0);
                marks.remove(old);
            }
            save();
        }
    }

    public static void mark(int mid) {
        if (mid == 0) {
            return;
        }
        ArrayList<Integer> one = new ArrayList<>(1);
        one.add(mid);
        mark(one);
    }

    public static void unmark(int mid) {
        synchronized (lock) {
            ensureLoaded();
            if (marks.remove(mid)) {
                order.remove(Integer.valueOf(mid));
                save();
            }
        }
    }

    public static void clear() {
        synchronized (lock) {
            ensureLoaded();
            marks.clear();
            order.clear();
            save();
        }
    }

    public static int count() {
        synchronized (lock) {
            ensureLoaded();
            return marks.size();
        }
    }
}
