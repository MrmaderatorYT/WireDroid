package com.ccs.wiredroid.tools.compilers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.analysis.ProblemItem;
import com.ccs.wiredroid.analysis.StaticAnalyzer;
import com.ccs.wiredroid.maven.MavenDependencyResolver;
import com.ccs.wiredroid.maven.MavenPaths;
import com.ccs.wiredroid.maven.MavenRunner;
import com.ccs.wiredroid.maven.PomModel;
import com.ccs.wiredroid.maven.PomParser;
import com.ccs.wiredroid.javase.JavaSeProjectRunner;
import com.ccs.wiredroid.project.ProjectScanner;
import com.ccs.wiredroid.util.AppPreferences;

import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Facade for compilation (ECJ + D8), Maven projects, native building, and running.
 */
public final class ProjectCompiler {

    private static final Object SNIPPET_CACHE_LOCK = new Object();

    public interface Callback {
        void onProgress(String message);
        void onResult(String output);
        void onProblems(List<ProblemItem> problems);

        /**
         * A chunk of the program's own output, as it is produced.
         *
         * <p>{@link #onResult} arrives only once the program has finished, which
         * is too late for anything interactive: a prompt would appear after the
         * answer was needed. Default-implemented so the callers that only want
         * the finished output keep compiling.</p>
         */
        default void onOutput(String chunk) { }

        /**
         * The test run's own objects, not its printed form.
         *
         * <p>{@link #onResult} carries the report as text, which is what the
         * console shows. The tree needs the real {@code Throwable}s to resolve a
         * failure to a file and a line, and re-parsing the text throws exactly
         * that away. Default-implemented so non-test callers are unaffected.</p>
         */
        default void onTestResults(List<com.ccs.wiredroid.maven.MavenTestRunner.TestClassResult> results,
                                   int totalTests, int passedTests, int failedTests,
                                   int skippedTests, long durationMs) { }
    }

    /** Delivers structured test results on the main thread. */
    public static void postTestResults(Callback callback,
                                       List<com.ccs.wiredroid.maven.MavenTestRunner.TestClassResult> results,
                                       int totalTests, int passedTests, int failedTests,
                                       int skippedTests, long durationMs) {
        if (callback == null) return;
        new Handler(Looper.getMainLooper()).post(() -> callback.onTestResults(
                results, totalTests, passedTests, failedTests, skippedTests, durationMs));
    }

    /** Delivers a chunk of program output on the main thread. */
    public static void postOutput(Callback callback, String chunk) {
        if (callback == null || chunk == null || chunk.isEmpty()) return;
        new Handler(Looper.getMainLooper()).post(() -> callback.onOutput(chunk));
    }

    private ProjectCompiler() {}

    /**
     * Stop the current run, whichever phase it is in.
     *
     * <p>Only the execution phase can be killed outright — the Java SE guest
     * process by signal, an ART program by interrupt. A build or a test sweep
     * has to notice {@link RunCancellation#isStopRequested()} and unwind, and a
     * program that ignores interrupts will not stop at all. What the generation
     * bump inside {@code requestStop} guarantees is the part the user can see:
     * nothing from the abandoned run reaches the console again.</p>
     */
    public static void stopCurrent() {
        RunCancellation.requestStop();
        DexRunner.stop();
        com.ccs.wiredroid.javase.JavaSeRunner.stopCurrent();
        ConsoleInputHolder.end();
    }

