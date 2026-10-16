# Vulkan 呈现探针

本阶段为模拟器优先开发提供一个独立的 Vulkan 呈现与生命周期检查。它不依赖游戏数据，不实现 NVN，也不装载原版程序。

## 使用

在主页面“设备报告”下选择“打开 Vulkan 测试画面”。红、绿、蓝三列由 Vulkan 的 render pass 与 `vkCmdClearAttachments` 生成，再通过 Android Surface 交换链呈现；Java 不绘制三色内容。

探针大约每 100 ms 提交一次，使用 FIFO 呈现。这是测试节奏，不能作为游戏帧率指标。状态中的“已提交”来自 `vkQueuePresentKHR` 的返回；设备测试另外用 PixelCopy 检查 Surface 像素，不把提交计数单独作为显示正确的证据。

## 实现约定

- `GraphicsProbeActivity` 用单独 HandlerThread 串行持有原生会话。Surface 替换、后台暂停及 Activity 销毁使旧代次失效，过期任务不再创建或绘制会话。
- `present_probe.cpp` 用 RAII 释放原生窗口、Vulkan 对象及部分初始化失败时的资源。释放前等待设备空闲。此原型不提供驱动失去响应时的进程内恢复保证。
- 渲染 fence 在确定取得交换链图像之后才重置。呈现等待 semaphore 按交换链图像分配，不能用渲染 fence 代替呈现完成条件。
- 清除矩形以 SurfaceView 坐标定义，要求交换链支持 identity transform。初版沿用 display current transform 时，横屏三列被转成三行，旋转像素测试复现后已修正；不支持 identity 的设备会明确失败。
- Surface 尺寸变化触发完整会话重建，`VK_ERROR_OUT_OF_DATE_KHR` 也请求重建。未实现游戏后端的资源复用、着色器管线或优化。
- JNI 接口仅供应用内部使用，原生 handle 由渲染线程拥有。它不是接收任意 handle 的公共接口。

交换链处理依据：[Khronos 交换链重建说明](https://docs.vulkan.org/tutorial/latest/03_Drawing_a_triangle/04_Swap_chain_recreation.html)、[呈现 semaphore 复用](https://docs.vulkan.org/guide/latest/swapchain_semaphore_reuse.html)。

## 设备检查

从 `dm/android` 目录构建 fixture 应用及 instrumentation APK。所有 adb 命令都指定目标 serial，避免误装到同时连接的真机。以下示例使用 `emulator-5554`：

```powershell
$env:JAVA_HOME = 'G:\Dev\SDKs\jdk-21'
$env:ANDROID_HOME = 'G:\Dev\SDKs\Android'
.\gradlew.bat :app:assembleFixtureDebug :app:assembleFixtureDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/fixture/debug/app-fixture-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/fixture/debug/app-fixture-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r -e suite vulkan-probe org.d2rm.android.fixture.test/org.d2rm.android.ImportInstrumentation
```

必须同时检查 `VULKAN_PROBE_REPORT` 中 `status=passed` 和 `INSTRUMENTATION_CODE: -1`。adb 命令退出 0 也可能对应测试失败。

测试覆盖空 Surface 拒绝、真实三色像素、三轮前后台释放与重建、Activity 重建、横屏后交换链尺寸与像素、最终会话释放。PixelCopy 的 PNG 保存在 fixture 应用的 `no_backup/vulkan-probe-*.png`。报告中的 `created` 与 `destroyed` 应在测试结束时相等。

## 2026-10-16 验证

基于 `main` 的 `8f9d051` 创建 `feature/android-oppo-94012`。Windows 上两个 debug flavor 与 fixture instrumentation 构建通过；两个 JVM 套件各 24 项通过，两个 flavor 的 Lint 检查通过。

在 `AppVanta_Verification_API37` 的 x86_64 / Android 17 模拟器上通过 51 项设备检查。图形设备报告为 `Goldfish GFXStream (SwiftShader Device (LLVM 10.0.0))`，模拟器显式使用 `-gpu swiftshader`。最终记录创建和销毁各 13 个会话，六张 Surface 像素图包含初始、三轮恢复、重建及横屏结果。原有导入器 14 项设备检查也通过。两个 flavor 的 Lint 各保留 5 条既有警告，没有新增警告。

原始日志、源文件与 APK 摘要、环境属性和 PNG 位于 `G:\Projects\.agent-store\d2r-android\evidence\vulkan-probe-20261016-r2`。设备面板因 `daemon.lock is not a directory` 不可用，本轮通过指定 serial 的 adb 驱动模拟器。

该结果只证明这一模拟器及软件图形后端上的呈现探针通过。OPPO/ARM64 真机、Vulkan validation layer、正式游戏输入、CPU/HLE、游戏着色器、NVN 回放和性能均未由本轮验收。后续用同一套检查在真机复验，再进入真实游戏运行时开发。
