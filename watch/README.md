# memo-sync-poc / watch —— Huawei Watch Fit 3（Lite Wearable 408×480）手表端 POC

手机 + 手表双向备忘录协议的**手表侧测试性实现**：Lite Wearable HAP 工程骨架 + 页面 + crabKeyboard 接入 + 纯 JS 协议核心（HLC / merge / repo / outbox / sync）+ Node 测试。

> 测试设备更正：**Huawei Watch Fit 3**，矩形 **408×480**，`round=false`，键盘用 **`keyboard_rect`**（不是 GT 3 / 圆形表盘）。
> 共享契约见 `../shared/PROTOCOL.md`，合并用例见 `../shared/merge_cases.json`（只读，由 Lead 维护）。

---

## 0. 结论速览

| 项 | 状态 |
|---|---|
| 合并契约（7 个 shared cases） | ✅ `node test/run-merge-cases.mjs` → **7/7 passed**，输出 `out/results.json` |
| 手表端 JS 核心单测（HLC/merge/repo/outbox/sync 冒烟） | ✅ `node test/run-tests.mjs` → **41/41 passed** |
| HAP 工程骨架（config.json / 页面 / 键盘 / 词库） | ✅ 文件齐全，静态检查通过；**未构建、未真机运行** |
| crabKeyboard `keyboard_rect` 408 适配 | ✅ 溢出项已改（466→408、480 高相关）；⚠️ 无真机截图验证 |
| HAP 构建（DevEco/hvigor/Gradle） | ❌ 本机环境受阻（无 API 7 SDK / hvigor 5 不产 FA 包 / hvigor 启动被环境限制）——详见 §7 |
| 真实 Wear Engine 收发 | ⚠️ 只有 `FeatureAbility` 适配骨架，未联调 |

**一句话**：协议/仓储/同步逻辑是“真的且有测试”；手机端、真实传输、真机 UI 是 mock/未验证。

---

## 1. 目录结构

```text
memo-sync-poc/watch/
├── package.json                    # "type":"module"；Node 测试脚本（无外部依赖）
├── README.md                       # 本文件
├── THIRD_PARTY_NOTICES.md          # crabKeyboard MIT 声明（已复制 LICENSE 原文）
├── build.gradle / settings.gradle / gradle.properties / gradlew(.bat)
│                                  # 传统 Lite Wearable (FA 模型) Gradle 工程文件（DevEco 3.x）
├── gradle/wrapper/                 # 上游同款 Gradle wrapper（7.3，未含 distribution）
├── entry/
│   ├── build.gradle                # compileSdkVersion 7 / compatibleSdkVersion 7
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── config.json             # deviceType liteWearable / rect / 408*480 / supportLists / pages
│       ├── js/default/
│       │   ├── app.js              # App 级初始化（repo + HLC + FeatureAbility.subscribeMsg）
│       │   ├── common/
│       │   │   ├── keyboard/       # 从 crabKeyboard 复制的公共素材 + lookupDictV5.js + LICENSE
│       │   │   └── memo/
│       │   │       ├── config.js       # ROUND=false, SCREEN_W=408, SCREEN_H=480, KEYBOARD_PAGE, 上限/文件名
│       │   │       ├── text.js         # UTF-8 字节计数/截断/预览（纯函数）
│       │   │       ├── hlc.js          # HLC 解析/比较/生成/接收（纯函数，可 Node 测试）
│       │   │       ├── merge.js        # 三路合并引擎（纯函数，可 Node 测试）
│       │   │       ├── repo.js         # index.json + memo_<id>.json + outbox.json + draft.json（storage 注入）
│       │   │       ├── sync.js         # SyncClient 骨架（transport 注入；req/start/push/pack/pull/done/err）
│       │   │       ├── file-storage.js # @system.file -> storage 适配（设备侧）
│       │   │       ├── transport.js    # FeatureAbility + system.wearengine 适配骨架（设备侧）
│       │   │       └── services.js     # 单例装配：repo + HLC clock + SyncClient + transport.subscribe
│       │   └── pages/
│       │       ├── list/list.{hml,css,js}      # 列表（408×480）
│       │       ├── detail/detail.{hml,css,js}  # 详情（正文按段落拆 list-item）
│       │       ├── edit/edit.{hml,css,js}      # 编辑（打开 keyboard_rect + storage 上下文 + editAction）
│       │       └── keyboard_rect/keyboard.{hml,css,js}  # crabKeyboard 复制 + 408 适配 + editAction patch
│       └── resources/
│           ├── base/element/string.json        # app_name
│           ├── base/media/icon*.png            # 来自上游示例工程
│           └── rawfile/pinyinDictV5_flash1001/ # flash 轻量词库 25 个文件，1,136,195 字节
├── test/
│   ├── fakes.mjs                # fake storage（内存文件系统）/ fake transport / 确定性 HLC clock
│   ├── run-merge-cases.mjs      # 读 shared/merge_cases.json -> out/results.json（契约格式）
│   └── run-tests.mjs            # HLC/merge/repo/outbox/sync/静态检查 共 41 项
└── out/results.json             # run-merge-cases 生成（契约输出，Lead 用来跨端对比）
```

