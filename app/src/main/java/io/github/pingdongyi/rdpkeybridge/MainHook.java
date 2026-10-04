package io.github.pingdongyi.rdpkeybridge;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口。LSPatch 会把本模块加载进被 patch 的目标应用（远程桌面），
 * 因此这里拿到的是目标应用的 handleLoadPackage。
 */
public class MainHook implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        RdpKeyHook.install(lpparam);
    }
}
