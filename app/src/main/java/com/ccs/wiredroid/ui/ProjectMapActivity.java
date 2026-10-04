package com.ccs.wiredroid.ui;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.project.ProjectMapGraph;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.Colors;
import com.ccs.wiredroid.util.FullScreenHelper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Project Map: the project as a graph of classes instead of a file tree.
 *
 * <p>Nodes are source files, edges are the imports between them. The layout is
 * a Fruchterman-Reingold force simulation run on a background thread; the view
 * is fed position snapshots as it settles and the thread exits for good once
 * the graph stops moving, so nothing keeps spinning in the background.</p>
 *
 * <p>Tapping a class returns its path to {@link MainActivity}, which opens it
 * in the editor.</p>
 */
public class ProjectMapActivity extends AppCompatActivity {

    private static final String EXTRA_PROJECT_DIR = "project_dir";

    /**
     * Result extras. MainActivity already knows how to open a file at a line
     * from a child activity under this request code, so the map reuses that
     * contract rather than growing a second one.
     */
    public static final String RESULT_FILE_PATH = "file_path";
    public static final String RESULT_LINE_NUMBER = "line_number";
    private static final int REQ_OPEN_FROM_MAP = 4006;

    // ── Force layout constants ────────────────────────────────────────────
    /** Ideal edge length in world units; the world size is derived from it. */
    private static final float SPRING_K = 100f;
    private static final float GRAVITY = 0.08f;
    /** Below this per-node movement the graph is settled and the thread stops. */
    private static final float CONVERGED = 0.35f;
    private static final int MIN_ITERATIONS = 40;
    private static final long FRAME_MS = 40L;

    private AppPreferences prefs;
    private AppTheme theme;
    private Typeface mono;

    private ProjectMapView mapView;
    private TextView statusText;
    private TextView emptyView;
    private LinearLayout buttonRow;

    private ProjectMapGraph graph;
    private int[] palette = new int[0];

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Read by the layout thread every iteration; set when the screen goes away. */
    private volatile boolean cancelled = false;

