#!/bin/bash
# 重打包后的 APK 签名助手。第一次运行会自动生成一把本地 key（长期复用，务必备份）。
#
# 用法:
#   tools/sign_apk.sh <in.apk> [out.apk]
#
# 为什么要自己签名:
#   原作者的 key 拿不到，任何修改都会破坏原签名，必须用新 key 重签。
#   代价是系统视作另一个应用 → 不能覆盖安装，必须先卸载旧版。
#
# key 放在 tools/keys/，密码写在同一目录的 keystore.pass（权限 600）。
# 换机器只拷这两个文件即可继续签出「同源」的包，不用再卸载重装。
set -uo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="$SELF_DIR/android"
APKSIGNER="$(find "$SDK_DIR" -name apksigner -type f 2>/dev/null | head -1)"
ZIPALIGN="$(find "$SDK_DIR" -name zipalign -type f 2>/dev/null | head -1)"

if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JH="$(/usr/libexec/java_home 2>/dev/null)"
  [ -n "$JH" ] && export JAVA_HOME="$JH"
fi

IN="${1:-}"
[ -f "$IN" ] || { echo "用法: $0 <in.apk> [out.apk]"; exit 1; }
[ -x "$APKSIGNER" ] || { echo "找不到 apksigner：$SDK_DIR"; exit 1; }

OUT="${2:-${IN%.apk}-signed.apk}"
KEYDIR="$SELF_DIR/keys"
KS="$KEYDIR/qiezi-local.jks"
PASSFILE="$KEYDIR/keystore.pass"
ALIAS="qiezi-local"

mkdir -p "$KEYDIR"
chmod 700 "$KEYDIR"

if [ ! -f "$PASSFILE" ]; then
  # 只用于本机自签，长度够就行；不外传
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24 >"$PASSFILE"
  chmod 600 "$PASSFILE"
  echo "已生成 keystore 密码：$PASSFILE"
fi
PASS="$(cat "$PASSFILE")"

if [ ! -f "$KS" ]; then
  echo "未找到 keystore，生成一把新的（有效期 30 年）…"
  keytool -genkeypair -v \
    -keystore "$KS" -storetype PKCS12 \
    -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=qiezi-local, O=self-maintained, C=CN" 2>&1 | tail -3
  echo "⚠️  这把 key 是你以后所有改包的身份，务必备份：$KS + $PASSFILE"
fi

echo "对齐中（zipalign 4 字节）…"
ALIGNED="$(mktemp -t apkzipalign).apk"
if [ -x "$ZIPALIGN" ]; then
  "$ZIPALIGN" -p -f 4 "$IN" "$ALIGNED" || { echo "zipalign 失败，已中止"; rm -f "$ALIGNED"; exit 1; }
  "$ZIPALIGN" -c 4 "$ALIGNED" >/dev/null 2>&1 && echo "对齐校验：OK" || echo "对齐校验：失败"
else
  echo "⚠️  找不到 zipalign（$SDK_DIR），跳过对齐（安装不受影响，但加载稍慢）"
  cp "$IN" "$ALIGNED"
fi

echo "签名中：$OUT"
"$APKSIGNER" sign \
  --ks "$KS" --ks-key-alias "$ALIAS" \
  --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT" "$ALIGNED" 2>&1 | grep -v "^WARNING" | head -8
rm -f "$ALIGNED"

echo "--- 校验 ---"
"$APKSIGNER" verify -v --print-certs "$OUT" 2>&1 | grep -viE "^WARNING" | head -12
echo
echo "产物：$OUT"
