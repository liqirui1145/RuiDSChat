package com.aichat.assistant;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AlignmentSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.text.style.URLSpan;
import android.util.DisplayMetrics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
                    String rest = line.substring(close + displayClose.length()).trim();
                    if (rest.length() > 0) {
                        appendBlockLine(out, rest, colors);
                    }
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
                    String rest = content.substring(close + closer.length()).trim();
                    if (rest.length() > 0) {
                        appendBlockLine(out, rest, colors);
                    }
                } else {
                    inDisplay = true;
                    displayClose = closer;
                    display.setLength(0);
                    display.append(content);
                }
                continue;
            }

            // 未加 $ 定界符的公式环境，例如 AI 直接输出的 \begin{aligned}...\end{aligned}
            if (trimmed.startsWith("\\begin{")) {
                String env = environmentName(trimmed);
                if (isMathEnvironment(env)) {
                    String endTag = "\\end{" + env + "}";
                    int closeLine = trimmed.indexOf(endTag) >= 0 ? i : -1;
                    int next = i + 1;
                    while (closeLine < 0 && next < lines.length) {
                        if (lines[next].indexOf(endTag) >= 0) {
                            closeLine = next;
                        }
                        next++;
                    }
                    // 找不到 \end 就不当成公式，避免把后续正文全部吞掉
                    if (closeLine >= 0) {
                        StringBuilder math = new StringBuilder(trimmed);
                        for (int k = i + 1; k <= closeLine; k++) {
                            math.append('\n').append(lines[k]);
                        }
                        appendDisplayMath(out, math.toString(), colors);
                        i = closeLine;
                        continue;
                    }
                }
            }

            // 表格：当前行像表头，且下一行是 |---|---| 分隔行
            if (isTableRow(trimmed) && i + 1 < lines.length
                    && isTableSeparator(lines[i + 1].trim())) {
                List<String[]> rows = new ArrayList<String[]>();
                rows.add(splitRow(trimmed));
                int[] aligns = parseAlignments(lines[i + 1].trim());
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
                appendTable(out, rows, aligns, tableColumnBudget(context), colors);
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
            int depth = 0;
            while (trimmed.startsWith(">")) {
                trimmed = trimmed.substring(1).trim();
                depth++;
            }
            int start = out.length();
            for (int i = 0; i < Math.min(depth, 3); i++) {
                out.append("▎");
            }
            appendInline(out, trimmed, colors);
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
            String body = rest.substring(2);
            // 任务列表：- [ ] / - [x]
            if (body.equals("[ ]") || body.startsWith("[ ] ")) {
                out.append(indented);
                out.append("☐ ");
                appendInline(out, body.substring(3).trim(), colors);
                return;
            }
            if (body.length() >= 3 && body.charAt(0) == '['
                    && (body.charAt(1) == 'x' || body.charAt(1) == 'X')
                    && body.charAt(2) == ']') {
                out.append(indented);
                out.append("☑ ");
                appendInline(out, body.substring(3).trim(), colors);
                return;
            }
            out.append(indented);
            out.append("• ");
            appendInline(out, body, colors);
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
                        String label = s.substring(i + 1, close);
                        String target = s.substring(close + 2, paren).trim();
                        int space = target.indexOf(' ');
                        if (space > 0) {
                            // [文字](url "标题")，标题部分不参与链接
                            target = target.substring(0, space);
                        }
                        int start = out.length();
                        appendInline(out, label, colors);
                        int stop = out.length();
                        if (stop > start) {
                            out.setSpan(new ForegroundColorSpan(colors.link), start, stop,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            out.setSpan(new UnderlineSpan(), start, stop,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            String url = normalizeUrl(target);
                            if (url != null) {
                                out.setSpan(new URLSpan(url), start, stop,
                                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            }
                        }
                        i = paren + 1;
                        continue;
                    }
                }
            }

            // 自动链接：<https://example.com>
            if (c == '<') {
                int close = s.indexOf('>', i + 1);
                if (close > i + 1) {
                    String inner = s.substring(i + 1, close).trim();
                    String url = normalizeUrl(inner);
                    if (url != null) {
                        int start = out.length();
                        out.append(inner);
                        out.setSpan(new ForegroundColorSpan(colors.link), start, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new UnderlineSpan(), start, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new URLSpan(url), start, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = close + 1;
                        continue;
                    }
                }
            }

            if (c == '~' && i + 1 < s.length() && s.charAt(i + 1) == '~') {
                int end = s.indexOf("~~", i + 2);
                if (end > i + 2) {
                    int start = out.length();
                    appendInline(out, s.substring(i + 2, end), colors);
                    if (out.length() > start) {
                        out.setSpan(new StrikethroughSpan(), start, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
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
                        appendInline(out, s.substring(i + 3, end), colors);
                        if (out.length() > start) {
                            out.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), start, out.length(),
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        i = end + 3;
                        continue;
                    }
                }
                // 两个连续标记：加粗
                if (i + 1 < s.length() && s.charAt(i + 1) == c) {
                    int end = s.indexOf(marker + marker, i + 2);
                    if (end > i + 2 && isItalicBoundary(s, c, i, end + 2)) {
                        int start = out.length();
                        appendInline(out, s.substring(i + 2, end), colors);
                        if (out.length() > start) {
                            out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        i = end + 2;
                        continue;
                    }
                }
                // 单个标记：斜体
                int end = s.indexOf(marker, i + 1);
                if (end > i + 1 && isItalicBoundary(s, c, i, end + 1)) {
                    int start = out.length();
                    appendInline(out, s.substring(i + 1, end), colors);
                    if (out.length() > start) {
                        out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
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
            if (c == '$') {
                int end = s.indexOf('$', i + 1);
                if (end > i + 1) {
                    sb.append(plainMath(latexToText(s.substring(i + 1, end))));
                    i = end + 1;
                    continue;
                }
            }
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

    private static final int ALIGN_LEFT = 0;
    private static final int ALIGN_CENTER = 1;
    private static final int ALIGN_RIGHT = 2;
    /** 压缩列宽时保留的最小宽度（按等宽字符计）。 */
    private static final int MIN_COLUMN_WIDTH = 4;

    /** 解析 |---|:---:|---:| 里的对齐标记。 */
    private static int[] parseAlignments(String separator) {
        String[] cells = splitRow(separator);
        int[] aligns = new int[cells.length];
        for (int c = 0; c < cells.length; c++) {
            String spec = cells[c].trim();
            boolean left = spec.startsWith(":");
            boolean right = spec.endsWith(":");
            if (left && right) {
                aligns[c] = ALIGN_CENTER;
            } else if (right) {
                aligns[c] = ALIGN_RIGHT;
            } else {
                aligns[c] = ALIGN_LEFT;
            }
        }
        return aligns;
    }

    /** 按屏幕宽度估算表格一行最多能放多少个等宽字符。 */
    private static int tableColumnBudget(Context context) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        float charPx = 0.62f * 15f * dm.scaledDensity;
        if (charPx <= 0) {
            return 40;
        }
        // 左右列表内边距 + 气泡内边距，约 54dp
        int available = dm.widthPixels - (int) (54 * dm.density);
        int columns = (int) (available / charPx);
        if (columns < 12) {
            columns = 12;
        }
        return Math.min(columns, 120);
    }

    private static void appendTable(SpannableStringBuilder out, List<String[]> rows,
                                    int[] aligns, int maxColumns, Colors colors) {
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

        // 总宽超屏时逐步压缩最宽的一列，配合单元格折行避免横向溢出
        int gap = 3 * (cols - 1);
        int total = gap;
        for (int c = 0; c < cols; c++) {
            total += widths[c];
        }
        while (total > maxColumns) {
            int widest = -1;
            for (int c = 0; c < cols; c++) {
                if (widths[c] > MIN_COLUMN_WIDTH && (widest < 0 || widths[c] > widths[widest])) {
                    widest = c;
                }
            }
            if (widest < 0) {
                break;
            }
            widths[widest]--;
            total--;
        }

        if (out.length() > 0) {
            out.append('\n');
        }

        appendTableRow(out, rows.get(0), widths, aligns, true, colors);

        out.append('\n');
        int ruleStart = out.length();
        for (int c = 0; c < cols; c++) {
            // 数据行用 " │ " 分隔，分隔线用 "─┼─" 才能逐列对齐
            if (c > 0) {
                out.append("─┼─");
            }
            for (int k = 0; k < widths[c]; k++) {
                out.append("─");
            }
        }
        out.setSpan(new ForegroundColorSpan(colors.rule), ruleStart, out.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        for (int r = 1; r < rows.size(); r++) {
            out.append('\n');
            appendTableRow(out, rows.get(r), widths, aligns, false, colors);
        }
    }

    private static void appendTableRow(SpannableStringBuilder out, String[] row, int[] widths,
                                       int[] aligns, boolean header, Colors colors) {
        int start = out.length();

        // 先按列宽把每个单元格折成多行，再按最长的一列逐行输出
        List<List<String>> wrapped = new ArrayList<List<String>>();
        int lineCount = 1;
        for (int c = 0; c < widths.length; c++) {
            String cell = c < row.length ? row[c] : "";
            List<String> parts = wrapCell(cell, widths[c]);
            wrapped.add(parts);
            lineCount = Math.max(lineCount, parts.size());
        }

        for (int line = 0; line < lineCount; line++) {
            if (line > 0) {
                out.append('\n');
            }
            for (int c = 0; c < widths.length; c++) {
                if (c > 0) {
                    out.append(" │ ");
                }
                List<String> parts = wrapped.get(c);
                String cell = line < parts.size() ? parts.get(line) : "";
                int pad = widths[c] - displayWidth(cell);
                int leftPad = 0;
                int rightPad = pad;
                int align = c < aligns.length ? aligns[c] : ALIGN_LEFT;
                if (align == ALIGN_RIGHT) {
                    leftPad = pad;
                    rightPad = 0;
                } else if (align == ALIGN_CENTER) {
                    leftPad = pad / 2;
                    rightPad = pad - leftPad;
                }
                for (int k = 0; k < leftPad; k++) {
                    out.append(' ');
                }
                out.append(cell);
                for (int k = 0; k < rightPad; k++) {
                    out.append(' ');
                }
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

    /** 按显示宽度折行，中文等宽字符占 2 列。 */
    private static List<String> wrapCell(String cell, int width) {
        List<String> lines = new ArrayList<String>();
        if (width < 1) {
            width = 1;
        }
        if (cell.length() == 0) {
            lines.add("");
            return lines;
        }
        StringBuilder current = new StringBuilder();
        int currentWidth = 0;
        int i = 0;
        while (i < cell.length()) {
            char c = cell.charAt(i);
            int charLength = 1;
            int charWidth;
            if (Character.isHighSurrogate(c) && i + 1 < cell.length()
                    && Character.isLowSurrogate(cell.charAt(i + 1))) {
                charWidth = 2;
                charLength = 2;
            } else {
                charWidth = isWideChar(c) ? 2 : 1;
            }
            if (currentWidth + charWidth > width && currentWidth > 0) {
                lines.add(current.toString());
                current.setLength(0);
                currentWidth = 0;
            }
            current.append(cell, i, i + charLength);
            currentWidth += charWidth;
            i += charLength;
        }
        if (current.length() > 0 || lines.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
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

    /** 只把明确的网址转成可点击链接，普通尖括号文本返回 null。 */
    private static String normalizeUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String url = raw.trim();
        if (url.length() == 0 || url.indexOf(' ') >= 0 || url.indexOf('\n') >= 0) {
            return null;
        }
        String lower = url.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("mailto:") || lower.startsWith("tel:")) {
            return url;
        }
        if (lower.startsWith("www.")) {
            return "https://" + url;
        }
        return null;
    }

    /** 判断 \\\\[2pt] 里的参数是不是合法的行距，避免误吞 [0,1] 这类内容。 */
    private static boolean isLineBreakSpacing(String spec) {
        if (spec == null || spec.length() == 0) {
            return false;
        }
        int i = 0;
        if (spec.charAt(0) == '-') {
            i++;
        }
        int digitsStart = i;
        while (i < spec.length() && (Character.isDigit(spec.charAt(i)) || spec.charAt(i) == '.')) {
            i++;
        }
        if (i == digitsStart) {
            return false;
        }
        String unit = spec.substring(i).trim().toLowerCase(Locale.US);
        return unit.equals("pt") || unit.equals("ex") || unit.equals("em")
                || unit.equals("mm") || unit.equals("cm") || unit.equals("in")
                || unit.equals("pc") || unit.equals("mu");
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

    /*
     * 转换分两步：先把 LaTeX 转成「带标记的文本」，再把标记还原成 Span。
     * 标记用 \u0001..\u0008、\u000b、\u000c 等不会出现在正文里的控制字符，
     * 这样 x^{n+1}、\lim_{x \to 0} 这类 Unicode 上下标表示不了的内容
     * 也能用真正的上标 / 下标 Span 正确显示。
     */
    private static final char M_SUP = '\u0001';
    private static final char M_SUP_END = '\u0002';
    private static final char M_SUB = '\u0003';
    private static final char M_SUB_END = '\u0004';
    private static final char M_UP = '\u0005';
    private static final char M_UP_END = '\u0006';
    private static final char M_BOLD = '\u0007';
    private static final char M_BOLD_END = '\u0008';
    private static final char M_UL = '\u000b';
    private static final char M_UL_END = '\u000c';

    private static final Map<String, String> LATEX_SYMBOLS = new HashMap<String, String>();
    private static final Map<Character, String> SUP_SCRIPTS = new HashMap<Character, String>();
    private static final Map<Character, String> SUB_SCRIPTS = new HashMap<Character, String>();
    private static final Map<Character, String> DOUBLE_STRUCK = new HashMap<Character, String>();
    private static final Map<Character, String> MATHCAL = new HashMap<Character, String>();
    private static final Set<String> LATEX_FUNCTIONS = new HashSet<String>();
    private static final Set<String> IGNORED_COMMANDS = new HashSet<String>();
    private static final Set<String> MATH_ENVIRONMENTS = new HashSet<String>();

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

        // 补充：常用箭头 / 关系 / 运算符
        LATEX_SYMBOLS.put("longrightarrow", "⟶");
        LATEX_SYMBOLS.put("longleftarrow", "⟵");
        LATEX_SYMBOLS.put("longleftrightarrow", "⟷");
        LATEX_SYMBOLS.put("Longrightarrow", "⟹");
        LATEX_SYMBOLS.put("Longleftarrow", "⟸");
        LATEX_SYMBOLS.put("Longleftrightarrow", "⟺");
        LATEX_SYMBOLS.put("impliedby", "⟸");
        LATEX_SYMBOLS.put("hookrightarrow", "↪");
        LATEX_SYMBOLS.put("hookleftarrow", "↩");
        LATEX_SYMBOLS.put("twoheadrightarrow", "↠");
        LATEX_SYMBOLS.put("twoheadleftarrow", "↞");
        LATEX_SYMBOLS.put("nearrow", "↗");
        LATEX_SYMBOLS.put("searrow", "↘");
        LATEX_SYMBOLS.put("swarrow", "↙");
        LATEX_SYMBOLS.put("nwarrow", "↖");
        LATEX_SYMBOLS.put("rightleftharpoons", "⇌");
        LATEX_SYMBOLS.put("leftrightharpoons", "⇋");
        LATEX_SYMBOLS.put("rightharpoonup", "⇀");
        LATEX_SYMBOLS.put("rightharpoondown", "⇁");
        LATEX_SYMBOLS.put("leadsto", "↝");
        LATEX_SYMBOLS.put("rightsquigarrow", "↝");
        LATEX_SYMBOLS.put("dashrightarrow", "⇢");
        LATEX_SYMBOLS.put("dashleftarrow", "⇠");
        LATEX_SYMBOLS.put("circlearrowleft", "↺");
        LATEX_SYMBOLS.put("circlearrowright", "↻");
        LATEX_SYMBOLS.put("leqslant", "⩽");
        LATEX_SYMBOLS.put("geqslant", "⩾");
        LATEX_SYMBOLS.put("nleq", "≰");
        LATEX_SYMBOLS.put("ngeq", "≱");
        LATEX_SYMBOLS.put("nless", "≮");
        LATEX_SYMBOLS.put("ngtr", "≯");
        LATEX_SYMBOLS.put("lesssim", "≲");
        LATEX_SYMBOLS.put("gtrsim", "≳");
        LATEX_SYMBOLS.put("lessgtr", "≶");
        LATEX_SYMBOLS.put("greaterless", "≷");
        LATEX_SYMBOLS.put("doteq", "≐");
        LATEX_SYMBOLS.put("asymp", "≍");
        LATEX_SYMBOLS.put("bowtie", "⋈");
        LATEX_SYMBOLS.put("models", "⊨");
        LATEX_SYMBOLS.put("vdash", "⊢");
        LATEX_SYMBOLS.put("dashv", "⊣");
        LATEX_SYMBOLS.put("top", "⊤");
        LATEX_SYMBOLS.put("bot", "⊥");
        LATEX_SYMBOLS.put("triangleq", "≜");
        LATEX_SYMBOLS.put("eqqcolon", "≕");
        LATEX_SYMBOLS.put("coloneqq", "≔");
        LATEX_SYMBOLS.put("coloneq", "≔");
        LATEX_SYMBOLS.put("prec", "≺");
        LATEX_SYMBOLS.put("succ", "≻");
        LATEX_SYMBOLS.put("preceq", "⪯");
        LATEX_SYMBOLS.put("succeq", "⪰");
        LATEX_SYMBOLS.put("subsetneq", "⊊");
        LATEX_SYMBOLS.put("supsetneq", "⊋");
        LATEX_SYMBOLS.put("sqsubset", "⊏");
        LATEX_SYMBOLS.put("sqsupset", "⊐");
        LATEX_SYMBOLS.put("sqsubseteq", "⊑");
        LATEX_SYMBOLS.put("sqsupseteq", "⊒");
        LATEX_SYMBOLS.put("nsubseteq", "⊈");
        LATEX_SYMBOLS.put("nsupseteq", "⊉");
        LATEX_SYMBOLS.put("nparallel", "∦");
        LATEX_SYMBOLS.put("nmid", "∤");
        LATEX_SYMBOLS.put("varpropto", "∝");
        LATEX_SYMBOLS.put("ominus", "⊖");
        LATEX_SYMBOLS.put("oslash", "⊘");
        LATEX_SYMBOLS.put("uplus", "⊎");
        LATEX_SYMBOLS.put("sqcup", "⊔");
        LATEX_SYMBOLS.put("sqcap", "⊓");
        LATEX_SYMBOLS.put("amalg", "⨿");
        LATEX_SYMBOLS.put("wr", "≀");
        LATEX_SYMBOLS.put("diamond", "⋄");
        LATEX_SYMBOLS.put("bigtriangleup", "△");
        LATEX_SYMBOLS.put("bigtriangledown", "▽");
        LATEX_SYMBOLS.put("triangleleft", "◁");
        LATEX_SYMBOLS.put("triangleright", "▷");
        LATEX_SYMBOLS.put("bigoplus", "⨁");
        LATEX_SYMBOLS.put("bigotimes", "⨂");
        LATEX_SYMBOLS.put("bigodot", "⨀");
        LATEX_SYMBOLS.put("bigsqcup", "⨆");
        LATEX_SYMBOLS.put("bigvee", "⋁");
        LATEX_SYMBOLS.put("bigwedge", "⋀");
        LATEX_SYMBOLS.put("biguplus", "⨄");
        LATEX_SYMBOLS.put("iiiint", "⨌");
        LATEX_SYMBOLS.put("oiint", "∯");
        LATEX_SYMBOLS.put("Re", "ℜ");
        LATEX_SYMBOLS.put("Im", "ℑ");
        LATEX_SYMBOLS.put("beth", "ℶ");
        LATEX_SYMBOLS.put("gimel", "ℷ");
        LATEX_SYMBOLS.put("daleth", "ℸ");
        LATEX_SYMBOLS.put("complement", "∁");
        LATEX_SYMBOLS.put("surd", "√");
        LATEX_SYMBOLS.put("measuredangle", "∡");
        LATEX_SYMBOLS.put("sphericalangle", "∢");
        LATEX_SYMBOLS.put("lozenge", "◊");
        LATEX_SYMBOLS.put("blacklozenge", "◆");
        LATEX_SYMBOLS.put("bigstar", "★");
        LATEX_SYMBOLS.put("backsimeq", "⋍");
        LATEX_SYMBOLS.put("clubsuit", "♣");
        LATEX_SYMBOLS.put("diamondsuit", "♢");
        LATEX_SYMBOLS.put("heartsuit", "♡");
        LATEX_SYMBOLS.put("spadesuit", "♠");
        LATEX_SYMBOLS.put("flat", "♭");
        LATEX_SYMBOLS.put("natural", "♮");
        LATEX_SYMBOLS.put("sharp", "♯");
        LATEX_SYMBOLS.put("backslash", "\\");
        LATEX_SYMBOLS.put("vert", "|");
        LATEX_SYMBOLS.put("Vert", "‖");
        LATEX_SYMBOLS.put("lvert", "|");
        LATEX_SYMBOLS.put("rvert", "|");
        LATEX_SYMBOLS.put("lVert", "‖");
        LATEX_SYMBOLS.put("rVert", "‖");
        LATEX_SYMBOLS.put("ulcorner", "⌜");
        LATEX_SYMBOLS.put("urcorner", "⌝");
        LATEX_SYMBOLS.put("llcorner", "⌞");
        LATEX_SYMBOLS.put("lrcorner", "⌟");
        LATEX_SYMBOLS.put("pounds", "£");
        LATEX_SYMBOLS.put("euro", "€");
        LATEX_SYMBOLS.put("yen", "¥");
        LATEX_SYMBOLS.put("copyright", "©");
        LATEX_SYMBOLS.put("degree", "°");

        // 双线体补全（含数字 𝟘..𝟡）
        DOUBLE_STRUCK.put('A', "𝔸");
        DOUBLE_STRUCK.put('J', "𝕁");
        DOUBLE_STRUCK.put('O', "𝕆");
        DOUBLE_STRUCK.put('S', "𝕊");
        DOUBLE_STRUCK.put('0', "𝟘");
        DOUBLE_STRUCK.put('1', "𝟙");
        DOUBLE_STRUCK.put('2', "𝟚");
        DOUBLE_STRUCK.put('3', "𝟛");
        DOUBLE_STRUCK.put('4', "𝟜");
        DOUBLE_STRUCK.put('5', "𝟝");
        DOUBLE_STRUCK.put('6', "𝟞");
        DOUBLE_STRUCK.put('7', "𝟟");
        DOUBLE_STRUCK.put('8', "𝟠");
        DOUBLE_STRUCK.put('9', "𝟡");

        // 花体
        MATHCAL.put('A', "𝒜");
        MATHCAL.put('B', "ℬ");
        MATHCAL.put('C', "𝒞");
        MATHCAL.put('D', "𝒟");
        MATHCAL.put('E', "ℰ");
        MATHCAL.put('F', "ℱ");
        MATHCAL.put('G', "𝒢");
        MATHCAL.put('H', "ℋ");
        MATHCAL.put('I', "ℐ");
        MATHCAL.put('J', "𝒥");
        MATHCAL.put('K', "𝒦");
        MATHCAL.put('L', "ℒ");
        MATHCAL.put('M', "ℳ");
        MATHCAL.put('N', "𝒩");
        MATHCAL.put('O', "𝒪");
        MATHCAL.put('P', "𝒫");
        MATHCAL.put('Q', "𝒬");
        MATHCAL.put('R', "ℛ");
        MATHCAL.put('S', "𝒮");
        MATHCAL.put('T', "𝒯");
        MATHCAL.put('U', "𝒰");
        MATHCAL.put('V', "𝒱");
        MATHCAL.put('W', "𝒲");
        MATHCAL.put('X', "𝒳");
        MATHCAL.put('Y', "𝒴");
        MATHCAL.put('Z', "𝒵");

        // 直立显示的函数名
        String[] functions = {"sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos",
                "arctan", "arccot", "sinh", "cosh", "tanh", "coth", "sech", "csch",
                "log", "ln", "lg", "exp", "lim", "limsup", "liminf", "max", "min",
                "sup", "inf", "det", "dim", "ker", "deg", "gcd", "hom", "arg", "Pr"};
        for (int i = 0; i < functions.length; i++) {
            LATEX_FUNCTIONS.add(functions[i]);
        }

        // 只影响排版、不需要输出内容的命令
        String[] ignored = {"displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle",
                "limits", "nolimits", "nonumber", "notag", "hline", "allowbreak",
                "linebreak", "pagebreak", "thinspace", "medspace", "thickspace",
                "enspace", "negthinspace", "tiny", "scriptsize", "footnotesize",
                "small", "normalsize", "large", "Large", "LARGE", "huge", "Huge",
                "left.", "right."};
        for (int i = 0; i < ignored.length; i++) {
            IGNORED_COMMANDS.add(ignored[i]);
        }

        // 数学环境
        String[] environments = {"matrix", "pmatrix", "bmatrix", "Bmatrix", "vmatrix",
                "Vmatrix", "smallmatrix", "array", "subarray", "cases", "dcases", "rcases",
                "aligned", "align", "align*", "alignedat", "gather", "gathered", "gather*",
                "equation", "equation*", "split", "multline", "multline*", "eqnarray",
                "eqnarray*"};
        for (int i = 0; i < environments.length; i++) {
            MATH_ENVIRONMENTS.add(environments[i]);
        }
    }

    private static void appendDisplayMath(SpannableStringBuilder out, String latex, Colors colors) {
        if (latex == null) {
            return;
        }
        String marked = latexToText(latex);
        if (visibleText(marked).trim().length() == 0) {
            return;
        }
        if (out.length() > 0) {
            out.append('\n');
        }
        int start = out.length();
        out.append("  ");
        if (!appendMathMarked(out, marked, colors, true)) {
            out.delete(start, out.length());
            return;
        }
        // 块级公式居中显示，更接近论文 / 教材排版
        out.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static boolean appendMath(SpannableStringBuilder out, String latex, Colors colors,
                                      boolean display) {
        if (latex == null) {
            return false;
        }
        String marked = latexToText(latex);
        if (visibleText(marked).trim().length() == 0) {
            return false;
        }
        return appendMathMarked(out, marked, colors, display);
    }

    /** 把带标记的数学文本写入 Spannable，并套上衬线、斜体、上下标等 Span。 */
    private static boolean appendMathMarked(SpannableStringBuilder out, String marked, Colors colors,
                                            boolean display) {
        int start = 0;
        int end = marked.length();
        while (start < end && isMathSpace(marked.charAt(start))) {
            start++;
        }
        while (end > start && isMathSpace(marked.charAt(end - 1))) {
            end--;
        }
        if (start >= end) {
            return false;
        }

        int baseStart = out.length();
        List<int[]> sups = new ArrayList<int[]>();
        List<int[]> subs = new ArrayList<int[]>();
        List<int[]> ups = new ArrayList<int[]>();
        List<int[]> bolds = new ArrayList<int[]>();
        List<int[]> uls = new ArrayList<int[]>();
        List<Integer> supStack = new ArrayList<Integer>();
        List<Integer> subStack = new ArrayList<Integer>();
        List<Integer> upStack = new ArrayList<Integer>();
        List<Integer> boldStack = new ArrayList<Integer>();
        List<Integer> ulStack = new ArrayList<Integer>();

        int visible = 0;
        for (int i = start; i < end; i++) {
            char c = marked.charAt(i);
            if (c == M_SUP) {
                supStack.add(out.length());
            } else if (c == M_SUP_END) {
                closeRange(supStack, sups, out.length());
            } else if (c == M_SUB) {
                subStack.add(out.length());
            } else if (c == M_SUB_END) {
                closeRange(subStack, subs, out.length());
            } else if (c == M_UP) {
                upStack.add(out.length());
            } else if (c == M_UP_END) {
                closeRange(upStack, ups, out.length());
            } else if (c == M_BOLD) {
                boldStack.add(out.length());
            } else if (c == M_BOLD_END) {
                closeRange(boldStack, bolds, out.length());
            } else if (c == M_UL) {
                ulStack.add(out.length());
            } else if (c == M_UL_END) {
                closeRange(ulStack, uls, out.length());
            } else {
                if (!Character.isWhitespace(c)) {
                    visible++;
                }
                out.append(c);
            }
        }
        while (!supStack.isEmpty()) {
            closeRange(supStack, sups, out.length());
        }
        while (!subStack.isEmpty()) {
            closeRange(subStack, subs, out.length());
        }
        while (!upStack.isEmpty()) {
            closeRange(upStack, ups, out.length());
        }
        while (!boldStack.isEmpty()) {
            closeRange(boldStack, bolds, out.length());
        }
        while (!ulStack.isEmpty()) {
            closeRange(ulStack, uls, out.length());
        }

        if (visible == 0) {
            out.delete(baseStart, out.length());
            return false;
        }

        int baseEnd = out.length();
        out.setSpan(new TypefaceSpan("serif"), baseStart, baseEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(colors.math), baseStart, baseEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (display) {
            out.setSpan(new RelativeSizeSpan(1.06f), baseStart, baseEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        applyItalic(out, baseStart, baseEnd, ups);
        applyScriptSpans(out, sups, true);
        applyScriptSpans(out, subs, false);
        for (int i = 0; i < bolds.size(); i++) {
            int[] r = bolds.get(i);
            if (r[1] > r[0]) {
                out.setSpan(new StyleSpan(Typeface.BOLD), r[0], r[1],
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        for (int i = 0; i < uls.size(); i++) {
            int[] r = uls.get(i);
            if (r[1] > r[0]) {
                out.setSpan(new UnderlineSpan(), r[0], r[1],
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return true;
    }

    private static void closeRange(List<Integer> stack, List<int[]> ranges, int end) {
        if (stack.isEmpty()) {
            return;
        }
        int start = stack.remove(stack.size() - 1);
        if (end > start) {
            ranges.add(new int[]{start, end});
        }
    }

    /** 除直立区段外整体斜体，函数名、\text{} 保持正体。 */
    private static void applyItalic(SpannableStringBuilder out, int start, int end,
                                    List<int[]> upright) {
        if (upright.isEmpty()) {
            italic(out, start, end);
            return;
        }
        for (int i = 1; i < upright.size(); i++) {
            int[] key = upright.get(i);
            int j = i - 1;
            while (j >= 0 && upright.get(j)[0] > key[0]) {
                upright.set(j + 1, upright.get(j));
                j--;
            }
            upright.set(j + 1, key);
        }
        int cursor = start;
        for (int i = 0; i < upright.size(); i++) {
            int s = Math.max(upright.get(i)[0], start);
            int e = Math.min(upright.get(i)[1], end);
            if (s > cursor) {
                italic(out, cursor, s);
            }
            if (e > cursor) {
                cursor = e;
            }
        }
        if (cursor < end) {
            italic(out, cursor, end);
        }
    }

    private static void italic(SpannableStringBuilder out, int start, int end) {
        if (end > start) {
            out.setSpan(new StyleSpan(Typeface.ITALIC), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static void applyScriptSpans(SpannableStringBuilder out, List<int[]> ranges,
                                         boolean superscript) {
        for (int i = 0; i < ranges.size(); i++) {
            int[] r = ranges.get(i);
            if (r[1] <= r[0]) {
                continue;
            }
            if (superscript) {
                out.setSpan(new SuperscriptSpan(), r[0], r[1],
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                out.setSpan(new SubscriptSpan(), r[0], r[1],
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            out.setSpan(new RelativeSizeSpan(0.72f), r[0], r[1],
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** 去掉控制标记后的可见文本，用于判断公式是否为空。 */
    private static String visibleText(String marked) {
        StringBuilder sb = new StringBuilder(marked.length());
        for (int i = 0; i < marked.length(); i++) {
            char c = marked.charAt(i);
            if (!isMathMarker(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isMathMarker(char c) {
        return (c >= M_SUP && c <= M_BOLD_END) || c == M_UL || c == M_UL_END;
    }

    private static boolean isMathSpace(char c) {
        return c == ' ' || c == '\n' || c == '\t' || c == '\r';
    }

    private static String plainMath(String marked) {
        return visibleText(marked);
    }

    /** 判断 $...$ 内容是否像公式，避免把货币金额（如 $5.00）误渲染。 */
    private static boolean looksLikeLatex(String content) {
        if (content.length() == 0) {
            return false;
        }
        if (Character.isWhitespace(content.charAt(0))
                || Character.isWhitespace(content.charAt(content.length() - 1))) {
            return false;
        }
        boolean hasLetter = false;
        boolean hasMarkup = false;
        boolean hasOperator = false;
        boolean hasGreek = false;
        boolean hasCjk = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (c >= 0x0370 && c <= 0x03FF) {
                    hasGreek = true;
                }
                if (c >= 0x4E00 && c <= 0x9FFF) {
                    hasCjk = true;
                }
            } else if (c == '\\' || c == '^' || c == '_' || c == '{' || c == '}') {
                hasMarkup = true;
            } else if (c == '+' || c == '-' || c == '*' || c == '/' || c == '='
                    || c == '<' || c == '>') {
                hasOperator = true;
            }
        }
        if (hasMarkup) {
            return true;
        }
        // $…$ 里夹着中文时按普通文本处理，避免把「$100$ 和 $x$」中间的中文当成公式
        if (hasCjk) {
            return false;
        }
        if (!hasLetter) {
            return false;
        }
        if (hasOperator || hasGreek) {
            return true;
        }
        return content.indexOf(' ') < 0 && content.length() <= 24
                || content.length() <= 10;
    }

    private static String latexToText(String latex) {
        StringBuilder out = new StringBuilder();
        List<String> envs = new ArrayList<String>();
        int[] pos = {0};
        convertLatex(latex, out, pos, envs);
        return tidyMath(out.toString());
    }

    /** 压缩多余空格，并去掉括号内侧的空格。 */
    private static String tidyMath(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean pendingSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') {
                pendingSpace = true;
                continue;
            }
            if (pendingSpace) {
                int last = sb.length() - 1;
                char prev = last >= 0 ? sb.charAt(last) : '\0';
                boolean afterOpen = prev == '(' || prev == '[' || prev == '{';
                boolean beforeClose = c == ')' || c == ']' || c == '}';
                if (last >= 0 && !afterOpen && !beforeClose && prev != ' ') {
                    sb.append(' ');
                }
                pendingSpace = false;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static void convertLatex(String s, StringBuilder out, int[] pos, List<String> envs) {
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (c == '%') {
                // LaTeX 注释，忽略到行尾
                while (pos[0] < s.length() && s.charAt(pos[0]) != '\n') {
                    pos[0]++;
                }
                continue;
            }
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
                        // \\[2pt] / \\[1em] 这类行距参数要跳过，否则会把 [2pt] 当正文
                        if (pos[0] < s.length() && s.charAt(pos[0]) == '[') {
                            int close = s.indexOf(']', pos[0]);
                            if (close > pos[0] && isLineBreakSpacing(
                                    s.substring(pos[0] + 1, close))) {
                                pos[0] = close + 1;
                            }
                        }
                    } else if (next == ' ' || next == ';' || next == ':' || next == '\n') {
                        out.append(' ');
                    } else if (next == ',' || next == '!') {
                        // 细间距，忽略
                    } else if (next == '|') {
                        out.append('‖');
                    } else if (next == '{' || next == '}' || next == '%' || next == '&'
                            || next == '#' || next == '_' || next == '$') {
                        out.append(next);
                    } else {
                        out.append(next);
                    }
                    continue;
                }
                int start = pos[0];
                while (pos[0] < s.length() && Character.isLetter(s.charAt(pos[0]))) {
                    pos[0]++;
                }
                String name = s.substring(start, pos[0]);
                // \operatorname*{...}、\hspace*{...} 这类带星号的写法
                if (pos[0] < s.length() && s.charAt(pos[0]) == '*'
                        && (name.equals("operatorname") || name.equals("hspace")
                        || name.equals("vspace"))) {
                    pos[0]++;
                }
                handleCommand(name, s, out, pos, envs);
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
            if (c == '\'') {
                out.append('′');
                pos[0]++;
                continue;
            }
            if (c == '~') {
                out.append(' ');
                pos[0]++;
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
        if (name.equals("binom") || name.equals("dbinom") || name.equals("tbinom")) {
            String n = latexToText(readArgument(s, pos));
            String k = latexToText(readArgument(s, pos));
            out.append("C(").append(plainMath(n)).append(", ").append(plainMath(k)).append(')');
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
            if (plainMath(value).length() == 1) {
                out.append("√").append(value);
            } else {
                out.append("√(").append(value).append(')');
            }
            return;
        }
        if (name.equals("text") || name.equals("textrm") || name.equals("textnormal")
                || name.equals("mbox") || name.equals("mathrm") || name.equals("mathup")
                || name.equals("mathsf") || name.equals("mathtt") || name.equals("mathop")
                || name.equals("operatorname")) {
            out.append(upright(latexToText(readArgument(s, pos))));
            return;
        }
        if (name.equals("textbf")) {
            out.append(bold(upright(latexToText(readArgument(s, pos)))));
            return;
        }
        if (name.equals("mathbf")) {
            out.append(bold(upright(latexToText(readArgument(s, pos)))));
            return;
        }
        if (name.equals("boldsymbol") || name.equals("bm") || name.equals("pmb")) {
            out.append(bold(latexToText(readArgument(s, pos))));
            return;
        }
        if (name.equals("textit") || name.equals("emph") || name.equals("mathit")) {
            out.append(latexToText(readArgument(s, pos)));
            return;
        }
        if (name.equals("mathbb")) {
            out.append(doubleStruck(plainMath(latexToText(readArgument(s, pos)))));
            return;
        }
        if (name.equals("mathcal") || name.equals("mathscr")) {
            out.append(scriptLetters(plainMath(latexToText(readArgument(s, pos)))));
            return;
        }
        if (name.equals("textcolor")) {
            readArgument(s, pos);
            out.append(upright(latexToText(readArgument(s, pos))));
            return;
        }
        if (name.equals("overline") || name.equals("bar")) {
            out.append(latexToText(readArgument(s, pos))).append('\u0304');
            return;
        }
        if (name.equals("underline")) {
            out.append(underlined(latexToText(readArgument(s, pos))));
            return;
        }
        if (name.equals("hat") || name.equals("widehat")) {
            out.append(latexToText(readArgument(s, pos))).append('\u0302');
            return;
        }
        if (name.equals("vec") || name.equals("overrightarrow")) {
            out.append(latexToText(readArgument(s, pos))).append('\u20D7');
            return;
        }
        if (name.equals("overleftarrow")) {
            out.append(latexToText(readArgument(s, pos))).append('\u20D6');
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
        if (name.equals("tilde") || name.equals("widetilde")) {
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
        if (name.equals("breve")) {
            out.append(latexToText(readArgument(s, pos))).append('\u0306');
            return;
        }
        if (name.equals("check")) {
            out.append(latexToText(readArgument(s, pos))).append('\u030C');
            return;
        }
        if (name.equals("overbrace") || name.equals("underbrace") || name.equals("boxed")
                || name.equals("fbox") || name.equals("substack")) {
            out.append(latexToText(readArgument(s, pos)));
            return;
        }
        if (name.equals("overset") || name.equals("stackrel")) {
            String top = latexToText(readArgument(s, pos));
            String base = latexToText(readArgument(s, pos));
            out.append(base).append(script('^', top));
            return;
        }
        if (name.equals("underset")) {
            String bottom = latexToText(readArgument(s, pos));
            String base = latexToText(readArgument(s, pos));
            out.append(base).append(script('_', bottom));
            return;
        }
        if (name.equals("xrightarrow") || name.equals("xleftarrow")
                || name.equals("xRightarrow") || name.equals("xLeftarrow")) {
            String label = latexToText(readArgument(s, pos));
            boolean left = name.equals("xleftarrow") || name.equals("xLeftarrow");
            out.append(left ? "←" : "→");
            if (plainMath(label).length() > 0) {
                out.append(script('^', label));
            }
            return;
        }
        if (name.equals("left") || name.equals("right") || name.equals("middle")) {
            if (pos[0] < s.length() && s.charAt(pos[0]) == '.') {
                pos[0]++;
            }
            return;
        }
        // 只放大括号的命令，注意不能误伤 bigcup / bigoplus 等符号
        if (name.equals("big") || name.equals("Big") || name.equals("bigg")
                || name.equals("Bigg") || name.equals("bigl") || name.equals("Bigl")
                || name.equals("biggl") || name.equals("Biggl") || name.equals("bigr")
                || name.equals("Bigr") || name.equals("biggr") || name.equals("Biggr")
                || name.equals("bigm") || name.equals("Bigm")) {
            return;
        }
        if (name.equals("begin") || name.equals("end")) {
            String env = readArgument(s, pos).trim();
            if (name.equals("begin")) {
                if (env.equals("array") || env.equals("tabular") || env.equals("alignat")
                        || env.equals("alignedat") || env.equals("subarray")) {
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
        if (name.equals("hspace") || name.equals("vspace") || name.equals("phantom")
                || name.equals("hphantom") || name.equals("vphantom")) {
            readArgument(s, pos);
            return;
        }
        if (name.equals("color") || name.equals("tag") || name.equals("label")
                || name.equals("ref") || name.equals("eqref") || name.equals("cline")) {
            readArgument(s, pos);
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
        if (name.equals("not")) {
            // \not= \not< \not> \not\in 之类的组合
            if (pos[0] < s.length()) {
                char next = s.charAt(pos[0]);
                if (next == '=') {
                    out.append('≠');
                    pos[0]++;
                    return;
                }
                if (next == '<') {
                    out.append('≮');
                    pos[0]++;
                    return;
                }
                if (next == '>') {
                    out.append('≯');
                    pos[0]++;
                    return;
                }
                if (next == '\\' && pos[0] + 1 < s.length()) {
                    char cmd = s.charAt(pos[0] + 1);
                    if (cmd == 'i') {
                        out.append('∉');
                        pos[0] += "\\in".length();
                        return;
                    }
                }
            }
            return;
        }
        if (IGNORED_COMMANDS.contains(name)) {
            return;
        }
        if (LATEX_FUNCTIONS.contains(name)) {
            out.append(upright(name));
            return;
        }
        String symbol = LATEX_SYMBOLS.get(name);
        if (symbol != null) {
            out.append(symbol);
        } else {
            out.append(name);
        }
    }

    private static String upright(String value) {
        return value.length() == 0 ? "" : String.valueOf(M_UP) + value + M_UP_END;
    }

    private static String bold(String value) {
        return value.length() == 0 ? "" : String.valueOf(M_BOLD) + value + M_BOLD_END;
    }

    private static String underlined(String value) {
        return value.length() == 0 ? "" : String.valueOf(M_UL) + value + M_UL_END;
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
        if (marker == '^' && (body.equals("∘") || body.equals("°"))) {
            return "°";
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
        // Unicode 上下标覆盖不到的字符（如 π、∞、中文、嵌套分数）
        // 改用真正的上下标 Span，比 "^(...)" 好看也更接近 LaTeX 排版。
        if (marker == '^') {
            return M_SUP + body + M_SUP_END;
        }
        return M_SUB + body + M_SUB_END;
    }

    private static String formatFraction(String numerator, String denominator) {
        String top = fractionPart(numerator);
        String bottom = fractionPart(denominator);
        if (top.length() == 0 || bottom.length() == 0) {
            return top + bottom;
        }
        return top + "/" + bottom;
    }

    private static String fractionPart(String value) {
        String bare = plainMath(value);
        if (bare.length() == 0) {
            return "";
        }
        boolean simple = bare.length() <= 3;
        for (int i = 0; i < bare.length(); i++) {
            char c = bare.charAt(i);
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

    private static String scriptLetters(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            String mapped = MATHCAL.get(value.charAt(i));
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
        if (env.equals("cases") || env.equals("dcases")) {
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
        if (env.equals("rcases")) {
            return "}";
        }
        return "";
    }

    private static boolean isMatrixEnv(List<String> envs) {
        if (envs.isEmpty()) {
            return false;
        }
        String env = envs.get(envs.size() - 1);
        return env.indexOf("matrix") >= 0 || env.equals("array")
                || env.equals("subarray");
    }

    /** 从 \begin{env} 这一行里取出环境名。 */
    private static String environmentName(String line) {
        int start = line.indexOf('{');
        if (start < 0) {
            return "";
        }
        int end = line.indexOf('}', start + 1);
        if (end < 0) {
            return "";
        }
        return line.substring(start + 1, end).trim();
    }

    private static boolean isMathEnvironment(String env) {
        return MATH_ENVIRONMENTS.contains(env);
    }
}
