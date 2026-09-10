# 粒子系统（`particle()` / `abs_particle()`）

1.7.10 没有"按任意注册 id 生成粒子"的通用 API，也没有高版本的粒子类（水滴、水花等）。
YSMU 用一层落地适配实现模型里的 `ysm.particle(...)` / `ysm.abs_particle(...)`，并尽量对齐
YSM 2.6.5 的观感。

## 模块

| 模块 | 文件 | 职责 |
| --- | --- | --- |
| Molang 函数 | `client/animation/molang/ParticleFunction.java` | 动画关键帧里的 `particle()`（mclib 函数） |
| 控制器函数 | `client/animation/controller/OpenYsmControllerExpressionEvaluator.java` | `.molang` 指令里直接调用 `particle()` |
| 核心分发 | `client/particle/ParticleEffectUtil.java` | 参数语义、散布、朝向旋转、解析、发射 |
| 行为表 | `client/particle/ParticleBehaviors.java` | 高版本粒子的物理/外观近似参数 |
| 纹理 | `client/particle/ParticleTextureManager.java` | 高版本粒子 PNG 解码、GL 纹理缓存、失败去重 |
| 粒子实例/管理 | `client/particle/CustomParticleFX.java`、`CustomParticleManager.java` | 独立渲染层 3 的 billboard 粒子、tick、按纹理分组批渲染 |
| 渲染注入 | `mixin/MixinEffectRenderer.java` | 在 vanilla `renderParticles` TAIL 渲染自定义粒子 |
| 资产读取 | `compat/LocalAssetProvider.java` | 从高版本游戏目录/版本 jar 读粒子纹理与音效 |
| 指令 | `command/ParticleCommand.java` + `network/message/SpawnParticleCommand.java` | `/particle`（服务端广播，packet id 25） |

## 参数语义

`particle(id, ox, oy, oz, dx, dy, dz, speed, count, lifetime)`（`abs_particle` 用绝对世界坐标）：

- `count == 0`：单粒子；位置 = 实体 + offset，速度 = `speed × delta`。
- `count > 0`：`count` 个粒子；位置 = 实体 + offset + 高斯散布（`delta` 为 σ），速度 = 高斯 × `speed`；
  批量上限 `MAX_BATCH_COUNT = 64`。
- offset 在**相对模式**下绕 Y 轴按 `renderYawOffset`（玩家）/`rotationYaw`（其他实体）旋转；
  绝对模式原样使用。
- **Y 用 `boundingBox.minY`（脚底）**，不是 `posY`：1.7.10 玩家的 `posY` 含 1.62 的
  `yOffset`，直接用会让粒子系统性偏高一个眼睛高度。这与官方 1.20.1 的 `entity.getY()`
  （脚底）语义一致。

## 行为表与回退

只有行为表覆盖的粒子才启用高版本纹理；纹理不可用时按 `VANILLA_FALLBACK` 映射到 1.7.10
内置近似粒子（`falling_dripstone_water → dripWater`、`rain → droplet`、`snowflake → snowshovel`、
`poof → smoke`、`explosion → largeexplode` 等），无映射则用原名交给 vanilla（未知名静默跳过）。

当前覆盖 4 类：

| 类别 | 粒子名 | 关键行为 |
| --- | --- | --- |
| 水滴 | `falling_dripstone_water`、`dripping_*`、`falling_water` | 忽略外部速度、`initVy=-0.1`、`gravity=0.6`、`scale=0.7`、tint 深水色 `(0.2,0.3,1.0)`、lifetime 60 |
| 水花 | `splash`、`rain` | 忽略外部速度、`initVy=0.1`、`gravity=0.4`、`scale=0.9`、lifetime 8 + 随机 0–32、渐隐、撞地/入液消失 |
| 火焰 | `flame`、`small_flame`、`copper_fire_flame`、`soul_fire_flame` | 保留外部速度、`gravity=-0.05`（上升）、`scale=0.6`、lifetime 40 |
| 雪花 | `snowflake`、`snow` | 忽略外部速度、`initVy=-0.04`、`scale=0.55`、lifetime 50、渐隐 |

