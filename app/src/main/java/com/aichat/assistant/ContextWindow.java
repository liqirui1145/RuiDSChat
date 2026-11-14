package com.aichat.assistant;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文窗口裁剪：只把最近的若干条历史发给模型，
 * 既控制请求体积，也避免超长历史导致接口报错或费用过高。
 */
public final class ContextWindow {

    /** 无论设置多少条，总字符数不超过该值（约合 2 万 tokens 以内）。 */
    private static final int MAX_CONTEXT_CHARS = 32000;
    /** 至少保留最近两条消息（一问一答），保证上下文连贯。 */
    private static final int MIN_KEEP = 2;

    private ContextWindow() {
    }

    public static List<ChatMessage> trim(List<ChatMessage> history, int maxMessages) {
        List<ChatMessage> clean = new ArrayList<ChatMessage>();
        if (history != null) {
            for (ChatMessage message : history) {
                if (message.getRole() != ChatMessage.ROLE_ERROR) {
                    clean.add(message);
                }
            }
        }
        if (clean.isEmpty()) {
            return clean;
        }

        int start = 0;
        if (maxMessages > 0 && clean.size() > maxMessages) {
            start = clean.size() - maxMessages;
        }

        int chars = 0;
        for (int i = start; i < clean.size(); i++) {
            chars += clean.get(i).getText().length();
        }
        while (start < clean.size() - MIN_KEEP && chars > MAX_CONTEXT_CHARS) {
            chars -= clean.get(start).getText().length();
            start++;
        }

        // 裁剪后必须以用户消息开头，避免首条是助手回复造成接口异常
        while (start < clean.size() && clean.get(start).getRole() != ChatMessage.ROLE_USER) {
            start++;
        }
        return new ArrayList<ChatMessage>(clean.subList(start, clean.size()));
    }
}
