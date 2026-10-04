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
 *
 * <p>因为吞掉了 Win(Meta) 的 down，系统不再维护 metaState，所以这里自己维护
 * Meta/Alt/Ctrl/Shift 状态，并在转发前把修饰位合成回 KeyEvent，保证组合键正确。
 */
public class AccessibilityKeyService extends AccessibilityService {

    private static final String TAG = "RdpKeyBridge/A11y";
    private static final boolean DEBUG = false;

    private volatile String mFocusedPkg = "";

    private volatile boolean mMeta;
    private volatile boolean mAlt;
    private volatile boolean mCtrl;
    private volatile boolean mShift;

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
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int keyCode = event.getKeyCode();
        updateModifiers(keyCode, down);

        boolean target = KeyRelay.isTarget(mFocusedPkg);
        if (!target) {
            return false;
        }

        boolean captured = capture(event);
        // 诊断：修饰键（Shift/Alt/Ctrl/Win）总是记录，其余键仅在 DEBUG 下记录
        if (DEBUG || isModifier(keyCode)) {
            diag(event, captured);
        }
        if (captured) {
            relay(synth(event), down);
            return true;
        }
        return false;
    }

    private static boolean isModifier(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
                return true;
            default:
                return false;
        }
    }

    private void updateModifiers(int keyCode, boolean down) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
                mMeta = down;
                break;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
                mAlt = down;
                break;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                mCtrl = down;
                break;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                mShift = down;
                break;
            default:
                break;
        }
    }

    private boolean capture(KeyEvent e) {
        // 目标组合键 或 按住 Win 时的任意键
        return KeyRelay.isTargetKey(e) || mMeta;
    }

    /** 把已跟踪的修饰键状态合成回事件，解决"吞掉 Win 后系统不再维护 metaState"的问题。 */
    private KeyEvent synth(KeyEvent e) {
        int meta = e.getMetaState();
        meta = mMeta ? (meta | KeyEvent.META_META_ON) : (meta & ~KeyEvent.META_META_ON);
        meta = mAlt ? (meta | KeyEvent.META_ALT_ON) : (meta & ~KeyEvent.META_ALT_ON);
        meta = mCtrl ? (meta | KeyEvent.META_CTRL_ON) : (meta & ~KeyEvent.META_CTRL_ON);
        meta = mShift ? (meta | KeyEvent.META_SHIFT_ON) : (meta & ~KeyEvent.META_SHIFT_ON);
        if (meta == e.getMetaState()) {
            return e;
        }
        return new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), e.getKeyCode(),
                e.getRepeatCount(), meta, e.getDeviceId(), e.getScanCode(), e.getFlags(), e.getSource());
    }

    private void relay(KeyEvent e, boolean down) {
        Intent base = new Intent(KeyRelay.ACTION);
        base.putExtra(KeyRelay.EXTRA_EVENT, e);
        base.putExtra(KeyRelay.EXTRA_DOWN, down);
        sendToTargets(base);
    }

    private void diag(KeyEvent e, boolean captured) {
        Intent base = new Intent(KeyRelay.ACTION_DIAG);
        base.putExtra(KeyRelay.EXTRA_CODE, e.getKeyCode());
        base.putExtra(KeyRelay.EXTRA_DOWN, e.getAction() == KeyEvent.ACTION_DOWN);
        base.putExtra(KeyRelay.EXTRA_META, e.getMetaState());
        base.putExtra(KeyRelay.EXTRA_CAPTURED, captured);
        sendToTargets(base);
    }

    private void sendToTargets(Intent base) {
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