---

## 2. 真实 vs Mock

| 模块 | 状态 | 说明 |
|---|---|---|
| `hlc.js` | ✅ 真实、有测试 | 固定 `<13位t>-<4位c>-<deviceId>`；比较/生成/接收；计数器溢出进位；时钟回拨单调 |
| `merge.js` | ✅ 真实、有测试 | 严格实现 `shared/PROTOCOL.md` §2；`run-merge-cases.mjs` 7/7 |
| `text.js` | ✅ 真实、有测试 | UTF-8 字节计数（emoji=4B）/截断/预览 |
| `repo.js` | ✅ 真实逻辑、fake storage 测试 | `internal://app/index.json` + `memo_<id>.json` + `outbox.json` + `device.json` + `draft.json`；`.bak` 回退；outbox 优先启动修复 |
| `sync.js` | ✅ 真实状态机骨架、fake transport 测试 | `req→start→push→pack→pull→done`；短键记录；按 8KB 预算分片；旧 `sid` 丢弃；done 后 ack outbox |
| 页面 HML/CSS/JS | ⚠️ 代码真实、**未真机运行** | 408×480 布局；edit 与 keyboard 的回传流程按设计文档实现；无预览器/真机验证 |
| `@system.file` 适配 | ⚠️ 代码真实、未在设备上跑 | 文件 IO 是轻手表真实 API；Node 测试用内存 fake |
| `@system.storage` 上下文 | ⚠️ 代码真实、未在设备上跑 | editCtx 在打开键盘前写入；返回后恢复；兼容真机“回调直接给值”（非 `{value}`） |
| Wear Engine 传输 | ⚠️ 骨架 | `FeatureAbility.sendMsg/subscribeMsg/detect` + `system.wearengine.setPackageName/setFingerprint`；**未与手机端联调** |
| 手机端 / 真实 P2P / HAP 构建 | ❌ 不在本 POC 验证范围 | 见 §7 |

**没有任何 mock 进入 HAP 包**：`test/`、`out/` 不参与 Gradle 构建；页面导入的 `services.js` 用真实 `file-storage`/`transport`。

---

## 3. Node 测试命令（exact commands）

环境：Node.js v24.12.0（本机实测）。**无任何 npm 依赖**，不需要 `npm install`。

```powershell
# 仓库根目录
cd C:\Users\NickDL\Desktop\codespace

# 1) 合并契约：7 个 shared cases -> out/results.json（失败退出码 1）
node memo-sync-poc/watch/test/run-merge-cases.mjs

# 2) 手表端单测 + 冒烟 + 静态检查
node memo-sync-poc/watch/test/run-tests.mjs

# 或者在 watch 目录内（等价）
cd memo-sync-poc/watch
node test/run-merge-cases.mjs
node test/run-tests.mjs
npm test        # = 上面两条按顺序执行（package.json scripts）
```

