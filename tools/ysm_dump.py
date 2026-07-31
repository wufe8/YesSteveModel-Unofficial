#!/usr/bin/env python3
"""
YSMU Model Dump — 通用 YSM / OpenYSM 模型与动画检查工具。

替代多个“针对具体模型/动画名”的临时脚本，通过参数化通用化。

用法:
    # 列出某模型目录下所有动画（文件名 + 动画名），可 --key 过滤动画名
    python tools/ysm_dump.py list res/smx
    python tools/ysm_dump.py list res/smx --key tail

    # 转储指定动画（loop、长度、骨骼、各通道关键帧摘要）
    python tools/ysm_dump.py dump res/smx "尾巴物理实现"

    # 在几何（models/main.json / arm.json）中按骨骼名关键字搜索
    python tools/ysm_dump.py bones res/smx tail

    # 查找所有“骨骼名含关键字”的动画（排查 tail/arm 等骨骼被哪些动画驱动）
    python tools/ysm_dump.py find res/smx Tail

模型目录约定（与 res/ 下模型一致）：
    <model>/animations/*.animation.json    OpenYSM 风格动画文件
    <model>/models/main.json | arm.json   几何文件
    也兼容 <model>/*.animation.json 与 <model>/main.json 的扁平布局。
"""

import argparse
import json
import sys
from pathlib import Path
from typing import Dict, List, Optional


# ---------------------------------------------------------------------------
# 路径发现
# ---------------------------------------------------------------------------
def find_animation_files(model_dir: Path) -> List[Path]:
    """返回模型目录下所有 *.animation.json（兼容 animations/ 子目录与扁平布局）。"""
    files = []
    anim_dir = model_dir / "animations"
    if anim_dir.is_dir():
        files.extend(sorted(anim_dir.glob("*.animation.json")))
    for p in sorted(model_dir.glob("*.animation.json")):
        if p not in files:
            files.append(p)
    return files


def find_geometry_files(model_dir: Path) -> List[Path]:
    """返回几何文件 main.json / arm.json（兼容 models/ 子目录与扁平布局）。"""
    cands = []
    models_dir = model_dir / "models"
    for name in ("main.json", "arm.json"):
        if models_dir.is_dir() and (models_dir / name).is_file():
            cands.append(models_dir / name)
        elif (model_dir / name).is_file():
            cands.append(model_dir / name)
    return cands


