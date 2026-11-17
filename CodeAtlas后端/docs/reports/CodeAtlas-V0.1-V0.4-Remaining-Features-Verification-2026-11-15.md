# CodeAtlas V0.1～V0.4 未完成能力核对结果

核对日期 2026-11-15，状态修订 2026-11-16。核对对象为 `D:\CodeAtlas\docs\reports\CodeAtlas-V0.1-V0.4-Remaining-Features-Audit-2026-11-15.md`（下称“原审计”）。本文件是该审计的完成度复核结论，不替代原审计及各版本验收记录。

## 1. 总体结论

原审计未全部完成。第 2 节列出的五个实现缺口已在 v0.4.2～v0.4.6 闭环，并有对应范围、契约、ADR 与验收记录；第 3～6 节的能力与工程欠账大部分仍在；第 7 节追踪表中 IMP-01 P1、PR-04 状态已前进，PR-05 未变；第 8 节旧发现结论未变。

| 原审计章节 | 复核结果 |
|---|---|
| 2.1 GitHub 仓库导入 | 已完成，版本 0.4.2 |
| 2.2 关系人工修正与来源留存 | 已完成，版本 0.4.3 |
| 2.3 大结果集诊断与关系浏览 | 已完成，版本 0.4.4 |
| 2.4 代码修改训练的 AI 反馈 | 已完成，版本 0.4.6 |
| 2.5 代码实践成果关联知识记录 | 已完成，版本 0.4.5 |
| 3 图形能力后续范围 | 未完成，仅三个首批视图 |
| 4 实现存在但缺完整验证 | 未完成，证据缺口未补 |
| 5 工程交付与文档同步欠账 | 部分完成，3 项文档已修，其余仍在 |
| 6 后续范围及未冻结设想 | 未开始 |
| 7 PRD 全量功能追踪 | 部分前进，PR-05 未变 |
| 8 旧发现及残余限制 | 未变 |

时间线依据：原审计文件写入时间为 18:24，v0.4.2～v0.4.6 验收记录写入时间为 20:05～21:46。

## 2. 已闭环项

### 2.1 GitHub 公开仓库导入（0.4.2）

- 新增 `POST /api/imports/github`，支持公开 github.com 仓库根地址与可选分支／标签。
- URL 与 ref 白名单校验、受控浅克隆、独立 HOME 配置、超时取消、staging 清理均已实现。
- 复用既有快照与索引流水线，V7 迁移记录 `source_type` 与 `source_ref`。
- 依据：`src/main/java/dev/codeatlas/github/GitHubFetcher.java`、`contracts/v0.4.2-github-import.md`、`docs/reports/v0.4.2-github-import-acceptance.md`、`docs/adr/ADR-007-github-import.md`。

### 2.2 关系人工修正与修正来源留存（0.4.3）

- 新增 `relation_corrections` 追加式覆盖层，支持 `CORRECTED`／`REJECTED`／`UNCERTAIN`，强制理由、可选来源。
- 原始关系、证据状态和图边聚合不被改写；查询同时返回原始值与 `effectiveKind`／`effectiveTargetText` 及完整历史。
- 源码页与图谱证据面板均已接入修正弹窗和历史展示。
- 依据：`src/main/java/dev/codeatlas/relations/RelationCorrectionService.java`、`src/main/java/dev/codeatlas/web/RelationCorrectionController.java`、`src/relations/RelationCorrection.tsx`、`contracts/v0.4.3-relation-corrections.md`、`docs/reports/v0.4.3-relation-corrections-acceptance.md`、`docs/adr/ADR-008-relation-corrections.md`。

### 2.3 大结果集诊断与旧关系列表完整浏览（0.4.4）

- 文件诊断与局部关系改为每页 200 条，显示“已加载／总数”、剩余条数和“加载更多”。
- 切换符号、代次或任务时丢弃迟到的分页响应。
- 依据：`CodeAtlas前端/src/App.tsx`（诊断请求、关系请求、加载更多）、`docs/requirements/scope-v0.4.4.md`、`docs/reports/v0.4.4-large-result-browsing-acceptance.md`。

