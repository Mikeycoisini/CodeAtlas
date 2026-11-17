# CodeAtlas 功能补齐与缺陷修复总结（2026-11-16）

本文汇总本轮从 V0.1–V0.4 剩余功能审计出发，完成的五项功能补齐、两项关键缺陷修复、测试基础设施调整与最终验收证据。详细执行记录见 [remaining-tasks-2026-11-16.md](remaining-tasks-2026-11-16.md)，阶段验收入口见 [v0.3-acceptance.md](v0.3-acceptance.md)。

## 1. 总览

| 版本 | 能力 | 主要交付 |
|---|---|---|
| 0.4.2 | GitHub 公开仓库导入 | 受控浅克隆、URL/ref 校验、超时/取消、暂存清理、V7 迁移 |
| 0.4.3 | 关系人工修正 | 追加式修正覆盖层、原始关系保留、关系图证据联动、V8 迁移 |
| 0.4.4 | 大结果集浏览 | 诊断/关系每页 200、总数显示、加载更多、迟到响应隔离 |
| 0.4.5 | 实践成果作为知识证据 | `PRACTICE_SUBMISSION`/`PRACTICE_TEST_RESULT`、`EVIDENCE_V3` |
| 0.4.6 | 代码修改 AI 反馈 | `PRACTICE_FEEDBACK` 上下文冻结、授权链路、结果与引用校验 |
| 本轮修复 | 自动索引状态、证据计数、测试基础设施 | 见第 3 节 |

最终运行包：`target/codeatlas-0.4.6.jar`，36,469,709 字节，SHA-256 `36bb349be4f058f970cbedc9e9ae1a2cceb63d9c8b8fdc86b2ce096152e3ee5b`（2026-11-16 12:49 重打包，包含当前前端与 `EVIDENCE_V3`）。

## 2. 功能新增

### 2.1 GitHub 公开仓库导入（0.4.2）

**解决的问题**：原系统只能导入本地目录，无法复现 GitHub 上已有项目。

**实现**：
- `GitHubFetcher`：仅接受 `https://github.com/{owner}/{repo}` 形式的公开仓库，ref 校验后执行受控浅克隆；设置超时、支持取消，临时 staging 目录在成功/失败/取消后清理，并提供 `cleanupStale()` 回收遗留目录。
- `POST /api/imports/github` 接受 `url`、`ref`，创建标准 IMPORT 任务，后续快照/索引流程与本地导入一致。
- 快照表/任务表增加来源字段：`source_type`（LOCAL/GITHUB）与 `source_ref`，由 Flyway V7 迁移引入。
- 前端导入弹窗支持“本地 / GitHub”切换并显示来源标记。

**契约与决策**：`contracts/v0.4.2-github-import.md`、`docs/adr/ADR-007-github-import.md`。

**验证**：URL/ref 非法、非 HTTPS、非 GitHub 主机均拒绝；克隆超时与取消可诊断；重复导入同一 commit 生成稳定快照身份（测试通过初始化确定性 Git 仓库消除外层工作区影响）。

### 2.2 关系人工修正（0.4.3）

**解决的问题**：静态分析产生的推断关系无法人工纠正，纠错结果也无处留存。

**实现**：
- `RelationCorrectionService`：追加式修正记录（`CORRECTED`/`REJECTED`/`UNCERTAIN`），原始关系永不修改；列表与关系图证据按“原始值 + 生效值 + 历史”叠加展示。
- Flyway V8 新增 `relation_corrections` 表（外键绑定快照、代次、关系）。
- 前端 `RelationCorrection.tsx` 提供修正弹窗，`RelationCard.tsx` 显示原始/生效状态与修正历史。

**契约与决策**：`contracts/v0.4.3-relation-corrections.md`、`docs/adr/ADR-008-relation-corrections.md`。

**验证**：修正后关系列表与图证据生效、原始关系保留、重复提交幂等、跨代次/快照身份校验。

### 2.3 大结果集诊断与关系浏览（0.4.4）

**解决的问题**：诊断/关系一次性全量渲染，超过 200 条时页面卡顿且无法继续查看。

**实现**：
- 诊断与局部关系按每页 200 加载，标题显示“已加载 / 总数”，提供“加载更多”；加载完毕按钮消失。
- 使用请求身份（身份字符串 + AbortController）隔离迟到响应，切换符号或快照后旧响应不会覆盖新结果。
- 后端分页参数校验：`limit` 1–1000、`offset` ≥ 0，非法返回 `INVALID_PAGE`。

