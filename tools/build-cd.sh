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
# 正断言：纯 CSS 适配层必须在
grep -q "手机适配 —— 纯 CSS" src/panel.html || { echo "❌ CSS 适配层没进去"; exit 6; }
grep -q "mask-image: linear-gradient" src/panel.html || { echo "❌ 标签栏可滑提示没进去"; exit 6; }

# ❗反断言（栅栏）：CD 侧绝不允许出现运行时注入脚本。
# 历史教训：panel-fix.js 曾在 WebView 里扫 DOM 量宽度再改，扫漏一个元素就整页错乱，
# 且永远慢一拍。适配只能写成 CSS 规则，不能靠 JS 事后动手。
if grep -q "panelFix\|pf-tabscroll\|原 panel-fix.js" src/panel.html; then
  echo "❌ panel.html 里出现了运行时注入脚本（panelFix / pf-tabscroll）"
  echo "   CD 适配只允许纯 CSS。见 patches/UI-PATCHES-1.0.0.md"
  exit 10
fi
# 内联脚本总量：上游 panel.html 自带 1 个业务 <script>；出现第 2 个就是又塞了适配脚本
NSCRIPT=$(grep -c "<script" src/panel.html)
[ "$NSCRIPT" -le 1 ] || { echo "❌ panel.html 有 $NSCRIPT 个 <script>（上游只有 1 个）—— 疑似又内联了适配脚本"; exit 11; }
echo "  纯 CSS 适配层在；无运行时注入脚本（<script>=1）"

# ── 成对断言：fetch 失败必须带 cause ──
# 正：describeFetchError 定义在、且调用点用的是它
grep -q "function describeFetchError" src/zcodeGateway.js \
  || { echo "❌ describeFetchError 没进去 —— fetch 失败只会显示「fetch failed」，排障无方向"; exit 12; }
grep -q "error: describeFetchError(e)" src/zcodeGateway.js \
  || { echo "❌ describeFetchError 没被调用（只定义没用等于没做）"; exit 12; }
# 反：fetchJsonRace 的 catch 里不许再只记 e.message
if grep -q "if (clientAbort.signal.aborted) return;.*" src/zcodeGateway.js; then
  awk '/if \\(clientAbort.signal.aborted\\) return;/{getline nxt; if (nxt ~ /error: e\\.message }/) {print "BAD"; exit 1}}' src/zcodeGateway.js \
    && true || { echo "❌ fetchJsonRace 的 catch 仍只记 e.message（丢掉了 cause）"; exit 13; }
fi
echo "  fetch 错误带 cause 在；catch 不再只记「fetch failed」"

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
echo "CD-BUILD-OK"
