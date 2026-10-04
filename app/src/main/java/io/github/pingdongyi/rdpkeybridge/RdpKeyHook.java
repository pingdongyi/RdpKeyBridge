package io.github.pingdongyi.rdpkeybridge;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 运行在被 patch 的目标应用进程里：
 * 1. 捕获 RDP 客户端的按键转发监听器，收到无障碍服务广播后把按键注入回去；
 * 2. 阻止远程会话的隐形 EditText 自动唤起系统输入法（软键盘）。
 */
public final class RdpKeyHook {

    private static final String TAG = "RdpKeyBridge";
    private static final boolean DEBUG = false;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile Object sListener;
    private static volatile boolean sReceiverRegistered;

    private RdpKeyHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lp) {
        if (!KeyRelay.isTarget(lp.packageName)) {
            return;
        }
        try {
            KeyFinder.find(lp);
            hookSetListener(lp);
            hookIme(lp);
            hookApplication();
            XposedBridge.log(TAG + ": installed for " + lp.packageName);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": install failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 按键注入
    // ------------------------------------------------------------------

    private static void hookSetListener(XC_LoadPackage.LoadPackageParam lp) {
        final String cls = KeyFinder.forwardClassName;
        final String method = KeyFinder.setListenerMethodName;
        final Class<?> iface = KeyFinder.listenerInterface;
        if (cls == null || method == null || iface == null) {
            XposedBridge.log(TAG + ": listener target not found, skip hooking");
            return;
        }
        XposedHelpers.findAndHookMethod(cls, lp.classLoader, method, iface, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] != null) {
                    sListener = param.args[0];
                    XposedBridge.log(TAG + ": listener captured "
                            + sListener.getClass().getName());
                }
            }
        });
    }

    private static void inject(KeyEvent event, boolean down) {
        if (event == null) {
            return;
        }
        final Object listener = sListener;
        final Method method = KeyFinder.keyMethod;
        if (listener == null || method == null) {
            XposedBridge.log(TAG + ": inject skipped (listener=" + (listener != null)
                    + ", method=" + (method != null) + ")");
            return;
        }
        MAIN.post(() -> {
            try {
                method.invoke(listener, event.getKeyCode(), event, down);
                if (DEBUG) {
                    XposedBridge.log(TAG + ": injected key=" + event.getKeyCode() + " down=" + down);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": inject failed: " + t);
            }
        });
    }

    // ------------------------------------------------------------------
    // 软键盘抑制（远程会话的隐形 EditText 获得焦点时不应弹输入法）
    // ------------------------------------------------------------------

    private static void hookIme(XC_LoadPackage.LoadPackageParam lp) {
        final String fwd = KeyFinder.forwardClassName;
        if (fwd != null) {
            Class<?> fwdClass = null;
            try {
                fwdClass = XposedHelpers.findClass(fwd, lp.classLoader);
            } catch (Throwable ignored) {
            }
            if (fwdClass != null) {
                hookForwardEditText(fwdClass);
            }
        }
        hookInputMethodManager(fwd);
        hookViewExt(lp);
    }

    /**
     * RDP 用 {@code com.microsoft.windowsapp.input.ext.ViewExtKt.a(View, boolean)}
     * 通过 WindowInsetsControllerCompat 主动 show/hide 系统输入法（boolean=true=show，
     * 内部走 WindowInsetsController.show(Type.ime())）。这里在 show 时直接跳过该方法。
     */
    private static void hookViewExt(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> viewExt = XposedHelpers.findClass(
                    "com.microsoft.windowsapp.input.ext.ViewExtKt", lp.classLoader);
            for (final Method m : viewExt.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() != void.class || p.length != 2
                        || !p[0].equals(View.class) || !p[1].equals(boolean.class)) {
                    continue;
                }
                XposedHelpers.findAndHookMethod(viewExt, m.getName(), View.class, boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (Boolean.TRUE.equals(param.args[1])) {
                                    // 想显示输入法 -> 直接不执行
                                    param.setResult(null);
                                }
                            }
                        });
                XposedBridge.log(TAG + ": hooked IME toggle " + viewExt.getName() + "#" + m.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook ViewExt failed: " + t);
        }
    }

    /**
     * 强制窗口的 softInputMode 为 stateAlwaysHidden。
     * 注意：这是全 App 级改动，容易引入回归，默认关闭。
     */
    @SuppressWarnings("unused")
    private static void hookSoftInputMode() {
        try {
            XposedHelpers.findAndHookMethod(Window.class, "setSoftInputMode", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int mode = (Integer) param.args[0];
                            mode = (mode & ~0xF) | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
                            param.args[0] = mode;
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook setSoftInputMode failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity a = (Activity) param.thisObject;
                        a.getWindow().setSoftInputMode(
                                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Activity.onResume failed: " + t);
        }
    }

    private static void hookForwardEditText(Class<?> fwdClass) {
        // 1) 构造后强制关闭"聚焦即弹软键盘"
        try {
            XposedHelpers.findAndHookConstructor(fwdClass, Context.class,
                    android.util.AttributeSet.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                XposedHelpers.callMethod(param.thisObject,
                                        "setShowSoftInputOnFocus", false);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook ForwardEditText ctor failed: " + t);
        }
        // 2) App 里没有任何地方调用 setShowSoftInputOnFocus，默认 true；
        //    构造后主动置 false 即可（callMethod 会向上查找父类，能命中 TextView 的实现）
        //    注意：不能用 findAndHookMethod 直接 hook，否则会 NoSuchMethodError（声明在 TextView 上）
    }

    private static void hookInputMethodManager(final String fwdClassName) {
        XC_MethodHook suppress = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && isForwardEditText(param.args[0], fwdClassName)) {
                    param.setResult(false);
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(InputMethodManager.class, "showSoftInput",
                    View.class, int.class, suppress);
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(InputMethodManager.class, "showSoftInput",
                    View.class, int.class, android.os.ResultReceiver.class, suppress);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isForwardEditText(Object view, String fwdClassName) {
        if (!(view instanceof View) || fwdClassName == null) {
            return false;
        }
        Class<?> c = view.getClass();
        while (c != null) {
            if (c.getName().equals(fwdClassName)) {
                return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 广播接收
    // ------------------------------------------------------------------

    private static void hookApplication() {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        registerReceiver((Application) param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": registerReceiver failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Application failed: " + t);
        }
    }

    private static void registerReceiver(Application app) {
        if (sReceiverRegistered) {
            return;
        }
        sReceiverRegistered = true;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                KeyEvent event = intent.getParcelableExtra(KeyRelay.EXTRA_EVENT);
                boolean down = intent.getBooleanExtra(KeyRelay.EXTRA_DOWN,
                        event != null && event.getAction() == KeyEvent.ACTION_DOWN);
                inject(event, down);
            }
        };
        BroadcastReceiver diagReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                // diag 只会在「目标前台 + 修饰键」或 DEBUG 时发送
                XposedBridge.log(TAG + " [a11y] key="
                        + intent.getIntExtra(KeyRelay.EXTRA_CODE, -1)
                        + " down=" + intent.getBooleanExtra(KeyRelay.EXTRA_DOWN, false)
                        + " meta=" + intent.getIntExtra(KeyRelay.EXTRA_META, 0)
                        + " captured=" + intent.getBooleanExtra(KeyRelay.EXTRA_CAPTURED, false));
            }
        };
        IntentFilter filter = new IntentFilter(KeyRelay.ACTION);
        IntentFilter diagFilter = new IntentFilter(KeyRelay.ACTION_DIAG);
        try {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            app.registerReceiver(diagReceiver, diagFilter, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            app.registerReceiver(receiver, filter);
            app.registerReceiver(diagReceiver, diagFilter);
        }
    }
}
