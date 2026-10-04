package com.ccs.wiredroid.testrunner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ccs.wiredroid.maven.MavenTestRunner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses test execution results into a hierarchy of {@link TestResultItem}s,
 * resolving source filenames and line numbers from stack traces for 1-click navigation.
 */
public final class TestReportParser {

    private static final Pattern STACK_TRACE_PATTERN = Pattern.compile(
            "at\\s+([\\w$.]+)\\.([\\w$]+)\\(([\\w$]+\\.(?:java|kt)):(\\d+)\\)"
    );

    public static final class ParsedReport {
        public final List<TestResultItem> items;
        public final int totalTests;
        public final int passedTests;
        public final int failedTests;
        public final int skippedTests;
        public final long durationMs;

        public ParsedReport(List<TestResultItem> items, int totalTests, int passedTests,
                            int failedTests, int skippedTests, long durationMs) {
            this.items = items != null ? items : Collections.emptyList();
            this.totalTests = totalTests;
            this.passedTests = passedTests;
            this.failedTests = failedTests;
            this.skippedTests = skippedTests;
            this.durationMs = durationMs;
        }

        public boolean isAllPassed() {
            return failedTests == 0 && totalTests > 0;
        }
    }

    private TestReportParser() {}

    /**
     * Converts structured {@link MavenTestRunner.TestClassResult}s into {@link ParsedReport}.
     */
    @NonNull
    public static ParsedReport fromMavenResults(@Nullable List<MavenTestRunner.TestClassResult> classResults) {
        if (classResults == null || classResults.isEmpty()) {
            return new ParsedReport(Collections.emptyList(), 0, 0, 0, 0, 0);
        }

        List<TestResultItem> suiteItems = new ArrayList<>();
        int total = 0;
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        long totalDuration = 0;

        for (MavenTestRunner.TestClassResult cr : classResults) {
            totalDuration += cr.durationMs;
            boolean classPassed = cr.isAllPassed();
            TestResultItem.Status classStatus = classPassed
                    ? TestResultItem.Status.PASSED
                    : TestResultItem.Status.FAILED;

            String classErrorMsg = null;
            String classStackTrace = null;
            String classSourceFile = null;
            int classErrorLine = -1;

            if (cr.classFailure != null) {
                classErrorMsg = cr.classFailure.getMessage();
                classStackTrace = getStackTrace(cr.classFailure);
                SourceLocation loc = extractLocation(classStackTrace, cr.simpleName);
                if (loc != null) {
                    classSourceFile = loc.fileName;
                    classErrorLine = loc.lineNumber;
                }
            }

            TestResultItem suiteItem = new TestResultItem(
                    cr.className,
                    cr.simpleName,
                    cr.className,
                    null,
                    cr.durationMs,
                    classStatus,
                    classErrorMsg,
                    classStackTrace,
                    classSourceFile,
                    classErrorLine,
                    true
            );

            for (MavenTestRunner.TestMethodResult mr : cr.methodResults) {
                total++;
                TestResultItem.Status mStatus;
                if (mr.skipped) {
                    skipped++;
                    mStatus = TestResultItem.Status.SKIPPED;
                } else if (mr.passed) {
                    passed++;
                    mStatus = TestResultItem.Status.PASSED;
                } else {
                    failed++;
                    mStatus = TestResultItem.Status.FAILED;
                }

                String errorMsg = null;
                String stackTrace = null;
                String srcFile = null;
                int errLine = -1;

                if (mr.failure != null) {
                    errorMsg = mr.failure.getMessage();
                    if (errorMsg == null || errorMsg.isEmpty()) {
                        errorMsg = mr.failure.getClass().getSimpleName();
                    }
                    stackTrace = getStackTrace(mr.failure);
                    SourceLocation loc = extractLocation(stackTrace, cr.simpleName);
                    if (loc != null) {
                        srcFile = loc.fileName;
                        errLine = loc.lineNumber;
                    }
                }

                String displayName = (mr.displayName != null && !mr.displayName.isEmpty())
                        ? mr.displayName : mr.methodName;

                TestResultItem methodItem = new TestResultItem(
                        cr.className + "#" + mr.methodName,
                        displayName,
                        cr.className,
                        mr.methodName,
                        mr.durationMs,
                        mStatus,
                        errorMsg,
                        stackTrace,
                        srcFile,
                        errLine,
                        false
                );
                suiteItem.addChild(methodItem);
            }

            if (cr.classFailure != null && cr.methodResults.isEmpty()) {
                total++;
                failed++;
            }

            suiteItems.add(suiteItem);
        }

        return new ParsedReport(suiteItems, total, passed, failed, skipped, totalDuration);
    }

    /**
     * Parses raw test log output into a {@link ParsedReport}.
     */
    @NonNull
    public static ParsedReport fromOutputText(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) {
            return new ParsedReport(Collections.emptyList(), 0, 0, 0, 0, 0);
        }

        // Every branch below keys off a line's exact prefix or indentation, and a
        // colour code sits in front of both. Strip before parsing, not after.
        String[] lines = com.ccs.wiredroid.util.AnsiParser.stripAnsi(text).split("\n");
        List<TestResultItem> suites = new ArrayList<>();
        TestResultItem currentSuite = null;
        TestResultItem currentMethod = null;

        int total = 0;
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        long duration = 0;

        StringBuilder pendingStackTrace = new StringBuilder();

