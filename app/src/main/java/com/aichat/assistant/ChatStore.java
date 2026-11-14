package com.aichat.assistant;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地对话存储：SQLite。
 * 替代原先把全部对话序列化成 JSON 存进 SharedPreferences 的方式，
 * 支持增量写入单条消息，避免历史变长后每次保存都重写整块数据。
 */
public class ChatStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "chat_history.db";
    private static final int DB_VERSION = 2;

    private final Context context;

    public ChatStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        this.context = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE conversations ("
                + "id TEXT PRIMARY KEY,"
                + "title TEXT NOT NULL DEFAULT '',"
                + "display_title TEXT NOT NULL DEFAULT '',"
                + "message_count INTEGER NOT NULL DEFAULT 0,"
                + "updated_at INTEGER NOT NULL DEFAULT 0,"
                + "pinned INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE messages ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "conversation_id TEXT NOT NULL,"
                + "role INTEGER NOT NULL,"
                + "content TEXT NOT NULL DEFAULT '',"
                + "attachment TEXT,"
                + "created_at INTEGER NOT NULL DEFAULT 0,"
                + "prompt_tokens INTEGER NOT NULL DEFAULT 0,"
                + "completion_tokens INTEGER NOT NULL DEFAULT 0,"
                + "reasoning TEXT)");
        db.execSQL("CREATE INDEX idx_messages_conversation ON messages(conversation_id, id)");

        migrateFromPrefs(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // v2.2：会话置顶、消息时间戳、token 用量与思考过程
            db.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN prompt_tokens INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN completion_tokens INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN reasoning TEXT");
            // 历史消息没有精确时间，用所在会话的最后更新时间回填，避免全部显示为升级当天
            db.execSQL("UPDATE messages SET created_at = ("
                    + "SELECT updated_at FROM conversations "
                    + "WHERE conversations.id = messages.conversation_id) "
                    + "WHERE created_at = 0");
        }
    }

    /** 把旧版存在 SharedPreferences 里的对话迁移进数据库，只执行一次。 */
    private void migrateFromPrefs(SQLiteDatabase db) {
        try {
            List<Conversation> legacy = Prefs.readLegacyConversations(context);
            if (legacy.isEmpty()) {
                Prefs.clearLegacyConversations(context);
                return;
            }
            for (Conversation conversation : legacy) {
                writeConversationRow(db, conversation);
                for (ChatMessage message : conversation.getMessages()) {
                    insertMessage(db, conversation.getId(), message);
                }
            }
            Prefs.clearLegacyConversations(context);
        } catch (Exception ignored) {
            // 迁移失败时保留旧数据，下次创建数据库时会再次尝试。
        }
    }

    /** 只读取会话列表（含标题与消息数），不加载具体消息。 */
    public List<Conversation> loadConversations() {
        List<Conversation> list = new ArrayList<Conversation>();
        Cursor cursor = getReadableDatabase().query("conversations",
                new String[]{"id", "title", "display_title", "message_count", "updated_at",
                        "pinned"},
                null, null, null, null, "pinned DESC, updated_at DESC");
        try {
            while (cursor.moveToNext()) {
                Conversation conversation = new Conversation(cursor.getString(0),
                        cursor.getString(1), cursor.getLong(4));
                conversation.setStoredSummary(cursor.getString(2), cursor.getInt(3));
                conversation.setPinned(cursor.getInt(5) != 0);
                list.add(conversation);
            }
        } finally {
            cursor.close();
        }
        return list;
    }

    /**
     * 按关键词搜索会话：匹配标题，或任意一条消息正文。
     * 返回命中的会话 id，界面据此过滤已加载的会话列表。
     */
    public Set<String> searchConversationIds(String keyword) {
        Set<String> ids = new HashSet<String>();
        String query = keyword == null ? "" : keyword.trim();
        if (query.length() == 0) {
            return ids;
        }
        String pattern = "%" + query + "%";
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id FROM conversations WHERE title LIKE ? OR display_title LIKE ?"
                        + " UNION SELECT conversation_id FROM messages WHERE content LIKE ?",
                new String[]{pattern, pattern, pattern});
        try {
            while (cursor.moveToNext()) {
                ids.add(cursor.getString(0));
            }
        } finally {
            cursor.close();
        }
        return ids;
    }

    /** 读取某个会话的全部消息，按写入顺序返回。 */
    public List<ChatMessage> loadMessages(String conversationId) {
        List<ChatMessage> list = new ArrayList<ChatMessage>();
        if (conversationId == null) {
            return list;
        }
        Cursor cursor = getReadableDatabase().query("messages",
                new String[]{"id", "role", "content", "attachment", "created_at",
                        "prompt_tokens", "completion_tokens", "reasoning"},
                "conversation_id=?", new String[]{conversationId}, null, null, "id ASC");
        try {
            while (cursor.moveToNext()) {
                ChatMessage message = new ChatMessage(cursor.getInt(1), cursor.getString(2));
                message.setDbId(cursor.getLong(0));
                String attachment = cursor.getString(3);
                if (attachment != null && attachment.length() > 0) {
                    message.setAttachmentName(attachment);
                }
                long createdAt = cursor.getLong(4);
                if (createdAt > 0) {
                    message.setTimestamp(createdAt);
                }
                message.setPromptTokens(cursor.getInt(5));
                message.setCompletionTokens(cursor.getInt(6));
                message.setReasoning(cursor.getString(7));
                list.add(message);
            }
        } finally {
            cursor.close();
        }
        return list;
    }

    /**
     * 保存会话及其消息：已存在的消息只更新内容，新消息插入，
     * 内存里已被移除的消息同步删除。
     */
    public void saveConversation(Conversation conversation) {
        if (conversation == null) {
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            writeConversationRow(db, conversation);

            // 未加载过消息的会话（例如只在抽屉里看到过）不能同步消息表，
            // 否则会把数据库里已有的消息误删。
            if (!conversation.isMessagesLoaded()) {
                db.setTransactionSuccessful();
                return;
            }

            Set<Long> alive = new HashSet<Long>();
            for (ChatMessage message : conversation.getMessages()) {
                if (message.getRole() == ChatMessage.ROLE_ERROR) {
                    continue;
                }
                ContentValues values = messageValues(conversation.getId(), message);
                long id = message.getDbId();
                if (id > 0) {
                    db.update("messages", values, "id=?", new String[]{String.valueOf(id)});
                    alive.add(id);
                } else {
                    long inserted = db.insert("messages", null, values);
                    if (inserted > 0) {
                        message.setDbId(inserted);
                        alive.add(inserted);
                    }
                }
            }

            List<Long> dead = new ArrayList<Long>();
            Cursor cursor = db.query("messages", new String[]{"id"},
                    "conversation_id=?", new String[]{conversation.getId()}, null, null, null);
            try {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(0);
                    if (!alive.contains(id)) {
                        dead.add(id);
                    }
                }
            } finally {
                cursor.close();
            }
            for (Long id : dead) {
                db.delete("messages", "id=?", new String[]{String.valueOf(id)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 只更新会话标题等元信息，不触碰消息表。 */
    public void updateConversationMeta(Conversation conversation) {
        if (conversation == null) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("title", conversation.getTitle());
        values.put("display_title", conversation.getDisplayTitle());
        values.put("message_count", conversation.getMessageCount());
        values.put("updated_at", conversation.getUpdatedAt());
        values.put("pinned", conversation.isPinned() ? 1 : 0);
        getWritableDatabase().update("conversations", values,
                "id=?", new String[]{conversation.getId()});
    }

    /** 清空某个会话的全部消息，保留会话本身。 */
    public void clearMessages(String conversationId) {
        if (conversationId == null) {
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("messages", "conversation_id=?", new String[]{conversationId});
            ContentValues values = new ContentValues();
            values.put("message_count", 0);
            values.put("display_title", "");
            db.update("conversations", values, "id=?", new String[]{conversationId});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void deleteConversation(String conversationId) {
        if (conversationId == null) {
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("messages", "conversation_id=?", new String[]{conversationId});
            db.delete("conversations", "id=?", new String[]{conversationId});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void writeConversationRow(SQLiteDatabase db, Conversation conversation) {
        ContentValues values = new ContentValues();
        values.put("id", conversation.getId());
        values.put("title", conversation.getTitle());
        values.put("display_title", conversation.getDisplayTitle());
        values.put("message_count", conversation.getMessageCount());
        values.put("updated_at", conversation.getUpdatedAt());
        values.put("pinned", conversation.isPinned() ? 1 : 0);
        db.insertWithOnConflict("conversations", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static ContentValues messageValues(String conversationId, ChatMessage message) {
        ContentValues values = new ContentValues();
        values.put("conversation_id", conversationId);
        values.put("role", message.getRole());
        values.put("content", message.getText());
        values.put("attachment", message.getAttachmentName());
        values.put("created_at", message.getTimestamp());
        values.put("prompt_tokens", message.getPromptTokens());
        values.put("completion_tokens", message.getCompletionTokens());
        values.put("reasoning", message.getReasoning());
        return values;
    }

    private static void insertMessage(SQLiteDatabase db, String conversationId, ChatMessage message) {
        if (message.getRole() == ChatMessage.ROLE_ERROR) {
            return;
        }
        long id = db.insert("messages", null, messageValues(conversationId, message));
        if (id > 0) {
            message.setDbId(id);
        }
    }
}
