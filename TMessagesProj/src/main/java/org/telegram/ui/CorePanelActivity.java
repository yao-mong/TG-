/*
 * Geekgram - 内核 / 订阅 面板（原生界面）
 *
 * 以前这个面板是个网页，网一断或者面板站挂了就是一片白，什么都看不到。
 * 现在改成原生：状态、订阅来源、启动停止、导入节点、看日志全在本地点，
 * 不需要联网渲染，永远不会白屏。
 *
 * 外观统一成 Telegram 自己的设置页：一样的标题栏、一样的圆角卡片和列表行。
 */
package org.telegram.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

public class CorePanelActivity extends Activity {

    private static final int COLOR_OK = 0xFF4FAE4E;
    private static final int COLOR_WARN = 0xFFE8A33D;
    private static final int COLOR_ERR = 0xFFE5533D;

    private TextView statusValue;
    private TextView sourceValue;
    private TextView noteView;
    private TextView btnStart;

    public static void open(Context context) {
        context.startActivity(new Intent(context, CorePanelActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        GeekgramUi.applySystemBars(this);

        GeekgramUi.Screen screen = GeekgramUi.screen(this, "内核 / 订阅", this::finish);
        setContentView(screen.root);

        // 状态
        GeekgramUi.section(screen.content, "状态");
        LinearLayout statusCard = GeekgramUi.card(this);
        statusValue = GeekgramUi.addInfo(statusCard, "内核状态", "—", true);
        GeekgramUi.addDivider(statusCard);
        sourceValue = GeekgramUi.addInfo(statusCard, "订阅来源", "—", false);
        noteView = GeekgramUi.note(this, "");
        noteView.setVisibility(View.GONE);
        statusCard.addView(noteView);
        GeekgramUi.addCard(screen.content, statusCard, 0);

        // 操作
        GeekgramUi.section(screen.content, "操作");
        LinearLayout actions = GeekgramUi.card(this);
        btnStart = GeekgramUi.addAction(actions, "启动内核", GeekgramUi.accent(), true, v -> toggleCore());
        GeekgramUi.addAction(actions, "YOYO 面板 · 登录 / 注册", GeekgramUi.accent(), true, v -> {
            try {
                startActivity(new Intent(this, YoyoPanelActivity.class));
            } catch (Throwable e) {
                Toast.makeText(this, "打不开面板：" + (e.getMessage() == null ? "未知原因" : e.getMessage()),
                        Toast.LENGTH_SHORT).show();
            }
        });
        GeekgramUi.addAction(actions, "导入节点 / 配置", GeekgramUi.accent(), true, v -> showImportDialog());
        GeekgramUi.addAction(actions, "从剪贴板导入", GeekgramUi.accent(), true, v -> importFromClipboard());
        GeekgramUi.addAction(actions, "查看运行日志", GeekgramUi.accent(), true, v -> showLog());
        GeekgramUi.addAction(actions, "清除订阅与节点", GeekgramUi.red(), false, v -> confirmClear());
        GeekgramUi.addCard(screen.content, actions, 12);

        // 说明
        GeekgramUi.section(screen.content, "说明");
        LinearLayout tips = GeekgramUi.card(this);
        tips.addView(GeekgramUi.note(this,
                "点「导入节点 / 配置」，把机场给的节点链接（ss / vmess / vless / trojan / hysteria2），"
                        + "或者整份 Clash 配置直接贴进来，App 会在本地转成内核能用的配置，全程不需要联网。\n\n"
                        + "网页版面板里能复制到订阅链接的话，也可以在「导入」里直接填链接。"));
        GeekgramUi.addCard(screen.content, tips, 0);

        refresh();
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
        statusValue.setText(running ? "运行中 · 127.0.0.1:" + ClashCoreManager.activePort()
                : (busy ? "启动中…" : "未运行"));
        statusValue.setTextColor(running ? COLOR_OK : (busy ? COLOR_WARN : COLOR_ERR));
        sourceValue.setText(ClashCoreManager.sourceText());
        String note = ClashCoreManager.getLastNote();
        if (TextUtils.isEmpty(note)) {
            noteView.setVisibility(View.GONE);
        } else {
            noteView.setVisibility(View.VISIBLE);
            noteView.setText(note);
        }
        if (btnStart != null) {
            btnStart.setText(running ? "停止内核" : "启动内核");
            if (busy) {
                btnStart.setText("启动中…");
            }
            btnStart.setTextColor(running ? GeekgramUi.red() : GeekgramUi.accent());
            btnStart.setEnabled(!busy);
        }
    }

    private void showImportDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(GeekgramUi.dp(22), GeekgramUi.dp(6), GeekgramUi.dp(22), 0);

        TextView hint = new TextView(this);
        hint.setText("三种都行：\n· 订阅链接（https://…）\n· 节点链接（ss/vmess/vless/trojan/hysteria2，可以一次贴一堆）\n· 整份 Clash 配置");
        hint.setTextColor(GeekgramUi.sub());
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setLineSpacing(GeekgramUi.dp(2), 1f);
        box.addView(hint);

        EditText input = new EditText(this);
        input.setHint("粘贴到这里");
        input.setTextColor(GeekgramUi.text());
        input.setHintTextColor(GeekgramUi.sub());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(4);
        input.setMaxLines(9);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = GeekgramUi.dp(8);
        box.addView(input, lp);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("导入节点");
        builder.setView(box);
        builder.setPositiveButton("导入", (dialog, which) -> {
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
        builder.setNegativeButton("取消", null);
        builder.setNeutralButton("从剪贴板", (dialog, which) -> {
            String text = readClipboard();
            if (TextUtils.isEmpty(text)) {
                Toast.makeText(this, "剪贴板是空的", Toast.LENGTH_SHORT).show();
                return;
            }
            String msg = ClashCoreManager.importText(text);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            refresh();
        });
        builder.show();
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
        ScrollView scroll = new ScrollView(this);
        scroll.addView(GeekgramUi.mono(this, finalLog), new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("运行日志")
                .setView(scroll, (int) (getResources().getDisplayMetrics().heightPixels * 0.55f))
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制", (dialog, which) -> {
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
                .setPositiveButton("清除", (dialog, which) -> {
                    ClashCoreManager.clearImport();
                    refresh();
                    Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
