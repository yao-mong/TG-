package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Geekgram 测试版：把本机当前账号的数据导出成一个 zip。
 * 导出的 zip 可以直接用「用账号文件登录」导回去（同机备份 / 换机登录）。
 */
public class AccountDataExportActivity extends Activity {

    private static final int REQ_CREATE = 4902;

    private final ArrayList<Item> items = new ArrayList<>();
    private TextView listView;
    private TextView logView;
    private TextView exportBtn;

    private static class Item {
        final File file;
        final String entryName;
        final String label;

        Item(File file, String entryName, String label) {
            this.file = file;
            this.entryName = entryName;
            this.label = label;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GeekgramUi.applySystemBars(this);

        GeekgramUi.Screen screen = GeekgramUi.screen(this, "导出账号数据", this::finish);
        setContentView(screen.root);

        GeekgramUi.section(screen.content, "说明");
        LinearLayout info = GeekgramUi.card(this);
        info.addView(GeekgramUi.note(this,
                "把本机当前账号的数据打成一个 zip，存到你手机上任何位置。\n\n"
                        + "导出的包里有：\n"
                        + "· tgnet.dat / dcNconf.dat（登录凭据与节点配置）\n"
                        + "· userconfig.xml / userconfigN.xml（每个账号的配置）\n\n"
                        + "换台手机装 Geekgram → 登录页「用账号文件登录」→ 选这个 zip，不用输验证码就能进。\n"
                        + "导出的包等于账号钥匙，别发给别人。"));
        GeekgramUi.addCard(screen.content, info, 0);

        GeekgramUi.section(screen.content, "本机账号数据");
        LinearLayout files = GeekgramUi.card(this);
        listView = GeekgramUi.note(this, "正在扫描账号数据…");
        files.addView(listView);
        GeekgramUi.addCard(screen.content, files, 0);

        exportBtn = GeekgramUi.primaryButton(this, "导出为 zip", v -> startCreate());
        exportBtn.setEnabled(false);
            exportBtn.setAlpha(0.45f);
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        exportParams.topMargin = dp(16);
        screen.content.addView(exportBtn, exportParams);

        GeekgramUi.section(screen.content, "其他");
        LinearLayout others = GeekgramUi.card(this);
        GeekgramUi.addAction(others, "重新扫描", GeekgramUi.accent(), false, v -> scanAsync());
        GeekgramUi.addCard(screen.content, others, 0);

        GeekgramUi.section(screen.content, "导出日志");
        LinearLayout logs = GeekgramUi.card(this);
        logView = GeekgramUi.mono(this, "");
        logs.addView(logView);
        GeekgramUi.addCard(screen.content, logs, 0);

        setBtnEnabled();
        scanAsync();
    }

    private int dp(float v) {
        return AndroidUtilities.dp(v);
    }

