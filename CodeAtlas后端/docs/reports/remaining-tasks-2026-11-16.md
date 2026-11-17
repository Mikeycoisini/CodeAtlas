# 剩余任务执行与验收记录（2026-11-16）

对应《CodeAtlas 剩余任务开发与验收交接说明（2026-11-16）》的 T01–T12。本轮已完成 MySQL 全量与 V6→V8 迁移/重启演练（T02），其余可执行项完成情况如下。

## 1. 基线与环境

| 项 | 值 |
|---|---|
| 后端 | Java 24 运行、target Java 21、Spring Boot 3.5.16、JDBC/Flyway V1–V8 |
| 运行包 | `target/codeatlas-0.4.6.jar`，36,469,810 字节，SHA-256 `B8F23A21C1FB641A79756BF7ED12B465F40F44AC7352B4BDCAFFC7B3ADA306AB`（2026-11-16 16:53 F02 与 GitProbe 子目录状态修复后重打包；历史 36,469,709 / `36bb349b…` 为 12:49 版本） |
| 前端 | React 19 + TypeScript + Vite；`npm run build` 通过，dist 已进入上述 JAR |
| AI | 本机 18001 FastAPI + 8002 固定 provider（确定性响应），内部令牌 `v046-fixture-token` |
| 数据库 | 专用 MySQL 8.0.43 @3307，库 `v046_browser`（浏览器）与 11 个 `v046_*` 测试库；数据目录 `target/browser-data-046`、`target/mysql-test-data` 等 |
| 浏览器 | Playwright 35 项，串行 1 worker（后端任务队列为单工作线程），baseURL `http://127.0.0.1:18080` |
| MySQL 全量 | 84 项，0 失败，1 跳过（Windows junction 沙箱限制）；证据 `target/mysql-current.log` |

## 2. 本轮修复

1. **前端任务状态被旧快照覆盖**：导入完成后自动索引任务开始，快照刷新把 `snapshot.latestTask`（仍为 QUEUED）写回，任务 id 未变导致轮询不再启动，界面长期停在“索引等待处理”。修复为同一快照只以轮询结果为准（`CodeAtlas前端/src/App.tsx`）。这是 explore/分页 4 项失败的根因。
2. **实践证据计数单位**：原 `EVIDENCE_V2` 按报告 ID 统计，同一提交的两份 PASSED 报告可累计为两次通过。改为按 `submissionId` 去重并升级 `EVIDENCE_V3`，历史 V2 判断保留原 rule_version（`src/main/java/dev/codeatlas/progress/EvidenceService.java`、[ADR-009](../adr/ADR-009-practice-evidence.md)）。
3. **测试基础设施**：新增 `CodeAtlas前端/tests/artifacts.ts` 让截图输出可通过 `PW_ARTIFACTS` 指向可写目录；Playwright 配置固定 `workers: 1`；分页/探索用例改为等待自动索引终态，超时放宽；修复新增练习用例的折叠差异定位与严格模式冲突。

## 3. T01 启动与基线

- JAR 直接启动（18080），MySQL 3307、AI 18001、固定 provider 8002 均可访问；`/api/session` 建立本机会话。
- AI 联调使用固定 provider：实践反馈 `SUCCEEDED/PARTIAL`、引用 `VERIFIED`，未向真实模型外发。
- 未验证：IDE 直接运行 Python 的交互确认、共享令牌损坏等异常注入；模型密钥仍为空，真实模型调用未开放。

## 4. T02 数据升级与重启

MySQL 全量（最终代码）：**84 项，0 失败，仅 1 项 junction 测试跳过**，日志 `target/mysql-current.log`。

迁移/重启演练（`target/mysql-upgrade/summary.json`）：

