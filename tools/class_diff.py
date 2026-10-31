#!/usr/bin/env python3
"""对比两个 APK 的类名集合，证明"改包没丢代码"。

体积变小是 smali 汇编器的正常现象（常量池/调试信息压缩），不能用来判断是否丢类。
真正可靠的证据是类名集合差分。

用法：
    tools/venv/bin/python tools/class_diff.py <旧.apk> <新.apk>
"""

import sys

from androguard.core.apk import APK
from androguard.core.dex import DEX


def dex_names(path):
    apk = APK(path)
    names = set()
    for raw in apk.get_all_dex():
        for c in DEX(raw).get_classes():
            names.add(c.get_name())
    return names


def main(argv):
    if len(argv) != 3:
        raise SystemExit(f"用法: {argv[0]} <旧.apk> <新.apk>")
    old_path, new_path = argv[1], argv[2]

    old = dex_names(old_path)
    new = dex_names(new_path)

    added = sorted(new - old)
    lost = sorted(old - new)

    print(f"旧包类数: {len(old)}")
    print(f"新包类数: {len(new)}")
    print(f"新增 {len(added)} 个:")
    for n in added:
        print(f"  + {n}")
    print(f"丢失 {len(lost)} 个:")
    for n in lost:
        print(f"  - {n}")

    return 0 if not lost else 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
