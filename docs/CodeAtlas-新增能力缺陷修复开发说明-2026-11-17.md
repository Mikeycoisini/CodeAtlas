# CodeAtlas 新增能力缺陷修复开发说明

日期2026-11-17。面向后续开发与测试agent，承接[新增能力复核报告](D:/CodeAtlas/docs/reports/CodeAtlas-New-Capabilities-Review-2026-11-17.md)及[本轮开发报告](D:/CodeAtlas/CodeAtlas后端/docs/reports/architecture-diagrams-ai-github-2026-11-17.md)。本文说明原因、影响、复现、修复和验收；当前仅完成文档编写。

## 1. 基线、范围及优先级

当前代码新增architecture、FlowService、SequenceService、UmlService、统一AI入口、私有GitHub凭据／手动同步及默认关闭的execution原型。当前报告包为codeatlas-0.4.6.jar，33342055字节，SHA256 3D0D5EAD60645DDF871F4B8A673225DF60DC209784ED9804D11943C04F74FB95。数据库已存在V9、V10，新增迁移按接手时真实最高版本编号。

下列N01～N05均为源码静态发现，修复前先建立独立失败复现。执行器问题仅在配置启用后进入执行路径，默认关闭状态继续保留。现有35项E2E及页面烟测可作回归基线，新语义仍需专项测试。

| 编号 | 优先级 | 问题 | 修改入口 |
|---|---|---|---|
| N01 | 高 | 控制流出口被忽略，后继连线错误 | FlowService.Builder |
| N02 | 中 | 无绑定Map与null判断失配 | GitHubSyncService |
| N03 | 中 | 导出同幂等键未比较内容 | ArchitectureExportService |
| N04 | 高，启用后 | 超时容器回收与结果分类不足 | ExecutionService.run／execute |
| N05 | 高，启用后 | 执行对象归属、不可变提交及幂等不足 | ExecutionService、ExecutionController、迁移 |

建议N01、N02、N03分别独立修复，N04／N05在同一执行器方案下分任务集成。每项实际复现结果、改动和剩余限制写入独立报告。

## 2. 共同要求

开始时核对当前源码、Git状态和用户修改。测试使用专用数据库与目录，3307仅为历史候选端口，先核对服务身份。项目源码、快照、旧判断、旧工件和旧执行记录保留可追溯身份。

私有凭据仅在本机保管，报告不输出token／密码。GitHub和Docker真实联调按既有授权具体范围执行，缺少条件时先用固定响应或受控替身验证服务逻辑。Docker清理严格针对持久记录的本任务容器ID，文件清理仅针对可核验的任务目录。

每项按“失败复现→最小修复→专项测试→兼容回归→报告”执行。若静态发现无法复现，给出实际路径和断言，修正问题状态。历史通过数量与本轮实际执行分别列明。

## 3. N01 控制流后继与终止语义

### 原因与影响

FlowService.Builder.statements遍历语句后将cursor赋为part.entry。if返回入口decision和正常出口merge；循环、try、switch也返回不同入口／出口。因此下一条普通语句从控制结构入口接出，忽略已经生成的出口。

例如`if (ok) { a(); } b();`预期真假分支汇合后到b，当前代码会以decision作为b的前驱，造成额外直达路径及汇合出口失联。build对终止块使用part.entry连接END，进一步需要检查return／throw的真实终止点。try当前对正常出口处理finally，return／throw经过finally的语义也需专门验证。

### 修复方案

建议将语句块构建结果明确区分入口、正常出口和带原因的跳转／终止出口。顺序组合连接全部正常出口，分支合并仅合并能正常继续的路径。return、throw、break、continue及其目标分别表达，循环回边连接到正确条件或更新节点。

finally应对相关正常及异常／返回路径执行，可能覆盖先前终止动作。若首批无法表达全部Java语义，给出明确未支持诊断与证据级别，并在UI说明覆盖范围，避免输出确定的错误连线。

### 复现与验收

- [ ] if无else、if／else以及嵌套分支后有普通语句，断言实际前驱集合。
- [ ] 单支return、两支return、throw后不可达语句，断言可达性与END入口。
- [ ] for／while／do-while、break／continue、嵌套循环和标号，断言回边／出口。
- [ ] try/catch/finally含return及throw、finally自身return，断言支持语义或明确诊断。
- [ ] switch无default、穿透、break和箭头语法，保留Java版本范围。
- [ ] 空方法、截断、节点限额和旧源码不可读，仍返回合法结构及诊断。

测试使用固定源码和公开API，逐边及可达性断言，保留文件／哈希／行号证据。UI烟测和节点数量作为补充。验收报告给出修复前后最小图结构。

## 4. N02 无绑定项目检查／同步

### 原因与影响

GitHubCredentialService.binding无绑定时返回`{"bound":false,"projectId":"..."}`。GitHubSyncService.check和sync仍用binding==null判断，随后读取credentialId、repoUrl和ref，造成空凭据查询、非法导入或非预期服务错误。