        for (String rawLine : lines) {
            String line = rawLine.trim();

            if (rawLine.startsWith("Testing started at") || line.startsWith("────────────")) {
                continue;
            }

            // Summary line: e.g. "✓ 5 tests passed (5 tests total, 45 ms)"
            if (line.contains("tests passed") || line.contains("Tests failed")) {
                Matcher numMatcher = Pattern.compile("(\\d+)\\s*ms").matcher(line);
                if (numMatcher.find()) {
                    try {
                        duration = Long.parseLong(numMatcher.group(1));
                    } catch (NumberFormatException ignored) {}
                }
                continue;
            }

            // Method line: starts with 2+ spaces then icon
            if (rawLine.startsWith("  ") && (rawLine.contains("✓") || rawLine.contains("✗") || rawLine.contains("⏸"))) {
                finishPendingError(currentMethod, pendingStackTrace);

                TestResultItem.Status status = rawLine.contains("✓")
                        ? TestResultItem.Status.PASSED
                        : (rawLine.contains("✗") ? TestResultItem.Status.FAILED : TestResultItem.Status.SKIPPED);

                total++;
                if (status == TestResultItem.Status.PASSED) passed++;
                else if (status == TestResultItem.Status.FAILED) failed++;
                else skipped++;

                long mDuration = parseDurationMs(line);
                String methodName = extractMethodName(line);
                String className = currentSuite != null ? currentSuite.className : "Test";

                currentMethod = new TestResultItem(
                        className + "#" + methodName + "_" + total,
                        methodName,
                        className,
                        methodName,
                        mDuration,
                        status,
                        null,
                        null,
                        null,
                        -1,
                        false
                );

                if (currentSuite != null) {
                    currentSuite.addChild(currentMethod);
                } else {
                    currentSuite = new TestResultItem(className, className, className, null,
                            mDuration, status, null, null, null, -1, true);
                    currentSuite.addChild(currentMethod);
                    suites.add(currentSuite);
                }
                continue;
            }

            // Suite / Class line: starts with icon without leading indentation
            if ((rawLine.startsWith("✓") || rawLine.startsWith("✗") || rawLine.startsWith("⏸"))
                    && !line.contains("tests passed") && !line.contains("Tests failed")) {
                finishPendingError(currentMethod, pendingStackTrace);
                currentMethod = null;

                TestResultItem.Status status = rawLine.startsWith("✓")
                        ? TestResultItem.Status.PASSED
                        : (rawLine.startsWith("✗") ? TestResultItem.Status.FAILED : TestResultItem.Status.SKIPPED);

                long sDuration = parseDurationMs(line);
                String name = extractSuiteName(line);

                currentSuite = new TestResultItem(
                        name + "_" + suites.size(),
                        name,
                        name,
                        null,
                        sDuration,
                        status,
                        null,
                        null,
                        null,
                        -1,
                        true
                );
                suites.add(currentSuite);
                continue;
            }

            // Error message or stack trace line
            if (currentMethod != null && currentMethod.status == TestResultItem.Status.FAILED) {
                if (line.startsWith("at ") || line.contains("Exception") || line.contains("Error")
                        || line.contains("expected:") || line.startsWith("--- output ---")) {
                    pendingStackTrace.append(rawLine).append("\n");
                }
            }
        }

        finishPendingError(currentMethod, pendingStackTrace);

        return new ParsedReport(suites, total, passed, failed, skipped, duration);
    }

    private static void finishPendingError(TestResultItem method, StringBuilder pendingStackTrace) {
        if (method == null || pendingStackTrace.length() == 0
                || method.status != TestResultItem.Status.FAILED) {
            return;
        }
        String stack = pendingStackTrace.toString();
        pendingStackTrace.setLength(0);
        SourceLocation loc = extractLocation(stack, method.className);
        String firstLine = stack.trim().split("\n")[0].trim();
        method.applyError(firstLine, stack, loc != null ? loc.fileName : null,
                loc != null ? loc.lineNumber : -1);
    }

    public static final class SourceLocation {
        public final String fileName;
        public final int lineNumber;

        public SourceLocation(String fileName, int lineNumber) {
            this.fileName = fileName;
            this.lineNumber = lineNumber;
        }
    }

    @Nullable
    public static SourceLocation extractLocation(@Nullable String stackTrace, @Nullable String targetClassName) {
        if (stackTrace == null || stackTrace.isEmpty()) return null;

        Matcher matcher = STACK_TRACE_PATTERN.matcher(stackTrace);
        SourceLocation fallbackLoc = null;

        while (matcher.find()) {
            String clz = matcher.group(1);
            String fileName = matcher.group(3);
            int line = -1;
            try {
                line = Integer.parseInt(matcher.group(4));
            } catch (NumberFormatException ignored) {}

            if (line > 0 && fileName != null) {
                SourceLocation loc = new SourceLocation(fileName, line);
                if (targetClassName != null && clz != null && clz.endsWith(targetClassName)) {
                    // Exact match on target test class!
                    return loc;
                }
                if (fallbackLoc == null && !clz.startsWith("org.junit")
                        && !clz.startsWith("org.opentest4j")
                        && !clz.startsWith("java.")
                        && !clz.startsWith("jdk.")
                        && !clz.startsWith("dalvik.")) {
                    fallbackLoc = loc;
                }
            }
        }
        return fallbackLoc;
    }

    private static String getStackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static long parseDurationMs(String line) {
        Matcher m = Pattern.compile("(\\d+)\\s*ms").matcher(line);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private static String extractSuiteName(String line) {
        String clean = line.replaceFirst("^[✓✗⏸]\\s*", "");
        int paren = clean.indexOf('(');
        if (paren > 0) clean = clean.substring(0, paren).trim();
        return clean;
    }

    private static String extractMethodName(String line) {
        String clean = line.replaceFirst("^[✓✗⏸]\\s*", "").trim();
        int paren = clean.indexOf('(');
        if (paren > 0) clean = clean.substring(0, paren).trim();
        return clean;
    }
}
