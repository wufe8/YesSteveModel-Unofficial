# `.molang` 自定义函数：YSMU 实现了哪一部分

YSM-wiki: `molang/script`（2.5.0 起）。官方把 `functions/*.molang` 当**脚本语言**执行
（`args[]`、`t.*` 临时变量、闭包、`fn.*` 链式调用、`loop`/`for_each`、`return` 穿透、
事件订阅 `函数名@事件名.molang`）。YSMU 没有解释器，只对**动画控制脚本**
（`@player_ctrl_<控制器>.molang`，把 `.` 换成 `_ctrl_`）做静态提取 + 少量运行时提示。

## 已实现

1. **状态 → 动画映射**：`MolangFunctionParser.parseStateToAnimationMap()` 提取
   `ctrl.<state> ? { ... ctrl.set_animation('动画名') ... }` 里的第一个调用作为该状态的默认动画；
   `parseConditionalAnimations()` 再提取同一块里带条件守卫的替代动画
   （`v.show_car ? { ctrl.set_animation('开车_待命'); }`）。
   两参数写法 `ctrl.set_animation('walk', ctrl.loop)` 也接受（循环类型本身被忽略，按动画自身的
   循环设置播放）。消费点在 `AnimationManager.getMolangMappedAnimation()`。
2. **过渡时长**：`ctrl.set_beginning_transition_length(秒)` →
   `MolangFunctionParser.parseAnimationHints()` 按动画名收集，运行时在
   `AnimationManager.applyMolangPlaybackHints()` 里写进 `controller.transitionLengthTicks`
   （该动画没有提示时恢复控制器默认值，避免上一段的自定义值泄漏）。
3. **重新加载**：`ctrl.indicate_reload` → 同一动画也被重新 `setAnimation`
   （`AnimationController.markNeedsReload()`）。
   二者都以动画名为键，同一动画在多个状态里给了不同数值时以最后一次为准。

## 未实现（按当前库里的实际用量排序）

| 特性 | 库内用量（参考模型库） | 说明 |
| --- | --- | --- |
| 复杂条件求值 | 大量（`!v.show_car&&!(ysm.food_level<=6)` 之类） | **只支持纯 `v.<名字>` / `!v.<名字>`**（`AnimationManager.evaluateSimpleCondition()`）；含 `&&`/`||`/比较运算的条件一律判 false，于是回落到该状态的默认动画。也就是说"电量低/开车"这类分支目前不生效 |
| `ctrl.set_beginning_transition_length` 之外的 ctrl API | `ctrl.use`/`ctrl.swing` 各 3 次、`ctrl.indicate_reload` 2 文件 | 脚本里的 `ctrl.use(...)`/`ctrl.swing(...)` 不会被执行（控制器条件路径里的同名函数是另一套实现） |
| `t.*` / `args[]` | 9 / 6 个文件 | 临时变量与参数只在自定义函数/事件订阅里有意义 |
| 事件订阅 `@player_init` / `@player_update` / `@sync` | 各 1 个文件（`eventsubscriber@sync.molang`） | 需要事件总线 + `ysm.sync` 网络包 + `v.roaming` 同步，属于新功能 |
| `fn.*`、`loop`、`for_each`、`break`/`continue`、闭包 | `fn.` 1 个文件，循环 0 | 需要真正的脚本解释器 |
| `ctrl.state_bypass` / `state_pause` / `state_stop`、`ctrl.reset` | `state_bypass` 6 个文件 | 现在无法表达"这一帧交回内置逻辑"；解析器只会给出默认映射 |

`docs/README.md` 的收录原则适用：这些都是跨模型的机制说明，具体模型名只在 `local/` 里出现。

## 下一步（如果要继续补）

1. **复杂条件求值**（收益最大）：`OpenYsmControllerExpressionEvaluator` 已有一套编译型表达式求值
   （`CompiledExpr` + `Context`，含 `ctrl.*`/`v.*`），把它的求值入口导出成
   `public static boolean evaluateCondition(String, EntityPlayer)`（`Context` 的构造点在
   `OpenYsmPlayerControllerRuntime`），然后让 `evaluateSimpleCondition()` 走它。
   注意这会**改变行为**：以前永远 false 的分支会开始命中，需要实机确认那些模型的动画切换是否符合预期。
2. `ctrl.state_bypass` 语义：需要给映射增加"无覆盖"这一档，让内置谓词接管该状态。
3. 事件订阅 + `ysm.sync`：新网络包 + 每个模型的事件注册表，工作量大，先确认有模型真的依赖它。

## 验证方式

- 单元测试：`MolangFunctionParserTest`（过渡/重载提取、动画之间不串味、两参数写法）。
- **单个表达式**用 `/ysm debug eval <表达式>` 在聊天框验证，不需要任何模型。该入口现在会把本地玩家
  临时设成 `ParticleEffectUtil` 的当前实体，所以依赖实体的函数（`query.is_item_name_any`、
  `query.relative_block_has_any_tag`、`ysm.equipped_enchantment_level`、`query.position` …）
  能正常求值；求值结束后还原实体与变量表。例：
  `/ysm debug eval query.is_item_name_any('mainhand','minecraft:diamond_sword')`（主手拿着钻石剑时应为 1）。
- 实机（脚本级）：装了用 `.molang` 控制动画的模型后，看状态切换的过渡是否变成脚本里写的时长
  （脚本常用 0.1s / 0；默认是 `Config.AnimationTransitionTicks` = 4 tick = 0.2s），
  以及 `ctrl.indicate_reload` 的动画在重复触发时是否重新播放。
