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
| `spark_dump.py` | 读 spark 采样数据（`https://bytebin.lucko.me/<code>` 的原始 `application/x-spark-sampler`），自己算 self（独占）时间并打印调用树 / 调用路径 / 按类·按包的 self 排行。**不要用网页版数字**，见"排查坑" |
| `gc_log_summary.py` | 汇总 JVM 统一 GC 日志（`-Xlog:gc*`）：GC 次数、STW 暂停总时长/最长、**分配速率**（按暂停回收量估算）、Full GC 后的存活堆。内存优化的"churn 侧"就看它 |
| `heap_hist_diff.py` | 对比两份 `jcmd <pid> GC.class_histogram` 的**存活集**直方图：按类给 Δbytes/Δobjects，并标出新增/消失的常驻结构。内存优化的"常驻侧"就看它 |
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

    # spark 采样：先拿原始数据（网页 HTML 只是 viewer 壳，没有数字）
    curl -s -H "Accept-Encoding: identity" https://bytebin.lucko.me/<code> -o p.sparkprofile
    python tools/spark_dump.py p.sparkprofile --meta
    python tools/spark_dump.py p.sparkprofile --thread Client --class-self --package-self --top 40
    python tools/spark_dump.py p.sparkprofile --thread Client --paths "<方法名正则>"   # 谁调用的
    python tools/spark_dump.py p.sparkprofile --thread Client --focus "<方法名正则>"   # 子树

    # 内存：churn（分配速率/暂停）与常驻（Full GC 后的存活集）分开量，别混着比
    java -Xms4G -Xmx4G -XX:+UseG1GC -Xlog:gc*:file=logs/gc.log:time,uptime,level,tags:filecount=5,filesize=20M ...
    python tools/gc_log_summary.py logs/gc.log --per-minute
    jcmd <pid> GC.class_histogram > local/logs/live-<版本>.txt
    python tools/heap_hist_diff.py local/logs/live-旧.txt local/logs/live-新.txt --only com.fox.ysmu

## 排查坑（踩过的，别再踩）

**日志本身**

1. `logs/latest.log` **只是最近一次游戏会话**，重开游戏就被覆盖。任何有价值的现象都要在
   重启前把日志留下（启动器已保留 `logs_<日期>_<jar>.zip`；也可以把 `latest.log` 改名成
   `latest_<日期>_<主题>.log`）。不要拿一份被覆盖过的日志下结论。
2. 日志是 GBK 输出，Windows 下直接读会乱码：`iconv -f GBK -t UTF-8`（PowerShell 用
   `-Encoding UTF8`）。
3. `Config` 里的 `debug` 等级需要启动参数，用户不会去改；探针一律写在 `info` 等级，
   并做频率限制（否则刷屏本身会改变现象）。

**采样与剖析**

4. 采样频率会骗人。游戏锁定 20 tps，但快速动画（如 0.02 s 的物理驱动、阶梯式闪电）在
   1 Hz 采样下会被抽样成"永远为 0"。按帧采样，或先确认采样周期远小于动画周期。
5. spark 网页上的 "CPU 时长" 是按**类目包含求和**的：同一方法在不同调用点会被累加，
   所以 `java.lang.reflect.Method.invoke` 能显示成 200%。判断"到底贵在哪"必须从调用树
   重算 **self（独占）时间**（`spark_dump.py` 做的事；全树 self 之和 = 根节点总时间，可自校验）。
   反过来，要衡量"某个子系统总开销"才用 inclusive，并且要说明它是几处调用点之和。
6. 采样数据要用 `curl` 从 `bytebin.lucko.me` 取原始 protobuf（`Accept-Encoding: identity`）；
   `spark.lucko.me/<code>` 返回的是 Next.js viewer 壳，里面没有数字。
7. **`metadata.number_of_ticks` 是游戏 tick，不是帧数**。它 ≈ 时长 × 20（MC 的 tick 上限），
   所以拿它当帧数去算"每帧 ms / fps"会离谱地错。第一次踩坑时把 496 tick / 24.8 s 读成
   "496 帧、20 fps"，而实际帧率是 300–500，所有"每帧"数字都大了 20 倍以上。
   **spark 的 profile 里没有帧数**：帧率要另外用 F3 记，或者干脆只用百分比。
