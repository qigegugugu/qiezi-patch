package com.lifeipeng.magicaca.local;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Bulb Time Lapse 页的界面改造，全部在运行时做，不改 XML、不碰资源 ID。
 *
 * 做六件事：
 *   1. 夜间红光 —— 整个窗口走一层红色色彩矩阵（API 31+ 用 RenderEffect，
 *      低版本降级为递归改文字颜色）。深空现场看一眼白屏，暗视要恢复二十分钟。
 *   2. 收起 Interval 的「时」栏 —— 深空最长也就几分钟，H 栏永远用不上，
 *      却占了 1/3 宽度；收掉之后 M/S 各占一半，戴手套也好点。
 *   3. 张数加 ± 按钮 —— 原来只能点开弹窗输数字，夜里摸黑太麻烦。
 *   4. 圆环外圈画进度弧 —— 原来只有「12 / 60」的文字，远远瞄一眼看不出来拍了几成。
 *   5. 隐藏曲线拍摄的两个圆形按钮（clear / sync）—— 深空场景零用途。
 *   6. 拍摄页常亮 —— 屏幕在黑夜里自己灭掉，任务还在跑，却看不到状态。
 *
 * 全部操作都是幂等的：onCreateView 每次进来都会调一次，重复调用只是刷新状态。
 * 任何一步失败都只打日志，绝不影响原功能。
 */
public final class UiPatch {

    private static final String PREFS = "qiezi_prefs";
    private static final String K_NIGHT = "night_mode";
    private static final String K_HINT = "night_hint_shown";

    /** 用来标记「这个 root 已经改过了」，避免重复包装布局 */
    private static final int TAG_DONE = 0x7E1A0001;
    private static final int TAG_ARC = 0x7E1A0002;

    /**
     * 红光滤镜：先把像素转成亮度，再全部压到红通道，并整体压暗到 50%。
     *
     * 系数 = 亮度权重 (0.30 / 0.59 / 0.11) × 0.5。
     * 白色 → 红通道 0.5（中亮，看得清）；背景那种暗灰 → 0.03（几乎全黑）。
     * 压得太狠（<0.35）在户外弱光下会读不清字，压得太轻又保不住暗视。
     */
    private static final float[] NIGHT_MATRIX = {
            0.15f, 0.295f, 0.055f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
    };

    private UiPatch() {
    }

    // ---------------------------------------------------------------- 入口

    /**
     * @param root Bulb Time Lapse 页的根 View（FragmentDelayAdvance.m_view）
     * @param host FragmentDelayAdvance 实例
     */
    public static void apply(View root, Object host) {
        if (root == null || host == null) {
            return;
        }
        try {
            hideHourColumn(host);
        } catch (Throwable t) {
            log("hideHourColumn", t);
        }
        try {
            addCountButtons(host);
        } catch (Throwable t) {
            log("addCountButtons", t);
        }
        try {
            addProgressArc(root, host);
        } catch (Throwable t) {
            log("addProgressArc", t);
        }
        try {
            hideCurveButtons(root);
        } catch (Throwable t) {
            log("hideCurveButtons", t);
        }
        try {
            keepScreenOn(root);
        } catch (Throwable t) {
            log("keepScreenOn", t);
        }
        try {
            DewPoint.attach(root);
        } catch (Throwable t) {
            log("dewPoint", t);
        }
        try {
            hookNightToggle(root, host);
            applyNight(root.getContext(), isNight(root.getContext()));
        } catch (Throwable t) {
            log("applyNight", t);
        }
    }

    // ---------------------------------------------------------------- 1. 夜间红光

