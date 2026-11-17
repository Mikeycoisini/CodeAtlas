# N03 导出幂等与失败恢复修复报告（2026-11-17）

## 基线

- 问题位置：`dev.codeatlas.architecture.ArchitectureExportService`。
- 表：`architecture_exports`（V9）；本轮新增 V11 `request_digest`。
- 幂等基础设施：`mutation_requests`（V2）。

## 原因与影响

原逻辑按 `snapshotId + idempotencyKey` 查到记录立即返回，位置在代次和投影摘要校验之前；没有比较主题、范围、代次或投影内容。同键改主题/范围/代次可能继续得到旧工件；FAILED 记录同键返回没有明确恢复语义；`synchronized` 只保护单实例。

## 复现

1. 首次导出 key=K、theme=light，成功。
2. 同 key theme=dark：修复前返回旧 light 工件，未冲突。
3. 同 key 改 generation 或 digest：修复前仍返回旧记录，不校验投影。
4. 失败记录同 key：修复前没有定义重试流程。

## 修复

### 请求摘要与命名

- 新增 `request_digest`，覆盖：
  - schema 版本
  - snapshot
  - 请求中的 generation（可为空）
  - 请求中的 projectionId / digest（可为空）
  - 归一化 format、scope、theme
- 数值/枚举归一化：format 大写、scope 大写、theme 小写；`ARCHITECTURE_HTML` 归一为历史 `ARCHIFY_HTML`。
- 同键同内容返回同一导出；同键不同内容返回 409 `IDEMPOTENCY_CONFLICT`，不创建新文件。

### 投影与代次

- 首次请求解析并校验当前代次和投影；显式旧投影返回 409 `PROJECTION_STALE`。
- 首次选定代次写入导出记录；同键重试不再因当前投影重建而丢弃已成功工件。
- 无 `generationId` 时使用当前已发布代次，并持久化。

### 事务、失败与恢复

- `mutation_requests` 先占位；`architecture_exports` 创建 `RUNNING` 记录；事务提交后渲染。
- 成功：临时文件 -> 原子移动 -> 计算大小/SHA-256 -> 更新 `SUCCEEDED`。
- 渲染/写入/数据库更新失败：记录 `FAILED` + `EXPORT_FAILED`；同键重试创建新记录，保留原失败记录。
- 启动时 `recoverInterrupted`：将遗留 `RUNNING/QUEUED` 标记 `FAILED/EXPORT_INTERRUPTED`，并清理任务目录内的 HTML/SVG 工件。
- 工件缺失：下载/重放返回 410 `EXPORT_ARTIFACT_MISSING`；SHA-256 不符返回 409 `EXPORT_ARTIFACT_TAMPERED`。
- 历史兼容：V11 之前已有 `SUCCEEDED` 且无 mutation 记录的导出，按字段匹配后直接重放；字段不匹配返回冲突，不覆盖旧工件。

## 验证

新增 `ArchitectureIntegrationTest.exportIdempotencyComparesContentAndRetriesFailures`：

- 同键同内容两次：第二次 200，同一 id，仅 1 条记录。
- 同键改 theme：409 `IDEMPOTENCY_CONFLICT`，记录数不变。
- 显式旧 digest：409 `PROJECTION_STALE`。
- 人为将首次记录改为 FAILED 后同键重试：新建 id，旧 FAILED 保留，记录数 2。
- 省略 generation：使用已发布代次并写入记录。
- 删除已成功工件后下载：410 `EXPORT_ARTIFACT_MISSING`。
- SVG 导出：格式 `SVG`，产物 `architecture.svg`，下载 content-type `image/svg+xml`，HTML/SVG 均无外部脚本/样式/图片引用。
- 全量 H2 114/0/0/5；MySQL 114/0/0/1。

## 兼容

- API 首次创建返回 201，重放返回 200；响应体结构不变。
- 旧 `ARCHIFY_HTML`/`HTML` 请求继续可用；新增 `ARCHITECTURE_HTML` 别名和 `SVG`。
- 旧导出记录不被覆盖。

## 剩余限制

- `synchronized` 仍保护单实例；跨实例依赖 `mutation_requests` 主键和数据库事务。
- 渲染仍为同步执行；大工件没有后台任务化。
- PNG 栅格化未实现；当前图片格式为 SVG。

## 证据

- `src/main/java/dev/codeatlas/architecture/ArchitectureExportService.java`
- `src/main/java/dev/codeatlas/architecture/ArchitectureHtmlRenderer.java`
- `src/main/resources/db/migration/V11__architecture_export_request_digest.sql`
- 日志：`n-fixes-h2.log`、`n-fixes-mysql2.log`
