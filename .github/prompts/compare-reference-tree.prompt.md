---
description: "Compare a YSMU (1.7.10) class with the corresponding reference implementation (1.20.1) to find porting gaps, with the YSM/Bedrock documentation as the authority"
name: "Compare with the reference tree"
argument-hint: "YSMU class path or feature name"
---

# Compare with the reference tree

Analyze the implementation gap between the YSMU (1.7.10) code and the corresponding
`OpenYSM/src` (Forge 1.20.1) reference implementation.

`OpenYSM/src` is **not** YSM's source: YSM has only open-sourced 1.x, so this tree is an
independent reimplementation of 2.6.5 behaviour plus a trimmed branch for porting. Use it to
discover mechanics the documentation never records, never as the correctness oracle. The
authority order is in `AGENTS.md` → *Reference Sources and Authority*: YSM wiki, Bedrock
docs/wiki, the official 2.6.5 client, and only then this tree.

## Steps

1. **Locate the YSMU source** — Read the specified class in `src/main/java/com/fox/ysmu/`. Provide the full source summary including:
   - Package and key method signatures
   - What runtime features it depends on (GeckoLib, Forge, Mixins, compat)
   - Any known issues or TODO comments

2. **Check the documented behaviour first** — Before reading any Java, find what the YSM wiki
   and the Bedrock docs say about the feature, and state the expected behaviour in one or two
   sentences. If a wiki page exists, it outranks both code bases.

3. **Locate the reference equivalent** — Find the matching class in `OpenYSM/src/main/java/com/elfmcys/yesstevemodel/`. Compare:
   - Package structure differences
   - API surface differences (Forge 1.20.1 vs 1.7.10 patterns, Capability vs EEP, etc.)
   - Modern Java features used (`record`, `List.of`, `Files.readString`, `VarHandle` etc.) that need backporting
   - Anything in `OpenYSM/.agent/remove-*.md` that was deliberately deleted from this tree (auth models, optional-mod compatibility, native code) — its absence there is not a YSM feature gap

4. **Identify gaps** — List differences in:
   - Behaviour visible in game (parts shown/hidden, animation playback, 轮盘 settings)
   - Data flow (how data moves from server→client, from model parsing→rendering)
   - Thread-safety assumptions
   - Dependency requirements (optional mods, Java version)

5. **Recommend a migration strategy** — For each gap:
   - Is the desired behaviour confirmed by the wiki, the Bedrock docs, or the official client?
   - Can it be bridged in the existing `RawYsmModelAdapter` path?
   - Does it need new code in the 1.7.10 codebase?
   - Does it need narrow edits to the vendored GeckoLib?
   - Is it a known reference-tree bug worth reporting upstream (see `local/analysis/openysm-issues.md`)?

## Output Format

```markdown
## YSMU: `<class path>`
- Key findings: ...
- Known issues: ...

## Documented behaviour
- Source: <wiki page / Bedrock doc> — expected: ...

## Reference tree: `<class path>`
- Key differences: ...
- Java version blockers: ...

## Gap Analysis
| Gap | Visible impact | Authority | Strategy |
|-----|----------------|-----------|----------|
| ... | ... | wiki/doc/official client/reference only | ... |

## Recommended Action
- Priority: High/Medium/Low
- Verification: how a human confirms it in game (what to look at)
```

## Notes

- **Do not modify** files in `OpenYSM/`.
- **Do not modify** vendored GeckoLib in `src/main/java/software/bernie/` unless the fix is proven identical in both versions.
- Prefer bridging through `RawYsmModelAdapter` over rewriting core paths.
- Prefer the existing `compat/` package for optional-mod dependencies.
- Reference arbitrary third-party models generically in comments and commit messages; concrete names are only for models that ship with the mod or fixtures under `src/test`.
- Anything that can only be judged visually must be verified by the author in a running client, not asserted from the code.
