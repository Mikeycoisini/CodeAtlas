# CodeAtlas V0.1～V0.4 未完成能力核对

核对日期为 2026-11-15，当前文件基线包含 0.4.1 交互关系图。核对对象为项目自有文档、产品源码、API 契约、迁移、测试源码和已有验收证据。本轮采用文档与代码静态核对，并复核历史测试记录。运行验证状态均按原报告标注。

## 1. 范围与判断依据

本轮清点 64 份项目文档及契约文件，包括根目录十份规划／复用报告、四份参考资料、三个工程的说明、后端范围／设计／计划／ADR／契约／验收记录，以及根目录独立测试和修复报告。OpenAPI 以结构化方式核查 81 个路径和 69 个 Schema。完整文件清单见附录。

功能范围采用 PRD、阶段范围和用户已批准的后续设计综合判断。原始讨论、待评审总体计划、固定日期的独立报告和复用候选均保留各自时间与状态。范围变动以对应的新设计、契约、当前代码和验收证据核对。

本文中的“已有实现”表示找到对应的产品代码。“部分实现”表示已有入口但相关需求链路尚未覆盖完整。“待验收”表示实现存在，完整验证证据仍有缺项。“后续扩展”表示文档已安排在后续，具体版本可能尚未确定。

清点排除依赖目录、构建产物、运行数据、临时目录和含本机配置的 application.yml。归档样本与第三方许可文件单独处理，作为对应报告的佐证。当前目录缺少 Git 元数据，无法给出分支和 Commit 基线。本轮没有重新运行 HTTP、浏览器、构建或模型调用。

## 2. 当前功能缺口与实现不完整处

### 2.1 GitHub 仓库导入

**状态为未实现，归属 IMP-01 P1，属于项目导入的后续增强。** 本地路径导入已具备。当前缺少仓库 URL 导入、远程获取和相应认证流程。PRD 同时保留了认证方式待设计，文件中没有将其冻结为 V0.1 P0 出口。[需求依据](D:/CodeAtlas/CodeAtlas-PRD-V1.0.md:80)

前端发送本地 path，后端 `/imports` 对应本地目录导入。当前 API 未提供远程仓库导入操作。[前端入口](D:/CodeAtlas/CodeAtlas前端/src/App.tsx:486) [导入接口](D:/CodeAtlas/CodeAtlas后端/src/main/java/dev/codeatlas/web/AtlasController.java:29) [导入服务](D:/CodeAtlas/CodeAtlas后端/src/main/java/dev/codeatlas/tasks/TaskService.java:58)

### 2.2 关系人工修正与修正来源留存

**状态为部分实现，归属 EXP-04 P1。** 说明和笔记可编辑。代码关系目前提供读取、筛选、可视化和证据跳转，尚未找到关系修正提交、修正理由／来源记录、原始分析与修正记录并存查询，以及对应界面。[需求依据](D:/CodeAtlas/CodeAtlas-PRD-V1.0.md:92)

局部关系和图谱控制器均为查询入口，当前迁移与服务未见独立的关系修正模型。已有文档编辑不能覆盖这部分关系修正要求。[关系接口](D:/CodeAtlas/CodeAtlas后端/src/main/java/dev/codeatlas/web/AtlasController.java:78) [图谱接口](D:/CodeAtlas/CodeAtlas后端/src/main/java/dev/codeatlas/graph/GraphController.java:26) [说明编辑](D:/CodeAtlas/CodeAtlas后端/src/main/java/dev/codeatlas/learning/LearningService.java:66)

### 2.3 大结果集的诊断与旧关系列表完整浏览

**状态为部分实现，涉及 V0.1 的 IMP-03／EXP-02。** 旧源码工作台分别只取前 1000 条诊断和局部关系，显示数组长度，未保留完整总数、下一页和截断提示。达到上限时，用户无法从该入口确认余量。后端已有分页，文件／符号搜索也有分页。[诊断请求](D:/CodeAtlas/CodeAtlas前端/src/App.tsx:243) [关系请求](D:/CodeAtlas/CodeAtlas前端/src/App.tsx:310) [诊断显示](D:/CodeAtlas/CodeAtlas前端/src/App.tsx:911)

