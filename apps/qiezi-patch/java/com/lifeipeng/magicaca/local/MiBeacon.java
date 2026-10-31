package com.lifeipeng.magicaca.local;

import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 小米 MiBeacon v4/v5 广播解析 + AES-CCM 解密（领普 KS2 走这条路）。
 *
 * KS2BB（产品 ID 21003）广播 0xFE95 服务数据，对象整体加密：
 *   密文 = TLV(objType LE16, objLen u8, objData)，MIC 4 字节尾随。
 *   nonce = MAC反序(6) + productId(2 LE) + packetId(1) + frameCnt(3)，AAD=0x11。
 * 对象：0x4801 温度 float32、0x4808 湿度 float32、0x5003 电量 u8。
 *
 * 字节布局以 ble_monitor xiaomi.py 为准（Android getServiceData 从 frctrl 起，
 * 比 ble_monitor 的 AD 结构少 4 字节头，本类直接按 Android 切片处理）。
 *
 * Android JCE 没有 AES/CCM，这里手写 CCM（CBC-MAC + CTR），
 * 刻意零 android import，宿主机 JVM 可直接单测。
 */
final class MiBeacon {

    /** 版本位、控制位（ble_monitor 口径） */
    private static final int FREQ_MESH = 1 << 7;
    private static final int FREQ_ENCRYPTED = 1 << 3;
    private static final int FREQ_MAC_INCLUDE = 1 << 4;
    private static final int FREQ_CAP_INCLUDE = 1 << 5;
    private static final int FREQ_OBJ_INCLUDE = 1 << 6;

    /** 已知产品 ID → 名字（仅显示用，解密成败才是过滤依据） */
    static String productName(int productId) {
        switch (productId) {
            case 21003: return "KS2";
            case 1371: return "T2";
            case 0x045B: return "LYWSD03MMC";
            case 0x0347: return "MHO-C401";
            case 0x03BC: return "CGG1";
            case 0x0CD7: return "MJWSD05MMC";
            default: return null;
        }
    }

    /** productId LE16 直读（用于未解密时的设备探测） */
    static int productIdOf(byte[] sd) {
        if (sd == null || sd.length < 4) {
            return -1;
        }
        return (sd[2] & 0xFF) | ((sd[3] & 0xFF) << 8);
    }

