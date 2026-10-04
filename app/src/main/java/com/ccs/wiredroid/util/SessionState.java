package com.ccs.wiredroid.util;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/**
 * Зберігає стан сесії проєкту: відкриті вкладки, активна вкладка, позиції кареток.
 * Ключ — хеш шляху до кореня проєкту.
 */
public final class SessionState {

    private static final String PREFS_NAME = "session_state";
    private static final String SEP = "|||";
    private static final String CURSOR_SEP = ";;";

    private final SharedPreferences prefs;

    public SessionState(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Зберігає стан сесії для проєкту.
     * @param projectRoot шлях до кореня проєкту
     * @param tabPaths    шляхи до файлів відкритих вкладок
     * @param activeIndex індекс активної вкладки
     * @param cursorLines рядки кареток (1-indexed), по одному на вкладку
     * @param cursorCols  стовпчики кареток (0-indexed), по одному на вкладку
     */
    public void save(String projectRoot, List<String> tabPaths, int activeIndex,
                     List<Integer> cursorLines, List<Integer> cursorCols) {
        String key = projectKey(projectRoot);
        StringBuilder sb = new StringBuilder();

        // Tab paths
        for (int i = 0; i < tabPaths.size(); i++) {
            if (i > 0) sb.append(SEP);
            sb.append(tabPaths.get(i));
        }

        // Active index
        sb.append(CURSOR_SEP).append(activeIndex);

        // Cursor positions (line:col per tab)
        sb.append(CURSOR_SEP);
        for (int i = 0; i < tabPaths.size(); i++) {
            if (i > 0) sb.append(SEP);
            int line = (i < cursorLines.size()) ? cursorLines.get(i) : 1;
            int col = (i < cursorCols.size()) ? cursorCols.get(i) : 0;
            sb.append(line).append(":").append(col);
        }

        prefs.edit().putString(key, sb.toString()).apply();
    }

    /**
     * Відновлює стан сесії для проєкту.
     * @return SavedSession або null якщо немає збереженого стану
     */
    public SavedSession restore(String projectRoot) {
        String key = projectKey(projectRoot);
        String data = prefs.getString(key, null);
        if (data == null || data.isEmpty()) return null;

        String[] parts = data.split(CURSOR_SEP, -1);
        if (parts.length < 3) return null;

        // Parse tab paths
        String[] paths = parts[0].split("\\|\\|\\|");
        List<String> tabPaths = new ArrayList<>();
        for (String p : paths) {
            if (!p.isEmpty()) tabPaths.add(p);
        }
        if (tabPaths.isEmpty()) return null;

        // Parse active index
        int activeIndex = 0;
        try {
            activeIndex = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ignored) {}
        if (activeIndex < 0 || activeIndex >= tabPaths.size()) activeIndex = 0;

        // Parse cursor positions
        List<Integer> cursorLines = new ArrayList<>();
        List<Integer> cursorCols = new ArrayList<>();
        String[] cursors = parts[2].split("\\|\\|\\|");
        for (String c : cursors) {
            if (c.isEmpty()) continue;
            String[] lc = c.split(":");
            if (lc.length == 0) continue;
            try {
                cursorLines.add(Integer.parseInt(lc[0]));
                cursorCols.add(lc.length > 1 ? Integer.parseInt(lc[1]) : 0);
            } catch (NumberFormatException e) {
                cursorLines.add(1);
                cursorCols.add(0);
            }
        }

        return new SavedSession(tabPaths, activeIndex, cursorLines, cursorCols);
    }

    public void clear(String projectRoot) {
        prefs.edit().remove(projectKey(projectRoot)).apply();
    }

    /**
     * Копіює стан сесії з одного шляху проекту до іншого (при копіюванні проекту).
     */
    public void copy(String fromProjectRoot, String toProjectRoot) {
        String fromKey = projectKey(fromProjectRoot);
        String toKey = projectKey(toProjectRoot);
        String data = prefs.getString(fromKey, null);
        if (data != null && !data.isEmpty()) {
            prefs.edit().putString(toKey, data).apply();
        }
    }

    private String projectKey(String projectRoot) {
        return "session_" + projectRoot.hashCode();
    }

    public static class SavedSession {
        public final List<String> tabPaths;
        public final int activeIndex;
        public final List<Integer> cursorLines;
        public final List<Integer> cursorCols;

        public SavedSession(List<String> tabPaths, int activeIndex,
                            List<Integer> cursorLines, List<Integer> cursorCols) {
            this.tabPaths = tabPaths;
            this.activeIndex = activeIndex;
            this.cursorLines = cursorLines;
            this.cursorCols = cursorCols;
        }
    }
}
