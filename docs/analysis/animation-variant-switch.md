# 动画变体切换（同一状态内换动画）

## 现象

带挥剑音效的模型，在挥剑动画**还没播完**时改变移动状态（静止→移动，或移动→静止），
会**再听一次**挥剑音效。

## 机制

模型可以把一个控制器状态的动画写成"条件变体"，例如某个攻击状态：

    "attack1": { "animations": [ { "attack_idle_1": "ctrl.idle" }, { "attack_1": "!ctrl.idle" } ] }

于是状态的**名字没变**（还是 `attack1`），但选中的动画随 `ctrl.idle` 在
`attack_idle_1` / `attack_1` 之间切换 —— 玩家在挥剑途中起步或停下就会触发。

YSMU 对这种"同状态、换动画"的处理是**保留播放位置**：状态机路径调用
`setAnimationPreservingTick(builder, absoluteTick, elapsedTick)`
（`OpenYsmPlayerControllerRuntime.applyAnimations` 里 `sameState` 分支），而不是从 tick 0 重播。
状态真正跳转（例如 `default → attack1`）才用 `setAnimation` 归零，此时 t=0 音效**应该**响。

保留位置带来两个副作用，两者都必须处理，否则音效要么"响两遍"要么"被砍半"：

1. **新变体的关键帧对象是全新的**。`executedKeyFrames` 里只有旧变体的
   `EventKeyFrame` 实例，挡不住新变体的同 tick 关键帧；两个变体都把挥剑音效写在
   t=0.0，于是切换的瞬间音效再触发一次。
   → 修法：`AnimationController.setAnimationPreservingTick` 调用
   `carrySoundKeyFramesPassed(outgoing, position)`：**只有当被替换掉的动画确实已经播过**
   位置之前的某个声音关键帧（该实例已在 `executedKeyFrames` 里）时，才把新动画里
   位置之前（`startTick < position`）的声音关键帧标记为已执行。
   两个"不能抑制"的反例必须挡住，否则会**完全没声音**：
   - **状态进入后的那一帧**也会走到保留位置的分支 —— 因为 `lastActiveAnimations` 只在
     `sameAnim` 分支里更新，而进入帧走的是 `sameState=false` 分支，所以下一帧
     `animsChanged` 必然是 true。此时被替换的动画（就是同一个对象）**还没轮到处理关键帧**
     （控制器在进入帧的末尾才 dequeue），所以"旧动画已播过"不成立，不能抑制。
   - 旧变体压根没有声音关键帧时，新变体的音效还没响过，应该照常触发。
   位置取控制器自己的时钟 `animationSpeed * (absoluteTick - tickOffset)`（而不是调用方给的
   "状态存活时间"），这样循环回绕后的位置也是对的。
   只处理**声音**关键帧：新变体 t=0 的 timeline（自定义指令）可能仍被它的骨骼需要，
   粒子关键帧也没有复现过重复触发。
2. **控制器音效被停掉**。`tryApplyController` 原先只要"当前主动画名变了"就
   `YSMSoundManager.stopController(...)`，而变体切换恰好会改名 —— 一次性挥剑音效被砍掉。
   → 修法：同一控制器状态内的变体切换（`sameState`）不停音效；状态真正跳转时才停。

`YSMSoundManager` 里的 100 ms 防抖**不能**指望：它比一次挥剑短得多，而且
`stopController` 会清掉该控制器的防抖记录（这正是"重复播放"能听到的原因之一）。
防抖继续作为极端连击的兜底，真正修的是上面两条位置语义。

## 验证

- `DebugSound=true`：`[YSMU-SOUND] onSoundKeyframe: ctrl=… sound=…` 应在一次挥剑里
  只出现一次；变体切换那一帧出现 `debounced` 或不出现都算正常。
- `DebugController=true`：`[YSMU-CTRL-PLAY] <ctrl> state='attack1' playing='…' sameState=true`
  可以看到是"同状态换动画"而不是状态跳转。
- 听感：挥剑途中起步/停下，音效只响一次且**不被截断**（若只做第 1 条而不做第 2 条，
  会从"响两遍"变成"响一半"）。

## 相关代码

- `software/bernie/geckolib3/core/controller/AnimationController.setAnimationPreservingTick`
  —— 保留位置的入口，以及 `resetEventKeyFrames` 为何**不能**在这里调用（会清空已执行集合）。
- `OpenYsmPlayerControllerRuntime.applyAnimations` —— 判断 `sameState` 并选择两条路径。
- `OpenYsmPlayerControllerRuntime.tryApplyController` —— 变体切换时不停控制器音效。