8. `SamplerData.time_windows` 是**按墙钟分钟**切窗口（`ProfilingWindowUtils.WINDOW_SIZE_SECONDS = 60`），
   多个窗口 = 采样跨过了分钟边界，**不是**"某段操作前/后"。想按操作分段只能自己记时间戳
   （或分成两次采样）。
9. 1.7.10 的栈帧里 MC/Forge 方法是 **SRG 名**（`func_71411_J`、`func_71361_d`），不是 MCP 名。
   要对照源码就去 `build/rfg/mcp_patched_minecraft-sources.jar`（或 `build/rfg/minecraft-src/`）
   反查。例：`Minecraft.func_71361_d` = `checkGLError(String)`，`RenderGlobal.func_147589_a` = `renderEntities`。
10. 采样里出现大块 **native/驱动调用 self 时间**（`glGetError`、`glIsEnabled`、`glDrawArrays`）
   时，先想清楚它是"CPU 在算"还是"驱动在同步点等 GPU"。4K 下二者能差 20% 以上帧时间；
   要归因 CPU 就把分辨率降下来重采一次。
11. spark 的 Java 采样器**只记时间不记调用次数**，"每帧调用了几次"一律推不出来；
   要次数得自己插计数器探针。
12. 探针插入位置要晚于数据填充。放在队列填充之前读到的永远是 `null`（曾把
    `posX=null` 当成解析 bug 追了很久）。
13. 同一帧内同一个模型可能被渲染多次（GUI 预览 + 世界渲染），**后一次会覆盖前一次**；
    看探针要区分是哪一趟、哪个控制器推的动画（打印动画名/控制器名，不要只打印骨骼值）。
14. GeckoLib 的 `AnimationController` 重复提交同一个 `AnimationBuilder` **不会重启**动画；
    反过来，控制器处于 Stopped 时会重新触发 reload 并把 tick 归零 —— 分析"动画卡在
    t=0"时先分清是回退路径（`playLoopAnimation`）还是 OpenYSM 状态机路径。

**模型与构建**

15. 仓库里的 `res/` 只是参考资料，**改它不影响游戏**。运行时用的是
    `config/ysmu/{custom,builtin}` + 客户端缓存；模型改了要重新导入/清缓存。
16. `-dirty` 只表示"有未提交改动"，不代表 jar 是新的。确认版本要看启动日志的
    `I am ysmu at version …`（`Tags.VERSION` = git tag/describe），并比对源文件与
    `build/libs/*.jar` 的时间戳。
17. 模型包里的文件名可能是中文（例如 `animations/主动画.json`）。写脚本时不要只匹配
    `*.animation.json`，应按"目录下所有 `.json`"处理，并注意 Python 的 Windows
    版本读不了 MSYS 路径（`/h/...`），要么用 `H:/...`，要么在 MSYS shell 里 `cd` 后用相对路径。
18. 模型 JSON 很大（单个 `main.animation.json` 可达 8 MB），`grep -o` 递归扫 `local/`
    会超时；用 Python 逐文件解析，或限定 `--include`。
19. javac 的 `@argfile` 遇到超长 `-cp` 行会解析失败；要快速做编译检查，
    直接对着上一次构建的 `build/libs/*-dev.jar` + 少量依赖编译改动文件即可。
20. 探针是**临时**的：问题查清就删掉，源码里只留 `Config.DEBUG_*` 开关 + 频率限制的诊断
    （`[YSMU-SOUND-PROBE]`、`allowDebugLog(tag)`）。骨骼/关键帧类探针写死了具体模型的骨骼名，
    留着只会误导后来人；要重跑用 `git show <sha>:<path>` 取回。

**内存（和 CPU 采样一样有口径坑）**

