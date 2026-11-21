package org.telegram.ui;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.TypedValue;
import android.util.Xml;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Geekgram: 用账号数据文件登录。
 * 支持导入 Telegram 的账号数据文件（tgnet.dat / dcNconf.dat / userconfig.xml），
 * 也支持包含这些文件的 zip 包。导入完成后重启即可直接进入已登录状态。
 */
public class AccountDataImportActivity extends Activity {

    private static final int REQ_PICK = 4901;

    private final ArrayList<Uri> picked = new ArrayList<>();
    private TextView pickedView;
    private TextView logView;
    private TextView pickValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GeekgramUi.applySystemBars(this);

        GeekgramUi.Screen screen = GeekgramUi.screen(this, "用账号文件登录", this::finish);
        setContentView(screen.root);

        GeekgramUi.section(screen.content, "说明");
        LinearLayout info = GeekgramUi.card(this);
        info.addView(GeekgramUi.note(this,
                "选 Telegram 的账号数据文件导进来，重启后直接进已登录状态。\n\n"
                        + "需要的文件（一般在 /data/data/<包名>/files/ 下）：\n"
                        + "· tgnet.dat\n"
                        + "· dc1conf.dat ~ dc5conf.dat\n"
                        + "· userconfig.xml（在 /data/data/<包名>/shared_prefs/ 下）\n\n"
                        + "也可以直接选一个装着这些文件的 zip 包。导入会覆盖当前这份数据的登录状态，"
                        + "当前没登录才建议用。"));
        GeekgramUi.addCard(screen.content, info, 0);

        GeekgramUi.section(screen.content, "账号数据");
        LinearLayout files = GeekgramUi.card(this);
        pickValue = GeekgramUi.addAction(files, "选择账号数据文件", "未选", GeekgramUi.accent(), true,
                v -> pickFiles());
        pickedView = GeekgramUi.note(this, "还没选文件");
        files.addView(pickedView);
        GeekgramUi.addCard(screen.content, files, 0);

        TextView doImport = GeekgramUi.primaryButton(this, "开始导入", v -> runImport());
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        importParams.topMargin = dp(16);
        screen.content.addView(doImport, importParams);

        GeekgramUi.section(screen.content, "导入日志");
        LinearLayout logs = GeekgramUi.card(this);
        logView = GeekgramUi.mono(this, "");
        logs.addView(logView);
        GeekgramUi.addCard(screen.content, logs, 0);

