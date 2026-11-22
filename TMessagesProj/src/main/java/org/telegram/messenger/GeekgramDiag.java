package org.telegram.messenger;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Geekgram 启动诊断。
 * 把启动阶段和未捕获异常写到手机「下载」目录下的 geekgram-startup-log.txt，
 * 闪退之后把这个文件发回来就能直接定位是哪一步挂的。
 */
public class GeekgramDiag {

    public static final String FILE_NAME = "geekgram-startup-log.txt";

    private static final Object lock = new Object();
    private static final StringBuilder buffer = new StringBuilder();
    private static Context appContext;
    private static Uri downloadUri;
    private static Thread.UncaughtExceptionHandler previousHandler;
    private static boolean installed;

    public static void install(Context context) {
        if (context == null) {
            return;
        }
        appContext = context.getApplicationContext();
        if (installed) {
            return;
        }
        installed = true;
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
                + " version=" + versionString());
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
                FileOutputStream out = new FileOutputStream(new File(dir, FILE_NAME), false);
                out.write(data.getBytes("UTF-8"));
                out.close();
            }
        } catch (Throwable ignore) {
        }
        try {
            if (downloadUri == null) {
                downloadUri = createDownloadEntry();
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

    private static Uri createDownloadEntry() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME);
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
            File f = new File(dir, FILE_NAME);
            if (!f.exists() && !f.createNewFile()) {
                return null;
            }
            return Uri.fromFile(f);
        } catch (Throwable ignore) {
        }
        return null;
    }
}
