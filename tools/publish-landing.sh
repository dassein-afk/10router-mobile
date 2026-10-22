#!/usr/bin/env bash
# 把下载页的「稳定入口」同步到 version.json 指向的最新版，然后热重载 caddy。
#
# 为什么需要这个脚本（v1.11.9 抓到的真 bug）：Caddyfile 里 /10router/get 与 /10router/local
# 的 302 目标是**手工写死的版本号**。发新版时只改了页面按钮、没改这两行 → 用户拿分享链接
# /10router/get 下载到的还是 v1.8.0 老包。这和 EXPECT_CODE 写死 212 是同一类病：
# 一个事实存在两处，必然漂移。唯一权威 = version.json，其余全部从这里生成。
#
# 用法： tools/publish-landing.sh            # 同步 + 校验 + 重载 + 实测
#       tools/publish-landing.sh --check     # 只报告是否漂移，不改文件
set -euo pipefail

VER_JSON="${VER_JSON:-/opt/apps/sites/landing/10router/version.json}"
CADDYFILE="${CADDYFILE:-/opt/apps/sites/blog/Caddyfile}"
LANDING_DIR="${LANDING_DIR:-/opt/apps/sites/landing/10router}"
CADDY_CONTAINER="${CADDY_CONTAINER:-edge-caddy}"
CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

[ -f "$VER_JSON" ] || { echo "❌ 找不到 $VER_JSON"; exit 1; }

read -r APK_URL VNAME VCODE <<EOF
$(python3 - "$VER_JSON" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
print(d["apk"], d["versionName"], d["versionCode"])
PY
)
EOF
LOCAL_NAME="10router-v$VNAME.apk"   # 落地目录的既有约定带 v，别凭空造名字
LOCAL_APK="$LANDING_DIR/$LOCAL_NAME"
echo "权威版本: $VNAME (vc$VCODE)"
echo "  镜像入口 → $APK_URL"
echo "  本地兜底 → /10router/$LOCAL_NAME"

if [ ! -s "$LOCAL_APK" ]; then
  echo "⚠️  本地兜底文件不存在：$LOCAL_APK"
  echo "    /10router/local 会 404。请先从镜像把包拉到落地目录（或确认磁盘余量足够）。"
fi

# ── 兜底包必须是"这一个包"：只判 -s（非空）不够。
# v1.11.10 实测踩过：release 还在上传时去下兜底包，拿到 0 字节 404 页；非空判据根本挡不住假包。
# GitHub 在 releases/tags 响应里直接给资产 digest（sha256:…），拿它硬校验本地包。
# ⚠️ 每条 gh 调用都要 `|| echo`：set -e 之下命令替换失败会直接把本脚本杀掉（踩过，现象是"静默不出结果"）。
TAG=$(sed -n 's|.*/download/\([^/]*\)/.*|\1|p' <<<"$APK_URL" | head -1)
REPO=$(sed -n 's|.*/github\.com/\([^/]*/[^/]*\)/.*|\1|p' <<<"$APK_URL" | head -1)
EXPECT_SHA=$(gh api "repos/$REPO/releases/tags/$TAG" --jq ".assets[] | select(.name==\"$LOCAL_NAME\") | .digest" 2>/dev/null | sed 's/^sha256://' || echo "")
if [ -z "$EXPECT_SHA" ]; then
  echo "⚠️  拿不到 release 的 sha256（gh 不可用 / 资产还在上传），本次**未**校验兜底包"
elif [ ! -s "$LOCAL_APK" ]; then
  echo "❌ 兜底包缺失：$LOCAL_APK（期望 sha256 ${EXPECT_SHA:0:16}…）"
  [ "$CHECK_ONLY" = "1" ] || exit 3
elif [ "$(sha256sum "$LOCAL_APK" | cut -d' ' -f1)" = "$EXPECT_SHA" ]; then
  echo "  ✅ 兜底包 sha256 与 release 资产一致（${EXPECT_SHA:0:16}…）"
else
  echo "❌ 兜底包 sha256 与 release 资产不一致"
  echo "   期望 $EXPECT_SHA"
  echo "   实际 $(sha256sum "$LOCAL_APK" | cut -d' ' -f1)"
  echo "   /10router/local 会发错文件 —— 重新下载后再跑本脚本。"
  exit 3
fi

python3 - "$CADDYFILE" "$APK_URL" "$LOCAL_NAME" "$CHECK_ONLY" <<'PY'
import re, sys
path, apk_url, local_name, check_only = sys.argv[1:5]
src = open(path, encoding="utf-8").read()

