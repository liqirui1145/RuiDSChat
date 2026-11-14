package com.aichat.assistant;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

public class ChatAdapter extends BaseAdapter {

    private final Context context;
    private final List<ChatMessage> messages;
    private final LayoutInflater inflater;

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
            bubble.setText(MarkdownRenderer.render(context, content));
        } else {
            bubble.setText(content);
        }
        return view;
    }
}
