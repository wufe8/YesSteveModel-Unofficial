#!/usr/bin/env python3
"""
YSMU Log Filter — 从 latest.log 中筛选所有 YSM 相关内容，去重、分组、筛选。

支持 [YSM_DEBUG], [YSM], [YSM Sound], [ysmu] 标签、当前主代码的 [YSMU-XXX] 前缀标签，
以及 YSMU 相关的 WARN/ERROR 日志。未落入具体分类的 [YSMU-XXX] 行会按标签自动分组。

用法:
    python tools/filter_ysm_log.py < lat.log
    python tools/filter_ysm_log.py h:\\minecraft\\...\\lat.log
    python tools/filter_ysm_log.py --raw < lat.log
    python tools/filter_ysm_log.py --model 玲纱 < lat.log
    python tools/filter_ysm_log.py --tag YSMU-CTRL < lat.log
    python tools/filter_ysm_log.py --level WARN < lat.log
    python tools/filter_ysm_log.py --context 2 < lat.log
    python tools/filter_ysm_log.py --hex < lat.log
    python tools/filter_ysm_log.py --all  < lat.log
"""

import sys
import re
from collections import defaultdict
from pathlib import Path
from typing import Optional

# ============================================================
# CLI flags (set by main)
# ============================================================
SHOW_RAW = False
MODEL_FILTER: Optional[str] = None
TAG_FILTER: Optional[str] = None
LEVEL_FILTER: Optional[str] = None
CONTEXT_LINES = 0
SHOW_HEX = False

# ============================================================
# Patterns
# ============================================================
PREFIX_RE = re.compile(r'^\[\d+:\d+:\d+\]\s*\[[^\]]*\]\s*:\s*')

YSM_LINE_RE = re.compile(
    r'\[(YSM_DEBUG|YSMU-DBG|YSM\s*(Sound)?|ysmu|YSM)\]'
    r'|OpenYSM\s'
    r'|YSM\s(footer|JSON|binary|folder|model)'
    r'|YSGP\sformat'
    r'|not a recognized'
    r'|Failed to (load|scan|create|write|initialize).*[Yy][Ss][Mm]'
    r'|ysmu_.*\.ysm'
    r'|YsmCrypt|YSMByteBuf'
    r'|com\.fox\.ysmu'
    r'|rip\.ysm'
    r'|software\.bernie\.geckolib3.*[Ee]rr'
    r'|geckolib3.*Exception'
    r'|Molang.*(?:couldn\'t be found|missing|error|fail)'
    r'|query\.\w+.*couldn\'t be found'
    r'|fn\.\w+.*couldn\'t be found'
    r'|Function.*couldn\'t be found'
    r'|Index.*out of bounds.*animation'
    r'|Controller.*(?:states|initial|transition)'
    r'|tryApplyController'
    r'|predicate\w+'
    r'|collectActiveAnimations'
    r'|prepareFrameVariables'
    r'|applyTransition'
    r'|Registered pack'
    r'|not bridgeable'
    r'|isBridgeable'
    r'|predicateParallel'
    r'|predicateMain'
    r'|legacyBodyActive'
    r'|sneak|sneaking'
    r'|nonEmpty|limbSwing|isMoving'
    r'|force transition'
    r'|playing\s'
    r'|OpenYSM.*model sync'
    r'|OpenYSM.*sync (index|complete|completed|cache)'
    r'|model sync.*'
    r'|Failed to load texture: ysmu'
    r'|\[CHAT\]'
    r'|\[YSMU-[A-Za-z0-9-]+\]'
    r'|texture texture: format'
)


# ============================================================
# Helpers
# ============================================================
def read_log(path=None):
    if path:
        return Path(path).read_text(encoding="utf-8", errors="replace")
    return sys.stdin.read()


def strip_prefix(line: str) -> str:
    """Remove timestamp like [12:34:56] and [Client thread/INFO]: prefix."""
    return PREFIX_RE.sub('', line).strip() if SHOW_RAW else line

