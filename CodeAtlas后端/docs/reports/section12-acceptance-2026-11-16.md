# 架构说明第 12 部分验收执行记录

日期：2026-11-16。范围：《CodeAtlas-架构总览与后续能力开发说明-2026-11-16》第 12 部分“截图中的待完整验收项目”。本文按执行顺序记录，尚未完成项保留状态与依赖。

## 环境基线

| 项 | 值 |
|---|---|
| 后端 | `target/codeatlas-0.4.6.jar`，Java 21 目标，JDK 24.0.2 |
| 前端 | React 19 + TypeScript 5.9.3 + Vite 7.3.8 + Terser 5.51.2 |
| 专用数据库 | MySQL 8.0 @127.0.0.1:3307，`v046_s12_e2e`（每轮重建） |
| 数据目录 | `CodeAtlas后端/target/s12-e2e-data`（每轮清理） |
| 浏览器 | Playwright 1.56.1，本机 Chrome，`workers=1` |
| AI 测试替身 | fake provider 8002 + fixture AI 服务 8001（真实模型项见后续） |

## 0. 执行摘要

| # | 第 12 部分任务 | 状态 | 结论 |
|---|---|---|---|
| 1 | 最新代码完整 E2E | 通过 | 干净库 35/35；发现并修复导入后自动索引状态丢失的前端缺陷 |
| 2 | 前端生产重建与包验证 | 通过 | `npm run build` 恢复；dist/JAR 93/93 文件哈希一致；最终 JAR 上 35/35 |
| 3 | 真实 AI 多用途语义 | 通过 | DeepSeek 五类真实调用全部成功，8/8 引用 VERIFIED，逐条源码核对 |
| 4 | 大规模性能与并发 | 通过（基线） | 540 文件/9,280 符号/16,320 关系；并发 0 错误；取消/重试成功；导入路径为瓶颈 |
| 5 | 综合安全专项 | 通过（定向） | 变更审查 clean；12 项运行时边界 + 6 类 Git 非法输入全部符合预期 |
| 6 | 完整 CI 执行与远端 | 分阶段通过 / 环境受阻 | H2 88/0/0/5、MySQL 首跑 88/0/0/1、前端构建、Python 12/12、最终 JAR E2E 35/35；单次完整 runner 最终因本机原生内存不足中断；远端 push 未授权 |
| 7 | F01～F06 独立复核 | 通过 | 独立核对 XML/JSON/哈希/计数；两处标注差异与范围限制已记录 |

本轮产品运行时改动仅 `App.tsx` 的自动索引状态修复；工具链/测试改动包括 `ci-local.ps1`、`test_local_config.py`、`test_service.py`、前端构建配置与 Terser 开发依赖。

## 1. 最新代码完整 E2E

**状态：验收通过（本机环境）。**

### 1.1 首轮失败与根因

首轮 `tests/explore.spec.ts` 2 项均超时：导入成功、后端索引任务已完成，但页面始终显示“开始索引”，未出现“索引部分成功/索引已完成”。

根因位于 [App.tsx](../../../CodeAtlas前端/src/App.tsx) 的导入轮询回调：导入成功后先调用 `resetSelection()`，其中 `setTask(null)` 触发轮询 effect 清理并 abort 当前请求；随后自动索引的 `post` 返回时命中 `controller.signal.aborted` 提前返回，`setRevision` 与 `setTask(follow)` 未执行，自动索引状态永久丢失。

修复：

- `resetSelection(clearTask = true)`，仅在需要时清空任务；
- 导入成功自动索引路径改为 `resetSelection(false)`，保留当前轮询控制器。

修复前失败证据：`target/s12-pw-artifacts/pre-fix-failure/`。

### 1.2 最终结果

