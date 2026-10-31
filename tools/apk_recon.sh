#!/bin/bash
# apk_recon.sh —— APK 静态侦察一键脚本（macOS）
# 用法: ./apk_recon.sh <target.apk> [输出目录]
# 产物: <输出目录>/report.md、raw/（apktool 解包）、java/（jadx 反编译）
set -uo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
VENV="$SELF_DIR/tools/venv"
PY="$VENV/bin/python"

# jadx / apktool 都靠 JAVA_HOME 启动；没设或设错时自动指向本机 JDK
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  JH="$(/usr/libexec/java_home 2>/dev/null || true)"
  [ -n "$JH" ] && export JAVA_HOME="$JH"
fi
case ":$PATH:" in *":/opt/homebrew/bin:"*) ;; *) export PATH="/opt/homebrew/bin:$PATH" ;; esac

APK="${1:-}"
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "用法: $0 <target.apk> [输出目录]"
  exit 1
fi
APK="$(cd "$(dirname "$APK")" && pwd)/$(basename "$APK")"
NAME="$(basename "$APK" .apk)"
if [ -n "${2:-}" ]; then
  OUT="$2"; OUT_IS_DEFAULT=0
else
  OUT="$SELF_DIR/output/$NAME"; OUT_IS_DEFAULT=1
fi
mkdir -p "$OUT"
REPORT="$OUT/report.md"
: > "$REPORT"

sec() { printf '\n## %s\n\n' "$1" >>"$REPORT"; printf '\n== %s ==\n' "$1"; }
note() { printf '%s\n' "$1" >>"$REPORT"; }
open_block() { printf '```text\n' >>"$REPORT"; }
close_block() { printf '```\n\n' >>"$REPORT"; }
log() { printf '   %s\n' "$1"; }

printf '# APK 侦察报告：%s\n\n' "$(basename "$APK")" >>"$REPORT"
printf '> 生成时间 %s ｜ 目标 SDK 与敏感行为仅作分析参考，请确保对目标有分析授权。\n' \
  "$(date '+%Y-%m-%d %H:%M')" >>"$REPORT"

# ---------- 1. 指纹 ----------
sec "1. 样本指纹"
log "计算哈希…"
{
  printf '| 项 | 值 |\n|---|---|\n'
  printf '| 文件名 | `%s` |\n' "$(basename "$APK")"
  printf '| 大小 | %s |\n' "$(du -h "$APK" | cut -f1 | tr -d ' ')"
  printf '| SHA-256 | `%s` |\n' "$(shasum -a 256 "$APK" | cut -d' ' -f1)"
  if command -v md5 >/dev/null 2>&1; then
    printf '| MD5 | `%s` |\n' "$(md5 -q "$APK")"
  fi
  printf '| 文件类型 | %s |\n\n' "$(file -b "$APK")"
} >>"$REPORT"

# ---------- 2. 加固 / 壳 ----------
sec "2. 加固与编译器识别"
log "识别壳与打包器…"
strip_ansi() { perl -pe 's/\e\[[0-9;]*[A-Za-z]//g'; }
if [ -x "$VENV/bin/apkid" ]; then
  open_block
  "$VENV/bin/apkid" "$APK" 2>&1 | strip_ansi | sed -n '1,25p' | tee -a "$REPORT" >/dev/null
  printf '```\n\n' >>"$REPORT"
else
  note "> apkid 未安装，跳过（可 pip install apkid 补上）。"
fi
PKG_LIST="$(unzip -Z1 "$APK" 2>/dev/null)"
SHELL_HINT=""
for sig in libjiagu libDexHelper libchaosvmp libsecexe libmobisec libnesec libshell \
           libexec.so libmobisec.so libSecShell libkwscmm libnesec.so libbaiduprotect; do
  if printf '%s\n' "$PKG_LIST" | grep -qi "$sig"; then
    SHELL_HINT="$SHELL_HINT $sig"
  fi
done
if [ -n "$SHELL_HINT" ]; then
  note "**命中已知加固特征库**：\`${SHELL_HINT# }\` → 解包出来的 smali 可能是壳代码，需要脱壳后再分析。"
  log "命中壳特征：${SHELL_HINT# }"
else
  note "未命中常见加固库特征（不代表一定没壳，只说明不是主流商业壳）。"
fi