def extract_level(line: str) -> str:
    """Extract log level from prefix like [Client thread/INFO] → INFO."""
    # [Thread/LEVEL] or [LEVEL] format
    m = re.search(r'\[[^\]]*?/([A-Z]+)\]', line)
    if m:
        return m.group(1)
    m = re.search(r'\[([A-Z]+)\]', line)
    return m.group(1) if m else 'UNKNOWN'


def decode_model_name(text: str) -> str:
    """Decode _name_<hex> encoded model names for readability."""
    def repl(m):
        try:
            raw = bytes.fromhex(m.group(1))
            return raw.decode('utf-8')
        except Exception:
            return m.group(0)
    return re.sub(r'_name_([0-9a-fA-F]+)', repl, text)


# ============================================================
# Classification
# ============================================================
def classify(line: str) -> str:
    """Classify a YSM log line into a group."""
    lc = line.lower()

    # Binary model issues
    if 'Failed to load OpenYSM binary model' in line or 'Unsupported OpenYSM binary' in line:
        return 'bin_err'
    if 'not a recognized YSGP format' in line:
        return 'bin_skip'
    if 'not bridgeable' in line:
        return 'bin_err'
    if 'isBridgeable' in line:
        return 'bin_err'
    if 'Invalid YSM file' in line or 'Corrupted YSM' in line:
        return 'bin_corrupt'
    if 'YsmCrypt' in line or 'decrypt' in line:
        return 'crypt'

    # Molang errors
    if "couldn't be found" in line:
        return 'molang_miss'

    # IndexOutOfBounds
    if 'Index' in line and ('out of bounds' in line or 'Index -1' in line):
        return 'idx_err'

    # Controller definitions (once per load)
    if 'Controller' in line and ('initial=' in line or 'states={' in line):
        return 'def'

    # State transitions
    if 'applyTransition' in line:
        return 'trans'
    if 'force transition' in line:
        return 'force'

    # tryApplyController
    if 'tryApplyController' in line:
        if 'EMPTY' in line or 'NO existing' in line or 'NOT FOUND' in line:
            return 'warn'
        if 'playing' in line:
            return 'play'
        return 'ctrl'

    if 'tryApply ' in line:
        return 'apply'
    if 'predicateMain' in line:
        return 'pred'
    if 'prepareFrameVariables' in line:
        return 'vars'

    # Predicates
    if re.search(r'predicate\w+', line):
        return 'pred'
    if 'collectActiveAnimations' in line:
        return 'coll'
    if 'predicateParallel' in line:
        return 'parallel'

    # OpenYSM sync
    if 'model sync' in line or 'sync index' in line or 'sync complete' in line or 'sync completed' in line or 'sync cache' in line or 'cache hit' in line or 'cache miss' in line or 'downloaded and cached' in line or 'server sent' in line:
        return 'sync'
    # Texture load failure
    if 'Failed to load texture' in line:
        return 'tex_err'

    # Sound
    if '[YSM Sound]' in line:
        return 'sound'
    # Pack registration
    if 'Registered pack' in line:
        return 'pack'

    # Generic YSMU stacktrace
    if 'com.fox.ysmu' in line or 'rip.ysm' in line:
        return 'stack'

    # General debug/info
    if '[YSMU-DBG]' in line or '[YSM_DEBUG]' in line:
        return 'debug'
    if '[YSM]' in line or '[ysmu]' in line:
        return 'info'
    if '[CHAT]' in line:
        return 'chat'

    # 通用 [YSMU-XXX] 标签分组（未被上述具体规则命中的按标签归组）
    m = re.search(r'\[(YSMU-[A-Za-z0-9-]+)\]', line)
    if m:
        return 'tag:' + m.group(1).upper()

    return 'other'


GROUP_ORDER = [
    'bin_err', 'bin_skip', 'bin_corrupt', 'crypt',
    'molang_miss', 'idx_err', 'tex_err',
    'def', 'trans', 'force', 'warn', 'play', 'ctrl',
    'apply', 'vars', 'coll', 'parallel', 'pred',
    'sync', 'sound', 'pack',
    'debug', 'info', 'stack', 'chat', 'other'
]

