package com.ccs.wiredroid.tools.refactor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ccs.wiredroid.project.ProjectScanner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RefactoringHelper {

    public interface RefactorCallback {
        void onResult(RefactorResult result);
    }

    public static class RefactorResult {
        public final String summary;
        public final int filesChanged;
        public final int occurrencesReplaced;
        public final List<FileChange> changes;

        public RefactorResult(String summary, int filesChanged, int occurrencesReplaced, List<FileChange> changes) {
            this.summary = summary;
            this.filesChanged = filesChanged;
            this.occurrencesReplaced = occurrencesReplaced;
            this.changes = changes;
        }
    }

    public static class FileChange {
        public final File file;
        public final String oldContent;
        public final String newContent;
        public final List<int[]> changedLines;

        public FileChange(File file, String oldContent, String newContent, List<int[]> changedLines) {
            this.file = file;
            this.oldContent = oldContent;
            this.newContent = newContent;
            this.changedLines = changedLines;
        }
    }

    private static final Set<String> JAVA_KEYWORDS = new HashSet<>();
    static {
        String[] kw = {"abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
                "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
                "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
                "interface", "long", "native", "new", "package", "private", "protected", "public",
                "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
                "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
                "null", "var", "record", "yield", "sealed", "non-sealed", "permits", "when",
                "exports", "module", "opens", "provides", "requires", "to", "uses", "with", "transitive"};
        for (String s : kw) JAVA_KEYWORDS.add(s);
    }

    private RefactoringHelper() {}

    public static void renameSymbolAsync(@NonNull Context context, @Nullable File projectRoot,
                                          @NonNull String oldName, @NonNull String newName,
                                          @NonNull RefactorCallback callback) {
        new Thread(() -> {
            try {
                RefactorResult result = renameSymbol(context, projectRoot, oldName, newName);
                new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result));
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onResult(
                        new RefactorResult("Error: " + e.getMessage(), 0, 0, new ArrayList<>())));
            }
        }).start();
    }

    public static RefactorResult renameSymbol(@NonNull Context context, @Nullable File projectRoot,
                                               @NonNull String oldName, @NonNull String newName) {
        if (projectRoot == null) {
            return new RefactorResult("No project open", 0, 0, new ArrayList<>());
        }

        List<File> sources = ProjectScanner.listJavaSources(projectRoot);
        List<FileChange> changes = new ArrayList<>();
        int totalReplacements = 0;

        Pattern wordPattern = Pattern.compile("\\b" + Pattern.quote(oldName) + "\\b");

        for (File file : sources) {
            try {
                String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                String cleaned = removeCommentsAndStrings(content);

                Matcher m = wordPattern.matcher(cleaned);
                int count = 0;
                while (m.find()) {
                    if (!isInStringOrComment(content, m.start())) {
                        count++;
                    }
                }

                if (count == 0) continue;

                List<int[]> lineChanges = new ArrayList<>();
                StringBuilder sb = new StringBuilder(content);
                int offset = 0;

                Matcher m2 = wordPattern.matcher(content);
                while (m2.find()) {
                    if (isInStringOrComment(content, m2.start())) continue;

                    int start = m2.start();
                    int end = m2.end();

                    if (start > 0 && content.charAt(start - 1) == '.') continue;
                    if (end < content.length() && content.charAt(end) == '(') continue;

                    String before = start > 0 ? content.substring(start - 1, start) : "";
                    String after = end < content.length() ? content.substring(end, end + 1) : "";
                    if (before.matches("[A-Za-z0-9_]") || after.matches("[A-Za-z0-9_]")) continue;

                    sb.replace(start + offset, end + offset, newName);
                    offset += newName.length() - oldName.length();

                    int lineNum = content.substring(0, m2.start()).split("\n").length;
                    lineChanges.add(new int[]{lineNum, lineNum});
                }

                if (offset != 0) {
                    String newContent = sb.toString();
                    changes.add(new FileChange(file, content, newContent, lineChanges));
                    totalReplacements += count;
                    com.ccs.wiredroid.util.LocalHistoryManager.saveSnapshot(
                            context, file, content, "Before rename " + oldName);
                    Files.write(file.toPath(), newContent.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
            }
        }

        String summary = "Renamed '" + oldName + "' → '" + newName + "': "
                + totalReplacements + " occurrences in " + changes.size() + " files";
        return new RefactorResult(summary, changes.size(), totalReplacements, changes);
    }

    public static void extractMethodAsync(@NonNull Context context, @NonNull String sourceCode,
                                           int startLine, int endLine, @NonNull String methodName,
                                           @NonNull RefactorCallback callback) {
        new Thread(() -> {
            try {
                String result = extractMethod(sourceCode, startLine, endLine, methodName);
                new Handler(Looper.getMainLooper()).post(() -> callback.onResult(
                        new RefactorResult(result != null ? "Method extracted: " + methodName : "Extraction failed",
                                result != null ? 1 : 0, 1, new ArrayList<>())));
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onResult(
                        new RefactorResult("Error: " + e.getMessage(), 0, 0, new ArrayList<>())));
            }
        }).start();
    }

    @Nullable
    public static String extractMethod(@NonNull String sourceCode, int startLine, int endLine,
                                        @NonNull String methodName) {
        String[] lines = sourceCode.split("\n", -1);
        if (startLine < 0 || endLine >= lines.length || startLine > endLine) return null;

        StringBuilder extractedBlock = new StringBuilder();
        for (int i = startLine; i <= endLine; i++) {
            extractedBlock.append(lines[i]);
            if (i < endLine) extractedBlock.append("\n");
        }

        String block = extractedBlock.toString();
        Set<String> usedVars = findUsedVariables(block);
        String paramList = buildParamList(usedVars, lines, startLine);
        String returnType = inferReturnType(block);

        StringBuilder methodBuilder = new StringBuilder();
        methodBuilder.append("\n    private ");
        if (returnType != null) methodBuilder.append(returnType).append(" ");
        else methodBuilder.append("void ");
        methodBuilder.append(methodName).append("(").append(paramList).append(") {\n");
        methodBuilder.append(block).append("\n");
        methodBuilder.append("    }\n");

        String callExpr = buildCallExpression(methodName, usedVars);

        StringBuilder newSource = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == startLine) {
                String indent = getIndent(lines[i]);
                newSource.append(indent).append(callExpr).append(";\n");
                i = endLine;
            } else {
                newSource.append(lines[i]);
                if (i < lines.length - 1) newSource.append("\n");
            }
        }

        int lastBrace = newSource.lastIndexOf("}");
        if (lastBrace >= 0) {
            newSource.insert(lastBrace, methodBuilder.toString());
        } else {
            newSource.append(methodBuilder.toString());
        }

        return newSource.toString();
    }

    public static void extractVariableAsync(@NonNull String sourceCode, int line, int startCol, int endCol,
                                             @NonNull String varName, @NonNull RefactorCallback callback) {
        new Thread(() -> {
            try {
                String result = extractVariable(sourceCode, line, startCol, endCol, varName);
                new Handler(Looper.getMainLooper()).post(() -> callback.onResult(
                        new RefactorResult(result != null ? "Variable extracted: " + varName : "Extraction failed",
                                result != null ? 1 : 0, 1, new ArrayList<>())));
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onResult(
                        new RefactorResult("Error: " + e.getMessage(), 0, 0, new ArrayList<>())));
            }
        }).start();
    }

    @Nullable
    public static String extractVariable(@NonNull String sourceCode, int line, int startCol, int endCol,
                                          @NonNull String varName) {
        String[] lines = sourceCode.split("\n", -1);
        if (line < 0 || line >= lines.length) return null;

        String targetLine = lines[line];
        if (startCol < 0 || endCol > targetLine.length() || startCol >= endCol) return null;

        String expression = targetLine.substring(startCol, endCol).trim();
        if (expression.isEmpty()) return null;

        String indent = getIndent(targetLine);
        String type = inferExpressionType(expression);

        lines[line] = targetLine.substring(0, startCol) + varName + targetLine.substring(endCol);

        StringBuilder newSource = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == line - 1 || (i == 0 && line == 0)) {
                newSource.append(indent);
                if (type != null) newSource.append(type).append(" ");
                else newSource.append("var ");
                newSource.append(varName).append(" = ").append(expression).append(";\n");
            }
            newSource.append(lines[i]);
            if (i < lines.length - 1) newSource.append("\n");
        }

        if (line == 0) {
            StringBuilder fixed = new StringBuilder();
            fixed.append(indent);
            if (type != null) fixed.append(type).append(" ");
            else fixed.append("var ");
            fixed.append(varName).append(" = ").append(expression).append(";\n");
            for (String l : lines) {
                fixed.append(l).append("\n");
            }
            return fixed.toString();
        }

        return newSource.toString();
    }

    public static void inlineMethodAsync(@NonNull Context context, @NonNull String sourceCode,
                                          @NonNull String methodName, @NonNull RefactorCallback callback) {
        new Thread(() -> {
            try {
                String result = inlineMethod(sourceCode, methodName);
                new Handler(Looper.getMainLooper()).post(() -> callback.onResult(
                        new RefactorResult(result != null ? "Inlined: " + methodName : "Inlining failed",
                                result != null ? 1 : 0, 1, new ArrayList<>())));
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onResult(
                        new RefactorResult("Error: " + e.getMessage(), 0, 0, new ArrayList<>())));
            }
        }).start();
    }

    @Nullable
    public static String inlineMethod(@NonNull String sourceCode, @NonNull String methodName) {
        Pattern methodPattern = Pattern.compile(
                "([ \\t]*)(?:private|public|protected)?\\s*(?:static)?\\s*\\w+\\s+"
                        + Pattern.quote(methodName) + "\\s*\\(([^)]*)\\)\\s*\\{",
                Pattern.MULTILINE);
        Matcher m = methodPattern.matcher(sourceCode);

        if (!m.find()) return null;

        int methodStart = m.start();
        String methodBody = extractMethodBody(sourceCode, m.end() - 1);
        if (methodBody == null) return null;

        int methodEnd = sourceCode.indexOf("}", m.end() + methodBody.length());
        if (methodEnd < 0) return null;
        methodEnd++;

        String paramStr = m.group(2).trim();
        String[] params = paramStr.isEmpty() ? new String[0] : paramStr.split(",");
        List<String> paramNames = new ArrayList<>();
        for (String p : params) {
            p = p.trim();
            int space = p.lastIndexOf(' ');
            if (space >= 0) paramNames.add(p.substring(space + 1).trim());
        }

        Pattern callPattern = Pattern.compile(
                "([ \\t]*)\\w+\\." + Pattern.quote(methodName) + "\\(([^)]*)\\)\\s*;\\s*\\n?");

        StringBuilder result = new StringBuilder(sourceCode);
        Matcher cm = callPattern.matcher(sourceCode);
        int offset = 0;
        while (cm.find()) {
            String indent = cm.group(1);
            String args = cm.group(2).trim();
            String[] argValues = args.isEmpty() ? new String[0] : args.split(",");

            String inlined = methodBody;
            for (int i = 0; i < Math.min(paramNames.size(), argValues.length); i++) {
                inlined = inlined.replaceAll("\\b" + Pattern.quote(paramNames.get(i).trim()) + "\\b",
                        argValues[i].trim());
            }

            String[] bodyLines = inlined.split("\n", -1);
            StringBuilder wrapped = new StringBuilder();
            for (String bl : bodyLines) {
                wrapped.append(indent).append(bl.trim()).append("\n");
            }

            int start = cm.start() + offset;
            int end = cm.end() + offset;
            result.replace(start, end, wrapped.toString());
            offset += wrapped.length() - (cm.end() - cm.start());
        }

        String removed = sourceCode.substring(methodStart, methodEnd);
        String finalResult = result.toString();
        int removedIdx = finalResult.indexOf(removed);
        if (removedIdx >= 0) {
            finalResult = finalResult.substring(0, removedIdx) + finalResult.substring(methodEnd);
        }

        return finalResult;
    }

    private static String removeCommentsAndStrings(String source) {
        StringBuilder sb = new StringBuilder(source.length());
        int i = 0;
        int len = source.length();
        while (i < len) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < len) {
                char next = source.charAt(i + 1);
                if (next == '/') {
                    while (i < len && source.charAt(i) != '\n') i++;
                    continue;
                } else if (next == '*') {
                    i += 2;
                    while (i < len && !(source.charAt(i) == '*' && i + 1 < len && source.charAt(i + 1) == '/')) {
                        i++;
                    }
                    i += 2;
                    continue;
                }
            }
            if (c == '"') {
                i++;
                while (i < len && source.charAt(i) != '"') {
                    if (source.charAt(i) == '\\') i++;
                    i++;
                }
                i++;
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < len && source.charAt(i) != '\'') {
                    if (source.charAt(i) == '\\') i++;
                    i++;
                }
                i++;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static boolean isInStringOrComment(String source, int offset) {
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < offset && i < source.length(); i++) {
            char c = source.charAt(i);
            if (inLineComment) {
                if (c == '\n') inLineComment = false;
            } else if (inBlockComment) {
                if (c == '*' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i++;
                }
            } else if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inString = false;
            } else if (inChar) {
                if (c == '\\') { i++; continue; }
                if (c == '\'') inChar = false;
            } else {
                if (c == '/' && i + 1 < source.length()) {
                    char next = source.charAt(i + 1);
                    if (next == '/') inLineComment = true;
                    else if (next == '*') { inBlockComment = true; i++; }
                } else if (c == '"') inString = true;
                else if (c == '\'') inChar = true;
            }
        }
        return inString || inChar || inLineComment || inBlockComment;
    }

    private static Set<String> findUsedVariables(String block) {
        Set<String> vars = new HashSet<>();
        Pattern assign = Pattern.compile("(\\w+)\\s*=");
        Matcher m = assign.matcher(block);
        while (m.find()) {
            String name = m.group(1);
            if (!JAVA_KEYWORDS.contains(name) && !Character.isUpperCase(name.charAt(0))) {
                vars.add(name);
            }
        }
        return vars;
    }

    private static String buildParamList(Set<String> vars, String[] lines, int extractStart) {
        Map<String, String> varTypes = new HashMap<>();
        for (int i = 0; i < extractStart && i < lines.length; i++) {
            String line = lines[i].trim();
            Pattern decl = Pattern.compile("(\\w+(?:<[^>]+>)?)\\s+(\\w+)\\s*[=;]");
            Matcher m = decl.matcher(line);
            while (m.find()) {
                varTypes.put(m.group(2), m.group(1));
            }
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String v : vars) {
            if (!first) sb.append(", ");
            String type = varTypes.getOrDefault(v, "Object");
            sb.append(type).append(" ").append(v);
            first = false;
        }
        return sb.toString();
    }

    private static String inferReturnType(String block) {
        Pattern returnPattern = Pattern.compile("return\\s+(.+?)\\s*;");
        Matcher m = returnPattern.matcher(block);
        if (m.find()) {
            String expr = m.group(1).trim();
            if (expr.matches("\\d+L?")) return "long";
            if (expr.matches("\\d+")) return "int";
            if (expr.matches("\\d+\\.\\d+[fFdD]?")) return "double";
            if (expr.matches("true|false")) return "boolean";
            if (expr.startsWith("\"")) return "String";
        }
        return null;
    }

    private static String buildCallExpression(String methodName, Set<String> usedVars) {
        StringBuilder sb = new StringBuilder(methodName).append("(");
        boolean first = true;
        for (String v : usedVars) {
            if (!first) sb.append(", ");
            sb.append(v);
            first = false;
        }
        sb.append(")");
        return sb.toString();
    }

    private static String extractMethodBody(String source, int braceStart) {
        int depth = 0;
        for (int i = braceStart; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(braceStart + 1, i).trim();
                }
            }
        }
        return null;
    }

    private static String inferExpressionType(String expr) {
        if (expr.matches("\\d+L?")) return "long";
        if (expr.matches("\\d+")) return "int";
        if (expr.matches("\\d+\\.\\d+[fFdD]?")) return "double";
        if (expr.matches("true|false")) return "boolean";
        if (expr.startsWith("\"")) return "String";
        if (expr.startsWith("'")) return "char";
        if (expr.contains("+") && expr.contains("\"")) return "String";
        return null;
    }

    private static String getIndent(String line) {
        StringBuilder indent = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == ' ') indent.append(' ');
            else if (c == '\t') indent.append('\t');
            else break;
        }
        return indent.toString();
    }

    public static boolean isValidIdentifier(String name) {
        if (name == null || name.isEmpty()) return false;
        if (JAVA_KEYWORDS.contains(name)) return false;
        if (!Character.isJavaIdentifierStart(name.charAt(0))) return false;
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) return false;
        }
        return true;
    }

    public static List<UsageLocation> findUsages(File projectRoot, String symbolName) {
        List<UsageLocation> usages = new ArrayList<>();
        if (projectRoot == null) return usages;

        List<File> sources = ProjectScanner.listJavaSources(projectRoot);
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(symbolName) + "\\b");

        for (File file : sources) {
            try {
                String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                String[] lines = content.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    Matcher m = pattern.matcher(lines[i]);
                    while (m.find()) {
                        if (!isInStringOrComment(content, contentIndexOf(content, lines, i, m.start()))) {
                            usages.add(new UsageLocation(file, i + 1, m.start(), m.end(), lines[i].trim()));
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return usages;
    }

    private static int contentIndexOf(String content, String[] lines, int lineIdx, int colIdx) {
        int offset = 0;
        for (int i = 0; i < lineIdx; i++) {
            offset += lines[i].length() + 1;
        }
        return offset + colIdx;
    }

    public static class UsageLocation {
        public final File file;
        public final int line;
        public final int startCol;
        public final int endCol;
        public final String lineContent;

        public UsageLocation(File file, int line, int startCol, int endCol, String lineContent) {
            this.file = file;
            this.line = line;
            this.startCol = startCol;
            this.endCol = endCol;
            this.lineContent = lineContent;
        }
    }
}
