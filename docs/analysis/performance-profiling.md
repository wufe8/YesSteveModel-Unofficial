# 性能剖析：方法论与通用结论

提炼自作者本地的性能排查笔记（未随仓库提供），并在一次 spark 采样后修订。原始记录的具体
数值与模型强相关（逐按钮渲染数、网格尺寸等），此处只保留**可复用的方法论**与**不随模型
变化的结构性结论**；"离屏 FBO 预览缓存已落地"这一条来自作者本地的一份 YSMU 与参考实现
UI 对比笔记，未在别处记录，故一并保留在此。

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
- **预览界面别让它暂停游戏**：`ExtraPlayerConfigScreen`（Alt+P）漏了
  `doesGuiPauseGame()`（`GuiScreen` 默认 `true`），单机下开那个界面游戏就是暂停的，一次触发两个症状：
  - **动作冻结**：暂停时 `updateTimer` 把 `renderPartialTicks` 写回旧值、`AnimationTicker` 不再
    `tick++` → `seekTime = AnimationData.tick(+partialTick)` 恒定 → `AnimationProcessor` 的
    `AnimationRenderState` 去重每帧命中 → 骨骼停在上一帧。而位置/缩放/旋转与 follow 低通是按
    墙钟算的，仍在更新，所以现象是"还在晃、还在跟随，但动作不动"（眨眼定格在闭眼那一瞬）。
  - **隐藏部件全露**：`CustomPlayerModel.setLivingAnimations` 按 `!isGamePaused()` 选分支，
    暂停时掉进 **GUI 预览分支**：`setPreviewParserValues` 把玩家查询复位成中性值、
    `applyWeaponBoneVisibility(..., null)` 不按手持隐藏 → 画出来的是"预览态"。
  修法是让这块屏幕与其它三个 YSMU 界面一样 `doesGuiPauseGame() -> false`。
  **不要**只设 `AnimationData.shouldPlayWhilePaused` 绕过时钟：那样变成"预览态 + 时钟在跑"，
  控制器在中性查询值上反复横跳，模型会抽搐（实测踩过）。
