/*
 * Geekgram - 主界面底部的自有云更新横幅
 *
 * 官方 Telegram 的更新提示已经关掉(BuildVars.CHECK_UPDATES=false),这个横幅占住
 * 同一个位置,显示后台发布的版本号、包大小和一个「立即更新」按钮。
 */
package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.ui.Components.LayoutHelper;

public class GeekgramUpdateBanner extends FrameLayout implements GeekgramUpdateChecker.StateListener {

    private final TextView titleView;
    private final TextView actionView;

    public GeekgramUpdateBanner(Context context) {
        super(context);
        setBackground(createBackground());

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = AndroidUtilities.dp(14);
        row.setPadding(pad, 0, pad, 0);
        addView(row, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f);
        row.addView(titleView, lp1);

        actionView = new TextView(context);
        actionView.setText("立即更新");
        actionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        actionView.setTextColor(0xFFFFFFFF);
        actionView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        lp2.leftMargin = AndroidUtilities.dp(10);
        row.addView(actionView, lp2);

        setOnClickListener(v -> {
            GeekgramUpdateChecker.Info info = GeekgramUpdateChecker.getLastInfo();
            if (info == null) {
                return;
            }
            Activity activity = AndroidUtilities.findActivity(context);
            GeekgramUpdateChecker.showAnnouncement(activity, info);
        });

        GeekgramUpdateChecker.addListener(this);
        onUpdateState(GeekgramUpdateChecker.getLastInfo());
    }

    private GradientDrawable createBackground() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF2AA1E0, 0xFF3B8BFF});
        g.setCornerRadius(0);
        return g;
    }

    @Override
    public void onUpdateState(GeekgramUpdateChecker.Info info) {
        AndroidUtilities.runOnUIThread(() -> {
            if (info == null || !info.hasUpdate || info.versionCode <= GeekgramUpdateChecker.getVersionCode()) {
                setVisibility(GONE);
                return;
            }
            String text = GeekgramUpdateChecker.bannerText(info);
            if (!TextUtils.isEmpty(info.notice)) {
                text = text + "  ·  " + info.notice.replace("\n", " ");
            }
            titleView.setText(text);
            actionView.setText(info.force ? "必须更新" : "立即更新");
            setVisibility(VISIBLE);
            requestLayout();
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        GeekgramUpdateChecker.removeListener(this);
    }
}
