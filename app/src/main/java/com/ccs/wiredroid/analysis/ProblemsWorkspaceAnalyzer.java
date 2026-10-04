package com.ccs.wiredroid.analysis;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.tools.compilers.ProjectCompiler;
import com.ccs.wiredroid.project.ProjectScanner;
import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Зведення діагностики: ECJ по тексту активного .java (як у редакторі) + легкий static по файлах на диску.
 * Для активного файлу пріоритет у повідомлень ECJ (щоб не дублювати зі застарілим вмістом на диску).
 *
 * <p>The two halves cost wildly different amounts and change at wildly different
 * rates, so they are treated differently. The active file is recompiled every
 * time, because that is the one being edited. The rest of the project is kept
 * per file, alongside the modified stamp it was read at, and a file is only read
 * again once that stamp moves — so a save costs one file rather than all of
 * them. Re-reading everything on every pass is how this came to burn over a
 * minute of CPU per pass on a two-thousand-file project, with the panel staying
 * empty because no pass ever reached the end.</p>
 */
public final class ProblemsWorkspaceAnalyzer {

    /**
     * Receives results as they come in, and decides whether the sweep continues.
     *
     * <p>Without this the caller sees nothing until the whole project has been
     * read, which on a large one is long enough that the panel looks broken.</p>
     */
    public interface Listener {
        /**
         * @param merged everything known so far, ready to display
         * @return false to abandon the sweep — its partial result is discarded
         */
        boolean onPartial(List<ProblemItem> merged);
    }

    /** Files between progress reports. Small enough to feel live, large enough not to thrash. */
    private static final int REPORT_EVERY = 100;

    /** What the last sweep found in one file, and the stamp it was read at. */
    private static final class Cached {
        final long modified;
        final List<ProblemItem> items;

        Cached(long modified, List<ProblemItem> items) {
            this.modified = modified;
            this.items = items;
        }
    }

