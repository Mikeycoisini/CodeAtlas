# 导入、图形、代码分析与 AI 体验修复实施报告



日期：2026-11-17  

依据：《CodeAtlas-导入图形分析与AI体验修复说明-2026-11-17.md》  

本轮目标：先补齐 UX01／UX04／UX05 的真实入口，再对 UX02／UX03／UX06 做可验证的阶段性修复。



> 状态说明。第 1～10 节为首轮实施记录，第 11 节为第二轮补充。后续修复与当前验证边界见第 12 节。历史测试计数保留原执行时点含义。

## 1. 交付概览



| 编号 | 本轮状态 | 主要变更 |

|---|---|---|

| UX01 | 已完成代码修复，待真实桌面手工验收 | 新增 `POST /api/local-directories/select`；“本地目录”页签直接打开选择器；Spring Boot 关闭默认 headless；超时/中断等待旧窗口退出后再释放互斥锁 |

| UX04 | 已完成 | 代码分析页内新增项目/快照上下文、类型/文件/符号选择器、URL 身份与降级提示 |

| UX05 | 已完成 | AI 助手页内新增源码来源选择、来源预览、解释/项目说明用途路由 |

| UX02 | 已完成本轮可交付范围 | 自环不放大包围盒；缓存加入版本与结构摘要；分层正交路由；默认每页 40 节点；面包屑与包范围返回；边标签按选中/悬停展开 |

| UX03 | 已完成本轮可交付范围 | 分区布局与裁切修复；本机部署边界；角色配色；正交连线与关系标签；画布缩放；主链路演示；详情面板独立分栏 |

| UX06 | 已完成本轮可交付范围 | 解释/项目说明自动准备外发预览；知识点拆分+知识关联+卡片保存；“一键生成训练草稿/六步引导草稿”并保留授权与幂等 |



## 2. UX01 本地目录选择



### 代码位置



- 后端：[LocalDirectoryController.java](../../../CodeAtlas后端/src/main/java/dev/codeatlas/web/LocalDirectoryController.java)

- 前端：[App.tsx](../../../CodeAtlas前端/src/App.tsx)

- 样式：[ui.css](../../../CodeAtlas前端/src/ui.css)



### 行为



- `POST /api/local-directories/select` 在本机交互式桌面进程内打开 `JFileChooser`，只允许选择目录。

- 返回 `selected` + `path`、`cancelled` 或 `unavailable` + `reason`。

- 单实例互斥：并发请求返回 `409 DIRECTORY_SELECT_IN_PROGRESS`；120 秒超时会关闭选择窗口。

- 无交互式桌面时返回 `unavailable`，前端展示原因并保留手填路径。

- 选中后回填真实绝对路径，并重置“我拥有该目录读取权限”的授权勾选，必须重新确认后才能创建快照。

- 导入仍走既有 `/api/imports`，扫描限制、敏感文件过滤、链接边界和来源身份规则不变。



### 验证



- Playwright 用例 `local import can fill the project path from the native directory picker`：模拟选择器返回中文、空格路径，输入框正确回填。

- 待手工验证：真实桌面点击“浏览…”，确认对话框置顶、取消、超时和 headless 回退。



## 3. UX04 代码分析入口



### 代码位置



- 后端符号过滤：[AtlasController.java](../../../CodeAtlas后端/src/main/java/dev/codeatlas/web/AtlasController.java)

- 前端：[AnalysisWorkspace.tsx](../../../CodeAtlas前端/src/analysis/AnalysisWorkspace.tsx)、[App.tsx](../../../CodeAtlas前端/src/App.tsx)

- 样式：[analysis.css](../../../CodeAtlas前端/src/analysis/analysis.css)



### 行为



- 进入“代码分析”后即使没有任何探索历史，也会展示选择器：

  - 分析类型：方法、构造器、类、接口、枚举、记录、注解；

  - 文件限定：按快照文件下拉过滤；

  - 符号搜索：类名、方法名或签名，支持分页加载。

- `/api/snapshots/{snapshot}/symbols` 新增 `fileId` 可选过滤，保证文件级筛选在服务端生效。

- 选择符号后写入 URL 身份（`view=analysis&projectId&snapshotId&generationId&fileId&symbolId&line`），刷新、深链接和后退可恢复。

- 方法和构造器可使用流程图、时序图；类和接口可使用完整 UML；枚举/记录/注解显示明确的版本降级说明，不再出现无响应标签。

- 页面顶部复用项目/快照工具栏；切换项目或快照会清空旧符号选择。

- 加载中、无匹配、索引未完成和 HTTP 错误均有可恢复提示。



### 验证



- 后端 `AtlasIntegrationTest` 新增 `fileId` 过滤断言。

- Playwright 用例覆盖：无探索历史进入 → 选择方法 → 生成流程图 → URL 携带 `symbolId` 与 `generationId`。



