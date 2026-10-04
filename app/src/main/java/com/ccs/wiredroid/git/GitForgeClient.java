package com.ccs.wiredroid.git;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Small GitHub/GitLab REST client used by the in-IDE Forge panel. */
public final class GitForgeClient {
    public enum Provider { GITHUB, GITLAB }

    public static final class Item {
        public final long id;
        public final int number;
        public final String title;
        public final String state;
        public final String author;
        public final String body;
        public final String url;
        public Item(long id, int number, String title, String state, String author,
                    String body, String url) {
            this.id = id; this.number = number; this.title = title; this.state = state;
            this.author = author; this.body = body; this.url = url;
        }
    }

    public static final class Pipeline {
        public final String name;
        public final String status;
        public final String branch;
        public final String url;
        Pipeline(String name, String status, String branch, String url) {
            this.name = name; this.status = status; this.branch = branch; this.url = url;
        }
    }

    private final Provider provider;
    private final String host;
    private final String owner;
    private final String repository;
    private final String token;

    public GitForgeClient(String remoteUrl, String token) {
        ParsedRemote parsed = parse(remoteUrl);
        provider = parsed.host.toLowerCase().contains("gitlab") ? Provider.GITLAB : Provider.GITHUB;
        host = parsed.host;
        owner = parsed.owner;
        repository = parsed.repository;
        this.token = token == null ? "" : token;
    }

    public Provider provider() { return provider; }

    public List<Item> pullRequests() throws Exception {
        if (provider == Provider.GITHUB) {
            JSONArray array = requestArray("GET", github("/pulls?state=all&per_page=50"), null);
            return githubItems(array);
        }
        JSONArray array = requestArray("GET", gitlab("/merge_requests?scope=all&per_page=50"), null);
        return gitlabItems(array);
    }

    public List<Item> issues() throws Exception {
        if (provider == Provider.GITHUB) {
            JSONArray raw = requestArray("GET", github("/issues?state=all&per_page=50"), null);
            JSONArray issues = new JSONArray();
            for (int i = 0; i < raw.length(); i++) {
                if (!raw.getJSONObject(i).has("pull_request")) issues.put(raw.getJSONObject(i));
            }
            return githubItems(issues);
        }
        return gitlabItems(requestArray("GET", gitlab("/issues?scope=all&per_page=50"), null));
    }

    public Item createPullRequest(String title, String body, String source, String target)
            throws Exception {
        JSONObject payload = new JSONObject().put("title", title);
        JSONObject result;
        if (provider == Provider.GITHUB) {
            payload.put("body", body).put("head", source).put("base", target);
            result = requestObject("POST", github("/pulls"), payload);
            return githubItem(result);
        }
        payload.put("description", body).put("source_branch", source).put("target_branch", target);
        result = requestObject("POST", gitlab("/merge_requests"), payload);
        return gitlabItem(result);
    }

    /** Adds a PR/MR discussion comment (the portable review primitive). */
    public void addReviewComment(int number, String body) throws Exception {
        JSONObject payload = new JSONObject().put("body", body);
        if (provider == Provider.GITHUB) {
            requestObject("POST", github("/issues/" + number + "/comments"), payload);
        } else {
            requestObject("POST", gitlab("/merge_requests/" + number + "/notes"), payload);
        }
    }

    public String pullRequestDiff(int number) throws Exception {
        if (provider == Provider.GITHUB) {
            return request("GET", github("/pulls/" + number), null,
                    "application/vnd.github.v3.diff");
        }
        JSONObject response = requestObject("GET", gitlab("/merge_requests/" + number + "/changes"), null);
        JSONArray changes = response.optJSONArray("changes");
        StringBuilder diff = new StringBuilder();
        if (changes != null) for (int i = 0; i < changes.length(); i++) {
            JSONObject change = changes.optJSONObject(i);
            diff.append("diff --git a/").append(change.optString("old_path"))
                    .append(" b/").append(change.optString("new_path")).append('\n')
                    .append(change.optString("diff")).append('\n');
        }
        return diff.toString();
    }

    /** GitHub inline review comment. GitLab falls back to an MR note with location context. */
    public void addInlineComment(int number, String body, String path, int line,
                                 String commitId) throws Exception {
        if (provider == Provider.GITHUB) {
            JSONObject payload = new JSONObject().put("body", body).put("path", path)
                    .put("line", line).put("side", "RIGHT").put("commit_id", commitId);
            requestObject("POST", github("/pulls/" + number + "/comments"), payload);
        } else {
            addReviewComment(number, path + ":" + line + "\n\n" + body);
        }
    }

