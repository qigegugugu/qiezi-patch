package com.lifeipeng.magicaca.local;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃记录器 —— 装上以后，App 闪退不再"什么线索都没有"。
 *
 * 1.7.3 的关键改动：落点换了。1.7.2 只写 /sdcard/Download，但那个目录需要
 * WRITE_EXTERNAL_STORAGE 的<b>运行时授权</b>，而 App 一启动就崩、根本没机会弹权限框，
 * 所以那份文件从来没写出来过 —— 用户"文件管理 → 下载"里当然找不到。
 *
 * 现在的落点按"用户能不能看见"排序：
 *   ① /sdcard/Android/media/<包名>/crash.txt
 *      —— app 专属外部目录，<b>免权限</b>，且 Android 11+ 的文件管理器里
 *         "内部存储 / Android / media / <包名>" 直接能点进去（ Android/data 会被系统藏掉）。
 *   ② 同目录 crash_latest.txt —— 只留最后 4KB，方便一眼看完截图。
 *   ③ /sdcard/Download/qiezi_crash.txt —— 有存储权限时才有，聊胜于无。
 *   ④ getExternalFilesDir()/crash.txt —— 连电脑 adb pull 用。
 * 外加一个 Toast：崩溃瞬间把异常类名和首行消息甩到屏幕上，用户截个图就行，
 * 连文件管理器都不用进。Toast 是跨进程提交给系统渲染的，主线程死了也照样显示。
 *
 * 三条红线（改的时候别踩）：
 *   1. install() 必须整体 try-catch。它在 Application.onCreate 第一行执行，
 *      自己崩了就是把"能打开"变成"打不开"。
 *   2. 保存原来的 handler，记完必须交还给它 —— 否则系统收不到崩溃，
 *      进程会僵在那儿（用户看到的是"卡死"而不是"闪退"）。
 *   3. 只做写文件 / Log / Toast，不做网络、不做弹窗、不读大文件。
 */
public final class CrashLog {

    private static final String TAG = "QzCrash";
    private static final String PKG = "com.lifeipeng.magicaca";

    private static Context app;

    private CrashLog() {
    }

    /** 免 Context 的落点：app 专属 media 目录，Android 11+ 文件管理器可见。 */
    private static File mediaDir() {
        try {
            File root = Environment.getExternalStorageDirectory();
            if (root != null) {
                return new File(root, "Android/media/" + PKG);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static void install(Context c) {
        try {
            if (c != null) {
                app = c.getApplicationContext();
            }
            final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    try {
                        dump(t, e);
                    } catch (Throwable ignored) {
                    }
                    // 交还给系统，让崩溃正常收尾（不交还的话进程会僵住）
                    if (prev != null) {
                        prev.uncaughtException(t, e);
                    }
                }
            });
            Log.i(TAG, "installed");
        } catch (Throwable ignored) {
        }
    }

    private static void dump(Thread t, Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("time    : " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        pw.println("thread  : " + (t == null ? "?" : t.getName()));
        pw.println("device  : " + Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        pw.println("version : " + version());
        pw.println("---- stack ----");
        e.printStackTrace(pw);
        pw.flush();
        String text = sw.toString();

        Log.e(TAG, text);

        // ① 主落点
        File dir = mediaDir();
        if (dir != null) {
            write(new File(dir, "crash.txt"), text);
            // ② 摘要版：只留开头，便于一眼截图
            int cut = Math.min(text.length(), 4096);
            write(new File(dir, "crash_latest.txt"), text.substring(0, cut));
        }
        // ③ 有权限时才有
        write(new File("/sdcard/Download/qiezi_crash.txt"), text);
        // ④ 连电脑用
        try {
            File ext = app == null ? null : app.getExternalFilesDir(null);
            if (ext != null) {
                write(new File(ext, "crash.txt"), text);
            }
        } catch (Throwable ignored) {
        }

        // ⑤ 屏幕提示：异常类型 + 第一条消息 + 第一个 app 栈帧
        try {
            if (app != null) {
                Toast.makeText(app, brief(e), Toast.LENGTH_LONG).show();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 压到两三行，够定位就行。 */
    private static String brief(Throwable e) {
        StringBuilder sb = new StringBuilder("崩溃 ");
        sb.append(e.getClass().getName());
        String msg = e.getMessage();
        if (msg != null) {
            if (msg.length() > 110) {
                msg = msg.substring(0, 110) + "…";
            }
            sb.append("\n").append(msg);
        }
        // 找第一个属于本 App 的栈帧，那才是真凶
        StackTraceElement[] st = e.getStackTrace();
        if (st != null) {
            for (StackTraceElement el : st) {
                String cn = el.getClassName();
                if (cn != null && cn.startsWith(PKG) && !cn.contains(".local.")) {
                    sb.append("\n@ ").append(el.getMethodName())
                            .append("(").append(el.getFileName()).append(":").append(el.getLineNumber()).append(")");
                    break;
                }
            }
        }
        return sb.toString();
    }

    private static String version() {
        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void write(File f, String text) {
        FileOutputStream out = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            out = new FileOutputStream(f, false);
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
