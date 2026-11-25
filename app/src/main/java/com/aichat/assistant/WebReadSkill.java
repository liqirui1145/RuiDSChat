package com.aichat.assistant;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页阅读技能：抓取指定 URL 的正文并交给模型总结、追问。
 */
public final class WebReadSkill implements AgentSkill {

    private static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_CHARS = 8000;

    @Override
    public String id() {
        return "web";
    }

    @Override
    public String label() {
        return "网页阅读";
    }

    @Override
    public boolean usesTools() {
        return true;
    }

    @Override
    public String systemPrompt(Context context) {
        return "你是网页阅读助手，可以抓取用户给出的网页并总结内容。\n"
                + "可用工具：\n"
                + "fetch_url {url} 抓取网页，返回标题与正文纯文本\n"
                + "规则：\n"
                + "1. 用户给出链接或让你查某个页面时，先调用 fetch_url，不要凭印象编造内容。\n"
                + "2. 需要对比多个页面时，可以依次调用多次，每次一个 URL。\n"
                + "3. 抓取失败时如实说明，并建议用户检查链接是否可访问。\n"
                + "4. 只依据抓到的内容回答，标注信息出自哪个页面。\n"
                + "5. 回复用简体中文，先给结论再给要点。";
    }

    @Override
    public void checkReady(Context context) throws ApiException {
    }

    @Override
    public String execute(Context context, String tool, JSONObject args) throws ApiException {
        if (!"fetch_url".equals(tool)) {
            throw new ApiException("不支持的工具：" + tool);
        }
        String url = text(args, "url");
        if (url.length() == 0) {
            throw new ApiException("缺少网页地址 url");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        return fetch(context, url);
    }

    @Override
    public String describe(String tool, JSONObject args) {
        String url = text(args, "url");
        return "读取网页 " + (url.length() > 60 ? url.substring(0, 60) + "…" : url);
    }

    private static String fetch(Context context, String address) throws ApiException {
        ApiClient.ensureTls(context);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(25000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android) RuiDSChat/4.0");
            connection.setRequestProperty("Accept", "text/html,text/plain,*/*");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 400) {
                throw new ApiException("网页返回 HTTP " + code);
            }
            String contentType = connection.getContentType();
            String charset = "UTF-8";
            if (contentType != null) {
                Matcher matcher = Pattern.compile("charset=([\\w-]+)",
                        Pattern.CASE_INSENSITIVE).matcher(contentType);
                if (matcher.find()) {
                    charset = matcher.group(1);
                }
            }
            byte[] data = readLimited(connection.getInputStream());
            String body = new String(data, charset);
            String title = extractTitle(body);
            String plain = toPlainText(body);
            if (plain.length() == 0) {
                throw new ApiException("网页没有可读正文（可能是纯前端渲染页面）");
            }
            StringBuilder builder = new StringBuilder();
            if (title.length() > 0) {
                builder.append("标题：").append(title).append('\n');
            }
            builder.append("链接：").append(address).append("\n\n");
            if (plain.length() > MAX_CHARS) {
                builder.append(plain.substring(0, MAX_CHARS)).append("\n\n【正文已截断】");
            } else {
                builder.append(plain);
            }
            return builder.toString();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("抓取网页失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] readLimited(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            total += read;
            if (total >= MAX_BYTES) {
                break;
            }
        }
        input.close();
        return output.toByteArray();
    }

    private static String extractTitle(String html) {
        Matcher matcher = Pattern.compile("<title[^>]*>([\\s\\S]*?)</title>",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (matcher.find()) {
            return decode(matcher.group(1)).replaceAll("\\s+", " ").trim();
        }
        return "";
    }

    /** 去掉脚本、样式与标签，保留可读文本。 */
    private static String toPlainText(String html) {
        String text = html;
        text = text.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        text = text.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        text = text.replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");
        text = text.replaceAll("(?is)<head[^>]*>.*?</head>", " ");
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?i)</(p|div|li|tr|h[1-6]|section|article)>", "\n");
        text = text.replaceAll("(?s)<[^>]+>", " ");
        text = decode(text);
        text = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        text = text.replaceAll("\\n\\s*\\n+", "\n\n");
        return text.trim();
    }

    private static String decode(String value) {
        return value.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }

    private static String text(JSONObject object, String key) {
        if (object == null) {
            return "";
        }
        Object value = object.opt(key);
        return value instanceof String ? ((String) value).trim() : "";
    }
}