GROUP_NAMES = {
    'bin_err':     '=== 1. Binary Model Load Errors ===',
    'bin_skip':    '=== 2. Binary Model Skipped (not YSGP) ===',
    'bin_corrupt': '=== 3. Binary Model Corrupt/Invalid ===',
    'crypt':       '=== 4. Encryption/Decryption Issues ===',
    'molang_miss': '=== 5. Missing Molang Functions ===',
    'idx_err':     '=== 6. Index Out of Bounds ===',
    'tex_err':     '=== 7. Texture Load Failures ===',
    'def':         '=== 8. Controller Definitions (once per load) ===',
    'trans':       '=== 9. State Transitions (condition evaluation) ===',
    'force':       '=== 10. Force Transitions ===',
    'warn':        '=== 11. Warnings (empty/missing animations) ===',
    'play':        '=== 12. Controller Play State ===',
    'ctrl':        '=== 13. Controller State (tryApplyController) ===',
    'apply':       '=== 14. OpenYSM Apply Results ===',
    'vars':        '=== 15. Frame Variables ===',
    'coll':        '=== 16. Animation Collection Details ===',
    'parallel':    '=== 17. Predicate Parallel ===',
    'pred':        '=== 18. Predicate Events ===',
    'sync':        '=== 19. OpenYSM Model Sync ===',
    'sound':       '=== 20. Sound System ===',
    'pack':        '=== 21. Pack Registration ===',
    'debug':       '=== 22. General Debug ===',
    'info':        '=== 23. General Info ===',
    'stack':       '=== 24. YSMU Stack Traces ===',
    'chat':        '=== 25. Chat ===',
    'other':       '=== 26. Other ===',
}


