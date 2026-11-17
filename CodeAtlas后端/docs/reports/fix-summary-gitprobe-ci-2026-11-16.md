# 本次修复总结：导入误报与 CI 落地（2026-11-16）

对应复核意见的两点纠正：

1. CI 文件编写与 remote/push 授权分开处理。
2. E2E“页脚指向另一项目”的失败单独复现，不全部归因于内存。

## 1. 结论

| 项 | 结果 |
|---|---|
| 页脚失败根因 | 导入任务因 `SOURCE_CHANGED` 失败，UI 保留旧项目；不是迟到响应，也不是 F02 |
| 触发条件 | 导入子树之外的仓库文件在两次扫描之间发生变化 |
| 产品修复 | `GitProbe` 的 `git status` 限定到导入子树（`-- .`） |
| 回归测试 | `SnapshotScannerTest` 9 项，0 失败，1 跳过 |
| 运行包 | 36,469,810 字节，SHA-256 `B8F23A21C1FB641A79756BF7ED12B465F40F44AC7352B4BDCAFFC7B3ADA306AB` |
| CI 配置 | 已落地并校验；未 push，远端运行待授权 |
| E2E 重跑 | 仍受本机客户端/内存限制；页脚逻辑已验证，完整套件需在充足内存环境复跑 |

## 2. 页脚失败复现与根因

失败 trace（`explore-imports-a-fixed-sn-…`）显示：

- 导入任务 `3bcbae51-654c-424b-8d24-9070824e7f33` 最终状态 **FAILED**，错误：
  `SOURCE_CHANGED 源码在导入过程中发生变化，请重试`。
- 导入后的 `/api/projects` 已包含新项目 `24e11608-…`（sourcePath 为本次 `browser-fixture-GbQl6M`），
  但页面仍选中旧项目 `2c13fe4a-…`，页脚显示旧 `training-browser-…` 路径。
- 因此“页脚指向另一项目”是导入失败的**症状**，不是页脚或迟到响应本身的问题。

根因在 [GitProbe.java](../../src/main/java/dev/codeatlas/snapshot/GitProbe.java)：

- 原命令为仓库级 `git status --porcelain=v1 --untracked-files=all`。
- 导入期间，同一仓库内、导入子目录之外的文档/证据写入会改变 before/after 的 Git 状态。
- `SnapshotScanner` 比较两次扫描结果，于是把“仓库其他位置变化”误判为“导入源码变化”，返回 `SOURCE_CHANGED`。
- 这解释了为什么在并发写文档时容易失败，而单独重试导入通常成功。

## 3. 代码修复

| 文件 | 变更 |
|---|---|
| `src/main/java/dev/codeatlas/snapshot/GitProbe.java` | status 命令增加 `-- .`，只统计导入子树的路径 |
| `src/test/java/dev/codeatlas/snapshot/SnapshotScannerTest.java` | 新增 `gitStatusIsScopedToTheImportedSubdirectory`：仓库根 dirty + 外部未跟踪文件时，断言状态只含导入子树 |

行为与兼容性：

- 新导入的 `git_status`/快照身份只包含导入子树，不再被仓库其他位置的改动污染。
- 已有快照、历史代次和数据库记录不重写、不迁移。
- 同一棵子树内容不变时，重复导入的摘要保持稳定。

## 4. CI 落地

| 文件 | 内容 |
|---|---|
| `.github/workflows/ci.yml` | 后端 H2 job、MySQL 服务容器 job（11 个测试库）、契约 YAML 检查 job；无真实模型密钥 |
| `scripts/ci-local.ps1` | 本地按 H2 → MySQL → 前端构建 → Python unittest → 可选 E2E 执行 |

校验结果：

- `ci.yml` 经系统 Python PyYAML 解析通过：`jobs=h2,mysql,contracts`。
- `ci-local.ps1` 经 PowerShell 语法解析通过。
- 未执行 `git push`，未运行 GitHub Actions。配置解析仅证明语法可读；本地脚本执行、远端运行与其他工程的持续集成覆盖分别验收。

## 5. 验证

| 验证 | 结果 |
|---|---|
| `SnapshotScannerTest` | 9 项，0 失败，1 跳过（Windows junction 沙箱） |
| 新 JAR 启动 | 18080 健康 `{"status":"UP","version":"0.4.6"}` |
| JAR 静态资源 | `index.html` + 145 个 assets，共 146 个文件；JS/CSS 200 且哈希与 dist 一致 |
| E2E 单用例复跑 | 仍超时；页面空白、无 `/api` 请求，资源完整、无页面异常；归因于本机浏览器/系统内存限制 |
| 历史全量 | H2 86/0/5、MySQL 86/0/1、Python 12/0（GitProbe 修复前结果；修复后目标测试通过） |

## 6. 未完成与风险

- 完整 E2E 35/35 需在内存充足或清理后的环境重跑；当前机器多次出现 JVM native OOM 与 Chrome 空白页。
- 前端 minified 重建仍受 esbuild/内存限制；dist 已从 JAR 恢复，未影响当前运行包。
- 远端 CI 运行、提交与 push 未获授权。
- 真实模型语义评估仍因无密钥/外发授权受阻（F07）。
- 本轮为缓解内存压力，测试 MySQL 3307 以 64MB buffer pool、关闭 performance_schema 运行；`v046_browser` 数据完整，用户 3306 未触碰。

## 7. 复现与验证命令

```powershell
# 目标回归
$env:JAVA_HOME='E:\jdk24'
mvn -q -DforkCount=0 -Dtest=SnapshotScannerTest test

# 重新打包（需先停止占用 JAR 的进程）
mvn -q -DskipTests package

# CI 配置解析
python -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml', encoding='utf-8'))"
python -c "import yaml; yaml.safe_load(open('contracts/openapi.yaml', encoding='utf-8'))"
```

相关记录：`followup-F10-2026-11-16.md`（CI）、`followup-F11-2026-11-16.md`（E2E 根因与回归）、`followup-status-2026-11-16.md`（总状态）。
