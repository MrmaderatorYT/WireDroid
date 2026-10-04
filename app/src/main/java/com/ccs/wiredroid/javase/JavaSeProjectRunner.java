package com.ccs.wiredroid.javase;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.maven.MavenDependencyResolver;
import com.ccs.wiredroid.maven.MavenLifecycle;
import com.ccs.wiredroid.maven.MavenPaths;
import com.ccs.wiredroid.maven.PomModel;
import com.ccs.wiredroid.project.ProjectScanner;
import com.ccs.wiredroid.tools.compilers.EcjCompiler;
import com.ccs.wiredroid.tools.compilers.ProjectCompiler;
import com.ccs.wiredroid.tools.compilers.RunConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Java source -> ECJ -> class files -> embedded Java SE 21. */
public final class JavaSeProjectRunner {

    private static final String BOOTSTRAP_CLASS =
            "com.ccs.wiredroid.javase.bootstrap.JavaSeBootstrap";
    private static final String MARKER_PREFIX = "\u001eWIREDROID:";
    private static final String COMPILE_OK = MARKER_PREFIX + "COMPILE_OK";
    private static final String COMPILE_FAILED = MARKER_PREFIX + "COMPILE_FAILED";
    private static final String RUN_OK = MARKER_PREFIX + "RUN_OK";
    private static final String RUN_FAILED = MARKER_PREFIX + "RUN_FAILED";

    private static final Pattern MAIN_METHOD = Pattern.compile(
            "(?s)\\bpublic\\s+static\\s+void\\s+main\\s*\\(\\s*"
                    + "(?:java\\.lang\\.)?String\\s*(?:\\[\\s*]|\\.\\.\\.)");
    private static final Pattern PUBLIC_TYPE = Pattern.compile(
            "(?m)\\bpublic\\s+(?:final\\s+|abstract\\s+)?(?:class|record|enum)\\s+(\\w+)");
    private static final Pattern ANY_TYPE = Pattern.compile(
            "(?m)\\b(?:class|record|enum)\\s+(\\w+)");

    private JavaSeProjectRunner() {}

    public static void compileAndRunProject(Context context, File projectRoot, PomModel pom,
                                            ProjectCompiler.Callback rawCallback) {
        ProjectCompiler.Callback callback = ProjectCompiler.wrapCallback(context, rawCallback);
        com.ccs.wiredroid.tools.compilers.RunCancellation.newWorker(() -> prepareProject(context.getApplicationContext(), projectRoot, pom, callback),
                "java-se-project").start();
    }

    public static void compileAndRunTests(Context context, File projectRoot, PomModel pom,
                                          File singleTestFile, ProjectCompiler.Callback rawCallback) {
        ProjectCompiler.Callback callback = ProjectCompiler.wrapCallback(context, rawCallback);
        com.ccs.wiredroid.tools.compilers.RunCancellation.newWorker(() -> prepareProjectTests(context.getApplicationContext(), projectRoot, pom,
                singleTestFile, callback), "java-se-tests").start();
    }

    public static void runSingleSource(Context context, String sourceCode, File logicalSourceFile,
                                       File projectRoot, ProjectCompiler.Callback rawCallback) {
        ProjectCompiler.Callback callback = ProjectCompiler.wrapCallback(context, rawCallback);
        com.ccs.wiredroid.tools.compilers.RunCancellation.newWorker(() -> prepareSingleSource(context.getApplicationContext(), sourceCode,
                logicalSourceFile, projectRoot, false, callback), "java-se-source").start();
    }

    public static void runSingleTestSource(Context context, String sourceCode, File logicalSourceFile,
                                           File projectRoot, ProjectCompiler.Callback rawCallback) {
        ProjectCompiler.Callback callback = ProjectCompiler.wrapCallback(context, rawCallback);
        com.ccs.wiredroid.tools.compilers.RunCancellation.newWorker(() -> prepareSingleSource(context.getApplicationContext(), sourceCode,
                logicalSourceFile, projectRoot, true, callback), "java-se-test-source").start();
    }

