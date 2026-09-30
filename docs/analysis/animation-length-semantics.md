# `animation_length` 的缺省语义与消费点

来源：作者本地针对上游格式问题的一次排查笔记（未随仓库提供）。此文只取其中
**格式语义**这一条可复用结论，并补上 YSMU 侧已经实现、但未被任何 `docs/` 文档记录的不变量；
上游逐条 bug 清单不在此复制。

## 规范

基岩版 Animations 文档：`animation_length` 可省略，缺省值是**最后一个关键帧的时间**，不是 0、
也不是"无限长"。

    "animation_length" : <float>  // default = time of last key frame.

这个值决定三件事：循环动画在哪里回绕、PLAY_ONCE 何时算播完、`q.all_animations_finished` 何时
成立。任何一条路径把它错当成 0 或 +∞，都会得到"循环不回绕""状态机卡在末帧"或"一进状态就
判已播完"。

## YSMU 的实现分层

1. **反序列化层不猜缺省值。** `YSMFolderDeserializer.parseAnimations` 读
   `animation_length` 时缺省填 0；`RawYsmModelAdapter.createAnimationJson` 只在
   `length > 0` 时才写回 `animation_length` 属性。于是"字段缺失"被保留成"属性缺失"，交给
   下一层按规范补算，而不是在反序列化时就钉死一个错误常量。
2. **JSON 层按最后关键帧补算。** `JsonAnimationUtils.deserializeJsonToAnimation`：有
   `animation_length` 就 `convertSecondsToTicks(秒)`；没有就 `calculateLength(boneAnimations)`。
   `calculateLength` 累加每条骨骼通道的 `getLastKeyframeTime()`（该方法把该通道所有关键帧的
   段长求和，单位已是 tick），取三通道最大值。
3. **哨兵值表示"没有任何定时内容"。** 若所有通道都没有时间键，`calculateLength` 返回
   `Double.MAX_VALUE`（`JsonAnimationUtils:479`），语义是"没有可用的周期"，不是"极长的动画"。

## 三个消费点（都按上面语义处理）

- **循环回绕**：`AnimationController.processCurrentAnimation` 只在 `tick >= animationLength`
  时进入回绕/结束分支，`wrapLoopTick` 对 `animationLength <= 0` 原样返回。哨兵值不会误触发
  回绕，但因为本来就没有时间键，画面本来就该保持不变。
- **防滑步周期**：`OpenYsmPlayerControllerRuntime.playbackLengthTicks` 显式把
  `null` / `<= 0` / `Double.MAX_VALUE` 当作"没有真实周期"，退回逐通道重算，避免用哨兵值当
  周期去算动画倍速。
- **动画完成查询**：`OpenYsmControllerExpressionEvaluator.allAnimationsFinished` 要求
  `animationLength != null && > 0` 才做
  `event.getAnimationTick() - state.enteredTick >= animationLength`；否则结合"是否本帧刚进入"
  返回 true/false。`anim_time_update` 驱动的动画另有分支，不在此判定。

## `loop` 的编码与同步往返

`loop` 在文件夹 JSON 里是 `true` / `false` / `"hold_on_last_frame"` / 缺省；进入 `RawYsmModel`
后是一个整数 `loopMode`，并且**会跟着服务端缓存/同步的二进制一路走到客户端**，再由
`RawYsmModelAdapter.putLoopMode` 写回动画 JSON。两端的编码表必须一致，且与上游 OpenYSM 一致：

| 文件夹 JSON | `loopMode` | 客户端写回 |
| --- | --- | --- |
| `true` / `"true"` / `"loop"` | 1 | `"loop": true` |
| `"hold_on_last_frame"` | 3 | `"loop": "hold_on_last_frame"` |
| `false` | 0 | `"loop": false` |
| 缺省 | 2 | 不写字段（GeckoLib 缺省 = `PLAY_ONCE`） |

回归点：`YSMFolderDeserializer.parseLoopMode` 曾把 `"hold_on_last_frame"` 编成 **2**，而
`putLoopMode` 只认 0/1/3 —— 2 落进"不写字段"分支，客户端动画退回 `PLAY_ONCE`。症状是
**控制器状态里"播完停在最后一帧"变成"播完回 idle"**；条件动画名路径（`use_mainhand:sword`
等）因为 `AnimationManager` 显式传 `HOLD_ON_LAST_FRAME`，把这个 bug 完全掩盖了。同一处编码
错误也会写进导出的 `.ysm`。

不变式：`loopMode` 的编码只在 `parseLoopMode` / `putLoopMode` 这一对函数里定义；改就两端
一起改，并由 `YsmResourceFormatTest#holdOnLastFrameLoopSurvivesFolderSyncRoundTrip` 锁住。
不要用"缺省时补一个默认值"的方式绕过：缺省的语义是 `PLAY_ONCE`，不是 `HOLD_ON_LAST_FRAME`。

## 教训

同一格式语义在多个代码路径里各写一份常量就会漂移：同一批资料里能看到 0、-1、+∞ 三种缺省
表示。正确做法是**只在一层把规范的缺省值补出来**（这里放在 JSON 层），其余层只处理补好后的
值，并用明确的哨兵（`Double.MAX_VALUE`）表达"确实没有时序内容"。

## 已知限制 / 未验证

- 哨兵 `Double.MAX_VALUE` 会让以 `q.all_animations_finished` 为唯一出口的状态在"当前动画
  完全没有任何时间键"时永远无法离开该状态（`tick - entered >= MAX_VALUE` 恒 false）。
  这是理论推导，未在实机构造模型验证。
- `anim_time_update` 动画、`HOLD_ON_LAST_FRAME`、`PLAY_ONCE` 的播放边界未在本文逐一展开；
  `loop` 的编码与同步往返见上一节。
- 上游 OpenYSM 的文件夹路径缺省取 +∞ 这一具体 bug 只是本文的对照来源；YSMU 的修复状态以上
  面源码为准，未与官方 2.6.5 客户端逐例对照。
