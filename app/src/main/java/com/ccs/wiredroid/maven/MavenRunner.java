package com.ccs.wiredroid.maven;

import android.content.Context;
import android.util.Log;

import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.ccs.wiredroid.R;
import com.ccs.wiredroid.project.ProjectScanner;
import com.ccs.wiredroid.tools.compilers.D8Dexer;
import com.ccs.wiredroid.tools.compilers.DexRunner;
import com.ccs.wiredroid.tools.compilers.EcjCompiler;
import com.ccs.wiredroid.tools.compilers.KotlinCompiler;
import com.ccs.wiredroid.tools.compilers.NativeBuildHelper;
import com.ccs.wiredroid.tools.compilers.ProjectCompiler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Handles Maven lifecycle execution phases: compile, package, clean, install, testCompile, testRun.
 */
public final class MavenRunner {

    private MavenRunner() {}

    public static void mavenCompileAndRun(Context context, File projectRoot, PomModel pom,
                                          ProjectCompiler.Callback rawCallback) {
        final ProjectCompiler.Callback callback = wrapCallback(context, rawCallback);
        com.ccs.wiredroid.tools.compilers.RunCancellation.newWorker(() -> {
            try {
                D8Dexer.cleanupOldDexDirs(context);

                File androidJar = D8Dexer.ensureAndroidJar(context,
                        new File(context.getCacheDir(), "compile_cache"));
                File outDir = MavenPaths.targetClassesDir(projectRoot);
                outDir.mkdirs();

                NativeBuildHelper.cleanupOldJniLibs(context);
                File jniLibsDir = NativeBuildHelper.compileNativeSources(context, projectRoot, callback);

                List<File> depJars = MavenDependencyResolver.resolve(projectRoot, pom,
                        msg -> ProjectCompiler.postProgress(callback, msg));

                if (stopped()) return;

                List<File> javaSources = ProjectScanner.listJavaSources(projectRoot);
                List<File> ktSources = ProjectScanner.listKotlinSources(projectRoot);
                if (javaSources.isEmpty() && ktSources.isEmpty()) {
                    ProjectCompiler.postResult(callback, "Немає сирцевих файлів (.java або .kt) у проєкті");
                    return;
                }

                if (!ktSources.isEmpty()) {
                    File stdlibJar = KotlinCompiler.ensureKotlinStdlib(context, new File(context.getCacheDir(), "compile_cache"));
                    if (stdlibJar != null && !depJars.contains(stdlibJar)) {
                        depJars.add(stdlibJar);
                    }
                    boolean ktOk = KotlinCompiler.compileProject(context, projectRoot, ktSources, javaSources, depJars, androidJar, outDir, callback);
                    if (!ktOk) return;
                }

                if (!javaSources.isEmpty()) {
                    String cp = EcjCompiler.classpath(depJars);
                    if (outDir.exists()) {
                        if (!cp.isEmpty()) cp += File.pathSeparator;
                        cp += outDir.getAbsolutePath();
                    }
                    List<File> srcArgs = new ArrayList<>(javaSources);
                    File[] ecjFiles = srcArgs.toArray(new File[0]);

                    String ecjErr = EcjCompiler.compileEcjMulti(androidJar, cp, outDir, javaTarget(context), ecjFiles);
                    if (ecjErr != null) {
                        ProjectCompiler.postCompileFailure(callback, context, projectRoot, ecjErr, null,
                                "Compilation Error:\n" + ecjErr);
                        return;
                    }
                }
                if (stopped()) return;
                ProjectCompiler.postProblems(callback, context, projectRoot, "", null);

                List<Path> classes = new ArrayList<>();
                EcjCompiler.collectClasses(outDir, classes);
                if (classes.isEmpty()) {
                    ProjectCompiler.postResult(callback, "Немає .class після компіляції");
                    return;
                }

                File dexDir = new File(context.getCacheDir(), "maven_dex_" + System.currentTimeMillis());
                dexDir.mkdirs();

                D8Command.Builder b = D8Command.builder()
                        .setOutput(dexDir.toPath(), OutputMode.DexIndexed)
                        .setMinApiLevel(android.os.Build.VERSION.SDK_INT);
                b.addLibraryFiles(androidJar.toPath());
                for (File j : depJars) {
                    if (j.exists()) b.addProgramFiles(j.toPath());
                }
                for (Path c : classes) b.addProgramFiles(c);
                if (stopped()) return;
                D8.run(b.build());

                String mainClass = pom.mainClass;
                if (mainClass == null || mainClass.isEmpty()) {
                    mainClass = pom.properties.get("mainClass");
                    if (mainClass != null) mainClass = pom.resolveProperty(mainClass);
                }
                if (mainClass == null || mainClass.isEmpty()) {
                    mainClass = DexRunner.findMainClass(classes);
                }
                if (mainClass == null || mainClass.isEmpty()) {
                    for (Path c : classes) {
                        String name = c.getFileName().toString();
                        if (name.endsWith("Kt.class")) {
                            String rel = outDir.toPath().relativize(c).toString().replace('/', '.').replace('\\', '.');
                            mainClass = rel.substring(0, rel.length() - ".class".length());
                            break;
                        }
                    }
                }
                if (mainClass == null || mainClass.isEmpty()) mainClass = "com.ccs.App";

                ProjectCompiler.postProgress(callback, "Running " + mainClass + "...");
                DexRunner.runDexMain(context, jniLibsDir, dexDir, mainClass, callback);
            } catch (Exception e) {
                ProjectCompiler.postResult(callback, "System Error: " + e.getMessage() + "\n" + Log.getStackTraceString(e));
            }
        }).start();
    }

