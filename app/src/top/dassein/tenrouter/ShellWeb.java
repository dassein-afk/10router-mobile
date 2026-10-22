package top.dassein.tenrouter;


import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.net.Socket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ShellWeb —— 把 APK 的壳（首页/网关/额度/设置 + 底部导航）原样搬到浏览器。
 *
 * 设计：
 *   · 只吐 assets/ui 的壳页面（与 APK 同一份 HTML，不做第二份）；
 *   · /api/state 返回与 uiStatusJson() 同形状的 JSON；
 *   · /api/act 复用 uiAct()；
 *   · 网关/额度两个面板由 web-shim.js 用 iframe **直连**上游端口（:gwPort/dashboard、:cdPort/），
 *     不再同源反代。反代要缓冲整个 SSR body + 每请求 Connection:close，是卡顿/黑化的根源。
 *
 * 为什么 iframe 直连就够：面板是现成的 web 服务，跨端口 iframe 仍是 same-site
 * （同 127.0.0.1，Cookie 不区分端口），能正常渲染并带会话；手机适配补丁只在 App 的
 * WebView 里注入（小屏才需要），浏览器大屏下上游本就正常。
 *
 * 复用 TunnelGate 的纯 Java ServerSocket + 流式透传（它已被验证：Android 服务器都能跑）。
 * 端口 20130。监听地址随 bindAll（局域网开关）走 0.0.0.0/127.0.0.1。
 *
 * 安全边界：
 *   · **来自本机（loopback）的请求：免密钥**。这是手机本机跑的本地服务，在手机上打开
 *     就是本机，再要求输入密钥没有意义。
 *   · **来自局域网其它设备的请求：必须带密钥**（入口 URL 上的 ?k= 会被写成 Cookie，
 *     之后子资源自动携带）。开局域网等于把界面交给同 Wi-Fi 的人，这里需要凭据。
 *   · 判定用 socket 的对端地址 `isLoopbackAddress()`，不看 Host 头（Host 可伪造）。
 *
 * 历史坑（都真机发生过，别再退回去）：
 *   1.11.18/19：本机也要密钥，且 `start()` 每次重绑都 `randToken()` —— 复制出去的链接
 *   在开关局域网/重启后立刻失效，页面只会回一句"需要密钥"（那句提示本身是死循环：
 *   让用户去 App 看密钥，而用户正是从 App 复制来的）。修法：token 进程内只生成一次。
 *   1.11.20：一刀切全免密钥 —— 局域网里谁都能改守护设置，越权。本次按来源分开。
 *
 * 另：`/api/state` 对未鉴权来源**不回传密钥**（否则局域网任意设备一次 GET 就能拿到密钥
 * 反手绕过鉴权）。
 */
public final class ShellWeb {

    public static final int PORT = 20130;
    // ❗不反代面板（2026-10-22 用户决定）。反代这条路走过两次都失败：
    //   ① 前缀方案：Next.js 客户端用绝对路径请求 /_next/、/api/ → 全部 404，登录页整页废；
    //   ② 根路径方案：要额外占端口、还要改写 Location，复杂度不值。
    //   现在面板就用它们自己的端口：网关 upstreamGw、额度 upstreamCd，壳只做首页+设置。

    private final MainActivity act;
    private final int upstreamGw;
    private final int upstreamCd;
    private volatile boolean running;
    private volatile boolean lan;
    private final ConcurrentHashMap<Integer, ServerSocket> servers = new ConcurrentHashMap<>();
    /** 进程内只生成一次：重绑（开关局域网）不换，否则用户复制出去的链接当场失效。 */
    private volatile String token;

    ShellWeb(MainActivity act, int upstreamGw, int upstreamCd) {
        this.act = act;
        this.upstreamGw = upstreamGw;
        this.upstreamCd = upstreamCd;
    }

    /** 随绑定状态启动（127.0.0.1 = 仅本机，0.0.0.0 = 局域网开关打开）。 */
    synchronized void start(boolean bindAll) {
        if (running) stop();
        this.lan = bindAll;
        if (token == null) token = randToken();
        try {
            InetAddress bind = InetAddress.getByName(bindAll ? "0.0.0.0" : "127.0.0.1");
            ServerSocket s = new ServerSocket(PORT, 128, bind);   // backlog 128：手机并发几十个请求
            servers.put(PORT, s);
            Thread t = new Thread(() -> acceptLoop(s, PORT), "shellweb-" + PORT);
            t.setDaemon(true);
            t.start();
            running = true;
        } catch (IOException e) {
            running = false;
            for (ServerSocket s : servers.values()) {
                try { s.close(); } catch (Exception ignored) {}
            }
            servers.clear();
        }
    }

    synchronized void stop() {
        running = false;
        for (ServerSocket s : servers.values()) {
            try { s.close(); } catch (Exception ignored) {}
        }
        servers.clear();
    }

