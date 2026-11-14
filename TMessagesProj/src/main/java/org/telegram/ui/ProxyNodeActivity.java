/*
 * Geekgram - 节点选择
 *
 * 一个原生页面，跟 Clash 面板里那个「代理」页是一个意思：
 *   · 自动选择：内核一直在所有节点里挑最快的
 *   · 各地区分组：香港 / 台湾 / 狮城 / 日本 / 美国 / 韩国，组里自动挑最快
 *   · 手动切换：把订阅里全部节点列出来，自己点一个
 *   · DIRECT：不走代理
 *
 * 点一下立刻生效（内核支持热切换），不需要重启 App、不用重连。
 * 页面不联网、不靠网页渲染，断网也能打开。
 */
package org.telegram.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ClashApi;
import org.telegram.messenger.ClashCoreManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public class ProxyNodeActivity extends Activity {

    private static final int COLOR_TOP = 0xFF1EA6E9;
    private static final int COLOR_BOTTOM = 0xFF2B84FB;
    private static final int COLOR_BG = 0xFFF2F4F7;
    private static final int COLOR_CARD = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFF10131A;
    private static final int COLOR_SUB = 0xFF707C8C;
    private static final int COLOR_OK = 0xFF2BB673;
    private static final int COLOR_BLUE = 0xFF2B84FB;

    private static final String[] REGION_NAMES = {
            "香港节点", "台湾节点", "狮城节点", "日本节点", "美国节点", "韩国节点"
    };
    private static final String[] REGION_FLAGS = {"🇭🇰", "🇹🇼", "🇸🇬", "🇯🇵", "🇺🇸", "🇰🇷"};

    private LinearLayout listBox;
    private TextView statusValue;
    private TextView currentValue;
    private TextView testBtn;

    private final Map<String, TextView> groupSubs = new LinkedHashMap<>();
    private final Map<String, TextView> groupChecks = new LinkedHashMap<>();
    private final Map<String, View> groupCards = new LinkedHashMap<>();

    private volatile boolean loading;
    private volatile boolean testing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        setContentView(root);

        // 顶部
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(20), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{COLOR_TOP, COLOR_BOTTOM});
        header.setBackground(bg);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(0xFFFFFFFF);
        back.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
        back.setPadding(0, 0, dp(12), dp(2));
        back.setOnClickListener(v -> finish());
        titleRow.addView(back);

        TextView title = new TextView(this);
        title.setText("节点选择");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        testBtn = new TextView(this);
        testBtn.setText("测速");
        testBtn.setTextColor(0xFFFFFFFF);
        testBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        testBtn.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0x33FFFFFF);
        btnBg.setCornerRadius(dp(14));
        testBtn.setBackground(btnBg);
        testBtn.setOnClickListener(v -> testAll());
        titleRow.addView(testBtn);
        header.addView(titleRow);

        currentValue = new TextView(this);
        currentValue.setText("读取中…");
        currentValue.setTextColor(0xFFFFFFFF);
        currentValue.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        currentValue.setPadding(0, dp(10), 0, 0);
        header.addView(currentValue);

        statusValue = new TextView(this);
        statusValue.setText("");
        statusValue.setTextColor(0xCCFFFFFF);
        statusValue.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        statusValue.setPadding(0, dp(4), 0, 0);
        header.addView(statusValue);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(dp(12), dp(12), dp(12), dp(30));
        scroll.addView(listBox);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    // ------------------------------------------------------------------ 加载

    private void load() {
        if (loading) {
            return;
        }
        loading = true;
        listBox.removeAllViews();
        groupSubs.clear();
        groupChecks.clear();
        groupCards.clear();
        if (!ClashCoreManager.isRunning()) {
            currentValue.setText("内核未运行");
            statusValue.setText("先把内置内核启动起来，再来选节点");
            addBigButton("启动内核", COLOR_BLUE, v -> startCore());
            addHint("内核起来后会自动拉取订阅里的节点，按地区分好组。");
            loading = false;
            return;
        }
        currentValue.setText("读取中…");
        statusValue.setText("");
        new Thread("geekgram-nodes-load") {
            @Override
            public void run() {
                final JSONObject all = ClashApi.proxies();
                final JSONObject proxyRoot = all == null ? null : all.optJSONObject("proxies");
                final String now = proxyRoot == null ? null : nameOf(proxyRoot.optJSONObject("PROXY"));
                final LinkedHashMap<String, String[]> rows = new LinkedHashMap<>();
                final LinkedHashMap<String, String[]> meta = new LinkedHashMap<>();
                if (proxyRoot != null) {
                    // 自动选择
                    rows.put("自动选择", new String[]{"自动选择", "一直在所有节点里挑最快的"});
                    // 手动切换
                    JSONObject manual = proxyRoot.optJSONObject("手动切换");
                    int manualCount = manual == null ? 0 : countOf(manual);
                    rows.put("手动切换", new String[]{"手动切换", manualCount > 0
                            ? ("订阅里全部 " + manualCount + " 个节点，自己点") : "订阅里没有节点"});
                    // 地区组
                    for (int i = 0; i < REGION_NAMES.length; i++) {
                        JSONObject g = proxyRoot.optJSONObject(REGION_NAMES[i]);
                        int n = g == null ? 0 : countOf(g);
                        String sub = n > 0 ? (n + " 个节点 · 自动挑最快") : "订阅里没有这个地区的节点";
                        rows.put(REGION_NAMES[i], new String[]{REGION_FLAGS[i] + "  " + REGION_NAMES[i], sub});
                        if (g != null) {
                            meta.put(REGION_NAMES[i], new String[]{String.valueOf(n), String.valueOf(ClashApi.lastDelay(g))});
                        }
                    }
                    rows.put("DIRECT", new String[]{"DIRECT", "不走代理，直接连"});
                }
                AndroidUtilities.runOnUIThread(() -> {
                    loading = false;
                    render(proxyRoot == null, now, rows, meta);
                });
            }
        }.start();
    }

    private void render(boolean noData, String now, LinkedHashMap<String, String[]> rows, LinkedHashMap<String, String[]> meta) {
        if (noData || rows.isEmpty()) {
            currentValue.setText("读不到节点");
            statusValue.setText("内核可能还在启动，稍等一下再进这个页面");
            addBigButton("重新读取", COLOR_BLUE, v -> load());
            loading = false;
            return;
        }
        currentValue.setText("当前：" + (TextUtils.isEmpty(now) ? "自动选择" : now));
        statusValue.setText("点一下就切换，立刻生效");
        for (Map.Entry<String, String[]> e : rows.entrySet()) {
            String key = e.getKey();
            String name = e.getValue()[0];
            String sub = e.getValue()[1];
            String delayText = null;
            if (meta.containsKey(key)) {
                int n = parseInt(meta.get(key)[0]);
                int d = parseInt(meta.get(key)[1]);
                if (n <= 0) {
                    delayText = "空";
                } else if (d > 0) {
                    delayText = d + " ms";
                }
            }
            boolean isDirect = "DIRECT".equals(key);
            View card = addCard(key, name, sub, delayText, key.equals(now), isDirect);
            groupCards.put(key, card);
        }
        addHint("分组里的节点来自你的订阅。点分组＝让所有流量走这组；长按分组＝在这组里手动指定一个节点。");
    }

    private int countOf(JSONObject group) {
        try {
            org.json.JSONArray arr = group.optJSONArray("all");
            return arr == null ? 0 : arr.length();
        } catch (Throwable t) {
            return 0;
        }
    }

    private String nameOf(JSONObject item) {
        if (item == null) {
            return "";
        }
        String n = item.optString("now", "");
        if (TextUtils.isEmpty(n)) {
            org.json.JSONArray all = item.optJSONArray("all");
            if (all != null && all.length() > 0) {
                n = String.valueOf(all.opt(0));
            }
        }
        return n == null ? "" : n;
    }

    // ------------------------------------------------------------------ 交互

    private void selectGroup(final String group) {
        Toast.makeText(this, "切换中…", Toast.LENGTH_SHORT).show();
        new Thread("geekgram-node-select") {
            @Override
            public void run() {
                final boolean ok = ClashApi.select("PROXY", group);
                AndroidUtilities.runOnUIThread(() -> {
                    if (ok) {
                        Toast.makeText(ProxyNodeActivity.this, "已切到 " + group, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(ProxyNodeActivity.this, "没切成，内核可能刚重启，过一下再点", Toast.LENGTH_SHORT).show();
                    }
                    load();
                });
            }
        }.start();
    }

    private void pickNode(final String group) {
        Toast.makeText(this, "读取节点…", Toast.LENGTH_SHORT).show();
        new Thread("geekgram-node-pick") {
            @Override
            public void run() {
                final JSONObject g = ClashApi.proxy(group);
                final ArrayList<String> names = new ArrayList<>();
                String now = "";
                if (g != null) {
                    now = g.optString("now", "");
                    org.json.JSONArray arr = g.optJSONArray("all");
                    for (int i = 0; arr != null && i < arr.length(); i++) {
                        String n = arr.optString(i, "");
                        if (!TextUtils.isEmpty(n)) {
                            names.add(n);
                        }
                    }
                }
                final String cur = now;
                AndroidUtilities.runOnUIThread(() -> {
                    if (names.isEmpty()) {
                        Toast.makeText(ProxyNodeActivity.this, "这组里没有节点（订阅里没有这个地区）", Toast.LENGTH_LONG).show();
                        return;
                    }
                    String[] items = new String[names.size()];
                    for (int i = 0; i < names.size(); i++) {
                        String n = names.get(i);
                        items[i] = n.equals(cur) ? ("✓  " + n) : ("    " + n);
                    }
                    new AlertDialog.Builder(ProxyNodeActivity.this)
                            .setTitle(group + " · 选节点")
                            .setItems(items, (d, which) -> {
                                String picked = names.get(which);
                                Toast.makeText(ProxyNodeActivity.this, "切换中…", Toast.LENGTH_SHORT).show();
                                new Thread("geekgram-node-pick-set") {
                                    @Override
                                    public void run() {
                                        ClashApi.select(group, picked);
                                        ClashApi.select("PROXY", group);
                                        AndroidUtilities.runOnUIThread(() -> {
                                            Toast.makeText(ProxyNodeActivity.this, "已选 " + picked, Toast.LENGTH_SHORT).show();
                                            load();
                                        });
                                    }
                                }.start();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                });
            }
        }.start();
    }

    private void startCore() {
        Toast.makeText(this, "正在启动内核…", Toast.LENGTH_SHORT).show();
        ClashCoreManager.startAsync(null, false, (ok, error) -> {
            if (ok) {
                load();
            } else {
                Toast.makeText(this, "启动失败：" + (error == null ? "未知原因" : error), Toast.LENGTH_LONG).show();
                statusValue.setText(error == null ? "" : error);
            }
        });
    }

    /** 给分组和组内节点测速，测完直接改卡片上的数字 */
    private void testAll() {
        if (testing) {
            return;
        }
        if (!ClashCoreManager.isRunning()) {
            Toast.makeText(this, "内核没运行，先启动", Toast.LENGTH_SHORT).show();
            return;
        }
        testing = true;
        testBtn.setText("测速中…");
        Toast.makeText(this, "正在测速，稍等几秒", Toast.LENGTH_SHORT).show();
        new Thread("geekgram-nodes-test") {
            @Override
            public void run() {
                final LinkedHashMap<String, Integer> results = new LinkedHashMap<>();
                String[] targets = new String[]{"自动选择", "手动切换", "香港节点", "台湾节点",
                        "狮城节点", "日本节点", "美国节点", "韩国节点"};
                for (String t : targets) {
                    int d = ClashApi.delay(t, 3000);
                    results.put(t, d);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    testing = false;
                    testBtn.setText("测速");
                    for (Map.Entry<String, Integer> e : results.entrySet()) {
                        TextView sub = groupSubs.get(e.getKey());
                        if (sub == null) {
                            continue;
                        }
                        int d = e.getValue();
                        String base = sub.getTag() == null ? "" : String.valueOf(sub.getTag());
                        sub.setText(d > 0 ? (base + " · " + d + " ms") : (base + " · 超时"));
                        sub.setTextColor(d > 0 ? COLOR_OK : 0xFFE5533D);
                    }
                    load();
                });
            }
        }.start();
    }

    // ------------------------------------------------------------------ 视图

    private View addCard(final String key, String name, String sub, String delayText,
                         boolean selected, boolean isDirect) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(13), dp(14), dp(13));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CARD);
        bg.setCornerRadius(dp(14));
        if (selected) {
            bg.setStroke(dp(2), COLOR_BLUE);
        }
        card.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(9);
        listBox.addView(card, lp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        t.setTextColor(COLOR_TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(t);

        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        s.setTextColor(COLOR_SUB);
        s.setPadding(0, dp(3), 0, 0);
        s.setTag(sub);
        texts.addView(s);
        groupSubs.put(key, s);
        card.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!TextUtils.isEmpty(delayText)) {
            TextView d = new TextView(this);
            d.setText(delayText);
            d.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            d.setTextColor("空".equals(delayText) ? COLOR_SUB : COLOR_OK);
            d.setPadding(dp(8), 0, dp(8), 0);
            card.addView(d);
        }

        TextView check = new TextView(this);
        check.setText(selected ? "✓" : "");
        check.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        check.setTextColor(COLOR_BLUE);
        check.setGravity(Gravity.CENTER);
        check.setWidth(dp(22));
        card.addView(check);
        groupChecks.put(key, check);

        card.setOnClickListener(v -> {
            if (isDirect || !key.equals("手动切换")) {
                selectGroup(key);
            } else {
                pickNode("手动切换");
            }
        });
        if (!isDirect && "手动切换".equals(key)) {
            card.setOnLongClickListener(v -> {
                pickNode("手动切换");
                return true;
            });
        } else if (!"自动选择".equals(key)) {
            card.setOnLongClickListener(v -> {
                pickNode(key);
                return true;
            });
        }
        return card;
    }

    private void addBigButton(String text, int color, View.OnClickListener cb) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(13), dp(16), dp(13));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        b.setOnClickListener(cb);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        listBox.addView(b, lp);
    }

    private void addHint(String text) {
        TextView note = new TextView(this);
        note.setText(text);
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        note.setTextColor(COLOR_SUB);
        note.setLineSpacing(dp(3), 1f);
        note.setPadding(dp(6), dp(14), dp(6), dp(6));
        listBox.addView(note);
    }

    private int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return -1;
        }
    }

    private int dp(float v) {
        return AndroidUtilities.dp(v);
    }
}
