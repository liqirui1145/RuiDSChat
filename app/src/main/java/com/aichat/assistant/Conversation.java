package com.aichat.assistant;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 一个独立的对话会话，包含标题与消息列表。 */
public class Conversation {

    private final String id;
    private String title;
    private long updatedAt;
    private List<ChatMessage> messages = new ArrayList<ChatMessage>();

    public Conversation(String id, String title, long updatedAt) {
        this.id = id == null ? UUID.randomUUID().toString() : id;
        this.title = title == null ? "" : title;
        this.updatedAt = updatedAt;
    }

    public static Conversation create() {
        return new Conversation(UUID.randomUUID().toString(), "", System.currentTimeMillis());
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    /** 没有手动标题时，用首条用户消息作为标题。 */
    public String getDisplayTitle() {
        if (title != null && title.trim().length() > 0) {
            return title.trim();
        }
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatMessage.ROLE_USER
                    && message.getText().trim().length() > 0) {
                String text = message.getText().trim().replace('\n', ' ');
                return text.length() > 18 ? text.substring(0, 18) + "…" : text;
            }
        }
        return "新对话";
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages == null ? new ArrayList<ChatMessage>() : messages;
    }

    public int getMessageCount() {
        int count = 0;
        for (ChatMessage message : messages) {
            if (message.getRole() != ChatMessage.ROLE_ERROR) {
                count++;
            }
        }
        return count;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("updatedAt", updatedAt);
        JSONArray array = new JSONArray();
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatMessage.ROLE_ERROR) {
                continue;
            }
            array.put(message.toJson());
        }
        o.put("messages", array);
        return o;
    }

    public static Conversation fromJson(JSONObject o) {
        Conversation conversation = new Conversation(
                o.optString("id", null),
                o.optString("title", ""),
                o.optLong("updatedAt", 0));
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        JSONArray array = o.optJSONArray("messages");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) {
                    messages.add(ChatMessage.fromJson(item));
                }
            }
        }
        conversation.setMessages(messages);
        return conversation;
    }
}
