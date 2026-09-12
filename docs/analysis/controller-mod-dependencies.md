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

## 具名并行槽位（非数字后缀）

wiki 只定义数字槽位，但官方对**非数字后缀**也发控制器：参考实现的 `ParallelProcessor`
用 `allowExtraSlots` 区分，player 与第一人称手臂传 `true`（动画条目匹配
`^<prefix>.<槽位名>_.+`、控制器条目匹配 `^<prefix>_ctrl_<槽位名>_.+`），弹射物/载具传 `false`。
于是模型可以把一整块状态机挂在一个具名槽位上（`player.pre_parallel_<名字>`），
其中的 `on_entry`/`on_exit` 与 Molang 全靠这个控制器执行。

**不能按当前模型动态注册控制器**：`AnimationFactory.getOrCreateAnimationData(uniqueId)` 对每个
animatable 只调用一次 `registerControllers`，而 `CustomPlayerEntity.mainModel` 是可变的（换模型时
渲染器直接 `setMainModel`，不重建实体）。动态注册要么在换模型后失效，要么得去改
`AnimationData`，生命周期很脆（曾试过并回退）。

因此用**固定备用池 + 运行时路由**：

1. `CustomPlayerEntity.registerControllers` 在数字 `pre_parallel_0..7` 之后追加
   `pre_parallel_extra_0..N-1_controller`，在数字 `parallel_0..7` 之后追加
   `parallel_extra_0..N-1_controller`（N = `ControllerUtils.NAMED_PARALLEL_EXTRA_SLOTS`）。
   谓词用 `predicateOpenYsmSlot`（走模型自己的状态机），**不是** `predicateParallel`
   （那是"直接播同名动画"的兜底，具名槽位没有同名动画）。插入位置即优先级：
   `AnimationData` 内部是 `LinkedHashMap`，后执行的覆盖先执行的。
2. `OpenYsmPlayerControllerRuntime.resolveControllers` 先认出池名
   `(player\.)?<族>_extra_<i>_controller`（必须先分流，否则会掉进数字槽位解析），
   再取该族第 i 个具名槽位并 `addMatch`。
3. 槽位表 `OpenYsmAnimationControllerRegistry.namedParallelSlots()` 取自
   `controllers ∪ declaredNames` 并按槽位名排序：**只声明了空 states 的占位槽位也必须占一个
   位置**，否则它后面的槽位会整体前移、错播别人的动画。`player.` 前缀与短名视为同一个槽位。
   结果缓存在 `ControllerSet` 上（池谓词每帧都要问一次；注册时整个 set 被替换，不会过期）。
4. 模型的具名槽位比池子多时，多出来的没有池控制器承载、永远不会播放 —— 此时打一条一次性
   `warnOnce`，不静默截断（提高 `NAMED_PARALLEL_EXTRA_SLOTS` 即可）。

池名的 `pre_parallel_extra_*`/`parallel_extra_*` 前缀会被既有的"这是并行控制器"判断
（`excludeRoot`、漫游变量优化、音效归属等）自然覆盖，与数字槽位同一条路径。

## 已知限制

- 具名槽位只在 player（与第一人称手臂，若将来有专用实体）上有意义；参考实现的
  弹射物/载具是 `allowExtraSlots=false`，YSMU 的池也只注册在 `CustomPlayerEntity` 上。
- `state_pause`/`state_stop` 之间的播放方式差异、以及 `ctrl.reset` 仍未实现（见
  `analysis/molang-custom-functions.md`）。

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
- 具名并行槽位：`DebugController=true` 时，若模型声明的具名槽位超过池子大小，会有一条
  `warnOnce`（`... declares N named <族> slots but only ... extra pool controllers exist`）。
  槽位表本身可用 JUnit 断言（`NamedParallelSlotsTest`），不需要开游戏。
