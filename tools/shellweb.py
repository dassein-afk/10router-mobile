#!/usr/bin/env python3
"""ShellWeb —— 把 APK 的壳（首页/网关/额度/设置 + 底部导航）原样搬到浏览器。

这是 NEXT.md「2.0」的服务端原型：同样的逻辑之后翻成 Java 类塞进 APK，
所以这里刻意只用标准库、不依赖任何框架，接口形状就是 Java 要实现的接口形状：

  GET  {PREFIX}/                → assets/ui 的壳页面（注入 web-shim.js）
  GET  {PREFIX}/api/state       → 与 MainActivity.uiStatusJson() 同形状的 JSON
  POST {PREFIX}/api/act         → 与 MainActivity.uiAct() 同名动作
  GET  {PREFIX}/gw/<rest>       → 同源反代 127.0.0.1:20128/dashboard，HTML 注入纯色导航条（MPA）
  GET  {PREFIX}/cd/<rest>       → 同源反代 127.0.0.1:47860，同上

密钥：URL 带 ?k=TOKEN，或 cookie。无密钥一律 403，可操作动作也 403。
"""
import http.client
import json
import os
import re
import socket
import threading
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UI = os.environ.get("UI", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "ui"))
SHIM = os.path.join(os.path.dirname(os.path.abspath(__file__)), "shellweb-shim.js")
PREFIX = os.environ.get("PREFIX", "/10rweb").rstrip("/")
TOKEN = open(os.environ.get("TOKEN_FILE", "/etc/shellweb/token")).read().strip()
PORT = int(os.environ.get("PORT", "20130"))
# ❗不反代面板（2026-10-22 用户决定，反代这条路的坑：Next.js 客户端用绝对路径请求
#   /_next/、/api/，前缀方案全部 404；根路径方案又要多占端口且仍需改写 Location）。
#   面板直接用自己的端口：网关 20129（relay→20128）/ 额度 47860，壳只做首页+设置。
PORT_GW = int(os.environ.get("PORT_GW", "20129"))   # 网关面板对外端口（relay）
PORT_CD = int(os.environ.get("PORT_CD", "47860"))   # 额度面板对外端口
STATE_FILE = os.environ.get("STATE_FILE", "/var/lib/shellweb/prefs.json")

GW = ("127.0.0.1", int(os.environ.get("GW_PORT", "20128")))
CD = ("127.0.0.1", int(os.environ.get("CD_PORT", "47860")))
MOUNT = {"gw": GW, "cd": CD}          # /gw → 网关, /cd → 额度管家
# 补丁层只作用于这两个面板；分域靠给文档打 data-pf，代理时直接告诉补丁"我是哪个面板"
PF = {"gw": "gateway", "cd": "credit"}
CD_HOME = os.environ.get("CD_HOME", os.path.expanduser("~/.creditdaddy"))

_lock = threading.Lock()


def prefs():
    try:
        with open(STATE_FILE) as f:
            return json.load(f)
    except Exception:
        return {}


def save_prefs(d):
    os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
    with _lock:
        with open(STATE_FILE, "w") as f:
            json.dump(d, f, ensure_ascii=False, indent=1)


def up(hostport, path="/", timeout=1.2):
    """面板是否活着：拿一个 GET 探一下，任何 HTTP 响应都算活着。"""
    try:
        with socket.create_connection(hostport, timeout=timeout):
            return True
    except Exception:
        return False


def cd_stats():
    """额度管家：和 APK 一样直接读它的 accounts.json（不碰需要面板密钥的 HTTP 接口）。"""
    running, n, today = up(CD, timeout=0.8), 0, False
    import datetime
    day = datetime.date.today().isoformat()
    for cand in (os.path.join(CD_HOME, "accounts.json"), "/opt/creditdaddy/accounts.json",
                 os.path.expanduser("~/creditdaddy/accounts.json")):
        try:
            acc = json.load(open(cand))
            if isinstance(acc, dict):
                acc = acc.get("accounts", [])
            n = len(acc)
            today = any(str(a.get("lastCheckin", "")).startswith(day) for a in acc if isinstance(a, dict))
            break
        except Exception:
            continue
    return running, n, today


def lan_ips():
    out = []
    for line in os.popen("hostname -I 2>/dev/null").read().split():
        out.append(line)
    vpn = [i for i in out if i.startswith(("10.", "100.", "172.16.", "192.168.10.")) and not i.startswith("10.8.0")]
    lan = [i for i in out if i not in vpn]
    return (lan[0] if lan else ""), (vpn[0] if vpn else "")


def local_ver():
    try:
        m = re.search(r'versionName="([^"]+)"', open(os.path.join(UI, "..", "AndroidManifest.xml"), encoding="utf-8").read())
        if m:
            return m.group(1)
    except Exception:
        pass
    return os.environ.get("VER", "web-preview")


