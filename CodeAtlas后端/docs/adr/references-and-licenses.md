# V0.1 参考与依赖记录

核对日期为 2026-11-13。通过 Maven 或 npm 引入依赖，版本由 pom.xml 和 package-lock.json 固定。下表记录直接依赖及参考，完整传递依赖许可证由其随附元数据保留。

| 项目 | 使用方式 | 许可与来源 |
|---|---|---|
| Spring Boot 3.5.16 | Web、JDBC、测试与打包依赖 | Apache-2.0，官方 Maven 元数据 |
| JavaParser 3.27.1 | AST 和 Symbol Solver 依赖 | 项目声明 LGPL-3.0 与 Apache-2.0 双许可，采用 Apache-2.0 路线，已核对本地 parent POM |
| Flyway | 数据库迁移依赖 | 版本由 Spring Boot 管理，许可信息随 artifact 保留 |
| MySQL Connector/J 9.7.0 | 正式运行 JDBC 驱动 | 本地 POM 声明 GPL v2 with Universal FOSS Exception v1.0 |
| React、Monaco、Vite、TypeScript | 前端依赖 | 各包 LICENSE 随 npm 安装保留，锁定版本见 package-lock.json |
| Playwright | 浏览器验收测试依赖 | Apache-2.0，包随附许可 |
| H2 | 测试范围内的数据库依赖 | 随 artifact 保留许可，正式 MySQL 运行不依赖 H2 |

JavaParser 的官方入门文档用于确认解析器与符号解析入口，并以固定样本验证 UTF-8、依赖缺失和重复类型的实际行为。[JavaParser 文档](https://javaparser.org/getting-started.html)

GitNexus 仅阅读仓库 README，作为项目探索与图谱交互的参考。未运行该项目，也未复制其源码。本阶段采用局部关系列表和证据跳转，未引入其整体架构。[GitNexus 仓库](https://github.com/abhigyanpatwari/GitNexus)

官方 Spring PetClinic 作为真实 Spring Boot 静态验收样本，固定 commit 为 500158f732419217507c7656904b8e6aa1bcc0d6。本地检出源码中的 LICENSE.txt 为 Apache-2.0，版权与许可文件原样保留。样本置于工作区 .runtime/spring-petclinic，CodeAtlas 产品源码通过 API 读取其快照。PetClinic 构建及业务代码保持未执行状态。[固定版本源码](https://github.com/spring-projects/spring-petclinic/tree/500158f732419217507c7656904b8e6aa1bcc0d6)

CodeAtlas 自身的开源发布许可证尚未由用户确定。正式分发范围和传递依赖许可清单在发布阶段进一步核对。

V0.3 阅读 exercism/problem-specifications 的 hello-world/canonical-data.json，固定 commit `84a96854db1bda581317164d3e6ac6aa01723182`，MIT。参考稳定用例 UUID 和显式期望值，未新增依赖。[固定源码](https://github.com/exercism/problem-specifications/blob/84a96854db1bda581317164d3e6ac6aa01723182/exercises/hello-world/canonical-data.json) [MIT 许可](https://github.com/exercism/problem-specifications/blob/84a96854db1bda581317164d3e6ac6aa01723182/LICENSE)

V0.4 接入 io.github.open-spaced-repetition:fsrs:1.0.0，MIT，固定源码commit c561ee6c56239b621a0a45b16a6c2e688441eae5。许可证随包保存，21参数与算法配置固定，Jackson沿用产品BOM。六项隔离验证及产品回归见v0.4-fsrs-poc.md和v0.4-acceptance.md。[固定源码](https://github.com/open-spaced-repetition/java-fsrs/tree/c561ee6c56239b621a0a45b16a6c2e688441eae5)

V0.4.1 实际依赖@xyflow/react12.12.1与@dagrejs/dagre3.1.3，MIT，固定commit分别为1bc405cd113618c17fd181b931a9fe573bec9fee和a73acbfebe6fda008e1a3d80f3738170699c3d6b。发布包和22项依赖许可已收集至META-INF/third-party/graph，实际依赖清单随验收证据保存。Dagre2.0.0缺失声明文件，3.1.3提供发布类型，graphlib固定4.0.6。[React Flow](https://github.com/xyflow/xyflow/tree/1bc405cd113618c17fd181b931a9fe573bec9fee) [Dagre](https://github.com/dagrejs/dagre/tree/a73acbfebe6fda008e1a3d80f3738170699c3d6b)

本轮实际阅读Understand Anything的GraphBuilder，固定commit5cc4aad9b8b43596dfedaee00755a7223825fe9b，参考节点／边分离与去重，采用现有CodeAtlas符号身份和证据契约实现适配。[参考源码](https://github.com/Egonex-AI/Understand-Anything/blob/5cc4aad9b8b43596dfedaee00755a7223825fe9b/understand-anything-plugin/packages/core/src/analyzer/graph-builder.ts)

V0.4.2 公开 GitHub 仓库导入复用本机 Git CLI 与现有快照流水线，不新增第三方库，也未复制外部源码。若后续改用 JGit 等库，需固定版本、核查 EPL-2.0 等许可并单独提交 ADR。

V0.4.3 关系人工修正为自研追加式覆盖层，复用现有 JDBC、MutationService 与前端组件，不新增依赖或外部源码。
