# CI执行脚本与GitProbe回归修正

日期为2026-11-16。本轮针对修复报告复核发现的路径错误、执行验证和导入级回归覆盖进行修正。

## 改动

- scripts/ci-local.ps1从自身位置定位后端，再定位项目根目录及相邻前端／AI。每一步显式设置工作目录。
- 新增CheckOnly路径检查；去除固定E盘JDK路径，JAVA_HOME存在时将其bin加入本次PATH。
- H2阶段清除遗留TEST_*覆盖，MySQL阶段使用专用库和调用者提供的测试账号密码。删除硬编码账号密码。脚本结束或失败后恢复环境和工作目录。
- 各步骤输出写入target/ci-local日志，命令非零退出后中止；Playwright显式启用JSON reporter。
- 新增scripts/ci-local-README.md，说明依赖、数据库及测试服务准备、跳过选项和远端覆盖范围。
- SnapshotScannerTest新增导入级测试，在扫描回调中修改导入子树外的已跟踪／未跟踪文件，验证导入成功、内容及快照身份稳定；随后修改子树内源码，仍返回SOURCE_CHANGED。
- 原fix-summary-gitprobe-ci报告已纠正“push是唯一剩余动作”的表述。

## 实际验证

1. PowerShell AST解析通过，从项目根目录及AI目录调用CheckOnly均得到正确三个工程路径。
2. 用刻意返回退出码17的Maven替身验证后端工作目录、H2不继承测试数据库账号、失败向上传播、环境与位置恢复。此检查属于执行脚本控制流测试。
3. SnapshotScannerTest在沙箱内首次运行有一项junction创建失败。随后获准沙箱外重跑，10项全部通过，0失败、0错误、0跳过，Maven BUILD SUCCESS。

Java命令在后端目录执行，使用JDK24、目标21、Maven离线设置和-Dtest=SnapshotScannerTest -DforkCount=0。没有重新执行全量Java／MySQL、Python、前端生产构建或浏览器E2E，也没有push或执行GitHub Actions。

## 剩余边界

本地CI需要调用者准备专用MySQL库、npm/Python依赖及配套E2E服务。远端workflow当前为后端H2、MySQL、YAML解析；前端、AI及E2E的远端集成继续待办。当前包保留前次GitProbe产品修复，本轮修改为执行脚本、测试及文档，未重新打包。
