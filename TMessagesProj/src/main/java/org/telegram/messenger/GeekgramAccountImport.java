package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * Geekgram: 账号数据导入的「暂存 + 开机应用」。
 *
 * 为什么不能在导入页直接写 files/tgnet.dat：
 * 导入是在 App 运行中做的，此时 tgnet 的网络层早就把旧配置读进 native 内存了。
 * 它随时可能 saveConfig() 把内存里那份旧数据写回 tgnet.dat，把我们刚写进去的
 * 凭据原样盖掉。结果就是：SharedPreferences 里已经是新账号（界面上「登上去了」），
 * 但 native 手上没有对应的 auth key，一发请求就 401，触发 onLogout，
 * 几秒钟后又被踢回登录页。
 *
 * 所以改成：导入时只把数据写进 files/.accimport 暂存区 → 立刻杀进程 →
 * 下次进程启动的最早期（native 和 UserConfig 都还没起来）把暂存区落到正式位置。
 * 那之后读到的就是干净的新数据，中间没有任何窗口能被旧状态覆盖。
 */
public class GeekgramAccountImport {

    private static final String STAGE_NAME = ".accimport";
    private static final String FLAG_NAME = "pending";
    private static final Pattern DAT_NAME = Pattern.compile("^(tgnet|dc[0-9]+conf)\\.dat$");
    private static final Pattern BAK_NAME = Pattern.compile("^(tgnet|dc[0-9]+conf)\\.dat\\.bak$");

    /** 上面一次导入里，native 触发过强制登出（401）时的时间，登录页据此提示 */
    private static final String FORCE_LOGOUT_PREF = "geekgram_import";
    private static final String KEY_FORCE_LOGOUT_AT = "force_logout_at";
    private static final String KEY_IMPORTED_AT = "imported_at";

    public static File stageDir() {
        return new File(ApplicationLoader.getFilesDirFixed(), STAGE_NAME);
    }

    private static File datDir(int account) {
        return new File(new File(stageDir(), "dat"), String.valueOf(account));
    }

    private static File prefsDir() {
        return new File(stageDir(), "prefs");
    }

    // ---------------------------------------------------------------- 导入侧

    /** 开一次新的导入：清掉上次没应用完的暂存 */
    public static void begin() {
        deleteTree(stageDir());
        stageDir().mkdirs();
    }

    public static boolean acceptDatName(String name) {
        return name != null && DAT_NAME.matcher(name).matches();
    }

