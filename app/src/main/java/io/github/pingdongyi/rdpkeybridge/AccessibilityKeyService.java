package io.github.pingdongyi.rdpkeybridge;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍按键过滤服务。
 *
 * <p>Android 14 起 {@link AccessibilityService#onKeyEvent(KeyEvent)} 返回 boolean，
 * 返回 true 表示消费该按键（不再下发给系统），我们借此阻止系统把
 * Win / Alt+Tab 变成"最近任务"，并把原始 KeyEvent 广播给被 patch 的目标应用进程。
 */
public class AccessibilityKeyService extends AccessibilityService {

    private static final String TAG = "RdpKeyBridge/A11y";

    private volatile String mFocusedPkg = "";
    private volatile boolean mMetaDown = false;

    /** 判断本无障碍服务是否已在系统设置中被开启。 */
    public static boolean isEnabled(Context ctx) {
        try {
            String enabled = Settings.Secure.getString(
                    ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabled)) {
                return false;
            }
            ComponentName me = new ComponentName(ctx, AccessibilityKeyService.class);
            String flat = me.flattenToString();
            String shortFlat = me.flattenToShortString();
            for (String s : enabled.split(":")) {
                if (s.equalsIgnoreCase(flat) || s.equalsIgnoreCase(shortFlat)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg != null) {
            mFocusedPkg = pkg.toString();
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event == null) {
            return false;
        }
        int kc = event.getKeyCode();
        if (kc == KeyEvent.KEYCODE_META_LEFT || kc == KeyEvent.KEYCODE_META_RIGHT) {
            mMetaDown = event.getAction() == KeyEvent.ACTION_DOWN;
        }

        if (!KeyRelay.isTarget(mFocusedPkg)) {
            return false;
        }
        if (!capture(event)) {
            return false;
        }
        relay(event);
        return true;
    }

    private boolean capture(KeyEvent e) {
        // 目标组合键 或 按住 Win 时的任意键
        return KeyRelay.isTargetKey(e) || mMetaDown;
    }

    private void relay(KeyEvent e) {
        Intent base = new Intent(KeyRelay.ACTION);
        base.putExtra(KeyRelay.EXTRA_EVENT, e);
        base.putExtra(KeyRelay.EXTRA_DOWN, e.getAction() == KeyEvent.ACTION_DOWN);
        for (String pkg : KeyRelay.TARGETS) {
            Intent i = new Intent(base);
            i.setPackage(pkg);
            try {
                sendBroadcast(i);
            } catch (Throwable ignored) {
            }
        }
    }
}