    public static void launch(Context context, File projectDir) {
        Intent i = new Intent(context, ProjectMapActivity.class);
        if (projectDir != null) i.putExtra(EXTRA_PROJECT_DIR, projectDir.getAbsolutePath());
        if (context instanceof Activity) {
            ((Activity) context).startActivityForResult(i, REQ_OPEN_FROM_MAP);
        } else {
            context.startActivity(i);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        mono = prefs.resolveTypeface();
        setContentView(buildRoot());
        FullScreenHelper.enable(this);

        String dir = getIntent().getStringExtra(EXTRA_PROJECT_DIR);
        if (dir == null) {
            Toast.makeText(this, R.string.map_no_project, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        statusText.setText(R.string.map_scanning);
        final File projectDir = new File(dir);
        io.execute(() -> {
            try {
                final ProjectMapGraph built = ProjectMapGraph.build(projectDir);
                if (cancelled) return;
                ui.post(() -> onGraphReady(built));
                if (built.nodeCount() > 0) runLayout(built);
            } catch (Exception | OutOfMemoryError e) {
                if (cancelled) return;
                ui.post(() -> {
                    statusText.setText(getString(R.string.map_error, String.valueOf(e)));
                    showEmpty(true);
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelled = true;
        io.shutdownNow();
    }

    /**
     * The activity declares configChanges for size and orientation, so rotation
     * does not rebuild anything — the map only has to re-frame itself.
     */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mapView != null) mapView.post(mapView::fitToScreen);
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle(R.string.map_title);
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setBackgroundColor(theme.consoleBg);

        TextView fitBtn = createButton(getString(R.string.map_btn_fit), theme.accent);
        fitBtn.setContentDescription(getString(R.string.map_a11y_fit));
        fitBtn.setOnClickListener(v -> mapView.fitToScreen());
        buttonRow.addView(fitBtn);

        TextView legendBtn = createButton(getString(R.string.map_btn_legend), theme.text);
        legendBtn.setContentDescription(getString(R.string.map_a11y_legend));
        legendBtn.setOnClickListener(v -> showLegend());
        buttonRow.addView(legendBtn);

        TextView clearBtn = createButton(getString(R.string.map_btn_clear), theme.textDim);
        clearBtn.setContentDescription(getString(R.string.map_a11y_clear));
        clearBtn.setOnClickListener(v -> {
            mapView.clearSelection();
            updateStatus();
        });
        buttonRow.addView(clearBtn);

        root.addView(buttonRow);

        FrameLayout canvasBox = new FrameLayout(this);
        canvasBox.setBackgroundColor(theme.bg);

        mapView = new ProjectMapView(this);
        mapView.setContentDescription(getString(R.string.map_a11y_canvas));
        mapView.setThemeColors(
                Colors.blend(theme.text, theme.bg, 0.55f),
                theme.accent,
                theme.successText,
                theme.text,
                theme.bg,
                theme.dark ? Color.WHITE : theme.text);
        mapView.setListener(new ProjectMapView.Listener() {
            @Override
            public void onNodeTap(int index) {
                openNode(index);
            }

            @Override
            public void onNodeLongPress(int index) {
                showSelection(index);
            }

            @Override
            public void onSelectionCleared() {
                updateStatus();
            }
        });
        canvasBox.addView(mapView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = new TextView(this);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(theme.textDim);
        emptyView.setTextSize(14);
        emptyView.setPadding(dp(32), dp(32), dp(32), dp(32));
        emptyView.setVisibility(View.GONE);
        SpannableStringBuilder empty = new SpannableStringBuilder();
        int start = empty.length();
        empty.append(getString(R.string.map_empty_title));
        empty.setSpan(new ForegroundColorSpan(theme.text), start, empty.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        empty.append("\n\n").append(getString(R.string.map_empty_hint));
        emptyView.setText(empty);
        canvasBox.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(canvasBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statusText = new TextView(this);
        statusText.setBackgroundColor(theme.consoleBg);
        statusText.setTextColor(theme.textDim);
        statusText.setTextSize(11);
        statusText.setSingleLine(true);
        statusText.setPadding(dp(12), dp(6), dp(12), dp(6));
        root.addView(statusText);

        return root;
    }

    private TextView createButton(String text, int color) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(color);
        btn.setTextSize(12);
        btn.setTypeface(mono, Typeface.BOLD);
        btn.setPadding(dp(14), dp(8), dp(14), dp(8));
        btn.setBackgroundResource(android.R.drawable.list_selector_background);
        return btn;
    }

    // ── Graph handover ────────────────────────────────────────────────────

    private void onGraphReady(ProjectMapGraph built) {
        graph = built;
        if (built.nodeCount() == 0) {
            showEmpty(true);
            statusText.setText(getString(R.string.map_status_line,
                    getString(R.string.map_status, 0, 0),
                    getString(R.string.map_ready)));
            return;
        }
        showEmpty(false);

        int n = built.nodeCount();
        palette = buildPalette(built.groups.size());
        String[] labels = new String[n];
        int[] colors = new int[n];
        float[] radii = new float[n];
        int maxIn = Math.max(1, built.maxInDegree());
        for (int i = 0; i < n; i++) {
            ProjectMapGraph.Node node = built.nodes.get(i);
            labels[i] = node.simpleName;
            colors[i] = palette[built.groupOf[i] % palette.length];
            // Square root keeps one 90-dependency hub from dwarfing everything.
            radii[i] = 12f + 26f * (float) Math.sqrt(node.inDegree / (float) maxIn);
        }
        mapView.setGraph(labels, colors, radii, built.edgeFrom, built.edgeTo);
        updateStatus();
    }

    private void showEmpty(boolean empty) {
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        mapView.setVisibility(empty ? View.GONE : View.VISIBLE);
        buttonRow.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /**
     * One colour per package bucket, spread by the golden angle so neighbouring
     * buckets stay distinguishable however many there are.
     */
    private int[] buildPalette(int groupCount) {
        int count = Math.max(1, groupCount);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            float hue = (i * 137.508f + 18f) % 360f;
            float sat = theme.dark ? 0.52f : 0.62f;
            float val = theme.dark ? 0.86f : 0.74f;
            if (i % 3 == 1) sat += 0.12f;
            if (i % 3 == 2) val -= 0.12f;
            int base = Color.HSVToColor(new float[]{hue, sat, val});
            // A touch of the background keeps the palette inside the theme.
            out[i] = Colors.blend(base, theme.bg, 0.12f);
        }
        return out;
    }

    // ── Status line ───────────────────────────────────────────────────────

    private void updateStatus() {
        updateStatus(false);
    }

    private void updateStatus(boolean settling) {
        if (graph == null) return;
        String counts;
        if (graph.hiddenClasses > 0) {
            counts = getString(R.string.map_status_capped,
                    graph.nodeCount(), graph.totalClasses, graph.edgeCount(), graph.hiddenClasses);
        } else {
            counts = getString(R.string.map_status, graph.nodeCount(), graph.edgeCount());
        }
        statusText.setText(getString(R.string.map_status_line, counts,
                getString(settling ? R.string.map_settling : R.string.map_ready)));
    }

    private void showSelection(int index) {
        if (graph == null || index < 0 || index >= graph.nodeCount()) return;
        ProjectMapGraph.Node node = graph.nodes.get(index);
        statusText.setText(getString(R.string.map_selected,
                node.fqName, mapView.neighbourCount(), node.inDegree, node.outDegree));
    }

    private void openNode(int index) {
        if (graph == null || index < 0 || index >= graph.nodeCount()) return;
        ProjectMapGraph.Node node = graph.nodes.get(index);
        if (!node.file.isFile()) {
            Toast.makeText(this, R.string.map_toast_missing_file, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent data = new Intent();
        data.putExtra(RESULT_FILE_PATH, node.file.getAbsolutePath());
        data.putExtra(RESULT_LINE_NUMBER, 1);
        setResult(RESULT_OK, data);
        finish();
    }

    private void showLegend() {
        if (graph == null || graph.nodeCount() == 0) return;
        int[] sizes = graph.groupSizes();
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < graph.groups.size(); i++) {
            String name = graph.groups.get(i);
            if (name.isEmpty()) name = getString(R.string.map_group_root);
            int start = sb.length();
            sb.append(getString(R.string.map_legend_row, name, sizes[i]));
            sb.setSpan(new ForegroundColorSpan(palette[i % palette.length]),
                    start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("\n");
        }
        if (graph.hiddenClasses > 0) {
            sb.append("\n").append(getString(R.string.map_legend_hidden, graph.hiddenClasses));
        }
        Dialogs.rounded(this)
                .setTitle(R.string.map_legend_title)
                .setMessage(sb)
                .setPositiveButton(R.string.map_dialog_close, null)
                .show();
    }

    // ── Force-directed layout (background thread) ─────────────────────────

    /**
     * Fruchterman-Reingold with a linear cooling schedule and a gravity term
     * that keeps disconnected components from drifting off the canvas.
     *
     * <p>Termination is guaranteed twice over: the temperature reaches zero at
     * {@code maxIterations}, and the loop also breaks early once the largest
     * per-node displacement drops below {@link #CONVERGED}. When the loop ends
     * the thread ends — there is no repeating frame callback to leave running.</p>
     */
    private void runLayout(ProjectMapGraph g) {
        final int n = g.nodeCount();
        final float[] x = new float[n];
        final float[] y = new float[n];
        final float[] dx = new float[n];
        final float[] dy = new float[n];
        final int[] ef = g.edgeFrom;
        final int[] et = g.edgeTo;

        // Seed on a phyllotaxis spiral: evenly spread, and identical every time
        // the same project is opened, so the map does not jump around.
        float world = SPRING_K * (float) Math.sqrt(Math.max(1, n));
        for (int i = 0; i < n; i++) {
            double angle = i * 2.399963;
            double r = world * 0.5 * Math.sqrt((i + 0.5) / n);
            x[i] = (float) (Math.cos(angle) * r);
            y[i] = (float) (Math.sin(angle) * r);
        }

        final int maxIterations = n <= 60 ? 260 : 420;
        final float startTemp = world * 0.12f;
        long lastPost = 0L;
        ui.post(() -> updateStatus(true));

        for (int iter = 0; iter < maxIterations && !cancelled; iter++) {
            float temp = startTemp * (1f - (float) iter / maxIterations);
            Arrays.fill(dx, 0f);
            Arrays.fill(dy, 0f);

            // Repulsion between every pair.
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    float ddx = x[i] - x[j];
                    float ddy = y[i] - y[j];
                    float dist2 = ddx * ddx + ddy * ddy;
                    if (dist2 < 0.01f) {
                        // Two nodes exactly on top of each other have no
                        // direction to push apart in; nudge them deterministically.
                        ddx = 0.1f * ((i % 2 == 0) ? 1 : -1);
                        ddy = 0.1f;
                        dist2 = ddx * ddx + ddy * ddy;
                    }
                    float dist = (float) Math.sqrt(dist2);
                    float force = SPRING_K * SPRING_K / dist;
                    float ux = ddx / dist;
                    float uy = ddy / dist;
                    dx[i] += ux * force;
                    dy[i] += uy * force;
                    dx[j] -= ux * force;
                    dy[j] -= uy * force;
                }
            }

            // Attraction along edges.
            for (int e = 0; e < ef.length; e++) {
                int a = ef[e];
                int b = et[e];
                float ddx = x[a] - x[b];
                float ddy = y[a] - y[b];
                float dist = (float) Math.sqrt(ddx * ddx + ddy * ddy);
                if (dist < 0.01f) continue;
                float force = dist * dist / SPRING_K;
                float ux = ddx / dist;
                float uy = ddy / dist;
                dx[a] -= ux * force;
                dy[a] -= uy * force;
                dx[b] += ux * force;
                dy[b] += uy * force;
            }

            // Gravity toward the origin.
            for (int i = 0; i < n; i++) {
                dx[i] -= x[i] * GRAVITY;
                dy[i] -= y[i] * GRAVITY;
            }

            float maxStep = 0f;
            for (int i = 0; i < n; i++) {
                float len = (float) Math.sqrt(dx[i] * dx[i] + dy[i] * dy[i]);
                if (len < 0.0001f) continue;
                float step = Math.min(len, temp);
                x[i] += dx[i] / len * step;
                y[i] += dy[i] / len * step;
                maxStep = Math.max(maxStep, step);
            }

            long now = System.currentTimeMillis();
            boolean settled = iter >= MIN_ITERATIONS && maxStep < CONVERGED;
            if (settled || now - lastPost >= FRAME_MS) {
                lastPost = now;
                final float[] sx = Arrays.copyOf(x, n);
                final float[] sy = Arrays.copyOf(y, n);
                final boolean first = iter == 0;
                ui.post(() -> {
                    mapView.setPositions(sx, sy);
                    if (first) mapView.fitToScreen();
                });
            }
            if (settled) break;
        }

        if (cancelled) return;
        final float[] fx = Arrays.copyOf(x, n);
        final float[] fy = Arrays.copyOf(y, n);
        ui.post(() -> {
            mapView.setPositions(fx, fy);
            mapView.fitToScreen();
            updateStatus(false);
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
