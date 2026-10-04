package io.github.pingdongyi.rdpkeybridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

/** 模块配置。模块 App 直接读 SharedPreferences；目标进程通过 SettingsProvider 读取。 */
public final class Config {

    public static final String PREFS = "settings";

    // 开关 key
    public static final String K_DEBUG = "debug";
    public static final String K_CAPTURE = "capture_enabled";
    public static final String K_META = "capture_meta";
    public static final String K_ALT_TAB = "capture_alt_tab";
    public static final String K_ALT_SPECIAL = "capture_alt_special";
    public static final String K_SHIFT = "capture_shift";

    public boolean debug;
    public boolean captureEnabled;
    public boolean captureMeta;
    public boolean captureAltTab;
    public boolean captureAltSpecial;
    public boolean captureShift;

    public static Config defaults() {
        Config s = new Config();
        s.debug = false;
        s.captureEnabled = true;
        s.captureMeta = true;
        s.captureAltTab = true;
        s.captureAltSpecial = true;
        s.captureShift = true;
        return s;
    }

    public static Config load(Context ctx) {
        Config d = defaults();
        if (ctx == null) {
            return d;
        }
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        d.debug = sp.getBoolean(K_DEBUG, d.debug);
        d.captureEnabled = sp.getBoolean(K_CAPTURE, d.captureEnabled);
        d.captureMeta = sp.getBoolean(K_META, d.captureMeta);
        d.captureAltTab = sp.getBoolean(K_ALT_TAB, d.captureAltTab);
        d.captureAltSpecial = sp.getBoolean(K_ALT_SPECIAL, d.captureAltSpecial);
        d.captureShift = sp.getBoolean(K_SHIFT, d.captureShift);
        return d;
    }

    public void save(Context ctx) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putBoolean(K_DEBUG, debug);
        e.putBoolean(K_CAPTURE, captureEnabled);
        e.putBoolean(K_META, captureMeta);
        e.putBoolean(K_ALT_TAB, captureAltTab);
        e.putBoolean(K_ALT_SPECIAL, captureAltSpecial);
        e.putBoolean(K_SHIFT, captureShift);
        e.apply();
    }

    public Bundle toBundle() {
        Bundle b = new Bundle();
        b.putBoolean(K_DEBUG, debug);
        b.putBoolean(K_CAPTURE, captureEnabled);
        b.putBoolean(K_META, captureMeta);
        b.putBoolean(K_ALT_TAB, captureAltTab);
        b.putBoolean(K_ALT_SPECIAL, captureAltSpecial);
        b.putBoolean(K_SHIFT, captureShift);
        return b;
    }

    public static Config fromBundle(Bundle b) {
        Config d = defaults();
        if (b == null) {
            return d;
        }
        d.debug = b.getBoolean(K_DEBUG, d.debug);
        d.captureEnabled = b.getBoolean(K_CAPTURE, d.captureEnabled);
        d.captureMeta = b.getBoolean(K_META, d.captureMeta);
        d.captureAltTab = b.getBoolean(K_ALT_TAB, d.captureAltTab);
        d.captureAltSpecial = b.getBoolean(K_ALT_SPECIAL, d.captureAltSpecial);
        d.captureShift = b.getBoolean(K_SHIFT, d.captureShift);
        return d;
    }
}
