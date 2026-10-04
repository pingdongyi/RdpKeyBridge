package io.github.pingdongyi.rdpkeybridge;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

/** 配置界面：按键接管开关 + Shizuku 一键关闭系统"外接键盘弹软键盘"。 */
public class MainActivity extends Activity {

    private static final int SHIZUKU_REQUEST_CODE = 1001;
    private static final String KEY_SHOW_IME_WITH_HARD_KEYBOARD = "show_ime_with_hard_keyboard";

    private TextView mStatus;
    private TextView mKbInfo;
    private TextView mShizukuInfo;
    private CheckBox cbDebug, cbCapture, cbMeta, cbAltTab, cbAltSpecial, cbShift;

    private final Shizuku.OnRequestPermissionResultListener mShizukuResult =
            (requestCode, grantResult) -> {
                if (requestCode == SHIZUKU_REQUEST_CODE) {
                    refreshStatus();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            Shizuku.addRequestPermissionResultListener(mShizukuResult);
        } catch (Throwable ignored) {
        }

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

        root.addView(divider("外接键盘时不再弹软键盘（系统设置）"));
        mKbInfo = new TextView(this);
        root.addView(mKbInfo);

        mShizukuInfo = new TextView(this);
        root.addView(mShizukuInfo);

        Button reqShizuku = new Button(this);
        reqShizuku.setText("申请 Shizuku 权限");
        reqShizuku.setOnClickListener(v -> requestShizuku());
        root.addView(reqShizuku);

        Button disableIme = new Button(this);
        disableIme.setText("关闭外接键盘软键盘（通过 Shizuku 设为 0）");
        disableIme.setOnClickListener(v -> runShizuku(
                "settings put secure " + KEY_SHOW_IME_WITH_HARD_KEYBOARD + " 0"));
        root.addView(disableIme);

        Button openHardKb = new Button(this);
        openHardKb.setText("打开物理键盘设置（手动关闭）");
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

        cbDebug = add(root, "Debug 日志（记录每个按键）");

        TextView hint = new TextView(this);
        hint.setText(R.string.hint);
        root.addView(hint);

        setContentView(scroll);
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 自动申请 Shizuku 权限
        requestShizuku();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(mShizukuResult);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
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

    // ------------------------------------------------------------------
    // Shizuku
    // ------------------------------------------------------------------

    private void requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                return;
            }
            if (Shizuku.isPreV11()) {
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                return;
            }
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
        } catch (Throwable ignored) {
        }
    }

    private void runShizuku(String command) {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Shizuku 未运行，请先启动 Shizuku", Toast.LENGTH_LONG).show();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
                Toast.makeText(this, "请先授予 Shizuku 权限，再点一次", Toast.LENGTH_LONG).show();
                return;
            }
            Process p = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, null);
            int code = p.waitFor();
            Toast.makeText(this, code == 0 ? "执行成功" : ("执行失败 code=" + code),
                    Toast.LENGTH_SHORT).show();
            refreshStatus();
        } catch (Throwable t) {
            Toast.makeText(this, "执行失败：" + t, Toast.LENGTH_LONG).show();
        }
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
                ? " ← 开启中：外接键盘会弹软键盘，建议关掉"
                : (v == 0 ? " ← 已关闭（正常）" : " ← 读不到，请手动确认");
        mKbInfo.setText(KEY_SHOW_IME_WITH_HARD_KEYBOARD + " = " + v + note);

        String shizuku;
        try {
            if (!Shizuku.pingBinder()) {
                shizuku = "Shizuku：未运行（请先启动 Shizuku / Sui）";
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                shizuku = "Shizuku：已授权 (uid=" + Shizuku.getUid() + ")";
            } else {
                shizuku = "Shizuku：未授权，点下方按钮授权";
            }
        } catch (Throwable t) {
            shizuku = "Shizuku：不可用";
        }
        mShizukuInfo.setText(shizuku);
    }
}
