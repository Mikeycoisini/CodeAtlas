# 独立审阅差距修复计划

依据已批准 docs/superpowers/specs/2026-11-14-review-gaps-design.md，内联执行并以文件交付。

## Task 1 来源检查

- [x] 在 LearningIntegrationTest 增加真实 HTTP 回归，覆盖未重导修改、删除、历史保持、链接、大小及读取边界，先观察失败。
- [x] SnapshotScanner 复用路径校验并提供有界单文件哈希，TaskService 核对 allowed-roots。
- [x] SourceEvidenceService 实现受控结果，LearningController 增加 POST /sources/{id}/check-current，GET 返回 UNCHECKED。
- [x] 单独及完整测试通过。

## Task 2 推断规则

- [x] JavaAnalyzerTest 增加 Spring 注解候选、同名遮蔽和通配歧义回归，先观察失败。
- [x] JavaAnalyzer 增加方法事务候选规则，保留其他证据等级，提升分析器版本。
- [x] 用真实 HTTP 导入并索引样本，验证 INFERRED 的规则、证据位置与历史代次。

## Task 3 界面

- [x] LearningLibrary 增加手动检查按钮、未检查及结果状态，并保护实体切换的迟到响应。
- [x] Playwright 验证未重导变化、缺失、历史片段与状态恢复，构建通过。

## Task 4 复核与交付

- [x] 全部 Java/Python 回归、隔离浏览器流程及冷加载测量，保留原独立报告。
- [x] 更新 API、README、需求阶段追踪和复核报告，记录本轮未测边界。
- [x] 独立实现审阅，重要问题回归后修复，构建运行包。

## 执行记录

用户批准手动触发及详细设计。独立 HTTP 已复现 D01 false/false/true 与 D02 INFERRED=0。D03 的 V0.4 归属在已批准设计中明确。

Pre-flight 来源检查复用 scanner 路径策略与 TaskService 允许根目录，HTTP 结果映射至前端独立状态。推断规则沿用现有 Relation JSON 结构，分析器版本变化触发新代次。无需数据库迁移。

Ruling: 当前无Git元数据，沿用原目录文件交付并记录验证产物，用户正在运行的实例不重启。版本回溯依赖文件备份。


## 完成与验证

Task 1至4完成。H2与独立MySQL各27项，Python6项，最终生产JAR浏览器8项通过。实际V1->V2升级、旧代次和MySQL重启已核对。本机冷加载5次已记录。原独立报告保留，补充报告位于根目录docs/reports/CodeAtlas-Review-Gaps-Followup-2026-11-14.md。

Final: fixed Spring注解遮蔽作用域问题 — JavaAnalyzerTest观察Sibling漏报/Child误报后修复，H2与MySQL完整套件通过。
Final: fixed junction缺失叶节点的检查顺序 — SnapshotScannerTest先观察NoSuchFileException后改为祖先优先，完整套件通过。
Ruling: 交付JAR使用环境变量配置模板，用户已填写的源码YAML保留 — 避免凭据进入可分发包 — JAR运行须由本机环境提供数据库凭据。
Ruling: 并行前端更新期间，以实际JAR内资源哈希记录生产测试和性能 — 确保测量可追踪 — 后续其他构建需要重新核对资源版本。
