---
description: "Compare a YSMU (1.7.10) class with the corresponding OpenYSM-Clone (1.20.1) implementation to identify porting gaps and migration strategies"
name: "Compare with OpenYSM-Clone"
argument-hint: "YSMU class path or feature name"
---

# Compare with OpenYSM-Clone

Analyze the implementation gap between the YSMU (1.7.10) code and the corresponding OpenYSM-Clone (1.20.1) reference.

## Steps

1. **Locate the YSMU source** — Read the specified class in `src/main/java/com/fox/ysmu/`. Provide the full source summary including:
   - Package and key method signatures
   - What runtime features it depends on (GeckoLib, Forge, Mixins, compat)
   - Any known issues or TODO comments

2. **Locate the OpenYSM-Clone equivalent** — Find the matching class in `OpenYSM-Clone/src/main/java/com/elfmcys/yesstevemodel/`. Compare:
   - Package structure differences
   - API surface differences (Forge 1.20.1 vs 1.7.10 patterns, Capability vs EEP, etc.)
   - Modern Java features used (`record`, `List.of`, `Files.readString`, `VarHandle` etc.) that need backporting

3. **Identify gaps** — List differences in:
   - Functionality (features present in OpenYSM but missing in YSMU)
   - Data flow (how data moves from server→client, from model parsing→rendering)
   - Thread-safety assumptions
   - Dependency requirements (optional mods, Java version)

4. **Recommend migration strategy** — For each gap:
   - Can be bridged in the existing `RawYsmModelAdapter` path?
   - Requires new code in the 1.7.10 codebase?
   - Requires narrow edits to the vendored GeckoLib?
   - Should wait for a future phase per `Port.md`?

## Output Format

```markdown
## YSMU: `<class path>`
- Key findings: ...
- Known issues: ...

## OpenYSM-Clone: `<class path>`
- Key differences: ...
- Java version blockers: ...

## Gap Analysis
| Gap | Impact | Migration Strategy |
|-----|--------|-------------------|
| ... | ... | ... |

## Recommended Action
- Priority: High/Medium/Low
- Estimated effort: ...
```

## Notes

- **Do not modify** files in `OpenYSM-Clone/` or `geckolib-Clone/`
- **Do not modify** vendored GeckoLib in `src/main/java/software/bernie/` unless the fix is proven identical in both versions
- Prefer bridging through `RawYsmModelAdapter` over rewriting core paths
- Prefer the existing `compat/` package for optional-mod dependencies
- Add `[YSMU-DBG]` or any looks like [YSMU-xxx] prefixed log statements for debug tracing
