# -*- coding: utf-8 -*-
"""Parse and plot the temporary YSMU animation probes.

Two probe lines are understood (see AnimationController.probeBoneKeyFrames and
AnimationProcessor.watchBone):

    [YSMU-KF]   bone=… tick=… anim=… posN=… posTick=… posLen=… posStart=… posEnd=…
                scaleN=… sTick=… sLen=… sStart=… sEnd=…
    [YSMU-BONE] bone=… raw=… sx=… sy=… sz=… px=… py=… pz=…

Usage:
    python tools/plot_anim_probe.py <log> [--bone NAME] [--series px]
                                          [--svg anim_probe.svg]

These probes are commented out in the tree (see AnimationProcessor and
AnimationController, marked "TEMP PROBE (remove)"); uncomment them and rebuild
before collecting a log. `--bone` defaults to the watch list the probes carry.

Outputs: a text summary (constant vs animating, min/max, distinct counts), an
ASCII plot of value against tick, a CSV next to the SVG, and a dependency-free
SVG so the trajectory can be looked at in a browser.
"""
import argparse
import os
import re
import sys

KF_RE = re.compile(
    r"\[YSMU-KF\] bone=(?P<bone>\S+) tick=(?P<tick>\S+) anim=(?P<anim>\S+)"
    r" posN=(?P<posN>\S+) posTick=(?P<posTick>\S+) posLen=(?P<posLen>\S+)"
    r" posStart=(?P<posStart>\S+) posEnd=(?P<posEnd>\S+)"
    r" scaleN=(?P<scaleN>\S+) sTick=(?P<sTick>\S+) sLen=(?P<sLen>\S+)"
    r" sStart=(?P<sStart>\S+) sEnd=(?P<sEnd>\S+)")
BONE_RE = re.compile(
    r"\[YSMU-BONE\] bone=(?P<bone>\S+) raw=(?P<raw>\S+)"
    r" sx=(?P<sx>\S+) sy=(?P<sy>\S+) sz=(?P<sz>\S+)"
    r" px=(?P<px>\S+) py=(?P<py>\S+) pz=(?P<pz>\S+)")
TIME_RE = re.compile(r"^\[(\d\d:\d\d:\d\d)\]")


def fnum(text):
    if text is None or text == "null":
        return None
    try:
        return float(text)
    except ValueError:
        return None


def seconds(stamp):
    h, m, s = (int(x) for x in stamp.split(":"))
    return h * 3600 + m * 60 + s


def parse(path):
    kf, bone = [], []
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            tm = TIME_RE.match(line)
            t = seconds(tm.group(1)) if tm else None
            m = KF_RE.search(line)
            if m:
                d = {k: fnum(v) if k not in ("bone", "anim") else v
                     for k, v in m.groupdict().items()}
                d["time"] = t
                kf.append(d)
                continue
            m = BONE_RE.search(line)
            if m:
                d = {k: fnum(v) if k != "bone" else v for k, v in m.groupdict().items()}
                d["time"] = t
                bone.append(d)
    return kf, bone


def stats(rows, key):
    vals = [r[key] for r in rows if r.get(key) is not None]
    if not vals:
        return None
    uniq = sorted(set(vals))
    return {
        "n": len(vals),
        "distinct": len(uniq),
        "min": uniq[0],
        "max": uniq[-1],
        "first": vals[0],
        "last": vals[-1],
    }


def ascii_plot(rows, xkey, ykey, width=96, height=16, title=""):
    pts = [(r[xkey], r[ykey]) for r in rows
           if r.get(xkey) is not None and r.get(ykey) is not None]
    if len(pts) < 2:
        return "    (not enough samples for %s)" % ykey
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    x0, x1 = min(xs), max(xs)
    y0, y1 = min(ys), max(ys)
    if x1 - x0 < 1e-9 or y1 - y0 < 1e-9:
        return "    %s is CONSTANT (%.4f) over %d samples" % (ykey, ys[0], len(ys))
    grid = [[" "] * width for _ in range(height)]
    for x, y in pts:
        cx = int((x - x0) / (x1 - x0) * (width - 1))
        cy = int((y - y0) / (y1 - y0) * (height - 1))
        grid[height - 1 - cy][cx] = "#"
    out = []
    if title:
        out.append("    " + title)
    for i, row in enumerate(grid):
        val = y1 - (y1 - y0) * i / (height - 1)
        out.append("    %9.3f |%s" % (val, "".join(row)))
    out.append("              +" + "-" * width)
    out.append("               %-9.2f%s%.2f   (x = %s)" % (x0, " " * (width - 20), x1, xkey))
    return "\n".join(out)


def svg_plot(series, path):
    """series: list of (label, [(x, y), ...]). Minimal dependency-free SVG."""
    w, h, pad = 900, 260, 46
    lanes = len(series)
    parts = ['<?xml version="1.0" encoding="UTF-8"?>',
             '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d">' % (w, h * lanes),
             '<rect width="100%%" height="100%%" fill="#111"/>']
    colors = ["#4fc3f7", "#ffb74d", "#81c784", "#e57373", "#ba68c8"]
    for li, (label, pts) in enumerate(series):
        if len(pts) < 2:
            continue
        ys = [p[1] for p in pts]
        y0, y1 = min(ys), max(ys)
        if y1 - y0 < 1e-9:
            y1 = y0 + 1.0
        x0, x1 = pts[0][0], pts[-1][0]
        if x1 - x0 < 1e-9:
            x1 = x0 + 1.0
        oy = li * h
        parts.append('<text x="8" y="%d" fill="#eee" font-family="monospace" font-size="13">%s  '
                     'min=%.4f max=%.4f n=%d</text>' % (oy + 16, label, y0, y1, len(pts)))
        parts.append('<rect x="%d" y="%d" width="%d" height="%d" fill="none" stroke="#333"/>'
                     % (pad, oy + 24, w - pad - 8, h - 40))
        poly = []
        for x, y in pts:
            px = pad + (x - x0) / (x1 - x0) * (w - pad - 8)
            py = oy + 24 + (h - 40) * (1.0 - (y - y0) / (y1 - y0))
            poly.append("%.1f,%.1f" % (px, py))
        parts.append('<polyline fill="none" stroke="%s" stroke-width="1.2" points="%s"/>'
                     % (colors[li % len(colors)], " ".join(poly)))
    parts.append("</svg>")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(parts))


