package com.lifeipeng.magicaca.local;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 深空盒子的下发引擎（纯逻辑，没有任何界面）。
 *
 * 盒子 = 相机遥控口上的两根开关线（半按 / 全按），只能编排「何时按、按多久、按几次」。
 * 本类只负责把界面选好的参数翻译成盒子能懂的特征值。
 *
 * 三类任务（字节全部照抄原版界面，不是猜的）：
 *
 *   唤醒：fff3 = 0xFF(-1) 半按，等 0.9 秒，fff3 = 0xFD(-3) 松开。
 *      出处 FragmentSingle.circlePressed()（-1）与 circleClickCancel()（-3）。
 *      真机症状：相机待机黑屏时，第一次曝光只有 0.1 秒 —— 因为盒子把线合上之后
 *      相机还要 ~2 秒才醒，醒过来才开帘，等它开帘时盒子的时长已经快走完了。
 *      半按是相机认的「唤醒」动作，先做一次就不用从曝光里扣这段时间。
 *
 *   B 门单档：fff2=0x04, fff5=formatTime(0), fff8=formatTime(空档秒),
 *             fff7=formatTimeByMillisec(曝光毫秒), fff6=intToLSBBytes(张数),
 *             fff9=appMode(4), fff1=0x01
 *      出处 FragmentDelayAdvance.getActions()。
 *      注意 fff7 必须走 formatTimeByMillisec（布局 [10ms,秒,分]），
 *      不能用 formatTime（布局 [秒,分,时]）—— 两者完全不同的编码。
 *
 *   定时预约：fff2=0x03, fff5=formatTime(秒), fff6=[01,00], fff9=appMode(0), fff1=0x01
 *      出处 FragmentSingle.getActions() 的「有延迟」分支（张数固定写死为 1）。
 *
 * 为什么全走固件任务、不逐张下发：
 *   逐张发要手机全程醒着，一进 Doze 就停；固件任务下发完盒子自己跑，锁屏不管。
 *
 * 为什么全是反射：
 *   本类在 classes2.dex，EActionNode / ETool / ApplicationDelegate 都在 classes.dex，
 *   编译期拿不到它们的 class。反射失败只打日志，绝不让辅助功能把主流程带崩。
 */
public final class AstroEngine {

    private static final String TAG = "QIEZI";

    private static final String U_F1 = "0000fff1-0000-1000-8000-00805f9b34fb";
    private static final String U_F2 = "0000fff2-0000-1000-8000-00805f9b34fb";
    private static final String U_F3 = "0000fff3-0000-1000-8000-00805f9b34fb";
    private static final String U_F5 = "0000fff5-0000-1000-8000-00805f9b34fb";
    private static final String U_F6 = "0000fff6-0000-1000-8000-00805f9b34fb";
    private static final String U_F7 = "0000fff7-0000-1000-8000-00805f9b34fb";
    private static final String U_F8 = "0000fff8-0000-1000-8000-00805f9b34fb";
    private static final String U_F9 = "0000fff9-0000-1000-8000-00805f9b34fb";

    /**
     * fff1 的动作标志位。
     * 原版由界面上的 m_sliderAction 决定，构造函数里默认值就是 4；
     * sliderAction==4 时算出来等于 1。这里固定 1，与用户手点开始时发出的字节完全一致。
     */
    public static final int FLAG_DEFAULT = 1;

    /**
     * 分段下发之间的间隔（毫秒）。
     *
     * 盒子收到 fff2 换模式后，固件会把任务参数重置成默认值，这个重置是异步的：
     * GATT 一写回 ok 不等于固件已经处理完，我们抢在它前面写 fff7，值可能被覆盖。
     * 220ms：一带一写回约 30~50ms，留 4 倍余量。
     */
    public static final int STAGE_GAP_MS = 220;

    /** 半按保持多久（毫秒）。相机从待机醒来大约要 1~2 秒，0.9 秒是够唤醒、又不至于太慢的折中。 */
    public static final int WAKE_HOLD_MS = 900;

    /** 松开半按之后、正式发任务之前等多久，给相机把「半按松开→就绪」这一步走完。 */
    public static final int WAKE_SETTLE_MS = 150;

    /** 拍摄前是否先半按唤醒。相机待机会把第一次曝光削成 0.1 秒，默认开着。 */
    public static boolean wakeBeforeShoot = true;

    private static final Handler UI = new Handler(Looper.getMainLooper());

    /**
     * 下发序列期间的上锁截止时刻。
     *
     * 连点两下时，第二批的「换模式」会插进第一批的 ② 和 ③ 之间，把刚写的曝光打回默认值；
     * 唤醒序列被插队也会乱。所以一次完整序列走完前，直接拒掉新的下发。
     */
    private static long sBusyUntil = 0L;