1. 在空库 `v046_upgrade` 上由当前 JAR 迁移到 V8，创建真实旧资源：项目/快照/索引（5 文件、11 符号、9 关系、1 诊断）、知识、卡片与两次复习、文档、训练任务与提交、练习副本/提交/外部报告、知识与实践证据、引导（4 次事件，CODE 步骤跳过）。
2. 记录升级前资源身份（`before.json`），随后模拟 V6：删除 `relation_corrections`、删除 `source_type`/`source_ref` 列并删除 Flyway 7/8 历史；校验版本为 `1,2,3,4,5,6` 且列已不存在。
3. 重启当前 JAR：Flyway 重新应用 V7/V8（`7:1,8:1`），新列默认值检查为 `0,0,0,1`（非 LOCAL 项目 0、非 LOCAL 任务 0、非空 source_ref 0、修正表存在）。升级前后资源身份完全一致。
4. 同库同数据目录再次重启：资源身份仍完全一致。
5. 备份恢复：`mysqldump` 导出后导入 `v046_restore` 并复制数据目录，启动 18200 实例校验项目、知识标题、提交摘要、进度状态与 4 条证据，Flyway 版本 `1–8`，全部一致。

说明：这是在同一构建上通过“降级模拟”构造的 V6 基线，验证了 V7/V8 的前向迁移与旧资源保留；未使用更早版本运行包创建旧数据。

## 5. T03 练习 UI 与全套浏览器回归

结果：**35/35 通过（4.2 分钟，1 worker）**，JSON 证据 `target/pw-result-046.json`，失败 trace 目录 `target/pw-output-046`（本轮无失败）。

新增/扩展覆盖（`CodeAtlas前端/tests/practice.spec.ts`）：

- 创建独立副本 → 第一次提交 → 第二次修改再提交；旧存档 diff 不变（v1 不包含 v2）。
- 四类外部结果 PASSED/FAILED/ERROR/TIMEOUT；同一幂等键重试返回同一报告 ID；所有报告 `contentDigest` 与提交摘要一致。
- 提交与报告关联知识；进度视图显示“用户报告通过的实践测试 1 次”、报告正文与“查看代码实践”。
- AI 预览 → DENY 后生成返回 403（不外发）→ ALLOW 生成 `SUCCEEDED/PARTIAL/VERIFIED`。
- 关闭确认（接受 dialog）→ 工作区 `CLOSED`，已保存提交仍可读取。

既有套件同时覆盖：探索/分批、图谱三视图与历史代次、学习与 AI 引用、训练草稿/判分、复习、进度、引导重排/跳过/退出/恢复、窄屏与深色主题、键盘焦点。

## 6. T04 V03-06 真实项目修改（PetClinic）

样本：`D:\CodeAtlas\.runtime\spring-petclinic`，commit `500158f732419217507c7656904b8e6aa1bcc0d6`，GIT_CLEAN，原仓库未被修改。

| 步骤 | 证据 |
|---|---|
| 静态导入/索引 | 132 文件、323 符号、0 诊断；导入 53.9s、索引 8.9s（50 个 Java 文件） |
| 修改前失败 | 先只加测试，`mvn -Dtest=OwnerTests test` 编译失败：`找不到符号 getDisplayName()`（`target/petclinic-046/before.log`） |
| 修改 | Owner 增加 `getDisplayName()`；OwnerTests 增加断言（副本内） |
| 真实测试 | 同一副本 `mvn -Dtest=OwnerTests test` 退出码 0，`Tests run: 3, Failures: 0, Errors: 0` |
| 产品提交 | 提交 1（含 Maven 构建产物，121 个变更）与提交 2（移除 target，仅 Owner.java + OwnerTests.java 两个变更） |
| 外部报告 | 报告绑定提交摘要，正文含失败/通过命令、JDK 17.0.12、Maven 3.6.3 与退出码；来源 `USER_REPORTED` |
| 复盘与追溯 | 提交/报告/知识均可通过 ID 追溯；原仓库 `git status` 为空 |

说明：Maven 构建产物按现有练习提交规则进入存档；第二次提交前删除 `target`，用于区分“构建产物”与“源码变更”。证据 JSON：`target/petclinic-046/summary.json`。

## 7. T05 V04-05 学习闭环