    /**
     * How many files are read at once during a sweep.
     *
     * <p>Reading a file and running the rules over it depends on nothing else,
     * so the sweep is one of the few parts of this app that genuinely divides
     * across cores. Half of them, capped at four: on an eight-core phone that is
     * four workers, and on a four-core one it is two, which leaves something for
     * the screen the user is still looking at.</p>
     *
     * <p>Four is where the gain stops. Measured on a two-thousand-file project:
     * one thread 45s, four threads 19s, all eight threads also 19s — and the
     * eight-thread run dropped frames. The cores past the first four are the
     * little ones, and the parts that cannot divide (sorting the merged list
     * between batches) set the floor.</p>
     */
    private static final int SWEEP_THREADS =
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));

    /** Created on first use and kept; the threads are daemons and idle out. */
    private static java.util.concurrent.ExecutorService sweepPool;

    private static synchronized java.util.concurrent.ExecutorService sweepPool() {
        if (sweepPool == null) {
            sweepPool = java.util.concurrent.Executors.newFixedThreadPool(SWEEP_THREADS, r -> {
                Thread t = new Thread(() -> {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    } catch (Throwable ignored) {}
                    r.run();
                }, "problems-sweep");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
        }
        return sweepPool;
    }

    private static final Object CACHE_LOCK = new Object();
    private static String cachedRootPath;
    private static final java.util.Map<String, Cached> FILE_CACHE = new java.util.HashMap<>();

    private ProblemsWorkspaceAnalyzer() {}

    /** Drops everything kept — for a project switch, or when in doubt. */
    public static void invalidateWorkspace() {
        synchronized (CACHE_LOCK) {
            cachedRootPath = null;
            FILE_CACHE.clear();
        }
    }

    /**
     * Drops what is kept about one file.
     *
     * <p>The sweep already re-reads anything whose modified stamp moved, so this
     * is belt and braces — but a filesystem that keeps that stamp to the nearest
     * second will not show two saves inside the same second, and the panel would
     * then describe the previous version.</p>
     */
    public static void invalidateFile(File file) {
        if (file == null) return;
        synchronized (CACHE_LOCK) {
            FILE_CACHE.remove(file.getAbsolutePath());
        }
    }

    public static List<ProblemItem> analyze(Context appContext, File projectRoot,
                                            String editorJavaSource, File activeJavaFile) {
        return analyze(appContext, projectRoot, editorJavaSource, activeJavaFile, false, null);
    }

    public static List<ProblemItem> analyze(Context appContext, File projectRoot,
                                            String editorJavaSource, File activeJavaFile,
                                            Listener listener) {
        return analyze(appContext, projectRoot, editorJavaSource, activeJavaFile, false, listener);
    }

    public static List<ProblemItem> analyze(Context appContext, File projectRoot,
                                            String editorJavaSource, File activeJavaFile,
                                            boolean activeFileOnly, Listener listener) {
        if (projectRoot == null) {
            projectRoot = new File(".");
        }
        final File root = projectRoot;
        final List<ProblemItem> active = activeFileProblems(appContext, editorJavaSource, activeJavaFile);

        // If in power saving or single-file mode, return active-file findings immediately
        if (activeFileOnly) {
            return present(active, activeJavaFile);
        }

        // A different project shares nothing with the last one.
        synchronized (CACHE_LOCK) {
            if (!root.getAbsolutePath().equals(cachedRootPath)) {
                FILE_CACHE.clear();
                cachedRootPath = root.getAbsolutePath();
            }
        }

        // The active file is ready now; showing it beats showing nothing for as
        // long as the sweep takes.
        if (listener != null && !listener.onPartial(present(active, activeJavaFile))) {
            return present(active, activeJavaFile);
        }

        List<File> sources = ProjectScanner.listJavaSources(root);
        List<ProblemItem> swept = new ArrayList<>();
        Set<String> stillThere = new java.util.HashSet<>(sources.size() * 2);
        for (File f : sources) stillThere.add(f.getAbsolutePath());

        // A batch at a time, spread across the pool. Batching rather than one big
        // fan-out keeps the reporting and the stop check exactly where they were,
        // and collecting each batch in submission order keeps the result the same
        // every run regardless of which worker finished first.
        for (int from = 0; from < sources.size(); from += REPORT_EVERY) {
            int to = Math.min(from + REPORT_EVERY, sources.size());
            List<java.util.concurrent.Callable<List<ProblemItem>>> batch = new ArrayList<>(to - from);
            for (File f : sources.subList(from, to)) {
                final File file = f;
                batch.add(() -> analyzeCached(appContext, file));
            }
            try {
                for (java.util.concurrent.Future<List<ProblemItem>> future : sweepPool().invokeAll(batch)) {
                    swept.addAll(future.get());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return present(merge(active, swept, activeJavaFile), activeJavaFile);
            } catch (java.util.concurrent.ExecutionException e) {
                // A file that blew up costs its own findings, not the sweep.
                android.util.Log.w("Problems", "sweep batch failed", e.getCause());
            }

            if (listener != null
                    && !listener.onPartial(present(merge(active, swept, activeJavaFile), activeJavaFile))) {
                return present(merge(active, swept, activeJavaFile), activeJavaFile);
            }
        }

        // Files that have gone away must not keep reporting problems.
        synchronized (CACHE_LOCK) {
            FILE_CACHE.keySet().retainAll(stillThere);
        }
        return present(merge(active, swept, activeJavaFile), activeJavaFile);
    }

    /**
     * One file's findings, from the kept copy when its modified stamp has not
     * moved. Safe to call from several threads: the rule sets hold nothing but
     * {@code static final} patterns, and each call builds its own list.
     */
    private static List<ProblemItem> analyzeCached(Context ctx, File f) {
        String path = f.getAbsolutePath();
        long modified = f.lastModified();
        synchronized (CACHE_LOCK) {
            Cached hit = FILE_CACHE.get(path);
            if (hit != null && hit.modified == modified) return hit.items;
        }
        // Only the files that actually changed are read again. This is what makes
        // a save cost one file instead of the whole project.
        List<ProblemItem> items = StaticAnalyzer.analyzeFile(ctx, f);
        synchronized (CACHE_LOCK) {
            FILE_CACHE.put(path, new Cached(modified, items));
        }
        return items;
    }

    /**
     * Everything about the file in the editor, from the text in the editor.
     *
     * <p>Both halves, not just the compiler: the sweep's findings for this file
     * are dropped by {@link #merge} because they describe the copy on disk, so if
     * the rules were not run here too, the file being worked on would be the one
     * file that never reported a style finding.</p>
     */
    private static List<ProblemItem> activeFileProblems(Context appContext, String editorJavaSource,
                                                        File activeJavaFile) {
        if (activeJavaFile == null
                || !activeJavaFile.getName().endsWith(".java")
                || editorJavaSource == null) {
            return new ArrayList<>();
        }
        List<ProblemItem> out = new ArrayList<>(
                ProjectCompiler.ecjProblemsForSource(appContext, editorJavaSource, activeJavaFile));
        out.addAll(StaticAnalyzer.analyzeSource(appContext, activeJavaFile, editorJavaSource));
        return out;
    }

    /**
     * Active-file findings win over anything the sweep said about the same file:
     * the sweep read it off disk, which may be several keystrokes behind.
     */
    private static List<ProblemItem> merge(List<ProblemItem> active, List<ProblemItem> workspace,
                                           File activeJavaFile) {
        List<ProblemItem> out = new ArrayList<>(active.size() + workspace.size());
        out.addAll(active);
        String activePath = activeJavaFile != null ? activeJavaFile.getAbsolutePath() : "";
        for (ProblemItem p : workspace) {
            if (!activePath.isEmpty() && p.file != null
                    && activePath.equals(p.file.getAbsolutePath())) {
                continue;
            }
            out.add(p);
        }
        return out;
    }

    /** Deduplicates, orders by what matters, and caps the length. */
    private static List<ProblemItem> present(List<ProblemItem> in, File activeJavaFile) {
        List<ProblemItem> res = dedupe(in);
        final String activePath = activeJavaFile != null ? activeJavaFile.getAbsolutePath() : "";
        // Errors first, then the file the user is looking at, so the one finding
        // that matters is not buried under a thousand style notes.
        Collections.sort(res, new Comparator<ProblemItem>() {
            @Override
            public int compare(ProblemItem a, ProblemItem b) {
                int r = rank(a.severity) - rank(b.severity);
                if (r != 0) return r;
                int an = isActive(a) ? 0 : 1, bn = isActive(b) ? 0 : 1;
                if (an != bn) return an - bn;
                String af = a.file != null ? a.file.getName() : "";
                String bf = b.file != null ? b.file.getName() : "";
                int f = af.compareTo(bf);
                if (f != 0) return f;
                return Integer.compare(a.line, b.line);
            }

            private boolean isActive(ProblemItem p) {
                return !activePath.isEmpty() && p.file != null
                        && activePath.equals(p.file.getAbsolutePath());
            }
        });

        // Deliberately not cut short here. Trimming before the panel sees the
        // list made the severity counts describe the trimmed copy rather than the
        // project — "Info 1999" next to a note about 23788 more — and, worse, a
        // severity that fell past the cut could not be brought back by unticking
        // the others. The list is cut for display instead, after filtering.
        return res;
    }

    private static int rank(ProblemItem.Severity s) {
        switch (s) {
            case ERROR:    return 0;
            case SECURITY: return 1;
            case WARNING:  return 2;
            default:       return 3;
        }
    }

    private static List<ProblemItem> dedupe(List<ProblemItem> in) {
        Set<String> seen = new LinkedHashSet<>();
        List<ProblemItem> res = new ArrayList<>(in.size());
        for (ProblemItem p : in) {
            String key = (p.file != null ? p.file.getAbsolutePath() : "?")
                    + "|" + p.line + "|" + p.severity + "|" + p.message;
            if (seen.add(key)) {
                res.add(p);
            }
        }
        return res;
    }
}
