package com.ccs.wiredroid.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import java.io.File;

public class HtmlViewerActivity extends AppCompatActivity {
    private static final String EXTRA_FILE_PATH = "file_path";

    public static void launch(Context context, File htmlFile) {
        Intent i = new Intent(context, HtmlViewerActivity.class);
        i.putExtra(EXTRA_FILE_PATH, htmlFile.getAbsolutePath());
        context.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        
        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle("HTML Preview");
        toolbar.setBackgroundColor(0xFF222222);
        toolbar.setTitleTextColor(0xFFFFFFFF);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        
        root.addView(toolbar, new android.widget.LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        String path = getIntent().getStringExtra(EXTRA_FILE_PATH);

        // The process's first WebView drags in the whole Chromium provider. That
        // cost is unavoidable, but it belongs after the shell has drawn rather
        // than in front of it, so the screen appears instead of hanging.
        root.post(() -> {
            if (isFinishing() || isDestroyed()) return;

            WebView webView = new WebView(this);
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(true);
            webView.setWebViewClient(new WebViewClient());

            root.addView(webView, new android.widget.LinearLayout.LayoutParams(-1, -1));

            if (path != null) {
                File f = new File(path);
                if (f.exists()) {
                    toolbar.setSubtitle(f.getName());
                    webView.loadUrl("file://" + f.getAbsolutePath());
                }
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
