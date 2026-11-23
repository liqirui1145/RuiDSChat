package com.aichat.assistant;

import android.content.Context;

import org.json.JSONObject;

/**
 * 可插拔技能：定义技能的系统提示、可用工具以及工具执行方式。
 * 由 {@link AgentRunner} 统一驱动「规划 → 调用工具 → 回传结果」循环。
 */
public interface AgentSkill {

    /** 技能标识，用于持久化选择。 */
    String id();

    /** 显示名称，用于按钮与操作记录。 */
    String label();

    /** 是否需要模型输出 JSON 并调用工具；false 表示纯提示词技能。 */
    boolean usesTools();

    /** 技能的系统提示词。 */
    String systemPrompt(Context context) throws ApiException;

    /** 开始前的检查，例如是否已登录 GitHub。 */
    void checkReady(Context context) throws ApiException;

    /** 执行一次工具调用，返回给模型的结果文本。 */
    String execute(Context context, String tool, JSONObject args) throws ApiException;

    /** 生成可读的操作描述，用于进度与日志。 */
    String describe(String tool, JSONObject args);
}
