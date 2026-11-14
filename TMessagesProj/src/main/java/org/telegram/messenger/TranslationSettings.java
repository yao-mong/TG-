package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

/**
 * Geekgram: global message-translation preferences (auto translate, target language,
 * display mode, provider, AI calibration).
 */
public class TranslationSettings {

    public static final String PROVIDER_TELEGRAM = "telegram";
    public static final String PROVIDER_GOOGLE = "google";
    public static final String PROVIDER_GEMINI = "gemini";

    public static final int DISPLAY_ONLY_TRANSLATION = 0;
    public static final int DISPLAY_ORIGINAL_AND_TRANSLATION = 1;

    // Geekgram v4: 换成新键，默认开启（旧版本留下的「关」不再沿用，保证装上就能看到译文）
    private static final String KEY_AUTO_TRANSLATE = "gg_translation_auto_v4";
    private static final String KEY_TARGET_LANGUAGE = "gg_translation_target";
    private static final String KEY_DISPLAY_MODE = "gg_translation_display_mode";
    private static final String KEY_PROVIDER = "gg_translation_provider";
    private static final String KEY_AI_CALIBRATION = "gg_translation_ai_calibration";
    private static final String KEY_GEMINI_KEY = "gg_translation_gemini_key";

    private static SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean isAutoTranslateEnabled() {
        // Geekgram: 默认开启，用户手动关掉才生效
        return prefs().getBoolean(KEY_AUTO_TRANSLATE, true);
    }

    public static void setAutoTranslateEnabled(boolean value) {
        prefs().edit().putBoolean(KEY_AUTO_TRANSLATE, value).apply();
        notifyChanged();
    }

    /** null means "follow the interface language" */
    public static String getTargetLanguage() {
        final String value = prefs().getString(KEY_TARGET_LANGUAGE, null);
        return TextUtils.isEmpty(value) ? null : value;
    }

    public static void setTargetLanguage(String language) {
        prefs().edit().putString(KEY_TARGET_LANGUAGE, language).apply();
        notifyChanged();
    }

    public static int getDisplayMode() {
        return prefs().getInt(KEY_DISPLAY_MODE, DISPLAY_ONLY_TRANSLATION);
    }

    public static void setDisplayMode(int mode) {
        prefs().edit().putInt(KEY_DISPLAY_MODE, mode).apply();
        notifyChanged();
    }

    /** true = 显示原文和译文 */
    public static boolean isShowOriginal() {
        return getDisplayMode() == DISPLAY_ORIGINAL_AND_TRANSLATION;
    }

    public static String getMessageProvider() {
        final String value = prefs().getString(KEY_PROVIDER, PROVIDER_TELEGRAM);
        if (PROVIDER_GOOGLE.equals(value) || PROVIDER_GEMINI.equals(value)) {
            return value;
        }
        return PROVIDER_TELEGRAM;
    }

    public static void setMessageProvider(String provider) {
        prefs().edit().putString(KEY_PROVIDER, provider).apply();
        notifyChanged();
    }

    public static boolean isAiCalibrationEnabled() {
        return prefs().getBoolean(KEY_AI_CALIBRATION, false);
    }

    public static void setAiCalibrationEnabled(boolean value) {
        prefs().edit().putBoolean(KEY_AI_CALIBRATION, value).apply();
        notifyChanged();
    }

    public static String getGeminiApiKey() {
        final String value = prefs().getString(KEY_GEMINI_KEY, null);
        return TextUtils.isEmpty(value) ? null : value.trim();
    }

    public static void setGeminiApiKey(String key) {
        prefs().edit().putString(KEY_GEMINI_KEY, key == null ? null : key.trim()).apply();
    }

    public static boolean isGeminiReady() {
        return !TextUtils.isEmpty(getGeminiApiKey());
    }

    /** Combined text for "显示原文和译文" mode. */
    public static CharSequence displayText(CharSequence original, TLRPC.TL_textWithEntities translation) {
        if (translation == null || TextUtils.isEmpty(translation.text)) {
            return original;
        }
        if (!isShowOriginal() || TextUtils.isEmpty(original)) {
            return translation.text;
        }
        return original + "\n" + translation.text;
    }

    public static void notifyChanged() {
        try {
            final int account = UserConfig.selectedAccount;
            TranslateController controller = MessagesController.getInstance(account).getTranslateController();
            controller.checkRestrictedLanguagesUpdate();
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_CHAT);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
