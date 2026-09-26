# Molang 调试覆盖层（Debug Overlay）

设计稿在 `local/analysis/debug-overlay-design.md`。本文按**当前源码**给每项标注
已实现 / 未实现 / 与设计不同，代码在 `client/gui/debug/DebugOverlay.java`、
`client/input/DebugOverlayKey.java`、`client/debug/MolangDebugSnapshot.java`。

## 用途

把 `MolangParser.VARIABLES`、`OpenYsmPlayerControllerRuntime.PENDING_ROAMING`、最近一帧
`ScopeState` 快照与 `ctrl.*` 的实时值铺成一屏只读表格，用来回答"这个模型的某个条件为什么
成立/不成立"。它不修改任何模型状态。

## 设计 vs 实现

| 设计项 | 状态 | 实际 |
| --- | --- | --- |
| `Ctrl+F3` / `Alt+B` 快捷键 | **未实现（改用别的组合）** | `Ctrl + <ExtraPlayerConfigKey 绑定的键>`（默认 P）。基键从 `ExtraPlayerConfigKey.EXTRA_PLAYER_RENDER_KEY` 读，玩家在 Controls 改绑 Alt+P 时 Ctrl+新键一起跟随，不写死 KEY_P。 |
| `/ysm debug overlay [on\|off\|toggle]` | 已实现 | `YsmCommand`；`debug` 子命令要求权限等级 4。命令开启时聊天栏提示一次快捷键。 |
| `Esc` 关闭 | 已实现（两段式） | 搜索模式内 Esc 只退出搜索；非搜索模式 Esc 才关闭。 |
| 经 `RenderGameOverlayEvent.Post` 绘制 | 已实现 | `ClientEventHandler.onRenderOverlay`，只在 `ElementType.ALL` 时渲染。不是 GuiScreen，不拦 GUI 输入。 |
| 数据源 `MolangDebugSnapshot.resolveVariables()` | **与设计不同** | 覆盖层用 `getAllVariables()`（全量快照）；`resolveVariables()` 留给 `/ysm debug query` 的精确/通配/模糊匹配。 |
| 过滤框 + 实时筛选 | 已实现 | 子串匹配；输入 `q.` 会展开为 `query.`（`expandSearchAlias`）。 |
| 过滤匹配高亮 | **部分** | 只把命中段画成白色文字，没有设计里的白底/下划线。 |
| 滚动：↑↓ / PageUp / PageDown / Home / End | 已实现 | 另加 `←` / `→` 翻页、`Enter` 进入/退出搜索模式。 |
| 三列布局（Name / Value / Type） | **与设计不同** | 表头只有 Name / Value；第三列在最右侧，`v.*` 显示来源模型 `@model`，其余显示类型提示。 |
| 配色（背景/标题/交替行/true 绿 false 灰） | 已实现 | 背景 alpha 由设计稿 `0xAA` 调为 `0xCC`；变量名用淡黄 `0xFFFFFFAA` 而非 `§e`。 |

## 设计稿没有、实现里补上的

- **来源列 `@模型`**：`MolangPhysicsRuntime.getGlobalVarSource` + 当前玩家模型，用来定位
  "重名 `v.*` 被别的模型的 timeline 写进全局变量表"造成的跨模型串变量（最右列蓝色 `@…`）。
- **动态 `ctrl.*`**：`getAllVariables()` 对
  `OpenYsmControllerExpressionEvaluator.CONTROLLER_STATE_NAMES` 里的状态名调
  `OpenYsmControllerExpressionEvaluator.evaluateCtrlState`，而不是读静态注册值。
  状态名单与判定规则都只有一份（`evaluateCtrlState` 直接委托控制器路径的
  `Context.isControllerState`）：以前这里抄过一份判定，`ctrl.idle` 的排除列表漏了
  `walk`/`run`，走路时叠加层报 `idle=1` 而模型看到的是 `0`，按它排查会走错方向。
- **动画完成查询的真实值**：`query.any_animation_finished` / `query.all_animations_finished`
  的静态注册值恒为 0，覆盖层改用求值缓存的真实值，并补充每个控制器单独的
  `query.<q>@<geckoControllerName>`。
- **嵌套赋值的 `v.*`**：覆盖层叠加最近一帧 `ScopeState` 快照，能看到 `v.wet` 这类只在动画
  内部赋值的变量。
- **窄屏自适应**：标题/过滤框/帮助栏按字体宽度决定是否省略，避免互相重叠。

## 键盘独占（为什么不用普通 KeyBinding）

搜索模式下打字不能触发原版快捷键。实现分三处：

1. `DebugOverlay.handleKeyInput()` 消费按键；
2. `DebugOverlayKey` 在搜索模式下对 `gameSettings.keyBindings` 逐个调 `isPressed()` 把按下
   计数吃掉（`KeyBinding.isPressed()` 会递减 `pressTime`），使原版后续打开背包/丢弃/聊天/
   快捷栏检查全部落空；
3. `MixinMinecraft` 在 `displayInGameMenu` 的 HEAD 取消，避免 Esc 打开暂停菜单（该检查发生在
   `KeyInputEvent` 之前，事件处理器来不及拦）。

## 已知限制 / 未验证

- 只显示 `MolangParser.VARIABLES` + `PENDING_ROAMING` + 最近一帧 ScopeState + 固定 `ctrl.*`
  名单里的项；模型自定义、当帧未被写入的变量不会出现。
- 覆盖层每帧做一次全量变量读取与列表重建，属客户端诊断功能，未做性能优化。
- 与 `Ctrl+P` 共用基键是有意为之，但未在游戏内穷举所有键位冲突场景。
- 设计稿的颜色/布局常量与实现有出入，以代码为准（本文表格已列出差异）。
