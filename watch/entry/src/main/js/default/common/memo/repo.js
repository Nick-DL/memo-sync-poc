/*
 * memo-sync-poc / watch — 本地仓储（纯 JS，storage 注入，可在 Node 下用 fake storage 测试）
 *
 * 文件布局（shared/PROTOCOL.md §3.3 / 设计文档 §3.4）:
 *   internal://app/index.json      列表索引（id/title/preview/updatedHlc/deleted/dirty/baseHlc）
 *   internal://app/memo_<id>.json  单条完整记录（正文按条分文件，避免一个大 JSON）
 *   internal://app/outbox.json     重试队列（dirty 的唯一真相来源）
 *   internal://app/device.json     设备 id（首次启动生成 w-xxxxxxxx 并持久化）
 *   internal://app/draft.json      键盘上下文 / 草稿（路由返回后恢复现场）
 *
 * 存储接口（由页面侧 file-storage.js 适配 @system.file，测试侧注入内存 fake）:
 *   storage.readText(uri, cb(err, text|null))
 *   storage.writeText(uri, text, cb(err))
 *   storage.deleteFile(uri, cb(err))        // 可选
 *
 * 写入顺序（设计 §3.5）:
 *   本地修改: outbox -> memo -> index
 *   canonical: memo -> outbox -> index
 *   每个文件写入前先把旧内容写到 <uri>.bak；读取 JSON 失败时回退 .bak。
 *
 * 本文件不 import 兄弟模块（同一份源码要同时给 Node ESM 和 Lite Wearable 用），
 * 需要的小工具（copyRecord/preview/randomHex）都在文件内。
 */

var FILES = {
    INDEX: 'internal://app/index.json',
    OUTBOX: 'internal://app/outbox.json',
    DEVICE: 'internal://app/device.json',
    DRAFT: 'internal://app/draft.json'
};

var SCHEMA = 1;

function memoUri(id) {
    return 'internal://app/memo_' + id + '.json';
}

function copyRecord(record) {
    if (!record) return null;
    var out = {};
    for (var key in record) {
        if (Object.prototype.hasOwnProperty.call(record, key)) out[key] = record[key];
    }
    return out;
}

function previewOf(body) {
    var s = body === null || body === undefined ? '' : String(body);
    s = s.replace(/\r\n/g, ' ').replace(/[\r\n\t]/g, ' ').replace(/\s+/g, ' ');
    return s.length > 60 ? s.slice(0, 59) + '…' : s;
}

function randomHex8() {
    var hex = '';
    for (var i = 0; i < 8; i++) hex += Math.floor(Math.random() * 16).toString(16);
    return hex;
}

