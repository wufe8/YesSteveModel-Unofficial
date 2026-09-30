# `.molang` 自定义函数：YSMU 实现了哪一部分

YSM-wiki: `molang/script`（2.5.0 起）。官方把 `functions/*.molang` 当**脚本语言**执行
（`args[]`、`t.*` 临时变量、闭包、`fn.*` 链式调用、`loop`/`for_each`、`return` 穿透、
事件订阅 `函数名@事件名.molang`）。YSMU 没有解释器，只对**动画控制脚本**
（`@player_ctrl_<控制器>.molang`，把 `.` 换成 `_ctrl_`）做静态提取 + 少量运行时提示。

## 已实现

1. **状态 → 动画映射**：`MolangFunctionParser.parseStateToAnimationMap()` 提取
   `ctrl.<state> ? { ... ctrl.set_animation('动画名') ... }` 里的第一个调用作为该状态的默认动画；
   `parseConditionalAnimations()` 再提取同一块里带条件守卫的替代动画
   （`v.show_car ? { ctrl.set_animation('开车_待命'); }`），以及**块自己那一层的复合守卫**
   （`(ctrl.walk && ysm.input_vertical < 0.1) ? { ... }` —— 它既不是纯 `ctrl.<state>` 条件、
   块内也常常没有嵌套三元，以前两条路都漏掉）。
   两参数写法 `ctrl.set_animation('walk', ctrl.loop)` 也接受（循环类型本身被忽略，按动画自身的
   循环设置播放）。消费点在 `AnimationManager.getMolangMappedAnimation()`：**条件替代动画先判**
   （脚本顺序 = 优先级），都不成立才用默认映射。
2. **复杂条件求值**：`AnimationManager.evaluateSimpleCondition()` 只对纯 `v.<名字>` /
   `!v.<名字>` 走快速路径，其余条件交给 `OpenYsmControllerExpressionEvaluator.evaluateCondition()`
   —— 于是 `!v.show_car&&!(ysm.food_level<=6)`、`(ctrl.walk && ysm.input_vertical < 0.1)`
   这类式子会真正求值（脚本里的注释也在此前被剥掉，否则注释混进条件会让求值必然失败）。
   求值环境只依赖 `ConditionScope`（读变量 + 调函数）两个查询，所以能用 JUnit 直接喂真实脚本里的
   条件断言选中的动画，不需要开游戏。
3. **过渡时长**：`ctrl.set_beginning_transition_length(秒)` →
   `MolangFunctionParser.parseAnimationHints()` 按动画名收集，运行时在
   `AnimationManager.applyMolangPlaybackHints()` 里写进 `controller.transitionLengthTicks`
   （该动画没有提示时恢复控制器默认值，避免上一段的自定义值泄漏）。
4. **重新加载**：`ctrl.indicate_reload` → 同一动画也被重新 `setAnimation`
   （`AnimationController.markNeedsReload()`）。
   二者都以动画名为键，同一动画在多个状态里给了不同数值时以最后一次为准。
5. **`ctrl.state_bypass`（部分）**：写在一个 `ctrl.<state>` 块**自己那一层**时，表示该状态
   "当前控制逻辑无操作，交回内置逻辑" → 该状态不出默认映射（块内条件分支的替代动画仍有效）。
   脚本**结尾**的 bypass 是整段脚本的兜底，含义正是"没提到的状态用内置逻辑"，而不提的状态
   本来就没有映射条目，所以无需额外处理。

## 二期解释器核心（已落地并**接入事件订阅**）

`com.fox.ysmu.client.animation.molang` 下有一个不依赖 Minecraft 的脚本解释器核心，已覆盖
wiki「自定义函数」页除事件订阅外的语法：

- `args[...]`（含 `args[t.a + 1]` 这类表达式下标，解析前改写成内部调用 `args_get(...)`）；
- 闭包块 `{ ... }`（可作 `? :` 分支或整个函数体）与 `return` 穿透任意嵌套块；
- `fn.*`：`fn.b;`（无参、整段就是它）与 `fn.x(a, b)`（**任意表达式里**都成立，因此递归
  `return n * fn.fact(n - 1);` 可用；带括号的形态走 mclib 的 `fn.` 函数名机制）；
  调用链上限 32，超过返回 0；
