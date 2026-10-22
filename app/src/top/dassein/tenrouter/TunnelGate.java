package top.dassein.tenrouter;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.Consumer;

/**
 * 隧道闸门：本机反向代理，**只放行 /v1 接口**，其余一律 404。
 *
 * 为什么需要它（实测教训）：
 *   cloudflared 的 `--url http://127.0.0.1:20128/v1` **不做路径过滤** —— 我们把
 *   `--url .../v1` 当成"只暴露 /v1"是错的：实测 `GET /dashboard` 会原样透传到源站。
 *   若直接把隧道指向网关，等于把**管理面板**（可查看/修改 API 密钥）暴露到公网。
 *
 * 所以公网隧道指向本闸门（127.0.0.1:20129），由闸门做白名单：
 *   · 只允许 /v1/* 与 /v1
 *   · 其余路径返回 404（连网关都碰不到）
 *   · 流式响应（SSE）原样透传，不做缓冲，不影响打字机效果
 *
 * 本类是**纯 Java**（只用 java.net/java.io），因此可以在服务器上单独编译并实测，
 * 不必依赖安卓环境 —— 这是它能被验证的前提。
 */
public final class TunnelGate {

    private final int listenPort;
    private final String upstreamHost;
    private final int upstreamPort;
    private final Consumer<String> log;

    private volatile boolean running;
    private ServerSocket server;
    private Thread acceptor;

    public TunnelGate(int listenPort, String upstreamHost, int upstreamPort, Consumer<String> log) {
        this.listenPort = listenPort;
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.log = log != null ? log : (s -> {});
    }

    public synchronized void start() throws Exception {
        if (running) return;
        server = new ServerSocket(listenPort, 64, InetAddress.getByName("127.0.0.1"));
        running = true;
        acceptor = new Thread(this::acceptLoop, "tunnel-gate");
        acceptor.setDaemon(true);
        acceptor.start();
        log.accept("[gate] 闸门已启动 127.0.0.1:" + listenPort + " → " + upstreamHost + ":" + upstreamPort + "（只放行 /v1）");
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
        acceptor = null;
        log.accept("[gate] 闸门已停止");
    }

    public boolean isRunning() { return running; }

    private void acceptLoop() {
        while (running) {
            Socket client = null;
            try {
                client = server.accept();
                final Socket c = client;
                Thread t = new Thread(() -> handle(c), "gate-conn");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (running) log.accept("[gate] accept 异常: " + e);
            }
        }
    }

    /** 只允许 /v1 与 /v1/* 这两类路径 */
    static boolean allowed(String path) {
        if (path == null) return false;
        String p = path;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        return p.equals("/v1") || p.startsWith("/v1/");
    }

    private void handle(Socket client) {
        Socket up = null;
        try {
            client.setSoTimeout(30000);
            InputStream cin = client.getInputStream();
            OutputStream cout = client.getOutputStream();

            // 读请求头（直到空行）
            StringBuilder head = new StringBuilder();
            int b;
            while ((b = cin.read()) != -1) {
                head.append((char) b);
                int n = head.length();
                // ❗必须同时认 \r\n\r\n 与 \n\n：只判 \n\n 会永远读不到头（实测导致请求挂死、curl 报 000）
                if (n >= 4 && head.charAt(n - 4) == '\r' && head.charAt(n - 3) == '\n'
                        && head.charAt(n - 2) == '\r' && head.charAt(n - 1) == '\n') break;
                if (n >= 2 && head.charAt(n - 2) == '\n' && head.charAt(n - 1) == '\n') break;
                if (n > 64 * 1024) break;               // 防畸形请求
            }
            String raw = head.toString();
            if (raw.isEmpty()) { client.close(); return; }

            String[] lines = raw.split("\r?\n");
            String requestLine = lines.length > 0 ? lines[0] : "";
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { client.close(); return; }
            String method = parts[0];
            String path = parts[1];

            if (!allowed(path)) {
                byte[] body = ("{\"error\":\"not found\"}").getBytes("UTF-8");
                cout.write(("HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\n"
                        + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                cout.write(body);
                cout.flush();
                client.close();
                log.accept("[gate] 拦截 " + method + " " + path);
                return;
            }

            // 转发：重写请求行去掉绝对地址，Host 指向源站
            up = new Socket(upstreamHost, upstreamPort);
            up.setSoTimeout(0);                       // 流式响应可能长时间不结束
            OutputStream uout = up.getOutputStream();
            StringBuilder rebuilt = new StringBuilder();
            rebuilt.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            int contentLength = -1;
            boolean chunked = false;
            for (int i = 1; i < lines.length; i++) {
                String ln = lines[i];
                if (ln.isEmpty()) continue;
                String lower = ln.toLowerCase();
                if (lower.startsWith("host:")) continue;
                if (lower.startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(ln.substring(ln.indexOf(':') + 1).trim()); } catch (Exception ignored) {}
                }
                if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) chunked = true;
                rebuilt.append(ln).append("\r\n");
            }
            rebuilt.append("Host: ").append(upstreamHost).append(':').append(upstreamPort).append("\r\n\r\n");
            uout.write(rebuilt.toString().getBytes("UTF-8"));

            // 请求体透传
            if (contentLength > 0) {
                byte[] buf = new byte[8192];
                int remain = contentLength;
                while (remain > 0) {
                    int n = cin.read(buf, 0, Math.min(buf.length, remain));
                    if (n <= 0) break;
                    uout.write(buf, 0, n);
                    remain -= n;
                }
            } else if (chunked) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = cin.read(buf)) > 0) {
                    uout.write(buf, 0, n);
                    if (n < buf.length) break;        // 简化处理：分块体读到不满即认为结束
                }
            }
            uout.flush();

            // 响应原样回传（SSE / chunked 都能流式通过）
            InputStream uin = up.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = uin.read(buf)) > 0) {
                cout.write(buf, 0, n);
                cout.flush();
            }
        } catch (Exception e) {
            // 客户端提前断开属于常态，不刷日志
        } finally {
            try { if (up != null) up.close(); } catch (Exception ignored) {}
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    // ---------------- 独立自测入口（服务器上用 JDK 跑，验证闸门逻辑） ----------------
    public static void main(String[] args) throws Exception {
        int listen = args.length > 0 ? Integer.parseInt(args[0]) : 20129;
        String host = args.length > 1 ? args[1] : "127.0.0.1";
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 20128;
        TunnelGate gate = new TunnelGate(listen, host, port, s -> System.out.println(s));
        gate.start();
        System.out.println("GATE_READY");
        Thread.sleep(3600_000L);
    }
}
