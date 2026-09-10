# 控制器与模组依赖标记

## 为什么需要

模型的动画关键帧/控制器条件里会出现可选模组的变量（当前注册了 `ctrl.tac_`、`ctrl.parcool_`、
`ctrl.slashblade_`、`ctrl.swem_` 四个模式，见 `ClientProxy` 与 `ModDependencyRegistry`）。
这些模组在 1.7.10 上通常不存在，对应的控制器/状态如果照常求值，会"永远不满足条件但一直播
默认状态"，表现为音效反复触发、部件显示错误。因此加载模型时就给控制器打上模组依赖标记，
运行时若依赖未满足则跳过该控制器。

## 标记必须逐动画做，不能整文件做

`ClientModelManager.parseAnimationsToBundle` 曾经按"文件内任一动画匹配就标记文件内所有动画"，
于是一个只在 `sneak/sneaking` 里用到 `ctrl.tac_` 的大动画文件，会把同文件的
`pre_parallel0..7`、衣服、表情动画全部标成 tacz 依赖。模组没装时，整个
`pre_parallel_0` 控制器在进入状态机前就被跳过 ⇒ **轮盘的衣服/表情开关全部失效、备用表情
骨骼悬浮在原位**，而手臂外套看起来还在（因为另一条路径退回 legacy 播放了合并版动画）。

修复方式：逐动画扫描，只标记自身 JSON 内容真正含模组模式串的动画。判断依赖是否命中要看
`animToModIds`（动画名 → modId 集合），再由 `scanAnimKeyframesForDeps` 把结果挂到
**引用了这些动画的控制器**上。

## 并行控制器的槽位与隐式控制器

`CustomPlayerEntity.registerControllers` 只注册固定槽位：`pre_parallel_0..7_controller` 与
`parallel_0..7_controller`（外加 main/hold/swing/use/cap 等）。这些槽位通过
`OpenYsmPlayerControllerRuntime.resolveControllers` 去找模型里的同名控制器。

模型**不一定声明**这些控制器。参考实现的 `ParallelProcessor` 会在动画表里扫描
`^(pre_parallel|parallel)[0-7]$`，为每个这样的动画补一个隐式控制器
`player.pre_parallel_N` / `player.parallel_N`（单状态直接播该动画）；模型自己声明了同名条目时
以声明为准（`CompositeAnimationController.init` 优先用声明条目的状态机）。

YSMU 现在也在 `OpenYsmAnimationControllerRegistry.synthesizeImplicitParallelControllers` 里做同样
的事，并额外记录 `ControllerSet.declaredNames`：**声明过但没有状态的条目也算占用**，用来
shadow 隐式控制器（否则会把模型故意留空的占位条目变成"直接播原始动画"）。

这条机制是"模型能正常显示"的关键：把某个动画从槽位回退路径上摘掉时（例如
`AnimationManager.predicateParallel` 不再回放原始并行动画），必须确认每个
`pre_parallelN`/`parallelN` 动画都有隐式控制器兜住，否则依赖这些动画做
`v.roaming.*` 可见性判断的模型会整体失效。

## 已知限制

- 只覆盖数字槽位 `pre_parallel_0..7` / `parallel_0..7`。模型若声明**带名字**的并行控制器
  （例如 `player.pre_parallel_表情`），YSMU 没有对应的 GeckoLib 槽位，`resolveControllers`
  永远匹配不到它 ⇒ 该控制器的状态不会被播放。参考实现因为按动画条目建控制器，不存在这个限制。
  若后续遇到依赖命名并行控制器的模型，需要动态注册控制器（注意 GeckoLib 的
  `AnimationData.uniqueID` 必须与渲染使用的那一份一致，否则注册了也不生效——曾试过并回退）。

## 排查方法

`DebugController=true`：

- `[YSMU-DEP] Registered mod dependency: <id> (loaded=…)`：启动时的依赖登记。
- `[YSMU-CTRL] Dep scan: <controller> now depends on [...] (via animation '…')`：谁被哪个动画
  标上了依赖。
- `[YSMU-CTRL-EVAL] <ctrlName>`：控制器进入了 `tryApplyController`。
  **完全不出现**说明它被模组依赖跳过（并行控制器会直接 return）或 `resolveControllers`
  没匹配到。
- `[YSMU-CTRL-ANIM] <ctrl> state='…' animations=[…] mergedBones=N`：状态实际合并播放了哪些动画。
- 刚同步完的前几帧会看到 `missing animation` / `no active animations`，那是懒加载暖机，
  下一帧就绪即正常。