- 全量 E2E：**35 expected / 0 unexpected / 0 skipped / 0 flaky，35 passed，3.6 分钟**。
- 覆盖 explore、graph、learning、pagination、practice、progress-guides、review、study-loop、training、ui 及 3 项布局单测。
- 无 JVM native OOM、无 `hs_err_pid*.log`。
- 数据库最终为 Flyway V8；任务状态 51 SUCCEEDED + 4 PARTIAL_SUCCESS。
- 截图共 20 张，包含 explorer、graph、learning、review、training 等关键界面。
- 证据：`target/s12-pw-final.json`、`target/s12-pw-artifacts-final/`、`target/s12-e2e-logs/`。
- trace：修复前失败 trace 保存在 `target/s12-pw-artifacts/pre-fix-failure/`；最终全绿按 `trace: retain-on-failure` 不生成新 trace，20 张截图作为通过证据。
- 运行环境：15.7 GB 可见内存、16 逻辑处理器；后端 `-Xmx320m`；Chrome headless、单 worker 串行；本轮无 `hs_err_pid*.log`。E2E 阶段未单独采样浏览器峰值内存，分进程资源峰值见第 4 节性能阶段。

## 2. 前端生产重建与包验证

**状态：验收通过（本机环境）。**

### 2.1 构建阻塞与处理

`npm run build` 在本机 Windows 上稳定失败于 `vite:esbuild-transpile`：

```
remove ...\esbuild-<hash>: Access is denied
```

esbuild 0.28.2 的 chunk 转译临时文件在构建期间被占用，构建结束后可删除；更换临时目录、关闭沙箱均未改变结果。处理方式：

- 新增 Terser 5.51.2 作为开发依赖；
- `vite.config.ts` 设置 `build.target = "esnext"`、`build.minify = "terser"`；
- 该组合跳过 `vite:esbuild-transpile`，仍输出压缩后的生产包。

最终 `npm.cmd run build` 成功：2705 个模块，主包 `index-DAI4PkvE.js` 3,637.99 kB（gzip 923.49 kB），构建约 1 分钟。

### 2.2 包一致性

| 项 | 结果 |
|---|---|
| dist 文件 | 93 |
| JAR 内 `BOOT-INF/classes/static` 文件 | 93 |
| dist→JAR 缺失 / 多余 / SHA-256 不符 | 0 / 0 / 0 |
| JAR 大小 | 33,185,911 字节 |
| JAR SHA-256 | `CF702F18150A1E26FA7703E47D801C33908EEE20361F70AF49F5874E2CD658DD` |
| 真实启动 | 18080 健康 `UP`，`/` 引用 `assets/index-DAI4PkvE.js`，该文件与 dist 同源 |

最终 JAR 上再次执行全量 E2E：**35/35 通过（3.6 分钟）**，证明压缩产物与修复共同生效。

最终 CI 前端重建后再次逐文件核对：`target/s12-build/dist-jar-manifest.txt` 与 `dist-jar-summary.json`，dist 93 / JAR 93、**93 OK、0 缺失、0 多余、0 哈希不符**；JAR 仍为 33,185,911 字节 / `CF702F18…`。

## 3. 真实 AI 多用途语义

**状态：验收通过（本机、公开样本、用户已授权）。**

### 3.1 配置与范围

- 用户明确授权使用本机 `DEEPSEEK_API_KEY` 执行真实模型评估。
- 模型：`deepseek` / `deepseek-flash`，`https://api.deepseek.com`，温度 0，输出上限 4096。
- 样本：公开 Spring PetClinic 固定快照 `126e233d-e578-4ae4-80ef-5958f27a088b`，代次 `b8b56958-a95c-425e-b730-a80e7f69f4c3`；未外发私有源码。
- 每个请求均经过产品内预览、`ALLOW` 授权、生成、引用校验链路。

### 3.2 五类结果

| 类别 | 用途/来源 | 状态 | tokens | 引用 | 不确定项 |
|---|---|---|---|---|---|
| 方法解释 | `OwnerController.processCreationForm` | SUCCEEDED | 1,419 | 1/1 VERIFIED | 5 |
| 项目/技术说明 | processCreationForm + 分页辅助方法 + `OwnerRepository.findByLastNameStartingWith` | SUCCEEDED | 2,845 | 3/3 VERIFIED | 7 |
| 理解题 | `VisitController.processNewVisitForm` + `OwnerController.showOwner` | SUCCEEDED | 2,237 | 2/2 VERIFIED | 0 |
| 实践反馈 | 最近一次代码练习提交（spring-sample） | SUCCEEDED | 2,082 | 1/1 VERIFIED | 3 |
| 引导建议 | PetClinic owner 流程引导（1 个源码来源） | SUCCEEDED | 1,613 | 1/1 VERIFIED | 4 |

