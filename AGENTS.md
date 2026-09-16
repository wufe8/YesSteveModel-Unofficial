# AGENTS.md

## Project Snapshot

YSMU is a Minecraft Forge 1.7.10 mod that ports Yes Steve Model (YSM) player models back to 1.7.10. The mod id is `ysmu`, the root package is `com.fox.ysmu`, and the Forge entry point is `src/main/java/com/fox/ysmu/ysmu.java`.

The build uses the GTNH Gradle convention plugin through `settings.gradle.kts` and `build.gradle.kts`. `gradle.properties` targets Minecraft `1.7.10`, Forge `10.13.4.1614`, MCP stable `12`, enables Mixins, enables Jabel modern Java syntax while still targeting JVM 8, and shades/relocates Jackson. Runtime/development dependencies are declared in `dependencies.gradle`.

YSMU already implements more of YSM than the in-repo `OpenYSM/` reference tree does (animation controllers, Molang, model sync, lazy loading, particles, debug overlay). Do not treat that tree as the specification — see the next section.

## Reference Sources and Authority

The target is the behaviour of YSM itself: whether a model works in game — parts shown or hidden as authored, animations playing, 轮盘 settings taking effect. The target is **not** code-level equivalence with any implementation. Prefer the higher entry when sources disagree:

1. The YSM documentation site, `https://ysm.cfpa.team/wiki/intro/` (English: `/en/wiki/intro/`) — YSM-specific semantics: 轮盘 (`/wiki/roulette/`, `/wiki/animation/extra/`), animation controllers (`/wiki/controller/`), parallel animations (`/wiki/animation/parallel/`), model structure (`/wiki/struct/`, `/wiki/type/`, `/wiki/ysm-uv-standard/`), Molang variables and functions (`/wiki/molang/var/`, `/wiki/molang/common/`, `/wiki/molang/script/`). The per-version changelogs (`/wiki/log/265/` …) say which release changed what.
2. Bedrock documentation and the Bedrock Wiki — the underlying format and Molang semantics: `https://bedrock.dev/docs/stable/Animations` (keyframes, `animation_length` default, loop types), `https://bedrock.dev/docs/stable/Molang`, `https://wiki.bedrock.dev/animation-controllers/animation-controllers-intro`, `https://wiki.bedrock.dev/concepts/molang`, `https://wiki.bedrock.dev/visuals/bedrock-modeling`.
3. The official YSM 2.6.5 client (1.20.1 Forge, 1.20.1/1.21.1 Fabric, NeoForge) as the oracle: when neither wiki answers the question, reproduce the case there and treat the screenshot or log as the spec.

When YSMU deliberately deviates from the reference tree, say so in one comment line: `// YSM-wiki: <page>` or `// Bedrock: <topic>`, plus why.

## Versioning and Release

The build derives its version from **git**: `gradle.properties` generates `com.fox.ysmu.Tags.VERSION` from the tag/describe, `ysmu.java` uses it in `@Mod(...)`, the jar name carries it (`+<hash>-dirty`), and `CommonProxy.preInit` logs `I am ysmu at version …`. Nothing in the source needs editing for a version bump, and `-dirty` only means "uncommitted changes", not "stale jar" — to identify the running build, compare the logged version/hash with `git log`.

The hand-written places that carry time-sensitive text and must be revisited on a release are deliberately few:

- `README.md` line with `**最新版本：…**`.
- The `## 变更历史` table in `README.md` — add one row per tagged release.
- `README.md` `## 已知问题` — drop entries that the release fixed and add new ones.
- The `git checkout <branch>` line in the README build section and the branch name next to the version — both name the currently active development branch.

Version numbers quoted inside code comments (for example "regression since 1.9a1-05") are historical markers, not state to maintain. If a new time-sensitive statement is added anywhere else, list it here so the next release does not miss it.

## Diagnostics, Logs and Local Tools

