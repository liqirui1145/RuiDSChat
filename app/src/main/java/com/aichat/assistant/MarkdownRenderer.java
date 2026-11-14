package com.aichat.assistant;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量 Markdown 渲染器：把 AI 回复里的 Markdown 语法转成 Spannable。
 * 仅依赖 Android framework，兼容 API 19，不引入第三方库。
 * 支持：加粗、斜体、加粗斜体、删除线、行内代码、代码块、标题、引用、
 * 分隔线、无序列表、链接，以及表格（等宽对齐显示）。
 */
public final class MarkdownRenderer {

    private MarkdownRenderer() {
    }

    /** 当前主题下用到的颜色，深色模式会自动切换。 */
    private static final class Colors {
        final int codeBg;
        final int codeText;
        final int link;
        final int quote;
        final int rule;
        final int math;

        Colors(Context context) {
            Resources r = context.getResources();
            codeBg = r.getColor(R.color.md_code_bg);
            codeText = r.getColor(R.color.md_code_text);
            link = r.getColor(R.color.md_link);
            quote = r.getColor(R.color.md_quote);
            rule = r.getColor(R.color.md_rule);
            math = r.getColor(R.color.text_primary);
        }
    }

    public static CharSequence render(Context context, String text) {
        if (text == null || text.length() == 0) {
            return text == null ? "" : text;
        }
        Colors colors = new Colors(context);
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = normalized.split("\n", -1);

        boolean inCode = false;
        StringBuilder code = new StringBuilder();
        boolean inDisplay = false;
        String displayClose = "";
        StringBuilder display = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                if (inCode) {
                    inCode = false;
                    appendCodeBlock(out, code.toString(), colors);
                    code.setLength(0);
                } else {
                    inCode = true;
                    code.setLength(0);
                }
                continue;
            }
            if (inCode) {
                if (code.length() > 0) {
                    code.append('\n');
                }
                code.append(line);
                continue;
            }

            // $$ ... $$ 或 \[ ... \] 块级公式，可跨行
            if (inDisplay) {
                int close = line.indexOf(displayClose);
                if (close >= 0) {
                    display.append(' ').append(line.substring(0, close));
                    appendDisplayMath(out, display.toString(), colors);
                    inDisplay = false;
                    display.setLength(0);
                } else {
                    display.append(' ').append(line);
                }
                continue;
            }
            if (trimmed.startsWith("$$") || trimmed.startsWith("\\[")) {
                String closer = trimmed.startsWith("$$") ? "$$" : "\\]";
                String content = trimmed.substring(2);
                int close = content.indexOf(closer);
                if (close >= 0) {
                    appendDisplayMath(out, content.substring(0, close), colors);
                } else {
                    inDisplay = true;
                    displayClose = closer;
                    display.setLength(0);
                    display.append(content);
                }
                continue;
            }

            // 表格：当前行像表头，且下一行是 |---|---| 分隔行
            if (isTableRow(trimmed) && i + 1 < lines.length
                    && isTableSeparator(lines[i + 1].trim())) {
                List<String[]> rows = new ArrayList<String[]>();
                rows.add(splitRow(trimmed));
                int next = i + 2;
                while (next < lines.length) {
                    String rowLine = lines[next].trim();
                    if (rowLine.length() == 0 || !isTableRow(rowLine)
                            || isTableSeparator(rowLine)) {
                        break;
                    }
                    rows.add(splitRow(rowLine));
                    next++;
                }
                appendTable(out, rows, colors);
                i = next - 1;
                continue;
            }

