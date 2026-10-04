package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppTheme;

import java.io.File;

import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * Encapsulates the find and replace bar in the editor.
 */
public final class FindReplaceController {

    public interface Callback {
        File getProjectDir();
        void startGlobalSearch(String query);
    }

    private final Activity activity;
    private final Callback callback;
    /** The panes and what they hold; a live view, not a snapshot. */
    private final EditorWorkspace ws;

    /** False until the ViewStub holding the find bar has been inflated. */
    private boolean barInflated;
    /** Last theme handed to applyTheme, re-applied to views inflated later. */
    private AppTheme lastTheme;

    private LinearLayout findBar;
    private EditText etFind;
    private EditText etReplace;
    private Switch switchFindScope;
    private boolean findGlobalMode = false;
    private int lastSearchOffset = -1;

    public FindReplaceController(Activity activity, EditorWorkspace ws, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        this.ws = ws;
    }

    /** Nothing to bind up front — the bar inflates when it is first opened. */
    public void bind() {
    }

    /**
     * Inflates the find bar the first time it is opened. It sits behind a
     * ViewStub because it starts hidden and is opened on demand, but it was
     * still inflated with the rest of the editor screen on every cold start.
     */
    private void ensureBar() {
        if (barInflated) return;
        barInflated = true;
        android.view.ViewStub stub = activity.findViewById(R.id.stubFindBar);
        if (stub != null) stub.inflate();
        findBar = activity.findViewById(R.id.findBar);
        etFind = activity.findViewById(R.id.etFind);
        etReplace = activity.findViewById(R.id.etReplace);
        switchFindScope = activity.findViewById(R.id.switchFindScope);

        if (etFind != null) {
            etFind.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    lastSearchOffset = -1;
                }
            });

            etFind.setOnEditorActionListener((v, actionId, event) -> {
                String q = etFind.getText().toString().trim();
                if (!q.isEmpty()) performFind(true);
                return true;
            });
        }

        View btnFindNext = activity.findViewById(R.id.btnFindNext);
        if (btnFindNext != null) btnFindNext.setOnClickListener(v -> performFind(true));

        View btnFindPrev = activity.findViewById(R.id.btnFindPrev);
        if (btnFindPrev != null) btnFindPrev.setOnClickListener(v -> performFind(false));

        View btnReplace = activity.findViewById(R.id.btnReplace);
        if (btnReplace != null) btnReplace.setOnClickListener(v -> performReplace());

        View btnReplaceAll = activity.findViewById(R.id.btnReplaceAll);
        if (btnReplaceAll != null) {
            btnReplaceAll.setOnClickListener(v -> performReplaceAll());
        }

        View btnFindClose = activity.findViewById(R.id.btnFindClose);
        if (btnFindClose != null) btnFindClose.setOnClickListener(v -> hideFindBar());

        if (switchFindScope != null) {
            switchFindScope.setOnCheckedChangeListener((btn, checked) -> {
                findGlobalMode = checked;
                switchFindScope.setText(checked ? activity.getString(R.string.find_scope_project) : activity.getString(R.string.find_scope_file));
                etFind.setHint(checked ? activity.getString(R.string.find_scope_hint) : activity.getString(R.string.find_hint));
            });
        }
        if (lastTheme != null) applyTheme(lastTheme);
    }

    public void applyTheme(@NonNull AppTheme theme) {
        lastTheme = theme;
        if (findBar != null) findBar.setBackgroundColor(theme.toolbar);
        int editBg = com.ccs.wiredroid.util.Colors.blend(theme.consoleBg, theme.bg, 0.4f);
        if (etFind != null) {
            etFind.setTextColor(theme.text);
            etFind.setHintTextColor(theme.textDim);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setColor(editBg);
            gd.setCornerRadius(dp(4));
            gd.setStroke(dp(1), theme.separator);
            etFind.setBackground(gd);
        }
        if (etReplace != null) {
            etReplace.setTextColor(theme.text);
            etReplace.setHintTextColor(theme.textDim);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setColor(editBg);
            gd.setCornerRadius(dp(4));
            gd.setStroke(dp(1), theme.separator);
            etReplace.setBackground(gd);
        }
        if (switchFindScope != null) {
            switchFindScope.setTextColor(theme.accent);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                switchFindScope.setThumbTintList(android.content.res.ColorStateList.valueOf(theme.accent));
                switchFindScope.setTrackTintList(android.content.res.ColorStateList.valueOf(theme.separator));
            }
        }
        android.widget.TextView btnFindPrev = activity.findViewById(R.id.btnFindPrev);
        if (btnFindPrev != null) btnFindPrev.setTextColor(theme.text);
        android.widget.TextView btnFindNext = activity.findViewById(R.id.btnFindNext);
        if (btnFindNext != null) btnFindNext.setTextColor(theme.text);
        android.widget.TextView btnFindClose = activity.findViewById(R.id.btnFindClose);
        if (btnFindClose != null) btnFindClose.setTextColor(theme.textDim);
        android.widget.TextView btnReplace = activity.findViewById(R.id.btnReplace);
        if (btnReplace != null) btnReplace.setTextColor(theme.accent);
        android.widget.TextView btnReplaceAll = activity.findViewById(R.id.btnReplaceAll);
        if (btnReplaceAll != null) btnReplaceAll.setTextColor(theme.accent);
        if (findBar != null && findBar.getChildCount() > 0) {
            View lastChild = findBar.getChildAt(findBar.getChildCount() - 1);
            if (lastChild != null && !(lastChild instanceof android.widget.LinearLayout)) {
                lastChild.setBackgroundColor(theme.separator);
            }
        }
    }

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    public void toggleFindBar() {
        ensureBar();
        if (findBar == null) return;
        if (findBar.getVisibility() == View.VISIBLE) {
            hideFindBar();
        } else {
            findBar.setVisibility(View.VISIBLE);
            etFind.requestFocus();
            showKeyboard(etFind);
        }
    }

    public void hideFindBar() {
        if (findBar == null) return;
        findBar.setVisibility(View.GONE);
        lastSearchOffset = -1;
        if (etFind != null) etFind.setText("");
        if (etReplace != null) etReplace.setText("");
        hideKeyboard();
    }

    public boolean isVisible() {
        return findBar != null && findBar.getVisibility() == View.VISIBLE;
    }

    public boolean isFindBarVisible() {
        return isVisible();
    }

    public void focusFind() {
        if (etFind != null) {
            etFind.requestFocus();
            showKeyboard(etFind);
        }
    }

    public void performFind(boolean forward) {
        if (etFind == null) return;
        String query = etFind.getText().toString();
        if (query.isEmpty()) return;

        if (findGlobalMode) {
            callback.startGlobalSearch(query);
            return;
        }

        CodeEditor activeEditor = ws.activeEditor;
        if (activeEditor == null) return;

        String fullText = activeEditor.getText().toString();
        String textLower = fullText.toLowerCase();
        String queryLow = query.toLowerCase();

        int foundIdx;
        if (forward) {
            int start = (lastSearchOffset >= 0) ? lastSearchOffset + 1 : 0;
            foundIdx = textLower.indexOf(queryLow, start);
            if (foundIdx == -1) foundIdx = textLower.indexOf(queryLow);
        } else {
            int start = (lastSearchOffset > 0) ? lastSearchOffset - 1 : fullText.length() - 1;
            foundIdx = textLower.lastIndexOf(queryLow, start);
            if (foundIdx == -1) foundIdx = textLower.lastIndexOf(queryLow);
        }

        if (foundIdx == -1) {
            Toast.makeText(activity, activity.getString(R.string.find_not_found, query), Toast.LENGTH_SHORT).show();
            return;
        }
        lastSearchOffset = foundIdx;

        int[] s = offsetToLineCol(fullText, foundIdx);
        int[] e = offsetToLineCol(fullText, foundIdx + query.length());
        activeEditor.setSelectionRegion(s[0], s[1], e[0], e[1]);
    }

    public void performReplace() {
        if (etFind == null || etReplace == null) return;
        String query = etFind.getText().toString();
        String repl = etReplace.getText().toString();
        if (query.isEmpty() || lastSearchOffset < 0) return;

        CodeEditor activeEditor = ws.activeEditor;
        if (activeEditor == null) return;

        String text = activeEditor.getText().toString();
        int end = lastSearchOffset + query.length();
        if (end > text.length()) return;

        if (text.substring(lastSearchOffset, end).equalsIgnoreCase(query)) {
            String newText = text.substring(0, lastSearchOffset) + repl
                    + text.substring(end);
            activeEditor.setText(newText);
            TabsAdapter tabsAdapter = ws.tabs();
            if (tabsAdapter != null) {
                int idx = tabsAdapter.getActiveIndex();
                if (idx >= 0) tabsAdapter.markModified(idx, true);
            }
            lastSearchOffset = -1;
            performFind(true);
        }
    }

    public void performReplaceAll() {
        if (etFind == null || etReplace == null) return;
        String query = etFind.getText().toString();
        String repl = etReplace.getText().toString();
        if (query.isEmpty()) return;

        CodeEditor activeEditor = ws.activeEditor;
        if (activeEditor == null) return;

        String newContent = activeEditor.getText().toString().replace(query, repl);
        MainActivity.setEditorTextPreservingSelection(activeEditor, newContent);
        lastSearchOffset = -1;
        TabsAdapter tabsAdapter = ws.tabs();
        if (tabsAdapter != null) {
            int idx = tabsAdapter.getActiveIndex();
            if (idx >= 0) tabsAdapter.markModified(idx, true);
        }
    }

    private int[] offsetToLineCol(String text, int offset) {
        int line = 0, col = 0;
        int len = Math.min(offset, text.length());
        for (int i = 0; i < len; i++) {
            if (text.charAt(i) == '\n') { line++; col = 0; }
            else col++;
        }
        return new int[]{line, col};
    }

    private void showKeyboard(View view) {
        if (view == null) return;
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        View view = activity.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }
}
