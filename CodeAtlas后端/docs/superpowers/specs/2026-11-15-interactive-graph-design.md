# 产品内交互关系图设计

日期 2026-11-15。状态 用户已批准详细设计并授权直接完成开发。

## 用户选择与交付目标

用户希望导入项目后，在 CodeAtlas 内查看代码架构和关系，点击节点定位固定快照源码，并允许复用 GitHub 上的相应功能。已选择首批 React Flow + Dagre，完成架构／类关系／局部调用图，其他图形随后扩展。

对应 PRD EXP-03 P1、V01-06 后续增强与根目录《后续版本复用实施计划》的图谱展示方案。本轮拟交付0.4.1，作为项目探索增强版本。版本号为实施者提案。

交付三个视图。

- 包依赖视图，按 Java 包聚合现有类型与静态关系，显示关系类别、证据状态和数量。
- 类型关系视图，显示类、接口、继承、实现及方法调用聚合后的类型依赖。
- 局部调用视图，以用户选定的方法／构造器为中心，展示直接调用方、被调用方及相关未解析关系。首版一跳，用户可选择邻居重新居中探索。

流程图、时序图、完整 UML 属性／可见性／多重性展示及 archify 导出在后续扩展范围。当前设计使用已有静态索引表达包与类型依赖，业务模块语义及运行时调用由其实际证据状态描述。

## 现有代码与数据依据

- JavaAnalyzer.Symbol 保存类型／接口／方法／构造器、文件ID、限定名、签名及精确范围，旧代次可继续读取。
- Relation 保存 sourceId、可空 targetId、targetText、kind、status、reason、resolver和调用点范围。现有类别包括 CALL、RUNTIME_CALL、EXTENDS、IMPLEMENTS及事务候选，保留 CONFIRMED／INFERRED／UNRESOLVED。
- AtlasStore.generation 校验指定代次属于该快照且已发布，PARTIAL_SUCCESS 的已发布结果允许查询，文件诊断须展示。
- AtlasController 提供符号、文件和局部关系接口，数据库 relations.id 在代次内稳定，payload本身未携带此ID。
- React App 已有项目／快照选择、Monaco源码、代次绑定、来源跳转及浏览器历史恢复。新入口复用这些身份和跳转逻辑。
- 当前包模型、类型成员归属及全局图聚合接口需要补充查询适配。现有关系与分析规则继续作为数据来源。

## 方案比较与选择

1. React Flow + Dagre。用户已选择。React Flow提供可定制节点边、点击、缩放、拖拽和视口控制，Dagre负责有向布局。可以直接融入现有React主题和源码导航，需要编写证据适配和聚合逻辑。
2. Mermaid。已核查其类图和时序图能力，适合文档化图形及后续多图种输出。本轮交互探索采用第一条路线，Mermaid留作后续图形候选。
3. archify独立HTML适配。当前本机技能包具有JSON渲染、交互查看和导出能力，包标记private且采用Node CLI。后续单独验证工件生成、部署和源码跳转集成。

A全量自研画布需要维护交互和布局，B库依赖符合现有React技术栈，C外部图服务增加部署与数据边界，D源码提取及E fork增加升级维护。首版采用B库依赖与CodeAtlas自己的数据适配层。

## 固定候选及验证状态

- @xyflow/react 12.12.1，commit 1bc405cd113618c17fd181b931a9fe573bec9fee，MIT。已读取package.json、ReactFlow类型与README，声明React／ReactDOM >=17，涵盖当前React19。已核对onNodeClick、onEdgeClick、colorMode、fitView、nodesConnectable等接口。
- @dagrejs/dagre 2.0.0，tag v2.0.0指向commit 9f8ee8b4ea7f0d2e4f8a3d34653f23eda05fad8d，MIT，依赖@dagrejs/graphlib 2.2.4。已读取发布标签、package.json和layout实现，布局会更新传入图对象，应用输入应使用独立副本。
- 当前完成GitHub源码与声明检查，尚未安装候选或运行适配PoC。实施开始后先验证生产打包、实际发布包类型声明、循环／自环／重复边、React19交互、深浅主题及50／500／1000节点样本。
- 固定版本的发布包、依赖树和随附声明进入许可记录。若候选存在打包或声明问题，可在相同路线内选择通过验证的发布版本并记录原因、固定commit和测试结果。变更运行时路线或产品范围需更新设计。

