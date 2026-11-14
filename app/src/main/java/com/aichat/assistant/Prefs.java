package com.aichat.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class Prefs {

    private static final String FILE_NAME = "ai_chat_prefs";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_CONVERSATIONS = "conversations";
    private static final String KEY_CURRENT_CONVERSATION = "current_conversation_id";

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_CHAT_MODEL = "deepseek-chat";
    public static final String DEFAULT_VISION_MODEL = "deepseek-flash";
    public static final String DEFAULT_SYSTEM_PROMPT = "你是一个乐于助人的 AI 助手，请使用简体中文回答。";

    private Prefs() {
    }

    private static SharedPreferences sp(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    public static String getApiKey(Context context) {
        return sp(context).getString("api_key", "");
    }

    public static void setApiKey(Context context, String value) {
        sp(context).edit().putString("api_key", value).apply();
    }

    public static String getBaseUrl(Context context) {
        String value = sp(context).getString("base_url", DEFAULT_BASE_URL);
        return value == null || value.trim().length() == 0 ? DEFAULT_BASE_URL : value.trim();
    }

    public static void setBaseUrl(Context context, String value) {
        sp(context).edit().putString("base_url", value).apply();
    }

    public static String getChatModel(Context context) {
        String value = sp(context).getString("chat_model", DEFAULT_CHAT_MODEL);
        return value == null || value.trim().length() == 0 ? DEFAULT_CHAT_MODEL : value.trim();
    }

    public static void setChatModel(Context context, String value) {
        sp(context).edit().putString("chat_model", value).apply();
    }

    public static String getVisionBaseUrl(Context context) {
        return trim(sp(context).getString("vision_base_url", ""));
    }

    public static void setVisionBaseUrl(Context context, String value) {
        sp(context).edit().putString("vision_base_url", value).apply();
    }

    public static String getVisionModel(Context context) {
        return trim(sp(context).getString("vision_model", ""));
    }

    public static void setVisionModel(Context context, String value) {
        sp(context).edit().putString("vision_model", value).apply();
    }

    public static String getVisionKey(Context context) {
        return trim(sp(context).getString("vision_key", ""));
    }

    public static void setVisionKey(Context context, String value) {
        sp(context).edit().putString("vision_key", value).apply();
    }

    public static String getSystemPrompt(Context context) {
        String value = sp(context).getString("system_prompt", DEFAULT_SYSTEM_PROMPT);
        return value == null ? "" : value.trim();
    }

    public static void setSystemPrompt(Context context, String value) {
        sp(context).edit().putString("system_prompt", value).apply();
    }

    /** 最大回复 tokens，0 表示不传该参数、使用接口默认值。 */
    public static int getMaxTokens(Context context) {
        return sp(context).getInt("max_tokens", 0);
    }

    public static void setMaxTokens(Context context, int value) {
        sp(context).edit().putInt("max_tokens", value < 0 ? 0 : value).apply();
    }

    /** 每次请求携带的历史消息条数，0 表示不限制（仍受字符预算限制）。 */
    public static int getContextMessages(Context context) {
        return sp(context).getInt("context_messages", 20);
    }

    public static void setContextMessages(Context context, int value) {
        sp(context).edit().putInt("context_messages", value < 0 ? 0 : value).apply();
    }

    public static boolean isDarkMode(Context context) {
        return sp(context).getBoolean("dark_mode", false);
    }

    public static void setDarkMode(Context context, boolean value) {
        sp(context).edit().putBoolean("dark_mode", value).apply();
    }

    /** 读取旧版存放在 SharedPreferences 里的对话数据，仅供迁移到 SQLite 使用。 */
    static List<Conversation> readLegacyConversations(Context context) {
        List<Conversation> list = new ArrayList<Conversation>();
        String raw = sp(context).getString(KEY_CONVERSATIONS, null);
        if (raw != null && raw.length() > 0) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.optJSONObject(i);
                    if (o != null) {
                        list.add(Conversation.fromJson(o));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (list.isEmpty()) {
            List<ChatMessage> old = new ArrayList<ChatMessage>();
            String history = sp(context).getString(KEY_HISTORY, null);
            if (history != null && history.length() > 0) {
                try {
                    JSONArray array = new JSONArray(history);
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject o = array.optJSONObject(i);
                        if (o != null) {
                            old.add(ChatMessage.fromJson(o));
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            if (!old.isEmpty()) {
                Conversation migrated = Conversation.create();
                migrated.setTitle("历史对话");
                migrated.setUpdatedAt(System.currentTimeMillis());
                migrated.setMessages(old);
                list.add(migrated);
            }
        }
        return list;
    }

    static void clearLegacyConversations(Context context) {
        sp(context).edit()
                .remove(KEY_CONVERSATIONS)
                .remove(KEY_HISTORY)
                .apply();
    }

    public static String getCurrentConversationId(Context context) {
        return trim(sp(context).getString(KEY_CURRENT_CONVERSATION, ""));
    }

    public static void setCurrentConversationId(Context context, String id) {
        sp(context).edit().putString(KEY_CURRENT_CONVERSATION, id).apply();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
