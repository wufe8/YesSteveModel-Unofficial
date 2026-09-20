# YesSteveModel-Unofficial (YSMU)

**该文档主要由ai生成 虽经人工修正 但不确保准确性**

---

## 概述

YSMU 是一个 Minecraft Forge 1.7.10 模组，将 YesSteveModel 移植回 1.7.10 (主要是给我自己玩gtnh)

---

## 当前状态

**最新版本：`1.9a1-08`**（`perf/previewUI` 分支）

> mod 版本号与 jar 名由 git tag 自动生成（`Tags.VERSION`），启动日志里会打印
> `I am ysmu at version …`。需要人工维护的时效信息只有三处：本行、下面的变更历史表、
> 以及「已知问题」小节。

> [NOTE]
> 项目仍处于 **Alpha 阶段**，部分功能可能不稳定, 欢迎提交 Issue

## 安装

### 环境要求
- **Minecraft**: 1.7.10
- **Java**: 8，或 17-25（需安装 lwjgl3ify）
- **Forge**: 10.13.4.1614
- **必需 Mod**（缺少会崩溃）:
  - [UniMixins](https://github.com/LegacyModdingMC/UniMixins) — Mixin 框架支持
  - [GTNHLib](https://github.com/GTNewHorizons/GTNHLib) ≥ 0.5.14 — 提供 `@EventBusSubscriber` 自动事件注册；更早版本缺少此类，启动时会 `NoClassDefFoundError`
- **可选 Mod**（缺失时自动降级，不影响启动）:
  - [Backhand](https://github.com/GTNewHorizons/Backhand) — 副手持物支持
  - [Angelica](https://github.com/GTNewHorizons/Angelica) — 光影兼容
  - Battlegear2 — 盾牌格挡检测
  - Et Futurum — 鞘翅飞行检测
  - Tinkers' Construct — 十字弩状态检测
  - [Baubles-Expanded](https://github.com/GTNewHorizons/Baubles-Expanded)（GTNH 随包自带）— `ysm.has_any_curios` 的 1.7.10 实现：Curios 在 1.7.10 上不存在，用其前身 Baubles 的槽位代替
- **GTNH 兼容性**:
  - ✅ GTNH 2.8.4 — 已测试
  - ✅ GTNH 2.9.0-beta2 — 已测试
  - ⚠️ GTNH 2.7.3 — 理论兼容（GTNHLib 0.5.23 ≥ 0.5.14），未实际测试
  - ❌ GTNH ≤ 2.6.x — 不兼容（GTNHLib < 0.5.14 缺少 `EventBusSubscriber`）

### 安装步骤
1. 安装 Forge 1.7.10（推荐 10.13.4.1614）
2. 将 UniMixins、GTNHLib 放入 `mods` 目录
3. 从 [Releases](https://github.com/wufe8/YesSteveModel-Unofficial/releases) 下载 YSMU 放入 `mods` 目录
4. 启动游戏

---

## 主要功能

### 模型系统
- **YSM 标准模型支持**：兼容旧版文件夹模型（`main.json` + `arm.json` + `.png`）和 `.ysm` 二进制模型
- **YSM 新格式支持**：`ysm.json` 文件夹结构、format 32 新版二进制 `.ysm`
- **高版本模型兼容**：支持 format 1.21.0 几何格式，桥接 `.molang` 函数文件
- **模型包系统**：模型扫描、服务端/客户端同步协议、GUI 模型分组
- **WebP 纹理解码**：移植 ImageStream 解码器，处理加密 `.ysm` 中的 WebP 贴图
- **内置模型**：内置默认模型自动提取到 `config/ysmu/builtin`
- **负尺寸 Cube 归一化**：自动归一化 BlockBench 负尺寸 Cube + 移除零 UV 面，提升模型兼容性
- **统一模型同步**：合并 legacy（MD5/AES）与 OpenYSM 两条同步路径为单一路径；服务端内容寻址去重、原子写缓存、侧车索引（二次启动重建降至 ~5s）；大模型库同步索引分块传输（突破 1.7.10 32KB 包限制）
- **按需懒加载**：几何/动画/贴图按需后台加载、空闲自动卸载，显著降低大型模型库的同步峰值内存与显存占用

### 动画系统
- **动画控制器**：状态机、blend transition、timeline、`on_entry`/`on_exit`
- **并行动画播放**：支持 `parallel_N` 控制器，多动画同时播放
- **攻击连击系统**：`post_swing` 状态机推进 attack1→2→3，支持检测模型武器可见性
- **剑格挡条件动画**：显式声明 `use_mainhand:sword` / `use_offhand:sword`（等价别名 `use_mainhand:sword_block` / `use_offhand:sword_block`，声明任意一个效果相同）即可覆盖剑格挡，无需动画控制器；盾牌格挡仍走 `use_mainhand:block` / `use_mainhand:shield`。详见 `docs/analysis/blocking-animation-names.md`
- **潜行动画修复**：四种降级路径（MOVE-BLOCK、SKY-REDIRECT、wasMoving、通用回退），修复无 `ground` 状态模型的潜行动画；自动识别模型控制器是否自行处理潜行，未处理时正确回退到 legacy 潜行状态机
- **动画过渡修复**：GeckoLib blend transition 实际生效（原首帧即跳过过渡），可通过 `AnimationTransitionTicks` 配置全局过渡时长（默认 200ms，模型 `blend_transition` 优先）
- **动画合并修复**：`arm.animation.json` 空动画不再覆盖 `main.animation.json` 有骨骼的正常版本
- **Root 骨骼过滤**：非主控制器自动剔除 Root 骨骼动画，防止身体旋转被覆盖
- **额外动画轮盘**：可定制的 8 槽位动画轮盘，支持子菜单导航、翻页
- **骑乘退出检测**：下马时 40-ticks 停止其他控制器，确保过渡动画播放
- **GUI 预览动画**：模型选择界面支持 hover/focus 动画和双控制器预览混合
- **动画预览界面**：模型纹理选择页面重做为三栏布局（动画列表 + 3D 预览 + 贴图选择），支持暂停/复位/地面切换、鼠标拖拽旋转视角

### Molang 脚本引擎
- **完整 Molang 解析器**：带运算符优先级修复、三元表达式、null-coalescing (`??`) 和赋值操作符
- **高版本桥接**：解析 `.molang` 函数文件，将 `ctrl.set_animation('正常_行走')` 映射为标准状态名
- **变量支持**：
  - `query.*` — 玩家/世界/物品查询函数
  - `query.is_blocking` 查询格挡状态
  - `ysm.*` — YSM 特有变量（`ground_speed2`、`fps`、`input_vertical/horizontal`、`has_helmet`、`attack_time` 等）
  - `v.roaming.*` — 服务端同步的 roaming 变量（`helmet` 等）
  - `ctrl.hold`/`ctrl.use`/`ctrl.swing` — 完整实现的物品检测函数
- **物理变量注入**：roaming 变量注入到动画关键帧路径

### 音频系统
- **OGG Vorbis 播放**：通过 DirectSound 实现模型音效播放
- **控制器级生命周期**：音效与 GeckoLib 控制器绑定，动画切换/停止时自动清理
- **vanilla 音效回退**：非 OGG 音效通过 `SoundHandler.playSound` 播放
- **音效内存缓存**：模型音效不再明文落盘（原写 `SOUND_CACHE/*.ogg`），改为内存缓存 + 后台预暖，首播无卡顿、磁盘不泄漏明文

### 配置与 GUI
- **全新配置面板**：右侧面板布局、更大预览、拖拽旋转预览玩家
- **模型选择界面增强**：前景/背景纹理、GUI 动画、foreground/background 贴图渲染
- **配置页面改进**：透明度滑块、显示名称、包文件夹图标
- **翻页/锁定按钮**：动画轮盘锁定、多页导航
- **`/ysm play` 命令**：在游戏中播放指定动画
- **预览刷新频率调整**：FBO 缓存刷新频率 在模型选择(Alt+Y)的设置页面中可以调整模型预览的刷新率 能有效提升预览页面的游戏帧数 但会导致动画预览卡顿
- **调试覆盖层**：`/ysm debug overlay`（快捷键 Ctrl+P）实时显示控制器状态/Molang 变量，支持搜索与过滤；变量右列显示其来源模型（`@模型名`），便于发现跨模型残留；`/ysm debug query <表达式>` 运行时查询变量值
- **副手物品隐藏**：`HiddenOffhandItems` 可配置隐藏指定副手物品（默认隐藏 Extra Utilities 除叶斧），避免其错误渲染
- **显存预算与降采样**：`TextureVramBudget`（默认 256MB）超预算按 LRU 整模型释放 GPU 纹理（字节保留在内存，重传无白模）；`TextureTargetSize`（默认关）可对超大贴图按 2 的幂降采样进一步压显存
- **渲染路径防御**：渲染/调度异常节流抑制，单个模型问题不再导致日志刷屏或崩溃报告反复触发
- **堆内存优化**：原始模型数据在同步完成后释放（~1GB）；GeckoLib 动画缓存自动卸载闲置模型（每模型 ~9MB）；KeyFrame ConstantValue 内联为原始 double 字段（~160MB）；AnimationPoint 对象池复用减少 GC 压力
- **HUD 自拍模型 FBO 缓存**：在 Alt+P 配置界面可按 C 切换。开启后 HUD 模型以自适应帧率更新（>125fps 时每 8 帧刷新，62.5-125fps 每 4 帧，<62.5fps 每 2 帧），大幅降低 HUD 渲染开销（测试模型从 164fps 提升至 520fps）；关闭则每帧完整渲染

### 兼容性
- **Backhand 双持**：通过 `BackhandCompat` 隔离, 副手物品正确检测
- **Angelica 光影**：第一人称手臂渲染通过 `AngelicaCompat` + Mixin 分流
- **Et Futurum 鞘翅**：通过 `EtFuturumCompat` 检测鞘翅装备/飞行/滑翔进度
- **Battlegear2 盾牌格挡**：通过 `BlockingCompat` 反射调用格挡检测，支持剑格挡、双持盾牌
- **TiConstruct 十字弩 (GTNH)**：通过 `TinkersCrossbowCompat` 识别弩的装填/加载状态，使 `use_mainhand:crossbow` 拉弦动画和 `hold_mainhand:charged_crossbow` 蓄能待机动画正常工作
- **高版本音效**：通过 `SoundNamespaceCompat` + `LocalAssetProvider` 加载本地高版本 Minecraft 资源包音效（`/ysm setgamepath`）
- **DirectBuffer 看门狗**：自动监控 Direct Buffer 内存使用，超过阈值自动 GC，缓解 ZGC 下某些 mod（如 Distant Horizons）的 DirectByteBuffer 泄漏影响；可通过配置关闭或调整阈值。`/ysm buffer` 命令可随时查看内存状态
- **UniMixins** 和 **GTNHLib** 为运行时必需

---

## 变更历史（自 1.9-alpha1 以来）

| 标签 | 说明 |
| --- | --- |
| `1.9-alpha1` | 初始重构版本，动画控制器支持、新模型同步协议 |
| `1.9-alpha1-fix-ThirdPersonView` | 修复第三人称视角问题 |
| `1.9-alpha1-fix-EmojiVisibillty` | 修复表情可见性，重构 Molang 函数注册 |
| `1.9-alpha1-fix-AttackAnimation` | 修复攻击动画，空闲时播放 `attack_idle_N` |
| `1.9-alpha1-fix-LogOverflow` | 修复 `query.position_delta` 导致的日志溢出崩溃 |
| `1.9-alpha1-pre1-feat-ExtraUI-00` | 额外动画轮盘、平行动画、Molang 增强、配置面板重构 |
| `1.9-alpha1-pre1-feat-ExtraUI-01` | GUI 预览动画、内置模型提取、WebP 解码器移植 |
| `1.9-alpha1-pre1-feat-ExtraUI-02` | 潜行语义修正、头盔检测、范围滑块 roaming 变量初始化 |
| `1.9a1-03` | 投射物渲染、攻击连击修复、并行模型缓存、格挡支持、滑条默认值修复 |
| `1.9a1-04` | 一系列性能优化、负尺寸cube修复 |
| `1.9a1-05` | 动画预览界面、HUD FBO 缓存、深度性能优化与 Bug 修复 |
| `1.9a1-06` | 统一模型加载、懒加载与显存优化、动画/Molang 修复、调试覆盖层 |
| `1.9a1-07` | 粒子系统、HUD 跟随三模式、玩家模型优先加载、同步/内存优化、Molang 补全、跨模型变量隔离与预览缩放修复 |
| `1.9a1-07.1` | 不可桥接的二进制模型的内置模型回退、预览 GUI 打开期间抑制闲置资源卸载 |
| `1.9a1-08` | 并行动画按通道合并；**隐式并行控制器**（按动画表为 `pre_parallelN`/`parallelN` 生成控制器）修复部件该隐藏却显示、轮盘选项无效；Bedrock `pre` 阶梯关键帧解析、合成状态周期取最长贡献动画；旧槽位控制器不再重复回放并行动画；挥剑音效在动画中途切换移动状态（站立↔行走变体）时重复播放；高版本音效名不再被误推给 vanilla 音效系统（曾导致音效"有播放动作却听不到"并刷 `Unable to play unknown soundEvent` WARN）；轮盘单选框变量注册与勾选框交互修复；客户端模型注册表在握手时清理；资源重载(F3+T/换资源包)后 SoundSystem 句柄自动重绑，不再永久静音；模型属性 `render_layers_first` 生效（渲染层先于本体提交，本体可以遮住手持物品）；`.molang` 动画控制脚本的 `ctrl.set_beginning_transition_length`/`ctrl.indicate_reload`/两参数 `set_animation` 生效；`query.is_item_name_any` 实现、`query.relative_block_has_any_tag` 支持 `minecraft:replaceable`；渲染/手持/覆盖层等每帧入口改为接住 `Throwable` 并去重告警；具名并行槽位池改为可配置（`NamedParallelExtraSlots`，默认 8/族、上限 16/族，含只有 `@player_ctrl_<槽位>.molang`、没有 `controller/*.json` 的槽位；池承载的槽位也能被 `ctrl.reset` 重置）；关键帧时间轴改为**有界调度器**（各贡献动画按自身周期计时、在真实播放时钟上派发，单帧派发量有上限；修掉极端短周期活锁与循环回绕后时间轴冻结）；`@sync` 在渲染帧外带「发起者玩家 + 模型」变量作用域执行，`v.*` 写回不再丢失，服务端按玩家约 1 秒限流、下行回主线程；补注册 `ysm.abs_particle`；内置兜底 default 模型的贴图与几何不再按空闲释放（修闲置回切白模、第一人称缺手臂 geo）；中文界面/聊天文本统一改用半角标点，修全角括号导致后半行不渲染与居中偏移；**常驻变量写回**：动画/时间轴写下的 `v.roaming.*` 不再被模型默认值每帧冲掉（某模型轮盘"变身"的时间轴用 `v.roaming.b` 记形态，默认值每帧回写导致变身只能生效一次、之后切不回来）；控制器注册顺序按 wiki「并行动画」改为 `parallelN` 优先级最高（排在主动画与轮盘 cap 之后，`pre_parallelN` 仍最低），并按 wiki「护甲动画」让护甲动画仍排在并行族之后 —— 修轮盘动画里的 `scale` 回写（如"打招呼/鼓掌"把 `AllBody` 缩放写回 1）盖掉并行形态缩放、人形与狐形同时显示；控制器「再入」判定改按**渲染帧**计（原来在 `MolangPhysicsRuntime.begin()` 里按**模型 pass** 推进帧计数，模型选择页(Alt+Y)一帧要渲染十几个模型，于是每个并行控制器都被判成"停放十几帧"→ 每帧重启：`enteredTick` 归零、tick 钉在 0、时间轴游标重启，眼睛/耳朵/尾巴/表情/物理状态每帧回到初值，表现为预览页抖动 + 眼睛逐帧眨动；关掉页面或开背包时每帧只有 2~3 个 pass 就正常。帧计数现在由 `TickEvent.RenderTickEvent` 每渲染帧推进一次；弹射物动画的**通道存在性**修复（只写旋转的动画不再把前一条动画写好的缩放/位移清零 —— 某弹射物外圈骨骼的 0.9 缩放被只写旋转的 `parallel3` 每帧清零，落地后的旋转白色六边形整段隐形）；弹射物**状态时钟**修复（控制器状态动画改为从**进入该状态**那一刻计时，`post_main`/`post_ground` 这类`hold_on_last_frame` 动画不再从第二帧起就停在末帧 —— 落地 0.2 秒的爆开动画以前被整段跳过，箭身缩放永远到不了 2.4）；弹射物动画的 **timeline 现在真的执行**（此前这条链路完全没有派发，飞行拖尾与命中水花的 `ysm.particle` 一次都不发），并为弹射物补上骨骼作用域（`ysm.bone_pivot_abs` 不再恒 0）与粒子渲染变换；`bubble_pop` 补进行为表（固定 4 tick 寿命、gravity 0.008、保留调用方速度）并支持逐帧贴图（`bubble_pop_0..4` 按 age 轮播，此前只读第一张）；弹射物模型改为**走混合渲染**（此前直接调 `renderRecursively` 绕过了 `IGeoRenderer.render` 的 blendFunc/enableBlend，贴图里透明的像素被当成不透明画出来：外圈方块变成实心面片；官方是半透明几乎不可见。当时把「底面发黑」也归给了透明像素的 RGB —— 那是错的，alpha test 早把 alpha≈0 的像素丢了，真正原因是下面的世界内光照）；弹射物时间轴的粒子改用**世界坐标**（vanilla `doRender` 的 x/y/z 是相机相对坐标，此前被当世界坐标用，粒子生成在离箭矢 2000+ 格处）；**骨骼可见性判定改为「任意一轴缩放为 0 即不渲染」**（原来只有三轴全 0 才隐藏）：某弹射物命中后把箭身写成 `[0,1,1]`，官方看不到、我们却按 0 厚度面片画了出来（那张贴图 alpha 全不透明，所以是个很显眼的实心方块）；单轴 0 是模型通用的隐藏写法（眼睑/嘴/眉毛/刀光轨迹），现代管线下这种退化矩阵本来也画不出来；弹射物**状态时钟（修正）**：交给渲染器的必须是「时钟原点 = 进入该状态时的实体年龄」（渲染器统一用 `年龄 - 原点` 采样）。第一版把「已播放时长 = 年龄 - 进入年龄」当原点，渲染器又减一次 —— 实际采样到的是「进入时的年龄」本身：飞行期（进入≈0）箭身停在动画第一帧（官方的「飞行时蓝色方块」根本不出现），近距离 3~5 格落地早，采样落在爆开动画中段（方块可见且不再变化），弹射物模型的**世界内定向光**改为关掉（只在弹射物绘制期间 `glDisable(GL_LIGHTING)`、`finally` 还原，与官方近平光一致）：压扁成薄卡片的子模型会带着压缩前的三个法线，官方的近平光下三个面几乎同亮，而 1.7.10 的 0.4 会把其中一个背光面打黑 —— 官方同角度实测白框最暗 230/255 = 0.90（近平光），我们这边实测 101/255 = 0.396（同一个 0.396 因子把白框压成灰、把半透明蓝 `(84,131,219) α136` 混天空成 `(87,115,165)`，就是玩家看到的「内圈蓝色部件底部发黑」）。玩家模型不在弹射物链路上，仍保留 1.7.10 的逐面明暗远距离落地晚反而被钳到末帧看着「正常」；`ysm.shoot_item_id` 实现（服务端把射手手持物经第二个 datawatcher 同步，客户端折算成模型写的现代 id —— GTNH 的 `TConstruct:Crossbow` → `minecraft:crossbow`），弩的子模型不再永远不显示 |

---

## 从源码构建

```powershell
# 克隆仓库
git clone https://github.com/wufe8/YesSteveModel-Unofficial.git
cd YesSteveModel-Unofficial

# 检出活跃开发分支
git checkout perf/previewUI

# 构建
.\gradlew.bat build

# 运行客户端
.\gradlew.bat runClient

# 运行测试
.\gradlew.bat test
```

构建产物位于 `build/libs/` 目录。

---

## 模型安装

### 文件夹模型
将模型文件夹放入 `config/ysmu/custom/`，需包含：
- `main.json` — 主体模型几何
- `arm.json` — 第一人称手臂几何
- 至少一个 `.png` 贴图
- 可选：`main.animation.json`、`arm.animation.json`、`extra.animation.json`

### `.ysm` 二进制模型
将 `.ysm` 文件放入 `config/ysmu/custom/`, 支持新版 format 32 格式(有问题详细描述issue)

---

## 已知问题
已修复问题通常会在下一次release时删除

- [SKIP] battlegear2的盾牌位置不正确 目前会以物品的位置来握持(实际上就是物品而非工具)
- [SKIP] WebP 解码器基于外部实现, 没搞定纯ImageIO
- 部分控制器变量与molang函数可能存在bug
- molang 自定义函数(`functions/*.molang`)已支持 `fn.*` 调用链与递归(深度 32)、`args[]`/`t.*` 临时变量、`loop`/`for_each`、`break`/`continue`、`@player_init`/`@player_update`/`@sync` 事件订阅，以及 `@player_ctrl_<槽位>.molang` 动画控制脚本每帧执行(`ctrl.set_animation` 含循环类型、`ctrl.set_beginning_transition_length`、`ctrl.indicate_reload`、`ctrl.state_continue`/`ctrl.state_bypass`)。`ctrl.hold`/`ctrl.use`/`ctrl.swing`/`ctrl.armor` 在关键帧、timeline 与脚本三条路径共用同一套判断（`$物品ID` / `#tag`（1.7.10 恒 false）/ `:类别`），`ctrl.reset` 会中止当前动画并重置该槽位的控制器状态。**已确认不做**（参考库里 `state_pause` 0 处引用、`state_stop` 仅 2 文件 3 处）：`ctrl.state_pause`（GeckoLib 无「暂停但不暂停时间轴」的原语，回落到内置逻辑）、`state_stop` 的平滑淡出（骨骼复位分支目前是瞬时的，改它会影响所有控制器的停止表现）。控制脚本已接到 `main`、`pre_main`/`post_main`/`pre_hold`/`pre_swing`/`pre_use`（OpenYSM 槽位）、数字 `pre_parallel_N`/`parallel_N`、`use`、槽位后缀（`player.<槽位>_<后缀>`，经备用池 `openysm_slot_extra_N_controller` 路由），以及模型自命名的并行槽位（经备用池 `*_extra_N_controller` 按当前模型路由，含只有 `@player_ctrl_<槽位>.molang`、没有 `controller/*.json` 条目的槽位）；`ctrl.reset` 的控制器名按 `player.` 前缀/`_controller` 后缀归一化匹配，池承载的具名槽位也能被重置。配置项 `MolangControlScripts` 可关回旧的纯静态提取行为
- `MOLANG_STATE_MAP`/`MOLANG_CONDITIONAL_MAP`（静态"状态→动画"映射，供 legacy 主状态机使用）**只允许身体层槽位写入**：`main` 与 `pre_main`。覆盖层槽位（`parallel_N`/`pre_parallel_N`/`use`/`swing`/`hold_*`/护甲…）由 `applyControlScript(event, 槽位)` 每帧在**自己的控制器**上求值，它们的 `ctrl.set_animation` 若被当成主状态的替代动画，整个主状态机就会被别的槽位劫持 —— 曾出现某内置子模型的 `@player_ctrl_parallel_5`（碰墙抬手，守卫 `ctrl.run || ctrl.walk`，而静态提取取的是守卫里**最后一个** ctrl 名 = `walk`）把走路替换成只有 4 根骨骼的贴墙防御姿势，表现为"向前走路腿不动、角色直立"。保留 `pre_main` 是因为 1.7.10 侧没有 `pre_main` 控制器，模型没有 JSON 控制器时它只能靠这两张表生效；跳过提取时会在 `DebugController` 下提示一次。同一处还有第二层缺陷：静态提取曾把"外层复合守卫 + 块里**第一个** `set_animation`"登记成一条替代动画，于是外层守卫会顶替内层的真判定 —— `碰墙抬手` 的四个 `query.relative_block_has_any_tag(...)` 墙判定就是这样被丢掉的，只剩 `ctrl.run || ctrl.walk`，**空旷超平坦（四个方向都没有墙）也会播 `defWall`**。现在只有在块里没有内层条件动画时才登记外层守卫那一条
- GUI 预览（无玩家）渲染前会把"玩家运动/视角"类查询复位成中性值（`query.head_*`/`body_*`/`eye_target_*`/`yaw_speed`/`ground_speed`/`vertical_speed`/`ysm.head_*`/`ysm.input_*`/`ysm.xxa|yya|zza` 等）。预览实体的 `setPlayer(null)` 让 `setParserValue` 整段不执行，而这些查询走的是**全局共享 parser**，不复位就会读到世界渲染上一次留下的实时值（实测预览里 `query.yaw_speed≈130`）；模型只要把这类速率累加进 `v.` 变量（头发/披风滞后），预览里就会无休止地 360 度旋转。会随时间自然推进的量（`query.anim_time`/`life_time`、`ysm.fps`、`ysm.rendering_in_*`）刻意不动
- 头部旋转查询的轴向按 wiki（`molang/ref`：`ysm.head_yaw` **与 `query.head_x_rotation` 相同**、`ysm.head_pitch` **与 `query.head_y_rotation` 相同**）绑定：**头部这对是 x = 左右视角(yaw)、y = 上下视角(pitch)**，与 `query.eye_target_*`/`body_*`（x = pitch、y = yaw）刻意不同，两条求值路径（关键帧与控制器/脚本）同源。此前 x/y 被绑反，模型用 `query.head_x_rotation` 做的左右摆动拿到的是俯仰角 —— 平视时看不出来，抬头到顶/低头到底时头发/披风被塞进 ±90 的左右旋转而过度翻转卷曲
- `query.head_z_rotation` 是 **YSMU 扩展**（YSM wiki 与官方实现都只有 `head_x_rotation`/`head_y_rotation`）：1.7.10 没有实体 roll，模型侧唯一的 Z 旋转是**相机**的 `EntityRenderer.camRoll`，而本机玩家头部朝向与镜头一致，因此该查询返回相机 roll 的插值（只对本机玩家生效，远程玩家的头部 roll 没有同步字段）。原版从不写 `camRoll`，所以不装 roll 相机类 mod 时它恒 0，与官方行为一致
- 副手隐藏名单(`HiddenOffhandItems`)此前只在 YSM 自己接管第一人称渲染时生效：主手**空手**时 `shouldRenderCustomHand` 成立、隐藏名单被应用；主手**拿物品**时 YSM 会让位给原版，副手由 Backhand 自己在其 RETURN 注入里画，隐藏名单被绕过（表现为"空手时治愈之斧隐藏、主手拿东西时又冒出来"）。已修：在原版第一人称渲染入口清空 Backhand 副手渲染器的 pending 物品（该字段每 tick 由 Backhand 重填，不会丢物品）
- `ysm.has_any_curios(槽位, 物品id...)` 用 1.7.10 的 **Baubles/Baubles-Expanded** 实现（Curios 的前身）：Curios 标准槽位 `necklace`/`ring`/`belt`/`charm`/`head`/`body`/`hands` 映射到 Baubles 类型 `amulet`/`ring`/`belt`/`charm`/`head`/`body`/`gauntlet`，模型直接写 Baubles 类型名也能用；**模组自建槽位（`back`/`spellbook`/`curio` 等）在 1.7.10 没有对应物**，返回 false 并各提示一次（不拿"任意饰品"冒充）。注意 1.7.10 上有三个都叫 `Baubles` 的版本、槽位结构不同：原版与 GTNH fork 是**固定 4 格**（`0=amulet, 1/2=ring, 3=belt`，没有类型化 API），只有 **Baubles-Expanded** 才是可配置的十几个槽位（`BaubleExpandedSlots`）；代码按"Expanded 的类在不在"分流，普通版下 `charm`/`head`/`body` 等类型返回 false 并提示需要 Expanded；解析不出槽位时若模型给了具体物品 id，会退化为"任意饰品槽里有没有这个物品"（遇到问题再回退，不做版本硬校验）。未装 Baubles 时该查询恒 false 并提示一次
- 标签类查询在 1.7.10 没有数据驱动实现，只做能原生回答的部分：`query.relative_block_has_any_tag` 支持 `minecraft:replaceable`；`query.equipped_item_any_tag`/`all_tags` 支持物品类型标签(`minecraft:swords`/`axes`/…)与材质标签(`forge:ingots/iron`→矿物词典 `ingotIron` 等)，其余恒 false。两类未命中分开处理：**来源模组没装**的标签（1.7.10 上最常见，如 `irons_spellbooks:staff`）永远匹配不到，按「正确跳过」每个警告一次 `mod '…' is not installed`；**来源可用但没映射**的才是本模组的缺口，在 `DebugController` 下提示一次。注意这些名字**必须注册**：未注册函数会让整条关键帧表达式解析失败、整个 animation 被丢弃
- 子模型(投射物/载具)可能还存在一些问题 目前仅保证默认模型投射物可用。弹射物这条链路上已按 wiki（`动画制作/弹射物动画`，其前身 `箭矢动画`）整理过：`air`/`ground`/`fire`/`water` 是**按实体状态择一**的状态动画（`air`/`ground` 优先度低、`fire`/`water` 优先度高，并行动画优先级最高），`parallel0..7` 恒定播放，控制器状态机叠在其上；过去"控制器没引用到的动画一律播放"会让四个状态动画同时生效，模型为不同状态准备的子模型（弓、弩、爆开、落地插地）全部叠在同一个实体上。另外 `.ysm`/`ysm.json` 包装的弹射物动画与控制器以前**只桥接几何和贴图、丢掉动画**，导致弹射物拿不到动画文件、几何停在绑定姿势（同样是"所有子模型同时可见"）。两者都已修。之后又修掉两个只在第三方弹射物模型上暴露的缺陷：**通道存在性**（解析器会给动画没写的通道留一个三轴为空的 `VectorKeyFrameList`，旧实现把它当成 0 写进骨骼，于是只写旋转的动画会把前一条动画写好的缩放清零 —— 某弹射物外圈骨骼的 0.9 缩放被 `parallel3` 每帧清零，落地后的旋转六边形隐形）与**状态时钟**（控制器状态动画改从进入状态那刻计时，落地爆开动画不再一帧就停在末帧）。弹射物动画的 `timeline`（含粒子）已接通：飞行拖尾与命中水花会执行，粒子经行为表落到高版本纹理（`bubble_pop` 固定 4 tick 寿命 + `bubble_pop_0..4` 按 age 轮播）；状态动画的「时钟原点 = 进入该状态时的实体年龄」，箭身缩放因此在飞行期保持动画末帧、命中后无论远近都从落地动画第一帧开始播（远距离射箭早先看着「正常」只是因为落地晚、采样被钳到末帧）。**仍近似**：弹射物的 `ysm.bone_pivot_abs` 走父链重算（弹射物没有渲染期矩阵捕获），粒子偏移按箭矢的渲染变换（`yaw-90` + `pitch`）换算到世界坐标 —— 与官方相比可能仍有小幅出入。`DEBUG_ANIMATION` 下会输出`[YSMU-PROJ-TL]` 一行/弹射物，说明该模型的活动动画有多少条 timeline 贡献、派发了多少条指令（`active animations carry no timeline instructions` 表示模型没写 timeline）。注意后者的副作用：模型里用**单轴 0** 把部件压成 0 厚度的「卡片」也不再渲染（与官方一致；若某个模型确实想显示 0 厚度卡片，应改成薄立方体）。**光照口径（已确认，不是待修缺陷）**：弹射物按官方平光渲染（只在弹射物渲染期间关掉固定管线定向光，官方同角度实测最暗面 230/255）；**其他模型（含玩家模型）刻意保留 1.7.10 实体 pass 的逐面明暗**（`RenderHelper.enableStandardItemLighting`：环境光 0.4 + 两盏 0.6 平行光 + `GL_FLAT`），法线同时背向两盏灯的面会被压到 0.4（白框实测 101/255）——经确认这样更立体、符合预期，官方那侧更平。若哪天真要对齐官方，做法与弹射物相同（`beginFlatProjectileLighting` 那套），但必须保留 GUI 预览现有光照与 `gui_no_lighting` 语义。`ysm.shoot_item_id` 现在返回**真正射出这支箭的物品**：1.7.10 的 `EntityArrow` 不把发射武器同步给客户端，所以由 `MixinEntityArrow` 在服务端构造箭矢时（弓/弩的 3 参数与生物瞄准的 5 参数两条构造路径）把射手当时的手持物注册名写进第二个 datawatcher，客户端再折算成模型会写的现代 id（`ProjectileShootItemIds`：GTNH 的 `TConstruct:Crossbow` → `minecraft:crossbow`，短弓/长弓/`xxx_bow` → `minecraft:bow`，其余原样返回）。此前该变量恒为空串，模型里 `crossbow = ysm.shoot_item_id == 'minecraft:crossbow'` 永远不成立 —— 弩射出的箭只能显示成弓。**仍需实机确认**：各类第三方下载模型的弹射物显示是否与官方一致；弩（含 GTNH 的 TiCon 弩）射出后是否显示弩的子模型。载具（`files.vehicles`）目前只做了**解析**（`RawYsmModel.vehicles`，文件夹/二进制两个反序列化器都认这个字段），**渲染入口还没接**：没有任何船/矿车/马的渲染 mixin，全仓库也没有代码消费 `model.vehicles`（`EntityBoat` 只出现在"玩家坐船"的动画状态谓词里），所以模型写了载具不会有任何替换效果 —— 这不是"没验"，是"没做"
- **GUI 预览不执行动画控制脚本**：预览实体没有玩家（`RenderUtil.renderEntityInInventory` 明确 `setPlayer(null)`），而 `@player_ctrl_<槽位>.molang` 要读 `ctrl.*`（手持物/输入）才能求值，所以预览里脚本一律不生效。后果：① 只有脚本、没有 `controller/*.json` 的槽位（含具名并行槽位）在预览里不会动，必须在游戏内看；② 同一段 Head/肢体旋转在预览里看起来左右相反 —— 预览用的是原版背包预览的**水平镜像**渲染（`glScalef(-scale, …)`），幅度一致、方向镜像，不是游戏内算错
- `ctrl.set_beginning_transition_length(秒)` 与 `ctrl.indicate_reload` 现在两条路径都生效：注册期静态提取（字面量动画名）与**本帧运行时**结果（动态算出的动画名也算）。wiki 要求 `ctrl.indicate_reload` 在 `ctrl.set_animation` 之前调用才重载同名动画，这条已按规范实现
- `fn.*`（`functions/*.molang` 定义的自定义函数）：wiki 只在 `.molang` 文件内调用它（含函数之间的链式调用与递归，深度上限 32），本模组与规范一致。关键帧表达式与控制器条件表达式不在自定义函数规范内：条件里写 `fn.x` 会提示一次 `Unsupported OpenYSM controller variable: fn.x`，关键帧里会让该条表达式解析失败。wiki 里用来直接执行一个函数的 `/ysmclient molang execute fn.x` **尚未实现**（现在只支持 `load`/`reset`）
- 并行动画支持数字槽位(`pre_parallel0..7` / `parallel0..7`)与模型自己命名的并行控制器(如 `player.pre_parallel_名字`, 由 `controller/*.json` 声明或只由 `@player_ctrl_<槽位>.molang` 声明；映射到备用池 `*_extra_N_controller`，按当前模型路由)。池大小由配置项 `NamedParallelExtraSlots` 决定(默认 8/族、上限 16/族、0 = 关闭)；超出池子的具名槽位不播放（按槽位名小写字母序丢弃末位），会给出一条一次性 WARN（含模型名与完整槽位表），不需要开 DebugController
- **一个槽位可以挂多个控制器**（官方 `ControllerSlotBinder` 对 `^player\.<槽位>(_.+)?$` 的每个名字都注册独立控制器；wiki「动画控制器」2.6.3 写明同组按名称字母序加载、越靠后优先级越高）。八个槽位控制器(`pre_main` / `post_main` / `pre_hold` / `post_hold` / `pre_swing` / `post_swing` / `pre_use` / `post_use`)下的 `player.<槽位>_<后缀>` 状态机由共享备用池 `openysm_slot_extra_N_controller` 承载，与 `@player_ctrl_<槽位>.molang` 控制脚本**并行**运行。修复前控制脚本每帧先返回，会把同槽位的后缀状态机整个短路掉：它的 `on_entry`/`on_exit` 赋值(形态开关那类变量)永不执行、过渡动画不播，模型就停在两个形态掺杂的样子；同一根因还让「一个槽位多个控制器」的模型一次只跑一个(`player.post_main_car_*` 那类整块车辆状态机，修后备箱/车门可以同时开)。池大小由配置项 `SlotExtraControllers` 决定(默认 8、上限 16、0 = 关闭)；池承载的后缀控制器不再从基槽位重播一遍(否则 timeline/音效关键帧会触发两次)，池关掉或放不下时退回旧的「基槽位取第一个匹配」行为，动画不会整体消失。**仍近似**：共享池只有一个注册位置(所有基槽位之后、轮盘 `cap` 之前)，所以 `player.pre_main_*` 的骨骼叠层顺序与 wiki 的组顺序(它把 `player.pre_main_*` 排在 `player.main` 之前)不完全一致；同一后缀同时写了 `@player_ctrl_<槽位>_<后缀>.molang` 与 JSON 控制器时仍是脚本优先(参考库里没有这种模型)
- 并行动画的**特殊混合**（wiki「并行动画」：高优先级并行与低优先级动画在同一骨骼上的**旋转相加**，位移/缩放则是覆盖）**只实现了「覆盖」**：GeckoLib 按控制器注册顺序逐通道写骨骼，后注册的赢。注册顺序已按 wiki 的优先级表固定为 `pre_parallel*`（最低，排在主动画前）→ 主动画/手部/挥砍/使用 → 轮盘 `cap` → `parallel*`（最高，数字越大越靠后）→ 护甲（护甲动画要能把并行设成 0 的护甲组缩放改回 1，见 wiki「护甲动画」），所以**同一骨骼同时被两层以上动画驱动时，旋转取的是最高优先级那一层的值，而不是相加**。要做真正的相加混合需要改 vendored GeckoLib 的骨骼求值（把 `AnimationProcessor` 的逐通道 set 换成按优先级累加旋转），影响面大，暂不做
- `ysm.sync(...)`（`@sync` 事件）此前由网络线程直接执行，且渲染帧之外没有变量作用域，脚本里的 `v.*` 写回会被静默丢弃。已修：下行包经 `Minecraft.func_152344_a` 回客户端主线程，并在执行时带上「发起者玩家 + 该模型」的变量作用域，写回落进该作用域并在下一帧可见。注意仍然：接收端只在本地已加载该模型时触发，参数最多 16 个、**不保证跨客户端的事件时序**；`v.roaming.*` 的写回同时走既有的 pending 漫游通道。限流改成**按参数区分**（客户端与服务端各一层）：参数不变的重复仍每秒一次，参数变化（模型把开关状态交给 sync 传，如某车辆模型的"鸣笛按下/松开"）立刻放行，两层都受每秒 4 次的硬上限约束 —— 上游没有限流，旧行为会把"松开"压到下一秒，表现为长鸣笛一直响。详见 `docs/analysis/ysm-sync.md`
- 关键帧时间轴（timeline）改为**有界调度器**：每个贡献动画按自己的周期计时、不再逐帧展开循环，单帧派发量有上限（过大跳变跳到当前相位而不是补播历史），并在动画真实播放时钟（`anim_time_update`/循环回绕/HOLD 截断之后）上派发。**行为变化**：漫游变量到动画变量的「立即刷新」只重放可证明幂等的赋值（`v.x = <只读 v.roaming.* 的纯复制/算术>`；无函数调用、无自增、无粒子/音效副作用），随机数/自增/粒子这类副作用指令改为按自身周期触发一次。轮盘显隐等漫游驱动效果需实机回归；极端短周期模型的单帧派发量被上限截断，属预期保护
- **只有客户端装 YSMU（服务端没装，甚至原版服务端）时**：同步握手是**服务端发起**的（服务端发 `S2CVersionCheck17` → 客户端回 `C2SVersionCheck17` → 服务端发 `RequestSyncModel`），所以进服时**客户端不会主动发包**，也不会被踢或刷屏；拿不到服务端模型就退回内置 `default`。`/ysmclient load` 把本地 `config/ysmu/{custom,builtin}` 扫到的模型注册给**自己**用（走的是和真实同步**完全相同**的"服务端缓存 → transcode → 客户端缓存 → 解析 → apply"路径 `OpenYsmModelSyncClient.registerLocalModel`，只是不过网络；与已由服务端注册的同名模型会跳过不覆盖），其他玩家看你仍是默认模型。选模型/贴图、轮盘播放这类操作**先在本地生效**（`ModelButton` 写本地 EEP、轮盘置本地 wheel 标志），只是不会持久化/同步给别人。两个注意点：① 收到服务器的同步握手（进服、`/ysm reload`）会清空客户端已注册模型（`prepareForNewSync`），本地加载的模型要重新 `/ysmclient load`；② `ysm.sync` 依赖服务端回声，服务端没有 YSMU 时发出去不会回来，靠它驱动的效果（如联网鸣笛）**静默失效**
- [SKIP] v.roaming长期变量目前不会永久保存
- 部分 `.ysm` 模型在服务端缓存重建时抛 `NoSuchElementException` 解析失败（每次重建均失败），会被跳过但不阻塞加载
- [SKIP] 首次更新构建后启动偶发崩溃（SDL3.dll 异常码 0xc000041d），重开游戏即可恢复，属 lwjgl3ify 上游兼容性问题
- [NOTE] Java 25 + ZGC 下 Distant Horizons 等 mod 可能导致 DirectBuffer 泄漏（Cleaner 未被及时处理），YSMU 提供了 DirectBuffer Watchdog 作为高阈值兜底（默认 1024 MB + 60秒后触发 强制GC），可通过配置关闭
- [NOTE] 目前最新Angelica(angelica-2.1.50.jar)会导致ysm模型受到原版亮度设置的影响 如果觉得太暗 尝试在设置中将原版的亮度滑条设置为100(明亮)



---

## 许可证

### 源代码
MIT

### 模型资源
仓库中自带的模型采用不同协议：
- **默认模型**: [CC0](https://creativecommons.org/publicdomain/zero/1.0/) — 完全开放
- **酒狐 (Wine Fox) 模型**: [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/) — 非商业使用，需署名，相同方式共享

### 第三方代码
1. **GeckoLib**
2. **OpenYSM**
3. **ImageStream WebP 解码器**

### 相关链接
- [OpenYSM](https://github.com/OpenYSM/OpenYSM)
- [YesSteveModel](https://github.com/YesSteveModel/YesSteveModel)
- [GeckoLib](https://github.com/bernie-g/geckolib)
- [GTNewHorizons](https://github.com/GTNewHorizons)
