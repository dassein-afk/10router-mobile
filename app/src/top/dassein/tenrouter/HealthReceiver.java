package top.dassein.tenrouter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** AlarmManager 心跳接收器：网关不在服务就立刻拉起；精确闹钟是一次性的，这里负责续订。 */
public class HealthReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent i) {
        // 留痕：闹钟到底有没有被系统投递，是判断"冻结/被杀"类问题的唯一硬证据
        // （v1.6.1 的教训：冻结 75 分钟里一条 PING 都没有 = 闹钟被 ROM 吞了）
        KeepAlive.logLine(ctx, "[keepalive] PING 闹钟已投递");
        KeepAlive.beat(ctx);
        KeepAlive.ensureRunning(ctx);
        KeepAlive.evaluateAnchor(ctx); // 闹钟拍 = 锚点互切最可靠的节拍
        KeepAlive.schedulePing(ctx);   // 一次性闹钟需要自己续订
    }
}
