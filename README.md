<div align="center">

# RdpKeyBridge

**不用 Root，让远程桌面（Windows App / Microsoft 远程桌面）用上完整的 Win / Alt+Tab 等快捷键**

</div>

---

## ✨ 功能

在 Android 上连接远程桌面时，系统会先一步吃掉一批「全局快捷键」，导致它们根本传不到远端 Windows：

| 按键 | Android 系统默认行为 |
| ---- | -------------------- |
| 单独按 `Win`(⊞ / Meta) | 打开最近任务 |
| `Alt + Tab` | 打开最近任务 |
| `Win + Space` / `Alt + Shift` | 切换输入法 / 键盘布局 |
| `Home` / `AppSwitch` | 回桌面 / 最近任务 |

本模块用 **LSPatch（免 Root 的 Xposed）** 把 Hook 注入到目标 App 进程，同时用一个**无障碍服务**在系统层把
这些按键「截胡」并转发进 RDP 客户端，从而让远端 Windows 收到完整的组合键。

- ✅ **免 Root**：LSPatch patch 目标 App 即可，不碰系统进程、没有开机风险
- ✅ 只对远程桌面类应用生效，不影响其它应用
- ✅ 顺手解决**远程时系统输入法（软键盘）被自动唤起**的问题

## 📱 支持的应用

- `com.microsoft.rdc.androidx`（Windows App / 新版 Microsoft 远程桌面）
- `com.microsoft.rdc.android`（旧版 Microsoft Remote Desktop）
- `com.microsoft.rdc.android.beta`

## ⚠️ 环境要求

- **Android 14 (API 34) 或更高**：`AccessibilityService.onKeyEvent` 从 Android 14 起才返回 `boolean`，
  才能「消费」按键；低于 14 只能观察、不能拦截，本方案不生效。
- 已安装 [LSPatch](https://github.com/JingMatrix/LSPatch)（免 Root Xposed）。

## 🚀 使用步骤

1. **安装模块**：安装本 APK，打开 → 点「打开无障碍设置」→ 在「已下载的应用」里开启 **RDP 键盘桥**。
2. **Patch 目标 App**：用 LSPatch 选择「Windows App / Microsoft 远程桌面」，勾选本模块后 patch：
   - **Integrated mode**：模块烤进 APK，产物自包含；
   - **Manager mode**：模块改动无需重新 patch，适合后续更新。
3. **安装 patch 版**：`patch 版会重新签名`，需要先卸载原版再安装。
4. 打开远程桌面，外接键盘即可使用 `Win`、`Win+Tab`、`Alt+Tab`、`Win+E` 等。

## 🔧 实现原理

```
┌──────────────────────────┐  广播(带 KeyEvent)  ┌────────────────────────────────────┐
│ RdpKeyBridge (模块 App)   │ ──────────────────► │ 目标 App (LSPatch 注入)             │
│ AccessibilityKeyService   │                     │ RdpKeyHook                          │
│ · Win/Alt+Tab 等返回 true │                     │ · Hook ForwardEditText.             │
│   消费，阻止系统最近任务   │                     │   setKeyInputListener → 拿 listener │
│ · 维护修饰键状态并合成      │                     │ · 主线程调用 IOnKeyInputListener.e(...)│
│   metaState 后转发         │                     │ · setShowSoftInputOnFocus(false)    │
└──────────────────────────┘                     │   抑制远程会话弹出软键盘             │
                                                 └────────────────────────────────────┘
```

- **注入点定位**：`KeyFinder` 优先用可读类名反射匹配，失败时用
  [DexKit](https://github.com/LuckyPray/DexKit) 按方法签名结构查找，抗混淆/抗版本变动。
- **修饰键**：因为吞掉了 `Win` 的 Shift/Meta down，系统不再维护 metaState，所以无障碍服务自己维护
  `Meta/Alt/Ctrl/Shift` 状态，转发前用 `new KeyEvent(...)` 合成回事件，保证组合键正确。

## ⌨️ 外接键盘时不再弹软键盘

Android 有个系统安全设置 **`show_ime_with_hard_keyboard`**：它为 `1` 时，系统会在外接键盘、
又有可编辑控件聚焦时**自动弹出虚拟键盘**。该判断发生在 **system_server**，App 端 Hook 拦不住。

模块 App 里提供了：
- 显示该设置当前值
- 通过 **Shizuku** 一键设为 `0`（打开 App 会自动申请 Shizuku 权限）
- 跳转「物理键盘设置」手动关闭

也可以直接用 ADB：

```bash
adb shell settings put secure show_ime_with_hard_keyboard 0
```

## 🏗️ 构建

```bash
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。

仓库自带 GitHub Actions：push `main` 编译并上传 Artifact；push `v*` 标签自动创建 Release 附带 APK。

## ⚠️ 已知限制

- 需要 Android 14+（见上）。
- LSPatch 会重签名目标 App：必须卸载原版；若目标 App 登录了工作/学校账号（Intune 托管），
  重签名可能触发完整性校验，请先在非托管账号上验证。
- 注入依赖 RDP 客户端内部的 `ForwardEditText` / `IOnKeyInputListener`，已用 DexKit 兜底，但仍可能随
  官方大改而失效。

## 📄 致谢

- [LSPatch](https://github.com/JingMatrix/LSPatch) — 免 Root 的 Xposed 框架
- [DexKit](https://github.com/LuckyPray/DexKit) — 运行时 dex 查询
- [Shizuku](https://github.com/RikkaApps/Shizuku) — 以 shell/root 身份执行系统设置

## 🛡️ 免责声明

本模块仅供学习与技术研究使用，请勿用于任何违反法律法规的用途。作者不对使用本模块造成的任何后果承担责任。