预期输出（实测）：

```text
[PASS] watch_create
[PASS] phone_create
[PASS] different_fields
[PASS] same_field_conflict
[PASS] delete_vs_edit
[PASS] edit_vs_delete
[PASS] same_content_no_conflict

[run-merge-cases] 7/7 passed -> ...\memo-sync-poc\watch\out\results.json
...
[run-tests] 41/41 passed
```

`out/results.json` 的格式（与 `PROTOCOL.md` §3 一致，只投影 title/body/deleted 与 conflict 的 title/body）：

```json
{ "results": [ { "name": "watch_create",
                 "canonical": { "title": "W", "body": "watch", "deleted": false },
                 "conflict": null } ] }
```

---

## 4. 合并/同步实现要点（与 PROTOCOL 对齐）

### 4.1 合并（`merge.js`）

- 一边为 null：另一边直接作为 canonical（不 `rev+1`）。
- 两边都有：`title`/`body` 逐字段取 HLC 大者；相同 HLC 不同内容 → **手机侧优先**；`created` 取小、`updated` 取大、`rev = max(rev)+1`。
- `deleted` 用“删除方 `deletedHlc` vs 编辑方 `updated`”比较：编辑方更大 → 编辑胜（`deleted=false`、清空 `deletedHlc`）；删除方更大 → 删除胜。**HLC 相等时选择编辑优先**（避免误删；契约未规定，已在此显式记录）。
- 冲突副本（POC 只处理正文）：`base` 非空 && 两边 `bodyHlc` 都变化 && body 不同 →
  `{ id: phone.id+'-conflict', title: '[冲突] '+loser.title, body: loser.body, deleted:false, rev:1 }`，loser = bodyHlc 较小者，相同则 watch 为 loser。
- `edit_vs_delete` 语义：**删除胜，但正文仍按字段级 LWW 取 watch 的较新 body**（共享用例已固定 `{title:"A", body:"W", deleted:true}`）。

### 4.2 HLC（`hlc.js`）

- 契约格式 `<13位毫秒>-<4位计数器>-<deviceId>`；比较先 `t` 再 `c` 最后 `d` 字典序。
- `receive()` 按设计文档**附录 B.2**（保证单调）：远端 t 更大 → `(remote.t, remote.c+1)`；同 t → `(t, max(c,remote.c)+1)`；本地 t 更大 → `(t, c+1)`。
  （设计文档 §3.2 的伪代码在本地 t 更大时把 c 归零，会破坏单调性，本实现以附录 B 为准。）
- 4 位计数器用尽时 `t+1, c=0` 进位，仍然单调。
- 设备 id：首次启动生成 `w-<8 hex>` 持久化到 `device.json`；ID 生成 `m-<deviceId>-<seq>`，`seq` 持久化在 `index.json`（避免只用 `Math.random()` 重启重复）。

### 4.3 仓储与 outbox（`repo.js`）

- `storage` 注入：`readText(uri, cb(err,text|null)) / writeText(uri,text,cb) / deleteFile(uri,cb)`。
  设备侧由 `file-storage.js` 用 `@system.file` 实现；Node 测试用 `test/fakes.mjs` 内存文件系统。
- 写入顺序：本地修改 `outbox → memo → index`；应用 canonical `memo → outbox → index`（与设计文档 §3.5 一致）。
- 原子性：写文件前把旧内容写 `<uri>.bak`，再写主文件；读取 JSON 解析失败回退 `.bak`（无 rename 的退化方案）。
- 启动修复：以 outbox 为唯一真相——outbox 有记录但 index/memo 缺失 → 补 memo 文件、补 index 条目并置 `dirty=true`；index 中 `dirty=true` 但 outbox 没有记录 → 清 dirty。
- `ackOutbox(pushed)` 只在“推送后未再修改”时清 dirty：推送中用户继续编辑的新记录会保留到下次同步。

### 4.4 同步客户端（`sync.js`）

