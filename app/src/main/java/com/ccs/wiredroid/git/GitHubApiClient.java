package com.ccs.wiredroid.git;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class GitHubApiClient {

    /**
     * Создает новый репозиторий на GitHub.
     * @param name Имя репозитория
     * @param token GitHub Personal Access Token
     * @param isPrivate Приватный ли репозиторий
     * @return clone_url созданного репозитория
     * @throws Exception в случае ошибки HTTP или парсинга
     */
    public static String createRepo(String name, String token, boolean isPrivate) throws Exception {
        URL url = new URL("https://api.github.com/user/repos");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "token " + token);
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);

        JSONObject body = new JSONObject();
        body.put("name", name);
        body.put("private", isPrivate);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = body.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int code = conn.getResponseCode();
        if (code >= 200 && code < 300) {
            try (InputStream is = conn.getInputStream();
                 BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
                JSONObject json = new JSONObject(response.toString());
                return json.getString("clone_url");
            }
        } else {
            try (InputStream es = conn.getErrorStream();
                 BufferedReader br = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8))) {
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    errorResponse.append(line);
                }
                String message = "Unknown error";
                try {
                    JSONObject errJson = new JSONObject(errorResponse.toString());
                    message = errJson.optString("message", errorResponse.toString());
                } catch (Exception e) {
                    message = errorResponse.toString();
                }
                throw new Exception("GitHub API Error " + code + ": " + message);
            }
        }
    }
}
