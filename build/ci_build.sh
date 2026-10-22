#!/usr/bin/env bash
# One-shot CI build: Termux arm64 runtime + 10Router standalone + signed APK.
# Proven locally on tcloud (v1.5.0/1.5.1); this script is the distilled pipeline.
#
# Required env: VERSION_NAME (e.g. 1.5.2), VERSION_CODE (e.g. 152),
#   ANDROID_HOME (SDK root with build-tools/34.0.0 + platforms/android-34),
#   KEYSTORE_B64 + ANDROID_KEYSTORE_PASS (release signing; if absent, a debug
#   keystore is generated — upgrades across keystore changes are impossible).
# Optional: WORK (scratch dir), ROUTER_TAG (pin 10Router release), NPM_REGISTRY.
set -euo pipefail
: "${VERSION_NAME:?}" "${VERSION_CODE:?}" "${ANDROID_HOME:?}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
B="${WORK:-$REPO_ROOT/.ci-work}"; mkdir -p "$B"; cd "$B"
BT="$ANDROID_HOME/build-tools/34.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"
[ -x "$BT/aapt2" ] && [ -f "$PLATFORM" ] || { echo "SDK missing: need build-tools;34.0.0 + platforms;android-34"; exit 2; }
command -v java >/dev/null || { echo "JDK 17+ required"; exit 2; }

