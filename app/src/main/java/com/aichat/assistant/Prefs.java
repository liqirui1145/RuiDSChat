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
        return readSecret(context, "api_key");
    }

    /** 保存 API Key（Keystore 加密）。返回 false 表示系统 Keystore 不可用，已退回明文保存。 */
    public static boolean setApiKey(Context context, String value) {
        return writeSecret(context, "api_key", value);
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
        return readSecret(context, "vision_key");
    }

    /** 保存图片接口 Key（Keystore 加密）。返回 false 表示已退回明文保存。 */
    public static boolean setVisionKey(Context context, String value) {
        return writeSecret(context, "vision_key", value);
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

    /** 推理强度：0 关闭，1 极低，2 低，3 中，4 高。 */
    public static int getReasoningLevel(Context context) {
        int value = sp(context).getInt("reasoning_level", 0);
        return value < 0 ? 0 : (value > 4 ? 4 : value);
    }

    public static void setReasoningLevel(Context context, int value) {
        sp(context).edit().putInt("reasoning_level", value < 0 ? 0 : (value > 4 ? 4 : value))
                .apply();
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

    /** GitHub 个人访问令牌（Keystore 加密保存）。 */
    public static String getGitHubToken(Context context) {
        return readSecret(context, "github_token");
    }

    public static boolean setGitHubToken(Context context, String value) {
        return writeSecret(context, "github_token", value);
    }

    /** GitHub OAuth App 的 Client ID（可公开，不是密钥）。 */
    public static String getGitHubClientId(Context context) {
        String value = trim(sp(context).getString("github_client_id", ""));
        return value.length() == 0 ? GitHubAuth.DEFAULT_CLIENT_ID : value;
    }

    public static void setGitHubClientId(Context context, String value) {
        sp(context).edit().putString("github_client_id", trim(value)).apply();
    }

    /** 授权登录后缓存的 GitHub 登录名，仅用于展示。 */
    public static String getGitHubLogin(Context context) {
        return trim(sp(context).getString("github_login", ""));
    }

    public static void setGitHubLogin(Context context, String value) {
        sp(context).edit().putString("github_login", trim(value)).apply();
    }

    /** 默认仓库，格式 owner/repo。 */
    public static String getGitHubRepo(Context context) {
        return trim(sp(context).getString("github_repo", ""));
    }

    public static void setGitHubRepo(Context context, String value) {
        sp(context).edit().putString("github_repo", trim(value)).apply();
    }

    public static String getGitHubBranch(Context context) {
        String value = trim(sp(context).getString("github_branch", ""));
        return value.length() == 0 ? "main" : value;
    }

    public static void setGitHubBranch(Context context, String value) {
        String branch = trim(value);
        sp(context).edit().putString("github_branch",
                branch.length() == 0 ? "main" : branch).apply();
    }

    /** 当前选中的技能 id，空字符串表示普通对话。 */
    public static String getSkillId(Context context) {
        String value = trim(sp(context).getString("skill_id", ""));
        if (value.length() == 0 && sp(context).getBoolean("github_mode", false)) {
            // 兼容旧版的 GitHub 模式开关
            return "github";
        }
        return value;
    }

    public static void setSkillId(Context context, String value) {
        sp(context).edit().putString("skill_id", trim(value)).apply();
    }

    public static String getCustomSkillName(Context context) {
        return trim(sp(context).getString("custom_skill_name", ""));
    }

    public static void setCustomSkillName(Context context, String value) {
        sp(context).edit().putString("custom_skill_name", trim(value)).apply();
    }

    public static String getCustomSkillPrompt(Context context) {
        String value = sp(context).getString("custom_skill_prompt", "");
        return value == null ? "" : value;
    }

    public static void setCustomSkillPrompt(Context context, String value) {
        String prompt = value == null ? "" : value.trim();
        sp(context).edit().putString("custom_skill_prompt", prompt).apply();
    }

    /** 对话里是否开启 GitHub 助手模式（旧版开关，仅用于迁移）。 */
    public static boolean isGitHubMode(Context context) {
        return sp(context).getBoolean("github_mode", false);
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

    /** 读取按会话保存的输入框草稿。 */
    public static String getDraft(Context context, String conversationId) {
        if (conversationId == null || conversationId.length() == 0) {
            return "";
        }
        return sp(context).getString("draft_" + conversationId, "");
    }

    public static void setDraft(Context context, String conversationId, String text) {
        if (conversationId == null || conversationId.length() == 0) {
            return;
        }
        String value = text == null ? "" : text;
        if (value.trim().length() == 0) {
            sp(context).edit().remove("draft_" + conversationId).apply();
        } else {
            sp(context).edit().putString("draft_" + conversationId, value).apply();
        }
    }

    /** 输入 / 输出 tokens 的单价，单位：元 / 百万 tokens。 */
    public static float getInputPrice(Context context) {
        return sp(context).getFloat("price_input", 2f);
    }

    public static void setInputPrice(Context context, float value) {
        sp(context).edit().putFloat("price_input", value < 0 ? 0f : value).apply();
    }

    public static float getOutputPrice(Context context) {
        return sp(context).getFloat("price_output", 8f);
    }

    public static void setOutputPrice(Context context, float value) {
        sp(context).edit().putFloat("price_output", value < 0 ? 0f : value).apply();
    }

    private static String readSecret(Context context, String key) {
        String stored = sp(context).getString(key, "");
        if (stored == null || stored.length() == 0) {
            return "";
        }
        if (stored.startsWith("aes:") || stored.startsWith("rsa:")) {
            String plain = ApiKeyStore.decrypt(context, stored);
            if (plain.length() == 0) {
                // 密钥被系统清除或数据损坏：丢弃密文，提示用户重新填写
                sp(context).edit().remove(key).apply();
            }
            return plain;
        }
        // 旧版明文：首次读取时自动迁移为 Keystore 密文
        String encrypted = ApiKeyStore.encrypt(context, stored);
        if (encrypted != null && encrypted.length() > 0) {
            sp(context).edit().putString(key, encrypted).apply();
        }
        return stored.trim();
    }

    private static boolean writeSecret(Context context, String key, String value) {
        String plain = value == null ? "" : value.trim();
        if (plain.length() == 0) {
            sp(context).edit().remove(key).apply();
            return true;
        }
        String encrypted = ApiKeyStore.encrypt(context, plain);
        if (encrypted == null || encrypted.length() == 0) {
            // Keystore 异常时退回明文，保证配置不丢失
            sp(context).edit().putString(key, plain).apply();
            return false;
        }
        sp(context).edit().putString(key, encrypted).apply();
        return true;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
