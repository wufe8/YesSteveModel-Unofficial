# 格挡条件动画名（剑 / 盾怎么分开覆盖）

## 目标

模型作者想给 1.7.10 的**剑右键格挡**一个独立动画，且不写动画控制器、不用
`query.is_blocking` 手写状态机 —— 只要在动画文件里**显式声明一个名字**就能覆盖。

## 上游语义与 1.7.10 的差异

| | 上游 YSM（1.20.1） | YSMU（1.7.10） |
| --- | --- | --- |
| 盾牌"防御状态" | `UseAnim.BLOCK` → `use_mainhand:block` | `EnumAction.block` → `use_mainhand:block` |
| 剑右键 | 1.9+ 取消了剑格挡，剑没有 use action | `ItemSword` 右键格挡返回 `EnumAction.block` |

所以上游"只有盾牌会命中的 `:block`"，在 1.7.10 **剑和盾都会命中**。想把剑格挡单独拆出来，
必须用一个类别名，因为 `doExtraTest` 的命中顺序是**先物品类别、后 use action**：

```
use_mainhand$<物品ID>  →  use_mainhand#<矿辞>  →  use_mainhand:<类别>  →  use_mainhand:<EnumAction 名>
```

类别由 `InnerClassify.getItemType` 给出（`sword`/`shield`/`bow`/…），`EnumAction` 名是
`block`/`bow`/`eat`/`drink`。剑的类别是 `sword`，所以声明 `use_mainhand:sword` 会先命中，
声明 `use_mainhand:block` 只在没有 `:sword` 时才轮到。

## 可用的名字

| 名字 | 何时命中 | 备注 |
| --- | --- | --- |
| `use_mainhand:sword` / `use_offhand:sword` | 剑格挡（1.7.10 剑只有"使用 = 格挡"） | 上游也认这个名字，但上游的剑没有 use action，**永远不触发**，所以天然只在 YSMU 生效 |
| `use_mainhand:sword_block` / `use_offhand:sword_block` | 剑格挡，与 `:sword` **等价** | YSMU 扩展别名：语义更明确，也为上游将来改动 `:sword` 语义留兼容；上游不认识它，同样只在 YSMU 生效 |
| `use_mainhand:block` / `use_offhand:block` | `EnumAction.block`：剑**和**盾都会命中 | 等同上游的盾牌语义；没声明 `:sword`/`:sword_block` 时剑格挡也走它 |
| `use_mainhand:shield` / `use_offhand:shield` | 类别是 `shield`（按物品类型/注册名/矿辞识别） | 盾牌专用 |
| `use_mainhand$minecraft:shield` 等 | 按物品 ID（`$`）/矿辞（`#`） | 最具体，优先级最高 |

别名规则（`ConditionalUse.resolveCategoryAlias`）：**原名字优先**。两个都声明时用 `:sword`，
既有模型的行为不变；只声明 `:sword_block` 时用别名。

命中之后统一走 `AnimationManager.predicateUse` 的条件分支：
`playAnimation(..., HOLD_ON_LAST_FRAME)` —— 动画播完停在最后一帧，循环类型由这里统一决定，
模型写在动画上的 `loop` 不生效（与 `hold_*` 的处理一致）。

## 没有声明任何名字时的回退

`predicateUse` 在拿不到条件动画时回退到 `use_mainhand` / `use_offhand`；格挡状态下走
`playBlockingAnimation`：把动画长度改成 2.0s 再 `HOLD_ON_LAST_FRAME`（`AnimationManager` 内），
也就是"停在 use 动画最后一帧"的姿态。模型什么都没声明时看到的就是它。

## 和自定义控制器路线的关系

想用控制器（`player.use` + `query.is_blocking`）也能覆盖，但注意两个边界：

- `player.use` 精确槽位才会**替换**内置逻辑（`predicateUse` 里 `tryApply` 先返回）；
  `player.pre_use*` 注册在 `use_controller` 之前，会被内置格挡动画反向覆盖。
  `player.post_use` 在之后，能盖住内置动画但内置路径仍在底层跑。
- 控制器当前状态没有有效动画时 `tryApplyController` 返回 `null`，`predicateUse` 会继续跑
  硬编码回退 —— 于是又出现上面那个"停在 use 最后一帧"的姿态。
- `query.is_blocking` 是 YSMU 专属查询，上游没有；`ctrl.hold/use/swing` 的物品类别仍只认
  `:sword` / `:shield`，**不接受** `:sword_block`（别名只作用于动画名这一层）。

## 已知限制

- 控制器状态动画的"停在最后一帧"取决于动画自己的 `loop`；文件夹模型写
  `"loop": "hold_on_last_frame"` 曾在同步往返里被丢掉（`parseLoopMode` 编成 2，而
  `putLoopMode` 只认 3），症状正好和这里混淆 —— 见
  [`animation-length-semantics.md`](animation-length-semantics.md) 的「`loop` 的编码与同步往返」。
- `BlockingCompat.isBlocking` 的三条路径里，"按住右键 + 副手盾"那条只对**本地玩家**生效，
  Battlegear2 的格挡标记只持续 1-2 tick；联机时本地与远端看到的格挡状态可能不同步。
- 剑的 offhand 覆盖（`use_offhand:sword`）只在副手确实是"正在使用的那只手"时才会被选中，
  1.7.10 原版剑格挡只有主手。

## 验证方法

1. 模型声明 `use_mainhand:sword_block`（或 `:sword`），副手放盾牌另测。
2. 主手拿剑右键按住 → 应播该动画并停在最后一帧；主手盾牌右键 → 仍走 `:block` / `:shield`。
3. 两个都不声明时，剑格挡应回退到 `use_mainhand` 的最后一帧姿态（对照组）。
4. 联机场景需要两个客户端各看一次，确认远端玩家的剑格挡也生效。

## 代码位置

- `client/animation/condition/ConditionalUse.java` —— `classifyExtra` / `resolveCategoryAlias`（命中顺序与别名）
- `client/animation/condition/InnerClassify.java` —— 物品类别（`sword`/`shield`/…）
- `client/animation/AnimationManager.java` —— `predicateUse` / `playBlockingAnimation`
- `compat/BlockingCompat.java` —— `query.is_blocking` 与格挡判定
- `src/test/java/.../condition/ConditionalUseTest.java` —— 命中顺序与别名等价的回归测试
