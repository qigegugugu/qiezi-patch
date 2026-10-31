#!/bin/bash
# 用 Android 模拟器验证一个 APK 到底能不能装、能不能跑。
#
# 用法:
#   tools/verify_apk.sh <apk> [输出目录]
#
# 干的事:
#   1. 没有运行中的模拟器就启动 qiezi16（Android 16 / API 36，与荣耀 Magic7 同代）
#   2. adb install -r（这一步能直接暴露 INSTALL_FAILED_DEPRECATED_SDK_VERSION 之类的硬伤）
#   3. 启动首屏，等 5 秒，截图
#   4. 抓 logcat 里的 FATAL EXCEPTION / SecurityException
#   5. 输出结论：装上了没、崩了没
#
# 为什么需要它: 静态验证（签名、对齐、badging 全对）也不能证明包能跑。
# 之前 targetSdk 23 的包所有静态检查都通过，在 Android 16 上直接被拒装。
set -uo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK="$SELF_DIR/android"
EMU="$SDK/emulator/emulator"
AVD_NAME="${AVD_NAME:-qiezi16}"
AAPT2="$(find "$SDK" -name aapt2 -type f 2>/dev/null | head -1)"

APK="${1:-}"
[ -f "$APK" ] || { echo "用法: $0 <apk> [输出目录]"; exit 1; }
[ -x "$EMU" ] || { echo "找不到 emulator：${EMU}（先跑 sdkmanager 装 emulator 与 system-image）"; exit 1; }

OUTDIR="${2:-$(dirname "$APK")/verify}"
mkdir -p "$OUTDIR"

command -v adb >/dev/null 2>&1 || export PATH="$SDK/platform-tools:$PATH"
command -v adb >/dev/null 2>&1 || { echo "找不到 adb"; exit 1; }

# ---------- 1. 确保有设备 ----------
booted() { [ "$(adb -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')" = "1" ]; }

if booted; then
  echo "复用已运行的模拟器"
else
  echo "启动模拟器 $AVD_NAME …（首次约 30–60 秒）"
  "$EMU" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect -memory 2048 -cores 4 >"$OUTDIR/emulator.log" 2>&1 &
  for _ in $(seq 1 60); do
    sleep 5
    booted && break
  done
  if ! booted; then
    echo "❌ 模拟器 5 分钟内没起来，看 $OUTDIR/emulator.log"
    exit 1
  fi
fi
echo "设备：Android $(adb -e shell getprop ro.build.version.release 2>/dev/null | tr -d '\r') / API $(adb -e shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"

# ---------- 2. 安装 ----------
PKG="$("$AAPT2" dump badging "$APK" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
[ -n "$PKG" ] || { echo "读不到包名"; exit 1; }

echo
echo "=== 安装 $PKG ==="
adb -e uninstall "$PKG" >/dev/null 2>&1
INSTALL_OUT="$(adb -e install -r "$APK" 2>&1)"
echo "$INSTALL_OUT" | tail -2
echo "$INSTALL_OUT" | grep -q "^Success" || { echo; echo "❌ 安装失败"; echo "$INSTALL_OUT" | grep -i fail; exit 1; }

# ---------- 3. 启动 ----------
# 注意：am start -n 必须用「包名/完整类名」，只给 包名.类名（无斜杠）会被静默忽略，
# 表现是命令不报错、App 没起来、截图拍到桌面 —— 只看日志会误判成"启动成功"。
LAUNCH="$("$AAPT2" dump badging "$APK" 2>/dev/null | sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p")"
[ -n "$LAUNCH" ] || { echo "读不到 launchable-activity"; exit 1; }
echo
echo "=== 启动 $PKG/$LAUNCH ==="
adb -e logcat -c >/dev/null 2>&1
START_OUT="$(adb -e shell am start -n "$PKG/$LAUNCH" 2>&1)"
echo "$START_OUT" | grep -iE "error|exception" && { echo "❌ 启动失败"; exit 1; }
sleep 5

# 确认 App 真的活着：进程存在 > 前台窗口是谁（系统对话框会抢焦点，属正常）
PID="$(adb -e shell pidof "$PKG" 2>/dev/null | tr -d '\r\n')"
if [ -z "$PID" ]; then
  echo "❌ 进程不存在 —— App 启动后已退出"
  adb -e logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION" -A6 | head -12
  exit 3
fi
echo "进程存活：pid=$PID"

FOCUS="$(adb -e shell dumpsys window 2>/dev/null | sed -n 's/.*mCurrentFocus=Window{[^ ]* [^ ]* \([^ }]*\).*/\1/p' | head -1)"
case "$FOCUS" in
  "$PKG"*) echo "前台：$FOCUS ✓" ;;
  *DeprecatedTargetSdkVersionDialog*)
    echo "前台：系统的「旧版应用」警告（${FOCUS}）—— 属预期，targetSdk<28 必有，点 OK 即可" ;;
  *GrantPermissionsActivity*|*permissioncontroller*)
    echo "前台：权限申请对话框（${FOCUS}）—— 属预期" ;;
  *) echo "⚠️  前台：${FOCUS:-未知}（进程仍在，可能已退到后台）" ;;
esac

SHOT="$OUTDIR/launch.png"
adb -e exec-out screencap -p >"$SHOT" 2>/dev/null
[ -s "$SHOT" ] && echo "截图：$SHOT"

# ---------- 4. 崩溃检查 ----------
CRASH="$(adb -e logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION|SecurityException|Permission Denial" | head -6)"
echo
echo "=== 运行日志 ==="
if [ -n "$CRASH" ]; then
  echo "❌ 发现问题："
  echo "$CRASH"
  exit 2
else
  echo "✅ 无崩溃、无权限拒绝"
fi

echo
echo "=== 结论 ==="
echo "安装成功 / 启动成功 / 未见异常 —— 但蓝牙功能需真机才能验证（模拟器无蓝牙硬件）"
