package com.lifeipeng.magicaca.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 「间隔方案」快捷条 —— 挂在 Bulb Time Lapse 页三个输入框的下方。
 *
 * 短按 = 套用该档；长按 = 把当前界面上的三个值存进该档。
 * 六个槽位全部可改，存在 qiezi_prefs 里，冷启动/升级都不丢。
 *
 * 间隔语义（2026-10-30 真机实测重定，**推翻旧结论**）：
 *   B 门延时摄影里，「间隔时间」= **曝光结束之后的空档**，
 *   真实周期 = 曝光 + 间隔。
 *   铁证（A7R3 + 盒子，掐表）：
 *     曝光 1s  / 间隔 1s  / 5 张 → 实跑 **9 秒**  = (1+1)×5−1
 *     曝光 10s / 间隔 20s / 3 张 → 实跑 **70 秒** = (10+20)×3−20
 *   旧结论「间隔 = 周期（含曝光）」错在两处：
 *     ① 出处是第三方文章的普通延时语境，不适用 B 门模式；
 *     ② 拿「Time Lapse 页把 fff7/fff8 发成同一个值」当同义证据 ——
 *        fff7 走 formatDuration([10ms,秒,分])、fff8 走 formatTime([秒,分,时])，
 *        编码同形、语义无关。同值 ≠ 同义。
 *   ⇒ 间隔只需留 2~3 秒写卡缓冲，与曝光长短无关，别再改回去。
 *   （普通延时摄影那个模块不受影响，那里 cycletime 仍是周期。）
 *
 * 为什么不 import FragmentDelayAdvance：
 *   本类在 classes2.dex，Fragment 在 classes.dex。编译 Java 时拿不到原工程的 class，
 *   所以字段和私有方法全部走反射。反射失败只打日志、不抛，方案条是便利功能，
 *   绝不能因为它把主功能带崩。
 */
public final class PresetBar {

    /** 和曝光时长持久化用同一个 prefs 文件，方便一起备份/清空 */
    public static final String PREFS = "qiezi_prefs";
    public static final String K_INTERVAL = "preset_interval_sec";
    public static final String K_COUNT = "preset_count";
    /** 与 1.6.8 滚轮那份一致 */
    public static final String K_EXPOSURE = "exposure_ms";

    private static final int SLOTS = 6;

    /**
     * 内置默认六档 = { 曝光毫秒, 张数, 间隔秒 }。
     *
     * 1.6.16：间隔改成「曝光之后的空档」，统一 3 秒。
     * 旧表填的是 62/122/182/303（= 曝光 + 2~3 秒，按「间隔=周期」算的），
     * 在真实叠加语义下会变成 2 倍周期 —— 60s×60 实际要跑 2 小时而不是 1 小时。
     * 写卡时间只跟单张体积有关（42MP RAW 82MB，UHS-I 卡 1~1.5 秒），
     * 与曝光长短无关，所以六档统一 3 秒就够，不用按档位加码。
     * 这些只是出厂值，长按任意一档就能用你现场调好的数字覆盖它。
     */
    private static final int[][] DEFAULTS = {
            {60000, 60, 3},
            {60000, 30, 3},
            {120000, 30, 3},
            {120000, 15, 3},
            {180000, 20, 3},
            {300000, 12, 3},
    };

    /** 1.6.16 一次性迁移用：旧默认间隔（按「间隔=周期」算出来的值） */
    private static final String K_MIG_V2 = "slot_gap_v2";
    /**
     * 两代旧默认值，按槽位给出候选：
     *   第一代（1.6.10）：65 / 126 / 186 / 308 —— 按「曝光 + 5 秒」算的
     *   第二代（1.6.11~1.6.15）：62 / 122 / 182 / 303 —— 按「曝光 + 2~3 秒」算的
     * 两代都是把 B 门的间隔当成「周期」，叠加语义下实际周期翻倍。
     */
    private static final int[][] OLD_IV = {
            {65, 62}, {65, 62}, {126, 122}, {126, 122}, {186, 182}, {308, 303},
    };
    private static final int NEW_IV = 3;

