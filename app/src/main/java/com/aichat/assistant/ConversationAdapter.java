package com.aichat.assistant;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 抽屉里的对话列表适配器。 */
public class ConversationAdapter extends BaseAdapter {

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

    private final Context context;
    private final List<Conversation> conversations;
    private final LayoutInflater inflater;

    private String currentId;

    public ConversationAdapter(Context context, List<Conversation> conversations) {
        this.context = context;
        this.conversations = conversations;
        this.inflater = LayoutInflater.from(context);
    }

    public void setCurrentId(String currentId) {
        this.currentId = currentId;
    }

    @Override
    public int getCount() {
        return conversations.size();
    }

    @Override
    public Object getItem(int position) {
        return conversations.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.item_conversation, parent, false);
        }
        LinearLayout root = (LinearLayout) view;
        TextView title = (TextView) view.findViewById(R.id.conversation_title);
        TextView meta = (TextView) view.findViewById(R.id.conversation_meta);

        Conversation conversation = conversations.get(position);
        title.setText(conversation.getDisplayTitle());

        String time = conversation.getUpdatedAt() > 0
                ? TIME_FORMAT.format(new Date(conversation.getUpdatedAt()))
                : "";
        int count = conversation.getMessageCount();
        String metaText = count + " 条消息";
        if (time.length() > 0 && count > 0) {
            metaText = metaText + " · " + time;
        } else if (time.length() > 0) {
            metaText = time;
        }
        meta.setText(metaText);

        boolean current = conversation.getId().equals(currentId);
        root.setBackgroundResource(current ? R.drawable.bg_chip : 0);
        return view;
    }
}