    boolean isRunning() { return running; }

    String getToken() { return token == null ? "" : token; }

    /** 重新生成密钥（用户点了「重新生成密钥」）：局域网分发出去的旧地址当场失效。 */
    synchronized void rotateToken() { if (running) token = randToken(); }

    /** 本机 = loopback，或对端地址正是本机某块网卡的地址（手机用自己局域网 IP 打开自己，也算本机）。 */
    private static boolean isSelf(InetAddress peer) {
        if (peer.isLoopbackAddress()) return true;
        try {
            for (Enumeration<NetworkInterface> ni = NetworkInterface.getNetworkInterfaces(); ni.hasMoreElements(); ) {
                for (Enumeration<InetAddress> a = ni.nextElement().getInetAddresses(); a.hasMoreElements(); ) {
                    if (a.nextElement().equals(peer)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private String randToken() {
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING)
                .replace('-', 'A').replace('_', 'B');   // 只留字母数字：粘进地址栏不会被转义
    }

    private void acceptLoop(ServerSocket srv, int port) {
        while (running) {
            try {
                Socket client = srv.accept();
                final Socket c = client;
                Thread t = new Thread(() -> handle(c, port), "shellweb-conn-" + port);
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (running) { /* transient */ }
            }
        }
    }

    // 请求体已读完、需要继续透传时使用；普通 GET 不需要
    private void handle(Socket client, int port) {
        try {
            client.setSoTimeout(30000);
            InputStream cin = client.getInputStream();
            OutputStream cout = client.getOutputStream();

            StringBuilder head = new StringBuilder();
            int b;
            while ((b = cin.read()) != -1) {
                head.append((char) b);
                int n = head.length();
                if (n >= 4 && head.charAt(n - 4) == '\r' && head.charAt(n - 3) == '\n'
                        && head.charAt(n - 2) == '\r' && head.charAt(n - 1) == '\n') break;
                if (n >= 2 && head.charAt(n - 2) == '\n' && head.charAt(n - 1) == '\n') break;
                if (n > 64 * 1024) break;
            }
            String raw = head.toString();
            if (raw.isEmpty()) { client.close(); return; }

            String[] lines = raw.split("\r?\n");
            String[] parts = (lines.length > 0 ? lines[0] : "").split(" ");
            if (parts.length < 2) { client.close(); return; }
            String method = parts[0];
            String path = parts[1];
            int q = path.indexOf('?');
            String query = q >= 0 ? path.substring(q + 1) : "";
            String clean = q >= 0 ? path.substring(0, q) : path;
            // 归一化：去掉可能的绝对 host 前缀
            if (clean.startsWith("http://") || clean.startsWith("https://")) {
                try { java.net.URI u = new java.net.URI(clean); clean = u.getPath(); } catch (Exception ignored) {}
            }

            // 鉴权：本机免密钥，局域网要密钥。判定用 socket 对端地址（Host 头可伪造，不看）。
            boolean local = client.getInetAddress() != null && isSelf(client.getInetAddress());
            boolean authed = local || authedByKey(query, lines);

            if (clean.equals("/api/state") && method.equals("GET")) {
                sendJson(cout, 200, act.uiStatusJson(authed));
                finish(client, cout);
                return;
            }
            if (clean.equals("/api/act") && method.equals("POST")) {
                if (!authed) { sendNeedKey(cout); finish(client, cout); return; }
                int cl = contentLength(lines);
                byte[] body = readBody(cin, cl);
                String name = "";
                try {
                    org.json.JSONObject o = new org.json.JSONObject(new String(body, StandardCharsets.UTF_8));
                    name = o.optString("name", "");
                } catch (Exception ignored) {}
                act.uiAct(name);                  // 复用与按钮完全相同的分发
                sendJson(cout, 200, "{\"ok\":true}");
                finish(client, cout);
                return;
            }
            // 其余：壳静态资源
            if (!authed) { sendNeedKey(cout); finish(client, cout); return; }
            serveAsset(client, cout, clean);
        } catch (Exception e) {
            android.util.Log.w("ShellWeb", "handle", e);
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }


    private static int contentLength(String[] lines) {
        for (int i = 1; i < lines.length; i++) {
            String ln = lines[i];
            if (ln.toLowerCase().startsWith("content-length:")) {
                try { return Integer.parseInt(ln.substring(ln.indexOf(':') + 1).trim()); } catch (Exception ignored) {}
            }
        }
        return -1;
    }

    private static byte[] readBody(InputStream cin, int cl) throws IOException {
        if (cl <= 0) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int remain = cl;
        while (remain > 0) {
            int n = cin.read(buf, 0, Math.min(buf.length, remain));
            if (n <= 0) break;
            out.write(buf, 0, n);
            remain -= n;
        }
        return out.toByteArray();
    }

                private void serveAsset(Socket client, OutputStream cout, String clean) throws IOException {
        String name = clean;
        if (name.equals("/") || name.isEmpty() || name.equals("/set")) name = "/index.html";
        name = name.substring(name.lastIndexOf('/') + 1);   // 去前导斜杠、取末段、防目录穿越
        if (name.isEmpty() || name.contains("..") || !name.matches("[A-Za-z0-9._-]+")) { send404(cout); return; }
        String asset = "ui/" + name;
        InputStream in = null;
        try { in = act.getAssets().open(asset); } catch (Exception e) { send404(cout); return; }
        byte[] data = readAll(in);
        String ctype;
        if (name.endsWith(".html")) ctype = "text/html; charset=utf-8";
        else if (name.endsWith(".js")) ctype = "application/javascript; charset=utf-8";
        else if (name.endsWith(".css")) ctype = "text/css; charset=utf-8";
        else ctype = "application/octet-stream";
        // 壳页面注入：在 <script src="app.js"> 前插入 web-shim（把 Native 桥换成 HTTP）
        if (name.equals("index.html")) {
            String html = new String(data, StandardCharsets.UTF_8);
            // 面板端口直接交给前端：网关 = 本机 10Router 端口，额度 = CD 端口。
            // 不反代 —— 让面板用自己的端口（Next.js 的 /_next/、/api/ 才天然正确）。
            String shim = "<script>window.__WEB_BASE='';"
                    + "window.__WEB_PORTS={gw:" + upstreamGw + ",cd:" + upstreamCd + "};</script>\n"
                    + "<script src=\"/web-shim.js\"></script>\n";
            html = html.replace("<script src=\"app.js\">", shim + "<script src=\"app.js\">");
            data = html.getBytes(StandardCharsets.UTF_8);
            ctype = "text/html; charset=utf-8";
        }
        sendBytes(cout, 200, ctype, data);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private void sendBytes(OutputStream out, int code, String ctype, byte[] data) throws IOException {
        String head = "HTTP/1.1 " + code + " OK\r\nContent-Type: " + ctype
                + "\r\n" + cookieLine() + "Content-Length: " + data.length
                + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    /** 把密钥写成 Cookie：入口 URL 带一次 ?k=，之后子资源/面板自动携带。 */
    private String cookieLine() {
        String t = token;
        return t == null || t.isEmpty() ? "" : "Set-Cookie: k=" + t + "; Path=/; HttpOnly; SameSite=Lax\r\n";
    }

    /** URL 上的 ?k= 命中，或之前下发的 Cookie 命中。 */
    private boolean authedByKey(String query, String[] lines) {
        String t = token;
        if (t == null || t.isEmpty()) return false;
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals("k") && kv.substring(eq + 1).equals(t)) return true;
        }
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].toLowerCase().startsWith("cookie:")) {
                for (String part : lines[i].substring(7).split(";")) {
                    if (part.trim().equals("k=" + t)) return true;
                }
            }
        }
        return false;
    }

    /** 局域网来源缺密钥：说清"去哪儿拿"，不再回那句让人原地打转的"地址末尾加 ?k="。 */
    private void sendNeedKey(OutputStream out) throws IOException {
        String body = "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<div style='font:14px/1.7 system-ui;padding:28px 20px;max-width:34em;margin:0 auto'>"
                + "<h2 style='font-size:17px;margin:0 0 10px'>这个地址需要密钥</h2>"
                + "<p style='margin:0 0 10px'>网页版从<b>手机本机</b>打开时不需要密钥；"
                + "从其它设备（同 Wi-Fi 的电脑/平板）打开时需要。</p>"
                + "<p style='margin:0 0 10px'>请在<b>手机 App → 设置 → 网页版</b>里，点"
                + "「同 Wi-Fi 设备」那一行的<b>复制地址</b>，把完整地址粘到这台设备的浏览器打开。</p>"
                + "<p style='margin:0;color:#8c8c8c;font-size:13px'>密钥随机生成、只存在手机内存；"
                + "地址失效就回手机 App 重新复制一次。</p></div>";
        byte[] d = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 403 Forbidden\r\nContent-Type: text/html; charset=utf-8"
                + "\r\nContent-Length: " + d.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(d);
        out.flush();
    }

    private void sendJson(OutputStream out, int code, String json) throws IOException {
        byte[] d = json.getBytes(StandardCharsets.UTF_8);
        sendBytes(out, code, "application/json; charset=utf-8", d);
    }


    private void send404(OutputStream out) throws IOException {
        String body = "<h1>404</h1>";
        byte[] d = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 404 Not Found\r\nContent-Type: text/html; charset=utf-8"
                + "\r\nContent-Length: " + d.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(d);
        out.flush();
    }

    private static void finish(Socket s, OutputStream out) {
        try { out.flush(); } catch (Exception ignored) {}
    }
}
