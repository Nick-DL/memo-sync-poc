#!/usr/bin/env node
/*
 * memo-sync-poc / watch — 单元 + 冒烟测试（无外部依赖，node:test 都不用，直接 assert）
 *
 * 覆盖：
 *   HLC            : 格式/比较/next/receive/计数器溢出/非法输入
 *   text           : UTF-8 字节计数 / 截断 / 预览
 *   config         : Fit 3 408x480 / keyboard_rect 常量
 *   merge          : shared/merge_cases.json 全量 + tie-break / 删除规则 / 冲突副本单测
 *   repo           : deviceId/index/memo/outbox/draft/原子写回退/启动修复
 *   sync (fake)    : 短键编解码 / 8KB 分片 / req->start->push->pull->done 冒烟 / 离线/错误/旧会话
 *   静态检查        : config.json 契约 / 页面与键盘文件齐全 / keyboard_rect 408 适配与 editAction patch
 *
 * 用法：node watch/test/run-tests.mjs（或 npm test）
 */

import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import Hlc from '../entry/src/main/js/default/common/memo/hlc.js';
import Text from '../entry/src/main/js/default/common/memo/text.js';
import Config from '../entry/src/main/js/default/common/memo/config.js';
import Merge from '../entry/src/main/js/default/common/memo/merge.js';
import Repo from '../entry/src/main/js/default/common/memo/repo.js';
import Sync from '../entry/src/main/js/default/common/memo/sync.js';
import { createFakeStorage, createFakeTransport, createFakeClock } from './fakes.mjs';

const here = dirname(fileURLToPath(import.meta.url)); // watch/test
const watchRoot = resolve(here, '..');               // watch
const casesPath = resolve(watchRoot, '..', 'shared', 'merge_cases.json');

let passed = 0;
const failures = [];

function test(name, fn) {
    try {
        fn();
        passed += 1;
        console.log(`[PASS] ${name}`);
    } catch (err) {
        failures.push({ name, err });
        const message = err && err.stack ? err.stack.split('\n')[0] : String(err);
        console.error(`[FAIL] ${name}: ${message}`);
    }
}

function group(name) {
    console.log(`\n== ${name} ==`);
}

// 同步调用仓库 API 并收集回调结果（fake storage 的回调是同步执行的）
function call(invoke) {
    const result = { called: false, err: undefined, value: undefined };
    invoke(function (err, value) {
        result.called = true;
        result.err = err;
        result.value = value;
    });
    return result;
}

function setupRepo() {
    const fake = createFakeStorage();
    const repo = Repo.create({ storage: fake.storage });
    const init = call((cb) => repo.init(cb));
    assert.equal(init.err, null, 'repo.init failed: ' + init.err);
    repo.setClock(createFakeClock(repo.getDeviceId()));
    return { fake, repo };
}

function baseRecord(overrides) {
    return Object.assign({
        id: 'm1',
        title: 'A',
        body: 'X',
        created: '0000000001000-0000-p',
        updated: '0000000001000-0000-p',
        titleHlc: '0000000001000-0000-p',
        bodyHlc: '0000000001000-0000-p',
        deleted: false,
        deletedHlc: null,
        rev: 1
    }, overrides || {});
}

// ============================================================
group('HLC');
// ============================================================

test('HLC format/parse 固定宽度往返', () => {
    assert.equal(Hlc.format(1000, 7, 'p'), '0000000001000-0007-p');
    assert.deepEqual(Hlc.parse('0000000001000-0007-p'), { t: 1000, c: 7, d: 'p' });
    assert.equal(Hlc.format(1729312345678, 3, 'w-a1b2c3d4'), '1729312345678-0003-w-a1b2c3d4');
    assert.equal(Hlc.parse('1729312345678-0003-w-a1b2c3d4').d, 'w-a1b2c3d4');
});

test('HLC compare 先 t 再 c 最后 d 字典序', () => {
    assert.equal(Hlc.compare('0000000001000-0000-p', '0000000001000-0000-w'), -1);
    assert.equal(Hlc.compare('0000000001000-0000-w', '0000000001000-0000-p'), 1);
    assert.equal(Hlc.compare('0000000001000-0002-p', '0000000001000-0002-p'), 0);
    assert.ok(Hlc.compare('0000000001000-0001-p', '0000000001000-0000-w') > 0);
    assert.ok(Hlc.compare('0000000002000-0000-p', '0000000001000-0009-z') > 0);
});

test('HLC next 在时钟回拨下严格单调', () => {
    const sequence = [1000, 2000, 1500, 1500, 1200];
    let cursor = 0;
    const clock = Hlc.createClock({
        deviceId: 'w-1',
        now() { const value = sequence[cursor]; cursor += 1; return value; }
    });
    const stamps = [clock.next(), clock.next(), clock.next(), clock.next(), clock.next()];
    assert.equal(stamps[0], '0000000001000-0000-w-1');
    assert.equal(stamps[1], '0000000002000-0000-w-1');
    assert.equal(stamps[2], '0000000002000-0001-w-1');
    for (let i = 1; i < stamps.length; i++) {
        assert.ok(Hlc.compare(stamps[i - 1], stamps[i]) < 0, `${stamps[i - 1]} 应 < ${stamps[i]}`);
    }
});

