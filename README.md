# Memo Sync POC — 手机 ↔ HarmonyOS Lite Wearable 双向备忘录

这是“双向同步备忘录”的测试性开发（POC），目标不是完整产品，而是先把最容易翻车的几件事验证掉：

1. **两端合并规则一致**：手机（JVM）和手表（JS）用同一套 HLC + 字段级合并，跑同一组 `shared/merge_cases.json`，结果必须逐字一致。
2. **Fit 3 的矩形布局**：测试设备是 **Huawei Watch Fit 3，408×480，矩形**，不是 GT 3；`round=false`，键盘用 `keyboard_rect`。
3. **crabKeyboard 能嵌入**：它是页面，不是系统输入法；编辑页要通过 `system.storage` 保存上下文，并给键盘 `done/cancel` 加 `editAction`。
4. **仓储/同步可测**：两端都用 fake storage/transport 跑离线编辑、outbox、分片、重试、冲突。

> 真实设备/真实 Wear Engine 传输不在本 POC 范围内；传输层是 mock，协议和仓储是真的。

---

## 目录

```text
memo-sync-poc/
  shared/                协议与共享 fixtures（Lead 维护，子 agent 只读）
    PROTOCOL.md
    merge_cases.json
  watch/                 手表侧 POC（watch-poc 写入）
  phone/                 手机侧 POC（phone-poc 写入）
  tools/                 Lead 的参考实现与整合测试
  README.md              本文件
```

---

## 快速运行

### 0. 参考实现（Python，不依赖任何构建工具）

```powershell
python memo-sync-poc\tools\reference_merge.py
```

### 1. 手表侧（Node）

```powershell
cd memo-sync-poc\watch
node test\run-merge-cases.mjs
node test\run-tests.mjs
```

输出：`watch/out/results.json`。

### 2. 手机侧（Java 17）

```powershell
cd memo-sync-poc\phone
powershell -NoProfile -ExecutionPolicy Bypass -File run-tests.ps1
```

输出：`phone/out/results.json`。

### 3. 跨语言一致性

```powershell
python memo-sync-poc\tools\run_conformance.py
```

### 4. Fit 3 布局静态检查

```powershell
python memo-sync-poc\tools\check_fit3.py
```

### 5. GitHub Actions 编译手机 App

工作流：[.github/workflows/phone-build.yml](.github/workflows/phone-build.yml)

- 触发：`workflow_dispatch` 或 push 到 `phone/**`、`shared/**`；
- Runner：`windows-latest`（保留 POC 的 PowerShell 测试脚本）；
- 工具链：JDK 21 + Android SDK 36 + Gradle 9.3.1；
- 步骤：先跑 Java core 86/86 + 7/7，再构建 APK，上传 artifact；
- 默认构建 `:shell:assembleDebug`——这是一个**只依赖 SaltUI、没有 Wear Engine SDK / Room KSP 的可构建 UI shell**，用来先验证 SaltUI/Compose/Android 工具链；
- 手动触发时可选择 `:app:assembleDebug`，等 Wear Engine SDK AAR 和 Room KSP 接入后再把默认目标切到 `:app`。

> 仓库根目录假设：把 `memo-sync-poc/` 作为 GitHub repo 根目录；如果 push 的是上一层 `codespace/`，把 workflow 里的 `phone/...` 改成 `memo-sync-poc/phone/...`。

---

## 架构

```text
Android 手机（Hub / 合并权威）
  Room: memos / sync_state / memo_peer_base / conflicts
  SyncManager: HLC + 字段级合并 + 冲突副本
  WearTransport: Wear Engine P2P（POC 为 mock）
        │
        │ 华为运动健康 / WearEngineService（真实环境）
        ▼
HarmonyOS Fit 3（Client / 离线编辑）
  pages: list / detail / edit / settings
  keyboard: crabKeyboard keyboard_rect（页面内嵌，408×480）
  Repo: index.json + memo_<id>.json + outbox.json
  SyncClient: req / start / push / pull / pack / done
```

关键设计：

- 手机是合并权威；手表只推 dirty、收 canonical。
- 记录级 `updatedHlc` + 字段级 `titleHlc/bodyHlc/deletedHlc`；冲突生成 `[冲突]` 副本。
- 单条 P2P 消息目标 8KB，正文上限 6144 字节。
- `outbox` 只有收到 `done` 才清；同步中继续编辑保留新 dirty。
- 手表 `subscribeMsg` 在 App 启动时注册一次，不要放页面 `onDestroy`。

---

## 当前状态

