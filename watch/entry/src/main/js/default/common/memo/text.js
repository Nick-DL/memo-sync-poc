/*
 * memo-sync-poc / watch — 纯文本工具
 *
 * 轻手表 JS 引擎按 ES5.1 约束写（var/function），同时保留 ESM 导出以便 Node 测试。
 * 不 import 任何 @system.*。
 *
 * 为什么需要它：
 * - crabKeyboard 的 maxLen 按 UTF-8 字节计（中文约 3 字节/字），业务页要先算字节；
 * - 列表预览要截断并去掉换行；
 * - 手表字体可能不支持 emoji，这里不做删除（避免丢数据），只负责计数/截断。
 */

// 返回字符串的 UTF-8 字节数（正确处理 surrogate pair，emoji = 4 字节）
function utf8Bytes(str) {
    if (str === null || str === undefined) return 0;
    var s = String(str);
    var total = 0;
    for (var i = 0; i < s.length; i++) {
        var code = s.charCodeAt(i);
        if (code < 0x80) {
            total += 1;
        } else if (code < 0x800) {
            total += 2;
        } else if (code >= 0xd800 && code <= 0xdbff) {
            var next = i + 1 < s.length ? s.charCodeAt(i + 1) : 0;
            if (next >= 0xdc00 && next <= 0xdfff) {
                total += 4;
                i++;
            } else {
                total += 3; // 孤立的高位代理，按替换字符计
            }
        } else {
            total += 3; // 中文等 BMP 字符 / 孤立低位代理
        }
    }
    return total;
}

// 按 UTF-8 字节截断字符串（不切断代理对），用于硬性限制
function truncateBytes(str, maxBytes) {
    if (str === null || str === undefined) return '';
    var s = String(str);
    if (maxBytes === 0 || maxBytes === null || maxBytes === undefined) return s;
    var total = 0;
    var out = '';
    for (var i = 0; i < s.length; i++) {
        var code = s.charCodeAt(i);
        var width;
        if (code < 0x80) width = 1;
        else if (code < 0x800) width = 2;
        else if (code >= 0xd800 && code <= 0xdbff) {
            var next = i + 1 < s.length ? s.charCodeAt(i + 1) : 0;
            width = (next >= 0xdc00 && next <= 0xdfff) ? 4 : 3;
        } else width = 3;
        if (total + width > maxBytes) break;
        total += width;
        out += s.charAt(i);
        if (width === 4 && i + 1 < s.length) {
            out += s.charAt(i + 1);
            i++;
        }
    }
    return out;
}

// 列表预览：去换行/制表符，压成一行，按字节截断（超出加 …）
function preview(str, maxBytes) {
    if (str === null || str === undefined) return '';
    var s = String(str).replace(/\r\n/g, ' ').replace(/[\r\n\t]/g, ' ').replace(/\s+/g, ' ');
    var limit = maxBytes || 60;
    if (utf8Bytes(s) <= limit) return s;
    return truncateBytes(s, Math.max(1, limit - 3)) + '…';
}

// 手表 UI 上的「字数」显示，例如 "12 字 / 36 B"
function lengthLabel(str) {
    var s = str === null || str === undefined ? '' : String(str);
    return s.length + ' 字 / ' + utf8Bytes(s) + ' B';
}

export default {
    utf8Bytes: utf8Bytes,
    truncateBytes: truncateBytes,
    preview: preview,
    lengthLabel: lengthLabel
};
