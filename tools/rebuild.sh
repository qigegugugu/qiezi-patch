#!/bin/bash
# 从 apktool 解包目录一条命令出可安装包：回编译 → 对齐 → 签名。
#
# 用法:
#   tools/rebuild.sh <解包目录> [输出.apk]
# 例:
#   tools/rebuild.sh output/com.lifeipeng.magicaca/raw
#
# 环境变量:
#   EXTRA_DEX2=<classes2.dex 路径>   回编译后把这个 dex 塞进包里（新增 Java 代码用）
# 例:
#   EXTRA_DEX2=apps/qiezi-patch/build/classes2.dex tools/rebuild.sh output/com.lifeipeng.magicaca/raw
#
# 为什么必须走这个脚本而不是只用 apktool b:
#   ① apktool 输出的 zip 不做 4 字节对齐，直接装能用但加载变慢，且 apksigner 要求先对齐再签；
#   ② 改过的包原签名已失效，必须用 tools/keys/ 里的本地 key 重签，否则装不上。
set -uo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JH="$(/usr/libexec/java_home 2>/dev/null)"
  [ -n "$JH" ] && export JAVA_HOME="$JH"
fi

DIR="${1:-}"
[ -n "$DIR" ] || { echo "用法: $0 <解包目录> [输出.apk]"; exit 1; }
[ -d "$DIR" ] || { echo "目录不存在: $DIR"; exit 1; }
[ -f "$DIR/apktool.yml" ] || { echo "不像 apktool 解包目录（缺 apktool.yml）: $DIR"; exit 1; }

command -v apktool >/dev/null 2>&1 || { echo "找不到 apktool"; exit 1; }

OUT="${2:-}"
if [ -z "$OUT" ]; then
  PARENT="$(cd "$DIR/.." && pwd)"
  mkdir -p "$PARENT/dist"
  OUT="$PARENT/dist/$(basename "$PARENT")-$(date +%Y%m%d-%H%M).apk"
fi

TMP="$(mktemp -t rebuild).apk"
echo "回编译：$DIR"
# -f 必须加：apktool 3.x 有增量缓存（$DIR/build），只比对 AndroidManifest.xml 与 res/。
# 改了 apktool.yml 的 sdkInfo / versionInfo 时它判定「资源没变」，直接复用旧缓存，
# 结果包里的 targetSdk / 版本号还是旧的 —— 静默出错，只签名通过根本看不出来。
if ! apktool b -f "$DIR" -o "$TMP" 2>&1 | tail -6; then
  echo "回编译失败，已中止"; rm -f "$TMP"; exit 1
fi
[ -s "$TMP" ] || { echo "回编译产物为空，已中止"; rm -f "$TMP"; exit 1; }

# 可选：注入第二个 dex（新增 Java 代码编出来的）
#   apktool 只会从 smali 目录产出 classes.dex，写 Java 新增的类需要另外塞一个 classes2.dex。
#   minSdk 24 原生支持多 dex（ART 直接扫 all classes*.dex），不需要 MultiDex.install。
#   必须在 zipalign/签名之前塞，否则签完再改包签名又失效。
if [ -n "${EXTRA_DEX2:-}" ]; then
  if [ ! -f "$EXTRA_DEX2" ]; then
    echo "EXTRA_DEX2 指向的文件不存在：$EXTRA_DEX2"; rm -f "$TMP"; exit 1
  fi
  echo
  echo "注入 classes2.dex：$EXTRA_DEX2"
  STAGE="$(mktemp -d -t apkdex)"
  cp "$EXTRA_DEX2" "$STAGE/classes2.dex"
  ( cd "$STAGE" && zip -q "$TMP" classes2.dex ) || { echo "注入 dex 失败"; rm -rf "$STAGE" "$TMP"; exit 1; }
  rm -rf "$STAGE"
  unzip -l "$TMP" | grep -q "classes2.dex" || { echo "注入后找不到 classes2.dex"; rm -f "$TMP"; exit 1; }
  echo "已注入：$(unzip -l "$TMP" | grep classes2.dex | awk '{print $1" bytes"}')"
fi

echo
"$SELF_DIR/sign_apk.sh" "$TMP" "$OUT"
RC=$?
rm -f "$TMP"
exit $RC
