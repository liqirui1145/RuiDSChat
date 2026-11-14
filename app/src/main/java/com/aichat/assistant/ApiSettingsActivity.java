package com.aichat.assistant;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

/**
 * 二级设置页：API Key、接口地址、对话模型与图片分析接口。
 */
public class ApiSettingsActivity extends BaseActivity {

    private EditText apiKeyInput;
    private EditText baseUrlInput;
    private EditText chatModelInput;
    private EditText maxTokensInput;
    private EditText contextMessagesInput;
    private EditText visionBaseUrlInput;
    private EditText visionModelInput;
    private EditText visionKeyInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_api_settings);

        apiKeyInput = (EditText) findViewById(R.id.api_key_input);
        baseUrlInput = (EditText) findViewById(R.id.base_url_input);
        chatModelInput = (EditText) findViewById(R.id.chat_model_input);
        maxTokensInput = (EditText) findViewById(R.id.max_tokens_input);
        contextMessagesInput = (EditText) findViewById(R.id.context_messages_input);
        visionBaseUrlInput = (EditText) findViewById(R.id.vision_base_url_input);
        visionModelInput = (EditText) findViewById(R.id.vision_model_input);
        visionKeyInput = (EditText) findViewById(R.id.vision_key_input);

        apiKeyInput.setText(Prefs.getApiKey(this));
        baseUrlInput.setText(Prefs.getBaseUrl(this));
        chatModelInput.setText(Prefs.getChatModel(this));
        int maxTokens = Prefs.getMaxTokens(this);
        maxTokensInput.setText(maxTokens > 0 ? String.valueOf(maxTokens) : "");
        contextMessagesInput.setText(String.valueOf(Prefs.getContextMessages(this)));
        visionBaseUrlInput.setText(Prefs.getVisionBaseUrl(this));
        visionModelInput.setText(Prefs.getVisionModel(this));
        visionKeyInput.setText(Prefs.getVisionKey(this));

        findViewById(R.id.back_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.save_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
    }

    private void save() {
        String baseUrl = baseUrlInput.getText().toString().trim();
        String chatModel = chatModelInput.getText().toString().trim();

        if (baseUrl.length() == 0) {
            baseUrl = Prefs.DEFAULT_BASE_URL;
        }
        if (chatModel.length() == 0) {
            chatModel = Prefs.DEFAULT_CHAT_MODEL;
        }

        Prefs.setApiKey(this, apiKeyInput.getText().toString().trim());
        Prefs.setBaseUrl(this, baseUrl);
        Prefs.setChatModel(this, chatModel);
        int maxTokens = 0;
        try {
            maxTokens = Integer.parseInt(maxTokensInput.getText().toString().trim());
        } catch (NumberFormatException ignored) {
        }
        Prefs.setMaxTokens(this, maxTokens);
        int contextMessages = 20;
        try {
            contextMessages = Integer.parseInt(contextMessagesInput.getText().toString().trim());
            if (contextMessages < 0) {
                contextMessages = 0;
            }
        } catch (NumberFormatException ignored) {
        }
        Prefs.setContextMessages(this, contextMessages);
        Prefs.setVisionBaseUrl(this, visionBaseUrlInput.getText().toString().trim());
        Prefs.setVisionModel(this, visionModelInput.getText().toString().trim());
        Prefs.setVisionKey(this, visionKeyInput.getText().toString().trim());

        Toast.makeText(this, "接口设置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }
}