    public List<Pipeline> pipelines() throws Exception {
        List<Pipeline> result = new ArrayList<>();
        if (provider == Provider.GITHUB) {
            JSONObject root = requestObject("GET", github("/actions/runs?per_page=30"), null);
            JSONArray runs = root.optJSONArray("workflow_runs");
            if (runs == null) return result;
            for (int i = 0; i < runs.length(); i++) {
                JSONObject run = runs.getJSONObject(i);
                String status = run.optString("conclusion", run.optString("status", "unknown"));
                result.add(new Pipeline(run.optString("name", "Actions"), status,
                        run.optString("head_branch"), run.optString("html_url")));
            }
        } else {
            JSONArray runs = requestArray("GET", gitlab("/pipelines?per_page=30"), null);
            for (int i = 0; i < runs.length(); i++) {
                JSONObject run = runs.getJSONObject(i);
                result.add(new Pipeline("Pipeline #" + run.optInt("id"),
                        run.optString("status"), run.optString("ref"), run.optString("web_url")));
            }
        }
        return result;
    }

    private String github(String suffix) {
        String apiHost = "github.com".equalsIgnoreCase(host) ? "api.github.com" : host + "/api/v3";
        return "https://" + apiHost + "/repos/" + encode(owner) + "/" + encode(repository) + suffix;
    }

    private String gitlab(String suffix) {
        return "https://" + host + "/api/v4/projects/" + encode(owner + "/" + repository) + suffix;
    }

    private JSONArray requestArray(String method, String url, JSONObject body) throws Exception {
        return new JSONArray(request(method, url, body));
    }

    private JSONObject requestObject(String method, String url, JSONObject body) throws Exception {
        return new JSONObject(request(method, url, body));
    }

    private String request(String method, String url, JSONObject body) throws Exception {
        return request(method, url, body, provider == Provider.GITHUB
                ? "application/vnd.github+json" : "application/json");
    }

    private String request(String method, String url, JSONObject body, String accept) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new java.net.URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (!token.isEmpty()) connection.setRequestProperty(
                provider == Provider.GITHUB ? "Authorization" : "PRIVATE-TOKEN",
                provider == Provider.GITHUB ? "Bearer " + token : token);
        if (body != null) {
            connection.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = read(stream);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            String message = response;
            try { message = new JSONObject(response).optString("message", response); }
            catch (Exception ignored) {}
            throw new IllegalStateException(provider + " API " + code + ": " + message);
        }
        return response.isEmpty() ? "{}" : response;
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream,
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static List<Item> githubItems(JSONArray array) {
        List<Item> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(githubItem(array.optJSONObject(i)));
        return result;
    }

    private static Item githubItem(JSONObject item) {
        JSONObject user = item.optJSONObject("user");
        return new Item(item.optLong("id"), item.optInt("number"), item.optString("title"),
                item.optString("state"), user == null ? "" : user.optString("login"),
                item.optString("body"), item.optString("html_url"));
    }

    private static List<Item> gitlabItems(JSONArray array) {
        List<Item> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(gitlabItem(array.optJSONObject(i)));
        return result;
    }

    private static Item gitlabItem(JSONObject item) {
        JSONObject user = item.optJSONObject("author");
        return new Item(item.optLong("id"), item.optInt("iid"), item.optString("title"),
                item.optString("state"), user == null ? "" : user.optString("username"),
                item.optString("description"), item.optString("web_url"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static final class ParsedRemote {
        final String host, owner, repository;
        ParsedRemote(String host, String owner, String repository) {
            this.host = host; this.owner = owner; this.repository = repository;
        }
    }

    private static ParsedRemote parse(String remote) {
        if (remote == null || remote.trim().isEmpty()) throw new IllegalArgumentException("No origin remote");
        String value = remote.trim();
        try {
            String host;
            String path;
            if (value.matches("^[^/]+@[^:]+:.*")) {
                int at = value.indexOf('@');
                int colon = value.indexOf(':', at);
                host = value.substring(at + 1, colon);
                path = value.substring(colon + 1);
            } else {
                URI uri = URI.create(value);
                host = uri.getHost();
                path = uri.getPath();
            }
            if (host == null || path == null) throw new IllegalArgumentException("Unsupported origin: " + remote);
            path = path.replaceFirst("^/", "").replaceFirst("\\.git$", "");
            int slash = path.lastIndexOf('/');
            if (slash <= 0) throw new IllegalArgumentException("Origin must contain owner/repository");
            return new ParsedRemote(host, path.substring(0, slash), path.substring(slash + 1));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Cannot parse origin: " + remote, error);
        }
    }
}
