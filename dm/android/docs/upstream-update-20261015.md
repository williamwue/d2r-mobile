# 上游 v1.0.16 整合记录

2026-10-15，将上游 `v1.0.16` / `afad65c895386a96dfb863f2317e41d61222a5a9` 合入本地 Android 分支。该版本比此前采用的上游 `824d2d3` 多 37 个提交。整合在独立 worktree 中进行，原有 13 个未提交或新增文件逐一核对 SHA-256，保持原内容和未提交状态。

## 资源契约

上游 `dm/PRODUCT` 从 `3.3.94012-r40` 升至 `3.3.94012-r44`。Android 正式资源契约及 `game-manifest.schema.json` 的 `contractId`、`productVersion` 同步更新；fixture 契约不变。三个原始 NSO 摘要和 18 个档案的名称、大小与之前一致。

旧 r40 清单会被新契约拒绝。如果已有正式资源清单，请用当前 `tools/prepare_game.py` 从已提取的原始 `Update` 目录重新生成，并用 `tools/verify.py manifest` 检查。原始资源未因这次整合改变；不要仅手工改旧清单的版本字段。

上游还修复了程序改写中的条件分支出口处理。Apple 平台使用的旧改写结果需要按新版本重新生成；本次没有将该实现直接移植为 Android 游戏运行时。

## 本轮检查

- `python tools/verify.py contract-check` 通过，另核对 Schema 与正式契约的身份字段一致。
- Python 共 35 项测试通过。
- fixture、game 的 JVM 测试各 24 项通过，零失败、错误或跳过。
- fixture debug、game debug、fixture AndroidTest APK 构建通过。两个应用 APK 内均包含 ARM64 与 x86_64 库，打包的契约与源码一致。
- 两个 debug 变体 Lint 均为零错误、5 条警告。
- ARM64 指令模型通过 184 项断言，执行 624 条指令，并检出四种故意破坏。已检查最终链接 ELF 的门分支及其与 fixture APK 内 ARM64 `.text` 的一致性。

构建、测试及模型在 Windows 隔离工作区执行。本轮没有执行设备端 instrumentation、Mac/iOS 构建、正式游戏装载或真机测试。跨平台构建和完整游戏结果仍须在对应环境验收。

Android 后续开发继续保持暂停。此次只整合上游、同步资源契约、运行适用回归检查并刷新迁移包，未完成此前暂停的最终设备复验和独立审查，也未发布或推送。
