#!/bin/bash
# Phase 2b on tcloud: fetch 10Router via release tarball (git over 443 stalls from CN), build, assemble, x86 sanity
set -x
B=/home/ubuntu/10droid; cd $B || exit 99

# kill any stuck git from the old chain
pkill -f "git ls-remote" 2>/dev/null
pkill -f "git clone" 2>/dev/null

# resolve latest release tag (fallback: main)
TAG=$(curl -fsSL --max-time 20 https://api.github.com/repos/techysy/10router/releases/latest | grep -m1 '"tag_name"' | cut -d'"' -f4)
[ -n "$TAG" ] || TAG=main
echo "USING_TAG $TAG"
rm -f src.tar.gz
curl -fsSL --retry 3 --max-time 600 -o src.tar.gz \
  "https://codeload.github.com/techysy/10router/tar.gz/refs/tags/$TAG" || \
curl -fsSL --retry 3 --max-time 600 -o src.tar.gz \
  "https://codeload.github.com/techysy/10router/tar.gz/refs/heads/main" || exit 90
ls -la src.tar.gz
rm -rf tenrouter-src; mkdir tenrouter-src
tar xzf src.tar.gz -C tenrouter-src --strip-components=1 || exit 89
cd tenrouter-src
npm config set registry https://registry.npmmirror.com
if [ -f package-lock.json ]; then npm ci --no-audit --no-fund --loglevel=warn || npm install --no-audit --no-fund --loglevel=warn || exit 88
else npm install --no-audit --no-fund --loglevel=warn || exit 88; fi
npm run build || exit 87

S=.next/standalone
[ -f "$S/server.js" ] || { echo "NO_STANDALONE"; exit 86; }
cp -r .next/static $S/.next/static
[ -d public ] && cp -r public $S/public
cp -r open-sse $S/ 2>/dev/null
cp -r src/mitm $S/src/ 2>/dev/null
mkdir -p $S/node_modules && cp -r node_modules/node-forge $S/node_modules/ 2>/dev/null

find $S -name '*.node' | tee $B/native-modules.txt
echo "NATIVE_COUNT $(wc -l < $B/native-modules.txt)"
grep -rhoE "process\.env\.[A-Z0-9_]+" open-sse src 2>/dev/null | sort -u | head -80 > $B/env-vars.txt

rm -rf $B/app; mkdir -p $B/app
cp -a $S/. $B/app/
printf 'PORT=20128\nHOSTNAME=127.0.0.1\n' > $B/app/.env
du -sh $B/app

# ---- x86 sanity on port 20130 (avoid any collision) ----
cd $B/app
PORT=20130 HOSTNAME=127.0.0.1 nohup node server.js > $B/app-run.log 2>&1 &
SANPID=$!
sleep 15
curl -s --max-time 6 http://127.0.0.1:20130/api/health; echo
curl -s -o /dev/null -w "dashboard:%{http_code}\n" --max-time 6 http://127.0.0.1:20130/dashboard
tail -15 $B/app-run.log
kill $SANPID 2>/dev/null; sleep 2; kill -9 $SANPID 2>/dev/null
echo MARK_PHASE2B_DONE
