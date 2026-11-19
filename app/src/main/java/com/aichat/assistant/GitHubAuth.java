package com.aichat.assistant;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GitHub OAuth 设备授权流程（Device Flow）。
 * 用户只需在浏览器打开验证页面并输入短码，无需复制任何 Token。
 */
public final class GitHubAuth {

    /**
     * 内置 OAuth App 的 Client ID。可公开，不是密钥。
     * 留空时需要在设置页填写一次自建 OAuth App 的 Client ID。
     */
    public static final String DEFAULT_CLIENT_ID = "Ov23liPNLcCXk90YECPQ";

    /** 需要申请的权限：仓库读写 + Actions。 */
    public static final String SCOPE = "repo workflow";

    public static final String NEW_APP_URL = "https://github.com/settings/applications/new";
    public static final String DEVICE_URL = "https://github.com/login/device";

    private static final String CODE_ENDPOINT = "https://github.com/login/device/code";
    private static final String TOKEN_ENDPOINT = "https://github.com/login/oauth/access_token";

    private GitHubAuth() {
    }

    /** 申请到的设备授权码。 */
    public static final class DeviceCode {
        public final String deviceCode;
        public final String userCode;
        public final String verificationUri;
        public final int expiresIn;
        public final int interval;

        DeviceCode(String deviceCode, String userCode, String verificationUri,
                   int expiresIn, int interval) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.expiresIn = expiresIn;
            this.interval = interval;
        }
    }

    /** 向 GitHub 申请一次性授权码。 */
    public static DeviceCode requestCode(Context context, String clientId) throws ApiException {
        String id = clientId == null ? "" : clientId.trim();
        if (id.length() == 0) {
            throw new ApiException("尚未配置 GitHub OAuth Client ID");
        }
        JSONObject json = post(context, CODE_ENDPOINT,
                "client_id=" + encode(id) + "&scope=" + encode(SCOPE));
        String deviceCode = text(json, "device_code");
        String userCode = text(json, "user_code");
        String uri = text(json, "verification_uri");
        if (deviceCode.length() == 0 || userCode.length() == 0) {
            String error = text(json, "error_description");
            if (error.length() == 0) {
                error = text(json, "error");
            }
            throw new ApiException("申请授权码失败"
                    + (error.length() == 0 ? "" : "：" + error));
        }
        if (uri.length() == 0) {
            uri = DEVICE_URL;
        }
        return new DeviceCode(deviceCode, userCode, uri,
                json.optInt("expires_in", 900), Math.max(5, json.optInt("interval", 5)));
    }

    /**
     * 轮询等待用户在浏览器完成授权，成功返回 access token。
     * 取消或超时抛出 ApiException。
     */
    public static String pollToken(Context context, String clientId, String deviceCode,
                                   int interval, int expiresIn, AtomicBoolean cancelled)
            throws ApiException {
        long deadline = System.currentTimeMillis() + Math.max(60, expiresIn) * 1000L;
        int waitSeconds = Math.max(5, interval);
        while (System.currentTimeMillis() < deadline) {
            sleep(waitSeconds * 1000L);
            if (cancelled != null && cancelled.get()) {
                throw new ApiException("已取消 GitHub 登录");
            }
            JSONObject json = post(context, TOKEN_ENDPOINT,
                    "client_id=" + encode(clientId)
                            + "&device_code=" + encode(deviceCode)
                            + "&grant_type=urn:ietf:params:oauth:grant-type:device_code");
            String token = text(json, "access_token");
            if (token.length() > 0) {
                return token;
            }
            String error = text(json, "error");
            if ("authorization_pending".equals(error)) {
                continue;
            }
            if ("slow_down".equals(error)) {
                waitSeconds += 5;
                continue;
            }
            if ("expired_token".equals(error)) {
                throw new ApiException("授权码已过期，请重新发起浏览器登录");
            }
            if ("access_denied".equals(error)) {
                throw new ApiException("你取消了 GitHub 授权");
            }
            String description = text(json, "error_description");
            throw new ApiException("GitHub 授权失败"
                    + (description.length() == 0 ? "" : "：" + description));
        }
        throw new ApiException("GitHub 授权超时，请重新发起浏览器登录");
    }

    private static JSONObject post(Context context, String endpoint, String form)
            throws ApiException {
        ApiClient.ensureTls(context);
        HttpURLConnection connection = null;
        try {
            byte[] payload = form.getBytes("UTF-8");
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "RuiDSChat/3.1 (Android)");
            connection.setFixedLengthStreamingMode(payload.length);

            OutputStream output = connection.getOutputStream();
            output.write(payload);
            output.flush();
            output.close();

            int code = connection.getResponseCode();
            String body = readAll(code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            JSONObject json;
            try {
                json = new JSONObject(body);
            } catch (Exception e) {
                throw new ApiException("GitHub 返回内容无法解析", code, body);
            }
            if (code < 200 || code >= 300) {
                String error = text(json, "error_description");
                if (error.length() == 0) {
                    error = text(json, "error");
                }
                throw new ApiException("GitHub 登录请求失败"
                        + (error.length() == 0 ? "" : "：" + error), code, body);
            }
            return json;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            String detail = describe(e);
            throw new ApiException("网络错误：" + detail, e, detail);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 把异常链拼成可读文本，便于定位 SSL 等底层错误。 */
    private static String describe(Throwable error) {
        StringBuilder builder = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 5) {
            if (builder.length() > 0) {
                builder.append(" ← ");
            }
            builder.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && message.length() > 0) {
                builder.append("：").append(message);
            }
            current = current.getCause();
            depth++;
        }
        return builder.toString();
    }

    private static String text(JSONObject object, String key) {
        if (object == null) {
            return "";
        }
        Object value = object.opt(key);
        return value instanceof String ? (String) value : "";
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return value == null ? "" : value;
        }
    }

    private static void sleep(long millis) throws ApiException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("已取消 GitHub 登录");
        }
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
}