`logs/latest.log` is only the **most recent game session**; a relaunch overwrites it. Before asking the user to restart, or immediately after anything interesting happens, preserve the log (the launcher already keeps `logs_<date>_<jar>.zip`; the user may also rename `latest.log` to something like `latest_<date>_<topic>.log`) and analyse every preserved log that matters, not just `latest.log`.

Before writing a new analysis script, look for one that already exists: `tools/` (tracked) and `local/tools/` (gitignored working scripts). `tools/README.md` lists what each does and collects the analysis pitfalls learned so far; read it before designing a probe. New one-off or environment-specific scripts belong in `local/tools/`; only generally useful, path-independent tools should be promoted to `tools/` and listed in `tools/README.md`.

Diagnostics convention: the user runs the client, so keep probes at `info` level (the `debug` config level needs launch arguments they will not change). A probe is **temporary**: it exists in the working tree only while one specific question is being chased, and it is deleted once that question is answered (`git show <sha>:<path>` recovers it). The only diagnostics that stay in the source permanently are the ones that are both behind a `Config.DEBUG_*` switch and rate-limited — for example `allowDebugLog(tag)` in the controller runtime, the `[YSMU-SOUND-PROBE]` stop-path lines, and the 20-tick active-source dump.

## ExecPlans

`.agent/PLANS.md` is an **optional** template for writing a long, fully self-contained design or migration document. This project does not follow an ExecPlan-per-task workflow: the day-to-day habit is a short plan or findings note in `local/plans/<topic>.md` (gitignored) that is kept up to date while the work is in progress. Use an ExecPlan when the work genuinely needs one (multi-milestone refactor, hand-off to another environment); do not demand one for ordinary fixes.

## Repository Layout

- `src/main/java/com/fox/ysmu`: project-specific mod code.
- `src/main/java/com/fox/ysmu/client`: client-only rendering, GUI, keybinds, animation predicates, texture/model registration, and upload state.
- `src/main/java/com/fox/ysmu/model`: server-side model discovery, built-in model extraction, cache generation, and folder/`.ysm` model format handling.
- `src/main/java/com/fox/ysmu/network`: Forge `SimpleNetworkWrapper` setup and packet classes.
- `src/main/java/com/fox/ysmu/eep`: 1.7.10 `IExtendedEntityProperties` state for selected model/texture, active animation, and starred models.
- `src/main/java/com/fox/ysmu/event`: GTNHLib event subscribers for common player sync and client rendering events.
- `src/main/java/com/fox/ysmu/compat`: optional-mod compatibility wrappers. Keep Backhand and similar direct calls behind these wrappers.
- `src/main/java/com/fox/ysmu/mixin`: Mixins only. `gradle.properties` restricts Mixins to package `com.fox.ysmu.mixin`.
- `src/main/java/software/bernie`, `src/main/java/com/eliotlash`, and `src/main/java/net/geckominecraft`: vendored/ported GeckoLib, Molang/math, and legacy adapter code. Treat these as third-party compatibility code and keep edits narrow.
- `src/main/resources/assets/ysmu/builtin`: built-in models, extracted into `config/ysmu/builtin` on every start. `default` is a modern `ysm.json` pack; `misc` is a pack manifest (`ysm-pack.json`) whose `1_alex` … `6_wine_fox` sub-directories are still the **legacy flat format** (`main.json` + `arm.json` + `*.png`), so the legacy loader path stays load-bearing.
- `res/wine_fox_fold/`: the **source of the built-in `wine_fox` pack** (22 sub-models + `ysm-pack.json`). It is a first-party model pack, but too large to keep in the repository, so it is copied into `assets/ysmu/builtin/` by hand and the author publishes two jar variants (with and without it). Its sub-models are therefore *not* third-party packs for the naming rules below.
- `src/main/resources/assets/ysmu/custom`: legacy built-in model location, empty (0 tracked files); the code that copied models from it is a commented-out block in `ServerModelManager`. Old installs still have `config/ysmu/custom/{default,default_boy,steve,alex,qingluka,wine_fox}` in the legacy format, and `ClientModelManager.loadDefaultModel()` prefers a legacy `custom/default` over the built-in modern one.
- `src/main/resources/assets/ysmu/lang`: `en_US.lang` and `zh_CN.lang`. Keep new translation keys in sync.
- `src/main/resources/mixins.ysmu.json`: Mixin config; five client Mixins are registered (`MixinItemRenderer`, `MixinEntityArrow`, `client.MixinRenderArrow`, `MixinMinecraft`, `MixinEffectRenderer`).
- `src/main/resources/META-INF/*_at.cfg`: access transformers for Minecraft/GeckoLib internals.
- `tools/`: Python utilities for model conversion, `.ysm` dumping and log analysis; see `tools/README.md`.

