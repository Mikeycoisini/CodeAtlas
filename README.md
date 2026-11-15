# CodeAtlas V0.4.1

本地 Java / Spring Boot 源码探索与学习工具。V0.2 新增授权 AI 解释、引用校验、可编辑文档、跨项目知识与学习卡片。支持目录导入、不可变源码快照、Java 声明与局部关系索引、符号搜索、源码定位、错误诊断、取消与重试，以及历史索引代次查询。

## 目录

后端工程位于 `D:\CodeAtlas\CodeAtlas后端`，前端工程位于 `D:\CodeAtlas\CodeAtlas前端`。原始规划文档保存在上级目录。设计、契约、固定样本与验收记录位于后端工程的 docs、contracts 和 test-fixtures。

## 运行环境

Java 21 或兼容 JDK，Maven 3.6.3 及以上，Node 22.12 及以上，MySQL 8.0，Python 3.11 及以上，以及用于读取来源信息的 Git。开发已使用 Windows 11、JDK 24.0.2、Node 24.15.0 和 MySQL 8.0.43 验证。解析语法级别固定为 Java 21。

## 首次启动

先在自己的 MySQL 实例中创建专用数据库，并配置有该库迁移和读写权限的账号。

```sql
CREATE DATABASE codeatlas CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
```

在 PowerShell 中进入后端目录，设置实际连接信息。密码通过本机环境变量提供。

```powershell
Set-Location 'D:\CodeAtlas\CodeAtlas后端'
$env:CODEATLAS_DB_URL = 'jdbc:mysql://127.0.0.1:3306/codeatlas?serverTimezone=UTC'
$env:CODEATLAS_DB_USER = 'codeatlas'
# 在本机设置 CODEATLAS_DB_PASSWORD 后继续
mvn -gs settings.xml -s settings.xml spring-boot:run
```

另一个 PowerShell 窗口启动前端。

```powershell
Set-Location 'D:\CodeAtlas\CodeAtlas前端'
npm.cmd ci --registry=https://registry.npmjs.org
npm.cmd run dev
```

浏览器打开 <http://127.0.0.1:5173>。点击“导入项目”，输入具有读取权限的绝对目录，确认授权后创建快照，再点击“开始索引”。通过“文件”或“符号”页签浏览源码，选择符号查看证据与关系。

可先导入 `D:\CodeAtlas\CodeAtlas后端\test-fixtures\spring-sample`。其中 Broken.java 故意包含语法错误，索引应为“部分成功”。搜索 `pay(String)`，选择 PaymentService 的重载方法可查看事务注解及调用证据。

## 打包与单进程运行

先构建前端，再构建后端。Maven 自动把相邻前端目录的 dist 打入 JAR。

```powershell
Set-Location 'D:\CodeAtlas\CodeAtlas前端'
npm.cmd run build
Set-Location 'D:\CodeAtlas\CodeAtlas后端'
mvn -gs settings.xml -s settings.xml verify
java -jar target/codeatlas-0.4.1.jar
```

保持数据库环境变量有效，打开 <http://127.0.0.1:8080> 使用完整应用。前端静态资源和 Monaco 均随构建提供。字体可使用本机回退字体。

## 配置

| 环境变量 | 默认值及用途 |
|---|---|
| CODEATLAS_DB_URL | 本机 3306 的 codeatlas 数据库 |
| CODEATLAS_DB_USER | codeatlas |
| CODEATLAS_DB_PASSWORD | 由本机提供 |
| CODEATLAS_PORT | 8080 |
| CODEATLAS_DATA | 当前后端目录下 data |
| CODEATLAS_ALLOWED_ROOTS | 空值表示接受用户在本机界面明确提交的目录，可用分号分隔限定根目录 |
| CODEATLAS_DEV_ORIGIN | http://127.0.0.1:5173 |

服务绑定 127.0.0.1。每次启动产生本机会话令牌。前端自动建立会话，手工 API 调用需先 GET /api/session，再通过 X-CodeAtlas-Token 传入令牌。数据目录有单实例文件锁，应配套使用独立数据库和同一数据目录。

## 快照与解析范围

