# N04 容器生命周期与结果分类修复报告（2026-11-17）

## 基线

- 问题位置：`dev.codeatlas.execution.ExecutionService.run/execute`。
- 本轮新增 `DockerRunner` 边界、`ProcessDockerRunner`、`V12__execution_job_binding.sql`。
- 配置仍默认 `codeatlas.executor.enabled=false`。

## 原因与影响

原实现超时只销毁本机 `docker run` 客户端进程，服务器侧容器可能继续运行；没有持久容器 ID 和 stop/kill/rm 路径。`EXECUTOR_TIMEOUT` 被通用 catch 改为 ERROR，非零退出全部 FAILED，无法区分测试失败、构建错误和基础设施错误。

## 修复

### 容器身份与回收

- 每个 job 固定容器名 `codeatlas-job-<jobId>`，并写入 `--label codeatlas.job=<jobId>`。
- `docker run` 使用 `--cidfile`，实际容器 ID 写入任务目录并保存到 `execution_jobs.container_id`。
- 超时/取消：
  - 读取 cidfile；
  - `docker stop --time 5 <id>`；
  - `docker rm -f <id>`；
  - `docker inspect` 验证不再运行；
  - 清理结果写入 `cleanup_status/cleanup_message`。
- 正常结束也按同一容器 ID 删除，不使用任意名称或无范围批量删除。
- 服务启动扫描 `QUEUED/RUNNING/CREATED` 任务，按持久容器 ID 停止并标记 `INTERRUPTED/EXECUTOR_RESTART`。

### 结果分类

- `TIMEOUT`：超时。
- `CANCELLED`：取消。
- `PASSED`：退出码 0。
- `BUILD_ERROR`：输出包含编译错误、依赖解析失败或主类缺失。
- `TEST_FAILED`：输出包含 surefire 测试失败或 `Failures: n>0`。
- `FAILED`：其他非零退出。
- `ERROR`：执行器/复制/存档等基础设施错误。
- 清理失败不覆盖终态，单独记录 `cleanup_status=CLEANUP_FAILED`。

### 日志与并发

- stdout/stderr 有界排空（64 KiB），超出追加 `[log truncated]`。
- 单工作线程队列 `SINGLE_WORKER`，状态接口报告 `maxConcurrency=1` 和结果类别。
- 取消标记写入 `cancel_requested`，运行中的 Docker runner 每 250 ms 检查。

## 验证

- `ProcessDockerRunnerTest` 3/3：命令包含固定 name/cidfile/label/network/pid 限制且不经 shell；空容器 ID 明确跳过清理；结果分类。
- `ExecutionServiceIntegrationTest` 4/4：
  - 超时 -> `TIMEOUT`，保留 `containerId` 和 `cleanupStatus=CLEANED`。
  - 编译错误 -> `BUILD_ERROR`。
  - 测试失败 -> `TEST_FAILED`。
  - 重启恢复 -> `INTERRUPTED`，仅停止持久记录中的容器 ID。
- 全量 H2 114/0/0/5；MySQL 114/0/0/1。

## 兼容与限制

- API 默认仍关闭；`GET /api/executions/status` 的默认关闭行为不变。
- 真实 Docker 守护进程未在本轮启用验证；使用命令替身验证调用序列、分类和恢复。
- 命令中的资源参数只证明配置声明，实际内核限制需 PR-05 受控镜像测试。

## 证据

- `src/main/java/dev/codeatlas/execution/DockerRunner.java`
- `src/main/java/dev/codeatlas/execution/ProcessDockerRunner.java`
- `src/main/java/dev/codeatlas/execution/ExecutionService.java`
- `src/test/java/dev/codeatlas/execution/ProcessDockerRunnerTest.java`
- `src/test/java/dev/codeatlas/execution/ExecutionServiceIntegrationTest.java`
