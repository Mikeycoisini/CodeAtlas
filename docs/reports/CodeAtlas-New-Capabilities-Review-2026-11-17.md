# 架构、图形、AI与GitHub新增能力复核

日期2026-11-17。依据architecture-diagrams-ai-github-2026-11-17.md、当前源码、V0.1～V0.4剩余复核及V0.5说明书。范围为静态源码、契约方向及现存运行证据核查。本次未重新执行HTTP、浏览器、Docker或真实GitHub认证。

## 1. 结论

新增架构、分析、AI入口和GitHub相关代码均存在，原剩余功能明显推进。当前仍有控制流正确性、同步边界、导出幂等及执行器风险，报告不能据现有通过数量认定全部闭环。

当前JAR实测33342055字节，SHA256为3d0d5ead60645ddf871f4b8a673225df60dc209784ed9804d11943c04f74fb95，与报告一致。.runtime/s12-final-evidence/pw-result-final2.json实际expected=35、unexpected=0、skipped=0、flaky=0。旧35项主要为既有工作区回归，新增工作区报告另以烟测展示，不能覆盖所有新增语义分支。

## 2. 需要修复的静态发现

### N01 高优先级，流程图后继连接错误

FlowService.statements在处理每个statement后使用cursor=part.entry，忽略part.exits作为后续语句的前驱。If返回entry=decision、exits=[merge]，循环／try／switch也返回入口与不同出口。因此if后普通语句会从decision直接连接，绕开汇合；循环后语句可能从循环入口直接接出。最小样本为`if (ok) { a(); } b();`，预期b仅从分支汇合连接，当前实现会以decision作为b前驱。

同时terminal分支到END使用part.entry，存在终止边定位错误的风险。需基于出口集合构造后继，分别验证两支返回、嵌套分支、break／continue、循环、异常／finally、switch穿透及不可达语句。节点数烟测不足以验收控制流语义。此项尚未独立HTTP复现。

### N02 中优先级，无绑定项目的检查／同步判断失配

GitHubCredentialService.binding已改为无绑定返回bound=false的Map。GitHubSyncService.check与sync仍仅判断binding==null，随后读取credentialId或repoUrl。无绑定请求将越过预期GITHUB_NOT_BOUND分支，进入空凭据读取或非法导入。需统一检查bound状态，覆盖未绑定、凭据撤销、轮换、绑定删除及有效绑定的API回归。此前只测试GET binding明确状态，不能代替check／sync测试。

### N03 中优先级，导出幂等键未校验请求内容

ArchitectureExportService.export按snapshotId+idempotencyKey发现记录即返回，位置早于generation、projectionId、digest核验，且不比较format／scope／theme。相同键改主题、范围或代次会返回旧结果，陈旧摘要也可能绕过校验；FAILED记录同键直接返回，重试语义不清晰。

应复用已有mutation_requests的同键同内容复用、同键异内容冲突模式，明确失败重试并保存请求摘要。增加当前快照内跨代次、不同范围／主题、失败重试及并发测试。synchronized只能限制当前服务实例，数据库约束或事务占位仍需处理一致性。

### N04 高优先级，执行器启用后的容器回收与结果分类

ExecutionService.run超时仅终止本机docker客户端进程，未保存容器ID／名称并明确停止、删除服务器侧容器。docker run客户端终止不保证容器内任务同步结束。超时抛EXECUTOR_TIMEOUT随后被execute通用catch转换为ERROR，丢失TIMEOUT分类。所有非零退出统一FAILED，也不能区分Maven启动／依赖环境错误。

需持久记录容器身份，超时、取消、崩溃恢复与异常均由受控清理路径处理，分类保留TIMEOUT／ERROR／FAILED含义。复制目录和失败工件也需生命周期清理。增加真实Docker受控测试后才可完成对应准入。当前默认关闭降低默认暴露，开关本身不能证明隔离安全。

### N05 执行内容与任务／提交绑定不足

ExecutionService.execute接受projectId、taskId和workspace路径，仅校验project存在与路径位于data/practice下，未见工作区与任务／项目／提交的数据库关联验证，执行对象也未冻结到准确submission摘要。任务执行结果应绑定不可变提交，验证身份归属，并提供幂等、队列／取消及恢复。当前能力只能记为关闭状态下的原型，不能登记PR-05完整实现。

## 3. 功能范围与原规划差异

| 能力 | 当前核对 | 尚缺范围 |
|---|---|---|
| 架构总览 | 已有提取、缓存、查询、交互及证据 | 需多个工程／多环境／多模块准确性与边界验证 |
| archify导出 | 自研ArchitectureHtmlRenderer产生独立HTML／内联SVG | 未接入或验证真实archify输入／渲染兼容性；仅支持HTML／ARCHIFY_HTML，无首个图片格式；自研替代路线应明确登记决策 |
| 流程图 | 实现存在且有节点烟测 | N01控制流语义修复及专项样本 |
| 时序图 | 有静态调用树 | 分支、异步、递归、多实现和消息语义需要专门断言；保留静态范围 |
| 完整UML | 字段／方法／构造器／关联候选、多重性已有 | 生命周期、泛型、多实现、旧索引及超过50成员等能力清单仍需实测；4成员样本不足以宣称完整覆盖 |
| AI助手 | 统一状态与入口已有 | 继续核对深链接、未选来源、离线和迟到身份隔离 |
| 私有GitHub | 加密、凭据及导入相关代码已有 | 未用真实私有仓库验证，不能认定端到端通过；N02需修 |
| 同步 | 手动检查／导入已有 | 自动定时同步未实现；无变化去重、并发及取消需对应证据 |
| 隔离执行 | 默认关闭的Docker执行原型 | N04／N05与独立安全准入、真实受控执行未完成 |
| 性能 | 同样本扫描A/B及成员预聚合已有 | 缓存祖先目录校验后的竞态／重解析点变化需定向复测，完整性能发布门槛仍需定义 |
| CI | 有现存本机35项E2E与日志报告 | 新功能专项覆盖与远端验证仍缺；分段命令通过和单次runner通过应按真实执行记录区分 |

## 4. 建议修复顺序

1. 优先修流程图语义、无绑定检查／同步和导出幂等，分别新增失败复现及回归。
2. 保持执行器关闭，完善不可变提交绑定、结果分类、容器身份与清理，再开展受控安全试点。
3. 将自研导出范围、图片缺项、私有仓库未实测、自动同步未启用同步到范围与当前索引。
4. 以新增功能专项测试补充既有E2E，覆盖真实证据及失败路径后再作完成判断。

本报告为静态发现与证据边界复核，不将未执行的复现场景标作已运行。真实第三方调用和Docker执行未在本次开展。
