# N04 容器生命周期修复记录

## 修复范围

2026-11-17，针对复核报告发现的运行中崩溃窗口及异常清理遗漏完成代码修复。

原实现等待 Docker 执行完成才记录容器名称和 ID，重启仅凭数据库 ID 清理。容器创建后进程退出时，ID 仍可能为空。另有异常返回路径跳过清理。

## 实现

- ExecutionService 在调用 Docker 前保存确定的任务容器名称。恢复使用服务器依据任务 ID 生成的精确名称，因此兼容旧任务名称或 ID 尚未写入的情况。
- ProcessDockerRunner 查询该名称对应的完整容器 ID 和 codeatlas.job 标签。只有完整 ID 格式和任务标签均匹配才按完整 ID 停止、删除，避免名称被重新使用时误删其他容器。
- 正常结束、超时、取消、启动异常和线程中断均进入 finally 清理。清理前终止尚在运行的 Docker 客户端及其子进程，线程中断标记在清理后恢复。
- Docker 辅助命令并行读取有界输出，超时或异常时销毁命令进程，避免输出管道阻塞。
- 查询明确报告容器不存在时记录 NOT_CREATED。Daemon 失联、标签不匹配和无法确认删除均记录 CLEANUP_FAILED。
- 保留 cidfile 用于执行结果记录。重启恢复采用确定名称加标签核验，无需新增数据库迁移。

## 回归范围

新增断言覆盖数据库无容器 ID 的恢复、精确标签核验、异属容器拒绝删除、Daemon 失联、启动异常清理、线程中断后的客户端销毁与中断标记恢复、删除后失联禁止误报成功。

复核命令使用 JDK 24，目标版本沿用项目配置。

```powershell
mvn -o -gs settings.xml -s settings.xml -Dtest=ControlFlowBuilderTest,ArchitectureIntegrationTest,ProcessDockerRunnerTest,ExecutionServiceIntegrationTest -DforkCount=0 test
```

最终回归结果为 28 项，0 失败、0 错误、0 跳过。执行日志保存在 CodeAtlas后端/target/n04-repair-regression.log。

## 验证边界

测试采用隔离 H2、模拟 Docker 命令和进程替身。真实 Docker 强制结束服务后的恢复、真实容器超时及平台隔离安全准入仍待独立验证。本次范围内未重新执行完整 MySQL 与浏览器 E2E 验收。

工作区包含其他开发者既有改动，本记录仅覆盖 execution 包的生命周期修复和对应测试。
