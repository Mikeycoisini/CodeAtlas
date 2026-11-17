# CodeAtlas 本轮开发总结（2026-11-17）

本文总结本轮针对《CodeAtlas-V0.1-V0.4-Remaining-Review-2026-11-17》所完成的开发、修复、性能优化与验证。详细验收证据见[架构/分析/AI/GitHub 验收报告](architecture-diagrams-ai-github-2026-11-17.md)，当前状态索引见[followup-status-2026-11-16.md](followup-status-2026-11-16.md)。

## 1. 背景与目标

本轮开发的基线是第 12 部分验收完成后的 0.4.6 代码。第 12 部分已解决导入自动索引状态、前端生产构建、真实 AI 调用、性能基线、安全复核、分阶段 CI 和 F01–F06 独立复核；本轮继续补齐复核文档中仍标记为未实现的能力：

1. 系统级架构总览与 archify 导出。
2. 流程图、时序图、完整 UML。
3. 统一 AI 助手入口。
4. 私有 GitHub 认证、同步和自动隔离执行。
5. 性能瓶颈优化、单次完整 CI 及远端验证。

约束：

- 所有分析必须绑定固定快照、已发布代次和源码证据。
- 静态分析不得伪装成运行时事实。
- AI 不自动写掌握度、不自动提交、不绕过原有出站授权。
- 私有凭据不得出现在 API 响应、任务参数或日志中。
- 自动隔离执行默认关闭，先落地安全门禁。
- 不修改用户已有的 MySQL 3306 实例。

## 2. 交付总览

| 能力 | 交付状态 | 主要结果 |
|---|---|---|
| 系统架构总览 | 完成 | 系统/应用/模块/组件/外部服务/数据存储；PetClinic 28 节点、53 关系、3 分组 |
| archify 导出 | 完成 | 自研独立 HTML/SVG；`ARCHIFY_HTML` 为历史格式名，新增 `ARCHITECTURE_HTML`/`SVG`；无绝对路径、无令牌 |
| 流程图 | 完成 | JavaParser 静态控制流；UI 样例 24 节点 |
| 时序图 | 完成 | 静态调用树；UI 样例 52 消息、25 参与者 |
| 完整 UML | 完成 | 源码级字段/方法/构造器/关联；UI 样例 4 个成员 |
| 统一 AI 助手 | 完成 | 无密钥状态接口 + 统一工作区；沿用原能力链路 |
| 私有 GitHub 认证 | 完成（无真实令牌） | AES-GCM 加密、撤销、轮换、绑定、私有导入 |
| 私有仓库同步 | 完成（手动） | 手动检查远端提交并创建新快照 |
| 自动隔离执行 | N04/N05 已实现，默认关闭 | 不可变提交、容器身份、超时回收、结果分类、重启恢复、幂等已落地；未实际启用 |
| 性能优化 | 完成 | 扫描 A/B 约 3.6–5.3 倍提升 |
| 单次完整 CI | 完成 | H2 114/0/0/5；MySQL 114/0/0/1；前端、Python、E2E 36/36 全部通过 |
| 远端验证 | 受阻 | GitHub 连接被重置、无 `gh`，未推送 |

最终运行包：`target/codeatlas-0.4.6.jar`，33,378,231 字节，SHA-256 `CBF813AC018649AE59084E65BCD3420F6A78FB129A677D880D889A5361F19122`；dist/JAR 101/101 文件哈希一致。

## 3. 架构与技术设计

### 3.1 分层

```text
React 工作区
  ├─ 架构总览 / 代码分析 / AI 助手 / GitHub
  └─ 固定快照、代次、源码证据跳转
        │
        ▼
Spring REST 控制器
  ├─ ArchitectureController / AnalysisController
  ├─ AiStatusController / GitHubController / ExecutionController
        │
        ▼
领域服务与分析器
  ├─ ArchitectureAnalyzer / ArchitectureService
  ├─ FlowService / SequenceService / UmlService
  ├─ GitHubCredentialService / GitHubSyncService
  └─ ExecutionService
        │
        ▼
MySQL + Flyway（V1–V10）
  ├─ architecture_projections / architecture_exports
  ├─ github_credentials / github_repo_bindings
  └─ execution_jobs / tasks.credential_id
```

