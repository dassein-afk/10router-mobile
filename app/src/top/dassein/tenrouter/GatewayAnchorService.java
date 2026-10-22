package top.dassein.tenrouter;

import android.content.ComponentName;
import android.os.Build;
import android.service.notification.NotificationListenerService;

/**
 * 系统绑定锚点（"系统加固"开关）。
 *
 * 为什么需要它：普通前台服务在国产 ROM 里只能拿"普通进程"待遇，息屏一段时间就被冻结/清掉。
 * 系统只对**系统绑定的组件**给高优先级待遇，而普通 App 无需任何厂商合作就能拿到的这类组件只有三种：
 *   1. NotificationListenerService（通知使用权）—— 本类
 *   2. VpnService（会独占 VPN 通道，和手机上的 WireGuard 冲突）
 *   3. AccessibilityService（权限过重）
 *
 * 导航/接单类应用看似"保活更牛"，实质是它们拿到了我们在代码里拿不到的两样东西：
 * 厂商白名单（商务合作）+ 系统推送通道（厂商 Push 由系统进程唤醒应用）。那是渠道特权，不是代码技巧。
 * 本类是我们能自己拿到的等价物：被 system_server 持有一条 binder 连接，进程被回收后系统会重连，
 * 从而把这些应用长期维持在"可见/可感知"优先级，而不是可随时回收的后台进程。
 *
 * 隐私边界：NotificationListenerService 在被授权后技术上具备读取通知的能力，
 * 但本类**不实现任何读取回调**（没有 onNotificationPosted/onNotificationRemoved），
 * 不获取、不存储、不上传任何通知内容。它只做一件事：被系统绑着，并在连接时确认网关还活着。
 */
public class GatewayAnchorService extends NotificationListenerService {

    @Override
    public void onCreate() {
        super.onCreate();
        KeepAlive.logLine(this, "[anchor] 系统绑定锚点已创建");
        KeepAlive.beat(this);
        KeepAlive.ensureRunning(this);
    }

    /** 系统连上我们了（首次授权、或进程被回收后重连）——两条保活路径都走一遍 */
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        KeepAlive.logLine(this, "[anchor] 系统已连接锚点 → 拉网关 + 重排闹钟/Job");
        KeepAlive.beat(this);
        KeepAlive.ensureRunning(this);
        KeepAlive.schedulePing(this);
        KeepAlive.scheduleJob(this);
    }

    /** 系统解绑（用户关了开关 / ROM 主动断）——尽量请求重连，别静默掉线 */
    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        KeepAlive.logLine(this, "[anchor] 系统解绑锚点 → 请求重连");
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                requestRebind(new ComponentName(this, GatewayAnchorService.class));
            }
        } catch (Throwable ignored) {
        }
        KeepAlive.schedulePing(this);
    }
}
