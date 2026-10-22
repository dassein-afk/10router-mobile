#!/bin/bash
# Phase 1 on tcloud: toolchain + Android SDK + Termux aarch64 node runtime
set -x
export DEBIAN_FRONTEND=noninteractive
B=/home/ubuntu/10droid; mkdir -p $B; cd $B || exit 99

apt-get update -y
apt-get install -y openjdk-17-jdk-headless unzip binutils xz-utils python3 ca-certificates curl || exit 91

# ---- Android SDK cmdline-tools (pin version for reproducibility) ----
[ -f "$B/android-sdk/cmdline-tools/latest/bin/sdkmanager" ] || {
  curl -fsSL -o cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip || exit 92
  rm -rf android-sdk-tmp; unzip -q cmdline-tools.zip -d android-sdk-tmp
  mkdir -p android-sdk/cmdline-tools; rm -rf android-sdk/cmdline-tools/latest
  mv android-sdk-tmp/cmdline-tools android-sdk/cmdline-tools/latest
}
yes | $B/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$B/android-sdk \
    "build-tools;34.0.0" "platforms;android-34" > $B/sdkmanager.log 2>&1
tail -3 $B/sdkmanager.log
ls $B/android-sdk/build-tools/34.0.0/aapt2 >/dev/null || exit 93
echo MARK_SDK_OK

# ---- Termux aarch64 runtime closure ----
cd $B
curl -fsSL -o Packages.gz https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz || exit 94
python3 resolve_deps.py node ca-certificates > urls.txt 2> deps-missing.txt
cat deps-missing.txt urls.txt
mkdir -p debs && cd debs
while read -r u; do
  f=$(basename "$u")
  [ -f "$f" ] || curl -fsSL --retry 3 -O "$u" || echo "FAILDL $u"
done < ../urls.txt
ls -la
cd $B
rm -rf pfx; mkdir -p pfx
for d in debs/*.deb; do
  rm -f debs/data.tar.*
  ( cd debs && ar x "$(basename "$d")" )
  tar xf "$(ls debs/data.tar.* | head -1)" -C pfx || { echo "FAILEXTRACT $d"; exit 95; }
done
mv pfx/data/data/com.termux/files/usr pfx/usr && rm -rf pfx/data
du -sh pfx/usr
ls pfx/usr/bin | head -20
echo "---- PT_INTERP / RUNPATH of node ----"
readelf -l pfx/usr/bin/node 2>/dev/null | grep -i interp
readelf -l pfx/usr/bin/node 2>/dev/null | grep -iA1 "NEEDED\|RUNPATH" | head -30
readelf -d pfx/usr/bin/node | grep -iE "runpath|rpath|needed"
node --version

# ---- CA bundle (bionic system certs are useless for node; ship Mozilla PEM) ----
cp /etc/ssl/certs/ca-certificates.crt $B/ca-bundle.pem
echo MARK_PHASE1_DONE
