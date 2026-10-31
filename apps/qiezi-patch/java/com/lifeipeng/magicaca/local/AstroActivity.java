package com.lifeipeng.magicaca.local;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * 1.6.27：深空从「弹窗」改成「整页」。
 *
 * 用户反馈：其它功能都是整页切换，只有深空是弹出来的浮层，切换感割裂。
 * 这里把面板内容装进一个标准 Activity，进出用短淡入淡出，观感与翻页一致，
 * 同时保留面板自身的夜视配色（Activity 有独立窗口，不受主界面 RenderEffect 影响）。
 */
public class AstroActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Throwable ignore) {
        }
        try {
            // 主题是 Theme.NoTitleBar，默认窗口底是 #303030，进页会闪一下灰。
            // 1.7.7：底色跟主界面走（#08090C），不再用纯黑，进出不跳色。
            getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0xFF08090C));
            getWindow().setStatusBarColor(0xFF08090C);
            getWindow().setNavigationBarColor(0xFF08090C);
        } catch (Throwable ignore) {
        }
        View page = AstroPanel.createView(this);
        setContentView(page);
    }

    @Override
    public void onBackPressed() {
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
