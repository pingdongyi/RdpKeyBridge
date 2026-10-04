# Xposed 入口类 (assets/xposed_init 通过反射加载)
-keep class io.github.pingdongyi.rdpkeybridge.MainHook { *; }

# 保留模块全部类，避免 R8 重命名/裁剪 Hook 回调
-keep class io.github.pingdongyi.rdpkeybridge.** { *; }

-keepclassmembers class * extends de.robv.android.xposed.XC_MethodHook {
    protected void beforeHookedMethod(...);
    protected void afterHookedMethod(...);
}
