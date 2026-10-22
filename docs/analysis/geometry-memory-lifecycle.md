# 几何/动画/贴图的动态卸载与重加载 —— 派生状态必须遵守的不变式

客户端使用一套"按空闲/容量动态释放
堆内存与显存、按需后台重加载"的机制，任何新的派生缓存（顶点缓冲、显示列表、烘焙结果、
按模型的身份缓存）都必须与它对齐，否则会出现三类症状：**释放不干净（内存/显存涨）**、
**重加载后用到旧几何（错位/闪一下）**、**绕过用户设定的显存预算**。

## 1. 现有机制

| 环节 | 位置 | 说明 |
| --- | --- | --- |
| 触发 | `ClientEventHandler` 每 1200 tick（60 s）→ `ClientModelManager.unloadUnusedCaches()` → `AssetManager.tick()` | 客户端 tick，即渲染线程 |
| 几何 / 动画 | `AssetCache<ResourceLocation, GeoModel>` / `<…, AnimationFile>`，`GEO.evict(now, idleMs, maxWeight)`、`ANIM.evict(…)` | 先按空闲超时释放，再按容量上限 LRU 释放 |
| 释放深度 | `ReleaseMode.DROP_HEAP`（几何/动画）/ `GPU_ONLY`（纹理） | 几何：从 `GeckoLibCache` 移除 + `CustomPlayerModel.clearInjectedCache(id)`；动画：同样 + `clearInjectedCache` |
| 重加载 | `AssetHandle.get()`（`AssetManager.geo()/anim()`）在 `ABSENT` 时触发后台解密+解析，主线程 `apply()` 写回 `GeckoLibCache` | `AssetCache.generation` 用来丢弃"清除之后才回来的"过期结果 |
| 贴图显存 | `unloadIdleTextures(now)`：整模型 30 s 未用 → 释放 GPU 副本（`freeGlTexture`）；`TEXTURE_RELEASE_BYTES_ON_IDLE` 才连堆字节一起放（之后靠 `restoreTextureData` 重解密） | 字节默认常驻堆，所以重上传不会白模 |
| 显存预算 | `Config.TEXTURE_VRAM_BUDGET_MB` + `TEXTURE_GPU_BYTES` + 5 s `TEXTURE_PROTECT_MS` 保护窗，超限淘汰到 75% | **只统计 YSM 模型贴图**，别的 GPU 资源不在账上 |
| 常驻 | `default` 兜底模型：`GEO.touchIf/ANIM.touchIf` + `unloadIdleTextures` 跳过 | 它没有加密客户端缓存可恢复，释放后必然白模/崩溃 |
| 抑制窗口 | `isIdleEvictionSuppressed()`：预览 GUI 打开时 **或** 关闭后 `GUI_POST_CLOSE_GRACE_SECONDS` 内，全部回收停止 | 避免"关一下再开"导致的反复重加载 |

## 2. 派生状态必须遵守的四条不变式

1. **生命周期要跟着资源走**。派生缓存只有两种合法挂法：
   - 挂在"随资源一起被替换/移除"的对象上（例如 `ControllerSet.namedParallelSlotCache`、
     `routeCache`：注册时整个 set 被换掉、`clear()` 时移除，所以不可能读到过期结果）；
   - 或者在 provider 的 `release()` 里显式清（例如 `clearInjectedCache`）。
   以 `GeoModel` 身份为键的缓存**必须是弱键**（`WeakHashMap`）：GeoModel 会被淘汰，
   强引用会让 `DROP_HEAP` 名存实亡。
2. **GPU 资源要在渲染线程释放，并且要么计入预算、要么自带上限**。evict 跑在客户端
   tick（渲染线程）上，所以 `release()` 里删 GL 对象是安全的；但现有显存账只统计贴图，
   新增的几何显存必须一起记，否则"用户设定的 `TEXTURE_VRAM_BUDGET_MB`"会被绕过。
