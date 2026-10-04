package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.Colors;
import com.ccs.wiredroid.util.FullScreenHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.CRC32;

/**
 * Hash calculator: MD5, SHA-1, SHA-256, SHA-512 and CRC32 of a file.
 *
 * <p>All five checksums come out of a <em>single</em> streaming pass — every
 * chunk read is fed to each digest — so a 2 GB file costs one buffer of memory,
 * not 2 GB, and is read from disk once rather than five times.</p>
 *
 * <p>The read runs on a background thread and checks {@link #cancelled} at every
 * chunk boundary, so Cancel stops the actual I/O instead of merely hiding a
 * spinner. Pure Android UI (no WebView, no XML layouts).</p>
 */
public class HashCalculatorActivity extends AppCompatActivity {

    private static final String EXTRA_FILE_PATH = "hash_file_path";
    private static final String STATE_PATH = "hash_state_path";
    private static final String STATE_URI = "hash_state_uri";
    private static final String STATE_RESULTS = "hash_state_results";

    private static final int REQ_PICK_FILE = 7301;

    /** 256 KiB: large enough that five digests amortise the read, small enough to stay cheap. */
    private static final int BUFFER_BYTES = 256 * 1024;
    /** Progress is posted at most this often — a 2 GB file must not flood the main thread. */
    private static final long PROGRESS_INTERVAL_MS = 100L;

    /**
     * Locale data is costly to spin up, so the two formatters outlive the activity and are
     * rebuilt only when the device locale changes. Neither class is thread-safe; both are
     * touched from {@link #applySource} alone, which always runs on the main thread.
     */
    private static Locale formatLocale;
    private static NumberFormat sizeFormat;
    private static DateFormat modifiedFormat;

    /** One row of the result table. A {@code null} JCA name means CRC32. */
    private static final class Algo {
        final int labelRes;
        final String jcaName;

        Algo(int labelRes, String jcaName) {
            this.labelRes = labelRes;
            this.jcaName = jcaName;
        }
    }

    private static final Algo[] ALGOS = {
            new Algo(R.string.hash_algo_md5, "MD5"),
            new Algo(R.string.hash_algo_sha1, "SHA-1"),
            new Algo(R.string.hash_algo_sha256, "SHA-256"),
            new Algo(R.string.hash_algo_sha512, "SHA-512"),
            new Algo(R.string.hash_algo_crc32, null),
    };

    /** What is being hashed: either a plain file or a picked content URI. */
    private static final class Source {
        final File file;
        final Uri uri;
        final String name;
        /** -1 when the provider does not report a size. */
        final long size;
        /** 0 when unknown. */
        final long lastModified;

