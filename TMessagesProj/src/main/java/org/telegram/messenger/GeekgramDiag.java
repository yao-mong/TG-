package org.telegram.messenger;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Geekgram 启动诊断。
 *
 * 重点解决两个问题：
 * 1) 手机上有好几个进程（主进程、组件沙箱进程等）都会写日志，以前它们写同一个文件、
 *    互相覆盖，最后留下的往往不是打开界面那一次。现在主进程写 geekgram-startup-log.txt，
 *    其它进程各自写 geekgram-log-<进程名>.txt，互不干扰。
 * 2) 闪退如果是原生层挂的（Java 抓不到），日志会停在最后一条标记上；再加上心跳线程，
 *    能区分「卡死被系统杀」和「原生崩溃」。
 */
public class GeekgramDiag {

    /** 主进程固定用这个名字，用户只需要发这个文件 */
    public static final String FILE_NAME = "geekgram-startup-log.txt";
    /** 只写一行的状态文件，跟主日志交叉验证用 */
    public static final String STATE_FILE_NAME = "geekgram-state.txt";

    private static final String PREFS = "geekgram_diag";
    private static final String KEY_ATTEMPTS = "start_attempts";
    private static final String KEY_LAST_STAGE = "last_stage";
    /** 上一次进程最后一次心跳的秒数（用来判断进程是在第几秒消失的） */
    private static final String KEY_LAST_HB = "last_hb_elapsed";
    /** 上一次启动是不是死在「代理接管 / 内置内核」这一段，是就不在启动时再自动跑它 */
    private static final String KEY_QUARANTINE_PROXY = "quarantine_proxy";
    /** 界面出来后要活过这么多秒，才算这次启动真的稳了（否则下次继续降级启动） */
    private static final long STABLE_WINDOW_MS = 20000L;

    private static final Object lock = new Object();
    private static final StringBuilder buffer = new StringBuilder();
    private static Context appContext;
    private static final java.util.HashMap<String, Uri> downloadUris = new java.util.HashMap<>();
    private static volatile String currentStage = "";
    private static Thread.UncaughtExceptionHandler previousHandler;
    private static boolean installed;
    private static String processName = "";
    private static boolean mainProcess = true;
    private static volatile boolean uiReady;
    private static long startElapsed;
    private static int safeModeLevel;
    private static boolean watchdogStarted;

    // ------------------------------------------------------------------ 安装

