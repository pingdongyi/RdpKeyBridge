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

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 运行在被 patch 的目标应用进程里：捕获 RDP 客户端的按键转发监听器，
 * 收到无障碍服务广播后把按键注入回去。
 *
 * <p>配置通过 {@link SettingsProvider} 从模块 App 读取（跨进程），2 秒缓存。
 */
public final class RdpKeyHook {

    private static final String TAG = "RdpKeyBridge";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile Object sListener;
    private static volatile boolean sReceiverRegistered;
    private static volatile Context sAppContext;

    private static volatile Config sConfig;
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
            hookApplication();
            XposedBridge.log(TAG + ": installed for " + lp.packageName);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": install failed: " + t);
        }
    }

    private static Config config() {
        long now = SystemClock.uptimeMillis();
        Config cached = sConfig;
        if (cached != null && now - sConfigTime < 2000L) {
            return cached;
        }
        Config cfg = Config.defaults();
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
                    cfg = Config.fromBundle(b);
                }
            }
        } catch (Throwable ignored) {
        }
        sConfig = cfg;
        sConfigTime = now;
        return cfg;
    }

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