    private static void prepareProject(Context context, File projectRoot, PomModel pom,
                                       ProjectCompiler.Callback callback) {
        try {
            List<File> sources = ProjectScanner.listJavaSources(projectRoot);
            if (sources.isEmpty()) {
                ProjectCompiler.postResult(callback, context.getString(R.string.javase_no_sources));
                return;
            }

            File runtime = installRuntime(context, callback);
            File bootstrap = JavaSeRuntimeManager.ensureBootstrapJar(context);
            File output = MavenPaths.targetClassesDir(projectRoot);
            deleteRecursive(output);
            if (!output.mkdirs() && !output.isDirectory()) {
                throw new IOException("Cannot create " + output);
            }
            MavenLifecycle.processResources(projectRoot,
                    line -> ProjectCompiler.postProgress(callback, line));

            List<File> dependencies = MavenDependencyResolver.resolve(projectRoot, pom,
                    line -> ProjectCompiler.postProgress(callback, line));
            String dependencyClasspath = EcjCompiler.classpath(dependencies);
            String runtimeClasspath = joinClasspath(output, dependencyClasspath);
            String mainClass = resolveMainClass(pom, sources);

            ProjectCompiler.postProgress(callback,
                    context.getString(R.string.javase_compiling));
            launch(context, runtime, bootstrap, projectRoot, output, "compile-run", mainClass,
                    dependencyClasspath, runtimeClasspath, sources,
                    RunConfig.from(context), callback, null);
        } catch (Throwable throwable) {
            ProjectCompiler.postResult(callback, systemError(throwable));
        }
    }

    private static void prepareProjectTests(Context context, File projectRoot, PomModel pom,
                                            File singleTestFile, ProjectCompiler.Callback callback) {
        try {
            List<File> mainSources = ProjectScanner.listJavaSources(projectRoot);
            List<File> testSources = ProjectScanner.listTestSources(projectRoot);

            Set<File> combinedSources = new HashSet<>(mainSources);
            combinedSources.addAll(testSources);
            if (singleTestFile != null && singleTestFile.isFile()) {
                combinedSources.add(singleTestFile);
            }

            if (combinedSources.isEmpty()) {
                ProjectCompiler.postResult(callback, context.getString(R.string.javase_no_sources));
                return;
            }

            File runtime = installRuntime(context, callback);
            File bootstrap = JavaSeRuntimeManager.ensureBootstrapJar(context);
            File output = MavenPaths.targetClassesDir(projectRoot);
            deleteRecursive(output);
            if (!output.mkdirs() && !output.isDirectory()) {
                throw new IOException("Cannot create " + output);
            }
            MavenLifecycle.processResources(projectRoot,
                    line -> ProjectCompiler.postProgress(callback, line));
            MavenLifecycle.processTestResources(projectRoot,
                    line -> ProjectCompiler.postProgress(callback, line));

            List<File> dependencies = new ArrayList<>();
            dependencies.addAll(MavenDependencyResolver.resolve(projectRoot, pom,
                    line -> ProjectCompiler.postProgress(callback, line)));
            dependencies.addAll(MavenDependencyResolver.resolveTestScoped(projectRoot, pom,
                    line -> ProjectCompiler.postProgress(callback, line)));

            String dependencyClasspath = EcjCompiler.classpath(dependencies);
            String runtimeClasspath = joinClasspath(output, dependencyClasspath);

            String targetTestClass = "";
            if (singleTestFile != null && singleTestFile.isFile()) {
                try {
                    String text = readUtf8(singleTestFile, 2 * 1024 * 1024);
                    String simple = sourceTypeName(text);
                    String pkg = EcjCompiler.extractPackageName(text);
                    targetTestClass = pkg.isEmpty() ? simple : pkg + "." + simple;
                } catch (Exception ignored) {}
            }

            ProjectCompiler.postProgress(callback, "Compiling main and test sources...");
            launch(context, runtime, bootstrap, projectRoot, output, "compile-test", targetTestClass,
                    dependencyClasspath, runtimeClasspath, new ArrayList<>(combinedSources),
                    RunConfig.from(context), callback, null);
        } catch (Throwable throwable) {
            ProjectCompiler.postResult(callback, systemError(throwable));
        }
    }