- `t.*` **按调用帧隔离**（wiki：调用链每个节点各一层，只有 `v.*` 共享）；
- `loop(n, {...})` / `for_each(t.x, args, {...})` / `break` / `continue`。

表达式层仍复用 `MolangParser`，变量 / 函数 / 调用参数通过
`MolangScriptInterpreter.MolangScriptScope` 注入。
接入方式见 `analysis/keyframe-molang-channels.md`：`functions/<名字>.molang` 会被登记为函数
（`fn.*` 可调用），`<名字>@player_init` / `@player_update` 会在
`MolangPhysicsRuntime.begin()`（= GeckoLib 的 `preAnimationSetup`，「更新玩家动画之前」）触发，
变量读写走 `OpenYsmScriptScope`（读复用控制器求值器、写与控制器的 onEntry/onExit 同一条路径）。
`@sync` 与 `ysm.sync(...)` 仍未接入（要新网络包）；**动画控制脚本
（`[@描述]@player_ctrl_<槽位>.molang`）仍然只做静态提取**，脚本正文不执行。

**两条已知边界**（写下来是因为它们会在"接入"时才咬人）：

- `args[i]` 支持字符串：字符串字面量在解析期被折成 `MolangStringPool` 的 int id（池 id 从
  `1_000_000` 起编号，与普通数值不撞车），`ScopeFunction` 再用 `isStringId()` 还原成
  `Argument.string`，所以 `ctrl.set_animation(args[0])` 这类调用能拿到真正的动画名。
- **块级与括号内的赋值都会写回宿主**：`v.x = 1;` 走解释器的 `AssignmentNode`；夹在表达式里的
  `(v.x = 1) + 2` 走 `MolangParser.parseSymbols` → `MolangAssignment`。后者在脚本通道上曾经失效
  （`ScriptMolangParser` 的变量是 supplier 型 `LazyVariable`，而 vendored `LazyVariable.set()`
  会把 supplier 换成常量，于是宿主收不到写入、本次执行后续读值还被冻住），现在由
  `ScriptMolangParser.FrameVariable` 把 `set()` 改道到 `writeVariable`，两条路一致；
  回归测试见 `MolangScriptCallAndLoopTest`。

## 未实现（按当前库里的实际用量排序）

| 特性 | 库内用量（参考模型库） | 说明 |
| --- | --- | --- |
| `ctrl.state_pause` | 库内 **0 处** | **已确认不做**。GeckoLib 没有"暂停播放但不暂停时间轴"的原语：`tick` 由 `AnimationProcessor` 统一推进，骨骼关键帧与 timeline/音效/粒子事件共用一个 tick。要做得给控制器加"暂停"并让事件循环改用未暂停的 tick（vendored 变更，只能实机验证）。等真有模型用到再说 |
| `ctrl.state_stop` 的**平滑**部分 | `state_stop` 2 文件 / 3 处 | **已确认不做**（只保留"中止"）。"平滑淡出"要改 vendored 的骨骼复位分支：`resetTickLength` 默认 1 且全仓无人调用 `setResetSpeedInTicks`，rotation/position 的 `mostRecentReset*Tick` 被写成 0（只有 scale 用了 `seekTime`，还留着"旋转问题相关"的 TODO）→ 改它会让**所有**控制器"骨骼不再被动画驱动"的复位从瞬变变成淡出，需要实机确认没有旋转问题回归 |
| 非主槽位的动画控制脚本 | `pre_main`/`parallel_N`/`use` 等 | 求值器与注册表都已按槽位就绪，但只有主动画槽位接在 `AnimationManager` 上；其余槽位仍走静态提取 |
| 条件里调用 `fn.*` / 读 `t.*` / `args[]` | 少量（都在自定义函数与事件订阅脚本里） | 条件求值（`AnimationManager` 的名称映射）走的是另一条路，会把这些当未定义（0） |
| `fn.x` 写在更大的表达式里但不带括号 | — | 只有"整段就是 `fn.x`"才当调用；`1 + fn.x` 里的 `fn.x` 会当未定义变量（0）。需要那种写法就写 `fn.x()`（走 mclib 函数机制，任意位置都成立） |

