#!/usr/bin/env python3
"""对比两份堆直方图（`jcmd <pid> GC.class_histogram` 的输出），按类看存活集的增减。

**先看这里，别拿"当前堆/峰值堆"对比。** 直方图有两条完全不同的路：

  * `jcmd <pid> GC.class_histogram`        —— 会先做一次 Full GC，只统计**可达对象**；
    这才是"常驻占用"，两个版本之间可以比（本工具就是比这个）。
  * `jcmd <pid> GC.class_histogram -all`   —— 不回收，**连不可达对象一起数**；
    它衡量的是"分配速率"，同一份构建的不同时刻都能差一倍以上。
    VisualVM sampler 里导出的堆直方图属于后者（能看到 TLAB 填充/未回收的临时对象）。

所以用法是：两个构建各跑一次同样的场景，在**同一个时刻**（例如预览页停 10 秒后）
各取一份 live 直方图，然后：

    jcmd <pid> GC.class_histogram > local/logs/live-<版本>.txt
    python tools/heap_hist_diff.py local/logs/live-A.txt local/logs/live-B.txt
    python tools/heap_hist_diff.py A.txt B.txt --only com.fox.ysmu --top 40

输出按 |Δbytes| 排序，并给出只在某一侧出现的类（新增/消失的常驻结构最容易看这里）。
"""
import argparse
import re
import sys

ROW_RE = re.compile(r"^\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)(?:\s+\(.*\))?\s*$")
TOTAL_RE = re.compile(r"^Total\s+(\d+)\s+(\d+)")


def parse(path):
    rows = {}
    total = None
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.rstrip("\n")
            total_match = TOTAL_RE.match(line.strip())
            if total_match:
                total = (int(total_match.group(1)), int(total_match.group(2)))
                continue
            match = ROW_RE.match(line)
            if not match:
                continue
            instances = int(match.group(1).replace(",", ""))
            size = int(match.group(2).replace(",", ""))
            name = match.group(3)
            rows[name] = (instances, size)
    if total is None:
        total = (sum(v[0] for v in rows.values()), sum(v[1] for v in rows.values()))
    return rows, total


def mib(value):
    return f"{value / 1048576:.1f}M"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("a", help="基线直方图（改动前）")
    parser.add_argument("b", help="对比直方图（改动后）")
    parser.add_argument("--top", type=int, default=25, help="按 |Δbytes| 显示前 N 行（默认 25，0=全部）")
    parser.add_argument("--min-bytes", type=int, default=65536,
                        help="忽略 |Δbytes| 小于该值的行（默认 64KiB，用来滤掉噪声）")
    parser.add_argument("--only", default=None, help="只看类名包含该子串的行（例如 com.fox.ysmu）")
    args = parser.parse_args()

    rows_a, total_a = parse(args.a)
    rows_b, total_b = parse(args.b)

    print(f"A = {args.a}   live {mib(total_a[1])} / {total_a[0]:,} objects")
    print(f"B = {args.b}   live {mib(total_b[1])} / {total_b[0]:,} objects")
    delta_bytes = total_b[1] - total_a[1]
    delta_objects = total_b[0] - total_a[0]
    print(f"delta = {delta_bytes / 1048576:+.1f} MiB ({delta_bytes / max(total_a[1], 1) * 100:+.1f}%)"
          f"   {delta_objects:+,} objects")
    print()

    diffs = []
    for name in set(rows_a) | set(rows_b):
        inst_a, size_a = rows_a.get(name, (0, 0))
        inst_b, size_b = rows_b.get(name, (0, 0))
        if args.only and args.only.lower() not in name.lower():
            continue
        if abs(size_b - size_a) < args.min_bytes and inst_a and inst_b:
            continue
        diffs.append((size_b - size_a, inst_b - inst_a, inst_a, size_a, inst_b, size_b, name))
    diffs.sort(key=lambda row: -abs(row[0]))

    print(f"{'d-bytes':>12} {'d-objects':>12} {'A objects':>12} {'B objects':>12}  class")
    for _, dinst, inst_a, size_a, inst_b, size_b, name in (diffs if args.top <= 0 else diffs[:args.top]):
        flag = ""
        if inst_a == 0:
            flag = "  [new]"
        elif inst_b == 0:
            flag = "  [gone]"
        print(f"{mib(size_b - size_a):>12} {dinst:>+12,} {inst_a:>12,} {inst_b:>12,}  {name}{flag}")

    shown = sum(row[0] for row in (diffs if args.top <= 0 else diffs[:args.top]))
    if args.top > 0 and len(diffs) > args.top:
        print(f"... {len(diffs) - args.top} more classes, |delta| total of all filtered rows "
              f"{sum(abs(row[0]) for row in diffs) / 1048576:.1f} MiB, shown {shown / 1048576:+.1f} MiB")


if __name__ == "__main__":
    main()