    /** 把一个 dat 文件写进暂存区（account = 0 是主账号，1..3 是另外几个） */
    public static boolean stageDat(int account, String name, InputStream in) {
        if (!acceptDatName(name)) {
            return false;
        }
        File dir = datDir(account);
        if (!dir.exists() && !dir.mkdirs()) {
            return false;
        }
        File target = new File(dir, name);
        try {
            OutputStream out = new FileOutputStream(target);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            out.close();
            return target.length() > 0;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** 把一份 userconfig 写进暂存区（原样字节，等到开机再解析落盘） */
    public static boolean stagePrefs(int account, InputStream in) {
        File dir = prefsDir();
        if (!dir.exists() && !dir.mkdirs()) {
            return false;
        }
        File target = new File(dir, prefsFileName(account));
        try {
            OutputStream out = new FileOutputStream(target);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            out.close();
            return target.length() > 0;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static String prefsFileName(int account) {
        return account == 0 ? "userconfing.xml" : ("userconfig" + account + ".xml");
    }

    /** 暂存区里到底攒下了哪些账号，日志里用 */
    public static int[] stagedAccounts() {
        ArrayList<Integer> list = new ArrayList<>();
        for (int a = 0; a < 4; a++) {
            File dir = datDir(a);
            File[] files = dir.listFiles();
            if (files != null && files.length > 0) {
                list.add(a);
                continue;
            }
            if (new File(prefsDir(), prefsFileName(a)).exists()) {
                list.add(a);
            }
        }
        int[] out = new int[list.size()];
        for (int a = 0; a < list.size(); a++) {
            out[a] = list.get(a);
        }
        return out;
    }

    /** 打完收工：立一个标记，下一次进程启动时应用 */
    public static boolean commit() {
        try {
            File flag = new File(stageDir(), FLAG_NAME);
            FileOutputStream out = new FileOutputStream(flag);
            out.write(String.valueOf(System.currentTimeMillis()).getBytes());
            out.flush();
            out.close();
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static boolean hasPending() {
        return new File(stageDir(), FLAG_NAME).exists();
    }

    // ---------------------------------------------------------------- 启动侧

    /**
     * 进程启动最早期调用（native 与 UserConfig 都还没起来）。
     * 把暂存区的数据落到正式位置。
     */
    public static void applyPending(Context context) {
        if (context == null) {
            return;
        }
        try {
            if (!isMainProcess(context)) {
                return;
            }
            if (!hasPending()) {
                return;
            }
            File filesDir = ApplicationLoader.getFilesDirFixed();
            int accounts = 0;
            int files = 0;

            for (int a = 0; a < 4; a++) {
                File accountDir = a == 0 ? filesDir : new File(filesDir, "account" + a);
                boolean touched = false;

                File[] staged = datDir(a).listFiles();
                if (staged != null && staged.length > 0) {
                    if (!accountDir.exists()) {
                        accountDir.mkdirs();
                    }
                    // 先清掉这个账号原来的凭据，避免旧的 dc 配置残留盖住新的
                    File[] existing = accountDir.listFiles();
                    if (existing != null) {
                        for (File f : existing) {
                            String n = f.getName();
                            if (DAT_NAME.matcher(n).matches() || BAK_NAME.matcher(n).matches()) {
                                //noinspection ResultOfMethodCallIgnored
                                f.delete();
                            }
                        }
                    }
                    for (File src : staged) {
                        if (!acceptDatName(src.getName())) {
                            continue;
                        }
                        if (copy(src, new File(accountDir, src.getName()))) {
                            files++;
                            touched = true;
                        }
                    }
                }

                File stagedPrefs = new File(prefsDir(), prefsFileName(a));
                if (stagedPrefs.exists() && stagedPrefs.length() > 0) {
                    if (writePrefs(stagedPrefs, a)) {
                        touched = true;
                        accounts++;
                    }
                }

                if (touched) {
                    // 换了一套凭据，旧库里存的是别的账号（或上一个会话）的东西，清掉让它重建
                    wipeAccountDatabase(accountDir);
                }
            }

            SharedPreferences sp = context.getSharedPreferences(FORCE_LOGOUT_PREF, Context.MODE_PRIVATE);
            sp.edit()
                    .putLong(KEY_IMPORTED_AT, System.currentTimeMillis())
                    .remove(KEY_FORCE_LOGOUT_AT)
                    .commit();

            FileLog.d("GeekgramAccountImport applied: accounts=" + accounts + " files=" + files);
            deleteTree(stageDir());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static boolean isMainProcess(Context context) {
        try {
            String process = currentProcessName();
            if (process == null) {
                return true;
            }
            return process.equals(context.getPackageName());
        } catch (Throwable e) {
            return true;
        }
    }

    private static String currentProcessName() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return android.app.Application.getProcessName();
            }
        } catch (Throwable ignore) {
        }
        try {
            byte[] buf = new byte[256];
            FileInputStream in = new FileInputStream("/proc/self/cmdline");
            int read = in.read(buf);
            in.close();
            if (read <= 0) {
                return null;
            }
            int end = 0;
            while (end < read && buf[end] != 0) {
                end++;
            }
            return new String(buf, 0, end);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 清掉这个账号的数据库，让它在新的凭据下重建（设置类 prefs 不动） */
    private static void wipeAccountDatabase(File accountDir) {
        String[] names = {"cache4.db", "cache4.db-wal", "cache4.db-shm", "cache4.db-journal"};
        for (String n : names) {
            File f = new File(accountDir, n);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /**
     * 解析 Android 的 shared_prefs xml，整份覆盖写进对应账号的 SharedPreferences。
     * 先解析成内存表，确认解析出东西了再 clear+写；解析失败就保持原样，
     * 否则一份坏 xml 会把用户现有的账号配置直接清空。
     */
    private static boolean writePrefs(File xml, int account) {
        String prefName = account == 0 ? "userconfing" : ("userconfig" + account);
        try {
            ArrayList<Object[]> parsed = new ArrayList<>(); // {key, type, value}
            FileInputStream in = new FileInputStream(xml);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, "UTF-8");
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    String key = parser.getAttributeValue(null, "name");
                    if (key != null) {
                        try {
                            if ("set".equals(tag)) {
                                //noinspection unchecked
                                HashSet<String> set = new HashSet<>();
                                int depth = parser.getDepth();
                                while (true) {
                                    int inner = parser.next();
                                    if (inner == XmlPullParser.END_DOCUMENT) {
                                        break;
                                    }
                                    if (inner == XmlPullParser.END_TAG && parser.getDepth() == depth) {
                                        break;
                                    }
                                    if (inner == XmlPullParser.START_TAG && "string".equals(parser.getName())) {
                                        set.add(parser.nextText());
                                    }
                                }
                                parsed.add(new Object[]{key, "set", set});
                            } else if ("string".equals(tag)) {
                                String v = parser.getAttributeValue(null, "value");
                                if (v == null) {
                                    v = parser.nextText();
                                }
                                parsed.add(new Object[]{key, "string", v});
                            } else if ("int".equals(tag) || "long".equals(tag)
                                    || "float".equals(tag) || "boolean".equals(tag)) {
                                // Android 的 int/long/float/boolean 是属性写法 <long name="x" value="0"/>，
                                // 没有文本节点。以前统一用 nextText() 读，一碰这类标签就抛
                                // END_TAG expected，整份配置解析中断，一项都没写进去。
                                String v = parser.getAttributeValue(null, "value");
                                if (v == null) {
                                    v = parser.nextText();
                                }
                                parsed.add(new Object[]{key, tag, v.trim()});
                            }
                        } catch (Throwable perItem) {
                            FileLog.e(perItem);
                        }
                    }
                }
                eventType = parser.next();
            }
            in.close();

            if (parsed.isEmpty()) {
                FileLog.e("GeekgramAccountImport: " + prefName + " parsed 0 items, keep old config");
                return false;
            }

            SharedPreferences prefs = ApplicationLoader.applicationContext
                    .getSharedPreferences(prefName, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.clear();
            int count = 0;
            for (Object[] row : parsed) {
                String key = (String) row[0];
                String type = (String) row[1];
                Object value = row[2];
                try {
                    if ("set".equals(type)) {
                        //noinspection unchecked
                        editor.putStringSet(key, (HashSet<String>) value);
                    } else if ("string".equals(type)) {
                        editor.putString(key, (String) value);
                    } else if ("int".equals(type)) {
                        editor.putInt(key, Integer.parseInt((String) value));
                    } else if ("long".equals(type)) {
                        editor.putLong(key, Long.parseLong((String) value));
                    } else if ("float".equals(type)) {
                        editor.putFloat(key, Float.parseFloat((String) value));
                    } else {
                        String t = ((String) value).trim();
                        editor.putBoolean(key, "true".equalsIgnoreCase(t) || "1".equals(t));
                    }
                    count++;
                } catch (Throwable perItem) {
                    FileLog.e(perItem);
                }
            }
            boolean ok = editor.commit();
            FileLog.d("GeekgramAccountImport prefs " + prefName + " -> " + count + " items, ok=" + ok);
            return ok && count > 0;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    // ---------------------------------------------------------------- 工具

    private static boolean copy(File src, File dst) {
        try {
            InputStream in = new FileInputStream(src);
            OutputStream out = new FileOutputStream(dst);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            out.close();
            in.close();
            return dst.length() > 0;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    // ---------------------------------------------------------------- 登出检测

    /** native 因为 401 把账号踢掉时记一笔，登录页据此给一句人话解释 */
    public static void markForceLogout() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                return;
            }
            ctx.getSharedPreferences(FORCE_LOGOUT_PREF, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_FORCE_LOGOUT_AT, System.currentTimeMillis()).commit();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** 刚导入完就被服务器踢了，返回 true（登录页提示一次就清掉） */
    public static boolean consumeForceLogoutAfterImport() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                return false;
            }
            SharedPreferences sp = ctx.getSharedPreferences(FORCE_LOGOUT_PREF, Context.MODE_PRIVATE);
            long importedAt = sp.getLong(KEY_IMPORTED_AT, 0);
            long logoutAt = sp.getLong(KEY_FORCE_LOGOUT_AT, 0);
            if (importedAt == 0 || logoutAt == 0) {
                return false;
            }
            boolean fresh = logoutAt >= importedAt && logoutAt - importedAt < 10 * 60 * 1000L;
            if (fresh) {
                sp.edit().remove(KEY_FORCE_LOGOUT_AT).remove(KEY_IMPORTED_AT).commit();
            }
            return fresh;
        } catch (Throwable e) {
            return false;
        }
    }
}
