package com.ccs.wiredroid.ui;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Переглядач SVG з підтримкою масштабування (жестами pinch-to-zoom, подвійним тапом
 * та екранними кнопками + / - / 100% / Скинути) і плавним переміщенням.
 */
public class SvgViewerActivity extends AppCompatActivity {

    private static final String EXTRA_FILE_PATH = "file_path";
    private static final float MIN_ZOOM = 0.2f;
    private static final float MAX_ZOOM = 15.0f;

    private AppPreferences prefs;
    private AppTheme theme;
    private ImageView imageView;
    private FrameLayout imageContainer;
    private TextView statusBar;
    private TextView tvZoomLevel;

    private Bitmap currentBitmap;
    private final Matrix matrix = new Matrix();
    private final Matrix savedMatrix = new Matrix();

    private float currentRelativeZoom = 1.0f;
    private float fitScale = 1.0f;
    private float fitTransX = 0f;
    private float fitTransY = 0f;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    public static void launch(Context context, File svgFile) {
        Intent i = new Intent(context, SvgViewerActivity.class);
        i.putExtra(EXTRA_FILE_PATH, svgFile.getAbsolutePath());
        context.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        FullScreenHelper.enable(this);

        initGestureDetectors();

        String path = getIntent().getStringExtra(EXTRA_FILE_PATH);
        if (path == null) {
            Toast.makeText(this, "No file specified", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        loadSvg(new File(path));
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        // Toolbar
        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setPopupTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);
        toolbar.setTitle("SVG Viewer");
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        // Status bar line
        statusBar = new TextView(this);
        statusBar.setBackgroundColor(theme.consoleBg);
        statusBar.setTextColor(theme.textDim);
        statusBar.setTextSize(11);
        statusBar.setPadding(dp(12), dp(6), dp(12), dp(6));
        root.addView(statusBar);

        // Main content container
        imageContainer = new FrameLayout(this);
        imageContainer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        imageContainer.setBackgroundColor(theme.consoleBg);
        imageContainer.setClipChildren(true);

        // Interactive Matrix ImageView
        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.MATRIX);
        imageView.setContentDescription(getString(R.string.a11y_svg_viewer));
        imageContainer.addView(imageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Floating Zoom Controls Bar (Material 3 Pill Shape)
        View zoomControls = buildZoomControls();
        FrameLayout.LayoutParams controlsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        controlsLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        controlsLp.bottomMargin = dp(20);
        imageContainer.addView(zoomControls, controlsLp);

        root.addView(imageContainer);
        return root;
    }

    private View buildZoomControls() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(theme.dark ? 0xEE2B2D30 : 0xEEFFFFFF);
        pillBg.setStroke(dp(1), theme.separator);
        pillBg.setCornerRadius(dp(22));
        bar.setBackground(pillBg);
        bar.setElevation(dp(6));

        // Zoom Out Button (−)
        TextView btnZoomOut = createControlButton(" − ");
        btnZoomOut.setOnClickListener(v -> zoomBy(1.0f / 1.35f, imageContainer.getWidth() / 2f, imageContainer.getHeight() / 2f));
        bar.addView(btnZoomOut);

        // Zoom Level Indicator / Reset Button (100%)
        tvZoomLevel = new TextView(this);
        tvZoomLevel.setText("100%");
        tvZoomLevel.setTextColor(theme.text);
        tvZoomLevel.setTextSize(13);
        tvZoomLevel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvZoomLevel.setGravity(Gravity.CENTER);
        tvZoomLevel.setPadding(dp(10), dp(6), dp(10), dp(6));
        tvZoomLevel.setClickable(true);
        tvZoomLevel.setFocusable(true);
        tvZoomLevel.setOnClickListener(v -> resetZoom());
        bar.addView(tvZoomLevel);

        // Zoom In Button (+)
        TextView btnZoomIn = createControlButton(" + ");
        btnZoomIn.setOnClickListener(v -> zoomBy(1.35f, imageContainer.getWidth() / 2f, imageContainer.getHeight() / 2f));
        bar.addView(btnZoomIn);

        // Separator
        View sep = new View(this);
        sep.setBackgroundColor(theme.separator);
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(dp(1), dp(18));
        sepLp.setMargins(dp(4), 0, dp(4), 0);
        bar.addView(sep, sepLp);

        // Fit / Reset button (↺)
        TextView btnReset = createControlButton(" ↺ ");
        btnReset.setOnClickListener(v -> resetZoom());
        bar.addView(btnReset);

        return bar;
    }

    private TextView createControlButton(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(theme.accent);
        tv.setTextSize(16);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initGestureDetectors() {
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float scaleFactor = detector.getScaleFactor();
                zoomBy(scaleFactor, detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                matrix.postTranslate(-distanceX, -distanceY);
                clampTranslation();
                imageView.setImageMatrix(matrix);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (currentRelativeZoom > 1.2f) {
                    resetZoom();
                } else {
                    zoomBy(2.5f / currentRelativeZoom, e.getX(), e.getY());
                }
                return true;
            }
        });