[React Flow固定源码](https://github.com/xyflow/xyflow/tree/1bc405cd113618c17fd181b931a9fe573bec9fee) · [Dagre固定源码](https://github.com/dagrejs/dagre/tree/9f8ee8b4ea7f0d2e4f8a3d34653f23eda05fad8d)

## 查询与领域适配

新增只读GraphQueryService与GraphController，复用AtlasStore及快照／代次校验。图由固定索引推导，首版沿用V1～V4数据库结构，无持久化布局迁移需求。

GET /api/snapshots/{snapshot}/graph 接受generationId、view=PACKAGE|TYPE|CALL、query、packageId、focusSymbolId、statuses、kinds、offset、limit和edgeLimit。默认PACKAGE，节点limit80，上限200，edgeLimit300，上限1000。CALL要求明确中心方法／构造器ID并验证归属。所有类型、分页、类别和身份在服务端校验。

省略generationId时只在首次请求选择已发布代次，响应返回实际generationId，后续请求显式携带。索引重建期间继续显示当前固定代次，用户可明确切换到新版。无发布代次时返回可识别空状态，失败和未发布代次不能作为可用图。

节点包含id、kind、label、qualifiedName、source引用或成员定位入口、memberCount。source引用至少包含projectId、snapshotId、generationId、fileId、symbolId和范围，文件路径／哈希从同一快照元数据取得。

边包含id、source、target、kind、status、reason摘要、evidenceCount。同端点／类别／状态聚合，保留原始关系ID用于分页证据查询。相同端点不同状态分别展示，RUNTIME_CALL保留其独立含义。布局线型和颜色与状态文字共同表达，图例可核对。

响应包含view、projectId、snapshotId、generationId、nodes、edges、节点／边总数、当前显示数量、truncated、分页参数及文件诊断计数。节点数量指符号／包／占位节点数量，边数量指聚合后的关系组数量，evidenceCount指原始关系记录条数。边上限截断后保持节点身份稳定，分页详情仍能查询完整原始证据。

PACKAGE／TYPE采用范围内的诱导子图。TYPE选择packageId时，候选类型只属于所选包，包外类型不进入该画布及节点总数。跨出所选包或搜索范围的原始关系按outsideScopeRelationCount独立提示，用户可清除包／搜索条件或切回包依赖视图探索更广范围。首版包下钻保持明确的包内范围，此项为实施者选择。

PACKAGE／TYPE的query对范围内完整候选节点集合的名称／限定名／签名做大小写不敏感包含搜索，返回命中节点及两端都命中的边。搜索首版保持精确过滤语义，邻居探索由CALL或用户清除筛选完成。statuses／kinds只筛选边，符合节点范围与query的孤立节点继续显示。先确定候选节点，再筛选关系和聚合，再按稳定ID排序及节点分页。totalNodeCount是完整候选节点数，totalEdgeCount是该完整候选集合内的聚合边数。仅因节点分页未进入画布的原始关系用outsidePageRelationCount单独提示。

CALL始终保留中心节点，query只过滤邻居，statuses／kinds先过滤原始邻接关系，再据此形成邻居集合。offset仅针对按稳定ID排序的邻居，limit为包括中心在内的画布节点上限，CALL要求limit至少2，每页最多limit-1个邻居。返回neighborTotal和neighborLimit，totalNodeCount为中心加全部匹配邻居数量。无匹配邻居时仍显示中心及明确空结果。图只展示中心与当前页邻居之间的原始一跳关系，中心自环单独保留。中心身份不会被搜索、关系筛选或翻页移除。

补充GET /api/snapshots/{snapshot}/graph/edges/{edge}/evidence，用相同generationId及view定位聚合关系，返回分页的原始关系、源／目标定位和reason。边ID由代次、视图及聚合键稳定生成，服务端验证其对应集合。

包节点提供成员分页，下钻复用TYPE视图的packageId。类型节点详情可查看成员方法及进入局部调用图。成员列表复用现有符号查询语义，增加受校验的owner/package过滤或专用只读成员接口，具体路由由实施者决定并记录OpenAPI。

## 旧数据归属与未知目标

类型归属使用同文件精确行列范围和限定名。方法／构造器选择范围包含它的最内层类型，嵌套类型按实际层级处理。包名由顶层类型的规范限定名与类型名推导。旧代次采用同一规则，无需用户重建即可进入图。

同名重复声明保持不同symbolId。归属歧义、非规范限定名或缺少元数据时，标记“归属未知”并保留原符号和来源，禁止按短类名跨文件猜测。默认包独立显示，用户包名和文件名按文本渲染。

targetId为空的关系保留为带UNRESOLVED或原始状态的占位节点，ID由原始证据身份／聚合上下文推导。占位节点可查看调用点和reason。用户点击未知目标时进入证据详情，可跳回源调用点。来源相似的未知节点合并必须保留其证据集合和数量。

TYPE与PACKAGE视图将方法调用映射到所属类型／包。包内／类型内自关系可切换显示，原始证据继续可查。包之间的边表示该范围内存在索引关系，聚合计数对应原始证据条数。

## 规模、分页与计算

图接口在服务端聚合，前端只接收当前有界子图。CALL优先查询中心符号直接入边／出边，再读取实际邻居。PACKAGE／TYPE的聚合以固定代次数据为输入，采用有界读取或流式扫描，避免将整个代次源码发送到浏览器。

画布限制属于首版展示选择，可分页、筛选和重新居中。页面显示限定的包／类型／中心、搜索条件和边界数量。各类总数依据上述视图规则计算，提供对应HTTP计数回归。

后台推导若采用缓存，键至少包含snapshotId、generationId、适配器版本和查询范围，容量有界。缓存属于可选优化，PoC测量证明有必要时再引入。实际样本记录读取、聚合、布局、首次可交互和新增包大小，验收报告说明环境和限额。

## 页面与导航行为

新增“关系图”入口，保留项目／快照选择与索引状态。提供包依赖、类型关系和局部调用切换、搜索、关系类别／证据状态过滤、分页、方向切换、适应画布、缩放控制和定位中心。画布为只读关系浏览，节点拖拽仅影响本次浏览布局。

点击类型／方法节点定位同一快照和代次的源码。包节点点击下钻到该包类型，用户继续选择具体类型后定位。点击边打开证据明细并可跳转原始行号。方法列表可直接进入该方法局部调用图，源码符号详情提供“查看关系图”入口。

图状态URL使用view=graph、graphView、graphFocus、graphPackage、graphQuery、graphStatuses、graphKinds、graphOffset，并显式保存projectId／snapshotId／generationId。同一关系图身份的缩放和拖拽位置可在sessionStorage保存。刷新、浏览器后退／前进恢复相同查询与代次，跳源码后可通过历史返回原图。

图接口请求和异步布局均绑定项目／快照／代次／视图／筛选身份。更换任何身份后取消旧请求，迟到结果只作用于原身份。布局输入按稳定ID排序，每次创建独立Dagre图；循环和自环保留，布局异常显示可恢复错误及等价节点／证据列表。

深浅主题沿用CSS变量。画布与等价节点／边列表均支持键盘操作，焦点明确，状态包含文本。窄屏以纵向区域呈现，画布具有限定高度、缩放与平移，页面内容无横向溢出。

用户源码标签、reason及targetText采用React文本节点渲染。图查询只读取本机索引，现有会话token与同源规则继续生效。新增关系或AI推断需要另外的领域行为，本轮图数据直接映射既有证据。

## 实施验证与交付

独立可实施性检查已完成。检查提出TYPE跨包范围、query邻居语义及CALL中心分页三项问题，已由实施者按上述明确范围／过滤／中心保留规则补齐。相关默认选择随本设计交由用户审批。

1. 隔离适配验证React Flow固定版本、Dagre发布包／类型、React19、独立输入副本、环与自环、多状态边和不同规模布局。记录实际依赖树及MIT声明。
2. Java黄金样本覆盖包／类／嵌套类、默认包、重复短名、重载／构造器、继承实现、已确认调用、事务候选、未知及运行时关系、归属歧义。
3. HTTP测试覆盖跨快照／代次拒绝、旧代次恢复、无索引／部分索引、状态类别过滤、分页截断／计数、聚合证据分页和源码行号。先观察新增API缺失的失败，再实现并验证。
4. 浏览器覆盖导入索引→包→类型→源码→后退、方法→一跳入出边→证据源码，搜索／过滤／分页、重建后旧图、迟到查询／布局隔离、刷新恢复、键盘和深浅／窄屏。
5. 完整运行Java H2与专用MySQL、Python兼容测试、TypeScript／Vite及既有浏览器回归。测量生产构建新增分块与加载，图组件按需加载。
6. 完成一次独立实现审阅及重要问题回归，更新OpenAPI、需求映射、ADR-003、固定引用／许可、运行说明和验收报告。打包拟议0.4.1，分发配置采用已有环境变量模板，最终JAR／MySQL浏览器验证后交付。

当前无Git元数据，沿用用户现有目录和哈希基线交付。后续实现计划可决定组件拆分、内部聚合表示、查询索引、布局间距与必要的成员接口，保持上述快照、证据、分页和导航语义。

