#!/usr/bin/env python3
"""把 JVM 统一 GC 日志（`-Xlog:gc*`）汇总成"可比"的几个量。

为什么需要它：Minecraft 的"当前堆占用 / 峰值堆"随 GC 时机和 ergonomics 大幅浮动
（ZGC 还会主动把内存要满），跨版本比这些数字没有意义。真正稳定、且与我们要优化的
东西一一对应的只有三个量：

  * 分配速率（≈ 每秒有多少垃圾被造出来）—— churn，优化分配路径时它下降；
  * STW 暂停总时长 / 次数 —— 用户体感到的卡顿；
  * Full GC 之后的存活堆 —— 真正常驻的东西（见 tools/heap_hist_diff.py）。

分配速率的估算方式：把每次 Young/Full 暂停"回收掉的字节数"（before - after）加起来
除以日志时长。GC 跟得上时它就等于分配速率；注意它**低估**晋升到老年代的那部分
（这部分没被回收，也就没被算进来）。

用法：
    java -Xmx4G -XX:+UseG1GC -Xlog:gc*:file=logs/gc.log:time,uptime,level,tags ...
    python tools/gc_log_summary.py logs/gc.log
    python tools/gc_log_summary.py logs/gc.log --per-minute
"""
import argparse
import re
import sys

HEAP_RE = re.compile(r"(\d+(?:\.\d+)?)([KMG])->(\d+(?:\.\d+)?)([KMG])\((\d+(?:\.\d+)?)([KMG])\)")
MS_RE = re.compile(r"([\d.]+)ms\s*$")
UPTIME_RE = re.compile(r"\[(\d+(?:\.\d+)?)s\]")
ISO_RE = re.compile(r"T(\d{2}):(\d{2}):(\d{2}(?:\.\d+)?)")
UNIT = {"K": 1024, "M": 1024 ** 2, "G": 1024 ** 3}


def _bytes(value, unit):
    return float(value) * UNIT[unit]


def classify(line):
    """返回 (类型, 是否 STW)，类型取值 young/full/other/concurrent/init。"""
    body = line.split("[gc", 1)[-1]
    if "Pause Full" in line or "Full GC" in line:
        return "full", True
    if "Pause Young" in line or "Minor" in line:
        return "young", True
    if "Pause" in line or "GC(" in line and "Concurrent" not in line and "->" in line:
        return "other", True
    if "Concurrent" in line:
        return "concurrent", False
    return "init", False


def parse(path):
    """→ (事件列表, 时长秒, 堆上限字节, 日志行数)。事件 = dict。"""
    events = []
    first_time = last_time = None
    iso_first = None
    heap_limit = None
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            up = UPTIME_RE.search(line)
            if up:
                seconds = float(up.group(1))
                first_time = seconds if first_time is None else first_time
                last_time = seconds
            else:
                iso = ISO_RE.search(line)
                if iso:
                    seconds = int(iso.group(1)) * 3600 + int(iso.group(2)) * 60 + float(iso.group(3))
                    if iso_first is None:
                        iso_first = seconds
                        first_time, last_time = 0.0, 0.0
                    else:
                        last_time = seconds - iso_first
            match = HEAP_RE.search(line)
            if not match:
                continue
            before = _bytes(match.group(1), match.group(2))
            after = _bytes(match.group(3), match.group(4))
            limit = _bytes(match.group(5), match.group(6))
            if before > 0:
                heap_limit = max(heap_limit or 0, limit)
            kind, stw = classify(line)
            ms = MS_RE.search(line)
            events.append({
                "kind": kind,
                "stw": stw,
                "before": before,
                "after": after,
                "reclaimed": max(before - after, 0.0),
                "pause_ms": float(ms.group(1)) if ms else 0.0,
                "at": last_time if last_time is not None else 0.0,
            })
    duration = (last_time - first_time) if (first_time is not None and last_time is not None) else 0.0
    duration = max(duration, 0.001)
    return events, duration, heap_limit, len(events)


