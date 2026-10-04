package com.ccs.wiredroid.db;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import com.ccs.wiredroid.util.SecureValueStore;

/**
 * Saved connections, kept as a JSON array in their own {@link SharedPreferences}
 * file.
 *
 * <p>A separate file rather than the app-wide preferences: these entries can
 * hold a credential, and keeping them apart means "clear saved connections"
 * stays a one-line operation that cannot take a user's editor settings with
 * it.</p>
 *
 * <p>Passwords are written only for entries whose {@code savePassword} flag is
 * set. They are stored in a Keystore-backed value store; old cleartext JSON
 * values are accepted once for migration.</p>
 */
public final class DbConnectionStore {

    private static final String PREFS = "com.ccs.wiredroid.db.connections";
    private static final String KEY_LIST = "connections";

    private final SharedPreferences prefs;
    private final Context context;

    public DbConnectionStore(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.prefs = context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<DbConnection> load() {
        List<DbConnection> out = new ArrayList<>();
        String raw = prefs.getString(KEY_LIST, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    DbConnection c = DbConnection.fromJson(o);
                    if (c.savePassword) {
                        String secure = SecureValueStore.get(context, PREFS, "password_" + c.id);
                        if (secure != null) {
                            c.password = secure;
                        } else if (c.password != null && !c.password.isEmpty()) {
                            SecureValueStore.put(context, PREFS, "password_" + c.id, c.password);
                        }
                    }
                    out.add(c);
                }
            }
        } catch (JSONException ignored) {
            // A corrupted blob is not worth losing the screen over; start empty.
        }
        return out;
    }

    public void saveAll(List<DbConnection> connections) {
        JSONArray arr = new JSONArray();
        for (DbConnection c : connections) {
            try {
                JSONObject json = c.toJson();
                if (c.savePassword && SecureValueStore.put(context, PREFS,
                        "password_" + c.id, c.password == null ? "" : c.password)) {
                    json.remove("password");
                    json.put("passwordSecure", true);
                }
                arr.put(json);
            } catch (JSONException ignored) {
                // Skip the one entry that cannot be encoded rather than the lot.
            }
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply();
    }

    /** Replaces the entry with the same id, or appends it. */
    public void upsert(DbConnection c) {
        List<DbConnection> all = load();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(c.id)) {
                all.set(i, c);
                saveAll(all);
                return;
            }
        }
        all.add(c);
        saveAll(all);
    }

    public void delete(String id) {
        List<DbConnection> all = load();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                all.remove(i);
                prefs.edit().remove("password_" + id).apply();
                break;
            }
        }
        saveAll(all);
    }
}