    private void scanAsync() {
        exportBtn.setEnabled(false);
        listView.setText("正在扫描账号数据…");
        new Thread(() -> {
            final ArrayList<Item> result = new ArrayList<>();
            try {
                collectAll(result);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            runOnUiThread(() -> {
                items.clear();
                items.addAll(result);
                StringBuilder sb = new StringBuilder();
                if (items.isEmpty()) {
                    sb.append("没扫到账号数据。\n当前可能还没登录，或者数据放在别的目录。");
                } else {
                    sb.append("扫到 ").append(items.size()).append(" 个文件：\n");
                    long total = 0;
                    SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
                    for (Item it : items) {
                        total += it.file.length();
                        sb.append("· ").append(it.label)
                                .append("  ").append(readable(it.file.length()))
                                .append("  ").append(fmt.format(new Date(it.file.lastModified())))
                                .append('\n');
                    }
                    sb.append("\n合计 ").append(readable(total));
                }
                listView.setText(sb.toString());
                setBtnEnabled();
            });
        }).start();
    }

    private void collectAll(ArrayList<Item> out) {
        File filesDir = ApplicationLoader.getFilesDirFixed();
        collectDir(filesDir, null, out);

        File[] subs = filesDir.listFiles();
        if (subs != null) {
            Arrays.sort(subs, Comparator.comparing(File::getName));
            for (File sub : subs) {
                if (!sub.isDirectory() || !sub.getName().startsWith("account")) {
                    continue;
                }
                if (!isAccountDirName(sub.getName())) {
                    continue;
                }
                collectDir(sub, sub.getName(), out);
            }
        }

        // 账号配置：账号 1 在 userconfing.xml，账号 2/3/4 在 userconfig1~3.xml
        // （UserConfig.getPreferences()：账号 0 → "userconfing"，账号 N → "userconfigN"）
        // 以前只收账号 1 那一份，其余账号的 tgnet 导出去了、配置却没导出，
        // 导回去只有第一个账号能恢复，其余全是未登录。
        File prefsDir = new File(filesDir.getParentFile(), "shared_prefs");
        File[] prefFiles = prefsDir.listFiles();
        if (prefFiles != null) {
            Arrays.sort(prefFiles, Comparator.comparing(File::getName));
            boolean hasAccount0 = false;
            for (File pf : prefFiles) {
                if (!pf.isFile() || pf.length() <= 0) {
                    continue;
                }
                int acc = accountOfConfigName(pf.getName());
                if (acc < 0) {
                    continue;
                }
                if (acc == 0) {
                    // userconfing.xml 和 userconfig.xml 可能是同一份，只收一个
                    if (hasAccount0) {
                        continue;
                    }
                    hasAccount0 = true;
                }
                String entryName = acc == 0 ? "userconfig.xml" : ("userconfig" + acc + ".xml");
                out.add(new Item(pf, entryName, entryName + "（账号 " + (acc + 1) + "）"));
            }
        }
    }

    /** 从配置文件名认出账号序号：userconfing.xml / userconfig.xml = 0，userconfigN.xml = N；不认返回 -1 */
    private int accountOfConfigName(String name) {
        if (name == null) {
            return -1;
        }
        String n = name.toLowerCase(Locale.US);
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

    private boolean isAccountDirName(String name) {
        String tail = name.substring("account".length());
        if (tail.isEmpty()) {
            return false;
        }
        for (int a = 0; a < tail.length(); a++) {
            if (!Character.isDigit(tail.charAt(a))) {
                return false;
            }
        }
        return true;
    }

    private void collectDir(File dir, String prefix, ArrayList<Item> out) {
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        Arrays.sort(list, Comparator.comparing(File::getName));
        for (File f : list) {
            if (!f.isFile() || f.length() <= 0) {
                continue;
            }
            String name = f.getName();
            if (!isAccountDataFile(name)) {
                continue;
            }
            String entry = prefix == null ? name : prefix + "/" + name;
            String label = prefix == null ? name : prefix + "/" + name;
            out.add(new Item(f, entry, label));
        }
    }

    private boolean isAccountDataFile(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (!lower.endsWith(".dat")) {
            return false;
        }
        if (lower.startsWith("tgnet")) {
            return true;
        }
        return lower.matches("dc\\d+conf\\.dat");
    }

    private String readable(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }

    private void startCreate() {
        if (items.isEmpty()) {
            Toast.makeText(this, "没有可导出的文件", Toast.LENGTH_SHORT).show();
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, "geekgram-account-" + stamp + ".zip");
        try {
            startActivityForResult(intent, REQ_CREATE);
        } catch (Throwable e) {
            FileLog.e(e);
            Toast.makeText(this, "打不开保存框", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CREATE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri target = data.getData();
        logView.setText("");
        exportBtn.setEnabled(false);
        appendLog("开始写入…");
        new Thread(() -> {
            int ok = 0;
            long total = 0;
            try {
                OutputStream os = getContentResolver().openOutputStream(target);
                if (os == null) {
                    appendLog("写入失败：拿不到输出流");
                    setBtnEnabled();
                    return;
                }
                ZipOutputStream zout = new ZipOutputStream(os);
                zout.setLevel(6);
                byte[] buffer = new byte[16384];
                for (Item it : items) {
                    if (!it.file.exists() || it.file.length() <= 0) {
                        continue;
                    }
                    try {
                        ZipEntry entry = new ZipEntry(it.entryName);
                        entry.setTime(it.file.lastModified());
                        zout.putNextEntry(entry);
                        FileInputStream in = new FileInputStream(it.file);
                        int read;
                        long wrote = 0;
                        while ((read = in.read(buffer)) > 0) {
                            zout.write(buffer, 0, read);
                            wrote += read;
                        }
                        in.close();
                        zout.closeEntry();
                        ok++;
                        total += wrote;
                        appendLog("已打包：" + it.entryName + "  " + readable(wrote));
                    } catch (Throwable e) {
                        FileLog.e(e);
                        appendLog("跳过：" + it.entryName + " " + e.getMessage());
                    }
                }
                zout.finish();
                zout.close();
            } catch (Throwable e) {
                FileLog.e(e);
                appendLog("出错：" + e.getMessage());
            }
            final int fok = ok;
            final long ftotal = total;
            runOnUiThread(() -> {
                if (fok == 0) {
                    appendLog("没导出到任何文件，换一次保存位置再试。");
                } else {
                    appendLog("—— 完成：打包 " + fok + " 个文件，共 " + readable(ftotal));
                    appendLog("这个 zip 收好。换机登录时用「用账号文件登录」选它就行。");
                    Toast.makeText(this, "已导出 " + fok + " 个文件", Toast.LENGTH_LONG).show();
                }
                setBtnEnabled();
            });
        }).start();
    }

    private void setBtnEnabled() {
        runOnUiThread(() -> {
            boolean can = !items.isEmpty();
            exportBtn.setEnabled(can);
            exportBtn.setAlpha(can ? 1f : 0.45f);
        });
    }

    private void appendLog(String line) {
        runOnUiThread(() -> logView.append(line + "\n"));
    }
}
