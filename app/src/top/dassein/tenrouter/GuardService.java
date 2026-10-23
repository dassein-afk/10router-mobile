package top.dassein.tenrouter;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 网关守卫：跑在独立进程 :guard 里的常驻服务，职责只有一个——**发现网关不通就立刻拉**。
 *
 * 方向说明（很重要，别搞反）：
 *   主进程用 bindService 把本进程拉起来（GuardLink.ensureGuard）。
 *   本进程反过来每几秒探一次网关 HTTP。两个进程**互为对方的存活证据**：
 *     - 主进程被 ROM 清掉 → 本进程还在，下一轮探测失败 → startForegroundService 拉回；
 *     - 本进程被清掉 → 主进程的 ServiceConnection.onServiceDisconnected 立即重绑。
 *   单靠"主进程监视自己"做不到第二条，因为主进程死了就没人监视了。
 *
 * 探测频率为什么是 3 秒：
 *   KeepAlive.isUp() 的 connectTimeout 是 700ms、readTimeout 1500ms，最坏 2.2 秒返回；
 *   3 秒一轮等于"进程死后最多 3 秒被发现 + 网关冷启约 4 秒"。用户切走去别的 App 用网关时，
 *   撞上重启窗口的概率从原来的"最多 5 分钟"降到"约 5 秒内恢复"。功耗上这是 3 秒一次
 *   本机回环 HTTP HEAD，回环不点无线电，可忽略。
 *
 * 为什么还发一条 IMPORTANCE_MIN 通知：
 *   空进程 + 无前台身份会被 ROM 优先回收，那这个兜底就白做了。
 *   MIN 通道不出声、不弹窗、不打扰，只是让系统认它是"有前台身份"的进程。
 */
public class GuardService extends Service {

    private static final long TICK_MS = 3_000L;
    /**
     * 心跳超过这么久没更新就判定主进程被冻住。
     *
     * 主进程的 watchdog 每轮都写心跳，正常间隔约 30 秒（watchLoop 的 sleep）。
     * 取 90 秒 = 3 倍余量：既不会把正常的长时间健康探测误判成冻结，
     * 又能保证用户体感"切走一分钟后回来还能用"。
     * ❗ 不要调小到和探测间隔同量级——会把"node 正在冷启动"误判成冻结，
     *    那样每次冷启动都会被自己重启一遍，永远起不来。
     */
    private static final long HB_STALE_SEC = 90L;

    private volatile boolean alive = true;
    private Thread loop;
    /** 上一次因判定冻结而强制拉起的时刻（60 秒退避，见 tickLoop） */
    private volatile long lastFreezeKickAt = 0;

    @Override public void onCreate() {
        super.onCreate();
        KeepAlive.logLine(this, "[guard] 守卫进程已启动（独立进程，只做探活与拉起）");
        // API 26+ 后台服务必须尽快前台化，否则被判死；低版本直接 startForeground 即可
        try { startForeground(0x9A1, notif()); } catch (Throwable t) {
            KeepAlive.logLine(this, "[guard] startForeground 失败: " + t);
        }
        loop = new Thread(this::tickLoop, "guard-tick");
        loop.setDaemon(true);
        loop.start();
    }

