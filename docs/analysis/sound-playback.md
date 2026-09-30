# 音效播放链路与两个坑

## 链路（`YSMSoundManager.playSound`）

模型音效的查找顺序，任一步成功即返回：

1. **模型自带 OGG**：`SOUND_SOURCES` 按 `modelId::name` 隔离，首次播放时从加密客户端缓存按需解密（不落盘明文）。
2. **1.7.10 SoundHandler**：**必须先确认注册表里真有这个名字**（见坑 1）。
3. **命名空间翻译**：`SoundNamespaceCompat.resolve` 把 `minecraft:x` 映射到本模组注册的命名空间。
4. **本地高版本资产**：`LocalAssetProvider.resolveSound` 按 `sounds.json` 取条目（多个变体随机选一个）→ `assets/objects/<hash>` → 硬链接成 `config/ysmu/cache/sounds/<hash>.ogg`（paulscode 的 CodecJOrbis 按扩展名选编解码器，无扩展名的资产必须先补名）→ paulscode SoundSystem `newSource/setPitch/setVolume/play`。
5. 全部失败 → 一条 `Unable to play unknown soundEvent` WARN。

## 坑 1：不要用字面量名字反射 MC 的内部字段（reobf 后会改名）

`soundExistsInHandler()` 原来写的是 `SoundManager.class.getDeclaredField("soundRegistry")`。模组在发布时会被 reobfuscate，运行时字段是 **SRG 名**，这个字面量**永远找不到** → 走 catch → 当时为了"保守" `return true` → 于是**每一个**音效名（包括 1.7.10 根本没有的高版本名，如 `minecraft:item.trident.throw`）都被推给 vanilla handler，每次都打一条 `Unable to play unknown soundEvent: …`。

修法：按**泛型类型**找字段（`Map<String, SoundList>`），MCP/SRG 两种命名都成立；注册表读不到就返回 `false`（不要假装存在，那只会换来一条 WARN）。同一个教训适用于任何"用字符串反射 MC 内部成员"的代码。

## 坑 2：同一状态内的动画变体切换会重放 t=0 的音效关键帧

见 [`animation-variant-switch.md`](animation-variant-switch.md)：状态名不变、只换条件动画（`attack_idle_1` ↔ `attack_1`）时，运行时保留播放位置，而新变体的关键帧对象是全新的，于是 t=0 的音效又响一次；另一侧 `tryApplyController` 还会把刚起播的音效停掉（"响两遍"或"响一半"）。

## 资源重载后的句柄失效（已修）

`resolveSndSystem()` 原来把 SoundSystem 句柄**永久缓存**在 static 里，而 vanilla
`SoundManager.loadSoundSystem()` 每次资源重载都会**换一个新实例**（`SoundManager.java:117`；
`unloadSoundSystem()` 只 `cleanup()`，不置空字段，:178-184；`SoundHandler.onResourceManagerReload`
→ `reloadSoundSystem`）。重载之后缓存的句柄指向已 clean 的库：`newSource`/`play` 正常返回、
**没有任何报错、永远没声音**，而且不会自愈（只有 `clear()` 会重置）。

现在 `resolveSndSystem()` 每次调用都**重读** vanilla 的字段（两个反射 `Field` 只在首次成功时缓存，
每 tick 成本是一次 `Field.get`）：

- 实例变了 → 重新绑定并打一条 `[YSMU-SOUND] SoundSystem was replaced (resource reload) — rebinding`；
- 字段暂时读不到（重载过程中/映射不符）→ 继续用旧句柄，等新实例出现再绑定；
- 找不到 SoundSystem 字段 → 放弃发现（不每 tick 重扫），退回旧行为。

实测背景：一次静音排查时探针显示 `matchesManager=true`（首次解析拿到的就是活实例），
所以它不是那次静音的成因（那次是坑 1），但 F3+T／换资源包之后确实会踩到，因此仍然要修。

## 排查用诊断（`DebugSound`，常驻）

`YSMSoundManager` 里 `[YSMU-SOUND-PROBE]` 那几行是**常驻诊断**，不是临时探针：`DebugSound=true`
（默认 `false`）就会打开，其中活跃源是每 20 tick 一次，其余为每次停止事件一行。它保留的理由是
paulscode 的 `SoundSystem` 在 1.7.10 里是唯一能回答"这个音源到底在不在播"的地方：

- 停止路径（`stopSound`/`stopController`/`stopAll`）到底停了什么 —— 这条链路原本**一行日志都没有**；
- 每 20 tick 一次的活跃源状态（`soundName->srcName(playing)`）；
- 所有诊断行带 `+毫秒 t=tick`：MC 日志时间戳只有秒级，判断"是否下一 tick 就被停"必须靠它。

原来的 `matchesManager`（缓存句柄是否仍是 SoundManager 当前实例）随上一节的修复**已删除**：
句柄现在每次调用都重读，不可能过期，这个检查只会永远输出 `true`。其余临时探针（骨骼/关键帧/
控制器 tick）在多轮排查后已从源码移除，需要时用 `git show c5f3cbb:<文件>` 取回。

一次**正常**的挥剑应当看到：`playSound` → `local asset` → `playing … as ysm_N`，**没有** vanilla WARN；`stopSound` 只在下个攻击阶段开始时出现（约 0.45 s 后），而不是同 tick。
