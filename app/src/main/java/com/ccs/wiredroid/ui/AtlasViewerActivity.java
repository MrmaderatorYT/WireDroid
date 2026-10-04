package com.ccs.wiredroid.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AtlasViewerActivity extends AppCompatActivity {

    private static final String EXTRA_FILE_PATH = "file_path";

    /** Region rows added per frame; a packed atlas can carry a couple of thousand. */
    private static final int LIST_CHUNK = 40;

    public static void launch(Context context, File atlasFile) {
        Intent intent = new Intent(context, AtlasViewerActivity.class);
        intent.putExtra(EXTRA_FILE_PATH, atlasFile.getAbsolutePath());
        context.startActivity(intent);
    }

    public static class AtlasRegion {
        public String name;
        public boolean rotate;
        public int x, y, width, height;
        public int origW, origH;
        public int offsetX, offsetY;
        public int index = -1;
    }

    private File atlasFile;
    private AppTheme theme;
    private Bitmap textureBitmap;

    private String textureImageName;
    private final List<AtlasRegion> regions = new ArrayList<>();

    private ImageView previewImageView;
    private TextView infoTextView;

    private final Handler animHandler = new Handler(Looper.getMainLooper());
    private boolean isPlayingAnim = false;
    private List<AtlasRegion> currentAnimFrames = new ArrayList<>();
    private int currentAnimFrameIdx = 0;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    // Kept apart from animHandler, which is cleared wholesale when playback stops.
    private final Handler ui = new Handler(Looper.getMainLooper());

    /**
     * Everything read off the .atlas file. The parser fills one of these instead of
     * writing the fields directly, so the UI thread never sees a half-built list.
     */
    private static class ParsedAtlas {
        String textureImageName;
        Bitmap textureBitmap;
        final List<AtlasRegion> regions = new ArrayList<>();
        String error;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppPreferences prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);
        super.onCreate(savedInstanceState);

        String path = getIntent().getStringExtra(EXTRA_FILE_PATH);
        if (path == null) { finish(); return; }
        atlasFile = new File(path);
        if (!atlasFile.exists()) { finish(); return; }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle("Atlas: " + atlasFile.getName());
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        setSupportActionBar(toolbar);
        root.addView(toolbar);

        TextView infoHeader = new TextView(this);
        infoHeader.setText("Loading atlas…");
        infoHeader.setTextColor(theme.textDim);
        infoHeader.setPadding(16, 12, 16, 12);
        infoHeader.setBackgroundColor(theme.statusBar);
        root.addView(infoHeader);

        LinearLayout contentRow = new LinearLayout(this);
        contentRow.setOrientation(LinearLayout.HORIZONTAL);

        // Left side: Atlas full sheet & frame preview
        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setPadding(16, 16, 16, 16);

        previewImageView = new ImageView(this);
        previewImageView.setLayoutParams(new LinearLayout.LayoutParams(400, 400));
        previewImageView.setBackgroundColor(Color.DKGRAY);
        previewImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        leftCol.addView(previewImageView);

        infoTextView = new TextView(this);
        infoTextView.setTextColor(theme.text);
        infoTextView.setPadding(0, 16, 0, 16);
        leftCol.addView(infoTextView);

        Button btnPlayAnim = new Button(this);
        btnPlayAnim.setText("▶ Play Animation");
        btnPlayAnim.setOnClickListener(v -> toggleAnimation());
        leftCol.addView(btnPlayAnim);

        contentRow.addView(leftCol);

        // Right side: Regions list
        ScrollView listScroll = new ScrollView(this);
        LinearLayout listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        listLayout.setPadding(16, 16, 16, 16);

        listScroll.addView(listLayout);
        contentRow.addView(listScroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        root.addView(contentRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        FullScreenHelper.enable(this);

        io.execute(() -> {
            ParsedAtlas parsed = parseAtlasFile();
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                applyParsedAtlas(parsed, infoHeader, listLayout);
            });
        });
    }

    private void applyParsedAtlas(ParsedAtlas parsed, TextView infoHeader, LinearLayout listLayout) {
        textureImageName = parsed.textureImageName;
        textureBitmap = parsed.textureBitmap;
        regions.clear();
        regions.addAll(parsed.regions);

        infoHeader.setText("Image: " + (textureImageName != null ? textureImageName : "N/A") + " | Total Regions: " + regions.size());

        if (parsed.error != null) {
            Toast.makeText(this, "Error parsing atlas: " + parsed.error, Toast.LENGTH_SHORT).show();
        }

        addRegionRows(listLayout, 0);

        if (!regions.isEmpty()) {
            selectRegion(regions.get(0));
        }
    }

    /** Fills the region list a chunk per frame, so a large atlas never stalls one. */
    private void addRegionRows(LinearLayout listLayout, int from) {
        int to = Math.min(from + LIST_CHUNK, regions.size());
        for (int i = from; i < to; i++) {
            AtlasRegion r = regions.get(i);
            TextView itemTv = new TextView(this);
            String label = r.name + (r.index >= 0 ? " [" + r.index + "]" : "") + " (" + r.width + "x" + r.height + ")";
            itemTv.setText(label);
            itemTv.setTextColor(theme.text);
            itemTv.setTextSize(14f);
            itemTv.setPadding(12, 12, 12, 12);
            itemTv.setOnClickListener(v -> selectRegion(r));
            listLayout.addView(itemTv);
        }
        if (to < regions.size()) {
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                addRegionRows(listLayout, to);
            });
        }
    }

    private ParsedAtlas parseAtlasFile() {
        ParsedAtlas parsed = new ParsedAtlas();
        try (BufferedReader br = new BufferedReader(new FileReader(atlasFile))) {
            String line;
            AtlasRegion currentRegion = null;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (!line.contains(":")) {
                    if (parsed.textureImageName == null) {
                        parsed.textureImageName = line;
                        File imgFile = new File(atlasFile.getParentFile(), parsed.textureImageName);
                        if (imgFile.exists()) {
                            parsed.textureBitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                        }
                    } else if (currentRegion == null || (currentRegion.x != 0 || currentRegion.width != 0)) {
                        currentRegion = new AtlasRegion();
                        currentRegion.name = line;
                        parsed.regions.add(currentRegion);
                    }
                } else if (currentRegion != null) {
                    String[] parts = line.split(":");
                    String key = parts[0].trim();
                    String val = parts[1].trim();

                    if (key.equals("rotate")) {
                        currentRegion.rotate = Boolean.parseBoolean(val);
                    } else if (key.equals("xy")) {
                        String[] xy = val.split(",");
                        currentRegion.x = Integer.parseInt(xy[0].trim());
                        currentRegion.y = Integer.parseInt(xy[1].trim());
                    } else if (key.equals("size")) {
                        String[] wh = val.split(",");
                        currentRegion.width = Integer.parseInt(wh[0].trim());
                        currentRegion.height = Integer.parseInt(wh[1].trim());
                    } else if (key.equals("orig")) {
                        String[] wh = val.split(",");
                        currentRegion.origW = Integer.parseInt(wh[0].trim());
                        currentRegion.origH = Integer.parseInt(wh[1].trim());
                    } else if (key.equals("offset")) {
                        String[] off = val.split(",");
                        currentRegion.offsetX = Integer.parseInt(off[0].trim());
                        currentRegion.offsetY = Integer.parseInt(off[1].trim());
                    } else if (key.equals("index")) {
                        currentRegion.index = Integer.parseInt(val);
                    }
                }
            }
        } catch (Exception e) {
            parsed.error = e.getMessage();
        }
        return parsed;
    }

    private void selectRegion(AtlasRegion region) {
        if (textureBitmap == null || region == null) return;

        try {
            Bitmap cropped = Bitmap.createBitmap(textureBitmap, region.x, region.y,
                    Math.min(region.width, textureBitmap.getWidth() - region.x),
                    Math.min(region.height, textureBitmap.getHeight() - region.y));
            previewImageView.setImageBitmap(cropped);
        } catch (Exception ignored) {}

        infoTextView.setText(String.format("Region: %s\nSize: %dx%d\nPos: (%d, %d)\nIndex: %d",
                region.name, region.width, region.height, region.x, region.y, region.index));

        // Group animation frames with matching prefix
        currentAnimFrames.clear();
        String baseName = region.name;
        for (AtlasRegion r : regions) {
            if (r.name.equals(baseName) || r.name.startsWith(baseName)) {
                currentAnimFrames.add(r);
            }
        }
    }

    private void toggleAnimation() {
        if (isPlayingAnim) {
            isPlayingAnim = false;
            animHandler.removeCallbacksAndMessages(null);
            return;
        }

        if (currentAnimFrames.isEmpty()) return;
        isPlayingAnim = true;
        currentAnimFrameIdx = 0;

        Runnable animRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPlayingAnim || currentAnimFrames.isEmpty()) return;
                AtlasRegion r = currentAnimFrames.get(currentAnimFrameIdx);
                selectRegion(r);
                currentAnimFrameIdx = (currentAnimFrameIdx + 1) % currentAnimFrames.size();
                animHandler.postDelayed(this, 150);
            }
        };

        animHandler.post(animRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isPlayingAnim = false;
        animHandler.removeCallbacksAndMessages(null);
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }
}
