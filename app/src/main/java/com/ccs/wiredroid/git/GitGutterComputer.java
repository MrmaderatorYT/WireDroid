package com.ccs.wiredroid.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Diffs an editor buffer against the same file's blob at {@code HEAD} so the
 * gutter can mark added / modified / deleted lines.
 *
 * <p>Everything here runs off the main thread and is deliberately total: an
 * untracked file, a directory that is not a repository, an unborn or otherwise
 * unresolvable HEAD, a binary blob or any JGit failure all yield an empty list
 * rather than an exception. A gutter decoration is never worth a crash.</p>
 */
public final class GitGutterComputer {

    /** Lines present in the buffer but absent from HEAD. */
    public static final int TYPE_ADDED = 0;
    /** Lines present in both but with different content. */
    public static final int TYPE_MODIFIED = 1;
    /** Lines present in HEAD but gone from the buffer. */
    public static final int TYPE_DELETED = 2;

    /** Files past this size are not diffed — the marks are not worth the stall. */
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private GitGutterComputer() {}

    /** One contiguous run of changed lines. */
    public static final class Hunk {

        public final int type;
        /** First buffer line covered, 0-based. */
        public final int startLine;
        /** One past the last buffer line covered; equals {@link #startLine} for a deletion. */
        public final int endLine;
        /** First HEAD line the hunk replaced, 0-based. */
        public final int origStartLine;
        /** One past the last HEAD line the hunk replaced. */
        public final int origEndLine;
        /** The HEAD text this hunk replaced, one entry per line, never null. */
        public final List<String> originalLines;

        Hunk(int type, int startLine, int endLine,
             int origStartLine, int origEndLine, List<String> originalLines) {
            this.type = type;
            this.startLine = startLine;
            this.endLine = endLine;
            this.origStartLine = origStartLine;
            this.origEndLine = origEndLine;
            this.originalLines = originalLines;
        }

        /** True when the buffer row sits inside this hunk's painted band. */
        public boolean coversRow(int row) {
            if (type == TYPE_DELETED) return row == startLine;
            return row >= startLine && row < endLine;
        }

        public String originalText() {
            if (originalLines.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < originalLines.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(originalLines.get(i));
            }
            return sb.toString();
        }
    }

    /**
     * Compares {@code buffer} with {@code file}'s content at HEAD.
     *
     * @return the hunks, or an empty list when there is nothing to show
     */
    public static List<Hunk> compute(File file, CharSequence buffer) {
        if (file == null || buffer == null) return Collections.emptyList();
        try {
            byte[] head = readHeadBlob(file);
            if (head == null) return Collections.emptyList();
            if (head.length > MAX_BYTES) return Collections.emptyList();
            if (RawText.isBinary(head)) return Collections.emptyList();

            byte[] now = buffer.toString().getBytes(StandardCharsets.UTF_8);
            if (now.length > MAX_BYTES) return Collections.emptyList();

            RawText a = new RawText(head);
            RawText b = new RawText(now);
            EditList edits = DiffAlgorithm
                    .getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                    .diff(RawTextComparator.DEFAULT, a, b);

            List<Hunk> out = new ArrayList<>(edits.size());
            for (Edit e : edits) {
                switch (e.getType()) {
                    case INSERT:
                        out.add(new Hunk(TYPE_ADDED, e.getBeginB(), e.getEndB(),
                                e.getBeginA(), e.getEndA(), Collections.<String>emptyList()));
                        break;
                    case REPLACE:
                        out.add(new Hunk(TYPE_MODIFIED, e.getBeginB(), e.getEndB(),
                                e.getBeginA(), e.getEndA(), slice(a, e.getBeginA(), e.getEndA())));
                        break;
                    case DELETE:
                        out.add(new Hunk(TYPE_DELETED, e.getBeginB(), e.getBeginB(),
                                e.getBeginA(), e.getEndA(), slice(a, e.getBeginA(), e.getEndA())));
                        break;
                    default:
                        break;
                }
            }
            return out;
        } catch (Throwable ignored) {
            // No repo, no HEAD, corrupt object, OOM on a pathological blob —
            // in every case the honest answer is "no marks".
            return Collections.emptyList();
        }
    }

    private static List<String> slice(RawText text, int from, int to) {
        List<String> lines = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to && i < text.size(); i++) {
            lines.add(text.getString(i));
        }
        return lines;
    }

    /**
     * @return the file's bytes at HEAD, or null when it is untracked, outside a
     *         repository, or HEAD does not resolve to a commit
     */
    private static byte[] readHeadBlob(File file) throws Exception {
        File canonical = canonical(file);
        File root = repoRoot(canonical);
        if (root == null) return null;

        String relPath = relativePath(canonical(root), canonical);
        if (relPath == null || relPath.isEmpty()) return null;

        try (Git git = Git.open(root)) {
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve(Constants.HEAD);
            if (head == null) return null;                       // unborn / empty HEAD

            try (ObjectReader reader = repo.newObjectReader();
                 RevWalk walk = new RevWalk(reader)) {
                RevCommit commit = walk.parseCommit(head);
                try (TreeWalk tw = TreeWalk.forPath(reader, relPath, commit.getTree())) {
                    if (tw == null) return null;                 // untracked at HEAD
                    ObjectId blob = tw.getObjectId(0);
                    if (blob == null || ObjectId.zeroId().equals(blob)) return null;
                    ObjectLoader loader = reader.open(blob);
                    return loader.getBytes(MAX_BYTES);
                }
            }
        }
    }

    /** Walks up from the file looking for the {@code .git} directory. */
    private static File repoRoot(File file) {
        File dir = file.getParentFile();
        int guard = 0;
        while (dir != null && guard++ < 64) {
            if (GitManager.isGitRepo(dir)) return dir;
            dir = dir.getParentFile();
        }
        return null;
    }

    /** Git-style forward-slash path of {@code file} below {@code root}, or null. */
    private static String relativePath(File root, File file) {
        String rootPath = root.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        if (!filePath.startsWith(rootPath)) return null;
        String rel = filePath.substring(rootPath.length());
        while (rel.startsWith(File.separator)) rel = rel.substring(1);
        return rel.replace(File.separatorChar, '/');
    }

    private static File canonical(File f) {
        try {
            return f.getCanonicalFile();
        } catch (Exception e) {
            return f.getAbsoluteFile();
        }
    }
}
