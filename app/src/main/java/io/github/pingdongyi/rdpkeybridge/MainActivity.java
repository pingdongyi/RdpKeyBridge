package io.github.pingdongyi.rdpkeybridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 配置界面：实时开关，改完保存即可，无需重新发版/patch。 */
public class MainActivity extends Activity {

    private TextView mStatus;
    private TextView mKbInfo;
    private CheckBox cbDebug, cbCapture, cbMeta, cbAltTab, cbAltSpecial, cbShift;
    private CheckBox cbIme, cbImeViewExt, cbImeShow, cbImeFocus, cbImeWindow;

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

        // ---- 系统级：外接键盘时是否显示虚拟键盘 ----
        mKbInfo = new TextView(this);
        root.addView(mKbInfo);

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

        Button setKb = new Button(this);
        setKb.setText("尝试设为 0（需 ADB 授权 WRITE_SECURE_SETTINGS）");
        setKb.setOnClickListener(v -> {
            try {
                Settings.Secure.putInt(getContentResolver(),
                        "show_ime_with_hard_keyboard", 0);
                Toast.makeText(this, "已设为 0", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(this, "失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
            }
            refreshStatus();
        });
        root.addView(setKb);

        cbCapture = add(root, "启用按键接管（总开关）");
        cbMeta = add(root, "接管 Win 键及其组合（如 Win+E）");
        cbAltTab = add(root, "接管 Alt+Tab");
        cbAltSpecial = add(root, "接管 Alt+Esc / Space / Enter / F4");
        cbShift = add(root, "接管 Shift（用于远端中英切换）");

        cbIme = add(root, "抑制远程会话软键盘（总开关）");
        cbImeViewExt = add(root, "  RDP 主动弹键盘（ViewExt）");
        cbImeShow = add(root, "  showSoftInput");
        cbImeFocus = add(root, "  ForwardEditText 聚焦弹键盘");
        cbImeWindow = add(root, "  强制窗口 stateAlwaysHidden（较重）");

        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(v -> save());
        root.addView(save);

        cbDebug = add(root, "Debug 日志（记录每个按键）");

        TextView hint = new TextView(this);
        hint.setText(R.string.hint);
        root.addView(hint);

        setContentView(scroll);
        load();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
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
        cbIme.setChecked(s.imeSuppress);
        cbImeViewExt.setChecked(s.imeViewExt);
        cbImeShow.setChecked(s.imeShowSoftInput);
        cbImeFocus.setChecked(s.imeFocus);
        cbImeWindow.setChecked(s.imeWindow);
    }

    private void save() {
        Config s = Config.defaults();
        s.debug = cbDebug.isChecked();
        s.captureEnabled = cbCapture.isChecked();
        s.captureMeta = cbMeta.isChecked();
        s.captureAltTab = cbAltTab.isChecked();
        s.captureAltSpecial = cbAltSpecial.isChecked();
        s.captureShift = cbShift.isChecked();
        s.imeSuppress = cbIme.isChecked();
        s.imeViewExt = cbImeViewExt.isChecked();
        s.imeShowSoftInput = cbImeShow.isChecked();
        s.imeFocus = cbImeFocus.isChecked();
        s.imeWindow = cbImeWindow.isChecked();
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
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        mStatus.setText(AccessibilityKeyService.isEnabled(this)
                ? R.string.status_on : R.string.status_off);
        if (mKbInfo != null) {
            int v = -1;
            try {
                v = Settings.Secure.getInt(getContentResolver(),
                        "show_ime_with_hard_keyboard", -1);
            } catch (Throwable ignored) {
            }
            String note = (v == 1)
                    ? " ← 开启中：外接键盘会弹软键盘，建议关掉"
                    : (v == 0 ? " ← 已关闭（正常）" : " ← 读不到，请手动确认");
            mKbInfo.setText("show_ime_with_hard_keyboard = " + v + note);
        }
    }
}
