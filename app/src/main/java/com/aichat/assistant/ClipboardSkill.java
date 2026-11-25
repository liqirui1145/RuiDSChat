package com.aichat.assistant;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import org.json.JSONObject;

/**
 * 剪贴板技能：读取剪贴板内容做总结、翻译或改写，也可以把结果写回剪贴板。
 */
public final class ClipboardSkill implements AgentSkill {

    private static final int MAX_CHARS = 20000;

    @Override
    public String id() {
        return "clipboard";
    }

    @Override
    public String label() {
        return "剪贴板";
    }

    @Override
    public boolean usesTools() {
        return true;
    }

    @Override
    public String systemPrompt(Context context) {
        return "你是剪贴板处理助手，可以读取用户当前复制的内容，并在处理后写回剪贴板。\n"
                + "可用工具：\n"
                + "read_clipboard {} 读取剪贴板文本\n"
                + "write_clipboard {text} 把文本写入剪贴板\n"
                + "规则：\n"
                + "1. 用户提到「剪贴板」「我刚复制的」「这段文字」而对话里没有内容时，先调用 read_clipboard。\n"
                + "2. 需要把结果放回剪贴板时，调用 write_clipboard，并在回复里说明已写入。\n"
                + "3. 剪贴板为空或读取失败时，直接告知用户，不要编造内容。\n"
                + "4. 回复用简体中文，改写类任务先给结果。";
    }

    @Override
    public void checkReady(Context context) throws ApiException {
        if (context.getSystemService(Context.CLIPBOARD_SERVICE) == null) {
            throw new ApiException("当前系统不支持剪贴板");
        }
    }

    @Override
    public String execute(Context context, String tool, JSONObject args) throws ApiException {
        ClipboardManager manager = (ClipboardManager) context.getSystemService(
                Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            throw new ApiException("当前系统不支持剪贴板");
        }
        if ("read_clipboard".equals(tool)) {
            if (!manager.hasPrimaryClip()) {
                throw new ApiException("剪贴板为空");
            }
            ClipData clip = manager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                throw new ApiException("剪贴板为空");
            }
            CharSequence text = clip.getItemAt(0).coerceToText(context);
            if (text == null || text.length() == 0) {
                throw new ApiException("剪贴板中没有文本内容");
            }
            String value = text.toString();
            if (value.length() > MAX_CHARS) {
                value = value.substring(0, MAX_CHARS) + "\n【内容已截断】";
            }
            return value;
        }
        if ("write_clipboard".equals(tool)) {
            String text = text(args, "text");
            if (text.length() == 0) {
                throw new ApiException("缺少要写入的文本 text");
            }
            manager.setPrimaryClip(ClipData.newPlainText("RuiDS Chat", text));
            return "已写入剪贴板，共 " + text.length() + " 个字符";
        }
        throw new ApiException("不支持的工具：" + tool);
    }

    @Override
    public String describe(String tool, JSONObject args) {
        if ("write_clipboard".equals(tool)) {
            return "写入剪贴板";
        }
        return "读取剪贴板";
    }

    private static String text(JSONObject object, String key) {
        if (object == null) {
            return "";
        }
        Object value = object.opt(key);
        return value instanceof String ? (String) value : "";
    }
}
