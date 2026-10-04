package com.ccs.wiredroid.analysis;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ccs.wiredroid.R;

final class StructureRules {

    private static final int MAX_METHOD_LENGTH = 50;
    private static final int MAX_CLASS_LENGTH = 500;
    private static final int MAX_PARAMETERS = 5;
    private static final int MAX_CYCLOMATIC = 10;
    private static final int MAX_NESTING_DEPTH = 4;
    private static final int MAX_FIELDS = 15;

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
    private static final Pattern P_IMPORT = Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.*]+)\\s*;");
    private static final Pattern P_CLASS_DECL = Pattern.compile(
            "(?:public|private|protected)?\\s*(?:abstract|final)?\\s*(class|interface|enum)\\s+(\\w+)");
    private static final Pattern P_FIELD_DECL = Pattern.compile(
            "^\\s*(?:private|protected|public)\\s+(?:static\\s+)?(?:final\\s+)?[\\w<>,\\[\\]]+\\s+(\\w+)\\s*[=;]");
    private static final Pattern P_LOCAL_VAR = Pattern.compile(
            "^\\s*(?:final\\s+)?(?:[A-Z][\\w<>\\[\\],]*|int|boolean|byte|char|short|long|float|double|var)\\s+(\\w+)\\s*[=;]");
    private static final Pattern P_RETURN_STMT = Pattern.compile("\\breturn\\b");

    private StructureRules() {}

    static void analyze(Context ctx, String content, String[] lines, File f, List<ProblemItem> out) {
        analyzeImports(ctx, content, lines, f, out);
        analyzeMethods(ctx, content, lines, f, out);
        analyzeClass(ctx, lines, f, out);
        analyzeFieldRules(ctx, lines, f, out);
    }

    private static void analyzeImports(Context ctx, String content, String[] lines, File f, List<ProblemItem> out) {
        Set<String> imports = new HashSet<>();
        List<Integer> importLineNums = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            Matcher m = P_IMPORT.matcher(lines[i]);
            if (m.find()) { imports.add(m.group(2)); importLineNums.add(i); }
        }

        for (int idx = 0; idx < importLineNums.size(); idx++) {
            int lineNum = importLineNums.get(idx);
            String imp = new ArrayList<>(imports).get(idx);
            if (imp.endsWith("*")) continue;
            String simpleName = imp.substring(imp.lastIndexOf('.') + 1);
            boolean used = false;
            for (int j = lineNum + 1; j < lines.length; j++) {
                if (lines[j].contains(simpleName)) { used = true; break; }
            }
            if (!used) out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_unused_import, imp), f, lineNum + 1));
        }

        Set<String> seen = new HashSet<>();
        for (String imp : imports) {
            if (!seen.add(imp))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_duplicate_import, imp), f, 1));
        }
    }

    private static void analyzeMethods(Context ctx, String content, String[] lines, File f, List<ProblemItem> out) {
        List<int[]> methods = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = P_METHOD_DECL.matcher(lines[i]);
            if (m.find() && !lines[i].trim().startsWith("//") && !lines[i].trim().startsWith("*"))
                methods.add(new int[]{i, 0, 0, 0});
        }

        for (int[] method : methods) {
            int startLine = method[0];
            int braceCount = 0;
            boolean foundOpen = false;
            int endLine = startLine;
            for (int i = startLine; i < lines.length; i++) {
                for (char c : lines[i].toCharArray()) {
                    if (c == '{') { braceCount++; foundOpen = true; }
                    else if (c == '}') braceCount--;
                }
                if (foundOpen && braceCount == 0) { endLine = i; break; }
            }

            int methodLength = 0;
            for (int i = startLine; i <= endLine && i < lines.length; i++) {
                String tl = lines[i].trim();
                if (!tl.isEmpty() && !tl.startsWith("//") && !tl.startsWith("*") && !tl.startsWith("/*")) methodLength++;
            }
            int complexity = computeCyclomaticComplexity(lines, startLine, endLine);
            int paramCount = countParameters(lines[startLine]);

            if (methodLength > MAX_METHOD_LENGTH)
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, "Метод занадто довгий (" + methodLength + " рядків)", f, startLine + 1));
            if (complexity > MAX_CYCLOMATIC)
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, "Висока цикломатична складність (" + complexity + ")", f, startLine + 1));
            if (paramCount > MAX_PARAMETERS)
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, "Забагато параметрів (" + paramCount + ")", f, startLine + 1));

            int maxNesting = computeMaxNesting(lines, startLine, endLine);
            if (maxNesting > MAX_NESTING_DEPTH)
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, "Глибока вкладеність (" + maxNesting + " рівнів)", f, startLine + 1));

            if (methodLength <= 2 && lines[startLine].contains("{"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, "Порожній метод", f, startLine + 1));

            String methodName = extractMethodName(lines[startLine]);
            if (methodName != null && !methodName.isEmpty() && Character.isUpperCase(methodName.charAt(0))
                    && !methodName.equals(methodName.toUpperCase()) && !lines[startLine].contains("class "))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, "Назва методу з великої літери: " + methodName, f, startLine + 1));

            int returnCount = 0;
            for (int i = startLine; i <= endLine && i < lines.length; i++)
                if (P_RETURN_STMT.matcher(lines[i]).find()) returnCount++;
            if (returnCount > 3)
                out.add(new ProblemItem(ProblemItem.Severity.INFO, "Багато return-ів (" + returnCount + ") — розгляньте ранні return", f, startLine + 1));

            if (lines[startLine].contains("public ") && !lines[startLine].contains("class ") && !lines[startLine].contains("interface ")) {
                boolean hasDoc = false;
                if (startLine > 0) {
                    for (int j = startLine - 1; j >= Math.max(0, startLine - 5); j--) {
                        if (lines[j].contains("/**") || lines[j].contains("*/") || lines[j].trim().startsWith("*")) { hasDoc = true; break; }
                    }
                }
                if (!hasDoc) out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_missing_javadoc_method), f, startLine + 1));
            }

            int localVarCount = 0;
            for (int i = startLine; i <= endLine && i < lines.length; i++)
                if (P_LOCAL_VAR.matcher(lines[i]).find()) localVarCount++;
            if (localVarCount > 10)
                out.add(new ProblemItem(ProblemItem.Severity.INFO, "Забагато локальних змінних (" + localVarCount + ") — розгляньте рефакторинг", f, startLine + 1));
        }
    }

    private static void analyzeClass(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        int classStart = -1;
        int fieldCount = 0;
        int classBraceCount = 0;
        boolean inClass = false;

        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (!inClass && P_CLASS_DECL.matcher(l).find()) { classStart = i; inClass = true; }

            if (inClass) {
                for (char c : lines[i].toCharArray()) {
                    if (c == '{') classBraceCount++;
                    else if (c == '}') classBraceCount--;
                }
                if ((l.startsWith("private ") || l.startsWith("protected ") || l.startsWith("public "))
                        && l.contains(";") && !l.contains("(")) fieldCount++;

                if (classBraceCount == 0 && classStart >= 0) {
                    int classLength = i - classStart + 1;
                    if (classLength > MAX_CLASS_LENGTH)
                        out.add(new ProblemItem(ProblemItem.Severity.WARNING, "Клас занадто великий (" + classLength + " рядків)", f, classStart + 1));
                    if (fieldCount > MAX_FIELDS)
                        out.add(new ProblemItem(ProblemItem.Severity.WARNING, "Забагато полів класу (" + fieldCount + ")", f, classStart + 1));
                    if (classLength > 300 && fieldCount > 10)
                        out.add(new ProblemItem(ProblemItem.Severity.WARNING, "God class — занадто багато відповідальності", f, classStart + 1));

                    if (lines[classStart].contains("implements") && lines[classStart].contains("Serializable")) {
                        boolean hasSerial = false;
                        for (int j = classStart; j <= i; j++) {
                            if (lines[j].contains("serialVersionUID")) { hasSerial = true; break; }
                        }
                        if (!hasSerial)
                            out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_serial_version_uid), f, classStart + 1));
                    }

                    Set<String> fieldNames = new HashSet<>();
                    for (int j = classStart; j <= i; j++) {
                        Matcher fm = P_FIELD_DECL.matcher(lines[j]);
                        if (fm.find()) fieldNames.add(fm.group(1));
                    }
                    for (String fname : fieldNames) {
                        boolean used = false;
                        for (int j = classStart; j <= i; j++) {
                            if (lines[j].contains(fname) && !P_FIELD_DECL.matcher(lines[j]).find()) { used = true; break; }
                        }
                        if (!used) out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_unused_field, fname), f, classStart + 1));
                    }

                    classStart = -1; fieldCount = 0; inClass = false;
                }
            }
        }
    }

    private static void analyzeFieldRules(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*") || l.startsWith("import")) continue;
            if (l.contains("static final") && l.contains("=")) {
                String varName = l.replaceAll(".*static\\s+final\\s+\\w+\\s+(\\w+)\\s*=.*", "$1");
                if (!varName.equals(l) && !varName.matches("[A-Z_][A-Z0-9_]*"))
                    out.add(new ProblemItem(ProblemItem.Severity.INFO, "Константа '" + varName + "' — UPPER_CASE", f, i + 1));
            }
        }
    }

    private static int computeCyclomaticComplexity(String[] lines, int start, int end) {
        int cc = 1;
        for (int i = start; i <= end && i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            cc += countPattern(l, "\\bif\\b") + countPattern(l, "\\bcase\\b") + countPattern(l, "\\bfor\\b")
                    + countPattern(l, "\\bwhile\\b") + countPattern(l, "\\bcatch\\b")
                    + countPattern(l, "&&") + countPattern(l, "\\|\\|") + countPattern(l, "\\?");
        }
        return cc;
    }

    private static int computeMaxNesting(String[] lines, int start, int end) {
        int max = 0, current = 0;
        for (int i = start; i <= end && i < lines.length; i++) {
            for (char c : lines[i].toCharArray()) {
                if (c == '{') { current++; max = Math.max(max, current); }
                else if (c == '}') current--;
            }
        }
        return max;
    }

    private static int countParameters(String line) {
        int idx = line.indexOf('(');
        if (idx < 0) return 0;
        int end = line.indexOf(')', idx);
        if (end < 0) return 0;
        String params = line.substring(idx + 1, end).trim();
        return params.isEmpty() ? 0 : params.split(",").length;
    }

    private static String extractMethodName(String line) {
        Matcher m = P_METHOD_DECL.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    static int countPattern(String line, String regex) {
        int count = 0;
        Matcher m = Pattern.compile(regex).matcher(line);
        while (m.find()) count++;
        return count;
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
