# 潜行动画的降级路径与判定树

提炼自 `local/analysis/sneak-implementations.md`。原文按模型逐条列出走哪条路径（模型名密度高），
本文改写为机制：潜行由哪几条路径负责、如何在它们之间做判定、以及判定写宽了会坏成什么样。

## 为什么需要判定树

模型对潜行的实现方式不统一：有的把潜行写进自己的身体控制器状态机，有的只有 legacy 的
`sneak` / `sneaking` 动画，有的虽有身体控制器但只处理待机。一律跳过 legacy、或让两条路径
同时播，会分别得到"移动潜行被站立蹲姿覆盖 Root 位移"和"只处理待机的控制器卡在无动画"。

## 路径 A：OpenYSM 控制器（`OpenYsmPlayerControllerRuntime.tryApply`）

- 模型自己声明 `player.pre_main` / `player.main` / `player.base` / `player.move` 等身体控制器，
  用 `ctrl.sneak` / `ctrl.sneaking` 等条件驱动状态机。
- `AnimationManager.predicateMain` 里 `tryApply(event)` 返回非 null 就直接 return，legacy 状态
  循环根本不执行。
- 控制器在潜行时选不出动画（`tryApply` 返回 null）时，仍会落回路径 B。

## 路径 B：legacy 状态机（`AnimationManager.predicateMain` 的状态循环）

- `AnimationRegister` 注册两个状态：
  - `sneak` = 在地面 + `player.isSneaking()` + `|limbSwingAmount| > MIN_SPEED`（移动潜行）；
  - `sneaking` = 在地面 + `player.isSneaking()`（站立潜行）。
- 状态按优先级轮询，命中即播；所有状态都不满足时回退 `idle`。
- `isAnimationNonEmpty` 跳过"loop:true 但没有骨骼"的空桩动画。模型即使声明了 `sneak` /
  `sneaking` 键也可能是空桩，legacy 会继续往下找，最终回退 `idle`。

## 判定：`openYsmHandlesSneak`

`AnimationManager.predicateMain` 调用
`OpenYsmAnimationControllerRegistry.hasControllerSneakHandling(animId, player.pre_main,
player.main, player.base, player.move)`：大小写不敏感地在这些身体控制器的**状态动画名、动画
条件、transition 条件、onEntry、onExit** 里搜 `sneak`。命中才在 legacy 循环里 `continue` 掉
`sneak` / `sneaking` 两个状态；未命中就照常走 legacy。

历史教训：这个开关宽过两次，两次都造成回归。

1. 早期用 `hasAnyController()`：任何 OpenYSM 控制器都跳过 legacy → 破坏没有身体控制器的模型。
2. 改成"存在身体控制器即跳过"：破坏"有 `player.main` 但只处理待机"的模型 —— 潜行时 legacy 被
   跳过、控制器又选不出潜行动画，卡在无动画。
3. 现在按"是否真的引用潜行"判定，两类模型都恢复。

## `ctrl.*` 的潜行语义（`OpenYsmControllerExpressionEvaluator`）

- `ctrl.sneak` = 在地面 && `isSneaking()` && 水平位移 > 0.05；
- `ctrl.sneaking` = 在地面 && `isSneaking()` && 水平位移 ≤ 0.05；
- `ctrl.walk` / `ctrl.run` 显式排除 `isSneaking()`；`ctrl.idle` 同样排除 `isSneaking()`。

两个要点：

- 移动判定必须用**实际水平位移**（`posX - prevPosX` 等 ×20），不能用 `limbSwingAmount`：
  1.7.10 潜行速度低，平滑值在阈值附近震荡，会出现 `sneak` / `sneaking` 反复横跳，表现为
  "移动潜行在放站立蹲姿"。调试命令走的是 `evaluateCtrlState`，它对 swim/sneak/run/walk 是
  与运行时相近但不等价的近似实现（例如 walk 用 motion 而非 limbSwingAmount），看趋势即可。
- `ctrl.idle` 在潜行时恒为 false。只处理待机的控制器因此在潜行时选不出动画 —— 这正是判定树
  必须落回 legacy 的原因。

## Root 骨骼与状态循环

- `applyAnimations` 的 `excludeRoot`：`player.pre_main` 与 `parallel_*` / `pre_parallel_*` 保留
  Root（身体位移与蹲下高度由它们负责）；其余 overlay 控制器（`post_*` 等）剔除 Root，避免
  叠加层覆盖全身位移。
- `applyTransition` 用 `visitedStates` 记录本次跳转链，目标状态已访问过就停在当前状态（不执行
  onExit / onEntry）。没有这条，两个互相满足条件的状态会振荡。

## 验证

- `DebugController=true`：看 `<ctrl> state='…'` 落在哪条路径；潜行切移动/静止时应看到
  `sneak` ↔ `sneaking` 切换而不是高频横跳。
- `/ysm debug overlay` 或 `/ysm debug query ctrl.sneak ctrl.sneaking ctrl.idle`：潜行移动时
  `sneak=1 / sneaking=0 / idle=0`，潜行静止时 `sneak=0 / sneaking=1`。
- 回归至少覆盖三类：控制器驱动潜行、无身体控制器、有身体控制器但只处理待机。用随模组发布的
  模型或 `src/test` fixture 构造，不要依赖某个下载模型。

## 已知限制 / 未验证

- 判定只看四个预登记的控制器名（`pre_main` / `main` / `base` / `move`）。模型若用别的名字
  驱动潜行，会被当作"未处理潜行"而走 legacy。
- 判定是**名字/条件字符串**匹配，不做行为分析：控制器引用了 `sneak` 字样但运行时不进入任何
  含潜行的状态，仍会跳过 legacy（尚无已确认的此类案例，未在实机逐一验证）。
- 空桩 `sneak` / `sneaking` 且无控制器处理的模型，潜行会回退 `idle`（未与官方 YSM 表现对照）。
