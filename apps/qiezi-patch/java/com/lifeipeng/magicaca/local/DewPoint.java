package com.lifeipeng.magicaca.local;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.UUID;

/**
 * 露点检测（1.9.11）—— 雾湿预警，除雾带的决策依据。
 *
 * 原理：
 *   小米温湿度计 2 代（LYWSD03MMC / MJWSD05MMC）刷社区固件（pvvx / ATC1441）
 *   之后每 2~5 秒往外广播一包温湿度（服务 UUID 0x181A，不建连接、谁听谁有）。
 *   本类在拍摄页可见期间开一个低功耗扫描，收包后：
 *     温度 + 湿度 → Magnus 公式算露点 → 余量 = 气温 - 露点。
 *   余量小于 2℃ 时镜头玻璃温度逼近露点，起雾只是时间问题；
 *   余量低于 2℃ 推一条系统通知「该开除雾带了」。
 *
 * 广播格式（社区固件两大流派，自动识别）：
 *   ATC1441：16 字节，[0..5]=MAC，[6..7]=温度 int16 大端 /10，[8]=湿度%，[9]=电量%。
 *   pvvx   ：15 字节，[0]=格式标志位（bit0=℉，bit1-2=温湿度位宽），
 *            温度 int16 小端 /10，湿度可 1 字节(%)或 2 字节(/10)，
 *            后跟电量% / 电量mV(2字节小端)。
 *
 * 生命周期：行 View attach 进窗口才开扫描、detach 就停——切走页面不耗电，
 * 和 ArcView 的 tick 降频是同一套思路。全类只进 classes2.dex，
 * 原包 smali 一行不改（入口在 UiPatch.apply 里追加一个 try 块）。
 */
public final class DewPoint {

    /** 环境传感广播的服务 UUID（0x181A Environmental Sensing）—— ATC/pvvx 固件 */
    private static final UUID SVC_ENV =
            UUID.fromString("0000181a-0000-1000-8000-00805f9b34fb");

    /** 小米 MiBeacon 服务 UUID（0xFE95）—— 领普 KS2 等米家设备 */
    private static final UUID SVC_XIAOMI =
            UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb");

    private static final String PREFS = "qiezi_prefs";
    /** 产品 ID：小米 T2（温湿一包齐发，主力）/ 领普 KS2（分包轮播湿度稀，兜底） */
    private static final int PID_T2 = 1371;
    private static final int PID_KS2 = 21003;
    private static final String K_ENABLED = "dew_enabled";
    private static final String K_BINDKEYS = "dew_bindkeys";   // 行格式 "MAC=KEY"
    private static final String CH_ID = "dew_alert";

    /** 数据比这个时间没更新就当传感器离线（正常 2~5 秒一包） */
    private static final long STALE_MS = 120_000L;
    /** 荣耀栈会静默停掉无过滤扫描（实测两次）：超时无包就自动重启扫描 */
    private static final long WATCHDOG_MS = 5L * 60 * 1000;
    /** 预警阈值：余量 < 2℃ 通知开除雾带 */
    private static final float WARN_MARGIN = 2.0f;
    /** 消警阈值：余量回到 > 3℃ 才重新武装，防止在 1.9℃ 附近反复轰炸 */
    private static final float REARM_MARGIN = 3.0f;
    /** 两条预警的最小间隔 */
    private static final long ALERT_COOLDOWN = 30L * 60_000L;

    // ---- 最新一次有效数据在 DewParse（纯计算类，零 Android 依赖，可宿主机单测） ----
    private static volatile long sLastMs;
    /** 状态字：0 正常 / 1 没定位权限 / 2 蓝牙没开 / 3 搜不到（还没收到过包） */
    private static volatile int sState = 3;

    private static volatile boolean sScanning;
    private static volatile boolean sAlertArmed = true;
    private static long sLastAlertMs;

    private static RowView sRow;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private DewPoint() {
    }

    // ---------------------------------------------------------------- 入口