合计 10,196 tokens，8 条引用全部 `VERIFIED`，无 `INVALID` 引用，无生成失败。

### 3.3 独立语义核对

- **方法解释**：准确识别 `@PostMapping("/owners/new")`、`@Valid` + `BindingResult`、失败时 flash `error` 与表单视图、成功时 `owners.save`、flash `message` 与 `redirect:/owners/{id}`；对保存后 id 是否回填、视图常量、flash 消费方式、Owner 校验约束和保存异常均列为不确定项。未发现与源码冲突的断言。
- **项目/技术说明**：准确归纳 owner 子域的 Spring MVC + Spring Data 分页写法，正确描述 `pageSize=5`、`Math.max(page,1)`、`PageRequest.of(validatedPage - 1, pageSize)` 和派生查询语义；明确声明只覆盖 3 个片段，未对其他模块、模板、数据库配置下结论。
- **理解题**：3 题分别覆盖单选、判断、短答；题目与参考答案逐条对照 `VisitController.java` 97–112 行和 `OwnerController.java` 178–186 行一致。Q1 正确指向 `rejectValue("date", "typeMismatch.visitDate")` 与 `hasErrors()`；Q2 正确描述 `addVisit`、`save` 与 `redirect:/owners/{ownerId}`；Q3 正确描述 `orElseThrow` 的异常类型与消息。
- **实践反馈**：正确识别提交差异仅新增两行注释，指出任务目标“两次独立修改”未被满足；正确发现同一 `contentDigest` 下 PASSED/FAILED/ERROR/TIMEOUT 四种报告并存且正文为占位文本；明确标注外部报告 `USER_REPORTED`、CodeAtlas 未执行代码，结论为 `AI_ASSISTED`、`suggestedVerdict=PARTIAL`。未把用户报告冒充为系统验证。
- **引导建议**：六个固定步骤各出现一次，顺序为 BUSINESS→SOURCE→PRINCIPLE→CODE→VERIFY→REFLECT；建议与唯一源码片段和缺失的查询流程一致，明确标注“查询流程无资料”“当前来源未核验”等不确定项；引用行 77–87 校验通过。唯一 minor 项：引用条目的 `explanation` 为空字符串，结构合法但说明信息不足。

### 3.4 证据与限制

- 原始输出：`target/s12-ai-eval/clean/*.json`（统一 UTF-8 拉取）、`target/s12-ai-eval/*.context.json`、`target/s12-ai-eval/summary.json`。
- 真实 AI 服务日志：`target/s12-ai-real.log`；后端生成与引用校验记录在测试库 `v046_s12_e2e`。
- 输入/输出仅包含公开 PetClinic 与 spring-sample 测试数据；未包含数据库口令、内部令牌或模型密钥；真实 AI 服务日志不记录提示词或密钥。
- 本次未重复真实模型的恶意提示注入、空输出、限额与取消场景；这些由既有 fixture 确定性测试覆盖（见 E2E 的 AI 用例）。真实模型的语义质量按上表逐条记录，不代表全部模型版本或全部项目类型。

## 4. 大规模性能并发

**状态：验收通过（基线，未设门槛）。**

### 4.1 样本与环境

| 项 | 值 |
|---|---|
| 样本 | `.runtime/s12-perf-sample`，由 `target/s12-perf/gen-sample.ps1` 生成，540 个 Java 文件 |
| 样本字节 | 729,370 |
| 抽取结果 | 9,280 符号、16,320 关系、0 文件诊断 |
| 数据库 | MySQL 8 @3307/`v046_s12_perf`（干净） |
| 后端 | JDK 24.0.2，`-Xmx512m`，SerialGC，端口 18080 |
| 机器 | 16 逻辑处理器，15.7 GB 可见内存，Windows 11 |

