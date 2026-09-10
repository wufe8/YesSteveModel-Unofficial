#!/usr/bin/env python
"""Collapse a Minecraft log into unique line "shapes" with occurrence counts.

Minecraft logs (especially with YSMU debug tags on) repeat the same message
thousands of times per second, varying only by timestamp/thread and by numeric
payload.  This tool groups lines by a normalized shape so the interesting
transitions stay readable, and can report the *distinct* payload values a tag
actually produced (which is what you usually want when a variable "doesn't
change": you want the set of values, not 2400 copies of them).

Usage
-----
    python tools/dedupe_mc_log.py <log> [options]

Common invocations
------------------
    # every distinct YSMU-SCOPE payload, with counts
    python tools/dedupe_mc_log.py latest.log --tag YSMU-SCOPE

    # only shapes seen in the last 5000 lines, newest group last
    python tools/dedupe_mc_log.py latest.log --tail 5000 --tag YSMU

    # keep digits meaningful (do not blank them out)
    python tools/dedupe_mc_log.py latest.log --no-normalize-numbers

    # show the first concrete example line per shape
    python tools/dedupe_mc_log.py latest.log --tag YSMU-CTRL-APPLY -e 1

Options
-------
  <log>                 Path to a .log or .log.gz file.
  -t/--tag TEXT         Only keep lines containing TEXT (repeatable, OR-ed).
  -f/--filter REGEX     Only keep lines matching REGEX (repeatable, OR-ed).
  -x/--exclude REGEX    Drop lines matching REGEX (repeatable).
  --tail N              Only consider the last N lines of the file.
  --from-line N         Only consider lines after the Nth line.
  --limit N             Cap printed groups (default 200).
  -e/--examples N       Print up to N concrete example lines per group (default 0).
  --max-example LEN     Truncate example lines to LEN chars (default 400).
  --no-normalize-numbers   Keep numbers as-is when building the shape key.
  --keep-time           Keep the leading timestamp in shape keys (default: strip).
  --sort count|first    Group ordering (default: first appearance).
  --summary             Print only the number of lines / groups.
  -q/--quiet            Suppress the trailing summary line.

Notes
-----
* The leading `[HH:MM:SS] [Thread/LEVEL]:` prefix is stripped from the shape key
  so the same message from different threads collapses together.
* Numeric payloads are replaced by `#` by default, so `... -> 0.0` and
  `... -> 1.0` collapse; use --no-normalize-numbers to keep them apart.
* Exit code is 0 even when nothing matched, so it is safe in scripts.
"""

from __future__ import annotations

import argparse
import gzip
import io
import re
import sys

# [12:34:56] [Client thread/INFO]:  |  [12:34:56] [YSMU-SyncStream/INFO]:
TIME_PREFIX = re.compile(r"^\[\d{2}:\d{2}:\d{2}\]\s*\[[^\]]*\]\s*:?\s?")
TIME_ONLY = re.compile(r"^\[\d{2}:\d{2}:\d{2}\]\s*")
NUMBER = re.compile(r"-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?")
HEX = re.compile(r"\b[0-9a-fA-F]{8,}\b")
UUID = re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                  r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")
RESLOC = re.compile(r"\b[a-z0-9_.-]+:[a-z0-9_./-]+\b")


def open_log(path: str) -> io.TextIOBase:
    if path.endswith(".gz"):
        return io.TextIOWrapper(gzip.open(path, "rb"), encoding="utf-8", errors="replace")
    return open(path, "r", encoding="utf-8", errors="replace")


def shape_key(line: str, normalize_numbers: bool, keep_time: bool) -> str:
    key = line.rstrip("\n")
    if keep_time:
        key = TIME_ONLY.sub("", key)
    else:
        key = TIME_PREFIX.sub("", key)
    if normalize_numbers:
        key = UUID.sub("<uuid>", key)
        key = HEX.sub("<hex>", key)
        key = RESLOC.sub("<res>", key)
        key = NUMBER.sub("#", key)
    return key


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(add_help=True, description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("log")
    ap.add_argument("-t", "--tag", action="append", default=[])
    ap.add_argument("-f", "--filter", action="append", default=[])
    ap.add_argument("-x", "--exclude", action="append", default=[])
    ap.add_argument("--tail", type=int, default=0)
    ap.add_argument("--from-line", type=int, default=0)
    ap.add_argument("--limit", type=int, default=200)
    ap.add_argument("-e", "--examples", type=int, default=0)
    ap.add_argument("--max-example", type=int, default=400)
    ap.add_argument("--no-normalize-numbers", action="store_true")
    ap.add_argument("--keep-time", action="store_true")
    ap.add_argument("--sort", choices=("first", "count"), default="first")
    ap.add_argument("--summary", action="store_true")
    ap.add_argument("-q", "--quiet", action="store_true")
    args = ap.parse_args(argv)

    normalize_numbers = not args.no_normalize_numbers
    filters = [re.compile(p) for p in args.filter]
    excludes = [re.compile(p) for p in args.exclude]

    kept: list[str] = []
    total = 0
    with open_log(args.log) as fh:
        for line in fh:
            total += 1
            if args.tag and not any(t in line for t in args.tag):
                continue
            if filters and not any(p.search(line) for p in filters):
                continue
            if excludes and any(p.search(line) for p in excludes):
                continue
            kept.append(line)

    if args.from_line:
        kept = kept[args.from_line:]
    if args.tail:
        kept = kept[-args.tail:]

    groups: dict[str, dict] = {}
    order: list[str] = []
    for line in kept:
        key = shape_key(line, normalize_numbers, args.keep_time)
        g = groups.get(key)
        if g is None:
            g = {"count": 0, "examples": []}
            groups[key] = g
            order.append(key)
        g["count"] += 1
        if len(g["examples"]) < args.examples:
            g["examples"].append(line.rstrip("\n")[:args.max_example])

    if args.sort == "count":
        order.sort(key=lambda k: groups[k]["count"], reverse=True)

    if not args.summary:
        shown = 0
        for key in order:
            if shown >= args.limit:
                break
            g = groups[key]
            print(f"[{g['count']:>6}x] {key}")
            for ex in g["examples"]:
                print(f"          | {ex}")
            shown += 1
        if len(order) > shown:
            print(f"... {len(order) - shown} more distinct shapes (use --limit)")

    if not args.quiet:
        print(f"--- lines={total} matched={len(kept)} distinct={len(groups)}",
              file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
