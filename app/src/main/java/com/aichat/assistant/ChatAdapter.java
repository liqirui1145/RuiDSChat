package com.aichat.assistant;

import android.content.Context;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ChatAdapter extends BaseAdapter {

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy年M月d日", Locale.CHINA);
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm", Locale.CHINA);

    private final Context context;
    private final List<ChatMessage> messages;
    private final LayoutInflater inflater;
    /** 缓存已渲染的富文本，滚动时不必重复解析整段 Markdown。 */
    private final Map<ChatMessage, Rendered> renderCache = new HashMap<ChatMessage, Rendered>();

    private static final class Rendered {
        String source;
        CharSequence text;
    }

    /** 长按消息的操作回调。 */
    public interface OnMessageActionListener {
        void onMessageLongClick(ChatMessage message);

        /** 点击「继续生成」时调用。 */
        void onContinueGeneration(ChatMessage message);

        /** 点击失败消息里的「重试」时调用。 */
        void onRetryMessage(ChatMessage message);
    }

    private OnMessageActionListener actionListener;

    /** 搜索定位后临时高亮的消息行号。 */
    private long highlightDbId;

    public void setHighlightDbId(long highlightDbId) {
        this.highlightDbId = highlightDbId;
    }

    public void setMessageActionListener(OnMessageActionListener listener) {
        this.actionListener = listener;
    }

    public ChatAdapter(Context context, List<ChatMessage> messages) {
        this.context = context;
        this.messages = messages;
        this.inflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
        return messages.size();
    }

    @Override
    public Object getItem(int position) {
        return messages.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.item_message, parent, false);
        }
        final ChatMessage msg = messages.get(position);

        TextView dateView = (TextView) view.findViewById(R.id.message_date);
        LinearLayout row = (LinearLayout) view.findViewById(R.id.message_row);
        LinearLayout bubble = (LinearLayout) view.findViewById(R.id.message_bubble);
        TextView reasoningToggle = (TextView) view.findViewById(R.id.reasoning_toggle);
        TextView reasoningText = (TextView) view.findViewById(R.id.reasoning_text);
        TextView bubbleText = (TextView) view.findViewById(R.id.message_text);
        TextView timeView = (TextView) view.findViewById(R.id.message_time);

        // 跨天时插入居中的日期分隔
        if (position == 0 || !isSameDay(messages.get(position - 1).getTimestamp(),
                msg.getTimestamp())) {
            dateView.setVisibility(View.VISIBLE);
            dateView.setText(formatDate(msg.getTimestamp()));
        } else {
            dateView.setVisibility(View.GONE);
        }

        switch (msg.getRole()) {
            case ChatMessage.ROLE_USER:
                row.setGravity(Gravity.END);
                bubble.setBackgroundResource(R.drawable.bg_user_bubble);
                bubbleText.setTextColor(context.getResources().getColor(R.color.white));
                break;
            case ChatMessage.ROLE_ERROR:
                row.setGravity(Gravity.START);
                bubble.setBackgroundResource(R.drawable.bg_error_bubble);
                bubbleText.setTextColor(context.getResources().getColor(R.color.danger));
                break;
            default:
                row.setGravity(Gravity.START);
                bubble.setBackgroundResource(R.drawable.bg_ai_bubble);
                bubbleText.setTextColor(context.getResources().getColor(R.color.text_primary));
                break;
        }
        timeView.setText(TIME_FORMAT.format(new Date(msg.getTimestamp())));
        LinearLayout.LayoutParams timeParams =
                (LinearLayout.LayoutParams) timeView.getLayoutParams();
        timeParams.gravity = msg.getRole() == ChatMessage.ROLE_USER
                ? Gravity.END : Gravity.START;
        timeView.setLayoutParams(timeParams);

        // 深度思考过程：默认折叠，点击标题展开
        String reasoning = msg.getReasoning();
        boolean hasReasoning = msg.getRole() == ChatMessage.ROLE_ASSISTANT
                && reasoning.trim().length() > 0;
        if (hasReasoning) {
            reasoningToggle.setVisibility(View.VISIBLE);
            reasoningToggle.setText(msg.isReasoningExpanded()
                    ? "▾ 收起思考过程" : "▸ 思考过程");
            if (msg.isReasoningExpanded()) {
                reasoningText.setVisibility(View.VISIBLE);
                reasoningText.setText(reasoning.trim());
            } else {
                reasoningText.setVisibility(View.GONE);
                reasoningText.setText("");
            }
        } else {
            reasoningToggle.setVisibility(View.GONE);
            reasoningText.setVisibility(View.GONE);
            reasoningText.setText("");
        }

        String content = msg.getText();
        if (msg.getAttachmentName() != null) {
            if (content.length() > 0) {
                content = content + "\n\n附件：" + msg.getAttachmentName();
            } else {
                content = "附件：" + msg.getAttachmentName();
            }
        }

        if (msg.getRole() == ChatMessage.ROLE_ASSISTANT) {
            bubbleText.setMovementMethod(LinkMovementMethod.getInstance());
            Rendered cached = renderCache.get(msg);
            if (cached == null || !content.equals(cached.source)) {
                if (renderCache.size() > 400) {
                    renderCache.clear();
                }
                cached = new Rendered();
                cached.source = content;
                cached.text = MarkdownRenderer.render(context, content);
                renderCache.put(msg, cached);
            }
            bubbleText.setText(appendContinueButton(msg, cached.text));
        } else if (msg.getRole() == ChatMessage.ROLE_ERROR) {
            bubbleText.setMovementMethod(LinkMovementMethod.getInstance());
            bubbleText.setText(buildErrorText(msg));
        } else {
            bubbleText.setMovementMethod(null);
            bubbleText.setText(content);
        }

        // 搜索命中跳转后短暂高亮整行
        if (highlightDbId > 0 && msg.getDbId() == highlightDbId) {
            view.setBackgroundColor(context.getResources().getColor(R.color.row_highlight));
        } else {
            view.setBackgroundColor(Color.TRANSPARENT);
        }

        final View.OnLongClickListener longClick = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (actionListener == null) {
                    return false;
                }
                actionListener.onMessageLongClick(msg);
                return true;
            }
        };
        view.setOnLongClickListener(longClick);
        bubble.setOnLongClickListener(longClick);
        bubbleText.setOnLongClickListener(longClick);

        reasoningToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                msg.setReasoningExpanded(!msg.isReasoningExpanded());
                notifyDataSetChanged();
            }
        });
        return view;
    }

    /** 失败气泡：内联「重试」与「查看详情」入口。 */
    private CharSequence buildErrorText(final ChatMessage msg) {
        SpannableStringBuilder builder = new SpannableStringBuilder(msg.getText());

        builder.append("\n\n↻ 点此重试");
        int retryStart = builder.length() - "↻ 点此重试".length();
        builder.setSpan(new ForegroundColorSpan(
                        context.getResources().getColor(R.color.md_link)),
                retryStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                if (actionListener != null) {
                    actionListener.onRetryMessage(msg);
                }
            }
        }, retryStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        if (msg.hasErrorDetail()) {
            builder.append("　·　");
            int toggleStart = builder.length();
            builder.append(msg.isErrorDetailExpanded() ? "▾ 收起错误详情" : "▸ 查看错误详情");
            builder.setSpan(new ForegroundColorSpan(
                            context.getResources().getColor(R.color.text_secondary)),
                    toggleStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    msg.setErrorDetailExpanded(!msg.isErrorDetailExpanded());
                    notifyDataSetChanged();
                }
            }, toggleStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (msg.isErrorDetailExpanded()) {
                builder.append("\n\n").append(msg.getErrorDetail());
            }
        }
        return builder;
    }

    /** 被长度上限截断的回复末尾追加「继续生成」入口。 */
    private CharSequence appendContinueButton(final ChatMessage msg, CharSequence rendered) {
        if (!msg.isTruncated()) {
            return rendered;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(rendered);
        builder.append("\n\n▶ 继续生成");
        int start = builder.length() - "▶ 继续生成".length();
        int end = builder.length();
        builder.setSpan(new ForegroundColorSpan(
                        context.getResources().getColor(R.color.md_link)),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                if (actionListener != null) {
                    actionListener.onContinueGeneration(msg);
                }
            }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return builder;
    }

    private static boolean isSameDay(long first, long second) {
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        a.setTimeInMillis(first);
        b.setTimeInMillis(second);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static String formatDate(long timestamp) {
        long now = System.currentTimeMillis();
        if (isSameDay(now, timestamp)) {
            return "今天";
        }
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (isSameDay(yesterday.getTimeInMillis(), timestamp)) {
            return "昨天";
        }
        return DATE_FORMAT.format(new Date(timestamp));
    }
}