def load_json(path: Path) -> Optional[dict]:
    try:
        with path.open("r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError) as e:
        print(f"  !! 无法解析 {path.name}: {e}", file=sys.stderr)
        return None


# ---------------------------------------------------------------------------
# list
# ---------------------------------------------------------------------------
def cmd_list(model_dir: Path, key: Optional[str]):
    files = find_animation_files(model_dir)
    if not files:
        print(f"未在 {model_dir} 找到 *.animation.json")
        return
    print(f"=== 动画文件（{len(files)}）===")
    kk = key.lower() if key else None
    total = 0
    for f in files:
        data = load_json(f)
        if not data:
            continue
        anims = data.get("animations", {})
        names = [n for n in anims.keys() if not kk or kk in n.lower()]
        if names:
            total += len(names)
            print(f"\n[{f.name}] ({len(names)})")
            for n in sorted(names):
                loop = anims[n].get("loop", False)
                print(f"    {n}  loop={loop}")
    print(f"\n共 {total} 个动画名")


# ---------------------------------------------------------------------------
# dump
# ---------------------------------------------------------------------------
def _fmt_keyframes(kf_type: str, kf_data) -> str:
    """把某骨骼某通道的关键帧数据描述成一行摘要。"""
    if isinstance(kf_data, dict):
        times = sorted(kf_data.keys())
        n = len(times)
        if times:
            first, last = times[0], times[-1]
            sample = kf_data[first]
            if isinstance(sample, (list, tuple)):
                val = ", ".join(str(round(x, 4)) for x in sample)
            else:
                val = str(sample)
            return f"{kf_type}: {n} kf [{first}..{last}] first=({val})"
        return f"{kf_type}: 0 kf"
    return f"{kf_type}: {type(kf_data).__name__}"


def _print_animation(f: Path, anim_name: str, a: dict) -> None:
    print(f"=== {anim_name}  @ {f.name} ===")
    print(f"loop: {a.get('loop')}")
    print(f"animation_length: {a.get('animation_length')}")
    extras = [k for k in a.keys()
              if k not in ("loop", "animation_length", "bones", "timeline", "molang")]
    if extras:
        print(f"other props: {extras}")
    bones = a.get("bones", {})
    print(f"bones: {len(bones)}")
    for bname, bdata in bones.items():
        print(f"\n  --- {bname} ---")
        if isinstance(bdata, dict):
            for kf_type, kf_data in bdata.items():
                print(f"      {_fmt_keyframes(kf_type, kf_data)}")
        else:
            print(f"      {type(bdata).__name__}")
    if "timeline" in a:
        print(f"\ntimeline events: {list(a['timeline'].keys())}")
    if "molang" in a:
        print(f"molang: {a['molang']}")


def cmd_dump(model_dir: Path, anim_name: str):
    files = find_animation_files(model_dir)
    candidates: List[tuple] = []  # (file, name, anim_data)
    for f in files:
        data = load_json(f)
        if not data:
            continue
        for name, a in data.get("animations", {}).items():
            candidates.append((f, name, a))

    exact = [c for c in candidates if c[1] == anim_name]
    if len(exact) == 1:
        _print_animation(*exact[0])
        return

    sub = [c for c in candidates if anim_name.lower() in c[1].lower()]
    if len(sub) == 1:
        f, name, a = sub[0]
        print(f"# 未精确匹配 '{anim_name}'，使用唯一子串 -> '{name}'")
        _print_animation(f, name, a)
        return
    if sub:
        print(f"子串 '{anim_name}' 匹配 {len(sub)} 个动画，请改用精确名称：")
        for f, name, _ in sub:
            print(f"  [{f.name}] {name}")
        return

    print(f"未找到动画 '{anim_name}'。现有动画名：")
    by_file: Dict[str, List[str]] = {}
    for f, name, _ in candidates:
        by_file.setdefault(f.name, []).append(name)
    for fname, names in by_file.items():
        print(f"  [{fname}] {', '.join(sorted(names)) if names else '(无)'}")


# ---------------------------------------------------------------------------
# bones / find
# ---------------------------------------------------------------------------
def _iter_bones(data: dict):
    for geo in data.get("minecraft:geometry") or []:
        yield from geo.get("bones", [])


def cmd_bones(model_dir: Path, key: str):
    kk = key.lower()
    files = find_geometry_files(model_dir)
    if not files:
        print(f"未在 {model_dir} 找到几何文件 (main.json/arm.json)")
        return
    for f in files:
        data = load_json(f)
        if not data:
            continue
        print(f"\n=== {f.name} ===")
        hits = 0
        for bone in _iter_bones(data):
            name = bone.get("name", "")
            if kk not in name.lower():
                continue
            hits += 1
            cubes = bone.get("cubes") or []
            children = bone.get("children") or []
            print(f"  {name}: parent={bone.get('parent', '(root)')} "
                  f"pivot={bone.get('pivot')} rot={bone.get('rotation')} "
                  f"cubes={len(cubes)} children={len(children)}")
        if hits == 0:
            print(f"  无匹配（关键字 '{key}'）")


def cmd_find(model_dir: Path, bone_key: str):
    """找出所有“骨骼名含关键字”的动画（即这些骨骼被哪些动画驱动）。"""
    kk = bone_key.lower()
    files = find_animation_files(model_dir)
    if not files:
        return
    print(f"=== 包含骨骼关键字 '{bone_key}' 的动画 ===")
    for f in files:
        data = load_json(f)
        if not data:
            continue
        for aname, adata in data.get("animations", {}).items():
            bones = adata.get("bones", {})
            matched = [b for b in bones if kk in b.lower()]
            if matched:
                print(f"  [{f.name}] {aname}: bones={matched}")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        prog="ysm_dump",
        description="通用 YSM / OpenYSM 模型与动画检查工具",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例:\n  python tools/ysm_dump.py list res/smx --key tail\n"
               "  python tools/ysm_dump.py dump res/smx \"尾巴物理实现\"\n"
               "  python tools/ysm_dump.py bones res/smx tail\n"
               "  python tools/ysm_dump.py find res/smx Tail",
    )
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_list = sub.add_parser("list", help="列出模型目录下的所有动画")
    p_list.add_argument("model_dir", help="模型目录，如 res/smx")
    p_list.add_argument("--key", help="按动画名关键字过滤（不区分大小写）")

    p_dump = sub.add_parser("dump", help="转储指定动画详情")
    p_dump.add_argument("model_dir", help="模型目录，如 res/smx")
    p_dump.add_argument("anim_name", help="动画名（须与文件内完全一致）")

    p_bones = sub.add_parser("bones", help="在几何中按骨骼名关键字搜索")
    p_bones.add_argument("model_dir", help="模型目录，如 res/smx")
    p_bones.add_argument("key", help="骨骼名关键字，如 tail")

    p_find = sub.add_parser("find", help="找出驱动指定骨骼的所有动画")
    p_find.add_argument("model_dir", help="模型目录，如 res/smx")
    p_find.add_argument("bone_key", help="骨骼名关键字，如 Tail")

    args = parser.parse_args(argv)
    md = Path(args.model_dir)
    if not md.is_dir():
        print(f"模型目录不存在: {md}", file=sys.stderr)
        return 1

    if args.cmd == "list":
        cmd_list(md, args.key)
    elif args.cmd == "dump":
        cmd_dump(md, args.anim_name)
    elif args.cmd == "bones":
        cmd_bones(md, args.key)
    elif args.cmd == "find":
        cmd_find(md, args.bone_key)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
