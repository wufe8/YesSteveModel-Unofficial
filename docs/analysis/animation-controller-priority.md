# 动画控制器执行顺序与骨骼覆盖

来源：`local/analysis/渲染管线分析_at-9de7944.txt`（原始逐帧调用链笔记）。该文件里的
**控制器注册顺序决定骨骼最终值**这一机制在当前源码中依然成立，且未被任何已提炼文档覆盖，
故据此写成此文；原文行号对应提交 `9de7944`，之后会漂移，本文改用类/方法名定位。

## 核心不变量：后执行的控制器覆盖先执行的（**旋转例外：`parallel` 族相加**）

`AnimationProcessor.tickAnimation` 按 `AnimationData.getAnimationControllers().values()` 的
注册顺序遍历控制器（该 map 是 `LinkedHashMap`，插入序 = 注册序），控制器里直接对骨骼调
`bone.setRotationX/Y/Z` 与 set position/scale。同一骨骼同一通道被后面的控制器写到就是后者
生效；某个控制器没动画该骨骼，就保留前者的值。**要调某个骨骼的最终姿态，第一步是确认谁
最后写它。**

**例外**：wiki「并行动画」写明 `parallel` 族"**采用了特殊的混合动画**……这个混合仅会混合旋转，
不会混合位移和缩放"——它的 rotation 与低优先级层**相加**而不是覆盖（OpenYSM 的对照实现：
`parallel` 族注册 `deprecatedMode=true`，processor 对它们走 `vector3f.add(value)`，其余走
覆盖）。实现点是 `AnimationProcessor.combineRotation(previous, value, additive)`，开关是
`AnimationController#setAdditiveRotation`：`CustomPlayerEntity` 只给 `parallel_0..7_controller`
与 `parallel_extra_*_controller` 打开，`pre_parallel*` 保持覆盖。

改成相加是为了修一个可见 bug：模型把轮胎自转写在 `pre_parallel2`
（`rotation=[v.wheel_rotate,0,0]`）、把前轮转向写在 `parallel4`（`rotation=[0,转向角,0]`）。
`parallel*` 注册在 `pre_parallel*` 之后，按"后写覆盖"处理时前轮的**整条 rotation 向量**被
`parallel4` 覆盖成 X=0 → **只有后轮转**。相加后前轮 = 自转 + 转向，两条通道各管一个分量。

回归测试：`ParallelRotationBlendTest`（wiki 的 10+25=35 例子 + 注册时只给 `parallel` 族打开）。

## 固定注册顺序（`CustomPlayerEntity.registerControllers`）

由先到后（越后优先级越高）：

1. `pre_parallel_0..7_controller`（对应动画 `pre_parallel0..7`）
2. `player.pre_main`
3. `main_controller`（legacy 主体）
4. `player.post_main`
5. `player.pre_hold`
6. `hold_offhand_controller` / `hold_mainhand_controller`
7. `player.post_hold`
8. `player.pre_swing`
9. `swing_controller`
10. `player.post_swing`
11. `player.pre_use` / `use_controller` / `player.post_use`
12. `parallel_0..7_controller`（对应 `parallel0..7`）
13. `head_controller` / `chest_controller` / `legs_controller` / `feet_controller`（盔甲槽 1..4）
14. `cap_controller`（最后执行，所以顶层覆盖动画能压过前面所有控制器）

`transitionLength` 只在 main/hold/swing/use/cap 等少数槽位非 0（`Config.ANIMATION_TRANSITION_TICKS`）。

## 每帧单控制器的处理链

    controller.process()
      → createInitialQueues()：清空本控制器的 boneAnimationQueues / activeBoneAnimationQueues
      → testAnimationPredicate()：调用 predicateMain / predicateCap / ... 决定 setAnimation
      → [Transitioning] saveSnapshotsForAnimation() + 过渡插值点
      → [Running] processCurrentAnimation()：把该 tick 的关键帧变成 AnimationPoint 入队，
                   并对涉及骨骼调用 markActiveBoneAnimationQueue()
    → 遍历 getActiveBoneAnimationQueues()：poll + MathUtil.lerpValues → 写 IBone
    → 全部控制器处理完后进入重置阶段：DirtyTracker 标记为"未变"的骨骼 lerp 回初始快照

要点：

- 队列每帧清空重建，控制器不会"继承"上一帧别人的值。
- 只有被 `markActiveBoneAnimationQueue` 标记的骨骼才会写回；没有任何控制器触及的骨骼在
  重置阶段回初始值。
- 旋转写的是 `lerp 值 + initialSnapshot.rotationValueX`，position / scale 写绝对值。

## 最终渲染

`IGeoRenderer.render`：`renderEarly`（整体缩放）→ 对每个 topLevelBone 递归
`renderRecursively`（`MATRIX_STACK.translate → moveToPivot → rotate(Z→Y→X) → scale →
moveBackFromPivot`，再渲染 cube、递归子骨骼）→ `renderAfter`。动画阶段写入 `IBone` 的旋转值
最终由 `MatrixStack.rotate(bone)` 按 Z→Y→X 顺序应用到矩阵。

## 对排查的意义

- "某个部件位置不对"先按优先级定位：`cap_controller` 最高，其次 `parallel_*`，再是 main；
  两类 overlay 控制器（`post_*` 等）还会被 `applyAnimations` 剔除 Root。
- "某个骨骼完全不动"要区分：没被任何控制器标记为 active（重置回初始），还是被后面的控制器
  覆盖了。
- 并行/命名控制器的注册限制见 [`controller-mod-dependencies.md`](controller-mod-dependencies.md)。

## 已知限制 / 未验证

- 注册顺序是代码写死的，模型不能改变；模型自带控制器由 OpenYSM 运行时映射到这些槽位。
- 未逐条实机验证"后执行一定覆盖"在过渡（Transitioning）帧的表现；过渡期两段插值可能让
  优先级关系在过渡时间内不成立。