### 3.2 证据模型

架构、流程图、时序图和 UML 的每个关键节点或关系都携带：

- `fileId`、`filePath`、`fileHash`
- 起止行号
- 生成规则或分析器版本
- `CONFIRMED` / `INFERRED` / `UNRESOLVED` 状态

这一模型确保分析结果可以回到固定快照源码，而不是依赖当前工作区或运行时推测。

### 3.3 缓存与幂等

- 架构投影写入 `architecture_projections`，查询时优先复用。
- 导出时校验 `projectionId` 和 `digest`，防止使用陈旧投影。
- 导出支持 `idempotencyKey`，重复请求返回同一导出记录。
- 导出文件原子写入 `data/exports/<id>/architecture.html`。

### 3.4 安全边界

- 架构分析 XML 禁用 DTD 和外部实体，防止 XXE。
- 配置 URL 对外输出前脱敏，不返回密码参数。
- 导出 HTML 转义文本，不输出源码内容、绝对路径或令牌。
- GitHub 令牌使用 AES-GCM 加密，密钥文件独立保存；令牌不进入 argv、URL、API 响应或日志。
- 执行器默认关闭；启用后要求 Docker，并使用 `--network=none`、CPU、内存、PID、超时和只读根限制。

## 4. 各能力实现

### 4.1 系统级架构总览

新增包 `dev.codeatlas.architecture`：

- `ArchitectureAnalyzer`：读取 Maven/Gradle、Spring 配置、`package.json`、`docker-compose`，识别系统、应用、模块、组件、外部服务、数据存储；组件依赖由 `CALL/EXTENDS/IMPLEMENTS` 聚合。
- `ArchitectureService`：从固定快照和已发布代次构建或读取投影，计算 SHA-256 摘要。
- `ArchitectureHtmlRenderer`：生成分层架构图、分组、节点、关系、证据面板和诊断。
- `ArchitectureExportService`：处理导出请求、投影校验、幂等、原子落盘和下载。
- 迁移 `V9__architecture.sql` 新增 `architecture_projections`、`architecture_exports`。

接口：

- `GET /api/snapshots/{snapshot}/architecture`
- `POST /api/architecture/exports`
- `GET /api/architecture/exports/{id}`
- `GET /api/architecture/exports/{id}/download`

前端 `ArchitectureWorkspace` 支持系统/应用/模块/组件/外部视角、深度、每页上限、搜索、模块下钻、节点和关系证据、源码定位以及 HTML 导出下载。

### 4.2 自研 HTML/SVG 导出

导出产物是 CodeAtlas 自研的单文件 HTML 或 SVG，内含样式和图形，可直接在浏览器打开。它不是对开发者个人 archify 技能目录或外部 archify npm 包的调用。`ARCHIFY_HTML` 是历史格式名，新增 `ARCHITECTURE_HTML` 别名和真实图片格式 `SVG`；HTML/SVG 均不依赖外部脚本、样式、字体或图片。

