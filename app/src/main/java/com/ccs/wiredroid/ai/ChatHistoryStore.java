package com.ccs.wiredroid.ai;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Conversations with the assistant, kept on disk.
 *
 * <p>The chat used to live in one in-memory list: leaving the screen threw the
 * conversation away, and there was no way back to anything said yesterday. This
 * holds the same messages the model is sent, so reopening a conversation
 * restores what the model knows, not merely what the screen showed.</p>
 *
 * <p>SQLite rather than preferences because the interesting operations are
 * "the newest twenty conversations" and "the messages of this one" — reads that
 * want an index, over rows that grow without bound. Serialising the lot into a
 * preference string would rewrite every conversation on every message.</p>
 *
 * <p>Stored unencrypted in the app's private database. That matches how the app
 * already treats the user's source — the projects themselves sit in plain files
 * — and differs deliberately from the API key, which is a credential and is
 * encrypted. Nothing here leaves the device except what is sent to the model.</p>
 */
public final class ChatHistoryStore {

    /** One saved conversation, without its messages. */
    public static final class Conversation {
        public final long id;
        public final String title;
        public final long updatedAt;
        public final int messageCount;

        Conversation(long id, String title, long updatedAt, int messageCount) {
            this.id = id;
            this.title = title;
            this.updatedAt = updatedAt;
            this.messageCount = messageCount;
        }
    }

    private static final String DB_NAME = "ai_chat_history.db";
    private static final int DB_VERSION = 1;

    private static final String T_CONVERSATION = "conversation";
    private static final String T_MESSAGE = "message";

    /** Longest title kept; the rest of the first question is not a title. */
    private static final int TITLE_MAX = 80;

    private final Helper helper;

