/*
 * Geekgram - 内核 / 订阅 面板（原生界面）
 *
 * 以前这个面板是个网页，网一断或者面板站挂了就是一片白，什么都看不到。
 * 现在改成原生：状态、订阅来源、启动停止、导入节点、看日志全在本地点，
 * 不需要联网渲染，永远不会白屏。
 */
package org.telegram.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.ClashCoreManager;

import java.util.ArrayList;

public class CorePanelActivity extends Activity {

    private static final int COLOR_TOP = 0xFF1EA6E9;
    private static final int COLOR_BOTTOM = 0xFF2B84FB;
    private static final int COLOR_BG = 0xFFF2F4F7;
    private static final int COLOR_CARD = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFF10131A;
    private static final int COLOR_SUB = 0xFF707C8C;
    private static final int COLOR_OK = 0xFF2BB673;
    private static final int COLOR_WARN = 0xFFE8A33D;
    private static final int COLOR_ERR = 0xFFE5533D;

    private LinearLayout content;
    private TextView statusValue;
    private TextView sourceValue;
    private TextView noteValue;
    private TextView btnStart;
    private boolean started;

    private static final ArrayList<Activity> livePanels = new ArrayList<>();

    public static void open(Context context) {
        context.startActivity(new Intent(context, CorePanelActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        setContentView(root);

        // 顶部渐变头
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(22), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{COLOR_TOP, COLOR_BOTTOM});
        header.setBackground(bg);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("内核 / 订阅面板");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        title.getPaint().setFakeBoldText(true);
        header.addView(title);

        TextView sub = new TextView(this);
        sub.setText("节点在本机运行 · 离线也能导入 · 不会白屏");
        sub.setTextColor(0xCCFFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setPadding(0, dp(4), 0, 0);
        header.addView(sub);

        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(28));
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 状态卡
        LinearLayout statusCard = card();
        statusValue = addRow(statusCard, "内核状态", "—", COLOR_TEXT, true);
        sourceValue = addRow(statusCard, "订阅来源", "—", COLOR_SUB, false);
        noteValue = addRow(statusCard, "提示", "", COLOR_WARN, false);
        noteValue.setVisibility(View.GONE);
        content.addView(statusCard);

        // 操作卡
        LinearLayout actions = card();
        btnStart = bigButton("启动内核", true, v -> toggleCore());
        actions.addView(btnStart);
        actions.addView(spaceV(8));
        actions.addView(bigButton("YOYO 面板 · 登录 / 注册", false, v -> {
            try {
                startActivity(new Intent(this, YoyoPanelActivity.class));
            } catch (Throwable e) {
                Toast.makeText(this, "打不开面板：" + (e.getMessage() == null ? "未知原因" : e.getMessage()),
                        Toast.LENGTH_SHORT).show();
            }
        }));
        actions.addView(spaceV(8));
        actions.addView(bigButton("导入节点 / 配置", false, v -> showImportDialog()));
        actions.addView(spaceV(8));
        actions.addView(bigButton("从剪贴板导入", false, v -> importFromClipboard()));
        actions.addView(spaceV(8));
        actions.addView(bigButton("查看运行日志", false, v -> showLog()));
        actions.addView(spaceV(8));
        actions.addView(bigButton("清除订阅与节点", false, v -> confirmClear()));
        content.addView(actions);

        // 提示卡
        LinearLayout tips = card();
        TextView tipTitle = new TextView(this);
        tipTitle.setText("面板打不开 / 订阅拉不到怎么办");
        tipTitle.setTextColor(COLOR_TEXT);
        tipTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tipTitle.getPaint().setFakeBoldText(true);
        tips.addView(tipTitle);
        TextView tipBody = new TextView(this);
        tipBody.setText("点「导入节点 / 配置」，把你机场给的节点链接（ss / vmess / vless / trojan / hysteria2），"
                + "或者整份 Clash 配置直接贴进来，App 会在本地转成内核能用的配置，全程不需要联网。\n\n"
                + "如果你在网页版面板里能复制到订阅链接，也可以直接用「导入」里的第一栏填链接。");
        tipBody.setTextColor(COLOR_SUB);
        tipBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tipBody.setLineSpacing(dp(3), 1f);
        tipBody.setPadding(0, dp(8), 0, 0);
        tips.addView(tipBody);
        content.addView(tips);

        livePanels.add(this);
        refresh();
    }

    @Override
    protected void onDestroy() {
        livePanels.remove(this);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ------------------------------------------------------------- 行为

    private void toggleCore() {
        if (ClashCoreManager.isBusy()) {
            Toast.makeText(this, "正在启动，稍等…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ClashCoreManager.isRunning()) {
            ClashCoreManager.stop();
            refresh();
            Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ClashCoreManager.isConfigured()) {
            showImportDialog();
            return;
        }
        btnStart.setText("启动中…");
        btnStart.setEnabled(false);
        ClashCoreManager.startAsync(null, false, (ok, error) -> {
            btnStart.setEnabled(true);
            refresh();
            if (!ok) {
                Toast.makeText(CorePanelActivity.this,
                        TextUtils.isEmpty(error) ? "启动失败" : error, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(CorePanelActivity.this, "内核已启动，代理已接管", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void refresh() {
        boolean running = ClashCoreManager.isRunning();
        boolean busy = ClashCoreManager.isBusy();
        statusValue.setText(running ? "运行中 · 127.0.0.1:" + ClashCoreManager.MIXED_PORT
                : (busy ? "启动中…" : "未运行"));
        statusValue.setTextColor(running ? COLOR_OK : (busy ? COLOR_WARN : COLOR_ERR));
        sourceValue.setText(ClashCoreManager.sourceText());
        if (TextUtils.isEmpty(ClashCoreManager.getLastNote())) {
            noteValue.setVisibility(View.GONE);
        } else {
            noteValue.setVisibility(View.VISIBLE);
            noteValue.setText(ClashCoreManager.getLastNote());
        }
        btnStart.setText(running ? "停止内核" : "启动内核");
        // 启动中不要覆盖按钮文案
        if (busy) {
            btnStart.setText("启动中…");
        }
        btnStart.setEnabled(!busy);
        started = true;
    }

    private void showImportDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);

        TextView hint = new TextView(this);
        hint.setText("三种都行：\n· 订阅链接（https://…）\n· 节点链接（ss/vmess/vless/trojan/hysteria2，可以一次贴一堆）\n· 整份 Clash 配置");
        hint.setTextColor(COLOR_SUB);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setLineSpacing(dp(2), 1f);
        box.addView(hint);

        EditText input = new EditText(this);
        input.setHint("粘贴到这里");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(4);
        input.setMaxLines(9);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        box.addView(input, lp);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("导入节点");
        b.setView(box);
        b.setPositiveButton("导入", (d, w) -> {
            String text = input.getText() == null ? "" : input.getText().toString();
            String msg = ClashCoreManager.importText(text);
            Toast.makeText(CorePanelActivity.this, msg, Toast.LENGTH_LONG).show();
            if (ClashCoreManager.isConfigured()) {
                ClashCoreManager.stop();
                refresh();
                ClashCoreManager.startAsync(null, false, (ok, error) -> {
                    refresh();
                    if (!ok) {
                        Toast.makeText(CorePanelActivity.this,
                                TextUtils.isEmpty(error) ? "导入成功但启动失败" : error, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
        b.setNegativeButton("取消", null);
        b.setNeutralButton("从剪贴板", (d, w) -> {
            String text = readClipboard();
            if (TextUtils.isEmpty(text)) {
                Toast.makeText(this, "剪贴板是空的", Toast.LENGTH_SHORT).show();
                return;
            }
            String msg = ClashCoreManager.importText(text);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            refresh();
        });
        b.show();
    }

    private void importFromClipboard() {
        String text = readClipboard();
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, "剪贴板是空的", Toast.LENGTH_SHORT).show();
            return;
        }
        String msg = ClashCoreManager.importText(text);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        if (ClashCoreManager.isConfigured()) {
            ClashCoreManager.stop();
            refresh();
            ClashCoreManager.startAsync(null, false, (ok, error) -> {
                refresh();
                if (!ok && !TextUtils.isEmpty(error)) {
                    Toast.makeText(CorePanelActivity.this, error, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private String readClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                return "";
            }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return "";
            }
            CharSequence cs = clip.getItemAt(0).coerceToText(this);
            return cs == null ? "" : cs.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private void showLog() {
        String log = ClashCoreManager.readLog();
        if (TextUtils.isEmpty(log)) {
            log = "还没有日志。内核没启动过，或者启动后没写过东西。";
        } else if (log.length() > 20000) {
            log = "…" + log.substring(log.length() - 20000);
        }
        final String finalLog = log;
        TextView tv = new TextView(this);
        tv.setText(finalLog);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(COLOR_TEXT);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("运行日志")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制", (d, w) -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("log", finalLog));
                            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Throwable ignore) {
                    }
                })
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清除订阅与节点")
                .setMessage("会删掉当前保存的订阅链接和本地节点，并停止内核。")
                .setPositiveButton("清除", (d, w) -> {
                    ClashCoreManager.clearImport();
                    refresh();
                    Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------- 控件

    private LinearLayout card() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CARD);
        bg.setCornerRadius(dp(14));
        ll.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        ll.setLayoutParams(lp);
        return ll;
    }

    private TextView addRow(LinearLayout parent, String label, String value, int valueColor, boolean bold) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(COLOR_SUB);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(valueColor);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, bold ? 14 : 13);
        v.setGravity(Gravity.END);
        if (bold) {
            v.getPaint().setFakeBoldText(true);
        }
        row.addView(v);
        parent.addView(row);
        return v;
    }

    private TextView bigButton(String text, boolean primary, View.OnClickListener listener) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setPadding(dp(14), dp(13), dp(14), dp(13));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        if (primary) {
            bg.setColors(new int[]{COLOR_TOP, COLOR_BOTTOM});
            bg.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
            b.setTextColor(Color.WHITE);
            b.getPaint().setFakeBoldText(true);
        } else {
            bg.setColor(0xFFF5F7FA);
            b.setTextColor(0xFF2C5F8A);
        }
        b.setBackground(bg);
        b.setOnClickListener(listener);
        return b;
    }

    private View spaceV(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h)));
        return v;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