# ---------- 3. 解包 ----------
sec "3. apktool 解包（资源 + smali）"
if command -v apktool >/dev/null 2>&1; then
  log "解包中（大包约 1–3 分钟）…"
  apktool d -f -o "$OUT/raw" "$APK" >"$OUT/log_apktool.txt" 2>&1
  if [ -f "$OUT/raw/AndroidManifest.xml" ]; then
    # 输出目录默认按文件名命名，但 base.apk 这类名字毫无信息量，改用包名
    if [ "$OUT_IS_DEFAULT" = "1" ]; then
      PKG_NAME="$("$PY" -c 'import sys,xml.etree.ElementTree as E;print(E.parse(sys.argv[1]).getroot().get("package",""))' \
        "$OUT/raw/AndroidManifest.xml" 2>/dev/null)"
      if [ -n "$PKG_NAME" ] && [ "$PKG_NAME" != "$NAME" ]; then
        rm -rf "$SELF_DIR/output/$PKG_NAME"
        mv "$OUT" "$SELF_DIR/output/$PKG_NAME"
        OUT="$SELF_DIR/output/$PKG_NAME"
        REPORT="$OUT/report.md"
        log "输出目录改用包名：output/$PKG_NAME"
      fi
    fi
    note "- 输出目录：\`raw/\`"
    note "- 资源：\`raw/res\`，smali：\`raw/smali*\`"
    note "- 是否可回编译：\`apktool b raw -o patched.apk\`（改完 smali 用它重打包）"
  else
    note "> 解包失败，日志尾部："
    open_block
    tail -15 "$OUT/log_apktool.txt" >>"$REPORT"
    close_block
  fi
else
  note "> 未找到 apktool，跳过。"
fi

# ---------- 4. 反编译 ----------
sec "4. jadx 反编译（Java 源码视角）"
if command -v jadx >/dev/null 2>&1; then
  log "反编译中（大包 2–5 分钟，别中断）…"
  jadx -d "$OUT/java" --no-res -q "$APK" >"$OUT/log_jadx.txt" 2>&1
  if [ -d "$OUT/java" ]; then
    JAVA_N="$(find "$OUT/java" -name '*.java' 2>/dev/null | wc -l | tr -d ' ')"
    # jadx 版本不同，有的直接铺在 -d 目录下，有的放 sources/ 子目录
    SRC_ROOT="java"
    [ -d "$OUT/java/sources" ] && SRC_ROOT="java/sources"
    note "- 源码目录：\`$SRC_ROOT/\`（${JAVA_N} 个 .java）"
    note "- 图形界面读代码：\`jadx-gui $APK\`"
  else
    note "> jadx 失败，日志尾部："
    open_block
    tail -15 "$OUT/log_jadx.txt" >>"$REPORT"
    close_block
  fi
else
  note "> 未找到 jadx，跳过。"
fi

# ---------- 5. Manifest ----------
sec "5. Manifest 剖析（权限 / 导出组件）"
log "解析 Manifest…"
if [ -f "$OUT/raw/AndroidManifest.xml" ]; then
  if [ -x "$PY" ]; then
    "$PY" "$SELF_DIR/tools/manifest_summary.py" "$OUT/raw/AndroidManifest.xml" >>"$REPORT"
  else
    note "> 找不到虚拟环境 python，跳过。"
  fi
else
  note "> 没有解包出的 Manifest，跳过；可改用 \`aapt2 dump badging\`。"
fi

# ---------- 6. 结构概览 ----------
sec "6. 包内结构概览"
{
  printf '| 类型 | 数量 |\n|---|---|\n'
  printf '| dex | %s |\n' "$(printf '%s\n' "$PKG_LIST" | grep -c '^classes.*\.dex$')"
  printf '| so | %s |\n' "$(printf '%s\n' "$PKG_LIST" | grep -c '\.so$')"
  printf '| assets 文件 | %s |\n' "$(printf '%s\n' "$PKG_LIST" | grep '^assets/' | grep -vc '/$')"
  printf '| res 文件 | %s |\n\n' "$(printf '%s\n' "$PKG_LIST" | grep '^res/' | grep -vc '/$')"
} >>"$REPORT"
if [ -n "$(printf '%s\n' "$PKG_LIST" | grep '\.so$')" ]; then
  note "**native 库**（核心逻辑常藏在这里）："
  note ""
  open_block
  printf '%s\n' "$PKG_LIST" | grep '\.so$' | sed -n '1,25p' >>"$REPORT"
  close_block
fi

# ---------- 7. 行为扫描 ----------
sec "7. 敏感行为扫描（smali 全量）"
PAT_FILE="$OUT/.patterns.txt"
LIST_FILE="$OUT/.patterns_labeled.txt"
cat >"$LIST_FILE" <<'PATTERNS'
反射调用|Ljava/lang/reflect/
动态加载 Dex|Ldalvik/system/(Dex|InMemoryDex|Path)ClassLoader;
命令执行|Ljava/lang/Runtime;->exec
创建进程|Ljava/lang/ProcessBuilder;
加解密|Ljavax/crypto/Cipher;
设备标识|->getDeviceId|->getSubscriberId|->getImei|->getAndroidId
短信操作|Landroid/telephony/SmsManager;
无障碍服务|Landroid/accessibilityservice/AccessibilityService
悬浮窗|TYPE_APPLICATION_OVERLAY|SYSTEM_ALERT_WINDOW
录屏截屏|Landroid/media/projection/MediaProjection
WebView 注入|addJavascriptInterface
静默安装|PackageInstaller;|installPackage
Root 检测|/system/bin/su|/system/xbin/su|test-keys|isRooted
模拟器检测|goldfish|generic_x86|ro.kernel.qemu|bluestacks
读取已装应用|getInstalledPackages|getInstalledApplications
网络请求|Ljava/net/HttpURLConnection;|Lokhttp3/|Lretrofit2/
PATTERNS
cut -d'|' -f2- "$LIST_FILE" >"$PAT_FILE"

