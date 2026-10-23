package top.dassein.tenrouter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.util.Map;

/**
 * 监管内置 `node custom-server.js` 网关：启动、保活、自愈。
 *
 * 三层保活：
 *   1. 前台服务 + Partial WakeLock（阻止进程被系统冻结 / CPU 睡死）
 *   2. android:stopWithTask="false" + onTaskRemoved 继续存活（划掉最近任务也不停）
 *   3. KeepAlive 的 AlarmManager 5 分钟心跳 + JobScheduler 15 分钟守护（进程真被杀后的兜底）
 *
 * 自愈：进程还活着但不再应答（被冻住 / 事件循环卡死）时，看门狗连续 3 次健康探测失败
 * 就杀掉 node 重新拉起——修掉"通知说网关在跑，但调 API 不通"这种挂着不动的状态。
 *
 * 通知文案只在 /api/health 真正通过后才变成"运行中"，所以它从此表示"已验证可服务"。
 */
public class GatewayService extends Service {

    static final int PORT = 20128;
    /** 配额管家（CreditDaddy）端口：只绑回环，不对外 */
    static final int CD_PORT = 47860;
    static final String ACTION_RESTART = "top.dassein.tenrouter.RESTART";
    static final String ACTION_TUNNEL = "top.dassein.tenrouter.TUNNEL";
    static final String DASH_URL = "http://127.0.0.1:" + PORT + "/dashboard";
    private static final String CH_ID = "gw2";

    private volatile boolean wantRun = true;
    private Thread runner;
    private Thread watchdog;
    private Thread cdRunner;
    private Process proc;
    private Process cdProc;
    private PowerManager.WakeLock wake;
    private android.net.wifi.WifiManager.WifiLock wlock;
    /** 锁状态（诊断/状态 JSON 读这两个，别猜）：CPU 与无线电是否真的被我们顶住 */
    public static volatile boolean cpuLockHeld = false;
    public static volatile boolean wifiLockHeld = false;
    private android.content.BroadcastReceiver screenRx;
    private volatile boolean healthy = false;
    private volatile int healthFails = 0;
    private volatile int startCount = 0;
    private volatile long lastSchedAt = 0;
    private volatile boolean bootCounted = false;
    private volatile int boots = 0;

    @Override
    public IBinder onBind(Intent i) { return null; }

