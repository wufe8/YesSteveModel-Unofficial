#!/usr/bin/env python3
"""扫描 res/ 下每个模型声明的「具名并行槽位」，估算 NamedParallelExtraSlots 的合理默认值。

与运行时 OpenYsmAnimationControllerRegistry.namedParallelSlots() 同一套来源：
  1. controller/*.json 里 animation_controllers 的键名（含只声明空 states 的占位槽位）；
  2. functions/*.molang 里 <描述>@player_ctrl_<槽位>.molang 的槽位名（只有脚本、没有 JSON 的槽位）。

槽位判定用与 Java 相同的正则：^(?:player\\.)?(pre_parallel|parallel)_([^0-9].*)$
（族名区分大小写；槽位后缀按小写去重，与运行时一致）。数字槽位 pre_parallel0..7 不算具名。
"""
import json
import os
import re
import sys
from collections import defaultdict

NAMED = re.compile(r'^(?:player\.)?(pre_parallel|parallel)_([^0-9].*)$')
# 数字槽位：parallel_0 / parallel0 / pre_parallel_7 …（运行时走固定数字槽位，不占具名池）
NUMERIC = re.compile(r'^(?:player\.)?(pre_parallel|parallel)_?([0-9]+)$')
CTRL_SUFFIX = 'player_ctrl_'


def base_name(path):
    name = os.path.basename(path.replace('\\', '/'))
    if name.lower().endswith('.molang'):
        name = name[:-len('.molang')]
    return name


def control_slot(path):
    base = base_name(path)
    at = base.rfind('@')
    if at < 0 or at == len(base) - 1:
        return None
    suffix = base[at + 1:]
    if not suffix.lower().startswith(CTRL_SUFFIX):
        return None
    slot = suffix[len(CTRL_SUFFIX):].strip()
    return slot.lower() if slot else None


def model_root_for(path, kind):
    p = path.replace('\\', '/')
    marker = '/controller/' if kind == 'json' else '/functions/'
    idx = p.find(marker)
    if idx >= 0:
        return p[:idx]
    # controllers/ 变体
    idx = p.find('/controllers/')
    return p[:idx] if idx >= 0 else os.path.dirname(os.path.dirname(p))


def collect_controllers(path):
    try:
        with open(path, 'r', encoding='utf-8', errors='replace') as f:
            data = json.load(f)
    except Exception:
        return []
    names = []
    containers = []
    if isinstance(data, dict):
        containers.append(data)
        ac = data.get('animation_controllers')
        if isinstance(ac, dict):
            containers.append(ac)
    for c in containers:
        if isinstance(c, dict):
            for k, v in c.items():
                if isinstance(v, dict) and ('states' in v or 'initial_state' in v):
                    names.append(k)
    return names


def main(root):
    per_model = defaultdict(lambda: defaultdict(set))  # model -> family -> {slot}
    numeric = defaultdict(lambda: defaultdict(set))     # model -> family -> {index}
    files_seen = 0
    for dirpath, _dirs, files in os.walk(root):
        for fn in files:
            low = fn.lower()
            full = os.path.join(dirpath, fn)
            if low.endswith('.json') and ('/controller/' in full.replace('\\', '/')
                                          or '/controllers/' in full.replace('\\', '/')):
                files_seen += 1
                names = collect_controllers(full)
                mr = model_root_for(full, 'json')
                for n in names:
                    m = NAMED.match(n)
                    if m:
                        per_model[mr][m.group(1)].add(m.group(2).lower())
                        continue
                    d = NUMERIC.match(n)
                    if d:
                        numeric[mr][d.group(1)].add(int(d.group(2)))
            elif low.endswith('.molang'):
                slot = control_slot(fn)
                if slot:
                    mr = model_root_for(full, 'molang')
                    m = NAMED.match(slot)
                    if m:
                        per_model[mr][m.group(1)].add(m.group(2).lower())

    rows = []
    for mr, fams in per_model.items():
        pre = len(fams.get('pre_parallel', ()))
        par = len(fams.get('parallel', ()))
        rows.append((max(pre, par), pre, par, mr))
    rows.sort(reverse=True)

    print(f"res root       : {root}")
    print(f"controller json: {files_seen}")
    print()
    print("== 声明了【具名】并行槽位的模型（非数字后缀，占用 NamedParallelExtraSlots 池）==")
    if rows:
        print(f"{'max':>3} {'pre':>3} {'par':>3}  model")
        for mx, pre, par, mr in rows:
            print(f"{mx:>3} {pre:>3} {par:>3}  {mr}")
    else:
        print("(none)")
    print()
    print("== 声明了【数字】并行槽位的模型（parallel_0..7 / pre_parallel_0..7，走固定数字槽位）==")
    print(f"{'pre':>3} {'par':>3}  model")
    for mr in sorted(numeric, key=lambda m: -len(numeric[m].get('parallel', ()))):
        pre = len(numeric[mr].get('pre_parallel', ()))
        par = len(numeric[mr].get('parallel', ()))
        print(f"{pre:>3} {par:>3}  {mr}")
    print()
    for thr in (1, 4, 8, 12, 16, 24, 32):
        n = sum(1 for mx, _p, _q, _m in rows if mx > thr)
        print(f"具名槽位需要 > {thr:>2} 个/族的模型数: {n}")
    if rows:
        print(f"res/ 中单族具名槽位最大值: {rows[0][0]}")
    else:
        print("res/ 中单族具名槽位最大值: 0")
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else 'res'))
