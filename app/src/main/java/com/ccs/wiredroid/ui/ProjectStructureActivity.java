package com.ccs.wiredroid.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.project.BuildSystem;
import com.ccs.wiredroid.project.ProjectStructure;
import com.ccs.wiredroid.tools.compilers.JavaVersions;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.Credits;
import com.ccs.wiredroid.util.FullScreenHelper;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IntelliJ's Project Structure, cut down to what an on-device build can
 * honestly report: coordinates, the bundled toolchain, the language level,
 * modules, declared libraries and the source roots that exist.
 *
 * <p>Only one control writes anything. The language-level spinner goes straight
 * to {@link AppPreferences#setJavaTarget(String)}, the same value the Settings
 * screen edits and the same value every compile reads, so the choice made here
 * is the choice the compiler gets. Everything else is a mirror of the build
 * script.</p>
 *
 * <p>This is an Activity rather than a dialog because the content is six
 * scrolling sections that must survive rotation, and because the POM parse and
 * directory walk behind it have to run off the main thread with a visible
 * loading state. Pure Android UI, no XML layout.</p>
 */
public class ProjectStructureActivity extends AppCompatActivity {

    private static final String EXTRA_PROJECT_DIR = "ps_project_dir";

    private AppPreferences prefs;
    private AppTheme theme;
    private Typeface mono;

    private File projectDir;
    private ProjectStructure structure;

    private LinearLayout column;
    private TextView statusText;
    private TextView effectiveLevelView;
    private TextView declaredLevelView;

    /**
     * Android delivers an {@code onItemSelected} for the initial selection. That
     * callback must not write a preference the user never touched — and it would
     * write the wrong one, since a stored target outside the spinner's range
     * leaves the selection sitting on index 0.
     */
    private boolean levelSelectionReady;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean finished;

    /** Opens the screen for {@code projectDir}. */
    public static void launch(Context context, File projectDir) {
        Intent i = new Intent(context, ProjectStructureActivity.class);
        if (projectDir != null) i.putExtra(EXTRA_PROJECT_DIR, projectDir.getAbsolutePath());
        context.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        mono = prefs.resolveTypeface();

        setContentView(buildRoot());
        FullScreenHelper.enable(this);

        String path = getIntent().getStringExtra(EXTRA_PROJECT_DIR);
        projectDir = path == null ? null : new File(path);
        if (projectDir == null) {
            statusText.setText(R.string.ps_no_project);
            return;
        }
        startLoad();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        finished = true;
        io.shutdownNow();
    }

    // ── Shell ─────────────────────────────────────────────────────────────────

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle(R.string.ps_title);
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(12), dp(4), dp(12), dp(16));
        scroll.addView(column);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statusText = new TextView(this);
        statusText.setBackgroundColor(theme.consoleBg);
        statusText.setTextColor(theme.textDim);
        statusText.setTextSize(10);
        statusText.setPadding(dp(8), dp(4), dp(8), dp(4));
        statusText.setText(R.string.ps_loading);
        root.addView(statusText);

        return root;
    }

    private void startLoad() {
        final File dir = projectDir;
        io.execute(() -> {
            ProjectStructure loaded = null;
            String failure = null;
            try {
                loaded = ProjectStructure.load(dir);
            } catch (Throwable t) {
                failure = String.valueOf(t.getMessage());
            }
            final ProjectStructure result = loaded;
            final String error = failure;
            ui.post(() -> {
                if (finished) return;
                if (result == null) {
                    statusText.setText(getString(R.string.ps_error_render, String.valueOf(error)));
                    return;
                }
                structure = result;
                render();
            });
        });
    }

    private void render() {
        column.removeAllViews();
        try {
            column.addView(buildProjectSection());
            column.addView(buildSdkSection());
            column.addView(buildLanguageLevelSection());
            statusText.setText(structure.root.getAbsolutePath());
        } catch (Exception e) {
            statusText.setText(getString(R.string.ps_error_render, String.valueOf(e.getMessage())));
            return;
        }
        // Modules, libraries and source roots are unbounded — a dependency-heavy
        // project puts hundreds of rows here, so they go up once the fixed
        // sections above have been drawn.
        column.post(this::renderLists);
    }

    private void renderLists() {
        if (finished || isFinishing() || isDestroyed()) return;
        try {
            column.addView(buildModulesSection());
            column.addView(buildLibrariesSection());
            column.addView(buildSourceRootsSection());
            if (!structure.warnings.isEmpty()) column.addView(buildWarningsSection());
        } catch (Exception e) {
            statusText.setText(getString(R.string.ps_error_render, String.valueOf(e.getMessage())));
        }
    }

    // ── Sections ──────────────────────────────────────────────────────────────

    private View buildProjectSection() {
        LinearLayout box = section(R.string.ps_section_project);
        box.addView(editableField(R.string.ps_field_name, structure.name, this::editName));
        box.addView(editableField(R.string.ps_field_package, basePackage(), this::editPackage));
        box.addView(field(R.string.ps_field_location, structure.root.getAbsolutePath()));
        box.addView(field(R.string.ps_field_build_system,
                structure.buildSystem == BuildSystem.Kind.NONE
                        ? getString(R.string.ps_build_system_none)
                        : BuildSystem.displayName(structure.buildSystem)));
        box.addView(field(R.string.ps_field_coordinates, coordinates()));
        box.addView(field(R.string.ps_field_packaging, structure.packaging));
        box.addView(field(R.string.ps_field_main_class, structure.mainClass));
        box.addView(field(R.string.ps_field_build_script,
                structure.buildScript == null ? null : structure.buildScript.getName()));
        return box;
    }

    private String coordinates() {
        if (structure.groupId == null && structure.version == null) return null;
        String group = structure.groupId == null ? getString(R.string.ps_none) : structure.groupId;
        String ver = structure.version == null ? getString(R.string.ps_none) : structure.version;
        return group + ":" + structure.name + ":" + ver;
    }

    /**
     * There is no external JDK to point at, so the SDK section reports the
     * toolchain that actually does the work. The versions come from
     * {@link Credits} rather than being retyped here, so a dependency bump
     * cannot leave this screen lying.
     */
    private View buildSdkSection() {
        LinearLayout box = section(R.string.ps_section_sdk);
        box.addView(field(R.string.ps_field_name, getString(R.string.ps_sdk_name)));
        box.addView(note(getString(R.string.ps_sdk_hint), theme.textDim));
        for (Credits.Group group : Credits.groups()) {
            if (group.titleRes != R.string.credits_group_compiler) continue;
            for (Credits.Entry entry : group.entries) {
                box.addView(note(getString(R.string.ps_sdk_entry, entry.name, entry.version),
                        theme.text));
            }
        }
        box.addView(note(getString(R.string.ps_sdk_range,
                labelOf(JavaVersions.MIN_COMPILABLE), labelOf(JavaVersions.MAX_COMPILABLE)),
                theme.accent));
        return box;
    }

    private View buildLanguageLevelSection() {
        LinearLayout box = section(R.string.ps_section_language_level);
        box.addView(note(getString(R.string.ps_level_hint), theme.textDim));

        // Every release is offered, including the ones the toolchain cannot emit —
        // a project may already declare one — and those say what they compile as.
        final List<JavaVersions.Release> releases = JavaVersions.all();
        final String[] codes = new String[releases.size()];
        String[] labels = new String[releases.size()];
        for (int i = 0; i < releases.size(); i++) {
            JavaVersions.Release r = releases.get(i);
            codes[i] = r.code;
            labels[i] = r.isCompilable()
                    ? r.label
                    : getString(R.string.ps_level_maps_to, r.label,
                                labelOf(JavaVersions.effective(r.code)));
        }

        Spinner sp = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, labels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(theme.text);
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);
        sp.setContentDescription(getString(R.string.ps_a11y_level));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        sp.setLayoutParams(lp);

        String current = JavaVersions.normalize(prefs.getJavaTarget());
        int selected = 0;
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(current)) {
                selected = i;
                break;
            }
        }
        sp.setSelection(selected);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!levelSelectionReady) return;
                prefs.setJavaTarget(codes[position]);
                updateLevelViews(codes[position]);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        sp.post(() -> levelSelectionReady = true);
        box.addView(sp);

        effectiveLevelView = note("", theme.text);
        box.addView(effectiveLevelView);
        declaredLevelView = note("", theme.textDim);
        box.addView(declaredLevelView);
        updateLevelViews(current);

        return box;
    }

    /**
     * Keeps the two explanatory lines honest: what the toolchain will really
     * compile at, and whether that contradicts the build script.
     */
    private void updateLevelViews(String chosen) {
        String effective = JavaVersions.effective(chosen);
        effectiveLevelView.setText(getString(R.string.ps_level_effective, labelOf(effective)));
        effectiveLevelView.setTextColor(
                JavaVersions.isCompilable(chosen) ? theme.text : theme.errorText);

        ProjectStructure.DeclaredLevel declared = structure.declaredLevel;
        if (declared == null) {
            declaredLevelView.setText(R.string.ps_level_declared_none);
            declaredLevelView.setTextColor(theme.textDim);
            return;
        }

        String scriptName = structure.buildScript == null
                ? getString(R.string.ps_none) : structure.buildScript.getName();
        if (JavaVersions.normalize(declared.code).equals(effective)) {
            declaredLevelView.setText(getString(R.string.ps_level_declared,
                    scriptName, declared.key, labelOf(declared.code)));
            declaredLevelView.setTextColor(theme.textDim);
        } else {
            declaredLevelView.setText(getString(R.string.ps_level_mismatch,
                    labelOf(declared.code), labelOf(effective)));
            declaredLevelView.setTextColor(theme.errorText);
        }
    }

    private View buildModulesSection() {
        LinearLayout box = section(R.string.ps_section_modules);
        if (structure.modules.isEmpty()) {
            box.addView(note(getString(R.string.ps_modules_empty), theme.textDim));
            return box;
        }
        for (ProjectStructure.Module m : structure.modules) {
            int statusRes;
            int colour;
            if (m.declared && !m.present) {
                statusRes = R.string.ps_module_missing;
                colour = theme.errorText;
            } else if (!m.declared) {
                statusRes = R.string.ps_module_undeclared;
                colour = theme.errorText;
            } else {
                statusRes = R.string.ps_module_ok;
                colour = theme.textDim;
            }
            box.addView(twoLineRow(m.name,
                    getString(R.string.ps_module_meta, m.relativePath, getString(statusRes)),
                    colour, null, null));
        }
        return box;
    }

    private View buildLibrariesSection() {
        LinearLayout box = section(R.string.ps_section_libraries);
        if (structure.libraries.isEmpty()) {
            box.addView(note(getString(R.string.ps_libraries_empty), theme.textDim));
            return box;
        }
        for (ProjectStructure.Library lib : structure.libraries) {
            boolean cached = lib.jar != null;
            String meta = getString(R.string.ps_library_meta, lib.scope,
                    getString(cached ? R.string.ps_library_cached : R.string.ps_library_not_cached));
            box.addView(twoLineRow(lib.coordinates(), meta,
                    cached ? theme.successText : theme.textDim,
                    getString(R.string.ps_a11y_library, lib.coordinates()),
                    v -> showLibraryDetails(lib)));
        }
        if (structure.librariesOmitted > 0) {
            box.addView(note(getString(R.string.ps_libraries_truncated,
                    structure.librariesOmitted), theme.textDim));
        }
        return box;
    }

    private void showLibraryDetails(ProjectStructure.Library lib) {
        String message = getString(R.string.ps_library_meta, lib.scope,
                getString(lib.jar != null
                        ? R.string.ps_library_cached : R.string.ps_library_not_cached))
                + "\n\n"
                + (lib.jar != null
                        ? getString(R.string.ps_library_jar, lib.jar.getAbsolutePath())
                        : getString(R.string.ps_library_jar_none));
        Dialogs.rounded(this)
                .setTitle(lib.coordinates())
                .setMessage(message)
                .setPositiveButton(R.string.ps_dialog_close, null)
                .show();
    }

    private View buildSourceRootsSection() {
        LinearLayout box = section(R.string.ps_section_source_roots);
        for (ProjectStructure.SourceRoot root : structure.sourceRoots) {
            String subtitle;
            int colour;
            if (!root.exists) {
                subtitle = getString(R.string.ps_root_absent, root.relativePath);
                colour = theme.textDim;
            } else {
                subtitle = getString(root.capped
                                ? R.string.ps_root_files_capped : R.string.ps_root_files,
                        root.relativePath, root.fileCount);
                colour = theme.accent;
            }
            box.addView(twoLineRow(getString(labelFor(root.kind)), subtitle, colour, null, null));
        }
        return box;
    }

    private View buildWarningsSection() {
        LinearLayout box = section(R.string.ps_section_warnings);
        for (String warning : structure.warnings) {
            box.addView(note(warning, theme.errorText));
        }
        return box;
    }

    private int labelFor(ProjectStructure.SourceRoot.Kind kind) {
        switch (kind) {
            case MAIN_JAVA:      return R.string.ps_root_main_java;
            case MAIN_KOTLIN:    return R.string.ps_root_main_kotlin;
            case MAIN_RESOURCES: return R.string.ps_root_main_resources;
            case TEST_JAVA:      return R.string.ps_root_test_java;
            case TEST_KOTLIN:    return R.string.ps_root_test_kotlin;
            default:             return R.string.ps_root_test_resources;
        }
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private LinearLayout section(int titleRes) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        box.setLayoutParams(lp);

        TextView header = new TextView(this);
        header.setText(titleRes);
        header.setTextColor(theme.textDim);
        header.setTextSize(11);
        header.setTypeface(mono, Typeface.BOLD);
        header.setAllCaps(true);
        header.setPadding(0, 0, 0, dp(4));
        box.addView(header);
        return box;
    }

    /**
     * A field the user can change, marked with a pencil.
     *
     * <p>Same row as {@link #field}, so an editable value does not read as a
     * different kind of thing — the pencil is the only difference, and it is
     * what says which of these the screen will let you change.</p>
     */
    private View editableField(int labelRes, String value, Runnable onEdit) {
        View row = field(labelRes, value);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        wrapper.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(2);
        wrapper.setLayoutParams(wlp);
        wrapper.setBackgroundColor(theme.consoleBg);

        row.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        wrapper.addView(row);

        TextView pencil = new TextView(this);
        pencil.setText("\u270E");
        pencil.setTextSize(16);
        pencil.setTextColor(theme.accent);
        pencil.setPadding(dp(12), dp(10), dp(12), dp(10));
        pencil.setContentDescription(getString(R.string.ps_a11y_edit, getString(labelRes)));
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        if (tv.resourceId != 0) pencil.setBackgroundResource(tv.resourceId);
        pencil.setOnClickListener(v -> onEdit.run());
        wrapper.addView(pencil);
        return wrapper;
    }

    /** The package the project's own sources live in, or empty when there is none. */
    private String basePackage() {
        try {
            String pkg = com.ccs.wiredroid.project.ProjectLayoutHelper.mainPackageName(structure.root);
            return pkg == null ? "" : pkg;
        } catch (Exception e) {
            return "";
        }
    }

    private void editName() {
        promptFor(R.string.ps_edit_name_title, R.string.ps_edit_name_hint,
                structure.name == null ? "" : structure.name, entered -> {
            File pom = new File(structure.root, "pom.xml");
            if (!pom.isFile()) {
                toast(getString(R.string.ps_edit_needs_pom));
                return;
            }
            try {
                String xml = new String(java.nio.file.Files.readAllBytes(pom.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                String out = com.ccs.wiredroid.maven.PomCoordinates.set(xml, "artifactId", entered);
                if (out == null) {
                    toast(getString(R.string.ps_edit_no_element, "artifactId"));
                    return;
                }
                // <name> is optional and often absent; only followed when it is
                // there, because adding one is a change the user did not ask for.
                String named = com.ccs.wiredroid.maven.PomCoordinates.set(out, "name", entered);
                if (named != null) out = named;
                java.nio.file.Files.write(pom.toPath(),
                        out.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                toast(getString(R.string.ps_edit_name_done, entered));
                startLoad();
            } catch (Exception e) {
                toast(getString(R.string.ps_error_render, String.valueOf(e.getMessage())));
            }
        });
    }

    private void editPackage() {
        final String current = basePackage();
        promptFor(R.string.ps_edit_package_title, R.string.ps_edit_package_hint,
                current, entered -> {
            if (entered.equals(current)) return;
            // Moves the files and rewrites every package and import statement;
            // the same helper the editor's Rename Package refactoring uses, so
            // there is one implementation of a move that can go wrong.
            boolean ok = com.ccs.wiredroid.util.PackageRenameHelper.renamePackage(
                    structure.root, current, entered);
            if (!ok) {
                toast(getString(R.string.ps_edit_package_failed));
                return;
            }
            File pom = new File(structure.root, "pom.xml");
            if (pom.isFile()) {
                try {
                    String xml = new String(java.nio.file.Files.readAllBytes(pom.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8);
                    // Only when the groupId was the package. A project whose
                    // coordinates deliberately differ from its package keeps them.
                    if (current.equals(com.ccs.wiredroid.maven.PomCoordinates.get(xml, "groupId"))) {
                        String out = com.ccs.wiredroid.maven.PomCoordinates.set(xml, "groupId", entered);
                        if (out != null) {
                            java.nio.file.Files.write(pom.toPath(),
                                    out.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                } catch (Exception ignored) {}
            }
            toast(getString(R.string.ps_edit_package_done, entered));
            startLoad();
        });
    }

    private interface Entered {
        void accept(String value);
    }

    private void promptFor(int titleRes, int hintRes, String initial, Entered onAccept) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(initial);
        input.setSelection(input.getText().length());
        input.setSingleLine(true);
        input.setHint(hintRes);
        input.setTextColor(theme.text);
        input.setHintTextColor(theme.textDim);
        int pad = dp(14);
        input.setPadding(pad, pad, pad, pad);

        androidx.appcompat.app.AlertDialog dialog = com.ccs.wiredroid.ui.Dialogs.rounded(this)
                .setTitle(titleRes)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty()) onAccept.accept(value);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        com.ccs.wiredroid.ui.Dialogs.style(dialog, theme);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private View field(int labelRes, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundColor(theme.consoleBg);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        row.setLayoutParams(lp);

        TextView key = new TextView(this);
        key.setText(labelRes);
        key.setTextColor(theme.textDim);
        key.setTextSize(11);
        row.addView(key);

        TextView val = new TextView(this);
        boolean empty = value == null || value.trim().isEmpty();
        val.setText(empty ? getString(R.string.ps_none) : value);
        val.setTextColor(empty ? theme.textDim : theme.text);
        val.setTextSize(13);
        val.setTypeface(mono);
        val.setPadding(0, dp(2), 0, 0);
        row.addView(val);

        return row;
    }

    private TextView note(String text, int colour) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(colour);
        tv.setTextSize(11);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View twoLineRow(String title, String subtitle, int subtitleColour,
                            String contentDescription, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        row.setLayoutParams(lp);

        if (click != null) {
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            row.setOnClickListener(click);
        } else {
            row.setBackgroundColor(theme.consoleBg);
        }
        if (contentDescription != null) row.setContentDescription(contentDescription);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(theme.text);
        t.setTextSize(13);
        t.setTypeface(mono);
        row.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextColor(subtitleColour);
        s.setTextSize(11);
        s.setPadding(0, dp(2), 0, 0);
        row.addView(s);

        return row;
    }

    /** Marketing name for a level code, falling back to the code itself. */
    private String labelOf(String code) {
        JavaVersions.Release r = JavaVersions.byCode(code);
        return r == null ? code : r.label;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
