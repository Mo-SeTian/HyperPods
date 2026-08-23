# HyperPods HyperOS 4 适配修改说明

## 1. 文档目的

本文档记录从上游 `Art-Chen/HyperPods` 的 `os3-dev` 分支适配到 Android 17 / HyperOS 4 的完整修改，重点解释：

- 原版本为什么在 HyperOS 4 上失效；
- 每处代码修改解决了什么问题；
- 为什么选择当前实现，而不是继续沿用 HyperOS 3 的逻辑；
- 已完成的实机验证，以及未来系统更新时需要重点复查的位置。

Fork 信息：

- 上游仓库：<https://github.com/Art-Chen/HyperPods>
- 当前 Fork：<https://github.com/Mo-SeTian/HyperPods>
- HyperOS 4 分支：`os4-dev`
- 当前版本：`3.0.0-AAP-W-HyperOS4`，`versionCode = 6`

## 2. 测试环境

本次适配和问题定位基于以下实机环境：

| 项目 | 信息 |
| --- | --- |
| 设备型号 | Xiaomi `2509FPN0BC` |
| 设备代号 | `popsicle` |
| Android | Android 17 |
| HyperOS | `OS4.0.0.23.XPBCNXM` |
| Build fingerprint | `Xiaomi/popsicle/popsicle:17/CP2A.260605.016/OS4.0.0.23.XPBCNXM:user/release-keys` |
| SoC | Qualcomm SM8850 |
| Hook 框架 | LSPosed |
| 耳机 | AirPods Pro |

分析时使用了 HyperOS 4 的以下系统组件：

- 蓝牙：`com.android.bluetooth`
- 蓝牙扩展：`com.xiaomi.bluetooth`
- 系统界面：`com.android.systemui`
- Bluetooth APEX 中的 `libbluetooth_jni.so`

## 3. 问题与修改总览

| 用户可见问题 | 根因 | 主要修改 |
| --- | --- | --- |
| AirPods 连接后蓝牙反复关闭、重启 | 旧 Socket 构造不兼容；后续又发现电量同步访问了已删除字段 | 适配 Android 17 `BluetoothSocket`；改用 `getAdapterService()`；增加异常边界 |
| 控制页无法切换降噪、通透等模式 | AAP Classic L2CAP 控制通道没有真正建立 | 反射匹配新版构造器，继续连接 PSM `0x1001` |
| 连接后没有超级岛 | 小米蓝牙扩展中的广播接收器注册依赖了不稳定的类加载/构造时机 | 在 `MiuiApplication.onCreate()` 后主动注册接收器 |
| 官方 AirPods 弹窗/超级岛与模块冲突 | HyperOS 4 新增官方 AirPods Fast Connect 处理链路 | 仅阻断官方 AirPods BLE 扫描入口，保留其他小米快连能力 |
| 系统界面控制卡片 Hook 失效 | HyperOS 4 调整了 SystemUI 插件对象和 ClassLoader 保存位置 | 同时兼容 OS4 新字段与 OS3 旧字段 |
| 系统提示 ELF 不符合 16 KB 对齐 | Native Hook 的 LOAD 段仍按 4 KB 链接 | 链接时设置 `max-page-size=16384` |
| 系统提示正在测试可调试应用 | Debug APK 的 manifest 带有 `debuggable=true` | 测试构建关闭 debuggable |
| Release 无法通过 R8 | KavaRef 引用了 Android 平台中不可用的反射类型 | 增加精确的 `-dontwarn` 规则 |
| Release 依赖本地手工签名 | 原工作流使用可选的第三方二次签名步骤 | 改为 GitHub Secrets 恢复 keystore，由 Gradle 直接签名 |

## 4. AAP L2CAP 控制通道适配

修改文件：

```text
app/src/main/java/moe/chenxy/hyperpods/pods/L2CAPController.kt
```

### 4.1 原来的问题

HyperOS 3 代码直接反射两个固定的 `BluetoothSocket` 构造器：

```kotlin
BluetoothSocket(
    type,
    auth,
    encrypt,
    device,
    port,
    uuid,
)
```

Android 17 的实际构造器在参数开头增加了 `BluetoothAdapter`，顺序变为：

```text
BluetoothSocket(
    BluetoothAdapter,
    BluetoothDevice,
    int,
    boolean,
    boolean,
    int,
    ParcelUuid,
    ...可能存在其他扩展参数
)
```

旧反射签名无法命中，AAP 通道没有建立，因此 UI 虽然能发送“切换降噪”的请求，实际并没有 Socket 可以把命令发给耳机。

### 4.2 当前实现

新代码不再假定构造器只有固定数量的参数，而是：