### 2.4 代码修改训练的 AI 反馈（0.4.6）

- 新增 `POST /api/practice/submissions/{id}/ai-context`，操作 `PRACTICE_FEEDBACK`。
- 上下文冻结任务目标／约束／验收条件、提交摘要、按预算截断的差异、用户外部报告及“未执行代码”限制；未授权不外发。
- AI 服务新增 `PRACTICE_FEEDBACK` schema；前端练习提交增加“请求 AI 反馈”面板。
- 依据：`src/main/java/dev/codeatlas/training/PracticeController.java`、`CodeAtlas前端/src/training/PracticeAiPanel.tsx`、`docs/reports/v0.4.6-practice-ai-feedback-acceptance.md`、`docs/adr/ADR-010-practice-ai-feedback.md`。

### 2.5 代码实践成果关联知识掌握记录（0.4.5）

- 证据来源扩展 `PRACTICE_SUBMISSION` 与 `PRACTICE_TEST_RESULT`：提交只作成果，PASSED 正、FAILED 负、ERROR／TIMEOUT 不计能力。
- 掌握规则升级 `EVIDENCE_V2`：两次用户报告通过可达到 SUPPORTED。
- 练习面板增加“关联代码提交到知识”“关联该报告到知识”，进度视图展示实践次数、摘要与报告详情。
- 依据：`src/main/java/dev/codeatlas/progress/EvidenceService.java`、`CodeAtlas前端/src/training/PracticePanel.tsx`、`docs/requirements/scope-v0.4.5.md`、`docs/reports/v0.4.5-practice-evidence-acceptance.md`、`docs/adr/ADR-009-practice-evidence.md`。

## 3. 部分完成的文档同步项

原审计第 5 节有三项已修正：

| 项目 | 当前状态 |
|---|---|
| 关系证据契约 | `docs/contracts/code-evidence.md` 已记录 `SPRING_TRANSACTIONAL_DECLARATION_V1` 规则及 TRANSACTION_BOUNDARY 候选 |
| OpenAPI Relation.kind | 已包含 `TRANSACTION_BOUNDARY`，修正请求的 `correctedKind` 同步 |
| 安全文档请求体说明 | `docs/security/threat-model-v0.md` 已改为实际 1 MiB 上限，并补充 0.4.2／0.4.3 边界 |

## 4. 仍未完成项

### 4.1 图形能力（原审计第 3 节）

- 已交付：包级架构图、类／接口关系图、一跳局部调用图。
- 未交付：业务流程图、时序图、完整 UML 成员属性／可见性／多重性、archify 独立 HTML 与图形导出。
- 现状依据：`CodeAtlas后端/docs/requirements/scope-v0.4.1.md`、`CodeAtlas后端/docs/reports/v0.4.1-acceptance.md`、前端仅有 `src/graph/` 三个视图组件，无流程图／时序图模块；后端图谱为一跳邻居，无多跳参数。

### 4.2 验证证据缺口（原审计第 4 节）

| 项目 | 仍缺证据 |
|---|---|
| V03-06 真实修改练习贯通 | 获授权真实 Java 项目的一次实际修改／补测试、外部执行、日志与复盘；阶段验收入口 `docs/reports/v0.3-acceptance.md` 已补齐，真实场景仍待验收 |
| 练习副本专用浏览器流程 | 已有 `CodeAtlas前端/tests/practice.spec.ts`；执行通过证据及关闭清理、实际关联知识和完整 AI 生成覆盖仍待补齐 |
| V04-05 完整学习闭环 | 真实项目修改成果进入知识证据的完整链路仍无真实场景验收 |
| V01-07 分析准确性 | 广泛人工对照、准确率与未解析分母、复杂 Spring 配置核验 |
| V02-07 及后续 AI 质量 | 真实模型多类型人工语义评估；0.4.6 使用固定服务验证结构 |
| 性能和资源 | 大项目加载、索引与图聚合压力、峰值资源、弱网；主包约 3.65 MB |
| 完整安全边界 | 进程级源码外发审计、文件竞争、独立安全评估 |

