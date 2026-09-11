# 性能剖析：方法论与通用结论

提炼自 `local/analysis/performance-analysis.md`。原文的具体数值与模型强相关（逐按钮渲染数、
网格尺寸等），此处只保留**可复用的方法论**与**不随模型变化的结构性结论**。其中"离屏 FBO
预览缓存已落地"这一条同时来自 `local/analysis/UI实现分析_at-f8e4265.txt`（该文件是 YSMU 与
参考实现的 UI 对比，未在别处记录这个机制），故一并注明。

## 1.7.10 可用的剖析工具

| 工具 | 适配 / 用法 |
| --- | --- |
| Spark | 原生支持 1.7.10 Forge；`/spark profiler` 采样、`/spark health`、`/spark heap`。首选快速定位。 |
| WarmRoast | 独立 Java 代理附加进程；Web 界面 + MCP 映射反混淆。已不再积极维护。 |
| VisualVM | JDK 自带 `jvisualvm` 附加进程，CPU 采样 / 内存 / 线程转储；只能看 CPU 侧且方法名混淆。 |
| `mcProfiler` | 在自有代码插 `Minecraft.getMinecraft().mcProfiler.startSection/endSection`，配合 F3 饼图看子系统占比。 |
| GTNH 生态 | Angelica 的 F3 屏、Hodgepodge 的 `-Dhodgepodge.logModTimes/logEventTimes`、Opis 的服务端时序。 |

推荐顺序：先 Spark 采样定位热点 → `mcProfiler` 细粒度插桩 → 内存问题再看堆转储。
采样/插桩前先关掉本模组的 `DEBUG_*` 开关，避免探针自身污染结论。

## 单实体渲染成本模型（与具体模型无关）

一帧一次实体渲染的链路固定：

    CustomPlayerRenderer.doRender
      → GeoReplacedEntityRenderer.doRender（构造 AnimationEvent）
      → AnimatedGeoModel.setLivingAnimations
      → AnimationProcessor.tickAnimation（遍历整套控制器）
      → IGeoRenderer.render → renderRecursively（每骨骼矩阵、每顶点/每面变换）

因此单实体每帧成本大致按 **骨骼数 × 顶点数**（几何）与 **控制器数 × 谓词/表达式数**
（动画）增长；模型越复杂两条曲线都越陡。GUI 的每次预览、HUD 纸娃娃各算一次实体渲染。

## 通用发现

- **相同渲染状态会被整体跳过**：`AnimationProcessor.tickAnimation` 用
  `AnimationRenderState.from(seekTime, event)` 去重，seekTime 与事件相同就直接 return。
  用采样器测"暂停/静止帧"的动画开销会严重低估；要测动画成本必须让实体动起来。
- **GL 状态抖动是独立成本**：GUI 预览逐按钮设置/恢复 `GL_COLOR_MATERIAL`、
  `GL_DEPTH_TEST`、`GL_SCISSOR_TEST` 与纹理矩阵模式，切换次数正比于可见按钮数。
- **GUI 预览是放大器**：模型/纹理选择界面每个按钮都跑一次完整实体渲染。已落地的修法是
  离屏 FBO 缓存（`util/FboCache`）：
  - `ModelButton` / `TextureButton` 把预览渲染进 `Framebuffer`，之后只画缓存纹理；
  - `Config.GUI_MODEL_PREVIEW_REFRESH` 控制刷新间隔：`0` = 只在脏/交互时重绘，`1..4` =
    每 N 帧强制重绘（越大越平滑、GPU 负载越高）；
  - HUD 纸娃娃另有 `Config.GUI_HUD_PREVIEW_CACHE` 开关；
  - 模型同步进行中不重建 FBO 缩略图（`ClientModelManager.SYNC_IN_PROGRESS`），先显示旧/空白。
- **GUI 预览实体走专用早退分支**：预览实体 `player == null`，`predicateMain` 直接返回
  `previewAnimation` 或 `gui` / `idle`，`predicateArmor` 立即 `STOP`。所以"GUI 里盔甲控制器
  空转"不是当前代码的问题；固定开销是每个预览实体仍注册了整套控制器。
- **纹理解码不在每帧**：`ImageIO.read` / `DynamicTexture` 注册集中在界面构建与翻页，表现为
  帧尖峰而非持续负载；翻页重建按钮会重新分配 `DynamicTexture`。

## 验证方法

- `/spark profiler` 采 30 s，看 `CustomPlayerRenderer` / `IGeoRenderer.renderRecursively`
  及其调用者的占比。
- F3 饼图或 `mcProfiler` 段：区分负载来自原版渲染还是模型动画。
- 对比实验：同一场景切换 `GUI_MODEL_PREVIEW_REFRESH` 的 0 与 4 观察帧率差；开/关
  `GUI_HUD_PREVIEW_CACHE` 对比 HUD 负载。

## 已知限制 / 未验证

- 1.7.10 是固定管线（GL 2.1），没有可用的现代 GPU profiler；只能从 CPU 侧观察 GL 调用耗时，
  GPU 内部分时不可得（可尝试 Intel GPA / RenderDoc 抓帧）。
- 原文的帧数/百分位数字来自特定模型与机器，**不**作为基线；换模型、换硬件需重测。
- 原文列出的其它优化方向（合批、骨骼矩阵缓存、Molang 预编译）只是候选，未逐项实现或实测；
  已确认实现的只有 FBO 预览缓存一处。
