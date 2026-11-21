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
import org.telegram.messenger.GeekgramAccountImport;
import org.telegram.messenger.FileLog;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
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
                "选账号数据文件导进来，导完会自动重启一次，重启之后直接进已登录状态。\n\n"
                        + "需要的文件（一般在 /data/data/<包名>/files/ 下）：\n"
                        + "· tgnet.dat\n"
                        + "· dc1conf.dat ~ dc5conf.dat\n"
                        + "· userconfig.xml / userconfigN.xml（在 /data/data/<包名>/shared_prefs/ 下，\n"
                        + "  多账号时每个账号一份，N 是账号序号）\n\n"
                        + "也可以直接选一个装着这些文件的 zip 包。\n"
                        + "一个账号要能直接进去，凭据和配置得成对存在：只导了 tgnet.dat 却没有对应的 "
                        + "userconfig，重启后那个账号还是未登录状态。"));
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
        GeekgramAccountImport.begin();
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
                    } else if (accountOfConfigName(name) >= 0) {
                        if (stageUserConfig(uri, accountOfConfigName(name))) {
                            xml++;
                        } else {
                            skipped++;
                        }
                    } else if (lower.endsWith(".dat")) {
                        if (stageDat(uri, name)) {
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
                return;
            }
            int[] accounts = GeekgramAccountImport.stagedAccounts();
            if (accounts.length > 1) {
                log("这次带了 " + accounts.length + " 个账号的数据。");
            }
            if (!GeekgramAccountImport.commit()) {
                log("暂存失败，换个存储位置再试。");
                return;
            }
            log("数据已暂存。现在立刻重启，重启之后才算真正写进去（这一步不做的话会被旧的登录状态盖回去）。");
            runOnUiThread(() -> {
                Toast.makeText(this, "导入好了，正在重启…", Toast.LENGTH_SHORT).show();
                restartApp();
            });
        }).start();
    }

    /** .dat 写进暂存区：accountN 目录的记到账号 N，根级的记到账号 0 */
    private boolean stageDat(Uri uri, String name) {
        try {
            int account = 0;
            String base = name;
            if (name != null && name.startsWith("account") && name.contains("_")) {
                // account2_tgnet.dat 这种命名：记到账号 2
                String num = name.substring("account".length(), name.indexOf('_'));
                try {
                    account = Integer.parseInt(num);
                } catch (Throwable ignore) {
                    account = 0;
                }
                base = name.substring(name.indexOf('_') + 1);
            }
            if (!GeekgramAccountImport.acceptDatName(base)) {
                log("跳过：" + name + "（不是 tgnet.dat / dcNconf.dat）");
                return false;
            }
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                return false;
            }
            boolean ok = GeekgramAccountImport.stageDat(account, base, in);
            in.close();
            log((ok ? "已暂存：" : "写入失败：") + base + (account == 0 ? "" : "（账号 " + (account + 1) + "）"));
            return ok;
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

    /** 解压 zip，内部按路径分清是哪个账号，全部写进暂存区 */
    private int[] importZip(Uri uri) {
        int docs = 0, xml = 0, skipped = 0;
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            ZipInputStream zin = new ZipInputStream(in);
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String path = entry.getName();
                String base = path.substring(path.lastIndexOf('/') + 1);
                String lower = base.toLowerCase();
                int accountOfDir = accountOfDirName(accountDirOf(path));
                if (accountOfConfigName(base) >= 0) {
                    if (GeekgramAccountImport.stagePrefs(accountOfConfigName(base), zin)) {
                        xml++;
                        log("已暂存配置：账号 " + (accountOfConfigName(base) + 1));
                    } else {
                        skipped++;
                    }
                } else if (lower.endsWith(".dat")) {
                    if (!GeekgramAccountImport.acceptDatName(base)) {
                        skipped++;
                    } else if (GeekgramAccountImport.stageDat(accountOfDir < 0 ? 0 : accountOfDir, base, zin)) {
                        docs++;
                        log("已暂存：" + base + (accountOfDir > 0 ? "（账号 " + (accountOfDir + 1) + "）" : ""));
                    } else {
                        skipped++;
                    }
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

    /** "account2" → 2；认不出返回 -1 */
    private int accountOfDirName(String dir) {
        if (TextUtils.isEmpty(dir) || !dir.startsWith("account")) {
            return -1;
        }
        try {
            int v = Integer.parseInt(dir.substring("account".length()));
            return v >= 0 && v < 4 ? v : -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** userconfig 写进暂存区（等到开机再解析落盘） */
    private boolean stageUserConfig(Uri uri, int account) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                return false;
            }
            boolean ok = GeekgramAccountImport.stagePrefs(account, in);
            in.close();
            log((ok ? "已暂存配置：账号 " : "配置写入失败：账号 ") + (account + 1));
            return ok;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * 从配置文件名认出账号序号：userconfig.xml / userconfing.xml = 0，userconfigN.xml = N；不认返回 -1。
     * Telegram 的多账号配置是分开存的（账号 0 → userconfing，账号 N → userconfigN），
     * 不是同一份 xml 里的 user2/user3。以前只认账号 0 那一个名字，其余账号的配置直接跳过。
     */
    private int accountOfConfigName(String name) {
        if (name == null) {
            return -1;
        }
        String n = name.toLowerCase(Locale.US);
        int slash = n.lastIndexOf('/');
        if (slash >= 0) {
            n = n.substring(slash + 1);
        }
        if (n.equals("userconfing.xml") || n.equals("userconfig.xml")) {
            return 0;
        }
        if (!n.startsWith("userconfig") || !n.endsWith(".xml")) {
            return -1;
        }
        String tail = n.substring("userconfig".length(), n.length() - 4);
        if (tail.isEmpty()) {
            return -1;
        }
        for (int a = 0; a < tail.length(); a++) {
            if (!Character.isDigit(tail.charAt(a))) {
                return -1;
            }
        }
        try {
            int acc = Integer.parseInt(tail);
            return acc >= 0 && acc < 4 ? acc : -1;
        } catch (Throwable e) {
            return -1;
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