    /**
     * 旧槽位把「间隔」当周期填，存的都是 65/126/186/308（1.6.10）或 62/122/182/303（1.6.11+）。
     * 真机实测确认 B 门的间隔是「曝光之后的空档」后，这些值会让实际周期翻倍
     * （60s×60 从 1 小时变 2 小时）。这里把**恰好等于旧默认值**的档改成 3 秒空档；
     * 用户自己改过间隔的档（不等于旧默认）原样保留，不动。
     * 只跑一次，用 prefs 里的 slot_gap_v2 标记。
     */
    private static void migrateToGapSemantics(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
            if (p.getBoolean(K_MIG_V2, false)) {
                return;
            }
            SharedPreferences.Editor e = p.edit();
            int changed = 0;
            for (int i = 0; i < SLOTS; i++) {
                int old = p.getInt("slot" + i + "_iv", -1);
                if (old <= 0) {
                    continue;
                }
                boolean hit = false;
                for (int cand : OLD_IV[i]) {
                    if (old == cand) {
                        hit = true;
                        break;
                    }
                }
                if (hit) {
                    e.putInt("slot" + i + "_iv", NEW_IV);
                    changed++;
                }
            }
            e.putBoolean(K_MIG_V2, true).apply();
            if (changed > 0) {
                Log.i("QIEZI", "方案条迁移：" + changed + " 档的间隔改为 " + NEW_IV + " 秒空档");
            }
        } catch (Throwable ignore) {
            // 方案条是便利功能，迁移失败绝不能影响主流程
        }
    }

    private static final int C_BG = 0xFF2C2C2C;
    private static final int C_BG_PRESSED = 0xFF3D3D3D;
    private static final int C_BORDER = 0xFF58595A;
    private static final int C_TEXT = 0xFFDDDDDD;

    /**
     * 选中态用「亮度差」表达，不用色相。
     *
     * 1.6.12 改的：原来选中底是蓝色 #1EA5F9，彩屏下好看，
     * 但夜间红光是一层只保留红通道的矩阵 —— 蓝色贡献约 0.055×B，几乎全黑，
     * 选中档在红光下跟没选一样，正是最需要看清的时候看不清。
     * 现在改成亮灰底 + 纯白描边，亮度是未选中的两倍多，两种模式下都分得清。
     */
    private static final int C_SEL_BG = 0xFF5A5F66;
    private static final int C_SEL_BG_PRESSED = 0xFF6B7178;
    private static final int C_SEL_BORDER = 0xFFFFFFFF;
    private static final int C_TEXT_ON = 0xFFFFFFFF;

    private static final TextView[] sChips = new TextView[SLOTS];
    private static LinearLayout sRow = null;
    private static Object sHost = null;
    private static int sSelected = -1;

    private PresetBar() {
    }

    // ---------------------------------------------------------------- 挂载

    /**
     * 把方案条插进 parent 的第 index 个子 View 之前。
     *
     * @param parent Bulb Time Lapse 页中间那个竖向 LinearLayout
     * @param index  插入位置（曝光时长输入框之后）
     * @param host   FragmentDelayAdvance 实例，读写三个输入框都靠它反射
     */
    public static void install(ViewGroup parent, int index, final Object host) {
        if (parent == null || host == null) {
            return;
        }
        final Context ctx = parent.getContext();
        if (ctx == null) {
            return;
        }

        sHost = host;

        // 1.6.16：装上去之前先把旧槽位的「间隔=周期」值迁成「3 秒空档」
        migrateToGapSemantics(ctx);

        int pad = dp(ctx, 2);
        int height = dp(ctx, 40);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(ctx, 10);
        rowLp.leftMargin = dp(ctx, 8);
        rowLp.rightMargin = dp(ctx, 8);
        row.setLayoutParams(rowLp);

        for (int i = 0; i < SLOTS; i++) {
            final int idx = i;
            TextView chip = new TextView(ctx);
            int[] slot = loadSlot(ctx, i);
            chip.setText(label(slot[0], slot[1]));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            chip.setGravity(Gravity.CENTER);
            chip.setTextColor(C_TEXT);
            chip.setSingleLine(true);
            chip.setClickable(true);
            chip.setBackground(makeBg(ctx, false));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, 1f);
            lp.leftMargin = pad;
            lp.rightMargin = pad;
            chip.setLayoutParams(lp);

            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applySlot(idx);
                }
            });
            // 长按 = 用当前界面上的三个值覆盖这一档
            chip.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    captureSlot(idx);
                    return true;
                }
            });
            sChips[i] = chip;
            row.addView(chip);
        }

        sRow = row;
        sSelected = -1;

        // 冷启动时把上次用的那档点亮。
        // 1.6.12：曝光也要一起比。原来只比「张数 + 间隔」，
        // 用户手动把曝光从 2 分钟改成 90 秒、张数间隔没动，回来会错点亮「2m×30」。
        int savedMs = loadExposure(ctx);
        int savedInterval = loadInterval(ctx);
        int savedCount = loadCount(ctx);
        for (int i = 0; i < SLOTS; i++) {
            int[] slot = loadSlot(ctx, i);
            if (slot[0] == savedMs && slot[1] == savedCount && slot[2] == savedInterval) {
                setSelected(i);
                break;
            }
        }

        int at = index;
        if (at < 0) {
            at = 0;
        }
        if (at > parent.getChildCount()) {
            at = parent.getChildCount();
        }
        parent.addView(row, at);
    }

    // ---------------------------------------------------------------- 套用 / 保存

    private static void applySlot(int i) {
        if (sHost == null) {
            return;
        }
        Context ctx = sRow != null ? sRow.getContext() : null;
        if (ctx == null) {
            return;
        }
        int[] slot = loadSlot(ctx, i);

        // 顺序有讲究：先曝光、再张数、最后间隔，跟界面上从上到下的顺序一致，
        // 万一某个 setter 触发 updateDisplayParams，中间态也不会太离谱
        Object duration = fld(sHost, "m_input_duration");
        Object times = fld(sHost, "m_input_times");
        Object circle = fld(sHost, "m_input_circle");
        if (duration == null || times == null || circle == null) {
            toast(ctx, "读取输入框失败，方案条暂时用不了");
            return;
        }

        setValue(duration, slot[0]);
        setValue(times, slot[1]);
        setValue(circle, slot[2]);

        // 刷新 Time Left / 视频时长，并把值落盘（否则冷启动「间隔记住了、曝光丢了」）
        call(sHost, "updateDisplayParams");
        save(ctx, slot[2], slot[1]);
        saveExposure(ctx, slot[0]);
        setSelected(i);

        toast(ctx, "已套用 " + label(slot[0], slot[1]) + "，空档 " + hms(slot[2]));
    }

    private static void captureSlot(int i) {
        if (sHost == null) {
            return;
        }
        Context ctx = sRow != null ? sRow.getContext() : null;
        if (ctx == null) {
            return;
        }
        int ms = getValue(fld(sHost, "m_input_duration"));
        int cnt = getValue(fld(sHost, "m_input_times"));
        int iv = getValue(fld(sHost, "m_input_circle"));
        if (ms <= 0 || cnt <= 0 || iv <= 0) {
            toast(ctx, "当前值还不完整，先填好曝光/张数/间隔");
            return;
        }
        // 1.6.16：删掉「间隔 < 曝光就拒存」的硬拦。
        // 间隔是「曝光之后的空档」，60s 曝光 + 3s 空档是完全合法的组合，
        // 旧版这条判断把正确用法全挡在外面。现在只在空档过小时于存档提示里带一句，不拦。
        saveSlot(ctx, i, ms, cnt, iv);
        if (sChips[i] != null) {
            sChips[i].setText(label(ms, cnt));
        }
        setSelected(i);

        // 顺带把这组值也当成「上次用的」，冷启动直接回到这里
        save(ctx, iv, cnt);
        saveExposure(ctx, ms);
        toast(ctx, "第 " + (i + 1) + " 档已存为 " + label(ms, cnt) + "，空档 " + hms(iv)
                + (iv < 2 ? "（偏短，拍 RAW 可能漏拍）" : ""));
    }

    /** 高亮某一档（-1 = 全部取消） */
    public static void setSelected(int index) {
        sSelected = index;
        for (int i = 0; i < SLOTS; i++) {
            TextView chip = sChips[i];
            if (chip == null) {
                continue;
            }
            boolean on = (i == index);
            chip.setTextColor(on ? C_TEXT_ON : C_TEXT);
            chip.setBackground(makeBg(chip.getContext(), on));
        }
    }

    private static StateListDrawable makeBg(Context ctx, boolean selected) {
        int radius = dp(ctx, 6);
        int strokeW = dp(ctx, selected ? 2 : 1);

        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(radius);
        normal.setColor(selected ? C_SEL_BG : C_BG);
        normal.setStroke(strokeW, selected ? C_SEL_BORDER : C_BORDER);

        GradientDrawable pressed = new GradientDrawable();
        pressed.setCornerRadius(radius);
        pressed.setColor(selected ? C_SEL_BG_PRESSED : C_BG_PRESSED);
        pressed.setStroke(strokeW, selected ? C_SEL_BORDER : C_BORDER);

        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sl.addState(new int[]{}, normal);
        return sl;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------------------------------------------------------- 文案

    /** 按钮文字：60s×60 / 2m×30 / 90s×20
     *
     * 60 秒不写成 1m —— 深空现场口头说的都是「六十秒一张」，
     * 写成 1m 反而要换算；两分钟以上再用分钟，短。
     */
    private static String label(int millis, int count) {
        int sec = millis / 1000;
        String e;
        if (sec >= 120 && sec % 60 == 0) {
            e = (sec / 60) + "m";
        } else {
            e = sec + "s";
        }
        return e + "×" + count;
    }

    /** 62 → 01:02，368 → 06:08 */
    private static String hms(int sec) {
        int m = sec / 60;
        int s = sec % 60;
        return (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
    }

    private static void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 槽位持久化

    /** smali 侧的 applyPreset 还在用这个方法取表（虽然 1.6.11 起不再被调用），保留 */
    public static int[][] presets() {
        int[][] t = new int[SLOTS][];
        Context ctx = sRow != null ? sRow.getContext() : null;
        for (int i = 0; i < SLOTS; i++) {
            t[i] = ctx != null ? loadSlot(ctx, i) : DEFAULTS[i];
        }
        return t;
    }

    private static int[] loadSlot(Context ctx, int i) {
        if (ctx == null || i < 0 || i >= SLOTS) {
            return DEFAULTS[0];
        }
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
            int[] d = DEFAULTS[i];
            return new int[]{
                    p.getInt("slot" + i + "_ms", d[0]),
                    p.getInt("slot" + i + "_cnt", d[1]),
                    p.getInt("slot" + i + "_iv", d[2]),
            };
        } catch (Throwable t) {
            return DEFAULTS[i];
        }
    }

    private static void saveSlot(Context ctx, int i, int ms, int cnt, int iv) {
        try {
            ctx.getSharedPreferences(PREFS, 0).edit()
                    .putInt("slot" + i + "_ms", ms)
                    .putInt("slot" + i + "_cnt", cnt)
                    .putInt("slot" + i + "_iv", iv)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 上次用的值
    // 存「间隔秒数」和「张数」两个独立值，不存方案序号：
    // 以后改内置表（增删档位、调缓冲），存序号会指向别的组合，存值不会。

    /** 由 saveParam 调用（进入页面、按下快门各一次），把当前值落盘 */
    public static void save(Context ctx, int intervalSec, int count) {
        if (ctx == null || intervalSec <= 0 || count <= 0) {
            return;
        }
        try {
            SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, 0).edit();
            e.putInt(K_INTERVAL, intervalSec);
            e.putInt(K_COUNT, count);
            e.apply();
        } catch (Throwable ignored) {
        }
    }

    public static int loadInterval(Context ctx) {
        return readInt(ctx, K_INTERVAL);
    }

    public static int loadCount(Context ctx) {
        return readInt(ctx, K_COUNT);
    }

    public static int loadExposure(Context ctx) {
        return readInt(ctx, K_EXPOSURE);
    }

    /**
     * 用户手动改过界面上任何一个值之后调用（目前是张数 ± 按钮）。
     *
     * 作用：让高亮永远只描述「界面上此刻正好等于哪一档」。
     * 原来改完张数高亮不动，会出现「60s×60 亮着、实际 62 张」，
     * 用户会以为方案还生效 —— 这种「看起来对、实际不对」最坑。
     *
     * 匹配用的是三个值全等；一个都对不上就全灭，宁可没有高亮也不给错信息。
     */
    public static void onManualEdit() {
        Context ctx = sRow != null ? sRow.getContext() : null;
        if (ctx == null || sHost == null) {
            return;
        }
        int ms = getValue(fld(sHost, "m_input_duration"));
        int cnt = getValue(fld(sHost, "m_input_times"));
        int iv = getValue(fld(sHost, "m_input_circle"));
        int hit = -1;
        if (ms > 0 && cnt > 0 && iv > 0) {
            for (int i = 0; i < SLOTS; i++) {
                int[] slot = loadSlot(ctx, i);
                if (slot[0] == ms && slot[1] == cnt && slot[2] == iv) {
                    hit = i;
                    break;
                }
            }
        }
        setSelected(hit);
    }

    /**
     * 曝光时长单独存一个键 —— 和 1.6.8 里滚轮「确定」写的键完全一致（qiezi_prefs/exposure_ms），
     * 这样两处写、一处读（ViewTimeBuildInput.doInit），不会出现「间隔记住了、曝光丢了」。
     */
    public static void saveExposure(Context ctx, int millis) {
        if (ctx == null || millis <= 0) {
            return;
        }
        try {
            ctx.getSharedPreferences(PREFS, 0).edit().putInt(K_EXPOSURE, millis).apply();
        } catch (Throwable ignored) {
        }
    }

    private static int readInt(Context ctx, String key) {
        if (ctx == null) {
            return -1;
        }
        try {
            return ctx.getSharedPreferences(PREFS, 0).getInt(key, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- 反射工具

    static Object fld(Object o, String name) {
        if (o == null) {
            return null;
        }
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    static int getValue(Object input) {
        if (input == null) {
            return -1;
        }
        try {
            Method m = input.getClass().getMethod("getValue");
            Object r = m.invoke(input);
            return r instanceof Integer ? ((Integer) r).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    static void setValue(Object input, int v) {
        if (input == null) {
            return;
        }
        try {
            input.getClass().getMethod("setValue", Integer.TYPE)
                    .invoke(input, Integer.valueOf(v));
        } catch (Throwable t) {
            android.util.Log.e("PresetBar", "setValue 失败 " + v, t);
        }
    }

    static void call(Object o, String name) {
        if (o == null) {
            return;
        }
        try {
            Method m = o.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            m.invoke(o);
        } catch (Throwable t) {
            android.util.Log.e("PresetBar", name + " 调用失败", t);
        }
    }
}
