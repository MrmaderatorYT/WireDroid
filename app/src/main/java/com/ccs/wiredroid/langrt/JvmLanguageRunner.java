package com.ccs.wiredroid.langrt;

import android.content.Context;
import android.os.Build;

import com.ccs.wiredroid.javase.JavaSeRunner;
import com.ccs.wiredroid.javase.JavaSeRuntimeManager;
import com.ccs.wiredroid.tools.compilers.ProjectCompiler;
import com.ccs.wiredroid.tools.compilers.RunConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a Groovy, Clojure or Scala file on the embedded Java SE runtime.
 *
 * <p>Not through the ECJ bootstrap the Java paths use: these languages bring
 * their own compiler, so the JVM is started on <em>their</em> entry point with
 * their jars on the classpath. Everything needed is inside the app, so this
 * works with no network.</p>
 */
public final class JvmLanguageRunner {

    private JvmLanguageRunner() {}

    /**
     * Compiles if the language needs it, then runs the file.
     *
     * <p>Returns immediately; progress and output reach the console through
     * {@code callback}.</p>
     */
    public static void run(Context context, File file, ProjectCompiler.Callback rawCallback) {
        run(context, file, null, rawCallback);
    }

    /**
     * As above, for a file that belongs to a project.
     *
     * <p>The project's build file names the language version, and that is what
     * runs — not the app-wide setting, which cannot be right for two projects
     * on different versions at once. Pass null for a scratch.</p>
     */
    public static void run(Context context, File file, File projectRoot,
                           ProjectCompiler.Callback rawCallback) {
        final Context app = context.getApplicationContext();
        final ProjectCompiler.Callback callback = ProjectCompiler.wrapCallback(app, rawCallback);
        final JvmLanguage language = JvmLanguage.of(file.getName());
        if (language == null) {
            ProjectCompiler.postResult(callback, "Not a runnable JVM language: " + file.getName());
            return;
        }

        new Thread(() -> {
            try {
                // Only when the number changes: the installer reports often,
                // and echoing every call filled the console with one line per
                // tick before anything had happened.
                final int[] lastPercent = { -1 };
                File runtime = JavaSeRuntimeManager.ensureInstalled(app, (stage, percent) -> {
                    if (stage != JavaSeRuntimeManager.Stage.INSTALLING) return;
                    int step = percent / 20 * 20;
                    if (step == lastPercent[0]) return;
                    lastPercent[0] = step;
                    ProjectCompiler.postProgress(callback, "Java SE runtime: " + step + "%");
                });

                String version = LanguageRuntimes.versionFor(app, language, projectRoot);
                ProjectCompiler.postProgress(callback,
                        language.displayName() + " " + version
                                + (LanguageRuntimes.isBundled(app, language, version)
                                    ? " (bundled)" : ""));
                List<File> jars = LanguageRuntimes.ensure(app, language, version,
                        line -> ProjectCompiler.postProgress(callback, line));

                if (language.compiles) {
                    compileThenRun(app, language, file, runtime, jars, callback);
                } else {
                    launchScript(app, language, file, runtime, jars, callback);
                }
            } catch (Throwable t) {
                ProjectCompiler.postResult(callback,
                        language.displayName() + " failed: " + describe(t));
            }
        }, "jvm-language-run").start();
    }

    /** Groovy and Clojure take the script path and run it in one go. */
    private static void launchScript(Context context, JvmLanguage language, File file,
                                     File runtime, List<File> jars,
                                     ProjectCompiler.Callback callback) {
        List<String> args = new ArrayList<>();
        args.add(language.mainClass);
        if (language == JvmLanguage.CLOJURE) {
            // Loading a .clj only defines what is in it. A file whose whole
            // body is (defn -main …) — which is how Clojure entry points are
            // written, and what both the starter template and the generated
            // Gradle script assume — therefore ran silently. Load it, then call
            // -main if it defined one.
            args.add("--init");
            args.add(file.getAbsolutePath());
            args.add("--eval");
            args.add(clojureEntryCall(namespaceIn(file)));
        } else {
            // clojure.main takes the script path as its main option: -M belongs
            // to the clj command-line tool, not to this class, and passing it
            // made Clojure look for a file literally named "-M".
            args.add(file.getAbsolutePath());
        }

        ProjectCompiler.postProgress(callback, "Running " + file.getName() + "…");
        launch(context, runtime, file.getParentFile(), jars, args, callback, null);
    }

    private static final Pattern CLOJURE_NS =
            Pattern.compile("\\(\\s*ns\\s+([A-Za-z0-9_.*+!\\-?<>=/']+)");