        GeekgramUi.section(screen.content, "其他");
        LinearLayout others = GeekgramUi.card(this);
        GeekgramUi.addAction(others, "重启 Geekgram", GeekgramUi.accent(), true, v -> restartApp());
        GeekgramUi.addAction(others, "导出当前账号数据（zip）", GeekgramUi.accent(), false, v -> {
            try {
                startActivity(new Intent(this, AccountDataExportActivity.class));
            } catch (Throwable e) {
                Toast.makeText(this, "打不开导出页", Toast.LENGTH_SHORT).show();
            }
        });
        GeekgramUi.addCard(screen.content, others, 0);
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQ_PICK);
        } catch (Throwable e) {
            Toast.makeText(this, "打不开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(float v) {
        return AndroidUtilities.dp(v);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) {
            return;
        }
        picked.clear();
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            for (int a = 0; a < count; a++) {
                picked.add(data.getClipData().getItemAt(a).getUri());
            }
        } else if (data.getData() != null) {
            picked.add(data.getData());
        }
        StringBuilder sb = new StringBuilder("已选 " + picked.size() + " 个文件：\n");
        for (Uri uri : picked) {
            sb.append("· ").append(displayName(uri)).append('\n');
        }
        pickedView.setText(sb.toString());
        if (pickValue != null) {
            pickValue.setText("已选 " + picked.size() + " 个");
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = cursor.getString(idx);
                }
            }
        } catch (Throwable ignore) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignore) {
                }
            }
        }
        if (TextUtils.isEmpty(name)) {
            name = uri.getLastPathSegment();
        }
        return name == null ? "未知文件" : name;
    }

    private void log(String line) {
        runOnUiThread(() -> logView.append(line + "\n"));
    }

    private void runImport() {
        if (picked.isEmpty()) {
            Toast.makeText(this, "先选文件", Toast.LENGTH_SHORT).show();
            return;
        }
        logView.setText("");
        new Thread(() -> {
            int docs = 0;
            int xml = 0;
            int skipped = 0;
            for (Uri uri : picked) {
                String name = displayName(uri);
                String lower = name == null ? "" : name.toLowerCase();
                try {
                    if (lower.endsWith(".zip")) {
                        int[] r = importZip(uri);
                        docs += r[0];
                        xml += r[1];
                        skipped += r[2];
                    } else if (lower.endsWith("userconfig.xml") || lower.endsWith("userconfing.xml")) {
                        if (importUserConfig(uri)) {
                            xml++;
                        } else {
                            skipped++;
                        }
                    } else if (lower.endsWith(".dat")) {
                        if (copyDat(uri, name)) {
                            docs++;
                        } else {
                            skipped++;
                        }
                    } else {
                        skipped++;
                        log("跳过：" + name);
                    }
                } catch (Throwable e) {
                    skipped++;
                    FileLog.e(e);
                    log("出错：" + name + " " + e.getMessage());
                }
            }
            log("—— 完成：数据文件 " + docs + " 个，账号配置 " + xml + " 份，跳过 " + skipped + " 个");
            if (docs == 0 && xml == 0) {
                log("没导入到有效文件，确认一下选的是 tgnet.dat / dc1conf.dat / userconfig.xml");
            } else {
                log("点下面的「重启 Geekgram」，重启后应该直接进已登录状态。");
            }
        }).start();
    }

    /** 把 .dat 复制到 files 目录，accountN 子目录的按原结构放 */
    private boolean copyDat(Uri uri, String name) {
        try {
            File filesDir = ApplicationLoader.getFilesDirFixed();
            File target;
            if (name != null && name.startsWith("account") && name.contains("_")) {
                // account2_tgnet.dat 这种命名：放回 accountN 目录
                String num = name.substring("account".length(), name.indexOf('_'));
                File sub = new File(filesDir, "account" + num);
                sub.mkdirs();
                target = new File(sub, name.substring(name.indexOf('_') + 1));
            } else {
                target = new File(filesDir, name);
            }
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                return false;
            }
            OutputStream out = new FileOutputStream(target);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.close();
            in.close();
            log("已写入：" + target.getName() + " (" + target.length() + " 字节)");
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            log("写入失败：" + name + " " + e.getMessage());
            return false;
        }
    }

    /** 从 zip 内路径里认出 accountN 目录名；根级 "account2/tgnet.dat" 和 "x/account2/tgnet.dat" 都认 */
    private String accountDirOf(String path) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        String p = path.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        String[] parts = p.split("/");
        if (parts.length < 2) {
            return null;
        }
        for (int a = 0; a < parts.length - 1; a++) {
            String seg = parts[a];
            if (seg.startsWith("account") && seg.length() > 7) {
                boolean allDigits = true;
                for (int b = 7; b < seg.length(); b++) {
                    if (!Character.isDigit(seg.charAt(b))) {
                        allDigits = false;
                        break;
                    }
                }
                if (allDigits) {
                    return seg;
                }
            }
        }
        return null;
    }

    /** 解压 zip，内部按文件名规则分发 */
    private int[] importZip(Uri uri) {
        int docs = 0, xml = 0, skipped = 0;
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            ZipInputStream zin = new ZipInputStream(in);
            File filesDir = ApplicationLoader.getFilesDirFixed();
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String path = entry.getName();
                String base = path.substring(path.lastIndexOf('/') + 1);
                String lower = base.toLowerCase();
                if (lower.endsWith("userconfig.xml") || lower.endsWith("userconfing.xml")) {
                    if (importUserConfig(zin)) {
                        xml++;
                    } else {
                        skipped++;
                    }
                } else if (lower.endsWith(".dat")) {
                    File target;
                    String dirName = accountDirOf(path);
                    if (dirName != null) {
                        File sub = new File(filesDir, dirName);
                        sub.mkdirs();
                        target = new File(sub, base);
                    } else {
                        target = new File(filesDir, base);
                    }
                    OutputStream out = new FileOutputStream(target);
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = zin.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                    out.close();
                    docs++;
                    log("已写入：" + target.getName());
                } else {
                    skipped++;
                }
                zin.closeEntry();
            }
            zin.close();
        } catch (Throwable e) {
            FileLog.e(e);
            log("解压失败：" + e.getMessage());
        }
        return new int[]{docs, xml, skipped};
    }

    /** 解析 userconfig xml，把每个键写进 SharedPreferences("userconfing") */
    private boolean importUserConfig(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            boolean ok = importUserConfig(in);
            in.close();
            return ok;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    private boolean importUserConfig(InputStream in) {
        int count = 0;
        int bad = 0;
        try {
            SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("userconfing", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
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
                                java.util.HashSet<String> set = new java.util.HashSet<>();
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
                                editor.putStringSet(key, set);
                                count++;
                            } else if ("string".equals(tag)) {
                                String v = parser.getAttributeValue(null, "value");
                                if (v == null) {
                                    v = parser.nextText();
                                }
                                editor.putString(key, v);
                                count++;
                            } else if ("int".equals(tag) || "long".equals(tag)
                                    || "float".equals(tag) || "boolean".equals(tag)) {
                                // Android 的 int/long/float/boolean 是属性写法 <long name="x" value="0"/>，
                                // 没有文本节点。以前统一用 nextText() 读，一碰这类标签就抛
                                // END_TAG expected，整份配置解析中断，一项都没写进去。
                                String v = parser.getAttributeValue(null, "value");
                                if (v == null) {
                                    v = parser.nextText();
                                }
                                String t = v.trim();
                                if ("int".equals(tag)) {
                                    editor.putInt(key, Integer.parseInt(t));
                                } else if ("long".equals(tag)) {
                                    editor.putLong(key, Long.parseLong(t));
                                } else if ("float".equals(tag)) {
                                    editor.putFloat(key, Float.parseFloat(t));
                                } else {
                                    editor.putBoolean(key, "true".equalsIgnoreCase(t) || "1".equals(t));
                                }
                                count++;
                            }
                        } catch (Throwable perItem) {
                            // 单项不认识就跳过，别让它把整份配置带崩
                            bad++;
                            FileLog.e(perItem);
                        }
                    }
                }
                eventType = parser.next();
            }
            if (!editor.commit()) {
                log("账号配置写入失败（旧数据冲突），先清除应用数据再导入");
                return false;
            }
            log("账号配置已写入：" + count + " 项" + (bad > 0 ? "（跳过 " + bad + " 项）" : ""));
            return count > 0;
        } catch (Throwable e) {
            FileLog.e(e);
            log("账号配置解析失败：" + e.getMessage());
            return false;
        }
    }

    private void restartApp() {
        // 必须先杀掉进程：UserConfig 在进程启动时就把账号配置读进内存了，
        // 在同一个进程里 startActivity 读到的还是旧值，等于没重启。
        try {
            Context app = getApplicationContext();
            Intent intent = new Intent(app, LaunchActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            int flags = PendingIntent.FLAG_CANCEL_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(app, 7711, intent, flags);
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.set(AlarmManager.RTC, System.currentTimeMillis() + 800, pi);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        finishAffinity();
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
