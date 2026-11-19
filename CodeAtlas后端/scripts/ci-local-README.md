# 本地 CI 执行说明

从任意目录运行 scripts/ci-local.ps1，脚本基于自身位置计算后端及相邻前端／AI路径。使用 -CheckOnly 检查路径，-SkipMysql 跳过数据库阶段，-SkipE2E 跳过浏览器阶段。逐步输出和日志保存在后端 target/ci-local。

需先安装对应 JDK21及兼容版本、Maven、Node及npm依赖、Python虚拟环境。JAVA_HOME存在时优先使用其bin，其他情况沿用PATH中的Java。

MySQL阶段使用本机3307的专用v046_atlas、v046_ai、v046_order、v046_graph、v046_github、v046_relations、v046_progress、v046_learning、v046_guides、v046_review、v046_training库。调用前提供TEST_DB_USER及TEST_DB_PASSWORD并准备测试库与权限。脚本不管理数据库服务。测试会修改这些库，执行前确认它们专用于测试。

H2阶段清除调用者遗留的TEST_*覆盖，MySQL阶段使用明确的专用库配置。结束或失败后恢复调用者的环境变量和工作目录。每个原生命令非零退出即停止后续步骤。

E2E要求当前构建的后端与AI固定响应测试服务已运行在配套测试环境，默认地址18080。脚本负责运行测试，服务启动和测试数据准备由调用者按工程README完成。输出line和JSON两种报告。CI工作流当前覆盖后端H2、MySQL和YAML解析；前端、Python及E2E的远端纳管仍待后续集成。
