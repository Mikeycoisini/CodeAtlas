# 规划复核遗留项状态索引（2026-11-16）

对应《CodeAtlas 规划复核遗留项开发任务书》F01–F12。每项独立验收记录位于同目录 `followup-Fxx-2026-11-16.md`；证据统一在 `evidence/planning-followup-2026-11-16/`。

| ID | 任务 | 状态 | 结果摘要 | 记录 |
|---|---|---|---|---|
| F01 | PetClinic 成功测试原始证据 | 实现完成待验收 | 独立副本重跑 OwnerTests 3/3，exit 0；非空 stdout、Surefire XML 与 run.json；文件哈希与第二次提交一致 | [F01](followup-F01-2026-11-16.md) |
| F02 | 旧 V2 当前投影升级 | 实现完成待验收 | 旧格式夹具复现 V2/SUPPORTED/2；改为缓存需 revision+rule_version 匹配，旧规则读取重算不写库；新 JAR 返回 V3/PRACTICING/1；H2 7/0/1、MySQL 7/0/0 | [F02](followup-F02-2026-11-16.md) |
| F03 | 第二次实质实践与五类练习 | 实现完成待验收 | 受控样本五类跑通（补全/修复先红后绿；补测试/重构/扩展 3→4、3→3、3→4）；每类含日志、提交、报告、知识证据与复盘；AI 不自动提交/写证据 | [F03](followup-F03-2026-11-16.md) |
| F04 | 真实旧版本迁移与恢复 | 实现完成待验收 | Git `6b2a760` 构建 0.4.1(V6)；升级 V7/V8；资源、中文、判断、引导与数据文件哈希不变；重启与 mysqldump 恢复库一致 | [F04](followup-F04-2026-11-16.md) |
| F05 | 调度字段与中文取证 | 实现完成待验收 | `firstDueAt=null` 为导出字段错误；dueAtMs 为 epoch 毫秒且重启一致；中文 API→MySQL→JSON→备份→恢复逐字节一致；迁移摘要乱码为导出侧；历史日志不可逆 U+FFFD 已定位 | [F05](followup-F05-2026-11-16.md) |
| F06 | 分析准确性扩展 | 实现完成待验收 | 新增独立预期测试 6/6；PetClinic 132 文件/323 符号/0 诊断；关系 2,048 条按状态与未解析原因统计 | [F06](followup-F06-2026-11-16.md) |
| F07 | 真实 AI 语义评估 | 受阻 | provider 为 fixture，`.env` 无密钥；五类评价标准已定义；真实语义质量未评估，未外发源码 | [F07](followup-F07-2026-11-16.md) |
| F08 | 大样本性能与资源 | 实现完成待验收 | 查询各 10 次：P95 21.72–51.82 ms，0 错误；3 文件导入 2.95s/索引 48ms；WS 260→270MB、峰值 320MB；无门槛基线 | [F08](followup-F08-2026-11-16.md) |
| F09 | 综合安全与边界 | 实现完成待验收 | 17/17 接口检查；SnapshotScanner 9/0/1、GitHubFetcher 6/0/0；日志无密码/令牌；非外部评估 | [F09](followup-F09-2026-11-16.md) |
| F10 | 工程及发布资料 | 实现完成待验收 | Git 现状/发布清单扫描完成；`.github/workflows/ci.yml` 与 `scripts/ci-local.ps1` 已落地并校验；远端运行/push 待授权；Python settings 由 F11 实测 | [F10](followup-F10-2026-11-16.md) |
| F11 | 完整学习闭环与发布收尾 | 受阻（历史）→ 第12部分补充 | H2 86/0/5、MySQL 86/0/1、Python 12/0；E2E 页脚失败根因为导入任务 `SOURCE_CHANGED`（根仓库级 git status 误判），已修复 GitProbe 并新增扫描器测试 9/0/1；E2E 重跑仍受客户端/内存限制；最终 JAR 36,469,810 字节 / `B8F23A21…`。第12部分复核后：H2 88/0/0/5、MySQL 首跑 88/0/0/1、干净库 E2E 35/35、最终包 33,185,911 / `CF702F18…`（见补充） | [F11](followup-F11-2026-11-16.md) |
| F12 | 图形后续任务边界 | 实现完成（2026-11-17） | 系统架构总览、自研 HTML/SVG 导出、流程图、时序图、完整 UML、统一 AI 入口、私有 GitHub 与执行门禁均已落地；见 2026-11-17 报告 | [F12](followup-F12-2026-11-16.md) / [2026-11-17](architecture-diagrams-ai-github-2026-11-17.md) |

