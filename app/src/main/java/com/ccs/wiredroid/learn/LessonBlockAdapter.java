package com.ccs.wiredroid.learn;

import com.ccs.wiredroid.util.Colors;

import android.text.SpannableString;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.R;

import java.util.IdentityHashMap;
import java.util.List;

/**
 * Рендерить блоки уроку нативно (без WebView).
 * Кольори з активної теми; код підсвічується через {@link JavaSyntaxHighlighter}.
 */
public class LessonBlockAdapter extends RecyclerView.Adapter<LessonBlockAdapter.VH> {

    public interface RunCallback {
        void onProgress(String message);
        void onResult(String output);
    }

    public interface OnRunSnippetListener {
        void onRunSnippet(LessonBlock block, RunCallback callback);
    }

    public interface OnOpenPlaygroundListener {
        void onOpenPlayground(LessonBlock block);
    }

    private List<LessonBlock> blocks;
    private AppTheme theme;
    private final OnRunSnippetListener runListener;
    private final OnOpenPlaygroundListener playgroundListener;
    private final IdentityHashMap<LessonBlock, RunState> runStates = new IdentityHashMap<>();

    public LessonBlockAdapter(List<LessonBlock> blocks, AppTheme theme,
                              OnRunSnippetListener runListener,
                              OnOpenPlaygroundListener playgroundListener) {
        this.blocks = blocks;
        this.theme = theme;
        this.runListener = runListener;
        this.playgroundListener = playgroundListener;
    }

    public void setBlocks(List<LessonBlock> blocks) {
        this.blocks = blocks;
        runStates.clear();
        notifyDataSetChanged();
    }

    public void setTheme(AppTheme theme) {
        this.theme = theme;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return blocks == null ? 0 : blocks.size();
    }

    @Override
    public int getItemViewType(int position) {
        return blocks.get(position).type;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(layoutFor(viewType), parent, false);
        return new VH(v);
    }

    /**
     * Every block type gets its own row layout.
     *
     * <p>A lesson is mostly paragraphs, and a paragraph row has no reason to pay
     * for inflating the code toolbar, its console and the note box just to hide
     * them again on the next line of {@link #onBindViewHolder}.</p>
     */
    private static int layoutFor(int type) {
        switch (type) {
            case LessonBlock.HEADING: return R.layout.item_lesson_heading;
            case LessonBlock.CODE:    return R.layout.item_lesson_code;
            case LessonBlock.LIST:    return R.layout.item_lesson_list;
            case LessonBlock.NOTE:
            case LessonBlock.WARNING: return R.layout.item_lesson_note;
            case LessonBlock.TABLE:   return R.layout.item_lesson_table;
            default:                  return R.layout.item_lesson_paragraph;
        }
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        LessonBlock b = blocks.get(position);
        hideAll(h);

        switch (b.type) {
            case LessonBlock.HEADING:
                h.heading.setVisibility(View.VISIBLE);
                h.heading.setText(b.text);
                h.heading.setTextColor(theme != null ? theme.text : 0xFFA9B7C6);
                break;
            case LessonBlock.PARAGRAPH:
                h.paragraph.setVisibility(View.VISIBLE);
                h.paragraph.setText(b.text);
                h.paragraph.setTextColor(theme != null ? theme.text : 0xFFC9CEC9);
                break;
            case LessonBlock.CODE:
                h.codeContainer.setVisibility(View.VISIBLE);
                SpannableString ss = JavaSyntaxHighlighter.highlight(b.text, theme);
                h.code.setText(ss);
                // фон код-блоку: злегка затемнена консоль
                h.code.setBackgroundColor(theme != null
                        ? Colors.blend(theme.consoleBg, theme.bg, 0.5f)
                        : 0xFF2B2B2B);
                h.code.setTextColor(theme != null ? theme.consoleText : 0xFFA9B7C6);
                bindRunnableCode(h, b);
                break;
            case LessonBlock.LIST:
                h.list.setVisibility(View.VISIBLE);
                StringBuilder sb = new StringBuilder();
                for (String item : b.text.split("\n")) {
                    sb.append("  •  ").append(item).append('\n');
                }
                if (sb.length() > 0) sb.setLength(sb.length() - 1);
                h.list.setText(sb.toString());
                h.list.setTextColor(theme != null ? theme.text : 0xFFC9CEC9);
                break;
            case LessonBlock.NOTE:
            case LessonBlock.WARNING:
                h.noteBox.setVisibility(View.VISIBLE);
                boolean warn = b.type == LessonBlock.WARNING;
                h.noteIcon.setText(warn ? "⚠️" : "💡");
                h.noteText.setText(b.text);
                int bg = warn
                        ? (0x55FFA500)
                        : (theme != null ? (theme.accent & 0x33FFFFFF) : 0x339876AA);
                h.noteBox.setBackgroundColor(bg);
                h.noteText.setTextColor(theme != null ? theme.text : 0xFFC9CEC9);
                break;
            case LessonBlock.TABLE:
                h.table.setVisibility(View.VISIBLE);
                StringBuilder tb = new StringBuilder();
                if (b.tableHeader != null) {
                    tb.append(b.tableHeader.replace("\t", "   ")).append('\n');
                    tb.append("─".repeat(40)).append('\n');
                }
                for (String row : b.text.split("\n")) {
                    tb.append(row.replace("\t", "   ")).append('\n');
                }
                if (tb.length() > 0) tb.setLength(tb.length() - 1);
                h.table.setText(tb.toString());
                h.table.setBackgroundColor(theme != null ? theme.consoleBg : 0xFF2B2B2B);
                h.table.setTextColor(theme != null ? theme.consoleText : 0xFFA9B7C6);
                break;
        }
    }

