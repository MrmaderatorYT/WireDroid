package com.ccs.wiredroid.analysis;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.ccs.wiredroid.R;

/**
 * Повноцінний статичний аналізатор Java-коду (Checkstyle/Lint-рівень).
 * Понад 70 правил: стиль, безпека, продуктивність, архітектура, ресурси, потоки, тести, документація.
 *
 * Правила розподілені по 6 класах:
 * - StyleRules: стиль коду, форматування, неймінг, документація
 * - StructureRules: імпорти, методи, класи, поля
 * - SafetyRules: null safety, потоки, виключення, ресурси
 * - ModernRules: streams, lambda, optional, date, regex, generics, анотації
 * - QualityRules: архітектура, продуктивність, тестування, deprecated, коллекції
 * - SecurityRules: секрети в коді, SQL-ін'єкції, слабка криптографія
 */
public final class StaticAnalyzer {

    private StaticAnalyzer() {}

    /** Аналіз без контексту (fallback на hardcoded рядки). */
    public static List<ProblemItem> analyze(File projectRoot, List<File> javaFiles) {
        return analyze(null, projectRoot, javaFiles);
    }

    /**
     * Told after each file how the sweep is going, and asked whether to keep
     * going. A sweep over a large project takes long enough that the caller
     * needs both: something to show before the end, and a way to stop when the
     * answer is already out of date.
     */
    public interface Progress {
        /**
         * @param out    the findings so far — read only, and only from this thread
         * @param done   how many files have been read
         * @param total  how many there are
         * @return false to abandon the sweep
         */
        boolean onFile(List<ProblemItem> out, int done, int total);
    }

    /** Аналіз з контекстом (використовує перекладені рядки). */
    public static List<ProblemItem> analyze(Context ctx, File projectRoot, List<File> javaFiles) {
        return analyze(ctx, projectRoot, javaFiles, null);
    }

    /** As above, but reporting progress and stopping when asked to. */
    public static List<ProblemItem> analyze(Context ctx, File projectRoot, List<File> javaFiles,
                                            Progress progress) {
        List<ProblemItem> out = new ArrayList<>();
        if (javaFiles == null) return out;

        int total = javaFiles.size();
        int done = 0;
        for (File f : javaFiles) {
            if (progress != null && !progress.onFile(out, done, total)) return out;
            done++;
            out.addAll(analyzeFile(ctx, f));
        }
        return out;
    }

    /**
     * Everything this reports about one file, with nothing cached.
     *
     * <p>Split out so a caller that only wants a single file — because only that
     * one changed — does not have to sweep the project to get it.</p>
     */
    public static List<ProblemItem> analyzeFile(Context ctx, File f) {
        List<ProblemItem> out = new ArrayList<>();
        if (!f.getName().endsWith(".java")) return out;
        try {
            // The empty-file case is handled by analyzeSource, so it is not
            // repeated here.
            return analyzeSource(ctx, f, new String(
                    java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * The same rules, run over text the caller already has.
     *
     * <p>For the file open in the editor that text is the buffer, not the copy on
     * disk — otherwise the file being worked on is the one file whose style
     * findings are always a few keystrokes stale, or missing entirely.</p>
     */
    public static List<ProblemItem> analyzeSource(Context ctx, File f, String content) {
        List<ProblemItem> out = new ArrayList<>();
        if (content == null) return out;
        try {
            if (content.trim().isEmpty()) {
                out.add(new ProblemItem(ProblemItem.Severity.WARNING,
                        ctx != null ? ctx.getString(R.string.sa_empty_file) : "", f, 1));
                return out;
            }
            String[] lines = content.split("\n", -1);

            // Each rule class is isolated, and the guard catches Throwable
            // rather than Exception on purpose. Every rule set keeps its
            // patterns in static finals, so a malformed one fails as
            // ExceptionInInitializerError — an Error, which a catch(Exception)
            // lets straight through and which then kills the whole analysis
            // thread. That happened: Android's regex engine is ICU and rejects
            // a bare "}" that desktop java.util.regex accepts, so a pattern
            // that compiled fine in tests took down the problems panel on
            // device. One broken rule set should cost its own findings, not
            // everyone else's.
            runRules(ctx, content, lines, f, out);

        } catch (Exception ignored) {}
        return out;
    }

    private static void runRules(Context ctx, String content, String[] lines,
                                 File f, List<ProblemItem> out) {
        try { StyleRules.analyze(ctx, lines, f, out); }              catch (Throwable ignored) {}
        try { StructureRules.analyze(ctx, content, lines, f, out); } catch (Throwable ignored) {}
        try { SafetyRules.analyze(ctx, lines, f, out); }             catch (Throwable ignored) {}
        try { ModernRules.analyze(ctx, lines, f, out); }             catch (Throwable ignored) {}
        try { QualityRules.analyze(ctx, lines, f, out); }            catch (Throwable ignored) {}
        try { SecurityRules.analyze(ctx, lines, f, out); }           catch (Throwable ignored) {}
    }
}