test('HLC receive 按附录 B 推进且保持单调', () => {
    const clock = Hlc.createClock({ deviceId: 'w-1', now: () => 1000 });
    assert.equal(clock.next(), '0000000001000-0000-w-1');
    assert.equal(clock.receive('0000000003000-0002-p'), '0000000003000-0003-w-1');
    assert.equal(clock.receive('0000000002000-0009-p'), '0000000003000-0004-w-1');
    const nextOlder = clock.next(500);
    assert.ok(Hlc.compare('0000000003000-0004-w-1', nextOlder) < 0);
});

test('HLC 4 位计数器溢出时进位 t', () => {
    const clock = Hlc.createClock({ deviceId: 'w-1', now: () => 5000 });
    clock.next(); // t=5000 c=0
    let previous = clock.peek();
    for (let i = 0; i < 10001; i++) {
        const current = clock.next();
        assert.ok(Hlc.compare(previous, current) < 0, '计数器溢出后必须仍单调');
        previous = current;
    }
    const parts = Hlc.parse(previous);
    assert.ok(parts.t > 5000);
    assert.ok(parts.c <= 9999);
});

test('HLC 拒绝非法输入', () => {
    assert.throws(() => Hlc.parse('bad'), /Invalid HLC/);
    assert.throws(() => Hlc.parse('0000000001000-0000'), /Invalid HLC/);
    assert.throws(() => Hlc.format(1000, 10000, 'p'), /Invalid HLC c/);
    assert.throws(() => Hlc.format(1000, 0, ''), /Invalid HLC device id/);
    assert.equal(Hlc.isHlc('0000000001000-0000-p'), true);
    assert.equal(Hlc.isHlc('x'), false);
});

test('HLC max/min', () => {
    const a = '0000000001000-0000-p';
    const b = '0000000002000-0000-w';
    assert.equal(Hlc.max(a, b), b);
    assert.equal(Hlc.min(a, b), a);
});

// ============================================================
group('text / config');
// ============================================================

test('text utf8Bytes 中英文/emoji', () => {
    assert.equal(Text.utf8Bytes('abc'), 3);
    assert.equal(Text.utf8Bytes('中'), 3);
    assert.equal(Text.utf8Bytes('😀'), 4);
    assert.equal(Text.utf8Bytes('a中😀'), 8);
    assert.equal(Text.utf8Bytes(null), 0);
});

test('text truncateBytes 不切断代理对 / preview 去换行', () => {
    assert.equal(Text.truncateBytes('中文字', 4), '中');
    assert.ok(Text.utf8Bytes(Text.truncateBytes('中文字', 7)) <= 7);
    assert.equal(Text.preview('a\nb\tc', 20), 'a b c');
    assert.equal(Text.lengthLabel('中a'), '2 字 / 4 B');
});

test('config 常量符合 Fit 3 契约', () => {
    assert.equal(Config.ROUND, false);
    assert.equal(Config.SCREEN_W, 408);
    assert.equal(Config.SCREEN_H, 480);
    assert.equal(Config.KEYBOARD_PAGE, '/pages/keyboard_rect/keyboard');
    assert.equal(Config.KEYBOARD_TARGET_KEY, 'draftText');
    assert.equal(Config.LIMIT.TITLE_BYTES, 64);
    assert.equal(Config.LIMIT.BODY_BYTES, 6144);
    assert.equal(Config.memoFile('m-1'), 'internal://app/memo_m-1.json');
});

// ============================================================
group('merge');
// ============================================================

test('merge 契约：shared/merge_cases.json 全部通过', () => {
    const cases = JSON.parse(readFileSync(casesPath, 'utf8'));
    assert.ok(Array.isArray(cases) && cases.length > 0);
    for (const testCase of cases) {
        const outcome = Merge.merge(testCase.base || null, testCase.phone || null, testCase.watch || null);
        const canonical = outcome.canonical
            ? {
                title: outcome.canonical.title,
                body: outcome.canonical.body,
                deleted: outcome.canonical.deleted === true
            }
            : null;
        const conflict = outcome.conflict
            ? { title: outcome.conflict.title, body: outcome.conflict.body }
            : null;
        assert.deepEqual(canonical, testCase.expect.canonical, `case ${testCase.name} canonical`);
        assert.deepEqual(conflict, testCase.expect.conflict || null, `case ${testCase.name} conflict`);
    }
});