    /** A holder only carries the views of its own type, so the rest are null here. */
    private void hideAll(VH h) {
        hide(h.heading);
        hide(h.paragraph);
        hide(h.codeContainer);
        hide(h.codeToolbar);
        hide(h.codeConsole);
        unbind(h.playground);
        unbind(h.run);
        unbind(h.clear);
        unbind(h.close);
        hide(h.list);
        hide(h.noteBox);
        hide(h.table);
    }

    private static void hide(View v) {
        if (v != null) v.setVisibility(View.GONE);
    }

    private static void unbind(View v) {
        if (v != null) v.setOnClickListener(null);
    }

    private void bindRunnableCode(VH h, LessonBlock block) {
        int toolbarBg = theme != null ? Colors.blend(theme.consoleBg, theme.toolbar, 0.35f) : 0xFF252526;
        int consoleBg = theme != null ? theme.consoleBg : 0xFF1E1E1E;
        int dim = theme != null ? theme.textDim : 0xFF8A8A8A;
        int accent = theme != null ? theme.accent : 0xFF4A86C8;

        h.codeToolbar.setBackgroundColor(toolbarBg);
        h.codeLanguage.setTextColor(dim);
        if (h.playground != null) {
            h.playground.setTextColor(accent);
            h.playground.setOnClickListener(v -> {
                if (playgroundListener != null) {
                    playgroundListener.onOpenPlayground(block);
                }
            });
        }
        h.run.setTextColor(accent);
        h.codeConsole.setBackgroundColor(consoleBg);
        h.outputLabel.setTextColor(dim);
        h.clear.setTextColor(dim);
        h.close.setTextColor(dim);

        if (!block.isRunnable() || runListener == null) {
            h.codeToolbar.setVisibility(View.GONE);
            h.codeConsole.setVisibility(View.GONE);
            return;
        }

        h.codeToolbar.setVisibility(View.VISIBLE);
        RunState state = runStates.get(block);
        if (state == null) {
            state = new RunState();
            runStates.put(block, state);
        }
        final RunState boundState = state;

        h.run.setEnabled(!state.running);
        h.run.setAlpha(state.running ? 0.55f : 1f);
        h.run.setText(state.running ? R.string.lesson_running : R.string.lesson_run);
        h.codeConsole.setVisibility(state.expanded ? View.VISIBLE : View.GONE);
        h.output.setText(state.output);
        h.output.setTextColor(state.error && theme != null ? theme.errorText
                : (theme != null ? theme.consoleText : 0xFFA9B7C6));

        h.run.setOnClickListener(v -> {
            if (boundState.running) return;
            boundState.running = true;
            boundState.expanded = true;
            boundState.error = false;
            boundState.output = v.getContext().getString(R.string.lesson_running);
            notifyBlockChanged(block);
            runListener.onRunSnippet(block, new RunCallback() {
                @Override
                public void onProgress(String message) {
                    if (!boundState.running) return;
                    boundState.output = message == null ? "" : message;
                    notifyBlockChanged(block);
                }

                @Override
                public void onResult(String output) {
                    boundState.running = false;
                    boundState.expanded = true;
                    String value = output == null ? "" : output.trim();
                    boundState.output = value.isEmpty()
                            ? v.getContext().getString(R.string.lesson_no_output)
                            : value;
                    boundState.error = isErrorOutput(value);
                    notifyBlockChanged(block);
                }
            });
        });
        h.clear.setOnClickListener(v -> {
            boundState.output = "";
            boundState.error = false;
            notifyBlockChanged(block);
        });
        h.close.setOnClickListener(v -> {
            boundState.expanded = false;
            notifyBlockChanged(block);
        });
    }