0.4.1 图谱已经提供聚合边的原始证据分页，关系具有新的分页入口。文件诊断的完整 UI 浏览仍有缺口。旧 V0.1 验收已记载此项限制。[早期限制记录](D:/CodeAtlas/CodeAtlas后端/docs/reports/v0.1-acceptance.md:104) [图谱分页契约](D:/CodeAtlas/CodeAtlas后端/contracts/v0.4.1-graphs.md:22)

### 2.4 代码修改训练的 AI 反馈深度

**基础人工练习链路已有，面向代码修改的 AI 辅导链路尚未覆盖。归属 PR-04 P1，关联 V0.3 训练增强。** 通用任务已有目标、约束、验收条件及独立 IDE 副本，可以人工开展补全、修复、测试、重构和小型功能扩展。PRD 没有强制要求五个独立枚举或五个专门页面。[需求依据](D:/CodeAtlas/CodeAtlas-PRD-V1.0.md:121) [任务模型](D:/CodeAtlas/CodeAtlas后端/src/main/java/dev/codeatlas/training/TrainingDefinition.java:16) [练习操作](D:/CodeAtlas/CodeAtlas前端/src/training/PracticePanel.tsx:35)

现有结构化出题和 AI 辅助反馈围绕源码理解题，题型为选择、判断和开放回答。反馈绑定理解题尝试与题目，尚未见接收练习 submission、代码差异和外部测试报告的专门 AI 上下文及反馈入口。五类实际修改任务的完整覆盖也缺少验收记录。因此该项应记为训练能力深度及覆盖欠账。[题型限制](D:/CodeAtlas/CodeAtlas后端/src/main/java/dev/codeatlas/training/TrainingDefinition.java:50) [AI 上下文入口](D:/CodeAtlas/CodeAtlas后端/src/main/java/dev/codeatlas/training/TrainingAiController.java:39) [模型反馈用途](D:/CodeAtlas/CodeAtlasAI/codeatlas_ai/provider.py:36)

### 2.5 代码实践成果关联知识掌握记录

**状态为部分实现，归属 V04-03／KN-01／MEM-02。** 理解题结果、开放题自评、卡片评分、个人复盘已经能形成独立学习证据。独立副本的代码提交及外部测试报告已保存，但尚未接入知识证据关联、判断历史和进度视图。[阶段要求](D:/CodeAtlas/CodeAtlas-总体与步骤实施计划-V1.1.md:177)

EvidenceService 接受四种来源，分别为 REVIEW_RATING、TRAINING_RESULT、TRAINING_SELF_ASSESSMENT、PERSONAL_REFLECTION。其中 TRAINING_RESULT 查询 training_attempts 的理解题结果，未接收 practice submission 或外部测试结果。PracticePanel 也未见关联知识入口。用户可手写个人复盘，代码提交／报告的结构化来源关联仍缺失。[证据来源校验](D:/CodeAtlas/CodeAtlas后端/src/main/java/dev/codeatlas/progress/EvidenceService.java:44) [理解题事实读取](D:/CodeAtlas/CodeAtlas后端/src/main/java/dev/codeatlas/progress/EvidenceService.java:83) [外部报告界面](D:/CodeAtlas/CodeAtlas前端/src/training/PracticePanel.tsx:60)

原理问答可由开放理解题、自评和显式关联知识承载首版，当前材料不足以另行判定必须新增独立原理题库模块。[开放题自评与关联](D:/CodeAtlas/CodeAtlas前端/src/training/AttemptView.tsx:94)

## 3. 图形能力的当前阶段与后续范围