test('merge 相同 HLC 内容不同 -> 手机侧优先', () => {
    const h = '0000000002000-0000-x';
    const phone = baseRecord({ title: 'P', body: 'p', titleHlc: h, bodyHlc: h, updated: h });
    const watch = baseRecord({ title: 'W', body: 'w', titleHlc: h, bodyHlc: h, updated: h });
    const outcome = Merge.merge(null, phone, watch);
    assert.equal(outcome.canonical.title, 'P');
    assert.equal(outcome.canonical.body, 'p');
    assert.equal(outcome.conflict, null);
});

test('merge 冲突副本 HLC 相同 -> watch 为 loser', () => {
    const base = baseRecord({ body: 'B' });
    const h = '0000000003000-0000-x';
    const phone = baseRecord({ title: 'P', body: 'P', titleHlc: h, bodyHlc: h, updated: h, rev: 2 });
    const watch = baseRecord({ title: 'W', body: 'W', titleHlc: h, bodyHlc: h, updated: h, rev: 2 });
    const outcome = Merge.merge(base, phone, watch);
    assert.equal(outcome.canonical.body, 'P'); // tie -> phone
    assert.ok(outcome.conflict, '应生成冲突副本');
    assert.equal(outcome.conflict.title, '[冲突] W');
    assert.equal(outcome.conflict.body, 'W');
    assert.equal(outcome.conflict.deleted, false);
    assert.equal(outcome.conflict.rev, 1);
    assert.equal(outcome.conflict.id, 'm1-conflict');
});

test('merge 单边 null / 双方 null', () => {
    assert.equal(Merge.merge(null, null, null).canonical, null);
    const watch = baseRecord({ title: 'W', body: 'watch' });
    const onlyWatch = Merge.merge(null, null, watch).canonical;
    assert.equal(onlyWatch.title, 'W');
    assert.equal(onlyWatch.body, 'watch');
    assert.equal(onlyWatch.rev, 1); // 规则 1：直接作为 canonical，不 rev+1
    const phone = baseRecord({ title: 'P', body: 'phone' });
    const onlyPhone = Merge.merge(baseRecord({}), phone, null).canonical;
    assert.equal(onlyPhone.body, 'phone');
});

test('merge created 取小 / updated 取大 / rev=max+1', () => {
    const phone = baseRecord({
        created: '0000000001000-0000-p',
        updated: '0000000004000-0000-p',
        titleHlc: '0000000004000-0000-p',
        bodyHlc: '0000000001000-0000-p',
        rev: 5
    });
    const watch = baseRecord({
        created: '0000000002000-0000-w',
        updated: '0000000003000-0000-w',
        titleHlc: '0000000001000-0000-p',
        bodyHlc: '0000000003000-0000-w',
        rev: 2
    });
    const outcome = Merge.merge(baseRecord({}), phone, watch);
    assert.equal(outcome.canonical.created, '0000000001000-0000-p');
    assert.equal(outcome.canonical.updated, '0000000004000-0000-p');
    assert.equal(outcome.canonical.rev, 6);
});

test('merge 删除/编辑同 HLC -> 编辑优先（避免误删）', () => {
    const h = '0000000005000-0000-p';
    const phone = baseRecord({ deleted: true, deletedHlc: h, updated: h, rev: 2 });
    const watch = baseRecord({ deleted: false, deletedHlc: null, updated: h, rev: 2 });
    const outcome = Merge.merge(null, phone, watch);
    assert.equal(outcome.canonical.deleted, false);
    assert.equal(outcome.canonical.deletedHlc, null);
});

test('merge 双方删除 -> deleted=true 且取较大 deletedHlc', () => {
    const phone = baseRecord({ deleted: true, deletedHlc: '0000000003000-0000-p', updated: '0000000003000-0000-p', rev: 2 });
    const watch = baseRecord({ deleted: true, deletedHlc: '0000000004000-0000-w', updated: '0000000004000-0000-w', rev: 2 });
    const outcome = Merge.merge(null, phone, watch);
    assert.equal(outcome.canonical.deleted, true);
    assert.equal(outcome.canonical.deletedHlc, '0000000004000-0000-w');
});

// ============================================================
group('repo / outbox');
// ============================================================

test('repo.init 生成并持久化 deviceId（二次 init 复用）', () => {
    const { fake, repo } = setupRepo();
    const deviceId = repo.getDeviceId();
    assert.match(deviceId, /^w-[0-9a-f]{8}$/);
    const deviceFile = JSON.parse(fake.read(Repo.FILES.DEVICE));
    assert.equal(deviceFile.deviceId, deviceId);
    const index = JSON.parse(fake.read(Repo.FILES.INDEX));
    assert.equal(index.schema, 1);
    assert.deepEqual(index.memos, []);

    const repo2 = Repo.create({ storage: fake.storage });
    assert.equal(call((cb) => repo2.init(cb)).err, null);
    assert.equal(repo2.getDeviceId(), deviceId);
});

