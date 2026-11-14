/*
 * Geekgram - 设置里的专属页
 *
 * 检查更新、改云更新服务器、防撤回开关、撤回记录,全都收在这里。
 * 代理页里那个「检查更新」已经隐藏,避免两处重复。
 */
package org.telegram.ui;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GeekgramRevokeCache;
import org.telegram.messenger.GeekgramUpdateChecker;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

public class GeekgramSettingsActivity extends Activity {

    private LinearLayout content;
    private TextView updateSub;
    private TextView baseSub;
    private TextView revokeSub;
    private TextView recordSub;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final int bg = Theme.getColor(Theme.key_windowBackgroundWhite);
        getWindow().setBackgroundDrawable(new ColorDrawable(bg));
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (Build.VERSION.SDK_INT >= 23) {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (Theme.isCurrentThemeDark()) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(bg);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(14), dp(12), dp(10));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        back.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(6), 0, dp(10), dp(2));
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Geekgram");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, tlp);

        TextView version = new TextView(this);
        version.setText(GeekgramUpdateChecker.getVersionLine());
        version.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        version.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        header.addView(version);

        root.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(56), 0, dp(24));
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        buildRows();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSubtitles();
    }

    private void buildRows() {
        updateSub = new TextView(this);
        addRow("检查更新", updateSub, v -> checkUpdate());

        baseSub = new TextView(this);
        addRow("云更新服务器", baseSub, v -> editBase());

        revokeSub = new TextView(this);
        addRow("防撤回(本地缓存)", revokeSub, v -> {
            GeekgramRevokeCache.setEnabled(!GeekgramRevokeCache.isEnabled());
            refreshSubtitles();
            Toast.makeText(this, GeekgramRevokeCache.isEnabled() ? "防撤回已开启" : "防撤回已关闭", Toast.LENGTH_SHORT).show();
        });

        recordSub = new TextView(this);
        addRow("撤回记录", recordSub, v -> startActivity(new android.content.Intent(this, GeekgramRevokeActivity.class)));

        TextView note = new TextView(this);
        note.setText("更新只走这里的云更新服务器,官方 Telegram 更新通道已关闭。\n对方撤回消息时,原文会先存在本机,撤回了也能在「撤回记录」里翻看。");
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        note.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        note.setLineSpacing(dp(4), 1f);
        note.setPadding(dp(18), dp(18), dp(18), dp(18));
        content.addView(note);

        refreshSubtitles();
    }

    private void refreshSubtitles() {
        String info = GeekgramUpdateChecker.getLastInfo() != null && GeekgramUpdateChecker.getLastInfo().hasUpdate
                ? "有新版本可更新" : "点击检查是否有新版本";
        updateSub.setText(info);
        baseSub.setText(GeekgramUpdateChecker.getBaseUrl());
        boolean on = GeekgramRevokeCache.isEnabled();
        int count = GeekgramRevokeCache.count();
        revokeSub.setText((on ? "已开启" : "已关闭") + " · 已缓存 " + count + " 条");
        recordSub.setText(count > 0 ? "共 " + count + " 条,点开查看" : "还没有记录");
    }

    private void checkUpdate() {
        Toast.makeText(this, "正在检查…", Toast.LENGTH_SHORT).show();
        GeekgramUpdateChecker.checkAsync(this, true, (ok, error, info) -> {
            if (!ok) {
                Toast.makeText(this, "检查失败:" + (error == null ? "网络不可用" : error), Toast.LENGTH_LONG).show();
                return;
            }
            if (info == null || !info.hasUpdate) {
                Toast.makeText(this, "已是最新版本 " + GeekgramUpdateChecker.getVersionLine(), Toast.LENGTH_SHORT).show();
                return;
            }
            refreshSubtitles();
            GeekgramUpdateChecker.showAnnouncement(this, info);
        });
    }

    private void editBase() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setText(GeekgramUpdateChecker.getBaseUrl());
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHint("https://你的后台域名");
        input.setPadding(dp(20), dp(12), dp(20), dp(12));

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("云更新服务器");
        builder.setView(input);
        builder.setPositiveButton("保存", (d, w) -> {
            GeekgramUpdateChecker.setBaseUrl(input.getText().toString().trim());
            refreshSubtitles();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("取消", null);
        builder.setNeutralButton("恢复默认", (d, w) -> {
            GeekgramUpdateChecker.setBaseUrl(GeekgramUpdateChecker.DEFAULT_BASE);
            refreshSubtitles();
        });
        builder.show();
    }

    private void addRow(String title, TextView subView, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));
        row.setOnClickListener(onClick);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        row.addView(t);

        subView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        subView.setSingleLine(true);
        subView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(3);
        row.addView(subView, slp);

        content.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1) > 0 ? dp(1) : 1);
        dlp.leftMargin = dp(18);
        content.addView(divider, dlp);
    }

    private int dp(float v) {
        return AndroidUtilities.dp(v);
    }
}
