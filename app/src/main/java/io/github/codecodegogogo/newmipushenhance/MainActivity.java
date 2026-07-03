package io.github.codecodegogogo.newmipushenhance;

import androidx.appcompat.app.AppCompatActivity;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.Toast;

import java.io.File;

public class MainActivity extends AppCompatActivity implements CompoundButton.OnCheckedChangeListener {

    private static final String PREFS_NAME = "settings";
    private static final String ACTION_SETTINGS_CHANGED =
            "io.github.codecodegogogo.newmipushenhance.ACTION_SETTINGS_CHANGED";
    private static final String KEY_AUTO_FREEZE_ENABLED = "auto_freeze_enabled";
    private static final String KEY_FREEZE_STRATEGY = "freeze_strategy";
    private static final int FREEZE_STRATEGY_TASK_REMOVED = 0;
    private static final int FREEZE_STRATEGY_SCREEN_OFF = 1;

    private CompoundButton kgzm_sw;
    private CompoundButton autoFreezeSwitch;
    private CompoundButton freezeTaskRemovedRadio;
    private CompoundButton freezeScreenOffRadio;
    private View freezeTaskRemovedOption;
    private View freezeScreenOffOption;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        kgzm_sw = findViewById(R.id.kgzm_sw);
        kgzm_sw.setChecked(isLauncherIconHidden());
        kgzm_sw.setOnCheckedChangeListener(this);
        autoFreezeSwitch = findViewById(R.id.auto_freeze_sw);
        freezeTaskRemovedRadio = findViewById(R.id.freeze_task_removed_radio);
        freezeScreenOffRadio = findViewById(R.id.freeze_screen_off_radio);
        freezeTaskRemovedOption = findViewById(R.id.freeze_task_removed_option);
        freezeScreenOffOption = findViewById(R.id.freeze_screen_off_option);
        initFreezeStrategyOptions();
        makeSettingsReadable();
        notifyModuleSettingsChanged();

