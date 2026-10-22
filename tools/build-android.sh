#!/usr/bin/env bash
# 一键重建安卓包：上游 ref + 补丁集 → 构建机出签名 APK。
#
# 用法： tools/build-android.sh <upstream-ref>     # 例：v1.5.0 / main
# 环境： TCLOUD=user@host（构建机，必填） KEY=~/.ssh/id_ed25519  APK_URL=<新版APK直链>
#
# 关键点：
#   · 补丁必须重放成功才继续（冲突立刻停）
#   · payload 里写 .patchset.json，构建机据此 assert，缺补丁直接失败
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# 本地构建机配置（不进仓）：复制 .buildenv.example 为 .buildenv 后填写
[ -f "$ROOT/.buildenv" ] && . "$ROOT/.buildenv"

REF="${1:?用法: tools/build-android.sh <upstream-ref> [cd-version]}"
CD_REF="${2:-1.6.0}"      # CreditDaddy 版本：每次构建都从 npm 重拉 + 重放 cd-patches
TCLOUD="${TCLOUD:?请设置 TCLOUD=user@构建机地址（例：export TCLOUD=ubuntu@1.2.3.4）}"
KEY="${KEY:-$HOME/.ssh/id_ed25519_tcloud}"
APK_URL="${APK_URL:-${ANDROID_APK_URL:-}}"
SSH=(ssh -o StrictHostKeyChecking=no -i "$KEY" "$TCLOUD")
WORK="${WORK:-$HOME/work/10r-$REF}"

echo "===== 1. 重放补丁（上游 $REF）"
bash "$ROOT/tools/upstream.sh" apply "$REF" "$WORK"

