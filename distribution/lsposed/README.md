# HyperPods HyperOS 4

面向小米 HyperOS 4 的 AirPods 支持模块，由 Mo-SeTian 在 [Art-Chen/HyperPods 的 os3-dev 分支](https://github.com/Art-Chen/HyperPods/tree/os3-dev)基础上持续维护。

本二改版的独立应用包名为 **`io.github.mo_setian.hyperpods`**。

## 功能

- 左右耳独立电量、在线耳机仓电量与低电量提醒。
- 根据型号显示关闭、降噪、通透、自适应聆听模式与可用设置。
- 佩戴检测、对话感知、个性化音量及按压、滑动、提示音设置。
- 小米超级岛和耳机通知、连接诊断。
- 兼容型号的来电头部手势、姿态测试及入睡暂停；需要兼容固件，效果以真机为准。

## 使用

需要小米 HyperOS 4 与可用的 LSPosed。安装后启用模块并勾选系统蓝牙 `com.android.bluetooth`、小米蓝牙扩展 `com.xiaomi.bluetooth`、系统界面 `com.android.systemui`，打开应用配置选项并重启手机。

从旧 `moe.chenxy.hyperpods` 迁移时，需要重新安装和配置应用设置；先关闭旧模块，避免重复 Hook。之后独立包始终保持同一应用 ID 和正式签名，可覆盖升级。

设备和固件支持以[源码仓库说明](https://github.com/Mo-SeTian/HyperPods/tree/os4-dev)为准；代码支持不代表所有组合均已完成实机验证。

## 来源与反馈

- 维护者：[Mo-SeTian](https://github.com/Mo-SeTian)
- 原作者：[Art-Chen](https://github.com/Art-Chen/HyperPods)
- [LibrePods](https://github.com/librepods-org/librepods)：部分协议、型号能力、图片、手势与姿态计算的实现或参考来源，保留源码归属说明。
- [Miuix](https://github.com/compose-miuix-ui/miuix)：设置界面组件。
- [问题反馈](https://github.com/Mo-SeTian/HyperPods/issues) · [支持二改维护](https://afdian.com/a/mosetian)

源码继续在 Mo-SeTian/HyperPods 的 os4-dev 分支维护，本仓库用于模块介绍和 APK 分发。遵循 GPL-3.0，原作者及引用代码的版权、许可说明保留在源码中。
