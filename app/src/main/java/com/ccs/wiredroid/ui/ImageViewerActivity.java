package com.ccs.wiredroid.ui;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Image viewer with pinch zoom, pan, rotation, a transparency checkerboard,
 * animated GIF/WebP playback and swipe-free navigation through the sibling
 * images in the same folder.
 *
 * <p>Animation uses {@link ImageDecoder} on API 28+; below that the first frame
 * is shown, which is the best the platform offers without bundling a decoder.</p>
 */
public class ImageViewerActivity extends AppCompatActivity {

    private static final String EXTRA_FILE_PATH = "file_path";

    /** Longest edge kept when decoding, to stay inside the texture limit. */
    private static final int MAX_DECODE_EDGE = 4096;

    private static final String[] IMAGE_EXTENSIONS = {
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".heic", ".heif", ".avif"
    };

    private AppPreferences prefs;
    private AppTheme theme;
    private ZoomableImageView imageView;
    private TextView statusBar;
    private Toolbar toolbar;

    private File current;
    /** Sibling images in the same folder, sorted by name. */
    private List<File> siblings = new ArrayList<>();
    private int siblingIndex = -1;
    /** False until the folder listing lands, so ◀ / ▶ do not judge an empty list. */
    private boolean siblingsReady;

    private int pixelWidth;
    private int pixelHeight;
    private String mimeType = "—";
    private boolean animated;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Identifies the newest decode, so a slow one cannot overwrite a newer image. */
    private int loadToken;

    /** One decode's output, applied to the fields once it reaches the UI thread. */
    private static class LoadResult {
        Drawable drawable;
        int pixelWidth;
        int pixelHeight;
        String mimeType = "—";
        boolean animated;
    }

    public static void launch(Context context, File imageFile) {
        Intent i = new Intent(context, ImageViewerActivity.class);
        i.putExtra(EXTRA_FILE_PATH, imageFile.getAbsolutePath());
        context.startActivity(i);
    }

    /** True when the extension is one this viewer can decode. */
    public static boolean isSupported(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : IMAGE_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        FullScreenHelper.enable(this);

        String path = getIntent().getStringExtra(EXTRA_FILE_PATH);
        if (path == null) {
            Toast.makeText(this, R.string.image_no_file, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        current = new File(path);
        // The image is queued first: both jobs share one worker, and the folder
        // listing only matters once ◀ / ▶ are tapped.
        loadImage(current);
        collectSiblings();
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle(R.string.image_viewer_title);
        toolbar.setTitleTextColor(theme.text);
        toolbar.setSubtitleTextColor(theme.textDim);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusBar = new TextView(this);
        statusBar.setBackgroundColor(theme.statusBar);
        statusBar.setTextColor(theme.textDim);
        statusBar.setTextSize(11);
        statusBar.setTypeface(prefs.resolveTypeface());
        statusBar.setPadding(dp(12), dp(6), dp(12), dp(6));
        root.addView(statusBar);

        imageView = new ZoomableImageView(this);
        imageView.setContentDescription(getString(R.string.a11y_image_viewer));
        imageView.setImageBackdrop(theme.consoleBg);
        imageView.setOnScaleChangeListener(percent -> updateStatus());
        root.addView(imageView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildControls());
        return root;
    }

    private View buildControls() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(theme.toolbar);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));

