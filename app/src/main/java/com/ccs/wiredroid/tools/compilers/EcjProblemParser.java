package com.ccs.wiredroid.tools.compilers;

import com.ccs.wiredroid.analysis.ProblemItem;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Розбір виводу Eclipse Compiler (ECJ). Формати можуть відрізнятися за версією / локаллю.
 */
public final class EcjProblemParser {

    /** Типовий рядок: "1. ERROR in /path/File.java (at line 10)" або без номера блоку. */
    private static final Pattern P_STANDARD = Pattern.compile(
            "(?:^|\\n)\\s*(?:\\d+\\.\\s*)?(ERROR|WARNING)\\s+in\\s+([^\\n(]+?)\\s*\\(at line\\s+(\\d+)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private EcjProblemParser() {}

    public static List<ProblemItem> parse(String ecjStderr, File projectRoot) {
        List<ProblemItem> list = new ArrayList<>();
        if (ecjStderr == null || ecjStderr.trim().isEmpty()) return list;

        String text = ecjStderr.replace("\r\n", "\n");

        Matcher m = P_STANDARD.matcher(text);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            String sevStr = m.group(1).toUpperCase();
            String pathPart = m.group(2).trim();
            int line = Integer.parseInt(m.group(3));
            ProblemItem.Severity sev = sevStr.startsWith("W")
                    ? ProblemItem.Severity.WARNING : ProblemItem.Severity.ERROR;

            File f = resolvePath(pathPart, projectRoot);
            String msg = extractDescription(text, m.end());
            String key = (f != null ? f.getAbsolutePath() : pathPart) + ":" + line + ":" + msg;
            if (seen.add(key)) {
                list.add(new ProblemItem(sev, msg, f, line));
            }
        }

        if (list.isEmpty() && text.contains("ERROR")) {
            String snippet = extractFirstErrorBlock(text);
            list.add(new ProblemItem(ProblemItem.Severity.ERROR,
                    snippet, null, 0));
        }

        /* Будь-який непорожній stderr, який не розпізнано шаблоном (інша локаль / формат ECJ). */
        if (list.isEmpty() && !text.trim().isEmpty()) {
            String raw = text.trim();
            if (raw.length() > 2000) raw = raw.substring(0, 2000) + "…";
            list.add(new ProblemItem(ProblemItem.Severity.ERROR, raw, null, 0));
        }

        return list;
    }

    private static File resolvePath(String pathPart, File projectRoot) {
        String p = pathPart.trim();
        if (p.isEmpty()) return null;

        File f = new File(p);
        if (f.exists() && f.isFile()) return f;

        if (projectRoot != null) {
            File r = new File(projectRoot, p);
            if (r.exists() && r.isFile()) return r;
            String name = new File(p).getName();
            if (name.endsWith(".java")) {
                File found = findFileByName(projectRoot, name);
                if (found != null) return found;
            }
        }
        return f;
    }

    private static File findFileByName(File dir, String name) {
        File[] ch = dir.listFiles();
        if (ch == null) return null;
        for (File c : ch) {
            if (c.isDirectory()) {
                File found = findFileByName(c, name);
                if (found != null) return found;
            } else if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }

    private static String extractDescription(String fullText, int fromMatchEnd) {
        int nl = fullText.indexOf('\n', fromMatchEnd);
        if (nl < 0) return "Помилка компілятора";
        int end = fullText.indexOf("\n----------", nl + 1);
        if (end < 0) end = fullText.length();
        String block = fullText.substring(nl + 1, end).trim();
        int firstNl = block.indexOf('\n');
        if (firstNl > 0) {
            block = block.substring(0, firstNl).trim();
        }
        if (block.isEmpty()) return "Помилка компілятора";
        return block.length() > 500 ? block.substring(0, 500) + "…" : block;
    }

    private static String extractFirstErrorBlock(String text) {
        int idx = text.indexOf("ERROR");
        if (idx < 0) idx = 0;
        String sub = text.substring(idx).trim();
        return sub.length() > 800 ? sub.substring(0, 800) + "…" : sub;
    }

    /**
     * Для компіляції одного джерела всі діагностики відносять до відкритого файлу
     * (ім'я в кеші може відрізнятися, напр. App.java у кеші vs Main.java у вкладці).
     */
    public static List<ProblemItem> remapToLogicalFile(List<ProblemItem> items, File logicalFile) {
        if (logicalFile == null || items == null) return items;
        List<ProblemItem> out = new ArrayList<>(items.size());
        for (ProblemItem p : items) {
            out.add(new ProblemItem(p.severity, p.message, logicalFile, p.line));
        }
        return out;
    }
}
