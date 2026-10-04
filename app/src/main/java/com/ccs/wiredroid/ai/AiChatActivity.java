package com.ccs.wiredroid.ai;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.FullScreenHelper;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.ui.CodeBlockView;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.CheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AiChatActivity extends AppCompatActivity {

    private static final String TAG = "AiChat";
    private static final String EXTRA_CODE = "code";
    private static final String EXTRA_FILE_NAME = "file_name";
    private static final String EXTRA_FILE_PATH = "file_path";
    private static final String EXTRA_PROJECT_ROOT = "project_root";
    private static final String EXTRA_INITIAL_PROMPT = "initial_prompt";

    private LinearLayout messagesContainer;
    private EditText etInput;
    private ScrollView scrollChat;
    private TextView tvModelLabel;
    private String codeContext = "";
    private String fileName = "";
    private String filePath = "";
    private String projectRoot = "";
    private boolean agentMode = false;
    private boolean planOnlyMode = false;
    private GeminiAgent agent;
    private PendingEdits.AgentChangeSet pendingChangeSet;
    private TextView btnSendRef;

    /**
     * The conversation, as the model sees it.
     *
     * <p>Every request used to pass null here while the plumbing to send history
     * already existed, so each message stood alone: "now rewrite that with
     * streams" had nothing to refer to. The open file is deliberately *not* in
     * this list — it goes in the system prompt, once per request, so it does not
     * accumulate a fresh copy of the whole file for every turn.</p>
     */
    private final java.util.ArrayList<GeminiService.ChatMessage> history = new java.util.ArrayList<>();

    /**
     * Roughly how much conversation to carry. Characters rather than tokens
     * because the exact tokeniser is Google's; four characters to a token is the
     * usual rule of thumb, so this is about 6k tokens of history — generous next
     * to the file context and far from the model's limit.
     */
    private static final int HISTORY_BUDGET_CHARS = 24_000;

    /** True while a request is in flight; a second send would corrupt the view. */
    private boolean awaitingReply = false;

    private ChatHistoryStore historyStore;
    /** The conversation being written to, or -1 before one is opened. */
    private long conversationId = -1;
    private static final int REQ_PICK_CONVERSATION = 4021;
    /** What the user asked the agent, kept until its reply arrives to pair with. */
    private String pendingAgentPrompt = "";

    private int accentColor = 0xFF4A86C8;
    private int bgColor = 0xFF1E1E1E;
    private int toolbarColor = 0xFF3C3F41;
    private int textColor = 0xFFBBBBBB;
    private int dimColor = 0xFF808080;
    private int greenColor = 0xFF499C54;
    private int errorColor = 0xFFFF6B6B;
    private static final int COLOR_BOLD = 0xFFFFFFFF;
    private static final int COLOR_HEADER = 0xFF4A86C8;

    public static void launch(Context context, String code, String fileName, String projectRoot) {
        launchWithPrompt(context, code, fileName, "", projectRoot, null);
    }

    public static void launchWithPrompt(Context context, String code, String fileName, String projectRoot, String prompt) {
        launchWithPrompt(context, code, fileName, "", projectRoot, prompt);
    }

    public static void launch(Context context, String code, String fileName, String filePath,
                              String projectRoot) {
        launchWithPrompt(context, code, fileName, filePath, projectRoot, null);
    }

    public static void launchWithPrompt(Context context, String code, String fileName,
                                        String filePath, String projectRoot, String prompt) {
        Intent i = new Intent(context, AiChatActivity.class);
        i.putExtra(EXTRA_CODE, code != null ? code : "");
        i.putExtra(EXTRA_FILE_NAME, fileName != null ? fileName : "");
        i.putExtra(EXTRA_FILE_PATH, filePath != null ? filePath : "");
        i.putExtra(EXTRA_PROJECT_ROOT, projectRoot != null ? projectRoot : "");
        if (prompt != null) i.putExtra(EXTRA_INITIAL_PROMPT, prompt);
        context.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppPreferences prefs = new AppPreferences(this);
        AppTheme appTheme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(appTheme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        FullScreenHelper.enable(this);
        applyColors();

        codeContext = getIntent().getStringExtra(EXTRA_CODE);
        if (codeContext == null) codeContext = "";
        fileName = getIntent().getStringExtra(EXTRA_FILE_NAME);
        if (fileName == null) fileName = "";
        filePath = getIntent().getStringExtra(EXTRA_FILE_PATH);
        if (filePath == null) filePath = "";
        projectRoot = getIntent().getStringExtra(EXTRA_PROJECT_ROOT);
        if (projectRoot == null) projectRoot = "";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);

        // ── Toolbar ──
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setBackgroundColor(toolbarColor);
        toolbar.setPadding(dp(8), dp(8), dp(12), dp(8));
        toolbar.setGravity(Gravity.CENTER_VERTICAL);

        TextView btnBack = new TextView(this);
        btnBack.setText("\u2190");
        btnBack.setTextColor(textColor);
        btnBack.setTextSize(18);
        btnBack.setPadding(dp(8), dp(4), dp(8), dp(4));
        btnBack.setContentDescription(getString(R.string.a11y_ai_back));
        btnBack.setOnClickListener(v -> finish());
        toolbar.addView(btnBack);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(R.string.ai_title);
        tvTitle.setTextColor(textColor);
        tvTitle.setTextSize(16);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tvTitle.setLayoutParams(titleLp);
        toolbar.addView(tvTitle);

        tvModelLabel = new TextView(this);
        // Filled in by loadAiSettings() — the model name lives in a prefs file
        // that is not open yet, and blocking on it here would hold the frame.
        tvModelLabel.setTextColor(dimColor);
        tvModelLabel.setTextSize(11);
        tvModelLabel.setPadding(dp(8), dp(4), dp(8), dp(4));
        tvModelLabel.setContentDescription(getString(R.string.a11y_ai_model));
        tvModelLabel.setOnClickListener(v -> showModelDialog());
        toolbar.addView(tvModelLabel);

        TextView btnActions = new TextView(this);
        btnActions.setText("\u22EE");
        btnActions.setTextColor(accentColor);
        btnActions.setTextSize(18);
        btnActions.setPadding(dp(10), dp(2), dp(10), dp(4));
        btnActions.setContentDescription(getString(R.string.ai_actions_title));
        btnActions.setOnClickListener(this::showActionsMenu);
        toolbar.addView(btnActions);

        TextView btnApiKey = new TextView(this);
        btnApiKey.setText(R.string.ai_key_button);
        btnApiKey.setTextColor(accentColor);
        btnApiKey.setTextSize(11);
        btnApiKey.setPadding(dp(8), dp(4), dp(8), dp(4));
        btnApiKey.setContentDescription(getString(R.string.a11y_ai_api_key));
        btnApiKey.setOnClickListener(v -> showApiKeyDialog());
        toolbar.addView(btnApiKey);

        root.addView(toolbar);

        // ── Session bar ──
        //
        // The quick actions used to live here as seven chips that overflowed the
        // width and pushed the two things people reach for constantly — starting
        // a fresh chat and finding an old one — off the screen entirely. The
        // actions are a menu now; this row is for the session.
        LinearLayout sessionRow = new LinearLayout(this);
        sessionRow.setOrientation(LinearLayout.HORIZONTAL);
        sessionRow.setBackgroundColor(toolbarColor);
        sessionRow.setPadding(dp(6), dp(4), dp(6), dp(6));
        sessionRow.setGravity(Gravity.CENTER_VERTICAL);

        sessionRow.addView(sessionButton("\uFF0B  " + getString(R.string.ai_new_chat),
                getString(R.string.ai_new_chat), v -> startNewConversation()));
        sessionRow.addView(sessionButton("\u21BA  " + getString(R.string.ai_history_title),
                getString(R.string.ai_history_title),
                v -> ChatHistoryActivity.pick(this, REQ_PICK_CONVERSATION)));

        root.addView(sessionRow);

        // ── Chat area ──
        scrollChat = new ScrollView(this);
        scrollChat.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        scrollChat.setBackgroundColor(bgColor);

        messagesContainer = new LinearLayout(this);
        messagesContainer.setOrientation(LinearLayout.VERTICAL);
        messagesContainer.setPadding(dp(8), dp(8), dp(8), dp(8));
        messagesContainer.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scrollChat.addView(messagesContainer);
        root.addView(scrollChat);

        // ── Input ──
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setBackgroundColor(toolbarColor);
        inputRow.setPadding(dp(8), dp(4), dp(8), dp(4));

        etInput = new EditText(this);
        etInput.setHint(R.string.ai_input_hint);
        etInput.setHintTextColor(dimColor);
        etInput.setTextColor(textColor);
        etInput.setBackgroundColor(bgColor);
        etInput.setTextSize(14);
        etInput.setMaxLines(5);
        etInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        etInput.setContentDescription(getString(R.string.a11y_ai_input));
        etInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView btnSend = new TextView(this);
        btnSend.setText("➤");
        btnSend.setTextColor(accentColor);
        btnSend.setTextSize(18);
        btnSend.setPadding(dp(12), dp(8), dp(12), dp(8));
        btnSend.setGravity(Gravity.CENTER);
        btnSend.setContentDescription(getString(R.string.a11y_ai_send));
        btnSend.setBackgroundResource(android.R.drawable.list_selector_background);

        btnSendRef = btnSend;

        inputRow.addView(etInput);
        inputRow.addView(btnSend);
        root.addView(inputRow);

        setContentView(root);

        // Reopens the last conversation if there is one, so leaving the screen no
        // longer throws the chat away. Before the welcome text, which would
        // otherwise sit above a restored conversation as if it started it.
        openInitialConversation();

        if (history.isEmpty()) {
            if (!codeContext.isEmpty()) {
                addText("System", getString(R.string.ai_loaded_file, fileName, codeContext.length()));
            } else {
                addText("System", getString(R.string.ai_ready));
            }
        }

        btnSend.setOnClickListener(v -> {
            if (awaitingReply && agentMode && agent != null) stopAgentTurn();
            else sendMessage();
        });

        String initialPrompt = getIntent().getStringExtra(EXTRA_INITIAL_PROMPT);
        if (initialPrompt != null && !initialPrompt.isEmpty()) {
            etInput.setText(initialPrompt);
            etInput.setSelection(initialPrompt.length());
        }

        loadAiSettings();

    }

    /**
     * Reads the model name and checks for a stored key away from the UI thread:
     * the key sits in the Android keystore, so answering "is one set?" means a
     * round trip to the keystore daemon plus a decrypt.
     */
    private void loadAiSettings() {
        new Thread(() -> {
            final String modelName = getModelDisplayName();
            final boolean hasKey = GeminiService.hasApiKey(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                tvModelLabel.setText(modelName);
                if (!hasKey) {
                    addText("System", getString(R.string.ai_key_not_set));
                    showApiKeyDialog();
                }
            });
        }, "ai-chat-settings").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        FullScreenHelper.enable(this);
    }

    // ── Text ──────────────────────────────────────────────────

    private void addText(String sender, String text) {
        boolean isSystem = "System".equals(sender);
        boolean isAI = "AI".equals(sender);

        // Заголовок
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setPadding(dp(8), dp(6), dp(8), dp(2));

        TextView tvLabel = new TextView(this);
        tvLabel.setText((isSystem ? "--- " : isAI ? ">>> " : "<<< ") + sender);
        tvLabel.setTextColor(isSystem ? dimColor : isAI ? greenColor : accentColor);
        tvLabel.setTextSize(12);
        tvLabel.setTypeface(tvLabel.getTypeface(), Typeface.BOLD);
        headerRow.addView(tvLabel);
        messagesContainer.addView(headerRow);

        // Контент
        if (isAI && text.contains("```")) {
            // AI-відповідь з кодовими блоками — парсимо на сегменти
            parseAndAddMarkdown(text);
        } else {
            // Простий текст
            TextView tvBody = new TextView(this);
            tvBody.setText(text);
            tvBody.setTextColor(textColor);
            tvBody.setTextSize(14);
            tvBody.setPadding(dp(8), dp(2), dp(8), dp(6));
            tvBody.setLineSpacing(0, 1.3f);
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bodyLp.bottomMargin = dp(4);
            tvBody.setLayoutParams(bodyLp);
            messagesContainer.addView(tvBody);

            // Кнопка вставки для текстової відповіді AI (весь текст → на курсор)
            if (isAI && !agentMode && !text.trim().isEmpty()) {
                addInsertButton(text, PendingEdits.LOCATION_CURSOR);
            }
        }

        scrollChat.post(() -> scrollChat.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * Додає рядок із кнопкою «Вставити в редактор» під кодом.
     * При натисканні планує вставку через PendingEdits і показує toast.
     * Location: "cursor" | "append" | "replace" (за замовч. cursor).
     */
    private void addInsertButton(String code, String location) {
        String finalCode = code == null ? "" : code;
        if (finalCode.trim().isEmpty()) return;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(2), dp(8), dp(8));

        TextView btn = new TextView(this);
        btn.setText(R.string.ai_insert_into_editor);
        btn.setTextColor(accentColor);
        btn.setTextSize(12);
        btn.setTypeface(btn.getTypeface(), Typeface.BOLD);
        btn.setPadding(dp(12), dp(6), dp(12), dp(6));
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setContentDescription(getString(R.string.ai_insert_desc));

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btn.setLayoutParams(btnLp);

        btn.setOnClickListener(v -> {
            String loc = location == null ? PendingEdits.LOCATION_CURSOR : location;
            PendingEdits.add(finalCode, loc);
            String label = PendingEdits.LOCATION_REPLACE.equals(loc) ? "Replace"
                    : PendingEdits.LOCATION_APPEND.equals(loc) ? "Append" : "Cursor";
            Toast.makeText(this, getString(R.string.ai_queued, label),
                    Toast.LENGTH_SHORT).show();
        });

        row.addView(btn);
        messagesContainer.addView(row);
    }

    /**
     * Marks where the "Thinking…" placeholder starts, so it can be removed whole.
     *
     * <p>Removing a fixed two views assumed the placeholder was always a header
     * and one body. It is not: addText renders through the markdown parser, which
     * can emit several views, so the header was left behind and every answer
     * appeared under an empty ">>> AI". Remembering the size beforehand does not
     * care how many views the renderer chose to add.</p>
     */
    private int pendingPlaceholderAt = -1;

    private void addPlaceholder(String sender, String text) {
        pendingPlaceholderAt = messagesContainer.getChildCount();
        addText(sender, text);
    }

    private void removeLastLine() {
        if (pendingPlaceholderAt < 0
                || pendingPlaceholderAt > messagesContainer.getChildCount()) {
            pendingPlaceholderAt = -1;
            return;
        }
        while (messagesContainer.getChildCount() > pendingPlaceholderAt) {
            messagesContainer.removeViewAt(messagesContainer.getChildCount() - 1);
        }
        pendingPlaceholderAt = -1;
    }

    /** Shows a concise native function call instead of dumping source-code JSON. */
    private void renderToolCall(String toolName, String args) {
        if (("editFile".equals(toolName) || "createFile".equals(toolName))
                && args != null && !args.isEmpty()) {
            try {
                org.json.JSONObject obj = new org.json.JSONObject(args);
                addText("🔧 Tool", toolName + "(" + obj.optString("path", "") + ")");
            } catch (org.json.JSONException e) {
                addText("🔧 Tool", toolName + "(" + args + ")");
            }
            return;
        }

        addText("🔧 Tool", toolName + "(" + args + ")");
    }

    // ── Markdown parser з CodeBlockView ───────────────────────

    private static final Pattern P_CODE_BLOCK = Pattern.compile("```(\\w*)\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern P_BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern P_HEADER = Pattern.compile("^#{1,3}\\s+(.+)$", Pattern.MULTILINE);

    private void parseAndAddMarkdown(String text) {
        Matcher m = P_CODE_BLOCK.matcher(text);
        int lastEnd = 0;

        while (m.find()) {
            // Текст перед кодовим блоком
            if (m.start() > lastEnd) {
                String before = text.substring(lastEnd, m.start());
                addFormattedTextView(before);
            }

            // Кодовий блок з обводкою та кнопкою Copy
            String lang = m.group(1);
            String code = m.group(2);
            if (code != null && code.endsWith("\n")) code = code.substring(0, code.length() - 1);

            CodeBlockView codeView = new CodeBlockView(this, code, lang);

            // Підсвітка
            SpannableStringBuilder highlighted = ChatFormatter.format("```" + (lang != null ? lang : "") + "\n" + code + "\n```", textColor);
            codeView.setHighlighted(highlighted);

            LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            codeLp.setMargins(dp(8), dp(4), dp(8), dp(4));
            codeView.setLayoutParams(codeLp);
            messagesContainer.addView(codeView);

            // Кнопка вставки під кожен кодовий блок (на курсор)
            if (!agentMode && code != null && !code.trim().isEmpty()) {
                addInsertButton(code, PendingEdits.LOCATION_CURSOR);
            }

            lastEnd = m.end();
        }

        // Текст після останнього кодового блоку
        if (lastEnd < text.length()) {
            addFormattedTextView(text.substring(lastEnd));
        }
    }

    private void addFormattedTextView(String text) {
        if (text.trim().isEmpty()) return;

        // Headers
        Matcher hm = P_HEADER.matcher(text);
        int last = 0;
        while (hm.find()) {
            if (hm.start() > last) addPlainTextView(text.substring(last, hm.start()));
            TextView tv = new TextView(this);
            tv.setText(hm.group(1));
            tv.setTextColor(COLOR_HEADER);
            tv.setTextSize(15);
            tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
            tv.setPadding(dp(8), dp(6), dp(8), dp(2));
            messagesContainer.addView(tv);
            last = hm.end();
        }
        if (last < text.length()) {
            addPlainTextView(text.substring(last));
        }
    }

    private void addPlainTextView(String text) {
        if (text.trim().isEmpty()) return;

        // Bold
        Matcher bm = P_BOLD.matcher(text);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int last = 0;
        while (bm.find()) {
            if (bm.start() > last) sb.append(text, last, bm.start());
            int s = sb.length();
            sb.append(bm.group(1));
            sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), s, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(COLOR_BOLD), s, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            last = bm.end();
        }
        if (last < text.length()) sb.append(text, last, text.length());

        TextView tv = new TextView(this);
        tv.setText(sb);
        tv.setTextColor(textColor);
        tv.setTextSize(14);
        tv.setPadding(dp(8), dp(2), dp(8), dp(4));
        tv.setLineSpacing(0, 1.3f);
        messagesContainer.addView(tv);
    }

    // ── Send ──────────────────────────────────────────────────

    private void sendMessage() {
        String input = etInput.getText().toString().trim();
        // Never the prompt text itself: it is the user's question and, with the
        // file context, their source.
        if (com.ccs.wiredroid.BuildConfig.DEBUG) Log.d(TAG, "send " + input.length() + " chars");
        if (input.isEmpty()) {
            return;
        }
        if (awaitingReply) {
            // Two requests in flight write two "Thinking…" lines and only one is
            // ever removed, so the view drifts out of step with the conversation.
            return;
        }
        if (!GeminiService.hasApiKey(this)) {
            addText("System", getString(R.string.ai_key_needed_first));
            showApiKeyDialog();
            return;
        }

        etInput.setText("");
        addText("You", input);
        addPlaceholder("AI", getString(R.string.ai_loading));

        if (agentMode) {
            // Agent mode - AI can execute tools
            if (agent == null) {
                agent = new GeminiAgent(this, new GeminiAgent.AgentCallback() {
                    @Override
                    public void onToolCall(String toolName, String args) {
                        // The first concrete agent step replaces the loading row;
                        // subsequent tool calls/results stay visible in the log.
                        removeLastLine();
                        renderToolCall(toolName, args);
                    }

                    @Override
                    public void onToolResult(String toolName, String result) {
                        addText("✅ " + toolName, compactToolResult(toolName, result));
                    }

                    @Override
                    public void onTextResponse(String text) {
                        setAwaitingReply(false);
                        removeLastLine();
                        addText("AI", text);
                        // Agent turns went through their own callbacks and never
                        // reached the history or the database, so an agent
                        // session vanished on leaving the screen and the model
                        // could not refer to what it had just done.
                        history.add(new GeminiService.ChatMessage(pendingAgentPrompt, true));
                        history.add(new GeminiService.ChatMessage(text, false));
                        historyStore.addMessage(conversationId, true, pendingAgentPrompt);
                        historyStore.addMessage(conversationId, false, text);
                        trimHistory();
                        showChangeSetReview();
                    }

                    @Override
                    public void onError(String error) {
                        setAwaitingReply(false);
                        removeLastLine();
                        addText("AI", error);
                    }

                    @Override
                    public void onDone() {
                        // Agent finished
                    }

                    @Override public void onChangeSetReady(PendingEdits.AgentChangeSet changeSet) {
                        pendingChangeSet = changeSet;
                    }
                });
            }

            // The same history the plain chat keeps, so the agent remembers the
            // edits it already made in this conversation.
            pendingAgentPrompt = input;
            setAwaitingReply(true);
            agent.setPlanOnly(planOnlyMode);
            agent.send(input, codeContext, fileName, filePath, projectRoot, historyWindow());
        } else {
            askModel(input);
        }
    }

    private String compactToolResult(String toolName, String result) {
        if (result == null) return "";
        if ("readFile".equals(toolName) || "getCurrentFile".equals(toolName)) {
            String[] lines = result.split("\\n", -1);
            String heading = lines.length == 0 ? toolName : lines[0];
            return heading + "\nFile contents are available to the agent; hidden here to keep the chat readable."
                    + " (" + result.length() + " characters)";
        }
        return result.length() > 1200 ? result.substring(0, 1200) + "\n… (truncated)" : result;
    }

    private void showChangeSetReview() {
        PendingEdits.AgentChangeSet changeSet = pendingChangeSet;
        if (changeSet == null || changeSet.isEmpty() || isFinishing()) return;
        String preview = buildChangePreview(changeSet);
        TextView body = new TextView(this);
        body.setText(preview);
        body.setTextColor(textColor);
        body.setTextSize(12);
        body.setTypeface(Typeface.MONOSPACE);
        body.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout reviewContent = new LinearLayout(this);
        reviewContent.setOrientation(LinearLayout.VERTICAL);
        CheckBox[] selected = new CheckBox[changeSet.changes.size()];
        for (int i = 0; i < changeSet.changes.size(); i++) {
            PendingEdits.FileChange change = changeSet.changes.get(i);
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText((change.created ? "Create: " : change.deleted ? "Delete: " : "Edit: ")
                    + change.path);
            checkBox.setTextColor(textColor);
            checkBox.setChecked(true);
            selected[i] = checkBox;
            reviewContent.addView(checkBox);
        }
        reviewContent.addView(body);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(reviewContent);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Review " + changeSet.changes.size() + " file change(s)")
                .setView(scroll)
                .setPositiveButton("Apply selected", (dialog, which) -> {
                    pendingChangeSet = null;
                    java.util.ArrayList<PendingEdits.FileChange> chosen = new java.util.ArrayList<>();
                    for (int i = 0; i < selected.length; i++) {
                        if (selected[i].isChecked()) chosen.add(changeSet.changes.get(i));
                    }
                    if (chosen.isEmpty()) {
                        Toast.makeText(this, "No files selected", Toast.LENGTH_SHORT).show();
                    } else {
                        PendingEdits.stageChangeSet(this,
                                new PendingEdits.AgentChangeSet(changeSet.projectRoot, chosen));
                        Toast.makeText(this, "Applying reviewed changes…", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                })
                .setNegativeButton("Reject", (dialog, which) -> {
                    pendingChangeSet = null;
                    PendingEdits.discardChangeSet(this);
                })
                .setNeutralButton("Keep chatting", null)
                .show();
    }

    private String buildChangePreview(PendingEdits.AgentChangeSet changeSet) {
        StringBuilder out = new StringBuilder();
        for (PendingEdits.FileChange change : changeSet.changes) {
            out.append(change.created ? "NEW " : change.deleted ? "DELETE " : "EDIT ")
                    .append(change.path).append('\n');
            String[] before = change.created ? new String[0] : change.base.split("\\n", -1);
            String[] after = change.deleted ? new String[0] : change.content.split("\\n", -1);
            int prefix = 0;
            while (prefix < before.length && prefix < after.length
                    && before[prefix].equals(after[prefix])) prefix++;
            int suffix = 0;
            while (suffix < before.length - prefix && suffix < after.length - prefix
                    && before[before.length - 1 - suffix].equals(after[after.length - 1 - suffix])) suffix++;
            int oldEnd = before.length - suffix;
            int newEnd = after.length - suffix;
            int from = Math.max(0, prefix - 2);
            int oldTo = Math.min(before.length, oldEnd + 2);
            int newTo = Math.min(after.length, newEnd + 2);
            for (int i = from; i < prefix; i++) out.append("  ").append(before[i]).append('\n');
            for (int i = prefix; i < oldEnd; i++) out.append("- ").append(before[i]).append('\n');
            for (int i = prefix; i < newEnd; i++) out.append("+ ").append(after[i]).append('\n');
            for (int i = oldEnd; i < oldTo; i++) out.append("  ").append(before[i]).append('\n');
            if (oldTo == before.length && newTo < after.length) out.append("…\n");
            out.append('\n');
        }
        return out.toString();
    }

    private void stopAgentTurn() {
        if (agent != null) agent.cancel();
        agent = null;
        setAwaitingReply(false);
        removeLastLine();
        addText("System", "Agent stopped. No proposed changes were applied.");
    }

    /**
     * The one place a chat request is made.
     *
     * <p>Both the input box and the quick-action chips came through here already
     * in spirit — each had its own copy of the same callback, and the two had
     * begun to differ. One path also means the in-flight flag and the history
     * cannot be updated in one place and forgotten in the other.</p>
     */
    private void askModel(String userText) {
        setAwaitingReply(true);
        GeminiService.chat(this, buildSystemPrompt(), userText, historyWindow(),
                new GeminiService.ResponseCallback() {
                    @Override public void onSuccess(String response) {
                        setAwaitingReply(false);
                        removeLastLine();
                        addText("AI", response);
                        history.add(new GeminiService.ChatMessage(userText, true));
                        history.add(new GeminiService.ChatMessage(response, false));
                        // Written only once the pair is complete, so a conversation
                        // reopened later never begins with a question that was
                        // never answered.
                        historyStore.addMessage(conversationId, true, userText);
                        historyStore.addMessage(conversationId, false, response);
                        trimHistory();
                    }

                    @Override public void onError(String error) {
                        setAwaitingReply(false);
                        removeLastLine();
                        addText("AI", error);
                        // The failed exchange is not remembered: replaying a turn
                        // the model never answered would make the next reply argue
                        // with a message that produced nothing.
                    }
                });
    }

    /** History as the request should see it, oldest first. */
    private java.util.List<GeminiService.ChatMessage> historyWindow() {
        return history.isEmpty() ? null : new java.util.ArrayList<>(history);
    }

    /** Drops the oldest turns once the conversation outgrows the budget. */
    private void trimHistory() {
        int total = 0;
        for (GeminiService.ChatMessage m : history) total += m.getText().length();
        // In pairs, so the list never starts with a model reply to a question
        // that is no longer there.
        while (total > HISTORY_BUDGET_CHARS && history.size() >= 2) {
            total -= history.remove(0).getText().length();
            total -= history.remove(0).getText().length();
        }
    }

    /** Blocks a second send while one is running, and shows that it is running. */
    private void setAwaitingReply(boolean waiting) {
        awaitingReply = waiting;
        if (btnSendRef != null) {
            btnSendRef.setText(waiting && agentMode ? "■" : "➤");
            btnSendRef.setContentDescription(waiting && agentMode ? "Stop agent" : "Send message");
            btnSendRef.setEnabled(true);
            btnSendRef.setAlpha(1f);
        }
    }

    // ── Quick actions ─────────────────────────────────────────

    /**
     * The actions, keyed by something stable.
     *
     * <p>They used to be dispatched by switching on the visible English label,
     * which quietly made the feature untranslatable: the moment "Explain" became
     * "Пояснити" the switch fell through to its default and sent the label itself
     * as the prompt. The id is what the code matches on; the label is only ever
     * shown.</p>
     */
    private enum QuickAction {
        EXPLAIN(R.string.ai_action_explain,
                "Explain this code in detail. What does it do? How does it work?"),
        FIND_BUGS(R.string.ai_action_find_bugs,
                "Find bugs, potential issues, and code smells. For each issue explain the "
                        + "problem and provide a fix."),
        REFACTOR(R.string.ai_action_refactor,
                "Refactor this code to be cleaner and follow best practices. Show the "
                        + "improved version."),
        OPTIMIZE(R.string.ai_action_optimize,
                "Optimize this code for better performance. Show the optimized version."),
        DOCUMENT(R.string.ai_action_document,
                "Add JavaDoc documentation to this code. Include class, method, parameter, "
                        + "and return docs."),
        TEST(R.string.ai_action_test,
                "Write JUnit tests for this code. Cover edge cases and normal cases.");

        final int labelRes;
        final String prompt;

        QuickAction(int labelRes, String prompt) {
            this.labelRes = labelRes;
            this.prompt = prompt;
        }
    }

    /**
     * The actions menu, anchored to the overflow button.
     *
     * <p>A Material 3 popup rather than a row of chips: there are seven of them,
     * they are worded differently in every language, and a horizontal row silently
     * pushed the later ones off the edge of a phone.</p>
     */
    private void showActionsMenu(View anchor) {
        androidx.appcompat.widget.PopupMenu menu =
                new androidx.appcompat.widget.PopupMenu(this, anchor);
        QuickAction[] actions = QuickAction.values();
        for (int i = 0; i < actions.length; i++) {
            menu.getMenu().add(0, i, i, getString(actions[i].labelRes));
        }
        int agentId = actions.length;
        menu.getMenu().add(0, agentId, agentId, getString(R.string.ai_action_agent))
                .setCheckable(true).setChecked(agentMode);
        int planId = agentId + 1;
        menu.getMenu().add(0, planId, planId, "Plan only (read-only)")
                .setCheckable(true).setChecked(planOnlyMode);

        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == agentId) {
                agentMode = !agentMode;
                Toast.makeText(this,
                        agentMode ? R.string.ai_agent_on : R.string.ai_agent_off,
                        Toast.LENGTH_SHORT).show();
                return true;
            }
            if (item.getItemId() == planId) {
                planOnlyMode = !planOnlyMode;
                if (planOnlyMode) agentMode = true;
                Toast.makeText(this, planOnlyMode
                        ? "Plan only: the agent can inspect but not edit files"
                        : "Plan only disabled", Toast.LENGTH_SHORT).show();
                return true;
            }
            performQuickAction(actions[item.getItemId()]);
            return true;
        });
        menu.show();
    }

    private void performQuickAction(QuickAction action) {
        if (codeContext.isEmpty()) {
            addText("System", getString(R.string.ai_need_file));
            return;
        }
        if (!GeminiService.hasApiKey(this)) {
            showApiKeyDialog();
            return;
        }
        if (awaitingReply) return;

        addText("You", "[" + getString(action.labelRes) + "]");
        addPlaceholder("AI", getString(R.string.ai_loading));

        // No file body pasted in front of the prompt: buildSystemPrompt() carries
        // the file, once, so a long conversation does not resend it every turn.
        askModel(action.prompt);
    }

    // ── Helpers ───────────────────────────────────────────────

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder(
                "You are an expert Java programming assistant inside WireDroid Android IDE. "
                + "Help the user with their code. Be concise and practical. "
                + "When showing code, use java code blocks. "
                + "When finding bugs, explain the issue and provide the fix. "
                + "When refactoring, show the improved code. "
                + "Keep responses focused and actionable.");
        // The open file belongs here rather than glued to each message: the
        // system prompt is sent once per request and never enters the history,
        // so a ten-turn conversation carries one copy of the file instead of ten.
        if (!codeContext.isEmpty()) {
            sb.append("\n\nThe user is looking at ").append(fileName).append(":\n```java\n")
              .append(codeContext).append("\n```");
        }
        return sb.toString();
    }

    private void showModelDialog() {
        // The list Google returned for this key when it was verified, falling
        // back to the built-in one. A hardcoded list goes out of date, and a
        // model that no longer exists fails every message.
        final String[] ids = GeminiService.models(this);
        final String[] names = GeminiService.modelDisplayNames(this);
        String current = GeminiService.getSelectedModel(this);
        int checked = 0;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equals(current)) { checked = i; break; }
        }
        com.ccs.wiredroid.ui.Dialogs.rounded(this)
                .setTitle(R.string.ai_model_select)
                .setSingleChoiceItems(names, checked, (d, w) -> {
                    GeminiService.setSelectedModel(this, ids[w]);
                    tvModelLabel.setText(names[w]);
                    addText("System", getString(R.string.ai_model_changed, names[w]));
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showApiKeyDialog() {
        EditText input = new EditText(this);
        input.setHint(R.string.ai_api_key_hint);
        input.setHintTextColor(dimColor);
        input.setTextColor(textColor);
        input.setPadding(48, 24, 48, 24);
        input.setSingleLine(true);
        String saved = GeminiService.getApiKey(this);
        if (saved != null && !saved.isEmpty()) input.setText(saved);

        com.ccs.wiredroid.ui.Dialogs.rounded(this)
                .setTitle(R.string.ai_api_key_title)
                .setMessage(R.string.ai_api_key_message)
                .setView(input)
                .setPositiveButton(R.string.ai_key_save_verify, (d, w) -> {
                    String key = GeminiService.sanitizeApiKey(input.getText().toString());
                    if (key.isEmpty()) return;
                    String problem = GeminiService.describeKeyProblem(this, key);
                    if (problem != null) {
                        // Offered, never refused. Only shapes that cannot be a key
                        // reach here, and even then Google owns the format — being
                        // told "this cannot work" while holding a key that does
                        // would be worse than the silent failure this replaces.
                        com.ccs.wiredroid.ui.Dialogs.rounded(this)
                                .setTitle(R.string.ai_key_check_title)
                                .setMessage(problem)
                                .setPositiveButton(R.string.ai_key_save_anyway, (d2, w2) -> storeApiKey(key))
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                        return;
                    }
                    storeApiKey(key);
                })
                .setNeutralButton(R.string.ai_key_get, (d, w) -> {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://aistudio.google.com/apikey"));
                    startActivity(browserIntent);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Saves the key and says what actually happened.
     *
     * <p>Storing can fail — the keystore may be unavailable — and the old code
     * ignored that and reported success anyway, which is how a key could be
     * "saved" and then not be there. Nothing is claimed until it is true, and
     * the key is then checked against the API so a wrong one is named here
     * rather than surfacing later as an opaque error mid-conversation.</p>
     */
    private void storeApiKey(String key) {
        if (!GeminiService.setApiKey(this, key)) {
            com.ccs.wiredroid.ui.Dialogs.rounded(this)
                    .setTitle(R.string.ai_key_save_failed_title)
                    .setMessage(R.string.ai_key_save_failed_message)
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        tvModelLabel.setText(getModelDisplayName());
        addText("System", getString(R.string.ai_key_saved_checking));
        GeminiService.verifyApiKey(this, key, new GeminiService.ResponseCallback() {
            @Override
            public void onSuccess(String response) {
                // Said after verification, because that is when the live model
                // list arrives and the selection may have moved onto it.
                addText("System", getString(R.string.ai_key_verified, getModelDisplayName()));
                tvModelLabel.setText(getModelDisplayName());
            }

            @Override
            public void onError(String error) {
                // The key stays stored: it may be the network that failed, and
                // discarding a key the user just pasted would be its own bug.
                addText("System", getString(R.string.ai_key_saved_unverified, error));
            }
        });
    }

    // ── Saved conversations ───────────────────────────────────

    /**
     * Opens the conversation this screen writes to.
     *
     * <p>The most recent one is reopened rather than started fresh: closing the
     * screen used to lose everything, and the common case for reopening it is
     * carrying on. A brand new conversation is one tap away.</p>
     */
    private void openInitialConversation() {
        historyStore = new ChatHistoryStore(this);
        long recent = historyStore.mostRecentNonEmpty();
        if (recent > 0) {
            loadConversation(recent, false);
        } else {
            conversationId = historyStore.startConversation();
        }
    }

    /** Replaces what is on screen and in the model's history with a saved chat. */
    private void loadConversation(long id, boolean announce) {
        // An untouched conversation left behind would clutter the list with
        // entries that hold nothing.
        historyStore.discardIfEmpty(conversationId);

        conversationId = id;
        history.clear();
        history.addAll(historyStore.loadMessages(id));
        trimHistory();

        messagesContainer.removeAllViews();
        pendingPlaceholderAt = -1;
        for (GeminiService.ChatMessage m : historyStore.loadMessages(id)) {
            addText(m.isFromUser() ? "You" : "AI", m.getText());
        }
        if (announce) {
            addText("System", getString(R.string.ai_history_restored, history.size()));
        }
    }

    /** Saves the current conversation and starts an empty one. */
    private void startNewConversation() {
        if (awaitingReply) return;
        historyStore.discardIfEmpty(conversationId);
        conversationId = historyStore.startConversation();
        history.clear();
        messagesContainer.removeAllViews();
        pendingPlaceholderAt = -1;
        if (!codeContext.isEmpty()) {
            addText("System", getString(R.string.ai_loaded_file_short, fileName, codeContext.length()));
        }
        addText("System", getString(R.string.ai_new_chat_started));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_CONVERSATION && resultCode == RESULT_OK && data != null) {
            long id = data.getLongExtra(ChatHistoryActivity.EXTRA_CONVERSATION_ID, -1);
            if (id > 0) loadConversation(id, true);
        }
    }

    private String getModelDisplayName() {
        return GeminiService.displayNameOf(GeminiService.getSelectedModel(this));
    }

    private void applyColors() {
        try {
            AppPreferences prefs = new AppPreferences(this);
            AppTheme theme = AppTheme.byId(prefs.getThemeId(), prefs);
            accentColor = theme.accent;
            bgColor = theme.bg;
            toolbarColor = theme.toolbar;
            textColor = theme.text;
            dimColor = theme.textDim;
            greenColor = theme.successText;
            errorColor = theme.errorText;
        } catch (Throwable ignored) {}
    }


    /** A pill in the session row: same shape for both, so neither reads as primary. */
    private TextView sessionButton(String label, String description, View.OnClickListener onClick) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(accentColor);
        button.setTextSize(12);
        button.setPadding(dp(14), dp(7), dp(14), dp(7));
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(description);

        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setCornerRadius(dp(16));
        pill.setStroke(dp(1), (accentColor & 0x00FFFFFF) | 0x66000000);
        button.setBackground(pill);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        button.setLayoutParams(lp);
        button.setOnClickListener(onClick);
        return button;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }


    // ══════════════════════════════════════════════════════════
    //  Voice Dictation (🎤 mic button)
    // ══════════════════════════════════════════════════════════

    // ── Lifecycle ─────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        if (agent != null) agent.cancel();
        // An opened-but-unused conversation should not clutter the saved list.
        if (historyStore != null && history.isEmpty()) {
            historyStore.discardIfEmpty(conversationId);
        }
        super.onDestroy();
    }
}
