package com.ccs.wiredroid.project;

import android.content.Context;
import android.content.SharedPreferences;

import com.ccs.wiredroid.util.AppPreferences;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which folders are open in the project drawer, remembered per project so the
 * tree looks the same next time the project is opened.
 *
 * <p>On first open the state is seeded from
 * {@link ProjectScanner#defaultExpandedPaths(File)} so the package containing the
 * sources is already visible instead of a single collapsed {@code src}.</p>
 */
public final class TreeExpansionState {

    private static final String KEY_PREFIX = "tree_expanded_";
    /** Guards against an unbounded preference entry on a huge project. */
    private static final int MAX_REMEMBERED = 2000;

    private final SharedPreferences prefs;
    private final File projectRoot;
    private final Set<String> expanded;

    public TreeExpansionState(Context context, File projectRoot) {
        this.prefs = new AppPreferences(context).raw();
        this.projectRoot = projectRoot;
        this.expanded = load();
    }

    private String key() {
        String path = projectRoot != null ? projectRoot.getAbsolutePath() : "none";
        // A hash keeps the key short and free of characters prefs dislike.
        return KEY_PREFIX + Integer.toHexString(path.hashCode());
    }

    private Set<String> load() {
        Set<String> stored = prefs.getStringSet(key(), null);
        if (stored != null) return new HashSet<>(stored);
        // No saved state: open the source package chain so the tree is usable.
        Set<String> defaults = ProjectScanner.defaultExpandedPaths(projectRoot);
        persist(defaults);
        return new HashSet<>(defaults);
    }

    private void persist(Set<String> value) {
        Set<String> toStore = value;
        if (value.size() > MAX_REMEMBERED) {
            toStore = new LinkedHashSet<>();
            int i = 0;
            for (String s : value) {
                if (i++ >= MAX_REMEMBERED) break;
                toStore.add(s);
            }
        }
        prefs.edit().putStringSet(key(), new HashSet<>(toStore)).apply();
    }

    /** The set the scanner should consult. Callers must not mutate it. */
    public Set<String> expandedPaths() {
        return expanded;
    }

    public boolean isExpanded(File dir) {
        return dir != null && expanded.contains(dir.getAbsolutePath());
    }

    /**
     * Flips a folder open or closed.
     *
     * @return the folder's new expanded state
     */
    public boolean toggle(File dir) {
        if (dir == null) return false;
        String path = dir.getAbsolutePath();
        boolean nowExpanded;
        if (expanded.remove(path)) {
            // Closing a folder also closes everything inside it, so reopening it
            // does not restore a deep tree the user had collapsed away.
            expanded.removeIf(p -> p.startsWith(path + File.separator));
            nowExpanded = false;
        } else {
            expanded.add(path);
            nowExpanded = true;
        }
        persist(expanded);
        return nowExpanded;
    }

    public void expand(File dir) {
        if (dir == null) return;
        if (expanded.add(dir.getAbsolutePath())) persist(expanded);
    }

    /** Opens {@code dir} and every folder beneath it. */
    public void expandRecursively(File dir) {
        if (dir == null) return;
        expanded.add(dir.getAbsolutePath());
        expanded.addAll(ProjectScanner.allDirectories(dir));
        persist(expanded);
    }

    /** Closes {@code dir} and everything beneath it. */
    public void collapseRecursively(File dir) {
        if (dir == null) return;
        String path = dir.getAbsolutePath();
        expanded.remove(path);
        expanded.removeIf(p -> p.startsWith(path + File.separator));
        persist(expanded);
    }

    /** Opens every folder in the project. */
    public void expandAll() {
        expanded.addAll(ProjectScanner.allDirectories(projectRoot));
        persist(expanded);
    }

    /** Closes every folder in the project. */
    public void collapseAll() {
        expanded.clear();
        persist(expanded);
    }

    /**
     * Opens every ancestor of {@code file} so it becomes visible in the tree.
     *
     * @return true when something actually changed, so callers can skip an
     *         unnecessary tree rebuild
     */
    public boolean revealFile(File file) {
        if (file == null || projectRoot == null) return false;
        String rootPath = projectRoot.getAbsolutePath();
        boolean changed = false;
        for (File dir = file.getParentFile(); dir != null; dir = dir.getParentFile()) {
            String path = dir.getAbsolutePath();
            if (!path.startsWith(rootPath)) break;
            changed |= expanded.add(path);
            if (path.equals(rootPath)) break;
        }
        if (changed) persist(expanded);
        return changed;
    }
}
