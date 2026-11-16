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
    private EditText priceInputInput;
    private EditText priceOutputInput;
    private EditText visionBaseUrlInput;
    private EditText visionModelInput;
    private EditText visionKeyInput;
    private EditText githubTokenInput;
    private EditText githubRepoInput;
    private EditText githubBranchInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_api_settings);

        apiKeyInput = (EditText) findViewById(R.id.api_key_input);
        baseUrlInput = (EditText) findViewById(R.id.base_url_input);
        chatModelInput = (EditText) findViewById(R.id.chat_model_input);
        maxTokensInput = (EditText) findViewById(R.id.max_tokens_input);
        contextMessagesInput = (EditText) findViewById(R.id.context_messages_input);
        priceInputInput = (EditText) findViewById(R.id.price_input_input);
        priceOutputInput = (EditText) findViewById(R.id.price_output_input);
        visionBaseUrlInput = (EditText) findViewById(R.id.vision_base_url_input);
        visionModelInput = (EditText) findViewById(R.id.vision_model_input);
        visionKeyInput = (EditText) findViewById(R.id.vision_key_input);
        githubTokenInput = (EditText) findViewById(R.id.github_token_input);
        githubRepoInput = (EditText) findViewById(R.id.github_repo_input);
        githubBranchInput = (EditText) findViewById(R.id.github_branch_input);

        apiKeyInput.setText(Prefs.getApiKey(this));
        baseUrlInput.setText(Prefs.getBaseUrl(this));
        chatModelInput.setText(Prefs.getChatModel(this));
        int maxTokens = Prefs.getMaxTokens(this);
        maxTokensInput.setText(maxTokens > 0 ? String.valueOf(maxTokens) : "");
        contextMessagesInput.setText(String.valueOf(Prefs.getContextMessages(this)));
        priceInputInput.setText(formatPrice(Prefs.getInputPrice(this)));
        priceOutputInput.setText(formatPrice(Prefs.getOutputPrice(this)));
        visionBaseUrlInput.setText(Prefs.getVisionBaseUrl(this));
        visionModelInput.setText(Prefs.getVisionModel(this));
        visionKeyInput.setText(Prefs.getVisionKey(this));
        githubTokenInput.setText(Prefs.getGitHubToken(this));
        githubRepoInput.setText(Prefs.getGitHubRepo(this));
        githubBranchInput.setText(Prefs.getGitHubBranch(this));

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
        findViewById(R.id.github_verify_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                verifyGitHub();
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
        Prefs.setInputPrice(this, parsePrice(priceInputInput.getText().toString(), 2f));
        Prefs.setOutputPrice(this, parsePrice(priceOutputInput.getText().toString(), 8f));
        Prefs.setVisionBaseUrl(this, visionBaseUrlInput.getText().toString().trim());
        Prefs.setVisionModel(this, visionModelInput.getText().toString().trim());
        boolean encrypted = Prefs.setVisionKey(this,
                visionKeyInput.getText().toString().trim());
        if (!Prefs.setGitHubToken(this, githubTokenInput.getText().toString().trim())) {
            encrypted = false;
        }
        Prefs.setGitHubRepo(this, githubRepoInput.getText().toString().trim());
        Prefs.setGitHubBranch(this, githubBranchInput.getText().toString().trim());
        if (!Prefs.setApiKey(this, apiKeyInput.getText().toString().trim())) {
            encrypted = false;
        }

        Toast.makeText(this, encrypted
                        ? "接口设置已保存，API Key 已加密"
                        : "已保存，但系统 Keystore 不可用，Key 暂以明文保存",
                Toast.LENGTH_LONG).show();
        finish();
    }

    private void verifyGitHub() {
        final String token = githubTokenInput.getText().toString().trim();
        if (token.length() == 0) {
            Toast.makeText(this, "请先填写 GitHub Token", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在连接 GitHub…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    String login = new GitHubClient(ApiSettingsActivity.this, token).getLogin();
                    result = "连接成功，当前账号：" + login;
                } catch (ApiException e) {
                    result = e.getMessage() == null ? "连接失败" : e.getMessage();
                } catch (Exception e) {
                    result = "连接失败：" + e;
                }
                final String message = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(ApiSettingsActivity.this, message,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private static float parsePrice(String text, float fallback) {
        try {
            float value = Float.parseFloat(text.trim());
            return value < 0 ? 0 : value;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String formatPrice(float value) {
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
