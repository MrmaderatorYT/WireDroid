package com.ccs.wiredroid.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Helper for sharing code to Pastebin via their API.
 * Requires a Pastebin API developer key (free at https://pastebin.com/doc_api).
 */
public final class PastebinHelper {

    private static final String API_URL = "https://pastebin.com/api/api_post.php";
    private static final String K_PASTEBIN_KEY = "pastebin_api_key";
    private static final String K_PASTEBIN_USER = "pastebin_user_key";

    private PastebinHelper() {}

    public static String getApiKey(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(K_PASTEBIN_KEY, "");
    }

    public static void setApiKey(Context ctx, String key) {
        SharedPreferences prefs = ctx.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(K_PASTEBIN_KEY, key).apply();
    }

    public static String getUserKey(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(K_PASTEBIN_USER, "");
    }

    public static void setUserKey(Context ctx, String key) {
        SharedPreferences prefs = ctx.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(K_PASTEBIN_USER, key).apply();
    }

    /**
     * Creates a paste on Pastebin. Must be called from a background thread.
     *
     * @return The URL of the created paste, or null on error.
     */
    public static String createPaste(Context ctx, String code, String title, String format, String privacy) throws IOException {
        String apiKey = getApiKey(ctx);
        if (apiKey.isEmpty()) {
            throw new IOException("Pastebin API key not set. Set it in Settings → Pastebin API Key.");
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("api_dev_key", apiKey);
        params.put("api_option", "paste");
        params.put("api_paste_code", code);
        params.put("api_paste_name", title != null ? title : "WireDroid paste");
        params.put("api_paste_format", format != null ? format : "java");
        params.put("api_paste_private", privacy != null ? privacy : "0"); // 0=public, 1=unlisted, 2=private
        params.put("api_paste_expire_date", "N"); // never expire

        // If user key is set, authenticate
        String userKey = getUserKey(ctx);
        if (!userKey.isEmpty()) {
            params.put("api_user_key", userKey);
        }

        return postApi(params);
    }

    private static String postApi(Map<String, String> params) throws IOException {
        StringBuilder postData = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (postData.length() > 0) postData.append('&');
            postData.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            postData.append('=');
            postData.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }

        byte[] body = postData.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            int responseCode = conn.getResponseCode();
            InputStream is = (responseCode >= 200 && responseCode < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            if (is == null) {
                throw new IOException("HTTP " + responseCode);
            }

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = is.read(tmp)) != -1) {
                buf.write(tmp, 0, n);
            }
            String response = buf.toString("UTF-8").trim();

            if (responseCode != 200) {
                throw new IOException("Pastebin error: " + response);
            }

            // Pastebin returns the URL on success
            if (response.startsWith("https://") || response.startsWith("http://")) {
                return response;
            }

            throw new IOException("Unexpected response: " + response);
        } finally {
            conn.disconnect();
        }
    }
}
