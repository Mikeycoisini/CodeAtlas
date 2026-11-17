# ADR-009 代码实践成果作为知识证据

日期 2026-11-15。状态：已采纳。归属 V04-03／KN-01／MEM-02，交付版本 0.4.5。

## 决策问题

代码练习的提交和用户外部测试报告，应如何进入知识掌握证据，既不丢失实践成果，也不把“提交了代码”误判为掌握？

## 背景与约束

- 代码练习已保存不可变提交、差异、内容摘要和用户外部测试报告；报告明确标记 `USER_REPORTED`，CodeAtlas 不执行代码。
- PRD 要求测试结果区分 PASSED／FAILED／ERROR／TIMEOUT，环境错误不能计为用户能力失败；掌握状态必须说明依据与局限。
- 现有掌握证据支持复习评分、理解题结果、理解题自评和个人复盘，`EVIDENCE_V1` 以两次自报复习或两个无帮助正确尝试作为 SUPPORTED。
- 实践提交是用户成果，不是能力判断；测试报告是用户提供的外部信息，也不是系统独立验证。

## 备选方案

| 方案 | 结论 | 理由 |
|---|---|---|
| A. 提交和报告都作为正证据 | 不采纳 | 提交代码不等于掌握；用户报告也不等于系统验证 |
| B. 提交仅作成果关联；报告 PASSED/FAILED 入证据，ERROR/TIMEOUT 不入 | 采纳 | 保留成果链，区分测试结果与环境故障，符合 PRD 语义 |
| C. 仅允许个人复盘关联实践 | 不采纳 | 无法绑定结构化提交与报告，审计仍留缺口 |

## 决策

- 新增 `PRACTICE_SUBMISSION` 来源：绑定练习提交，`source=USER_PRACTICE`、`eligible=false`、`qualifier=PRACTICE_ARTIFACT`；记录提交摘要、变更文件数、任务／快照和测试报告数量，只进入历史与关联展示。
- 新增 `PRACTICE_TEST_RESULT` 来源：绑定用户测试报告，`source=USER_REPORTED`；`PASSED` 为 `eligible=true, strength=+1, qualifier=PRACTICE`，`FAILED` 为 `eligible=true, strength=-1`，`ERROR`/`TIMEOUT` 为 `eligible=false, qualifier=ENVIRONMENT`。
- 掌握规则升级为 `EVIDENCE_V2`：SUPPORTED 条件增加“两个用户报告通过的实践测试”；最新有效证据为 FAILED 实践测试时进入 NEEDS_REVIEW；ERROR/TIMEOUT 不影响掌握状态。
- 进度视图展示实践通过次数、提交摘要、报告状态／方法／环境／报告内容和限制说明；代码练习面板提供“关联代码提交到知识”和“关联该报告到知识”入口。
- 复用 `POST /api/progress/evidence` 与现有幂等、撤回、知识版本和历史机制；无新 API、无迁移。
- 明确不做：系统自动执行测试、把用户报告升级为系统验证、把提交本身计为掌握、多用户评审。

## 后果

**正面**：实践成果与知识证据形成可追溯链路；掌握判断保留来源与局限；环境错误不误伤能力评价；无需迁移；可回退。

**负面**：PASSED/FAILED 依赖用户如实填写；两次用户报告通过即可达到 SUPPORTED，强度低于系统执行；提交证据只作历史展示，不直接提高掌握度；报告内容可能较长，界面需要折叠展示。

## 可逆性

两向门。移除两种来源与前端入口即可回到原证据类型；已写入证据保留并可撤回；`EVIDENCE_V2` 只改变新判断，旧判断历史仍按原版本保存。

## 适应度函数

| 不变量 | 检查 |
|---|---|
| 提交不单独提高掌握 | 集成测试：仅关联提交时状态为 NO_EVIDENCE/PRACTICING，eligible=false |
| PASSED 计入实践通过 | 集成测试：一次 PASSED 为 PRACTICING，两次不同提交的 PASSED 为 SUPPORTED，practicePasses=2；同一提交多份报告只计一次 |
| FAILED 为负证据 | 集成测试：最新 FAILED 使状态为 NEEDS_REVIEW |
| ERROR/TIMEOUT 不计能力 | 集成测试：ERROR 的 eligible=false，状态不因环境错误改变 |
| 来源与限制可见 | 进度界面显示提交摘要、报告状态/方法/环境/内容和 limitation |
| 幂等与撤回沿用 | 复用现有唯一键、撤回和无覆盖历史机制 |

