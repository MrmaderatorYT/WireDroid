package com.ccs.wiredroid.maven;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import dalvik.system.DexClassLoader;

/**
 * On-device JUnit 4 / JUnit 5 / Standalone test runner (analog of IntelliJ IDEA / Maven Surefire).
 */
public final class MavenTestRunner {

    private static final String TAG = "MavenTestRunner";
    private static final String ICON_PASS = "✓";
    private static final String ICON_FAIL = "✗";
    private static final String ICON_SKIP = "⏸";

    /** Callback for test execution progress/results */
    public interface Callback {
        void onProgress(String line);
        void onResult(String output);
        default void onTestResults(List<TestClassResult> results, int totalTests, int passedTests,
                                   int failedTests, int skippedTests, long durationMs) {}
    }

    private MavenTestRunner() {}

    public static final class TestMethodResult {
        public final String methodName;
        public final String displayName;
        public final boolean passed;
        public final boolean skipped;
        public final long durationMs;
        public final Throwable failure;
        public final String capturedOutput;

        public TestMethodResult(String methodName, String displayName, boolean passed,
                                boolean skipped, long durationMs, Throwable failure,
                                String capturedOutput) {
            this.methodName = methodName;
            this.displayName = displayName;
            this.passed = passed;
            this.skipped = skipped;
            this.durationMs = durationMs;
            this.failure = failure;
            this.capturedOutput = capturedOutput;
        }
    }

    public static final class TestClassResult {
        public final String className;
        public final String simpleName;
        public long durationMs;
        public final List<TestMethodResult> methodResults = new ArrayList<>();
        public final Throwable classFailure;

        public TestClassResult(String className, String simpleName, long durationMs, Throwable classFailure) {
            this.className = className;
            this.simpleName = simpleName;
            this.durationMs = durationMs;
            this.classFailure = classFailure;
        }

        public boolean isAllPassed() {
            if (classFailure != null) return false;
            for (TestMethodResult r : methodResults) {
                if (!r.passed && !r.skipped) return false;
            }
            return true;
        }
    }