- 默认最多 20,000 个文件、单文件 2 MiB、总计 256 MiB。超限导入失败并可诊断。
- 默认忽略 .git、.idea、.vscode、node_modules、target、build、dist、.gradle、.m2，以及 .env、.env.*、*.pem、*.key 和 *.p12 文件。
- 快照清单由相对路径、长度、文件 SHA-256 和策略版本构成。Git commit 与状态单独记录。
- 外部 Git 过滤器或子模块使工作区状态标记为 UNKNOWN，界面和数据保留原因。
- Java 解析提取类型、接口、方法、构造函数、注解和位置。继承与接口实现、编译期调用和运行时目标分别表达。
- CONFIRMED 对应可确定的源码声明目标。INFERRED 表示带规则名和运行时条件的候选。当前 SPRING_TRANSACTIONAL_DECLARATION_V1 为明确 Spring 事务方法注解生成 TRANSACTION_BOUNDARY 候选。UNRESOLVED 覆盖缺失依赖、重复类型、外部目标及动态实现。
- 编码支持 UTF-8。无效编码和语法错误按文件记录。外部 Maven 依赖、Lombok 生成成员、反射、Spring 代理与复杂运行时装配的关系可能未解析。
- 文件系统检查包含链接、重解析点、前后属性、内容哈希与二次扫描。导入时应使用稳定工作区，Windows 路径式访问仍存在恶意并发替换的系统边界，详见威胁模型。

## 测试

后端默认使用测试范围内的 H2 MySQL 模式，运行解析、快照、实际 HTTP 和 JDBC 集成测试。

```powershell
mvn -gs settings.xml -s settings.xml test
```

用专用 MySQL 测试库运行同一组测试时，可设置 TEST_DB_URL、TEST_DB_USER、TEST_DB_PASSWORD 和 TEST_DB_DRIVER，其中驱动为 com.mysql.cj.jdbc.Driver。测试会写入该库，请使用专用测试库。

前端端到端测试需要本机 Chrome、已运行的后端与 5173 前端，使用专用验收数据库。

```powershell
Set-Location 'D:\CodeAtlas\CodeAtlas前端'
npx.cmd playwright test
```

测试通过真实界面导入固定样本，并将临时样本写入后端 target。测试报告与截图位于前端 test-results。

## 验收状态

实际验证结果见 docs/reports/v0.1-acceptance.md。官方 Spring PetClinic 固定版本已完成真实项目静态验收，用户业务项目及常用数据库连接尚待提供。工作区内独立 MySQL 测试实例的数据属于开发验收数据。

## V0.2 AI 服务

Python 工程位于相邻的 CodeAtlasAI，安装和配置详见该目录 README.md。Java 与 Python 通过 CODEATLAS_AI_SERVICE_TOKEN 使用相同的本机令牌。Java 只持有内部服务令牌，Python 从本机环境变量或 CODEATLAS_LLM_ENV_FILE 指定的文件读取模型凭据。

首次配置好数据库并构建 JAR 后，可在上级目录运行以下命令。脚本生成当前进程使用的内部令牌并启动 Python，退出时清理子进程。

```powershell
./scripts/start-local.ps1 -JavaHome 'E:/jdk24' -LlmEnvFile 'D:/HealthAi-server/.env'
```

脚本路径为 D:/CodeAtlas/scripts/start-local.ps1。默认供应商 deepseek、模型 deepseek-flash，模型通过 CODEATLAS_LLM_MODEL 覆盖。账号实际模型列表已查询验证，参考配置中旧的 deepseek-chat 已调整。AI 服务独立运行，手工资料管理可在 AI 服务离线时使用。

| 环境变量 | 默认值及用途 |
|---|---|
| CODEATLAS_AI_URL | http://127.0.0.1:8001，仅允许本机服务 |
| CODEATLAS_AI_SERVICE_TOKEN | Java 与 Python 共用，启动脚本自动生成 |
| CODEATLAS_AI_TIMEOUT | Java 等待 AI 服务 70 秒 |
| CODEATLAS_LLM_ENV_FILE | Python 读取的本机凭据文件路径，可选 |
| CODEATLAS_LLM_PROVIDER | deepseek |
| CODEATLAS_LLM_MODEL | deepseek-flash |
| CODEATLAS_LLM_BASE_URL | https://api.deepseek.com |
| CODEATLAS_LLM_API_KEY | 本机模型密钥，可替代文件方式 |
| CODEATLAS_LLM_MAX_TOKENS | 4096，支持 128 至 4096 |
| CODEATLAS_LLM_TIMEOUT | Python 网络读取超时 60 秒 |

