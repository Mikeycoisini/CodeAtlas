# N01 控制流后继与终止语义修复报告（2026-11-17）

## 基线

- 代码：`codeatlas-0.4.6.jar`（修复前 33,342,055 字节 / `3D0D5EAD…`）。
- 问题位置：`dev.codeatlas.analysis.FlowService.Builder`。
- 修复位置：新增 `dev.codeatlas.analysis.ControlFlowBuilder`，`FlowService` 改为调用该构建器。

## 原因

原 `statements` 在每条语句后把 `cursor` 赋为 `part.entry`。`if`、循环、`try`、`switch` 的入口和出口不同，导致下一条普通语句从控制结构入口接出，忽略真实出口；`return`/`throw`/`break`/`continue` 也没有区分正常出口、终止出口和跳转。

## 复现

修复前使用固定源码：

```java
if (ok) { a(); }
b();
```

`b()` 的前驱是 `DECISION`，而不是真假分支汇合后的 `MERGE`。同理，`if/else` 两分支均 `return` 时，`END` 的前驱是 `DECISION`，不是两个 `RETURN` 节点；循环中的 `break` 会被当成可继续执行的普通节点。

修复前断言：`ControlFlowBuilderTest` 的 8 个用例中至少 5 个失败。修复后全部通过。

## 修复内容

- 新增 `ControlFlowBuilder`，区分：
  - 正常出口 `normal`
  - `return`/`throw` 终止出口 `terminated`
  - `break`/`continue` 跳转 `jumps`
- 顺序语句只连接前一条语句的全部正常出口；多个正常出口先汇合到 `MERGE`。
- `if/else` 合并正常出口；两分支终止时不生成虚假 `DECISION -> END`。
- 循环消费匹配的 `break`/`continue`；支持嵌套循环、标签循环和 `switch` 中的 `break`。
- 终止语句后的代码标记 `UNREACHABLE_STATEMENTS`，不再生成错误节点。
- `try/catch/finally`：
  - 正常出口经过 `FINALLY`。
  - `return/throw` 与 `finally` 的完整交互当前不能无歧义表达，显式输出 `FINALLY_NOT_APPLIED_TO_TERMINATION`；`finally` 自身终止输出 `FINALLY_TERMINATION_OVERRIDES`，不伪装为确定语义。
- `switch`：
  - 支持语句组穿透、箭头/块分支、`break`、无 `default` 的 `NO_MATCH` 边和 `SWITCH_NO_DEFAULT` 诊断。
- 保留节点/边上限、源码证据和截断诊断。

## 兼容与诊断

- `flow` API 返回结构不变，新增诊断码：
  - `UNREACHABLE_STATEMENTS`
  - `UNRESOLVED_JUMP`
  - `FINALLY_NOT_APPLIED_TO_TERMINATION`
  - `FINALLY_TERMINATION_OVERRIDES`
  - `SWITCH_NO_DEFAULT`
  - `LABELED_JUMP_APPROXIMATED`
- 未支持的 Java 语义以诊断呈现，不输出确定的错误连线。

## 验证

- 专项测试：`ControlFlowBuilderTest` 8/8。
- 覆盖：if 无 else、if/else 双 return、循环 break/continue、终止后不可达、try/finally 终止诊断、switch 穿透/default、嵌套 if、空方法。
- 全量 H2：114/0/0/5；MySQL：114/0/0/1。
- 既有 `ArchitectureIntegrationTest.flowSequenceAndUmlKeepSourceEvidence` 继续通过。

## 证据

- 测试源码：`src/test/java/dev/codeatlas/analysis/ControlFlowBuilderTest.java`
- 实现：`src/main/java/dev/codeatlas/analysis/ControlFlowBuilder.java`
- 日志：`D:\CodeAtlas\.runtime\n-fixes-h2.log`、`n-fixes-mysql2.log`

## 剩余限制

- `try/finally` 中 `return/throw` 的 finally 执行顺序仍为显式近似，不作为确定语义。
- `switch` 表达式、模式匹配和复杂标签块未扩展；由诊断标识。
