package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Geekgram: minimal Gemini translator used by the "翻译器" setting
 * (Google Translate is provided by TranslateAlert2, Telegram by the server).
 */
public class GeminiTranslator {

    private static final String ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";

    private static final ExecutorService executor = Executors.newFixedThreadPool(2);

    private static String languageName(String code) {
        if (TextUtils.isEmpty(code)) {
            return "the target language";
        }
        try {
            String base = code.split("_")[0];
            Locale loc = new Locale(base);
            String name = loc.getDisplayLanguage(Locale.ENGLISH);
            if (!TextUtils.isEmpty(name) && !"und".equalsIgnoreCase(name)) {
                return name;
            }
        } catch (Exception ignore) {
        }
        return code;
    }

    public static void translate(String text, String toLanguage, Utilities.Callback2<String, String> done) {
        if (TextUtils.isEmpty(text)) {
            if (done != null) done.run(null, "empty");
            return;
        }
        final String prompt = "Translate the text below into " + languageName(toLanguage) + ".\n"
            + "Rules: output only the translation, keep line breaks and formatting, never explain or add notes.\n\n"
            + "TEXT:\n" + text;
        request(prompt, done);
    }

    /** Post-edits an existing machine translation so it reads naturally. */
    public static void polish(String translatedText, String toLanguage, Utilities.Callback<String> done) {
        if (TextUtils.isEmpty(translatedText)) {
            if (done != null) done.run(null);
            return;
        }
        final String prompt = "You are a translation proofreader. The text below is a machine translation into "
            + languageName(toLanguage) + ".\n"
            + "Rewrite it so it reads naturally in that language, keeping the meaning, emotion and line breaks unchanged.\n"
            + "Rules: output only the rewritten text, never explain.\n\n"
            + "TEXT:\n" + translatedText;
        request(prompt, (result, error) -> {
            if (done != null) done.run(result);
        });
    }

    private static void request(String prompt, Utilities.Callback2<String, String> done) {
        final String apiKey = TranslationSettings.getGeminiApiKey();
        if (TextUtils.isEmpty(apiKey)) {
            if (done != null) done.run(null, "no key");
            return;
        }
        executor.execute(() -> {
            String result = null, error = null;
            HttpURLConnection conn = null;
            try {
                final JSONObject body = new JSONObject();
                final JSONArray contents = new JSONArray();
                final JSONObject content = new JSONObject();
                final JSONArray parts = new JSONArray();
                final JSONObject part = new JSONObject();
                part.put("text", prompt);
                parts.put(part);
                content.put("parts", parts);
                contents.put(content);
                body.put("contents", contents);
                final JSONObject genCfg = new JSONObject();
                genCfg.put("temperature", 0.2);
                body.put("generationConfig", genCfg);

                conn = (HttpURLConnection) new URL(ENDPOINT + "?key=" + apiKey).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                final byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }

                final int code = conn.getResponseCode();
                final InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                final String responseText = readAll(is);
                if (code >= 200 && code < 300) {
                    final JSONObject json = new JSONObject(responseText);
                    final JSONArray candidates = json.optJSONArray("candidates");
                    if (candidates != null && candidates.length() > 0) {
                        final JSONObject candidate = candidates.getJSONObject(0);
                        final JSONObject contentObj = candidate.optJSONObject("content");
                        if (contentObj != null) {
                            final JSONArray partsArr = contentObj.optJSONArray("parts");
                            if (partsArr != null) {
                                final StringBuilder sb = new StringBuilder();
                                for (int i = 0; i < partsArr.length(); ++i) {
                                    final String piece = partsArr.getJSONObject(i).optString("text", "");
                                    sb.append(piece);
                                }
                                result = sb.toString().trim();
                            }
                        }
                    }
                    if (TextUtils.isEmpty(result)) {
                        error = "empty response";
                    }
                } else {
                    error = "HTTP " + code + (TextUtils.isEmpty(responseText) ? "" : (": " + firstLine(responseText)));
                }
            } catch (Throwable t) {
                FileLog.e(t);
                error = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : (": " + t.getMessage()));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            final String finalResult = result, finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (done != null) done.run(finalResult, finalError);
            });
        });
    }

    private static String firstLine(String text) {
        int idx = text.indexOf('\n');
        String line = idx >= 0 ? text.substring(0, idx) : text;
        return line.length() > 200 ? line.substring(0, 200) : line;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = is.read(buffer)) != -1) {
            bos.write(buffer, 0, read);
        }
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
