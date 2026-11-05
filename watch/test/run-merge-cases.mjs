#!/usr/bin/env node
/*
 * memo-sync-poc / watch — 合并契约一致性测试
 *
 * 读取 shared/merge_cases.json（Lead 维护，只读），用 watch 端合并引擎跑一遍，
 * 把结果投影成 PROTOCOL.md §3 规定的 results.json 契约格式：
 *
 *   {
 *     "results": [
 *       { "name": "<case>", "canonical": {"title","body","deleted"}, "conflict": null|{"title","body"} }
 *     ]
 *   }
 *
 * 同时逐条对比 case.expect；有失败时打印明细并以退出码 1 结束（方便 Lead/CI 直接判）。
 *
 * 用法：node watch/test/run-merge-cases.mjs
 * 输出：watch/out/results.json
 */

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import Merge from '../entry/src/main/js/default/common/memo/merge.js';

const here = dirname(fileURLToPath(import.meta.url)); // .../memo-sync-poc/watch/test
const casesPath = resolve(here, '..', '..', 'shared', 'merge_cases.json'); // .../memo-sync-poc/shared/merge_cases.json
const outDir = resolve(here, '..', 'out');
const outPath = resolve(outDir, 'results.json');

function projectCanonical(record) {
    if (!record) return null;
    return {
        title: record.title,
        body: record.body,
        deleted: record.deleted === true
    };
}

function projectConflict(record) {
    if (!record) return null;
    return {
        title: record.title,
        body: record.body
    };
}

function equalsCanonical(expected, actual) {
    if (!expected || !actual) return false;
    return expected.title === actual.title &&
        expected.body === actual.body &&
        expected.deleted === actual.deleted;
}

function equalsConflict(expected, actual) {
    if (!expected && !actual) return true;
    if (!expected || !actual) return false;
    return expected.title === actual.title && expected.body === actual.body;
}

function main() {
    const raw = readFileSync(casesPath, 'utf8');
    const cases = JSON.parse(raw);
    if (!Array.isArray(cases) || cases.length === 0) {
        console.error(`[run-merge-cases] ${casesPath} 不是非空数组`);
        process.exit(2);
    }

    const results = [];
    const failures = [];

    for (const testCase of cases) {
        const name = testCase.name || `case_${results.length}`;
        let canonical = null;
        let conflict = null;
        let error = null;
        try {
            const outcome = Merge.merge(testCase.base || null, testCase.phone || null, testCase.watch || null);
            canonical = projectCanonical(outcome.canonical);
            conflict = projectConflict(outcome.conflict);
        } catch (err) {
            error = err && err.stack ? err.stack : String(err);
            canonical = null;
            conflict = null;
        }

        results.push({ name, canonical, conflict });

        const expected = testCase.expect || {};
        const okCanonical = !error && equalsCanonical(expected.canonical, canonical);
        const okConflict = !error && equalsConflict(expected.conflict || null, conflict);
        if (okCanonical && okConflict) {
            console.log(`[PASS] ${name}`);
        } else {
            failures.push({
                name,
                error,
                expected: { canonical: expected.canonical, conflict: expected.conflict || null },
                actual: { canonical, conflict }
            });
            console.log(`[FAIL] ${name}`);
        }
    }

    mkdirSync(outDir, { recursive: true });
    writeFileSync(outPath, JSON.stringify({ results }, null, 2) + '\n', 'utf8');

    if (failures.length > 0) {
        console.error('\n[run-merge-cases] 失败明细:');
        for (const failure of failures) {
            console.error(`- ${failure.name}`);
            if (failure.error) console.error(`    error: ${failure.error.split('\n')[0]}`);
            console.error(`    expected: ${JSON.stringify(failure.expected)}`);
            console.error(`    actual:   ${JSON.stringify(failure.actual)}`);
        }
        console.error(`\n[run-merge-cases] ${results.length - failures.length}/${results.length} passed -> ${outPath}`);
        process.exit(1);
    }

    console.log(`\n[run-merge-cases] ${results.length}/${results.length} passed -> ${outPath}`);
}

main();
