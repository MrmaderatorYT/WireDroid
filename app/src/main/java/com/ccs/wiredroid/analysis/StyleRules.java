package com.ccs.wiredroid.analysis;

import android.content.Context;
import java.io.File;
import java.util.List;
import java.util.regex.Pattern;

import com.ccs.wiredroid.R;

final class StyleRules {

    private static final int MAX_LINE_LENGTH = 160;
    private static final Pattern P_CATCH_EMPTY = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}");

    private StyleRules() {}

    static void analyze(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        analyzeLineRules(ctx, lines, f, out);
        analyzeCodeStyle(ctx, lines, f, out);
        analyzeNaming(ctx, lines, f, out);
        analyzeDocumentation(ctx, lines, f, out);
    }

    private static void analyzeLineRules(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (line.length() > MAX_LINE_LENGTH)
                out.add(new ProblemItem(ProblemItem.Severity.INFO,
                        str(ctx, R.string.sa_long_line, MAX_LINE_LENGTH, line.length()), f, i + 1));

            String lower = line.toLowerCase();
            if (lower.contains("todo")) out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_todo), f, i + 1));
            if (lower.contains("fixme")) out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_fixme), f, i + 1));
            if (lower.contains("hack")) out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_hack), f, i + 1));
            if (lower.contains("xxx")) out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_xxx), f, i + 1));
            if (line.contains("noinspection")) out.add(new ProblemItem(ProblemItem.Severity.INFO, "noinspection — приховує попередження аналізатора", f, i + 1));

            if (!line.isEmpty() && line.charAt(line.length() - 1) == ' ')
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_trailing_space), f, i + 1));

            if (line.contains("\t") && line.contains("    "))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_mixed_tabs), f, i + 1));

            if (trimmed.startsWith("//") && !trimmed.startsWith("///")
                    && (trimmed.contains("(") || trimmed.contains("=") || trimmed.contains(";")))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_commented_code), f, i + 1));

            if (line.contains("@Deprecated"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_deprecated_api), f, i + 1));
            if (line.contains("System.exit("))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_system_exit), f, i + 1));
            if (line.contains("Thread.sleep(") && !line.contains("try"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_thread_sleep), f, i + 1));

            if (line.matches(".*\\b(List|Map|Set|ArrayList|HashMap|HashSet|LinkedList)\\s+\\w+.*") && !line.contains("<"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, "Raw type — використовуйте параметризований тип", f, i + 1));

            if (line.contains("C:\\\\") || line.contains("/home/") || line.contains("/Users/") || line.contains("D:\\\\"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_hardcoded_path), f, i + 1));

            if (line.matches(".*\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b.*"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_hardcoded_ip), f, i + 1));

            if ((lower.contains("password") || lower.contains("secret") || lower.contains("api_key"))
                    && (lower.contains("=") || lower.contains(":")))
                out.add(new ProblemItem(ProblemItem.Severity.ERROR, str(ctx, R.string.sa_password_in_code), f, i + 1));

            if ((trimmed.startsWith("if") || trimmed.startsWith("for") || trimmed.startsWith("while") || trimmed.startsWith("else"))
                    && trimmed.endsWith(" {}"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING,
                        "Порожній блок " + trimmed.substring(0, trimmed.indexOf(' ')), f, i + 1));

            int semicolons = 0;
            for (char c : line.toCharArray()) { if (c == ';') semicolons++; }
            if (semicolons > 2 && !trimmed.startsWith("//") && !trimmed.startsWith("for") && !trimmed.startsWith("import"))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_multiple_statements), f, i + 1));

            if (trimmed.startsWith("import") && trimmed.contains(".*;"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_wildcard_import), f, i + 1));

            if (trimmed.contains("@SuppressWarnings(\"all\")"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_suppress_all), f, i + 1));

            if (line.contains("\"\" +") || line.contains("+ \"\""))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_empty_string_concat), f, i + 1));

            if ((line.contains("new FileInputStream(") || line.contains("new FileOutputStream(")
                    || line.contains("new BufferedReader(") || line.contains("new BufferedWriter("))
                    && !line.contains("try ("))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_resource_leak), f, i + 1));
        }
    }

    private static void analyzeCodeStyle(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*") || l.startsWith("import")) continue;

            if ((l.startsWith("if") || l.startsWith("for") || l.startsWith("while") || l.startsWith("else"))
                    && l.endsWith(")") && i + 1 < lines.length && !lines[i + 1].trim().startsWith("{"))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_one_line_block), f, i + 1));

            if (l.contains("?") && l.contains(":") && l.length() > 100)
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_long_ternary), f, i + 1));

            if (l.equals(";") || l.equals("};"))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_unnecessary_semicolon), f, i + 1));

            if (l.contains("static final") && l.contains("=")) {
                String varName = l.replaceAll(".*static\\s+final\\s+\\w+\\s+(\\w+)\\s*=.*", "$1");
                if (!varName.equals(l) && !varName.matches("[A-Z_][A-Z0-9_]*"))
                    out.add(new ProblemItem(ProblemItem.Severity.INFO, "Константа '" + varName + "' — UPPER_CASE", f, i + 1));
            }

            if (l.startsWith("this.") && !l.contains("this("))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, "Redundant this. в контексті", f, i + 1));

            if (l.contains("== true") || l.contains("== false") || l.contains("!= true") || l.contains("!= false"))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_bool_true_false), f, i + 1));
        }
    }

    private static void analyzeNaming(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        Pattern pVarDecl = Pattern.compile(
                "^\\s*(?:private|protected|public)?\\s*(?:static)?\\s*(?:final)?\\s*"
                + "[A-Z][\\w<>\\[\\],]*\\s+([a-z]\\w*)\\s*[=;]");

        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*") || l.startsWith("import")) continue;

            if (!l.contains("for ") && !l.contains("for(")) {
                java.util.regex.Matcher m = pVarDecl.matcher(l);
                if (m.find()) {
                    String varName = m.group(1);
                    if (varName.length() == 1 && !varName.equals("i") && !varName.equals("j") && !varName.equals("e"))
                        out.add(new ProblemItem(ProblemItem.Severity.INFO, "Однолітерна змінна '" + varName + "'", f, i + 1));
                }
            }

            if (l.contains("boolean ") && l.contains("=")) {
                String varName = l.replaceAll(".*boolean\\s+(\\w+)\\s*=.*", "$1");
                if (!varName.equals(l) && !varName.startsWith("is") && !varName.startsWith("has")
                        && !varName.startsWith("can") && !varName.startsWith("should")
                        && !varName.startsWith("was") && !varName.startsWith("will")
                        && !varName.startsWith("use") && !varName.startsWith("enable")
                        && !varName.startsWith("disable") && !varName.startsWith("flag"))
                    out.add(new ProblemItem(ProblemItem.Severity.INFO, "Boolean '" + varName + "' — префікс is/has/can", f, i + 1));
            }
        }
    }

    private static void analyzeDocumentation(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        Pattern pClassDecl = Pattern.compile(
                "(?:public|private|protected)?\\s*(?:abstract|final)?\\s*(class|interface|enum)\\s+(\\w+)");

        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (pClassDecl.matcher(l).find() && l.contains("public")) {
                boolean hasDoc = false;
                if (i > 0) {
                    for (int j = i - 1; j >= Math.max(0, i - 5); j--) {
                        if (lines[j].contains("/**") || lines[j].contains("*/") || lines[j].trim().startsWith("*")) {
                            hasDoc = true;
                            break;
                        }
                    }
                }
                if (!hasDoc)
                    out.add(new ProblemItem(ProblemItem.Severity.INFO, "Відсутній JavaDoc для публічного класу", f, i + 1));
            }
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