- 消息：`{v:1,t,sid,...}`；记录短键 `{id,ti,bo,c,u,th,bh,del,dh,rev,base}`。
- 手表只发 `req/push/pack`，只处理 `start/pull/done/err`；旧 `sid` 消息直接丢弃。
- 分片：按 JSON UTF-8 字节预算（默认 8192-256）切分 `push`/`pull` 的 `recs`。
- `done` 后：`ackOutbox(pushed)` → `setLastPullHlc(cur)` → 回 idle；失败/离线保留 outbox。
- 传输接口：`detect(cb(ok)) / send(obj, cb(err)) / onMessage(handler)`；真实实现见 `transport.js`。
- **未实现**：超时重发（`RETRY_LIMIT=3` 常量保留但 POC 未挂定时器）、多分片 pull 的并发/乱序缓存、手机主动 `sync.start` 的完整场景 B（骨架接受 `start`，但未做 ping→202 流程）。

---

## 5. Fit 3 408×480 布局与 crabKeyboard 适配

### 5.1 页面

- 所有页面宽度按 408、高度按 480 手工分配；触控按钮高度 ≥ 52px（≥44px 要求）。
- 列表：header 84 + list 348 + status 40 = 472；列表项 112px，一屏 3 条，只读 `index.json`。
- 详情：header 76 + title 48 + meta 28 + bodyList 232 + footer 76 + status 20 = 480；正文按 `\n` 拆成 `list-item`，避免超大 `text` 组件。
- 编辑：header 76 + panel 300 + footer 84 + status 20 = 480；显示标题/正文 + 字节数（键盘 `maxLen` 按 UTF-8 字节）。
- 颜色：黑底白字高对比；无动画、无圆角依赖（兼容 Lite CSS 子集，仅用 flex/left/top/width/height/background-color/border-radius/font-size/color/padding）。

### 5.2 键盘接入流程（关键）

```text
list “新建” → repo.createMemo → Router.replace edit(id)
edit “编辑正文”:
  1. Services.init 完成（repo 注入 HLC clock）
  2. repo.saveDraft(ctx) 写 draft.json（容灾）
  3. Storage.set('editCtx', JSON.stringify({memoId, field, original, maxLen}))
  4. Router.replace('/pages/keyboard_rect/keyboard', {targetPage:'/pages/edit/edit',
       targetKey:'draftText', maxLen, allowNewline: field=='body', keyType:0})
keyboard: done() -> Router.replace(edit, {draftText:'...', editAction:'done'})
          cancel() -> Router.replace(edit, {draftText:'', editAction:'cancel'})
edit.onInit（被 Router.replace 重建）:
  5. Services.init -> Storage.get('editCtx')（回调直接给字符串，兼容 {value} 包装）
  6. editAction=='cancel' -> 清上下文回 detail；否则 repo.saveField 后清上下文回 detail
  7. 保存成功后 requestSync('save')
```

`data` 里显式声明 `id / draftText / editAction`：Lite Wearable 只注入 data 中声明过的路由参数。

### 5.3 对 crabKeyboard `keyboard_rect` 的修改（MIT，均带 `[memo-sync-poc]` 注释）

上游 rect 版**本来就是按 408 宽设计**的（`keyboard.js` 里横滑动画偏移就是 `aniPos+408`，键盘图 570/741px 是可拖动的长图；文本列宽 370 = 408−19×2 也是对的）。真正需要改的只有：

| 位置 | 上游 | 改为 | 原因 |
|---|---|---|---|
| `keyboard.hml` pyShadow 图片宽度 | 466px | **408px** | 408 屏右侧溢出 58px（纯装饰阴影，挤压显示） |
| `keyboard.css` `.keyContainer` 高度 | 325px | **320px** | `top:160 + 325 = 485`，480 高屏底部溢出 5px |
| `keyboard.css` `.alert` 高度 | 420px | **380px** | 弹窗 `top:100 + 420 = 520` 底部溢出 40px |
| `keyboard.css` `.alert_list` 高度 | 290px | **250px** | 配合 alert 380 高度（100 bar + 250 list） |
| `keyboard.js` done()/cancel() | 只回传 targetKey | 增加 `editAction:'done'/'cancel'` | 区分“取消”和“清空后完成”（设计文档 §6.4） |
| `keyboard.js` 尺寸常量 | 散落 408/370 | `SCREEN_W=408` / `TEXT_W=SCREEN_W-38` / `numOfLists=TEXT_W/30` | 显式参数化，便于以后换屏型；行为不变 |

