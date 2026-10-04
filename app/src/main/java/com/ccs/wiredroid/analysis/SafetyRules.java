package com.ccs.wiredroid.analysis;

import android.content.Context;
import java.io.File;
import java.util.List;
import java.util.regex.Pattern;

import com.ccs.wiredroid.R;

final class SafetyRules {

    private static final Pattern P_STRING_EQ = Pattern.compile("(?:==|!=)\\s*\"[^\"]*\"|\"[^\"]*\"\\s*(?:==|!=)");
    private static final Pattern P_THROWABLE_CATCH = Pattern.compile("catch\\s*\\(\\s*(?:Throwable|Exception)\\s+\\w+\\s*\\)");
    private static final Pattern P_CATCH_EMPTY = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}");

    private SafetyRules() {}

    static void analyze(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        analyzeStringComparison(ctx, lines, f, out);
        analyzeSafety(ctx, lines, f, out);
        analyzeEmptyCatch(ctx, lines, f, out);
        analyzeExceptionHandling(ctx, lines, f, out);
        analyzeNullSafety(ctx, lines, f, out);
        analyzeThreadSafety(ctx, lines, f, out);
        analyzeResourceManagement(ctx, lines, f, out);
    }

    private static void analyzeStringComparison(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (P_STRING_EQ.matcher(l).find() && !l.contains("length()"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_string_equals), f, i + 1));
        }
    }

    private static void analyzeSafety(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;

            if (P_THROWABLE_CATCH.matcher(l).find())
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_catch_throwable), f, i + 1));
            if (l.contains("protected void finalize()"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_finalize), f, i + 1));
            if ((l.contains("double") || l.contains("float")) && l.contains("==") && !l.contains("!=") && !l.contains("case"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, "Порівняння double/float через ==", f, i + 1));
            if (l.contains("new Date()") || l.contains("java.util.Date"))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_java_util_date), f, i + 1));
            if (l.contains("new SimpleDateFormat"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_simple_date_format), f, i + 1));
            // SQL injection moved to SecurityRules. The check here fired on the
            // mere presence of a SQL literal, so a fully parameterised
            // PreparedStatement was reported as an injection too — and a rule
            // that is wrong most of the time trains people to ignore it.
            if (l.contains("Math.random()"))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_math_random), f, i + 1));
            if (l.contains("Runtime.getRuntime().exec(") || l.contains("ProcessBuilder"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_runtime_exec), f, i + 1));
            if ((l.contains("static ") && !l.contains("final ") && l.contains("List"))
                    || (l.contains("static ") && !l.contains("final ") && l.contains("Map"))
                    || (l.contains("static ") && !l.contains("final ") && l.contains("Set")))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_mutable_static), f, i + 1));
        }
    }

    private static void analyzeEmptyCatch(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            if (P_CATCH_EMPTY.matcher(lines[i]).find())
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_empty_catch), f, i + 1));
            if (lines[i].trim().startsWith("catch") && i + 1 < lines.length) {
                String next = lines[i + 1].trim();
                if (!next.equals("}") && !next.contains("throw") && !next.contains("log")
                        && !next.contains("System.err") && next.length() < 5)
                    out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_catch_no_log), f, i + 1));
            }
        }
    }

    private static void analyzeExceptionHandling(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (l.contains("throws ") && l.contains("Exception") && !l.contains("throws IOException")
                    && !l.contains("throws InterruptedException"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, "throws Exception — використовуйте специфічні виключення", f, i + 1));
        }
    }

    private static void analyzeNullSafety(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (l.contains("Optional.get()") && !lines[i].contains("isPresent()"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, "Optional.get() без isPresent()", f, i + 1));
            if (l.contains(".equals(null)"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, "Використовуйте == null замість .equals(null)", f, i + 1));
        }
    }

    private static void analyzeThreadSafety(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (l.contains("SimpleDateFormat") && lines[i].contains("static"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, "SimpleDateFormat в static контексті — небезпечно для потоків", f, i + 1));
        }
    }

    private static void analyzeResourceManagement(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (l.contains("Connection") && l.contains("DriverManager.getConnection")) {
                boolean hasTryWithResources = false;
                for (int j = Math.max(0, i - 2); j <= Math.min(lines.length - 1, i + 1); j++) {
                    if (lines[j].contains("try (")) hasTryWithResources = true;
                }
                if (!hasTryWithResources)
                    out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_connection_leak), f, i + 1));
            }
            if ((l.contains("new Socket(") || l.contains("new ServerSocket(")) && !l.contains("try ("))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, "Socket без try-with-resources", f, i + 1));
        }
    }

    private static String str(Context ctx, int resId) {
        if (ctx != null) return ctx.getString(resId);
        return "";
    }

    private static String str(Context ctx, int resId, Object... args) {
        if (ctx != null) return ctx.getString(resId, args);
        return "";
    }
}
