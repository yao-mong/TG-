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

    private static final String PREFS = "geekgram_diag";
    private static final String KEY_ATTEMPTS = "start_attempts";
    private static final String KEY_LAST_STAGE = "last_stage";

    private static final Object lock = new Object();
    private static final StringBuilder buffer = new StringBuilder();
    private static Context appContext;
    private static Uri downloadUri;
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

    /** 界面开始创建时调用：记一次尝试，崩了下次就会降级启动 */
    public static void beginUiLaunch() {
        if (!mainProcess || appContext == null) {
            return;
        }
        try {
            int attempts = prefs().getInt(KEY_ATTEMPTS, 0) + 1;
            prefs().edit().putInt(KEY_ATTEMPTS, attempts).commit();
        } catch (Throwable ignore) {
        }
    }

    /** 首帧画出来了 = 这次启动是好的，把降级计数清掉 */
    public static void markUiReady() {
        if (uiReady) {
            return;
        }
        uiReady = true;
        mark("首帧已绘制，界面正常显示（耗时 " + ((SystemClock.elapsedRealtime() - startElapsed) / 1000f) + "s）");
        if (!mainProcess || appContext == null) {
            return;
        }
        try {
            prefs().edit().putInt(KEY_ATTEMPTS, 0).remove(KEY_LAST_STAGE).commit();
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
        try {
            if (downloadUri == null) {
                downloadUri = createDownloadEntry(logFileName());
            }
            if (downloadUri != null) {
                OutputStream out = appContext.getContentResolver().openOutputStream(downloadUri, "wt");
                if (out != null) {
                    out.write(data.getBytes("UTF-8"));
                    out.close();
                }
            }
        } catch (Throwable ignore) {
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
