# 常驻变量（`v.roaming.*`）的作用域与"裸名别名"

## 语义：两个命名空间

YSM wiki（`/wiki/molang/var/`）把变量分成四类，其中两类容易混：

| 写法 | 含义 | 生命周期 |
| --- | --- | --- |
| `variable.x` / `v.x` | 实体变量 | 退出游戏/切维度会重置 |
| `variable.roaming.x` / `v.roaming.x` | **常驻变量** | 不随退出/切维度重置 |

**`v.roaming.x` 与 `v.x` 是两个不同的变量**，不是同一个东西的两种写法。轮盘里的
单选/勾选/滑条（`ysm.json` 的 `config_forms`，字段 `value` 通常是 `v.roaming.<name>`）
写的是**常驻**那个。

## YSMU 的注入：为什么会有"裸名别名"

`OpenYsmPlayerControllerRuntime.injectRoamingVar()` 把常驻变量的值注入**两个** map：

- 关键帧 Molang 的作用域（`MolangPhysicsRuntime` 的 `ScopeState`，`keyPrefix = "v."`）；
- 控制器条件的运行时状态（`RuntimeState`，`keyPrefix = ""`）。

除了 `roaming.x` 本体，它还会写**剥掉 `roaming.` 前缀的裸名**（`v.x` / `x`）。这不是多余：
确实有模型只在关键帧里写裸名 —— 第一方 wine_fox 的 `14_momo` 就是
`"scale": "1-v.smallfox_size"`，而它的滑块声明是 `v.roaming.smallfox_size`。
删掉别名会直接让这类写法读到自己的（未赋值的）变量。

## 别名会覆盖模型自己的变量（已修）

危险的是模型**同时**用两个命名空间，而名字相同：

```molang
v.bq_qx2 = v.roaming.bq_qx2 ? v.roaming.bq_qx2-1 : <自动分支>
```

这里 `v.bq_qx2` 是模型自己算出来的结果，`v.roaming.bq_qx2` 是轮盘选项。别名注入会往
`v.bq_qx2` 写**选项原值**，于是同一个键被两个来源轮流写：注入值 2 / 模型计算值 1 交替。

实测特征（用户提供）：

- 值在 `0↔1`（选"隐藏"，roaming=1）或 `1↔2`（选"显示"，roaming=2）之间震荡；
  选"默认"（roaming=0）时两个来源恰好都是 0，看不出来。
- **只在 `evals/frame == 1` 时肉眼可见**。一帧有两个 pass（第三人称：世界 + HUD 纸娃娃）时，
  总有一次 pass 会在注入之后把值收敛回模型自己的计算值；一帧只有一个 pass（第一人称只有
  被 FBO 限频的纸娃娃）时，没烘焙的那些帧留在注入值上，于是按烘焙节奏闪。
  `evals/frame` 可以从 F3 明细（Shift 展开）读。

表现是部件/表情按帧闪烁（透明度不一致）。

## 修法：自校准，而不是删别名

`injectRoamingVar()` 里的规则：

1. 维护 `MODEL_OWNED_VARS`（每模型一组"模型自己会写的裸名"）。
2. 每次注入裸名别名前，如果目标 map 里该键的当前值**不是**我们上一轮注入的值，
   就说明模型自己改写过它 ⇒ 记进 `MODEL_OWNED_VARS`，从此该模型不再注入这个别名
   （`LAST_INJECTED_ALIAS` 记录上一轮注入值，见 `RoamingBareAliasTest`）。
3. 只读裸名的模型（`14_momo` 那种）永远学不到"被改写过"，别名继续生效，滑块改值照常跟随。
4. 控制器 map 的裸名跳过沿用 Molang 作用域学到的结论（模型的写发生在关键帧里）。

选择自校准而不是"加载时扫一遍脚本"：脚本里的赋值目标要额外解析一遍所有关键帧，而运行时
这个信号是免费的、且对任何写法的模型都成立。

**已知限制**：

- 自校准有一轮延迟：第一轮仍会注入一次（该轮模型通常紧接着就覆盖它，且这一轮多在模型加载）。
- 只有当"模型写入的值 ≠ 我们注入的值"时才会学到；如果两者恰好相等就先不学（此时注入
  没有副作用，等它们不等时会立刻学到）。
- 名字里没有 `roaming.` 前缀的常驻变量本来就没有别名，不受影响。

## 验证

- `RoamingBareAliasTest`（4 条）：模型自己写裸名时别名让位、只读裸名时别名继续生效、
  无前缀名字不产生别名、控制器 map 同样跳过被拥有的裸名。
- 客户端验收：把表情/部件按"隐藏/显示"切换，F3（Shift）看 `evals/frame` 为 1 和 2 两种情形，
  部件都不应再按帧闪；只读裸名的第一方模型（`wine_fox` 的对应缩放）应仍随滑条变化。

## 隔离现状（审计后收紧）

同一份数据有两条读取路径，**口径必须一致**。审计时的实际状况：

