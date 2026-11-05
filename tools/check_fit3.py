#!/usr/bin/env python3
"""Static Fit 3 / 408x480 checks for the watch POC."""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WATCH = ROOT / "watch"
CONFIG = WATCH / "entry" / "src" / "main" / "config.json"


def main():
    ok = True
    print("== Fit 3 watch POC checks ==")
    if not CONFIG.exists():
        print("FAIL config.json missing:", CONFIG)
        return 1
    cfg = json.loads(CONFIG.read_text(encoding="utf-8"))
    module = cfg.get("module", {})
    device_type = module.get("deviceType", [])
    print(("PASS" if "liteWearable" in device_type else "FAIL"), "deviceType liteWearable:", device_type)
    ok = ok and "liteWearable" in device_type

    distro = module.get("distroFilter", {})
    shapes = distro.get("screenShape", {}).get("value", [])
    windows = distro.get("screenWindow", {}).get("value", [])
    print(("PASS" if "rect" in shapes else "FAIL"), "screenShape rect:", shapes)
    print(("PASS" if "408*480" in windows else "FAIL"), "screenWindow 408*480:", windows)
    ok = ok and "rect" in shapes and "408*480" in windows

    pages = []
    for js in module.get("js", []):
        pages.extend(js.get("pages", []))
    print(("PASS" if any("keyboard_rect" in p for p in pages) else "FAIL"), "keyboard_rect registered:", pages)
    ok = ok and any("keyboard_rect" in p for p in pages)

    support = ""
    for item in module.get("metaData", {}).get("customizeData", []):
        if item.get("name") == "supportLists":
            support = item.get("value", "")
    print(("PASS" if support else "WARN"), "supportLists placeholder:", support or "(missing)")

    # Source-level checks. 466/370 are expected in the copied keyboard until
    # adapted; report them as notes, not hard failures.
    notes = []
    for path in WATCH.rglob("*"):
        if path.is_file() and path.suffix in {".js", ".css", ".hml", ".json"}:
            try:
                text = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            rel = path.relative_to(WATCH).as_posix()
            if "ROUND = false" in text or "ROUND=false" in text or "var ROUND" in text:
                notes.append(f"{rel}: round flag found")
            if "408" in text:
                notes.append(f"{rel}: 408 reference")
            if "466" in text:
                notes.append(f"{rel}: 466 reference (keyboard may need 408 adaptation)")
            if "keyboard_circle" in text and "keyboard_rect" not in text:
                notes.append(f"{rel}: only keyboard_circle referenced")
    print("\n-- notes --")
    for n in notes[:60]:
        print(" ", n)
    if len(notes) > 60:
        print("  ...", len(notes) - 60, "more")

    print("\nFIT3:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
