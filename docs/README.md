# docs/

面向后续维护者的**工程笔记**：记录"为什么这么写"、非显然的不变量、踩过的坑，以及
验收方法。行为规范本身不在这里 —— 那以 `AGENTS.md` 的 *Reference Sources and Authority*
为准（YSM wiki → 基岩规范 → 官方客户端实机 → 参考实现）。

## 收录原则

- 只写**跨模型、可复用**的结论：机制、决策、验证方式、已知限制。
- **不写具体第三方模型的名字或路径**。需要举例时描述问题的形状（"某模型把可见性写在
  `pre_parallel6` 的 `scale` 上"），或者用随模组发布的模型 / `src/test` fixture。
- 与时间点强相关的排查记录留在 `local/`（gitignored）：`local/analysis/` 是原始笔记，
  `local/plans/` 是过程文档。这里只放提炼后的、不会随某次调试失效的内容。

## 目录

| 文档 | 内容 |
| --- | --- |
| [`analysis/particle-system.md`](analysis/particle-system.md) | 粒子系统架构：`particle()`/`abs_particle()` 参数语义、行为表、高版本资产回退、已知限制 |
| [`analysis/animation-stride-matching.md`](analysis/animation-stride-matching.md) | 防滑步：动画播放倍速的算法、为什么不改 `query.ground_speed`、验证方法、配置项 |
| [`analysis/controller-mod-dependencies.md`](analysis/controller-mod-dependencies.md) | 控制器与动画的模组依赖标记、并行控制器的槽位与隐式控制器机制、排查方法 |
| [`analysis/animation-variant-switch.md`](analysis/animation-variant-switch.md) | 同一状态内换动画（条件变体）时的位置保留语义：为什么 t=0 音效会重播/被砍半，以及两条修法 |
| [`analysis/sound-playback.md`](analysis/sound-playback.md) | 音效查找链路；不要用字面量反射 MC 内部字段（reobf 改名 → 有音效却听不到 + WARN）；SoundSystem 句柄在资源重载后失效的隐患；探针现状 |
| [`analysis/molang-identifier-parsing.md`](analysis/molang-identifier-parsing.md) | 自研 Molang 解析器里"标识符允许数字续接"的必要性，以及一个被静默忽略的连带效应 |
| [`analysis/crash-report-log-spam.md`](analysis/crash-report-log-spam.md) | `Negative index in crash report handler` 刷屏的含义（每帧一份 `CrashReport`，不是崩溃）、定位方法与"每帧入口不许抛异常 / `catch (Throwable)`"的修法原则 |

## 尚未提炼（仍在 `local/analysis/`，需要先剔除模型信息）

- `sneak-implementations.md` —— 潜行动画的四条降级路径与判定树（模型名密度高，需重写）。
- `debug-overlay-design.md` —— 调试覆盖层设计（部分已实现，需补"已实现/未实现"状态）。
- `performance-analysis.md` —— 性能剖析（数值与模型强相关，只适合提炼方法论）。
- `ysmu-negative-index-spam-diagnosis.md` —— 已提炼为 `analysis/crash-report-log-spam.md`；
  原始文档保留完整证据、逐会话统计与 bisect 顺序，再次出现该刷屏时先读它。

