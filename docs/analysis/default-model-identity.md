# 默认模型的身份：两种角色、编码规则与回收钉住

"默认模型"在这个 mod 里有**两个不同的东西**，它们指向不同的资源、寿命也不同。
把它们混为一谈会产生一类很难查的故障：模型被回收后**再也恢复不了**，玩家随之永久隐形。

| 角色 | 是什么 | id | 来源 | 有没有加密客户端缓存 |
| --- | --- | --- | --- | --- |
| **内置兜底模型** | `config/ysmu/builtin/default`，渲染层"自己的 geo 拿不到"时的回退 | `ysmu:default/main`（字面量） | 启动时从磁盘直接读（`ClientModelManager.loadDefaultModel()`） | **没有** —— 回收后无法重建 |
| **配置里的默认模型** | `Config.DefaultModelId`，玩家进游戏时选的模型 | **编码后**的内部 id | 服务端扫描 → 同步索引 → 客户端缓存 | 有（走同步） |

两个角色都要被"钉住"（不被空闲回收），但**认领方式不同**：

- 内置兜底模型**永远**要认领：它没有缓存条目，被 `AssetManager.tick()` 回收后
  几何/动画再也读不回来，GeckoLib 找不到 geo 会抛 `GeoModelException`，玩家则一直隐形。
- 配置里的默认模型按**编码后的内部 id**认领：它多半来自同步、有缓存，回收后还能懒加载回来，
  但被回收期间仍会闪一下（而且它经常是"其余模型回退动画/骨骼"的对象）。

## 名字 → 内部 id

`ModelIdUtil.getInternalModelId()`：名字匹配 `[a-z0-9._-]+` 时原样使用，否则
编码成 `_name_` + UTF-8 hex。因此**含空格、大写字母、非 ASCII 字符的名字一定会被编码**。

> 目录名本身是模型 id 的一部分：`custom/<包>/<模型>` 的相对路径会被整体编码，
> 所以 `wine_fox/15_kluonoa` 这类 id 从来都不是字面量。

比较"这是不是默认模型"时**必须用编码后的 id**：

| 位置 | 正确写法 | 写错的后果 |
| --- | --- | --- |
| `ClientModelManager.isDefaultModelBundle()` | `Config.getDefaultModelInternalId().equals(path)` | 名字带空格时永不匹配 → 两个默认模型都没人钉 → 30s 空闲后被回收 → 内置兜底永久隐形 |
| `OpenYsmModelSyncServer` 的同步索引排序 | 同上 | 默认模型不再排在索引最前，"其余模型回退到默认模型动画/骨骼"的前提被打破 |

用**原始配置字符串**去比注册表里的 id 是这里最容易犯的错：配置值和注册表值看起来
"是同一个名字"，但只有名字是安全名时它们才字面相等。正确写法参照
`ExtendedModelInfo`（玩家初始模型 id）——它用的是 `ModelIdUtil.getInternalModelId(Config.DEFAULT_MODEL_ID)`。

## 渲染回退指向谁

`CustomPlayerEntity.getMainModel()` 的最终回退是**内置兜底模型**
（`ysmu:default/main`），不是配置里的默认模型：前者保证在磁盘上、永远加载得到；
后者可能根本不是本地文件夹模型（它是同步来的），回退到它未必有 geo。

配置里的默认模型只决定"玩家初始用哪个模型"（`ExtendedModelInfo`），不决定回退。
内置兜底贴图固定为 `default/default.png`；`DefaultModelTexture` 属于玩家初始模型，
不能拿它的文件名拼接内置兜底的目录。

## 应用代次闸门

客户端每轮同步/重载换一个**应用代次**（`APPLY_GENERATION`）。已解析的模型带着
它开始解析时的代次，主线程发布前必须逐个核对（`isApplyGenerationReady`）。
模型发布屏障与握手有效性是两层独立的约束：

1. **当前代次的发布屏障**由主线程清理路径推进；清理失败必须有明确日志，不能无声地
   把有效的 apply 管线永远留在关闭状态。已失效的旧清理不得推进新代次的
   `clearedApplyGeneration`。
2. **有效握手与作废握手必须区分**：仍属于当前连接、当前同步的握手需要正常完成，
   否则回复版本号或发出同步请求的后续流程不会执行；被新同步或断线作废的旧握手
   则不得继续向新连接发包。future 完成与否本身不能代替连接及同步身份的校验。

`isApplyGenerationReady` 只控制模型能否发布到注册表，不能为旧握手继续发包背书。
上述生命周期约束也不代表握手 continuation 的全部失效时序已经由 apply gate 覆盖。

相关：`模型更新与运行中清理缓存`（发布顺序与代次）、`ysm 同步协议`。