    public static void mavenCompile(Context context, File projectRoot, PomModel pom,
                                    ProjectCompiler.Callback rawCallback) {
        final ProjectCompiler.Callback callback = wrapCallback(context, rawCallback);
        com.ccs.wiredroid.tools.compilers.RunCancellation.newWorker(() -> {
            try {
                mavenCompileOnly(context, projectRoot, pom, callback);
                ProjectCompiler.postResult(callback, "compile: BUILD SUCCESS\n"
                        + MavenPaths.targetClassesDir(projectRoot).getAbsolutePath());
            } catch (Exception e) {
                ProjectCompiler.postResult(callback, "compile failed: " + e.getMessage());
            }
        }, "maven-compile").start();
    }

    public static void mavenPackage(Context context, File projectRoot, PomModel pom, ProjectCompiler.Callback rawCallback) {
        final ProjectCompiler.Callback callback = wrapCallback(context, rawCallback);
        com.ccs.wiredroid.tools.compilers.RunCancellation.newWorker(() -> {
            try {
                mavenCompileOnly(context, projectRoot, pom, callback);
                String mainClass = pom.mainClass;
                if (mainClass == null || mainClass.isEmpty()) {
                    mainClass = pom.properties.get("mainClass");
                    if (mainClass != null) mainClass = pom.resolveProperty(mainClass);
                }
                if (mainClass == null || mainClass.isEmpty()) mainClass = "com.ccs.App";

                File target = new File(projectRoot, "target");
                target.mkdirs();
                File jar = new File(target, pom.artifactId + "-" + pom.version + ".jar");

                Manifest manifest = new Manifest();
                manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
                manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);

                try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar), manifest)) {
                    File classesDir = MavenPaths.targetClassesDir(projectRoot);
                    addDirectoryToJar(classesDir, classesDir, jos);
                }