    private static void prepareSingleSource(Context context, String sourceCode,
                                            File logicalSourceFile, File projectRoot,
                                            boolean isTest,
                                            ProjectCompiler.Callback callback) {
        File runDirectory = new File(context.getCacheDir(),
                "java_se_source_" + System.currentTimeMillis());
        try {
            File runtime = installRuntime(context, callback);
            File bootstrap = JavaSeRuntimeManager.ensureBootstrapJar(context);
            File output = new File(runDirectory, "classes");
            if (!output.mkdirs() && !output.isDirectory()) {
                throw new IOException("Cannot create " + output);
            }
            String simpleName = sourceTypeName(sourceCode);
            File source = new File(runDirectory, simpleName + ".java");
            EcjCompiler.writeUtf8(source, sourceCode);
            String packageName = EcjCompiler.extractPackageName(sourceCode);
            String targetClass = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
            File workingDirectory = projectRoot != null && projectRoot.isDirectory()
                    ? projectRoot
                    : logicalSourceFile != null && logicalSourceFile.getParentFile() != null
                    ? logicalSourceFile.getParentFile() : runDirectory;

            ProjectCompiler.postProgress(callback,
                    context.getString(R.string.javase_compiling));
            List<File> sources = new ArrayList<>();
            sources.add(source);

            // If inside a project, also include other project java sources so dependencies compile
            if (projectRoot != null && projectRoot.isDirectory()) {
                List<File> projectSources = ProjectScanner.listJavaSources(projectRoot);
                for (File f : projectSources) {
                    if (!f.getName().equals(source.getName())) sources.add(f);
                }
            }

            String operation = isTest ? "compile-test" : "compile-run";
            launch(context, runtime, bootstrap, workingDirectory, output, operation, targetClass,
                    "", output.getAbsolutePath(), sources, RunConfig.from(context), callback,
                    () -> deleteRecursive(runDirectory));
        } catch (Throwable throwable) {
            deleteRecursive(runDirectory);
            ProjectCompiler.postResult(callback, systemError(throwable));
        }
    }

    private static File installRuntime(Context context, ProjectCompiler.Callback callback)
            throws IOException {
        final JavaSeRuntimeManager.Stage[] previousStage = {null};
        final int[] previousPercent = {-1};
        return JavaSeRuntimeManager.ensureInstalled(context, (stage, percent) -> {
            if (stage == previousStage[0] && percent == previousPercent[0]) return;
            previousStage[0] = stage;
            previousPercent[0] = percent;
            String message;
            switch (stage) {
                case VERIFYING:
                    message = context.getString(R.string.javase_runtime_verifying);
                    break;
                case INSTALLING:
                    message = context.getString(R.string.javase_runtime_installing, percent);
                    break;
                case READY:
                default:
                    message = context.getString(R.string.javase_runtime_ready);
                    break;
            }
            ProjectCompiler.postProgress(callback, message);
        });
    }

    private static void launch(Context context, File runtime, File bootstrap,
                               File workingDirectory, File outputDirectory, String operation,
                               String mainClass, String compileClasspath, String runtimeClasspath,
                               List<File> sources, RunConfig runConfig,
                               ProjectCompiler.Callback callback, Runnable cleanup) {
        JavaSeRunner.Request request = new JavaSeRunner.Request();
        request.runtimeHome = runtime;
        request.workingDirectory = workingDirectory;
        request.environment.putAll(runConfig.env);
        request.onOutput = chunk -> ProjectCompiler.postOutput(callback, chunk);

        File temp = new File(context.getCacheDir(), "java-se-tmp");
        temp.mkdirs();
        int heapMb = Build.SUPPORTED_64_BIT_ABIS.length > 0 ? 256 : 192;
        request.arguments.add("java");
        request.arguments.add("-Xms32m");
        request.arguments.add("-Xmx" + heapMb + "m");
        request.arguments.add("-XX:+UseSerialGC");
        request.arguments.add("-XX:ActiveProcessorCount="
                + Math.max(1, Runtime.getRuntime().availableProcessors()));
        request.arguments.add("-Djava.awt.headless=true");
        request.arguments.add("-Dfile.encoding=UTF-8");
        request.arguments.add("-Dsun.stdout.encoding=UTF-8");
        request.arguments.add("-Dsun.stderr.encoding=UTF-8");
        request.arguments.add("-Djdk.lang.Process.launchMechanism=FORK");
        request.arguments.add("-Djava.io.tmpdir=" + temp.getAbsolutePath());
        request.arguments.add("-Duser.home=" + workingDirectory.getAbsolutePath());
        request.arguments.add("-cp");
        request.arguments.add(bootstrap.getAbsolutePath());
        request.arguments.add(BOOTSTRAP_CLASS);
        request.arguments.add(operation != null ? operation : "compile-run");
        request.arguments.add("21");
        request.arguments.add(outputDirectory.getAbsolutePath());
        request.arguments.add(mainClass != null ? mainClass : "");
        request.arguments.add(compileClasspath != null ? compileClasspath : "");
        request.arguments.add(runtimeClasspath);
        request.arguments.add(Integer.toString(sources.size()));
        for (File source : sources) request.arguments.add(source.getAbsolutePath());
        for (String argument : runConfig.args) request.arguments.add(argument);

        if ("compile-test".equals(operation)) {
            ProjectCompiler.postProgress(callback,
                    mainClass != null && !mainClass.isEmpty() ? "Running tests in " + mainClass + "..." : "Running tests...");
        } else {
            ProjectCompiler.postProgress(callback,
                    context.getString(R.string.javase_running,
                            mainClass == null || mainClass.isEmpty() ? "main" : mainClass));
        }

        JavaSeRunner.launch(context, request, result -> {
            try {
                handleResult(context, workingDirectory, result, callback);
            } finally {
                if (cleanup != null) cleanup.run();
            }
        });
    }