**没有改**：键盘图片宽度 570/741（那是图片本身宽度，横向拖动 `keyPos` 边界 `-162/-333` 由 570−408、741−408 得出，与 408 屏自洽）；confirm 按钮 `left:35 / left:293`（35+94=129、293+80=373，都在 408 内）；候选栏/工具栏/删除键（`left:318+85=403`）都在 408 内。

**仍存在的风险/未验证项**（真机 spike 要确认）：

1. 无真机/预览器：408×480 下键盘实际观感、候选栏滚动、删除键边缘 5px 余量未截图验证。
2. pyShadow 从 466 压到 408 是横向非等比压缩（0.875×），纯装饰、不影响功能，但阴影可能略变形。
3. 键盘 `keyboard.hml` 里 `<slider>`（模拟表冠）在 Fit 3 上是否有表冠/滑动映射未验证。
4. crabKeyboard 要求 `compileSdkVersion 7`；Fit 3 固件 API 版本与 API 7 的兼容性**未验证**（设计文档 §1.4 把这条列为最大风险；倒数日 rect408entry 是 compatible=3/target=6）。若固件不支持，需要降编译目标并重测键盘。
5. 字体：键盘按 `Device.getInfo().apiVersion` 切 `HarmonyOSHans-Medium / HarmonyOSSans-Bold / HarmonyOS_Sans_SCGB2312-Bold`，Fit 3 实际命中哪个字体未验证。
6. 剪切板 `shear.json`、输入偏好 `pyref.json` 是键盘自带能力，POC 未接业务剪切板。

---

## 6. 在 DevEco 中打开

本项目是**传统 Lite Wearable（FA 模型）Gradle 工程**（与 crabKeyboard 相同），不是 DevEco 5 的 stage/hvigor 工程：

1. 安装 **DevEco Studio 3.x**（3.0/3.1；能识别 `com.huawei.ohos:hap` Gradle 工程的版本）；
2. SDK Manager 安装 **API 7 Lite Wearable SDK**（`compileSdkVersion 7`）；
3. `File > Open…` 选择 `memo-sync-poc/watch` 目录（根目录有 `build.gradle` / `settings.gradle`）；
4. 等待 Gradle sync（需要访问 `repo.huaweicloud.com` / `developer.huawei.com/repo` 拉 `com.huawei.ohos:hap:3.1.5.0`）；
5. 配置自动签名/调试证书（手表 HAP 必须签名才能安装；`supportLists` 里的手机证书指纹见 §8）；
6. 连接 Fit 3（开发者模式 + 华为运动健康），`entry` 运行。

DevEco 5.0 + hvigor **不能**直接打开/构建本项目（见 §7）。如果只想看页面代码，不需要构建，直接读 `entry/src/main/js/default/**`。

---

## 7. 构建阻塞（本次实测记录）

### 7.1 尝试 1：hvigor（DevEco 5.0.13 自带 hvigor 5.17.2）

```powershell
# 在临时副本里补了 hvigor/hvigor-config.json5 + hvigorfile.ts 后执行：
node "D:\Program Files\Huawei\DevEco Studio\tools\hvigor\bin\hvigorw.js" assembleHap --no-daemon
# 输出：
# Error: spawn C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe ENOENT
```

结论：hvigor 启动器在本会话环境里就挂了（它依赖 `wmic`/`powershell.exe` 子进程探测系统语言等，被环境限制），**还没走到项目解析**。即使能启动，hvigor 5.17 的 `hvigor-ohos-plugin` 是 **stage 模型**构建器（需要 `app.json5`/`module.json5`/`build-profile.json5`/`oh-package.json5`），而 Lite Wearable 只有 **FA 模型**（`entry/src/main/config.json`），无法用它产出 liteWearable HAP。