- **GUI 预览是放大器**：模型/纹理选择界面每个按钮都跑一次完整实体渲染。已落地的修法是
  离屏 FBO 缓存（`util/FboCache`）：
  - `ModelButton` / `TextureButton` 把预览渲染进 `Framebuffer`，之后只画缓存纹理；
  - `Config.GUI_MODEL_PREVIEW_REFRESH` 控制刷新：`-1` = **自动（默认，整页共享预算）**、
    `0` = 只在脏/交互时重绘、`1..4` = 每 N 帧强制重绘；
  - **自动模式的目标曲线与 HUD 纸娃娃同形**（`PreviewRefreshPolicy`）：
    `perPreviewHz = clamp(帧率 × 0.75, 上限 90 Hz)`，再被"整页每帧开销 ≤ 8 ms"、
    "每 8 帧至少一次"（下界）夹住。用户实测定的点：**60 fps → 45 Hz、120 fps 及以上 → 90 Hz**；
    这一页打开时周边背景顺不顺看不出来，所以宁可把帧预算花在预览上。可见数量按"上一帧几个
    预览参与刷新"统计，翻页自动跟随。三个细节不能省：
    **错峰**（按键 id 给不同初始相位，否则十几个烘焙撞在同一帧 = 一帧十几毫秒的尖峰）、
    **焦点放宽**（悬停/选中的那个在播 hover/focus，按整页平均速率会发顿，单独放宽 3 倍）、
    **间隔硬下界**（见下一条）；
  - **间隔下界同样是"帧数"，而且这里比 HUD 更容易踩到**：每个缩略图都是那个模型唯一一次
    动画推进，间隔一超过控制器的再入窗口，眼睛/尾巴这些并行控制器每次都被重装并钉回 tick 0
    —— 实测表现是**眨眼卡在眨眼过程中**（用户报的原话）。所以
    `MAX_SKIP_FRAMES = OpenYsmPlayerControllerRuntime.safePassWindowFrames()`（= 再入窗口 - 2
    = 8 帧）既写进目标频率（让显示的目标说实话），也写成每个预览自己的帧计数兜底。
    注意它在帧率越高时越贵：下界是 `帧率 ÷ 8`，所以这一页的稳态成本是
    `可见数量 × 单次成本 ÷ 8` 每帧（13 个 × 1 ms ÷ 8 ≈ 1.6 ms/帧），这是"不破坏动画"的价格。
  - **兜底写成"每帧开销"而不是"每秒预算"**：比例式目标意味着每帧烘焙次数是
    `可见数量 × 0.75`（与帧率无关），所以帧时间才是被顶的那个量；按每秒算的预算会在帧率高时
    严重超支、帧率低时又太紧。`OVERHEAD_MS_PER_FRAME = 8` 只在预览极多或烘焙极贵时绑上。
  - **成本估计要挡掉加载尖峰**：每个模型第一次烘焙会把几何与动画一起加载（几十毫秒量级），
    原样喂进 EMA 会把整页频率压到底好几个周期（用户报的"目标频率太低"有这一份）。
    `noteBake` 现在把单次采样夹在 `4 × 当前估计` 以内：真实变慢仍能推上去，尖峰最多 ×1.9/采样；
  - **模型选择页左下角版本号后面直接贴频率**（`PreviewRefreshPolicy.describeMode()`）：
    自动模式显示 `preview FBO 30.8 Hz x13`，静态/每 N 帧显示对应模式。不用开 F3 就能看到；
  - HUD 纸娃娃另有 `Config.GUI_HUD_PREVIEW_CACHE` 开关，刷新率按**速率（Hz）+ 墙钟累加器**
    决定（**不要**用"每 N 帧"：同一个 N 在 400 fps 是 200 Hz、在 60 fps 是 7.5 Hz，策略会
    随帧率漂移，且低帧率那一侧正是最需要收敛的时候）。关系是
    `targetHz = clamp(min(150ms/秒 ÷ 重渲染成本, 帧率 × 0.75, 120), 12, 120)`：上不超 120 Hz、
    下不低于 12 Hz（低帧率下宁可超预算也不能看起来冻结）、中间按"每秒只花 15%"收敛。
    **× 0.75 = "每 4 帧最多烘 3 帧"**：帧率锁 120 时目标 90 Hz、锁 240 时到 120 Hz 上限、
    不限帧时也是 120 Hz。这个系数是实测定的（用户对比 120 / 240 / 不限帧三档提出）。
    累加器在单帧跨过多个间隔时丢掉多余额度，不做补帧。
  - **只有离散变化立即生效**：手持物品 / 盔甲 / 位置缩放 / 分辨率 / 显式 `invalidate()`。
    **角度变化不再有旁路** —— 转动时也走上面的速率策略（每次都用当前角度重烘焙，不累积误差，
    只是步长变粗）。以前 0.001° 的阈值会让"一转身就逐帧重渲染"，那是这类预览的最大开销来源。
    注意 `RenderUtil.pollHudDisplayBodyYaw()` 仍要**每帧**调用一次（推进 follow 模式的低通，
    与是否重渲染无关）。
  - **第一人称下这个预览不是"额外的一次"，而是唯一一次**：原版 `RenderGlobal.renderEntities`
    的主实体循环会跳过自己（`entity != renderViewEntity || thirdPersonView != 0 || isPlayerSleeping`），
    所以第一人称 + 没开界面时，**只有 HUD 纸娃娃这一条 pass 在推进自身动画与控制器状态机**。
    于是给它的刷新率设上限 = 直接给动画状态机的调用频率设上限，而
    `OpenYsmPlayerControllerRuntime` 是**按渲染帧**数"停放"的：同一个控制器两次处理相隔超过
    `RE_ENTRY_FRAMES = 10` 帧就判成"模型被换走又换回来" → `sameAnim=false` → 重新
    `setAnimation` → `shouldResetTick` 生效 → `adjustTick` 返回 0 → **每个动画都被钉在 tick 0**
    （走路/挥剑停在第一帧、状态切换时抖动）。`ControllerReEntryFrameTest` 记录的就是同一类
    失败（当年是模型选择页一帧十几个 pass 引起）。
  - **所以第一人称下必须再加一道下界，而且它是"帧数"不是"Hz"**：间隔一旦超过控制器的
    再入窗口，限频就从"省性能"变成"功能 bug"。规则是
    `targetHz = max(上面的速率策略, 帧率 ÷ HUD_SOLE_DRIVER_MAX_SKIP_FRAMES)`，且每帧硬性
    要求相邻两次烘焙的间隔 `>= HUD_SOLE_DRIVER_MAX_SKIP_FRAMES` 时强制烘焙；后者
    `= OpenYsmPlayerControllerRuntime.reEntryFrameWindow() - 2` = 8 帧（两个帧的余量），
    由窗口推导而不是拍值。副作用要知道：帧率超过 8 × 120 = 960 fps 时这道下界会把实际
    速率顶到 120 Hz 上限之上（1381 fps 下约 172 Hz）—— 那是硬约束，不是偏好。
  - **判据是 `thirdPersonView != 0`，不是 `currentScreen == null`**：第一人称 + 背包/轮盘时
    世界渲染同样不画自己，纸娃娃仍是唯一动画 pass，只有它还在动。真正有"第二个 pass"的只有
    两种情况——第三人称的世界渲染、以及自带预览的界面（Alt+P 的 HUD 设置页，它在
    `onRenderScreen` 里早退、由界面自己的预览每帧驱动）——那时才回落到纯速率策略。
  - **判据是 `150 ÷ 单次成本 > 帧率`**，也就是"一次重渲染超过一帧的 15%"才节流。所以把帧率
    锁到 60 往往看不出任何变化（2 ms 的重渲染只占 12%），要验证得锁 120 或不限帧。
  - **F3 右侧有实时读数**（`ClientEventHandler.onDebugText`，挂在 `RenderGameOverlayEvent.Text` 上，
    F3 关着时零开销、不写日志所以不需要 `Config.DEBUG_*`）。默认**只一行短行**，因为在默认
    界面尺寸下两行长文本会把左列挤掉；**按住 Shift 或 Ctrl** 才显示完整明细：
    短行 `HUD FBO x Hz (xN/f)` 就是"纸娃娃动画的等效帧数" + 缓存命中率（1.00 = 完全没命中）；
    明细补 `target`（窗口内平均）/ `frame` / `sole driver` / `bake` / `blit` /
    `max gap`（窗口内相邻两次烘焙的**最大**帧间隔，对着 8 帧的上限看 —— 曾经报"最后一帧的
    间隔"，恰好落在间隔中间就会误报成 7），以及预览页开着时的
    `preview grid`（可见数量、每个预览的目标 Hz、单次烘焙成本）。
    一次 0.5 s 窗口一发布；`--` 表示还没采样到。
  - **屏幕盖住 HUD 时不要重渲染**：1.7.10 里 HUD 先画、`currentScreen` 后画
    （`EntityRenderer.updateCameraAndRender`），所以 `PlayerModelScreen` 等自带背景的
    全屏界面打开时，纸娃娃画了也看不见。`ClientEventHandler.onRenderScreen` 对这些界面
    直接早退并 `invalidate()`。轮盘 `AnimationRouletteScreen` 不画背景、故意要露纸娃娃，
    **不在**该名单里。
  - 模型同步进行中不重建 FBO 缩略图（`ClientModelManager.SYNC_IN_PROGRESS`），先显示旧/空白。