    private static boolean acquire(long windowMs) {
        long now = System.currentTimeMillis();
        if (now < sBusyUntil) {
            Log.w(TAG, "上一批还在下发，这次的请求被忽略");
            return false;
        }
        sBusyUntil = now + windowMs;
        return true;
    }

    /** 界面用：现在是否正在下发中。 */
    public static boolean busy() {
        return System.currentTimeMillis() < sBusyUntil;
    }

    private AstroEngine() {
    }

    // ------------------------------------------------------------ 对外任务

    /** 找星 / 单张：B 门模式拍 1 张，曝光时长由盒子按住快门的时间决定。 */
    public static boolean shootBulbOne(Context ctx, int expMs) {
        boolean ok = sendBulbTask(ctx, expMs, GAP_DEFAULT_SEC, 1, FLAG_DEFAULT);
        Log.i(TAG, "深空·找星单张 曝光 " + (expMs / 1000f) + "s " + (ok ? "已受理" : "被拒（忙或失败）"));
        return ok;
    }

    /** 单张时 fff8 只是填个合法值，公式里 (曝光+空档)×1−空档 = 曝光，不影响时长。 */
    public static final int GAP_DEFAULT_SEC = 3;

    /**
     * 只唤醒，不拍。给界面上的「唤醒相机」按钮用 ——
     * 相机刚睡下去不想浪费一张 0.1 秒的废片时，先用这个把它叫醒。
     */
    public static boolean wakeOnly(Context ctx) {
        if (!acquire(WAKE_HOLD_MS + WAKE_SETTLE_MS + 300L)) {
            return false;
        }
        boolean ok = pressHalf(ctx);
        Log.i(TAG, "深空·唤醒相机 " + (ok ? "半按保持 " + WAKE_HOLD_MS + "ms" : "失败"));
        return ok;
    }

