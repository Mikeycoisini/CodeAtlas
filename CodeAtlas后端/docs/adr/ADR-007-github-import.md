# ADR-007 公开 GitHub 仓库导入

日期 2026-11-15。状态：已采纳。归属 IMP-01 P1，交付版本 0.4.2。

## 决策问题

如何在不削弱本机单用户安全边界、不复制现有快照与索引流水线的前提下，增加 GitHub 仓库导入？

## 背景与约束

- `POST /api/imports` 当前只接受本地绝对目录；`SnapshotScanner.capture` 负责不可变快照、哈希清单、Git 状态与 commit；`TaskService` 以单工作线程执行 IMPORT/INDEX 任务。
- PRD IMP-01 P1 要求 GitHub 导入，认证方式待设计；IMP-04 禁止执行项目构建脚本；非功能要求最小权限与可追溯性。
- 本机已声明 Git 运行依赖，`GitProbe` 已有固定参数、超时与输出限制的加固调用。
- 项目复用治理要求固定版本、核查许可证、PoC 与 ADR；优先复用现有能力，避免新增供应链。
- 服务绑定 127.0.0.1，使用运行时会话令牌；JSON 请求体实际读取上限为 1 MiB。

## 备选方案

| 方案 | 结论 | 理由 |
|---|---|---|
| A. 本机 Git CLI 浅克隆到 staging，再走现有快照流水线 | 采纳 | 无新依赖；复用已声明的 Git 与 `GitProbe` 加固方式；快照、commit、代次语义不变 |
| B. 引入 JGit | 未采纳 | 纯 Java、控制力强，但新增 EPL-2.0 依赖、供应链审查与 JAR 体积，需要单独 PoC |
| C. GitHub REST tarball API | 未采纳 | 需处理压缩包解压、zip-slip、大小限制与限流，新增攻击面 |
| D. 保持本地导入，由用户手工 clone | 未采纳 | 不满足 IMP-01 P1 |

## 决策

- 新增 `POST /api/imports/github`，请求 `{url, ref?}`；保留现有 `POST /api/imports` 不变。
- 首版仅支持公开仓库：URL 必须是 `https://github.com/{owner}/{repo}(.git)?` 根地址；拒绝 http、其他主机、URL 凭据、查询参数、片段、非默认端口和 `git@` 地址。
- `ref` 可选，仅接受分支或标签名称；省略时使用远端默认分支。字符集与 Git ref 规则做保守校验，拒绝 `-` 开头、`..`、`//`、`.lock` 等。
- 克隆到 `CODEATLAS_DATA/github-staging/<taskId>`，使用 `--depth 1 --single-branch --recurse-submodules=no`，禁用 hooks、fsmonitor、LFS smudge 和全局／系统 Git 配置；清除可注入的 `GIT_*`/`SSH_*` 环境变量，禁止终端提示。
- 克隆有超时与取消轮询；stdout/stderr 截断；成功、失败、取消后删除 staging。
- 克隆完成后复用 `SnapshotScanner.capture` 与 `AtlasStore.completeImport`；GitProbe 记录解析后的 commit，`.git` 不进入快照。
- V7 迁移增加 `projects.source_type`、`tasks.source_type` 与 `tasks.source_ref`；既有数据默认 `LOCAL`，不保存任何凭据。
- 前端导入弹窗增加“本地目录 / GitHub 仓库”切换；失败任务可通过 `POST /api/tasks/{id}/retry` 使用原 URL 与 ref 重试。
- 明确不做：私有仓库、OAuth/GitHub App、凭据保存、submodule/LFS 内容、自动同步、远端代码执行。

## 后果

**正面**：复用既有导入、快照、索引与重试链路；可回退；无新运行时依赖；凭据面为零；公开仓库导入与本地导入共享同一套证据与代次语义。

**负面**：依赖本机 Git 与网络；浅克隆大仓库仍有下载时间与磁盘峰值；不支持 submodule/LFS 内容；首版无法导入私有仓库；Git 版本差异可能影响错误文本映射。

## 可逆性

两向门。移除新端点、UI 与 staging 代码即可回到本地导入；V7 只增加带默认值或可空列，旧数据不受影响。触发重审：需要私有仓库、OAuth、多用户、自动同步，或浅克隆限制无法满足真实仓库。

## 安全边界

- 远端仓库视为不可信数据：不执行构建、测试、脚本或 hook。
- 仅允许 github.com HTTPS；克隆使用独立 HOME、空全局配置、`GIT_CONFIG_NOSYSTEM=1` 和空 credential helper。
- `core.symlinks=false`、`core.autocrlf=false`、`core.hooksPath=`；禁用 LFS smudge 与递归 submodule。
- 沿用现有 20,000 文件、单文件 2 MiB、总计 256 MiB 限制，以及重解析点/符号链接校验。
- 克隆进程超时默认 180 秒，可取消；输出最多保留 64 KiB 用于错误分类。

## 适应度函数

| 不变量 | 检查 |
|---|---|
| 只允许 github.com HTTPS 仓库根地址 | `GitHubFetcherTest` 覆盖 http、异主机、userinfo、query、fragment、子树地址、非标准端口、SSH 地址 |
| ref 不能形成选项注入 | `GitHubFetcherTest` 覆盖 `-` 开头、`..`、空格、反斜杠、Git 特殊字符与 `.lock` |
| 快照 commit 等于克隆 HEAD | 本地仓库克隆后由 `SnapshotScanner` 捕获并比对 `rev-parse HEAD` |
| 不执行远端代码 | 关闭 hooks/submodule/LFS；快照仅读取文件 |
| staging 在成功/失败后清理 | 单元失败用例与集成成功用例断言目录不存在 |
| 既有快照限制生效 | 复用 `SnapshotScanner` 现有 `IMPORT_LIMIT` 路径 |
| 失败可重试且使用原 ref | 集成测试模拟一次网络失败后重试成功 |

## 职责

用户负责选择仓库并确认授权；后端维护者负责 URL/ref 校验、克隆参数、超时与清理。本地检查路径为 `mvn test`、`npm run build`；正式发布前仍需 MySQL 与浏览器验收。代码责任：`ASSUMED: CodeAtlas 后端维护者`。

## 后续

私有仓库认证与凭据保管、OAuth/GitHub App、真实大仓库配额与限速、真实网络浏览器验收，需在后续设计与 ADR 中单独确认。