源码页选择方法并点击“解释源码”，填写问题、深度及用途，核对完整外发预览后授权生成。预览包括文件头和所选声明，最多六个来源、24,000 字符和 64 KiB，15 分钟后须重新准备。每次追问重新预览和授权。引用逐项显示校验结果，保存解释后可继续编辑、提炼知识和创建卡片。

文档、知识、卡片导航均提供手工新建、搜索、编辑和删除。来源选择器支持为一个知识关联不同项目。单次 AI 解释要求来源属于同一快照和索引代次。重新生成保存独立候选。版本冲突时保留当前草稿并显示提示，可复制草稿后刷新再合并。

将数据库和 CODEATLAS_DATA 目录配套备份及恢复，文件快照与数据库来源身份相互依赖。V2 迁移由 Flyway 自动执行，升级前先备份现有数据。中断中的生成任务在重启后标记 PROCESS_INTERRUPTED，已完成解释和学习资料继续保留。

## V0.2 验证

Python 测试命令为 `CodeAtlasAI/.venv/Scripts/python.exe -m unittest discover -s tests -v`，在 AI 工程中运行。完整浏览器学习测试需要运行 `tests/fake_provider.py` 和指向它的 AI 服务，均仅用于合成样本。真实模型联调使用公开 PetClinic，并在验收记录中单独记载。

MySQL 回归分别配置 TEST_DB_URL、TEST_LEARNING_DB_URL、TEST_AI_DB_URL 三个专用库，并设置 TEST_DB_USER、TEST_DB_PASSWORD 和 TEST_DB_DRIVER。默认 H2 测试可直接执行 Maven test。

当前 Windows 环境 Maven 默认使用 Java 17，项目需要 Java 21 及以上。可在当前终端设置 JAVA_HOME 和 PATH。JDK 24 的本机管道在默认用户临时目录出现错误时，使用短 ASCII 临时目录并同时设置 TEMP、TMP 和 JAVA_TOOL_OPTIONS 的 java.io.tmpdir。启动脚本已处理此项。

验收记录位于 docs/reports/v0.2-acceptance.md，API 位于 contracts/openapi.yaml，来源与授权规则位于 contracts/v0.2-learning-and-ai.md。

## 独立审阅差距修复

资料来源提供“检查当前源码”按钮。该操作只核验当前来源文件，单文件上限2 MiB，并遵守导入根目录和链接边界。结果可为一致、变化、缺失、不可访问、不安全路径或超限。普通查看资料显示“未检查当前源码”，检查结果标明时间，刷新后重新检查。存在较新已导入快照与当前文件是否变化各有独立含义。历史引用始终保留原快照。

Spring 事务候选规则仅覆盖方法上的明确单类型 import 或完整限定名 @Transactional，规则名为 SPRING_TRANSACTIONAL_DECLARATION_V1，状态为 INFERRED。当前方法作用域或继承成员中的同名声明、无法解析的继承及通配导入采用保守跳过。代理启用、调用路径和事务管理器仍需运行时验证。类级、组合注解和动态装配留待后续规则扩展。

分析器版本已提升为 javaparser-3.27.1-v2，现有项目点击“重建索引”后可查询新候选，旧代次继续可访问。个人掌握记录依已批准设计归属V0.4，KN-01当前实现覆盖知识及多项目来源。


## V0.3 训练任务与源码理解题

选择源码方法后点击“创建训练任务”，或从“训练”新建并关联来源。填写目标和验收条件，编辑单选、判断或开放题，保存草稿或发布。已发布任务可开始作答，支持保存回答、逐题跳过、整次跳过、提示与参考答案，以及重新作答。训练历史绑定开始时的任务版本和来源哈希。