    private android.app.Notification notif() {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        final String ch = "guard";
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(ch) == null) {
            nm.createNotificationChannel(new android.app.NotificationChannel(
                    ch, "网关守卫", android.app.NotificationManager.IMPORTANCE_MIN));
        }
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(this, ch)
                : new android.app.Notification.Builder(this);
        return b.setContentTitle("网关守卫运行中")
                .setContentText("网关一旦不通，几秒内自动拉起")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void tickLoop() {
        int fails = 0;
        int launches = 0;
        long lastLaunchAt = 0;
        while (alive) {
            try { Thread.sleep(TICK_MS); } catch (InterruptedException e) { break; }
            // 用户点过「彻底退出」→ 守卫立刻自我了断，绝不把主进程拉回来
            if (KeepAlive.userStopped(this)) {
                KeepAlive.logLine(this, "[guard] 用户已要求停止 → 守卫退出");
                stopSelf();
                return;
            }
            boolean up = KeepAlive.isUp();

            // ---- 冻结自愈（这条才是"退出 App 后 Agent 不通"的真正解药）----
            // 主进程被 ROM 冻住时：进程还在、心跳却停写，node 的事件循环也跟着停，
            // 于是 Agent 的连接要么排队没人接、要么接到一个永远不回包的死 socket。
            // 从用户角度看就是"退出 App 后 Agent 没反应，回去就好了"。
            // 守卫进程在**另一个**进程里，不受冻结影响，由它判定并强制重启。
            long hbAgo = KeepAlive.heartbeatAgeSec(this);
            if (hbAgo > HB_STALE_SEC) {
                // 退避：ROM 处于"长期冻结"状态时，我们每 3 秒拉一次只会白耗电且日志刷屏，
                // 而且拉也拉不动（系统就是不调度主进程）。改成 60 秒一次，直到心跳活过来。
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastFreezeKickAt > 60_000L) {
                    lastFreezeKickAt = nowMs;
                    KeepAlive.logLine(this, "[guard] 主进程心跳已停 " + hbAgo + "s → 判定被冻结，强制拉起");
                    GuardLink.ensureGateway(this);
                    KeepAlive.ensureRunning(this);
                    try { KeepAlive.schedulePing(this); } catch (Throwable ignored) {}
                }
                fails = 0;
                continue;
            }

            // ⚠️⚠️ 这里原来是 `if (up) { fails=0; continue; }`，有两个致命问题，实测直接造成启动风暴：
            //   ① 探测的是**网关 HTTP**，而网关冷启要 4 秒以上。主进程明明活着、只是在正常重启 node，
            //      我们却当成"主进程死了"，一个劲 startForegroundService → onStartCommand → 又重启 node。
            //   ② **阈值被自己抬高**：守卫连续 2 次不通就拉起，把 onStartCommand 的计数一推，
            //      下一轮阈值就变成 60 秒以上才动手 —— 该出手的时候反而不出手了。
            // 日志实锤：进程启动第 57 次，而 14 分钟里只有 3 次（60/80/100）；20:56:45 和 21:06:56
            // 两处 `ensureRunning 节流跳过（41ms/43ms 前刚拉过）` = 心跳闹钟和守卫在同一毫秒抢着拉起。
            // 正确判据：**心跳是否新鲜**才是"主进程还活着"的唯一证据，网关 HTTP 只是副证据。
            boolean hbFresh = hbAgo >= 0 && hbAgo < 45;
            if (up || hbFresh) {
                if (fails > 0) {
                    KeepAlive.logLine(this, "[guard] 网关恢复（之前连续 " + fails + " 次不通）");
                }
                fails = 0;
                continue;
            }
            fails++;
            // 连续 3 次不通、且距上次拉起至少 15 秒才动手。
            // 原先是"连续 2 次、无时间下限"，3 秒一轮等于 6 秒一拉，正好落在网关冷启窗口里。
            long nowMs2 = System.currentTimeMillis();
            if (fails < 3 || nowMs2 - lastLaunchAt < 15_000L) continue;
            fails = 0;
            lastLaunchAt = nowMs2;
            launches++;
            // 日志里必须留下「我为什么认为它死了」：只有 HTTP 不通但心跳新鲜 = 网关自己在重启，别插手
            KeepAlive.logLine(this, "[guard] 网关不通且心跳已停 " + hbAgo + "s → 第 " + launches + " 次拉起");
            // 先直接拉（不受 keepalive 的 8 秒节流约束——守卫的意义就是比心跳快），
            // 再让 ensureRunning 走一遍它那套内部逻辑（幂等，且会重排闹钟/Job）。
            GuardLink.ensureGateway(this);
            KeepAlive.ensureRunning(this);
            // 拉起后立刻重排心跳：否则主进程里那条 AlarmClock 可能已经随进程死掉了
            try { KeepAlive.schedulePing(this); } catch (Throwable ignored) {}
        }
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        return START_STICKY;   // 被系统杀掉后请把我重建
    }

    @Override public void onDestroy() {
        alive = false;
        if (loop != null) loop.interrupt();
        KeepAlive.logLine(this, "[guard] 守卫进程 onDestroy（被回收）——主进程会立即重绑");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