## 职责

用户填写外部报告并负责真实性；后端维护者负责来源校验、证据语义和界面说明。本地检查路径为 `mvn test`、`npm run build`。代码责任：`ASSUMED: CodeAtlas 后端维护者`。

## 后续

系统自动隔离执行（PR-05）若获批，可作为更高强度证据来源单独设计；多用户评审、报告附件与测试日志解析留待后续。

## 修订 2026-11-16：EVIDENCE_V3 计数单位

复核发现原实现按 `testResultId` 统计 PASSED 次数，同一提交的两份报告即可让知识达到 SUPPORTED，与“两次独立实践”的语义不符。现将统计单位改为 `submissionId` 去重：同一提交的多份 PASSED 报告只计一次，不同提交（可来自同一或不同练习副本）分别计数。规则版本升级为 `EVIDENCE_V3`；历史 `EVIDENCE_V2` 判断保留原 rule_version，不重写。对应适应度检查：同一提交两份 PASSED 仍为 PRACTICING 且 practicePasses=1；第二份独立提交 PASSED 后为 SUPPORTED 且 practicePasses=2。

## 修订 2026-11-16：旧规则当前投影

### 问题

`GET /progress/knowledge/{id}` 原来只按知识 revision 判断缓存投影是否可复用。升级到 `EVIDENCE_V3` 后，若知识未改版、`knowledge_progress` 仍指向旧 `EVIDENCE_V2` 判断，读取会继续返回旧的 SUPPORTED/practicePasses=2，直到用户新增证据才重算。这是静态发现，已用旧格式夹具复现。

### 备选方案

| 方案 | 结论 | 理由 |
|---|---|---|
| A. 读取时按当前规则重算，但不写库 | 采纳 | GET 无副作用；不追加重复判断；无需锁；旧判断与 rule_version 完整保留 |
| B. 首次读取时追加新 assessment 并移动指针 | 不采纳 | GET 变成写操作；并发首次读取需要额外锁与去重；失败时指针与历史可能不一致 |
| C. 迁移时批量重算所有当前指针 | 不采纳 | 需要新迁移和历史判断改写，违背“历史判断保留原版本”；升级窗口外的旧数据仍会遗漏 |

### 决策

- 缓存投影仅在“知识 revision 一致 **且** `rule_version` 等于当前规则”时复用。
- 旧规则投影在读取响应中按当前规则重算；**不插入** `mastery_assessments`、**不更新** `knowledge_progress`。
- `progressRevision` 继续表示持久化指针的 revision，不因读取重算而变化；`assessment.ruleVersion` 明确为当前规则。
- 历史接口继续返回旧 `EVIDENCE_V2` 判断及其原始 rule_version。

### 后果

**正面**：升级后旧错误判断不会因缺少新用户操作而持续生效；GET 可重复、可并发；历史与指针保持一致；回退只需恢复复用条件。

**负面**：存在旧规则投影时，每次读取都会重算一次（单用户本地规模可接受；`list` 会逐项重算受影响知识）；客户端可能看到 `assessment.ruleVersion=EVIDENCE_V3` 而 `progressRevision` 仍指向旧行，需要在文档中说明二者语义。

### 适应度函数

| 不变量 | 检查 |
|---|---|
| 旧规则不持续影响当前判断 | 夹具：V2 指针 + 同提交两份 PASSED，GET 返回 V3/PRACTICING/1 |
| GET 不写判断 | 多次及 8 并发 GET 后 `mastery_assessments` 数量不变、指针不变 |
| 历史保留 | history 仍包含 `EVIDENCE_V2` 行 |
| 列表与详情一致 | `status=PRACTICING` 包含该知识，`status=SUPPORTED` 不包含 |
| 撤回与改版 | 撤回单条证据仍为 V3；知识改版后为 NO_EVIDENCE，历史证据计数保留 |
| 升级中断 | 缺失 `knowledge_progress` 时仍按当前规则返回，且不补写指针 |