    private static boolean isNight(Context ctx) {
        try {
            // 1.7.6：默认值从 true 改成 false。
            //
            // 原来默认 true，看起来"照顾深空用户"，实际是个坑：本包自签名，
            // 覆盖安装必失败 → 每次升级都得先卸载 → 卸载清空 SharedPreferences
            // → 新装必然是 true → 一打开就是全屏红光。
            // 用户看到的是"界面全红/看不清/像坏了"，根本想不到是夜视模式开着。
            //
            // 规矩：所有改画面观感的开关（夜视红光、峰值、斑马、网格）一律默认关，
            // 要用的人自己开。黑暗环境下默认亮白才是更安全的默认。
            return ctx.getSharedPreferences(PREFS, 0).getBoolean(K_NIGHT, false);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void setNight(Context ctx, boolean on) {
        try {
            ctx.getSharedPreferences(PREFS, 0).edit().putBoolean(K_NIGHT, on).apply();
        } catch (Throwable ignored) {
        }
        applyNight(ctx, on);
    }

    private static void applyNight(Context ctx, boolean on) {
        Activity a = activityOf(ctx);
        if (a == null) {
            return;
        }
        View decor = a.getWindow().getDecorView();
        if (decor == null) {
            return;
        }
        // RenderEffect 是 API 31 才有的类，用反射调：这样在旧系统上编译期也不会缺类
        boolean ok = false;
        try {
            Class<?> reCls = Class.forName("android.graphics.RenderEffect");
            Object effect = null;
            if (on) {
                Method create = reCls.getMethod("createColorFilterEffect", android.graphics.ColorFilter.class);
                effect = create.invoke(null, new ColorMatrixColorFilter(new ColorMatrix(NIGHT_MATRIX)));
            }
            Method set = View.class.getMethod("setRenderEffect", reCls);
            set.invoke(decor, effect);
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) {
            // 旧系统（< Android 12）没有 RenderEffect，退一步只把文字和图标染红，
            // 视觉效果差些，但至少夜里不会一片惨白
            paintTree(decor, on);
        }
    }

    /** 兜底方案：递归把文字改红、图标加红色滤镜 */
    private static void paintTree(View v, boolean on) {
        if (v == null) {
            return;
        }
        if (v instanceof TextView) {
            ((TextView) v).setTextColor(on ? 0xFFFF3B30 : 0xFFFFFFFF);
        }
        if (v instanceof ImageView) {
            try {
                // 注意不能传 0：setColorFilter(int) 是 SRC_ATOP 语义，传 0 会把图标涂成透明
                ((ImageView) v).setColorFilter(on
                        ? new android.graphics.PorterDuffColorFilter(
                        0xFFFF3B30, android.graphics.PorterDuff.Mode.SRC_IN)
                        : null);
            } catch (Throwable ignored) {
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                paintTree(g.getChildAt(i), on);
            }
        }
    }

    /** 长按顶部标题切换夜间模式 —— 不额外占屏幕，也不用加按钮 */
    private static void hookNightToggle(View root, Object host) {
        Object titleObj = PresetBar.fld(host, "m_title");
        if (!(titleObj instanceof TextView)) {
            return;
        }
        final TextView title = (TextView) titleObj;
        if (title.getTag() != null) {
            return;
        }
        title.setTag(Boolean.TRUE);
        title.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Context ctx = v.getContext();
                boolean now = !isNight(ctx);
                setNight(ctx, now);
                Toast.makeText(ctx, now ? "夜间红光：开（长按标题切换）" : "夜间红光：关",
                        Toast.LENGTH_SHORT).show();
                return true;
            }
        });