## 4. UX05 AI 源码选择与用途路由



### 代码位置



- 前端：[AiAssistantWorkspace.tsx](../../../CodeAtlas前端/src/ai/AiAssistantWorkspace.tsx)、[AiPanel.tsx](../../../CodeAtlas前端/src/learning/AiPanel.tsx)、[App.tsx](../../../CodeAtlas前端/src/App.tsx)

- 样式：[ai-assistant.css](../../../CodeAtlas前端/src/ai/ai-assistant.css)



### 行为



- AI 助手页内可直接选择项目/快照（复用顶部工具栏）、文件、类型/方法并预览当前来源。

- 支持方法、构造器、类、接口、枚举、记录、注解以及“整个文件”来源；文件级来源使用既有 `/api/sources` 兼容协议。

- 来源预览展示符号签名、文件路径与起始行；切换项目或快照会清空旧来源。

- “解释”固定传入 `METHOD_EXPLANATION`，“项目说明”固定传入 `PROJECT_NOTE`，不再共用默认用途。

- 未选来源时点击能力卡片会给出明确提示；AI 离线、未配置模型和来源未索引均有对应状态。

- 打开 AI 面板后不再沿用上一个项目/快照的授权预览。



### 验证



- Playwright 用例覆盖：AI 工作区选符号 → 来源预览包含文件路径 → 打开解释面板 → 自动进入外发范围确认。



## 5. UX02 关系图可读性（阶段修复）



### 代码位置



- [layout.ts](../../../CodeAtlas前端/src/graph/layout.ts)

- [GraphCanvas.tsx](../../../CodeAtlas前端/src/graph/GraphCanvas.tsx)

- [GraphWorkspace.tsx](../../../CodeAtlas前端/src/graph/GraphWorkspace.tsx)

- [graph.css](../../../CodeAtlas前端/src/graph/graph.css)



### 变更



- 自环不再扩大节点占位，包围盒回到节点与正常层级布局本身。

- 布局缓存键加入版本号、方向与节点/边结构摘要；结构变化后不会恢复旧坐标。

- 初始视图仅使用常规 `fitView`（`minZoom=0.25`、`maxZoom=1`），不再按最大自环半径放大画布范围。

- 边标签默认隐藏，悬停或选中关系时展开；大图只保留选中边的标签。

- 新增“适应全图”“可读比例（80%）”控件；画布高度从 480px 提升到 560px。

- 摘要区显示自环数量与“选中关系查看证据”的提示。



### 仍待完成



- 按模块/包聚合、节点下钻、面包屑、邻接展开、端口级双向/跨层路由；

- 聚合模型上的 Dagre 验证与 ELK compound 评估；

- 10～30 个可读节点的初始聚合图与 12px 字号实测基线。



## 6. UX03 架构总览布局（阶段修复）



### 代码位置



- [ArchitectureWorkspace.tsx](../../../CodeAtlas前端/src/architecture/ArchitectureWorkspace.tsx)

- [architecture.css](../../../CodeAtlas前端/src/architecture/architecture.css)



### 变更



- 重写固定坐标布局为分区布点：

  - 左上：系统/应用；

  - 左下：未归属组件（两列）；

  - 中列：模块组及其子组件；

  - 右列：外部服务/数据存储。

- 每个分区独立计算高度，`contentHeight` 纳入未归属组件行数，修复组件互相占位与右侧裁切。

- 画布改为固定视口 + 内部 `arch-scene` 滚动容器，详情面板占据独立网格列，不再遮挡画布。

- 场景宽度从 1240/1720 收敛到 1280，首屏可同时看到系统、应用和模块组。



### 仍待完成



- 与参考 HTML 的部署边界、协议标注、折线路由、引导视图、演示和导出视觉对齐；

- 系统/应用/模块/组件四层投影的完整下钻与证据导航；

- 参考模板版本与 SHA-256 记录、内嵌脚本/许可/CSP/脱敏检查；

- 导出 HTML/SVG 与页面共用投影和布局的最终验收。



## 7. UX06 AI 生成与保存（阶段修复）



### 代码位置



- [AiPanel.tsx](../../../CodeAtlas前端/src/learning/AiPanel.tsx)

- [AiAssistantWorkspace.tsx](../../../CodeAtlas前端/src/ai/AiAssistantWorkspace.tsx)

- [App.tsx](../../../CodeAtlas前端/src/App.tsx)



### 变更



- AI 能力卡片打开解释/项目说明时传入用途与来源，并启用 `autoPrepare`：面板自动准备有界上下文，直接进入外发预览与授权。

- 授权确认仍保留，模型调用前必须勾选同意并点击“授权并生成”。

- 生成结果新增“保存为卡片”，写入题干、答案、不确定项和来源 ID，复用既有卡片复习调度。