1. 枚举 `BluetoothSocket` 的所有声明构造器；
2. 匹配已经从实机日志确认的前七个参数类型；
3. 如果有多个候选，选择参数数量最少的一个；
4. 为 OS4 后续附加参数提供按类型生成的默认值；
5. 设置构造器可访问后创建 Socket。

关键参数含义：

```kotlin
0 -> BluetoothAdapter.getDefaultAdapter()
1 -> AirPods BluetoothDevice
2 -> 3       // TYPE_L2CAP，经典蓝牙 BR/EDR
3 -> true    // authenticated
4 -> true    // encrypted
5 -> 0x1001  // AirPods AAP PSM
6 -> AAP ParcelUuid
```

这里必须保留 `TYPE_L2CAP = 3`。Android 公共 API 中的某些 L2CAP 创建方式会走 LE 通道，而 AirPods AAP 控制需要的是经典蓝牙 PSM `0x1001`。

### 4.3 为什么不再递归重试

旧代码连接失败后会直接再次调用 `connectPod()`。这样会重复：

- 创建协程；
- 注册广播接收器；
- 启动媒体路由扫描；
- 创建 Socket 并再次连接。

当构造签名永久不匹配时，这不是“短暂网络重试”，而是无上限的失败风暴，可能拖慢或杀死蓝牙进程。

现在连接失败后会：

```kotlin
Log.e(TAG, "failed to connect to AirPods socket; stop retrying", error)
runCatching { socket.close() }
return@launch
```

也就是说，本轮连接失败会安全结束，下一次真正的耳机连接状态变化再触发新的连接流程。

## 5. 蓝牙重启崩溃修复

修改位置：`L2CAPController.setRegularBatteryLevel()`。

### 5.1 崩溃证据

AAP Socket 修通后，日志已经能够读取 AirPods 的型号、序列号和固件，随后蓝牙进程发生：

```text
java.lang.NoSuchFieldError:
com.android.bluetooth.a2dp.A2dpService#mAdapterService
```

崩溃调用链来自：

```text
onBatteryInfoReceived
  -> handleBatteryChanged
  -> setRegularBatteryLevel
  -> XposedHelpers.getObjectField(mContext, "mAdapterService")
```

因此，当时“蓝牙自动关闭重启”并不是 L2CAP 本身导致，而是通道成功后收到第一批电量数据，触发了旧字段访问。

### 5.2 OS4 变化

HyperOS 4 的 `A2dpService` 不再直接持有 `mAdapterService` 字段。该对象由新的父类结构管理，并通过 `getAdapterService()` 暴露。

### 5.3 兼容策略

当前代码优先使用 OS4 方法，失败时回退 OS3 字段：

```kotlin
val service = try {
    XposedHelpers.callMethod(mContext, "getAdapterService")
} catch (_: Throwable) {
    XposedHelpers.getObjectField(mContext, "mAdapterService")
}
```

外层再捕获 `Throwable`，原因是 Xposed 反射失败不一定只抛出 `Exception`，也可能抛出 `NoSuchFieldError` 等 `Error`。电量镜像属于增强功能，不应因为失败而终止整个 `com.android.bluetooth` 进程。

## 6. 超级岛接收器注册时机修复

修改文件：

```text
app/src/main/java/moe/chenxy/hyperpods/hook/MiBluetoothToastHook.kt
```

### 6.1 原来的注册方式

原模块在 `MiuiBluetoothNotification` 的构造 Hook 回调中注册 HyperPods 广播接收器。这个方案隐含两个条件：

1. YukiHookAPI 使用的 ClassLoader 能找到该类；
2. 模块安装并注入后，该类还会再次执行构造函数。

HyperOS 4 的蓝牙扩展包含插件化和延迟加载机制，这两个条件不再稳定。结果是 `com.xiaomi.bluetooth` 虽然被注入，但模块接收器可能没有注册，所以 `com.android.bluetooth` 发出的连接广播无人处理。

### 6.2 新的注册方式

现在 Hook 蓝牙扩展应用的生命周期入口：

```kotlin
"com.android.bluetooth.ble.MiuiApplication".toClass().method {
    name = "onCreate"
    emptyParam()
}.hook {
    after {
        registerHyperPodsReceiver(this.instance as Context)
    }
}
```

这样可以在真实 Application Context 已经可用后注册，并避免把注册动作完全绑定到某个后续业务类是否被加载。

同时保留：

- `appContext` 已可用时的立即注册；
- 原 `MiuiBluetoothNotification` 构造路径作为旧系统兼容后备；
- `receiverRegistered` 防止同一进程重复注册。

接收器处理四类模块内部事件：