## Runtime Flow

Startup begins in `ysmu.java`. `CommonProxy.preInit` loads `Config`, calls `ServerModelManager.reloadPacks()`, and logs the version. `CommonProxy.init` registers network packets through `NetworkHandler.init()`. `ClientProxy.init` additionally registers animation states/Molang variables, the custom player renderer, and key bindings.

`ServerModelManager.reloadPacks()` creates `config/ysmu` with `builtin`, `custom`, `export` and `cache`, clears and re-extracts the built-in packs into `config/ysmu/builtin`, initializes `cache/server/PASSWORD`, and rebuilds encrypted server cache files for both folder models and `.ysm` files. Model scanning covers both `custom` and `builtin`.

Model sync starts with the server sending `RequestSyncModel`. The client replies with cached MD5 names via `SyncModelFiles`. The server sends an encrypted password (`SendModelPassword`), asks the client to load cache hits (`RequestLoadModel`), and sends missing cache files (`SendModelFile`). The client decrypts and registers models through `ClientModelManager.registerAll()`.

Client rendering cancels vanilla `RenderPlayerEvent.Pre` in `ClientEventHandler` and delegates to `CustomPlayerRenderer`. `CustomPlayerRenderer` chooses model/texture state from `ExtendedModelInfo` or NPC overrides, posts `SpecialPlayerRenderEvent`, then renders through the GeckoLib replacement renderer. First-person hand rendering is split between `RenderHandEvent` and the Angelica-specific `MixinItemRenderer` path.

## Model and Resource Rules

Folder models live under `config/ysmu/custom/<model name>` (or a built-in pack under `config/ysmu/builtin`) and must include `main.json`, `arm.json`, and at least one `.png`. A `ysm.json` describes a modern pack: geometry under `models/`, animations under `animations/`, controllers under `controller/`, Molang functions under `functions/`. Optional animation files are `main.animation.json`, `arm.animation.json`, and `extra.animation.json`; missing animation files fall back to the built-in default animations.

`.ysm` files in `config/ysmu/custom` are also scanned. Only files containing `main.json`, `arm.json`, and at least one `.png` are cached.

`ModelIdUtil` normalizes model names for `ResourceLocation`. Safe ids match `[a-z0-9._-]+`; unsafe names are encoded as `_name_` plus UTF-8 hex. Use `ModelIdUtil` helpers instead of hand-building model, main, arm, or texture ids.

Built-in model assets in `src/main/resources/assets/ysmu/builtin` are extracted into `config/ysmu/builtin` on reload, and the runtime extraction intentionally overwrites what is there — never treat `config/ysmu/builtin` as a place to keep edits.

Runtime models come from two different sources when debugging: the repository copies under `res/` (reference material, gitignored) and the game's `config/ysmu/{custom,builtin}` plus its client cache. Editing `res/` does not change what the running client renders.

## Network and Threading Rules

Packet ids in `NetworkHandler` are part of the wire protocol. Add new ids when needed; do not renumber existing ids. Keep packet side registration explicit and consistent with the handler behavior.

