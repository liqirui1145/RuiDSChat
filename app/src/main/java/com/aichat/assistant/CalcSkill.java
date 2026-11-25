package com.aichat.assistant;

import android.content.Context;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 计算器技能：四则运算与日期差，纯本地计算，结果可复核。
 */
public final class CalcSkill implements AgentSkill {

    @Override
    public String id() {
        return "calc";
    }

    @Override
    public String label() {
        return "计算器";
    }

    @Override
    public boolean usesTools() {
        return true;
    }

    @Override
    public String systemPrompt(Context context) {
        return "你是计算助手，所有数值计算必须通过工具完成，不要心算。\n"
                + "可用工具：\n"
                + "calculate {expression} 计算四则运算，支持 + - * / % ^ 与括号，例如 (12.5*3+8)/2\n"
                + "date_diff {start,end} 计算两个日期相差天数，格式 yyyy-MM-dd\n"
                + "规则：\n"
                + "1. 先调用工具，再根据工具返回值作答。\n"
                + "2. 结果为小数时保留合理位数，并说明单位或假设。\n"
                + "3. 表达式无法计算时，指出具体错误位置。\n"
                + "4. 回复用简体中文，先给结果再给简要过程。";
    }

    @Override
    public void checkReady(Context context) throws ApiException {
    }

    @Override
    public String execute(Context context, String tool, JSONObject args) throws ApiException {
        if ("calculate".equals(tool)) {
            String expression = text(args, "expression");
            if (expression.length() == 0) {
                throw new ApiException("缺少表达式 expression");
            }
            double value = new Parser(expression).parse();
            return expression + " = " + format(value);
        }
        if ("date_diff".equals(tool)) {
            String start = text(args, "start");
            String end = text(args, "end");
            long from = parseDate(start);
            long to = parseDate(end);
            long days = Math.round((to - from) / 86400000.0);
            return start + " 到 " + end + " 相差 " + days + " 天";
        }
        throw new ApiException("不支持的工具：" + tool);
    }

    @Override
    public String describe(String tool, JSONObject args) {
        if ("date_diff".equals(tool)) {
            return "计算日期差";
        }
        return "计算 " + text(args, "expression");
    }

    private static long parseDate(String value) throws ApiException {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            format.setLenient(false);
            Date date = format.parse(value);
            if (date == null) {
                throw new ApiException("日期格式应为 yyyy-MM-dd：" + value);
            }
            return date.getTime();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("日期格式应为 yyyy-MM-dd：" + value);
        }
    }

    private static String format(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "无法计算（结果不是有限数值）";
        }
        if (Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.valueOf((long) Math.rint(value));
        }
        String text = String.format(Locale.US, "%.6f", value);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static String text(JSONObject object, String key) {
        if (object == null) {
            return "";
        }
        Object value = object.opt(key);
        return value instanceof String ? ((String) value).trim() : "";
    }

    /** 递归下降解析四则运算表达式。 */
    private static final class Parser {

        private final String input;
        private int pos;

        Parser(String input) {
            this.input = input.replace('×', '*').replace('÷', '/')
                    .replace('（', '(').replace('）', ')').replace(' ', '\0')
                    .replace("\0", "");
        }

        double parse() throws ApiException {
            if (input.length() == 0) {
                throw new ApiException("表达式为空");
            }
            double value = parseExpression();
            if (pos < input.length()) {
                throw new ApiException("无法识别的字符：" + input.charAt(pos));
            }
            return value;
        }

        private double parseExpression() throws ApiException {
            double value = parseTerm();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '+') {
                    pos++;
                    value += parseTerm();
                } else if (c == '-') {
                    pos++;
                    value -= parseTerm();
                } else {
                    break;
                }
            }
            return value;
        }

        private double parseTerm() throws ApiException {
            double value = parseFactor();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '*') {
                    pos++;
                    value *= parseFactor();
                } else if (c == '/') {
                    pos++;
                    double divisor = parseFactor();
                    if (divisor == 0) {
                        throw new ApiException("除数不能为 0");
                    }
                    value /= divisor;
                } else if (c == '%') {
                    pos++;
                    double divisor = parseFactor();
                    if (divisor == 0) {
                        throw new ApiException("取余的除数不能为 0");
                    }
                    value %= divisor;
                } else {
                    break;
                }
            }
            return value;
        }

        private double parseFactor() throws ApiException {
            if (pos < input.length() && input.charAt(pos) == '-') {
                pos++;
                return -parseFactor();
            }
            double base = parsePrimary();
            if (pos < input.length() && input.charAt(pos) == '^') {
                pos++;
                double exponent = parseFactor();
                return Math.pow(base, exponent);
            }
            return base;
        }

        private double parsePrimary() throws ApiException {
            if (pos >= input.length()) {
                throw new ApiException("表达式不完整");
            }
            char c = input.charAt(pos);
            if (c == '(') {
                pos++;
                double value = parseExpression();
                if (pos >= input.length() || input.charAt(pos) != ')') {
                    throw new ApiException("缺少右括号");
                }
                pos++;
                return value;
            }
            int start = pos;
            while (pos < input.length()) {
                char ch = input.charAt(pos);
                if (Character.isDigit(ch) || ch == '.') {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new ApiException("无法识别的字符：" + c);
            }
            try {
                return Double.parseDouble(input.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new ApiException("数字格式错误：" + input.substring(start, pos));
            }
        }
    }
}
