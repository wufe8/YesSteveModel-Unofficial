# 模型里的 Molang：四条通道与 YSMU 的执行点

一个模型包里的 Molang 会从四个互不相干的地方进来。它们由不同的代码执行、
支持程度也不同 —— 排查"脚本写了却没生效"时先确认作者把代码放在了哪条通道上。

| 通道 | 在包里的位置 | YSMU 的执行点 | 状态 |
| --- | --- | --- | --- |
| ① 脚本函数 | `functions/名字.molang`、`functions/名字@事件.molang` | `MolangScriptInterpreter` + `OpenYsmScriptRuntime` | 已接入（含 `@sync`，见下） |
| ② 关键帧值 | `animations/*.json` 的骨头 `position`/`rotation`/`scale` 写成 Molang 字符串 | `JsonKeyFrameUtils` → `KeyFrame` 持有 `IValue`，每帧求值 | 已支持 |
| ③ 时间轴 | `animations/*.json` 的 `timeline` | `MolangInstructionExecutor`（GeckoLib 的 custom instruction keyframe） | 已支持（本次补注释剥离） |
| ④ 动画控制脚本 | `functions/[@描述]@player_ctrl_<槽位>.molang` | `AnimationControlScripts` + `AnimationControlScope`，主槽位接在 `AnimationManager` | 已执行（`pause`/`stop`/`reset` 未接线） |

## ① 脚本函数与事件订阅

- 文件名解析（`MolangScriptRegistry`，纯函数有单测）：`名字@事件` 里事件必须是
  `player_init` / `player_update` / `sync` 之一；带 `@` 但不是已知事件的**是动画控制脚本**
  （④），登记成函数会与描述前缀混淆，所以明确排除。名字不区分大小写。
- 触发顺序（wiki）：`player_init` > `player_update` > `sync`，且 `v.roaming` 的同步早于
  `player_init`。YSMU 的触发点在 `MolangPhysicsRuntime.begin()` 的末尾 —— 它由
  `CustomPlayerModel.setMolangQueries()`（GeckoLib 的 `preAnimationSetup`）调用，
  正好是"每次更新玩家动画之前"，而漫游变量就在同一函数里刚注入完。
- `@player_init` 每个 (玩家, 模型) 只跑一次；模型缓存刷新、玩家登出时清标记（会重跑）。
- 事件脚本同时是函数（wiki：`setup@player_init.molang` 创建了名为 `setup` 的函数），
  所以 `fn.setup` 也调得到。
- 变量读写走 `OpenYsmScriptScope`：读复用控制器求值器的 `Context`（`q.*`/`ysm.*`/`ctrl.*`
  那几百行查询），写与控制器的 `onEntry/onExit` 同一条路径（`MolangPhysicsRuntime.setVariable`
  + `v.roaming.*` 回写 `PENDING_ROAMING`）。脚本写的 `v.*` 当帧就对骨骼可见。
- 尚未接入的是 `@sync` + `ysm.sync(...)`：需要新网络包（三期）。
- 提醒：`ysm.keyboard(...)` 读的是**本地**键盘。脚本对每个渲染中的玩家模型都会跑，
  所以远端玩家的键盘类脚本会跟着本地按键走 —— 已知偏差，等真的遇到再说。
- 性能：`@player_update` 的脚本每次调用都会重新解析（解释器没有 AST 缓存）。事件脚本通常只有
  几行，没问题；如果将来有模型在 `@player_update` 里塞一整段脚本，再给解释器加按文本缓存。
  通道 ③ 的 `MolangInstructionExecutor` 已经有指令级缓存，所以大 timeline 只付 `IValue.get()`。

## ② 关键帧值写成 Molang

Bedrock 允许把骨头的静态值写成 Molang：`"position": ["v.x","v.y",0]`、`"scale": "v.s"`。
`JsonAnimationUtils.getPositionKeyFrames()` 把数组形式包成 t=0 的一帧，
`JsonKeyFrameUtils` 把每个分量 `parseExpression()` 成 `IValue`；非 `ConstantValue` 的会在每帧
`get()`，所以这是**每帧驱动骨骼**（`scale` 给 0 就能隐藏部件，是常见的显隐手法）。

### `query.position_delta`：两个入口共用一份增量

同一个名字有两个入口，读的是同一份数据：

| 写法 | 走哪条路 | 实现 |
| --- | --- | --- |
| 关键帧/时间轴 `q.position_delta(0)` | mclib 解析 → `QueryPositionDeltaFunction` | 读静态槽 |
| `.molang` 脚本 / 控制器条件 `q.position_delta(0)` | `ScriptMolangParser` → `OpenYsmControllerExpressionEvaluator.Context.functionValue` | 同一个静态槽 |
| 裸变量 `q.position_delta`（无参） | `parser.setValue` 的 `LazyVariable` supplier | 返回位移长度 |

