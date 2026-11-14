/*
 * Geekgram - YOYO 面板内嵌
 *
 * 在 App 内直接打开 Vpn 面板的登录 / 注册页，用户登录成功后自动读取面板下发的
 * sub_token，拼成 Clash 订阅链接写入内置内核，一键接管代理。
 *
 * 不另建账号体系：账号、注册、邮箱验证码、面板的订阅逻辑全部是面板自己的。
 */
package org.telegram.ui;

import android.content.Intent;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Keep;

import org.telegram.messenger.ClashCoreManager;
import org.telegram.messenger.ClashProxyHelper;
import org.telegram.messenger.GeekgramNet;
import org.telegram.messenger.GeekgramPanelProxy;
import org.telegram.messenger.R;

public class YoyoPanelActivity extends android.app.Activity {

    /** 面板地址；换面板只改这一行 */
    public static final String PANEL_BASE = "https://vpn.geeksv.com.cn";
    private static final String PANEL_LOGIN = PANEL_BASE + "/login";

    private static final int COLOR_TOP = 0xFF1EA6E9;
    private static final int COLOR_BOTTOM = 0xFF2B84FB;

    private WebView webView;
    private ProgressBar progress;
    private LinearLayout bottomBar;
    private TextView bottomText;
    private TextView actionButton;
    private View errorBox;
    private TextView errorText;
    private boolean usingProxy;
    private boolean proxyStarting;