            appendBlockLine(out, line, colors);
        }
        if (inCode) {
            appendCodeBlock(out, code.toString(), colors);
        }
        if (inDisplay) {
            appendDisplayMath(out, display.toString(), colors);
        }
        return out;
    }

    private static void appendBlockLine(SpannableStringBuilder out, String line, Colors colors) {
        String trimmed = line.trim();
        if (out.length() > 0) {
            out.append('\n');
        }
        if (trimmed.length() == 0) {
            return;
        }

        int level = headingLevel(trimmed);
        if (level > 0) {
            int start = out.length();
            appendInline(out, trimmed.substring(level).trim(), colors);
            int end = out.length();
            float scale = level == 1 ? 1.38f : (level == 2 ? 1.24f : (level == 3 ? 1.12f : 1.05f));
            out.setSpan(new RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        if (trimmed.equals("---") || trimmed.equals("***") || trimmed.equals("___")) {
            int start = out.length();
            out.append("────────────────────");
            out.setSpan(new ForegroundColorSpan(colors.rule), start, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        if (trimmed.startsWith(">")) {
            int start = out.length();
            out.append("▎");
            String body = trimmed.substring(1);
            if (body.startsWith(" ")) {
                body = body.substring(1);
            }
            appendInline(out, body, colors);
            out.setSpan(new ForegroundColorSpan(colors.quote), start, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        // 列表：保留缩进，把 - / * / + 换成圆点
        int indentEnd = 0;
        while (indentEnd < line.length()
                && (line.charAt(indentEnd) == ' ' || line.charAt(indentEnd) == '\t')) {
            indentEnd++;
        }
        String indented = line.substring(0, indentEnd);
        String rest = line.substring(indentEnd);
        if (rest.startsWith("- ") || rest.startsWith("* ") || rest.startsWith("+ ")) {
            out.append(indented);
            out.append("• ");
            appendInline(out, rest.substring(2), colors);
            return;
        }

        appendInline(out, line, colors);
    }

    private static int headingLevel(String line) {
        int count = 0;
        while (count < line.length() && count < 6 && line.charAt(count) == '#') {
            count++;
        }
        if (count > 0 && count < line.length() && line.charAt(count) == ' ') {
            return count + 1;
        }
        return 0;
    }

    private static void appendCodeBlock(SpannableStringBuilder out, String code, Colors colors) {
        if (code.length() == 0) {
            return;
        }
        int start = out.length();
        out.append(code);
        int end = out.length();
        out.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(0.92f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new BackgroundColorSpan(colors.codeBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(colors.codeText), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void appendInline(SpannableStringBuilder out, String s, Colors colors) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);

            // LaTeX 公式：$...$、$$...$$、\(...\)、\[...\]
            if (c == '$') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '$') {
                    int end = s.indexOf("$$", i + 2);
                    if (end > i + 2 && appendMath(out, s.substring(i + 2, end), colors, true)) {
                        i = end + 2;
                        continue;
                    }
                } else {
                    int end = s.indexOf('$', i + 1);
                    if (end > i + 1) {
                        String latex = s.substring(i + 1, end);
                        if (looksLikeLatex(latex) && appendMath(out, latex, colors, false)) {
                            i = end + 1;
                            continue;
                        }
                    }
                }
            }
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '(') {
                    int end = s.indexOf("\\)", i + 2);
                    if (end > i + 2 && appendMath(out, s.substring(i + 2, end), colors, false)) {
                        i = end + 2;
                        continue;
                    }
                } else if (next == '[') {
                    int end = s.indexOf("\\]", i + 2);
                    if (end > i + 2 && appendMath(out, s.substring(i + 2, end), colors, true)) {
                        i = end + 2;
                        continue;
                    }
                }
            }

            if (c == '\\' && i + 1 < s.length() && isMarkdownChar(s.charAt(i + 1))) {
                out.append(s.charAt(i + 1));
                i += 2;
                continue;
            }

            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1) {
                    int start = out.length();
                    out.append(s, i + 1, end);
                    int stop = out.length();
                    out.setSpan(new TypefaceSpan("monospace"), start, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(colors.codeBg), start, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ForegroundColorSpan(colors.codeText), start, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }

            if (c == '[') {
                int close = s.indexOf(']', i + 1);
                if (close > i + 1 && close + 1 < s.length() && s.charAt(close + 1) == '(') {
                    int paren = s.indexOf(')', close + 2);
                    if (paren > close + 2) {
                        int start = out.length();
                        out.append(s, i + 1, close);
                        int stop = out.length();
                        out.setSpan(new ForegroundColorSpan(colors.link), start, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new UnderlineSpan(), start, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = paren + 1;
                        continue;
                    }
                }
            }

            if (c == '~' && i + 1 < s.length() && s.charAt(i + 1) == '~') {
                int end = s.indexOf("~~", i + 2);
                if (end > i + 2) {
                    int start = out.length();
                    out.append(s, i + 2, end);
                    out.setSpan(new StrikethroughSpan(), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }

            if (c == '*' || c == '_') {
                String marker = String.valueOf(c);
                // 三个连续标记：加粗 + 斜体
                if (i + 2 < s.length() && s.charAt(i + 1) == c && s.charAt(i + 2) == c) {
                    int end = s.indexOf(marker + marker + marker, i + 3);
                    if (end > i + 3 && isItalicBoundary(s, c, i, end + 3)) {
                        int start = out.length();
                        out.append(s, i + 3, end);
                        out.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), start, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = end + 3;
                        continue;
                    }
                }
                // 两个连续标记：加粗
                if (i + 1 < s.length() && s.charAt(i + 1) == c) {
                    int end = s.indexOf(marker + marker, i + 2);
                    if (end > i + 2 && isItalicBoundary(s, c, i, end + 2)) {
                        int start = out.length();
                        out.append(s, i + 2, end);
                        out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = end + 2;
                        continue;
                    }
                }
                // 单个标记：斜体
                int end = s.indexOf(marker, i + 1);
                if (end > i + 1 && isItalicBoundary(s, c, i, end + 1)) {
                    int start = out.length();
                    out.append(s, i + 1, end);
                    out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }

            out.append(c);
            i++;
        }
    }

    // ======================== 表格 ========================

    private static boolean isTableRow(String trimmed) {
        return trimmed.length() > 0 && trimmed.indexOf('|') >= 0;
    }

    /** 形如 | --- | :---: | ---: | 的分隔行。 */
    private static boolean isTableSeparator(String trimmed) {
        if (trimmed.length() == 0 || trimmed.indexOf('-') < 0 || trimmed.indexOf('|') < 0) {
            return false;
        }
        boolean hasDash = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '-') {
                hasDash = true;
            } else if (c != '|' && c != ':' && c != ' ') {
                return false;
            }
        }
        return hasDash;
    }

    private static String[] splitRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) {
            t = t.substring(1);
        }
        if (t.endsWith("|")) {
            t = t.substring(0, t.length() - 1);
        }
        List<String> cells = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\' && i + 1 < t.length() && t.charAt(i + 1) == '|') {
                sb.append('|');
                i++;
            } else if (c == '|') {
                cells.add(stripInline(sb.toString().trim()));
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        cells.add(stripInline(sb.toString().trim()));
        return cells.toArray(new String[0]);
    }

    /** 去掉表格单元格里不会增加显示宽度的标记。 */
    private static String stripInline(String s) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '[') {
                int close = s.indexOf(']', i + 1);
                if (close > i + 1 && close + 1 < s.length() && s.charAt(close + 1) == '(') {
                    int paren = s.indexOf(')', close + 2);
                    if (paren > close + 2) {
                        sb.append(s, i + 1, close);
                        i = paren + 1;
                        continue;
                    }
                }
            }
            if (c == '*' || c == '`' || c == '~') {
                i++;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString().trim();
    }

    private static void appendTable(SpannableStringBuilder out, List<String[]> rows, Colors colors) {
        if (rows.isEmpty()) {
            return;
        }
        int cols = 0;
        for (String[] row : rows) {
            cols = Math.max(cols, row.length);
        }
        if (cols == 0) {
            return;
        }

        int[] widths = new int[cols];
        for (String[] row : rows) {
            for (int c = 0; c < row.length; c++) {
                widths[c] = Math.max(widths[c], displayWidth(row[c]));
            }
        }
        for (int c = 0; c < cols; c++) {
            if (widths[c] < 1) {
                widths[c] = 1;
            }
        }

        if (out.length() > 0) {
            out.append('\n');
        }

        appendTableRow(out, rows.get(0), widths, true, colors);

        out.append('\n');
        int ruleStart = out.length();
        for (int c = 0; c < cols; c++) {
            if (c > 0) {
                out.append("┼");
            }
            for (int k = 0; k < widths[c]; k++) {
                out.append("─");
            }
        }
        out.setSpan(new ForegroundColorSpan(colors.rule), ruleStart, out.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        for (int r = 1; r < rows.size(); r++) {
            out.append('\n');
            appendTableRow(out, rows.get(r), widths, false, colors);
        }
    }

    private static void appendTableRow(SpannableStringBuilder out, String[] row, int[] widths,
                                       boolean header, Colors colors) {
        int start = out.length();
        for (int c = 0; c < widths.length; c++) {
            if (c > 0) {
                out.append(" │ ");
            }
            String cell = c < row.length ? row[c] : "";
            out.append(cell);
            int pad = widths[c] - displayWidth(cell);
            for (int k = 0; k < pad; k++) {
                out.append(' ');
            }
        }
        int end = out.length();
        out.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (header) {
            out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new ForegroundColorSpan(colors.codeText), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** 按「中文占 2 列、西文占 1 列」估算显示宽度，让表格在等宽字体下对齐。 */
    private static int displayWidth(String s) {
        int width = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length()
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                width += 2;
                i++;
            } else {
                width += isWideChar(c) ? 2 : 1;
            }
        }
        return width;
    }

    private static boolean isWideChar(char c) {
        return (c >= 0x1100 && c <= 0x115F)
                || (c >= 0x2E80 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE10 && c <= 0xFE19)
                || (c >= 0xFE30 && c <= 0xFE6F)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6);
    }

    /**
     * 判断下划线/星号是否构成合法的斜体边界，避免把 snake_case、文件路径里的 _ 误判。
     */
    private static boolean isItalicBoundary(String s, char marker, int open, int close) {
        if (marker == '*') {
            return true;
        }
        boolean leftOk = open == 0 || !Character.isLetterOrDigit(s.charAt(open - 1));
        boolean rightOk = close >= s.length() || !Character.isLetterOrDigit(s.charAt(close));
        return leftOk && rightOk;
    }

    private static boolean isMarkdownChar(char c) {
        return c == '*' || c == '_' || c == '`' || c == '~' || c == '\\'
                || c == '[' || c == ']' || c == '(' || c == ')' || c == '#' || c == '$';
    }

    // ======================== LaTeX 数学公式 ========================

    private static final Map<String, String> LATEX_SYMBOLS = new HashMap<String, String>();
    private static final Map<Character, String> SUP_SCRIPTS = new HashMap<Character, String>();
    private static final Map<Character, String> SUB_SCRIPTS = new HashMap<Character, String>();
    private static final Map<Character, String> DOUBLE_STRUCK = new HashMap<Character, String>();

    static {
        // 希腊字母
        LATEX_SYMBOLS.put("alpha", "α");
        LATEX_SYMBOLS.put("beta", "β");
        LATEX_SYMBOLS.put("gamma", "γ");
        LATEX_SYMBOLS.put("delta", "δ");
        LATEX_SYMBOLS.put("epsilon", "ε");
        LATEX_SYMBOLS.put("varepsilon", "ϵ");
        LATEX_SYMBOLS.put("zeta", "ζ");
        LATEX_SYMBOLS.put("eta", "η");
        LATEX_SYMBOLS.put("theta", "θ");
        LATEX_SYMBOLS.put("vartheta", "ϑ");
        LATEX_SYMBOLS.put("iota", "ι");
        LATEX_SYMBOLS.put("kappa", "κ");
        LATEX_SYMBOLS.put("lambda", "λ");
        LATEX_SYMBOLS.put("mu", "μ");
        LATEX_SYMBOLS.put("nu", "ν");
        LATEX_SYMBOLS.put("xi", "ξ");
        LATEX_SYMBOLS.put("omicron", "ο");
        LATEX_SYMBOLS.put("pi", "π");
        LATEX_SYMBOLS.put("varpi", "ϖ");
        LATEX_SYMBOLS.put("rho", "ρ");
        LATEX_SYMBOLS.put("varrho", "ϱ");
        LATEX_SYMBOLS.put("sigma", "σ");
        LATEX_SYMBOLS.put("varsigma", "ς");
        LATEX_SYMBOLS.put("tau", "τ");
        LATEX_SYMBOLS.put("upsilon", "υ");
        LATEX_SYMBOLS.put("phi", "φ");
        LATEX_SYMBOLS.put("varphi", "ϕ");
        LATEX_SYMBOLS.put("chi", "χ");
        LATEX_SYMBOLS.put("psi", "ψ");
        LATEX_SYMBOLS.put("omega", "ω");
        LATEX_SYMBOLS.put("Gamma", "Γ");
        LATEX_SYMBOLS.put("Delta", "Δ");
        LATEX_SYMBOLS.put("Theta", "Θ");
        LATEX_SYMBOLS.put("Lambda", "Λ");
        LATEX_SYMBOLS.put("Xi", "Ξ");
        LATEX_SYMBOLS.put("Pi", "Π");
        LATEX_SYMBOLS.put("Sigma", "Σ");
        LATEX_SYMBOLS.put("Upsilon", "Υ");
        LATEX_SYMBOLS.put("Phi", "Φ");
        LATEX_SYMBOLS.put("Psi", "Ψ");
        LATEX_SYMBOLS.put("Omega", "Ω");
        // 运算与关系
        LATEX_SYMBOLS.put("times", "×");
        LATEX_SYMBOLS.put("div", "÷");
        LATEX_SYMBOLS.put("cdot", "·");
        LATEX_SYMBOLS.put("pm", "±");
        LATEX_SYMBOLS.put("mp", "∓");
        LATEX_SYMBOLS.put("ast", "∗");
        LATEX_SYMBOLS.put("star", "⋆");
        LATEX_SYMBOLS.put("bullet", "•");
        LATEX_SYMBOLS.put("circ", "∘");
        LATEX_SYMBOLS.put("leq", "≤");
        LATEX_SYMBOLS.put("le", "≤");
        LATEX_SYMBOLS.put("geq", "≥");
        LATEX_SYMBOLS.put("ge", "≥");
        LATEX_SYMBOLS.put("neq", "≠");
        LATEX_SYMBOLS.put("ne", "≠");
        LATEX_SYMBOLS.put("approx", "≈");
        LATEX_SYMBOLS.put("sim", "∼");
        LATEX_SYMBOLS.put("simeq", "≃");
        LATEX_SYMBOLS.put("cong", "≅");
        LATEX_SYMBOLS.put("equiv", "≡");
        LATEX_SYMBOLS.put("propto", "∝");
        LATEX_SYMBOLS.put("ll", "≪");
        LATEX_SYMBOLS.put("gg", "≫");
        LATEX_SYMBOLS.put("infty", "∞");
        LATEX_SYMBOLS.put("partial", "∂");
        LATEX_SYMBOLS.put("nabla", "∇");
        LATEX_SYMBOLS.put("sum", "∑");
        LATEX_SYMBOLS.put("prod", "∏");
        LATEX_SYMBOLS.put("coprod", "∐");
        LATEX_SYMBOLS.put("int", "∫");
        LATEX_SYMBOLS.put("iint", "∬");
        LATEX_SYMBOLS.put("iiint", "∭");
        LATEX_SYMBOLS.put("oint", "∮");
        LATEX_SYMBOLS.put("forall", "∀");
        LATEX_SYMBOLS.put("exists", "∃");
        LATEX_SYMBOLS.put("nexists", "∄");
        LATEX_SYMBOLS.put("emptyset", "∅");
        LATEX_SYMBOLS.put("varnothing", "∅");
        LATEX_SYMBOLS.put("therefore", "∴");
        LATEX_SYMBOLS.put("because", "∵");
        LATEX_SYMBOLS.put("angle", "∠");
        LATEX_SYMBOLS.put("perp", "⊥");
        LATEX_SYMBOLS.put("parallel", "∥");
        LATEX_SYMBOLS.put("mid", "∣");
        LATEX_SYMBOLS.put("in", "∈");
        LATEX_SYMBOLS.put("notin", "∉");
        LATEX_SYMBOLS.put("ni", "∋");
        LATEX_SYMBOLS.put("subset", "⊂");
        LATEX_SYMBOLS.put("supset", "⊃");
        LATEX_SYMBOLS.put("subseteq", "⊆");
        LATEX_SYMBOLS.put("supseteq", "⊇");
        LATEX_SYMBOLS.put("cup", "∪");
        LATEX_SYMBOLS.put("cap", "∩");
        LATEX_SYMBOLS.put("bigcup", "⋃");
        LATEX_SYMBOLS.put("bigcap", "⋂");
        LATEX_SYMBOLS.put("setminus", "∖");
        LATEX_SYMBOLS.put("rightarrow", "→");
        LATEX_SYMBOLS.put("to", "→");
        LATEX_SYMBOLS.put("leftarrow", "←");
        LATEX_SYMBOLS.put("gets", "←");
        LATEX_SYMBOLS.put("leftrightarrow", "↔");
        LATEX_SYMBOLS.put("Rightarrow", "⇒");
        LATEX_SYMBOLS.put("Leftarrow", "⇐");
        LATEX_SYMBOLS.put("Leftrightarrow", "⇔");
        LATEX_SYMBOLS.put("implies", "⇒");
        LATEX_SYMBOLS.put("iff", "⇔");
        LATEX_SYMBOLS.put("uparrow", "↑");
        LATEX_SYMBOLS.put("downarrow", "↓");
        LATEX_SYMBOLS.put("Uparrow", "⇑");
        LATEX_SYMBOLS.put("Downarrow", "⇓");
        LATEX_SYMBOLS.put("mapsto", "↦");
        LATEX_SYMBOLS.put("ldots", "…");
        LATEX_SYMBOLS.put("dots", "…");
        LATEX_SYMBOLS.put("cdots", "⋯");
        LATEX_SYMBOLS.put("vdots", "⋮");
        LATEX_SYMBOLS.put("ddots", "⋱");
        LATEX_SYMBOLS.put("prime", "′");
        LATEX_SYMBOLS.put("degree", "°");
        LATEX_SYMBOLS.put("deg", "°");
        LATEX_SYMBOLS.put("langle", "⟨");
        LATEX_SYMBOLS.put("rangle", "⟩");
        LATEX_SYMBOLS.put("lfloor", "⌊");
        LATEX_SYMBOLS.put("rfloor", "⌋");
        LATEX_SYMBOLS.put("lceil", "⌈");
        LATEX_SYMBOLS.put("rceil", "⌉");
        LATEX_SYMBOLS.put("lbrace", "{");
        LATEX_SYMBOLS.put("rbrace", "}");
        LATEX_SYMBOLS.put("lbrack", "[");
        LATEX_SYMBOLS.put("rbrack", "]");
        LATEX_SYMBOLS.put("square", "□");
        LATEX_SYMBOLS.put("checkmark", "✓");
        LATEX_SYMBOLS.put("triangle", "△");
        LATEX_SYMBOLS.put("blacktriangle", "▲");
        LATEX_SYMBOLS.put("oplus", "⊕");
        LATEX_SYMBOLS.put("otimes", "⊗");
        LATEX_SYMBOLS.put("odot", "⊙");
        LATEX_SYMBOLS.put("wedge", "∧");
        LATEX_SYMBOLS.put("land", "∧");
        LATEX_SYMBOLS.put("vee", "∨");
        LATEX_SYMBOLS.put("lor", "∨");
        LATEX_SYMBOLS.put("neg", "¬");
        LATEX_SYMBOLS.put("lnot", "¬");
        LATEX_SYMBOLS.put("aleph", "ℵ");
        LATEX_SYMBOLS.put("hbar", "ℏ");
        LATEX_SYMBOLS.put("ell", "ℓ");
        LATEX_SYMBOLS.put("wp", "℘");
        LATEX_SYMBOLS.put("dag", "†");
        LATEX_SYMBOLS.put("ddag", "‡");
        LATEX_SYMBOLS.put("S", "§");
        LATEX_SYMBOLS.put("P", "¶");

        // 上标
        SUP_SCRIPTS.put('0', "⁰");
        SUP_SCRIPTS.put('1', "¹");
        SUP_SCRIPTS.put('2', "²");
        SUP_SCRIPTS.put('3', "³");
        SUP_SCRIPTS.put('4', "⁴");
        SUP_SCRIPTS.put('5', "⁵");
        SUP_SCRIPTS.put('6', "⁶");
        SUP_SCRIPTS.put('7', "⁷");
        SUP_SCRIPTS.put('8', "⁸");
        SUP_SCRIPTS.put('9', "⁹");
        SUP_SCRIPTS.put('+', "⁺");
        SUP_SCRIPTS.put('-', "⁻");
        SUP_SCRIPTS.put('=', "⁼");
        SUP_SCRIPTS.put('(', "⁽");
        SUP_SCRIPTS.put(')', "⁾");
        SUP_SCRIPTS.put('n', "ⁿ");
        SUP_SCRIPTS.put('i', "ⁱ");
        SUP_SCRIPTS.put('x', "ˣ");
        SUP_SCRIPTS.put('y', "ʸ");
        SUP_SCRIPTS.put('a', "ᵃ");
        SUP_SCRIPTS.put('b', "ᵇ");
        SUP_SCRIPTS.put('c', "ᶜ");
        SUP_SCRIPTS.put('d', "ᵈ");
        SUP_SCRIPTS.put('e', "ᵉ");
        SUP_SCRIPTS.put('f', "ᶠ");
        SUP_SCRIPTS.put('g', "ᵍ");
        SUP_SCRIPTS.put('h', "ʰ");
        SUP_SCRIPTS.put('j', "ʲ");
        SUP_SCRIPTS.put('k', "ᵏ");
        SUP_SCRIPTS.put('l', "ˡ");
        SUP_SCRIPTS.put('m', "ᵐ");
        SUP_SCRIPTS.put('o', "ᵒ");
        SUP_SCRIPTS.put('p', "ᵖ");
        SUP_SCRIPTS.put('r', "ʳ");
        SUP_SCRIPTS.put('s', "ˢ");
        SUP_SCRIPTS.put('t', "ᵗ");
        SUP_SCRIPTS.put('u', "ᵘ");
        SUP_SCRIPTS.put('v', "ᵛ");
        SUP_SCRIPTS.put('w', "ʷ");
        SUP_SCRIPTS.put('z', "ᶻ");
        SUP_SCRIPTS.put('T', "ᵀ");
        SUP_SCRIPTS.put('A', "ᴬ");
        SUP_SCRIPTS.put('B', "ᴮ");
        SUP_SCRIPTS.put('D', "ᴰ");
        SUP_SCRIPTS.put('E', "ᴱ");
        SUP_SCRIPTS.put('G', "ᴳ");
        SUP_SCRIPTS.put('H', "ᴴ");
        SUP_SCRIPTS.put('I', "ᴵ");
        SUP_SCRIPTS.put('J', "ᴶ");
        SUP_SCRIPTS.put('K', "ᴷ");
        SUP_SCRIPTS.put('L', "ᴸ");
        SUP_SCRIPTS.put('M', "ᴹ");
        SUP_SCRIPTS.put('N', "ᴺ");
        SUP_SCRIPTS.put('O', "ᴼ");
        SUP_SCRIPTS.put('P', "ᴾ");
        SUP_SCRIPTS.put('R', "ᴿ");
        SUP_SCRIPTS.put('U', "ᵁ");
        SUP_SCRIPTS.put('V', "ⱽ");
        SUP_SCRIPTS.put('W', "ᵂ");

        // 下标
        SUB_SCRIPTS.put('0', "₀");
        SUB_SCRIPTS.put('1', "₁");
        SUB_SCRIPTS.put('2', "₂");
        SUB_SCRIPTS.put('3', "₃");
        SUB_SCRIPTS.put('4', "₄");
        SUB_SCRIPTS.put('5', "₅");
        SUB_SCRIPTS.put('6', "₆");
        SUB_SCRIPTS.put('7', "₇");
        SUB_SCRIPTS.put('8', "₈");
        SUB_SCRIPTS.put('9', "₉");
        SUB_SCRIPTS.put('+', "₊");
        SUB_SCRIPTS.put('-', "₋");
        SUB_SCRIPTS.put('=', "₌");
        SUB_SCRIPTS.put('(', "₍");
        SUB_SCRIPTS.put(')', "₎");
        SUB_SCRIPTS.put('a', "ₐ");
        SUB_SCRIPTS.put('e', "ₑ");
        SUB_SCRIPTS.put('h', "ₕ");
        SUB_SCRIPTS.put('i', "ᵢ");
        SUB_SCRIPTS.put('j', "ⱼ");
        SUB_SCRIPTS.put('k', "ₖ");
        SUB_SCRIPTS.put('l', "ₗ");
        SUB_SCRIPTS.put('m', "ₘ");
        SUB_SCRIPTS.put('n', "ₙ");
        SUB_SCRIPTS.put('o', "ₒ");
        SUB_SCRIPTS.put('p', "ₚ");
        SUB_SCRIPTS.put('r', "ᵣ");
        SUB_SCRIPTS.put('s', "ₛ");
        SUB_SCRIPTS.put('t', "ₜ");
        SUB_SCRIPTS.put('u', "ᵤ");
        SUB_SCRIPTS.put('v', "ᵥ");
        SUB_SCRIPTS.put('x', "ₓ");

        // 双线体
        DOUBLE_STRUCK.put('R', "ℝ");
        DOUBLE_STRUCK.put('N', "ℕ");
        DOUBLE_STRUCK.put('Z', "ℤ");
        DOUBLE_STRUCK.put('Q', "ℚ");
        DOUBLE_STRUCK.put('C', "ℂ");
        DOUBLE_STRUCK.put('P', "ℙ");
        DOUBLE_STRUCK.put('H', "ℍ");
        DOUBLE_STRUCK.put('E', "𝔼");
        DOUBLE_STRUCK.put('B', "𝔹");
        DOUBLE_STRUCK.put('D', "𝔻");
        DOUBLE_STRUCK.put('F', "𝔽");
        DOUBLE_STRUCK.put('G', "𝔾");
        DOUBLE_STRUCK.put('I', "𝕀");
        DOUBLE_STRUCK.put('K', "𝕂");
        DOUBLE_STRUCK.put('L', "𝕃");
        DOUBLE_STRUCK.put('M', "𝕄");
        DOUBLE_STRUCK.put('T', "𝕋");
        DOUBLE_STRUCK.put('U', "𝕌");
        DOUBLE_STRUCK.put('V', "𝕍");
        DOUBLE_STRUCK.put('W', "𝕎");
        DOUBLE_STRUCK.put('X', "𝕏");
        DOUBLE_STRUCK.put('Y', "𝕐");
    }

    private static void appendDisplayMath(SpannableStringBuilder out, String latex, Colors colors) {
        String text = latexToText(latex).trim();
        if (text.length() == 0) {
            return;
        }
        if (out.length() > 0) {
            out.append('\n');
        }
        int start = out.length();
        out.append("  ").append(text);
        int end = out.length();
        out.setSpan(new TypefaceSpan("serif"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(colors.math), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(1.06f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append('\n');
    }

    private static boolean appendMath(SpannableStringBuilder out, String latex, Colors colors,
                                      boolean display) {
        String text = latexToText(latex).trim();
        if (text.length() == 0) {
            return false;
        }
        int start = out.length();
        out.append(text);
        int end = out.length();
        out.setSpan(new TypefaceSpan("serif"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(colors.math), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (display) {
            out.setSpan(new RelativeSizeSpan(1.06f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return true;
    }

    /** 判断 $...$ 内容是否像公式，避免把货币金额（如 $5.00）误渲染。 */
    private static boolean looksLikeLatex(String content) {
        if (content.length() == 0) {
            return false;
        }
        if (content.charAt(0) == ' ' || content.charAt(content.length() - 1) == ' ') {
            return false;
        }
        boolean hasLetter = false;
        boolean hasSpace = false;
        boolean hasMarkup = false;
        boolean hasOperator = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (c == ' ' || c == '\t') {
                hasSpace = true;
            } else if (c == '+' || c == '-' || c == '*' || c == '/' || c == '=' || c == '<') {
                hasOperator = true;
            }
            if (c == '\\' || c == '^' || c == '_' || c == '{' || c == '}') {
                hasMarkup = true;
            }
        }
        if (hasMarkup) {
            return true;
        }
        return hasLetter && !hasSpace && (hasOperator || content.length() <= 20)
                && content.length() <= 24;
    }

    private static String latexToText(String latex) {
        StringBuilder out = new StringBuilder();
        List<String> envs = new ArrayList<String>();
        int[] pos = {0};
        convertLatex(latex, out, pos, envs);
        return out.toString();
    }

    private static void convertLatex(String s, StringBuilder out, int[] pos, List<String> envs) {
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (c == '\\') {
                pos[0]++;
                if (pos[0] >= s.length()) {
                    break;
                }
                char next = s.charAt(pos[0]);
                if (!Character.isLetter(next)) {
                    pos[0]++;
                    if (next == '\\') {
                        out.append('\n');
                    } else if (next == ' ' || next == ';' || next == ':' || next == '>') {
                        out.append(' ');
                    } else if (next == '{' || next == '}' || next == '%' || next == '&'
                            || next == '#' || next == '_' || next == '$') {
                        out.append(next);
                    } else if (next == '|') {
                        out.append('‖');
                    } else if (next == ',' || next == '!') {
                        // 细间距，忽略
                    } else {
                        out.append(next);
                    }
                    continue;
                }
                int start = pos[0];
                while (pos[0] < s.length() && Character.isLetter(s.charAt(pos[0]))) {
                    pos[0]++;
                }
                handleCommand(s.substring(start, pos[0]), s, out, pos, envs);
                continue;
            }
            if (c == '{') {
                pos[0]++;
                convertLatex(s, out, pos, envs);
                if (pos[0] < s.length() && s.charAt(pos[0]) == '}') {
                    pos[0]++;
                }
                continue;
            }
            if (c == '}') {
                pos[0]++;
                continue;
            }
            if (c == '^' || c == '_') {
                pos[0]++;
                String argument = readArgument(s, pos);
                out.append(script(c, latexToText(argument)));
                continue;
            }
            if (c == '&') {
                pos[0]++;
                out.append(isMatrixEnv(envs) ? " │ " : "  ");
                continue;
            }
            if (c == '$') {
                pos[0]++;
                continue;
            }
            out.append(c);
            pos[0]++;
        }
    }

    private static void handleCommand(String name, String s, StringBuilder out, int[] pos,
                                      List<String> envs) {
        if (name.equals("frac") || name.equals("dfrac") || name.equals("tfrac")
                || name.equals("cfrac")) {
            String numerator = latexToText(readArgument(s, pos));
            String denominator = latexToText(readArgument(s, pos));
            out.append(formatFraction(numerator, denominator));
            return;
        }
        if (name.equals("sqrt")) {
            String index = null;
            if (pos[0] < s.length() && s.charAt(pos[0]) == '[') {
                int close = s.indexOf(']', pos[0]);
                if (close > pos[0]) {
                    index = latexToText(s.substring(pos[0] + 1, close));
                    pos[0] = close + 1;
                }
            }
            String value = latexToText(readArgument(s, pos));
            if (index != null && index.length() > 0) {
                out.append(script('^', index));
            }
            out.append("√(").append(value).append(')');
            return;
        }
        if (name.equals("text") || name.equals("textrm") || name.equals("textsf")
                || name.equals("textbf") || name.equals("textit") || name.equals("mbox")
                || name.equals("operatorname") || name.equals("mathrm")
                || name.equals("mathit") || name.equals("mathsf") || name.equals("mathtt")
                || name.equals("mathbf") || name.equals("boldsymbol") || name.equals("mathcal")
                || name.equals("mathbb") || name.equals("mathnormal")) {
            String value = latexToText(readArgument(s, pos));
            out.append(name.equals("mathbb") ? doubleStruck(value) : value);
            return;
        }
        if (name.equals("overline") || name.equals("bar")) {
            out.append(latexToText(readArgument(s, pos))).append('\u0304');
            return;
        }
        if (name.equals("hat")) {
            out.append(latexToText(readArgument(s, pos))).append('\u0302');
            return;
        }
        if (name.equals("vec")) {
            out.append(latexToText(readArgument(s, pos))).append('\u20D7');
            return;
        }
        if (name.equals("dot")) {
            out.append(latexToText(readArgument(s, pos))).append('\u0307');
            return;
        }
        if (name.equals("ddot")) {
            out.append(latexToText(readArgument(s, pos))).append('\u0308');
            return;
        }
        if (name.equals("tilde")) {
            out.append(latexToText(readArgument(s, pos))).append('\u0303');
            return;
        }
        if (name.equals("acute")) {
            out.append(latexToText(readArgument(s, pos))).append('\u0301');
            return;
        }
        if (name.equals("grave")) {
            out.append(latexToText(readArgument(s, pos))).append('\u0300');
            return;
        }
        if (name.equals("left") || name.equals("right")
                || name.startsWith("big") || name.startsWith("Big")) {
            if (pos[0] < s.length() && s.charAt(pos[0]) == '.') {
                pos[0]++;
            }
            return;
        }
        if (name.equals("begin") || name.equals("end")) {
            String env = readArgument(s, pos).trim();
            if (name.equals("begin")) {
                if (env.equals("array") || env.equals("tabular")) {
                    readArgument(s, pos);
                }
                envs.add(env);
                out.append(envOpen(env));
            } else {
                out.append(envClose(env));
                if (!envs.isEmpty()) {
                    envs.remove(envs.size() - 1);
                }
            }
            return;
        }
        if (name.equals("quad")) {
            out.append("  ");
            return;
        }
        if (name.equals("qquad")) {
            out.append("    ");
            return;
        }
        if (name.equals("displaystyle") || name.equals("textstyle")
                || name.equals("scriptstyle") || name.equals("scriptscriptstyle")
                || name.equals("limits") || name.equals("nolimits")
                || name.equals("nonumber") || name.equals("notag")) {
            return;
        }
        if (name.equals("pmod")) {
            out.append(" (mod ").append(latexToText(readArgument(s, pos))).append(')');
            return;
        }
        if (name.equals("bmod") || name.equals("mod")) {
            out.append(" mod ");
            return;
        }
        String symbol = LATEX_SYMBOLS.get(name);
        if (symbol != null) {
            out.append(symbol);
        } else {
            out.append(name);
        }
    }

    /** 读取一个参数：{...} 分组、\命令 或单个字符。 */
    private static String readArgument(String s, int[] pos) {
        if (pos[0] >= s.length()) {
            return "";
        }
        if (s.charAt(pos[0]) == '{') {
            pos[0]++;
            int depth = 1;
            int start = pos[0];
            while (pos[0] < s.length()) {
                char c = s.charAt(pos[0]);
                if (c == '\\' && pos[0] + 1 < s.length()) {
                    pos[0] += 2;
                    continue;
                }
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
                pos[0]++;
            }
            String content = s.substring(start, Math.min(pos[0], s.length()));
            if (pos[0] < s.length() && s.charAt(pos[0]) == '}') {
                pos[0]++;
            }
            return content;
        }
        if (s.charAt(pos[0]) == '\\') {
            int start = pos[0];
            pos[0]++;
            if (pos[0] < s.length() && !Character.isLetter(s.charAt(pos[0]))) {
                pos[0]++;
            } else {
                while (pos[0] < s.length() && Character.isLetter(s.charAt(pos[0]))) {
                    pos[0]++;
                }
            }
            return s.substring(start, pos[0]);
        }
        char c = s.charAt(pos[0]);
        pos[0]++;
        return String.valueOf(c);
    }

    private static String script(char marker, String body) {
        if (body.length() == 0) {
            return "";
        }
        Map<Character, String> map = marker == '^' ? SUP_SCRIPTS : SUB_SCRIPTS;
        StringBuilder result = new StringBuilder();
        boolean mapped = true;
        for (int i = 0; i < body.length(); i++) {
            String value = map.get(body.charAt(i));
            if (value == null) {
                mapped = false;
                break;
            }
            result.append(value);
        }
        if (mapped) {
            return result.toString();
        }
        return marker == '^' ? "^(" + body + ")" : "_(" + body + ")";
    }

    private static String formatFraction(String numerator, String denominator) {
        return fractionPart(numerator) + "/" + fractionPart(denominator);
    }

    private static String fractionPart(String value) {
        if (value.length() == 0) {
            return "";
        }
        boolean simple = value.length() <= 3;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '.') {
                simple = false;
                break;
            }
        }
        return simple ? value : "(" + value + ")";
    }

    private static String doubleStruck(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            String mapped = DOUBLE_STRUCK.get(value.charAt(i));
            result.append(mapped == null ? String.valueOf(value.charAt(i)) : mapped);
        }
        return result.toString();
    }

    private static String envOpen(String env) {
        if (env.equals("pmatrix")) {
            return "(";
        }
        if (env.equals("bmatrix")) {
            return "[";
        }
        if (env.equals("Bmatrix")) {
            return "{";
        }
        if (env.equals("vmatrix")) {
            return "|";
        }
        if (env.equals("Vmatrix")) {
            return "‖";
        }
        if (env.equals("cases")) {
            return "{";
        }
        return "";
    }

    private static String envClose(String env) {
        if (env.equals("pmatrix")) {
            return ")";
        }
        if (env.equals("bmatrix")) {
            return "]";
        }
        if (env.equals("Bmatrix")) {
            return "}";
        }
        if (env.equals("vmatrix")) {
            return "|";
        }
        if (env.equals("Vmatrix")) {
            return "‖";
        }
        return "";
    }

    private static boolean isMatrixEnv(List<String> envs) {
        if (envs.isEmpty()) {
            return false;
        }
        String env = envs.get(envs.size() - 1);
        return env.indexOf("matrix") >= 0 || env.equals("array");
    }
}
