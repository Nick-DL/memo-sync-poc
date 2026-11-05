# 手机端 POC（Java 17 同步核心 + Android/SaltUI 源码骨架）

> 共享契约：`../shared/PROTOCOL.md`、`../shared/merge_cases.json`（只读，本目录不修改）。
> 设计文档：`../../daysmatter-analysis/双向备忘录App-两端设计方案.md`。

本目录是“手机 + 华为 Fit 3 轻手表”双向备忘录 POC 的**手机侧**。环境里没有 `kotlinc`/可用
Gradle，因此采用 **Java 17 核心 + `javac` 一条命令测试** 的路线；Android 层（Kotlin +
**SaltUI**）作为**不能在本环境编译的源码骨架**交付，并在 `android/README.md` 标注原因。

## TL;DR 状态

| 项目 | 状态 |
|---|---|
| Java 17 同步核心（HLC / MergeEngine / ProtocolCodec / Chunking / SyncManager / InMemoryTransport） | **真实实现，已测试** |
| 共享合并用例 | **7/7 PASS**（写入 `out/results.json`，与 `shared/merge_cases.json` 的 `expect` 完全一致） |
| Java 自测 | **86/86 PASS**（HLC 15、Merge 20、Codec 12、Chunking 16、Sync 19、MergeCases 4） |
| 手机↔手表传输 | **Mock**：`InMemoryTransport`；真实 Wear Engine 适配器只是源码骨架 |
| Android App / SaltUI UI / Room | **源码骨架，未编译**（JDK 17 ≠ SaltUI 3.x 需要的 JDK 21 工具链） |

## 目录结构

```text
phone/
├─ core/
│  ├─ build.gradle.kts                  # 可选 Gradle 接线（POC 实际用 javac，不执行）
│  └─ src/
│     ├─ main/java/com/example/memosync/
│     │  ├─ core/  Hlc.java MemoRecord.java MergeResult.java MergeEngine.java
│     │  │         WireMessage.java ProtocolCodec.java Chunker.java ChunkAssembler.java
│     │  │         Transport.java TransportException.java InMemoryTransport.java
│     │  ├─ sync/  SyncManager.java MemoStore.java PeerState.java InMemoryMemoStore.java
│     │  └─ cli/   MergeCaseRunner.java
│     └─ test/java/com/example/memosync/test/
│           AllTests.java TestRunner.java Assertions.java Records.java
│           HlcTest.java MergeEngineTest.java ProtocolCodecTest.java
│           ChunkingTest.java SyncManagerTest.java MergeCasesTest.java
├─ lib/gson-2.11.0.jar                  # Maven Central vendor（JSON 解析/输出）
├─ out/results.json                     # 契约输出（由 CLI 生成，已提交为证据）
├─ run-tests.ps1                        # 编译 + 全部自测 + merge cases CLI
├─ run-merge-cases.ps1                  # Lead conformance harness 的入口（只跑 merge cases）
├─ android/                             # SaltUI 工程；:shell 可在 CI 构建，:app 源码骨架
└─ README.md
```

## 环境要求

- Windows 10/11 + **JDK 17**（`javac`/`java` 在 PATH；本机验证为 17.0.14）。
- 无需 Kotlin / Android SDK / Gradle / 外网即可运行核心测试。
- `lib/gson-2.11.0.jar` 已 vendor（SHA-256 `57928D6E5A6EDEB2ABD3770A8F95BA44DCE45F3B23B7A9DC2B309C581552A78B`），
  来源 `https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar`。
  如果该 jar 丢失，`run-*.ps1` 会报错并给出下载 URL。

## 怎么跑（精确命令）

全部命令在 `memo-sync-poc/phone/` 下执行（脚本内部也会 `Push-Location $PSScriptRoot`）：

```powershell
# 1) 全量自测 + 合并用例：期望 "86/86 tests passed" + "RESULT: PASS" + "ALL PHONE POC TESTS PASSED"
powershell -NoProfile -ExecutionPolicy Bypass -File run-tests.ps1

# 2) 只跑共享合并用例（Lead 的 conformance harness 使用的入口）：
#    期望 7/7 PASS，退出码 0，写 out/results.json
powershell -NoProfile -ExecutionPolicy Bypass -File run-merge-cases.ps1

# 3) 直接跑 CLI（等价于 2，参数可覆盖默认路径）
java -cp "out\classes-tests;lib\gson-2.11.0.jar" com.example.memosync.cli.MergeCaseRunner
java -cp "out\classes-tests;lib\gson-2.11.0.jar" com.example.memosync.cli.MergeCaseRunner --cases ..\shared\merge_cases.json --out out\results.json
```

