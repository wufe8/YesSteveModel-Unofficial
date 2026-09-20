# 几何/动画/贴图的动态卸载与重加载 —— 派生状态必须遵守的不变式

这份笔记是**几何提交（渲染）优化前的冲突检查**：项目里已经有一整套"按空闲/容量动态释放
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

## 2. 派生状态必须遵守的三条不变式

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

## 3. 本次改动与这套机制的交叉检查

- `perf(anim): 作用域变量同步改成增量`（D2）：纯数据结构，无交叉。
- `perf(anim): 骨骼登记按模型缓存`（D5）：`AnimationProcessor.registrations` 是
  `WeakHashMap<GeoModel, ModelRegistration>`。**安全**，理由是实证的：`GeoBone` 不反向引用
  `GeoModel`（只有 `parent`/`childBones`/`childCubes`），所以"值"不会把"键"钉住；模型被淘汰后
  键不可达 → 条目消失。重加载解析出的是**新的 GeoModel 对象** → 身份缓存必然未命中 →
  骨骼表重建，`GeoModelProvider.release()` 里"下一次 `getModel()` 会重建骨骼表"的注释依然成立。
- `perf(anim): 控制器复用处理器的名字索引`（D3）：控制器每次 `process()` 都从处理器换成
  **当前模型**的索引，换模型/重加载后不会继续用旧骨骼。
- **发现两处"释放不彻底"**（都不随模型数增长，量级 ≈ 1 个模型，但值得修）：
  - 既有：`AnimatedGeoModel.currentModel` 只被赋新值、从不置空 → 最后渲染过的那个 GeoModel
    即使被 `DROP_HEAP` 淘汰，仍被强引用，它的几何堆并没有真正释放。
  - D5 引入的 `currentRegistration`（以及控制器的 `boneNameToBone`）钉住的是**同一个对象**的
    骨骼表，所以增量是 0；但它同样应该随释放一起丢掉。
  - **已修**：`GeoModelProvider.release()` 已经拿到 `GeoModel` 实例，现在会在那里调用
    `CustomPlayerModel.onGeoModelReleased(geo)` → `AnimationProcessor.forgetModel(geo)`
    （丢登记、必要时换空的当前登记）+ 清 `AnimatedGeoModel.currentModel`。控制器的
    `boneNameToBone` 会在下一次 `process()` 换成新模型的索引，所以那份几何在下一帧就不可达了。
    单测：`AnimationProcessorRegistrationCacheTest#aReleasedModelDropsItsRegistration`。

## 4. 几何优化（D4）的约束清单

1. per-model 顶点缓冲/VBO：键用 `GeoModel` 身份（弱键），并在 `release()` 里删除 GL 对象。
2. 显存要记账：扩展一个"几何显存字节"计数器，纳入同一套预算/上限，否则预算语义被绕过。
3. 缓存要自带 LRU/上限（例如只保留当前可见的十几个预览），不能依赖 evict。
4. 不要缓存跨模型共享的中间结果，除非能证明重加载后仍有效。
5. 验收：单元测试只能覆盖预算/淘汰策略；实机检查"长时间翻页后堆与显存不涨"，
   必要时加一个按模型/按类别的占用读数（现有 F3 只报 HUD/预览 FBO 的频率与耗时）。

## 5. 相关代码位置

- `client/asset/{AssetCache,AssetManager,AssetHandle,ReleaseMode}.java`、`asset/provider/{GeoModelProvider,AnimationProvider}.java`
- `client/ClientModelManager.java`：`unloadUnusedCaches()`、`unloadIdleTextures()`、`isIdleEvictionSuppressed()`、`TEXTURE_*` 常量、`ensureTexturesLoaded()`、`restoreTextureData()`
- `client/model/CustomPlayerModel.java`：`INJECTED_LOCATIONS` / `clearInjectedCache()` / `injectVirtualBones()`
- `software/bernie/geckolib3/{model/AnimatedGeoModel,core/processor/AnimationProcessor}.java`
- `docs/analysis/performance-profiling.md`（性能方法论与 FBO 预览缓存）
