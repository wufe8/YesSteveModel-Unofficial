#!/usr/bin/env python3
"""Minimal spark sampler (.sparkprofile / bytebin) protobuf reader.

Schema source: lucko/spark spark-common/src/main/proto/spark/spark_sampler.proto

No dependencies: we decode the protobuf wire format directly.

Usage:
    python local/tools/spark_dump.py spark.bin --meta
    python local/tools/spark_dump.py spark.bin --thread "Client thread" --top 40
    python local/tools/spark_dump.py spark.bin --thread "Client thread" --json out.json
"""
import argparse
import json
import struct
import sys
from collections import defaultdict


# ---------------------------------------------------------------- wire format
def read_varint(buf, pos):
    result = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, pos
        shift += 7


def iter_fields(buf):
    """Yield (field_number, wire_type, payload) for a protobuf message body."""
    pos = 0
    n = len(buf)
    while pos < n:
        key, pos = read_varint(buf, pos)
        fnum, wtype = key >> 3, key & 0x7
        if wtype == 0:  # varint
            val, pos = read_varint(buf, pos)
            yield fnum, wtype, val
        elif wtype == 1:  # 64-bit
            yield fnum, wtype, buf[pos:pos + 8]
            pos += 8
        elif wtype == 2:  # length-delimited
            ln, pos = read_varint(buf, pos)
            yield fnum, wtype, buf[pos:pos + ln]
            pos += ln
        elif wtype == 5:  # 32-bit
            yield fnum, wtype, buf[pos:pos + 4]
            pos += 4
        else:
            raise ValueError("unsupported wire type %d" % wtype)


def as_str(payload):
    return payload.decode("utf-8", "replace")


