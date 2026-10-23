package top.dassein.tenrouter;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

/**
 * 最强一档的守候通道（VpnService）。
 *
 * 为什么它最强：VpnService 是系统级网络组件——system_server 自己绑定它，进程被杀会直接影响
 * 整机网络状态。系统设置里打开"始终开启的 VPN"后，**系统会主动把它重新拉起**，
 * 而不是像普通应用那样只能靠 START_STICKY 或闹钟求它回来。这正是 wgtunnel 能长期活着的机制。
 *
 * 本实现刻意做成"零路由"：
 *   - 只 addAddress，不调用 addRoute / addDnsServer
 *   - 因此没有任何流量被导入这条隧道，不代理、不解析、不读取用户数据
 *   - 它存在的唯一目的，是让系统把我们当作 VPN 组件持有，从而获得系统级的存活待遇
 *
 * 代价（必须让用户知道）：Android 同一时刻只允许一条 VPN。
 * 开启本通道会顶掉手机上现有的 VPN（例如 WireGuard）；反之亦然。
 * 所以它是可选项，不是默认。
 */
public class VpnKeepAliveService extends VpnService {

    public static final String ACTION_STOP = "top.dassein.tenrouter.VPN_STOP";

    /** 通道是否真的建立成功（UI/诊断读这个，别猜） */
    public static volatile boolean active = false;
    private static volatile long establishedAt = 0;

    private ParcelFileDescriptor tun;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            KeepAlive.logLine(this, "[vpn] 收到停止指令，关闭守候通道");
            closeTun();
            stopSelf();
            return START_NOT_STICKY;
        }
        // ❗意志优先：用户把锚点模式选成「关」之后，任何来源（START_STICKY 重建、
        //   系统重启隧道、别的代码误调）都不许再把通道建起来。
        //   没有这道闸，用户点了关、系统下一秒重建，界面又变回「占用中」。
        if (!KeepAlive.anchorWanted(this)) {
            KeepAlive.logLine(this, "[vpn] 锚点模式为关，拒绝建立守候通道");
            closeTun();
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureForeground();   // 必须是前台服务，否则 startForegroundService 会在 5s 后判死
        startTun();
        KeepAlive.ensureRunning(this);
        KeepAlive.schedulePing(this);
        // 交回协调器裁决：模式若不该用 VPN 锚（比如用户已切到音频/关），本服务会被停掉，
        // 防止 START_STICKY/系统重启隧道时无视用户意愿抢通道
        KeepAlive.evaluateAnchor(this, true);
        return START_STICKY;
    }

    /** VpnService 同时作为前台服务存在：既满足 startForegroundService 的要求，也让守候更稳 */
    private void ensureForeground() {
        try {
            final String CH = "gw2";
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        CH, "10Router 网关", android.app.NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            android.content.Intent open = new android.content.Intent(this, MainActivity.class);
            open.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 23) flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0x5F, open, flags);

            android.app.Notification.Builder nb;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nb = new android.app.Notification.Builder(this, CH);
            } else {
                nb = new android.app.Notification.Builder(this);
            }
            nb.setContentTitle("10Router 守候中")
              .setContentText("系统级躺驻已开启（零路由，不接管流量）")
              .setSmallIcon(android.R.drawable.stat_sys_download_done)
              .setContentIntent(pi)
              .setOngoing(true)
              .setShowWhen(false);
            startForeground(2, nb.build());
        } catch (Throwable t) {
            KeepAlive.logLine(this, "[vpn] startForeground 失败: " + t);
        }
    }

    private void startTun() {
        if (tun != null) return;
        try {
            Builder b = new Builder();
            b.setSession("10Router 守候通道");
            // 仅给隧道一个内网地址，不声明任何路由 → 不接管任何应用的流量
            b.addAddress("10.121.121.2", 32);
            b.setMtu(1500);
            b.setBlocking(false);
            tun = b.establish();
            active = tun != null;
            establishedAt = System.currentTimeMillis();
            KeepAlive.logLine(this, "[vpn] 守候通道建立 " + (active ? "成功" : "失败(tun=null)")
                    + "（零路由：不接管流量）");
        } catch (Throwable t) {
            active = false;
            KeepAlive.logLine(this, "[vpn] 建立失败: " + t);
        }
    }

    /**
     * 用户在系统里撤销/切换到别的 VPN。
     *
     * ❗这里不能再"降到音频锚"：音频锚已删除。而且不能让协调器立刻重排 —— 撤销瞬间我们的
     * Network 记录可能还没消失，`externalVpn()` 会误判"通道空闲"，转头又把隧道建回来，
     * 和用户的梯子互相顶。正确做法是交给协调器，但先记一笔"刚被撤销"，让它走 90 秒冷静期。
     */
    @Override
    public void onRevoke() {
        KeepAlive.logLine(this, "[vpn] 系统撤销了守候通道（可能被别的 VPN 顶掉）");
        closeTun();
        boolean byOtherVpn = KeepAlive.externalVpn(this);
        KeepAlive.noteVpnRevoked(this, byOtherVpn);
        KeepAlive.evaluateAnchor(this, true);
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        KeepAlive.logLine(this, "[vpn] onDestroy");
        closeTun();
        // 被系统停掉也别断供：交给闹钟/Job 再拉
        current = null;
        KeepAlive.schedulePing(this);
        super.onDestroy();
    }

    /** 从任意外部位置立即释放隧道（用户点「关」/ 彻底退出时用，不等服务生命周期） */
    public static void forceCloseTun() {
        try {
            VpnKeepAliveService inst = current;
            if (inst != null) inst.closeTun();
        } catch (Throwable ignored) {}
        active = false;
    }

    private static volatile VpnKeepAliveService current;

    @Override public void onCreate() {
        super.onCreate();
        current = this;
    }

    private void closeTun() {
        try { if (tun != null) tun.close(); } catch (Throwable ignored) {}
        tun = null;
        active = false;
    }

    public static long establishedAt() { return establishedAt; }
}
