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
# 构建机 sshd 会限流（kex_exchange_identification: read: Connection reset by peer）。
# 这一步前面已经跑完 6 个补丁重放，直接失败等于全白费 —— 先探活并退避重试。
for i in 1 2 3 4 5; do
  # ❗必须 </dev/null：ssh 会吞掉 stdin，而后面那条命令要往 heredoc 喂脚本，
  # 不重定向会让 heredoc 内容整个丢失，远端 bash -s 拿到空脚本 → 报出莫名其妙的路径错误。
  if "${SSH[@]}" "true" </dev/null >/dev/null 2>&1; then break; fi
  echo "  SSH 暂不可用（第 $i 次），等 30 秒重试…"
  sleep 30
  [ "$i" = 5 ] && { echo "❌ 构建机 SSH 持续不可用，检查是否被限流/封禁"; exit 5; }
done
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
scp -q -o StrictHostKeyChecking=no -i "$KEY" "$ROOT/tools/flatten_layers.py" "$TCLOUD:/home/ubuntu/10droid/flatten_layers.py"
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
# 退出码 0 还不够：必须看到成功标记，否则等于"跑过但没做完"（静默成功是这里最贵的故障）
grep -q "CD-BUILD-OK" /tmp/build-cd.log || { echo "❌ CD 构建没有完成标记"; tail -20 /tmp/build-cd.log; exit 8; }
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
# 镜像标签必须与本次发布的版本号一致。
# 曾经写死 `releases/download/latest/`，ghfast 不认这个标签、恒 404，
# 用户侧的"更新按钮"于是永远显示"检查失败"。这个断言就是防它再漂。
VN=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$ROOT/apk-src/AndroidManifest.xml" | head -1)
MIRROR_TAG=$(sed -n 's|.*MIRROR_URL = "https://ghfast.top/https://github.com/dassein-afk/10router-mobile/releases/download/\([^/]*\)/".*|\1|p' "$ROOT/app/src/top/dassein/tenrouter/KeepAlive.java" | head -1)
echo "  镜像标签: ${MIRROR_TAG:-（没读到）}  版本号: v$VN"
[ "$MIRROR_TAG" = "v$VN" ] || { echo "  ❌ 镜像标签与 versionName 不一致 —— 发出去更新按钮又会检查失败"; exit 9; }
case "$MIRROR_TAG" in
  latest) echo "  ❌ 镜像标签是 latest：ghfast 不解析，恒 404"; exit 9 ;;
esac
echo "  ✅ 镜像标签与版本号一致"

# 打出来的包里必须真有本次要新增的组件。
# 踩过的坑：我改了 app/AndroidManifest.xml 的 <service>，但打包用的是 apk-src/AndroidManifest.xml，
# 构建一路绿灯、包也签了，装到手机上那两个服务根本不存在——"构建成功"不等于"改的东西进去了"。
for SVC in GuardService A11yAnchorService; do
  if Q=$( "${SSH[@]}" "\$HOME/10droid/android-sdk/build-tools/34.0.0/aapt2 dump xmltree --file AndroidManifest.xml '$APK'" ) \
     && printf '%s' "$Q" | grep -q "\.$SVC"; then
    echo "  ✅ 组件 $SVC 在编译后的 manifest 里"
  else
    echo "  ❌ 组件 $SVC 不在包里 —— 改的是 app/AndroidManifest.xml 而不是 apk-src/AndroidManifest.xml？"
    exit 10
  fi
done

# ── 保活三个"自杀性"判据的反断言（v0.0.7 实锤过，全都会把正常状态当故障） ──
# 这三条都是"代码看起来合理、跑起来自己打自己"，只能靠静态断言钉住。
echo "===== 自证：三个自杀性判据已修"
# ❗断言前必须剥注释：解释性注释里会逐字引用旧代码（本轮就因此误报了一次）。
# 只查 cdLoop 块内；loop() 里那条是对的（launchOnce 阻塞到 node 退出）。
if sed -n '/private void cdLoop/,/^    }/p' "$ROOT/app/src/top/dassein/tenrouter/GatewayService.java" \
   | sed 's|//.*||' | grep -qs 'startedAt > 60_000'; then
  echo "  ❌ cdLoop 仍在用「跑满 60 秒才重置退避」判据 → 额度管家会每 60 秒被重复拉起"
  exit 14
fi
grep -qs 'boolean hbFresh' "$ROOT/app/src/top/dassein/tenrouter/GuardService.java" \
  || { echo "  ❌ 守卫进程没有用心跳判活 → 会把网关冷启窗口当主进程死亡，造成启动风暴"; exit 14; }
grep -qs '连续无响应，重启 node' "$ROOT/app/src/top/dassein/tenrouter/GatewayService.java" \
  && { echo "  ❌ 看门狗仍会主动 destroy 正在冷启的 node"; exit 14; }
echo "  ✅ cdLoop 不再空转、守卫用心跳判活、看门狗不杀健康 node"

