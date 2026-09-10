# 防滑步（stride matching）

## 目标

让 walk/run/sneak/swim 这类"腿与地面反复接触"的移动动画，其步态周期与玩家真实位移匹配，
消除"脚在地上滑"的观感。

## 原理

YSM 的移动动画是固定周期循环：动画长度固定 ⇒ 每个周期的"步幅"固定。玩家速度与动画设计
速度不一致时就会滑步。解法是按真实水平速度缩放动画播放倍速
（GeckoLib `AnimationController.animationSpeed`）。

四种步态各用自己的**规范步幅**归一化（内部常量，不暴露配置）：

    设计速度 = 规范步幅 S / 动画周期 T
    倍速     = clamp( 基础倍率 × 平滑水平速度 × T / S , 0.2, 3.0 )

- 规范步幅：walk/run 4.317、sneak 1.295、swim 1.727 blocks。来源是 vanilla 速度 ×
  随模组发布的默认模型对应动画周期（均约 1.0 s），所以"步幅 = 速度"。
- 同一步态在同一速度下脚速一致；走得快则动画放快，走慢则回落，静止回到 1.0。
- 玩家速度每帧实时读取，药水/属性变速自动适配。
- `fly` 不参与（没有"腿与地面接触"的变化）。

两个容易踩的点：

1. **速度必须用 `pos` 差值 ×20**（本地与远程一致），不能用 `motionX`：1.7.10 渲染期
   `motionX` ≈ `pos` 差值的 0.546 倍，直接拿它当速度会让 BASE 需要调到 ~4.31 才"刚好"。
2. **`query.ground_speed` 刻意保留 `motionX`**，不要"顺手修成真实速度"：
   YSM wiki 定义它是 YSM 自己的标定（走≈1.7 / 跑≈3.2 / 飞≈20），不是真实速度的线性函数；
   模型表达式（阈值判断）按它标定，改成真实速度会整体放大 ~1.83 倍并破坏这些表达式。
   需要真实速度时用 `ysm.ground_speed2`（= `pos` 差值 ×20）。

## 验证方法

- `DebugController=true` 时每秒输出
  `[YSMU-SPEED] anim='walk' cycle=1.000s design=4.317 groundSpeed=4.317 target=1.000x (BASE=1.0)`：
  走路应看到 groundSpeed≈4.3、target≈1.0；冲刺 run 约 5.6、target≈0.87。
- 数学自检：walk `T=1.0s` 设计速度 4.317、行走 4.317 ⇒ 倍速 1.0；run `T=0.6667s`
  设计速度 6.476、冲刺 5.612 ⇒ 倍速 0.867，周期拉长到 0.769 s，覆盖 5.612×0.769=4.317
  blocks，与 walk 同步幅。
- 录像验证：60 fps 录制直线行走，一个步幅（同侧腿两次落地）= 1.0 s = 60 帧，
  期间位移应≈4.317 格。

## 配置（`config/ysmu.cfg` → `animation`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `AnimationSpeedMatch` | true | 总开关 |
| `AnimationSpeedMatchBase` | 1.0 | 基础倍率（0.25–4.0），整体偏快/偏慢时微调 |
| `AnimationSpeedMatchResponse` | 0.5 | 平滑响应（0.05–1.0），越小越平滑 |

## 实现位置

- `client/animation/MovementSpeedMatcher.java`
  - `SpeedProvider.designSpeedFor(name)`：≤0 表示非移动类动画；
  - `DEFAULT_PROVIDER`：内置名称表（walk/walking/walk_loop、run/sprint、sneak/crouch、
    swim、fly），既用于分类，也用于"动画周期未知"时的回退速度；
  - `computeMultiplier(...)`：按玩家平滑（有状态）；
  - `cycleSeconds(file, name)`：从模型动画文件读周期（tick → 秒）。
- 挂钩点：OpenYSM 控制器路径（`OpenYsmPlayerControllerRuntime.applyPlaybackSpeed`，
  只作用于主身体控制器）与 legacy 路径（`AnimationManager.predicateMain`）。
- 名称表以外的动画名（idle/jump/swing/ride、自定义名）不缩放。

## 注意事项

- GUI 预览（`player == null`）不干预倍速，预览页的暂停/冻结不受影响。
- 倍速本帧设置、下一帧生效（`process` 先算 tick 再调 predicate），16 ms 滞后无感。
- 停止移动时由 idle 重置倍速，不会残留 1.3x 之类的值。
