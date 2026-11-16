package com.aichat.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GitHub 助手：让模型通过结构化 JSON 指令调用 GitHub 工具，
 * 在对话里完成读代码、改文件、提交 commit、建仓库、回复 issue、触发 Actions 等操作。
 */
public final class GitHubAgent {

    private static final int MAX_ROUNDS = 4;
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

    public static Result run(Context context, List<ChatMessage> history, String instruction,
                             Progress progress, AtomicBoolean cancelled) throws ApiException {
        String token = Prefs.getGitHubToken(context);
        if (token.length() == 0) {
            throw new ApiException("还没有配置 GitHub 令牌，请到「设置 - 接口设置 - GitHub 助手」填写");
        }
        String apiKey = Prefs.getApiKey(context);
        if (apiKey.length() == 0) {
            throw new ApiException("还没有配置对话接口的 API Key，请先到设置里填写");
        }
        GitHubClient client = new GitHubClient(context, token);
        String systemPrompt = buildSystemPrompt(context);
        List<ChatMessage> working = new ArrayList<ChatMessage>(history);
        String userText = instruction;
        StringBuilder log = new StringBuilder();

        for (int round = 0; round < MAX_ROUNDS; round++) {
            if (isCancelled(cancelled)) {
                return new Result("已停止 GitHub 操作。", log.toString(), true);
            }
            if (progress != null) {
                progress.onStep(round == 0 ? "正在规划 GitHub 操作…"
                        : "正在执行第 " + (round + 1) + " 轮…");
            }
            String raw = ApiClient.chat(context, Prefs.getBaseUrl(context), apiKey,
                    Prefs.getChatModel(context), systemPrompt, working, userText, null);
            working.add(new ChatMessage(ChatMessage.ROLE_USER, userText));
            working.add(new ChatMessage(ChatMessage.ROLE_ASSISTANT, raw));

            JSONObject plan = extractJson(raw);
            if (plan == null) {
                // 模型没有按协议返回 JSON，直接把它的回答当作最终结果
                return new Result(raw.trim(), log.toString(), false);
            }
            JSONArray actions = plan.optJSONArray("actions");
            String reply = text(plan, "reply");
            if (actions == null || actions.length() == 0) {
                return new Result(reply.length() > 0 ? reply : "任务已完成。",
                        log.toString(), false);
            }

            JSONArray results = new JSONArray();
            int count = Math.min(actions.length(), MAX_ACTIONS_PER_ROUND);
            for (int i = 0; i < count; i++) {
                if (isCancelled(cancelled)) {
                    return new Result("已停止 GitHub 操作。", log.toString(), true);
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
                String label = describe(tool, args);
                if (progress != null) {
                    progress.onStep("正在执行：" + label);
                }
                try {
                    String result = execute(context, client, tool, args);
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

    private static String execute(Context context, GitHubClient client, String tool,
                                  JSONObject args) throws ApiException {
        String repoFull = text(args, "repo");
        if (repoFull.length() == 0) {
            repoFull = Prefs.getGitHubRepo(context);
        }
        String owner = "";
        String repo = "";
        int slash = repoFull.indexOf('/');
        if (slash > 0) {
            owner = repoFull.substring(0, slash).trim();
            repo = repoFull.substring(slash + 1).trim();
        }
        String branch = text(args, "branch");
        if (branch.length() == 0) {
            branch = Prefs.getGitHubBranch(context);
        }

        if ("list_repos".equals(tool)) {
            return client.listRepos();
        }
        if ("create_repo".equals(tool)) {
            return client.createRepo(text(args, "name"),
                    args.optBoolean("private", false),
                    text(args, "description"),
                    args.optBoolean("auto_init", true));
        }
        if (owner.length() == 0 || repo.length() == 0) {
            throw new ApiException("未配置默认仓库，请在设置里填写 owner/repo");
        }
        if ("get_repo".equals(tool)) {
            return client.getRepo(owner, repo);
        }
        if ("list_files".equals(tool)) {
            return client.listFiles(owner, repo, text(args, "path"), branch);
        }
        if ("read_file".equals(tool)) {
            return client.readFile(owner, repo, text(args, "path"), branch);
        }
        if ("write_file".equals(tool)) {
            return client.writeFile(owner, repo, text(args, "path"),
                    text(args, "content"), text(args, "message"), branch,
                    text(args, "sha"));
        }
        if ("list_issues".equals(tool)) {
            return client.listIssues(owner, repo, text(args, "state"));
        }
        if ("create_issue".equals(tool)) {
            return client.createIssue(owner, repo, text(args, "title"),
                    text(args, "body"));
        }
        if ("comment_issue".equals(tool)) {
            return client.commentIssue(owner, repo, args.optInt("number"),
                    text(args, "body"));
        }
        if ("set_issue_state".equals(tool)) {
            return client.setIssueState(owner, repo, args.optInt("number"),
                    text(args, "state"));
        }
        if ("list_workflows".equals(tool)) {
            return client.listWorkflows(owner, repo);
        }
        if ("dispatch_workflow".equals(tool)) {
            return client.dispatchWorkflow(owner, repo, text(args, "workflow_id"),
                    branch, text(args, "inputs"));
        }
        if ("list_workflow_runs".equals(tool)) {
            return client.listWorkflowRuns(owner, repo);
        }
        if ("list_commits".equals(tool)) {
            return client.listCommits(owner, repo, branch);
        }
        throw new ApiException("不支持的工具：" + tool);
    }

    private static String describe(String tool, JSONObject args) {
        if ("read_file".equals(tool) || "write_file".equals(tool)
                || "list_files".equals(tool)) {
            String path = text(args, "path");
            String action = "read_file".equals(tool) ? "读取 "
                    : "write_file".equals(tool) ? "提交 " : "浏览 ";
            return action + (path.length() == 0 ? "仓库根目录" : path);
        }
        if ("create_repo".equals(tool)) {
            return "新建仓库 " + text(args, "name");
        }
        if ("get_repo".equals(tool)) {
            return "查看仓库信息";
        }
        if ("list_repos".equals(tool)) {
            return "列出仓库";
        }
        if ("list_issues".equals(tool)) {
            return "查看 issue 列表";
        }
        if ("create_issue".equals(tool)) {
            return "新建 issue：" + text(args, "title");
        }
        if ("comment_issue".equals(tool)) {
            return "回复 issue #" + args.optInt("number");
        }
        if ("set_issue_state".equals(tool)) {
            return "更新 issue #" + args.optInt("number") + " 状态";
        }
        if ("list_workflows".equals(tool)) {
            return "查看 workflow 列表";
        }
        if ("dispatch_workflow".equals(tool)) {
            return "触发 workflow " + text(args, "workflow_id");
        }
        if ("list_workflow_runs".equals(tool)) {
            return "查看 workflow 运行记录";
        }
        if ("list_commits".equals(tool)) {
            return "查看提交记录";
        }
        return tool;
    }

    private static String buildSystemPrompt(Context context) {
        String repo = Prefs.getGitHubRepo(context);
        if (repo.length() == 0) {
            repo = "（未配置）";
        }
        String branch = Prefs.getGitHubBranch(context);
        return "你是内嵌在 RuiDS Chat 里的 GitHub 操作助手。"
                + "你可以读取和修改仓库文件、提交 commit、新建仓库、处理 issue、查看并触发 GitHub Actions。\n"
                + "默认仓库：" + repo + "（默认分支：" + branch + "）\n"
                + "工作规则：\n"
                + "1. 只输出一个 JSON 对象，不要输出 Markdown 代码块或额外解释。\n"
                + "2. 格式：{\"plan\":\"一句话计划\",\"actions\":[{\"tool\":\"工具名\",\"args\":{...}}],\"reply\":\"最终答复\"}。\n"
                + "3. actions 为空时，reply 必填，作为给用户的最终答复。\n"
                + "4. 需要真实数据时先调用工具，不要凭空猜测仓库内容。\n"
                + "5. 修改已有文件前必须先 read_file 取得 sha；write_file 覆盖已有文件时必须带 sha。\n"
                + "6. 每次最多 " + MAX_ACTIONS_PER_ROUND + " 个 actions；不要删除仓库、不要 force push。\n"
                + "7. 文件路径相对仓库根目录；未指定仓库时使用默认仓库，其他仓库用 args.repo=\"owner/repo\" 指定。\n"
                + "8. 如果 read_file 结果包含「内容已截断」，不要用 write_file 覆盖该文件，应告知用户文件过大。\n"
                + "9. 提交信息要清晰描述改动；所有操作都会真实执行，请谨慎。\n"
                + "10. 信息不足时不要猜测，用 reply 说明缺少什么。\n"
                + "11. 用中文回复用户。\n"
                + "可用工具（args 字段）：\n"
                + "list_repos {} 列出当前账号的仓库\n"
                + "get_repo {repo?} 查看仓库信息\n"
                + "list_files {path?,repo?} 浏览目录\n"
                + "read_file {path,repo?} 读取文件\n"
                + "write_file {path,content,message,sha?,branch?,repo?} 新建或更新文件并提交\n"
                + "create_repo {name,private?,description?,auto_init?} 新建仓库\n"
                + "list_issues {state?,repo?} 查看 issue\n"
                + "create_issue {title,body,repo?} 新建 issue\n"
                + "comment_issue {number,body,repo?} 回复 issue\n"
                + "set_issue_state {number,state,repo?} 关闭或重新打开 issue，state 为 open/closed\n"
                + "list_workflows {repo?} 查看 Actions workflow\n"
                + "dispatch_workflow {workflow_id,ref?,inputs?,repo?} 触发 workflow\n"
                + "list_workflow_runs {repo?} 查看 workflow 运行记录\n"
                + "list_commits {branch?,repo?} 查看提交记录";
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
