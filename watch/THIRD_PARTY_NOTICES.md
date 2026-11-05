# 第三方声明 / THIRD-PARTY NOTICES

本 POC 的手表键盘页面与相关素材来自开源项目 **crabKeyboard**（MIT License），
在 `memo-sync-poc/watch` 内做了 408×480（Huawei Watch Fit 3 矩形屏）适配和 `editAction` 小补丁。

## crabKeyboard

- 上游仓库：https://github.com/crabPangXie/crabKeyboard （本地参考副本：`daysmatter-analysis/refs/crabKeyboard`）
- 版本：1.1.5（2026-10-05）
- 作者：crabKun、kqakqakqa
- 许可证：MIT License（全文见 `entry/src/main/js/default/common/keyboard/LICENSE`）

被复制的部分：

```text
entry/src/main/js/default/pages/keyboard_rect/keyboard.{hml,css,js}   # rect 键盘页面（Fit 3 用）
entry/src/main/js/default/common/keyboard/                             # 键盘公共素材 + lookupDictV5.js
entry/src/main/resources/rawfile/pinyinDictV5_flash1001/               # flash 轻量词库（1,136,195 字节）
entry/src/main/resources/base/media/icon.png / icon_small.png          # 应用图标（来自同一上游示例工程）
```

本项目对上游源码的修改（均保留在文件内以 `[memo-sync-poc patch]` 注释标记）：

1. `keyboard_rect/keyboard.js`：`done()` / `cancel()` 回传参数增加 `editAction: 'done' | 'cancel'`；
2. `keyboard_rect/keyboard.js`：显式声明 `SCREEN_W=408` / `TEXT_W=SCREEN_W-38`，用 `SCREEN_W` 替换动画偏移 408；
3. `keyboard_rect/keyboard.hml`：`pyShadow` 图片宽度 466px → 408px（408 屏右侧会溢出 58px）；
4. `keyboard_rect/keyboard.css`：`keyContainer` 高度 325 → 320；`alert` 高度 420 → 380；`alert_list` 290 → 250
   （480 高屏底部溢出修复）。

```text
MIT License

Copyright (c) 2026 kqakqakqa
Copyright (c) 2026 crabKun

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
