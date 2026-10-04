package com.ccs.wiredroid.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.ScrollEvent;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * High-performance Minimap view with zero-allocation line traversal, cached Bitmap rendering,
 * and visibility-aware power saving.
 */
public class MinimapView extends View {

    private CodeEditor editor;
    private final Paint bgPaint = new Paint();
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vpBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vpBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint currentLineBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF viewportRect = new RectF();

    private Bitmap cacheBitmap;
    private Canvas cacheCanvas;
    private boolean cacheDirty = true;

    /** Long enough to swallow a burst of typing, short enough not to look stale. */
    private static final long CACHE_REBUILD_DEBOUNCE_MS = 200L;

    private final Runnable rebuildCache = () -> {
        if (getVisibility() != View.VISIBLE) return;
        cacheDirty = true;
        invalidate();
    };

    private int totalLines = 0;
    private float editorContentHeight = 1;
    private int cursorLine = 0;
    private boolean isDragging = false;

    private int colorText;
    private int colorKeyword;
    private int colorString;
    private int colorComment;
    private int colorNumber;
    private int colorAccent;

    private static final float VIEWPORT_RADIUS = 3f;

    public MinimapView(Context context) {
        super(context);
        init();
    }

    public MinimapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MinimapView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);

        bgPaint.setColor(0xCC1E1E1E);
        bgPaint.setStyle(Paint.Style.FILL);

        barPaint.setStyle(Paint.Style.FILL);
        barPaint.setStrokeCap(Paint.Cap.ROUND);

        vpBgPaint.setColor(0x28FFFFFF);
        vpBgPaint.setStyle(Paint.Style.FILL);

        vpBorderPaint.setColor(0x50FFFFFF);
        vpBorderPaint.setStyle(Paint.Style.STROKE);
        vpBorderPaint.setStrokeWidth(1.5f);

        cursorLinePaint.setColor(0x60FFD700);
        cursorLinePaint.setStyle(Paint.Style.FILL);

        currentLineBg.setColor(0x15FFFFFF);
        currentLineBg.setStyle(Paint.Style.FILL);

        colorText = 0xFFBBBBBB;
        colorKeyword = 0xFF569CD6;
        colorString = 0xFF6A9955;
        colorComment = 0xFF608B4E;
        colorNumber = 0xFFB5CEA8;
        colorAccent = 0xFFDCDCAA;
    }

    public void setEditor(CodeEditor editor) {
        this.editor = editor;
        if (editor != null) {
            editor.subscribeEvent(ScrollEvent.class, (event, subs) -> {
                if (getVisibility() == View.VISIBLE) postInvalidate();
            });
            editor.subscribeEvent(ContentChangeEvent.class, (event, subs) -> {
                if (getVisibility() != View.VISIBLE) {
                    cacheDirty = true;
                    return;
                }
                removeCallbacks(rebuildCache);
                postDelayed(rebuildCache, CACHE_REBUILD_DEBOUNCE_MS);
            });
        }
    }

    public void setThemeColors(int bg, int text, int keyword, int string, int comment,
                                int number, int accent, int vpBg, int vpBdr) {
        this.colorText = text;
        this.colorKeyword = keyword;
        this.colorString = string;
        this.colorComment = comment;
        this.colorNumber = number;
        this.colorAccent = accent;
        bgPaint.setColor(bg);
        vpBgPaint.setColor(vpBg);
        vpBorderPaint.setColor(vpBdr);
        cacheDirty = true;
        if (getVisibility() == View.VISIBLE) postInvalidate();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == View.VISIBLE) {
            cacheDirty = true;
            postInvalidate();
        } else {
            removeCallbacks(rebuildCache);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            cacheDirty = true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(rebuildCache);
        if (cacheBitmap != null && !cacheBitmap.isRecycled()) {
            cacheBitmap.recycle();
        }
        cacheBitmap = null;
        cacheCanvas = null;
        cacheDirty = true;
    }

    private void updateCache(int w, int h, Content text, float rowH) {
        if (cacheBitmap == null || cacheBitmap.getWidth() != w || cacheBitmap.getHeight() != h) {
            if (cacheBitmap != null && !cacheBitmap.isRecycled()) {
                cacheBitmap.recycle();
            }
            cacheBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            cacheCanvas = new Canvas(cacheBitmap);
        }

        cacheCanvas.drawRect(0, 0, w, h, bgPaint);
        cacheDirty = false;
        totalLines = text.getLineCount();
        if (totalLines == 0) return;

        editorContentHeight = totalLines * rowH;
        float scale = h / editorContentHeight;
        float lineH = Math.max(1.5f, rowH * scale);
        float padding = 2f;
        float maxBarWidth = w - padding * 2;
        float indentUnit = maxBarWidth / 100f;

        for (int i = 0; i < totalLines; i++) {
            float y = i * lineH;
            if (y > h) break;

            CharSequence cs;
            try {
                cs = text.getLine(i);
            } catch (Exception e) {
                continue;
            }
            if (cs == null || cs.length() == 0) continue;

            int len = cs.length();
            int firstNonWs = 0;
            int indentSpaces = 0;
            while (firstNonWs < len) {
                char c = cs.charAt(firstNonWs);
                if (c == ' ') indentSpaces++;
                else if (c == '\t') indentSpaces += 4;
                else break;
                firstNonWs++;
            }
            if (firstNonWs == len) continue; // empty or whitespace-only line

            int trimmedLen = len - firstNonWs;
            int indent = indentSpaces / 4;

            float barWidth;
            if (trimmedLen < 3) {
                barWidth = indentUnit * 2;
            } else {
                barWidth = Math.min(maxBarWidth - indent * indentUnit,
                        Math.max(indentUnit * 1.5f, trimmedLen * indentUnit * 0.55f));
            }
            if (barWidth < 1.5f) barWidth = 1.5f;

            float barX = padding + indent * indentUnit;
            float barY = y + lineH * 0.15f;
            float barH = Math.max(1.2f, lineH * 0.65f);

            int color = classifyLine(cs, firstNonWs, len);
            barPaint.setColor(color);
            barPaint.setAlpha(150);

            cacheCanvas.drawRoundRect(barX, barY, barX + barWidth, barY + barH, 1f, 1f, barPaint);
        }
        cacheDirty = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (editor == null || getVisibility() != View.VISIBLE) return;

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        Content text = editor.getText();
        if (text == null) {
            canvas.drawRect(0, 0, w, h, bgPaint);
            return;
        }

        float rowH = editor.getRowHeight();
        if (rowH <= 0) rowH = 14f;

        if (cacheDirty || cacheBitmap == null || cacheBitmap.getWidth() != w || cacheBitmap.getHeight() != h) {
            updateCache(w, h, text, rowH);
        }

        if (cacheBitmap != null && !cacheBitmap.isRecycled()) {
            canvas.drawBitmap(cacheBitmap, 0, 0, null);
        } else {
            canvas.drawRect(0, 0, w, h, bgPaint);
        }

        totalLines = text.getLineCount();
        if (totalLines == 0) return;
        editorContentHeight = totalLines * rowH;

        float editorH = editor.getHeight();
        float scrollY = editor.getScrollY();
        cursorLine = editor.getCursor().getLeftLine();

        float scale = h / editorContentHeight;
        float lineH = Math.max(1.5f, rowH * scale);

        // Current line highlight
        float clY = (cursorLine * rowH / editorContentHeight) * h;
        canvas.drawRect(0, clY - lineH * 0.3f, w, clY + lineH * 1.3f, currentLineBg);

        // Viewport rectangle
        float vpTop = Math.max(0, (scrollY / editorContentHeight) * h);
        float vpHeight = Math.max(4, (editorH / editorContentHeight) * h);
        viewportRect.set(0, vpTop, w, vpTop + vpHeight);
        canvas.drawRoundRect(viewportRect, VIEWPORT_RADIUS, VIEWPORT_RADIUS, vpBgPaint);
        canvas.drawRoundRect(viewportRect, VIEWPORT_RADIUS, VIEWPORT_RADIUS, vpBorderPaint);

        // Cursor line inside viewport
        float cursorY = (cursorLine * rowH / editorContentHeight) * h;
        canvas.drawRect(0, cursorY, w, Math.min(cursorY + 1.5f, h), cursorLinePaint);
    }

    /** Fast zero-allocation line classification directly examining CharSequence indices. */
    private int classifyLine(CharSequence cs, int start, int end) {
        if (start >= end) return colorText;
        char c0 = cs.charAt(start);
        if (c0 == '/' || c0 == '*') return colorComment;
        if (c0 == '@') return colorAccent;
        if (c0 >= '0' && c0 <= '9') return colorNumber;

        // Check for strings
        for (int i = start; i < end; i++) {
            char c = cs.charAt(i);
            if (c == '"' || c == '\'') return colorString;
        }

        // Check common keywords
        if (startsWith(cs, start, end, "import ") || startsWith(cs, start, end, "package ")) return colorKeyword;
        if (startsWith(cs, start, end, "public ") || startsWith(cs, start, end, "private ") || startsWith(cs, start, end, "protected ")) return colorKeyword;
        if (startsWith(cs, start, end, "class ") || startsWith(cs, start, end, "interface ") || startsWith(cs, start, end, "enum ")) return colorKeyword;
        if (startsWith(cs, start, end, "return ") || startsWith(cs, start, end, "throw ") || startsWith(cs, start, end, "new ")) return colorKeyword;
        if (startsWith(cs, start, end, "if ") || startsWith(cs, start, end, "for ") || startsWith(cs, start, end, "while ")) return colorKeyword;
        if (startsWith(cs, start, end, "static ") || startsWith(cs, start, end, "final ") || startsWith(cs, start, end, "void ")) return colorKeyword;

        // Method calls
        for (int i = start; i < end; i++) {
            if (cs.charAt(i) == '(') return colorAccent;
        }
        return colorText;
    }

    private static boolean startsWith(CharSequence cs, int start, int end, String prefix) {
        int pLen = prefix.length();
        if (end - start < pLen) return false;
        for (int i = 0; i < pLen; i++) {
            if (cs.charAt(start + i) != prefix.charAt(i)) return false;
        }
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (editor == null) return false;

        float y = event.getY();
        int h = getHeight();
        if (h <= 0) return false;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isDragging = true;
                scrollToPosition(y);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (isDragging) {
                    scrollToPosition(y);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void scrollToPosition(float minimapY) {
        if (editor == null) return;
        int h = getHeight();
        if (h <= 0) return;

        float ratio = minimapY / h;
        int totalRows = editor.getText().getLineCount();
        int targetRow = (int) (ratio * totalRows);
        targetRow = Math.max(0, Math.min(targetRow, totalRows - 1));

        float rowH = editor.getRowHeight();
        int targetScroll = (int) (targetRow * rowH - editor.getHeight() / 2f);
        targetScroll = Math.max(0, targetScroll);

        editor.scrollBy(0, targetScroll - (int) editor.getScrollY());
    }
}
