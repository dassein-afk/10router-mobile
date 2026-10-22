#!/usr/bin/env bash
# 下游补丁管理：拉上游源码、生成/重放补丁、校验构建产物里补丁还在。
#
# 为什么需要：10Router 上游一直在发版，我们在它上面改了 9 个文件（中文默认、窄屏自适应、
# android 更新渠道）。重建新版时如果直接拿上游源码打包，这些改动会被无声抹掉。
# 所以把改动固化成 patches/*.patch，每次升级 = 重放补丁；冲突就报错，绝不静默出包。
#
# 用法：
#   tools/upstream.sh fetch <ref>               # 拉上游源码到 $CACHE/<ref>（ref 如 v1.5.0 / main）
#   tools/upstream.sh list <ref>                # 列出下游相对上游改了哪些文件
#   tools/upstream.sh make-patches <ref>        # 由下游树生成 patches/*.patch
#   tools/upstream.sh apply <ref> <outdir>      # 上游 + 全部补丁 → outdir（任一补丁失败即退出非 0）
#   tools/upstream.sh assert <built-app-dir>    # 校验构建产物确实带着补丁（不带就报错）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UPSTREAM_REPO="${UPSTREAM_REPO:-techysy/10router}"
DOWNSTREAM="${DOWNSTREAM:-$HOME/10r-src}"   # 我们的下游源码树
CACHE="${CACHE:-$HOME/.upstream}"           # 上游源码缓存
PATCHES="$ROOT/patches"
EXCLUDES=(--exclude=node_modules --exclude=.next --exclude=.git --exclude=.upstream)

die() { echo "❌ $*" >&2; exit 1; }

fetch() {
  local ref="$1" dest="$CACHE/$ref" url
  if [ -f "$dest/package.json" ]; then echo "cache hit: $dest"; return; fi
  rm -rf "$dest"; mkdir -p "$dest"
  if [ "$ref" = "main" ]; then
    url="https://codeload.github.com/$UPSTREAM_REPO/tar.gz/refs/heads/main"
  else
    url="https://codeload.github.com/$UPSTREAM_REPO/tar.gz/refs/tags/$ref"
  fi
  echo "fetch $url"
  curl -fsSL --retry 3 --max-time 600 -o "$dest.tar.gz" "$url" || die "下载失败（国内直连 codeload 常超时；可在能连的机器上拉好后拷进 $CACHE/$ref）"
  tar xzf "$dest.tar.gz" -C "$dest" --strip-components=1
  rm -f "$dest.tar.gz"
  echo "上游版本: $(grep -m1 '"version"' "$dest/package.json")"
}

# 列出下游相对上游有差异的文件（相对路径）
list_changed() {
  local ref="$1"
  [ -d "$CACHE/$ref" ] || fetch "$ref"
  ( cd "$CACHE/$ref" && diff -rq "${EXCLUDES[@]}" . "$DOWNSTREAM" 2>/dev/null || true ) \
    | awk '/^Files /{print $2}' | sed 's|^\./||'
}

