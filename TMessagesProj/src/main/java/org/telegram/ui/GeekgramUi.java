/*
 * Geekgram - 自建页面统一成 Telegram 原生外观
 *
 * 账号导入、账号导出、内核面板这几个页面以前各画各的（蓝渐变头、白卡片、圆角大按钮），
 * 跟 Telegram 自己的设置页完全是两种风格。这里把公共部分抽出来：
 * 同一个标题栏高度/字体/返回图标、同一套 Theme 颜色、同样的圆角卡片和列表行、
 * 同样的分割线与说明文字。页面只描述内容，长什么样由这里决定。
 *
 * 注意：所有颜色都走 Theme.getColor(...)，夜间主题下不会出现一块刺眼的白底。
 */
package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;

public final class GeekgramUi {

    private GeekgramUi() {
    }

    // ---------------------------------------------------------------- 颜色

    public static int bg() {
        return Theme.getColor(Theme.key_windowBackgroundGray);
    }

    public static int cardColor() {
        return Theme.getColor(Theme.key_windowBackgroundWhite);
    }

    public static int text() {
        return Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
    }

    public static int sub() {
        return Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2);
    }

    public static int accent() {
        return Theme.getColor(Theme.key_featuredStickers_addButton);
    }

    public static int red() {
        return Theme.getColor(Theme.key_text_RedRegular);
    }

    public static int divider() {
        return Theme.getColor(Theme.key_divider);
    }

    public static int dp(float v) {
        return AndroidUtilities.dp(v);
    }

    /** 状态栏 / 导航栏跟 Telegram 设置页一样：白顶栏、浅灰底 */
    public static void applySystemBars(Activity activity) {
        boolean dark = Theme.isCurrentThemeDark();
        if (Build.VERSION.SDK_INT >= 21) {
            activity.getWindow().setStatusBarColor(cardColor());
            activity.getWindow().setNavigationBarColor(bg());
        }
        AndroidUtilities.setLightStatusBar(activity, !dark);
        if (Build.VERSION.SDK_INT >= 26) {
            View decor = activity.getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (dark) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    // ---------------------------------------------------------------- 骨架

    public static class Screen {
        public LinearLayout root;
        /** 往里塞卡片的容器（左右已留白） */
        public LinearLayout content;
        public TextView title;
    }

    /**
     * 搭一个跟 Telegram 设置页一样的页面：状态栏留白 + 标题栏（返回箭头 + 标题）+ 滚动内容。
     */
    public static Screen screen(final Activity activity, String titleText, final Runnable onBack) {
        Screen screen = new Screen();

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg());
        screen.root = root;

        int statusHeight = AndroidUtilities.statusBarHeight;
        if (statusHeight <= 0) {
            statusHeight = dp(24);
        }
        root.setPadding(0, statusHeight, 0, 0);

        FrameLayout bar = new FrameLayout(activity);
        bar.setBackgroundColor(cardColor());
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ActionBar.getCurrentActionBarHeight());
        root.addView(bar, barParams);

        ImageView back = new ImageView(activity);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setColorFilter(text(), PorterDuff.Mode.MULTIPLY);
        Drawable selector = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector));
        back.setBackground(selector);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onBack != null) {
                    onBack.run();
                } else {
                    activity.onBackPressed();
                }
            }
        });
        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.MATCH_PARENT);
        backParams.gravity = Gravity.LEFT;
        bar.addView(back, backParams);

        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(text());
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        titleParams.leftMargin = dp(56);
        titleParams.rightMargin = dp(16);
        bar.addView(title, titleParams);
        screen.title = title;

        View line = new View(activity);
        line.setBackgroundColor(divider());
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        ScrollView scroll = new ScrollView(activity);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(24));
        scroll.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        screen.content = content;

        return screen;
    }

    // ---------------------------------------------------------------- 卡片

    public static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(cardColor());
        background.setCornerRadius(dp(12));
        card.setBackground(background);
        return card;
    }

    public static void addCard(LinearLayout content, LinearLayout card, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMargin);
        content.addView(card, params);
    }

    /** 卡片上方那种灰色小标题 */
    public static TextView section(LinearLayout content, String label) {
        TextView view = new TextView(content.getContext());
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(sub());
        view.setPadding(dp(18), dp(14), dp(18), dp(6));
        content.addView(view);
        return view;
    }

    /** 卡片之间的空隙 */
    public static View space(Context context, int height) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)));
        return view;
    }

    public static void addDivider(LinearLayout card) {
        View line = new View(card.getContext());
        line.setBackgroundColor(divider());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        params.leftMargin = dp(16);
        params.rightMargin = dp(16);
        card.addView(line, params);
    }

    /** 左标题右内容的一行（内核状态、订阅来源这种） */
    public static TextView addInfo(LinearLayout card, String label, String value, boolean bold) {
        Context context = card.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView left = new TextView(context);
        left.setText(label);
        left.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        left.setTextColor(text());
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView right = new TextView(context);
        right.setText(value);
        right.setTextSize(TypedValue.COMPLEX_UNIT_SP, bold ? 15 : 14);
        right.setTextColor(sub());
        right.setGravity(Gravity.END);
        if (bold) {
            right.setTypeface(AndroidUtilities.bold());
        }
        row.addView(right, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(row);
        return right;
    }

    /** 列表里可以点的一行（Telegram 设置页那种蓝字行） */
    public static TextView addAction(LinearLayout card, String label, int color, boolean withDivider, View.OnClickListener listener) {
        return addAction(card, label, null, color, withDivider, listener);
    }

    public static TextView addAction(LinearLayout card, String label, String value, int color, boolean withDivider, View.OnClickListener listener) {
        Context context = card.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector)));
        if (listener != null) {
            row.setOnClickListener(listener);
            row.setClickable(true);
        }

        TextView left = new TextView(context);
        left.setText(label);
        left.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        left.setTextColor(color);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView right = null;
        if (!TextUtils.isEmpty(value)) {
            right = new TextView(context);
            right.setText(value);
            right.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            right.setTextColor(sub());
            right.setSingleLine(true);
            right.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.leftMargin = dp(10);
            row.addView(right, params);
        }

        card.addView(row);
        if (withDivider) {
            addDivider(card);
        }
        return right == null ? left : right;
    }

    /** Telegram 那种蓝底白字的主按钮 */
    public static TextView primaryButton(Context context, String text, View.OnClickListener listener) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        button.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(accent());
        background.setCornerRadius(dp(10));
        button.setBackground(background);
        button.setOnClickListener(listener);
        return button;
    }

    /** 灰色说明块 */
    public static TextView note(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(sub());
        view.setLineSpacing(dp(3), 1f);
        view.setPadding(dp(16), dp(12), dp(16), dp(14));
        return view;
    }

    /** 等宽、可选中的日志块 */
    public static TextView mono(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        view.setTextColor(text());
        view.setTextIsSelectable(true);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setLineSpacing(dp(2), 1f);
        view.setPadding(dp(16), dp(12), dp(16), dp(12));
        return view;
    }
}
