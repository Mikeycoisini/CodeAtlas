# CodeAtlas 灾后补充代码修复与验证（2026-11-19）

基线提交：f41c9b947088cf8e7460d8de55e3ef03a46e28d7。对应独立复核 R01—R06。本轮在 C 盘隔离源码完成修复，保留 E 盘恢复数据；未使用恢复目录中的空字节文件覆盖工程。

## 修复内容

| 编号 | 修复 |
|---|---|
| R01 | 新增 V13 项目归属迁移、索引和可确定的历史归属回填；不更改 V1—V12 校验和 |
| R02 | 任务、作答 HTTP 请求使用当前项目；知识/文档/卡片、复习队列、掌握列表、错题和引导列表过滤项目；拒绝当前项目与外部来源不匹配；头部选择器全工作区可用；切换清除 AI 选择 |
| R03 | 判断题只接受明确真/假字符串；unknown、空串、maybe、2 等拒绝成为答案；新增 Python 回归 |
| R04 | 新增后端 learning-pack 原子采纳接口；以生成记录加锁、V14 持久化采纳结果；知识/卡片/任务同一事务提交；不同浏览器幂等键重试同一生成仍返回相同结果；保存 generationId |
| R05 | 知识点代码从授权固定快照按合法范围提取，越界拒绝；采纳时再次读取固定源码；不直接将模型编写的 code 当原始源码 |
| R06 | 新增共用 SourceSelector；分析页与 AI 页可直接选择文件/符号；支持整个文件、搜索、分页、服务端文件过滤，固定所选代次 |

## 补回的缺失项

- LocalDirectoryController：本机 Swing 目录选择、单窗口互斥、超时/中断取消，EDT 窗口真正结束才释放互斥；无桌面返回手工输入回退。入口仍需单独授权后导入，不自动创建快照。
- Spring Boot 明确 setHeadless(false)；点击“本地目录”直接调用选择接口，选择后清除授权勾选；GitHub 地址仍手工输入。
- ai-pack-retry.spec.ts：无历史选源、首次准备失败重试、授权前禁用生成、原子采纳请求、目录回填/授权重置。
- V13__project_scope.sql 与 V14__learning_pack_adoption.sql。
- 前端固定 127.0.0.1:5171、strictPort，Playwright 与后端默认开发 Origin 同步；localhost 同开发端口兼容保持。
- Terser 限制 maxWorkers=2；图布局忽略自环对 Dagre 节点尺寸的放大，普通关系采用独立正交通道；默认 40 节点；缓存含布局版本和结构摘要。
- 架构画布增加缩放/重置、静态关系演示和静态分组说明，使用折线路由。该增量不代表与参考 HTML 的完整视觉验收已完成。
- Windows Git 子进程使用空全局配置和 longpaths；本地 upload-pack 样本的短临时目录可由 codeatlas.test.temp 指定，避免审查路径本身超过 Windows Git 限制。

## 新接口与数据约定

POST /api/training/ai/generations/{id}/learning-pack，请求仅接受 idempotencyKey；服务端从成功的 TRAINING_QUESTIONS 记录及授权上下文取得资料，不接受客户端提供知识/题目或项目路径。

返回 trainingId、knowledge、cards、knowledgeIds、cardIds。以 generation_id 唯一持久化，整个采纳失败时不保留部分知识/卡片。已有仅创建训练任务的 /task 接口继续兼容。

当前项目由 projectId 查询参数指定。无参数的旧客户端仍可管理原有跨项目资料；多项目历史来源不会被强行归为单一项目，保持未分配。CARD 可以关联已软删除的既有知识并继续编辑，但不允许新建关联到已删除知识。

## 验证

- H2 全量：125 tests，0 failures，0 errors，4 条条件跳过；生产 JAR 打包通过。末次日志 fix-package-final.log。
- 独立 MySQL 8.0.43（本机 3317）：训练与 AI 事务回归及本地 Git 共 31 tests，0 failures，0 errors，0 skipped。使用本轮专用 fix_training / fix_ai，不触碰已有服务。
- MySQL V12→V14、全新 V1→V14、中文知识/卡片归属回填、重启后四个测试库迁移校验和及归属保留：通过。执行前核验测试数据目录的 server UUID。
- Python：14/14 通过，包括无效判断答案拒绝；本机 Pydantic 2.13.4，requirements 锁定 2.12.4，完整锁定环境可由 CI 继续复验。
- TypeScript 与 Vite 生产构建通过；普通/自环布局、并行正交通道共 6 项 Node 图形测试通过。
- Playwright 本轮针对性回归 3/3：使用固定 API 响应，覆盖新增工作区、无历史选源、AI 重试与原子采纳、目录回填。没有用这些结果冒充真实模型或真实桌面窗口验收。
- 打包前清理隔离 target/classes/static，按哈希比较前端 dist 与 JAR 中静态文件；结果见本目录 JSON 证据摘要。

## 验证边界

本轮没有外发真实业务源码、调用收费模型，未重复执行全部历史浏览器业务流程，也未执行真实桌面窗口的置顶/超时故障注入。参考架构图完整视觉对齐、生产性能和远端 CI 执行不能据本报告标为通过。前端主包仍有大小警告，尚未据此测量实际加载性能。

本机证据日志与临时数据库留在 C 盘隔离工作区；不把运行令牌、.env、数据库、依赖、JAR、dist 或完整会话上传到仓库。