Do not perform heavy file IO, encryption/decryption, or model parsing directly on a packet handler path. Existing code uses `ThreadTools.THREAD_POOL` for background work and `Minecraft.func_152344_a(...)` when client-side model or texture registration must return to the client thread.

The model password must be available before cached model files can decrypt. Preserve the `SendModelPassword` before `RequestLoadModel` relationship and the retry behavior in `RequestLoadModel`.

## Compatibility Notes

Runtime prerequisites from the README are UniMixins and GTNHLib. Development/runtime extras include NotEnoughItems, Nashorn, Angelica, Backhand, Jackson, and JUnit as declared in `dependencies.gradle`.

Use `@EventBusSubscriber` from GTNHLib for event subscribers following the existing pattern. Client-only subscribers should specify `side = Side.CLIENT`.

Use `BackhandCompat` and `AngelicaCompat` rather than scattering optional-mod API calls through core logic. Keep compatibility checks resilient when the optional mod is absent.

Optional-mod integration must be **capability-probed, never version-gated**: decide by whether the class/method is present (`ModAvailability.isClassPresent`, `Loader.isModLoaded`), then try the call and fall back/skip on failure. Version numbers are not a reliable test — different forks of the same mod (three different `Baubles` all report `Baubles`) have incomparable versions, and a version check rejects working setups. If a capability is missing or a call throws, degrade to false/0 or another mechanism, log once, and revisit only when a real failure is reported.

The one-shot `[YSMU-COMPAT]` WARN for "the optional mod is not installed / this slot has no 1.7.10 analogue" is **intended output, not noise to silence**: the model really did use a query the target environment cannot answer, and that is an in-scope expected error. Keep it WARN and unconditional (per-key deduplicated) rather than moving it behind `Config.DEBUG_*` or demoting it to INFO; a review that flags these lines as defects is re-opening a settled decision. Only rate-limit or consolidate them if a real log-spam report arrives.

1.7.10 has no offhand slot, no data-driven item tags and no modern capability lifecycle; map 1.20.1 checks onto vanilla/GTNH behaviour or an existing compat wrapper instead of copying them.

## Coding Conventions

Follow `.editorconfig`: UTF-8, LF line endings, 4-space Java indentation, 2-space Markdown/JSON/YAML indentation, final newline, and no trailing whitespace except in `.lang` files.

On Windows PowerShell, read UTF-8 files with `-Encoding UTF8`; otherwise Chinese README, comments, and lang files may display as mojibake.

Modern Java syntax is enabled by Jabel, and the code already uses pattern variables. The produced mod still targets JVM 8, so avoid Java 9+ library APIs unless the project already provides or shades them.

Preserve existing public names and legacy casing, including the lowercase `ysmu` mod class. Avoid broad rewrites in vendored GeckoLib/Molang code unless the task specifically requires it.

When adding user-facing text, update both `en_US.lang` and `zh_CN.lang`. When adding config fields, update `Config`, the relevant GUI screen if applicable, and translation keys.

Chinese **displayable text** in `zh_CN.lang` (`gui.*`, `commands.*`, `message.*`, `molang.*` — the GUI and the chat use the same `FontRenderer`) uses **ASCII punctuation plus one space** (`名称: 值(说明)`), never fullwidth `，。、；：！？（）…` or `【】“”`. On some clients the font stops rendering the rest of the line at a fullwidth `（` (the tail becomes invisible) while centring still measures the whole string, so a centred label appears shifted left and drifts when the selected option changes, and a left-aligned one silently loses its explanatory tail. Keep the explanatory text — just write the punctuation in ASCII. Decorative glyphs that the default font does ship (e.g. `▌`) are fine; replace other icon glyphs (e.g. `⏸`) with an ASCII form.

When adding model animation states, register names and priorities through `AnimationRegister`/`AnimationManager`, and ensure `ConditionManager.addTest` can classify conditional animation names.

