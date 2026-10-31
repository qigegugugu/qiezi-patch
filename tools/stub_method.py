#!/usr/bin/env python3
"""把一个 smali 方法的方法体整个替换成 return-void（或 return <常量>）。

用途：干掉指向已下线服务端的死网络调用。比"在方法开头插一条 return"更安全——
残留的不可达代码在 ART 校验器下可能触发类型合并问题，而整体清空方法体不会。

用法：
    stub_method.py <Class.smali> <方法名+签名> [--dry-run]

例：
    stub_method.py raw/smali/com/x/MainActivity.smali 'requestVersion()V' --dry-run
    stub_method.py raw/smali/com/x/MainActivity.smali 'requestVersion()V'

签名必须与 `.method` 行里冒号后的部分完全一致（含返回类型），例如：
    requestUser()V          无参无返回
    onEvent(Ljava/lang/String;)V

注意：只处理 `.locals` / `.registers` 声明，保留方法注解与 .param 名（若有）。
方法上有注解时不动注解块，因为注解可能被反射读取。
"""

import re
import sys


def find_method(lines, sig):
    """返回 (start, end) —— 命中 `.method ... sig` 到对应 `.end method` 的行号（含）。"""
    pat = re.compile(r"^\s*\.method\b.*\b" + re.escape(sig) + r"\s*$")
    starts = [i for i, ln in enumerate(lines) if pat.match(ln.rstrip("\n"))]
    if not starts:
        return None
    if len(starts) > 1:
        raise SystemExit(f"错误：签名 {sig} 命中 {len(starts)} 处，无法确定要改哪个")
    start = starts[0]
    for i in range(start + 1, len(lines)):
        if lines[i].strip() == ".end method":
            return start, i
    raise SystemExit(f"错误：方法 {sig} 没找到配对的 .end method（文件被截断？）")


def stub_block(header_line, indent_hint):
    """保留原 .method 行（含修饰符与签名的原始写法），方法体只留 return-void。"""
    return [
        header_line,
        "    .locals 0\n",
        "\n",
        "    return-void\n",
        ".end method\n",
    ]


def main(argv):
    if len(argv) < 3:
        raise SystemExit(__doc__.strip().splitlines()[0] + "\n见文件头注释了解用法")
    path, sig = argv[1], argv[2]
    dry = "--dry-run" in argv[3:]

    with open(path, "r", encoding="utf-8") as f:
        lines = f.readlines()

    hit = find_method(lines, sig)
    if hit is None:
        raise SystemExit(f"错误：{path} 里没有签名 {sig} 的方法")
    start, end = hit

    body_lines = end - start + 1
    new = lines[:start] + stub_block(lines[start], None) + lines[end + 1 :]

    print(f"命中：{path}")
    print(f"  方法：{lines[start].strip()}")
    print(f"  原方法体 {body_lines} 行 → 4 行（.locals 0 / return-void）")

    if dry:
        print("  --dry-run，未写盘")
        return 0

    with open(path, "w", encoding="utf-8") as f:
        f.writelines(new)
    print("  已写盘")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