test('repo.createMemo 写 index + memo + outbox，写顺序 outbox->memo->index', () => {
    const { fake, repo } = setupRepo();
    const created = call((cb) => repo.createMemo({ title: '购物清单', body: '牛奶\n鸡蛋' }, cb));
    assert.equal(created.err, null);
    const record = created.value;
    assert.equal(record.id, 'm-' + repo.getDeviceId() + '-1');
    assert.equal(record.rev, 1);
    assert.equal(record.deleted, false);
    assert.equal(record.deletedHlc, null);
    assert.ok(Hlc.isHlc(record.created));
    assert.equal(record.created, record.updated);
    assert.equal(record.titleHlc, record.created);
    assert.equal(record.bodyHlc, record.created);

    assert.ok(fake.has(Repo.memoUri(record.id)), 'memo_<id>.json 应存在');
    const outbox = JSON.parse(fake.read(Repo.FILES.OUTBOX));
    assert.equal(outbox.records.length, 1);
    assert.equal(outbox.records[0].id, record.id);
    assert.equal(outbox.records[0].base, null);
    const index = JSON.parse(fake.read(Repo.FILES.INDEX));
    assert.equal(index.memos.length, 1);
    assert.equal(index.memos[0].dirty, true);
    assert.equal(index.memos[0].preview, '牛奶 鸡蛋');
    assert.equal(repo.getOutboxCount(), 1);

    const outboxPos = fake.writes.indexOf(Repo.FILES.OUTBOX);
    const memoPos = fake.writes.indexOf(Repo.memoUri(record.id));
    const indexPos = fake.writes.lastIndexOf(Repo.FILES.INDEX);
    assert.ok(outboxPos >= 0 && memoPos > outboxPos && indexPos > memoPos, '写顺序应为 outbox -> memo -> index');
    assert.equal(repo.listMemos().length, 1);
});

test('repo.saveField 更新字段 HLC/rev/outbox，不影响其他字段 HLC', () => {
    const { fake, repo } = setupRepo();
    const record = call((cb) => repo.createMemo({ title: 'A', body: 'X' }, cb)).value;
    const saved = call((cb) => repo.saveField(record.id, 'title', '今天', cb));
    assert.equal(saved.err, null);
    const updated = saved.value;
    assert.equal(updated.title, '今天');
    assert.ok(Hlc.compare(record.titleHlc, updated.titleHlc) < 0);
    assert.equal(updated.bodyHlc, record.bodyHlc);
    assert.equal(updated.rev, 2);
    assert.equal(updated.updated, updated.titleHlc);
    assert.equal(repo.getOutboxCount(), 1);
    const outbox = JSON.parse(fake.read(Repo.FILES.OUTBOX));
    assert.equal(outbox.records[0].updated, updated.updated);
});

test('repo.saveField 正文更新预览与字段', () => {
    const { repo } = setupRepo();
    const record = call((cb) => repo.createMemo({ title: 'A', body: 'X' }, cb)).value;
    const saved = call((cb) => repo.saveField(record.id, 'body', '第一行\n第二行', cb)).value;
    assert.equal(saved.body, '第一行\n第二行');
    const entry = repo.getIndexEntry(record.id);
    assert.equal(entry.preview, '第一行 第二行');
    assert.equal(entry.dirty, true);
});

test('repo.saveField 参数校验', () => {
    const { repo } = setupRepo();
    const record = call((cb) => repo.createMemo({ title: 'A' }, cb)).value;
    const outcome = call((cb) => repo.saveField(record.id, 'bodyx', 'x', cb));
    assert.ok(outcome.err instanceof Error, '非法字段名应回调错误');
    assert.match(outcome.err.message, /field must be/);
});

test('repo.deleteMemo 软删除并从列表消失', () => {
    const { repo } = setupRepo();
    const record = call((cb) => repo.createMemo({ title: 'A', body: 'X' }, cb)).value;
    const deleted = call((cb) => repo.deleteMemo(record.id, cb)).value;
    assert.equal(deleted.deleted, true);
    assert.ok(Hlc.isHlc(deleted.deletedHlc));
    assert.equal(deleted.updated, deleted.deletedHlc);
    assert.equal(repo.listMemos().length, 0);
    assert.equal(repo.getIndexEntry(record.id).deleted, true);
    assert.equal(repo.getOutboxCount(), 1);
});

test('repo.applyCanonical 覆盖本地并清 dirty/outbox/baseHlc', () => {
    const { repo } = setupRepo();
    const record = call((cb) => repo.createMemo({ title: 'A', body: 'X' }, cb)).value;
    const canonicalUpdated = '0000000009000-0000-p';
    const canonical = {
        id: record.id,
        title: 'A',
        body: 'canonical-body',
        created: record.created,
        updated: canonicalUpdated,
        titleHlc: record.titleHlc,
        bodyHlc: canonicalUpdated,
        deleted: false,
        deletedHlc: null,
        rev: 3
    };
    assert.equal(call((cb) => repo.applyCanonical(canonical, cb)).err, null);
    const memo = call((cb) => repo.getMemo(record.id, cb)).value;
    assert.equal(memo.body, 'canonical-body');
    assert.equal(memo.rev, 3);
    assert.equal(repo.getOutboxCount(), 0, 'canonical 到达后应清掉已推送的 outbox');
    const entry = repo.getIndexEntry(record.id);
    assert.equal(entry.dirty, false);
    assert.equal(entry.baseHlc, canonicalUpdated);
    assert.equal(entry.updatedHlc, canonicalUpdated);
});

