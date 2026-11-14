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
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

public final class ApiClient {

    private static boolean tlsReady = false;

    private ApiClient() {
    }

    public static String chat(Context context, String baseUrl, String apiKey, String model,
                              String systemPrompt, List<ChatMessage> history,
                              String userText, String imageDataUri) throws ApiException {
        ensureTls(context);

        String endpoint = buildEndpoint(baseUrl);
        HttpURLConnection connection = null;
        try {
            JSONObject root = new JSONObject();
            root.put("model", model);
            root.put("stream", false);

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
                throw new ApiException(httpError(code, body, imageDataUri != null));
            }
            return parseContent(body, code, imageDataUri != null);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(networkError(e), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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
                    if (part != null && part.has("text")) {
                        result.append(part.optString("text", ""));
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

    private static String httpError(int code, String body, boolean usedImage) {
        String detail = "";
        try {
            JSONObject root = new JSONObject(body);
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                detail = error.optString("message", "");
            } else {
                detail = root.optString("message", "");
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

    private static synchronized void ensureTls(Context context) throws ApiException {
        if (tlsReady) {
            return;
        }
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            factory.init(buildTrustStore(context));

            SSLContext sslContext;
            try {
                sslContext = SSLContext.getInstance("TLSv1.2");
            } catch (Exception e) {
                sslContext = SSLContext.getInstance("TLS");
            }
            sslContext.init(null, factory.getTrustManagers(), null);
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
            in = context.getResources().openRawResource(R.raw.deepseek_root_g2);
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            for (Certificate certificate : certificateFactory.generateCertificates(in)) {
                store.setCertificateEntry("extra" + index++, certificate);
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
