package top.dassein.tenrouter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;

/**
 * 进程互拉（A 方案的核心）：让网关进程有一个"同 uid 的兄弟进程"盯着。
 *
 * 为什么这条比加更多闹钟有用：
 *   闹钟/JobScheduler 只能把进程从**死**里救回，且是分钟级（系统允许的最快粒度）。
 *   而国产 ROM 真正让网关不可用的动作是**冻结**（cgroup freezer：进程还在、线程全停）。
 *   被冻住的进程里，任何线程、闹钟解冻前都跑不了；但��外的同 uid 进程只要还活着，
 *   重启一个被判死的 Service 就是毫秒级的事。
 *
 * 为什么用 bindService 而不是常驻空进程：
 *   本类声明的 android:process=":guard" 会在**第一次 bindService 时**创建该进程，
 *   而"进程里有活着的 Service 实例"本身就足以让系统保留它。
 *   一旦 :guard 进程被 ROM 一起清掉，我们自己的 ServiceConnection.onServiceDisconnected
 *   不会回调（进程都死了）——所以真正的保险是它反过来盯着主进程，见 GuardService 注释。
 *
 * 代价：多一个空进程（ARM64 上约 2–4 MB RSS）。装了这个 App 的用户本来就是
 * "本地网关常驻"的重度用户，这个代价换"切走 App 后几秒内自愈"是划算的。
 */
public final class GuardLink {

    /** 守卫进程名（Android 的 : 前缀 = 与主进程同 uid、不同进程） */
    public static final String GUARD_PROCESS = ":guard";

    private static ServiceConnection conn;
    /** 上一次尝试绑定的时刻：绑定失败（守卫进程被系统限流、或被冻结）后要能重试，
     *  原实现 conn!=null 就永远返回，一次失败=守卫永久缺席。 */
    private static volatile long lastBindAt = 0;

    /**
     * 在主进程里调用：把 :guard 进程拉起来并保持绑定。
     * 幂等——已连上时直接返回；但**失败后允许重试**（每 30 秒最多一次），
     * 否则一次 bindService 失败就把兜底永久关掉了。
     */
    public static void ensureGuard(Context ctx) {
            if (KeepAlive.userStopped(ctx)) return;
        if (conn != null) return;
        long now = System.currentTimeMillis();
        if (now - lastBindAt < 30_000L) return;
        lastBindAt = now;
        final Context app = ctx.getApplicationContext();
        conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName n, IBinder b) {
                KeepAlive.logLine(app, "[guard] 已连上兄弟进程 :guard（网关被杀/被冻由它兜住）");
            }
            @Override public void onServiceDisconnected(ComponentName n) {
                // 崩了而不是正常解绑：马上重新绑，别让兜底空档存在
                KeepAlive.logLine(app, "[guard] 兄弟进程 :guard 断开 → 立即重连");
                conn = null;
                ensureGuard(app);
            }
        };
        try {
            Intent i = new Intent(app, GuardService.class);
            // BIND_AUTO_CREATE：进程不在时创建它；这就是"把 :guard 拉起来"的唯一手段
            boolean ok = app.bindService(i, conn, Context.BIND_AUTO_CREATE);
            KeepAlive.logLine(app, "[guard] bindService(:guard) = " + ok);
        } catch (Throwable t) {
            KeepAlive.logLine(app, "[guard] bindService 失败: " + t);
        }
    }

    /** 解绑（只在真的不想再兜底时调用，比如用户关掉全部保活） */
    public static void release(Context ctx) {
        ServiceConnection c = conn;
        conn = null;
        if (c == null) return;
        try { ctx.getApplicationContext().unbindService(c); } catch (Throwable ignored) {}
    }

    /**
     * 把主进程的网关服务拉起来。
     *
     * 刻意不复用 KeepAlive.ensureRunning()：那里有 8 秒节流 + `if (isUp()) return`，
     * 节流是为了防"闹钟风暴"，但守卫进程存在的意义正是**判定要更快**——
     * 它看到网关 1 秒不通就该动手，而不是和心跳抢那个节流窗口。
     */
    static void ensureGateway(Context ctx) {
        if (KeepAlive.userStopped(ctx)) return;
        // ❗startForegroundService 对**已经在跑**的服务不是"确认一下"，而是再投递一次
        //   onStartCommand（startId 自增、startCount 自增），服务会把 evaluateAnchor、
        //   startNotif、healPatches 全重跑一遍。守卫每 3 秒探一次，网关冷启要 4 秒以上，
        //   于是冷启窗口里反复重入 → 日志全是 `svc onStartCommand count=2/3` 和
        //   `关闭 VPN 锚`，而且每次重入都可能打断正在启动的 node。
        //   守卫的价值是"比心跳快"，不是"不看状态盲投"——所以这里只加**状态判断**，
        //   不加节流（节流那 8 秒是心跳用的，守卫不该被它压住）。
        try {
            if (isGatewayProbablyRunning(ctx)) return;
        } catch (Throwable ignored) {}
        try {
            Intent svc = new Intent(ctx, GatewayService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
            else ctx.startService(svc);
        } catch (Throwable t) {
            KeepAlive.logLine(ctx, "[guard] 拉起网关失败（后台启动限制？）: " + t);
        }
    }

    /**
     * 网关服务此刻是否已经在运行（= 主进程还活着）。
     *
     * ⚠️ 不能用 bindService 探测：GatewayService 跑在**主进程**，而调用本方法的是
     *    `:guard` 进程 —— 跨进程 bind 时 onServiceConnected 只有在主进程还活着并接受
     *    bind 时才会回调，主进程已经死了就永远不回调。用「等回调 + 超时返回 false」会
     *    假阴性（该拉的时候说"在跑"，干脆不拉），方向正好反了。
     *
     * 改用**服务存活就能证明的两个事实**，任一成立即认为"不必再投递"：
     *   ① 心跳新鲜（主进程的 watchLoop / 闹钟 / 锚点都会写）—— 进程还活着；
     *   ② 网关端口在监听 —— 服务已经把 node 拉起来了。
     * 两者都不成立才是真该 startForegroundService 的时候。
     */
    static boolean isGatewayProbablyRunning(Context ctx) {
        long hbAgo = KeepAlive.heartbeatAgeSec(ctx);
        if (hbAgo >= 0 && hbAgo < 45) return true;      // 主进程刚写过心跳 → 活着
        return KeepAlive.portInUse(GatewayService.PORT);
    }

    static void notifyGuardAlive(Context ctx, String text) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            final String ch = "guard";
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(ch) == null) {
                // IMPORTANCE_MIN：不出声、不弹、不占状态栏主要位置，只是让进程"有前台身份"
                nm.createNotificationChannel(new NotificationChannel(
                        ch, "网关守卫", NotificationManager.IMPORTANCE_MIN));
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, ch) : new Notification.Builder(ctx);
            Notification n = b.setContentTitle("网关守卫运行中")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build();
            nm.notify(0x9A1, n);
        } catch (Throwable ignored) {}
    }
}