    public static void install(Context context) {
        if (context == null) {
            return;
        }
        appContext = context.getApplicationContext();
        if (installed) {
            return;
        }
        installed = true;
        startElapsed = SystemClock.elapsedRealtime();
        String resolved = resolveProcessName();
        if (resolved == null || resolved.length() == 0) {
            resolved = appContext.getPackageName();
        }
        processName = resolved;
        mainProcess = processName.equals(appContext.getPackageName());
        safeModeLevel = readSafeModeLevel();

        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                synchronized (lock) {
                    buffer.append('\n').append(time())
                            .append("  ==== 崩溃 thread=")
                            .append(thread == null ? "?" : thread.getName())
                            .append(" ====\n");
                    buffer.append(Log.getStackTraceString(throwable)).append('\n');
                }
                flush();
            } catch (Throwable ignore) {
            }
            if (previousHandler != null) {
                try {
                    previousHandler.uncaughtException(thread, throwable);
                } catch (Throwable ignore) {
                }
            }
        });
        mark("diag installed android=" + Build.VERSION.SDK_INT
                + " pkg=" + appContext.getPackageName()
                + " version=" + versionString()
                + " process=" + processName
                + " 上次未走完启动=" + Math.max(0, safeModeLevel)
                + (safeModeLevel > 0 ? " 本次安全模式=L" + safeModeLevel : ""));

        if (mainProcess && appContext != null) {
            try {
                // 每个进程只记一次：这次启动能不能活过稳定窗口，下次启动就知道了
                int attempts = prefs().getInt(KEY_ATTEMPTS, 0) + 1;
                prefs().edit().putInt(KEY_ATTEMPTS, attempts).commit();
            } catch (Throwable ignore) {
            }
        }

        // 上一次进程没走完（降级计数还没被清）→ 把最后停留的那一步、最后心跳秒数打出来。
        // 原生层崩溃 Java 是抓不到的，但这两条足以把断点锁死到某一步。
        if (mainProcess && appContext != null) {
            String prevStage = "";
            long prevHb = -1;
            try {
                SharedPreferences p = prefs();
                prevStage = p.getString(KEY_LAST_STAGE, "");
                prevHb = p.getLong(KEY_LAST_HB, -1);
            } catch (Throwable ignore) {
            }
            if (prevStage != null && prevStage.length() > 0) {
                mark("上次启动中断于：" + prevStage
                        + (prevHb >= 0 ? ("（进程最后心跳 " + prevHb + "s，之后就没再写日志）") : ""));
                if (isRiskyStage(prevStage)) {
                    try {
                        prefs().edit().putBoolean(KEY_QUARANTINE_PROXY, true).commit();
                        mark("上次崩在代理接管/内置内核这一段 → 本次启动不再自动跑它（可在代理页开关里重新开启）");
                    } catch (Throwable ignore) {
                    }
                }
            }
            try {
                prefs().edit().remove(KEY_LAST_HB).commit();
            } catch (Throwable ignore) {
            }
        }
        startWatchdog();
    }

    /** 主进程才需要界面的就绪判断，其它进程不看 */
    public static boolean isMainProcess() {
        return mainProcess;
    }

    /**
     * 上一次启动有没有走到界面：走到过返回 0，连续崩过几次就返回几（最多 3）。
     * 只在主进程里用，别的进程启动（组件、推送）不算一次启动尝试。
     */
    public static int readSafeModeLevel() {
        if (!mainProcess || appContext == null) {
            return 0;
        }
        try {
            int attempts = prefs().getInt(KEY_ATTEMPTS, 0);
            if (attempts < 0) {
                attempts = 0;
            }
            return Math.min(attempts, 3);
        } catch (Throwable ignore) {
        }
        return 0;
    }

    public static int getSafeModeLevel() {
        return safeModeLevel;
    }

    /** 界面开始创建时调用（降级计数已在进程启动时记过，这里只补一个打点） */
    public static void beginUiLaunch() {
        mark("界面开始创建");
    }

    /**
     * 首帧画出来了只说明「界面能建起来」，不等于这次启动是好的。
     *
     * 以前这里直接把降级计数清零，结果就出现了这次的坑：
     * 界面 1.1s 就画好了 → 计数清零 → 2 秒后进程才挂 → 下次启动还是 L0 → 再挂一次，
     * 安全模式永远进不去。所以现在改成：首帧只记一笔，要活过 STABLE_WINDOW_MS
     * 才把计数清掉；中途挂了，下次启动按 L1 降级跑。
     */
    public static void markUiReady() {
        if (uiReady) {
            return;
        }
        uiReady = true;
        mark("首帧已绘制，界面正常显示（耗时 " + ((SystemClock.elapsedRealtime() - startElapsed) / 1000f) + "s）");
        markStage("首帧已绘制，进入稳定观察期（" + (STABLE_WINDOW_MS / 1000) + "s）");
        if (!mainProcess || appContext == null) {
            return;
        }
        try {
            final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.postDelayed(() -> {
                try {
                    if (!uiReady) {
                        return;
                    }
                    mark("启动稳定（首帧后 " + (STABLE_WINDOW_MS / 1000) + "s 进程仍在），清除降级计数");
                    markStage("启动稳定（首帧后 " + (STABLE_WINDOW_MS / 1000) + "s）");
                    prefs().edit().putInt(KEY_ATTEMPTS, 0).commit();
                } catch (Throwable ignore) {
                }
            }, STABLE_WINDOW_MS);
        } catch (Throwable ignore) {
        }
    }

    /**
     * 记一步「正在干什么」，同时把这一步写进磁盘。
     *
     * 好处：原生层崩溃 Java 抓不到堆栈，但下一次启动能读到「上次中断于：XXX」，
     * 直接锁死是哪一个动作把进程带走的，不用再一轮轮猜。
     */
    public static void markStage(String stage) {
        currentStage = stage;
        mark(stage);
        if (!mainProcess || appContext == null) {
            return;
        }
        try {
            prefs().edit().putString(KEY_LAST_STAGE, stage).commit();
        } catch (Throwable ignore) {
        }
        flushState(stage);
    }

    private static boolean isRiskyStage(String stage) {
        if (stage == null) {
            return false;
        }
        return stage.contains("接管") || stage.contains("代理") || stage.contains("内核");
    }

    /** 上次启动崩在代理接管 / 内置内核那一段吗 */
    public static boolean isProxyQuarantined() {
        if (!mainProcess || appContext == null) {
            return false;
        }
        try {
            return prefs().getBoolean(KEY_QUARANTINE_PROXY, false);
        } catch (Throwable ignore) {
        }
        return false;
    }

    /** 用户自己重新打开自动接管时解除隔离（说明他想再试一次） */
    public static void clearProxyQuarantine() {
        if (!mainProcess || appContext == null) {
            return;
        }
        try {
            prefs().edit().putBoolean(KEY_QUARANTINE_PROXY, false).commit();
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------ 记录

    public static void mark(String stage) {
        try {
            synchronized (lock) {
                buffer.append(time()).append("  ").append(stage).append('\n');
            }
            flush();
        } catch (Throwable ignore) {
        }
    }

    public static void markThrowable(String stage, Throwable t) {
        try {
            synchronized (lock) {
                buffer.append(time()).append("  ").append(stage).append('\n');
                buffer.append(Log.getStackTraceString(t)).append('\n');
            }
            flush();
        } catch (Throwable ignore) {
        }
    }

    private static String time() {
        try {
            return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        } catch (Throwable ignore) {
            return "--";
        }
    }

    private static SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 心跳：前 20 秒每 2 秒记一次，之后每 10 秒记到 2 分钟。
     * 如果界面一直没出来，8 秒时把主线程的调用栈抓一份下来
     * （能区分「卡在主线程被系统杀」和「直接崩在原生层」）。
     */
    private static void startWatchdog() {
        if (watchdogStarted) {
            return;
        }
        watchdogStarted = true;
        Thread t = new Thread(() -> {
            boolean mainStackDumped = false;
            for (int i = 0; ; i++) {
                try {
                    Thread.sleep(i < 10 ? 2000 : 10000);
                } catch (InterruptedException ignore) {
                    return;
                }
                try {
                    long elapsed = (SystemClock.elapsedRealtime() - startElapsed) / 1000;
                    if (elapsed > 120) {
                        mark("心跳结束（启动后 " + elapsed + "s，界面已就绪=" + uiReady + "）");
                        return;
                    }
                    Thread mainThread = null;
                    try {
                        mainThread = android.os.Looper.getMainLooper().getThread();
                    } catch (Throwable ignore) {
                    }
                    String state = mainThread == null ? "?" : String.valueOf(mainThread.getState());
                    if (elapsed >= 20 && i % 3 != 0) {
                        continue; // 20 秒之后少记一点，免得文件太长
                    }
                    mark("心跳 " + elapsed + "s 主线程=" + state + " 界面就绪=" + uiReady);
                    flushState(currentStage);
                    if (mainProcess && appContext != null) {
                        try {
                            prefs().edit().putLong(KEY_LAST_HB, elapsed).apply();
                        } catch (Throwable ignore) {
                        }
                    }
                    if (!uiReady && !mainStackDumped && elapsed >= 8 && mainThread != null) {
                        mainStackDumped = true;
                        StringBuilder sb = new StringBuilder("主线程调用栈");
                        try {
                            StackTraceElement[] st = mainThread.getStackTrace();
                            for (StackTraceElement e : st) {
                                sb.append("\n    at ").append(e);
                                if (sb.length() > 6000) {
                                    sb.append("\n    ...");
                                    break;
                                }
                            }
                        } catch (Throwable ignore) {
                        }
                        mark(sb.toString());
                    }
                } catch (Throwable ignore) {
                }
            }
        }, "geekgram-diag-watchdog");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------ 落盘

    private static String logFileName() {
        if (mainProcess) {
            return FILE_NAME;
        }
        String p = processName;
        int idx = p.lastIndexOf(':');
        if (idx >= 0) {
            p = p.substring(idx + 1);
        }
        p = p.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (p.length() == 0) {
            p = "proc";
        }
        return "geekgram-log-" + p + ".txt";
    }

    private static void flush() {
        if (appContext == null) {
            return;
        }
        final String data;
        synchronized (lock) {
            data = buffer.toString();
        }
        try {
            File dir = appContext.getExternalFilesDir(null);
            if (dir == null) {
                dir = appContext.getFilesDir();
            }
            if (dir != null) {
                FileOutputStream out = new FileOutputStream(new File(dir, logFileName()), false);
                out.write(data.getBytes("UTF-8"));
                out.close();
            }
        } catch (Throwable ignore) {
        }
        if (!writeDownload(logFileName(), data)) {
            synchronized (lock) {
                buffer.append(time()).append("  （下载目录这次没写进去，下次重开文件再写；"
                        + "私有目录那份不受影响）\n");
            }
        }
    }

    /**
     * 状态文件：每次心跳 / 换阶段都盖一遍，内容只有一行。
     *
     * 用途是跟主日志交叉验证：如果主日志突然停住、而这个小文件还在继续更新，
     * 说明进程没死、只是主日志没写下去（那就不是闪退）；两个一起停，才是进程真的没了。
     */
    private static void flushState(String stage) {
        if (!mainProcess || appContext == null) {
            return;
        }
        try {
            long elapsed = (SystemClock.elapsedRealtime() - startElapsed) / 1000;
            String data = time() + "  进程存活 " + elapsed + "s  当前步骤=" + stage
                    + "  界面就绪=" + uiReady + "\n";
            writeDownload(STATE_FILE_NAME, data);
            try {
                File dir = appContext.getExternalFilesDir(null);
                if (dir != null) {
                    FileOutputStream out = new FileOutputStream(new File(dir, STATE_FILE_NAME), false);
                    out.write(data.getBytes("UTF-8"));
                    out.close();
                }
            } catch (Throwable ignore) {
            }
        } catch (Throwable ignore) {
        }
    }

    /** 往下载目录写小文件；同一个文件名固定复用一条记录，写失败就丢掉重建 */
    private static boolean writeDownload(String fileName, String data) {
        try {
            Uri uri;
            synchronized (downloadUris) {
                uri = downloadUris.get(fileName);
            }
            if (uri == null) {
                uri = createDownloadEntry(fileName);
                if (uri == null) {
                    return false;
                }
                synchronized (downloadUris) {
                    downloadUris.put(fileName, uri);
                }
            }
            OutputStream out = appContext.getContentResolver().openOutputStream(uri, "wt");
            if (out == null) {
                synchronized (downloadUris) {
                    downloadUris.remove(fileName);
                }
                return false;
            }
            out.write(data.getBytes("UTF-8"));
            out.close();
            return true;
        } catch (Throwable e) {
            synchronized (downloadUris) {
                downloadUris.remove(fileName);
            }
            return false;
        }
    }

    private static String versionString() {
        try {
            android.content.pm.PackageInfo info = appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0);
            return info.versionName + "/" + info.versionCode;
        } catch (Throwable ignore) {
            return "?";
        }
    }

    private static String resolveProcessName() {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                return android.app.Application.getProcessName();
            }
        } catch (Throwable ignore) {
        }
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread");
            Method m = cls.getMethod("currentProcessName");
            Object v = m.invoke(null);
            if (v instanceof String) {
                return (String) v;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private static Uri createDownloadEntry(String fileName) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = appContext.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                return uri;
            }
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) {
                return null;
            }
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File f = new File(dir, fileName);
            if (!f.exists() && !f.createNewFile()) {
                return null;
            }
            return Uri.fromFile(f);
        } catch (Throwable ignore) {
        }
        return null;
    }
}