echo "===== 2. 生成补丁标记"
PSID=$(cat "$ROOT/patches"/*.patch | sha256sum | cut -c1-16)
LIST=$(cd "$ROOT/patches" && i=0 && while IFS= read -r p; do
  i=$((i + 1))
  if [ "$i" -gt 1 ]; then printf ','; fi
  printf '{"file":"%s","sha256":"%s"}' "$p" "$(sha256sum "$p" | cut -d' ' -f1)"
done < <(ls -1 *.patch); printf '')
cat > "$WORK/.patchset.json" <<EOF
{"patchset":"$PSID","upstream":"$REF","built":"$(date -u +%Y-%m-%dT%H:%M:%SZ)","patches":[$LIST]}
EOF
echo "  patchset=$PSID upstream=$REF"

echo "===== 3. 推源码到构建机"
"${SSH[@]}" "mkdir -p /home/ubuntu/10droid && bash -s" <<'SH'
set -e
B=/home/ubuntu/10droid
[ -d $B/tenrouter-src/node_modules ] || { echo "构建机缺 node_modules，先跑一次 npm install"; exit 1; }
SH
# 推整棵树（除 node_modules/.next/.git）：补丁不只改 src/，根目录的 custom-server.js 也要同步，
# 只推 src/ 会让根目录补丁静默丢失（踩过：custom-server 的父进程守卫没进包）
tar czf - -C "$WORK" --exclude=node_modules --exclude=.next --exclude=.git . \
  | "${SSH[@]}" "cd /home/ubuntu/10droid/tenrouter-src && tar xzf -"
scp -q -o StrictHostKeyChecking=no -i "$KEY" "$WORK/.patchset.json" "$TCLOUD:/home/ubuntu/10droid/.patchset.json"
scp -q -o StrictHostKeyChecking=no -i "$KEY" "$ROOT/tools/upstream.sh" "$ROOT/tools/remote-build.sh" "$TCLOUD:/home/ubuntu/10droid/"
# phase3_apk.sh 与壳 UI 也必须每次从仓库推过去：构建机上的副本会漂移，
# 一旦漂移就会出现"包能签、版本号对、但 assets 缺文件"的静默坏包（2026-10-19 踩过）。
scp -q -o StrictHostKeyChecking=no -i "$KEY" "$ROOT/build/phase3_apk.sh" "$TCLOUD:/home/ubuntu/10droid/phase3_apk.sh"
# apk-src/AndroidManifest.xml 是版本号的真正权威：aapt2 link 的 --version-code/--version-name
# 会被 manifest 里的显式值压掉（实测），所以 manifest 必须跟着仓库走。
scp -q -o StrictHostKeyChecking=no -i "$KEY" "$ROOT/apk-src/AndroidManifest.xml" "$TCLOUD:/home/ubuntu/10droid/apk-src/AndroidManifest.xml"
tar czf - -C "$ROOT/app" ui | "${SSH[@]}" "mkdir -p /home/ubuntu/10droid/apk-src && tar xzf - -C /home/ubuntu/10droid/apk-src"
# launcher 图标同样必须每次从仓库推（2026-10-19 审计：构建机 res 是手工遗留的"10+电波"旧图，
# 与首页黑色 R 不同源 —— 图标不在版本库，等于图标不存在）
tar czf - -C "$ROOT/apk-src" res | "${SSH[@]}" "tar xzf - -C /home/ubuntu/10droid/apk-src"
# 壳 Java 源码同样必须每次从仓库推：构建机 apk-src/src 是编译输入，不推就会拿旧 Java 签新号
# （2026-10-19 发现这一路径缺失——之前全靠手工 scp，正是脚本漂移的老坑）
# ❗必须先删再解：tar 解压只覆盖/新增，**不会删除**仓库里已删掉的文件。
# 2026-10-22 踩过：本机删了 PanelPatch.java，构建机旧文件还在 → javac 报"找不到符号"，
# 表现为"代码明明改了却编不过"，而且错误信息指向一个仓库里已经不存在的文件。
"${SSH[@]}" "rm -rf /home/ubuntu/10droid/apk-src/src"
tar czf - -C "$ROOT/app" src | "${SSH[@]}" "tar xzf - -C /home/ubuntu/10droid/apk-src"
echo "  ok"

echo "===== 3.5 重建 CD 载荷（npm 包 + cd-patches 重放）"
# ❗为什么放在构建链里：CD 不是从 git 构建的，载荷以前靠手工 scp。
# 2026-10-22 踩过：手工做的 cd.zip 没推上去，包能签出来但额度面板还是旧版（没有手机适配补丁）。
bash "$ROOT/tools/build-cd.sh" "$CD_REF" > /tmp/build-cd.log 2>&1 || { echo "❌ CD 载荷重建失败"; tail -20 /tmp/build-cd.log; exit 7; }
tail -6 /tmp/build-cd.log
scp -q -o StrictHostKeyChecking=no -i "$KEY" "$ROOT/apk/assets/cd.zip" "$TCLOUD:/home/ubuntu/10droid/apk/assets/cd.zip"
echo "  cd.zip 已推送 $(stat -c %s "$ROOT/apk/assets/cd.zip") B"

echo "===== 4. 构建机执行（build + assert + 签名）"
if ! "${SSH[@]}" "cd /home/ubuntu/10droid && ANDROID_APK_URL='$APK_URL' bash remote-build.sh" > /tmp/remote-build.log 2>&1; then
  echo "❌ 构建机流程失败"; tail -40 /tmp/remote-build.log; exit 4
fi
tail -40 /tmp/remote-build.log

echo "===== 5. 取回 APK 并核对（防止拿到旧包）"
APK=$( "${SSH[@]}" "ls -t /home/ubuntu/10droid/*.apk | head -1" )
OUT="$ROOT/dist/$(basename "$APK")"
mkdir -p "$ROOT/dist"
rm -f "$OUT"
scp -q -o StrictHostKeyChecking=no -i "$KEY" "$TCLOUD:$APK" "$OUT"
echo "  $OUT"
sha256sum "$OUT"
# 版本权威 = apk-src/AndroidManifest.xml（aapt2 的 --version-* 会被 manifest 显式值压掉），
# 这里不再写死数字，避免脚本与 manifest 漂移（曾写死 212，实际已到 216）
EXPECT_CODE="${EXPECT_CODE:-$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$ROOT/apk-src/AndroidManifest.xml" | head -1)}"
GOT_CODE=$( "${SSH[@]}" "\$HOME/10droid/android-sdk/build-tools/34.0.0/aapt2 dump badging '$APK' | head -1" )
echo "  远端版本: $GOT_CODE"
case "$GOT_CODE" in
  *"versionCode='$EXPECT_CODE'"*) echo "  ✅ versionCode=$EXPECT_CODE" ;;
  *) echo "  ❌ APK 版本号不是 $EXPECT_CODE —— 这次不是新构建，别发"; exit 6 ;;
esac
echo "===== 完成（新包还没投放；要发布再走上传 + 飞书那一步）"
