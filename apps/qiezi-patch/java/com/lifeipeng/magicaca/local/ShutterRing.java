package com.lifeipeng.magicaca.local;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import java.lang.reflect.Field;

/**
 * 快门环 —— 叠在 ViewCircle 里的一层自绘：外发光 + 绕圈进度弧 + 中央球面高光。
 *
 * 为什么另起一层而不是改 ViewCircle：
 *   ViewCircle 的环是一张 PNG（circle_normal / circle_selected），静态的。
 *   要做「发光 + 绕圈」只能自绘，但把 ViewCircle 整个重写风险太大（它管着触摸回调、
 *   delegate 转发、ViewShutter 的创建），所以只往它里面塞一层，原逻辑一行不动。
 *
 * 三个必须注意的点：
 *   1. <b>attach 必须在 addView(m_shutter) 之前</b>：环在 r≈95dp，叶片盘 r≈31dp，
 *      两者不重叠，所以环"在下面"照样看得见；但中央那层球面高光要垫在叶片底下，
 *      才像叶片嵌在凹槽里。放上面就变成糊了一层白雾。
 *   2. <b>onTouchEvent 必须返回 false</b>：它铺满整个 ViewCircle，返回 true 会把
 *      下面 ImageView 的按下/抬起全吃掉 —— 那就按不动快门了。
 *   3. <b>必须 LAYER_TYPE_SOFTWARE</b>：setShadowLayer 对图形（不是文字）在硬件加速下
 *      直接不生效。200dp 的小 View 用软件层没有性能问题。
 *
 * 进度弧是 indeterminate（不确定进度），只表达"正在拍"。真进度目前拿不到干净的数据源，
 * 与其猜一个假的百分比，不如老实做活动指示。
 */
public class ShutterRing extends View {

    private static final int ACCENT = 0xFF38E1F2;

    private final Paint pArc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDisc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private float phase = 0f;
    private boolean active = false;
    private ValueAnimator anim;

    private float cx, cy, outer;
    private Shader arcShader;

