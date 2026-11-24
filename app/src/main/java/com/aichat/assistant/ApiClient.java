package com.aichat.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public final class ApiClient {

    private static boolean tlsReady = false;

    private ApiClient() {
    }

    public static String chat(Context context, String baseUrl, String apiKey, String model,
                              String systemPrompt, List<ChatMessage> history,
                              String userText, String imageDataUri) throws ApiException {
        return chatInternal(context, baseUrl, apiKey, model, systemPrompt, history,
                userText, imageDataUri, false);
    }

    /** JSON 模式对话：强制模型只输出 JSON 对象，用于工具调用类任务。 */
    public static String chatJson(Context context, String baseUrl, String apiKey, String model,
                                  String systemPrompt, List<ChatMessage> history,
                                  String userText) throws ApiException {
        return chatInternal(context, baseUrl, apiKey, model, systemPrompt, history,
                userText, null, true);
    }

    private static String chatInternal(Context context, String baseUrl, String apiKey, String model,
                                       String systemPrompt, List<ChatMessage> history,
                                       String userText, String imageDataUri, boolean jsonMode)
            throws ApiException {
        ensureTls(context);

        String endpoint = buildEndpoint(baseUrl);
        HttpURLConnection connection = null;
        try {
            JSONObject root = buildPayload(model, systemPrompt, history, userText,
                    imageDataUri, false, 0, 0);
            if (jsonMode) {
                root.put("temperature", 0);
                JSONObject format = new JSONObject();
                format.put("type", "json_object");
                root.put("response_format", format);
            }

            byte[] payload = root.toString().getBytes("UTF-8");

            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(180000);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("User-Agent", "AiChatAssistant/1.0 (Android)");
            connection.setFixedLengthStreamingMode(payload.length);

            OutputStream output = connection.getOutputStream();
            output.write(payload);
            output.flush();
            output.close();

            int code = connection.getResponseCode();
            String body;
            if (code >= 200 && code < 300) {
                body = readAll(connection.getInputStream());
            } else {
                body = readAll(connection.getErrorStream());
            }

            if (code < 200 || code >= 300) {
                throw httpErrorException(code, body, imageDataUri != null);
            }
            return parseContent(body, code, imageDataUri != null);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(networkError(e), e, networkDiagnostics(e));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 流式增量回调，运行在后台线程，界面刷新需要自行切回主线程。 */
    public interface StreamCallback {
        void onDelta(String text);

        /** 深度思考模型的思考过程增量。 */
        void onReasoning(String text);

        /** 接口返回的本次请求 token 用量。 */
        void onUsage(int promptTokens, int completionTokens);

        void onFinish(String finishReason);
    }

    /** 用于中断进行中的流式请求。 */
    public static final class StreamHandle {

        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;

        public void cancel() {
            cancelled = true;
            HttpURLConnection current = connection;
            if (current != null) {
                try {
                    current.disconnect();
                } catch (Exception ignored) {
                }
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    /**
     * 流式对话：逐段回调 delta.content，返回完整回复。
     * 被取消时返回已经收到的部分内容，不抛出异常。
     */
    public static String chatStream(Context context, String baseUrl, String apiKey, String model,
                                    String systemPrompt, List<ChatMessage> history,
                                    String userText, String imageDataUri, int maxTokens,
                                    int reasoningLevel, StreamCallback callback, StreamHandle handle)
            throws ApiException {
        ensureTls(context);

        String endpoint = buildEndpoint(baseUrl);
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        StringBuilder full = new StringBuilder();
        try {
            JSONObject root = buildPayload(model, systemPrompt, history, userText,
                    imageDataUri, true, maxTokens, reasoningLevel);
            byte[] payload = root.toString().getBytes("UTF-8");

            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            if (handle != null) {
                handle.connection = connection;
            }
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(180000);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("User-Agent", "AiChatAssistant/1.0 (Android)");
            connection.setFixedLengthStreamingMode(payload.length);

            OutputStream output = connection.getOutputStream();
            output.write(payload);
            output.flush();
            output.close();

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String body = readAll(connection.getErrorStream());
                throw httpErrorException(code, body, imageDataUri != null);
            }

            reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
            String finishReason = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (handle != null && handle.isCancelled()) {
                    break;
                }
                String trimmed = line.trim();
                if (trimmed.length() == 0 || trimmed.startsWith(":") || !trimmed.startsWith("data:")) {
                    // 空行与 ": keep-alive" 注释行直接跳过
                    continue;
                }
                String data = trimmed.substring(5).trim();
                if (data.length() == 0) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }
                try {
                    JSONObject chunk = new JSONObject(data);
                    JSONObject error = chunk.optJSONObject("error");
                    if (error != null) {
                        String message = text(error, "message");
                        if (message.length() == 0) {
                            message = "未知错误";
                        }
                        String errorCode = text(error, "code");
                        throw new ApiException("模型返回错误：" + message, 0,
                                errorCode.length() > 0
                                        ? "error.code=" + errorCode + "\n" + data : data);
                    }
                    JSONObject usage = chunk.optJSONObject("usage");
                    if (usage != null) {
                        int promptTokens = usage.optInt("prompt_tokens", 0);
                        int completionTokens = usage.optInt("completion_tokens", 0);
                        if (callback != null && (promptTokens > 0 || completionTokens > 0)) {
                            callback.onUsage(promptTokens, completionTokens);
                        }
                    }
                    JSONArray choices = chunk.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) {
                        continue;
                    }
                    JSONObject choice = choices.optJSONObject(0);
                    if (choice == null) {
                        continue;
                    }
                    JSONObject delta = choice.optJSONObject("delta");
                    String piece = "";
                    if (delta != null) {
                        String reasoning = text(delta, "reasoning_content");
                        if (reasoning.length() == 0) {
                            reasoning = text(delta, "reasoning");
                        }
                        if (reasoning.length() > 0 && callback != null) {
                            callback.onReasoning(reasoning);
                        }
                        piece = text(delta, "content");
                        if (piece.length() == 0) {
                            // 兼容把增量放在 message 里的实现
                            JSONObject message = delta.optJSONObject("message");
                            piece = text(message, "content");
                        }
                    }
                    if (piece.length() > 0) {
                        full.append(piece);
                        if (callback != null) {
                            callback.onDelta(piece);
                        }
                    }
                    String reason = text(choice, "finish_reason");
                    if (reason.length() > 0) {
                        finishReason = reason;
                    }
                } catch (ApiException e) {
                    throw e;
                } catch (Exception ignored) {
                    // 忽略无法解析的保活数据
                }
            }

            if (callback != null) {
                callback.onFinish(finishReason);
            }
            return full.toString().trim();
        } catch (ApiException e) {
            if (handle != null && handle.isCancelled()) {
                return full.toString().trim();
            }
            throw e;
        } catch (Exception e) {
            if (handle != null && handle.isCancelled()) {
                return full.toString().trim();
            }
            throw new ApiException(networkError(e), e, networkDiagnostics(e));
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
            if (handle != null) {
                handle.connection = null;
            }
        }
    }

    /** 思考强度可选值，与 Prefs.getReasoningLevel 的下标一一对应（0 表示关闭思考）。 */
    private static final String[] REASONING_EFFORTS =
            new String[]{"", "minimal", "low", "medium", "high"};

    private static JSONObject buildPayload(String model, String systemPrompt,
                                           List<ChatMessage> history, String userText,
                                           String imageDataUri, boolean stream, int maxTokens,
                                           int reasoningLevel)
            throws Exception {
        JSONObject root = new JSONObject();
        root.put("model", model);
        root.put("stream", stream);
        if (stream) {
            // 让接口在最后一个数据块返回 token 用量，用于费用估算
            JSONObject streamOptions = new JSONObject();
            streamOptions.put("include_usage", true);
            root.put("stream_options", streamOptions);
        }
        if (maxTokens > 0) {
            root.put("max_tokens", maxTokens);
        }
        if (model != null && model.startsWith("deepseek")) {
            // DeepSeek V4 起思考模式默认开启，需要用 thinking 显式开关
            JSONObject thinking = new JSONObject();
            thinking.put("type", reasoningLevel > 0 ? "enabled" : "disabled");
            root.put("thinking", thinking);
        }
        if (reasoningLevel > 0 && reasoningLevel < REASONING_EFFORTS.length) {
            // 思考强度：low / high / max
            root.put("reasoning_effort", REASONING_EFFORTS[reasoningLevel]);
        }

        JSONArray messages = new JSONArray();
        if (systemPrompt != null && systemPrompt.trim().length() > 0) {
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", systemPrompt.trim());
            messages.put(system);
        }

        for (ChatMessage message : history) {
            if (message.getRole() == ChatMessage.ROLE_ERROR) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("role", message.getRole() == ChatMessage.ROLE_USER ? "user" : "assistant");
            item.put("content", message.getText());
            messages.put(item);
        }

        JSONObject last = new JSONObject();
        last.put("role", "user");
        if (imageDataUri != null) {
            JSONArray parts = new JSONArray();
            JSONObject textPart = new JSONObject();
            textPart.put("type", "text");
            textPart.put("text", userText);
            parts.put(textPart);

            JSONObject imagePart = new JSONObject();
            imagePart.put("type", "image_url");
            JSONObject imageUrl = new JSONObject();
            imageUrl.put("url", imageDataUri);
            imagePart.put("image_url", imageUrl);
            parts.put(imagePart);

            last.put("content", parts);
        } else {
            last.put("content", userText);
        }
        messages.put(last);
        root.put("messages", messages);
        return root;
    }

    private static String parseContent(String body, int code, boolean usedImage)
            throws ApiException {
        try {
            JSONObject root = new JSONObject(body);
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                throw new ApiException(httpError(code, body, usedImage));
            }
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new ApiException("接口返回内容为空，请稍后重试");
            }
            JSONObject choice = choices.getJSONObject(0);
            JSONObject message = choice.optJSONObject("message");
            if (message == null) {
                throw new ApiException("接口返回格式异常，缺少 message 字段");
            }
            Object contentObject = message.opt("content");
            StringBuilder result = new StringBuilder();
            if (contentObject instanceof String) {
                result.append((String) contentObject);
            } else if (contentObject instanceof JSONArray) {
                JSONArray array = (JSONArray) contentObject;
                for (int i = 0; i < array.length(); i++) {
                    JSONObject part = array.optJSONObject(i);
                    if (part != null) {
                        result.append(text(part, "text"));
                    }
                }
            }
            String text = result.toString().trim();
            if (text.length() == 0) {
                throw new ApiException("模型没有返回文字内容，请稍后重试");
            }
            return text;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("接口返回格式异常：" + shorten(body));
        }
    }

    /**
     * 读取 JSON 里的字符串字段。
     * org.json 的 optString 会把 JSON null 转成字符串 "null"，
     * 深度思考接口在正文阶段会持续返回 "reasoning_content": null，
     * 直接使用会把大段 null 拼进消息里，所以这里只接受真正的字符串。
     */
    private static String text(JSONObject object, String key) {
        if (object == null) {
            return "";
        }
        Object value = object.opt(key);
        return value instanceof String ? (String) value : "";
    }

    private static String httpError(int code, String body, boolean usedImage) {
        String detail = "";
        try {
            JSONObject root = new JSONObject(body);
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                detail = text(error, "message");
            } else {
                detail = text(root, "message");
            }
        } catch (Exception ignored) {
        }
        if (detail == null) {
            detail = "";
        }
        if (detail.length() > 300) {
            detail = detail.substring(0, 300) + "…";
        }

        String friendly;
        if (code == 401) {
            friendly = "API Key 无效或未通过鉴权（401）";
        } else if (code == 402) {
            friendly = "账户余额不足（402）";
        } else if (code == 403) {
            friendly = "没有访问该模型的权限（403）";
        } else if (code == 404) {
            friendly = "接口地址或模型不存在（404），请检查接口地址和模型名称";
        } else if (code == 429) {
            friendly = "请求过于频繁，请稍后重试（429）";
        } else if (code >= 500) {
            friendly = "服务端暂时异常（" + code + "），请稍后重试";
        } else {
            friendly = "请求失败（" + code + "）";
        }
        if (detail.length() > 0 && !detail.contains(friendly)) {
            friendly = friendly + "：" + detail;
        }
        if (usedImage && (code == 400 || code == 422)) {
            friendly = friendly + "\n\n提示：请确认图片为 JPEG / PNG / GIF / WebP 格式且大小正常；"
                    + "DeepSeek 官方接口会自动使用 deepseek-flash 理解图片，使用第三方接口时请检查视觉模型名称。";
        }
        return friendly;
    }

    private static ApiException httpErrorException(int code, String body, boolean usedImage) {
        String raw = body == null ? "" : body.trim();
        if (raw.length() > 1200) {
            raw = raw.substring(0, 1200) + "…";
        }
        return new ApiException(httpError(code, body, usedImage), code, raw);
    }

    private static String networkDiagnostics(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static String networkError(Exception e) {
        if (e instanceof UnknownHostException) {
            return "网络不可用或域名解析失败，请检查网络连接";
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return "HTTPS 安全连接失败：" + e.getMessage();
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "请求超时，请检查网络后重试";
        }
        String message = e.getMessage();
        return "网络请求失败：" + (message == null ? e.getClass().getSimpleName() : message);
    }

    private static String buildEndpoint(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "https://" + base;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[4096];
        int read;
        try {
            while ((read = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, read);
            }
        } finally {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
        }
        return builder.toString();
    }

    private static String shorten(String body) {
        if (body == null) {
            return "空响应";
        }
        String text = body.trim();
        if (text.length() > 200) {
            text = text.substring(0, 200) + "…";
        }
        return text;
    }

    static synchronized void ensureTls(Context context) throws ApiException {
        if (tlsReady) {
            return;
        }
        try {
            KeyStore store = buildTrustStore(context);
            TrustManagerFactory factory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);

            SSLContext sslContext;
            try {
                sslContext = SSLContext.getInstance("TLSv1.2");
            } catch (Exception e) {
                sslContext = SSLContext.getInstance("TLS");
            }
            TrustManager[] trustManagers = {
                    new CompatTrustManager(factory.getTrustManagers(), store)
            };
            sslContext.init(null, trustManagers, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(
                    new TlsSocketFactory(sslContext.getSocketFactory()));
            tlsReady = true;
        } catch (Exception e) {
            throw new ApiException("初始化安全连接失败：" + e.getMessage(), e);
        }
    }

    private static KeyStore buildTrustStore(Context context) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        int index = 0;

        try {
            KeyStore system = KeyStore.getInstance("AndroidCAStore");
            system.load(null, null);
            Enumeration<String> aliases = system.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                Certificate certificate = system.getCertificate(alias);
                if (certificate != null) {
                    try {
                        store.setCertificateEntry("sys" + index++, certificate);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }

        InputStream in = null;
        try {
            // Android 6 及以下缺少 GitHub 使用的 Sectigo / USERTrust ECC 根证书，
            // 这里连同 DeepSeek 的根证书一起内置，避免老设备握手失败。
            int[] extraRoots = {
                    R.raw.deepseek_root_g2,
                    R.raw.github_usertrust_ecc,
                    R.raw.github_sectigo_e46,
                    R.raw.github_usertrust_rsa,
                    R.raw.github_sectigo_r46,
                    R.raw.github_comodo_rsa,
            };
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            for (int resId : extraRoots) {
                in = context.getResources().openRawResource(resId);
                for (Certificate certificate : certificateFactory.generateCertificates(in)) {
                    store.setCertificateEntry("extra" + index++, certificate);
                }
                in.close();
                in = null;
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
        return store;
    }

    /**
     * 旧系统（Android 5.x/6）的 PKIX 实现有时会拒绝合法证书链，
     * 这里在系统校验失败后退回手工链校验：只接受能逐级验签、
     * 且最终落到内置根证书、且未过期的链。
     */
    private static final class CompatTrustManager implements X509TrustManager {

        private final X509TrustManager delegate;
        private final Set<X509Certificate> roots = new HashSet<X509Certificate>();

        CompatTrustManager(TrustManager[] managers, KeyStore store) throws Exception {
            X509TrustManager found = null;
            if (managers != null) {
                for (TrustManager manager : managers) {
                    if (manager instanceof X509TrustManager) {
                        found = (X509TrustManager) manager;
                        break;
                    }
                }
            }
            if (found == null) {
                throw new IllegalStateException("缺少 X509 信任管理器");
            }
            delegate = found;
            Enumeration<String> aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                Certificate certificate = store.getCertificate(aliases.nextElement());
                if (certificate instanceof X509Certificate) {
                    roots.add((X509Certificate) certificate);
                }
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            try {
                delegate.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                if (!verifyManually(chain)) {
                    throw e;
                }
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }

        private boolean verifyManually(X509Certificate[] chain) {
            if (chain == null || chain.length == 0) {
                return false;
            }
            try {
                for (X509Certificate certificate : chain) {
                    certificate.checkValidity();
                }
                for (int i = 0; i + 1 < chain.length; i++) {
                    X509Certificate child = chain[i];
                    X509Certificate parent = chain[i + 1];
                    if (!child.getIssuerX500Principal().equals(parent.getSubjectX500Principal())) {
                        return false;
                    }
                    child.verify(parent.getPublicKey());
                }
                X509Certificate last = chain[chain.length - 1];
                if (roots.contains(last)) {
                    return true;
                }
                for (X509Certificate root : roots) {
                    if (last.getIssuerX500Principal().equals(root.getSubjectX500Principal())) {
                        last.verify(root.getPublicKey());
                        return true;
                    }
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /** Android 4.4 默认不启用 TLS 1.2，这里强制启用。 */
    private static class TlsSocketFactory extends SSLSocketFactory {

        private final SSLSocketFactory delegate;

        TlsSocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
                throws IOException {
            return tune(delegate.createSocket(socket, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
            return tune(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws IOException, UnknownHostException {
            return tune(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return tune(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
                                   int localPort) throws IOException {
            return tune(delegate.createSocket(address, port, localAddress, localPort));
        }

        private Socket tune(Socket socket) {
            if (socket instanceof SSLSocket) {
                SSLSocket sslSocket = (SSLSocket) socket;
                List<String> protocols = new ArrayList<String>();
                for (String protocol : sslSocket.getSupportedProtocols()) {
                    if ("TLSv1.2".equals(protocol) || "TLSv1.3".equals(protocol)) {
                        protocols.add(protocol);
                    }
                }
                if (!protocols.isEmpty()) {
                    sslSocket.setEnabledProtocols(protocols.toArray(new String[protocols.size()]));
                }
            }
            return socket;
        }
    }
}
