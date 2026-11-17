# N02 无绑定项目检查/同步修复报告（2026-11-17）

## 基线

- 问题位置：`dev.codeatlas.github.GitHubSyncService.check`、`sync`。
- 绑定契约：`GitHubCredentialService.binding` 无绑定时返回 `{"bound":false,"projectId":"..."}`。

## 原因与影响

`check` 和 `sync` 仍用 `binding == null` 判断。无绑定时会继续读取 `credentialId`、`repoUrl`、`ref`，造成空凭据查询、非法导入或非预期服务错误；项目不存在与项目无绑定也未区分。

## 复现

1. 创建一个存在但未绑定 GitHub 的项目。
2. `POST /api/github/projects/{project}/check` 或 `/sync`。
3. 修复前：在 `credentials.token(null)` 或 `tasks.importGithub(null...)` 处进入错误路径。
4. 修复后：在读取凭据、访问 GitHub、创建 IMPORT 任务之前返回 404 `GITHUB_NOT_BOUND`。

## 修复

- 新增私有 `binding(projectId)`：
  - 先 `SELECT id FROM projects WHERE id=?`；项目不存在返回 404 `NOT_FOUND`。
  - 再读取绑定；`binding == null` 或 `bound != true` 返回 404 `GITHUB_NOT_BOUND`。
- `check` 和 `sync` 统一使用该方法。
- 不改变 `GET binding` 的 `bound:false` 返回，UI 可继续正常显示。

## 验证

新增 `ArchitectureIntegrationTest.unboundProjectRejectsCheckAndSyncBeforeGithubAccess`：

- check 与 sync 均返回 404 / `GITHUB_NOT_BOUND`。
- 调用前后任务总数不变（零新增任务）。
- 不存在的项目返回 404 / `NOT_FOUND`。
- 零网络请求由“在 token/import 之前返回”代码路径保证。

全量 H2 114/0/0/5；MySQL 114/0/0/1。

## 兼容

- 有效绑定、撤销/轮换、远端拒绝、分支不存在等路径保持原契约。
- 凭据撤销后的排队任务仍按 `GitHubCredentialService.token` 的撤销检查处理。

## 证据

- `src/main/java/dev/codeatlas/github/GitHubSyncService.java`
- `src/test/java/dev/codeatlas/ArchitectureIntegrationTest.java`
- 日志：`n-fixes-h2.log`、`n-fixes-mysql2.log`
