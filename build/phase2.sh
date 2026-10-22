#!/bin/bash
# Phase 2 on tcloud: clone 10Router public snapshot, build, assemble standalone app dir, x86 sanity run
set -x
B=/home/ubuntu/10droid; cd $B || exit 99

REPO=""
for R in https://github.com/some-du6e/10router.git https://github.com/techysy/10router.git; do
  if git ls-remote "$R" HEAD >/dev/null 2>&1; then REPO=$R; break; fi
done
[ -n "$REPO" ] || { echo "NO_REPO"; exit 90; }
echo "USING_REPO $REPO"

rm -rf tenrouter-src
git clone --depth 1 "$REPO" tenrouter-src || exit 89
cd tenrouter-src || exit 89
git log --oneline -1
npm config set registry https://registry.npmmirror.com
npm install --no-audit --no-fund --loglevel=warn || exit 88
npm run build || exit 87

S=.next/standalone
[ -f "$S/server.js" ] || { echo "NO_STANDALONE"; exit 86; }
cp -r .next/static $S/.next/static
[ -d public ] && cp -r public $S/public
cp -r open-sse $S/ 2>/dev/null
cp -r src/mitm $S/src/ 2>/dev/null
mkdir -p $S/node_modules && cp -r node_modules/node-forge $S/node_modules/ 2>/dev/null
cp package.json $S/package.json 2>/dev/null

# foreign-arch native modules inventory (must NOT be required unconditionally at startup)
find $S -name '*.node' -not -path '*/canvases/*' | tee $B/native-modules.txt
echo "NATIVE_COUNT $(wc -l < $B/native-modules.txt)"
# env vars the app supports (for .env template)
grep -rhoE "process\.env\.[A-Z0-9_]+" $S/open-sse $S/.next 2>/dev/null | sort -u | head -60 > $B/env-vars.txt

rm -rf $B/app; mkdir -p $B/app
cp -a $S/. $B/app/
printf 'PORT=20128\nHOSTNAME=127.0.0.1\n' > $B/app/.env

# ---- x86 sanity: does the assembled standalone boot and answer health? ----
cd $B/app
nohup node server.js > $B/app-run.log 2>&1 &
SANPID=$!
sleep 15
curl -s --max-time 6 http://127.0.0.1:20128/api/health; echo
curl -s -o /dev/null -w "dashboard:%{http_code}\n" --max-time 6 http://127.0.0.1:20128/dashboard
tail -20 $B/app-run.log
kill $SANPID 2>/dev/null
sleep 2; kill -9 $SANPID 2>/dev/null
echo MARK_PHASE2_DONE
