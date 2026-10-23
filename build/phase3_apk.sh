#!/bin/bash
# Phase 3 on tcloud: assemble + compile + sign 10Router APK (no gradle)
#
# ⚠️ 本文件是构建机 /home/ubuntu/10droid/phase3_apk.sh 的唯一权威副本。
#    由 tools/build-android.sh 每次构建前 scp 推送——不要再在构建机上单独手改，
#    否则两边漂移（2026-10-19 就因此丢过 assets/ui 与 assets/cd.zip：包能签出来、
#    版本号也对，但壳 UI 与额度面板整体缺失）。
set -x
B=/home/ubuntu/10droid; SDK=$B/android-sdk; BT=$SDK/build-tools/34.0.0; cd $B || exit 9
apt-get install -y zip >/dev/null 2>&1 || sudo -n apt-get install -y zip

VCODE=${VCODE:-$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' apk-src/AndroidManifest.xml | head -1)}
VNAME=${VNAME:-$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' apk-src/AndroidManifest.xml | head -1)}

# ---- 1. prepare app payload ----
# strip foreign-arch native modules (node>=24 runtime uses built-in node:sqlite; sharp optional)
find app -name "*.node" -print -delete
# dereference symlinks inside pfx (java.util.zip can't recreate symlinks on Android)
find pfx/usr -type l | while read -r l; do
  t=$(realpath "$l" 2>/dev/null)
  [ -n "$t" ] && [ -f "$t" ] && { rm -f "$l"; cp -f "$t" "$l"; }
done
# trim dead weight
rm -rf app/.next/cache pfx/usr/share/man pfx/usr/share/doc pfx/usr/include 2>/dev/null
# 兜底：只有 .env 不存在时才写默认值。绝不能覆盖——remote-build.sh 在里面写了
# INSTALL_CHANNEL=android / ANDROID_APK_URL，丢了面板就会当自己是 npm 安装，
# 把"立即更新"按钮放出来（安卓上是杀网关进程 + 装进 Termux prefix，纯坏事）。
[ -s app/.env ] || printf 'PORT=20128\nHOSTNAME=127.0.0.1\n' > app/.env
du -sh app pfx

# ---- 2. bootstrap.zip (assets payload; reuse if already built) ----
mkdir -p apk/assets
if [ ! -s apk/assets/bootstrap.zip ]; then
  # Tailwind v4 把全部样式放进 @layer，而 @layer 需要 Chromium>=99；
  # 无 GMS 的华为/EMUI WebView 常停更在更早版本 → 整份样式被忽略（面板变白屏黑字）。
  # 展开成普通规则（顺序不变，语义等价），老内核也能渲染。
  python3 flatten_layers.py app/.next/static/css/*.css || exit 9
  zip -q -9 -r apk/assets/bootstrap.zip pfx app || exit 8
fi
ls -la apk/assets/bootstrap.zip

# ---- 2b. 壳 UI + 额度管家载荷：都必须进包，缺一个就停 ----
# 壳用 file:///android_asset/ui/index.html；
# CD 载荷由 KeepAlive.ensureCdPayload 从 assets/cd.zip 解出 cd/bin/creditdaddy.js。
rm -rf apk/assets/ui && mkdir -p apk/assets/ui
# 手机适配补丁 1.0.0 已改为源码级（打进上游源码），不再随包发 panel-fix.{css,js}
for f in index.html app.js offline.html OPEN_SOURCE_NOTICES.txt; do
  [ -s "apk-src/ui/$f" ] || { echo "!! 缺 apk-src/ui/$f"; exit 11; }
  cp -f "apk-src/ui/$f" "apk/assets/ui/$f"
done
[ -s apk/assets/cd.zip ] || { echo "!! 缺 apk/assets/cd.zip（额度管家载荷）"; exit 12; }
ls -la apk/assets apk/assets/ui

# ---- 3. compile the shell ----
cd $B/apk-src || exit 7
rm -rf work; mkdir -p work/classes work/dex
$BT/aapt2 compile --dir res -o work/res.zip || exit 6
$BT/aapt2 link -o work/app.unsigned.apk \
  -I $SDK/platforms/android-34/android.jar \
  --manifest AndroidManifest.xml \
  --min-sdk-version 24 --target-sdk-version 28 \
  --version-code $VCODE --version-name $VNAME \
  --auto-add-overlay work/res.zip || exit 5
javac -nowarn -classpath "$SDK/platforms/android-34/android.jar" \
  -source 1.8 -target 1.8 -d work/classes \
  $(find src -name '*.java') 2>work/javac.log || { tail -30 work/javac.log; exit 4; }
$BT/d8 --min-api 24 --lib "$SDK/platforms/android-34/android.jar" \
  --output work/dex $(find work/classes -name '*.class') || exit 3
ls -la work/dex

# ---- 4. package: dex + assets into apk ----
# 整目录塞进去（assets/），不要点名单个文件——点名就会在下次加文件时静默漏掉。
cd $B/apk-src/work || exit 2
cp app.unsigned.apk temp.apk
zip -q -j temp.apk dex/classes.dex
(cd $B/apk && zip -q -r -X "$B/apk-src/work/temp.apk" assets) || exit 1
ls -la temp.apk
$BT/zipalign -f -p 4 temp.apk aligned.apk || exit 1

# ---- 5. sign (self-signed release key, persisted) ----
KS=$B/10droid.keystore
KS_PASS=${ANDROID_KEYSTORE_PASS:-10router}
[ -f $KS ] || keytool -genkeypair -v -keystore $KS -alias 10router -storepass "$KS_PASS" -keypass "$KS_PASS" \
   -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=10Router Android,OU=das-sein,O=das-sein,L=Internet,C=CN"
APK=$B/10router-android-v1.5.0.apk
$BT/apksigner sign --ks $KS --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$APK" aligned.apk || exit 1
$BT/apksigner verify --print-certs "$APK" | head -6

# ---- 6. 出包自证：assets 一个都不能少（少了就别发） ----
for e in assets/ui/index.html assets/ui/app.js assets/ui/offline.html \
         assets/ui/OPEN_SOURCE_NOTICES.txt \
         assets/bootstrap.zip assets/cd.zip; do
  unzip -l "$APK" | grep -qE "[[:space:]]$e\$" || { echo "!! APK 缺 $e —— 别再发这个包"; exit 13; }
done
echo "✅ assets 齐全"
# MIT 合规自证：APK 是 10Router 的一份「副本」，版权与许可声明必须随包。
# Next standalone 会把上游 LICENSE 剥掉，所以由 remote-build.sh 显式补进 app/。
unzip -l "$APK" | grep -qE "[[:space:]]assets/bootstrap.zip\$" || exit 13
unzip -p "$APK" assets/bootstrap.zip > /tmp/bs.zip 2>/dev/null || exit 13
unzip -l /tmp/bs.zip | grep -qE "[[:space:]]app/LICENSE\$" || {
  echo "!! bootstrap.zip 里没有 app/LICENSE（上游 10Router 的许可声明，随包义务）"; exit 15; }
echo "✅ 上游 LICENSE 随包"
# 版本号自证：manifest 压过 aapt2 的 --version-code（实测），所以只能看出包结果
BADGE=$($BT/aapt2 dump badging "$APK" 2>/dev/null | head -1)
echo "$BADGE" | grep -q "versionCode='$VCODE'" || { echo "!! 版本号不是 $VCODE：$BADGE"; exit 14; }
echo "✅ 版本 $VNAME (versionCode $VCODE)"
unzip -l "$APK" | grep -E "assets/" | head -20
ls -la "$APK"
echo MARK_PHASE3_DONE