    private static void handleResult(Context context, File projectRoot, JavaSeRunner.Result result,
                                     ProjectCompiler.Callback callback) {
        String raw = result.output != null ? result.output : "";
        boolean compileOk = raw.contains(COMPILE_OK);
        boolean compileFailed = raw.contains(COMPILE_FAILED);
        boolean runFailed = raw.contains(RUN_FAILED);
        boolean runOk = raw.contains(RUN_OK);
        String output = stripMarkers(raw).trim();

        if (compileFailed) {
            String diagnostics = output.isEmpty() ? "ECJ compilation failed" : output;
            ProjectCompiler.postCompileFailure(callback, context, projectRoot, diagnostics, null,
                    "Compilation Error:\n" + diagnostics);
            return;
        }
        if (!compileOk) {
            String detail = result.error != null ? result.error : output;
            ProjectCompiler.postResult(callback, "System Error: "
                    + (detail == null || detail.isEmpty()
                    ? context.getString(R.string.javase_process_died) : detail));
            return;
        }

        ProjectCompiler.postProblems(callback, context, projectRoot, "", null);
        if (runFailed) {
            ProjectCompiler.postResult(callback, output);
        } else if (result.error != null && !runOk && !result.processDied) {
            ProjectCompiler.postResult(callback, "System Error: " + result.error
                    + (output.isEmpty() ? "" : "\n" + output));
        } else if (result.exitCode != 0 && !runOk && !result.processDied) {
            ProjectCompiler.postResult(callback, "System Error: embedded JVM exited with code "
                    + result.exitCode + (output.isEmpty() ? "" : "\n" + output));
        } else {
            // A process death after COMPILE_OK commonly means user code called
            // System.exit. Its output is still a valid completed console run.
            ProjectCompiler.postResult(callback, output);
        }
    }

    private static String stripMarkers(String output) {
        return output.replace(COMPILE_OK, "")
                .replace(COMPILE_FAILED, "")
                .replace(RUN_OK, "")
                .replace(RUN_FAILED, "");
    }

    private static String resolveMainClass(PomModel pom, List<File> sources) {
        if (pom != null) {
            String main = pom.mainClass;
            if (main == null || main.trim().isEmpty()) {
                main = pom.properties.get("mainClass");
                if (main != null) main = pom.resolveProperty(main);
            }
            if (main != null && !main.trim().isEmpty()) return main.trim();
        }
        for (File source : sources) {
            try {
                String text = readUtf8(source, 2 * 1024 * 1024);
                if (!MAIN_METHOD.matcher(text).find()) continue;
                String type = sourceTypeName(text);
                String packageName = EcjCompiler.extractPackageName(text);
                return packageName.isEmpty() ? type : packageName + "." + type;
            } catch (Exception ignored) {}
        }
        return "";
    }

    private static String sourceTypeName(String source) {
        Matcher matcher = PUBLIC_TYPE.matcher(source);
        if (!matcher.find()) matcher = ANY_TYPE.matcher(source);
        return matcher.find(0) ? matcher.group(1) : EcjCompiler.extractClassName(source);
    }

    private static String joinClasspath(File first, String rest) {
        return rest == null || rest.isEmpty() ? first.getAbsolutePath()
                : first.getAbsolutePath() + File.pathSeparator + rest;
    }

    private static String readUtf8(File file, int maxBytes) throws IOException {
        if (file.length() > maxBytes) throw new IOException("Source file is too large");
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        }
    }

    private static String systemError(Throwable throwable) {
        String message = throwable.getMessage();
        return "System Error: " + (message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message)
                + "\n" + Log.getStackTraceString(throwable);
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }
}
