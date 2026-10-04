package com.ccs.wiredroid.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.ccs.wiredroid.project.ProjectManager;
import com.ccs.wiredroid.analysis.ProblemItem;
import com.ccs.wiredroid.analysis.StaticAnalyzer;
import com.ccs.wiredroid.util.languages.ast.JavaAstParser;
import com.ccs.wiredroid.util.languages.ast.JavaLexer;
import com.ccs.wiredroid.ui.MemberOutline;
import com.ccs.wiredroid.git.GitManager;
import com.ccs.wiredroid.util.AppPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * A file-aware coding agent backed by Gemini's native function-calling protocol.
 *
 * <p>The agent never edits "where the cursor happens to be". It reads a project
 * file, identifies one exact and unique fragment, and queues a replacement for
 * that canonical path. A virtual in-memory copy is updated after every accepted
 * edit, so a second tool call sees the first change instead of stale source.</p>
 */
public class GeminiAgent {

    public interface AgentCallback {
        void onToolCall(String toolName, String args);
        void onToolResult(String toolName, String result);
        void onTextResponse(String text);
        void onError(String error);
        void onDone();
        default void onChangeSetReady(PendingEdits.AgentChangeSet changeSet) {}
    }

    private static final int MAX_ITERATIONS = 14;
    private static final int MAX_FILE_CHARS = 600_000;
    private static final int MAX_TREE_ENTRIES = 600;
    private static final int MAX_SEARCH_FILES = 3_000;

