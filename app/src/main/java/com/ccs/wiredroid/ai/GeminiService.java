package com.ccs.wiredroid.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.SecureValueStore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Сервіс для роботи з Google Gemini API (AI Studio).
 * Підтримує gemini-2.5-flash, gemini-2.5-flash-lite, gemini-2.0-flash.
 */
public final class GeminiService {

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String PREFS_NAME = "gemini_settings";
    private static final String KEY_API_KEY = "gemini_api_key";
    private static final String KEY_MODEL = "gemini_model";

    private static final String KEY_MODEL_CACHE = "gemini_model_cache";

    public static final String MODEL_25_FLASH = "gemini-2.5-flash";
    public static final String MODEL_25_FLASH_LITE = "gemini-2.5-flash-lite";
    public static final String MODEL_25_PRO = "gemini-2.5-pro";
    public static final String MODEL_20_FLASH = "gemini-2.0-flash";

    /** What to offer before the key has been checked and Google has listed its own. */
    public static final String[] AVAILABLE_MODELS = {
            MODEL_25_FLASH,
            MODEL_25_FLASH_LITE,
            MODEL_25_PRO,
            MODEL_20_FLASH
    };

    public static final String[] MODEL_DISPLAY_NAMES = {
            "Gemini 2.5 Flash",
            "Gemini 2.5 Flash Lite",
            "Gemini 2.5 Pro",
            "Gemini 2.0 Flash"
    };

