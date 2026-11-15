package com.aichat.assistant;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 全局消息搜索的结果列表。 */
public class SearchResultAdapter extends BaseAdapter {

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

    private final Context context;
    private final List<ChatStore.MessageHit> hits;
    private final LayoutInflater inflater;

    public SearchResultAdapter(Context context, List<ChatStore.MessageHit> hits) {
        this.context = context;
        this.hits = hits;
        this.inflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
        return hits.size();
    }

    @Override
    public Object getItem(int position) {
        return hits.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.item_search_result, parent, false);
        }
        ChatStore.MessageHit hit = hits.get(position);
        TextView title = (TextView) view.findViewById(R.id.search_title);
        TextView snippet = (TextView) view.findViewById(R.id.search_snippet);
        TextView meta = (TextView) view.findViewById(R.id.search_meta);

        title.setText(hit.title);
        snippet.setText(hit.snippet);
        String time = hit.timestamp > 0 ? TIME_FORMAT.format(new Date(hit.timestamp)) : "";
        String role = hit.role == ChatMessage.ROLE_USER ? "我" : "AI";
        meta.setText(time.length() > 0 ? role + " · " + time : role);
        return view;
    }
}
