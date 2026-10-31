package com.lifeipeng.magicaca.local;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 深空面板 —— 独立的固定页面，跟 B 门延时互不干扰。
 *
 * 前提（用户的实际用法）：相机固定在 M 档 + B 门，光圈 ISO 在机身上设好，
 * 快门开多久完全由盒子按住遥控线的时间决定。所以这个面板里所有「曝光」
 * 都是盒子真正按下去多少秒，不是给相机下参数（那也做不到）。
 *
 * 入口（1.6.24 起）：
 *   主入口 = 左侧抽屉菜单「单拍」下面那一项「深空」，由 {@link #installMenu} 运行时插进去。
 *   单拍页右上角的小按钮已经撤掉 —— 用户反馈那个位置根本找不到。
 *
 * 界面（1.6.24 重做，参照索尼 Creators' App 的语言）：
 *   大标题 + 副标题 / 分段胶囊（找星·阶梯·预约）/ 圆角卡片行 / 圆形主按钮。
 *   一屏只显示一块，不再是一条长列表滚到底。
 *   夜视联动：主界面开着「红光」时，本面板自动换暗红配色 —— Dialog 有独立的 window，
 *   主界面 decorView 上的 RenderEffect 管不到这里，必须自己算。
 *
 * 三块：
 *   找星     —— 选一个曝光（1~10 秒为主），拍 1 张。看得到就看得到，看不到就挪镜头再点。
 *   曝光阶梯 —— 逐档指定曝光（默认 15/30/60/120），每档连拍几张，自动跑完。
 *   定时预约 —— 选一个具体时刻开拍 1 张，由固件自己等，手机可以锁屏、可以退出。
 *
 * 所有参数都存在 qiezi_prefs 里，下次打开还在。
 */
public final class AstroPanel {

    private static final String TAG = "QIEZI";
    private static final String P = "qiezi_prefs";

    private static final String K_STAR = "astro_star_sec";
    private static final String K_L0 = "astro_lad0";
    private static final String K_L1 = "astro_lad1";
    private static final String K_L2 = "astro_lad2";
    private static final String K_L3 = "astro_lad3";
    private static final String K_L4 = "astro_lad4";
    private static final String K_L5 = "astro_lad5";
    private static final String K_STEPS = "astro_steps";
    private static final String K_SHOTS = "astro_shots";
    private static final String K_GAP = "astro_gap";
    private static final String K_BH = "astro_book_h";
    private static final String K_WAKE = "astro_wake";
    private static final String K_BM = "astro_book_m";

    /** 主界面的夜视（红光）开关，1.6.12 就有，长按标题切换。 */
    private static final String K_NIGHT = "night_mode";

    private static final String TAG_MENU = "qiezi_astro_menu_entry";

    /** 时间选择网格（秒）。1~10 秒是找星的主力区间，放在最前面。 */
    private static final int[] SEC_GRID = {
            1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 25,
            30, 40, 50, 60, 90, 120, 180, 240, 300, 450, 600, 900};

    private static final int[] STEP_CHOICES = {3, 4, 5, 6};
    private static final int[] SHOT_CHOICES = {1, 2, 3, 5};
    private static final int[] GAP_CHOICES = {2, 3, 5, 10};

    // ---------------- 当前参数 ----------------
    private static int sStarSec = 2;
    private static final int[] sLadder = {15, 30, 60, 120, 240, 480};
    private static int sSteps = 4;
    private static int sShots = 2;
    private static int sGap = 3;
    private static int sBookH = -1;   // -1 = 还没定过，用「现在 +30 分钟」
    private static int sBookM = 0;
    private static boolean sWakeBefore = true;   // 拍摄前先半按唤醒相机

    private static boolean sLoaded = false;
    private static boolean sLadderRunning = false;
    private static Dialog sDialog = null;

    private static LinearLayout sHost = null;        // 分段下方的内容容器
    private static LinearLayout sSegBar = null;      // 分段胶囊容器
    private static int sTab = 0;                     // 0 找星 / 1 阶梯 / 2 预约

    private static TextView sLadderBtn = null;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private AstroPanel() {
    }

    private interface IntSink {
        void onPick(int v);
    }

    private interface ClockSink {
        void onPick(int hour, int minute);
    }

    // ============================================================ 配色

    /**
     * 一套配色。Sony Creators' App 的做法是「近黑底 + 一层浅一点的圆角卡片 + 极细分割线」，
     * 颜色只有三种：正文近白、说明灰、强调色一种。这里照着来。
     */
    private static final class Pal {
        int bg;         // 页面底
        int card;       // 卡片
        int cardHi;     // 卡片按下
        int line;       // 分割线
        int text;       // 正文
        int dim;        // 说明
        int accent;     // 强调
        int onAccent;   // 强调底上的字
        int segBg;      // 分段容器
        int segOn;      // 分段选中
        int glassEdge;  // 液态玻璃的高光边
    }

    private static Pal pal(Context ctx) {
        Pal p = new Pal();
        if (nightOn(ctx)) {
            // 红光：只保留红通道，整体压暗。深空现场看白屏，暗视要 20 分钟才回来。
            p.bg = 0xFF070202;
            p.card = 0xFF140505;
            p.cardHi = 0xFF1F0808;
            p.line = 0xFF2A0B0B;
            p.text = 0xFFD4574A;
            p.dim = 0xFF7E3129;
            p.accent = 0xFFE0503F;
            p.onAccent = 0xFF0A0000;
            p.segBg = 0xFF120404;
            p.segOn = 0xFF2A0C0A;
            p.glassEdge = 0x40E0503F;
        } else {
            // 1.7.7：对齐主界面 Lumen Dark 色板（底 #08090C / 玻璃卡 / 强调青 #38E1F2）。
            // 之前用的是 iOS 旧色板（纯黑 + #0A84FF），从抽屉进深空像是换了个 App。
            p.bg = 0xFF08090C;
            p.card = 0x1FFFFFFF;
            p.cardHi = 0x2EFFFFFF;
            p.line = 0x2EFFFFFF;
            p.text = 0xFFF5F7FA;
            p.dim = 0xFF9AA2AE;
            p.accent = 0xFF38E1F2;
            p.onAccent = 0xFF04141A;
            p.segBg = 0x14FFFFFF;
            p.segOn = 0x26FFFFFF;
            p.glassEdge = 0x24FFFFFF;
        }
        return p;
    }

    private static boolean nightOn(Context ctx) {
        try {
            Object o = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getAll().get(K_NIGHT);
            return (o instanceof Boolean) && ((Boolean) o);
        } catch (Throwable t) {
            return false;
        }
    }

    // ============================================================ 左侧菜单入口

    /**
     * 把「深空」插进左侧抽屉菜单，位置在「单拍」下面 —— 那里不用滚动就能看见。
     *
     * 不改 XML、不加资源 ID：运行时找 menu_single，拿它的父容器，插在它后面。
     * 用 tag 防重复（Activity 重建时会再调一次）。
     */
    public static void installMenu(android.app.Activity act) {
        if (act == null) {
            return;
        }
        try {
            final Pal p = pal(act);
            int anchor = act.getResources().getIdentifier("menu_single", "id", act.getPackageName());
            if (anchor == 0) {
                Log.w(TAG, "左侧菜单：找不到 menu_single，深空入口没插上");
                return;
            }
            View single = act.findViewById(anchor);
            if (single == null) {
                return;
            }
            ViewParent vp = single.getParent();
            if (!(vp instanceof ViewGroup)) {
                return;
            }
            final ViewGroup parent = (ViewGroup) vp;
            if (parent.findViewWithTag(TAG_MENU) != null) {
                return;   // 已经插过了
            }

            restyleMenuRows(act, parent, p);

            final TextView item = new TextView(act);
            item.setTag(TAG_MENU);
            item.setText("深空");
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            item.setTextColor(p.accent);
            item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            item.setSingleLine(true);
            item.setClickable(true);
            item.setPadding(dp(act, 20), 0, dp(act, 16), 0);
            item.setBackground(rowBg(act, p));

            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 44));
            parent.addView(item, parent.indexOfChild(single) + 1, lp);

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Context c = v.getContext();
                    hideDrawer(v);
                    open(c);
                }
            });
            Log.i(TAG, "左侧菜单已加入「深空」入口");
        } catch (Throwable t) {
            Log.w(TAG, "深空菜单入口挂载失败：" + t);
        }
    }

    /**
     * 点完随手把抽屉收起来，不然关掉面板会看到一张开着的菜单。
     *
     * 注意**不能用 `hideAllMenu()`**：那个方法除了把抽屉移出屏幕，还会把整个
     * `m_panelMenuLeft` 设成 GONE；而汉堡按钮走的 `menuLeftClickHandler()` 只做 X 位移动画、
     * 从不把 view 设回 VISIBLE —— 一旦 GONE 就再也弹不出来，只能重启 App。
     * 1.6.24 就是这么踩的。这里照官方收起的方式办：只挪 X + 清标志位，保持 VISIBLE。
     */
    private static void hideDrawer(View v) {
        try {
            Object act = v.getContext();
            View panel = drawerView(act, "m_panelMenuLeft");
            View inner = drawerView(act, "m_panelMenuLeftInner");
            int w = inner != null ? inner.getWidth() : 0;
            if (w <= 0 && panel != null) {
                w = panel.getWidth();
            }
            if (panel != null && w > 0) {
                panel.animate().x(-w).setDuration(60).start();
            }
            setMenuFlag(act, "isMenuLeftVisible", false);
            setMenuFlag(act, "isMenuRightVisible", false);
        } catch (Throwable ignore) {
        }
    }

    private static View drawerView(Object act, String name) {
        try {
            java.lang.reflect.Field f = act.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object o = f.get(act);
            return o instanceof View ? (View) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setMenuFlag(Object act, String name, boolean value) {
        try {
            java.lang.reflect.Field f = act.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.setBoolean(act, value);
        } catch (Throwable ignore) {
        }
    }

    /**
     * 抽屉里原来是 15 个「灰色方块」堆起来的按钮，改一处漏一处，所以运行时统一重排：
     * 透明行 + 左对齐 + 圆角按下态。这是 Creators' App 的做法 —— 列表靠留白分隔，不靠色块。
     */
    private static void restyleMenuRows(android.app.Activity act, ViewGroup parent, Pal p) {
        int rowH = dp(act, 44);
        for (int i = 0; i < parent.getChildCount(); i++) {
            View v = parent.getChildAt(i);
            if (!(v instanceof TextView)) {
                continue;
            }
            TextView t = (TextView) v;
            if (t.getText() == null || t.getText().length() == 0) {
                continue;      // 布局末尾那个纯占位的空行
            }
            ViewGroup.LayoutParams lp = t.getLayoutParams();
            if (lp != null) {
                lp.height = rowH;
                t.setLayoutParams(lp);
            }
            t.setBackground(rowBg(act, p));
            t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            t.setPadding(dp(act, 20), 0, dp(act, 16), 0);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            t.setTextColor(p.text);
        }
    }

    /** 列表行背景：常态透明，按下淡卡片 + 10dp 圆角。 */
    private static StateListDrawable rowBg(Context ctx, Pal p) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(ctx, p.cardHi, dp(ctx, 10)));
        s.addState(new int[]{}, round(ctx, 0x00000000, dp(ctx, 10)));
        return s;
    }

    // ============================================================ 主面板

    /** 1.6.27：深空改成整页 Activity —— 与其它功能一样是"翻页"，不再浮在界面上。 */
    public static void open(Context ctx) {
        if (!(ctx instanceof android.app.Activity)) {
            Log.w(TAG, "深空页需要 Activity 上下文");
            return;
        }
        try {
            android.content.Intent i = new android.content.Intent(ctx, AstroActivity.class);
            ctx.startActivity(i);
            ((android.app.Activity) ctx).overridePendingTransition(
                    android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable t) {
            Log.w(TAG, "打开深空页失败：" + t);
            toast(ctx, "打开失败：" + t);
        }
    }

    /** 由 AstroActivity 调用，返回整页内容。 */
    public static View createView(final Context ctx) {
        load(ctx);
        final Pal p = pal(ctx);
        sDialog = null;

        LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(p.bg);

        // ---------- 页头 ----------
        page.addView(header(ctx, p, null));

        // ---------- 分段 ----------
        sSegBar = new LinearLayout(ctx);
        sSegBar.setOrientation(LinearLayout.HORIZONTAL);
        sSegBar.setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4));
        GradientDrawable segBgDrawable = round(ctx, p.segBg, dp(ctx, 19));
        segBgDrawable.setStroke(1, p.glassEdge);
        sSegBar.setBackground(segBgDrawable);
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 38));
        segLp.leftMargin = dp(ctx, 20);
        segLp.rightMargin = dp(ctx, 20);
        segLp.topMargin = dp(ctx, 6);
        sSegBar.setLayoutParams(segLp);
        String[] tabs = {"找星", "阶梯", "预约"};
        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            TextView t = new TextView(ctx);
            t.setText(tabs[i]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            t.setGravity(Gravity.CENTER);
            t.setClickable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(ctx, 30), 1f);
            t.setLayoutParams(lp);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sTab = idx;
                    paintSeg(v.getContext());
                    rebuild(v.getContext());
                }
            });
            sSegBar.addView(t);
        }
        page.addView(sSegBar);

        // ---------- 内容（滚动） ----------
        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(true);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        sHost = new LinearLayout(ctx);
        sHost.setOrientation(LinearLayout.VERTICAL);
        sHost.setPadding(dp(ctx, 20), dp(ctx, 14), dp(ctx, 20), dp(ctx, 28));
        sv.addView(sHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(sv);

        paintSeg(ctx);
        rebuild(ctx);
        return page;
    }

    /** 关闭整页。 */
    static void closePage(View v) {
        Context c = v.getContext();
        try {
            save(c);
        } catch (Throwable ignore) {
        }
        sHost = null;
        sSegBar = null;
        sLadderBtn = null;
        if (c instanceof android.app.Activity) {
            android.app.Activity a = (android.app.Activity) c;
            a.finish();
            a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
    }

    private static View header(Context ctx, final Pal p, final Dialog d) {
        // 1.7.7：页头改成与主界面 ViewPanelTop 同构 —— 58dp、标题居中、副标题居中，
        // 返回键贴在左上角（原来用右对齐标题 + 右上角 ✕，进页像换了个 App）。
        FrameLayout head = new FrameLayout(ctx);
        head.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 58)));

        LinearLayout titles = new LinearLayout(ctx);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        TextView title = new TextView(ctx);
        title.setText("深空");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        title.setTextColor(p.text);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setIncludeFontPadding(false);
        title.setGravity(Gravity.CENTER);
        titles.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText("M 档 + B 门 · 曝光靠按住的时长");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        sub.setTextColor(p.dim);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sub.setLayoutParams(subLp);
        titles.addView(sub);
        head.addView(titles);

        TextView close = new TextView(ctx);
        close.setText("‹");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        close.setTextColor(p.text);
        close.setGravity(Gravity.CENTER);
        close.setClickable(true);
        GradientDrawable cg = new GradientDrawable();
        cg.setShape(GradientDrawable.OVAL);
        cg.setColor(0x00000000);
        close.setBackground(cg);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44),
                Gravity.START | Gravity.CENTER_VERTICAL);
        clp.leftMargin = dp(ctx, 6);
        close.setLayoutParams(clp);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (d != null) {
                    d.dismiss();
                } else {
                    closePage(v);
                }
            }
        });
        head.addView(close);
        return head;
    }

    private static void paintSeg(Context ctx) {
        if (sSegBar == null) {
            return;
        }
        Pal p = pal(ctx);
        for (int i = 0; i < sSegBar.getChildCount(); i++) {
            TextView t = (TextView) sSegBar.getChildAt(i);
            boolean on = i == sTab;
            t.setTextColor(on ? p.text : p.dim);
            if (on) {
                t.setBackground(round(ctx, p.segOn, dp(ctx, 15)));
                t.setTypeface(null, android.graphics.Typeface.BOLD);
            } else {
                t.setBackground(null);
                t.setTypeface(null, android.graphics.Typeface.NORMAL);
            }
        }
    }

    private static void rebuild(Context ctx) {
        if (sHost == null) {
            return;
        }
        sHost.removeAllViews();
        if (sTab == 0) {
            buildStar(ctx, sHost);
        } else if (sTab == 1) {
            buildLadder(ctx, sHost);
        } else {
            buildBook(ctx, sHost);
        }
    }

    // ============================================================ 找星

    private static void buildStar(Context ctx, LinearLayout host) {
        final Pal p = pal(ctx);

        // 曝光读数：一眼能看清的大数字，按住不放的时长就是这个
        TextView big = new TextView(ctx);
        big.setText(fmtSec(sStarSec));
        big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f);
        big.setTextColor(p.text);
        big.setGravity(Gravity.CENTER);
        big.setTypeface(null, android.graphics.Typeface.BOLD);
        big.setIncludeFontPadding(false);
        LinearLayout.LayoutParams bigLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bigLp.topMargin = dp(ctx, 10);
        big.setLayoutParams(bigLp);
        host.addView(big);

        TextView bigSub = new TextView(ctx);
        bigSub.setText("盒子按住快门 " + fmtSec(sStarSec));
        bigSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        bigSub.setTextColor(p.dim);
        bigSub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bsLp.topMargin = dp(ctx, 6);
        bsLp.bottomMargin = dp(ctx, 22);
        bigSub.setLayoutParams(bsLp);
        host.addView(bigSub);

        // 圆形快门
        host.addView(shutter(ctx, p), centered(ctx));
        host.addView(hint(ctx, p, "拍 1 张。看得到就看得到，看不到就挪镜头再点。"), centeredWrap(ctx));

        LinearLayout c = card(ctx, p);
        c.setLayoutParams(cardLp(ctx, 22));
        c.addView(row(ctx, p, "曝光时长", fmtSec(sStarSec) + "  ▸", p.accent,
                new View.OnClickListener() {
                    @Override
                    public void onClick(final View v) {
                        pickSeconds(v.getContext(), "找星曝光", sStarSec, new IntSink() {
                            @Override
                            public void onPick(int val) {
                                sStarSec = val;
                                save(v.getContext());
                                rebuild(v.getContext());
                            }
                        });
                    }
                }, false));
        c.addView(divider(ctx, p));
        c.addView(row(ctx, p, "拍前唤醒", sWakeBefore ? "开  ▸" : "关  ▸",
                sWakeBefore ? p.accent : p.dim, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sWakeBefore = !sWakeBefore;
                        AstroEngine.wakeBeforeShoot = sWakeBefore;
                        save(v.getContext());
                        rebuild(v.getContext());
                        toast(v.getContext(), sWakeBefore
                                ? "每张前先半按 0.9 秒唤醒，快门会晚约 1.5 秒"
                                : "不自动唤醒：相机待机时曝光会被削成 0.1 秒");
                    }
                }, false));
        c.addView(divider(ctx, p));
        c.addView(row(ctx, p, "只唤醒（不拍）", "执行  ▸", p.text, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Context cx = v.getContext();
                if (!AstroEngine.connected(cx)) {
                    toast(cx, "盒子没连上，先在首页连设备");
                    return;
                }
                boolean ok = AstroEngine.wakeOnly(cx);
                toast(cx, ok ? "半按唤醒，0.9 秒后松开（不拍）" : "正在下发，稍等一下");
            }
        }, true));
        host.addView(c);

        host.addView(hint(ctx, p, "相机待机黑屏时开帘要晚约 1.9 秒，2 秒的按压实际只曝 0.1 秒 —— "
                + "这就是要「拍前唤醒」的原因。"), topGap(ctx, 16));
    }

    private static View shutter(Context ctx, final Pal p) {
        FrameLayout wrap = new FrameLayout(ctx);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 116), dp(ctx, 116)));

        // 外圈：细描边，按下时消失，像 Creators 的快门
        final View ring = new View(ctx);
        GradientDrawable rg = new GradientDrawable();
        rg.setShape(GradientDrawable.OVAL);
        rg.setColor(0x00000000);
        rg.setStroke(dp(ctx, 2), (p.accent & 0x00FFFFFF) | 0x55000000);
        ring.setBackground(rg);
        FrameLayout.LayoutParams rgLp = new FrameLayout.LayoutParams(dp(ctx, 116), dp(ctx, 116));
        ring.setLayoutParams(rgLp);
        wrap.addView(ring);

        final TextView btn = new TextView(ctx);
        btn.setText("拍 1 张");
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        btn.setTextColor(p.onAccent);
        btn.setGravity(Gravity.CENTER);
        btn.setClickable(true);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        final GradientDrawable sg = new GradientDrawable();
        sg.setShape(GradientDrawable.OVAL);
        sg.setColor(p.accent);
        btn.setBackground(sg);
        FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(dp(ctx, 88), dp(ctx, 88));
        bLp.gravity = Gravity.CENTER;
        btn.setLayoutParams(bLp);
        btn.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start();
                    ring.setAlpha(0.3f);
                } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP
                        || e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    ring.setAlpha(1f);
                }
                return false;
            }
        });
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Context c = v.getContext();
                if (!AstroEngine.connected(c)) {
                    toast(c, "盒子没连上，先在首页连设备");
                    return;
                }
                if (sLadderRunning) {
                    toast(c, "阶梯还在跑，等它走完再拍");
                    return;
                }
                if (AstroEngine.busy()) {
                    toast(c, "上一张还在下发，稍等一下");
                    return;
                }
                boolean ok = AstroEngine.shootBulbOne(c, sStarSec * 1000);
                toast(c, ok ? ("已下发：曝光 " + fmtSec(sStarSec) + "，马上按快门")
                        : "下发失败，看日志 QIEZI");
            }
        });
        wrap.addView(btn);
        return wrap;
    }

    // ============================================================ 曝光阶梯

    private static void buildLadder(Context ctx, LinearLayout host) {
        final Pal p = pal(ctx);

        host.addView(heading(ctx, p, "逐档指定曝光", "自动连跑一遍，拍完挑最合适的那一档，当晚就按这个时长拍正式序列。"));

        LinearLayout c = card(ctx, p);
        c.setLayoutParams(cardLp(ctx, 0));
        c.addView(row(ctx, p, "档数", sSteps + " 档  ▸", p.accent, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sSteps = next(STEP_CHOICES, sSteps);
                save(v.getContext());
                rebuild(v.getContext());
            }
        }, false));
        c.addView(divider(ctx, p));
        c.addView(row(ctx, p, "每档张数", sShots + " 张  ▸", p.accent, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sShots = next(SHOT_CHOICES, sShots);
                save(v.getContext());
                rebuild(v.getContext());
            }
        }, false));
        c.addView(divider(ctx, p));
        c.addView(row(ctx, p, "空档（拍完等多久）", fmtSec(sGap) + "  ▸", p.accent,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sGap = next(GAP_CHOICES, sGap);
                        save(v.getContext());
                        rebuild(v.getContext());
                    }
                }, true));
        host.addView(c);

        // 每档一行
        TextView secHead = new TextView(ctx);
        secHead.setText("各档曝光");
        secHead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        secHead.setTextColor(p.dim);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = dp(ctx, 22);
        hl.bottomMargin = dp(ctx, 8);
        hl.leftMargin = dp(ctx, 4);
        secHead.setLayoutParams(hl);
        host.addView(secHead);

        final LinearLayout lc = card(ctx, p);
        lc.setLayoutParams(cardLp(ctx, 0));
        for (int i = 0; i < sSteps; i++) {
            final int idx = i;
            LinearLayout r = row(ctx, p, "第 " + (i + 1) + " 档", fmtSec(sLadder[i]) + "  ▸", p.text,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(final View v) {
                            pickSeconds(v.getContext(), "第 " + (idx + 1) + " 档曝光", sLadder[idx],
                                    new IntSink() {
                                        @Override
                                        public void onPick(int val) {
                                            sLadder[idx] = val;
                                            save(v.getContext());
                                            rebuild(v.getContext());
                                        }
                                    });
                        }
                    }, i == sSteps - 1);
            lc.addView(r);
            if (i < sSteps - 1) {
                lc.addView(divider(ctx, p));
            }
        }
        host.addView(lc);

        // 预估
        TextView est = new TextView(ctx);
        est.setText(estimateText());
        est.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        est.setTextColor(p.dim);
        est.setLineSpacing(dp(ctx, 2), 1f);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(ctx, 16);
        elp.leftMargin = dp(ctx, 4);
        est.setLayoutParams(elp);
        host.addView(est);

        // 主按钮
        TextView go = primary(ctx, p, sLadderRunning ? "阶梯进行中…" : "开始阶梯", sLadderRunning ? p.dim : p.onAccent,
                sLadderRunning ? 0x1FFFFFFF : p.accent);
        sLadderBtn = go;
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sLadderRunning) {
                    toast(v.getContext(), "阶梯还在跑，等它走完");
                    return;
                }
                startLadder(v.getContext());
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 52));
        glp.topMargin = dp(ctx, 22);
        go.setLayoutParams(glp);
        host.addView(go);
    }

    private static String estimateText() {
        StringBuilder sb = new StringBuilder("将跑：");
        for (int i = 0; i < sSteps; i++) {
            sb.append(sLadder[i]).append("s");
            if (i < sSteps - 1) {
                sb.append(" → ");
            }
        }
        sb.append("\n每档 ").append(sShots).append(" 张，共 ").append(sSteps * sShots)
                .append(" 张，约 ").append(human(ladderTotalMs()));
        return sb.toString();
    }

    private static String previewNums() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sSteps; i++) {
            if (i > 0) {
                sb.append("/");
            }
            sb.append(sLadder[i]).append("s");
        }
        return sb.toString();
    }

    private static int ladderTotalMs() {
        int total = 0;
        for (int i = 0; i < sSteps; i++) {
            total += AstroEngine.stepDurationMs(sLadder[i] * 1000, sGap, sShots)
                    + AstroEngine.SLACK_MS;
        }
        return total;
    }

    private static void startLadder(final Context ctx) {
        if (!AstroEngine.connected(ctx)) {
            toast(ctx, "盒子没连上，先在首页连设备");
            return;
        }
        if (sLadderRunning) {
            toast(ctx, "阶梯还在跑，等它走完");
            return;
        }
        final int steps = sSteps;
        final int shots = sShots;
        final int gap = sGap;

        sLadderRunning = true;
        save(ctx);
        setLadderBtn("阶梯 1/" + steps);
        Log.i(TAG, "曝光阶梯开始：" + previewNums() + "，每档 " + shots + " 张，空档 " + gap + "s");

        int offset = 0;
        for (int i = 0; i < steps; i++) {
            final int idx = i;
            final int expMs = sLadder[i] * 1000;
            sHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    boolean ok = AstroEngine.sendBulbTask(ctx, expMs, gap, shots,
                            AstroEngine.FLAG_DEFAULT);
                    Log.i(TAG, "阶梯 " + (idx + 1) + "/" + steps + "：曝光 " + (expMs / 1000)
                            + "s ×" + shots + (ok ? " 已下发" : " 下发失败"));
                    setLadderBtn("阶梯 " + (idx + 1) + "/" + steps + (ok ? "" : " 失败"));
                    if (idx == 0) {
                        toast(ctx, "阶梯开始，共约 " + human(ladderTotalMs()));
                    }
                }
            }, offset);
            offset += AstroEngine.stepDurationMs(expMs, gap, shots) + AstroEngine.SLACK_MS;
        }

        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                sLadderRunning = false;
                setLadderBtn("开始阶梯");
                toast(ctx, "阶梯跑完了，" + steps + " 档各 " + shots + " 张");
                Log.i(TAG, "曝光阶梯结束");
            }
        }, offset);
    }

    private static void setLadderBtn(String s) {
        if (sLadderBtn != null) {
            sLadderBtn.setText(s);
        }
    }

    // ============================================================ 定时预约

    private static void buildBook(Context ctx, LinearLayout host) {
        final Pal p = pal(ctx);

        host.addView(heading(ctx, p, "定时预约", "到点由固件自己按一次，手机可以锁屏、可以退出 App。曝光用「找星」那个时长。"));

        TextView clock = new TextView(ctx);
        clock.setText(sBookH < 0 ? "-- : --" : hmText(sBookH, sBookM));
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f);
        clock.setTextColor(p.text);
        clock.setGravity(Gravity.CENTER);
        clock.setTypeface(null, android.graphics.Typeface.BOLD);
        clock.setIncludeFontPadding(false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(ctx, 6);
        clock.setLayoutParams(clp);
        host.addView(clock);

        TextView cd = new TextView(ctx);
        cd.setText(sBookH < 0 ? "还没选时刻" : ("距开拍 " + human(bookDelaySec() * 1000)));
        cd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        cd.setTextColor(sBookH < 0 ? p.dim : p.accent);
        cd.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cdLp.topMargin = dp(ctx, 8);
        cdLp.bottomMargin = dp(ctx, 22);
        cd.setLayoutParams(cdLp);
        host.addView(cd);

        LinearLayout c = card(ctx, p);
        c.setLayoutParams(cardLp(ctx, 0));
        c.addView(row(ctx, p, "开拍时刻", (sBookH < 0 ? "选时刻" : hmText(sBookH, sBookM)) + "  ▸",
                p.accent, new View.OnClickListener() {
                    @Override
                    public void onClick(final View v) {
                        pickClock(v.getContext(), sBookH, sBookM, new ClockSink() {
                            @Override
                            public void onPick(int h, int m) {
                                sBookH = h;
                                sBookM = m;
                                save(v.getContext());
                                rebuild(v.getContext());
                            }
                        });
                    }
                }, false));
        c.addView(divider(ctx, p));
        c.addView(row(ctx, p, "曝光", fmtSec(sStarSec) + "  ▸", p.text, new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                pickSeconds(v.getContext(), "预约曝光", sStarSec, new IntSink() {
                    @Override
                    public void onPick(int val) {
                        sStarSec = val;
                        save(v.getContext());
                        rebuild(v.getContext());
                    }
                });
            }
        }, true));
        host.addView(c);

        // 待机警告 —— 这一段是现场踩过的坑，不能省略
        LinearLayout warn = new LinearLayout(ctx);
        warn.setOrientation(LinearLayout.HORIZONTAL);
        warn.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));
        GradientDrawable wg = round(ctx, p.card, dp(ctx, 12));
        wg.setStroke(dp(ctx, 1), (p.accent & 0x00FFFFFF) | 0x44000000);
        warn.setBackground(wg);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(ctx, 16);
        warn.setLayoutParams(wlp);

        TextView wl = new TextView(ctx);
        wl.setText("!");
        wl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        wl.setTextColor(p.accent);
        wl.setTypeface(null, android.graphics.Typeface.BOLD);
        wl.setGravity(Gravity.CENTER);
        GradientDrawable dotg = new GradientDrawable();
        dotg.setShape(GradientDrawable.OVAL);
        dotg.setColor((p.accent & 0x00FFFFFF) | 0x22000000);
        wl.setBackground(dotg);
        warn.addView(wl, new LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20)));

        TextView wt = new TextView(ctx);
        wt.setText("相机若已待机，这一按会被削成 0.1 秒且没法补救。预约前把机身"
                + "「MENU → 设置 → 电源设置选项 → 自动关机」设为关。");
        wt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        wt.setTextColor(p.dim);
        wt.setLineSpacing(dp(ctx, 3), 1f);
        LinearLayout.LayoutParams wtLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wtLp.leftMargin = dp(ctx, 10);
        wt.setLayoutParams(wtLp);
        warn.addView(wt);
        host.addView(warn);

        TextView go = primary(ctx, p, "预约这一刻", p.onAccent, p.accent);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Context cx = v.getContext();
                if (sBookH < 0) {
                    toast(cx, "先点上面「开拍时刻」定一个时间");
                    return;
                }
                if (!AstroEngine.connected(cx)) {
                    toast(cx, "盒子没连上，先在首页连设备");
                    return;
                }
                int delay = bookDelaySec();
                boolean ok = AstroEngine.shootAfter(cx, delay);
                toast(cx, ok ? ("已预约：" + hmText(sBookH, sBookM) + " 开拍（约 "
                        + human(delay * 1000) + "后）") : "下发失败，看日志 QIEZI");
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 52));
        glp.topMargin = dp(ctx, 22);
        go.setLayoutParams(glp);
        host.addView(go);
    }

    // ============================================================ 时间选择器

    /** 秒数网格选择器：常用值一点即选，也可以自己填。 */
    private static void pickSeconds(final Context ctx, String title, int current, final IntSink sink) {
        final Pal p = pal(ctx);
        try {
            final Dialog d = new Dialog(ctx, android.R.style.Theme_DeviceDefault_NoActionBar);
            ScrollView sv = new ScrollView(ctx);
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(p.bg);
            root.setPadding(dp(ctx, 20), dp(ctx, 22), dp(ctx, 20), dp(ctx, 24));
            sv.addView(root, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView t = new TextView(ctx);
            t.setText(title);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
            t.setTextColor(p.text);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            t.setIncludeFontPadding(false);
            root.addView(t);

            TextView dsc = new TextView(ctx);
            dsc.setText("点一个常用值，或在下面直接填秒数。");
            dsc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            dsc.setTextColor(p.dim);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = dp(ctx, 6);
            dlp.bottomMargin = dp(ctx, 14);
            dsc.setLayoutParams(dlp);
            root.addView(dsc);

            LinearLayout line = null;
            for (int i = 0; i < SEC_GRID.length; i++) {
                if (i % 4 == 0) {
                    line = hrow(ctx);
                    root.addView(line);
                }
                final int v = SEC_GRID[i];
                boolean sel = v == current;
                TextView b = cell(ctx, p, fmtSec(v), sel);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        sink.onPick(v);
                        d.dismiss();
                    }
                });
                line.addView(b, cellLp(ctx));
            }

            LinearLayout custom = hrow(ctx);
            custom.setPadding(0, dp(ctx, 10), 0, 0);
            final EditText et = new EditText(ctx);
            et.setInputType(InputType.TYPE_CLASS_NUMBER);
            et.setHint("自定义秒数");
            et.setHintTextColor(p.dim);
            et.setTextColor(p.text);
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            et.setSingleLine(true);
            et.setBackground(round(ctx, p.card, dp(ctx, 10)));
            et.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0);
            LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(0, dp(ctx, 46), 1.3f);
            et.setLayoutParams(etLp);
            custom.addView(et);

            TextView ok = cell(ctx, p, "确定", false);
            ok.setTextColor(p.accent);
            ok.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    try {
                        int v = Integer.parseInt(et.getText().toString().trim());
                        if (v < 1) {
                            v = 1;
                        }
                        if (v > 3600) {
                            v = 3600;
                        }
                        sink.onPick(v);
                        d.dismiss();
                    } catch (Throwable t2) {
                        toast(ctx, "填个整数秒数");
                    }
                }
            });
            LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(dp(ctx, 72), dp(ctx, 46));
            okLp.leftMargin = dp(ctx, 8);
            custom.addView(ok, okLp);
            root.addView(custom);

            TextView cancel = cell(ctx, p, "取消", false);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    d.dismiss();
                }
            });
            LinearLayout cancelRow = hrow(ctx);
            cancelRow.setPadding(0, dp(ctx, 12), 0, 0);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(ctx, 46), 1f);
            cancelRow.addView(cancel, clp);
            root.addView(cancelRow);

            d.setContentView(sv);
            // 自绘 Dialog 默认不接收窗口外触摸 → 点空白关不掉，只能点"取消"。
            // 这里显式打开，与 App 里其它弹窗的行为一致。
            d.setCancelable(true);
            d.setCanceledOnTouchOutside(true);
            if (d.getWindow() != null) {
                d.getWindow().setBackgroundDrawable(new ColorDrawable(p.bg));
                d.getWindow().setGravity(Gravity.BOTTOM);
                d.getWindow().addFlags(
                        android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
            }
            d.show();
            if (d.getWindow() != null) {
                d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable t) {
            Log.w(TAG, "秒数选择器打开失败：" + t);
        }
    }

    /** 时刻选择器：小时 6 列 + 分钟（5 分钟步长）6 列，一屏选完。 */
    private static void pickClock(final Context ctx, int curH, int curM, final ClockSink sink) {
        if (curH < 0) {
            curH = 21;
            curM = 0;
        }
        final Pal p = pal(ctx);
        try {
            final Dialog d = new Dialog(ctx, android.R.style.Theme_DeviceDefault_NoActionBar);
            ScrollView sv = new ScrollView(ctx);
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(p.bg);
            root.setPadding(dp(ctx, 20), dp(ctx, 22), dp(ctx, 20), dp(ctx, 24));
            sv.addView(root, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView t = new TextView(ctx);
            t.setText("开拍时刻");
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
            t.setTextColor(p.text);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            t.setIncludeFontPadding(false);
            root.addView(t);

            final int[] sel = {curH, curM};
            final List<TextView> hourChips = new ArrayList<TextView>();
            final List<TextView> minChips = new ArrayList<TextView>();

            root.addView(subLabel(ctx, p, "时"));
            LinearLayout hl = null;
            for (int h = 0; h < 24; h++) {
                if (h % 6 == 0) {
                    hl = hrow(ctx);
                    root.addView(hl);
                }
                final int hv = h;
                TextView b = cell(ctx, p, String.valueOf(h), hv == curH);
                hourChips.add(b);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sel[0] = hv;
                        repaint(ctx, hourChips, sel[0], false);
                    }
                });
                hl.addView(b, cellLp(ctx));
            }

            root.addView(subLabel(ctx, p, "分"));
            LinearLayout ml = null;
            int rows = 0;
            for (int m = 0; m < 60; m += 5) {
                if (rows % 6 == 0) {
                    ml = hrow(ctx);
                    root.addView(ml);
                }
                rows++;
                final int mv = m;
                TextView b = cell(ctx, p, String.format("%02d", m), mv == curM);
                minChips.add(b);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sel[1] = mv;
                        repaint(ctx, minChips, sel[1], true);
                    }
                });
                ml.addView(b, cellLp(ctx));
            }

            if (curM % 5 != 0) {
                TextView note = new TextView(ctx);
                note.setText("原来的 " + String.format("%02d", curM) + " 分不在 5 分钟刻度上，"
                        + "选完会按最近的刻度走。");
                note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                note.setTextColor(p.dim);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                nlp.topMargin = dp(ctx, 10);
                note.setLayoutParams(nlp);
                root.addView(note);
            }

            repaint(ctx, hourChips, sel[0], false);
            repaint(ctx, minChips, sel[1], true);

            LinearLayout btns = hrow(ctx);
            btns.setPadding(0, dp(ctx, 16), 0, 0);
            TextView cancel = cell(ctx, p, "取消", false);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(ctx, 48), 1f);
            clp.rightMargin = dp(ctx, 8);
            btns.addView(cancel, clp);
            TextView ok = cell(ctx, p, "确定", false);
            ok.setTextColor(p.onAccent);
            ok.setBackground(round(ctx, p.accent, dp(ctx, 12)));
            ok.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sink.onPick(sel[0], sel[1]);
                    d.dismiss();
                }
            });
            btns.addView(ok, new LinearLayout.LayoutParams(0, dp(ctx, 48), 1f));
            root.addView(btns);

            d.setContentView(sv);
            // 自绘 Dialog 默认不接收窗口外触摸 → 点空白关不掉，只能点"取消"。
            // 这里显式打开，与 App 里其它弹窗的行为一致。
            d.setCancelable(true);
            d.setCanceledOnTouchOutside(true);
            if (d.getWindow() != null) {
                d.getWindow().setBackgroundDrawable(new ColorDrawable(p.bg));
                d.getWindow().setGravity(Gravity.BOTTOM);
                d.getWindow().addFlags(
                        android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
            }
            d.show();
            if (d.getWindow() != null) {
                d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable t) {
            Log.w(TAG, "时刻选择器打开失败：" + t);
        }
    }

    private static void repaint(Context ctx, List<TextView> chips, int selected, boolean minute) {
        Pal p = pal(ctx);
        for (TextView b : chips) {
            String txt = String.valueOf(b.getText());
            boolean on = minute ? String.format("%02d", selected).equals(txt)
                    : String.valueOf(selected).equals(txt);
            b.setTextColor(on ? p.accent : p.text);
            b.setBackground(on ? outline(ctx, p) : round(ctx, p.card, dp(ctx, 10)));
        }
    }

    // ============================================================ 文案

    private static String fmtSec(int sec) {
        if (sec < 60) {
            return sec + " 秒";
        }
        int m = sec / 60;
        int s = sec % 60;
        if (s == 0) {
            return m + " 分";
        }
        return m + " 分 " + s + " 秒";
    }

    private static String human(int ms) {
        int sec = (ms + 500) / 1000;
        if (sec < 60) {
            return sec + " 秒";
        }
        int m = sec / 60;
        int s = sec % 60;
        if (s == 0) {
            return m + " 分";
        }
        return m + " 分 " + s + " 秒";
    }

    private static String hmText(int h, int m) {
        return String.format("%02d:%02d", h, m);
    }

    /** 距离开拍的秒数；若今天的这个时刻已经过了，就顺延到明天。 */
    private static int bookDelaySec() {
        if (sBookH < 0) {
            return 1800;
        }
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar target = java.util.Calendar.getInstance();
        target.set(java.util.Calendar.HOUR_OF_DAY, sBookH);
        target.set(java.util.Calendar.MINUTE, sBookM);
        target.set(java.util.Calendar.SECOND, 0);
        target.set(java.util.Calendar.MILLISECOND, 0);
        if (target.getTimeInMillis() <= now.getTimeInMillis()) {
            target.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        long diff = (target.getTimeInMillis() - now.getTimeInMillis()) / 1000;
        if (diff < 1) {
            diff = 1;
        }
        if (diff > 86399) {
            diff = 86399;
        }
        return (int) diff;
    }

    // ============================================================ 持久化

    private static void load(Context ctx) {
        if (sLoaded) {
            return;
        }
        sLoaded = true;
        try {
            SharedPreferences p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE);
            sStarSec = Math.max(1, p.getInt(K_STAR, sStarSec));
            sLadder[0] = Math.max(1, p.getInt(K_L0, sLadder[0]));
            sLadder[1] = Math.max(1, p.getInt(K_L1, sLadder[1]));
            sLadder[2] = Math.max(1, p.getInt(K_L2, sLadder[2]));
            sLadder[3] = Math.max(1, p.getInt(K_L3, sLadder[3]));
            sLadder[4] = Math.max(1, p.getInt(K_L4, sLadder[4]));
            sLadder[5] = Math.max(1, p.getInt(K_L5, sLadder[5]));
            sSteps = clampChoice(p.getInt(K_STEPS, sSteps), STEP_CHOICES);
            sShots = clampChoice(p.getInt(K_SHOTS, sShots), SHOT_CHOICES);
            sGap = clampChoice(p.getInt(K_GAP, sGap), GAP_CHOICES);
            sBookH = p.getInt(K_BH, -1);
            sBookM = p.getInt(K_BM, 0);
            sWakeBefore = p.getBoolean(K_WAKE, sWakeBefore);
            AstroEngine.wakeBeforeShoot = sWakeBefore;
            if (sBookH < -1 || sBookH > 23) {
                sBookH = -1;
            }
            if (sBookM < 0 || sBookM > 59) {
                sBookM = 0;
            }
        } catch (Throwable t) {
            Log.w(TAG, "读深空参数失败：" + t);
        }
    }

    private static int clampChoice(int v, int[] choices) {
        for (int c : choices) {
            if (c == v) {
                return v;
            }
        }
        return choices[0];
    }

    private static int next(int[] choices, int cur) {
        int idx = 0;
        for (int i = 0; i < choices.length; i++) {
            if (choices[i] == cur) {
                idx = i;
            }
        }
        return choices[(idx + 1) % choices.length];
    }

    private static void save(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
                    .putInt(K_STAR, sStarSec)
                    .putInt(K_L0, sLadder[0])
                    .putInt(K_L1, sLadder[1])
                    .putInt(K_L2, sLadder[2])
                    .putInt(K_L3, sLadder[3])
                    .putInt(K_L4, sLadder[4])
                    .putInt(K_L5, sLadder[5])
                    .putInt(K_STEPS, sSteps)
                    .putInt(K_SHOTS, sShots)
                    .putInt(K_GAP, sGap)
                    .putInt(K_BH, sBookH)
                    .putInt(K_BM, sBookM)
                    .putBoolean(K_WAKE, sWakeBefore)
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "存深空参数失败：" + t);
        }
    }

    // ============================================================ UI 小工具

    private static LinearLayout card(Context ctx, Pal p) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        // 液态玻璃：大圆角 + 1px 高光边（3x 屏上约 0.33dp，肉眼就是一道细亮线）
        GradientDrawable g = round(ctx, p.card, dp(ctx, 22));
        g.setStroke(1, p.glassEdge);
        c.setBackground(g);
        return c;
    }

    private static LinearLayout.LayoutParams cardLp(Context ctx, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, topDp);
        return lp;
    }

    /**
     * 卡片里的一行：左标签 + 右值，整行可点。
     * 注意这个 LinearLayout 一定要显式带 LayoutParams —— 不带就按 MATCH_PARENT 高处理。
     */
    private static LinearLayout row(Context ctx, final Pal p, String label, String value,
                                    int valueColor, View.OnClickListener l, boolean last) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 50)));
        if (l != null) {
            r.setClickable(true);
            // 按下态用整行圆角：卡片本身是圆角背景但不裁子 View，
            // 首/末行若用直角会在卡片四角露出一个方块。
            StateListDrawable s = new StateListDrawable();
            s.addState(new int[]{android.R.attr.state_pressed}, round(ctx, p.cardHi, dp(ctx, 14)));
            s.addState(new int[]{}, round(ctx, 0x00000000, dp(ctx, 14)));
            r.setBackground(s);
            r.setOnClickListener(l);
        }

        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        t.setTextColor(p.text);
        t.setSingleLine(true);
        r.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View sp = new View(ctx);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, 1, 1f);
        sp.setLayoutParams(slp);
        r.addView(sp);

        TextView v = new TextView(ctx);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        v.setTextColor(valueColor);
        v.setSingleLine(true);
        r.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private static View divider(Context ctx, Pal p) {
        View v = new View(ctx);
        v.setBackgroundColor(p.line);
        // 0.5dp 而不是 1px：3x 屏上 1px 细到看不见，dp(1) 又会偏粗
        int h = Math.max(1, Math.round(ctx.getResources().getDisplayMetrics().density * 0.5f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h);
        lp.leftMargin = dp(ctx, 16);
        lp.rightMargin = dp(ctx, 16);
        v.setLayoutParams(lp);
        return v;
    }

    private static TextView primary(Context ctx, Pal p, String label, int textColor, int bgColor) {
        TextView b = new TextView(ctx);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        b.setTextColor(textColor);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setBackground(roundFull(ctx, bgColor, dp(ctx, 14)));
        return b;
    }

    private static View heading(Context ctx, Pal p, String title, String desc) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        t.setTextColor(p.text);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setIncludeFontPadding(false);
        box.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView d = new TextView(ctx);
        d.setText(desc);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        d.setTextColor(p.dim);
        d.setLineSpacing(dp(ctx, 3), 1f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(ctx, 6);
        dlp.bottomMargin = dp(ctx, 16);
        d.setLayoutParams(dlp);
        box.addView(d);
        return box;
    }

    private static TextView subLabel(Context ctx, Pal p, String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        t.setTextColor(p.dim);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 14);
        lp.bottomMargin = dp(ctx, 6);
        lp.leftMargin = dp(ctx, 2);
        t.setLayoutParams(lp);
        return t;
    }

    private static TextView hint(Context ctx, Pal p, String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        t.setTextColor(p.dim);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(dp(ctx, 3), 1f);
        return t;
    }

    /** 网格小格子：0 宽 + weight，高度固定 —— 千万别用 MATCH_PARENT 宽，会被压成 0。 */
    private static TextView cell(Context ctx, Pal p, String s, boolean selected) {
        TextView b = new TextView(ctx);
        b.setText(s);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        b.setTextColor(selected ? p.accent : p.text);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setClickable(true);
        b.setBackground(selected ? outline(ctx, p) : round(ctx, p.card, dp(ctx, 10)));
        return b;
    }

    private static LinearLayout.LayoutParams cellLp(Context ctx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(ctx, 44), 1f);
        lp.leftMargin = dp(ctx, 3);
        lp.rightMargin = dp(ctx, 3);
        lp.topMargin = dp(ctx, 3);
        lp.bottomMargin = dp(ctx, 3);
        return lp;
    }

    private static LinearLayout hrow(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }



    private static LinearLayout.LayoutParams centered(Context ctx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    private static LinearLayout.LayoutParams centeredWrap(Context ctx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 16);
        return lp;
    }

    private static LinearLayout.LayoutParams topGap(Context ctx, int dpv) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, dpv);
        return lp;
    }

    private static GradientDrawable round(Context ctx, int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    private static GradientDrawable roundFull(Context ctx, int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    private static GradientDrawable outline(Context ctx, Pal p) {
        GradientDrawable g = new GradientDrawable();
        g.setColor((p.accent & 0x00FFFFFF) | 0x1A000000);
        g.setCornerRadius(dp(ctx, 10));
        g.setStroke(dp(ctx, 1), p.accent);
        return g;
    }

    /** 只圆上面两角 / 下面两角，给卡片首尾行用。 */
    private static GradientDrawable roundCorners(Context ctx, int color, boolean topOnly) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        float r = dp(ctx, 16);
        if (topOnly) {
            g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        } else {
            g.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        }
        return g;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignore) {
        }
    }
}
