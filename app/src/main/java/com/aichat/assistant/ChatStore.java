package com.aichat.assistant;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 本地对话存储：SQLite。
 * 替代原先把全部对话序列化成 JSON 存进 SharedPreferences 的方式，
 * 支持增量写入单条消息，避免历史变长后每次保存都重写整块数据。
 */
public class ChatStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "chat_history.db";
    private static final int DB_VERSION = 3;

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
                + "pinned INTEGER NOT NULL DEFAULT 0,"
                + "archived INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE messages ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "conversation_id TEXT NOT NULL,"
                + "role INTEGER NOT NULL,"
                + "content TEXT NOT NULL DEFAULT '',"
                + "attachment TEXT,"
                + "created_at INTEGER NOT NULL DEFAULT 0,"
                + "prompt_tokens INTEGER NOT NULL DEFAULT 0,"
                + "completion_tokens INTEGER NOT NULL DEFAULT 0,"
                + "reasoning TEXT,"
                + "error_detail TEXT)");
        db.execSQL("CREATE INDEX idx_messages_conversation ON messages(conversation_id, id)");
        db.execSQL("CREATE INDEX idx_messages_created ON messages(created_at)");

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
        if (oldVersion < 3) {
            // v2.4：会话归档、失败消息的原始错误详情
            db.execSQL("ALTER TABLE conversations ADD COLUMN archived INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN error_detail TEXT");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_created ON messages(created_at)");
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
                        "pinned", "archived"},
                null, null, null, null, "pinned DESC, updated_at DESC");
        try {
            while (cursor.moveToNext()) {
                Conversation conversation = new Conversation(cursor.getString(0),
                        cursor.getString(1), cursor.getLong(4));
                conversation.setStoredSummary(cursor.getString(2), cursor.getInt(3));
                conversation.setPinned(cursor.getInt(5) != 0);
                conversation.setArchived(cursor.getInt(6) != 0);
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

    /** 单条消息的搜索命中结果。 */
    public static final class MessageHit {
        public final long messageId;
        public final String conversationId;
        public final int role;
        public final String title;
        public final String snippet;
        public final long timestamp;

        MessageHit(long messageId, String conversationId, int role, String title,
                   String snippet, long timestamp) {
            this.messageId = messageId;
            this.conversationId = conversationId;
            this.role = role;
            this.title = title == null ? "" : title;
            this.snippet = snippet == null ? "" : snippet;
            this.timestamp = timestamp;
        }
    }

    /** 全局消息搜索：返回命中的消息，附带会话标题与上下文片段。 */
    public List<MessageHit> searchMessages(String keyword, int limit) {
        List<MessageHit> hits = new ArrayList<MessageHit>();
        String query = keyword == null ? "" : keyword.trim();
        if (query.length() == 0) {
            return hits;
        }
        String pattern = "%" + query + "%";
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT m.id, m.conversation_id, m.role, m.content, m.created_at,"
                        + " COALESCE(NULLIF(c.display_title, ''), NULLIF(c.title, ''), '新对话')"
                        + " FROM messages m LEFT JOIN conversations c ON c.id = m.conversation_id"
                        + " WHERE m.role != ? AND m.content LIKE ?"
                        + " ORDER BY m.id DESC LIMIT ?",
                new String[]{String.valueOf(ChatMessage.ROLE_ERROR), pattern,
                        String.valueOf(limit <= 0 ? 50 : limit)});
        try {
            while (cursor.moveToNext()) {
                hits.add(new MessageHit(cursor.getLong(0), cursor.getString(1),
                        cursor.getInt(2), cursor.getString(5),
                        buildSnippet(cursor.getString(3), query), cursor.getLong(4)));
            }
        } finally {
            cursor.close();
        }
        return hits;
    }

    /** 截取关键词附近的片段，便于在搜索结果里快速判断上下文。 */
    private static String buildSnippet(String content, String keyword) {
        if (content == null) {
            return "";
        }
        String flat = content.replace('\n', ' ').replace('\r', ' ').trim();
        String lower = flat.toLowerCase(Locale.CHINA);
        int index = lower.indexOf(keyword.toLowerCase(Locale.CHINA));
        int start = index < 0 ? 0 : Math.max(0, index - 16);
        int end = Math.min(flat.length(), start + 68);
        String snippet = flat.substring(start, end);
        if (start > 0) {
            snippet = "…" + snippet;
        }
        if (end < flat.length()) {
            snippet = snippet + "…";
        }
        return snippet;
    }

    /** 读取某个会话的全部消息，按写入顺序返回。 */
    public List<ChatMessage> loadMessages(String conversationId) {
        List<ChatMessage> list = new ArrayList<ChatMessage>();
        if (conversationId == null) {
            return list;
        }
        Cursor cursor = getReadableDatabase().query("messages",
                new String[]{"id", "role", "content", "attachment", "created_at",
                        "prompt_tokens", "completion_tokens", "reasoning", "error_detail"},
                "conversation_id=?", new String[]{conversationId}, null, null, "id ASC");
        try {
            while (cursor.moveToNext()) {
                ChatMessage message = new ChatMessage(cursor.getInt(1),
                        cleanNullDump(cursor.getString(2)));
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
                message.setReasoning(cleanNullDump(cursor.getString(7)));
                message.setErrorDetail(cursor.getString(8));
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
        values.put("archived", conversation.isArchived() ? 1 : 0);
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

    /** token 用量累计值。 */
    public static final class UsageTotals {
        public long promptTokens;
        public long completionTokens;

        public long total() {
            return promptTokens + completionTokens;
        }
    }

    /** 按会话聚合的 token 用量。 */
    public static final class UsageRow {
        public final String title;
        public final long promptTokens;
        public final long completionTokens;

        UsageRow(String title, long promptTokens, long completionTokens) {
            this.title = title == null ? "新对话" : title;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

        public long total() {
            return promptTokens + completionTokens;
        }
    }

    /** 统计某个时间点之后的 token 用量；传入 0 表示全部历史。 */
    public UsageTotals loadUsageTotals(long sinceMillis) {
        UsageTotals totals = new UsageTotals();
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT SUM(prompt_tokens), SUM(completion_tokens) FROM messages"
                        + " WHERE created_at >= ?",
                new String[]{String.valueOf(sinceMillis <= 0 ? 0 : sinceMillis)});
        try {
            if (cursor.moveToFirst()) {
                totals.promptTokens = cursor.getLong(0);
                totals.completionTokens = cursor.getLong(1);
            }
        } finally {
            cursor.close();
        }
        return totals;
    }

    /** 按会话汇总 token 用量，用于「用量历史」里的排行。 */
    public List<UsageRow> loadUsageByConversation(int limit) {
        List<UsageRow> rows = new ArrayList<UsageRow>();
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COALESCE(NULLIF(c.display_title, ''), NULLIF(c.title, ''), '新对话'),"
                        + " SUM(m.prompt_tokens), SUM(m.completion_tokens)"
                        + " FROM messages m LEFT JOIN conversations c ON c.id = m.conversation_id"
                        + " WHERE m.prompt_tokens > 0 OR m.completion_tokens > 0"
                        + " GROUP BY m.conversation_id"
                        + " ORDER BY SUM(m.prompt_tokens) + SUM(m.completion_tokens) DESC"
                        + " LIMIT ?",
                new String[]{String.valueOf(limit <= 0 ? 10 : limit)});
        try {
            while (cursor.moveToNext()) {
                rows.add(new UsageRow(cursor.getString(0), cursor.getLong(1), cursor.getLong(2)));
            }
        } finally {
            cursor.close();
        }
        return rows;
    }

    /** 修复旧版本把 JSON null 拼成「nullnull…」的历史消息。 */
    private static String cleanNullDump(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > 0 && trimmed.replace("null", "").trim().length() == 0) {
            return "";
        }
        return value;
    }

    private static void writeConversationRow(SQLiteDatabase db, Conversation conversation) {
        ContentValues values = new ContentValues();
        values.put("id", conversation.getId());
        values.put("title", conversation.getTitle());
        values.put("display_title", conversation.getDisplayTitle());
        values.put("message_count", conversation.getMessageCount());
        values.put("updated_at", conversation.getUpdatedAt());
        values.put("pinned", conversation.isPinned() ? 1 : 0);
        values.put("archived", conversation.isArchived() ? 1 : 0);
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
        values.put("error_detail", message.getErrorDetail());
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