    /**
     * The models the key can actually use, as the API listed them.
     *
     * <p>A built-in list goes stale, and a stale one is not a cosmetic problem:
     * the app shipped with {@code gemini-3.5-flash} as its default, a name that
     * does not exist, so every message failed for someone who had just entered a
     * perfectly good key. Verifying the key already lists the models, so the
     * answer is kept and used.</p>
     */
    public static String[] models(Context ctx) {
        String cached = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_MODEL_CACHE, "");
        if (cached == null || cached.isEmpty()) return AVAILABLE_MODELS;
        String[] ids = cached.split("\\n");
        return ids.length == 0 ? AVAILABLE_MODELS : ids;
    }

    /** Names for {@link #models}, in the same order. */
    public static String[] modelDisplayNames(Context ctx) {
        String[] ids = models(ctx);
        if (ids == AVAILABLE_MODELS) return MODEL_DISPLAY_NAMES;
        String[] names = new String[ids.length];
        for (int i = 0; i < ids.length; i++) names[i] = displayNameOf(ids[i]);
        return names;
    }

    /** "gemini-2.5-flash-lite" reads as "Gemini 2.5 Flash Lite". */
    public static String displayNameOf(String id) {
        if (id == null || id.isEmpty()) return "";
        for (int i = 0; i < AVAILABLE_MODELS.length; i++) {
            if (AVAILABLE_MODELS[i].equals(id)) return MODEL_DISPLAY_NAMES[i];
        }
        StringBuilder out = new StringBuilder();
        for (String part : id.split("-")) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.isLetter(part.charAt(0))
                    ? Character.toUpperCase(part.charAt(0)) + part.substring(1)
                    : part);
        }
        return out.toString();
    }

    /** Remembers the usable models from a {@code GET /models} body. */
    private static void cacheModels(Context ctx, String body) {
        if (ctx == null || body == null || body.isEmpty()) return;
        try {
            org.json.JSONArray list = new JSONObject(body).optJSONArray("models");
            if (list == null) return;
            StringBuilder ids = new StringBuilder();
            for (int i = 0; i < list.length(); i++) {
                JSONObject model = list.optJSONObject(i);
                if (model == null) continue;
                org.json.JSONArray methods = model.optJSONArray("supportedGenerationMethods");
                boolean chats = false;
                for (int m = 0; methods != null && m < methods.length(); m++) {
                    if ("generateContent".equals(methods.optString(m))) { chats = true; break; }
                }
                if (!chats) continue;
                String name = model.optString("name", "");
                if (name.startsWith("models/")) name = name.substring("models/".length());
                if (!name.startsWith("gemini-")) continue;
                if (ids.length() > 0) ids.append('\n');
                ids.append(name);
            }
            if (ids.length() == 0) return;
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_MODEL_CACHE, ids.toString()).apply();
        } catch (Exception ignored) {
            // An unreadable list leaves the built-in one in place, which is the
            // same position the app was in before asking.
        }
    }

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private GeminiService() {}

    // ── API Key Management ─────────────────────────────────────

    /**
     * Stores the key, encrypted.
     *
     * @return false when it could not be stored — the caller must say so rather
     *         than report success.
     *
     * <p>This used to return void and do nothing at all when the keystore was
     * unavailable: the key was dropped on the floor while the dialog still said
     * "Key saved", and the next request reported no key set. Falling back to
     * cleartext is deliberately not the answer — the store exists because this
     * value used to sit in plain preferences — so the failure is surfaced.</p>
     */
    public static boolean setApiKey(Context ctx, String apiKey) {
        // No "clear the legacy cleartext copy" step, because there is no separate
        // copy to clear. SecureValueStore writes into this same preferences file
        // under this same name — it only marks the value with a "v1:" prefix — so
        // the write above has already replaced any cleartext that was there.
        // Removing afterwards deleted the ciphertext that had just been stored,
        // which is why a saved key was gone by the next message.
        return SecureValueStore.put(ctx, PREFS_NAME, KEY_API_KEY, apiKey);
    }

    /**
     * Tidies a pasted key: people paste with quotes, a {@code KEY=} prefix, or a
     * trailing newline, and every one of those reaches the API as part of the
     * credential and comes back as an opaque 400.
     */
    public static String sanitizeApiKey(String pasted) {
        if (pasted == null) return "";
        String key = pasted.trim();
        int equals = key.indexOf('=');
        if (equals > 0 && key.substring(0, equals).matches("(?i)[a-z_ ]*key")) {
            key = key.substring(equals + 1).trim();
        }
        while (key.length() > 1
                && ((key.startsWith("\"") && key.endsWith("\""))
                || (key.startsWith("'") && key.endsWith("'"))
                || (key.startsWith("`") && key.endsWith("`")))) {
            key = key.substring(1, key.length() - 1).trim();
        }
        return key.replaceAll("\\s", "");
    }

    /**
     * What is certainly wrong with this key, in words, or null.
     *
     * <p>Deliberately narrow. AI Studio issues keys in more than one shape — the
     * long-standing {@code AIza…} and the newer {@code AQ.…} — and the prefix is
     * Google's to change again. Guessing validity from the prefix would reject a
     * working key, which is worse than storing one that turns out not to work:
     * the second is one clear error message away from being understood, the
     * first is a wall. Only shapes that cannot be a key at all are named here;
     * everything else is settled by {@link #verifyApiKey}, which asks the API.</p>
     */
    public static String describeKeyProblem(Context ctx, String key) {
        if (key == null || key.isEmpty()) {
            return ctx.getString(R.string.ai_key_problem_empty);
        }
        if (key.startsWith("http://") || key.startsWith("https://")) {
            return ctx.getString(R.string.ai_key_problem_url);
        }
        if (key.startsWith("sk-") || key.startsWith("sk_")) {
            return ctx.getString(R.string.ai_key_problem_openai);
        }
        if (key.length() < 20) {
            return ctx.getString(R.string.ai_key_problem_short, key.length());
        }
        return null;
    }

    /**
     * Asks the API whether the key works, rather than guessing from its shape.
     *
     * <p>The one check that stays true when Google changes the key format. Uses
     * the cheapest call there is — listing models — so verifying costs nothing
     * against the quota and does not depend on the chosen model existing.</p>
     */
    /** Also remembers which models the key may use. */
    public static void verifyApiKey(Context ctx, String apiKey, ResponseCallback callback) {
        final Context app = ctx.getApplicationContext();
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("x-goog-api-key", apiKey);
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(20_000);

                int code = conn.getResponseCode();
                if (code == 200) {
                    cacheModels(app, readStream(conn.getInputStream()));
                    mainHandler.post(() ->
                            callback.onSuccess(app.getString(R.string.ai_key_works)));
                    return;
                }
                String body = readStream(conn.getErrorStream());
                String detail = extractApiMessage(body);
                final String message;
                if (code == 400 || code == 401 || code == 403) {
                    message = app.getString(R.string.ai_key_rejected, code,
                            detail.isEmpty() ? "" : "\n\n" + detail);
                } else {
                    message = app.getString(R.string.ai_key_check_failed, code,
                            detail.isEmpty() ? "" : "\n\n" + detail);
                }
                mainHandler.post(() -> callback.onError(message));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(
                        app.getString(R.string.ai_key_unreachable,
                                e.getClass().getSimpleName()
                                        + (e.getMessage() == null ? "" : ": " + e.getMessage()))));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /** Pulls the human-readable part out of an API error body. */
    private static String extractApiMessage(String body) {
        if (body == null || body.isEmpty()) return "";
        try {
            JSONObject error = new JSONObject(body).optJSONObject("error");
            if (error != null) {
                String message = error.optString("message", "");
                if (!message.isEmpty()) return message;
            }
        } catch (Exception ignored) {
            // Not the documented shape; the raw body is still better than nothing.
        }
        return body.length() > 400 ? body.substring(0, 400) + "…" : body;
    }

    public static String getApiKey(Context ctx) {
        String secure = SecureValueStore.get(ctx, PREFS_NAME, KEY_API_KEY);
        if (secure != null) return secure;
        // Nothing decryptable in the slot, so whatever is there is either absent
        // or a cleartext key written by an older build. Re-storing it encrypts it
        // in place; the old value is overwritten by that write, and deleting the
        // slot afterwards would throw away what was just written — the same
        // mistake that used to make every save vanish.
        SharedPreferences raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String legacy = raw.getString(KEY_API_KEY, "");
        if (legacy != null && !legacy.isEmpty()) {
            SecureValueStore.put(ctx, PREFS_NAME, KEY_API_KEY, legacy);
        }
        return legacy == null ? "" : legacy;
    }

    public static boolean hasApiKey(Context ctx) {
        String key = getApiKey(ctx);
        return key != null && !key.trim().isEmpty();
    }

    public static void setSelectedModel(Context ctx, String model) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODEL, model).apply();
    }

    /**
     * The chosen model, or a working one when that choice cannot be honoured.
     *
     * <p>Checked against the live list when there is one, so a model that was
     * retired — or one this app shipped a wrong name for — is replaced instead
     * of failing every request.</p>
     */
    public static String getSelectedModel(Context ctx) {
        String[] available = models(ctx);
        String model = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_MODEL, MODEL_25_FLASH);
        for (String m : available) {
            if (m.equals(model)) return model;
        }
        for (String preferred : AVAILABLE_MODELS) {
            for (String m : available) {
                if (m.equals(preferred)) return preferred;
            }
        }
        return available.length > 0 ? available[0] : MODEL_25_FLASH;
    }

    // ── Chat (conversation with history) ───────────────────────

    public interface ResponseCallback {
        void onSuccess(String response);
        void onError(String error);
    }

    /** Raw generateContent response used by the agent's native function-call loop. */
    public interface JsonResponseCallback {
        void onSuccess(JSONObject response);
        void onError(String error);
    }

    /**
     * Надсилає повідомлення з контекстом коду (system prompt + chat history).
     */
    public static void chat(Context ctx, String systemPrompt, String userMessage,
                             java.util.List<ChatMessage> history, ResponseCallback callback) {
        try {
            JSONObject body = buildRequestBody(systemPrompt, userMessage, history);
            generateContent(ctx, body, new JsonResponseCallback() {
                @Override public void onSuccess(JSONObject response) {
                    try {
                        String text = parseResponse(response);
                        if (text == null) callback.onError(ctx.getString(R.string.ai_err_empty));
                        else callback.onSuccess(text);
                    } catch (JSONException e) {
                        callback.onError(ctx.getString(R.string.ai_err_empty));
                    }
                }

                @Override public void onError(String error) {
                    callback.onError(error);
                }
            });
        } catch (JSONException e) {
            mainHandler.post(() -> callback.onError(ctx.getString(R.string.ai_err_network,
                    e.getClass().getSimpleName() + ": " + e.getMessage())));
        }
    }

    /**
     * Sends an already-built Gemini request body and returns the complete JSON.
     * Keeping the model content intact is required for function calling: the next
     * request must contain the exact model functionCall part and matching
     * functionResponse, not a lossy text summary of the previous step.
     */
    public static void generateContent(Context ctx, JSONObject body,
                                       JsonResponseCallback callback) {
        if (!hasApiKey(ctx)) {
            mainHandler.post(() -> callback.onError("No API key set. Go to Settings → AI."));
            return;
        }

        final Context app = ctx.getApplicationContext();
        final String apiKey = getApiKey(ctx);
        final String model = getSelectedModel(ctx);
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                if (com.ccs.wiredroid.BuildConfig.DEBUG) {
                    android.util.Log.d("GeminiService", "POST " + model + " body="
                            + body.toString().length() + "B");
                }
                URL url = new URL(BASE_URL + model + ":generateContent");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("x-goog-api-key", apiKey);
                conn.setDoOutput(true);
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(30_000);

                byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bodyBytes);
                }

                int responseCode = conn.getResponseCode();
                if (com.ccs.wiredroid.BuildConfig.DEBUG) {
                    android.util.Log.d("GeminiService", "HTTP " + responseCode);
                }
                if (responseCode != 200) {
                    String errBody = readStream(conn.getErrorStream());
                    String detail = extractApiMessage(errBody);
                    String lower = detail.toLowerCase(java.util.Locale.ROOT);
                    String hint;
                    if (responseCode == 400 && lower.contains("model")) {
                        hint = app.getString(R.string.ai_err_hint_model);
                    } else if (responseCode == 400) {
                        hint = app.getString(R.string.ai_err_hint_key);
                    } else if (responseCode == 401 || responseCode == 403) {
                        hint = app.getString(R.string.ai_err_hint_rejected);
                    } else if (responseCode == 404) {
                        hint = app.getString(R.string.ai_err_hint_model);
                    } else if (responseCode == 429) {
                        hint = app.getString(R.string.ai_err_hint_rate);
                    } else if (responseCode >= 500) {
                        hint = app.getString(R.string.ai_err_hint_google);
                    } else {
                        hint = "";
                    }
                    String error = (detail.isEmpty() ? "HTTP " + responseCode : detail)
                            + (hint.isEmpty() ? "" : "\n\n" + hint);
                    mainHandler.post(() -> callback.onError(error));
                    return;
                }

                String responseBody = readStream(conn.getInputStream());
                JSONObject response = new JSONObject(responseBody);
                mainHandler.post(() -> callback.onSuccess(response));
            } catch (Exception e) {
                if (com.ccs.wiredroid.BuildConfig.DEBUG) {
                    android.util.Log.e("GeminiService", "request failed", e);
                }
                String message = app.getString(R.string.ai_err_network,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                mainHandler.post(() -> callback.onError(message));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /**
     * Швидкий одноразовий запит без історії (для scan/explain).
     */
    public static void quickPrompt(Context ctx, String prompt, ResponseCallback callback) {
        chat(ctx, "", prompt, null, callback);
    }

    // ── Request Building ───────────────────────────────────────

    private static JSONObject buildRequestBody(String systemPrompt, String userMessage,
                                                java.util.List<ChatMessage> history) throws JSONException {
        JSONObject body = new JSONObject();

        JSONArray contents = new JSONArray();

        // System instruction (як user/system повідомлення на початку)
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "user");
            sysMsg.put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt)));
            contents.put(sysMsg);

            JSONObject sysResp = new JSONObject();
            sysResp.put("role", "model");
            sysResp.put("parts", new JSONArray().put(new JSONObject().put("text", "Understood. I will follow these instructions.")));
            contents.put(sysResp);
        }

        // History
        if (history != null) {
            for (ChatMessage msg : history) {
                JSONObject m = new JSONObject();
                m.put("role", msg.isFromUser() ? "user" : "model");
                m.put("parts", new JSONArray().put(new JSONObject().put("text", msg.getText())));
                contents.put(m);
            }
        }

        // Current user message
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("parts", new JSONArray().put(new JSONObject().put("text", userMessage)));
        contents.put(userMsg);

        body.put("contents", contents);

        // Generation config
        JSONObject genConfig = new JSONObject();
        genConfig.put("temperature", 0.7);
        genConfig.put("maxOutputTokens", 8192);

        // Disable Gemini 2.5+/3.x "thinking" mode. Without this the model burns its
        // entire output-token budget on internal reasoning and returns an EMPTY
        // visible answer (only an opaque thoughtSignature), which stalls the agent
        // loop and leaves the UI waiting indefinitely. thinkingBudget:0 = off.
        JSONObject thinkingConfig = new JSONObject();
        thinkingConfig.put("thinkingBudget", 0);
        genConfig.put("thinkingConfig", thinkingConfig);

        body.put("generationConfig", genConfig);

        return body;
    }

    private static String parseResponse(JSONObject json) throws JSONException {
        JSONArray candidates = json.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            return "No response from AI.";
        }
        JSONObject candidate = candidates.getJSONObject(0);
        JSONObject content = candidate.optJSONObject("content");
        if (content == null) return "Empty response.";

        JSONArray parts = content.optJSONArray("parts");
        if (parts == null || parts.length() == 0) return "No content.";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            String text = part.optString("text", "");
            if (!text.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(text);
            }
        }

        // If text is empty but there are parts, it's likely a thinking-only
        // response (all tokens spent on internal reasoning). Return null so the
        // caller can distinguish "no real answer" from an actual text reply and
        // avoid treating a stub message as a final response.
        if (sb.length() == 0 && parts.length() > 0) {
            return null;
        }

        return sb.toString();
    }

    private static String readStream(java.io.InputStream is) throws java.io.IOException {
        if (is == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString().trim();
    }

    // ── Chat Message Model ─────────────────────────────────────

    public static class ChatMessage {
        private final String text;
        private final boolean fromUser;

        public ChatMessage(String text, boolean fromUser) {
            this.text = text;
            this.fromUser = fromUser;
        }

        public String getText() { return text; }
        public boolean isFromUser() { return fromUser; }
    }
}
