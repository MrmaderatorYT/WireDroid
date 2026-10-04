package com.ccs.wiredroid.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * Process-local queue of manual chat insertions and file-targeted agent edits.
 *
 * AiChatActivity працює в окремому вікні поверх MainActivity (редактора), а Cursor
 * активного редактора живе саме в MainActivity. Тому перенесення згенерованого коду
 * з чату в редактор відбувається через цей статичний буфер: пишемо сюди під час сесії
 * чату, а MainActivity.onResume дренує чергу й застосовує її до activeEditor.
 *
 * The queue preserves operation order. Manual "Insert" actions still target the
 * active cursor, while agent patches carry a canonical path plus an exact fragment
 * and therefore cannot drift to whichever editor happens to have focus later.
 */
public final class PendingEdits {

    private static final String PREFS = "pending_agent_change_set";
    private static final String KEY = "change_set";
    private static final String UNDO_KEY = "undo_change_set";

    /** One reviewed agent transaction. New files have an empty base and created=true. */
    public static final class FileChange {
        public final String path;
        public final String base;
        public final String content;
        public final boolean created;
        public final boolean deleted;

        public FileChange(String path, String base, String content, boolean created) {
            this(path, base, content, created, false);
        }

        public FileChange(String path, String base, String content, boolean created, boolean deleted) {
            this.path = path;
            this.base = base == null ? "" : base;
            this.content = content == null ? "" : content;
            this.created = created;
            this.deleted = deleted;
        }
    }

    public static final class AgentChangeSet {
        public final String id;
        public final String projectRoot;
        public final List<FileChange> changes;

        public AgentChangeSet(String projectRoot, List<FileChange> changes) {
            this(UUID.randomUUID().toString(), projectRoot, changes);
        }

        private AgentChangeSet(String id, String projectRoot, List<FileChange> changes) {
            this.id = id;
            this.projectRoot = projectRoot == null ? "" : projectRoot;
            this.changes = Collections.unmodifiableList(new ArrayList<>(changes));
        }

        public boolean isEmpty() { return changes.isEmpty(); }

        private JSONObject toJson() throws JSONException {
            JSONArray files = new JSONArray();
            for (FileChange change : changes) files.put(new JSONObject()
                    .put("path", change.path).put("base", change.base)
                    .put("content", change.content).put("created", change.created));
            // The operation type is persisted so a later Undo can remove new files safely.
            for (int i = 0; i < changes.size(); i++) {
                files.getJSONObject(i).put("deleted", changes.get(i).deleted);
            }
            return new JSONObject().put("id", id).put("projectRoot", projectRoot)
                    .put("changes", files);
        }

        private static AgentChangeSet fromJson(JSONObject json) throws JSONException {
            JSONArray files = json.getJSONArray("changes");
            List<FileChange> changes = new ArrayList<>();
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                changes.add(new FileChange(file.getString("path"), file.optString("base"),
                        file.optString("content"), file.optBoolean("created"),
                        file.optBoolean("deleted")));
            }
            return new AgentChangeSet(json.optString("id"), json.optString("projectRoot"), changes);
        }
    }

    private static AgentChangeSet stagedChangeSet;

    public static synchronized void stageChangeSet(Context context, AgentChangeSet changeSet) {
        stagedChangeSet = changeSet;
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY, changeSet.toJson().toString()).apply();
        } catch (JSONException ignored) {}
    }

    public static synchronized AgentChangeSet peekChangeSet(Context context) {
        AgentChangeSet result = stagedChangeSet;
        if (result == null) {
            String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
            if (raw != null) try { result = AgentChangeSet.fromJson(new JSONObject(raw)); }
            catch (JSONException ignored) {}
        }
        return result;
    }

    public static synchronized void discardChangeSet(Context context) {
        stagedChangeSet = null;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }

    public static synchronized void saveUndoChangeSet(Context context, AgentChangeSet set) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(UNDO_KEY, set.toJson().toString()).apply();
        } catch (JSONException ignored) {}
    }

    public static synchronized AgentChangeSet takeUndoChangeSet(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(UNDO_KEY, null);
        prefs.edit().remove(UNDO_KEY).apply();
        if (raw == null) return null;
        try { return AgentChangeSet.fromJson(new JSONObject(raw)); }
        catch (JSONException ignored) { return null; }
    }

    public static final String LOCATION_CURSOR    = "cursor";
    public static final String LOCATION_APPEND    = "append";
    public static final String LOCATION_REPLACE   = "replace";

    /**
     * Replace one exact fragment, leaving the rest of the file alone.
     *
     * <p>The three modes above can only add text or overwrite everything, so an
     * agent asked to change one method had to choose between pasting a second
     * copy of it at the cursor and rewriting the whole file from memory. Both
     * look to the user like "it pasted the code instead of editing it", and the
     * second also risks losing anything the model did not bother to reproduce.</p>
     */
    public static final String LOCATION_PATCH     = "patch";

    /** Один запит на вставку коду. */
    public static final class Edit {
        public final String code;
        public final String location;
        /** For {@link #LOCATION_PATCH}: the exact text to replace. */
        public final String find;
        /** Canonical target path for agent patches; null for manual cursor inserts. */
        public final String path;

        public Edit(String code, String location) {
            this(code, location, null, null);
        }

        public Edit(String code, String location, String find) {
            this(code, location, find, null);
        }

        public Edit(String code, String location, String find, String path) {
            this.code = code == null ? "" : code;
            this.location = location == null ? LOCATION_CURSOR : location;
            this.find = find;
            this.path = path;
        }
    }

    private static final Deque<Edit> queue = new ArrayDeque<>();

    private PendingEdits() {}

    /** Додати код у чергу на вставку. Потокобезпечно через синхронізацію на черзі. */
    public static void add(String code, String location) {
        if (code == null || code.isEmpty()) return;
        synchronized (queue) {
            queue.add(new Edit(code, location));
        }
    }

    /**
     * Queues a replacement of {@code find} by {@code code}.
     *
     * <p>Empty replacement text is allowed here — deleting a fragment is a real
     * edit — so this does not share the guard above.</p>
     */
    public static void addPatch(String find, String code) {
        addPatch(null, find, code);
    }

    /** Queues an exact replacement for one particular project file. */
    public static void addPatch(String path, String find, String code) {
        if (find == null || find.isEmpty()) return;
        synchronized (queue) {
            queue.add(new Edit(code, LOCATION_PATCH, find, path));
        }
    }

    /** Чи є хоть одна відкладена вставка. */
    public static boolean hasPending() {
        synchronized (queue) {
            return !queue.isEmpty();
        }
    }

    /**
     * Витягти всі відкладені вставки у порядку додавання й очистити чергу.
     * Повертає незмінний список (може бути порожнім).
     */
    public static List<Edit> drain() {
        synchronized (queue) {
            if (queue.isEmpty()) return Collections.emptyList();
            List<Edit> out = new ArrayList<>(queue);
            queue.clear();
            return out;
        }
    }

    /** Скасувати всі відкладені вставки (наприклад, якщо користувач закрив чат). */
    public static void clear() {
        synchronized (queue) {
            queue.clear();
        }
    }
}