- 真实报告（提交 2 的 PASSED）关联知识后：`PRACTICING`、practicePasses=1；第二次独立提交的报告关联后：`SUPPORTED`、practicePasses=2、`EVIDENCE_V3`。
- 同一知识创建卡片并完成两次可追溯复习（GOOD、EASY，历史含 ENROLL/两条 REVIEW）；进度显示 positiveRecalls=2。
- 追加 ERROR 报告后：证据保留、`eligible=false`、状态仍为 `SUPPORTED`（环境错误不降低判断）。
- 引导的跳过/重排/退出/恢复由 `progress-guides.spec.ts` 浏览器用例覆盖并通过。
- 证据 JSON：`target/petclinic-046/learning.json`；规则修订见 ADR-009。

## 8. T06 分析准确性（PetClinic 固定版本）

19/19 项核对通过（`target/petclinic-analysis.json`）：

- OwnerController 类与 7 个关键方法、@Controller 注解；OwnerRepository 识别为接口且有 `findByLastNameStartingWith`；VetRepository 接口与 `findAll()`/`findAll(Pageable)` 两个重载，均保留 @Transactional。
- PetClinic 的 `Repository`/`JpaRepository` 父类型不在索引内，事务关系按保守规则省略（注解仍显示）；自包含样本（显式 import + 无未解析祖先）生成 `TRANSACTION_BOUNDARY / INFERRED / SPRING_TRANSACTIONAL_DECLARATION_V1`，两侧行为均符合预期。
- 132 文件 0 诊断；TYPE 图 894 节点/1171 边（首页 80/141，未解析 47）、PACKAGE 850/942、CALL（showOwner 为中心）42/41。
- 未覆盖：Controller→Repository 的运行时绑定、Spring 代理与 AOP、组合注解；这些继续标记为运行时未知。

## 9. T08 性能基线（PetClinic，含 UI/JAR 进程 512m 堆）

| 操作 | 观测 |
|---|---|
| 导入 132 文件 | 53.9s（含 Git 探测与双重扫描校验） |
| 索引 50 Java 文件 | 8.9s |
| 符号查询（Owner，≤100） | 8 ms |
| 文件分页（200） | 6.7 ms |
| PACKAGE 图（1000 边上限） | 52.3 ms |
| TYPE 图 | 51.8 ms |
| CALL 图（showOwner 焦点） | 20.7 ms |

仅在当前设备与本机 MySQL 下测量一次；未做分位统计、并发与大样本压力，也未与布局 Worker 耗时拆分。

## 10. T09 定向安全与边界

12/12 预期拒绝/放行符合（`target/security-046.json`）：

- 无令牌 401、跨源 Origin 403、跨站 Sec-Fetch-Site 403、非 JSON POST 415、>1MiB 413。
- GitHub 非官方主机与非 HTTPS 400 `INVALID_GITHUB_URL`；分页 limit=1001/offset=-1 400 `INVALID_PAGE`；未知快照/任务 404 不泄露内容。
- 既有 Java 测试覆盖路径穿越、junction/重解析点、文件替换竞态、导入边界、GitHub 超时/取消等（84 项 H2 全量，0 失败、5 跳过）。

## 11. T10 工程与文档

- 已更新：`README.md`、`contracts/v0.4-review-evidence-guides.md`、`docs/requirements/scope-v0.4.md`、`scope-v0.4.5.md`、`docs/architecture/domain-model.md`、`docs/reports/v0.4.5-practice-evidence-acceptance.md`、`v0.4.6-practice-ai-feedback-acceptance.md`、`v0.3-acceptance.md`、`docs/adr/ADR-009-practice-evidence.md`。
- `contracts/openapi.yaml` 已包含 GitHub 导入、练习工作区/提交/报告与 `practice/submissions/{id}/ai-context`。
- 未完成：Git remote/提交（需用户明确授权）、CI 配置与实际运行、Python settings 挂起测试排查。

## 12. T11/T12 与遗留

- T11 流程图/时序图/完整 UML/导出仍属新功能，未在本轮排期；现有三视图与历史恢复已回归。
- T12：上述打包与 JAR 启动验证完成；发布清单中的 remote、CI、真实模型语义评估和 PR-05 仍保留为后续项。
- 真实 AI 语义质量未评估（无模型密钥）；固定 provider 仅证明结构、引用与授权边界。
