package com.ccs.wiredroid.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class TmxViewerActivity extends AppCompatActivity {

    private static final String EXTRA_FILE_PATH = "file_path";

    public static void launch(Context context, File tmxFile) {
        Intent intent = new Intent(context, TmxViewerActivity.class);
        intent.putExtra(EXTRA_FILE_PATH, tmxFile.getAbsolutePath());
        context.startActivity(intent);
    }

    private File tmxFile;
    private AppTheme theme;

    private int mapWidth = 0;
    private int mapHeight = 0;
    private int tileWidth = 32;
    private int tileHeight = 32;

    private Bitmap tilesetBitmap;
    private final List<MapLayer> layers = new ArrayList<>();

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private static class MapLayer {
        String name;
        int[][] tileGids;
    }

    /**
     * Everything read off the .tmx file. The parser fills one of these instead of
     * writing the fields directly, so nothing the map view draws is touched from
     * the worker thread.
     */
    private static class ParsedMap {
        int mapWidth;
        int mapHeight;
        int tileWidth = 32;
        int tileHeight = 32;
        Bitmap tilesetBitmap;
        final List<MapLayer> layers = new ArrayList<>();
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
        tmxFile = new File(path);
        if (!tmxFile.exists()) { finish(); return; }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle("TMX Map: " + tmxFile.getName());
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        setSupportActionBar(toolbar);
        root.addView(toolbar);

        TextView infoHeader = new TextView(this);
        infoHeader.setText("Loading map…");
        infoHeader.setTextColor(theme.textDim);
        infoHeader.setPadding(16, 12, 16, 12);
        infoHeader.setBackgroundColor(theme.statusBar);
        root.addView(infoHeader);

        MapView mapView = new MapView(this);
        root.addView(mapView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        FullScreenHelper.enable(this);

        io.execute(() -> {
            ParsedMap parsed = parseTmxFile();
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                applyParsedMap(parsed, infoHeader, mapView);
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
    }

    private void applyParsedMap(ParsedMap parsed, TextView infoHeader, MapView mapView) {
        mapWidth = parsed.mapWidth;
        mapHeight = parsed.mapHeight;
        tileWidth = parsed.tileWidth;
        tileHeight = parsed.tileHeight;
        tilesetBitmap = parsed.tilesetBitmap;
        layers.clear();
        layers.addAll(parsed.layers);

        infoHeader.setText(String.format("Dimensions: %dx%d tiles | Tile Size: %dx%d px | Layers: %d",
                mapWidth, mapHeight, tileWidth, tileHeight, layers.size()));
        mapView.invalidate();

        if (parsed.error != null) {
            Toast.makeText(this, "Error parsing TMX: " + parsed.error, Toast.LENGTH_LONG).show();
        }
    }

    private ParsedMap parseTmxFile() {
        ParsedMap parsed = new ParsedMap();
        try (FileInputStream fis = new FileInputStream(tmxFile)) {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(fis);
            doc.getDocumentElement().normalize();

            Element mapElem = doc.getDocumentElement();
            parsed.mapWidth = Integer.parseInt(mapElem.getAttribute("width"));
            parsed.mapHeight = Integer.parseInt(mapElem.getAttribute("height"));
            parsed.tileWidth = Integer.parseInt(mapElem.getAttribute("tilewidth"));
            parsed.tileHeight = Integer.parseInt(mapElem.getAttribute("tileheight"));

            // Parse tileset image
            NodeList tilesetList = doc.getElementsByTagName("tileset");
            for (int i = 0; i < tilesetList.getLength(); i++) {
                Element tsElem = (Element) tilesetList.item(i);
                NodeList imgList = tsElem.getElementsByTagName("image");
                if (imgList.getLength() > 0) {
                    Element imgElem = (Element) imgList.item(0);
                    String source = imgElem.getAttribute("source");
                    File imgFile = new File(tmxFile.getParentFile(), source);
                    if (imgFile.exists()) {
                        parsed.tilesetBitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                    }
                }
            }

            // Parse map layers
            NodeList layerList = doc.getElementsByTagName("layer");
            for (int i = 0; i < layerList.getLength(); i++) {
                Element layerElem = (Element) layerList.item(i);
                MapLayer layer = new MapLayer();
                layer.name = layerElem.getAttribute("name");
                layer.tileGids = new int[parsed.mapHeight][parsed.mapWidth];

                NodeList dataList = layerElem.getElementsByTagName("data");
                if (dataList.getLength() > 0) {
                    Element dataElem = (Element) dataList.item(0);
                    String csv = dataElem.getTextContent().trim();
                    String[] tokens = csv.split("[\n\r,]+");
                    int idx = 0;
                    for (int r = 0; r < parsed.mapHeight && idx < tokens.length; r++) {
                        for (int c = 0; c < parsed.mapWidth && idx < tokens.length; c++) {
                            try {
                                String t = tokens[idx++].trim();
                                if (!t.isEmpty()) {
                                    layer.tileGids[r][c] = Integer.parseInt(t);
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
                parsed.layers.add(layer);
            }
        } catch (Exception e) {
            parsed.error = e.getMessage();
        }
        return parsed;
    }

    private class MapView extends View {
        private float scaleFactor = 1.0f;
        private float focusX = 0f;
        private float focusY = 0f;
        private float lastTouchX;
        private float lastTouchY;
        private final ScaleGestureDetector scaleDetector;
        private final Paint gridPaint = new Paint();
        private final Paint textPaint = new Paint();

        public MapView(Context context) {
            super(context);
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    scaleFactor *= detector.getScaleFactor();
                    scaleFactor = Math.max(0.2f, Math.min(scaleFactor, 5.0f));
                    invalidate();
                    return true;
                }
            });

            gridPaint.setColor(theme.separator);
            gridPaint.setStyle(Paint.Style.STROKE);
            gridPaint.setStrokeWidth(1f);

            textPaint.setColor(theme.textDim);
            textPaint.setTextSize(24f);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!scaleDetector.isInProgress()) {
                        float dx = event.getX() - lastTouchX;
                        float dy = event.getY() - lastTouchY;
                        focusX += dx;
                        focusY += dy;
                        invalidate();
                    }
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    break;
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.translate(focusX + 50, focusY + 50);
            canvas.scale(scaleFactor, scaleFactor);

            int totalW = mapWidth * tileWidth;
            int totalH = mapHeight * tileHeight;

            // Draw background & grid
            canvas.drawRect(0, 0, totalW, totalH, gridPaint);

            for (int r = 0; r <= mapHeight; r++) {
                canvas.drawLine(0, r * tileHeight, totalW, r * tileHeight, gridPaint);
            }
            for (int c = 0; c <= mapWidth; c++) {
                canvas.drawLine(c * tileWidth, 0, c * tileWidth, totalH, gridPaint);
            }

            // Render tiles
            int colsInTileset = tilesetBitmap != null ? tilesetBitmap.getWidth() / tileWidth : 1;

            for (MapLayer layer : layers) {
                for (int r = 0; r < mapHeight; r++) {
                    for (int c = 0; c < mapWidth; c++) {
                        int gid = layer.tileGids[r][c];
                        if (gid <= 0) continue;

                        int tileIdx = gid - 1;
                        int srcX = (tileIdx % colsInTileset) * tileWidth;
                        int srcY = (tileIdx / colsInTileset) * tileHeight;

                        Rect dest = new Rect(c * tileWidth, r * tileHeight, (c + 1) * tileWidth, (r + 1) * tileHeight);
                        if (tilesetBitmap != null) {
                            Rect src = new Rect(srcX, srcY, srcX + tileWidth, srcY + tileHeight);
                            canvas.drawBitmap(tilesetBitmap, src, dest, null);
                        } else {
                            Paint tilePaint = new Paint();
                            tilePaint.setColor(Color.HSVToColor(new float[]{(gid * 37) % 360, 0.6f, 0.8f}));
                            canvas.drawRect(dest, tilePaint);
                        }
                    }
                }
            }

            canvas.restore();
        }
    }
}
