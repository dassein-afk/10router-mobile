#!/usr/bin/env bash
# 重建 CD（CreditDaddy）载荷：npm 包 → 重放 cd-patches → cd.zip
#
# 为什么需要：CD 不是从 git 源码构建的，而是 npm 包（techysy 只发 npm）。
# 手机适配补丁 1.0.0 打在 cd/src/panel.html 上（CSS + 内联 script），
# 每次升级 CD 版本都必须把这层重放一次，否则面板适配会静默消失。
#
# 用法： tools/build-cd.sh <版本号>            # 例：tools/build-cd.sh 1.6.0
# 输出： apk/assets/cd.zip（构建链 phase3_apk.sh 直接取这个文件）
#
# 任一补丁打不上就非 0 退出 —— 绝不静默产出没补丁的载荷。
set -euo pipefail

VER="${1:?用法: tools/build-cd.sh <cd 版本号>，例 1.6.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-/tmp/cd-build-$VER}"
PATCH_DIR="$ROOT/cd-patches"
OUT="$ROOT/apk/assets/cd.zip"

echo "===== 1. 拉 creditdaddy@$VER"
rm -rf "$WORK"; mkdir -p "$WORK"
cd "$WORK"
npm pack "creditdaddy@$VER" > /dev/null
tar xzf creditdaddy-*.tgz
PKG_VER=$(grep -m1 '"version"' package/package.json | sed 's/.*: *"//;s/".*//')
echo "  包内版本: $PKG_VER"
[ "$PKG_VER" = "$VER" ] || { echo "❌ 版本不符（要 $VER，拿到 $PKG_VER）"; exit 3; }

echo "===== 2. 重放 cd-patches"
shopt -s nullglob
patches=("$PATCH_DIR"/*.patch)
[ ${#patches[@]} -gt 0 ] || { echo "❌ cd-patches/ 为空"; exit 4; }
cd "$WORK/package"
for p in "${patches[@]}"; do
  if patch -p2 --dry-run -f -i "$p" > /dev/null 2>&1; then
    patch -p2 -f -i "$p" > /dev/null
    echo "  OK   $(basename "$p")"
  else
    echo "  FAIL $(basename "$p")"
    echo "❌ 补丁打不上：上游动过 src/panel.html。"
    echo "   手工按 patches/UI-PATCHES-1.0.0.md「二、CreditDaddy 侧」改一遍，然后重新生成："
    echo "   diff -u <原panel.html> <改后panel.html> > cd-patches/01-src_panel_html.patch"
    exit 5
  fi
done

echo "===== 3. 自证：补丁真的进去了"
grep -q "手机适配补丁 1.0.0" src/panel.html || { echo "❌ CSS 补丁没进去"; exit 6; }
grep -q "原 panel-fix.js" src/panel.html || { echo "❌ 行为脚本没进去"; exit 6; }
echo "  CSS + 行为脚本都在"

echo "===== 4. 打 cd.zip"
cd "$WORK"
# cd/ 前缀是 App 解包约定（KeepAlive.ensureCdPayload 按 cd/ 找 bin/creditdaddy.js）
rm -rf cd && cp -a package cd
rm -f "$OUT"
mkdir -p "$(dirname "$OUT")"
# 用 python 打 zip：构建机上不一定有 zip，但一定有 python3（避免"包能签、载荷是空的"）
python3 - "$WORK" "$OUT" <<'PY'
import sys, os, zipfile
work, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(os.path.join(work, "cd")):
        for f in files:
            full = os.path.join(root, f)
            z.write(full, os.path.relpath(full, work))
PY
echo "  $OUT  $(stat -c %s "$OUT") B"
python3 - "$OUT" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
n = z.namelist()
for need in ("cd/bin/creditdaddy.js", "cd/src/panel.html", "cd/package.json", "cd/LICENSE"):
    assert need in n, "缺 " + need
print("  条目 %d，关键文件齐全（含 LICENSE）" % len(n))
PY
echo "===== 完成"