客观题在服务器规则判分，开放题支持自评和复盘。AI 出题经预览授权生成可编辑候选，点击“追加到任务”后再发布。提交后可按题请求 AI 辅助反馈，并查看实际发送的题目、参考答案和回答。每次请求仍受已有预算与授权约束。

V03-01/V03-02 的训练任务与源码理解题契约见 contracts/v0.3-training.md。训练任务详情还可基于关联快照创建独立练习目录，在本地 IDE 修改后保存精确提交。提交保存完整文件内容、哈希和差异预览；外部测试结果由用户自行填写，始终标为 USER_REPORTED。关闭练习副本会清理未提交的目录内容，已保存提交与报告保留。该流程不执行练习代码；接口见 contracts/v0.3-practice.md。原始验收记录 docs/reports/v0.3-training-acceptance.md 为当时的历史范围。

Flyway 启动时自动应用 V3，已有 V1/V2 库可增量升级。生产库升级前应配套备份数据库和快照目录。正式单进程运行支持 JDK21 及以上，当前构建使用 JDK24、Java21目标。

本机 application.yml 可填写配置，也可引用环境变量。分发 JAR 的配置使用环境变量模板，运行时可通过工作目录的 config/application.yml 或 Spring Boot 外置配置参数指定本机配置。


## V0.4 复习、学习证据与引导

保存卡片后点击“加入复习”，或从复习队列选择卡片。先填写回忆，再查看答案并选择忘记、困难、记住、简单。评分由用户自报，HARD表示费力但仍正确回忆。系统保存原回忆、参考答案、调度前后状态与下一次时间。可暂停/恢复、跳过、按知识或标签筛选队列，并切换上海/UTC/纽约显示时区。

卡片实质内容改动后，通过“采用新版并重新学习”开始新轮次。个人笔记与标签调整可继续原调度。原轮次和软删除卡片的复习历史仍可查询。历史事件支持按身份直接打开。

“进度”展示当前知识版本的学习证据及判断依据。卡片复习自动归属尝试开始时关联的知识，理解题结果与自评可显式关联知识。支持用户个人记录、错误关联撤回和逐次判断历史。用户自报、规则判分与AI建议分别显示来源。知识改版后的证据按历史版本保留。

“引导”可独立创建，六个步骤自由调整、跳过、暂停、退出和恢复。编码/验证步骤可记录外部实践说明。AI调整建议先选择资料，再预览授权，候选由用户明确采纳。没有模型配置时可继续使用手工引导与复习。

FSRS在Java进程中计算，使用固定1.0.0库和关闭随机扰动的DEFAULT_V1配置。新增库已进入pom，既有安装环境继续使用原MySQL和Python服务。V4迁移会自动创建复习/证据/引导表，并为mutation_requests添加可空响应列。

运行中的服务需重启以加载本次代码。分发JAR以环境变量模板配置，IDE可使用本机源YAML。数据库与快照目录应配套备份。验收记录见 docs/reports/v0.4-acceptance.md，详细契约见 contracts/v0.4-review-evidence-guides.md。

## V0.4.1 产品内交互关系图

选择项目和快照后打开“关系图”，查看包依赖或类型关系。包节点下钻到包内类型，类型节点定位源码，“查看成员”可将方法设为一跳调用中心。源码符号详情也提供“查看关系图”入口。点击关系查看原始证据、reason、resolver和准确源码行号。

可按名称、限定名、证据状态和关系类别筛选，切换方向、自关系及分页，并使用缩放、平移和适应画布。包／类型的搜索展示命中节点间的关系，界面单独说明范围外和页外数量。调用中心始终保留，邻居可重新居中。图节点和等价列表使用相同身份。

图固定已发布索引代次，重新索引后可主动查看最新索引，浏览器后退可回到原图和原代次。图组件按需加载，Dagre在Worker布局，浏览器会话保存视口和拖拽位置，重新布局可恢复自动排布。

新增依赖React Flow12.12.1、Dagre3.1.3，引用及许可见docs/adr/references-and-licenses.md。图功能使用现有索引，数据库仍应用当前代码已有的V1～V6迁移。契约见contracts/v0.4.1-graphs.md，决策见docs/adr/ADR-003-interactive-graphs.md，验收见docs/reports/v0.4.1-acceptance.md。
