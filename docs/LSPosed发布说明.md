# LSPosed 独立包发布与迁移

## 应用身份

- 应用名：HyperPods HyperOS 4
- 应用 ID：`io.github.mo_setian.hyperpods`
- 源码：<https://github.com/Mo-SeTian/HyperPods/tree/os4-dev>
- 原始项目：<https://github.com/Art-Chen/HyperPods/tree/os3-dev>
- 构建 31：`3.0.0-AAP-W-HyperOS4`

应用 ID 与 Kotlin 命名空间用途不同。源码类、JNI 方法和 Xposed 入口保留 `moe.chenxy.hyperpods`，Yuki 注解显式使用新的 `BuildConfig.APPLICATION_ID`。设置 Provider、发送者 UID 校验、通知图片和跳转使用新应用 ID；内部广播以新应用 ID 为前缀，与旧包隔离。

## 首次安装

1. 下载独立包正式签名 APK。
2. 在 LSPosed 关闭旧 `moe.chenxy.hyperpods` 模块。可保留旧应用供恢复，但不要同时启用。
3. 安装新包并启用 HyperPods HyperOS 4，勾选 `com.android.bluetooth`、`com.xiaomi.bluetooth`、`com.android.systemui`。
4. 打开新应用，重新配置应用选项，再重启手机，让所有作用域加载新模块。

新应用不会读取旧应用的私有设置；耳机自身保存的配置仍由耳机回报。后续版本固定应用 ID 和签名，递增 `versionCode` 后覆盖安装。

## LSPosed 收录

收录申请 [#2361](https://github.com/Xposed-Modules-Repo/submission/issues/2361) 已获机器人批准，独立分发仓库为 <https://github.com/Xposed-Modules-Repo/io.github.mo_setian.hyperpods>。仓库创建不代表已被索引；需填写仓库 Description 并至少发布一个有效 APK Release。

分发仓库只放模块介绍和分发元数据，文件模板位于本项目 `distribution/lsposed/`。源码、构建与问题反馈继续留在 Mo-SeTian/HyperPods。请在 GitHub 网页设置 Description 为 `HyperPods HyperOS 4`、Website 为源码仓库地址。

Release 标题使用版本名；Tag 使用 `VersionCode-VersionName`，例如 `31-3.0.0-AAP-W-HyperOS4`，并在发布时一起上传正式签名 APK。GitHub Actions 中的构建产物不会自动成为 LSPosed 可索引的 Release。

## 正式签名与工作流

密钥库、密码、别名、证书和恢复说明仅保存在本机 `private-signing/`，该目录及常见密钥库扩展名已被 Git 忽略；不要上传这些文件。后续必须保留这套密钥，不能重新生成。

如需启用 GitHub Actions 正式打包，在源码仓库的 Settings → Secrets and variables → Actions 配置 `OS4_SIGNING_KEY`、`OS4_KEY_STORE_PASSWORD`、`OS4_ALIAS`、`OS4_KEY_PASSWORD`；完整值保存在本机对应同名文本文件中。只通过 GitHub Secrets 保存，不提交到 Git。

工作流不会复用旧包的签名 Secrets。缺少新 Secrets 时，分支提交只执行检查，不上传替代签名 APK；Tag 发布则明确失败。配置完整后校验 APK 签名与密钥库证书一致，继续采用版本名加短 MD5 的 APK 命名方式。临时密钥库在任务结束时清理。

官方规则：<https://github.com/Xposed-Modules-Repo/submission>。
