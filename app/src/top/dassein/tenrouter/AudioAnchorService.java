package top.dassein.tenrouter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.IBinder;

/**
 * 音频守候锚（第 3.5 档）：不占 VPN 通道，专治"VPN 槽被梯子/Tailscale/WG 占用"时的后台冻结。
 *
 * 原理：进程持有活跃的 AudioTrack 播放流时，ROM 一般把进程归入"音频应用"待遇，
 * 冻结与 LMK 优先级都让路；前台服务通知再兜一层。与 VpnKeepAliveService（第 4 档）
 * 由 KeepAlive.evaluateAnchor() 自动互切：VPN 通道空闲用 VPN 锚，被占用退到本锚。
 *
 * 听不听得见？——听不见，这是硬约束（用户明确拒绝过白噪声）：
 *   频率 19.5kHz：成人听阈上限普遍 <17kHz，手机扬声器 18kHz 以上也基本滚降；
 *   幅度 24/32767（约 -62dBFS）：即使个别设备能泄漏高频，也在噪声底以下。
 * 系统只关心"有流在播"，波形随便选，所以挑一个物理上就听不见的。
 */
public class AudioAnchorService extends Service {

    public static final String ACTION_STOP = "top.dassein.tenrouter.AUDIO_STOP";

    /** 播放流是否真的活着（UI/诊断读这个，别猜） */
    public static volatile boolean active = false;
    private static volatile long startedAt = 0;

    private volatile boolean run = false;
    private AudioTrack track;
    private Thread worker;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            KeepAlive.logLine(this, "[audio] 收到停止指令，释放音频锚");
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureForeground();
        startTone();
        return START_STICKY;
    }

    private void ensureForeground() {
        try {
            final String CH = "anchor_audio";
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH, "10Router 音频守候",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int pf = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) pf |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(this, 0x5E, open, pf);
            Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CH) : new Notification.Builder(this);
            nb.setContentTitle("10Router 音频守候")
              .setContentText("听不见的锚点 · 不占 VPN 通道，梯子随便用")
              .setSmallIcon(android.R.drawable.presence_audio_online)
              .setContentIntent(pi).setOngoing(true).setShowWhen(false);
            startForeground(3, nb.build());
        } catch (Throwable t) {
            KeepAlive.logLine(this, "[audio] startForeground 失败: " + t);
        }
    }

    private void startTone() {
        if (run) return;
        try {
            final int RATE = 44100, FREQ = 19500, AMP = 24, N = 4096;
            short[] buf = new short[N];
            for (int i = 0; i < N; i++)
                buf[i] = (short) (AMP * Math.sin(2 * Math.PI * FREQ * i / RATE));
            AudioAttributes at = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            int minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf < N) minBuf = N * 2;
            track = new AudioTrack(at, fmt, minBuf, AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            track.play();
            run = true;
            worker = new Thread(() -> {
                int err = 0;
                while (run) {
                    int n;
                    try { n = track.write(buf, 0, N); } catch (Throwable t) { n = -1; }
                    if (n < 0) {
                        if (++err >= 50) {   // 设备被抢/禁用：如实放手，交给协调器下一拍重启
                            KeepAlive.logLine(null, "[audio] 播放流持续失败，音频锚退出等待重估");
                            active = false; run = false; release();
                            return;
                        }
                        try { Thread.sleep(40); } catch (InterruptedException e) { return; }
                        continue;
                    }
                    err = 0;
                }
                active = false;
            }, "audio-anchor");
            worker.start();
            active = true;
            startedAt = System.currentTimeMillis();
            KeepAlive.logLine(this, "[audio] 音频锚已建立（19.5kHz/-62dBFS：听不见，不占 VPN 通道）");
        } catch (Throwable t) {
            active = false;
            KeepAlive.logLine(this, "[audio] 建立失败: " + t);
        }
    }

    private void release() {
        try { if (track != null) { track.stop(); track.release(); } } catch (Throwable ignored) {}
        track = null;
    }

    @Override
    public void onDestroy() {
        KeepAlive.logLine(this, "[audio] onDestroy（音频锚释放）");
        run = false;
        if (worker != null) { try { worker.join(600); } catch (InterruptedException ignored) {} }
        release();
        active = false;
        KeepAlive.schedulePing(this);
        super.onDestroy();
    }

    public static long startedAt() { return startedAt; }
}