| Action | 用途 |
| --- | --- |
| `chen.action.hyperpods.podconnecting` | 显示 AirPods 连接超级岛 |
| `chen.action.hyperpods.sendstrongtoast` | 显示耳机/充电盒电量超级岛 |
| `chen.action.hyperpods.updatepodsnotification` | 更新常驻耳机通知 |
| `chen.action.hyperpods.cancelpodsnotification` | 取消耳机通知 |

## 7. 屏蔽 HyperOS 4 官方 AirPods 快连

### 7.1 冲突来源

HyperOS 4 的 `Bluetooth Extension` 已新增官方 AirPods 支持，主要链路包括：

```text
Apple BLE Manufacturer Data (Company ID 76)
  -> MiuiAirPodsFastConnectHelper
  -> airpodsRepository
  -> MiuiFastConnectActivity / Controller
  -> 官方连接弹窗和超级岛
```

实机日志中的典型标签包括：

```text
MiuiAirPodsFastConnectHelper_Plugin
MiuiAirPodsFastConnectController_Plugin
```

官方链路与 HyperPods 同时识别同一副 AirPods，并同时管理弹窗、超级岛和电量状态，会造成状态竞争。实机表现是官方和模块两边都可能无法正常展示。

### 7.2 当前屏蔽点

反编译确认 `b1.d.U(ScanResult)` 是官方 AirPods BLE 扫描数据的入口。因此模块把该方法替换为空实现：

```kotlin
"b1.d".toClass().method {
    name = "U"
    param(ScanResult::class.java)
}.hook {
    replaceUnit {
        Log.v("Art_Chen", "Blocked official HyperOS AirPods fast-connect scan")
    }
}
```

选择这个位置的原因：

- 足够靠前，可避免官方仓库状态、弹窗和超级岛继续运行；
- 只针对官方 AirPods Helper；
- 不需要关闭整个 `com.xiaomi.bluetooth`；
- 不影响 HyperPods 自己的经典 L2CAP/AAP 控制；
- 不屏蔽普通小米耳机或其他设备的 Fast Connect。

注意：`b1.d` 是系统 APK 中的混淆类名。升级到新的 Bluetooth Extension 版本后，这是最需要重新反编译核对的 Hook 点之一。

## 8. SystemUI 插件 ClassLoader 适配

修改文件：

```text
app/src/main/java/moe/chenxy/hyperpods/hook/SystemUIPluginHook.kt
```

HyperOS 的控制中心组件不一定由 SystemUI 主 ClassLoader 直接加载。模块必须拿到 `miui.systemui.plugin` 的 ClassLoader，才能继续加载 `DeviceCardHook`。

HyperOS 3 使用：

```text
PluginInstance.mPluginFactory
  -> mClassLoaderFactory
  -> get()
```

HyperOS 4 使用：

```text
PluginInstance.pluginData
  -> context
  -> getClassLoader()
```

包名读取也从旧的 `getPackage()` 迁移到了 `getPackageName()`。当前实现先走 OS4 路径，再通过 `runCatching/getOrElse` 回退 OS3，避免为了支持新系统而破坏旧分支兼容性。

## 9. 16 KB Page Size 兼容

修改文件：

```text
app/src/main/cpp/CMakeLists.txt
```

HyperOS 4 的兼容性检查指出：

```text
lib/arm64-v8a/libhyperpods_hook.so: LOAD 区段未对齐
```

原库的 ELF LOAD 段对齐为 `2**12`，即 4 KB。现在给链接器增加：

```cmake
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE
    "-Wl,-z,max-page-size=16384")
```

重新构建后，arm64 库的所有 LOAD 段均为：

```text
align 2**14
```

即 16 KB，同时仍可在 4 KB page size 设备上加载。

APK 还使用以下命令验证 ZIP 内 native library 对齐：

```bash
zipalign -c -P 16 -v 4 HyperPods.apk
```

## 10. 构建与版本调整

修改文件：`app/build.gradle.kts`。

### 10.1 版本

```kotlin
versionCode = 6
versionName = "3.0.0-AAP-W-HyperOS4"
```

提升 `versionCode` 是为了允许系统把新包识别为可覆盖升级版本；新的 `versionName` 明确区分 HyperOS 3 与 HyperOS 4 构建。

### 10.2 不可调试标记

Debug 构建也设置：

```kotlin
isDebuggable = false
```

原因是 HyperOS 4 会对可调试应用显示醒目的兼容性警告。代价是 `BuildConfig.DEBUG` 同样会变为 `false`，依赖它的详细调试日志不会输出。后续若需要长期维护测试日志，建议新增独立的 `buildConfigField`，不要再依赖 `BuildConfig.DEBUG`。

### 10.3 R8 兼容

Release 首次构建时，R8 报告 KavaRef 引用：

```text
java.lang.reflect.AnnotatedType
```

Android 运行时不提供该 Java SE 类型，且这里只是可选反射能力。根据 R8 生成的最小规则，在 `app/proguard-rules.pro` 中加入：

