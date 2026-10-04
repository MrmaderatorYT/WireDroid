package com.ccs.wiredroid.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.Colors;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The path strip under the console: {@code project › src › main › Foo.java}.
 * A tap anywhere on it puts the file's absolute path on the clipboard.
 *
 * <p>Deliberately not {@link BreadcrumbBar}. That bar's whole contract is
 * navigation — every segment opens a folder or member dropdown on tap — and it
 * owns the caret-tracking member segment fed by {@link MemberOutline}. Here a
 * tap has to mean "copy" and there is no member to show, so sharing the class
 * would mean a mode flag threaded through its rebuild path plus a second,
 * silent consumer of the outline scanner. The two bars differ by a handful of
 * layout idioms, and the editor breadcrumbs are the busier feature; they are
 * left untouched.</p>
 */
public final class PathCopyBar extends HorizontalScrollView {

    /** Enough of the tail to be recognisable when the file sits outside a project. */
    private static final int LOOSE_SEGMENTS = 3;

    private final LinearLayout row;

    private AppTheme theme;
    private File file;
    private File projectRoot;
    private boolean scrollToEndPending;

    public PathCopyBar(Context context) {
        this(context, null);
    }

    public PathCopyBar(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PathCopyBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setHorizontalScrollBarEnabled(false);
        // Stretches the row to the viewport, so the blank tail after a short
        // path still belongs to the tap target.
        setFillViewport(true);

        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        // Background before padding: setBackgroundResource adopts the drawable's
        // own padding and would otherwise discard what was set first.
        row.setBackgroundResource(selectableBackground());
        row.setPadding(dp(8), 0, dp(8), 0);
        row.setOnClickListener(v -> copyPath());
        addView(row, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)));

        // A HorizontalScrollView never calls performClick() off a touch, so the
        // row is what the finger hits. TalkBack, however, dispatches its click
        // to this view — the XML contentDescription lives here — and that route
        // does call performClick(). Both nodes need the listener.
        setOnClickListener(v -> copyPath());
    }

    public void applyTheme(AppTheme t) {
        this.theme = t;
        if (t != null) setBackgroundColor(Colors.blend(t.toolbar, t.bg, 0.35f));
        rebuild();
    }

    /**
     * Points the bar at a file. A null file hides the bar outright — an empty
     * strip under an empty console is just clutter.
     */
    public void setFile(File file, File projectRoot) {
        this.file = file;
        this.projectRoot = projectRoot;
        rebuild();
    }

    // ── Building ───────────────────────────────────────

    private void rebuild() {
        row.removeAllViews();
        if (file == null) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);

        List<String> names = segments(file, projectRoot);
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) row.addView(separator());
            row.addView(segmentView(names.get(i), i == names.size() - 1));
        }

        scrollToEndPending = true;
        requestLayout();
    }

    /**
     * Folder names from the project root down to the file. A file opened from
     * outside the project shows only its last few segments — an absolute path
     * from /storage/emulated/0 tells the reader nothing. The clipboard still
     * receives the full path either way.
     */
    private static List<String> segments(File file, File root) {
        String rootPath = root == null ? null : root.getAbsolutePath();
        if (rootPath != null && !file.getAbsolutePath().startsWith(rootPath)) rootPath = null;

        List<String> names = new ArrayList<>();
        File cur = file;
        int guard = 0;
        while (cur != null && guard++ < 64) {
            String name = cur.getName();
            if (name.isEmpty()) break; // the filesystem root has no name worth showing
            names.add(name);
            if (rootPath != null && rootPath.equals(cur.getAbsolutePath())) break;
            if (rootPath == null && names.size() >= LOOSE_SEGMENTS) break;
            cur = cur.getParentFile();
        }
        Collections.reverse(names);
        return names;
    }

    // ── Clipboard ─────────────────────────────────────

    private void copyPath() {
        if (file == null) return;
        ClipboardManager cm =
                (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            toast(R.string.pathbar_copy_failed);
            return;
        }
        try {
            cm.setPrimaryClip(ClipData.newPlainText(
                    getContext().getString(R.string.pathbar_clip_label),
                    file.getAbsolutePath()));
        } catch (RuntimeException denied) {
            // Some OEM clipboards throw when the window has lost focus; a failed
            // copy must not take the editor down with it.
            toast(R.string.pathbar_copy_failed);
            return;
        }
        toast(R.string.pathbar_copied);
    }

    private void toast(int messageRes) {
        Toast.makeText(getContext().getApplicationContext(), messageRes, Toast.LENGTH_SHORT)
                .show();
    }

    // ── Views ─────────────────────────────────────────

    /** Segments are inert on purpose: the tap belongs to the bar as a whole. */
    private TextView segmentView(String text, boolean emphasised) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setSingleLine(true);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(2), 0, dp(2), 0);
        tv.setTextColor(emphasised ? textColor() : dimColor());
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return tv;
    }

    private TextView separator() {
        TextView tv = new TextView(getContext());
        tv.setText(R.string.crumb_separator);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setTextColor(dimColor());
        tv.setPadding(dp(2), 0, dp(2), 0);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return tv;
    }

    private int selectableBackground() {
        TypedValue out = new TypedValue();
        getContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, out, true);
        return out.resourceId;
    }

    private int textColor() {
        return theme != null ? theme.text : 0xFFBBBBBB;
    }

    private int dimColor() {
        return theme != null ? theme.textDim : 0xFF808080;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Keeps the file name on screen; a long path scrolls off to the left. */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (scrollToEndPending) {
            scrollToEndPending = false;
            int overflow = row.getWidth() - (getWidth() - getPaddingLeft() - getPaddingRight());
            scrollTo(Math.max(0, overflow), 0);
        }
    }
}
