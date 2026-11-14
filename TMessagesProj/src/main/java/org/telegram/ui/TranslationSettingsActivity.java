package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GeminiTranslator;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.TranslateController;
import org.telegram.messenger.TranslationSettings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ListView.AdapterWithDiffUtils;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Geekgram: 消息翻译设置
 */
public class TranslationSettingsActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter adapter;

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_CHECK = 1;
    private static final int VIEW_TYPE_TEXT = 2;
    private static final int VIEW_TYPE_SHADOW = 3;
    private static final int VIEW_TYPE_PREVIEW = 4;

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);

        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        simpleActionBar.setTitle("消息翻译设置");
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setSections();
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setSectionsDrawBackground(true);
        listView.setLayoutAnimation(null);
        listView.setAdapter(adapter = new ListAdapter());
        DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setDurations(350);
        itemAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        itemAnimator.setDelayAnimations(false);
        itemAnimator.setSupportsChangeAnimations(false);
        listView.setItemAnimator(itemAnimator);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> onItemClick(view, position));

        updateItems(false);

        return fragmentView;
    }

    private int previewVersion;

    private void onItemClick(View view, int position) {
        if (position < 0 || position >= items.size()) {
            return;
        }
        final ItemInner item = items.get(position);
        if (item.id == 1) {
            final boolean value = !TranslationSettings.isAutoTranslateEnabled();
            TranslationSettings.setAutoTranslateEnabled(value);
            ((TextCheckCell) view).setChecked(value);
            // Geekgram: 开关一变，立刻把当前会话重新翻一遍，不用退出重进
            TranslateController controller = getMessagesController() == null ? null : getMessagesController().getTranslateController();
            if (controller != null) {
                controller.onGlobalAutoTranslateChanged(value);
            }
        } else if (item.id == 2) {
            showTargetLanguagePicker();
        } else if (item.id == 3) {
            presentFragment(new RestrictedLanguagesSelectActivity());
        } else if (item.id == 11) {
            showDisplayModePicker();
        } else if (item.id == 21) {
            showProviderPicker();
        } else if (item.id == 22) {
            showAiCalibrationPicker();
        } else if (item.id == 23) {
            showGeminiKeyDialog();
        } else if (item.id == 24) {
            testGemini();
        }
    }

    private void showTargetLanguagePicker() {
        final ArrayList<TranslateController.Language> languages = TranslateController.getLanguages();
        final CharSequence[] names = new CharSequence[languages.size() + 1];
        names[0] = "跟随应用（" + currentTargetLanguageName() + "）";
        for (int i = 0; i < languages.size(); ++i) {
            final TranslateController.Language lang = languages.get(i);
            names[i + 1] = TextUtils.isEmpty(lang.ownDisplayName) || TextUtils.equals(lang.ownDisplayName, lang.displayName)
                ? lang.displayName : lang.displayName + " · " + lang.ownDisplayName;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("目标语言");
        builder.setItems(names, (dialog, which) -> {
            if (which == 0) {
                TranslationSettings.setTargetLanguage(null);
            } else {
                TranslationSettings.setTargetLanguage(languages.get(which - 1).code);
            }
            updateItems(true);
            refreshList();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showDisplayModePicker() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("翻译后的显示效果");
        builder.setItems(new CharSequence[]{"显示原文和译文", "只显示译文"}, (dialog, which) -> {
            TranslationSettings.setDisplayMode(which == 0
                ? TranslationSettings.DISPLAY_ORIGINAL_AND_TRANSLATION
                : TranslationSettings.DISPLAY_ONLY_TRANSLATION);
            updateItems(true);
            refreshList();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showProviderPicker() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("消息翻译");
        builder.setItems(new CharSequence[]{"Telegram 翻译", "Google 翻译", "Gemini（需 API Key）"}, (dialog, which) -> {
            TranslationSettings.setMessageProvider(which == 0
                ? TranslationSettings.PROVIDER_TELEGRAM
                : which == 1 ? TranslationSettings.PROVIDER_GOOGLE : TranslationSettings.PROVIDER_GEMINI);
            if (which == 2 && !TranslationSettings.isGeminiReady()) {
                showGeminiKeyDialog();
            }
            updateItems(true);
            refreshList();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showAiCalibrationPicker() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("AI 翻译校准");
        builder.setMessage("开启后，Google 翻译的结果会再交给 Gemini 润色一遍，读起来更自然。需要填 Gemini API Key。");
        builder.setItems(new CharSequence[]{"关闭", "Gemini 校准"}, (dialog, which) -> {
            TranslationSettings.setAiCalibrationEnabled(which == 1);
            if (which == 1 && !TranslationSettings.isGeminiReady()) {
                showGeminiKeyDialog();
            }
            updateItems(true);
            refreshList();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showGeminiKeyDialog() {
        if (getParentActivity() == null) {
            return;
        }
        final Context context = getParentActivity();
        final FrameLayout container = new FrameLayout(context);
        final EditText editText = new EditText(context);
        editText.setHint("AIza...");
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        final String current = TranslationSettings.getGeminiApiKey();
        if (current != null) {
            editText.setText(current);
        }
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(getThemedColor(Theme.key_windowBackgroundGray));
        editText.setBackground(bg);
        editText.setPadding(dp(12), dp(10), dp(12), dp(10));
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 24, 8, 24, 4));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Gemini API Key");
        builder.setMessage("在 Google AI Studio 申请，填进来只存在本机，不会上传到别处。");
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            TranslationSettings.setGeminiApiKey(editText.getText().toString());
            updateItems(true);
            refreshList();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void testGemini() {
        if (!TranslationSettings.isGeminiReady()) {
            showGeminiKeyDialog();
            return;
        }
        final AlertDialog progressDialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.setCanCancel(false);
        showDialog(progressDialog);
        GeminiTranslator.translate("Hello world, this is a translation test.", TranslationSettings.getTargetLanguage() != null ? TranslationSettings.getTargetLanguage() : TranslateController.currentLanguage(), (result, error) -> {
            try {
                progressDialog.dismiss();
            } catch (Exception ignore) {
            }
            final Bulletin.SimpleLayout layout = new Bulletin.SimpleLayout(getContext(), getResourceProvider());
            if (result != null) {
                layout.textView.setText("Gemini 正常：" + result);
                layout.imageView.setImageResource(R.drawable.msg_translate);
            } else {
                layout.textView.setText("Gemini 连接失败：" + error);
                layout.imageView.setImageResource(R.drawable.msg_warning);
            }
            layout.textView.setSingleLine(false);
            layout.textView.setPadding(0, dp(4), 0, dp(4));
            Bulletin.make(TranslationSettingsActivity.this, layout, 4000).show();
        });
    }

    private String currentTargetLanguageName() {
        final String code = TranslationSettings.getTargetLanguage();
        if (code == null) {
            final String name = TranslateController.getLanguages() == null ? null
                : org.telegram.ui.Components.TranslateAlert2.languageName(TranslateController.currentLanguage());
            return TextUtils.isEmpty(name) ? "简体中文" : name;
        }
        final String name = org.telegram.ui.Components.TranslateAlert2.languageName(code);
        return TextUtils.isEmpty(name) ? code : name;
    }

    private void refreshList() {
        if (listView != null) {
            for (int i = 0; i < listView.getChildCount(); ++i) {
                listView.getChildAt(i).invalidate();
            }
            adapter.notifyDataSetChanged();
        }
    }

    private final ArrayList<ItemInner> oldItems = new ArrayList<>(), items = new ArrayList<>();

    private void updateItems(boolean animated) {
        oldItems.clear();
        oldItems.addAll(items);
        items.clear();

        items.add(new ItemInner(VIEW_TYPE_CHECK, 1, "自动翻译"));
        items.add(new ItemInner(VIEW_TYPE_TEXT, 2, "目标语言"));
        items.add(new ItemInner(VIEW_TYPE_TEXT, 3, "不翻译"));
        items.add(new ItemInner(VIEW_TYPE_SHADOW, 4, "打开自动翻译后，所有会话收到的消息都会自动翻成目标语言；「不翻译」里勾上的语言会跳过。"));

        items.add(new ItemInner(VIEW_TYPE_HEADER, 10, "翻译后的显示效果"));
        items.add(new ItemInner(VIEW_TYPE_PREVIEW, 11, null));
        items.add(new ItemInner(VIEW_TYPE_SHADOW, 12, null));

        items.add(new ItemInner(VIEW_TYPE_HEADER, 20, "翻译器"));
        items.add(new ItemInner(VIEW_TYPE_TEXT, 21, "消息翻译"));
        items.add(new ItemInner(VIEW_TYPE_TEXT, 22, "AI 翻译校准"));
        items.add(new ItemInner(VIEW_TYPE_TEXT, 23, "Gemini API Key"));
        items.add(new ItemInner(VIEW_TYPE_TEXT, 24, "测试 Gemini 连接"));
        items.add(new ItemInner(VIEW_TYPE_SHADOW, 25, "Telegram 翻译用服务器自带的翻译，最省事；Google 翻译走免费接口，不需要 Key；Gemini 翻译质量更好，但要填自己的 API Key。"));

        if (adapter == null) {
            return;
        }
        if (animated) {
            adapter.setItems(oldItems, items);
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    private static class ItemInner extends AdapterWithDiffUtils.Item {
        public CharSequence text;
        public int id;

        public ItemInner(int viewType, int id, CharSequence text) {
            super(viewType, false);
            this.id = id;
            this.text = text;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ItemInner item = (ItemInner) o;
            return id == item.id && Objects.equals(text, item.text);
        }
    }

    private class ListAdapter extends AdapterWithDiffUtils {

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_HEADER) {
                view = new HeaderCell(getContext());
            } else if (viewType == VIEW_TYPE_CHECK) {
                view = new TextCheckCell(getContext());
            } else if (viewType == VIEW_TYPE_TEXT) {
                view = new TextSettingsCell(getContext());
            } else if (viewType == VIEW_TYPE_PREVIEW) {
                view = new PreviewCell(getContext());
            } else {
                view = new TextInfoPrivacyCell(getContext());
            }
            return new RecyclerListView.Holder(view);
        }

        @SuppressLint("SetTextI18n")
        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position < 0 || position >= items.size()) {
                return;
            }
            final ItemInner item = items.get(position);
            final boolean divider = position + 1 < items.size() && items.get(position + 1).viewType == item.viewType;
            if (holder.getItemViewType() == VIEW_TYPE_HEADER) {
                ((HeaderCell) holder.itemView).setText(item.text);
            } else if (holder.getItemViewType() == VIEW_TYPE_SHADOW) {
                final TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                if (TextUtils.isEmpty(item.text)) {
                    cell.setFixedSize(12);
                    cell.setText(null);
                } else {
                    cell.setFixedSize(0);
                    cell.setText(item.text);
                }
            } else if (holder.getItemViewType() == VIEW_TYPE_CHECK) {
                ((TextCheckCell) holder.itemView).setTextAndCheck(item.text, TranslationSettings.isAutoTranslateEnabled(), divider);
            } else if (holder.getItemViewType() == VIEW_TYPE_TEXT) {
                final TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                CharSequence value = "";
                if (item.id == 2) {
                    value = currentTargetLanguageName();
                } else if (item.id == 3) {
                    final int count = RestrictedLanguagesSelectActivity.getRestrictedLanguages().size();
                    value = count == 0 ? "未设置" : count + " 种语言";
                } else if (item.id == 21) {
                    final String provider = TranslationSettings.getMessageProvider();
                    value = TranslationSettings.PROVIDER_GOOGLE.equals(provider) ? "Google 翻译"
                        : TranslationSettings.PROVIDER_GEMINI.equals(provider) ? "Gemini" : "Telegram 翻译";
                } else if (item.id == 22) {
                    value = TranslationSettings.isAiCalibrationEnabled() ? "Gemini 校准" : "关闭";
                } else if (item.id == 23) {
                    value = TranslationSettings.isGeminiReady() ? "已填写" : "未填写";
                }
                cell.setTextAndValue(item.text, value, divider);
                if (item.id == 21 && TranslationSettings.PROVIDER_GEMINI.equals(TranslationSettings.getMessageProvider()) && !TranslationSettings.isGeminiReady()) {
                    value = "Gemini · 未填 API Key";
                    cell.setTextAndValue(item.text, value, divider);
                }
            } else if (holder.getItemViewType() == VIEW_TYPE_PREVIEW) {
                ((PreviewCell) holder.itemView).update();
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int type = holder.getItemViewType();
            return type != VIEW_TYPE_SHADOW && type != VIEW_TYPE_HEADER;
        }

        @Override
        public int getItemViewType(int position) {
            if (position < 0 || position >= items.size()) {
                return 0;
            }
            return items.get(position).viewType;
        }
    }

    /** Two side-by-side preview bubbles, tapping one selects the display mode. */
    private class PreviewCell extends FrameLayout {

        private final LinearLayout leftBox, rightBox;
        private final TextView leftText, rightText, leftLabel, rightLabel;
        private final LinearLayout leftWrap, rightWrap;
        private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public PreviewCell(Context context) {
            super(context);
            setWillNotDraw(false);
            setPadding(0, dp(6), 0, dp(10));

            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            leftWrap = new LinearLayout(context);
            leftWrap.setOrientation(LinearLayout.VERTICAL);
            leftBox = new LinearLayout(context);
            leftBox.setOrientation(LinearLayout.VERTICAL);
            leftBox.setPadding(dp(14), dp(10), dp(14), dp(10));
            leftText = new TextView(context);
            leftText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            leftText.setText("Hello World\n你好世界");
            leftBox.addView(leftText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            leftWrap.addView(leftBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            leftLabel = new TextView(context);
            leftLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            leftLabel.setGravity(Gravity.CENTER);
            leftLabel.setPadding(0, dp(8), 0, 0);
            leftLabel.setText("显示原文和译文");
            leftWrap.addView(leftLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            row.addView(leftWrap, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 6, 0));

            rightWrap = new LinearLayout(context);
            rightWrap.setOrientation(LinearLayout.VERTICAL);
            rightBox = new LinearLayout(context);
            rightBox.setOrientation(LinearLayout.VERTICAL);
            rightBox.setPadding(dp(14), dp(10), dp(14), dp(10));
            rightText = new TextView(context);
            rightText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            rightText.setText("你好世界");
            rightBox.addView(rightText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            rightWrap.addView(rightBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            rightLabel = new TextView(context);
            rightLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            rightLabel.setGravity(Gravity.CENTER);
            rightLabel.setPadding(0, dp(8), 0, 0);
            rightLabel.setText("只显示译文");
            rightWrap.addView(rightLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            row.addView(rightWrap, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 6, 0, 12, 0));

            leftWrap.setOnClickListener(v -> {
                TranslationSettings.setDisplayMode(TranslationSettings.DISPLAY_ORIGINAL_AND_TRANSLATION);
                update();
                refreshList();
            });
            rightWrap.setOnClickListener(v -> {
                TranslationSettings.setDisplayMode(TranslationSettings.DISPLAY_ONLY_TRANSLATION);
                update();
                refreshList();
            });
            update();
        }

        public void update() {
            final boolean showOriginal = TranslationSettings.isShowOriginal();
            applyBox(leftBox, leftLabel, showOriginal, true);
            applyBox(rightBox, rightLabel, !showOriginal, false);
            invalidate();
        }

        private void applyBox(LinearLayout box, TextView label, boolean selected, boolean isOut) {
            final GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(selected
                ? getThemedColor(Theme.key_featuredStickers_addButton)
                : (isOut ? getThemedColor(Theme.key_chat_outBubble) : getThemedColor(Theme.key_chat_inBubble)));
            if (selected) {
                bg.setStroke(dp(2), getThemedColor(Theme.key_featuredStickers_addButton));
            }
            box.setBackground(bg);
            final int textColor = selected
                ? getThemedColor(Theme.key_featuredStickers_buttonText)
                : getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
            ((TextView) box.getChildAt(0)).setTextColor(textColor);
            label.setTextColor(selected
                ? getThemedColor(Theme.key_featuredStickers_addButton)
                : getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            label.setTypeface(selected ? AndroidUtilities.bold() : null);
        }
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Nullable
    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }
}
