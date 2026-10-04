package com.ccs.wiredroid.ui;

import com.ccs.wiredroid.util.Colors;
import android.app.Activity;
import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;
import com.ccs.wiredroid.util.EditorSettingsApplier;
import com.ccs.wiredroid.util.languages.WireDroidLanguage;
import com.ccs.wiredroid.tools.bytecode.InstructionAdapter;
import com.ccs.wiredroid.tools.bytecode.Deobfuscator;
import com.ccs.wiredroid.tools.bytecode.CallGraphModel;
import com.ccs.wiredroid.tools.bytecode.BytecodeModel;
import com.ccs.wiredroid.project.ProjectManager;
import com.ccs.wiredroid.analysis.ProblemsAdapter;
import com.ccs.wiredroid.analysis.LiveProblemsScheduler;
import com.ccs.wiredroid.project.ProjectScanner;
import com.ccs.wiredroid.ai.AiChatActivity;
import com.ccs.wiredroid.ai.ExactTextEdit;
import com.ccs.wiredroid.ai.PendingEdits;
import com.ccs.wiredroid.tools.bytecode.BytecodeEditorActivity;
import com.ccs.wiredroid.tools.bytecode.BytecodeEditor;
import com.ccs.wiredroid.analysis.ProblemItem;
import com.ccs.wiredroid.analysis.ProblemsWorkspaceAnalyzer;
import com.ccs.wiredroid.tools.compilers.ProjectCompiler;
import com.ccs.wiredroid.maven.PomModel;
import com.ccs.wiredroid.maven.PomParser;
import com.ccs.wiredroid.maven.MavenPaths;
import com.ccs.wiredroid.maven.MavenDependencyResolver;
import com.ccs.wiredroid.maven.MavenProjectFactory;
import com.ccs.wiredroid.project.ProjectLayoutHelper;
import com.ccs.wiredroid.git.GitManager;
import com.ccs.wiredroid.git.GitActivity;
import com.ccs.wiredroid.util.AutoImportHelper;
import com.ccs.wiredroid.util.JavaFormatter;
import com.ccs.wiredroid.util.JsonXmlFormatter;
import com.ccs.wiredroid.util.MarkdownRenderer;
import com.ccs.wiredroid.util.PastebinHelper;
import com.ccs.wiredroid.util.PowerSavingManager;
import com.ccs.wiredroid.util.SessionState;
import com.ccs.wiredroid.util.FileTemplates;
import com.ccs.wiredroid.util.languages.CppLanguage;
import com.ccs.wiredroid.util.languages.XmlLanguage;
import com.ccs.wiredroid.util.languages.CssLanguage;
import com.ccs.wiredroid.util.languages.JavaScriptLanguage;
import com.ccs.wiredroid.util.languages.SqlLanguage;
import com.ccs.wiredroid.util.languages.GradleLanguage;
import com.ccs.wiredroid.util.languages.JsonLanguage;
import com.ccs.wiredroid.util.languages.BashLanguage;
import com.ccs.wiredroid.util.languages.KotlinLanguage;
import com.ccs.wiredroid.util.languages.MarkdownLanguage;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;

import com.ccs.wiredroid.debug.DebuggerController;
import com.ccs.wiredroid.debug.DebugVariable;
import com.ccs.wiredroid.debug.WatchExpression;
import com.ccs.wiredroid.debug.ExpressionEvaluator;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Date;

import com.ccs.wiredroid.debug.BreakpointOverlay;
import com.ccs.wiredroid.debug.BookmarkOverlay;
import com.ccs.wiredroid.debug.BookmarkManager;
import com.ccs.wiredroid.ui.panels.BottomPanel;
import com.ccs.wiredroid.ui.tools.DeveloperToolDialogs;
import com.ccs.wiredroid.ui.panels.BottomPanelController;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.SelectionChangeEvent;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail;
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion;
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer;
import io.github.rosemoe.sora.widget.style.DiagnosticIndicatorStyle;

public class MainActivity extends AppCompatActivity {

    // ── Views ──────────────────────────────────────────────
    private DrawerLayout  drawerLayout;
    private com.ccs.wiredroid.wire.WireDrawerController wireDrawer;
    private LinearLayout  editorsContainer;
    /**
     * Set by onCreate so the resume that immediately follows it does not repaint
     * a screen that was painted a moment ago. Applying the theme walks the whole
     * view tree and re-applies every editor setting, which is not free, and at
     * startup it was being done twice back to back for no visible difference.
     * Every later resume still re-applies, because that is how a change made in
     * settings takes effect.
     */
    private boolean       themeJustApplied;

