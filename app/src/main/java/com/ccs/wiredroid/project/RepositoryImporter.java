package com.ccs.wiredroid.project;

import com.ccs.wiredroid.git.GitManager;

import org.eclipse.jgit.lib.ProgressMonitor;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Clone, then work out what was cloned.
 *
 * <p>Everything here blocks: it is network and disk work, and it belongs on a
 * background executor. Progress arrives through {@link Listener} as the raw
 * task names JGit produces ("Receiving objects", "Resolving deltas"); the UI
 * wraps them in a localized frame rather than this class inventing sentences.</p>
 */
public final class RepositoryImporter {

    /** Progress from the clone, on the calling (background) thread. */
    public interface Listener {
        /** @param gitTask the task JGit is running, verbatim */
        void onGitTask(String gitTask);
    }

    /** Raised when the user cancels; the partial clone has already been removed. */
    public static final class CancelledException extends Exception {
        CancelledException() {
            super("cancelled");
        }
    }

    private RepositoryImporter() {}

    /**
     * Clones {@code repo} into {@code dest} and detects what it is.
     *
     * <p>A failed or cancelled clone leaves nothing behind: a half-written
     * directory would otherwise block the next attempt at the same name, and
     * looks to every other part of the app like a real project.</p>
     *
     * @param cancel polled during the transfer; set it to abort
     * @return the layout of the cloned directory
     * @throws CancelledException if {@code cancel} was set
     */
    public static ImportedLayout cloneAndDetect(RepoUrl repo, File dest,
                                                String username, String token,
                                                AtomicBoolean cancel, Listener listener)
            throws Exception {
        boolean created = !dest.exists();
        try {
            String branch = repo.branch;
            if (branch == null) {
                // A single-branch clone needs a branch name; ask the remote for
                // its default rather than give up on the narrow fetch. A remote
                // that will not answer still clones the ordinary way below.
                try {
                    branch = GitManager.defaultBranch(repo.cloneUrl, username, token);
                } catch (Exception ignored) {
                    branch = null;
                }
            }
            throwIfCancelled(cancel);

            GitManager.clone(repo.cloneUrl, dest, username, token,
                    branch, branch != null, monitor(cancel, listener));

            throwIfCancelled(cancel);
            return ProjectLayoutDetector.detect(dest);
        } catch (Exception e) {
            if (created) deleteRecursive(dest);
            if (cancel != null && cancel.get()) throw new CancelledException();
            throw e;
        }
    }

    /** Re-runs detection on a chosen module of a multi-module repository. */
    public static ImportedLayout detect(File dir) {
        return ProjectLayoutDetector.detect(dir);
    }

    private static void throwIfCancelled(AtomicBoolean cancel) throws CancelledException {
        if (cancel != null && cancel.get()) throw new CancelledException();
    }

    private static ProgressMonitor monitor(AtomicBoolean cancel, Listener listener) {
        return new ProgressMonitor() {
            @Override public void start(int totalTasks) {}

            @Override public void beginTask(String title, int totalWork) {
                if (listener != null && title != null) listener.onGitTask(title);
            }

            @Override public void update(int completed) {}

            @Override public void endTask() {}

            @Override public boolean isCancelled() {
                return cancel != null && cancel.get();
            }
        };
    }

    static boolean deleteRecursive(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        return file.delete();
    }
}
