# 架构总览、代码分析、统一 AI、私有 GitHub、性能与 CI 验收（2026-11-17）

对应《CodeAtlas-V0.1-V0.4-Remaining-Review-2026-11-17》中仍标为未实现的五项能力。本轮在原有固定快照、代次和源码证据模型上补齐，不引入第二套身份或运行时事实。

## 结论摘要

| 项目 | 结果 | 说明 |
|---|---|---|
| 系统级架构总览 | 通过 | 固定快照推导系统/应用/模块/组件/外部服务/数据存储；28 节点、53 关系、3 分组（PetClinic 样例） |
| archify 导出 | 通过 | 自研独立 HTML/SVG；`ARCHIFY_HTML` 为历史格式名，新增 `ARCHITECTURE_HTML` 与 `SVG`；未调用外部 archify 包或开发者个人目录 |
| 流程图 | 通过 | 方法静态控制流；UI 样例 24 节点；无运行时语义 |
| 时序图 | 通过 | 静态调用树；UI 样例 52 条消息、25 个参与者；不代表运行时顺序 |
| 完整 UML | 通过 | 固定源码读取字段/方法/构造器/关联；UI 样例 4 个成员；不受图视图 50 成员上限影响 |
| 统一 AI 助手 | 通过 | `GET /api/ai/status` 无密钥状态；四个新工作区共用一个入口；AI 实际能力沿用原授权链路 |
| 私有 GitHub 认证 | 通过（无真实令牌） | AES-GCM 本地加密、撤销、轮换、绑定、私有导入；未用真实私有仓库验证 |
| 私有仓库同步 | 通过（手动） | 手动检查远端提交并创建新快照；自动定时同步未启用 |
| 自动隔离执行 | N04/N05 已实现，默认关闭 | 不可变提交、固定容器身份、超时回收、重启恢复、结果分类和幂等已落地；未实际启用 Docker |
| 性能瓶颈优化 | 通过 | 540 文件扫描 A/B：旧 172.9/173.9 s，新 32.5/48.7 s，约 3.6–5.3 倍 |
| 单次完整 CI | 通过 | H2 114/0/0/5；MySQL 114/0/0/1；前端构建；Python 12/12；E2E 36/36 |
| 远端验证 | 受阻 | `git ls-remote` 连接被重置；本机无 `gh`；未推送、未触发远端 CI |
| 最终 JAR | 通过 | 33,378,231 字节，SHA-256 `CBF813AC018649AE59084E65BCD3420F6A78FB129A677D880D889A5361F19122` |
| dist/JAR 清单 | 通过 | 101/101 文件哈希一致，0 缺失、0 多余、0 不符 |

> 2026-11-17 复核后已修复 N01–N05：控制流出口、无绑定 GitHub 检查/同步、导出幂等/失败恢复、容器生命周期/结果分类、执行归属/不可变提交/幂等。修复记录见 `new-capabilities-fix-N01` 至 `N05` 报告及 `new-capabilities-fix-scope-2026-11-17.md`。上表计数和 JAR 哈希为本轮修复后的最终值。

## 1. 系统级架构总览与导出

新增包 `dev.codeatlas.architecture`：

- `ArchitectureAnalyzer`：只读解析 `pom.xml`、Gradle、Spring 配置、`package.json`、`docker-compose`；识别系统、应用、模块、组件、外部服务、数据存储；组件依赖由 `CALL/EXTENDS/IMPLEMENTS` 聚合；XML 禁用 DTD/外部实体；配置 URL 脱敏；上限 20 模块、120 组件、220 节点、600 边。
- `ArchitectureService`：优先读取 `architecture_projections` 缓存，缺失时基于已发布代次重建；查询支持视角、焦点、深度、上限；SHA-256 摘要用于导出时防止陈旧投影。
- `ArchitectureHtmlRenderer`：自包含 HTML、CSS、内联 SVG 和交互脚本，含证据侧栏、浅/深主题；不输出源码内容、绝对路径或令牌。
- `ArchitectureExportService`：校验格式/范围/主题/投影摘要/幂等键，原子写入 `data/exports/<id>/architecture.html`，记录 `architecture_exports`。
- 迁移：`V9__architecture.sql`。
- 接口：`GET /api/snapshots/{snapshot}/architecture`、`POST /api/architecture/exports`、`GET /api/architecture/exports/{id}`、`GET /api/architecture/exports/{id}/download`。
- 前端：`src/architecture/ArchitectureWorkspace.tsx`，支持分层画布、模块分组、下钻、节点/关系证据、源码定位和 HTML 导出下载。