    /**
     * 解析一包 0xFE95 服务数据。
     *
     * @param sd   Android getServiceData(0xFE95) 的字节（从 frame control 起）
     * @param mac  扫描到的设备 MAC，6 字节、网络序（AA 第一字节）
     * @param keys 可尝试的 bindkey（16 字节一组）；MIC 校验通过才算数
     * @return {temp, hum, battery}，缺项为 NaN / -1；key 不对或格式不认识返回 null
     */
    static float[] parse(byte[] sd, byte[] mac, List<byte[]> keys) {
        try {
            if (sd == null || sd.length < 10 || mac == null || mac.length != 6) {
                return null;
            }
            int frctrl = (sd[0] & 0xFF) | ((sd[1] & 0xFF) << 8);
            if ((frctrl & FREQ_MESH) != 0) {
                return null;              // Mesh 设备不走广播数据
            }
            int version = frctrl >>> 12;
            if (version < 4) {
                return null;              // 只做 v4/v5（KS2 是 v5）
            }
            if ((frctrl & FREQ_OBJ_INCLUDE) == 0) {
                return null;              // 没带对象数据（纯按键/配网包）
            }
            int p = 5;                    // sd[0..4]: frctrl2 + productId2 + packetId1
            if ((frctrl & FREQ_MAC_INCLUDE) != 0) {
                if (sd.length < p + 6) {
                    return null;
                }
                for (int j = 0; j < 6; j++) {
                    if (sd[p + j] != mac[5 - j]) {
                        return null;      // 包内 MAC 与扫描 MAC 不符
                    }
                }
                p += 6;
            }
            if ((frctrl & FREQ_CAP_INCLUDE) != 0) {
                if (sd.length < p + 1) {
                    return null;
                }
                int cap = sd[p] & 0xFF;
                p += 1;
                if ((cap & 0x20) != 0) {
                    p += 1;               // IO 能力附加字节
                }
            }

            byte[] payload;
            if ((frctrl & FREQ_ENCRYPTED) != 0) {
                if (sd.length < p + 8) {  // 密文至少 1 + fc3 + mic4
                    return null;
                }
                byte[] cipherpayload = slice(sd, p, sd.length - 7);
                byte[] token = slice(sd, sd.length - 4, sd.length);
                byte[] fc3 = slice(sd, sd.length - 7, sd.length - 4);
                byte[] nonce = new byte[12];
                for (int j = 0; j < 6; j++) {
                    nonce[j] = mac[5 - j];            // MAC 反序
                }
                nonce[6] = sd[2];                     // productId LE16
                nonce[7] = sd[3];
                nonce[8] = sd[4];                     // packetId
                System.arraycopy(fc3, 0, nonce, 9, 3); // frameCnt 低 3 字节
                payload = null;
                if (keys != null) {
                    for (byte[] key : keys) {
                        if (key == null || key.length != 16) {
                            continue;
                        }
                        byte[] pt = ccmDecrypt(key, nonce, new byte[]{0x11}, cipherpayload, token);
                        if (pt != null) {
                            payload = pt;
                            break;                    // MIC 过了才是对的 key
                        }
                    }
                }
                if (payload == null) {
                    return null;
                }
            } else {
                // 未加密的 v5（少见，支持上以防万一）
                if (sd.length < p + 3) {
                    return null;
                }
                payload = slice(sd, p, sd.length);
            }

            // TLV 循环：objType LE16 + objLen u8 + data
            float temp = Float.NaN;
            float hum = Float.NaN;
            int bat = -1;
            int s = 0;
            while (s + 3 <= payload.length) {
                int objType = (payload[s] & 0xFF) | ((payload[s + 1] & 0xFF) << 8);
                int objLen = payload[s + 2] & 0xFF;
                if (s + 3 + objLen > payload.length) {
                    break;
                }
                if (objType == 0x4801 && objLen == 4) {
                    temp = Float.intBitsToFloat(le32(payload, s + 3));
                } else if (objType == 0x4808 && objLen == 4) {
                    hum = Float.intBitsToFloat(le32(payload, s + 3));
                } else if (objType == 0x1004 && objLen == 2) {
                    temp = le16s(payload, s + 3) / 10f;    // 米家标准：int16 LE /10
                } else if (objType == 0x1006 && objLen == 2) {
                    hum = (payload[s + 3] & 0xFF | (payload[s + 4] & 0xFF) << 8) / 10f;
                } else if (objType == 0x100D && objLen == 4) {
                    // 温湿合包：int16 温 + uint16 湿（LYWSD03MMC/T2 常用）
                    temp = le16s(payload, s + 3) / 10f;
                    hum = (payload[s + 5] & 0xFF | (payload[s + 6] & 0xFF) << 8) / 10f;
                } else if (objType == 0x5003 && objLen >= 1) {
                    bat = payload[s + 3] & 0xFF;
                } else if (objType == 0x100A && objLen >= 1) {
                    bat = payload[s + 3] & 0xFF;
                }
                s += 3 + objLen;
            }
            if (Float.isNaN(temp) && Float.isNaN(hum) && bat < 0) {
                return null;              // 没有任何环境数据（纯按键包等）
            }
            return new float[]{temp, hum, bat};
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================ AES-CCM

    /**
     * CCM 解密 + MIC 校验。返回明文；MIC 不符/格式错返回 null。
     * L 由 nonce 长度推导（MiBeacon nonce 12 → L=3），M 由 tag 长度决定。
     * 注意 tag 要再和 S0（计数器 0 的 CTR 块）异或——RFC 3610 向量实测踩过。
     */
    static byte[] ccmDecrypt(byte[] key, byte[] nonce, byte[] aad,
                             byte[] cipher, byte[] tagExpected) {
        try {
            byte[] pt = ctrXor(key, nonce, cipher, 1);
            byte[] tag = cbcMac(key, nonce, aad, pt, tagExpected.length);
            byte[] s0 = ctrBlock0(key, nonce, tagExpected.length);
            for (int i = 0; i < tagExpected.length; i++) {
                tag[i] ^= s0[i];
            }
            for (int i = 0; i < tagExpected.length; i++) {
                if (tag[i] != tagExpected[i]) {
                    return null;
                }
            }
            return pt;
        } catch (Throwable t) {
            return null;
        }
    }

    /** CCM 加密（单测造向量用）：先算 tag⊕S0，再 CTR 加密明文，输出 密文||tag */
    static byte[] ccmEncrypt(byte[] key, byte[] nonce, byte[] aad, byte[] plain) throws Exception {
        byte[] tag = cbcMac(key, nonce, aad, plain, 4);
        byte[] s0 = ctrBlock0(key, nonce, 4);
        for (int i = 0; i < 4; i++) {
            tag[i] ^= s0[i];
        }
        byte[] ct = ctrXor(key, nonce, plain, 1);
        byte[] out = new byte[ct.length + 4];
        System.arraycopy(ct, 0, out, 0, ct.length);
        System.arraycopy(tag, 0, out, ct.length, 4);
        return out;
    }

    /** CTR 计数器 0 块（只用于加密 tag）：flags(L-1) || nonce || 0 */
    private static byte[] ctrBlock0(byte[] key, byte[] nonce, int len) throws Exception {
        int l = 15 - nonce.length;
        Cipher aes = aesOf(key);
        byte[] block = new byte[16];
        block[0] = (byte) (l - 1);
        System.arraycopy(nonce, 0, block, 1, nonce.length);
        byte[] s = aes.doFinal(block);
        byte[] out = new byte[len];
        System.arraycopy(s, 0, out, 0, len);
        return out;
    }

    /** CBC-MAC：B0 || aad块 || 明文块，逐块异或后过 AES，取前 M 字节。L/M 由 nonce/tag 长度推导 */
    private static byte[] cbcMac(byte[] key, byte[] nonce, byte[] aad,
                                 byte[] plain, int m) throws Exception {
        if (nonce.length < 7 || nonce.length > 13) {
            throw new IllegalArgumentException("nonce 长度必须在 7~13");
        }
        int l = 15 - nonce.length;                // 长度字段字节数
        Cipher aes = aesOf(key);
        // B0：Adata<<6 | (M-2)/2<<3 | (L-1)
        byte[] x = new byte[16];
        x[0] = (byte) (aad != null && aad.length > 0 ? 0x40 : 0x00);
        x[0] |= (byte) (((m - 2) / 2) << 3);
        x[0] |= (byte) (l - 1);
        System.arraycopy(nonce, 0, x, 1, nonce.length);
        int msgLen = plain.length;
        for (int i = 0; i < l; i++) {             // 长度字段大端，占末尾 l 字节
            x[15 - i] = (byte) ((msgLen >> (8 * i)) & 0xFF);
        }
        x = aes.doFinal(x);

        if (aad != null && aad.length > 0) {
            // aad 长度 2 字节 BE（<65280 的情形），补齐到 16 的倍数
            List<Byte> buf = new ArrayList<>();
            buf.add((byte) ((aad.length >> 8) & 0xFF));
            buf.add((byte) (aad.length & 0xFF));
            for (byte b : aad) {
                buf.add(b);
            }
            while (buf.size() % 16 != 0) {
                buf.add((byte) 0);
            }
            byte[] ab = new byte[buf.size()];
            for (int i = 0; i < ab.length; i++) {
                ab[i] = buf.get(i);
            }
            for (int off = 0; off < ab.length; off += 16) {
                for (int i = 0; i < 16; i++) {
                    x[i] ^= ab[off + i];
                }
                x = aes.doFinal(x);
            }
        }
        for (int off = 0; off < plain.length; off += 16) {
            for (int i = 0; i < 16 && off + i < plain.length; i++) {
                x[i] ^= plain[off + i];
            }
            x = aes.doFinal(x);
        }
        byte[] tag = new byte[m];
        System.arraycopy(x, 0, tag, 0, m);
        return tag;
    }

    /** CTR 模式：A_j = flags(L-1) || nonce || j(L字节 BE)，从 j=start 起流水异或 */
    private static byte[] ctrXor(byte[] key, byte[] nonce, byte[] data, int start) throws Exception {
        int l = 15 - nonce.length;
        Cipher aes = aesOf(key);
        byte[] out = new byte[data.length];
        byte[] block = new byte[16];
        block[0] = (byte) (l - 1);
        System.arraycopy(nonce, 0, block, 1, nonce.length);
        int counter = start;
        for (int off = 0; off < data.length; off += 16, counter++) {
            for (int i = 0; i < l; i++) {
                block[15 - i] = (byte) ((counter >> (8 * i)) & 0xFF);
            }
            byte[] s = aes.doFinal(block);
            int n = Math.min(16, data.length - off);
            for (int i = 0; i < n; i++) {
                out[off + i] = (byte) (data[off + i] ^ s[i]);
            }
        }
        return out;
    }

    private static Cipher aesOf(byte[] key) throws Exception {
        Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return aes;
    }

    // ================================================================ 工具

    private static byte[] slice(byte[] src, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(src, from, out, 0, out.length);
        return out;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    /** 小端 int16（带符号，温度负值用） */
    private static int le16s(byte[] b, int off) {
        return (short) ((b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8));
    }

    private MiBeacon() {
    }
}