    /** 系统回收前的信号：鸿蒙/EMUI 通常先 trim 再杀，记下来能区分"被杀"与"被冻结" */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        logLine("[shell] onTrimMemory level=" + level + "（系统在收紧内存）");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "10router:gateway");
                wake.setReferenceCounted(false);
                wake.acquire();
                cpuLockHeld = wake.isHeld();
            }
        } catch (Exception ignored) {}
        // 无线电高性能锁：PARTIAL_WAKE_LOCK 只顶住 CPU，Wi-Fi 芯片息屏后照样进省电——
        // 局域网其他设备发到手机的包会被 AP 缓冲、RTT 飙到几百 ms 甚至丢包，表现就是
        // "进程明明活着，别的设备却连不上"。FULL_HIGH_PERF 让无线电保持全速（要 ACCESS_WIFI_STATE）。
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wlock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "10router:gw-radio");
                wlock.setReferenceCounted(false);
                wlock.acquire();
                wifiLockHeld = wlock.isHeld();
                logLine("[shell] 无线电高性能锁已持有（息屏后局域网不掉速）");
            }
        } catch (Exception e) {
            logLine("[shell] 无线电锁失败: " + e);
        }
        registerScreenForensics();
        // 服务起来了就把守卫进程也叫醒：此后两个进程互为存活证据。
        GuardLink.ensureGuard(this);
    }

    /** 息屏/亮屏取证：日志时间线能直接回答"到底是哪一层在锁屏后死了"——
     *  亮屏后紧跟着"检测到进程被冻结" = ROM 冻结层；亮屏后网关健康但息屏期间别的设备连不上 = 无线电省电层；
     *  闹钟时间线正常、进程一直在，却彻底没流量 = 厂商断网策略层（只能白名单，代码拦不住）。 */
    private void registerScreenForensics() {
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(android.content.Intent.ACTION_SCREEN_OFF);
            f.addAction(android.content.Intent.ACTION_SCREEN_ON);
            screenRx = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, android.content.Intent i) {
                    boolean off = android.content.Intent.ACTION_SCREEN_OFF.equals(i.getAction());
                    logLine("[screen] " + (off ? "息屏" : "亮屏")
                            + "（CPU锁=" + (wake != null && wake.isHeld() ? "有" : "无")
                            + "·无线电=" + (wifiLockHeld ? "全速" : "省电")
                            + "·锚=" + KeepAlive.anchorState(c).replace('|', ' ') + "）");
                    if (!off) {
                        logLine("[screen] 亮屏复检：网关" + (KeepAlive.isUp() ? "仍可服务" : "此刻不可服务"));
                        KeepAlive.evaluateAnchor(c, true);
                    }
                }
            };
            registerReceiver(screenRx, f);
        } catch (Exception e) {
            logLine("[shell] 息屏取证广播注册失败: " + e);
        }
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        // 用户点过「彻底退出」→ START_STICKY 重建也不许复活
        if (KeepAlive.userStopped(this)) {
            logLine("[shell] 用户已要求停止 → 服务拒绝启动");
            stopSelf();
            return START_NOT_STICKY;
        }
        wantRun = true;
        if (i != null && ACTION_TUNNEL.equals(i.getAction())) {
            logLine("[tunnel] 收到隧道开关变更 → 停止当前隧道，按新状态重来");
            stopTunnel();
        }
        if (i != null && ACTION_RESTART.equals(i.getAction())) {
            logLine("[shell] 收到重启指令 → 重启网关与额度管家");
            try { if (proc != null) proc.destroy(); } catch (Exception ignored) {}
            try { if (cdProc != null) cdProc.destroy(); } catch (Exception ignored) {}
            startNotif("正在重启…");
        }
        startCount++;
        // 每个进程只计一次（onStartCommand 会被反复调）
        if (!bootCounted) {
            bootCounted = true;
            boots = KeepAlive.bumpBootCount(this);
            logLine("[shell] ===== 进程启动第 " + boots + " 次 ====="
                    + (boots > 1 ? "（说明前面被系统杀过 " + (boots - 1) + " 次）" : ""));
        }
        // 被反复拉起时不要把日志刷爆（实测一次风暴 96 行），只记首尾和心跳
        if (startCount <= 3 || startId % 20 == 0) {
            logLine("[shell] svc onStartCommand flags=" + flags + " startId=" + startId + " count=" + startCount);
        }
        KeepAlive.beat(this);
        KeepAlive.healPatchesIfNeeded(this);
        if (boots >= 3) {
            // 被反复杀的实锤：直接引导用户去授权后台活动（代码拦不住 ROM，只能把人送到正确的开关前）
            startNotif("被系统反复挂起（第 " + boots + " 次启动）· 点此授权「后台活动」",
                    KeepAlive.backgroundRunPending(this));
        } else {
            startNotif("正在启动网关…");
        }
        if (runner == null || !runner.isAlive()) {
            runner = new Thread(this::loop, "gw-runner");
            runner.start();
        }
        if (watchdog == null || !watchdog.isAlive()) {
            watchdog = new Thread(this::watchLoop, "gw-watchdog");
            watchdog.start();
        }
        // 额度管家与网关同生共死：同一个保活体系护两个进程，而不是让用户装两个 App 各打一场保活战
        if (cdRunner == null || !cdRunner.isAlive()) {
            cdRunner = new Thread(this::cdLoop, "cd-runner");
            cdRunner.start();
        }
        // 公网隧道同样由这一个服务监管（开关打开才真正跑）
        if (tunnelRunner == null || !tunnelRunner.isAlive()) {
            tunnelRunner = new Thread(this::tunnelLoop, "tunnel-runner");
            tunnelRunner.start();
        }
        // 调度类操作节流：每次启动都重排 JobScheduler/闹钟会互相打断（风暴放大器）
        long now = System.currentTimeMillis();
        if (now - lastSchedAt > 5 * 60_000L) {
            lastSchedAt = now;
            KeepAlive.scheduleJob(this);
            KeepAlive.schedulePing(this);
        }
        KeepAlive.evaluateAnchor(this);   // 服务被拉起 = 好时机：让锚点协调器重估一次
        return START_STICKY;
    }

    /** 用户从最近任务划掉：不停服务，网关继续在后台跑 */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        logLine("[shell] task removed — 保持网关运行");
        if (runner == null || !runner.isAlive()) {
            runner = new Thread(this::loop, "gw-runner");
            runner.start();
        }
        KeepAlive.schedulePing(this);
        super.onTaskRemoved(rootIntent);
    }

    /** 监管 node 进程：退出就按退避重启 */
    private void loop() {
        int backoff = 2;
        while (wantRun) {
            long startedAt = System.currentTimeMillis();
            boolean alive = launchOnce();
            if (!wantRun) break;
            if (alive && System.currentTimeMillis() - startedAt > 60_000) backoff = 2; // long run: reset
            startNotif("网关已退出，" + backoff + " 秒后重试");
            try { Thread.sleep(backoff * 1000L); } catch (InterruptedException e) { break; }
            backoff = Math.min(backoff * 2, 60);
        }
    }

    /** 独立看门狗：进程还在但不服务（被冻住/卡死）就杀掉重启 */
    private void watchLoop() {
        while (wantRun) {
            long t0 = android.os.SystemClock.elapsedRealtime();
            try { Thread.sleep(30_000L); } catch (InterruptedException e) { break; }
            if (!wantRun) break;
            // 冻结检测：EMUI/国产 ROM 在后台会把整个 App 冻住（进程还在、线程全停）。
            // 解冻瞬间我们这一觉可能睡了几分钟——此时健康探测失败不是"node 卡死"，
            // 而是自己刚被冻过，绝不能因此杀掉健康的 node。
            long slept = android.os.SystemClock.elapsedRealtime() - t0;
            if (slept > 60_000L) {
                long secs = slept / 1000;
                logLine("[shell] 检测到进程被冻结约 " + secs + "s（后台被系统挂起）");
                KeepAlive.bumpFreeze(this, secs);
                healthFails = 0;
                KeepAlive.beat(this);
                // 关键修正：原实现冻结后直接 continue，把可能已经死掉的 node 当健康的放着。
                // 冻结期间网络与定时器全停，解冻后既有的 SSE 流基本都废了——这里必须复检，
                // 两次探测都不通就主动重启 node（而不是等用户下次发消息才发现 502）。
                boolean up = KeepAlive.isUp();
                if (!up) {
                    try { Thread.sleep(1500L); } catch (InterruptedException e) { break; }
                    up = KeepAlive.isUp();
                }
                if (!up) {
                    Process p = proc;
                    logLine("[shell] 解冻后网关仍不可服务 → 重启 node");
                    startNotif("被系统冻结过（累计 " + KeepAlive.freezeStats(this)[0]
                            + " 次）· 点通知去授权「后台活动」", KeepAlive.backgroundRunPending(this));
                    try { if (p != null) p.destroy(); } catch (Exception ignored) {}
                } else {
                    logLine("[shell] 解冻后网关仍可服务，无需重启");
                }
                continue;
            }
            KeepAlive.beat(this);
            if (KeepAlive.isUp()) {
                healthFails = 0;
                if (!healthy) {
                    healthy = true;
                    logLine("[shell] health ok");
                    startNotif("网关运行中 · 127.0.0.1:" + PORT);
                }
                continue;
            }
            // ⚠️ 这里曾经有个「连续 3 次无响应 → destroy node」的判据。但本循环每 30 秒才转一圈，
            //    3 次 = 90 秒；而 node 正常退出后 cd/gw runner 自己会在 1~2 秒内重启它，
            //    于是这 90 秒里有两次探测注定打在冷启窗口上 → 第三次判死 → 又把刚起来的 healthy node 杀掉。
            //    它和守卫进程的拉起也互相踩：守卫刚拉起来，这里又 destroy。日志里 startId 每次 +20
            //    （60/80/100）就是这个回环的脚印。
            //    现在改成「只记录、不主动杀」：进程不存在才重启，进程在但 HTTP 不通交给 node 自己
            //    和守卫进程处理——杀一个正在冷启的 node 永远不会让它更快好起来。
            healthy = false;
            healthFails++;
            logLine("[shell] health fail " + healthFails + "/3（不主动杀 node，等它自己起来）");
            if (healthFails >= 3) {
                healthFails = 0;
                Process p = proc;
                boolean procDead = true;
                if (p != null) {
                    try { p.exitValue(); } catch (IllegalThreadStateException stillRunning) { procDead = false; }
                }
                if (procDead) {
                    logLine("[shell] node 进程确实不在了 → 重启");
                    startNotif("网关无响应，正在重启…");
                }
            }
        }
    }

    // ---------------- 公网隧道（cloudflared，仅暴露 /v1） ----------------

    private Thread tunnelRunner;
    private Process tunnelProc;
    private TunnelGate gate;

    /** 监管出站隧道：开关打开就跑，关掉就停；输出写 tunnel.log，公网地址抓进 tunnel.url */
    private void tunnelLoop() {
        int backoff = 3;
        while (wantRun) {
            if (!KeepAlive.tunnelEnabled(this)) {
                stopTunnel();
                try { Thread.sleep(3000L); } catch (InterruptedException e) { break; }
                continue;
            }
            long startedAt = System.currentTimeMillis();
            launchTunnel();
            // 等它退出或开关被关掉
            while (wantRun && KeepAlive.tunnelEnabled(this) && tunnelProc != null) {
                try { Thread.sleep(2000L); } catch (InterruptedException e) { break; }
                if (tunnelProc == null) break;
                try { tunnelProc.exitValue(); tunnelProc = null; } catch (IllegalThreadStateException alive) { /* 还在跑 */ }
            }
            stopTunnel();
            if (!wantRun || !KeepAlive.tunnelEnabled(this)) { backoff = 3; continue; }
            if (System.currentTimeMillis() - startedAt > 120_000) backoff = 3;
            logLine("[tunnel] 隧道退出，" + backoff + " 秒后重试");
            try { Thread.sleep(backoff * 1000L); } catch (InterruptedException e) { break; }
            backoff = Math.min(backoff * 2, 60);
        }
    }

    private void launchTunnel() {
        try {
            File base = getFilesDir();
            File pfx = new File(base, "pfx/usr");
            File bin = new File(base, KeepAlive.CFL_ENTRY);
            if (!bin.exists()) {
                logLine("[tunnel] cloudflared 不存在，尝试解包");
                KeepAlive.ensureCflPayload(this);
            }
            if (!bin.exists()) {
                logLine("[tunnel] 载荷缺失，无法启动隧道: " + bin.getAbsolutePath());
                KeepAlive.cdLog(this, "[tunnel] 载荷缺失: " + bin.getAbsolutePath());
                return;
            }
            if (!bin.canExecute()) bin.setExecutable(true, false);

            File urlFile = new File(base, "tunnel.url");
            if (urlFile.exists()) urlFile.delete();
            File logFile = new File(base, "tunnel.log");

            // ❗必须经过闸门：实测 cloudflared 的 --url 路径**不做过滤**，
            // 直接指向网关会把管理面板（可看改 API 密钥）暴露到公网。
            // 闸门只放行 /v1，其余 404，且源站根本收不到请求。
            int gatePort = PORT + 1;
            if (gate == null || !gate.isRunning()) {
                gate = new TunnelGate(gatePort, "127.0.0.1", PORT, s -> logLine(s));
                gate.start();
            }
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(),
                    "tunnel", "--no-autoupdate", "--url",
                    "http://127.0.0.1:" + gatePort);
            pb.directory(new File(base, "cfl/bin"));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
            Map<String, String> env = pb.environment();
            env.put("PATH", pfx.getAbsolutePath() + "/bin:/system/bin:/bin");
            env.put("LD_LIBRARY_PATH", pfx.getAbsolutePath() + "/lib");
            env.put("HOME", new File(base, "home").getAbsolutePath());
            env.put("TMPDIR", getCacheDir().getAbsolutePath());
            File ca = new File(pfx, "etc/ssl/ca-bundle.pem");
            if (ca.exists()) {
                env.put("SSL_CERT_FILE", ca.getAbsolutePath());
                env.put("NODE_EXTRA_CA_CERTS", ca.getAbsolutePath());
            }
            logLine("[tunnel] 启动 cloudflared → 闸门 127.0.0.1:" + gatePort + "（只放行 /v1）");
            tunnelProc = pb.start();
            watchTunnelUrl(logFile);
        } catch (Exception e) {
            logLine("[tunnel] 启动失败: " + e);
            KeepAlive.cdLog(this, "[tunnel] 启动失败: " + e);
        }
    }

    /** 从 cloudflared 输出里抓公网地址（它自己会打印 https://xxx.trycloudflare.com） */
    private void watchTunnelUrl(final File logFile) {
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline && KeepAlive.tunnelEnabled(this)) {
                try {
                    String txt = KeepAlive.tailLogFile(logFile, 200);
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("https://[a-z0-9-]+\\.trycloudflare\\.com").matcher(txt);
                    if (m.find()) {
                        String url = m.group();
                        java.io.FileWriter w = new java.io.FileWriter(new File(getFilesDir(), "tunnel.url"), false);
                        w.write(url);
                        w.close();
                        logLine("[tunnel] 公网地址就绪: " + url);
                        return;
                    }
                } catch (Exception ignored) {}
                try { Thread.sleep(1500L); } catch (InterruptedException e) { return; }
            }
        }).start();
    }

    private void stopTunnel() {
        Process p = tunnelProc;
        tunnelProc = null;
        if (p != null) { try { p.destroy(); } catch (Exception ignored) {} }
        try {
            if (gate != null) { gate.stop(); gate = null; }
        } catch (Exception ignored) {}
        try { new File(getFilesDir(), "tunnel.url").delete(); } catch (Exception ignored) {}
    }

    /** 监管额度管家（CreditDaddy）进程；它挂了不影响网关，各自独立退避 */
    private void cdLoop() {
        int backoff = 2;
        while (wantRun) {
            long startedAt = System.currentTimeMillis();
            // ❗launchCd() 返回「spawn 成功」，不等于「进程健康」：node 可能因为
            //   数据目录被别的实例锁住而在 1 秒内退出。这两种情况必须区别对待。
            boolean spawned = launchCd();
            if (!wantRun) break;
            if (!spawned) {
                try { Thread.sleep(backoff * 1000L); } catch (InterruptedException e) { break; }
                backoff = Math.min(backoff * 2, 60);
                continue;
            }
            // 等它自然退出；活着就每 500ms 确认一次
            long aliveSince = System.currentTimeMillis();
            while (wantRun) {
                Process p = cdProc;
                if (p == null) break;
                try {
                    p.exitValue();
                    break;                        // 退出了
                } catch (IllegalThreadStateException stillRunning) {
                    // 仍在跑：跨过 60 秒就算稳定，退避归零，然后继续陪它
                    if (System.currentTimeMillis() - aliveSince > 60_000L) backoff = 2;
                    try { Thread.sleep(500L); } catch (InterruptedException e) { return; }
                }
            }
            long lived = System.currentTimeMillis() - startedAt;
            // ❗这里是 v0.0.7 的回归点：原来只要 spawn 成功就把 backoff 归零，
            //   于是一个「1 秒就死」的进程会被立刻重拉，形成 1 秒一轮的死循环，
            //   并把 cd.log 刷爆（实测每秒 2 行）。秒退必须退避，且要告诉用户为什么。
            boolean stable = lived > 60_000L;
            if (stable) {
                backoff = 2;
                logLine("[cd] 额度管家进程退出（运行 " + (lived / 1000) + "s）→ 重启");
            } else {
                boolean portBusy = KeepAlive.portInUse(CD_PORT);
                logLine("[cd] 额度管家启动后 " + (lived / 1000) + "s 就退出"
                        + (portBusy ? "（" + CD_PORT + " 端口已被占用，可能已有另一个实例在跑）" : "")
                        + " → " + backoff + " 秒后重试");
                try { Thread.sleep(backoff * 1000L); } catch (InterruptedException e) { break; }
                backoff = Math.min(backoff * 2, 60);
            }
        }
    }

    private boolean launchCd() {
        try {
            File base = getFilesDir();
            File pfx = new File(base, "pfx/usr");
            File nodeBin = new File(pfx, "bin/node");
            File cdDir = new File(base, "cd");
            File entry = new File(cdDir, "bin/creditdaddy.js");

            // 额度管家是独立小包：这里按需解包 + 校验（不再依赖大 payload 的顺序解包结果）
            if (!entry.exists()) {
                KeepAlive.cdLog(this, "入口不存在，尝试解包 assets/cd.zip");
                KeepAlive.ensureCdPayload(this);
            }
            if (!nodeBin.exists() || !entry.exists()) {
                String why = "[cd] 载荷仍缺失: node=" + nodeBin.exists() + " entry=" + entry.exists()
                        + " dirs=" + cdDir.exists();
                logLine(why);
                KeepAlive.cdLog(this, why + "\n" + KeepAlive.payloadReport(this));
                return false;
            }
            if (!nodeBin.canExecute()) nodeBin.setExecutable(true, false);
            File home = new File(base, "home");
            File cdHome = new File(home, ".creditdaddy");
            cdHome.mkdirs();

            ProcessBuilder pb = new ProcessBuilder(nodeBin.getAbsolutePath(), entry.getAbsolutePath(),
                    "daemon", "--port", String.valueOf(CD_PORT), "--host", "127.0.0.1");
            pb.directory(cdDir);
            pb.redirectErrorStream(true);
            // 单独一个日志文件：它是首次在安卓上跑，出问题必须能一眼看到报错（UI 里有「额度管家日志」入口）
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(base, "cd.log")));
            Map<String, String> env = pb.environment();
            env.put("PREFIX", pfx.getAbsolutePath());
            env.put("PATH", pfx.getAbsolutePath() + "/bin:/system/bin:/bin");
            env.put("LD_LIBRARY_PATH", pfx.getAbsolutePath() + "/lib");
            env.put("HOME", home.getAbsolutePath());
            env.put("CREDITDADDY_HOME", cdHome.getAbsolutePath());
            env.put("TMPDIR", getCacheDir().getAbsolutePath());
            env.put("ANDROID_PARENT_PID", String.valueOf(android.os.Process.myPid()));
            File ca = new File(pfx, "etc/ssl/ca-bundle.pem");
            if (ca.exists()) {
                env.put("NODE_EXTRA_CA_CERTS", ca.getAbsolutePath());
                env.put("SSL_CERT_FILE", ca.getAbsolutePath());
            }
            File icuDir = findIcuDataDir(pfx);
            if (icuDir != null) env.put("ICU_DATA", icuDir.getAbsolutePath());
            logLine("[cd] 启动额度管家 127.0.0.1:" + CD_PORT);
            cdProc = pb.start();
            return true;
        } catch (Exception e) {
            logLine("[cd] 启动失败: " + e);
            KeepAlive.cdLog(this, "启动失败: " + e);
            return false;
        }
    }

    /** launch node and block until it exits; false if it could not start or died fast */
    private boolean launchOnce() {
        try {
            File base = getFilesDir();
            File pfx = new File(base, "pfx/usr");
            File nodeBin = new File(pfx, "bin/node");
            File appDir = new File(base, "app");
            if (!nodeBin.exists()) {
                // 首次解包没跑完（没打开过 App）或解包失败：给出明确指引，别静默退避重试
                logLine("[shell] runtime missing: " + nodeBin.getAbsolutePath() + " — 请打开 10Router 完成首次初始化");
                startNotif("首次初始化未完成，请打开 10Router 完成解包");
                return false;
            }
            if (!nodeBin.canExecute()) nodeBin.setExecutable(true, false);

            ProcessBuilder pb = new ProcessBuilder(nodeBin.getAbsolutePath(), "custom-server.js");
            pb.directory(appDir);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(base, "gateway.log")));
            Map<String, String> env = pb.environment();
            env.put("PREFIX", pfx.getAbsolutePath());
            env.put("PATH", pfx.getAbsolutePath() + "/bin:/system/bin:/bin");
            env.put("LD_LIBRARY_PATH", pfx.getAbsolutePath() + "/lib");
            env.put("HOME", new File(base, "home").getAbsolutePath());
            env.put("TMPDIR", getCacheDir().getAbsolutePath());
            env.put("PORT", String.valueOf(PORT));
            // 默认只监听回环；用户显式打开"允许局域网/虚拟网访问"后才监听所有网卡
            boolean bindAll = KeepAlive.bindAll(this);
            env.put("HOSTNAME", bindAll ? "0.0.0.0" : "127.0.0.1");
            logLine("[shell] 网关监听 " + (bindAll ? "0.0.0.0（局域网/虚拟网可访问）" : "127.0.0.1（仅本机）"));
            env.put("NODE_OPTIONS", "--max-old-space-size=256");
            // 更新渠道标记：面板据此显示"下载新版 APK"，并拒绝 npm 自更新（会杀进程 + 装错目录）
            env.put("INSTALL_CHANNEL", "android");
            env.put("ANDROID_APK_URL", APK_URL);
            // 让 node 知道自己挂在哪个 App 进程下：父进程一死就自退，避免孤儿占着 20128
            env.put("ANDROID_PARENT_PID", String.valueOf(android.os.Process.myPid()));
            File ca = new File(pfx, "etc/ssl/ca-bundle.pem");
            if (ca.exists()) {
                env.put("NODE_EXTRA_CA_CERTS", ca.getAbsolutePath());
                env.put("SSL_CERT_FILE", ca.getAbsolutePath());
            }
            File icuDir = findIcuDataDir(pfx); // termux ICU data lives under share/icui18n/<ver>
            if (icuDir != null) env.put("ICU_DATA", icuDir.getAbsolutePath());
            logLine("[shell] launching node");
            proc = pb.start();
            healthy = false;
            healthFails = 0;
            // 启动验证：90s 内必须真的能服务。进程活着但不应答 = 卡死（本次日志就是这种），
            // 杀掉让监管循环重拉，比挂着"运行中"干等强。
            final Process started = proc;
            Thread verifier = new Thread(() -> {
                int waitedMs = 0;
                long prev = android.os.SystemClock.elapsedRealtime();
                for (int i = 0; i < 45; i++) {
                    if (started != proc) return;                 // 已被替换
                    if (KeepAlive.isUp()) {
                        logLine("[shell] node 启动后 " + (waitedMs / 1000) + "s 内可服务");
                        return;
                    }
                    try { Thread.sleep(2000L); } catch (InterruptedException e) { return; }
                    long now = android.os.SystemClock.elapsedRealtime();
                    long slept = now - prev;
                    prev = now;
                    // 被系统冻住的时间不算超时：否则解冻后会把刚要起来的 node 误杀
                    if (slept > 10_000L) {
                        logLine("[shell] 启动验证期间检测到冻结 " + (slept / 1000) + "s，重置计时");
                        continue;
                    }
                    waitedMs += waitedMs >= 0 ? (int) slept : 2000;
                }
                if (started == proc) {
                    logLine("[shell] node 启动 90s 仍不可服务（进程活着）→ 杀掉重拉");
                    try { started.destroy(); } catch (Exception ignored) {}
                }
            }, "gw-verify");
            verifier.setDaemon(true);
            verifier.start();
            int code = proc.waitFor();
            proc = null;
            healthy = false;
            logLine("[shell] node exited code=" + code);
            return code == 0;
        } catch (Throwable t) {
            logLine("[shell] launch failed: " + t);
            return false;
        }
    }

    /** 新版 APK 下载地址（面板更新提示条用） */
    static final String APK_URL = "https://das-sein.top/10router/get";

    /** 版本清单：检查更新用它比版本，禁止降级（比本机低的版本永远不提供入口） */
    static final String UPDATE_MANIFEST = "https://das-sein.top/10router/version.json";

    /** append one line to files/gateway.log (best effort, 带时间戳) */
    private void logLine(String line) {
        try {
            String ts = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(new java.util.Date());
            java.io.FileWriter w = new java.io.FileWriter(new File(getFilesDir(), "gateway.log"), true);
            w.write(ts + " " + line + "\n");
            w.close();
        } catch (Exception ignored) {}
    }

    private static File findIcuDataDir(File pfx) {
        File icudt = new File(pfx, "share/icui18n");
        File[] subs = icudt.exists() ? icudt.listFiles() : null;
        if (subs == null) return null;
        for (File s : subs) {
            File[] dats = s.listFiles((d, n) -> n.startsWith("icudt") && n.endsWith(".dat"));
            if (dats != null && dats.length > 0) return s;
        }
        return null;
    }

    private void startNotif(String text) { startNotif(text, null); }

    /** ci=null 点开回 App；冻结/反复被杀这类"要用户去授权"的通知，直接把人送到授权页 */
    private void startNotif(String text, PendingIntent ci) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = nm.getNotificationChannel(CH_ID);
            if (ch == null) {
                // 用 DEFAULT 而不是 LOW：很多 ROM 会把低重要性通知的前台服务降级处理
                ch = new NotificationChannel(CH_ID, "10Router 网关", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        PendingIntent pi = ci;
        if (pi == null) {
            Intent ti = new Intent(this, MainActivity.class);
            pi = PendingIntent.getActivity(this, 0, ti,
                    Build.VERSION.SDK_INT >= 24 ? PendingIntent.FLAG_IMMUTABLE : 0);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_ID) : new Notification.Builder(this);
        Notification n = b.setContentTitle("10Router")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        // ponytail: 三参 startForeground(int,Notification,int) 与 FOREGROUND_SERVICE_TYPE_* 常量
        // 都是 API 29 才引入；在 API 26/27/28 上调用即 NoSuchMethodError/NoSuchFieldError，
        // 前台服务整体失效 → 进程被疯狂回收（已实测 Android 8 上 8 次启动 8 次冻结）。
        // 低版本只能走两参，前台类型靠 targetSdk 28 豁免，不传类型不影响前台优先级。
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(1, n, fgsTypes());
            } catch (Throwable t) {
                // 类型被拒（如 API34+ 未授麦克风权限）→ 逐级回退，绝不能丢掉前台身份
                logLine("[shell] startForeground(typed) 失败: " + t + " → 回退 dataSync");
                try {
                    startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } catch (Throwable t2) {
                    logLine("[shell] startForeground(dataSync) 也失败: " + t2);
                    try { startForeground(1, n); } catch (Throwable t3) { logLine("[shell] startForeground 彻底失败: " + t3); }
                }
            }
        } else {
            // API 26-28：两参即前台服务，无类型参数
            try { startForeground(1, n); } catch (Throwable t3) { logLine("[shell] startForeground 彻底失败: " + t3); }
        }
    }

    /** 前台服务类型：对标 Operit（dataSync|microphone|specialUse）。纯 dataSync 在鸿蒙最容易被清。仅 API>=29 调用。 */
    private static int fgsTypes() {
        int t = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        if (Build.VERSION.SDK_INT >= 29) {
            t |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        if (Build.VERSION.SDK_INT >= 34) {
            t |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        return t;
    }

    @Override
    public void onDestroy() {
        logLine("[shell] svc onDestroy（被停止/回收）");
        wantRun = false;
        try { if (screenRx != null) unregisterReceiver(screenRx); } catch (Exception ignored) {}
        try { if (wlock != null && wlock.isHeld()) wlock.release(); } catch (Exception ignored) {}
        wifiLockHeld = false;
        Process p = proc;
        if (p != null) p.destroy();
        Process cp = cdProc;
        if (cp != null) cp.destroy();
        stopTunnel();
        if (runner != null) runner.interrupt();
        if (watchdog != null) watchdog.interrupt();
        if (wake != null && wake.isHeld()) {
            try { wake.release(); } catch (Exception ignored) {}
        }
        // 服务被系统停掉也别断供：交给心跳和 JobScheduler 拉回来
        KeepAlive.scheduleJob(this);
        KeepAlive.schedulePing(this);
        super.onDestroy();
    }
}
