package com.ccs.wiredroid.analysis;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ccs.wiredroid.R;

final class QualityRules {

    private static final int MAX_ENUM_CONSTANTS = 20;
    /** One nesting level of <...>; spaces inside are fine because > ends it. */
    private static final String GENERIC = "<[^<>]*(?:<[^<>]*>[^<>]*)*>";

    private static final Pattern P_MAGIC_NUMBER = Pattern.compile("(?<!=|!|<|>|\\+|-|\\*|/|%|\\||&|\\^|~|<<|>>)\\s*(\\d{2,})\\b");
    private static final Pattern P_SYSOUT = Pattern.compile("System\\.out\\.print(ln)?\\s*\\(");
    private static final Pattern P_SYSE = Pattern.compile("System\\.err\\.print(ln)?\\s*\\(");
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
    private static final Pattern P_FIELD_DECL = Pattern.compile(
            "^\\s*(?:private|protected|public)\\s+(?:static\\s+)?(?:final\\s+)?[\\w<>,\\[\\]]+\\s+(\\w+)\\s*[=;]");

    private QualityRules() {}

    static void analyze(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        analyzeMagicNumbers(ctx, lines, f, out);
        analyzeSysout(ctx, lines, f, out);
        analyzeProductivity(ctx, lines, f, out);
        analyzeArchitecture(ctx, lines, f, out);
        analyzeApiDesign(ctx, lines, f, out);
        analyzeCopyPaste(ctx, lines, f, out);
        analyzeDeprecated(ctx, lines, f, out);
        analyzeCollections(ctx, lines, f, out);
        analyzeImmutability(ctx, lines, f, out);
        analyzeEnums(ctx, lines, f, out);
        analyzeTesting(ctx, lines, f, out);
        analyzeSwitch(ctx, lines, f, out);
        analyzeInline(ctx, lines, f, out);
        analyzeFinalLocal(ctx, lines, f, out);
    }

    private static void analyzeMagicNumbers(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*") || l.startsWith("import")) continue;
            Matcher m = P_MAGIC_NUMBER.matcher(l);
            while (m.find()) {
                int val = Integer.parseInt(m.group(1));
                if (val == 0 || val == 1 || val == -1) continue;
                if (l.contains("static final") || l.contains("@") || l.contains("new int[")
                        || l.contains("case ") || l.contains("0x")) continue;
                out.add(new ProblemItem(ProblemItem.Severity.INFO, "Magic number " + m.group(1) + " — винесіть у константу", f, i + 1));
            }
        }
    }

    private static void analyzeSysout(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (P_SYSOUT.matcher(l).find()) out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_system_out), f, i + 1));
            if (P_SYSE.matcher(l).find()) out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_system_err), f, i + 1));
            if (l.contains(".printStackTrace()"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_print_stacktrace), f, i + 1));
        }
    }

    private static void analyzeProductivity(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (l.contains("+") && l.contains(".toString()"))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_redundant_tostring), f, i + 1));
            if (l.contains(".indexOf(") && (l.contains("== -1") || l.contains("!= -1")))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_indexof_contains), f, i + 1));
            if (l.contains(".length() == 0") || l.contains(".length() > 0") || l.contains(".length() != 0"))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_length_zero), f, i + 1));
            if (l.contains(".size() == 0") || l.contains(".size() > 0") || l.contains(".size() != 0"))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_size_zero), f, i + 1));
        }
    }

    private static void analyzeArchitecture(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            int dots = 0;
            for (char c : l.toCharArray()) { if (c == '.') dots++; }
            if (dots > 4)
                out.add(new ProblemItem(ProblemItem.Severity.INFO, "Довгий ланцюжок викликів (" + dots + ")", f, i + 1));
        }
    }

    private static void analyzeApiDesign(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (l.contains("public ") && (l.contains("List<") || l.contains("Map<") || l.contains("Set<"))
                    && l.contains(";") && !l.contains("Collections.unmodifiable"))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, "Публічний метод повертає мутабельну колекцію", f, i + 1));
        }
    }

    private static void analyzeCopyPaste(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        Map<String, Integer> lineCounts = new HashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.length() < 20 || trimmed.startsWith("//") || trimmed.startsWith("*")) continue;
            lineCounts.merge(trimmed, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : lineCounts.entrySet()) {
            if (e.getValue() >= 3)
                out.add(new ProblemItem(ProblemItem.Severity.INFO, "Дубльований код (" + e.getValue() + " разів): " + e.getKey().substring(0, Math.min(40, e.getKey().length())), f, 1));
        }
    }

    private static void analyzeDeprecated(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (l.contains("new Hashtable") || l.contains("new Vector") || l.contains("new StringBuffer"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, "Застарілий клас — використовуйте сучасні аналоги", f, i + 1));
            if (l.contains(".toString().equals("))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_tostring_equals), f, i + 1));
        }
    }

    private static void analyzeCollections(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (l.contains(".keySet()") && i + 1 < lines.length && lines[i + 1].contains(".get("))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_keyset_get), f, i + 1));
        }
    }

    private static void analyzeImmutability(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (l.contains("private String ") && !l.contains("final") && l.contains(";"))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, "Приватне поле String без final — розгляньте immutability", f, i + 1));
        }
    }

    private static void analyzeEnums(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (l.contains(".ordinal()"))
                out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_enum_ordinal), f, i + 1));
        }
    }

    private static void analyzeTesting(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        String name = f.getName();
        if (!name.contains("Test") && !name.contains("test")) return;
        boolean hasAssertion = false;
        for (String line : lines) {
            if (line.contains("assert") || line.contains("verify")) { hasAssertion = true; break; }
        }
        if (!hasAssertion && lines.length > 5)
            out.add(new ProblemItem(ProblemItem.Severity.WARNING, str(ctx, R.string.sa_test_no_assert), f, 1));
    }

    private static void analyzeSwitch(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            if (l.contains("switch") && l.contains("(") && !l.contains("default"))
                out.add(new ProblemItem(ProblemItem.Severity.INFO, str(ctx, R.string.sa_switch_no_default), f, i + 1));
        }
    }

    private static void analyzeInline(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        for (int i = 0; i < lines.length; i++) {
            Matcher m = P_METHOD_DECL.matcher(lines[i]);
            if (m.find() && !lines[i].trim().startsWith("//") && !lines[i].trim().startsWith("*")) {
                String methodName = m.group(1);
                if (methodName == null) continue;
                int callCount = 0;
                for (String line : lines) {
                    if (line.contains(methodName + "(")) callCount++;
                }
                if (callCount == 1) {
                    out.add(new ProblemItem(ProblemItem.Severity.INFO, "Метод '" + methodName + "' викликається 1 раз — розгляньте inline", f, i + 1));
                }
            }
        }
    }

    private static void analyzeFinalLocal(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        Pattern pLocalVar = Pattern.compile(
                "^\\s*(?:final\\s+)?(?:[A-Z][\\w<>\\[\\],]*|int|boolean|byte|char|short|long|float|double|var)\\s+(\\w+)\\s*[=;]");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("//") || l.startsWith("*")) continue;
            Matcher m = pLocalVar.matcher(l);
            if (m.find() && !l.contains("final ")) {
                String varName = m.group(1);
                boolean reassigned = false;
                for (int j = i + 1; j < lines.length; j++) {
                    if (lines[j].contains(varName + " =") || lines[j].contains(varName + "=")) {
                        reassigned = true;
                        break;
                    }
                }
                if (!reassigned)
                    out.add(new ProblemItem(ProblemItem.Severity.INFO, "Змінна '" + varName + "' не перезаписується — зробіть final", f, i + 1));
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
