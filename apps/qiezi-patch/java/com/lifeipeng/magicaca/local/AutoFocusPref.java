package com.lifeipeng.magicaca.local;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 「自动对焦」偏好的本地记忆。
 *
 * 为什么要存本地：这个开关原来的唯一真相是快门盒里的 fff3 特征值
 * （连接后 readValues 读回 currentFocusTime==2 就把 useAutoFocus 置真）。
 * 盒子是 CC254x，掉电后特征值回落默认，于是每次盒子重启/换电池，
 * 用户上次关掉的「自动对焦」就又自己打开了 —— 而自动对焦一开，
 * 相机在 MF 下不回合焦信号，快门盒就一直卡在「等合焦」，按快门没反应。
 *
 * 所以这里给 App 侧留一份记忆：连接读到 fff3 之后用它覆盖，
 * 让「上次怎么设的」跨盒子断电、跨手机重装都保持。
 */
public final class AutoFocusPref {

    private static final String PREFS = "qiezi_prefs";
    private static final String KEY = "autofocus_on";

    private AutoFocusPref() {
    }

    /**
     * @return -1 表示没存过（用盒子里的值）；0 用户上次关掉；1 用户上次开着
     */
    public static int get(Context ctx) {
        if (ctx == null) {
            return -1;
        }
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
            if (!p.contains(KEY)) {
                return -1;
            }
            return p.getBoolean(KEY, true) ? 1 : 0;
        } catch (Throwable t) {
            return -1;
        }
    }

    public static void save(Context ctx, boolean on) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY, on).apply();
        } catch (Throwable ignored) {
            // 记忆失败不影响主功能，下次用盒子里的值
        }
    }
}