样本性质：540 文件为**可复现合成样本**（生成脚本固定、字节数与符号/关系统计固定）；真实 PetClinic 仅 132 文件，用于 F06 分析而非同规模压测。本轮未对真实大型开源项目做同规模性能测量，这是本项的范围限制。

### 4.2 导入与索引

| 操作 | 结果 | 耗时 |
|---|---|---|
| 冷导入 | SUCCEEDED，540 文件 | 92,191.6 ms |
| 热导入（同路径、已存在快照） | SUCCEEDED，复用快照 | 153,967 ms |
| 首次索引 | SUCCEEDED，9,280 符号 / 16,320 关系 | 28,448.4 ms |
| 取消后重试索引 | CANCELLED → SUCCEEDED | 取消 108.1 ms；重试 24,979 ms |

导入耗时显著高于同规模索引。静态定位到 [SnapshotScanner.java](../../../CodeAtlas后端/src/main/java/dev/codeatlas/snapshot/SnapshotScanner.java)：

- `capture` 对同一棵树执行两次 `scan`（内容复制与二次核对），每次逐文件重新读取并计算 SHA-256；
- `validate` 对每个文件重复校验全部祖先目录，每个祖先调用两次 `toRealPath`；540 文件 × 3 次校验 × 约 9 层目录，在 Windows 上形成约数万次路径解析；
- 冷导入 92 s、热导入 154 s 均远高于 0.73 MB 样本的合理 I/O 成本，属于可复现基线，不是单次抖动。

该路径同时承载路径穿越、链接/重解析点和导入期间变更检测，优化（例如目录校验缓存、二次扫描复用哈希）需要先做安全复核，本次未直接改动。

### 4.3 查询 P50/P95（每项 20 次）

| 查询 | P50 ms | P95 ms | max ms |
|---|---|---|---|
| 符号分页 | 66.47 | 81.86 | 82.47 |
| 包图 PACKAGE | 243.17 | 289.53 | 391.85 |
| 类型图 TYPE | 229.49 | 263.4 | 263.95 |
| 类图 CLASS | 915.66 | 1,393.42 | 1,828.88 |
| 结构图 STRUCTURE | 73.86 | 94.7 | 97.99 |
| 局部调用图 CALL | 105.28 | 119.41 | 122.85 |
| 图边证据 | 215.28 | 258.97 | 259.68 |

类图 P95 约 1.39 s，为当前最慢查询；与 `GraphIndex.classNode` 按类型汇总成员、以及 500 个类型 × 9,280 符号的投影成本一致。其余图查询 P95 均在 300 ms 以内。

### 4.4 并发、取消与首次交互

- 并发：4 客户端 × 10 次 TYPE 图查询，共 40 次；错误 0，错误率 0%；墙钟 7,479 ms；请求 P50 451.84 ms、P95 651.46 ms。
- 取消恢复：索引取消在 108.1 ms 内进入 CANCELLED；随后 retry 索引 SUCCEEDED，24,979 ms。
- HTTP 首次交互：HTML 47.1 ms；项目列表 4.3 ms；首个符号页 59.1 ms。
- 浏览器首次交互（Chrome headless，1440×900）：项目选择器 646 ms、快照选中 665 ms、FCP 608 ms、load 490.7 ms。

### 4.5 进程资源峰值

| 进程 | 最大 Working Set | 最大 Private Bytes | CPU 秒 | 平均 CPU（全部 16 核） |
|---|---|---|---|---|
| Java 后端 | 714 MB | 702.8 MB | 183.09 | 5.69% |
| MySQL 3307 | 146.9 MB | 386.3 MB | 47.97 | 1.49% |

采样 386 次、历时 201.2 s。后端 `-Xmx512m`，Working Set 高于堆上限属元空间、JIT、线程栈和原生缓冲的正常叠加；未出现 OOM 或 `hs_err`。本节只采样 Java 后端与 MySQL；**Chrome/布局 Worker 的峰值内存未采样**，浏览器首次交互耗时见 4.4。

