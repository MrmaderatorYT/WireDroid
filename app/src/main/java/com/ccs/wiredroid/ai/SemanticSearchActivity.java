package com.ccs.wiredroid.ai;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.ui.MainActivity;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds code by what it does rather than by what it spells.
 *
 * <p>"Where do we write to the database?" cannot be grepped: the answer may
 * contain neither word. The project is reduced to a digest of names and imports
 * — see {@link CodeDigest} — and the model is asked which of those files
 * answer the question, with a reason for each. It only ever picks from the list
 * it was given, so a hit is always a file that exists.</p>
 */
public class SemanticSearchActivity extends AppCompatActivity {

    private static final String EXTRA_ROOT = "project_root";

    public static void launch(Context context, File projectDir) {
        Intent i = new Intent(context, SemanticSearchActivity.class);
        if (projectDir != null) i.putExtra(EXTRA_ROOT, projectDir.getAbsolutePath());
        context.startActivity(i);
    }

    private AppTheme theme;
    private File root;
    private EditText question;
    private TextView status;
    private LinearLayout results;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppPreferences prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);
        super.onCreate(savedInstanceState);

        String path = getIntent().getStringExtra(EXTRA_ROOT);
        root = path == null ? null : new File(path);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(theme.bg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(R.string.semantic_title);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        page.addView(toolbar);

        question = new EditText(this);
        question.setHint(R.string.semantic_hint);
        question.setHintTextColor(theme.textDim);
        question.setTextColor(theme.text);
        question.setBackgroundColor(theme.consoleBg);
        question.setPadding(dp(12), dp(10), dp(12), dp(10));
        question.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        question.setSingleLine(true);
        question.setOnEditorActionListener((v, actionId, event) -> {
            ask();
            return true;
        });
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qlp.setMargins(dp(12), dp(12), dp(12), dp(4));
        page.addView(question, qlp);

        status = new TextView(this);
        status.setTextColor(theme.textDim);
        status.setTextSize(12f);
        status.setPadding(dp(14), dp(4), dp(14), dp(8));
        status.setText(R.string.semantic_ready);
        page.addView(status);

        ScrollView scroll = new ScrollView(this);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(dp(8), 0, dp(8), dp(16));
        scroll.addView(results);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(page);
        FullScreenHelper.enable(this);

        // The key is Keystore-encrypted, so answering this costs a binder round
        // trip and a decrypt — not something the first frame should wait on.
        // Until it comes back the status line keeps its neutral text.
        new Thread(() -> {
            if (GeminiService.hasApiKey(this)) return;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                status.setText(R.string.semantic_no_key);
            });
        }).start();
    }

    private void ask() {
        String q = question.getText().toString().trim();
        if (q.isEmpty()) return;
        if (root == null) {
            Toast.makeText(this, R.string.semantic_no_project, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!GeminiService.hasApiKey(this)) {
            status.setText(R.string.semantic_no_key);
            return;
        }

        results.removeAllViews();
        status.setText(R.string.semantic_reading);

        new Thread(() -> {
            List<CodeDigest.Entry> digest = CodeDigest.of(root);
            if (digest.isEmpty()) {
                runOnUiThread(() -> status.setText(R.string.semantic_empty_project));
                return;
            }
            StringBuilder catalogue = new StringBuilder();
            for (CodeDigest.Entry e : digest) catalogue.append(e.summary);

            String prompt = "You are given a catalogue of files in a project. Each line is:\n"
                    + "path | types: ... | methods: ... | uses: ...\n\n"
                    + "CATALOGUE:\n" + catalogue + "\n"
                    + "QUESTION: " + q + "\n\n"
                    + "List the files that answer the question, most relevant first, at most 8.\n"
                    + "Reply with one file per line in exactly this form and nothing else:\n"
                    + "<path> :: <one short sentence on why it is relevant>\n"
                    + "Use only paths that appear verbatim in the catalogue. "
                    + "If nothing is relevant, reply with the single word NONE.";

            runOnUiThread(() -> status.setText(getString(R.string.semantic_asking, digest.size())));

            GeminiService.quickPrompt(this, prompt, new GeminiService.ResponseCallback() {
                @Override
                public void onSuccess(String response) {
                    runOnUiThread(() -> render(response, digest));
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> status.setText(getString(R.string.semantic_failed, error)));
                }
            });
        }, "semantic-search").start();
    }

    private void render(String response, List<CodeDigest.Entry> digest) {
        results.removeAllViews();
        if (response == null || response.trim().isEmpty()
                || response.trim().equalsIgnoreCase("NONE")) {
            status.setText(R.string.semantic_no_match);
            return;
        }

        List<String[]> hits = new ArrayList<>();
        for (String line : response.split("\\R")) {
            String row = line.trim();
            if (row.isEmpty()) continue;
            int sep = row.indexOf("::");
            String path = (sep < 0 ? row : row.substring(0, sep)).trim();
            String why = sep < 0 ? "" : row.substring(sep + 2).trim();
            path = path.replaceAll("^[-*\\d.\\s]+", "").trim();

            // Only a path the catalogue actually contained: the model must not
            // be able to invent a file, and a stale one would open nothing.
            File resolved = null;
            for (CodeDigest.Entry e : digest) {
                if (CodeDigest.relative(root, e.file).equals(path)) { resolved = e.file; break; }
            }
            if (resolved == null) continue;
            hits.add(new String[]{path, why});
            addResult(resolved, path, why);
        }

        if (hits.isEmpty()) {
            status.setText(R.string.semantic_no_match);
        } else {
            status.setText(getResources().getQuantityString(
                    R.plurals.semantic_found, hits.size(), hits.size()));
        }
    }

    private void addResult(File file, String path, String why) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackgroundColor(theme.consoleBg);

        TextView name = new TextView(this);
        name.setText(path);
        name.setTextColor(theme.accent);
        name.setTextSize(13f);
        card.addView(name);

        if (!why.isEmpty()) {
            TextView reason = new TextView(this);
            reason.setText(why);
            reason.setTextColor(theme.textDim);
            reason.setTextSize(12f);
            reason.setPadding(0, dp(4), 0, 0);
            card.addView(reason);
        }

        card.setOnClickListener(v -> {
            Intent open = new Intent(this, MainActivity.class);
            open.putExtra("file_path", file.getAbsolutePath());
            open.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(open);
            finish();
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, 0);
        results.addView(card, lp);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