    public ChatHistoryStore(Context context) {
        helper = new Helper(context.getApplicationContext());
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + T_CONVERSATION + " ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "title TEXT NOT NULL DEFAULT '', "
                    + "created_at INTEGER NOT NULL, "
                    + "updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE " + T_MESSAGE + " ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "conversation_id INTEGER NOT NULL, "
                    + "from_user INTEGER NOT NULL, "
                    + "body TEXT NOT NULL, "
                    + "created_at INTEGER NOT NULL, "
                    // Deleting a conversation must not leave its messages behind;
                    // enforced by the database rather than by remembering to do it.
                    + "FOREIGN KEY (conversation_id) REFERENCES " + T_CONVERSATION + "(id) "
                    + "ON DELETE CASCADE)");
            // The list screen orders by this, and the message view filters by it.
            db.execSQL("CREATE INDEX idx_conversation_updated ON "
                    + T_CONVERSATION + "(updated_at DESC)");
            db.execSQL("CREATE INDEX idx_message_conversation ON "
                    + T_MESSAGE + "(conversation_id, id)");
        }

        @Override
        public void onConfigure(SQLiteDatabase db) {
            super.onConfigure(db);
            // Off by default on Android, so the cascade above would silently do
            // nothing without this.
            db.setForeignKeyConstraintsEnabled(true);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // No migration to write yet. Dropping is only acceptable while this is
            // version 1 and nothing has shipped; the day a version 2 exists, this
            // has to become a real ALTER.
            db.execSQL("DROP TABLE IF EXISTS " + T_MESSAGE);
            db.execSQL("DROP TABLE IF EXISTS " + T_CONVERSATION);
            onCreate(db);
        }
    }

    /** Opens a new conversation and returns its id, or -1 if it could not. */
    public long startConversation() {
        try {
            long now = System.currentTimeMillis();
            ContentValues values = new ContentValues();
            values.put("title", "");
            values.put("created_at", now);
            values.put("updated_at", now);
            return helper.getWritableDatabase().insert(T_CONVERSATION, null, values);
        } catch (Throwable failure) {
            return -1;
        }
    }

    /**
     * Appends one message and stamps the conversation.
     *
     * <p>The first thing the user says becomes the title: it is what they will
     * recognise in the list, and asking them to name a conversation before
     * having it is a worse trade.</p>
     */
    public void addMessage(long conversationId, boolean fromUser, String body) {
        if (conversationId <= 0 || body == null) return;
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            long now = System.currentTimeMillis();

            ContentValues message = new ContentValues();
            message.put("conversation_id", conversationId);
            message.put("from_user", fromUser ? 1 : 0);
            message.put("body", body);
            message.put("created_at", now);
            db.insert(T_MESSAGE, null, message);

            ContentValues touch = new ContentValues();
            touch.put("updated_at", now);
            if (fromUser) {
                db.execSQL("UPDATE " + T_CONVERSATION
                                + " SET updated_at = ?, title = CASE WHEN title = '' THEN ? ELSE title END"
                                + " WHERE id = ?",
                        new Object[]{now, titleFrom(body), conversationId});
            } else {
                db.update(T_CONVERSATION, touch, "id = ?",
                        new String[]{String.valueOf(conversationId)});
            }
        } catch (Throwable ignored) {
            // A conversation that fails to persist must not break the one on screen.
        }
    }

    /** Messages of one conversation, oldest first. */
    @NonNull
    public List<GeminiService.ChatMessage> loadMessages(long conversationId) {
        List<GeminiService.ChatMessage> out = new ArrayList<>();
        if (conversationId <= 0) return out;
        try (Cursor c = helper.getReadableDatabase().query(T_MESSAGE,
                new String[]{"from_user", "body"}, "conversation_id = ?",
                new String[]{String.valueOf(conversationId)}, null, null, "id ASC")) {
            while (c.moveToNext()) {
                out.add(new GeminiService.ChatMessage(c.getString(1), c.getInt(0) == 1));
            }
        } catch (Throwable ignored) {
            // Fall through with whatever was read.
        }
        return out;
    }

    /** Saved conversations, newest first. Empty ones are not listed. */
    @NonNull
    public List<Conversation> listConversations(int limit) {
        List<Conversation> out = new ArrayList<>();
        String sql = "SELECT c.id, c.title, c.updated_at, COUNT(m.id) AS n"
                + " FROM " + T_CONVERSATION + " c"
                + " LEFT JOIN " + T_MESSAGE + " m ON m.conversation_id = c.id"
                + " GROUP BY c.id HAVING n > 0"
                + " ORDER BY c.updated_at DESC LIMIT ?";
        try (Cursor c = helper.getReadableDatabase().rawQuery(sql,
                new String[]{String.valueOf(Math.max(1, limit))})) {
            while (c.moveToNext()) {
                out.add(new Conversation(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3)));
            }
        } catch (Throwable ignored) {
            // Fall through with whatever was read.
        }
        return out;
    }

    /** The conversation to reopen on launch, or -1 when there is none worth it. */
    public long mostRecentNonEmpty() {
        List<Conversation> recent = listConversations(1);
        return recent.isEmpty() ? -1 : recent.get(0).id;
    }

    public void deleteConversation(long conversationId) {
        if (conversationId <= 0) return;
        try {
            helper.getWritableDatabase().delete(T_CONVERSATION, "id = ?",
                    new String[]{String.valueOf(conversationId)});
        } catch (Throwable ignored) {}
    }

    /** Removes conversations that were opened and never used. */
    public void discardIfEmpty(long conversationId) {
        if (conversationId <= 0) return;
        try {
            helper.getWritableDatabase().execSQL(
                    "DELETE FROM " + T_CONVERSATION + " WHERE id = ? AND NOT EXISTS ("
                            + "SELECT 1 FROM " + T_MESSAGE + " WHERE conversation_id = ?)",
                    new Object[]{conversationId, conversationId});
        } catch (Throwable ignored) {}
    }

    /** First line of the message, clipped — what the list shows. */
    static String titleFrom(@Nullable String body) {
        if (body == null) return "";
        String title = body.trim().replaceAll("\\s+", " ");
        if (title.length() > TITLE_MAX) {
            title = title.substring(0, TITLE_MAX).trim() + "…";
        }
        return title;
    }
}