- **GUI 预览实体走专用早退分支**：预览实体 `player == null`，`predicateMain` 直接返回
  `previewAnimation` 或 `gui` / `idle`，`predicateArmor` 立即 `STOP`。所以"GUI 里盔甲控制器
  空转"不是当前代码的问题；固定开销是每个预览实体仍注册了整套控制器。
- **纹理解码不在每帧**：`ImageIO.read` / `DynamicTexture` 注册集中在界面构建与翻页，表现为
  帧尖峰而非持续负载；翻页重建按钮会重新分配 `DynamicTexture`。
- **FBO 缓存不是"一次就够"**：命中路径只是一次纹理 blit，但**刷新策略**决定了实际收益。
  一份 4K、开着模型选择界面的客户端采样里，HUD 纸娃娃路径仍占整条客户端线程 36.5%
  （命中路径只有 8 ms，重渲染 8,756 ms）。最新一次（最小环境）把两种工况分开了：
  **模型界面开着时纸娃娃占 8.8%，关着时占 ~50%**。每次改预览缓存后都要重新采样确认。
- **别把"每帧重算的间隔值"交给 `FboCache.checkAndResize`**：它一看到间隔值与上一帧不同就把
  自己的倒计时清零（对固定的 2/4/8 档位没问题），所以喂给它一个持续漂移的测量值会让它
  **每帧都重渲染**。踩过一次：把 HUD 纸娃娃的间隔改成自适应后，重渲染频率从 0.48 次/帧
  变成 1.01 次/帧，正好把 A0 省下的量吃掉。倒计时要由调用方持有、间隔每个周期只采样一次。
  计数是从 profile 里反推的：`capture()` 每次 3 次 `glIsEnabled`、blit 每帧 4 次，两者同一条
  `GL11C.glIsEnabled`，相除即得"重渲染/帧"。

