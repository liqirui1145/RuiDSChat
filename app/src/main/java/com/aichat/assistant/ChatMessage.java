package com.aichat.assistant;

import org.json.JSONException;
import org.json.JSONObject;

public class ChatMessage {

    public static final int ROLE_USER = 0;
    public static final int ROLE_ASSISTANT = 1;
    public static final int ROLE_ERROR = 2;

    private final int role;
    private final String text;
    private String attachmentName;
    /** 实际发送给模型的内容（可能包含文件全文），不持久化。 */
    private String sendText;

    public ChatMessage(int role, String text) {
        this.role = role;
        this.text = text == null ? "" : text;
    }

    public int getRole() {
        return role;
    }

    public String getText() {
        return text;
    }

    public String getAttachmentName() {
        return attachmentName;
    }

    public void setAttachmentName(String attachmentName) {
        this.attachmentName = attachmentName;
    }

    public String getSendText() {
        return sendText == null ? text : sendText;
    }

    public void setSendText(String sendText) {
        this.sendText = sendText;
    }

    public boolean isUser() {
        return role == ROLE_USER;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("role", role);
        o.put("text", text);
        if (attachmentName != null) {
            o.put("attachment", attachmentName);
        }
        return o;
    }

    public static ChatMessage fromJson(JSONObject o) {
        int role = o.optInt("role", ROLE_ASSISTANT);
        ChatMessage m = new ChatMessage(role, o.optString("text", ""));
        String attachment = o.optString("attachment", null);
        if (attachment != null && attachment.length() > 0) {
            m.setAttachmentName(attachment);
        }
        return m;
    }
}
