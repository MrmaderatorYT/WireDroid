package com.ccs.wiredroid.ui;
import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;
import com.ccs.wiredroid.util.JsonXmlFormatter;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Built-in HTTP Client (Postman-like).
 * Parses .http files and sends requests via OkHttp.
 * Displays formatted JSON/XML response.
 */
public class HttpApiClientActivity extends AppCompatActivity {

    private static final String EXTRA_FILE_PATH = "file_path";

    private AppPreferences prefs;
    private AppTheme theme;
    /** Resolved once per screen — every field and button on the form wants it. */
    private Typeface mono;

    private EditText methodSpinner;
    private EditText urlInput;
    private EditText headersInput;
    private EditText bodyInput;
    private TextView responseStatus;
    private TextView responseHeaders;
    private LinearLayout responseBody;
    private TextView timingText;

    /** Which protocol the Send button speaks. */
    private enum Mode { REST, GRAPHQL, WEBSOCKET }

    private Mode mode = Mode.REST;
    private TextView modeRest, modeGraphQl, modeWs;
    private TextView bodyLabel;
    private TextView variablesLabel;
    private EditText variablesInput;
    private LinearLayout wsRow;
    private EditText wsMessage;
    private TextView sendBtn;
    private WebSocket socket;

    // Long-lived because a socket that idles for a minute must not be closed
    // out from under the user; the REST calls share it harmlessly. Built on
    // first use so that merely opening the screen does not pay for the OkHttp
    // class graph and the default TLS setup.
    private OkHttpClient client;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    public static void launch(Context context, File httpFile) {
        Intent i = new Intent(context, HttpApiClientActivity.class);
        i.putExtra(EXTRA_FILE_PATH, httpFile.getAbsolutePath());
        context.startActivity(i);
    }

    public static void launch(Context context) {
        Intent i = new Intent(context, HttpApiClientActivity.class);
        context.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        mono = prefs.resolveTypeface();
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        try {
            setContentView(buildRoot());
        } catch (Exception e) {
            android.util.Log.e("HTTP", "buildRoot failed", e);
            LinearLayout fallback = new LinearLayout(this);
            fallback.setOrientation(LinearLayout.VERTICAL);
            fallback.setBackgroundColor(0xFFFFFFFF);
            TextView errTv = new TextView(this);
            errTv.setText("Error: " + e.getMessage());
            errTv.setTextColor(0xFFFF0000);
            errTv.setPadding(16, 16, 16, 16);
            fallback.addView(errTv);
            setContentView(fallback);
            return;
        }
        FullScreenHelper.enable(this);

        setMode(Mode.REST);

        String path = getIntent().getStringExtra(EXTRA_FILE_PATH);
        if (path != null) {
            loadHttpFile(new File(path));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // A socket left open would keep delivering frames to a dead view.
        WebSocket ws = socket;
        socket = null;
        if (ws != null) ws.cancel();
        io.shutdownNow();
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle("HTTP Client");
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(8));

        // Protocol switch
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, 0, 0, dp(8));
        modeRest = createBtn("REST", theme.text);
        modeGraphQl = createBtn("GraphQL", theme.text);
        modeWs = createBtn("WebSocket", theme.text);
        modeRest.setOnClickListener(v -> setMode(Mode.REST));
        modeGraphQl.setOnClickListener(v -> setMode(Mode.GRAPHQL));
        modeWs.setOnClickListener(v -> setMode(Mode.WEBSOCKET));
        modeRow.addView(modeRest);
        modeRow.addView(modeGraphQl);
        modeRow.addView(modeWs);
        content.addView(modeRow);

