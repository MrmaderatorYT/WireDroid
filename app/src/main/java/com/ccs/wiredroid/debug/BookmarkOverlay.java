package com.ccs.wiredroid.debug;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.HashSet;
import java.util.Set;

import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * Overlay для відображення закладок на рядках редактора.
 * Малює синій прапорець зліва від номера рядка.
 */
public class BookmarkOverlay extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path flagPath = new Path();
    private CodeEditor editor;
    private final Set<Integer> bookmarkLines = new HashSet<>();

    private static final int FLAG_COLOR = 0xFF2196F3;
    private static final int FLAG_SIZE_DP = 14;

    public BookmarkOverlay(Context context) {
        super(context);
        init();
    }

    public BookmarkOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BookmarkOverlay(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setTranslationZ(9f);

        float density = getResources().getDisplayMetrics().density;
        int flagSize = (int) (FLAG_SIZE_DP * density);

        paint.setColor(FLAG_COLOR);
        paint.setStyle(Paint.Style.FILL);

        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(flagSize * 0.65f);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setEditor(CodeEditor editor) {
        this.editor = editor;
    }

    public void setBookmarks(Set<Integer> lines) {
        bookmarkLines.clear();
        if (lines != null) bookmarkLines.addAll(lines);
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (editor == null) return;

        int rowHeight = editor.getRowHeight();
        if (rowHeight <= 0) return;

        int firstVisible = editor.getFirstVisibleRow();
        int lastVisible = editor.getLastVisibleRow();
        float offsetY = editor.getOffsetY();
        float density = getResources().getDisplayMetrics().density;
        int flagSize = (int) (FLAG_SIZE_DP * density);

        for (int line : bookmarkLines) {
            int row = line - 1;
            if (row < firstVisible || row > lastVisible) continue;

            float top = editor.getRowTop(row) - offsetY;
            float centerX = flagSize / 2f;
            float centerY = top + rowHeight / 2f;

            // Draw flag shape
            flagPath.reset();
            flagPath.moveTo(centerX - flagSize * 0.35f, centerY - flagSize * 0.4f);
            flagPath.lineTo(centerX + flagSize * 0.35f, centerY - flagSize * 0.4f);
            flagPath.lineTo(centerX + flagSize * 0.35f, centerY + flagSize * 0.15f);
            flagPath.lineTo(centerX, centerY + flagSize * 0.35f);
            flagPath.lineTo(centerX - flagSize * 0.35f, centerY + flagSize * 0.15f);
            flagPath.close();
            canvas.drawPath(flagPath, paint);

            // Draw bookmark number
            textPaint.setTextSize(flagSize * 0.5f);
            String num = String.valueOf(bookmarkLines.size());
            canvas.drawText(num, centerX, centerY + flagSize * 0.15f, textPaint);
        }
    }
}