| 能力 | 当前状态 | 文档所述阶段 |
|---|---|---|
| 包级架构关系图 | 已有交互实现，按 Java 包聚合静态关系 | EXP-03／V01-06 增强，实际交付 0.4.1 |
| 类／接口关系图 | 已有交互实现，继承、实现、类型依赖、成员入口 | 实际交付 0.4.1 |
| 局部调用图 | 已有方法／构造器一跳入出关系，邻居可重新居中 | 实际交付 0.4.1 |
| 业务流程图／流程图生成与展示 | 未交付，已有业务流程文字说明用途 | 0.4.1 批准设计明确留后续，未指定具体版本 |
| 时序图 | 未交付 | 同上，后续扩展未排具体版本 |
| 完整 UML 成员属性、可见性和多重性展示 | 未交付，现有类型关系图覆盖基础关系 | 同上，后续扩展未排具体版本 |
| archify 独立 HTML／图形导出集成 | 未交付，设计中作为后续候选适配 | 同上，后续验证与集成未排具体版本 |

以上以批准设计的三个首批视图及后续边界为依据。当前架构图表达包依赖，尚未承诺语义业务模块、部署拓扑或完整动态调用链自动还原。[批准设计](D:/CodeAtlas/CodeAtlas后端/docs/superpowers/specs/2026-11-15-interactive-graph-design.md:11) [交付范围](D:/CodeAtlas/CodeAtlas后端/docs/requirements/scope-v0.4.1.md:5) [最新验收](D:/CodeAtlas/CodeAtlas后端/docs/reports/v0.4.1-acceptance.md:7)

## 4. 已有实现但仍缺完整验证的项目

| 项目 | 已有证据 | 尚缺证据与阶段影响 |
|---|---|---|
| V03-06 真实修改练习贯通 | 合成样本 HTTP／数据库回归覆盖创建副本、修改提交、摘要绑定、报告及关闭清理 | 获授权真实 Java 项目的一次实际修改／补测试、外部执行、日志与复盘完整记录。阶段要求的 v0.3-acceptance.md 未见，现存 v0.3-training-acceptance.md 仅首批范围 |
| 练习副本专用浏览器流程 | PracticePanel 已接入任务详情，TypeScript／Vite 有历史构建记录，后端已有练习回归 | 未找到该面板创建副本→保存代码→录入四类结果→历史查看→关闭清理的专用浏览器用例与完整记录 |
| V04-05 完整学习闭环 | 既有 study-loop 浏览器回归、复习／证据／引导功能及升级重启证据 | 真实项目修改成果加入知识证据的完整链路受 2.5 缺口影响。独立真实场景的完整验收仍待补齐 |
| V01-07 分析准确性 | 合成黄金样本及固定 PetClinic 静态导入历史 | 广泛 Controller／Service／事务方法人工对照、准确率与未解析分母、复杂 Spring 配置与动态关系的充分独立核验 |
| V02-07 及后续 AI 质量 | V0.2 有一次公开 PetClinic 真实 DeepSeek 联调；后续确定性响应回归 | 多类型解释、训练出题／反馈、学习引导的真实模型人工语义评估。位置引用校验与语义正确性需分别记录 |
| 性能和资源 | 有 PetClinic 基线、50／500／1000 节点纯布局及小样本 HTTP 测量 | 完整大项目加载、索引与图聚合压力、首次交互、峰值资源、弱网等测量，主包仍约 3.64 MB |
| 完整安全边界 | 已有路径、token、授权、请求体、提示注入等专项代码与历史测试 | 进程级源码外发审计、广泛文件竞争、完整安全测试与独立评估仍缺充分证据 |

阶段要求及验证范围分别见真实任务出口、修复记录和最新图谱验收。上述表格保留“功能存在”和“证据充分程度”两个独立判断。[V03-06 要求](D:/CodeAtlas/CodeAtlas-总体与步骤实施计划-V1.1.md:158) [练习修复范围](D:/CodeAtlas/docs/reports/CodeAtlas-V0.3-V0.4-Fix-Record-2026-11-15.md:9) [尚未覆盖的专项](D:/CodeAtlas/docs/reports/CodeAtlas-Review-Gaps-Followup-2026-11-14.md:57) [最新性能限制](D:/CodeAtlas/CodeAtlas后端/docs/reports/v0.4.1-acceptance.md:58)

