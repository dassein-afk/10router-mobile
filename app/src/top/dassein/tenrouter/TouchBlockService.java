package top.dassein.tenrouter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * 触摸屏蔽：全屏透明覆盖层吸收所有 MotionEvent（点击/滑动/长按全部吃掉）。
 *
 * 解决「屏幕看不见但触控层仍生效 → 放口袋误触」的痛点：
 *   亮度0 只关背光，电容触控层照样工作（StackOverflow 权威确认）；
 *   本服务用 TYPE_APPLICATION_OVERLAY 全屏覆盖层 + OnTouchListener 返回 true 吃掉所有事件。
 *
 * ⚠️ 致命问题：系统级覆盖层吃掉所有触摸后，用户点不到任何按钮 → 被锁死在手机里。
 *    必须有**退出机制**：① 长按 3 秒退出（本服务）② 通知栏「关闭」按钮 ③ 不自动恢复。
 *
 * 需要 SYSTEM_ALERT_WINDOW 权限（Settings.canDrawOverlays 判断，无则引导用户开）。
 * 覆盖层类型：API 26+ 用 TYPE_APPLICATION_OVERLAY，低版本回退 TYPE_PHONE。
 * 必须是前台服务（Android 8+ 后台服务限制），否则覆盖层会被系统回收。
 *
 * 能拦：应用区所有触摸（点击/滑动/滚动/长按）。
 * 拦不住：系统手势（返回/Home/通知栏）——那需要无障碍服务或系统「防误触保护」，
 *        本服务不做，UI 已引导用户走系统设置。
 */
public class TouchBlockService extends Service {
    private static final String CH_ID = "touch_block";
    private static final int NOTIF_ID = 9;
    private static final long LONG_PRESS_MS = 3000;   // 长按 3 秒退出（与 TouchFree 一致）

    private WindowManager wm;
    private View overlay;
    private long downAt = 0;

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForeground(NOTIF_ID, buildNotification());
        addOverlay();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            KeepAlive.setTouchBlock(this, false);   // 清文件 + 停服务
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CH_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CH_ID, "触摸屏蔽", NotificationManager.IMPORTANCE_LOW));
            }
        }
        // 通知栏「关闭」按钮：覆盖层吃掉所有触摸时，用户仍能从通知栏关掉
        Intent stop = new Intent(this, TouchBlockService.class).setAction("stop");
        PendingIntent pi = PendingIntent.getService(this, 0, stop,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        b.addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(
                                this, android.R.drawable.ic_menu_close_clear_cancel),
                        "关闭触摸屏蔽", pi).build());
        return b.setContentTitle("触摸屏蔽中")
                .setContentText("禁止触摸中；长按屏幕 3 秒 或 点通知栏「关闭」解除")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void addOverlay() {
        if (wm == null) return;
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        overlay = new View(this);
        overlay.setBackgroundColor(0x00000000);   // 透明：防误触不需要看见内容
        // 关键：返回 true = 消费掉这个事件，不往下传 → 应用区所有触摸失效。
        // 同时用 ACTION_DOWN/UP 计时，长按 3 秒自动退出（防用户被锁死）。
        overlay.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) downAt = System.currentTimeMillis();
            else if (e.getAction() == MotionEvent.ACTION_UP) {
                if (System.currentTimeMillis() - downAt >= LONG_PRESS_MS) {
                    KeepAlive.setTouchBlock(this, false);
                    stopSelf();
                }
            }
            return true;
        });
        try { wm.addView(overlay, p); } catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        if (overlay != null) {
            try { wm.removeView(overlay); } catch (Exception ignored) {}
            overlay = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    static void start(Context ctx) {
        Intent i = new Intent(ctx, TouchBlockService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable ignored) {}
    }

    static void stop(Context ctx) {
        try { ctx.stopService(new Intent(ctx, TouchBlockService.class)); } catch (Throwable ignored) {}
    }
}
