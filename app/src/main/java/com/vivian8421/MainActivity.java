package com.vivian8421;

import androidx.appcompat.app.AppCompatActivity;

import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.vivian8421.mipushEnhance.R;

public class MainActivity extends AppCompatActivity implements CompoundButton.OnCheckedChangeListener {

    private CompoundButton kgzm_sw;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        kgzm_sw = findViewById(R.id.kgzm_sw);
        kgzm_sw.setChecked(isLauncherIconHidden());
        kgzm_sw.setOnCheckedChangeListener(this);

        View aboutButton = findViewById(R.id.about_btn);
        aboutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, AboutActivity.class));
            }
        });

        View rebootButton = findViewById(R.id.reboot_btn);
        rebootButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showRebootConfirmDialog();
            }
        });
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        switch (buttonView.getId()){
            case R.id.kgzm_sw:
                showLauncherIcon(!isChecked);
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
        return new ComponentName(MainActivity.this, "com.vivian8421.MainActivityAlias");
    }

    private void showRebootConfirmDialog() {
        new MaterialAlertDialogBuilder(this)
                .setCustomTitle(createRebootConfirmTitle())
                .setMessage(R.string.reboot_confirm_message)
                .setPositiveButton(R.string.dialog_yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        rebootPhone();
                    }
                })
                .setNegativeButton(R.string.dialog_no, null)
                .show();
    }

    private TextView createRebootConfirmTitle() {
        TextView titleView = new TextView(this);
        titleView.setText(R.string.reboot_confirm_title);
        titleView.setTextColor(getResources().getColor(R.color.settings_text_primary));
        titleView.setTextSize(22);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setPadding(dp(24), dp(20), dp(24), dp(4));
        return titleView;
    }

    private void rebootPhone() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "reboot"});
                    int exitCode = process.waitFor();
                    if (exitCode != 0) {
                        showRebootFailedDialog();
                    }
                } catch (Exception e) {
                    showRebootFailedDialog();
                }
            }
        }).start();
    }

    private void showRebootFailedDialog() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                new MaterialAlertDialogBuilder(MainActivity.this)
                        .setTitle(R.string.reboot_failed_title)
                        .setMessage(R.string.reboot_failed_message)
                        .setPositiveButton(R.string.dialog_ok, null)
                        .show();
            }
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