### 4.6 结论与后续

- 本次为**无可比门槛的基线**；按 F08 先例，不将绝对耗时直接解释为通过/失败阈值。
- 主要性能风险是**导入路径**（90–154 s / 540 文件）和**类图查询**（P95 1.39 s）；两者均可稳定复现，建议分别作为独立优化任务，先补安全/行为回归再改。
- 证据：`target/s12-perf/results.json`、`target/s12-perf/resources.json`、`target/s12-perf/browser-perf.json`、`target/s12-perf-logs/`、`target/s12-perf/gen-sample.ps1`、`target/s12-perf/run-perf.ps1`。

## 5. 综合安全专项

**状态：验收通过（定向边界；非正式外部安全认证）。**

### 5.1 本次变更代码审查

对未提交变更（`App.tsx` 导入轮询修复、`vite.config.ts` 构建配置、Terser 依赖、`target/` 下测试脚本）执行定向安全审查。结论：

> ✅ No exploitable issues found in the reviewed change set.

理由：`App.tsx` 的改动只调整 React 状态更新顺序，没有新增不可信输入、危险 sink、权限判断或敏感数据流；`vite.config.ts` 与 Terser 属开发构建链，不进入运行时请求路径；`target/` 下脚本仅为本机验收工具，按规则属于测试/夹具范围。测试脚本中的 `v046-s12-token` 是临时本地测试令牌，不是真实模型密钥或生产凭据。

### 5.2 运行时边界检查

在性能后端（`v046_s12_perf`，18080）执行 12 项定向检查，全部通过：

| 检查 | 预期 | 实际 |
|---|---|---|
| `/api/session` 公开 | 200 | 200 |
| 无令牌访问 `/api/projects` | 401 | 401 |
| 非本机 Origin | 403 | 403 |
| `Sec-Fetch-Site: cross-site` | 403 | 403 |
| 非 JSON POST | 415 | 415 |
| 相对导入路径 | 400 | 400 |
| 不存在导入路径 | 400 | 400 |
| 请求体 > 1 MiB | 413 | 413 |
| 含 `.env`/`.key`/`.p12` 的导入 | 成功但排除敏感文件 | SUCCEEDED，0 个敏感文件进入快照 |
| `Safe.java` 正常导入 | 1 个 | 1 个 |
| 后端日志扫描敏感模式 | 0 | 0 |

Git 导入边界另测 6 类非法输入，全部在克隆前拒绝：

| 输入 | 结果 |
|---|---|
| `http://github.com/owner/repo` | 400 `INVALID_GITHUB_URL` |
| `https://gitlab.com/owner/repo` | 400 `INVALID_GITHUB_URL` |
| 带查询参数 `?tab=readme` | 400 `INVALID_GITHUB_URL` |
| 带 `user:pass@` 凭据 | 400 `INVALID_GITHUB_URL` |
| `git@github.com:owner/repo.git` | 400 `INVALID_GITHUB_URL` |
| `ref=--upload-pack=evil` | 400 `INVALID_GITHUB_REF` |

导入路径穿越、链接/重解析点、文件替换竞态和导入期间变更由 `SnapshotScannerTest`/F09 证据覆盖（9 项，0 失败，1 跳过）；本次未重复构造 Windows junction 攻击样本。

### 5.3 AI、导出、同步与执行器

- **AI**：最终 E2E 已覆盖 `DENY` 无外发（403）、`ALLOW` 生成、上下文摘要/用途校验、迟到响应隔离和取消；F09 的 17 项接口检查继续有效。真实模型的本地服务令牌、非本机 URL 拒绝和响应大小限制由 `AiClientTest`/`AiIntegrationTest` 覆盖。
- **导出**：产品尚未提供导出接口，archify 导出仍在后续批次；本项标记 N/A（无实现，不是通过）。
- **同步**：私有认证与自动同步未实现；N/A。
- **未来执行器**：PR-05 自动隔离执行未开放，仍按独立安全准入处理；N/A。