**验证**：`tests/pagination.spec.ts` 覆盖 201 条文件诊断与 201+ 条关系的总数、加载更多、按钮消失；迟到响应隔离由既有探索用例覆盖。

### 2.4 实践成果关联知识证据（0.4.5）

**解决的问题**：练习提交与用户外部测试报告已保存，但未进入知识证据与掌握判断。

**实现**：
- 新增证据来源：
  - `PRACTICE_SUBMISSION`：绑定提交，`USER_PRACTICE`、`eligible=false`，只作成果关联。
  - `PRACTICE_TEST_RESULT`：绑定用户报告，`USER_REPORTED`；PASSED 正证据、FAILED 负证据、ERROR/TIMEOUT 不计能力（`qualifier=ENVIRONMENT`）。
- 掌握规则升级为 `EVIDENCE_V3`：两次**不同提交**的用户报告通行可达到 SUPPORTED；同一提交多份报告只计一次；历史 V2 判断保留原 `rule_version`。
- 进度视图显示实践通过次数、提交摘要、报告状态/方法/环境/正文与限制说明；练习面板提供“关联代码提交到知识”“关联该报告到知识”。

**契约与决策**：`contracts/v0.4-review-evidence-guides.md`、`docs/adr/ADR-009-practice-evidence.md`。

**验证**：`ProgressIntegrationTest` 覆盖同提交两份报告仍为 PRACTICING（1 次）、第二份独立提交 PASSED 后 SUPPORTED（2 次）、ERROR 不降级、FAILED 进入 NEEDS_REVIEW；浏览器用例覆盖关联后进度页显示报告正文与“查看代码实践”。

### 2.5 代码修改 AI 辅助反馈（0.4.6）

**解决的问题**：AI 反馈只绑定理解题作答，无法针对代码修改、差异与外部测试报告给出建议。

**实现**：
- 新接口 `POST /api/practice/submissions/{id}/ai-context`，操作 `PRACTICE_FEEDBACK`。
- 上下文冻结：任务目标/约束/验收、提交摘要、差异（预算 16000 字符）、用户外部报告（预算 6000 字符，标记 `USER_REPORTED`）、任务来源与“CodeAtlas 未执行代码”限制；总量限制 24k/64KiB。
- 复用 AI 预览 → 用户授权（ALLOW/DENY）→ 生成 → 取消/错误处理链路；未授权不外发。
- Java 结果校验：`PASS/PARTIAL/NEEDS_WORK/UNSURE`，结果标记 `AI_ASSISTED`，引用逐条校验位置。
- 前端 `PracticeAiPanel.tsx` 显示预览范围、授权、优点/待改进/说明/引用/不确定项，采纳由用户决定。
- Python AI 服务新增 `PracticeFeedback` schema、提示词与 `OPERATIONS` 映射，固定 provider 提供确定性输出。

**契约与决策**：`contracts/openapi.yaml`、`docs/adr/ADR-010-practice-ai-feedback.md`。

**验证**：`AiIntegrationTest.practiceFeedbackUsesFrozenDiffAndUserReportWithConsent` 覆盖冻结内容、DENY 不外发、ALLOW 生成、引用 VERIFIED；浏览器用例覆盖预览/拒绝/允许/生成与弹窗关闭。

## 3. 本轮缺陷修复

### 3.1 自动索引任务状态被旧快照覆盖（高影响）

- **现象**：导入成功后前端自动开始索引，但任务条长期停在“索引等待处理 0 个文件”，诊断/符号/关系一直为空；后端任务实际早已 `PARTIAL_SUCCESS`。
- **根因**：导入完成回调先在快照为旧值时拉取快照详情，随后轮询把索引任务更新到终态；诊断副作用再次执行时用 `snapshot.latestTask`（QUEUED 旧对象）覆盖任务状态。由于任务 id 未变，轮询 effect 不会重启，界面永久停在 QUEUED。
- **修复**：同一快照的任务状态只以轮询结果为准，仅当展示的是其它快照任务时才切换（`CodeAtlas前端/src/App.tsx`）。
- **影响**：修复了 explore/分页 4 项浏览器用例失败，并消除真实用户遇到“索引永远等待”的可能。

### 3.2 实践证据重复计数（正确性）

