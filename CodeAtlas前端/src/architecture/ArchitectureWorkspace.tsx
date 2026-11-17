import { useEffect, useMemo, useState } from "react";
import { api, post } from "../api";
import useStudyLocation from "../study/useStudyLocation";
import type { EvidenceSource } from "../learning/learningApi";
import {
  architectureEdgeKinds,
  architectureKinds,
  architectureStatuses,
  type ArchitectureEdge,
  type ArchitectureEvidence,
  type ArchitectureExport,
  type ArchitectureNode,
  type ArchitecturePage,
} from "./architectureApi";
import "./architecture.css";

type Position = { x: number; y: number; w: number; h: number };
type GroupBox = { id: string; name: string; x: number; y: number; w: number; h: number };

function navigate(values: Record<string, string>) {
  const params = new URLSearchParams(location.search);
  params.set("view", "architecture");
  for (const [key, value] of Object.entries(values)) {
    if (value) params.set(key, value); else params.delete(key);
  }
  history.pushState(null, "", `?${params}`);
  dispatchEvent(new PopStateEvent("popstate"));
}

function layout(page: ArchitecturePage) {
  const positions = new Map<string, Position>();
  const groups: GroupBox[] = [];
  const modules = page.nodes.filter(node => node.kind === "MODULE");
  const components = page.nodes.filter(node => node.kind === "COMPONENT");
  const leaves = page.nodes.filter(node => ["EXTERNAL_SERVICE", "DATASTORE"].includes(node.kind));
  let cursor = 34;
  if (modules.length) {
    for (const module of modules) {
      const children = components.filter(node => node.parentId === module.id);
      const height = Math.max(112, children.length * 92 + 48);
      positions.set(module.id, { x: 590, y: cursor + 20, w: 210, h: 70 });
      groups.push({ id: module.id, name: module.name, x: 550, y: cursor - 8, w: 650, h: height });
      children.forEach((child, index) => positions.set(child.id, { x: 900, y: cursor + 10 + index * 92, w: 220, h: 78 }));
      cursor += height + 24;
    }
  }
  const unassigned = components.filter(node => !positions.has(node.id));
  unassigned.forEach((node, index) => {
    const column = index % 4;
    const row = Math.floor(index / 4);
    positions.set(node.id, { x: 70 + column * 280, y: 34 + row * 104, w: 220, h: 78 });
  });
  leaves.forEach((node, index) => {
    positions.set(node.id, { x: 900, y: cursor + index * 92, w: 220, h: 78 });
  });
  const contentHeight = Math.max(420, cursor + leaves.length * 92 + 50);
  const application = page.nodes.find(node => node.kind === "APPLICATION");
  const system = page.nodes.find(node => node.kind === "SYSTEM");
  const center = Math.max(80, contentHeight / 2 - 40);
  if (application) positions.set(application.id, { x: 310, y: center, w: 220, h: 78 });
  if (system) positions.set(system.id, { x: 40, y: center, w: 210, h: 78 });
  return { positions, groups, width: 1240, height: contentHeight };
}

function evidenceSource(page: ArchitecturePage, evidence: ArchitectureEvidence): EvidenceSource {
  return {
    id: evidence.symbolId ?? evidence.fileId,
    snapshotId: page.snapshotId,
    generationId: page.generationId ?? "",
    fileId: evidence.fileId,
    symbolId: evidence.symbolId ?? "",
    projectId: page.projectId,
    filePath: evidence.filePath,
    sha256: evidence.fileHash,
    startLine: evidence.startLine,
    endLine: evidence.endLine,
    symbolName: "",
    content: "",
    snapshotOutdated: false,
  };
}

function EvidenceList({ page, evidenceIds, onSource }: { page: ArchitecturePage; evidenceIds: string[]; onSource: (source: EvidenceSource) => void }) {
  const evidence = page.evidence.filter(item => evidenceIds.includes(item.id));
  if (!evidence.length) return <p className="arch-muted">没有直接源码证据；该节点来自结构推导。</p>;
  return <ul className="arch-evidence">{evidence.map(item => <li key={item.id}>
    <strong>{item.filePath}</strong><small> · L{item.startLine}–{item.endLine} · {item.rule}</small>
    <button onClick={() => onSource(evidenceSource(page, item))}>定位源码</button>
  </li>)}</ul>;
}