        Source(File file, Uri uri, String name, long size, long lastModified) {
            this.file = file;
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    private AppPreferences prefs;
    private AppTheme theme;
    private Typeface mono;

    private Source source;
    private String[] results = new String[ALGOS.length];

    private TextView fileNameView;
    private TextView fileSizeView;
    private TextView fileModifiedView;
    private LinearLayout progressBlock;
    private ProgressBar progressBar;
    private TextView progressText;
    private TextView cancelBtn;
    private TextView recomputeBtn;
    private final View[] rowViews = new View[ALGOS.length];
    private final TextView[] valueViews = new TextView[ALGOS.length];
    private EditText compareInput;
    private TextView compareStatus;
    private TextView statusText;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Flipped by Cancel and by onDestroy; the read loop polls it every chunk. */
    private volatile boolean cancelled;
    /** Bumped on every new run so results of a superseded run are discarded. */
    private volatile int generation;
    private Future<?> task;

    /** Opens the calculator on {@code file}. */
    public static void launch(Context context, File file) {
        Intent i = new Intent(context, HashCalculatorActivity.class);
        if (file != null) i.putExtra(EXTRA_FILE_PATH, file.getAbsolutePath());
        context.startActivity(i);
    }

    /** Opens the calculator with no file — the system file picker comes up first. */
    public static void launch(Context context) {
        context.startActivity(new Intent(context, HashCalculatorActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        mono = prefs.resolveTypeface();

        try {
            setContentView(buildRoot());
        } catch (Exception e) {
            android.util.Log.e("HashCalculator", "buildRoot failed", e);
            LinearLayout fallback = new LinearLayout(this);
            fallback.setOrientation(LinearLayout.VERTICAL);
            fallback.setBackgroundColor(theme.bg);
            TextView errTv = new TextView(this);
            errTv.setText(getString(R.string.hash_error_layout, String.valueOf(e.getMessage())));
            errTv.setTextColor(theme.errorText);
            errTv.setPadding(dp(16), dp(16), dp(16), dp(16));
            fallback.addView(errTv);
            setContentView(fallback);
            return;
        }
        FullScreenHelper.enable(this);

        if (restoreState(savedInstanceState)) return;

        String path = getIntent().getStringExtra(EXTRA_FILE_PATH);
        if (path != null) {
            loadFile(new File(path));
        } else {
            // The picker brings a whole external process up in front of us; drawing this
            // screen first means the user lands on the calculator, not on a blank window.
            getWindow().getDecorView().post(() -> {
                if (isFinishing() || isDestroyed()) return;
                pickFile();
            });
        }
    }

    /** @return true when saved state took over and no fresh work is needed. */
    private boolean restoreState(@Nullable Bundle state) {
        if (state == null) return false;

        String[] saved = state.getStringArray(STATE_RESULTS);
        String path = state.getString(STATE_PATH);
        String uriText = state.getString(STATE_URI);

        if (path != null) {
            applySource(fromFile(new File(path)), saved);
            return true;
        }
        if (uriText != null) {
            resolveAndApply(Uri.parse(uriText), saved);
            return true;
        }
        return false;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (source == null) return;
        if (source.file != null) out.putString(STATE_PATH, source.file.getAbsolutePath());
        if (source.uri != null) out.putString(STATE_URI, source.uri.toString());
        if (hasAnyResult()) out.putStringArray(STATE_RESULTS, results);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelled = true;
        generation++;
        if (task != null) task.cancel(false);
        io.shutdownNow();
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle(R.string.hash_title);
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(12), dp(12), dp(12), dp(12));
        scroll.addView(column);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        column.addView(buildFileCard());
        column.addView(buildProgressBlock());

        column.addView(sectionHeader(R.string.hash_section_checksums));
        column.addView(buildResultRows());

        column.addView(sectionHeader(R.string.hash_section_compare));
        column.addView(buildCompareBlock());

        View divider = new View(this);
        divider.setBackgroundColor(theme.separator);
        root.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setBackgroundColor(theme.toolbar);
        actions.setPadding(dp(4), dp(2), dp(4), dp(2));

        TextView openBtn = createButton(getString(R.string.hash_action_open), theme.accent);
        openBtn.setContentDescription(getString(R.string.hash_a11y_open));
        openBtn.setOnClickListener(v -> pickFile());
        actions.addView(openBtn);

        recomputeBtn = createButton(getString(R.string.hash_action_recompute), theme.textDim);
        recomputeBtn.setContentDescription(getString(R.string.hash_a11y_recompute));
        recomputeBtn.setOnClickListener(v -> startCompute());
        recomputeBtn.setVisibility(View.GONE);
        actions.addView(recomputeBtn);

        root.addView(actions);

        statusText = new TextView(this);
        statusText.setBackgroundColor(theme.consoleBg);
        statusText.setTextColor(theme.textDim);
        statusText.setTextSize(10);
        statusText.setPadding(dp(8), dp(4), dp(8), dp(4));
        statusText.setText(R.string.hash_status_idle);
        root.addView(statusText);

        return root;
    }

    private View buildFileCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(theme.consoleBg);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));

        fileNameView = new TextView(this);
        fileNameView.setText(R.string.hash_no_file);
        fileNameView.setTextColor(theme.accent);
        fileNameView.setTextSize(14);
        fileNameView.setTypeface(mono, Typeface.BOLD);
        card.addView(fileNameView);

        fileSizeView = new TextView(this);
        fileSizeView.setTextColor(theme.textDim);
        fileSizeView.setTextSize(11);
        fileSizeView.setPadding(0, dp(2), 0, 0);
        card.addView(fileSizeView);

        fileModifiedView = new TextView(this);
        fileModifiedView.setTextColor(theme.textDim);
        fileModifiedView.setTextSize(11);
        card.addView(fileModifiedView);

        return card;
    }

