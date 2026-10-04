package com.ccs.wiredroid.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.uml.PlantUmlHighlighter;
import com.ccs.wiredroid.uml.UmlGraph;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UmlDiagramActivity extends AppCompatActivity {

    private static final String EXTRA_PROJECT_PATH = "project_path";

    public static void launch(Context context, File projectDir) {
        Intent intent = new Intent(context, UmlDiagramActivity.class);
        intent.putExtra(EXTRA_PROJECT_PATH, projectDir != null ? projectDir.getAbsolutePath() : null);
        context.startActivity(intent);
    }

    public static class UmlClass {
        public String name;
        public String type = "class";
        public String superClass;
        public List<String> interfaces = new ArrayList<>();
        public List<String> fields = new ArrayList<>();
        public List<String> methods = new ArrayList<>();
        public float x, y, width, height;
    }

    private File projectDir;
    private AppTheme theme;
    private final List<UmlClass> umlClasses = new ArrayList<>();

    /** Parsed model behind the drawing — types, members and how they relate. */
    private UmlGraph graph;

    private UmlDiagramView canvasView;
    private ProgressBar progress;
    private Toolbar umlToolbar;
    private EditText plantUmlEditText;
    private LinearLayout codeContainer;
    private FrameLayout canvasOverlay;

    private View btnToggleView;
    private View btnApply;
    private View btnCopy;
    private View btnFit;
    private View btnZoomIn;
    private View btnZoomOut;

    private boolean isCodeView = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppPreferences prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);
        super.onCreate(savedInstanceState);

        String path = getIntent().getStringExtra(EXTRA_PROJECT_PATH);
        if (path != null) projectDir = new File(path);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle(R.string.uml_title);
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        setSupportActionBar(toolbar);
        umlToolbar = toolbar;
        root.addView(toolbar);

        // Control bar
        LinearLayout ctrlRow = new LinearLayout(this);
        ctrlRow.setOrientation(LinearLayout.HORIZONTAL);
        ctrlRow.setPadding(dp(12), dp(8), dp(12), dp(8));
        ctrlRow.setBackgroundColor(theme.statusBar);
        ctrlRow.setGravity(Gravity.CENTER_VERTICAL);

        btnToggleView = tonalButton(getString(R.string.uml_btn_source), ctrlRow);
        btnToggleView.setOnClickListener(v -> toggleViewMode());

        btnApply = tonalButton(getString(R.string.dialog_apply), ctrlRow);
        btnApply.setVisibility(View.GONE);
        btnApply.setOnClickListener(v -> applyCodeChanges());

        btnFit = tonalButton(getString(R.string.uml_btn_fit), ctrlRow);
        btnFit.setOnClickListener(v -> canvasView.fit());

        btnZoomIn = tonalButton("+", ctrlRow);
        btnZoomIn.setOnClickListener(v -> canvasView.zoomIn());

        btnZoomOut = tonalButton("-", ctrlRow);
        btnZoomOut.setOnClickListener(v -> canvasView.zoomOut());

        btnCopy = tonalButton(getString(R.string.uml_btn_copy), ctrlRow);
        btnCopy.setOnClickListener(v -> copyPlantUml());

        root.addView(ctrlRow);

        // Canvas overlay container
        canvasOverlay = new FrameLayout(this);
        canvasView = new UmlDiagramView(this);
        canvasView.setTheme(theme);
        canvasView.setOnTypeTapped((file, name) -> {
            if (file == null) return;
            Intent open = new Intent(this, MainActivity.class);
            open.putExtra("file_path", file.getAbsolutePath());
            open.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(open);
        });
        canvasOverlay.addView(canvasView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        pl.gravity = Gravity.CENTER;
        canvasOverlay.addView(progress, pl);

        root.addView(canvasOverlay, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Code view container (hidden by default)
        codeContainer = new LinearLayout(this);
        codeContainer.setOrientation(LinearLayout.VERTICAL);
        codeContainer.setVisibility(View.GONE);
        codeContainer.setBackgroundColor(theme.bg);

        plantUmlEditText = new EditText(this);
        plantUmlEditText.setTypeface(prefs.resolveTypeface());
        plantUmlEditText.setTextColor(theme.text);
        plantUmlEditText.setHintTextColor(theme.textDim);
        plantUmlEditText.setTextSize(13f);
        plantUmlEditText.setPadding(dp(16), dp(16), dp(16), dp(16));
        plantUmlEditText.setBackgroundColor(theme.consoleBg);
        plantUmlEditText.setGravity(Gravity.TOP | Gravity.START);
        plantUmlEditText.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        plantUmlEditText.setHorizontallyScrolling(true);
        plantUmlEditText.setLineSpacing(0f, 1.2f);
        PlantUmlHighlighter.attach(plantUmlEditText, theme.dark);

        ScrollView codeScroll = new ScrollView(this);
        codeScroll.setFillViewport(true);
        HorizontalScrollView hScroll = new HorizontalScrollView(this);
        hScroll.setFillViewport(true);
        hScroll.addView(plantUmlEditText, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        codeScroll.addView(hScroll, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        codeContainer.addView(codeScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        root.addView(codeContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        FullScreenHelper.enable(this);
        startScan();
    }

    private void startScan() {
        new Thread(() -> {
            UmlGraph scanned = null;
            String failure = null;
            try {
                scanned = UmlGraph.scan(projectDir, 60);
                parseProjectClasses();
            } catch (Throwable t) {
                failure = String.valueOf(t.getMessage());
            }
            final UmlGraph ready = scanned;
            final String error = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (progress != null) progress.setVisibility(View.GONE);
                if (ready == null) {
                    if (umlToolbar != null) umlToolbar.setTitle(getString(R.string.uml_failed, error));
                    return;
                }
                graph = ready;
                canvasView.setGraph(graph);
                if (plantUmlEditText != null) {
                    plantUmlEditText.setText(generatePlantUml());
                    PlantUmlHighlighter.highlight(plantUmlEditText.getText(), theme.dark);
                }
                if (umlToolbar != null) {
                    umlToolbar.setTitle(getResources().getQuantityString(
                            R.plurals.uml_summary, graph.types().size(),
                            graph.types().size(), graph.relations().size()));
                }
            });
        }, "uml-scan").start();
    }

    /** A filled-tonal Material button. */
    private View tonalButton(CharSequence text, LinearLayout parent) {
        MaterialButton b = new MaterialButton(this,
                null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setTextSize(12f);
        b.setTextColor(theme.accent);
        b.setStrokeColor(android.content.res.ColorStateList.valueOf(theme.separator));
        b.setRippleColor(android.content.res.ColorStateList.valueOf(
                (theme.accent & 0x00FFFFFF) | 0x33000000));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        parent.addView(b, lp);
        return b;
    }

    private void toggleViewMode() {
        isCodeView = !isCodeView;
        if (isCodeView) {
            canvasOverlay.setVisibility(View.GONE);
            codeContainer.setVisibility(View.VISIBLE);
            ((MaterialButton) btnToggleView).setText(getString(R.string.uml_btn_canvas));
            btnApply.setVisibility(View.VISIBLE);
            btnFit.setVisibility(View.GONE);
            btnZoomIn.setVisibility(View.GONE);
            btnZoomOut.setVisibility(View.GONE);

            if (plantUmlEditText != null) {
                plantUmlEditText.setText(generatePlantUml());
                PlantUmlHighlighter.highlight(plantUmlEditText.getText(), theme.dark);
            }
        } else {
            applyCodeChanges();
            codeContainer.setVisibility(View.GONE);
            canvasOverlay.setVisibility(View.VISIBLE);
            ((MaterialButton) btnToggleView).setText(getString(R.string.uml_btn_source));
            btnApply.setVisibility(View.GONE);
            btnFit.setVisibility(View.VISIBLE);
            btnZoomIn.setVisibility(View.VISIBLE);
            btnZoomOut.setVisibility(View.VISIBLE);
        }
    }

    private void applyCodeChanges() {
        if (plantUmlEditText == null) return;
        String code = plantUmlEditText.getText().toString();
        UmlGraph parsed = UmlGraph.fromPlantUml(code);
        if (parsed.types().isEmpty()) {
            Toast.makeText(this, "No UML classes found in code", Toast.LENGTH_SHORT).show();
            return;
        }
        graph = parsed;
        canvasView.setGraph(graph);
        if (umlToolbar != null) {
            umlToolbar.setTitle(getResources().getQuantityString(
                    R.plurals.uml_summary, graph.types().size(),
                    graph.types().size(), graph.relations().size()));
        }
        Toast.makeText(this, "Diagram updated", Toast.LENGTH_SHORT).show();
    }

    private void copyPlantUml() {
        String textToCopy = isCodeView && plantUmlEditText != null
                ? plantUmlEditText.getText().toString()
                : generatePlantUml();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("PlantUML", textToCopy));
            Toast.makeText(this, "PlantUML copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    private void parseProjectClasses() {
        if (projectDir == null || !projectDir.exists()) return;
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(projectDir, javaFiles);

        float currX = 50f;
        float currY = 50f;

        for (File f : javaFiles) {
            try {
                String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                UmlClass cls = parseClassContent(content, f.getName());
                if (cls != null) {
                    cls.x = currX;
                    cls.y = currY;
                    cls.width = 280f;
                    cls.height = Math.max(160f, 60 + (cls.fields.size() + cls.methods.size()) * 24f);

                    currX += 340f;
                    if (currX > 1200f) {
                        currX = 50f;
                        currY += 320f;
                    }
                    umlClasses.add(cls);
                }
            } catch (IOException ignored) {}
        }
    }

    private void collectJavaFiles(File dir, List<File> result) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File c : children) collectJavaFiles(c, result);
            }
        } else if (dir.getName().endsWith(".java") || dir.getName().endsWith(".kt")) {
            result.add(dir);
        }
    }

    private UmlClass parseClassContent(String code, String fileName) {
        Pattern classPattern = Pattern.compile("(public|private|protected)?\\s*(static)?\\s*(abstract)?\\s*(class|interface|enum|record)\\s+([A-Za-z0-9_]+)(\\s+extends\\s+([A-Za-z0-9_.]+))?(\\s+implements\\s+([A-Za-z0-9_,\\s.]+))?");
        Matcher m = classPattern.matcher(code);
        if (!m.find()) return null;

        UmlClass cls = new UmlClass();
        cls.type = m.group(4);
        cls.name = m.group(5);
        if (m.group(7) != null) cls.superClass = m.group(7).trim();
        if (m.group(9) != null) {
            for (String impl : m.group(9).split(",")) {
                cls.interfaces.add(impl.trim());
            }
        }

        // Parse fields
        Pattern fieldPattern = Pattern.compile("(private|protected|public)?\\s+(static\\s+)?(final\\s+)?([A-Za-z0-9_<>\\[\\]]+)\\s+([A-Za-z0-9_]+)\\s*(;|=)");
        Matcher fm = fieldPattern.matcher(code);
        while (fm.find()) {
            String vis = getVisibilitySymbol(fm.group(1));
            cls.fields.add(vis + " " + fm.group(5) + ": " + fm.group(4));
        }

        // Parse methods
        Pattern methodPattern = Pattern.compile("(private|protected|public)?\\s+(static\\s+)?([A-Za-z0-9_<>\\[\\]]+)\\s+([A-Za-z0-9_]+)\\s*\\(([^)]*)\\)\\s*\\{");
        Matcher mm = methodPattern.matcher(code);
        while (mm.find()) {
            String vis = getVisibilitySymbol(mm.group(1));
            cls.methods.add(vis + " " + mm.group(4) + "(): " + mm.group(3));
        }

        return cls;
    }

    private String getVisibilitySymbol(String vis) {
        if (vis == null) return "~";
        switch (vis) {
            case "public": return "+";
            case "private": return "-";
            case "protected": return "#";
            default: return "~";
        }
    }

    private String generatePlantUml() {
        if (graph != null && !graph.types().isEmpty()) {
            StringBuilder sb = new StringBuilder("@startuml\n");
            for (UmlGraph.Type t : graph.types()) {
                String kindStr = UmlGraph.kindLabel(t.kind);
                if (t.kind == UmlGraph.Kind.ABSTRACT) {
                    kindStr = "abstract class";
                }
                sb.append(kindStr).append(" ").append(t.name).append(" {\n");
                for (UmlGraph.Member m : t.members) {
                    sb.append("  ").append(m.method ? "+" : "-").append(" ")
                            .append(m.name);
                    if (m.type != null && !m.type.isEmpty()) {
                        sb.append(": ").append(m.type);
                    }
                    sb.append("\n");
                }
                sb.append("}\n\n");
            }
            for (UmlGraph.Relation r : graph.relations()) {
                if (r.link == UmlGraph.Link.EXTENDS) {
                    sb.append(r.from.name).append(" --|> ").append(r.to.name).append("\n");
                } else if (r.link == UmlGraph.Link.IMPLEMENTS) {
                    sb.append(r.from.name).append(" ..|> ").append(r.to.name).append("\n");
                } else if (r.link == UmlGraph.Link.ASSOCIATION) {
                    sb.append(r.from.name).append(" --> ")
                            .append(r.multiplicity.isEmpty() ? "" : "\"" + r.multiplicity + "\" ")
                            .append(r.to.name).append("\n");
                }
            }
            sb.append("@enduml\n");
            return sb.toString();
        }

        StringBuilder sb = new StringBuilder("@startuml\n");
        for (UmlClass c : umlClasses) {
            sb.append(c.type).append(" ").append(c.name);
            if (c.superClass != null) sb.append(" extends ").append(c.superClass);
            sb.append(" {\n");
            for (String f : c.fields) sb.append("  ").append(f).append("\n");
            for (String m : c.methods) sb.append("  ").append(m).append("\n");
            sb.append("}\n\n");

            for (String impl : c.interfaces) {
                sb.append(c.name).append(" ..|> ").append(impl).append("\n");
            }
        }
        sb.append("@enduml\n");
        return sb.toString();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
