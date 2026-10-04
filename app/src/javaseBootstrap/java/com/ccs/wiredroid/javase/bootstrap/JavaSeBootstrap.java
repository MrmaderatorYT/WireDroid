package com.ccs.wiredroid.javase.bootstrap;

import org.eclipse.jdt.internal.compiler.batch.Main;

import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** ECJ compiler, user-main bridge, and test runner executed by the embedded Java SE VM. */
public final class JavaSeBootstrap {

    public static final String MARKER_PREFIX = "\u001eWIREDROID:";
    public static final String COMPILE_OK = MARKER_PREFIX + "COMPILE_OK";
    public static final String COMPILE_FAILED = MARKER_PREFIX + "COMPILE_FAILED";
    public static final String RUN_OK = MARKER_PREFIX + "RUN_OK";
    public static final String RUN_FAILED = MARKER_PREFIX + "RUN_FAILED";

    private JavaSeBootstrap() {}

    public static void main(String[] args) {
        installAutoFlushingOutput();
        try {
            Invocation invocation = Invocation.parse(args);
            if (!compile(invocation)) {
                marker(COMPILE_FAILED);
                return;
            }
            marker(COMPILE_OK);
            if (invocation.mode == Invocation.Mode.COMPILE_ONLY) return;

            if (invocation.mode == Invocation.Mode.TEST) {
                boolean allPassed = runTests(invocation);
                marker(allPassed ? RUN_OK : RUN_FAILED);
            } else {
                runMain(invocation);
                marker(RUN_OK);
            }
        } catch (Throwable throwable) {
            unwrap(throwable).printStackTrace(System.err);
            marker(RUN_FAILED);
        }
    }

    /**
     * Makes the program's output appear as it is written.
     *
     * <p>The VM's stdout is a file here, not a terminal, so the JDK gives
     * {@code System.out} an eight-kilobyte buffer and no autoflush — a prompt
     * printed before a read would sit in that buffer until the program ended,
     * which is far too late for anything the user is meant to answer.</p>
     */
    private static void installAutoFlushingOutput() {
        try {
            System.setOut(new java.io.PrintStream(
                    new java.io.FileOutputStream(java.io.FileDescriptor.out), true));
            System.setErr(new java.io.PrintStream(
                    new java.io.FileOutputStream(java.io.FileDescriptor.err), true));
        } catch (Throwable ignored) {
            // Buffered output is worse than unbuffered, but not a reason to
            // refuse to run the program.
        }
    }