    /**
     * Лише ECJ (без D8), для фонового аналізу. Викликати не з головного потоку.
     */
    public static List<ProblemItem> ecjProblemsForSource(Context context, String sourceCode,
                                                         File logicalFile) {
        try {
            if (sourceCode == null || sourceCode.trim().isEmpty()) {
                return new ArrayList<>();
            }
            String className = EcjCompiler.extractClassName(sourceCode);
            File cacheDir = new File(context.getCacheDir(), "live_compile_cache");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File srcFile = new File(cacheDir, className + ".java");
            File androidJar = D8Dexer.ensureAndroidJar(context, cacheDir);
            EcjCompiler.writeUtf8(srcFile, sourceCode);
            String ecjErr = EcjCompiler.compileEcj(androidJar, null, cacheDir, javaTarget(context), srcFile);
            if (ecjErr == null) {
                return new ArrayList<>();
            }
            return EcjProblemParser.remapToLogicalFile(
                    EcjProblemParser.parse(ecjErr, null), logicalFile);
        } catch (Exception e) {
            // Returning an empty list here makes "the compiler could not run" look
            // exactly like "this file is fine", which is how a broken analysis
            // stayed invisible. Say so instead.
            android.util.Log.w("ProjectCompiler", "live analysis failed for " + logicalFile, e);
            List<ProblemItem> failed = new ArrayList<>(1);
            String reason = e.getMessage() != null && !e.getMessage().trim().isEmpty()
                    ? e.getMessage() : e.getClass().getSimpleName();
            failed.add(new ProblemItem(ProblemItem.Severity.WARNING,
                    context != null
                            ? context.getString(R.string.problems_analysis_failed, reason)
                            : "Could not analyse this file: " + reason,
                    logicalFile, 1));
            return failed;
        }
    }

    public static String javaTarget(Context ctx) {
        try {
            return com.ccs.wiredroid.project.ProjectJdk.forOpenProject(ctx);
        } catch (Throwable t) {
            return AppPreferences.JAVA_8;
        }
    }

    public static void runSingleSource(Context context, String sourceCode, Callback callback) {
        runSingleSource(context, sourceCode, null, null, callback);
    }

