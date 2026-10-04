package com.ccs.wiredroid.analysis;

import android.content.Context;
import java.io.File;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ccs.wiredroid.R;

final class ModernRules {

    /** One nesting level of <...>; spaces inside are fine because > ends it. */
    private static final String GENERIC = "<[^<>]*(?:<[^<>]*>[^<>]*)*>";

    private static final Pattern P_METHOD_DECL = Pattern.compile(
            // The type part must not be a character class that contains \\s while the
            // next token is \\s+ — the two can claim the same spaces, and the engine
            // then tries every split. That is what this used to be, and on real Java
            // it did not finish: one pass over eight source files ran for minutes,
            // which is why the Problems panel stayed empty on any project of size.
            // Generics keep their spaces here because <...> delimits them, so nothing
            // is ambiguous. The rewrite is also stricter — it no longer reports "if"
            // from `if (x > 0) {` — and it now handles array and qualified return
            // types, which the old one missed.
            "^\\s*(?:(?:public|private|protected|static|final|synchronized|abstract"
            + "|native|default|strictfp)\\s+)*"
            + "(?:" + GENERIC + "\\s+)?"
            + "[\\w.$\\[\\]]+(?:" + GENERIC + ")?(?:\\[\\])*"
            + "\\s+(\\w+)\\s*\\(");

    private ModernRules() {}

    static void analyze(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        analyzeModernization(ctx, lines, f, out);
        analyzeStreams(ctx, lines, f, out);
        analyzeLambda(ctx, lines, f, out);
        analyzeOptional(ctx, lines, f, out);
        analyzeDate(ctx, lines, f, out);
        analyzeRegex(ctx, lines, f, out);
        analyzeGenerics(ctx, lines, f, out);
        analyzeAnnotations(ctx, lines, f, out);
    }

    private static void analyzeModernization(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (l.contains("new StringBuilder()") || l.contains("new StringBuffer()"))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, "StringBuilder/Buffer — розгляньте String.join або concat", f, i + 1));
            if (l.contains("new ") && l.contains("Runnable()") || l.contains("new OnClickListener()"))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, "Анонімний клас — розгляньте лямбду", f, i + 1));
        }
    }

    private static void analyzeStreams(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (l.contains(".stream()") && !l.contains(".collect(") && !l.contains(".forEach(")
                    && !l.contains(".map(") && !l.contains(".filter(") && !l.contains(".reduce("))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_stream_no_terminal), f, i + 1));
            if (l.contains(".findFirst().get()"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, "findFirst().get() без isPresent() — використовуйте findFirst().orElseThrow()", f, i + 1));
        }
    }

    private static void analyzeLambda(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (l.contains("->") && l.contains("{")) {
                int braceCount = 0;
                for (int j = i; j < Math.min(i + 10, lines.length); j++) {
                    for (char c : lines[j].toCharArray()) {
                        if (c == '{') braceCount++;
                        else if (c == '}') braceCount--;
                    }
                    if (braceCount == 0 && j > i) {
                        if (j - i > 5) out.add(new ProblemItem(ProblemItem.Severity.INFO, "Лямбда занадто довга (" + (j - i) + " рядків)", f, i + 1));
                        break;
                    }
                }
            }
        }
    }

    private static void analyzeOptional(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (l.contains("Optional.get()") && !l.contains("isPresent()"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, "Optional.get() без isPresent()", f, i + 1));
            if (l.contains("Optional.orElse(null)"))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, "Optional.orElse(null) — розгляньте orElseThrow() або isPresent()", f, i + 1));
        }
    }

    private static void analyzeDate(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (l.contains("new Date()") || l.contains("Calendar.getInstance()"))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_java_util_date), f, i + 1));
        }
    }

    private static void analyzeRegex(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (l.contains("Pattern.compile(") && (l.contains("for") || l.contains("while")))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, "Pattern.compile() в циклі", f, i + 1));
            if (l.contains(".matches(") && !l.contains("Pattern"))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_string_matches), f, i + 1));
        }
    }

    private static void analyzeGenerics(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (l.contains("(List)") || l.contains("(Map)") || l.contains("(Set)"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, "Raw type в касті", f, i + 1));
        }
    }

    private static void analyzeAnnotations(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (l.contains("@SuppressWarnings(\"unchecked\")"))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, "@SuppressWarnings(\"unchecked\") — перевірте чи потрібно", f, i + 1));
        }
    }

    private static String str(Context ctx, int resId) {
        if (ctx != null) return ctx.getString(resId);
        return "";
    }
}
