package com.aichat.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;

/**
 * 所有界面共用的基类：根据「深色模式」偏好强制覆盖夜间资源。
 * 这样即使在 Android 4.4 上，也能使用 values-night 里的深色配色。
 */
public class BaseActivity extends Activity {

    /** 当前界面实际采用的深色状态，用于 onResume 时判断是否需要重建。 */
    protected boolean appliedDark;

    @Override
    protected void attachBaseContext(Context newBase) {
        Configuration config = new Configuration(newBase.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (Prefs.isDarkMode(newBase)
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO);
        super.attachBaseContext(newBase.createConfigurationContext(config));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appliedDark = Prefs.isDarkMode(this);
        super.onCreate(savedInstanceState);
    }

    /** 主题切换后若界面仍在使用旧配色，则重建当前界面。 */
    protected boolean recreateIfThemeChanged() {
        if (Prefs.isDarkMode(this) != appliedDark) {
            recreate();
            return true;
        }
        return false;
    }
}
