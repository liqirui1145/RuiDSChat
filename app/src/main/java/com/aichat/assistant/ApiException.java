package com.aichat.assistant;

public class ApiException extends Exception {

    /** HTTP 状态码，0 表示没有拿到响应（网络异常等）。 */
    private int statusCode;
    /** 原始诊断信息，例如响应体或异常类型。 */
    private String diagnostics = "";

    public ApiException(String message) {
        super(message);
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
    }

    public ApiException(String message, int statusCode, String rawResponse) {
        super(message);
        this.statusCode = statusCode;
        this.diagnostics = rawResponse == null ? "" : rawResponse;
    }

    public ApiException(String message, Throwable cause, String diagnostics) {
        super(message, cause);
        this.diagnostics = diagnostics == null ? "" : diagnostics;
    }

    public int getStatusCode() {
        return statusCode;
    }

    /** 供「查看详情」展示的原始错误信息。 */
    public String getDiagnostics() {
        StringBuilder builder = new StringBuilder();
        if (statusCode > 0) {
            builder.append("HTTP ").append(statusCode);
        }
        String detail = diagnostics == null ? "" : diagnostics.trim();
        if (detail.length() > 0) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(detail);
        }
        return builder.toString();
    }
}
