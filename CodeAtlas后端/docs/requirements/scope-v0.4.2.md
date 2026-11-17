# V0.4.2 公开 GitHub 仓库导入

依据用户对 IMP-01 P1 的实现选择：公开仓库、分支/标签、复用本机 Git CLI。

| 需求 | 交付 |
|---|---|
| 远程仓库导入 | `POST /api/imports/github`，公开 github.com 仓库根地址，可选分支/标签 |
| 快照与索引 | 复用现有 IMPORT 任务、SnapshotScanner、commit 记录与索引流水线 |
| 任务可追溯 | V7 增加 `source_type` 与 `source_ref`；失败任务可按原 ref 重试 |
| 前端入口 | 导入弹窗增加“本地目录 / GitHub 仓库”切换与来源授权说明 |
| 安全边界 | 仅 HTTPS github.com；无凭据；浅克隆；禁 submodule/hooks/LFS；超时与 staging 清理 |
| 明确不做 | 私有仓库认证、OAuth、自动同步、远端代码执行、submodule/LFS 内容 |
