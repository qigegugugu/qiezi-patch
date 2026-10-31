#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 apktool 反编译出的 AndroidManifest.xml 提取关键信息（只读，不改文件）。

用法: manifest_summary.py <path/to/AndroidManifest.xml>

输出为 Markdown 片段，直接拼进侦察报告。
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"

# 这些权限普通 App 申请无效（signature|privileged），一旦出现说明是旧模板
# 或系统级应用遗留，值得单独点出来而不是混在普通权限里。
PRIVILEGED_ONLY = {"READ_LOGS", "WRITE_SECURE_SETTINGS", "INSTALL_PACKAGES",
                   "DELETE_PACKAGES", "MOUNT_UNMOUNT_FILESYSTEMS", "REBOOT"}


def read_apktool_yml(manifest_path):
    """apktool 解包时把 versionName/versionCode/sdk 写在 apktool.yml 里，
    而 AndroidManifest.xml 可能本身没有这些属性（老包常见）。"""
    yml = os.path.join(os.path.dirname(os.path.abspath(manifest_path)), "apktool.yml")
    info = {}
    if not os.path.isfile(yml):
        return info
    section = ""
    try:
        with open(yml, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if re.match(r"^\S", line):
                    section = line.strip().rstrip(":")
                    continue
                m = re.match(r"^\s+(\w+):\s*(\S+)\s*$", line)
                if m:
                    info[f"{section}.{m.group(1)}"] = m.group(2)
    except OSError:
        return {}
    return info


def strip_pkg(name, pkg):
    """com.foo.Bar -> .Bar；同名包返回原值，避免拼出 '..Bar'。"""
    if pkg and name.startswith(pkg + "."):
        return "." + name[len(pkg) + 1:]
    return name

DANGEROUS = {
    "READ_SMS", "SEND_SMS", "RECEIVE_SMS", "RECEIVE_MMS", "READ_CONTACTS",
    "WRITE_CONTACTS", "READ_CALL_LOG", "WRITE_CALL_LOG", "CALL_PHONE",
    "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "CAMERA", "RECORD_AUDIO",
    "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
    "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE",
    "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "BODY_SENSORS",
    "ACTIVITY_RECOGNITION", "GET_ACCOUNTS", "READ_CALENDAR", "WRITE_CALENDAR",
    "ANSWER_PHONE_CALLS", "PROCESS_OUTGOING_CALLS", "BLUETOOTH_CONNECT",
    "BLUETOOTH_SCAN", "POST_NOTIFICATIONS", "QUERY_ALL_PACKAGES",
    "REQUEST_INSTALL_PACKAGES", "SYSTEM_ALERT_WINDOW", "PACKAGE_USAGE_STATS",
}


def short_perm(name):
    for p in ("android.permission.", "com.android."):
        if name.startswith(p):
            return name[len(p):]
    return name


def attr(el, name, default="-"):
    if el is None:
        return default
    v = el.get(A + name)
    return default if v is None else v


def main():
    if len(sys.argv) < 2:
        print("usage: manifest_summary.py <AndroidManifest.xml>")
        return 2
    try:
        root = ET.parse(sys.argv[1]).getroot()
    except Exception as exc:                       # noqa: BLE001
        print(f"> 解析失败: {exc}")
        return 1

    pkg = root.get("package", "?")
    yml = read_apktool_yml(sys.argv[1])

    def merged(man_val, yml_key, label):
        if man_val not in ("-", None):
            return man_val
        v = yml.get(yml_key)
        if v:
            return f"{v}（取自 apktool.yml）"
        return f"**未声明**（{label}）"

    print(f"- **包名**: `{pkg}`")
    print(f"- **versionName**: "
          f"{merged(root.get(A + 'versionName'), 'versionInfo.versionName', '安装后按 0 处理')}   "
          f"**versionCode**: "
          f"{merged(root.get(A + 'versionCode'), 'versionInfo.versionCode', '无法升级')}")

    sdk = root.find("uses-sdk")
    min_v = attr(sdk, "minSdkVersion")
    tgt_v = attr(sdk, "targetSdkVersion")
    if min_v == "-" and "sdkInfo.minSdkVersion" in yml:
        min_v = f"{yml['sdkInfo.minSdkVersion']}（取自 apktool.yml）"
    if tgt_v == "-" and "sdkInfo.targetSdkVersion" in yml:
        tgt_v = f"{yml['sdkInfo.targetSdkVersion']}（取自 apktool.yml）"
    print(f"- **minSdk**: {min_v}   **targetSdk**: {tgt_v}")
    if sdk is None:
        print("  > 清单里没有 `<uses-sdk>` 节点：apktool 只在 apktool.yml 记录，"
              "系统按平台默认值处理，现代 Android 会套用旧版兼容行为。")

    app = root.find("application")
    print()
    print("| 标记 | 值 | 含义 |")
    print("|---|---|---|")
    flags = [
        ("debuggable", "true = 可直接附加调试器，几乎等于源码可见"),
        ("allowBackup", "true = 可用 adb backup 拖数据"),
        ("usesCleartextTraffic", "true = 允许明文 HTTP"),
        ("extractNativeLibs", "true = so 解压到磁盘，便于替换"),
        ("networkSecurityConfig", "自定义证书校验策略"),
        ("name", "Application 类"),
        ("process", "运行进程名"),
    ]
    for key, meaning in flags:
        val = attr(app, key)
        if val != "-":
            print(f"| `{key}` | `{val}` | {meaning} |")

    raw_tgt = root.find("uses-sdk")
    target_num = None
    for cand in (attr(raw_tgt, "targetSdkVersion"), yml.get("sdkInfo.targetSdkVersion")):
        if cand and cand.isdigit():
            target_num = int(cand)
            break
    if target_num is not None and target_num < 28 and attr(app, "usesCleartextTraffic") == "-":
        print()
        print(f"> targetSdk {target_num} < 28 且未声明 `usesCleartextTraffic`，"
              "系统默认**允许明文 HTTP**，抓包和中间人改造都不需要额外条件。")

    exposed = []
    for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
        for el in root.findall(f"application/{tag}"):
            exposed.append((tag, el))

    if exposed:
        print()
        print("**组件清单**（导出 = 可被外部应用直接调起，是主要攻击面）")
        print()
        print("| 类型 | 名称 | 导出 | 权限 | intent-filter 线索 |")
        print("|---|---|---|---|---|")
        for tag, el in exposed:
            name = strip_pkg(attr(el, "name"), pkg)
            auth = attr(el, "authorities")
            if auth != "-":
                name += f" （authorities: {auth}）"
            exp = attr(el, "exported")
            has_filter = el.find("intent-filter") is not None
            if exp == "-":
                exp = "默认(有filter→是)" if has_filter else "默认(否)"
            hints = set()
            for f in el.findall("intent-filter"):
                for a in f.findall("action"):
                    hints.add(attr(a, "name").replace("android.intent.action.", ""))
                for d in f.findall("data"):
                    scheme = attr(d, "scheme")
                    host = attr(d, "host")
                    if scheme != "-":
                        hints.add(f"{scheme}://{'' if host == '-' else host}")
                for c in f.findall("category"):
                    cat = attr(c, "name").replace("android.intent.category.", "")
                    if cat == "LAUNCHER":
                        hints.add("LAUNCHER")
            print(f"| {tag} | `{name}` | {exp} | {attr(el, 'permission')} | "
                  f"{', '.join(sorted(hints)) or '-'} |")

    perms = []
    for tag in ("uses-permission", "uses-permission-sdk-23"):
        for el in root.findall(tag):
            perms.append(attr(el, "name"))
    print()
    risky = [p for p in perms if short_perm(p) in DANGEROUS]
    privileged = [p for p in perms if short_perm(p) in PRIVILEGED_ONLY]
    print(f"**权限**：共 {len(perms)} 项，其中敏感 {len(risky)} 项")
    print()
    if risky:
        print("敏感权限（运行时会弹窗、审核和用户最在意的那些）：")
        print()
        for p in sorted(set(risky)):
            print(f"- `{p}`")
        print()
    if privileged:
        print("这些权限普通 App 拿不到（signature|privileged），出现即说明代码是旧模板"
              "或系统级应用改的，运行时通常被静默忽略：")
        print()
        for p in sorted(set(privileged)):
            print(f"- `{p}`")
        print()
    normal = sorted({short_perm(p) for p in perms}
                    - {short_perm(p) for p in risky}
                    - {short_perm(p) for p in privileged})
    if normal:
        print(f"普通权限：{', '.join(normal[:40])}"
              f"{' …' if len(normal) > 40 else ''}")

    if root.find("queries") is not None:
        print()
        print("> 声明了 `<queries>`：会主动探测设备上装了哪些 App（Android 11+）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