        final Context ctx = title.getContext();
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
            if (!p.getBoolean(K_HINT, false)) {
                p.edit().putBoolean(K_HINT, true).apply();
                title.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(ctx, "夜间红光已开，长按标题可切回彩色",
                                Toast.LENGTH_LONG).show();
                    }
                }, 800L);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 2. 收起「时」栏

    private static void hideHourColumn(Object host) {
        Object circle = PresetBar.fld(host, "m_input_circle");
        if (circle == null) {
            return;
        }
        View hour = asView(PresetBar.fld(circle, "m_inputHour"));
        View hourUnit = asView(PresetBar.fld(circle, "m_hourToMin"));
        if (hour != null) {
            hour.setVisibility(View.GONE);
        }
        if (hourUnit != null) {
            hourUnit.setVisibility(View.GONE);
        }
        // 1.6.12：宽度不能再硬编码。
        // 原来写死 220dp，结果 Interval 行只有 212px、Exposure 行仍是 292px，
        // 两行的「分」「秒」对不齐，夜里一眼就看出来歪。
        // 现在直接照抄曝光时长那一行的宽度，两行同宽、数字垂直对齐。
        View cv = asView(circle);
        View dur = asView(PresetBar.fld(host, "m_input_duration"));
        if (cv == null || dur == null) {
            return;
        }
        ViewGroup.LayoutParams dlp = dur.getLayoutParams();
        ViewGroup.LayoutParams lp = cv.getLayoutParams();
        if (dlp == null || lp == null || dlp.width == 0) {
            return;
        }
        if (lp.width != dlp.width) {
            lp.width = dlp.width;
            cv.setLayoutParams(lp);
        }
    }

    // ---------------------------------------------------------------- 3. 张数 ±

    private static void addCountButtons(Object host) {
        Object timesObj = PresetBar.fld(host, "m_input_times");
        if (!(timesObj instanceof View)) {
            return;
        }
        final View times = (View) timesObj;
        if (times.getTag() != null) {
            return;
        }
        final ViewGroup parent = times.getParent() instanceof ViewGroup ? (ViewGroup) times.getParent() : null;
        if (parent == null) {
            return;
        }
        Context ctx = times.getContext();
        int idx = parent.indexOfChild(times);
        ViewGroup.LayoutParams lp = times.getLayoutParams();

        parent.removeView(times);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (lp != null) {
            row.setLayoutParams(lp);
        }

        LinearLayout.LayoutParams btnLpL = new LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44));
        btnLpL.leftMargin = dp(ctx, 4);
        btnLpL.rightMargin = dp(ctx, 4);
        LinearLayout.LayoutParams btnLpR = new LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44));
        btnLpR.leftMargin = dp(ctx, 4);
        btnLpR.rightMargin = dp(ctx, 4);

        TextView minus = makeStepButton(ctx, "−");
        TextView plus = makeStepButton(ctx, "+");

        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        midLp.topMargin = dp(ctx, 0);

        row.addView(minus, btnLpL);
        row.addView(times, midLp);
        row.addView(plus, btnLpR);

        parent.addView(row, idx < 0 ? 0 : idx);
        times.setTag(Boolean.TRUE);

        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step(host, times, -1);
            }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step(host, times, 1);
            }
        });
    }

    private static TextView makeStepButton(Context ctx, String text) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(0xFFDDDDDD);
        b.setClickable(true);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(ctx, 22));
        bg.setColor(0xFF2C2C2C);
        bg.setStroke(dp(ctx, 1), 0xFF58595A);
        b.setBackground(bg);
        return b;
    }

    private static void step(Object host, Object times, int delta) {
        int cur = PresetBar.getValue(times);
        if (cur < 0) {
            return;
        }
        int next = cur + delta;
        if (next < 1) {
            next = 1;
        }
        if (next > 9999) {
            next = 9999;
        }
        PresetBar.setValue(times, next);
        PresetBar.call(host, "updateDisplayParams");
        PresetBar.call(host, "saveParam");
        // 1.6.12：手动改过张数之后，原来那档的高亮就不成立了。
        // 不同步的话会出现「60s×60 亮着、实际 62 张」，用户以为方案还有效。
        PresetBar.onManualEdit();
    }

    // ---------------------------------------------------------------- 4. 进度弧

    private static void addProgressArc(View root, Object host) {
        View circle = asView(PresetBar.fld(host, "m_circle"));
        if (circle == null || circle.getTag(TAG_ARC) != null) {
            return;
        }
        if (!(circle.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) circle.getParent();
        ArcView arc = new ArcView(parent.getContext(), circle);
        // 1.6.12：不写死 RelativeLayout.LayoutParams。
        // 传基类 LayoutParams，parent 的 checkLayoutParams 会自己 generate 成对的那一型；
        // 万一以后布局从 RelativeLayout 换成别的，这里不会再静默失败。
        parent.addView(arc, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        circle.setTag(TAG_ARC, Boolean.TRUE);
    }

    /**
     * 圆环上的进度弧。
     *
     * 两个坑都在这儿踩过，别改回去：
     *   ① 不能 setForeground 画在 ViewCircle 自己身上 —— 它的 onDraw 里 canvas.clipPath
     *      会把前景裁到左上角 1/4 的圆里，整个圆环都跟着糊掉。
     *   ② 圆心不能按自己（match_parent 的容器）算，容器比圆大，实测偏 18px；
     *      必须取 circle.getX()/getY() + 半个圆，坐标系才和圆环一致。
     * circle_normal.png 里的圆环只占控件的 71.5%，所以半径按 0.3575 取。
     */
    private static final class ArcView extends View {

        private final View circleView;
        private final Paint paintRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint paintDone = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                // 1.6.12：show/hide 切页时 Fragment 的 view 不销毁也不 detach，
                // 所以 onDetachedFromWindow 收不到，这个循环会在别的页面上继续跑。
                // 不可见就降频到 3 秒、而且不 invalidate —— 切出去的那几个小时
                // 不该还在每 500ms 唤醒一次 CPU。
                if (isShown()) {
                    invalidate();
                    handler.postDelayed(this, 500L);
                } else {
                    handler.postDelayed(this, 3000L);
                }
            }
        };

        ArcView(Context ctx, View circle) {
            super(ctx);
            this.circleView = circle;
            setClickable(false);
            setFocusable(false);
            paintRing.setStyle(Paint.Style.STROKE);
            paintRing.setStrokeWidth(dp(2));
            // 1.6.12：底环原来是 0x26（15% 白），夜里几乎看不见，
            // 用户根本不知道那儿有进度指示。提到 35%，够看清又不抢戏。
            paintRing.setColor(0x59FFFFFF);
            paintDone.setStyle(Paint.Style.STROKE);
            paintDone.setStrokeWidth(dp(3));
            paintDone.setStrokeCap(Paint.Cap.ROUND);
            paintDone.setColor(0xE6FFFFFF);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            handler.removeCallbacks(tick);
            handler.post(tick);
        }

        @Override
        protected void onDetachedFromWindow() {
            handler.removeCallbacks(tick);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            int total = staticInt("currentMaxShotTimes");
            int done = staticInt("currentShotTimes");
            if (total <= 0) {
                return;
            }
            float p = done / (float) total;
            if (p < 0f) {
                p = 0f;
            }
            if (p > 1f) {
                p = 1f;
            }
            float cx;
            float r;
            if (circleView != null && circleView.getWidth() > 0) {
                cx = circleView.getX() + circleView.getWidth() / 2f;
                r = circleView.getWidth() * 0.3575f + dp(4);
            } else {
                cx = getWidth() / 2f;
                r = Math.min(getWidth(), getHeight()) * 0.3575f + dp(4);
            }
            float cy = circleView != null && circleView.getHeight() > 0
                    ? circleView.getY() + circleView.getHeight() / 2f
                    : getHeight() / 2f;
            if (r <= dp(8)) {
                return;
            }
            oval.set(cx - r, cy - r, cx + r, cy + r);
            c.drawOval(oval, paintRing);
            if (p > 0f) {
                c.drawArc(oval, -90f, 360f * p, false, paintDone);
            }
        }

        private int staticInt(String name) {
            // 1.6.12：必须缓存。这个方法是 onDraw 每 500ms 调两次的，
            // 原来每次都 Class.forName + getField —— 深空一整夜 8 小时就是 11.5 万次反射，
            // 屏幕还亮着，纯白烧电。Class/Field 解析一次就够。
            try {
                Field f;
                if ("currentShotTimes".equals(name)) {
                    if (fDone == null) {
                        fDone = eglobal().getField(name);
                    }
                    f = fDone;
                } else {
                    if (fMax == null) {
                        fMax = eglobal().getField(name);
                    }
                    f = fMax;
                }
                return f.getInt(null);
            } catch (Throwable t) {
                return 0;
            }
        }

        private Class<?> eglobal() throws ClassNotFoundException {
            Class<?> c = sEGlobal;
            if (c == null) {
                c = Class.forName("com.lifeipeng.magicaca.common.EGlobal");
                sEGlobal = c;
            }
            return c;
        }

        private static volatile Class<?> sEGlobal;
        private static volatile Field fDone;
        private static volatile Field fMax;

        private int dp(int v) {
            return UiPatch.dp(getContext(), v);
        }
    }

    // ---------------------------------------------------------------- 5. 隐藏曲线按钮

    private static void hideCurveButtons(View root) {
        Context ctx = root.getContext();
        int idClear = ctx.getResources().getIdentifier("btnClear", "id", ctx.getPackageName());
        int idSync = ctx.getResources().getIdentifier("btnSync", "id", ctx.getPackageName());
        hide(root.findViewById(idClear));
        hide(root.findViewById(idSync));
    }

    private static void hide(View v) {
        if (v != null) {
            v.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------- 6. 常亮

    private static void keepScreenOn(View root) {
        Activity a = activityOf(root.getContext());
        if (a != null) {
            a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ---------------------------------------------------------------- 工具

    private static Activity activityOf(Context ctx) {
        if (ctx instanceof Activity) {
            return (Activity) ctx;
        }
        if (ctx instanceof android.content.ContextWrapper) {
            Context b = ((android.content.ContextWrapper) ctx).getBaseContext();
            if (b instanceof Activity) {
                return (Activity) b;
            }
        }
        return null;
    }

    private static View asView(Object o) {
        return o instanceof View ? (View) o : null;
    }

    static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void log(String where, Throwable t) {
        android.util.Log.e("UiPatch", where + " 失败", t);
    }
}