# ── v0.0.8：秒退必须退避 / 彻底退出 / VPN 真能关 ──
# cdLoop 里「spawn 成功就 backoff=2」会让 1 秒就死的进程被立刻重拉（v0.0.7 实测每秒 2 行日志）。
if sed -n '/private void cdLoop/,/^    }/p' "$ROOT/app/src/top/dassein/tenrouter/GatewayService.java" \
   | sed 's|//.*||' | grep -qs 'alive) {[[:space:]]*$'; then
  echo "  ❌ cdLoop 又把「spawn 成功」当成健康了 —— 秒退进程会被无限重拉"
  exit 15
fi
grep -qs 'boolean stable = lived > 60_000L' "$ROOT/app/src/top/dassein/tenrouter/GatewayService.java" \
  || { echo "  ❌ cdLoop 缺少「活过 60 秒才算稳定」判据"; exit 15; }

# 彻底退出必须存在，且四个自愈入口都认这个标记
grep -qs 'public static void shutdownAll' "$ROOT/app/src/top/dassein/tenrouter/KeepAlive.java" \
  || { echo "  ❌ 没有 shutdownAll，用户无法彻底关闭"; exit 16; }
for F in GuardService GuardLink WatchdogJob GatewayService; do
  grep -qs 'userStopped' "$ROOT/app/src/top/dassein/tenrouter/$F.java" \
    || { echo "  ❌ $F 没检查 userStopped —— 用户点了退出会被它拉回来"; exit 16; }
done
grep -qs 'clearUserStopped' "$ROOT/app/src/top/dassein/tenrouter/MainActivity.java" \
  || { echo "  ❌ MainActivity 没清 user_stopped —— 退出后重开 App 会永久失效"; exit 16; }

# VPN 必须用 stopService，不能用 startService(ACTION_STOP)
if sed -n '/private static void stopVpnAnchor/,/^    }/p' "$ROOT/app/src/top/dassein/tenrouter/KeepAlive.java" \
   | sed 's|//.*||' | grep -qs 'startService'; then
  echo "  ❌ stopVpnAnchor 仍在用 startService —— 服务没跑时会先把隧道建起来再拆"
  exit 17
fi
grep -qs 'forceCloseTun' "$ROOT/app/src/top/dassein/tenrouter/VpnKeepAliveService.java" \
  || { echo "  ❌ 缺 forceCloseTun，点关之后 tun 可能仍挂着"; exit 17; }
grep -qs 'anchorWanted' "$ROOT/app/src/top/dassein/tenrouter/VpnKeepAliveService.java" \
  || { echo "  ❌ 服务没问 anchorWanted —— START_STICKY 重建会无视用户已关"; exit 17; }
# 界面：已授权也要给按钮，不能只剩一个绿标签
if grep -qs "s.notif ? null" "$ROOT/app/ui/app.js"; then
  echo "  ❌ 设置页已授权时按钮仍会消失"; exit 18
fi
echo "  ✅ 秒退退避 / 彻底退出四路认标记 / VPN 用 stopService / 已授权也有按钮"

# ── v0.0.9：不许在启动时骚扰用户 / 华为自启动入口 / 守卫不许盲投 ──
# ① onCreate 里不许无条件跳系统页（v0.0.8 实测：每次开 App 都被弹去电池优化+通知设置）
if sed -n '/protected void onCreate/,/new Thread(this::prepare)/p' \
     "$ROOT/app/src/top/dassein/tenrouter/MainActivity.java" \
   | sed 's|//.*||' | grep -qsE '^[^/]*\b(askNotificationPermission|ensureBatteryExempt)\(\);'; then
  echo "  ❌ onCreate 里仍在自动跳系统页 —— 用户每次启动都会被弹去电池优化/通知设置"
  exit 19
fi
grep -qs 'askNotificationPermission();' "$ROOT/app/src/top/dassein/tenrouter/MainActivity.java" \
  || { echo "  ❌ 反断言前提不成立：askNotificationPermission 调用点全没了，设置页按钮会失效"; exit 19; }
# ② 华为自启动必须在「启动管理」，不能在「应用管理」里找（用户实测那里压根没有这一项）
sed -n '/public static String openAutostartSettings/,/^    }/p' \
     "$ROOT/app/src/top/dassein/tenrouter/KeepAlive.java" | head -12 \
  | grep -qs 'StartupNormalAppListActivity' \
  || { echo "  ❌ 自启动候选第一位不是「启动管理」—— 华为会跳进没有自启动项的应用管理页"; exit 20; }
grep -qs '设置 → 电池 → 启动管理' "$ROOT/app/src/top/dassein/tenrouter/KeepAlive.java" \
  || { echo "  ❌ 华为的手动路径没写「设置 → 电池 → 启动管理」"; exit 20; }
# ③ 守卫拉起网关前必须判状态（startForegroundService 对已存在的服务 = 再投一次 onStartCommand）
grep -qs 'isGatewayProbablyRunning' "$ROOT/app/src/top/dassein/tenrouter/GuardLink.java" \
  || { echo "  ❌ 守卫盲投 startForegroundService —— 会反复打断正在启动的 node"; exit 21; }
echo "  ✅ 启动不骚扰 / 华为走启动管理 / 守卫先判状态再拉"
echo "===== 完成（新包还没投放；要发布再走上传 + 飞书那一步）"