test('repo.applyCanonical 遇到更新的本地编辑 -> 保留 dirty', () => {
    const { repo } = setupRepo();
    const created = call((cb) => repo.createMemo({ title: 'A', body: 'X' }, cb)).value;
    const edited = call((cb) => repo.saveField(created.id, 'body', '本地新正文', cb)).value;
    // 手机下发的 canonical 版本比本地已推送的版本旧（同步过程中用户又改了）
    const staleCanonical = {
        id: created.id,
        title: 'A',
        body: 'old-canonical',
        created: created.created,
        updated: created.updated,
        titleHlc: created.titleHlc,
        bodyHlc: created.bodyHlc,
        deleted: false,
        deletedHlc: null,
        rev: 2
    };
    assert.equal(call((cb) => repo.applyCanonical(staleCanonical, cb)).err, null);
    assert.equal(repo.getOutboxCount(), 1, '本地新编辑不能被旧 canonical 清掉');
    const entry = repo.getIndexEntry(created.id);
    assert.equal(entry.dirty, true);
    assert.equal(repo.getOutbox()[0].updated, edited.updated, '队列里应保留新的 HLC');
});

test('repo.ackOutbox 只清「推送后未再修改」的记录', () => {
    const { repo } = setupRepo();
    const created = call((cb) => repo.createMemo({ title: 'A', body: 'X' }, cb)).value;
    assert.equal(call((cb) => repo.ackOutbox([{ id: created.id, updatedHlc: created.updated }], cb)).err, null);
    assert.equal(repo.getOutboxCount(), 0);
    assert.equal(repo.getIndexEntry(created.id).dirty, false);

    const edited = call((cb) => repo.saveField(created.id, 'body', 'Y', cb)).value;
    // 用旧的推送快照 ack：不能清掉新编辑
    assert.equal(call((cb) => repo.ackOutbox([{ id: created.id, updatedHlc: created.updated }], cb)).err, null);
    assert.equal(repo.getOutboxCount(), 1);
    assert.equal(repo.getIndexEntry(created.id).dirty, true);
    assert.equal(repo.getOutbox()[0].updated, edited.updated);
});

test('repo 原子写：主文件损坏时回退 .bak', () => {
    const { fake, repo } = setupRepo();
    const created = call((cb) => repo.createMemo({ title: 'A', body: 'first' }, cb)).value;
    call((cb) => repo.saveField(created.id, 'body', 'second', cb));
    assert.ok(fake.has(Repo.memoUri(created.id) + '.bak'), '应写 .bak 备份');
    fake.corrupt(Repo.memoUri(created.id), '{broken json');
    const recovered = call((cb) => repo.getMemo(created.id, cb));
    assert.equal(recovered.err, null);
    assert.equal(recovered.value.body, 'first', '应从 .bak 恢复上一版');
});

test('repo.init 以 outbox 为准修复缺失的 memo/index', () => {
    const { fake, repo } = setupRepo();
    const created = call((cb) => repo.createMemo({ title: 'A', body: '修复内容' }, cb)).value;
    // 模拟崩溃：memo 文件没写成、index 丢了条目，但 outbox 已有记录
    const index = JSON.parse(fake.read(Repo.FILES.INDEX));
    index.memos = [];
    fake.corrupt(Repo.FILES.INDEX, JSON.stringify(index));
    delete fake.files[Repo.memoUri(created.id)];
    delete fake.files[Repo.memoUri(created.id) + '.bak'];

    const repo2 = Repo.create({ storage: fake.storage });
    assert.equal(call((cb) => repo2.init(cb)).err, null);
    const list = repo2.listMemos();
    assert.equal(list.length, 1);
    assert.equal(list[0].dirty, true);
    const memo = call((cb) => repo2.getMemo(created.id, cb)).value;
    assert.equal(memo.body, '修复内容');
});

test('repo draft 存取与清理', () => {
    const { repo } = setupRepo();
    assert.equal(call((cb) => repo.loadDraft(cb)).value, null);
    assert.equal(call((cb) => repo.saveDraft({ memoId: 'm-1', field: 'body', text: '草稿' }, cb)).err, null);
    const draft = call((cb) => repo.loadDraft(cb)).value;
    assert.equal(draft.memoId, 'm-1');
    assert.equal(draft.text, '草稿');
    assert.equal(call((cb) => repo.clearDraft(cb)).err, null);
    assert.equal(call((cb) => repo.loadDraft(cb)).value, null);
});