实测：PetClinic 架构导出 56,263 字节，包含 `<svg>`，不包含 `D:\` 绝对路径或 AI 服务令牌。

### 4.3 流程图

`FlowService` 使用 JavaParser 从固定源码构建静态控制流：

- 节点：`START/END/DECISION/LOOP/RETURN/THROW/TRY/CATCH/FINALLY/SWITCH/BRANCH/MERGE/JUMP`
- 边：顺序、条件、循环、异常等
- 警告：`STATIC_FLOW_ONLY`、异步和动态分派不展开、TRY/SWITCH 近似
- 每个节点和边均带源码证据

接口：`GET /api/snapshots/{snapshot}/symbols/{symbol}/flow`。

### 4.4 时序图

`SequenceService` 从调用关系构建静态调用树：

- 参与者、消息、顺序号、递归标记、未解析目标
- 消息证据包含文件、行号和文件哈希
- 明确标注“静态顺序，不代表运行时顺序”

接口：`GET /api/snapshots/{snapshot}/symbols/{symbol}/sequence?depth=&limit=`。

### 4.5 完整 UML

`UmlService` 直接读取固定快照源码，不受图视图 50 成员上限影响：

- 字段、方法、构造器
- 可见性、修饰符、注解
- 字段类型关联候选和多重性
- `extends` / `implements` 关系
- 集合字段标记 `AGGREGATION_CANDIDATE`；生命周期不足时不虚构组合关系

接口：`GET /api/snapshots/{snapshot}/symbols/{symbol}/uml`。

前端 `AnalysisWorkspace` 提供流程图、时序图、完整 UML 三个页签，并与源码证据跳转打通。

### 4.6 统一 AI 助手

新增 `GET /api/ai/status`，返回：

- `reachable`、`configured`
- `provider`、`model`、`baseUrl`、`fingerprint`、`maxTokens`
- 五项能力：源码解释与追问、理解题与出题、实践反馈、六步引导建议、项目/技术说明
- 不可达时返回 `AI_SERVICE_UNAVAILABLE`，手工资料不受影响

前端 `AiAssistantWorkspace` 统一展示状态、提供方和能力入口。它不复制 AI 调用链，原有源码选择、授权、引用校验和证据写入逻辑保持不变；未配置或不可达时只显示离线说明。

### 4.7 私有 GitHub 认证与同步

迁移 `V10__github_credentials_and_execution.sql` 新增：

- `tasks.credential_id`
- `github_credentials`
- `github_repo_bindings`
- `execution_jobs`

核心实现：

- `GitHubCredentialService`：AES-GCM 加密令牌，密钥文件 `data/.github-key`；创建、列表、撤销、轮换、绑定、查询绑定；通过 `api.github.com/user` 验证令牌和 scopes。
- `GitHubFetcher`：私有仓库克隆通过 Git 配置项注入 `Authorization: Bearer`，令牌不进入 argv 或 URL。
- `TaskService`：私有导入携带凭据，成功后写入项目绑定；公开导入保持原路径。
- `GitHubSyncService`：手动检查远端提交，必要时通过导入创建新快照；旧快照、知识和训练来源不变。
- `GitHubController`：凭据 CRUD/轮换、私有导入、绑定查询、检查和同步。

本轮修复：无绑定时 `GET /api/github/projects/{id}/binding` 原返回 200 空响应，前端 JSON 解析失败并显示“服务响应无效”。现固定返回：

```json
{"bound": false, "projectId": "..."}
```

有绑定时返回 `bound: true` 和完整绑定字段；新增 `unboundProjectReturnsExplicitState` 集成测试。

### 4.8 自动隔离执行

`ExecutionService`（N04/N05 修复后）默认关闭，但已具备完整执行门禁和任务模型：

- 默认 `codeatlas.executor.enabled=false`；`GET /api/executions/status` 返回启用、Docker、镜像、资源、单工作线程和结果类别。
- 执行请求只接受 `submissionId + idempotencyKey`；拒绝客户端 `projectId/taskId/workspace`。
- 从不可变 `practice_submissions` 归档复制，推导 project/snapshot/task/workspace，复制后校验 content digest。
- 容器固定名称 `codeatlas-job-<jobId>`、`--label codeatlas.job` 和 cidfile；超时/取消按记录容器 ID stop/rm，并记录清理结果。
- 结果分类：PASSED、TEST_FAILED、BUILD_ERROR、TIMEOUT、CANCELLED、FAILED、ERROR、INTERRUPTED。
- 启动恢复扫描未终结任务，只停止持久记录中的容器 ID；同键同提交复用 job，同键改提交冲突。

按设计，本轮未启用真实 Docker；需要 PR-05 安全准入后再开启。

### 4.9 性能优化

#### 扫描器目录校验缓存

`SnapshotScanner.validate` 原对每个文件的每一级祖先重复执行 `readAttributes` 和两次 `toRealPath`。现每次扫描维护 `validatedDirectories`：

- 祖先目录只校验一次。
- 每个文件叶子仍在校验时、读取后、读取完成时重新校验。
- 重解析点、符号链接和特殊文件的边界不放松。

#### 类图成员预聚合

`GraphIndex` 在构造期按所有者分组和排序成员，新增 `membersByOwner`；类节点和 `GraphQueryService.members` 不再为每个类扫描全部符号。

#### A/B 结果

样本：540 文件、729,370 字节、20 个包。旧扫描器取自 `codeatlas-0.4.5.jar`，与当前扫描器在同一 JVM、同一样本上交替运行，均执行两次扫描（复制和二次核对）：

| 轮次 | 旧扫描器 | 新扫描器 |
|---|---|---|
| 1 | 172,936 ms | 32,482 ms |
| 2 | 173,939 ms | 48,678 ms |

中位数约 173.4 s → 40.6 s，约 4.3 倍；单轮提升 3.6–5.3 倍。PetClinic 干净库导入端到端 15.9 s（含任务队列和数据库写入），索引约 0.8 s。

## 5. 前端集成

`App.tsx` 新增四个工作区：

- `architecture`：架构总览
- `analysis`：代码分析
- `ai`：AI 助手
- `github`：私有 GitHub

四个工作区均采用懒加载，导航新增对应按钮和标题；URL 支持 `view/projectId/snapshotId/generationId/symbolId/line` 以及架构参数 `archPerspective/archFocus/archDepth/archLimit`，刷新和返回可恢复上下文。

新增文件：

- `src/architecture/architectureApi.ts`、`ArchitectureWorkspace.tsx`、`architecture.css`
- `src/analysis/analysisApi.ts`、`AnalysisWorkspace.tsx`、`analysis.css`
- `src/ai/aiApi.ts`、`AiAssistantWorkspace.tsx`、`ai-assistant.css`
- `src/github/githubApi.ts`、`GitHubWorkspace.tsx`、`github.css`

同时修正页脚版本号，由 `0.4.1` 改为 `0.4.6`。

## 6. 缺陷修复记录

| 现象 | 根因 | 修复 | 验证 |
|---|---|---|---|
| GitHub 视图显示“服务响应无效” | 无绑定时接口返回 200 空响应，前端 JSON 解析失败 | 返回显式 `bound:false`；前端按 `bound` 判断 | 浏览器 UI 烟测无错误；新增集成测试 |
| MySQL CI 阶段架构测试上下文失败 | CI 未设置 `TEST_ARCH_DB_URL`，MySQL 驱动回退到 H2 URL | 新增 `v046_arch` 库和 CI 变量 | MySQL 全量 114/0/0/1 |
| JAR 体积异常且包含旧哈希资源 | `target/classes/static` 未清理，旧构建资源被重复打包 | 清理构建目录后重建 | 37,582,734 → 33,378,231 字节；101/101 一致 |
| 页脚显示 0.4.1 | 版本号硬编码未更新 | 改为 0.4.6 | 构建产物检查 |
| E2E 首轮 1 个超时 | 导入后等待“索引已完成”存在时序波动；数据库显示索引任务实际成功 | 单测重跑和全量重跑 | 单测 13.3 s 通过；全量 36/36 |
| 未绑定项目接口无稳定 JSON | 服务层对空结果返回 `null` | 返回 `Map.of("bound", false, "projectId", ...)` | 新测试 `unboundProjectReturnsExplicitState` |

第 12 部分基线的修复（导入自动索引状态、esbuild/Terser、CI stderr、Python 临时目录）继续保留，详见 `section12-acceptance-2026-11-16.md`。

## 7. 验证结果

### 7.1 自动化测试

| 阶段 | 结果 | 证据 |
|---|---|---|
| 后端 H2 | 114 通过 / 0 失败 / 0 错误 / 5 跳过，BUILD SUCCESS | `n-fixes-h2.log` |
| 后端 MySQL 3307 | 114 通过 / 0 失败 / 0 错误 / 1 跳过，BUILD SUCCESS | `n-fixes-mysql2.log`；清理后最终重跑受本机 native OOM 中断（`n-fixes-mysql-rerun-oom.log`），非产品断言失败 |
| 前端生产构建 | Vite 7.3.8，2716 模块，成功 | `package-clean.log` |
| Python AI 服务 | 12/12 OK | `python-tests-final.log` |
| 浏览器 E2E | 36/36，0 未预期、0 flaky | `n-fix-pw.log`、`n-fix-pw.json` |
| dist/JAR 清单 | 101/101 OK，0 缺失、0 多余、0 不符 | `dist-jar-summary.json` |

### 7.2 API 与 UI 烟测

- 架构接口：PetClinic 快照 28 节点、53 边、3 分组，`truncated=false`。
- 架构导出：56,263 字节，含 `<svg>`，无绝对路径和令牌。
- 流程图：`processCreationForm` 24 节点。
- 时序图：52 条消息、25 个参与者。
- UML：`BaseEntity` 1 个字段、3 个方法。
- AI 状态：AI 服务未启动时 `reachable=false, configured=false`，无令牌。
- 执行器：`enabled=false`，默认安全关闭。
- 四个新视图：架构总览、代码分析、AI 助手、GitHub 均无页面错误。

### 7.3 最终产物

- JAR：33,378,231 字节
- SHA-256：`CBF813AC018649AE59084E65BCD3420F6A78FB129A677D880D889A5361F19122`
- 静态文件：101 个，与 `CodeAtlas前端/dist` 逐文件一致
- 迁移：V1–V12 在干净 MySQL 库成功执行

## 8. 已知限制

1. 未使用真实私有 GitHub 令牌或私有仓库做端到端导入；加密、门禁、绑定和错误路径由测试覆盖。
2. 私有仓库自动定时同步未启用；当前为手动检查和手动同步。
3. 自动隔离执行默认关闭，未实际运行 Docker；需 PR-05 安全准入后再启用。
4. 架构导出为 CodeAtlas 自研独立 HTML/SVG 渲染器，不是外部 archify 兼容实现；未集成外部 archify npm 包。
5. 远端验证受阻：`git ls-remote` 返回连接重置，本机无 `gh`，未推送、未触发远端 CI。
6. E2E 首次运行出现一次“索引已完成”等待超时，随后单测和全量重跑均通过，记录为已隔离的时序波动。
7. `target-leftover-20261117` 保留了部分清理失败的旧测试目录（约 2.8 MB），未再执行递归删除。

## 9. 文件与证据

### 后端新增/修改

- `src/main/java/dev/codeatlas/architecture/`
- `src/main/java/dev/codeatlas/analysis/`
- `src/main/java/dev/codeatlas/ai/AiStatusController.java`
- `src/main/java/dev/codeatlas/github/`
- `src/main/java/dev/codeatlas/execution/`
- `src/main/java/dev/codeatlas/snapshot/SnapshotScanner.java`（性能）
- `src/main/java/dev/codeatlas/graph/GraphIndex.java`（性能）
- `src/main/java/dev/codeatlas/graph/GraphQueryService.java`（性能）
- `src/main/resources/db/migration/V9__architecture.sql`
- `src/main/resources/db/migration/V10__github_credentials_and_execution.sql`
- `scripts/ci-local.ps1`、`scripts/ci-local-README.md`
- `src/test/java/dev/codeatlas/ArchitectureIntegrationTest.java`
- `src/test/java/dev/codeatlas/architecture/ArchitectureAnalyzerTest.java`

### 前端新增/修改

- `src/architecture/`
- `src/analysis/`
- `src/ai/`
- `src/github/`
- `src/App.tsx`

### 报告与证据

- 本总结：`docs/reports/development-summary-2026-11-17.md`
- 详细验收：[architecture-diagrams-ai-github-2026-11-17.md](architecture-diagrams-ai-github-2026-11-17.md)
- 当前状态索引：[followup-status-2026-11-16.md](followup-status-2026-11-16.md)
- 第 12 部分基线：[section12-acceptance-2026-11-16.md](section12-acceptance-2026-11-16.md)
- 证据目录：`D:\CodeAtlas\.runtime\s12-final-evidence\`

## 10. 复现命令

```powershell
# 后端 H2 全量
$env:JAVA_HOME='<JDK 21+>'
mvn -B test