function createRepo(options) {
    if (!options || !options.storage) {
        throw new Error('repo: options.storage is required');
    }
    var storage = options.storage;
    var clock = options.clock || null;
    var loaded = false;
    var deviceId = null;
    var index = null;   // index.json 的内存态
    var outbox = null;  // outbox.json 的内存态

    // ---------------- storage 小工具 ----------------

    function storageRead(uri, cb) {
        storage.readText(uri, function (err, text) {
            cb(err || null, (text === undefined || text === null) ? null : text);
        });
    }

    function storageWrite(uri, text, cb) {
        storage.writeText(uri, text, function (err) {
            cb(err || null);
        });
    }

    // 读 JSON；主文件损坏时回退 <uri>.bak（设计 §3.5 的 repair 路径）
    function readJson(uri, cb) {
        storageRead(uri, function (err, text) {
            if (err) { cb(err, null); return; }
            if (!text) { cb(null, null); return; }
            var parsed;
            var broken = false;
            try { parsed = JSON.parse(text); } catch (e) { broken = true; }
            if (!broken) { cb(null, parsed); return; }
            storageRead(uri + '.bak', function (err2, backup) {
                if (err2 || !backup) { cb(null, null); return; }
                try { cb(null, JSON.parse(backup)); } catch (e2) { cb(null, null); }
            });
        });
    }

    // 写 JSON：先把旧内容备份到 .bak，再写主文件（@system.file 没有 rename 时的退化方案）
    function writeJson(uri, value, cb) {
        var text = JSON.stringify(value);
        storageRead(uri, function (err, previous) {
            if (err) { cb(err); return; }
            var writeMain = function () {
                storageWrite(uri, text, function (err2) { cb(err2 || null); });
            };
            if (previous) {
                storageWrite(uri + '.bak', previous, function () { writeMain(); });
            } else {
                writeMain();
            }
        });
    }

    // ---------------- index / outbox 内存操作 ----------------

    function findIndexEntry(id) {
        if (!index) return null;
        for (var i = 0; i < index.memos.length; i++) {
            if (index.memos[i].id === id) return index.memos[i];
        }
        return null;
    }

    function findOutboxRecord(id) {
        if (!outbox) return null;
        for (var i = 0; i < outbox.records.length; i++) {
            if (outbox.records[i].id === id) return outbox.records[i];
        }
        return null;
    }

    function removeOutboxRecord(id) {
        if (!outbox) return false;
        for (var i = 0; i < outbox.records.length; i++) {
            if (outbox.records[i].id === id) {
                outbox.records.splice(i, 1);
                return true;
            }
        }
        return false;
    }

    function setOutboxRecord(record) {
        for (var i = 0; i < outbox.records.length; i++) {
            if (outbox.records[i].id === record.id) {
                outbox.records[i] = record;
                return;
            }
        }
        outbox.records.push(record);
    }

    function indexEntryFromRecord(record, dirty, baseHlc) {
        return {
            id: record.id,
            title: record.title || '',
            preview: previewOf(record.body),
            createdHlc: record.created,
            updatedHlc: record.updated,
            deleted: record.deleted === true,
            dirty: dirty === true,
            rev: record.rev || 0,
            baseHlc: baseHlc === undefined ? null : baseHlc
        };
    }

    // 同步 index 条目（保留 baseHlc，dirty 由调用方设置）
    function upsertIndexEntry(record) {
        var entry = findIndexEntry(record.id);
        if (!entry) {
            entry = indexEntryFromRecord(record, true, null);
            index.memos.push(entry);
            return entry;
        }
        entry.title = record.title || '';
        entry.preview = previewOf(record.body);
        entry.createdHlc = record.created;
        entry.updatedHlc = record.updated;
        entry.deleted = record.deleted === true;
        entry.rev = record.rev || 0;
        return entry;
    }

    function ensureLoaded(cb) {
        if (!loaded) {
            cb(new Error('repo: init() has not completed'));
            return false;
        }
        return true;
    }

    // ---------------- 写路径 ----------------

    // 本地新建/编辑/删除：outbox -> memo -> index（设计 §3.5）
    function persistLocal(record, cb) {
        var oldEntry = findIndexEntry(record.id);
        var baseHlc = oldEntry ? oldEntry.baseHlc : null;
        var entry = upsertIndexEntry(record);
        entry.dirty = true;

        var outRecord = copyRecord(record);
        outRecord.base = baseHlc === undefined ? null : baseHlc;
        setOutboxRecord(outRecord);

        writeJson(FILES.OUTBOX, outbox, function (err) {
            if (err) { cb(err); return; }
            writeJson(memoUri(record.id), record, function (err2) {
                if (err2) { cb(err2); return; }
                writeJson(FILES.INDEX, index, function (err3) { cb(err3 || null); });
            });
        });
    }

    // 应用手机合并后的 canonical：memo -> outbox -> index
    function applyCanonical(record, cb) {
        if (!ensureLoaded(cb)) return;
        if (!record || !record.id) {
            cb(new Error('repo.applyCanonical: record.id is required'));
            return;
        }
        var rec = copyRecord(record);
        if (rec.title === undefined || rec.title === null) rec.title = '';
        if (rec.body === undefined || rec.body === null) rec.body = '';
        if (rec.deletedHlc === undefined) rec.deletedHlc = null;

        var entry = upsertIndexEntry(rec);
        var outRecord = findOutboxRecord(rec.id);
        var stillDirty = false;
        if (outRecord) {
            // outbox 里存的是完整 memo 记录，HLC 字段名是 updated（不是 updatedHlc）
            // HLC 是定宽编码（t 13 位 / c 4 位），字典序即时间序，可直接比较字符串
            if (outRecord.updated <= rec.updated) {
                removeOutboxRecord(rec.id);
            } else {
                // canonical 到达前本地又改了：保留新 dirty，下次再推
                stillDirty = true;
            }
        }
        entry.dirty = stillDirty;
        entry.baseHlc = rec.updated;

        writeJson(memoUri(rec.id), rec, function (err) {
            if (err) { cb(err); return; }
            writeJson(FILES.OUTBOX, outbox, function (err2) {
                if (err2) { cb(err2); return; }
                writeJson(FILES.INDEX, index, function (err3) { cb(err3 || null); });
            });
        });
    }

    // ---------------- 启动修复 ----------------

    // 以 outbox 为准修复 index/memo（设计 §3.5 最后一段）
    function repair(cb) {
        var records = outbox.records;
        var dirtyIds = {};
        var repairedIndex = false;
        var cursor = 0;

        function finish() {
            for (var i = 0; i < index.memos.length; i++) {
                var entry = index.memos[i];
                if (entry.dirty && !dirtyIds[entry.id]) {
                    entry.dirty = false;
                    repairedIndex = true;
                }
            }
            if (!repairedIndex) { cb(null); return; }
            writeJson(FILES.INDEX, index, function (err) { cb(err || null); });
        }

        function step() {
            if (cursor >= records.length) { finish(); return; }
            var record = records[cursor++];
            if (!record || !record.id) { step(); return; }
            dirtyIds[record.id] = true;

            var entry = findIndexEntry(record.id);
            if (!entry) {
                var stored = copyRecord(record);
                delete stored.base;
                index.memos.push(indexEntryFromRecord(stored, true, record.base === undefined ? null : record.base));
                repairedIndex = true;
            } else if (!entry.dirty) {
                entry.dirty = true;
                repairedIndex = true;
            }

            readJson(memoUri(record.id), function (err, memo) {
                if (err) { cb(err); return; }
                if (!memo) {
                    // 崩溃在 outbox 之后、memo 之前：用 outbox 里的完整记录补回 memo 文件
                    var rebuilt = copyRecord(record);
                    delete rebuilt.base;
                    writeJson(memoUri(record.id), rebuilt, function (err2) {
                        if (err2) { cb(err2); return; }
                        step();
                    });
                } else {
                    step();
                }
            });
        }

        step();
    }

    // ---------------- 对外 API ----------------

    var repo = {
        init: function (cb) {
            readJson(FILES.DEVICE, function (err, device) {
                if (err) { cb(err); return; }

                var proceed = function (id) {
                    deviceId = id;
                    readJson(FILES.INDEX, function (err2, loadedIndex) {
                        if (err2) { cb(err2); return; }
                        var createdIndex = false;
                        if (!loadedIndex || loadedIndex.schema !== SCHEMA || !(loadedIndex.memos instanceof Array)) {
                            loadedIndex = {
                                schema: SCHEMA,
                                deviceId: id,
                                seq: 0,
                                lastPullHlc: null,
                                memos: []
                            };
                            createdIndex = true;
                        }
                        loadedIndex.deviceId = id;
                        if (typeof loadedIndex.seq !== 'number') loadedIndex.seq = 0;
                        if (loadedIndex.lastPullHlc === undefined) loadedIndex.lastPullHlc = null;
                        index = loadedIndex;

                        readJson(FILES.OUTBOX, function (err3, loadedOutbox) {
                            if (err3) { cb(err3); return; }
                            var createdOutbox = false;
                            if (!loadedOutbox || loadedOutbox.schema !== SCHEMA || !(loadedOutbox.records instanceof Array)) {
                                loadedOutbox = { schema: SCHEMA, records: [] };
                                createdOutbox = true;
                            }
                            outbox = loadedOutbox;
                            repair(function (err4) {
                                if (err4) { cb(err4); return; }
                                // 首次启动：把内存里的空 index/outbox 落盘，页面/测试随后可直接读到文件
                                var writeOutbox = function (next) {
                                    if (!createdOutbox) { next(); return; }
                                    writeJson(FILES.OUTBOX, outbox, function (writeErr) {
                                        if (writeErr) { cb(writeErr); return; }
                                        next();
                                    });
                                };
                                var writeIndex = function () {
                                    if (!createdIndex) { loaded = true; cb(null); return; }
                                    writeJson(FILES.INDEX, index, function (writeErr) {
                                        if (writeErr) { cb(writeErr); return; }
                                        loaded = true;
                                        cb(null);
                                    });
                                };
                                writeOutbox(writeIndex);
                            });
                        });
                    });
                };

                if (device && typeof device.deviceId === 'string' && device.deviceId.length > 0) {
                    proceed(device.deviceId);
                } else {
                    var newId = 'w-' + randomHex8();
                    writeJson(FILES.DEVICE, { schema: SCHEMA, deviceId: newId }, function (err5) {
                        if (err5) { cb(err5); return; }
                        proceed(newId);
                    });
                }
            });
        },

        isLoaded: function () { return loaded; },
        getDeviceId: function () { return deviceId; },
        setClock: function (hlcClock) { clock = hlcClock; },
        getClock: function () { return clock; },

        // ---- index ----
        listMemos: function () {
            if (!loaded) return [];
            var out = [];
            for (var i = 0; i < index.memos.length; i++) {
                var entry = index.memos[i];
                if (entry.deleted) continue; // POC：列表不显示 tombstone（没有回收站页面）
                out.push(copyRecord(entry));
            }
            out.sort(function (a, b) {
                if (a.updatedHlc === b.updatedHlc) return 0;
                return a.updatedHlc < b.updatedHlc ? 1 : -1; // 新 -> 旧
            });
            return out;
        },

        getIndexEntry: function (id) {
            var entry = findIndexEntry(id);
            return entry ? copyRecord(entry) : null;
        },

        getLastPullHlc: function () {
            return index ? index.lastPullHlc : null;
        },

        setLastPullHlc: function (hlc, cb) {
            if (!ensureLoaded(cb)) return;
            index.lastPullHlc = (hlc === undefined) ? null : hlc;
            writeJson(FILES.INDEX, index, function (err) { if (cb) cb(err || null); });
        },

        // ---- memo CRUD ----
        getMemo: function (id, cb) {
            if (!ensureLoaded(cb)) return;
            readJson(memoUri(id), function (err, record) {
                if (err) { cb(err, null); return; }
                cb(null, record || null);
            });
        },

        createMemo: function (data, cb) {
            if (!ensureLoaded(cb)) return;
            if (!clock) { cb(new Error('repo.createMemo: clock not injected (call setClock) ')); return; }
            var now;
            try { now = clock.next(); } catch (e) { cb(e); return; }

            var seq = (index.seq || 0) + 1;
            index.seq = seq;
            var record = {
                id: 'm-' + deviceId + '-' + seq,
                title: (data && data.title) ? String(data.title) : '',
                body: (data && data.body) ? String(data.body) : '',
                created: now,
                updated: now,
                titleHlc: now,
                bodyHlc: now,
                deleted: false,
                deletedHlc: null,
                rev: 1
            };
            persistLocal(record, function (err) {
                if (err) { cb(err, null); return; }
                cb(null, copyRecord(record));
            });
        },

        saveField: function (id, field, value, cb) {
            if (!ensureLoaded(cb)) return;
            if (field !== 'title' && field !== 'body') {
                cb(new Error('repo.saveField: field must be "title" or "body", got ' + field));
                return;
            }
            if (!clock) { cb(new Error('repo.saveField: clock not injected (call setClock)')); return; }
            repo.getMemo(id, function (err, record) {
                if (err) { cb(err, null); return; }
                if (!record) { cb(new Error('repo.saveField: memo not found: ' + id), null); return; }
                var now = clock.next();
                record[field] = (value === null || value === undefined) ? '' : String(value);
                record[field === 'title' ? 'titleHlc' : 'bodyHlc'] = now;
                record.updated = now;
                record.rev = (record.rev || 0) + 1;
                persistLocal(record, function (err2) {
                    if (err2) { cb(err2, null); return; }
                    cb(null, copyRecord(record));
                });
            });
        },

        deleteMemo: function (id, cb) {
            if (!ensureLoaded(cb)) return;
            if (!clock) { cb(new Error('repo.deleteMemo: clock not injected (call setClock)')); return; }
            repo.getMemo(id, function (err, record) {
                if (err) { cb(err, null); return; }
                if (!record) { cb(new Error('repo.deleteMemo: memo not found: ' + id), null); return; }
                var now = clock.next();
                record.deleted = true;
                record.deletedHlc = now;
                record.updated = now;
                record.rev = (record.rev || 0) + 1;
                persistLocal(record, function (err2) {
                    if (err2) { cb(err2, null); return; }
                    cb(null, copyRecord(record));
                });
            });
        },

        applyCanonical: applyCanonical,

        // ---- outbox ----
        getOutbox: function () {
            if (!outbox) return [];
            var out = [];
            for (var i = 0; i < outbox.records.length; i++) out.push(copyRecord(outbox.records[i]));
            return out;
        },

        getOutboxCount: function () {
            return outbox ? outbox.records.length : 0;
        },

        // 收到 done 后调用：pushed = [{id, updatedHlc}, ...] 是本次推送出去的快照
        ackOutbox: function (pushed, cb) {
            if (!ensureLoaded(cb)) return;
            var list = pushed instanceof Array ? pushed : [];
            var changed = false;
            for (var i = 0; i < list.length; i++) {
                var item = list[i] || {};
                var queued = findOutboxRecord(item.id);
                if (!queued) continue;
                // outbox 存完整记录（updated），推送快照用 updatedHlc 命名，两者都按 HLC 字符串比较
                if (queued.updated === item.updatedHlc) {
                    removeOutboxRecord(item.id);
                    var entry = findIndexEntry(item.id);
                    if (entry) { entry.dirty = false; changed = true; }
                }
                // 推送之后本地又改过（updatedHlc 变了）：保留 dirty，下次再推
            }
            if (!changed) { if (cb) cb(null); return; }
            writeJson(FILES.OUTBOX, outbox, function (err) {
                if (err) { if (cb) cb(err); return; }
                writeJson(FILES.INDEX, index, function (err2) { if (cb) cb(err2 || null); });
            });
        },

        clearOutbox: function (cb) {
            if (!ensureLoaded(cb)) return;
            outbox.records = [];
            for (var i = 0; i < index.memos.length; i++) index.memos[i].dirty = false;
            writeJson(FILES.OUTBOX, outbox, function (err) {
                if (err) { if (cb) cb(err); return; }
                writeJson(FILES.INDEX, index, function (err2) { if (cb) cb(err2 || null); });
            });
        },

        // ---- draft / 键盘上下文 ----
        saveDraft: function (draft, cb) {
            writeJson(FILES.DRAFT, draft || {}, function (err) { if (cb) cb(err || null); });
        },

        loadDraft: function (cb) {
            readJson(FILES.DRAFT, function (err, draft) {
                if (err) { cb(err, null); return; }
                if (!draft || !draft.memoId) { cb(null, null); return; }
                cb(null, draft);
            });
        },

        clearDraft: function (cb) {
            if (typeof storage.deleteFile === 'function') {
                storage.deleteFile(FILES.DRAFT, function (err) { if (cb) cb(err || null); });
            } else {
                writeJson(FILES.DRAFT, {}, function (err) { if (cb) cb(err || null); });
            }
        }
    };

    return repo;
}

export default {
    create: createRepo,
    FILES: FILES,
    memoUri: memoUri
};
