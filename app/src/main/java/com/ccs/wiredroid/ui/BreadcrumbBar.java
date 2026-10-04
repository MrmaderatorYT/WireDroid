package com.ccs.wiredroid.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.Colors;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The thin path strip above the editor: {@code project › src › main ›
 * MainActivity.java › onCreate()}.
 *
 * <p>It lives <em>outside</em> the {@link io.github.rosemoe.sora.widget.CodeEditor},
 * as a sibling view in the parent column. That is the whole point of the
 * design: a tap here is an ordinary button press on an ordinary
 * {@link TextView} and never reaches the editor's text, so it cannot start a
 * selection, move the caret, or fight the editor's own gesture handling. Every
 * segment opens its dropdown on a <em>single</em> tap — there is no
 * double-tap gesture anywhere in this class.</p>
 */
public class BreadcrumbBar extends HorizontalScrollView {

    public interface Listener {
        /** A file was chosen from one of the dropdowns. */
        void onBreadcrumbOpenFile(File file);

        /** A member was chosen from the trailing dropdown; line is 0-based. */
        void onBreadcrumbJumpToLine(int line);
    }

    private final LinearLayout row;

    private Listener listener;
    private AppTheme theme;

    private File file;
    private File projectRoot;
    private List<MemberOutline.Member> members = Collections.emptyList();
    private int caretLine = -1;

    private TextView memberSegment;
    private boolean scrollToEndPending;

    public BreadcrumbBar(Context context) {
        this(context, null);
    }

