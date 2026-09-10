# Molang 标识符必须允许数字续接

## 问题

YSMU 自研的两个递归下降求值器（`OpenYsmControllerExpressionEvaluator.parseIdentifier()`、
`ProjectileControllerRuntime.parseIdentifier()`）在读取标识符时漏了
`Character.isDigit(c)`，于是变量名里的数字被当成标识符结束：

    v.flag2   → 解析为 v.flag ，剩下的 2==1 这类内容被静默忽略
    v.reload1 → 解析为 v.reload

所有含数字的变量名都会被截断，而且不会报错——表达式照常求值，只是语义变了。

参考实现用基于词法分析器的完整解析器（`Characters.isValidForWordContinuation()` 含
`isDigit(c)`），所以没有这个问题；这是移植时自研解析器引入的偏差。

## 修复

在两个 `parseIdentifier()` 的字符条件里补 `|| Character.isDigit(c)`（标识符首字符仍要求
字母/下划线，避免把数字字面量吃进变量名）。

## 连带效应（值得记住的一类坑）

截断是**静默**的，因此故障现象看起来完全不像"解析器 bug"：某个控制器里
`v.a==0 && v.a2` 这类条件，因为 `v.a2` 被读成 `v.a`，会在 `v.a` 非零时恒真，导致状态
无限重入（表现为动画/音效卡在某个状态反复触发）。修复后条件恢复正常。

排查这类问题的方法：不要只看表达式文本，直接把解析器的**标识符切分**打印出来对比；
`DebugController=true` 时控制器进入/重入状态机的情况也能侧面暴露"条件恒真"。

## 与模型无关的建模坑（顺带记录）

- 动画 `timeline` 里只写表达式、不赋值的语句（例如 `!v.flag;`）**不会**翻转/设置变量；
  模型作者常误以为它等价于 toggle。真正的兜底需要在 `on_exit` 里显式赋值
  （`v.flag=0`）。
- 控制器的 `on_entry`/`on_exit` 是语句列表，逐条执行；单条语句里用 `;` 分隔的多句也是
  逐句执行（基岩文档的语义），不存在"整段作为一个表达式"的行为。
