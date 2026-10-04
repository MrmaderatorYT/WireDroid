package com.ccs.wiredroid.ui;

import java.io.File;

/**
 * Елемент дерева проєкту (як у IntelliJ IDEA): файл або каталог з глибиною для відступу.
 *
 * <p>Каталоги також несуть стан розкриття, тож дерево показує лише вміст
 * розкритих папок.</p>
 */
public class FileTreeNode {

    public final File path;
    public final int depth;
    public final boolean directory;
    /** Чи розкрито каталог (для файлів завжди {@code false}). */
    public final boolean expanded;
    /** Чи має каталог вміст — визначає, чи малювати трикутник. */
    public final boolean hasChildren;

    public FileTreeNode(File path, int depth, boolean directory) {
        this(path, depth, directory, false, false);
    }

    public FileTreeNode(File path, int depth, boolean directory,
                        boolean expanded, boolean hasChildren) {
        this.path = path;
        this.depth = depth;
        this.directory = directory;
        this.expanded = directory && expanded;
        this.hasChildren = directory && hasChildren;
    }

    /** Коротка назва (останній сегмент шляху). */
    public String shortName() {
        return path.getName();
    }
}