**MySQL 迁移与重启已有更新证据。** 最新 0.4.1 记录为 MySQL 67 项、最终 JAR 浏览器 30 项、V4→V6 升级及同库重启对照 10 个旧资源。迁移 JSON 记录旧复习、训练、证据、引导、固定源码及旧代次图保留。旧独立报告的 BLOCKED／NOT_TESTED 反映其当时范围，本轮静态复核采用这些较新记录，但未重新执行测试。[验收结果](D:/CodeAtlas/CodeAtlas后端/docs/reports/v0.4.1-acceptance.md:13) [升级详情](D:/CodeAtlas/CodeAtlas后端/docs/reports/v0.4.1-acceptance.md:49) [迁移证据](D:/CodeAtlas/CodeAtlas后端/docs/reports/evidence/v041-graphs-2026-11-15/graph-migration-evidence.json)

## 5. 工程交付与文档同步欠账

| 项目 | 核对结果 | 原阶段／依据 |
|---|---|---|
| 独立 Git 基线与 CI | 当前目录无 Git 元数据，未找到项目 CI 配置。已有本地构建／测试命令和文件哈希交付 | P0-07 工程准备，统一计划第 62～65 行 |
| 统一领域模型／ER 文档 | 计划中的 docs/architecture/domain-model.md 未找到。数据库迁移、阶段设计、领域契约已分散覆盖模型 | P0-04，统一计划第 46～50 行 |
| 独立任务／ADR 模板文件 | docs/agent-task-template.md 和独立 ADR 模板未找到。总体计划内有内嵌任务模板，已有实际 ADR | P0-07，属于交付形式与文档整合欠账 |
| 关系证据契约 | code-evidence.md 第 9 行仍称框架 INFERRED 规则尚未实现，当前已有事务候选 | 需补记后续规则及适用边界 |
| OpenAPI Relation.kind | 枚举只含 EXTENDS／IMPLEMENTS／CALL／RUNTIME_CALL，遗漏实际产出的 TRANSACTION_BOUNDARY | 契约同步欠账，当前分析器已有该关系 |
| 安全文档请求体说明 | threat-model-v0.md 第 13 行仍写声明上限 16 KiB 且分块限额待补，当前过滤器已检查实际读取上限 1 MiB | 文档与当前实现不一致 |
| V0.3／V0.4 旧范围 | 首批 scope／design／acceptance 仍把 V03-03 以后写为后续，当前 V6／PracticeService／新契约已经增加副本等能力 | 应保留历史范围并补充当前状态索引 |
| 根目录复用报告、UI 审计和黄金样本说明 | 部分仍写无图谱、无 FSRS、无真实项目或候选未验证。已有 0.4.1 图谱、Java FSRS 和 PetClinic 历史验证 | 历史文档应标注被何份新记录补充，避免当作当前清单 |
| UI 维护及可访问性 | UI 审计列出旧硬编码样式双层维护、深层弹窗完整焦点约束待验证 | 工程／可用性欠账，完整专项审查仍待开展 |

工程清单依据总体计划，具体约束和当前行为可分别从模型、过滤器和旧文档定位。文档欠账在本轮报告中记录，原业务实现与历史报告保持原状。[P0 要求](D:/CodeAtlas/CodeAtlas-总体与步骤实施计划-V1.1.md:46) [旧证据契约](D:/CodeAtlas/CodeAtlas后端/docs/contracts/code-evidence.md:9) [旧安全说明](D:/CodeAtlas/CodeAtlas后端/docs/security/threat-model-v0.md:13) [实际请求限制](D:/CodeAtlas/CodeAtlas后端/src/main/java/dev/codeatlas/web/LocalAccessFilter.java:41) [UI 未决项](D:/CodeAtlas/CodeAtlas前端/UI_AUDIT_AND_REDESIGN.md:58)

## 6. 后续范围及尚未冻结的设想

