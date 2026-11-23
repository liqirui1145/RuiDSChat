package com.aichat.assistant;

import android.content.Context;

import org.json.JSONObject;

/**
 * GitHub 技能：让模型通过结构化 JSON 指令调用 GitHub 工具，
 * 在对话里完成读代码、改文件、提交 commit、建仓库、回复 issue、触发 Actions 等操作。
 */
public final class GitHubSkill implements AgentSkill {

    private static final int MAX_ACTIONS_PER_ROUND = 6;

    private final GitHubClient client;

    public GitHubSkill(Context context) {
        String token = Prefs.getGitHubToken(context);
        client = token.length() == 0 ? null : new GitHubClient(context, token);
    }

    @Override
    public String id() {
        return "github";
    }

    @Override
    public String label() {
        return "GitHub";
    }

    @Override
    public boolean usesTools() {
        return true;
    }

    @Override
    public void checkReady(Context context) throws ApiException {
        if (Prefs.getGitHubToken(context).trim().length() == 0) {
            throw new ApiException("还没有连接 GitHub，请到「接口设置 - GitHub 助手」完成登录");
        }
    }

    @Override
    public String execute(Context context, String tool, JSONObject args) throws ApiException {
        if (client == null) {
            throw new ApiException("还没有连接 GitHub，请到设置里完成登录");
        }
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
            if (!args.has("content")) {
                throw new ApiException("缺少文件内容 content");
            }
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
        if ("list_releases".equals(tool)) {
            return client.listReleases(owner, repo);
        }
        if ("create_release".equals(tool)) {
            return client.createRelease(owner, repo, text(args, "tag"), text(args, "title"),
                    text(args, "body"), text(args, "target"),
                    args.optBoolean("draft", false), args.optBoolean("prerelease", false));
        }
        if ("create_branch".equals(tool)) {
            return client.createBranch(owner, repo, text(args, "name"), text(args, "from"));
        }
        if ("delete_file".equals(tool)) {
            return client.deleteFile(owner, repo, text(args, "path"), text(args, "message"),
                    branch, text(args, "sha"));
        }
        if ("create_pull_request".equals(tool)) {
            return client.createPullRequest(owner, repo, text(args, "title"),
                    text(args, "head"), text(args, "base"), text(args, "body"));
        }
        if ("merge_pull_request".equals(tool)) {
            return client.mergePullRequest(owner, repo, args.optInt("number"));
        }
        if ("rerun_workflow_run".equals(tool)) {
            return client.rerunWorkflowRun(owner, repo, args.optLong("run_id"));
        }
        throw new ApiException("不支持的工具：" + tool);
    }

    @Override
    public String describe(String tool, JSONObject args) {
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
        if ("list_releases".equals(tool)) {
            return "查看 Release 列表";
        }
        if ("create_release".equals(tool)) {
            return "发布 Release " + text(args, "tag");
        }
        if ("create_branch".equals(tool)) {
            return "新建分支 " + text(args, "name");
        }
        if ("delete_file".equals(tool)) {
            return "删除 " + text(args, "path");
        }
        if ("create_pull_request".equals(tool)) {
            return "创建 PR：" + text(args, "title");
        }
        if ("merge_pull_request".equals(tool)) {
            return "合并 PR #" + args.optInt("number");
        }
        if ("rerun_workflow_run".equals(tool)) {
            return "重新运行 #" + args.optLong("run_id");
        }
        return tool;
    }

    @Override
    public String systemPrompt(Context context) {
        String repo = Prefs.getGitHubRepo(context);
        if (repo.length() == 0) {
            repo = "（未配置）";
        }
        String branch = Prefs.getGitHubBranch(context);
        return "你是内嵌在 RuiDS Chat 里的 GitHub 操作助手，代替用户真实执行 GitHub 操作。"
                + "你拥有仓库读写、Issue、Actions、Release 权限，所有动作都会真实生效。\n"
                + "默认仓库：" + repo + "（默认分支：" + branch + "）\n"
                + "输出要求（必须严格遵守）：\n"
                + "1. 只输出一个 JSON 对象，不要 Markdown 代码块，不要任何解释文字。\n"
                + "2. 结构：{\"plan\":\"一句话计划\",\"actions\":[{\"tool\":\"工具名\",\"args\":{...}}],\"reply\":\"给用户的最终答复\"}。\n"
                + "3. 只要用户提出 GitHub 操作诉求（改代码、提交、建库、发 Release、回复 Issue、触发 Actions 等），"
                + "actions 必须至少包含一个工具调用；禁止只用 reply 回答「我可以帮你」。\n"
                + "4. 需要真实数据时先调用工具，不要凭空猜测仓库内容；每次最多 " + MAX_ACTIONS_PER_ROUND + " 个 actions。\n"
                + "5. 修改或覆盖已有文件前必须先 read_file 取得 sha，write_file 必须带上 sha；新建文件不需要 sha。\n"
                + "6. write_file 的 content 必须是完整文件内容，不能只给 diff 或省略号。\n"
                + "7. 工具失败后根据错误信息调整参数重试；仍失败则在 reply 里如实说明失败原因。\n"
                + "8. 未指定仓库时使用默认仓库；操作其他仓库时在 args 里加 repo=\"owner/repo\"。\n"
                + "9. 不要删除仓库、不要 force push；删除单个文件需用户明确要求。\n"
                + "10. 如果 read_file 结果标注「内容已截断」，不要覆盖该文件，应告知用户文件过大。\n"
                + "11. reply 用中文，简明说明实际做了什么、结果如何。\n"
                + "可用工具（args 字段）：\n"
                + "list_repos {} 列出当前账号的仓库\n"
                + "get_repo {repo?} 查看仓库信息\n"
                + "list_files {path?,repo?} 浏览目录\n"
                + "read_file {path,repo?} 读取文件（返回 sha 与内容）\n"
                + "write_file {path,content,message,sha?,branch?,repo?} 新建或更新文件并提交 commit\n"
                + "delete_file {path,sha,message?,branch?,repo?} 删除文件\n"
                + "create_repo {name,private?,description?,auto_init?} 新建仓库\n"
                + "create_branch {name,from?,repo?} 新建分支\n"
                + "list_issues {state?,repo?} 查看 issue\n"
                + "create_issue {title,body,repo?} 新建 issue\n"
                + "comment_issue {number,body,repo?} 回复 issue\n"
                + "set_issue_state {number,state,repo?} 关闭或重新打开 issue，state 为 open/closed\n"
                + "create_pull_request {title,head,base?,body?,repo?} 创建 PR\n"
                + "merge_pull_request {number,repo?} 合并 PR\n"
                + "list_workflows {repo?} 查看 Actions workflow\n"
                + "dispatch_workflow {workflow_id,ref?,inputs?,repo?} 触发 workflow（不支持上传 APK 等二进制资产）\n"
                + "list_workflow_runs {repo?} 查看 workflow 运行记录\n"
                + "rerun_workflow_run {run_id,repo?} 重新运行某次 workflow\n"
                + "list_commits {branch?,repo?} 查看提交记录\n"
                + "list_releases {repo?} 查看 Release 列表\n"
                + "create_release {tag,title?,body?,target?,draft?,prerelease?,repo?} 创建 tag 并发布 Release";
    }

    private static String text(JSONObject object, String key) {
        if (object == null) {
            return "";
        }
        Object value = object.opt(key);
        return value instanceof String ? (String) value : "";
    }
}
