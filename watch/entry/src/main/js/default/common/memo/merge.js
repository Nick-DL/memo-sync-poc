/*
 * memo-sync-poc / watch — 合并引擎（纯函数，可 Node 测试）
 *
 * 实现 shared/PROTOCOL.md §2 的 POC 合并契约：
 *   merge(base, phone, watch) -> { canonical, conflict }
 *
 * 规则摘要（逐条对照 PROTOCOL.md）:
 *  1) 一边为 null：另一边直接作为 canonical（不 rev+1）。
 *  2) 两边都有：逐字段取 HLC 大者；相同 HLC 不同内容 -> 手机侧优先（确定性 tie-break）；
 *     created 取较小 HLC；updated 取较大；rev = max(rev) + 1。
 *  3) 冲突副本（仅正文，POC 简化）：
 *     base 非空 && 两边 bodyHlc 都 != base.bodyHlc && 两边 body 不同 ->
 *     { id: phone.id + '-conflict', title: '[冲突] ' + loser.title, body: loser.body,
 *       deleted: false, rev: 1 }
 *     loser = bodyHlc 较小的一边；相同则 watch 为 loser。
 *  4) 删除/编辑冲突：删除方用 deletedHlc、编辑方用 updated 比较；
 *     updated 更大 -> 编辑胜（deleted=false，deletedHlc=null）；
 *     deletedHlc 更大 -> 删除胜。相等时按「编辑优先于删除」处理（避免误删，见 README）。
 *
 * 本文件不 import 兄弟模块（轻手表对无扩展名相对导入更稳，Node ESM 对扩展名很严格）；
 * 代价是这里自带一份 HLC 解析/比较（约 20 行），换取同一份源码两端可加载。
 * 页面侧不要 import 这个文件；只有 Node 测试/未来手机端合并参考使用。
 */

var HLC_RE = /^(\d{13})-(\d{4})-(.+)$/;

function splitHlc(value) {
    if (typeof value !== 'string') {
        throw new Error('merge: HLC must be a string, got ' + typeof value + ' (' + value + ')');
    }
    var m = HLC_RE.exec(value);
    if (!m) {
        throw new Error('merge: invalid HLC "' + value + '"');
    }
    return { t: parseInt(m[1], 10), c: parseInt(m[2], 10), d: m[3] };
}

// 返回 -1 / 0 / 1
function compareHlc(a, b) {
    var pa = splitHlc(a);
    var pb = splitHlc(b);
    if (pa.t !== pb.t) return pa.t < pb.t ? -1 : 1;
    if (pa.c !== pb.c) return pa.c < pb.c ? -1 : 1;
    if (pa.d === pb.d) return 0;
    return pa.d < pb.d ? -1 : 1;
}

function maxHlc(a, b) {
    return compareHlc(a, b) >= 0 ? a : b;
}

function minHlc(a, b) {
    return compareHlc(a, b) <= 0 ? a : b;
}

function copyRecord(record) {
    if (!record) return null;
    var out = {};
    for (var key in record) {
        if (Object.prototype.hasOwnProperty.call(record, key)) out[key] = record[key];
    }
    return out;
}

function num(value) {
    var n = parseInt(value, 10);
    return isFinite(n) ? n : 0;
}

// 字段级 LWW：HLC 大者胜；相同 HLC 时手机侧优先
function pickField(phone, watch, valueKey, hlcKey) {
    var cmp = compareHlc(phone[hlcKey], watch[hlcKey]);
    var source = cmp < 0 ? watch : phone; // cmp === 0 -> phone（POC tie-break）
    return { source: source, hlc: source[hlcKey] };
}

// deleted 状态参与比较的 HLC：已删除用 deletedHlc，未删除用 updated（PROTOCOL §2.2）
function deletedStateHlc(record) {
    return record.deleted ? record.deletedHlc : record.updated;
}