- **现象**：同一提交上传两份 PASSED 报告即可让知识达到 `SUPPORTED`，把“两份报告”误当成“两次独立实践”。
- **修复**：实践通过次数改为按 `submissionId` 去重，规则升级 `EVIDENCE_V3`；同步更新 ADR-009、契约、README、范围文档与集成测试。
- **兼容**：`mastery_assessments` 历史行保留原 `rule_version`，不重写旧判断。

### 3.3 测试基础设施与用例

- Playwright 固定 `workers: 1`：后端任务队列为单工作线程，默认 8 worker 会造成导入排队与超时。
- 新增 `tests/artifacts.ts`：截图输出支持 `PW_ARTIFACTS` 指向可写目录，隔离环境下不再因截图写入失败。
- 探索/分页用例改为等待自动索引终态并放宽超时；修复导入弹窗标题变更导致的用例过时；修复新练习用例的折叠差异定位与严格模式冲突。
- 新增练习面板用例：二次提交与旧存档不变、四类外部结果、同键重试、报告摘要绑定、知识证据与进度展示、AI 拒绝/允许/生成、关闭确认与清理后提交保留。

## 4. 验证汇总

| 验证 | 结果 | 证据 |
|---|---|---|
| Java H2 全量 | 84 项，0 失败，5 跳过 | `mvn -DforkCount=0 test` |
| Java MySQL 全量 | 84 项，0 失败，1 跳过（junction） | `target/mysql-current.log` |
| 浏览器 E2E | 35/35 通过（串行 1 worker，4.2 分钟） | `target/pw-result-046.json` |
| PetClinic 真实修改 | 修改前编译失败；修改后 3/3 测试通过；提交/报告/知识证据闭环 | `target/petclinic-046/summary.json` |
| 学习闭环 | 两次复习（GOOD/EASY）、`EVIDENCE_V3 SUPPORTED`、ERROR 不降级 | `target/petclinic-046/learning.json` |
| 分析准确性 | PetClinic 19/19 项核对通过 | `target/petclinic-analysis.json` |
| 性能基线 | 导入 53.9s（132 文件）、索引 8.9s（50 Java 文件）、图查询 20.7–52.3ms | 同上 summary |
| 定向安全 | 12/12 预期拒绝/放行 | `target/security-046.json` |
| V6→V8 迁移 | 降级模拟 → 升级 7/8 成功；资源前后一致；重启一致 | `target/mysql-upgrade/summary.json` |
| 备份恢复 | 另一库 + 数据目录副本校验一致（Flyway 1–8） | `target/mysql-upgrade/restore.json` |

## 5. 关键文件索引

**后端**
- `src/main/java/dev/codeatlas/github/GitHubFetcher.java`
- `src/main/java/dev/codeatlas/relations/RelationCorrectionService.java`
- `src/main/java/dev/codeatlas/progress/EvidenceService.java`
- `src/main/java/dev/codeatlas/ai/AiContextService.java`、`AiResultValidator.java`
- `src/main/java/dev/codeatlas/training/PracticeController.java`、`PracticeService.java`
- `src/main/resources/db/migration/V7__github_import.sql`、`V8__relation_corrections.sql`

**前端**
- `src/App.tsx`（分页、任务轮询与快照状态同步）
- `src/relations/RelationCorrection.tsx`、`RelationCard.tsx`
- `src/training/PracticePanel.tsx`、`PracticeAiPanel.tsx`
- `tests/practice.spec.ts`、`pagination.spec.ts`、`explore.spec.ts`、`tests/artifacts.ts`

**AI 服务**
- `codeatlas_ai/schemas.py`、`provider.py`、`tests/fake_provider.py`

**契约与决策**
- `contracts/v0.4.2-github-import.md`、`v0.4.3-relation-corrections.md`、`v0.4-review-evidence-guides.md`、`openapi.yaml`
- `docs/adr/ADR-007`～`ADR-010`

## 6. 已知限制与后续

- AI 验证使用本机固定 provider，真实模型语义质量未评估（无密钥）；发生真实调用前仍需预览与授权。
- 系统隔离执行（PR-05）未开放，外部测试报告真实性由用户负责。
- PetClinic 验证为一个固定样本；组合注解、Spring 代理与运行时绑定继续标记为未知。
- V6 基线通过降级模拟构造，未使用更早版本运行包创建旧数据；备份恢复为单机演练。
- Git remote/提交与 CI 需用户授权；流程图/时序图/完整 UML/导出仍属新功能。
