# ADR-003 产品内交互关系图

日期2026-11-15，用户已批准设计，交付目标0.4.1。

## 决策

采用@xyflow/react12.12.1承载产品内节点／边交互，Dagre3.1.3在Web Worker计算有向布局。GraphQueryService只读投影现有固定索引，保留符号身份、证据状态及源码位置。架构视图按Java包聚合，类型和一跳调用分别提供探索入口。

React Flow固定commit为1bc405cd113618c17fd181b931a9fe573bec9fee。Dagre原候选2.0.0的发布包未包含其声明的index.d.ts，改用同路线3.1.3，固定commit a73acbfebe6fda008e1a3d80f3738170699c3d6b，graphlib4.0.6。两者的发布包与传递依赖许可随JAR保存在META-INF/third-party/graph。

## 取舍

自研画布需维护缩放、焦点与布局。库依赖沿用现有React，源码提取或fork需要持续跟踪上游。Mermaid适用于后续文档图形，archify的独立HTML生成与导出另做适配。当前实现关注包／类型／局部调用探索。

Understand Anything的GraphBuilder用于参考节点／边分离、容器关系与去重思路。CodeAtlas使用既有符号UUID区分重载和重复声明，同时按generation固定关系证据。图模型由本机索引推导，无新增模型外发。

## 数据与恢复

图功能无需新增迁移。开发前基线已包含V5证据顺序与V6练习副本，构建及兼容验收保留这些已有迁移。包内和搜索子图提供边界计数，节点／边上限分别为200／1000。旧代次、未解析目标及部分索引保留实际含义。

React工作区按需加载，布局计算在隔离Worker进行，查询／布局迟到结果受身份保护。网络／布局异常保留等价列表入口。页面URL可恢复范围，sessionStorage保存本会话视口，源码跳转回到准确快照。

[React Flow固定源码](https://github.com/xyflow/xyflow/tree/1bc405cd113618c17fd181b931a9fe573bec9fee) · [Dagre固定源码](https://github.com/dagrejs/dagre/tree/a73acbfebe6fda008e1a3d80f3738170699c3d6b)
