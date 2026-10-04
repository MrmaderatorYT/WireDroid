package com.ccs.wiredroid.git;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * GitLab API client: create repos, get clone URLs.
 * Works with both gitlab.com and self-hosted GitLab instances.
 */
public class GitLabApiClient {

    private final String baseUrl;
    private final String token;

    /**
     * @param baseUrl GitLab instance URL (e.g. "https://gitlab.com" or "https://git.company.com")
     * @param token   Personal Access Token
     */
    public GitLabApiClient(String baseUrl, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
    }

    /**
     * Creates a new project on GitLab.
     * @param name      Project name
     * @param isPrivate Private or public
     * @return clone_url of the created project
     */
    public String createProject(String name, boolean isPrivate) throws Exception {
        URL url = new URL(baseUrl + "/api/v4/projects");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("PRIVATE-TOKEN", token);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);

        JSONObject body = new JSONObject();
        body.put("name", name);
        body.put("visibility", isPrivate ? "private" : "public");

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
                return json.getString("http_url_to_repo");
            }
        } else {
            String message = readErrorResponse(conn);
            throw new Exception("GitLab API Error " + code + ": " + message);
        }
    }

    /**
     * Lists user's projects (for autocomplete, etc).
     * @param search Search query
     * @return JSON array of projects
     */
    public String listProjects(String search) throws Exception {
        String urlStr = baseUrl + "/api/v4/projects?membership=true&per_page=20";
        if (search != null && !search.isEmpty()) {
            urlStr += "&search=" + java.net.URLEncoder.encode(search, "UTF-8");
        }

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("PRIVATE-TOKEN", token);

        int code = conn.getResponseCode();
        if (code >= 200 && code < 300) {
            try (InputStream is = conn.getInputStream();
                 BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
                return response.toString();
            }
        } else {
            String message = readErrorResponse(conn);
            throw new Exception("GitLab API Error " + code + ": " + message);
        }
    }

    private String readErrorResponse(HttpURLConnection conn) {
        try (InputStream es = conn.getErrorStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8))) {
            StringBuilder errorResponse = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                errorResponse.append(line);
            }
            String errorStr = errorResponse.toString();
            try {
                JSONObject errJson = new JSONObject(errorStr);
                return errJson.optString("message", errorStr);
            } catch (Exception e) {
                return errorStr;
            }
        } catch (Exception e) {
            return "Unknown error";
        }
    }
}
