# V0.1/V0.2 独立审阅差距修复设计

日期为 2026-11-14，状态为用户已批准并完成修复验收。

## 依据与目标

独立 HTTP 复现已确认 D01 和 D02，记录位于 target/independent-review-http.json。用户选择点击“检查当前源码”时核验。修复保持旧快照与保存资料身份，补齐可解释的 INFERRED 规则，并确认 D03 阶段归属。

## D01 用户触发的来源核验

新增 POST /api/sources/{id}/check-current，接受空 JSON。来源记录解析出项目原始目录与相对路径。复用现有导入边界检查，遵守当前 allowed-roots，拒绝链接、重解析点、越界与特殊文件。单次只读该来源文件，最多2 MiB，读取前后核对属性及哈希。

返回 sourceId、status、checkedAt、message，可附历史及当前哈希。status 为 MATCHED、CHANGED、MISSING、UNAVAILABLE、UNSAFE 或 LIMIT_EXCEEDED。检测中出现并发变化归为 UNAVAILABLE，用户可以重试。异常只返回受控说明。

GET 来源继续保留 snapshotOutdated 的“存在较新已导入快照”语义，增加 currentSourceStatus=UNCHECKED，表达尚未检查当前工作区。自动读取源码仅发生在用户按下检查按钮后。检查结果仅代表检查时刻，显示在当前资料来源行中，切换实体后迟到结果不进入另一资料。

该操作不创建快照、不重建索引、不修改来源或资料，也不发送模型请求。原始源码缺失时仍可从已有快照读取历史片段。

## D02 Spring 事务边界候选

新增规则 SPRING_TRANSACTIONAL_DECLARATION_V1。仅对能静态确认注解名称为 org.springframework.transaction.annotation.Transactional 的方法声明生成 TRANSACTION_BOUNDARY / INFERRED 关系，定位到注解范围。采用完整限定名或明确单类型 import，拒绝本地同名注解遮蔽及通配导入歧义。构造函数不产出该规则。

targetId 指向该方法，targetText 标明事务代理候选。reason 包含规则名、注解证据和运行时条件，说明代理启用、调用路径及事务管理器仍需核对。resolver 标记独立规则名。此关系表达事务声明候选，现有编译期 CALL 和运行时 UNRESOLVED 关系继续保留。

分析器版本递增为 javaparser-3.27.1-v2，已有代次仍可查询，新规则在用户重建索引后出现。前端现有状态展示复用 INFERRED，并展示 reason 与证据定位。

## D03 阶段归属

已批准 2026-11-14-v0.2-design.md 明确将学习掌握状态和复习调度归入 V0.4。总体计划 V04-03 关联原理问答、复习和练习成果。补充需求追踪，KN-01 在 V0.2 覆盖独立知识与跨项目来源，个人掌握记录在 V0.4 独立保存并保留依据。V0.2 卡片和用户笔记不表示掌握度。

## 验证与交付

后端真实 HTTP 测试覆盖修改未导入、删除文件、新快照、不可读/不安全路径和大小边界，确保历史片段不变。解析测试覆盖正确 Spring 注解、完整限定名、同名遮蔽、通配歧义和普通方法。前端浏览器验证用户按钮、状态、历史跳转及迟到响应隔离。

回归全部 Java、Python 和 TypeScript 构建。浏览器使用隔离服务和合成样本。补充本机冷加载观察，测量条件与覆盖边界写入追踪报告。原独立报告保留，本轮另附复核报告。第三方模型语义与完整安全/压力结论按实际执行状态记录。

本轮沿用原目录的文件交付，源码凭据配置由用户管理。构建测试使用独立数据目录，用户正在运行的实例保持其原状态。
