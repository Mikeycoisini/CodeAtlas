import { useEffect, useState } from "react";
import { api, type Page } from "../api";
import type { EvidenceSource } from "../learning/learningApi";
import useStudyLocation from "../study/useStudyLocation";
import GraphCanvas from "./GraphCanvas";
import { graphNavigate, sourceEvidence, evidenceStates, relationKinds, edgeKindLabels, correctionVerdicts, type GraphView, type GraphPage, type GraphNode, type GraphEvidence } from "./graphApi";
import "./graph.css";

function EvidencePanel({ snapshot, generation, edge, view, focus, onSource }: {
  snapshot: string; generation: string; edge: string; view: GraphView; focus: string; onSource: (source: EvidenceSource) => void;
}) {
  const [page, setPage] = useState<Page<GraphEvidence> | null>(null); const [offset, setOffset] = useState(0); const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    const params = new URLSearchParams({ generationId: generation, view, focusSymbolId: focus, limit: "10", offset: String(offset) });
    api<Page<GraphEvidence>>(`/snapshots/${snapshot}/graph/edges/${edge}/evidence?${params}`, { signal: controller.signal }).then(setPage)
      .catch(error => { if (!controller.signal.aborted) setError(String(error.message)); });
    return () => controller.abort();
  }, [snapshot, generation, edge, view, focus, offset]);
  return <section className="graph-evidence"><h3>原始关系证据</h3><p className="hint">图边保持原始分析；用户修正显示在对应证据条目中。</p>{error && <p role="alert">{error}</p>}
    {page?.items.map(item => <article key={item.relation.id} className="graph-evidence-item">
      <strong>{relationKinds[item.relation.kind]} · {evidenceStates[item.relation.status]}</strong><p>{item.relation.targetText}</p><p>{item.relation.reason}</p><small>{item.relation.resolver}</small>
      {item.latestCorrection && <div className={`relation-correction verdict-${item.latestCorrection.verdict.toLowerCase()}`}>
        <strong>{correctionVerdicts[item.latestCorrection.verdict] ?? item.latestCorrection.verdict}</strong>
        <p>{item.latestCorrection.reason}</p>
        {item.latestCorrection.source && <small>来源：{item.latestCorrection.source}</small>}
        {item.corrections && item.corrections.length > 1 && <small>共 {item.corrections.length} 条修正记录</small>}
      </div>}
      <button onClick={() => onSource(sourceEvidence(item.source))}>定位调用证据 · {item.source.filePath} · L{item.source.startLine}</button>
      {item.target && <button onClick={() => onSource(sourceEvidence(item.target!))}>定位声明目标 · {item.target.filePath} · L{item.target.startLine}</button>}
    </article>)}
    <div className="button-row"><button disabled={!offset} onClick={() => setOffset(value => Math.max(0, value - 10))}>上一页证据</button><button disabled={!page || offset + 10 >= page.total} onClick={() => setOffset(value => value + 10)}>下一页证据</button><small>共 {page?.total ?? 0} 条</small></div>
  </section>;
}

function Members({ snapshot, generation, typeId, onSource }: { snapshot: string; generation: string; typeId: string; onSource: (source: EvidenceSource) => void }) {
  const [page, setPage] = useState<Page<GraphNode> | null>(null); const [offset, setOffset] = useState(0); const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    api<Page<GraphNode>>(`/snapshots/${snapshot}/graph/types/${typeId}/members?generationId=${generation}&limit=20&offset=${offset}`, { signal: controller.signal }).then(setPage)
      .catch(error => { if (!controller.signal.aborted) setError(String(error.message)); });
    return () => controller.abort();
  }, [snapshot, generation, typeId, offset]);
  return <section className="graph-members"><h3>成员方法与构造器</h3>{error && <p role="alert">{error}</p>}
    {page?.items.map(node => <article key={node.id}><strong>{node.label}</strong><small>{node.qualifiedName}</small><div className="button-row">
      <button onClick={() => graphNavigate({ graphView: "CALL", graphFocus: node.id, graphPackage: "", graphQuery: "", graphOffset: "0", graphType: "", graphEdge: "" })}>以 {node.label} 为调用中心</button>
      {node.source && <button onClick={() => onSource(sourceEvidence(node.source!))}>查看成员源码</button>}
    </div></article>)}
    {page?.total === 0 && <p>未提取到直接成员。</p>}
    <div className="button-row"><button disabled={!offset} onClick={() => setOffset(value => Math.max(0, value - 20))}>上一页成员</button><button disabled={!page || offset + 20 >= page.total} onClick={() => setOffset(value => value + 20)}>下一页成员</button></div>
  </section>;
}