## 验证方法

- `/spark profiler` 采 30 s，看 `CustomPlayerRenderer` / `IGeoRenderer.renderRecursively`
  及其调用者的占比。
- **别读网页上的 "CPU 时长"**：它是按类目包含求和的（同一方法在不同调用点会累加，能显示成
  200%）。用 `tools/spark_dump.py` 从调用树重算 self（独占）时间，并先看
  `--package-self` / `--class-self` 再往下钻。
- 采样时把场景写清楚（分辨率、维度、有没有开界面），并把"世界内正常游玩"与"UI 场景"分开采：
  UI 场景会把世界渲染完全淹没（曾只占 8%）。
- 归因 CPU 前先处理 native/驱动调用的大块 self 时间（`glGetError` 之类）：4K 下它可能占
  25% 帧时间却只是驱动在等 GPU；要归因 CPU 就降分辨率重采一次。
- F3 饼图或 `mcProfiler` 段：区分负载来自原版渲染还是模型动画。
- 对比实验：同一场景切换 `GUI_MODEL_PREVIEW_REFRESH` 的 0 与 4 观察帧率差；开/关
  `GUI_HUD_PREVIEW_CACHE` 对比 HUD 负载。

## 已知限制 / 未验证

- 1.7.10 是固定管线（GL 2.1），没有可用的现代 GPU profiler；只能从 CPU 侧观察 GL 调用耗时，
  GPU 内部分时不可得（可尝试 Intel GPA / RenderDoc 抓帧）。
- spark 的 Java 采样器只记时间不记调用次数：任何"每帧几次"都是推的，别当成实测。
  唯一的例外是 HUD 纸娃娃：F3 右侧的 `HUD FBO x Hz / xN/frame` 是代码内直接计数的实测值，
  可以拿来校准 profile 里反推出来的数。
- **`metadata.number_of_ticks` 是游戏 tick 不是帧数**（≈ 时长 × 20，就是 MC 的 tick 上限），
  profile 里根本没有帧数。踩过一次：把 496 tick / 24.8 s 读成 "496 帧 / 20 fps"，
  于是所有"每帧 ms"都大了 20 倍以上。跨密度、跨机器对比时**只用百分比**最省事。
- `SamplerData.time_windows` 是按**墙钟分钟**切的，多窗口只说明采样跨过了分钟边界，
  不能当成"某个操作前 / 后"。
- 原文的帧数/百分位数字来自特定模型与机器，**不**作为基线；换模型、换硬件需重测。
- 已落地的优化只覆盖了 HUD 预览与控制器/Molang 的每帧重复写入；合批、骨骼矩阵缓存、
  Molang 预编译、`renderBoneCubes` 的逐骨 GL 状态切换都还是候选，未实现。

