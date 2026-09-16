# tools/

辅助脚本集合。**都是可选工具**：不参与构建，不需要随 jar 发布，只用 Python 3 标准库
（`plot_anim_probe.py --png` 会用到可选的 matplotlib）。

新增工具的约定：通用、与路径无关的工具放这里并在本文件登记；一次性的、
写死了本机路径的、只服务于某次排查的脚本放 `local/tools/`（gitignored）。

## 工具

| 工具 | 用途 |
| --- | --- |
| `convert_new_ysm.py` / `convert.md` | 把新版 `ysm.json` 文件夹模型转换为旧版结构（`main.json` + `arm.json` + `*.png`），兼容早期加载路径 |
| `ysm_dump.py` | 解析/导出 `.ysm` 二进制模型（含 `_debug_*` 中间文件），排查模型解析问题用 |
| `filter_ysmu_log.py` | **YSMU 专用**日志筛选：按 `[YSMU-*]` 标签分类、去重、分组，支持 `--model` / `--tag` / `--level` / `--context` |
| `dedupe_mc_log.py` | **通用**日志"行形状"折叠：把只差时间戳/线程/数字的重复行合并计数，并可列出某个标签实际产生过的**所有不同取值**（判断"变量根本没变"时最有用） |
| `plot_anim_probe.py` | 解析 `[YSMU-KF]` / `[YSMU-BONE]` 探针行（探针本身已从源码移除，只能用留档日志；需要重跑就用 `git show c5f3cbb:<文件>` 取回），输出文本统计 + ASCII 图 + CSV + 无依赖 SVG（`--png` 需要 matplotlib） |
| `vendor_imagestream.py` | 生成 ImageStream/WebP 解码相关的 vendored 代码 |
| `scan_named_parallel_slots.py` | 扫描模型目录树（默认 `res`），统计每个模型声明的**具名**并行槽位（`(player.)?(pre_parallel\|parallel)_<非数字>`，来源同运行时：`controller/*.json` 键名 + `<描述>@player_ctrl_<槽位>.molang` 文件名）与**数字**槽位数量，用来判断 `NamedParallelExtraSlots` 该设多大 |

典型用法：

    # 这个标签到底产生过哪些取值？（而不是 2400 行重复）
    python tools/dedupe_mc_log.py .minecraft/logs/latest.log --tag YSMU-SCOPE

    # 只看最后 5000 行里的不同形状
    python tools/dedupe_mc_log.py latest.log --tail 5000

    # YSMU 专用筛选
    python tools/filter_ysmu_log.py --tag YSMU-CTRL --context 2 < latest.log

    # 骨骼轨迹
    python tools/plot_anim_probe.py probe.log --bone <bone-name>

## 排查坑（踩过的，别再踩）

**日志本身**

1. `logs/latest.log` **只是最近一次游戏会话**，重开游戏就被覆盖。任何有价值的现象都要在
   重启前把日志留下（启动器已保留 `logs_<日期>_<jar>.zip`；也可以把 `latest.log` 改名成
   `latest_<日期>_<主题>.log`）。不要拿一份被覆盖过的日志下结论。
2. 日志是 GBK 输出，Windows 下直接读会乱码：`iconv -f GBK -t UTF-8`（PowerShell 用
   `-Encoding UTF8`）。
3. `Config` 里的 `debug` 等级需要启动参数，用户不会去改；探针一律写在 `info` 等级，
   并做频率限制（否则刷屏本身会改变现象）。

**探针与采样**

4. 采样频率会骗人。游戏锁定 20 tps，但快速动画（如 0.02 s 的物理驱动、阶梯式闪电）在
   1 Hz 采样下会被抽样成"永远为 0"。按帧采样，或先确认采样周期远小于动画周期。
5. 探针插入位置要晚于数据填充。放在队列填充之前读到的永远是 `null`（曾把
   `posX=null` 当成解析 bug 追了很久）。
6. 同一帧内同一个模型可能被渲染多次（GUI 预览 + 世界渲染），**后一次会覆盖前一次**；
   看探针要区分是哪一趟、哪个控制器推的动画（打印动画名/控制器名，不要只打印骨骼值）。
7. GeckoLib 的 `AnimationController` 重复提交同一个 `AnimationBuilder` **不会重启**动画；
   反过来，控制器处于 Stopped 时会重新触发 reload 并把 tick 归零 —— 分析"动画卡在
   t=0"时先分清是回退路径（`playLoopAnimation`）还是 OpenYSM 状态机路径。

**模型与构建**

8. 仓库里的 `res/` 只是参考资料，**改它不影响游戏**。运行时用的是
   `config/ysmu/{custom,builtin}` + 客户端缓存；模型改了要重新导入/清缓存。
9. `-dirty` 只表示"有未提交改动"，不代表 jar 是新的。确认版本要看启动日志的
   `I am ysmu at version …`（`Tags.VERSION` = git tag/describe），并比对源文件与
   `build/libs/*.jar` 的时间戳。
10. 模型包里的文件名可能是中文（例如 `animations/主动画.json`）。写脚本时不要只匹配
    `*.animation.json`，应按"目录下所有 `.json`"处理，并注意 Python 的 Windows
    版本读不了 MSYS 路径（`/h/...`），要么用 `H:/...`，要么在 MSYS shell 里 `cd` 后用相对路径。
11. 模型 JSON 很大（单个 `main.animation.json` 可达 8 MB），`grep -o` 递归扫 `local/`
    会超时；用 Python 逐文件解析，或限定 `--include`。
12. javac 的 `@argfile` 遇到超长 `-cp` 行会解析失败；要快速做编译检查，
    直接对着上一次构建的 `build/libs/*-dev.jar` + 少量依赖编译改动文件即可。
13. 探针是**临时**的：问题查清就删掉，源码里只留 `Config.DEBUG_*` 开关 + 频率限制的诊断
    （`[YSMU-SOUND-PROBE]`、`allowDebugLog(tag)`）。骨骼/关键帧类探针写死了具体模型的骨骼名，
    留着只会误导后来人；要重跑用 `git show <sha>:<path>` 取回。
