package com.lifeipeng.magicaca.local;

/**
 * 露点检测的纯计算部分：广播字节解析 + Magnus 露点公式。
 *
 * 刻意一个 android.* 都不 import —— 这样可以直接在宿主机 JVM 上
 * 跑单测验证字节格式（android.jar 是 stub，沾一点就 Stub! 崩）。
 * DewPoint 负责扫描生命周期和 UI，这里的字段由它读写。
 */
final class DewParse {

    static volatile float sTemp = Float.NaN;
    static volatile float sHum = Float.NaN;
    static volatile float sDew = Float.NaN;
    static volatile int sBattery = -1;

    /** MiBeacon 分包合并的单项新鲜期：超龄的值不再参与露点计算 */
    static final long STALE_MS = 20L * 60 * 1000;
    private static volatile long sTempMs;
    private static volatile long sHumMs;

    /** 主机切换时清空旧值（防两台设备数据混算露点） */
    static void resetValues() {
        sTemp = Float.NaN;
        sHum = Float.NaN;
        sDew = Float.NaN;
        sBattery = -1;
        sTempMs = 0;
        sHumMs = 0;
    }

    private DewParse() {
    }

    /**
     * MiBeacon（米家系）分包合并：温/湿度可能在不同广播包里各自到达
     * （实测 T2/LYWSD 系一次只发一项），各记最近一次有效值；
     * 两项都新鲜才算露点。NaN 表示本包没带该项。
     */
    static void mergeMi(float temp, float hum, int bat) {
        long now = System.currentTimeMillis();
        if (bat >= 0 && bat <= 100) {
            sBattery = bat;
        }
        if (!Float.isNaN(temp) && temp > -40f && temp < 85f) {
            sTemp = temp;
            sTempMs = now;
        }
        if (!Float.isNaN(hum) && hum > 0f && hum <= 100f) {
            sHum = hum;
            sHumMs = now;
        }
        if (now - sTempMs < STALE_MS && now - sHumMs < STALE_MS
                && !Float.isNaN(sTemp) && !Float.isNaN(sHum)) {
            publish(sTemp, Math.round(sHum), sBattery);
        }
    }

    /**
     * 解析 0x181A 服务数据。返回 false 表示不是认识的格式
     * （过滤掉别人的环境传感设备）。双格式判定：
     *   16 字节 = ATC1441（MAC 前缀 + 大端）；
     *   13/15 字节 = pvvx（格式标志 + 小端）。
     */
    static boolean parse(byte[] d) {
        try {
            if (d.length == 16) {
                // ATC1441：[0..5]MAC，[6..7]温度 int16 大端 /10，[8]湿度，[9]电量
                int raw = ((d[6] & 0xFF) << 8) | (d[7] & 0xFF);
                float temp = (short) raw / 10f;
                int hum = d[8] & 0xFF;
                int bat = d[9] & 0xFF;
                if (hum <= 0 || hum > 100) {
                    return false;
                }
                publish(temp, hum, bat);
                return true;
            }
            if (d.length == 15 || d.length == 13) {
                // pvvx：[0]标志位（bit0=℉，bit1-2=温/湿位宽），随后小端
                int flags = d[0] & 0xFF;
                int fmt = (flags >> 1) & 0x03;   // 0:int8+u8 1:int16+u8 2:int8+u16 3:int16+u16
                int p = 1;
                float temp;
                if (fmt == 1 || fmt == 3) {
                    int raw = (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
                    temp = (short) raw / 10f;
                    p += 2;
                } else {
                    temp = (byte) d[p];
                    p += 1;
                }
                int hum;
                if (fmt >= 2) {
                    hum = Math.round(((d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8)) / 10f);
                    p += 2;
                } else {
                    hum = d[p] & 0xFF;
                    p += 1;
                }
                int bat = d[p] & 0xFF;
                if (hum <= 0 || hum > 100 || temp < -40f || temp > 85f) {
                    return false;
                }
                publish(temp, hum, bat);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Magnus-Tetens 公式：气温 + 相对湿度 → 露点。
     * 常温段（-30~35℃）误差 < 0.2℃，对除雾决策绰绰有余。
     */
    static void publish(float temp, int hum, int bat) {
        double es = 6.112 * Math.exp(17.67 * temp / (temp + 243.5));
        double e = (hum / 100.0) * es;
        double td = 243.5 * Math.log(e / 6.112) / (17.67 - Math.log(e / 6.112));
        sTemp = temp;
        sHum = hum;
        sDew = (float) td;
        sBattery = bat;
    }
}