### 修复与验证

- [ ] 调用POST /api/github/projects/{project}/check及/sync，选一个存在但无绑定的项目，记录预期GITHUB_NOT_BOUND与实际行为。
- [ ] 统一binding契约，推荐在服务层集中校验Boolean.TRUE.equals(bound)，或使用明确的绑定领域类型。
- [ ] 无绑定在读取凭据、访问GitHub和创建IMPORT任务之前返回约定的404业务错误。
- [ ] 保留GET binding返回bound=false供UI正常显示；项目不存在与项目无绑定分别处理。
- [ ] 覆盖有效绑定、撤销／轮换、远端拒绝、分支不存在和绑定状态变化。

验收必须断言无绑定时零网络请求、零新增任务、稳定错误码。凭据撤销后排队任务的使用策略需与现有凭据契约一致。

## 5. N03 导出幂等与失败恢复

### 原因与影响

ArchitectureExportService按snapshotId和idempotencyKey查到记录立即返回，位置在generation和投影digest校验之前。请求参数变化没有比较，主题、scope或代次已变时可能继续得到旧结果；FAILED记录同键返回后没有明确恢复语义。方法synchronized只保护单实例。

### 修复建议

复用mutation_requests的占位、请求摘要和冲突机制。标准化大小写、默认值和可选字段，定义同键同内容返回既有任务，同键不同内容返回409及明确错误码。

请求摘要至少覆盖快照、固定代次、投影身份及摘要、格式、范围和主题。省略generation时首次选定代次应随任务保存，重试语义在契约中明确。首个新请求验证当前投影；已成功任务的同内容重试继续返回原工件，其历史投影不因当前已重建而失效。

失败重试可采用新任务或明确重试动作，保留原失败记录。文件先生成到任务拥有的临时目录，校验后发布；数据库写入失败要清理或标记孤立工件，并提供启动／后台恢复。工件缺失／篡改校验、存储限额和清理策略同步考虑。

### 验收

- [ ] 同键同内容单次、并发、进程重启后均返回同一任务／工件。
- [ ] 同键改主题／scope／代次／digest返回冲突，未额外创建文件。
- [ ] 首次陈旧digest被拒绝；已成功旧工件重试及下载符合历史语义。
- [ ] 磁盘写入、原子移动、数据库写入和服务中断分别注入失败，状态／文件可恢复。
- [ ] 同键失败与重试按已定义流程执行，重复成功记录由事务／唯一约束控制。

新增约束或字段使用V10之后的前向迁移，检查历史重复键并给出迁移策略，避免覆盖旧工件。

## 6. N04 容器生命周期与结果分类

### 原因与影响

当前docker run超时后销毁的是本机客户端进程。服务器侧容器可能继续执行，未见持久容器ID及显式stop／kill／rm恢复路径。EXECUTOR_TIMEOUT被execute的通用catch改为ERROR，非零退出全部FAILED还混合了构建环境错误。

### 修复建议

- [ ] 为job生成固定容器名／label，使用受控cidfile或Docker返回结果记录实际ID，保存于执行记录。
- [ ] 区分排队、创建、运行、终态及清理状态。超时／取消明确停止任务容器，等待并验证退出，必要时kill后移除。
- [ ] 服务重启扫描未终结job，根据实际容器身份恢复／终结，避免任意名称或无范围批量删除。
- [ ] 保留TIMEOUT、CANCELLED、ERROR和FAILED含义。测试实际未通过、编译错误、依赖／工具缺失等分类在执行契约中明确。
- [ ] stdout／stderr持续有界排空、脱敏，保留截断提示；清理失败单独记录，支持安全重试。

先以Docker命令替身验证调用序列、异常和幂等，再在获授权受控镜像中运行超时、取消、进程退出及重启用例。默认关闭配置保持原值。

**验收**：任何终态均能查到精确容器身份和退出／清理结果；超时任务不会继续消耗服务器资源；日志、运行目录和容器可按策略回收。命令含资源参数只证明配置声明，安全限制应通过实际受控测试确认。

## 7. N05 任务归属、提交内容及幂等

### 原因与影响

当前execute接收projectId、taskId和workspace路径，仅核对project存在及路径在data/practice下，缺少任务、快照、工作区和项目的完整绑定。它复制当前可变目录，执行结果也未绑定准确submission摘要；execution_jobs虽有snapshot_id列，当前INSERT未写入。

### 修复方案

建议改为执行已保存的submissionId，由服务端推导project、snapshot、taskRevision及归属。任务可能包含多个项目来源，按被选快照及现有工作区定义准确归属，避免随意选择第一个来源。

从经过摘要校验的不可变存档创建执行副本，复制时保持链接／重解析点及变化边界检查。客户端传入的宿主机路径不直接决定执行内容。记录submissionId、contentDigest、snapshotId、任务版本、执行配置／镜像摘要、运行环境和幂等请求身份。