### 5.4 剩余系统假设

- Windows 路径式访问的 junction/重解析点竞态属于平台边界，仍依赖 F09 威胁模型中的系统假设；本次未声称彻底消除。
- 本机回环访问模型（127.0.0.1 + 会话令牌）不适用于多用户或远程部署；远程场景需要额外认证与网络边界。
- 测试实例使用测试数据库口令和测试令牌，未写入产品源码或报告；真实模型密钥只通过环境变量传入。
- 本次为定向检查与独立审阅，不构成外部安全认证或渗透测试结论。

证据：`target/s12-security/results.json`、`target/s12-security/git-boundary.json`、`target/s12-security/run-security.ps1`、`target/s12-perf-logs/`、最终 E2E 的 AI 用例与 F09 记录。

## 6. 完整 CI 执行与远端

**状态：本地 CI 修复并重跑（结果见 6.2）；远端未执行。**

### 6.1 CI runner 的两处真实修复

首跑 `scripts/ci-local.ps1` 暴露两个阻塞：

1. **Maven stderr 被当成终止错误**：H2 测试实际完成（88 项、0 失败、5 跳过），但 Maven 输出的 `WARNING: ... sun.misc.Unsafe` 写入 stderr，在 `$ErrorActionPreference='Stop'` 下被 PowerShell 转为 `NativeCommandError`，runner 在退出码判断前中止。修复 `Step`：原生命令执行期间改为 `Continue`，结束后恢复原设置，统一以 `$LASTEXITCODE` 判断。
2. **Python 测试临时目录竞态**：3 个 `TemporaryDirectory` 在清理时遇到 `WinError 145 目录不是空的`；原因是 Windows 上杀毒/索引进程会短暂占用新建的 `.env`/令牌文件，`target` 和 `CodeAtlasAI` 工作目录更容易触发。修复：三处测试临时目录增加 `ignore_cleanup_errors=True`，runner 在项目目录运行 Python 测试，并把 `TEMP/TMP` 指向 `.runtime/py-temp`；同配置复跑 12/12 通过。曾尝试把 cwd 也移到独立临时目录，但在本机 Windows 上导致测试进程挂起，已回退。

两处均为 runner/测试隔离修复，不影响产品运行时接口或数据。

### 6.2 本地 CI 结果

| 阶段 | 结果 | 耗时 |
|---|---|---|
| backend-h2 | 88 tests / 0 failures / 0 errors / 5 skipped，BUILD SUCCESS | 3:24 |
| backend-mysql | 首跑 88 tests / 0 failures / 0 errors / 1 skipped，BUILD SUCCESS；最终单跑 `forkCount=1` 与 `forkCount=0` 均在 `ProgressIntegrationTest` 触发原生 OOM，非断言失败 | 4:11（首跑） |
| frontend-build | `tsc -b && vite build` 成功，主包 Terser 压缩 | 1:01 |
| python-tests | 12/12 通过（`.runtime/py-temp` + cleanup 容错） | 1.02 s |
| browser-e2e | 35/35 通过（最终 JAR 独立运行，`target/s12-pw-final.json`）；CI runner 因 MySQL OOM 未进入该阶段 | 3.6 分钟 |

H2 结果来自 `target/ci-local/backend-h2.log`；MySQL 首跑原始命令输出另存为 `target/s12-ci/mysql-first-run-raw.log`（140,469 字节，SHA-256 `CBBDDA2A65D680D9840C9F5E398A66675E95F5C551E1CE69AC4A7F619E210410`），因为后续 OOM 重跑覆盖了 `target/ci-local/backend-mysql.log`。Python 12/12 见 `target/ci-local/python-tests-final.log`；E2E 见 `target/s12-pw-final.json` 与 `target/s12-pw-artifacts-final/`。

