# Memo Sync POC — 共享协议与测试契约

这是“手机 + HarmonyOS Lite Wearable 双向备忘录”测试性开发的共同契约。  
目标不是完整产品，而是先证明：

1. 手机/手表两端能用同一套 HLC + 合并规则得到一致结果；
2. 手表端能在 **Fit 3（矩形 408×480，`round=false`）** 上组织页面并接入 crabKeyboard；
3. 两端各自的仓储/同步状态机可以独立测试；
4. 后续替换真实 Wear Engine 传输时，业务层不需要改。

> 设备更正：测试设备是 **Huawei Watch Fit 3**，不是 GT 3。  
> `round=false`，屏幕 **408×480**，键盘用 `keyboard_rect`。

---

## 1. 范围

本 POC 只做纯文本 memo：

```text
id, title, body
created, updated
titleHlc, bodyHlc
deleted, deletedHlc
rev
```

不做富文本、图片、附件、多手表、云端。

HLC 固定格式：

```text
<13位毫秒>-<4位计数器>-<deviceId>
例如：0000000001000-0000-p
```

比较规则：先 `t`，再 `c`，最后 `d` 字典序。

---

## 2. 合并契约

输入：

- `base`：上次同步的 canonical 记录，可能为 null；
- `phone`：手机当前记录，可能为 null；
- `watch`：手表当前记录，可能为 null。

输出：

```json
{
  "canonical": { ...MemoRecord... },
  "conflict": { ...MemoRecord... } | null
}
```

POC 规则（故意简化，但方向与正式设计一致）：

1. 一边为 null：另一边直接作为 canonical。
2. 两边都有：
   - 每个字段取 HLC 较大者：
     - `title` 用 `titleHlc`
     - `body` 用 `bodyHlc`
     - `deleted` 用 `deletedHlc`；未删除时用 `updated` 参与比较
   - 相同 HLC、不同内容：手机侧优先（POC 的确定性 tie-break）。
   - `created` 取较小 HLC。
   - `updated` 取两边 `updated` 的较大值。
   - `rev = max(phone.rev, watch.rev) + 1`。
3. 冲突副本：
   - 如果 `base` 非空，且 `phone.bodyHlc != base.bodyHlc`、`watch.bodyHlc != base.bodyHlc`、两边 body 不同，则生成 conflict：
     - `id = phone.id + '-conflict'`
     - `title = '[冲突] ' + loser.title`
     - `body = loser.body`
     - `deleted = false`
     - `rev = 1`
   - loser 是 HLC 较小的一边；HLC 相同则 watch 为 loser。
4. 删除/编辑冲突：
   - 若一边 `deleted=true`，比较其 `deletedHlc` 与另一边 `updated`：
     - 编辑方 `updated` 更大 -> 编辑胜，`deleted=false`，清空 `deletedHlc`；
     - 删除方 `deletedHlc` 更大 -> 删除胜。

---

## 3. 测试契约

两端都必须提供“读 `shared/merge_cases.json`，输出 `results.json`”的能力：

```json
{
  "results": [
    {
      "name": "watch_create",
      "canonical": { "title": "W", "body": "watch", "deleted": false },
      "conflict": null
    }
  ]
}
```

只比较 `canonical.title`、`canonical.body`、`canonical.deleted` 和 `conflict` 的 `title/body`；HLC/rev 由各自单测覆盖。

### 3.1 手表侧（JS/Node）

- 纯逻辑模块必须能在 Node 下 import（ESM 或 CommonJS 均可）；
- 提供 `node watch/test/run-merge-cases.mjs`（或等价命令），读共享 cases，输出 results；
- 页面/仓储使用可注入的 storage/transport，Node 测试用 fake 实现。

### 3.2 手机侧（Java 17 或 Kotlin/JVM）

- 环境里 `kotlinc` 不在 PATH；优先用 **Java 17 + javac** 保证可编译，Android 层保留 Kotlin 源码骨架；
- 如果 Gradle/Kotlin 能快速跑通也可以用 Kotlin，但必须提供一条可复现的测试命令；
- 提供 CLI/main，读共享 cases，输出 results；JSON 解析可用 Gson（放到 `phone/lib/`）或自带最小解析器。

### 3.3 集成验收

Lead 会运行两端 CLI，逐 case 对比输出；同时检查：

- 手表 `config.json` 是 `rect` / `408*480` / `round=false`；
- 手表键盘页使用 `keyboard_rect`；
- 手表有 `index.json` + `memo_<id>.json` + `outbox.json` 的仓储实现（可用 fake storage 测）；
- 手机有 HLC、merge、协议状态机的最少实现和测试；
- 两端 `README` 写清楚“哪些是真的、哪些是 mock、怎么跑”。

---

## 4. 非目标

- 不要求本 POC 真的连上 Fit 3 或华为运动健康；
- 不要求完整 Android APK / 手表 HAP 可安装（能构建更好，构建不了要记录原因）；
- 不要求 UI 精美；页面结构能表达 list/detail/edit/keyboard 即可。

---

## 5. 目录与写入边界

```text
memo-sync-poc/
  shared/        Lead 维护，子 agent 只读
  watch/         watch-poc 写入
  phone/         phone-poc 写入
  tools/         Lead 写入
  README.md      Lead 写入
```

子 agent 不要修改 `shared/`、对方目录或 `daysmatter-analysis/` 下的设计文档。
