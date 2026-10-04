package io.github.pingdongyi.rdpkeybridge;

/** 无障碍服务与目标应用进程之间的按键中转协议。 */
public final class KeyRelay {

    /** 广播 action：真正需要注入的按键。 */
    public static final String ACTION = "io.github.pingdongyi.rdpkeybridge.RELAY_KEY";
    /** 广播 action：诊断用，只记录不注入。 */
    public static final String ACTION_DIAG = "io.github.pingdongyi.rdpkeybridge.DIAG_KEY";

    /** 广播 action：配置变更通知（目标进程收到后刷新缓存）。 */
    public static final String ACTION_SETTINGS_CHANGED =
            "io.github.pingdongyi.rdpkeybridge.SETTINGS_CHANGED";

    public static final String EXTRA_EVENT = "key_event";
    public static final String EXTRA_DOWN = "is_down";
    public static final String EXTRA_CODE = "key_code";
    public static final String EXTRA_META = "key_meta";
    public static final String EXTRA_CAPTURED = "key_captured";

    /** 支持的目标应用。 */
    public static final String[] TARGETS = {
            "com.microsoft.rdc.androidx",   // Windows App / 新版 Microsoft 远程桌面
            "com.microsoft.rdc.android",    // 旧版 Microsoft Remote Desktop
            "com.microsoft.rdc.android.beta",
    };

    private KeyRelay() {
    }

    public static boolean isTarget(String pkg) {
        if (pkg == null) {
            return false;
        }
        for (String t : TARGETS) {
            if (t.equals(pkg)) {
                return true;
            }
        }
        return false;
    }
}
