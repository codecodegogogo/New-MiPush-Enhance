package com.vivian8421;

import androidx.appcompat.app.AppCompatActivity;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CompoundButton;

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
        final Dialog dialog = createBottomDialog(R.layout.dialog_reboot_confirm);
        View cancelButton = dialog.findViewById(R.id.dialog_cancel_btn);
        View confirmButton = dialog.findViewById(R.id.dialog_confirm_btn);

        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
            }
        });
        confirmButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
                rebootPhone();
            }
        });
        showBottomDialog(dialog);
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
                final Dialog dialog = createBottomDialog(R.layout.dialog_reboot_failed);
                View okButton = dialog.findViewById(R.id.dialog_ok_btn);
                okButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        dialog.dismiss();
                    }
                });
                showBottomDialog(dialog);
            }
        });
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
