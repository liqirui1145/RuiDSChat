package com.aichat.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends BaseActivity {

    private static final int REQUEST_PICK_FILE = 1001;

    private ListView messageList;
    private ChatAdapter adapter;
    private EditText input;
    private TextView sendButton;
    private TextView attachButton;
    private LinearLayout statusRow;
    private TextView statusText;
    private LinearLayout attachmentRow;
    private ImageView attachmentThumb;
    private TextView attachmentBadge;
    private TextView attachmentName;
    private TextView attachmentRemove;

    private View drawerScrim;
    private LinearLayout drawerPanel;
    private ListView conversationList;
    private TextView newConversationButton;
    private LinearLayout drawerSettingsButton;
    private ConversationAdapter conversationAdapter;

    private final List<Conversation> conversations = new ArrayList<Conversation>();
    private Conversation current;
    private boolean drawerOpen;
    private ChatStore store;

    private final List<ChatMessage> messages = new ArrayList<ChatMessage>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Uri pendingUri;
    private String pendingName;
    private boolean pendingImage;
    private boolean sending;

    private static final long STREAM_REFRESH_MS = 80;

    private volatile ApiClient.StreamHandle streamHandle;
    private ChatMessage streamingMessage;
    private final StringBuilder streamBuffer = new StringBuilder();
    private volatile boolean streamRefreshScheduled;
    private String streamFinishReason;

    /** 流式增量节流刷新，避免每个 token 都重绘整条消息。 */
    private final Runnable streamRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            streamRefreshScheduled = false;
            ChatMessage target = streamingMessage;
            if (target == null) {
                return;
            }
            target.setText(snapshotStream());
            hideStatus();
            adapter.notifyDataSetChanged();
            scrollToBottom();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        messageList = (ListView) findViewById(R.id.message_list);
        input = (EditText) findViewById(R.id.input);
        sendButton = (TextView) findViewById(R.id.send_button);
        attachButton = (TextView) findViewById(R.id.attach_button);
        statusRow = (LinearLayout) findViewById(R.id.status_row);
        statusText = (TextView) findViewById(R.id.status_text);
        attachmentRow = (LinearLayout) findViewById(R.id.attachment_row);
        attachmentThumb = (ImageView) findViewById(R.id.attachment_thumb);
        attachmentBadge = (TextView) findViewById(R.id.attachment_badge);
        attachmentName = (TextView) findViewById(R.id.attachment_name);
        attachmentRemove = (TextView) findViewById(R.id.attachment_remove);

        drawerScrim = findViewById(R.id.drawer_scrim);
        drawerPanel = (LinearLayout) findViewById(R.id.drawer_panel);
        conversationList = (ListView) findViewById(R.id.conversation_list);
        newConversationButton = (TextView) findViewById(R.id.new_conversation_button);
        drawerSettingsButton = (LinearLayout) findViewById(R.id.drawer_settings_button);

        store = new ChatStore(this);
        loadConversations();

        adapter = new ChatAdapter(this, messages);
        messageList.setAdapter(adapter);
        View emptyView = findViewById(R.id.empty_state);
        messageList.setEmptyView(emptyView);
        messageList.setSelection(adapter.getCount());

        conversationAdapter = new ConversationAdapter(this, conversations);
        conversationAdapter.setCurrentId(current.getId());
        conversationList.setAdapter(conversationAdapter);

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sending) {
                    stopGeneration();
                } else {
                    sendMessage();
                }
            }
        });
        attachButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        });
        findViewById(R.id.drawer_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDrawer();
            }
        });
        drawerScrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDrawer();
            }
        });
        newConversationButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newConversation();
            }
        });
        drawerSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDrawer();
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        conversationList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                switchConversation(position);
            }
        });
        conversationList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                conversationMenu(position);
                return true;
            }
        });
        attachmentRemove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearAttachment();
            }
        });

        messageList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                copyMessage(position);
                return true;
            }
        });
    }

    /** 从本地数据库读取会话列表，并加载上次打开的会话消息。 */
    private void loadConversations() {
        conversations.clear();
        conversations.addAll(store.loadConversations());
        if (conversations.isEmpty()) {
            Conversation created = Conversation.create();
            store.saveConversation(created);
            conversations.add(created);
        }
        String savedId = Prefs.getCurrentConversationId(this);
        current = null;
        for (Conversation conversation : conversations) {
            if (conversation.getId().equals(savedId)) {
                current = conversation;
                break;
            }
        }
        if (current == null) {
            current = conversations.get(0);
        }
        if (!current.isMessagesLoaded()) {
            current.setMessages(store.loadMessages(current.getId()));
        }
        messages.clear();
        messages.addAll(current.getMessages());
        Prefs.setCurrentConversationId(this, current.getId());
    }

    @Override
    protected void onResume() {
        super.onResume();
        recreateIfThemeChanged();
        TextView emptySettingsButton = (TextView) findViewById(R.id.empty_settings_button);
        if (emptySettingsButton != null) {
            boolean hasKey = Prefs.getApiKey(this).trim().length() > 0;
            emptySettingsButton.setText(hasKey ? "接口设置" : "填写 API Key");
            emptySettingsButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean hasKey = Prefs.getApiKey(MainActivity.this).trim().length() > 0;
                    startActivity(new Intent(MainActivity.this,
                            hasKey ? SettingsActivity.class : ApiSettingsActivity.class));
                }
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        persistConversations();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (streamHandle != null) {
            streamHandle.cancel();
        }
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_FILE || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        String name = FileUtils.getDisplayName(this, uri);
        String mime = FileUtils.getMimeType(this, uri);
        boolean image = FileUtils.isImage(name, mime);
        boolean text = FileUtils.isTextFile(name, mime);
        if (!image && !text) {
            toast("暂不支持该文件类型，请选择图片或文本 / 代码文件");
            return;
        }
        pendingUri = uri;
        pendingName = name;
        pendingImage = image;
        showAttachment();
    }

    private void sendMessage() {
        if (sending) {
            return;
        }
        String typed = input.getText().toString().trim();
        if (typed.length() == 0 && pendingUri == null) {
            toast("请输入内容或添加附件");
            return;
        }
        String apiKey = Prefs.getApiKey(this);
        if (apiKey.trim().length() == 0 && !(pendingUri != null && pendingImage
                && Prefs.getVisionKey(this).length() > 0)) {
            showKeyDialog();
            return;
        }

        final Uri uri = pendingUri;
        final String fileName = pendingName;
        final boolean image = pendingImage;
        final String userText = typed;

        String displayText = typed;
        if (displayText.length() == 0) {
            displayText = image ? "请分析这张图片" : "请分析这个文件";
        }
        ChatMessage message = new ChatMessage(ChatMessage.ROLE_USER, displayText);
        if (fileName != null) {
            message.setAttachmentName(fileName);
        }
        message.setSendText(typed);
        messages.add(message);
        adapter.notifyDataSetChanged();
        scrollToBottom();
        input.setText("");
        clearAttachment();
        persistConversations();

        sending = true;
        setBusy(true);
        showStatus("正在准备…");

        final List<ChatMessage> prior = ContextWindow.trim(
                new ArrayList<ChatMessage>(messages.subList(0, messages.size() - 1)),
                Prefs.getContextMessages(this));
        final Context appContext = getApplicationContext();

        streamFinishReason = null;
        synchronized (streamBuffer) {
            streamBuffer.setLength(0);
        }
        streamRefreshScheduled = false;
        final ApiClient.StreamHandle handle = new ApiClient.StreamHandle();
        streamHandle = handle;
        final ChatMessage placeholder = new ChatMessage(ChatMessage.ROLE_ASSISTANT, "正在思考…");
        streamingMessage = placeholder;
        messages.add(placeholder);
        adapter.notifyDataSetChanged();
        scrollToBottom();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                String imageDataUri = null;
                String sendText = userText;
                try {
                    if (uri != null) {
                        if (image) {
                            updateStatus("正在压缩图片…");
                            imageDataUri = FileUtils.imageToDataUri(appContext, uri);
                            if (sendText.length() == 0) {
                                sendText = "请分析这张图片，详细描述画面内容、主体、文字信息以及可能的场景。";
                            }
                        } else {
                            updateStatus("正在读取文件…");
                            String content = FileUtils.readText(appContext, uri);
                            String header = sendText.length() == 0 ? "请分析以下文件内容：" : sendText;
                            sendText = header + "\n\n【文件：" + fileName + "】\n" + content;
                        }
                    }

                    updateStatus("正在等待模型回复…");

                    String baseUrl = Prefs.getBaseUrl(appContext);
                    String key = apiKey;
                    String model = Prefs.getChatModel(appContext);
                    if (imageDataUri != null) {
                        String visionBase = Prefs.getVisionBaseUrl(appContext);
                        String visionModel = Prefs.getVisionModel(appContext);
                        String visionKey = Prefs.getVisionKey(appContext);
                        if (visionBase.length() > 0) {
                            baseUrl = visionBase;
                        }
                        if (visionModel.length() > 0) {
                            model = visionModel;
                        } else if (baseUrl.toLowerCase(Locale.US).contains("deepseek")) {
                            // DeepSeek 官方接口使用 deepseek-flash 理解图片
                            model = Prefs.DEFAULT_VISION_MODEL;
                        }
                        if (visionKey.length() > 0) {
                            key = visionKey;
                        }
                    }

                    String reply = ApiClient.chatStream(appContext, baseUrl, key, model,
                            Prefs.getSystemPrompt(appContext), prior, sendText, imageDataUri,
                            Prefs.getMaxTokens(appContext),
                            new ApiClient.StreamCallback() {
                                @Override
                                public void onDelta(String text) {
                                    onStreamDelta(text);
                                }

                                @Override
                                public void onFinish(String finishReason) {
                                    if (finishReason != null) {
                                        streamFinishReason = finishReason;
                                    }
                                }
                            }, handle);

                    if (handle.isCancelled()) {
                        finishStream(null, reply, null, true);
                    } else if (reply.length() == 0) {
                        finishStream("模型没有返回文字内容，请稍后重试", null, streamFinishReason, false);
                    } else {
                        finishStream(null, reply, streamFinishReason, false);
                    }
                } catch (Exception e) {
                    if (handle.isCancelled()) {
                        finishStream(null, null, null, true);
                    } else {
                        String error = e.getMessage();
                        if (error == null || error.trim().length() == 0) {
                            error = "发生未知错误，请稍后重试";
                        }
                        finishStream(error, snapshotStream(), null, false);
                    }
                }
            }
        });
    }

    private void stopGeneration() {
        if (!sending) {
            return;
        }
        ApiClient.StreamHandle current = streamHandle;
        if (current != null) {
            current.cancel();
        }
        showStatus("正在停止…");
    }

    private void onStreamDelta(String piece) {
        synchronized (streamBuffer) {
            streamBuffer.append(piece);
        }
        if (!streamRefreshScheduled) {
            streamRefreshScheduled = true;
            handler.postDelayed(streamRefreshRunnable, STREAM_REFRESH_MS);
        }
    }

    private String snapshotStream() {
        synchronized (streamBuffer) {
            return streamBuffer.toString();
        }
    }

    private void finishStream(final String error, final String reply, final String finishReason,
                              final boolean cancelled) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) {
                    return;
                }
                handler.removeCallbacks(streamRefreshRunnable);
                streamRefreshScheduled = false;
                sending = false;
                setBusy(false);
                hideStatus();

                ChatMessage target = streamingMessage;
                streamingMessage = null;
                streamHandle = null;

                String text = reply == null ? "" : reply;
                if (cancelled) {
                    if (text.trim().length() == 0) {
                        if (target != null) {
                            messages.remove(target);
                        }
                    } else {
                        text = appendNotice(text, "（已停止生成）");
                    }
                }
                if ("length".equals(finishReason)) {
                    text = appendNotice(text, "（回复达到长度上限被截断，可发送「继续」让模型接着写。）");
                }
                if (error != null) {
                    if (text.trim().length() == 0) {
                        if (target != null) {
                            messages.remove(target);
                        }
                        messages.add(new ChatMessage(ChatMessage.ROLE_ERROR, error));
                    } else {
                        text = appendNotice(text, "（连接中断：" + error + "）");
                    }
                }

                if (text.trim().length() > 0) {
                    if (target != null) {
                        target.setText(text);
                    } else {
                        messages.add(new ChatMessage(ChatMessage.ROLE_ASSISTANT, text));
                    }
                }

                adapter.notifyDataSetChanged();
                scrollToBottom();
                persistConversations();
            }
        });
    }

    private static String appendNotice(String text, String notice) {
        if (text == null || text.length() == 0) {
            return notice;
        }
        return text + "\n\n" + notice;
    }

    private void showAttachment() {
        attachmentRow.setVisibility(View.VISIBLE);
        attachmentName.setText(pendingName);
        if (pendingImage) {
            attachmentThumb.setVisibility(View.VISIBLE);
            attachmentBadge.setVisibility(View.GONE);
            loadThumbnail(pendingUri);
        } else {
            attachmentThumb.setVisibility(View.GONE);
            attachmentThumb.setImageDrawable(null);
            attachmentBadge.setVisibility(View.VISIBLE);
            String ext = "";
            int dot = pendingName.lastIndexOf('.');
            if (dot >= 0 && dot < pendingName.length() - 1) {
                ext = pendingName.substring(dot + 1).toUpperCase(Locale.US);
            }
            if (ext.length() > 4) {
                ext = ext.substring(0, 4);
            }
            attachmentBadge.setText(ext.length() == 0 ? "TXT" : ext);
        }
    }

    private void loadThumbnail(final Uri uri) {
        final Context appContext = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = FileUtils.loadThumbnail(appContext, uri);
                if (bitmap == null) {
                    return;
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (pendingUri != null && pendingUri.equals(uri) && !isFinishing()) {
                            attachmentThumb.setImageBitmap(bitmap);
                        }
                    }
                });
            }
        }).start();
    }

    private void clearAttachment() {
        pendingUri = null;
        pendingName = null;
        pendingImage = false;
        attachmentRow.setVisibility(View.GONE);
        attachmentThumb.setImageDrawable(null);
        attachmentBadge.setVisibility(View.GONE);
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择文件"), REQUEST_PICK_FILE);
        } catch (Exception e) {
            toast("没有找到可用的文件选择器");
        }
    }

    private void copyMessage(int position) {
        if (position < 0 || position >= messages.size()) {
            return;
        }
        ChatMessage message = messages.get(position);
        String text = message.getText();
        if (message.getAttachmentName() != null) {
            text = text + "\n\n附件：" + message.getAttachmentName();
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("message", text));
        toast("已复制到剪贴板");
    }

    // ======================== 抽屉与多对话管理 ========================

    private void openDrawer() {
        if (drawerOpen) {
            return;
        }
        drawerOpen = true;
        conversationAdapter.setCurrentId(current.getId());
        conversationAdapter.notifyDataSetChanged();
        int width = drawerPanel.getWidth();
        if (width <= 0) {
            width = (int) (286 * getResources().getDisplayMetrics().density);
        }
        drawerPanel.setTranslationX(-width);
        drawerPanel.setVisibility(View.VISIBLE);
        drawerScrim.setAlpha(0f);
        drawerScrim.setVisibility(View.VISIBLE);
        drawerScrim.animate().alpha(1f).setDuration(200).start();
        drawerPanel.animate().translationX(0f).setDuration(220).start();
    }

    private void closeDrawer() {
        if (!drawerOpen) {
            return;
        }
        drawerOpen = false;
        float width = drawerPanel.getWidth() > 0 ? drawerPanel.getWidth()
                : 286 * getResources().getDisplayMetrics().density;
        drawerPanel.animate().translationX(-width).setDuration(200)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!drawerOpen) {
                            drawerPanel.setVisibility(View.GONE);
                        }
                    }
                }).start();
        drawerScrim.animate().alpha(0f).setDuration(200)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!drawerOpen) {
                            drawerScrim.setVisibility(View.GONE);
                        }
                    }
                }).start();
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        super.onBackPressed();
    }

    private void newConversation() {
        if (sending) {
            toast("请先点击「停止」结束当前回复");
            return;
        }
        if (messages.isEmpty() && current.getTitle().trim().length() == 0) {
            closeDrawer();
            return;
        }
        saveCurrentConversation();
        store.saveConversation(current);
        Conversation conversation = Conversation.create();
        conversations.add(0, conversation);
        current = conversation;
        store.saveConversation(current);
        Prefs.setCurrentConversationId(this, current.getId());
        messages.clear();
        adapter.notifyDataSetChanged();
        clearAttachment();
        conversationAdapter.setCurrentId(current.getId());
        conversationAdapter.notifyDataSetChanged();
        closeDrawer();
    }

    private void switchConversation(int position) {
        if (position < 0 || position >= conversations.size()) {
            return;
        }
        Conversation target = conversations.get(position);
        if (target == current) {
            closeDrawer();
            return;
        }
        if (sending) {
            toast("请先点击「停止」结束当前回复");
            return;
        }
        saveCurrentConversation();
        store.saveConversation(current);
        current = target;
        if (!current.isMessagesLoaded()) {
            current.setMessages(store.loadMessages(current.getId()));
        }
        Prefs.setCurrentConversationId(this, current.getId());
        messages.clear();
        messages.addAll(current.getMessages());
        adapter.notifyDataSetChanged();
        clearAttachment();
        scrollToBottom();
        closeDrawer();
    }

    private void conversationMenu(final int position) {
        if (position < 0 || position >= conversations.size()) {
            return;
        }
        final Conversation conversation = conversations.get(position);
        String[] items = new String[]{"重命名", "删除"};
        new AlertDialog.Builder(this)
                .setTitle(conversation.getDisplayTitle())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            renameConversation(conversation);
                        } else {
                            deleteConversation(conversation);
                        }
                    }
                })
                .show();
    }

    private void renameConversation(final Conversation conversation) {
        LinearLayout container = new LinearLayout(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, 0);
        final EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setText(conversation.getTitle());
        editText.setSelection(editText.getText().length());
        editText.setHint("输入对话名称");
        container.addView(editText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("重命名对话")
                .setView(container)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        conversation.setTitle(editText.getText().toString().trim());
                        conversation.setUpdatedAt(System.currentTimeMillis());
                        store.updateConversationMeta(conversation);
                        conversationAdapter.notifyDataSetChanged();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteConversation(final Conversation conversation) {
        if (sending) {
            toast("请先点击「停止」结束当前回复");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除对话")
                .setMessage("确定删除「" + conversation.getDisplayTitle() + "」吗？删除后无法恢复。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        boolean wasCurrent = conversation == current;
                        conversations.remove(conversation);
                        store.deleteConversation(conversation.getId());
                        if (conversations.isEmpty()) {
                            Conversation created = Conversation.create();
                            store.saveConversation(created);
                            conversations.add(created);
                        }
                        if (wasCurrent) {
                            current = conversations.get(0);
                            if (!current.isMessagesLoaded()) {
                                current.setMessages(store.loadMessages(current.getId()));
                            }
                            messages.clear();
                            messages.addAll(current.getMessages());
                            adapter.notifyDataSetChanged();
                            Prefs.setCurrentConversationId(MainActivity.this, current.getId());
                        } else {
                            saveCurrentConversation();
                            store.saveConversation(current);
                        }
                        conversationAdapter.setCurrentId(current.getId());
                        conversationAdapter.notifyDataSetChanged();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveCurrentConversation() {
        if (current == null) {
            return;
        }
        current.setMessages(new ArrayList<ChatMessage>(messages));
        current.setUpdatedAt(System.currentTimeMillis());
    }

    private void persistConversations() {
        if (current == null) {
            return;
        }
        saveCurrentConversation();
        Prefs.setCurrentConversationId(this, current.getId());
        store.saveConversation(current);
    }

    private void showKeyDialog() {
        new AlertDialog.Builder(this)
                .setTitle("尚未填写 API Key")
                .setMessage("请先在设置中填写 DeepSeek API Key，然后即可开始对话。")
                .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startActivity(new Intent(MainActivity.this, ApiSettingsActivity.class));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void setBusy(boolean busy) {
        // 生成过程中「发送」变为「停止」，保持可点击
        sendButton.setEnabled(true);
        sendButton.setText(busy ? "停止" : "发送");
        attachButton.setEnabled(!busy);
        attachButton.setAlpha(busy ? 0.5f : 1.0f);
    }

    private void showStatus(String text) {
        statusRow.setVisibility(View.VISIBLE);
        statusText.setText(text);
    }

    private void hideStatus() {
        statusRow.setVisibility(View.GONE);
    }

    private void updateStatus(final String text) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing()) {
                    statusText.setText(text);
                }
            }
        });
    }

    private void scrollToBottom() {
        messageList.post(new Runnable() {
            @Override
            public void run() {
                if (adapter.getCount() > 0) {
                    messageList.setSelection(adapter.getCount() - 1);
                }
            }
        });
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
