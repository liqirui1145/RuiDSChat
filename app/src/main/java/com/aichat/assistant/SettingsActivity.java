package com.aichat.assistant;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Toast;

public class SettingsActivity extends BaseActivity {

    private CheckBox darkModeCheck;
    private EditText systemPromptInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        darkModeCheck = (CheckBox) findViewById(R.id.dark_mode_check);
        systemPromptInput = (EditText) findViewById(R.id.system_prompt_input);

        systemPromptInput.setText(Prefs.getSystemPrompt(this));
        darkModeCheck.setChecked(Prefs.isDarkMode(this));
        darkModeCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.setDarkMode(SettingsActivity.this, isChecked);
                recreate();
            }
        });

        findViewById(R.id.api_settings_entry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, ApiSettingsActivity.class));
            }
        });

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
        Prefs.setSystemPrompt(this, systemPromptInput.getText().toString().trim());
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }
}