    private View buildProgressBlock() {
        progressBlock = new LinearLayout(this);
        progressBlock.setOrientation(LinearLayout.VERTICAL);
        progressBlock.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        progressBlock.setLayoutParams(lp);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(ColorStateList.valueOf(theme.accent));
        progressBar.setContentDescription(getString(R.string.hash_a11y_progress));
        progressBlock.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        progressText = new TextView(this);
        progressText.setTextColor(theme.textDim);
        progressText.setTextSize(11);
        progressText.setTypeface(mono);
        row.addView(progressText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        cancelBtn = createButton(getString(R.string.hash_action_cancel), theme.errorText);
        cancelBtn.setContentDescription(getString(R.string.hash_a11y_cancel));
        cancelBtn.setOnClickListener(v -> cancelCompute());
        row.addView(cancelBtn);

        progressBlock.addView(row);
        return progressBlock;
    }

    private View buildResultRows() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        for (int i = 0; i < ALGOS.length; i++) {
            if (i > 0) {
                View sep = new View(this);
                sep.setBackgroundColor(theme.separator);
                container.addView(sep, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            row.setContentDescription(getString(R.string.hash_a11y_row, getString(ALGOS[i].labelRes)));

            TextView label = new TextView(this);
            label.setText(ALGOS[i].labelRes);
            label.setTextColor(theme.accent);
            label.setTextSize(11);
            label.setTypeface(mono, Typeface.BOLD);
            row.addView(label);

            TextView value = new TextView(this);
            value.setText(R.string.hash_value_pending);
            value.setTextColor(theme.text);
            value.setTextSize(12);
            value.setTypeface(mono);
            value.setPadding(0, dp(2), 0, 0);
            row.addView(value);

            final int index = i;
            row.setOnClickListener(v -> copyResult(index));

            rowViews[i] = row;
            valueViews[i] = value;
            container.addView(row);
        }
        return container;
    }

    private View buildCompareBlock() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);

        compareInput = new EditText(this);
        compareInput.setHint(R.string.hash_compare_hint);
        compareInput.setHintTextColor(theme.textDim);
        compareInput.setTextColor(theme.text);
        compareInput.setBackgroundColor(theme.consoleBg);
        compareInput.setTypeface(mono);
        compareInput.setTextSize(12);
        compareInput.setSingleLine(true);
        compareInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        compareInput.setPadding(dp(8), dp(8), dp(8), dp(8));
        compareInput.setContentDescription(getString(R.string.hash_a11y_compare));
        compareInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { refreshComparison(); }
        });
        block.addView(compareInput);

        compareStatus = new TextView(this);
        compareStatus.setTextSize(12);
        compareStatus.setTypeface(mono, Typeface.BOLD);
        compareStatus.setTextColor(theme.textDim);
        compareStatus.setPadding(dp(2), dp(6), dp(2), 0);
        compareStatus.setVisibility(View.GONE);
        block.addView(compareStatus);

        return block;
    }

    private TextView sectionHeader(int textRes) {
        TextView header = new TextView(this);
        header.setText(textRes);
        header.setTextColor(theme.textDim);
        header.setTextSize(11);
        header.setTypeface(mono, Typeface.BOLD);
        header.setAllCaps(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(4);
        header.setLayoutParams(lp);
        return header;
    }

    private TextView createButton(String text, int color) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(color);
        btn.setTextSize(12);
        btn.setTypeface(mono, Typeface.BOLD);
        btn.setPadding(dp(12), dp(8), dp(12), dp(8));
        btn.setBackgroundResource(android.R.drawable.list_selector_background);
        return btn;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // ── Choosing a file ───────────────────────────────────────────────────────

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK_FILE);
        } catch (Exception e) {
            Toast.makeText(this, R.string.hash_error_no_picker, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FILE) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        resolveAndApply(data.getData(), null);
    }

    private Source fromFile(File file) {
        return new Source(file, null, file.getName(),
                file.exists() ? file.length() : -1L, file.lastModified());
    }

    /**
     * Stat'ing the file is three syscalls, so the card fills in from the io thread. The name
     * is known without touching the disk and goes up straight away, so the screen never
     * claims to have no file while one is on its way in.
     */
    private void loadFile(File file) {
        fileNameView.setText(file.getName());
        io.execute(() -> {
            Source resolved = fromFile(file);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                applySource(resolved, null);
            });
        });
    }

    /** Metadata for a content URI needs a provider query, so it happens off the main thread. */
    private void resolveAndApply(Uri uri, @Nullable String[] preset) {
        io.execute(() -> {
            String name = uri.getLastPathSegment();
            long size = -1L;
            long modified = 0L;
            try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int nameCol = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameCol >= 0 && !c.isNull(nameCol)) name = c.getString(nameCol);
                    int sizeCol = c.getColumnIndex(OpenableColumns.SIZE);
                    if (sizeCol >= 0 && !c.isNull(sizeCol)) size = c.getLong(sizeCol);
                    int modCol = c.getColumnIndex(
                            android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                    if (modCol >= 0 && !c.isNull(modCol)) modified = c.getLong(modCol);
                }
            } catch (Exception ignored) {
                // Providers are free to reject the query; name/size simply stay unknown.
            }
            Source resolved = new Source(null, uri, name != null ? name : uri.toString(),
                    size, modified);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                applySource(resolved, preset);
            });
        });
    }

    /** Shows {@code src}; reuses {@code preset} results when they survived a rotation. */
    private void applySource(Source src, @Nullable String[] preset) {
        source = src;
        ensureFormats();
        fileNameView.setText(src.name);
        fileSizeView.setText(src.size >= 0
                ? getString(R.string.hash_size_detail, formatSize(src.size),
                        sizeFormat.format(src.size))
                : getString(R.string.hash_size_unknown));
        fileModifiedView.setText(src.lastModified > 0
                ? getString(R.string.hash_modified,
                        modifiedFormat.format(new Date(src.lastModified)))
                : getString(R.string.hash_modified_unknown));

        if (preset != null && preset.length == ALGOS.length && hasAnyResult(preset)) {
            results = preset;
            showResults();
            statusText.setText(R.string.hash_status_ready);
            setBusy(false);
            refreshComparison();
            return;
        }
        startCompute();
    }

    // ── Computing ─────────────────────────────────────────────────────────────

    private void startCompute() {
        if (source == null) return;

        cancelled = true;              // stop whatever is mid-read
        final int runId = ++generation; // and discard anything it still posts
        cancelled = false;

        results = new String[ALGOS.length];
        showResults();
        refreshComparison();
        setBusy(true);
        progressBar.setIndeterminate(source.size <= 0);
        progressBar.setProgress(0);
        progressText.setText(source.size > 0
                ? getString(R.string.hash_progress, formatSize(0), formatSize(source.size), 0)
                : getString(R.string.hash_progress_unknown, formatSize(0)));
        statusText.setText(R.string.hash_status_computing);

        final Source src = source;
        task = io.submit(() -> compute(runId, src));
    }

    /** Runs on the io thread: one pass over the bytes, every digest fed in step. */
    private void compute(int runId, Source src) {
        long started = SystemClock.elapsedRealtime();

        MessageDigest[] digests = new MessageDigest[ALGOS.length];
        for (int i = 0; i < ALGOS.length; i++) {
            if (ALGOS[i].jcaName == null) continue;
            try {
                digests[i] = MessageDigest.getInstance(ALGOS[i].jcaName);
            } catch (NoSuchAlgorithmException e) {
                digests[i] = null;   // rendered as "not available" rather than failing the run
            }
        }
        CRC32 crc = new CRC32();

        byte[] buffer = new byte[BUFFER_BYTES];
        long done = 0;
        long lastPost = 0;

        try (InputStream in = openStream(src)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (cancelled || runId != generation) return;
                if (n == 0) continue;

                for (MessageDigest d : digests) {
                    if (d != null) d.update(buffer, 0, n);
                }
                crc.update(buffer, 0, n);
                done += n;

                long now = SystemClock.elapsedRealtime();
                if (now - lastPost >= PROGRESS_INTERVAL_MS) {
                    lastPost = now;
                    publishProgress(runId, done, src.size);
                }
            }
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            ui.post(() -> {
                if (runId != generation) return;
                setBusy(false);
                statusText.setText(getString(R.string.hash_status_error, message));
                Toast.makeText(this, getString(R.string.hash_error_open_failed, message),
                        Toast.LENGTH_LONG).show();
            });
            return;
        }

        if (cancelled || runId != generation) return;

        final String[] out = new String[ALGOS.length];
        for (int i = 0; i < ALGOS.length; i++) {
            if (ALGOS[i].jcaName == null) {
                out[i] = String.format(Locale.US, "%08x", crc.getValue());
            } else if (digests[i] != null) {
                out[i] = hex(digests[i].digest());
            }
        }

        final long total = done;
        final long elapsed = SystemClock.elapsedRealtime() - started;
        ui.post(() -> {
            if (runId != generation) return;
            results = out;
            showResults();
            setBusy(false);
            progressBar.setIndeterminate(false);
            progressBar.setProgress(100);
            progressText.setText(getString(R.string.hash_progress,
                    formatSize(total), formatSize(total), 100));
            statusText.setText(getString(R.string.hash_status_done, formatSize(total), elapsed));
            refreshComparison();
        });
    }

    private InputStream openStream(Source src) throws IOException {
        if (src.file != null) return new FileInputStream(src.file);
        InputStream in = getContentResolver().openInputStream(src.uri);
        if (in == null) throw new IOException(getString(R.string.hash_error_no_stream));
        return in;
    }

    private void publishProgress(int runId, long done, long total) {
        ui.post(() -> {
            if (runId != generation) return;
            if (total > 0) {
                int percent = (int) Math.min(100L, done * 100L / total);
                progressBar.setIndeterminate(false);
                progressBar.setProgress(percent);
                progressText.setText(getString(R.string.hash_progress,
                        formatSize(done), formatSize(total), percent));
            } else {
                progressText.setText(getString(R.string.hash_progress_unknown, formatSize(done)));
            }
        });
    }

    private void cancelCompute() {
        cancelled = true;
        generation++;              // ignore anything the doomed run still posts
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        setBusy(false);
        progressBar.setIndeterminate(false);
        statusText.setText(R.string.hash_status_cancelled);
    }

    private void setBusy(boolean busy) {
        progressBlock.setVisibility(busy ? View.VISIBLE : View.GONE);
        cancelBtn.setVisibility(busy ? View.VISIBLE : View.GONE);
        recomputeBtn.setVisibility(!busy && source != null ? View.VISIBLE : View.GONE);
    }

    // ── Results, clipboard, comparison ────────────────────────────────────────

    private void showResults() {
        for (int i = 0; i < ALGOS.length; i++) {
            String value = results[i];
            if (value != null) {
                valueViews[i].setText(value);
                valueViews[i].setTextColor(theme.text);
            } else {
                valueViews[i].setText(R.string.hash_value_pending);
                valueViews[i].setTextColor(theme.textDim);
            }
            rowViews[i].setBackgroundResource(android.R.drawable.list_selector_background);
        }
    }

    private void copyResult(int index) {
        String value = results[index];
        if (value == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.hash_clip_label), value));
        Toast.makeText(this, getString(R.string.hash_toast_copied,
                getString(ALGOS[index].labelRes)), Toast.LENGTH_SHORT).show();
    }

    /** Case-insensitive, surrounding whitespace ignored; the matching row lights up. */
    private void refreshComparison() {
        if (compareInput == null) return;
        String expected = compareInput.getText().toString().trim();

        if (expected.isEmpty()) {
            compareStatus.setVisibility(View.GONE);
            clearHighlights();
            return;
        }
        compareStatus.setVisibility(View.VISIBLE);

        if (!hasAnyResult()) {
            compareStatus.setText(R.string.hash_compare_pending);
            compareStatus.setTextColor(theme.textDim);
            clearHighlights();
            return;
        }

        String needle = expected.toLowerCase(Locale.ROOT);
        int matched = -1;
        for (int i = 0; i < ALGOS.length; i++) {
            if (results[i] != null && results[i].equals(needle)) {
                matched = i;
                break;
            }
        }

        for (int i = 0; i < ALGOS.length; i++) {
            if (i == matched) {
                rowViews[i].setBackgroundColor(Colors.blend(theme.bg, theme.successText, 0.25f));
                valueViews[i].setTextColor(theme.successText);
            } else {
                rowViews[i].setBackgroundResource(android.R.drawable.list_selector_background);
                valueViews[i].setTextColor(results[i] != null ? theme.text : theme.textDim);
            }
        }

        if (matched >= 0) {
            compareStatus.setText(getString(R.string.hash_compare_match,
                    getString(ALGOS[matched].labelRes)));
            compareStatus.setTextColor(theme.successText);
        } else {
            compareStatus.setText(R.string.hash_compare_mismatch);
            compareStatus.setTextColor(theme.errorText);
        }
    }

    private void clearHighlights() {
        for (int i = 0; i < ALGOS.length; i++) {
            rowViews[i].setBackgroundResource(android.R.drawable.list_selector_background);
            valueViews[i].setTextColor(results[i] != null ? theme.text : theme.textDim);
        }
    }

    private boolean hasAnyResult() {
        return hasAnyResult(results);
    }

    private static boolean hasAnyResult(String[] values) {
        if (values == null) return false;
        for (String v : values) {
            if (v != null) return true;
        }
        return false;
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    private static void ensureFormats() {
        Locale current = Locale.getDefault();
        if (!current.equals(formatLocale)) {
            formatLocale = current;
            sizeFormat = NumberFormat.getIntegerInstance();
            modifiedFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private String formatSize(long bytes) {
        if (bytes < 0) return getString(R.string.hash_size_unknown);
        if (bytes < 1024) return getString(R.string.hash_size_b, bytes);
        if (bytes < 1024L * 1024) return getString(R.string.hash_size_kb, bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) {
            return getString(R.string.hash_size_mb, bytes / (1024.0 * 1024));
        }
        return getString(R.string.hash_size_gb, bytes / (1024.0 * 1024 * 1024));
    }
}
