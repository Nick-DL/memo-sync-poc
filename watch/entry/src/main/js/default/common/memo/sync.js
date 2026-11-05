/*
 * memo-sync-poc / watch — 同步客户端骨架（transport 注入，可在 Node 下用 fake transport 测试）
 *
 * 角色：手表 = client / 离线编辑器。手表不做合并，只负责：
 *   1) 把 outbox 里的 dirty 记录 push 给手机（分片）
 *   2) 接收手机下发的 canonical pull，逐条 applyCanonical
 *   3) 收到 done 后清 outbox / 更新 lastPullHlc
 *
 * 消息信封（shared/PROTOCOL.md 对应的设计文档 §4.3）:
 *   {v:1, t:'req'|'start'|'push'|'pack'|'pull'|'done'|'err', sid, ...}
 * 记录短键: {id,ti,bo,c,u,th,bh,del,dh,rev,base}
 *
 * 传输接口（由页面侧 transport.js 适配 FeatureAbility，测试侧注入 fake）:
 *   transport.detect(cb(ok))                 // 手机端 App 是否在运行
 *   transport.send(obj, cb(err))             // 发送一条 JSON 消息
 *   transport.onMessage(handler)             // 注册收消息回调（App 级，只注册一次）
 *
 * 本文件不 import 兄弟模块（同一份源码要同时给 Node ESM 和 Lite Wearable 用）。
 */

var VERSION = 1;
var MESSAGE_BUDGET = 8192;     // 单条 P2P 消息目标上限（设计 §4.5）
var ENVELOPE_RESERVE = 256;    // 信封/sid/字段名预留
var RETRY_LIMIT = 3;

function utf8Bytes(str) {
    if (str === null || str === undefined) return 0;
    var s = String(str);
    var total = 0;
    for (var i = 0; i < s.length; i++) {
        var code = s.charCodeAt(i);
        if (code < 0x80) total += 1;
        else if (code < 0x800) total += 2;
        else if (code >= 0xd800 && code <= 0xdbff) {
            var next = i + 1 < s.length ? s.charCodeAt(i + 1) : 0;
            if (next >= 0xdc00 && next <= 0xdfff) { total += 4; i++; } else total += 3;
        } else {
            total += 3;
        }
    }
    return total;
}

// 完整记录 -> 短键记录（手表 push 使用；base = 上次从手机收到的 canonical HLC）
function encodeRecord(record) {
    var out = {
        id: record.id,
        ti: record.title === null || record.title === undefined ? '' : record.title,
        bo: record.body === null || record.body === undefined ? '' : record.body,
        c: record.created,
        u: record.updated,
        th: record.titleHlc,
        bh: record.bodyHlc,
        del: record.deleted === true ? 1 : 0,
        dh: record.deletedHlc === undefined ? null : record.deletedHlc,
        rev: record.rev || 0
    };
    if (record.base !== undefined && record.base !== null) out.base = record.base;
    return out;
}

// 短键记录 -> 完整记录
function decodeRecord(record) {
    return {
        id: record.id,
        title: record.ti === null || record.ti === undefined ? '' : record.ti,
        body: record.bo === null || record.bo === undefined ? '' : record.bo,
        created: record.c,
        updated: record.u,
        titleHlc: record.th,
        bodyHlc: record.bh,
        deleted: record.del === 1 || record.del === true,
        deletedHlc: record.dh === undefined ? null : record.dh,
        rev: record.rev || 0
    };
}

// 按 JSON 字节预算把短键记录分片；空数组 -> []
function splitEncoded(encodedRecords, budget) {
    var limit = budget || (MESSAGE_BUDGET - ENVELOPE_RESERVE);
    var chunks = [];
    var current = [];
    var size = 2; // "[]"
    for (var i = 0; i < encodedRecords.length; i++) {
        var record = encodedRecords[i];
        var recordSize = utf8Bytes(JSON.stringify(record)) + 1; // +1 = 逗号
        if (current.length > 0 && size + recordSize > limit) {
            chunks.push(current);
            current = [];
            size = 2;
        }
        current.push(record);
        size += recordSize;
    }
    if (current.length > 0) chunks.push(current);
    return chunks;
}

