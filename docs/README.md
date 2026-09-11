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
| [`analysis/sneak-animation-paths.md`](analysis/sneak-animation-paths.md) | 潜行动画的降级路径与判定树：legacy 与 OpenYSM 控制器谁负责、`hasControllerSneakHandling` 为什么不能写宽、Root 与循环检测 |
| [`analysis/animation-controller-priority.md`](analysis/animation-controller-priority.md) | 控制器注册顺序与骨骼覆盖：后执行的控制器覆盖先执行的、每帧处理链、cap/parallel/main 的优先级 |
| [`analysis/animation-length-semantics.md`](analysis/animation-length-semantics.md) | `animation_length` 缺省语义：缺省=最后关键帧、`Double.MAX_VALUE` 哨兵、循环/防滑步/动画完成三个消费点 |
| [`analysis/geometry-cube-sanitization.md`](analysis/geometry-cube-sanitization.md) | 几何清理：负尺寸 cube 的两趟 `CULL_FRONT` 渲染、零面积 UV 面删除、何时保留/归一化 |
| [`analysis/molang-custom-functions.md`](analysis/molang-custom-functions.md) | `.molang` 自定义函数：YSMU 只实现了动画控制脚本子集（状态→动画、过渡时长、`indicate_reload`），复杂条件/事件订阅/`fn.*` 未实现及补法 |
| [`analysis/performance-profiling.md`](analysis/performance-profiling.md) | 性能剖析方法论：1.7.10 可用工具、单实体渲染成本模型、FBO 预览缓存与 `GUI_MODEL_PREVIEW_REFRESH`、GPU 分析局限 |
| [`analysis/debug-overlay.md`](analysis/debug-overlay.md) | Molang 调试覆盖层：设计 vs 实现逐项状态（快捷键/数据源/布局/键盘独占），以及设计稿里没有的 `@来源` 列与动态 `ctrl.*` |

## 已提炼的原始笔记（仍在 `local/analysis/`，含更完整的证据）

- `ysmu-negative-index-spam-diagnosis.md` —— 已提炼为 `analysis/crash-report-log-spam.md`；
  原始文档保留完整证据、逐会话统计与 bisect 顺序，再次出现该刷屏时先读它。
- `sneak-implementations.md`、`debug-overlay-design.md`、`performance-analysis.md` —— 分别提炼为
  上表的 `sneak-animation-paths.md`、`debug-overlay.md`、`performance-profiling.md`。
- `渲染管线分析_at-9de7944.txt`、`UI实现分析_at-f8e4265.txt` —— 原始 dump；其中的可复用结论已并入
  `animation-controller-priority.md` 与 `performance-profiling.md`。
- 其余 `.txt`（逐模型 dump）没有可复用的结论，按 `docs/README.md` 的收录原则不入库。