    private static final Set<String> SKIPPED_DIRECTORIES = new HashSet<>(Arrays.asList(
            ".git", ".gradle", ".idea", "build", "target", "out", "node_modules"));
    private static final Set<String> SEARCHABLE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "java", "kt", "kts", "xml", "gradle", "properties", "json", "md", "txt",
            "yaml", "yml", "toml", "html", "htm", "css", "js", "ts", "tsx", "jsx",
            "c", "cc", "cpp", "h", "hpp", "py", "sh", "sql", "gitignore"));

    private final Context context;
    private final AgentCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService agentExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, String> workingFiles = new HashMap<>();
    private final Map<String, String> baseFiles = new HashMap<>();
    private final Set<String> createdFiles = new HashSet<>();
    private final Set<String> deletedFiles = new HashSet<>();

    private volatile boolean cancelled;
    private volatile boolean planOnly;
    private long turnId;
    private int iterationCount;
    private String currentCode = "";
    private String currentFileName = "";
    private String currentFilePath = "";
    private String configuredProjectRoot = "";
    private String workingProjectRoot = "";
    private JSONArray conversationContents = new JSONArray();

    public GeminiAgent(Context context, AgentCallback callback) {
        this.context = context;
        this.callback = callback;
    }

    public void setPlanOnly(boolean planOnly) { this.planOnly = planOnly; }

    /** Backwards-compatible entry point for callers that do not know the file path. */
    public void send(String userMessage, String codeContext, String fileName,
                     List<GeminiService.ChatMessage> history) {
        send(userMessage, codeContext, fileName, "", "", history);
    }

    public void send(String userMessage, String codeContext, String fileName, String filePath,
                     List<GeminiService.ChatMessage> history) {
        send(userMessage, codeContext, fileName, filePath, "", history);
    }

    /** Starts one complete agent turn, including all function calls it needs. */
    public void send(String userMessage, String codeContext, String fileName, String filePath,
                     String projectRoot, List<GeminiService.ChatMessage> history) {
        final long token;
        synchronized (this) {
            cancelled = false;
            token = ++turnId;
            iterationCount = 0;
            currentFileName = fileName == null ? "" : fileName;
            configuredProjectRoot = projectRoot == null ? "" : projectRoot;
            String rootKey = canonicalProjectRoot();
            if (!rootKey.equals(workingProjectRoot)) {
                workingFiles.clear();
                baseFiles.clear();
                createdFiles.clear();
                deletedFiles.clear();
            }
            workingProjectRoot = rootKey;
            currentFilePath = canonicalCurrentPath(filePath);
            String stagedCurrent = currentFilePath.isEmpty()
                    ? null : workingFiles.get(currentFilePath);
            currentCode = stagedCurrent != null ? stagedCurrent
                    : (codeContext == null ? "" : codeContext);
            if (!currentFilePath.isEmpty()) workingFiles.put(currentFilePath, currentCode);
            if (!currentFilePath.isEmpty()) baseFiles.putIfAbsent(currentFilePath, currentCode);
            conversationContents = buildInitialContents(userMessage, history);
        }
        requestNext(token);
    }

    private JSONArray buildInitialContents(String userMessage,
                                            List<GeminiService.ChatMessage> history) {
        JSONArray contents = new JSONArray();
        try {
            if (history != null) {
                for (GeminiService.ChatMessage item : history) {
                    contents.put(message(item.isFromUser() ? "user" : "model",
                            new JSONObject().put("text", item.getText())));
                }
            }
            contents.put(message("user", new JSONObject().put("text",
                    userMessage == null ? "" : userMessage)));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return contents;
    }

    private void requestNext(long token) {
        if (!isCurrent(token)) return;
        final JSONObject body;
        try {
            body = buildAgentRequest();
        } catch (JSONException e) {
            finishError(token, "Could not build the agent request: " + e.getMessage());
            return;
        }
        GeminiService.generateContent(context, body, new GeminiService.JsonResponseCallback() {
            @Override public void onSuccess(JSONObject response) {
                if (!isCurrent(token)) return;
                try {
                    agentExecutor.execute(() -> processResponse(token, response));
                } catch (RejectedExecutionException ignored) {
                    // The activity was destroyed while the network call completed.
                }
            }

            @Override public void onError(String error) {
                finishError(token, error);
            }
        });
    }

    private JSONObject buildAgentRequest() throws JSONException {
        JSONObject body = new JSONObject();
        body.put("systemInstruction", new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", systemInstruction()))));
        body.put("contents", conversationContents);

        body.put("tools", new JSONArray().put(new JSONObject()
                .put("functionDeclarations", toolDeclarations())));
        body.put("toolConfig", new JSONObject().put("functionCallingConfig",
                new JSONObject().put("mode", "AUTO")));

        JSONObject generation = new JSONObject();
        generation.put("temperature", 0.2);
        generation.put("maxOutputTokens", 8192);
        generation.put("thinkingConfig", new JSONObject().put("thinkingBudget", 0));
        body.put("generationConfig", generation);
        return body;
    }

    private String systemInstruction() {
        String visible = currentFilePath.isEmpty()
                ? (currentFileName.isEmpty() ? "none" : currentFileName)
                : relativePath(new File(currentFilePath));
        return "You are a coding agent inside WireDroid. The current file is " + visible + ".\n"
                + (planOnly ? "PLAN ONLY: inspect and explain a concrete implementation plan. "
                + "Do not propose edits, file creation, deletion, or rename.\n" : "")
                + "Work like a careful IDE agent: inspect relevant files before changing them, "
                + "follow references when needed, and use project-relative paths.\n"
                + "For every change to an existing file call editFile(path, find, replace). `find` "
                + "must be copied exactly from the latest file content and must occur exactly once. "
                + "Include enough surrounding lines to make it unique.\n"
                + "To add an import, field, method, or block, replace a stable nearby anchor with "
                + "that same anchor plus the new code in `replace`. Never use a cursor position. "
                + "Never rewrite a whole file to make a local change. Before finishing, call "
                + "getDiagnostics for changed Java files and fix relevant errors or new warnings.\n"
                + "All editFile and createFile calls are staged in one proposed change set; nothing "
                + "is written until the user reviews and applies that complete set. After editFile "
                + "succeeds, its changed content becomes the latest version for later tool calls. "
                + "If it reports not found or ambiguous, read the file and retry with a "
                + "correct longer fragment. Use createFile only for a genuinely new file.\n"
                + "Use renameFile or deleteFile only when explicitly requested. A file rename does "
                + "not update source references automatically; explain that and stage related edits.\n"
                + "Do not paste code in the final response when tools can apply it. At the end, give "
                + "a concise summary of files changed and what was done.";
    }

    private JSONArray toolDeclarations() throws JSONException {
        JSONArray declarations = new JSONArray();
        declarations.put(declaration("getCurrentFile",
                "Read the complete latest contents and project-relative path of the file open in the editor.",
                new JSONObject(), new JSONArray()));
        declarations.put(declaration("getProjectStructure",
                "List the project tree so you can locate relevant files.",
                new JSONObject(), new JSONArray()));

        JSONObject readProps = new JSONObject();
        readProps.put("path", stringProperty("Project-relative path of the file to read."));
        readProps.put("startLine", new JSONObject().put("type", "integer")
                .put("description", "Optional first line to return, one-based."));
        readProps.put("endLine", new JSONObject().put("type", "integer")
                .put("description", "Optional last line to return, one-based."));
        declarations.put(declaration("readFile", "Read the latest contents or a line range of one project file.",
                readProps, new JSONArray().put("path")));

        JSONObject listProps = new JSONObject();
        listProps.put("path", stringProperty(
                "Project-relative directory to list. Omit for the project root."));
        declarations.put(declaration("listFiles", "List the immediate children of a directory.",
                listProps, new JSONArray()));

        JSONObject searchProps = new JSONObject();
        searchProps.put("query", stringProperty("Literal, case-sensitive text to search for."));
        searchProps.put("path", stringProperty(
                "Optional project-relative file or directory to limit the search."));
        searchProps.put("maxResults", new JSONObject().put("type", "integer")
                .put("description", "Maximum matches to return, from 1 to 100."));
        declarations.put(declaration("searchInProject",
                "Search project text files and return path, line number, and matching line.",
                searchProps, new JSONArray().put("query")));

        JSONObject diagnosticProps = new JSONObject();
        diagnosticProps.put("path", stringProperty(
                "Optional project-relative Java file. Omit to check the current file."));
        declarations.put(declaration("getDiagnostics",
                "Run the project's static source analyzer on the latest staged Java contents.",
                diagnosticProps, new JSONArray()));

        JSONObject symbolsProps = new JSONObject();
        symbolsProps.put("path", stringProperty("Project-relative Java or Kotlin source file."));
        declarations.put(declaration("listSymbols",
                "List declared types, fields, constructors, and methods with source lines.",
                symbolsProps, new JSONArray().put("path")));

        declarations.put(declaration("getGitDiff",
                "Read the current working-tree diff so you can preserve the user's existing changes.",
                new JSONObject(), new JSONArray()));

        JSONObject refsProps = new JSONObject();
        refsProps.put("symbol", stringProperty("Exact identifier text to find across project files."));
        refsProps.put("path", stringProperty("Optional project-relative file or directory scope."));
        refsProps.put("maxResults", new JSONObject().put("type", "integer")
                .put("description", "Maximum matches, 1 to 100."));
        declarations.put(declaration("findReferences",
                "Find textual references to a symbol and return file paths and line numbers.",
                refsProps, new JSONArray().put("symbol")));

        JSONObject editProps = new JSONObject();
        editProps.put("path", stringProperty("Project-relative path of the existing file to edit."));
        editProps.put("find", stringProperty(
                "Exact unique text copied from the latest file contents, preserving all whitespace."));
        editProps.put("replace", stringProperty(
                "Exact replacement text. Use an empty string only to delete the matched fragment."));
        declarations.put(declaration("editFile",
                "Replace one exact unique fragment in a specific existing file.",
                editProps, new JSONArray().put("path").put("find").put("replace")));

        JSONObject createProps = new JSONObject();
        createProps.put("path", stringProperty("Project-relative path for the new file."));
        createProps.put("content", stringProperty("Complete contents of the new file."));
        declarations.put(declaration("createFile",
                "Create a new project file. Refuses to overwrite an existing file.",
                createProps, new JSONArray().put("path").put("content")));

        JSONObject deleteProps = new JSONObject();
        deleteProps.put("path", stringProperty("Project-relative path of the file to remove."));
        declarations.put(declaration("deleteFile",
                "Propose deleting an existing project file. The deletion waits for user review.",
                deleteProps, new JSONArray().put("path")));

        JSONObject renameProps = new JSONObject();
        renameProps.put("oldPath", stringProperty("Current project-relative file path."));
        renameProps.put("newPath", stringProperty("New project-relative file path."));
        declarations.put(declaration("renameFile",
                "Propose a file rename as a reviewed delete-and-create operation.",
                renameProps, new JSONArray().put("oldPath").put("newPath")));
        if (!planOnly) return declarations;
        JSONArray readOnly = new JSONArray();
        for (int i = 0; i < declarations.length(); i++) {
            JSONObject declaration = declarations.optJSONObject(i);
            String name = declaration == null ? "" : declaration.optString("name");
            if (!"editFile".equals(name) && !"createFile".equals(name)
                    && !"deleteFile".equals(name) && !"renameFile".equals(name)) {
                readOnly.put(declaration);
            }
        }
        return readOnly;
    }

    private static JSONObject declaration(String name, String description, JSONObject properties,
                                          JSONArray required) throws JSONException {
        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        if (required.length() > 0) parameters.put("required", required);
        return new JSONObject().put("name", name).put("description", description)
                .put("parameters", parameters);
    }

    private static JSONObject stringProperty(String description) throws JSONException {
        return new JSONObject().put("type", "string").put("description", description);
    }

    private void processResponse(long token, JSONObject response) {
        if (!isCurrent(token)) return;
        if (++iterationCount > MAX_ITERATIONS) {
            finishError(token, "The agent reached its tool-call limit before finishing.");
            return;
        }
        try {
            JSONArray candidates = response.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                finishError(token, "Gemini returned no candidate response.");
                return;
            }
            JSONObject candidate = candidates.getJSONObject(0);
            JSONObject content = candidate.optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            if (content == null || parts == null || parts.length() == 0) {
                finishError(token, "The agent stopped without a response ("
                        + candidate.optString("finishReason", "empty response") + ").");
                return;
            }

            // Preserve the exact model turn, including opaque signatures used by
            // newer Gemini models to validate subsequent function responses.
            conversationContents.put(content);
            List<JSONObject> calls = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null) continue;
                JSONObject call = part.optJSONObject("functionCall");
                if (call != null) calls.add(call);
                String piece = part.optString("text", "");
                if (!piece.isEmpty()) {
                    if (text.length() > 0) text.append('\n');
                    text.append(piece);
                }
            }

            if (calls.isEmpty()) {
                if (text.length() == 0) finishError(token,
                        "The agent returned neither text nor a tool call.");
                else finishText(token, text.toString());
                return;
            }

            JSONArray responseParts = new JSONArray();
            for (JSONObject call : calls) {
                if (!isCurrent(token)) return;
                String name = call.optString("name", "");
                JSONObject args = call.optJSONObject("args");
                if (args == null) args = new JSONObject();
                postToolCall(name, args.toString());
                String result = executeTool(name, args);
                if (!isCurrent(token)) return;
                postToolResult(name, result);

                JSONObject functionResponse = new JSONObject();
                functionResponse.put("name", name);
                if (call.has("id")) functionResponse.put("id", call.optString("id"));
                functionResponse.put("response", new JSONObject().put("result", result));
                responseParts.put(new JSONObject().put("functionResponse", functionResponse));
            }
            conversationContents.put(new JSONObject().put("role", "user")
                    .put("parts", responseParts));
            requestNext(token);
        } catch (Exception e) {
            finishError(token, "Could not process the agent response: " + e.getMessage());
        }
    }

    private String executeTool(String name, JSONObject args) {
        try {
            if (planOnly && isMutationTool(name)) return "Unavailable in plan-only mode.";
            switch (name) {
                case "getCurrentFile": return executeGetCurrentFile();
                case "getProjectStructure": return executeGetProjectStructure();
                case "readFile": return executeReadFile(args);
                case "listFiles": return executeListFiles(args);
                case "searchInProject": return executeSearch(args);
                case "getDiagnostics": return executeDiagnostics(args);
                case "listSymbols": return executeListSymbols(args);
                case "getGitDiff": return executeGitDiff();
                case "findReferences": return executeFindReferences(args);
                case "editFile": return executeEditFile(args);
                case "createFile": return executeCreateFile(args);
                case "deleteFile": return executeDeleteFile(args);
                case "renameFile": return executeRenameFile(args);
                default: return "Unknown or unavailable tool: " + name;
            }
        } catch (JSONException e) {
            return "Invalid arguments for " + name + ": " + e.getMessage();
        } catch (Exception e) {
            return "Error executing " + name + ": "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static boolean isMutationTool(String name) {
        return "editFile".equals(name) || "createFile".equals(name)
                || "deleteFile".equals(name) || "renameFile".equals(name);
    }

    private String executeGitDiff() {
        try {
            String diff = GitManager.diffWorkingTree(projectRoot());
            if (diff == null || diff.trim().isEmpty()) return "Working tree has no unstaged diff.";
            return shorten(diff, 24_000);
        } catch (Exception e) {
            return "Git diff unavailable: " + e.getMessage();
        }
    }

    private String executeFindReferences(JSONObject args) throws Exception {
        JSONObject search = new JSONObject().put("query", args.optString("symbol"))
                .put("path", args.optString("path", "."))
                .put("maxResults", args.optInt("maxResults", 50));
        return executeSearch(search);
    }

    private String executeGetCurrentFile() {
        if (currentCode.isEmpty() && currentFilePath.isEmpty()) {
            return "No file is open in the editor.";
        }
        String path = currentFilePath.isEmpty() ? currentFileName
                : relativePath(new File(currentFilePath));
        return "Path: " + path + "\n" + currentCode;
    }

    private String executeReadFile(JSONObject args) throws Exception {
        File file = resolveProjectFile(args.getString("path"), true);
        if (!file.isFile() && !createdFiles.contains(file.getCanonicalPath())) {
            return "Not a file: " + relativePath(file);
        }
        String content = latestContents(file);
        if (content.length() > MAX_FILE_CHARS) {
            return "File is too large to return safely (" + content.length()
                    + " characters). Use searchInProject to narrow the location.";
        }
        String[] lines = content.split("\\n", -1);
        int start = Math.max(1, args.optInt("startLine", 1));
        int end = Math.min(lines.length, Math.max(start, args.optInt("endLine", lines.length)));
        StringBuilder result = new StringBuilder("Path: ").append(relativePath(file))
                .append("\nLines: ").append(start).append('-').append(end).append("\n");
        for (int i = start - 1; i < end; i++) {
            result.append(i + 1).append(": ").append(lines[i]).append('\n');
        }
        return result.toString();
    }

    private String executeListFiles(JSONObject args) throws Exception {
        File directory = resolveProjectFile(args.optString("path", "."), true);
        if (!directory.isDirectory()) return "Not a directory: " + relativePath(directory);
        File root = projectRoot();
        File[] children = directory.listFiles();
        if (children == null || children.length == 0) return "Empty directory.";
        Arrays.sort(children, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (File child : children) {
            if (isSkipped(child) || !isInsideProject(root, child)) continue;
            out.append(child.isDirectory() ? "[DIR] " : "[FILE] ")
                    .append(relativePath(child)).append('\n');
            if (++count >= 400) {
                out.append("... list truncated");
                break;
            }
        }
        return out.length() == 0 ? "Empty directory." : out.toString();
    }

    private String executeGetProjectStructure() {
        try {
            File root = projectRoot();
            StringBuilder out = new StringBuilder();
            int[] count = {0};
            appendTree(root, root, out, 0, count);
            if (count[0] >= MAX_TREE_ENTRIES) out.append("... tree truncated\n");
            return out.toString();
        } catch (Exception e) {
            return "Cannot read project structure: " + e.getMessage();
        }
    }

    private void appendTree(File root, File directory, StringBuilder out,
                            int depth, int[] count) {
        if (depth > 6 || count[0] >= MAX_TREE_ENTRIES) return;
        File[] children = directory.listFiles();
        if (children == null) return;
        Arrays.sort(children, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (File child : children) {
            if (count[0] >= MAX_TREE_ENTRIES) return;
            if (isSkipped(child) || !isInsideProject(root, child)) continue;
            out.append("  ".repeat(depth)).append(child.isDirectory() ? "[D] " : "[F] ")
                    .append(child.getName()).append('\n');
            count[0]++;
            if (child.isDirectory()) appendTree(root, child, out, depth + 1, count);
        }
    }

    private String executeSearch(JSONObject args) throws Exception {
        String query = args.getString("query");
        if (query.isEmpty()) return "searchInProject requires a non-empty query.";
        int limit = Math.max(1, Math.min(100, args.optInt("maxResults", 30)));
        File start = resolveProjectFile(args.optString("path", "."), true);
        File root = projectRoot();
        ArrayDeque<File> pending = new ArrayDeque<>();
        pending.add(start);
        StringBuilder matches = new StringBuilder();
        int results = 0;
        int scanned = 0;

        while (!pending.isEmpty() && results < limit && scanned < MAX_SEARCH_FILES) {
            File item = pending.removeFirst();
            if (!isInsideProject(root, item)) continue;
            if (item.isDirectory()) {
                File[] children = item.listFiles();
                if (children == null) continue;
                List<File> ordered = new ArrayList<>(Arrays.asList(children));
                ordered.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File child : ordered) {
                    if (!isSkipped(child) && isInsideProject(root, child)) pending.addLast(child);
                }
                continue;
            }
            scanned++;
            if (!isSearchable(item) || item.length() > MAX_FILE_CHARS * 4L) continue;
            String content;
            try {
                content = latestContents(item);
            } catch (Exception ignored) {
                continue;
            }
            int from = 0;
            int line = 1;
            int countedUntil = 0;
            while (results < limit) {
                int at = content.indexOf(query, from);
                if (at < 0) break;
                for (int i = countedUntil; i < at; i++) if (content.charAt(i) == '\n') line++;
                countedUntil = at;
                int lineStart = content.lastIndexOf('\n', Math.max(0, at - 1)) + 1;
                int lineEnd = content.indexOf('\n', at);
                if (lineEnd < 0) lineEnd = content.length();
                String preview = content.substring(lineStart, lineEnd).trim();
                matches.append(relativePath(item)).append(':').append(line).append(": ")
                        .append(shorten(preview, 220)).append('\n');
                results++;
                from = at + Math.max(1, query.length());
            }
        }
        if (results == 0) return "No matches for: " + query;
        if (results >= limit) matches.append("... result limit reached\n");
        if (scanned >= MAX_SEARCH_FILES) matches.append("... file scan limit reached\n");
        return matches.toString();
    }

    private String executeDiagnostics(JSONObject args) throws Exception {
        String requested = args.optString("path", "");
        File file = requested.isEmpty() && !currentFilePath.isEmpty()
                ? new File(currentFilePath) : resolveProjectFile(requested, true);
        if ((!file.isFile() && !createdFiles.contains(file.getCanonicalPath()))
                || !"java".equalsIgnoreCase(extension(file.getName()))) {
            return "getDiagnostics supports Java source files only.";
        }
        List<ProblemItem> findings = StaticAnalyzer.analyzeSource(context, file, latestContents(file));
        if (findings.isEmpty()) return "No static analysis findings for " + relativePath(file) + ".";
        StringBuilder out = new StringBuilder("Diagnostics for ").append(relativePath(file)).append(":\n");
        int count = 0;
        for (ProblemItem finding : findings) {
            out.append(finding.severity.name()).append(" line ").append(finding.line)
                    .append(": ").append(finding.message).append('\n');
            if (++count >= 80) { out.append("... findings truncated\n"); break; }
        }
        return out.toString();
    }

    private String executeListSymbols(JSONObject args) throws Exception {
        File file = resolveProjectFile(args.getString("path"), true);
        String lower = file.getName().toLowerCase(Locale.ROOT);
        if (!MemberOutline.supports(lower)) return "listSymbols supports Java and Kotlin source files.";
        String source = latestContents(file);
        if (source.length() > MAX_FILE_CHARS) return "Source file is too large to outline safely.";
        StringBuilder out = new StringBuilder(relativePath(file)).append(":\n");
        if (lower.endsWith(".java")) {
            JavaAstParser parser = new JavaAstParser(new JavaLexer(source).tokenize());
            parser.parse();
            for (String type : parser.declaredTypes) out.append("TYPE ").append(type).append('\n');
        }
        for (MemberOutline.Member member : MemberOutline.scan(source, lower)) {
            out.append(member.kind.name()).append(" line ").append(member.line + 1)
                    .append(": ").append(member.label).append('\n');
        }
        return out.toString();
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private String executeEditFile(JSONObject args) throws Exception {
        File file = resolveProjectFile(args.getString("path"), true);
        if (!file.isFile() && !createdFiles.contains(file.getCanonicalPath())) {
            return "editFile target is not a file: " + relativePath(file);
        }
        String find = ExactTextEdit.unwrapFence(args.getString("find"));
        String replacement = ExactTextEdit.unwrapFence(args.getString("replace"));
        ExactTextEdit.Result result = ExactTextEdit.apply(latestContents(file), find, replacement);
        switch (result.status) {
            case EMPTY_FIND:
                return "editFile refused: find must not be empty.";
            case NOT_FOUND:
                return "editFile refused: the exact find text is not in the latest version of "
                        + relativePath(file) + ". Read the file again and copy it exactly.";
            case AMBIGUOUS:
                return "editFile refused: find occurs more than once in " + relativePath(file)
                        + ". Include more surrounding lines so it is unique.";
            case APPLIED:
            default:
                String canonical = file.getCanonicalPath();
                baseFiles.putIfAbsent(canonical, latestContents(file));
                workingFiles.put(canonical, result.text);
                if (canonical.equals(currentFilePath)) currentCode = result.text;
                return "Staged exact edit in " + relativePath(file) + " at line "
                        + lineAt(result.text, result.offset) + ". The rest of the file is unchanged.";
        }
    }

    private String executeCreateFile(JSONObject args) throws Exception {
        File file = resolveProjectFile(args.getString("path"), false);
        String canonical = file.getCanonicalPath();
        if (file.exists() || createdFiles.contains(canonical)) {
            return "createFile refused: file already exists: " + relativePath(file);
        }
        String content = ExactTextEdit.unwrapFence(args.optString("content", ""));
        createdFiles.add(canonical);
        baseFiles.put(canonical, "");
        workingFiles.put(canonical, content);
        return "Staged new file " + relativePath(file) + ". It will only be written after review.";
    }

    private String executeDeleteFile(JSONObject args) throws Exception {
        File file = resolveProjectFile(args.getString("path"), true);
        if (!file.isFile()) return "deleteFile target is not a file.";
        String canonical = file.getCanonicalPath();
        if (createdFiles.remove(canonical)) {
            workingFiles.remove(canonical);
            baseFiles.remove(canonical);
            return "Removed the newly proposed file from this change set.";
        }
        String current = latestContents(file);
        baseFiles.putIfAbsent(canonical, current);
        workingFiles.remove(canonical);
        deletedFiles.add(canonical);
        return "Staged deletion of " + relativePath(file) + ". It will only be deleted after review.";
    }

    private String executeRenameFile(JSONObject args) throws Exception {
        File source = resolveProjectFile(args.getString("oldPath"), true);
        File destination = resolveProjectFile(args.getString("newPath"), false);
        String sourceKey = source.getCanonicalPath();
        String destinationKey = destination.getCanonicalPath();
        if (sourceKey.equals(destinationKey)) return "renameFile refused: source and destination are identical.";
        if (destination.exists() || createdFiles.contains(destinationKey)) {
            return "renameFile refused: destination already exists.";
        }
        String content = latestContents(source);
        baseFiles.putIfAbsent(sourceKey, content);
        if (createdFiles.remove(sourceKey)) baseFiles.remove(sourceKey);
        else deletedFiles.add(sourceKey);
        workingFiles.remove(sourceKey);
        createdFiles.add(destinationKey);
        baseFiles.put(destinationKey, "");
        workingFiles.put(destinationKey, content);
        return "Staged rename " + relativePath(source) + " → " + relativePath(destination)
                + ". References inside source code are not changed automatically.";
    }

    private String latestContents(File file) throws IOException {
        String key = file.getCanonicalPath();
        if (deletedFiles.contains(key)) throw new IOException("file is staged for deletion");
        String staged = workingFiles.get(key);
        if (staged != null) return staged;
        String content = projectManager().readFile(file);
        baseFiles.putIfAbsent(key, content);
        workingFiles.put(key, content);
        return content;
    }

    private ProjectManager projectManager() throws IOException {
        ProjectManager manager = new ProjectManager(context);
        manager.setProjectRoot(projectRoot());
        return manager;
    }

    private File projectRoot() throws IOException {
        String configured = configuredProjectRoot.isEmpty()
                ? new AppPreferences(context).getProjectRoot() : configuredProjectRoot;
        if (configured == null || configured.trim().isEmpty()) {
            throw new IOException("no project is open");
        }
        File root = new File(configured).getCanonicalFile();
        if (!root.isDirectory()) throw new IOException("project root is unavailable");
        return root;
    }

    /** Resolves an AI-provided path strictly inside the active project. */
    private File resolveProjectFile(String rawPath, boolean requireExisting) throws IOException {
        if (rawPath == null || rawPath.trim().isEmpty()) throw new IOException("empty path");
        File root = projectRoot();
        File candidate = new File(rawPath.trim());
        if (!candidate.isAbsolute()) candidate = new File(root, rawPath.trim());
        candidate = candidate.getCanonicalFile();
        String prefix = root.getPath() + File.separator;
        if (!candidate.equals(root) && !candidate.getPath().startsWith(prefix)) {
            throw new IOException("path is outside the active project");
        }
        if (requireExisting && !candidate.exists()
                && !createdFiles.contains(candidate.getCanonicalPath())) {
            throw new IOException("file does not exist");
        }
        return candidate;
    }

    private String canonicalCurrentPath(String path) {
        if (path == null || path.trim().isEmpty()) return "";
        try {
            return resolveProjectFile(path, true).getCanonicalPath();
        } catch (IOException ignored) {
            return "";
        }
    }

    private String canonicalProjectRoot() {
        try {
            return projectRoot().getCanonicalPath();
        } catch (IOException ignored) {
            return "";
        }
    }

    private String relativePath(File file) {
        try {
            File root = projectRoot();
            String rootPath = root.getCanonicalPath();
            String path = file.getCanonicalPath();
            if (path.equals(rootPath)) return ".";
            String prefix = rootPath + File.separator;
            return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
        } catch (IOException e) {
            return file.getPath();
        }
    }

    private static boolean isSkipped(File file) {
        return file.isDirectory() && (file.getName().startsWith(".")
                || SKIPPED_DIRECTORIES.contains(file.getName()));
    }

    /** Symlinks in a project must not turn read/search/tree into an escape hatch. */
    private static boolean isInsideProject(File root, File candidate) {
        try {
            String rootPath = root.getCanonicalPath();
            String candidatePath = candidate.getCanonicalPath();
            return candidatePath.equals(rootPath)
                    || candidatePath.startsWith(rootPath + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isSearchable(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? name : name.substring(dot + 1);
        return SEARCHABLE_EXTENSIONS.contains(extension)
                || "gradlew".equals(name) || "pom.xml".equals(name);
    }

    private static int lineAt(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static String shorten(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "\n…";
    }

    private static JSONObject message(String role, JSONObject part) throws JSONException {
        return new JSONObject().put("role", role).put("parts", new JSONArray().put(part));
    }

    private boolean isCurrent(long token) {
        return !cancelled && token == turnId;
    }

    private void postToolCall(String name, String args) {
        mainHandler.post(() -> callback.onToolCall(name, args));
    }

    private void postToolResult(String name, String result) {
        mainHandler.post(() -> callback.onToolResult(name, result));
    }

    private void finishText(long token, String text) {
        if (!isCurrent(token)) return;
        List<PendingEdits.FileChange> changes = new ArrayList<>();
        for (Map.Entry<String, String> entry : workingFiles.entrySet()) {
            String path = entry.getKey();
            String base = baseFiles.get(path);
            if (base == null) continue;
            String updated = entry.getValue();
            boolean created = createdFiles.contains(path);
            if (created || !base.equals(updated)) {
                changes.add(new PendingEdits.FileChange(path, base, updated, created));
            }
        }
        for (String path : deletedFiles) {
            String base = baseFiles.get(path);
            if (base != null) changes.add(new PendingEdits.FileChange(path, base, "", false, true));
        }
        if (!changes.isEmpty()) {
            PendingEdits.AgentChangeSet changeSet = new PendingEdits.AgentChangeSet(
                    workingProjectRoot, changes);
            mainHandler.post(() -> { if (isCurrent(token)) callback.onChangeSetReady(changeSet); });
        }
        mainHandler.post(() -> {
            if (!isCurrent(token)) return;
            callback.onTextResponse(text);
            callback.onDone();
        });
    }

    private void finishError(long token, String error) {
        if (!isCurrent(token)) return;
        mainHandler.post(() -> {
            if (!isCurrent(token)) return;
            callback.onError(error);
            callback.onDone();
        });
    }

    public synchronized void cancel() {
        cancelled = true;
        turnId++;
        agentExecutor.shutdownNow();
    }
}
