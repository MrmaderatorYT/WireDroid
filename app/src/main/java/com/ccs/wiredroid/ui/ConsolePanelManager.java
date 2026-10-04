package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppTheme;

/**
 * Manages the run output console, auto-scrolling, scroll-to-end action,
 * console clearing/copying, and console pane divider dragging.
 */
public final class ConsolePanelManager {

    public interface Callback {
        AppTheme getTheme();
        int getBottomPanelMode();
        boolean isLiveMetricsVisible();
        int getLiveMetricsHeight();
    }

    private final Activity activity;
    private final Callback callback;

    private ScrollView consoleScroll;
    /** Output and the input line together; this is what shows and hides. */
    private View panelRun;
    private View consoleInputRow;
    private TextView consoleOutput;

    /** Colour the running program left switched on, carried between its chunks. */
    private final com.ccs.wiredroid.util.AnsiParser.State ansiState =
            new com.ccs.wiredroid.util.AnsiParser.State();
    private View btnConsoleScrollEnd;
    private View consoleDivider;
    private boolean consoleAutoScroll = true;

    public ConsolePanelManager(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    public void bind() {
        consoleScroll = activity.findViewById(R.id.consoleScroll);
        panelRun = activity.findViewById(R.id.panelRun);
        consoleInputRow = activity.findViewById(R.id.consoleInputRow);
        consoleOutput = activity.findViewById(R.id.consoleOutput);
        btnConsoleScrollEnd = activity.findViewById(R.id.btnConsoleScrollEnd);
        consoleDivider = activity.findViewById(R.id.consoleDivider);

        setupConsoleScrollEnd();
    }

    public void applyTheme(@NonNull AppTheme theme) {
        if (consoleScroll != null) consoleScroll.setBackgroundColor(theme.consoleBg);
        if (consoleOutput != null) consoleOutput.setTextColor(theme.consoleText);
        if (consoleDivider != null) consoleDivider.setBackgroundColor(theme.separator);
        refreshScrollEndButton();
    }

    public void append(String text, int color) {
        appendConsole(text, color);
    }

    public void appendConsole(String text, int color) {
        appendConsole(text, color, true);
    }

    /**
     * Appends console text, optionally without ending the line.
     *
     * <p>Streamed program output already carries its own newlines, and a prompt
     * printed with {@code print} deliberately has none — adding one would break
     * the line the program meant to leave open for the answer.</p>
     */
    public void appendConsole(String text, int color, boolean newline) {
        if (consoleOutput == null || text == null) return;
        // An IDE-authored line — a separator, a progress note, an error — ends
        // whatever colour the program had left switched on, so it is parsed on
        // its own and clears the carried state.
        ansiState.reset();
        String line = newline ? text + "\n" : text;
        consoleOutput.append(com.ccs.wiredroid.util.AnsiParser.parse(line, color));
        if (consoleAutoScroll) scrollConsoleToEnd();
    }

    /**
     * A chunk straight from the running program, styled in its own right.
     *
     * <p>Separate from {@link #appendConsole} because a program sets a colour
     * once and then prints many lines under it, and the console appends those
     * lines one at a time. Sharing {@link #ansiState} across the chunks is what
     * keeps the second and third lines the colour the program asked for.</p>
     */
    public void appendProgramOutput(String chunk, int color) {
        if (consoleOutput == null || chunk == null || chunk.isEmpty()) return;
        consoleOutput.append(
                com.ccs.wiredroid.util.AnsiParser.parse(chunk, color, ansiState));
        if (consoleAutoScroll) scrollConsoleToEnd();
    }

    public void setVisible(boolean visible) {
        // The column, not just the scroll view: hiding only the output would
        // leave the input line floating over whichever panel took its place.
        View target = panelRun != null ? panelRun : consoleScroll;
        if (target != null) {
            target.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Shows the input line while a program is running.
     *
     * <p>Hidden otherwise, because there is nothing to type into: a line
     * submitted with no program waiting would go nowhere.</p>
     */
    public void setInputVisible(boolean visible) {
        if (consoleInputRow != null) {
            consoleInputRow.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    public void setText(String text) {
        if (consoleOutput != null) {
            consoleOutput.setText(text != null ? text : "");
        }
    }

    public void clear() {
        if (consoleOutput != null) {
            consoleOutput.setText("");
        }
        ansiState.reset();
        consoleAutoScroll = true;
        refreshScrollEndButton();
    }

    public String getText() {
        return consoleOutput != null ? consoleOutput.getText().toString() : "";
    }

    public void copyToClipboard() {
        if (consoleOutput == null) return;
        CharSequence text = consoleOutput.getText();
        if (text == null || text.length() == 0) {
            Toast.makeText(activity, R.string.toast_console_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("WireDroid console", text));
            Toast.makeText(activity, R.string.toast_copied_clipboard, Toast.LENGTH_SHORT).show();
        }
    }

    public void setupConsoleDivider(View editorsContainer, View bottomPanelContent) {
        if (consoleDivider == null || editorsContainer == null || bottomPanelContent == null) return;
        consoleDivider.setOnTouchListener(new View.OnTouchListener() {
            private float startY;
            private int startEditorHeight;
            private int startPanelHeight;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getRawY();
                        startEditorHeight = editorsContainer.getHeight();
                        startPanelHeight = bottomPanelContent.getHeight();
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float delta = event.getRawY() - startY;
                        int minEditorHeight = dp(80);
                        int minPanelHeight = dp(40);
                        int total = startEditorHeight + startPanelHeight;
                        if (total <= minEditorHeight + minPanelHeight) return true;

                        int newEditorHeight = (int) (startEditorHeight + delta);
                        if (newEditorHeight < minEditorHeight) {
                            newEditorHeight = minEditorHeight;
                        } else if (newEditorHeight > total - minPanelHeight) {
                            newEditorHeight = total - minPanelHeight;
                        }
                        int newPanelHeight = total - newEditorHeight;

                        LinearLayout.LayoutParams elp = (LinearLayout.LayoutParams) editorsContainer.getLayoutParams();
                        elp.weight = 0;
                        elp.height = newEditorHeight;
                        editorsContainer.setLayoutParams(elp);

                        LinearLayout.LayoutParams plp = (LinearLayout.LayoutParams) bottomPanelContent.getLayoutParams();
                        plp.weight = 0;
                        plp.height = newPanelHeight;
                        bottomPanelContent.setLayoutParams(plp);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(false);
                        }
                        return true;
                }
                return false;
            }
        });
    }

    public void setupConsoleScrollEnd() {
        if (consoleScroll == null) return;

        consoleScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            boolean atEnd = isConsoleAtEnd();
            if (atEnd != consoleAutoScroll) {
                consoleAutoScroll = atEnd;
                refreshScrollEndButton();
            }
        });

        if (btnConsoleScrollEnd != null) {
            btnConsoleScrollEnd.setOnClickListener(v -> {
                consoleAutoScroll = true;
                scrollConsoleToEnd();
                refreshScrollEndButton();
            });
        }
        refreshScrollEndButton();
    }