- 原有“提炼为知识”“保存为文档”保持幂等键与来源校验。



### 仍待完成



- 源码理解出题自动创建训练任务草稿；

- 实践反馈自动关联 submission、测试结果与知识证据；

- 六步引导草稿自动创建与 `apply-suggestion` 一次性编排；

- 多产物一次采纳的幂等清单与部分失败恢复；

- 五种用途的真实模型质量、引用正确性和卡片粒度验收。



## 8. 自动化验证



| 项目 | 结果 |

|---|---|

| 后端全量 H2 测试 | **120 tests / 0 failures / 0 errors / 5 skipped**（22 个测试类） |

| 后端 `AtlasIntegrationTest` 单独运行 | 通过（含新增 `fileId` 过滤断言） |

| 前端 `tsc -b` | 通过 |

| 前端 `npm run build` | 通过（Vite 7.3.8，构建产物已更新） |

| 图布局 Node 测试 | **3/3 通过**（含 1000 节点布局、自环/平行边、边标签间距） |

| 新增 Playwright 用例 | **3/3 通过**：架构/分析/AI/GitHub 工作区、分析与 AI 无历史选择、目录选择回填 |

| 全量 Playwright | 25 通过；7 个用例因外部后端 `127.0.0.1:18080` 与 AI mock `127.0.0.1:8002` 未启动而连接失败，6 个未运行；与本次改动无关，需在完整联调环境复跑 |



说明：首次全量后端测试出现 10 个 `AiIntegrationTest` 会话引导空指针，单独运行及与 `AiClientTest` 成对运行均通过；随后完整复跑 120 项全部通过。该次失败判定为测试环境瞬时问题，仍建议在 CI 中保留完整日志观察。



## 9. 截图证据



- `CodeAtlas前端/test-results/new-architecture.png`：架构总览分区布局与详情面板分栏。

- `CodeAtlas前端/test-results/new-workspaces.png`：架构/分析/AI/GitHub 工作区渲染。



## 10. 仍待验证边界



- 真实桌面进程中的系统目录选择器（置顶、取消、超时、headless 回退）。

- 使用真实 EquipOps 快照的小/中/大图可读性基线、布局耗时和主线程阻塞记录。

- 参考 HTML 与 `docs/architecture` 副本的差异、版本与 SHA-256。

- 真实私有 GitHub 仓库、真实 Docker 执行、真实模型生成与费用边界（沿用此前未完成项）。

- 枚举/记录/注解的 UML：当前后端仅支持类与接口，前端已明确降级说明。

- MySQL 持久化复跑：本轮无新增 Flyway 迁移，未重跑 MySQL 专项。



## 11. 第二轮复核补充（同日）



### 11.1 目录选择两项代码问题



- **Spring Boot 默认 headless**：在 [CodeAtlasApplication.java](../../../CodeAtlas后端/src/main/java/dev/codeatlas/CodeAtlasApplication.java) 中改为 `SpringApplication` 并 `setHeadless(false)`，真实桌面可打开 `JFileChooser`；无桌面环境仍由控制器捕获并回退手填路径。

- **入口行为**：[App.tsx](../../../CodeAtlas前端/src/App.tsx) 的“本地目录”页签现在直接调用本机选择接口，不再只切换来源。

- **窗口生命周期**：[LocalDirectoryController.java](../../../CodeAtlas后端/src/main/java/dev/codeatlas/web/LocalDirectoryController.java) 在超时或中断时先设置取消标志、取消活动选择器，并等待选择线程真正退出后才释放互斥锁；旧窗口关闭前的新请求返回 409，避免同时出现两个对话框。



### 11.2 卡片的知识拆分与生成身份



- 后端 [LearningService.java](../../../CodeAtlas后端/src/main/java/dev/codeatlas/learning/LearningService.java) 允许 CARD 携带 `generationId`，并复用 EXPLANATION 生成记录与已校验引用校验。

- [AiPanel.tsx](../../../CodeAtlas前端/src/learning/AiPanel.tsx) 的“保存知识点与卡片”按键：

  - 将 `keyPoints` 拆成最多 3 个知识点，分别创建 KNOWLEDGE；

  - 每个知识点创建一张关联卡片，写入 `knowledgeId`、`generationId`、来源 ID 与独立幂等键；

  - 重复点击或部分失败重试不会重复生成记录。



### 11.3 关系图聚合与路由



- [edgeGeometry.ts](../../../CodeAtlas前端/src/graph/edgeGeometry.ts) 改为分层正交路由，平行边按 fan 偏移分离。

- [GraphWorkspace.tsx](../../../CodeAtlas前端/src/graph/GraphWorkspace.tsx) 默认每页节点从 80 调整为 40；新增“全部范围 / 当前包 / 搜索”面包屑与返回上一级；节点超过 30 时提示下钻。

