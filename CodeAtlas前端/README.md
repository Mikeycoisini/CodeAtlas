# CodeAtlas 前端

React、TypeScript、Vite 与只读 Monaco。配套后端与完整运行说明位于相邻的 CodeAtlas后端/README.md。

```powershell
npm.cmd ci --registry=https://registry.npmjs.org
npm.cmd run dev
```

打开 <http://127.0.0.1:5173>。API 代理至本机 8080。生产构建使用 `npm.cmd run build`，后端 Maven 构建会将 dist 打入可运行 JAR。

端到端验证使用 `npx.cmd playwright test`，需要已运行的前后端、专用测试数据库与本机 Chrome。

V0.2 包含文档、知识、卡片与源码解释预览授权界面。完整学习流程测试 tests/learning.spec.ts 需要后端、Python AI 服务和本机确定性测试提供商，启动方法见相邻 CodeAtlasAI/README.md。PLAYWRIGHT_BASE_URL 可设置为 http://127.0.0.1:8080 以验证打包界面。

V0.3训练首批提供训练任务编辑、作答历史、规则判分、自评、AI出题与辅助反馈。tests/training.spec.ts 覆盖手工与AI流程、缓存冲突恢复和迟到响应隔离，使用独立数据库与本机固定响应服务运行。

训练任务详情现可创建独立代码练习副本、保存代码提交并录入用户在外部环境得到的测试报告。关闭副本时会提示确认并清理工作目录，已保存提交保留。报告仅标记为用户提供，界面不会执行项目代码。

V0.4新增复习、进度和引导工作区。review.spec.ts、progress-guides.spec.ts与study-loop.spec.ts覆盖评分历史、证据、引导恢复及完整学习闭环，继续使用本机确定性上游进行回归。

V0.4.1新增按需加载的关系图工作区，复用React Flow12.12.1和Dagre3.1.3。tests/graph.spec.ts覆盖包下钻、类型／调用关系、固定快照源码、旧代次恢复及迟到查询／布局隔离。node --test tests/graph-layout.test.mjs验证纯布局、输入隔离和50／500／1000节点样本。

V0.4.2在导入弹窗中增加“本地目录 / GitHub 仓库”来源切换。GitHub 来源提交仓库根地址与可选分支/标签，由后端受控浅克隆并复用现有快照流水线；前端不接收或保存凭据，仅支持公开仓库。后端契约与验收见相邻 CodeAtlas后端/contracts/v0.4.2-github-import.md 和 docs/reports/v0.4.2-github-import-acceptance.md。

V0.4.3在源码关系卡片增加“修正关系”入口、原始值与生效值并存、修正理由/来源/时间和历史记录。图谱证据条目显示对应修正，但图边保持原始分析。后端契约见相邻 CodeAtlas后端/contracts/v0.4.3-relation-corrections.md。

V0.4.4将局部关系与文件诊断改为每页 200 条加载，显示已加载/总数并提供“加载更多”。tests/pagination.spec.ts 覆盖 201 条诊断与关系、剩余条数提示和加载完成后按钮消失。

V0.4.5 在代码练习面板为代码提交和用户测试报告增加“关联知识”入口。进度视图显示实践证据、通过次数和限制说明；提交本身不单独计入掌握，ERROR/TIMEOUT 不计为能力失败。后端契约见相邻 CodeAtlas后端/contracts/v0.4-review-evidence-guides.md。

V0.4.6 在代码练习提交上增加“请求 AI 反馈”。预览任务目标、代码差异、用户外部测试报告和源码并授权后生成；反馈为 AI 辅助建议，不改规则判分、测试报告或掌握状态。后端契约见相邻 CodeAtlas后端/contracts/v0.3-practice.md。
