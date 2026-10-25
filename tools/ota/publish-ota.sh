#!/usr/bin/env bash
# publish-ota.sh — 生成/更新 OTA 清单 updates.json（服务端预烘焙流水线入口）
#
# 设计：组件级 OTA 热更新。本脚本只负责"把一份已打好的载荷登记进清单"，
# 并（可选）复制到 landing 站点、校验线上可达。补丁必须在服务端重放，
# 设备端只下载成品并 sha256 校验。
#
# 用法：
#   tools/ota/publish-ota.sh -c <component> -v <x.y.z> -f <payload.zip> \
#       -u <mirror1> [-u <mirror2> ...] [--apk-min N] [--rollout 0.1] \
#       [-n "note"]... [-o updates.json] [--landing /opt/apps/sites/landing/10router]
#
# 校验（硬规则）：
#   - component 仅 [a-zA-Z0-9_-]
#   - version 必须 semver x.y.z，且严格高于已登记版本（防降级）
#   - 每个 mirror 必须 https://
#   - rollout ∈ [0,1]；apk-min 整数
#   - size/sha256 默认自动算，可显式覆盖
set -euo pipefail

OUT="$PWD/updates.json"
LANDING_ROOT=""
COMP=""; VER=""; FILE=""; APK_MIN=""; SIZE=""; SHA=""; FLEXIBLE=1; FORCE=0; ROLLOUT=""
declare -a MIRRORS=(); declare -a NOTES=()

usage() { sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -c|--component) COMP="$2"; shift 2;;
    -v|--version)   VER="$2"; shift 2;;
    -f|--file)      FILE="$2"; shift 2;;
    -u|--url|--mirror) MIRRORS+=("$2"); shift 2;;
    --apk-min)      APK_MIN="$2"; shift 2;;
    --size)         SIZE="$2"; shift 2;;
    --sha256)       SHA="$2"; shift 2;;
    --no-flexible)  FLEXIBLE=0; shift;;
    --force)        FORCE=1; shift;;
    --rollout)      ROLLOUT="$2"; shift 2;;
    -n|--note)      NOTES+=("$2"); shift 2;;
    -o|--out)       OUT="$2"; shift 2;;
    --landing)      LANDING_ROOT="$2"; shift 2;;
    -h|--help)      usage 0;;
    *) echo "未知参数: $1" >&2; usage 1;;
  esac
done

[[ -n "$COMP" && -n "$VER" && -n "$FILE" && ${#MIRRORS[@]} -gt 0 ]] \
  || { echo "缺少必填参数（-c -v -f 至少 1 个 -u）" >&2; usage 1; }
[[ -f "$FILE" ]] || { echo "载荷文件不存在: $FILE" >&2; exit 1; }
[[ "$COMP" =~ ^[a-zA-Z0-9_-]+$ ]] || { echo "component 非法: $COMP" >&2; exit 1; }
[[ "$VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "version 非法（需 semver x.y.z）: $VER" >&2; exit 1; }
for u in "${MIRRORS[@]}"; do
  [[ "$u" == https://* ]] || { echo "镜像必须用 https: $u" >&2; exit 1; }
done
if [[ -n "$ROLLOUT" ]]; then
  awk -v r="$ROLLOUT" 'BEGIN{ if(r+0<0||r+0>1) exit 1 }' \
    || { echo "rollout 需 0..1: $ROLLOUT" >&2; exit 1; }
fi
[[ -z "$APK_MIN" || "$APK_MIN" =~ ^[0-9]+$ ]] || { echo "apk-min 需整数: $APK_MIN" >&2; exit 1; }

SIZE="${SIZE:-$(stat -c%s "$FILE")}"
SHA="${SHA:-$(sha256sum "$FILE" | awk '{print $1}')}"

# ---- 版本单调（不降级） ----
if [[ -f "$OUT" ]]; then
  OLD=$(jq -r --arg c "$COMP" '.components[$c].version // empty' "$OUT")
  if [[ -n "$OLD" ]]; then
    if ! awk -v o="$OLD" -v n="$VER" 'BEGIN{
      split(o,a,"."); split(n,b,".");
      for(i=1;i<=3;i++){ ao=a[i]+0; an=b[i]+0;
        if(an>ao){ok=1;break} if(an<ao){ok=0;break} }
      exit (ok==1?0:1)
    }'; then
      echo "拒绝降级：$COMP 已发布 $OLD，新 $VER 不更高" >&2; exit 1
    fi
    echo "更新组件 $COMP: $OLD -> $VER"
  else
    echo "新增组件 $COMP @ $VER"
  fi
else
  echo "新建清单，新增 $COMP @ $VER"
fi

NOTES_JSON=$(printf '%s\n' "${NOTES[@]}" | jq -R . | jq -s .)
MIRRORS_JSON=$(printf '%s\n' "${MIRRORS[@]}" | jq -R . | jq -s .)
COMP_JSON=$(jq -n \
  --arg v "$VER" --argjson sz "$SIZE" --arg sha "$SHA" \
  --argjson fl "$FLEXIBLE" --argjson fo "$FORCE" \
  --arg apk "$APK_MIN" --argjson mirrors "$MIRRORS_JSON" --argjson notes "$NOTES_JSON" \
  --arg rollout "$ROLLOUT" \
  '{
     version:$v, size:$sz, sha256:$sha,
     flexible:$fl, force:$fo,
     minAppVersionCode:(if $apk=="" then null else ($apk|tonumber) end),
     rollout:(if $rollout=="" then null else ($rollout|tonumber) end),
     minFreeBytes:null, sigAlgorithm:"sha256",
     mirrors:$mirrors, notes:$notes
   }')

TMP=$(mktemp)
if [[ -f "$OUT" ]]; then
  jq --arg c "$COMP" --argjson comp "$COMP_JSON" \
     '.components[$c]=$comp | .schema=(.schema//1)' "$OUT" > "$TMP"
else
  jq -n --arg c "$COMP" --argjson comp "$COMP_JSON" \
     '{schema:1, components:{($c):$comp}}' > "$TMP"
fi

# 填 etag(内容指纹) + generatedAt + ttl
ETAG=$(sha256sum "$TMP" | awk '{print $1}')
jq --arg e "$ETAG" --arg g "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '.etag=$e | .generatedAt=$g | .ttlSeconds=(.ttlSeconds//86400)' \
   "$TMP" > "$OUT"
rm -f "$TMP"

# ---- 发布到 landing + 线上校验 ----
if [[ -n "$LANDING_ROOT" ]]; then
  OTA="$LANDING_ROOT/ota"
  mkdir -p "$OTA/payloads"
  cp "$FILE" "$OTA/payloads/$(basename "$FILE")"
  cp "$OUT" "$OTA/updates.json"
  echo "已复制载荷+payload 到 $OTA"
  PUB_BASE="https://das-sein.top/10router/ota"
  code=$(curl -sS -o /dev/null -w "%{http_code}" --max-time 20 "$PUB_BASE/updates.json")
  if [[ "$code" == "200" ]]; then echo "线上 manifest OK ($code)"; else echo "线上 manifest 异常: $code" >&2; exit 1; fi
fi

echo "完成：$OUT  [$COMP $VER sha256=$SHA]"
