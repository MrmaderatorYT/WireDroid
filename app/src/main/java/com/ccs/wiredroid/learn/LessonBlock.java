package com.ccs.wiredroid.learn;

import java.util.List;

/**
 * Один блок контенту уроку. {@code type} визначає як рендерер його показує.
 * Замість HTML/WebView — нативні блоки, швидко й компактно.
 */
public final class LessonBlock {

    public static final int HEADING   = 0;  // підрозділ уроку (h2)
    public static final int PARAGRAPH = 1;  // звичайний текст
    public static final int CODE      = 2;  // Java-код з підсвіткою
    public static final int LIST      = 3;  // маркований список
    public static final int NOTE      = 4;  // інформаційна рамка
    public static final int WARNING   = 5;  // попереджувальна рамка
    public static final int TABLE     = 6;  // таблиця (header + rows)

    public static final int RUN_NONE            = 0;
    public static final int RUN_JAVA_STATEMENTS = 1;
    public static final int RUN_JAVA_SOURCE     = 2;

    public final int type;
    /** Для HEADING/PARAGRAPH/CODE/NOTE/WARNING — основний текст.
     *  Для LIST — рядки розділені \n.
     *  Для TABLE — рядки розділені \n, колонки \t. */
    public final String text;
    /** Для TABLE — рядок заголовків з колонками розділеними \t. */
    public final String tableHeader;
    /** Спосіб виконання CODE-блоку. Звичайні ілюстративні блоки мають RUN_NONE. */
    public final int runMode;
    /** Код для виконання. Може відрізнятися від показаного, якщо прикладу потрібен прихований boilerplate. */
    public final String executionText;

    private LessonBlock(int type, String text, String tableHeader,
                        int runMode, String executionText) {
        this.type = type;
        this.text = text;
        this.tableHeader = tableHeader;
        this.runMode = runMode;
        this.executionText = executionText;
    }

    // ── Фабричні методи ────────────────────────────────────────────────────

    public static LessonBlock heading(String text) {
        return new LessonBlock(HEADING, text, null, RUN_NONE, null);
    }

    public static LessonBlock paragraph(String text) {
        return new LessonBlock(PARAGRAPH, text, null, RUN_NONE, null);
    }

    public static LessonBlock code(String code) {
        return new LessonBlock(CODE, code, null, RUN_NONE, null);
    }

    /**
     * Виконуваний фрагмент із виразами/інструкціями. Runner непомітно додає imports,
     * {@code SnippetRunner} і {@code main}; користувач бачить лише суттєвий код.
     */
    public static LessonBlock runnableCode(String code) {
        return new LessonBlock(CODE, code, null, RUN_JAVA_STATEMENTS, code);
    }

    /**
     * Виконуваний приклад із окремим повним source. Корисно, коли показаний фрагмент
     * навмисно опускає допоміжні класи, дані або {@code main}.
     */
    public static LessonBlock runnableCode(String displayedCode, String fullSource) {
        return new LessonBlock(CODE, displayedCode, null, RUN_JAVA_SOURCE, fullSource);
    }

    public static LessonBlock list(List<String> items) {
        return new LessonBlock(LIST, String.join("\n", items), null, RUN_NONE, null);
    }

    public static LessonBlock list(String... items) {
        return new LessonBlock(LIST, String.join("\n", items), null, RUN_NONE, null);
    }

    public static LessonBlock note(String text) {
        return new LessonBlock(NOTE, text, null, RUN_NONE, null);
    }

    public static LessonBlock warning(String text) {
        return new LessonBlock(WARNING, text, null, RUN_NONE, null);
    }

    /**
     * Таблиця. {@code header} — колонки через табуляцію,
     * {@code rows} — по рядку, колонки через табуляцію.
     */
    public static LessonBlock table(String header, List<String> rows) {
        return new LessonBlock(TABLE, String.join("\n", rows), header, RUN_NONE, null);
    }

    /** Створює блок, описаний у JSON-файлі курсу. */
    static LessonBlock fromAsset(int type, String text, String tableHeader,
                                 int runMode, String executionText) {
        return new LessonBlock(type, text, tableHeader, runMode, executionText);
    }

    public boolean isRunnable() {
        return type == CODE && runMode != RUN_NONE
                && executionText != null && !executionText.trim().isEmpty();
    }
}
