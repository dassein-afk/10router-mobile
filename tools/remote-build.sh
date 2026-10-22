#!/bin/bash
# 在构建机（tcloud /home/ubuntu/10droid）上跑：上游源码 + 补丁 → Next build → payload → bootstrap.zip → 签 APK
# 由 tools/build-android.sh 推送调用，不要单独手工跑以外的流程。
#
# 前置（由调用方放置）：
#   /home/ubuntu/10droid/tenrouter-src/   已打好补丁的上游源码（node_modules 已存在）
#   /home/ubuntu/10droid/.patchset.json   补丁标记（会被写进 payload）
#   /home/ubuntu/10droid/upstream.sh      产物校验脚本
set -e -o pipefail
B=/home/ubuntu/10droid; cd $B

echo "=== 1. Next build"
cd $B/tenrouter-src
if ! npm run build > /tmp/next-build.log 2>&1; then echo "❌ Next build 失败"; tail -30 /tmp/next-build.log; exit 3; fi
tail -6 /tmp/next-build.log
cd $B

echo "=== 2. 组装 standalone payload"
S=$B/tenrouter-src/.next/standalone
rm -rf $S/.next/static $S/public $S/open-sse $S/src/mitm $S/node_modules/node-forge
cp -r $B/tenrouter-src/.next/static $S/.next/static
[ -d $B/tenrouter-src/public ] && cp -r $B/tenrouter-src/public $S/public
cp -r $B/tenrouter-src/open-sse $S/ 2>/dev/null || true
cp -r $B/tenrouter-src/src/mitm $S/src/ 2>/dev/null || true
mkdir -p $S/node_modules && cp -r $B/tenrouter-src/node_modules/node-forge $S/node_modules/ 2>/dev/null || true
find $S -name '*.node' -delete 2>/dev/null || true          # x64 原生模块必须删（安卓走 node:sqlite）

rm -rf $B/app; mkdir -p $B/app; cp -a $S/. $B/app/
{
  printf 'PORT=20128\n'
  printf 'HOSTNAME=127.0.0.1\n'
  printf 'INSTALL_CHANNEL=android\n'
  [ -n "${ANDROID_APK_URL:-}" ] && printf 'ANDROID_APK_URL=%s\n' "$ANDROID_APK_URL"
} > $B/app/.env
[ -s $B/.patchset.json ] && cp $B/.patchset.json $B/app/.patchset.json
# MIT 合规：Next 的 standalone 产物会把上游 LICENSE 剥掉，而 APK 是 10Router 的一份
# 「副本」，必须随附版权与许可声明。这里显式补回上游源码树里的 LICENSE。
if [ -s "$B/tenrouter-src/LICENSE" ]; then
  cp -f "$B/tenrouter-src/LICENSE" "$B/app/LICENSE"
  echo "上游 LICENSE 已随 payload 打包（$(wc -c < "$B/app/LICENSE") B）"
else
  echo "!! 缺 $B/tenrouter-src/LICENSE（上游许可声明，随包义务）"; exit 13
fi
echo "app 大小: $(du -sh $B/app | cut -f1)"

echo "=== 3. 产物校验（缺补丁就别打包）"
bash $B/upstream.sh assert $B/app

echo "=== 4. 重打 bootstrap.zip + 签 APK"
cd $B
rm -f $B/apk/assets/bootstrap.zip                            # 不删会复用旧 payload
rm -f $B/10router-android-*.apk
if ! bash $B/phase3_apk.sh > /tmp/phase3.log 2>&1; then
  echo "❌ phase3（打包/签名）失败"; tail -30 /tmp/phase3.log; exit 4
fi
tail -14 /tmp/phase3.log

echo "=== 5. 产物自证（版本号 + 新鲜度）"
BT=$B/android-sdk/build-tools/34.0.0
APK=$(ls -t $B/10router-android-*.apk | head -1)
[ -s "$APK" ] || { echo "❌ 没有产出 APK"; exit 5; }
$BT/aapt2 dump badging "$APK" > /tmp/badging.txt 2>&1 || true
head -1 /tmp/badging.txt
echo "APK=$APK  size=$(stat -c %s "$APK")  mtime=$(stat -c %y "$APK")"
sha256sum "$APK"

echo "=== 6. 生成 payload 包（App 运行时热更新用） ==="
# ⚠️ 用固定名 latest + 版本清单，不用版本号拼包名（v1.11.7 教训：npm 版本 ≠ payload 包名 → 100% 404）
V10R=$(grep -m1 '"version"' $B/app/package.json 2>/dev/null | sed 's/.*: *"//;s/".*//' | tr -d '[:space:]')
[ -z "$V10R" ] && V10R="unknown"
rm -f $B/payload-10r-*.zip
( cd $B/app && zip -qr $B/payload-10r-latest.zip . ) && echo "  payload-10r-latest.zip  $(du -h $B/payload-10r-latest.zip | cut -f1)"
printf '{"version":"%s","name":"payload-10r-latest","note":"10R standalone，解压后直接替换 files/app"}\n' "$V10R" > $B/payload-10r-latest.json
echo "  payload-10r-latest.json  version=$V10R"
VCD=$(unzip -p $B/apk/assets/cd.zip cd/package.json 2>/dev/null | grep -m1 '"version"' | sed 's/.*: *"//;s/".*//' | tr -d '[:space:]')
[ -z "$VCD" ] && VCD="unknown"
rm -f $B/payload-cd-*.zip
cp $B/apk/assets/cd.zip $B/payload-cd-latest.zip && echo "  payload-cd-latest.zip  $(du -h $B/payload-cd-latest.zip | cut -f1)"
printf '{"version":"%s","name":"payload-cd-latest","note":"CD 包，解压后是 files/cd（带 cd/ 前缀，App 自动处理）"}\n' "$VCD" > $B/payload-cd-latest.json
echo "  payload-cd-latest.json  version=$VCD"
ls -la $B/payload-*.zip $B/payload-*.json
echo "=== DONE"