**坑在哪**：静态槽原来只在裸变量那个 supplier 里写，而 `LazyVariable` 只在**被读到**时才求值。
模型如果只用函数版（例如 `car_stuff` 用 `q.position_delta(0/2)` 算 `t.speed_frame` 再累加成
`v.wheel_rotate` 驱动轮胎），裸变量永远没人读 → 槽里恒为 0 → **位移存在但车轮不转**。
现在增量由 `AnimationRegister.setEntityQueryValues` 每帧主动 `update()`，两个入口共用；
`AnimationRegister.setPreviewParserValues` 会把槽归零，否则 GUI 预览会拿世界渲染最后一个
玩家的位移驱动轮胎。回归测试：`QueryPositionDeltaTest`（`update()`/`delta()`/mclib 函数版读取；
"每帧真的调用 update"要真实 `EntityPlayer`，只能实机验证）。

## ③ 时间轴（timeline）

`timeline` 是**动画级**通道，在 `MolangInstructionExecutor` 里执行：

- YSM 允许写成字符串**数组**（官方 Bedrock 是单表达式）；`JsonAnimationUtils.instructionString()`
  用 `;` 拼成一条指令串。
- 数组元素之间必须用 **`";
"`** 拼接（不是 `";"`）。作者常把 `.molang` 整段贴进 `timeline`，
  里面带 C 风格注释，而行注释是"吃到**行尾**"的：只用 `;` 拼、串里没有换行时，**第一个 `//`
  会把后面所有语句全部吃掉**。实测一条 21959 字符的 timeline 被剥成 0 字符 ⇒ 整个模型的 Molang
  一行都不执行（表现为"游戏完全不响应按键"）。保留换行后，注释才会在元素边界处结束。
- 剥注释在切语句**之前**做（按上面保留的换行），这样"注释里带 `;`"不会把语句切坏，
  注释碎片也不会变成解析失败的"语句"。
- 例子：一个 1 tick、`loop: true` 的并行动画（`pre_parallel2` → 隐式控制器
  `player.pre_parallel_2`）每帧跑几百行 timeline，就能把整个小游戏跑起来：状态写在 `v.*`，
  骨头按 ② 读出来显示。

## ④ 动画控制脚本

`functions/[@描述]@player_ctrl_<槽位>.molang` 的槽位从**最后一个** `@` 之后解析、忽略描述前缀
（参考库里两种写法都有），由 `MolangScriptRegistry.controlSlotOf()` 实现（有单测）。
两条路径同时存在：

- **静态提取**（`MolangFunctionParser`，一直是兜底）：状态→动画映射、过渡时长、`indicate_reload`，
  给 `AnimationManager.MOLANG_*_MAP` 用；见 `analysis/molang-custom-functions.md`。
- **每帧执行**（`AnimationControlScripts`）：把脚本正文交给 `MolangScriptInterpreter` 跑一遍，
  由 `AnimationControlScope` 包住宿主作用域并截获 `ctrl.*`：

  | 脚本里写的 | 求值器捕获成 |
  | --- | --- |
  | `ctrl.set_animation(名字[, ctrl.loop|play_once|hold_on_last_frame])` | 动画名 + 循环类型 |
  | `ctrl.set_beginning_transition_length(秒)` | 过渡秒数 |
  | `ctrl.indicate_reload`（带不带 `()` 都认） | reload 标志 |
  | `ctrl.reset`（同上） | reset 标志 |
  | `return ctrl.state_continue / state_pause / state_stop / state_bypass` | 谓词 |

  谓词编码成 `1e9+1..4`：脚本的结果是"最后一条语句的值"，若用 1/2/3/4，结尾写成
  `ctrl.set_animation(...)` 或随手留一个 `v.x` 都会被误读成谓词；控制函数因此恒返回 0。

- **接线范围**：`AnimationManager.applyControlScript` 由三个谓词入口调用 ——
  `predicateOpenYsmSlot`（`pre_main`/`post_main`/`pre_hold`/`pre_swing`/`pre_use`…）、
  `predicateParallel`（数字 `pre_parallel_N`/`parallel_N`）、`predicateUse`（`use`）；
  主动画槽位在 `getActiveAnimations` 里内联求值（先跑脚本再决定目标动画，不绕过
  `predicateMain` 的 `legacyBodyActive` 等簿记）。槽位名由 `controlSlotName` 从控制器名推导
  （`player.X` → `X`、`X_controller` → `X`）；具名并行备用池（`*_extra_N_controller`）推不出名字，
  由 `AnimationManager.controlSlotFor` 问 `OpenYsmPlayerControllerRuntime.namedParallelControlSlot`
  拿当前模型路由到的槽位。只在"明确 `state_continue` + 动画名存在"时覆盖内置逻辑，
  `bypass`/`NONE`/脚本报错/动画不存在一律回退，所以开启它（`Config.MolangControlScripts`，
  可关闭回旧行为）不会让本来能动的模型不动。脚本给出的循环类型会用上
  （`ctrl.loop`/`play_once`/`hold_on_last_frame`）。
