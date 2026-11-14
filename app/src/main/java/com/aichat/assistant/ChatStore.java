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
    private static final int DB_VERSION = 1;

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
                + "updated_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE messages ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "conversation_id TEXT NOT NULL,"
                + "role INTEGER NOT NULL,"
                + "content TEXT NOT NULL DEFAULT '',"
                + "attachment TEXT)");
        db.execSQL("CREATE INDEX idx_messages_conversation ON messages(conversation_id, id)");

        migrateFromPrefs(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 目前只有版本 1，后续升级在此补充迁移逻辑。
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
                new String[]{"id", "title", "display_title", "message_count", "updated_at"},
                null, null, null, null, "updated_at DESC");
        try {
            while (cursor.moveToNext()) {
                Conversation conversation = new Conversation(cursor.getString(0),
                        cursor.getString(1), cursor.getLong(4));
                conversation.setStoredSummary(cursor.getString(2), cursor.getInt(3));
                list.add(conversation);
            }
        } finally {
            cursor.close();
        }
        return list;
    }

    /** 读取某个会话的全部消息，按写入顺序返回。 */
    public List<ChatMessage> loadMessages(String conversationId) {
        List<ChatMessage> list = new ArrayList<ChatMessage>();
        if (conversationId == null) {
            return list;
        }
        Cursor cursor = getReadableDatabase().query("messages",
                new String[]{"id", "role", "content", "attachment"},
                "conversation_id=?", new String[]{conversationId}, null, null, "id ASC");
        try {
            while (cursor.moveToNext()) {
                ChatMessage message = new ChatMessage(cursor.getInt(1), cursor.getString(2));
                message.setDbId(cursor.getLong(0));
                String attachment = cursor.getString(3);
                if (attachment != null && attachment.length() > 0) {
                    message.setAttachmentName(attachment);
                }
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
        getWritableDatabase().update("conversations", values,
                "id=?", new String[]{conversation.getId()});
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
        db.insertWithOnConflict("conversations", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static ContentValues messageValues(String conversationId, ChatMessage message) {
        ContentValues values = new ContentValues();
        values.put("conversation_id", conversationId);
        values.put("role", message.getRole());
        values.put("content", message.getText());
        values.put("attachment", message.getAttachmentName());
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