3. **不能指望 evict 兜底浏览页**。预览 GUI 打开期间回收是被抑制的（设计如此），
   所以任何"每个已加载模型一份"的堆/显存派生数据都会随浏览的模型数线性增长。
4. **重加载的解析结果必须与首次同步的一致**。同一条资源有两条解析路径：首次同步
   （`ClientModelManager.parseAnimationsToBundle`，eager）与懒加载重载
   （`parseAnimationFromCache`）。两条路径各写一份过滤规则就会漂移，而漂移的后果不一定是
   "少一条动画"——**同名动画会被后解析的那份覆盖**。实测：弹射物的
   `arrow.animation.json` 声明了 `parallel0`（12 根骨）/ `parallel1`（1 根骨），而玩家的
   `parallel1` 是"人形 / 兽形"两根根骨的缩放所在；懒加载路径少了"弹射物 key 不并进玩家
   文件"这条过滤，重载后玩家的 `AllBody.scale` / `FOX.scale` 整条被替换掉 ⇒ 两种形态同时
   显示。症状还带一个误导性的时间结构：`/ysm reload` 把**当前**模型换回 eager 解析，于是
   "当前模型好了、上一个坏了"；`warmModel` 在同步登记缓存路径**之前**给进存档时的模型创建
   了 ANIM 条目，所以连它也走懒加载重载 ⇒ 删掉 `config/ysmu/cache` 后所有模型一起坏。
   两条路径现在共用 `isPlayerAnimationSourceKey()`；回归测试
   `PlayerAnimationMergeFilterTest`。

## 3. 身份缓存与释放回调

`AnimationProcessor.registrations` 使用 `WeakHashMap<GeoModel, ModelRegistration>`。弱键只有在值不反向引用键时才能释放：`GeoBone` 持有父子骨骼和 cubes，不反向持有 `GeoModel`。重加载产生新的几何对象，必须重新建立骨骼索引；控制器每次 `process()` 获取当前处理器的索引，不能沿用旧模型的表。

`GeoModelProvider.release()` 把释放的对象交给 `CustomPlayerModel.onGeoModelReleased(geo)`，通知处理器丢弃登记，并按对象身份清理 provider、renderer 与头部姿态引用。释放旧对象不能误清已经切换到的新对象。全量会话清理也必须清 renderer 的玩家、模型和贴图引用，不能仅移除注册表条目。

回归入口：`AnimationProcessorRegistrationCacheTest`、`CustomPlayerRendererLifecycleTest`；后者覆盖实际释放回调与完整 runtime clear。

## 4. 渲染优化的资源边界

- cube 局部变换合成与标量顶点变换属于 CPU 运算，不增加 GPU 资源；数值等价性由 `MatrixStackCubeTransformTest` 检查。
- 直接访问 Tessellator 原始缓冲必须考虑 Angelica 等模组的格式布局；通过 API 提交和直接写内部布局不能未经验证就互换。
- 引入 per-model VBO 等缓存时，必须遵守前述弱引用/显式释放、预算和容量约束，不能靠空闲淘汰掩盖无限增长。
- 单元测试覆盖索引、预算与释放策略；堆/显存和真实重加载画面仍需在相同场景实机比较。

## 5. 相关代码位置

- `client/asset/{AssetCache,AssetManager,AssetHandle,ReleaseMode}.java`、`asset/provider/{GeoModelProvider,AnimationProvider}.java`
- `client/ClientModelManager.java`：`unloadUnusedCaches()`、`unloadIdleTextures()`、`isIdleEvictionSuppressed()`、`TEXTURE_*` 常量、`ensureTexturesLoaded()`、`restoreTextureData()`
- `client/model/CustomPlayerModel.java`：`INJECTED_LOCATIONS` / `clearInjectedCache()` / `injectVirtualBones()`
- `software/bernie/geckolib3/{model/AnimatedGeoModel,core/processor/AnimationProcessor}.java`
- `docs/analysis/performance-profiling.md`（性能方法论与 FBO 预览缓存）
