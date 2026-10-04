package io.github.pingdongyi.rdpkeybridge;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 配置界面：按键接管开关。 */
public class MainActivity extends Activity {

    private static final String KEY_SHOW_IME_WITH_HARD_KEYBOARD = "show_ime_with_hard_keyboard";

    private TextView mStatus;
    private TextView mKbInfo;
    private CheckBox cbDebug, cbCapture, cbMeta, cbAltTab, cbAltSpecial, cbShift;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        mStatus = new TextView(this);
        root.addView(mStatus);

        Button a11y = new Button(this);
        a11y.setText(R.string.btn_open_a11y);
        a11y.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable ignored) {
            }
        });
        root.addView(a11y);

        Button appInfo = new Button(this);
        appInfo.setText("打开本应用信息");
        appInfo.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Throwable ignored) {
            }
        });
        root.addView(appInfo);

        root.addView(divider("按键接管"));
        cbCapture = add(root, "启用按键接管（总开关）");
        cbMeta = add(root, "接管 Win 键及其组合（如 Win+E）");
        cbAltTab = add(root, "接管 Alt+Tab");
        cbAltSpecial = add(root, "接管 Alt+Esc / Space / Enter / F4");
        cbShift = add(root, "接管 Shift（用于远端中英切换）");

        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(v -> save());
        root.addView(save);

        cbDebug = add(root, "Debug 日志（记录每个按键）");

        root.addView(divider("外接键盘弹软键盘？"));
        mKbInfo = new TextView(this);
        root.addView(mKbInfo);

        TextView imeHint = new TextView(this);
        imeHint.setText("部分设备的系统自带输入法会在接管外接键盘时弹出悬浮窗，属于系统/输入法行为，模块无法拦截。\n"
                + "解决办法：换一个第三方输入法（如 Gboard）即可不再弹悬浮窗；\n"
                + "也可在下方「物理键盘设置」里关闭「显示虚拟键盘」。");
        root.addView(imeHint);

        Button openHardKb = new Button(this);
        openHardKb.setText("打开物理键盘设置");
        openHardKb.setOnClickListener(v -> {
            try {
                startActivity(new Intent("android.settings.HARD_KEYBOARD_SETTINGS"));
            } catch (Throwable t) {
                try {
                    startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
                } catch (Throwable ignored) {
                }
            }
        });
        root.addView(openHardKb);

        TextView hint = new TextView(this);
        hint.setText(R.string.hint);
        root.addView(hint);

        setContentView(scroll);
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView divider(String text) {
        TextView tv = new TextView(this);
        tv.setText("\n" + text);
        tv.setTextSize(16f);
        return tv;
    }

    private CheckBox add(LinearLayout root, String text) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        root.addView(cb);
        return cb;
    }

    private void load() {
        Config s = Config.load(this);
        cbDebug.setChecked(s.debug);
        cbCapture.setChecked(s.captureEnabled);
        cbMeta.setChecked(s.captureMeta);
        cbAltTab.setChecked(s.captureAltTab);
        cbAltSpecial.setChecked(s.captureAltSpecial);
        cbShift.setChecked(s.captureShift);
    }

    private void save() {
        Config s = Config.defaults();
        s.debug = cbDebug.isChecked();
        s.captureEnabled = cbCapture.isChecked();
        s.captureMeta = cbMeta.isChecked();
        s.captureAltTab = cbAltTab.isChecked();
        s.captureAltSpecial = cbAltSpecial.isChecked();
        s.captureShift = cbShift.isChecked();
        s.save(this);
        for (String pkg : KeyRelay.TARGETS) {
            Intent i = new Intent(KeyRelay.ACTION_SETTINGS_CHANGED);
            i.setPackage(pkg);
            try {
                sendBroadcast(i);
            } catch (Throwable ignored) {
            }
        }
        Toast.makeText(this, "已保存（1~2 秒内生效）", Toast.LENGTH_SHORT).show();
    }

    private void refreshStatus() {
        mStatus.setText(AccessibilityKeyService.isEnabled(this)
                ? R.string.status_on : R.string.status_off);

        int v = -1;
        try {
            v = Settings.Secure.getInt(getContentResolver(),
                    KEY_SHOW_IME_WITH_HARD_KEYBOARD, -1);
        } catch (Throwable ignored) {
        }
        String note = (v == 1)
                ? "（系统开着：更容易弹软键盘）"
                : (v == 0 ? "（系统已关）" : "（读不到）");
        mKbInfo.setText(KEY_SHOW_IME_WITH_HARD_KEYBOARD + " = " + v + note);
    }
}