def mib(value):
    return f"{value / 1048576:.0f}M"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("logs", nargs="+", help="GC 日志文件（可多个）")
    parser.add_argument("--from-s", type=float, default=None, help="只看 uptime >= 该秒数的区间（避开启动/加载阶段）")
    parser.add_argument("--to-s", type=float, default=None, help="只看 uptime <= 该秒数的区间")
    parser.add_argument("--per-minute", action="store_true", help="再打一张按分钟的分配速率/暂停表")
    parser.add_argument("--top", type=int, default=5, help="最长暂停的前 N 名（默认 5）")
    args = parser.parse_args()

    for path in args.logs:
        try:
            events, duration, heap_limit, _ = parse(path)
        except OSError as error:
            print(f"{path}: 读不了（{error}）", file=sys.stderr)
            continue
        if not events:
            print(f"{path}: 没解析到任何 GC 事件（日志里需要带 [gc] 装饰与 'X->Y(Z)' 形式的堆变化）")
            continue
        if args.from_s is not None or args.to_s is not None:
            low = args.from_s if args.from_s is not None else 0.0
            high = args.to_s if args.to_s is not None else float("inf")
            events = [e for e in events if low <= e["at"] <= high]
            if events:
                duration = max(events[-1]["at"] - events[0]["at"], 0.001)

        young = [e for e in events if e["kind"] == "young"]
        full = [e for e in events if e["kind"] == "full"]
        other = [e for e in events if e["kind"] == "other"]
        concurrent = [e for e in events if e["kind"] == "concurrent"]
        stw = [e for e in events if e["stw"]]
        pause_ms = sum(e["pause_ms"] for e in stw)
        reclaimed = sum(e["reclaimed"] for e in young + full)

        print(f"== {path}")
        print(f"   duration {duration:.1f}s" + (f"   heap limit {mib(heap_limit)}" if heap_limit else ""))
        print(f"   GC: young {len(young)}  full {len(full)}  other-stw {len(other)}  concurrent {len(concurrent)}"
              f"   -> {len(stw) / (duration / 60):.1f} STW/min")
        print(f"   STW pause total {pause_ms / 1000:.2f}s ({pause_ms / 1000 / duration * 100:.1f}% wall)"
              f"  avg {pause_ms / max(len(stw), 1):.1f}ms  max {max((e['pause_ms'] for e in stw), default=0):.1f}ms")
        print(f"   allocation rate (from reclaimed bytes at pauses): {reclaimed / duration / 1048576:.1f} MiB/s")
        # 两次 STW 之间堆"净涨"的速率：理论上 ≈ 分配速率 − 晋升，
        # 它和上面那个数一致才说明堆曲线看到的就是分配速率（否则是采样混叠）。
        ordered = sorted(stw, key=lambda e: e["at"])
        net = 0.0
        for previous, current in zip(ordered, ordered[1:]):
            net += max(current["before"] - previous["after"], 0.0)
        print(f"   net heap fill rate (between STW pauses, {len(ordered)} pauses):"
              f" {net / duration / 1048576:.1f} MiB/s")
        if young:
            print(f"   young avg heap: {mib(sum(e['before'] for e in young) / len(young))} ->"
                  f" {mib(sum(e['after'] for e in young) / len(young))}")
        if full:
            last = full[-1]
            print(f"   live heap after last full GC: {mib(last['after'])}"
                  + (f" ({last['after'] / heap_limit * 100:.1f}% of limit)" if heap_limit else ""))
        else:
            last = stw[-1] if stw else None
            if last:
                print(f"   heap after last STW GC: {mib(last['after'])}  (no full GC -> old gen not compacted;"
                      f" use 'jcmd GC.class_histogram' for the live set)")

        if args.top:
            print("   longest pauses:")
            for event in sorted(stw, key=lambda e: -e["pause_ms"])[:args.top]:
                print(f"     {event['pause_ms']:8.1f}ms  {event['kind']:<6} {mib(event['before'])} -> {mib(event['after'])}"
                      f"  @{event['at']:.0f}s")

        if args.per_minute and duration > 60:
            print("   per minute:")
            print("     minute   gcs   pause(s)   reclaimed(MiB)   alloc(MiB/s)")
            buckets = {}
            for event in stw:
                minute = int(event["at"] // 60)
                bucket = buckets.setdefault(minute, [0, 0.0, 0.0])
                bucket[0] += 1
                bucket[1] += event["pause_ms"] / 1000
                bucket[2] += event["reclaimed"]
            for minute in sorted(buckets):
                count, pause, rec = buckets[minute]
                print(f"     {minute:5d}   {count:5d}   {pause:8.3f}   {rec / 1048576:12.1f}   {rec / 60 / 1048576:11.1f}")
        print()


if __name__ == "__main__":
    main()
