package com.ccs.wiredroid.debug;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Менеджер закладок: зберігає закладки за файлами та рядками.
 * Зберігається в SharedPreferences.
 */
public final class BookmarkManager {

    private static final String PREFS_NAME = "bookmarks";
    private static final String KEY_PREFIX = "bm_";

    private static volatile BookmarkManager instance;

    private final SharedPreferences prefs;
    private final Map<String, Set<Integer>> bookmarks = new HashMap<>();

    private BookmarkManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadAll();
    }

    public static BookmarkManager getInstance(Context context) {
        if (instance == null) {
            synchronized (BookmarkManager.class) {
                if (instance == null) {
                    instance = new BookmarkManager(context);
                }
            }
        }
        return instance;
    }

    public boolean hasBookmark(String filePath, int line) {
        Set<Integer> set = bookmarks.get(filePath);
        return set != null && set.contains(line);
    }

    public void toggleBookmark(String filePath, int line) {
        Set<Integer> set = getOrCreate(filePath);
        if (set.contains(line)) {
            set.remove(line);
        } else {
            set.add(line);
        }
        save(filePath, set);
    }

    public void addBookmark(String filePath, int line) {
        Set<Integer> set = getOrCreate(filePath);
        set.add(line);
        save(filePath, set);
    }

    public void removeBookmark(String filePath, int line) {
        Set<Integer> set = bookmarks.get(filePath);
        if (set != null) {
            set.remove(line);
            save(filePath, set);
        }
    }

    public Set<Integer> getBookmarks(String filePath) {
        Set<Integer> set = bookmarks.get(filePath);
        return set != null ? new HashSet<>(set) : new HashSet<>();
    }

    public List<BookmarkEntry> getAllBookmarks() {
        List<BookmarkEntry> all = new ArrayList<>();
        for (Map.Entry<String, Set<Integer>> e : bookmarks.entrySet()) {
            for (int line : e.getValue()) {
                all.add(new BookmarkEntry(e.getKey(), line));
            }
        }
        return all;
    }

    public void clearFile(String filePath) {
        bookmarks.remove(filePath);
        prefs.edit().remove(KEY_PREFIX + filePath).apply();
    }

    private Set<Integer> getOrCreate(String filePath) {
        Set<Integer> set = bookmarks.get(filePath);
        if (set == null) {
            set = new HashSet<>();
            bookmarks.put(filePath, set);
        }
        return set;
    }

    private void save(String filePath, Set<Integer> set) {
        if (set.isEmpty()) {
            prefs.edit().remove(KEY_PREFIX + filePath).apply();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int line : set) {
            if (sb.length() > 0) sb.append(",");
            sb.append(line);
        }
        prefs.edit().putString(KEY_PREFIX + filePath, sb.toString()).apply();
    }

    /**
     * Whether a stored key names a file this can actually open.
     *
     * <p>Every writer here stores {@code File.getAbsolutePath()}, so anything
     * that is not an absolute path was written by a build that keyed bookmarks
     * differently. One such leftover — {@code bm_-1305596005}, evidently a hash
     * code — showed up in the bookmarks panel as "★ -1305596005:13": a row
     * naming no file and leading nowhere when tapped.</p>
     */
    static boolean isUsableKey(String filePath) {
        return filePath != null
                && filePath.startsWith("/")
                && filePath.length() > 1
                && !filePath.endsWith("/");
    }

    private void loadAll() {
        Map<String, ?> all = prefs.getAll();
        List<String> unusable = new ArrayList<>();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(KEY_PREFIX)) continue;
            String filePath = key.substring(KEY_PREFIX.length());
            if (!isUsableKey(filePath)) {
                unusable.add(key);
                continue;
            }
            String val = (String) e.getValue();
            if (val == null || val.isEmpty()) continue;
            Set<Integer> set = new HashSet<>();
            for (String s : val.split(",")) {
                try {
                    set.add(Integer.parseInt(s.trim()));
                } catch (NumberFormatException ignored) {}
            }
            if (!set.isEmpty()) {
                bookmarks.put(filePath, set);
            }
        }
        if (!unusable.isEmpty()) {
            // Dropped rather than merely hidden: nothing can ever navigate to
            // them, so leaving them behind means carrying the bad rows forever.
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : unusable) editor.remove(key);
            editor.apply();
        }
    }

    public static class BookmarkEntry {
        public final String filePath;
        public final int line;

        public BookmarkEntry(String filePath, int line) {
            this.filePath = filePath;
            this.line = line;
        }
    }
}
