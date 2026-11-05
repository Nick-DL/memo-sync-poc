/*
 * memo-sync-poc / watch — shared constants
 *
 * 测试设备: Huawei Watch Fit 3 (Lite Wearable, rect 408x480) —— 不是 GT 3。
 * 之后如果要加圆形表盘设备，只改 ROUND 即可（crabKeyboard 另有 keyboard_circle）。
 *
 * 本文件是纯数据模块：不 import 任何 @system.*，因此可以在 Node 下直接 import。
 */

var ROUND = false; // Fit 3 = 矩形，必须用 keyboard_rect
var SCREEN_W = 408;
var SCREEN_H = 480;
var KEYBOARD_PAGE = ROUND
    ? '/pages/keyboard_circle/keyboard'
    : '/pages/keyboard_rect/keyboard';

var PAGES = {
    LIST: '/pages/list/list',
    DETAIL: '/pages/detail/detail',
    EDIT: '/pages/edit/edit'
};

// 路由参数 targetKey / data 字段名（edit 页与键盘页共用）
var KEYBOARD_TARGET_KEY = 'draftText';
var KEYBOARD_EDIT_ACTION_KEY = 'editAction';

// 字节上限（键盘 maxLen 按 UTF-8 字节统计）
var LIMIT = {
    TITLE_BYTES: 64,
    BODY_BYTES: 6144,
    PROTOCOL_MESSAGE_BYTES: 8192 // 单条 P2P 消息目标上限（设计文档 4.5）
};

var FILE = {
    ROOT: 'internal://app/',
    INDEX: 'internal://app/index.json',
    OUTBOX: 'internal://app/outbox.json',
    DEVICE: 'internal://app/device.json',
    DRAFT: 'internal://app/draft.json',
    SHEAR: 'internal://app/shear.json'
};

function memoFile(id) {
    return FILE.ROOT + 'memo_' + id + '.json';
}

var PROTOCOL_VERSION = 1;

// Wear Engine P2P 对端身份（手机 App 包名 + 手机 APK 证书 SHA-256）
// 必须与 config.json 的 metaData.supportLists = "<包名>:<指纹>"、手机端 setPeerPkgName/setPeerFingerPrint 一致。
// debug 与 release 证书不同，联调时按实际签名替换。
var PEER = {
    PHONE_PKG: 'com.example.memo',
    PHONE_CERT: '<PHONE_APK_CERT_SHA256_PLACEHOLDER>'
};

export default {
    ROUND: ROUND,
    SCREEN_W: SCREEN_W,
    SCREEN_H: SCREEN_H,
    KEYBOARD_PAGE: KEYBOARD_PAGE,
    PAGES: PAGES,
    PEER: PEER,
    KEYBOARD_TARGET_KEY: KEYBOARD_TARGET_KEY,
    KEYBOARD_EDIT_ACTION_KEY: KEYBOARD_EDIT_ACTION_KEY,
    LIMIT: LIMIT,
    FILE: FILE,
    memoFile: memoFile,
    PROTOCOL_VERSION: PROTOCOL_VERSION
};
