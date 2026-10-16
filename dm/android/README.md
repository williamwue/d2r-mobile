# Android 适配实验

本阶段提供 Android 应用壳、设备能力报告和离线资源导入器。**尚不能启动《暗黑破坏神 II：重制版》。** ARM64 游戏执行、HLE 服务和 Vulkan 游戏渲染仍待实现。

已执行的检查及未验证范围见 [首阶段验证记录](docs/verification.md)。

## 构建

工具链固定在 [toolchain.lock.json](toolchain.lock.json)。需要 JDK 21、Android SDK API 37、NDK 28.2.13676358 和 CMake 3.22.1。为 SDK 设置 `ANDROID_HOME`，为 JDK 设置 `JAVA_HOME`。Windows PowerShell 示例：

```powershell
$env:JAVA_HOME = 'G:\Dev\SDKs\jdk-21'
$env:ANDROID_HOME = 'G:\Dev\SDKs\Android'
Set-Location G:\Projects\d2r-mobile\dm\android
.\gradlew.bat :app:assembleGameDebug :app:assembleFixtureDebug
```

`game` 的包名是 `org.d2rm.android`。`fixture` 的包名是 `org.d2rm.android.fixture`，界面标注 TEST。两者数据隔离。正式契约在 debug 和 release 构建中都拒绝测试资源。APK 同时包含 `arm64-v8a` 和 `x86_64` 的能力检测库。包含 ARM64 库不代表游戏已经能在 ARM64 上运行。

## 模拟器验收

先生成完全合成的测试资源。它们不包含游戏内容。

```powershell
python tools/make_fixture.py --output G:\DevCaches\Temp\d2r-android-fixture
python tools/verify.py manifest --profile fixture --manifest G:\DevCaches\Temp\d2r-android-fixture\game-manifest.json --resources G:\DevCaches\Temp\d2r-android-fixture
adb install -r app/build/outputs/apk/fixture/debug/app-fixture-debug.apk
adb push G:\DevCaches\Temp\d2r-android-fixture /sdcard/Download/
```

打开 TEST 应用，通过系统目录选择器选择刚复制的文件夹。所选目录必须直接包含 `game-manifest.json` 和三个数据文件。导入完成后重启应用，检查资源状态及设备报告。选择同一目录导入正式应用应被拒绝。

设备报告下的“打开 Vulkan 测试画面”可运行独立三色呈现探针，不需要导入任何资源。
它通过真实 Vulkan render pass 和交换链出图，检查前后台与旋转后的会话恢复；不执行游戏、NVN 指令或游戏着色器。
构建、设备检查命令与验证范围见 [Vulkan 呈现探针](docs/vulkan-probe.md)。

## 准备正式资源

输入必须是已提取的原始 `Update` 目录，包含 `ExeFS`、`RomFS/data` 和项目要求的构建清单。NSP、NSZ、XCI 包文件不能直接导入本应用。此工具不提取安装包，也不获取密钥。

```powershell
python tools/prepare_game.py --update-root X:\Extracted\Update --output X:\Extracted\Update\RomFS\data\game-manifest.json
python tools/verify.py manifest --manifest X:\Extracted\Update\RomFS\data\game-manifest.json --resources X:\Extracted\Update\RomFS\data
python tools/verify.py contract-check
python tools/verify.py unit
```

工具核对三个原始 NSO 的固定 SHA-256，以及 18 个档案的名称和大小，再流式计算档案摘要。修改过的主程序和稀疏裁剪资源不属于本阶段的输入。将 `game-manifest.json` 与要求的数据文件放在同一个可供 Android 选择的目录中。不要把 ExeFS 复制进应用。

清单是未签名的本地元数据。Android 验证清单结构、契约和复制内容摘要，不能由此证明文件来源或真实性。正式资源总大小为 5,561,224,037 字节；导入时需要额外的应用私有存储空间。

## 导入与恢复边界

应用在私有、不参与备份的目录内导入资源。全部文件完成大小及 SHA-256 检查后才发布新资源集。提交开始前接受的取消请求会保留旧集。进入提交阶段后不能取消，应用会明确显示正在完成发布。复制及校验在前台进行；旋转屏幕保留操作，进入后台请求取消。重试可以重新复制，不承诺从断点字节继续。

已发布的旧资源集会保留，当前阶段不自动清理。反复导入会增加空间占用。清除应用数据或卸载应用会丢失导入副本；请保留来源文件。冷启动检查已发布记录和文件长度，不把这一检查描述成对全部内容重新计算摘要。

## 验证边界

x86_64 模拟器用于应用、SAF 文件访问、导入状态和 JNI 加载验证。设备报告中的 Vulkan 枚举只是驱动能力查询，没有渲染游戏。OPPO X9 Ultra / Android 17 真机仍需单独验证 ARM64 指令执行、TLS/x18、页大小、驱动兼容性、输入、温度和持续性能。
[Synthetic ARM64 bridge ABI and verification](docs/arm64-bridge.md)