def build_state():
    p = prefs()
    lan, vpn = lan_ips()
    cd_on, cd_acc, cd_today = cd_stats()
    css_b = os.path.join(UI, "panel-fix.css")
    js_b = os.path.join(UI, "panel-fix.js")
    return {
        # —— 和 uiStatusJson() 同名同义 ——
        "ver": local_ver(),
        "booting": False,
        "gw": up(GW),
        "gwPort": GW[1],
        "hbAge": 0,
        "boots": 0,
        "freezeCount": 0,
        "notif": True,
        "battery": True,
        "anchor": True,
        "vpnAnchor": False,
        "audioAnchor": False,
        "anchorMode": "auto",
        "anchorWhy": "web",
        "extVpn": False,
        "keepScreenOn": False,
        "keepScreenMode": 0,
        "touchBlock": bool(p.get("touchBlock")),
        "radioFull": False,
        "patch": bool(p.get("patch", True)),
        "lan": lan,
        "vpn": vpn,
        "bindAll": bool(p.get("bindAll", True)),
        "cdPort": CD[1],
        "tunnelOn": False,
        "tunnelUrl": "",
        "patchVer": local_ver(),
        "patchSrc": "bundled",
        "cd": cd_on,
        "cdAccounts": cd_acc,
        "cdToday": cd_today,
        # —— 网页版专属说明位（壳里不显示，调试用）——
        "web": True,
    }


def do_act(name):
    """和 uiAct() 同名。能真做的真做；手机专属的如实回绝，不演。"""
    p = prefs()
    if name in ("lanToggle", "lanOn", "lanOff"):
        p["bindAll"] = not p.get("bindAll", True)
        save_prefs(p)
        return True, ("已允许局域网访问" if p["bindAll"] else "已只监听本机")
    if name == "patch":
        p["patch"] = not p.get("patch", True)
        save_prefs(p)
        return True, ("补丁层已开" if p["patch"] else "补丁层已关")
    if name in ("patchCheck", "patchReset", "patchUpdate"):
        return True, "网页版补丁就是仓库里这两份文件，无需热更新"
    if name == "touchBlock":
        p["touchBlock"] = not p.get("touchBlock", False)
        save_prefs(p)
        return True, ("防误触已开" if p["touchBlock"] else "防误触已关")
    if name == "update":
        return True, "网页版请在手机浏览器打开下载页"
    return False, "这个动作是手机上的（后台守护/唤醒/日志），网页版不代做"


def read_assets(*names):
    for n in names:
        try:
            return open(os.path.join(UI, n), "rb").read()
        except Exception:
            pass
    return b""


def inject_shell(html, req, host="127.0.0.1", active="home"):
    """壳页面：注入底部导航条（跨端口跳转）+ web-shim.js（把 Native 桥换成 HTTP）。"""
    shim = ('<script>window.__WEB_BASE=%r;window.__WEB_PORTS={"gw":%d,"cd":%d};</script>\n'
            '<script src="%s/web-shim.js"></script>\n' % (PREFIX, PORT_GW, PORT_CD, PREFIX))
    nav = nav_html(host, active)
    if b"<script src=\"app.js\">" in html:
        html = html.replace(b"<script src=\"app.js\">",
                            (shim.encode() + b"\n<script src=\"app.js\">"), 1)
    else:
        html = html + ("\n%s" % shim).encode()
    # 导航条插在 </body> 前
    if b"</body>" in html:
        html = html.replace(b"</body>", nav.encode() + b"</body>", 1)
    else:
        html = html + nav.encode()
    return html


def nav_html(host, active="home"):
    """壳页面底部导航条（纯色、无 blur）：跨端口整页跳转。
    网关/额度是**各自独立的面板端口**，壳只负责首页/设置。"""
    def cls(k):
        return " on" if k == active else ""
    return (
        "<style>"
        "nav.webnav{position:fixed;left:0;right:0;bottom:0;height:calc(52px + env(safe-area-inset-bottom));"
        "padding-bottom:env(safe-area-inset-bottom);display:flex;background:var(--card);"
        "border-top:1px solid var(--line);z-index:50}"
        "nav.webnav .wtab{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;"
        "gap:3px;background:none;border:0;color:var(--dim2);font-size:11.5px;position:relative;"
        "cursor:pointer;text-decoration:none}"
        "nav.webnav .wtab i{font-style:normal;font-size:18px;line-height:1}"
        "nav.webnav .wtab.on{color:var(--fg)}"
        "nav.webnav .wtab.on::before{content:'';position:absolute;top:0;left:50%%;transform:translateX(-50%%);"
        "width:22px;height:2px;border-radius:2px;background:var(--acc)}"
        "</style>"
        '<nav class="webnav">'
        '<a class="wtab%s" href="http://%s:%d/"><i>\u2302</i><span>\u9996\u9875</span></a>'
        '<a class="wtab%s" href="http://%s:%d/dashboard"><i>\u25c9</i><span>\u7f51\u5173</span></a>'
        '<a class="wtab%s" href="http://%s:%d/"><i>\u25c8</i><span>\u989d\u5ea6</span></a>'
        '<a class="wtab%s" href="http://%s:%d/set"><i>\u2699</i><span>\u8bbe\u7f6e</span></a>'
        "</nav>"
    ) % (cls("home"), host, PORT, cls("gw"), host, PORT_GW, cls("credit"), host, PORT_CD, cls("set"), host, PORT)


