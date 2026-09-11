# `Negative index in crash report handler (0/N)` 刷屏

提炼自 2026-09 的一次排查（完整证据、逐会话统计与命令留在 `local/analysis/`，见文末）。

## 这行是什么

**不是崩溃，也不是日志框架的问题**：它是原版 1.7.10 `net.minecraft.crash.CrashReport.makeCategoryDepth`
（SRG `func_85057_a`）里的调试 `System.out.println`，唯一含义是"此刻正在构造一份崩溃报告"。

```java
// net.minecraft.crash.CrashReport
public CrashReportCategory makeCategoryDepth(String name, int depth) {
    CrashReportCategory cat = new CrashReportCategory(this, name);
    if (this.field_85059_f) {                       // isStackTraceEnabled
        int j = cat.getPrunedStackTrace(depth);     // 当前线程栈帧数 - 3 - depth
        StackTraceElement[] causeTrace = this.cause.getStackTrace();
        int k = causeTrace.length - j;
        if (k < 0) System.out.println("Negative index in crash report handler (" + causeTrace.length + "/" + j + ")");
        ...
        this.field_85059_f = cat.firstTwoElementsOfStackTraceMatch(...);   // 之后置 false
    }
}
```

由此可读出的四件事：

1. `(0/45)`：`0` = 报告 cause 的**栈帧数**，`45` = 裁剪后的当前线程栈长（即构造分类时线程共 49 帧）。
   `depth` 反映的是 **catch 现场**的栈，所以只能粗分"哪条路径"，不能唯一定位。
2. **一份报告只打一行**（第一次分类后 `field_85059_f` 置 false）。所以"刷屏 N 行"= 真的每帧新建了 N 份
   `CrashReport`，即有一个异常正在被**每帧抛出并吞掉**。
3. **游戏不崩**：`Minecraft.run()` 对 `ReportedException` 的处理是打 `FATAL "Reported exception thrown!"`
   → 弹崩溃屏 → 写 `crash-*.txt` → 退出。这三样都不出现，说明 `ReportedException` 在更早的地方被吞了。
4. cause 栈帧为 `0` 是 JVM `-XX:+OmitStackTraceInFastThrow`（默认开启）的特征：同一处字节码反复抛同一隐式
   异常后，HotSpot 改用预分配的"无栈"异常实例。用 `-XX:-OmitStackTraceInFastThrow` 启动可以让它重新带上栈
   （注意：只有 cause 栈长 > `j` 时该行才会消失，"没消失"不能推翻推断）。

## 出现时先做三件事

```bash
# 1) 量：刷屏行数与速率（逐秒）
zgrep -c "Negative index in crash report handler" latest.log
awk '/Negative index/{print $1}' latest.log | uniq -c | sort -rn | head

# 2) 有没有走到 FML 的致命路径（有 = 另一类问题：真的崩了）
grep -c "Reported exception thrown!" latest.log
ls -t crash-reports/ 2>/dev/null | head
```

3. 记下**正在跑的构建**（启动日志的 `I am ysmu at version …`）与复现窗口，再决定二分范围：刷屏紧跟在某条
   自己的日志之后（例如模型同步完成）时，优先怀疑那条路径上 `Minecraft.func_152344_a` 调度的任务。

同一批日志里的 `crash-*.txt` 可能是**无关**的另一个 mod 的问题，先单独读它，不要默认有因果关系。

## 修法原则

1. **每帧会被调用的入口不允许把异常抛给 vanilla**：统一 `catch (Throwable)` + 一次性 WARN（沿用
   `[YSMU-RENDER] … suppressed` 模式），并按调用点（tag / 栈首帧）去重，便于下次定位。
2. **`catch (Exception)` 兜不住 `Error`**：`NoSuchMethodError`/`LinkageError`/`ExceptionInInitializerError`
   会漏出去，而它们恰恰是每帧重试刷屏的典型来源。这类 `Error` 应当"一次性 WARN + 禁用该模型/渲染器"。
3. **不要把 vanilla 的 catch 当兜底**：它会在每帧构造整份 `CrashReport`（栈遍历 + 对象分配），
   既是 CPU/GC 浪费，也真的可能把游戏打崩。

验收：刷屏行数归零；连续操作 5 分钟（进世界 / 开关预览与场景 / 切换模型 / 第一人称与第三人称）仍为 0；
若确实还有异常，应只看到一次性 WARN，而不是每秒几十行。

## 已知案例与状态

- 2026-08/09 在 07.1 上观测到约 78 行/秒（≈ 每渲染帧一次），回归窗口 `e9ea75e..9e8572b`
  （怀疑其中的模型同步改动 `cd19fe4`）。
- 2026-09-10 用**完全相同的二进制**复跑未复现 → 暂按偶发/环境相关处理。**未复现 ≠ 已修复**。
- 若再次出现：按上文定位，`local/analysis/ysmu-negative-index-spam-diagnosis.md`（417 行）留了完整证据、
  每帧入口清单、bisect 顺序与三种定位方法（spark / 临时 Mixin hook / 最小复现）。

参考：原版已知现象 [MC-99899](https://mojira.dev/MC-99899)。
