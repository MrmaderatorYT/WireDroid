package com.ccs.wiredroid.ui;
import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;
import com.ccs.wiredroid.util.languages.WireDroidLanguage;
import com.ccs.wiredroid.util.EditorSettingsApplier;
import com.ccs.wiredroid.tools.compilers.ProjectCompiler;
import com.ccs.wiredroid.analysis.ProblemItem;

import android.os.Bundle;
import android.text.Html;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.rosemoe.sora.widget.CodeEditor;

public class TutorialActivity extends AppCompatActivity {

    private AppPreferences appPrefs;
    private AppTheme theme;

    private TextView tvTitle;
    private TextView tabTheory;
    private TextView tabPractice;
    private WebView wvTheory;
    private View layoutPractice;

    private CodeEditor playgroundEditor;
    private TextView playgroundConsole;
    private View btnResetCode;
    private View btnRunPlayground;

    private String lessonFile;
    private String lessonTitle;
    private List<String> codeSnippets = new ArrayList<>();
    private String originalCode = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appPrefs = new AppPreferences(this);
        theme = AppTheme.byId(appPrefs.getThemeId(), appPrefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tutorial);
        FullScreenHelper.enable(this);

        lessonFile = getIntent().getStringExtra("lesson_file");
        lessonTitle = getIntent().getStringExtra("lesson_title");

        bindViews();
        setupStyles();
        setupTabs();
        loadLessonContent();
    }

    private void bindViews() {
        findViewById(R.id.btnTutorialBack).setOnClickListener(v -> finish());
        tvTitle = findViewById(R.id.tvTutorialTitle);
        tvTitle.setText(lessonTitle);

        tabTheory = findViewById(R.id.tabTheory);
        tabPractice = findViewById(R.id.tabPractice);
        wvTheory = findViewById(R.id.wvTheory);
        layoutPractice = findViewById(R.id.layoutPractice);

        playgroundEditor = findViewById(R.id.playgroundEditor);
        playgroundConsole = findViewById(R.id.playgroundConsole);
        btnResetCode = findViewById(R.id.btnResetCode);
        btnRunPlayground = findViewById(R.id.btnRunPlayground);
    }

    private void setupStyles() {
        playgroundEditor.setEditorLanguage(new WireDroidLanguage(this, null));
        EditorSettingsApplier.apply(playgroundEditor, appPrefs, theme);

        btnResetCode.setOnClickListener(v -> resetCode());
        btnRunPlayground.setOnClickListener(v -> runCode());

        WebSettings ws = wvTheory.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDefaultTextEncodingName("utf-8");
    }

    private void setupTabs() {
        tabTheory.setOnClickListener(v -> {
            tabTheory.setBackgroundColor(0xFF3574F0);
            tabTheory.setTextColor(0xFFFFFFFF);
            tabPractice.setBackgroundColor(0xFF1E1E20);
            tabPractice.setTextColor(0xFF808080);
            wvTheory.setVisibility(View.VISIBLE);
            layoutPractice.setVisibility(View.GONE);
        });

        tabPractice.setOnClickListener(v -> {
            tabPractice.setBackgroundColor(0xFF3574F0);
            tabPractice.setTextColor(0xFFFFFFFF);
            tabTheory.setBackgroundColor(0xFF1E1E20);
            tabTheory.setTextColor(0xFF808080);
            wvTheory.setVisibility(View.GONE);
            layoutPractice.setVisibility(View.VISIBLE);
        });
    }

    private void loadLessonContent() {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(getAssets().open("tutorials/" + lessonFile), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();

            String rawHtml = sb.toString();

            Pattern p = Pattern.compile("<pre class=\"brush:java;\">(.*?)</pre>", Pattern.DOTALL);
            Matcher m = p.matcher(rawHtml);
            while (m.find()) {
                String code = m.group(1);
                code = Html.fromHtml(code).toString();
                codeSnippets.add(code);
            }

            if (!codeSnippets.isEmpty()) {
                originalCode = codeSnippets.get(0);
                playgroundEditor.setText(originalCode);
                playgroundEditor.setEditable(true);
            } else {
                originalCode = "public class Program {\n    public static void main(String[] args) {\n        System.out.println(\"Hello World!\");\n    }\n}";
                playgroundEditor.setText(originalCode);
                playgroundEditor.setEditable(true);
            }

            String styledHtml = injectThemeStyles(rawHtml);
            wvTheory.loadDataWithBaseURL("file:///android_asset/tutorials/", styledHtml, "text/html", "UTF-8", null);

        } catch (Exception e) {
            Toast.makeText(this, "Помилка завантаження матеріалу: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private String injectThemeStyles(String htmlContent) {
        String hexBg = String.format("#%06X", (0xFFFFFF & theme.bg));
        String hexText = String.format("#%06X", (0xFFFFFF & theme.text));
        String hexTextDim = String.format("#%06X", (0xFFFFFF & theme.textDim));
        String hexAccent = String.format("#%06X", (0xFFFFFF & theme.accent));
        String hexKeyword = String.format("#%06X", (0xFFFFFF & theme.editorKeyword));
        String hexString = String.format("#%06X", (0xFFFFFF & theme.editorString));
        
        String styleOverride = "<style>"
                + "body { background-color: " + hexBg + "; color: " + hexText + "; font-family: sans-serif; padding: 16px; line-height: 1.6; }\n"
                + "h1, h2, h3 { color: " + hexText + "; }\n"
                + "a { color: " + hexAccent + "; text-decoration: none; }\n"
                + "pre { background-color: " + (theme.dark ? "#2B2D30" : "#F5F5F5") + "; padding: 12px; border-radius: 4px; overflow-x: auto; color: " + hexText + "; }\n"
                + "code { font-family: monospace; }\n"
                + ".keyword { color: " + hexKeyword + "; }\n"
                + ".string { color: " + hexString + "; }\n"
                + ".nav, .socBlock, header, footer, .socbtns, #header, #footer { display: none !important; }\n"
                + "</style>";
        return htmlContent.replace("</head>", styleOverride + "</head>");
    }

    private void resetCode() {
        playgroundEditor.setText(originalCode);
        playgroundConsole.setText("Код відновлено до початкового стану.");
    }

    private void runCode() {
        String code = playgroundEditor.getText().toString();
        playgroundConsole.setText("Компіляція та запуск...\n");

        ProjectCompiler.runSingleSource(this, code, new ProjectCompiler.Callback() {
            @Override
            public void onProgress(String message) {
                runOnUiThread(() -> playgroundConsole.append(message + "\n"));
            }

            @Override
            public void onResult(String output) {
                runOnUiThread(() -> {
                    if (output == null || output.trim().isEmpty()) {
                        playgroundConsole.append("\nПрограма завершилась успішно.");
                    } else {
                        playgroundConsole.append("\n--- Вивід програми ---\n" + output.trim());
                    }
                });
            }

            @Override
            public void onProblems(List<ProblemItem> problems) {
                if (problems != null && !problems.isEmpty()) {
                    runOnUiThread(() -> {
                        playgroundConsole.append("\n--- Помилки компіляції ---\n");
                        for (ProblemItem p : problems) {
                            playgroundConsole.append("Рядок " + p.line + ": " + p.message + "\n");
                        }
                    });
                }
            }
        });
    }
}
