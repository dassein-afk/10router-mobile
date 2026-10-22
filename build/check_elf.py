#!/usr/bin/env python3
"""Static ELF closure check for a Termux-pfx tree extracted for Android.
Walks bin/ and lib/, collects DT_NEEDED via readelf, verifies each soname is
either present under pfx lib/ or is a bionic/system library. Also prints
PT_INTERP for executables (must be /system/bin/linker64)."""
import subprocess, sys, os

root = sys.argv[1]
libs = set()
libdir = os.path.join(root, "lib")
# available by EXACT name (file or symlink) — linker resolves DT_NEEDED by exact name
for dirpath, _, files in os.walk(libdir):
    for f in files:
        libs.add(f)
        p = os.path.join(dirpath, f)
        if os.path.islink(p):
            continue
        try:
            with open(p, "rb") as fh:
                if fh.read(4) != b"\x7fELF":
                    continue
        except Exception:
            continue
        try:
            out = subprocess.check_output(["readelf", "-d", p], text=True)
            for line in out.splitlines():
                if "(SONAME)" in line:
                    sonames.add(line.split("[", 1)[1].rstrip("]"))
        except Exception:
            pass
system_ok = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
             "libz.so", "libstdc++.so", "libc++.so", "libandroid-support.so",
             "libcompiler_rt.so", "ld-android.so", "libcamera2ndk.so",
             "libnativewindow.so", "libmediandk.so", "libEGL.so", "libGLESv2.so",
             "libvulkan.so", "libOpenSLES.so", "libGLESv1_CM.so"}

missing, interps, nointerp = {}, set(), []
targets = []
for sub in ("bin", "lib", "libexec"):
    d = os.path.join(root, sub)
    if os.path.isdir(d):
        for f in sorted(os.listdir(d)):
            p = os.path.join(d, f)
            if os.path.isfile(p) and not os.path.islink(p):
                targets.append(p)

for p in targets:
    try:
        head = open(p, "rb").read(4)
        if head != b"\x7fELF":
            continue
    except Exception:
        continue
    d = subprocess.check_output(["readelf", "-d", p], text=True, errors="replace")
    need = [l.split("[", 1)[1].rstrip("]") for l in d.splitlines() if "(NEEDED)" in l]
    for n in need:
        base = os.path.basename(n)
        if n in libs or base in libs or n in system_ok or base in system_ok:
            continue
        missing.setdefault(p, []).append(n)
    l = subprocess.check_output(["readelf", "-l", p], text=True, errors="replace")
    interp = [w for w in l.split() if w.endswith("linker64") or "linker" in w and w.startswith("/")]
    if p.startswith(os.path.join(root, "bin")):
        if any("system/bin/linker64" in i for i in interp):
            interps.add("system:" + p)
        elif interp:
            interps.add("ODD " + str(interp) + " " + p)
        else:
            nointerp.append(p)
    rp = [l2.split("[", 1)[1].rstrip("]") for l2 in d.splitlines() if "(RUNPATH)" in l2 or "(RPATH)" in l2]
    for r in rp:
        if "com.termux" in r:
            print(f"RUNPATH-TERMUX {p}: {r}  (will be overridden by LD_LIBRARY_PATH)")

print("== INTERP summary ==")
print("system-linker bins:", len([x for x in interps if x.startswith('system:')]))
odd = [x for x in interps if x.startswith("ODD")]
print("odd interps:", odd if odd else "none")
print("bins with no interp (static?):", [x.replace(root+"/","") for x in nointerp] or "none")
print("== MISSING NEEDED ==")
if not missing:
    print("CLOSED-OK: every NEEDED resolves in pfx or bionic system")
else:
    for k, v in missing.items():
        print("MISSING", k.replace(root+"/", ""), v)