make_patches() {
  local ref="$1" rel idx=0 name
  [ -d "$DOWNSTREAM" ] || die "下游树不存在: $DOWNSTREAM"
  list_changed "$ref" > "$CACHE/$ref.changed"
  [ -s "$CACHE/$ref.changed" ] || die "下游树与上游 $ref 没有差异"
  mkdir -p "$PATCHES"; rm -f "$PATCHES"/*.patch
  cat > "$ROOT/patches/PINS" <<EOF
upstream_repo=$UPSTREAM_REPO
upstream_ref=$ref
generated=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
  while IFS= read -r rel; do
    idx=$((idx + 1))
    name=$(printf "%02d-%s.patch" "$idx" "$(echo "$rel" | tr '/().' '____')")
    ( cd "$CACHE/$ref" && diff -u --label "a/$rel" --label "b/$rel" "$rel" "$DOWNSTREAM/$rel" ) \
      > "$PATCHES/$name" || true
    echo "  写出 $name  ($rel)"
  done < "$CACHE/$ref.changed"
  echo "共 $idx 个补丁 → $PATCHES"
}

apply_patches() {
  local ref="$1" out="$2" p rc=0 ok=0 fail=0
  [ -d "$PATCHES" ] || die "没有 patches/ 目录"
  [ -d "$CACHE/$ref" ] || fetch "$ref"
  rm -rf "$out"; mkdir -p "$out"
  cp -a "$CACHE/$ref/." "$out/"
  for p in "$PATCHES"/*.patch; do
    [ -e "$p" ] || die "patches/ 为空"
    if ( cd "$out" && git apply --check --whitespace=nowarn "$p" ) 2>/dev/null \
       && ( cd "$out" && git apply --whitespace=nowarn "$p" ); then
      echo "  OK   $(basename "$p")"; ok=$((ok + 1))
    else
      echo "  FAIL $(basename "$p")"; fail=$((fail + 1)); rc=1
    fi
  done
  echo "补丁重放：ok=$ok fail=$fail → $out"
  if [ "$rc" -ne 0 ]; then
    echo "❌ 有补丁打不上：上游动过你改的这几个文件。手工 rebase（改下游树 → make-patches 重生成）后再重建，别硬上。"
    exit 1
  fi
}

# 构建产物校验：确保出包的 payload 真的带着补丁
assert_patches() {
  local app="$1" bad=0
  [ -d "$app" ] || die "payload 目录不存在: $app"
  chk() { if eval "$2" >/dev/null 2>&1; then echo "  OK   $1"; else echo "  MISS $1"; bad=$((bad + 1)); fi; }
  chk "patchset 标记 (.patchset.json)" "[ -s '$app/.patchset.json' ]"
  chk "android 更新守卫（拒 npm 自更新）" "grep -rqs 'APK 安装方式不支持自更新' '$app/.next' || grep -rqs 'APK 安装方式不支持自更新' '$app/src/app/api/version/update/route.js'"
  chk "android 渠道 APK 链接" "grep -rqs 'ANDROID_APK_URL' '$app/.next' || grep -rqs 'ANDROID_APK_URL' '$app/src/app/api/version/route.js'"
  # 手机适配补丁 1.0.0：面板内更新入口是**删掉**的（源码级），所以这条改成反向断言。
  # 正向写法（要求存在）在 v1.13.0 之后必然 MISS —— 断言本身要跟着补丁的形态变。
  chk "面板内更新入口已删（补丁 1.0.0）" "! grep -rqs 'Download new APK' '$app/.next'"
  # 侧栏不透明：旧的半透明 rgba(38,38,38,.72) 必须从产物里消失
  chk "侧栏不透明（旧半透明值已消失）" "! grep -rqE 'rgba\([[:space:]]*38[[:space:]]*,[[:space:]]*38[[:space:]]*,[[:space:]]*38[[:space:]]*,[[:space:]]*0?\.72[[:space:]]*\)' '$app/.next/static'"
  chk "node 父进程守卫 (custom-server.js)" "grep -qs 'ANDROID_PARENT_PID' '$app/custom-server.js'"
  # v1.11.16：窄屏 CSS 兜底与 DEFAULT_LOCALE=zh-CN 两个补丁已按用户要求撤掉，
  # 不再作为断言项（留着会让每次构建都必然 MISS 并拒绝发版）。
  echo "产物校验：缺失 $bad 项"
  [ "$bad" -eq 0 ] || { echo "❌ 构建产物缺补丁 —— 这份包别发。"; exit 1; }
}

case "${1:-}" in
  fetch)        fetch "${2:?need ref}" ;;
  list)         list_changed "${2:?need ref}" ;;
  make-patches) make_patches "${2:?need ref}" ;;
  apply)        apply_patches "${2:?need ref}" "${3:?need outdir}" ;;
  assert)       assert_patches "${2:?need built app dir}" ;;
  *) sed -n '2,20p' "$0"; exit 1 ;;
esac
