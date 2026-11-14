package com.aichat.assistant;

import android.content.Context;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChatAdapter extends BaseAdapter {

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
    }

    private OnMessageActionListener actionListener;

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
        LinearLayout row = (LinearLayout) view;
        TextView bubble = (TextView) view.findViewById(R.id.message_text);
        ChatMessage msg = messages.get(position);

        String content = msg.getText();
        if (msg.getAttachmentName() != null) {
            if (content.length() > 0) {
                content = content + "\n\n附件：" + msg.getAttachmentName();
            } else {
                content = "附件：" + msg.getAttachmentName();
            }
        }

        switch (msg.getRole()) {
            case ChatMessage.ROLE_USER:
                row.setGravity(Gravity.END);
                bubble.setBackgroundResource(R.drawable.bg_user_bubble);
                bubble.setTextColor(context.getResources().getColor(R.color.white));
                break;
            case ChatMessage.ROLE_ERROR:
                row.setGravity(Gravity.START);
                bubble.setBackgroundResource(R.drawable.bg_error_bubble);
                bubble.setTextColor(context.getResources().getColor(R.color.danger));
                break;
            default:
                row.setGravity(Gravity.START);
                bubble.setBackgroundResource(R.drawable.bg_ai_bubble);
                bubble.setTextColor(context.getResources().getColor(R.color.text_primary));
                break;
        }

        if (msg.getRole() == ChatMessage.ROLE_ASSISTANT) {
            bubble.setMovementMethod(LinkMovementMethod.getInstance());
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
            bubble.setText(cached.text);
        } else {
            bubble.setMovementMethod(null);
            bubble.setText(content);
        }

        View.OnLongClickListener longClick = new View.OnLongClickListener() {
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
        return view;
    }
}