# ============================================================
# Main filter
# ============================================================
def filter_log(text: str):
    lines = text.splitlines()
    raw_lines = []  # (line_no, stripped, original)

    for idx, line in enumerate(lines):
        stripped = strip_prefix(line)
        level = extract_level(line)

        # Apply level filter
        if LEVEL_FILTER and LEVEL_FILTER.upper() != level:
            continue

        # Check if this is a YSM-related line
        is_ysm = bool(YSM_LINE_RE.search(stripped))
        # Also catch non-prefixed YSM stacktrace lines
        if not is_ysm:
            is_ysm = ('com.fox.ysmu' in line or 'rip.ysm' in line)
        if not is_ysm:
            continue

        # Apply model filter
        if MODEL_FILTER and MODEL_FILTER.lower() not in stripped.lower():
            continue

        # Apply [YSMU-XXX] tag filter
        if TAG_FILTER and TAG_FILTER.lower() not in line.lower():
            continue

        raw_lines.append((idx, stripped, line))

    if not raw_lines:
        print("No YSM-related log lines found.")
        return

    # ========================================
    # RAW mode
    # ========================================
    if SHOW_RAW:
        print('=' * 60)
        print(f"Raw YSM Log ({len(raw_lines)} lines):")
        print('=' * 60)
        last_printed = -1
        for line_no, stripped, original in raw_lines:
            level = extract_level(original)
            # Context lines before
            if CONTEXT_LINES > 0:
                start = max(0, line_no - CONTEXT_LINES)
                for ctx_no in range(start, line_no):
                    if ctx_no > last_printed and ctx_no < len(lines):
                        ctx_line = strip_prefix(lines[ctx_no])
                        if ctx_line:
                            print(f"  ctx> {ctx_line}")
                            last_printed = ctx_no
            # The YSM line itself
            decoded = decode_model_name(stripped)
            print(f"  [{level}] {decoded}")
            last_printed = line_no
            # Context lines after
            if CONTEXT_LINES > 0:
                end = min(len(lines), line_no + CONTEXT_LINES + 1)
                for ctx_no in range(line_no + 1, end):
                    if ctx_no > last_printed:
                        ctx_line = strip_prefix(lines[ctx_no])
                        if ctx_line:
                            print(f"  ctx< {ctx_line}")
                            last_printed = ctx_no
        return

    # ========================================
    # Grouped mode (default)
    # ========================================

    # Deduplicate consecutive lines
    deduped = []
    for item in raw_lines:
        if not deduped or item[1] != deduped[-1][1]:
            deduped.append(item)

    # Classify
    groups = defaultdict(list)
    for line_no, stripped, original in deduped:
        g = classify(stripped)
        groups[g].append((line_no, stripped, original))

    # 动态标签组（如 tag:YSMU-CTRL）排在固定分组之后
    dynamic = sorted(g for g in groups if g not in GROUP_ORDER)

    # Summary
    total = len(deduped)
    print(f"{'=' * 60}")
    print(f"Summary ({total} lines after dedup):")
    for g in GROUP_ORDER + dynamic:
        entries = groups.get(g)
        if entries:
            name = GROUP_NAMES.get(g, g).strip('= ').strip()
            print(f"  {name}: {len(entries)}")
    # Hint about DEBUG-level logs
    has_debug_only = any(g in ('bin_skip', 'bin_corrupt') and groups.get(g) for g in GROUP_ORDER)
    if has_debug_only:
        print(f"  (Hint: use --level DEBUG or --raw to see more detail about skipped models)")
    print(f"{'=' * 60}")

    # Grouped output
    for g in GROUP_ORDER + dynamic:
        entries = groups.get(g)
        if not entries:
            continue
        print(f"\n{GROUP_NAMES.get(g, g)}")
        print('-' * 60)
        last_no = -1
        for line_no, stripped, original in entries:
            # Gap indicator
            if CONTEXT_LINES > 0 and last_no >= 0 and line_no > last_no + 1:
                gap = line_no - last_no - 1
                if gap <= CONTEXT_LINES * 2:
                    for ctx_no in range(last_no + 1, line_no):
                        if ctx_no < len(lines):
                            ctx_line = strip_prefix(lines[ctx_no])
                            if ctx_line:
                                print(f"  ctx> {ctx_line}")
                else:
                    print(f"  ... ({gap} lines) ...")
            level = extract_level(original)
            decoded = decode_model_name(stripped)
            print(f"  [{level}] {decoded}")
            last_no = line_no


# ============================================================
# Entry point
# ============================================================
if __name__ == '__main__':
    import argparse

    parser = argparse.ArgumentParser(
        description='Filter YSM-related log lines from Minecraft latest.log',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s < latest.log
  %(prog)s --raw < latest.log
  %(prog)s --model steve < latest.log
  %(prog)s --level WARN < latest.log
  %(prog)s --context 2 --raw < latest.log
  %(prog)s --hex --raw < latest.log
  %(prog)s H:\\minecraft\\...\\latest.log
""")
    parser.add_argument('logfile', nargs='?',
                        help='Path to latest.log (reads stdin if omitted)')
    parser.add_argument('--raw', action='store_true',
                        help='Show all YSM lines in chronological order')
    parser.add_argument('--model',
                        help='Filter by model name (substring match, case-insensitive)')
    parser.add_argument('--tag',
                        help='Only keep lines containing the given [YSMU-XXX] tag (substring match)')
    parser.add_argument('--level', choices=['DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'],
                        help='Filter by log level')
    parser.add_argument('--context', type=int, default=0,
                        help='Show N lines of surrounding context (use with --raw)')
    parser.add_argument('--hex', action='store_true',
                        help='Show hex dump hint for binary-related errors')
    parser.add_argument('--time', action='store_true',
                        help='Show time')

    args = parser.parse_args()

    SHOW_RAW = args.raw
    MODEL_FILTER = args.model
    TAG_FILTER = args.tag
    LEVEL_FILTER = args.level
    CONTEXT_LINES = args.context
    SHOW_HEX = args.hex
    SHOW_TIME = args.time

    raw_text = read_log(args.logfile)
    filter_log(raw_text)
