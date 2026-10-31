# qiezi-patch — 茄子快门增强补丁（自研部分开源）

为安卓快门遥控 App「茄子快门」（`com.lifeipeng.magicaca`）追加自研功能的**补丁源码与工具链**。
本仓库**只包含我们自己从零写的代码**，不含、也永远不会包含原 APK、反编译 smali 或原包资源。

> **免责声明**：本项目仅供学习研究与个人使用。请自行购买/下载原版 App 并支持原作者。
> 请勿使用本仓库产出物分发修改版 APK 或用于商业用途。

## 功能一览

| 功能 | 说明 |
|---|---|
| 露点检测（1.9.11+） | BLE 扫描米家系温湿度计广播 → Magnus 公式算露点 → 余量 < 2℃ 系统通知「该开除雾带了」 |
| 多传感器主机管理 | 固定优先级选主机（防多台传感器数据混算），失联 3 分钟自动切备机 |
| MiBeacon v4/v5 解密 | 纯 Java 手写 AES-CCM（Android JCE 无 CCM），含 RFC 3610 向量验证 |
| B 门延时增强 | 方案预设条（60s×60 … 5m×12）、曲线界面增强等 |

支持的温湿度计：

- 米家蓝牙温湿度计 T2（`miaomiaoce.sensor_ht.t2`，温湿一包齐发，推荐当主力）
- 领普 KS2 / KS2BB（`linp.sensor_ht.ks2bb`，温湿分包轮播，当备机）
- 刷 pvvx / ATC1441 社区固件的 LYWSD03MMC / MHO-C401 等（明文广播，免 bindkey）

## 仓库结构

```
apps/qiezi-patch/     补丁 Java 源码 + classes2.dex 构建脚本
  java/.../local/     全部功能代码（14 个类，零第三方依赖）
    UiPatch.java      总入口：各功能从这里挂进原包界面
    DewPoint.java     露点检测：BLE 扫描 / 行显示 / 看门狗 / 预警通知
    DewParse.java     广播字节解析 + Magnus 露点公式（零 Android 依赖，可宿主机单测）
    MiBeacon.java     MiBeacon v4/v5 解析 + 手写 AES-CCM
tools/                逆向/构建/签名/验证工具链
  rebuild.sh          apktool 回编译 + 注入第二 dex + 签名，统一出包入口
  sign_apk.sh         zipalign + apksigner（keystore 不存在时自动生成一把本地的）
  verify_apk.sh       装机 → 启动 → 截图 → 抓 FATAL，一键验证
  apk_recon.sh        原包侦察：产出报告/结论/原始清单（按包名归档）
  stub_method.py      整体替换 smali 方法体（比"开头插 return"安全）
  class_diff.py       重编译前后类名集合差分（防 apktool 缓存丢代码）
docs/design-1.7/      快门界面视觉规范（配色/尺寸/圆角体系）
```

## 构建指南

### 前置

- JDK 17+（我们在 Temurin 25 上开发）
- [apktool](https://apktool.org/) 3.x
- Android SDK：`build-tools`（≥35，34 的 d8 读 JDK 25 产物会 NPE）+ `platforms/android-34/android.jar`
- 原版 APK 一份（**自备**，流程不下载、不附带）

### 步骤

```bash
# 1. apktool 解包原 APK
apktool d base.apk -o output/com.lifeipeng.magicaca/raw

# 2. 编译补丁 dex（产出 classes2.dex）
bash apps/qiezi-patch/build.sh

# 3. 回编译 + 注入 + 签名（一次完成）
EXTRA_DEX2=apps/qiezi-patch/build/classes2.dex bash tools/rebuild.sh output/com.lifeipeng.magicaca/raw

# 4. 装机验证
adb install dist/com.lifeipeng.magicaca-<日期>.apk
bash tools/verify_apk.sh <包名>
```

原包侧只需要在对应界面挂上 `UiPatch.apply(...)` 一个静态调用（补丁全部功能都从这个入口自举，
各 Java 文件头部注释标明了各自的挂载点），其余零侵入：原 classes.dex 结构不动，新代码全在 classes2.dex。

### 露点传感器 bindkey（米家系加密广播）

1. 手机装 [XiaomiCloudTokensExtractor](https://github.com/PiotrMachowski/XiaomiCloudTokensExtractor)，扫码登录米家账号
2. 拿到 `bindkey`（32 位 hex）后，在 App 露点行点开管理对话框，按 `MAC=KEY` 一行一台填入

## 设计约定（踩坑换来的，改代码前先读）

- 补丁代码零 Android 外依赖、纯 Java + 手写 CCM，可在宿主机 JVM 跑单测（android.jar 是 stub）
- 涉及小米广播分包（温/湿不在同一包）的合并逻辑有 20 分钟新鲜期，改超时前先看 `DewParse`
- 多传感器选主机用**固定优先级**而非 RSSI 互抢——两台设备信号来回跳会让数据互相污染
- 视觉规范见 `docs/design-1.7/`，暗色 UI 三级文字对比度需实测（`#5C5C68` on `#0B0B0E` 仅 2.98:1）

## 致谢

- [lifeipeng](https://www.coolapk.com/) — 茄子快门原作者，本补丁的一切都建立在它的基础上
- [ble_monitor](https://github.com/custom-components/ble_monitor) — MiBeacon 字节布局参考
- pvvx / ATC1441 社区固件 — 温湿度计广播格式
- [PiotrMachowski/XiaomiCloudTokensExtractor](https://github.com/PiotrMachowski/XiaomiCloudTokensExtractor) — bindkey 提取

## License

MIT — 仅覆盖本仓库自研代码，不延伸到原 App 的任何部分。