        imageView.setOnTouchListener((v, event) -> {
            boolean scaleHandled = scaleDetector.onTouchEvent(event);
            boolean gestureHandled = gestureDetector.onTouchEvent(event);
            return scaleHandled || gestureHandled || true;
        });
    }

    private void zoomBy(float factor, float focusX, float focusY) {
        float targetZoom = currentRelativeZoom * factor;
        if (targetZoom < MIN_ZOOM) factor = MIN_ZOOM / currentRelativeZoom;
        if (targetZoom > MAX_ZOOM) factor = MAX_ZOOM / currentRelativeZoom;

        currentRelativeZoom *= factor;
        matrix.postScale(factor, factor, focusX, focusY);
        clampTranslation();
        imageView.setImageMatrix(matrix);
        updateZoomLabel();
    }

    private void resetZoom() {
        currentRelativeZoom = 1.0f;
        matrix.reset();
        matrix.postScale(fitScale, fitScale);
        matrix.postTranslate(fitTransX, fitTransY);
        imageView.setImageMatrix(matrix);
        updateZoomLabel();
    }

    private void clampTranslation() {
        if (currentBitmap == null) return;
        float[] values = new float[9];
        matrix.getValues(values);

        float currentWidth = currentBitmap.getWidth() * values[Matrix.MSCALE_X];
        float currentHeight = currentBitmap.getHeight() * values[Matrix.MSCALE_Y];

        float containerW = imageContainer.getWidth();
        float containerH = imageContainer.getHeight();

        float minX = containerW - currentWidth - dp(60);
        float maxX = dp(60);
        if (currentWidth < containerW) {
            values[Matrix.MTRANS_X] = (containerW - currentWidth) / 2f;
        } else {
            if (values[Matrix.MTRANS_X] < minX) values[Matrix.MTRANS_X] = minX;
            if (values[Matrix.MTRANS_X] > maxX) values[Matrix.MTRANS_X] = maxX;
        }

        float minY = containerH - currentHeight - dp(60);
        float maxY = dp(60);
        if (currentHeight < containerH) {
            values[Matrix.MTRANS_Y] = (containerH - currentHeight) / 2f;
        } else {
            if (values[Matrix.MTRANS_Y] < minY) values[Matrix.MTRANS_Y] = minY;
            if (values[Matrix.MTRANS_Y] > maxY) values[Matrix.MTRANS_Y] = maxY;
        }

        matrix.setValues(values);
    }

    private void updateZoomLabel() {
        if (tvZoomLevel != null) {
            tvZoomLevel.setText(String.format(java.util.Locale.ROOT, "%d%%", Math.round(currentRelativeZoom * 100)));
        }
    }

    private void loadSvg(File file) {
        statusBar.setText("Loading: " + file.getName());

        new Thread(() -> {
            Bitmap rendered = null;
            String summary = null;
            String parseError = null;
            String otherError = null;

            try (InputStream is = new FileInputStream(file)) {
                SVG svg = SVG.getFromInputStream(is);

                float svgWidth = svg.getDocumentWidth();
                float svgHeight = svg.getDocumentHeight();

                int screenW = getResources().getDisplayMetrics().widthPixels;
                int screenH = getResources().getDisplayMetrics().heightPixels;

                if (svgWidth <= 0 || svgHeight <= 0) {
                    svgWidth = screenW;
                    svgHeight = screenH;
                }

                // Render at higher crisp resolution for vector sharpness
                float scaleX = (float) screenW / svgWidth;
                float scaleY = (float) screenH / svgHeight;
                float scale = Math.max(scaleX, scaleY) * 1.5f;

                int bitmapWidth = Math.max(1, (int) (svgWidth * scale));
                int bitmapHeight = Math.max(1, (int) (svgHeight * scale));

                Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(Color.WHITE);

                float canvasScale = (float) bitmapWidth / svgWidth;
                canvas.scale(canvasScale, canvasScale);
                svg.renderToCanvas(canvas);

                rendered = bitmap;
                summary = String.format(java.util.Locale.ROOT, "%s — %.0f x %.0f (rendered %dx%d)",
                        file.getName(), svgWidth, svgHeight, bitmapWidth, bitmapHeight);

            } catch (SVGParseException e) {
                parseError = e.getMessage();
            } catch (Exception e) {
                otherError = e.getMessage();
            }

            final Bitmap readyBitmap = rendered;
            final String readySummary = summary;
            final String parseFailure = parseError;
            final String otherFailure = otherError;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                if (readyBitmap != null) {
                    currentBitmap = readyBitmap;
                    imageView.setImageBitmap(readyBitmap);
                    statusBar.setText(readySummary);

                    imageContainer.post(() -> {
                        int cWidth = imageContainer.getWidth();
                        int cHeight = imageContainer.getHeight();
                        if (cWidth > 0 && cHeight > 0 && currentBitmap != null) {
                            float sx = (float) cWidth / currentBitmap.getWidth();
                            float sy = (float) cHeight / currentBitmap.getHeight();
                            fitScale = Math.min(sx, sy) * 0.9f;
                            fitTransX = (cWidth - currentBitmap.getWidth() * fitScale) / 2f;
                            fitTransY = (cHeight - currentBitmap.getHeight() * fitScale) / 2f;
                            resetZoom();
                        }
                    });
                } else if (parseFailure != null) {
                    statusBar.setText("Parse error: " + parseFailure);
                    Toast.makeText(this, "SVG parse error: " + parseFailure, Toast.LENGTH_LONG).show();
                } else {
                    statusBar.setText("Error: " + otherFailure);
                    Toast.makeText(this, "Error: " + otherFailure, Toast.LENGTH_LONG).show();
                }
            });
        }, "svg-render").start();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