                ProjectCompiler.postProgress(callback, "JAR: " + jar.getAbsolutePath());
                ProjectCompiler.postResult(callback, "package: BUILD SUCCESS\n" + jar.getAbsolutePath());
            } catch (Exception e) {
                ProjectCompiler.postResult(callback, "package failed: " + e.getMessage());
            }
        }).start();
    }

    public static void mavenTestCompile(Context context, File projectRoot, PomModel pom,
                                        ProjectCompiler.Callback rawCallback) {
        final ProjectCompiler.Callback callback = wrapCallback(context, rawCallback);
        com.ccs.wiredroid.tools.compilers.RunCancellation.newWorker(() -> {
            try {
                File androidJar = D8Dexer.ensureAndroidJar(context,
                        new File(context.getCacheDir(), "compile_cache"));
                File outDir = MavenPaths.targetTestClassesDir(projectRoot);
                outDir.mkdirs();

                List<File> deps = new ArrayList<>();
                deps.addAll(MavenDependencyResolver.resolve(projectRoot, pom,
                        msg -> ProjectCompiler.postProgress(callback, msg)));
                deps.addAll(MavenDependencyResolver.resolveTestScoped(projectRoot, pom,
                        msg -> ProjectCompiler.postProgress(callback, msg)));

                String cp = EcjCompiler.classpath(deps);
                File classesMain = MavenPaths.targetClassesDir(projectRoot);
                if (!classesMain.exists() || classesMain.list() == null || classesMain.list().length == 0) {
                    ProjectCompiler.postResult(callback, "Спочатку виконайте compile (Run).");
                    return;
                }
                if (!cp.isEmpty()) cp += File.pathSeparator;
                cp += classesMain.getAbsolutePath();

                if (stopped()) return;

                List<File> testJava = ProjectScanner.listTestSources(projectRoot);
                List<File> testKt = ProjectScanner.listTestKotlinSources(projectRoot);
                if (testJava.isEmpty() && testKt.isEmpty()) {
                    ProjectCompiler.postResult(callback, "Немає тестів у src/test/java або src/test/kotlin");
                    return;
                }

                if (!testKt.isEmpty()) {
                    File stdlibJar = KotlinCompiler.ensureKotlinStdlib(context, new File(context.getCacheDir(), "compile_cache"));
                    if (stdlibJar != null && !deps.contains(stdlibJar)) {
                        deps.add(stdlibJar);
                    }
                    List<File> allDeps = new ArrayList<>(deps);
                    allDeps.add(classesMain);
                    boolean ktOk = KotlinCompiler.compileProject(context, projectRoot, testKt, testJava, allDeps, androidJar, outDir, callback);
                    if (!ktOk) return;
                }

                if (!testJava.isEmpty()) {
                    if (outDir.exists()) cp += File.pathSeparator + outDir.getAbsolutePath();
                    String ecjErr = EcjCompiler.compileEcjMulti(androidJar, cp, outDir, javaTarget(context),
                            testJava.toArray(new File[0]));
                    if (ecjErr != null) {
                        ProjectCompiler.postCompileFailure(callback, context, projectRoot, ecjErr, null,
                                "Test compile failed:\n" + ecjErr);
                        return;
                    }
                }
                ProjectCompiler.postResult(callback, "testCompile: BUILD SUCCESS\nTest sources compiled to target/test-classes.");
            } catch (Exception e) {
                ProjectCompiler.postResult(callback, "testCompile: " + e.getMessage());
            }
        }).start();
    }

    public static void mavenCompileOnly(Context context, File projectRoot, PomModel pom,
                                         ProjectCompiler.Callback callback) throws Exception {
        com.ccs.wiredroid.profiler.PerformanceMonitor monitor =
                com.ccs.wiredroid.profiler.PerformanceMonitor.get();
        com.ccs.wiredroid.profiler.PerformanceMonitor.Timer timer = monitor == null
                ? null : monitor.timer("compile.maven");
        try {
        File androidJar = D8Dexer.ensureAndroidJar(context, new File(context.getCacheDir(), "compile_cache"));
        File outDir = MavenPaths.targetClassesDir(projectRoot);
        outDir.mkdirs();

        MavenLifecycle.processResources(projectRoot, msg -> ProjectCompiler.postProgress(callback, msg));

        List<File> depJars = MavenDependencyResolver.resolve(projectRoot, pom,
                msg -> ProjectCompiler.postProgress(callback, msg));
        
        List<File> javaSources = ProjectScanner.listJavaSources(projectRoot);
        List<File> ktSources = ProjectScanner.listKotlinSources(projectRoot);
        if (javaSources.isEmpty() && ktSources.isEmpty()) throw new IllegalStateException("no sources");

        if (!ktSources.isEmpty()) {
            File stdlibJar = KotlinCompiler.ensureKotlinStdlib(context, new File(context.getCacheDir(), "compile_cache"));
            if (stdlibJar != null && !depJars.contains(stdlibJar)) {
                depJars.add(stdlibJar);
            }
            boolean ktOk = KotlinCompiler.compileProject(context, projectRoot, ktSources, javaSources, depJars, androidJar, outDir, callback);
            if (!ktOk) throw new IllegalStateException("Kotlin compilation failed");
        }

        if (!javaSources.isEmpty()) {
            String cp = EcjCompiler.classpath(depJars);
            if (outDir.exists()) {
                if (!cp.isEmpty()) cp += File.pathSeparator;
                cp += outDir.getAbsolutePath();
            }
            String ecjErr = EcjCompiler.compileEcjMulti(androidJar, cp, outDir, javaTarget(context),
                    javaSources.toArray(new File[0]));
            if (ecjErr != null) throw new IllegalStateException(ecjErr);
        }
        } finally {
            if (timer != null) timer.close();
        }
    }

    public static void mavenClean(Context context, File projectRoot, ProjectCompiler.Callback rawCallback) {
        final ProjectCompiler.Callback callback = wrapCallback(context, rawCallback);
        com.ccs.wiredroid.tools.compilers.RunCancellation.newWorker(() -> {
            try {
                MavenLifecycle.clean(projectRoot, msg -> ProjectCompiler.postProgress(callback, msg));
                ProjectCompiler.postResult(callback, "clean: BUILD SUCCESS\ntarget/ deleted.");
            } catch (Exception e) {
                ProjectCompiler.postResult(callback, "clean failed: " + e.getMessage());
            }
        }).start();
    }

    public static void mavenInstall(Context context, File projectRoot, PomModel pom, ProjectCompiler.Callback rawCallback) {
        final ProjectCompiler.Callback callback = wrapCallback(context, rawCallback);
        com.ccs.wiredroid.tools.compilers.RunCancellation.newWorker(() -> {
            try {
                mavenCompileOnly(context, projectRoot, pom, callback);

                String mainClass = pom.mainClass;
                if (mainClass == null || mainClass.isEmpty()) {
                    mainClass = pom.properties.get("mainClass");
                    if (mainClass != null) mainClass = pom.resolveProperty(mainClass);
                }
                if (mainClass == null || mainClass.isEmpty()) mainClass = "com.ccs.App";

                File target = new File(projectRoot, "target");
                target.mkdirs();
                File jar = new File(target, pom.artifactId + "-" + pom.version + ".jar");

                Manifest manifest = new Manifest();
                manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
                manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);

                try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar), manifest)) {
                    File classesDir = MavenPaths.targetClassesDir(projectRoot);
                    addDirectoryToJar(classesDir, classesDir, jos);
                }

                ProjectCompiler.postProgress(callback, "JAR: " + jar.getAbsolutePath());

                File installed = MavenLifecycle.install(projectRoot, pom,
                        msg -> ProjectCompiler.postProgress(callback, msg));
                if (installed != null) {
                    ProjectCompiler.postResult(callback, "install: BUILD SUCCESS\nJAR installed to:\n"
                            + installed.getAbsolutePath());
                } else {
                    ProjectCompiler.postResult(callback, "install: BUILD FAILURE\nCould not install JAR.");
                }
            } catch (Exception e) {
                ProjectCompiler.postResult(callback, "install failed: " + e.getMessage());
            }
        }).start();
    }

    public static void mavenTestRun(Context context, File projectRoot, PomModel pom, ProjectCompiler.Callback rawCallback) {
        final ProjectCompiler.Callback callback = wrapCallback(context, rawCallback);
        com.ccs.wiredroid.tools.compilers.RunCancellation.newWorker(() -> {
            try {
                File androidJar = D8Dexer.ensureAndroidJar(context,
                        new File(context.getCacheDir(), "compile_cache"));

                File classesMain = MavenPaths.targetClassesDir(projectRoot);
                if (!classesMain.exists() || isEmpty(classesMain)) {
                    mavenCompileOnly(context, projectRoot, pom, callback);
                }

                File outDir = MavenPaths.targetTestClassesDir(projectRoot);
                outDir.mkdirs();

                MavenLifecycle.processTestResources(projectRoot,
                        msg -> ProjectCompiler.postProgress(callback, msg));

                List<File> deps = new ArrayList<>();
                deps.addAll(MavenDependencyResolver.resolve(projectRoot, pom,
                        msg -> ProjectCompiler.postProgress(callback, msg)));
                deps.addAll(MavenDependencyResolver.resolveTestScoped(projectRoot, pom,
                        msg -> ProjectCompiler.postProgress(callback, msg)));

                String cp = EcjCompiler.classpath(deps);
                if (!classesMain.exists()) classesMain.mkdirs();
                if (!cp.isEmpty()) cp += File.pathSeparator;
                cp += classesMain.getAbsolutePath();

                if (stopped()) return;

                List<File> testJava = ProjectScanner.listTestSources(projectRoot);
                List<File> testKt = ProjectScanner.listTestKotlinSources(projectRoot);
                if (testJava.isEmpty() && testKt.isEmpty()) {
                    ProjectCompiler.postResult(callback, "No tests found in src/test/java or src/test/kotlin");
                    return;
                }

                ProjectCompiler.postProgress(callback, "Compiling test sources...");
                if (!testKt.isEmpty()) {
                    File stdlibJar = KotlinCompiler.ensureKotlinStdlib(context, new File(context.getCacheDir(), "compile_cache"));
                    if (stdlibJar != null && !deps.contains(stdlibJar)) {
                        deps.add(stdlibJar);
                    }
                    List<File> allDeps = new ArrayList<>(deps);
                    allDeps.add(classesMain);
                    boolean ktOk = KotlinCompiler.compileProject(context, projectRoot, testKt, testJava, allDeps, androidJar, outDir, callback);
                    if (!ktOk) return;
                }

                if (!testJava.isEmpty()) {
                    String testCp = cp;
                    if (outDir.exists()) testCp += File.pathSeparator + outDir.getAbsolutePath();
                    String ecjErr = EcjCompiler.compileEcjMulti(androidJar, testCp, outDir, javaTarget(context),
                            testJava.toArray(new File[0]));
                    if (ecjErr != null) {
                        ProjectCompiler.postCompileFailure(callback, context, projectRoot, ecjErr, null,
                                "Test compilation failed:\n" + ecjErr);
                        return;
                    }
                }
                if (stopped()) return;
                ProjectCompiler.postProgress(callback, "Test sources compiled successfully.");

                List<Path> allClasses = new ArrayList<>();
                EcjCompiler.collectClasses(classesMain, allClasses);
                EcjCompiler.collectClasses(outDir, allClasses);

                if (allClasses.isEmpty()) {
                    ProjectCompiler.postResult(callback, "No .class files found for dexing.");
                    return;
                }

                ProjectCompiler.postProgress(callback, "Dexing test classes...");
                File dexDir = new File(context.getCacheDir(), "maven_test_dex_" + System.currentTimeMillis());
                dexDir.mkdirs();

                D8Command.Builder b = D8Command.builder()
                        .setOutput(dexDir.toPath(), OutputMode.DexIndexed)
                        .setMinApiLevel(android.os.Build.VERSION.SDK_INT);
                b.addLibraryFiles(androidJar.toPath());
                for (File j : deps) {
                    if (j.exists()) b.addProgramFiles(j.toPath());
                }
                for (Path c : allClasses) b.addProgramFiles(c);
                if (stopped()) return;
                D8.run(b.build());

                if (stopped()) return;
                ProjectCompiler.postProgress(callback, "Running tests...");
                MavenTestRunner.runTests(context, dexDir, outDir, classesMain, deps, androidJar,
                        new MavenTestRunner.Callback() {
                            @Override public void onProgress(String line) {
                                ProjectCompiler.postProgress(callback, line);
                            }
                            @Override public void onResult(String output) {
                                ProjectCompiler.postResult(callback, output);
                            }
                            @Override public void onTestResults(
                                    List<MavenTestRunner.TestClassResult> results, int totalTests,
                                    int passedTests, int failedTests, int skippedTests,
                                    long durationMs) {
                                ProjectCompiler.postTestResults(callback, results, totalTests,
                                        passedTests, failedTests, skippedTests, durationMs);
                            }
                        });
            } catch (Exception e) {
                ProjectCompiler.postResult(callback, "Test execution failed: " + e.getMessage() + "\n"
                        + Log.getStackTraceString(e));
            }
        }).start();
    }

    /**
     * Whether the user has asked this run to stop.
     *
     * <p>Checked between phases rather than inside them: ECJ and D8 are opaque
     * CPU-bound calls that will not notice an interrupt, so the boundary is the
     * only place a build can be abandoned. Nothing is posted here — Stop has
     * already put its own line on the console, and the generation bump means
     * anything this run said afterwards would be dropped anyway.</p>
     */
    private static boolean stopped() {
        return com.ccs.wiredroid.tools.compilers.RunCancellation.isStopRequested();
    }

    private static boolean isEmpty(File dir) {
        File[] files = dir.listFiles();
        return files == null || files.length == 0;
    }

    private static void addDirectoryToJar(File root, File source, JarOutputStream jos)
            throws IOException {
        File[] files = source.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                addDirectoryToJar(root, f, jos);
            } else {
                String rel = root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
                jos.putNextEntry(new JarEntry(rel));
                try (FileInputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) jos.write(buf, 0, n);
                }
                jos.closeEntry();
            }
        }
    }

    private static String javaTarget(Context ctx) {
        try {
            return com.ccs.wiredroid.project.ProjectJdk.forOpenProject(ctx);
        } catch (Throwable t) {
            return com.ccs.wiredroid.util.AppPreferences.JAVA_8;
        }
    }

    private static ProjectCompiler.Callback wrapCallback(Context context, ProjectCompiler.Callback original) {
        return ProjectCompiler.wrapCallback(context, original);
    }
}