| 方向 | 路径 | 隔离 |
| --- | --- | --- |
| 写 | 关键帧/脚本/时间轴的 `v.*` → `MolangPhysicsRuntime.setVariable` → scope（键 = 玩家×模型） | ✅ |
| 写 | 常驻写回 → `noteRoamingWrite(ctx.modelId, …)` → `PENDING_ROAMING` + 按模型标记 | ✅ |
| 写 | 控制器 `onEntry/onExit` 写 `v.roaming.*` → 按 `currentModelId(context)` 标记 | ✅ |
| 写 | 时间轴写全局 `VARIABLES` → `noteGlobalVarOwner` 记来源 | ✅ |
| 读 | 关键帧 `ScopedMolangVariable` → scope 未命中 → `getGlobalScopedValue`（按来源过滤） | ✅ |
| 读 | 控制器 `localVariableValue` → `RuntimeState`（按模型注入） | ✅ |
| 读 | ↳ 两级都未命中 → `sharedVariableValue` 读**全局** `PENDING_ROAMING` | ❌ 曾无模型判断 |
| 读 | 全局 `v.*` 无来源记录（帧外写入） | ❌ 曾"无记录 = 放行" |

两处 ❌ 的后果是一样的：模型 A 在轮盘里设的值 / 模型初始化时写的值，会被模型 B 在读渲染时读到。
调试叠加层的 `@模型` 列**看不出来** —— 它只查时间轴全局写的来源（`GLOBAL_VAR_OWNER`），对
`PENDING_ROAMING` 不区分来源，所以那一列对这类值是近似的。

收紧：

1. 控制器回退加模型判定：`isRoamingNameForModel(modelId, name)` = 声明过（`config_forms`）
   ∪ 本模型显式设置过 ∪ 全局轮盘变量（`lock_wheel`/`wheel_anim`）；`x` 与 `roaming.x` 两种
   写法都认。`x` 与 `roaming.x` 在调用点本来就是两个不同的实参，所以"两边都有定义时各读各的"
   由调用点保证，回退只需补模型判定。
2. `markRoamingExplicit(null, …)` 不再退化成全局标记（只对全局轮盘名字生效）：`isRoamingExplicit`
   先查全局集合，退化会让一个模型的设置对所有模型生效。
3. 帧外写进全局 `VARIABLES` 的 `v.*` 现在会记一个"无归属"哨兵，读取侧按
   `isGlobalVarReadable(owner, currentModel)` 判断。**注意判定顺序**：先看"有没有模型上下文"，
   没有就一律放行 —— 帧外写 → 帧外读的往返（模型初始化、指令、Molang 单测）必须保留。
   第一版把哨兵判断放在前面，4 个 Molang 解析/嵌套赋值测试当场变红。

验证：`RoamingIsolationTest` 6 条。客户端配方：模型 A 声明 `roaming.x` 并在轮盘里设成 2；
模型 B 不声明，在**控制器条件**里读 `v.x`，应收敛到 0 而不是 2。

## 另一条跨模型串值通道：待播动画（与常驻变量无关）

排查"切模型后自动播了一次变身"时容易先怀疑常驻变量，但那条路是隔离的（见上表）。真正的
通道是**外部动画的触发记录**：

| 记录 | 存哪 | 键 | 什么时候清 |
| --- | --- | --- | --- |
| `play_animation` + `animation` | `ExtendedModelInfo`（按玩家，随 NBT 广播） | 动画名 | 客户端：动画自然播完（**不同步给服务端**）；服务端：只在收到移动键的 `.stop` 时 |
| `lock_wheel` + `wheel_anim` + `currentWheelAnim` | `PENDING_ROAMING`（全局扁平表）+ `AnimationManager` 静态字段 | 动画名 | 轮盘：把锁关掉时 |

两条都是"按名字"记的，而名字只在触发它的那个模型文件里有意义 ⇒ 换模型必须作废，否则新模型
会按同名找到**另一条完全不相干的动画**。实测症状：在 A 按过轮盘"变身"（`extra0`：time 轴
`1.2083: v.roaming.a=1-v.roaming.b;`，模型侧只由这条时间轴写形态状态）后切到 B，B 自己播
一遍"变身"，顺带改掉 B 的 `v.roaming.a/b` —— 看起来像常驻变量串值，实际是**动画被重放**，
状态是被重放的动画改的。

修法：`ExtendedModelInfo.setModelAndTexture()`（客户端 GUI 乐观更新与服务端
`SetModelAndTexture` 共用的唯一赋值点）在模型真的变（按 `getModelIdFromSubId` 归一后比较）
时 `stopAnimation()`；回归测试 `ExtendedModelInfoModelSwitchTest`。只靠
`CustomPlayerRenderer` 里"模型变了就 `stopAnimation()`"的守卫不够：`ModelButton.doPress()`
会**乐观地**先把 EEP 的 modelId 改掉，守卫那一帧在服务端广播（仍带着旧模型的
`play_animation=true`）到达之前就用掉了，广播到达时 modelId 已经相同。

`lock_wheel` 那条通道仍然存在（轮盘锁是用户的显式选择，暂不在换模型时作废）。两条通道的
触发现场都可以从 `[YSMU-CAP] <source> animation '<名字>' starts on model=<id>` 读出来
（`DebugController` 门控，按上升沿去重，所以重放会再报一次）。