脚本兼容 Windows PowerShell 5.1 和 PowerShell 7（实测用 `powershell -NoProfile -File`，即 5.1
路径；脚本本身为 ASCII，Java 源文件为 UTF-8 且 `javac -encoding UTF-8`）。没有引入任何测试框架：
`TestRunner` 打印 `PASS/FAIL` 行，失败时退出码非 0。

Lead 的验收命令（仓库根目录）：

```powershell
python memo-sync-poc/tools/run_conformance.py            # 两端都跑
python memo-sync-poc/tools/run_conformance.py --skip-watch
# 本机实测（--skip-watch）: CONFORMANCE: PASS, exit 0
```

生成物：`out/results.json`（契约格式）；`out/classes-merge/`、`out/classes-tests/` 为临时编译输出，
可随时删除并由脚本重建。

## GitHub Actions 编译手机 App

GitHub Actions 工作流：`../.github/workflows/phone-build.yml`（如果 `memo-sync-poc` 是 repo 根目录）。

- Runner：`windows-latest`；JDK 21；Android SDK 36；Gradle 9.3.1。
- 先跑本目录的 `run-tests.ps1`（86/86 + 7/7），再构建 APK。
- 默认目标：`:shell:assembleDebug` —— `android/shell/` 是一个只依赖 SaltUI、不依赖
  Wear Engine SDK / Room KSP 的**可构建 UI shell**，用于验证 JDK 21 + AGP 9.1.1 +
  Kotlin 2.4.20 + CMP 1.12.2 + SaltUI 3.0.0 的完整工具链。
- 手动触发时可选择 `:app:assembleDebug`；`android/app/` 是完整手机骨架，等 Wear Engine SDK
  AAR、Room KSP、证书指纹接好后即可切默认目标。
- 产物：Actions artifact `memo-sync-phone-debug`（APK）。

本地无法构建的原因：本机只有 JDK 17，SaltUI 3.x 需要 JDK 21；Android SDK 存在但不是瓶颈。
所以把 Android 编译放到 CI 是本 POC 的正式构建路径。

## 本 POC 里“真的”和“mock 的”

| 能力 | 真实性 | 说明 |
|---|---|---|
| HLC（parse/compare/next/receive、溢出不破坏 4 位编码） | ✅ 真实现 | `core/Hlc.java`，15 个单测；同时提供 null 容错比较辅助方法 |
| 记录模型 / 三路合并 / 冲突副本 | ✅ 真实现 | `core/MergeEngine.java`，逐条对齐 `shared/PROTOCOL.md` §2 与 Lead 的 `tools/reference_merge.py` |
| 短线协议 JSON（`v/t/sid/dev/cur/dirty/i/n/recs/next/code/msg`；记录 `id/ti/bo/c/u/th/bh/del/dh/rev/base/f`） | ✅ 真实现 | `core/ProtocolCodec.java`，12 个单测；`del` 输出 0/1，`dh`/`conflict` 显式 `null` |
| 分片 / 重组（8 KB 预算、乱序、重复、空传输 `n=0`） | ✅ 真实现 | `core/Chunker.java` + `ChunkAssembler.java`，16 个单测 |
| 手机 Hub 状态机（req/start/push/pack/pull/done/err） | ✅ 真实现（POC 骨架） | `sync/SyncManager.java`，19 个单测；含重复/乱序 chunk、旧 session 丢弃、pending 触发、多分片 pull、强制下发 `f=1` |
| 仓储（含 peer base、游标持久化） | ✅ 真实现（内存版） | `sync/InMemoryMemoStore.java`；Android Room 版是骨架 |
| 手机↔手表传输 | ❌ Mock | `core/InMemoryTransport.java`（测试用）；真实 Wear Engine 见 `android/.../WearEngineTransport.kt`（未编译） |
| Android UI（SaltUI） | ✅ `:shell` 可构建；`:app` 源码骨架 | `android/shell/` 是 CI 默认构建的 SaltUI shell；完整 `:app` 需 Wear Engine SDK / Room KSP |
| Room / Wear Engine P2P | ❌ 源码骨架 | `android/app/` 标记 `NOT COMPILED IN THIS POC`，见 `android/README.md` |
| JSON 库 | ✅ 真实依赖 | vendored Gson 2.11.0（非自写解析器） |

## 合并契约实现要点（与 `shared/merge_cases.json` 对齐）