已补齐（原表里的这几条）：执行动画控制脚本、`@sync` + `ysm.sync`、`q.debug_output`/`ysm.dump_*`
调试输出、`ctrl.hold`/`ctrl.use`/`ctrl.swing`/`ctrl.armor`（关键帧与脚本路径，与控制器条件路径
共用 `CtrlItemMatcher`）、`ctrl.reset`。

`docs/README.md` 的收录原则适用：这些都是跨模型的机制说明，具体第三方模型名不入库。

## 下一步（如果要继续补）

1. ~~**把解释器接上**~~ **已完成**：`PreParsedModelBundle` 保留函数体 + 事件表，
   `OpenYsmScriptRuntime` 在 `MolangPhysicsRuntime.begin()` 触发 `@player_init`/`@player_update`，
   `OpenYsmScriptScope` 提供游戏侧读写。**必须实机确认**（`@player_init`/`@player_update`
   与 `@player_ctrl_*` 各走一遍真机模型）。
2. ~~二期剩下的语言特性~~ **已完成**（`fn.*` + 每层 `t.*` + 循环）。注意参考库里
   `fn.` 只出现在 `motorSynth.molang` 的一句注释里、`loop`/`for_each` 一次都没用，
   所以这几条特性目前只有 wiki 示例当规格（见 `MolangScriptCallAndLoopTest`）。
3. ~~三期：`@sync` + `ysm.sync(...)`~~ **已完成**（`C2SMolangSync`/`S2CMolangSync`，id 28/29）；
   ~~把动画控制脚本当控制器主体执行~~ **主槽位已完成**（`AnimationControlScripts`，配置项
   `MolangControlScripts` 可关回静态提取）。剩余见上一节的表。
4. 条件求值第一版只覆盖纯 `v.*` 快速路径 + 控制器求值器；注意它**改变了行为**：以前永远 false 的
   分支会开始命中，需要实机确认那些模型的动画切换是否符合预期（内置 `wine_fox` 包的"低电量 /
   电量过低"分支就是例子）。

## 验证方式

- 单元测试：`MolangFunctionParserTest`（过渡/重载提取、动画之间不串味、两参数写法）、
  `MolangRealScriptTest`（拿内置包的真实脚本当夹具：注释剥离、复合守卫、块内 `state_bypass`）、
  `OpenYsmConditionEvaluationTest`（喂真实脚本里的条件 + 假 `ConditionScope`，断言选中的动画）、
  `AnimationManagerMolangConditionTest`（条件替代动画的选取顺序）、
  `MolangScriptInterpreterTest` / `MolangArgsRewriterTest`（解释器：块/三元/return 穿透/args 下标）、
  `MolangScriptRealFixtureTest`（**执行**内置包的真实脚本：`halo_battery_indicator.molang` 的
  `args[0]`×饱食度×`q.life_time`、`eventsubscriber@sync.molang` 写回宿主变量）、
  `MolangScriptCallAndLoopTest`（`fn.*`/递归/`t.*` 隔离/循环，脚本取自 wiki 示例）。
- **单个表达式**用 `/ysm debug eval <表达式>` 在聊天框验证，不需要任何模型。该入口现在会把本地玩家
  临时设成 `ParticleEffectUtil` 的当前实体，所以依赖实体的函数（`query.is_item_name_any`、
  `query.relative_block_has_any_tag`、`ysm.equipped_enchantment_level`、`query.position` …）
  能正常求值；求值结束后还原实体与变量表。例：
  `/ysm debug eval query.is_item_name_any('mainhand','minecraft:diamond_sword')`（主手拿着钻石剑时应为 1）。
- 实机（脚本级）：装了用 `.molang` 控制动画的模型后，看状态切换的过渡是否变成脚本里写的时长
  （脚本常用 0.1s / 0；默认是 `Config.AnimationTransitionTicks` = 4 tick = 0.2s），
  以及 `ctrl.indicate_reload` 的动画在重复触发时是否重新播放。
- 条件分支是否命中：`DebugController` 打开时，每个 模型×状态 打一条一次性
  `[YSMU-MOLANG] <模型> state '<状态>' -> '<动画>' (conditional branch)`。只要出现这条，
  就说明复杂条件求值在实机里跑通了（脚本里带运算符的分支以前一律为假）。
