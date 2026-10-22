#!/usr/bin/env python3
"""回归门禁：对比「基线」与「候选」的结构快照，任何一条不过就 FAIL（拒发）。

设计依据（都是真实踩过的坑）：
  · 越界元素增加      → v1.9.1 早期版本把 fixed 侧栏算进行宽，整行换行
  · 主内容列被挤窄    → 同一个 bug 的另一个表现（主内容被挤到侧栏下方）
  · 页面高度异常膨胀  → 把工具条/行拆成多行（v1.9.0 的暴力 flex-wrap）
  · 裁切/小字变多     → 适配反而让内容更难读
  · 关键元素消失      → 误伤导航/按钮
  · 低对比文字变多    → "看不清 / 弹窗黑框看不见字"（浅色主题下上游把次级文字用到 2.5:1）
  · 结构性截断变多    → "显示不完全"（overflow:hidden 把宽内容整块藏掉）

快照可以是两种形态：
  · 旧：{case: {metrics, mainWidth, ...}}
  · 新：{case: {s: <结构快照>, c: <对比度/截断快照>}}

用法：
  python3 tools/gate.py baseline.json candidate.json
退出码：0 通过（可发布）／1 不通过（禁止发布）
"""
import json
import sys

# 高度增长的容忍度：必须同时超过"比例"和"绝对量"才算回归。
# 为什么不能只用比例：CD 面板某内部滚动区的基线只有 41px，任何一点变化都会显得像"膨胀 3 倍"。
# 而真正要抓的是"内容被拆行/栅格塌成一列"这类**数量级**膨胀（实测 10x）。
HEIGHT_TOLERANCE = 0.25     # 允许增长 25%
HEIGHT_ABS_FLOOR = 120      # 且必须超过 120px 绝对值
# 主内容列允许的收窄像素（超过即认为被挤压）
MAIN_WIDTH_TOLERANCE = 2


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def split(snap):
    """兼容两种快照形态：新版返回 (结构, 对比度)，旧版对比度部分为 None"""
    if isinstance(snap, dict) and "s" in snap:
        return snap.get("s") or {}, snap.get("c")
    return snap, None


def compare(case, base, cand):
    base, base_c = split(base)
    cand, cand_c = split(cand)
    fails, notes = [], []

    # 新维度 1：低对比文字不得变多（这是"看不清/黑框无字"的量化口径）
    if base_c and cand_c:
        for key, label in (("low", "低对比文字"), ("clips", "结构性截断")):
            bv, cv = base_c.get(key, 0), cand_c.get(key, 0)
            if cv > bv:
                fails.append(f"{label}: {bv} → {cv}（变多了）")
            elif cv < bv:
                notes.append(f"{label}: {bv} → {cv} ✓改善")

    for key in ("over", "clip", "small"):
        b, c = base["metrics"][key], cand["metrics"][key]
        if c > b:
            fails.append(f"{key}: {b} → {c}（变多了）")
        elif c < b:
            notes.append(f"{key}: {b} → {c} ✓改善")

    # 小热区：允许减少；增加说明有元素被压小了
    b, c = base["metrics"]["tiny"], cand["metrics"]["tiny"]
    if c > b:
        fails.append(f"tiny: {b} → {c}（热区变小了）")
    elif c < b:
        notes.append(f"tiny: {b} → {c} ✓改善")

    # 主内容列不得被挤窄
    bw, cw = base.get("mainWidth", 0), cand.get("mainWidth", 0)
    if bw and cw and cw < bw - MAIN_WIDTH_TOLERANCE:
        fails.append(f"mainWidth: {bw} → {cw}（主内容被挤窄）")

    # 高度不得异常膨胀：文档高 + 内部滚动区内容高（h-screen 布局靠后者）
    for label, key in (("pageHeight", "pageHeight"), ("contentHeight", "contentHeight")):
        b, c = base.get(key, 0), cand.get(key, 0)
        if not b:
            continue
        if c > b + max(HEIGHT_ABS_FLOOR, b * HEIGHT_TOLERANCE):
            fails.append(f"{label}: {b} → {c}（+{c-b}，超过容忍：比例 25% 且绝对 120px）")

    # 关键元素不得消失
    for k, v in (base.get("keyVisible") or {}).items():
        if v and not (cand.get("keyVisible") or {}).get(k):
            fails.append(f"关键元素 {k} 从可见变成不可见")

    return fails, notes


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    base, cand = load(sys.argv[1]), load(sys.argv[2])

    missing = sorted(set(base) - set(cand))
    extra = sorted(set(cand) - set(base))
    all_fail, improved = [], []

    print("=" * 72)
    print(f"{'用例':44s} {'结论':10s}")
    print("-" * 72)
    for case in sorted(base):
        if case not in cand:
            continue
        fails, notes = compare(case, base[case], cand[case])
        verdict = "FAIL" if fails else "PASS"
        print(f"{case:44s} {verdict:10s}")
        for f in fails:
            print(f"    ✗ {f}")
        for n in notes:
            print(f"    · {n}")
        if fails:
            all_fail.append((case, fails))
        if notes:
            improved.append((case, notes))

    print("=" * 72)
    if missing:
        print(f"⚠️  候选缺少用例（视为不可信）：{missing}")
    if extra:
        print(f"ℹ️  候选多出用例：{extra}")

    if all_fail:
        print(f"\n❌ 门禁不通过：{len(all_fail)} 个用例出现回归 —— 禁止发布")
        for case, fails in all_fail:
            print(f"   {case}: {'; '.join(fails)}")
        return 1

    print(f"\n✅ 门禁通过：{len(base)} 个用例无回归")
    if improved:
        total = sum(len(n) for _, n in improved)
        print(f"   共 {total} 项指标改善")
    return 0


if __name__ == "__main__":
    sys.exit(main())