test('repo 注入校验：缺 storage / 未 init 调用会报错', () => {
    assert.throws(() => Repo.create({}), /storage is required/);
    const repo = Repo.create({ storage: createFakeStorage().storage });
    assert.deepEqual(repo.listMemos(), [], '未 init 时列表应为空数组');
    let error = null;
    repo.getMemo('m-x', (err) => { error = err; });
    assert.ok(error instanceof Error);
});

// ============================================================
group('sync（fake transport）');
// ============================================================

test('sync encode/decode 短键往返（含 base）', () => {
    const record = baseRecord({ title: '标题', body: '正文', rev: 2, deleted: true, deletedHlc: '0000000003000-0000-w', base: '0000000002000-0000-p' });
    const encoded = Sync.encodeRecord(record);
    assert.equal(encoded.ti, '标题');
    assert.equal(encoded.bo, '正文');
    assert.equal(encoded.del, 1);
    assert.equal(encoded.dh, '0000000003000-0000-w');
    assert.equal(encoded.base, '0000000002000-0000-p');
    const decoded = Sync.decodeRecord(encoded);
    assert.equal(decoded.title, '标题');
    assert.equal(decoded.body, '正文');
    assert.equal(decoded.deleted, true);
    assert.equal(decoded.deletedHlc, '0000000003000-0000-w');
    assert.equal(decoded.rev, 2);
});

test('sync splitEncoded 按 8KB 预算分片', () => {
    const record = Sync.encodeRecord(baseRecord({ id: 'm-big', body: 'x'.repeat(4000) }));
    const budget = Sync.MESSAGE_BUDGET - 256;
    const chunks = Sync.splitEncoded([record, record, record], budget);
    assert.ok(chunks.length >= 2, '三条大记录应分片');
    for (const chunk of chunks) {
        const bytes = Buffer.byteLength(JSON.stringify(chunk), 'utf8');
        assert.ok(bytes <= budget, `分片 ${bytes}B 超预算 ${budget}B`);
    }
    assert.equal(chunks.reduce((sum, chunk) => sum + chunk.length, 0), 3);
    assert.deepEqual(Sync.splitEncoded([], budget), []);
});

test('sync 冒烟：req -> start -> push -> pack -> pull -> done', () => {
    const { repo } = setupRepo();
    const created = call((cb) => repo.createMemo({ title: 'watch', body: 'watch-body' }, cb)).value;
    const transport = createFakeTransport();
    const canonicalUpdated = '0000000009000-0000-p';
    const canonical = {
        id: created.id,
        title: 'watch',
        body: 'merged-body',
        created: created.created,
        updated: canonicalUpdated,
        titleHlc: created.titleHlc,
        bodyHlc: canonicalUpdated,
        deleted: false,
        deletedHlc: null,
        rev: created.rev + 1
    };
    const phases = [];
    const errors = [];
    const client = Sync.createClient({
        repo,
        transport,
        deviceId: repo.getDeviceId(),
        onState: (state) => phases.push(state.phase),
        onError: (error) => errors.push(error)
    });

    transport.setPeer(function (message) {
        if (message.t === 'req') {
            transport.receive({ v: 1, t: 'start', sid: 's1', cur: null });
        } else if (message.t === 'push') {
            assert.equal(message.sid, 's1');
            transport.receive({ v: 1, t: 'pack', sid: 's1', i: message.i });
            transport.receive({
                v: 1,
                t: 'pull',
                sid: 's1',
                i: 0,
                n: 1,
                recs: [Sync.encodeRecord(canonical)],
                next: canonicalUpdated
            });
        } else if (message.t === 'pack') {
            transport.receive({ v: 1, t: 'done', sid: 's1', cur: canonicalUpdated });
        }
    });

    const started = client.request('unit-test');
    assert.equal(started, true);
    assert.equal(errors.length, 0, '不应有错误: ' + JSON.stringify(errors));
    const state = client.getState();
    assert.equal(state.phase, 'idle');
    assert.equal(state.error, null);

    assert.equal(repo.getOutboxCount(), 0, 'done 后应清空 outbox');
    assert.equal(repo.getLastPullHlc(), canonicalUpdated);
    const memo = call((cb) => repo.getMemo(created.id, cb)).value;
    assert.equal(memo.body, 'merged-body');
    const entry = repo.getIndexEntry(created.id);
    assert.equal(entry.dirty, false);
    assert.equal(entry.baseHlc, canonicalUpdated);

    assert.ok(phases.includes('requesting'), '应经过 requesting');
    assert.ok(phases.includes('pushing'), '应经过 pushing');
    assert.ok(phases.includes('pulling'), '应经过 pulling');
    assert.equal(phases[phases.length - 1], 'idle', '最终状态应为 idle: ' + phases.join(','));
});