export default function ArchitectureWorkspace({ snapshotId, generationId, refreshRevision, onSource, theme }: {
  snapshotId: string; generationId: string; refreshRevision: number;
  onSource: (source: EvidenceSource) => void; theme: "light" | "dark";
}) {
  const params = useStudyLocation();
  const perspective = params.get("archPerspective") ?? "SYSTEM";
  const focusId = params.get("archFocus") ?? "";
  const depth = Number(params.get("archDepth") ?? "2");
  const limit = Number(params.get("archLimit") ?? "80");
  const [page, setPage] = useState<ArchitecturePage | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [query, setQuery] = useState("");
  const [selectedNode, setSelectedNode] = useState<ArchitectureNode | null>(null);
  const [selectedEdge, setSelectedEdge] = useState<ArchitectureEdge | null>(null);
  const [exporting, setExporting] = useState(false);

  useEffect(() => {
    setPage(null); setError(""); setNotice(""); setSelectedNode(null); setSelectedEdge(null);
    if (!snapshotId) return;
    const controller = new AbortController();
    const request = new URLSearchParams({ perspective, depth: String(depth), limit: String(limit) });
    if (generationId) request.set("generationId", generationId);
    if (focusId) request.set("focusId", focusId);
    api<ArchitecturePage>(`/snapshots/${snapshotId}/architecture?${request}`, { signal: controller.signal })
      .then(setPage)
      .catch(reason => { if (!controller.signal.aborted) setError(String(reason.message)); });
    return () => controller.abort();
  }, [snapshotId, generationId, perspective, focusId, depth, limit, refreshRevision]);

  const canvas = useMemo(() => page ? layout(page) : null, [page]);
  const needle = query.trim().toLowerCase();
  const visible = (node: ArchitectureNode) => !needle
    || `${node.name} ${node.responsibility} ${node.technology} ${node.kind}`.toLowerCase().includes(needle);
  const visibleIds = new Set(page?.nodes.filter(visible).map(node => node.id) ?? []);

  const selectNode = (node: ArchitectureNode) => {
    setSelectedEdge(null);
    if (node.kind === "SYSTEM") { navigate({ archPerspective: "SYSTEM", archFocus: "" }); return; }
    if (node.kind === "APPLICATION") { navigate({ archPerspective: "APPLICATION", archFocus: "" }); return; }
    if (node.kind === "MODULE") { navigate({ archPerspective: "MODULE", archFocus: node.id, archDepth: "2" }); return; }
    setSelectedNode(node);
  };

  const exportHtml = async () => {
    if (!page?.generationId || exporting) return;
    setExporting(true); setError(""); setNotice("");
    try {
      const result = await post<ArchitectureExport>("/architecture/exports", {
        snapshotId: page.snapshotId, generationId: page.generationId,
        projectionId: page.projectionId, digest: page.digest,
        format: "ARCHIFY_HTML", scope: perspective === "MODULE" ? "MODULE" : perspective === "APPLICATION" ? "APPLICATION" : "SYSTEM",
        theme, idempotencyKey: crypto.randomUUID(),
      });
      setNotice(`已生成 ${result.artifactName} · ${Math.round(result.artifactSize / 1024)} KB`);
      const session = await (await fetch("/api/session")).json() as { token: string };
      const response = await fetch(result.downloadUrl, { headers: { "X-CodeAtlas-Token": session.token } });
      if (!response.ok) throw new Error("导出文件下载失败");
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a"); anchor.href = url; anchor.download = result.artifactName; anchor.click();
      setTimeout(() => URL.revokeObjectURL(url), 2_000);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    } finally { setExporting(false); }
  };

  if (!snapshotId) return <main className={`arch-workspace ${theme}`}><h1>架构总览</h1><p>选择项目和源码快照后查看系统架构。</p></main>;
  return <main className={`arch-workspace arch-${theme}`}>
    <div className="arch-controls">
      <div><p className="arch-eyebrow">SYSTEM ARCHITECTURE</p><h1>架构总览</h1><p className="arch-muted">从固定快照识别系统、应用、业务模块、组件、外部服务与数据存储。</p></div>
      <div className="arch-actions">
        <label>视角<select aria-label="架构视角" value={perspective} onChange={event => navigate({ archPerspective: event.target.value, archFocus: "" })}>
          <option value="SYSTEM">系统</option><option value="APPLICATION">应用</option><option value="MODULE">模块</option><option value="COMPONENT">组件</option><option value="EXTERNAL">外部</option>
        </select></label>
        <label>深度<select aria-label="下钻深度" value={depth} onChange={event => navigate({ archDepth: event.target.value })}><option value="1">1</option><option value="2">2</option><option value="3">3</option></select></label>
        <label>每页<select aria-label="架构节点上限" value={limit} onChange={event => navigate({ archLimit: event.target.value })}><option value="40">40</option><option value="80">80</option><option value="160">160</option></select></label>
        <label>搜索<input aria-label="搜索架构节点" value={query} maxLength={120} onChange={event => setQuery(event.target.value)} placeholder="组件、职责、技术"/></label>
        {(focusId || perspective !== "SYSTEM") && <button onClick={() => navigate({ archPerspective: "SYSTEM", archFocus: "" })}>返回全局</button>}
        <button onClick={() => navigate({ archPerspective: perspective, archFocus: focusId })}>重新读取</button>
        <button className="arch-export" disabled={!page?.generationId || exporting} onClick={exportHtml}>{exporting ? "导出中…" : "导出 HTML"}</button>
      </div>
    </div>
    {error && <p className="arch-error" role="alert">{error}</p>}
    {notice && <p className="arch-notice" role="status">{notice}</p>}
    {!page ? !error && <p className="arch-muted">正在读取架构投影…</p> : <>
      <section className="arch-summary">
        <strong>{page.nodes.length} / {page.totalNodeCount} 节点 · {page.edges.length} / {page.totalEdgeCount} 关系</strong>
        <small>投影 {page.projectionId.slice(0, 8)} · 摘要 {page.digest.slice(0, 12)} · 生成器 {page.analyzerVersion}</small>
        <p>覆盖：文件 {page.coverage.files} · Java {page.coverage.javaFiles} · 配置 {page.coverage.configFiles} · 模块 {page.coverage.modules} · 组件 {page.coverage.components} · 外部 {page.coverage.externalServices} · 数据 {page.coverage.dataStores}</p>
        {page.truncated && <p className="arch-warning">结果已截断：还有 {page.omittedEdgeCount} 条关系或更多节点未展示，可缩小视角或提高上限。</p>}
        {!page.generationId && <p className="arch-warning">该快照尚无已发布索引。</p>}
      </section>
      <div className="arch-layout">
        <section className="arch-canvas" style={{ width: canvas!.width, height: canvas!.height }}>
          {canvas!.groups.map(group => visibleIds.has(group.id) || page.nodes.some(node => node.parentId === group.id && visibleIds.has(node.id)) ?
            <div className="arch-group" key={group.id} style={{ left: group.x, top: group.y, width: group.w, height: group.h }}><span>{group.name}</span></div> : null)}
          <svg className="arch-edges" width={canvas!.width} height={canvas!.height} aria-hidden="true">
            {page.edges.map(edge => {
              const source = canvas!.positions.get(edge.source); const target = canvas!.positions.get(edge.target);
              if (!source || !target || !visibleIds.has(edge.source) || !visibleIds.has(edge.target)) return null;
              const sx = source.x + source.w, sy = source.y + source.h / 2;
              const tx = target.x, ty = target.y + target.h / 2;
              return <path key={edge.id} className={`arch-edge ${edge.status.toLowerCase()}`} d={`M ${sx} ${sy} C ${sx + 70} ${sy} ${tx - 70} ${ty} ${tx} ${ty}`}
                onClick={() => { setSelectedNode(null); setSelectedEdge(edge); }}><title>{edge.kind} · {architectureStatuses[edge.status] ?? edge.status}</title></path>;
            })}
          </svg>
          {page.nodes.filter(node => visibleIds.has(node.id) && canvas!.positions.has(node.id)).map(node => {
            const position = canvas!.positions.get(node.id)!;
            return <button key={node.id} className={`arch-node kind-${node.kind.toLowerCase()} status-${node.status.toLowerCase()} ${selectedNode?.id === node.id ? "selected" : ""}`}
              style={{ left: position.x, top: position.y, width: position.w, height: position.h }} onClick={() => selectNode(node)}>
              <strong>{node.name}</strong><small>{architectureKinds[node.kind] ?? node.kind} · {architectureStatuses[node.status] ?? node.status}</small>
              <span>{node.responsibility}</span>
              {node.technology && <em>{node.technology}</em>}
            </button>;
          })}
        </section>
        <aside className="arch-side">
          {selectedNode ? <>
            <p className="arch-eyebrow">{architectureKinds[selectedNode.kind] ?? selectedNode.kind}</p><h2>{selectedNode.name}</h2>
            <p>{selectedNode.responsibility}</p><p className="arch-muted">{selectedNode.technology} · {architectureStatuses[selectedNode.status] ?? selectedNode.status}</p>
            <h3>证据</h3><EvidenceList page={page} evidenceIds={selectedNode.evidenceIds} onSource={onSource}/>
            {selectedNode.kind === "MODULE" && <button onClick={() => navigate({ archPerspective: "MODULE", archFocus: selectedNode.id, archDepth: "2" })}>下钻模块</button>}
          </> : selectedEdge ? <>
            <p className="arch-eyebrow">{architectureEdgeKinds[selectedEdge.kind] ?? selectedEdge.kind}</p><h2>关系证据</h2>
            <p>{page.nodes.find(node => node.id === selectedEdge.source)?.name} → {page.nodes.find(node => node.id === selectedEdge.target)?.name}</p>
            <p className="arch-muted">{architectureStatuses[selectedEdge.status] ?? selectedEdge.status} · {selectedEdge.reason}</p>
            <h3>证据</h3><EvidenceList page={page} evidenceIds={selectedEdge.evidenceIds} onSource={onSource}/>
          </> : <>
            <h2>如何阅读</h2><p>点击系统、应用或模块下钻；点击组件查看职责与源码证据。</p>
            <p className="arch-muted">架构总览使用固定快照推导；配置声明不等同于运行连接，AI 未参与本轮投影。</p>
            <details><summary>诊断 {page.diagnostics.length} 条</summary><ul>{page.diagnostics.map(item => <li key={item.code + item.message}><strong>{item.code}</strong> {item.message}{item.filePath && <small> · {item.filePath}</small>}</li>)}</ul></details>
          </>}
          <section className="arch-legend"><h3>状态</h3><p><i className="dot confirmed"/>已确认 · <i className="dot inferred"/>推断 · <i className="dot unresolved"/>未解析</p></section>
        </aside>
      </div>
    </>}
  </main>;
}
