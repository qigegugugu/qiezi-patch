#!/bin/bash
# 茄子快门「补丁 dex」构建脚本 —— 只编译 com.lifeipeng.magicaca.local.* 到 classes2.dex
#
# 为什么需要第二个 dex：
#   原包是用 apktool 从 smali 回编译出来的，那套流程只能产出 classes.dex。
#   而本项目里要新增的 UI（方案条）用 Java 写明显比手写 smali 安全，所以：
#     ① 这里把 Java 编成 classes2.dex；
#     ② tools/rebuild.sh 回编译后把它塞进 APK 再签名；
#     ③ 原 classes.dex 一行不用改结构，只是多了几个 invoke-static 调进来。
#   minSdk 24 天生支持多 dex（Android 5.0+ 由 ART 直接加载，不需要 MultiDex.install）。
#
# 用法:
#   apps/qiezi-patch/build.sh          # 产出 apps/qiezi-patch/build/classes2.dex
set -uo pipefail

SELF="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SELF/../.." && pwd)"
SDK="$ROOT/tools/android"
AJ="$SDK/platforms/android-34/android.jar"

# build-tools 必须挑版本最高的：
#   34 那套的 d8（R8 8.2.2-dev）读 JDK 25 编出的匿名内部类会 NPE，35.0.0 正常。
BT=""
if [ -d "$SDK/build-tools" ]; then
  BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
  BT="${BT%/}"
fi
[ -n "$BT" ] || BT="$SDK/android-14"

export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

BUILD="$SELF/build"
die() { echo "✗ $*"; exit 1; }

[ -x "$BT/d8" ] || die "找不到 d8: $BT/d8"
[ -f "$AJ" ]    || die "找不到 android.jar: $AJ"

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex"

echo "[1/3] javac"
find "$SELF/java" -name '*.java' > "$BUILD/sources.txt"
echo "      源文件 $(wc -l < "$BUILD/sources.txt") 个"
# 注意：不要把 javac 直接管道给 grep —— 管道的退出码是 grep 的，
# 编译失败了照样往下走、照样用旧 class 出 dex（2026-10-22、10-30 各踩一次）。
javac -source 8 -target 8 -nowarn -encoding UTF-8 \
  -classpath "$AJ" \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt" > "$BUILD/javac.log" 2>&1
JC=$?
grep -v '^注:' "$BUILD/javac.log" | head -60
[ $JC -eq 0 ] || die "javac 失败（退出码 ${JC}），完整日志 $BUILD/javac.log"
[ -n "$(find "$BUILD/classes" -name '*.class' | head -1)" ] || die "javac 没产出 class"

echo "[2/3] d8 --min-api 24"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$BT/d8" --release --min-api 24 --lib "$AJ" \
  --output "$BUILD/dex" @"$BUILD/classes.txt" || die "d8 失败"
[ -f "$BUILD/dex/classes.dex" ] || die "d8 没产出 classes.dex"

mv "$BUILD/dex/classes.dex" "$BUILD/classes2.dex"

echo "[3/3] 完成"
ls -lh "$BUILD/classes2.dex"
echo
echo "出包：EXTRA_DEX2=$BUILD/classes2.dex tools/rebuild.sh output/com.lifeipeng.magicaca/raw"
