# YesSteveModel-Unofficial (YSMU)

**该文档主要由ai生成 虽经人工修正 但不确保准确性**

---

## 概述

YSMU 是一个 Minecraft Forge 1.7.10 模组，将 YesSteveModel 移植回 1.7.10 (主要是给我自己玩gtnh)

---

## 当前状态

**最新版本：`1.9a1-06`**（`perf/previewUI` 分支）

> [NOTE]
> 项目仍处于 **Alpha 阶段**，部分功能可能不稳定, 欢迎提交 Issue

## 安装

### 环境要求
- **Minecraft**: 1.7.10
- **java**: java8, 17-25(with lwjgl3ify)
- **Forge**: 10.13.4.1614
- **必需 Mod**:
  - [UniMixins](https://github.com/LegacyModdingMC/UniMixins)
  - [GTNHLib](https://github.com/GTNewHorizons/GTNHLib)
- **测试兼容性**: +unimixins-all-1.7.10-0.3.1.jar, angelica-2.1.42.jar, gtnhlib-0.10.9.jar, lwjgl3ify-3.0.25.jar, backhand-1.7.7.jar, modularui2-2.2.18-1.7.10.jar
- **GTNH测试版本**: 2.8.4, 2.9.0beta2

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
- **内置模型**：内置默认模型自动提取到 `config/ysmu/custom`
- **负尺寸 Cube 归一化**：自动归一化 BlockBench 负尺寸 Cube + 移除零 UV 面，提升模型兼容性
- **统一模型同步**：合并 legacy（MD5/AES）与 OpenYSM 两条同步路径为单一路径；服务端内容寻址去重、原子写缓存、侧车索引（二次启动重建降至 ~5s）；大模型库同步索引分块传输（突破 1.7.10 32KB 包限制）
- **按需懒加载**：几何/动画/贴图按需后台加载、空闲自动卸载，显著降低大型模型库的同步峰值内存与显存占用

### 动画系统
- **动画控制器**：状态机、blend transition、timeline、`on_entry`/`on_exit`
- **并行动画播放**：支持 `parallel_N` 控制器，多动画同时播放
- **攻击连击系统**：`post_swing` 状态机推进 attack1→2→3，支持检测模型武器可见性
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
- **调试覆盖层**：`/ysm debug overlay`（快捷键 Ctrl+P）实时显示控制器状态/Molang 变量，支持搜索与过滤；`/ysm debug query <表达式>` 运行时查询变量值
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
- 未实现molnag自定义函数处理
- 子模型(投射物/载具)可能还存在一些问题 目前仅保证默认模型投射物可用
- [SKIP] v.roaming长期变量目前不会永久保存 可能 wont fix
- 粒子系统未实现
- 第一人称下音效播放位置固定在玩家原位置：左右平移时音效会播放在反移动方向侧，存在违和感（音效未跟随玩家移动）
- 部分 `.ysm` 模型在服务端缓存重建时抛 `NoSuchElementException` 解析失败（每次重建均失败），会被跳过但不阻塞加载
- [TODO] 自动化测试覆盖不足：目前仅格式解析测试，模型加载/同步等重构缺少回归测试
- [FIXED] ysm格式的cube路径与文件夹json的cube路径不同 其poly_mash会丢失size<0的状态 导致本应是负缩放大小的模型无法通过正确翻转法线 来做到内外面翻转/描边的效果
- [SKIP] 首次更新构建后启动偶发崩溃（SDL3.dll 异常码 0xc000041d），重开游戏即可恢复，属 lwjgl3ify 上游兼容性问题
- [SKIP] Java 25 + ZGC 下 Distant Horizons 等 mod 可能导致 DirectBuffer 泄漏（Cleaner 未被及时处理），YSMU 提供了 DirectBuffer Watchdog 作为高阈值兜底（默认 1024 MB + 60秒后触发 强制GC），可通过配置关闭
- [SKIP] 目前最新Angelica(angelica-2.1.50.jar)会导致ysm模型受到原版亮度设置的影响 如果觉得太暗 尝试在设置中将原版的亮度滑条设置为100(明亮) wont fix



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
