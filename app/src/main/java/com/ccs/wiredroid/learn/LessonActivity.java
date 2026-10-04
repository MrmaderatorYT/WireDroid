package com.ccs.wiredroid.learn;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.analysis.ProblemItem;
import com.ccs.wiredroid.tools.compilers.ProjectCompiler;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.EditorSettingsApplier;
import com.ccs.wiredroid.util.FullScreenHelper;
import com.ccs.wiredroid.util.languages.AstJavaLanguage;

import java.util.List;

import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * Навчальний центр: показує один матеріал обраного розділу нативно з інтерактивним
 * спліт-екран Playground на базі повноцінного {@link CodeEditor}.
 */
public class LessonActivity extends AppCompatActivity {

    public static final String EXTRA_COURSE = "course_id";
    public static final String EXTRA_LESSON = "lesson_id";

    private static final String DEFAULT_PLAYGROUND_CODE =
            "public class Playground {\n"
            + "    public static void main(String[] args) {\n"
            + "        System.out.println(\"⚡ Java Playground ready!\");\n"
            + "        int a = 10;\n"
            + "        int b = 20;\n"
            + "        System.out.println(\"Sum: \" + (a + b));\n"
            + "    }\n"
            + "}\n";

    private AppPreferences appPrefs;
    private AppTheme theme;
    private CourseRegistry courseRegistry;

    private ViewPager2 pager;
    private String courseId;
    private String lessonId;
    private List<Lesson> materials;
    private int currentIndex = 0;
    private boolean destroyed;

    // Playground components
    private View playgroundDividerBar;
    private View playgroundContainer;
    private TextView btnPlaygroundToggle;
    private TextView btnPlaygroundRun;
    private TextView btnPlaygroundClear;
    private TextView btnPlaygroundExpand;
    private TextView btnPlaygroundClose;
    private CodeEditor playgroundEditor;
    private TextView tvPlaygroundOutput;
    private boolean isPlaygroundVisible = false;
    private boolean isPlaygroundFullscreen = false;
    private boolean isPlaygroundRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appPrefs = new AppPreferences(this);
        theme = AppTheme.byId(appPrefs.getThemeId(), appPrefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_material);
        FullScreenHelper.enable(this);

        courseId = getIntent().getStringExtra(EXTRA_COURSE);
        lessonId = getIntent().getStringExtra(EXTRA_LESSON);

        TextView tvTitle = findViewById(R.id.tvMaterialTitle);
        pager = findViewById(R.id.pagerMaterial);
        View btnBack = findViewById(R.id.btnMaterialBack);
        View btnPrev = findViewById(R.id.btnMaterialPrev);
        View btnNext = findViewById(R.id.btnMaterialNext);
        btnPlaygroundToggle = findViewById(R.id.btnPlaygroundToggle);

        View root = findViewById(R.id.materialRoot);
        if (root != null) root.setBackgroundColor(theme.bg);

        View toolbar = findViewById(R.id.materialToolbar);
        if (toolbar != null) toolbar.setBackgroundColor(theme.toolbar);

        View toolbarSep = findViewById(R.id.materialToolbarSep);
        if (toolbarSep != null) toolbarSep.setBackgroundColor(theme.separator);

        View bottomBar = findViewById(R.id.materialBottomBar);
        if (bottomBar != null) bottomBar.setBackgroundColor(theme.toolbar);

        View bottomSep = findViewById(R.id.materialBottomSep);
        if (bottomSep != null) bottomSep.setBackgroundColor(theme.separator);

        pager.setBackgroundColor(theme.bg);
        findViewById(android.R.id.content).setBackgroundColor(theme.bg);