CNT=0
if [ -d "$OUT/raw" ]; then
  CNT="$(find "$OUT/raw" -name '*.smali' -type f 2>/dev/null | wc -l | tr -d ' ')"
fi
if [ "${CNT:-0}" -gt 0 ]; then
  log "扫描 ${CNT} 个 smali 文件…"
  : >"$OUT/.hits.txt"
  find "$OUT/raw" -name '*.smali' -type f -print0 2>/dev/null \
    | xargs -0 grep -nHE -f "$PAT_FILE" >"$OUT/.hits.txt" 2>/dev/null
  {
    printf '| 行为 | 命中行数 | 示例文件 |\n|---|---|---|\n'
    while IFS='|' read -r label pat; do
      [ -z "$label" ] && continue
      c="$(grep -cE -- "$pat" "$OUT/.hits.txt" 2>/dev/null)"
      f="$(grep -m1 -E -- "$pat" "$OUT/.hits.txt" 2>/dev/null | cut -d: -f1)"
      f="${f#"$OUT"/raw/}"
      printf '| %s | %s | `%s` |\n' "$label" "${c:-0}" "${f:-—}"
    done <"$LIST_FILE"
    printf '\n'
  } >>"$REPORT"
  note "> 命中行数只表示「代码里出现过」，不等于「一定会执行」，需要结合调用链确认。"
else
  note "> 没有 smali，跳过（多半是解包失败或已加壳）。"
fi

# ---------- 8. 网络线索 ----------
sec "8. 接口与密钥线索"
SRC_DIRS=()
[ -d "$OUT/java" ] && SRC_DIRS+=("$OUT/java")
for d in "$OUT"/raw/smali*; do [ -d "$d" ] && SRC_DIRS+=("$d"); done
# 接口地址常藏在 assets 配置和 strings.xml 里，一起扫
[ -d "$OUT/raw/assets" ] && SRC_DIRS+=("$OUT/raw/assets")
[ -d "$OUT/raw/res/values" ] && SRC_DIRS+=("$OUT/raw/res/values")
if [ "${#SRC_DIRS[@]}" -gt 0 ]; then
  log "提取 URL / IP…"
  URLS="$(grep -rhoIE "https?://[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]+" "${SRC_DIRS[@]}" 2>/dev/null \
        | sed 's/["\\,)}]*$//' | sort -u | head -40)"
  if [ -n "$URLS" ]; then
    note "**URL 候选**（前 40 条，去重）"
    note ""
    open_block
    printf '%s\n' "$URLS" >>"$REPORT"
    close_block
  else
    note "- 未在代码里直接发现 URL，接口可能拼在配置或 assets 里。"
  fi
  IPS="$(grep -rhoIE "\b([0-9]{1,3}\.){3}[0-9]{1,3}\b" "${SRC_DIRS[@]}" 2>/dev/null \
        | grep -vE '^(0|127|255)\.' | sort -u | head -15)"
  if [ -n "$IPS" ]; then
    note "**IP 候选**（含误报，如版本号）"
    note ""
    open_block
    printf '%s\n' "$IPS" >>"$REPORT"
    close_block
  fi
fi

# ---------- 9. 签名 ----------
sec "9. 签名信息"
if command -v keytool >/dev/null 2>&1; then
  SIG="$(keytool -printcert -jarfile "$APK" 2>/dev/null | grep -E 'Owner|Issuer|Valid|SHA256|SHA-256' | head -8)"
  if [ -n "$SIG" ]; then
    open_block
    printf '%s\n' "$SIG" >>"$REPORT"
    close_block
  else
    note "> keytool 读不到 v2/v3 签名（只认 v1）。要完整验签需装 Android SDK 的 apksigner。"
  fi
fi

# ---------- 10. 下一步 ----------
sec "10. 下一步建议"
note "1. 先看第 5 节的**导出组件**：有没有不需要权限就能调起的 Activity / Provider。"
note "2. 再看第 7 节的**动态加载**与**反射**：有命中就说明有运行时解密的逻辑，静态会卡住，转 Frida。"
note "3. 接口联调：\`mitmproxy\` 起代理，手机设代理 + 装证书，配合 Frida 做 SSL unpinning。"
note "4. 上机动态：\`adb shell\` + \`frida -U -f <包名> -l hook.js\`。"
note ""
note "本次产物：\`raw/\`（资源+smali）、\`java/\`（反编译源码）、\`report.md\`（本文件）。"

rm -f "$PAT_FILE" "$LIST_FILE"
printf '\n报告已生成：%s\n' "$REPORT"