test('sync 忽略旧 session 的 pull', () => {
    const { repo } = setupRepo();
    const created = call((cb) => repo.createMemo({ title: 'A', body: 'orig' }, cb)).value;
    const transport = createFakeTransport();
    Sync.createClient({ repo, transport, deviceId: repo.getDeviceId() });
    const stale = {
        id: created.id,
        title: 'A',
        body: 'stale',
        created: created.created,
        updated: '0000000009000-0000-p',
        titleHlc: created.titleHlc,
        bodyHlc: '0000000009000-0000-p',
        deleted: false,
        deletedHlc: null,
        rev: 2
    };
    transport.receive({ v: 1, t: 'pull', sid: 'old-session', i: 0, n: 1, recs: [Sync.encodeRecord(stale)] });
    const memo = call((cb) => repo.getMemo(created.id, cb)).value;
    assert.equal(memo.body, 'orig', '旧 session 的记录不能覆盖本地');
});

test('sync 手机离线时保留 outbox 并报 phone_offline', () => {
    const { repo } = setupRepo();
    call((cb) => repo.createMemo({ title: 'A', body: 'X' }, cb));
    const transport = createFakeTransport({ detect: false });
    const errors = [];
    const client = Sync.createClient({ repo, transport, deviceId: repo.getDeviceId(), onError: (e) => errors.push(e) });
    assert.equal(client.request('offline-test'), true);
    assert.equal(client.getState().phase, 'idle');
    assert.equal(repo.getOutboxCount(), 1, '离线不能丢 dirty');
    assert.equal(errors.length, 1);
    assert.equal(errors[0].code, 'phone_offline');
});

test('sync 收到 err 消息后结束会话并保留 outbox', () => {
    const { repo } = setupRepo();
    call((cb) => repo.createMemo({ title: 'A', body: 'X' }, cb));
    const transport = createFakeTransport();
    const errors = [];
    const client = Sync.createClient({ repo, transport, deviceId: repo.getDeviceId(), onError: (e) => errors.push(e) });
    transport.setPeer(function (message) {
        if (message.t === 'req') {
            transport.receive({ v: 1, t: 'err', code: 'merge_failed', msg: '手机端合并失败' });
        }
    });
    client.request('err-test');
    assert.equal(client.getState().phase, 'idle');
    assert.equal(repo.getOutboxCount(), 1);
    assert.equal(errors[0].code, 'merge_failed');
});

// ============================================================
group('静态检查（HAP skeleton / keyboard_rect 适配）');
// ============================================================

test('config.json: liteWearable + rect + 408*480 + 页面 + supportLists', () => {
    const config = JSON.parse(readFileSync(resolve(watchRoot, 'entry/src/main/config.json'), 'utf8'));
    assert.ok(config.module.deviceType.includes('liteWearable'), 'deviceType 应为 liteWearable');
    assert.ok(config.module.distroFilter.screenShape.value.includes('rect'), 'screenShape 应为 rect');
    assert.ok(config.module.distroFilter.screenWindow.value.includes('408*480'), 'screenWindow 应为 408*480');
    assert.equal(config.module.js[0].name, 'default');
    const pages = config.module.js[0].pages;
    for (const page of ['pages/list/list', 'pages/detail/detail', 'pages/edit/edit', 'pages/keyboard_rect/keyboard']) {
        assert.ok(pages.includes(page), `pages 缺少 ${page}`);
    }
    const customize = config.module.metaData.customizeData;
    const supportLists = customize.find((item) => item.name === 'supportLists');
    assert.ok(supportLists, 'metaData.supportLists 占位缺失');
    assert.ok(typeof supportLists.value === 'string' && supportLists.value.indexOf(':') > 0);
    assert.equal(config.module.distroFilter.screenShape.policy, 'include');
});

test('HAP 页面 / 键盘 / 词库 / 图标文件齐全', () => {
    const required = [
        'entry/src/main/js/default/app.js',
        'entry/src/main/js/default/pages/list/list.hml',
        'entry/src/main/js/default/pages/list/list.css',
        'entry/src/main/js/default/pages/list/list.js',
        'entry/src/main/js/default/pages/detail/detail.hml',
        'entry/src/main/js/default/pages/detail/detail.css',
        'entry/src/main/js/default/pages/detail/detail.js',
        'entry/src/main/js/default/pages/edit/edit.hml',
        'entry/src/main/js/default/pages/edit/edit.css',
        'entry/src/main/js/default/pages/edit/edit.js',
        'entry/src/main/js/default/pages/keyboard_rect/keyboard.hml',
        'entry/src/main/js/default/pages/keyboard_rect/keyboard.css',
        'entry/src/main/js/default/pages/keyboard_rect/keyboard.js',
        'entry/src/main/js/default/common/keyboard/lookupDictV5.js',
        'entry/src/main/js/default/common/keyboard/LICENSE',
        'entry/src/main/js/default/common/keyboard/rect/confirm.png',
        'entry/src/main/js/default/common/keyboard/common/0.png',
        'entry/src/main/js/default/common/memo/hlc.js',
        'entry/src/main/js/default/common/memo/merge.js',
        'entry/src/main/js/default/common/memo/repo.js',
        'entry/src/main/js/default/common/memo/sync.js',
        'entry/src/main/js/default/common/memo/config.js',
        'entry/src/main/resources/base/element/string.json',
        'entry/src/main/resources/base/media/icon.png',
        'entry/src/main/resources/rawfile/pinyinDictV5_flash1001/key0.dat',
        'entry/src/main/resources/rawfile/pinyinDictV5_flash1001/value1.dat'
    ];
    for (const relative of required) {
        assert.ok(existsSync(resolve(watchRoot, relative)), `缺少文件: ${relative}`);
    }
});

