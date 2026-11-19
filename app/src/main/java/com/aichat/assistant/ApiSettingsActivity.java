package com.aichat.assistant;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 二级设置页：API Key、接口地址、对话模型、图片分析接口与 GitHub 授权。
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
    private TextView githubStatus;
    private EditText githubClientIdInput;
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
        githubStatus = (TextView) findViewById(R.id.github_status);
        githubClientIdInput = (EditText) findViewById(R.id.github_client_id_input);
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
        githubClientIdInput.setText(Prefs.getGitHubClientId(this));
        githubRepoInput.setText(Prefs.getGitHubRepo(this));
        githubBranchInput.setText(Prefs.getGitHubBranch(this));
        updateGitHubStatus();

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
        findViewById(R.id.github_login_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startGitHubLogin();
            }
        });
        findViewById(R.id.github_logout_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmGitHubLogout();
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
        Prefs.setGitHubClientId(this, githubClientIdInput.getText().toString().trim());
        Prefs.setGitHubRepo(this, githubRepoInput.getText().toString().trim());
        Prefs.setGitHubBranch(this, githubBranchInput.getText().toString().trim());
        if (!Prefs.setApiKey(this, apiKeyInput.getText().toString().trim())) {
            encrypted = false;
        }

        Toast.makeText(this, encrypted
                        ? "设置已保存，密钥已加密"
                        : "已保存，但系统 Keystore 不可用，密钥暂以明文保存",
                Toast.LENGTH_LONG).show();
        finish();
    }

    private void updateGitHubStatus() {
        boolean hasToken = Prefs.getGitHubToken(this).trim().length() > 0;
        String login = Prefs.getGitHubLogin(this);
        if (hasToken && login.length() > 0) {
            githubStatus.setText("已登录：" + login);
        } else if (hasToken) {
            githubStatus.setText("已登录（令牌已保存）");
        } else {
            githubStatus.setText("未登录");
        }
    }

    /** 浏览器设备授权登录：申请短码 -> 打开浏览器 -> 轮询拿 token。 */
    private void startGitHubLogin() {
        String typedClientId = githubClientIdInput.getText().toString().trim();
        if (typedClientId.length() > 0) {
            Prefs.setGitHubClientId(this, typedClientId);
        }
        final String clientId = Prefs.getGitHubClientId(this);
        if (clientId.length() == 0) {
            showClientIdHelp();
            return;
        }
        Toast.makeText(this, "正在向 GitHub 申请授权码…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final GitHubAuth.DeviceCode code;
                try {
                    code = GitHubAuth.requestCode(getApplicationContext(), clientId);
                } catch (final ApiException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(ApiSettingsActivity.this,
                                    e.getMessage() == null ? "申请授权码失败" : e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showDeviceDialog(clientId, code);
                    }
                });
            }
        }).start();
    }

    private void showDeviceDialog(final String clientId, final GitHubAuth.DeviceCode code) {
        copyToClipboard(code.userCode);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("在浏览器中完成授权")
                .setMessage("授权码 " + code.userCode + " 已复制。\n\n"
                        + "1. 点击「打开浏览器」\n"
                        + "2. 在 GitHub 页面粘贴或输入上面的授权码\n"
                        + "3. 点击 Authorize，然后回到这里\n\n"
                        + "登录会自动完成，请勿关闭本对话框。")
                .setPositiveButton("打开浏览器", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                openBrowser(code.verificationUri);
                            }
                        });
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                cancelled.set(true);
                                dialog.dismiss();
                            }
                        });
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                cancelled.set(true);
            }
        });
        dialog.show();
        openBrowser(code.verificationUri);

        new Thread(new Runnable() {
            @Override
            public void run() {
                String token = null;
                String error = null;
                try {
                    token = GitHubAuth.pollToken(getApplicationContext(), clientId,
                            code.deviceCode, code.interval, code.expiresIn, cancelled);
                } catch (ApiException e) {
                    error = e.getMessage() == null ? "GitHub 登录失败" : e.getMessage();
                } catch (Exception e) {
                    error = "GitHub 登录失败：" + e;
                }

                if (token != null) {
                    String login = "";
                    String scopes = "";
                    try {
                        GitHubClient client = new GitHubClient(getApplicationContext(), token);
                        login = client.getLogin();
                        scopes = client.getScopes();
                    } catch (Exception ignored) {
                    }
                    Prefs.setGitHubToken(getApplicationContext(), token);
                    if (login.length() > 0) {
                        Prefs.setGitHubLogin(getApplicationContext(), login);
                    }
                    final String loginText = login;
                    final String scopeText = scopes;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dialog.dismiss();
                            githubClientIdInput.setText(Prefs.getGitHubClientId(
                                    ApiSettingsActivity.this));
                            updateGitHubStatus();
                            String message = loginText.length() > 0
                                    ? "已登录 GitHub：" + loginText
                                    : "GitHub 登录成功";
                            if (scopeText.length() == 0) {
                                message += "\n权限：无（无法读写仓库）";
                            } else {
                                message += "\n权限：" + scopeText;
                            }
                            if (!scopeText.contains("repo")) {
                                message += "\n\n⚠ 未获得 repo 权限，GitHub 助手将无法提交或建库。"
                                        + "请到 GitHub → Settings → Applications 撤销本应用授权后重新登录。";
                            }
                            Toast.makeText(ApiSettingsActivity.this, message,
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                } else {
                    final String message = error;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!cancelled.get()) {
                                dialog.dismiss();
                                showLoginError(message);
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private void showLoginError(String message) {
        final String text = message == null || message.length() == 0
                ? "GitHub 登录失败" : message;
        String hint = "";
        if (text.contains("SSL") || text.contains("CertPath") || text.contains("证书")
                || text.contains("Handshake")) {
            hint = "\n\n提示：若本机浏览器也打不开 GitHub，可在其他设备或电脑访问 "
                    + "https://github.com/login/device 输入授权码完成授权，App 会自动继续登录。"
                    + "\n另外请先关闭 VPN、广告拦截或抓包类应用再试。";
        }
        new AlertDialog.Builder(this)
                .setTitle("GitHub 登录失败")
                .setMessage(text + hint)
                .setPositiveButton("复制错误", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyToClipboard(text);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showClientIdHelp() {
        new AlertDialog.Builder(this)
                .setTitle("首次使用：创建授权应用")
                .setMessage("浏览器授权前需要一个 GitHub OAuth App，只需创建一次（免费）：\n\n"
                        + "1. 打开创建页\n"
                        + "2. Application name 填 RuiDS Chat\n"
                        + "3. Homepage URL 填 https://github.com/liqirui1145/RuiDSChat\n"
                        + "4. Authorization callback URL 也填同一地址\n"
                        + "5. 务必勾选 Enable Device Flow\n"
                        + "6. 点 Register application，复制 Client ID，粘贴到上方输入框并保存\n\n"
                        + "Client ID 不是密钥，可以公开。")
                .setPositiveButton("打开创建页", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openBrowser(GitHubAuth.NEW_APP_URL);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmGitHubLogout() {
        if (Prefs.getGitHubToken(this).trim().length() == 0) {
            Toast.makeText(this, "当前未登录 GitHub", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("退出 GitHub 登录")
                .setMessage("退出后需要重新通过浏览器授权，才能继续使用 GitHub 助手。")
                .setPositiveButton("退出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.setGitHubToken(ApiSettingsActivity.this, "");
                        Prefs.setGitHubLogin(ApiSettingsActivity.this, "");
                        Prefs.setGitHubMode(ApiSettingsActivity.this, false);
                        updateGitHubStatus();
                        Toast.makeText(ApiSettingsActivity.this, "已退出 GitHub 登录",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void verifyGitHub() {
        final String token = Prefs.getGitHubToken(this).trim();
        if (token.length() == 0) {
            Toast.makeText(this, "请先通过浏览器登录 GitHub", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在连接 GitHub…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    GitHubClient client = new GitHubClient(ApiSettingsActivity.this, token);
                    String login = client.getLogin();
                    String scopes = client.getScopes();
                    Prefs.setGitHubLogin(ApiSettingsActivity.this, login);
                    result = "连接成功，当前账号：" + login
                            + "\n权限：" + (scopes.length() == 0 ? "无（无法读写仓库）" : scopes);
                    if (!scopes.contains("repo")) {
                        result += "\n\n⚠ 缺少 repo 权限，请在 GitHub 撤销授权后重新登录";
                    }
                } catch (ApiException e) {
                    result = e.getMessage() == null ? "连接失败" : e.getMessage();
                } catch (Exception e) {
                    result = "连接失败：" + e;
                }
                final String message = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateGitHubStatus();
                        Toast.makeText(ApiSettingsActivity.this, message,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void openBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开浏览器，请手动访问：" + url,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("GitHub 授权码", text));
            }
        } catch (Exception ignored) {
        }
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
