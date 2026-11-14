/*
 * Geekgram - 撤回记录
 *
 * 对方撤回的消息,原文已经存在本机,这里列出来,点一条就能复制。
 */
package org.telegram.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GeekgramRevokeCache;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class GeekgramRevokeActivity extends Activity {

    private final ArrayList<GeekgramRevokeCache.Entry> items = new ArrayList<>();
    private final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
    private Adapter adapter;
    private TextView emptyView;

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
        back.setPadding(dp(6), 0, dp(10), dp(2));
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = new TextView(this);
        title.setText("撤回记录");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView clear = new TextView(this);
        clear.setText("清空");
        clear.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        clear.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        clear.setPadding(dp(10), dp(4), dp(6), dp(4));
        clear.setOnClickListener(v -> confirmClear());
        header.addView(clear);

        root.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setPadding(0, dp(56), 0, dp(16));
        list.setClipToPadding(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= items.size()) {
                return;
            }
            GeekgramRevokeCache.Entry e = items.get(position);
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("message", e.text == null ? "" : e.text));
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = new TextView(this);
        emptyView.setText("还没有记录\n对方撤回的消息会出现在这里");
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        setContentView(root);
        reload();
    }

    private void reload() {
        items.clear();
        items.addAll(GeekgramRevokeCache.load());
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void confirmClear() {
        if (items.isEmpty()) {
            Toast.makeText(this, "没有记录", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("清空撤回记录");
        b.setMessage("清空后本地缓存就没了,清空之后不能恢复。");
        b.setPositiveButton("清空", (d, w) -> {
            GeekgramRevokeCache.clear();
            reload();
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView == null) {
                row = new LinearLayout(GeekgramRevokeActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(18), dp(12), dp(18), dp(12));
                row.setTag(new Holder(row));
            } else {
                row = (LinearLayout) convertView;
            }
            Holder h = (Holder) row.getTag();
            GeekgramRevokeCache.Entry e = items.get(position);

            String name = TextUtils.isEmpty(e.name) ? "聊天 " + e.dialogId : e.name;
            h.top.setText(name + " · " + fmt.format(new Date(e.date * 1000L)));
            h.text.setText(e.text == null ? "" : e.text);

            View last = position == items.size() - 1 ? null : h.divider;
            h.divider.setVisibility(last == null ? View.GONE : View.VISIBLE);
            return row;
        }
    }

    private class Holder {
        TextView top;
        TextView text;
        View divider;

        Holder(LinearLayout row) {
            top = new TextView(GeekgramRevokeActivity.this);
            top.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            top.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            top.setSingleLine(true);
            top.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(top);

            text = new TextView(GeekgramRevokeActivity.this);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            text.setPadding(0, dp(3), 0, 0);
            row.addView(text);

            divider = new View(GeekgramRevokeActivity.this);
            divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
            lp.topMargin = dp(12);
            row.addView(divider, lp);
        }
    }

    private int dp(float v) {
        return AndroidUtilities.dp(v);
    }
}