        bar.addView(button("◀", R.string.image_action_previous, v -> showSibling(-1)));
        bar.addView(button("⊡", R.string.image_action_fit, v -> imageView.resetToFit()));
        bar.addView(button("1:1", R.string.image_action_actual, v -> imageView.resetToActualSize()));
        bar.addView(button("⟳", R.string.image_action_rotate, v -> {
            imageView.rotate90();
            updateStatus();
        }));
        bar.addView(button("▦", R.string.image_action_checkerboard, v -> {
            boolean next = !imageView.isCheckerboard();
            imageView.setCheckerboard(next, theme.dark);
            if (!next) imageView.setImageBackdrop(theme.consoleBg);
        }));
        bar.addView(button("ⓘ", R.string.image_action_info, v -> showInfo()));
        bar.addView(button("▶", R.string.image_action_next, v -> showSibling(1)));
        return bar;
    }

    private TextView button(String label, int descriptionRes, View.OnClickListener listener) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(theme.accent);
        view.setTextSize(15);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        view.setContentDescription(getString(descriptionRes));
        view.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        view.setLayoutParams(lp);
        return view;
    }

    // ─── Loading ────────────────────────────────────────────────────────────

    /**
     * Lists the decodable images beside {@code current}, so ◀ / ▶ can walk them.
     * The listing is only needed once a navigation button is tapped, so it runs
     * off the main thread and the buttons wait for it.
     */
    private void collectSiblings() {
        File file = current;
        io.execute(() -> {
            List<File> found = new ArrayList<>();
            int index = -1;
            File parent = file.getParentFile();
            File[] files = parent != null ? parent.listFiles() : null;
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && isSupported(f.getName())) found.add(f);
                }
                found.sort(Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
                for (int i = 0; i < found.size(); i++) {
                    if (found.get(i).getAbsolutePath().equals(file.getAbsolutePath())) {
                        index = i;
                        break;
                    }
                }
            }
            final int foundIndex = index;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                siblings = found;
                siblingIndex = foundIndex;
                siblingsReady = true;
                // The status line carries the "3/17" counter, so it is now stale.
                if (imageView.getDrawable() != null) updateStatus();
            });
        });
    }

    private void showSibling(int delta) {
        if (!siblingsReady) return;
        if (siblings.size() < 2 || siblingIndex < 0) {
            Toast.makeText(this, R.string.image_no_other_images, Toast.LENGTH_SHORT).show();
            return;
        }
        siblingIndex = (siblingIndex + delta + siblings.size()) % siblings.size();
        current = siblings.get(siblingIndex);
        loadImage(current);
    }

    private void loadImage(File file) {
        toolbar.setSubtitle(file.getName());
        animated = false;
        mimeType = "—";
        pixelWidth = 0;
        pixelHeight = 0;

        final int token = ++loadToken;
        io.execute(() -> {
            LoadResult result = decode(file);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                // Another file was picked while this one was decoding.
                if (token != loadToken) return;
                applyLoadResult(file, result);
            });
        });
    }

    /** The whole read of one file: header, then animated or still pixels. */
    private LoadResult decode(File file) {
        LoadResult result = new LoadResult();
        BitmapFactory.Options opts = readBounds(file, result);
        result.drawable = decodeAnimated(file, result);
        if (result.drawable == null) result.drawable = decodeBitmap(file, opts);
        return result;
    }

    private void applyLoadResult(File file, LoadResult result) {
        pixelWidth = result.pixelWidth;
        pixelHeight = result.pixelHeight;
        mimeType = result.mimeType;
        animated = result.animated;

        if (result.drawable == null) {
            statusBar.setText(getString(R.string.image_decode_failed, file.getName()));
            Toast.makeText(this, getString(R.string.image_decode_failed, file.getName()),
                    Toast.LENGTH_LONG).show();
            imageView.setDrawable(null);
            return;
        }
        imageView.setDrawable(result.drawable);
        updateStatus();
    }

    /**
     * Reads dimensions and MIME type without allocating the pixels, and hands back
     * the filled options so the still decode does not have to read the header again.
     */
    private BitmapFactory.Options readBounds(File file, LoadResult result) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        result.pixelWidth = opts.outWidth;
        result.pixelHeight = opts.outHeight;
        if (opts.outMimeType != null) result.mimeType = opts.outMimeType;
        return opts;
    }

    /**
     * Decodes an animated GIF or WebP.
     *
     * @return the animated drawable, or {@code null} if the file is not animated
     *         or the platform is older than API 28
     */
    private Drawable decodeAnimated(File file, LoadResult result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null;
        String lower = file.getName().toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".gif") && !lower.endsWith(".webp")) return null;
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(file);
            Drawable decoded = ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                android.util.Size size = info.getSize();
                result.pixelWidth = size.getWidth();
                result.pixelHeight = size.getHeight();
                if (info.getMimeType() != null) result.mimeType = info.getMimeType();
                int longest = Math.max(size.getWidth(), size.getHeight());
                if (longest > MAX_DECODE_EDGE) {
                    int divisor = (longest + MAX_DECODE_EDGE - 1) / MAX_DECODE_EDGE;
                    decoder.setTargetSampleSize(Math.max(1, divisor));
                }
            });
            if (decoded instanceof AnimatedImageDrawable) {
                result.animated = true;
                ((AnimatedImageDrawable) decoded).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                return decoded;
            }
            // A static GIF/WebP: still a valid drawable, just not animated.
            return decoded;
        } catch (Exception e) {
            return null;
        }
    }

    /** Decodes a still image, downsampling so very large files still open. */
    private Drawable decodeBitmap(File file, BitmapFactory.Options opts) {
        try {
            int sample = 1;
            while (opts.outWidth / sample > MAX_DECODE_EDGE
                    || opts.outHeight / sample > MAX_DECODE_EDGE) {
                sample *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bitmap == null) return null;
            return new BitmapDrawable(getResources(), bitmap);
        } catch (Throwable e) {
            // OutOfMemoryError is realistic here, so catch Throwable.
            return null;
        }
    }

    // ─── Status and info ────────────────────────────────────────────────────

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append(current.getName());
        if (pixelWidth > 0 && pixelHeight > 0) {
            sb.append("  ").append(pixelWidth).append("×").append(pixelHeight);
        }
        sb.append("  ").append(humanSize(current.length()));
        sb.append("  ").append(String.format(Locale.US, "%.0f%%", imageView.getScalePercent()));
        if (imageView.getRotationDegrees() != 0) {
            sb.append("  ").append(imageView.getRotationDegrees()).append("°");
        }
        if (animated) sb.append("  ").append(getString(R.string.image_animated));
        if (siblings.size() > 1 && siblingIndex >= 0) {
            sb.append("  ").append(siblingIndex + 1).append("/").append(siblings.size());
        }
        statusBar.setText(sb.toString());
    }

    private void showInfo() {
        Drawable d = imageView.getDrawable();
        boolean hasAlpha = false;
        if (d instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) d).getBitmap();
            if (bitmap != null) hasAlpha = bitmap.hasAlpha();
        }
        String message = getString(R.string.image_info_body,
                current.getAbsolutePath(),
                pixelWidth, pixelHeight,
                mimeType,
                humanSize(current.length()),
                hasAlpha ? getString(R.string.image_info_yes) : getString(R.string.image_info_no),
                animated ? getString(R.string.image_info_yes) : getString(R.string.image_info_no));

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.image_action_info)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.image_open_in_hex, (dlg, w) ->
                        HexEditorActivity.launch(this, current))
                .show();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }
}
