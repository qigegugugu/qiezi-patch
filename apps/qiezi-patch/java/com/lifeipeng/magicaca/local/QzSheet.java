package com.lifeipeng.magicaca.local;

import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;

/**
 * 把任意 Dialog 改成「底部弹出」。
 *
 * 为什么必须从代码改：Dialog 的弹出位置由 WindowManager.LayoutParams.gravity 决定，
 * Theme 里没有能设它的属性（android:windowGravity 不存在）。原工程的滚轮弹窗是
 * Holo.Dialog，默认居中 —— 布局做得再像 bottom sheet，窗口还是在屏幕正中间。
 *
 * 时序坑：setLayout 只有在窗口 attach 之后才有意义，所以必须在 show() 之后调用。
 */
public final class QzSheet {

    private QzSheet() {
    }

    public static void apply(Dialog d) {
        try {
            applyInner(d);
        } catch (Throwable ignored) {
            // 位置调不动就把弹窗留在原地，绝不能因为这点装饰让选时间直接崩
        }
    }

    private static void applyInner(Dialog d) {
        if (d == null) {
            return;
        }
        Window w = d.getWindow();
        if (w == null) {
            return;
        }
        w.setGravity(Gravity.BOTTOM);
        w.setBackgroundDrawable(new ColorDrawable(0x00000000));
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0.62f);
        // 贴底满宽：内容自己负责底部安全区的 padding
        w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
    }
}