        courseRegistry = CourseRegistry.getInstance(getApplicationContext());
        Course section = courseRegistry.getCourse(courseId);
        if (section == null) {
            Toast.makeText(this, "Section not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        materials = section.allMaterials();

        // знайти поточний матеріал
        for (int i = 0; i < materials.size(); i++) {
            if (materials.get(i).id.equals(lessonId)) { currentIndex = i; break; }
        }
        if (currentIndex >= materials.size()) currentIndex = 0;

        int lang = courseRegistry.getLanguage();
        tvTitle.setTextColor(theme.text);

        pager.setAdapter(new LessonPagerAdapter(materials, theme, lang, this::runSnippet, this::openPlaygroundWithSnippet));
        pager.setCurrentItem(currentIndex, false);
        warmNeighbours(lang);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                currentIndex = position;
                updateTitle();
                updateNavBar();
            }
        });

        btnBack.setOnClickListener(v -> finish());
        btnPrev.setOnClickListener(v -> navigate(-1));
        btnNext.setOnClickListener(v -> navigate(1));

        initPlayground();

        updateTitle();
        updateNavBar();
    }

    private void initPlayground() {
        playgroundDividerBar = findViewById(R.id.playgroundDividerBar);
        playgroundContainer = findViewById(R.id.playgroundContainer);
        btnPlaygroundRun = findViewById(R.id.btnPlaygroundRun);
        btnPlaygroundClear = findViewById(R.id.btnPlaygroundClear);
        btnPlaygroundExpand = findViewById(R.id.btnPlaygroundExpand);
        btnPlaygroundClose = findViewById(R.id.btnPlaygroundClose);
        playgroundEditor = findViewById(R.id.playgroundEditor);
        tvPlaygroundOutput = findViewById(R.id.tvPlaygroundOutput);

        View consoleHeader = findViewById(R.id.playgroundConsoleHeader);
        if (consoleHeader != null) consoleHeader.setBackgroundColor(theme.toolbar);

        View consoleLayout = findViewById(R.id.playgroundConsoleLayout);
        if (consoleLayout != null) consoleLayout.setBackgroundColor(theme.consoleBg);

        View consoleSep = findViewById(R.id.playgroundConsoleSep);
        if (consoleSep != null) consoleSep.setBackgroundColor(theme.separator);

        if (playgroundDividerBar != null) {
            playgroundDividerBar.setBackgroundColor(theme.toolbar);
        }

        // Style Playground Run button
        if (btnPlaygroundRun != null) {
            GradientDrawable runBg = new GradientDrawable();
            runBg.setShape(GradientDrawable.RECTANGLE);
            runBg.setColor(0xFF2E7D32); // Deep Emerald Green
            runBg.setCornerRadius(14 * getResources().getDisplayMetrics().density);
            btnPlaygroundRun.setBackground(runBg);
            btnPlaygroundRun.setOnClickListener(v -> runPlaygroundCode());
        }

        if (btnPlaygroundClear != null) {
            btnPlaygroundClear.setTextColor(theme.textDim);
            btnPlaygroundClear.setOnClickListener(v -> {
                if (playgroundEditor != null) playgroundEditor.setText(DEFAULT_PLAYGROUND_CODE);
                if (tvPlaygroundOutput != null) tvPlaygroundOutput.setText("");
            });
        }

        if (btnPlaygroundExpand != null) {
            btnPlaygroundExpand.setTextColor(theme.textDim);
            btnPlaygroundExpand.setOnClickListener(v -> togglePlaygroundFullscreen());
        }

        if (btnPlaygroundClose != null) {
            btnPlaygroundClose.setTextColor(theme.textDim);
            btnPlaygroundClose.setOnClickListener(v -> hidePlayground());
        }

        View btnConsoleClear = findViewById(R.id.btnPlaygroundConsoleClear);
        if (btnConsoleClear != null) {
            btnConsoleClear.setOnClickListener(v -> {
                if (tvPlaygroundOutput != null) tvPlaygroundOutput.setText("");
            });
        }

        // Configure Sora CodeEditor
        if (playgroundEditor != null) {
            EditorSettingsApplier.apply(playgroundEditor, appPrefs, theme);
            playgroundEditor.setEditorLanguage(new AstJavaLanguage(this, null));
            playgroundEditor.setText(DEFAULT_PLAYGROUND_CODE);
        }

        if (btnPlaygroundToggle != null) {
            btnPlaygroundToggle.setTextColor(theme.accent);
            btnPlaygroundToggle.setOnClickListener(v -> togglePlayground());
        }
    }

    private void togglePlayground() {
        if (isPlaygroundVisible) {
            hidePlayground();
        } else {
            showPlayground();
        }
    }

    private void showPlayground() {
        isPlaygroundVisible = true;
        if (playgroundDividerBar != null) playgroundDividerBar.setVisibility(View.VISIBLE);
        if (playgroundContainer != null) playgroundContainer.setVisibility(View.VISIBLE);

        LinearLayout.LayoutParams pagerLp = (LinearLayout.LayoutParams) pager.getLayoutParams();
        pagerLp.weight = isPlaygroundFullscreen ? 0 : 1;
        pager.setLayoutParams(pagerLp);
        pager.setVisibility(isPlaygroundFullscreen ? View.GONE : View.VISIBLE);

        if (btnPlaygroundToggle != null) {
            btnPlaygroundToggle.setText(R.string.lesson_playground_hide);
        }
    }

    private void hidePlayground() {
        isPlaygroundVisible = false;
        isPlaygroundFullscreen = false;
        if (playgroundDividerBar != null) playgroundDividerBar.setVisibility(View.GONE);
        if (playgroundContainer != null) playgroundContainer.setVisibility(View.GONE);

        LinearLayout.LayoutParams pagerLp = (LinearLayout.LayoutParams) pager.getLayoutParams();
        pagerLp.weight = 1;
        pager.setLayoutParams(pagerLp);
        pager.setVisibility(View.VISIBLE);

        if (btnPlaygroundExpand != null) btnPlaygroundExpand.setText("⤢");
        if (btnPlaygroundToggle != null) {
            btnPlaygroundToggle.setText(R.string.lesson_playground_show);
        }
    }

    private void togglePlaygroundFullscreen() {
        if (!isPlaygroundVisible) {
            showPlayground();
            return;
        }
        isPlaygroundFullscreen = !isPlaygroundFullscreen;
        if (isPlaygroundFullscreen) {
            pager.setVisibility(View.GONE);
            if (btnPlaygroundExpand != null) btnPlaygroundExpand.setText("🗗");
        } else {
            pager.setVisibility(View.VISIBLE);
            LinearLayout.LayoutParams pagerLp = (LinearLayout.LayoutParams) pager.getLayoutParams();
            pagerLp.weight = 1;
            pager.setLayoutParams(pagerLp);
            if (btnPlaygroundExpand != null) btnPlaygroundExpand.setText("⤢");
        }
    }

    public void openPlaygroundWithSnippet(LessonBlock block) {
        if (block == null) return;
        String rawCode = block.executionText != null ? block.executionText : block.text;
        String formatted;
        if (block.runMode == LessonBlock.RUN_JAVA_SOURCE || (rawCode.contains("class ") && rawCode.contains("static void main"))) {
            formatted = rawCode;
        } else {
            formatted = "public class Playground {\n"
                    + "    public static void main(String[] args) throws Exception {\n"
                    + indentCode(rawCode, "        ") + "\n"
                    + "    }\n"
                    + "}\n";
        }

        if (playgroundEditor != null) {
            playgroundEditor.setText(formatted);
        }
        showPlayground();
        if (tvPlaygroundOutput != null) {
            tvPlaygroundOutput.setText("⚡ Loaded snippet into Playground. Tap '▶ Run' to execute.");
            tvPlaygroundOutput.setTextColor(theme.consoleText);
        }
    }

    private static String indentCode(String text, String prefix) {
        if (text == null) return "";
        return prefix + text.replace("\r\n", "\n").replace("\r", "\n")
                .replace("\n", "\n" + prefix);
    }

    private void runPlaygroundCode() {
        if (playgroundEditor == null || isPlaygroundRunning) return;
        String sourceCode = playgroundEditor.getText().toString().trim();
        if (sourceCode.isEmpty()) {
            Toast.makeText(this, "Editor is empty", Toast.LENGTH_SHORT).show();
            return;
        }

        final String executableCode;
        if (sourceCode.contains("class ") && sourceCode.contains("static void main")) {
            executableCode = sourceCode;
        } else {
            executableCode = LessonSnippetRunner.wrapStatements(sourceCode);
        }

        isPlaygroundRunning = true;
        if (btnPlaygroundRun != null) {
            btnPlaygroundRun.setEnabled(false);
            btnPlaygroundRun.setAlpha(0.6f);
            btnPlaygroundRun.setText("⏳ Running");
        }

        if (tvPlaygroundOutput != null) {
            tvPlaygroundOutput.setText("Compiling & executing...");
            tvPlaygroundOutput.setTextColor(theme.consoleText);
        }

        ProjectCompiler.runJavaSnippet(getApplicationContext(), executableCode, new ProjectCompiler.Callback() {
            @Override
            public void onProgress(String message) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    if (tvPlaygroundOutput != null) {
                        tvPlaygroundOutput.setText(message != null ? message : "Processing...");
                    }
                });
            }

            @Override
            public void onResult(String output) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    isPlaygroundRunning = false;
                    if (btnPlaygroundRun != null) {
                        btnPlaygroundRun.setEnabled(true);
                        btnPlaygroundRun.setAlpha(1.0f);
                        btnPlaygroundRun.setText("▶ Run");
                    }
                    if (tvPlaygroundOutput != null) {
                        String out = output != null ? output.trim() : "";
                        if (out.isEmpty()) out = "(Program finished with no output)";
                        tvPlaygroundOutput.setText(out);
                        boolean isErr = out.startsWith("Compilation Error:") || out.startsWith("Error:") || out.startsWith("System Error:");
                        tvPlaygroundOutput.setTextColor(isErr ? theme.errorText : theme.consoleText);
                    }
                });
            }

            @Override
            public void onProblems(List<ProblemItem> problems) {
                // ECJ diagnostic problems are handled in onResult output
            }
        });
    }

    /**
     * A lesson's text lives in its own asset file, so reaching a page costs a
     * read and a JSON parse. {@link Lesson#content(int)} caches what it reads,
     * so paying for the two neighbours here means the reader never pays for them
     * mid-swipe.
     */
    private void warmNeighbours(int lang) {
        final List<Lesson> pages = materials;
        final int index = currentIndex;
        new Thread(() -> {
            for (int offset : new int[] { 1, -1 }) {
                int i = index + offset;
                if (i < 0 || i >= pages.size()) continue;
                try {
                    pages.get(i).content(lang);
                } catch (RuntimeException ignored) {
                }
            }
        }, "lesson-warmup").start();
    }

    private void updateTitle() {
        TextView tvTitle = findViewById(R.id.tvMaterialTitle);
        if (tvTitle != null && currentIndex < materials.size()) {
            tvTitle.setText(materials.get(currentIndex).title(courseRegistry.getLanguage()));
        }
    }

    private void updateNavBar() {
        View prev = findViewById(R.id.btnMaterialPrev);
        View next = findViewById(R.id.btnMaterialNext);
        if (prev != null) prev.setVisibility(currentIndex > 0 ? View.VISIBLE : View.INVISIBLE);
        if (next != null) {
            next.setVisibility(currentIndex < materials.size() - 1 ? View.VISIBLE : View.INVISIBLE);
        }
        TextView position = findViewById(R.id.tvMaterialPosition);
        if (position != null) {
            position.setText(getString(R.string.material_position,
                    currentIndex + 1, materials.size()));
        }
    }

    private void navigate(int delta) {
        int idx = currentIndex + delta;
        if (idx < 0 || idx >= materials.size()) return;
        pager.setCurrentItem(idx, true);
    }

    private void runSnippet(LessonBlock block, LessonBlockAdapter.RunCallback callback) {
        LessonSnippetRunner.run(getApplicationContext(), block, new LessonSnippetRunner.Callback() {
            @Override
            public void onProgress(String message) {
                if (!destroyed) callback.onProgress(message);
            }

            @Override
            public void onResult(String output) {
                if (!destroyed) callback.onResult(output);
            }
        });
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }
}