高版本纹理读取顺序：版本 jar（`versions/<jarVer>/<jarVer>.jar` 内 `assets/minecraft/...`）
优先，其次 `assets/objects/<hash>`；先解析 `particles/<name>.json` 的 `textures` 字段，
缺失时回退 `textures/particle/<name>.png`。

## 为什么不用 vanilla 的 EffectRenderer

1.7.10 的 `EffectRenderer` 只渲染 layer 0/1/2，自定义粒子需要独立层；且 SRG 环境下
`@Shadow` 拿不到 `fxLayers` 字段（refmap 只生成方法映射）。因此自定义粒子走独立列表
（`CustomParticleManager`，上限 1024，满则移除最旧）+ 只在 `renderParticles` 做 TAIL 注入。
混合/亮度参数与 vanilla 粒子保持一致（`GL_BLEND` + `SRC_ALPHA/ONE_MINUS_SRC_ALPHA` +
`glAlphaFunc(GREATER, 0.0039)` + `glDepthMask(false)`）。

## 配置与调试

| 配置（`ysmu.cfg`） | 默认 | 说明 |
| --- | --- | --- |
| `HighVersionGamePath` | `""` | 高版本游戏目录 |
| `HighVersionAssetVersion` | `"32"` | `assets/indexes/` 索引版本 |
| `HighVersionJarVersion` | `"26.2"` | 含客户端 jar 的版本目录（新版粒子纹理在这里） |
| `DebugParticle` | false | `[YSMU-PARTICLE]` 生成/纹理/回退日志（每个粒子名只打一次回退） |
| `ParticleYAdjust` | 0.0 | 额外下移量，用于校正整体高度 |
| `ParticleZeroOffset` | false | 强制 offset 归零，确认粒子是否生成在实体位置 |

`/particle`（服务端，op 2；末尾的 `[force|normal] [<viewers>]` 目前静默忽略，统一广播）：

    /particle minecraft:splash
    /particle minecraft:splash ~ ~ ~ 0.5 0.5 0.5 0 20
    /particle minecraft:falling_dripstone_water ~ ~1 ~ 0 0 0 1 1
    /particle minecraft:flame 100 64 100 0.5 0 0.5 0.1 10

模型侧的调用形态（用于确认参数顺序）：`ysm.particle('flame', 0, 1, 0, 0.5, 0, 0.5, 0.1, 4, 10)`；
用骨骼定位时走 `ysm.bone_pivot_abs('Locator')` 得到绝对枢轴再除以 16。

## 已知限制

- 多帧纹理动画未实现（`splash_0..3`、`explosion 0..15` 等按整图 0..1 渲染，未按 age 切帧）。
- 水滴的"悬挂→下落"两阶段未实现（只有下落）。
- `/particle` 的 `[viewers]` 选择器未实现（统一广播）。
- 行为表只覆盖上述 4 类，其余高版本粒子走 vanilla 回退或静默跳过。

## 常见坑

- 粒子整体偏高 ≈1.6 格：用的是 `posY` 而不是脚底，见上文。
- 水花看不见或飞散：靠行为表的"忽略外部速度 + `initVy=0.1` + 撞地消失"处理；模型传了极大
  speed 时用 `velocityScale` 缩放。
- 同进程内"高版本纹理 + vanilla 回退"混用：`LocalAssetProvider.reset()`（配置变更时）会清空
  `ParticleTextureManager` 的 GL 纹理与失败缓存，否则旧纹理 id/失败状态会残留。
- 只填 asset 版本不填 jar 版本时新版粒子纹理取不到（新版纹理在版本 jar 内），chat 会提示并
  列出可用的版本目录。
