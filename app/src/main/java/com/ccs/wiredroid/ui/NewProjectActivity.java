package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.CompoundButtonCompat;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.maven.MavenPaths;
import com.ccs.wiredroid.project.ProjectCreator;
import com.ccs.wiredroid.project.ProjectRuntime;
import com.ccs.wiredroid.project.ProjectTemplates;
import com.ccs.wiredroid.project.ProjectTemplates.Template;
import com.ccs.wiredroid.project.SampleRegistry;
import com.ccs.wiredroid.project.SampleRegistry.SampleItem;
import com.ccs.wiredroid.tools.compilers.JavaVersions;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.Colors;
import com.ccs.wiredroid.util.FullScreenHelper;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IntelliJ's New Project dialog, rebuilt for a phone.
 *
 * <p>An Activity and not a dialog: the form is a template selector plus up to
 * seven controls and a structure preview, the soft keyboard covers half of a
 * 720x1600 screen and a dialog cannot keep Create above it, creation does file
 * I/O that needs a visible busy state, and the whole thing has to survive
 * rotation. Views are built in code, like {@link ProjectStructureActivity}, so
 * there is no XML layout to keep in sync across {@code layout/} and
 * {@code layout-port/}.</p>
 *
 * <p>IntelliJ's left-hand list becomes a pinned horizontal rail of template
 * chips directly under the toolbar: the template stays visible and one tap away
 * while the form is filled, which is what the desktop's two panes buy and what a
 * two-step drill-down — today's list dialog followed by a form dialog — throws
 * away. Below it the form shows only the fields the chosen factory actually
 * consumes, and a live tree of what will land on disk.</p>
 *
 * <p>One rule governs sparseness: an option that is real for a sibling template
 * but impossible for this one is <em>disabled with a reason</em> (Maven under
 * Kotlin), while an option whose concept does not exist for the template is
 * <em>removed</em> (a build system for a bytecode scratch project). A greyed
 * control implies "could be turned on", which would be its own lie.</p>
 *
 * <p>The caller opens the created project; this screen only reports the path
 * through {@link #EXTRA_PROJECT_PATH}.</p>
 */
public class NewProjectActivity extends AppCompatActivity {

    /** Absolute path of the created project, returned with {@code RESULT_OK}. */
    public static final String EXTRA_PROJECT_PATH = "np_project_path";

    /**
     * Absolute path of a created scratch file, returned with {@code RESULT_OK}.
     *
     * <p>Separate from {@link #EXTRA_PROJECT_PATH} because the two mean opposite
     * things to the caller: a project path replaces the open project, a scratch
     * path is just a file to open. Exactly one of them is ever set.</p>
     */
    public static final String EXTRA_SCRATCH_PATH = "np_scratch_path";

    private static final String S_TEMPLATE = "np_s_template";
    private static final String S_NAME = "np_s_name";
    private static final String S_MAVEN = "np_s_maven";
    private static final String S_JAVA_MAVEN = "np_s_java_maven";
    private static final String S_JDK = "np_s_jdk";
    private static final String S_RUNTIME = "np_s_runtime";
    private static final String S_GIT = "np_s_git";
    private static final String S_GROUP = "np_s_group";
    private static final String S_ARTIFACT = "np_s_artifact";
    private static final String S_ADVANCED = "np_s_advanced";
    private static final String S_GROUP_TOUCHED = "np_s_group_touched";
    private static final String S_ARTIFACT_TOUCHED = "np_s_artifact_touched";
    private static final String S_SAMPLE_ID = "np_s_sample_id";
    private static final String S_SCRATCH_KOTLIN = "np_s_scratch_kotlin";
    private static final String S_SCRATCH_NAME = "np_s_scratch_name";
    private static final String S_LANG_VERSION = "np_s_lang_version";

    /** External storage is slow enough that the exists() check is not per-keystroke. */
    private static final long NAME_CHECK_DELAY_MS = 200L;

    private AppPreferences prefs;
    private AppTheme theme;
    private Typeface mono;

    private Template template = Template.CIRCUIT;
    private String selectedSampleId = "hello_world";
    /**
     * Which build script the new project gets.
     *
     * <p>{@code maven} was a boolean while there were two options. Ant is a
     * third, and the segments below are what the user actually picks from.</p>
     */
    private com.ccs.wiredroid.project.BuildSystem.Kind buildChoice =
            com.ccs.wiredroid.project.BuildSystem.Kind.MAVEN;
    /** The build system chosen while a Maven-capable template was selected. */
    private boolean javaMaven = true;
    private ProjectRuntime.Mode runtimeMode = ProjectRuntime.Mode.ART;
    private boolean advancedExpanded;
    private boolean groupTouched;
    private boolean artifactTouched;
    private boolean creating;
    private boolean collides;
    private volatile File baseDir;
    private long nameCheckToken;

    private boolean examplesMode;
    private String wireExampleId = "blink-uno";
    private org.json.JSONArray wireExamples = new org.json.JSONArray();
    private LinearLayout wireExamplesBlock;
    private TextView newModeChip, examplesModeChip, wireExampleDescription;
    private String suggestedExampleName = "";
    private HorizontalScrollView railScroller;
    private LinearLayout rail;
    private TextView captionView;
    private LinearLayout sampleBlock;
    private Spinner sampleSpinner;
    private TextView sampleDescView;
    private LinearLayout nameBlock;
    private EditText nameEdit;
    private TextView nameError;
    private TextView locationView;
    private TextView locationNotice;
    private LinearLayout buildBlock;
    private TextView segMaven;
    private TextView segGradle;
    private TextView segAnt;
    private TextView buildNote;
    private LinearLayout runtimeBlock;
    private TextView segArt;
    private TextView segJavaSe;
    private TextView runtimeNote;
    private JdkPicker jdk;
    private View jdkBlock;
    private Spinner languageVersionSpinner;
    private TextView languageVersionLabel;
    private TextView languageVersionNote;
    private View languageVersionBlock;
    private List<String> languageVersions = java.util.Collections.emptyList();
    private Template languageVersionsFor;
    private boolean syncingLanguageVersion;
    private final java.util.Map<Template, String> languageVersionChoice =
            new java.util.EnumMap<>(Template.class);
    private LinearLayout gitBlock;
    private CheckBox gitCheck;
    private CheckBox nativeCheck;
    private LinearLayout nativeBlock;
    private LinearLayout nativeBackendBlock;
    private TextView nativeTccRow;
    private TextView nativeNdkRow;
    private boolean nativeUseNdk;
    private LinearLayout advancedBlock;
    private TextView advancedHeader;
    /** Built on the first expand; null while the section is still collapsed. */
    private LinearLayout advancedBody;
    private EditText groupEdit;
    private TextView groupError;
    private LinearLayout artifactBlock;
    private EditText artifactEdit;
    private TextView artifactNote;
    /** Coordinates carried across rotation until the fields that hold them exist. */
    private String pendingGroup = "";
    private String pendingArtifact = "";
    private TextView previewView;
    /**
     * Kept so the two headings can be retitled per template.
     *
     * <p>"Project will be created in" and "Project structure" are lies for a
     * scratch, which is one file and no project at all.</p>
     */
    private TextView locationLabel;
    private TextView previewLabel;

    // ── Scratch template ──
    private LinearLayout scratchBlock;
    private EditText scratchNameEdit;
    /** One segment per scratch language, in the order they are offered. */
    private final java.util.Map<com.ccs.wiredroid.scratch.ScratchLanguage, TextView> scratchSegments =
            new java.util.LinkedHashMap<>();
    private com.ccs.wiredroid.scratch.ScratchLanguage scratchLanguage = com.ccs.wiredroid.scratch.ScratchLanguage.JAVA;
    private Button createButton;
    private Button cancelButton;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable nameCheck = this::runNameCheck;
    private volatile boolean finished;

    /**
     * Keep the wizard usable with a physical keyboard.  The form is built
     * dynamically, so handling these at the Activity level also works when
     * focus is inside one of the optional/advanced fields.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                finish();
                return true;
            }
            if ((event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER)
                    && !event.isAltPressed()) {
                // Ctrl+Enter is an explicit submit shortcut. Plain Enter is
                // also submit because all wizard inputs are single-line.
                if (createButton != null && createButton.isEnabled()) {
                    startCreate();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** Opens the wizard; the result carries {@link #EXTRA_PROJECT_PATH}. */
    public static void launchForResult(Activity activity, int requestCode) {
        activity.startActivityForResult(new Intent(activity, NewProjectActivity.class), requestCode);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        mono = prefs.resolveTypeface();

        if (savedInstanceState != null) {
            template = ProjectTemplates.byOrdinal(savedInstanceState.getInt(S_TEMPLATE, 0));
            buildChoice = com.ccs.wiredroid.project.BuildSystem.Kind.valueOf(
                    savedInstanceState.getString(S_MAVEN,
                            com.ccs.wiredroid.project.BuildSystem.Kind.MAVEN.name()));
            javaMaven = savedInstanceState.getBoolean(S_JAVA_MAVEN, true);
            runtimeMode = ProjectRuntime.Mode.fromId(
                    savedInstanceState.getString(S_RUNTIME));
            advancedExpanded = savedInstanceState.getBoolean(S_ADVANCED, false);
            groupTouched = savedInstanceState.getBoolean(S_GROUP_TOUCHED, false);
            artifactTouched = savedInstanceState.getBoolean(S_ARTIFACT_TOUCHED, false);
            selectedSampleId = savedInstanceState.getString(S_SAMPLE_ID, "hello_world");
            scratchLanguage = com.ccs.wiredroid.scratch.ScratchLanguage.valueOf(savedInstanceState.getString(
                    S_SCRATCH_KOTLIN, com.ccs.wiredroid.scratch.ScratchLanguage.JAVA.name()));
        } else if (getIntent() != null && getIntent().hasExtra("initial_template")) {
            template = ProjectTemplates.byOrdinal(getIntent().getIntExtra("initial_template", 0));
        }

        examplesMode = savedInstanceState != null && savedInstanceState.getBoolean("wire_examples_mode", false);
        if (savedInstanceState != null) wireExampleId = savedInstanceState.getString("wire_example_id", "blink-uno");
        try { wireExamples = com.ccs.wiredroid.wire.WireExamples.list(this); }
        catch (Exception e) { Toast.makeText(this, "Не вдалося завантажити приклади: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        setContentView(buildRoot());
        FullScreenHelper.enable(this);

        // FullScreenHelper drops decor fitting on API 35+, where adjustResize
        // alone no longer lifts the bottom bar above the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content),
                (v, windowInsets) -> {
                    Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
                    v.setPadding(bars.left, bars.top, bars.right,
                            Math.max(bars.bottom, ime.bottom));
                    return windowInsets;
                });

        if (savedInstanceState != null) {
            nameEdit.setText(savedInstanceState.getString(S_NAME, ""));
            pendingGroup = savedInstanceState.getString(S_GROUP, "");
            pendingArtifact = savedInstanceState.getString(S_ARTIFACT, "");
            gitCheck.setChecked(savedInstanceState.getBoolean(S_GIT, false));
            String code = savedInstanceState.getString(S_JDK);
            if (code != null) jdk.setSelectedCode(code);
            String languageVersion = savedInstanceState.getString(S_LANG_VERSION);
            if (languageVersion != null) languageVersionChoice.put(template, languageVersion);
            scratchNameEdit.setText(savedInstanceState.getString(S_SCRATCH_NAME, ""));
        } else if (template == Template.SAMPLES) {
            SampleItem item = SampleRegistry.getOrDefault(selectedSampleId);
            nameEdit.setText(item.defaultProjectName);
        }

        applyTemplate();
        resolveBaseDir();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("wire_examples_mode", examplesMode);
        outState.putString("wire_example_id", wireExampleId);
        outState.putInt(S_TEMPLATE, template.ordinal());
        outState.putString(S_NAME, nameEdit.getText().toString());
        outState.putString(S_MAVEN, buildChoice.name());
        outState.putBoolean(S_JAVA_MAVEN, javaMaven);
        outState.putString(S_JDK, jdk.selectedCode());
        outState.putString(S_SCRATCH_KOTLIN, scratchLanguage.name());
        String languageVersion = languageVersionChoice.get(template);
        if (languageVersion != null) outState.putString(S_LANG_VERSION, languageVersion);
        if (scratchNameEdit != null) {
            outState.putString(S_SCRATCH_NAME, scratchNameEdit.getText().toString());
        }
        outState.putString(S_RUNTIME, runtimeMode.id);
        outState.putBoolean(S_GIT, gitCheck.isChecked());
        outState.putString(S_GROUP, groupText());
        outState.putString(S_ARTIFACT, artifactText());
        outState.putBoolean(S_ADVANCED, advancedExpanded);
        outState.putBoolean(S_GROUP_TOUCHED, groupTouched);
        outState.putBoolean(S_ARTIFACT_TOUCHED, artifactTouched);
        outState.putString(S_SAMPLE_ID, selectedSampleId);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        finished = true;
        ui.removeCallbacks(nameCheck);
        io.shutdownNow();
    }

    // ── Shell ─────────────────────────────────────────────────────────────

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle(R.string.np_title);
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar);

        root.addView(buildRail());
        root.addView(separator());

        captionView = new TextView(this);
        captionView.setTextColor(theme.textDim);
        captionView.setTextSize(12);
        captionView.setPadding(dp(16), dp(8), dp(16), dp(4));
        root.addView(captionView);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(4), dp(16), dp(16));
        buildForm(column);
        scroll.addView(column);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(separator());
        root.addView(buildBottomBar());
        return root;
    }

    private View buildRail() {
        rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.HORIZONTAL);
        rail.setPadding(dp(12), dp(8), dp(12), dp(8));

        newModeChip = modeChip("Новий проєкт", false);
        rail.addView(newModeChip);
        TextView divider = new TextView(this);
        divider.setText("|"); divider.setTextColor(theme.textDim);
        divider.setGravity(Gravity.CENTER); divider.setPadding(dp(8), dp(8), dp(8), dp(8));
        rail.addView(divider);
        examplesModeChip = modeChip("Приклади", true);
        rail.addView(examplesModeChip);

        railScroller = new HorizontalScrollView(this);
        railScroller.setHorizontalScrollBarEnabled(false);
        railScroller.setBackgroundColor(theme.bg);
        railScroller.addView(rail);
        return railScroller;
    }

    private TextView modeChip(String title, boolean examples) {
        TextView chip = new TextView(this);
        chip.setText(title); chip.setTextSize(13); chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        chip.setOnClickListener(v -> {
            examplesMode = examples;
            if (examples) suggestExampleName();
            applyTemplate();
        });
        return chip;
    }

    private org.json.JSONObject selectedWireExample() {
        for (int i = 0; i < wireExamples.length(); i++) {
            org.json.JSONObject item = wireExamples.optJSONObject(i);
            if (item != null && wireExampleId.equals(item.optString("id"))) return item;
        }
        return new org.json.JSONObject();
    }

    private void suggestExampleName() {
        if (nameEdit == null) return;
        String current = nameEdit.getText().toString();
        if (current.isEmpty() || current.equals(suggestedExampleName)) {
            suggestedExampleName = selectedWireExample().optString("projectName", "Embedded_Project");
            nameEdit.setText(suggestedExampleName);
        }
    }

    private View buildWireExamplesBlock() {
        TextView title = new TextView(this);
        title.setText("Готова плата, з’єднання та код C++"); title.setTextColor(theme.textDim);
        Spinner picker = new Spinner(this);
        picker.setContentDescription("Вибір прикладу");
        java.util.List<String> titles = new java.util.ArrayList<>();
        int selected = 0;
        for (int i = 0; i < wireExamples.length(); i++) {
            org.json.JSONObject item = wireExamples.optJSONObject(i);
            titles.add(item.optString("title"));
            if (wireExampleId.equals(item.optString("id"))) selected = i;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, titles) {
            @Override public View getView(int position, View view, ViewGroup parent) {
                TextView text = (TextView) super.getView(position, view, parent);
                text.setTextColor(theme.text); return text;
            }
            @Override public View getDropDownView(int position, View view, ViewGroup parent) {
                TextView text = (TextView) super.getDropDownView(position, view, parent);
                text.setTextColor(theme.text); text.setBackgroundColor(theme.toolbar);
                text.setPadding(dp(12), dp(12), dp(12), dp(12)); return text;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        picker.setAdapter(adapter); picker.setSelection(selected);
        wireExampleDescription = new TextView(this);
        wireExampleDescription.setTextColor(theme.textDim);
        wireExampleDescription.setText(selectedWireExample().optString("description"));
        picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) {}
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                wireExampleId = wireExamples.optJSONObject(position).optString("id");
                wireExampleDescription.setText(selectedWireExample().optString("description"));
                if (examplesMode) suggestExampleName();
                if (previewView != null) refresh();
            }
        });
        wireExamplesBlock = block(title, picker, wireExampleDescription);
        return wireExamplesBlock;
    }

    /**
     * Language pair and file name for {@link Template#SCRATCH}.
     *
     * <p>Its own block rather than a reuse of the project name field: the label,
     * the validation and the default all differ, and an empty name is valid here
     * — it becomes {@code Scratch.java}.</p>
     */
    private View buildScratchBlock() {
        LinearLayout segments = new LinearLayout(this);
        segments.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable frame = new GradientDrawable();
        frame.setCornerRadius(dp(8));
        frame.setStroke(dp(1), theme.separator);
        segments.setBackground(frame);
        // Five now rather than two. The labels are the language names, which
        // need no translation, so they come from the enum rather than from
        // five more string resources.
        for (com.ccs.wiredroid.scratch.ScratchLanguage language : com.ccs.wiredroid.scratch.ScratchLanguage.values()) {
            TextView chip = segment(language.displayName, () -> selectScratchLanguage(language));
            scratchSegments.put(language, chip);
            segments.addView(chip);
        }

        scratchNameEdit = newEdit(getString(R.string.scratch_name_hint));
        scratchNameEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        scratchNameEdit.setSingleLine(true);
        scratchNameEdit.addTextChangedListener(new SimpleWatcher(this::refresh));

        TextView note = new TextView(this);
        note.setTextColor(theme.textDim);
        note.setTextSize(11);
        note.setText(R.string.np_scratch_note);

        scratchBlock = block(label(R.string.np_scratch_language), segments,
                scratchNameEdit, note);
        scratchBlock.setVisibility(View.GONE);
        return scratchBlock;
    }

    private void selectScratchLanguage(com.ccs.wiredroid.scratch.ScratchLanguage language) {
        if (scratchLanguage == language) return;
        scratchLanguage = language;
        applyTemplate();
    }

    private View railDivider() {
        View line = new View(this);
        line.setBackgroundColor(theme.separator);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(24));
        lp.rightMargin = dp(8);
        lp.gravity = Gravity.CENTER_VERTICAL;
        return applyParams(line, lp);
    }

    private void buildForm(LinearLayout column) {
        // ── Samples Selector (shown when Template.SAMPLES selected) ──
        column.addView(buildWireExamplesBlock());
        column.addView(buildSampleBlock());

        // ── Scratch (shown when Template.SCRATCH selected) ──
        column.addView(buildScratchBlock());

        // ── Name ──
        nameEdit = newEdit(getString(R.string.np_name_hint));
        nameEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        nameEdit.setSingleLine(true);
        nameEdit.addTextChangedListener(new SimpleWatcher(() -> {
            syncDerivedFields();
            scheduleNameCheck();
            refresh();
        }));
        nameError = newErrorLine();
        nameBlock = block(label(R.string.np_name_label), nameEdit, nameError);
        column.addView(nameBlock);

        // ── Location ──
        locationView = new TextView(this);
        locationView.setTextColor(theme.text);
        locationView.setTextSize(12);
        locationView.setTypeface(mono);
        locationView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        locationView.setSingleLine(true);
        locationNotice = newErrorLine();
        locationLabel = label(R.string.np_location_label);
        column.addView(block(locationLabel, locationView, locationNotice));

        // ── Build system ──
        LinearLayout segments = new LinearLayout(this);
        segments.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable frame = new GradientDrawable();
        frame.setCornerRadius(dp(10));
        frame.setStroke(dp(1), theme.separator);
        segments.setBackground(frame);
        segMaven = segment(R.string.np_build_maven,
                () -> selectBuildSystem(com.ccs.wiredroid.project.BuildSystem.Kind.MAVEN));
        segGradle = segment(R.string.np_build_gradle,
                () -> selectBuildSystem(com.ccs.wiredroid.project.BuildSystem.Kind.GRADLE));
        segAnt = segment(R.string.np_build_ant,
                () -> selectBuildSystem(com.ccs.wiredroid.project.BuildSystem.Kind.ANT));
        segments.addView(segMaven);
        segments.addView(segGradle);
        segments.addView(segAnt);
        buildNote = new TextView(this);
        buildNote.setTextColor(theme.textDim);
        buildNote.setTextSize(11);
        buildNote.setText(R.string.np_kotlin_gradle_only);
        buildBlock = block(label(R.string.np_build_label), segments, buildNote);
        column.addView(buildBlock);

        // ── Execution runtime ──
        LinearLayout runtimeSegments = new LinearLayout(this);
        runtimeSegments.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable runtimeFrame = new GradientDrawable();
        runtimeFrame.setCornerRadius(dp(8));
        runtimeFrame.setStroke(dp(1), theme.separator);
        runtimeSegments.setBackground(runtimeFrame);
        segArt = segment(R.string.np_runtime_art,
                () -> selectRuntime(ProjectRuntime.Mode.ART));
        segJavaSe = segment(R.string.np_runtime_java_se,
                () -> selectRuntime(ProjectRuntime.Mode.JAVA_SE_21));
        runtimeSegments.addView(segArt);
        runtimeSegments.addView(segJavaSe);
        runtimeNote = new TextView(this);
        runtimeNote.setTextColor(theme.textDim);
        runtimeNote.setTextSize(11);
        runtimeBlock = block(label(R.string.np_runtime_label), runtimeSegments, runtimeNote);
        column.addView(runtimeBlock);

        // ── Target JDK ──
        // Reused whole so this screen and Project Structure offer one list.
        // The global default is read straight from preferences: it is what
        // ProjectJdk would return here, without pulling in a class whose build
        // file parsing this screen never reaches.
        jdk = new JdkPicker(this, theme, JavaVersions.normalize(prefs.getJavaTarget()));
        jdkBlock = jdk.getView();
        column.addView(jdkBlock);

        // ── Language version ──
        // Kotlin, Scala, Groovy and Clojure each carry a version of their own.
        // The picker above is Java's, and showing only that on a Kotlin project
        // asked the wrong question — this block asks the right one.
        languageVersionLabel = label(R.string.settings_language_version);
        languageVersionSpinner = new Spinner(this);
        languageVersionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (syncingLanguageVersion || position >= languageVersions.size()) return;
                languageVersionChoice.put(template, languageVersions.get(position));
                // The caption names the version, and it is applyTemplate that
                // draws it — refresh alone would leave it naming the old one.
                updateCaption();
                refresh();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        languageVersionNote = new TextView(this);
        languageVersionNote.setTextColor(theme.textDim);
        languageVersionNote.setTextSize(11);
        languageVersionBlock = block(languageVersionLabel, languageVersionSpinner,
                languageVersionNote);
        column.addView(languageVersionBlock);

        // ── Git ──
        gitCheck = new CheckBox(this);
        gitCheck.setText(R.string.np_git_label);
        gitCheck.setTextColor(theme.text);
        gitCheck.setTextSize(13);
        CompoundButtonCompat.setButtonTintList(gitCheck, ColorStateList.valueOf(theme.accent));
        gitCheck.setOnCheckedChangeListener((b, checked) -> refresh());
        TextView gitNote = new TextView(this);
        gitNote.setTextColor(theme.textDim);
        gitNote.setTextSize(11);
        gitNote.setText(R.string.np_git_note);
        gitBlock = block(gitCheck, gitNote);
        column.addView(gitBlock);

        // ── Native code (JNI) ──
        // Asked here rather than in settings because it describes the project,
        // not the person: one project has a JNI layer, the next does not.
        nativeCheck = new CheckBox(this);
        nativeCheck.setText(R.string.np_native_label);
        nativeCheck.setTextColor(theme.text);
        nativeCheck.setTextSize(13);
        CompoundButtonCompat.setButtonTintList(nativeCheck, ColorStateList.valueOf(theme.accent));
        nativeCheck.setOnCheckedChangeListener((b, checked) -> refresh());
        TextView nativeNote = new TextView(this);
        nativeNote.setTextColor(theme.textDim);
        nativeNote.setTextSize(11);
        nativeNote.setText(R.string.np_native_note);

        // The two rows stay unbuilt until the box is ticked: the checkbox starts
        // clear, so on the first frame they are a pair of ripple backgrounds
        // nobody can see.
        nativeBackendBlock = new LinearLayout(this);
        nativeBackendBlock.setOrientation(LinearLayout.VERTICAL);

        nativeBlock = block(nativeCheck, nativeNote);
        nativeBlock.addView(nativeBackendBlock);
        column.addView(nativeBlock);

        // ── Advanced settings ──
        advancedHeader = new TextView(this);
        advancedHeader.setTextColor(theme.accent);
        advancedHeader.setTextSize(13);
        advancedHeader.setPadding(dp(2), dp(12), dp(2), dp(12));
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        if (ripple.resourceId != 0) advancedHeader.setBackgroundResource(ripple.resourceId);
        advancedHeader.setOnClickListener(v -> {
            advancedExpanded = !advancedExpanded;
            applyTemplate();
        });

        advancedBlock = new LinearLayout(this);
        advancedBlock.setOrientation(LinearLayout.VERTICAL);
        advancedBlock.addView(advancedHeader);
        column.addView(advancedBlock);

        // ── Structure preview ──
        previewView = new TextView(this);
        previewView.setTypeface(mono);
        previewView.setTextSize(11);
        previewView.setTextColor(theme.consoleText);
        previewView.setBackgroundColor(theme.consoleBg);
        previewView.setPadding(dp(10), dp(8), dp(10), dp(8));
        previewLabel = label(R.string.np_preview_label);
        column.addView(block(previewLabel, previewView));
    }

    /**
     * Fills the Advanced section the first time it is opened.
     *
     * <p>The section starts collapsed and most projects are created without it,
     * so its two text fields — the costliest widgets on the form — are not worth
     * building before the first frame. Until then their values live in
     * {@link #pendingGroup} and {@link #pendingArtifact}, which is also how they
     * survive a rotation taken while the section was shut.</p>
     */
    private void ensureAdvancedBody() {
        if (advancedBody != null) return;

        groupEdit = newEdit(getString(R.string.np_group_hint));
        groupEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        groupEdit.setSingleLine(true);
        groupEdit.setText(pendingGroup);
        groupEdit.addTextChangedListener(new SimpleWatcher(() -> {
            if (groupEdit.hasFocus()) groupTouched = true;
            refresh();
        }));
        groupError = newErrorLine();

        artifactEdit = newEdit(getString(R.string.np_artifact_hint));
        artifactEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        artifactEdit.setSingleLine(true);
        artifactEdit.setText(pendingArtifact);
        artifactEdit.addTextChangedListener(new SimpleWatcher(() -> {
            if (artifactEdit.hasFocus()) artifactTouched = true;
            refresh();
        }));
        artifactNote = new TextView(this);
        artifactNote.setTextColor(theme.textDim);
        artifactNote.setTextSize(11);
        artifactNote.setVisibility(View.GONE);
        artifactBlock = block(label(R.string.np_artifact_label), artifactEdit, artifactNote);

        advancedBody = new LinearLayout(this);
        advancedBody.setOrientation(LinearLayout.VERTICAL);
        advancedBody.addView(block(label(R.string.np_group_label), groupEdit, groupError));
        advancedBody.addView(artifactBlock);
        advancedBlock.addView(advancedBody);

        syncDerivedFields();
    }

    private View buildSampleBlock() {
        sampleBlock = new LinearLayout(this);
        sampleBlock.setOrientation(LinearLayout.VERTICAL);

        sampleSpinner = new Spinner(this);
        List<SampleItem> samples = SampleRegistry.getAll();
        ArrayAdapter<SampleItem> adapter = new ArrayAdapter<SampleItem>(this,
                android.R.layout.simple_spinner_item, samples) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(theme.text);
                tv.setTextSize(14);
                return tv;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
                tv.setTextColor(theme.text);
                tv.setBackgroundColor(theme.toolbar);
                tv.setPadding(dp(12), dp(10), dp(12), dp(10));
                return tv;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sampleSpinner.setAdapter(adapter);

        if (theme != null) {
            GradientDrawable popupBg = new GradientDrawable();
            popupBg.setColor(theme.toolbar);
            popupBg.setStroke(dp(1), theme.separator);
            popupBg.setCornerRadius(dp(8));
            sampleSpinner.setPopupBackgroundDrawable(popupBg);
        }

        int selIdx = 0;
        for (int i = 0; i < samples.size(); i++) {
            if (samples.get(i).id.equals(selectedSampleId)) {
                selIdx = i;
                break;
            }
        }
        sampleSpinner.setSelection(selIdx);

        sampleDescView = new TextView(this);
        sampleDescView.setTextColor(theme.textDim);
        sampleDescView.setTextSize(11);
        sampleDescView.setPadding(0, dp(4), 0, dp(6));
        sampleDescView.setText(samples.get(selIdx).description);

        sampleSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                SampleItem item = samples.get(position);
                selectedSampleId = item.id;
                sampleDescView.setText(item.description);
                String current = nameEdit.getText().toString().trim();
                if (current.isEmpty() || current.startsWith("Sample_")) {
                    nameEdit.setText(item.defaultProjectName);
                }
                scheduleNameCheck();
                refresh();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        sampleBlock.addView(label(R.string.np_sample_label));
        sampleBlock.addView(sampleSpinner);
        sampleBlock.addView(sampleDescView);
        return sampleBlock;
    }

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(theme.toolbar);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));

        cancelButton = new Button(this);
        cancelButton.setText(R.string.dialog_cancel);
        cancelButton.setAllCaps(false);
        cancelButton.setTextColor(theme.textDim);
        cancelButton.setBackgroundColor(0x00000000);
        cancelButton.setOnClickListener(v -> finish());
        bar.addView(cancelButton);

        createButton = new Button(this);
        createButton.setAllCaps(false);
        createButton.setTextColor(theme.bg);
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(10));
        fill.setColor(theme.accent);
        createButton.setBackground(fill);
        createButton.setOnClickListener(v -> startCreate());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        lp.leftMargin = dp(12);
        bar.addView(createButton, lp);
        return bar;
    }

    // ── Selection ─────────────────────────────────────────────────────────

    private void selectTemplate(Template chosen) {
        if (creating || chosen == template) return;
        if (template.mavenAllowed) javaMaven = buildChoice == com.ccs.wiredroid.project.BuildSystem.Kind.MAVEN;
        template = chosen;
        // A template that cannot be built by Maven cannot be built by Ant
        // either — both are generated by factories that only exist for Java.
        if (!template.mavenAllowed) {
            buildChoice = com.ccs.wiredroid.project.BuildSystem.Kind.GRADLE;
        } else if (javaMaven) {
            buildChoice = com.ccs.wiredroid.project.BuildSystem.Kind.MAVEN;
        }
        if (chosen == Template.SAMPLES) {
            SampleItem item = SampleRegistry.getOrDefault(selectedSampleId);
            String current = nameEdit.getText().toString().trim();
            if (current.isEmpty() || current.equals("Playground") || current.startsWith("Sample_")) {
                nameEdit.setText(item.defaultProjectName);
            }
        }
        applyTemplate();
        scheduleNameCheck();
    }

    private void selectBuildSystem(com.ccs.wiredroid.project.BuildSystem.Kind want) {
        if (creating) return;
        // Kotlin is generated by its own factory, which writes a Gradle script;
        // Maven and Ant have nothing to generate for it. Not a dead tap — the
        // greyed segment explains itself.
        if (want != com.ccs.wiredroid.project.BuildSystem.Kind.GRADLE && !template.mavenAllowed) {
            Toast.makeText(this, R.string.np_kotlin_gradle_only, Toast.LENGTH_SHORT).show();
            return;
        }
        if (buildChoice == want) return;
        buildChoice = want;
        if (template.mavenAllowed) {
            javaMaven = want == com.ccs.wiredroid.project.BuildSystem.Kind.MAVEN;
        }
        applyTemplate();
    }

    private void selectRuntime(ProjectRuntime.Mode mode) {
        if (creating) return;
        if (runtimeMode == mode) return;
        runtimeMode = mode;
        applyTemplate();
    }

    /** Applies every per-template visibility rule, then repaints the values. */
    private void applyTemplate() {
        if (!template.mavenAllowed) buildChoice = com.ccs.wiredroid.project.BuildSystem.Kind.GRADLE;

        View selectedChip = null;
        for (int i = 0; i < rail.getChildCount(); i++) {
            View child = rail.getChildAt(i);
            if (!(child.getTag() instanceof Template)) continue;
            boolean selected = child.getTag() == template;
            styleChip((TextView) child, selected);
            if (selected) selectedChip = child;
        }
        if (selectedChip != null) {
            final View chip = selectedChip;
            railScroller.post(() -> railScroller.requestChildRectangleOnScreen(rail,
                    new Rect(chip.getLeft(), chip.getTop(), chip.getRight(), chip.getBottom()),
                    false));
        }
        styleChip(newModeChip, !examplesMode);
        styleChip(examplesModeChip, examplesMode);
        wireExamplesBlock.setVisibility(examplesMode ? View.VISIBLE : View.GONE);
        updateCaption();

        if (sampleBlock != null) {
            sampleBlock.setVisibility(template == Template.SAMPLES ? View.VISIBLE : View.GONE);
        }
        boolean scratch = template == Template.SCRATCH;
        if (locationLabel != null) {
            locationLabel.setText(scratch
                    ? R.string.np_scratch_location_label : R.string.np_location_label);
        }
        if (previewLabel != null) {
            previewLabel.setText(scratch
                    ? R.string.np_scratch_preview_label : R.string.np_preview_label);
        }
        if (scratchBlock != null) {
            scratchBlock.setVisibility(scratch ? View.VISIBLE : View.GONE);
            float scratchRadius = dp(8);
            int index = 0;
            int last = scratchSegments.size() - 1;
            for (java.util.Map.Entry<com.ccs.wiredroid.scratch.ScratchLanguage, TextView> entry : scratchSegments.entrySet()) {
                SegmentEnd end = index == 0 ? SegmentEnd.LEFT
                        : index == last ? SegmentEnd.RIGHT : SegmentEnd.MIDDLE;
                styleSegment(entry.getValue(), entry.getKey() == scratchLanguage,
                        true, end, scratchRadius);
                index++;
            }
        }
        nameBlock.setVisibility(template.takesName ? View.VISIBLE : View.GONE);
        buildBlock.setVisibility(template.hasBuildSystem ? View.VISIBLE : View.GONE);
        buildNote.setVisibility(template.hasBuildSystem && !template.mavenAllowed
                ? View.VISIBLE : View.GONE);
        boolean javaSe = template == Template.JAVA
                && runtimeMode == ProjectRuntime.Mode.JAVA_SE_21;
        runtimeBlock.setVisibility(template == Template.JAVA || template == Template.SAMPLES
                ? View.VISIBLE : View.GONE);
        jdkBlock.setVisibility(template.takesJdk && !javaSe ? View.VISIBLE : View.GONE);
        syncLanguageVersion();
        updateCaption();
        gitBlock.setVisibility(template.takesGit ? View.VISIBLE : View.GONE);
        advancedBlock.setVisibility(template.takesCoordinates ? View.VISIBLE : View.GONE);
        if (advancedExpanded) ensureAdvancedBody();
        if (advancedBody != null) {
            advancedBody.setVisibility(advancedExpanded ? View.VISIBLE : View.GONE);
            artifactBlock.setVisibility(
                    template.takesCoordinates
                            && buildChoice == com.ccs.wiredroid.project.BuildSystem.Kind.MAVEN
                            ? View.VISIBLE : View.GONE);
        }
        advancedHeader.setText(advancedExpanded
                ? R.string.np_advanced_expanded : R.string.np_advanced_collapsed);

        float buildRadius = dp(10);
        boolean maven = buildChoice == com.ccs.wiredroid.project.BuildSystem.Kind.MAVEN;
        boolean gradle = buildChoice == com.ccs.wiredroid.project.BuildSystem.Kind.GRADLE;
        boolean ant = buildChoice == com.ccs.wiredroid.project.BuildSystem.Kind.ANT;
        styleSegment(segMaven, maven, template.mavenAllowed, SegmentEnd.LEFT, buildRadius);
        styleSegment(segGradle, gradle, true, SegmentEnd.MIDDLE, buildRadius);
        styleSegment(segAnt, ant, template.mavenAllowed, SegmentEnd.RIGHT, buildRadius);
        float runtimeRadius = dp(8);
        styleSegment(segArt, !javaSe, true, SegmentEnd.LEFT, runtimeRadius);
        styleSegment(segJavaSe, javaSe, true, SegmentEnd.RIGHT, runtimeRadius);
        runtimeNote.setText(javaSe
                ? R.string.np_runtime_java_se_bundled_note : R.string.np_runtime_art_note);

        syncDerivedFields();
        refresh();
    }

    private void styleChip(TextView chip, boolean selected) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(16));
        if (selected) {
            shape.setColor(theme.accent);
            chip.setTextColor(theme.bg);
        } else {
            shape.setColor(0x00000000);
            shape.setStroke(dp(1), theme.separator);
            chip.setTextColor(theme.textDim);
        }
        chip.setBackground(shape);
    }

    /** Where a segment sits in its row, which decides its rounded corners. */
    private enum SegmentEnd { LEFT, MIDDLE, RIGHT }

    private void styleSegment(TextView segment, boolean selected, boolean enabled,
                              SegmentEnd end, float radius) {
        segment.setAlpha(enabled ? 1f : 0.4f);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(selected && enabled ? theme.accent : 0x00000000);
        float[] radii;
        switch (end) {
            case LEFT:
                radii = new float[] { radius, radius, 0, 0, 0, 0, radius, radius };
                break;
            case RIGHT:
                radii = new float[] { 0, 0, radius, radius, radius, radius, 0, 0 };
                break;
            case MIDDLE:
            default:
                // A middle segment has square ends on both sides, or the row
                // would show a gap where two rounded corners meet.
                radii = new float[] { 0, 0, 0, 0, 0, 0, 0, 0 };
                break;
        }
        shape.setCornerRadii(radii);
        segment.setBackground(shape);
        segment.setTextColor(selected && enabled ? theme.bg : theme.text);
    }

    // ── Values ────────────────────────────────────────────────────────────

    /** Group and artifact hints track the name until the user edits the field. */
    private void syncDerivedFields() {
        if (groupEdit == null) return;
        String safe = safeName();
        if (!groupTouched) {
            groupEdit.setHint(ProjectTemplates.defaultGroupId(safe));
        }
        if (!artifactTouched) {
            artifactEdit.setHint(safe.isEmpty()
                    ? getString(R.string.np_artifact_hint)
                    : ProjectTemplates.sanitizeArtifactId(safe));
        }
    }

    private String safeName() {
        if (!template.takesName) return ProjectTemplates.PLAYGROUND_DIR;
        return ProjectTemplates.sanitizeName(nameEdit.getText().toString());
    }

    /** The group id typed so far, or the one still waiting for its field. */
    private String groupText() {
        return groupEdit == null ? pendingGroup : groupEdit.getText().toString();
    }

    /** The artifact id typed so far, or the one still waiting for its field. */
    private String artifactText() {
        return artifactEdit == null ? pendingArtifact : artifactEdit.getText().toString();
    }

    private void resolveBaseDir() {
        io.execute(() -> {
            // getWireDroidBase does real file I/O — a mkdirs and a write probe.
            final File resolved = MavenPaths.getWireDroidBase(this);
            ui.post(() -> {
                if (finished) return;
                baseDir = resolved;
                scheduleNameCheck();
                refresh();
            });
        });
    }

    private void scheduleNameCheck() {
        ui.removeCallbacks(nameCheck);
        ui.postDelayed(nameCheck, NAME_CHECK_DELAY_MS);
    }

    private void runNameCheck() {
        final File base = baseDir;
        final String safe = safeName();
        if (base == null || safe.isEmpty()) {
            collides = false;
            refresh();
            return;
        }
        final long token = ++nameCheckToken;
        io.execute(() -> {
            final boolean exists = new File(base, safe).exists();
            ui.post(() -> {
                if (finished || token != nameCheckToken) return;
                collides = exists;
                refresh();
            });
        });
    }

    /** Repaints every derived label and the Create button's enabled state. */
    private void refresh() {
        // Native sources only mean anything where the template compiles sources
        // of its own; Playground and Bytecode have no build step to pick them up.
        // This lives here rather than in applyTemplate because the checkbox and
        // the two compiler rows call refresh, and they must repaint on the tap
        // that caused them — not on the next template change.
        boolean nativePossible = template.takesJdk
                && !(template == Template.JAVA
                && runtimeMode == ProjectRuntime.Mode.JAVA_SE_21);
        boolean backendShown = nativePossible && nativeCheck.isChecked();
        nativeBlock.setVisibility(nativePossible ? View.VISIBLE : View.GONE);
        nativeBackendBlock.setVisibility(backendShown ? View.VISIBLE : View.GONE);
        if (backendShown) {
            buildCompilerRows();
            paintCompilerRow(nativeTccRow, !nativeUseNdk);
            // Absent where the NDK cannot run at all.
            if (nativeNdkRow != null) paintCompilerRow(nativeNdkRow, nativeUseNdk);
        }

        String safe = safeName();
        File base = baseDir;
        if (template == Template.SCRATCH) {
            // The scratch directory, not the projects directory — and resolved
            // through ScratchManager so the shown name is the name that will be
            // written, suffix and all.
            File dir = com.ccs.wiredroid.scratch.ScratchManager.getScratchDir(this);
            locationView.setText(new File(dir, scratchFileName()).getAbsolutePath());
        } else {
            locationView.setText(base == null
                    ? getString(R.string.np_location_resolving)
                    : new File(base, safe.isEmpty() ? "…" : safe).getAbsolutePath());
        }

        // A collision blocks the three name-taking templates, because their
        // factories throw. Playground deletes and rewrites instead, so it warns.
        boolean replaces = template == Template.PLAYGROUND && collides;
        boolean nameBlocked = template.takesName && collides;
        locationView.setTextColor(nameBlocked || replaces ? theme.errorText : theme.text);

        if (nameBlocked) {
            nameError.setText(getString(R.string.np_name_exists, safe));
            nameError.setVisibility(View.VISIBLE);
        } else {
            nameError.setVisibility(View.GONE);
        }

        if (replaces) {
            locationNotice.setText(R.string.np_playground_replaces);
            locationNotice.setVisibility(View.VISIBLE);
        } else {
            locationNotice.setVisibility(View.GONE);
        }

        String typedGroup = groupText();
        boolean groupBlocked = template.takesCoordinates
                && !typedGroup.trim().isEmpty()
                && !ProjectTemplates.isValidGroupId(typedGroup);
        if (groupError != null) {
            if (groupBlocked) groupError.setText(R.string.np_group_invalid);
            groupError.setVisibility(groupBlocked ? View.VISIBLE : View.GONE);
        }

        if (artifactNote != null) {
            String typedArtifact = artifactText().trim();
            String sanitizedArtifact = ProjectTemplates.sanitizeArtifactId(typedArtifact);
            if (!typedArtifact.isEmpty() && !typedArtifact.equals(sanitizedArtifact)) {
                artifactNote.setText(getString(R.string.np_artifact_sanitized, sanitizedArtifact));
                artifactNote.setVisibility(View.VISIBLE);
            } else {
                artifactNote.setVisibility(View.GONE);
            }
        }

        // The extra .gitignore is only written where the template writes none.
        boolean extraIgnore = gitCheck.isChecked() && template.takesGit
                && (template == Template.BYTECODE
                    || (template == Template.JAVA
                        && buildChoice == com.ccs.wiredroid.project.BuildSystem.Kind.MAVEN));
        List<String> lines = ProjectTemplates.previewLines(
                template, buildChoice, template == Template.SCRATCH ? scratchFileName() : safe,
                typedGroup, extraIgnore);
        if (examplesMode) {
            org.json.JSONArray files = selectedWireExample().optJSONArray("files");
            java.util.List<String> exampleLines = new java.util.ArrayList<>();
            exampleLines.add(safe + "/");
            if (files != null) for (int i = 0; i < files.length(); i++) exampleLines.add("  " + files.optString(i));
            exampleLines.add("  .gitignore");
            previewView.setText(TextUtils.join("\n", exampleLines));
        } else previewView.setText(TextUtils.join("\n", lines));

        // A scratch needs no base directory and no typed name: it writes into
        // app storage and falls back to Scratch.java.
        boolean valid = !creating
                && (template == Template.SCRATCH
                    || (base != null
                        && (!template.takesName || !safe.isEmpty())
                        && !nameBlocked
                        && !groupBlocked));
        createButton.setEnabled(valid);
        createButton.setAlpha(valid ? 1f : 0.4f);
        createButton.setText(creating
                ? getString(R.string.np_creating)
                : getString(replaces ? R.string.np_replace : R.string.dialog_create));
        cancelButton.setEnabled(!creating);
    }

    // ── Creation ──────────────────────────────────────────────────────────

    /** The file name a scratch would get right now, collision suffix included. */
    private String scratchFileName() {
        String typed = scratchNameEdit == null ? "" : scratchNameEdit.getText().toString();
        return com.ccs.wiredroid.scratch.ScratchManager.resolveFileName(this, typed, scratchLanguage);
    }

    /**
     * Writes the scratch and hands its path back.
     *
     * <p>Deliberately not routed through {@link ProjectCreator}: there is no
     * project to create, no build file to write and no root to switch to. The
     * caller opens the file and leaves the current project alone.</p>
     */
    private void startCreateScratch() {
        if (creating) return;
        creating = true;
        refresh();

        final String typed = scratchNameEdit.getText().toString();
        final com.ccs.wiredroid.scratch.ScratchLanguage language = scratchLanguage;
        io.execute(() -> {
            File created = null;
            String failure = null;
            try {
                created = com.ccs.wiredroid.scratch.ScratchManager.create(this, typed, language);
            } catch (Throwable t) {
                String message = t.getMessage();
                failure = (message == null || message.trim().isEmpty())
                        ? t.getClass().getSimpleName() : message;
            }
            final File file = created;
            final String error = failure;
            ui.post(() -> {
                if (finished) return;
                creating = false;
                if (file == null) {
                    refresh();
                    Toast.makeText(this, getString(R.string.np_failed, error),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Intent data = new Intent();
                data.putExtra(EXTRA_SCRATCH_PATH, file.getAbsolutePath());
                setResult(RESULT_OK, data);
                finish();
            });
        });
    }

    private void startCreate() {
        if (template == Template.SCRATCH) {
            startCreateScratch();
            return;
        }
        if (creating) return;
        creating = true;
        refresh();

        final ProjectCreator.Request request = new ProjectCreator.Request();
        request.runtimeMode = template == Template.JAVA
                ? runtimeMode : ProjectRuntime.Mode.ART;
        request.nativeEnabled = nativeCheck.isChecked() && template.takesJdk
                && request.runtimeMode == ProjectRuntime.Mode.ART;
        request.nativeBackend = nativeUseNdk
                ? com.ccs.wiredroid.util.AppPreferences.NATIVE_NDK
                : com.ccs.wiredroid.util.AppPreferences.NATIVE_TCC;
        request.template = template;
        request.sampleId = selectedSampleId;
        request.wireExampleId = examplesMode ? wireExampleId : null;
        request.name = nameEdit.getText().toString().trim();
        request.buildSystem = buildChoice;
        request.javaTarget = template.takesJdk
                ? (request.runtimeMode == ProjectRuntime.Mode.JAVA_SE_21
                ? "21" : jdk.selectedCode()) : null;
        request.languageVersion = languageVersionChoice.get(template);
        request.groupId = template.takesCoordinates ? groupText().trim() : null;
        request.artifactId = template.takesCoordinates && buildChoice == com.ccs.wiredroid.project.BuildSystem.Kind.MAVEN
                ? artifactText().trim() : null;
        request.initGit = template.takesGit && gitCheck.isChecked();

        io.execute(() -> {
            ProjectCreator.Result result = null;
            String failure = null;
            try {
                result = ProjectCreator.create(this, request);
            } catch (Throwable t) {
                String message = t.getMessage();
                failure = (message == null || message.trim().isEmpty())
                        ? t.getClass().getSimpleName() : message;
            }
            final ProjectCreator.Result created = result;
            final String error = failure;
            ui.post(() -> {
                if (finished) return;
                creating = false;
                if (created == null) {
                    refresh();
                    Toast.makeText(this, getString(R.string.np_failed, error),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (created.warning != null) {
                    Toast.makeText(this, created.warning, Toast.LENGTH_LONG).show();
                }
                Intent data = new Intent();
                data.putExtra(EXTRA_PROJECT_PATH, created.root.getAbsolutePath());
                setResult(RESULT_OK, data);
                finish();
            });
        });
    }

    // ── Small builders ────────────────────────────────────────────────────

    private TextView label(int textRes) {
        TextView view = new TextView(this);
        view.setText(textRes);
        view.setTextColor(theme.textDim);
        view.setTextSize(12);
        return view;
    }

    private TextView newErrorLine() {
        TextView view = new TextView(this);
        view.setTextColor(theme.errorText);
        view.setTextSize(11);
        view.setVisibility(View.GONE);
        return view;
    }

    private EditText newEdit(String hint) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setHintTextColor(theme.textDim);
        edit.setTextColor(theme.text);
        edit.setTextSize(14);
        edit.setBackgroundColor(Colors.blend(theme.bg, theme.text, 0.05f));
        edit.setPadding(dp(12), dp(10), dp(12), dp(10));
        return edit;
    }

    private TextView segment(int textRes, Runnable onClick) {
        return segment(getString(textRes), onClick);
    }

    /** For labels that are language names, which are the same in every locale. */
    private TextView segment(String text, Runnable onClick) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(10), dp(8), dp(10));
        view.setOnClickListener(v -> onClick.run());
        view.setLayoutParams(new LinearLayout.LayoutParams(0, dp(44), 1f));
        return view;
    }

    /** Fills {@link #nativeBackendBlock} the first time the JNI box is ticked. */
    private void buildCompilerRows() {
        if (nativeTccRow != null) return;
        nativeTccRow = compilerRow(R.string.np_native_tcc, R.string.np_native_tcc_note,
                () -> { nativeUseNdk = false; refresh(); });
        nativeBackendBlock.addView(nativeTccRow);

        // The NDK's binaries are arm64; offering the choice on an x86 device
        // would let a project be created against a compiler it can never run.
        if (!com.ccs.wiredroid.tools.compilers.NdkManager.isSupportedOnThisDevice()) {
            nativeUseNdk = false;
            return;
        }
        nativeNdkRow = compilerRow(R.string.np_native_ndk,
                R.string.np_native_ndk_bundled_note,
                () -> { nativeUseNdk = true; refresh(); });
        nativeBackendBlock.addView(nativeNdkRow);
    }

    /**
     * One compiler option, drawn as a radio row with its own explanation.
     *
     * <p>The trade between the two is the whole decision — size against
     * capability — so the explanation sits next to the choice rather than
     * behind a help icon.</p>
     */
    private TextView compilerRow(int titleRes, int noteRes, Runnable onPick) {
        TextView row = new TextView(this);
        row.setTextSize(12);
        row.setPadding(dp(24), dp(8), dp(8), dp(8));
        android.util.TypedValue ripple = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            row.setBackgroundResource(ripple.resourceId);
        }
        row.setTag(new int[]{titleRes, noteRes});
        row.setOnClickListener(v -> onPick.run());
        return row;
    }

    /** Repaints a compiler row for the current selection. */
    private void paintCompilerRow(TextView row, boolean selected) {
        int[] res = (int[]) row.getTag();
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        sb.append(selected ? "●  " : "○  ").append(getString(res[0])).append('\n');
        int noteStart = sb.length();
        sb.append(getString(res[1]));
        sb.setSpan(new android.text.style.ForegroundColorSpan(theme.textDim),
                noteStart, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.92f),
                noteStart, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        row.setText(sb);
        row.setTextColor(selected ? theme.accent : theme.text);
    }

    /**
     * Versions the current template can be created against, newest first.
     *
     * <p>Empty for templates whose only version is Java's — those are answered
     * by the JDK picker instead.</p>
     */
    /** The one-line description under the template rail, naming the chosen version. */
    private void updateCaption() {
        captionView.setText(template.caption(this, languageVersionChoice.get(template)));
    }

    private List<String> versionsFor(Template value) {
        if (value == Template.KOTLIN) {
            return com.ccs.wiredroid.project.KotlinProjectFactory.selectableVersions();
        }
        com.ccs.wiredroid.langrt.JvmLanguage language = value.jvmLanguage();
        return language == null
                ? java.util.Collections.<String>emptyList()
                : com.ccs.wiredroid.langrt.LanguageRuntimes.selectableVersions(this, language);
    }

    /**
     * Points the version spinner at the current template.
     *
     * <p>Kotlin and the three JVM languages differ in what a non-bundled choice
     * costs: for Kotlin nothing is fetched, because the app compiles with the
     * compiler it ships and the version only lands in the generated script; for
     * the others it is a download the first time that language runs. The note
     * under the spinner says which of the two applies.</p>
     */
    private void syncLanguageVersion() {
        List<String> versions = versionsFor(template);
        languageVersionBlock.setVisibility(versions.isEmpty() ? View.GONE : View.VISIBLE);
        if (versions.isEmpty()) return;

        boolean kotlin = template == Template.KOTLIN;
        String bundled = kotlin
                ? com.ccs.wiredroid.project.KotlinProjectFactory.KOTLIN_VERSION
                : com.ccs.wiredroid.langrt.LanguageRuntimes.bundledVersion(
                        this, template.jvmLanguage());

        if (template != languageVersionsFor) {
            languageVersionsFor = template;
            languageVersions = versions;
            String[] labels = new String[versions.size()];
            for (int i = 0; i < versions.size(); i++) {
                String version = versions.get(i);
                labels[i] = version.equals(bundled)
                        ? getString(R.string.settings_language_version_bundled, version)
                        : kotlin ? version
                        : getString(R.string.settings_language_version_download, version);
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                    android.R.layout.simple_spinner_item, labels) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    TextView item = (TextView) super.getView(position, convertView, parent);
                    item.setTextColor(theme.text);
                    return item;
                }
            };
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            languageVersionSpinner.setAdapter(adapter);
            languageVersionLabel.setText(getString(R.string.settings_language_version,
                    kotlin ? "Kotlin" : template.jvmLanguage().displayName()));
            languageVersionNote.setText(kotlin
                    ? getString(R.string.np_kotlin_version_note, bundled)
                    : getString(R.string.settings_language_version_desc));
        }

        String chosen = languageVersionChoice.get(template);
        int index = chosen == null ? 0 : Math.max(0, versions.indexOf(chosen));
        // A Spinner reports a programmatic selection on the next layout pass,
        // not from setSelection itself, so the guard has to outlive this call —
        // and is only armed when the selection actually moves, or a refresh
        // between frames would swallow a real tap.
        if (languageVersionSpinner.getSelectedItemPosition() != index) {
            syncingLanguageVersion = true;
            languageVersionSpinner.setSelection(index);
            languageVersionSpinner.post(() -> syncingLanguageVersion = false);
        }
        languageVersionChoice.put(template, versions.get(index));
    }

    private LinearLayout block(View... children) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        box.setLayoutParams(lp);
        for (View child : children) box.addView(child);
        return box;
    }

    private View separator() {
        View line = new View(this);
        line.setBackgroundColor(theme.separator);
        return applyParams(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private View applyParams(View view, ViewGroup.LayoutParams params) {
        view.setLayoutParams(params);
        return view;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /** A {@link TextWatcher} that only ever needs the "after" callback. */
    private static final class SimpleWatcher implements TextWatcher {
        private final Runnable action;

        SimpleWatcher(Runnable action) {
            this.action = action;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void afterTextChanged(Editable s) {
            action.run();
        }
    }
}