1. 一边为 null：另一边原样作为 canonical（不改 rev）。
2. 两边都有：
   - `title` 用 `titleHlc`、`body` 用 `bodyHlc` 做字段级 LWW；**HLC 相同内容不同 → 手机侧赢**；
   - `deleted`：删除方 `deletedHlc` vs 编辑方 `updated`，**编辑方严格更新才复活**，平局删除胜；
     双方都删 → 保留较大 `deletedHlc`；复活时清空 `deletedHlc`；
   - **即使删除胜出，title/body 仍按字段 HLC 合并**（共享用例 `edit_vs_delete`：`deleted=true` 且
     `body` 取手表更新的 `"W"`）；
   - `created` 取较小 HLC、`updated` 取较大 HLC、`rev = max(rev)+1`。
3. 冲突副本：`base` 非空且双方 `bodyHlc` 都相对 base 改变、且 body 不同 → 生成
   `id = phone.id + "-conflict"`、`title = "[冲突] " + loser.title`、`body = loser.body`、
   `deleted = false`、`rev = 1`；loser 为 `bodyHlc` 较小者，平局 watch 为 loser。
4. CLI 输出只含契约字段：`results[].name`、`canonical{title,body,deleted}`、`conflict{title,body}|null`；
   HLC/rev 由单测覆盖。7 个共享用例逐条 PASS（HLC 相同 → 手机优先等细节由自测覆盖）。

> 说明：`MergeEngine` 是 Lead 参考实现 `memo-sync-poc/tools/reference_merge.py` 的 Java 逐规则移植；
> 两边对同一 fixture 结果一致，是本 POC 的跨语言一致性证据。

## 同步状态机（手机 Hub，POC 范围）

```text
手表 → 手机 : req {dev, cur, dirty}                       （或手机侧 requestSync 主动开始）
手机 → 手表 : start {sid, cur}
手表 → 手机 : push {sid, i, n, recs}                      （n=0 约定表示“没有 dirty 记录”）
手机 → 手表 : pack {sid, i}                               （每个 push 分片都回 ack，含重复分片）
        ……分片收齐后：三路合并、更新 canonical / peer base / 冲突副本
手机 → 手表 : pull {sid, i, n, recs, next}                （`updated > cur` 的记录 + 本次合并结果强制 `f=1`）
手表 → 手机 : pack {sid, i}
手机 → 手表 : done {sid, cur=next}
```

- 同一时间只跑一个 session；会话中新的触发只置 pending，结束后自动再开；
- 旧 `sid` 的消息直接丢弃；重复 chunk 会重新 ack 但只应用一次；乱序 chunk 按 `i` 重组；
- 分片按 8 KB 消息预算（`Chunker`），单条超大记录单独成片并标记 `oversized()`；
- **POC 骨架不包含**：5s 重发/3 次重试、60s 会话超时、持久化队列（设计文档 §4.5 的定时器/存储部分），
  这些由 Android 层 + 后续迭代补齐（README 末尾“阻塞与后续”）。

## Java 核心 ↔ Android/Kotlin 映射

| Java 17 POC（已测试） | Android/Kotlin 骨架（未编译） | 说明 |
|---|---|---|
| `core/Hlc.java` | `data/MemoDao.kt`（编辑保存时调用 `Hlc.next()`） | 手机 deviceId 来自 `DeviceIds`（SharedPreferences） |
| `core/MergeEngine.java` | `sync/SyncManager.kt`（由 Java 核心直接调用，不重写） | 手机是合并权威，Kotlin 不重复实现 |
| `core/ProtocolCodec.java` | 两端共用同一短线 JSON 契约（watch 侧 JS 必须镜像） | 非 Kotlin 重写 |
| `core/Chunker.java` / `ChunkAssembler.java` | `sync/WearEngineTransport.kt` + 8 KB 消息预算 | 传输层只负责字节 |
| `core/Transport.java` + `core/InMemoryTransport.java` | `sync/Transport.kt`（extends 核心 Transport）+ `sync/WearEngineTransport.kt` | mock ⇒ 真实 Wear Engine P2P |
| `sync/SyncManager.java`（Hub 状态机） | `sync/SyncManager.kt`（生命周期/去抖/单例包装） | 业务不因换传输而改 |
| `sync/MemoStore.java` + `InMemoryMemoStore.java` | `data/MemoDao.kt` `RoomMemoStore`、`data/MemoEntity.kt`、`data/AppDatabase.kt` | Room 表结构对齐设计文档 §3.3 |
| `cli/MergeCaseRunner.java` | —（仅 POC 验收用） | conformance 入口 |