Reference third-party models generically in code comments and commit messages. A concrete model name is fine when it ships with the mod (the built-in `wine_fox` pack and its sub-models, `default`, `misc`, or a fixture under `src/test`); otherwise describe the shape of the problem ("a model that declares only `player.parallel_0..7` and drives visibility from `pre_parallel6`") instead of naming a downloaded pack. Note the models under `res/` (other than `wine_fox_fold`) are downloaded packs that once exposed a bug — still not nameable.

Terminology: "YSM" names the product and the behaviour being targeted; "OpenYSM" stays for the reference implementation, its in-repo tree, and the model/sync format it defined — `OpenYsmFormat` sits next to the legacy `FolderFormat`, and the `OPENYSM_*` constants name the controller slots of that format.

## Gradle and Verification

The toolchain works: Java 25 compiles to a JVM 8 target via Jabel, and `gradlew build` / `gradlew test` can be run from the repository root. Agents may run build and test commands, but the **normal workflow is that the author compiles and tests**: this mod has no front-end closed loop, so model assembly, rendering and 轮盘 behaviour can only be accepted in a running client. For anything visual, ask the user to run and report, and give them the exact command plus what to look for.

Useful commands from the repository root:

- `.\gradlew.bat build`
- `.\gradlew.bat test`
- `.\gradlew.bat runClient`
- `.\gradlew.bat runServer`

`src/test/java` holds JUnit 5 sources (`YsmResourceFormatTest`, `OpenYsmSyncProtocolTest`, `YsmFoundationTest`, `MolangParserOpenYsmPhysicsTest`, `NestedAssignmentRegressionTest`, `ParticleMolangExpressionTest`, `ImplicitParallelControllerTest`, `MolangFunctionParserTest`, `MolangRealScriptTest`, `OpenYsmConditionEvaluationTest`, `AnimationManagerMolangConditionTest`, `NamedParallelSlotsTest`, `QueryItemNameAnyFunctionTest`, `QueryBlockTagFunctionTest`, `AnimationManagerMolangHintsTest`, `BuiltinAssetsTest`, `MolangScriptRegistryTest`, `MolangScriptCallAndLoopTest`, `AnimationControlScriptTest`, `MolangQueryAbbreviationTest`, `MolangDebugOutputTest`, `QueryDurabilityFunctionTest`, `YsmEffectLevelFunctionTest`, `MolangSyncSenderTest`, `MolangSyncPacketTest`, `RemotePlayerAnimationQueriesTest`, `ProjectileGroundTrackerTest`, `CtrlItemMatcherTest`, `AnimationManagerControlScriptDecisionTest`, `ItemTagMatcherTest`, `CameraRollQueryTest`, `ModAvailabilityTest`, `BaublesCompatTest`, `BackhandCompatTest`, `NamedParallelRoutingTest`, `TimelineEventSchedulerTest`, `TimelineContributorBuilderTest`, `TimelineRoamingRefreshClassifierTest`, `AnimationControllerTimelineClockTest`, `MolangVariableScopeTest`); extend them when touching parsers, the sync protocol or Molang. Keep the whole suite in the low seconds: a pathological short-period scheduling case once livelocked one test for ~235s before the cap actually bounded it, so a sudden multi-minute `gradlew test` is a defect signal, not just a slow machine — read the per-class times in `build/test-results/test/*.xml` (`time="…"`) instead of guessing. The `.molang` fixtures under `src/test/resources/molang/` are copied from the built-in `wine_fox` pack (which may be named) and normalised to LF — keep `res/` out of tests, it is gitignored. CI is effectively dormant: `.github/workflows/build-and-test.yml` triggers on `master, main`, while this repository's default branch is `1.0` and development happens on `perf/previewUI`, so pushes do not run it. `.github/workflows/release-tags.yml` triggers on any tag and does run. Do not spend effort designing automated gameplay tests — rendering, model assembly and 轮盘 behaviour can only be judged in a running client (and would need a vision model to automate).
