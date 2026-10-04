package io.github.pingdongyi.rdpkeybridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

/** 极简界面：只用来引导开启无障碍服务。 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Button btn = findViewById(R.id.btn_a11y);
        btn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable ignored) {
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        TextView status = findViewById(R.id.status);
        status.setText(AccessibilityKeyService.isEnabled(this)
                ? R.string.status_on : R.string.status_off);
    }
}