    private String pendingToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        root.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        webView = new WebView(this);
        configureWebView(webView);
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        errorBox = buildErrorBox();
        errorBox.setVisibility(View.GONE);
        root.addView(errorBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bottomBar = buildBottomBar();
        root.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
        root.addView(progress, plp);

        setContentView(root);

        webView.addJavascriptInterface(new Bridge(), "GeekgramPanel");
        webView.loadUrl(PANEL_LOGIN);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{COLOR_TOP, COLOR_BOTTOM});
        bar.setBackground(bg);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(20);
        close.setTextColor(Color.WHITE);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> finish());
        bar.addView(close, new LinearLayout.LayoutParams(dp(52), dp(52)));

        TextView title = new TextView(this);
        title.setText("YOYO 面板 · 登录获取订阅");
        title.setTextSize(15);
        title.setTextColor(Color.WHITE);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView reload = new TextView(this);
        reload.setText("刷新");
        reload.setTextSize(14);
        reload.setTextColor(Color.WHITE);
        reload.setGravity(Gravity.CENTER);
        reload.setPadding(dp(8), 0, dp(12), 0);
        reload.setOnClickListener(v -> webView.reload());
        bar.addView(reload, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));

        return bar;
    }

    private LinearLayout buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(12), dp(10));
        bar.setBackgroundColor(0xFFF5F7FA);

        bottomText = new TextView(this);
        bottomText.setText("登录后会自动读取订阅");
        bottomText.setTextSize(13);
        bottomText.setTextColor(0xFF333B45);
        bar.addView(bottomText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        actionButton = new TextView(this);
        actionButton.setText("导入并连接");
        actionButton.setTextSize(14);
        actionButton.setTextColor(Color.WHITE);
        actionButton.setGravity(Gravity.CENTER);
        GradientDrawable btn = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{COLOR_TOP, COLOR_BOTTOM});
        btn.setCornerRadius(dp(18));
        actionButton.setBackground(btn);
        actionButton.setVisibility(View.GONE);
        actionButton.setPadding(dp(16), dp(9), dp(16), dp(9));
        actionButton.setOnClickListener(v -> importAndConnect());
        bar.addView(actionButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return bar;
    }

    @SuppressWarnings("deprecation")
    private void configureWebView(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE
                + "; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && !"about:blank".equals(url)) {
                    hideError();
                }
                view.evaluateJavascript(HOOK_JS, null);
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request == null || !request.isForMainFrame()) {
                    return;
                }
                showError(error == null ? "连不上面板" : String.valueOf(error.getDescription()));
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (Build.VERSION.SDK_INT >= 23) {
                    return;
                }
                showError(TextUtils.isEmpty(description) ? "连不上面板" : description);
            }

            @Override
            public void onReceivedHttpError(WebView view, android.webkit.WebResourceRequest request,
                                            android.webkit.WebResourceResponse errorResponse) {
                if (request == null || !request.isForMainFrame() || errorResponse == null) {
                    return;
                }
                showError("面板返回 HTTP " + errorResponse.getStatusCode());
            }
        });
    }

    private boolean handleUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            if (url.startsWith("http://127.0.0.1:")) {
                // 备用通道（本机代理），留在内嵌页
                return false;
            }
            // 面板本站继续留在内嵌页，外链交给系统浏览器
            if (url.startsWith(PANEL_BASE)) {
                return false;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Throwable ignore) {
            }
            return true;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable ignore) {
        }
        return true;
    }

    private void onTokenFound(final String token) {
        if (TextUtils.isEmpty(token) || token.equals(pendingToken)) {
            return;
        }
        pendingToken = token;
        runOnUiThread(() -> {
            bottomText.setText("已读取订阅 · " + maskToken(token));
            actionButton.setVisibility(View.VISIBLE);
            bottomText.setTextColor(0xFF1E88E5);
        });
    }

    private String maskToken(String t) {
        if (t.length() <= 10) {
            return t;
        }
        return t.substring(0, 4) + "…" + t.substring(t.length() - 4);
    }

    private void importAndConnect() {
        if (TextUtils.isEmpty(pendingToken)) {
            return;
        }
        final String url = PANEL_BASE + "/sub/" + pendingToken;
        ClashCoreManager.setSubscription(url);
        ClashProxyHelper.setAutoEnabled(true);
        ClashProxyHelper.applyAsync(true);
        Toast.makeText(this, "订阅已导入，正在启动内核…", Toast.LENGTH_SHORT).show();
        ClashCoreManager.startAsync(null, false, (ok, error) -> runOnUiThread(() -> {
            if (ok) {
                Toast.makeText(this, "内核已启动，代理已接管", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "订阅已保存，内核启动失败："
                        + (TextUtils.isEmpty(error) ? "未知原因" : error), Toast.LENGTH_LONG).show();
            }
        }));
        finish();
    }

    private View buildErrorBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.WHITE);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("面板没能打开");
        title.setTextSize(17);
        title.setTextColor(0xFF333B45);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        errorText = new TextView(this);
        errorText.setTextSize(13);
        errorText.setTextColor(0xFF6B7684);
        errorText.setGravity(Gravity.CENTER);
        errorText.setPadding(0, dp(10), 0, dp(16));
        box.addView(errorText);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER);
        row1.addView(makeButton("重试", this::loadDirect));
        row1.addView(makeButton("走备用通道", this::loadViaProxy));
        box.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);
        row2.addView(makeButton("用浏览器打开", new Runnable() {
            @Override
            public void run() {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PANEL_LOGIN)));
                } catch (Throwable ignore) {
                }
            }
        }));
        row2.addView(makeButton("手动填订阅", this::showManualSubDialog));
        box.addView(row2);

        return box;
    }

    private TextView makeButton(String text, Runnable action) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2B84FB);
        bg.setCornerRadius(dp(18));
        b.setBackground(bg);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), dp(6), dp(6), dp(6));
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void showError(final String reason) {
        runOnUiThread(() -> {
            if (errorText != null) {
                errorText.setText((TextUtils.isEmpty(reason) ? "连不上面板" : reason)
                        + "\n\n这个网络下直连面板可能被拦，点「走备用通道」再试一次；"
                        + "也可以在「手动填订阅」里直接粘贴订阅链接，内核一样能用。");
            }
            if (errorBox != null) {
                errorBox.setVisibility(View.VISIBLE);
            }
            if (webView != null) {
                webView.setVisibility(View.GONE);
            }
            if (bottomText != null) {
                bottomText.setText("面板没打开 · 可走备用通道");
            }
        });
    }

    private void hideError() {
        if (errorBox != null) {
            errorBox.setVisibility(View.GONE);
        }
        if (webView != null) {
            webView.setVisibility(View.VISIBLE);
        }
    }

    private void loadDirect() {
        usingProxy = false;
        GeekgramPanelProxy.stop();
        hideError();
        if (bottomText != null) {
            bottomText.setText("登录后会自动读取订阅");
        }
        if (webView != null) {
            webView.loadUrl(PANEL_LOGIN);
        }
    }

    /** 直连打不开时，走本机代理（DoH 解析真实 IP + 自建 TLS 连接）把面板取回来 */
    private void loadViaProxy() {
        hideError();
        if (webView != null) {
            webView.loadUrl("about:blank");
        }
        if (proxyStarting) {
            return;
        }
        proxyStarting = true;
        if (bottomText != null) {
            bottomText.setText("正在准备备用通道…");
        }
        new Thread(() -> {
            int p = -1;
            String err = null;
            try {
                p = GeekgramPanelProxy.start();
            } catch (Throwable t) {
                err = GeekgramNet.describe(t);
            }
            final int fp = p;
            final String fe = err;
            runOnUiThread(() -> {
                proxyStarting = false;
                if (fp > 0) {
                    usingProxy = true;
                    if (bottomText != null) {
                        bottomText.setText("备用通道已就绪");
                    }
                    if (webView != null) {
                        webView.loadUrl("http://127.0.0.1:" + fp + "/login");
                    }
                } else {
                    showError("备用通道没起来：" + (fe == null ? "未知原因" : fe));
                }
            });
        }, "geekgram-panel-proxy-start").start();
    }

    private void showManualSubDialog() {
        final EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setHint(PANEL_BASE + "/sub/xxxx");
        String saved = ClashCoreManager.getSubscription();
        if (!TextUtils.isEmpty(saved)) {
            edit.setText(saved);
        }
        new AlertDialog.Builder(this)
                .setTitle("填订阅链接")
                .setView(edit)
                .setPositiveButton("保存并连接", (d, w) -> {
                    String url = edit.getText() == null ? "" : edit.getText().toString().trim();
                    if (TextUtils.isEmpty(url) || !(url.startsWith("http://") || url.startsWith("https://"))) {
                        Toast.makeText(this, "链接要以 http 开头", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ClashCoreManager.setSubscription(url);
                    ClashProxyHelper.setAutoEnabled(true);
                    ClashProxyHelper.applyAsync(true);
                    Toast.makeText(this, "订阅已保存，正在启动内核…", Toast.LENGTH_SHORT).show();
                    ClashCoreManager.startAsync(null, true, (ok, error) -> runOnUiThread(() -> {
                        if (ok) {
                            Toast.makeText(this, "内核已启动，代理已接管", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "订阅已保存，内核启动失败："
                                    + (TextUtils.isEmpty(error) ? "未知原因" : error), Toast.LENGTH_LONG).show();
                        }
                    }));
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        try {
            if (webView != null) {
                webView.stopLoading();
                webView.removeJavascriptInterface("GeekgramPanel");
                webView.destroy();
                webView = null;
            }
        } catch (Throwable ignore) {
        }
        try {
            if (usingProxy) {
                GeekgramPanelProxy.stop();
            }
        } catch (Throwable ignore) {
        }
        super.onDestroy();
    }

    /** JS 回调桥 */
    private class Bridge {

        @Keep
        @JavascriptInterface
        public void onSubToken(final String token) {
            onTokenFound(token);
        }

        @Keep
        @JavascriptInterface
        public void onSubUrl(final String url) {
            if (TextUtils.isEmpty(url)) {
                return;
            }
            int idx = url.indexOf("/sub/");
            if (idx >= 0) {
                String t = url.substring(idx + 5);
                int q = t.indexOf('?');
                if (q > 0) {
                    t = t.substring(0, q);
                }
                onTokenFound(t);
            }
        }

        @Keep
        @JavascriptInterface
        public void toast(final String msg) {
            if (TextUtils.isEmpty(msg)) {
                return;
            }
            runOnUiThread(() -> Toast.makeText(YoyoPanelActivity.this, msg, Toast.LENGTH_SHORT).show());
        }
    }

    /**
     * 注入到面板页面：钩住 XHR / fetch / localStorage / 页面文本，
     * 一旦出现 sub_token 就回传给原生。
     */
    private static final String HOOK_JS =
            "(function(){" +
            "if(window.__gkPanelHook)return;window.__gkPanelHook=1;" +
            "var seen={};" +
            "function emit(t){" +
            "  if(!t||typeof t!=='string')return;" +
            "  t=t.replace(/[\\s\"',;]/g,'');" +
            "  if(t.length<8||t.length>160)return;" +
            "  if(seen[t])return;seen[t]=1;" +
            "  try{window.GeekgramPanel.onSubToken(t);}catch(e){}" +
            "}" +
            "function scan(s){" +
            "  if(!s||typeof s!=='string'||s.length>400000)return;" +
            "  var m=s.match(/\"sub_token\"\\s*:\\s*\"([^\"]{8,160})\"/);" +
            "  if(m)emit(m[1]);" +
            "  m=s.match(/\\/sub\\/([A-Za-z0-9_\\-\\.=]{8,160})/);" +
            "  if(m)emit(m[1]);" +
            "  m=s.match(/sub_token=([A-Za-z0-9_\\-\\.=]{8,160})/);" +
            "  if(m)emit(m[1]);" +
            "}" +
            "var o=XMLHttpRequest.prototype.open,se=XMLHttpRequest.prototype.send;" +
            "XMLHttpRequest.prototype.open=function(){try{this.__gkUrl=arguments[1];}catch(e){}return o.apply(this,arguments);};" +
            "XMLHttpRequest.prototype.send=function(){var x=this;" +
            "  x.addEventListener('load',function(){try{scan(x.responseText);}catch(e){}});" +
            "  return se.apply(this,arguments);};" +
            "var f=window.fetch;" +
            "if(f){window.fetch=function(){var p=f.apply(this,arguments);" +
            "  try{p.then(function(r){try{r.clone().text().then(scan).catch(function(){});}catch(e){}}).catch(function(){});}catch(e){}" +
            "  return p;};}" +
            "setInterval(function(){" +
            "  try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);scan(k);scan(localStorage.getItem(k));}}" +
            "  catch(e){}" +
            "  try{scan(document.body?document.body.innerText:'');}catch(e){}" +
            "},2000);" +
            "})();";
}
