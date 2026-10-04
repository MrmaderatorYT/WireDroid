package com.ccs.wiredroid.ui;
import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;
import com.ccs.wiredroid.project.ProjectManager;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebView preview for HTML/CSS/JS files.
 * Supports:
 * - Opening .html files from project
 * - Live reload on save
 * - JavaScript console output display
 * - Custom URL bar for testing
 */
public class WebViewPreviewActivity extends AppCompatActivity {

    private static final String EXTRA_FILE_PATH = "file_path";
    private static final String EXTRA_HTML_CONTENT = "html_content";
    private static final String EXTRA_URL = "url";

    private AppPreferences prefs;
    private AppTheme theme;

    private WebView webView;
    private EditText urlBar;
    private TextView consoleOutput;
    private TextView statusText;
    private File currentFile;
    private boolean liveReload = true;
    /** onCreate already loaded the file; live reload only applies to later returns. */
    private boolean fileLoadedInOnCreate;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    public static void launch(Context context, File htmlFile) {
        Intent i = new Intent(context, WebViewPreviewActivity.class);
        i.putExtra(EXTRA_FILE_PATH, htmlFile.getAbsolutePath());
        context.startActivity(i);
    }

    public static void launch(Context context, String htmlContent) {
        Intent i = new Intent(context, WebViewPreviewActivity.class);
        i.putExtra(EXTRA_HTML_CONTENT, htmlContent);
        context.startActivity(i);
    }

