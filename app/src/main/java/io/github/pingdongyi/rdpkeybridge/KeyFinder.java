package io.github.pingdongyi.rdpkeybridge;

import android.view.KeyEvent;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

/**
 * 定位 RDP 客户端的按键转发入口（解决混淆/版本变动带来的脆弱性）。
 *
 * <p>优先用可读的类名做反射匹配；若类名被混淆，则用 DexKit 按方法签名结构查找：
 * 先找到接口方法 {@code (int, KeyEvent, boolean) -> boolean}（即 IOnKeyInputListener.e），
 * 再找到"以该接口为参数"的方法（即 ForwardEditText.setKeyInputListener）。
 */
public final class KeyFinder {

    private static final String TAG = "RdpKeyBridge";

    public static volatile String forwardClassName;
    public static volatile String setListenerMethodName;
    public static volatile Class<?> listenerInterface;
    /** e(int, KeyEvent, boolean) -> boolean */
    public static volatile Method keyMethod;
    /** l(KeyEvent) -> boolean */
    public static volatile Method preImeMethod;

    static {
        try {
            System.loadLibrary("dexkit");
        } catch (Throwable ignored) {
        }
    }

    private KeyFinder() {
    }

    public static void find(XC_LoadPackage.LoadPackageParam lp) {
        if (tryReflection(lp.classLoader)) {
            return;
        }
        tryDexKit(lp);
    }

    private static boolean tryReflection(ClassLoader cl) {
        try {
            Class<?> iface = Class.forName(
                    "com.microsoft.a3rdc.desktop.view.input.IOnKeyInputListener", false, cl);
            Method key = matchMethod(iface, boolean.class, int.class, KeyEvent.class, boolean.class);
            Method pre = matchMethod(iface, boolean.class, KeyEvent.class);
            Class<?> fwd = Class.forName(
                    "com.microsoft.a3rdc.desktop.view.ForwardEditText", false, cl);
            Method setter = null;
            for (Method m : fwd.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0].equals(iface)) {
                    setter = m;
                    break;
                }
            }
            if (key != null && setter != null) {
                apply(iface, key, pre, fwd.getName(), setter.getName());
                log("reflection: " + fwd.getName() + "#" + setter.getName()
                        + " key=" + key.getName());
                return true;
            }
        } catch (Throwable t) {
            log("reflection miss: " + t);
        }
        return false;
    }

    private static void tryDexKit(XC_LoadPackage.LoadPackageParam lp) {
        String apk = lp.appInfo != null ? lp.appInfo.sourceDir : null;
        if (apk == null) {
            return;
        }
        try (DexKitBridge bridge = DexKitBridge.create(apk)) {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .searchPackages("com.microsoft.a3rdc")
                    .matcher(MethodMatcher.create().returnType("boolean").paramCount(3)));

            String ifaceName = null;
            for (MethodData md : methods) {
                List<String> p = md.getParamTypeNames();
                if (p != null && p.size() == 3
                        && "int".equals(p.get(0))
                        && "android.view.KeyEvent".equals(p.get(1))
                        && "boolean".equals(p.get(2))) {
                    ifaceName = md.getClassName();
                    break;
                }
            }
            if (ifaceName == null) {
                log("dexkit: listener interface not found");
                return;
            }

            Class<?> iface = Class.forName(ifaceName, false, lp.classLoader);
            Method key = matchMethod(iface, boolean.class, int.class, KeyEvent.class, boolean.class);
            if (key == null) {
                log("dexkit: key method not found");
                return;
            }

            List<MethodData> setters = bridge.findMethod(FindMethod.create()
                    .searchPackages("com.microsoft.a3rdc")
                    .matcher(MethodMatcher.create().paramCount(1)));
            String fwd = null;
            String setter = null;
            for (MethodData md : setters) {
                List<String> p = md.getParamTypeNames();
                if (p != null && p.size() == 1 && ifaceName.equals(p.get(0))) {
                    fwd = md.getClassName();
                    setter = md.getName();
                    break;
                }
            }
            if (fwd == null || setter == null) {
                log("dexkit: setKeyInputListener not found");
                return;
            }
            apply(iface, key, matchMethod(iface, boolean.class, KeyEvent.class), fwd, setter);
            log("dexkit: " + fwd + "#" + setter + " key=" + key.getName());
        } catch (Throwable t) {
            log("dexkit failed: " + t);
        }
    }

    private static void apply(Class<?> iface, Method key, Method pre, String fwd, String setter) {
        listenerInterface = iface;
        keyMethod = key;
        preImeMethod = pre;
        forwardClassName = fwd;
        setListenerMethodName = setter;
    }

    private static Method matchMethod(Class<?> cls, Class<?> ret, Class<?>... params) {
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getReturnType().equals(ret)) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p.length != params.length) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < p.length; i++) {
                if (!p[i].equals(params[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return m;
            }
        }
        return null;
    }

    private static void log(String s) {
        XposedBridge.log(TAG + " [finder] " + s);
    }
}