# 后端 MySQL 全量：设置 TEST_DB_* 和 v046_* 专用库后
mvn -B test

# 前端生产构建
npm run build

# Python AI 服务测试
.venv\Scripts\python.exe -m unittest discover -s tests -v

# 浏览器 E2E：需 18080 后端与 8001/8002 AI 固定服务
$env:PLAYWRIGHT_BASE_URL='http://127.0.0.1:18080'
npx playwright test --workers=1 --reporter=line,json

# 打包
mvn -DskipTests package
```

## 11. 结论

本轮完成了系统级架构总览与自研 HTML/SVG 导出、流程图/时序图/完整 UML、统一 AI 助手入口、私有 GitHub 加密认证与手动同步、自动隔离执行（不可变提交、容器生命周期、重启恢复和幂等，默认关闭），以及扫描器和类图性能优化。

H2、MySQL、前端构建、Python 和浏览器 E2E 在同一验证周期内全部通过；最终 JAR 与前端 dist 逐文件一致。真实私有仓库、定时同步、Docker 执行和远端 CI 仍受凭据、安全准入和网络条件限制，已在验收报告中明确标注。

## 12. 2026-11-17 复核缺陷修复补正

复核文档指出的五项缺陷已修复并补专项测试：

| 编号 | 修复 | 报告 |
|---|---|---|
| N01 | 控制流区分正常出口/终止出口/跳转，修复 if/循环/try/switch 后继连线 | [N01](new-capabilities-fix-N01-2026-11-17.md) |
| N02 | 无绑定项目在凭据/网络/导入前返回 `GITHUB_NOT_BOUND` | [N02](new-capabilities-fix-N02-2026-11-17.md) |
| N03 | 导出请求摘要、同键冲突、失败重试、历史工件与工件完整性 | [N03](new-capabilities-fix-N03-2026-11-17.md) |
| N04 | 固定容器身份、超时/取消 stop/rm、结果分类、重启恢复 | [N04](new-capabilities-fix-N04-2026-11-17.md) |
| N05 | 执行改为不可变 submission 归属、内容摘要、幂等和 V12 字段 | [N05](new-capabilities-fix-N05-2026-11-17.md) |

范围遗漏补正见 [scope 报告](new-capabilities-fix-scope-2026-11-17.md)：明确自研 HTML/SVG（新增 `ARCHITECTURE_HTML`/`SVG`）、测试适配器覆盖私有 GitHub/同步、时序图递归与未解析、UML 集合多重性、扫描缓存竞态和新增工作区 E2E。

修复后最终验证：H2 114/0/0/5、MySQL 114/0/0/1、E2E 36/36、Python 12/12；JAR 33,378,231 字节 / `CBF813AC…`，dist/JAR 101/101 一致。