Android UI：`android/app/src/main/java/com/example/memosync/android/`
`MainActivity.kt`（`SaltTheme` 根 + `onStart` 触发同步）与 `ui/MemoScreens.kt`（list/edit/conflict/settings，
使用 SaltUI 的 `BasicScreen`、`ScreenCard`、`Item`、`Button`、`Text`、`TitleBarButton`）。
SaltUI 版本/依赖/未确认 API 见 `android/README.md`。

## 测试覆盖清单（86 项）

- `HlcTest`（15）：格式/13+4 位宽、含 `-` 的 deviceId、比较顺序（t→c→d）、编码字符串排序、`next`
  单调/时钟回拨/计数器溢出保护、`receive` 三种分支、相等/哈希、null 容错辅助方法。
- `MergeEngineTest`（20）：null 侧、字段 LWW、Equal-HLC 手机优先、created/updated/rev、双方删除、
  删除 vs 编辑（双向）、平局删除胜、**删除胜但 body 仍取较新字段**、冲突副本（loser 判定/平局 watch）、
  同内容/单边改动/base 为 null 不产生冲突、输入防御性拷贝。
- `ProtocolCodecTest`（12）：各消息类型短键、记录短键、`del` 0/1、`dh` 显式 null、`f=1`、`base`、
  recs 往返、版本/类型/JSON 校验、多余字段容忍、缺失 recs 语义。
- `ChunkingTest`（16）：UTF-8 字节（中文/emoji）、空输入、顺序保持、每片预算、超大单条标记、
  组装（顺序/乱序/重复/`n=0`/缺失/非法下标/计数不一致/reset）、split→wire→decode→assemble 往返。
- `SyncManagerTest`（19）：主动/被动开始会话、完整会话、远端编辑合并回 pull、远端新建、墓碑下发、
  冲突副本落库、重复/乱序 push、旧 session 丢弃、重复 pack、pending 触发、坏帧、传输失败、
  abort、多分片 pull、强制下发、远端删除胜、下次会话复用游标。
- `MergeCasesTest`（4）：CLI 7/7 + 输出文件、逐用例期望比对与顺序、`edit_vs_delete` 契约回归、
  输出字段白名单（`canonical` 3 键 / `conflict` 2 键）。

## 阻塞与后续

| 项 | 状态 / 说明 |
|---|---|
| JSON 依赖 | **已解决**：`lib/gson-2.11.0.jar` vendor 自 Maven Central；注意 Gson 默认不输出 `JsonNull` 成员，`ProtocolCodec`/`MergeCaseRunner` 显式启用 `serializeNulls()`（这是 POC 抓到的真实 bug） |
| Kotlin / Android 构建 | **阻塞**：本机仅 JDK 17；SaltUI 3.x 目标 JDK 21 工具链（Kotlin 2.4.20 / CMP 1.12.2 / AGP 9.1.1），无 `kotlinc`、无可用 Gradle wrapper 发布版；Android SDK 存在（`D:\Android\Sdk`）但不是当前阻塞点。`android/` 全部源码骨架，未编译 |
| Wear Engine 真机 | **未验证**：需要 HMS appid、真机证书指纹、Wear Engine SDK AAR；`WearEngineTransport.kt` 按倒数日反编译参考实现 + 设计文档 §5.6 写骨架，SDK 回调签名标 `TODO(SDK)` |
| 协议歧义（已按共享用例/参考实现定案） | ① 删除胜出时 title/body 仍做字段级 LWW（`edit_vs_delete` 契约）；② 字段 HLC 平局手机胜；③ 冲突 loser 平局 watch；④ 删除 vs 编辑平局删除胜；⑤ 空 push `n=0` 表示无 dirty（POC 约定，设计文档未显式定义） |
| 协议未定义项（POC 未实现） | 消息大小上限（设计文档说需实测，POC 按 8 KB 预算）、5s 重发 / 3 次重试、60s 会话超时、丢包网关；Android 层由 coroutine/前台服务后续补齐 |
| conflict 副本的 HLC 列 | POC 用 canonical 的 `created/updated` 填充；正式版应由产品定义（当前只要求 `title/body/deleted/rev`） |
| Room `memo_peer_base` 列 | 设计文档 §3.3 只列 `base_hlc/base_title/base_body`；POC 合并需要 base 的字段 HLC，`MemoEntity.kt` 已扩展列并加注释 |
| 手表侧一致性 | 共享契约变更（`edit_vs_delete.body=W`、`conflict:null` 显式输出）已由 Lead 通知；watch-poc 的 JS 输出必须同样显式 `null`（JS `JSON.stringify` 默认就会输出） |