# ---------- 1. Termux aarch64 runtime (bionic ELF, NOT glibc Node) ----------
if [ ! -x pfx/usr/bin/node ]; then
  python3 "$REPO_ROOT/build/resolve_deps.py" nodejs ca-certificates > urls-node.txt
  mkdir -p debs && ( cd debs
    while read -r u; do
      f=$(basename "$u")
      [ -f "$f" ] || curl -fsSL --retry 3 --max-time 300 -O "$u"
    done < ../urls-node.txt )
  rm -rf pfx; mkdir pfx
  for d in debs/*.deb; do
    rm -f debs/data.tar.*
    ( cd debs && ar x "$(basename "$d")" )
    tar xf "$(ls debs/data.tar.* | head -1)" -C pfx || { echo "FAILEXTRACT $d"; exit 95; }
  done
  mv pfx/data/data/com.termux/files/usr pfx/usr && rm -rf pfx/data
  mkdir -p pfx/usr/etc/ssl
  cp /etc/ssl/certs/ca-certificates.crt pfx/usr/etc/ssl/ca-bundle.pem
  readelf -l pfx/usr/bin/node | grep -qi linker64 || { echo "node not bionic-linked"; exit 96; }
  python3 "$REPO_ROOT/build/check_elf.py" pfx/usr
fi

# ---------- 2. 10Router standalone build ----------
if [ ! -f app/.ci-app-ok ]; then
  TAG="${ROUTER_TAG:-$(curl -fsSL --max-time 20 https://api.github.com/repos/techysy/10router/releases/latest | grep -m1 '"tag_name"' | cut -d'"' -f4)}"
  [ -n "$TAG" ] || TAG=main
  echo "USING_ROUTER_TAG $TAG"
  rm -rf tenrouter-src; mkdir tenrouter-src
  curl -fsSL --retry 3 --max-time 600 -o src.tar.gz "https://codeload.github.com/techysy/10router/tar.gz/refs/tags/$TAG" \
    || curl -fsSL --retry 3 --max-time 600 -o src.tar.gz "https://codeload.github.com/techysy/10router/tar.gz/refs/heads/main"
  tar xzf src.tar.gz -C tenrouter-src --strip-components=1
  ( cd tenrouter-src
    npm config set registry "${NPM_REGISTRY:-https://registry.npmjs.org}"
    if [ -f package-lock.json ]; then npm ci --no-audit --no-fund --loglevel=warn || npm install --no-audit --no-fund --loglevel=warn
    else npm install --no-audit --no-fund --loglevel=warn; fi
    npm run build
    S=.next/standalone
    [ -f "$S/server.js" ] || { echo "NO_STANDALONE"; exit 86; }
    cp -r .next/static "$S/.next/static"
    [ -d public ] && cp -r public "$S/public"
    cp -r open-sse "$S/" 2>/dev/null || true
    mkdir -p "$S/src" && cp -r src/mitm "$S/src/" 2>/dev/null || true
    mkdir -p "$S/node_modules" && cp -r node_modules/node-forge "$S/node_modules/" 2>/dev/null || true
    # entry MUST be custom-server.js (stamps loopback peer headers; server.js alone
    # makes the on-device WebView look "remote" -> dashboard locked, v1.5.0 lesson)
    cp custom-server.js "$S/custom-server.js"
    rm -rf "$B/app"; mkdir -p "$B/app"
    cp -a "$S/." "$B/app/"
  )
  printf 'PORT=20128\nHOSTNAME=127.0.0.1\n' > app/.env

  # x86 sanity: the real entry, real health + real loopback verdict
  ( cd app
    PORT=20130 HOSTNAME=127.0.0.1 setsid node custom-server.js > "$B/app-run.log" 2>&1 &
    SRV=$!
    for i in $(seq 1 30); do
      sleep 2
      H=$(curl -s --max-time 5 http://127.0.0.1:20130/api/health || true)
      echo "$H" | grep -q '"ok":true' && break
    done
    echo "$H"
    curl -fsS --max-time 8 http://127.0.0.1:20130/api/auth/status | grep -q bootstrapLocal \
      || { echo "SANITY: loopback not detected"; tail -20 "$B/app-run.log"; exit 85; }
    kill $SRV 2>/dev/null || true
  )
  touch app/.ci-app-ok
fi

# ---------- 3. trim + zip payload ----------
find app -name '*.node' -delete            # foreign-arch natives; node:sqlite covers DB
find pfx/usr -type l -print0 | while IFS= read -r -d '' l; do   # java.util.zip can't restore symlinks
  t=$(realpath "$l" 2>/dev/null) || continue
  [ -f "$t" ] && { rm -f "$l"; cp -f "$t" "$l"; }
done
rm -rf app/.next/cache pfx/usr/share/man pfx/usr/share/doc pfx/usr/include app/.ci-app-ok 2>/dev/null || true
mkdir -p apk/assets
if [ "${REUSE_BOOTSTRAP:-0}" != 1 ] || [ ! -s apk/assets/bootstrap.zip ]; then
  zip -q -9 -r apk/assets/bootstrap.zip pfx app
fi
ls -la apk/assets/bootstrap.zip

# ---------- 4. compile shell (no Gradle) ----------
rm -rf apk-src work; mkdir -p work/classes work/dex
cp -a "$REPO_ROOT/app" apk-src && cd apk-src
sed -i "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VERSION_CODE\"/; s/android:versionName=\"[^\"]*\"/android:versionName=\"$VERSION_NAME\"/" AndroidManifest.xml
"$BT/aapt2" compile --dir res -o work/res.zip
"$BT/aapt2" link -o work/app.unsigned.apk -I "$PLATFORM" \
  --manifest AndroidManifest.xml --min-sdk-version 24 --target-sdk-version 28 --auto-add-overlay work/res.zip
javac -nowarn -classpath "$PLATFORM" -source 1.8 -target 1.8 -d work/classes $(find src -name '*.java')
"$BT/d8" --min-api 24 --lib "$PLATFORM" --output work/dex $(find work/classes -name '*.class')
cd work
cp app.unsigned.apk temp.apk
zip -q -j temp.apk dex/classes.dex
(cd "$B/apk" && zip -q -X "$B/apk-src/work/temp.apk" assets/bootstrap.zip)
"$BT/zipalign" -f -p 4 temp.apk aligned.apk

# ---------- 5. sign ----------
KS="$B/ci.keystore"
if [ -n "${KEYSTORE_B64:-}" ]; then
  printf '%s' "$KEYSTORE_B64" | base64 -d > "$KS"
  : "${ANDROID_KEYSTORE_PASS:?}"
else
  echo "WARN: no KEYSTORE_B64 — generating throwaway key (upgrades will fail)"
  ANDROID_KEYSTORE_PASS="${ANDROID_KEYSTORE_PASS:-$(head -c 12 /dev/urandom | base64)}"
  keytool -genkeypair -v -keystore "$KS" -alias 10router -storepass "$ANDROID_KEYSTORE_PASS" -keypass "$ANDROID_KEYSTORE_PASS" \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=10Router Android,OU=das-sein,O=das-sein,L=Internet,C=CN" >/dev/null 2>&1
fi
APK="$B/dist/10router-android-$VERSION_NAME.apk"; mkdir -p "$B/dist"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$ANDROID_KEYSTORE_PASS" --key-pass "pass:$ANDROID_KEYSTORE_PASS" --out "$APK" aligned.apk
"$BT/apksigner" verify --print-certs "$APK" | head -4
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
SIZE=$(stat -c%s "$APK")
echo "RESULT apk=$APK size=$SIZE sha256=$SHA"
[ -n "${GITHUB_OUTPUT:-}" ] && { echo "apk=$APK" >> "$GITHUB_OUTPUT"; echo "size=$SIZE" >> "$GITHUB_OUTPUT"; echo "sha256=$SHA" >> "$GITHUB_OUTPUT"; }
exit 0