export default function GraphWorkspace({ projectId, snapshotId, generationId, refreshRevision, onGeneration, onSource, theme }: {
  projectId: string; snapshotId: string; generationId: string; onGeneration: (generation: string) => void;
  onSource: (source: EvidenceSource) => void; theme: "light" | "dark"; refreshRevision: number;
}) {
  const params = useStudyLocation();
  const view = (params.get("graphView") ?? "PACKAGE") as GraphView;
  const focus = params.get("graphFocus") ?? ""; const packageId = params.get("graphPackage") ?? "";
  const query = params.get("graphQuery") ?? ""; const offset = Number(params.get("graphOffset") ?? "0");
  const limit = Number(params.get("graphLimit") ?? "80"); const edgeLimit = Number(params.get("graphEdgeLimit") ?? "300");
  const statuses = params.get("graphStatuses") ?? Object.keys(evidenceStates).join(",");
  const kinds = params.get("graphKinds") ?? Object.keys(relationKinds).join(",");
  const includeSelf = params.get("graphSelf") !== "false";
  const directionParam = params.get("graphDirection");
  const direction = directionParam === "LR" || directionParam === "TB" ? directionParam : (view === "CLASS" || view === "STRUCTURE" ? "TB" : "LR");
  const selectedEdge = params.get("graphEdge") ?? ""; const selectedType = params.get("graphType") ?? "";
  const [page, setPage] = useState<GraphPage | null>(null); const [error, setError] = useState(""); const [notice, setNotice] = useState("");
  const [revision, setRevision] = useState(0);
  const request = new URLSearchParams({ view, query, packageId, focusSymbolId: focus, statuses, kinds, offset: String(offset), limit: String(limit), edgeLimit: String(edgeLimit), includeSelf: String(includeSelf) });
  if (generationId) request.set("generationId", generationId);
  const queryKey = request.toString(); const identity = `${projectId}|${snapshotId}|${queryKey}`;
  useEffect(() => {
    setPage(null); setError(""); setNotice("");
    if (!snapshotId || view === "CALL" && !focus) return;
    const controller = new AbortController();
    api<GraphPage>(`/snapshots/${snapshotId}/graph?${queryKey}`, { signal: controller.signal }).then(result => {
      if (controller.signal.aborted) return;
      setPage(result);
      if (!generationId && result.generationId) onGeneration(result.generationId);
    }).catch(error => { if (!controller.signal.aborted) setError(String(error.message)); });
    return () => controller.abort();
  }, [identity, revision, refreshRevision]);
  const navigate = (values: Record<string, string>) => graphNavigate({ graphOffset: "0", graphEdge: "", graphType: "", ...values });
  const selectNode = (node: GraphNode) => {
    if (node.kind === "ROOT") { setNotice("项目根节点仅用于展示包层级，请选择具体包。"); return; }
    if (node.kind === "PACKAGE") {
      if (view === "STRUCTURE" && node.memberCount === 0) { setNotice("该包没有直接类型，请选择子包查看。"); return; }
      navigate({ graphView: "TYPE", graphPackage: node.id, graphQuery: "", graphFocus: "" });
    }
    else if (node.source) onSource(sourceEvidence(node.source));
    else {
      const edge = page?.edges.find(edge => edge.target === node.id || edge.source === node.id);
      if (edge && edge.kind !== "CONTAINS") graphNavigate({ graphEdge: edge.id, graphType: "" });
      else setNotice("当前筛选下没有显示该节点的关系，可放宽筛选查看证据。");
    }
  };
  const selectEdge = (edgeId: string) => {
    const edge = page?.edges.find(item => item.id === edgeId);
    if (edge?.kind === "CONTAINS") { setNotice("包结构边是层级推导，没有对应源码关系证据。"); return; }
    graphNavigate({ graphEdge: edgeId, graphType: "" });
  };
  const toggle = (field: string, value: string, all: string) => {
    const selection = new Set(all === "NONE" ? [] : all.split(","));
    if (selection.has(value)) selection.delete(value); else selection.add(value);
    navigate({ [field]: [...selection].sort().join(",") || "NONE" });
  };
  if (!snapshotId) return <section className="graph-workspace"><h1>代码关系图</h1><p>选择项目和源码快照后查看关系。</p></section>;
  const step = view === "CALL" ? Math.max(1, limit - 1) : limit;
  const pageableTotal = page ? view === "CALL" ? page.neighborTotal : page.totalNodeCount : 0;
  return <main className="graph-workspace"><div className="graph-controls">
    <h1>代码关系图</h1><label>图形视图<select aria-label="图形视图" value={view} onChange={event => navigate({ graphView: event.target.value, graphPackage: "", graphFocus: "", graphQuery: "" })}>
      <option value="PACKAGE">包依赖</option><option value="TYPE">类型关系</option><option value="CLASS">类图</option><option value="STRUCTURE">项目结构图</option><option value="CALL">局部调用</option></select></label>
    <label>搜索图节点<input aria-label="搜索图节点" value={query} maxLength={200} onChange={event => navigate({ graphQuery: event.target.value })}/></label>
    <label>布局方向<select aria-label="布局方向" value={direction} onChange={event => graphNavigate({ graphDirection: event.target.value })}><option value="LR">从左到右</option><option value="TB">从上到下</option></select></label>
    <label>每页节点<select aria-label="每页节点" value={limit} onChange={event => navigate({ graphLimit: event.target.value })}><option value="20">20</option><option value="80">80</option><option value="200">200</option></select></label>
    <label>画布边上限<select aria-label="画布边上限" value={edgeLimit} onChange={event => navigate({ graphEdgeLimit: event.target.value })}><option value="300">300</option><option value="1000">1000</option></select></label>
    {packageId && view !== "STRUCTURE" && <button onClick={() => navigate({ graphPackage: "" })}>清除包范围</button>}
    <button onClick={() => setRevision(value => value + 1)}>重新读取当前代次</button>
    {view !== "STRUCTURE" && <>
      <fieldset><legend>证据状态</legend>{Object.entries(evidenceStates).map(([key, text]) => <label className="graph-check" key={key}><input type="checkbox" checked={statuses.split(",").includes(key)} onChange={() => toggle("graphStatuses", key, statuses)}/>{text}</label>)}</fieldset>
      <fieldset><legend>关系类别</legend>{Object.entries(relationKinds).map(([key, text]) => <label className="graph-check" key={key}><input type="checkbox" checked={kinds.split(",").includes(key)} onChange={() => toggle("graphKinds", key, kinds)}/>{text}</label>)}</fieldset>
      <label className="graph-check"><input type="checkbox" checked={includeSelf} onChange={event => navigate({ graphSelf: String(event.target.checked) })}/>显示自关系</label>
    </>}
  </div>
    {error && <p className="graph-error" role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    {view === "CALL" && !focus ? <p>请从类型成员或源码方法选择调用中心。</p> : !page ? !error && <p role="status">正在读取固定代次关系…</p> : <>
      <section className="graph-summary"><strong>{page.nodes.length} / {page.totalNodeCount} 节点 · {page.edges.length} / {page.totalEdgeCount} 组关系</strong>
        <small>代次 {page.generationId ?? "尚无已发布索引"} · {page.diagnosticCount} 条文件诊断</small>
        {(packageId || query) && <p>当前按{packageId ? "所选包" : "节点搜索"}限定范围，匹配节点间的关系显示在画布中。</p>}
        {view === "STRUCTURE" && <p>项目结构图按 Java 包层级展示，边为推导的包含关系，不读取源码关系证据。</p>}
        {page.truncated && <p>范围外关系 {page.outsideScopeRelationCount} 条 · 页外关系 {page.outsidePageRelationCount} 条 · 超出边上限 {page.omittedEdgeCount} 组</p>}
        {!page.generationId && <p>运行索引后可查看关系图。</p>}
      </section>
      {page.nodes.length > 0 ? <GraphCanvas key={identity} page={page} identity={identity} theme={theme} direction={direction} center={focus} onNode={selectNode} onEdge={selectEdge}/> : <p>当前范围没有匹配节点。</p>}
      <div className="button-row"><button disabled={!offset} onClick={() => graphNavigate({ graphOffset: String(Math.max(0, offset - step)), graphEdge: "", graphType: "" })}>上一页节点</button><button disabled={offset + step >= pageableTotal} onClick={() => graphNavigate({ graphOffset: String(offset + step), graphEdge: "", graphType: "" })}>下一页节点</button></div>
      {view === "CALL" && focus && <button onClick={() => document.querySelector(`[data-graph-node="${focus}"]`)?.scrollIntoView({ block: "center" })}>定位中心记录</button>}
      <div className="graph-records"><section className="graph-node-list"><h2>节点与源码</h2>{page.nodes.map(node => <article key={node.id} data-graph-node={node.id}>
        <button aria-label={`打开节点 ${node.label}`} onClick={() => selectNode(node)}>{node.label}</button><small>{node.qualifiedName} · {node.kind}{node.ownershipUnknown && " · 归属或目标待确认"}</small>
        {node.source && <small>{node.source.filePath} · L{node.source.startLine}</small>}
        {["CLASS", "INTERFACE"].includes(node.kind) && <button aria-label={`查看成员 ${node.label}`} onClick={() => graphNavigate({ graphType: node.id, graphEdge: "" })}>查看成员 · {node.memberCount}</button>}
        {view === "CALL" && ["METHOD", "CONSTRUCTOR"].includes(node.kind) && node.id !== focus && <button onClick={() => navigate({ graphFocus: node.id, graphQuery: "" })}>以 {node.label} 为调用中心</button>}
      </article>)}</section>
      <section className="graph-edge-list"><h2>关系与证据</h2>{page.edges.map(edge => <article key={edge.id} className={`graph-edge-${edge.status.toLowerCase()}`}>
        <strong>{page.nodes.find(node => node.id === edge.source)?.label} → {page.nodes.find(node => node.id === edge.target)?.label}</strong>
        <small>{edgeKindLabels[edge.kind]} · {evidenceStates[edge.status]} · {edge.evidenceCount} 条</small><p>{edge.reason}</p>
        {edge.kind !== "CONTAINS" && <button onClick={() => graphNavigate({ graphEdge: edge.id, graphType: "" })}>查看关系证据</button>}
      </article>)}</section></div>
      {selectedEdge && page.generationId && <EvidencePanel key={`${identity}-${selectedEdge}`} snapshot={snapshotId} generation={page.generationId} edge={selectedEdge} view={view} focus={focus} onSource={onSource}/>}
      {selectedType && page.generationId && <Members key={`${identity}-${selectedType}`} snapshot={snapshotId} generation={page.generationId} typeId={selectedType} onSource={onSource}/>}
    </>}
  </main>;
}
