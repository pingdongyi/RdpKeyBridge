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
 * 返回 true 表示消费该按键，我们借此阻止系统把 Win / Alt+Tab 变成"最近任务"，
 * 并把原始 KeyEvent 广播给被 patch 的目标应用进程。
 *
 * <p>吞掉 Win 后系统不再维护 metaState，所以这里自己维护修饰键状态并在转发前合成回事件。
 * 具体接管哪些键由 {@link Settings} 动态控制（见模块 App 界面）。
 */
public class AccessibilityKeyService extends AccessibilityService {

    private static final String TAG = "RdpKeyBridge/A11y";

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

        if (!KeyRelay.isTarget(mFocusedPkg)) {
            return false;
        }

        Settings cfg = Settings.load(this);
        boolean captured = capture(event, cfg);
        if (cfg.debug || isModifier(keyCode)) {
            diag(event, captured);
        }
        if (captured) {
            relay(synth(event), down);
            return true;
        }
        return false;
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

    private boolean capture(KeyEvent e, Settings cfg) {
        if (!cfg.captureEnabled) {
            return false;
        }
        int kc = e.getKeyCode();
        int meta = e.getMetaState();

        if (cfg.captureMeta) {
            if (kc == KeyEvent.KEYCODE_META_LEFT || kc == KeyEvent.KEYCODE_META_RIGHT) {
                return true;
            }
            if ((meta & KeyEvent.META_META_ON) != 0) {
                return true;
            }
            if (mMeta) {
                return true;
            }
        }
        if (cfg.captureShift
                && (kc == KeyEvent.KEYCODE_SHIFT_LEFT || kc == KeyEvent.KEYCODE_SHIFT_RIGHT)) {
            return true;
        }
        if ((meta & KeyEvent.META_ALT_ON) != 0) {
            if (cfg.captureAltTab && kc == KeyEvent.KEYCODE_TAB) {
                return true;
            }
            if (cfg.captureAltSpecial) {
                switch (kc) {
                    case KeyEvent.KEYCODE_ESCAPE:
                    case KeyEvent.KEYCODE_SPACE:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_F4:
                        return true;
                    default:
                        break;
                }
            }
        }
        return false;
    }

    /** 把已跟踪的修饰键状态合成回事件。 */
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
