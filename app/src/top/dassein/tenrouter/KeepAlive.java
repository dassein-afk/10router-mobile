package top.dassein.tenrouter;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 保活与自愈共用工具：健康探测 + 定时/网络恢复时把网关拉回来。
 *
 * 为什么需要：安卓会杀后台进程、也会把进程冻住（进程还在、通知还在，但 socket 没人应答）。
 * 单靠 START_STICKY 不够，需要"外部闹钟 + JobScheduler + 网络恢复"几条冗余路径。
 */
public final class KeepAlive {

    static final String ACTION_PING = "top.dassein.tenrouter.PING";
    private static final int JOB_ID = 0x10A;
    private static final int PING_INTERVAL_MIN = 5;
    /** 电池豁免机器的闹钟间隔（秒）：见 schedulePing 注释，用户核心场景要求秒级自愈 */
    private static final int PING_EXEMPT_SEC = 15;

    private KeepAlive() {}

    /** 网关是否真的在服务（不只是进程存在） */
    /**
     * 端口上是否已经有人在监听（用于区分「起不来」和「已经有一个实例在跑」）。
     * 只做一次本机 TCP connect，不发送任何数据，200ms 超时。
     */
    public static boolean portInUse(int port) {
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    public static boolean isUp() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "http://127.0.0.1:20128/api/health").openConnection();
            c.setConnectTimeout(700);
            c.setReadTimeout(1500);
            int rc = c.getResponseCode();
            c.disconnect();
            return rc == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** 把网关服务拉起来；目标 SDK 28，后台调 startForegroundService 不受 API31 限制 */
    public static void ensureRunning(Context ctx) {
        // 节流：风暴实测过——96 次 onStartCommand 在几十秒内，每次都从头走一遍服务启动。
        // 8 秒内只允许拉一次，日志里也能看出是谁在反复触发。
        long now = System.currentTimeMillis();
        if (now - lastEnsureAt < 8_000L) {
            logLine(ctx, "[keepalive] ensureRunning 节流跳过（" + (now - lastEnsureAt) + "ms 前刚拉过）");
            return;
        }
        lastEnsureAt = now;
        // ❗别在这里 return：原来 `if (isUp()) return;` 把下面的 GuardLink.ensureGuard()
        // 一起短路了——网关一健康，守卫进程就永远拉不起来。而守卫进程恰恰是被清后
        // 几秒内拉回的唯一手段，结果等于守卫只在"已经坏了"的时候才存在，形同虚设。
        if (!isUp()) {
            try {
                Intent svc = new Intent(ctx, GatewayService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
                else ctx.startService(svc);
            } catch (Exception e) {
                logLine(ctx, "[keepalive] startService 失败: " + e);
            }
        }
        // 每次"确认网关该在跑"的时刻都确保守卫进程在（幂等，conn 已存在就直接返回）。
        // 放在节流之后，避免高频心跳反复 bindService。
        GuardLink.ensureGuard(ctx);
    }

    private static volatile long lastEnsureAt = 0;

    /** 注册 JobScheduler 周期守护（15 分钟粒度，被系统杀后也能回来） */
    public static void scheduleJob(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, WatchdogJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true);
            js.schedule(b.build());
        } catch (Exception ignored) {
        }
    }

    /**
     * 注册心跳。关键：EMUI / HarmonyOS 会把普通精确闹钟（setExact / setExactAndAllowWhileIdle）
     * 静默吞掉——实测进程被冻结 75 分钟期间，1 分钟一次的精确闹钟一次都没响过。
     * 唯一在激进省电 + 后台冻结下仍准时投递的是 setAlarmClock（系统"闹钟"类）。
     * 代价：状态栏出现闹钟图标；换来的是冻结期间也能被唤醒。
     *
     * ❗ 间隔为什么从 1 分钟改成 15 秒：
     *   用户的核心场景是"我在别的 App 里调本机网关"。撞上一次 1 分钟的空档，对话就断一次。
     *   已获电池豁免时 Doze 本来就不限制我们，15 秒是允许的；未豁免时仍走 5 分钟
     *   （系统会对短周期精确闹钟做限流，设了也会被拉长，所以干脆按档位给）。
     *   真正"几秒级"的那条保活是 GuardService（独立进程 3 秒探活），闹钟是它的兜底。
     */
    public static void schedulePing(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = pingIntent(ctx);
            boolean exempt = isIgnoringBattery(ctx);
            long delay = (exempt ? PING_EXEMPT_SEC : PING_INTERVAL_MIN * 60L) * 1000L;
            if (android.os.Build.VERSION.SDK_INT >= 21 && (exempt || isAggressiveRom())) {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(
                        System.currentTimeMillis() + delay, showIntent(ctx)), pi);
                lastAlarmMode = "AlarmClock(" + (delay / 1000) + "s)";
            } else if (exempt && android.os.Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + delay, pi);
                lastAlarmMode = "ExactAndAllowWhileIdle(" + (delay / 1000) + "s)";
            } else {
                am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + delay, delay, pi);
                lastAlarmMode = "InexactRepeating(" + (delay / 60000) + "min)";
            }
        } catch (Exception e) {
            logLine(ctx, "[keepalive] schedulePing 失败: " + e);
        }
    }

    /** 最近一次注册的闹钟类型（诊断用：能看出到底用的哪一档） */
    public static volatile String lastAlarmMode = "未注册";

    /** 华为/荣耀/鸿蒙：这几个 ROM 对后台闹钟与进程冻结最激进 */
    public static boolean isAggressiveRom() {
        try {
            String mf = (android.os.Build.MANUFACTURER + " " + android.os.Build.BRAND + " "
                    + android.os.Build.DISPLAY).toLowerCase();
            return mf.contains("huawei") || mf.contains("honor") || mf.contains("harmony");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 闹钟图标点开时回到 App（setAlarmClock 必须给 showIntent） */
    private static PendingIntent showIntent(Context ctx) {
        android.content.Intent i = new android.content.Intent(ctx, MainActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(ctx, 0x2B, i, flags);
    }

    // ---------- 冻结/被杀取证 ----------

    /** 记录一次被冻结事件：次数 + 最长一次 + 最后发生时间（诊断报告用） */
    public static void bumpFreeze(Context ctx, long seconds) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "freeze.txt");
            int count = 0;
            long max = 0;
            if (f.exists()) {
                BufferedReader r = new BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f)));
                String l = r.readLine();
                r.close();
                if (l != null) {
                    String[] p = l.split(",");
                    if (p.length >= 2) { count = Integer.parseInt(p[0].trim()); max = Long.parseLong(p[1].trim()); }
                }
            }
            count++;
            if (seconds > max) max = seconds;
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write(count + "," + max + "," + System.currentTimeMillis());
            w.close();
        } catch (Exception ignored) {}
    }

    /** 读冻结统计：返回 {次数, 最长秒数, 最后时间ms} */
    public static long[] freezeStats(Context ctx) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "freeze.txt");
            if (!f.exists()) return new long[]{0, 0, 0};
            BufferedReader r = new BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f)));
            String l = r.readLine();
            r.close();
            if (l == null) return new long[]{0, 0, 0};
            String[] p = l.split(",");
            return new long[]{
                    p.length > 0 ? Long.parseLong(p[0].trim()) : 0,
                    p.length > 1 ? Long.parseLong(p[1].trim()) : 0,
                    p.length > 2 ? Long.parseLong(p[2].trim()) : 0};
        } catch (Exception e) {
            return new long[]{0, 0, 0};
        }
    }

    // ===================== 服务器模式：屏幕常亮兜底 =====================
    // 激进 ROM 的冻结几乎都挂在"息屏"这个触发器上；锁屏保活不追求省电，追求绝对在线：
    // 屏幕不自动熄灭 → 冻结器大概率不触发、无线电不进省电。
    // 三态：0=关 1=全亮 2=亮度0。
    // ⚠️ 亮度0 的诚实边界（踩过坑，别再宣传错）：亮度0 只关背光，【电容触控层依旧完全工作】——
    //    放口袋照样误触。它只防"ROM 因息屏而冻结"，不防误碰。
    //    真正防误碰只有两条路：① 屏幕真正关闭（手动锁屏，触控由锁屏界面接管/禁用）
    //    ② 系统级「防误触保护/口袋模式」（距离传感器检测遮挡，各厂商路径不同，只能引导用户开）。
    // 诚实边界：1/2 挡自动息屏，挡不住手动按电源锁屏。

    public static int keepScreenMode(Context ctx) {
        File f = new File(ctx.getFilesDir(), "keep_screen_on");
        if (!f.exists()) return 0;
        return "2".equals(readSmall(f)) ? 2 : 1;
    }

    public static boolean keepScreenOn(Context ctx) { return keepScreenMode(ctx) > 0; }

    private static String readSmall(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] b = new byte[8];
            int n = in.read(b);
            in.close();
            return n > 0 ? new String(b, 0, n).trim() : "1";
        } catch (Exception e) { return "1"; }
    }

    public static void setKeepScreenMode(Context ctx, int mode) {
        try {
            File f = new File(ctx.getFilesDir(), "keep_screen_on");
            if (mode <= 0) f.delete();
            else {
                java.io.FileOutputStream o = new java.io.FileOutputStream(f);
                o.write((mode == 2 ? "2" : "1").getBytes());
                o.close();
            }
        } catch (Exception ignored) {}
        logLine(ctx, "[screen] 锁屏保活 → " + (mode == 0 ? "关" : mode == 2 ? "亮度0（不误碰）" : "全亮"));
    }

    public static void setKeepScreenOn(Context ctx, boolean on) { setKeepScreenMode(ctx, on ? 1 : 0); }

    // ===================== 触摸屏蔽（覆盖层吃掉 MotionEvent）=====================
    // 真相：亮度0 只关背光，电容触控层依旧完全工作（StackOverflow 权威确认）。
    // 要「屏幕看不见 + 禁止触摸」必须双管齐下：亮度0（看不见）+ 触摸屏蔽（挡误触）。
    // 触摸屏蔽 = 全屏 TYPE_APPLICATION_OVERLAY 覆盖层，OnTouchListener 返回 true 吃掉所有事件。

    public static boolean touchBlockEnabled(Context ctx) {
        return new File(ctx.getFilesDir(), "touch_block").exists();
    }

    public static void setTouchBlock(Context ctx, boolean on) {
        try {
            File f = new File(ctx.getFilesDir(), "touch_block");
            if (on) f.createNewFile(); else f.delete();
        } catch (Exception ignored) {}
        if (on) TouchBlockService.start(ctx); else TouchBlockService.stop(ctx);
        logLine(ctx, "[touch] 触摸屏蔽 → " + (on ? "开（覆盖层吃掉所有触摸）" : "关"));
    }

    /** 覆盖层权限（悬浮窗）是否已授予。没授就引导用户去开。 */
    public static boolean canDrawOverlays(Context ctx) {
        try { return android.provider.Settings.canDrawOverlays(ctx); } catch (Throwable t) { return true; }
    }

    // ===================== 「后台活动」授权页深链 =====================
    // 冻结实锤之后必须把人一步送到正确的开关前，通知/按钮都走这个，不再让人自己翻设置。

    public static android.content.Intent backgroundRunIntent(Context ctx) {
        String pkg = ctx.getPackageName();
        String[] actions = {
                "android.settings.APPLICATION_ENERGY_SAVING_SETTINGS",   // 应用级省电详情（Android 10+，华为多数认）
                "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", // 电池优化豁免列表（AOSP 全认）
                "android.settings.APPLICATION_DETAILS_SETTINGS",         // 应用信息页（保底）
        };
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        for (String a : actions) {
            android.content.Intent i = new android.content.Intent(a);
            if (a.endsWith("APPLICATION_DETAILS_SETTINGS")) {
                i.setData(android.net.Uri.parse("package:" + pkg));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                if (pm.resolveActivity(i, 0) != null) return i;
            } catch (Exception ignored) {}
        }
        android.content.Intent last = new android.content.Intent("android.settings.APPLICATION_DETAILS_SETTINGS");
        last.setData(android.net.Uri.parse("package:" + pkg));
        last.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return last;
    }

    public static PendingIntent backgroundRunPending(Context ctx) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(ctx, 0x6C, backgroundRunIntent(ctx), flags);
    }

    /** 用户是否开启了"极强守候"（VpnService 通道）——持久化，开机/升级后据此恢复 */
    public static boolean vpnWanted(Context ctx) {
        try {
            return new java.io.File(ctx.getFilesDir(), "vpn_wanted").exists();
        } catch (Exception e) {
            return false;
        }
    }

    public static void setVpnWanted(Context ctx, boolean on) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "vpn_wanted");
            if (on) f.createNewFile(); else f.delete();
        } catch (Exception ignored) {}
    }

    // ===================== 守候锚点：VPN 锚与第三方 VPN 互斥让位 =====================
    // 痛点：用户的 VPN 通道要留给梯子/Tailscale/WG，第 4 档 VPN 锚抢槽就不可用。
    // 方案：两档锚点由一个协调器按通道占用情况互切——VPN 空闲用 VPN 锚（最硬），
    // 被占用立刻让位（不抢通道），外部 VPN 撤了再切回。

    /**
     * 守候模式：auto=通道空闲时用 VPN 锚、被占用时自动让位 | vpn=强制 VPN 锚 | off=关。
     *
     * 音频锚（第 3.5 档）已删除：Android 14+ 上它的 startForeground 缺类型声明会抛异常并被吞掉，
     * 实际从未进入前台；且国产 ROM 的冻结判据是白名单与自启动授权，不看进程里有没有音频流。
     * 留着只会让用户以为"开着锚"，比没有更糟。替代它的是系统绑定锚（通知使用权）+ 自启动引导。
     *
     * 升级迁移：没有 anchor_mode 文件时按老 vpn_wanted 判定 auto/off；老值 "audio" 一律读成 auto，
     * 免得用户卡在一个已经不存在的档位上。
     */
    public static String anchorMode(Context ctx) {
        String m = readFile1(new java.io.File(ctx.getFilesDir(), "anchor_mode")).trim();
        if (m.equals("audio")) return "auto";   // 已删除的档位，静默迁移
        if (m.equals("auto") || m.equals("vpn") || m.equals("off")) return m;
        return vpnWanted(ctx) ? "auto" : "off";
    }

    public static void setAnchorMode(Context ctx, String m) {
        if (m.equals("audio")) m = "auto";
        setVpnWanted(ctx, m.equals("auto") || m.equals("vpn"));
        try {
            java.io.FileWriter w = new java.io.FileWriter(
                    new java.io.File(ctx.getFilesDir(), "anchor_mode"), false);
            w.write(m); w.close();
        } catch (Exception ignored) {}
        logLine(ctx, "[anchor] 守候模式 → " + m);
        // ❗必须立刻生效，不能等 20 秒轮询：
        //   用户点「关」的时候期待的是"马上掉"，等一轮轮询回来再拆，观感就是"按钮没用"。
        //   force=true 跳过 20 秒节流，并在 off 分支里真的 stopService。
        try { evaluateAnchor(ctx, true); } catch (Throwable ignored) {}
    }

    /**
     * 有没有第三方 VPN 在占通道（梯子/Tailscale/wg）；我们自己的守候隧道不算。
     *
     * ❗旧实现第一行是 `if (VpnKeepAliveService.active) return false;` —— 只要我们自己的隧道开着
     * 就永远说"没有外部 VPN"，于是再也发现不了用户的梯子顶掉了我们，转头又去抢回来，
     * 两边来回顶 = 用户说的"它们会被同时使用"。必须先看网络、再判归属。
     */
    public static boolean externalVpn(Context ctx) {
        int count = 0;
        boolean anyVpn = false;
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    anyVpn = true;
                    count++;
                }
            }
        } catch (Throwable ignored) { return false; }
        if (!anyVpn) return false;
        // 我们自己的隧道也算一条；多于一条就说明外面确实还有别人的 VPN
        return count > (VpnKeepAliveService.active ? 1 : 0);
    }

    /**
     * 诊断用：把每一条 VPN 网络的「归属谁」原样列出来。
     *
     * ❗加这个是因为实测出现「外部 VPN 占通道=是」但用户根本没开梯子，
     * 而 externalVpn() 只看条数 —— 条数对不上时我们无法从诊断里判断到底是
     * ①华为自己的某个网络被标成了 VPN ②还是我们自己的隧道被算了两次。
     * 与其猜，不如把这行贴出来看。
     */
    public static String vpnNetworksDump(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "无法读取（ConnectivityManager=null）";
            int n = 0;
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(net);
                if (c == null || !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                n++;
                int uid = c.getOwnerUid();
                String owner = "未知";
                try {
                    if (uid >= 0) {
                        String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
                        owner = pkgs != null && pkgs.length > 0 ? pkgs[0] : ("uid=" + uid);
                    }
                } catch (Throwable ignored) {}
                boolean ours = owner.equals(ctx.getPackageName());
                sb.append("\n   · ").append(ours ? "本应用隧道" : "其他 App：" + owner)
                  .append("（uid=").append(uid).append("）");
            }
            if (n == 0) sb.append("\n   · 没有任何 VPN 网络");
        } catch (Throwable t) { sb.append(" 读取失败：").append(t); }
        return sb.toString();
    }

    private static volatile boolean lastExt = false;
    private static volatile long extClearedAt = 0;      // 外部 VPN 消失的时刻（防抖回切）
    private static volatile long lastEvalAt = 0;
    private static volatile String lastAnchorState = null;

    /** 协调器唯一入口：算出该用哪档锚点并切过去。幂等，20 秒内重复调用直接跳过（轮询友好）。 */
    public static void evaluateAnchor(Context ctx) { evaluateAnchor(ctx, false); }

    public static synchronized void evaluateAnchor(Context ctx, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastEvalAt < 20_000) return;
        lastEvalAt = now;
        String m = anchorMode(ctx);
        boolean ext = externalVpn(ctx);
        if (!ext && lastExt) extClearedAt = now;   // 刚从"被占"变"空闲"：记时刻，等 90s 再回切
        lastExt = ext;

        if (m.equals("off")) {
            stopVpnAnchor(ctx);
            writeAnchorState(ctx, "off", "用户已关（只靠系统绑定锚 + 闹钟）");
            return;
        }
        String want = m.equals("auto") ? (ext ? "none" : "vpn") : m;
        String why = m.equals("auto")
                ? (ext ? externalVpnName(ctx) + " 正占用 VPN 通道，已让位（双 VPN 会互相顶掉）" : "VPN 通道空闲")
                : "手动指定";

        if (want.equals("vpn")) {
            // 自动回切要等外部 VPN 确实走掉 90 秒（它的重连抖动期间不抢回来）
            if (m.equals("auto") && extClearedAt != 0 && now - extClearedAt < 90_000) {
                writeAnchorState(ctx, "wait", "外部 VPN 刚断开，90 秒后切回 VPN 锚（期间不抢通道）");
                return;
            }
            extClearedAt = 0;
            if (!VpnKeepAliveService.active) {
                // ❗冷静期：刚被撤销 90 秒内不重建，避免与用户的梯子拉锯
                if (vpnRevokedAt != 0 && now - vpnRevokedAt < 90_000) {
                    writeAnchorState(ctx, "wait", "隧道刚被撤销（" + (vpnRevokedByOther ? "其他 VPN 顶掉" : "系统关闭")
                            + "），90 秒内不重建，避免双 VPN 互相顶");
                    return;
                }
                // ❗占用通道前先看有没有别人的 VPN，有就绝不建立 —— 双 VPN 同时存在会互相踢下线
                if (externalVpn(ctx)) {
                    writeAnchorState(ctx, "wait", externalVpnName(ctx) + " 在用，不抢通道");
                    return;
                }
                try {
                    if (android.net.VpnService.prepare(ctx) == null) {
                        startVpnAnchor(ctx);
                        vpnRevokedAt = 0;
                        writeAnchorState(ctx, "vpn", why);
                    } else {
                        // 还没授权：不自动弹窗（后台弹窗会被系统拒），等用户自己来点
                        writeAnchorState(ctx, "wait", "VPN 锚未授权：需要时在设置里点一下授权");
                    }
                } catch (Throwable t) {
                    writeAnchorState(ctx, "wait", "VPN 锚建立异常：" + t + "（靠系统绑定锚兜着）");
                }
                return;
            }
            writeAnchorState(ctx, "vpn", why);
        } else {
            // 让位：把我们的隧道撤掉，把 VPN 槽还给用户的梯子
            if (VpnKeepAliveService.active) stopVpnAnchor(ctx);
            writeAnchorState(ctx, "none", why);
        }
    }

    /** 外部 VPN 的可读名字（诊断/界面用）；拿不到就返回"其他 VPN"。 */
    public static String externalVpnName(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "其他 VPN";
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null || !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                String name = c.getOwnerUid() >= 0
                        ? ctx.getPackageManager().getNameForUid(c.getOwnerUid()) : null;
                if (name != null && !name.equals(ctx.getPackageName())) return name;
            }
        } catch (Throwable ignored) {}
        return "其他 VPN";
    }

    /**
     * 记录一次"守候隧道被系统撤销"。被别的 VPN 顶掉时，冷静期内不再抢通道，
     * 否则两边会形成"我建你顶、你顶我建"的拉锯 —— 用户看到的就是两条 VPN 同时在用。
     */
    public static void noteVpnRevoked(Context ctx, boolean byOtherVpn) {
        vpnRevokedAt = System.currentTimeMillis();
        vpnRevokedByOther = byOtherVpn;
        logLine(ctx, "[anchor] 隧道被撤销（第三方 VPN 顶掉=" + byOtherVpn + "），进入 90 秒冷静期");
    }

    private static volatile long vpnRevokedAt = 0;
    private static volatile boolean vpnRevokedByOther = false;

    /** 当前锚点状态一行（"mode|原因"），供首页 chip / 诊断显示，别猜。 */
    public static String anchorState(Context ctx) {
        String s = readFile1(new java.io.File(ctx.getFilesDir(), "anchor_state")).trim();
        return s;
    }

    private static void writeAnchorState(Context ctx, String mode, String why) {
        String line = mode + "|" + why;
        if (line.equals(lastAnchorState)) return;
        lastAnchorState = line;
        try {
            java.io.FileWriter w = new java.io.FileWriter(
                    new java.io.File(ctx.getFilesDir(), "anchor_state"), false);
            w.write(line); w.close();
        } catch (Exception ignored) {}
    }

    /**
     * 用户到底要不要 VPN 锚。服务被系统重建时必须先问这一句。
     *
     * 为什么不能只看静态变量 active：进程被清后 active 归零，START_STICKY 把服务拉回来时
     * 它并不知道用户已经把模式选成了「关」——于是老老实实又建一条隧道。
     * 用户看到的正是「我点了关，它又自己开了」。模式存在文件里，跨进程死亡也有效。
     */
    public static boolean anchorWanted(Context ctx) {
        String m = anchorMode(ctx);
        if ("off".equals(m)) return false;
        if ("vpn".equals(m)) return true;
        // auto：只有通道空闲时才用 VPN 锚（有别人的梯子就让位）
        return !externalVpn(ctx);
    }

    private static void startVpnAnchor(Context ctx) {
        if (VpnKeepAliveService.active) return;
        try {
            Intent i = new Intent(ctx, VpnKeepAliveService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable t) { logLine(ctx, "[anchor] 启动 VPN 锚失败: " + t); }
    }

    private static void stopVpnAnchor(Context ctx) {
        // ❗不能再用 startService(ACTION_STOP)：服务没在跑时，Android 会先把服务
        //   create 起来、走一遍正常 onStartCommand → startTun() 真的把隧道建立出来，
        //   然后才收到 STOP。用户点了"关"，结果是先建再拆，中间还可能是 START_STICKY
        //   把它再拉一轮 —— 表现就是「VPN 点关了照样不掉」。
        // stopService 直接停，onDestroy 里 closeTun()，全程不碰 establish()。
        try {
            Intent i = new Intent(ctx, VpnKeepAliveService.class);
            ctx.stopService(i);
        } catch (Throwable t) {
            logLine(ctx, "[anchor] 关闭 VPN 锚失败: " + t);
        }
        // 兜底：即便服务没能停（比如被系统持着），也要立刻关 tun，别让通道继续挂着
        boolean wasActive = VpnKeepAliveService.active;
        VpnKeepAliveService.forceCloseTun();
        // ❗只在**真的动了什么**的时候记一行。
        //   原来无条件 log，而 evaluateAnchor 每 20 秒被网关/闹钟调一次，
        //   于是 GatewayService 的 onStartCommand → evaluateAnchor → stopVpnAnchor 形成
        //   每 15~20 秒一行的噪音，把真正有用的日志全埋了（实测 gateway.log 里一半是这句）。
        if (wasActive) logLine(ctx, "[anchor] 守候通道已关闭（原为开启）");
    }

    private static String readFile1(java.io.File f) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String s = r.readLine();
            return s == null ? "" : s;
        } catch (Exception e) { return ""; }
    }

    /** 系统省电模式是否开启 */
    public static boolean powerSave(Context ctx) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isPowerSaveMode();
        } catch (Exception e) {
            return false;
        }
    }

    /** 通知是否被允许（前台服务通知不可见时，鸿蒙会很快回收该进程） */
    public static boolean notifEnabled(Context ctx) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                return nm != null && nm.areNotificationsEnabled();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }



    /** 是否已被系统排除在电池优化之外（保活的前提） */
    public static boolean isIgnoringBattery(Context ctx) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- 系统绑定锚点（通知使用权 = NotificationListenerService） ----------

    /**
     * 用户是否已授予"通知使用权"（= 系统会绑着我们的 GatewayAnchorService）。
     * 这是普通 App 无需厂商合作就能拿到的最高一档存活待遇，详见 GatewayAnchorService 注释。
     */
    public static boolean anchorEnabled(Context ctx) {
        try {
            String flat = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), "enabled_notification_listeners");
            if (flat == null) return false;
            // 系统串格式：pkg/cls:pkg/cls（可能带 userId/ 前缀）
            return flat.contains(ctx.getPackageName() + "/");
        } catch (Exception e) {
            return false;
        }
    }

    /** 跳到"通知使用权"授权页（ROM 差异大，跳不过就退到应用详情） */
    public static void openAnchorSettings(Context ctx) {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            openAppDetail(ctx);
        }
    }

    // ---------- 无障碍锚点（可选第四档） ----------

    /**
     * 用户是否已在系统「无障碍」里开本 App 的锚点。
     *
     * 判据只能用 Settings.Secure 里那条扁平串（与通知使用权同一套路），
     * 没有任何 API 能"查询某个 AccessibilityService 是否已启用"——别去找，没有。
     * 系统串格式：pkg/cls:pkg/cls（可能带 userId/ 前缀）。
     */
    public static boolean a11yEnabled(Context ctx) {
        try {
            String flat = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), "enabled_accessibility_services");
            if (flat == null) return false;
            return flat.contains(ctx.getPackageName() + "/");
        } catch (Exception e) {
            return false;
        }
    }

    /** 跳「无障碍」设置页。❗ 这个页面不能直达某个 App 项，系统只提供总列表。 */
    public static void openA11ySettings(Context ctx) {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            openAppDetail(ctx);
        }
    }


    private static void openAppDetail(Context ctx) {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {}
    }

    // ---------- 心跳与诊断 ----------

    /** 每次看门狗/服务存活都刷新心跳时间戳，用于判断"进程最后一次活着是什么时候" */
    public static void beat(Context ctx) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(new java.io.File(ctx.getFilesDir(), "heartbeat.txt"), false);
            w.write(String.valueOf(System.currentTimeMillis()));
            w.close();
        } catch (Exception ignored) {}
    }

    /** 距今多少秒没有心跳；-1 = 从没有过 */
    public static long heartbeatAgeSec(Context ctx) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "heartbeat.txt");
            if (!f.exists()) return -1;
            BufferedReader r = new BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f)));
            String s = r.readLine();
            r.close();
            long t = Long.parseLong(s.trim());
            return (System.currentTimeMillis() - t) / 1000;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 汇总一份可发的诊断报告：设备/ROM、保活状态、心跳、日志尾 */
    public static String diagReport(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("== 10Router 安卓诊断 ==\n");
        sb.append("时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())).append('\n');
        sb.append("厂商/机型: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append('\n');
        sb.append("Android: ").append(android.os.Build.VERSION.RELEASE).append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("ROM: ").append(android.os.Build.DISPLAY).append('\n');
        long age = heartbeatAgeSec(ctx);
        sb.append("上次心跳: ").append(age < 0 ? "无记录" : (age + " 秒前")).append('\n');
        sb.append("电池优化豁免: ").append(isIgnoringBattery(ctx) ? "已豁免" : "未豁免 ← 头号被杀原因").append('\n');
        sb.append("网关保活(通知使用权): ").append(anchorEnabled(ctx) ? "已开启（系统持有连接）" : "未开启 ← 息屏后被清的主因").append('\n');
        sb.append("无障碍锚点: ").append(a11yEnabled(ctx)
                ? "已开启（系统级绑定，不占 VPN 通道，不读屏幕）"
                : "未开启（可选第四档；开了能和 VPN 锚拿同等待遇）").append('\n');
        sb.append("进程启动次数: ").append(bootCountOf(ctx))
          .append("（>1 = 主进程被系统清掉过；被清后由 START_STICKY / 闹钟 / 守卫进程拉回）\n");
        long[] fz = freezeStats(ctx);
        // 口径必须写清楚，否则数字会被误读：这个计数是看门狗「一觉睡超 60 秒」的次数。
        //   睡超 60 秒 = 这段时间 CPU 没给我们，也就是被冻结 —— 但同一个冻结窗口里，
        //   看门狗的多次未完成睡眠会各记一次；所以它是「冻结窗口数」的上界，不是精确值。
        //   它只回答一个问题：这台机器上「冻结」到底有多频繁。不必去优化这个数字本身。
        sb.append("被冻结次数: ").append(fz[0]);
        if (fz[0] > 0) {
            sb.append("，最长一次 ").append(fz[1] / 60).append(" 分钟");
            if (fz[2] > 0) {
                long minAgo = (System.currentTimeMillis() - fz[2]) / 60000L;
                sb.append("，最近一次 ").append(minAgo).append(" 分钟前");
            }
        }
        sb.append(fz[0] == 0 ? "（未发生）" : "（按看门狗睡超 60s 计；同一窗口可能计多次，看趋势不看绝对值）").append('\n');
        sb.append("闹钟类型: ").append(lastAlarmMode)
          .append(isAggressiveRom() ? "（已按华为/鸿蒙规则用 AlarmClock）" : "").append('\n');
        sb.append("守候模式: ").append(anchorMode(ctx)).append('\n');
        sb.append("保活锁: CPU=").append(GatewayService.cpuLockHeld ? "持有（不睡）" : "无（服务没跑？）")
          .append(" · 无线电=").append(GatewayService.wifiLockHeld ? "全速（息屏不降载）" : "省电 ← 息屏后别的设备连不上的常见真凶").append('\n');
        sb.append("服务器常亮: ").append(keepScreenOn(ctx) ? "开（仅前台/插电时；非保活手段，锁屏即失效）" : "关").append('\n');
        sb.append("当前锚点: ").append(VpnKeepAliveService.active ? "VPN（占一条 VPN 通道）" : "无 ← 靠系统绑定锚 + AlarmClock + JobScheduler 兜着").append('\n');
        sb.append("锚点原因: ").append(anchorState(ctx).replace('|', ' ')).append('\n');
        sb.append("外部 VPN 占通道: ").append(externalVpn(ctx) ? "是（" + externalVpnName(ctx) + " 在用，VPN 锚已让位）" : "否").append('\n');
        sb.append("VPN 网络明细:").append(vpnNetworksDump(ctx)).append('\n');
        sb.append("通知权限: ").append(notifEnabled(ctx) ? "已允许" : "未允许 ← 前台服务通知不可见会被快速回收").append('\n');
        sb.append("省电模式: ").append(powerSave(ctx) ? "开启中 ← 更容易被冻结" : "关闭").append('\n');
        boolean up = isUp();
        long age2 = heartbeatAgeSec(ctx);
        // 刚起来 1 秒就报"不可服务"是冤枉 node：Next.js 冷启动要 5s 左右
        sb.append("网关健康: ").append(up ? "可服务"
                : (age2 >= 0 && age2 < 20 ? "启动中（进程刚起 " + age2 + "s）" : "不可服务")).append('\n');
        try {
            sb.append("App 版本: ").append(ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName).append('\n');
        } catch (Exception ignored) {}
        // 额度管家单独一份日志：它起不来时，这一节就是唯一线索
        try {
            java.io.File cdf = new java.io.File(ctx.getFilesDir(), "cd.log");
            if (cdf.exists()) {
                sb.append("\n== cd.log 尾部（额度管家） ==\n").append(tailFile(cdf, 40)).append('\n');
            } else {
                sb.append("\n== cd.log == （不存在：额度管家从未启动过）\n");
            }
        } catch (Exception ignored) {}
        sb.append("\n== gateway.log 尾部 ==\n").append(tailLog(ctx, 120)).append('\n');
        return sb.toString();
    }

    /** 一键导出：把诊断报告交给系统分享面板（可发到飞书/微信/文件） */
    public static void shareReport(Context ctx) {
        try {
            String report = diagReport(ctx);
            java.io.File out = new java.io.File(ctx.getCacheDir(), "10router-diag.txt");
            java.io.FileWriter w = new java.io.FileWriter(out, false);
            w.write(report);
            w.close();
            android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(android.content.Intent.EXTRA_SUBJECT, "10Router 诊断");
            send.putExtra(android.content.Intent.EXTRA_TEXT, report);
            send.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(android.content.Intent.createChooser(send, "导出诊断").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            logLine(ctx, "[diag] share 失败: " + e);
        }
    }

    /**
     * 国产 ROM 的自启动/后台管理页，能直接跳就跳，跳不过去退回应用详情。
     *
     * 这是"不占 VPN 通道"的保活里最关键的一环：电池优化豁免只管 AOSP 那套，
     * 华为/小米/OPPO/vivo 各有自己一套且**默认全关**；系统绑定锚只能拿到 binder 连接，
     * 管不了开机自启和后台清理白名单。
     *
     * 各家 Activity 名字经常改、还常被删，所以逐个试、失败退到应用详情页，
     * 并把「手动怎么走」返回给界面 —— 不让用户点了一个没反应的按钮就没下文。
     *
     * @return 该机型的手动操作路径（界面直接展示）
     */
    public static String openAutostartSettings(Context ctx) {
        // ❗顺序即优先级，第一行必须是「这台机器上真的存在的那个」。
        //
        // 华为 ANA-AN00（HarmonyOS 4.2 / EMUI 12）实测：resolveActivity 对
        // com.huawei.systemmanager.startupmgr.* 三处都返回非 null，但点进去是**空列表**——
        // 自启动授权其实住在「设置 → 电池 → 启动管理」，由 com.hihonor.systemmanager
        // 或 com.huawei.systemmanager 的 .startupmgr.ui.StartupNormalAppListActivity 承载，
        // 而「应用管理」里根本没有这一项。这就是用户说的"跳到应用管理里找不到自启动"。
        // 所以华为系先试「启动管理」，再试其余。
        String mf = (android.os.Build.MANUFACTURER + " " + android.os.Build.BRAND).toLowerCase();
        boolean hw = mf.contains("huawei") || mf.contains("honor") || mf.contains("harmony");
        String[][] targets = {
                // 华为 / 荣耀：启动管理（真入口）
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupAppListActivity"},
                {"com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.honor.systemmanager", "com.honor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                // 小米 / 红米
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                // OPPO / 一加 / realme
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                // vivo / iQOO
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
                // 魅族 / 三星 / 乐视
                {"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
                {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
                {"com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"},
        };
        for (String[] t : targets) {
            try {
                android.content.Intent i = new android.content.Intent();
                i.setComponent(new android.content.ComponentName(t[0], t[1]));
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                // ❗必须先 resolveActivity 再 startActivity。
                // 原来只靠 try/catch：Activity 不存在时很多 ROM 会**静默失败**，
                // 于是循环里第一个 target 无论能不能用都会被执行一次，日志还写着"已打开"——
                // 用户看到的就是"跳到了一个不相干的页面"。
                if (ctx.getPackageManager().resolveActivity(i, 0) == null) continue;
                ctx.startActivity(i);
                logLine(ctx, "[anchor] 已打开自启动授权页: " + t[0] + "/" + t[1]);
                if (hw) {
                    logLine(ctx, "[anchor] 华为/荣耀：若这一页是「应用启动管理」列表，把 10Router 的"
                            + "「自动管理」关掉，三个开关（自启动/关联启动/后台活动）全开");
                }
                return manualPath();
            } catch (Exception ignored) { /* 这家没这个 Activity，试下一个 */ }
        }
        // 兜底 1（关键修正）：直接拉起厂商「手机管家 / 安全中心」App 主页。
        // 这是 getActivity/XXPermissions（千万级权限框架）在华为/小米/OPPO/vivo 上验证过的做法：
        // 这些厂商的后台/自启动页没有稳定公开的组件名，新系统版本甚至会拦截第三方直接拉起
        // systemmanager 组件（SecurityException，见 golyakoff/ago-root#1893 真机复盘）。
        // 所以宁可打开管家主页让用户自己点进去，也**绝不**退化到「电池优化」页——
        // 那跟自启动是两回事，退化过去就是用户看到的「点自启动却进了电池优化界面」。
        String[] managers = vendorManagers(mf);
        for (String pkg : managers) {
            try {
                android.content.Intent li = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (li != null) {
                    li.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(li);
                    logLine(ctx, "[anchor] 自启动无直链，已打开厂商管家主页(" + pkg + ")；手动路径: " + manualPath());
                    return manualPath();
                }
            } catch (Exception ignored) {}
        }
        // 兜底 2：应用详情页。在**所有** ROM 上都存在、且一定和本应用相关，
        // 比"跳到某个不相干的系统页"强——用户至少知道自己站在哪、该找什么。
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            logLine(ctx, "[anchor] 没有可用的自启动直链，已打开本应用详情页；手动路径: " + manualPath());
        } catch (Exception ignored) {}
        return manualPath();
    }

    /**
     * 各厂商「手机管家 / 安全中心」包名（与自启动/后台管理页同 App），用于兜底拉起主页。
     * 来源交叉确认：getActivity/XXPermissions PermissionSettingPage 的
     * HUA_WEI_MOBILE_MANAGER / XIAO_MI_MOBILE_MANAGER / OPPO_SAFE_CENTER /
     * VIVO_MOBILE_MANAGER 常量，加 OPPO 开放平台与 iQOO/vivo 实测包名。
     */
    private static String[] vendorManagers(String mf) {
        if (mf.contains("huawei") || mf.contains("honor") || mf.contains("harmony"))
            return new String[]{"com.huawei.systemmanager","com.hihonor.systemmanager","com.honor.systemmanager"};
        if (mf.contains("xiaomi") || mf.contains("redmi") || mf.contains("poco"))
            return new String[]{"com.miui.securitycenter"};
        if (mf.contains("oppo") || mf.contains("realme") || mf.contains("oneplus"))
            return new String[]{"com.coloros.safecenter","com.oplus.safecenter","com.color.safecenter","com.oppo.safe"};
        if (mf.contains("vivo") || mf.contains("iqoo"))
            return new String[]{"com.iqoo.secure","com.vivo.permissionmanager","com.bairenkeji.icaller"};
        if (mf.contains("samsung"))
            return new String[]{"com.samsung.android.lool","com.samsung.android.sm"};
        if (mf.contains("meizu"))
            return new String[]{"com.meizu.safe"};
        // 未知厂商：把常见管家都试一遍，谁装了拉谁。
        return new String[]{"com.huawei.systemmanager","com.miui.securitycenter","com.coloros.safecenter","com.iqoo.secure","com.samsung.android.lool"};
    }

    /** 按本机厂商给出自启动的手动操作路径（跳转失败时这是唯一出路）。 */
    public static String manualPath() {
        String mf = (android.os.Build.MANUFACTURER + " " + android.os.Build.BRAND).toLowerCase();
        if (mf.contains("huawei") || mf.contains("honor") || mf.contains("harmony"))
            // ❗实测（ANA-AN00 / HarmonyOS 4.2）：自启动**不在**「应用管理」里，
            //    那里面翻遍了也没有"自启动"这一项。真入口在「设置 → 电池 → 启动管理」，
            //    或者负一屏/桌面下拉搜「应用启动管理」直接进。两条路都写出来，
            //    因为不同 EMUI/HarmonyOS 小版本入口位置不一样，只写一条会把人带错。
            return "设置 → 电池 → 启动管理 → 10Router → 关掉「自动管理」，"
                 + "自启动/关联启动/后台活动 三个开关全开（部分机型在「手机管家 → 应用启动管理」）";
        if (mf.contains("xiaomi") || mf.contains("redmi") || mf.contains("poco"))
            return "设置 → 应用设置 → 应用管理 → 10Router → 自启动打开；省电策略选「无限制」；最近任务里下拉卡片加锁";
        if (mf.contains("oppo") || mf.contains("realme") || mf.contains("oneplus"))
            return "设置 → 电池 → 应用耗电管理 → 10Router → 允许后台运行、自启动、关联启动";
        if (mf.contains("vivo") || mf.contains("iqoo"))
            return "i管家 → 应用管理 → 权限管理 → 自启动 → 10Router 打开；后台高耗电选「允许」";
        if (mf.contains("samsung"))
            return "设置 → 电池 → 后台使用限制 → 把 10Router 加进「从不休眠的应用」";
        return "设置 → 应用 → 10Router → 电池 → 选「不受限制」";
    }

    private static PendingIntent pingIntent(Context ctx) {
        Intent i = new Intent(ctx, HealthReceiver.class).setAction(ACTION_PING);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, 0x10A, i, flags);
    }

    /** 网络恢复时也拉一次：换 Wi-Fi/流量后网关常有残留在坏 socket 上 */
    public static void watchNetwork(Context ctx) {
        // 回调只能注册一次：MainActivity 每次重建都注册一遍会累积，一次网络变化触发 N 次拉服务（实测风暴源之一）
        if (netCallbackRegistered) return;
        netCallbackRegistered = true;
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            final Context app = ctx.getApplicationContext();
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    logLine(app, "[keepalive] 网络可用 → ensureRunning");
                    ensureRunning(app);
                }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        logLine(app, "[keepalive] 网络已校验 → ensureRunning");
                        ensureRunning(app);
                    }
                }
            });
        } catch (Exception e) {
            netCallbackRegistered = false;
            logLine(ctx, "[keepalive] 注册网络回调失败: " + e);
        }
    }

    private static volatile boolean netCallbackRegistered = false;

    /**
     * 载荷指纹：直接取 APK 里 assets/<name> 的 CRC32 + 长度。
     *
     * 为什么不用人工维护的版本常量：老逻辑是 `.ready_v<BOOT_VERSION>`，常量得手工 +1，
     * 漏一次就是"用户装上新 APK，跑的还是旧载荷"——用户的问法很准："这个真的是更新了 10R 吗？"
     * 指纹随内容自动变：换了载荷就一定重解包，没换就一枚标记都不动。
     */
    public static String payloadFingerprint(Context ctx, String asset) {
        java.util.zip.ZipFile zf = null;
        try {
            zf = new java.util.zip.ZipFile(ctx.getPackageCodePath());
            java.util.zip.ZipEntry e = zf.getEntry("assets/" + asset);
            if (e != null) return Long.toHexString(e.getCrc()) + "-" + e.getSize();
        } catch (Throwable ignored) {
        } finally {
            try { if (zf != null) zf.close(); } catch (Throwable ignored) {}
        }
        return "unknown";
    }

    /**
     * payload 补丁标记自检：`.patchset.json` 是构建时写进 payload 的补丁标记。
     * 若它消失（被外部更新/覆盖），删掉首次解包标记 —— 下次打开 App 会从 APK 内置
     * assets 重新解包，自动恢复成我们打了补丁的版本。
     */
    public static void healPatchesIfNeeded(Context ctx) {
        try {
            File base = ctx.getFilesDir();
            File appDir = new File(base, "app");
            if (!appDir.exists()) return;                       // 还没解包过，交给首次解包
            if (new File(appDir, ".patchset.json").exists()) return;
            File[] markers = base.listFiles((d, n) -> n.startsWith(".ready_"));
            if (markers != null) for (File m : markers) m.delete();
            logLine(ctx, "[patch] payload 缺 .patchset.json —— 已置位重解包，下次打开 App 恢复补丁版");
        } catch (Exception ignored) {}
    }

    /** 每次"新进程启动"计数（持久化，跨进程死亡累加）——把"被系统反复杀"变成可数事实 */
    public static int bumpBootCount(Context ctx) {
        int n = 0;
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "boots.txt");
            if (f.exists()) {
                BufferedReader r = new BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f)));
                String s = r.readLine();
                r.close();
                if (s != null) n = Integer.parseInt(s.trim());
            }
            n++;
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write(String.valueOf(n));
            w.close();
        } catch (Exception ignored) {}
        return n;
    }

    /** 读启动次数（不递增） */
    public static int bootCountOf(Context ctx) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "boots.txt");
            if (!f.exists()) return 0;
            BufferedReader r = new BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f)));
            String s = r.readLine();
            r.close();
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 追加一行到 files/gateway.log（带毫秒时间戳，风暴节奏靠它才看得见） */
    public static void logLine(Context ctx, String line) {
        try {
            String ts = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(new java.util.Date());
            java.io.FileWriter w = new java.io.FileWriter(new java.io.File(ctx.getFilesDir(), "gateway.log"), true);
            w.write(ts + " " + line + "\n");
            w.close();
        } catch (Exception ignored) {}
    }

    // ===================== 彻底退出 =====================
    /**
     * 把这个 App 的全部后台活动停掉，并且**让系统别再自动拉起**。
     *
     * 为什么需要它：这个 App 天生要"赖着不走"——前台服务 + START_STICKY + AlarmClock(15s)
     * + JobScheduler + 独立守卫进程 + 可选 VPN。任何一项单独关掉，其它几项都会把它拉回来。
     * 所以"关掉 VPN 就好"这种思路不成立，必须有一个**一次拆干净**的入口。
     *
     * 关掉的顺序是有讲究的（写错了会自己把自己拉回来）：
     *   1. 先落一个「用户要求停止」的标记 —— 之后所有自愈路径看到它就闭嘴。
     *      没有这一步，停服务 -> START_STICKY 拉起 -> 又活，用户会以为按钮没用。
     *   2. 撤掉闹钟与 Job —— 它们会在几秒到 15 分钟内把一切都重建。
     *   3. 停 VPN 隧道（先关 tun，用户状态栏里的钥匙图标立刻消失）。
     *   4. 停守卫进程、停网关服务（onDestroy 会结束 node 与额度管家两个子进程）。
     *   5. 最后清孤儿：万一上个进程被系统杀掉、node 的父进程变成 1，它不会被 4 带走。
     */
    public static void shutdownAll(Context ctx) {
        logLine(ctx, "[quit] 用户要求彻底退出 → 开始拆除");

        // 1. 意志标记：先立规矩，再动手
        setFlag(ctx, "user_stopped", true);

        // 2. 闹钟 + Job（这两个是"复活"的主力）
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pingIntent(ctx));
            logLine(ctx, "[quit] 心跳闹钟已撤");
        } catch (Throwable t) { logLine(ctx, "[quit] 撤闹钟失败: " + t); }
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(JOB_ID);
            logLine(ctx, "[quit] 看门狗 Job 已撤");
        } catch (Throwable t) { logLine(ctx, "[quit] 撤 Job 失败: " + t); }

        // 3. VPN 隧道：先关 tun（状态栏钥匙立刻消失），再停服务
        VpnKeepAliveService.forceCloseTun();
        try { ctx.stopService(new Intent(ctx, VpnKeepAliveService.class)); } catch (Throwable ignored) {}

        // 4. 守卫进程 + 网关服务
        try { ctx.stopService(new Intent(ctx, GuardService.class)); } catch (Throwable ignored) {}
        try { ctx.stopService(new Intent(ctx, GatewayService.class)); } catch (Throwable ignored) {}
        try { ctx.stopService(new Intent(ctx, TouchBlockService.class)); } catch (Throwable ignored) {}

        // 5. 清孤儿 node（父进程已死时，停服务不会带走它）
        killStrayProcesses(ctx);

        logLine(ctx, "[quit] 拆除完成（要恢复请回 App 或重启手机）");
    }

    /**
     * 杀掉残留的 node / cloudflared 子进程。
     *
     * 为什么必须单独做：Android 上 stopService 只结束 Java 服务，ProcessBuilder 起的
     * 子进程如果父进程已经不是我们（App 曾被系统杀过、node 变成了 init 的孤儿），
     * 它会继续占着 20128/47860，用户回到 App 也起不来新实例。
     * 这里用 `ps -A -o PID,PPID,NAME` 找 node，只杀「父进程不是我们」的那些。
     */
    private static void killStrayProcesses(Context ctx) {
        int myPid = android.os.Process.myPid();
        try {
            java.lang.Process ps = new ProcessBuilder("/system/bin/sh", "-c",
                    "ps -A -o PID,PPID,NAME 2>/dev/null || ps -A 2>/dev/null").start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(ps.getInputStream()));
            String line;
            int killed = 0;
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 3) continue;
                String name = f[f.length - 1];
                // 只认我们自己的二进制名，别误杀系统里别的 node（虽然安卓上基本没有）
                boolean ours = name.equals("node") || name.equals("creditdaddy.js")
                        || name.startsWith("cloudflared");
                if (!ours) continue;
                int pid, ppid;
                try { pid = Integer.parseInt(f[0]); ppid = Integer.parseInt(f[1]); } catch (Exception e) { continue; }
                if (pid <= 1 || pid == myPid) continue;
                try { android.os.Process.killProcess(pid); killed++; } catch (Throwable ignored) {}
                if (ppid == myPid) continue;   // 正常子进程，服务 onDestroy 已处理
            }
            r.close();
            if (killed > 0) logLine(ctx, "[quit] 清理残留子进程 " + killed + " 个");
        } catch (Throwable t) {
            logLine(ctx, "[quit] 清孤儿失败: " + t);
        }
    }

    /** 用户是否点过「彻底退出」（点过就不许自愈再把服务拉起来） */
    public static boolean userStopped(Context ctx) {
        return flag(ctx, "user_stopped");
    }

    /** 用户回到 App 并明确要启动时清掉该标记 */
    public static void clearUserStopped(Context ctx) {
        try { new java.io.File(ctx.getFilesDir(), "user_stopped").delete(); } catch (Throwable ignored) {}
    }

    // ---------- 手机适配补丁开关 ----------

    /**
     * 适配补丁开关。默认**开**：用户在设置里明确要求"把页面问题修掉"，所以修复默认生效
     * （已过 21 用例回归门禁：零回归、88 项改善）。关掉即完全按上游原样显示，随时可一键排除。
     */
    // ---------- 远程访问（局域网 / 虚拟网 / 公网隧道） ----------

    /** 开关标记（文件存在=开），与补丁/守护开关同一套路，简单可靠 */
    private static boolean flag(Context ctx, String name) {
        try { return new java.io.File(ctx.getFilesDir(), name).exists(); } catch (Exception e) { return false; }
    }
    private static void setFlag(Context ctx, String name, boolean on) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), name);
            if (on) f.createNewFile(); else f.delete();
        } catch (Exception ignored) {}
    }

    /** 网关是否监听所有网卡（0.0.0.0）。关=只允许手机自己访问（默认） */
    public static boolean bindAll(Context ctx) { return flag(ctx, "bind_all"); }
    public static void setBindAll(Context ctx, boolean on) { setFlag(ctx, "bind_all", on); }

    /** 是否开启公网隧道（cloudflared，仅暴露 /v1 接口） */
    public static boolean tunnelEnabled(Context ctx) { return flag(ctx, "tunnel_on"); }
    public static void setTunnelEnabled(Context ctx, boolean on) { setFlag(ctx, "tunnel_on", on); }

    /** 隧道当前公网地址（由 cloudflared 输出抓取后写入） */
    public static String tunnelUrl(Context ctx) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "tunnel.url");
            if (!f.exists()) return "";
            BufferedReader r = new BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f)));
            String s = r.readLine();
            r.close();
            return s == null ? "" : s.trim();
        } catch (Exception e) { return ""; }
    }

    /** 本机可被外部访问的地址。
     *  用户痛点：以前把 wlan/wg/docker 的 IP 全部逗号堆一行，"到底复制哪个"没法选。
     *  现在只选一个最兜底的局域网 IPv4：物理网卡优先（wlan/eth），VPN 虚拟网卡一律不算
     *  （wg 的 10.8.0.x、tailscale 的 100.x 都不是家庭 Wi-Fi 里别人能直达的地址），
     *  同优先级里 192.168（家用路由）> 10 > 172。虚拟网段仍单独返回，给诊断用。 */
    public static String lanAddresses() {
        String best = ""; int bestScore = -1;
        StringBuilder vpn = new StringBuilder();
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis = java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String nm = ni.getName() == null ? "" : ni.getName().toLowerCase(java.util.Locale.US);
                boolean vpnIface = nm.startsWith("wg") || nm.startsWith("tun") || nm.startsWith("tap")
                        || nm.startsWith("utun") || nm.startsWith("ts") || nm.contains("tailscale")
                        || nm.startsWith("ppp");
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (!(a instanceof java.net.Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    // 100.64.0.0/10 是 Tailscale/CGNAT 段：说明装了官方 Tailscale，可异地私有访问
                    if (ip.startsWith("100.")) { if (vpn.length() > 0) vpn.append(','); vpn.append(ip); continue; }
                    if (vpnIface || ip.startsWith("169.254.")) continue;   // 虚拟网卡/无DHCP自签地址不当选
                    int p = ip.startsWith("192.168.") ? 2 : (ip.startsWith("10.") || ip.startsWith("172.")) ? 1 : -1;
                    if (p < 0) continue;
                    int phys = (nm.startsWith("wlan") || nm.startsWith("eth") || nm.startsWith("wifi") || nm.startsWith("rndis")) ? 4 : 0;
                    int score = phys + p;                                   // 物理口 > 虚拟口；家用段 > 其他
                    if (score > bestScore) { bestScore = score; best = ip; }
                }
            }
        } catch (Exception ignored) {}
        return best + "|" + vpn;
    }

    // ---------- 出站隧道载荷（cloudflared，独立小包） ----------

    static final String CFL_ENTRY = "cfl/bin/cloudflared";

    /** cloudflared 的独立下载地址（不塞进 APK：APK 少 9.6MB，只有开隧道的人才需要下） */
    static final String CFL_URL = "https://das-sein.top/10router/cfl.zip";

    /**
     * 解包 cloudflared：优先用随包资产（若存在），否则**按需下载**再解包。
     * 幂等、逐条容错、校验入口文件、失败重试。
     */
    public static synchronized boolean ensureCflPayload(Context ctx) {
        java.io.File base = ctx.getFilesDir();
        java.io.File entry = new java.io.File(base, CFL_ENTRY);
        if (entry.exists()) return true;

        java.io.File zip = new java.io.File(ctx.getCacheDir(), "cfl.zip");
        boolean haveZip = false;
        try {
            // 1) 随包资产（如果这一版把它打进去了）
            java.io.InputStream a = ctx.getAssets().open("cfl.zip");
            java.io.FileOutputStream os = new java.io.FileOutputStream(zip);
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = a.read(b)) > 0) os.write(b, 0, n);
            os.close();
            a.close();
            haveZip = zip.length() > 1_000_000;
        } catch (Exception noAsset) {
            haveZip = false;
        }
        if (!haveZip) {
            // 2) 按需下载（约 9.6MB，只在真正要用隧道时才发生）
            for (int i = 1; i <= 2 && !haveZip; i++) {
                try {
                    cdLog(ctx, "下载 cloudflared 第 " + i + " 次");
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(CFL_URL).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(120000);
                    if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                    java.io.InputStream in = c.getInputStream();
                    java.io.FileOutputStream os2 = new java.io.FileOutputStream(zip);
                    byte[] b2 = new byte[1 << 16];
                    int n2, total = 0;
                    while ((n2 = in.read(b2)) > 0) {
                        total += n2;
                        if (total > 64 * 1024 * 1024) throw new Exception("体积异常");
                        os2.write(b2, 0, n2);
                    }
                    os2.close();
                    in.close();
                    c.disconnect();
                    haveZip = zip.length() > 1_000_000;
                } catch (Exception de) {
                    cdLog(ctx, "下载失败: " + de);
                }
            }
        }
        if (!haveZip) { cdLog(ctx, "无法获得 cloudflared 载荷"); return false; }

        for (int attempt = 1; attempt <= 3 && !entry.exists(); attempt++) {
            try {
                java.io.InputStream in = new java.io.FileInputStream(zip);
                java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(in);
                java.util.zip.ZipEntry e;
                byte[] buf = new byte[1 << 16];
                while ((e = zin.getNextEntry()) != null) {
                    String name = e.getName();
                    if (name.startsWith("..") || name.startsWith("/")) { zin.closeEntry(); continue; }
                    java.io.File out = new java.io.File(base, name);
                    if (e.isDirectory()) { out.mkdirs(); zin.closeEntry(); continue; }
                    java.io.File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try {
                        java.io.OutputStream os = new java.io.FileOutputStream(out);
                        int n;
                        while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
                        os.close();
                        out.setExecutable(true, false);
                    } catch (Exception ignored) {}
                    zin.closeEntry();
                }
                zin.close();
                cdLog(ctx, "cloudflared 解包第 " + attempt + " 次：入口存在=" + entry.exists());
            } catch (Exception ex) {
                cdLog(ctx, "cloudflared 解包异常: " + ex);
            }
        }
        try { zip.delete(); } catch (Exception ignored) {}
        return entry.exists();
    }

    // ---------- 额度管家载荷（独立小包，按需解包） ----------

    /** 额度管家入口文件（相对 files/）。它存在 = 载荷可用。 */
    static final String CD_ENTRY = "cd/bin/creditdaddy.js";

    /** 额度管家自己的日志：出问题时必须留下痕迹（上一版它为空/不存在，导致完全无法定位） */
    public static void cdLog(Context ctx, String msg) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(new java.io.File(ctx.getFilesDir(), "cd.log"), true);
            String ts = new java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(new java.util.Date());
            w.write(ts + " [payload] " + msg + "\n");
            w.close();
        } catch (Exception ignored) {}
    }

    /**
     * 确保额度管家载荷就位。
     *
     * 为什么单独做：额度管家原来是塞在那个 5223 条目的大 payload 的**最末尾**，
     * 而大解包是单遍顺序写入 —— 手机上一旦中途被系统冻结/中断，pfx 与 app 已经写完
     * （所以网关正常），cd/ 却永远不会落地，表现为"额度管家永远打不开"，且看不出异常。
     * 现在它是独立小包 assets/cd.zip：按需解包、逐条容错、解完校验入口文件、失败重试。
     */
    public static synchronized boolean ensureCdPayload(Context ctx) {
        java.io.File base = ctx.getFilesDir();
        java.io.File entry = new java.io.File(base, CD_ENTRY);
        // 闸门按"载荷指纹"而不是"入口文件在不在"。
        // 旧逻辑是 entry.exists() 就 return —— 等于额度管家只能靠卸载重装升级：
        // 用户装上新 APK、10R 都换新了，额度面板还是几个月前那份（用户："你考虑过真实的更新情况吗"）。
        java.io.File fp = new java.io.File(base, ".ready_cd_" + payloadFingerprint(ctx, "cd.zip"));
        if (entry.exists() && fp.exists()) return true;
        for (int attempt = 1; attempt <= 3 && !fp.exists(); attempt++) {
            int failed = 0, total = 0;
            try {
                java.io.InputStream in = ctx.getAssets().open("cd.zip");
                java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(in);
                java.util.zip.ZipEntry e;
                byte[] buf = new byte[1 << 16];
                while ((e = zin.getNextEntry()) != null) {
                    String name = e.getName();
                    if (name.startsWith("..") || name.startsWith("/")) { zin.closeEntry(); continue; }
                    java.io.File out = new java.io.File(base, name);
                    if (e.isDirectory()) { out.mkdirs(); zin.closeEntry(); continue; }
                    java.io.File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    total++;
                    try {
                        java.io.OutputStream os = new java.io.FileOutputStream(out);
                        int n;
                        while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
                        os.close();
                    } catch (Exception we) {
                        failed++;   // 单个文件失败不能放弃整包
                    }
                    zin.closeEntry();
                }
                zin.close();
                cdLog(ctx, "解包第 " + attempt + " 次：共 " + total + " 项，失败 " + failed
                        + "，入口存在=" + entry.exists());
                // 一枚都不能失败才落标记：落早了等于把"解了一半"的载荷当成品，永远不再补
                if (entry.exists() && failed == 0) fp.createNewFile();
            } catch (Exception ex) {
                cdLog(ctx, "解包第 " + attempt + " 次异常: " + ex);
            }
        }
        return entry.exists();
    }

    /** 载荷自检报告：给"面板打不开"页直接显示，不靠猜 */
    public static String payloadReport(Context ctx) {
        java.io.File base = ctx.getFilesDir();
        StringBuilder sb = new StringBuilder();
        sb.append("node 运行时: ").append(new java.io.File(base, "pfx/usr/bin/node").exists() ? "有" : "缺失").append('\n');
        sb.append("网关载荷: ").append(new java.io.File(base, "app/custom-server.js").exists() ? "有" : "缺失").append('\n');
        sb.append("额度管家载荷: ").append(new java.io.File(base, CD_ENTRY).exists() ? "有" : "缺失").append('\n');
        try {
            java.io.File cdf = new java.io.File(base, "cd.log");
            sb.append("cd.log: ").append(cdf.exists() ? (cdf.length() + " 字节") : "不存在").append('\n');
        } catch (Exception ignored) {}
        return sb.toString();
    }

    /** 读任意文件尾部（诊断用） */
    private static String tailFile(java.io.File f, int lines) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f)));
            java.util.ArrayDeque<String> dq = new java.util.ArrayDeque<>();
            String line;
            while ((line = r.readLine()) != null) { dq.addLast(line); if (dq.size() > lines) dq.pollFirst(); }
            r.close();
            StringBuilder sb = new StringBuilder();
            for (String l : dq) sb.append(l).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "(读日志失败 " + e + ")";
        }
    }

    /** 读任意日志文件尾部（公开版，隧道捕获公网地址时用） */
    public static String tailLogFile(java.io.File f, int lines) {
        return tailFile(f, lines);
    }

    /** 读 gateway.log 尾部（诊断用） */
    public static String tailLog(Context ctx, int lines) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "gateway.log");
            if (!f.exists()) return "(无日志)";
            BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f)));
            java.util.ArrayDeque<String> dq = new java.util.ArrayDeque<>();
            String line;
            while ((line = r.readLine()) != null) { dq.addLast(line); if (dq.size() > lines) dq.pollFirst(); }
            r.close();
            StringBuilder sb = new StringBuilder();
            for (String l : dq) sb.append(l).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "(读日志失败 " + e + ")";
        }
    }

    /* ===== 更新清单下载（原 PanelPatch 里的网络工具，补丁注入删掉后挪到这里） ===== */

    /**
     * 更新清单的 GitHub 加速镜像。
     * ❗必须用精确版本号，不能用 `latest`：ghfast 不解析 releases/download/latest/ 这种标签，
     * 恒返回 404（实测多次、跨数小时一致）。而 `latest` 恰好是"永远不会变"的写法，
     * 所以这个 404 在用户侧表现为"更新按钮永远检查失败"。
     * 发新版时这里要跟着改 tag —— build-android.sh 会断言它与 versionName 一致。
     */
    static final String MIRROR_URL = "https://ghfast.top/https://github.com/dassein-afk/10router-mobile/releases/download/v0.0.10/";

    /** 版本清单镜像地址 */
    static final String MIRROR_MANIFEST = MIRROR_URL + "version.json";

    /** 抓取文本，超过 limit 字节即失败（防被错误页/大文件撑爆内存） */
    static String fetch(String url, int limit) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        // 更新检查是"按钮点下去要立刻有反应"的场景：清单只有 200 字节，8s/10s 足够。
        // 原来 8s/15s 乘上"三级来源 × 每次可能重试"会让用户盯着"正在检查"一分多钟。
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        c.setInstanceFollowRedirects(true);
        try {
            int rc = c.getResponseCode();
            if (rc != 200) throw new Exception("HTTP " + rc);
            java.io.InputStream in = c.getInputStream();
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > limit) throw new Exception("内容过大");
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            in.close();
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }
}
