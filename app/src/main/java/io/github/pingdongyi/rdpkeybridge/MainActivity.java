package io.github.pingdongyi.rdpkeybridge;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import rikka.shizuku.Shizuku;

/** 配置界面：按键接管开关 + Shizuku（手动申请）读/改受限设置与无障碍状态。 */
public class MainActivity extends Activity {

    private static final int SHIZUKU_REQUEST_CODE = 1001;
    private static final String KEY_SHOW_IME_WITH_HARD_KEYBOARD = "show_ime_with_hard_keyboard";
    private static final String PKG = "io.github.pingdongyi.rdpkeybridge";

    private TextView mStatus;
    private TextView mKbInfo;
    private TextView mShizukuInfo;
    private TextView mOut;
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

        Button appInfo = new Button(this);
        appInfo.setText("打开「应用信息」（如需『允许受限设置』）");
        appInfo.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Throwable ignored) {
            }
        });
        root.addView(appInfo);

        TextView a11yHint = new TextView(this);
        a11yHint.setText("提示：Android 13+ 侧载应用的无障碍服务可能受「受限设置」限制。"
                + "若开启后又被自动关闭，可用下方 Shizuku 功能查看/重置受限设置。");
        root.addView(a11yHint);

        // ---------------- Shizuku（手动申请） ----------------
        root.addView(divider("Shizuku（用于查看/重置受限设置，需手动申请权限）"));

        mShizukuInfo = new TextView(this);
        root.addView(mShizukuInfo);

        Button reqShizuku = new Button(this);
        reqShizuku.setText("申请 Shizuku 权限");
        reqShizuku.setOnClickListener(v -> requestShizuku());
        root.addView(reqShizuku);

        Button getOp = new Button(this);
        getOp.setText("查看受限设置（access_restricted_settings）");
        getOp.setOnClickListener(v -> execShizuku(
                "cmd appops get " + PKG + " android:access_restricted_settings"));
        root.addView(getOp);

        Button revokeOp = new Button(this);
        revokeOp.setText("撤销受限授权（设为 default，菜单会重新出现）");
        revokeOp.setOnClickListener(v -> execShizuku(
                "cmd appops set " + PKG + " android:access_restricted_settings default"));
        root.addView(revokeOp);

        Button allowOp = new Button(this);
        allowOp.setText("允许受限设置（设为 allow）");
        allowOp.setOnClickListener(v -> execShizuku(
                "cmd appops set " + PKG + " android:access_restricted_settings allow"));
        root.addView(allowOp);

        Button resetA11y = new Button(this);
        resetA11y.setText("重置无障碍状态（清空后到设置里重新开启）");
        resetA11y.setOnClickListener(v -> execShizuku(
                "settings put secure enabled_accessibility_services null; "
                        + "settings put secure accessibility_enabled 0"));
        root.addView(resetA11y);

        mOut = new TextView(this);
        mOut.setTextIsSelectable(true);
        root.addView(mOut);

        // ---------------- 按键接管 ----------------
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

        // ---------------- 外接键盘 ----------------
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

    /** 只在用户点击时申请，不自动申请。 */
    private void requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                toast("Shizuku 未运行，请先启动 Shizuku / Sui");
                return;
            }
            if (Shizuku.isPreV11()) {
                toast("Shizuku 版本过低");
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                toast("已授权");
                return;
            }
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
        } catch (Throwable t) {
            toast("Shizuku 不可用：" + t);
        }
    }

    private void execShizuku(final String command) {
        try {
            if (!Shizuku.pingBinder()) {
                toast("Shizuku 未运行，请先启动 Shizuku / Sui");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                toast("请先点『申请 Shizuku 权限』");
                return;
            }
        } catch (Throwable t) {
            toast("Shizuku 不可用：" + t);
            return;
        }
        mOut.setText("执行中…\n$ " + command);
        new Thread(() -> {
            String out;
            try {
                out = shizukuExec(command);
            } catch (Throwable t) {
                out = "执行失败：" + t;
            }
            final String result = out;
            runOnUiThread(() -> {
                mOut.setText("$ " + command + "\n" + (result == null || result.isEmpty()
                        ? "(无输出，通常表示成功)" : result));
                refreshStatus();
            });
        }).start();
    }

    private String shizukuExec(String command) throws Exception {
        Process p = newProcess(command);
        StringBuilder sb = new StringBuilder();
        BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line;
        while ((line = out.readLine()) != null) {
            sb.append(line).append('\n');
        }
        BufferedReader err = new BufferedReader(new InputStreamReader(p.getErrorStream()));
        while ((line = err.readLine()) != null) {
            sb.append(line).append('\n');
        }
        p.waitFor();
        return sb.toString().trim();
    }

    /**
     * 以 Shizuku 身份（shell/root）执行命令。
     * {@code Shizuku.newProcess} 在 13.x 里是 private（且标记 deprecated），
     * 由于 Shizuku API 是打包进本 App 的类，可用反射调用（API 14 移除后需换 transactRemote）。
     */
    private Process newProcess(String command) throws Exception {
        java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, new String[]{"sh", "-c", command}, null, null);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
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

        String shizuku;
        try {
            if (!Shizuku.pingBinder()) {
                shizuku = "Shizuku：未运行（请先启动 Shizuku / Sui）";
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                shizuku = "Shizuku：已授权 (uid=" + Shizuku.getUid() + ")";
            } else {
                shizuku = "Shizuku：未授权，点下方『申请 Shizuku 权限』";
            }
        } catch (Throwable t) {
            shizuku = "Shizuku：不可用";
        }
        mShizukuInfo.setText(shizuku);
    }
}
