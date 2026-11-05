/*
 * memo-sync-poc / watch — Node 测试用 fake storage / transport / clock
 *
 * 这些只在 Node 测试里用，不进入 HAP 包（不打包 resources，Gradle 只认 js/default 与 resources）。
 */

import Hlc from '../entry/src/main/js/default/common/memo/hlc.js';

/*
 * 内存文件系统，模拟 @system.file 的异步回调接口：
 *   readText(uri, cb(err, text|null))
 *   writeText(uri, text, cb(err))
 *   deleteFile(uri, cb(err))
 */
export function createFakeStorage(initialFiles) {
    const files = Object.create(null);
    if (initialFiles) {
        for (const key of Object.keys(initialFiles)) files[key] = initialFiles[key];
    }
    const writes = [];

    const storage = {
        readText(uri, callback) {
            const text = Object.prototype.hasOwnProperty.call(files, uri) ? files[uri] : null;
            callback(null, text);
        },
        writeText(uri, text, callback) {
            files[uri] = String(text);
            writes.push(uri);
            if (callback) callback(null);
        },
        deleteFile(uri, callback) {
            delete files[uri];
            if (callback) callback(null);
        }
    };

    return {
        storage,
        files,
        writes,
        read(uri) {
            return Object.prototype.hasOwnProperty.call(files, uri) ? files[uri] : null;
        },
        has(uri) {
            return Object.prototype.hasOwnProperty.call(files, uri);
        },
        corrupt(uri, text) {
            files[uri] = text;
        }
    };
}

/*
 * 假 transport：记录 sent 消息，把收到的消息分发给 onMessage 订阅者，
 * setPeer(handler) 可挂一个“手机端模拟器”在 send 时同步回应。
 */
export function createFakeTransport(options) {
    const handlers = [];
    const sent = [];
    const opts = options || {};
    let detectResult = opts.detect === undefined ? true : opts.detect;
    let peer = null;

    const transport = {
        detect(callback) {
            callback(!!detectResult);
        },
        send(message, callback) {
            sent.push(message);
            if (peer) peer(message);
            if (callback) callback(null);
        },
        onMessage(handler) {
            handlers.push(handler);
        },
        receive(message) {
            for (let i = 0; i < handlers.length; i++) handlers[i](message);
        },
        sent,
        setPeer(handler) {
            peer = handler;
        },
        setDetectResult(ok) {
            detectResult = ok;
        },
        resetSent() {
            sent.length = 0;
        }
    };
    return transport;
}

// 确定性 HLC 时钟：每次 next() 把物理时间推进 tick 毫秒
export function createFakeClock(deviceId, options) {
    const opts = options || {};
    let now = opts.start === undefined ? 1000 : opts.start;
    const tick = opts.tick === undefined ? 1000 : opts.tick;
    return Hlc.createClock({
        deviceId: deviceId || 'w-00000000',
        now() {
            now += tick;
            return now;
        }
    });
}
