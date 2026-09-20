# `ysm.sync(...)` 到底影响什么

YSM-wiki：`molang/script`「主动同步」。一句话：**它不改任何变量、不播任何东西，
它只是"让所有客户端用同一组参数去跑发起者那个模型的 `sync` 事件脚本"** ——
具体效果完全由模型自己的 `@sync` 订阅脚本决定。

## 一条 sync 的完整链路（YSMU 实现）

1. **模型脚本调用**：`ysm.sync(0,1)`。
   - 关键帧/时间轴路径：mclib 的 `YsmSyncFunction`；
   - `.molang` 脚本与控制器条件路径：`OpenYsmControllerExpressionEvaluator.Context.functionValue`
     → 两条都汇到 `MolangSyncSender.request`。
   - wiki 约束：只支持数值参数、最多 16 个（YSMU 超出是**截断**而不是像 wiki 说的解析失败），
     调用后立刻返回（数值形态返回 0），不等事件触发。
2. **客户端上行**：`C2SMolangSync`（带**发起者当前的主模型 id** + int 参数）。
   服务端并不知道客户端此刻渲染的是哪个模型，所以模型 id 必须由客户端带上。
3. **服务端广播**：`C2SMolangSync.Handler` → 通过 `EntityTracker.func_151248_b`
   发给**能看到发起者的玩家 + 发起者自己**（与上游 OpenYSM 的
   `sendToTrackingEntityAndSelf` 一致），包体是 `S2CMolangSync`（发起者 UUID + 模型 id + 参数）。
   原来发给全服所有玩家 —— 无关客户端也要跑一遍该模型的 `@sync` 脚本，人多时纯浪费，
   而 `@sync` 的效果本来就只跟"渲染该玩家"有关。
4. **接收端**：`S2CMolangSync.Handler` 把工作交回客户端主线程 →
   `MolangSyncClient.handle`：
   - 按 UUID 在本地世界找到发起者实体（**找不到就静默跳过**，比如人还没进视野/已离开）；
   - 本地**没加载**该模型、或该模型没有 `.molang` 脚本 → 跳过；
   - 否则 `OpenYsmScriptRuntime.runSyncScripts(player, modelId, args)`。
5. **脚本执行**：跑该模型所有订阅 `sync` 的函数（`functions/*@sync.molang`），
   `args[]` 就是传过来的参数。`@sync` 在**渲染帧之外**触发，所以
   `runSyncScripts` 会临时挂上 **(发起者玩家, 该模型)** 的变量作用域
   （`MolangPhysicsRuntime.runWithVariableScope`）：脚本里的 `v.*` 写回落进
   "下一帧 `begin()` 会读到的那份 ScopeState"，既跨帧可见，也不会污染当前正在渲染的其他玩家。

## 因此它"影响"的东西

- **只有模型自己写的效果**：典型写法是用参数表示一个开关/事件，在 `@sync` 里
  `v.roaming.xxx = args[1]`，再由 `@player_update` 或控制器条件去读它。
  本仓参考的某个车辆模型就是这样传"鸣笛按下/松开"：`@sync` 写
  `v.roaming.horn_checker`，`@player_update` 里按它 `ysm.play_sound('horn_long',…)` /
  `stop_sound('horn_long')`。因为是广播，**别的玩家看你**也会跟着响。
- **会写进 `v.roaming.*` 时**：`v.roaming` 有自己的存储与同步，
  所以这个状态还会跟着存档/维度切换保留（wiki：`v.roaming` 可恢复）。
- **不影响的**：它不做变量同步（只传那 16 个整数）、不直接播声音/粒子/换动画、
  也不排队重放 —— 漏掉就是漏掉。

## 限流（YSMU 特有，上游没有）

两边原本都是"每秒 1 次、超出直接丢"（`MolangSyncSender.MAX_PER_SECOND` 与
`C2SMolangSync.MIN_BROADCAST_INTERVAL_MS`）。问题是 `ysm.sync` 有两种用途，
被一视同仁地压掉：

| 用途 | 形态 | 每秒 1 次丢包的后果 |
| --- | --- | --- |
| 重复 | 模型把它写在每帧脚本里，参数不变 | 正是限流要压的（合理） |
| 状态变化 | 参数就是开关值（按下/松开） | **可见的行为差异**：松手要等到下一秒才生效，长鸣笛一直响；上游下一个 tick 就停 |

现在两边都改成**按参数区分**：

- 参数与上次**相同** → 同一状态的重复 → 仍按每秒 1 次压掉，而且**不占**硬上限
  （模型每帧重试不会把额度吃光）；
- 参数**变了** → 状态切换 → 立刻放行；
- 两种都受每秒硬上限约束（`MAX_PER_SECOND` / `MAX_BROADCASTS_PER_SECOND` = 4）。
  服务端这一层是防"改过的客户端"的放大攻击，必须有真上限：一个不停换参数的恶意客户端
  最多把广播放大到 4 次/秒（**上游是无上限**）。
  要更严就把这个常量调小（调到 1 会让"按下+松开"里的松开也进不去，等于回到旧行为）。

判定仍然必须在服务端做（客户端那层可以被绕过），并且用
`ConcurrentHashMap.compute` 把"读窗口 + 判断 + 写回"串行化（早先的 get/put 组合有竞态）。