```proguard
-dontwarn java.lang.reflect.AnnotatedType
```

该规则只压制这个明确的缺失类型，不使用宽泛的 `-ignorewarnings`，避免隐藏其他真实依赖问题。

## 11. GitHub Actions 与正式签名

修改文件：`.github/workflows/build.yml`。

工作流触发条件：

- 推送到 `os4-dev`：构建并上传签名 Release APK；
- 向 `os4-dev` 提交 Pull Request：构建 Debug APK，不读取发布密钥；
- 手动 `workflow_dispatch`：构建签名 Release APK；
- 推送 `v*` 标签：构建 APK 并创建 GitHub Release。

签名流程：

```text
GitHub Secret: SIGNING_KEY
  -> Base64 解码到 $RUNNER_TEMP
  -> 通过 -PKEYSTORE_* 参数交给 Gradle
  -> apksign 插件在 packageRelease 阶段直接签名
  -> 上传已签名 APK
```

需要的 Secrets：

| Secret | 说明 |
| --- | --- |
| `SIGNING_KEY` | JKS 文件的单行 Base64 |
| `ALIAS` | 私钥别名 |
| `KEY_STORE_PASSWORD` | 密钥库密码 |
| `KEY_PASSWORD` | 私钥密码 |

keystore 和密码不能提交进仓库，并且必须离线永久备份。丢失同一签名私钥后，新 APK 无法覆盖安装旧版本。

## 12. 适配过程与测试版本

### test3

- 目标：阻止错误 Socket 重试导致蓝牙反复异常；
- 结果：蓝牙不再闪退，但 AAP 通道仍未建立；
- 日志发现：Android 17 构造器参数顺序与初始假设不同。

### test4

- 目标：匹配实际 Android 17 Socket 构造器、增加 16 KB 对齐、调整超级岛接收器；
- 结果：AAP 通道成功建立并读出设备信息；
- 新问题：收到电量数据后访问 `mAdapterService`，导致 `NoSuchFieldError` 和蓝牙进程重启。

### test5

- 目标：改用 `getAdapterService()` 并为电量同步增加异常保护；
- 结果：蓝牙稳定，耳机控制页面和降噪切换正常。

### test6

- 目标：屏蔽 HyperOS 4 官方 AirPods Fast Connect，并固定模块超级岛注册时机；
- 实机结果：
  - 官方 AirPods 弹窗/超级岛成功阻断；
  - HyperPods 超级岛正常弹出；
  - 降噪、通透等控制功能正常；
  - 蓝牙保持稳定。

test6 的实机结果构成当前 `os4-dev` 的功能基线。

## 13. 验证方法

### 13.1 构建

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

### 13.2 APK 元数据

```bash
aapt dump badging HyperPods.apk
```

需要确认：

- `versionCode='6'`；
- `versionName='3.0.0-AAP-W-HyperOS4'`；
- 不存在 `application-debuggable`；
- 包含目标 ABI。

### 13.3 签名

```bash
apksigner verify --verbose --print-certs HyperPods.apk
```

### 13.4 16 KB 对齐

```bash
zipalign -c -P 16 -v 4 HyperPods.apk
llvm-objdump -p libhyperpods_hook.so
```

ELF LOAD 段应显示 `align 2**14`。

### 13.5 实机功能回归

每次系统或蓝牙扩展更新后，至少验证：

1. AirPods 连接不会导致蓝牙关闭或重启；
2. 能进入控制页面；
3. 降噪、通透、关闭模式可以实际切换；
4. 入耳检测正常；
5. 左右耳和充电盒电量更新；
6. 官方 AirPods 快连不再出现；
7. HyperPods 超级岛正常出现；
8. 控制中心耳机卡片正常加载；
9. 断开、重连和重启手机后功能仍正常。

## 14. 后续维护重点

HyperOS 更新后优先检查以下位置：

1. `BluetoothSocket` 构造器前缀和附加参数；
2. `A2dpService.getAdapterService()` 是否仍存在；
3. 官方 AirPods Helper 的混淆类名 `b1.d` 与扫描方法 `U(ScanResult)`；
4. `MiuiApplication` 和 `MiuiBluetoothNotification` 的包名及加载时机；
5. `PluginInstance.pluginData.context` 的 SystemUI 插件 ClassLoader 路径；
6. Bluetooth APEX 中 Native Hook 目标函数的签名；
7. APK 中所有第三方 `.so` 的 16 KB 对齐状态。

建议每次只更新一个 Hook 点，并先确认日志中的类、方法和字段真实存在，再进行实机测试。蓝牙服务属于系统关键进程，所有非关键增强路径都应设置异常边界，避免单个 Hook 失败导致整个蓝牙栈重启。
