package com.aichat.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通用 Agent 循环：让模型输出结构化 JSON 动作，App 执行技能工具并回传结果，
 * 最多执行若干轮，直到模型给出最终答复。
 */
public final class AgentRunner {

    private static final int MAX_ROUNDS = 6;
    private static final int MAX_ACTIONS_PER_ROUND = 6;
    private static final int MAX_TOOL_RESULT_CHARS = 12000;

    public interface Progress {
        void onStep(String message);
    }

    public static final class Result {
        public final String reply;
        public final String log;
        public final boolean cancelled;

        Result(String reply, String log, boolean cancelled) {
            this.reply = reply == null ? "" : reply;
            this.log = log == null ? "" : log;
            this.cancelled = cancelled;
        }
    }

    private AgentRunner() {
    }

    public static Result run(Context context, AgentSkill skill, List<ChatMessage> history,
                             String instruction, Progress progress, AtomicBoolean cancelled)
            throws ApiException {
        if (skill == null) {
            throw new ApiException("未选择技能");
        }
        skill.checkReady(context);
        String apiKey = Prefs.getApiKey(context);
        if (apiKey.length() == 0) {
            throw new ApiException("还没有配置对话接口的 API Key，请先到设置里填写");
        }
        String model = Prefs.getChatModel(context);
        String systemPrompt = skill.systemPrompt(context);

        if (!skill.usesTools()) {
            if (progress != null) {
                progress.onStep("正在思考…");
            }
            String reply = ApiClient.chat(context, Prefs.getBaseUrl(context), apiKey, model,
                    systemPrompt, history, instruction, null);
            return new Result(reply, "", isCancelled(cancelled));
        }

        List<ChatMessage> working = new ArrayList<ChatMessage>(history);
        String userText = instruction;
        StringBuilder log = new StringBuilder();

        for (int round = 0; round < MAX_ROUNDS; round++) {
            if (isCancelled(cancelled)) {
                return new Result("已停止操作。", log.toString(), true);
            }
            if (progress != null) {
                progress.onStep(round == 0 ? "正在规划操作…"
                        : "正在执行第 " + (round + 1) + " 轮…");
            }
            String raw = requestPlan(context, model, apiKey, systemPrompt, working, userText);
            working.add(new ChatMessage(ChatMessage.ROLE_USER, userText));
            working.add(new ChatMessage(ChatMessage.ROLE_ASSISTANT, raw));

            JSONObject plan = extractJson(raw);
            if (plan == null) {
                // 模型没有按协议返回 JSON，直接把它的回答当作最终结果
                return new Result(raw.trim(), log.toString(), false);
            }
            JSONArray actions = parseActions(plan);
            String reply = text(plan, "reply");
            if (actions == null || actions.length() == 0) {
                if (reply.length() > 0) {
                    return new Result(reply, log.toString(), false);
                }
                if (round == 0) {
                    return new Result("模型没有返回可执行的操作。请换用 deepseek-flash 模型再试，"
                            + "或把指令写得更具体。\n\n模型原文：\n" + raw.trim(),
                            log.toString(), false);
                }
                return new Result("任务已完成。", log.toString(), false);
            }

            JSONArray results = new JSONArray();
            int count = Math.min(actions.length(), MAX_ACTIONS_PER_ROUND);
            for (int i = 0; i < count; i++) {
                if (isCancelled(cancelled)) {
                    return new Result("已停止操作。", log.toString(), true);
                }
                JSONObject action = actions.optJSONObject(i);
                if (action == null) {
                    continue;
                }
                String tool = text(action, "tool");
                JSONObject args = action.optJSONObject("args");
                if (args == null) {
                    args = action;
                }
                String label = skill.describe(tool, args);
                if (progress != null) {
                    progress.onStep("正在执行：" + label);
                }
                try {
                    String result = skill.execute(context, tool, args);
                    log.append("✓ ").append(label).append('\n');
                    results.put(resultObject(tool, true, truncate(result)));
                } catch (Exception e) {
                    String message = e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage();
                    log.append("✗ ").append(label).append("：").append(message).append('\n');
                    results.put(resultObject(tool, false, message));
                }
            }
            userText = "以下是工具执行结果（JSON）：\n" + results.toString()
                    + "\n请根据结果继续。如果任务已完成，请输出空的 actions 数组，"
                    + "并在 reply 中给出最终答复。";
        }
        return new Result("已达到最大执行轮数，请查看操作记录，或把任务拆小后再试。",
                log.toString(), false);
    }

    private static boolean isCancelled(AtomicBoolean cancelled) {
        return cancelled != null && cancelled.get();
    }

    /** 让模型输出 JSON 计划；优先使用 JSON 模式，不支持时退回普通对话。 */
    private static String requestPlan(Context context, String model, String apiKey,
                                      String systemPrompt, List<ChatMessage> working,
                                      String userText) throws ApiException {
        String baseUrl = Prefs.getBaseUrl(context);
        try {
            return ApiClient.chatJson(context, baseUrl, apiKey, model, systemPrompt,
                    working, userText);
        } catch (ApiException e) {
            int code = e.getStatusCode();
            if (code == 400 || code == 404 || code == 422) {
                // 部分第三方兼容接口不支持 response_format，退回普通对话
                return ApiClient.chat(context, baseUrl, apiKey, model, systemPrompt,
                        working, userText, null);
            }
            throw e;
        }
    }

    /** 兼容 actions 为数组、JSON 字符串或单个对象，以及省略 plan 直接给工具的情况。 */
    private static JSONArray parseActions(JSONObject plan) {
        JSONArray actions = plan.optJSONArray("actions");
        if (actions == null) {
            actions = plan.optJSONArray("action");
        }
        if (actions == null) {
            Object raw = plan.opt("actions");
            if (raw instanceof String) {
                String value = ((String) raw).trim();
                try {
                    actions = new JSONArray(value);
                } catch (Exception e) {
                    try {
                        actions = new JSONArray().put(new JSONObject(value));
                    } catch (Exception ignored) {
                    }
                }
            } else if (raw instanceof JSONObject) {
                actions = new JSONArray().put((JSONObject) raw);
            }
        }
        if (actions == null && text(plan, "tool").length() > 0) {
            actions = new JSONArray().put(plan);
        }
        return actions;
    }

    private static JSONObject resultObject(String tool, boolean ok, String result) {
        JSONObject object = new JSONObject();
        try {
            object.put("tool", tool);
            object.put("ok", ok);
            object.put("result", result);
        } catch (Exception ignored) {
        }
        return object;
    }

    /** 从模型输出里提取 JSON；兼容被 ``` 包裹或前后带解释的情况。 */
    private static JSONObject extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int lineEnd = text.indexOf('\n', fence);
            if (lineEnd > 0) {
                text = text.substring(lineEnd + 1);
            }
            int endFence = text.lastIndexOf("```");
            if (endFence >= 0) {
                text = text.substring(0, endFence);
            }
        }
        text = text.trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                return new JSONObject().put("actions", new JSONArray(text));
            } catch (Exception ignored) {
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return new JSONObject(text.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= MAX_TOOL_RESULT_CHARS) {
            return value;
        }
        return value.substring(0, MAX_TOOL_RESULT_CHARS) + "\n【结果已截断】";
    }

    private static String text(JSONObject object, String key) {
        if (object == null) {
            return "";
        }
        Object value = object.opt(key);
        return value instanceof String ? (String) value : "";
    }
}