**单次完整 runner 未一次性跑完 MySQL+E2E**：最终重跑时本机可用 commit/虚拟内存仅约 0.62 GB，Surefire 测试 JVM 在 `ProgressIntegrationTest` 报 `Native memory allocation (malloc) failed ... Chunk::new`，生成 `hs_err_pid44024.log` / `hs_err_pid21692.log`。同一 MySQL 套件在较早一次完整运行中已 **88/0/0/1 通过**，因此这是本机内存环境阻塞，不是测试断言或产品回归。需要在关闭占内存应用或更高内存机器上重跑单次完整 CI。

### 6.3 远端状态

- `origin` 为 `https://github.com/Mikeycoisini/CodeAtlas.git`，`main` 跟踪 `origin/main`；当前只有初始提交，本轮改动未提交、未 push。
- 本机未安装 `gh`；GitHub Actions 页面在未认证抓取下为空，无法读取远端运行记录。
- `.github/workflows/ci.yml` 目前只覆盖后端子工程；前端、Python 与 E2E 仍通过 `scripts/ci-local.ps1` 执行，直到仓库布局统一或获得根级 CI 授权。
- **远端 CI 运行与 push 需要用户明确授权**；本轮未执行，按“受阻（未授权）”保留，不把它当作产品失败。

## 7. F01～F06 独立复核

**状态：独立复核通过（保留少量证据标注差异与范围限制）。**

复核方法：逐份阅读 `followup-F01`～`F06` 记录与 `evidence/planning-followup-2026-11-16/` 原始证据；不直接采信报告结论，另行核对 XML、JSON、日志、哈希与独立导入计数。

| 项 | 独立观察 | 结论 |
|---|---|---|
| F01 | Surefire XML 解析为 3 tests / 0 failures / 0 errors / 0 skipped，XML SHA-256 与 `run.json` 一致；exit 0；原仓库 commit `500158f…`、clean；变更文件哈希与第二次提交 `afterSha256` 一致 | **通过**。一处标注差异：`run.json` 的 `stdoutBytes=7409` 对应 `mvn-owner-tests.stdout.raw.log`（7,409 字节），归档的规范化 `mvn-owner-tests.stdout.log` 为 7,461 字节，其哈希与 `run.json.logs.stdout.sha256` 一致；报告正文只写 7,409 字节，未区分 raw/规范化。不影响测试结论，建议后续标注 raw 与 normalized 两个大小。 |
| F02 | `after-fix-summary.json` 确认 V2/SUPPORTED/2 → V3/PRACTICING/1；读取前后 assessments 3→3、指针未移动；5 并发一致；history 保留 V2；撤回/缺指针/知识改版结果符合预期；`EvidenceService` 中 `revisionChanged`/`ruleChanged` 读取重算逻辑存在 | **通过**。一处产物哈希差异：summary 中 `jar=36,469,794 / 13791338…` 是 F02 执行时中间包，不是最终 F11 包 `36,469,810 / B8F23A21…`，也不是本轮最终包 `33,185,911 / CF702F18…`；功能结论以代码与真实 HTTP 响应为准，发布时应引用最终包哈希。 |
| F03 | `f03-summary.json` 含 5 类：补全（4/Errors1 → 4/0）、修复（4/Failures1 → 4/0）、补测试（3/0 → 4/0）、重构（3/0 → 3/0）、扩展（3/0 → 4/0）；每类均有 task/workspace/submission/report/knowledge/evidence/reflection ID；进度为 PRACTICING / EVIDENCE_V3 / evidenceCount=2；AI 生成前后提交数与证据数不变 | **通过**。限制：五类是 1 个类 + 1 个测试类的受控样本，不等同第二个大型真实项目；该限制已在 F03 报告写明。 |
| F04 | `after-v8.json` 与 `restore.json` 均为 Flyway 1–8、`dataFilesUnchanged=true`；V7/V8 各 1 次；资源身份、中文、旧判断、引导历史保留；备份 133,481 字节及恢复库均有记录 | **通过**。限制：V6 基线由 Git `6b2a760` 源码构建，不是官方 V6 二进制；MySQL 5.7、非 Windows、生产体量未覆盖。 |
| F05 | `chain-verification.json`：API UTF-8 全 true、无 U+FFFD；MySQL 源/恢复 HEX 一致；`dueAtMs` 与 ISO 换算一致；备份 `SET NAMES utf8mb4` 且含预期标题 UTF-8；恢复库与源库一致；两次评分 dueAtMs 与重启后 state 一致 | **通过**。历史 `before.log`/报告中的 58 个 U+FFFD 已定位为不可逆捕获损坏，保留原值；备份大文件在 `target/f05`，证据目录仅保留哈希与结果。 |
| F06 | 用本轮独立 PetClinic 导入复核：132 文件 / 323 符号 / 2,048 关系 / 0 诊断；关系分布为 CALL 285+1,317、RUNTIME_CALL 422、EXTENDS 8+3、IMPLEMENTS 6、TRANSACTION_BOUNDARY 7；与 `f06-counts.json` 及报告逐项一致；独立预期测试 6/6（Surefire） | **通过**。限制：仅固定 PetClinic 版本与独立夹具，未建立跨项目广泛准确率；大量 UNRESOLVED 是外部依赖/运行时保守结果，不应直接解释为漏检率。 |