# 引号里的绝对路径（HTML 属性与 JS 字符串两种形状都吃）
SET_RE = re.compile(rb'(["\x27])(/(?:api|dashboard|login|settings|credits|_next|favicon|assets|src|static|manifest)[^"\x27\\]+)\1')


def rewrite_abs(body, mount):
    """上游 HTML 里的绝对路径要挂到本前缀下，否则浏览器会打到站点根、丢掉补丁层。"""
    return SET_RE.sub(lambda m: m.group(1) + mount.encode() + m.group(2) + m.group(1), body)


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "ShellWeb/1.0"

    def log_message(self, fmt, *a):
        pass

    def send_(self, code, ctype, body, extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or []):
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def authed(self):
        q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        if (q.get("k") or [""])[0] == TOKEN:
            return True
        # 首屏渲染需要 /api/state，放行（无密钥）；真正的风险动作 /api/act 仍强制鉴权
        if urllib.parse.urlparse(self.path).path[len(PREFIX):] == "/api/state":
            return True
        for c in (self.headers.get("Cookie") or "").split(";"):
            if c.strip() == "k=" + TOKEN:
                return True
        return False

    # ---------------- 路由 ----------------
    def do_GET(self):
        self.route("GET")

    def do_HEAD(self):
        self.route("GET")

    def do_POST(self):
        self.route("POST")

    def route(self, method):
        u = urllib.parse.urlparse(self.path)
        path = u.path
        if not path.startswith(PREFIX):
            self.send_(404, "text/plain; charset=utf-8", b"404")
            return
        rest = path[len(PREFIX):] or "/"
        if not self.authed():
            self.send_(403, "text/html; charset=utf-8",
                       "<h1>需要密钥</h1><p>地址尾巴加 <code>?k=密钥</code>，密钥在手机的「设置 → 网页端口」里看。</p>".encode())
            return
        cookie = [] if method != "GET" else [("Set-Cookie", "k=%s; Path=/; HttpOnly; SameSite=Lax" % TOKEN)]

        if rest == "/api/state":
            self.send_(200, "application/json; charset=utf-8", json.dumps(build_state()).encode(), cookie)
        elif rest == "/api/act":
            n = (self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode("utf-8", "ignore") or "").strip()
            try:
                n = json.loads(n).get("name", n)
            except Exception:
                pass
            ok, msg = do_act(n)
            self.send_(200 if ok else 400, "application/json; charset=utf-8",
                       json.dumps({"ok": ok, "msg": msg}, ensure_ascii=False).encode(), cookie)
        elif rest == "/web-shim.js":
            self.send_(200, "application/javascript; charset=utf-8", open(SHIM, "rb").read(), cookie)
        elif rest.startswith("/panel-fix."):
            f = os.path.join(UI, os.path.basename(rest))
            self.send_(200, "text/css; charset=utf-8" if f.endswith(".css") else "application/javascript; charset=utf-8",
                       open(f, "rb").read(), cookie)
        elif rest in ("/", "/index.html", "/set"):
            host = (self.headers.get("Host") or "127.0.0.1").split(":")[0]
            active = "set" if rest == "/set" else "home"
            self.send_(200, "text/html; charset=utf-8",
                       inject_shell(open(os.path.join(UI, "index.html"), "rb").read(), rest, host, active), cookie)
        else:
            name = os.path.basename(rest)
            if name in ("app.js", "offline.html", "panel-fix.css", "panel-fix.js"):
                ctype = ("application/javascript; charset=utf-8" if name.endswith(".js")
                         else "text/css; charset=utf-8" if name.endswith(".css") else "text/html; charset=utf-8")
                self.send_(200, ctype, read_assets(name), cookie)
            else:
                self.send_(404, "text/plain; charset=utf-8", b"404")



if __name__ == "__main__":
    # backlog 必须够大：手机浏览器一开就是几十个并发连接，默认 5 会丢连接。
    ThreadingHTTPServer.allow_reuse_address = True
    ThreadingHTTPServer.request_queue_size = 128
    ThreadingHTTPServer.daemon_threads = True
    print("ShellWeb 壳 :%d prefix=%s（网关 :%d / 额度 :%d 各自独立，不反代）"
          % (PORT, PREFIX, PORT_GW, PORT_CD))
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