> 说明：上表 F01～F11 为各任务执行时点的历史状态；第12部分复核后的当前结果见文末“2026-11-16 第12部分复核补充”与 `section12-acceptance-2026-11-16.md`，2026-11-17 最新结果见文末对应章节。

## 集成注意

- 当前运行包：`target/codeatlas-0.4.6.jar`，33,378,231 字节，SHA-256 `CBF813AC018649AE59084E65BCD3420F6A78FB129A677D880D889A5361F19122`（2026-11-17 N01–N05 修复后干净重建；dist/JAR 101/101 一致。此前 33,342,055 字节 / `3D0D5EAD…` 为复核前时点）。
- F02 修改了产品代码；F06 仅测试；F09/F11 脚本不进入产品。最终打包以 2026-11-17 报告与 `target/codeatlas-0.4.6.jar` 的 `CBF813AC…` 哈希为准。
- F07 已在本轮第12部分使用本机 `DEEPSEEK_API_KEY` 对公开 PetClinic 完成五类真实调用；扩大模型、私有代码或费用范围仍需单独授权。F10 的 CI 文件与本地脚本已完成并修复 runner stderr/Python 临时目录问题，仍剩远端 CI 运行与 push 授权。
- 历史 V6 基线由 Git 源码构建，不是官方发布包；剩余兼容风险见 F04。
- 本次“导入误报 + CI 落地”修复总结见 `docs/reports/fix-summary-gitprobe-ci-2026-11-16.md`。

## 2026-11-16 第12部分复核补充

- 独立验收记录：[section12-acceptance-2026-11-16.md](section12-acceptance-2026-11-16.md)。
- F01～F06 已逐份阅读记录与原始证据并独立核对；建议关闭“实现完成待验收”状态。保留两处证据标注差异：F01 raw/normalized 字节数、F02 中间包哈希。
- 第12部分其他结果：干净库 E2E 35/35；dist/JAR 93/93 文件哈希一致；真实 DeepSeek 五类调用成功（8/8 引用 VERIFIED）；540 文件性能基线；12 项运行时边界 + 6 类 Git 非法输入通过；CI 为分阶段通过，单次完整 runner 受本机原生内存限制，远端未授权。
- 本轮修复：`App.tsx` 导入后自动索引状态；`ci-local.ps1` 原生命令 stderr 与 Python 临时目录；三处 Python 测试清理容错；前端构建 Terser 配置。

## 2026-11-17 架构/分析/AI/GitHub/性能/CI 完成

- 独立验收记录：[architecture-diagrams-ai-github-2026-11-17.md](architecture-diagrams-ai-github-2026-11-17.md)。
- 开发总结：[development-summary-2026-11-17.md](development-summary-2026-11-17.md)。
- 系统架构总览与自研 HTML/SVG 导出（`ARCHIFY_HTML` 历史格式名，新增 `ARCHITECTURE_HTML`/`SVG`）、流程图、时序图、完整 UML、统一 AI 状态入口、私有 GitHub 加密凭据/手动同步/执行安全门禁均已实现。
- 性能：540 文件扫描 A/B，旧 172.9/173.9 s，新 32.5/48.7 s，约 3.6–5.3 倍；类图成员改为构造期预聚合。
- 单次完整 CI：H2 114/0/0/5；MySQL 114/0/0/1（清理后最终重跑受本机 native OOM 中断，非产品断言失败）；前端构建成功；Python 12/12；E2E 36/36；新视图 UI 烟测全部通过。
- 最终包：33,378,231 字节 / `CBF813AC018649AE59084E65BCD3420F6A78FB129A677D880D889A5361F19122`；dist/JAR 101/101 一致。
- 复核修复：N01 控制流、N02 无绑定 GitHub、N03 导出幂等、N04 容器生命周期、N05 执行归属/不可变提交/幂等，详见 [N01](new-capabilities-fix-N01-2026-11-17.md)、[N02](new-capabilities-fix-N02-2026-11-17.md)、[N03](new-capabilities-fix-N03-2026-11-17.md)、[N04](new-capabilities-fix-N04-2026-11-17.md)、[N05](new-capabilities-fix-N05-2026-11-17.md) 及 [scope](new-capabilities-fix-scope-2026-11-17.md)。
- 本轮修复：GitHub 无绑定空响应、CI 缺少 `TEST_ARCH_DB_URL`/`TEST_EXEC_DB_URL`、JAR 残留旧静态资源、页脚版本 0.4.1；新增未绑定项目、导出幂等、执行归属和范围专项测试。
- 仍受限：真实私有 GitHub 令牌/仓库未验证；自动定时同步和 Docker 执行未启用；远端 GitHub 连接被重置且本机无 `gh`，未推送、未触发远端 CI。
