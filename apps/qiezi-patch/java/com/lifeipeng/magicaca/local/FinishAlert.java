package com.lifeipeng.magicaca.local;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * 拍摄结束提醒：震动 + 响铃 + 通知，三件一起上。
 *
 * 背景（原版为什么不好用）：
 *   原包在收到 EMSG_TASK_BEGIN 时用 AlarmManager 按「预估时长」排一个闹钟，到点弹通知。
 *   但 addNotify() 里的算法把 Interval 当成了「曝光之后的空档」：
 *       temp = times * (cycleTime + duration) - cycleTime + delay
 *   而 Interval 实际是「周期」（上次开门→下次开门），所以选 60s×60、Interval 65s 时
 *   它排出 60*(65+60)-65 = 7435 秒 ≈ 2 小时 4 分，真实只要 65 分钟 —— 会晚一小时才响。
 *   而且它只在「预估结束」时响一次，中途断连、少拍几张，闹钟也不会跟着变。
 *
 * 这个类改成由硬件真实回报（EMSG_TASK_COMPLETE）触发，不靠猜。
 * 闹钟那条保留作兜底（公式在 ApplicationDelegate.addNotify 里一起修了），
 * BLE 万一掉线，还能有个备份。
 *
 * 为什么声音走 STREAM_ALARM：
 *   拍深空一般在荒郊夜里，手机常开勿扰、媒体音量也常被调成 0。
 *   闹钟流是勿扰模式默认放行的（用户得专门关「闹钟」例外），是唯一还能响的通道。
 *
 * 为什么用户自己按停止不响：
 *   toStop() 会先调 markManualStop()，10 秒内到达的「任务完成」直接静默 ——
 *   人就在手机旁边点的停止，再响一声只会吵到旁边的人。
 */
public final class FinishAlert {

    /** 通知渠道。API 26+ 必须有，否则系统按「杂项」归类，横幅/优先级都不受控 */
    private static final String CHANNEL_ID = "qiezi_task_done";
    private static final int NOTIFY_ID = 0x5157;

    /** 手动停止后多久内收到的「完成」不算数（硬件回得慢，给 15 秒） */
    private static final long MANUAL_STOP_WINDOW_MS = 15000L;

    /**
     * 同一轮任务只提醒一次。
     *
     * 1.6.12：有些固件在断开/收尾时会连发两条 EMSG_TASK_COMPLETE。
     * 原来 fire() 一进来就把 sManualStopAt 清成 0，于是第一条被静默、
     * 第二条（10 秒内）却照响 —— 人已经按停了，帐篷里还是响三声。
     * 现在把手动标记保留到自然过期，再加一道 60 秒去重，双保险。
     */
    private static final long FIRE_DEDUP_MS = 60000L;

    /** 手动停止的时刻（elapsedRealtime），0 表示没有 */
    private static volatile long sManualStopAt = 0L;

    /** 上次真正响过的时刻，用于去重 */
    private static volatile long sLastFireAt = 0L;

    /** 正在响的 ToneGenerator，再次触发时先释放，避免两路声音叠在一起 */
    private static ToneGenerator sTone;

    /**
     * 「刚刚真的收到硬件完成消息」的有效期。
     *
     * 为什么需要它：taskCompleteHandler() 不是纯完成回调，它同时被当成「复位界面」用 ——
     *   · FragmentDelayAdvance.onResume()：currentTaskLeftTime == 0 时调它
     *   · FragmentDelayAdvance.setFocusOut()：收起键盘时调它
     *   · MainActivity.menuStopCurrentTask()：菜单里「停止当前任务」时调它
     * 如果按「谁调我谁就是完成」来判断，进一次页面就响三声 + 震动，
     * 实测在模拟器上点 Skip 进 Bulb Time Lapse 页必响，通知栏立刻多一条。
     *
     * 所以改成：只有 ApplicationDelegate 收到 EMSG_TASK_COMPLETE 那条广播时，
     * 才在调 taskCompleteHandler 之前打这个标记；fire() 见到标记才响。
     * 有效期 5 秒是为了防残留 —— 如果当时不在 BTL 页，标记不会被消耗，
     * 5 秒后自动作废，不会等到下次进页面才突然响。
     */
    private static volatile long sRealCompleteUntil = 0L;

    /** 由 ApplicationDelegate 的 EMSG_TASK_COMPLETE 分支调用 */
    public static void markRealComplete() {
        sRealCompleteUntil = SystemClock.elapsedRealtime() + 5000L;
    }

    private FinishAlert() {
    }

    // ---------------------------------------------------------------- 对外接口

    /** 用户主动停止拍摄时调用，抑制紧随其后的结束提醒 */
    public static void markManualStop() {
        sManualStopAt = SystemClock.elapsedRealtime();
    }

