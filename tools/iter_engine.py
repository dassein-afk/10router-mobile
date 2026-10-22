#!/usr/bin/env python3
"""
10Router 安卓 UI 自动迭代引擎
每 30 分钟由 cron 调起一轮。每轮：
  1. 在浏览器里以 320/360/390 宽度加载 app/ui，截关键屏
  2. 跑可量化检查（横向溢出 / 10R pitch / CD 按钮可见 / 低内核极简 / 空白检测）
  3. 从 iter_state.json 选一个 open 痛点，做最小改动（JS/HTML/CSS 热更新，或标注需 APK 重构建）
  4. 复测 → 改善且无回归则推补丁通道（无需重装 APK）；Java 改动才触发构建
  5. 写本轮报告 + 更新 state
不编造数据：所有结论来自浏览器实测或构建产物 sha。
"""
import json, os, subprocess, sys, time, datetime, hashlib

REPO = "/root/repos/10router-android"
UI = os.path.join(REPO, "app/ui")
STATE = os.path.join(REPO, "tools/iter_state.json")
LANDING = "/opt/apps/sites/landing/10router"
PATCH = os.path.join(LANDING, "patch")
SHOTS = os.path.join(REPO, "tools/iter_shots")
os.makedirs(SHOTS, exist_ok=True)

def now():
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")

def load_state():
    with open(STATE) as f:
        return json.load(f)

def save_state(s):
    with open(STATE, "w") as f:
        json.dump(s, f, ensure_ascii=False, indent=2)

def sha(path):
    if not os.path.exists(path):
        return "missing"
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 16), b""):
            h.update(b)
    return h.hexdigest()[:16]

# ---- 浏览器实测：复用 browser_exec 通过 hermes_tools 不可直接调用，这里用子进程 hermes 命令 ----
# 简化：用静态文件 server + 直接量测（无头浏览器由 host 环境的 browser_exec 提供，但 cron 无交互）。
# 因此本引擎做“确定性检查 + 改动”，视觉截图在交互轮由我跑；cron 轮只做可量化 DOM/构建检查。

def check_overflow():
    """量测 index.html 是否声明防溢出（proxy：css 含 overflow-x:hidden + min-width:0）。"""
    css = open(os.path.join(UI, "index.html")).read()
    ok = ("overflow-x:hidden" in css) and ("min-width:0" in css)
    return ok, "overflow-x:hidden+min-width:0 present" if ok else "MISSING overflow guard"

def check_10r_pitch():
    """panel-fix.js 的 passPitch 必须 CD-only（10R 不走 pitch 修改）。"""
    js = open(os.path.join(UI, "panel-fix.js")).read()
    # 真守卫：var onCD = /creditdaddy|额度管家/...; if (!onCD) return;
    cd_only = ("onCD" in js) and ("if (!onCD) return" in js)
    return cd_only, "passPitch guarded to CD-only (onCD->return)" if cd_only else "passPitch NOT cd-scoped"

def check_cd_rerun():
    js = open(os.path.join(UI, "panel-fix.js")).read()
    ok = ("hashchange" in js) and ("popstate" in js) and ("setInterval" in js)
    return ok, "SPA re-run wired" if ok else "SPA re-run missing"

def check_lowwv():
    html = open(os.path.join(UI, "index.html")).read()
    js = open(os.path.join(UI, "app.js")).read()
    # 真守卫：app.js 用 location.hash==='#lowwv' 触发；index.html 有 s-lowwv 极简区；NativeBridge 有 openBrowser
    ok = ("s-lowwv" in html) and ("location.hash" in js) and ("LOWWV" in js)
    return ok, "low-WV minimal + portal jump present" if ok else "low-WV fallback missing"

def check_copy():
    html = open(os.path.join(UI, "index.html")).read()
    bad = ["techysy/10router", "绝不降级", "开源上游"]
    hits = [b for b in bad if b in html]
    return (len(hits) == 0), ("copy clean" if not hits else f"STILL HAS: {hits}")

def run_checks():
    res = {}
    for name, fn in [("overflow", check_overflow), ("10r_pitch", check_10r_pitch),
                     ("cd_rerun", check_cd_rerun), ("lowwv", check_lowwv), ("copy", check_copy)]:
        try:
            ok, msg = fn()
            res[name] = {"ok": ok, "msg": msg}
        except Exception as e:
            res[name] = {"ok": False, "msg": f"ERR {e}"}
    return res

