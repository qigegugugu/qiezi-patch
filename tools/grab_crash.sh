#!/bin/bash
# 抓崩溃日志 —— 手机闪退时用这个，比截图有用得多。
#
# 用法：
#   1. 手机用 USB 线连电脑，弹「允许 USB 调试」点允许
#   2. 终端里跑：bash tools/grab_crash.sh
#   3. 按提示在手机上打开一次 App
#   4. 闪退后回到终端按回车，把打印出来的内容整段发我
#
# 不连电脑也能拿到：App 崩溃后会自己写一份到
#   手机「文件管理 → 下载 → qiezi_crash.txt」
# 把这个文件截图发我也行。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="$ROOT/tools/android/platform-tools/adb"

[ -x "$ADB" ] || { echo "✗ 找不到 adb：$ADB"; exit 1; }

echo "== 1/4 等设备 =="
"$ADB" wait-for-device || { echo "✗ 没检测到设备，检查 USB 线和「USB 调试」开关"; exit 1; }
"$ADB" shell getprop ro.product.model
echo "   已连接"

echo "== 2/4 清空旧日志 =="
"$ADB" logcat -c
"$ADB" shell rm -f /sdcard/Download/qiezi_crash.txt 2>/dev/null

echo "== 3/4 现在去手机上打开「茄子快门」 =="
read -r -p "   闪退（或正常打开）之后，回来按回车继续..." _

echo "== 4/4 收集 =="
echo "---- FATAL / AndroidRuntime ----"
"$ADB" logcat -d -b crash 2>/dev/null | tail -n 80
echo
echo "---- App 自己写的崩溃文件 ----"
"$ADB" shell cat /sdcard/Download/qiezi_crash.txt 2>/dev/null || echo "(没有这个文件：说明没崩到 App 进程内，或者崩溃发生在记录器安装之前)"
echo
echo "---- 最近 40 行 crash tag ----"
"$ADB" logcat -d -s QzCrash:V AndroidRuntime:E 2>/dev/null | tail -n 40
