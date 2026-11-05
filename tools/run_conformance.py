#!/usr/bin/env python3
"""Cross-language conformance harness for the memo-sync POC.

Runs the watch (Node) and phone (PowerShell/Java) merge-case CLIs, then
compares both `results.json` files against `shared/merge_cases.json`.

Usage (from repo root):
  python memo-sync-poc/tools/run_conformance.py
  python memo-sync-poc/tools/run_conformance.py --skip-phone
  python memo-sync-poc/tools/run_conformance.py \
      --watch-cmd "node test/run-merge-cases.mjs" --watch-cwd watch \
      --phone-cmd "powershell -NoProfile -ExecutionPolicy Bypass -File run-merge-cases.ps1" \
      --phone-cwd phone
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load_cases():
    return json.loads((ROOT / "shared" / "merge_cases.json").read_text(encoding="utf-8"))


def expected(case):
    return case["expect"]


def normalize_result(r):
    canonical = r.get("canonical") or {}
    conflict = r.get("conflict")
    return {
        "name": r.get("name"),
        "canonical": {
            "title": canonical.get("title"),
            "body": canonical.get("body"),
            "deleted": canonical.get("deleted"),
        },
        "conflict": None if not conflict else {
            "title": conflict.get("title"),
            "body": conflict.get("body"),
        },
    }


def load_results(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if isinstance(data, list):
        return data
    return data.get("results", [])


def run(cmd, cwd):
    print(f"\n$ (cd {cwd}) {cmd}")
    proc = subprocess.run(cmd, cwd=cwd, shell=True, text=True)
    if proc.returncode != 0:
        print(f"  command failed with exit {proc.returncode}")
    return proc.returncode == 0


def compare(label, results, cases):
    by_name = {r.get("name"): normalize_result(r) for r in results}
    ok = True
    print(f"\n== {label} ==")
    for case in cases:
        name = case["name"]
        exp = expected(case)
        if name not in by_name:
            print(f"FAIL {name}: missing result")
            ok = False
            continue
        actual = by_name[name]
        passed = actual["canonical"] == exp["canonical"] and actual["conflict"] == exp["conflict"]
        ok = ok and passed
        print(("PASS" if passed else "FAIL"), name)
        if not passed:
            print("  expected:", json.dumps(exp, ensure_ascii=False))
            print("  actual  :", json.dumps(actual, ensure_ascii=False))
    extra = sorted(set(by_name) - {c["name"] for c in cases})
    if extra:
        print("WARN extra results:", extra)
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-watch", action="store_true")
    ap.add_argument("--skip-phone", action="store_true")
    ap.add_argument("--watch-cmd", default="node test/run-merge-cases.mjs")
    ap.add_argument("--watch-cwd", default=str(ROOT / "watch"))
    ap.add_argument("--phone-cmd", default="powershell -NoProfile -ExecutionPolicy Bypass -File run-merge-cases.ps1")
    ap.add_argument("--phone-cwd", default=str(ROOT / "phone"))
    args = ap.parse_args()

    cases = load_cases()
    all_ok = True

    if not args.skip_watch:
        if not Path(args.watch_cwd).exists():
            print("WARN watch dir missing:", args.watch_cwd)
            all_ok = False
        else:
            ran = run(args.watch_cmd, args.watch_cwd)
            watch_results = ROOT / "watch" / "out" / "results.json"
            if ran and watch_results.exists():
                all_ok = compare("watch", load_results(watch_results), cases) and all_ok
            else:
                print("FAIL watch: no result file at", watch_results)
                all_ok = False

    if not args.skip_phone:
        if not Path(args.phone_cwd).exists():
            print("WARN phone dir missing:", args.phone_cwd)
            all_ok = False
        else:
            ran = run(args.phone_cmd, args.phone_cwd)
            phone_results = ROOT / "phone" / "out" / "results.json"
            if ran and phone_results.exists():
                all_ok = compare("phone", load_results(phone_results), cases) and all_ok
            else:
                print("FAIL phone: no result file at", phone_results)
                all_ok = False

    print("\nCONFORMANCE:", "PASS" if all_ok else "FAIL")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