### 7.2 尝试 2：Gradle（上游同款方式）

未实际执行（会先触发下载/签名问题，且缺 SDK），阻塞点：

1. 本机 **没有 API 7 Lite Wearable SDK**：`D:\Program Files\Huawei\DevEco Studio\sdk\default\openharmony` 只有 **API 17 / 5.0.5.165**；`sdk/default` 下无旧版 `hms`/`liteWearable` SDK 目录。
2. 本机 Gradle 缓存只有 8.0.2 / 9.3.1 / 9.4.0（`D:\Android\Gradle\wrapper\dists`），**没有 crabKeyboard wrapper 用的 Gradle 7.3**；首次 `gradlew` 需联网下载约 100MB + `com.huawei.ohos:hap:3.1.5.0` 插件。
3. HAP 安装需要签名证书；`supportLists` / `setFingerprint` 需要手机 APK 证书 SHA-256，本 POC 未做证书链路（设计文档 §7.1）。
4. Fit 3 固件对 API 7 / `compatibleSdkVersion 7` 的兼容性未验证（设计文档 §1.4 的 spike 项）。

**要真正构建**：装 DevEco Studio 3.x + API 7 Lite Wearable SDK → 用 §6 的 Gradle 方式 → 配调试签名 → 连 Fit 3 安装。或者按设计文档 §11 先做“最小 HAP + 键盘页” spike。

### 7.3 未做的检查

- 真机/模拟器 UI 截图、键盘实际输入、返回流程、中文候选、超长文本：本环境无 Fit 3 设备/预览器，**全部未验证**。
- 真实手机-手表 P2P（Wear Engine）：未联调（手机端不在本任务范围）。
- HAP 包体：flash 词库 1,136,195B + keyboard 素材约 137KB + 业务代码，符合“flash 方案约 3.5MB”预期路径，但未实际打包测量。

---

## 8. 需要替换的占位符

| 位置 | 占位符 | 替换为 |
|---|---|---|
| `config.json` `module.metaData.customizeData[name=supportLists].value` | `com.example.memo:<PHONE_APK_CERT_SHA256_PLACEHOLDER>` | 手机端包名 + 手机 APK 证书 SHA-256（与厂商文档格式对齐） |
| `common/memo/config.js` `PEER.PHONE_PKG` | `com.example.memo` | 手机 App 包名 |
| `common/memo/config.js` `PEER.PHONE_CERT` | `<PHONE_APK_CERT_SHA256_PLACEHOLDER>` | 手机 APK 证书 SHA-256（debug/release 两套） |
| `config.json` `app.bundleName` | `com.example.memo.hmw` | 你的手表 HAP 包名（需与手机端 `setPeerPkgName` 一致） |

设备 id 不需要手工填：首次启动生成 `w-<8hex>` 并写入 `internal://app/device.json`。

---

## 9. 与共享契约的对应关系

| PROTOCOL 要求 | 本目录实现 |
|---|---|
| 手表 config：`liteWearable` / `rect` / `408*480` / `round=false` | `entry/src/main/config.json` + `common/memo/config.js`（`ROUND=false, SCREEN_W=408, SCREEN_H=480`） |
| 使用 `keyboard_rect` | `KEYBOARD_PAGE='/pages/keyboard_rect/keyboard'`；config.json 只注册 rect 键盘页 |
| `index.json` + `memo_<id>.json` + `outbox.json` 仓储（fake storage 可测） | `common/memo/repo.js` + `test/fakes.mjs` |
| HLC 13 位 t / 4 位 c / device | `common/memo/hlc.js`（单测覆盖） |
| 合并契约全量 cases | `test/run-merge-cases.mjs` → `out/results.json` |
| 可 inject 的 storage/transport | `repo.js` storage 注入；`sync.js` transport 注入；页面侧真实适配在 `file-storage.js` / `transport.js` |
| README 写清真实/mock/命令 | 本文件 |
