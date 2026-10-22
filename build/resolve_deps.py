#!/usr/bin/env python3
"""Resolve Termux aarch64 deb dependency closure and print download URLs.
Usage: python3 resolve_deps.py node [extra pkgs...]  (reads ./Packages.gz)"""
import gzip, re, sys, os

BASE = os.environ.get("TERMUX_REPO_BASE",
    "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/")
txt = gzip.open("Packages.gz", "rt", encoding="utf8", errors="replace").read()

pkgs = {}
for blk in txt.split("\n\n"):
    pm = re.search(r"^Package: (.+)$", blk, re.M)
    fm = re.search(r"^Filename: (.+)$", blk, re.M)
    if pm and fm:
        pkgs[pm.group(1).strip()] = {
            "file": fm.group(1).strip(), "rec": blk}

def depset(entry, fields):
    out = []
    for fld in fields:
        m = re.search(rf"^{fld}:(.*)$", entry["rec"], re.M)
        if not m:
            continue
        for item in m.group(1).split(","):
            item = item.strip()
            if not item:
                continue
            item = re.sub(r"\([^)]*\)", "", item).strip()   # drop version
            item = item.split("|")[0].strip()               # first alternative
            item = item.split(":")[0].strip()               # arch qualifier
            out.append(item)
    return out

seen, order, missing = set(), [], []
queue = list(sys.argv[1:])
while queue:
    n = queue.pop(0)
    if n in seen:
        continue
    seen.add(n)
    if n not in pkgs:
        missing.append(n)
        continue
    order.append(n)
    for d in depset(pkgs[n], ["Depends", "Pre-Depends"]):
        if d not in seen:
            queue.append(d)

for m in missing:
    print(f"# missing: {m}", file=sys.stderr)
for n in order:
    print(BASE + pkgs[n]["file"])
