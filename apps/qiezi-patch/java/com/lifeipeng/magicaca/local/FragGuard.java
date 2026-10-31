package com.lifeipeng.magicaca.local;

import android.app.Fragment;
import android.app.FragmentManager;
import android.util.Log;
import android.view.View;

import java.util.List;

/**
 * 1.6.27：页面叠加的兜底。
 *
 * 现象：用户从「曲线延时」切到别的页面后，曲线页的标题/曲线/快门环还浮在新页面上
 * （因为 11 个 fragment 里只有曲线页自己有黑底，其它页根布局是透明的，一叠就穿）。
 *
 * 官方的切页逻辑本身会 hide 全部 fragment，但 hide 是异步事务，某些回调里会重新
 * 把 view 设回 VISIBLE。这里在每次切页后强制走一遍：先执行挂起事务，再把
 * 「已经 isHidden() 但 view 还可见」的一律设 GONE。不碰当前显示的页（它的 isHidden 是 false）。
 */
public final class FragGuard {

    private static final String TAG = "QzFragGuard";

    private FragGuard() {
    }

    public static void collapse(android.app.Activity act) {
        if (act == null) {
            return;
        }
        try {
            FragmentManager fm = act.getFragmentManager();
            if (fm == null) {
                return;
            }
            fm.executePendingTransactions();
            List<Fragment> list = fm.getFragments();
            if (list == null) {
                return;
            }
            int fixed = 0;
            for (Fragment f : list) {
                if (f == null || !f.isHidden()) {
                    continue;
                }
                View v = f.getView();
                if (v != null && v.getVisibility() != View.GONE) {
                    v.setVisibility(View.GONE);
                    fixed++;
                }
            }
            if (fixed > 0) {
                Log.i(TAG, "收掉 " + fixed + " 个残留页面视图");
            }
        } catch (Throwable t) {
            Log.w(TAG, "collapse 失败：" + t);
        }
    }
}
