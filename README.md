# YesSteveModel-Unofficial (YSMU)

**该文档主要由ai生成 虽经人工修正 但不确保准确性**

---

## 概述

YSMU 是一个 Minecraft Forge 1.7.10 模组，将 YesSteveModel 移植回 1.7.10 (主要是给我自己玩gtnh)

---

## 当前状态

**最新版本：`1.9a1-04`**（`feat-ExtraUI` 分支）

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
- **GTNH测试版本**: 2.8.4, 2.9.0beta1

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

### 动画系统
- **动画控制器**：状态机、blend transition、timeline、`on_entry`/`on_exit`
- **并行动画播放**：支持 `parallel_N` 控制器，多动画同时播放
- **攻击连击系统**：`post_swing` 状态机推进 attack1→2→3，支持检测模型武器可见性
- **潜行动画修复**：四种降级路径（MOVE-BLOCK、SKY-REDIRECT、wasMoving、通用回退），修复无 `ground` 状态模型的潜行动画
- **动画合并修复**：`arm.animation.json` 空动画不再覆盖 `main.animation.json` 有骨骼的正常版本
- **Root 骨骼过滤**：非主控制器自动剔除 Root 骨骼动画，防止身体旋转被覆盖
- **额外动画轮盘**：可定制的 8 槽位动画轮盘，支持子菜单导航、翻页
- **骑乘退出检测**：下马时 40-ticks 停止其他控制器，确保过渡动画播放
- **GUI 预览动画**：模型选择界面支持 hover/focus 动画和双控制器预览混合

### Molang 脚本引擎
- **完整 Molang 解析器**：带运算符优先级修复、三元表达式、null-coalescing (`??`) 和赋值操作符
- **高版本桥接**：解析 `.molang` 函数文件，将 `ctrl.set_animation('正常_行走')` 映射为标准状态名
- **变量支持**：
  - `query.*` — 玩家/世界/物品查询函数
  - `ysm.*` — YSM 特有变量（`ground_speed2`、`fps`、`input_vertical/horizontal`、`has_helmet`、`attack_time` 等）
  - `v.roaming.*` — 服务端同步的 roaming 变量（`helmet` 等）
  - `ctrl.hold`/`ctrl.use`/`ctrl.swing` — 完整实现的物品检测函数
- **物理变量注入**：roaming 变量注入到动画关键帧路径

### 音频系统
- **OGG Vorbis 播放**：通过 DirectSound 实现模型音效播放
- **控制器级生命周期**：音效与 GeckoLib 控制器绑定，动画切换/停止时自动清理
- **vanilla 音效回退**：非 OGG 音效通过 `SoundHandler.playSound` 播放

### 配置与 GUI
- **全新配置面板**：右侧面板布局、更大预览、拖拽旋转预览玩家
- **模型选择界面增强**：前景/背景纹理、GUI 动画、foreground/background 贴图渲染
- **配置页面改进**：透明度滑块、显示名称、包文件夹图标
- **翻页/锁定按钮**：动画轮盘锁定、多页导航
- **`/ysm play` 命令**：在游戏中播放指定动画
- **预览刷新频率调整**：FBO 缓存刷新频率 在模型选择(Alt+Y)的设置页面中可以调整模型预览的刷新率 能有效提升预览页面的游戏帧数 但会导致预览动画卡顿
- **HUD 自拍模型 FBO 缓存**：在 Alt+P 配置界面可按 C 切换。开启后 HUD 模型以动态帧率更新（>125fps 时以1/8的频率刷新，62.5-125fps 时缩放至 16-32fps，<62.5fps 时缩放至 0-32fps），大幅降低 HUD 渲染开销；关闭则每帧完整渲染（等效未优化行为）

### 兼容性
- **Backhand 双持**：通过 `BackhandCompat` 隔离, 副手物品正确检测
- **Angelica 光影**：第一人称手臂渲染通过 `AngelicaCompat` + Mixin 分流
- **Et Futurum 鞘翅**：通过 `EtFuturumCompat` 检测鞘翅装备/飞行/滑翔进度
- **Battlegear2 盾牌格挡**：通过 `BlockingCompat` 反射调用格挡检测，支持剑格挡、双持盾牌
- **TiConstruct 十字弩 (GTNH)**：通过 `TinkersCrossbowCompat` 识别弩的装填/加载状态，使 `use_mainhand:crossbow` 拉弦动画和 `hold_mainhand:charged_crossbow` 蓄能待机动画正常工作
- **高版本音效**：通过 `SoundNamespaceCompat` + `LocalAssetProvider` 加载本地高版本 Minecraft 资源包音效（`/ysm setgamepath`）
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

---

## 从源码构建

```powershell
# 克隆仓库
git clone https://github.com/wufe8/YesSteveModel-Unofficial.git
cd YesSteveModel-Unofficial

# 检出活跃开发分支
git checkout feat-ExtraUI

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

- [SKIP] battlegear2的盾牌位置不正确 目前会以物品的位置来握持(实际上就是物品而非工具)
- [FIXED] 目前剑格挡重载为一个静止的使用动画 看起来会像格挡 此外格挡状态 模型可使用query.is_blocking Molang查询
- 部分新版模型可能需要 `.molang` 函数文件桥接才能正常工作
- [SKIP] WebP 解码器基于外部实现, 没搞定纯ImageIO
- 子模型(投射物/载具)仍然存在一些问题 目前仅保证默认模型投射物可用
- [SKIP] v.roaming长期变量目前不会永久保存 可能 wont fix
- 粒子系统未实现
- ysm格式的cube路径与文件夹json的cube路径不同 其poly_mash会丢失size<0的状态 导致本应是负缩放大小的模型无法通过正确翻转法线 来做到内外面翻转/描边的效果
- [SKIP] 首次更新构建后启动偶发崩溃（SDL3.dll 异常码 0xc000041d），重开游戏即可恢复，属 lwjgl3ify 上游兼容性问题



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
