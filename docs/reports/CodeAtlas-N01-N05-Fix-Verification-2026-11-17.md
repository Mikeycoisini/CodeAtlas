# N01～N05修复复核

> 后续状态更新。N04 崩溃窗口及异常清理已完成代码修复，详见同目录 CodeAtlas-N04-Lifecycle-Fix-2026-11-17.md。以下内容保留修复前复核证据，当前状态以该修复记录为准。真实 Docker 验收仍待完成。

日期2026-11-17。对照新增能力缺陷修复开发说明、当前源码及独立修复报告。本次实际执行ControlFlowBuilderTest、ArchitectureIntegrationTest、ProcessDockerRunnerTest、ExecutionServiceIntegrationTest，合计22项，0失败、0错误、0跳过。沙箱内首轮启动受限，获准在沙箱外重跑后成功；Docker使用替身，未实际启动Docker或访问私有仓库。

## 当前状态

| 项 | 结论 | 边界 |
|---|---|---|
| N01 | 原核心问题已修 | normal／terminated／jump出口分离，8项通过；finally终止、模式switch等仍为显式限制 |
| N02 | 原无绑定问题已修 | check／sync统一验证bound，专项集成通过；真实私有仓库仍未测 |
| N03 | 请求内容幂等等核心问题已修 | V11、请求摘要、同键冲突、失败重试、工件检查及恢复已有；真实断电／多进程竞争覆盖仍待补证 |
| N04 | 部分修复，仍有关键遗漏 | 正常超时／取消清理和分类已加入，但运行中崩溃窗口未覆盖 |
| N05 | 提交身份核心问题已修 | 由submission推导归属、复制不可变存档并校验摘要，V12及幂等已有；真实Docker安全准入仍缺 |

## 尚存的N04问题

ExecutionService.runJob先调用runner.run(spec)，返回后才更新container_name和container_id。ProcessDockerRunner也在等待运行结束或超时／取消后读取cidfile。因此容器已创建但任务尚未结束时，数据库容器身份为空。

recoverInterrupted只读取数据库containerId，空值直接标NOT_CREATED，没有尝试读取该job的cidfile，也没有按已验证的固定容器名／label查找。服务进程在该窗口终止后，真实Docker容器可能持续运行。

现有restartRecoveryStopsOnlyTheRecordedContainer测试手工插入带container_id的RUNNING行，未覆盖真实运行中ID尚未落库的情况。需在启动前持久化确定的name／cidfile，在容器创建后及时保存ID，并在恢复时对job归属进行核验后从cidfile或受控名称／label恢复身份。新增“已创建容器、数据库ID为空、服务崩溃／重启”的断言和受控Docker试验。

ProcessDockerRunner.run通用catch返回CLEANUP_FAILED，但没有finally清理已启动进程／容器，读取ID或等待被中断等异常路径还需覆盖。恢复、取消和异常清理均限定本job对象。

## 文档范围剩余

SVG已新增，自研HTML／SVG路线已明确，真实archify外部集成仍未采用。真实私有仓库验证、自动定时同步、真实Docker准入、远端CI仍未完成。时序图动态语义、完整UML复杂边界和扫描缓存真实链接竞态测试保持限制，不能将整份任务书标为全部完成。

本轮没有修改业务实现。N04问题为源码静态确认的时序缺口，尚未真实Docker崩溃复现。完整Java／MySQL／E2E未在本轮重跑，22项专项通过仅覆盖其现有断言。
