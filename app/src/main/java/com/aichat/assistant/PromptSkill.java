package com.aichat.assistant;

import android.content.Context;

import org.json.JSONObject;

/**
 * 自定义技能：只使用用户填写的名称与提示词，不调用任何工具。
 */
public final class PromptSkill implements AgentSkill {

    @Override
    public String id() {
        return "custom";
    }

    @Override
    public String label() {
        return "自定义";
    }

    @Override
    public boolean usesTools() {
        return false;
    }

    @Override
    public void checkReady(Context context) throws ApiException {
        if (Prefs.getCustomSkillPrompt(context).trim().length() == 0) {
            throw new ApiException("还没有配置自定义技能，请到「接口设置 - 技能」填写提示词");
        }
    }

    @Override
    public String systemPrompt(Context context) {
        String name = Prefs.getCustomSkillName(context);
        String prompt = Prefs.getCustomSkillPrompt(context);
        StringBuilder builder = new StringBuilder();
        if (name.length() > 0) {
            builder.append("你的角色是「").append(name).append("」。\n");
        }
        builder.append(prompt);
        return builder.toString();
    }

    @Override
    public String execute(Context context, String tool, JSONObject args) throws ApiException {
        throw new ApiException("自定义技能不支持工具调用");
    }

    @Override
    public String describe(String tool, JSONObject args) {
        return tool;
    }
}