    /**
     * 触发结束提醒。
     *
     * 调用点只有一个：FragmentDelayAdvance.taskCompleteHandler()。
     * 那个方法只有 Bulb Time Lapse 页有，所以这里不用再判断模式 ——
     * 单张 / 连拍不会走到这儿，不会出现「随手拍一张就响三声」。
     *
     * @param ctx 任意 Context（内部会取 applicationContext，不会泄漏 Activity）
     */
    public static void fire(Context ctx) {
        if (ctx == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();

        // 1.6.12：先确认这是「真的拍完了」，而不是 onResume / setFocusOut 的界面复位。
        // 这一条不加的话，每次进拍摄页都会响三声。
        if (now > sRealCompleteUntil) {
            android.util.Log.i("FinishAlert", "跳过：不是硬件完成回调（生命周期复位）");
            return;
        }
        sRealCompleteUntil = 0L;
        android.util.Log.i("FinishAlert", "触发结束提醒");

        long manualAt = sManualStopAt;
        if (manualAt != 0L && now - manualAt < MANUAL_STOP_WINDOW_MS) {
            // 用户刚自己按的停止，静默返回。
            // 注意别在这里把 sManualStopAt 清零 —— 清了第二条回调就会响。
            return;
        }
        if (sLastFireAt != 0L && now - sLastFireAt < FIRE_DEDUP_MS) {
            // 同一轮任务的重复上报，已经响过了
            return;
        }
        sLastFireAt = now;
        sManualStopAt = 0L;

        Context app;
        try {
            app = ctx.getApplicationContext();
        } catch (Throwable t) {
            app = ctx;
        }
        if (app == null) {
            return;
        }

        // 三件事各自 try 住：任何一件在怪机型上炸了，都不能带崩 BLE 回调链
        try {
            vibrate(app);
        } catch (Throwable ignored) {
        }
        try {
            beep(app);
        } catch (Throwable ignored) {
        }
        try {
            notify(app);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 震动

    private static void vibrate(Context app) {
        Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) {
            return; // 模拟器 / 平板常见，没有就算了
        }
        // 0.5s 震 - 0.25s 停 - 0.5s 震 - 0.25s 停 - 0.9s 震，约 2.4 秒
        long[] pattern = new long[]{0L, 500L, 250L, 500L, 250L, 900L};
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            v.vibrate(pattern, -1);
        }
    }

    // ---------------------------------------------------------------- 响铃

    private static void beep(Context app) {
        // 1.6.12：上一次的 ToneGenerator 可能还在响（三声要 3.3 秒），
        // 先掐掉再起新的，否则两路声音交错，听起来像卡带。
        releaseTone();
        // STREAM_ALARM：勿扰放行、不会被媒体音量为 0 影响、夜里能穿透帐篷
        ToneGenerator tg;
        try {
            tg = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        } catch (Throwable t) {
            // 极少数设备上音频服务拿不到，退回播系统闹铃（这是主提醒，不能哑）
            ringAlarm(app);
            return;
        }
        sTone = tg;
        final ToneGenerator tgf = tg;
        final Handler h = new Handler(Looper.getMainLooper());
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        // TONE_PROP_BEEP2 = 两声短「嘟嘟」，间隔清楚但不刺耳
                        tgf.startTone(ToneGenerator.TONE_PROP_BEEP2, 700);
                    } catch (Throwable ignored) {
                    }
                    if (idx == 2) {
                        // 三声都发完了再释放，早释放会哑掉
                        h.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (sTone == tgf) {
                                    sTone = null;
                                }
                                try {
                                    tgf.release();
                                } catch (Throwable ignored) {
                                }
                            }
                        }, 1000L);
                    }
                }
            }, i * 1100L);
        }
    }

    /** 释放正在响的那一路（没有就什么都不做） */
    private static synchronized void releaseTone() {
        ToneGenerator t = sTone;
        sTone = null;
        if (t != null) {
            try {
                t.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 兜底：播系统闹铃 6 秒（ToneGenerator 用不了的时候） */
    private static void ringAlarm(Context app) {
        final Ringtone r = RingtoneManager.getRingtone(
                app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
        if (r == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 21) {
            // 走闹钟流，别被媒体音量 0 吃掉
            r.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
        }
        r.play();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    r.stop();
                } catch (Throwable ignored) {
                }
            }
        }, 6000L);
    }

    // ---------------------------------------------------------------- 通知

    private static void notify(Context app) {
        NotificationManager nm =
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "拍摄结束提醒", NotificationManager.IMPORTANCE_HIGH);
            // 声音和震动由本类自己出，渠道别再响一遍，否则会「嘟嘟 + 叮咚」重叠
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(true);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(app, CHANNEL_ID);
        } else {
            b = new Notification.Builder(app);
        }

        Intent i = new Intent();
        // 走 PermissionGate（与桌面图标同一个入口），避免直接拉 MainActivity
        // 时因为进程被回收而缺初始化状态
        i.setClassName(app, "com.lifeipeng.magicaca.PermissionGate");
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(app, 0, i, flags);

        b.setSmallIcon(android.R.drawable.ic_menu_camera) // 系统图标，不依赖本包资源 ID
                .setContentTitle("茄子快门")
                .setContentText("拍摄任务完成")
                .setTicker("拍摄任务完成")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setWhen(System.currentTimeMillis())
                .setOnlyAlertOnce(true);
        if (Build.VERSION.SDK_INT >= 21) {
            b.setCategory(Notification.CATEGORY_ALARM);
        }

        nm.notify(NOTIFY_ID, b.build());
    }
}
