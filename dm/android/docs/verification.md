# 首阶段验证记录

2026-10-13，Android 17、API 37、x86_64 模拟器。

## 已通过

| 检查 | 结果 |
| --- | --- |
| Python 资源工具 | 32 项测试通过 |
| 原项目契约对照 | PRODUCT、3 个 NSO 摘要、18 个档案名称和大小一致 |
| Android JVM 测试 | game 和 fixture 各 24 项通过 |
| Android 设备端导入测试 | 14 项通过，使用实际文件系统重命名和同步 |
| 构建 | game、fixture 的 debug 和未签名 release APK |
| Native 库 | x86_64、arm64-v8a 均构建成功 |
| JNI 实际加载 | x86_64，sentinel 4242，页大小 4096 字节 |
| Vulkan 枚举 | 模拟器 SwiftShader 设备可见，未执行游戏渲染 |
| 系统目录选择器 | TEST 包成功导入 48 MiB 合成资源 |
| 输入拒绝 | 正式包拒绝 fixture，TEST 包拒绝损坏及缺失文件 |
| 旧数据保护 | 失败导入后保留旧资源，冷启动恢复当前记录 |

两个 debug 变体的 Lint 均为 0 errors、6 warnings。提示涉及工具版本、备份规则、可用空间 API、应用图标和两处文本本地化。

设备故障注入覆盖完成资源目录重命名后、发布指针之前的失败恢复。`active.json` 替换后的目录同步失败及回滚由 JVM 测试覆盖，没有据此声称设备端验证了该回滚路径。

## 复现检查

在 `dm/android` 下执行：

```powershell
python tools/verify.py unit
python tools/verify.py contract-check
.\gradlew.bat :app:assembleGameDebug :app:assembleFixtureDebug :app:assembleGameRelease :app:assembleFixtureRelease :app:assembleFixtureDebugAndroidTest :app:testGameDebugUnitTest :app:testFixtureDebugUnitTest :app:lintGameDebug :app:lintFixtureDebug
adb install -r app/build/outputs/apk/fixture/debug/app-fixture-debug.apk
adb install -r app/build/outputs/apk/androidTest/fixture/debug/app-fixture-debug-androidTest.apk
adb shell am instrument -w org.d2rm.android.fixture.test/org.d2rm.android.ImportInstrumentation
```

设备测试输出末尾必须出现 `PASS 14 Android importer checks`，且不能出现 `FAIL`。不能仅凭 `adb` 的退出码判定通过。JVM 测试报告位于 `app/build/test-results`，Lint 报告位于 `app/build/reports`。

## 尚未证明

没有完整正式游戏资源导入证据。下载目录中的 NSP、NSZ 安装包不能代替已提取的输入。本阶段没有 ARM64 游戏执行、HLE 或游戏渲染实现。

没有 OPPO 真机证据，也没有真实掉电、16 KiB 页设备、持续性能或温度测试。模拟器 Vulkan 能力不能代替手机驱动验收。取消与重试已通过导入器测试；界面测试中的导入在切到后台前已结束，因此没有据此声称证明进行中导入的后台取消或旋转行为。

旧资源集及失败 staging 会保留，重复导入增加存储占用。首阶段需要手动管理应用数据，尚未实现资源集清理界面。
