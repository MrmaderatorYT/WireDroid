package com.ccs.wiredroid.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import com.ccs.wiredroid.util.AppTheme;

import java.io.FileInputStream;
import java.util.Locale;

/**
 * Heap and processor use, sampled while a program runs.
 *
 * <p>Optimized for zero-allocation sampling and instant sleep when hidden.</p>
 */
public class LiveMetricsView extends View {

    /** Two minutes of history at the sampling interval below. */
    private static final int SAMPLES = 240;
    private static final long DEFAULT_PERIOD_MS = 500L;

    /** Set from PowerSavingManager, so the graph slows instead of stopping. */
    private long periodMs = DEFAULT_PERIOD_MS;

    private final float[] heapMb = new float[SAMPLES];
    private final float[] cpuPct = new float[SAMPLES];
    private int count;

    private float peakHeapMb;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private int heapColor = 0xFF4A86C8;
    private int cpuColor = 0xFFCC7832;
    private int textColor = 0xFF9A9A9A;
    private int gridColor = 0x22FFFFFF;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private boolean sampling;

    // Processor time is a counter, so a rate needs the previous reading.
    private long lastCpuTicks = -1;
    private long lastCpuAt;
    private final long clockTicksPerSecond = 100L;   // _SC_CLK_TCK on Android
    private final byte[] statBuffer = new byte[1024];

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!sampling || getVisibility() != View.VISIBLE) return;
            sample();
            invalidate();
            ticker.postDelayed(this, periodMs);
        }
    };

    public LiveMetricsView(Context context) {
        this(context, null);
    }

    public LiveMetricsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(1.5f));
        fill.setStyle(Paint.Style.FILL);
        label.setTextSize(dp(9f));
    }

    public void applyTheme(AppTheme theme) {
        if (theme == null) return;
        setBackgroundColor(theme.consoleBg);
        heapColor = theme.accent;
        cpuColor = theme.editorKeyword;
        textColor = theme.textDim;
        gridColor = (theme.textDim & 0x00FFFFFF) | 0x22000000;
        invalidate();
    }

    /** Milliseconds between samples; takes effect from the next tick. */
    public void setPeriodMs(long ms) {
        periodMs = Math.max(100L, ms);
    }

    /** Begins sampling and clears any previous run's trace. */
    public void start() {
        count = 0;
        peakHeapMb = 0f;
        lastCpuTicks = -1;
        sampling = true;
        ticker.removeCallbacks(tick);
        if (getVisibility() == View.VISIBLE) {
            ticker.post(tick);
        }
    }

    /** Stops sampling but leaves the trace on screen to be read afterwards. */
    public void stop() {
        sampling = false;
        ticker.removeCallbacks(tick);
        invalidate();
    }

    public boolean isSampling() {
        return sampling;
    }

    /** True while sampling or after at least one sample has been captured. */
    public boolean hasTrace() {
        return sampling || count > 0;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == View.VISIBLE && sampling) {
            ticker.removeCallbacks(tick);
            ticker.post(tick);
        } else {
            ticker.removeCallbacks(tick);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    // ── Sampling ────────────────────────────────────────────────────────────

    private void sample() {
        Runtime rt = Runtime.getRuntime();
        float mb = (rt.totalMemory() - rt.freeMemory()) / (1024f * 1024f);
        peakHeapMb = Math.max(peakHeapMb, mb);
        push(heapMb, mb);
        push(cpuPct, readCpuPercent());
        if (count < SAMPLES) count++;
    }

    private void push(float[] series, float value) {
        if (count < SAMPLES) {
            series[count] = value;
        } else {
            System.arraycopy(series, 1, series, 0, SAMPLES - 1);
            series[SAMPLES - 1] = value;
        }
    }

    /**
     * Processor use since the last sample, as a percentage of one core.
     *
     * <p>Read from {@code /proc/self/stat} fields 14 and 15 without String allocations.</p>
     */
    private float readCpuPercent() {
        long ticks = readProcessTicks();
        long now = android.os.SystemClock.elapsedRealtime();
        if (ticks < 0) return 0f;
        if (lastCpuTicks < 0) {
            lastCpuTicks = ticks;
            lastCpuAt = now;
            return 0f;
        }
        long deltaTicks = ticks - lastCpuTicks;
        long deltaMs = now - lastCpuAt;
        lastCpuTicks = ticks;
        lastCpuAt = now;
        if (deltaMs <= 0) return 0f;
        float seconds = deltaMs / 1000f;
        return Math.max(0f, (deltaTicks / (float) clockTicksPerSecond) / seconds * 100f);
    }

    private long readProcessTicks() {
        try (FileInputStream fis = new FileInputStream("/proc/self/stat")) {
            int len = fis.read(statBuffer, 0, statBuffer.length);
            if (len <= 0) return -1;

            // Find closing paren of comm field
            int closeParen = -1;
            for (int i = len - 1; i >= 0; i--) {
                if (statBuffer[i] == ')') {
                    closeParen = i;
                    break;
                }
            }
            if (closeParen < 0) return -1;

            // Fields after ')' start with field 3 (state).
            // utime is field 14 (index 11 after comm), stime is field 15 (index 12 after comm).
            int fieldIndex = 2;
            int idx = closeParen + 1;
            long utime = 0;
            long stime = 0;

            while (idx < len && fieldIndex <= 15) {
                // skip whitespace
                while (idx < len && (statBuffer[idx] == ' ' || statBuffer[idx] == '\t' || statBuffer[idx] == '\n')) {
                    idx++;
                }
                if (idx >= len) break;
                fieldIndex++;

                long val = 0;
                while (idx < len && statBuffer[idx] >= '0' && statBuffer[idx] <= '9') {
                    val = val * 10 + (statBuffer[idx] - '0');
                    idx++;
                }

                if (fieldIndex == 14) utime = val;
                if (fieldIndex == 15) {
                    stime = val;
                    return utime + stime;
                }
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ── Drawing ─────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        line.setColor(gridColor);
        line.setStrokeWidth(dp(0.5f));
        canvas.drawLine(0, h / 2f, w, h / 2f, line);
        line.setStrokeWidth(dp(1.5f));

        if (count < 2) {
            label.setColor(textColor);
            canvas.drawText(sampling ? "sampling…" : "no data",
                    dp(6f), h / 2f + dp(3f), label);
            return;
        }

        // Heap scales to its own peak so the shape fills the strip; the
        // processor axis is fixed at one core, so 50% always means 50%.
        float heapMax = Math.max(1f, peakHeapMb) * 1.15f;
        drawSeries(canvas, heapMb, heapMax, heapColor, w, h, true);
        drawSeries(canvas, cpuPct, 100f, cpuColor, w, h, false);

        label.setColor(heapColor);
        canvas.drawText(String.format(Locale.ROOT, "heap %.0f MB (peak %.0f)",
                heapMb[count - 1], peakHeapMb), dp(6f), dp(11f), label);
        label.setColor(cpuColor);
        canvas.drawText(String.format(Locale.ROOT, "cpu %.0f%%", cpuPct[count - 1]),
                dp(6f), h - dp(4f), label);
        label.setColor(textColor);
        String note = "process-wide";
        canvas.drawText(note, w - label.measureText(note) - dp(6f), dp(11f), label);
    }

    private void drawSeries(Canvas canvas, float[] series, float max,
                            int color, float w, float h, boolean shade) {
        if (max <= 0) return;
        float step = w / (SAMPLES - 1f);
        // Newest sample sits at the right edge, so a short run grows inward.
        float offset = w - (count - 1) * step;

        path.reset();
        for (int i = 0; i < count; i++) {
            float x = offset + i * step;
            float y = h - Math.min(1f, series[i] / max) * (h - dp(14f)) - dp(2f);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        line.setColor(color);
        canvas.drawPath(path, line);

        if (shade) {
            path.lineTo(w, h);
            path.lineTo(offset, h);
            path.close();
            fill.setColor((color & 0x00FFFFFF) | 0x22000000);
            canvas.drawPath(path, fill);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