实测：PetClinic 快照返回 28 节点、53 关系、3 分组、`truncated=false`；导出 56,263 字节，HTML 含 `<svg>`，不含 `D:\` 绝对路径和 AI 服务令牌。UI 中架构视图 858 ms 内渲染 28 个节点，无控制台错误。

## 2. 流程图、时序图、完整 UML

新增包 `dev.codeatlas.analysis`：

- `FlowService`：基于固定源码的 JavaParser 静态控制流，节点覆盖 `START/END/DECISION/LOOP/RETURN/THROW/TRY/CATCH/FINALLY/SWITCH/BRANCH/MERGE/JUMP`，节点和边均带文件、行号、文件哈希证据；明确标注 `STATIC_FLOW_ONLY`、异步/动态分派不展开。
- `SequenceService`：静态调用树、参与者、消息、递归/未解析标记，证据含 `filePath/fileHash`。
- `UmlService`：直接读取固定快照源码，返回字段、方法、构造器、可见性、修饰符、注解、字段类型关联候选、多重性和继承/实现关系；集合字段标记 `AGGREGATION_CANDIDATE`，生命周期不足时不虚构组合关系。
- 接口：`/flow`、`/sequence`、`/uml`。
- 前端：`src/analysis/AnalysisWorkspace.tsx`，三个页签和源码证据跳转。

实测 `processCreationForm`：流程图 24 节点；时序图 52 条消息、25 个参与者。`BaseEntity`：UML 4 个成员（1 字段、3 方法）。三者均无页面错误。

## 3. 统一 AI 助手入口

- 新增 `GET /api/ai/status`：返回 `reachable/configured/provider/model/baseUrl/fingerprint/maxTokens/capabilities/message`，不返回令牌；不可达时返回 `AI_SERVICE_UNAVAILABLE`，手工资料不受影响。
- 前端新增 `src/ai/AiAssistantWorkspace.tsx`：统一展示解释、出题、实践反馈、引导建议、项目/技术说明五个入口，并沿用各能力原有的源码选择、授权、引用校验和证据写入逻辑。
- 未新增第二套 AI 调用链，也未绕过原有出站授权。

实测：AI 服务未启动时返回 `reachable=false, configured=false` 且无令牌；E2E 阶段启动固定 provider 后，原有 AI 学习、练习和引导用例全部通过。

## 4. 私有 GitHub 认证、同步与自动隔离执行

- 迁移：`V10__github_credentials_and_execution.sql`，新增 `tasks.credential_id`、`github_credentials`、`github_repo_bindings`、`execution_jobs`。
- `GitHubCredentialService`：AES-GCM 加密令牌，密钥文件 `data/.github-key`；支持创建、列表、撤销、轮换、绑定、查询绑定；通过 `api.github.com/user` 验证并读取 scopes；任何响应和日志不返回明文令牌。
- `GitHubFetcher`：私有克隆通过 `GIT_CONFIG_COUNT/GIT_CONFIG_KEY_0/GIT_CONFIG_VALUE_0` 注入 `Authorization: Bearer`，令牌不进入 argv 或仓库 URL。
- `TaskService`：私有导入携带凭据，成功后写入项目绑定；公开导入保持原路径。
- `GitHubSyncService`：手动检查远端提交，必要时调用导入创建新快照；旧快照、知识和训练来源保持不变。
- `GitHubController`：凭据 CRUD/轮换、私有导入、绑定查询、检查、同步。
- `ExecutionService`（N04/N05 修复后）：默认 `codeatlas.executor.enabled=false`；启用后要求 Docker，从不可变 `practice_submissions` 归档复制执行副本，记录 submission/content digest/snapshot/task/project；容器固定名称和 cidfile，超时/取消按记录的容器 ID 停止并删除；支持 QUEUED/RUNNING/PASSED/TEST_FAILED/BUILD_ERROR/TIMEOUT/CANCELLED/ERROR/INTERRUPTED 和重启恢复。未实际启用真实 Docker。

本轮修复：无绑定时 `GET /api/github/projects/{id}/binding` 原返回 200 空响应，前端 JSON 解析失败并显示“服务响应无效”。现返回 `{"bound":false,"projectId":"..."}`，有绑定时返回 `bound:true` 与完整绑定；新增 `unboundProjectReturnsExplicitState` 测试。

UI 实测：GitHub 视图在无绑定、无执行器时正常渲染，无错误；私有导入/检查/同步依赖真实令牌，本轮未执行。

## 5. 性能瓶颈优化

### 5.1 扫描器路径校验缓存

`SnapshotScanner.validate` 原先对每个文件的每一级祖先重复执行 `readAttributes` 和两次 `toRealPath`。现改为每次扫描维护 `validatedDirectories`，祖先目录只校验一次；每个文件叶子仍在校验、读取后、读取完成时重新校验，重解析点/特殊文件边界不放松。

### 5.2 类图成员预聚合

`GraphIndex` 新增构造期 `membersByOwner`，按所有者分组并排序；类节点和 `GraphQueryService.members` 不再为每个类扫描全部符号。

### 5.3 同一样本 A/B

样本：540 文件、729,370 字节、20 个包。旧扫描器取自发布包 `codeatlas-0.4.5.jar`，与当前 `SnapshotScanner` 在同一 JVM、同一样本上交替运行，均执行两次扫描（复制 + 二次核对）：

| 轮次 | 旧扫描器 | 新扫描器 |
|---|---|---|
| 1 | 172,936 ms | 32,482 ms |
| 2 | 173,939 ms | 48,678 ms |

中位数约 173.4 s → 40.6 s，约 4.3 倍；单轮提升 3.6–5.3 倍。PetClinic 干净库导入端到端 15.9 s（含任务队列和数据库写入），索引约 0.8 s。

## 6. 单次完整 CI 与前端界面验证

| 阶段 | 结果 | 证据 |
|---|---|---|
| 后端 H2 | 114 通过 / 0 失败 / 0 错误 / 5 跳过，BUILD SUCCESS | `.runtime/n-fixes-h2.log` |
| 后端 MySQL 3307 | 114 通过 / 0 失败 / 0 错误 / 1 跳过，BUILD SUCCESS | `.runtime/n-fixes-mysql2.log`；清理后最终重跑受本机 native OOM 中断（`n-fixes-mysql-rerun-oom.log`），非产品断言失败 |
| 前端生产构建 | Vite 7.3.8，2716 模块，成功 | `.runtime/n-fix-frontend-build.log` |
| Python AI 服务 | 12/12 OK | `.runtime/n-fix-python.log` |
| 浏览器 E2E | 36/36 通过，0 未预期、0 flaky | `.runtime/n-fix-pw.log`、`.runtime/n-fix-pw.json` |
| 新视图 UI 烟测 | 架构 28 节点、流程图 24 节点、时序图 52 消息、UML 4 成员；四视图均无错误 | `.runtime/s12-final-evidence/smoke-*.png`、`n-fix-pw.log` |
| dist/JAR 清单 | 101/101 OK，0 缺失、0 多余、0 哈希不符 | `.runtime/n-fix-evidence/dist-jar-summary.json` |

补充：

- N01–N05 修复后专项测试：`ControlFlowBuilderTest` 8/8、`ExecutionServiceIntegrationTest` 4/4、`ProcessDockerRunnerTest` 3/3；架构测试 7/7、GitHub 测试 4/4、扫描器 11/11（1 跳过）。
- 新增工作区 E2E：`tests/new-workspaces.spec.ts`，架构 4 节点、代码分析/AI/GitHub 切换和无绑定空状态。
- 首次 MySQL 全量缺少 `TEST_ARCH_DB_URL`/`TEST_EXEC_DB_URL` 导致两个新测试上下文回退 H2 URL；补齐 `v046_arch`、`v046_exec` 后重跑 114/0/0/1。
- 初次打包残留旧静态资源，JAR 37,582,734 字节；干净构建后 33,378,231 字节，101 个静态文件与 dist 完全一致。
- 前端页脚版本由 `0.4.1` 修正为 `0.4.6`。

## 7. 远端验证

- 后端目录存在 Git 仓库，`origin` 为 `https://github.com/Mikeycoisini/CodeAtlas.git`，当前分支 `main`，本地仅一个初始提交，工作区有大量未提交修改。
- `git ls-remote --heads origin` 返回 `fatal: unable to access ... Recv failure: Connection was reset`，未取得远端分支。
- 本机未安装 `gh`，无法查询远端 Actions。
- 未执行 `git push`，也未触发远端 CI；远端运行需网络恢复、提交范围确认和推送授权。