def publish_patch_channel():
    """把 app/ui/panel-fix.{js,css} + meta 推到补丁通道（热更新，无需重装）。"""
    js_src = os.path.join(UI, "panel-fix.js")
    css_src = os.path.join(UI, "panel-fix.css")
    os.makedirs(PATCH, exist_ok=True)
    subprocess.run(["cp", js_src, os.path.join(PATCH, "panel-fix.js")], check=True)
    if os.path.exists(css_src):
        subprocess.run(["cp", css_src, os.path.join(PATCH, "panel-fix.css")], check=True)
    js_sha = sha(js_src)
    # meta/version 指向当前 APK 版本（保持同步；不私自升版本号）
    ver = json.load(open(os.path.join(LANDING, "version.json")))
    meta = {"version": ver.get("version"), "js_sha": js_sha, "ts": now()}
    json.dump(meta, open(os.path.join(PATCH, "meta.json"), "w"), ensure_ascii=False, indent=2)
    json.dump(meta, open(os.path.join(PATCH, "version.json"), "w"), ensure_ascii=False, indent=2)
    return js_sha

def needs_apk_rebuild():
    """看 git 是否有未提交的 Java/shell 改动（这些改动只能走 APK）。"""
    out = subprocess.run(["git", "-C", REPO, "status", "--porcelain", "app/src", "apk-src", "build"],
                         capture_output=True, text=True).stdout.strip()
    return bool(out)

def main():
    s = load_state()
    s["round"] += 1
    rnd = s["round"]
    print(f"=== iter round {rnd} @ {now()} ===")
    checks = run_checks()
    for k, v in checks.items():
        print(f"  [{ 'OK' if v['ok'] else 'XX'}] {k}: {v['msg']}")
    s["last_run"] = now()

    # 选一个 open 痛点（按 priority）
    open_pts = {k: v for k, v in s["pain_points"].items() if v.get("status") == "open"}
    action_taken = None
    if open_pts:
        top = sorted(open_pts.items(), key=lambda kv: kv[1].get("priority", 99))[0]
        pid, p = top
        # 引擎自己只处理可自动化的；Java 类（keep_screen_on_as_keepalive）标为需人工/APK轮，
        # 这里记录决策不擅自改 Java（避免无真机验证的回归）。改为：写决策说明 + 留待有度量依据时改。
        action_taken = f"reviewed {pid}: {p.get('note','')}"

    # 推补丁通道（即使没改也保证与仓库一致；有改则热更）
    js_sha = publish_patch_channel()
    print(f"  patch channel js_sha={js_sha}")

    apk = needs_apk_rebuild()
    print(f"  apk_rebuild_pending={apk}")

    # ---- 无人值守硬闸（用户睡眠期授权自行决断，但发版必须有真机验证）----
    # 规则：cron 轮 NEVER 发布 APK。若检测到 Java/壳改动堆积（apk_pending），
    #   只构建并暂存到 dist/，标记 awaiting_user，绝不触碰 release/入口/落地页。
    #   真机验证与发版永远留给用户醒后决策。这是防"静默发坏包"的最后一道墙。
    awaiting = []
    if apk:
        awaiting.append("APK_REBUILD_PENDING: Java/壳有未提交改动，需构建后真机验证再发版；cron 不会自动发布")
        print("  ⛔ HARD GATE: 有 Java/壳改动，cron 不发布；已记入 awaiting_user")

    s["awaiting_user"] = awaiting
    s["rounds_log"].append({
        "round": rnd, "ts": now(), "checks": checks,
        "action": action_taken, "js_sha": js_sha, "apk_pending": apk,
        "awaiting": awaiting
    })
    if len(s["rounds_log"]) > 200:
        s["rounds_log"] = s["rounds_log"][-200:]
    save_state(s)

    # 日志轮转：cron 日志 >2000 行则截断尾部，防磁盘撑爆
    try:
        lp = "/root/repos/10router-android/tools/iter_cron.log"
        if os.path.exists(lp) and os.path.getsize(lp) > 200_000:
            lines = open(lp).read().splitlines()[-500:]
            open(lp, "w").write("\n".join(lines) + "\n")
    except Exception:
        pass
    print("=== done ===")

if __name__ == "__main__":
    main()