    public ShutterRing(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setClickable(false);
        setFocusable(false);
        pArc.setStyle(Paint.Style.STROKE);
        pArc.setStrokeCap(Paint.Cap.ROUND);
        pDisc.setStyle(Paint.Style.FILL);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    // ---------------------------------------------------------------- 对外接口

    /** 幂等：已经加过就什么都不做。任何异常都吞掉 —— 环是附加效果，不该拖垮界面。 */
    public static void attach(ViewGroup parent) {
        try {
            if (parent == null || find(parent) != null) {
                return;
            }
            ShutterRing r = new ShutterRing(parent.getContext());
            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            // 插到 m_shutter 之前，而不是简单 append：
            // 延后挂载时 m_shutter 早就加进去了，直接 append 会把球面高光盖到叶片<b>上面</b>
            // （看起来像糊了一层白雾）。这里按当前兄弟顺序找位置，插对了效果才和同步挂载一致。
            int idx = -1;
            try {
                View shutter = field(parent, "m_shutter");
                if (shutter != null) {
                    idx = parent.indexOfChild(shutter);
                }
            } catch (Throwable ignored) {
            }
            if (idx >= 0 && idx <= parent.getChildCount()) {
                parent.addView(r, idx, lp);
            } else {
                parent.addView(r, lp);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 延后到第一帧之后再挂 —— 1.7.3 起 ViewCircle 改走这个入口。
     *
     * 为什么：原先是直接在 ViewCircle.initChildren() 里同步 addView。那是<b>构造链上</b>，
     * 此时 ViewCircle 自己还没完成初始化、也还没 attach 到窗口，往里塞一个
     * MATCH_PARENT×MATCH_PARENT 的自绘子 View 会立刻触发 requestLayout/invalidate。
     * 这条路一旦抛错（哪怕是 Error）被 catch 掉，View 树也可能已经处于半吊子状态，
     * 后面测量/绘制就一路错下去 —— 表现就是"刚打开就没了"。
     * 挪到 post() 之后：Activity 已经建完、View 已经 attach，挂不挂得上都只是"有没有光环"的差别。
     */
    public static void deferAttach(final ViewGroup parent) {
        try {
            if (parent == null) {
                return;
            }
            parent.post(new Runnable() {
                @Override
                public void run() {
                    attach(parent);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 拍摄开始/结束时调用；parent 没挂环会先挂上。 */
    public static void setActive(ViewGroup parent, boolean on) {
        try {
            attach(parent);
            ShutterRing r = find(parent);
            if (r != null) {
                r.setActive(on);
            }
        } catch (Throwable ignored) {
        }
    }

    public static ShutterRing find(ViewGroup p) {
        if (p == null) {
            return null;
        }
        // 注意：这里不能抛 —— 调用点都在 UI 线程的构造/测量路径上
        for (int i = 0; i < p.getChildCount(); i++) {
            View c = p.getChildAt(i);
            if (c instanceof ShutterRing) {
                return (ShutterRing) c;
            }
        }
        return null;
    }

    public void setActive(boolean on) {
        if (active == on) {
            return;
        }
        active = on;
        if (on) {
            if (anim == null) {
                anim = ValueAnimator.ofFloat(0f, 1f);
                anim.setDuration(1800L);
                anim.setRepeatCount(ValueAnimator.INFINITE);
                anim.setInterpolator(new LinearInterpolator());
                anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator a) {
                        phase = ((Float) a.getAnimatedValue()).floatValue();
                        invalidate();
                    }
                });
            }
            anim.start();
        } else {
            if (anim != null) {
                anim.cancel();
            }
            phase = 0f;
        }
        invalidate();
    }

    public boolean isActive() {
        return active;
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        try {
            layoutShaders(w, h);
        } catch (Throwable ignored) {
        }
    }

    private void layoutShaders(int w, int h) {
        cx = w / 2f;
        cy = h / 2f;
        outer = Math.min(w, h) / 2f - dp(1.5f);
        oval.set(cx - outer, cy - outer, cx + outer, cy + outer);

        pArc.setStrokeWidth(dp(2.5f));
        pArc.setShadowLayer(dp(7f), 0f, 0f, 0x8C38E1F2);

        // 亮弧只占一圈的 25%（约 90°），前段淡出，才像"跑动的进度"而不是一截电线
        arcShader = new SweepGradient(cx, cy,
                new int[]{0x0038E1F2, 0x0038E1F2, 0xFFEAFDFF, 0xFF38E1F2, 0x0038E1F2},
                new float[]{0f, 0.52f, 0.66f, 0.78f, 1f});

        // 中央球面：高光偏上 → 中段透明 → 边缘暗，叶片看着是嵌在一个球窝里
        pDisc.setShader(new RadialGradient(cx, cy - dp(3f), dp(33f),
                new int[]{0x40FFFFFF, 0x14FFFFFF, 0x00000000, 0x59000000},
                new float[]{0f, 0.40f, 0.74f, 1f},
                Shader.TileMode.CLAMP));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        try {
            // 中央球面高光（垫在叶片下面）
            canvas.drawCircle(cx, cy, dp(33f), pDisc);

            if (active) {
                // 整圈都画，靠 SweepGradient 只让 25% 显形，再整体旋转
                int breathe = (int) (150 + 95 * (0.5f + 0.5f * Math.sin(phase * Math.PI * 2.0 * 3.0)));
                pArc.setAlpha(breathe);
                if (arcShader != null) {
                    pArc.setShader(arcShader);
                }
                canvas.save();
                canvas.rotate(phase * 360f, cx, cy);
                canvas.drawArc(oval, 0f, 360f, false, pArc);
                canvas.restore();
            } else {
                // 静息：一圈几乎看不见的细环，只把 PNG 环的边缘收个口
                pArc.setAlpha(255);
                pArc.setShader(null);
                pArc.setColor(0x14FFFFFF);
                canvas.drawArc(oval, 0f, 360f, false, pArc);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 按下反馈

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            try {
                twistShutter();
            } catch (Throwable ignored) {
            }
        }
        // 关键：返回 false，让事件继续落到下面那个 ImageView
        return false;
    }

    /** 叶片轻轻拧一下再回正 —— 按下时的"光圈收一下"手感。 */
    private void twistShutter() {
        ViewGroup parent = getParent() instanceof ViewGroup ? (ViewGroup) getParent() : null;
        final View shutter = field(parent, "m_shutter");
        if (shutter == null) {
            return;
        }
        shutter.animate().cancel();
        shutter.animate().rotation(26f).setDuration(110L)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        shutter.animate().rotation(0f)
                                .setDuration(340L)
                                .setInterpolator(new DecelerateInterpolator(1.6f))
                                .start();
                    }
                }).start();
    }

    private static View field(Object o, String name) {
        if (o == null) {
            return null;
        }
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(o);
                return v instanceof View ? (View) v : null;
            } catch (NoSuchFieldException nf) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