    public static void launchUrl(Context context, String url) {
        Intent i = new Intent(context, WebViewPreviewActivity.class);
        i.putExtra(EXTRA_URL, url);
        context.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        FullScreenHelper.enable(this);

        String path = getIntent().getStringExtra(EXTRA_FILE_PATH);
        String content = getIntent().getStringExtra(EXTRA_HTML_CONTENT);
        String url = getIntent().getStringExtra(EXTRA_URL);

        if (path != null) {
            currentFile = new File(path);
            urlBar.setText(path);
            fileLoadedInOnCreate = true;
            loadHtmlFile(currentFile);
        } else if (content != null) {
            loadHtmlContent(content);
        } else if (url != null) {
            urlBar.setText(url);
            webView.loadUrl(url);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Auto-reload on resume if live reload is enabled
        if (fileLoadedInOnCreate) {
            fileLoadedInOnCreate = false;
        } else if (liveReload && currentFile != null) {
            loadHtmlFile(currentFile);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) webView.destroy();
        io.shutdownNow();
    }

    private View buildRoot() {
        Typeface font = prefs.resolveTypeface();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle("WebView Preview");
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // URL bar
        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        urlRow.setBackgroundColor(theme.consoleBg);
        urlRow.setPadding(dp(6), dp(4), dp(6), dp(4));

        urlBar = new EditText(this);
        urlBar.setHint("File path or URL");
        urlBar.setHintTextColor(theme.textDim);
        urlBar.setTextColor(theme.text);
        urlBar.setTextSize(12);
        urlBar.setTypeface(font);
        urlBar.setBackgroundColor(Color.TRANSPARENT);
        urlBar.setSingleLine(true);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setContentDescription(getString(R.string.a11y_webview_url));
        urlBar.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        urlRow.addView(urlBar);

        TextView goBtn = createBtn("Go", theme.accent, font);
        goBtn.setContentDescription(getString(R.string.a11y_webview_go));
        goBtn.setOnClickListener(v -> navigateToUrl());
        urlRow.addView(goBtn);

        TextView reloadBtn = createBtn("↻", theme.accent, font);
        reloadBtn.setContentDescription(getString(R.string.a11y_webview_reload));
        reloadBtn.setOnClickListener(v -> {
            if (currentFile != null) {
                loadHtmlFile(currentFile);
            } else {
                webView.reload();
            }
        });
        urlRow.addView(reloadBtn);

        TextView liveBtn = createBtn("Live", liveReload ? theme.successText : theme.textDim, font);
        liveBtn.setContentDescription(getString(R.string.a11y_webview_live));
        liveBtn.setOnClickListener(v -> {
            liveReload = !liveReload;
            liveBtn.setTextColor(liveReload ? theme.successText : theme.textDim);
        });
        urlRow.addView(liveBtn);

        root.addView(urlRow);

        // WebView
        webView = new WebView(this);
        setupWebView();
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Console output
        TextView consoleLabel = new TextView(this);
        consoleLabel.setText("Console");
        consoleLabel.setTextColor(theme.textDim);
        consoleLabel.setTextSize(10);
        consoleLabel.setPadding(dp(8), dp(4), dp(8), 0);
        root.addView(consoleLabel);

        consoleOutput = new TextView(this);
        consoleOutput.setBackgroundColor(theme.consoleBg);
        consoleOutput.setTextColor(theme.successText);
        consoleOutput.setTextSize(10);
        consoleOutput.setTypeface(font);
        consoleOutput.setPadding(dp(8), dp(2), dp(8), dp(4));
        consoleOutput.setMaxLines(6);
        consoleOutput.setSingleLine(true);
        consoleOutput.setHorizontallyScrolling(true);
        root.addView(consoleOutput);

        // Status bar
        statusText = new TextView(this);
        statusText.setBackgroundColor(theme.consoleBg);
        statusText.setTextColor(theme.textDim);
        statusText.setTextSize(10);
        statusText.setPadding(dp(8), dp(4), dp(8), dp(4));
        root.addView(statusText);

        return root;
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                urlBar.setText(url);
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                statusText.setText("Loading: " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                statusText.setText("Ready — " + url);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage msg) {
                String level;
                switch (msg.messageLevel()) {
                    case ERROR:   level = "ERROR"; break;
                    case WARNING: level = "WARN"; break;
                    default:      level = "LOG"; break;
                }
                String line = level + ": " + msg.message()
                        + (msg.sourceId() != null ? " (" + msg.sourceId() + ":" + msg.lineNumber() + ")" : "");
                appendConsole(line);
                return true;
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    statusText.setText("Loading... " + newProgress + "%");
                }
            }
        });

        // Add JS interface for console forwarding
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void log(String message) {
                ui.post(() -> appendConsole("JS: " + message));
            }
        }, "AndroidConsole");
    }

    // ══════════════════════════════════════════════════════════
    //  File loading
    // ══════════════════════════════════════════════════════════

    private void loadHtmlFile(File file) {
        if (file == null || !file.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show();
            return;
        }

        statusText.setText("Loading: " + file.getName());
        currentFile = file;

        ui.post(() -> {
            String fileUrl = "file://" + file.getAbsolutePath();
            webView.loadUrl(fileUrl);
            statusText.setText("Ready — " + file.getName());
        });
    }

    private void loadHtmlContent(String content) {
        webView.loadDataWithBaseURL(null, content, "text/html", "UTF-8", null);
    }

    private void navigateToUrl() {
        String input = urlBar.getText().toString().trim();
        if (input.isEmpty()) return;

        // Check if it's a file path
        if (input.startsWith("/") || input.contains(".html") || input.contains(".htm")) {
            File file = new File(input);
            if (file.exists()) {
                currentFile = file;
                loadHtmlFile(file);
                return;
            }
            // Try relative to project
            String projectPath = null;
            try {
                projectPath = new ProjectManager(this).getProjectDir().getAbsolutePath();
            } catch (Exception ignored) {}
            if (projectPath != null) {
                file = new File(projectPath, input);
                if (file.exists()) {
                    currentFile = file;
                    loadHtmlFile(file);
                    return;
                }
            }
        }

        // Check if it's a URL
        if (!input.startsWith("http://") && !input.startsWith("https://")
                && !input.startsWith("file://") && !input.startsWith("data:")) {
            input = "https://" + input;
            urlBar.setText(input);
        }

        currentFile = null;
        webView.loadUrl(input);
    }

    // ══════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════

    private void appendConsole(String message) {
        String current = consoleOutput.getText().toString();
        if (current.length() > 500) {
            current = current.substring(current.length() - 400);
        }
        consoleOutput.setText(current + "\n" + message);
    }

    private TextView createBtn(String text, int color, Typeface font) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(color);
        btn.setTextSize(12);
        btn.setTypeface(font, Typeface.BOLD);
        btn.setPadding(dp(10), dp(6), dp(10), dp(6));
        btn.setBackgroundResource(android.R.drawable.list_selector_background);
        btn.setGravity(Gravity.CENTER);
        btn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) btn.getLayoutParams();
        lp.setMarginStart(dp(4));
        return btn;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
