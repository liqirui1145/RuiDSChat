package com.aichat.assistant;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * GitHub REST API 的最小封装。
 * 只依赖 HttpURLConnection 与 org.json，覆盖对话里常用的仓库、文件、Issue 与 Actions 操作。
 */
public final class GitHubClient {

    private static final String API = "https://api.github.com";
    /** 单次读取文件的最大字符数，避免把上下文撑爆。 */
    private static final int MAX_FILE_CHARS = 40000;

    private final Context context;
    private final String token;

    public GitHubClient(Context context, String token) {
        this.context = context.getApplicationContext();
        this.token = token == null ? "" : token.trim();
    }

    public boolean hasToken() {
        return token.length() > 0;
    }

    /** 验证令牌并返回当前登录账号。 */
    public String getLogin() throws ApiException {
        JSONObject user = json(request("GET", "/user", null));
        return str(user, "login");
    }

    public String listRepos() throws ApiException {
        JSONArray array = array(request("GET",
                "/user/repos?per_page=30&sort=updated&affiliation=owner,collaborator", null));
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            JSONObject repo = array.optJSONObject(i);
            if (repo == null) {
                continue;
            }
            builder.append(str(repo, "full_name"))
                    .append(repo.optBoolean("private") ? "（私有）" : "")
                    .append(" 默认分支 ").append(str(repo, "default_branch"))
                    .append(" · ").append(str(repo, "description")).append('\n');
        }
        return builder.length() == 0 ? "没有任何仓库" : builder.toString().trim();
    }

    public String getRepo(String owner, String repo) throws ApiException {
        JSONObject object = json(request("GET", repoPath(owner, repo), null));
        return "仓库：" + str(object, "full_name")
                + "\n默认分支：" + str(object, "default_branch")
                + "\n可见性：" + (object.optBoolean("private") ? "私有" : "公开")
                + "\n描述：" + str(object, "description")
                + "\n地址：" + str(object, "html_url");
    }

    public String listFiles(String owner, String repo, String path, String ref) throws ApiException {
        String endpoint = repoPath(owner, repo) + "/contents";
        if (path != null && path.trim().length() > 0) {
            endpoint += "/" + encodePath(path.trim());
        }
        if (ref != null && ref.trim().length() > 0) {
            endpoint += "?ref=" + encode(ref.trim());
        }
        String body = request("GET", endpoint, null);
        if (body.trim().startsWith("[")) {
            JSONArray array = array(body);
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                builder.append(str(item, "type")).append("  ")
                        .append(str(item, "path"));
                if ("file".equals(str(item, "type"))) {
                    builder.append("  ").append(item.optLong("size")).append(" 字节");
                }
                builder.append('\n');
            }
            return builder.length() == 0 ? "目录为空" : builder.toString().trim();
        }
        JSONObject file = json(body);
        return "这是一个文件：" + str(file, "path") + "\nSHA：" + str(file, "sha")
                + "\n大小：" + file.optLong("size") + " 字节\n提示：使用 read_file 读取内容";
    }

    public String readFile(String owner, String repo, String path, String ref) throws ApiException {
        if (path == null || path.trim().length() == 0) {
            throw new ApiException("缺少文件路径 path");
        }
        String endpoint = repoPath(owner, repo) + "/contents/" + encodePath(path.trim());
        if (ref != null && ref.trim().length() > 0) {
            endpoint += "?ref=" + encode(ref.trim());
        }
        JSONObject object = json(request("GET", endpoint, null));
        String sha = str(object, "sha");
        String content = "";
        String base64 = str(object, "content");
        if (base64.length() > 0 && "base64".equals(str(object, "encoding"))) {
            try {
                content = new String(Base64.decode(base64, Base64.DEFAULT), "UTF-8");
            } catch (Exception e) {
                content = "";
            }
        }
        if (content.length() == 0) {
            String downloadUrl = str(object, "download_url");
            if (downloadUrl.length() > 0) {
                content = rawGet(downloadUrl);
            }
        }
        boolean truncated = false;
        if (content.length() > MAX_FILE_CHARS) {
            content = content.substring(0, MAX_FILE_CHARS);
            truncated = true;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("路径：").append(str(object, "path"))
                .append("\nSHA：").append(sha)
                .append("\n大小：").append(object.optLong("size")).append(" 字节\n内容：\n")
                .append(content);
        if (truncated) {
            builder.append("\n【内容已截断，仅显示前 ").append(MAX_FILE_CHARS).append(" 字符】");
        }
        return builder.toString();
    }

    public String writeFile(String owner, String repo, String path, String content,
                            String message, String branch, String sha) throws ApiException {
        if (path == null || path.trim().length() == 0) {
            throw new ApiException("缺少文件路径 path");
        }
        if (content == null) {
            throw new ApiException("缺少文件内容 content");
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("message", message == null || message.trim().length() == 0
                    ? "Update " + path : message.trim());
            payload.put("content", Base64.encodeToString(content.getBytes("UTF-8"),
                    Base64.NO_WRAP));
            if (branch != null && branch.trim().length() > 0) {
                payload.put("branch", branch.trim());
            }
            if (sha != null && sha.trim().length() > 0) {
                payload.put("sha", sha.trim());
            }
            JSONObject response = json(request("PUT",
                    repoPath(owner, repo) + "/contents/" + encodePath(path.trim()),
                    payload.toString()));
            JSONObject commit = response.optJSONObject("commit");
            String commitSha = commit == null ? "" : str(commit, "sha");
            if (commitSha.length() > 7) {
                commitSha = commitSha.substring(0, 7);
            }
            return "已提交 " + path + "（commit " + commitSha + "）"
                    + (commit == null ? "" : "\n" + str(commit, "html_url"));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("写入文件失败：" + e.getMessage(), e);
        }
    }

    public String createRepo(String name, boolean isPrivate, String description,
                             boolean autoInit) throws ApiException {
        if (name == null || name.trim().length() == 0) {
            throw new ApiException("缺少仓库名称 name");
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("name", name.trim());
            payload.put("private", isPrivate);
            payload.put("auto_init", autoInit);
            if (description != null && description.trim().length() > 0) {
                payload.put("description", description.trim());
            }
            JSONObject response = json(request("POST", "/user/repos", payload.toString()));
            return "已创建仓库 " + str(response, "full_name")
                    + "\n默认分支：" + str(response, "default_branch")
                    + "\n地址：" + str(response, "html_url");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("创建仓库失败：" + e.getMessage(), e);
        }
    }

    public String listIssues(String owner, String repo, String state) throws ApiException {
        String query = (state == null || state.trim().length() == 0) ? "open" : state.trim();
        JSONArray array = array(request("GET",
                repoPath(owner, repo) + "/issues?state=" + encode(query) + "&per_page=20", null));
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            JSONObject issue = array.optJSONObject(i);
            if (issue == null || issue.has("pull_request")) {
                continue;
            }
            JSONObject user = issue.optJSONObject("user");
            builder.append('#').append(issue.optInt("number")).append(" [")
                    .append(str(issue, "state")).append("] ")
                    .append(str(issue, "title"))
                    .append("（").append(user == null ? "" : str(user, "login"))
                    .append("，").append(issue.optInt("comments")).append(" 评论）\n");
        }
        return builder.length() == 0 ? "没有找到 issue" : builder.toString().trim();
    }

    public String createIssue(String owner, String repo, String title, String body)
            throws ApiException {
        if (title == null || title.trim().length() == 0) {
            throw new ApiException("缺少 issue 标题 title");
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("title", title.trim());
            payload.put("body", body == null ? "" : body);
            JSONObject response = json(request("POST",
                    repoPath(owner, repo) + "/issues", payload.toString()));
            return "已创建 issue #" + response.optInt("number") + "："
                    + str(response, "title") + "\n" + str(response, "html_url");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("创建 issue 失败：" + e.getMessage(), e);
        }
    }

    public String commentIssue(String owner, String repo, int number, String body)
            throws ApiException {
        if (number <= 0) {
            throw new ApiException("缺少 issue 编号");
        }
        if (body == null || body.trim().length() == 0) {
            throw new ApiException("缺少回复内容 body");
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("body", body);
            JSONObject response = json(request("POST",
                    repoPath(owner, repo) + "/issues/" + number + "/comments",
                    payload.toString()));
            return "已回复 issue #" + number + "\n" + str(response, "html_url");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("回复 issue 失败：" + e.getMessage(), e);
        }
    }

    public String setIssueState(String owner, String repo, int number, String state)
            throws ApiException {
        if (number <= 0) {
            throw new ApiException("缺少 issue 编号");
        }
        String value;
        if ("closed".equals(state)) {
            value = "closed";
        } else if ("open".equals(state) || "reopen".equals(state)) {
            value = "open";
        } else {
            throw new ApiException("state 只能是 open 或 closed");
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("state", value);
            JSONObject response = json(request("PATCH",
                    repoPath(owner, repo) + "/issues/" + number, payload.toString()));
            return "issue #" + number + " 已标记为 " + str(response, "state")
                    + "\n" + str(response, "html_url");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("更新 issue 状态失败：" + e.getMessage(), e);
        }
    }

    public String listWorkflows(String owner, String repo) throws ApiException {
        JSONObject root = json(request("GET",
                repoPath(owner, repo) + "/actions/workflows?per_page=30", null));
        JSONArray array = root.optJSONArray("workflows");
        StringBuilder builder = new StringBuilder();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                builder.append("id=").append(item.optLong("id")).append("  ")
                        .append(str(item, "name")).append("  ")
                        .append(str(item, "path")).append("  ")
                        .append(str(item, "state")).append('\n');
            }
        }
        return builder.length() == 0 ? "仓库没有可用的 workflow" : builder.toString().trim();
    }

    public String dispatchWorkflow(String owner, String repo, String workflowId,
                                   String ref, String inputsJson) throws ApiException {
        if (workflowId == null || workflowId.trim().length() == 0) {
            throw new ApiException("缺少 workflow_id");
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("ref", ref == null || ref.trim().length() == 0 ? "main" : ref.trim());
            if (inputsJson != null && inputsJson.trim().length() > 0) {
                payload.put("inputs", new JSONObject(inputsJson.trim()));
            }
            request("POST", repoPath(owner, repo) + "/actions/workflows/"
                    + encode(workflowId.trim()) + "/dispatches", payload.toString());
            return "已触发 workflow " + workflowId + "（ref=" + payload.optString("ref") + "）";
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("触发 workflow 失败：" + e.getMessage(), e);
        }
    }

    public String listWorkflowRuns(String owner, String repo) throws ApiException {
        JSONObject root = json(request("GET",
                repoPath(owner, repo) + "/actions/runs?per_page=10", null));
        JSONArray array = root.optJSONArray("workflow_runs");
        StringBuilder builder = new StringBuilder();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject run = array.optJSONObject(i);
                if (run == null) {
                    continue;
                }
                builder.append('#').append(run.optLong("id")).append("  ")
                        .append(str(run, "name")).append("  ")
                        .append(str(run, "status")).append('/')
                        .append(str(run, "conclusion")).append("  ")
                        .append(str(run, "html_url")).append('\n');
            }
        }
        return builder.length() == 0 ? "没有 workflow 运行记录" : builder.toString().trim();
    }

    public String listCommits(String owner, String repo, String branch) throws ApiException {
        String endpoint = repoPath(owner, repo) + "/commits?per_page=10";
        if (branch != null && branch.trim().length() > 0) {
            endpoint += "&sha=" + encode(branch.trim());
        }
        JSONArray array = array(request("GET", endpoint, null));
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            JSONObject commit = array.optJSONObject(i);
            if (commit == null) {
                continue;
            }
            String sha = str(commit, "sha");
            if (sha.length() > 7) {
                sha = sha.substring(0, 7);
            }
            JSONObject detail = commit.optJSONObject("commit");
            String message = "";
            if (detail != null) {
                message = str(detail, "message");
                int newline = message.indexOf('\n');
                if (newline > 0) {
                    message = message.substring(0, newline);
                }
            }
            builder.append(sha).append("  ").append(message).append('\n');
        }
        return builder.length() == 0 ? "没有提交记录" : builder.toString().trim();
    }

    public String createRelease(String owner, String repo, String tag, String title, String body,
                                String target, boolean draft, boolean prerelease)
            throws ApiException {
        if (tag == null || tag.trim().length() == 0) {
            throw new ApiException("缺少 release 的 tag 名称");
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("tag_name", tag.trim());
            payload.put("name", title == null || title.trim().length() == 0
                    ? tag.trim() : title.trim());
            payload.put("body", body == null ? "" : body);
            if (target != null && target.trim().length() > 0) {
                payload.put("target_commitish", target.trim());
            }
            payload.put("draft", draft);
            payload.put("prerelease", prerelease);
            JSONObject response = json(request("POST",
                    repoPath(owner, repo) + "/releases", payload.toString()));
            return "已发布 Release " + str(response, "tag_name")
                    + (draft ? "（草稿）" : "")
                    + "\n" + str(response, "html_url");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("发布 Release 失败：" + e.getMessage(), e);
        }
    }

    public String listReleases(String owner, String repo) throws ApiException {
        JSONArray array = array(request("GET",
                repoPath(owner, repo) + "/releases?per_page=10", null));
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            builder.append(str(item, "tag_name")).append("  ")
                    .append(str(item, "name"))
                    .append(item.optBoolean("draft") ? "（草稿）" : "")
                    .append(item.optBoolean("prerelease") ? "（预发布）" : "")
                    .append("  ").append(str(item, "published_at")).append('\n');
        }
        return builder.length() == 0 ? "还没有 Release" : builder.toString().trim();
    }

    public String createBranch(String owner, String repo, String name, String from)
            throws ApiException {
        if (name == null || name.trim().length() == 0) {
            throw new ApiException("缺少分支名称 name");
        }
        String base = from == null ? "" : from.trim();
        if (base.length() == 0) {
            base = str(json(request("GET", repoPath(owner, repo), null)), "default_branch");
        }
        if (base.length() == 0) {
            base = "main";
        }
        try {
            JSONObject ref = json(request("GET",
                    repoPath(owner, repo) + "/git/ref/heads/" + encodePath(base), null));
            JSONObject object = ref.optJSONObject("object");
            String sha = object == null ? "" : str(object, "sha");
            if (sha.length() == 0) {
                throw new ApiException("找不到分支 " + base);
            }
            JSONObject payload = new JSONObject();
            payload.put("ref", "refs/heads/" + name.trim());
            payload.put("sha", sha);
            JSONObject response = json(request("POST",
                    repoPath(owner, repo) + "/git/refs", payload.toString()));
            return "已创建分支 " + str(response, "ref") + "（基于 " + base + "）";
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("创建分支失败：" + e.getMessage(), e);
        }
    }

    public String deleteFile(String owner, String repo, String path, String message,
                             String branch, String sha) throws ApiException {
        if (path == null || path.trim().length() == 0) {
            throw new ApiException("缺少文件路径 path");
        }
        if (sha == null || sha.trim().length() == 0) {
            throw new ApiException("删除文件必须提供 sha，请先 read_file");
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("message", message == null || message.trim().length() == 0
                    ? "Delete " + path : message.trim());
            payload.put("sha", sha.trim());
            if (branch != null && branch.trim().length() > 0) {
                payload.put("branch", branch.trim());
            }
            request("DELETE", repoPath(owner, repo) + "/contents/" + encodePath(path.trim()),
                    payload.toString());
            return "已删除 " + path;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("删除文件失败：" + e.getMessage(), e);
        }
    }

    public String createPullRequest(String owner, String repo, String title, String head,
                                    String base, String body) throws ApiException {
        if (title == null || title.trim().length() == 0) {
            throw new ApiException("缺少 PR 标题 title");
        }
        if (head == null || head.trim().length() == 0) {
            throw new ApiException("缺少源分支 head");
        }
        try {
            String baseBranch = base == null ? "" : base.trim();
            if (baseBranch.length() == 0) {
                baseBranch = str(json(request("GET", repoPath(owner, repo), null)),
                        "default_branch");
            }
            JSONObject payload = new JSONObject();
            payload.put("title", title.trim());
            payload.put("head", head.trim());
            payload.put("base", baseBranch);
            payload.put("body", body == null ? "" : body);
            JSONObject response = json(request("POST",
                    repoPath(owner, repo) + "/pulls", payload.toString()));
            return "已创建 PR #" + response.optInt("number") + "："
                    + str(response, "title") + "\n" + str(response, "html_url");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("创建 PR 失败：" + e.getMessage(), e);
        }
    }

    public String mergePullRequest(String owner, String repo, int number) throws ApiException {
        if (number <= 0) {
            throw new ApiException("缺少 PR 编号");
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("merge_method", "merge");
            JSONObject response = json(request("PUT",
                    repoPath(owner, repo) + "/pulls/" + number + "/merge",
                    payload.toString()));
            return "PR #" + number + " 合并结果：" + (response.optBoolean("merged")
                    ? "成功" : str(response, "message"));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("合并 PR 失败：" + e.getMessage(), e);
        }
    }

    public String rerunWorkflowRun(String owner, String repo, long runId) throws ApiException {
        if (runId <= 0) {
            throw new ApiException("缺少 run_id");
        }
        try {
            request("POST", repoPath(owner, repo) + "/actions/runs/" + runId + "/rerun", null);
            return "已重新运行 #" + runId;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("重新运行失败：" + e.getMessage(), e);
        }
    }

    private String repoPath(String owner, String repo) throws ApiException {
        if (owner == null || owner.trim().length() == 0
                || repo == null || repo.trim().length() == 0) {
            throw new ApiException("未配置默认仓库，请在设置的 GitHub 助手里填写 owner/repo");
        }
        return "/repos/" + encode(owner.trim()) + "/" + encode(repo.trim());
    }

    private String request(String method, String path, String body) throws ApiException {
        HttpURLConnection connection = null;
        try {
            ApiClient.ensureTls(context);
            connection = (HttpURLConnection) new URL(API + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(120000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "RuiDSChat/3.0 (Android)");
            if (token.length() > 0) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                byte[] payload = body.getBytes("UTF-8");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(payload.length);
                OutputStream output = connection.getOutputStream();
                output.write(payload);
                output.flush();
                output.close();
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = readAll(stream);
            if (code < 200 || code >= 300) {
                throw new ApiException(githubError(response, code), code, response);
            }
            return response;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("连接 GitHub 失败：" + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String rawGet(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(120000);
            connection.setRequestProperty("User-Agent", "RuiDSChat/3.0 (Android)");
            if (token.length() > 0) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return "";
            }
            return readAll(connection.getInputStream());
        } catch (Exception e) {
            return "";
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String githubError(String body, int code) {
        try {
            JSONObject object = new JSONObject(body);
            String message = str(object, "message");
            if (message.length() > 0) {
                return "GitHub：" + message + "（HTTP " + code + "）";
            }
        } catch (Exception ignored) {
        }
        return "GitHub 请求失败（HTTP " + code + "）";
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line).append('\n');
        }
        reader.close();
        return builder.toString();
    }

    private static JSONObject json(String body) throws ApiException {
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            throw new ApiException("解析 GitHub 返回失败", e);
        }
    }

    private static JSONArray array(String body) throws ApiException {
        try {
            return new JSONArray(body);
        } catch (Exception e) {
            throw new ApiException("解析 GitHub 返回失败", e);
        }
    }

    private static String str(JSONObject object, String key) {
        if (object == null) {
            return "";
        }
        Object value = object.opt(key);
        return value instanceof String ? (String) value : "";
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8")
                    .replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    private static String encodePath(String path) {
        StringBuilder builder = new StringBuilder();
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                builder.append('/');
            }
            builder.append(encode(parts[i]));
        }
        return builder.toString();
    }
}