- **指令的落地**（`AnimationManager.decideControlScript` → `applyControlScriptStop`）：
  `ctrl.reset` = `PlayState.STOP`（清骨骼队列即"粗暴中止"）+ `markNeedsReload` + 清该槽位的
  控制器运行时状态；`state_stop` = 同样的中止 + 重载。`state_pause` 交回内置逻辑
  （GeckoLib 没有暂停原语），`DebugController` 下每个 模型×槽位 记一条一次性日志，不假装支持。
  清控制器状态时按 `player.` 前缀/`_controller` 后缀归一化匹配，因此池承载的具名并行槽位
  （运行时键是 `player.<族>_<名字>`）也能被 `ctrl.reset` 重置到。
- **`state_stop` 的"平滑"与 `state_pause`：已确认不做**（库内用量 `state_stop` 2 文件 3 处、
  `state_pause` 0 处）。`state_stop` 的"平滑"要改 vendored 的骨骼复位分支：`resetTickLength`
  默认 1 且全仓无人调用 `setResetSpeedInTicks`，rotation/position 的 `mostRecentReset*Tick` 被硬写成 0
  （只有 scale 用了 `seekTime`，还留着"旋转问题相关"的 TODO），所以 `percentageReset` 第一帧就是 1；
  改它会让**所有**控制器的复位从瞬变变成淡出。`state_pause` 要给控制器加暂停原语并让
  timeline/音效/粒子事件循环改用未暂停的 tick。两者都只能实机验证，等真有模型用到再做。
- **字符串实参**：`ScopeFunction` 用 `MolangStringPool.isStringId()` 把池化 id 还原成字符串，
  否则 `ctrl.set_animation('x')` 只能拿到数字；池 id 从 `1_000_000` 起编号正是为了让这个判断可靠。

### `@sync`（主动同步）

`ysm.sync(数值...)`（≤16 个参数）在**两条通道**都能发起（关键帧用 `YsmSyncFunction`，
控制器/脚本用求值器的 `functionValue`）：客户端 → `C2SMolangSync`(id 28) → 服务端广播
`S2CMolangSync`(id 29) → 各客户端按"发起者 + 包里带的模型"跑该模型的 `sync` 事件脚本
（`OpenYsmScriptRuntime.runSyncScripts`）。发起后立刻返回；因为 wiki 说"开销相当大"，
客户端侧做了每秒一次的限流（`MolangSyncSender`）。

## 输入类函数：键码是 GLFW 的

wiki 的 molang 参考表写得很明确：`ysm.keyboard(keycode1, keycode2, ...)` / `ysm.mouse(keycode)`
的键码是 **GLFW** 的（表里直接链到 `glfw.org/docs/latest/group__keys.html`），而且
`ysm.keyboard` **支持多个参数**、"只要有一个按键按下则返回 true"。

1.7.10 用的是 LWJGL2，两套键码完全不同，而且 LWJGL2 的按键数组**只有 256 项**：

| | GLFW（模型里写的） | LWJGL2（1.7.10） |
| --- | --- | --- |
| 上/下/左/右 | 265 / 264 / 263 / 262 | 200 / 208 / 203 / 205 |
| Tab / Esc / Enter | 258 / 256 / 257 | 15 / 1 / 28 |
| 字母 E / A | 69 / 65（ASCII） | 18 / 30（扫描码） |

所以模型里的 `ysm.keyboard(265)` 在 LWJGL2 上会**越界**，`Keyboard.isKeyDown(265)` 抛
`ArrayIndexOutOfBoundsException`——外层那个 `catch` 把它当成"没按下"，按键驱动的模型
（keyframe/timeline 里做小游戏、`.molang` 脚本里用 Tab 鸣笛）就全都收不到输入。
转换表在 `com.fox.ysmu.compat.KeyboardCompat`（硬编码的 GLFW→LWJGL2 表，`KeyboardCompatTest`
钉住），关键帧路径（`YsmKeyboardFunction`/`YsmMouseFunction`）与控制器条件路径
（`OpenYsmControllerConditionEvaluator` 的 `ysm.keyboard`/`ysm.mouse` 分支）都走它。

排查输入问题时：

- `DebugController=true` 下每次按下/松开翻转会打一条
  `[YSMU-KEY] ysm.keyboard(265) -> LWJGL 200 (UP) = 1`（每个键码只在翻转时打，不会刷屏）；
- 未知键码打一次 `[YSMU-KEY] … no 1.7.10 (LWJGL2) mapping for code N — expected a GLFW code`；
- 手按着键执行 `/ysm debug eval ysm.keyboard(265)` 应当返回 1（该命令走的是关键帧解析器）。

## 排查方法

- `DebugController = true`：
  - `[YSMU-MOLANG-SCRIPT] <模型> <事件> -> N script(s): [名字…]`：事件订阅真的被触发（每个 模型×事件 一条）；
  - `[YSMU-MOLANG] <模型> state '…' -> '…' (conditional branch)`：条件映射命中；
  - `[YSMU-CTRL-ANIM] <控制器> state='…' animations=[…]`：状态实际播了什么。
- 单个表达式用 `/ysm debug eval <表达式>`（`query.*`/`ysm.*` 都能查，见 `analysis/debug-overlay.md`）。
- 脚本/时间轴里的变量写没写进去，用 debug overlay 看 `v.*` 的来源与数值。
