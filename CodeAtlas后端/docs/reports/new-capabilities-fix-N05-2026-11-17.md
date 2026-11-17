# N05 任务归属、不可变提交与幂等修复报告（2026-11-17）

## 基线

- 问题位置：`dev.codeatlas.execution.ExecutionService`、`ExecutionController`、`V10__github_credentials_and_execution.sql`。
- 本轮新增 V12 `execution_job_binding` 字段。
- 默认 `codeatlas.executor.enabled=false`。

## 原因与影响

原 `execute(projectId, taskId, workspace)` 只核对项目存在和路径在 `data/practice` 下，未绑定任务、快照、工作区和项目；复制的是当前可变练习目录；执行结果未绑定准确 submission 摘要；`execution_jobs.snapshot_id` 未写入；没有幂等请求身份。

## 修复

### 请求与归属

- 执行请求改为 `submissionId + idempotencyKey`。
- 控制器拒绝 `projectId/taskId/workspace` 旧字段，返回 400 `EXECUTOR_LEGACY_REQUEST`。
- 服务端由 `PracticeService.submission(submissionId)` 推导：
  - `workspaceId`、`snapshotId`、`taskId`、`projectId`
  - `contentDigest`
  - 不可变 `archivePath`
- 不再由客户端决定宿主机路径或执行内容。

### 不可变内容

- 从 `data/practice/submission-*` 归档复制，而不是当前 workspace。
- 复制前重新校验归档 manifest/content digest。
- 复制时拒绝符号链接、重解析点和特殊文件；路径必须位于 `data/practice`。
- 复制完成后重新计算 manifest 摘要并与 job 的 `contentDigest` 比较；不一致在启动 Docker 前失败 `SUBMISSION_CORRUPTED`。
- 原工作目录后续修改不影响执行副本。

### 幂等与记录

- 复用 `mutation_requests`，operation=`EXECUTION_CREATE`，请求摘要包含：
  - submissionId
  - contentDigest
  - image
  - timeout
  - command
- 同键同提交返回同一 job；同键改提交返回 409 `IDEMPOTENCY_CONFLICT`。
- `execution_jobs` 记录：
  - project/snapshot/task/submission
  - content_digest/request_digest/config_digest
  - idempotency_key
  - container_name/container_id
  - workspace_path/archive_path
  - result_class/cleanup_status/cleanup_message
- 队列状态：`QUEUED -> RUNNING -> PASSED/TEST_FAILED/BUILD_ERROR/TIMEOUT/CANCELLED/FAILED/ERROR/INTERRUPTED`。
- `GET /api/executions/{id}` 查询；`POST /api/executions/{id}/cancel` 取消。
- API 视图移除 `workspacePath`/`archivePath`，不返回宿主机内部路径。

## 验证

`ExecutionServiceIntegrationTest` 4/4：

- 执行成功并绑定正确 project/snapshot/task/submission/contentDigest。
- 执行副本来自归档；修改原 workspace 后副本内容不变。
- 同键同提交返回同一 job，Docker 调用不重复。
- 同键改提交 409。
- 旧 `workspace/projectId/taskId` 请求 400。
- 未知 submission 404。
- 归档篡改在 Docker 启动前 409 `SUBMISSION_CORRUPTED`。
- 超时/编译失败/测试失败分类和容器身份记录。
- 重启恢复只停止记录中的容器 ID。

全量 H2 114/0/0/5；MySQL 114/0/0/1。

## 兼容与限制

- 旧执行请求字段不再接受；这是有意的破坏性变更，避免客户端路径决定执行内容。
- 默认关闭；真实 Docker 受控执行和 PR-05 宿主机隔离准入仍未完成。
- `practice_workspaces.status` 当前不影响已存在 submission 的执行；归档不可变校验优先。
- 执行结果与学习证据的来源适配仍未自动写入，继续保留 `USER_REPORTED`。

## 证据

- `src/main/java/dev/codeatlas/execution/ExecutionService.java`
- `src/main/java/dev/codeatlas/execution/ExecutionController.java`
- `src/main/resources/db/migration/V12__execution_job_binding.sql`
- `src/test/java/dev/codeatlas/execution/ExecutionServiceIntegrationTest.java`
