package top.dassein.tenrouter;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍锚点（可选第四档，默认关闭，由用户在系统设置里手动授权）。
 *
 * 它和 VPN 锚是同一级别的东西，但不是同一种代价：
 *   VpnService  = 系统级网络组件，system_server 主动绑定/重启它，代价是**独占 VPN 通道**；
 *   AccessibilityService = 系统级辅助功能组件，同样由 system_server 绑定/重启，
 *                          且**不占 VPN 通道**、不接管任何流量。这就是它存在的理由：
 *                          "占用 VPN 的人"（开梯子/Tailscale/WireGuard）也能拿到系统级绑定待遇。
 *
 * 为什么它是可选而不是默认：
 *   用户在系统设置里看到的授权文案是"观察您的操作、读取屏幕内容"。技术上这类服务确实
 *   可以做到键盘记录器级别的事情，所以**必须用户自己决定**，并且必须在界面上把这句话
 *   原样写出来，不能藏在设置二级页里骗授权。本类**不实现任何读取行为**（见下方)。
 *
 * 隐私边界（与 GatewayAnchorService 同一套纪律）：
 *   - flags 里**不**申请 FLAG_RETRIEVE_INTERACTIVE_WINDOWS、不申请 canRetrieveWindowContent；
 *   - 不实现 onAccessibilityEvent 的任何读取逻辑（方法体只有一行日志级别的空转）；
 *   - 不调用 getRootInActiveWindow / getWindows / findAccessibilityNodeInfosByText；
 *   - 不缓存、不上传任何事件内容。
 *   系统绑定我们的目的是"提供服务"，本服务只利用"被绑定"这一事实抬高存活优先级。
 */
public class A11yAnchorService extends AccessibilityService {

    /**
     * ❗ 只申请"能收到事件"的能力，绝不申请读取窗口内容。
     * 用 accessibilityEventTypes 里最小的一组（TYPE_WINDOW_STATE_CHANGED 都不给，
     * 只留 TYPE_WINDOWS_CHANGED 都嫌多）——直接**一个事件类型都不订阅**：
     * setServiceInfo 里 eventTypes = 0 时系统仍会绑定服务，但不会推送任何事件内容。
     * 这样即使我们将来写错代码，也拿不到屏幕上的字。
     */
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.eventTypes = 0;                 // 不订阅任何事件
                info.feedbackType = 0;
                info.notificationTimeout = 0;
                info.flags = 0;                      // 明确不拿 INTERACTIVE_WINDOWS
                setServiceInfo(info);
            }
        } catch (Throwable t) {
            KeepAlive.logLine(this, "[a11y] 收紧服务配置失败（无害）: " + t);
        }
        KeepAlive.logLine(this, "[a11y] 无障碍锚点已连上系统（不订阅事件、不读屏幕）");
        KeepAlive.beat(this);
        KeepAlive.ensureRunning(this);
        KeepAlive.schedulePing(this);
        KeepAlive.scheduleJob(this);
        GuardLink.ensureGuard(this);
    }

    /** 空实现：我们不要事件，这里理应永不被调用。留着是为了不继承默认行为。 */
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        // 故意什么都不做。见类注释"隐私边界"。
    }

    @Override public void onInterrupt() { }
}
