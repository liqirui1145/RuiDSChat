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
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends BaseActivity implements ChatAdapter.OnMessageActionListener {

    private static final int REQUEST_PICK_FILE = 1001;

    private ListView messageList;
    private ChatAdapter adapter;
    private EditText input;
    private TextView sendButton;
    private TextView attachButton;
    private TextView thinkingButton;
    /** 思考强度选项，下标与 Prefs.getReasoningLevel 对应。 */
    private static final String[] REASONING_LABELS =
            new String[]{"关闭（直接回答）", "极低", "低", "中", "高"};
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
    private EditText drawerSearch;
    private TextView usageSummary;
    private ConversationAdapter conversationAdapter;

    /** 全部会话；conversations 用于切换与保存。 */
    private final List<Conversation> conversations = new ArrayList<Conversation>();
    /** 抽屉中实际展示的会话（搜索过滤后）。 */
    private final List<Conversation> visibleConversations = new ArrayList<Conversation>();
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
    private final StringBuilder reasoningBuffer = new StringBuilder();
    private volatile boolean streamRefreshScheduled;
    private String streamFinishReason;
    /** 本次请求返回的 token 用量。 */
    private volatile int streamPromptTokens;
    private volatile int streamCompletionTokens;
    /** 续写时已有内容的开头部分，流式刷新时拼在增量前面。 */
    private String streamPrefix = "";
    /** 用户是否停留在列表底部；上滑查看历史时不再强制跟随输出。 */
    private boolean atBottom = true;

    /** 流式增量节流刷新，避免每个 token 都重绘整条消息。 */
    private final Runnable streamRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            streamRefreshScheduled = false;
            ChatMessage target = streamingMessage;
            if (target == null) {
                return;
            }
            String reasoning = snapshotReasoning();
            String partial = streamPrefix + snapshotStream();
            if (partial.length() == 0 && reasoning.length() > 0) {
                // 思考阶段还没有正文，保留占位文案
                partial = "正在思考…";
            }
            target.setText(partial);
            if (reasoning.length() > 0) {
                target.setReasoning(reasoning);
            }
            hideStatus();
            adapter.notifyDataSetChanged();
            if (atBottom) {
                scrollToBottom();
            }
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
        thinkingButton = (TextView) findViewById(R.id.thinking_button);
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
        drawerSearch = (EditText) findViewById(R.id.drawer_search);
        usageSummary = (TextView) findViewById(R.id.usage_summary);

        store = new ChatStore(this);
        loadConversations();

        adapter = new ChatAdapter(this, messages);
        adapter.setMessageActionListener(this);
        messageList.setAdapter(adapter);
        View emptyView = findViewById(R.id.empty_state);
        messageList.setEmptyView(emptyView);
        messageList.setSelection(adapter.getCount());
        messageList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
                                 int totalItemCount) {
                if (totalItemCount == 0) {
                    atBottom = true;
                } else {
                    atBottom = firstVisibleItem + visibleItemCount >= totalItemCount - 1;
                }
            }
        });

        conversationAdapter = new ConversationAdapter(this, visibleConversations);
        conversationAdapter.setCurrentId(current.getId());
        conversationList.setAdapter(conversationAdapter);
        refreshConversationList();

        // 输入框恢复上次未发送的草稿
        input.setText(Prefs.getDraft(this, current.getId()));
        if (input.getText().length() > 0) {
            input.setSelection(input.getText().length());
        }
        drawerSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refreshConversationList();
            }
        });
        usageSummary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showUsageDialog();
            }
        });
        refreshUsageSummary();

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
        thinkingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showReasoningDialog();
            }
        });
        updateThinkingLabel();
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
        findViewById(R.id.top_new_button).setOnClickListener(new View.OnClickListener() {
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
        saveDraft();
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
        Prefs.setDraft(this, current.getId(), "");
        persistConversations();

        final List<ChatMessage> prior = ContextWindow.trim(
                new ArrayList<ChatMessage>(messages.subList(0, messages.size() - 1)),
                Prefs.getContextMessages(this));
        startStreaming(prior, userText, uri, fileName, image, null);
    }

    /** 发起流式请求；首次提问、重新生成和继续生成共用这套流程。 */
    private void startStreaming(final List<ChatMessage> prior, final String userText,
                                final Uri uri, final String fileName, final boolean image,
                                final ChatMessage continueTarget) {
        sending = true;
        setBusy(true);
        showStatus("正在准备…");

        final Context appContext = getApplicationContext();

        streamFinishReason = null;
        streamPromptTokens = 0;
        streamCompletionTokens = 0;
        streamPrefix = continueTarget == null ? "" : continueTarget.getText();
        synchronized (streamBuffer) {
            streamBuffer.setLength(0);
        }
        synchronized (reasoningBuffer) {
            reasoningBuffer.setLength(0);
        }
        streamRefreshScheduled = false;
        final ApiClient.StreamHandle handle = new ApiClient.StreamHandle();
        streamHandle = handle;
        if (continueTarget != null) {
            continueTarget.setTruncated(false);
            streamingMessage = continueTarget;
        } else {
            ChatMessage placeholder = new ChatMessage(ChatMessage.ROLE_ASSISTANT, "正在思考…");
            streamingMessage = placeholder;
            messages.add(placeholder);
        }
        adapter.notifyDataSetChanged();
        atBottom = true;
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
                    String key = Prefs.getApiKey(appContext);
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
                            Prefs.getReasoningLevel(appContext),
                            new ApiClient.StreamCallback() {
                                @Override
                                public void onDelta(String text) {
                                    onStreamDelta(text);
                                }

                                @Override
                                public void onReasoning(String text) {
                                    onStreamReasoning(text);
                                }

                                @Override
                                public void onUsage(int promptTokens, int completionTokens) {
                                    streamPromptTokens = promptTokens;
                                    streamCompletionTokens = completionTokens;
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
                        finishStream("模型没有返回文字内容，请稍后重试", reply, streamFinishReason, false);
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
        ApiClient.StreamHandle handle = streamHandle;
        if (handle != null) {
            handle.cancel();
        }
        showStatus("正在停止…");
    }

    @Override
    public void onMessageLongClick(final ChatMessage message) {
        final List<String> actions = new ArrayList<String>();
        actions.add("复制内容");
        if (message.getRole() == ChatMessage.ROLE_USER) {
            actions.add("编辑并重发");
        }
        if (message.getRole() == ChatMessage.ROLE_ASSISTANT) {
            actions.add("重新生成");
        }
        actions.add("删除这条");
        actions.add("导出 / 分享对话");

        new AlertDialog.Builder(this)
                .setTitle("消息操作")
                .setItems(actions.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String action = actions.get(which);
                        if ("复制内容".equals(action)) {
                            copyMessage(message);
                        } else if ("编辑并重发".equals(action)) {
                            editAndResend(message);
                        } else if ("重新生成".equals(action)) {
                            confirmRegenerate(message);
                        } else if ("删除这条".equals(action)) {
                            deleteMessage(message);
                        } else {
                            exportConversation();
                        }
                    }
                })
                .show();
    }

    /** 把这条用户消息回填到输入框，并删除它之后的全部内容，等待编辑后重新发送。 */
    private void editAndResend(ChatMessage message) {
        if (sending) {
            toast("正在生成回复，请先停止");
            return;
        }
        int index = messages.indexOf(message);
        if (index < 0) {
            return;
        }
        String text = message.getText();
        while (messages.size() > index) {
            messages.remove(messages.size() - 1);
        }
        adapter.notifyDataSetChanged();
        persistConversations();
        refreshUsageSummary();

        input.setText(text);
        input.setSelection(input.getText().length());
        input.requestFocus();
        InputMethodManager manager =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
        toast("已回填到输入框，编辑后点击发送");
    }

    @Override
    public void onContinueGeneration(ChatMessage message) {
        if (sending) {
            toast("正在生成回复，请稍候");
            return;
        }
        int index = messages.indexOf(message);
        if (index < 0) {
            return;
        }
        List<ChatMessage> prior = ContextWindow.trim(
                new ArrayList<ChatMessage>(messages.subList(0, index + 1)),
                Prefs.getContextMessages(this));
        startStreaming(prior, "请接着上文继续输出未完成的内容，不要重复已经输出过的部分。",
                null, null, false, message);
    }

    private void copyMessage(ChatMessage message) {
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager == null) {
            return;
        }
        manager.setPrimaryClip(ClipData.newPlainText("消息内容", message.getText()));
        toast("已复制到剪贴板");
    }

    private void deleteMessage(ChatMessage message) {
        if (sending) {
            toast("回复正在生成，请先停止");
            return;
        }
        messages.remove(message);
        adapter.notifyDataSetChanged();
        refreshUsageSummary();
        persistConversations();
    }

    private void confirmRegenerate(final ChatMessage message) {
        if (sending) {
            toast("请先停止当前回复");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("重新生成")
                .setMessage("将删除这条回复及其之后的内容，并重新向模型提问，确定吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("重新生成", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        regenerate(message);
                    }
                })
                .show();
    }

    private void regenerate(ChatMessage message) {
        int index = messages.indexOf(message);
        if (index < 0) {
            return;
        }
        int userIndex = -1;
        for (int i = index - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == ChatMessage.ROLE_USER) {
                userIndex = i;
                break;
            }
        }
        if (userIndex < 0) {
            toast("没有找到对应的提问，无法重新生成");
            return;
        }
        String question = messages.get(userIndex).getText();
        while (messages.size() > userIndex + 1) {
            messages.remove(messages.size() - 1);
        }
        adapter.notifyDataSetChanged();
        persistConversations();

        List<ChatMessage> prior = ContextWindow.trim(
                new ArrayList<ChatMessage>(messages), Prefs.getContextMessages(this));
        startStreaming(prior, question, null, null, false, null);
    }

    private void exportConversation() {
        boolean hasContent = false;
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatMessage.ROLE_USER
                    || message.getRole() == ChatMessage.ROLE_ASSISTANT) {
                hasContent = true;
                break;
            }
        }
        if (!hasContent) {
            toast("当前对话还没有内容");
            return;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(current.getDisplayTitle()).append("\n\n");
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatMessage.ROLE_USER) {
                builder.append("## 我\n\n");
            } else if (message.getRole() == ChatMessage.ROLE_ASSISTANT) {
                builder.append("## AI\n\n");
            } else {
                continue;
            }
            builder.append(message.getText()).append("\n\n");
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, current.getDisplayTitle());
        intent.putExtra(Intent.EXTRA_TEXT, builder.toString());
        startActivity(Intent.createChooser(intent, "导出对话"));
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

    private void onStreamReasoning(String piece) {
        synchronized (reasoningBuffer) {
            reasoningBuffer.append(piece);
        }
        if (!streamRefreshScheduled) {
            streamRefreshScheduled = true;
            handler.postDelayed(streamRefreshRunnable, STREAM_REFRESH_MS);
        }
    }

    private String snapshotReasoning() {
        synchronized (reasoningBuffer) {
            return reasoningBuffer.toString();
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
                String prefix = streamPrefix;
                streamPrefix = "";

                String text = reply == null ? "" : reply;
                if (cancelled) {
                    if (text.trim().length() == 0 && prefix.length() == 0) {
                        if (target != null) {
                            messages.remove(target);
                        }
                    } else {
                        text = appendNotice(text, "（已停止生成）");
                    }
                }
                if (error != null) {
                    if (text.trim().length() == 0 && prefix.length() == 0) {
                        if (target != null) {
                            messages.remove(target);
                        }
                        messages.add(new ChatMessage(ChatMessage.ROLE_ERROR, error));
                        adapter.notifyDataSetChanged();
                        scrollToBottom();
                        persistConversations();
                        return;
                    }
                    text = appendNotice(text, "（连接中断：" + error + "）");
                }

                // 续写时把已有内容拼在增量前面
                boolean truncated = "length".equals(finishReason) && error == null && !cancelled;
                String combined = prefix + text;
                ChatMessage finished = target;
                if (combined.trim().length() > 0) {
                    if (target != null) {
                        target.setText(combined);
                        target.setTruncated(truncated);
                    } else {
                        ChatMessage created =
                                new ChatMessage(ChatMessage.ROLE_ASSISTANT, combined);
                        created.setTruncated(truncated);
                        messages.add(created);
                        finished = created;
                    }
                } else if (truncated && target != null) {
                    target.setTruncated(true);
                }
                if (finished != null) {
                    String reasoning = snapshotReasoning();
                    if (reasoning.trim().length() > 0) {
                        finished.setReasoning(reasoning);
                    }
                    finished.setPromptTokens(streamPromptTokens);
                    finished.setCompletionTokens(streamCompletionTokens);
                }

                adapter.notifyDataSetChanged();
                if (atBottom) {
                    scrollToBottom();
                }
                refreshUsageSummary();
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

    /** 选择本次提问的思考强度，选项全局记忆。 */
    private void showReasoningDialog() {
        new AlertDialog.Builder(this)
                .setTitle("思考强度")
                .setSingleChoiceItems(REASONING_LABELS, Prefs.getReasoningLevel(this),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Prefs.setReasoningLevel(MainActivity.this, which);
                                updateThinkingLabel();
                                dialog.dismiss();
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateThinkingLabel() {
        int level = Prefs.getReasoningLevel(this);
        if (level == 0) {
            thinkingButton.setText("思考 关闭");
        } else {
            thinkingButton.setText("思考 " + REASONING_LABELS[level]);
        }
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

    // ======================== 抽屉与多对话管理 ========================

    private void openDrawer() {
        if (drawerOpen) {
            return;
        }
        drawerOpen = true;
        refreshConversationList();
        int width = drawerPanel.getWidth();
        if (width <= 0) {
            width = (int) (300 * getResources().getDisplayMetrics().density);
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
                : 300 * getResources().getDisplayMetrics().density;
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
        saveDraft();
        saveCurrentConversation();
        store.saveConversation(current);
        Conversation conversation = Conversation.create();
        conversations.add(0, conversation);
        current = conversation;
        store.saveConversation(current);
        sortConversations();
        Prefs.setCurrentConversationId(this, current.getId());
        messages.clear();
        adapter.notifyDataSetChanged();
        clearAttachment();
        input.setText("");
        drawerSearch.setText("");
        refreshConversationList();
        refreshUsageSummary();
        closeDrawer();
    }

    private void switchConversation(int position) {
        if (position < 0 || position >= visibleConversations.size()) {
            return;
        }
        Conversation target = visibleConversations.get(position);
        if (target == current) {
            closeDrawer();
            return;
        }
        if (sending) {
            toast("请先点击「停止」结束当前回复");
            return;
        }
        saveDraft();
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
        // 恢复该会话上次未发送的草稿
        input.setText(Prefs.getDraft(this, current.getId()));
        if (input.getText().length() > 0) {
            input.setSelection(input.getText().length());
        }
        refreshConversationList();
        refreshUsageSummary();
        scrollToBottom();
        closeDrawer();
    }

    private void conversationMenu(final int position) {
        if (position < 0 || position >= visibleConversations.size()) {
            return;
        }
        final Conversation conversation = visibleConversations.get(position);
        final List<String> items = new ArrayList<String>();
        items.add(conversation.isPinned() ? "取消置顶" : "置顶会话");
        items.add("重命名");
        items.add("清空消息");
        items.add("删除");

        new AlertDialog.Builder(this)
                .setTitle(conversation.getDisplayTitle())
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            togglePin(conversation);
                        } else if (which == 1) {
                            renameConversation(conversation);
                        } else if (which == 2) {
                            confirmClearConversation(conversation);
                        } else {
                            deleteConversation(conversation);
                        }
                    }
                })
                .show();
    }

    /** 置顶 / 取消置顶，并重新排序抽屉列表。 */
    private void togglePin(Conversation conversation) {
        conversation.setPinned(!conversation.isPinned());
        store.updateConversationMeta(conversation);
        sortConversations();
        refreshConversationList();
        toast(conversation.isPinned() ? "已置顶" : "已取消置顶");
    }

    private void confirmClearConversation(final Conversation conversation) {
        if (sending) {
            toast("请先点击「停止」结束当前回复");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空消息")
                .setMessage("确定清空「" + conversation.getDisplayTitle()
                        + "」中的全部消息吗？会话本身会保留。")
                .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        clearConversation(conversation);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearConversation(Conversation conversation) {
        conversation.setMessages(new ArrayList<ChatMessage>());
        conversation.setStoredSummary("", 0);
        conversation.setUpdatedAt(System.currentTimeMillis());
        store.clearMessages(conversation.getId());
        store.updateConversationMeta(conversation);
        if (conversation == current) {
            messages.clear();
            adapter.notifyDataSetChanged();
            Prefs.setDraft(this, conversation.getId(), "");
            refreshUsageSummary();
        }
        sortConversations();
        refreshConversationList();
        toast("已清空该会话");
    }

    /** 输入框内容按会话保存为草稿。 */
    private void saveDraft() {
        if (current == null) {
            return;
        }
        Prefs.setDraft(this, current.getId(), input.getText().toString());
    }

    /** 按搜索关键词重建抽屉列表。 */
    private void refreshConversationList() {
        if (conversationAdapter == null) {
            return;
        }
        String keyword = drawerSearch == null ? "" : drawerSearch.getText().toString().trim();
        visibleConversations.clear();
        if (keyword.length() == 0) {
            visibleConversations.addAll(conversations);
        } else {
            Set<String> ids = store.searchConversationIds(keyword);
            for (Conversation conversation : conversations) {
                if (ids.contains(conversation.getId())) {
                    visibleConversations.add(conversation);
                }
            }
        }
        conversationAdapter.setCurrentId(current == null ? "" : current.getId());
        conversationAdapter.notifyDataSetChanged();
    }

    /** 置顶优先，其次按最近更新时间排序。 */
    private void sortConversations() {
        Collections.sort(conversations, new Comparator<Conversation>() {
            @Override
            public int compare(Conversation left, Conversation right) {
                if (left.isPinned() != right.isPinned()) {
                    return left.isPinned() ? -1 : 1;
                }
                if (left.getUpdatedAt() == right.getUpdatedAt()) {
                    return 0;
                }
                return left.getUpdatedAt() > right.getUpdatedAt() ? -1 : 1;
            }
        });
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
                            input.setText(Prefs.getDraft(MainActivity.this, current.getId()));
                        } else {
                            saveCurrentConversation();
                            store.saveConversation(current);
                        }
                        refreshConversationList();
                        refreshUsageSummary();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 统计当前会话累计 tokens 并按设置单价估算费用。 */
    private void refreshUsageSummary() {
        if (usageSummary == null) {
            return;
        }
        int prompt = 0;
        int completion = 0;
        for (ChatMessage message : messages) {
            prompt += message.getPromptTokens();
            completion += message.getCompletionTokens();
        }
        int total = prompt + completion;
        if (total <= 0) {
            usageSummary.setText("本会话暂无 token 用量");
            return;
        }
        double cost = prompt / 1000000d * Prefs.getInputPrice(this)
                + completion / 1000000d * Prefs.getOutputPrice(this);
        usageSummary.setText("本会话 " + formatTokens(total) + " tokens · 约 ¥"
                + formatCost(cost));
    }

    private void showUsageDialog() {
        int prompt = 0;
        int completion = 0;
        for (ChatMessage message : messages) {
            prompt += message.getPromptTokens();
            completion += message.getCompletionTokens();
        }
        double inputPrice = Prefs.getInputPrice(this);
        double outputPrice = Prefs.getOutputPrice(this);
        double cost = prompt / 1000000d * inputPrice + completion / 1000000d * outputPrice;
        StringBuilder builder = new StringBuilder();
        builder.append("本会话累计：").append(formatTokens(prompt + completion)).append(" tokens\n");
        builder.append("· 输入：").append(formatTokens(prompt)).append(" tokens（¥")
                .append(trimPrice(inputPrice)).append(" / 百万）\n");
        builder.append("· 输出：").append(formatTokens(completion)).append(" tokens（¥")
                .append(trimPrice(outputPrice)).append(" / 百万）\n");
        builder.append("· 估算费用：约 ¥").append(formatCost(cost)).append("\n\n");
        builder.append("说明：\n");
        builder.append("1. 用量来自接口返回的 usage 字段，仅统计本会话当前加载的消息。\n");
        builder.append("2. 每条回复的输入 token 都包含其上下文，因此多轮对话的输入量会重复累计。\n");
        builder.append("3. 单价可在「设置 - 接口设置 - 费用估算」中修改，实际扣费以服务商账单为准。");
        new AlertDialog.Builder(this)
                .setTitle("Token 用量与费用")
                .setMessage(builder.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    private static String formatTokens(int value) {
        return String.format(Locale.US, "%,d", value);
    }

    private static String formatCost(double cost) {
        if (cost <= 0) {
            return "0";
        }
        if (cost < 0.0001) {
            return "<0.0001";
        }
        return String.format(Locale.US, "%.4f", cost);
    }

    private static String trimPrice(double value) {
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
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
        sendButton.setText(busy ? "■" : "↑");
        sendButton.setTextSize(busy ? 15 : 20);
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