        // Method + URL row
        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);

        methodSpinner = new EditText(this);
        methodSpinner.setText("GET");
        methodSpinner.setTextColor(theme.successText);
        methodSpinner.setTextSize(13);
        methodSpinner.setTypeface(mono, Typeface.BOLD);
        methodSpinner.setBackgroundColor(theme.consoleBg);
        methodSpinner.setPadding(dp(8), dp(8), dp(8), dp(8));
        methodSpinner.setSingleLine(true);
        methodSpinner.setSelectAllOnFocus(true);
        methodSpinner.setContentDescription(getString(R.string.a11y_http_method));
        LinearLayout.LayoutParams methodLp = new LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT);
        methodSpinner.setLayoutParams(methodLp);
        urlRow.addView(methodSpinner);

        urlInput = new EditText(this);
        urlInput.setHint("https://api.example.com/endpoint");
        urlInput.setHintTextColor(theme.textDim);
        urlInput.setTextColor(theme.text);
        urlInput.setTextSize(12);
        urlInput.setTypeface(mono);
        urlInput.setBackgroundColor(theme.consoleBg);
        urlInput.setPadding(dp(8), dp(8), dp(8), dp(8));
        urlInput.setSingleLine(true);
        urlInput.setContentDescription(getString(R.string.a11y_http_url));
        urlInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        urlRow.addView(urlInput);

        sendBtn = createBtn("▶ Send", theme.successText);
        sendBtn.setContentDescription(getString(R.string.a11y_http_send));
        sendBtn.setOnClickListener(v -> sendRequest());
        urlRow.addView(sendBtn);

        content.addView(urlRow);

        // Headers
        TextView headersLabel = createLabel("Headers (Key: Value per line)");
        content.addView(headersLabel);

        headersInput = new EditText(this);
        headersInput.setHint("Content-Type: application/json\nAccept: */*");
        headersInput.setHintTextColor(theme.textDim);
        headersInput.setTextColor(theme.text);
        headersInput.setTextSize(11);
        headersInput.setTypeface(mono);
        headersInput.setBackgroundColor(theme.consoleBg);
        headersInput.setPadding(dp(8), dp(6), dp(8), dp(6));
        headersInput.setMinLines(2);
        headersInput.setMaxLines(4);
        headersInput.setContentDescription(getString(R.string.a11y_http_headers));
        content.addView(headersInput);

        // Body
        bodyLabel = createLabel("Body (POST/PUT)");
        content.addView(bodyLabel);

        bodyInput = new EditText(this);
        bodyInput.setHint("{ \"key\": \"value\" }");
        bodyInput.setHintTextColor(theme.textDim);
        bodyInput.setTextColor(theme.text);
        bodyInput.setTextSize(11);
        bodyInput.setTypeface(mono);
        bodyInput.setBackgroundColor(theme.consoleBg);
        bodyInput.setPadding(dp(8), dp(6), dp(8), dp(6));
        bodyInput.setMinLines(3);
        bodyInput.setMaxLines(8);
        bodyInput.setContentDescription(getString(R.string.a11y_http_body));
        content.addView(bodyInput);

        variablesLabel = createLabel("Variables (JSON)");
        variablesLabel.setVisibility(View.GONE);
        content.addView(variablesLabel);

        variablesInput = new EditText(this);
        variablesInput.setHint("{ \"id\": 1 }");
        variablesInput.setHintTextColor(theme.textDim);
        variablesInput.setTextColor(theme.text);
        variablesInput.setTextSize(11);
        variablesInput.setTypeface(mono);
        variablesInput.setBackgroundColor(theme.consoleBg);
        variablesInput.setPadding(dp(8), dp(6), dp(8), dp(6));
        variablesInput.setMinLines(2);
        variablesInput.setMaxLines(5);
        variablesInput.setVisibility(View.GONE);
        content.addView(variablesInput);

        wsRow = new LinearLayout(this);
        wsRow.setOrientation(LinearLayout.HORIZONTAL);
        wsRow.setPadding(0, dp(6), 0, 0);
        wsRow.setVisibility(View.GONE);

        wsMessage = new EditText(this);
        wsMessage.setHint("Message to send");
        wsMessage.setHintTextColor(theme.textDim);
        wsMessage.setTextColor(theme.text);
        wsMessage.setTextSize(11);
        wsMessage.setTypeface(mono);
        wsMessage.setBackgroundColor(theme.consoleBg);
        wsMessage.setPadding(dp(8), dp(6), dp(8), dp(6));
        wsMessage.setSingleLine(true);
        wsMessage.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        wsRow.addView(wsMessage);

        TextView wsSend = createBtn("Send ▸", theme.accent);
        wsSend.setOnClickListener(v -> sendSocketMessage());
        wsRow.addView(wsSend);
        content.addView(wsRow);

        // Response section
        View divider = new View(this);
        divider.setBackgroundColor(theme.separator);
        content.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // Response status + timing
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setPadding(0, dp(8), 0, dp(4));

        responseStatus = new TextView(this);
        responseStatus.setTextColor(theme.textDim);
        responseStatus.setTextSize(12);
        responseStatus.setTypeface(mono, Typeface.BOLD);
        statusRow.addView(responseStatus);

        timingText = new TextView(this);
        timingText.setTextColor(theme.textDim);
        timingText.setTextSize(11);
        timingText.setTypeface(mono);
        timingText.setPadding(dp(12), 0, 0, 0);
        statusRow.addView(timingText);

        content.addView(statusRow);

        // Response headers
        responseHeaders = new TextView(this);
        responseHeaders.setTextColor(theme.textDim);
        responseHeaders.setTextSize(10);
        responseHeaders.setTypeface(mono);
        responseHeaders.setPadding(0, dp(2), 0, dp(6));
        content.addView(responseHeaders);

        // Response body
        responseBody = new LinearLayout(this);
        responseBody.setOrientation(LinearLayout.VERTICAL);
        responseBody.setBackgroundColor(theme.consoleBg);
        responseBody.setPadding(dp(8), dp(8), dp(8), dp(8));
        content.addView(responseBody, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.addView(content);

        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    // ══════════════════════════════════════════════════════════
    //  .http file parser
    // ══════════════════════════════════════════════════════════

    /** Reads and parses off the UI thread; the form fills in once the answer lands. */
    private void loadHttpFile(File file) {
        io.execute(() -> {
            try {
                String content = new String(java.nio.file.Files.readAllBytes(file.toPath()));
                HttpFile parsed = parseHttpFile(content);
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    applyHttpFile(parsed, file.getName());
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, "Error loading .http file: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void applyHttpFile(HttpFile parsed, String fileName) {
        methodSpinner.setText(parsed.method);
        urlInput.setText(parsed.url);

        StringBuilder headers = new StringBuilder();
        for (Map.Entry<String, String> e : parsed.headers.entrySet()) {
            headers.append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }
        headersInput.setText(headers.toString().trim());

        bodyInput.setText(parsed.body != null ? parsed.body : "");
        bodyInput.setEnabled(!"GET".equalsIgnoreCase(parsed.method)
                && !"HEAD".equalsIgnoreCase(parsed.method));

        Toast.makeText(this, "Loaded: " + fileName, Toast.LENGTH_SHORT).show();
    }

    private static class HttpFile {
        String method = "GET";
        String url = "";
        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        String body = null;
    }

    /**
     * Parse IntelliJ .http file format:
     * <pre>
     * ### comment
     * GET https://api.example.com/users
     * Content-Type: application/json
     *
     * { "name": "John" }
     * </pre>
     */
    private HttpFile parseHttpFile(String content) {
        HttpFile result = new HttpFile();
        String[] lines = content.split("\n");

        int state = 0; // 0=method, 1=headers, 2=body
        StringBuilder bodyBuilder = new StringBuilder();

        for (String rawLine : lines) {
            String line = rawLine.trim();

            // Skip comments and separators
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("###")) {
                if (state == 1) state = 2; // empty line after headers = body starts
                continue;
            }

            if (state == 0) {
                // Parse "METHOD URL" line
                String[] parts = line.split("\\s+", 2);
                if (parts.length == 2) {
                    result.method = parts[0].toUpperCase(Locale.ROOT);
                    result.url = parts[1];
                    state = 1;
                }
            } else if (state == 1) {
                // Parse header "Key: Value"
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String key = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    result.headers.put(key, value);
                } else {
                    // Not a header — might be body without blank line
                    state = 2;
                    bodyBuilder.append(line).append("\n");
                }
            } else {
                bodyBuilder.append(line).append("\n");
            }
        }

        if (bodyBuilder.length() > 0) {
            result.body = bodyBuilder.toString().trim();
        }
        return result;
    }

    // ══════════════════════════════════════════════════════════
    //  HTTP execution
    // ══════════════════════════════════════════════════════════

    /** Reshapes the form for the chosen protocol and relabels the send button. */
    private void setMode(Mode next) {
        if (socket != null && next != Mode.WEBSOCKET) closeSocket("switching protocol");
        mode = next;

        int on = theme.accent;
        int off = theme.textDim;
        modeRest.setTextColor(next == Mode.REST ? on : off);
        modeGraphQl.setTextColor(next == Mode.GRAPHQL ? on : off);
        modeWs.setTextColor(next == Mode.WEBSOCKET ? on : off);

        boolean graphql = next == Mode.GRAPHQL;
        boolean ws = next == Mode.WEBSOCKET;

        methodSpinner.setVisibility(next == Mode.REST ? View.VISIBLE : View.GONE);
        variablesLabel.setVisibility(graphql ? View.VISIBLE : View.GONE);
        variablesInput.setVisibility(graphql ? View.VISIBLE : View.GONE);
        wsRow.setVisibility(ws ? View.VISIBLE : View.GONE);
        bodyInput.setVisibility(ws ? View.GONE : View.VISIBLE);
        bodyLabel.setVisibility(ws ? View.GONE : View.VISIBLE);

        if (graphql) {
            bodyLabel.setText("Query");
            bodyInput.setHint("query { user(id: 1) { name } }");
            urlInput.setHint("https://api.example.com/graphql");
        } else if (ws) {
            urlInput.setHint("wss://echo.websocket.org");
        } else {
            bodyLabel.setText("Body (POST/PUT)");
            bodyInput.setHint("{ \"key\": \"value\" }");
            urlInput.setHint("https://api.example.com/endpoint");
        }
        updateSendLabel();
    }

    private void updateSendLabel() {
        if (mode == Mode.WEBSOCKET) {
            sendBtn.setText(socket == null ? "▶ Connect" : "■ Disconnect");
            sendBtn.setTextColor(socket == null ? theme.successText : theme.errorText);
        } else {
            sendBtn.setText("▶ Send");
            sendBtn.setTextColor(theme.successText);
        }
    }

    /** The URL as typed, with a scheme filled in to match the protocol. */
    private String resolvedUrl() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) return "";
        boolean ws = mode == Mode.WEBSOCKET;
        boolean hasScheme = url.startsWith("http://") || url.startsWith("https://")
                || url.startsWith("ws://") || url.startsWith("wss://");
        if (!hasScheme) {
            url = (ws ? "wss://" : "https://") + url;
            urlInput.setText(url);
        }
        return url;
    }

    /** Only ever reached from the UI thread, so no locking is needed. */
    private OkHttpClient client() {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
        }
        return client;
    }

    private void sendRequest() {
        if (mode == Mode.WEBSOCKET) {
            if (socket == null) openSocket(); else closeSocket("closed by user");
            return;
        }
        if (mode == Mode.GRAPHQL) {
            sendGraphQl();
            return;
        }
        String method = methodSpinner.getText().toString().trim().toUpperCase(Locale.ROOT);
        String url = resolvedUrl();
        if (url.isEmpty()) {
            Toast.makeText(this, "Enter a URL", Toast.LENGTH_SHORT).show();
            return;
        }

        // Build request
        Request.Builder rb = new Request.Builder().url(url);

        // Headers
        String headersText = headersInput.getText().toString().trim();
        if (!headersText.isEmpty()) {
            for (String line : headersText.split("\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    rb.addHeader(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                }
            }
        }

        // Body
        String bodyText = bodyInput.getText().toString().trim();
        if (!bodyText.isEmpty() && !method.equals("GET") && !method.equals("HEAD")) {
            MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
            rb.method(method, RequestBody.create(bodyText, mediaType));
        } else {
            rb.method(method, null);
        }

        // UI: show loading
        responseBody.removeAllViews();
        responseStatus.setText("Sending...");
        responseStatus.setTextColor(theme.textDim);
        responseHeaders.setText("");
        timingText.setText("");

        long startTime = System.currentTimeMillis();

        client().newCall(rb.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                ui.post(() -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    responseStatus.setText("FAILED");
                    responseStatus.setTextColor(theme.errorText);
                    timingText.setText(elapsed + " ms");
                    responseBody.removeAllViews();
                    addResponseBody("Error: " + e.getMessage(), theme.errorText);
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                long elapsed = System.currentTimeMillis() - startTime;
                int code = response.code();
                String body = response.body() != null ? response.body().string() : "";
                Headers respHeaders = response.headers();

                ui.post(() -> {
                    // Status
                    String statusText = code + " " + response.message();
                    responseStatus.setText(statusText);
                    responseStatus.setTextColor(code >= 200 && code < 300
                            ? theme.successText : theme.errorText);
                    timingText.setText(body.length() / 1024 + " KB — " + elapsed + " ms");

                    // Headers
                    StringBuilder hb = new StringBuilder();
                    for (int i = 0; i < respHeaders.size(); i++) {
                        hb.append(respHeaders.name(i)).append(": ").append(respHeaders.value(i));
                        if (i < respHeaders.size() - 1) hb.append("\n");
                    }
                    responseHeaders.setText(hb.toString());

                    // Body
                    responseBody.removeAllViews();
                    renderResponseBody(body);
                });
            }
        });
    }

    // ══════════════════════════════════════════════════════════
    //  Response rendering
    // ══════════════════════════════════════════════════════════

    // ── GraphQL ─────────────────────────────────────────────────────────────

    /**
     * Wraps the query in the transport envelope GraphQL-over-HTTP expects.
     *
     * <p>The whole protocol is one POST of {@code {"query": …, "variables": …}},
     * so this reuses the REST plumbing rather than adding a client. What it
     * really buys the user is not having to hand-escape a multi-line query into
     * a JSON string every time.</p>
     */
    private void sendGraphQl() {
        String url = resolvedUrl();
        if (url.isEmpty()) {
            Toast.makeText(this, "Enter a URL", Toast.LENGTH_SHORT).show();
            return;
        }
        String query = bodyInput.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "Enter a query", Toast.LENGTH_SHORT).show();
            return;
        }
        String variables = variablesInput.getText().toString().trim();

        StringBuilder payload = new StringBuilder("{\"query\":")
                .append(jsonString(query));
        if (!variables.isEmpty()) {
            // Passed through as written: it is already JSON, and re-encoding it
            // as a string would make the server see text where it wants an object.
            payload.append(",\"variables\":").append(variables);
        }
        payload.append('}');

        Request.Builder rb = new Request.Builder().url(url)
                .post(RequestBody.create(payload.toString(),
                        MediaType.parse("application/json; charset=utf-8")));
        rb.addHeader("Accept", "application/json");
        applyHeaders(rb);
        dispatch(rb.build());
    }

    /** Minimal JSON string escaping — enough for a query typed into a box. */
    private static String jsonString(String raw) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private void applyHeaders(Request.Builder rb) {
        String headersText = headersInput.getText().toString().trim();
        if (headersText.isEmpty()) return;
        for (String line : headersText.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                rb.addHeader(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
    }

    /** Sends a prepared request through the same response rendering as REST. */
    private void dispatch(Request request) {
        responseBody.removeAllViews();
        responseStatus.setText("Sending...");
        responseStatus.setTextColor(theme.textDim);
        responseHeaders.setText("");
        timingText.setText("");
        long startTime = System.currentTimeMillis();

        client().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                ui.post(() -> {
                    responseStatus.setText("FAILED");
                    responseStatus.setTextColor(theme.errorText);
                    timingText.setText((System.currentTimeMillis() - startTime) + " ms");
                    responseBody.removeAllViews();
                    addResponseBody("Error: " + e.getMessage(), theme.errorText);
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                int code = response.code();
                Headers headers = response.headers();
                ui.post(() -> {
                    responseStatus.setText("HTTP " + code);
                    responseStatus.setTextColor(code < 400 ? theme.successText : theme.errorText);
                    timingText.setText((System.currentTimeMillis() - startTime) + " ms");
                    StringBuilder hs = new StringBuilder();
                    for (String name : headers.names()) {
                        hs.append(name).append(": ").append(headers.get(name)).append('\n');
                    }
                    responseHeaders.setText(hs.toString().trim());
                    renderResponseBody(body);
                });
            }
        });
    }

    // ── WebSocket ───────────────────────────────────────────────────────────

    private void openSocket() {
        String url = resolvedUrl();
        if (url.isEmpty()) {
            Toast.makeText(this, "Enter a URL", Toast.LENGTH_SHORT).show();
            return;
        }
        Request.Builder rb = new Request.Builder().url(url);
        applyHeaders(rb);

        responseBody.removeAllViews();
        responseHeaders.setText("");
        timingText.setText("");
        responseStatus.setText("Connecting…");
        responseStatus.setTextColor(theme.textDim);
        long startTime = System.currentTimeMillis();

        socket = client().newWebSocket(rb.build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                ui.post(() -> {
                    responseStatus.setText("CONNECTED");
                    responseStatus.setTextColor(theme.successText);
                    timingText.setText((System.currentTimeMillis() - startTime) + " ms");
                    logFrame("▲ open", theme.textDim);
                    updateSendLabel();
                });
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                ui.post(() -> logFrame("◀ " + text, theme.text));
            }

            @Override
            public void onMessage(WebSocket ws, okio.ByteString bytes) {
                ui.post(() -> logFrame("◀ [" + bytes.size() + " bytes]", theme.textDim));
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
                ui.post(() -> onSocketGone("CLOSED " + code
                        + (reason == null || reason.isEmpty() ? "" : " " + reason)));
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                ui.post(() -> {
                    logFrame("✕ " + t.getMessage(), theme.errorText);
                    onSocketGone("FAILED");
                });
            }
        });
        updateSendLabel();
    }

    private void sendSocketMessage() {
        if (socket == null) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = wsMessage.getText().toString();
        if (text.isEmpty()) return;
        if (socket.send(text)) {
            logFrame("▶ " + text, theme.accent);
            wsMessage.setText("");
        } else {
            logFrame("✕ could not queue (socket closing)", theme.errorText);
        }
    }

    private void closeSocket(String reason) {
        WebSocket ws = socket;
        socket = null;
        if (ws != null) ws.close(1000, reason);
        onSocketGone("CLOSED");
    }

    private void onSocketGone(String status) {
        socket = null;
        responseStatus.setText(status);
        responseStatus.setTextColor(theme.textDim);
        updateSendLabel();
    }

    /** One line in the frame log, newest at the bottom. */
    private void logFrame(String text, int color) {
        addResponseBody(text, color);
    }

    private void renderResponseBody(String body) {
        if (body.isEmpty()) {
            addResponseBody("(empty response)", theme.textDim);
            return;
        }

        // Try JSON — get pre-highlighted SpannableStringBuilder
        if (body.trim().startsWith("{") || body.trim().startsWith("[")) {
            SpannableStringBuilder formatted = JsonXmlFormatter.formatJson(body);
            TextView tv = new TextView(this);
            tv.setText(formatted);
            tv.setTextColor(theme.consoleText);
            tv.setTextSize(11);
            tv.setTypeface(new AppPreferences(this).resolveTypeface());
            tv.setPadding(0, 0, 0, dp(4));
            responseBody.addView(tv);
            return;
        }

        // Try XML
        if (body.trim().startsWith("<")) {
            SpannableStringBuilder formatted = JsonXmlFormatter.formatXml(body);
            TextView tv = new TextView(this);
            tv.setText(formatted);
            tv.setTextColor(theme.consoleText);
            tv.setTextSize(11);
            tv.setTypeface(new AppPreferences(this).resolveTypeface());
            tv.setPadding(0, 0, 0, dp(4));
            responseBody.addView(tv);
            return;
        }

        // Plain text
        addResponseBody(body, theme.consoleText);
    }

    private void addResponseBody(String text, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(11);
        tv.setTypeface(new AppPreferences(this).resolveTypeface());
        tv.setPadding(0, 0, 0, dp(4));
        responseBody.addView(tv);
    }

    // ══════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════

    private TextView createLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(theme.textDim);
        label.setTextSize(11);
        label.setPadding(0, dp(8), 0, dp(2));
        return label;
    }

    private TextView createBtn(String text, int color) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(color);
        btn.setTextSize(13);
        btn.setTypeface(mono, Typeface.BOLD);
        btn.setPadding(dp(12), dp(8), dp(12), dp(8));
        btn.setBackgroundResource(android.R.drawable.list_selector_background);
        btn.setGravity(Gravity.CENTER);
        btn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) btn.getLayoutParams();
        lp.setMarginStart(dp(6));
        return btn;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
