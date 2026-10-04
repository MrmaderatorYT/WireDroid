package com.ccs.wiredroid.analysis;

import java.io.File;

public class ProblemItem {

    /**
     * SECURITY is deliberately its own level rather than a WARNING with a
     * differently worded message. A leaked API key and an unused import are not
     * the same kind of finding, and a user scanning a list of eighty warnings
     * will not spot the one that matters. It is listed last so that no existing
     * {@code ordinal()} shifts.
     */
    public enum Severity { ERROR, WARNING, INFO, SECURITY }

    public final Severity severity;
    public final String message;
    public final File file;
    public final int line;

    public ProblemItem(Severity severity, String message, File file, int line) {
        this.severity = severity;
        this.message = message;
        this.file = file;
        this.line = line;
    }
}