| 能力 | 当前情况 | 文档归属 |
|---|---|---|
| 自动隔离编译／测试执行及 Worker | 当前仅保存用户外部验证报告，尚未开放系统自动执行 | PR-05 P2／V03-07 独立安全预研，统一计划明确其不构成 V0.3 完成交付条件 |
| 项目／快照删除、保留期限和存储回收 | 当前历史快照持续保留，练习目录可关闭清理。未见完整项目／快照删除、存储上限管理及自动回收 | PRD 未决事项、V1.0 数据与发布完善 |
| 配套备份恢复产品能力 | README 有数据库与快照配套备份说明，未见产品内备份恢复入口和完整演练记录 | V1.0 发布完善 |
| 多用户、公网协作、多语言分析 | 当前本机单用户 Java 范围 | 总体计划首批非目标，后续另行定义 |
| 全项目自动检索汇总和文档问答 | 已有选定来源解释、四种文档用途、搜索／编辑／再生成。上下文最多六来源、同快照同代次，未见自动全项目检索汇总与独立跨文档 AI 问答链路 | 参考讨论中的广义产品设想，PRD AI-04 未要求完整项目自动覆盖，未冻结为早期必做项 |
| 自动技术栈识别、语义业务模块及多跳调用探索 | 已有目录、类型／方法、包聚合和可重新居中的一跳图。未见完整技术栈画像、自动业务模块语义划分和一次请求的多跳图 | 参考讨论中的愿景及后续深度增强，当前阶段范围未冻结相应验收 |
| 复杂综合挑战、项目依赖环境编排、变异测试 | 当前通用外部练习可手工组织，未见专门自动编排／评测链路 | 参考资料中的后续研究建议 |
| Neo4j／向量检索／LangGraph／外部文档工具整套接入 | 当前没有采用这些可选路线 | 技术候选按需决策；Java FSRS 和 React Flow 已实际接入，ts-fsrs 等候选无需全部实现 |

以上项目的阶段依据来自 PRD、整合计划及批准图谱设计。参考资料中的候选项目名称和架构建议不单独构成 V0.1～V0.4 的交付承诺。[执行预研边界](D:/CodeAtlas/CodeAtlas-总体与步骤实施计划-V1.1.md:163) [V1.0 范围](D:/CodeAtlas/CodeAtlas-总体与步骤实施计划-V1.1.md:189) [原始愿景](D:/CodeAtlas/参考资料/对话.txt:60) [AI 已批准范围](D:/CodeAtlas/CodeAtlas后端/docs/adr/ADR-002-v0.2-learning-and-ai.md)

## 7. PRD 全量功能追踪

下表覆盖 PRD 的 26 个需求编号。IMP-01 另列 P1 远程导入分项。各项的源码依据、验证局限和后续范围见前文及对应阶段文档。