    private void notifyBlockChanged(LessonBlock block) {
        if (blocks == null) return;
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i) == block) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    private static boolean isErrorOutput(String output) {
        return output.startsWith("Compilation Error")
                || output.startsWith("Execution Exception")
                || output.startsWith("System Error")
                || output.startsWith("Error:");
    }


    /**
     * Lets a long code line be scrolled without turning the page.
     *
     * <p>The lesson pager and a code block both want horizontal drags. Without
     * this the pager wins every time and code wider than the screen can never be
     * read. The block claims the gesture while it still has somewhere to go, and
     * hands it back at either end — so one more drag past the end turns the page,
     * which is what a reader expects.</p>
     */
    private static void keepCodeScrollFromThePager(HorizontalScrollView scroll) {
        if (scroll == null) return;
        scroll.setOnTouchListener(new View.OnTouchListener() {
            private float downX;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        break;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        View content = ((HorizontalScrollView) v).getChildAt(0);
                        int overflow = content == null ? 0
                                : content.getWidth() - v.getWidth()
                                        + v.getPaddingLeft() + v.getPaddingRight();
                        boolean scrollable = overflow > 0;
                        boolean draggingLeft = event.getX() < downX;
                        boolean atStart = v.getScrollX() <= 0;
                        boolean atEnd = v.getScrollX() >= overflow;
                        // Release only when this block cannot move any further
                        // in the direction of the drag.
                        boolean exhausted = !scrollable
                                || (draggingLeft && atEnd)
                                || (!draggingLeft && atStart);
                        v.getParent().requestDisallowInterceptTouchEvent(!exhausted);
                        break;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                    default:
                        break;
                }
                return false;   // the scroller still does its own scrolling
            }
        });
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView heading;
        final TextView paragraph;
        final LinearLayout codeContainer;
        final LinearLayout codeToolbar;
        final HorizontalScrollView codeScroll;
        final TextView code;
        final TextView codeLanguage;
        final TextView playground;
        final TextView run;
        final LinearLayout codeConsole;
        final TextView outputLabel;
        final TextView output;
        final TextView clear;
        final TextView close;
        final TextView list;
        final LinearLayout noteBox;
        final TextView noteIcon;
        final TextView noteText;
        final TextView table;

        VH(View v) {
            super(v);
            heading   = v.findViewById(R.id.blockHeading);
            paragraph = v.findViewById(R.id.blockParagraph);
            codeContainer = v.findViewById(R.id.blockCodeContainer);
            codeToolbar = v.findViewById(R.id.blockCodeToolbar);
            codeScroll= v.findViewById(R.id.blockCodeScroll);
            keepCodeScrollFromThePager(codeScroll);
            code      = v.findViewById(R.id.blockCode);
            codeLanguage = v.findViewById(R.id.blockCodeLanguage);
            playground = v.findViewById(R.id.blockCodePlayground);
            run = v.findViewById(R.id.blockCodeRun);
            codeConsole = v.findViewById(R.id.blockCodeConsole);
            outputLabel = v.findViewById(R.id.blockCodeOutputLabel);
            output = v.findViewById(R.id.blockCodeOutput);
            clear = v.findViewById(R.id.blockCodeClear);
            close = v.findViewById(R.id.blockCodeClose);
            list      = v.findViewById(R.id.blockList);
            noteBox   = v.findViewById(R.id.blockNoteBox);
            noteIcon  = v.findViewById(R.id.blockNoteIcon);
            noteText  = v.findViewById(R.id.blockNoteText);
            table     = v.findViewById(R.id.blockTable);
        }
    }

    private static final class RunState {
        boolean running;
        boolean expanded;
        boolean error;
        String output = "";
    }
}