    /**
     * 在 B 门曲线延时页挂一行露点显示。幂等：onCreateView 每次进来都会调。
     *
     * @param root FragmentDelayAdvance 的根 View（fragment_delay_advance.xml 的 RelativeLayout）
     */
    public static void attach(View root) {
        if (root == null || root.getTag(0x7E1A00DE) != null) {
            return;
        }
        Context ctx = root.getContext();
        appCtx = ctx.getApplicationContext();
        RowView row = new RowView(ctx);
        // 插进设置卡片所在的垂直面板末尾；找不到就退回根布局
        int panelId = ctx.getResources()
                .getIdentifier("delay_advance_center_panel", "id", ctx.getPackageName());
        View panel = panelId != 0 ? root.findViewById(panelId) : null;
        if (panel instanceof ViewGroup) {
            ((ViewGroup) panel).addView(row);
        } else if (root instanceof ViewGroup) {
            ((ViewGroup) root).addView(row);
        } else {
            return;
        }
        root.setTag(0x7E1A00DE, Boolean.TRUE);
        sRow = row;
        refresh();
    }

    // ---------------------------------------------------------------- 行 View

    private static final class RowView extends LinearLayout {

        private final TextView text;
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                watchdog();
                if (isShown()) {
                    refresh();
                    sHandler.postDelayed(this, 3000L);
                } else {
                    // 不可见时降频：只保活循环，不刷新 UI
                    sHandler.postDelayed(this, 10_000L);
                }
            }
        };

        RowView(Context ctx) {
            super(ctx);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER);
            setClickable(true);
            setLongClickable(true);
            int pad = UiPatch.dp(ctx, 6);
            setPadding(0, pad, 0, pad);

            text = new TextView(ctx);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            text.setGravity(Gravity.CENTER);
            addView(text, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            // 细圆角描边，和设置卡片一个气质，但更轻
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(UiPatch.dp(ctx, 10));
            bg.setColor(0x14FFFFFF);
            bg.setStroke(1, 0x24FFFFFF);
            setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    UiPatch.dp(ctx, 300), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = UiPatch.dp(ctx, 4);
            setLayoutParams(lp);

            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    showManageDialog(v.getContext());
                }
            });
            setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    Context c = v.getContext();
                    boolean now = !enabled(c);
                    prefs(c).edit().putBoolean(K_ENABLED, now).apply();
                    Toast.makeText(c, now ? "露点检测：开" : "露点检测：关（长按行可再开）",
                            Toast.LENGTH_SHORT).show();
                    if (now) {
                        startScan(c);
                    } else {
                        stopScan();
                    }
                    refresh();
                    return true;
                }
            });
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            sRow = this;
            android.util.Log.i("DewPoint", "row attach, enabled=" + enabled(getContext()));
            if (enabled(getContext())) {
                startScan(getContext());
            }
            sHandler.removeCallbacks(tick);
            sHandler.post(tick);
        }

        @Override
        protected void onDetachedFromWindow() {
            sHandler.removeCallbacks(tick);
            stopScan();
            super.onDetachedFromWindow();
        }
    }

    // ---------------------------------------------------------------- 管理对话框

    /**
     * 点露点行弹出：当前状态 + 探测到的米家设备 + bindkey 编辑。
     * KS2（v5 固件）的 key 走米家云提取；刷了 pvvx/ATC 固件的设备不需要 key。
     */
    private static void showManageDialog(final Context ctx) {
        try {
            Activity act = activityOf(ctx);
            if (act == null || act.isFinishing()) {
                return;
            }
            StringBuilder msg = new StringBuilder();
            msg.append("状态：").append(statusText(ctx)).append("\n\n");
            String det = detectedSummary();
            if (!det.isEmpty()) {
                msg.append("发现的米家设备：\n").append(det)
                   .append("\n（优先用小米 T2，领普 KS2 失联时兜底；")
                   .append("均需填 bindkey）\n\n");
            }
            msg.append("bindkey 配置（一行一台，格式 MAC=32位hex）：\n")
               .append("米家 v5 设备（KS2）：key 用「XiaomiCloudTokensExtractor」")
               .append("从米家云提取；\n")
               .append("刷 pvvx/ATC 固件的设备：不用填 key，开机即出数。");

            android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
            android.widget.LinearLayout box = new android.widget.LinearLayout(ctx);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = UiPatch.dp(ctx, 16);
            box.setPadding(pad, 0, pad, 0);

            android.widget.TextView msgView = new android.widget.TextView(ctx);
            msgView.setText(msg.toString());
            msgView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            msgView.setPadding(0, 0, 0, UiPatch.dp(ctx, 8));
            box.addView(msgView);

            final android.widget.EditText input = new android.widget.EditText(ctx);
            input.setText(loadBindkeysRaw(ctx));
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            input.setMinLines(2);
            input.setHint("A4:C1:38:XX:XX:XX=0123456789ABCDEF0123456789ABCDEF");
            box.addView(input);
            scroll.addView(box);

            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(act)
                    .setTitle("露点检测")
                    .setView(scroll)
                    .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            saveBindkeys(ctx, input.getText().toString());
                            Toast.makeText(ctx, "bindkey 已保存", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("关闭", null);
            if (needsPermission(ctx)) {
                b.setNeutralButton("申请定位权限",
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                Activity a = activityOf(ctx);
                                if (a != null) {
                                    a.requestPermissions(new String[]{
                                            "android.permission.ACCESS_FINE_LOCATION"}, 1);
                                }
                            }
                        });
            }
            b.show();
        } catch (Throwable t) {
            android.util.Log.w("DewPoint", "管理对话框失败", t);
        }
    }

    // ---------------------------------------------------------------- 刷新显示

    private static void refresh() {
        final RowView row = sRow;
        if (row == null) {
            return;
        }
        final Context ctx = row.getContext();
        final boolean night;
        try {
            night = prefs(ctx).getBoolean("night_mode", false);
        } catch (Throwable t) {
            return;
        }
        sHandler.post(new Runnable() {
            @Override
            public void run() {
                if (sRow != row || !row.isShown()) {
                    return;
                }
                applyState(row, ctx, night);
                checkAlert(ctx, night);
            }
        });
    }

    private static void applyState(RowView row, Context ctx, boolean night) {
        String body;
        int color;
        boolean warn = false;
        if (!enabled(ctx)) {
            body = "露点检测：已关（长按开启）";
            color = 0xFF6B7280;
        } else if (needsPermission(ctx)) {
            body = "露点检测：点此授权定位（蓝牙扫描需要）";
            color = night ? 0xFFFF3B30 : 0xFFFFB020;
        } else if (sState == 2) {
            body = "露点检测：蓝牙未开";
            color = night ? 0xFFFF3B30 : 0xFFFFB020;
        } else if (sState == 3 && Float.isNaN(DewParse.sDew)) {
            body = "露点检测：搜索传感器中…（点此行可配置）";
            color = night ? 0xCCFF3B30 : 0xFF6B7280;
        } else if (Float.isNaN(DewParse.sDew)) {
            // 已收到广播但温/湿两项还没凑齐（米家系分包轮播，等下一包）
            body = "露点检测：已连上，等温湿度数据齐全…";
            color = night ? 0xCCFF3B30 : 0xFF6B7280;
        } else if (System.currentTimeMillis() - sLastMs > STALE_MS) {
            body = "露点检测：传感器离线（最后数据 " + fmtMain() + "）";
            color = night ? 0xCCFF3B30 : 0xFF6B7280;
        } else {
            float margin = DewParse.sTemp - DewParse.sDew;
            if (margin < 0.5f) {
                color = 0xFFFF5252;      // 已经在结露边缘
                warn = true;
            } else if (margin < WARN_MARGIN) {
                color = 0xFFFFB020;      // 逼近露点
                warn = true;
            } else {
                color = night ? 0xCCFF3B30 : 0xFF9AA2AE;
            }
            body = fmtMain() + " · 余量 " + String.format(java.util.Locale.US, "%.1f", margin)
                    + "℃" + (DewParse.sBattery >= 0 ? " · 电量 " + DewParse.sBattery + "%" : "");
        }
        row.text.setText(body);
        row.text.setTextColor(color);
        row.text.setTypeface(null, warn ? Typeface.BOLD : Typeface.NORMAL);
    }

    private static String fmtMain() {
        if (Float.isNaN(DewParse.sDew)) {
            return "无数据";
        }
        return "气温 " + String.format(java.util.Locale.US, "%.1f", DewParse.sTemp) + "℃ · 露点 "
                + String.format(java.util.Locale.US, "%.1f", DewParse.sDew) + "℃";
    }

    private static String statusText(Context ctx) {
        if (sState == 2) {
            return "蓝牙没开，开了才有数据";
        }
        if (Float.isNaN(DewParse.sDew)) {
            return "还没收到传感器广播，确认传感器在 3 米内、刷过 pvvx/ATC 固件";
        }
        return fmtMain();
    }

    // ---------------------------------------------------------------- 预警

    private static void checkAlert(Context ctx, boolean night) {
        if (!enabled(ctx) || Float.isNaN(DewParse.sDew)) {
            return;
        }
        long now = System.currentTimeMillis();
        float margin = DewParse.sTemp - DewParse.sDew;
        if (margin < WARN_MARGIN && sAlertArmed && now - sLastAlertMs > ALERT_COOLDOWN) {
            sAlertArmed = false;
            sLastAlertMs = now;
            notifyFog(ctx, margin);
        } else if (margin > REARM_MARGIN) {
            sAlertArmed = true;
        }
    }

    private static void notifyFog(Context ctx, float margin) {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH_ID, "露点预警",
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("露点余量不足，镜头要起雾");
                nm.createNotificationChannel(ch);
                b = new Notification.Builder(ctx, CH_ID);
            } else {
                b = new Notification.Builder(ctx);
                b.setPriority(Notification.PRIORITY_HIGH);
            }
            b.setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("镜头要起雾了")
                    .setContentText("露点余量 "
                            + String.format(java.util.Locale.US, "%.1f", margin)
                            + "℃，开除雾带 / 检查镜片")
                    .setAutoCancel(true);
            nm.notify(0xDE01, b.build());
        } catch (Throwable t) {
            android.util.Log.w("DewPoint", "发预警失败", t);
        }
    }

    // ---------------------------------------------------------------- 扫描

    private static void startScan(Context ctx) {
        boolean perm = needsPermission(ctx);
        android.util.Log.i("DewPoint", "startScan: scanning=" + sScanning
                + " needPerm=" + perm + " enabled=" + enabled(ctx));
        if (sScanning || perm) {
            if (perm) {
                sState = 1;
            }
            return;
        }
        try {
            BluetoothAdapter adapter = adapterOf(ctx);
            android.util.Log.i("DewPoint", "adapter=" + adapter
                    + " on=" + (adapter != null && adapter.isEnabled()));
            if (adapter == null || !adapter.isEnabled()) {
                sState = 2;
                return;
            }
            BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                sState = 2;
                return;
            }
            // 不用硬件过滤器：荣耀栈上带 filter 的扫描匹配不出结果（实测 22:47 诊断，
            // 无过滤能听到 KS2/LYWSD03MMC，带过滤一条都收不到）。软件分流在回调里做。
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED).build();
            scanner.startScan(new java.util.ArrayList<ScanFilter>(), settings, sCallback);
            sScanning = true;
            android.util.Log.i("DewPoint", "startScan OK(无过滤)");
        } catch (Throwable t) {
            android.util.Log.w("DewPoint", "开扫描失败", t);
        }
    }

    private static void stopScan() {
        if (!sScanning) {
            return;
        }
        sScanning = false;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.getBluetoothLeScanner() != null) {
                adapter.getBluetoothLeScanner().stopScan(sCallback);
                android.util.Log.i("DewPoint", "stopScan");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 扫描看门狗：注册着但长期无包（栈静默停止/环境无传感器）就重启一轮扫描 */
    private static void watchdog() {
        if (!sScanning) {
            return;
        }
        long since = System.currentTimeMillis() - sLastMs;
        if (since > WATCHDOG_MS) {
            android.util.Log.w("DewPoint", "watchdog: " + (since / 1000) + "s 无包，重启扫描");
            stopScan();
            RowView row = sRow;
            if (row != null) {
                startScan(row.getContext());
            }
        }
    }

    private static final ScanCallback sCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            try {
                ScanRecord rec = result.getScanRecord();
                if (rec == null) {
                    return;
                }
                // 路线一：ATC/pvvx 固件的明文广播（0x181A）
                byte[] env = rec.getServiceData(new ParcelUuid(SVC_ENV));
                if (env != null && env.length >= 5 && DewParse.parse(env)) {
                    sLastMs = System.currentTimeMillis();
                    sState = 0;
                    return;
                }
                // 路线二：米家 MiBeacon（0xFE95，领普 KS2 等）
                byte[] mi = rec.getServiceData(new ParcelUuid(SVC_XIAOMI));
                if (mi != null && mi.length >= 5) {
                    android.util.Log.i("DewPoint", "收到 0xFE95 广播 len=" + mi.length
                            + " dev=" + (result.getDevice() != null
                            ? result.getDevice().getAddress() : "?"));
                    handleMiBeacon(result, mi);
                }
            } catch (Throwable t) {
                android.util.Log.w("DewPoint", "onScanResult 异常", t);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            android.util.Log.w("DewPoint", "onScanFailed code=" + errorCode);
            sScanning = false;
        }
    };

    /** MiBeacon 分流：先记探测（供管理对话框提示），再尝试解密 */
    private static void handleMiBeacon(ScanResult result, byte[] sd) {
        String addr = result.getDevice() != null ? result.getDevice().getAddress() : null;
        if (addr == null) {
            return;
        }
        String mac = addr.replace(":", "").toUpperCase();
        int pid = MiBeacon.productIdOf(sd);
        String name = pid > 0 ? MiBeacon.productName(pid) : null;
        synchronized (sDetected) {
            sDetected.put(mac, (name == null ? "MiBeacon" : name) + "|"
                    + System.currentTimeMillis());
        }
        byte[] macBytes = macToBytes(addr);
        if (macBytes == null) {
            return;
        }
        float[] r = MiBeacon.parse(sd, macBytes, bindkeys());
        if (r == null) {
            android.util.Log.i("DewPoint", "MiBeacon 未解出: dev=" + addr
                    + " len=" + sd.length + " pid=" + pid
                    + " keys=" + bindkeys().size()
                    + " sd=" + hexHead(sd, sd.length));
            return;                         // 没 key / key 不对 / 纯按键包
        }
        float temp = r[0];
        float hum = r[1];
        int bat = (int) r[2];
        long now = System.currentTimeMillis();

        // 主机优先级（1.9.17）：小米 T2 为主（温湿一包齐发、来包勤），
        // 领普 KS2 兜底。废弃 RSSI 互抢——两台信号来回跳是打架根源；
        // 同级不互抢，高优先级的包一来就抢回，主机失联 3 分钟谁都能接管。
        int prio = priorityOf(pid);
        synchronized (sDevLock) {
            sDevRssi.put(mac, result.getRssi());
            sDevLast.put(mac, now);
        }
        String cur = sActiveMac;
        boolean adopt;
        boolean switched = false;
        if (cur == null || cur.equals(mac)) {
            adopt = true;
        } else {
            long curLast;
            synchronized (sDevLock) {
                Long l = sDevLast.get(cur);
                curLast = l == null ? 0 : l;
            }
            boolean stale = now - curLast > SWITCH_STALE_MS;
            adopt = stale || prio > sActivePid;
            if (adopt) {
                android.util.Log.i("DewPoint", "主机切换 " + cur + " → " + mac
                        + (stale ? "（主机失联）" : "（优先级接管）"));
                switched = true;
            }
        }
        if (!adopt) {
            return;
        }
        if (switched) {
            sActiveMac = mac;
            sActivePid = prio;
            DewParse.resetValues();           // 换主机就清旧值，防止两台数据混算
        } else if (cur == null) {
            sActiveMac = mac;
            sActivePid = prio;
        }
        DewParse.mergeMi(temp, hum, bat);
        sLastMs = now;
        sState = 0;
    }

    /** 主机锁定的设备观测：RSSI / 最近包时刻 */
    private static final Object sDevLock = new Object();
    private static final java.util.HashMap<String, Integer> sDevRssi =
            new java.util.HashMap<>();
    private static final java.util.HashMap<String, Long> sDevLast =
            new java.util.HashMap<>();
    private static volatile String sActiveMac;
    /** 当前主机的优先级（T2=2 > KS2=1 > 其他=0），防低优先级把主机顶下去 */
    private static volatile int sActivePid;
    private static final long SWITCH_STALE_MS = 3L * 60 * 1000;

    /** 设备优先级：T2 温湿齐发当主力；KS2 湿度包轮播太稀，只兜底 */
    private static int priorityOf(int pid) {
        if (pid == PID_T2) {
            return 2;
        }
        if (pid == PID_KS2) {
            return 1;
        }
        return 0;
    }

    // ---------------------------------------------------------------- bindkey 管理

    /** 探测到的米家设备：MAC → "名字|时间戳"（无论有无 key，见到广播就记） */
    private static final java.util.HashMap<String, String> sDetected =
            new java.util.HashMap<>();

    /** prefs 里 "MAC=KEY" 行 → key 字节列表（扫描回调线程调用，只读 appCtx） */
    private static java.util.List<byte[]> bindkeys() {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        try {
            if (appCtx == null) {
                return out;
            }
            String raw = prefs(appCtx).getString(K_BINDKEYS, "");
            for (String line : raw.split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0 || line.length() - eq - 1 != 32) {
                    continue;
                }
                out.add(hexToBytes(line.substring(eq + 1)));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static android.content.Context appCtx;

    static String loadBindkeysRaw(Context ctx) {
        try {
            return prefs(ctx).getString(K_BINDKEYS, "");
        } catch (Throwable t) {
            return "";
        }
    }

    static void saveBindkeys(Context ctx, String raw) {
        try {
            StringBuilder sb = new StringBuilder();
            for (String line : raw.split("\n")) {
                String t = line.trim().toUpperCase();
                if (t.isEmpty()) {
                    continue;
                }
                int eq = t.indexOf('=');
                if (eq <= 0 || t.length() - eq - 1 != 32) {
                    continue;               // 丢弃格式不对的行
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t);
            }
            prefs(ctx).edit().putString(K_BINDKEYS, sb.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    static String detectedSummary() {
        StringBuilder sb = new StringBuilder();
        synchronized (sDetected) {
            for (java.util.Map.Entry<String, String> e : sDetected.entrySet()) {
                String name = e.getValue().split("\\|")[0];
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(macPretty(e.getKey())).append("（").append(name).append("）");
            }
        }
        return sb.toString();
    }

    static String macPretty(String noColon) {
        if (noColon == null || noColon.length() != 12) {
            return noColon;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(noColon, i, i + 2);
        }
        return sb.toString();
    }

    static byte[] macToBytes(String addr) {
        try {
            String[] parts = addr.split(":");
            if (parts.length != 6) {
                return null;
            }
            byte[] out = new byte[6];
            for (int i = 0; i < 6; i++) {
                out[i] = (byte) Integer.parseInt(parts[i], 16);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    static byte[] hexToBytes(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 日志用：前 n 字节转 hex */
    private static String hexHead(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length && i < n; i++) {
            sb.append(String.format("%02X", b[i]));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 工具

    private static BluetoothAdapter adapterOf(Context ctx) {
        try {
            BluetoothManager mgr = (BluetoothManager)
                    ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            if (mgr != null) {
                return mgr.getAdapter();
            }
        } catch (Throwable ignored) {
        }
        return BluetoothAdapter.getDefaultAdapter();
    }

    /**
     * targetSdk 25 不认 BLUETOOTH_SCAN（那是 31+ 的事），
     * 走老规矩：BLE 扫描要定位权限，FINE 或 COARSE 任一即可。
     */
    private static boolean needsPermission(Context ctx) {
        return ctx.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")
                != PackageManager.PERMISSION_GRANTED
                && ctx.checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION")
                != PackageManager.PERMISSION_GRANTED;
    }

    private static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean(K_ENABLED, true);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0);
    }

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
}
