/*
 * memo-sync-poc / watch — HLC (Hybrid Logical Clock)
 *
 * 契约（shared/PROTOCOL.md §1）:
 *   固定格式 "<13位毫秒>-<4位计数器>-<deviceId>"
 *   例: "0000000001000-0000-p"
 *   比较规则: 先 t，再 c，最后 d 字典序。
 *
 * 生成/接收规则按设计文档附录 B.2（保证单调）：
 *   next(now):            now > t ? (now, 0) : (t, c + 1)
 *   receive(remote):      remote.t > t ? (remote.t, remote.c + 1)
 *                       : remote.t == t ? (t, max(c, remote.c) + 1)
 *                       : (t, c + 1)
 * 注意：设计文档 §3.2 的伪代码 receive() 在本地 t 更大时把 c 归零，会破坏单调性，
 * 这里以附录 B 的参考实现为准（README 有说明）。
 *
 * ES5.1 风格实现 + ESM 导出，可在 Node 下直接 import。
 */

var HLC_RE = /^(\d{13})-(\d{4})-(.+)$/;
var MAX_T = 9999999999999; // 13 位
var MAX_C = 9999;          // 4 位

function HlcError(message) {
    this.name = 'HlcError';
    this.message = message;
    if (Error.captureStackTrace) Error.captureStackTrace(this, HlcError);
}
HlcError.prototype = Object.create(Error.prototype);
HlcError.prototype.constructor = HlcError;

function padLeft(value, width) {
    var s = String(value);
    while (s.length < width) s = '0' + s;
    return s;
}

function parse(value) {
    if (typeof value !== 'string') {
        throw new HlcError('HLC must be a string, got ' + typeof value + ': ' + value);
    }
    var m = HLC_RE.exec(value);
    if (!m) {
        throw new HlcError('Invalid HLC (want <13-digit ms>-<4-digit counter>-<deviceId>): ' + value);
    }
    return {
        t: parseInt(m[1], 10),
        c: parseInt(m[2], 10),
        d: m[3]
    };
}

function format(t, c, d) {
    if (typeof t !== 'number' || !isFinite(t) || Math.floor(t) !== t || t < 0 || t > MAX_T) {
        throw new HlcError('Invalid HLC t: ' + t);
    }
    if (typeof c !== 'number' || Math.floor(c) !== c || c < 0 || c > MAX_C) {
        throw new HlcError('Invalid HLC c: ' + c);
    }
    if (typeof d !== 'string' || d.length === 0) {
        throw new HlcError('Invalid HLC device id: ' + d);
    }
    return padLeft(t, 13) + '-' + padLeft(c, 4) + '-' + d;
}

// 返回 -1 / 0 / 1
function compare(a, b) {
    var pa = typeof a === 'string' ? parse(a) : a;
    var pb = typeof b === 'string' ? parse(b) : b;
    if (pa.t !== pb.t) return pa.t < pb.t ? -1 : 1;
    if (pa.c !== pb.c) return pa.c < pb.c ? -1 : 1;
    if (pa.d === pb.d) return 0;
    return pa.d < pb.d ? -1 : 1;
}

function max(a, b) {
    return compare(a, b) >= 0 ? a : b;
}

function min(a, b) {
    return compare(a, b) <= 0 ? a : b;
}

// 判断字符串是否是合法 HLC
function isHlc(value) {
    if (typeof value !== 'string') return false;
    return HLC_RE.test(value);
}

/*
 * 可注入 now() 的时钟。
 *   var clock = Hlc.createClock({ deviceId: 'w-a1b2c3d4', now: function () { return Date.now(); } });
 *   clock.next();            // 生成下一个 HLC
 *   clock.receive(remote);   // 收到远端 HLC 后推进本地时钟，返回新 HLC
 *   clock.peek();            // 查看当前 HLC（不推进）
 */
function createClock(options) {
    if (!options || typeof options.deviceId !== 'string' || options.deviceId.length === 0) {
        throw new HlcError('createClock: options.deviceId is required');
    }
    var deviceId = options.deviceId;
    var nowFn = typeof options.now === 'function' ? options.now : function () { return Date.now(); };
    var t = 0;
    var c = 0;

    function bumpCounter() {
        c += 1;
        if (c > MAX_C) {
            // 4 位计数器用尽：把物理时间推进 1ms 再重置计数器（仍然单调）
            t += 1;
            c = 0;
        }
    }

    return {
        deviceId: deviceId,
        next: function (nowMs) {
            var now = (nowMs === undefined || nowMs === null) ? nowFn() : nowMs;
            if (now > t) {
                t = now;
                c = 0;
            } else {
                bumpCounter();
            }
            return format(t, c, deviceId);
        },
        receive: function (remote) {
            var r = parse(remote);
            if (r.t > t) {
                t = r.t;
                c = r.c + 1;
            } else if (r.t === t) {
                c = Math.max(c, r.c) + 1;
            } else {
                c += 1;
            }
            if (c > MAX_C) {
                t += 1;
                c = 0;
            }
            return format(t, c, deviceId);
        },
        peek: function () {
            return format(t, c, deviceId);
        },
        state: function () {
            return { t: t, c: c, d: deviceId };
        }
    };
}

export default {
    HlcError: HlcError,
    parse: parse,
    format: format,
    compare: compare,
    max: max,
    min: min,
    isHlc: isHlc,
    createClock: createClock
};
