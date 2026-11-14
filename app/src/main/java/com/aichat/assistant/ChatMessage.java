package com.aichat.assistant;

import org.json.JSONObject;

public class ChatMessage {

    public static final int ROLE_USER = 0;
    public static final int ROLE_ASSISTANT = 1;
    public static final int ROLE_ERROR = 2;

    private final int role;
    private String text;
    private String attachmentName;
    /** 实际发送给模型的内容（可能包含文件全文），不持久化。 */
    private String sendText;
    /** 是否因长度上限被截断，用于显示「继续生成」入口，不持久化。 */
    private boolean truncated;
    /** 数据库中的行号，0 表示尚未写入。 */
    private long dbId;
    /** 消息时间，用于列表时间戳与日期分隔。 */
    private long timestamp = System.currentTimeMillis();
    /** 深度思考模型返回的思考过程，与正文分开存放。 */
    private String reasoning = "";
    /** 本次回复消耗的输入 / 输出 tokens，由接口 usage 返回。 */
    private int promptTokens;
    private int completionTokens;
    /** 思考过程当前是否展开（仅界面状态，不持久化）。 */
    private boolean reasoningExpanded;

    public ChatMessage(int role, String text) {
        this.role = role;
        this.text = text == null ? "" : text;
        this.timestamp = System.currentTimeMillis();
    }

    public long getDbId() {
        return dbId;
    }

    public void setDbId(long dbId) {
        this.dbId = dbId;
    }

    public int getRole() {
        return role;
    }

    public String getText() {
        return text;
    }

    /** 流式回复时逐步更新内容。 */
    public void setText(String text) {
        this.text = text == null ? "" : text;
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

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getReasoning() {
        return reasoning == null ? "" : reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning == null ? "" : reasoning;
    }

    public boolean hasReasoning() {
        return getReasoning().trim().length() > 0;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(int promptTokens) {
        this.promptTokens = promptTokens < 0 ? 0 : promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(int completionTokens) {
        this.completionTokens = completionTokens < 0 ? 0 : completionTokens;
    }

    public int getTotalTokens() {
        return promptTokens + completionTokens;
    }

    public boolean isReasoningExpanded() {
        return reasoningExpanded;
    }

    public void setReasoningExpanded(boolean reasoningExpanded) {
        this.reasoningExpanded = reasoningExpanded;
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
