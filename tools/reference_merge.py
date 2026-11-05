#!/usr/bin/env python3
"""Independent Python reference for the shared merge cases.

This is the Lead's ground truth for the POC conformance harness. It is not
production code; it intentionally mirrors the simplified rules in
`shared/PROTOCOL.md`.
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def hlc_key(s):
    if s is None:
        return None
    t, c, d = s.split("-", 2)
    return (int(t), int(c), d)


def hmax(a, b):
    return a if hlc_key(a) >= hlc_key(b) else b


def merge(base, phone, watch):
    if phone is None and watch is None:
        return None, None
    if phone is None:
        return dict(watch), None
    if watch is None:
        return dict(phone), None

    canonical = dict(phone)

    # Field-level HLC winner. Equal HLC -> phone wins (deterministic tie-break).
    if hlc_key(watch["titleHlc"]) > hlc_key(phone["titleHlc"]):
        canonical["title"] = watch["title"]
        canonical["titleHlc"] = watch["titleHlc"]
    if hlc_key(watch["bodyHlc"]) > hlc_key(phone["bodyHlc"]):
        canonical["body"] = watch["body"]
        canonical["bodyHlc"] = watch["bodyHlc"]

    if phone["deleted"] and watch["deleted"]:
        canonical["deleted"] = True
        canonical["deletedHlc"] = hmax(phone["deletedHlc"], watch["deletedHlc"])
    elif phone["deleted"]:
        if hlc_key(watch["updated"]) > hlc_key(phone["deletedHlc"]):
            canonical["deleted"] = False
            canonical["deletedHlc"] = None
        else:
            canonical["deleted"] = True
            canonical["deletedHlc"] = phone["deletedHlc"]
    elif watch["deleted"]:
        if hlc_key(phone["updated"]) > hlc_key(watch["deletedHlc"]):
            canonical["deleted"] = False
            canonical["deletedHlc"] = None
        else:
            canonical["deleted"] = True
            canonical["deletedHlc"] = watch["deletedHlc"]
    else:
        canonical["deleted"] = False
        canonical["deletedHlc"] = None

    canonical["created"] = min(phone["created"], watch["created"], key=hlc_key)
    canonical["updated"] = hmax(phone["updated"], watch["updated"])
    canonical["rev"] = max(phone["rev"], watch["rev"]) + 1

    conflict = None
    if base is not None:
        both_body_changed = (
            phone["bodyHlc"] != base["bodyHlc"]
            and watch["bodyHlc"] != base["bodyHlc"]
        )
        if both_body_changed and phone["body"] != watch["body"]:
            loser = phone if hlc_key(phone["bodyHlc"]) < hlc_key(watch["bodyHlc"]) else watch
            conflict = {
                "id": phone["id"] + "-conflict",
                "title": "[冲突] " + loser["title"],
                "body": loser["body"],
                "deleted": False,
                "rev": 1,
            }
    return canonical, conflict


def normalize(canonical):
    if canonical is None:
        return None
    return {
        "title": canonical["title"],
        "body": canonical["body"],
        "deleted": canonical["deleted"],
    }


def normalize_conflict(conflict):
    if conflict is None:
        return None
    return {"title": conflict["title"], "body": conflict["body"]}


def main():
    cases = json.loads((ROOT / "shared" / "merge_cases.json").read_text(encoding="utf-8"))
    results = []
    ok = True
    for case in cases:
        canonical, conflict = merge(case["base"], case["phone"], case["watch"])
        actual = {
            "name": case["name"],
            "canonical": normalize(canonical),
            "conflict": normalize_conflict(conflict),
        }
        results.append(actual)
        exp = case["expect"]
        passed = actual["canonical"] == exp["canonical"] and actual["conflict"] == exp["conflict"]
        ok = ok and passed
        print(("PASS" if passed else "FAIL"), case["name"])
        if not passed:
            print("  expected:", json.dumps(exp, ensure_ascii=False))
            print("  actual  :", json.dumps(actual, ensure_ascii=False))

    out = ROOT / "tools" / "reference_results.json"
    out.write_text(json.dumps({"results": results}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("wrote", out)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
