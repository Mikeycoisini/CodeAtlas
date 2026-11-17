# 范围遗漏补正报告（archify / 私有仓库 / 同步 / UML / 扫描缓存 / CI）

日期：2026-11-17。对应《CodeAtlas-新增能力缺陷修复开发说明》第 8 节。

## 1. archify 命名与自研决策

- **决策**：不集成外部 archify npm 包，也不读取开发者个人技能目录。产品内渲染器是 CodeAtlas 自研的独立 HTML/SVG 生成器。
- **命名修正**：
  - 历史格式名 `ARCHIFY_HTML` 继续接受，避免破坏现有 API/记录。
  - 新增明确别名 `ARCHITECTURE_HTML`，归一为 `ARCHIFY_HTML`。
  - 新增真实图片格式 `SVG`，产物 `architecture.svg`，下载 content-type `image/svg+xml`。
- **离线工件验证**：
  - HTML 不包含 `<script src=`、外链样式或 `url(http...)`。
  - SVG 是单文件内联 `<svg>`，不含 `<script>`、`<image>` 或外部样式。
  - 两者均不输出绝对目录或令牌。
- **限制**：PNG 栅格化未实现；SVG 是当前实际图片导出格式。

## 2. 私有 GitHub 与同步

- 新增测试适配器，不访问真实 GitHub：
  - `GitHubCredentialService.validate` 改为可覆盖，测试返回固定 scopes。
  - `GitHubSyncService.remoteHead` 改为可覆盖，测试返回固定 SHA。
  - `GitHubFetcher.fetch(..., token)` 在测试中复用本地 fixture 克隆。
- 覆盖：
  - 保存加密凭据、列表不返回 token。
  - 私有导入、项目绑定、绑定标签。
  - 手动 check 返回远端 SHA / changed。
  - 手动 sync 创建新快照，旧快照保留。
  - rotate、revoke；撤销后私有导入返回 409 `GITHUB_CREDENTIAL_REVOKED`。
- 真实私有仓库联调仍需真实 token 和网络授权；本轮未执行。

## 3. 自动同步

- 当前仍为手动检查/同步；未启用调度、无变化去重、取消和 ref 变化策略。
- 本轮明确保留“未实现”状态，不把手动同步报告为自动同步。

## 4. 时序图

新增 `ArchitectureIntegrationTest.sequenceReportsCyclesUnresolvedTargetsAndTruncation`：

- 递归调用 `cycle=true`。
- 未解析目标参与者 `未解析 · missing.audit()`。
- `limit=1` 时 `truncated=true`。
- 消息证据包含文件/哈希/行号（既有断言）。
- 分支、异步、多实现仍按静态调用树近似，未扩展运行时语义。

## 5. 完整 UML

新增断言：

- `java.util.List<DemoService>` 字段识别为 `AGGREGATION_CANDIDATE`，多重性 `0..*`。
- 普通字段 `DemoService` 多重性 `1`。
- 泛型、集合、字段类型关联候选有专项覆盖。
- 嵌套类型、复杂泛型边界、旧索引大成员类仍按能力矩阵报告，未宣称完整覆盖。

## 6. 扫描性能缓存竞态

新增 `SnapshotScannerTest.cachedDirectoryValidationStillDetectsSubtreeReplacement`：

- 扫描过程中替换已访问目录：
  - 原目录移动为 `a-old`；
  - 原路径创建新目录并写入不同文件。
- 第二次扫描与第一次文件清单不一致，返回 `SOURCE_CHANGED`。
- 证明目录校验缓存不会吞掉子树替换；文件叶子仍被重新校验。

## 7. CI 与新增工作区 E2E

- 新增 `tests/new-workspaces.spec.ts`：
  - 架构视图 4 节点渲染；
  - 代码分析、AI 助手、GitHub 视图可切换；
  - GitHub 无绑定空状态不报“服务响应无效”。
- CI 变量补充：
  - `TEST_ARCH_DB_URL` → `v046_arch`
  - `TEST_EXEC_DB_URL` → `v046_exec`
- 全量结果：H2 114/0/0/5；MySQL 114/0/0/1；E2E 36/36。
- 远端：GitHub 连接仍被重置，本机无 `gh`，未推送、未触发远端 CI。

## 8. 剩余限制

1. 真实私有仓库、真实 Docker、自动同步未执行。
2. SVG 为实际图片格式；PNG 未实现。
3. 时序图仍是静态调用树，异步/多实现不展开。
4. UML 对复杂泛型和嵌套类型的推断仍有限。
5. 扫描缓存竞态测试使用目录替换，不构造真实 junction/symlink（Windows 权限限制）。