21. **堆直方图有两条完全不同的路，先分清再比**：
    - `jcmd <pid> GC.class_histogram` —— **会先做一次 Full GC，只数可达对象**，这是"常驻占用"，
      跨版本可比（`tools/heap_hist_diff.py` 比的就是它）；
    - `jcmd <pid> GC.class_histogram -all` —— 不回收、**连不可达对象一起数**，
      它其实在量"分配速率"（同一份构建换个时刻能差一倍以上）。
    实测（JDK 25，一个一边造垃圾一边持有 50MB 的进程）：默认 53.8M / 79,716 个对象，
    `-all` 137.0M / 84,161 个对象，多出来的 83M 全是当时还没被回收的垃圾。
    **VisualVM sampler 导出的直方图属于后者**（所以能看到 `jdk.internal.vm.FillerElement[]`
    这种 TLAB 填充、以及成百万个"上一帧刚被换掉"的动画队列 —— 2026-09-27 那份 3.45 GiB 的快照就是）。
22. **不要比"当前堆 / 峰值堆"**：那是 GC 时机、`-Xmx`、ergonomics 的产物，不是代码的性质。
    要比的是三件事：① Full GC 后的**存活集**（直方图）；② **分配速率**与每秒 GC 次数
    （GC 日志 / JFR 分配采样）；③ **STW 暂停总时长**（用户真正感觉到的卡顿）。
    帧率另外用 F3 记 —— CPU/内存优化最终都只体现在帧率上（和坑 5 同源）。
    A/B 两次测量请**固定 `-Xms` 与 `-Xmx`、固定 GC、固定场景脚本与时长**，最好等世界/模型加载
    稳定 60s 后再开始，并在同一个时刻（例如预览页静置 10 秒后）取存活集。
23. **不同 GC 之间的内存数字不可比**：ZGC（尤其 JDK 21+ 分代 ZGC）会主动把内存要满、
    日志事件名也不同；G1 的"回收量/暂停"更适合看 churn。要 A/B 就固定一种（目前用 G1），
    ZGC 只在"玩家实际用什么"这一层单独确认。
24. **JFR 的 `jdk.ObjectAllocationSample.weight` 不可全信**：它是"距上次采样之间分配的字节数"的估算，
    在分配速率极高时会由个别样本垄断 —— 实测一次 69s 的录制里，**单个 `Double` 样本的 weight
    就有 171.5 GB，占总权重 217.9 GiB 的 68%**，于是 `jfr view allocation-by-class` 报出
    "`java.lang.Double` 占 79.67%"，而按**样本数**（1,156/18,982 = 6.1%）与瞬时直方图的垃圾构成
    （Double 48 MiB / 总量 1797 MiB = 2.7%）看都只有几个百分点。
    结论：**用样本数或 `jfr view allocation-by-site` 的调用点排序做归因，再用瞬时直方图交叉验证**，
    不要直接引用 weight 百分比。`jfr print --events jdk.ObjectAllocationSample --stack-depth 8` 的输出
    可以按调用点自行聚合（帧行格式是 `method(...) line: N`，`...` 表示被截断）。
25. 想在 VisualVM 里快速拿到**近存活集**的视图：进入固定场景 → 静置 10 秒 →
    点 Sampler 里的 **Perform GC** → 立刻导直方图。等价于 `jcmd GC.class_histogram`
    （记得别在 GC 前那一瞬间导，那份是含垃圾的）。
