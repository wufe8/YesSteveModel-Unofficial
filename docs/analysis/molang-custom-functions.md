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

## 未实现（按当前库里的实际用量排序）

| 特性 | 库内用量（参考模型库） | 说明 |
| --- | --- | --- |
| 条件里调用 `fn.*` / 读 `t.*` / `args[]` | 少量（都在自定义函数与事件订阅脚本里） | 条件求值会把这些当未定义（0）。真正需要它们是二期解释器的事 |
| `ctrl.set_beginning_transition_length` 之外的 ctrl API | `ctrl.use`/`ctrl.swing` 各 3 次、`ctrl.indicate_reload` 2 文件 | 脚本里的 `ctrl.use(...)`/`ctrl.swing(...)` 不会被执行（控制器条件路径里的同名函数是另一套实现） |
| `t.*` / `args[]` | 9 / 6 个文件 | 临时变量与参数只在自定义函数/事件订阅里有意义 |
| 事件订阅 `@player_init` / `@player_update` / `@sync` | 各 1 个文件（`eventsubscriber@sync.molang`） | 需要事件总线 + `ysm.sync` 网络包 + `v.roaming` 同步，属于新功能 |
| `fn.*`、`loop`、`for_each`、`break`/`continue`、闭包 | `fn.` 1 个文件，循环 0 | 需要真正的脚本解释器 |
| `state_pause` / `state_stop` / `ctrl.reset` | `state_stop` 2 个文件 | `state_stop`/`state_pause` 仍算"脚本接管了该状态"，所以默认映射保留；它们与 `state_continue` 的**播放方式**差异（暂停时间轴 / 平滑停止）静态提取表达不了，`ctrl.reset` 同理 |

`docs/README.md` 的收录原则适用：这些都是跨模型的机制说明，具体模型名只在 `local/` 里出现。

## 下一步（如果要继续补）

1. **解释器核心**（二期，见 `local/plans/molang-custom-functions-execplan.md`）：`args[]`、`t.*`、
   闭包块 `{...}`、`return` 穿透、`fn.*` 链式调用、`loop`/`for_each`/`break`/`continue`。
   核心类不依赖 Minecraft，几乎可以全部用 JUnit 覆盖。
2. 事件订阅 + `ysm.sync`（三期）：新网络包 + 每个模型的事件注册表，工作量大，先确认有模型真的依赖它。
3. 条件求值第一版只覆盖纯 `v.*` 快速路径 + 控制器求值器；注意它**改变了行为**：以前永远 false 的
   分支会开始命中，需要实机确认那些模型的动画切换是否符合预期（内置 `wine_fox` 包的"低电量 /
   电量过低"分支就是例子）。

## 验证方式

- 单元测试：`MolangFunctionParserTest`（过渡/重载提取、动画之间不串味、两参数写法）、
  `MolangRealScriptTest`（拿内置包的真实脚本当夹具：注释剥离、复合守卫、块内 `state_bypass`）、
  `OpenYsmConditionEvaluationTest`（喂真实脚本里的条件 + 假 `ConditionScope`，断言选中的动画）、
  `AnimationManagerMolangConditionTest`（条件替代动画的选取顺序）。
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
