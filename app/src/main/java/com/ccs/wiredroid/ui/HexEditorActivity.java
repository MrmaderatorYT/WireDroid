package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Hex editor for binary files: offset, hex and ASCII columns with byte-level
 * editing, insert/delete, search, go-to-offset and undo/redo.
 *
 * <p>The whole file is held in memory, so a size ceiling applies — see
 * {@link #MAX_FILE_BYTES}. Beyond it the file is opened read-only on a truncated
 * view rather than silently showing partial data as if it were the whole file.</p>
 */
public class HexEditorActivity extends AppCompatActivity {

    private static final String EXTRA_FILE_PATH = "file_path";
    private static final String EXTRA_READ_ONLY = "read_only";

    /** 16 MiB — comfortably editable in memory on a phone. */
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final int BYTES_PER_ROW = 16;
    private static final int UNDO_LIMIT = 200;
    /** Rows are formatted a byte at a time, where a Formatter per byte is far too costly. */
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    private AppPreferences prefs;
    private AppTheme theme;
    private Typeface mono;

    private File file;
    private byte[] data = new byte[0];
    private boolean readOnly;
    private boolean truncated;
    private boolean dirty;
    private int selectedOffset = -1;

    private final Deque<Edit> undoStack = new ArrayDeque<>();
    private final Deque<Edit> redoStack = new ArrayDeque<>();

    private HexAdapter adapter;
    private RecyclerView recycler;
    private TextView statusBar;
    private Toolbar toolbar;

    /** One reversible change. A replacement carries equal-length before/after. */
    private static final class Edit {
        final int offset;
        final byte[] before;
        final byte[] after;

        Edit(int offset, byte[] before, byte[] after) {
            this.offset = offset;
            this.before = before;
            this.after = after;
        }
    }

    public static void launch(Context context, File target) {
        launch(context, target, false);
    }

    /** Opens {@code target}; pass {@code readOnly} to forbid edits and saving. */
    public static void launch(Context context, File target, boolean readOnly) {
        Intent i = new Intent(context, HexEditorActivity.class);
        i.putExtra(EXTRA_FILE_PATH, target.getAbsolutePath());
        i.putExtra(EXTRA_READ_ONLY, readOnly);
        context.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);
        super.onCreate(savedInstanceState);
        mono = prefs.resolveTypeface();

        String path = getIntent().getStringExtra(EXTRA_FILE_PATH);
        readOnly = getIntent().getBooleanExtra(EXTRA_READ_ONLY, false);
        if (path == null) {
            Toast.makeText(this, R.string.hex_no_file, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        file = new File(path);

        setContentView(buildRoot());
        FullScreenHelper.enable(this);
        loadFile();
    }

    // ─── UI ─────────────────────────────────────────────────────────────────

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle(R.string.hex_title);
        toolbar.setTitleTextColor(theme.text);
        toolbar.setSubtitleTextColor(theme.textDim);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> confirmExit());
        setSupportActionBar(toolbar);
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView header = new TextView(this);
        header.setTypeface(mono);
        header.setTextSize(11);
        header.setTextColor(theme.textDim);
        header.setBackgroundColor(theme.statusBar);
        header.setPadding(dp(8), dp(4), dp(8), dp(4));
        header.setText(buildHeaderLine());
        HorizontalScrollView headerScroll = new HorizontalScrollView(this);
        headerScroll.setHorizontalScrollBarEnabled(false);
        headerScroll.addView(header);
        root.addView(headerScroll);

        recycler = new RecyclerView(this);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HexAdapter();
        recycler.setAdapter(adapter);
        recycler.setBackgroundColor(theme.consoleBg);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(recycler, listParams);

        statusBar = new TextView(this);
        statusBar.setTypeface(mono);
        statusBar.setTextSize(11);
        statusBar.setTextColor(theme.textDim);
        statusBar.setBackgroundColor(theme.statusBar);
        statusBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        root.addView(statusBar);

        return root;
    }

    /** Column ruler: {@code Offset   00 01 02 … 0F   ASCII}. */
    private String buildHeaderLine() {
        StringBuilder sb = new StringBuilder("Offset    ");
        for (int i = 0; i < BYTES_PER_ROW; i++) {
            sb.append(String.format(Locale.US, "%02X ", i));
        }
        sb.append("  ").append(getString(R.string.hex_column_ascii));
        return sb.toString();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.hex_menu_goto).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, 2, 1, R.string.hex_menu_find).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, 3, 2, R.string.hex_menu_undo).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, 4, 3, R.string.hex_menu_redo).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, 5, 4, R.string.hex_menu_save).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, 6, 5, R.string.hex_menu_info).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case 1: showGotoDialog(); return true;
            case 2: showFindDialog(); return true;
            case 3: undo(); return true;
            case 4: redo(); return true;
            case 5: saveFile(); return true;
            case 6: showFileInfo(); return true;
            default: return super.onOptionsItemSelected(item);
        }
    }

    // ─── Loading and saving ─────────────────────────────────────────────────

    private void loadFile() {
        // Up to MAX_FILE_BYTES come off flash here, so the read stays off the UI
        // thread; the list is empty until the bytes are in hand.
        new Thread(() -> {
            boolean missing = !file.isFile();
            byte[] loaded = null;
            boolean cut = false;
            boolean lockWrites = false;
            String failure = null;
            if (!missing) {
                try {
                    long length = file.length();
                    if (length > MAX_FILE_BYTES) {
                        cut = true;
                        lockWrites = true;
                        loaded = new byte[MAX_FILE_BYTES];
                        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
                            int read = 0;
                            while (read < MAX_FILE_BYTES) {
                                int n = in.read(loaded, read, MAX_FILE_BYTES - read);
                                if (n < 0) break;
                                read += n;
                            }
                        }
                    } else {
                        loaded = Files.readAllBytes(file.toPath());
                    }
                    if (!file.canWrite()) lockWrites = true;
                } catch (IOException e) {
                    failure = e.getMessage();
                }
            }
            final boolean notAFile = missing;
            final byte[] bytes = loaded;
            final boolean wasTruncated = cut;
            final boolean lockedWrites = lockWrites;
            final String error = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (notAFile) {
                    Toast.makeText(this, getString(R.string.hex_cannot_read, file.getName()),
                            Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                if (error != null) {
                    Toast.makeText(this, getString(R.string.hex_cannot_read, error),
                            Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                data = bytes;
                truncated = wasTruncated;
                if (lockedWrites) readOnly = true;
                if (wasTruncated) {
                    Toast.makeText(this, getString(R.string.hex_truncated,
                            MAX_FILE_BYTES / (1024 * 1024)), Toast.LENGTH_LONG).show();
                }
                adapter.notifyDataSetChanged();
                updateStatus();
                toolbar.setSubtitle(file.getName());
            });
        }, "hex-load").start();
    }

    private void saveFile() {
        if (readOnly) {
            Toast.makeText(this, truncated ? R.string.hex_readonly_truncated : R.string.hex_readonly,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!dirty) {
            Toast.makeText(this, R.string.hex_nothing_to_save, Toast.LENGTH_SHORT).show();
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hex_menu_save)
                .setMessage(getString(R.string.hex_save_confirm, file.getName(), data.length))
                .setPositiveButton(R.string.hex_menu_save, (d, w) -> writeBytes())
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void writeBytes() {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.toast_save_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
            return;
        }
        dirty = false;
        updateStatus();
        setResult(Activity.RESULT_OK);
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
    }

    private void confirmExit() {
        if (!dirty) { finish(); return; }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hex_unsaved_title)
                .setMessage(R.string.hex_unsaved_message)
                .setPositiveButton(R.string.hex_menu_save, (d, w) -> writeBytes())
                .setNegativeButton(R.string.hex_discard, (d, w) -> finish())
                .setNeutralButton(R.string.dialog_cancel, null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (dirty) {
            confirmExit();
            return;
        }
        super.onBackPressed();
    }

    // ─── Editing ────────────────────────────────────────────────────────────

    /** Replaces {@code length} bytes at {@code offset}, recording it for undo. */
    private void applyEdit(int offset, byte[] replacement, int length) {
        if (offset < 0 || offset > data.length) return;
        int end = Math.min(data.length, offset + length);
        byte[] before = new byte[end - offset];
        System.arraycopy(data, offset, before, 0, before.length);

        byte[] next = new byte[data.length - before.length + replacement.length];
        System.arraycopy(data, 0, next, 0, offset);
        System.arraycopy(replacement, 0, next, offset, replacement.length);
        System.arraycopy(data, end, next, offset + replacement.length, data.length - end);

        data = next;
        pushUndo(new Edit(offset, before, replacement));
        dirty = true;
        // A same-length change repaints one row; a size change shifts everything.
        if (before.length == replacement.length) {
            adapter.notifyItemRangeChanged(offset / BYTES_PER_ROW,
                    (replacement.length / BYTES_PER_ROW) + 2);
        } else {
            adapter.notifyDataSetChanged();
        }
        updateStatus();
    }

    private void pushUndo(Edit edit) {
        undoStack.push(edit);
        while (undoStack.size() > UNDO_LIMIT) undoStack.removeLast();
        redoStack.clear();
    }

    private void undo() {
        Edit edit = undoStack.poll();
        if (edit == null) {
            Toast.makeText(this, R.string.hex_nothing_to_undo, Toast.LENGTH_SHORT).show();
            return;
        }
        data = splice(data, edit.offset, edit.after.length, edit.before);
        redoStack.push(edit);
        dirty = true;
        adapter.notifyDataSetChanged();
        updateStatus();
    }

    private void redo() {
        Edit edit = redoStack.poll();
        if (edit == null) {
            Toast.makeText(this, R.string.hex_nothing_to_redo, Toast.LENGTH_SHORT).show();
            return;
        }
        data = splice(data, edit.offset, edit.before.length, edit.after);
        undoStack.push(edit);
        dirty = true;
        adapter.notifyDataSetChanged();
        updateStatus();
    }

    /** Returns {@code source} with {@code removeLength} bytes at {@code offset} replaced. */
    private static byte[] splice(byte[] source, int offset, int removeLength, byte[] insert) {
        int safeOffset = Math.max(0, Math.min(offset, source.length));
        int end = Math.min(source.length, safeOffset + removeLength);
        byte[] out = new byte[source.length - (end - safeOffset) + insert.length];
        System.arraycopy(source, 0, out, 0, safeOffset);
        System.arraycopy(insert, 0, out, safeOffset, insert.length);
        System.arraycopy(source, end, out, safeOffset + insert.length, source.length - end);
        return out;
    }

    /** Byte editor: hex, decimal and character views of one byte. */
    private void showByteDialog(int offset) {
        if (offset < 0 || offset >= data.length) return;
        selectedOffset = offset;
        adapter.notifyItemChanged(offset / BYTES_PER_ROW);
        updateStatus();

        int value = data[offset] & 0xFF;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView info = new TextView(this);
        info.setTypeface(mono);
        info.setTextColor(theme.textDim);
        info.setTextSize(12);
        info.setText(getString(R.string.hex_byte_info, offset, offset, value,
                describeChar(value), toBinary(value)));
        box.addView(info);

        EditText input = new EditText(this);
        input.setTypeface(mono);
        input.setTextColor(theme.text);
        input.setHintTextColor(theme.textDim);
        input.setHint(R.string.hex_byte_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(String.format(Locale.US, "%02X", value));
        input.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        input.setLayoutParams(lp);
        box.addView(input);

        if (readOnly) {
            input.setEnabled(false);
            TextView note = new TextView(this);
            note.setTextColor(theme.errorText);
            note.setTextSize(11);
            note.setText(truncated ? R.string.hex_readonly_truncated : R.string.hex_readonly);
            box.addView(note);
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.hex_byte_title, offset))
                        .setView(box)
                        .setNegativeButton(R.string.dialog_cancel, null);

        if (!readOnly) {
            builder.setPositiveButton(R.string.dialog_apply, (d, w) -> {
                Integer parsed = parseByte(input.getText().toString());
                if (parsed == null) {
                    Toast.makeText(this, R.string.hex_invalid_byte, Toast.LENGTH_SHORT).show();
                    return;
                }
                applyEdit(offset, new byte[]{(byte) parsed.intValue()}, 1);
            });
            builder.setNeutralButton(R.string.hex_more, (d, w) -> showByteActions(offset));
        }
        builder.show();
    }

    /** Insert / delete / fill actions around one offset. */
    private void showByteActions(int offset) {
        String[] options = {
                getString(R.string.hex_action_insert_before),
                getString(R.string.hex_action_insert_after),
                getString(R.string.hex_action_delete),
                getString(R.string.hex_action_fill_row),
        };
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.hex_byte_title, offset))
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0: applyEdit(offset, new byte[]{0}, 0); break;
                        case 1: applyEdit(Math.min(data.length, offset + 1), new byte[]{0}, 0); break;
                        case 2: applyEdit(offset, new byte[0], 1); break;
                        case 3: fillRow(offset); break;
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void fillRow(int offset) {
        EditText input = newInput(getString(R.string.hex_byte_hint), "00");
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hex_action_fill_row)
                .setView(input)
                .setPositiveButton(R.string.dialog_apply, (d, w) -> {
                    Integer parsed = parseByte(input.getText().toString());
                    if (parsed == null) {
                        Toast.makeText(this, R.string.hex_invalid_byte, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int rowStart = (offset / BYTES_PER_ROW) * BYTES_PER_ROW;
                    int count = Math.min(BYTES_PER_ROW, data.length - rowStart);
                    byte[] fill = new byte[count];
                    java.util.Arrays.fill(fill, (byte) parsed.intValue());
                    applyEdit(rowStart, fill, count);
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /**
     * Parses a byte value. Hex is the default because that is how the field is
     * presented; {@code 'c'} gives a character and {@code 200d} a decimal.
     *
     * @return 0–255, or {@code null} when the text is not a valid byte
     */
    static Integer parseByte(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // Character literal: 'A'
        if (s.length() == 3 && s.charAt(0) == '\'' && s.charAt(2) == '\'') {
            int value = s.charAt(1);
            return value <= 255 ? value : null;
        }
        int radix = 16;
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        } else if (s.startsWith("#")) {
            s = s.substring(1);
        } else if (s.length() > 1 && (s.endsWith("d") || s.endsWith("D"))
                && s.substring(0, s.length() - 1).matches("[0-9]+")) {
            s = s.substring(0, s.length() - 1);
            radix = 10;
        }
        try {
            int value = Integer.parseInt(s, radix);
            return (value < 0 || value > 255) ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ─── Navigation and search ──────────────────────────────────────────────

    private void showGotoDialog() {
        EditText input = newInput(getString(R.string.hex_goto_hint), "");
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hex_menu_goto)
                .setView(input)
                .setPositiveButton(R.string.hex_go, (d, w) -> {
                    String s = input.getText().toString().trim();
                    if (s.isEmpty()) return;
                    long offset;
                    try {
                        offset = s.startsWith("0x") || s.startsWith("0X")
                                ? Long.parseLong(s.substring(2), 16)
                                : Long.parseLong(s, 16);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, R.string.hex_invalid_offset, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (offset < 0 || offset >= data.length) {
                        Toast.makeText(this, R.string.hex_offset_out_of_range, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    scrollToOffset((int) offset);
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showFindDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView hint = new TextView(this);
        hint.setTextColor(theme.textDim);
        hint.setTextSize(11);
        hint.setText(R.string.hex_find_hint);
        box.addView(hint);

        EditText input = newInput(getString(R.string.hex_find_placeholder), "");
        box.addView(input);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hex_menu_find)
                .setView(box)
                .setPositiveButton(R.string.hex_find_next, (d, w) -> {
                    byte[] pattern = parsePattern(input.getText().toString());
                    if (pattern == null || pattern.length == 0) {
                        Toast.makeText(this, R.string.hex_invalid_pattern, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int from = selectedOffset >= 0 ? selectedOffset + 1 : 0;
                    int found = indexOf(data, pattern, from);
                    if (found < 0 && from > 0) found = indexOf(data, pattern, 0);   // wrap around
                    if (found < 0) {
                        Toast.makeText(this, R.string.hex_not_found, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    selectedOffset = found;
                    scrollToOffset(found);
                    adapter.notifyDataSetChanged();
                    updateStatus();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /**
     * Parses a search pattern: {@code "text"} for literal bytes, otherwise a run
     * of hex pairs with optional separators ({@code DE AD BE EF}, {@code deadbeef}).
     */
    static byte[] parsePattern(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        String compact = s.replaceAll("[\\s,:;\\-]|0[xX]", "");
        if (compact.isEmpty() || compact.length() % 2 != 0) return null;
        if (!compact.matches("[0-9A-Fa-f]+")) return null;
        byte[] out = new byte[compact.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(compact.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** First index at or after {@code from} where {@code pattern} occurs, or -1. */
    static int indexOf(byte[] haystack, byte[] pattern, int from) {
        if (pattern.length == 0 || pattern.length > haystack.length) return -1;
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (haystack[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private void scrollToOffset(int offset) {
        selectedOffset = offset;
        int row = offset / BYTES_PER_ROW;
        LinearLayoutManager lm = (LinearLayoutManager) recycler.getLayoutManager();
        if (lm != null) lm.scrollToPositionWithOffset(row, dp(48));
        adapter.notifyDataSetChanged();
        updateStatus();
    }

    private void showFileInfo() {
        String message = getString(R.string.hex_info_body,
                file.getAbsolutePath(),
                data.length,
                humanSize(data.length),
                detectFileType(data),
                readOnly ? getString(R.string.hex_info_readonly) : getString(R.string.hex_info_writable));
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hex_menu_info)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Identifies a file from its leading magic bytes. Only formats the app itself
     * handles are listed; anything else reports as binary or text.
     */
    static String detectFileType(byte[] b) {
        if (b.length >= 4) {
            if (u(b, 0) == 0xCA && u(b, 1) == 0xFE && u(b, 2) == 0xBA && u(b, 3) == 0xBE) {
                return "Java class (0xCAFEBABE)";
            }
            if (u(b, 0) == 0x50 && u(b, 1) == 0x4B) return "ZIP / JAR / APK";
            if (u(b, 0) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "PNG image";
            if (u(b, 0) == 0xFF && u(b, 1) == 0xD8 && u(b, 2) == 0xFF) return "JPEG image";
            if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "GIF image";
            if (b[0] == 'd' && b[1] == 'e' && b[2] == 'x') return "Android DEX";
            if (u(b, 0) == 0x7F && b[1] == 'E' && b[2] == 'L' && b[3] == 'F') return "ELF binary";
            if (u(b, 0) == 0x1A && u(b, 1) == 0x45 && u(b, 2) == 0xDF && u(b, 3) == 0xA3) {
                return "Matroska / WebM";
            }
            if (b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return "PDF";
            if (b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') return "Ogg";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') {
            if (b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "WebP image";
            return "RIFF (WAV/AVI)";
        }
        if (b.length >= 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            return "ISO BMFF (MP4/MOV)";
        }
        return looksLikeText(b) ? "Text" : "Binary";
    }

    private static int u(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    /** Heuristic: mostly printable in the first kilobyte and no NUL bytes. */
    private static boolean looksLikeText(byte[] b) {
        int limit = Math.min(b.length, 1024);
        if (limit == 0) return true;
        int printable = 0;
        for (int i = 0; i < limit; i++) {
            int v = b[i] & 0xFF;
            if (v == 0) return false;
            if (v == '\n' || v == '\r' || v == '\t' || (v >= 0x20 && v < 0x7F) || v >= 0x80) {
                printable++;
            }
        }
        return printable * 100 / limit >= 95;
    }

    // ─── Rows ───────────────────────────────────────────────────────────────

    private class HexAdapter extends RecyclerView.Adapter<RowHolder> {

        @NonNull
        @Override
        public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(HexEditorActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(8), dp(2), dp(8), dp(2));

            TextView offset = new TextView(HexEditorActivity.this);
            offset.setTypeface(mono);
            offset.setTextSize(11);
            offset.setTextColor(theme.textDim);

            TextView hex = new TextView(HexEditorActivity.this);
            hex.setTypeface(mono);
            hex.setTextSize(11);
            hex.setTextColor(theme.consoleText);
            hex.setPadding(dp(8), 0, dp(8), 0);

            TextView ascii = new TextView(HexEditorActivity.this);
            ascii.setTypeface(mono);
            ascii.setTextSize(11);
            ascii.setTextColor(theme.editorString);

            row.addView(offset);
            row.addView(hex);
            row.addView(ascii);

            HorizontalScrollView scroll = new HorizontalScrollView(HexEditorActivity.this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.addView(row);
            return new RowHolder(scroll, offset, hex, ascii);
        }

        @Override
        public void onBindViewHolder(@NonNull RowHolder holder, int position) {
            int rowStart = position * BYTES_PER_ROW;
            int count = Math.min(BYTES_PER_ROW, data.length - rowStart);
            if (count < 0) count = 0;

            holder.offset.setText(hexOffset(rowStart));

            StringBuilder hex = new StringBuilder(BYTES_PER_ROW * 3);
            StringBuilder ascii = new StringBuilder(BYTES_PER_ROW);
            for (int i = 0; i < BYTES_PER_ROW; i++) {
                if (i < count) {
                    int v = data[rowStart + i] & 0xFF;
                    hex.append(HEX_DIGITS[v >>> 4]).append(HEX_DIGITS[v & 0xF]).append(' ');
                    ascii.append(v >= 0x20 && v < 0x7F ? (char) v : '.');
                } else {
                    hex.append("   ");
                    ascii.append(' ');
                }
            }

            // Highlight the selected byte in both columns.
            if (selectedOffset >= rowStart && selectedOffset < rowStart + count) {
                int i = selectedOffset - rowStart;
                SpannableString hexSpan = new SpannableString(hex.toString());
                int hexStart = i * 3;
                hexSpan.setSpan(new BackgroundColorSpan(withAlpha(theme.accent, 0x66)),
                        hexStart, hexStart + 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                hexSpan.setSpan(new ForegroundColorSpan(theme.text),
                        hexStart, hexStart + 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                holder.hex.setText(hexSpan);

                SpannableString asciiSpan = new SpannableString(ascii.toString());
                asciiSpan.setSpan(new BackgroundColorSpan(withAlpha(theme.accent, 0x66)),
                        i, i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                holder.ascii.setText(asciiSpan);
            } else {
                holder.hex.setText(hex.toString());
                holder.ascii.setText(ascii.toString());
            }

            final int finalCount = count;
            // Tapping the hex column picks the exact byte under the finger; the
            // column is fixed-width, so the character offset maps to a byte index.
            holder.hex.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                    int charOffset = ((TextView) v).getOffsetForPosition(event.getX(), event.getY());
                    int byteIndex = Math.min(finalCount - 1, Math.max(0, charOffset / 3));
                    if (finalCount > 0) showByteDialog(rowStart + byteIndex);
                }
                return true;
            });
            holder.ascii.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                    int charOffset = ((TextView) v).getOffsetForPosition(event.getX(), event.getY());
                    int byteIndex = Math.min(finalCount - 1, Math.max(0, charOffset));
                    if (finalCount > 0) showByteDialog(rowStart + byteIndex);
                }
                return true;
            });
            holder.offset.setOnClickListener(v -> {
                if (finalCount > 0) showByteDialog(rowStart);
            });
        }

        @Override
        public int getItemCount() {
            if (data.length == 0) return 0;
            return (data.length + BYTES_PER_ROW - 1) / BYTES_PER_ROW;
        }
    }

    private static class RowHolder extends RecyclerView.ViewHolder {
        final TextView offset;
        final TextView hex;
        final TextView ascii;

        RowHolder(View root, TextView offset, TextView hex, TextView ascii) {
            super(root);
            this.offset = offset;
            this.hex = hex;
            this.ascii = ascii;
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.hex_status_size, data.length, humanSize(data.length)));
        if (selectedOffset >= 0 && selectedOffset < data.length) {
            sb.append("   ").append(getString(R.string.hex_status_offset,
                    selectedOffset, data[selectedOffset] & 0xFF));
        }
        if (readOnly) sb.append("   🔒");
        if (dirty) sb.append("   ●");
        statusBar.setText(sb.toString());
    }

    private EditText newInput(String hint, String value) {
        EditText input = new EditText(this);
        input.setTypeface(mono);
        input.setHint(hint);
        input.setText(value);
        input.setTextColor(theme.text);
        input.setHintTextColor(theme.textDim);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSelectAllOnFocus(true);
        int pad = dp(12);
        input.setPadding(pad, pad, pad, pad);
        return input;
    }

    /** Eight uppercase hex digits, as {@code %08X} would render them. */
    private static String hexOffset(int value) {
        char[] out = new char[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = HEX_DIGITS[value & 0xF];
            value >>>= 4;
        }
        return new String(out);
    }

    private static String describeChar(int value) {
        if (value >= 0x20 && value < 0x7F) return "'" + (char) value + "'";
        switch (value) {
            case 0x00: return "NUL";
            case 0x09: return "TAB";
            case 0x0A: return "LF";
            case 0x0D: return "CR";
            case 0x1B: return "ESC";
            case 0x7F: return "DEL";
            default: return "—";
        }
    }

    private static String toBinary(int value) {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 7; i >= 0; i--) sb.append((value >> i) & 1);
        return sb.toString();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