function createClient(options) {
    if (!options || !options.repo) throw new Error('sync: options.repo is required');
    if (!options.transport) throw new Error('sync: options.transport is required');

    var repo = options.repo;
    var transport = options.transport;
    var deviceId = options.deviceId || (typeof repo.getDeviceId === 'function' ? repo.getDeviceId() : null);
    var onState = typeof options.onState === 'function' ? options.onState : function () {};
    var onError = typeof options.onError === 'function' ? options.onError : function () {};

    var phase = 'idle';
    var sessionId = null;
    var pushChunks = [];
    var pushIndex = 0;
    var pushed = [];       // 本次会话推送快照 [{id, updatedHlc}]
    var ackCount = 0;
    var nextCursor = null;
    var lastError = null;
    var lastReason = null;

    function setPhase(next, reason) {
        phase = next;
        lastReason = reason || lastReason;
        onState(getState());
    }

    function notifyError(error) {
        lastError = error || { code: 'unknown', message: 'unknown sync error' };
        onError(lastError);
    }

    function sendEnvelope(message, cb) {
        transport.send(message, function (err) {
            cb(err || null);
        });
    }

    function fail(err) {
        var message = err && err.message ? err.message : String(err);
        sessionId = null;
        pushChunks = [];
        pushIndex = 0;
        pushed = [];
        ackCount = 0;
        notifyError({ code: 'transport_error', message: message });
        setPhase('idle', 'error');
    }

    function resetSessionState() {
        sessionId = null;
        pushChunks = [];
        pushIndex = 0;
        pushed = [];
        ackCount = 0;
        nextCursor = null;
    }

    function startPushing() {
        var records = repo.getOutbox();
        pushed = [];
        var encoded = [];
        for (var i = 0; i < records.length; i++) {
            pushed.push({ id: records[i].id, updatedHlc: records[i].updated });
            encoded.push(encodeRecord(records[i]));
        }
        pushChunks = splitEncoded(encoded, MESSAGE_BUDGET - ENVELOPE_RESERVE);
        pushIndex = 0;
        ackCount = 0;
        if (pushChunks.length === 0) {
            // 没有 dirty：直接等手机 pull
            setPhase('waiting_pull');
            return;
        }
        setPhase('pushing');
        sendNextPush();
    }

    function sendNextPush() {
        if (!sessionId) return; // 会话已结束（done 可能先于 send 回调到达，不能被旧回调带偏状态）
        if (pushIndex >= pushChunks.length) {
            setPhase('waiting_pull');
            return;
        }
        var index = pushIndex;
        sendEnvelope({
            v: VERSION,
            t: 'push',
            sid: sessionId,
            i: index,
            n: pushChunks.length,
            recs: pushChunks[index]
        }, function (err) {
            if (err) { fail(err); return; }
            pushIndex += 1;
            if (sessionId) sendNextPush();
        });
    }

    function applySequentially(records, index, cb) {
        if (index >= records.length) { cb(null); return; }
        var record = records[index];
        if (!record || !record.id) { applySequentially(records, index + 1, cb); return; }
        repo.applyCanonical(record, function (err) {
            if (err) { cb(err); return; }
            applySequentially(records, index + 1, cb);
        });
    }

    function handleStart(message) {
        if (!message.sid) { notifyError({ code: 'protocol_error', message: 'start without sid' }); return; }
        // 只接受「请求中 / 等待 start / 空闲（手机主动发起）」三种状态
        if (phase !== 'idle' && phase !== 'requesting' && phase !== 'waiting_start') return;
        sessionId = message.sid;
        startPushing();
    }

    function handlePack(message) {
        if (!sessionId || message.sid !== sessionId) return; // 旧 session 丢弃
        ackCount += 1;
        if (ackCount >= pushChunks.length && phase === 'pushing') setPhase('waiting_pull');
    }

    function handlePull(message) {
        if (!sessionId || message.sid !== sessionId) return;
        var recs = message.recs instanceof Array ? message.recs : [];
        if (message.next) nextCursor = message.next;
        var decoded = [];
        for (var i = 0; i < recs.length; i++) decoded.push(decodeRecord(recs[i]));
        setPhase('pulling');
        applySequentially(decoded, 0, function (err) {
            if (err) { fail(err); return; }
            // 先进入 waiting_done 再发 pack：真实链路里 done 可能在 pack 回调前到达，这里不依赖回调顺序
            setPhase('waiting_done');
            sendEnvelope({ v: VERSION, t: 'pack', sid: sessionId, i: message.i || 0 }, function (err2) {
                if (err2) fail(err2);
            });
        });
    }

    function handleDone(message) {
        if (!sessionId || message.sid !== sessionId) return;
        var cursor = message.cur || nextCursor;
        var snapshot = pushed.slice(0);
        repo.ackOutbox(snapshot, function (ackErr) {
            if (ackErr) {
                notifyError({ code: 'repo_error', message: String(ackErr) });
                setPhase('idle', 'error');
                return;
            }
            var finish = function (err) {
                if (err) notifyError({ code: 'repo_error', message: String(err) });
                resetSessionState();
                setPhase('idle', 'done');
            };
            if (cursor) repo.setLastPullHlc(cursor, finish);
            else finish(null);
        });
    }

    function handleError(message) {
        notifyError({
            code: message.code || 'protocol_error',
            message: message.msg || message.message || '手机端返回错误'
        });
        resetSessionState();
        setPhase('idle', 'error');
    }

    function onMessage(message) {
        if (!message || typeof message !== 'object') return;
        if (message.v !== VERSION) {
            notifyError({ code: 'bad_version', message: '协议版本不兼容: v=' + message.v });
            return;
        }
        switch (message.t) {
            case 'start': handleStart(message); break;
            case 'pack': handlePack(message); break;
            case 'pull': handlePull(message); break;
            case 'done': handleDone(message); break;
            case 'err': handleError(message); break;
            default: break; // 未知类型忽略（前向兼容）
        }
    }

    function getState() {
        return {
            phase: phase,
            sessionId: sessionId,
            reason: lastReason,
            error: lastError,
            pending: typeof repo.getOutboxCount === 'function' ? repo.getOutboxCount() : 0
        };
    }

    var client = {
        // 手表主动请求同步（打开 App / 保存后 / 手动同步）
        request: function (reason) {
            lastReason = reason || 'request';
            if (phase !== 'idle') return false; // 已有会话：调用方稍后重试（POC 不排队）
            lastError = null;
            resetSessionState();
            setPhase('detecting');
            transport.detect(function (phoneReady) {
                if (!phoneReady) {
                    notifyError({ code: 'phone_offline', message: '请先打开手机端 App' });
                    setPhase('idle', 'offline');
                    return;
                }
                setPhase('requesting');
                sendEnvelope({
                    v: VERSION,
                    t: 'req',
                    dev: deviceId,
                    cur: typeof repo.getLastPullHlc === 'function' ? repo.getLastPullHlc() : null,
                    dirty: typeof repo.getOutboxCount === 'function' ? repo.getOutboxCount() : 0
                }, function (err) {
                    if (err) { fail(err); return; }
                    // 只从 requesting 前进；若会话已经跑完（假 transport/极快链路），不要回退状态
                    if (phase === 'requesting') setPhase('waiting_start');
                });
            });
            return true;
        },

        onMessage: onMessage,
        getState: getState,
        isBusy: function () { return phase !== 'idle'; },
        reset: function () {
            resetSessionState();
            lastError = null;
            setPhase('idle', 'reset');
        }
    };

    if (typeof transport.onMessage === 'function') transport.onMessage(onMessage);
    return client;
}

export default {
    VERSION: VERSION,
    MESSAGE_BUDGET: MESSAGE_BUDGET,
    RETRY_LIMIT: RETRY_LIMIT,
    createClient: createClient,
    encodeRecord: encodeRecord,
    decodeRecord: decodeRecord,
    splitEncoded: splitEncoded
};