| 需求 | 阶段归属 | 当前核对结果 |
|---|---|---|
| IMP-01 P0 | V0.1 | 本地导入、状态、名称和来源已有 |
| IMP-01 P1 | 导入后续增强 | GitHub 导入未实现，见 2.1 |
| IMP-02 | V0.1 | 快照、commit／dirty 区分、内容哈希及旧版本已有 |
| IMP-03 | V0.1 | 进度、部分结果、重试／取消、幂等已有，大结果 UI 有缺口，见 2.3 |
| IMP-04 | V0.1 | 只读静态扫描／索引已有，完整边界验证见第 4 节 |
| EXP-01 | V0.1 | 文件树、类／接口／方法、路径／符号搜索和行号定位已有 |
| EXP-02 | V0.1 及修复 | 继承、实现、静态调用、未解析与事务 INFERRED 已有，规则覆盖有限 |
| EXP-03 | 项目探索增强，0.4.1 | 包／类型／一跳调用交互图及固定源码跳转已有 |
| EXP-04 | P1 探索增强 | 说明可编辑，关系修正缺失；流程图后续，见 2.2／第 3 节 |
| AI-01 | V0.2 | 所选方法解释、深度、追问和不确定项已有 |
| AI-02 | V0.2 | 来源身份、范围和哈希引用校验已有，语义质量待专项验证 |
| AI-03 | V0.2 | 讲解／笔记保存、编辑、删除、持久化及版本来源已有 |
| AI-04 | V0.2 P1 | 方法／技术笔记／项目说明／业务流程说明用途已有，覆盖取决于所选片段 |
| AI-05 | V0.2 及后续扩展 | 预览／授权、拒绝／取消、最小权限和结构校验已有，全面安全待专项验证 |
| KN-01 | V0.2 知识／案例，V0.4 掌握记录 | 独立知识、多对多来源和独立掌握记录已有，代码实践成果关联见 2.5 |
| KN-02 | V0.2 | 知识／卡片手工及 AI 可编辑 CRUD、搜索、笔记与引用已有 |
| KN-03 | V0.2 及修复 | 历史快照引用、新快照提示、手动检查当前源码已有 |
| MEM-01 | V0.4 | 主动回忆、Java FSRS、评分、下次安排和不可覆盖历史已有 |
| MEM-02 | V0.4 P1 | 错题、依据／局限、版本化进度及撤回已有，代码实践关联有缺口 |
| PR-01 | V0.3 | 多题任务、来源、目标、验收、提示／跳过、规则判分／自评／AI 辅助已有 |
| PR-02 | V0.3 | 指定快照独立副本及外部验证报告已有，真实项目贯通待验收 |
| PR-03 | V0.3 | 不可变提交、哈希、差异预览、完整存档、报告和时间已有 |
| PR-04 | V0.3 训练 P1 增强 | 通用人工修改链路已有，面向变更／报告的 AI 反馈及五类实操覆盖见 2.4 |
| PR-05 | P2／独立安全准入 | 自动隔离执行未开放，见第 6 节 |
| GUIDE-01 | V0.4 P1 | 用户主动开启六步骤引导及 AI 候选已有 |
| GUIDE-02 | V0.4 P1 | 跳过、重排、暂停、退出、恢复及自由入口已有 |
| GUIDE-03 | V0.4 P1 | 目标／所选知识／证据摘要驱动 AI 建议、用户修正与显式采纳已有，真实建议质量待验收 |

## 8. 已处理的旧发现及残余历史限制

- D01 已增加手动“检查当前源码”，可区分一致、变化、缺失和路径边界等状态，保留旧快照。触发方式已由用户批准为手动检查。[实现](D:/CodeAtlas/CodeAtlas后端/src/main/java/dev/codeatlas/learning/SourceEvidenceService.java:109)
- D02 已产生 Spring 方法事务边界候选，状态 INFERRED，保留规则名及运行时条件。类级、组合注解和复杂动态装配属于后续规则扩展。[实现](D:/CodeAtlas/CodeAtlas后端/src/main/java/dev/codeatlas/analysis/JavaAnalyzer.java:114)
- D03 的个人掌握记录已在 V0.4 独立建模。学习证据、判断历史和知识进度均有实现，代码实践成果关联仍见 2.5。[证据服务](D:/CodeAtlas/CodeAtlas后端/src/main/java/dev/codeatlas/progress/EvidenceService.java:96)
- D03-02 已补独立副本、提交内容、差异和用户外部报告，当前需补真实使用与专用 UI 验收。[实践契约](D:/CodeAtlas/CodeAtlas后端/contracts/v0.3-practice.md:3)
- D03-01 已补省略 answers 时继承已存草稿的逻辑及回归。历史上已丢失的回答缺少自动恢复依据。[修复记录](D:/CodeAtlas/docs/reports/CodeAtlas-V0.3-V0.4-Fix-Record-2026-11-15.md:7)
- D04-01 已通过 V5 的持久顺序修复新增证据同时间戳排序。旧记录真实先后无法重建，继续使用已公开的历史平局规则。[修复记录](D:/CodeAtlas/docs/reports/CodeAtlas-V0.3-V0.4-Fix-Record-2026-11-15.md:8)

## 附录 A 文档清点

以下为本轮开始时的 64 份文件，不含本报告。OpenAPI 按结构读取，第三方许可及报告附件按其引用核对。清点时各文件的行数、字符数和 SHA-256 保存在工作区 .runtime 的文档清单中。