    private static String getBootstrapJarPath() {
        try {
            return new File(JavaSeBootstrap.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getAbsolutePath();
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean compile(Invocation invocation) {
        List<String> compilerArgs = new ArrayList<>();
        compilerArgs.add("-source");
        compilerArgs.add(invocation.javaTarget);
        compilerArgs.add("-target");
        compilerArgs.add(invocation.javaTarget);
        compilerArgs.add("-proc:none");
        compilerArgs.add("-encoding");
        compilerArgs.add("UTF-8");

        String bootstrapJar = getBootstrapJarPath();
        String cp = invocation.compileClasspath;
        if (!bootstrapJar.isEmpty() && !cp.contains(bootstrapJar)) {
            cp = cp.isEmpty() ? bootstrapJar : bootstrapJar + File.pathSeparator + cp;
        }

        if (!cp.isEmpty()) {
            compilerArgs.add("-classpath");
            compilerArgs.add(cp);
        }
        compilerArgs.add("-d");
        compilerArgs.add(invocation.outputDirectory);
        compilerArgs.addAll(invocation.sources);

        PrintWriter out = new PrintWriter(System.out, true);
        PrintWriter err = new PrintWriter(System.err, true);
        return new Main(out, err, false, null, null)
                .compile(compilerArgs.toArray(new String[0]));
    }

    private static void runMain(Invocation invocation) throws Exception {
        if (invocation.mainClass.isEmpty()) {
            throw new IllegalArgumentException("No public static void main(String[] args) found");
        }

        List<URL> urls = new ArrayList<>();
        if (!invocation.outputDirectory.isEmpty()) {
            File outDir = new File(invocation.outputDirectory);
            urls.add(outDir.toURI().toURL());
        }
        for (String entry : invocation.runtimeClasspath.split(File.pathSeparator)) {
            if (!entry.isEmpty()) urls.add(new File(entry).toURI().toURL());
        }

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(new URL[0]), JavaSeBootstrap.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            System.setProperty("java.class.path", invocation.runtimeClasspath);
            Class<?> type = Class.forName(invocation.mainClass, true, loader);
            Method main = type.getMethod("main", String[].class);
            int modifiers = main.getModifiers();
            if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)
                    || main.getReturnType() != void.class) {
                throw new NoSuchMethodException(invocation.mainClass
                        + ".main must be public static void");
            }
            main.invoke(null, (Object) invocation.programArgs.toArray(new String[0]));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static boolean runTests(Invocation invocation) throws Exception {
        List<URL> urls = new ArrayList<>();
        if (!invocation.outputDirectory.isEmpty()) {
            File outDir = new File(invocation.outputDirectory);
            urls.add(outDir.toURI().toURL());
        }
        for (String entry : invocation.runtimeClasspath.split(File.pathSeparator)) {
            if (!entry.isEmpty()) urls.add(new File(entry).toURI().toURL());
        }

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(new URL[0]), JavaSeBootstrap.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            System.setProperty("java.class.path", invocation.runtimeClasspath);

            List<String> searchDirs = new ArrayList<>();
            if (!invocation.outputDirectory.isEmpty()) {
                searchDirs.add(invocation.outputDirectory);
            }
            for (String entry : invocation.runtimeClasspath.split(File.pathSeparator)) {
                if (!entry.isEmpty() && new File(entry).isDirectory()) {
                    searchDirs.add(entry);
                }
            }

            return JavaSeTestRunner.runTests(loader, invocation.mainClass, searchDirs);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException
                && ((InvocationTargetException) throwable).getCause() != null) {
            return ((InvocationTargetException) throwable).getCause();
        }
        return throwable;
    }

    private static void marker(String value) {
        System.out.println(value);
        System.out.flush();
        System.err.flush();
    }

    static final class Invocation {
        enum Mode {
            RUN_MAIN,
            COMPILE_ONLY,
            TEST
        }

        final Mode mode;
        final String javaTarget;
        final String outputDirectory;
        final String mainClass;
        final String compileClasspath;
        final String runtimeClasspath;
        final List<String> sources;
        final List<String> programArgs;

        Invocation(Mode mode, String javaTarget, String outputDirectory, String mainClass,
                   String compileClasspath, String runtimeClasspath, List<String> sources,
                   List<String> programArgs) {
            this.mode = mode;
            this.javaTarget = javaTarget;
            this.outputDirectory = outputDirectory;
            this.mainClass = mainClass;
            this.compileClasspath = compileClasspath;
            this.runtimeClasspath = runtimeClasspath;
            this.sources = sources;
            this.programArgs = programArgs;
        }

        static Invocation parse(String[] args) {
            if (args.length < 7) {
                throw new IllegalArgumentException("Invalid WireDroid Java SE invocation");
            }
            Mode mode;
            if ("compile-run".equals(args[0])) mode = Mode.RUN_MAIN;
            else if ("compile".equals(args[0])) mode = Mode.COMPILE_ONLY;
            else if ("compile-test".equals(args[0]) || "test".equals(args[0])) mode = Mode.TEST;
            else throw new IllegalArgumentException("Unknown operation: " + args[0]);

            int sourceCount = Integer.parseInt(args[6]);
            if (sourceCount < 1 || args.length < 7 + sourceCount) {
                throw new IllegalArgumentException("Invalid source count: " + sourceCount);
            }
            List<String> sources = new ArrayList<>(sourceCount);
            for (int i = 0; i < sourceCount; i++) sources.add(args[7 + i]);
            List<String> programArgs = new ArrayList<>();
            for (int i = 7 + sourceCount; i < args.length; i++) programArgs.add(args[i]);
            return new Invocation(mode, args[1], args[2], args[3], args[4], args[5],
                    sources, programArgs);
        }
    }
}
