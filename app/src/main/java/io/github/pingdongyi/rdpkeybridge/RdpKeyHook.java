package io.github.pingdongyi.rdpkeybridge;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
 * 2. 按配置抑制远程会话的软键盘。
 *
 * <p>配置通过 {@link SettingsProvider} 从模块 App 读取（跨进程），2 秒缓存。
 */
public final class RdpKeyHook {

    private static final String TAG = "RdpKeyBridge";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile Object sListener;
    private static volatile boolean sReceiverRegistered;
    private static volatile Context sAppContext;

    private static volatile Settings sConfig;
    private static volatile long sConfigTime;

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
    // 配置
    // ------------------------------------------------------------------

    private static Settings config() {
        long now = SystemClock.uptimeMillis();
        Settings cached = sConfig;
        if (cached != null && now - sConfigTime < 2000L) {
            return cached;
        }
        Settings cfg = Settings.defaults();
        try {
            Context ctx = sAppContext;
            if (ctx == null) {
                ctx = (Context) XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", null),
                        "currentApplication");
                if (ctx != null) {
                    sAppContext = ctx;
                }
            }
            if (ctx != null) {
                Bundle b = ctx.getContentResolver().call(SettingsProvider.URI, "get", null, null);
                if (b != null) {
                    cfg = Settings.fromBundle(b);
                }
            }
        } catch (Throwable ignored) {
        }
        sConfig = cfg;
        sConfigTime = now;
        return cfg;
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
        final boolean debug = config().debug;
        MAIN.post(() -> {
            try {
                method.invoke(listener, event.getKeyCode(), event, down);
                if (debug) {
                    XposedBridge.log(TAG + ": injected key=" + event.getKeyCode() + " down=" + down);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": inject failed: " + t);
            }
        });
    }

    // ------------------------------------------------------------------
    // 软键盘抑制
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
        hookSoftInputMode();
    }

    private static void hookForwardEditText(Class<?> fwdClass) {
        try {
            XposedHelpers.findAndHookConstructor(fwdClass, Context.class,
                    android.util.AttributeSet.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Settings cfg = config();
                            if (!(cfg.imeSuppress && cfg.imeFocus)) {
                                return;
                            }
                            try {
                                // callMethod 会向上查找父类（setShowSoftInputOnFocus 在 TextView 上）
                                XposedHelpers.callMethod(param.thisObject,
                                        "setShowSoftInputOnFocus", false);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook ForwardEditText ctor failed: " + t);
        }
    }

    /**
     * RDP 用 {@code com.microsoft.windowsapp.input.ext.ViewExtKt.a(View, boolean)}
     * 通过 WindowInsetsControllerCompat 主动 show/hide 系统输入法（boolean=true=show）。
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
                                Settings cfg = config();
                                if (cfg.imeSuppress && cfg.imeViewExt
                                        && Boolean.TRUE.equals(param.args[1])) {
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

    private static void hookInputMethodManager(final String fwdClassName) {
        XC_MethodHook suppress = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Settings cfg = config();
                if (cfg.imeSuppress && cfg.imeShowSoftInput
                        && param.args.length > 0
                        && isForwardEditText(param.args[0], fwdClassName)) {
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

    /** 全 App 级窗口 softInputMode 强制（较重，默认关闭，可在界面开启）。 */
    private static void hookSoftInputMode() {
        try {
            XposedHelpers.findAndHookMethod(Window.class, "setSoftInputMode", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Settings cfg = config();
                            if (!(cfg.imeSuppress && cfg.imeWindow)) {
                                return;
                            }
                            int mode = (Integer) param.args[0];
                            mode = (mode & ~0xF)
                                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
                            param.args[0] = mode;
                        }
                    });
            XposedHelpers.findAndHookMethod(android.app.Activity.class, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Settings cfg = config();
                            if (!(cfg.imeSuppress && cfg.imeWindow)) {
                                return;
                            }
                            try {
                                ((android.app.Activity) param.thisObject).getWindow()
                                        .setSoftInputMode(WindowManager.LayoutParams
                                                .SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook softInputMode failed: " + t);
        }
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
                        sAppContext = (Application) param.thisObject;
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
                XposedBridge.log(TAG + " [a11y] key="
                        + intent.getIntExtra(KeyRelay.EXTRA_CODE, -1)
                        + " down=" + intent.getBooleanExtra(KeyRelay.EXTRA_DOWN, false)
                        + " meta=" + intent.getIntExtra(KeyRelay.EXTRA_META, 0)
                        + " captured=" + intent.getBooleanExtra(KeyRelay.EXTRA_CAPTURED, false));
            }
        };
        // 收到任意广播时刷新一次配置
        BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                sConfigTime = 0L;
                config();
            }
        };
        IntentFilter filter = new IntentFilter(KeyRelay.ACTION);
        IntentFilter diagFilter = new IntentFilter(KeyRelay.ACTION_DIAG);
        IntentFilter settingsFilter = new IntentFilter(KeyRelay.ACTION_SETTINGS_CHANGED);
        try {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            app.registerReceiver(diagReceiver, diagFilter, Context.RECEIVER_EXPORTED);
            app.registerReceiver(settingsReceiver, settingsFilter, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            app.registerReceiver(receiver, filter);
            app.registerReceiver(diagReceiver, diagFilter);
            app.registerReceiver(settingsReceiver, settingsFilter);
        }
    }
}