| 项 | 状态 | 证据 |
|---|---|---|
| 共享协议/fixtures | ✅ 完成 | [PROTOCOL.md](shared/PROTOCOL.md)、[merge_cases.json](shared/merge_cases.json) |
| Python 参考合并 | ✅ 7/7 | `tools/reference_merge.py` |
| 手表 JS 合并 | ✅ 7/7 | `watch/test/run-merge-cases.mjs` |
| 手表 JS 单测/冒烟/静态检查 | ✅ 41/41 | `watch/test/run-tests.mjs` |
| 手表 HAP 骨架 | ✅ 文件齐全，未构建 | Fit 3 408×480 / rect；见 [watch/README.md](watch/README.md) |
| 手机 Java core | ✅ 86/86 + 7/7 | [phone/README.md](phone/README.md) |
| 手机 SaltUI UI 骨架 | ✅ 源码就绪 + 可构建 shell | `phone/android/shell/` 由 CI 构建；完整 `:app` 仍缺 Wear Engine SDK/Room KSP |
| GitHub Actions 手机编译 | ✅ workflow 就绪，待首次运行 | `.github/workflows/phone-build.yml`（`:shell` 可构建；`:app` 可选） |
| 跨语言 conformance | ✅ PASS | `tools/run_conformance.py` |
| Fit 3 静态检查 | ✅ PASS | `tools/check_fit3.py` |
| 真机 HAP 构建 / 真实 P2P | ⛔ 本机受阻 / 未联调 | 无 API 7 Lite Wearable SDK；hvigor 5 为 stage 模型；见 README §7 |

### 最终测试记录

```text
python memo-sync-poc/tools/reference_merge.py        -> 7/7 PASS
cd memo-sync-poc/watch && npm test                   -> merge 7/7 + tests 41/41 PASS
cd memo-sync-poc/phone && run-tests.ps1              -> 86/86 + merge 7/7 PASS
python memo-sync-poc/tools/run_conformance.py        -> CONFORMANCE: PASS
python memo-sync-poc/tools/check_fit3.py             -> FIT3: PASS
```

手表 41 项覆盖 HLC 7、text/config 3、merge 7、repo/outbox 13、sync fake 6、静态检查 5。  
手机 86 项覆盖 HLC 15、MergeEngine 20、ProtocolCodec 12、Chunking 16、SyncManager 19、MergeCases 4。

---

## 真实 vs Mock

| 部分 | POC | 正式 |
|---|---|---|
| 手机 ↔ 手表传输 | 内存 fake transport | 华为 Wear Engine P2P / Honor Wear Kit |
| 手表文件系统 | fake storage（Node） | `@system.file` + `internal://app/` |
| 手机数据库 | 内存 store（Java） | Room/SQLite |
| 手表键盘 | 复制 crabKeyboard 源码 | 同源，按 408×480 适配 |
| 手机 UI | SaltUI Kotlin 源码骨架 | SaltUI + Compose，JDK 21 构建 |
| 设备 | 无真机 | Fit 3 真机 + 华为运动健康 |

---

## 手机 UI：SaltUI

手机前端按用户要求使用 [SaltUI](https://github.com/Moriafly/SaltUI)（Apache-2.0，Compose Multiplatform）。

- 依赖：`io.github.moriafly:salt-ui:3.0.0`（所有 SaltUI artifact 同版本）；
- 根节点 `SaltTheme`；页面使用 `BasicScreen` / `ScreenCard` / `Item` 等；
- SaltUI 3.x 要求 Kotlin 2.4.20、Compose Multiplatform 1.12.2、AGP 9.1.1、JDK 21；
- 当前机器 JDK 17、无 Kotlin CLI，因此 **UI 层只提供源码骨架，不在本机编译**；Java 17 core 的测试不受影响。

---

## 下一步

1. **真机 HAP**：用 DevEco Studio 3.x + API 7 Lite Wearable SDK 打开 `watch/`，补调试签名，在 Fit 3 上跑通 `keyboard_rect` 的 408×480 输入/返回流程。
2. **真实传输**：把 `watch/common/memo/transport.js` 的 `FeatureAbility` 骨架和 `phone/android/.../WearEngineTransport.kt` 接到真实 Wear Engine；配置 `supportLists` / `peerFingerPrint` 的 release 证书。
3. **手机 UI**：在 JDK 21 / AGP 9.1.1 / SaltUI 3.0.0 环境编译 `phone/android/`，把 `ui/MemoScreens.kt` 的 demo 数据换成 Room + Java core 的 `SyncManager`。
4. **真机双向测试**：手机新建→手表看到；手表离线编辑→手机合并；同字段冲突→冲突副本；断线/重启/低电量。
5. **再做 v1.1**：diff3 正文合并、标签、多设备、可选加密。

> 已知阻塞（本机）：无 API 7 Lite Wearable SDK、hvigor 5 只支持 stage 模型（Lite Wearable 是 FA）、JDK 17 不满足 SaltUI 3.x 的 JDK 21 工具链。详见 [watch/README.md](watch/README.md) §7 与 [phone/android/README.md](phone/android/README.md)。
