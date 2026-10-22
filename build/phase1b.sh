#!/bin/bash
# Phase 1b on tcloud: correct Termux package name is `nodejs`, re-extract pfx, static ELF verify
set -x
B=/home/ubuntu/10droid; cd $B || exit 9
apt-get install -y python3-pil fonts-dejavu-core >/dev/null 2>&1

python3 resolve_deps.py nodejs ca-certificates > urls-node.txt 2> deps-missing.txt
cat deps-missing.txt; echo ---; cat urls-node.txt
mkdir -p debs2 && cd debs2
while read -r u; do
  f=$(basename "$u")
  [ -f "$f" ] || curl -fsSL --retry 3 --max-time 300 -O "$u" || echo "FAILDL $u"
done < ../urls-node.txt
ls -la
cd $B
rm -rf pfx; mkdir pfx
for d in debs2/*.deb; do
  rm -f debs2/data.tar.*
  ( cd debs2 && ar x "$(basename "$d")" )
  tar xf "$(ls debs2/data.tar.* | head -1)" -C pfx || { echo "FAILEXTRACT $d"; exit 95; }
done
mv pfx/data/data/com.termux/files/usr pfx/usr && rm -rf pfx/data
mkdir -p pfx/usr/etc/ssl
cp /etc/ssl/certs/ca-certificates.crt pfx/usr/etc/ssl/ca-bundle.pem
du -sh pfx/usr/bin/node pfx/usr/lib
readelf -l pfx/usr/bin/node | grep -iE "interp|linker"
find pfx/usr/share -name "icudt*.dat"
python3 check_elf.py pfx/usr
echo MARK_PHASE1B_DONE