    /** The namespace a Clojure file declares, or null when it declares none. */
    private static String namespaceIn(File file) {
        String text = readText(file);
        if (text == null) return null;
        Matcher m = CLOJURE_NS.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /**
     * An expression that calls {@code -main} if the loaded file defined one.
     *
     * <p>Silent when it did not: a script whose body already printed has
     * nothing left to call, and saying so would be noise.</p>
     */
    private static String clojureEntryCall(String namespace) {
        String symbol = namespace == null ? "'-main" : "'" + namespace + "/-main";
        return "(when-let [f (resolve " + symbol + ")] (apply f *command-line-args*))";
    }

    /**
     * Scala compiles first: there is no runner that skips it.
     *
     * <p>Two JVM starts rather than one, because the compiler and the compiled
     * program need different classpaths — the program must not see the compiler
     * on its own path.</p>
     */
    private static void compileThenRun(Context context, JvmLanguage language, File file,
                                       File runtime, List<File> jars,
                                       ProjectCompiler.Callback callback) {
        File out = new File(context.getCacheDir(), "language-out/" + language.id);
        deleteRecursive(out);
        out.mkdirs();

        List<String> compileArgs = new ArrayList<>();
        compileArgs.add(language.mainClass);
        compileArgs.add("-d");
        compileArgs.add(out.getAbsolutePath());
        compileArgs.add("-classpath");
        compileArgs.add(classpath(jars));
        compileArgs.add(file.getAbsolutePath());

        ProjectCompiler.postProgress(callback, "Compiling " + file.getName() + "…");
        // Measured on a 3.7 GB phone: asking for 512 MB and then 1 GB both got
        // the process killed by Android's low-memory killer part-way through,
        // because the JVM's own overhead rides on top of the heap. A smaller
        // ceiling makes it collect rather than grow, which is the only way a
        // desktop compiler fits here.
        int compileHeap = 384;
        launch(context, runtime, file.getParentFile(), jars, compileArgs, compileHeap,
                callback, result -> {
            // Judged by what came out, not by the exit code. dotc ends with
            // System.exit, which takes the whole embedded JVM process with it;
            // the runner then reports the process as having died and the exit
            // code as a failure, even though the class files are sitting there.
            boolean produced = hasClassFiles(out);
            if (!produced) {
                String message = result.output == null || result.output.trim().isEmpty()
                        ? "The Scala compiler produced no classes." : result.output;
                ProjectCompiler.postResult(callback, message);
                return;
            }
            // Warnings and errors both reach the console on the way here, so a
            // compile that produced classes and complained is still a success.
            if (result.output != null && !result.output.trim().isEmpty()) {
                ProjectCompiler.postProgress(callback, result.output.trim());
            }
            String mainClass = mainClassOf(file, out);
            if (mainClass == null) {
                ProjectCompiler.postResult(callback,
                        "Compiled, but no class with a main method was produced.");
                return;
            }
            List<String> runArgs = new ArrayList<>();
            runArgs.add(mainClass);
            // The program runs against the language library and its own output;
            // the compiler is deliberately absent from this classpath.
            List<File> runtimeJars = new ArrayList<>();
            for (File jar : jars) {
                String name = jar.getName();
                if (name.contains("compiler") || name.contains("tasty")
                        || name.contains("asm") || name.contains("interface")) {
                    continue;
                }
                runtimeJars.add(jar);
            }
            runtimeJars.add(out);
            ProjectCompiler.postProgress(callback, "Running " + mainClass + "…");
            launch(context, runtime, file.getParentFile(), runtimeJars, runArgs, callback, null);
        });
    }

    /** Whether the compiler wrote anything, which is the real success signal. */
    /**
     * Whether the compiler wrote anything at all, at any depth.
     *
     * <p>A source with a {@code package} puts its classes in a matching
     * directory, so looking only at the top level reported a perfectly good
     * compile as having produced nothing.</p>
     */
    private static boolean hasClassFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (file.isDirectory()) {
                if (hasClassFiles(file)) return true;
            } else if (file.getName().endsWith(".class")) {
                return true;
            }
        }
        return false;
    }

    /** {@code package a.b.c} at the head of a Scala source, or "" for none. */
    private static String packageOf(String text) {
        if (text == null) return "";
        Matcher m = SCALA_PACKAGE.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /** The first class file under {@code dir}, as a class name relative to it. */
    private static String firstClassUnder(File dir, String prefix) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.endsWith(".class") && !name.contains("$")) {
                return prefix + name.substring(0, name.length() - ".class".length());
            }
        }
        for (File file : files) {
            if (file.isDirectory()) {
                String found = firstClassUnder(file, prefix + file.getName() + ".");
                if (found != null) return found;
            }
        }
        return null;
    }

    /** {@code object Foo} and {@code @main def bar} are what Scala turns into entry points. */
    private static final Pattern SCALA_OBJECT = Pattern.compile("\\bobject\\s+([A-Za-z_][\\w$]*)");
    private static final Pattern SCALA_MAIN_DEF =
            Pattern.compile("@main\\s+def\\s+([A-Za-z_][\\w$]*)");
    private static final Pattern SCALA_PACKAGE =
            Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)");

    /**
     * The class to start, worked out from the source and checked against what
     * the compiler actually produced.
     */
    private static String mainClassOf(File source, File outputDir) {
        String text = readText(source);
        List<String> candidates = new ArrayList<>();
        if (text != null) {
            Matcher main = SCALA_MAIN_DEF.matcher(text);
            while (main.find()) candidates.add(main.group(1));
            Matcher object = SCALA_OBJECT.matcher(text);
            while (object.find()) candidates.add(object.group(1));
        }
        // The class file sits under the source's package, and the name to
        // start has to carry that package too — a bare object name is not
        // something the JVM can find.
        String pkg = packageOf(text);
        File packageDir = outputDir;
        for (String part : pkg.isEmpty() ? new String[0] : pkg.split("\\.")) {
            packageDir = new File(packageDir, part);
        }
        for (String candidate : candidates) {
            if (new File(packageDir, candidate + ".class").isFile()) {
                return pkg.isEmpty() ? candidate : pkg + "." + candidate;
            }
            if (new File(outputDir, candidate + ".class").isFile()) return candidate;
        }
        // Nothing matched the source, so fall back to whatever single class
        // came out — a nested or synthetic one would carry a $.
        return firstClassUnder(outputDir, "");
    }

    /** Receives the outcome of one JVM start when a second one follows. */
    private interface Then {
        void accept(JavaSeRunner.Result result);
    }

    private static void launch(Context context, File runtime, File workingDirectory,
                               List<File> classpathEntries, List<String> mainAndArgs,
                               ProjectCompiler.Callback callback, Then then) {
        launch(context, runtime, workingDirectory, classpathEntries, mainAndArgs,
                defaultHeapMb(), callback, then);
    }

    /** Enough for a script; the Scala compiler asks for its own figure below. */
    private static int defaultHeapMb() {
        return Build.SUPPORTED_64_BIT_ABIS.length > 0 ? 512 : 256;
    }

    private static void launch(Context context, File runtime, File workingDirectory,
                               List<File> classpathEntries, List<String> mainAndArgs,
                               int heapMb, ProjectCompiler.Callback callback, Then then) {
        RunConfig runConfig = RunConfig.from(context);
        File temp = new File(context.getCacheDir(), "java-se-tmp");
        temp.mkdirs();

        JavaSeRunner.Request request = new JavaSeRunner.Request();
        request.runtimeHome = runtime;
        request.workingDirectory = workingDirectory != null && workingDirectory.isDirectory()
                ? workingDirectory : context.getCacheDir();
        request.environment.putAll(runConfig.env);
        request.onOutput = chunk -> ProjectCompiler.postOutput(callback, chunk);

        request.arguments.add("java");
        request.arguments.add("-Xms32m");
        // More than the Java paths ask for: these compilers are written for
        // desktop machines and will not start in the smaller heap.
        request.arguments.add("-Xmx" + heapMb + "m");
        request.arguments.add("-XX:+UseSerialGC");
        // Capped for the same reason as the heap: metaspace is outside it, and
        // a compiler loads thousands of classes.
        request.arguments.add("-XX:MaxMetaspaceSize=192m");
        // The Scala compiler recurses deeply over its own trees.
        request.arguments.add("-Xss4m");
        request.arguments.add("-Djava.awt.headless=true");
        request.arguments.add("-Dfile.encoding=UTF-8");
        request.arguments.add("-Dsun.stdout.encoding=UTF-8");
        request.arguments.add("-Dsun.stderr.encoding=UTF-8");
        request.arguments.add("-Djava.io.tmpdir=" + temp.getAbsolutePath());
        request.arguments.add("-Duser.home=" + request.workingDirectory.getAbsolutePath());
        request.arguments.add("-cp");
        request.arguments.add(classpath(classpathEntries));
        request.arguments.addAll(mainAndArgs);
        for (String argument : runConfig.args) request.arguments.add(argument);

        JavaSeRunner.launch(context, request, result -> {
            if (then != null) {
                then.accept(result);
                return;
            }
            String output = result.output == null ? "" : result.output;
            if (result.error != null && !result.error.isEmpty()) {
                output = output.isEmpty() ? result.error : output + "\n" + result.error;
            }
            ProjectCompiler.postResult(callback, output);
        });
    }

    private static String classpath(List<File> entries) {
        StringBuilder sb = new StringBuilder();
        for (File entry : entries) {
            if (sb.length() > 0) sb.append(File.pathSeparatorChar);
            sb.append(entry.getAbsolutePath());
        }
        return sb.toString();
    }

    private static String readText(File file) {
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursive(child);
        file.delete();
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.trim().isEmpty()
                ? t.getClass().getSimpleName() : message;
    }
}