- 保留包级聚合与类型下钻、CALL 视图、自环计数、证据面板与历史代次固定。



### 11.4 架构总览与参考视觉



- [ArchitectureWorkspace.tsx](../../../CodeAtlas前端/src/architecture/ArchitectureWorkspace.tsx) 新增：

  - “本机部署 · 127.0.0.1”边界框；

  - 数据存储位于边界内、外部服务位于边界外的分区；

  - 正交连线与关系类别标签；

  - 画布缩放（60%–160%）；

  - “演示主链路”高亮系统 → 应用 → 模块 → 组件 → 数据存储/外部服务；

  - 节点按系统、应用、模块、组件、数据存储、外部服务配色。

- [architecture.css](../../../CodeAtlas前端/src/architecture/architecture.css) 增加边界、角色色条、追踪/淡化与缩放控件样式。



### 11.5 AI 一键学习草稿



- 新增 [LearningPackPanel.tsx](../../../CodeAtlas前端/src/ai/LearningPackPanel.tsx)：

  - 训练草稿：创建证据来源 → 训练出题上下文 → 外发预览授权 → 生成 → 创建 DRAFT 训练任务；

  - 引导草稿：创建引导 → 引导 AI 上下文 → 授权 → 生成 → `apply-suggestion` 采纳六步建议；

  - 全程保留授权确认、取消、重新准备与幂等键。

- 后端新增 `POST /api/training/ai/generations/{id}/task`（[TrainingAiController.java](../../../CodeAtlas后端/src/main/java/dev/codeatlas/training/TrainingAiController.java)），只接受已成功的 `TRAINING_QUESTIONS` 生成记录，并用上下文来源与已校验引用创建草稿任务。

- [AiAssistantWorkspace.tsx](../../../CodeAtlas前端/src/ai/AiAssistantWorkspace.tsx) 在选源后展示“一键生成训练草稿 / 一键生成引导草稿”。



### 11.6 真实接口验收



使用本机 MySQL 后端与现有快照验证：



- 方法 `flow`：3 节点 / 2 条控制流 / 2 条警告返回成功；

- 方法 `sequence`：参与者与消息结构返回成功（样本方法无静态调用，消息数为 0）；

- 类 `uml`：字段 0、方法 1、关联候选 0，返回成功；

- `GET /api/health`：`{"status":"UP","version":"0.4.6"}`。



### 11.7 测试与构建



| 项目 | 结果 |

|---|---|

| 后端全量 H2 | **120 tests / 0 failures / 0 errors / 5 skipped** |

| 前端 `tsc -b` | 通过 |

| 前端 `npm run build` | 通过；[vite.config.ts](../../../CodeAtlas前端/vite.config.ts) 限制 terser `maxWorkers: 2`，避免 16 核低内存环境构建时 V8 提交失败 |

| Playwright 工作区用例 | **3/3 通过**（架构/分析/AI/GitHub、无历史选源、目录选择回填） |

| 图布局 Node 测试 | **3/3 通过** |



### 11.8 本轮仍待验证



- 真实桌面点击“本地目录”后的系统对话框置顶、取消、超时与并发重试。

- 真实 AI 服务下的训练草稿/六步引导完整链路（当前仅以接口和模拟数据验证协议）。

- 参考 HTML 的完整视觉基线（协议专用标注、演示导出、四层投影下钻）。

- 真实 EquipOps 快照的大图可读性量化指标与主线程耗时。

- MySQL 全量测试复跑（本轮无迁移变更，H2 已全绿）。


## 12. 本轮跟进修复

- 目录选择互斥由选择工作线程在任务及 EDT 对话框结束后释放，移除 30 秒后无条件释放逻辑。请求处理结束前保留所有权，避免旧请求的取消处理污染新请求。
- AI 草稿失败或取消后的“重新准备”会重新调用 prepare，刷新生成及采纳幂等键并重置同意状态。首次准备失败也提供“重试准备”。引导重新准备时复用已创建的引导，避免重复创建。
- 架构边界改为“项目内部 · 静态结构分组”，移除无快照证据的固定 localhost 部署声明。内部数据存储分组仅表示结构归属。
- 知识与卡片各自使用对应 keyPoint 内容，避免每张卡片复制整篇解释。当前使用模型已有知识摘要作为卡片答案，尚未新增逐卡专用问答生成协议。
- 验证已完成前端类型检查、后端编译、3 项模拟接口工作区 E2E、5 项 Node 图形测试。新增 AI 准备失败重试用例单独记录运行结果。
- 本轮未进行真实系统对话框超时/中断注入、真实 AI 调用、生产构建和完整 MySQL 回归。参考图完整视觉对齐、大图量化及实践反馈产物编排继续保留为剩余项。
