# Molang 调试覆盖层（Debug Overlay）

实现入口：`client/gui/debug/DebugOverlay.java`、`client/input/DebugOverlayKey.java`、
`client/debug/MolangDebugSnapshot.java`。

## 用途

把 `MolangParser.VARIABLES`、`OpenYsmPlayerControllerRuntime.PENDING_ROAMING`、最近一帧
`ScopeState` 快照与 `ctrl.*` 的实时值铺成一屏只读表格，用来回答"这个模型的某个条件为什么
成立/不成立"。它不修改任何模型状态。

## 操作与布局

| 功能 | 行为 |
| --- | --- |
| 快捷键 | `Ctrl + <ExtraPlayerConfigKey 绑定的键>`（基键默认 P）；随玩家改绑变化 |
| 命令 | `/ysm debug overlay on` / `off` / `toggle`，权限等级 4 |
| 关闭 | 搜索中 Esc 退出搜索；非搜索模式 Esc 关闭覆盖层 |
| 绘制 | `RenderGameOverlayEvent.Post` 的 `ElementType.ALL`；不是 GuiScreen |
| 数据 | `getAllVariables()` 提供全量快照；`resolveVariables()` 留给 debug query 的匹配查询 |
| 搜索 | 子串匹配，`q.` 展开为 `query.`；命中段以白色突出 |
| 导航 | 方向键、PageUp/PageDown、Home/End；Enter 切换搜索模式 |
| 布局 | Name / Value 与最右侧来源或类型提示；窄屏按文字宽度省略 |

## 数据源与诊断语义

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

## 能力边界

- 只显示 `MolangParser.VARIABLES` + `PENDING_ROAMING` + 最近一帧 ScopeState + 固定 `ctrl.*`
  名单里的项；模型自定义、当帧未被写入的变量不会出现。
- 覆盖层每帧做一次全量变量读取与列表重建，属客户端诊断功能，未做性能优化。
