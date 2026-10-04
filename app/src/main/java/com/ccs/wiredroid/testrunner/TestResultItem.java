package com.ccs.wiredroid.testrunner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Model representing a test suite (class) or individual test method in the execution tree.
 */
public final class TestResultItem {

    public enum Status {
        PASSED,
        FAILED,
        SKIPPED
    }

    public final String id;
    public final String title;
    public final String className;
    @Nullable public final String methodName;
    public final long durationMs;
    public final Status status;
    // Not final: the text-log parser only learns a failure's message and source
    // location from the lines that follow the one naming the test, so it has to
    // fill these in afterwards. They used to be final and were written by
    // reflection, which swallowed every failure and would break under R8.
    @Nullable public String errorMessage;
    @Nullable public String stackTrace;
    @Nullable public String sourceFile;
    public int errorLine;
    public final boolean isSuite;
    public final List<TestResultItem> children = new ArrayList<>();
    public boolean isExpanded = true;

    public TestResultItem(@NonNull String id,
                          @NonNull String title,
                          @NonNull String className,
                          @Nullable String methodName,
                          long durationMs,
                          @NonNull Status status,
                          @Nullable String errorMessage,
                          @Nullable String stackTrace,
                          @Nullable String sourceFile,
                          int errorLine,
                          boolean isSuite) {
        this.id = id;
        this.title = title;
        this.className = className;
        this.methodName = methodName;
        this.durationMs = durationMs;
        this.status = status;
        this.errorMessage = errorMessage;
        this.stackTrace = stackTrace;
        this.sourceFile = sourceFile;
        this.errorLine = errorLine;
        this.isSuite = isSuite;
    }

    public void addChild(@NonNull TestResultItem child) {
        children.add(child);
    }

    /**
     * Attaches what a failure turned out to be, once its trailing lines are read.
     *
     * @param sourceFile file named by the stack frame, or {@code null} if none was found
     * @param errorLine  1-based line in that file, or a non-positive value if unknown
     */
    public void applyError(@Nullable String errorMessage, @Nullable String stackTrace,
                           @Nullable String sourceFile, int errorLine) {
        this.errorMessage = errorMessage;
        this.stackTrace = stackTrace;
        if (sourceFile != null) {
            this.sourceFile = sourceFile;
            this.errorLine = errorLine;
        }
    }

    public boolean hasFailedChildren() {
        for (TestResultItem child : children) {
            if (child.status == Status.FAILED) return true;
        }
        return false;
    }

    @NonNull
    public List<TestResultItem> getChildren() {
        return Collections.unmodifiableList(children);
    }
}