26. **交 jar 之前必须确认它是 reobf 过的**。增量构建（`build -x test`，以及中途跑过的
    `compileJava --rerun-tasks`）实测会让 `build/libs/<name>.jar` 变成**未 reobf** 的 dev 类：
    MC 成员名仍是 MCP 名，进游戏就在第一次用到时抛 `NoSuchFieldError`/`NoSuchMethodError`。
    2026-09-28 那次"加载期崩溃"就是这么来的 —— `GeoReplacedEntityRenderer.<init>` 里
    `this.renderManager = RenderManager.instance` 没被改成 `field_76990_c`，在
    `new CustomPlayerRenderer()` 直接抛 `NoSuchFieldError`；更坑的是它又被下面第 27 条的
    log4j 问题盖成了 `NoClassDefFoundError: ...RendererLivingEntity`，看起来像另一回事。
    一行自检（应为 `field_76990_c`；出现 `renderManager` 就是没 reobf）：

    ```bash
    unzip -p build/libs/<name>.jar software/bernie/geckolib3/geo/GeoReplacedEntityRenderer.class > /tmp/c.class
    javap -p -c /tmp/c.class | grep -m1 putfield
    ```

    更通用的一条（对任意类都适用，输出应为 0）：

    ```bash
    javap -p -c -classpath build/libs/<name>.jar software.bernie.geckolib3.geo.GeoEntityRenderer \
      | grep -cE "Field net/minecraft/[a-zA-Z/]*\.[a-z][a-zA-Z]*:"
    ```

    出问题就用 `./gradlew clean build`（已实测 4 分钟、26 个 task 全跑）重新出一份。
27. **FML 记录 mod 初始化失败时，报错本身会被 log4j 顶掉**。FML 走
    `FMLLog.log(Level, Throwable, ...)` → log4j 的 `ThrowableProxy` 会按异常栈里的类名去
    `loadClass` 解析 package data；栈里只要有运行世界的 MC 类，RFB 的
    `RfbSystemClassLoader.getClassBytes(name)` 就会因为"按运行世界类名找资源、而 jar 里只有
    混淆名"（`boh.class` 在、`net/minecraft/.../RendererLivingEntity.class` 不在）抛
    `ClassNotFoundException: Class bytes are null for ...`，把真实异常整条替换掉 —— 于是
    crash report 里只剩这条二次错误，`Potion`/`EffectRenderer`/`RenderArrow` 这些同签名崩溃
    都是这个成因，跟被点名的类无关。
    取真实异常的办法：在可能抛出的入口外面 `try { ... } catch (Throwable t) { t.printStackTrace(); throw ... }`
    —— `printStackTrace` 只写文本、不做类解析，会以 `[STDERR]` 明文落进 `latest.log`
    （`ClientProxy.init` 上临时加过一次，见 commit `ea17b8a`）。
28. **`int[]` 缩回不是"只在我们自己的 draw 里"发生的**。1.7.10 补丁版 `Tessellator.draw()` 里那段
    "容量 > 0x20000 且没用满 1/8 就缩回 256 KiB"（`rawBufferSize = 0x10000; rawBuffer = new int[…]`）
    在本环境里**实际执行的是 Angelica 那份**：`TessellatorStreamingDrawer.draw()` 里有同一段
    （Angelica 的 Mixin 接管了 `draw`）。判别方法：JFR 里 `Tessellator.func_78381_a` 从不作为
    分配点出现，而 `TessellatorStreamingDrawer.draw:<行号>` 是 20~30% 的样本。
    因为 `rawBufferIndex <= rawBufferSize < (rawBufferSize << 3)` 恒成立，这个条件等价于
    "**容量超过 512 KiB 就缩**"，与用量无关 —— 所以"把我们自己的 draw 包起来、事后把大缓冲装回去"
    这类做**必然无效**：预览页里每次 `FboCache.draw`（模型按钮的 FBO 烘焙）和 GUI/字体的 draw 都走
    同一个共享实例、又不在包裹范围内，装回去的大缓冲立刻又被它们缩掉，下一批继续
    `Arrays.copyOf` 长回来（实测 `Arrays.copyOf <- Tessellator.func_78377_a <- IGeoRenderer.renderCube`
    占 49.6% 样本）。
    可行的做法是**让容量永远不超过 0x20000**：我们自己提交顶点时按用量切批
    （快满先 `draw()` + `startDrawing()`），并在 draw 前把容量写回 ≤ 0x20000（只写 int、不换数组）。
    另一个量级提示：这类多 MB 的 humongous `int[]` 分配会带来**秒级 young GC 暂停**
    （实测 1155s 处 3.998s、959s 处 2.792s），比吞吐更值得优先处理。