2026-11-16 更正。target/mysql-046.log 已记录 MySQL 全量 81 项、0 失败、1 跳过；mysql-046-upgrade.log 与 mysql-046-restart.log 分别记录 V6→V8 迁移与同库重启，各 5 项测试通过。当前最高迁移为 V8。旧业务资源逐项保留对照与新增浏览器 E2E 尚缺证据。最新 0.4.6 验收已补 H2 全量与 JAR 成功打包记录，先前页面文件不足属于历史失败。自动令牌的后续源码变更仍需独立打包验证。

### 4.3 工程交付与文档欠账（原审计第 5 节）

| 项目 | 核对结果 |
|---|---|
| 独立 Git 基线与 CI | 仅 `CodeAtlas后端` 有 `.git`，单个初始提交 `6b2a760`，工作区大量未提交；前端、AI、根目录无 Git；未找到项目 CI 配置 |
| 统一领域模型／ER 文档 | `docs/architecture/domain-model.md` 仍不存在 |
| 独立任务／ADR 模板 | 未找到 `docs/agent-task-template.md` 或独立 ADR 模板 |
| V0.3／V0.4 旧范围 | `docs/requirements/scope-v0.3-training.md`、`docs/requirements/scope-v0.4.md` 仍把 V03-03 之后的副本与执行验证写成后续 |
| 根目录复用报告、UI 审计、黄金样本 | 文件未更新；`CodeAtlas前端/UI_AUDIT_AND_REDESIGN.md` 仍称“没有独立的类图、调用图和流程图数据视图”，与 0.4.1 已交付图谱不符；黄金样本仍写“真实项目的路径和版本待用户提供” |
| UI 维护及可访问性 | 双层硬编码样式、深层弹窗焦点约束未处理 |

### 4.4 后续范围（原审计第 6 节）

均未开始：自动隔离编译／测试执行及 Worker、项目／快照删除与保留回收、产品内备份恢复入口、多用户与公网协作、全项目自动检索汇总与跨文档问答、自动技术栈识别与语义业务模块、多跳调用探索、复杂综合挑战与变异测试、Neo4j／向量检索／LangGraph 接入。

现状：`PracticeService` 仍只保存用户外部报告，系统不执行代码；`AtlasController` 无项目或快照删除接口。

### 4.5 PRD 追踪变化（原审计第 7 节）

- IMP-01 P1：由“未实现”改为已实现（0.4.2）。
- EXP-04：说明与关系修正已有，业务流程图仍后续。
- PR-04：已补实践提交／差异／外部报告的 AI 反馈，五类实操任务的完整覆盖仍缺验收记录。
- PR-05：自动隔离执行仍为 P2，未开放。
- 其余需求状态未变。

### 4.6 旧发现（原审计第 8 节）

D01、D02、D03、D03-01、D03-02、D04-01 的结论与残余限制未变，本轮无新增证据。

## 5. 核对方法与限制

- 采用静态核对：读取产品源码、契约、迁移、测试源码与验收记录，未重新运行 HTTP、浏览器、构建或模型调用。
- 原审计附录所列 64 份文档仍为基线，本轮新增核对 0.4.2～0.4.6 的范围、契约、ADR、验收及新增源码目录。
- “已完成”指找到对应实现与验收记录，不等于已通过真实环境验证；第 4.2 节的证据缺口仍然适用。

## 6. 建议优先级

1. 更新已有 `v0.3-acceptance.md`，补齐真实项目修改记录，并运行、完善已有练习面板浏览器用例。
2. 以既有 MySQL 全量和 V6→V8 日志为历史基线，补旧业务资源逐项对照及新增版本浏览器 E2E；针对后续源码变更执行相应回归和最终打包。
3. 更新 `scope-v0.3-training.md`、`scope-v0.4.md`、`UI_AUDIT_AND_REDESIGN.md` 与黄金样本说明，标注被哪些新记录补充。
4. 补 `docs/architecture/domain-model.md` 与任务／ADR 模板，建立后端以外工程的 Git 基线与最小 CI。
5. 图形后续能力（流程图、时序图、UML、导出）与第 6 节范围另行排期，不在当前完成判定内。
