package top.dassein.tenrouter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机（含部分厂商的快速开机）后把网关拉起来，并注册心跳与守护任务。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent i) {
        String a = i.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(a);
        if (!boot) return;
        Intent svc = new Intent(ctx, GatewayService.class);
        try {
            // Android 8+ 后台禁止 startService（抛 IllegalStateException 且被吞掉 = 开机后网关永不启动）
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
            else ctx.startService(svc);
        } catch (Exception ignored) {}
        KeepAlive.scheduleJob(ctx);
        KeepAlive.schedulePing(ctx);
        KeepAlive.watchNetwork(ctx);
        // 开机后按"守候模式"裁决该挂哪档锚：VPN 通道空闲挂 VPN 锚，被梯子/Tailscale 占了
        // 就挂音频锚——绝不无脑拉 VPN，免得开机第一件事是抢掉用户的梯子。
        if (!KeepAlive.anchorMode(ctx).equals("off")) {
            try {
                KeepAlive.evaluateAnchor(ctx, true);
                KeepAlive.logLine(ctx, "[anchor] 开机恢复守候锚点（模式 " + KeepAlive.anchorMode(ctx) + "）");
            } catch (Exception e) {
                KeepAlive.logLine(ctx, "[anchor] 开机恢复失败: " + e);
            }
        }
    }
}