    public static void runTests(Context context, File dexDir, File testClassesDir,
                                File mainClassesDir, List<File> depJars, File androidJar,
                                Callback callback) {
        try {
            String startTime = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());

            // 1. Secure dex copy (Android 14+ requirement)
            File dexFile = new File(dexDir, "classes.dex");
            if (!dexFile.exists()) {
                postResult(callback, "Error: classes.dex not found in " + dexDir.getAbsolutePath());
                return;
            }

            File secureDexDir = new File(context.getDir("dex", Context.MODE_PRIVATE),
                    "test_" + System.currentTimeMillis());
            secureDexDir.mkdirs();
            File secureDex = new File(secureDexDir, "classes.dex");
            copyFile(dexFile, secureDex);
            secureDex.setReadOnly();

            // 2. Build classpath for DexClassLoader
            StringBuilder libPath = new StringBuilder();
            for (File jar : depJars) {
                if (libPath.length() > 0) libPath.append(File.pathSeparator);
                libPath.append(jar.getAbsolutePath());
            }
            if (androidJar != null) {
                if (libPath.length() > 0) libPath.append(File.pathSeparator);
                libPath.append(androidJar.getAbsolutePath());
            }

            File optDir = new File(secureDexDir, "opt");
            optDir.mkdirs();

            // 3. Create DexClassLoader
            DexClassLoader cl = new DexClassLoader(
                    secureDex.getAbsolutePath(),
                    optDir.getAbsolutePath(),
                    null,
                    context.getClassLoader()
            );

            // 4. Discover test classes from .class files
            List<String> classNames = discoverTestClassNames(testClassesDir);
            if (classNames.isEmpty()) {
                postResult(callback, "No test classes found in " + testClassesDir.getName());
                return;
            }

            long totalStartTime = System.nanoTime();
            List<TestClassResult> classResults = new ArrayList<>();
            int totalTests = 0;
            int passedTests = 0;
            int failedTests = 0;
            int skippedTests = 0;

            boolean stoppedByUser = false;
            for (String className : classNames) {
                // Between classes, not inside one: a single test method is an
                // opaque reflective call. This is what makes Stop actually end a
                // long test sweep instead of only relabelling the toolbar.
                if (com.ccs.wiredroid.tools.compilers.RunCancellation.isStopRequested()) {
                    stoppedByUser = true;
                    break;
                }
                try {
                    Class<?> testClass = cl.loadClass(className);
                    TestClassResult cr = runDexTestClass(testClass);
                    classResults.add(cr);

                    for (TestMethodResult m : cr.methodResults) {
                        totalTests++;
                        if (m.skipped) skippedTests++;
                        else if (m.passed) passedTests++;
                        else failedTests++;
                    }
                    if (cr.classFailure != null && cr.methodResults.isEmpty()) {
                        totalTests++;
                        failedTests++;
                    }
                } catch (ClassNotFoundException e) {
                    continue;
                } catch (Throwable t) {
                    Log.w(TAG, "Failed loading test class " + className, t);
                }
            }

            long totalDurationMs = Math.max(1, (System.nanoTime() - totalStartTime) / 1_000_000L);

            if (stoppedByUser) {
                // A partial pass is not a result. Reporting it as one would show
                // a green "all passed" for a sweep that never reached most of
                // its classes.
                postResult(callback, context.getString(
                        com.ccs.wiredroid.R.string.run_stopped_by_user));
                return;
            }

            // Build IntelliJ IDEA output report
            StringBuilder sb = new StringBuilder();
            sb.append("Testing started at ").append(startTime).append(" ...\n\n");

            for (TestClassResult cr : classResults) {
                String classIcon = cr.isAllPassed() ? ICON_PASS : ICON_FAIL;
                String pkgInfo = cr.className.contains(".") ? cr.className : "";
                sb.append(classIcon).append(" ").append(cr.simpleName);
                if (!pkgInfo.isEmpty()) {
                    sb.append(" (").append(pkgInfo).append(", ").append(cr.durationMs).append(" ms)\n");
                } else {
                    sb.append(" (").append(cr.durationMs).append(" ms)\n");
                }

                if (cr.classFailure != null) {
                    sb.append("  ").append(ICON_FAIL).append(" Class initialization failed: ")
                            .append(unwrap(cr.classFailure).getMessage()).append("\n");
                    appendStackTrace(sb, cr.classFailure, "    ");
                }

                for (TestMethodResult mr : cr.methodResults) {
                    String name = (mr.displayName != null && !mr.displayName.isEmpty())
                            ? mr.displayName : mr.methodName;
                    if (mr.skipped) {
                        sb.append("  ").append(ICON_SKIP).append(" ").append(name).append(" (ignored)\n");
                    } else if (mr.passed) {
                        sb.append("  ").append(ICON_PASS).append(" ").append(name)
                                .append(" (").append(mr.durationMs).append(" ms)\n");
                    } else {
                        sb.append("  ").append(ICON_FAIL).append(" ").append(name)
                                .append(" (").append(mr.durationMs).append(" ms)\n");
                        if (mr.failure != null) {
                            appendStackTrace(sb, mr.failure, "    ");
                        }
                    }
                    if (mr.capturedOutput != null && !mr.capturedOutput.trim().isEmpty()) {
                        sb.append("    --- output ---\n");
                        for (String line : mr.capturedOutput.split("\n")) {
                            sb.append("    ").append(line).append("\n");
                        }
                    }
                }
                sb.append("\n");
            }

            sb.append("--------------------------------------------------\n");
            if (failedTests == 0) {
                sb.append(ICON_PASS).append(" ").append(passedTests).append(" tests passed (")
                        .append(totalTests).append(" tests total, ").append(totalDurationMs).append(" ms)\n\n");
                sb.append("Process finished with exit code 0");
            } else {
                sb.append(ICON_FAIL).append(" Tests failed: ").append(passedTests).append(" passed, ")
                        .append(failedTests).append(" failed");
                if (skippedTests > 0) sb.append(", ").append(skippedTests).append(" skipped");
                sb.append(" (").append(totalTests).append(" tests total, ").append(totalDurationMs).append(" ms)\n\n");
                sb.append("Process finished with exit code 1");
            }

            try {
                callback.onTestResults(classResults, totalTests, passedTests, failedTests, skippedTests, totalDurationMs);
            } catch (Throwable ignored) {}
            postResult(callback, sb.toString());
        } catch (Throwable e) {
            postResult(callback, "Test runner error: " + e.getMessage() + "\n"
                    + Log.getStackTraceString(e));
        }
    }

    private static TestClassResult runDexTestClass(Class<?> testClass) {
        String simpleName = testClass.getSimpleName();
        String className = testClass.getName();
        long classStart = System.nanoTime();

        if (hasAnnotation(testClass, "Ignore", "Disabled")) {
            long dur = Math.max(1, (System.nanoTime() - classStart) / 1_000_000L);
            TestClassResult res = new TestClassResult(className, simpleName, dur, null);
            res.methodResults.add(new TestMethodResult(simpleName, simpleName, false, true, 0, null, null));
            return res;
        }

        Method beforeClass = findMethodWithAnnotation(testClass, true, "BeforeClass", "BeforeAll");
        Method afterClass = findMethodWithAnnotation(testClass, true, "AfterClass", "AfterAll");
        Method beforeMethod = findMethodWithAnnotation(testClass, false, "Before", "BeforeEach", "setUp");
        Method afterMethod = findMethodWithAnnotation(testClass, false, "After", "AfterEach", "tearDown");

        List<Method> testMethods = findTestMethods(testClass);
        if (testMethods.isEmpty()) {
            long dur = Math.max(1, (System.nanoTime() - classStart) / 1_000_000L);
            return new TestClassResult(className, simpleName, dur, null);
        }

        if (beforeClass != null) {
            try {
                beforeClass.setAccessible(true);
                beforeClass.invoke(null);
            } catch (Throwable t) {
                long dur = Math.max(1, (System.nanoTime() - classStart) / 1_000_000L);
                return new TestClassResult(className, simpleName, dur, unwrap(t));
            }
        }

        TestClassResult classResult = new TestClassResult(className, simpleName, 0, null);

        PrintStream origOut = System.out;
        PrintStream origErr = System.err;

        for (Method m : testMethods) {
            if (com.ccs.wiredroid.tools.compilers.RunCancellation.isStopRequested()) break;
            String methodName = m.getName();
            String displayName = getDisplayName(m);

            if (hasAnnotation(m, "Ignore", "Disabled")) {
                classResult.methodResults.add(new TestMethodResult(methodName, displayName, false, true, 0, null, null));
                continue;
            }

            ByteArrayOutputStream outputCapture = new ByteArrayOutputStream();
            PrintStream captureStream = new PrintStream(outputCapture, true);

            Object instance = null;
            long methodStart = System.nanoTime();
            Throwable failure = null;
            boolean passed = false;

            try {
                Constructor<?> ctor = testClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                instance = ctor.newInstance();

                if (beforeMethod != null) {
                    beforeMethod.setAccessible(true);
                    beforeMethod.invoke(instance);
                }

                System.setOut(captureStream);
                System.setErr(captureStream);

                m.setAccessible(true);
                m.invoke(instance);
                passed = true;
            } catch (Throwable t) {
                failure = unwrap(t);
                Class<?> expected = getExpectedException(m);
                if (expected != null && expected.isInstance(failure)) {
                    passed = true;
                    failure = null;
                }
            } finally {
                System.setOut(origOut);
                System.setErr(origErr);
                captureStream.flush();

                if (instance != null && afterMethod != null) {
                    try {
                        afterMethod.setAccessible(true);
                        afterMethod.invoke(instance);
                    } catch (Throwable t) {
                        if (failure == null) failure = unwrap(t);
                        passed = false;
                    }
                }
            }

            long methodDuration = Math.max(1, (System.nanoTime() - methodStart) / 1_000_000L);
            String captured = outputCapture.toString();
            classResult.methodResults.add(new TestMethodResult(methodName, displayName, passed, false, methodDuration, failure, captured));
        }

        if (afterClass != null) {
            try {
                afterClass.setAccessible(true);
                afterClass.invoke(null);
            } catch (Throwable t) {
                Log.w(TAG, "Error in @AfterClass for " + className, t);
            }
        }

        classResult.durationMs = Math.max(1, (System.nanoTime() - classStart) / 1_000_000L);
        return classResult;
    }

    private static List<Method> findTestMethods(Class<?> cls) {
        List<Method> list = new ArrayList<>();
        boolean isTestCase = false;
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            if ("junit.framework.TestCase".equals(current.getName())) {
                isTestCase = true;
                break;
            }
            current = current.getSuperclass();
        }

        current = cls;
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (current != null && current != Object.class) {
            for (Method m : current.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers()) && !isPackagePrivate(m.getModifiers())) continue;
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 0) continue;
                if (!seen.add(m.getName())) continue;

                if (hasAnnotation(m, "Test", "ParameterizedTest", "RepeatedTest")) {
                    list.add(m);
                } else if (isTestCase && m.getName().startsWith("test")) {
                    list.add(m);
                }
            }
            current = current.getSuperclass();
        }
        list.sort(Comparator.comparing(Method::getName));
        return list;
    }

    private static boolean isPackagePrivate(int modifiers) {
        return !Modifier.isPublic(modifiers) && !Modifier.isPrivate(modifiers) && !Modifier.isProtected(modifiers);
    }

    private static Method findMethodWithAnnotation(Class<?> cls, boolean mustBeStatic, String... annotationNames) {
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            for (Method m : current.getDeclaredMethods()) {
                if (mustBeStatic && !Modifier.isStatic(m.getModifiers())) continue;
                if (!mustBeStatic && Modifier.isStatic(m.getModifiers())) continue;
                if (hasAnnotation(m, annotationNames)) return m;
                if (m.getParameterCount() == 0) {
                    for (String name : annotationNames) {
                        if (m.getName().equals(name)) return m;
                    }
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static boolean hasAnnotation(Method m, String... simpleNames) {
        for (Annotation a : m.getAnnotations()) {
            String name = a.annotationType().getSimpleName();
            String fq = a.annotationType().getName();
            for (String expected : simpleNames) {
                if (name.equalsIgnoreCase(expected) || fq.endsWith("." + expected)) return true;
            }
        }
        return false;
    }

    private static boolean hasAnnotation(Class<?> cls, String... simpleNames) {
        for (Annotation a : cls.getAnnotations()) {
            String name = a.annotationType().getSimpleName();
            String fq = a.annotationType().getName();
            for (String expected : simpleNames) {
                if (name.equalsIgnoreCase(expected) || fq.endsWith("." + expected)) return true;
            }
        }
        return false;
    }

    private static String getDisplayName(Method m) {
        for (Annotation a : m.getAnnotations()) {
            if ("DisplayName".equals(a.annotationType().getSimpleName())) {
                try {
                    Method val = a.annotationType().getMethod("value");
                    return (String) val.invoke(a);
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static Class<?> getExpectedException(Method m) {
        for (Annotation a : m.getAnnotations()) {
            if ("Test".equals(a.annotationType().getSimpleName())) {
                try {
                    Method expected = a.annotationType().getMethod("expected");
                    Class<?> exp = (Class<?>) expected.invoke(a);
                    if (exp != null && exp != void.class && !exp.getSimpleName().equals("None")) {
                        return exp;
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static List<String> discoverTestClassNames(File classesDir) {
        List<String> names = new ArrayList<>();
        if (!classesDir.exists()) return names;
        collectClassNames(classesDir, classesDir, names);
        return names;
    }

    private static void collectClassNames(File root, File dir, List<String> names) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectClassNames(root, f, names);
            } else if (f.getName().endsWith(".class")) {
                String rel = root.toPath().relativize(f.toPath()).toString()
                        .replace(File.separatorChar, '.');
                if (rel.endsWith(".class")) {
                    rel = rel.substring(0, rel.length() - 6);
                }
                if (!rel.contains("$") && !rel.equals("module-info") && !rel.equals("package-info")) {
                    names.add(rel);
                }
            }
        }
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof InvocationTargetException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    private static void appendStackTrace(StringBuilder sb, Throwable t, String indent) {
        Throwable unwrapped = unwrap(t);
        String msg = unwrapped.getMessage();
        sb.append(indent).append(unwrapped.getClass().getName()).append(msg != null ? ": " + msg : "").append("\n");
        StackTraceElement[] traces = unwrapped.getStackTrace();
        if (traces != null) {
            for (StackTraceElement elem : traces) {
                String className = elem.getClassName();
                if (className.startsWith("java.lang.reflect.") || className.startsWith("dalvik.system.")
                        || className.startsWith("com.ccs.wiredroid.maven.MavenTestRunner")) {
                    continue;
                }
                sb.append(indent).append("  at ").append(elem).append("\n");
            }
        }
    }

    private static void copyFile(File src, File dest) throws Exception {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
        }
    }

    private static void postProgress(Callback cb, String line) {
        if (cb != null) cb.onProgress(line);
    }

    private static void postResult(Callback cb, String output) {
        if (cb != null) cb.onResult(output);
    }
}
