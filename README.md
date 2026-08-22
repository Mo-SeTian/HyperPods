# HyperPods AAP for HyperOS 4

一个面向 Xiaomi HyperOS 的 LSPosed/Xposed 模块，让 AirPods 在小米设备上获得更完整的连接、控制与系统界面体验。

当前版本：`3.0.0-AAP-W-HyperOS4`

> 本仓库是 [Art-Chen/HyperPods](https://github.com/Art-Chen/HyperPods) 的 Fork，基于上游 `os3-dev` 分支继续适配 HyperOS 4。原项目及核心实现版权归原作者 Art_Chen 所有。
>
> Fork 仓库：[Mo-SeTian/HyperPods](https://github.com/Mo-SeTian/HyperPods)
>
> HyperOS 4 开发分支：[os4-dev](https://github.com/Mo-SeTian/HyperPods/tree/os4-dev)

## 功能

- AirPods 入耳检测
- 主动降噪、通透模式与关闭模式切换
- 左耳、右耳和充电盒的精确电量显示
- 连接提示、HyperOS 超级岛与耳机通知
- HyperOS 控制中心耳机卡片
- AirPods AAP 通信与控制

## HyperOS 4 适配与优化

本分支针对 Android 17 / HyperOS 4 完成了以下调整，并已在 HyperOS `OS4.0.0.23.XPBCNXM` 上进行实机验证：

- 适配 Android 17 新版 `BluetoothSocket` 构造结构，恢复 AirPods AAP Classic L2CAP 控制通道。
- 修复连接 AirPods 后蓝牙服务崩溃、自动关闭并重启的问题。
- 适配 OS4 的 `A2dpService.getAdapterService()`，避免旧字段 `mAdapterService` 缺失导致蓝牙进程崩溃。
- 为电量同步等非关键路径增加异常保护，防止模块异常拖垮系统蓝牙服务。
- 适配 HyperOS 4 SystemUI 插件的新 ClassLoader 结构，恢复控制中心设备卡片 Hook。
- 调整小米蓝牙扩展进程中的广播接收器注册时机，恢复连接提示和超级岛。
- 屏蔽 HyperOS 4 新增的官方 AirPods Fast Connect 处理入口，避免官方弹窗、官方超级岛与模块逻辑互相冲突。
- 移除失败后的递归 Socket 重连，避免连接失败时产生重试风暴。
- Native Hook 库按 16 KB ELF LOAD 段对齐，兼容新系统的 16 KB page size 要求。
- Release 构建启用 R8 混淆、资源压缩和不可调试标记。
- 新增 GitHub Actions：自动恢复仓库密钥、签名 Release APK、上传构件，并在推送版本标签时创建 GitHub Release。

## 已验证环境

- 设备型号：Xiaomi `2509FPN0BC`
- SoC：Qualcomm SM8850
- 系统：Android 17 / HyperOS `OS4.0.0.23.XPBCNXM`
- LSPosed
- AirPods Pro（AAP PSM `0x1001`）

其他 HyperOS 4 版本和设备可能使用不同的系统组件或混淆符号，使用前请自行评估，并准备好停用模块的恢复方式。

## 安装

1. 从本仓库的 [Actions](https://github.com/Mo-SeTian/HyperPods/actions) 或 [Releases](https://github.com/Mo-SeTian/HyperPods/releases) 下载 APK。
2. 安装 APK，并在 LSPosed 中启用 HyperPods。
3. 确认作用域包含：
   - `com.android.bluetooth`
   - `com.xiaomi.bluetooth`
   - `com.android.systemui`
4. 重启设备，使蓝牙、蓝牙扩展和系统界面进程完整加载 Hook。
5. 重新连接 AirPods，并测试超级岛、控制页面和降噪模式切换。

如出现蓝牙异常，请先在 LSPosed 中停用模块并重启设备。

## 本地构建

Debug：

```bash
./gradlew :app:assembleDebug
```

Release：

```bash
./gradlew :app:assembleRelease \
  -PKEYSTORE_FILE=/absolute/path/to/release.jks \
  -PKEYSTORE_PASSWORD='your-store-password' \
  -PKEY_ALIAS='your-key-alias' \
  -PKEY_PASSWORD='your-key-password'
```

## GitHub Actions 签名

工作流需要配置以下 GitHub Actions Secrets：

| Secret | 内容 |
| --- | --- |
| `SIGNING_KEY` | JKS 文件的单行 Base64 内容 |
| `ALIAS` | 签名密钥别名 |
| `KEY_STORE_PASSWORD` | JKS 密钥库密码 |
| `KEY_PASSWORD` | 私钥密码 |

生成 `SIGNING_KEY`：

```bash
openssl base64 -A -in release.jks
```

请永久、安全地备份 keystore 与密码。签名密钥丢失后，后续 APK 将无法覆盖升级已安装版本。不要把 keystore、Base64 私钥或密码提交到 Git 仓库。

## 上游与致谢

- [Art-Chen/HyperPods](https://github.com/Art-Chen/HyperPods)：原项目与核心 HyperOS 集成实现。
- [LibrePods](https://github.com/kavishdevar/librepods)：AAP 协议定义及相关功能实现参考。

`AAP` 是 Apple Inc. 用于与 AirPods 通信的协议。

如果你需要更多功能，或使用的不是 HyperOS，建议同时了解 [LibrePods](https://github.com/kavishdevar/librepods)。

## License

本项目沿用上游许可证，以 [GNU General Public License v3.0](LICENSE) 发布。

Original copyright (C) 2024 Art_Chen.