### 7.1 关闭依据与保留项

- **建议关闭 F01～F06 的“待验收”状态**：每项都有可定位的原始证据、真实执行或独立计数，且本轮独立核对未发现结论性冲突。
- 保留的标注差异：F01 raw/normalized 字节数；F02 中间包哈希。二者均不影响功能结论，但发布资料应引用最终 JAR 与规范化证据。
- 保留的范围限制：F03 小型受控样本、F04 源码基线而非官方 V6 包、F05 历史 U+FFFD 不可恢复、F06 非广泛准确率。上述限制已在各记录中声明，不能作为“全部真实项目/全部环境已覆盖”的依据。

## 本次改动文件

| 文件 | 改动 |
|---|---|
| `CodeAtlas前端/src/App.tsx` | 修复导入后自动索引状态被 abort 丢弃 |
| `CodeAtlas前端/vite.config.ts` | 生产构建改用 `esnext` + Terser，规避 esbuild 临时文件锁 |
| `CodeAtlas前端/package.json`、`package-lock.json` | 新增 Terser 5.51.2 开发依赖 |
| `CodeAtlas后端/scripts/ci-local.ps1` | 原生命令 stderr 不再被 Stop 模式当作终止错误；Python 测试使用独立 TEMP/TMP |
| `CodeAtlasAI/tests/test_local_config.py`、`test_service.py` | 三处测试临时目录增加 `ignore_cleanup_errors`，规避 Windows 文件占用导致清理失败 |
| `CodeAtlas后端/docs/reports/section12-acceptance-2026-11-16.md` | 本验收执行记录 |

## 未覆盖与限制

- 本记录为当前本机环境结果；未做外部分发、远端 CI、生产部署或正式外部安全认证。
- 真实 AI 语义只覆盖 `deepseek-flash`、公开 PetClinic 与一次固定调用，不代表其他模型、私有代码或长期输出质量。
- 性能为无可比门槛的基线；导入路径 90–154 s/540 文件与类图 P95 1.39 s 已作为后续优化项，未在本轮修改。
- 安全为定向检查与变更审查；Windows junction/重解析点竞态仍依赖 F09 威胁模型假设。
- 单次完整 CI 最终未在本机一次性跑完 MySQL+E2E：MySQL 首跑 88/0/0/1 通过，最终重跑因本机 commit/原生内存不足在 `ProgressIntegrationTest` OOM；E2E 35/35 已在最终 JAR 上独立通过。需关闭占内存应用或换高内存机器后重跑单次完整 runner。
- 本机 8080/5173 当前未监听；本轮测试未以这两个端口为目标，但建议重新启动 IDE 后端与前端 dev server。
- 远端 CI 未运行：`gh` 未安装、GitHub Actions 页面需认证、push 未获授权；不把未执行的远端任务声明为通过。
- Terser/构建配置改动只影响开发构建链；`App.tsx` 修复只影响导入后自动索引状态，不改变后端接口或数据模型。