function resolveDeleted(phone, watch) {
    var phoneHlc = deletedStateHlc(phone);
    var watchHlc = deletedStateHlc(watch);
    var cmp = compareHlc(phoneHlc, watchHlc);
    if (cmp > 0) {
        return { deleted: !!phone.deleted, deletedHlc: phone.deleted ? phone.deletedHlc : null };
    }
    if (cmp < 0) {
        return { deleted: !!watch.deleted, deletedHlc: watch.deleted ? watch.deletedHlc : null };
    }
    // HLC 相等但删除状态不同：编辑优先（避免误删）；状态相同则保持一致
    if (!!phone.deleted === !!watch.deleted) {
        var deleted = !!phone.deleted;
        return { deleted: deleted, deletedHlc: deleted ? (phone.deletedHlc || watch.deletedHlc) : null };
    }
    return { deleted: false, deletedHlc: null };
}

function buildConflict(phone, watch, canonical) {
    var cmp = compareHlc(phone.bodyHlc, watch.bodyHlc);
    var loser;
    if (cmp > 0) loser = watch;        // 手机 bodyHlc 更大 -> watch 为 loser
    else if (cmp < 0) loser = phone;   // watch bodyHlc 更大 -> phone 为 loser
    else loser = watch;                // HLC 相同 -> watch 为 loser（契约规定）
    return {
        id: phone.id + '-conflict',
        title: '[冲突] ' + (loser.title === null || loser.title === undefined ? '' : loser.title),
        body: loser.body === null || loser.body === undefined ? '' : loser.body,
        created: canonical.created,
        updated: canonical.updated,
        titleHlc: loser.titleHlc,
        bodyHlc: loser.bodyHlc,
        deleted: false,
        deletedHlc: null,
        rev: 1
    };
}

function isBothChangedAndDifferent(base, phone, watch) {
    if (!base) return false;
    var phoneChanged = phone.bodyHlc !== base.bodyHlc;
    var watchChanged = watch.bodyHlc !== base.bodyHlc;
    var bodiesDiffer = (phone.body === null || phone.body === undefined ? '' : phone.body) !==
        (watch.body === null || watch.body === undefined ? '' : watch.body);
    return phoneChanged && watchChanged && bodiesDiffer;
}

/*
 * 主入口。
 *   base  : 上次同步的 canonical 记录（可能为 null）
 *   phone : 手机当前记录（可能为 null）
 *   watch : 手表当前记录（可能为 null）
 * 返回   : { canonical: MemoRecord|null, conflict: MemoRecord|null }
 */
function merge(base, phone, watch) {
    // 规则 1：一边为 null，另一边直接作为 canonical
    if (!phone && !watch) {
        return { canonical: base ? copyRecord(base) : null, conflict: null };
    }
    if (!phone) {
        return { canonical: copyRecord(watch), conflict: null };
    }
    if (!watch) {
        return { canonical: copyRecord(phone), conflict: null };
    }

    // 规则 2：字段级 LWW
    var titleWinner = pickField(phone, watch, 'title', 'titleHlc');
    var bodyWinner = pickField(phone, watch, 'body', 'bodyHlc');
    var deleted = resolveDeleted(phone, watch);

    var canonical = {
        id: phone.id,
        title: titleWinner.source.title === null || titleWinner.source.title === undefined ? '' : titleWinner.source.title,
        body: bodyWinner.source.body === null || bodyWinner.source.body === undefined ? '' : bodyWinner.source.body,
        created: minHlc(phone.created, watch.created), // 规则 2：created 取较小 HLC
        updated: maxHlc(phone.updated, watch.updated), // 规则 2：updated 取较大值
        titleHlc: titleWinner.hlc,
        bodyHlc: bodyWinner.hlc,
        deleted: deleted.deleted,
        deletedHlc: deleted.deletedHlc,
        rev: Math.max(num(phone.rev), num(watch.rev)) + 1 // 规则 2：rev = max + 1
    };

    // 规则 3：冲突副本（只看正文，POC 简化；标题采用 LWW 不生成副本）
    var conflict = null;
    if (isBothChangedAndDifferent(base, phone, watch)) {
        conflict = buildConflict(phone, watch, canonical);
    }

    return { canonical: canonical, conflict: conflict };
}

export default {
    merge: merge,
    compareHlc: compareHlc,
    maxHlc: maxHlc,
    minHlc: minHlc
};
