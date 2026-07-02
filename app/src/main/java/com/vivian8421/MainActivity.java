package com.vivian8421;

import androidx.appcompat.app.AppCompatActivity;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
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
}