    public BreadcrumbBar(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BreadcrumbBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setHorizontalScrollBarEnabled(false);
        setFillViewport(false);
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)));
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void applyTheme(AppTheme t) {
        this.theme = t;
        if (t != null) setBackgroundColor(Colors.blend(t.toolbar, t.bg, 0.35f));
        rebuild();
    }

    /**
     * Points the bar at a file. A null file hides the bar outright — an empty
     * strip above an empty editor is just clutter.
     */
    public void setFile(File file, File projectRoot) {
        this.file = file;
        this.projectRoot = projectRoot;
        this.members = Collections.emptyList();
        this.caretLine = -1;
        setVisibility(file == null ? GONE : VISIBLE);
        rebuild();
    }

    /** Installs a fresh outline; cheap, the scan itself happens off this thread. */
    public void setMembers(List<MemberOutline.Member> members) {
        this.members = members == null ? Collections.<MemberOutline.Member>emptyList() : members;
        updateMemberSegment();
    }

    /** Called as the caret moves; only the trailing segment can change. */
    public void setCaretLine(int line) {
        if (this.caretLine == line) return;
        this.caretLine = line;
        updateMemberSegment();
    }

    // ── Building ──────────────────────────────────────────────

    private void rebuild() {
        row.removeAllViews();
        memberSegment = null;
        if (file == null) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);

        List<File> chain = pathChain(file, projectRoot);
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) row.addView(separator());
            final File segment = chain.get(i);
            boolean last = i == chain.size() - 1;
            TextView tv = segmentView(segment.getName(), last);
            tv.setOnClickListener(v -> showSiblingMenu(v, segment));
            row.addView(tv);
        }

        if (MemberOutline.supports(file.getName().toLowerCase(java.util.Locale.ROOT))) {
            row.addView(separator());
            memberSegment = segmentView(getContext().getString(R.string.crumb_no_member), true);
            memberSegment.setOnClickListener(this::showMemberMenu);
            row.addView(memberSegment);
            updateMemberSegment();
        }

        scrollToEndPending = true;
        requestLayout();
    }

    private void updateMemberSegment() {
        if (memberSegment == null) return;
        MemberOutline.Member m = MemberOutline.enclosing(members, caretLine);
        String label = m != null ? m.label : getContext().getString(R.string.crumb_no_member);
        if (!label.contentEquals(memberSegment.getText())) {
            memberSegment.setText(label);
            scrollToEndPending = true;
            requestLayout();
        }
    }

    /**
     * The visible path: every folder from the project root down to the file.
     * Outside a project only the file and two ancestors are shown — an absolute
     * path from {@code /storage/emulated/0} tells the reader nothing.
     */
    private static List<File> pathChain(File file, File root) {
        List<File> chain = new ArrayList<>();
        File cur = file;
        String rootPath = root == null ? null : root.getAbsolutePath();
        int guard = 0;
        while (cur != null && guard++ < 64) {
            chain.add(cur);
            if (rootPath != null && rootPath.equals(cur.getAbsolutePath())) break;
            if (rootPath == null && chain.size() >= 3) break;
            cur = cur.getParentFile();
        }
        Collections.reverse(chain);
        return chain;
    }

    // ── Dropdowns ─────────────────────────────────────────────

    /**
     * Lists the entries of {@code segment}'s parent folder — its siblings —
     * exactly as VS Code does. Picking a folder drills into it.
     */
    private void showSiblingMenu(View anchor, File segment) {
        File dir = segment.getParentFile();
        if (dir == null || !dir.isDirectory() || isAboveRoot(dir)) {
            dir = segment.isDirectory() ? segment : segment.getParentFile();
        }
        if (dir == null) return;
        showFolderMenu(anchor, dir);
    }

    private boolean isAboveRoot(File dir) {
        if (projectRoot == null) return false;
        String rootPath = projectRoot.getAbsolutePath();
        String dirPath = dir.getAbsolutePath();
        return !dirPath.startsWith(rootPath);
    }

    private void showFolderMenu(View anchor, File dir) {
        final List<File> entries = listing(dir);
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        if (entries.isEmpty()) {
            menu.getMenu().add(Menu.NONE, 0, 0, R.string.crumb_empty).setEnabled(false);
        } else {
            for (int i = 0; i < entries.size(); i++) {
                File f = entries.get(i);
                String label = f.isDirectory() ? f.getName() + "/" : f.getName();
                menu.getMenu().add(Menu.NONE, i, i, label);
            }
        }
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id < 0 || id >= entries.size()) return false;
            File picked = entries.get(id);
            if (picked.isDirectory()) {
                anchor.post(() -> showFolderMenu(anchor, picked));
            } else if (listener != null) {
                listener.onBreadcrumbOpenFile(picked);
            }
            return true;
        });
        menu.show();
    }

    private void showMemberMenu(View anchor) {
        final List<MemberOutline.Member> snapshot = members;
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        if (snapshot.isEmpty()) {
            menu.getMenu().add(Menu.NONE, 0, 0, R.string.crumb_no_members).setEnabled(false);
        } else {
            for (int i = 0; i < snapshot.size(); i++) {
                MemberOutline.Member m = snapshot.get(i);
                menu.getMenu().add(Menu.NONE, i, i,
                        getContext().getString(R.string.crumb_member_entry, m.label, m.line + 1));
            }
        }
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id < 0 || id >= snapshot.size()) return false;
            if (listener != null) listener.onBreadcrumbJumpToLine(snapshot.get(id).line);
            return true;
        });
        menu.show();
    }

    /** Folders first, then files, both alphabetical; hidden entries dropped. */
    private static List<File> listing(File dir) {
        File[] raw = dir.listFiles();
        if (raw == null) return Collections.emptyList();
        List<File> out = new ArrayList<>(Arrays.asList(raw));
        for (int i = out.size() - 1; i >= 0; i--) {
            if (out.get(i).getName().startsWith(".")) out.remove(i);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return out;
    }

    // ── Views ─────────────────────────────────────────────────

    private TextView segmentView(String text, boolean emphasised) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setSingleLine(true);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(6), 0, dp(6), 0);
        tv.setTextColor(emphasised ? textColor() : dimColor());
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setBackgroundResource(selectableBackground());
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

    /**
     * Keeps the tail of the path on screen. The file and the method matter far
     * more than the repository root, so a long path scrolls off to the left.
     */
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
