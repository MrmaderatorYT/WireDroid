package com.ccs.wiredroid.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.Colors;
import com.ccs.wiredroid.util.FullScreenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Several JShell sessions side by side in one window.
 *
 * <p>Each pane owns a {@link JShellPanelManager}, which owns its own history,
 * scrollback and scratch directory — output cannot land in the wrong pane. The
 * panes are laid out in a weighted {@link LinearLayout} whose orientation the
 * user flips between columns and rows; dividers between them can be dragged to
 * re-balance the split.</p>
 *
 * <p>The activity handles its own configuration changes (see the manifest), so
 * rotating or resizing the window — DeX, freeform, split-screen — re-flows the
 * layout in {@link #onConfigurationChanged} instead of destroying the sessions.
 * When the window is too small for the panes to be legible, only the focused
 * pane is shown and a switcher row appears to move between them.</p>
 */
public class SplitTerminalActivity extends AppCompatActivity {

    private static final int MIN_PANES = 1;
    private static final int MAX_PANES = 4;
    private static final int INITIAL_PANES = 2;

    /** Narrower than this per pane and the text wraps into an unreadable ribbon. */
    private static final int MIN_PANE_WIDTH_DP = 220;
    /** Shorter than this and a pane is all header and input with no scrollback. */
    private static final int MIN_PANE_HEIGHT_DP = 150;
    /** Toolbar, switcher and status line, which do not belong to any pane. */
    private static final int CHROME_HEIGHT_DP = 96;

    private static final int MENU_ADD = 1;
    private static final int MENU_CLOSE = 2;
    private static final int MENU_SPLIT = 3;

    private static final String STATE_PANE_COUNT = "pane_count";
    private static final String STATE_ORIENTATION = "split_orientation";
    private static final String STATE_FOCUSED = "focused_pane";

    private AppPreferences prefs;
    private AppTheme theme;
    private Typeface mono;

    private LinearLayout splitContainer;
    private LinearLayout switcherRow;
    private HorizontalScrollView switcherScroll;
    private TextView statusBar;

    private final List<Pane> panes = new ArrayList<>();
    private int focused;
    private int splitOrientation = LinearLayout.HORIZONTAL;
    private boolean compact;

    public static void launch(Context context) {
        context.startActivity(new Intent(context, SplitTerminalActivity.class));
    }

    /** One terminal: its views, its session, and its share of the split. */
    private static final class Pane {
        LinearLayout frame;         // bordered outer view — carries the focus marker
        LinearLayout content;       // console background, handed to the session
        TextView title;
        TextView runningBadge;
        TextView closeButton;
        TextView output;
        EditText input;
        JShellPanelManager session;
        float weight = 1f;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);
        super.onCreate(savedInstanceState);
        mono = prefs.resolveTypeface();

        Configuration config = getResources().getConfiguration();
        int startingPanes = INITIAL_PANES;
        if (savedInstanceState != null) {
            startingPanes = clamp(savedInstanceState.getInt(STATE_PANE_COUNT, INITIAL_PANES));
            splitOrientation = savedInstanceState.getInt(STATE_ORIENTATION, LinearLayout.HORIZONTAL);
            focused = savedInstanceState.getInt(STATE_FOCUSED, 0);
        } else {
            // Stack rows on a tall window, columns on a wide one.
            splitOrientation = config.screenWidthDp >= config.screenHeightDp
                    ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL;
        }

        setContentView(buildRoot());
        FullScreenHelper.enable(this);

        // Only the first pane is needed to put a usable terminal on screen; the
        // others each cost a JShell session and a selectable scrollback, so they
        // join once the first frame is out.
        panes.add(createPane());
        int restoredFocus = focused;
        focused = 0;
        relayout();

        if (startingPanes > 1) {
            final int remaining = startingPanes - 1;
            final int wanted = restoredFocus;
            splitContainer.post(() -> addStartupPanes(remaining, wanted));
        }
    }

    private void addStartupPanes(int count, int focusIndex) {
        if (isFinishing() || isDestroyed()) return;
        for (int i = 0; i < count; i++) panes.add(createPane());
        if (focusIndex > 0 && focusIndex < panes.size()) focused = focusIndex;
        relayout();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PANE_COUNT, panes.size());
        outState.putInt(STATE_ORIENTATION, splitOrientation);
        outState.putInt(STATE_FOCUSED, focused);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (Pane pane : panes) pane.session.release();
        panes.clear();
    }

    /**
     * The window changed shape. The panes themselves are untouched — only the
     * split is measured again, which is the whole point of handling the change
     * here rather than letting the activity restart.
     */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        relayout();
    }

    // ─── UI ─────────────────────────────────────────────────────────────────

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle(R.string.terminal_title);
        toolbar.setTitleTextColor(theme.text);
        toolbar.setSubtitleTextColor(theme.textDim);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        setSupportActionBar(toolbar);
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        switcherScroll = new HorizontalScrollView(this);
        switcherScroll.setHorizontalScrollBarEnabled(false);
        switcherScroll.setBackgroundColor(theme.statusBar);
        switcherRow = new LinearLayout(this);
        switcherRow.setOrientation(LinearLayout.HORIZONTAL);
        switcherRow.setPadding(dp(4), dp(2), dp(4), dp(2));
        switcherScroll.addView(switcherRow);
        root.addView(switcherScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        splitContainer = new LinearLayout(this);
        splitContainer.setOrientation(splitOrientation);
        splitContainer.setBackgroundColor(theme.bg);
        root.addView(splitContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statusBar = new TextView(this);
        statusBar.setTypeface(mono);
        statusBar.setTextSize(11);
        statusBar.setTextColor(theme.textDim);
        statusBar.setBackgroundColor(theme.statusBar);
        statusBar.setPadding(dp(8), dp(4), dp(8), dp(4));
        root.addView(statusBar);

        return root;
    }

    private Pane createPane() {
        final Pane pane = new Pane();

        pane.frame = new LinearLayout(this);
        pane.frame.setOrientation(LinearLayout.VERTICAL);
        int border = dp(2);
        pane.frame.setPadding(border, border, border, border);

        pane.content = new LinearLayout(this);
        pane.content.setOrientation(LinearLayout.VERTICAL);
        pane.frame.addView(pane.content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Header: name, running badge, close.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(2), dp(2), dp(2));

        pane.title = new TextView(this);
        pane.title.setTypeface(mono, Typeface.BOLD);
        pane.title.setTextSize(11);
        pane.title.setMaxLines(1);
        header.addView(pane.title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        pane.runningBadge = new TextView(this);
        pane.runningBadge.setTypeface(mono);
        pane.runningBadge.setTextSize(10);
        pane.runningBadge.setTextColor(theme.successText);
        pane.runningBadge.setText(R.string.terminal_pane_running);
        pane.runningBadge.setVisibility(View.GONE);
        pane.runningBadge.setPadding(dp(4), 0, dp(4), 0);
        header.addView(pane.runningBadge);

        pane.closeButton = new TextView(this);
        pane.closeButton.setText(R.string.terminal_close_symbol);
        pane.closeButton.setTypeface(mono, Typeface.BOLD);
        pane.closeButton.setTextSize(12);
        pane.closeButton.setTextColor(theme.textDim);
        pane.closeButton.setPadding(dp(10), dp(4), dp(10), dp(4));
        pane.closeButton.setBackgroundResource(android.R.drawable.list_selector_background);
        pane.closeButton.setOnClickListener(v -> requestClosePane(pane));
        header.addView(pane.closeButton);

        pane.content.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View headerRule = new View(this);
        headerRule.setBackgroundColor(theme.separator);
        pane.content.addView(headerRule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // Scrollback.
        pane.output = new TextView(this);
        pane.output.setTypeface(mono);
        pane.output.setTextSize(12);
        pane.output.setTextColor(theme.consoleText);
        pane.output.setTextIsSelectable(true);
        pane.output.setPadding(dp(8), dp(6), dp(8), dp(6));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(pane.output);
        scroll.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) focusPane(pane, false);
            return false;
        });
        pane.content.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Input.
        pane.input = new EditText(this);
        pane.input.setHint(R.string.terminal_input_hint);
        pane.input.setTypeface(mono);
        pane.input.setTextSize(12);
        pane.input.setSingleLine(true);
        pane.input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        pane.input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        pane.input.setPadding(dp(8), dp(6), dp(8), dp(6));
        pane.input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) focusPane(pane, false);
        });
        pane.content.addView(pane.input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pane.session = new JShellPanelManager(this, pane.content, scroll, pane.output, pane.input);
        pane.session.applyTheme(theme);
        pane.session.setStateListener(busy -> {
            pane.runningBadge.setVisibility(busy ? View.VISIBLE : View.GONE);
        });

        // applyTheme paints the whole pane background; the header sits on top of it.
        header.setBackgroundColor(theme.toolbar);
        return pane;
    }

    // ─── Layout ─────────────────────────────────────────────────────────────

    /**
     * Rebuilds the split from the current pane list, orientation and window
     * size. Pane views are re-parented, never recreated, so scrollback and
     * session state survive every re-flow.
     */
    private void relayout() {
        compact = shouldUseCompact();
        splitContainer.setOrientation(splitOrientation);
        splitContainer.removeAllViews();

        if (focused >= panes.size()) focused = Math.max(0, panes.size() - 1);

        if (compact) {
            Pane visible = panes.get(focused);
            splitContainer.addView(visible.frame, paneParams(1f));
        } else {
            for (int i = 0; i < panes.size(); i++) {
                Pane pane = panes.get(i);
                if (i > 0) splitContainer.addView(buildDivider(panes.get(i - 1), pane), dividerParams());
                splitContainer.addView(pane.frame, paneParams(pane.weight));
            }
        }

        refreshSwitcher();
        refreshPaneChrome();
        refreshStatus();
        invalidateOptionsMenu();
    }

    private LinearLayout.LayoutParams paneParams(float weight) {
        return splitOrientation == LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight);
    }

    private LinearLayout.LayoutParams dividerParams() {
        return splitOrientation == LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(dp(8), ViewGroup.LayoutParams.MATCH_PARENT)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
    }

    /**
     * True when the window cannot give every pane a legible share, in which case
     * one pane is shown at a time and the switcher row takes over.
     */
    private boolean shouldUseCompact() {
        if (panes.size() <= 1) return false;
        Configuration config = getResources().getConfiguration();
        if (splitOrientation == LinearLayout.HORIZONTAL) {
            return config.screenWidthDp / panes.size() < MIN_PANE_WIDTH_DP;
        }
        int usable = config.screenHeightDp - CHROME_HEIGHT_DP;
        return usable / panes.size() < MIN_PANE_HEIGHT_DP;
    }

    /** A grab handle between two panes; dragging it moves weight from one to the other. */
    private View buildDivider(final Pane before, final Pane after) {
        View divider = new View(this);
        divider.setBackgroundColor(theme.separator);
        divider.setContentDescription(getString(R.string.terminal_a11y_divider));

        final float[] startAt = new float[1];
        final float[] startWeights = new float[2];
        final int[] startSizes = new int[2];

        divider.setOnTouchListener((v, event) -> {
            boolean horizontal = splitOrientation == LinearLayout.HORIZONTAL;
            float position = horizontal ? event.getRawX() : event.getRawY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startAt[0] = position;
                    startWeights[0] = before.weight;
                    startWeights[1] = after.weight;
                    startSizes[0] = horizontal ? before.frame.getWidth() : before.frame.getHeight();
                    startSizes[1] = horizontal ? after.frame.getWidth() : after.frame.getHeight();
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int total = startSizes[0] + startSizes[1];
                    if (total <= 0) return true;
                    float totalWeight = startWeights[0] + startWeights[1];
                    float share = (startSizes[0] + (position - startAt[0])) / total;
                    share = Math.max(0.15f, Math.min(0.85f, share));
                    before.weight = totalWeight * share;
                    after.weight = totalWeight * (1f - share);
                    applyWeights();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.performClick();
                    return true;
                default:
                    return false;
            }
        });
        return divider;
    }

    private void applyWeights() {
        for (Pane pane : panes) {
            ViewGroup.LayoutParams params = pane.frame.getLayoutParams();
            if (params instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) params).weight = pane.weight;
                pane.frame.setLayoutParams(params);
            }
        }
        splitContainer.requestLayout();
    }

    // ─── Focus ──────────────────────────────────────────────────────────────

    /**
     * Marks {@code pane} as the one that receives typing. With several input
     * fields on screen the border and header tint are the only way to tell.
     */
    private void focusPane(Pane pane, boolean moveCursor) {
        int index = panes.indexOf(pane);
        if (index < 0) return;
        boolean changed = index != focused;
        focused = index;
        if (compact && changed) {
            relayout();
        } else {
            refreshPaneChrome();
            refreshSwitcher();
        }
        if (moveCursor) {
            pane.input.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(pane.input, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void refreshPaneChrome() {
        for (int i = 0; i < panes.size(); i++) {
            Pane pane = panes.get(i);
            boolean isFocused = i == focused;
            pane.title.setText(getString(R.string.terminal_pane_name, i + 1));
            pane.title.setTextColor(isFocused ? theme.accent : theme.textDim);
            pane.closeButton.setContentDescription(
                    getString(R.string.terminal_a11y_close_pane, paneName(i)));
            pane.input.setContentDescription(getString(R.string.terminal_a11y_input, paneName(i)));
            pane.output.setContentDescription(getString(R.string.terminal_a11y_output, paneName(i)));

            GradientDrawable frameBg = new GradientDrawable();
            frameBg.setColor(theme.consoleBg);
            frameBg.setCornerRadius(dp(4));
            frameBg.setStroke(dp(isFocused ? 2 : 1),
                    isFocused ? theme.accent : Colors.blend(theme.bg, theme.separator, 0.6f));
            pane.frame.setBackground(frameBg);
        }
    }

    private void refreshSwitcher() {
        switcherScroll.setVisibility(View.VISIBLE);
        switcherRow.removeAllViews();

        for (int i = 0; i < panes.size(); i++) {
            final Pane pane = panes.get(i);
            boolean isFocused = i == focused;
            TextView chip = new TextView(this);
            chip.setText(paneName(i));
            chip.setTypeface(mono, isFocused ? Typeface.BOLD : Typeface.NORMAL);
            chip.setTextSize(11);
            chip.setTextColor(isFocused ? theme.accent : theme.text);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            if (isFocused) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(theme.toolbar);
                bg.setCornerRadius(dp(4));
                chip.setBackground(bg);
            }
            chip.setOnClickListener(v -> focusPane(pane, true));
            switcherRow.addView(chip);
        }

        TextView addChip = new TextView(this);
        addChip.setText(" + ");
        addChip.setTypeface(mono, Typeface.BOLD);
        addChip.setTextSize(14);
        addChip.setTextColor(theme.accent);
        addChip.setPadding(dp(12), dp(6), dp(12), dp(6));
        addChip.setOnClickListener(v -> addPane());
        switcherRow.addView(addChip);
    }

    private void refreshStatus() {
        String layout = getString(splitOrientation == LinearLayout.HORIZONTAL
                ? R.string.terminal_layout_columns : R.string.terminal_layout_rows);
        statusBar.setText(compact
                ? getString(R.string.terminal_status_compact, panes.size(), paneName(focused))
                : getString(R.string.terminal_status, panes.size(), layout));
    }

    private String paneName(int index) {
        return getString(R.string.terminal_pane_name, index + 1);
    }

    // ─── Actions ────────────────────────────────────────────────────────────

    private void addPane() {
        if (panes.size() >= MAX_PANES) {
            Toast.makeText(this, getString(R.string.terminal_toast_max_panes, MAX_PANES),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Pane pane = createPane();
        // A new pane starts at the average share, so the split stays balanced.
        float average = 0f;
        for (Pane existing : panes) average += existing.weight;
        pane.weight = panes.isEmpty() ? 1f : average / panes.size();
        panes.add(pane);
        focused = panes.size() - 1;
        relayout();
    }

    /** Closing a pane throws away whatever it is running, so ask first. */
    private void requestClosePane(final Pane pane) {
        if (panes.size() <= MIN_PANES) {
            Toast.makeText(this, R.string.terminal_toast_last_pane, Toast.LENGTH_SHORT).show();
            return;
        }
        if (pane.session.isBusy()) {
            int index = panes.indexOf(pane);
            Dialogs.rounded(this)
                    .setTitle(R.string.terminal_close_confirm_title)
                    .setMessage(getString(R.string.terminal_close_confirm_message,
                            paneName(Math.max(0, index))))
                    .setPositiveButton(R.string.terminal_close_confirm_yes, (d, w) -> closePane(pane))
                    .setNegativeButton(R.string.terminal_cancel, null)
                    .show();
            return;
        }
        closePane(pane);
    }

    private void closePane(Pane pane) {
        int index = panes.indexOf(pane);
        if (index < 0 || panes.size() <= MIN_PANES) return;
        panes.remove(index);
        pane.session.release();
        splitContainer.removeView(pane.frame);
        if (focused >= panes.size()) focused = panes.size() - 1;
        relayout();
    }

    private void toggleSplitOrientation() {
        splitOrientation = splitOrientation == LinearLayout.HORIZONTAL
                ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL;
        relayout();
    }

    // ─── Menu ───────────────────────────────────────────────────────────────

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_ADD, 0, R.string.terminal_menu_add_pane)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, MENU_CLOSE, 1, R.string.terminal_menu_close_pane)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, MENU_SPLIT, 2, R.string.terminal_menu_split_columns)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem add = menu.findItem(MENU_ADD);
        if (add != null) add.setEnabled(panes.size() < MAX_PANES);
        MenuItem close = menu.findItem(MENU_CLOSE);
        if (close != null) close.setEnabled(panes.size() > MIN_PANES);
        MenuItem split = menu.findItem(MENU_SPLIT);
        if (split != null) {
            // The title names the orientation the split will switch to.
            split.setTitle(splitOrientation == LinearLayout.HORIZONTAL
                    ? R.string.terminal_menu_split_rows : R.string.terminal_menu_split_columns);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case MENU_ADD:
                addPane();
                return true;
            case MENU_CLOSE:
                if (focused >= 0 && focused < panes.size()) requestClosePane(panes.get(focused));
                return true;
            case MENU_SPLIT:
                toggleSplitOrientation();
                return true;
            case android.R.id.home:
                finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private int clamp(int paneCount) {
        return Math.max(MIN_PANES, Math.min(MAX_PANES, paneCount));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
