package com.ccs.wiredroid.ui;

import java.io.File;

public class FileTab {

    public final File file;
    public boolean isModified;
    /** Restored unsaved buffer is kept separate until Save/Run or a new edit. */
    public boolean recoveredDraft;
    public boolean isPinned;
    /** Байтки .class файлу (для байткод-в'ювера); null для текстових файлів. */
    public byte[] classBytes;

    public int cursorLine = 0;
    public int cursorColumn = 0;

    /**
     * Show this file rendered rather than as source.
     *
     * <p>Lives on the tab, not on the pane. Preview used to be a property of the
     * pane, so switching to another file left the rendered document sitting over
     * it — and switching back showed source again. It belongs to the document
     * the user asked to render, and follows it.</p>
     */
    public boolean previewMode = false;

    public FileTab(File file) {
        this.file = file;
        this.isModified = false;
        this.isPinned = false;
    }

    /** Чи є ця вкладка .class-файлом (бінарним). */
    public boolean isClassFile() {
        return file != null && file.getName().endsWith(".class");
    }

    public String getDisplayName() {
        String prefix = isPinned ? "\ud83d\udccc " : "";
        return prefix + (isModified ? file.getName() + " \u25cf" : file.getName());
    }
}