    /**
     * Компілює і запускає самодостатній Java-приклад з матеріалів.
     */
    public static void runJavaSnippet(Context context, String sourceCode, Callback rawCallback) {
        final Callback callback = wrapCallback(context, rawCallback);
        RunCancellation.newWorker(() -> {
            File runDir = null;
            try {
                postProgress(callback, context.getString(R.string.lesson_compiling));
                File snippetRoot = new File(context.getCacheDir(), "lesson_snippets");
                File androidJar;
                synchronized (SNIPPET_CACHE_LOCK) {
                    androidJar = D8Dexer.ensureAndroidJar(context, snippetRoot);
                }

                String className = EcjCompiler.extractClassName(sourceCode);
                runDir = new File(snippetRoot,
                        "run_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId());
                if (!runDir.mkdirs() && !runDir.isDirectory()) {
                    throw new IOException("Cannot create snippet directory: " + runDir);
                }

                File srcFile = new File(runDir, className + ".java");
                EcjCompiler.writeUtf8(srcFile, sourceCode);
                String ecjErr = EcjCompiler.compileEcj(androidJar, null, runDir, AppPreferences.JAVA_8, srcFile);
                if (ecjErr != null) {
                    postResult(callback, "Compilation Error:\n" + ecjErr);
                    return;
                }
                postProblems(callback, context, null, "", null);

                File classFile = EcjCompiler.findClassFile(runDir, className);
                if (classFile == null) {
                    postResult(callback, "Error: " + className + ".class not found.");
                    return;
                }

                List<File> classFiles = EcjCompiler.findAllClassFiles(runDir);
                File dexDir = new File(runDir, "dex");
                if (!dexDir.mkdirs() && !dexDir.isDirectory()) {
                    throw new IOException("Cannot create snippet dex directory: " + dexDir);
                }
                postProgress(callback, context.getString(R.string.lesson_dexing));
                D8Dexer.runD8Dex(androidJar, dexDir, classFiles);

                String fqName = classFile.getAbsolutePath()
                        .substring(runDir.getAbsolutePath().length() + 1)
                        .replace(".class", "")
                        .replace('/', '.');
                postProgress(callback, context.getString(R.string.lesson_executing));
                DexRunner.runDexMain(context, null, dexDir, fqName, callback);
            } catch (Exception e) {
                postResult(callback, "System Error: " + e.getMessage() + "\n"
                        + Log.getStackTraceString(e));
            } finally {
                if (runDir != null) {
                    EcjCompiler.deleteRecursive(runDir);
                }
            }
        }, "lesson-snippet").start();
    }

    /**
     * Компілює та запускає ОДИН файл (.java або .kt) як окремий процес.
     */
    public static void runSingleSource(Context context, String sourceCode, File logicalSourceFile, File projectRoot,
                                       Callback rawCallback) {
        final Callback callback = wrapCallback(context, rawCallback);
        RunCancellation.newWorker(() -> {
            try {
                boolean isKotlin = logicalSourceFile != null && logicalSourceFile.getName().toLowerCase(Locale.ROOT).endsWith(".kt");
                String className = EcjCompiler.extractClassName(sourceCode);
                File cacheDir = new File(context.getCacheDir(), "compile_cache");
                if (!cacheDir.exists()) cacheDir.mkdirs();

                File androidJar = D8Dexer.ensureAndroidJar(context, cacheDir);
                File dexDir = new File(cacheDir, "dex");
                if (!dexDir.exists()) {
                    dexDir.mkdirs();
                } else {
                    File[] oldFiles = dexDir.listFiles();
                    if (oldFiles != null) {
                        for (File f : oldFiles) f.delete();
                    }
                }

                if (isKotlin) {
                    postProgress(callback, "Compiling Kotlin source...");
                    cleanKotlinCache(cacheDir);

                    File srcFile = new File(cacheDir, className + ".kt");
                    EcjCompiler.writeUtf8(srcFile, sourceCode);

                    long compileStarted = android.os.SystemClock.elapsedRealtimeNanos();
                    List<File> classFiles = KotlinCompiler.compile(srcFile, projectRoot, cacheDir, androidJar, className, callback, context);
                    recordPerformanceDuration("compile.kotlin", compileStarted);
                    if (classFiles == null || classFiles.isEmpty()) {
                        return;
                    }

                    File stdlib = KotlinCompiler.ensureKotlinStdlib(context, cacheDir);
                    if (stdlib != null && stdlib.exists()) {
                        List<File> allFiles = new ArrayList<>(classFiles);
                        allFiles.add(stdlib);
                        long dexStarted = android.os.SystemClock.elapsedRealtimeNanos();
                        D8Dexer.runD8Dex(androidJar, dexDir, allFiles);
                        recordPerformanceDuration("compile.d8", dexStarted);
                    } else {
                        long dexStarted = android.os.SystemClock.elapsedRealtimeNanos();
                        D8Dexer.runD8Dex(androidJar, dexDir, classFiles);
                        recordPerformanceDuration("compile.d8", dexStarted);
                    }

                    String runClassName = null;
                    for (File cf : classFiles) {
                        String name = cf.getName().replace(".class", "");
                        if (name.equals(className + "Kt")) {
                            String relative = cacheDir.toURI().relativize(cf.toURI()).getPath();
                            runClassName = relative.replace(".class", "").replace('/', '.');
                            break;
                        }
                    }
                    if (runClassName == null) {
                        for (File cf : classFiles) {
                            String name = cf.getName().replace(".class", "");
                            if (name.equals(className)) {
                                String relative = cacheDir.toURI().relativize(cf.toURI()).getPath();
                                runClassName = relative.replace(".class", "").replace('/', '.');
                                break;
                            }
                        }
                    }
                    if (runClassName == null) {
                        File directKt = new File(cacheDir, className + "Kt.class");
                        if (directKt.exists()) {
                            runClassName = className + "Kt";
                        } else {
                            File directClass = new File(cacheDir, className + ".class");
                            if (directClass.exists()) {
                                runClassName = className;
                            }
                        }
                    }

                    if (runClassName == null) {
                        postResult(callback, "Kotlin Error: no runnable class found. " +
                                "Ensure the file has a `fun main()` or a `class` with a companion main.");
                        return;
                    }
                    DexRunner.runDexMain(context, null, dexDir, runClassName, callback);
                } else {
                    File srcFile = new File(cacheDir, className + ".java");
                    EcjCompiler.writeUtf8(srcFile, sourceCode);

                    long compileStarted = android.os.SystemClock.elapsedRealtimeNanos();
                    String ecjErr = EcjCompiler.compileEcj(androidJar, null, cacheDir, javaTarget(context), srcFile);
                    recordPerformanceDuration("compile.java", compileStarted);
                    if (ecjErr != null) {
                        postCompileFailure(callback, context, null, ecjErr, logicalSourceFile,
                                "Compilation Error:\n" + ecjErr);
                        return;
                    }
                    postProblems(callback, context, null, "", logicalSourceFile);

                    File classFile = EcjCompiler.findClassFile(cacheDir, className);
                    if (classFile == null) {
                        postResult(callback, "Error: " + className + ".class not found.");
                        return;
                    }

                    long dexStarted = android.os.SystemClock.elapsedRealtimeNanos();
                    D8Dexer.runD8Dex(androidJar, dexDir, classFile);
                    recordPerformanceDuration("compile.d8", dexStarted);
                    String fqName = classFile.getAbsolutePath()
                            .substring(cacheDir.getAbsolutePath().length() + 1)
                            .replace(".class", "")
                            .replace('/', '.');
                    DexRunner.runDexMain(context, null, dexDir, fqName, callback);
                }
            } catch (ClassNotFoundException e) {
                postResult(callback, "Error: Kotlin compiler library not integrated. Please verify kotlin-compiler-embeddable in build.gradle.");
            } catch (Exception e) {
                postResult(callback, "System Error: " + e.getMessage() + "\n" + Log.getStackTraceString(e));
            }
        }).start();
    }

    /**
     * Compile and run a single source in debug mode: instruments bytecode
     * with debug hooks, loads DexClassLoader, and starts a debug session.
     */
    public static void debugSingleSource(Context context, String sourceCode, File logicalSourceFile, File projectRoot,
                                          Callback rawCallback) {
        final Callback callback = wrapCallback(context, rawCallback);
        RunCancellation.newWorker(() -> {
            try {
                boolean isKotlin = logicalSourceFile != null
                        && logicalSourceFile.getName().toLowerCase(Locale.ROOT).endsWith(".kt");
                if (isKotlin) {
                    postResult(callback, "Debugging is not supported for Kotlin files yet.");
                    return;
                }

                String className = EcjCompiler.extractClassName(sourceCode);
                File cacheDir = new File(context.getCacheDir(), "debug_compile_cache");
                if (cacheDir.exists()) {
                    File[] oldFiles = cacheDir.listFiles();
                    if (oldFiles != null) {
                        for (File f : oldFiles) {
                            if (f.getName().equals("android.jar")) continue;
                            if (f.isDirectory()) EcjCompiler.deleteRecursive(f);
                            else f.delete();
                        }
                    }
                }
                if (!cacheDir.exists()) cacheDir.mkdirs();

                File androidJar = D8Dexer.ensureAndroidJar(context, cacheDir);
                File dexDir = new File(cacheDir, "debug_dex");
                if (!dexDir.exists()) {
                    dexDir.mkdirs();
                } else {
                    File[] oldFiles = dexDir.listFiles();
                    if (oldFiles != null) {
                        for (File f : oldFiles) {
                            if (f.isDirectory()) EcjCompiler.deleteRecursive(f);
                            else f.delete();
                        }
                    }
                }

                // Resolve project root if in a project
                File resolvedProjectRoot = projectRoot;
                if (resolvedProjectRoot == null && logicalSourceFile != null) {
                    File dir = logicalSourceFile.getParentFile();
                    while (dir != null) {
                        if (new File(dir, "pom.xml").exists()
                                || new File(dir, ".project").exists()
                                || new File(dir, ".wiredroid").exists()
                                || new File(dir, "build.gradle").exists()
                                || new File(dir, "build.gradle.kts").exists()) {
                            resolvedProjectRoot = dir;
                            break;
                        }
                        dir = dir.getParentFile();
                    }
                }

                // If logicalSourceFile exists, sync in-memory edits to disk
                if (logicalSourceFile != null && sourceCode != null) {
                    try {
                        EcjCompiler.writeUtf8(logicalSourceFile, sourceCode);
                    } catch (Exception ignored) {}
                }

                List<File> allSources = new ArrayList<>();
                if (resolvedProjectRoot != null) {
                    allSources = ProjectScanner.listJavaSources(resolvedProjectRoot);
                }
                if (logicalSourceFile != null && !allSources.contains(logicalSourceFile)) {
                    allSources.add(logicalSourceFile);
                }

                // Resolve dependencies if maven or libs folder exists
                List<File> depJars = new ArrayList<>();
                if (resolvedProjectRoot != null) {
                    if (new File(resolvedProjectRoot, "pom.xml").exists()) {
                        try {
                            PomModel pom = PomParser.parse(MavenPaths.pomFile(resolvedProjectRoot));
                            depJars = MavenDependencyResolver.resolve(resolvedProjectRoot, pom,
                                    msg -> postProgress(callback, msg));
                        } catch (Exception ignored) {}
                    }
                    File libsDir = new File(resolvedProjectRoot, "libs");
                    if (libsDir.isDirectory()) {
                        File[] jarFiles = libsDir.listFiles(f -> f.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
                        if (jarFiles != null) {
                            for (File j : jarFiles) {
                                if (!depJars.contains(j)) depJars.add(j);
                            }
                        }
                    }
                }
                String cp = EcjCompiler.classpath(depJars);

                postProgress(callback, "Compiling...");
                String ecjErr;
                if (allSources.isEmpty()) {
                    File srcFile = new File(cacheDir, className + ".java");
                    EcjCompiler.writeUtf8(srcFile, sourceCode);
                    ecjErr = EcjCompiler.compileEcj(androidJar, cp, cacheDir, javaTarget(context), srcFile);
                } else {
                    ecjErr = EcjCompiler.compileEcjMulti(androidJar, cp, cacheDir, javaTarget(context),
                            allSources.toArray(new File[0]));
                }

                if (ecjErr != null) {
                    postCompileFailure(callback, context, resolvedProjectRoot, ecjErr, logicalSourceFile,
                            "Compilation Error:\n" + ecjErr);
                    return;
                }
                postProblems(callback, context, resolvedProjectRoot, "", logicalSourceFile);

                List<File> classFiles = EcjCompiler.findAllClassFiles(cacheDir);
                if (classFiles.isEmpty()) {
                    postResult(callback, "Error: No .class files found after compilation.");
                    return;
                }

                // Compile native sources for debug mode if present
                File jniLibsDir = null;
                if (resolvedProjectRoot != null) {
                    jniLibsDir = NativeBuildHelper.compileNativeSources(context, resolvedProjectRoot, callback);
                }

                postProgress(callback, "Instrumenting for debugging...");
                java.util.Map<Integer, String> bpMap = com.ccs.wiredroid.debug.DebuggerController.getInstance().getBreakpoints();
                java.util.Set<Integer> bpLines = new java.util.HashSet<>(bpMap.keySet());
                for (File cf : classFiles) {
                    com.ccs.wiredroid.debug.DebugInstrumenter.instrumentFile(cf, bpLines);
                }

                postProgress(callback, "Converting to DEX...");
                D8Command.Builder d8b = D8Command.builder()
                        .setOutput(dexDir.toPath(), OutputMode.DexIndexed)
                        .setMinApiLevel(android.os.Build.VERSION.SDK_INT)
                        .addLibraryFiles(androidJar.toPath());
                for (File j : depJars) {
                    d8b.addProgramFiles(j.toPath());
                }
                for (File cf : classFiles) {
                    d8b.addProgramFiles(cf.toPath());
                }
                D8.run(d8b.build());

                // Find FQ class name for active/main class
                String fqClassName = null;
                String pkg = EcjCompiler.extractPackageName(sourceCode);
                if (pkg != null && !pkg.isEmpty()) {
                    fqClassName = pkg + "." + className;
                } else {
                    fqClassName = className;
                }
                File expectedClassFile = new File(cacheDir, fqClassName.replace('.', File.separatorChar) + ".class");
                if (!expectedClassFile.exists()) {
                    File found = EcjCompiler.findClassFile(cacheDir, className);
                    if (found != null) {
                        String rel = found.getAbsolutePath().substring(cacheDir.getAbsolutePath().length() + 1);
                        fqClassName = rel.replace(".class", "").replace('/', '.').replace('\\', '.');
                    }
                }

                postProgress(callback, "Starting debug session...");
                postResult(callback, "DEBUG_SESSION:" + fqClassName + ":" + cacheDir.getAbsolutePath()
                        + ":" + dexDir.getAbsolutePath()
                        + ":" + (jniLibsDir != null ? jniLibsDir.getAbsolutePath() : ""));
            } catch (Exception e) {
                postResult(callback, "System Error: " + e.getMessage() + "\n" + Log.getStackTraceString(e));
            }
        }).start();
    }

    /**
     * Виконує .class байти напряму: конвертує у DEX та запускає main().
     */
    public static void runClassBytes(Context context, String className, byte[] classBytes,
                                      Callback rawCallback) {
        // Wrapped like every other run entry point, so Stop retires this one's
        // output too rather than letting it surface in the next run's console.
        final Callback callback = wrapCallback(context, rawCallback);
        try {
            File cacheDir = new File(context.getCacheDir(), "bytecode_run_" + System.currentTimeMillis());
            if (!cacheDir.exists()) cacheDir.mkdirs();

            File classFile = new File(cacheDir, className.replace('.', '/') + ".class");
            classFile.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(classFile)) {
                fos.write(classBytes);
            }

            File androidJar = D8Dexer.ensureAndroidJar(context, cacheDir);
            File dexDir = new File(cacheDir, "dex");
            if (!dexDir.exists()) dexDir.mkdirs();

            postProgress(callback, "Converting .class → DEX…");
            D8Dexer.runD8Dex(androidJar, dexDir, classFile);

            postProgress(callback, "Running " + className + "…");
            DexRunner.runDexMain(context, null, dexDir, className, callback);
        } catch (Exception e) {
            postResult(callback, "Error: " + e.getMessage() + "\n" + Log.getStackTraceString(e));
        }
    }

    public static void debugRunDex(Context context, String className, File dexDir,
                                   File debugCacheDir, File jniLibsDir, Callback callback) {
        DexRunner.debugRunDex(context, className, dexDir, debugCacheDir, jniLibsDir, callback);
    }

    private static void recordPerformanceDuration(String name, long startedNanos) {
        com.ccs.wiredroid.profiler.PerformanceMonitor monitor =
                com.ccs.wiredroid.profiler.PerformanceMonitor.get();
        if (monitor != null) monitor.recordDuration(name,
                android.os.SystemClock.elapsedRealtimeNanos() - startedNanos);
    }

    public static void mavenCompileAndRun(Context context, File projectRoot, PomModel pom, Callback callback) {
        MavenRunner.mavenCompileAndRun(context, projectRoot, pom, callback);
    }

    public static void javaSeCompileAndRun(Context context, File projectRoot, PomModel pom,
                                           Callback callback) {
        JavaSeProjectRunner.compileAndRunProject(context, projectRoot, pom, callback);
    }

    public static void javaSeTestRun(Context context, File projectRoot, PomModel pom,
                                     File singleTestFile, Callback callback) {
        JavaSeProjectRunner.compileAndRunTests(context, projectRoot, pom, singleTestFile, callback);
    }

    public static void runJavaSeSingleSource(Context context, String sourceCode,
                                             File logicalSourceFile, File projectRoot,
                                             Callback callback) {
        JavaSeProjectRunner.runSingleSource(context, sourceCode, logicalSourceFile,
                projectRoot, callback);
    }

    public static void runJavaSeSingleTestSource(Context context, String sourceCode,
                                                 File logicalSourceFile, File projectRoot,
                                                 Callback callback) {
        JavaSeProjectRunner.runSingleTestSource(context, sourceCode, logicalSourceFile,
                projectRoot, callback);
    }

    public static void mavenCompile(Context context, File projectRoot, PomModel pom, Callback callback) {
        MavenRunner.mavenCompile(context, projectRoot, pom, callback);
    }

    public static void mavenPackage(Context context, File projectRoot, PomModel pom, Callback callback) {
        MavenRunner.mavenPackage(context, projectRoot, pom, callback);
    }

    public static void mavenTestCompile(Context context, File projectRoot, PomModel pom, Callback callback) {
        MavenRunner.mavenTestCompile(context, projectRoot, pom, callback);
    }

    public static void mavenClean(Context context, File projectRoot, Callback callback) {
        MavenRunner.mavenClean(context, projectRoot, callback);
    }

    public static void mavenInstall(Context context, File projectRoot, PomModel pom, Callback callback) {
        MavenRunner.mavenInstall(context, projectRoot, pom, callback);
    }

    public static void mavenTestRun(Context context, File projectRoot, PomModel pom, Callback callback) {
        MavenRunner.mavenTestRun(context, projectRoot, pom, callback);
    }

    // ── Bytecode viewer (ECJ → .class) ────────────────────

    public static final class BytecodeCompileResult {
        public final File classFile;
        public final String errorMessage;

        public BytecodeCompileResult(File classFile, String errorMessage) {
            this.classFile = classFile;
            this.errorMessage = errorMessage;
        }
    }

    public static BytecodeCompileResult compileForBytecodeView(Context context, File javaFile,
            String sourceText, File projectRoot) {
        try {
            File outDir = new File(context.getCacheDir(), "bytecode_view");
            EcjCompiler.deleteRecursive(outDir);
            outDir.mkdirs();
            File srcFile = new File(outDir, javaFile.getName());
            EcjCompiler.writeUtf8(srcFile, sourceText);
            File androidJar = D8Dexer.ensureAndroidJar(context, new File(context.getCacheDir(), "compile_cache"));
            String cp = "";
            if (projectRoot != null && new File(projectRoot, "pom.xml").exists()) {
                PomModel pom = PomParser.parse(MavenPaths.pomFile(projectRoot));
                List<File> deps = MavenDependencyResolver.resolve(projectRoot, pom, null);
                cp = EcjCompiler.classpath(deps);
                File tc = MavenPaths.targetClassesDir(projectRoot);
                if (tc.exists()) {
                    if (!cp.isEmpty()) cp += File.pathSeparator;
                    cp += tc.getAbsolutePath();
                }
            }
            String ecjErr = EcjCompiler.compileEcj(androidJar, cp, outDir, javaTarget(context), srcFile);
            if (ecjErr != null) {
                return new BytecodeCompileResult(null, ecjErr.trim());
            }
            File classFile = EcjCompiler.findCompiledClass(outDir, sourceText);
            if (classFile == null || !classFile.exists()) {
                return new BytecodeCompileResult(null, "No .class output (check package and class name).");
            }
            return new BytecodeCompileResult(classFile, null);
        } catch (Exception e) {
            String msg = e.getMessage();
            return new BytecodeCompileResult(null, msg != null ? msg : "compile error");
        }
    }

    public static List<File> compileKotlinPublic(File srcFile, File projectRoot, File cacheDir, File androidJar, String className, Callback callback, Context context) {
        return KotlinCompiler.compile(srcFile, projectRoot, cacheDir, androidJar, className, callback, context);
    }

    public static void postProgress(Callback cb, String msg) {
        if (cb == null) return;
        new Handler(Looper.getMainLooper()).post(() -> cb.onProgress(msg));
    }

    public static void postResult(Callback cb, String r) {
        if (cb == null) return;
        new Handler(Looper.getMainLooper()).post(() -> cb.onResult(r));
    }

    public static List<ProblemItem> buildProblemsList(File projectRoot, String ecjErr, File remapSourceFile) {
        List<ProblemItem> problems = new ArrayList<>(EcjProblemParser.remapToLogicalFile(
                EcjProblemParser.parse(ecjErr, projectRoot), remapSourceFile));
        if (projectRoot != null) {
            List<File> sources = ProjectScanner.listJavaSources(projectRoot);
            problems.addAll(StaticAnalyzer.analyze(projectRoot, sources));
        }
        return problems;
    }

    public static void postCompileFailure(Callback cb, Context ctx, File projectRoot, String ecjErr,
                                          File remapSourceFile, String fullResultText) {
        if (cb == null) return;
        final List<ProblemItem> toPost = buildProblemsList(projectRoot, ecjErr, remapSourceFile);
        new Handler(Looper.getMainLooper()).post(() -> {
            cb.onProblems(toPost);
            cb.onResult(fullResultText);
        });
    }

    public static void postProblems(Callback cb, Context ctx, File projectRoot, String ecjErr,
                                    File remapSourceFile) {
        if (cb == null) return;
        final List<ProblemItem> toPost = buildProblemsList(projectRoot, ecjErr, remapSourceFile);
        new Handler(Looper.getMainLooper()).post(() -> cb.onProblems(toPost));
    }

    public static Callback wrapCallback(Context context, Callback original) {
        if (original == null) return null;
        // The run this callback belongs to. Stop bumps the generation, so a
        // thread that outlives its own run — a loop that ignored the interrupt —
        // can no longer print into whatever the user started next.
        final long generation = RunCancellation.current();
        return new Callback() {
            private boolean superseded() {
                return !RunCancellation.isCurrent(generation);
            }

            @Override
            public void onProgress(String message) {
                if (superseded()) return;
                if (context != null) {
                    try {
                        if (new AppPreferences(context).isVerboseLoggingEnabled()) {
                            Log.d("WireDroidConsole", message);
                        }
                    } catch (Throwable ignored) {}
                }
                original.onProgress(message);
            }

            @Override
            public void onResult(String output) {
                if (superseded()) return;
                if (context != null) {
                    try {
                        if (new AppPreferences(context).isVerboseLoggingEnabled()) {
                            Log.d("WireDroidConsole", output);
                        }
                    } catch (Throwable ignored) {}
                }
                original.onResult(output);
            }

            @Override
            public void onOutput(String chunk) {
                // Forwarded explicitly: this wrapper is an anonymous class, so
                // anything it does not override falls back to the interface
                // default — and for onOutput that default is to drop the text.
                if (superseded()) return;
                original.onOutput(chunk);
            }

            @Override
            public void onProblems(List<ProblemItem> problems) {
                if (superseded()) return;
                original.onProblems(problems);
            }

            @Override
            public void onTestResults(List<com.ccs.wiredroid.maven.MavenTestRunner.TestClassResult> results,
                                      int totalTests, int passedTests, int failedTests,
                                      int skippedTests, long durationMs) {
                if (superseded()) return;
                original.onTestResults(results, totalTests, passedTests, failedTests,
                        skippedTests, durationMs);
            }
        };
    }

    public static boolean downloadFile(String urlStr, File dest, int connectTimeout, int readTimeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(connectTimeout);
        c.setReadTimeout(readTimeout);
        c.setRequestMethod("GET");
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                c.disconnect();
                return false;
            }
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            return true;
        } finally {
            c.disconnect();
        }
    }

    public static String extractClassNamePublic(String source) {
        return EcjCompiler.extractClassName(source);
    }

    public static File ensureAndroidJarPublic(Context context, File cacheDir) throws Exception {
        return D8Dexer.ensureAndroidJar(context, cacheDir);
    }

    public static void writeUtf8Public(File f, String s) throws Exception {
        EcjCompiler.writeUtf8(f, s);
    }

    public static String compileEcjPublic(File androidJar, String classpath, File outDir,
                                          String javaTarget, File... srcFiles) {
        return EcjCompiler.compileEcj(androidJar, classpath, outDir, javaTarget, srcFiles);
    }

    public static File findClassFilePublic(File dir, String className) {
        return EcjCompiler.findClassFile(dir, className);
    }

    public static void runD8DexPublic(File androidJar, File dexDir, List<File> classFiles) throws Exception {
        D8Dexer.runD8Dex(androidJar, dexDir, classFiles);
    }

    public static void runD8DexPublic(File androidJar, File dexDir, File classFile) throws Exception {
        D8Dexer.runD8Dex(androidJar, dexDir, classFile);
    }

    private static void cleanKotlinCache(File cacheDir) {
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (name.equals("android.jar") || name.equals("dex")) continue;
            if (name.startsWith("kotlin-stdlib") && name.endsWith(".jar")) continue;
            if (name.equals("kotlin_plugin_root")) continue;
            if (name.endsWith(".class") || name.endsWith(".kt")) {
                if (!f.delete()) Log.w("KotlinCompiler", "Could not delete " + f);
                continue;
            }
            if (f.isDirectory()) {
                EcjCompiler.deleteRecursive(f);
            }
        }
    }
}