    /** 定时预约：delaySec 秒之后由固件自己开拍，拍 1 张。 */
    public static boolean shootAfter(final Context ctx, final int delaySec) {
        if (!acquire(STAGE_GAP_MS * 2L + 300L)) {
            return false;
        }
        final List<Object> stageMode = new ArrayList<Object>();
        stageMode.add(node(U_F2, new byte[]{3}));
        stageMode.add(node(U_F9, etool("adaptorAppModeWithNoParam", 0)));

        final List<Object> stageParam = new ArrayList<Object>();
        stageParam.add(node(U_F5, etool("formatTime", delaySec)));
        stageParam.add(node(U_F6, etool("intToLSBBytes", 1)));

        final List<Object> stageStart = new ArrayList<Object>();
        stageStart.add(node(U_F1, new byte[]{(byte) FLAG_DEFAULT}));

        boolean ok = send(ctx, stageMode);
        Log.i(TAG, "深空·预约 " + delaySec + " 秒后拍 1 张 ①mode " + (ok ? "ok" : "失败"));
        if (!ok) {
            return false;
        }

        UI.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean ok2 = send(ctx, stageParam);
                Log.i(TAG, "预约②参数 延迟 " + delaySec + "s 张数 1 " + (ok2 ? "ok" : "失败"));
            }
        }, STAGE_GAP_MS);

        UI.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean ok3 = send(ctx, stageStart);
                Log.i(TAG, "预约③启动 " + (ok3 ? "ok" : "失败"));
            }
        }, STAGE_GAP_MS * 2);

        return true;
    }

    /**
     * B 门任务：曝光 durationMs，空档 gapSec，连拍 count 张。
     *
     * 顺序：唤醒（可选）→ ① 换模式 → ② 参数 → ③ 启动。
     * 第 ③ 段重写一遍 fff7 是兜底：万一固件那次重置落在 ②③ 之间，这一遍仍能写回去。
     */
    public static boolean sendBulbTask(final Context ctx, final int durationMs, final int gapSec,
                                       final int count, final int flags) {
        long window = STAGE_GAP_MS * 2L + 300L;
        if (wakeBeforeShoot) {
            window += WAKE_HOLD_MS + WAKE_SETTLE_MS;
        }
        if (!acquire(window)) {
            return false;
        }

        if (wakeBeforeShoot) {
            if (!pressHalf(ctx)) {
                return false;
            }
            UI.postDelayed(new Runnable() {
                @Override
                public void run() {
                    releaseHalf(ctx);
                    UI.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            runBulbStages(ctx, durationMs, gapSec, count, flags);
                        }
                    }, WAKE_SETTLE_MS);
                }
            }, WAKE_HOLD_MS);
        } else {
            runBulbStages(ctx, durationMs, gapSec, count, flags);
        }
        return true;
    }

    // ------------------------------------------------------------ 内部：唤醒与三段下发

    private static boolean pressHalf(Context ctx) {
        List<Object> nodes = new ArrayList<Object>();
        nodes.add(node(U_F3, new byte[]{(byte) 0xFF}));   // -1 = 半按
        boolean ok = send(ctx, nodes);
        Log.i(TAG, "唤醒·半按 " + (ok ? "ok" : "失败"));
        return ok;
    }

    private static void releaseHalf(Context ctx) {
        List<Object> nodes = new ArrayList<Object>();
        nodes.add(node(U_F3, new byte[]{(byte) 0xFD}));   // -3 = 松开
        boolean ok = send(ctx, nodes);
        Log.i(TAG, "唤醒·松开 " + (ok ? "ok" : "失败"));
    }

    private static void runBulbStages(final Context ctx, final int durationMs, final int gapSec,
                                      final int count, final int flags) {
        final List<Object> stageMode = new ArrayList<Object>();
        stageMode.add(node(U_F2, new byte[]{4}));
        stageMode.add(node(U_F9, etool("adaptorAppModeWithNoParam", 4)));

        final List<Object> stageParam = new ArrayList<Object>();
        stageParam.add(node(U_F5, etool("formatTime", 0)));
        stageParam.add(node(U_F8, etool("formatTime", gapSec)));
        stageParam.add(node(U_F7, etool("formatTimeByMillisec", durationMs)));
        stageParam.add(node(U_F6, etool("intToLSBBytes", count)));

        final List<Object> stageStart = new ArrayList<Object>();
        stageStart.add(node(U_F7, etool("formatTimeByMillisec", durationMs)));
        stageStart.add(node(U_F1, new byte[]{(byte) flags}));

        boolean ok = send(ctx, stageMode);
        Log.i(TAG, "①mode=4 " + (ok ? "ok" : "失败"));
        if (!ok) {
            return;
        }

        UI.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean ok2 = send(ctx, stageParam);
                Log.i(TAG, "②参数 曝光 " + durationMs + "ms / 空档 " + gapSec + "s ×" + count
                        + " " + (ok2 ? "ok" : "失败") + " 盒子回读 " + boxDurationMs() + "ms");
            }
        }, STAGE_GAP_MS);

        UI.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean ok3 = send(ctx, stageStart);
                Log.i(TAG, "③启动 " + (ok3 ? "ok" : "失败"));
            }
        }, STAGE_GAP_MS * 2);
    }

    /** 诊断用：盒子把 fff7 回读成多少毫秒（EGlobal.currentDuration）。读不到返回 -1。 */
    private static int boxDurationMs() {
        try {
            Class<?> c = Class.forName("com.lifeipeng.magicaca.common.EGlobal");
            return c.getField("currentDuration").getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------ 时长估算

    /**
     * B 门单档实际耗时：周期 = 曝光 + 空档，末张后面不再等空档。
     * 这条公式是掐表验过的（曝光 10s / 空档 20s / 3 张 = 70 秒）。
     */
    public static int stepDurationMs(int expMs, int gapSec, int shots) {
        int cycleMs = expMs + gapSec * 1000;
        return cycleMs * shots - gapSec * 1000;
    }

    /** 阶梯总时长（不含档间余量），给界面显示预估用。 */
    public static int ladderTotalMs(int[] secs, int steps, int shots, int gapSec) {
        int total = 0;
        for (int i = 0; i < steps && i < secs.length; i++) {
            total += stepDurationMs(secs[i] * 1000, gapSec, shots) + SLACK_MS;
        }
        return total;
    }

    /** 档与档之间留的余量，防止上一任务还没收尾就发下一个。 */
    public static final int SLACK_MS = 3000;

    // ------------------------------------------------------------ 反射底座

    public static boolean connected(Context ctx) {
        try {
            Object app = ctx.getApplicationContext();
            Method m = app.getClass().getMethod("isBleServiceAvailable");
            Object r = m.invoke(app);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return true; // 判不了就照发，别把功能堵死
        }
    }

    private static byte[] etool(String method, int arg) {
        try {
            Class<?> c = Class.forName("com.lifeipeng.magicaca.tool.ETool");
            Method m = c.getMethod(method, int.class);
            return (byte[]) m.invoke(null, arg);
        } catch (Throwable t) {
            Log.w(TAG, "ETool." + method + "(" + arg + ") 失败：" + t);
            return null;
        }
    }

    private static Object node(String uuid, byte[] val) {
        if (val == null) {
            return null;
        }
        try {
            Class<?> c = Class.forName("com.lifeipeng.magicaca.entry.EActionNode");
            Constructor<?> k = c.getConstructor(String.class, byte[].class);
            return k.newInstance(uuid, val);
        } catch (Throwable t) {
            Log.w(TAG, "构造 EActionNode 失败：" + t);
            return null;
        }
    }

    private static boolean send(Context ctx, List<Object> nodes) {
        for (Object o : nodes) {
            if (o == null) {
                return false;
            }
        }
        try {
            Object app = ctx.getApplicationContext();
            Method m = app.getClass().getMethod("writeCharValues", List.class);
            m.invoke(app, nodes);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "writeCharValues 调用失败：" + t);
            return false;
        }
    }
}