    /** One problems pass at a time; see refreshProblemsMergedAsync. */
    /** Reads big files off the main thread; one at a time is plenty. */
    private final java.util.concurrent.ExecutorService fileReadWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "file-read");
                t.setDaemon(true);
                return t;
            });

    private final java.util.concurrent.ExecutorService problemsWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(() -> {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    } catch (Throwable ignored) {}
                    r.run();
                }, "problems-refresh");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    private static final java.util.concurrent.ExecutorService inlayWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(() -> {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    } catch (Throwable ignored) {}
                    r.run();
                }, "inlay-hints-worker");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    private final java.util.concurrent.atomic.AtomicBoolean problemsRunning =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean problemsDirty;
    private volatile String pendingProblemsText = "";
    private volatile File pendingProblemsFile;

    private RecyclerView  tabsRecycler;
    private RecyclerView  tabsRecycler2;
    private View          tabsGroup1;
    private View          tabsGroup2;
    private View          tabsGroupDivider;
    private View          tabsGroupDividerLine;
    private SplitDividerController splitDivider;
    private RecyclerView  fileTreeRecycler;
    private com.ccs.wiredroid.util.VoiceToTextManager voiceToText;
    private TextView      statusLineCol;
    private TextView      statusFileName;
    private TextView      statusEncoding;
    private TextView      statusLineSeparator;
    private ImageView     statusReadOnly;
    private TextView      tvAppVersion;
    private TextView      toolbarTitle;
    /**
     * The editors and what they hold. Pulled out of this class because the
     * eleven fields it carries were named 491 times in here; see
     * {@link EditorWorkspace}.
     */
    private final EditorWorkspace ws = new EditorWorkspace();

    private RecyclerView  problemsRecycler;
    private View          problemsPanel;
    private TextView      problemsEmpty;
    private TextView      problemsJavaSeNote;
    private TextView      tabRun;
    private TextView      tabProblems;
    private TextView      tabBytecode;
    private View          callGraphRoot;
    private CallGraphPanelManager callGraphManager;
    private View          btnClearConsole;
    private View          btnCopyPanel;
    private android.widget.Switch liveMetricsToggle;
    private TextView      tabConsole;
    private View          tabsBar;
    private View          tabBorder;
    private View          bottomTabsBar;
    private FrameLayout   bottomPanelContent;
    private Toolbar       toolbar;
    private View          statusBar;
    private View          keyAccessoryBar;
    private LinearLayout  accessoryBarLayout;
    private ImageView btnLightbulb;
    private final List<ProblemItem> activeFileProblems = new ArrayList<>();

    // Panel identity lives in BottomPanel; these aliases keep the many existing
    // switchBottomPanel(PANEL_X) call sites readable.
    private static final int PANEL_RUN           = BottomPanel.RUN.mode;
    private static final int PANEL_PROBLEMS      = BottomPanel.PROBLEMS.mode;
    private static final int PANEL_BYTECODE      = BottomPanel.BYTECODE.mode;
    private static final int PANEL_DEBUG         = BottomPanel.DEBUG.mode;
    private static final int PANEL_DEBUG_CONSOLE = BottomPanel.DEBUG_CONSOLE.mode;
    private static final int PANEL_CALL_GRAPH    = BottomPanel.CALL_GRAPH.mode;
    private static final int PANEL_BOOKMARKS     = BottomPanel.BOOKMARKS.mode;
    private static final int PANEL_DEPS          = BottomPanel.DEPS.mode;
    private static final int PANEL_PROFILER      = BottomPanel.PROFILER.mode;
    private static final int PANEL_TODO          = BottomPanel.TODO.mode;
    private static final int PANEL_CONSOLE       = BottomPanel.CONSOLE.mode;

    private BottomPanelController panelController;
    private int bottomPanelMode = PANEL_RUN;

    // ── Decomposed UI Controllers ────────────────────────
    private ConsolePanelManager consoleManager;
    private FindReplaceController findReplaceController;
    private BytecodePanelManager bytecodeManager;
    private DebugUiCoordinator debugCoordinator;
    private ErrorStripeOverlay errorStripe1;
    private ErrorStripeOverlay errorStripe2;
    private SearchableMenuController searchableMenuController;
    private EditorMenuBar menuBar;
    private ProjectTransferController projectTransfer;
    private com.ccs.wiredroid.testrunner.TestPanelManager testPanelManager;

    /**
     * Whether this run already filled the test tree from real result objects.
     *
     * <p>Both paths can fire: the structured callback arrives first, then the
     * text report. Without this the accurate tree was immediately replaced by
     * one re-parsed out of the console text.</p>
     */
    private boolean structuredTestResultsShown;

    // ── Debug & Bookmarks UI ─────────────────────────────
    private TextView      tabDebug;
    private TextView      tabDebugConsole;
    private TextView      tabCallGraph;
    private TextView      tabBookmarks;
    private BookmarkUiController bookmarkController;

    // ── Minimap ─────────────────────────────────────────────

    // ── Editor chrome: git gutter + breadcrumbs ─────────────
    private GitGutterOverlay gitGutterOverlay1;
    private GitGutterOverlay gitGutterOverlay2;
    private GitGutterController gitGutter;
    private BreadcrumbBar breadcrumbBar;
    /** The path strip under the console; unrelated to breadcrumbBar above the editor. */
    private PathCopyBar filePathBar;
    /**
     * Outline scans share one background thread with nothing else. Static so a
     * configuration change recreating the activity does not leave a thread
     * behind each time.
     */
    private static final java.util.concurrent.ExecutorService outlineWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "breadcrumb-outline");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    private static final java.util.concurrent.atomic.AtomicInteger outlineGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    private Runnable pendingOutlineScan;

    // ── Dependency Viewer ───────────────────────────────────
    private DependencyPanelManager depsManager;

    // ── Profiler ─────────────────────────────────────────────
    private ProfilerPanelManager profilerManager;

    // ── Live Metrics overlay ─────────────────────────────────
    private LiveMetricsView liveMetrics;

    // ── Inlay overlays ───────────────────────────────────────

    // ── TODO/FIXME Tracker ──────────────────────────────────
    private TodoPanelManager todoManager;
    private JShellPanelManager jshellManager;

    // ── Refactoring ─────────────────────────────────────────
    private RefactorController refactorController;

    // ── Maven ──────────────────────────────────────────────
    private MavenActionDelegate mavenDelegate;
    private MavenToolPanel mavenPanel;

    // ── Adapters & Managers ────────────────────────────────
    private FileTreeAdapter  fileTreeAdapter;
    private com.ccs.wiredroid.ui.FileTreeController fileTreeController;
    private com.ccs.wiredroid.ui.StructureAdapter structureAdapter;
    private com.ccs.wiredroid.ui.StructureOptionsController structureOptions;
    private androidx.recyclerview.widget.RecyclerView structureRecycler;
    private TextView btnStructureOptions;
    private View projectContainer;
    private TextView tabProject;
    private TextView tabStructure;
    private ProjectManager   projectManager;
    private ProblemsAdapter  problemsAdapter;
    private LiveProblemsScheduler liveProblemsScheduler;

    // ── State ──────────────────────────────────────────────
    private SharedPreferences prefs;
    private AppPreferences    appPrefs;
    private PowerSavingManager powerSaving;
    private SessionState sessionState;
    private AppTheme          theme;
    private final Handler autoSaveHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingAutoSave;
    private final Handler wireDraftHandler=new Handler(Looper.getMainLooper());
    private final Map<CodeEditor,Runnable> wireDraftPending=new HashMap<>();
    private final java.util.concurrent.ExecutorService wireDraftWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Set<String> wireDraftNotified=new HashSet<>();
    private boolean isRunning        = false;
    /** Set when Tab was swallowed to accept a suggestion, so its release is too. */
    private boolean consumedSuggestionTab = false;
    private File    copiedFile       = null;

    private static final int REQ_SETTINGS      = 4001;
    private static final int REQ_OPEN_FILE     = ProjectTransferController.REQ_OPEN_FILE;
    private static final int REQ_SAVE_AS       = ProjectTransferController.REQ_SAVE_AS;
    private static final int REQ_EXPORT_PROJ   = ProjectTransferController.REQ_EXPORT_PROJ;
    private static final int REQ_LIB_MANAGER   = 4005;
    private static final int REQ_GLOBAL_SEARCH = 4006;
    private static final int REQ_PLAY_MEDIA    = ProjectTransferController.REQ_PLAY_MEDIA;
    private static final int REQ_IMPORT_FILES  = ProjectTransferController.REQ_IMPORT_FILES;
    private static final int REQ_LOAD_MAPPING  = BytecodePanelManager.REQ_LOAD_MAPPING;
    private static final int REQ_CLASS_BROWSER = 9001;
    private static final int REQ_ARCHIVE_FOLDER = 9002;

    private static final int MD_COLOR_TEXT = 0xFFDFE1E5;
    private static final int MD_COLOR_TEXT_LIGHT = 0xFF3C3F41;

    private static final String DEFAULT_CODE = com.ccs.wiredroid.wire.WireProject.DEFAULT_CODE;

    // ══════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appPrefs = new AppPreferences(this);
        powerSaving = new PowerSavingManager(this);
        powerSaving.addListener(active -> {
            if (liveProblemsScheduler != null) {
                liveProblemsScheduler.setInterval(powerSaving.getProblemsScanIntervalMs());
                if (active && !powerSaving.shouldRunLiveProblems()) {
                    liveProblemsScheduler.stop();
                } else if (!active && powerSaving.shouldRunLiveProblems()) {
                    liveProblemsScheduler.start();
                }
            }
            boolean minimapEnabled = isMinimapAllowed();
            if (ws.minimapView1 != null) ws.minimapView1.setVisibility(minimapEnabled ? View.VISIBLE : View.GONE);
            if (ws.minimapView2 != null) ws.minimapView2.setVisibility(minimapEnabled && ws.isSplitActive ? View.VISIBLE : View.GONE);
        });
        sessionState = new SessionState(this);
        theme    = AppTheme.byId(appPrefs.getThemeId(), appPrefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);

        com.ccs.wiredroid.util.StartupTrace.Phases phases =
                com.ccs.wiredroid.util.StartupTrace.phases("MainActivity");
        setContentView(R.layout.activity_main);
        phases.mark("setContentView");
        FullScreenHelper.enable(this);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, windowInsets) -> {
            Insets imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            Insets systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            int bottomMargin = Math.max(imeInsets.bottom, systemBarsInsets.bottom);
            v.setPadding(0, 0, 0, bottomMargin);
            return windowInsets;
        });

        prefs = getSharedPreferences(AppPreferences.PREFS_NAME, MODE_PRIVATE);

        bindViews();
        phases.mark("bindViews");
        if (statusLineSeparator != null) {
            statusLineSeparator.setOnClickListener(v -> showLineSeparatorDialog());
        }
        if (statusEncoding != null) {
            statusEncoding.setOnClickListener(v -> showEncodingSelectionDialog());
        }
        if (statusReadOnly != null) {
            statusReadOnly.setOnClickListener(v -> toggleReadOnly());
            statusReadOnly.setOnLongClickListener(v -> {
                showReadOnlyInfo();
                return true;
            });
        }
        setupBackHandling();
        setupToolbar();
        phases.mark("setupToolbar");
        setupTabs();
        setupConsoleInput();
        phases.mark("setupTabs");
        setupFileTree();
        phases.mark("setupFileTree");
        setupEditor();
        phases.mark("setupEditor");
        setupBottomTabs();
        phases.mark("setupBottomTabs");
        setupProblemsList();
        phases.mark("setupProblemsList");

        setupProject(savedInstanceState != null);
        phases.mark("setupProject");
        if (com.ccs.wiredroid.wire.WireProject.isCircuit(projectManager.getProjectDir())) {
            wireDrawer = new com.ccs.wiredroid.wire.WireDrawerController(this,drawerLayout,projectManager.getProjectDir(),theme,
                file -> {saveCurrentFile();openFile(existingWireTabFile(file));},this::saveCurrentFile,this::openWireDiagnostic,this::saveWireCodeForRun);
            if (wireDrawer.canPin() || getIntent().getBooleanExtra("wire_show",false))
                drawerLayout.post(() -> wireDrawer.show(getIntent().getBooleanExtra("wire_run",false)));
        }

        applyTheme();
        phases.mark("applyTheme");
        themeJustApplied = true;
        phases.done();
        initLiveProblemsScheduler();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(wireDrawer!=null)wireDrawer.resume();
        if (liveProblemsScheduler != null) {
            liveProblemsScheduler.setInterval(powerSaving.getProblemsScanIntervalMs());
            if (powerSaving.shouldRunLiveProblems()) {
                liveProblemsScheduler.start();
            } else {
                liveProblemsScheduler.stop();
            }
        }
        if (themeJustApplied) {
            themeJustApplied = false;
        } else {
            theme = AppTheme.byId(appPrefs.getThemeId(), appPrefs);
            applyTheme();
        }
        invalidateOptionsMenu();
        // Update minimap visibility when returning from settings
        boolean minimapEnabled = isMinimapAllowed();
        if (ws.minimapView1 != null) ws.minimapView1.setVisibility(minimapEnabled ? View.VISIBLE : View.GONE);
        if (ws.minimapView2 != null) ws.minimapView2.setVisibility(minimapEnabled && ws.isSplitActive ? View.VISIBLE : View.GONE);
        // Auto-refresh problems on resume (e.g. returning from settings with new power saving mode)
        if (projectManager != null && projectManager.getProjectDir() != null) {
            refreshProblemsMergedAsync();
        }
        applyInlineDiagnostics(problemsAdapter != null ? problemsAdapter.getItems() : null);
        // Any Git operation happens in another activity, so coming back is the
        // one reliable moment to re-diff against the new HEAD.
        if (gitGutter != null) gitGutter.refreshAll();
        // Застосувати відкладені вставки коду від AI-чату (кнопка "Insert" / інструмент insertCode).
        applyPendingAiEdits();
    }

    /** Applies manual insertions to the active editor and agent patches to their named files. */
    private void applyPendingAiEdits() {
        PendingEdits.AgentChangeSet changeSet = PendingEdits.peekChangeSet(this);
        if (changeSet != null && !changeSet.isEmpty()) {
            if (applyAgentChangeSet(changeSet, true)) {
                PendingEdits.discardChangeSet(this);
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("Changes applied")
                        .setMessage(changeSet.changes.size() + " file(s) updated.")
                        .setPositiveButton("Undo all", (dialog, which) -> {
                            PendingEdits.AgentChangeSet undo = PendingEdits.takeUndoChangeSet(this);
                            if (undo == null || !applyAgentChangeSet(undo, false)) {
                                if (undo != null) PendingEdits.saveUndoChangeSet(this, undo);
                                Toast.makeText(this, "Could not safely undo: a file has changed",
                                        Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(this, "All agent changes undone", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Done", null)
                        .show();
            } else {
                android.widget.Toast.makeText(this,
                        "Agent changes were not applied: a target changed or a write failed",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        }
        if (!PendingEdits.hasPending()) return;
        java.util.List<PendingEdits.Edit> edits = PendingEdits.drain();
        if (edits.isEmpty()) return;

        int applied = 0;
        int rejected = 0;
        int manualActiveEdits = 0;
        boolean wasProgrammatic = ws.isProgrammaticChange;
        ws.isProgrammaticChange = true;
        try {
            for (PendingEdits.Edit e : edits) {
                try {
                    if (PendingEdits.LOCATION_PATCH.equals(e.location)) {
                        boolean targeted = e.path != null && !e.path.isEmpty();
                        boolean changed = targeted
                                ? applyPatchToFile(e.path, e.find, e.code)
                                : applyPatch(ws.activeEditor, e.find, e.code);
                        if (!changed) {
                            rejected++;
                            continue;
                        }
                        if (!targeted) manualActiveEdits++;
                    } else if (ws.activeEditor == null) {
                        rejected++;
                        continue;
                    } else if (PendingEdits.LOCATION_REPLACE.equals(e.location)) {
                        ws.activeEditor.setText(e.code);
                        manualActiveEdits++;
                    } else if (PendingEdits.LOCATION_APPEND.equals(e.location)) {
                        // Перейти в кінець документу й вставити
                        int lastLine = ws.activeEditor.getText().getLineCount() - 1;
                        if (lastLine < 0) lastLine = 0;
                        int lastCol = ws.activeEditor.getText().getColumnCount(lastLine);
                        ws.activeEditor.setSelection(lastLine, lastCol);
                        String sep = needLeadingNewline() ? "\n" : "";
                        ws.activeEditor.insertText(sep + e.code, 0);
                        manualActiveEdits++;
                    } else {
                        // cursor (за замовч.)
                        ws.activeEditor.insertText(e.code, 0);
                        manualActiveEdits++;
                    }
                    applied++;
                } catch (Exception ex) {
                    rejected++;
                    android.util.Log.w("MainActivity", "AI insert failed: " + ex.getMessage());
                }
            }
        } finally {
            ws.isProgrammaticChange = wasProgrammatic;
        }

        // Cursor/append buttons are still a manual chat feature. Agent changes
        // are marked against their own file inside applyPatchToFile().
        if (manualActiveEdits > 0 && ws.activeEditor != null) {
            FileTab tab = ws.activeTab();
            int index = tab == null || ws.tabs() == null
                    ? -1 : ws.tabs().getTabs().indexOf(tab);
            if (index >= 0) {
                ws.tabs().markModified(index, true);
                scheduleAutoSave(ws.activeEditor, tab, index);
            }
        }

        if (applied > 0 || rejected > 0) {
            String message = applied + " AI edit(s) applied"
                    + (rejected > 0 ? ", " + rejected
                    + " skipped (target or exact text no longer matches)" : "");
            android.widget.Toast.makeText(this, message,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** Validate and apply a complete reviewed agent proposal as one recoverable transaction. */
    private boolean applyAgentChangeSet(PendingEdits.AgentChangeSet set, boolean recordUndo) {
        if (projectManager == null || projectManager.getProjectDir() == null) return false;
        Map<File, String> before = new LinkedHashMap<>();
        Map<File, String> after = new LinkedHashMap<>();
        Set<File> created = new HashSet<>();
        Map<File, CodeEditor> openEditors = new HashMap<>();
        Map<File, FileTab> openTabs = new HashMap<>();
        Map<File, TabsAdapter> openAdapters = new HashMap<>();
        boolean oldProgrammatic = ws.isProgrammaticChange;
        try {
            File root = projectManager.getProjectDir().getCanonicalFile();
            if (!root.getPath().equals(new File(set.projectRoot).getCanonicalPath())) return false;
            String prefix = root.getPath() + File.separator;
            for (PendingEdits.FileChange change : set.changes) {
                File target = new File(change.path).getCanonicalFile();
                if (!target.getPath().startsWith(prefix) || isFileReadOnly(target)) return false;
                CodeEditor editor = null;
                FileTab tab = null;
                TabsAdapter adapter = null;
                if (ws.leftTab != null && sameFile(ws.leftTab.file, target)) {
                    editor = ws.editor; tab = ws.leftTab; adapter = ws.tabsLeft;
                } else if (ws.rightTab != null && sameFile(ws.rightTab.file, target)) {
                    editor = ws.editor2; tab = ws.rightTab; adapter = ws.tabsRight;
                }
                String current;
                if (change.created) {
                    if (target.exists() || !change.base.isEmpty()) return false;
                    current = "";
                    created.add(target);
                } else {
                    if (!target.isFile()) return false;
                    current = editor != null ? editor.getText().toString() : projectManager.readFile(target);
                    if (!current.equals(change.base)) return false;
                }
                // Keep a deleted file tab out of a dangling state. Ask the user to close it first.
                if (change.deleted && editor != null) return false;
                before.put(target, current);
                after.put(target, change.content);
                if (editor != null) {
                    openEditors.put(target, editor);
                    openTabs.put(target, tab);
                    openAdapters.put(target, adapter);
                }
            }

            // Write all closed/new files first; editor buffers are changed only after disk succeeds.
            for (Map.Entry<File, String> entry : after.entrySet()) {
                File target = entry.getKey();
                if (created.contains(target)) {
                    File parent = target.getParentFile();
                    if (parent == null || (!parent.isDirectory() && !parent.mkdirs())
                            || !target.createNewFile()) throw new IOException("cannot create " + target);
                }
                PendingEdits.FileChange fileChange = null;
                for (PendingEdits.FileChange candidate : set.changes) {
                    if (new File(candidate.path).getCanonicalFile().equals(target)) {
                        fileChange = candidate; break;
                    }
                }
                if (fileChange != null && fileChange.deleted) {
                    java.nio.file.Files.delete(target.toPath());
                } else {
                    projectManager.writeFile(target, entry.getValue());
                }
            }
            ws.isProgrammaticChange = true;
            for (Map.Entry<File, CodeEditor> entry : openEditors.entrySet()) {
                File target = entry.getKey();
                CodeEditor editor = entry.getValue();
                if (!created.contains(target)) {
                    com.ccs.wiredroid.util.LocalHistoryManager.saveSnapshot(
                            this, target, before.get(target), "Before AI change set");
                }
                MainActivity.setEditorTextPreservingSelection(editor, after.get(target));
                FileTab tab = openTabs.get(target);
                TabsAdapter adapter = openAdapters.get(target);
                int index = adapter == null || tab == null ? -1 : adapter.getTabs().indexOf(tab);
                if (index >= 0) {
                    adapter.markModified(index, true);
                    if (editor == ws.activeEditor) scheduleAutoSave(editor, tab, index);
                }
            }
            for (PendingEdits.FileChange change : set.changes) {
                File target = new File(change.path).getCanonicalFile();
                if (!openEditors.containsKey(target) && !created.contains(target)) {
                    com.ccs.wiredroid.util.LocalHistoryManager.saveSnapshot(
                            this, target, before.get(target), "Before AI change set");
                }
            }
            if (gitGutter != null) gitGutter.refreshAll();
            if (recordUndo) {
                List<PendingEdits.FileChange> inverses = new ArrayList<>();
                for (PendingEdits.FileChange change : set.changes) {
                    if (change.created) {
                        inverses.add(new PendingEdits.FileChange(change.path, change.content,
                                "", false, true));
                    } else if (change.deleted) {
                        inverses.add(new PendingEdits.FileChange(change.path, "", change.base, true));
                    } else {
                        inverses.add(new PendingEdits.FileChange(change.path, change.content,
                                change.base, false));
                    }
                }
                PendingEdits.saveUndoChangeSet(this,
                        new PendingEdits.AgentChangeSet(set.projectRoot, inverses));
            }
            return true;
        } catch (Exception failure) {
            android.util.Log.w("MainActivity", "Agent change set failed; rolling back", failure);
            // Restore every disk target that was touched, then restore editor buffers.
            for (Map.Entry<File, String> entry : before.entrySet()) {
                File target = entry.getKey();
                try {
                    if (created.contains(target)) {
                        if (target.exists()) java.nio.file.Files.delete(target.toPath());
                    } else if (isDeletedOperation(set, target)) {
                        if (!target.exists()) {
                            File parent = target.getParentFile();
                            if (parent != null && !parent.isDirectory()) parent.mkdirs();
                            if (target.createNewFile()) projectManager.writeFile(target, entry.getValue());
                        }
                    } else if (after.containsKey(target) && target.exists()) {
                        projectManager.writeFile(target, entry.getValue());
                    }
                } catch (Exception rollbackFailure) {
                    android.util.Log.e("MainActivity", "Agent rollback failed for " + target,
                            rollbackFailure);
                }
                CodeEditor editor = openEditors.get(target);
                if (editor != null) MainActivity.setEditorTextPreservingSelection(editor, entry.getValue());
            }
            return false;
        } finally {
            ws.isProgrammaticChange = oldProgrammatic;
        }
    }

    private boolean isDeletedOperation(PendingEdits.AgentChangeSet set, File target) {
        for (PendingEdits.FileChange change : set.changes) {
            try {
                if (change.deleted && new File(change.path).getCanonicalFile().equals(target)) return true;
            } catch (IOException ignored) {}
        }
        return false;
    }

    /** Applies one exact patch to an editor buffer and keeps the caret near it. */
    private boolean applyPatch(CodeEditor editor, String find, String replacement) {
        if (editor == null || editor.getText() == null) return false;
        ExactTextEdit.Result result = ExactTextEdit.apply(
                editor.getText().toString(), find, replacement);
        if (!result.applied()) return false;
        MainActivity.setEditorTextPreservingSelection(editor, result.text);
        try {
            int line = 0;
            for (int i = 0; i < result.offset && i < result.text.length(); i++) {
                if (result.text.charAt(i) == '\n') line++;
            }
            editor.setSelection(Math.min(line,
                    Math.max(0, editor.getText().getLineCount() - 1)), 0);
        } catch (Exception ignored) {}
        return true;
    }

    /**
     * Applies an agent edit to the canonical file it named. Visible files are
     * changed in their own pane; closed files are updated on disk. The path is
     * revalidated here because it crossed an activity boundary in a static queue.
     */
    private boolean applyPatchToFile(String rawPath, String find, String replacement) {
        try {
            File target = resolvePendingAiTarget(rawPath);
            if (target == null || isFileReadOnly(target)) return false;

            CodeEditor editor = null;
            FileTab tab = null;
            TabsAdapter tabs = null;
            if (ws.leftTab != null && sameFile(ws.leftTab.file, target)) {
                editor = ws.editor;
                tab = ws.leftTab;
                tabs = ws.tabsLeft;
            } else if (ws.rightTab != null && sameFile(ws.rightTab.file, target)) {
                editor = ws.editor2;
                tab = ws.rightTab;
                tabs = ws.tabsRight;
            }

            if (editor != null) {
                String before = editor.getText().toString();
                if (!applyPatch(editor, find, replacement)) return false;
                com.ccs.wiredroid.util.LocalHistoryManager.saveSnapshot(
                        this, target, before, "Before AI edit");
                int index = tabs == null || tab == null ? -1 : tabs.getTabs().indexOf(tab);
                if (index >= 0) {
                    tabs.markModified(index, true);
                    if (editor == ws.activeEditor) scheduleAutoSave(editor, tab, index);
                }
            } else {
                String before = projectManager.readFile(target);
                ExactTextEdit.Result result = ExactTextEdit.apply(before, find, replacement);
                if (!result.applied()) return false;
                com.ccs.wiredroid.util.LocalHistoryManager.saveSnapshot(
                        this, target, before, "Before AI edit");
                projectManager.writeFile(target, result.text);
            }
            if (gitGutter != null) gitGutter.refreshAll();
            return true;
        } catch (IOException e) {
            android.util.Log.w("MainActivity", "AI target edit failed", e);
            return false;
        }
    }

    private File resolvePendingAiTarget(String rawPath) throws IOException {
        if (projectManager == null || projectManager.getProjectDir() == null
                || rawPath == null || rawPath.trim().isEmpty()) return null;
        File root = projectManager.getProjectDir().getCanonicalFile();
        File target = new File(rawPath.trim());
        if (!target.isAbsolute()) target = new File(root, rawPath.trim());
        target = target.getCanonicalFile();
        String prefix = root.getPath() + File.separator;
        if ((!target.equals(root) && !target.getPath().startsWith(prefix)) || !target.isFile()) {
            return null;
        }
        return target;
    }

    private static boolean sameFile(File first, File second) {
        if (first == null || second == null) return false;
        try {
            return first.getCanonicalFile().equals(second.getCanonicalFile());
        } catch (IOException e) {
            return first.equals(second);
        }
    }

    /** Чи потрібен порожній рядок перед вставлянням append (файл не закінчується \n)? */
    private boolean needLeadingNewline() {
        try {
            var txt = ws.activeEditor.getText();
            int lc = txt.getLineCount();
            if (lc == 0) return false;
            String last = txt.getLine(lc - 1).toString();
            return !last.isEmpty();
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    protected void onPause() {
        checkpointVisibleWireEditors();
        if (wireDrawer != null) wireDrawer.pause();
        if (liveProblemsScheduler != null) {
            liveProblemsScheduler.stop();
        }
        if (pendingAutoSave != null) {
            autoSaveHandler.removeCallbacks(pendingAutoSave);
            pendingAutoSave = null;
        }
        if (debugCoordinator != null && debugCoordinator.isDebugging()) {
            debugCoordinator.stopDebug();
        }
        profilerManager.stopLiveRefresh();
        if (liveMetrics != null) liveMetrics.stop();
        inlayHandler.removeCallbacksAndMessages(null);
        inlayPending.clear();
        if (breadcrumbBar != null && pendingOutlineScan != null) {
            breadcrumbBar.removeCallbacks(pendingOutlineScan);
            pendingOutlineScan = null;
        }
        if (gitGutter != null) {
            gitGutter.cancelAllPending();
        }
        if (todoManager != null) {
            todoManager.cancelAutoRefresh();
        }
        super.onPause();
        saveCurrentToActiveTab();
        saveSessionState();
    }

    @Override
    protected void onDestroy() {
        wireDraftHandler.removeCallbacksAndMessages(null);wireDraftPending.clear();wireDraftWorker.shutdown();
        if (wireDrawer != null) wireDrawer.close();
        if (debugCoordinator != null) {
            debugCoordinator.cleanup();
        }
        if (powerSaving != null) {
            powerSaving.destroy();
        }
        problemsWorker.shutdownNow();
        super.onDestroy();
    }

    // ══════════════════════════════════════════════════════════
    //  Setup
    // ══════════════════════════════════════════════════════════

    private void setupBackHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // A menu takes no focus, so the back key reaches the activity
                // instead of it. Closing it is what back means while it is open.
                if (AnchoredMenu.dismissOpen()) return;
                if (wireDrawer != null && wireDrawer.closeDrawer()) return;
                if (findReplaceController != null && findReplaceController.isFindBarVisible()) {
                    findReplaceController.hideFindBar();
                    return;
                }
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                    return;
                }
                // Return to WelcomeActivity when back pressed at root level
                Intent intent = new Intent(MainActivity.this, WelcomeActivity.class);
                startActivity(intent);
                finish();
            }
        });
    }

    private void bindViews() {
        drawerLayout     = findViewById(R.id.drawerLayout);
        ws.editor           = findViewById(R.id.editor);
        ws.editor2          = findViewById(R.id.editor2);
        editorsContainer = findViewById(R.id.editorsContainer);
        ws.editorDivider    = findViewById(R.id.editorDivider);
        ws.editorDividerLine = findViewById(R.id.editorDividerLine);
        ws.editorDividerGrip = findViewById(R.id.editorDividerGrip);
        ws.wrapperEditor1   = findViewById(R.id.wrapperEditor1);
        ws.wrapperEditor2   = findViewById(R.id.wrapperEditor2);
        liveMetrics = findViewById(R.id.liveMetrics);
        ws.inlayOverlay1 = findViewById(R.id.inlayOverlay1);
        ws.inlayOverlay2 = findViewById(R.id.inlayOverlay2);
        if (ws.inlayOverlay1 != null) ws.inlayOverlay1.setEditor(ws.editor);
        if (ws.inlayOverlay2 != null) ws.inlayOverlay2.setEditor(ws.editor2);
        ws.breakpointOverlay1 = findViewById(R.id.breakpointOverlay1);
        ws.breakpointOverlay2 = findViewById(R.id.breakpointOverlay2);
        ws.bookmarkOverlay1 = findViewById(R.id.bookmarkOverlay1);
        ws.bookmarkOverlay2 = findViewById(R.id.bookmarkOverlay2);
        ws.activeEditor     = ws.editor;
        ws.activeEditorDecorator = new com.ccs.wiredroid.debug.DebugEditorDecorator(ws.editor, ws.breakpointOverlay1);
        if (ws.bookmarkOverlay1 != null) ws.bookmarkOverlay1.setEditor(ws.editor);
        if (ws.bookmarkOverlay2 != null) ws.bookmarkOverlay2.setEditor(ws.editor2);
        tabsRecycler     = findViewById(R.id.tabsRecycler);
        tabsRecycler2    = findViewById(R.id.tabsRecycler2);
        tabsGroup1       = findViewById(R.id.tabsGroup1);
        tabsGroup2       = findViewById(R.id.tabsGroup2);
        tabsGroupDivider = findViewById(R.id.tabsGroupDivider);
        tabsGroupDividerLine = findViewById(R.id.tabsGroupDividerLine);
        fileTreeRecycler = findViewById(R.id.fileTreeRecycler);
        structureRecycler = findViewById(R.id.structureRecycler);
        btnStructureOptions = findViewById(R.id.btnStructureOptions);
        projectContainer = findViewById(R.id.projectContainer);
        tabProject = findViewById(R.id.tabProject);
        tabStructure = findViewById(R.id.tabStructure);
        toolbarTitle     = findViewById(R.id.toolbarTitle);
        statusLineCol    = findViewById(R.id.statusLineCol);
        statusFileName   = findViewById(R.id.statusFileName);
        tabRun           = findViewById(R.id.tabRun);
        tabProblems      = findViewById(R.id.tabProblems);
        tabBytecode      = findViewById(R.id.tabBytecode);
        btnClearConsole  = findViewById(R.id.btnClearConsole);
        btnCopyPanel     = findViewById(R.id.btnCopyPanel);
        liveMetricsToggle = findViewById(R.id.toggleLiveMetrics);
        tabConsole       = findViewById(R.id.tabConsole);
        tabsBar          = findViewById(R.id.tabsBar);
        tabBorder        = findViewById(R.id.tabBorder);
        bottomTabsBar    = findViewById(R.id.bottomTabsBar);
        bottomPanelContent = findViewById(R.id.bottomPanelContent);
        statusBar        = findViewById(R.id.statusBar);
        statusEncoding   = findViewById(R.id.statusEncoding);
        statusLineSeparator = findViewById(R.id.statusLineSeparator);
        statusReadOnly   = findViewById(R.id.statusReadOnly);
        tvAppVersion     = findViewById(R.id.tvAppVersion);
        if (tvAppVersion != null) {
            tvAppVersion.setText("v" + com.ccs.wiredroid.BuildConfig.VERSION_NAME);
        }
        toolbar          = findViewById(R.id.toolbar);
        keyAccessoryBar  = findViewById(R.id.keyAccessoryBar);
        accessoryBarLayout = findViewById(R.id.accessoryBarLayout);
        btnLightbulb     = findViewById(R.id.btnLightbulb);
        if (btnLightbulb != null) {
            btnLightbulb.setOnClickListener(v -> showQuickFixForCurrentLine());
        }

        // Decomposed UI controllers
        consoleManager = new ConsolePanelManager(this, new ConsolePanelManager.Callback() {
            @Override public AppTheme getTheme() { return theme; }
            @Override public int getBottomPanelMode() { return bottomPanelMode; }
            @Override public boolean isLiveMetricsVisible() { return liveMetrics != null && liveMetrics.getVisibility() == View.VISIBLE; }
            @Override public int getLiveMetricsHeight() { return liveMetrics != null ? liveMetrics.getHeight() : 0; }
        });
        consoleManager.bind();
        setupConsoleDivider();
        setupSplitDivider();

        View testView = findViewById(R.id.testRunnerView);
        View consoleScroll = findViewById(R.id.consoleScroll);
        if (testView != null && consoleScroll != null) {
            testPanelManager = new com.ccs.wiredroid.testrunner.TestPanelManager(testView, consoleScroll,
                    new com.ccs.wiredroid.testrunner.TestPanelManager.Callback() {
                        @Override
                        public void onNavigateToSource(String fileName, int lineNumber) {
                            if (fileName == null) return;
                            File file = findFileInProject(fileName);
                            if (file == null && ws.activeTab() != null && ws.activeTab().file != null
                                    && ws.activeTab().file.getName().equals(fileName)) {
                                file = ws.activeTab().file;
                            }
                            if (file == null) {
                                File scratches = com.ccs.wiredroid.scratch.ScratchManager.getScratchDir(MainActivity.this);
                                File candidate = new File(scratches, fileName);
                                if (candidate.exists()) file = candidate;
                            }
                            if (file != null) {
                                openFile(file);
                                if (lineNumber > 0 && ws.activeEditor != null) {
                                    final int ln = lineNumber;
                                    ws.activeEditor.postDelayed(() -> ws.activeEditor.setSelection(ln - 1, 0), 200);
                                }
                            }
                        }

                        @Override
                        public void onToggleView(boolean showTree) {
                            if (consoleManager != null) {
                                consoleManager.positionScrollEndButton();
                            }
                        }
                    });
            testPanelManager.applyTheme(theme);
        }

        findReplaceController = new FindReplaceController(this, ws, new FindReplaceController.Callback() {
            @Override public File getProjectDir() { return projectManager != null ? projectManager.getProjectDir() : null; }
            @Override public void startGlobalSearch(String query) {
                Intent i = new Intent(MainActivity.this, GlobalSearchActivity.class);
                if (projectManager != null && projectManager.getProjectDir() != null) {
                    i.putExtra("project_root", projectManager.getProjectDir().getAbsolutePath());
                }
                i.putExtra("query", query);
                startActivityForResult(i, REQ_GLOBAL_SEARCH);
            }
        });
        findReplaceController.bind();

        bytecodeManager = new BytecodePanelManager(this, ws, new BytecodePanelManager.Callback() {
            @Override public String getActiveSourceCode() { return ws.activeEditor != null && ws.activeEditor.getText() != null ? ws.activeEditor.getText().toString() : ""; }
            @Override public File getProjectDir() { return projectManager != null ? projectManager.getProjectDir() : null; }
            @Override public AppTheme getTheme() { return theme; }
            @Override public boolean isRunning() { return isRunning; }
            @Override public void setRunning(boolean running) { isRunning = running; }
            @Override public void appendConsole(String text, int color) { MainActivity.this.appendConsole(text, color); }
            @Override public void clearConsole() { if (consoleManager != null) consoleManager.clear(); }
            @Override public void switchBottomPanel(int mode) { MainActivity.this.switchBottomPanel(mode); }
            @Override public void saveCurrentToActiveTab() { MainActivity.this.saveCurrentToActiveTab(); }
            @Override public void onCallGraphFromBytecode(String className) { openCallGraphFromBytecode(className); }
        });
        bytecodeManager.bind();

        tabDebug         = findViewById(R.id.tabDebug);
        tabDebugConsole  = findViewById(R.id.tabDebugConsole);
        tabCallGraph     = findViewById(R.id.tabCallGraph);
        tabBookmarks     = findViewById(R.id.tabBookmarks);
        bookmarkController = new BookmarkUiController(this, new BookmarkUiController.Callback() {
            @Override public void onBookmarkClicked(java.io.File file, int line) {
                openFile(file);
                if (line > 0 && ws.activeEditor != null) {
                    ws.activeEditor.postDelayed(() -> ws.activeEditor.setSelection(line - 1, 0), 200);
                }
            }
            @Override public AppTheme getTheme() { return theme; }
        });
        bookmarkController.bind();

        debugCoordinator = new DebugUiCoordinator(this, ws, new DebugUiCoordinator.Callback() {
            @Override public com.ccs.wiredroid.debug.DebugEditorDecorator getActiveEditorDecorator() { return ws.activeEditorDecorator; }
            @Override public AppTheme getTheme() { return theme; }
            @Override public File findFileInProject(String fileName) { return MainActivity.this.findFileInProject(fileName); }
            @Override public void openFile(File file) { MainActivity.this.openFile(file); }
            @Override public void switchBottomPanel(int mode) { MainActivity.this.switchBottomPanel(mode); }
            @Override public void setRunning(boolean running) { isRunning = running; }
            @Override public BottomPanelController getPanelController() { return panelController; }
        });
        debugCoordinator.bind();

        searchableMenuController = new SearchableMenuController(this, new SearchableMenuController.Callback() {
            @Override public AppTheme getTheme() { return theme; }
            @Override public String getMavenPanelLabel() { return buildPanelLabel(); }
            @Override public void executeMenuAction(String action) { MainActivity.this.executeMenuAction(action); }
        });

        // The named menus across the top. Everything the toolbar has no room
        // for lives here by name, instead of only behind the ⋮ palette.
        LinearLayout menuBarHost = findViewById(R.id.menuBarHost);
        if (menuBarHost != null) {
            menuBar = new EditorMenuBar(this, new EditorMenuBar.Callback() {
                @Override public AppTheme getTheme() { return theme; }
                @Override public String getMavenPanelLabel() { return buildPanelLabel(); }
                @Override public boolean isRunning() { return isRunning; }
                @Override public void executeMenuAction(String action) {
                    MainActivity.this.executeMenuAction(action);
                }
            });
            menuBarHost.addView(menuBar.createView());
        }

        projectTransfer = new ProjectTransferController(this, ws, new ProjectTransferController.Callback() {
            @Override public ProjectManager getProjectManager() { return projectManager; }
            @Override public FileTreeAdapter getFileTreeAdapter() { return fileTreeAdapter; }
            @Override public void refreshFileTree() { MainActivity.this.refreshFileTree(); }
            @Override public void openFile(File file) { MainActivity.this.openFile(file); }
        });

        callGraphRoot     = findViewById(R.id.callGraphRoot);
        callGraphManager = new CallGraphPanelManager(this, new CallGraphPanelManager.Callback() {
            @Override public File getProjectDir() { return projectManager != null ? projectManager.getProjectDir() : null; }
            @Override public AppTheme getTheme() { return theme; }
            @Override public void runOnUiThread(Runnable r) { MainActivity.this.runOnUiThread(r); }
        });
        callGraphManager.bind();

        // Minimap
        errorStripe1 = findViewById(R.id.errorStripe1);
        errorStripe2 = findViewById(R.id.errorStripe2);
        // Tapping a mark goes to its line, with the same flash as any other jump.
        for (ErrorStripeOverlay stripe : new ErrorStripeOverlay[]{ errorStripe1, errorStripe2 }) {
            if (stripe != null) stripe.setListener(this::jumpToEditorLine);
        }

        ws.minimapView1 = findViewById(R.id.minimapView1);
        ws.minimapView2 = findViewById(R.id.minimapView2);

        // Editor chrome
        gitGutterOverlay1 = findViewById(R.id.gitGutterOverlay1);
        gitGutterOverlay2 = findViewById(R.id.gitGutterOverlay2);
        breadcrumbBar = findViewById(R.id.breadcrumbBar);
        filePathBar = findViewById(R.id.filePathBar);

        // Dependency Viewer
        depsManager = new DependencyPanelManager(this, new DependencyPanelManager.Callback() {
            @Override public void runOnUiThread(Runnable r) { MainActivity.this.runOnUiThread(r); }
            @Override public ProjectManager getProjectManager() { return projectManager; }
            @Override public AppTheme getTheme() { return theme; }
        });
        depsManager.bind();

        // Profiler
        profilerManager = new ProfilerPanelManager(this, ws, new ProfilerPanelManager.Callback() {
            @Override public void saveCurrentToActiveTab() { MainActivity.this.saveCurrentToActiveTab(); }
            @Override public boolean isRunning() { return isRunning; }
            @Override public void setRunning(boolean r) { isRunning = r; }
            @Override public String getEditorText() { return ws.activeEditor != null && ws.activeEditor.getText() != null ? ws.activeEditor.getText().toString() : ""; }
            @Override public void runOnUiThread(Runnable r) { MainActivity.this.runOnUiThread(r); }
            @Override public void appendConsole(String text, int color) { MainActivity.this.appendConsole(text, color); }
            @Override public void switchBottomPanel(int panel) { MainActivity.this.switchBottomPanel(panel); }
            @Override public void showToast(String msg) { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show(); }
            @Override public PowerSavingManager getPowerSaving() { return powerSaving; }
            @Override public AppTheme getTheme() { return theme; }
            @Override public AppPreferences getAppPrefs() { return appPrefs; }
        });
        profilerManager.bind();

        // Maven
        mavenDelegate = new MavenActionDelegate(new MavenActionDelegate.Callback() {
            @Override public void runOnUiThread(Runnable r) { MainActivity.this.runOnUiThread(r); }
            @Override public ProjectManager getProjectManager() { return projectManager; }
            @Override public AppTheme getTheme() { return theme; }
            @Override public void appendConsole(String text, int color) { MainActivity.this.appendConsole(text, color); }
            @Override public void switchBottomPanel(int panel) { MainActivity.this.switchBottomPanel(panel); }
            @Override public void setConsoleText(String text) { if (consoleManager != null) consoleManager.setText(text); }
            @Override public void setProblemsItems(List<ProblemItem> items) {
                problemsAdapter.setItems(items);
                applyInlineDiagnostics(items);
            }
            @Override public void saveCurrentToActiveTab() { MainActivity.this.saveCurrentToActiveTab(); }
            @Override public Activity getActivity() { return MainActivity.this; }
        });

        // TODO/FIXME
        todoManager = new TodoPanelManager(this, new TodoPanelManager.Callback() {
            @Override public void onTodoItemClicked(java.io.File file, int line) {
                openFile(file);
                if (line > 0 && ws.activeEditor != null) {
                    ws.activeEditor.setSelection(Math.max(0, line - 1), 0);
                }
            }
            @Override public void runOnUiThread(Runnable r) { MainActivity.this.runOnUiThread(r); }
            @Override public ProjectManager getProjectManager() { return projectManager; }
            @Override public AppTheme getTheme() { return theme; }
            @Override public java.io.File getCurrentFile() {
                FileTab active = ws.activeTab();
                return active == null ? null : active.file;
            }
        });
        todoManager.bind();

        // JShell Console
        jshellManager = new JShellPanelManager(this,
                findViewById(R.id.panelJShell),
                (TextView) findViewById(R.id.tabConsole),
                findViewById(R.id.jshellScroll),
                findViewById(R.id.jshellOutput),
                findViewById(R.id.jshellInput));
    }

    /** Перефарбовує статичні UI-елементи відповідно до поточної теми. */
    private void applyTheme() {
        if (wireDrawer != null) wireDrawer.applyTheme(theme);
        if (menuBar != null) menuBar.applyTheme(theme);
        View menuBarSeparator = findViewById(R.id.menuBarSeparator);
        if (menuBarSeparator != null) menuBarSeparator.setBackgroundColor(theme.separator);
        if (drawerLayout != null) drawerLayout.setBackgroundColor(theme.bg);
        View drawerRoot   = findViewById(R.id.drawerRoot);
        View drawerHeader = findViewById(R.id.drawerHeader);
        if (drawerRoot != null)   drawerRoot.setBackgroundColor(theme.toolbar);
        if (drawerHeader != null) drawerHeader.setBackgroundColor(theme.bg);
        if (toolbar != null) {
            toolbar.setBackgroundColor(theme.toolbar);
            Drawable overflow = toolbar.getOverflowIcon();
            if (overflow != null) {
                overflow.mutate().setColorFilter(theme.text, PorterDuff.Mode.SRC_IN);
            }
        }
        if (toolbarTitle != null) toolbarTitle.setTextColor(theme.text);
        View toolbarBackBtn = findViewById(R.id.toolbarBack);
        if (toolbarBackBtn != null) ((TextView) toolbarBackBtn).setTextColor(theme.text);
        View toolbarOverflowBtn = findViewById(R.id.toolbarOverflow);
        if (toolbarOverflowBtn != null) ((TextView) toolbarOverflowBtn).setTextColor(theme.text);
        Drawable navIcon = toolbar != null ? toolbar.getNavigationIcon() : null;
        if (navIcon != null) navIcon.mutate().setColorFilter(theme.text, PorterDuff.Mode.SRC_IN);
        if (tabsBar != null)      tabsBar.setBackgroundColor(theme.toolbar);
        if (tabBorder != null)    tabBorder.setBackgroundColor(theme.accent);
        // The handle stays transparent; only the hairline and the grip take a
        // colour, or the panes would be separated by a 14dp block.
        if (splitDivider != null) {
            splitDivider.applyTheme(theme.separator, theme.textDim, theme.accent);
        } else {
            if (ws.editorDividerLine != null) ws.editorDividerLine.setBackgroundColor(theme.separator);
            if (ws.editorDividerGrip != null) ws.editorDividerGrip.setBackgroundColor(theme.textDim);
        }
        if (tabsGroupDividerLine != null) tabsGroupDividerLine.setBackgroundColor(theme.separator);
        updateActiveEditorBorders();
        // Update editor color scheme
        if (ws.editor != null) EditorSettingsApplier.apply(ws.editor, appPrefs, theme);
        if (ws.editor2 != null) EditorSettingsApplier.apply(ws.editor2, appPrefs, theme);

        // Minimap theming
        if (ws.minimapView1 != null) ws.minimapView1.setThemeColors(
                theme.consoleBg, theme.text, theme.editorKeyword, theme.editorString,
                theme.editorComment, 0xFFB5CEA8, theme.accent, 0x28FFFFFF, 0x50FFFFFF);
        if (ws.minimapView2 != null) ws.minimapView2.setThemeColors(
                theme.consoleBg, theme.text, theme.editorKeyword, theme.editorString,
                theme.editorComment, 0xFFB5CEA8, theme.accent, 0x28FFFFFF, 0x50FFFFFF);

        if (gitGutter != null) gitGutter.applyTheme(theme);
        if (breadcrumbBar != null) breadcrumbBar.applyTheme(theme);
        if (filePathBar != null) filePathBar.applyTheme(theme);
        if (btnExitZenMode != null) styleZenExitButton();

        View accessoryBarContainer = findViewById(R.id.accessoryBarContainer);
        if (accessoryBarContainer != null) accessoryBarContainer.setBackgroundColor(theme.toolbar);
        View keyAccessoryBar = findViewById(R.id.keyAccessoryBar);
        if (keyAccessoryBar != null) keyAccessoryBar.setBackgroundColor(theme.toolbar);
        setupKeyAccessoryBar();

        refreshBreakpointMarkers();
        refreshBookmarkMarkers();
        if (bottomTabsBar != null) bottomTabsBar.setBackgroundColor(theme.toolbar);
        refreshBottomTabColors();
        if (statusBar != null)    statusBar.setBackgroundColor(theme.statusBar);
        if (consoleManager != null) consoleManager.applyTheme(theme);
        if (testPanelManager != null) testPanelManager.applyTheme(theme);
        if (findReplaceController != null) findReplaceController.applyTheme(theme);
        if (liveMetrics != null) liveMetrics.applyTheme(theme);
        if (mavenPanel != null) mavenPanel.applyTheme(theme);
        if (ws.inlayOverlay1 != null) ws.inlayOverlay1.applyTheme(theme);
        if (ws.inlayOverlay2 != null) ws.inlayOverlay2.applyTheme(theme);
        if (problemsRecycler != null) problemsRecycler.setBackgroundColor(theme.consoleBg);
        applyProblemsPanelTheme();
        if (bookmarkController != null && bookmarkController.getRecycler() != null)
            bookmarkController.getRecycler().setBackgroundColor(theme.consoleBg);
        if (tabBookmarks != null && bottomPanelMode != PANEL_BOOKMARKS)
            tabBookmarks.setTextColor(theme.textDim);

        if (bytecodeManager != null) bytecodeManager.applyTheme(theme);
        if (debugCoordinator != null) debugCoordinator.applyTheme(theme);

        // Call Graph panel theming
        if (callGraphManager != null) callGraphManager.applyTheme(theme);

        // Dependency Viewer theming
        if (depsManager != null) depsManager.applyTheme(theme);

        // Profiler theming
        if (profilerManager != null) profilerManager.applyTheme(theme);

        // TODO/FIXME theming
        if (todoManager != null) todoManager.applyTheme(theme);

        // JShell theming
        if (jshellManager != null) jshellManager.applyTheme(theme);

        // Drawer elements theming
        TextView tabProjectView = findViewById(R.id.tabProject);
        if (tabProjectView != null) tabProjectView.setTextColor(theme.accent);
        if (structureOptions != null) structureOptions.applyTheme(theme);
        TextView tvDrawerArrow = findViewById(R.id.tvDrawerArrow);
        if (tvDrawerArrow != null) tvDrawerArrow.setTextColor(theme.textDim);
        TextView tvDrawerMavenHint = findViewById(R.id.tvDrawerMavenHint);
        if (tvDrawerMavenHint != null) tvDrawerMavenHint.setTextColor(theme.textDim);
        TextView tvDrawerNewFilePlus = findViewById(R.id.tvDrawerNewFilePlus);
        if (tvDrawerNewFilePlus != null) tvDrawerNewFilePlus.setTextColor(theme.accent);
        TextView tvDrawerNewFileText = findViewById(R.id.tvDrawerNewFileText);
        if (tvDrawerNewFileText != null) tvDrawerNewFileText.setTextColor(theme.text);
        TextView tvDrawerScratchesText = findViewById(R.id.tvDrawerScratchesText);
        if (tvDrawerScratchesText != null) tvDrawerScratchesText.setTextColor(theme.text);
        android.widget.ImageView ivDrawerScratchesIcon = findViewById(R.id.ivDrawerScratchesIcon);
        if (ivDrawerScratchesIcon != null) {
            ivDrawerScratchesIcon.setColorFilter(theme.accent, PorterDuff.Mode.SRC_IN);
        }
        View sep1 = findViewById(R.id.drawerSeparator1);
        if (sep1 != null) sep1.setBackgroundColor(theme.separator);
        View sep2 = findViewById(R.id.drawerSeparator2);
        if (sep2 != null) sep2.setBackgroundColor(theme.separator);

        // Adapters theming
        if (ws.tabsLeft  != null) ws.tabsLeft.setTheme(theme);
        if (ws.tabsRight != null) ws.tabsRight.setTheme(theme);
        if (fileTreeAdapter != null) fileTreeAdapter.setTheme(theme);
        if (problemsAdapter != null) problemsAdapter.setTheme(theme);

        // Status bar дочірні: дивайдери та текстові підписи (UTF-8 / Java)
        if (statusBar instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) statusBar;
            for (int i = 0; i < g.getChildCount(); i++) {
                View v = g.getChildAt(i);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(theme.textDim);
                } else if (v.getBackground() != null) {
                    v.setBackgroundColor(theme.separator);
                }
            }
        }
        if (statusLineCol != null) statusLineCol.setTextColor(theme.text);
        if (statusFileName != null) statusFileName.setTextColor(theme.textDim);
        if (statusLineSeparator != null) {
            statusLineSeparator.setTextColor(theme.textDim);
        }
        if (statusEncoding != null) {
            statusEncoding.setTextColor(theme.textDim);
            statusEncoding.setText(appPrefs.getFileEncoding());
        }
        // The padlock recolours itself from the current theme.
        updateReadOnlyIndicator();
        for (int id : new int[]{R.id.btnTreeExpandAll, R.id.btnTreeCollapseAll}) {
            View v = findViewById(id);
            if (v instanceof TextView) ((TextView) v).setTextColor(theme.textDim);
        }
        if (keyAccessoryBar != null) keyAccessoryBar.setBackgroundColor(theme.toolbar);
        setupKeyAccessoryBar();
        invalidateOptionsMenu();
    }

    private void setupKeyAccessoryBar() {
        if (accessoryBarLayout == null) return;
        accessoryBarLayout.removeAllViews();

        // ▲ ▼ and Tab drive the completion popup when it is open. On a tablet with
        // no hardware keyboard there is otherwise no way to reach the second
        // suggestion at all — the list can only be tapped, and the popup sits
        // where the finger is about to land.
        final String SUGGEST_UP = "▲";
        final String SUGGEST_DOWN = "▼";
        final String CARET_LEFT = "◀";
        final String CARET_RIGHT = "▶";
        final String COMMENT = "//";
        final String[] editorActions = {"Next", "Skip", "All", "Expand", "Def"};

        // Symbols worth a tap depend on the file: an XML file wants angle
        // brackets, a Java one a semicolon, and the row is too small to carry
        // both. A custom row switches that off — it is then exactly what was
        // typed into the setting, navigation keys included.
        java.util.List<String> tokens = new java.util.ArrayList<>();
        if (appPrefs.isContextualSymbols()) {
            FileTab activeTab = ws.tabs() == null ? null : ws.tabs().getActiveTab();
            java.util.Collections.addAll(tokens,
                    com.ccs.wiredroid.editor.EditorTextActions.contextualSymbols(
                            activeTab == null ? null : activeTab.file));
            java.util.Collections.addAll(tokens,
                    COMMENT, CARET_LEFT, CARET_RIGHT, SUGGEST_UP, SUGGEST_DOWN, "Tab");
        } else {
            String rawSymbols = appPrefs.getKeyBarSymbols();
            if (rawSymbols == null || rawSymbols.trim().isEmpty()) {
                rawSymbols = AppPreferences.DEFAULT_KEY_BAR_SYMBOLS;
            }
            java.util.Collections.addAll(tokens, rawSymbols.trim().split("\\s+"));
        }
        final String[] symbols = tokens.toArray(new String[0]);

        if (ws.activeEditor instanceof WireDroidCodeEditor) {
            for (String action : editorActions) {
                TextView btn = new TextView(this);
                btn.setText(action); btn.setTextColor(theme.text); btn.setTextSize(12);
                btn.setGravity(Gravity.CENTER); btn.setPadding(dp(10), 0, dp(10), 0);
                btn.setFocusable(false); btn.setClickable(true);
                btn.setBackgroundResource(android.R.drawable.list_selector_background);
                btn.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
                btn.setOnClickListener(v -> {
                    WireDroidCodeEditor editor = (WireDroidCodeEditor) ws.activeEditor;
                    switch (action) {
                        case "Next":
                            if (!editor.hasOccurrenceSelections()) editor.selectNextOccurrence();
                            else editor.selectNextOccurrence();
                            break;
                        case "Skip": editor.skipNextOccurrence(); break;
                        case "All": editor.selectAllOccurrences(); break;
                        case "Expand": editor.expandSelection(); break;
                        case "Def": goToDefinition(); break;
                    }
                });
                accessoryBarLayout.addView(btn);
            }
        }

        for (final String symbol : symbols) {
            TextView btn = new TextView(this);
            btn.setText(symbol);
            btn.setTextColor(theme.text);
            btn.setTextSize(14);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(dp(12), 0, dp(12), 0);

            android.util.TypedValue tv = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            if (tv.resourceId != 0) {
                btn.setBackgroundResource(tv.resourceId);
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            btn.setLayoutParams(lp);
            btn.setClickable(true);
            // Not focusable: these are touch targets, and taking focus from the
            // editor closes the suggestion popup before the click handler can
            // act on it — which is exactly what Tab and the arrows need to reach.
            btn.setFocusable(false);
            btn.setFocusableInTouchMode(false);

            if (COMMENT.equals(symbol)) {
                btn.setOnClickListener(v -> executeMenuAction("toggle_comment"));
                accessoryBarLayout.addView(btn);
                continue;
            }

            if (CARET_LEFT.equals(symbol) || CARET_RIGHT.equals(symbol)) {
                // Held down, it repeats: placing a caret precisely by tapping a
                // glyph forty times is the part of touch editing that hurts most.
                final boolean right = CARET_RIGHT.equals(symbol);
                btn.setOnTouchListener(new View.OnTouchListener() {
                    private final Runnable repeat = new Runnable() {
                        @Override public void run() {
                            moveCaretHorizontally(right);
                            btn.postDelayed(this, 45);
                        }
                    };

                    @Override
                    public boolean onTouch(View v, android.view.MotionEvent event) {
                        switch (event.getAction()) {
                            case android.view.MotionEvent.ACTION_DOWN:
                                moveCaretHorizontally(right);
                                btn.postDelayed(repeat, 320);
                                return true;
                            case android.view.MotionEvent.ACTION_UP:
                            case android.view.MotionEvent.ACTION_CANCEL:
                                btn.removeCallbacks(repeat);
                                return true;
                            default:
                                return false;
                        }
                    }
                });
                accessoryBarLayout.addView(btn);
                continue;
            }

            if (SUGGEST_UP.equals(symbol) || SUGGEST_DOWN.equals(symbol) || "Tab".equalsIgnoreCase(symbol)) {
                // Acted on touch-down, not on click. The popup hides itself the
                // moment the editor loses focus, and a click is delivered after
                // that has already happened — which made this work or not work
                // depending on timing. Touch-down runs first, so the popup is
                // still there to act on.
                final boolean isTab = "Tab".equalsIgnoreCase(symbol);
                final boolean down = SUGGEST_DOWN.equals(symbol);
                btn.setOnTouchListener((v, event) -> {
                    if (event.getAction() != android.view.MotionEvent.ACTION_DOWN) return false;
                    if (ws.activeEditor == null || !ws.activeEditor.isEditable()) return true;
                    if (!isTab) {
                        moveThroughSuggestionsOrCaret(down);
                        return true;
                    }
                    if (acceptSuggestion()) return true;
                    int tabSize = appPrefs.getTabSize();
                    StringBuilder spaces = new StringBuilder();
                    for (int i = 0; i < tabSize; i++) spaces.append(" ");
                    ws.activeEditor.insertText(spaces.toString(), spaces.length());
                    return true;
                });
                accessoryBarLayout.addView(btn);
                continue;
            }

            btn.setOnClickListener(v -> {
                if (ws.activeEditor != null && ws.activeEditor.isEditable()) {
                    ws.activeEditor.insertText(symbol, symbol.length());
                }
            });
            accessoryBarLayout.addView(btn);
        }
    }

    /** The completion popup of the focused editor, or null when none is showing. */
    @androidx.annotation.Nullable
    private io.github.rosemoe.sora.widget.component.EditorAutoCompletion visibleSuggestions() {
        if (ws.activeEditor == null) return null;
        try {
            io.github.rosemoe.sora.widget.component.EditorAutoCompletion window =
                    ws.activeEditor.getComponent(
                            io.github.rosemoe.sora.widget.component.EditorAutoCompletion.class);
            return window != null && window.isShowing() ? window : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** @return true when a suggestion was accepted, false when there was none. */
    private boolean acceptSuggestion() {
        io.github.rosemoe.sora.widget.component.EditorAutoCompletion window = visibleSuggestions();
        if (window == null) return false;
        if (window.select()) return true;
        // The list opens with nothing highlighted, so select() has no position to
        // act on until an arrow key moves it. Tab arriving first is the normal
        // case, not an edge one — take the top suggestion, as an IDE does.
        try {
            return window.select(0);
        } catch (Throwable empty) {
            // No items after all; the caller falls back to inserting an indent.
            return false;
        }
    }

    /** Steps through the open suggestion list, or moves the caret up/down if closed. */
    private void moveThroughSuggestionsOrCaret(boolean down) {
        io.github.rosemoe.sora.widget.component.EditorAutoCompletion window = visibleSuggestions();
        if (window != null) {
            if (down) {
                window.moveDown();
            } else {
                window.moveUp();
            }
            return;
        }
        if (ws.activeEditor != null) {
            int keyCode = down ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP;
            ws.activeEditor.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            ws.activeEditor.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        }
    }

    /** One character left or right, wrapping across line ends as the caret does. */
    private void moveCaretHorizontally(boolean right) {
        if (ws.activeEditor == null) return;
        int keyCode = right ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
        ws.activeEditor.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        ws.activeEditor.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setNavigationIcon(R.drawable.ic_menu);
        Drawable nav = toolbar.getNavigationIcon();
        if (nav != null) nav.setColorFilter(theme.text, PorterDuff.Mode.SRC_IN);
        toolbar.setNavigationOnClickListener(v ->
                drawerLayout.openDrawer(GravityCompat.START));

        // Three-dot overflow button → searchable menu
        View overflowBtn = findViewById(R.id.toolbarOverflow);
        if (overflowBtn != null) {
            overflowBtn.setOnClickListener(v -> showSearchableMenu());
        }

        // Back button → WelcomeActivity
        View backBtn = findViewById(R.id.toolbarBack);
        if (backBtn != null) {
            backBtn.setVisibility(View.VISIBLE);
            backBtn.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, WelcomeActivity.class);
                startActivity(intent);
                finish();
            });
        }

        // Clear console button in bottom panel
        if (btnClearConsole != null) {
            btnClearConsole.setOnClickListener(v -> {
                if (bottomPanelMode == PANEL_RUN && consoleManager != null) {
                    consoleManager.clear();
                }
                else if (bottomPanelMode == PANEL_CONSOLE && jshellManager != null) jshellManager.getConsoleOutput().setText("");
            });
        }
        if (btnCopyPanel != null) {
            btnCopyPanel.setOnClickListener(v -> {
                String textToCopy = "";
                if (bottomPanelMode == PANEL_RUN && consoleManager != null) {
                    textToCopy = consoleManager.getText();
                } else if (bottomPanelMode == PANEL_CONSOLE && jshellManager != null) {
                    textToCopy = jshellManager.getConsoleOutput().getText().toString();
                } else if (bottomPanelMode == PANEL_TODO && todoManager != null) {
                    textToCopy = todoManager.getCopyText();
                } else if (bottomPanelMode == PANEL_PROBLEMS && problemsAdapter != null) {
                    StringBuilder sb = new StringBuilder();
                    for (ProblemItem p : problemsAdapter.getItems()) {
                        sb.append(p.message).append(" (").append(p.file).append(":").append(p.line).append(")\n");
                    }
                    textToCopy = sb.toString();
                }
                if (!textToCopy.isEmpty()) {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("Panel Output", textToCopy);
                    clipboard.setPrimaryClip(clip);
                    android.widget.Toast.makeText(MainActivity.this, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
    /**
     * Whether the running program's output has already reached the console.
     *
     * <p>Output now arrives as it is produced, but the runner still delivers the
     * whole thing again when the program ends. Printing both would show every
     * line twice, so the final delivery only adds the summary.</p>
     */
    private boolean streamedOutput = false;

    private void setupConsoleInput() {
        android.widget.EditText input = findViewById(R.id.consoleInput);
        View send = findViewById(R.id.btnConsoleSend);
        if (input == null) return;

        Runnable submit = () -> {
            String line = input.getText().toString();
            boolean accepted = com.ccs.wiredroid.tools.compilers.ConsoleInputHolder.submit(line);
            if (!accepted) {
                Toast.makeText(this, R.string.console_input_closed, Toast.LENGTH_SHORT).show();
                return;
            }
            // Echoed the way a terminal echoes what you type — without it the
            // console shows the prompt and then the answer out of nowhere.
            appendConsole(line, theme.textDim);
            input.setText("");
        };
        input.setOnEditorActionListener((v, actionId, event) -> {
            // A hardware Enter is delivered here twice — once for the key going
            // down and once for it coming up, both as IME_NULL. Acting on both
            // handed the program two lines for one press, and the second was
            // empty, which is what a parse of the next input would choke on.
            if (event != null && event.getAction() != android.view.KeyEvent.ACTION_DOWN) {
                return true;
            }
            submit.run();
            return true;
        });
        if (send != null) send.setOnClickListener(v -> submit.run());
    }

    private void setupTabs() {
        ws.tabsLeft  = buildTabStrip(tabsRecycler, ws.editor);
        ws.tabsRight = buildTabStrip(tabsRecycler2, ws.editor2);

        // "+" new tab button
        View btnNewTab = findViewById(R.id.btnNewTab);
        if (btnNewTab != null) btnNewTab.setOnClickListener(v -> showNewFileDialog());
    }

    /**
     * Wires one strip to one pane.
     *
     * <p>The listener closes over the pane rather than reading the active one,
     * so a tap on the right-hand strip moves focus there first. Without that,
     * tapping a tab in the pane you are not in would load its file into the
     * pane you are — the tab would appear to jump sides.</p>
     */
    private TabsAdapter buildTabStrip(RecyclerView strip,
                                      io.github.rosemoe.sora.widget.CodeEditor pane) {
        TabsAdapter adapter = new TabsAdapter();
        adapter.setTabListener(new TabsAdapter.TabListener() {
            @Override public void onTabSelected(int index) {
                focusPane(pane);
                switchTab(index);
            }
            @Override public void onTabClosed(int index) {
                focusPane(pane);
                closeTab(index);
            }
            @Override public void onTabLongPressed(int index) {
                focusPane(pane);
                showTabContextMenu(index, null, 0, 0);
            }
            @Override public void onTabMenuRequested(int index, View anchor, float x, float y) {
                focusPane(pane);
                showTabContextMenu(index, anchor, x, y);
            }
        });
        strip.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        strip.setAdapter(adapter);
        return adapter;
    }

    /** Moves focus to a pane without touching what either pane is showing. */
    private void focusPane(io.github.rosemoe.sora.widget.CodeEditor pane) {
        if (pane == null || ws.activeEditor == pane) return;
        if (pane == ws.editor2 && !ws.isSplitActive) return;
        saveCurrentToActiveTab();
        ws.activeEditor = pane;
        pane.requestFocus();
        updateActiveEditorBorders();
    }

    /** The strip belonging to the pane that has focus. */
    private RecyclerView activeStrip() {
        return ws.activeEditor == ws.editor2 ? tabsRecycler2 : tabsRecycler;
    }

    private void setupFileTree() {
        fileTreeAdapter = new FileTreeAdapter();
        fileTreeController = new FileTreeController(this, ws, new FileTreeController.Callback() {
            @Override public void onFileOpened(File file) { openFile(file); }
            @Override public void onRefreshNeeded() {
                // Renames and deletes go through ProjectManager, which drops the
                // kept sweep itself.
            }
            @Override public AppTheme getTheme() { return theme; }
            @Override public ProjectManager getProjectManager() { return projectManager; }
            @Override public FileTreeAdapter getFileTreeAdapter() { return fileTreeAdapter; }
            @Override public DrawerLayout getDrawerLayout() { return drawerLayout; }
            @Override public int dp(int v) { return MainActivity.this.dp(v); }
        });
        fileTreeAdapter.setNodeListener(new FileTreeAdapter.NodeListener() {
            @Override
            public void onNodeClicked(FileTreeNode node) {
                if (node.directory) {
                    // Tapping a folder opens or closes it instead of doing nothing.
                    fileTreeController.toggleFolder(node.path);
                    return;
                }
                openFile(node.path);
                drawerLayout.closeDrawer(GravityCompat.START);
            }


            @Override
            public void onNodeLongClicked(FileTreeNode node) {
                if (node.directory) {
                    fileTreeController.showFolderContextMenu(node.path);
                } else {
                    fileTreeController.showFileContextMenu(node.path);
                }
            }
        });
        fileTreeRecycler.setLayoutManager(new LinearLayoutManager(this));
        fileTreeRecycler.setAdapter(fileTreeAdapter);
        fileTreeAdapter.setReadOnlyPaths(appPrefs.getReadOnlyFiles());

        if (structureRecycler != null) {
            structureRecycler.setLayoutManager(new LinearLayoutManager(this));
            structureAdapter = new com.ccs.wiredroid.ui.StructureAdapter(this, theme, member -> {
                drawerLayout.closeDrawer(GravityCompat.START);
                if (ws.activeEditor != null) {
                    ws.activeEditor.setSelection(member.line, 0);
                }
            });
            structureRecycler.setAdapter(structureAdapter);
        }

        if (btnStructureOptions != null) {
            structureOptions = new com.ccs.wiredroid.ui.StructureOptionsController(
                    this, appPrefs, btnStructureOptions, theme,
                    this::updateStructureDrawer);
        }

        if (tabProject != null && tabStructure != null) {
            tabProject.setOnClickListener(v -> {
                tabProject.setTextColor(theme.text);
                tabProject.setTypeface(null, android.graphics.Typeface.BOLD);
                tabStructure.setTextColor(theme.textDim);
                tabStructure.setTypeface(null, android.graphics.Typeface.NORMAL);
                projectContainer.setVisibility(View.VISIBLE);
                structureRecycler.setVisibility(View.GONE);
                if (btnStructureOptions != null) btnStructureOptions.setVisibility(View.GONE);
            });
            tabStructure.setOnClickListener(v -> {
                tabStructure.setTextColor(theme.text);
                tabStructure.setTypeface(null, android.graphics.Typeface.BOLD);
                tabProject.setTextColor(theme.textDim);
                tabProject.setTypeface(null, android.graphics.Typeface.NORMAL);
                projectContainer.setVisibility(View.GONE);
                structureRecycler.setVisibility(View.VISIBLE);
                if (btnStructureOptions != null) btnStructureOptions.setVisibility(View.VISIBLE);
                updateStructureDrawer();
            });
        }
        
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                if (structureRecycler != null && structureRecycler.getVisibility() == View.VISIBLE) {
                    updateStructureDrawer();
                }
            }
        });

        View btnTreeExpandAll = findViewById(R.id.btnTreeExpandAll);
        if (btnTreeExpandAll != null) {
            btnTreeExpandAll.setOnClickListener(v -> fileTreeController.expandAll());
        }
        View btnTreeCollapseAll = findViewById(R.id.btnTreeCollapseAll);
        if (btnTreeCollapseAll != null) {
            btnTreeCollapseAll.setOnClickListener(v -> fileTreeController.collapseAll());
        }

        View btnNewFile = findViewById(R.id.btnNewFile);
        if (btnNewFile != null) btnNewFile.setOnClickListener(v -> fileTreeController.showNewFileDialog());

        View btnImportFiles = findViewById(R.id.btnImportFiles);
        if (btnImportFiles != null) btnImportFiles.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            importFilesToProject();
        });

        View btnScratches = findViewById(R.id.btnScratches);
        if (btnScratches != null) btnScratches.setVisibility(View.GONE);
        TextView sourceHint=findViewById(R.id.tvDrawerMavenHint);
        if(sourceHint!=null)sourceHint.setText("Embedded · sketches / src");
    }

    private void updateStructureDrawer() {
        if (structureAdapter != null && ws.activeEditor != null) {
            String text = ws.activeEditor.getText().toString();
            FileTab tab = ws.tabs() != null ? ws.tabs().getActiveTab() : null;
            String name = (tab != null && tab.file != null) ? tab.file.getName() : "";
            List<com.ccs.wiredroid.ui.MemberOutline.Member> members = com.ccs.wiredroid.ui.MemberOutline.scan(text, name);
            structureAdapter.setMembers(structureOptions != null
                    ? structureOptions.apply(members) : members);
        }
    }

    private void showRegexTesterDialog() {
        DeveloperToolDialogs.showRegexTester(this, theme);
    }

    private void showBase64EncoderDialog() {
        DeveloperToolDialogs.showEncoder(this, theme);
    }

    /**
     * Replaces the whole document, putting the caret and the viewport back.
     *
     * <p>{@code setText} resets the cursor to the very start and scrolls to the
     * top; {@code setSelection} then scrolls the caret back into view, but at
     * whatever offset it likes rather than the one the reader was at. Restoring
     * both is what keeps a reformat from feeling like the file jumped.</p>
     *
     * <p>Only for changes the user asked for — a reformat on Save, or before a
     * Run. Never call it from a background or debounced path: replacing the
     * buffer under someone who is still typing moves their caret.</p>
     */
    public static void setEditorTextPreservingSelection(CodeEditor editor, String newText) {
        if (editor == null || newText == null) return;
        int curLine = 0;
        int curCol = 0;
        try {
            curLine = editor.getCursor().getLeftLine();
            curCol = editor.getCursor().getLeftColumn();
        } catch (Exception ignored) {}
        int scrollX = editor.getScrollX();
        int scrollY = editor.getScrollY();

        editor.setText(newText);

        try {
            int maxLine = Math.max(0, editor.getText().getLineCount() - 1);
            int line = Math.max(0, Math.min(curLine, maxLine));
            int maxCol = Math.max(0, editor.getText().getColumnCount(line));
            int col = Math.max(0, Math.min(curCol, maxCol));
            editor.setSelection(line, col);
        } catch (Exception e) {
            // Swallowing this silently is how the caret ended up at 0,0 with no
            // trace of why. It stays non-fatal, but it stops being invisible.
            if (com.ccs.wiredroid.BuildConfig.DEBUG) {
                android.util.Log.w("Editor", "caret restore failed after replace", e);
            }
        }

        try {
            editor.scrollBy(scrollX - editor.getScrollX(), scrollY - editor.getScrollY());
        } catch (Exception ignored) {}
    }

    private boolean saveEditorToTab(CodeEditor ed, FileTab tab) {
        if (tab == null || tab.file == null) return false;
        try {
            if((tab.recoveredDraft||isFileReadOnly(tab.file))&&projectManager.isEmbeddedFile(tab.file)) {
                cancelWireDraft(ed);
                com.ccs.wiredroid.wire.RecoveryStore.checkpoint(tab.file,ed.getText().toString());
                tab.cursorLine=ed.getCursor().getLeftLine();tab.cursorColumn=ed.getCursor().getLeftColumn();return false;
            }
            cancelWireDraft(ed);
            // Deliberately writes the buffer through untouched.
            //
            // Every caller of this method is an *implicit* save: the debounced
            // auto-save, a tab switch, closing or swapping a split pane. Running
            // the formatter from here rewrote the whole document via setText()
            // roughly a second after the user stopped typing — which sent the
            // caret to the start of the line and scrolled the view, mid-sentence,
            // in a file that was still half-written. Formatting incomplete code
            // is also the case the formatter is worst at.
            //
            // Format-on-save now lives where the name says it does: saveTab(),
            // reached by the Save action. See shouldFormatOnSave() there.
            String content = ed.getText().toString();
            projectManager.writeFile(tab.file, content);
            if (gitGutter != null) gitGutter.refreshAll();
            tab.isModified = false;
            tab.cursorLine = ed.getCursor().getLeftLine();
            tab.cursorColumn = ed.getCursor().getLeftColumn();
            return true;
        } catch (IOException e) {
            try {
                if(projectManager.isEmbeddedFile(tab.file)) {
                    com.ccs.wiredroid.wire.RecoveryStore.checkpoint(tab.file,ed.getText().toString());
                    tab.recoveredDraft=true;
                }
            }catch(IOException checkpointError){android.util.Log.w("WireRecovery","Checkpoint after failed save",checkpointError);}
            Toast.makeText(this, getString(R.string.toast_save_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void updateActiveEditorBorders() {
        if (!ws.isSplitActive) {
            ws.wrapperEditor1.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            ws.wrapperEditor2.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            return;
        }
        int activeColor = theme != null ? theme.accent : 0xFF4A86C8;
        int inactiveColor = theme != null ? theme.separator : 0xFF515151;
        ws.wrapperEditor1.setBackgroundColor(ws.activeEditor == ws.editor ? activeColor : inactiveColor);
        ws.wrapperEditor2.setBackgroundColor(ws.activeEditor == ws.editor2 ? activeColor : inactiveColor);
    }

    private void setActiveEditor(CodeEditor ed) {
        if (com.ccs.wiredroid.BuildConfig.DEBUG) {
            android.util.Log.d("BpOverlay", "setActiveEditor: ed=" + (ed != null)
                    + " activeEditor=" + (ws.activeEditor == ed));
        }
        if (ws.activeEditor == ed) return;
        ws.activeEditor = ed;
        // Focus moved to the other pane, so the analysis now describes a different
        // file. Usually the text hash differs and the scheduler notices by itself;
        // it does not when both panes hold identical content, which is exactly what
        // happens when the same file is open on both sides.
        if (liveProblemsScheduler != null && powerSaving.shouldRunLiveProblems()) {
            liveProblemsScheduler.invalidate();
            liveProblemsScheduler.scheduleScan();
        }
        // Diagnostics belong to the editor pane's current file. Reapply the
        // latest list immediately when focus moves so a split editor cannot
        // leave markers from the other pane visible.
        applyInlineDiagnostics(problemsAdapter != null ? problemsAdapter.getItems() : null);
        updateActiveEditorBorders();
        BreakpointOverlay ov = (ed == ws.editor) ? ws.breakpointOverlay1 : ws.breakpointOverlay2;
        ws.activeEditorDecorator = new com.ccs.wiredroid.debug.DebugEditorDecorator(ed, ov);
        refreshBreakpointMarkers();
        refreshBookmarkMarkers();
        updateEditorChrome();

        FileTab currentTab = (ed == ws.editor) ? ws.leftTab : ws.rightTab;
        if (currentTab != null) {
            int idx = ws.tabs().getTabs().indexOf(currentTab);
            if (idx >= 0 && idx != ws.tabs().getActiveIndex()) {
                ws.tabs().setActiveIndex(idx);
                updateStatusFileName(currentTab.file);
                fileTreeAdapter.setActiveFile(currentTab.file);
            }
        }
    }

    private void configureEditor(final CodeEditor ed) {
        ed.setEditorLanguage(new CppLanguage());
        EditorSettingsApplier.apply(ed, appPrefs, theme);
        ed.setEditable(false);

        // Cursor position → status bar
        ed.subscribeEvent(io.github.rosemoe.sora.event.SelectionChangeEvent.class, (event, sub) -> {
            if (ws.activeEditor != ed) return;
            int ln  = event.getLeft().line + 1;
            int col = event.getLeft().column + 1;
            String text = getString(R.string.status_line, ln, col);
            if (!text.equals(statusLineCol.getText())) {
                runOnUiThread(() -> statusLineCol.setText(text));
            }
            // Trailing breadcrumb segment follows the caret into its method.
            final int caretLine = event.getLeft().line;
            runOnUiThread(() -> {
                if (breadcrumbBar != null) breadcrumbBar.setCaretLine(caretLine);
                updateLightbulb(caretLine + 1);
            });
        });

        // Content change → mark tab as modified + auto-save (if enabled)
        ed.subscribeEvent(io.github.rosemoe.sora.event.ContentChangeEvent.class, (event, sub) -> {
            final long editorEventStarted = android.os.SystemClock.elapsedRealtimeNanos();
            if (ws.isProgrammaticChange) return;
            // Recomputes are debounced and run on background worker threads; only the
            // buffer snapshot touches the main thread.
            runOnUiThread(() -> {
                if (gitGutter != null && powerSaving.shouldRunGitGutter()) gitGutter.schedule(ed);
                if (ws.activeEditor == ed) scheduleOutlineScan(600L);
                scheduleInlayHints(ed, 700L);
                if (liveProblemsScheduler != null && powerSaving.shouldRunLiveProblems()) {
                    // The scheduler decides whether to run by hashing the *focused*
                    // editor's text. An edit in the other split pane leaves that
                    // hash alone, so the pass would be skipped as "nothing changed"
                    // — the one case the dirty check gets wrong. Say so explicitly.
                    if (ed != ws.activeEditor) liveProblemsScheduler.invalidate();
                    // No literal here: the delay is the power profile's scan
                    // interval, set in onResume and when the profile changes.
                    liveProblemsScheduler.scheduleScan();
                }
            });
            FileTab tab = (ed == ws.editor) ? ws.leftTab : ws.rightTab;
            if (tab == null) return;
            int idx = ws.tabsFor(ed).getTabs().indexOf(tab);
            if (idx >= 0) {
                runOnUiThread(() -> {
                    if (!tab.isModified) ws.tabsFor(ed).markModified(idx, true);
                    scheduleAutoSave(ed, tab, idx);
                });
            }
            if (powerSaving.shouldRunTodoScan() && bottomPanelMode == PANEL_TODO) {
                if (todoManager != null) todoManager.scheduleAutoRefresh();
            }
            com.ccs.wiredroid.profiler.PerformanceMonitor performance =
                    com.ccs.wiredroid.profiler.PerformanceMonitor.get();
            if (performance != null) {
                performance.recordDuration("editor.changeLatency",
                        android.os.SystemClock.elapsedRealtimeNanos() - editorEventStarted);
            }
        });

        // Focus changes
        ed.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                setActiveEditor(ed);
            }
        });

        // Touch listener: gutter tap toggles breakpoint, rest captures focus
        // Triple-tap counter for bookmarks
        final long[] tapTimestamps = new long[3];
        final int[] tapCount = {0};

        ed.setOnTouchListener((v, event) -> {
            if (ws.activeEditor != ed) {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    ed.requestFocus();
                    setActiveEditor(ed);
                }
            }
            // Detect taps in the line number gutter area
            if (event.getAction() == android.view.MotionEvent.ACTION_UP && appPrefs.isLineNumbers()) {
                float gutterWidth = ed.measureTextRegionOffset();
                if (gutterWidth <= 0) gutterWidth = 60 * getResources().getDisplayMetrics().density;
                if (event.getX() < gutterWidth) {
                    long pos = ed.getPointPositionOnScreen(event.getX(), event.getY());
                    int line = io.github.rosemoe.sora.util.IntPair.getFirst(pos);
                    if (line >= 0) {
                        int line1 = line + 1;
                        long now = System.currentTimeMillis();
                        DebuggerController ctrl = DebuggerController.getInstance();
                        boolean isLongPress = (event.getEventTime() - event.getDownTime()) > 450;

                        // Triple-tap detection for bookmarks
                        if (!isLongPress) {
                            tapCount[0]++;
                            System.arraycopy(tapTimestamps, 1, tapTimestamps, 0, 2);
                            tapTimestamps[2] = now;

                            if (tapCount[0] >= 3
                                    && (tapTimestamps[2] - tapTimestamps[0]) < 500) {
                                // Triple-tap → toggle bookmark on the clicked line
                                toggleBookmarkAtLine(line1);
                                tapCount[0] = 0;
                                return true;
                            } else if (now - tapTimestamps[2] > 500) {
                                tapCount[0] = 1;
                            }
                        }

                        if (isLongPress) {
                            if (debugCoordinator != null) debugCoordinator.showBreakpointEditorDialog(line1);
                        } else if (tapCount[0] < 3) {
                            ctrl.toggleBreakpoint(line1);
                            boolean has = ctrl.hasBreakpoint(line1);
                            Toast.makeText(ed.getContext(),
                                    has ? getString(R.string.debug_breakpoint_set, line1) : getString(R.string.debug_breakpoint_removed, line1),
                                    Toast.LENGTH_SHORT).show();
                        }
                        if (ws.activeEditor == ed) {
                            refreshBreakpointMarkers();
                            refreshBookmarkMarkers();
                        }
                        return true;
                    }
                }
            }
            return false;
        });

        // Long press context menu for debugging
        ed.setOnLongClickListener(v -> {
            if (debugCoordinator == null || !debugCoordinator.isDebugging()) return false;
            DebuggerController ctrl = DebuggerController.getInstance();
            if (!ctrl.isPaused()) return false;

            // Get selected text
            CharSequence selected = ed.getText().subSequence(
                    ed.getCursor().getLeftColumn(),
                    ed.getCursor().getRightColumn());
            if (selected == null || selected.length() == 0) return false;

            String selText = selected.toString().trim();
            if (selText.isEmpty()) return false;

            // Show context menu
            String[] items = {"Evaluate '" + selText + "'", "Add Watch '" + selText + "'"};
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(ed.getContext())
                    .setItems(items, (dialog, which) -> {
                        if (which == 0) {
                            if (debugCoordinator != null) debugCoordinator.evaluateExpression(selText);
                        } else if (which == 1) {
                            if (debugCoordinator != null) debugCoordinator.addWatch(selText);
                            Toast.makeText(ed.getContext(), "Added watch: " + selText, Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
            return true;
        });

        ed.subscribeEvent(io.github.rosemoe.sora.event.ScrollEvent.class, (event, sub) -> {
            BreakpointOverlay bpOv = (ed == ws.editor) ? ws.breakpointOverlay1 : ws.breakpointOverlay2;
            if (bpOv != null) bpOv.postInvalidate();
            BookmarkOverlay bmOv = (ed == ws.editor) ? ws.bookmarkOverlay1 : ws.bookmarkOverlay2;
            if (bmOv != null) bmOv.postInvalidate();
            if (gitGutter != null) gitGutter.invalidate(ed);
            InlayHintsOverlay inlay = (ed == ws.editor) ? ws.inlayOverlay1 : ws.inlayOverlay2;
            if (inlay != null) inlay.postInvalidate();
        });
    }

    /** Debounces disk writes so auto-save does not write once per keystroke. */
    private void scheduleAutoSave(CodeEditor ed, FileTab tab, int index) {
        tab.recoveredDraft=false;scheduleWireDraft(ed,tab);
        if (pendingAutoSave != null) {
            autoSaveHandler.removeCallbacks(pendingAutoSave);
            pendingAutoSave = null;
        }
        if (!powerSaving.shouldAutoSave()) return;
        Runnable job = () -> {
            pendingAutoSave = null;
            if (ws.tabs() == null || ws.activeEditor != ed
                    || ws.tabs().getActiveTab() != tab || !tab.isModified) return;
            if(saveEditorToTab(ed, tab))ws.tabs().markModified(index, false);
        };
        pendingAutoSave = job;
        long delay = appPrefs.getAutoSaveDelayMs();
        if (delay <= 0) autoSaveHandler.post(job);
        else autoSaveHandler.postDelayed(job, delay);
    }

    private void cancelWireDraft(CodeEditor editor) {
        Runnable pending=wireDraftPending.remove(editor);if(pending!=null)wireDraftHandler.removeCallbacks(pending);
    }
    private void scheduleWireDraft(CodeEditor editor,FileTab tab) {
        cancelWireDraft(editor);
        try{if(projectManager==null||!projectManager.isEmbeddedFile(tab.file))return;}catch(IOException ignored){return;}
        Runnable task=()->{
            wireDraftPending.remove(editor);
            FileTab current=editor==ws.editor?ws.leftTab:ws.rightTab;
            if(current!=tab||!tab.isModified||isDestroyed())return;
            try {
                String text=editor.getText().toString();long epoch=com.ccs.wiredroid.wire.RecoveryStore.draftEpoch(tab.file);
                wireDraftWorker.execute(()->{try{com.ccs.wiredroid.wire.RecoveryStore.draft(tab.file,text,epoch);}catch(IOException failure){runOnUiThread(()->{if(!isDestroyed())Toast.makeText(this,"Не вдалося зберегти буфер для відновлення: "+failure.getMessage(),Toast.LENGTH_LONG).show();});}});
            }catch(IOException|java.util.concurrent.RejectedExecutionException ignored){}
        };
        wireDraftPending.put(editor,task);wireDraftHandler.postDelayed(task,450);
    }
    private void checkpointVisibleWireEditors() {
        for(CodeEditor editor:new CodeEditor[]{ws.editor,ws.editor2}) {
            if(editor==null)continue;cancelWireDraft(editor);FileTab tab=editor==ws.editor?ws.leftTab:ws.rightTab;
            if(tab==null||!tab.isModified)continue;
            try{if(projectManager!=null&&projectManager.isEmbeddedFile(tab.file))com.ccs.wiredroid.wire.RecoveryStore.checkpoint(tab.file,editor.getText().toString());}
            catch(IOException failure){Toast.makeText(this,"Не вдалося зберегти буфер для відновлення: "+failure.getMessage(),Toast.LENGTH_LONG).show();}
        }
    }
    private void restoreWireDraft(CodeEditor editor,FileTab tab) {
        if(editor==null||tab==null||tab.file==null||projectManager==null)return;
        try {
            String storageNotice=projectManager.consumeRecoveryNotice();if(storageNotice!=null)Toast.makeText(this,storageNotice,Toast.LENGTH_LONG).show();
            if(!projectManager.isEmbeddedFile(tab.file))return;
            String draft=com.ccs.wiredroid.wire.RecoveryStore.readDraft(tab.file,2*1024*1024);
            if(draft==null)return;
            if(draft.equals(editor.getText().toString())){com.ccs.wiredroid.wire.RecoveryStore.discardDraft(tab.file);return;}
            boolean previous=ws.isProgrammaticChange;ws.isProgrammaticChange=true;
            try{editor.setText(draft);}finally{ws.isProgrammaticChange=previous;}
            tab.recoveredDraft=true;int index=ws.tabsFor(editor).getTabs().indexOf(tab);if(index>=0)ws.tabsFor(editor).markModified(index,true);
            if(wireDraftNotified.add(tab.file.getCanonicalPath()))Toast.makeText(this,"Відновлено незбережений буфер "+tab.file.getName()+". Натисніть «Зберегти», щоб записати його у файл.",Toast.LENGTH_LONG).show();
        }catch(IOException failure){Toast.makeText(this,"Буфер відновлення недоступний: "+failure.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void openWireDiagnostic(File file,int line,int column) {
        File target=existingWireTabFile(file);openFile(target);CodeEditor editor=ws.activeEditor;if(editor==null)return;
        editor.post(()->{
            FileTab tab=ws.activeTab();if(tab==null||!tab.file.equals(target))return;
            int targetLine=Math.max(0,Math.min(line-1,editor.getText().getLineCount()-1));
            int targetColumn=Math.max(0,Math.min(column-1,editor.getText().getColumnCount(targetLine)));
            editor.setSelection(targetLine,targetColumn);editor.requestFocus();
        });
    }
    private File existingWireTabFile(File file) {
        try{File canonical=file.getCanonicalFile();for(FileTab tab:ws.allTabs())if(tab.file!=null&&tab.file.getCanonicalFile().equals(canonical))return tab.file;}
        catch(IOException ignored){}return file;
    }

    // ══════════════════════════════════════════════════════════
    //  Editor chrome — Git gutter + breadcrumbs
    // ══════════════════════════════════════════════════════════

    private void setupEditorChrome() {
        gitGutter = new GitGutterController(this, theme);
        gitGutter.bind(ws.editor, gitGutterOverlay1);
        gitGutter.bind(ws.editor2, gitGutterOverlay2);

        if (breadcrumbBar != null) {
            breadcrumbBar.applyTheme(theme);
            breadcrumbBar.setListener(new BreadcrumbBar.Listener() {
                @Override
                public void onBreadcrumbOpenFile(File file) {
                    if (file != null && file.isFile()) openFile(file);
                }

                @Override
                public void onBreadcrumbJumpToLine(int line) {
                    jumpToEditorLine(line);
                }
            });
        }
    }

    /** Moves the caret to a 0-based line without disturbing anything else. */
    private void jumpToEditorLine(int line0) {
        if (ws.activeEditor == null) return;
        try {
            final CodeEditor editor = ws.activeEditor;
            int max = Math.max(0, editor.getText().getLineCount() - 1);
            final int line = Math.max(0, Math.min(line0, max));

            // Selected whole, then collapsed a moment later. Landing on a line
            // after clicking an error in a log is disorienting when nothing
            // moves visibly; half a second of the line standing out says where
            // you arrived, and needs no drawing code of its own.
            int length = editor.getText().getColumnCount(line);
            editor.setSelectionRegion(line, 0, line, length);
            editor.ensurePositionVisible(line, 0, true);
            editor.requestFocus();
            editor.postDelayed(() -> {
                try {
                    if (editor.getText() != null
                            && editor.getText().getCursor().getLeftLine() == line) {
                        editor.setSelection(line, 0);
                    }
                } catch (Exception ignored) {}
            }, 600);
        } catch (Exception ignored) {}
    }

    /**
     * Repoints the gutter and the breadcrumb bar at whatever each editor now
     * holds. Called after any tab open, switch or close.
     */
    private void updateEditorChrome() {
        if (gitGutter != null) {
            gitGutter.setFile(ws.editor, diffableFile(ws.leftTab));
            gitGutter.setFile(ws.editor2, diffableFile(ws.rightTab));
        }
        FileTab active = ws.activeTab();
        File root = projectManager != null ? projectManager.getProjectDir() : null;
        File activeFile = active != null ? active.file : null;
        if (breadcrumbBar != null) {
            breadcrumbBar.setFile(activeFile, root);
        }
        if (filePathBar != null) {
            filePathBar.setFile(activeFile, root);
        }
        if (problemsAdapter != null) {
            problemsAdapter.setScopeFile(activeFile);
            updateProblemsChrome();
        }
        // The TODO panel follows the same file when it is scoped to one.
        if (todoManager != null) todoManager.onCurrentFileChanged();
        updateLineSeparatorStatus();
        scheduleOutlineScan(0);
    }

    /** A .class tab shows bytecode, not the buffer — there is nothing to diff. */
    private static File diffableFile(FileTab tab) {
        if (tab == null || tab.file == null || tab.isClassFile()) return null;
        return tab.file;
    }

    /**
     * Queues a brace-depth outline scan for the active file.
     *
     * <p>The scan itself runs on {@link #outlineWorker}; only the buffer
     * snapshot is taken here, so typing in a large file never waits on it.</p>
     */
    private void scheduleOutlineScan(long delayMs) {
        if (breadcrumbBar == null) return;
        if (pendingOutlineScan != null) {
            breadcrumbBar.removeCallbacks(pendingOutlineScan);
            pendingOutlineScan = null;
        }
        FileTab tab = ws.activeTab();
        final File file = (tab != null && !tab.isClassFile()) ? tab.file : null;
        if (file == null
                || !MemberOutline.supports(file.getName().toLowerCase(Locale.ROOT))) {
            breadcrumbBar.setMembers(null);
            return;
        }
        Runnable task = () -> {
            pendingOutlineScan = null;
            runOutlineScan(file);
        };
        pendingOutlineScan = task;
        breadcrumbBar.postDelayed(task, Math.max(0L, delayMs));
    }

    private void runOutlineScan(File file) {
        if (ws.activeEditor == null || breadcrumbBar == null) return;
        final String snapshot;
        try {
            snapshot = ws.activeEditor.getText().toString();
        } catch (Throwable ignored) {
            return;
        }
        final String nameLower = file.getName().toLowerCase(Locale.ROOT);
        final int generation = outlineGeneration.incrementAndGet();
        outlineWorker.execute(() -> {
            final java.util.List<MemberOutline.Member> members =
                    MemberOutline.scan(snapshot, nameLower);
            runOnUiThread(() -> {
                if (outlineGeneration.get() != generation) return;
                if (breadcrumbBar == null) return;
                breadcrumbBar.setMembers(members);
                if (ws.activeEditor != null) {
                    try {
                        breadcrumbBar.setCaretLine(ws.activeEditor.getCursor().getLeftLine());
                    } catch (Exception ignored) {}
                }
            });
        });
    }

    private void setupEditor() {
        statusLineCol.setText(getString(R.string.status_line, 1, 1));
        setupEditorChrome();
        configureEditor(ws.editor);
        configureEditor(ws.editor2);
        updateActiveEditorBorders();

        // Setup minimaps — вимикається в режимі енергозбереження та портретному режимі
        boolean minimapEnabled = isMinimapAllowed();
        if (ws.minimapView1 != null) {
            ws.minimapView1.setVisibility(minimapEnabled ? View.VISIBLE : View.GONE);
            ws.minimapView1.setEditor(ws.editor);
        }
        if (ws.minimapView2 != null) {
            ws.minimapView2.setEditor(ws.editor2);
        }

        // Initialize Voice-to-Text
        voiceToText = new com.ccs.wiredroid.util.VoiceToTextManager(this);
        voiceToText.setCallback(new com.ccs.wiredroid.util.VoiceToTextManager.Callback() {
            @Override
            public void onResult(String text) {
                if (ws.activeEditor != null && text != null) {
                    ws.activeEditor.insertText(text, 1);
                }
            }

            @Override
            public void onError(String error) {
                Toast.makeText(MainActivity.this, "🎤 " + error, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onPartialResult(String partial) {
                // Show partial result in status bar
                if (statusLineCol != null && partial != null) {
                    statusLineCol.setText("🎤 " + partial);
                }
            }
        });
    }



    private void setupBottomTabs() {
        if (profilerManager != null) profilerManager.bind();

        panelController = new BottomPanelController(theme, hiddenPanelsFromPrefs());

        panelController.register(BottomPanel.RUN, tabRun,
                visible -> {
                    if (consoleManager != null) consoleManager.setVisible(visible);
                });
        panelController.register(BottomPanel.PROBLEMS, tabProblems, visible -> {
            if (visible) {
                ensureProblemsPanel();
                refreshProblemsRuntimeNote();
            }
            if (problemsPanel != null) {
                problemsPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        });
        panelController.register(BottomPanel.BYTECODE, tabBytecode, bytecodeManager);
        panelController.register(BottomPanel.DEBUG, tabDebug, visible -> {
            if (debugCoordinator != null) debugCoordinator.setDebuggerPanelVisible(visible);
        });
        panelController.register(BottomPanel.DEBUG_CONSOLE, tabDebugConsole, visible -> {
            if (debugCoordinator != null) debugCoordinator.setDebugConsoleVisible(visible);
        });
        panelController.register(BottomPanel.CALL_GRAPH, tabCallGraph,
                new BottomPanelController.Binding() {
                    @Override public void setVisible(boolean visible) {
                        if (callGraphManager != null) callGraphManager.setVisibility(visible);
                    }
                    @Override public void onShown() {
                        if (callGraphManager != null) callGraphManager.refresh();
                    }
                });
        panelController.register(BottomPanel.BOOKMARKS, tabBookmarks,
                new BottomPanelController.Binding() {
                    @Override public void setVisible(boolean visible) {
                        if (bookmarkController != null) bookmarkController.setVisibility(visible);
                    }
                    @Override public void onShown() {
                        if (bookmarkController != null) bookmarkController.refreshList();
                    }
                });
        if (depsManager != null) {
            panelController.register(BottomPanel.DEPS, depsManager.getTab(),
                    new BottomPanelController.Binding() {
                        @Override public void setVisible(boolean visible) {
                            depsManager.setVisibility(visible);
                        }
                        @Override public void onShown() { depsManager.refresh(); }
                        @Override public boolean styleTab(boolean active, AppTheme theme, int activeBg) {
                            depsManager.updateTabStyle(active, theme, activeBg);
                            return true;
                        }
                    });
        }
        if (profilerManager != null) {
            panelController.register(BottomPanel.PROFILER, profilerManager.getTab(),
                    new BottomPanelController.Binding() {
                        @Override public void setVisible(boolean visible) {
                            profilerManager.setVisibility(visible);
                        }
                        @Override public boolean styleTab(boolean active, AppTheme theme, int activeBg) {
                            profilerManager.updateTabStyle(active, theme, activeBg);
                            return true;
                        }
                    });
        }
        if (todoManager != null) {
            panelController.register(BottomPanel.TODO, todoManager.getTab(),
                    new BottomPanelController.Binding() {
                        @Override public void setVisible(boolean visible) {
                            todoManager.setVisibility(visible);
                        }
                        @Override public void onShown() { todoManager.refresh(); }
                        @Override public boolean styleTab(boolean active, AppTheme theme, int activeBg) {
                            todoManager.updateTabStyle(active, theme, activeBg);
                            return true;
                        }
                    });
        }
        panelController.register(BottomPanel.CONSOLE, tabConsole, visible -> {
            if (jshellManager != null) {
                jshellManager.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        });

        if (liveMetricsToggle != null) {
            liveMetricsToggle.setChecked(appPrefs.isRunMetricsVisible());
            liveMetricsToggle.setOnCheckedChangeListener((button, enabled) -> {
                appPrefs.setRunMetricsVisible(enabled);
                if (liveMetrics != null) {
                    if (!enabled) {
                        liveMetrics.stop();
                    } else if (isRunning && !liveMetrics.isSampling()) {
                        liveMetrics.applyTheme(theme);
                        liveMetrics.setPeriodMs(powerSaving.getLiveMetricsIntervalMs());
                        liveMetrics.start();
                    }
                }
                refreshLiveMetricsVisibility();
                if (consoleManager != null) consoleManager.positionScrollEndButton();
            });
        }

        panelController.setOnPanelChanged(this::onBottomPanelChanged);
        panelController.applyOrder(appPrefs.getPanelOrder());
        panelController.select(BottomPanel.RUN);
    }

    /** Chrome that depends on which panel is showing. */
    private void onBottomPanelChanged(BottomPanel panel) {
        bottomPanelMode = panel.mode;

        if (liveMetricsToggle != null) {
            liveMetricsToggle.setVisibility(panel == BottomPanel.RUN ? View.VISIBLE : View.GONE);
            liveMetricsToggle.setTextColor(theme.textDim);
        }
        refreshLiveMetricsVisibility();

        if (btnClearConsole != null) {
            btnClearConsole.setVisibility(
                    (panel == BottomPanel.RUN || panel == BottomPanel.CONSOLE)
                            ? View.VISIBLE : View.GONE);
            ((TextView) btnClearConsole).setTextColor(theme.textDim);
        }
        if (btnCopyPanel != null) {
            boolean copyable = panel == BottomPanel.RUN || panel == BottomPanel.CONSOLE
                    || panel == BottomPanel.TODO || panel == BottomPanel.PROBLEMS;
            btnCopyPanel.setVisibility(copyable ? View.VISIBLE : View.GONE);
            ((TextView) btnCopyPanel).setTextColor(theme.textDim);
        }
        if (consoleManager != null) consoleManager.refreshScrollEndButton();
    }

    private void setupConsoleDivider() {
        if (consoleManager != null) {
            consoleManager.setupConsoleDivider(editorsContainer, bottomPanelContent);
        }
    }

    /** Lets the seam between the two editor panes be dragged. */
    private void setupSplitDivider() {
        if (ws.editorDivider == null || editorsContainer == null
                || ws.wrapperEditor1 == null || ws.wrapperEditor2 == null) {
            return;
        }
        splitDivider = new SplitDividerController(
                ws.editorDivider, ws.editorDividerLine, ws.editorDividerGrip,
                editorsContainer,
                ws.wrapperEditor1, ws.wrapperEditor2,
                tabsGroup1, tabsGroup2,
                new SplitDividerController.RatioStore() {
                    @Override public float getSplitRatio() { return appPrefs.getSplitRatio(); }
                    @Override public void setSplitRatio(float ratio) { appPrefs.setSplitRatio(ratio); }
                });
        if (theme != null) {
            splitDivider.applyTheme(theme.separator, theme.textDim, theme.accent);
        }
    }

    private void refreshBottomTabColors() {
        if (panelController == null) return;
        panelController.applyTheme(theme);
        panelController.setHidden(hiddenPanelsFromPrefs());
        panelController.applyOrder(appPrefs.getPanelOrder());
        onBottomPanelChanged(panelController.active());
    }

    private java.util.Set<BottomPanel> hiddenPanelsFromPrefs() {
        java.util.Set<BottomPanel> hidden = new java.util.HashSet<>();
        for (String key : appPrefs.getHiddenPanels()) {
            BottomPanel panel = BottomPanel.byKey(key);
            if (panel != null) hidden.add(panel);
        }
        hidden.addAll(java.util.Arrays.asList(BottomPanel.BYTECODE, BottomPanel.DEBUG,
                BottomPanel.DEBUG_CONSOLE, BottomPanel.CALL_GRAPH, BottomPanel.DEPS, BottomPanel.PROFILER, BottomPanel.CONSOLE));
        return hidden;
    }

    private void switchBottomPanel(int mode) {
        if (panelController != null) panelController.select(mode);
        if (consoleManager != null) consoleManager.positionScrollEndButton();
    }


    // ══════════════════════════════════════════════════════════
    //  Debug
    // ══════════════════════════════════════════════════════════

    /** Шукає файл за іменем у проєктній директорії (рекурсивно). */
    private File findFileInProject(String name) {
        if (projectManager == null || projectManager.getProjectDir() == null) return null;
        File root = projectManager.getProjectDir();
        java.util.Deque<File> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            File d = stack.pop();
            File[] kids = d.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                if (k.isDirectory()) stack.push(k);
                else if (k.getName().equals(name)) return k;
            }
        }
        return null;
    }

    private void refreshBreakpointMarkers() {
        if (debugCoordinator != null) debugCoordinator.refreshBreakpointMarkers();
    }

    private void toggleBreakpointAtCursor() {
        if (debugCoordinator != null) debugCoordinator.toggleBreakpointAtCursor();
    }

    // ══════════════════════════════════════════════════════════
    //  Bookmarks & Voice Input
    // ══════════════════════════════════════════════════════════

    private void toggleVoiceInput() {
        if (voiceToText == null) {
            Toast.makeText(this, "Voice input not initialized", Toast.LENGTH_SHORT).show();
            return;
        }

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 1001);
            return;
        }

        if (voiceToText.isListening()) {
            voiceToText.stopListening();
            Toast.makeText(this, "🎤 Stopped", Toast.LENGTH_SHORT).show();
            if (statusLineCol != null) {
                int ln = ws.activeEditor != null ? ws.activeEditor.getCursor().getLeftLine() + 1 : 1;
                int col = ws.activeEditor != null ? ws.activeEditor.getCursor().getLeftColumn() + 1 : 1;
                statusLineCol.setText(getString(R.string.status_line, ln, col));
            }
        } else {
            voiceToText.startListening();
            Toast.makeText(this, "🎤 Listening... Speak now", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleBookmarkAtCursor() {
        if (ws.activeEditor == null) return;
        FileTab tab = ws.activeTab();
        if (tab == null || tab.file == null) return;
        int line = ws.activeEditor.getCursor().getLeftLine() + 1;
        toggleBookmarkAtLine(line);
    }

    private void toggleBookmarkAtLine(int line) {
        FileTab tab = ws.activeTab();
        if (tab == null || tab.file == null) return;
        BookmarkManager bm = BookmarkManager.getInstance(this);
        bm.toggleBookmark(tab.file.getAbsolutePath(), line);
        boolean has = bm.hasBookmark(tab.file.getAbsolutePath(), line);
        Toast.makeText(this, has ? getString(R.string.bookmark_set, line) : getString(R.string.bookmark_removed, line),
                Toast.LENGTH_SHORT).show();
        refreshBookmarkMarkers();
        if (bottomPanelMode == PANEL_BOOKMARKS) {
            if (bookmarkController != null) bookmarkController.refreshList();
        }
    }

    private void refreshBookmarkMarkers() {
        FileTab tab = ws.activeTab();
        if (tab == null || tab.file == null) return;
        BookmarkManager bm = BookmarkManager.getInstance(this);
        java.util.Set<Integer> lines = bm.getBookmarks(tab.file.getAbsolutePath());
        BookmarkOverlay ov = (ws.activeEditor == ws.editor) ? ws.bookmarkOverlay1 : ws.bookmarkOverlay2;
        if (ov != null) ov.setBookmarks(lines);
    }

    private void showBookmarksDialog() {
        switchBottomPanel(PANEL_BOOKMARKS);
        if (bookmarkController != null) bookmarkController.refreshList();
    }

    // ══════════════════════════════════════════════════════════
    //  Debug & Bytecode runners
    // ══════════════════════════════════════════════════════════

    private void startDebug() {
        if (isRunning || (debugCoordinator != null && debugCoordinator.isDebugging())) return;
        FileTab activeTab = ws.tabs().getActiveTab();
        if (activeTab == null) {
            Toast.makeText(this, R.string.toast_no_file_open, Toast.LENGTH_SHORT).show();
            return;
        }

        saveCurrentToActiveTab();
        if (!activeTab.file.getName().endsWith(".java")) {
            Toast.makeText(this, R.string.debug_java_only, Toast.LENGTH_SHORT).show();
            return;
        }

        setRunning(true);
        if (debugCoordinator != null) {
            debugCoordinator.clearDebugConsole();
            debugCoordinator.showDebugTabs(true);
            debugCoordinator.appendDebugConsole(getString(R.string.debug_starting), 0xFF499C54);
            debugCoordinator.appendDebugConsole(getString(R.string.debug_file_label, activeTab.file.getName()), 0xFF888888);
            debugCoordinator.appendDebugConsole(getString(R.string.debug_breakpoints_label, DebuggerController.getInstance().getBreakpoints().size()), 0xFF888888);
        }
        switchBottomPanel(PANEL_DEBUG_CONSOLE);

        String source = ws.activeEditor.getText().toString();

        ProjectCompiler.debugSingleSource(this, source, activeTab.file, projectManager.getProjectDir(),
                new ProjectCompiler.Callback() {
                    @Override
                    public void onProgress(String msg) {
                        if (debugCoordinator != null) debugCoordinator.appendDebugConsole("   " + msg, 0xFF888888);
                    }

                    @Override
                    public void onResult(String output) {
                        if (output != null && output.startsWith("DEBUG_SESSION:")) {
                            String[] parts = output.split(":");
                            if (parts.length >= 4) {
                                String className = parts[1];
                                File classDir = new File(parts[2]);
                                File dexDir = new File(parts[3]);
                                File jniLibsDir = parts.length >= 5 && !parts[4].isEmpty()
                                        ? new File(parts[4]) : null;

                                if (debugCoordinator != null) {
                                    debugCoordinator.appendDebugConsole("Class: " + className, 0xFF888888);
                                    debugCoordinator.appendDebugConsole("ClassDir: " + classDir.getAbsolutePath(), 0xFF888888);
                                    debugCoordinator.appendDebugConsole("DexDir: " + dexDir.getAbsolutePath(), 0xFF888888);
                                    if (jniLibsDir != null) {
                                        debugCoordinator.appendDebugConsole("JniLibs: " + jniLibsDir.getAbsolutePath(), 0xFF888888);
                                    }
                                }

                                DebuggerController ctrl = DebuggerController.getInstance();
                                ctrl.startDebug(source, className, classDir, dexDir,
                                        null, null, MainActivity.this,
                                        MainActivity.this.getClassLoader(), jniLibsDir);

                                new Thread(() -> {
                                    ProjectCompiler.debugRunDex(
                                            MainActivity.this, className, dexDir, classDir, jniLibsDir,
                                            new ProjectCompiler.Callback() {
                                                @Override
                                                public void onProgress(String msg) {
                                                    runOnUiThread(() -> {
                                                        if (debugCoordinator != null) debugCoordinator.appendDebugConsole("   " + msg, 0xFF888888);
                                                    });
                                                }

                                                @Override
                                                public void onResult(String output) {
                                                    runOnUiThread(() -> {
                                                        ctrl.stopDebug();
                                                        setRunning(false);
                                                        if (debugCoordinator != null) {
                                                            debugCoordinator.appendDebugConsole("", 0xFF888888);
                                                            debugCoordinator.appendDebugConsole("──────────── Output ────────────", 0xFF499C54);
                                                            boolean err = output != null
                                                                    && (output.startsWith("Compilation Error")
                                                                    || output.startsWith("Execution Exception")
                                                                    || output.startsWith("System Error")
                                                                    || output.startsWith("Error:"));
                                                            debugCoordinator.appendDebugConsole(
                                                                    output != null ? output.trim() : "",
                                                                    err ? 0xFFCF4444 : 0xFFAAAAAA);
                                                        }
                                                    });
                                                }

                                                @Override
                                                public void onProblems(List<ProblemItem> problems) {}
                                            });
                                }, "DebugRunner").start();
                            }
                        } else {
                            setRunning(false);
                            if (debugCoordinator != null) {
                                debugCoordinator.appendDebugConsole("", 0xFF888888);
                                debugCoordinator.appendDebugConsole("──────────── Output ────────────", 0xFF499C54);
                                boolean err = output != null && (output.startsWith("Compilation Error")
                                        || output.startsWith("Execution Exception")
                                        || output.startsWith("System Error")
                                        || output.startsWith("Error:"));
                                debugCoordinator.appendDebugConsole(output != null ? output.trim() : "",
                                        err ? 0xFFCF4444 : 0xFFAAAAAA);
                            }
                        }
                    }

                    @Override
                    public void onProblems(List<ProblemItem> problems) {
                        problemsAdapter.setItems(problems);
                    }
                });
    }

    private void stopDebug() {
        if (debugCoordinator != null) debugCoordinator.stopDebug();
    }

    private void refreshBytecodePanel() {
        if (bytecodeManager != null) bytecodeManager.refresh();
    }





    /** One checkbox in the Problems filter row. */
    private static final class SeverityFilter {
        final int viewId;
        final ProblemItem.Severity severity;
        final int labelRes;

        SeverityFilter(int viewId, ProblemItem.Severity severity, int labelRes) {
            this.viewId = viewId;
            this.severity = severity;
            this.labelRes = labelRes;
        }
    }

    /** The filter row, in the order it appears — worst first. */
    private static final SeverityFilter[] PROBLEM_FILTERS = {
            new SeverityFilter(R.id.filterErrors, ProblemItem.Severity.ERROR,
                    R.string.problems_filter_errors),
            new SeverityFilter(R.id.filterSecurity, ProblemItem.Severity.SECURITY,
                    R.string.problems_filter_security),
            new SeverityFilter(R.id.filterWarnings, ProblemItem.Severity.WARNING,
                    R.string.problems_filter_warnings),
            new SeverityFilter(R.id.filterInfo, ProblemItem.Severity.INFO,
                    R.string.problems_filter_info),
    };

    /**
     * Inflates the Problems panel the first time it is opened and wires its
     * severity filter.
     *
     * <p>The panel starts hidden like the others, so it waits behind a ViewStub
     * rather than being built on every cold start.</p>
     */
    private void ensureProblemsPanel() {
        if (problemsPanel != null) return;
        android.view.ViewStub stub = findViewById(R.id.stubProblemsPanel);
        if (stub != null) stub.inflate();
        problemsPanel = findViewById(R.id.problemsPanel);
        problemsRecycler = findViewById(R.id.problemsRecycler);
        problemsEmpty = findViewById(R.id.problemsEmpty);
        problemsJavaSeNote = findViewById(R.id.problemsJavaSeNote);

        if (problemsRecycler != null) {
            problemsRecycler.setLayoutManager(new LinearLayoutManager(this));
            problemsRecycler.setAdapter(problemsAdapter);
        }
        for (SeverityFilter filter : PROBLEM_FILTERS) {
            bindSeverityFilter(filter.viewId, filter.severity);
        }
        android.widget.Switch scope = findViewById(R.id.filterScopeCurrent);
        if (scope != null) {
            scope.setChecked(problemsAdapter.isScopedToCurrentFile());
            scope.setOnCheckedChangeListener((v, checked) -> {
                problemsAdapter.setScopeToCurrentFile(checked);
                appPrefs.setProblemsScopeCurrentFile(checked);
                updateProblemsChrome();
            });
        }
        // The scope has to know which file that is before the first list is drawn.
        problemsAdapter.setScopeFile(ws.activeFile());
        refreshProblemsRuntimeNote();
        applyProblemsPanelTheme();
        updateProblemsChrome();
    }

    private void refreshProblemsRuntimeNote() {
        File root = projectManager != null ? projectManager.getProjectDir() : null;
        String currentJdk = com.ccs.wiredroid.project.ProjectJdk.forOpenProject(this);
        boolean isJavaSe = com.ccs.wiredroid.project.ProjectRuntime.isJavaSe(root)
                && ("21".equals(currentJdk) || com.ccs.wiredroid.tools.compilers.JavaVersions.feature(currentJdk) >= 21);

        if (problemsJavaSeNote != null) {
            problemsJavaSeNote.setVisibility(isJavaSe ? View.VISIBLE : View.GONE);
        }
        if (debugCoordinator != null) {
            debugCoordinator.refreshJavaSeNotes(isJavaSe);
        }
    }

    private void bindSeverityFilter(int viewId, final ProblemItem.Severity severity) {
        final android.widget.CheckBox box = findViewById(viewId);
        if (box == null || problemsAdapter == null) return;
        box.setChecked(problemsAdapter.isSeverityShown(severity));
        box.setOnCheckedChangeListener((v, checked) -> {
            problemsAdapter.setSeverityShown(severity, checked);
            appPrefs.setProblemSeverityShown(severity.name(), checked);
        });
    }

    /**
     * Keeps the filter labels' counts and the empty message in step with the list.
     *
     * <p>An empty list has two quite different causes, and saying which one it is
     * matters: there may be nothing wrong, or there may be plenty wrong and all
     * of it filtered out.</p>
     */
    private void updateProblemsChrome() {
        if (problemsPanel == null || problemsAdapter == null) return;

        int total = 0;
        for (SeverityFilter filter : PROBLEM_FILTERS) {
            int count = problemsAdapter.countOf(filter.severity);
            total += count;
            android.widget.CheckBox box = findViewById(filter.viewId);
            if (box != null) box.setText(getString(filter.labelRes, count));
        }

        boolean listEmpty = problemsAdapter.getItemCount() == 0;
        if (problemsRecycler != null) {
            problemsRecycler.setVisibility(listEmpty ? View.GONE : View.VISIBLE);
        }
        if (problemsEmpty != null) {
            problemsEmpty.setVisibility(listEmpty ? View.VISIBLE : View.GONE);
            problemsEmpty.setText(total > 0
                    ? R.string.problems_all_filtered
                    : R.string.problems_none);
        }
    }

    private void applyProblemsPanelTheme() {
        if (problemsPanel == null) return;
        problemsPanel.setBackgroundColor(theme.consoleBg);
        View filterBar = findViewById(R.id.problemsFilterScroll);
        if (filterBar != null) filterBar.setBackgroundColor(theme.toolbar);
        if (problemsEmpty != null) problemsEmpty.setTextColor(theme.textDim);
        if (problemsRecycler != null) problemsRecycler.setBackgroundColor(theme.consoleBg);
        View filterSep = findViewById(R.id.problemsFilterSep);
        if (filterSep != null) filterSep.setBackgroundColor(theme.separator);
        View divider = findViewById(R.id.problemsDivider);
        if (divider != null) divider.setBackgroundColor(theme.separator);
        if (problemsJavaSeNote != null) {
            problemsJavaSeNote.setTextColor(theme.text);
            problemsJavaSeNote.setBackgroundColor(
                    Colors.blend(theme.consoleBg, theme.accent, 0.12f));
        }
        for (SeverityFilter filter : PROBLEM_FILTERS) {
            android.widget.CheckBox box = findViewById(filter.viewId);
            if (box != null) box.setTextColor(theme.text);
        }
        android.widget.Switch scope = findViewById(R.id.filterScopeCurrent);
        if (scope != null) scope.setTextColor(theme.text);
        if (problemsAdapter != null) problemsAdapter.setTheme(theme);
    }

    private void setupProblemsList() {
        problemsAdapter = new ProblemsAdapter(this);
        // The severities the user last chose, restored before anything is shown
        // so the first list is already filtered the way they left it.
        for (ProblemItem.Severity sev : ProblemItem.Severity.values()) {
            problemsAdapter.setSeverityShown(sev,
                    appPrefs.isProblemSeverityShown(sev.name()));
        }
        problemsAdapter.setScopeToCurrentFile(appPrefs.isProblemsScopeCurrentFile());
        problemsAdapter.registerAdapterDataObserver(
                new RecyclerView.AdapterDataObserver() {
                    @Override public void onChanged() { updateProblemsChrome(); }
                });
        problemsAdapter.setListener(item -> {
            if (item.file == null || !item.file.exists()) return;
            openFile(item.file);
            // The panel stays on Problems. It used to jump to the console on
            // every tap, which meant losing your place in the list each time you
            // looked at a finding — and with a list this long, that is the whole
            // list.
            final int line0 = Math.max(0, item.line - 1);
            // Posted to the main thread rather than to a particular editor:
            // opening a file can change which editor is active, and the old code
            // queued the caret move on whichever one happened to be active
            // beforehand, so in split view it landed in the wrong one.
                new Handler(Looper.getMainLooper()).postDelayed(
                    () -> jumpToEditorLine(line0), 120);
        });
    }

    private void applyEditorLanguage(File file) {
        applyEditorLanguage(file, ws.activeEditor);
    }

    private void applyEditorLanguage(File file, CodeEditor ed) {
        if (ed == null) return;
        // Past a couple of megabytes the file is not source any more — a log, a
        // dataset, something generated — and highlighting it costs a freeze for
        // colour nobody reads. Plain text opens instantly at any size.
        if (com.ccs.wiredroid.editor.LargeFilePolicy.isPlain(file)) {
            ed.setEditorLanguage(new io.github.rosemoe.sora.lang.EmptyLanguage());
            return;
        }
        if (file != null) {
            String name = file.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith(".ino") || name.endsWith(".cpp") || name.endsWith(".c") || name.endsWith(".h") 
                    || name.endsWith(".hpp") || name.endsWith(".cc") || name.endsWith(".cxx")) {
                ed.setEditorLanguage(new CppLanguage());
                return;
            } else if (name.endsWith(".xml") || name.endsWith(".html") || name.endsWith(".htm")
                    || name.endsWith(".svg")) {
                ed.setEditorLanguage(new XmlLanguage());
                return;
            } else if (name.endsWith(".css")) {
                ed.setEditorLanguage(new CssLanguage());
                return;
            } else if (name.endsWith(".js")) {
                ed.setEditorLanguage(new JavaScriptLanguage());
                return;
            } else if (name.endsWith(".sql")) {
                ed.setEditorLanguage(new SqlLanguage());
                return;
            } else if (name.endsWith(".gradle") || name.endsWith(".gradle.kts")) {
                // Checked before .kts/.kt so Kotlin-DSL build scripts get the
                // Gradle highlighter rather than the plain Kotlin one.
                ed.setEditorLanguage(new GradleLanguage());
                return;
            } else if (name.endsWith(".properties")) {
                ed.setEditorLanguage(new com.ccs.wiredroid.util.languages.PropertiesLanguage());
                return;
            } else if (name.endsWith(".json")) {
                ed.setEditorLanguage(new JsonLanguage());
                return;
            } else if (name.endsWith(".sh") || name.endsWith(".bash")) {
                ed.setEditorLanguage(new BashLanguage());
                return;
            } else if (name.endsWith(".kt") || name.endsWith(".kts")) {
                ed.setEditorLanguage(new KotlinLanguage());
                return;
            } else if (name.endsWith(".md") || name.endsWith(".markdown")) {
                ed.setEditorLanguage(new MarkdownLanguage());
                return;
            }
            // Checked after .gradle above, so a build script keeps the DSL
            // highlighter rather than being treated as plain Groovy.
            io.github.rosemoe.sora.lang.Language extra =
                    com.ccs.wiredroid.util.languages.LanguageFiles.languageFor(name);
            if (extra != null) {
                ed.setEditorLanguage(extra);
                return;
            }
        }
        ed.setEditorLanguage(new io.github.rosemoe.sora.lang.EmptyLanguage());
    }

    private io.github.rosemoe.sora.lang.Language resolveJavaLanguage() {
        File projectRoot = projectManager != null ? projectManager.getProjectDir() : null;
        // One question, asked of the one place that knows the answer. The three
        // branches this replaced each decided for themselves, and the middle one
        // refused semantic highlighting on low battery even when the power-saving
        // profile said to keep it.
        boolean ast = powerSaving != null
                ? powerSaving.shouldUseAstHighlighting()
                : appPrefs != null && appPrefs.isAstHighlighting();
        return ast
                ? new com.ccs.wiredroid.util.languages.AstJavaLanguage(this, projectRoot)
                : new WireDroidLanguage(this, projectRoot);
    }

    private void setupProject(boolean isRestoringState) {
        com.ccs.wiredroid.util.StartupTrace.Phases sp =
                com.ccs.wiredroid.util.StartupTrace.phases("setupProject");
        projectManager = new ProjectManager(this);

        String saved = prefs.getString("project_root", null);
        File root = null;
        if (saved != null) {
            File t = new File(saved);
            if (t.isDirectory()) root = t;
        }
        if (root == null) {
            File candidate = MavenPaths.projectDir(this, "MyCircuit");
            if (com.ccs.wiredroid.wire.WireProject.isCircuit(candidate)) root = candidate;
            else {
                try {root = com.ccs.wiredroid.wire.WireProject.create(this, candidate.exists() ? "MyCircuit_" + System.currentTimeMillis() : "MyCircuit");}
                catch (Exception e) {
                    root = new File(getFilesDir(), "project");root.mkdirs();
                    try {com.ccs.wiredroid.wire.WireProject.initializeImported(root);} catch (IOException ignored) {}
                }
            }
            prefs.edit().putString("project_root", root.getAbsolutePath()).apply();
        }

        // Check if external storage project is accessible, if not — copy to internal
        if (root != null && isExternalPath(root) && !canReadRoot(root)) {
            String oldRootPath = root.getAbsolutePath();
            File internalRoot = copyToInternal(root);
            if (internalRoot != null) {
                root = internalRoot;
                prefs.edit().putString("project_root", root.getAbsolutePath()).apply();
                sessionState.copy(oldRootPath, root.getAbsolutePath());
                sessionState.clear(oldRootPath);
                Toast.makeText(this, "Project copied to internal storage", Toast.LENGTH_SHORT).show();
            }
        }

        // No invalidation here on purpose: the kept sweep is keyed by project
        // root, so a different project misses the cache by itself, while
        // reopening the same one reuses a sweep that is still perfectly good.
        projectManager.setProjectRoot(root);
        sp.mark("resolveRoot");
        updateToolbarTitle();
        setupToolbarProjectPathLongClick();
        refreshFileTree();
        sp.mark("refreshFileTree");

        // Always try to restore session from SharedPreferences (more reliable than savedInstanceState)
        SessionState.SavedSession session = sessionState.restore(root.getAbsolutePath());
        sp.mark("restoreSession");
        if (session != null && !session.tabPaths.isEmpty()) {
            boolean anyOpened = false;
            for (int i = 0; i < session.tabPaths.size(); i++) {
                File f = new File(session.tabPaths.get(i));
                if (f.exists() && f.canRead()) {
                    try {
                        openFile(f);
                        anyOpened = true;
                    } catch (Exception e) {
                        // Skip unreadable files
                    }
                }
            }
            // Restore active tab
            if (anyOpened && session.activeIndex >= 0
                    && session.activeIndex < ws.tabs().getTabs().size()) {
                switchTab(session.activeIndex);
            }
            // Restore cursor positions to tabs and active editor
            for (int i = 0; i < session.tabPaths.size(); i++) {
                if (i < session.cursorLines.size()) {
                    File f = new File(session.tabPaths.get(i));
                    int idx = ws.tabs().indexOfFile(f);
                    if (idx >= 0) {
                        int line = session.cursorLines.get(i) - 1;
                        int col = (i < session.cursorCols.size()) ? session.cursorCols.get(i) : 0;
                        FileTab tab = ws.tabs().getTabs().get(idx);
                        tab.cursorLine = line;
                        tab.cursorColumn = col;
                        if (idx == ws.tabs().getActiveIndex()) {
                            try {
                                ws.activeEditor.setSelection(line, col);
                            } catch (Exception e) {}
                        }
                    }
                }
            }
            // Fallback: if no files opened from session, open first file
            if (!anyOpened) {
                List<File> files = projectManager.getSourceFiles();
                sp.mark("getJavaFiles");
                if (!files.isEmpty()) {
                    openFile(files.get(0));
                }
            }
        } else if (!isRestoringState) {
            // No saved session AND not restoring from savedInstanceState — open first file or create default
            // Any language, not only Java: a Scala or Groovy project has its
            // own main, and writing a Java starter beside it was the app
            // misreading "no .java files" as "no code".
            List<File> files = projectManager.getSourceFiles();
            if (files.isEmpty()) {
                if (projectManager.hasStandardLayout()) {
                    try {
                        File pkgDir = ProjectLayoutHelper.mainJavaPackageDir(root);
                        String pkg = ProjectLayoutHelper.mainPackageName(root);
                        File app = new File(pkgDir, "App.java");
                        projectManager.writeFile(app,
                                "package " + pkg + ";\n\npublic class App {\n"
                                        + "    public static void main(String[] args) {\n"
                                        + "        System.out.println(\"Hello\");\n"
                                        + "    }\n}\n");
                        refreshFileTree();
                        openFile(app);
                    } catch (Exception e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                } else {
                    try {
                        File main = projectManager.createFile("Main", DEFAULT_CODE);
                        if (main != null) openFile(main);
                    } catch (IOException e) {
                        Toast.makeText(this, R.string.error_cannot_create_main, Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                openFile(files.get(0));
            }
        }
        sp.mark("openFiles");
        refreshProblemsMergedAsync();
        sp.done();
        // openFile() above already picked the language for whatever tab ended up
        // active. Re-applying Java unconditionally here undid that — semantic
        // highlighting was chosen correctly and then overwritten a moment later,
        // which is why the AST setting looked like it did nothing. Going through
        // applyEditorLanguage keeps the one thing this line was actually for:
        // handing the language a project root, which is only known by now.
        FileTab activeTab = ws.activeTab();
        applyEditorLanguage(activeTab != null ? activeTab.file : null, ws.activeEditor);

        // A file handed over from another app is opened last so it ends up as
        // the active tab, on top of whatever the session restored.
        openRequestedFile(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openRequestedFile(intent);
        if (wireDrawer != null && intent.getBooleanExtra("wire_show",false)) wireDrawer.show(intent.getBooleanExtra("wire_run",false));
    }

    /** Honours {@link FileOpenActivity#EXTRA_OPEN_FILE}, if present. */
    private void openRequestedFile(Intent intent) {
        if (intent == null) return;
        String path = intent.getStringExtra(FileOpenActivity.EXTRA_OPEN_FILE);
        if (path == null) return;
        // Consume it, so a rotation or a return from Settings does not reopen.
        intent.removeExtra(FileOpenActivity.EXTRA_OPEN_FILE);

        File file = new File(path);
        if (!file.isFile()) {
            Toast.makeText(this, getString(R.string.open_with_failed, path), Toast.LENGTH_LONG).show();
            return;
        }
        try {
            refreshFileTree();
            openFile(file);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.open_with_failed,
                    String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show();
        }
    }

    private void initLiveProblemsScheduler() {
        liveProblemsScheduler = new LiveProblemsScheduler(this,
                new LiveProblemsScheduler.Sources() {
                    @Override
                    public String getEditorText() {
                        return ws.activeEditor.getText() != null ? ws.activeEditor.getText().toString() : "";
                    }

                    @Override
                    public File getActiveJavaFile() {
                        FileTab t = ws.tabs().getActiveTab();
                        if (t == null || t.file == null) {
                            return null;
                        }
                        if (!t.file.getName().endsWith(".java")) {
                            return null;
                        }
                        return t.file;
                    }

                    @Override
                    public File getProjectRoot() {
                        return projectManager.getProjectDir();
                    }

                    @Override
                    public boolean shouldSkipScan() {
                        return com.ccs.wiredroid.BuildConfig.EMBEDDED_EDITION || isRunning || !powerSaving.shouldRunLiveProblems();
                    }

                    @Override
                    public boolean isPowerSavingActive() {
                        return powerSaving.isPowerSavingActive();
                    }
                },
                items -> {
                    problemsAdapter.setItems(items);
                    applyInlineDiagnostics(items);
                });
        liveProblemsScheduler.setInterval(powerSaving.getProblemsScanIntervalMs());
        if (!powerSaving.shouldRunLiveProblems()) {
            liveProblemsScheduler.stop();
        }
    }

    /** ECJ (активний файл) + static (проєкт); з диска для інших файлів. */
    /**
     * Recomputes the Problems list for the active file plus the workspace.
     *
     * <p>One pass is an ECJ compile of the open file and a static sweep of every
     * source in the project, so it is not something to run several times over.
     * This used to start a fresh thread per call from seven different places;
     * opening a project fired three at once and they piled up behind ECJ's
     * global stream lock, each waiting out the one in front.</p>
     *
     * <p>Now a single worker runs at most one pass at a time. Requests that
     * arrive while a pass is running do not queue — they replace the pending
     * input and set a flag, so what follows is one more pass with the newest
     * text rather than one pass per request. Results from an input that has
     * since been superseded are dropped instead of being shown.</p>
     */
    private void refreshProblemsMergedAsync() {
        if (com.ccs.wiredroid.BuildConfig.EMBEDDED_EDITION) return;
        if (problemsWorker.isShutdown()) return;
        final String text = ws.activeEditor != null && ws.activeEditor.getText() != null
                ? ws.activeEditor.getText().toString() : "";
        FileTab tab = ws.tabs() != null ? ws.tabs().getActiveTab() : null;
        // Snapshot on the UI thread: the editor's content must not be read from
        // the worker while the user is still typing into it.
        pendingProblemsText = text;
        pendingProblemsFile = (tab != null && tab.file != null && tab.file.getName().endsWith(".java"))
                ? tab.file
                : null;
        problemsDirty = true;
        if (problemsRunning.compareAndSet(false, true)) {
            problemsWorker.execute(problemsPass);
        }
    }

    private void publishProblems(final List<ProblemItem> items) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (problemsAdapter != null) problemsAdapter.setItems(items);
            applyInlineDiagnostics(items);
        });
    }

    /** Draws the same active-file findings directly in the editor and drives the quick-fix lightbulb. */
    /** Repaints the problem strip beside whichever editor is active. */
    private void updateErrorStripe(CodeEditor editor) {
        ErrorStripeOverlay stripe = editor == ws.editor ? errorStripe1 : errorStripe2;
        if (stripe == null) return;
        stripe.setColors(theme.errorText, 0xFFD8A02E, theme.accent);
        stripe.setProblems(activeFileProblems, Math.max(1, editor.getLineCount()));
    }

    private void applyInlineDiagnostics(List<ProblemItem> items) {
        if (ws == null || ws.activeEditor == null) return;
        CodeEditor editor = ws.activeEditor;
        DiagnosticsContainer diagnostics = new DiagnosticsContainer();
        File activeFile = ws.activeFile();
        String source = editor.getText() == null ? "" : editor.getText().toString();

        activeFileProblems.clear();
        if (activeFile != null && items != null) {
            String activePath = activeFile.getAbsolutePath();
            for (ProblemItem item : items) {
                if (item != null && item.file != null && activePath.equals(item.file.getAbsolutePath())) {
                    activeFileProblems.add(item);
                }
            }
        }

        // The strip down the right edge shows the same problems, placed by
        // proportion of the file rather than by what is currently on screen.
        updateErrorStripe(editor);

        if (activeFile != null && !source.isEmpty() && !activeFileProblems.isEmpty()) {
            int lineCount = editor.getLineCount();
            int sourceLength = source.length();
            for (ProblemItem item : activeFileProblems) {
                if (item.line < 1 || item.line > lineCount) continue;
                int line0 = item.line - 1;
                int start = lineStart(source, line0);
                if (start < 0 || start >= sourceLength) continue;
                int end = source.indexOf('\n', start);
                if (end < 0) end = sourceLength;
                while (end > start && (source.charAt(end - 1) == '\r')) end--;
                if (end <= start) end = Math.min(sourceLength, start + 1);
                short severity = item.severity == ProblemItem.Severity.ERROR
                        ? DiagnosticRegion.SEVERITY_ERROR : DiagnosticRegion.SEVERITY_WARNING;
                diagnostics.addDiagnostic(new DiagnosticRegion(start, end, severity,
                        line0, new DiagnosticDetail(item.message, item.message,
                                java.util.Collections.emptyList(), item)));
            }
        }

        if (appPrefs != null && appPrefs.isDiagnosticsUnderline()) {
            editor.setDiagnosticIndicatorStyle(DiagnosticIndicatorStyle.WAVY_LINE);
        } else {
            editor.setDiagnosticIndicatorStyle(DiagnosticIndicatorStyle.NONE);
        }
        editor.setDiagnostics(diagnostics);

        if (editor.getCursor() != null) {
            updateLightbulb(editor.getCursor().getLeftLine() + 1);
        }
    }

    private void updateLightbulb(int line1) {
        if (btnLightbulb == null) return;
        if (appPrefs == null || !appPrefs.isDiagnosticsLightbulb() || activeFileProblems.isEmpty()) {
            btnLightbulb.setVisibility(View.GONE);
            return;
        }

        ProblemItem match = null;
        for (ProblemItem p : activeFileProblems) {
            if (p != null && p.line == line1) {
                match = p;
                if (p.severity == ProblemItem.Severity.ERROR) break;
            }
        }

        if (match != null) {
            btnLightbulb.setVisibility(View.VISIBLE);
            btnLightbulb.setImageResource(R.drawable.ic_lightbulb);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(6));
            if (match.severity == ProblemItem.Severity.ERROR) {
                btnLightbulb.setColorFilter(0xFFFF5252, PorterDuff.Mode.SRC_IN);
                bg.setColor(theme != null && theme.dark ? 0x33FF5252 : 0x22FF5252);
                bg.setStroke(dp(1), 0x66FF5252);
            } else {
                btnLightbulb.setColorFilter(0xFFFFD54F, PorterDuff.Mode.SRC_IN);
                bg.setColor(theme != null && theme.dark ? 0x33FFD54F : 0x22FFD54F);
                bg.setStroke(dp(1), 0x66FFD54F);
            }
            btnLightbulb.setBackground(bg);
        } else {
            btnLightbulb.setVisibility(View.GONE);
        }
    }

    private void showQuickFixForCurrentLine() {
        if (ws == null || ws.activeEditor == null) return;
        CodeEditor editor = ws.activeEditor;
        int currentLine1 = editor.getCursor() != null ? editor.getCursor().getLeftLine() + 1 : 1;

        List<ProblemItem> lineProblems = new ArrayList<>();
        for (ProblemItem p : activeFileProblems) {
            if (p != null && p.line == currentLine1) {
                lineProblems.add(p);
            }
        }
        if (lineProblems.isEmpty() && !activeFileProblems.isEmpty()) {
            lineProblems.add(activeFileProblems.get(0));
        }
        if (lineProblems.isEmpty()) return;

        final ProblemItem problem = lineProblems.get(0);
        final File activeFile = ws.activeFile();

        // A popup at the lightbulb, not a dialog: the fix belongs next to the
        // problem, and a full-screen sheet for five one-word actions hides the
        // very line it is talking about.
        AnchoredMenu menu = AnchoredMenu.with(this, theme).minWidth(240);
        menu.custom(quickFixHeader(problem));
        menu.separator();

        String lineText = "";
        if (editor.getText() != null && currentLine1 > 0
                && currentLine1 <= editor.getText().getLineCount()) {
            lineText = editor.getText().getLineString(currentLine1 - 1);
        }
        final String fixLine = lineText;
        if (!fixLine.equals(fixLine.replaceFirst("\\s+$", ""))) {
            menu.item("✂", "Прибрати пробіли в кінці рядка", () -> {
                int line = Math.max(0, Math.min(currentLine1 - 1, editor.getText().getLineCount() - 1));
                String current = editor.getText().getLineString(line);
                int newLength = current.replaceFirst("\\s+$", "").length();
                editor.getText().delete(line, newLength, line, current.length());
                editor.setSelection(line, newLength);
            });
        }
        String duplicateImportPrefix = getString(R.string.sa_duplicate_import, "").trim();
        if (problem.message != null && problem.message.startsWith(duplicateImportPrefix)) {
            menu.item("⊖", "Видалити дубльований import", () -> {
                int line = Math.max(0, Math.min(currentLine1 - 1, editor.getText().getLineCount() - 1));
                int lastLine = editor.getText().getLineCount() - 1;
                if (line < lastLine) editor.getText().delete(line, 0, line + 1, 0);
                else if (line > 0) editor.getText().delete(line - 1, editor.getText().getColumnCount(line - 1), line, editor.getText().getColumnCount(line));
                else editor.getText().delete(line, 0, line, editor.getText().getColumnCount(line));
            });
        }

        // 1. Auto-import (for Java files)
        if (activeFile != null && activeFile.getName().endsWith(".java")) {
            menu.item("⚡", getString(R.string.quickfix_auto_import), () -> {
                autoImportBeforeRun();
                Toast.makeText(MainActivity.this, R.string.refactor_imports_organized, Toast.LENGTH_SHORT).show();
            });
        }

        // 2. Fix with AI
        menu.item("🤖", getString(R.string.quickfix_ai_fix), () -> {
            String code = editor.getText() != null ? editor.getText().toString() : "";
            String fname = activeFile != null ? activeFile.getName() : "";
            String filePath = activeFile != null ? activeFile.getAbsolutePath() : "";
            String root = projectManager != null && projectManager.getProjectDir() != null
                    ? projectManager.getProjectDir().getAbsolutePath() : "";
            String prompt = "Fix or explain this problem in " + fname + " at line " + problem.line + ":\n\n"
                    + problem.message;
            AiChatActivity.launchWithPrompt(MainActivity.this, code, fname, filePath, root, prompt);
        });

        // 3. Jump to next problem
        if (activeFileProblems.size() > 1) {
            menu.item("⏭", getString(R.string.quickfix_next_problem), () -> {
                ProblemItem next = null;
                for (ProblemItem p : activeFileProblems) {
                    if (p != null && p.line > currentLine1) {
                        next = p;
                        break;
                    }
                }
                if (next == null) next = activeFileProblems.get(0);
                if (next != null && editor.getText() != null) {
                    int targetLine0 = Math.max(0, next.line - 1);
                    int maxLine = Math.max(0, editor.getText().getLineCount() - 1);
                    int jumpLine = Math.min(targetLine0, maxLine);
                    editor.setSelection(jumpLine, 0);
                    updateLightbulb(jumpLine + 1);
                }
            });
        }

        // 4. Show in Problems panel
        menu.item("📋", getString(R.string.quickfix_show_in_problems),
                () -> switchBottomPanel(PANEL_PROBLEMS));

        // 5. Copy error message
        menu.item("📄", getString(R.string.quickfix_copy_message), () -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Problem", problem.message));
                Toast.makeText(MainActivity.this, R.string.label_copy, Toast.LENGTH_SHORT).show();
            }
        });

        menu.showBelow(btnLightbulb);
    }

    private void goToDefinition() {
        if (ws == null || ws.activeEditor == null || ws.activeFile() == null
                || !ws.activeFile().getName().endsWith(".java")) return;
        String source = ws.activeEditor.getText().toString();
        int caret = ws.activeEditor.getCursor().getLeft();
        java.util.List<com.ccs.wiredroid.util.languages.ast.JavaToken> tokens =
                new com.ccs.wiredroid.util.languages.ast.JavaLexer(source).tokenize();
        new com.ccs.wiredroid.util.languages.ast.JavaAstParser(tokens).parse();
        com.ccs.wiredroid.util.languages.ast.JavaToken target = null;
        for (com.ccs.wiredroid.util.languages.ast.JavaToken token : tokens) {
            if (token.start <= caret && caret <= token.end && token.isIdentifier()) { target = token; break; }
        }
        if (target == null) return;
        final String name = target.text;
        File root = projectManager == null ? null : projectManager.getProjectDir();
        java.util.List<File> candidates = new java.util.ArrayList<>();
        collectJavaFiles(root, candidates, 4000);
        candidates.sort((a, b) -> Boolean.compare(!a.equals(ws.activeFile()), !b.equals(ws.activeFile())));
        for (File file : candidates) {
            try {
                String text = file.equals(ws.activeFile()) ? source : projectManager.readFile(file);
                java.util.List<com.ccs.wiredroid.util.languages.ast.JavaToken> ts =
                        new com.ccs.wiredroid.util.languages.ast.JavaLexer(text).tokenize();
                new com.ccs.wiredroid.util.languages.ast.JavaAstParser(ts).parse();
                for (int i = 0; i < ts.size(); i++) {
                    com.ccs.wiredroid.util.languages.ast.JavaToken t = ts.get(i);
                    if (!name.equals(t.text) || t.kind != com.ccs.wiredroid.util.languages.ast.JavaToken.Kind.IDENTIFIER) continue;
                    boolean declaration = t.role == com.ccs.wiredroid.util.languages.ast.SemanticRole.TYPE
                            || (i + 1 < ts.size() && ts.get(i + 1).is("(")
                            && t.role == com.ccs.wiredroid.util.languages.ast.SemanticRole.METHOD);
                    if (!declaration || (file.equals(ws.activeFile()) && t.start == target.start)) continue;
                    openFile(file);
                    ws.activeEditor.setSelection(t.line, t.column);
                    return;
                }
            } catch (Exception ignored) { }
        }
        Toast.makeText(this, "Визначення не знайдено", Toast.LENGTH_SHORT).show();
    }

    private void collectJavaFiles(File dir, java.util.List<File> out, int limit) {
        if (dir == null || !dir.isDirectory() || out.size() >= limit) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (out.size() >= limit) return;
            if (child.isDirectory()) {
                if (!child.getName().equals(".git") && !child.getName().equals("build")
                        && !child.getName().equals(".gradle")) collectJavaFiles(child, out, limit);
            } else if (child.getName().endsWith(".java")) out.add(child);
        }
    }

    /**
     * The block at the top of the quick-fix popup: which problem this is about.
     *
     * <p>A menu title would not do — it is one ellipsized line, and a compiler
     * message is rarely one line. This wraps to four and stops, wide enough to
     * read and narrow enough that the popup stays a popup.</p>
     */
    private View quickFixHeader(ProblemItem problem) {
        int dim = theme != null ? theme.textDim : 0xFF808080;
        int text = theme != null ? theme.text : 0xFFDFE1E5;
        int severityColor;
        int severityLabel;
        if (problem.severity == ProblemItem.Severity.ERROR
                || problem.severity == ProblemItem.Severity.SECURITY) {
            severityColor = 0xFFFF5252;
            severityLabel = R.string.severity_error;
        } else if (problem.severity == ProblemItem.Severity.WARNING) {
            severityColor = 0xFFFFD54F;
            severityLabel = R.string.severity_warning;
        } else {
            severityColor = 0xFF5C9BD6;
            severityLabel = R.string.severity_info;
        }

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        int maxWidth = Math.min((int) (300 * density),
                getResources().getDisplayMetrics().widthPixels - (int) (48 * density));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, (int) (10 * density), pad, (int) (10 * density));

        TextView where = new TextView(this);
        where.setText(getString(R.string.quickfix_problem_at_line,
                problem.line, getString(severityLabel)));
        where.setTextSize(11);
        where.setTextColor(severityColor);
        where.setSingleLine(true);
        box.addView(where);

        TextView message = new TextView(this);
        message.setText(problem.message);
        message.setTextSize(13);
        message.setTextColor(text);
        message.setMaxLines(4);
        message.setEllipsize(android.text.TextUtils.TruncateAt.END);
        message.setMaxWidth(maxWidth);
        message.setPadding(0, (int) (3 * density), 0, 0);
        box.addView(message);

        if (activeFileProblems.size() > 1) {
            TextView more = new TextView(this);
            more.setText(getString(R.string.quickfix_more_problems, activeFileProblems.size() - 1));
            more.setTextSize(11);
            more.setTextColor(dim);
            more.setPadding(0, (int) (4 * density), 0, 0);
            box.addView(more);
        }
        return box;
    }

    private static int lineStart(String source, int line) {
        if (line < 0) return -1;
        int start = 0;
        for (int i = 0; i < line; i++) {
            int newline = source.indexOf('\n', start);
            if (newline < 0) return -1;
            start = newline + 1;
        }
        return start;
    }

    private final Runnable problemsPass = new Runnable() {
        @Override
        public void run() {
            while (true) {
                problemsDirty = false;
                String text = pendingProblemsText;
                File active = pendingProblemsFile;
                File root = projectManager != null ? projectManager.getProjectDir() : null;

                // Partial results are shown as they arrive; a first sweep of a
                // large project takes long enough that waiting for the end looks
                // like nothing is happening. The sweep is only abandoned when the
                // screen is going away — not merely because a newer request is
                // queued, because then a busy first second would keep restarting
                // it and it would never finish or get cached.
                final List<ProblemItem> list = ProblemsWorkspaceAnalyzer.analyze(
                        getApplicationContext(), root, text, active,
                        partial -> {
                            publishProblems(partial);
                            return !isFinishing() && !isDestroyed() && !problemsWorker.isShutdown();
                        });
                publishProblems(list);
                if (com.ccs.wiredroid.BuildConfig.DEBUG) {
                    android.util.Log.i("Startup", "problems pass: " + list.size() + " item(s) for "
                            + (active != null ? active.getName() : "no active file"));
                }

                if (problemsDirty) continue;
                problemsRunning.set(false);
                // A request that landed between the check above and the release
                // would find the flag still set and skip scheduling, so look once
                // more; if someone else has already claimed the slot, leave it.
                if (!problemsDirty) return;
                if (!problemsRunning.compareAndSet(false, true)) return;
            }
        }
    };

    private void updateToolbarTitle() {
        if (toolbarTitle != null) {
            toolbarTitle.setText(projectManager.getProjectDir().getName());
        }
    }

    /** Long-press project name in the toolbar to copy the project root path. */
    private void setupToolbarProjectPathLongClick() {
        if (toolbarTitle == null) return;
        toolbarTitle.setContentDescription(getString(R.string.toolbar_project_a11y));
        toolbarTitle.setOnLongClickListener(v -> {
            if (projectManager == null) return false;
            File dir = projectManager.getProjectDir();
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("WireDroid project", dir.getAbsolutePath()));
                Toast.makeText(this, R.string.toast_project_path_copied, Toast.LENGTH_SHORT).show();
            }
            return true;
        });
    }

    private void copyConsoleToClipboard() {
        if (consoleManager != null) {
            consoleManager.copyToClipboard();
        }
    }

    private void shareCurrentFile() {
        FileTab tab = ws.tabs().getActiveTab();
        if (tab == null || tab.file == null) {
            Toast.makeText(this, R.string.toast_no_file_open, Toast.LENGTH_SHORT).show();
            return;
        }
        saveCurrentToActiveTab();
        try {
            Uri fileUri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", tab.file);
            Intent send = new Intent(Intent.ACTION_SEND);
            String mime = tab.file.getName().endsWith(".java") ? "text/x-java" : "text/plain";
            send.setType(mime);
            send.putExtra(Intent.EXTRA_SUBJECT, tab.file.getName());
            send.putExtra(Intent.EXTRA_STREAM, fileUri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.share_file_chooser)));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.error_cannot_read, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void shareToPastebin() {
        String apiKey = PastebinHelper.getApiKey(this);
        if (apiKey.isEmpty()) {
            showPastebinKeyDialog();
            return;
        }

        String code = "";
        String title = "WireDroid";
        FileTab tab = ws.tabs().getActiveTab();
        if (ws.activeEditor != null && ws.activeEditor.getText() != null) {
            code = ws.activeEditor.getText().toString();
        }
        if (tab != null && tab.file != null) {
            title = tab.file.getName();
        }
        if (code.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_file_open, Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, R.string.pastebin_uploading, Toast.LENGTH_SHORT).show();
        String finalCode = code;
        String finalTitle = title;

        new Thread(() -> {
            try {
                String url = PastebinHelper.createPaste(this, finalCode, finalTitle, "java", "0");
                runOnUiThread(() -> {
                    if (url != null) {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("pastebin", url));
                        }
                        Toast.makeText(this, R.string.pastebin_success, Toast.LENGTH_LONG).show();
                        // Open in browser
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        } catch (Exception ignored) {}
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.pastebin_error, e.getMessage()), Toast.LENGTH_LONG).show());
            }
        }, "pastebin-upload").start();
    }

    private void showPastebinKeyDialog() {
        EditText input = new EditText(this);
        input.setHint(R.string.pastebin_hint);
        input.setHintTextColor(theme.textDim);
        input.setTextColor(theme.text);
        input.setPadding(dp(32), dp(16), dp(32), dp(16));
        input.setSingleLine(true);

        newRoundedDialog()
                .setTitle(R.string.pastebin_title)
                .setMessage(R.string.pastebin_message)
                .setView(input)
                .setPositiveButton(R.string.dialog_apply, (d, w) -> {
                    String key = input.getText().toString().trim();
                    if (!key.isEmpty()) {
                        PastebinHelper.setApiKey(this, key);
                        shareToPastebin();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (ws.tabs() != null) {
            java.util.ArrayList<String> paths = new java.util.ArrayList<>();
            for (FileTab tab : ws.allTabs()) {
                paths.add(tab.file.getAbsolutePath());
            }
            outState.putStringArrayList("open_tab_paths", paths);
            outState.putInt("active_tab_index", ws.tabs().getActiveIndex());
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Menu
    // ══════════════════════════════════════════════════════════

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            Drawable icon = item.getIcon();
            if (icon != null) {
                icon.mutate().setColorFilter(theme.text, PorterDuff.Mode.SRC_IN);
            }
        }
        MenuItem gitItem = menu.findItem(R.id.action_git);
        if (gitItem != null) {
            File dir = projectManager.getProjectDir();
            gitItem.setVisible(dir != null && GitManager.isGitRepo(dir));
        }
        MenuItem runItem = menu.findItem(R.id.action_run);
        if (runItem != null) {
            if (isRunning) {
                runItem.setIcon(R.drawable.ic_stop);
                runItem.setTitle(R.string.menu_stop);
                runItem.setContentDescription(getString(R.string.a11y_menu_stop));
                Drawable icon = runItem.getIcon();
                if (icon != null) {
                    icon.mutate().setColorFilter(0xFFE53935, PorterDuff.Mode.SRC_IN);
                }
            } else {
                runItem.setIcon(R.drawable.ic_run);
                runItem.setTitle(R.string.menu_run);
                runItem.setContentDescription(getString(R.string.a11y_menu_run));
                Drawable icon = runItem.getIcon();
                if (icon != null) {
                    icon.mutate().setColorFilter(theme != null ? theme.text : 0xFFCCCCCC, PorterDuff.Mode.SRC_IN);
                }
            }
        }
        // Hide overflow items from the native menu — we show them in our searchable dialog
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if (item.getItemId() != R.id.action_wire
                    && item.getItemId() != R.id.action_undo
                    && item.getItemId() != R.id.action_redo
                    && item.getItemId() != R.id.action_save
                    && item.getItemId() != R.id.action_run
                    && item.getItemId() != R.id.action_debug
                    && item.getItemId() != R.id.action_find
                    && item.getItemId() != R.id.action_settings
                    && item.getItemId() != R.id.action_git
                    && item.getItemId() != R.id.action_ai_chat) {
                item.setVisible(false);
            }
        }
        applyToolbarPreferences(menu);
        return super.onPrepareOptionsMenu(menu);
    }

    /**
     * Hides the toolbar buttons the user has switched off.
     *
     * <p>Applied last, so it wins over the blanket "show these, hide the rest"
     * pass above. Nothing is removed from the menu — every one of these actions
     * is still reachable from the searchable menu, which is what makes hiding
     * the button safe rather than a way to lose a feature.</p>
     */
    private void applyToolbarPreferences(Menu menu) {
        if (appPrefs == null) return;
        hideIfOff(menu, R.id.action_undo,    appPrefs.isToolbarUndo());
        hideIfOff(menu, R.id.action_redo,    appPrefs.isToolbarRedo());
        hideIfOff(menu, R.id.action_debug, false);
        hideIfOff(menu, R.id.action_find,    appPrefs.isToolbarFind());
        hideIfOff(menu, R.id.action_format,  appPrefs.isToolbarFormat());
        hideIfOff(menu, R.id.action_ai_chat, appPrefs.isToolbarAiChat());
    }

    private void hideIfOff(Menu menu, int id, boolean shown) {
        MenuItem item = menu.findItem(id);
        // Only ever hides. A button the earlier passes already hid — Git outside
        // a repository — must not be brought back by a preference that knows
        // nothing about why it was hidden.
        if (item != null && !shown) item.setVisible(false);
    }

    @Override
    public boolean onPrepareOptionsPanel(View view, Menu menu) {
        super.onPrepareOptionsPanel(view, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_wire) { openWireWorkspace(false); return true; }
        if      (id == R.id.action_run)           { if (isRunning) stopRunning(); else runCurrentFile(); return true; }
        else if (id == R.id.action_debug)         { openWireWorkspace(false);           return true; }
        else if (id == R.id.action_save)          { saveCurrentFile();      return true; }
        else if (id == R.id.action_find)          { toggleFindBar();        return true; }
        else if (id == R.id.action_undo)          { ws.activeEditor.undo();          return true; }
        else if (id == R.id.action_redo)          { ws.activeEditor.redo();          return true; }
        else if (id == R.id.action_settings)         { openSettings();            return true; }
        else if (id == R.id.action_format)           { formatCurrentFile(); return true; }
        else if (id == R.id.action_git)              { openGit(); return true; }
        else if (id == R.id.action_ai_chat)           { openAiChat(); return true; }
        else if (id == R.id.action_search_everywhere) { SearchEverywhereActivity.launch(this, projectManager.getProjectDir()); return true; }
        else if (id == R.id.action_split_screen)      { toggleSplitScreen(); return true; }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Intercept overflow menu button to show our searchable dialog instead.
     */
    @Override
    public boolean onMenuOpened(int featureId, android.view.Menu menu) {
        // FEATURE_OPTIONS_PANEL = 6, but it's deprecated so use raw value
        if (featureId == 6) {
            new Handler(Looper.getMainLooper()).post(this::showSearchableMenu);
            return false;
        }
        return super.onMenuOpened(featureId, menu);
    }

    private void showSearchableMenu() {
        if (searchableMenuController != null) {
            searchableMenuController.show();
        }
    }

    private void executeMenuAction(String action) {
        if (java.util.Arrays.asList("debug", "new_scratch", "open_scratch", "sync_deps", "library",
                "load_mapping", "maven_panel", "cpp_module", "auto_import", "refactor", "dependencies",
                "uml_generator", "run_config", "organize_imports", "bytecode", "call_graph",
                "class_browser", "maven_package", "maven_test", "maven_test_run", "maven_clean", "maven_install").contains(action)) return;
        switch (action) {
            case "wire_workspace": openWireWorkspace(false); break;
            case "new_scratch":   showNewScratchDialog(); break;
            case "open_scratch":  showOpenScratchDialog(); break;
            case "bytecode":      switchBottomPanel(PANEL_BYTECODE); break;
            case "new_file":      showNewFileDialog(); break;
            case "new_maven":     openNewProjectWizard(); break;
            case "sync_deps":     syncDependencies(); break;
            case "library":       openLibraryManager(); break;
            case "class_browser": openClassBrowser(); break;
            case "load_mapping":  loadProGuardMapping(); break;
            case "maven_panel":   openMavenPanel(); break;
            case "maven_package": mavenPackage(); break;
            case "maven_test":    mavenTestCompile(); break;
            case "maven_test_run": mavenTestRun(); break;
            case "maven_clean":   mavenClean(); break;
            case "maven_install": mavenInstall(); break;
            case "cpp_module":    showCreateCppModuleDialog(); break;
            case "clear_console": if (consoleManager != null) consoleManager.clear(); break;
            case "share_file":    shareCurrentFile(); break;
            case "pastebin":      shareToPastebin(); break;
            case "open_file":     pickFileToOpen(); break;
            case "import_files":  importFilesToProject(); break;
            case "save_as":       saveCurrentAs(); break;
            case "format":        formatCurrentFile(); break;
            case "auto_import":   showAutoImportDialog(); break;
            case "view_formatted": showFormattedView(); break;
            case "md_preview":    showMarkdownPreview(); break;
            case "export_project": exportProjectAsZip(); break;
            case "split_screen":  toggleSplitScreen(); break;
            case "play_media":    pickMediaFile(); break;
            case "http_client":   HttpApiClientActivity.launch(this); break;
            case "webview_preview": openWebViewPreview(); break;
            case "call_graph":    openCallGraph(); break;
            case "toggle_bookmark": toggleBookmarkAtCursor(); break;
            case "show_bookmarks":  showBookmarksDialog(); break;
            case "voice_input":     toggleVoiceInput(); break;
            case "refactor":        showRefactorDialog(); break;
            case "dependencies":    switchBottomPanel(PANEL_DEPS); break;
            case "regex_tester":    showRegexTesterDialog(); break;
            case "base64_encoder":  showBase64EncoderDialog(); break;
            case "hash_calc":       openHashCalculator(); break;
            case "ssl_certs":       SslCertificateActivity.launch(this); break;
            case "split_terminal":  SplitTerminalActivity.launch(this); break;
            case "db_client":
                com.ccs.wiredroid.db.DbClientActivity.launch(this);
                break;
            case "bt_share":        shareCurrentFileOverBluetooth(); break;
            case "search_everywhere":
                SearchEverywhereActivity.launch(this, projectManager.getProjectDir());
                break;
            case "local_history":
                showLocalHistoryDialog();
                break;
            case "zen_mode":
                toggleZenMode();
                break;
            case "run_config":
                showRunConfigurationDialog();
                break;
            case "semantic_search":
                com.ccs.wiredroid.ai.SemanticSearchActivity.launch(this,
                        projectManager != null ? projectManager.getProjectDir() : null);
                break;
            case "organize_imports":
                if (refactorController != null) refactorController.organizeImports();
                break;
            case "uml_generator":
                UmlDiagramActivity.launch(this, projectManager != null ? projectManager.getProjectDir() : null);
                break;
            case "project_map":
                ProjectMapActivity.launch(this, projectManager.getProjectDir());
                break;
            case "project_structure":
                openWireWorkspace(false);
                break;
            // Also reachable from the toolbar buttons; the menu bar addresses
            // them by name because it has no MenuItem to hand to
            // onOptionsItemSelected.
            case "undo":          if (ws.activeEditor != null) ws.activeEditor.undo(); break;
            case "redo":          if (ws.activeEditor != null) ws.activeEditor.redo(); break;
            case "save":          saveCurrentFile(); break;
            case "run":           if (isRunning) stopRunning(); else runCurrentFile(); break;
            case "stop":          if (isRunning) stopRunning(); break;
            case "debug":         startDebug(); break;
            case "find":          toggleFindBar(); break;
            case "git":           openGit(); break;
            case "settings":      openSettings(); break;
            case "ai_chat":       openAiChat(); break;
            case "new_project":   openNewProjectWizard(); break;
            case "problems":      switchBottomPanel(PANEL_PROBLEMS); break;
            case "todo_panel":    switchBottomPanel(PANEL_TODO); break;
            case "run_panel":     switchBottomPanel(PANEL_RUN); break;
            case "line_separator": showLineSeparatorDialog(); break;
            case "encoding":      showEncodingSelectionDialog(); break;
            case "command_palette": showSearchableMenu(); break;
            case "duplicate_line":
                com.ccs.wiredroid.editor.EditorTextActions.duplicateLines(ws.activeEditor);
                break;
            case "move_line_up":
                com.ccs.wiredroid.editor.EditorTextActions.moveLinesUp(ws.activeEditor);
                break;
            case "move_line_down":
                com.ccs.wiredroid.editor.EditorTextActions.moveLinesDown(ws.activeEditor);
                break;
            case "toggle_comment": {
                FileTab commentTab = ws.tabs() == null ? null : ws.tabs().getActiveTab();
                com.ccs.wiredroid.editor.EditorTextActions.toggleLineComment(
                        ws.activeEditor, commentTab == null ? null : commentTab.file);
                break;
            }
            case "fold_toggle":
            case "fold_all":
            case "unfold_all":
                runFoldAction(action);
                break;
        }
    }

    /**
     * Folds and unfolds blocks in the active editor.
     *
     * <p>Which block "fold" means is decided by the caret: the one starting on
     * its line, or failing that the innermost one around it. When there is no
     * such block the user is told, rather than the command doing nothing and
     * looking broken.</p>
     */
    private void runFoldAction(String action) {
        if (!(ws.activeEditor instanceof WireDroidCodeEditor)) return;
        com.ccs.wiredroid.editor.EditorFolding folding =
                ((WireDroidCodeEditor) ws.activeEditor).folding();
        if (folding == null) return;

        boolean changed;
        switch (action) {
            case "fold_all":
                changed = folding.foldAll();
                break;
            case "unfold_all":
                changed = folding.unfoldAll();
                break;
            default:
                int line = ws.activeEditor.getCursor() == null
                        ? 0 : ws.activeEditor.getCursor().getLeftLine();
                changed = folding.toggleAt(line);
                break;
        }
        if (!changed) {
            Toast.makeText(this, R.string.editor_nothing_to_fold, Toast.LENGTH_SHORT).show();
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Scratchpad (Standalone files)
    // ══════════════════════════════════════════════════════════

    private void showScratchMenu() {
        String[] items = {
                getString(R.string.scratch_new_title),
                getString(R.string.scratch_open_title)
        };
        androidx.appcompat.app.AlertDialog dialog = Dialogs.rounded(this)
                .setTitle(R.string.scratch_open_title)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        showNewScratchDialog();
                    } else {
                        showOpenScratchDialog();
                    }
                })
                .show();
        Dialogs.style(dialog, theme);
    }

    private void showNewScratchDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        final RadioGroup radioGroup = new RadioGroup(this);
        radioGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbJava = new RadioButton(this);
        rbJava.setText(R.string.scratch_java);
        rbJava.setId(View.generateViewId());
        rbJava.setChecked(true);
        if (theme != null) rbJava.setTextColor(theme.text);
        radioGroup.addView(rbJava);

        RadioButton rbKotlin = new RadioButton(this);
        rbKotlin.setText(R.string.scratch_kotlin);
        rbKotlin.setId(View.generateViewId());
        if (theme != null) rbKotlin.setTextColor(theme.text);
        radioGroup.addView(rbKotlin);

        layout.addView(radioGroup);

        final EditText input = new EditText(this);
        input.setHint(R.string.scratch_name_hint);
        if (theme != null) {
            input.setTextColor(theme.text);
            input.setHintTextColor(theme.textDim);
        }
        input.setSingleLine(true);
        layout.addView(input);

        androidx.appcompat.app.AlertDialog dialog = Dialogs.rounded(this)
                .setTitle(R.string.scratch_new_title)
                .setView(layout)
                .setPositiveButton(R.string.dialog_create, (d, w) -> {
                    boolean isJava = rbJava.isChecked();
                    String name = input.getText().toString().trim();
                    try {
                        File scratch = isJava
                                ? com.ccs.wiredroid.scratch.ScratchManager.createJavaScratch(this, name)
                                : com.ccs.wiredroid.scratch.ScratchManager.createKotlinScratch(this, name);
                        openFile(scratch);
                        Toast.makeText(this, scratch.getName(), Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        Dialogs.style(dialog, theme);
    }

    private void showOpenScratchDialog() {
        List<File> scratches = com.ccs.wiredroid.scratch.ScratchManager.listScratches(this);
        if (scratches.isEmpty()) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.scratch_open_title)
                    .setMessage(R.string.scratch_empty_list)
                    .setPositiveButton(R.string.scratch_new_title, (d, w) -> showNewScratchDialog())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        CharSequence[] names = new CharSequence[scratches.size()];
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault());
        for (int i = 0; i < scratches.size(); i++) {
            File f = scratches.get(i);
            names[i] = f.getName() + "  (" + sdf.format(new Date(f.lastModified())) + ")";
        }

        androidx.appcompat.app.AlertDialog dialog = Dialogs.rounded(this)
                .setTitle(R.string.scratch_open_title)
                .setItems(names, (d, which) -> {
                    if (which >= 0 && which < scratches.size()) {
                        openFile(scratches.get(which));
                    }
                })
                .setNeutralButton(R.string.scratch_new_title, (d, w) -> showNewScratchDialog())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        Dialogs.style(dialog, theme);
    }

    /** Hash the file in the active tab, or let the user pick one if nothing is open. */
    private void openHashCalculator() {
        FileTab tab = ws.tabs().getActiveTab();
        if (tab != null && tab.file != null) {
            saveCurrentToActiveTab();
            HashCalculatorActivity.launch(this, tab.file);
        } else {
            HashCalculatorActivity.launch(this);
        }
    }

    private void shareCurrentFileOverBluetooth() {
        FileTab tab = ws.tabs().getActiveTab();
        if (tab == null || tab.file == null) {
            Toast.makeText(this, R.string.toast_no_file_open, Toast.LENGTH_SHORT).show();
            return;
        }
        // Flush the buffer first: sending the on-disk file while the editor holds
        // unsaved edits would transfer a stale copy.
        saveCurrentToActiveTab();
        saveCurrentFile();
        com.ccs.wiredroid.util.BluetoothShare.send(this, tab.file);
    }

    private void openGit() {
        saveCurrentToActiveTab();
        GitActivity.launch(this, projectManager.getProjectDir());
    }

    // ══════════════════════════════════════════════════════════
    //  Tab management
    // ══════════════════════════════════════════════════════════

    /**
     * Extensions that hold binary data the editor cannot render as text. These
     * go to the hex editor instead of being decoded as broken UTF-8.
     */
    private static boolean isBinaryByExtension(String nameLower) {
        String[] binary = {
                ".bin", ".dat", ".so", ".o", ".a", ".dex", ".odex", ".vdex", ".art",
                ".jar", ".zip", ".apk", ".aar", ".aab", ".war", ".ear",
                ".gz", ".bz2", ".xz", ".7z", ".rar", ".tar",
                ".pdf", ".ttf", ".otf", ".woff", ".woff2", ".ico", ".icns",
                ".pyc", ".exe", ".dll", ".dylib", ".keystore", ".jks", ".p12", ".der"
        };
        for (String ext : binary) {
            if (nameLower.endsWith(ext)) return true;
        }
        return false;
    }

    private void openFile(File file) {
        int existing = ws.tabs().indexOfFile(file);
        if (existing >= 0) {
            switchTab(existing);
            return;
        }
        // The file may already be open in the other pane. Going there beats
        // opening a second tab on the same file, which would give the two panes
        // separate unsaved copies of it.
        if (ws.isSplitActive) {
            io.github.rosemoe.sora.widget.CodeEditor other =
                    ws.activeEditor == ws.editor ? ws.editor2 : ws.editor;
            int inOther = ws.tabsFor(other).indexOfFile(file);
            if (inOther >= 0) {
                focusPane(other);
                switchTab(inOther);
                return;
            }
        }
        saveCurrentToActiveTab();

        String nameLower = file.getName().toLowerCase(Locale.ROOT);

        // .svg, .html, .htm — open in editor (preview via menu)
        if (nameLower.endsWith(".svg") || nameLower.endsWith(".html") || nameLower.endsWith(".htm")) {
            openEditableFile(file);
            return;
        }

        // Bitmap image: open in the image viewer (zoom, rotate, animation).
        if (ImageViewerActivity.isSupported(nameLower)) {
            ImageViewerActivity.launch(this, file);
            return;
        }

        // Audio or video: open in the media player.
        if (MediaPlayerActivity.isMediaFile(nameLower)) {
            MediaPlayerActivity.launch(this, file);
            return;
        }

        // Binary formats with no dedicated viewer: open in the hex editor.
        if (isBinaryByExtension(nameLower)) {
            HexEditorActivity.launch(this, file);
            return;
        }

        // .db/.sqlite — database: open in inspector
        if (nameLower.endsWith(".db") || nameLower.endsWith(".sqlite")
                || nameLower.endsWith(".sqlite3")) {
            DatabaseInspectorActivity.launch(this, file);
            return;
        }

        // .http — HTTP client request
        if (nameLower.endsWith(".http")) {
            HttpApiClientActivity.launch(this, file);
            return;
        }

        // .tmx — Tiled Map XML viewer
        if (nameLower.endsWith(".tmx")) {
            TmxViewerActivity.launch(this, file);
            return;
        }

        // .atlas — Texture Atlas inspector
        if (nameLower.endsWith(".atlas")) {
            AtlasViewerActivity.launch(this, file);
            return;
        }

        // .html/.htm — open in editor (preview via menu)
        if (nameLower.endsWith(".html") || nameLower.endsWith(".htm")) {
            openEditableFile(file);
            return;
        }

        // .md/.markdown — open in editor (preview via menu)
        if (nameLower.endsWith(".md") || nameLower.endsWith(".markdown")) {
            openEditableFile(file);
            return;
        }

        // .class — бінарний файл: завантажуємо байтки й відкриваємо байткод-панель.
        if (file.getName().endsWith(".class")) {
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                FileTab tab = new FileTab(file);
                tab.classBytes = bytes;
                ws.tabs().addTab(tab);
                int idx = ws.tabs().getTabs().size() - 1;
                ws.tabs().setActiveIndex(idx);
                ws.isProgrammaticChange = true;
                ws.activeEditor.setText("");
                ws.isProgrammaticChange = false;
                ws.activeEditor.setEditable(false);
                activeStrip().scrollToPosition(idx);
                updateStatusFileName(file);
                fileTreeAdapter.setActiveFile(file);
                if (ws.activeEditor == ws.editor) ws.leftTab = tab; else ws.rightTab = tab;
                updateEditorChrome();
                switchBottomPanel(PANEL_BYTECODE);
            } catch (IOException e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("permission denied")) {
                    newRoundedDialog()
                            .setTitle(R.string.permission_storage_denied_title)
                            .setMessage(R.string.permission_storage_denied_message)
                            .setPositiveButton(R.string.permission_storage_open_settings, (d, w) -> openStorageSettings())
                            .setNegativeButton(R.string.dialog_cancel, null)
                            .show();
                } else {
                    Toast.makeText(this, getString(R.string.error_cannot_open, msg), Toast.LENGTH_SHORT).show();
                }
            }
            return;
        }

        // Regular file — read and show in editor
        try {
            String content = projectManager.readFile(file);
            FileTab tab = new FileTab(file);
            ws.tabs().addTab(tab);
            int idx = ws.tabs().getTabs().size() - 1;
            ws.tabs().setActiveIndex(idx);
            ws.isProgrammaticChange = true;
            ws.activeEditor.setText(content);
            ws.isProgrammaticChange = false;
            applyEditorLanguage(file, ws.activeEditor);
            ws.activeEditor.setEditable(!isFileReadOnly(file));
            updateReadOnlyIndicator();
            activeStrip().scrollToPosition(idx);
            updateStatusFileName(file);
            fileTreeAdapter.setActiveFile(file);
            fileTreeController.revealInTree(file);

            if (ws.activeEditor == ws.editor) {
                ws.leftTab = tab;
            } else {
                ws.rightTab = tab;
            }
            restoreWireDraft(ws.activeEditor,tab);
            refreshProblemsMergedAsync();
            refreshBookmarkMarkers();
            updateEditorChrome();
            // Opening a file goes through neither a tab switch nor a content
            // change, so the hints have to be asked for here as well.
            scheduleInlayHints(ws.activeEditor, 400L);
        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("permission denied")) {
                newRoundedDialog()
                        .setTitle(R.string.permission_storage_denied_title)
                        .setMessage(R.string.permission_storage_denied_message)
                        .setPositiveButton(R.string.permission_storage_open_settings, (d, w) -> openStorageSettings())
                        .setNegativeButton(R.string.dialog_cancel, null)
                        .show();
            } else {
                Toast.makeText(this, getString(R.string.error_cannot_open, msg), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void switchTab(int index) {
        if (index == ws.tabs().getActiveIndex()) return;
        saveCurrentToActiveTab();
        // Hints belong to the file, so the outgoing one's are dropped at once
        // rather than lingering over the incoming text until the next pass.
        InlayHintsOverlay leaving = (ws.activeEditor == ws.editor) ? ws.inlayOverlay1 : ws.inlayOverlay2;
        if (leaving != null) leaving.clear();
        scheduleInlayHints(ws.activeEditor, 250L);
        FileTab tab = ws.tabs().getTabs().get(index);
        try {
            // .class вкладка — показуємо байткод-панель замість редактора
            if (tab.isClassFile()) {
                ws.tabs().setActiveIndex(index);
                ws.isProgrammaticChange = true;
                ws.activeEditor.setText("");
                ws.isProgrammaticChange = false;
                ws.activeEditor.setEditable(false);
                ws.tabs().markModified(index, false);
                activeStrip().scrollToPosition(index);
                updateStatusFileName(tab.file);
                fileTreeAdapter.setActiveFile(tab.file);
                if (ws.activeEditor == ws.editor) ws.leftTab = tab; else ws.rightTab = tab;
                updateEditorChrome();
                syncPanePreview(ws.activeEditor == ws.editor);
                switchBottomPanel(PANEL_BYTECODE);
                return;
            }

            String content = projectManager.readFile(tab.file);
            ws.tabs().setActiveIndex(index);
            ws.isProgrammaticChange = true;
            ws.activeEditor.setText(content);
            ws.isProgrammaticChange = false;
            applyEditorLanguage(tab.file, ws.activeEditor);
            ws.activeEditor.setEditable(!isFileReadOnly(tab.file));
            try {
                ws.activeEditor.setSelection(tab.cursorLine, tab.cursorColumn);
            } catch (Exception e) {}
            ws.tabs().markModified(index, false);
            activeStrip().scrollToPosition(index);
            updateStatusFileName(tab.file);
            fileTreeAdapter.setActiveFile(tab.file);

            if (ws.activeEditor == ws.editor) {
                ws.leftTab = tab;
            } else {
                ws.rightTab = tab;
            }
            restoreWireDraft(ws.activeEditor,tab);
            refreshProblemsMergedAsync();
            refreshBookmarkMarkers();
            updateEditorChrome();
            // The pane now holds a different file, so what it shows is decided
            // again from that file's own flag — the previous tab's preview does
            // not carry over, and returning to it brings its preview back.
            syncPanePreview(ws.activeEditor == ws.editor);
            scheduleInlayHints(ws.activeEditor, 300L);
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.error_cannot_read, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void closeTab(int index) {
        if (index < 0 || index >= ws.tabs().getTabs().size()) return;
        FileTab tab = ws.tabs().getTabs().get(index);
        if (tab.isModified) {
            newRoundedDialog()
                    .setTitle(R.string.dialog_unsaved_title)
                    .setMessage(getString(R.string.dialog_unsaved_message, tab.file.getName()))
                    .setPositiveButton(R.string.dialog_save_close, (d, w) -> { if(saveTab(index))doCloseTab(index); })
                    .setNegativeButton(R.string.dialog_discard, (d, w) -> doCloseTab(index))
                    .setNeutralButton(R.string.dialog_cancel, null)
                    .show();
        } else {
            doCloseTab(index);
        }
    }

    private void doCloseTab(int index) {
        FileTab tabBeingClosed = ws.tabs().getTabs().get(index);
        if(tabBeingClosed!=null&&tabBeingClosed.file!=null) {
            if(tabBeingClosed==ws.leftTab)cancelWireDraft(ws.editor);
            if(tabBeingClosed==ws.rightTab)cancelWireDraft(ws.editor2);
            try{if(projectManager.isEmbeddedFile(tabBeingClosed.file))com.ccs.wiredroid.wire.RecoveryStore.discardDraft(tabBeingClosed.file);}
            catch(IOException error){Toast.makeText(this,"Не вдалося прибрати буфер відновлення: "+error.getMessage(),Toast.LENGTH_LONG).show();return;}
        }
        if (tabBeingClosed != null && tabBeingClosed.file != null) {
            try {
                String content = projectManager.readFile(tabBeingClosed.file);
                com.ccs.wiredroid.util.LocalHistoryManager.saveSnapshot(this, tabBeingClosed.file, content, "Close");
            } catch (Exception ignored) {}
        }
        int active = ws.tabs().getActiveIndex();
        boolean inRightPane = ws.activeEditor == ws.editor2;
        ws.tabs().removeTab(index);

        if (ws.leftTab == tabBeingClosed) ws.leftTab = null;
        if (ws.rightTab == tabBeingClosed) ws.rightTab = null;

        // Closing the last tab of a pane closes the pane. This is the whole
        // point of giving each pane its own strip: ending a split is now the
        // same gesture as closing any tab, instead of a second trip through the
        // long-press menu that opened it.
        if (ws.isSplitActive && ws.tabs().getTabs().isEmpty()) {
            if (inRightPane) {
                closeSplit();
            } else {
                // The first pane emptied; the second one's tabs become the only
                // ones, so merge them back and carry on unsplit.
                ws.activeEditor = ws.editor2;
                closeSplit();
            }
            return;
        }

        if (ws.tabs().getTabs().isEmpty()) {
            ws.isProgrammaticChange = true;
            ws.editor.setText("");
            ws.editor2.setText("");
            ws.isProgrammaticChange = false;
            ws.editor.setEditable(false);
            ws.editor2.setEditable(false);
            // Free language syntax trees from memory
            ws.editor.setEditorLanguage(null);
            ws.editor2.setEditorLanguage(null);
            ws.leftTab = null;
            ws.rightTab = null;
            statusFileName.setText("");
            fileTreeAdapter.setActiveFile(null);
            updateEditorChrome();
            return;
        }

        int next = (index < active) ? active - 1
                 : (index == active) ? Math.min(index, ws.tabs().getTabs().size() - 1)
                 : active;

        FileTab tab = ws.tabs().getTabs().get(next);
        try {
            ws.tabs().setActiveIndex(next);
            ws.isProgrammaticChange = true;
            ws.activeEditor.setText(projectManager.readFile(tab.file));
            ws.isProgrammaticChange = false;
            applyEditorLanguage(tab.file, ws.activeEditor);
            ws.activeEditor.setEditable(true);
            activeStrip().scrollToPosition(next);
            updateStatusFileName(tab.file);
            fileTreeAdapter.setActiveFile(tab.file);
            
            if (ws.activeEditor == ws.editor) {
                ws.leftTab = tab;
            } else {
                ws.rightTab = tab;
            }
            restoreWireDraft(ws.activeEditor,tab);
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.error_cannot_read, e.getMessage()), Toast.LENGTH_SHORT).show();
        }

        // The pane the user was not in keeps its own tab; it only needs filling
        // if the closed tab happened to be the one it was showing.
        if (ws.isSplitActive) {
            if (ws.leftTab == null)  fillPaneFromItsStrip(ws.editor);
            if (ws.rightTab == null) fillPaneFromItsStrip(ws.editor2);
        }
        refreshBookmarkMarkers();
        updateEditorChrome();
        syncPanePreviews();
    }

    /** Shows a pane's first remaining tab, for when it lost the one it had. */
    private void fillPaneFromItsStrip(io.github.rosemoe.sora.widget.CodeEditor pane) {
        TabsAdapter strip = ws.tabsFor(pane);
        if (strip == null || strip.getTabs().isEmpty()) return;
        int idx = Math.min(Math.max(strip.getActiveIndex(), 0), strip.getTabs().size() - 1);
        strip.setActiveIndex(idx);
        FileTab tab = strip.getTabs().get(idx);
        if (pane == ws.editor) ws.leftTab = tab; else ws.rightTab = tab;
        loadIntoPane(pane, tab);
    }

    /** Splits on the current tab, or merges the panes back together. */
    private void toggleSplitScreen() {
        if (ws.isSplitActive) closeSplit();
        else openSplit(ws.tabs().getActiveTab());
    }

    /**
     * Opens {@code moving} in a second pane beside the current one.
     *
     * <p>The tab <em>moves</em>: it leaves the strip it was in and appears in
     * the new one, which is what makes the split visible in the UI at all. The
     * one exception is a lone tab — there is nothing to leave behind, so it is
     * shown on both sides until the user opens something else.</p>
     */
    private void openSplit(FileTab moving) {
        if (moving == null || ws.isSplitActive) {
            if (moving != null && ws.isSplitActive) moveTabToPane(moving, ws.editor2);
            return;
        }
        saveCurrentToActiveTab();

        ws.isSplitActive = true;
        ws.editorDivider.setVisibility(View.VISIBLE);
        ws.wrapperEditor2.setVisibility(View.VISIBLE);
        ws.editor2.setVisibility(View.VISIBLE);
        if (tabsGroup2 != null) tabsGroup2.setVisibility(View.VISIBLE);
        if (tabsGroupDivider != null) tabsGroupDivider.setVisibility(View.VISIBLE);
        if (ws.minimapView2 != null) {
            ws.minimapView2.setVisibility(isMinimapAllowed() ? View.VISIBLE : View.GONE);
        }
        if (splitDivider != null) splitDivider.applyStoredRatio();

        boolean lone = ws.tabsLeft.getTabs().size() <= 1;
        if (!lone) ws.tabsLeft.removeTab(ws.tabsLeft.getTabs().indexOf(moving));

        ws.tabsRight.addTab(moving);
        ws.tabsRight.setActiveIndex(ws.tabsRight.getTabs().size() - 1);
        ws.rightTab = moving;

        // The left pane may have just lost the file it was showing.
        if (!lone && ws.leftTab == moving) {
            ws.leftTab = null;
            int fallback = Math.min(Math.max(ws.tabsLeft.getActiveIndex(), 0),
                    ws.tabsLeft.getTabs().size() - 1);
            if (fallback >= 0) {
                ws.tabsLeft.setActiveIndex(fallback);
                ws.leftTab = ws.tabsLeft.getTabs().get(fallback);
                loadIntoPane(ws.editor, ws.leftTab);
            }
        } else if (ws.leftTab != null) {
            applyEditorLanguage(ws.leftTab.file, ws.editor);
        }

        ws.activeEditor = ws.editor2;
        loadIntoPane(ws.editor2, moving);
        ws.editor2.requestFocus();
        updateActiveEditorBorders();
        afterTabStripChanged();
    }

    /**
     * Ends the split, taking the second pane's tabs with it.
     *
     * <p>Its tabs move back into the first strip rather than closing — the user
     * asked to stop splitting, not to lose the files they had open there.</p>
     */
    private void closeSplit() {
        if (!ws.isSplitActive) return;
        if (ws.rightTab != null) saveEditorToTab(ws.editor2, ws.rightTab);
        clearPanePreviews();

        FileTab wasRight = ws.rightTab;
        for (FileTab tab : new java.util.ArrayList<>(ws.tabsRight.getTabs())) {
            if (!ws.tabsLeft.getTabs().contains(tab)) ws.tabsLeft.addTab(tab);
        }
        while (!ws.tabsRight.getTabs().isEmpty()) ws.tabsRight.removeTab(0);
        ws.tabsRight.setActiveIndex(-1);

        ws.isSplitActive = false;
        ws.editorDivider.setVisibility(View.GONE);
        ws.wrapperEditor2.setVisibility(View.GONE);
        ws.editor2.setVisibility(View.GONE);
        if (tabsGroup2 != null) tabsGroup2.setVisibility(View.GONE);
        if (tabsGroupDivider != null) tabsGroupDivider.setVisibility(View.GONE);
        if (ws.minimapView2 != null) ws.minimapView2.setVisibility(View.GONE);

        ws.isProgrammaticChange = true;
        ws.editor2.setText("");
        ws.isProgrammaticChange = false;
        ws.rightTab = null;
        ws.activeEditor = ws.editor;

        // Keep looking at the file that was in the pane being closed, if the
        // user was in it — that is the one they were reading.
        FileTab keep = wasRight != null && ws.tabsLeft.getTabs().contains(wasRight)
                ? wasRight : ws.leftTab;
        if (keep == null && !ws.tabsLeft.getTabs().isEmpty()) keep = ws.tabsLeft.getTabs().get(0);
        if (keep != null) {
            ws.tabsLeft.setActiveIndex(ws.tabsLeft.getTabs().indexOf(keep));
            ws.leftTab = keep;
            loadIntoPane(ws.editor, keep);
        }
        ws.editor.requestFocus();
        updateActiveEditorBorders();
        afterTabStripChanged();
    }

    /** Moves an already-open tab into the given pane's strip. */
    private void moveTabToPane(FileTab tab, io.github.rosemoe.sora.widget.CodeEditor pane) {
        if (tab == null || pane == null) return;
        TabsAdapter target = ws.tabsFor(pane);
        TabsAdapter source = pane == ws.editor2 ? ws.tabsLeft : ws.tabsRight;
        if (target == null || source == null) return;

        int at = source.getTabs().indexOf(tab);
        if (at >= 0) {
            source.removeTab(at);
            if (pane == ws.editor2 && ws.leftTab == tab) ws.leftTab = null;
            if (pane == ws.editor  && ws.rightTab == tab) ws.rightTab = null;
        }
        if (!target.getTabs().contains(tab)) target.addTab(tab);
        // Emptying a pane by moving its last tab out is the same thing as
        // closing that last tab: the pane goes, and the split with it. Leaving
        // the tab behind instead would show one file in both panes.
        if (source.getTabs().isEmpty()) {
            if (pane == ws.editor) {
                target.setActiveIndex(target.getTabs().indexOf(tab));
                ws.rightTab = tab;
                ws.activeEditor = ws.editor2;
                closeSplit();
            } else {
                // The first pane emptied; the second one is all that is left.
                ws.rightTab = tab;
                ws.tabsRight.setActiveIndex(ws.tabsRight.getTabs().indexOf(tab));
                ws.activeEditor = ws.editor2;
                closeSplit();
            }
            return;
        }
        target.setActiveIndex(target.getTabs().indexOf(tab));
        if (pane == ws.editor2) ws.rightTab = tab; else ws.leftTab = tab;

        ws.activeEditor = pane;
        loadIntoPane(pane, tab);
        pane.requestFocus();

        // Whichever pane gave the tab up may now be showing nothing.
        io.github.rosemoe.sora.widget.CodeEditor other =
                pane == ws.editor2 ? ws.editor : ws.editor2;
        FileTab otherTab = pane == ws.editor2 ? ws.leftTab : ws.rightTab;
        if (otherTab == null) {
            TabsAdapter otherTabs = ws.tabsFor(other);
            if (!otherTabs.getTabs().isEmpty()) {
                int idx = Math.min(Math.max(otherTabs.getActiveIndex(), 0),
                        otherTabs.getTabs().size() - 1);
                otherTabs.setActiveIndex(idx);
                FileTab pick = otherTabs.getTabs().get(idx);
                if (other == ws.editor) ws.leftTab = pick; else ws.rightTab = pick;
                loadIntoPane(other, pick);
            }
        }
        updateActiveEditorBorders();
        afterTabStripChanged();
    }


    /** Refreshes the chrome that names the file the user is in. */
    private void afterTabStripChanged() {
        FileTab active = ws.activeTab();
        if (active != null) {
            updateStatusFileName(active.file);
            fileTreeAdapter.setActiveFile(active.file);
        }
        refreshBookmarkMarkers();
        updateEditorChrome();
        syncPanePreviews();
    }

    /**
     * The context menu for a tab (long press or right-click / context-click).
     */
    private void showTabContextMenu(int index, @androidx.annotation.Nullable View anchor, float x, float y) {
        java.util.List<FileTab> tabs = ws.tabs().getTabs();
        if (index < 0 || index >= tabs.size()) return;
        FileTab tab = tabs.get(index);
        String name = tab.file != null ? tab.file.getName() : getString(R.string.tab_untitled);

        AnchoredMenu menu = AnchoredMenu.with(this, theme)
                .title(name);

        // Pin / Unpin
        if (tab.isPinned) {
            menu.item(R.drawable.ic_pin, getString(R.string.tab_menu_unpin), () -> {
                tab.isPinned = false;
                ws.tabs().notifyDataSetChanged();
            });
        } else {
            menu.item(R.drawable.ic_pin, getString(R.string.tab_menu_pin), () -> {
                tab.isPinned = true;
                ws.tabs().notifyDataSetChanged();
            });
        }

        // Close tab
        menu.item(getString(R.string.tab_menu_close), () -> closeTab(index));

        // Close other tabs
        if (tabs.size() > 1) {
            menu.item(getString(R.string.tab_menu_close_others), () -> closeOtherTabs(tab));
        }

        // Close tabs to the right
        if (index < tabs.size() - 1) {
            menu.item(getString(R.string.tab_menu_close_right), () -> closeTabsToRight(index));
        }

        // Copy path
        if (tab.file != null) {
            menu.item(R.drawable.ic_copy, getString(R.string.tab_menu_copy_path), () -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("File Path", tab.file.getAbsolutePath()));
                    Toast.makeText(this, R.string.tab_path_copied, Toast.LENGTH_SHORT).show();
                }
            });
        }

        menu.separator();

        // Split view actions
        boolean inRightStrip = ws.isSplitActive && ws.tabsRight.getTabs().contains(tab);
        if (!inRightStrip) {
            menu.item(getString(R.string.tab_split_open_beside), () -> openInSplit(tab));
        } else {
            menu.item(getString(R.string.tab_split_move_back), () -> moveTabToPane(tab, ws.editor));
        }
        if (ws.isSplitActive) {
            if (ws.leftTab != null && ws.rightTab != null) {
                menu.item(getString(R.string.tab_split_swap), this::swapSplitPanes);
            }
            menu.item(getString(R.string.tab_split_close), () -> { if (ws.isSplitActive) toggleSplitScreen(); });
        }

        if (anchor != null && (x > 0 || y > 0)) {
            menu.showAt(anchor, x, y);
        } else if (anchor != null) {
            menu.showBelow(anchor);
        } else {
            View fallback = activeStrip();
            if (fallback != null) menu.showBelow(fallback);
        }
    }

    private void closeOtherTabs(FileTab keepTab) {
        java.util.List<FileTab> tabs = new java.util.ArrayList<>(ws.tabs().getTabs());
        for (FileTab t : tabs) {
            if (t != keepTab && !t.isPinned) {
                int idx = ws.tabs().getTabs().indexOf(t);
                if (idx >= 0) closeTab(idx);
            }
        }
    }

    private void closeTabsToRight(int index) {
        java.util.List<FileTab> tabs = new java.util.ArrayList<>(ws.tabs().getTabs());
        for (int i = tabs.size() - 1; i > index; i--) {
            FileTab t = tabs.get(i);
            if (!t.isPinned) {
                int idx = ws.tabs().getTabs().indexOf(t);
                if (idx >= 0) closeTab(idx);
            }
        }
    }

    /** Shows a file in the second pane, turning the split on if it is off. */
    private void openInSplit(FileTab tab) {
        if (tab == null) return;
        if (ws.isSplitActive) moveTabToPane(tab, ws.editor2);
        else openSplit(tab);
    }

    /** Exchanges the two panes' current tabs, strips included. */
    private void swapSplitPanes() {
        if (!ws.isSplitActive || ws.leftTab == null || ws.rightTab == null) return;
        saveEditorToTab(ws.editor, ws.leftTab);
        saveEditorToTab(ws.editor2, ws.rightTab);

        FileTab wasLeft = ws.leftTab, wasRight = ws.rightTab;
        int li = ws.tabsLeft.getTabs().indexOf(wasLeft);
        int ri = ws.tabsRight.getTabs().indexOf(wasRight);
        // The same tab can sit in both strips when only one file is open; there
        // is nothing to exchange then.
        if (li < 0 || ri < 0 || wasLeft == wasRight) return;

        ws.tabsLeft.removeTab(li);
        ws.tabsRight.removeTab(ri);
        ws.tabsLeft.addTab(wasRight);
        ws.tabsRight.addTab(wasLeft);
        ws.tabsLeft.setActiveIndex(ws.tabsLeft.getTabs().indexOf(wasRight));
        ws.tabsRight.setActiveIndex(ws.tabsRight.getTabs().indexOf(wasLeft));

        ws.leftTab = wasRight;
        ws.rightTab = wasLeft;
        loadIntoPane(ws.editor, ws.leftTab);
        loadIntoPane(ws.editor2, ws.rightTab);
        updateActiveEditorBorders();
        afterTabStripChanged();
    }

    private void loadIntoPane(io.github.rosemoe.sora.widget.CodeEditor pane, FileTab tab) {
        if (pane == null) return;
        // Derived after the tab is in place, at the end of this method.
        try {
            ws.isProgrammaticChange = true;
            pane.setText(tab == null || tab.file == null ? "" : projectManager.readFile(tab.file));
            if (tab != null && tab.file != null) applyEditorLanguage(tab.file, pane);
            pane.setEditable(tab != null && tab.file != null);
        } catch (IOException e) {
            pane.setText("");
            pane.setEditable(false);
        } finally {
            ws.isProgrammaticChange = false;
        }
        restoreWireDraft(pane,tab);
            syncPanePreview(pane == ws.editor);
    }

    /** Run uses current project buffers only after every modified file has committed. */
    private boolean saveWireCodeForRun() {
        try {
            for(FileTab tab:ws.allTabs()) {
                if(!tab.isModified||!projectManager.isEmbeddedFile(tab.file))continue;
                boolean shown=tab==ws.leftTab||tab==ws.rightTab;
                if(!shown&&!tab.recoveredDraft) {
                    Toast.makeText(this,"Відкрийте й збережіть змінений файл: "+tab.file.getName(),Toast.LENGTH_LONG).show();return false;
                }
                TabsAdapter tabs=ws.tabsLeft.getTabs().contains(tab)?ws.tabsLeft:ws.tabsRight;
                int index=tabs.getTabs().indexOf(tab);
                if(index<0||!saveTab(tabs,index)||tab.isModified)return false;
            }
            return true;
        }catch(IOException error){Toast.makeText(this,"Не вдалося зберегти код: "+error.getMessage(),Toast.LENGTH_LONG).show();return false;}
    }

    private void saveCurrentFile() {
        int idx = ws.tabs().getActiveIndex();
        if (idx < 0) return;
        if (saveTab(idx)) {
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Writes the tab's editor contents to disk.
     *
     * @return false when the file is locked read-only or the write failed
     */
    private boolean saveTab(int index) {
        return saveTab(ws.tabs(),index);
    }

    private boolean saveTab(TabsAdapter tabs,int index) {
        FileTab tab = tabs.getTabs().get(index);
        if (isFileReadOnly(tab.file)) {
            Toast.makeText(this, getString(R.string.read_only_save_blocked, tab.file.getName()),
                    Toast.LENGTH_LONG).show();
            return false;
        }
        CodeEditor targetEd = null;
        if (tab == ws.leftTab) targetEd = ws.editor;
        else if (tab == ws.rightTab) targetEd = ws.editor2;
        if(targetEd==null&&tab.recoveredDraft) {
            try {
                String draft=com.ccs.wiredroid.wire.RecoveryStore.readDraft(tab.file,2*1024*1024);
                if(draft==null)throw new IOException("Буфер відновлення відсутній");
                projectManager.writeFile(tab.file,draft);tab.recoveredDraft=false;tabs.markModified(index,false);return true;
            }catch(IOException error){Toast.makeText(this,getString(R.string.toast_save_failed,error.getMessage()),Toast.LENGTH_LONG).show();return false;}
        }
        if (targetEd == null) targetEd = ws.activeEditor;

        // The formatters below rebuild the text with '\n', so a file that used
        // CRLF would come out of a save converted — every line changed, and a
        // diff that says so. Remembered here and put back after formatting.
        io.github.rosemoe.sora.text.LineSeparator separator =
                com.ccs.wiredroid.util.LineSeparators.detect(targetEd.getText()).dominant;

        try {
            // This is the explicit Save, so it is the one place a reformat is
            // something the user asked for — and the only remaining reader of
            // the "format on save" switch, which would otherwise be decoration.
            if (tab.file.getName().endsWith(".java")
                    && powerSaving != null && powerSaving.shouldFormatOnSave()) {
                String currentText = targetEd.getText().toString();
                String optimized = com.ccs.wiredroid.util.AutoImportHelper.optimizeImports(currentText);
                String formatted = JavaFormatter.format(optimized, appPrefs.getTabSize());
                if (!formatted.equals(currentText)) {
                    ws.isProgrammaticChange = true;
                    try {
                        setEditorTextPreservingSelection(targetEd, formatted);
                    } finally {
                        ws.isProgrammaticChange = false;
                    }
                }
            } else if (tab.file.getName().endsWith(".xml")) {
                String currentText = targetEd.getText().toString();
                String formatted = com.ccs.wiredroid.util.XmlFormatter.formatXml(currentText);
                if (!formatted.equals(currentText)) {
                    ws.isProgrammaticChange = true;
                    try {
                        setEditorTextPreservingSelection(targetEd, formatted);
                    } finally {
                        ws.isProgrammaticChange = false;
                    }
                }
            }
            String finalContent = targetEd.getText().toString();
            String withSeparator = com.ccs.wiredroid.util.LineSeparators.convert(finalContent, separator);
            if (!withSeparator.equals(finalContent)) {
                // Put it back in the buffer too, so what the status bar reports
                // and what is on disk cannot disagree.
                ws.isProgrammaticChange = true;
                try {
                    setEditorTextPreservingSelection(targetEd, withSeparator);
                } finally {
                    ws.isProgrammaticChange = false;
                }
                finalContent = withSeparator;
            }
            projectManager.writeFile(tab.file, finalContent);
            cancelWireDraft(targetEd);tab.recoveredDraft=false;
            tabs.markModified(index, false);
            if (gitGutter != null) gitGutter.refreshAll();
            com.ccs.wiredroid.util.LocalHistoryManager.saveSnapshot(this, tab.file, finalContent, "Save");
            return true;
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.toast_save_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void saveCurrentToActiveTab() {
        int idx = ws.tabs().getActiveIndex();
        if (idx < 0 || ws.tabs().getTabs().isEmpty()) return;
        FileTab tab = ws.tabs().getTabs().get(idx);
        tab.cursorLine = ws.activeEditor.getCursor().getLeftLine();
        tab.cursorColumn = ws.activeEditor.getCursor().getLeftColumn();
        if (tab.isModified) {
            saveEditorToTab(ws.activeEditor, tab);
        }
    }

    private void saveSessionState() {
        if (projectManager == null || projectManager.getProjectDir() == null) return;
        if (ws.tabs() == null || ws.tabs().getTabs().isEmpty()) return;

        String projectRoot = projectManager.getProjectDir().getAbsolutePath();
        List<String> tabPaths = new ArrayList<>();
        List<Integer> cursorLines = new ArrayList<>();
        List<Integer> cursorCols = new ArrayList<>();

        // Ensure current active tab's position is updated before saving
        int activeIdx = ws.tabs().getActiveIndex();
        if (activeIdx >= 0 && activeIdx < ws.tabs().getTabs().size()) {
            FileTab activeTab = ws.tabs().getTabs().get(activeIdx);
            activeTab.cursorLine = ws.activeEditor.getCursor().getLeftLine();
            activeTab.cursorColumn = ws.activeEditor.getCursor().getLeftColumn();
        }

        for (FileTab tab : ws.allTabs()) {
            if (tab.file != null) {
                tabPaths.add(tab.file.getAbsolutePath());
                cursorLines.add(tab.cursorLine + 1); // 1-indexed for persistence (legacy)
                cursorCols.add(tab.cursorColumn);
            }
        }

        sessionState.save(projectRoot, tabPaths, ws.tabs().getActiveIndex(),
                cursorLines, cursorCols);
    }

    private CodeEditor getEditorForTab(FileTab tab) {
        if (tab == ws.leftTab) return ws.editor;
        if (tab == ws.rightTab) return ws.editor2;
        if (ws.tabs().getActiveTab() == tab) return ws.activeEditor;
        return null;
    }

    // ══════════════════════════════════════════════════════════
    //  Auto-Import
    // ══════════════════════════════════════════════════════════

    private void autoImportBeforeRun() {
        FileTab tab = ws.tabs().getActiveTab();
        if (tab == null || tab.file == null) return;
        if (!tab.file.getName().endsWith(".java")) return;

        String source = ws.activeEditor.getText().toString();
        List<AutoImportHelper.ImportSuggestion> suggestions =
                AutoImportHelper.findMissingImports(this, projectManager.getProjectDir(), source);
        if (!suggestions.isEmpty()) {
            String updated = AutoImportHelper.addImportsAuto(source, suggestions);
            ws.isProgrammaticChange = true;
            try {
                setEditorTextPreservingSelection(ws.activeEditor, updated);
            } finally {
                ws.isProgrammaticChange = false;
            }
            // The change event is suppressed above, so nothing else would ask
            // for a recompute — and every inserted import shifts the lines the
            // hints were measured against.
            scheduleInlayHints(ws.activeEditor, 300L);
            StringBuilder msg = new StringBuilder("Auto-imported:\n");
            for (AutoImportHelper.ImportSuggestion s : suggestions) {
                msg.append("  import ").append(s.fullImport).append(";\n");
            }
            appendConsole(msg.toString(), theme.textDim);
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Markdown Preview
    // ══════════════════════════════════════════════════════════

    private void showMarkdownPreview() {
        FileTab tab = ws.tabs().getActiveTab();
        if (tab == null || tab.file == null) {
            Toast.makeText(this, R.string.toast_no_file_open, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = tab.file.getName().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".md") && !name.endsWith(".markdown")) {
            Toast.makeText(this, R.string.md_only_markdown, Toast.LENGTH_SHORT).show();
            return;
        }

        String content = ws.activeEditor.getText().toString();
        if (content.trim().isEmpty()) {
            Toast.makeText(this, R.string.file_is_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        // Rendered in the editor pane, never in a separate screen. A full-screen
        // activity threw away everything around the file — the tabs, the tree,
        // and in split view the pane the user had put beside it — to show one
        // document that fits perfectly well where the document already is.
        togglePanePreview(content);
    }

    /** The rendered document shown over each pane, created on first use. */
    private MarkdownDocumentView panePreview1;
    private MarkdownDocumentView panePreview2;

    /**
     * Turns preview on or off for the file the user is looking at.
     *
     * <p>The flag goes on the tab, so the answer to "is this rendered" travels
     * with the document. Whether a preview is drawn is then only ever derived
     * from the tab currently in the pane — there is no second place where the
     * two could disagree.</p>
     */
    private void togglePanePreview(String content) {
        FileTab tab = ws.activeTab();
        if (tab == null) return;
        tab.previewMode = !tab.previewMode;
        syncPanePreview(ws.activeEditor == ws.editor);
        if (tab.previewMode) {
            Toast.makeText(this, R.string.md_pane_preview_on, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Makes the pane show what its current tab asks for.
     *
     * <p>Called after anything that changes which file a pane holds. Rendering
     * from the editor's own text rather than from disk keeps the preview honest
     * about unsaved edits.</p>
     */
    private void syncPanePreview(boolean left) {
        io.github.rosemoe.sora.widget.CodeEditor pane = left ? ws.editor : ws.editor2;
        FileTab tab = left ? ws.leftTab : ws.rightTab;
        MarkdownDocumentView view = left ? panePreview1 : panePreview2;
        boolean wanted = tab != null && tab.previewMode && pane != null;

        if (!wanted) {
            if (view != null) view.setVisibility(View.GONE);
            return;
        }

        android.widget.FrameLayout wrapper = left ? ws.wrapperEditor1 : ws.wrapperEditor2;
        if (view == null) {
            view = new MarkdownDocumentView(this);
            wrapper.addView(view, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (left) panePreview1 = view; else panePreview2 = view;
        }
        String content = pane.getText() == null ? "" : pane.getText().toString();
        view.setMarkdown(content, theme, appPrefs.resolveTypeface(), dp(12));
        view.setVisibility(View.VISIBLE);
        view.bringToFront();
    }

    /** Re-derives both panes; cheap, and there are only ever two. */
    private void syncPanePreviews() {
        syncPanePreview(true);
        syncPanePreview(false);
    }

    /**
     * Drops any preview covering a pane and forgets the flag.
     *
     * <p>Used when the split closes: the second pane is going away, so a tab
     * still marked for preview would come back rendered in a pane that no
     * longer exists beside anything.</p>
     */
    private void clearPanePreviews() {
        if (ws.leftTab != null) ws.leftTab.previewMode = false;
        if (ws.rightTab != null) ws.rightTab.previewMode = false;
        if (panePreview1 != null) panePreview1.setVisibility(View.GONE);
        if (panePreview2 != null) panePreview2.setVisibility(View.GONE);
    }

    private void openEditableFile(File file) {
        // Big files are read on a worker: readFile on the main thread is what
        // makes opening one feel like the app has hung.
        if (com.ccs.wiredroid.editor.LargeFilePolicy.readsAsync(file)) {
            openLargeFileAsync(file);
            return;
        }
        openEditableFileNow(file, null);
    }

    /**
     * Reads a large file in the background, then hands the text to the editor.
     *
     * <p>The tab is not created until the text is in hand: a tab that exists
     * while its content is still loading is a tab that can be switched away
     * from, closed, or saved over.</p>
     */
    private void openLargeFileAsync(File file) {
        final long size = file.length();
        Toast.makeText(this, getString(R.string.editor_large_file_loading,
                com.ccs.wiredroid.editor.LargeFilePolicy.describeSize(size)),
                Toast.LENGTH_SHORT).show();
        fileReadWorker.execute(() -> {
            String content;
            String failure = null;
            try {
                content = projectManager.readFile(file);
            } catch (Exception e) {
                content = null;
                failure = e.getMessage();
            }
            final String loaded = content;
            final String error = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (loaded == null) {
                    Toast.makeText(this, getString(R.string.error_cannot_open, error),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                openEditableFileNow(file, loaded);
            });
        });
    }

    private void openEditableFileNow(File file, String preloaded) {
        try {
            String content = preloaded != null ? preloaded : projectManager.readFile(file);
            FileTab tab = new FileTab(file);
            ws.tabs().addTab(tab);
            int idx = ws.tabs().getTabs().size() - 1;
            ws.tabs().setActiveIndex(idx);
            ws.isProgrammaticChange = true;
            ws.activeEditor.setText(content);
            ws.isProgrammaticChange = false;
            applyEditorLanguage(file, ws.activeEditor);

            // Read-only above the top threshold, and said out loud: silently
            // refusing keystrokes is the worst of the three options.
            com.ccs.wiredroid.editor.LargeFilePolicy.Mode mode =
                    com.ccs.wiredroid.editor.LargeFilePolicy.modeFor(file);
            boolean editable = mode != com.ccs.wiredroid.editor.LargeFilePolicy.Mode.READ_ONLY;
            ws.activeEditor.setEditable(editable);
            if (!editable) {
                Toast.makeText(this, getString(R.string.editor_large_file_read_only,
                        com.ccs.wiredroid.editor.LargeFilePolicy.describeSize(file.length())),
                        Toast.LENGTH_LONG).show();
            } else if (mode == com.ccs.wiredroid.editor.LargeFilePolicy.Mode.PLAIN) {
                Toast.makeText(this, getString(R.string.editor_large_file_plain,
                        com.ccs.wiredroid.editor.LargeFilePolicy.describeSize(file.length())),
                        Toast.LENGTH_SHORT).show();
            }
            activeStrip().scrollToPosition(idx);
            updateStatusFileName(file);
            fileTreeAdapter.setActiveFile(file);
            if (ws.activeEditor == ws.editor) ws.leftTab = tab; else ws.rightTab = tab;
            restoreWireDraft(ws.activeEditor,tab);

            // Add preview button for HTML/MD/SVG files
            updatePreviewButton(file);
            refreshBookmarkMarkers();
            updateEditorChrome();
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.error_cannot_open, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void updatePreviewButton(File file) {
        if (file == null) return;
        String name = file.getName().toLowerCase(Locale.ROOT);
        boolean canPreview = name.endsWith(".html") || name.endsWith(".htm")
                || name.endsWith(".md") || name.endsWith(".markdown")
                || name.endsWith(".svg");

        // Find or create preview button in toolbar
        TextView previewBtn = toolbar.findViewById(R.id.toolbarPreview);
        if (previewBtn == null) {
            previewBtn = new TextView(this);
            previewBtn.setId(R.id.toolbarPreview);
            previewBtn.setText("👁");
            previewBtn.setTextSize(18);
            previewBtn.setTextColor(theme.text);
            previewBtn.setPadding(dp(8), dp(4), dp(4), dp(4));
            previewBtn.setBackgroundResource(android.R.drawable.list_selector_background);
            toolbar.addView(previewBtn, new Toolbar.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            previewBtn.setOnClickListener(v -> togglePreview());
        }
        previewBtn.setVisibility(canPreview ? View.VISIBLE : View.GONE);
    }

    private boolean previewMode = false;

    private void togglePreview() {
        FileTab tab = ws.tabs().getActiveTab();
        if (tab == null || tab.file == null) return;
        String name = tab.file.getName().toLowerCase(Locale.ROOT);

        if (previewMode) {
            // Switch back to editor
            ws.activeEditor.setVisibility(View.VISIBLE);
            if (bottomPanelMode == PANEL_RUN && consoleManager != null) {
                consoleManager.setVisible(true);
            }
            previewMode = false;
        } else {
            // Switch to preview
            String content = ws.activeEditor.getText().toString();
            if (content.trim().isEmpty()) {
            Toast.makeText(this, R.string.file_is_empty, Toast.LENGTH_SHORT).show();
                return;
            }

            if (name.endsWith(".html") || name.endsWith(".htm")) {
                WebViewPreviewActivity.launch(this, tab.file);
            } else if (name.endsWith(".md") || name.endsWith(".markdown")) {
                showMarkdownPreview();
            } else if (name.endsWith(".html") || name.endsWith(".htm")) {
                HtmlViewerActivity.launch(this, tab.file);
            } else if (name.endsWith(".svg")) {
                SvgViewerActivity.launch(this, tab.file);
            }
        }
    }

    private void showAutoImportDialog() {
        FileTab tab = ws.tabs().getActiveTab();
        if (tab == null || tab.file == null) {
            Toast.makeText(this, R.string.toast_no_file_open, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!tab.file.getName().endsWith(".java")) {
            Toast.makeText(this, R.string.auto_import_java_only, Toast.LENGTH_SHORT).show();
            return;
        }

        String source = ws.activeEditor.getText().toString();
        AutoImportHelper.analyzeAndSuggest(this, projectManager.getProjectDir(),
                ws.activeEditor.getText(), suggestions -> {
                    if (suggestions.isEmpty()) {
                        Toast.makeText(this, R.string.auto_import_none, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String[] items = new String[suggestions.size()];
                    boolean[] checked = new boolean[suggestions.size()];
                    for (int i = 0; i < suggestions.size(); i++) {
                        items[i] = "import " + suggestions.get(i).fullImport + ";";
                        checked[i] = true;
                    }
                    newRoundedDialog()
                            .setTitle(R.string.auto_import_title)
                            .setMultiChoiceItems(items, checked, (d, w, isChecked) -> checked[w] = isChecked)
                            .setPositiveButton(R.string.auto_import_add, (d, w) -> {
                                List<AutoImportHelper.ImportSuggestion> toAdd = new ArrayList<>();
                                for (int i = 0; i < suggestions.size(); i++) {
                                    if (checked[i]) toAdd.add(suggestions.get(i));
                                }
                                if (!toAdd.isEmpty()) {
                                    String updated = AutoImportHelper.addImportsAuto(
                                            ws.activeEditor.getText().toString(), toAdd);
                                    ws.isProgrammaticChange = true;
                                    ws.activeEditor.setText(updated);
                                    ws.isProgrammaticChange = false;
                                    int idx = ws.tabs().getActiveIndex();
                                    if (idx >= 0) ws.tabs().markModified(idx, true);
                                }
                            })
                            .setNegativeButton(R.string.dialog_cancel, null)
                            .show();
                });
    }

    // ══════════════════════════════════════════════════════════
    //  JSON / XML Viewer

    private void showFormattedView() {
        FileTab tab = ws.tabs().getActiveTab();
        if (tab == null || tab.file == null) {
            Toast.makeText(this, R.string.toast_no_file_open, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = tab.file.getName().toLowerCase(java.util.Locale.ROOT);
        boolean isJson = JsonXmlFormatter.isJsonFile(name);
        boolean isXml = JsonXmlFormatter.isXmlFile(name);

        if (!isJson && !isXml) {
            Toast.makeText(this, R.string.formatted_only_json_xml, Toast.LENGTH_SHORT).show();
            return;
        }

        String content = ws.activeEditor.getText().toString();
        if (content.trim().isEmpty()) {
            Toast.makeText(this, "File is empty", Toast.LENGTH_SHORT).show();
            return;
        }

        android.text.SpannableStringBuilder formatted;
        if (isJson) {
            formatted = JsonXmlFormatter.formatJson(content);
        } else {
            formatted = JsonXmlFormatter.formatXml(content);
        }

        TextView formattedOutput = new TextView(this);
        formattedOutput.setText(formatted);
        formattedOutput.setTypeface(new AppPreferences(this).resolveTypeface());
        formattedOutput.setTextSize(13);
        formattedOutput.setBackgroundColor(theme.consoleBg);
        formattedOutput.setTextColor(theme.consoleText);
        formattedOutput.setPadding(24, 16, 24, 16);
        formattedOutput.setTextIsSelectable(true);
        formattedOutput.setHorizontallyScrolling(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(formattedOutput);
        scroll.setBackgroundColor(theme.consoleBg);

        newRoundedDialog()
                .setTitle(name + " — formatted")
                .setView(scroll)
                .setPositiveButton(R.string.copy_button, (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("formatted", content));
                    Toast.makeText(this, R.string.toast_copied_clipboard, Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(R.string.apply_to_editor, (d, w) -> {
                    String pretty;
                    if (isJson) {
                        try {
                            org.json.JSONObject obj = new org.json.JSONObject(content);
                            pretty = obj.toString(2);
                        } catch (Exception e1) {
                            try {
                                org.json.JSONArray arr = new org.json.JSONArray(content);
                                pretty = arr.toString(2);
                            } catch (Exception e2) {
                                pretty = content;
                            }
                        }
                    } else {
                        pretty = content;
                    }
                    ws.isProgrammaticChange = true;
                    ws.activeEditor.setText(pretty);
                    ws.isProgrammaticChange = false;
                    int idx = ws.tabs().getActiveIndex();
                    if (idx >= 0) ws.tabs().markModified(idx, true);
                })
                .setNegativeButton(R.string.close_button, null)
                .show();
    }

    private void showLocalHistoryDialog() {
        FileTab activeTab = ws.tabs().getActiveTab();
        if (activeTab == null || activeTab.file == null) {
            Toast.makeText(this, R.string.toast_no_file_open, Toast.LENGTH_SHORT).show();
            return;
        }
        com.ccs.wiredroid.ui.LocalHistoryDialog.show(this, theme, activeTab.file,
                ws.activeEditor == null ? "" : ws.activeEditor.getText().toString(), content -> {
            if (ws.activeEditor != null) {
                ws.activeEditor.setText(content);
                saveCurrentFile();
            }
        });
    }

    private void formatCurrentFile() {
        FileTab tab = ws.tabs().getActiveTab();
        if (tab == null || tab.file == null) {
            Toast.makeText(this, R.string.toast_no_file_open, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = tab.file.getName().toLowerCase(java.util.Locale.ROOT);
        if (JsonXmlFormatter.isJsonFile(name) || JsonXmlFormatter.isXmlFile(name)) {
            showFormattedView();
            return;
        }
        if (!tab.file.getName().endsWith(".java")) {
            return;
        }
        String currentText = ws.activeEditor.getText() != null ? ws.activeEditor.getText().toString() : "";
        if (currentText.isEmpty()) return;

        String optimized = com.ccs.wiredroid.util.AutoImportHelper.optimizeImports(currentText);
        String formatted = JavaFormatter.format(optimized, appPrefs.getTabSize());
        if (!formatted.equals(currentText)) {
            ws.isProgrammaticChange = true;
            try {
                setEditorTextPreservingSelection(ws.activeEditor, formatted);
            } finally {
                ws.isProgrammaticChange = false;
            }
            int idx = ws.tabs().getActiveIndex();
            if (idx >= 0) {
                ws.tabs().markModified(idx, true);
                if (powerSaving.shouldAutoSave()) {
                    scheduleAutoSave(ws.activeEditor, tab, idx);
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Run
    // ══════════════════════════════════════════════════════════

    private void openWireWorkspace(boolean run) {
        saveCurrentFile();
        if (wireDrawer != null) wireDrawer.show(run);
    }

    private void runCurrentFile() {
        if (com.ccs.wiredroid.BuildConfig.EMBEDDED_EDITION) {openWireWorkspace(true);return;}
        if (com.ccs.wiredroid.wire.WireProject.isCircuit(projectManager.getProjectDir())) {
            openWireWorkspace(true);
            return;
        }
        if (isRunning) return;
        FileTab activeTab = ws.tabs().getActiveTab();
        if (activeTab == null) {
            Toast.makeText(this, R.string.toast_no_file_open, Toast.LENGTH_SHORT).show();
            return;
        }

        saveCurrentToActiveTab();
        if (activeTab != null && activeTab.file != null) {
            com.ccs.wiredroid.util.LocalHistoryManager.saveSnapshot(this, activeTab.file, ws.activeEditor.getText().toString(), "Compile");
        }

        String fileName = activeTab.file.getName().toLowerCase(Locale.ROOT);

        // SQL files — run against project database
        if (fileName.endsWith(".sql")) {
            runSqlFile(activeTab.file);
            return;
        }

        // Kotlin files — run via Kotlin compiler
        if (fileName.endsWith(".kt")) {
            runKotlinFile(activeTab.file);
            return;
        }

        // Groovy, Clojure and Scala bring their own compilers and run on the
        // embedded Java SE runtime rather than on ART.
        if (com.ccs.wiredroid.langrt.JvmLanguage.of(fileName) != null) {
            runJvmLanguageFile(activeTab.file);
            return;
        }

        if (activeTab.file.getName().endsWith(".java")) {
            autoImportBeforeRun();
        }

        File currentProject = projectManager.getProjectDir();
        boolean isScratch = com.ccs.wiredroid.scratch.ScratchManager.isScratchFile(this, activeTab.file);
        if (isScratch) {
            currentProject = null;
        }
        boolean javaSeMode = com.ccs.wiredroid.project.ProjectRuntime.isJavaSe(currentProject);
        String currentSourceText = ws.activeEditor.getText().toString();
        boolean isCurrentTestFile = com.ccs.wiredroid.project.ProjectScanner.isTestFile(activeTab.file, currentSourceText);

        if (debugCoordinator != null && !debugCoordinator.isDebugging()) {
            debugCoordinator.showDebugTabs(false);
        }

        if (testPanelManager != null && !isCurrentTestFile) {
            testPanelManager.showConsole();
        }

        if (com.ccs.wiredroid.project.BuildSystem.isBuildable(currentProject)) {
            setRunning(true);
            if (consoleManager != null) consoleManager.clear();
            switchBottomPanel(PANEL_RUN);
            com.ccs.wiredroid.project.BuildSystem.Kind buildKind =
                    com.ccs.wiredroid.project.BuildSystem.detect(projectManager.getProjectDir());
            appendConsole(getString(R.string.console_build_run,
                    com.ccs.wiredroid.project.BuildSystem.displayName(buildKind)), theme.textDim);
            String buildRunConfig = com.ccs.wiredroid.tools.compilers.RunConfig.from(this).describe();
            if (!buildRunConfig.isEmpty()) appendConsole("   " + buildRunConfig, theme.textDim);
            try {
                com.ccs.wiredroid.project.BuildSystem.Model buildModel =
                        com.ccs.wiredroid.project.BuildSystem.model(projectManager.getProjectDir());
                for (String warning : buildModel.warnings) {
                    appendConsole("   ⚠ " + warning, theme.errorText);
                }
                PomModel pom = buildModel.pom;
                ProjectCompiler.Callback projectRunCallback = new ProjectCompiler.Callback() {
                    @Override
                    public void onOutput(String chunk) {
                        // Straight to the console as the program writes it, so a
                        // prompt is on screen before the program blocks reading
                        // the answer to it.
                        streamedOutput = true;
                        appendProgramOutput(chunk);
                    }

                            @Override
                            public void onProgress(String msg) {
                                appendConsole("   " + msg, theme.textDim);
                            }

                            @Override
                            public void onResult(String output) {
                                setRunning(false);
                                appendConsole("", theme.accent);
                                appendConsole(getString(R.string.console_output_separator), theme.accent);
                                if (output == null || output.trim().isEmpty()) {
                                    appendConsole(getString(R.string.console_build_success), theme.successText);
                                    refreshProblemsMergedAsync();
                                    return;
                                }
                                boolean isTestOutput = output.contains("Testing started at")
                                        || output.contains("Process finished with exit code")
                                        || output.contains("tests passed")
                                        || output.contains("Tests failed");
                                if (isTestOutput && testPanelManager != null
                                        && !structuredTestResultsShown) {
                                    testPanelManager.displayRawOutput(output);
                                }
                                boolean err = output.startsWith("Compilation Error")
                                        || output.startsWith("Execution Exception")
                                        || output.startsWith("System Error")
                                        || output.startsWith("Error:")
                                        || output.contains("Process finished with exit code 1");
                                // Already on screen if it was streamed as it ran.
                                if (!streamedOutput) {
                                    appendConsole(output.trim(),
                                            err ? theme.errorText : theme.consoleText);
                                }
                                if (!err && !isTestOutput) {
                                    appendConsole("\n" + getString(R.string.console_build_success), theme.successText);
                                }
                                boolean compilationFailed = output.startsWith("Compilation Error");
                                if (!compilationFailed) {
                                    refreshProblemsMergedAsync();
                                }
                            }

                            @Override
                            public void onProblems(List<ProblemItem> problems) {
                                problemsAdapter.setItems(problems);
                            }

                            @Override
                            public void onTestResults(
                                    List<com.ccs.wiredroid.maven.MavenTestRunner.TestClassResult> results,
                                    int totalTests, int passedTests, int failedTests,
                                    int skippedTests, long durationMs) {
                                if (testPanelManager == null) return;
                                // The runner's own objects, so a failure resolves
                                // to a file and a line from the real stack trace
                                // instead of whatever survived being printed.
                                testPanelManager.displayReport(
                                        com.ccs.wiredroid.testrunner.TestReportParser
                                                .fromMavenResults(results));
                                structuredTestResultsShown = true;
                            }
                        };
                if (isCurrentTestFile) {
                    if (javaSeMode) {
                        ProjectCompiler.javaSeTestRun(this, currentProject, pom, activeTab.file, projectRunCallback);
                    } else {
                        ProjectCompiler.mavenTestRun(this, currentProject, pom, projectRunCallback);
                    }
                } else if (javaSeMode) {
                    ProjectCompiler.javaSeCompileAndRun(this, currentProject, pom,
                            projectRunCallback);
                } else {
                    ProjectCompiler.mavenCompileAndRun(this, currentProject, pom,
                            projectRunCallback);
                }
            } catch (Exception e) {
                setRunning(false);
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            return;
        }

        setRunning(true);
        if (consoleManager != null) consoleManager.clear();
        switchBottomPanel(PANEL_RUN);
        appendConsole(getString(R.string.console_running_file, activeTab.file.getName()), theme.textDim);
        String runConfigSummary = com.ccs.wiredroid.tools.compilers.RunConfig.from(this).describe();
        if (!runConfigSummary.isEmpty()) appendConsole("   " + runConfigSummary, theme.textDim);

        ProjectCompiler.Callback sourceRunCallback = new ProjectCompiler.Callback() {
                    @Override
                    public void onOutput(String chunk) {
                        // Straight to the console as the program writes it, so a
                        // prompt is on screen before the program blocks reading
                        // the answer to it.
                        streamedOutput = true;
                        appendProgramOutput(chunk);
                    }

                    @Override
                    public void onProgress(String msg) {
                        appendConsole("   " + msg, theme.textDim);
                    }

                    @Override
                    public void onResult(String output) {
                        setRunning(false);
                        appendConsole("", theme.accent);
                        appendConsole(getString(R.string.console_output_separator), theme.accent);
                        if (output == null || output.trim().isEmpty()) {
                            appendConsole(getString(R.string.console_process_exit_ok), theme.successText);
                            return;
                        }
                        boolean isTestOutput = output.contains("Testing started at")
                                || output.contains("Process finished with exit code")
                                || output.contains("tests passed")
                                || output.contains("Tests failed");
                        if (isTestOutput && testPanelManager != null && !structuredTestResultsShown) {
                            // Fallback for the single-source test path, which has
                            // no result objects to hand over.
                            testPanelManager.displayRawOutput(output);
                        }
                        boolean isError = output.startsWith("Compilation Error")
                                || output.startsWith("Execution Exception")
                                || output.startsWith("System Error")
                                || output.startsWith("Error:")
                                || output.contains("Process finished with exit code 1");
                        // Already on screen if it was streamed as it ran.
                        if (!streamedOutput) {
                            appendConsole(output.trim(),
                                    isError ? theme.errorText : theme.consoleText);
                        }
                        if (!isError && !isTestOutput) {
                            appendConsole("\n" + getString(R.string.console_process_exit_ok), theme.successText);
                        }
                    }

                    @Override
                    public void onProblems(List<ProblemItem> problems) {
                        problemsAdapter.setItems(problems);
                    }
                };
        if (isCurrentTestFile) {
            if (javaSeMode) {
                ProjectCompiler.runJavaSeSingleTestSource(this, currentSourceText,
                        activeTab.file, currentProject, sourceRunCallback);
            } else {
                ProjectCompiler.runSingleSource(this, currentSourceText,
                        activeTab.file, currentProject, sourceRunCallback);
            }
        } else if (javaSeMode) {
            ProjectCompiler.runJavaSeSingleSource(this, currentSourceText,
                    activeTab.file, currentProject, sourceRunCallback);
        } else {
            ProjectCompiler.runSingleSource(this, currentSourceText,
                    activeTab.file, currentProject, sourceRunCallback);
        }
    }

    private void syncDependencies() {
        if (mavenDelegate != null) mavenDelegate.syncDependencies();
    }

    /**
     * Names the panel after the project's own build system.
     *
     * <p>A Gradle user searching the palette types "Gradle"; an entry that only
     * ever said "Maven" is one they never find.</p>
     */
    private String buildPanelLabel() {
        File dir = projectManager == null ? null : projectManager.getProjectDir();
        com.ccs.wiredroid.project.BuildSystem.Kind kind =
                com.ccs.wiredroid.project.BuildSystem.detect(dir);
        if (kind == com.ccs.wiredroid.project.BuildSystem.Kind.NONE) {
            return getString(R.string.build_panel_open);
        }
        return com.ccs.wiredroid.project.BuildSystem.displayName(kind);
    }

    /**
     * Builds the build-tool drawer the first time it is asked for.
     *
     * <p>It used to be inflated with the main layout, which is already the most
     * expensive thing this screen does. A panel most sessions never open has no
     * business costing anything at startup.</p>
     */
    private void setupMavenPanel() {
        View panelRoot = findViewById(R.id.mavenPanelRoot);
        if (panelRoot == null) {
            if (drawerLayout == null) return;
            panelRoot = getLayoutInflater().inflate(R.layout.drawer_maven, drawerLayout, false);
            androidx.drawerlayout.widget.DrawerLayout.LayoutParams lp =
                    new androidx.drawerlayout.widget.DrawerLayout.LayoutParams(
                            Math.round(280 * getResources().getDisplayMetrics().density),
                            androidx.drawerlayout.widget.DrawerLayout.LayoutParams.MATCH_PARENT);
            lp.gravity = GravityCompat.END;
            drawerLayout.addView(panelRoot, lp);
        }
        mavenPanel = new MavenToolPanel(this, panelRoot, new MavenToolPanel.Callback() {
            @Override public File getProjectDir() {
                return projectManager == null ? null : projectManager.getProjectDir();
            }
            @Override public AppTheme getTheme() { return theme; }
            @Override public void runPhase(String phase) {
                if (mavenDelegate != null) mavenDelegate.runPhase(phase);
            }
            @Override public void syncDependencies() { MainActivity.this.syncDependencies(); }
            @Override public void closeDrawer() {
                if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.END);
            }
        });
    }

    /** Opens the Maven drawer, re-reading the build file so it is never stale. */
    private void openMavenPanel() {
        if (mavenPanel == null) setupMavenPanel();
        if (mavenPanel == null || drawerLayout == null) return;
        mavenPanel.rebuild();
        drawerLayout.openDrawer(GravityCompat.END);
    }

    private void mavenPackage() {
        if (mavenDelegate != null) mavenDelegate.mavenPackage();
    }

    private void mavenTestCompile() {
        if (mavenDelegate != null) mavenDelegate.mavenTestCompile();
    }

    private void mavenTestRun() {
        if (mavenDelegate != null) mavenDelegate.mavenTestRun();
    }

    private void mavenClean() {
        if (mavenDelegate != null) mavenDelegate.mavenClean();
    }

    private void mavenInstall() {
        if (mavenDelegate != null) mavenDelegate.mavenInstall();
    }


    private void showCreateCppModuleDialog() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(32, 24, 32, 24);

        EditText input = newEditForDialog("Module/Library name (e.g. native-lib)");
        layout.addView(input);

        android.widget.RadioGroup rg = new android.widget.RadioGroup(this);
        android.widget.RadioButton rbC = new android.widget.RadioButton(this);
                    rbC.setText(R.string.cpp_module_c_option);
        rbC.setTextColor(theme.text);
        rbC.setChecked(true);
        android.widget.RadioButton rbCpp = new android.widget.RadioButton(this);
                    rbCpp.setText(R.string.cpp_module_cpp_bundled_option);
        rbCpp.setTextColor(theme.text);
        rg.addView(rbC);
        rg.addView(rbCpp);
        layout.addView(rg);

        newRoundedDialog()
                .setTitle(R.string.menu_create_cpp_module)
                .setView(layout)
                .setPositiveButton(R.string.dialog_create, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    String cleanName = name.replaceAll("[^a-zA-Z0-9_-]", "_");
                    
                    boolean isCpp = rbCpp.isChecked();
                    String ext = isCpp ? ".cpp" : ".c";
                    
                    File root = projectManager.getProjectDir();
                    File cppDir = new File(root, "src/main/cpp");
                    if (!cppDir.exists()) cppDir.mkdirs();
                    
                    File cppFile = new File(cppDir, cleanName + ext);
                    if (cppFile.exists()) {
                        Toast.makeText(this, R.string.cpp_module_exists, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    try {
                        String pkg = ProjectLayoutHelper.mainPackageName(root);
                        String cMethodName = "Java_" + pkg.replace('.', '_') + "_App_stringFromJNI_" + cleanName;
                        
                        String cTemplate;
                        if (isCpp) {
                            cTemplate = "#include <jni.h>\n#include <string>\n\n"
                                    + "extern \"C\" JNIEXPORT jstring JNICALL\n"
                                    + cMethodName + "(JNIEnv *env, jobject thiz) {\n"
                                    + "    std::string hello = \"Hello from C++ code in \" + std::string(\"" + cleanName + "\") + \"!\";\n"
                                    + "    return env->NewStringUTF(hello.c_str());\n"
                                    + "}\n";
                        } else {
                            cTemplate = "#include <jni.h>\n\n"
                                    + "JNIEXPORT jstring JNICALL\n"
                                    + cMethodName + "(JNIEnv *env, jobject thiz) {\n"
                                    + "    return (*env)->NewStringUTF(env, \"Hello from C code in " + cleanName + "!\");\n"
                                    + "}\n";
                        }
                        
                        projectManager.writeFile(cppFile, cTemplate);
                        
                        // Let's check App.java to inject static JNI loaders
                        File mainJavaPkgDir = ProjectLayoutHelper.mainJavaPackageDir(root);
                        File appJavaFile = new File(mainJavaPkgDir, "App.java");
                        if (appJavaFile.exists()) {
                            String javaCode = projectManager.readFile(appJavaFile);
                            if (!javaCode.contains("System.loadLibrary(\"" + cleanName + "\")")) {
                                String loadBlock = "    static {\n"
                                        + "        try {\n"
                                        + "            System.loadLibrary(\"" + cleanName + "\");\n"
                                        + "        } catch (UnsatisfiedLinkError e) {\n"
                                        + "            System.err.println(\"WARNING: native library '" + cleanName + "' not found.\\n\"\n"
                                        + "                + \"Run the project first to compile C sources.\");\n"
                                        + "        }\n"
                                        + "    }\n\n"
                                        + "    public native String stringFromJNI_" + cleanName + "();\n\n";
                                
                                int classBodyIdx = javaCode.indexOf("public class App {");
                                if (classBodyIdx != -1) {
                                    int insertPos = javaCode.indexOf('{', classBodyIdx) + 1;
                                    String updatedJava = javaCode.substring(0, insertPos) + "\n" + loadBlock + javaCode.substring(insertPos);
                                    if (updatedJava.contains("System.out.println(\"Hello from \" + App.class.getPackage().getName());")) {
                                        updatedJava = updatedJava.replace(
                                            "System.out.println(\"Hello from \" + App.class.getPackage().getName());",
                                            "System.out.println(\"Hello from \" + App.class.getPackage().getName());\n        System.out.println(new App().stringFromJNI_" + cleanName + "());"
                                        );
                                    }
                                    projectManager.writeFile(appJavaFile, updatedJava);
                                    
                                    // Update editor content if App.java is currently active
                                    int activeIdx = ws.tabs().indexOfFile(appJavaFile);
                                    if (activeIdx >= 0 && activeIdx == ws.tabs().getActiveIndex()) {
                                        ws.isProgrammaticChange = true;
                                        ws.activeEditor.setText(updatedJava);
                                        ws.isProgrammaticChange = false;
                                    }
                                }
                            }
                        }
                        
                        refreshFileTree();
                        openFile(cppFile);
                        if (appJavaFile.exists()) {
                            openFile(appJavaFile);
                        }
                        Toast.makeText(this, R.string.cpp_module_created, Toast.LENGTH_SHORT).show();
                    } catch (IOException e) {
                        Toast.makeText(this, getString(R.string.cpp_module_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /** Result code for {@link NewProjectActivity}; 4010 is REQ_LOAD_MAPPING. */
    private static final int REQ_NEW_PROJECT = 4011;

    private void openNewProjectWizard() {
        saveCurrentToActiveTab();
        NewProjectActivity.launchForResult(this, REQ_NEW_PROJECT);
    }

    private EditText newEditForDialog(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(theme.textDim);
        e.setTextColor(theme.text);
        e.setBackgroundColor(Colors.blend(theme.bg, theme.text, 0.05f));
        e.setPadding(32, 16, 32, 16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        e.setLayoutParams(lp);
        return e;
    }

    // ══════════════════════════════════════════════════════════
    //  Console
    // ══════════════════════════════════════════════════════════

    private void appendConsole(String text, int color) {
        appendConsole(text, color, true);
    }

    private void appendConsole(String text, int color, boolean newline) {
        if (consoleManager != null) {
            consoleManager.appendConsole(text, color, newline);
        }
    }

    /** Program output, so a colour it set stays on for the lines that follow. */
    private void appendProgramOutput(String chunk) {
        if (consoleManager != null) {
            consoleManager.appendProgramOutput(chunk, theme.consoleText);
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Find & Replace
    // ══════════════════════════════════════════════════════════

    private void toggleFindBar() {
        if (findReplaceController != null) findReplaceController.toggleFindBar();
    }

    private void hideFindBar() {
        if (findReplaceController != null) findReplaceController.hideFindBar();
    }

    // ══════════════════════════════════════════════════════════
    //  File Manager
    // ══════════════════════════════════════════════════════════

    private void refreshFileTree() {
        if (fileTreeController != null) fileTreeController.refreshFileTree();
    }

    private void showNewFileDialog() {
        if (fileTreeController != null) fileTreeController.showNewFileDialog();
    }

    private void showFolderContextMenu(File folder) {
        if (fileTreeController != null) fileTreeController.showFolderContextMenu(folder);
    }

    private void showFileContextMenu(File file) {
        if (fileTreeController != null) fileTreeController.showFileContextMenu(file);
    }

    private void showRenameDialog(File file) {
        if (fileTreeController != null) fileTreeController.showRenameDialog(file);
    }

    private void showDeleteDialog(File file) {
        if (fileTreeController != null) fileTreeController.showDeleteDialog(file);
    }

    private void pasteFileToFolder(File folder) {
        if (fileTreeController != null) fileTreeController.pasteFileToFolder(folder);
    }

    private void createArchiveFromFolder(File folder) {
        if (fileTreeController != null) fileTreeController.createArchiveFromFolder(folder);
    }

    private void archiveFolderToUri(Uri uri) {
        if (fileTreeController != null) fileTreeController.archiveFolderToUri(uri);
    }

    // ══════════════════════════════════════════════════════════
    //  Settings + new features
    // ══════════════════════════════════════════════════════════

    private void openWebViewPreview() {
        // If there's a current HTML file open, preview it
        FileTab tab = ws.tabs().getActiveTab();
        if (tab != null && tab.file != null) {
            String name = tab.file.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".html") || name.endsWith(".htm")) {
                WebViewPreviewActivity.launch(this, tab.file);
                return;
            }
            // For any other file, try to wrap it in an HTML page
            if (name.endsWith(".css") || name.endsWith(".js")) {
                try {
                    String content = projectManager.readFile(tab.file);
                    String html = "<!DOCTYPE html><html><head>"
                            + "<style>body{font-family:monospace;margin:20px;background:#1e1e1e;color:#d4d4d4}</style>"
                            + (name.endsWith(".css") ? "<link rel='stylesheet' href='" + tab.file.getName() + "'>" : "")
                            + "</head><body>"
                            + (name.endsWith(".js") ? "<script>" + content + "</script>" : "")
                            + "<pre>" + content.replace("&", "&amp;").replace("<", "&lt;") + "</pre>"
                            + "</body></html>";
                    WebViewPreviewActivity.launch(this, html);
                    return;
                } catch (Exception ignored) {}
            }
        }
        // Default: open empty preview
        String defaultHtml = "<!DOCTYPE html><html><head>"
                + "<style>body{font-family:sans-serif;margin:40px;background:#1e1e1e;color:#d4d4d4}"
                + "h1{color:#569cd6}code{background:#2d2d2d;padding:2px 6px;border-radius:4px}</style>"
                + "</head><body>"
                + "<h1>WebView Preview</h1>"
                + "<p>Open an <code>.html</code> file to preview it here.</p>"
                + "<p>Or use the URL bar above to load any URL.</p>"
                + "</body></html>";
        WebViewPreviewActivity.launch(this, defaultHtml);
    }

    private void openSettings() {
        // Settings shows the project-scoped entries only for a project it was
        // handed; the editor always has one.
        SettingsActivity.launch(this, REQ_SETTINGS,
                projectManager == null ? null : projectManager.getProjectDir());
    }

    private void openLibraryManager() {
        if (!com.ccs.wiredroid.project.BuildSystem.isBuildable(projectManager.getProjectDir())) {
            Toast.makeText(this, R.string.toast_no_build_script, Toast.LENGTH_SHORT).show();
            return;
        }
        saveCurrentToActiveTab();
        LibraryManagerActivity.launch(this, projectManager.getProjectDir(), REQ_LIB_MANAGER);
    }

    private void openClassBrowser() {
        String path = projectManager.hasStandardLayout()
                ? projectManager.getProjectDir().getAbsolutePath() : null;
        ClassBrowserActivity.launch(this, path);
    }

    private void openCallGraph() {
        File projectDir = projectManager.getProjectDir();
        if (projectDir == null) {
            Toast.makeText(this, R.string.call_graph_no_project, Toast.LENGTH_SHORT).show();
            return;
        }
        CallGraphActivity.launch(this, projectDir);
    }

    private void openCallGraphFromBytecode(String className) {
        File projectDir = projectManager != null ? projectManager.getProjectDir() : null;
        if (projectDir == null) {
            Toast.makeText(this, R.string.call_graph_no_project, Toast.LENGTH_SHORT).show();
            return;
        }
        if (className != null) {
            CallGraphActivity.launch(this, projectDir, className, null);
        } else {
            CallGraphActivity.launch(this, projectDir);
        }
    }

    private void loadProGuardMapping() {
        if (bytecodeManager != null) {
            bytecodeManager.loadProGuardMapping();
        }
    }

    private void loadMappingResult(Uri uri) {
        if (bytecodeManager != null) {
            bytecodeManager.loadMappingResult(uri);
        }
    }

    private void openDecompiledClass(File file, String className) {
        try {
            String content = projectManager.readFile(file);
            FileTab tab = new FileTab(file);
            ws.tabs().addTab(tab);
            int idx = ws.tabs().getTabs().size() - 1;
            ws.tabs().setActiveIndex(idx);
            ws.isProgrammaticChange = true;
            ws.activeEditor.setText(content);
            ws.isProgrammaticChange = false;
            ws.activeEditor.setEditable(false);
            activeStrip().scrollToPosition(idx);
            updateStatusFileName(file);
            if (ws.activeEditor == ws.editor) {
                ws.leftTab = tab;
            } else {
                ws.rightTab = tab;
            }
            Toast.makeText(this, getString(R.string.class_browser_opened, className), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_NEW_PROJECT) {
            if (resultCode == RESULT_OK && data != null) {
                String scratch = data.getStringExtra(NewProjectActivity.EXTRA_SCRATCH_PATH);
                if (scratch != null) {
                    // A scratch is a file, not a project. Opening it must leave
                    // the current project — and every panel bound to it — alone,
                    // so this returns before the restart below.
                    File file = new File(scratch);
                    if (file.isFile()) openFile(file);
                    return;
                }
                String path = data.getStringExtra(NewProjectActivity.EXTRA_PROJECT_PATH);
                if (path != null) {
                    appPrefs.setProjectRoot(path);
                    appPrefs.addRecentProject(path);
                    // A different project root reaches every panel, tab and
                    // index; a clean restart is cheaper than rewiring them.
                    Intent restart = new Intent(this, MainActivity.class);
                    restart.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    if (com.ccs.wiredroid.wire.WireProject.isCircuit(new File(path))) {
                        com.ccs.wiredroid.wire.WireActivity.launch(this, new File(path), false);
                    } else {
                        startActivity(restart);
                    }
                    finish();
                }
            }
            return;
        }
        if (requestCode == REQ_SETTINGS) {
            if (resultCode == RESULT_OK && data != null && data.getBooleanExtra(SettingsActivity.EXTRA_CHANGED, false)) {
                recreate();
                return;
            }
            // Toolbar buttons are a menu concern, so rebuilding the menu is the
            // whole update — cheaper than the recreate the heavier settings need,
            // and it is why those switches do not set the "changed" flag.
            invalidateOptionsMenu();
            return;
        }
        if (requestCode == REQ_LIB_MANAGER) {
            if (resultCode == RESULT_OK) {
                // Reload pom.xml if it's currently open
                FileTab activeTab = ws.tabs().getActiveTab();
                if (activeTab != null && activeTab.file != null && activeTab.file.getName().equals("pom.xml")) {
                    try {
                        String content = projectManager.readFile(activeTab.file);
                        ws.isProgrammaticChange = true;
                        ws.activeEditor.setText(content);
                        ws.isProgrammaticChange = false;
                    } catch (IOException ignored) {}
                }
                syncDependencies();
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null) return;

        // Class Browser — повертає шлях до декомпільованого файлу
        if (requestCode == REQ_CLASS_BROWSER) {
            String filePath = data.getStringExtra(ClassBrowserActivity.RESULT_FILE_PATH);
            String className = data.getStringExtra(ClassBrowserActivity.RESULT_CLASS_NAME);
            if (filePath != null) {
                File file = new File(filePath);
                if (file.exists()) {
                    openDecompiledClass(file, className);
                }
            }
            return;
        }

        // Global search — повертає extras, не URI
        if (requestCode == REQ_GLOBAL_SEARCH) {
            String filePath = data.getStringExtra("file_path");
            int lineNum = data.getIntExtra("line_number", 1);
            if (filePath != null) {
                File file = new File(filePath);
                if (file.exists()) {
                    openFile(file);
                    ws.activeEditor.postDelayed(() -> {
                        if (lineNum > 0) {
                            ws.activeEditor.setSelection(lineNum - 1, 0);
                        }
                    }, 200);
                }
            }
            return;
        }

        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT_FILES) {
            importFilesResult(data);
            return;
        }
        if (uri == null) return;
        if (requestCode == REQ_OPEN_FILE)        importExternalJavaFile(uri);
        else if (requestCode == REQ_SAVE_AS)     writeCurrentEditorToUri(uri);
        else if (requestCode == REQ_EXPORT_PROJ) exportProjectToUri(uri);
        else if (requestCode == REQ_PLAY_MEDIA)  openMediaPlayer(uri);
        else if (requestCode == REQ_LOAD_MAPPING) loadMappingResult(uri);
        else if (requestCode == REQ_ARCHIVE_FOLDER) archiveFolderToUri(uri);
        else if (requestCode == REQ_STORAGE_PERMISSION) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (android.os.Environment.isExternalStorageManager()) {
                    recreate();
                } else {
                    newRoundedDialog()
                            .setTitle(R.string.permission_storage_denied_title)
                            .setMessage(R.string.permission_storage_denied_message)
                            .setPositiveButton(R.string.permission_storage_try_again, (d, w) -> {
                                try {
                                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                                    startActivityForResult(intent, REQ_STORAGE_PERMISSION);
                                } catch (Exception e) {
                                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                                    startActivityForResult(intent, REQ_STORAGE_PERMISSION);
                                }
                            })
                            .setNegativeButton(R.string.permission_storage_exit, (d, w) -> finish())
                            .setCancelable(false)
                            .show();
                }
            }
        }
        return;
    }

    // ── External file & export delegation ─────────────────────

    private void importFilesToProject() {
        if (projectTransfer != null) projectTransfer.importFilesToProject();
    }

    private void importFilesResult(Intent data) {
        if (projectTransfer != null) projectTransfer.importFilesResult(data);
    }

    private void pickFileToOpen() {
        if (projectTransfer != null) projectTransfer.pickFileToOpen();
    }

    private void pickMediaFile() {
        if (projectTransfer != null) projectTransfer.pickMediaFile();
    }

    private void openMediaPlayer(Uri uri) {
        if (projectTransfer != null) projectTransfer.openMediaPlayer(uri);
    }

    private void importExternalJavaFile(Uri uri) {
        if (projectTransfer != null) projectTransfer.importExternalJavaFile(uri);
    }

    private void saveCurrentAs() {
        if (projectTransfer != null) projectTransfer.saveCurrentAs();
    }

    private void writeCurrentEditorToUri(Uri uri) {
        if (projectTransfer != null) projectTransfer.writeCurrentEditorToUri(uri);
    }

    private void exportProjectAsZip() {
        if (projectTransfer != null) projectTransfer.exportProjectAsZip();
    }

    private void exportProjectToUri(Uri uri) {
        if (projectTransfer != null) projectTransfer.exportProjectToUri(uri);
    }

    // ══════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════

    private void updateStatusFileName(File file) {
        statusFileName.setText(file.getName());
        TextView language=findViewById(R.id.statusLanguage);
        if(language!=null){String name=file.getName().toLowerCase(java.util.Locale.ROOT);
            language.setText(name.endsWith(".ino")?"Arduino C++":name.matches(".*\\.(cpp|cc|cxx|hpp)$")?"C++":name.endsWith(".h")?"C/C++":name.endsWith(".c")?"C":name.endsWith(".json")?"JSON":"Текст");}
        // The padlock belongs to the file now shown, so refresh it together.
        updateReadOnlyIndicator();
    }

    private void showKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /** @see Dialogs#rounded */
    private com.google.android.material.dialog.MaterialAlertDialogBuilder newRoundedDialog() {
        return Dialogs.rounded(this);
    }

    // ══════════════════════════════════════════════════════════
    //  Read-only lock
    // ══════════════════════════════════════════════════════════

    /**
     * True when the active file must not be edited — either the user locked it,
     * or the filesystem refuses writes.
     */
    private boolean isActiveFileReadOnly() {
        FileTab tab = ws.tabs() != null ? ws.tabs().getActiveTab() : null;
        if (tab == null || tab.file == null) return false;
        return isFileReadOnly(tab.file);
    }

    private boolean isFileReadOnly(File file) {
        if (file == null) return false;
        if (appPrefs != null && appPrefs.isReadOnly(file.getAbsolutePath())) return true;
        return file.exists() && !file.canWrite();
    }

    /** Flips the user's lock on the active file. */
    private void toggleReadOnly() {
        FileTab tab = ws.tabs() != null ? ws.tabs().getActiveTab() : null;
        if (tab == null || tab.file == null) {
            Toast.makeText(this, R.string.toast_no_file_open, Toast.LENGTH_SHORT).show();
            return;
        }
        File file = tab.file;

        // A file the filesystem itself protects cannot be unlocked from here.
        if (file.exists() && !file.canWrite() && !appPrefs.isReadOnly(file.getAbsolutePath())) {
            Toast.makeText(this, R.string.read_only_filesystem, Toast.LENGTH_LONG).show();
            return;
        }

        boolean nowLocked = !appPrefs.isReadOnly(file.getAbsolutePath());
        if (nowLocked && tab.isModified) {
            // Locking would strand the pending edits, so offer to save them first.
            newRoundedDialog()
                    .setTitle(R.string.read_only_lock_title)
                    .setMessage(R.string.read_only_unsaved_message)
                    .setPositiveButton(R.string.dialog_save_and_lock, (d, w) -> {
                        saveCurrentFile();
                        applyReadOnly(file, true);
                    })
                    .setNeutralButton(R.string.read_only_lock_anyway, (d, w) -> applyReadOnly(file, true))
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show();
            return;
        }
        applyReadOnly(file, nowLocked);
    }

    private void applyReadOnly(File file, boolean locked) {
        appPrefs.setReadOnly(file.getAbsolutePath(), locked);
        updateReadOnlyIndicator();
        applyEditableStateToEditors();
        if (fileTreeAdapter != null) {
            fileTreeAdapter.setReadOnlyPaths(appPrefs.getReadOnlyFiles());
        }
        Toast.makeText(this, locked ? R.string.read_only_locked : R.string.read_only_unlocked,
                Toast.LENGTH_SHORT).show();
    }

    /** Repaints the padlock to match the active file's state. */
    private void updateReadOnlyIndicator() {
        if (statusReadOnly == null) return;
        FileTab tab = ws.tabs() != null ? ws.tabs().getActiveTab() : null;
        if (tab == null || tab.file == null) {
            statusReadOnly.setImageResource(R.drawable.ic_lock_open);
            statusReadOnly.setColorFilter(theme != null ? theme.textDim : 0xFF808080, PorterDuff.Mode.SRC_IN);
            statusReadOnly.setContentDescription(getString(R.string.a11y_status_read_only));
            return;
        }
        boolean locked = isFileReadOnly(tab.file);
        statusReadOnly.setImageResource(locked ? R.drawable.ic_lock_closed : R.drawable.ic_lock_open);
        statusReadOnly.setColorFilter(locked
                ? (theme != null ? theme.errorText : 0xFFFF6B6B)
                : (theme != null ? theme.textDim : 0xFF808080), PorterDuff.Mode.SRC_IN);
        statusReadOnly.setContentDescription(getString(locked
                ? R.string.a11y_status_read_only_on : R.string.a11y_status_read_only_off));
    }

    /**
     * Makes each editor's editable state match its tab's lock. Class files stay
     * non-editable regardless, since they are shown as bytecode.
     */
    private void applyEditableStateToEditors() {
        applyEditableState(ws.editor, ws.leftTab);
        applyEditableState(ws.editor2, ws.rightTab);
    }

    private void applyEditableState(CodeEditor target, FileTab tab) {
        if (target == null) return;
        if (tab == null || tab.file == null) return;
        if (tab.classBytes != null) {
            target.setEditable(false);
            return;
        }
        target.setEditable(!isFileReadOnly(tab.file));
    }

    private void showReadOnlyInfo() {
        FileTab tab = ws.tabs() != null ? ws.tabs().getActiveTab() : null;
        if (tab == null || tab.file == null) {
            Toast.makeText(this, R.string.toast_no_file_open, Toast.LENGTH_SHORT).show();
            return;
        }
        File file = tab.file;
        boolean userLocked = appPrefs.isReadOnly(file.getAbsolutePath());
        boolean fsLocked = file.exists() && !file.canWrite();
        newRoundedDialog()
                .setTitle(R.string.read_only_lock_title)
                .setMessage(getString(R.string.read_only_info_body,
                        file.getName(),
                        userLocked ? getString(R.string.read_only_yes) : getString(R.string.read_only_no),
                        fsLocked ? getString(R.string.read_only_yes) : getString(R.string.read_only_no)))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Shows what the file in the active pane ends its lines with.
     *
     * <p>Read from the buffer rather than remembered, because the editor keeps a
     * separator per line: a paste can bring foreign endings into an otherwise
     * consistent file, and a stored value would keep claiming otherwise. Mixed
     * files say so, which is the state worth noticing — it is what makes a diff
     * touch every line.</p>
     */
    private void updateLineSeparatorStatus() {
        if (statusLineSeparator == null) return;
        FileTab active = ws.activeTab();
        if (active == null || ws.activeEditor == null || ws.activeEditor.getText() == null) {
            statusLineSeparator.setVisibility(View.GONE);
            return;
        }
        statusLineSeparator.setVisibility(View.VISIBLE);
        com.ccs.wiredroid.util.LineSeparators.Detected detected =
                com.ccs.wiredroid.util.LineSeparators.detect(ws.activeEditor.getText());
        String name = com.ccs.wiredroid.util.LineSeparators.shortName(detected.dominant);
        statusLineSeparator.setText(detected.mixed
                ? getString(R.string.status_line_separator_mixed, name) : name);
    }

    /**
     * Converts the active file's line endings.
     *
     * <p>Rewriting the whole buffer would drop the caret to the start, so the
     * selection is put back afterwards — the text is the same apart from the
     * endings, and a conversion the user asked for should not also move them.</p>
     */
    private void showLineSeparatorDialog() {
        FileTab active = ws.tabs().getActiveTab();
        if (active == null || ws.activeEditor == null || ws.activeEditor.getText() == null) return;

        String text = ws.activeEditor.getText().toString();
        io.github.rosemoe.sora.text.LineSeparator current =
                com.ccs.wiredroid.util.LineSeparators.detect(text).dominant;
        int checked = com.ccs.wiredroid.util.LineSeparators.indexOf(current);
        String[] labels = {
                getString(R.string.line_separator_lf),
                getString(R.string.line_separator_crlf),
                getString(R.string.line_separator_cr),
        };

        // Opens above its own status-bar item, the way a desktop IDE's line
        // ending picker does, rather than as a dialog over the whole editor.
        AnchoredMenu menu = AnchoredMenu.with(this, theme)
                .title(getString(R.string.dialog_line_separator))
                .minWidth(220);
        for (int i = 0; i < labels.length; i++) {
            final io.github.rosemoe.sora.text.LineSeparator target =
                    com.ccs.wiredroid.util.LineSeparators.CHOICES[i];
            menu.checkable(labels[i], i == checked, () -> applyLineSeparator(target));
        }
        if (statusLineSeparator != null) menu.showAbove(statusLineSeparator);
    }

    private void applyLineSeparator(io.github.rosemoe.sora.text.LineSeparator target) {
        FileTab active = ws.tabs().getActiveTab();
        if (active == null || ws.activeEditor == null || ws.activeEditor.getText() == null) return;

        String text = ws.activeEditor.getText().toString();
        String converted = com.ccs.wiredroid.util.LineSeparators.convert(text, target);
        if (converted.equals(text)) {
            updateLineSeparatorStatus();
            return;
        }

        setEditorTextPreservingSelection(ws.activeEditor, converted);
        int index = ws.tabs().getTabs().indexOf(active);
        if (index >= 0) {
            ws.tabs().markModified(index, true);
            // Written out at once: the point of choosing an ending is what lands
            // on disk, and leaving it unsaved would show the new label over a
            // file that still has the old endings.
            saveTab(index);
        }
        updateLineSeparatorStatus();
        Toast.makeText(this, getString(R.string.toast_line_separator_set,
                com.ccs.wiredroid.util.LineSeparators.shortName(target)), Toast.LENGTH_SHORT).show();
    }


    private void showEncodingSelectionDialog() {
        final String[] encodings = { "UTF-8 BOM", "UTF-16 BE BOM", "UTF-16 LE BOM", "Ansi" };
        String current = appPrefs.getFileEncoding();
        int checkedItem = 0;
        for (int i = 0; i < encodings.length; i++) {
            if (encodings[i].equals(current)) {
                checkedItem = i;
                break;
            }
        }

        AnchoredMenu menu = AnchoredMenu.with(this, theme)
                .title(getString(R.string.dialog_select_encoding))
                .minWidth(220);
        for (int i = 0; i < encodings.length; i++) {
            final String selected = encodings[i];
            menu.checkable(selected, i == checkedItem, () -> applyFileEncoding(selected));
        }
        if (statusEncoding != null) menu.showAbove(statusEncoding);
    }

    /** Stores the encoding and re-reads the open file through it. */
    private void applyFileEncoding(String encoding) {
        appPrefs.setFileEncoding(encoding);
        if (statusEncoding != null) statusEncoding.setText(encoding);

        FileTab activeTab = ws.tabs().getActiveTab();
        if (activeTab == null || activeTab.file == null) return;
        try {
            String content = projectManager.readFile(activeTab.file);
            ws.isProgrammaticChange = true;
            ws.activeEditor.setText(content);
            ws.isProgrammaticChange = false;
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.error_cannot_read, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Global Search
    // ══════════════════════════════════════════════════════════

    private void showGlobalSearch() {
        Intent i = new Intent(this, GlobalSearchActivity.class);
        i.putExtra("project_root", projectManager.getProjectDir().getAbsolutePath());
        startActivityForResult(i, REQ_GLOBAL_SEARCH);
    }

    // ══════════════════════════════════════════════════════════
    //  Media Player
    // ══════════════════════════════════════════════════════════

    private void pickMediaFileUnused() {
        Intent i;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Photo Picker (API 33+) — system-provided, no permission needed
            i = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
            i.setType("*/*");
            String[] mimeTypes = {"audio/*", "video/*"};
            i.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        } else {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            String[] mimeTypes = {"audio/*", "video/*", "audio/mpeg", "audio/mp3",
                    "audio/wav", "audio/ogg", "video/mp4", "video/webm", "video/3gpp"};
            i.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        }
        try {
            startActivityForResult(i, REQ_PLAY_MEDIA);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ══════════════════════════════════════════════════════════
    //  AI Chat
    // ══════════════════════════════════════════════════════════

    private void openAiChat() {
        String code = "";
        String fileName = "";
        String filePath = "";
        if (ws.activeEditor != null && ws.activeEditor.getText() != null) {
            code = ws.activeEditor.getText().toString();
        }
        FileTab tab = ws.tabs().getActiveTab();
        if (tab != null && tab.file != null) {
            fileName = tab.file.getName();
            filePath = tab.file.getAbsolutePath();
        }
        AiChatActivity.launch(this, code, fileName, filePath,
                projectManager.getProjectDir() != null
                        ? projectManager.getProjectDir().getAbsolutePath() : "");
    }



    private void runSqlFile(File sqlFile) {
        // Find a .db file in the project, or create one
        File projectDir = projectManager.getProjectDir();
        if (projectDir == null) {
            Toast.makeText(this, R.string.call_graph_no_project, Toast.LENGTH_SHORT).show();
            return;
        }

        File dbFile = null;
        File[] files = projectDir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (n.endsWith(".db") || n.endsWith(".sqlite") || n.endsWith(".sqlite3")) {
                    dbFile = f;
                    break;
                }
            }
        }

        // If no .db file, create one
        if (dbFile == null) {
            dbFile = new File(projectDir, "data.db");
        }

        final File targetDb = dbFile;
        setRunning(true);
        if (consoleManager != null) consoleManager.clear();
        switchBottomPanel(PANEL_RUN);
        appendConsole("SQL: " + sqlFile.getName() + " -> " + targetDb.getName(), theme.textDim);

        new Thread(() -> {
            try {
                android.database.sqlite.SQLiteDatabase db =
                        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
                                targetDb, null);

                String sql = new String(java.nio.file.Files.readAllBytes(sqlFile.toPath()));
                String[] statements = sql.split(";");

                int executed = 0;
                for (String stmt : statements) {
                    String trimmed = stmt.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;

                    String upper = trimmed.toUpperCase(Locale.ROOT).trim();
                    if (upper.startsWith("SELECT") || upper.startsWith("PRAGMA") || upper.startsWith("EXPLAIN")) {
                        try (android.database.Cursor cursor = db.rawQuery(trimmed, null)) {
                            int cols = cursor.getColumnCount();
                            final int rows = cursor.getCount();

                            // Header
                            StringBuilder header = new StringBuilder();
                            for (int i = 0; i < cols; i++) {
                                if (i > 0) header.append("\t| ");
                                header.append(cursor.getColumnName(i));
                            }
                            final String hdr = header.toString();
                            runOnUiThread(() -> appendConsole(hdr, theme.successText));

                            // Rows
                            int rowCount = 0;
                            while (cursor.moveToNext()) {
                                StringBuilder row = new StringBuilder();
                                for (int i = 0; i < cols; i++) {
                                    if (i > 0) row.append("\t| ");
                                    row.append(cursor.isNull(i) ? "NULL" : cursor.getString(i));
                                }
                                final String r = row.toString();
                                final int finalRowCount = rowCount;
                                runOnUiThread(() -> appendConsole(r, theme.consoleText));
                                rowCount++;
                                if (rowCount >= 100) {
                                    runOnUiThread(() -> appendConsole("... (" + (rows - finalRowCount) + " more rows)", theme.textDim));
                                    break;
                                }
                            }
                            final int totalRows = rows;
                            runOnUiThread(() -> appendConsole("→ " + totalRows + " rows", theme.successText));
                        }
                    } else {
                        db.execSQL(trimmed);
                        final int ex = ++executed;
                        runOnUiThread(() -> appendConsole("OK (" + ex + " statements executed)", theme.successText));
                    }
                }
                db.close();

                final int totalExec = executed;
                runOnUiThread(() -> {
                    appendConsole("\nDone! Database: " + targetDb.getName(), theme.successText);
                    setRunning(false);
                });
            } catch (Exception e) {
                final String err = e.getMessage();
                runOnUiThread(() -> {
                    appendConsole("Error: " + err, theme.errorText);
                    setRunning(false);
                });
            }
        }, "sql-run").start();
    }

    /**
     * Runs a Groovy, Clojure or Scala file.
     *
     * <p>Saved first, because the language's own compiler reads the file from
     * disk rather than the buffer the editor is holding.</p>
     */
    private void runJvmLanguageFile(File file) {
        com.ccs.wiredroid.langrt.JvmLanguage language =
                com.ccs.wiredroid.langrt.JvmLanguage.of(file.getName());
        if (language == null) return;

        saveCurrentToActiveTab();
        setRunning(true);
        if (consoleManager != null) consoleManager.clear();
        switchBottomPanel(PANEL_RUN);
        appendConsole(language.displayName() + ": " + file.getName(), theme.textDim);

        // Scratches belong to no project, and then the setting in Settings is
        // what decides the version.
        File jvmLangProject =
                com.ccs.wiredroid.scratch.ScratchManager.isScratchFile(this, file)
                        || projectManager == null
                        ? null : projectManager.getProjectDir();
        com.ccs.wiredroid.langrt.JvmLanguageRunner.run(this, file, jvmLangProject,
                new ProjectCompiler.Callback() {
            @Override
            public void onProgress(String msg) {
                appendConsole("   " + msg, theme.textDim);
            }

            @Override
            public void onOutput(String chunk) {
                streamedOutput = true;
                appendConsole(chunk, theme.consoleText, false);
            }

            @Override
            public void onResult(String output) {
                setRunning(false);
                appendConsole("", theme.accent);
                appendConsole(getString(R.string.console_output_separator), theme.accent);
                boolean isError = output != null
                        && (output.startsWith("error:") || output.contains("error:")
                            || output.contains(" failed:"));
                if (!streamedOutput && output != null && !output.trim().isEmpty()) {
                    appendConsole(output.trim(), isError ? theme.errorText : theme.consoleText);
                }
            }

            @Override
            public void onProblems(List<ProblemItem> problems) {
                if (problemsAdapter != null) problemsAdapter.setItems(problems);
            }
        });
    }

    private void runKotlinFile(File ktFile) {
        setRunning(true);
        if (consoleManager != null) consoleManager.clear();
        if (testPanelManager != null) testPanelManager.showConsole();
        switchBottomPanel(PANEL_RUN);
        appendConsole("Kotlin: " + ktFile.getName(), theme.textDim);

        boolean isScratch = com.ccs.wiredroid.scratch.ScratchManager.isScratchFile(this, ktFile);
        File currentProj = isScratch ? null : (projectManager != null ? projectManager.getProjectDir() : null);

        String source;
        try {
            if (ws.activeEditor != null && ws.activeTab() != null && ktFile.equals(ws.activeTab().file)) {
                source = ws.activeEditor.getText().toString();
            } else {
                source = projectManager.readFile(ktFile);
            }
        } catch (Exception e) {
            appendConsole("Error reading file: " + e.getMessage(), theme.errorText);
            setRunning(false);
            return;
        }

        ProjectCompiler.runSingleSource(this, source, ktFile, currentProj,
                new ProjectCompiler.Callback() {
                    @Override
                    public void onProgress(String msg) {
                        appendConsole("   " + msg, theme.textDim);
                    }

                    @Override
                    public void onResult(String output) {
                        setRunning(false);
                        appendConsole("", theme.accent);
                        appendConsole(getString(R.string.console_output_separator), theme.accent);
                        if (output == null || output.trim().isEmpty()) {
                            appendConsole(getString(R.string.console_process_exit_ok), theme.successText);
                            return;
                        }
                        boolean isTestOutput = output.contains("Testing started at")
                                || output.contains("Process finished with exit code")
                                || output.contains("tests passed")
                                || output.contains("Tests failed");
                        if (isTestOutput && testPanelManager != null && !structuredTestResultsShown) {
                            // Fallback for the single-source test path, which has
                            // no result objects to hand over.
                            testPanelManager.displayRawOutput(output);
                        }
                        boolean isError = output.startsWith("Compilation Error")
                                || output.startsWith("Execution Exception")
                                || output.startsWith("System Error")
                                || output.startsWith("Error:");
                        appendConsole(output.trim(), isError ? theme.errorText : theme.consoleText);
                        if (!isError && !isTestOutput) {
                            appendConsole("\n" + getString(R.string.console_process_exit_ok), theme.successText);
                        }
                    }

                    @Override
                    public void onProblems(List<ProblemItem> problems) {
                        problemsAdapter.setItems(problems);
                    }
                });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Suggestions first, before anything else looks at the key.
        //
        // Sora binds Tab and the arrows to the popup itself, but that only fires
        // if CodeEditor.onKeyDown is reached, and Tab in particular is the key
        // Android uses for focus traversal — it does not reliably survive the
        // trip down the hierarchy from a physical keyboard. Handling it here is
        // not a duplicate: returning true means the editor never sees the event,
        // so the two paths cannot both fire.
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && !event.isCtrlPressed() && !event.isAltPressed() && !event.isShiftPressed()
                && visibleSuggestions() != null) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_TAB:
                    if (acceptSuggestion()) {
                        consumedSuggestionTab = true;
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    moveThroughSuggestionsOrCaret(true);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    moveThroughSuggestionsOrCaret(false);
                    return true;
                default:
                    break;
            }
        }
        // The matching release, so a Tab whose press was consumed cannot still
        // reach focus traversal on the way up.
        if (event.getAction() == KeyEvent.ACTION_UP
                && event.getKeyCode() == KeyEvent.KEYCODE_TAB
                && consumedSuggestionTab) {
            consumedSuggestionTab = false;
            return true;
        }
        // Alt with the vertical arrows moves lines, the way it does in IntelliJ
        // and VS Code. Checked before the Ctrl block because Alt is its own
        // modifier and would otherwise fall through to the caret movement.
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.isAltPressed()
                && !event.isCtrlPressed()) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    executeMenuAction("move_line_up");
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    executeMenuAction("move_line_down");
                    return true;
                default:
                    break;
            }
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.isCtrlPressed()) {
            AppPreferences shortcuts = new AppPreferences(this);
            // User-defined bindings are checked before the legacy key switch,
            // so a shortcut can be moved to any physical key, not only the
            // defaults listed below.
            if (shortcuts.matchesShortcut("duplicate_line", "Ctrl+D", event)) {
                executeMenuAction("duplicate_line"); return true;
            }
            if (shortcuts.matchesShortcut("toggle_comment", "Ctrl+/", event)) {
                executeMenuAction("toggle_comment"); return true;
            }
            if (shortcuts.matchesShortcut("save", "Ctrl+S", event)) {
                saveCurrentFile(); return true;
            }
            if (shortcuts.matchesShortcut("find", "Ctrl+F", event)) {
                if (findReplaceController != null) {
                    if (!findReplaceController.isFindBarVisible()) findReplaceController.toggleFindBar();
                    else findReplaceController.focusFind();
                }
                return true;
            }
            if (shortcuts.matchesShortcut("run", "Ctrl+R", event)) {
                if (isRunning) stopRunning(); else runCurrentFile(); return true;
            }
            if (shortcuts.matchesShortcut("delete_line", "Ctrl+Y", event)) {
                deleteCurrentLine(); return true;
            }
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_D:
                    if (!shortcuts.matchesShortcut("duplicate_line", "Ctrl+D", event)) break;
                    executeMenuAction("duplicate_line");
                    return true;
                case KeyEvent.KEYCODE_SLASH:
                    if (!shortcuts.matchesShortcut("toggle_comment", "Ctrl+/", event)) break;
                    executeMenuAction("toggle_comment");
                    return true;
                // Ctrl+- / Ctrl+= fold and unfold, Shift widens both to the
                // whole file — the IntelliJ bindings.
                case KeyEvent.KEYCODE_MINUS:
                    executeMenuAction(event.isShiftPressed() ? "fold_all" : "fold_toggle");
                    return true;
                case KeyEvent.KEYCODE_EQUALS:
                    executeMenuAction(event.isShiftPressed() ? "unfold_all" : "fold_toggle");
                    return true;
                case KeyEvent.KEYCODE_S:
                    if (!shortcuts.matchesShortcut("save", "Ctrl+S", event)) break;
                    saveCurrentFile();
                    return true;
                case KeyEvent.KEYCODE_F:
                    if (!shortcuts.matchesShortcut("find", "Ctrl+F", event)) break;
                    if (findReplaceController != null) {
                        if (!findReplaceController.isFindBarVisible()) {
                            findReplaceController.toggleFindBar();
                        } else {
                            findReplaceController.focusFind();
                        }
                    }
                    return true;
                case KeyEvent.KEYCODE_R:
                    if (!shortcuts.matchesShortcut("run", "Ctrl+R", event)) break;
                    if (isRunning) {
                        stopRunning();
                    } else {
                        runCurrentFile();
                    }
                    return true;
                case KeyEvent.KEYCODE_Y:
                    if (!shortcuts.matchesShortcut("delete_line", "Ctrl+Y", event)) break;
                    deleteCurrentLine();
                    return true;
                default:
                    break;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.isAltPressed()) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_INSERT || event.getKeyCode() == KeyEvent.KEYCODE_N) {
                if (refactorController != null) {
                    refactorController.showGenerateDialog();
                    return true;
                }
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.isAltPressed() && event.isShiftPressed()) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_G) {
                if (refactorController != null) {
                    refactorController.showGenerateDialog();
                    return true;
                }
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE && isZenMode) {
            toggleZenMode();
            return true;
        }
        // Ctrl+Shift+N or Ctrl+Shift+Insert: New Scratch File
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.isCtrlPressed() && event.isShiftPressed()
                && (event.getKeyCode() == KeyEvent.KEYCODE_N || event.getKeyCode() == KeyEvent.KEYCODE_INSERT)) {
            showNewScratchDialog();
            return true;
        }
        // Ctrl+Shift+F: global search
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.isCtrlPressed() && event.isShiftPressed()
                && event.getKeyCode() == KeyEvent.KEYCODE_F) {
            showGlobalSearch();
            return true;
        }
        // Ctrl+Shift+A: AI chat
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.isCtrlPressed() && event.isShiftPressed()
                && event.getKeyCode() == KeyEvent.KEYCODE_A) {
            openAiChat();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (appPrefs != null && appPrefs.isAutoSystemTheme()) {
            int nightMode = newConfig.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            boolean isDark = (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES);
            if (theme == null || theme.dark != isDark) {
                // Just re-read: the preferences already return the theme chosen
                // for whichever mode is now active. Writing a fixed Darcula or
                // VS Light here would throw away the user's own pick.
                applyTheme();
            }
        }
    }



    private com.ccs.wiredroid.util.InlayHints.Index inlayIndex;
    private final Handler inlayHandler = new Handler(Looper.getMainLooper());
    private final java.util.Map<CodeEditor, Runnable> inlayPending = new java.util.HashMap<>();

    /**
     * Recomputes the parameter hints for one pane, off the main thread.
     *
     * <p>Debounced on the same principle as the other passes: a hint that
     * arrives a beat after the keystroke is fine, one that stutters the caret
     * is not.</p>
     */
    private void scheduleInlayHints(CodeEditor ed, long delayMs) {
        if (ed == null || powerSaving == null || !powerSaving.shouldRunInlayHints()) {
            InlayHintsOverlay off = (ed == ws.editor) ? ws.inlayOverlay1 : ws.inlayOverlay2;
            if (off != null) off.clear();
            return;
        }
        Runnable previous = inlayPending.remove(ed);
        if (previous != null) inlayHandler.removeCallbacks(previous);

        Runnable job = () -> {
            FileTab tab = (ed == ws.editor) ? ws.leftTab : ws.rightTab;
            InlayHintsOverlay overlay = (ed == ws.editor) ? ws.inlayOverlay1 : ws.inlayOverlay2;
            if (overlay == null) return;
            if (tab == null || tab.file == null || !tab.file.getName().endsWith(".java")) {
                overlay.clear();
                return;
            }
            String source = ed.getText().toString();
            inlayWorker.execute(() -> {
                try {
                    com.ccs.wiredroid.util.InlayHints.Index index = inlayIndex;
                    if (index == null) {
                        index = com.ccs.wiredroid.util.InlayHints.index(
                                projectManager != null ? projectManager.getProjectDir() : null, 400);
                        inlayIndex = index;
                    }
                    // The open buffer may declare methods the index has not seen
                    // since it was built, so it is folded in every time.
                    com.ccs.wiredroid.util.InlayHints.addSource(index, source);
                    java.util.List<com.ccs.wiredroid.util.InlayHints.Hint> hints =
                            com.ccs.wiredroid.util.InlayHints.forSource(source, index, 300);
                    runOnUiThread(() -> overlay.setHints(hints));
                } catch (Throwable ignored) {
                    runOnUiThread(overlay::clear);
                }
            });
        };
        inlayPending.put(ed, job);
        inlayHandler.postDelayed(job, delayMs);
    }

    /**
     * Ends the current run and has the last word on the console.
     *
     * <p>{@code stopCurrent} retires the run's callback generation, so whatever
     * the abandoned work posts afterwards is dropped. Without that the console
     * printed "stopped by user" and then, seconds later, the stale run's own
     * "finished with exit code 0" underneath it.</p>
     */
    private void stopRunning() {
        if (!isRunning) return;
        com.ccs.wiredroid.tools.compilers.ProjectCompiler.stopCurrent();
        setRunning(false);
        appendConsole("\n" + getString(R.string.console_process_stopped),
                theme != null ? theme.errorText : 0xFFCF4444);
    }

    /**
     * The one place a run starts or ends, so the live graph cannot drift out of
     * step with it. Marshalled to the UI thread because several call sites are
     * completion callbacks arriving on a worker.
     */
    private void setRunning(boolean running) {
        isRunning = running;
        if (running) {
            // Synchronously, before the callback is wrapped: the wrapper captures
            // this generation, and everything a previous, abandoned run still
            // wants to say is filtered out by comparing against it.
            com.ccs.wiredroid.tools.compilers.RunCancellation.beginRun();
            structuredTestResultsShown = false;
        }
        runOnUiThread(() -> {
            if (consoleManager != null) consoleManager.setInputVisible(running);
            if (running) {
                // Reset when a run starts, not when it ends: onResult calls
                // setRunning(false) before it decides whether to print the
                // output, and this already runs on the main thread — so
                // clearing it there cleared it before the check, and every run
                // printed its output a second time.
                streamedOutput = false;
            } else {
                // A run that ended while the program was blocked on read() must
                // not leave that thread parked on a queue nobody will feed.
                com.ccs.wiredroid.tools.compilers.ConsoleInputHolder.end();
            }
            invalidateOptionsMenu();
        });
        runOnUiThread(() -> {
            if (liveMetrics == null) return;
            boolean metricsEnabled = appPrefs.isRunMetricsVisible();
            if (running && metricsEnabled) {
                liveMetrics.setVisibility(View.VISIBLE);
                liveMetrics.applyTheme(theme);
                liveMetrics.setPeriodMs(powerSaving.getLiveMetricsIntervalMs());
                liveMetrics.start();
            } else {
                // Left on screen: the shape of the run is worth reading once
                // the run is over.
                liveMetrics.stop();
            }
            refreshLiveMetricsVisibility();
            if (consoleManager != null) {
                consoleManager.positionScrollEndButton();
            }
        });
    }

    private void refreshLiveMetricsVisibility() {
        if (liveMetrics == null) return;
        boolean show = bottomPanelMode == PANEL_RUN
                && appPrefs.isRunMetricsVisible()
                && liveMetrics.hasTrace();
        liveMetrics.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private boolean isZenMode = false;
    private TextView btnExitZenMode;

    private void toggleZenMode() {
        isZenMode = !isZenMode;
        View toolbar = findViewById(R.id.toolbar);
        View tabsBar = findViewById(R.id.tabsBar);
        View tabBorder = findViewById(R.id.tabBorder);
        View accessoryBarContainer = findViewById(R.id.accessoryBarContainer);
        View consoleDivider = findViewById(R.id.consoleDivider);
        View tabRun = findViewById(R.id.tabRun);
        View bottomBar = tabRun != null && tabRun.getParent() != null ? (View) tabRun.getParent().getParent() : null;

        int vis = isZenMode ? View.GONE : View.VISIBLE;
        if (toolbar != null) toolbar.setVisibility(vis);
        if (tabsBar != null) tabsBar.setVisibility(vis);
        if (tabBorder != null) tabBorder.setVisibility(vis);
        if (accessoryBarContainer != null) accessoryBarContainer.setVisibility(vis);
        FileTab activeTab = ws != null && ws.tabs() != null ? ws.tabs().getActiveTab() : null;
        if (breadcrumbBar != null) breadcrumbBar.setVisibility(isZenMode ? View.GONE : (activeTab != null && activeTab.file != null ? View.VISIBLE : View.GONE));
        if (consoleDivider != null) consoleDivider.setVisibility(vis);
        if (bottomBar != null) bottomBar.setVisibility(vis);

        if (btnExitZenMode == null) {
            int id = getResources().getIdentifier("btnExitZenMode", "id", getPackageName());
            if (id != 0) {
                btnExitZenMode = findViewById(id);
            }
            if (btnExitZenMode == null && ws != null && ws.wrapperEditor1 != null) {
                btnExitZenMode = new TextView(this);
                btnExitZenMode.setText("✕ Zen");
                btnExitZenMode.setTextSize(12f);
                btnExitZenMode.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                float density = getResources().getDisplayMetrics().density;
                btnExitZenMode.setPadding((int)(12 * density), (int)(6 * density), (int)(12 * density), (int)(6 * density));
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.TOP | Gravity.END;
                lp.topMargin = (int)(8 * density);
                lp.rightMargin = (int)(12 * density);
                btnExitZenMode.setLayoutParams(lp);
                btnExitZenMode.setElevation(6 * density);
                btnExitZenMode.setClickable(true);
                btnExitZenMode.setFocusable(true);
                ws.wrapperEditor1.addView(btnExitZenMode);
            }
            if (btnExitZenMode != null) {
                btnExitZenMode.setOnClickListener(v -> toggleZenMode());
            }
        }
        if (btnExitZenMode != null) {
            btnExitZenMode.setVisibility(isZenMode ? View.VISIBLE : View.GONE);
            if (isZenMode) {
                styleZenExitButton();
            }
        }

        Toast.makeText(this, isZenMode ? "Zen Mode Active (Tap '✕ Zen' or press Esc to exit)" : "Exited Zen Mode", Toast.LENGTH_SHORT).show();
    }

    private void styleZenExitButton() {
        if (btnExitZenMode == null || theme == null) return;
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor((theme.toolbar & 0x00FFFFFF) | (0xE6 << 24));
        gd.setCornerRadius(16 * getResources().getDisplayMetrics().density);
        gd.setStroke((int) (1.5f * getResources().getDisplayMetrics().density), theme.separator);
        btnExitZenMode.setBackground(gd);
        btnExitZenMode.setTextColor(theme.accent);
    }

    private void deleteCurrentLine() {
        if (ws.activeEditor == null) return;
        try {
            io.github.rosemoe.sora.text.Content text = ws.activeEditor.getText();
            int currentLine = ws.activeEditor.getCursor().getLeftLine();
            if (currentLine >= 0 && currentLine < text.getLineCount()) {
                int start = text.getCharIndex(currentLine, 0);
                int end = (currentLine + 1 < text.getLineCount()) 
                        ? text.getCharIndex(currentLine + 1, 0)
                        : text.getCharIndex(currentLine, text.getLine(currentLine).length());
                text.delete(start, end);
            }
        } catch (Exception ignored) {}
    }

    private void showRunConfigurationDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        if (theme != null) root.setBackgroundColor(theme.bg);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView tvArgs = new TextView(this);
        tvArgs.setText("Program Arguments (String[] args):");
        if (theme != null) tvArgs.setTextColor(theme.text);
        root.addView(tvArgs);

        EditText etArgs = new EditText(this);
        etArgs.setText(appPrefs.getProgramArgs());
        etArgs.setHint("e.g. arg1 arg2 \"hello world\"");
        if (theme != null) {
            etArgs.setTextColor(theme.text);
            etArgs.setHintTextColor(theme.textDim);
            android.graphics.drawable.GradientDrawable sBg1 = new android.graphics.drawable.GradientDrawable();
            sBg1.setColor(com.ccs.wiredroid.util.Colors.blend(theme.consoleBg, theme.bg, 0.4f));
            sBg1.setCornerRadius(dp(8));
            sBg1.setStroke(dp(1), theme.separator);
            etArgs.setBackground(sBg1);
            etArgs.setPadding(dp(12), dp(10), dp(12), dp(10));
        }
        root.addView(etArgs);

        TextView tvEnv = new TextView(this);
        tvEnv.setText("\nEnvironment Variables (KEY=VAL):");
        if (theme != null) tvEnv.setTextColor(theme.text);
        root.addView(tvEnv);

        EditText etEnv = new EditText(this);
        etEnv.setText(appPrefs.getEnvVars());
        etEnv.setHint("e.g. KEY1=VAL1, KEY2=VAL2");
        if (theme != null) {
            etEnv.setTextColor(theme.text);
            etEnv.setHintTextColor(theme.textDim);
            android.graphics.drawable.GradientDrawable sBg2 = new android.graphics.drawable.GradientDrawable();
            sBg2.setColor(com.ccs.wiredroid.util.Colors.blend(theme.consoleBg, theme.bg, 0.4f));
            sBg2.setCornerRadius(dp(8));
            sBg2.setStroke(dp(1), theme.separator);
            etEnv.setBackground(sBg2);
            etEnv.setPadding(dp(12), dp(10), dp(12), dp(10));
        }
        root.addView(etEnv);

        androidx.appcompat.app.AlertDialog dialog = newRoundedDialog()
                .setTitle("Program Arguments & Environment")
                .setView(root)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    appPrefs.setProgramArgs(etArgs.getText().toString().trim());
                    appPrefs.setEnvVars(etEnv.getText().toString().trim());
                    Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        Dialogs.style(dialog, theme);
    }

    private static final int REQ_STORAGE_PERMISSION = 9998;

    private boolean checkStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                newRoundedDialog()
                        .setTitle(R.string.permission_storage_title)
                        .setMessage(R.string.permission_storage_message)
                        .setPositiveButton(R.string.permission_storage_open_settings, (d, w) -> {
                            openStorageSettings();
                        })
                        .setNegativeButton(R.string.permission_storage_exit, (d, w) -> finish())
                        .setCancelable(false)
                        .show();
                return false;
            }
        }
        return true;
    }

    private boolean isExternalPath(File file) {
        try {
            String path = file.getCanonicalPath();
            String internal = getFilesDir().getCanonicalPath();
            return !path.startsWith(internal);
        } catch (Exception e) {
            return true;
        }
    }

    private boolean canReadRoot(File root) {
        if (!root.exists() || !root.isDirectory()) return false;
        // Try to actually list files — Samsung lies about permission
        File[] files = root.listFiles();
        if (files == null) return false;
        // Try to read first Java file found
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".java")) {
                try {
                    java.io.FileInputStream fis = new java.io.FileInputStream(f);
                    fis.read();
                    fis.close();
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        }
        // No Java files — try any file
        for (File f : files) {
            if (f.isFile()) {
                try {
                    java.io.FileInputStream fis = new java.io.FileInputStream(f);
                    fis.read();
                    fis.close();
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        }
        return true;
    }

    private File copyToInternal(File externalDir) {
        try {
            File internalDir = new File(getFilesDir(), "imported_project");
            if (internalDir.exists()) deleteRecursive(internalDir);
            internalDir.mkdirs();
            copyDirRecursive(externalDir, internalDir);
            return internalDir;
        } catch (Exception e) {
            return null;
        }
    }

    private void copyDirRecursive(File src, File dest) throws IOException {
        if (src.isDirectory()) {
            dest.mkdirs();
            File[] files = src.listFiles();
            if (files != null) {
                for (File file : files) {
                    copyDirRecursive(file, new File(dest, file.getName()));
                }
            }
        } else {
            try (java.io.InputStream in = new java.io.FileInputStream(src);
                 java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            }
        }
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }

    private void openStorageSettings() {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_STORAGE_PERMISSION);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivityForResult(intent, REQ_STORAGE_PERMISSION);
            } catch (Exception e2) {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQ_STORAGE_PERMISSION);
                } catch (Exception e3) {
                    Toast.makeText(this, "Cannot open settings. Please grant storage permission manually.", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  CPU Profiler — delegated to ProfilerPanelManager
    // ══════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════
    //  Dependency Graph
    // ══════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════
    //  TODO/FIXME Tracker — delegated to TodoPanelManager
    // ══════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════
    //  Dependency Graph
    // ══════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════
    //  Dependency Viewer — delegated to DependencyPanelManager
    // ══════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════
    //  Refactoring — delegated to RefactorController
    // ══════════════════════════════════════════════════════════

    private void showRefactorDialog() {
        if (refactorController == null) {
            refactorController = new RefactorController(this, ws, new RefactorController.Callback() {
                @Override public ProjectManager getProjectManager() { return projectManager; }
                @Override public AppTheme getTheme() { return theme; }
                @Override public void runOnUiThread(Runnable r) { MainActivity.this.runOnUiThread(r); }
                @Override public void refreshProblemsAsync() { refreshProblemsMergedAsync(); }
                @Override public void reloadTab(FileTab tab) { /* handled by controller */ }
                @Override public int dp(int v) { return MainActivity.this.dp(v); }
            });
        }
        refactorController.showDialog();
    }

    private boolean isMinimapAllowed() {
        boolean isPortrait = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        return powerSaving.shouldUseMinimap() && !isPortrait;
    }
}