        View aboutButton = findViewById(R.id.about_btn);
        aboutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, AboutActivity.class));
            }
        });

        View rootPermissionButton = findViewById(R.id.root_permission_btn);
        rootPermissionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showRootPermissionDialog();
            }
        });

        View projectLink = findViewById(R.id.project_link_container);
        projectLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openProjectUrl();
            }
        });
    }

    private void initFreezeStrategyOptions() {
        final int strategy = getFreezeStrategy();
        boolean autoFreezeEnabled = isAutoFreezeEnabled();
        autoFreezeSwitch.setChecked(autoFreezeEnabled);
        autoFreezeSwitch.setOnCheckedChangeListener(this);
        applyFreezeStrategySelection(strategy);
        applyFreezeStrategyVisibility(autoFreezeEnabled);

        freezeTaskRemovedOption.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setFreezeStrategy(FREEZE_STRATEGY_TASK_REMOVED);
            }
        });

        freezeScreenOffOption.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setFreezeStrategy(FREEZE_STRATEGY_SCREEN_OFF);
            }
        });
    }

    private boolean isAutoFreezeEnabled() {
        return getSettingsPreferences().getBoolean(KEY_AUTO_FREEZE_ENABLED, false);
    }

    private void setAutoFreezeEnabled(boolean enabled) {
        getSettingsPreferences()
                .edit()
                .putBoolean(KEY_AUTO_FREEZE_ENABLED, enabled)
                .commit();
        makeSettingsReadable();
        notifyModuleSettingsChanged();
        applyFreezeStrategyVisibility(enabled);
    }

    private void applyFreezeStrategyVisibility(boolean enabled) {
        int visibility = enabled ? View.VISIBLE : View.GONE;
        freezeTaskRemovedOption.setVisibility(visibility);
        freezeScreenOffOption.setVisibility(visibility);
    }

    private int getFreezeStrategy() {
        return getSettingsPreferences().getInt(KEY_FREEZE_STRATEGY, FREEZE_STRATEGY_TASK_REMOVED);
    }

    private void setFreezeStrategy(int strategy) {
        getSettingsPreferences()
                .edit()
                .putInt(KEY_FREEZE_STRATEGY, strategy)
                .commit();
        makeSettingsReadable();
        notifyModuleSettingsChanged();
        applyFreezeStrategySelection(strategy);
    }

    private SharedPreferences getSettingsPreferences() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    private void notifyModuleSettingsChanged() {
        Intent intent = new Intent(ACTION_SETTINGS_CHANGED);
        intent.putExtra(KEY_AUTO_FREEZE_ENABLED, isAutoFreezeEnabled());
        intent.putExtra(KEY_FREEZE_STRATEGY, getFreezeStrategy());
        sendBroadcast(intent);
    }

    private void makeSettingsReadable() {
        try {
            File dataDir = new File(getApplicationInfo().dataDir);
            File sharedPrefsDir = new File(dataDir, "shared_prefs");
            File settingsFile = new File(sharedPrefsDir, PREFS_NAME + ".xml");
            dataDir.setReadable(true, false);
            dataDir.setExecutable(true, false);
            sharedPrefsDir.setReadable(true, false);
            sharedPrefsDir.setExecutable(true, false);
            if (settingsFile.exists()) {
                settingsFile.setReadable(true, false);
            }
        } catch (Throwable ignored) {
        }
    }

    private void applyFreezeStrategySelection(int strategy) {
        boolean taskRemovedSelected = strategy == FREEZE_STRATEGY_TASK_REMOVED;
        freezeTaskRemovedRadio.setChecked(taskRemovedSelected);
        freezeScreenOffRadio.setChecked(!taskRemovedSelected);
        freezeTaskRemovedOption.setBackgroundResource(taskRemovedSelected
                ? R.drawable.bg_strategy_option_selected
                : R.drawable.bg_strategy_option);
        freezeScreenOffOption.setBackgroundResource(taskRemovedSelected
                ? R.drawable.bg_strategy_option
                : R.drawable.bg_strategy_option_selected);
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        switch (buttonView.getId()){
            case R.id.kgzm_sw:
                showLauncherIcon(!isChecked);
                break;
            case R.id.auto_freeze_sw:
                setAutoFreezeEnabled(isChecked);
                break;
        }
    }

    public void showLauncherIcon(boolean isShow){
        PackageManager packageManager = this.getPackageManager();
        int show = isShow ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        packageManager.setComponentEnabledSetting(getAliseComponentName(), show, PackageManager.DONT_KILL_APP);
    }

    private boolean isLauncherIconHidden() {
        int state = getPackageManager().getComponentEnabledSetting(getAliseComponentName());
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
    }

    private ComponentName getAliseComponentName(){
        return new ComponentName(
                MainActivity.this,
                "io.github.codecodegogogo.newmipushenhance.MainActivityAlias");
    }

    private void openProjectUrl() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.project_url)));
        startActivity(intent);
    }

    private void showRootPermissionDialog() {
        final Dialog dialog = createBottomDialog(R.layout.dialog_root_permission);
        View cancelButton = dialog.findViewById(R.id.dialog_cancel_btn);
        View confirmButton = dialog.findViewById(R.id.dialog_confirm_btn);

        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
                showRootToast(false);
            }
        });
        confirmButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
                requestRootPermission();
            }
        });
        showBottomDialog(dialog);
    }

    private void requestRootPermission() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean granted = false;
                try {
                    Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
                    granted = process.waitFor() == 0;
                } catch (Throwable ignored) {
                }
                final boolean rootGranted = granted;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showRootToast(rootGranted);
                    }
                });
            }
        }).start();
    }

    private void showRootToast(boolean granted) {
        Toast.makeText(
                this,
                granted ? R.string.root_permission_success : R.string.root_permission_missing,
                Toast.LENGTH_SHORT)
                .show();
    }

    private Dialog createBottomDialog(int layoutResId) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(layoutResId);
        dialog.setCanceledOnTouchOutside(true);
        return dialog;
    }

    private void showBottomDialog(Dialog dialog) {
        dialog.show();
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }

        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        WindowManager.LayoutParams params = window.getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.BOTTOM;
        params.dimAmount = 0.35f;
        window.setAttributes(params);
    }
}