    public void scrollConsoleToEnd() {
        if (consoleScroll == null) return;
        consoleScroll.post(() -> {
            View content = consoleScroll.getChildAt(0);
            if (content != null) consoleScroll.scrollTo(0, content.getBottom());
        });
    }

    public boolean isConsoleAtEnd() {
        if (consoleScroll == null) return true;
        View content = consoleScroll.getChildAt(0);
        if (content == null) return true;
        int maxScroll = content.getBottom() - consoleScroll.getHeight();
        return consoleScroll.getScrollY() >= maxScroll - dp(8);
    }

    public void refreshScrollEndButton() {
        if (btnConsoleScrollEnd == null) return;

        boolean onRunPanel = callback.getBottomPanelMode() == 0; // 0 == PANEL_RUN
        btnConsoleScrollEnd.setVisibility(onRunPanel ? View.VISIBLE : View.GONE);
        if (!onRunPanel) return;

        AppTheme theme = callback.getTheme();
        if (theme != null) {
            Drawable bg = btnConsoleScrollEnd.getBackground();
            if (bg instanceof GradientDrawable) {
                ((GradientDrawable) bg.mutate()).setColor(theme.toolbar);
            }
            if (btnConsoleScrollEnd instanceof ImageView) {
                ((ImageView) btnConsoleScrollEnd).setImageTintList(
                        ColorStateList.valueOf(consoleAutoScroll ? theme.textDim : theme.accent));
            }
        }
        btnConsoleScrollEnd.setAlpha(consoleAutoScroll ? 0.45f : 1f);

        String label = activity.getString(consoleAutoScroll
                ? R.string.console_autoscroll_following
                : R.string.console_autoscroll_paused);
        btnConsoleScrollEnd.setContentDescription(label);
        btnConsoleScrollEnd.setTooltipText(label);

        positionScrollEndButton();
    }

    public void positionScrollEndButton() {
        if (btnConsoleScrollEnd == null) return;
        int strip = 0;
        if (callback.isLiveMetricsVisible()) {
            int h = callback.getLiveMetricsHeight();
            strip = h > 0 ? h : dp(56);
        }
        if (btnConsoleScrollEnd.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) btnConsoleScrollEnd.getLayoutParams();
            int wanted = dp(14) + strip;
            if (lp.bottomMargin != wanted) {
                lp.bottomMargin = wanted;
                btnConsoleScrollEnd.setLayoutParams(lp);
            }
        }
    }

    public ScrollView getScrollView() {
        return consoleScroll;
    }

    public TextView getTextView() {
        return consoleOutput;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