def png_plot(series, path):
    """Optional matplotlib output (the default SVG is dependency-free)."""
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except Exception as exc:  # pragma: no cover
        print("  (matplotlib unavailable: %s — keeping the SVG)" % exc)
        return False
    lanes = max(len(series), 1)
    fig, axes = plt.subplots(lanes, 1, figsize=(12, 2.2 * lanes), squeeze=False)
    for ax, (label, pts) in zip(axes[:, 0], series):
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        ax.plot(xs, ys, linewidth=1.0)
        ax.set_title(label, fontsize=8)
        ax.grid(True, linewidth=0.3)
    fig.tight_layout()
    fig.savefig(path, dpi=110)
    print("  wrote %s" % path)
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("log")
    ap.add_argument("--bone", default=None, help="restrict to one bone")
    ap.add_argument("--svg", default="anim_probe.svg")
    ap.add_argument("--png", default=None, help="also write a PNG (needs matplotlib)")
    args = ap.parse_args()

    kf, bone = parse(args.log)
    print("parsed: %d [YSMU-KF] lines, %d [YSMU-BONE] lines" % (len(kf), len(bone)))
    if not kf and not bone:
        print("no probe lines found (old format?)")
        return 1

    bones = sorted({r["bone"] for r in kf} | {r["bone"] for r in bone})
    if args.bone:
        bones = [b for b in bones if b == args.bone]

    for b in bones:
        kb = [r for r in kf if r["bone"] == b]
        bb = [r for r in bone if r["bone"] == b]
        print("\n=== %s ===" % b)
        if kb:
            span = (kb[-1]["time"] - kb[0]["time"]) if kb[0]["time"] is not None else 0
            print("  KF samples=%d span=%ss (~%.1f/s)" % (len(kb), span, len(kb) / max(span, 1)))
            print("  merged animation lengths: %s"
                  % sorted({r["anim"] for r in kb}))
            for k in ("tick", "posTick", "posLen", "posStart", "posEnd",
                      "sTick", "sLen", "sStart", "sEnd"):
                s = stats(kb, k)
                if s is None:
                    continue
                verdict = "CONSTANT" if s["distinct"] == 1 else "varies"
                print("    %-9s n=%-5d distinct=%-6d min=%-12.4f max=%-12.4f %s"
                      % (k, s["n"], s["distinct"], s["min"], s["max"], verdict))
            print(ascii_plot(kb, "tick", "posStart", title="KF point: tick -> posStart (x)"))
            print(ascii_plot(kb, "tick", "sEnd", title="KF point: tick -> scale end value"))
        if bb:
            # raw tick is monotone; use it as x when the controller tick is unknown
            print("  BONE samples=%d  (raw tick %s .. %s)"
                  % (len(bb), bb[0]["raw"], bb[-1]["raw"]))
            for k in ("px", "py", "pz", "sx", "sy", "sz"):
                s = stats(bb, k)
                if s is None:
                    continue
                verdict = "CONSTANT" if s["distinct"] == 1 else "varies"
                print("    %-3s n=%-5d distinct=%-6d min=%-10.4f max=%-10.4f %s"
                      % (k, s["n"], s["distinct"], s["min"], s["max"], verdict))
            print(ascii_plot(bb, "raw", "px", title="final bone: raw tick -> position x"))

    # CSV + SVG for eyeballing
    svg_dir = os.path.dirname(os.path.abspath(args.svg))
    if svg_dir and not os.path.isdir(svg_dir):
        os.makedirs(svg_dir)
    series = []
    csv_path = os.path.splitext(args.svg)[0] + ".csv"
    with open(csv_path, "w", encoding="utf-8") as fh:
        fh.write("tag,bone,tick,posStart,posEnd,scaleEnd,px,py,pz\n")
        for r in kf:
            if args.bone and r["bone"] != args.bone:
                continue
            fh.write("KF,%s,%.4f,%s,%s,%s,,,\n" % (r["bone"], r["tick"] or 0,
                                                   r["posStart"], r["posEnd"], r["sEnd"]))
        for r in bone:
            if args.bone and r["bone"] != args.bone:
                continue
            fh.write("BONE,%s,%.4f,,,,%s,%s,%s\n"
                     % (r["bone"], r["raw"] or 0, r["px"], r["py"], r["pz"]))
    for b in bones:
        kb = [(r["tick"], r["posStart"]) for r in kf if r["bone"] == b and r["posStart"] is not None]
        if len(kb) > 1:
            series.append(("KF %s posStart vs tick" % b, kb))
        bb = [(r["raw"], r["px"]) for r in bone if r["bone"] == b and r["px"] is not None]
        if len(bb) > 1:
            series.append(("BONE %s px vs raw tick" % b, bb))
    if series:
        svg_plot(series, args.svg)
        print("\nwrote %s\nwrote %s" % (args.svg, csv_path))
        if args.png:
            png_plot(series, args.png)
    return 0


if __name__ == "__main__":
    sys.exit(main())