test('keyboard_rect 已适配 408 宽并 patch editAction', () => {
    const keyboardDir = resolve(watchRoot, 'entry/src/main/js/default/pages/keyboard_rect');
    const keyboardJs = readFileSync(resolve(keyboardDir, 'keyboard.js'), 'utf8');
    assert.ok(keyboardJs.includes("obj.editAction='done'"), 'done() 未加 editAction');
    assert.ok(keyboardJs.includes("obj.editAction='cancel'"), 'cancel() 未加 editAction');
    assert.ok(keyboardJs.includes('pinyinDictV5_flash1001'), 'dictPath 应指向 flash 词库');
    assert.ok(keyboardJs.includes('SCREEN_W=408'), '应显式声明 SCREEN_W=408');
    assert.ok(keyboardJs.includes('TEXT_W=SCREEN_W-38'), '文本列宽应按 408 屏参数化');

    const keyboardHml = readFileSync(resolve(keyboardDir, 'keyboard.hml'), 'utf8');
    assert.ok(!/width:\s*466px/.test(keyboardHml), '466px 的 pyShadow 仍会溢出 408 屏');
    assert.ok(keyboardHml.includes('width: 408px'), 'pyShadow 应改为 408px');

    const keyboardCss = readFileSync(resolve(keyboardDir, 'keyboard.css'), 'utf8');
    assert.ok(/\.keyContainer\s*\{[^}]*height:\s*320px/.test(keyboardCss), 'keyContainer 高度应为 320（480-160）');
    assert.ok(/\.alert\s*\{[^}]*height:\s*380px/.test(keyboardCss), 'alert 弹窗高度应降到 380 以内');
});

test('edit 页 keyboard 上下文：storage 直接返回值 + editAction 注入', () => {
    const editJs = readFileSync(resolve(watchRoot, 'entry/src/main/js/default/pages/edit/edit.js'), 'utf8');
    // Lite Wearable 真机 @system.storage.get 的 success 回调直接给值（字符串），不是 {value} 包装
    assert.ok(editJs.includes('function readStoredValue(data)'), '缺少 storage 回调值兼容 helper');
    assert.ok(editJs.includes('data.value !== undefined) return data.value'), 'helper 应同时兼容 {value} 包装（测试 fake）');
    assert.ok(editJs.includes('JSON.parse(raw)'), '应解析兼容后的 raw 值');
    assert.ok(!editJs.includes('JSON.parse(data.value)'), '不能直接假设回调参数带 .value（真机会丢上下文）');
    // 路由参数必须在 data 里声明才会被注入
    assert.ok(editJs.includes('draftText: null'), 'data 必须声明 draftText');
    assert.ok(editJs.includes('editAction: null'), 'data 必须声明 editAction');
    assert.ok(editJs.includes('Config.KEYBOARD_TARGET_KEY'), 'targetKey 应使用共享常量');
    assert.ok(editJs.includes('Config.KEYBOARD_PAGE'), '应通过 Config.KEYBOARD_PAGE 打开 keyboard_rect');
    assert.ok(editJs.includes('URI 占位') === false); // 防止误留占位
    // 打开键盘前必须先写 storage（Router.replace 之后页面会被替换，写就来不及了）
    const storageSetPos = editJs.indexOf('Storage.set({');
    const keyboardRoutePos = editJs.indexOf('uri: Config.KEYBOARD_PAGE');
    assert.ok(storageSetPos >= 0 && keyboardRoutePos > storageSetPos, '必须先写 editCtx 再 Router.replace 打开键盘');
});

test('MIT 许可与第三方声明保留', () => {
    const license = readFileSync(resolve(watchRoot, 'entry/src/main/js/default/common/keyboard/LICENSE'), 'utf8');
    assert.ok(license.includes('MIT License'));
    assert.ok(license.includes('crabKun'));
    const noticesPath = resolve(watchRoot, 'THIRD_PARTY_NOTICES.md');
    assert.ok(existsSync(noticesPath), '缺少 THIRD_PARTY_NOTICES.md');
    const notices = readFileSync(noticesPath, 'utf8');
    assert.ok(notices.includes('crabKeyboard'));
    assert.ok(notices.includes('MIT'));
});

// ============================================================
// 汇总
// ============================================================
const total = passed + failures.length;
console.log(`\n[run-tests] ${passed}/${total} passed`);
if (failures.length > 0) {
    console.error('[run-tests] 失败列表:');
    for (const failure of failures) console.error(`- ${failure.name}`);
    process.exit(1);
}
