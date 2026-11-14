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
    private Button exportBtn;

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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff1c1c1e);
        root.setPadding(dp(18), dp(24), dp(18), dp(18));

        TextView title = new TextView(this);
        title.setText("导出账号数据");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTextColor(0xffffffff);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("把本机当前账号的数据打成一个 zip，存到你手机上任何位置。\n\n"
                + "导出的包里有：\n"
                + "· tgnet.dat / dcNconf.dat（登录凭据与节点配置）\n"
                + "· userconfig.xml（账号配置）\n\n"
                + "换台手机装 Geekgram 测试版 → 登录页「用账号文件登录」→ 选这个 zip，"
                + "不用输验证码就能进已登录状态。\n"
                + "导出的包等于账号钥匙，别发给别人。");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        desc.setTextColor(0xff9a9a9e);
        desc.setLineSpacing(dp(4), 1f);
        desc.setPadding(0, dp(12), 0, dp(12));
        root.addView(desc);

        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(box);

        listView = new TextView(this);
        listView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        listView.setTextColor(0xffd0d0d4);
        listView.setText("正在扫描账号数据…");
        listView.setPadding(0, dp(6), 0, dp(10));
        box.addView(listView);

        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        logView.setTextColor(0xff8ab4f8);
        logView.setPadding(0, dp(6), 0, dp(10));
        box.addView(logView);

        exportBtn = new Button(this);
        exportBtn.setText("导出为 zip");
        exportBtn.setEnabled(false);
        exportBtn.setOnClickListener(v -> startCreate());
        root.addView(exportBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button rescan = new Button(this);
        rescan.setText("重新扫描");
        rescan.setOnClickListener(v -> scanAsync());
        root.addView(rescan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
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
                exportBtn.setEnabled(!items.isEmpty());
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

        File prefsDir = new File(filesDir.getParentFile(), "shared_prefs");
        File uc = new File(prefsDir, "userconfing.xml");
        if (!uc.exists()) {
            File uc2 = new File(prefsDir, "userconfig.xml");
            if (uc2.exists()) {
                uc = uc2;
            }
        }
        if (uc.exists() && uc.length() > 0) {
            out.add(new Item(uc, "userconfig.xml", "userconfig.xml"));
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
                exportBtn.setEnabled(!items.isEmpty());
            });
        }).start();
    }

    private void setBtnEnabled() {
        runOnUiThread(() -> exportBtn.setEnabled(!items.isEmpty()));
    }

    private void appendLog(String line) {
        runOnUiThread(() -> logView.append(line + "\n"));
    }
}