定义队列、并发、重试、取消及执行器不可用行为。执行与测试结果及学习证据之间通过明确来源适配，用户外部报告继续保留USER_REPORTED。系统结果只有在相应安全与真实性验证后使用对应来源类型。

### 验收

- [ ] 跨任务、项目、快照、工作区身份组合在复制／启动之前被拒绝。
- [ ] 原工作目录后续修改不改变本次执行内容，存档损坏在运行前失败。
- [ ] 同键同提交返回同job，同键改提交冲突，网络重试不重复启动容器。
- [ ] 普通路径、符号链接、junction、祖先重解析点及恶意替换在允许边界内处理。
- [ ] 失败／超时／取消／重启恢复记录仍绑定同一内容摘要，原仓库保持不变。

本项与N04共同形成执行器设计。当前PR-05的权限／凭据、网络、资源、依赖下载和宿主机隔离准入继续逐项验证。

## 8. 范围遗漏及报告修正任务

| 项目 | 当前实际范围 | 后续动作与验收 |
|---|---|---|
| archify | 自研HTML／内联SVG，格式仅HTML／ARCHIFY_HTML | 明确命名与自研替代决策；若声明兼容，定义协议／行为并用真实样本验证；补实际图片导出格式和离线工件测试 |
| 私有GitHub | 凭据与导入代码，未真实仓库联调 | 在授权范围验证创建／轮换／撤销、私有导入、失败脱敏及旧来源；先用测试适配器覆盖逻辑 |
| 自动同步 | 当前手动检查／同步 | 保留未实现状态；若选入批次，补调度、无变化去重、并发、取消、ref变化及新快照保留策略 |
| 时序图 | 静态调用树及消息展示 | 增加递归、分支、异步、多实现、未解析、截断及源码跳转断言 |
| 完整UML | 成员和字段类型关联候选 | 泛型、多重性推断、组合证据、嵌套类型、旧索引及大成员类逐项测试，按能力矩阵报告 |
| 扫描性能缓存 | 祖先目录按扫描缓存 | 构造已校验祖先被替换／恢复的竞态样本，检查双扫描、文件读取边界及系统假设；性能与安全共同验收 |
| CI | 本机旧套件35项及新增烟测 | 增加上述专项回归和新增工作区E2E；单次runner证据、分段日志及远端状态分别记录 |

架构交互应验证多工程／多环境／多模块、未知组件和配置证据。导出范围、视图筛选和当前投影应一致且可解释，敏感配置值、绝对路径和凭据不能进入工件。

## 9. 执行批次与交付

批次一处理N01～N03，新增专项测试并回归既有功能。批次二在默认关闭条件下设计N04／N05，再进行授权受控执行验证。范围遗漏分别登记子任务；真实私有仓库、自动同步和额外图片格式按具体选择推进。

建议报告位置为CodeAtlas后端/docs/reports/new-capabilities-fix-Nxx-2026-11-17.md，证据位于对应evidence子目录。每份包含基线、复现及实际观察、代码／Schema改动、历史兼容、执行命令与退出码、测试数量及跳过原因、原始证据、产物摘要、未完成和集成注意。

测试命令在对应工程执行，JDK21及兼容版本、测试库和服务配置按当前README准备。执行mvn指定新增测试，随后全量H2／MySQL；前端构建和现有E2E结合新增用例实际运行。最终包重新构建并逐文件核对dist，记录新的版本／哈希，真实启动验证正常入口。

文档更新覆盖开发报告、scope、OpenAPI／Schema、ADR及当前状态索引。旧验收保留原时点，新的成功只声明本次实际覆盖范围。

## 10. 源码定位

- [流程图构建](D:/CodeAtlas/CodeAtlas后端/src/main/java/dev/codeatlas/analysis/FlowService.java)
- [GitHub同步](D:/CodeAtlas/CodeAtlas后端/src/main/java/dev/codeatlas/github/GitHubSyncService.java)
- [绑定状态契约实现](D:/CodeAtlas/CodeAtlas后端/src/main/java/dev/codeatlas/github/GitHubCredentialService.java)
- [导出服务](D:/CodeAtlas/CodeAtlas后端/src/main/java/dev/codeatlas/architecture/ArchitectureExportService.java)
- [执行器](D:/CodeAtlas/CodeAtlas后端/src/main/java/dev/codeatlas/execution/ExecutionService.java)
- [执行数据模型](D:/CodeAtlas/CodeAtlas后端/src/main/resources/db/migration/V10__github_credentials_and_execution.sql)
- [V0.5开发范围](D:/CodeAtlas/docs/CodeAtlas-V0.5-项目开发说明书-2026-11-17.md)

本文对修复顺序和技术方案作建议安排，未在本次修改产品实现。N01～N05应由后续agent补失败复现及实际修复证据，静态风险、代码已实现和验证通过分别管理。
