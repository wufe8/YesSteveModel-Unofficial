# YesSteveModel-Unofficial (YSMU)

**该文档主要由ai生成 虽经人工修正 但不确保准确性**

---

## 概述

YSMU 是一个 Minecraft Forge 1.7.10 模组，将 YesSteveModel 移植回 1.7.10 (主要是给我自己玩gtnh)

---

## 下载与更新

下载、版本说明和已知限制统一见 [GitHub Releases](https://github.com/wufe8/YesSteveModel-Unofficial/releases)。

项目处于 **Alpha 阶段**，部分功能可能不稳定；遇到问题请附游戏版本、模组版本与日志提交 Issue。

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
- **GTNH 兼容性**：确认整合包中的 GTNHLib 满足上述要求；具体版本的实测环境见对应 Release。

### 安装步骤
1. 安装 Forge 1.7.10（推荐 10.13.4.1614）
2. 将 UniMixins、GTNHLib 放入 `mods` 目录
3. 从 [Releases](https://github.com/wufe8/YesSteveModel-Unofficial/releases) 下载 YSMU 放入 `mods` 目录
4. 启动游戏

---

## 主要功能

### 模型与资源
- **两种模型格式**：旧版文件夹（`main.json` + `arm.json` + `.png`）与 `.ysm` 二进制；新版 `ysm.json` 包、format 32 二进制、1.21.0 几何格式，含加密包内的 WebP 贴图解码
- **统一同步 + 按需懒加载**：客户端/服务端单一路径同步（内容寻址去重、原子写缓存、分块索引以突破 1.7.10 的 32KB 包限制）；几何/动画/贴图按需后台加载、空闲自动卸载
- **兼容性归一化**：负尺寸 Cube 归一化、零面积 UV 面移除、空动画不再覆盖正常版本
- **内置模型**：默认模型与 `wine_fox` 包自动提取到 `config/ysmu/builtin`（可选用不含 `wine_fox` 的瘦身 jar）

### 动画
- **控制器与并行动画**：状态机、blend transition（`AnimationTransitionTicks`，模型自带值优先）、timeline、`on_entry`/`on_exit`；数字与**具名**并行槽位（池大小可配置），高优先级 `parallel*` 对同一骨骼的旋转做相加
- **额外动画轮盘**：条目数量不限（每页 8 个，前 8 个可绑定快捷键 `extra0`..`extra7`），支持子菜单与翻页
- **预览**：模型选择界面（Alt+Y）三栏布局（动画列表 + 3D 预览 + 贴图），支持暂停/复位/地面切换/拖拽旋转；HUD 自拍模型支持 FBO 缓存与跟随模式（Alt+P）
- **状态与兼容修复**：攻击连击、剑/盾格挡条件动画、潜行动画四条降级路径、步幅匹配防滑步（默认关）、Root 骨骼过滤、骑乘退出检测

### Molang
- **自研解析器与解释器**：运算符优先级、三元、`??`、赋值；`.molang` 自定义函数（`fn.*`、`args[]`、`t.*`、`loop`/`for_each`、递归）、事件订阅（`@player_init`/`@player_update`/`@sync`）与 `@player_ctrl_<槽位>` 动画控制脚本
- **函数与查询**：`query.*`（物品/方块/耐久/格挡/相机…）、`ysm.*`（`fps`、`ground_speed2`、`effect_level`、粒子、音效…）、`ctrl.*`（`hold`/`use`/`swing`/`armor`/`reset`/`set_animation`…）；关键帧、timeline、脚本与控制器条件共用同一套求值
- **变量作用域**：`v.roaming.*`（服务端同步）与 `v.*` 按模型隔离，跨模型不再串值

### 音效
- OGG Vorbis 播放与控制器级生命周期；播放状态与音效按**玩家**归属隔离；高版本资源包音效回退
- **音效内存缓存**：模型音效不再明文落盘（原写 `SOUND_CACHE/*.ogg`），改为内存缓存 + 后台预暖，首播无卡顿、磁盘不泄漏明文

### 配置与 GUI
- **配置面板与选择界面**：右侧面板布局与拖拽旋转预览、透明度/显示名称/包图标、前景背景贴图、轮盘锁定与多页导航
- **预览刷新率可调**：默认「自动」（整页共享预算，按每帧实际重画成本分配），另有「静止」与固定「每 N 帧」档；固定档更平滑但更吃 GPU
- **HUD 自拍模型 FBO 缓存**：Alt+P 界面按 C 切换；按**速率 + 预算**更新（目标 12-120 Hz，整块预览最多占 15% 墙钟时间），关闭则每帧完整渲染
- **调试**：`/ysm play`、`/ysm debug overlay`（Ctrl+P，显示控制器状态/Molang 变量及其来源模型）；`/ysm debug query <变量> [变量2 ...]` 查询变量，`/ysm debug eval <表达式>` 求值表达式
- **显存与内存**：`TextureVramBudget`（默认 256MB，超预算按 LRU 整模型释放 GPU 纹理）与 `TextureTargetSize` 降采样；`HiddenOffhandItems` 副手隐藏名单；DirectBuffer 看门狗（`/ysm buffer` 查看）
- **渲染防御**：每帧入口接住 `Throwable` 并去重告警，单个模型的问题不再刷屏或反复触发崩溃报告

### 兼容性
- **可选模组**（全部**能力探测**、缺失即降级）：Backhand 副手、Angelica 光影、Et Futurum 鞘翅、Tinkers' Construct 十字弩、Battlegear2 盾牌格挡、Baubles/Baubles-Expanded 饰品查询、高版本资源包音效（`/ysm setgamepath`）
- **必需**：UniMixins + GTNHLib（Minecraft 1.7.10 / Forge）

---

## 从源码构建

```powershell
# 克隆仓库
git clone https://github.com/wufe8/YesSteveModel-Unofficial.git
cd YesSteveModel-Unofficial

# 如需构建特定发布版，先检出对应 tag
# git checkout <tag>

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

### `ysm.json` 模型包
同一个 `config/ysmu/custom/<文件夹>` 也可以是 YSM 2.5+ 的模型包（内置 `default` 就是这种结构）：
- 根目录必须有 `ysm.json`（文件名和位置固定，UTF-8 无 BOM）；
- 几何/动画/控制器/贴图由 `ysm.json` 的 `files` 段引用，因此不必叫 `main.json`/`arm.json`，也可以放在任意子目录；
- 一个文件夹里可以放多个模型子目录或 `.ysm` 文件；
- 再加 `ysm-pack.json`（包名/描述/多语言）和 `ysm-pack.png`（封面，宽高比 52:90）就是一个模型包，选择界面会先显示包、再进入包选模型。

结构以官方文档为准：[项目结构](https://ysm.cfpa.team/wiki/struct/)、[模型打包](https://ysm.cfpa.team/wiki/model-pack/)。包内嵌的 `.zip`/`.7z` 归档暂不支持，请解压成文件夹。

### `.ysm` 二进制模型
将 `.ysm` 文件放入 `config/ysmu/custom/`, 支持新版 format 32 格式(有问题详细描述issue)

---

## 已知问题

> 下面是通用功能边界；各版本的故障、修复与实测环境以 [Release 说明](https://github.com/wufe8/YesSteveModel-Unofficial/releases) 为准。

- 载具（`files.vehicles`）只解析、未接渲染：模型写了载具没有替换效果。
- GUI 预览不执行动画控制脚本，且预览是水平镜像渲染（左右与游戏内相反）。
- 本机轮盘锁对远端 EEP 动画的移动打断仍是全局行为。
- 弹射物 `ysm.bone_pivot_abs` 与粒子偏移是近似换算。
- 只有客户端装 YSMU 时，收到的同步握手会清空已注册模型；`ysm.sync` 静默失效。
- 部分 `.ysm` 在服务端缓存重建时抛 `NoSuchElementException`（跳过，不阻塞加载）。
- `/ysmclient load` 只添加启动扫描时发现的本地模型，不支持运行中本地源的增删改刷新；手动清理缓存不等于重载模型源，操作语义见 [模型更新与清理缓存](docs/analysis/model-cache-refresh.md)。
- 1.7.10 无对应能力的群系标签、饰品槽位或物品标签查询会明确警告并返回 false/0，不等于完整支持高版本标签系统；`ysm.texture_name` 恒为 0，依赖贴图名的条件判断可能导致部件错误显示或隐藏。
- 大型模型库首次同步、客户端缓存完全未命中时，缺失文件请求可能超过 1.7.10 的上行包限制，导致同步失败；下行索引分块不能消除此限制。
- `v.roaming` 长期变量不会永久保存。
- battlegear2 盾牌位置不正确（以物品位置握持）。
- WebP 解码器基于外部实现，未走纯 ImageIO。
- 首次更新构建后启动偶发崩溃（SDL3.dll `0xc000041d`），重开即可，属 lwjgl3ify 上游问题。
- Java 25 + ZGC 下 Distant Horizons 等可能导致 DirectBuffer 泄漏（有看门狗兜底，可关）。
- Angelica 2.1.50 环境曾观察到模型受原版亮度影响（可将亮度滑条调到 100）；不据此推断其他版本的表现。

**机制与设计取舍**见 `docs/analysis/`：[Molang/控制脚本](docs/analysis/molang-custom-functions.md)、[控制器顺序与并行相加](docs/analysis/animation-controller-priority.md)、[`ysm.sync`](docs/analysis/ysm-sync.md)、[`v.roaming` 作用域](docs/analysis/roaming-variable-scope.md)。

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