def sub_block(text, handle, target):
    # 只替换指定 handle 块内的 redir 行，别的地方一律不动
    pat = re.compile(
        r"(handle " + re.escape(handle) + r" \{.*?redir \* )\S+( 302)", re.S)
    new, n = pat.subn(lambda m: m.group(1) + target + m.group(2), text, count=1)
    if n == 0:
        raise SystemExit(f"❌ 在 {handle} 块里没找到 redir 行，Caddyfile 结构变了，去看一眼再改")
    return new

cur_get = re.search(r"handle /10router/get \{.*?redir \* (\S+) 302", src, re.S)
cur_local = re.search(r"handle /10router/local \{.*?redir \* (\S+) 302", src, re.S)
if not cur_get or not cur_local:
    raise SystemExit("❌ Caddyfile 结构变了，两个 handle 块里找不到 redir 行，先去看一眼再改")
same = cur_get.group(1) == apk_url and cur_local.group(1) == "/10router/" + local_name
print(f"  现状 get   → {cur_get.group(1)}")
print(f"  现状 local → {cur_local.group(1)}")

if same:
    print("✅ 无漂移，Caddyfile 已指向权威版本")
    sys.exit(0)
if check_only == "1":
    print("🔴 漂移：Caddyfile ≠ version.json（跑 tools/publish-landing.sh 修）")
    sys.exit(2)

src = sub_block(src, "/10router/get", apk_url)
src = sub_block(src, "/10router/local", "/10router/" + local_name)
open(path, "w", encoding="utf-8").write(src)
print("✏️  已改写 Caddyfile 的两条 302")
PY

SITE="${SITE:-das-sein.top}"

# ── 线上入口实测：文件对 ≠ 运行时对，必须两条都验（v1.11.16 抓到的真故障）
# 现象：Caddyfile 已指向 v1.11.14，线上 /10router/get 却还 302 到 v1.11.12 —— 用户从分享链接
#      进来看到的就是旧包（等于降级）。根因见下面 reload 段的注释（单文件 bind mount 的 inode）。
assert_live() {
  local p loc fail=0
  for p in get local; do
    loc=$(curl -s -o /dev/null -H 'Cache-Control: no-cache' \
               -w '%{redirect_url}' "https://$SITE/10router/$p" || echo "")
    printf "  /10router/%-6s → %s\n" "$p" "${loc:-（无跳转/不可达）}"
    # local 那条 Caddy 写的是相对路径，curl 会补成绝对 URL —— 只比后缀
    if [ "$p" = "get" ]; then
      [ "$loc" = "$APK_URL" ] || fail=1
    else
      case "$loc" in */10router/"$LOCAL_NAME") ;; *) fail=1 ;; esac
    fi
  done
  [ "$fail" = "0" ] || { echo "❌ 线上入口与 version.json 不一致（文件改了但运行时没换 = reload 没吃到新内容；也可能是入口本身漂移/不可达）"; return 4; }
}

if [ "$CHECK_ONLY" = "1" ]; then
  # 文件级漂移已由上面的 python 以退出码 2 终止（set -e），走到这里说明文件是对的
  echo "=== 实测线上入口 ==="
  assert_live || exit 4
  echo "✅ 文件与运行时都指向权威版本"
  exit 0
fi

echo "=== 把宿主 Caddyfile 推进容器再重载 ==="
# ❗ **单文件 bind mount 的 inode 坑（这就是 /get 落后 3 个版本的真凶）**：
#    /opt/apps/sites/blog/Caddyfile 以**单个文件**挂到 edge-caddy:/etc/caddy/Caddyfile。
#    `patch`/`sed -i`/`open(path,'w')` 都是"写新文件再改名"，生成**新 inode**；
#    容器里那个挂载点仍指向**旧 inode** → `caddy reload --config /etc/caddy/Caddyfile`
#    读到的还是旧内容，而且**看起来一切成功**（reload 返回 info 日志、文件里也确实是对的）。
#    容器内 /etc/caddy 只读（`docker cp` 覆盖报 device or resource busy），所以走 /tmp 再 reload。
#    代价：下次容器重建会重新按路径挂载 → 自动吃到宿主的当前文件，内容本来就一致，不会回退。
docker cp "$CADDYFILE" "$CADDY_CONTAINER:/tmp/Caddyfile.publish" 2>/dev/null \
  || { echo "❌ docker cp 失败（容器没起？$CADDY_CONTAINER）"; exit 5; }

echo "=== 校验 caddy 配置（用推进去的那份）==="
docker exec "$CADDY_CONTAINER" caddy validate --config /tmp/Caddyfile.publish --adapter caddyfile 2>&1 | tail -2

echo "=== 热重载（不中断已在下载的连接）==="
docker exec "$CADDY_CONTAINER" caddy reload --config /tmp/Caddyfile.publish --adapter caddyfile 2>&1 | tail -2
sleep 2

echo "=== 实测入口 ==="
assert_live || exit 4
echo "=== 完成 ==="
