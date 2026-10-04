package com.ccs.wiredroid.javase.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.File;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Embedded IntelliJ IDEA-style JUnit 4 / JUnit 5 / Standalone Test Runner.
 */
public final class JavaSeTestRunner {

    private static final String ICON_PASS = "✓";
    private static final String ICON_FAIL = "✗";
    private static final String ICON_SKIP = "⏸";

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

    public static boolean runTests(ClassLoader classLoader, String targetClass,
                                   List<String> searchDirectories) {
        String startTime = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        System.out.println("Testing started at " + startTime + " ...\n");

        List<Class<?>> testClasses = new ArrayList<>();
        if (targetClass != null && !targetClass.trim().isEmpty()) {
            try {
                Class<?> cls = Class.forName(targetClass.trim(), true, classLoader);
                testClasses.add(cls);
            } catch (Throwable t) {
                System.err.println("Could not load target test class: " + targetClass);
                t.printStackTrace(System.err);
                return false;
            }
        } else {
            testClasses = discoverTestClasses(classLoader, searchDirectories);
        }

        if (testClasses.isEmpty()) {
            System.out.println("No test classes found.");
            return true;
        }

        long totalStartTime = System.nanoTime();
        List<TestClassResult> classResults = new ArrayList<>();
        int totalTests = 0;
        int passedTests = 0;
        int failedTests = 0;
        int skippedTests = 0;

        for (Class<?> testClass : testClasses) {
            TestClassResult classResult = runTestClass(testClass);
            classResults.add(classResult);

            for (TestMethodResult m : classResult.methodResults) {
                totalTests++;
                if (m.skipped) skippedTests++;
                else if (m.passed) passedTests++;
                else failedTests++;
            }
            if (classResult.classFailure != null && classResult.methodResults.isEmpty()) {
                totalTests++;
                failedTests++;
            }
        }

        long totalDurationMs = Math.max(1, (System.nanoTime() - totalStartTime) / 1_000_000L);

        // Print Tree Report in IntelliJ IDEA format
        for (TestClassResult cr : classResults) {
            String classIcon = cr.isAllPassed() ? ICON_PASS : ICON_FAIL;
            String pkgInfo = cr.className.contains(".") ? cr.className : "";
            System.out.println(classIcon + " " + cr.simpleName
                    + (!pkgInfo.isEmpty() ? " (" + pkgInfo + ", " + cr.durationMs + " ms)" : " (" + cr.durationMs + " ms)"));

            if (cr.classFailure != null) {
                System.out.println("  " + ICON_FAIL + " Class initialization failed: "
                        + unwrap(cr.classFailure).getMessage());
                printThrowable(cr.classFailure, "    ");
            }

            for (TestMethodResult mr : cr.methodResults) {
                String name = (mr.displayName != null && !mr.displayName.isEmpty())
                        ? mr.displayName : mr.methodName;
                if (mr.skipped) {
                    System.out.println("  " + ICON_SKIP + " " + name + " (ignored)");
                } else if (mr.passed) {
                    System.out.println("  " + ICON_PASS + " " + name + " (" + mr.durationMs + " ms)");
                } else {
                    System.out.println("  " + ICON_FAIL + " " + name + " (" + mr.durationMs + " ms)");
                    if (mr.failure != null) {
                        printThrowable(mr.failure, "    ");
                    }
                }
                if (mr.capturedOutput != null && !mr.capturedOutput.trim().isEmpty()) {
                    System.out.println("    --- output ---");
                    for (String line : mr.capturedOutput.split("\n")) {
                        System.out.println("    " + line);
                    }
                }
            }
            System.out.println();
        }

        // Summary footer
        System.out.println("--------------------------------------------------");
        if (failedTests == 0) {
            System.out.println(ICON_PASS + " " + passedTests + " tests passed ("
                    + totalTests + " tests total, " + totalDurationMs + " ms)\n");
            System.out.println("Process finished with exit code 0");
            return true;
        } else {
            System.out.println(ICON_FAIL + " Tests failed: " + passedTests + " passed, "
                    + failedTests + " failed"
                    + (skippedTests > 0 ? ", " + skippedTests + " skipped" : "")
                    + " (" + totalTests + " tests total, " + totalDurationMs + " ms)\n");
            System.out.println("Process finished with exit code 1");
            return false;
        }
    }

    private static TestClassResult runTestClass(Class<?> testClass) {
        String simpleName = testClass.getSimpleName();
        String className = testClass.getName();
        long classStart = System.nanoTime();

        // Check class-level ignore
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

        // Run @BeforeClass / @BeforeAll
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
                // Instantiate class per test
                Constructor<?> ctor = testClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                instance = ctor.newInstance();

                // Run @Before
                if (beforeMethod != null) {
                    beforeMethod.setAccessible(true);
                    beforeMethod.invoke(instance);
                }

                // Redirect stdout/stderr during test
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

        // Run @AfterClass / @AfterAll
        if (afterClass != null) {
            try {
                afterClass.setAccessible(true);
                afterClass.invoke(null);
            } catch (Throwable t) {
                System.err.println("Error in @AfterClass for " + className + ": " + t);
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
                if (hasAnnotation(m, annotationNames)) {
                    return m;
                }
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

    private static List<Class<?>> discoverTestClasses(ClassLoader loader, List<String> directories) {
        List<Class<?>> classes = new ArrayList<>();
        Set<String> classNames = new HashSet<>();

        for (String dirPath : directories) {
            File dir = new File(dirPath);
            if (!dir.exists() || !dir.isDirectory()) continue;
            collectClassNames(dir, dir, classNames);
        }

        for (String fqName : classNames) {
            try {
                Class<?> cls = Class.forName(fqName, false, loader);
                if (Modifier.isAbstract(cls.getModifiers())) continue;
                if (!findTestMethods(cls).isEmpty() || cls.getSimpleName().endsWith("Test")
                        || cls.getSimpleName().endsWith("Tests") || cls.getSimpleName().endsWith("TestCase")) {
                    classes.add(cls);
                }
            } catch (Throwable ignored) {}
        }
        classes.sort(Comparator.comparing(Class::getName));
        return classes;
    }

    private static void collectClassNames(File root, File current, Set<String> out) {
        File[] files = current.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectClassNames(root, f, out);
            } else if (f.getName().endsWith(".class") && !f.getName().contains("$")
                    && !f.getName().equals("module-info.class") && !f.getName().equals("package-info.class")) {
                String rel = root.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '.');
                if (rel.endsWith(".class")) rel = rel.substring(0, rel.length() - 6);
                out.add(rel);
            }
        }
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof InvocationTargetException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    private static void printThrowable(Throwable t, String indent) {
        Throwable unwrapped = unwrap(t);
        String msg = unwrapped.getMessage();
        System.out.println(indent + unwrapped.getClass().getName() + (msg != null ? ": " + msg : ""));
        StackTraceElement[] traces = unwrapped.getStackTrace();
        if (traces != null) {
            for (StackTraceElement elem : traces) {
                // Only print relevant frames, skip java.lang.reflect
                String className = elem.getClassName();
                if (className.startsWith("java.lang.reflect.") || className.startsWith("jdk.internal.")
                        || className.startsWith("com.ccs.wiredroid.javase.bootstrap.")) {
                    continue;
                }
                System.out.println(indent + "  at " + elem);
            }
        }
    }
}