## 8. 限制与未完成

1. 未使用真实私有 GitHub 令牌或私有仓库做端到端导入；加密、门禁、绑定和错误路径由测试覆盖。
2. 私有仓库自动定时同步未启用；当前为手动检查和手动同步。
3. 自动隔离执行默认关闭，未实际运行 Docker；需 PR-05 安全准入后再启用。
4. 架构导出为 CodeAtlas 自研独立 HTML/SVG 渲染器，不是外部 archify 兼容实现；未集成外部 archify npm 包或开发者个人技能目录。
5. 远端 CI、推送和真实远端验证受网络与授权限制。
6. `target-leftover-20261117` 保留了部分清理失败的旧测试目录（约 2.8 MB），未再执行递归删除。

## 9. 复现与证据

核心命令：

```powershell
# 后端 H2
$env:JAVA_HOME='E:\jdk24'; mvn -B test

# 后端 MySQL（CI 变量见 scripts/ci-local.ps1）
mvn -B test

# 前端
npm run build

# Python
.venv\Scripts\python.exe -m unittest discover -s tests -v

# 浏览器 E2E（需 18080 后端和 8001/8002 AI 固定服务）
$env:PLAYWRIGHT_BASE_URL='http://127.0.0.1:18080'
npx playwright test --workers=1 --reporter=line,json
```

证据目录：`D:\CodeAtlas\.runtime\s12-final-evidence\`，包含测试日志、E2E JSON、UI 截图、架构导出 HTML、dist/JAR 清单和最终打包日志。
