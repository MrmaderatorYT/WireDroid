package com.ccs.wiredroid.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Image surface with pinch zoom, drag pan, double-tap zoom and 90° rotation.
 *
 * <p>Everything is done with a single {@link Matrix}, so animated drawables keep
 * animating while transformed. A checkerboard can be drawn underneath to make
 * transparency visible.</p>
 */
public class ZoomableImageView extends View {

    /** Zoom bounds relative to the fit-to-window scale. */
    private static final float MIN_SCALE_FACTOR = 0.25f;
    private static final float MAX_SCALE = 16f;

    private Drawable drawable;
    private final Matrix matrix = new Matrix();
    private final RectF drawableRect = new RectF();
    private final RectF mappedRect = new RectF();
    private final float[] values = new float[9];

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    private int rotationDegrees;
    private boolean checkerboard;
    private int backgroundTint = Color.TRANSPARENT;
    private Paint lightSquare;
    private Paint darkSquare;
    private int checkerSize = 16;

    /** Notified whenever the zoom level changes, so a caller can show it. */
    public interface OnScaleChangeListener {
        void onScaleChanged(float percent);
    }

    private OnScaleChangeListener scaleListener;

    public ZoomableImageView(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        checkerSize = (int) (8 * context.getResources().getDisplayMetrics().density);

        lightSquare = new Paint();
        lightSquare.setColor(0xFF3A3A3A);
        darkSquare = new Paint();
        darkSquare.setColor(0xFF2E2E2E);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float factor = detector.getScaleFactor();
                        float current = currentScale();
                        float fit = fitScale();
                        float min = fit * MIN_SCALE_FACTOR;
                        // Clamp so a fast pinch cannot overshoot the limits.
                        if (current * factor < min) factor = min / current;
                        if (current * factor > MAX_SCALE) factor = MAX_SCALE / current;
                        matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                        constrain();
                        invalidate();
                        notifyScale();
                        return true;
                    }
                });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                matrix.postTranslate(-dx, -dy);
                constrain();
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float fit = fitScale();
                // Cycle fit → 100% → 300% → fit, so a tap always changes something.
                float current = currentScale();
                float target;
                if (current < fit * 1.05f) target = Math.max(1f, fit * 1.05f);
                else if (current < 2.9f) target = 3f;
                else target = fit;
                float factor = target / current;
                matrix.postScale(factor, factor, e.getX(), e.getY());
                constrain();
                invalidate();
                notifyScale();
                return true;
            }
        });
    }

    public void setOnScaleChangeListener(OnScaleChangeListener listener) {
        this.scaleListener = listener;
    }

    /** Replaces the image and resets zoom, pan and rotation. */
    public void setDrawable(Drawable next) {
        if (drawable instanceof Animatable) ((Animatable) drawable).stop();
        this.drawable = next;
        this.rotationDegrees = 0;
        if (next != null) {
            next.setCallback(null);
            next.setBounds(0, 0, next.getIntrinsicWidth(), next.getIntrinsicHeight());
            drawableRect.set(0, 0, next.getIntrinsicWidth(), next.getIntrinsicHeight());
            // A drawable needs a callback to schedule its own animation frames.
            next.setCallback(new Drawable.Callback() {
                @Override public void invalidateDrawable(Drawable who) { invalidate(); }
                @Override public void scheduleDrawable(Drawable who, Runnable what, long when) {
                    postDelayed(what, when - android.os.SystemClock.uptimeMillis());
                }
                @Override public void unscheduleDrawable(Drawable who, Runnable what) {
                    removeCallbacks(what);
                }
            });
            if (next instanceof Animatable) ((Animatable) next).start();
        }
        resetToFit();
    }

    public Drawable getDrawable() {
        return drawable;
    }

    /** Draws a light/dark checkerboard behind the image to reveal transparency. */
    public void setCheckerboard(boolean enabled, boolean darkTheme) {
        this.checkerboard = enabled;
        lightSquare.setColor(darkTheme ? 0xFF3A3A3A : 0xFFFFFFFF);
        darkSquare.setColor(darkTheme ? 0xFF2E2E2E : 0xFFE0E0E0);
        invalidate();
    }

    public boolean isCheckerboard() {
        return checkerboard;
    }

    /** Solid colour drawn behind the image when the checkerboard is off. */
    public void setImageBackdrop(int color) {
        this.backgroundTint = color;
        invalidate();
    }

    /** Rotates by 90° clockwise and refits. */
    public void rotate90() {
        rotationDegrees = (rotationDegrees + 90) % 360;
        resetToFit();
    }

    public int getRotationDegrees() {
        return rotationDegrees;
    }

    /** Scales the image so the whole of it is visible. */
    public void resetToFit() {
        if (drawable == null || getWidth() == 0 || getHeight() == 0) return;
        matrix.reset();

        float w = drawableRect.width();
        float h = drawableRect.height();
        if (w <= 0 || h <= 0) return;

        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees, w / 2f, h / 2f);
            if (rotationDegrees == 90 || rotationDegrees == 270) {
                // Re-centre after rotation swaps the bounding box.
                matrix.postTranslate((h - w) / 2f, (w - h) / 2f);
            }
        }

        RectF bounds = new RectF(drawableRect);
        matrix.mapRect(bounds);
        float scale = Math.min(getWidth() / bounds.width(), getHeight() / bounds.height());
        matrix.postScale(scale, scale, bounds.centerX(), bounds.centerY());

        bounds.set(drawableRect);
        matrix.mapRect(bounds);
        matrix.postTranslate(getWidth() / 2f - bounds.centerX(), getHeight() / 2f - bounds.centerY());

        invalidate();
        notifyScale();
    }

    /** Sets zoom to 1:1 image pixels, centred on the viewport. */
    public void resetToActualSize() {
        if (drawable == null) return;
        resetToFit();
        float factor = 1f / currentScale();
        matrix.postScale(factor, factor, getWidth() / 2f, getHeight() / 2f);
        constrain();
        invalidate();
        notifyScale();
    }

    /** Current zoom as a percentage of the image's own pixels. */
    public float getScalePercent() {
        return currentScale() * 100f;
    }

    private float currentScale() {
        matrix.getValues(values);
        float scaleX = values[Matrix.MSCALE_X];
        float skewY = values[Matrix.MSKEW_Y];
        // Rotation puts the scale partly in the skew terms.
        return (float) Math.hypot(scaleX, skewY);
    }

    private float fitScale() {
        if (drawable == null || getWidth() == 0 || drawableRect.width() <= 0) return 1f;
        float w = drawableRect.width();
        float h = drawableRect.height();
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            float tmp = w; w = h; h = tmp;
        }
        return Math.min(getWidth() / w, getHeight() / h);
    }

    /** Keeps the image from being dragged entirely off-screen. */
    private void constrain() {
        if (drawable == null) return;
        mappedRect.set(drawableRect);
        matrix.mapRect(mappedRect);

        float dx = 0, dy = 0;
        if (mappedRect.width() <= getWidth()) {
            dx = getWidth() / 2f - mappedRect.centerX();
        } else {
            if (mappedRect.left > 0) dx = -mappedRect.left;
            else if (mappedRect.right < getWidth()) dx = getWidth() - mappedRect.right;
        }
        if (mappedRect.height() <= getHeight()) {
            dy = getHeight() / 2f - mappedRect.centerY();
        } else {
            if (mappedRect.top > 0) dy = -mappedRect.top;
            else if (mappedRect.bottom < getHeight()) dy = getHeight() - mappedRect.bottom;
        }
        if (dx != 0 || dy != 0) matrix.postTranslate(dx, dy);
    }

    private void notifyScale() {
        if (scaleListener != null) scaleListener.onScaleChanged(getScalePercent());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        resetToFit();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = scaleDetector.onTouchEvent(event);
        // Suppress panning while a pinch is in progress, or the image jumps.
        if (!scaleDetector.isInProgress()) {
            handled |= gestureDetector.onTouchEvent(event);
        }
        return handled || super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (checkerboard) {
            drawCheckerboard(canvas);
        } else if (backgroundTint != Color.TRANSPARENT) {
            canvas.drawColor(backgroundTint);
        }
        if (drawable == null) return;

        int save = canvas.save();
        canvas.concat(matrix);
        drawable.draw(canvas);
        canvas.restoreToCount(save);
    }

    private void drawCheckerboard(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        canvas.drawRect(0, 0, width, height, lightSquare);
        for (int y = 0, row = 0; y < height; y += checkerSize, row++) {
            for (int x = (row % 2 == 0 ? checkerSize : 0); x < width; x += checkerSize * 2) {
                canvas.drawRect(x, y, x + checkerSize, y + checkerSize, darkSquare);
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (drawable instanceof Animatable) ((Animatable) drawable).stop();
        if (drawable != null) drawable.setCallback(null);
        super.onDetachedFromWindow();
    }
}