def packed_doubles(payload):
    return list(struct.unpack("<%dd" % (len(payload) // 8), payload))


def packed_varints(payload):
    out = []
    pos = 0
    while pos < len(payload):
        v, pos = read_varint(payload, pos)
        out.append(v)
    return out


def scalar_doubles(fnum, wtype, payload, out):
    """Handle a repeated double that may be packed (wt=2) or unpacked (wt=1)."""
    if wtype == 2:
        out.extend(packed_doubles(payload))
    elif wtype == 1:
        out.append(struct.unpack("<d", payload)[0])


# ------------------------------------------------------------------- node
class Node:
    __slots__ = ("class_name", "method_name", "parent_line", "line",
                 "method_desc", "times", "children")

    def __init__(self):
        self.class_name = ""
        self.method_name = ""
        self.parent_line = 0
        self.line = 0
        self.method_desc = ""
        self.times = []
        self.children = []

    def label(self):
        cn = self.class_name.rsplit(".", 1)[-1] if self.class_name else ""
        if cn:
            return "%s.%s" % (cn, self.method_name)
        if self.method_name:
            return self.method_name
        return "(unknown)"

    def full(self):
        if self.class_name:
            return "%s.%s" % (self.class_name, self.method_name)
        return self.method_name or "(unknown)"


def parse_stack_node(buf):
    n = Node()
    for fnum, wtype, payload in iter_fields(buf):
        if fnum == 3:
            n.class_name = as_str(payload)
        elif fnum == 4:
            n.method_name = as_str(payload)
        elif fnum == 5:
            n.parent_line = payload
        elif fnum == 6:
            n.line = payload
        elif fnum == 7:
            n.method_desc = as_str(payload)
        elif fnum == 8:
            scalar_doubles(fnum, wtype, payload, n.times)
        # 9 = children_refs: already resolved into the child list order
    return n


def parse_thread(buf):
    name = ""
    times = []
    stack_nodes = []
    for fnum, wtype, payload in iter_fields(buf):
        if fnum == 1:
            name = as_str(payload)
        elif fnum == 3:
            stack_nodes.append(parse_stack_node(payload))
        elif fnum == 4:
            scalar_doubles(fnum, wtype, payload, times)
    # children_refs index into the *sibling-order* flat list: spark emits the
    # whole thread's node list in one array, children_refs point to positions.
    # We rebuild by parsing the recursion in wire order instead: StackTraceNode
    # children appear immediately after their parent, so reconstruct with a
    # depth-first pass over the raw bytes.
    return name, times, stack_nodes


# The flat-list reconstruction above loses the tree, so re-parse thread bytes
# with a proper recursive descent that keeps children_refs.
def parse_thread_tree(buf):
    name = ""
    times = []
    flat = []
    root_refs = []
    for fnum, wtype, payload in iter_fields(buf):
        if fnum == 1:
            name = as_str(payload)
        elif fnum == 3:
            flat.append(payload)
        elif fnum == 4:
            scalar_doubles(fnum, wtype, payload, times)
        elif fnum == 5:
            root_refs = packed_varints(payload) if wtype == 2 else [payload]
    nodes = []
    refs = []
    for raw in flat:
        node = Node()
        child_refs = []
        for fnum, wtype, payload in iter_fields(raw):
            if fnum == 3:
                node.class_name = as_str(payload)
            elif fnum == 4:
                node.method_name = as_str(payload)
            elif fnum == 5:
                node.parent_line = payload
            elif fnum == 6:
                node.line = payload
            elif fnum == 7:
                node.method_desc = as_str(payload)
            elif fnum == 8:
                scalar_doubles(fnum, wtype, payload, node.times)
            elif fnum == 9:
                child_refs = packed_varints(payload) if wtype == 2 else [payload]
        nodes.append(node)
        refs.append(child_refs)
    for i, cr in enumerate(refs):
        for c in cr:
            nodes[i].children.append(nodes[c])
    root = Node()
    root.class_name = ""
    root.method_name = name
    root.times = list(times)
    for c in root_refs:
        root.children.append(nodes[c])
    return name, times, root, nodes


# ------------------------------------------------------------------ metadata
def parse_platform_meta(buf):
    out = {}
    for fnum, wtype, payload in iter_fields(buf):
        if fnum == 2:
            out["name"] = as_str(payload)
        elif fnum == 3:
            out["version"] = as_str(payload)
        elif fnum == 4:
            out["minecraft_version"] = as_str(payload)
        elif fnum == 7:
            out["spark_data_version"] = payload
        elif fnum == 8:
            out["brand"] = as_str(payload)
        elif fnum == 9:
            out["spark_version"] = as_str(payload)
    return out


def parse_sources(buf):
    """map<string, PluginOrModMetadata> — key field 1, value field 2."""
    key, meta = None, {}
    for fnum, wtype, payload in iter_fields(buf):
        if fnum == 1:
            key = as_str(payload)
        elif fnum == 2:
            for f2, w2, p2 in iter_fields(payload):
                if f2 == 1:
                    meta["name"] = as_str(p2)
                elif f2 == 2:
                    meta["version"] = as_str(p2)
                elif f2 == 3:
                    meta["author"] = as_str(p2)
    return key, meta


def parse_metadata(buf):
    md = {"sources": {}}
    for fnum, wtype, payload in iter_fields(buf):
        if fnum == 2:
            md["start_time"] = payload
        elif fnum == 3:
            md["interval"] = payload
        elif fnum == 7:
            md["platform"] = parse_platform_meta(payload)
        elif fnum == 11:
            md["end_time"] = payload
        elif fnum == 12:
            md["number_of_ticks"] = payload
        elif fnum == 13:
            k, m = parse_sources(payload)
            if k:
                md["sources"][k] = m
        elif fnum == 15:
            md["sampler_mode"] = payload
        elif fnum == 16:
            md["sampler_engine"] = payload
        elif fnum == 17:
            md["sampler_engine_version"] = as_str(payload)
    return md


def parse_sampler(buf):
    data = {"threads": [], "time_windows": [], "metadata": {}}
    for fnum, wtype, payload in iter_fields(buf):
        if fnum == 1:
            data["metadata"] = parse_metadata(payload)
        elif fnum == 2:
            name, times, root, nodes = parse_thread_tree(payload)
            data["threads"].append({"name": name, "times": times,
                                    "root": root, "node_count": len(nodes)})
        elif fnum == 6:
            data["time_windows"] = packed_varints(payload) if wtype == 2 else [payload]
    return data


# ------------------------------------------------------------------ analysis
def total(root, window=None):
    if not root.times:
        return 0.0
    if window is None:
        return sum(root.times)
    return root.times[window] if window < len(root.times) else 0.0


def node_time(node, window=None):
    return total(node, window)


def exclusive(node, window=None):
    return max(0.0, node_time(node, window)
               - sum(node_time(c, window) for c in node.children))


def walk(node, predicate, path=()):
    """Yield (node, path) for every node matching predicate."""
    p = path + (node,)
    if predicate(node):
        yield node, p
    for c in node.children:
        yield from walk(c, predicate, p)


def print_tree(node, window, maxdepth, mindepth=0, indent=0, limit=1.0):
    if indent > maxdepth:
        return
    t = node_time(node, window)
    if t < limit:
        return
    ex = exclusive(node, window)
    print("%s%s  [incl %.3fs  self %.3fs]" % (
        "  " * indent, node.label(), t, ex))
    kids = sorted(node.children, key=lambda c: -node_time(c, window))
    for c in kids:
        print_tree(c, window, maxdepth, mindepth, indent + 1, limit)


def build_parents(root):
    parent = {id(root): None}
    stack = [root]
    while stack:
        n = stack.pop()
        for c in n.children:
            parent[id(c)] = n
            stack.append(c)
    return parent


def print_paths(root, pattern, limit=12, maxlabel=160):
    """Print every call path (root -> matching node) with inclusive times."""
    import re
    rx = re.compile(pattern)
    parent = build_parents(root)
    hits = []
    for n, _ in walk(root, lambda x: bool(rx.search(x.full()))):
        hits.append(n)
    hits.sort(key=lambda n: -node_time(n))
    print("=== %d node(s) matching /%s/ (sum incl = %.1f ms) ===" % (
        len(hits), pattern, sum(node_time(n) for n in hits)))
    for n in hits[:limit]:
        chain = []
        cur = n
        while cur is not None:
            chain.append(cur)
            cur = parent[id(cur)]
        chain.reverse()
        print("-- incl %.1f ms, self %.1f ms" % (node_time(n), exclusive(n)))
        for d, c in enumerate(chain):
            if d == 0:
                continue
            print("   %s%s   [%.1f]" % ("  " * (d - 1), c.full()[:maxlabel],
                                        node_time(c)))


def aggregate(root, key_fn):
    agg = defaultdict(float)
    for n, _ in walk(root, lambda x: True):
        v = exclusive(n)
        if v > 0:
            agg[key_fn(n)] += v
    return agg


def print_aggregate(root, key_fn, title, top=60, minv=0.0):
    agg = aggregate(root, key_fn)
    print("=== %s ===" % title)
    for k, v in sorted(agg.items(), key=lambda kv: -kv[1])[:top]:
        if v < minv:
            break
        print("%10.1f ms  %6.2f%%  %s" % (v, 100.0 * v / node_time(root), k))
    print()


def print_focus(root, pattern, limit=8, depth=4, minv=0.0):
    """Print the subtree under each node matching pattern, sized by inclusive time."""
    import re
    rx = re.compile(pattern)
    hits = [n for n, _ in walk(root, lambda x: bool(rx.search(x.full())))]
    hits.sort(key=lambda n: -node_time(n))
    total = node_time(root)
    print("=== focus /%s/ : %d node(s), sum incl %.1f ms ===" % (
        pattern, len(hits), sum(node_time(n) for n in hits)))

    def rec(n, d, prefix):
        t = node_time(n)
        if t < minv:
            return
        print("%s%-70s incl %8.1f (%5.2f%%)  self %8.1f" % (
            prefix, n.label()[:70], t, 100.0 * t / total, exclusive(n)))
        if d >= depth:
            return
        kids = sorted(n.children, key=lambda c: -node_time(c))
        for i, c in enumerate(kids[:12]):
            rec(c, d + 1, prefix + ("  " if i == 0 else "  "))

    for n in hits[:limit]:
        print("-" * 100)
        rec(n, 0, "")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--meta", action="store_true")
    ap.add_argument("--thread")
    ap.add_argument("--top", type=int, default=30)
    ap.add_argument("--depth", type=int, default=3)
    ap.add_argument("--min", type=float, default=0.0,
                    help="only show nodes with at least this inclusive time (ms)")
    ap.add_argument("--json")
    ap.add_argument("--paths", help="regex: print call paths to matching methods")
    ap.add_argument("--paths-limit", type=int, default=12)
    ap.add_argument("--focus",
                    help="regex: print the subtree under matching nodes (inclusive)")
    ap.add_argument("--focus-depth", type=int, default=4)
    ap.add_argument("--class-self", action="store_true",
                    help="self time aggregated per class")
    ap.add_argument("--package-self", action="store_true",
                    help="self time aggregated per package prefix")
    args = ap.parse_args()

    buf = open(args.file, "rb").read()
    data = parse_sampler(buf)
    md = data["metadata"]

    if args.meta:
        plat = md.get("platform", {})
        print("platform      :", plat)
        print("interval (us) :", md.get("interval"))
        print("start/end     :", md.get("start_time"), md.get("end_time"))
        print("duration (ms) :", (md.get("end_time", 0) - md.get("start_time", 0)))
        print("ticks         :", md.get("number_of_ticks"))
        print("engine        :", md.get("sampler_engine"),
              md.get("sampler_engine_version"))
        print("mode          :", md.get("sampler_mode"))
        print("time_windows  :", data["time_windows"])
        mods = md.get("sources", {})
        print("sources (%d)  :" % len(mods))
        for k in sorted(mods):
            print("   ", k, mods[k].get("version"))
        print("threads       :")
        for t in data["threads"]:
            print("    %-40s nodes=%-6d total=%.1f ms" % (
                t["name"], t["node_count"], sum(t["times"])))
        return

    threads = data["threads"]
    if args.thread:
        sel = [t for t in threads if args.thread.lower() in t["name"].lower()]
        if not sel:
            print("no thread matching", args.thread, file=sys.stderr)
            print([t["name"] for t in threads], file=sys.stderr)
            sys.exit(1)
    else:
        sel = [max(threads, key=lambda t: sum(t["times"]))]

    for t in sel:
        root = t["root"]
        total = node_time(root)
        print("=== thread: %s  total=%.1f ms ===" % (t["name"], total))

        if args.paths:
            print()
            print_paths(root, args.paths, args.paths_limit)
            continue

        if args.focus:
            print()
            print_focus(root, args.focus, args.paths_limit, args.focus_depth,
                        args.min)
            continue

        if args.class_self:
            print()
            print_aggregate(root, lambda n: n.class_name or "(none)",
                            "SELF time per class", args.top)
        if args.package_self:
            def pkg(n):
                f = n.full()
                parts = f.split(".")
                return ".".join(parts[:3]) if len(parts) > 3 else f
            print()
            print_aggregate(root, pkg, "SELF time per package", args.top)

        print()
        print_tree(root, None, args.depth, limit=args.min)
        print()
        print("--- top %d by SELF time ---" % args.top)
        agg = aggregate(root, lambda n: n.full())
        for name, v in sorted(agg.items(), key=lambda kv: -kv[1])[:args.top]:
            print("%10.1f ms  %6.2f%%  %s" % (v, 100.0 * v / total, name))
        print()
        print("--- top %d by INCLUSIVE time (dedup by method) ---" % args.top)
        incl = aggregate(root, lambda n: n.full())
        incl.clear()
        for n, _ in walk(root, lambda x: True):
            incl[n.full()] += node_time(n)
        for name, v in sorted(incl.items(), key=lambda kv: -kv[1])[:args.top]:
            print("%10.1f ms  %6.2f%%  %s" % (v, 100.0 * v / total, name))

    if args.json:
        def dump(n):
            return {
                "cls": n.class_name, "m": n.method_name,
                "line": n.line, "desc": n.method_desc,
                "times": n.times,
                "children": [dump(c) for c in n.children],
            }
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump({"metadata": md,
                       "threads": [{"name": t["name"], "times": t["times"],
                                    "root": dump(t["root"])} for t in threads]},
                      f)
        print("wrote", args.json)



if __name__ == "__main__":
    main()
